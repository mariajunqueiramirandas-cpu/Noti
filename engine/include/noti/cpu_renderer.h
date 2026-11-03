// Reference software renderer. Implements the InkRenderer contract exactly
// (same SDF, same MAX-union wet blending, same restore/commit semantics as the
// GL renderer) so tests can verify pixel-level invariants on any host, e.g.
// "after pen-up the front buffer holds no trace of the predicted tail".
// Not used on device.
#pragma once

#include <cstdint>
#include <vector>

#include "noti/renderer.h"

namespace noti {

struct ImageRGBA8 {
  int32_t w = 0, h = 0;
  std::vector<uint32_t> px;  // premultiplied RGBA, little-endian R in low byte

  void resize(int32_t w_, int32_t h_) { w = w_; h = h_; px.assign(size_t(w) * h, 0u); }
  uint32_t at(int32_t x, int32_t y) const { return px[size_t(y) * w + x]; }
  void clear(const RectI& r);
  void copy_from(const ImageRGBA8& src, const RectI& r);
};

class CpuRenderer final : public InkRenderer {
 public:
  CpuRenderer(int32_t w, int32_t h);

  void surface_changed(void*, int32_t w, int32_t h) override;
  void render(const FrameUpdate& f) override;
  void commit(const StrokeStore& store, uint32_t id, const RectI& wet_rect) override;
  void cancel(const RectI& wet_rect) override;
  void redraw(const StrokeStore& store, uint32_t page) override;

  const ImageRGBA8& backing() const { return backing_; }
  const ImageRGBA8& front() const { return front_; }
  const ImageRGBA8& dry() const { return dry_; }
  // What the user sees: front (wet) composited over dry. With
  // include_prediction = false, the real wet ink (backing) is used instead.
  ImageRGBA8 composite(bool include_prediction = true) const;

  uint64_t pixels_touched() const { return pixels_touched_; }

  // Rasterise capsules into `img` with MAX blending, clipped to `clip`.
  static uint64_t draw_max(ImageRGBA8& img, const Capsule* caps, uint32_t n, const float* premul, const RectI& clip);

 private:
  void draw_into_dry(const StrokeStore& store, uint32_t id);
  ImageRGBA8 backing_, front_, dry_, scratch_;
  uint64_t pixels_touched_ = 0;
};

}  // namespace noti
