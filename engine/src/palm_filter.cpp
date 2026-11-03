#include "noti/palm_filter.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace noti {
namespace {

constexpr float kPi = 3.14159265358979f;
constexpr int64_t kMs = 1'000'000;

// Base log-odds with no evidence at all: a slight lean towards "deliberate",
// so a clean fingertip far from any pen activity is accepted on its first
// sample.
constexpr float kBase = -0.5f;

// Evidence weights (log-likelihood ratios, natural log). See palm_filter.h.
constexpr float kPenTouching = 5.5f;
constexpr float kPenHovering = 5.0f;
constexpr float kPenJustLifted = 4.5f;    // at the moment of lift, decaying over the grace window
constexpr float kPenAfterGrace = 1.0f;    // "the pen is in use", decaying over kPenInUseNs
constexpr int64_t kPenInUseNs = 10'000 * kMs;
constexpr float kPenLateFactor = 0.9f;    // pen activity that starts after touch-down
constexpr float kPenConcurrent = 3.0f;    // pen touched down while the contact was on the glass

constexpr float kHandMax = 3.0f, kHandMin = -2.5f;
// With the nib position certain (pen hovering or touching), a contact the
// writing hand cannot physically reach is strong evidence of a deliberate touch.
constexpr float kHandMinCertain = -4.5f;
constexpr float kNearNibMm = 12.0f, kNearNib = 1.5f;
constexpr int64_t kHandRecencyNs = 4'000 * kMs;

constexpr float kSizeSlope = 0.9f, kSizeMin = -2.0f, kSizeMax = 4.0f;
constexpr float kElongated = 0.8f;
constexpr int64_t kGrowthWindowNs = 25 * kMs;
constexpr float kGrowthSlope = 2.5f, kGrowthMin = -0.8f, kGrowthMax = 1.8f;

constexpr float kPurposeful = 1.8f;          // full at 8 mm of straight, brisk travel
constexpr float kPurposefulPerMm = 0.08f;     // ... and more beyond, up to kPurposefulMax
constexpr float kPurposefulMax = 3.5f;
constexpr float kDecoupledMm = 12.0f, kDecoupledFullMm = 25.0f, kDecoupled = 2.5f;
constexpr int64_t kRestStartNs = 400 * kMs;
constexpr float kRestPerSec = 3.0f, kRestMax = 2.5f;
constexpr float kTap = 1.2f;
// A contact still undecided when it lifts counts as a tap only with real
// evidence for it (a clean tap far from any pen activity scores about -1.7).
constexpr float kTapAccept = -1.0f;
constexpr float kEdgeMm = 5.0f, kEdge = 1.0f;

constexpr float kClusterMm = 60.0f;
constexpr int64_t kClusterNs = 400 * kMs;
constexpr float kClusterPalm = 3.0f, kClusterCrowd = 2.0f, kTwoFinger = 1.5f;
constexpr int64_t kRecentPalmNs = 300 * kMs;
constexpr float kLingerRevokeMm = 40.0f;

constexpr float kFingerPressure = 0.7f;

inline float wrap_pi(float a) {
  while (a > kPi) a -= 2 * kPi;
  while (a < -kPi) a += 2 * kPi;
  return a;
}

inline float clampf(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }
inline bool finite(float v) { return std::isfinite(v); }

}  // namespace

// ---------------------------------------------------------------------------
// Recorder
// ---------------------------------------------------------------------------

struct PalmFilter::Recorder {
  struct Row {
    int64_t t;
    float x, y, major, minor, orientation, pressure, tilt;
    uint32_t id;
    uint8_t kind;  // 'P' pen, 'T' touch
    uint8_t action, tool, flags;
  };
  std::vector<Row> rows;
  char label[16] = "?";
};

void PalmFilter::set_recording(bool on, const char* label) noexcept {
  if (!on) {
    rec_.reset();
    return;
  }
  if (!rec_) {
    rec_ = std::make_unique<Recorder>();
    rec_->rows.reserve(1u << 18);
  }
  std::snprintf(rec_->label, sizeof(rec_->label), "%s", label ? label : "?");
}

void PalmFilter::record_touch(const TouchSample& s) noexcept {
  if (!rec_) return;
  rec_->rows.push_back({s.t_ns, s.x, s.y, s.major, s.minor, s.orientation, s.pressure, 0.0f, s.id, 'T',
                        uint8_t(s.action), uint8_t(s.tool), s.flags});
}

void PalmFilter::record_pen(const InkSample& s) noexcept {
  if (!rec_) return;
  rec_->rows.push_back({s.t_ns, s.x, s.y, 0.0f, 0.0f, s.orientation, s.pressure, s.tilt, 0u, 'P',
                        uint8_t(s.action), uint8_t(ToolType::kStylus), uint8_t(s.flags)});
}

int64_t PalmFilter::export_recording(const char* path) const {
  if (!rec_) return -1;
  FILE* f = std::fopen(path, "w");
  if (!f) return -1;
  std::fprintf(f, "kind,t_ns,id,action,tool,flags,x,y,major,minor,orientation,pressure,tilt,label\n");
  for (const Recorder::Row& r : rec_->rows) {
    std::fprintf(f, "%c,%lld,%u,%u,%u,%u,%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%s\n", r.kind, (long long)r.t, r.id,
                 r.action, r.tool, r.flags, r.x, r.y, r.major, r.minor, r.orientation, r.pressure, r.tilt,
                 r.kind == 'T' ? rec_->label : "");
  }
  const bool ok = std::fclose(f) == 0;
  return ok ? int64_t(rec_->rows.size()) : -1;
}

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

