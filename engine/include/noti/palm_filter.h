// Palm rejection: decides, for every finger contact on the canvas, whether it
// is a deliberate touch or part of the writing hand, and turns the raw contact
// stream into two clean outputs:
//
//   * gesture events  – only deliberate touches, with the samples that arrived
//                       while the decision was pending replayed first, so a pan
//                       starts exactly where the finger landed;
//   * finger ink      – InkSamples for the ink engine (when drawing with a
//                       finger is on), drawn speculatively from the first
//                       sample and rolled back (Action::kCancel) if the contact
//                       turns out to be a palm.
//
// The pen is never filtered: it is ground truth, and its state is the
// strongest evidence about everything else.
//
// How a contact is decided
// ------------------------
// Every contact carries a log-odds score, log P(palm)/P(touch), that is the sum
// of independent evidence terms (log-likelihood ratios). It is re-evaluated on
// every sample and on a timer, and the contact is decided as soon as the score
// leaves the undecided band (Wald's sequential probability ratio test: the
// shortest expected wait for given error rates). Terms:
//
//   L0 system      TOOL_TYPE_PALM or a pointer the platform cancelled → palm, final
//   L1 pen         pen touching / hovering / lifted a moment ago (adaptive grace
//                  window learned from your inter-stroke gaps); the pen going
//                  down while the contact is on the glass
//   L2 hand model  where your palm rests relative to the nib: a two-component
//                  (right/left hand) Gaussian model of the palm offset, learned
//                  online, rotated with the pen's azimuth when the pen reports
//                  one. Gives a palm zone before the pen is in hover range, and
//                  lets the other hand's fingers through on the far side
//   L3 contact     peak contact size against a learned finger/palm boundary,
//                  elongation, growth over the first ~25 ms (palms roll on,
//                  fingertips don't), purposeful vs resting motion, clean taps,
//                  the bezel edge
//   L4 clusters    contacts that land together in a hand-sized area are one
//                  hand: a decided palm makes its neighbours (the pinky beside
//                  the heel of the hand) palms too; two small contacts landing
//                  together a finger-spread apart look like a two-finger gesture
//   L5 commitment  gestures start only once a contact is a confirmed touch
//                  (then replay); finger ink starts at once and is cancelled if
//                  the verdict flips, including during a short linger after lift
//   L6 learning    finger and palm sizes, the hand model, handedness and the
//                  grace window adapt to the user from confident decisions only,
//                  inside fixed bounds; export/import to persist them
//
// Threading: single-threaded (the ink thread). Nothing here allocates after
// construction except the optional recorder, which allocates when enabled.
#pragma once

#include <cstdint>
#include <cstdio>
#include <memory>
#include <type_traits>

#include "noti/sample.h"

