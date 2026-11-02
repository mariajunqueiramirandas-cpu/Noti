// Renderer interface: the engine decides *what* changed, a renderer decides
// how to put it on screen. Called only from the render thread.
//
// Contract (front-buffer model, plan §3 / §4.3):
//   * The renderer keeps a private "backing" image holding the real wet ink
//     (no prediction) and the on-screen "front" image = backing + prediction.
//   * render(), visible work first:
//                1. front[restore_rect] = backing[restore_rect]   (erase old tail)
//                2. draw `ink` into front                         (scissor: ink_rect)
//                3. draw `pred` into front                        (scissor: pred_rect)
//                   → submit: this is everything the user sees
//                4. draw `ink` into backing                       (off the critical path)
//     Step 1 uses the backing *before* this frame's ink, which is fine because
//     step 2 redraws that ink on top.
//     Ink coverage is combined with MAX blending (per-channel max of
//     premultiplied colour), i.e. the stroke is the union of its capsules: no
//     double-blended joints and idempotent redraws.
//   * commit(): move the finished stroke to the dry layer and clear it from
//     the wet layer without a visible gap or flicker.
//   * cancel(): erase a stroke the system cancelled.
#pragma once

#include <cstdint>

#include "noti/geometry.h"
#include "noti/stroke_store.h"

namespace noti {

struct LatencyProbe;

struct FrameUpdate {
  const Capsule* ink = nullptr;  // new real segments since the last frame
  uint32_t ink_count = 0;
  const Capsule* pred = nullptr;  // full predicted tail (replaces the previous one)
  uint32_t pred_count = 0;
  RectI ink_rect;      // pixel bounds of `ink`
  RectI pred_rect;     // pixel bounds of `pred`
  RectI restore_rect;  // pixel bounds of the previous tail, to erase
  const float* color = nullptr;  // premultiplied RGBA of the active stroke
};

class InkRenderer {
 public:
  virtual ~InkRenderer() = default;

  // Render-thread lifecycle (e.g. make the EGL context current, ADPF session).
  virtual bool thread_init() { return true; }
  virtual void thread_shutdown() {}

  // Surface lifecycle, delivered on the render thread in input order.
  virtual void surface_changed(void* /*native_window*/, int32_t /*w*/, int32_t /*h*/) {}
  virtual void surface_destroyed() {}

  // Pen hovering: pre-warm (clocks, refresh rate, caches) / relax.
  virtual void hover(bool /*near*/) {}

  virtual void render(const FrameUpdate& f) = 0;
  virtual void commit(const StrokeStore& store, uint32_t stroke_id, const RectI& wet_rect) = 0;
  virtual void cancel(const RectI& wet_rect) = 0;

  // Work for the batch is done (e.g. report ADPF actual duration).
  virtual void batch_done(int64_t /*cpu_ns*/) {}
  // The render thread is about to sleep. Return the longest it may sleep
  // before calling idle() again (ns), or -1 to sleep until new input.
  virtual int64_t idle() { return -1; }

  // Renderers that measure GPU completion write into this.
  void set_probe(LatencyProbe* p) { probe_ = p; }

 protected:
  LatencyProbe* probe_ = nullptr;
};

// Does nothing; measures the engine alone.
class NullRenderer final : public InkRenderer {
 public:
  void render(const FrameUpdate& f) override { frames_ += 1; capsules_ += f.ink_count + f.pred_count; }
  void commit(const StrokeStore&, uint32_t, const RectI&) override { commits_ += 1; }
  void cancel(const RectI&) override { cancels_ += 1; }
  uint64_t frames_ = 0, capsules_ = 0, commits_ = 0, cancels_ = 0;
};

}  // namespace noti