PalmFilter::PalmFilter(const PalmConfig& cfg) : cfg_(cfg) {
  // Cold-start hand model: a right-handed writer's palm rests below and to the
  // right of the nib, a left-handed writer's below and to the left. Neither is
  // assumed; a few confident palms settle it.
  Gauss& r = hand_[0];
  r.w = 0.5f; r.mx = 45.0f; r.my = 55.0f; r.sxx = 30.0f * 30.0f; r.syy = 30.0f * 30.0f; r.sxy = 150.0f; r.n = 3.0f;
  Gauss& l = hand_[1];
  l.w = 0.5f; l.mx = -45.0f; l.my = 55.0f; l.sxx = 30.0f * 30.0f; l.syy = 30.0f * 30.0f; l.sxy = -150.0f; l.n = 3.0f;
  for (RecentPalm& p : recent_palms_) p = {INT64_MIN / 2, 0, 0};
}

PalmFilter::~PalmFilter() = default;

void PalmFilter::set_finger_ink(bool on) noexcept {
  cfg_.finger_ink = on;
  if (!on && ink_slot_ >= 0) revoke_ink(last_time_);
}

void PalmFilter::set_view(float w, float h, float px_per_mm) noexcept {
  cfg_.view_w = w;
  cfg_.view_h = h;
  if (px_per_mm > 0.5f && finite(px_per_mm)) cfg_.px_per_mm = px_per_mm;
}

float PalmFilter::grace_ms() const noexcept { return clampf(2.5f * gap_ms_, 300.0f, 1500.0f); }

float PalmFilter::size_boundary_mm() const noexcept { return clampf(0.5f * (finger_mm_ + palm_mm_), 10.0f, 18.0f); }

PalmFilter::Contact* PalmFilter::find(uint32_t id) noexcept {
  for (Contact& c : contacts_)
    if (c.used && !c.up && c.id == id) return &c;
  return nullptr;
}

const PalmFilter::Contact* PalmFilter::find(uint32_t id) const noexcept {
  for (const Contact& c : contacts_)
    if (c.used && !c.up && c.id == id) return &c;
  return nullptr;
}

Verdict PalmFilter::verdict(uint32_t id) const noexcept {
  const Contact* c = find(id);
  return c ? c->verdict : Verdict::kUndecided;
}

float PalmFilter::score(uint32_t id) const noexcept {
  const Contact* c = find(id);
  return c ? c->score : std::nanf("");
}

uint32_t PalmFilter::live_contacts() const noexcept {
  uint32_t n = 0;
  for (const Contact& c : contacts_) n += c.used && !c.up;
  return n;
}

// ---------------------------------------------------------------------------
// Pen (L1 inputs)
// ---------------------------------------------------------------------------

void PalmFilter::observe_pen(const InkSample* s, uint32_t n) noexcept {
  for (uint32_t i = 0; i < n; ++i) observe_one_pen(s[i]);
}

bool PalmFilter::pen_in_context(int64_t t) const noexcept {
  if (!pen_ever_) return false;
  if (pen_ != PenState::kAway) return true;
  return t - t_pen_left_ < int64_t(grace_ms() * float(kMs));
}

void PalmFilter::observe_one_pen(const InkSample& s) noexcept {
  record_pen(s);
  const int64_t t = s.t_ns;
  last_time_ = std::max(last_time_, t);
  const bool has_pos = s.action != Action::kHoverExit;
  if (has_pos) {
    pen_x_ = s.x;
    pen_y_ = s.y;
    pen_tilt_ = s.tilt;
    pen_az_ = s.orientation;
  }
  switch (s.action) {
    case Action::kHoverEnter:
      pen_ever_ = true;
      t_pen_seen_ = t;
      if (pen_ != PenState::kTouch) pen_ = PenState::kHover;
      on_pen_active(t);
      break;
    case Action::kHoverMove:
      pen_ever_ = true;
      t_pen_seen_ = t;
      if (pen_ == PenState::kAway) {
        pen_ = PenState::kHover;
        on_pen_active(t);
      }
      break;
    case Action::kHoverExit:
      t_pen_seen_ = t;
      for (Contact& c : contacts_) c.nib_tracked = false;  // the nib is no longer known
      if (pen_ == PenState::kHover) {
        pen_ = PenState::kAway;
        t_pen_left_ = t;
      }
      break;
    case Action::kDown: {
      if (t_pen_up_ > 0) {
        const float gap = float(t - t_pen_up_) / float(kMs);
        if (gap > 30.0f && gap < 2000.0f) gap_ms_ = clampf(gap_ms_ + 0.1f * (gap - gap_ms_), 60.0f, 1500.0f);
      }
      pen_ever_ = true;
      pen_ = PenState::kTouch;
      t_pen_seen_ = t;
      for (Contact& c : contacts_)
        if (c.used && !c.up && c.t_down < t && c.t_pen_down_during == 0) c.t_pen_down_during = t;
      on_pen_active(t);
      break;
    }
    case Action::kMove:
      t_pen_seen_ = t;
      if (pen_ != PenState::kTouch) {  // missed DOWN: still writing
        pen_ever_ = true;
        pen_ = PenState::kTouch;
        on_pen_active(t);
      }
      if (s.tilt > 0.2f && finite(s.orientation)) {
        // Circular mean and spread of the azimuth while writing.
        const float cx = std::cos(s.orientation), cy = std::sin(s.orientation);
        az_cx_ += 0.02f * (cx - az_cx_);
        az_cy_ += 0.02f * (cy - az_cy_);
        const float mean = std::atan2(az_cy_, az_cx_);
        az_spread_ += 0.02f * (std::fabs(wrap_pi(s.orientation - mean)) - az_spread_);
      }
      break;
    case Action::kUp:
    case Action::kCancel:
      t_pen_seen_ = t;
      pen_ = PenState::kAway;  // a hover event follows if the pen is still in range
      t_pen_left_ = t_pen_up_ = t;
      break;
  }
}