namespace noti {

// MotionEvent.TOOL_TYPE_* values.
enum class ToolType : uint8_t { kUnknown = 0, kFinger = 1, kStylus = 2, kMouse = 3, kEraser = 4, kPalm = 5 };

enum class TouchAction : uint8_t { kDown = 0, kMove = 1, kUp = 2, kCancel = 3 };

enum TouchFlags : uint8_t {
  kTouchNone = 0,
  kTouchCanceled = 1 << 0,    // pointer went up with FLAG_CANCELED (platform palm rejection)
  kTouchHistorical = 1 << 1,  // came from MotionEvent.getHistorical*
};

// One finger/palm contact sample, 40 bytes: part of the JNI contract.
struct TouchSample {
  int64_t t_ns;       // hardware event time, CLOCK_MONOTONIC
  float x, y;         // view px
  float major, minor; // touch ellipse axes in view px (0 = not reported)
  float orientation;  // radians
  float pressure;     // as reported (unreliable for fingers; recorded, not used)
  uint32_t id;        // stable for the life of the contact (device and pointer id)
  TouchAction action;
  ToolType tool;
  uint8_t flags;      // TouchFlags
  uint8_t reserved;
};
static_assert(sizeof(TouchSample) == 40, "TouchSample layout is part of the JNI contract");
static_assert(std::is_trivially_copyable_v<TouchSample>);

enum class GestureAction : uint8_t { kDown = 0, kMove = 1, kUp = 2, kCancel = 3 };

// One event of the filtered (deliberate touches only) stream, 24 bytes.
struct GestureEvent {
  int64_t t_ns;
  float x, y;
  uint32_t id;
  GestureAction action;
  uint8_t reserved[3];
};
static_assert(sizeof(GestureEvent) == 24, "GestureEvent layout is part of the JNI contract");

enum class Verdict : uint8_t { kUndecided = 0, kTouch = 1, kPalm = 2 };

// What happened to one contact, recorded when it ends (for tests, palm_bench
// and the recorder).
struct ContactReport {
  uint32_t serial;          // unique per contact, in order of touch-down
  uint32_t id;
  Verdict verdict;          // final
  bool system_palm;         // decided by L0
  bool gesture;             // a gesture Down was emitted for it
  bool gesture_cancelled;   // ... and later cancelled
  bool ink_started;         // tentative finger ink was drawn
  bool ink_committed;       // finger ink was kept
  int64_t t_down_ns;
  int64_t t_decided_ns;     // first time it left the undecided band (or at lift)
  int64_t t_up_ns;
  float peak_score;
  float final_score;
};

struct PalmConfig {
  float px_per_mm = 13.5f;    // Xiaomi Pad 7: 3200×2136 on 11.2" ≈ 343 ppi
  float view_w = 0, view_h = 0;
  bool finger_ink = false;    // single deliberate finger draws

  // SPRT bounds on log-odds (palm vs touch).
  float decide_palm = 3.0f;
  float decide_touch = -2.0f;
  float revoke_margin = 1.0f;     // a confirmed touch is revoked only at decide_palm + this
  float ink_start_max = 0.0f;     // tentative ink starts only below this score

  int64_t ink_start_window_ns = 80'000'000;   // tentative ink must start this soon after touch-down
  int64_t ink_linger_ns = 80'000'000;         // finger ink stays revocable this long after lift
  int64_t two_finger_window_ns = 150'000'000; // second finger this soon → gesture, not ink
  int64_t rescore_period_ns = 40'000'000;     // re-score still contacts (resting evidence grows)
};

class PalmFilter {
 public:
  static constexpr uint32_t kMaxContacts = 16;
  static constexpr uint32_t kReplay = 48;          // samples kept per undecided contact
  static constexpr uint32_t kMaxGestures = 1024;   // per call
  static constexpr uint32_t kMaxInk = 512;         // per call
  static constexpr uint32_t kMaxReports = 64;      // until drained
  static constexpr uint32_t kModelFloats = 20;
  static constexpr float kModelVersion = 1.0f;

  explicit PalmFilter(const PalmConfig& cfg = {});
  ~PalmFilter();
  PalmFilter(const PalmFilter&) = delete;
  PalmFilter& operator=(const PalmFilter&) = delete;

  const PalmConfig& config() const noexcept { return cfg_; }
  void set_finger_ink(bool on) noexcept;
  void set_view(float w, float h, float px_per_mm) noexcept;

  // ---- inputs (all on one thread, in event order) ----
  // Pen samples, before the ink engine sees them. May emit a finger-ink cancel
  // (which must reach the engine before these pen samples) and gesture events.
  void observe_pen(const InkSample* s, uint32_t n) noexcept;
  // Finger / palm contacts.
  void process(const TouchSample* s, uint32_t n) noexcept;
  // Timers (resting evidence, finger-ink linger). Returns ns until the next
  // deadline, or -1 if none.
  int64_t tick(int64_t now_ns) noexcept;
  // Next deadline in ns from `now_ns`, -1 if none (no side effects).
  int64_t next_deadline(int64_t now_ns) const noexcept;

