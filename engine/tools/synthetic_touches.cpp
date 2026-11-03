#include "synthetic_touches.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <random>
#include <sstream>

namespace noti::tools {
namespace {

constexpr int64_t kMs = 1'000'000;
constexpr int64_t kPenPeriod = 4'166'667;   // 240 Hz
constexpr int64_t kTouchPeriod = 8'333'333; // 120 Hz contact reports

struct V2 { float x, y; };

// Builds one scenario in millimetres; converts to view pixels on output.
class Builder {
 public:
  Builder(Scenario* s, const TouchSynthConfig& cfg, uint32_t salt)
      : s_(s), cfg_(cfg), rng_(cfg.seed * 7919u + salt * 104729u) {
    side_ = cfg.left_handed ? -1.0f : 1.0f;
  }

  float side() const { return side_; }
  float uni(float a, float b) { return std::uniform_real_distribution<float>(a, b)(rng_); }
  float gauss(float sd) { return std::normal_distribution<float>(0.0f, sd)(rng_); }
  uint32_t new_id(Expect e) {
    const uint32_t id = next_id_++;
    s_->expect[id] = e;
    return id;
  }

  // Where the writing hand's palm rests relative to the nib, for this session.
  V2 palm_offset() { return {side_ * (45.0f + uni(-8, 8)), 55.0f + uni(-8, 8)}; }

  void pen(int64_t t, Action a, float x, float y, float tilt = 0.7f) {
    TimelineEvent e{};
    e.t = t;
    e.pen = true;
    e.ink.t_ns = e.ink.t_recv_ns = t;
    e.ink.x = x * ppm();
    e.ink.y = y * ppm();
    e.ink.pressure = a == Action::kDown || a == Action::kMove || a == Action::kUp ? 0.5f : 0.0f;
    e.ink.tilt = tilt;
    // Azimuth: the pen leans towards the writing hand, swinging a little.
    e.ink.orientation = side_ * (2.3f + 0.15f * std::sin(float(t) * 1e-9f * 0.7f));
    e.ink.action = a;
    s_->events.push_back(e);
    if (a != Action::kHoverExit) nib_.push_back({t, {x, y}});
  }

  void touch(int64_t t, uint32_t id, TouchAction a, float x, float y, float major, float minor,
             ToolType tool = ToolType::kFinger, uint8_t flags = 0) {
    TimelineEvent e{};
    e.t = t;
    e.pen = false;
    e.touch.t_ns = t;
    e.touch.x = x * ppm();
    e.touch.y = y * ppm();
    e.touch.major = cfg_.report_size ? major * ppm() : 0.0f;
    e.touch.minor = cfg_.report_size ? minor * ppm() : 0.0f;
    e.touch.pressure = 0.3f;
    e.touch.id = id;
    e.touch.action = a;
    e.touch.tool = tool;
    e.touch.flags = flags;
    s_->events.push_back(e);
  }

  // Writes `words` words along a line starting at (x, y); returns the end time.
  // Hover approaches before each word and continues in the gaps.
  int64_t write(int64_t t, float x, float y, int words, bool exit_at_end = true) {
    pen(t, Action::kHoverEnter, x - 4, y - 3);
    for (int k = 1; k <= 12; ++k) pen(t + k * kPenPeriod, Action::kHoverMove, x - 4 + k * 0.33f, y - 3 + k * 0.25f);
    t += 13 * kPenPeriod;
    for (int w = 0; w < words; ++w) {
      const int64_t dur = int64_t(uni(250, 450)) * kMs;
      const float speed = uni(40, 70);  // mm/s
      const int n = int(dur / kPenPeriod);
      const float freq = uni(6, 10), amp = uni(1.5f, 3.0f);
      pen(t, Action::kDown, x, y);
      for (int k = 1; k <= n; ++k) {
        const float ts = float(k) * float(kPenPeriod) * 1e-9f;
        pen(t + k * kPenPeriod, k == n ? Action::kUp : Action::kMove, x + speed * ts,
            y + amp * std::sin(2.0f * 3.14159f * freq * ts));
      }
      x += speed * float(n) * float(kPenPeriod) * 1e-9f;
      t += n * kPenPeriod;
      // Gap: hover forward to the next word.
      const int64_t gap = int64_t(uni(120, 280)) * kMs;
      const int g = int(gap / kPenPeriod);
      pen(t + kPenPeriod, Action::kHoverEnter, x, y - 2);
      for (int k = 2; k <= g; ++k) pen(t + k * kPenPeriod, Action::kHoverMove, x + k * 0.12f, y - 2);
      x += g * 0.12f;
      t += g * kPenPeriod;
    }
    if (exit_at_end) {
      pen(t + kPenPeriod, Action::kHoverExit, x, y);
      t += kPenPeriod;
    }
    return t;
  }

