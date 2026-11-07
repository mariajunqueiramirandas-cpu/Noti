// InkEngine: the ink render loop (plan §3). Two ways to run it:
//
// INLINE (production on Android): the thread that receives pen input *is* the
// render thread. On the device that is a dedicated high-priority Looper thread
// that owns its own input window, so neither the UI thread nor any thread
// hand-off sits between a MotionEvent and its draw call:
//
//   attach_inline()                 once, on the ink thread
//   process(samples, n)             per MotionEvent: predict + draw, synchronously
//   execute(cmd)                    surface / vsync events raised on the ink thread
//   idle()                          housekeeping timer (returns next deadline)
//   post(cmd)                       from the UI thread (lock-free, applied at next process())
//
// THREADED (benchmarks, platforms without an input thread): start() spawns a
// render thread fed through the SPSC ring:
//
//   input thread ──push()──► SPSC sample ring ──► render thread
//   (JNI, wait-free:          (4096 × 40 B)         drain all pending samples
//    memcpy + 1 release                             → predictor
//    store + 1 fence;                               → capsules (+ predicted tail)
//    futex wake only if                             → ONE scissored renderer call
//    the renderer sleeps)                           → probe timestamps
//
// After the batch the render thread spins briefly (catching the next 240 Hz
// sample without a futex round-trip when the ADPF boost keeps the core hot),
// then sleeps on a futex. Nothing on this path allocates, locks or logs.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>

#include "noti/display_timing.h"
#include "noti/geometry.h"
#include "noti/latency_probe.h"
#include "noti/predictor.h"
#include "noti/renderer.h"
#include "noti/sample.h"
#include "noti/spsc_ring.h"
#include "noti/stroke_recorder.h"
#include "noti/stroke_store.h"
#include "noti/wake_event.h"

namespace noti {

struct EngineConfig {
  PredictorConfig predictor;      // predictor.horizon_ms = fixed horizon used until vsync timing arrives
  DisplayTiming display;          // per-frame scan-out-aware horizon (needs kVsync commands)
  Affine2 input_to_surface;          // view px → surface px
  int32_t surface_w = 0, surface_h = 0;
  // Threaded mode: busy-wait after a batch before sleeping. -1 = never sleep
  // (default: power is not a constraint; the render thread polls the ring
  // continuously and picks up a sample within ~100 ns of its push).
  int64_t spin_ns = -1;
  // Predictive wake (only when spin_ns >= 0): during a stroke the pen reports on a fixed clock
  // (240 Hz), so the render thread sleeps with a timeout that expires
  // `wake_lead_ns` before the next sample is due, then spins for it. The
  // sample is then picked up without a futex wake-up or CPU idle exit.
  // The producer's futex wake remains the fallback for early samples.
  bool predictive_wake = true;
  int64_t wake_lead_ns = 300'000;
  int64_t wake_slack_ns = 700'000;   // give up spinning this long after the due time
  bool probe = true;                 // record latency/prediction statistics
  // Stroke store reservation (virtual; pages become resident as ink is written).
  // 8 M capsules ≈ 9.7 h of continuous writing at 240 Hz.
  uint32_t store_max_strokes = 1u << 18;
  uint32_t store_max_capsules = 1u << 23;
};

enum class CommandType : uint32_t {
  kSetColor,           // f[0..3] = straight RGBA
  kSetWidth,           // f[0] = diameter px at full pressure
  kSetPressureGamma,   // f[0] = gamma, f[1] = floor
  kSetHorizon,         // f[0] = ms
  kSetPredictor,       // i0 = PredictorModel
  kSetTransform,       // f[0..5] = a b tx c d ty
  kSurfaceChanged,     // ptr = native window, i0 = w, i1 = h
  kSurfaceDestroyed,
  kResetProbe,
  kVsync,              // t = a vsync (scan-out start) time, CLOCK_MONOTONIC; i0 = period ns
  kSetDisplay,         // f[0] = panel ms, f[1] = gpu ms, i0 = ScanAxis, i1 = reversed
  kSetRecording,       // i0 = 1 on / 0 off (raw stroke recorder for predictor tuning)
  kSetPage,            // i0 = page; f[0], f[1] = page origin, f[2] = page width (input px)
};

struct Command {
  CommandType type;
  int32_t i0 = 0, i1 = 0;
  int64_t t = 0;
  float f[6] = {};
  void* ptr = nullptr;
};

struct EngineStats {
  std::atomic<uint64_t> samples{0};
  std::atomic<uint64_t> frames{0};
  std::atomic<uint64_t> strokes{0};
  std::atomic<uint64_t> wakeups{0};   // futex sleeps that ended in work
  std::atomic<uint64_t> spin_hits{0}; // batches caught while spinning
  std::atomic<uint64_t> predicted_hits{0};  // samples caught by predictive wake
  std::atomic<uint64_t> store_full{0};      // samples dropped because the store is full
};

class InkEngine {
 public:
  static constexpr std::size_t kSampleRing = 4096;
  static constexpr std::size_t kCommandRing = 64;
  static constexpr uint32_t kMaxBatch = 256;