  // ---- outputs: valid until clear_outputs() ----
  const GestureEvent* gestures() const noexcept { return gestures_; }
  uint32_t gesture_count() const noexcept { return n_gestures_; }
  const InkSample* ink() const noexcept { return ink_; }
  uint32_t ink_count() const noexcept { return n_ink_; }
  void clear_outputs() noexcept { n_gestures_ = n_ink_ = 0; }

  // Reports of ended contacts (oldest first); drained by the caller.
  uint32_t drain_reports(ContactReport* out, uint32_t max) noexcept;

  // ---- learned model (L6) ----
  void export_model(float out[kModelFloats]) const noexcept;
  bool import_model(const float in[kModelFloats]) noexcept;  // false if the data is unusable
  // 1 = confidently right-handed, 0 = left-handed, 0.5 = unknown.
  float right_handedness() const noexcept { return hand_[0].w; }
  float grace_ms() const noexcept;
  float size_boundary_mm() const noexcept;

  // ---- introspection (tests, bench) ----
  Verdict verdict(uint32_t id) const noexcept;
  float score(uint32_t id) const noexcept;  // NaN if no such live contact
  uint32_t live_contacts() const noexcept;
  bool ink_active() const noexcept { return ink_slot_ >= 0; }

  // ---- recorder: raw pen + touch streams as CSV for palm_bench ----
  // label: written into every touch row ("palm", "touch" or "?"); allocates.
  void set_recording(bool on, const char* label = "?") noexcept;
  bool recording() const noexcept { return rec_ != nullptr; }
  int64_t export_recording(const char* path) const;  // rows written, -1 on error

 private:
  enum class PenState : uint8_t { kAway, kHover, kTouch };
  enum class Ink : uint8_t { kNone, kTentative, kLinger, kCommitted, kRevoked };

  struct Pt { int64_t t; float x, y; };

  struct Contact {
    bool used = false;
    uint32_t id = 0, serial = 0;
    int64_t t_down = 0, t_last = 0, t_up = 0, t_decided = 0, t_pen_down_during = 0;
    float x0 = 0, y0 = 0, x = 0, y = 0;
    float path_mm = 0, max_disp_mm = 0;
    float major0_mm = 0, peak_major_mm = 0, minor_at_peak_mm = 0;
    bool size_seen = false;
    float score = 0, peak_score = -1e9f;
    float hand_term = 0;       // L2 contribution at last scoring (kept out of learning)
    int64_t t_eval = 0;        // last time the score was evaluated
    Verdict verdict = Verdict::kUndecided;
    bool final_palm = false, system_palm = false, up = false;
    bool gesture = false, gesture_cancelled = false;
    bool learned = false;
    Ink ink = Ink::kNone;
    float pen_prior = 0;       // L1 at touch-down
    // Nib position at touch-down while the pen was tracked (hovering or touching):
    // the writing hand's palm moves rigidly with the nib, anything else doesn't.
    float nib_x0 = 0, nib_y0 = 0;
    bool nib_tracked = false;
    // Replay buffer: first sample + ring of the most recent ones.
    Pt first{};
    Pt ring[kReplay];
    uint32_t ring_n = 0, ring_head = 0;
  };

  struct Gauss {  // 2-D Gaussian, running moments, palm offset from the nib in mm
    float w = 0.5f;            // mixture weight
    float mx = 0, my = 0;
    float sxx = 0, sxy = 0, syy = 0;
    float n = 0;
  };

  // input handling
  void on_down(const TouchSample& s) noexcept;
  void on_move(Contact& c, const TouchSample& s) noexcept;
  void on_up(Contact& c, const TouchSample& s, bool cancelled) noexcept;
  void observe_one_pen(const InkSample& s) noexcept;
  Contact* find(uint32_t id) noexcept;
  const Contact* find(uint32_t id) const noexcept;
  int slot_of(const Contact& c) const noexcept { return int(&c - contacts_); }

