// End-to-end engine benchmark (plan §5.2 "CI performance gate").
//
// A producer thread replays synthetic handwriting at the real 240 Hz pen rate
// into a running InkEngine, exactly like the JNI input handler would, and the
// engine's own latency probe reports the distribution of
//   recv→start   input thread → render thread hand-off (futex / spin / predictive wake)
//   recv→submit  sample received → renderer call returned (the CI-gated number)
// Then a burst run measures raw throughput (ns per sample, synchronous).
//
//   engine_bench [--seconds S] [--renderer null|cpu] [--no-predictive] [--spin-us N]
//                [--gate-p99-us N] [--gate-cpu-us N]
//
// Gates:
//   --gate-cpu-us N  p99 engine CPU time per batch ≤ N µs and burst cost ≤ N/50 µs
//                    per sample. Measures only what the engine controls: stable on
//                    shared CI machines.
//   --gate-p99-us N  wall-clock recv→submit p99 ≤ N µs. Includes OS scheduling of the
//                    hand-off, so only meaningful on the device or a quiet machine.
#include <time.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "noti/clock.h"
#include "noti/cpu_renderer.h"
#include "noti/engine.h"
#include "synthetic_strokes.h"

using namespace noti;
using namespace noti::tools;

namespace {

void sleep_until(int64_t t_ns) {
  timespec ts{time_t(t_ns / 1'000'000'000), long(t_ns % 1'000'000'000)};
  while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr) != 0) {
  }
}

void print_hist(const char* name, const Histogram& h) {
  std::printf("  %-14s n=%-7llu p50 %8.1f µs   p90 %8.1f µs   p99 %8.1f µs   max %8.1f µs\n", name,
              (unsigned long long)h.count(), h.percentile(50) / 1e3, h.percentile(90) / 1e3,
              h.percentile(99) / 1e3, double(h.max()) / 1e3);
}

std::vector<InkSample> to_samples(const std::vector<Stroke>& strokes, double gap_ms) {
  std::vector<InkSample> out;
  int64_t t = 0;
  for (const Stroke& s : strokes) {
    for (size_t k = 0; k < s.size(); ++k) {
      InkSample smp{};
      smp.t_ns = t + (s.t_ns[k] - s.t0_ns);
      smp.x = s.x[k];
      smp.y = s.y[k];
      smp.pressure = s.p[k];
      smp.action = k == 0 ? Action::kDown : (k + 1 == s.size() ? Action::kUp : Action::kMove);
      out.push_back(smp);
    }
    t += (s.t_ns.back() - s.t0_ns) + int64_t(gap_ms * 1e6);
  }
  return out;
}

}  // namespace