void PalmFilter::on_pen_active(int64_t t) noexcept {
  // The pen is coming in: a finger stroke in progress (or lingering) was the hand.
  if (ink_slot_ >= 0) revoke_ink(t);
  for (Contact& c : contacts_) {
    if (!c.used || c.up || c.final_palm) continue;
    c.pen_prior = std::max(c.pen_prior, kPenLateFactor * pen_prior(t, c.x, c.y));
  }
  rescore_all(t);
}

// ---------------------------------------------------------------------------
// Evidence
// ---------------------------------------------------------------------------

float PalmFilter::pen_prior(int64_t t, float, float) const noexcept {
  if (!pen_ever_) return 0.0f;
  switch (pen_) {
    case PenState::kTouch: return kPenTouching;
    case PenState::kHover: return kPenHovering;
    case PenState::kAway: break;
  }
  const float since = float(std::max<int64_t>(0, t - t_pen_left_));
  const float grace = grace_ms() * float(kMs);
  if (since < grace) return kPenJustLifted - (kPenJustLifted - kPenAfterGrace) * (since / grace);
  return kPenAfterGrace * std::exp(-(since - grace) / float(kPenInUseNs));
}

void PalmFilter::rotate_to_hand_frame(float* dx, float* dy) const noexcept {
  if (rot_mode_ == 0 || az_spread_ < 0.03f || pen_tilt_ < 0.2f) return;
  const float mean = std::atan2(az_cy_, az_cx_);
  const float a = float(rot_mode_) * wrap_pi(pen_az_ - mean);
  // Undo the hand's swing around the nib (screen y points down).
  const float c = std::cos(a), s = std::sin(a);
  const float x = *dx, y = *dy;
  *dx = x * c + y * s;
  *dy = -x * s + y * c;
}

namespace {
float gauss_pdf(float dx, float dy, float mx, float my, float sxx, float sxy, float syy) {
  const float det = sxx * syy - sxy * sxy;
  if (!(det > 1e-3f)) return 0.0f;
  const float x = dx - mx, y = dy - my;
  const float md2 = (syy * x * x - 2.0f * sxy * x * y + sxx * y * y) / det;
  return std::exp(-0.5f * md2) / (2.0f * kPi * std::sqrt(det));
}
}  // namespace

float PalmFilter::hand_llr(int64_t t, float x, float y) const noexcept {
  if (!pen_ever_) return 0.0f;
  const float recency = pen_ != PenState::kAway
                            ? 1.0f
                            : std::exp(-float(std::max<int64_t>(0, t - t_pen_seen_)) / float(kHandRecencyNs));
  if (recency < 0.02f) return 0.0f;
  float dx = mm(x - pen_x_), dy = mm(y - pen_y_);
  const float dist = std::sqrt(dx * dx + dy * dy);
  rotate_to_hand_frame(&dx, &dy);
  float p = 0.0f;
  for (const Gauss& g : hand_) p += g.w * gauss_pdf(dx, dy, g.mx, g.my, g.sxx, g.sxy, g.syy);
  const float w_mm = cfg_.view_w > 0 ? mm(cfg_.view_w) : 240.0f;
  const float h_mm = cfg_.view_h > 0 ? mm(cfg_.view_h) : 160.0f;
  const float p_touch = 1.0f / std::max(1000.0f, w_mm * h_mm);  // deliberate touches: anywhere
  const float lo = pen_ != PenState::kAway ? kHandMinCertain : kHandMin;
  float llr = p > 0.0f ? std::log(p / p_touch) : lo;
  llr = clampf(llr, lo, kHandMax);
  // The knuckles of the hand holding the pen land right next to the nib.
  if (pen_ != PenState::kAway && dist < kNearNibMm) llr += kNearNib;
  return llr * recency;
}