  InkEngine(const EngineConfig& cfg, InkRenderer& renderer);
  ~InkEngine();
  InkEngine(const InkEngine&) = delete;
  InkEngine& operator=(const InkEngine&) = delete;

  // ---- threaded mode ----
  // Start/stop the dedicated render thread. Without start(), call pump().
  bool start();
  void stop();
  bool running() const noexcept { return thread_.joinable(); }

  // ---- inline mode: every call below on the same (ink) thread ----
  bool attach_inline();  // renderer.thread_init() on the calling thread
  void detach_inline();  // renderer.thread_shutdown()
  // Processes pending UI commands, then these samples, and draws. Returns the
  // renderer's next housekeeping deadline in ns from now (-1 = none).
  NOTI_HOT int64_t process(const InkSample* samples, uint32_t n) noexcept;
  // Executes a command raised on the render thread itself (surface, vsync):
  // bypasses the command ring, whose single producer is the UI thread.
  void execute(const Command& c) noexcept;
  // Renderer housekeeping (GPU keep-alive, deferred power changes). Returns
  // the next deadline in ns from now, or -1.
  int64_t idle() noexcept;
  // Raw-stroke recorder export (render thread, or engine stopped).
  int64_t export_recording(const char* path) const { return recorder_.export_csv(path); }

  // ---- producer side: exactly one producer thread for samples+commands ----
  // Wait-free. Returns the number of samples accepted (rest are dropped and
  // counted in probe().dropped_samples).
  NOTI_HOT uint32_t push(const InkSample* samples, uint32_t n) noexcept;
  bool post(const Command& c) noexcept;
  // Posts and blocks until the render thread has executed the command
  // (surface teardown must not return before the renderer let go of it).
  void post_sync(const Command& c) noexcept;

  // ---- synchronous mode (tests, benchmarks, or a caller-owned thread) ----
  // Processes everything pending; returns true if any work was done.
  bool pump() noexcept;

  // Document/edit APIs: ink thread only; geometry stays canonical in the store.
  struct VisiblePage { uint32_t page; float x, y, width, height; };
  void set_pages(const std::vector<VisiblePage>& pages);
  void set_tool(int tool) noexcept;
  void undo();
  void redo();
  bool edit_page(int operation, uint32_t page);
  void finish_input();
  bool document_busy() const noexcept { return active_ || editing_; }
  std::vector<uint8_t> snapshot();
  bool restore(const uint8_t* bytes, size_t size);
  uint64_t revision() const noexcept { return revision_; }
  bool can_undo() const noexcept { return !undo_.empty(); }
  bool can_redo() const noexcept { return !redo_.empty(); }
  RectF selection_bounds() const noexcept;
  size_t selection_count() const noexcept { return selected_.size(); }
  void clear_selection() noexcept { selected_.clear(); }
  // Tool 4 (transcribe) is a lasso whose loop asks the app to read the ink it selects.
  // Counts the loops finished, so the app notices each one.
  uint64_t transcribe_serial() const noexcept { return transcribe_serial_; }
  // Tool 5 (type) is a loop, or a tap, that marks a place to type on: counted like
  // transcribe loops, and kept as [page, then x y per point] in page units.
  uint64_t type_serial() const noexcept { return type_serial_; }
  const std::vector<float>& type_loop() const noexcept { return type_loop_; }
  // Every visible stroke of `page`, in the selection_ink format.
  std::vector<float> page_ink(uint32_t page) const;
  // The selected strokes, canonical: [count, then per stroke: id, page, r, g, b, a
  // (premultiplied), n, then n capsules of x0 y0 r0 x1 y1 r1 in page units].
  std::vector<float> selection_ink() const;
  // One undoable edit: hides `ids` (visible strokes of `page`) and adds strokes on
  // `page` cut from `capsules` (page units) by `sizes`. All or nothing.
  bool replace_strokes(const std::vector<uint32_t>& ids, uint32_t page, const float color[4],
                       const std::vector<Capsule>& capsules, const std::vector<uint32_t>& sizes);