  // Nib position at time t (clamped to the recorded trajectory), smoothed the
  // way a resting hand follows the pen: the palm trails the nib by ~150 ms.
  V2 nib_at(int64_t t) const {
    if (nib_.empty()) return {100, 80};
    V2 acc{0, 0};
    for (int k = 0; k < 5; ++k) {
      const V2 p = nib_raw(t - k * 60 * kMs);
      acc.x += p.x;
      acc.y += p.y;
    }
    return {acc.x / 5, acc.y / 5};
  }

  // A palm: lands small and rolls on to its full size; follows `anchor`.
  void palm(uint32_t id, int64_t t0, int64_t t1, const std::function<V2(int64_t)>& anchor, float peak = 0,
            bool roll = true, float elong = 0.6f, ToolType tool = ToolType::kFinger) {
    if (peak <= 0) peak = uni(20, 30);
    const float start = roll ? peak * uni(0.4f, 0.55f) : peak;
    const int64_t roll_ns = int64_t(uni(20, 40)) * kMs;
    for (int64_t t = t0;; t += kTouchPeriod) {
      const bool last = t >= t1;
      const float k = roll ? std::min(1.0f, float(t - t0) / float(roll_ns)) : 1.0f;
      const float major = start + (peak - start) * k;
      const V2 a = anchor(std::min(t, t1));
      touch(std::min(t, t1), id, t == t0 ? TouchAction::kDown : last ? TouchAction::kUp : TouchAction::kMove,
            a.x + gauss(0.15f), a.y + gauss(0.15f), major, major * elong, tool);
      if (last) break;
    }
  }

  // A deliberate fingertip following `path` (relative time in ns → position).
  void finger(uint32_t id, int64_t t0, int64_t t1, const std::function<V2(int64_t)>& path, float size = 0) {
    if (size <= 0) size = uni(8, 10.5f);
    for (int64_t t = t0;; t += kTouchPeriod) {
      const bool last = t >= t1;
      const int64_t tt = std::min(t, t1);
      const V2 p = path(tt - t0);
      const float m = size * (t == t0 ? 0.92f : 1.0f);
      touch(tt, id, t == t0 ? TouchAction::kDown : last ? TouchAction::kUp : TouchAction::kMove, p.x, p.y, m,
            m * 0.85f);
      if (last) break;
    }
  }

  void finish() {
    std::stable_sort(s_->events.begin(), s_->events.end(),
                     [](const TimelineEvent& a, const TimelineEvent& b) { return a.t < b.t; });
    s_->px_per_mm = ppm();
  }

  float ppm() const { return 13.5f; }

 private:
  V2 nib_raw(int64_t t) const {
    if (t <= nib_.front().t) return nib_.front().p;
    if (t >= nib_.back().t) return nib_.back().p;
    auto it = std::lower_bound(nib_.begin(), nib_.end(), t, [](const NibPt& a, int64_t v) { return a.t < v; });
    const NibPt& b = *it;
    const NibPt& a = *(it - 1);
    const float f = b.t > a.t ? float(t - a.t) / float(b.t - a.t) : 0.0f;
    return {a.p.x + (b.p.x - a.p.x) * f, a.p.y + (b.p.y - a.p.y) * f};
  }