int main(int argc, char** argv) {
  double seconds = 3.0;
  std::string renderer_name = "null";
  bool predictive = true;
  long spin_us = -1;
  double gate_p99_us = 0;
  double gate_cpu_us = 0;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    auto next = [&]() -> const char* { return i + 1 < argc ? argv[++i] : "0"; };
    if (a == "--seconds") seconds = std::atof(next());
    else if (a == "--renderer") renderer_name = next();
    else if (a == "--no-predictive") predictive = false;
    else if (a == "--spin-us") spin_us = std::atol(next());
    else if (a == "--gate-p99-us") gate_p99_us = std::atof(next());
    else if (a == "--gate-cpu-us") gate_cpu_us = std::atof(next());
    else {
      std::fprintf(stderr, "usage: %s [--seconds S] [--renderer null|cpu] [--no-predictive] [--spin-us N] [--gate-p99-us N] [--gate-cpu-us N]\n", argv[0]);
      return 2;
    }
  }

  // Enough strokes for the requested wall time (strokes average ~0.8 s).
  auto strokes = make_dataset(std::max(4, int(seconds / 0.8) + 2), 42);
  std::vector<InkSample> samples = to_samples(strokes, 120.0);

  NullRenderer null_r;
  CpuRenderer cpu_r(2400, 1800);
  InkRenderer& r = renderer_name == "cpu" ? static_cast<InkRenderer&>(cpu_r) : null_r;

  EngineConfig cfg;
  cfg.surface_w = 2400;
  cfg.surface_h = 1800;
  cfg.predictive_wake = predictive;
  if (spin_us >= 0) cfg.spin_ns = spin_us * 1000;

  bool gate_ok = true;
  // ---- realtime run ----
  {
    InkEngine engine(cfg, r);
    Command c{CommandType::kSetWidth};
    c.f[0] = 6.0f;
    engine.post(c);
    engine.start();
    const int64_t start = now_ns() + 20'000'000;
    const int64_t limit = int64_t(seconds * 1e9);
    size_t pushed = 0;
    for (InkSample s : samples) {
      if (s.t_ns > limit) {
        if (s.action != Action::kUp && pushed) {  // close the stroke cleanly
          s.action = Action::kUp;
          s.t_ns = limit;
        } else {
          break;
        }
      }
      const int64_t due = start + s.t_ns;
      sleep_until(due);
      s.t_ns = due;            // hardware time
      s.t_recv_ns = now_ns();  // input handler time
      engine.push(&s, 1);
      ++pushed;
      if (s.t_ns - start >= limit) break;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    engine.stop();

    const LatencyProbe& p = engine.probe();
    const EngineStats& st = engine.stats();
    std::printf("realtime: %zu samples at 240 Hz over %.1f s, renderer=%s, predictive_wake=%s, spin=%lld µs\n", pushed,
                seconds, renderer_name.c_str(), predictive ? "on" : "off", (long long)(cfg.spin_ns / 1000));
    print_hist("recv→start", p.recv_to_start);
    print_hist("recv→submit", p.recv_to_submit);
    print_hist("frame cpu", p.frame_cpu);
    std::printf("  wake-ups: futex %llu, predictive %llu, spin %llu; dropped %llu\n",
                (unsigned long long)st.wakeups.load(), (unsigned long long)st.predicted_hits.load(),
                (unsigned long long)st.spin_hits.load(), (unsigned long long)p.dropped_samples.load());
    std::printf("  prediction error (end point vs. later real sample): p50 %.2f px  p99 %.2f px\n",
                p.pred_error_cpx.percentile(50) / 100.0, p.pred_error_cpx.percentile(99) / 100.0);

    if (gate_p99_us > 0) {
      const double p99 = p.recv_to_submit.percentile(99) / 1e3;
      const bool ok = p99 <= gate_p99_us;
      std::printf("GATE %s: recv→submit p99 %.1f µs (limit %.1f µs)\n", ok ? "PASS" : "FAIL", p99, gate_p99_us);
      gate_ok &= ok;
    }
    if (gate_cpu_us > 0) {
      const double p99 = p.frame_cpu.percentile(99) / 1e3;
      const bool ok = p99 <= gate_cpu_us;
      std::printf("GATE %s: frame cpu p99 %.1f µs (limit %.1f µs)\n", ok ? "PASS" : "FAIL", p99, gate_cpu_us);
      gate_ok &= ok;
    }
  }

  // ---- inline mode: the input thread renders (production path on Android) ----
  {
    InkEngine engine(cfg, r);
    Command c{CommandType::kSetWidth};
    c.f[0] = 6.0f;
    engine.post(c);
    engine.attach_inline();
    const int64_t start = now_ns() + 20'000'000;
    const int64_t limit = int64_t(seconds * 1e9);
    size_t pushed = 0;
    for (InkSample s : samples) {
      if (s.t_ns > limit) {
        if (s.action == Action::kUp || !pushed) break;
        s.action = Action::kUp;
        s.t_ns = limit;
      }
      const int64_t due = start + s.t_ns;
      sleep_until(due);
      s.t_ns = due;
      s.t_recv_ns = now_ns();
      engine.process(&s, 1);
      ++pushed;
      if (s.t_ns - start >= limit) break;
    }
    engine.detach_inline();
    const LatencyProbe& p = engine.probe();
    std::printf("\ninline (input thread draws, no hand-off): %zu samples\n", pushed);
    print_hist("recv→submit", p.recv_to_submit);
    print_hist("frame cpu", p.frame_cpu);
  }

  // ---- burst throughput (synchronous, no thread hand-off) ----
  {
    NullRenderer nr;
    EngineConfig bc = cfg;
    bc.probe = false;
    InkEngine engine(bc, nr);
    auto many = to_samples(make_dataset(400, 9), 50.0);
    const int64_t t0 = now_ns();
    for (size_t i = 0; i < many.size(); ++i) {
      engine.push(&many[i], 1);
      engine.pump();
    }
    const double ns = double(now_ns() - t0) / double(many.size());
    std::printf("\nburst: %zu samples, one frame per sample: %.0f ns per sample end-to-end (ring + predictor + geometry + frame)\n",
                many.size(), ns);
    if (gate_cpu_us > 0) {
      const double limit_ns = gate_cpu_us * 1000.0 / 50.0;
      const bool ok = ns <= limit_ns;
      std::printf("GATE %s: burst %.0f ns per sample (limit %.0f ns)\n", ok ? "PASS" : "FAIL", ns, limit_ns);
      gate_ok &= ok;
    }
  }
  return gate_ok ? 0 : 1;
}