float PalmFilter::evaluate(Contact& c, int64_t now) noexcept {
  c.t_eval = now;
  if (c.system_palm) return 99.0f;
  float s = kBase + c.pen_prior;

  // L2: hand model.
  c.hand_term = hand_llr(now, c.x, c.y);
  s += c.hand_term;

  // L3: contact shape.
  if (c.size_seen) {
    // Palms roll on: their first samples are fingertip-sized. Small size only
    // counts as evidence for a deliberate touch once the roll-on window passed.
    const bool settled = c.up || c.t_last - c.t_down >= kGrowthWindowNs;
    const float size = clampf(kSizeSlope * (c.peak_major_mm - size_boundary_mm()), kSizeMin, kSizeMax);
    s += settled ? size : std::max(0.0f, size);
    if (c.minor_at_peak_mm > 0.0f && c.peak_major_mm > 12.0f && c.minor_at_peak_mm / c.peak_major_mm < 0.55f)
      s += kElongated;
    if (c.major0_mm > 0.0f && (c.up || c.t_last - c.t_down >= kGrowthWindowNs)) {
      const float g = c.peak_major_mm / c.major0_mm;
      s += clampf(kGrowthSlope * (g - 1.25f), kGrowthMin, kGrowthMax);
    }
  }

  // L3: motion.
  const int64_t end = c.up ? c.t_up : std::max(now, c.t_last);
  const int64_t dur = std::max<int64_t>(0, end - c.t_down);
  const float disp = std::hypot(mm(c.x - c.x0), mm(c.y - c.y0));
  if (disp > 3.0f) {
    const float straight = disp / std::max(c.path_mm, 1e-3f);
    const float speed = disp / std::max(1e-3f, float(dur) * 1e-9f);
    if (straight > 0.8f && speed > 20.0f)
      s -= std::min(kPurposefulMax, kPurposeful * std::min(1.0f, disp / 8.0f) +
                                        kPurposefulPerMm * std::max(0.0f, disp - 8.0f));
  }
  // L2: the writing hand moves rigidly with the nib. A contact travelling on its
  // own while the nib is tracked and stays put is not that hand.
  if (c.nib_tracked) {
    const float rel = std::hypot(mm((c.x - c.x0) - (pen_x_ - c.nib_x0)), mm((c.y - c.y0) - (pen_y_ - c.nib_y0)));
    if (rel > kDecoupledMm) s -= kDecoupled * std::min(1.0f, (rel - kDecoupledMm) / (kDecoupledFullMm - kDecoupledMm));
  }
  if (dur > kRestStartNs && c.max_disp_mm < 2.5f)
    s += std::min(kRestMax, float(dur - kRestStartNs) * 1e-9f * kRestPerSec);
  if (c.up && dur < 250 * kMs && c.max_disp_mm < 2.0f) s -= kTap;

  // L3: bezel edge (a hand gripping the tablet).
  if (cfg_.view_w > 0 && cfg_.view_h > 0) {
    const float e = mm(std::min(std::min(c.x, c.y), std::min(cfg_.view_w - c.x, cfg_.view_h - c.y)));
    if (e < kEdgeMm && (!c.size_seen || c.peak_major_mm > 10.0f)) s += kEdge;
  }

  // L1: the pen went down while this contact was on the glass.
  if (c.t_pen_down_during && c.hand_term > -1.0f) s += kPenConcurrent;

  // L4: clusters.
  int neighbours = 0;
  bool palm_neighbour = false, two_finger = false;
  const float b = size_boundary_mm();
  for (const Contact& o : contacts_) {
    if (!o.used || &o == &c) continue;
    const float d = std::hypot(mm(o.x - c.x), mm(o.y - c.y));
    const int64_t dt = std::llabs(o.t_down - c.t_down);
    if (d < kClusterMm && dt < kClusterNs) {
      ++neighbours;
      if (o.verdict == Verdict::kPalm) palm_neighbour = true;
    }
    // Two fingertips landing together a finger-spread apart. Needs sizes: without
    // them a palm and its pinky look exactly the same.
    if (!o.up && o.verdict != Verdict::kPalm && dt < 120 * kMs && d > 15.0f && d < 90.0f && o.size_seen &&
        c.size_seen && o.peak_major_mm < b && c.peak_major_mm < b)
      two_finger = true;
  }
  for (const RecentPalm& p : recent_palms_) {
    if (c.t_down - p.t < kRecentPalmNs && c.t_down >= p.t &&
        std::hypot(mm(p.x - c.x), mm(p.y - c.y)) < kClusterMm)
      palm_neighbour = true;
  }
  if (palm_neighbour) s += kClusterPalm;
  if (neighbours >= 2) s += kClusterCrowd;
  else if (two_finger && neighbours <= 1 && pen_ != PenState::kTouch) s -= kTwoFinger;
  return s;
}

void PalmFilter::rescore_all(int64_t now) noexcept {
  for (Contact& c : contacts_)
    if (c.used && !c.up) update(c, now);
}

void PalmFilter::update(Contact& c, int64_t now) noexcept {
  if (c.final_palm) return;
  c.score = evaluate(c, now);
  c.peak_score = std::max(c.peak_score, c.score);
  if (c.verdict == Verdict::kTouch) {
    if (c.score >= cfg_.decide_palm + cfg_.revoke_margin) decide_palm(c, now, true);
    else if (c.ink == Ink::kTentative && c.score >= cfg_.decide_palm) decide_palm(c, now, true);
    return;
  }
  if (c.score >= cfg_.decide_palm) {
    decide_palm(c, now, true);
    return;
  }
  maybe_start_ink(c, now);
  if (c.score <= cfg_.decide_touch) decide_touch(c, now);
}

// ---------------------------------------------------------------------------
// Decisions and outputs (L5)
// ---------------------------------------------------------------------------