  // ---- observation (any thread) ----
  const LatencyProbe& probe() const noexcept { return probe_; }
  LatencyProbe& probe() noexcept { return probe_; }
  const EngineStats& stats() const noexcept { return stats_; }
  // Render thread / quiescent only.
  const StrokeStore& store() const noexcept { return store_; }
  uint32_t page() const noexcept { return page_; }
  const PageMap& page_map() const noexcept { return page_map_; }
  const Prediction& last_prediction() const noexcept { return pred_; }
  const StrokePredictor& predictor() const noexcept { return predictor_; }

 private:
  void thread_main();
  void handle_command(const Command& c) noexcept;
  void process_samples(const InkSample* s, uint32_t n, int64_t t_start) noexcept;
  void begin_stroke(const InkSample& s) noexcept;
  void extend_stroke(const InkSample& s) noexcept;
  void finish_stroke(int64_t t_end) noexcept;
  void cancel_stroke() noexcept;
  void set_page(uint32_t page, float x, float y, float width) noexcept;
  void flush_frame(bool with_prediction) noexcept;
  NOTI_INLINE void note_sample(const InkSample& s) noexcept;
  void track_prediction_error(int64_t t_prev, float x0, float y0, int64_t t_now, float x1, float y1) noexcept;
  bool has_pending() noexcept;

  bool edit_sample(const InkSample& s);
  bool pick_page(float x, float y) noexcept;
  void redraw_document();
  void end_edit(bool cancel);
  struct EditAction;
  void apply_edit(bool forward, const EditAction& action);
  struct EditAction {
    int kind = 0; // 0 add, 1 erase, 2 move, 3 replace (erase ids, add `added`)
    std::vector<uint32_t> ids, added;
    float dx = 0, dy = 0;
  };
  void remember_edit(EditAction action);
  std::vector<VisiblePage> visible_pages_;
  std::vector<EditAction> undo_, redo_;
  std::vector<uint32_t> selected_;
  struct Point { float x, y; };
  std::vector<Point> polygon_;
  EditAction edit_;
  int tool_ = 0, gesture_tool_ = 0;
  bool editing_ = false, moving_selection_ = false, document_mode_ = false, layout_dirty_ = false;
  float edit_x_ = 0, edit_y_ = 0;
  RectF edit_bounds_;
  uint64_t revision_ = 0, transcribe_serial_ = 0, type_serial_ = 0;
  std::vector<float> type_loop_;

  EngineConfig cfg_;
  InkRenderer& renderer_;

  // Cross-thread queues.
  SpscRing<InkSample, kSampleRing> samples_;
  SpscRing<Command, kCommandRing> commands_;
  WakeEvent wake_;
  std::atomic<bool> stop_{false};
  std::atomic<uint32_t> sync_done_{0};
  uint32_t posted_ = 0;  // commands posted (producer-owned); sync_done_ counts executed
  std::thread thread_;

  // ---- render-thread state ----
  StrokePredictor predictor_;
  Brush brush_;
  float scale_ = 1.0f;
  float stroke_color_[4] = {};  // premultiplied, latched at pen-down
  int32_t surface_w_, surface_h_;

  bool active_ = false;
  uint32_t page_ = 0;
  PageMap page_map_;  // current page's placement on the surface
  uint32_t frame_first_ = 0;  // first capsule of the open stroke not yet handed to the renderer
  RectF stroke_bounds_;
  float last_x_ = 0, last_y_ = 0, last_r_ = 0;
  int64_t last_t_ = 0;
  int64_t stroke_t0_ = 0;

  Prediction pred_;
  Capsule pred_caps_[Prediction::kMaxTailPoints];
  RectI prev_pred_rect_;

  // Samples rendered in the current frame (for the probe).
  int64_t frame_t_in_[kMaxBatch];
  int64_t frame_t_recv_[kMaxBatch];
  uint32_t frame_samples_ = 0;
  int64_t batch_t_start_ = 0;
  int64_t sample_period_ns_ = 0;  // EMA of the pen report interval (hardware clock)
  int64_t vsync_ns_ = 0, vsync_period_ns_ = 0;  // display scan-out timing (kVsync)
  // Lower envelope of (render-thread pickup time − hardware time): the
  // fastest the platform has delivered a sample recently. Next sample's
  // earliest arrival ≈ last hardware time + period + this.
  int64_t dispatch_floor_ns_ = INT64_MAX;

  // Predictions awaiting their ground truth.
  struct PendingPrediction { int64_t target_ns; float x, y; };
  static constexpr uint32_t kPending = 16;
  PendingPrediction pending_[kPending];
  uint32_t pending_head_ = 0, pending_tail_ = 0;

  StrokeStore store_;
  StrokeRecorder recorder_;
  LatencyProbe probe_;
  EngineStats stats_;
  InkSample scratch_[kMaxBatch];
};

}  // namespace noti
