#include "noti/cpu_renderer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace noti {
namespace {

inline uint32_t pack(float r, float g, float b, float a) {
  auto q = [](float v) { return uint32_t(std::clamp(v, 0.0f, 1.0f) * 255.0f + 0.5f); };
  return q(r) | (q(g) << 8) | (q(b) << 16) | (q(a) << 24);
}

inline uint32_t max4(uint32_t a, uint32_t b) {
  uint32_t out = 0;
  for (int s = 0; s < 32; s += 8) out |= std::max((a >> s) & 255u, (b >> s) & 255u) << s;
  return out;
}

// Premultiplied src-over.
inline uint32_t over(uint32_t src, uint32_t dst) {
  const uint32_t sa = src >> 24;
  uint32_t out = 0;
  for (int s = 0; s < 32; s += 8) {
    const uint32_t sc = (src >> s) & 255u, dc = (dst >> s) & 255u;
    const uint32_t v = sc + (dc * (255u - sa) + 127u) / 255u;
    out |= std::min(v, 255u) << s;
  }
  return out;
}

}  // namespace

void ImageRGBA8::clear(const RectI& r0) {
  const RectI r = r0.intersected({0, 0, w, h});
  for (int32_t y = r.y0; y < r.y1; ++y) std::fill_n(&px[size_t(y) * w + r.x0], r.width(), 0u);
}

void ImageRGBA8::copy_from(const ImageRGBA8& src, const RectI& r0) {
  const RectI r = r0.intersected({0, 0, w, h});
  for (int32_t y = r.y0; y < r.y1; ++y)
    std::memcpy(&px[size_t(y) * w + r.x0], &src.px[size_t(y) * w + r.x0], sizeof(uint32_t) * r.width());
}

CpuRenderer::CpuRenderer(int32_t w, int32_t h) { surface_changed(nullptr, w, h); }

void CpuRenderer::surface_changed(void*, int32_t w, int32_t h) {
  backing_.resize(w, h);
  front_.resize(w, h);
  dry_.resize(w, h);
  scratch_.resize(w, h);
}

uint64_t CpuRenderer::draw_max(ImageRGBA8& img, const Capsule* caps, uint32_t n, const float* c, const RectI& clip0) {
  const RectI clip = clip0.intersected({0, 0, img.w, img.h});
  uint64_t touched = 0;
  for (uint32_t i = 0; i < n; ++i) {
    RectF b;
    b.add(caps[i]);
    const RectI r = pixel_bounds(b, img.w, img.h).intersected(clip);
    for (int32_t y = r.y0; y < r.y1; ++y) {
      for (int32_t x = r.x0; x < r.x1; ++x) {
        const float cov = coverage(capsule_sdf(float(x) + 0.5f, float(y) + 0.5f, caps[i]));
        ++touched;
        if (cov <= 0.0f) continue;
        uint32_t& d = img.px[size_t(y) * img.w + x];
        d = max4(d, pack(c[0] * cov, c[1] * cov, c[2] * cov, c[3] * cov));
      }
    }
  }
  return touched;
}

void CpuRenderer::render(const FrameUpdate& f) {
  // Same order as the GL renderer: everything visible first, backing last.
  if (!f.restore_rect.empty()) front_.copy_from(backing_, f.restore_rect);
  if (f.ink_count) pixels_touched_ += draw_max(front_, f.ink, f.ink_count, f.color, f.ink_rect);
  if (f.pred_count) pixels_touched_ += draw_max(front_, f.pred, f.pred_count, f.color, f.pred_rect);
  if (f.ink_count) pixels_touched_ += draw_max(backing_, f.ink, f.ink_count, f.color, f.ink_rect);
}

void CpuRenderer::commit(const StrokeStore& store, uint32_t id, const RectI& wet_rect) {
  const StrokeRecord& s = store.stroke(id);
  const RectI r = pixel_bounds(s.bounds, dry_.w, dry_.h);
  // Union-render the stroke on its own, then composite it over the page so
  // translucent ink looks identical wet and dry.
  scratch_.clear(r);
  draw_max(scratch_, store.capsules(id), s.capsule_count, s.color, r);
  for (int32_t y = r.y0; y < r.y1; ++y)
    for (int32_t x = r.x0; x < r.x1; ++x) {
      const size_t i = size_t(y) * dry_.w + x;
      dry_.px[i] = over(scratch_.px[i], dry_.px[i]);
    }
  backing_.clear(wet_rect);
  front_.clear(wet_rect);
}

void CpuRenderer::cancel(const RectI& wet_rect) {
  backing_.clear(wet_rect);
  front_.clear(wet_rect);
}

ImageRGBA8 CpuRenderer::composite(bool include_prediction) const {
  ImageRGBA8 out = dry_;
  const ImageRGBA8& wet = include_prediction ? front_ : backing_;
  for (size_t i = 0; i < out.px.size(); ++i) out.px[i] = over(wet.px[i], out.px[i]);
  return out;
}

}  // namespace noti