void PalmFilter::decide_touch(Contact& c, int64_t now) noexcept {
  if (c.verdict == Verdict::kTouch) return;
  c.verdict = Verdict::kTouch;
  if (!c.t_decided) c.t_decided = now;
  if (c.ink == Ink::kTentative || c.ink == Ink::kLinger) return;  // it's ink, not a gesture
  if (ink_slot_ >= 0) return;  // gestures wait while a finger stroke is being drawn
  replay_gesture(c);
}

void PalmFilter::decide_palm(Contact& c, int64_t now, bool final) noexcept {
  c.verdict = Verdict::kPalm;
  c.final_palm = final;
  if (!c.t_decided) c.t_decided = now;
  if (c.gesture && !c.gesture_cancelled) {
    emit_gesture(GestureAction::kCancel, c, now, c.x, c.y);
    c.gesture_cancelled = true;
  }
  if (ink_slot_ == slot_of(c)) revoke_ink(now);
  // L4: its neighbours are now probably the same hand.
  for (Contact& o : contacts_) {
    if (!o.used || o.up || &o == &c || o.final_palm) continue;
    if (std::hypot(mm(o.x - c.x), mm(o.y - c.y)) < kClusterMm && std::llabs(o.t_down - c.t_down) < kClusterNs)
      update(o, now);
  }
  // A palm landing on a just-finished finger stroke means that stroke was the hand.
  if (ink_slot_ >= 0) {
    Contact& owner = contacts_[ink_slot_];
    if (owner.ink == Ink::kLinger && std::hypot(mm(owner.x - c.x), mm(owner.y - c.y)) < kLingerRevokeMm)
      revoke_ink(now);
  }
  // L6: learn where the hand rests, from palms that are palms regardless of where they are.
  if (!c.learned && !c.system_palm && pen_in_context(now) && c.score - c.hand_term >= cfg_.decide_palm - 1.0f) {
    c.learned = true;
    learn_hand(mm(c.x - pen_x_), mm(c.y - pen_y_));
  }
}

void PalmFilter::emit_gesture(GestureAction a, const Contact& c, int64_t t, float x, float y) noexcept {
  if (n_gestures_ >= kMaxGestures) return;
  GestureEvent& e = gestures_[n_gestures_++];
  e = {};
  e.t_ns = t;
  e.x = x;
  e.y = y;
  e.id = c.id;
  e.action = a;
}

void PalmFilter::replay_gesture(Contact& c) noexcept {
  if (c.gesture) return;
  c.gesture = true;
  emit_gesture(GestureAction::kDown, c, c.first.t, c.first.x, c.first.y);
  const uint32_t start = c.ring_head + kReplay - c.ring_n;
  for (uint32_t i = 0; i < c.ring_n; ++i) {
    const Pt& p = c.ring[(start + i) % kReplay];
    if (p.t > c.first.t) emit_gesture(GestureAction::kMove, c, p.t, p.x, p.y);
  }
}

void PalmFilter::emit_ink(Action a, int64_t t, float x, float y) noexcept {
  if (n_ink_ >= kMaxInk) return;
  InkSample& s = ink_[n_ink_++];
  s = {};
  s.t_ns = t;
  s.t_recv_ns = t;
  s.x = x;
  s.y = y;
  s.pressure = kFingerPressure;
  s.action = a;
  s.flags = kFlagFinger;
}

void PalmFilter::maybe_start_ink(Contact& c, int64_t now) noexcept {
  if (!cfg_.finger_ink || ink_slot_ >= 0 || c.ink != Ink::kNone || c.up) return;
  if (c.verdict == Verdict::kPalm || c.gesture) return;
  if (pen_ != PenState::kAway || pen_prior(now, c.x, c.y) >= 1.0f) return;  // the pen is in use
  if (now - c.t_down > cfg_.ink_start_window_ns) return;  // too late: a gesture or a palm
  if (c.score >= cfg_.ink_start_max) return;
  for (const Contact& o : contacts_)
    if (o.used && &o != &c && !o.up && o.verdict != Verdict::kPalm) return;  // more than one finger
  ink_slot_ = slot_of(c);
  c.ink = Ink::kTentative;
  // Draw everything since touch-down: no ink is lost to the decision.
  emit_ink(Action::kDown, c.first.t, c.first.x, c.first.y);
  const uint32_t start = c.ring_head + kReplay - c.ring_n;
  for (uint32_t i = 0; i < c.ring_n; ++i) {
    const Pt& p = c.ring[(start + i) % kReplay];
    if (p.t > c.first.t) emit_ink(Action::kMove, p.t, p.x, p.y);
  }
}

void PalmFilter::revoke_ink(int64_t t) noexcept {
  if (ink_slot_ < 0) return;
  Contact& c = contacts_[ink_slot_];
  emit_ink(Action::kCancel, t, c.x, c.y);
  c.ink = Ink::kRevoked;
  ink_slot_ = -1;
  if (c.up) finish(c);
  release_suppressed_gestures(t);
}

void PalmFilter::commit_ink(int64_t t) noexcept {
  if (ink_slot_ < 0) return;
  Contact& c = contacts_[ink_slot_];
  emit_ink(Action::kUp, std::max(t, c.t_up), c.x, c.y);
  c.ink = Ink::kCommitted;
  ink_slot_ = -1;
  finish(c);
  release_suppressed_gestures(t);
}