  // evidence
  float pen_prior(int64_t t, float x, float y) const noexcept;
  float hand_llr(int64_t t, float x, float y) const noexcept;
  float evaluate(Contact& c, int64_t now) noexcept;
  void update(Contact& c, int64_t now) noexcept;   // re-score + act on the verdict
  void rescore_all(int64_t now) noexcept;

  // actions
  void decide_touch(Contact& c, int64_t now) noexcept;
  void decide_palm(Contact& c, int64_t now, bool final) noexcept;
  void emit_gesture(GestureAction a, const Contact& c, int64_t t, float x, float y) noexcept;
  void replay_gesture(Contact& c) noexcept;
  void maybe_start_ink(Contact& c, int64_t now) noexcept;
  void emit_ink(Action a, int64_t t, float x, float y) noexcept;
  void revoke_ink(int64_t t) noexcept;
  void commit_ink(int64_t t) noexcept;
  void finish(Contact& c) noexcept;  // contact ended: report, learn, free
  void release_suppressed_gestures(int64_t now) noexcept;
  void on_pen_active(int64_t t) noexcept;
  bool pen_in_context(int64_t t) const noexcept;

  // learning
  void learn_from(const Contact& c) noexcept;
  void learn_hand(float dx_mm, float dy_mm) noexcept;
  void rotate_to_hand_frame(float* dx, float* dy) const noexcept;

  float mm(float px) const noexcept { return px / cfg_.px_per_mm; }
  void record_touch(const TouchSample& s) noexcept;
  void record_pen(const InkSample& s) noexcept;

  PalmConfig cfg_;
  Contact contacts_[kMaxContacts];
  uint32_t next_serial_ = 0;
  int64_t last_time_ = 0;

  // Pen state (L1/L2).
  PenState pen_ = PenState::kAway;
  bool pen_ever_ = false;
  int64_t t_pen_seen_ = 0;       // last pen event of any kind
  int64_t t_pen_left_ = 0;       // last lift or hover exit
  int64_t t_pen_up_ = 0;         // last lift (for the gap model)
  float pen_x_ = 0, pen_y_ = 0;
  float pen_tilt_ = 0, pen_az_ = 0;

  // Learned model (L6).
  Gauss hand_[2];                // [0] right hand (palm right of nib), [1] left
  float gap_ms_ = 250.0f;        // EMA of pen-up → pen-down gaps (inter-stroke)
  float finger_mm_ = 9.5f;       // EMA of confirmed fingertip peak size
  float palm_mm_ = 22.0f;        // EMA of confirmed palm peak size
  // Pen azimuth while writing: circular mean (unit-vector EMA) and spread.
  // When the pen reports an azimuth, the hand model lives in a frame that
  // turns with it: the hand swings around the nib as the pen's lean changes.
  float az_cx_ = 0.0f, az_cy_ = 0.0f;
  float az_spread_ = 0.0f;       // EMA of |azimuth - mean|: stays 0 when the pen reports none
  // Which way the platform's azimuth turns relative to the hand (-1, 0 = off, +1),
  // chosen by whichever frame has fit the learned palms best.
  float rot_err_[3] = {0, 0, 0};
  int rot_mode_ = 0;
  // Recent palms that already ended (L4 inheritance for late neighbours).
  struct RecentPalm { int64_t t; float x, y; };
  RecentPalm recent_palms_[8];
  uint32_t recent_palm_next_ = 0;

  // Finger ink.
  int ink_slot_ = -1;
  int64_t t_linger_end_ = 0;

  // Outputs.
  GestureEvent gestures_[kMaxGestures];
  uint32_t n_gestures_ = 0;
  InkSample ink_[kMaxInk];
  uint32_t n_ink_ = 0;
  ContactReport reports_[kMaxReports];
  uint32_t reports_head_ = 0, reports_n_ = 0;

  // Recorder.
  struct Recorder;
  std::unique_ptr<Recorder> rec_;
};

}  // namespace noti