  struct NibPt { int64_t t; V2 p; };
  Scenario* s_;
  TouchSynthConfig cfg_;
  std::mt19937 rng_;
  float side_ = 1.0f;
  uint32_t next_id_ = 1;
  std::vector<NibPt> nib_;
};

// Horizontal start of a line of writing: hand side of the page leaves room for the palm.
float line_x(const Builder& b) { return b.side() > 0 ? 40.0f : 110.0f; }

std::function<V2(int64_t)> palm_follow(const Builder& b, V2 off) {
  return [&b, off](int64_t t) {
    const V2 n = b.nib_at(t);
    return V2{n.x + off.x, n.y + off.y};
  };
}

}  // namespace

std::vector<Scenario> make_touch_scenarios(const TouchSynthConfig& cfg) {
  std::vector<Scenario> out;
  const char* hand = cfg.left_handed ? "L" : "R";
  const char* size = cfg.report_size ? "" : "/nosize";
  auto add = [&](const char* name, bool finger_ink, uint32_t salt, const std::function<void(Builder&)>& script) {
    Scenario s;
    s.name = std::string(name) + "/" + hand + size;
    s.finger_ink = finger_ink;
    Builder b(&s, cfg, salt);
    script(b);
    b.finish();
    out.push_back(std::move(s));
  };

  // The hand rests on the glass before the pen has ever been near it.
  add("palm_before_pen_cold", false, 1, [](Builder& b) {
    const V2 off = b.palm_offset();
    const float x = line_x(b), y = 60;
    const uint32_t p = b.new_id(Expect::kPalm);
    const int64_t t_pen = 400 * kMs;
    const int64_t end = t_pen + 3000 * kMs;
    // Recorded first so the nib trajectory exists for the palm to follow.
    const int64_t t_end = b.write(t_pen, x, y, 4);
    b.palm(p, 0, std::max(end, t_end + 200 * kMs), [&b, off, x, y](int64_t t) {
      const V2 n = t < 400 * kMs ? V2{x, y} : b.nib_at(t);
      return V2{n.x + off.x, n.y + off.y};
    });
  });

  // Same, but the pen was used a few seconds earlier (the model knows the hand).
  add("palm_before_pen_warm", false, 2, [](Builder& b) {
    const V2 off = b.palm_offset();
    const float x = line_x(b);
    const uint32_t p0 = b.new_id(Expect::kPalm);
    const int64_t e0 = b.write(0, x, 40, 3);
    b.palm(p0, 80 * kMs, e0 + 100 * kMs, palm_follow(b, off));
    const int64_t t1 = e0 + 2500 * kMs;
    const uint32_t p1 = b.new_id(Expect::kPalm);
    const int64_t e1 = b.write(t1 + 350 * kMs, x, 70, 3);
    b.palm(p1, t1, e1 + 150 * kMs, [&b, off, x, t1](int64_t t) {
      const V2 n = t < t1 + 350 * kMs ? V2{x, 70} : b.nib_at(t);
      return V2{n.x + off.x, n.y + off.y};
    });
  });

  add("palm_after_pen", false, 3, [](Builder& b) {
    const V2 off = b.palm_offset();
    const uint32_t p = b.new_id(Expect::kPalm);
    const int64_t e = b.write(0, line_x(b), 60, 5);
    b.palm(p, 70 * kMs + 120 * kMs, e + 100 * kMs, palm_follow(b, off));
  });

  // The classic false two-finger double tap: palm + pinky land and lift twice
  // between words, while the pen hovers.
  add("palm_pinky_double_tap_hover", false, 4, [](Builder& b) {
    const V2 off = b.palm_offset();
    const int64_t e = b.write(0, line_x(b), 60, 3, false);
    // Hover in place afterwards.
    const V2 n = b.nib_at(e);
    for (int k = 1; k <= 200; ++k) b.pen(e + k * kPenPeriod, Action::kHoverMove, n.x, n.y - 2);
    b.pen(e + 201 * kPenPeriod, Action::kHoverExit, n.x, n.y - 2);
    for (int tap = 0; tap < 2; ++tap) {
      const int64_t t0 = e + 100 * kMs + tap * 230 * kMs;
      const uint32_t palm = b.new_id(Expect::kPalm), pinky = b.new_id(Expect::kPalm);
      const V2 pp{n.x + off.x, n.y + off.y};
      const V2 pk{pp.x - b.side() * 26, pp.y - 22};
      b.palm(palm, t0, t0 + 110 * kMs, [pp](int64_t) { return pp; }, b.uni(18, 22));
      b.finger(pinky, t0 + 12 * kMs, t0 + 105 * kMs, [pk](int64_t) { return pk; }, b.uni(9.5f, 11.5f));
    }
  });

  // Same tap pair, 1.2 s after the pen left hover range (outside the grace window).
  add("palm_pinky_double_tap_after_pen", false, 5, [](Builder& b) {
    const V2 off = b.palm_offset();
    const int64_t e = b.write(0, line_x(b), 60, 3);
    const V2 n = b.nib_at(e);
    for (int tap = 0; tap < 2; ++tap) {
      const int64_t t0 = e + 1200 * kMs + tap * 230 * kMs;
      const uint32_t palm = b.new_id(Expect::kPalm), pinky = b.new_id(Expect::kPalm);
      const V2 pp{n.x + off.x, n.y + off.y};
      const V2 pk{pp.x - b.side() * 26, pp.y - 22};
      b.palm(palm, t0, t0 + 110 * kMs, [pp](int64_t) { return pp; }, b.uni(18, 22));
      b.finger(pinky, t0 + 12 * kMs, t0 + 105 * kMs, [pk](int64_t) { return pk; }, b.uni(9.5f, 11.5f));
    }
  });

  add("palm_relands_mid_stroke", false, 6, [](Builder& b) {
    const V2 off = b.palm_offset();
    const int64_t e = b.write(0, line_x(b), 60, 5);
    const int64_t mid = e / 2;
    const uint32_t p0 = b.new_id(Expect::kPalm), p1 = b.new_id(Expect::kPalm);
    b.palm(p0, 150 * kMs, mid, palm_follow(b, off));
    b.palm(p1, mid + 150 * kMs, e + 100 * kMs, palm_follow(b, off));
  });

  // The pen leaves; the palm stays, drifts, lifts, then lands again later.
  add("palm_stays_after_pen_leaves", false, 7, [](Builder& b) {
    const V2 off = b.palm_offset();
    const int64_t e = b.write(0, line_x(b), 60, 4);
    const uint32_t p0 = b.new_id(Expect::kPalm), p1 = b.new_id(Expect::kPalm);
    b.palm(p0, 150 * kMs, e + 2500 * kMs, [&b, off, e](int64_t t) {
      const V2 n = b.nib_at(std::min(t, e));
      const float drift = t > e ? float(t - e) * 1e-9f * 1.5f : 0.0f;
      return V2{n.x + off.x + drift, n.y + off.y};
    });
    const V2 n = b.nib_at(e);
    const V2 pp{n.x + off.x + 3, n.y + off.y + 2};
    b.palm(p1, e + 3700 * kMs, e + 5200 * kMs, [pp](int64_t) { return pp; });
  });

  add("sliding_palm", false, 8, [](Builder& b) {
    const V2 off = b.palm_offset();
    const int64_t e = b.write(0, b.side() > 0 ? 20.0f : 60.0f, 60, 7);
    const uint32_t p = b.new_id(Expect::kPalm);
    b.palm(p, 100 * kMs, e + 100 * kMs, palm_follow(b, off));
  });

  // The other hand pinches on the far side while the writing hand hovers.
  add("other_hand_pinch_while_hovering", false, 9, [](Builder& b) {
    const V2 off = b.palm_offset();
    const float x = b.side() > 0 ? 130.0f : 40.0f;
    const int64_t e = b.write(0, x, 60, 2, false);
    const V2 n = b.nib_at(e);
    for (int k = 1; k <= 300; ++k) b.pen(e + k * kPenPeriod, Action::kHoverMove, n.x, n.y - 3);
    b.pen(e + 301 * kPenPeriod, Action::kHoverExit, n.x, n.y - 3);
    const uint32_t palm = b.new_id(Expect::kPalm);
    b.palm(palm, 100 * kMs, e + 1200 * kMs, palm_follow(b, off));
    const float cx = n.x - b.side() * 105, cy = 70;
    const uint32_t f0 = b.new_id(Expect::kGesture), f1 = b.new_id(Expect::kGesture);
    const int64_t t0 = e + 200 * kMs;
    b.finger(f0, t0, t0 + 450 * kMs, [cx, cy](int64_t t) {
      const float k = float(t) / float(450 * kMs);
      return V2{cx - 8 - 20 * k, cy - 4 - 12 * k};
    });
    b.finger(f1, t0 + 25 * kMs, t0 + 450 * kMs, [cx, cy](int64_t t) {
      const float k = float(t) / float(425 * kMs);
      return V2{cx + 8 + 20 * k, cy + 4 + 12 * k};
    });
  });

  // One finger of the other hand pans a second after writing.
  add("other_hand_pan_after_writing", false, 10, [](Builder& b) {
    const float x = b.side() > 0 ? 130.0f : 40.0f;
    const int64_t e = b.write(0, x, 60, 3);
    const V2 n = b.nib_at(e);
    const uint32_t f = b.new_id(Expect::kGesture);
    const float cx = n.x - b.side() * 100;
    b.finger(f, e + 900 * kMs, e + 1300 * kMs, [cx](int64_t t) {
      const float k = float(t) / float(400 * kMs);
      return V2{cx, 120 - 60 * k};
    });
  });

  // The writing hand's own index finger scrolls a few seconds after writing.
  add("pan_after_writing_same_hand", false, 11, [](Builder& b) {
    const int64_t e = b.write(0, line_x(b), 60, 3);
    const V2 n = b.nib_at(e);
    const uint32_t f = b.new_id(Expect::kGesture);
    b.finger(f, e + 3000 * kMs, e + 3400 * kMs, [n](int64_t t) {
      const float k = float(t) / float(400 * kMs);
      return V2{n.x + 5, n.y + 20 - 50 * k};
    });
  });

  // A thumb gripping the bezel while writing, and again with the pen idle.
  add("edge_grip", false, 12, [](Builder& b) {
    const int64_t e = b.write(0, line_x(b), 60, 4);
    const float ex = b.side() > 0 ? 2.5f : 222.0f - 2.5f;
    const uint32_t g0 = b.new_id(Expect::kPalm), g1 = b.new_id(Expect::kPalm);
    b.palm(g0, 200 * kMs, e, [ex](int64_t) { return V2{ex, 100}; }, b.uni(16, 20), false, 0.5f);
    b.palm(g1, e + 20000 * kMs, e + 23000 * kMs, [ex](int64_t) { return V2{ex, 90}; }, b.uni(16, 20), false, 0.5f);
  });

  add("finger_pan_idle", false, 13, [](Builder& b) {
    const uint32_t f = b.new_id(Expect::kGesture);
    b.finger(f, 0, 300 * kMs, [](int64_t t) {
      const float k = float(t) / float(300 * kMs);
      return V2{100 + 10 * k, 100 - 60 * k};
    });
  });

  add("two_finger_double_tap_idle", false, 14, [](Builder& b) {
    for (int tap = 0; tap < 2; ++tap) {
      const int64_t t0 = tap * 220 * kMs;
      const uint32_t a = b.new_id(Expect::kGesture), c = b.new_id(Expect::kGesture);
      b.finger(a, t0, t0 + 90 * kMs, [](int64_t) { return V2{100, 80}; });
      b.finger(c, t0 + 15 * kMs, t0 + 85 * kMs, [](int64_t) { return V2{130, 85}; });
    }
  });

  add("finger_taps_idle", false, 15, [](Builder& b) {
    for (int k = 0; k < 3; ++k) {
      const uint32_t f = b.new_id(Expect::kGesture);
      const float x = 60.0f + 40.0f * float(k);
      b.finger(f, k * 400 * kMs, k * 400 * kMs + 80 * kMs, [x](int64_t) { return V2{x, 90}; });
    }
  });

  // ---- drawing with a finger ----

  add("finger_ink_stroke", true, 16, [](Builder& b) {
    const uint32_t f = b.new_id(Expect::kInk);
    b.finger(f, 0, 350 * kMs, [](int64_t t) {
      const float k = float(t) / float(350 * kMs);
      return V2{80 + 60 * k, 90 + 15 * std::sin(6.0f * k)};
    });
  });

  add("finger_ink_palm_roll", true, 17, [](Builder& b) {
    const uint32_t p = b.new_id(Expect::kPalm);
    b.palm(p, 0, 900 * kMs, [](int64_t) { return V2{150, 110}; });
  });

  add("finger_ink_two_finger_pan", true, 18, [](Builder& b) {
    const uint32_t a = b.new_id(Expect::kGesture), c = b.new_id(Expect::kGesture);
    b.finger(a, 0, 400 * kMs, [](int64_t t) { return V2{90, 100 - 50 * float(t) / float(400 * kMs)}; });
    b.finger(c, 40 * kMs, 400 * kMs, [](int64_t t) { return V2{130, 102 - 50 * float(t) / float(360 * kMs)}; });
  });

  add("finger_ink_after_pen", true, 19, [](Builder& b) {
    const int64_t e = b.write(0, line_x(b), 50, 3);
    const uint32_t f = b.new_id(Expect::kInk);
    b.finger(f, e + 3000 * kMs, e + 3300 * kMs, [](int64_t t) {
      const float k = float(t) / float(300 * kMs);
      return V2{60 + 50 * k, 120};
    });
  });

  add("finger_ink_palm_while_writing", true, 20, [](Builder& b) {
    const V2 off = b.palm_offset();
    const int64_t e = b.write(0, line_x(b), 60, 4);
    const uint32_t p = b.new_id(Expect::kPalm);
    b.palm(p, 150 * kMs, e + 100 * kMs, palm_follow(b, off));
  });

  return out;
}

// ---------------------------------------------------------------------------
// Replay and scoring
// ---------------------------------------------------------------------------

namespace {
struct StreamCheck {
  std::map<uint32_t, int> open;  // gesture id → 1 while down
  bool ink_open = false;
  bool ok = true;
  std::string err;
  void fail(const std::string& e) {
    if (ok) err = e;
    ok = false;
  }
  void gesture(const GestureEvent& g) {
    int& st = open[g.id];
    switch (g.action) {
      case GestureAction::kDown: if (st) fail("gesture down twice " + std::to_string(g.id)); st = 1; break;
      case GestureAction::kMove: if (!st) fail("gesture move without down " + std::to_string(g.id)); break;
      case GestureAction::kUp:
      case GestureAction::kCancel: if (!st) fail("gesture end without down " + std::to_string(g.id)); st = 0; break;
    }
  }
  void ink(const InkSample& s) {
    switch (s.action) {
      case Action::kDown: if (ink_open) fail("ink down while a stroke is open"); ink_open = true; break;
      case Action::kMove: if (!ink_open) fail("ink move without down"); break;
      case Action::kUp:
      case Action::kCancel: if (!ink_open) fail("ink end without down"); ink_open = false; break;
      default: break;
    }
  }
};
}  // namespace

int64_t scenario_end(const Scenario& s) { return s.events.empty() ? 0 : s.events.back().t; }

ReplayOutput replay(const Scenario& s, PalmFilter& f, int64_t off) {
  ReplayOutput out;
  StreamCheck check;
  f.set_view(s.view_w, s.view_h, s.px_per_mm);
  f.set_finger_ink(s.finger_ink);
  auto collect = [&] {
    for (uint32_t i = 0; i < f.gesture_count(); ++i) {
      out.gestures.push_back(f.gestures()[i]);
      check.gesture(f.gestures()[i]);
    }
    for (uint32_t i = 0; i < f.ink_count(); ++i) {
      out.ink.push_back(f.ink()[i]);
      check.ink(f.ink()[i]);
    }
    f.clear_outputs();
    ContactReport r[16];
    for (uint32_t n; (n = f.drain_reports(r, 16)) > 0;) out.reports.insert(out.reports.end(), r, r + n);
  };
  auto run_timers = [&](int64_t& now, int64_t until) {
    for (int guard = 0; guard < 100000; ++guard) {
      const int64_t d = f.next_deadline(now);
      if (d < 0 || now + d > until) break;
      now += std::max<int64_t>(d, 1);
      f.tick(now);
      collect();
    }
  };
  int64_t now = (s.events.empty() ? 0 : s.events.front().t) + off;
  for (const TimelineEvent& e0 : s.events) {
    TimelineEvent e = e0;
    e.t += off;
    e.ink.t_ns += off;
    e.ink.t_recv_ns += off;
    e.touch.t_ns += off;
    run_timers(now, e.t);
    now = std::max(now, e.t);
    if (e.pen) f.observe_pen(&e.ink, 1);
    else f.process(&e.touch, 1);
    collect();
  }
  run_timers(now, now + 5'000 * kMs);
  if (f.live_contacts() || f.ink_active()) check.fail("contacts still live after the scenario");
  for (const auto& [id, st] : check.open)
    if (st) check.fail("gesture never ended " + std::to_string(id));
  if (check.ink_open) check.fail("ink stroke never ended");
  out.streams_well_formed = check.ok;
  out.stream_error = check.err;
  return out;
}

void score(const Scenario& s, const ReplayOutput& out, Score* acc) {
  char buf[256];
  if (!out.streams_well_formed) acc->failures.push_back(s.name + ": malformed output: " + out.stream_error);
  for (const ContactReport& r : out.reports) {
    auto it = s.expect.find(r.id);
    const Expect e = it == s.expect.end() ? Expect::kAny : it->second;
    switch (e) {
      case Expect::kPalm:
        ++acc->palms;
        if (r.gesture || r.ink_committed) {
          ++acc->false_accepts;
          std::snprintf(buf, sizeof(buf), "%s: palm #%u accepted (%s, peak score %.2f)", s.name.c_str(), r.id,
                        r.gesture ? "gesture" : "ink", r.peak_score);
          acc->failures.push_back(buf);
        } else if (r.ink_started) {
          ++acc->transient_ink;
        }
        break;
      case Expect::kGesture:
        ++acc->touches;
        if (!r.gesture || r.gesture_cancelled) {
          ++acc->false_rejects;
          std::snprintf(buf, sizeof(buf), "%s: touch #%u rejected (%s, final score %.2f, peak %.2f)", s.name.c_str(),
                        r.id, r.gesture_cancelled ? "cancelled" : "never accepted", r.final_score, r.peak_score);
          acc->failures.push_back(buf);
        } else {
          acc->touch_latency_ms.push_back(double(r.t_decided_ns - r.t_down_ns) * 1e-6);
        }
        break;
      case Expect::kInk:
        ++acc->inks;
        if (!r.ink_committed) {
          ++acc->false_rejects;
          std::snprintf(buf, sizeof(buf), "%s: finger stroke #%u lost (score %.2f)", s.name.c_str(), r.id,
                        r.final_score);
          acc->failures.push_back(buf);
        }
        break;
      case Expect::kAny:
        break;
    }
  }
}

bool load_touch_csv(const std::string& path, bool finger_ink, Scenario* out, std::string* err) {
  std::ifstream in(path);
  if (!in) {
    *err = "cannot open " + path;
    return false;
  }
  out->name = path;
  out->finger_ink = finger_ink;
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty() || line[0] == 'k') continue;  // header
    std::stringstream ss(line);
    std::string f[14];
    for (int i = 0; i < 14 && std::getline(ss, f[i], ','); ++i) {}
    TimelineEvent e{};
    e.t = std::strtoll(f[1].c_str(), nullptr, 10);
    const float x = std::strtof(f[6].c_str(), nullptr), y = std::strtof(f[7].c_str(), nullptr);
    if (f[0] == "P") {
      e.pen = true;
      e.ink.t_ns = e.ink.t_recv_ns = e.t;
      e.ink.action = Action(std::atoi(f[3].c_str()));
      e.ink.flags = uint16_t(std::atoi(f[5].c_str()));
      e.ink.x = x;
      e.ink.y = y;
      e.ink.orientation = std::strtof(f[10].c_str(), nullptr);
      e.ink.pressure = std::strtof(f[11].c_str(), nullptr);
      e.ink.tilt = std::strtof(f[12].c_str(), nullptr);
    } else if (f[0] == "T") {
      e.touch.t_ns = e.t;
      e.touch.id = uint32_t(std::strtoul(f[2].c_str(), nullptr, 10));
      e.touch.action = TouchAction(std::atoi(f[3].c_str()));
      e.touch.tool = ToolType(std::atoi(f[4].c_str()));
      e.touch.flags = uint8_t(std::atoi(f[5].c_str()));
      e.touch.x = x;
      e.touch.y = y;
      e.touch.major = std::strtof(f[8].c_str(), nullptr);
      e.touch.minor = std::strtof(f[9].c_str(), nullptr);
      e.touch.orientation = std::strtof(f[10].c_str(), nullptr);
      e.touch.pressure = std::strtof(f[11].c_str(), nullptr);
      const std::string& label = f[13];
      out->expect[e.touch.id] = label == "palm" ? Expect::kPalm : label == "touch" ? Expect::kGesture : Expect::kAny;
      if (finger_ink && label == "touch") out->expect[e.touch.id] = Expect::kAny;  // gesture or ink: both fine
    } else {
      continue;
    }
    out->events.push_back(e);
  }
  std::stable_sort(out->events.begin(), out->events.end(),
                   [](const TimelineEvent& a, const TimelineEvent& b) { return a.t < b.t; });
  if (out->events.empty()) {
    *err = "no events in " + path;
    return false;
  }
  return true;
}

}  // namespace noti::tools