void PalmFilter::release_suppressed_gestures(int64_t) noexcept {
  for (Contact& o : contacts_) {
    if (!o.used || o.up || o.gesture || o.verdict != Verdict::kTouch) continue;
    // A revoked stroke was the hand (or became a two-finger gesture, which
    // replays itself); a running or kept one is ink, not a gesture.
    if (o.ink != Ink::kNone) continue;
    replay_gesture(o);
  }
}

// ---------------------------------------------------------------------------
// Contacts
// ---------------------------------------------------------------------------

void PalmFilter::process(const TouchSample* s, uint32_t n) noexcept {
  for (uint32_t i = 0; i < n; ++i) {
    const TouchSample& t = s[i];
    record_touch(t);
    last_time_ = std::max(last_time_, t.t_ns);
    if (t.tool == ToolType::kStylus || t.tool == ToolType::kEraser) continue;  // the pen path owns these
    Contact* c = find(t.id);
    switch (t.action) {
      case TouchAction::kDown:
        if (c) on_up(*c, t, true);  // missed UP for a reused id
        on_down(t);
        break;
      case TouchAction::kMove:
        if (c) on_move(*c, t);
        else if (!(t.flags & kTouchHistorical)) on_down(t);  // missed DOWN: treat as new
        break;
      case TouchAction::kUp:
        if (c) on_up(*c, t, (t.flags & kTouchCanceled) != 0);
        break;
      case TouchAction::kCancel:
        if (c) on_up(*c, t, true);
        break;
    }
  }
}

void PalmFilter::on_down(const TouchSample& s) noexcept {
  Contact* slot = nullptr;
  for (Contact& c : contacts_)
    if (!c.used) { slot = &c; break; }
  if (!slot) return;  // more than 16 contacts: ignore (nothing deliberate looks like that)
  Contact& c = *slot;
  c = Contact{};
  c.used = true;
  c.id = s.id;
  c.serial = next_serial_++;
  c.t_down = c.t_last = s.t_ns;
  c.x0 = c.x = s.x;
  c.y0 = c.y = s.y;
  c.first = {s.t_ns, s.x, s.y};
  c.ring[0] = c.first;
  c.ring_n = 1;
  c.ring_head = 1;
  if (s.major > 0.0f) {
    c.size_seen = true;
    c.major0_mm = c.peak_major_mm = mm(s.major);
    c.minor_at_peak_mm = mm(s.minor);
  }
  c.system_palm = s.tool == ToolType::kPalm;
  c.pen_prior = pen_prior(s.t_ns, s.x, s.y);
  if (pen_ != PenState::kAway) {
    c.nib_tracked = true;
    c.nib_x0 = pen_x_;
    c.nib_y0 = pen_y_;
  }

  // A second finger right after a finger started drawing: it's a two-finger
  // gesture, not a stroke.
  if (ink_slot_ >= 0 && !c.system_palm) {
    Contact& owner = contacts_[ink_slot_];
    if (owner.ink == Ink::kTentative && s.t_ns - owner.t_down < cfg_.two_finger_window_ns &&
        owner.max_disp_mm < 10.0f && evaluate(c, s.t_ns) < cfg_.decide_palm) {
      revoke_ink(s.t_ns);
      if (owner.verdict == Verdict::kTouch && !owner.gesture) replay_gesture(owner);
    }
  }
  if (c.system_palm) {
    c.score = 99.0f;
    decide_palm(c, s.t_ns, true);
    return;
  }
  update(c, s.t_ns);
  // New evidence for the neighbours (cluster terms changed).
  for (Contact& o : contacts_)
    if (o.used && !o.up && &o != &c) update(o, s.t_ns);
}

void PalmFilter::on_move(Contact& c, const TouchSample& s) noexcept {
  const float step = std::hypot(mm(s.x - c.x), mm(s.y - c.y));
  c.path_mm += step;
  c.x = s.x;
  c.y = s.y;
  c.t_last = std::max(c.t_last, s.t_ns);
  c.max_disp_mm = std::max(c.max_disp_mm, std::hypot(mm(s.x - c.x0), mm(s.y - c.y0)));
  if (s.major > 0.0f) {
    const float m = mm(s.major);
    if (!c.size_seen) {
      c.size_seen = true;
      c.major0_mm = m;
    }
    if (m > c.peak_major_mm) {
      c.peak_major_mm = m;
      c.minor_at_peak_mm = mm(s.minor);
    }
  }
  if (s.tool == ToolType::kPalm) c.system_palm = true;
  c.ring[c.ring_head % kReplay] = {s.t_ns, s.x, s.y};
  c.ring_head = (c.ring_head + 1) % kReplay;
  c.ring_n = std::min(c.ring_n + 1, kReplay);

  if (c.final_palm) return;
  // A stroke or gesture that starts on this sample replays it (it is in the
  // ring already); only one that was already running gets it as a live move.
  const bool was_ink = c.ink == Ink::kTentative && ink_slot_ == slot_of(c);
  const bool was_gesture = c.gesture && !c.gesture_cancelled;
  update(c, s.t_ns);
  if (was_ink && c.ink == Ink::kTentative && ink_slot_ == slot_of(c)) emit_ink(Action::kMove, s.t_ns, s.x, s.y);
  else if (was_gesture && !c.gesture_cancelled) emit_gesture(GestureAction::kMove, c, s.t_ns, s.x, s.y);
}

