// Unit tests for the portable engine core. No framework dependency.
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <thread>
#include <vector>

#include "noti/clock.h"
#include "noti/cpu_renderer.h"
#include "noti/display_timing.h"
#include "noti/engine.h"
#include "noti/geometry.h"
#include "noti/latency_probe.h"
#include "noti/predictor.h"
#include "noti/spsc_ring.h"
#include "noti/wake_event.h"

using namespace noti;

namespace {

int g_failures = 0;
const char* g_test = "";

#define CHECK(cond)                                                                   \
  do {                                                                                \
    if (!(cond)) {                                                                    \
      std::printf("  FAIL %s:%d [%s] %s\n", __FILE__, __LINE__, g_test, #cond);      \
      ++g_failures;                                                                   \
    }                                                                                 \
  } while (0)
#define CHECK_NEAR(a, b, tol)                                                                          \
  do {                                                                                                 \
    const double _a = (a), _b = (b);                                                                   \
    if (!(std::fabs(_a - _b) <= (tol))) {                                                              \
      std::printf("  FAIL %s:%d [%s] %s=%g vs %s=%g (tol %g)\n", __FILE__, __LINE__, g_test, #a, _a, #b, \
                  _b, double(tol));                                                                    \
      ++g_failures;                                                                                    \
    }                                                                                                  \
  } while (0)

struct TestCase { const char* name; std::function<void()> fn; };
std::vector<TestCase>& registry() { static std::vector<TestCase> r; return r; }
struct Reg { Reg(const char* n, std::function<void()> f) { registry().push_back({n, std::move(f)}); } };
#define TEST(name) \
  static void name(); \
  static Reg reg_##name(#name, name); \
  static void name()

InkSample sample(Action a, int64_t t_ms_x1000, float x, float y, float p = 0.6f) {
  InkSample s{};
  s.t_ns = t_ms_x1000 * 1000;  // input in µs
  s.t_recv_ns = s.t_ns;
  s.x = x;
  s.y = y;
  s.pressure = p;
  s.action = a;
  return s;
}

// ---------------------------------------------------------------------------

TEST(ring_basic_and_wraparound) {
  SpscRing<int, 8> r;
  int out[16];
  CHECK(r.empty_acquire());
  int seq = 0, expect = 0;
  for (int round = 0; round < 100; ++round) {
    int in[5];
    for (int& v : in) v = seq++;
    CHECK(r.try_push(in, 5) == 5);
    const size_t n = r.pop(out, 16);
    CHECK(n == 5);
    for (size_t i = 0; i < n; ++i) CHECK(out[i] == expect++);
  }
  int big[10] = {};
  CHECK(r.try_push(big, 10) == 8);  // partial push when nearly full
  CHECK(r.try_push(big, 1) == 0);
  CHECK(r.pop(out, 3) == 3);
  CHECK(r.try_push(big, 10) == 3);
}

TEST(ring_two_thread_stress) {
  static SpscRing<uint64_t, 256> r;
  constexpr uint64_t kN = 2'000'000;
  std::atomic<bool> ok{true};
  std::thread consumer([&] {
    uint64_t expect = 0, buf[64];
    while (expect < kN) {
      const size_t n = r.pop(buf, 1 + (expect % 64));
      for (size_t i = 0; i < n; ++i)
        if (buf[i] != expect++) ok = false;
    }
  });
  uint64_t next = 0, buf[64];
  while (next < kN) {
    const size_t want = std::min<uint64_t>(1 + (next % 37), kN - next);
    for (size_t i = 0; i < want; ++i) buf[i] = next + i;
    next += r.try_push(buf, want);
  }
  consumer.join();
  CHECK(ok.load());
}

TEST(wake_event_no_lost_wakeups) {
  // Consumer sleeps whenever the ring is empty; producer pushes in bursts with
  // pauses. A lost wake-up would hang (caught by the deadline).
  static SpscRing<uint32_t, 64> r;
  WakeEvent ev;
  constexpr uint32_t kN = 100'000;
  std::atomic<uint32_t> got{0};
  std::atomic<bool> stop{false};
  std::thread consumer([&] {
    uint32_t buf[64];
    while (!stop.load()) {
      const size_t n = r.pop(buf, 64);
      if (n) { got += uint32_t(n); continue; }
      ev.prepare_wait();
      if (!r.empty_acquire() || stop.load()) { ev.cancel_wait(); continue; }
      ev.wait();
    }
  });
  for (uint32_t i = 0; i < kN; ++i) {
    while (!r.try_push(i)) std::this_thread::yield();
    ev.notify();
    if (i % 1000 == 0) std::this_thread::sleep_for(std::chrono::microseconds(50));
  }
  const int64_t deadline = now_ns() + 5'000'000'000LL;
  while (got.load() < kN && now_ns() < deadline) std::this_thread::sleep_for(std::chrono::milliseconds(1));
  CHECK(got.load() == kN);
  stop = true;
  ev.notify();
  consumer.join();
}

TEST(wake_event_timed_wait) {
  WakeEvent ev;
  ev.prepare_wait();
  const int64_t t0 = now_ns();
  const bool woke = ev.wait_for(2'000'000);
  const int64_t el = now_ns() - t0;
  CHECK(!woke);
  ev.cancel_wait();
  CHECK(el >= 1'500'000);
}

// ---------------------------------------------------------------------------

// Brute-force distance to the union of discs swept along the capsule.
double brute_sdf(double px, double py, const Capsule& c) {
  double best = 1e30;
  for (int i = 0; i <= 20000; ++i) {
    const double t = i / 20000.0;
    const double cx = c.x0 + (c.x1 - c.x0) * t, cy = c.y0 + (c.y1 - c.y0) * t;
    const double r = c.r0 + (c.r1 - c.r0) * t;
    best = std::min(best, std::hypot(px - cx, py - cy) - r);
  }
  return best;
}

TEST(capsule_sdf_matches_brute_force_outside) {
  const Capsule caps[] = {
      {10, 10, 3, 40, 20, 3},     // uniform
      {10, 10, 6, 40, 12, 1.5f},  // strongly tapered
      {0, 0, 1, 0.5f, 0, 4},      // one disc almost contains the other
      {5, 5, 2, 5, 5, 2},         // dot
      {0, 0, 5, 3, 0, 1},         // fully degenerate (r0 - r1 > length)
  };
  for (const Capsule& c : caps) {
    for (int i = 0; i < 400; ++i) {
      const float px = -15.0f + float(i % 20) * 4.0f, py = -15.0f + float(i / 20) * 3.0f;
      const float sd = capsule_sdf(px, py, c);
      const double bf = brute_sdf(px, py, c);
      if (bf > 0.05) CHECK_NEAR(sd, bf, 0.02 + 0.002 * bf);
      else if (bf < -0.05) CHECK(sd < 0.0f);
    }
  }
}

TEST(pressure_curve_monotonic) {
  PressureCurve c;
  float prev = -1;
  for (int i = 0; i <= 1000; ++i) {
    const float v = c(float(i) / 1000.0f);
    CHECK(v >= prev);
    prev = v;
  }
  CHECK_NEAR(c(0.0f), 0.12f, 1e-5);
  CHECK_NEAR(c(1.0f), 1.0f, 1e-5);
  CHECK_NEAR(c(2.0f), 1.0f, 1e-5);  // clamps
}

TEST(rect_math) {
  RectF f;
  CHECK(f.empty());
  f.add(Capsule{10, 10, 2, 20, 15, 3});
  const RectI r = pixel_bounds(f, 100, 100);
  CHECK(r.x0 <= 7 && r.y0 <= 7 && r.x1 >= 24 && r.y1 >= 19);
  CHECK(pixel_bounds(f, 12, 12).x1 == 12);  // clipped
  CHECK((RectI{0, 0, 2, 2}.united(RectI{5, 5, 6, 6}) == RectI{0, 0, 6, 6}));
  CHECK((RectI{0, 0, 2, 2}.intersected(RectI{5, 5, 6, 6}).empty()));
}

// ---------------------------------------------------------------------------

TEST(histogram_percentiles) {
  Histogram h;
  for (uint64_t v = 1; v <= 10000; ++v) h.record(v * 1000);  // 1 µs .. 10 ms
  CHECK(h.count() == 10000);
  CHECK_NEAR(double(h.percentile(50)), 5e6, 5e6 * 0.07);
  CHECK_NEAR(double(h.percentile(99)), 9.9e6, 9.9e6 * 0.07);
  CHECK(h.max() == 10'000'000);
  for (uint64_t v : {0ull, 63ull, 64ull, 1000ull, 1ull << 30, ~0ull}) {
    const int b = Histogram::bucket_of(v);
    CHECK(b >= 0 && b < Histogram::kBuckets);
  }
}

// ---------------------------------------------------------------------------

void feed_line(StrokePredictor& p, double vx, double vy, int n, double period_ms = 1000.0 / 240.0) {
  for (int k = 0; k < n; ++k) {
    const double t = k * period_ms;
    p.add(int64_t(t * 1e6), float(100 + vx * t), float(200 + vy * t), 0.6f);
  }
}

TEST(predictor_constant_velocity_is_exact) {
  for (PredictorModel m : {PredictorModel::kLinear, PredictorModel::kPolynomial, PredictorModel::kKalman}) {
    PredictorConfig c;
    c.model = m;
    c.horizon_ms = 10;
    StrokePredictor p(c);
    feed_line(p, 0.8, -0.3, 60);
    Prediction out;
    CHECK(p.predict(&out) == uint32_t(c.tail_points));
    const double t_last = 59 * 1000.0 / 240.0;
    const PredictedPoint& e = out.points[out.count - 1];
    CHECK_NEAR(out.horizon_ms, 10.0, 0.01);
    CHECK_NEAR(e.x, 100 + 0.8 * (t_last + 10), 0.15);
    CHECK_NEAR(e.y, 200 - 0.3 * (t_last + 10), 0.15);
    // Tail points are evenly spaced in time and lie on the line.
    for (uint32_t i = 0; i < out.count; ++i) CHECK_NEAR(out.points[i].dt_ms, 10.0 * (i + 1) / out.count, 1e-4);
  }
}

TEST(predictor_still_pen_predicts_nothing) {
  StrokePredictor p;
  feed_line(p, 0.0, 0.0, 40);
  Prediction out;
  CHECK(p.predict(&out) == 0);
}

TEST(predictor_warmup) {
  StrokePredictor p;
  Prediction out;
  p.add(0, 0, 0, 0.5f);
  CHECK(p.predict(&out) == 0);
  p.add(4'166'666, 4, 0, 0.5f);
  CHECK(p.predict(&out) == 0);
}

TEST(predictor_braking_never_passes_stop_point) {
  // Decelerate uniformly to a stop at x = 100 + 0.5*v0*T.
  const double v0 = 1.5, T = 120.0, a = -v0 / T;
  const double stop_x = 100 + 0.5 * v0 * T;
  StrokePredictor p;
  Prediction out;
  for (int k = 0;; ++k) {
    const double t = k * 1000.0 / 240.0;
    if (t > T) break;
    p.add(int64_t(t * 1e6), float(100 + v0 * t + 0.5 * a * t * t), 300.0f, 0.6f);
    if (p.predict(&out)) CHECK(out.points[out.count - 1].x <= stop_x + 0.75);
  }
}

TEST(predictor_pen_lift_shortens_horizon) {
  PredictorConfig c;
  c.horizon_ms = 12;
  StrokePredictor steady(c), lifting(c);
  // Same motion; in `lifting` the pressure falls 0.6 → 0.1 over the last
  // 5 samples (~20 ms), the way it does as the nib leaves the glass.
  for (int k = 0; k < 40; ++k) {
    const int64_t t = int64_t(k * 4'166'666);
    const float x = 100.0f + 0.8f * float(k) * 4.1666f;
    steady.add(t, x, 50, 0.6f);
    lifting.add(t, x, 50, k < 35 ? 0.6f : 0.6f - 0.1f * float(k - 34));
  }
  Prediction a, b;
  steady.predict(&a);
  lifting.predict(&b);
  CHECK(a.horizon_ms > 11.0f);
  CHECK(b.horizon_ms < 0.5f * a.horizon_ms);
}

TEST(predictor_sharp_turn_is_gated) {
  // Circle with a tight radius: heading changes fast, horizon must shrink.
  PredictorConfig c;
  c.horizon_ms = 16;
  StrokePredictor p(c);
  Prediction out;
  const double r = 12, w = 0.02;  // 0.24 px/ms tangential speed, 20 rad/s
  for (int k = 0; k < 60; ++k) {
    const double t = k * 1000.0 / 240.0;
    p.add(int64_t(t * 1e6), float(500 + r * std::cos(w * t)), float(500 + r * std::sin(w * t)), 0.6f);
  }
  p.predict(&out);
  CHECK(out.horizon_ms * w <= c.max_turn_rad * 1.5);
}

TEST(predictor_horizon_rises_slowly_falls_fast) {
  PredictorConfig c;
  c.horizon_ms = 12;
  StrokePredictor p(c);
  Prediction out;
  float prev = 0;
  for (int k = 0; k < 20; ++k) {
    p.add(int64_t(k * 4'166'666), 100.0f + float(k) * 4.0f, 0, 0.6f);
    p.predict(&out);
    CHECK(out.horizon_ms <= prev + 4.17f * c.horizon_rise_rate + 0.01f);
    prev = out.horizon_ms;
  }
}

// ---------------------------------------------------------------------------

struct EngineFixture {
  static constexpr int W = 320, H = 240;
  CpuRenderer renderer{W, H};
  EngineConfig cfg;
  std::unique_ptr<InkEngine> engine;

  EngineFixture() {
    cfg.surface_w = W;
    cfg.surface_h = H;
    cfg.store_max_strokes = 64;
    cfg.store_max_capsules = 1 << 16;
    engine = std::make_unique<InkEngine>(cfg, renderer);
    Command c{CommandType::kSetWidth};
    c.f[0] = 5.0f;
    engine->post(c);
    engine->pump();
  }
};

uint64_t nonzero(const ImageRGBA8& a) {
  uint64_t n = 0;
  for (uint32_t v : a.px) n += v != 0;
  return n;
}

TEST(engine_prediction_leaves_no_residue) {
  EngineFixture fx;
  int64_t t = 0;
  auto push = [&](Action a, float x, float y) {
    InkSample s = sample(a, t, x, y);
    fx.engine->push(&s, 1);
    fx.engine->pump();
    t += 4167;
  };
  push(Action::kDown, 40, 60);
  bool saw_prediction = false;
  for (int k = 1; k < 50; ++k) {
    // A curve that ends with a sharp reversal so the tail must be erased.
    const float x = 40 + float(k) * 4.0f, y = 60 + 30 * std::sin(float(k) * 0.15f);
    push(Action::kMove, x, y);
    if (fx.engine->last_prediction().count) saw_prediction = true;
    // Outside the current tail's rect, front == backing.
    const ImageRGBA8& f = fx.renderer.front();
    const ImageRGBA8& b = fx.renderer.backing();
    for (size_t i = 0; i < f.px.size(); ++i) {
      if (f.px[i] != b.px[i]) {
        // Must be covered by the predicted tail: front can only add ink.
        CHECK(((f.px[i] >> 24) & 255) >= ((b.px[i] >> 24) & 255));
      }
    }
  }
  CHECK(saw_prediction);
  push(Action::kMove, 236, 60);
  push(Action::kUp, 236, 60);
  // After pen-up the wet layer is empty, the dry layer has the stroke.
  CHECK(nonzero(fx.renderer.front()) == 0);
  CHECK(nonzero(fx.renderer.backing()) == 0);
  CHECK(nonzero(fx.renderer.dry()) > 500);
  CHECK(fx.engine->store().stroke_count() == 1);
  CHECK(fx.engine->store().stroke(0).capsule_count == 52);
}

TEST(engine_wet_equals_dry) {
  // Real wet ink just before pen-up must look identical once committed dry
  // (translucent ink, self-overlapping: catches double blending at joints).
  EngineFixture fx;
  Command col{CommandType::kSetColor};
  col.f[0] = 0.2f; col.f[1] = 0.4f; col.f[2] = 0.9f; col.f[3] = 0.5f;  // translucent
  fx.engine->post(col);
  int64_t t = 0;
  std::vector<InkSample> s;
  s.push_back(sample(Action::kDown, t, 30, 30));
  for (int k = 1; k < 40; ++k) {
    t += 4167;
    // Self-overlapping loop: a double-blended joint would show here.
    s.push_back(sample(Action::kMove, t, 120 + 60 * std::cos(k * 0.2f), 100 + 60 * std::sin(k * 0.2f), 0.4f + 0.01f * k));
  }
  fx.engine->push(s.data(), uint32_t(s.size()));
  fx.engine->pump();
  // Real wet ink over the page must look exactly like the committed stroke.
  InkSample last = s.back();
  const ImageRGBA8 before = fx.renderer.composite(/*include_prediction=*/false);
  last.action = Action::kUp;
  last.t_ns += 4'167'000;  // same position: adds a zero-length segment only
  fx.engine->push(&last, 1);
  fx.engine->pump();
  const ImageRGBA8 after = fx.renderer.composite();
  uint64_t diff = 0;
  for (size_t i = 0; i < before.px.size(); ++i) {
    for (int sh = 0; sh < 32; sh += 8) {
      const int a = int((before.px[i] >> sh) & 255), b = int((after.px[i] >> sh) & 255);
      if (std::abs(a - b) > 1) ++diff;  // rounding of over() vs. max
    }
  }
  CHECK(diff == 0);
}

TEST(engine_cancel_erases) {
  EngineFixture fx;
  std::vector<InkSample> s{sample(Action::kDown, 0, 50, 50)};
  for (int k = 1; k < 20; ++k) s.push_back(sample(Action::kMove, k * 4167, 50 + 5.0f * k, 50));
  fx.engine->push(s.data(), uint32_t(s.size()));
  fx.engine->pump();
  CHECK(nonzero(fx.renderer.front()) > 0);
  InkSample c = sample(Action::kCancel, 20 * 4167, 0, 0);
  fx.engine->push(&c, 1);
  fx.engine->pump();
  CHECK(nonzero(fx.renderer.front()) == 0);
  CHECK(nonzero(fx.renderer.backing()) == 0);
  CHECK(nonzero(fx.renderer.dry()) == 0);
  CHECK(fx.engine->store().stroke_count() == 0);
}

TEST(engine_long_stroke_is_never_split) {
  // The open stroke lives in the store, so any length commits as one stroke.
  EngineFixture fx;
  std::vector<InkSample> s{sample(Action::kDown, 0, 20, 20)};
  for (int k = 1; k < 3000; ++k)
    s.push_back(sample(Action::kMove, k * 4167, 160 + 100 * std::cos(k * 0.01f), 120 + 100 * std::sin(k * 0.013f)));
  s.push_back(sample(Action::kUp, 3000 * 4167, 160, 120));
  for (size_t i = 0; i < s.size(); i += 7) {
    fx.engine->push(&s[i], uint32_t(std::min<size_t>(7, s.size() - i)));
    fx.engine->pump();
  }
  CHECK(fx.engine->store().stroke_count() == 1);
  CHECK(fx.engine->store().stroke(0).capsule_count == 3001);
  CHECK(nonzero(fx.renderer.front()) == 0);
}

TEST(engine_store_full_degrades_gracefully) {
  CpuRenderer r(200, 200);
  EngineConfig cfg;
  cfg.surface_w = cfg.surface_h = 200;
  cfg.store_max_strokes = 4;
  cfg.store_max_capsules = 32;
  InkEngine e(cfg, r);
  std::vector<InkSample> s{sample(Action::kDown, 0, 20, 20)};
  for (int k = 1; k < 50; ++k) s.push_back(sample(Action::kMove, k * 4167, 20 + 3.0f * k, 20));
  s.push_back(sample(Action::kUp, 50 * 4167, 170, 20));
  e.push(s.data(), uint32_t(s.size()));
  e.pump();
  CHECK(e.store().stroke_count() == 1);
  CHECK(e.store().stroke(0).capsule_count == 32);
  CHECK(e.stats().store_full.load() == 51 - 32);
  // Further strokes: no crash, nothing written past the reservation.
  e.push(s.data(), uint32_t(s.size()));
  e.pump();
  CHECK(e.store().capsule_count() == 32);
}

TEST(engine_missing_up_does_not_lose_ink) {
  EngineFixture fx;
  std::vector<InkSample> s{sample(Action::kDown, 0, 20, 20), sample(Action::kMove, 4167, 40, 20),
                           sample(Action::kDown, 200000, 100, 100), sample(Action::kMove, 204167, 120, 100),
                           sample(Action::kUp, 208334, 140, 100)};
  fx.engine->push(s.data(), uint32_t(s.size()));
  fx.engine->pump();
  CHECK(fx.engine->store().stroke_count() == 2);
  CHECK(nonzero(fx.renderer.front()) == 0);
}

TEST(engine_transform_and_width_scale) {
  EngineFixture fx;
  Command c{CommandType::kSetTransform};
  const float f[6] = {2, 0, 10, 0, 2, 20};
  std::memcpy(c.f, f, sizeof(f));
  fx.engine->post(c);
  InkSample d = sample(Action::kDown, 0, 10, 10), u = sample(Action::kUp, 4167, 10, 10, 0.6f);
  fx.engine->push(&d, 1);
  fx.engine->push(&u, 1);
  fx.engine->pump();
  const Capsule* caps = fx.engine->store().capsules(0);
  CHECK_NEAR(caps[0].x0, 30, 1e-5);
  CHECK_NEAR(caps[0].y0, 40, 1e-5);
  Brush b;
  b.width_px = 5;
  CHECK_NEAR(caps[0].r0, 2 * b.radius(0.6f), 1e-4);
}

TEST(engine_threaded_end_to_end) {
  NullRenderer r;
  EngineConfig cfg;
  cfg.surface_w = 1000;
  cfg.surface_h = 1000;
  cfg.store_max_strokes = 1024;
  cfg.store_max_capsules = 1 << 16;
  InkEngine e(cfg, r);
  e.start();
  int64_t t = now_ns();
  constexpr int kStrokes = 20, kPer = 30;
  for (int s = 0; s < kStrokes; ++s) {
    for (int k = 0; k < kPer; ++k) {
      InkSample smp = sample(k == 0 ? Action::kDown : (k == kPer - 1 ? Action::kUp : Action::kMove), 0,
                             100.0f + 3.0f * k, 100.0f + 2.0f * s);
      smp.t_ns = t;
      smp.t_recv_ns = now_ns();
      t += 4'166'666;
      e.push(&smp, 1);
      if (k % 7 == 0) std::this_thread::sleep_for(std::chrono::microseconds(300));
    }
  }
  // post_sync acts as a barrier: all earlier commands are done once it returns.
  Command c{CommandType::kSetWidth};
  c.f[0] = 3;
  e.post_sync(c);
  const int64_t deadline = now_ns() + 2'000'000'000LL;
  while (e.stats().strokes.load() < kStrokes && now_ns() < deadline) std::this_thread::sleep_for(std::chrono::milliseconds(1));
  e.stop();
  CHECK(e.stats().strokes.load() == uint64_t(kStrokes));
  CHECK(r.commits_ == uint64_t(kStrokes));
  CHECK(e.stats().samples.load() == uint64_t(kStrokes * kPer));
  CHECK(e.probe().recv_to_submit.count() == uint64_t(kStrokes * kPer));
  CHECK(e.probe().dropped_samples.load() == 0);
}

TEST(engine_predictor_switch_mid_stroke_applies_next_stroke) {
  EngineFixture fx;
  InkSample d = sample(Action::kDown, 0, 10, 10);
  fx.engine->push(&d, 1);
  fx.engine->pump();
  Command c{CommandType::kSetPredictor};
  c.i0 = int32_t(PredictorModel::kPolynomial);
  fx.engine->post(c);
  fx.engine->pump();
  CHECK(fx.engine->predictor().config().model == PredictorModel::kKalman);  // not mid-stroke
  InkSample u = sample(Action::kUp, 4167, 12, 10), d2 = sample(Action::kDown, 100000, 50, 50);
  fx.engine->push(&u, 1);
  fx.engine->push(&d2, 1);
  fx.engine->pump();
  CHECK(fx.engine->predictor().config().model == PredictorModel::kPolynomial);
}

TEST(scan_wait_math) {
  const int64_t P = 6'944'444;  // 144 Hz
  // Ready exactly at vsync: row 0 is being scanned now, row 1.0 at 0.95 P.
  CHECK(scan_wait_ns(1'000'000, 1'000'000, P, 0.95f, 0.0f) == 0);
  CHECK_NEAR(double(scan_wait_ns(1'000'000, 1'000'000, P, 0.95f, 1.0f)), 0.95 * P, 2);
  // Just missed the row: wait almost a full period.
  const int64_t row_t = int64_t(0.95 * P * 0.5);
  CHECK_NEAR(double(scan_wait_ns(row_t + 1, 0, P, 0.95f, 0.5f)), double(P - 1), 2);
  // Phase computed modulo the period, also for vsync timestamps in the future.
  CHECK(scan_wait_ns(10 * P + 5, 0, P, 0.95f, 0.0f) == P - 5);
  CHECK(scan_wait_ns(5, 3 * P, P, 0.95f, 0.0f) == P - 5);
  for (int64_t t = 0; t < 3 * P; t += 12345) {
    const int64_t w = scan_wait_ns(t, 0, P, 0.95f, 0.3f);
    CHECK(w >= 0 && w < P);
  }
}

TEST(visible_horizon) {
  DisplayTiming d;
  d.axis = ScanAxis::kY;
  d.gpu_ms = 0.5f;
  d.panel_ms = 5.0f;
  const int64_t P = 6'944'444;
  // No vsync yet → fallback.
  CHECK_NEAR(visible_horizon_ms(d, 100'000'000, 98'000'000, 0, 0, 0, 0, 1000, 1000, 12.0f), 12.0, 1e-6);
  // Stale vsync → fallback.
  CHECK_NEAR(visible_horizon_ms(d, 2'000'000'000, 1'999'000'000, 0, P, 0, 0, 1000, 1000, 12.0f), 12.0, 1e-6);
  // Sample 2 ms old, ready 0.5 ms later exactly at a vsync, ink at the top row:
  // visible immediately → 2 + 0.5 + 0 + 5 = 7.5 ms.
  const int64_t now = 100'000'000, vs = now + 500'000;
  CHECK_NEAR(visible_horizon_ms(d, now, now - 2'000'000, vs, P, 0, 0.0f, 1000, 1000, 12.0f), 7.5, 1e-3);
  // Same, ink at the bottom row: + 0.95 P.
  CHECK_NEAR(visible_horizon_ms(d, now, now - 2'000'000, vs, P, 0, 1000.0f, 1000, 1000, 12.0f), 7.5 + 0.95 * 6.944444, 1e-3);
  // Reversed scan flips that.
  d.reversed = true;
  CHECK_NEAR(visible_horizon_ms(d, now, now - 2'000'000, vs, P, 0, 1000.0f, 1000, 1000, 12.0f), 7.5, 1e-3);
  // Unknown geometry: average wait of half a period.
  d.axis = ScanAxis::kUnknown;
  CHECK_NEAR(visible_horizon_ms(d, now, now - 2'000'000, vs, P, 0, 0, 1000, 1000, 12.0f), 7.5 + 6.944444 / 2, 1e-3);
}

TEST(engine_inline_mode_and_vsync_horizon) {
  CpuRenderer r(400, 400);
  EngineConfig cfg;
  cfg.surface_w = cfg.surface_h = 400;
  cfg.store_max_strokes = 16;
  cfg.store_max_capsules = 1 << 12;
  InkEngine e(cfg, r);
  CHECK(e.attach_inline());
  Command w{CommandType::kSetWidth};
  w.f[0] = 4;
  e.post(w);  // UI thread path: applied by the next process()
  Command disp{CommandType::kSetDisplay};
  disp.f[0] = 5.0f; disp.f[1] = 0.3f; disp.i0 = int32_t(ScanAxis::kY);
  e.execute(disp);
  Command vs{CommandType::kVsync};
  vs.t = now_ns();
  vs.i0 = 6'944'444;
  e.execute(vs);
  // Real-time replay at 240 Hz; each sample is 1 ms old when processed.
  int64_t t = 0;
  for (int k = 0; k < 30; ++k) {
    std::this_thread::sleep_for(std::chrono::microseconds(4166));
    InkSample smp = sample(k == 0 ? Action::kDown : Action::kMove, 0, 50.0f + 4.0f * k, 200.0f);
    t = now_ns() - 1'000'000;
    smp.t_ns = smp.t_recv_ns = t;
    e.process(&smp, 1);
  }
  // Horizons were computed per frame from vsync timing: within
  // [elapsed + gpu + panel, + one period].
  const LatencyProbe& p = e.probe();
  CHECK(p.horizon_us.count() == 30);
  // ≥ 1 ms elapsed + 0.3 ms GPU + 5 ms panel; ≤ that + one period + scheduling slack.
  CHECK(p.horizon_us.percentile(1) >= 6000);
  CHECK(p.horizon_us.percentile(50) <= 15000);
  CHECK(p.horizon_us.max() <= uint64_t(25'000));
  CHECK(nonzero(r.front()) > 0);
  InkSample up = sample(Action::kUp, 0, 170, 200);
  up.t_ns = t + 4'166'666;
  e.process(&up, 1);
  CHECK(e.store().stroke_count() == 1);
  CHECK(nonzero(r.front()) == 0);
  e.detach_inline();
}

TEST(recorder_exports_predictor_bench_csv) {
  CpuRenderer r(200, 200);
  EngineConfig cfg;
  cfg.surface_w = cfg.surface_h = 200;
  cfg.store_max_strokes = 16;
  cfg.store_max_capsules = 1 << 12;
  InkEngine e(cfg, r);
  Command rec{CommandType::kSetRecording};
  rec.i0 = 1;
  e.execute(rec);
  for (int s = 0; s < 3; ++s) {
    std::vector<InkSample> v{sample(Action::kDown, s * 100000, 10, 10.0f + s)};
    for (int k = 1; k < 5; ++k) v.push_back(sample(Action::kMove, s * 100000 + k * 4167, 10.0f + k, 10.0f + s));
    v.push_back(sample(Action::kUp, s * 100000 + 5 * 4167, 15, 10.0f + s));
    e.process(v.data(), uint32_t(v.size()));
  }
  const char* path = "noti_recorder_test.csv";
  CHECK(e.export_recording(path) == 18);
  FILE* f = std::fopen(path, "r");
  CHECK(f != nullptr);
  if (f) {
    char line[256];
    int lines = 0, strokes_seen = 0;
    unsigned last = ~0u;
    while (std::fgets(line, sizeof(line), f)) {
      ++lines;
      unsigned id;
      if (std::sscanf(line, "%u,", &id) == 1 && id != last) { ++strokes_seen; last = id; }
    }
    std::fclose(f);
    std::remove(path);
    CHECK(lines == 19);  // header + 18 samples
    CHECK(strokes_seen == 3);
  }
}

TEST(engine_hover_reaches_renderer) {
  struct HoverRenderer final : InkRenderer {
    int enters = 0, exits = 0;
    void hover(bool near) override { near ? ++enters : ++exits; }
    void render(const FrameUpdate&) override {}
    void commit(const StrokeStore&, uint32_t, const RectI&) override {}
    void cancel(const RectI&) override {}
  } r;
  EngineConfig cfg;
  InkEngine e(cfg, r);
  InkSample s[] = {sample(Action::kHoverEnter, 0, 1, 1), sample(Action::kHoverMove, 1, 2, 2),
                   sample(Action::kHoverExit, 2, 3, 3)};
  e.push(s, 3);
  e.pump();
  CHECK(r.enters == 1);
  CHECK(r.exits == 1);
}

}  // namespace

int main() {
  int failed_tests = 0;
  for (auto& t : registry()) {
    g_test = t.name;
    const int before = g_failures;
    const int64_t t0 = now_ns();
    t.fn();
    const bool ok = g_failures == before;
    failed_tests += !ok;
    std::printf("%s %-46s %8.1f ms\n", ok ? "[ OK ]" : "[FAIL]", t.name, double(now_ns() - t0) / 1e6);
  }
  std::printf("\n%zu tests, %d failed\n", registry().size(), failed_tests);
  return failed_tests ? 1 : 0;
}