void PalmFilter::on_up(Contact& c, const TouchSample& s, bool cancelled) noexcept {
  if (s.action != TouchAction::kCancel && (s.x != c.x || s.y != c.y)) {
    TouchSample m = s;
    m.action = TouchAction::kMove;
    on_move(c, m);
  }
  const int64_t t = std::max(s.t_ns, c.t_last);
  if (cancelled) {
    // FLAG_CANCELED on a pointer: the platform says it was a palm. ACTION_CANCEL:
    // the gesture was taken away (the stylus took over, or a system gesture).
    if (s.action == TouchAction::kUp) c.system_palm = true;
    if (!c.final_palm) decide_palm(c, t, true);
  }
  c.up = true;
  c.t_up = t;
  if (!c.final_palm) {
    update(c, t);
    if (c.verdict == Verdict::kUndecided) {
      // Out of time: decide on what we have. Ties go to "palm" for ink and
      // gestures alike: a missed tap is cheaper than a stray mark or jump.
      if (c.score < kTapAccept) {
        decide_touch(c, t);
      } else {
        decide_palm(c, t, true);
      }
    }
  }
  if (c.gesture && !c.gesture_cancelled) emit_gesture(GestureAction::kUp, c, t, c.x, c.y);
  if (c.ink == Ink::kTentative && ink_slot_ == slot_of(c)) {
    c.ink = Ink::kLinger;
    t_linger_end_ = t + cfg_.ink_linger_ns;
    if (cfg_.ink_linger_ns <= 0) commit_ink(t);
    return;  // the slot stays alive until the stroke is committed or revoked
  }
  finish(c);
}

void PalmFilter::finish(Contact& c) noexcept {
  if (!c.used) return;
  learn_from(c);
  if (c.verdict == Verdict::kPalm) {
    recent_palms_[recent_palm_next_++ % 8] = {c.t_up ? c.t_up : last_time_, c.x, c.y};
  }
  ContactReport r{};
  r.serial = c.serial;
  r.id = c.id;
  r.verdict = c.verdict;
  r.system_palm = c.system_palm;
  r.gesture = c.gesture;
  r.gesture_cancelled = c.gesture_cancelled;
  r.ink_started = c.ink != Ink::kNone;
  r.ink_committed = c.ink == Ink::kCommitted;
  r.t_down_ns = c.t_down;
  r.t_decided_ns = c.t_decided ? c.t_decided : c.t_up;
  r.t_up_ns = c.t_up;
  r.peak_score = c.peak_score;
  r.final_score = c.score;
  const uint32_t idx = (reports_head_ + reports_n_) % kMaxReports;
  reports_[idx] = r;
  if (reports_n_ < kMaxReports) ++reports_n_;
  else reports_head_ = (reports_head_ + 1) % kMaxReports;
  c.used = false;
}

uint32_t PalmFilter::drain_reports(ContactReport* out, uint32_t max) noexcept {
  uint32_t n = 0;
  while (reports_n_ && n < max) {
    out[n++] = reports_[reports_head_];
    reports_head_ = (reports_head_ + 1) % kMaxReports;
    --reports_n_;
  }
  return n;
}

// ---------------------------------------------------------------------------
// Timers
// ---------------------------------------------------------------------------

int64_t PalmFilter::tick(int64_t now) noexcept {
  last_time_ = std::max(last_time_, now);
  if (ink_slot_ >= 0 && contacts_[ink_slot_].ink == Ink::kLinger && now >= t_linger_end_) commit_ink(now);
  for (Contact& c : contacts_) {
    if (!c.used || c.up || c.final_palm) continue;
    if (now - c.t_eval >= cfg_.rescore_period_ns) update(c, now);
  }
  return next_deadline(now);
}

int64_t PalmFilter::next_deadline(int64_t now) const noexcept {
  int64_t at = INT64_MAX;
  if (ink_slot_ >= 0 && contacts_[ink_slot_].ink == Ink::kLinger) at = t_linger_end_;
  for (const Contact& c : contacts_) {
    if (!c.used || c.up || c.final_palm) continue;
    at = std::min(at, c.t_eval + cfg_.rescore_period_ns);
  }
  if (at == INT64_MAX) return -1;
  return std::max<int64_t>(0, at - now);
}

// ---------------------------------------------------------------------------
// Learning (L6)
// ---------------------------------------------------------------------------

void PalmFilter::learn_from(const Contact& c) noexcept {
  if (!c.size_seen) return;
  if (c.verdict == Verdict::kPalm && (c.system_palm || c.score - c.hand_term >= cfg_.decide_palm)) {
    palm_mm_ = clampf(palm_mm_ + 0.05f * (c.peak_major_mm - palm_mm_), 14.0f, 45.0f);
  } else if (c.verdict == Verdict::kTouch && !c.gesture_cancelled && c.ink != Ink::kRevoked &&
             c.score - c.hand_term <= cfg_.decide_touch && c.peak_major_mm > 3.0f) {
    finger_mm_ = clampf(finger_mm_ + 0.05f * (c.peak_major_mm - finger_mm_), 6.0f, 14.0f);
  }
}

void PalmFilter::learn_hand(float dx, float dy) noexcept {
  if (!finite(dx) || !finite(dy) || std::hypot(dx, dy) > 250.0f) return;
  // Choose the azimuth frame that explains palms best: track each candidate's
  // squared error against the dominant component.
  if (az_spread_ >= 0.03f && pen_tilt_ >= 0.2f) {
    const Gauss& dom = hand_[hand_[0].w >= hand_[1].w ? 0 : 1];
    const float mean = std::atan2(az_cy_, az_cx_);
    for (int m = -1; m <= 1; ++m) {
      const float a = float(m) * wrap_pi(pen_az_ - mean);
      const float c = std::cos(a), s = std::sin(a);
      const float rx = dx * c + dy * s, ry = -dx * s + dy * c;
      const float e = (rx - dom.mx) * (rx - dom.mx) + (ry - dom.my) * (ry - dom.my);
      rot_err_[m + 1] += 0.1f * (e - rot_err_[m + 1]);
    }
    int best = 1;
    for (int m = 0; m < 3; ++m)
      if (rot_err_[m] < rot_err_[best] * 0.9f) best = m;
    rot_mode_ = best - 1;
  }
  rotate_to_hand_frame(&dx, &dy);
  // E-step: which hand does this palm belong to?
  float p[2];
  for (int k = 0; k < 2; ++k) {
    const Gauss& g = hand_[k];
    p[k] = g.w * gauss_pdf(dx, dy, g.mx, g.my, g.sxx, g.sxy, g.syy) + 1e-12f;
  }
  const float sum = p[0] + p[1];
  // M-step, online: weights move slowly; each component learns in proportion
  // to its responsibility, with a capped count so the model keeps adapting.
  for (int k = 0; k < 2; ++k) {
    Gauss& g = hand_[k];
    const float r = p[k] / sum;
    g.w = clampf(g.w + 0.15f * (r - g.w), 0.03f, 0.97f);
    if (r < 0.05f) continue;
    g.n = std::min(g.n + r, 100.0f);
    const float a = r / (g.n + 1.0f);
    const float ex = dx - g.mx, ey = dy - g.my;
    g.mx += a * ex;
    g.my += a * ey;
    g.sxx = (1.0f - a) * (g.sxx + a * ex * ex);
    g.sxy = (1.0f - a) * (g.sxy + a * ex * ey);
    g.syy = (1.0f - a) * (g.syy + a * ey * ey);
    g.sxx = clampf(g.sxx, 12.0f * 12.0f, 45.0f * 45.0f);
    g.syy = clampf(g.syy, 12.0f * 12.0f, 45.0f * 45.0f);
    const float lim = 0.9f * std::sqrt(g.sxx * g.syy);
    g.sxy = clampf(g.sxy, -lim, lim);
  }
  const float wsum = hand_[0].w + hand_[1].w;
  hand_[0].w /= wsum;
  hand_[1].w /= wsum;
}

void PalmFilter::export_model(float out[kModelFloats]) const noexcept {
  std::memset(out, 0, sizeof(float) * kModelFloats);
  out[0] = kModelVersion;
  out[1] = hand_[0].w;
  for (int k = 0; k < 2; ++k) {
    const Gauss& g = hand_[k];
    float* o = out + 2 + k * 6;
    o[0] = g.mx; o[1] = g.my; o[2] = g.sxx; o[3] = g.sxy; o[4] = g.syy; o[5] = g.n;
  }
  out[14] = gap_ms_;
  out[15] = finger_mm_;
  out[16] = palm_mm_;
  out[17] = az_cx_;
  out[18] = az_cy_;
  out[19] = float(rot_mode_);
}

bool PalmFilter::import_model(const float in[kModelFloats]) noexcept {
  if (in[0] != kModelVersion) return false;
  for (uint32_t i = 0; i < kModelFloats; ++i)
    if (!finite(in[i])) return false;
  Gauss g[2];
  for (int k = 0; k < 2; ++k) {
    const float* o = in + 2 + k * 6;
    g[k].mx = clampf(o[0], -200.0f, 200.0f);
    g[k].my = clampf(o[1], -200.0f, 200.0f);
    g[k].sxx = clampf(o[2], 144.0f, 2025.0f);
    g[k].syy = clampf(o[4], 144.0f, 2025.0f);
    const float lim = 0.9f * std::sqrt(g[k].sxx * g[k].syy);
    g[k].sxy = clampf(o[3], -lim, lim);
    g[k].n = clampf(o[5], 0.0f, 100.0f);
  }
  g[0].w = clampf(in[1], 0.03f, 0.97f);
  g[1].w = 1.0f - g[0].w;
  hand_[0] = g[0];
  hand_[1] = g[1];
  gap_ms_ = clampf(in[14], 60.0f, 1500.0f);
  finger_mm_ = clampf(in[15], 6.0f, 14.0f);
  palm_mm_ = clampf(in[16], 14.0f, 45.0f);
  az_cx_ = clampf(in[17], -1.0f, 1.0f);
  az_cy_ = clampf(in[18], -1.0f, 1.0f);
  rot_mode_ = int(clampf(std::round(in[19]), -1.0f, 1.0f));
  return true;
}

}  // namespace noti
