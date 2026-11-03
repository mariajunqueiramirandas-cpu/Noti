// Stroke geometry: every stroke is a chain of tapered capsules ("uneven
// capsules"), one per pair of consecutive samples. A capsule is exactly the
// GPU instance record: the fragment shader evaluates its signed distance field
// analytically, so ink is perfectly anti-aliased at any zoom with zero CPU
// tessellation.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "noti/config.h"

namespace noti {

// 24 bytes. Layout is shared with the GLSL instance attributes.
struct Capsule {
  float x0, y0, r0;
  float x1, y1, r1;
};
static_assert(sizeof(Capsule) == 24);

// Axis-aligned float rect. Empty when x0 > x1.
struct RectF {
  float x0 = 1e30f, y0 = 1e30f, x1 = -1e30f, y1 = -1e30f;

  NOTI_INLINE bool empty() const noexcept { return x0 > x1 || y0 > y1; }
  NOTI_INLINE void add(const Capsule& c) noexcept {
    x0 = std::min(x0, std::min(c.x0 - c.r0, c.x1 - c.r1));
    y0 = std::min(y0, std::min(c.y0 - c.r0, c.y1 - c.r1));
    x1 = std::max(x1, std::max(c.x0 + c.r0, c.x1 + c.r1));
    y1 = std::max(y1, std::max(c.y0 + c.r0, c.y1 + c.r1));
  }
  NOTI_INLINE void add(const RectF& r) noexcept {
    x0 = std::min(x0, r.x0);
    y0 = std::min(y0, r.y0);
    x1 = std::max(x1, r.x1);
    y1 = std::max(y1, r.y1);
  }
};

// Integer pixel rect, half-open [x0, x1) × [y0, y1). Empty when x0 >= x1.
struct RectI {
  int32_t x0 = 0, y0 = 0, x1 = 0, y1 = 0;

  NOTI_INLINE bool empty() const noexcept { return x0 >= x1 || y0 >= y1; }
  NOTI_INLINE int32_t width() const noexcept { return x1 - x0; }
  NOTI_INLINE int32_t height() const noexcept { return y1 - y0; }
  NOTI_INLINE int64_t area() const noexcept {
    return empty() ? 0 : int64_t(width()) * int64_t(height());
  }
  NOTI_INLINE RectI united(const RectI& o) const noexcept {
    if (empty()) return o;
    if (o.empty()) return *this;
    return {std::min(x0, o.x0), std::min(y0, o.y0), std::max(x1, o.x1), std::max(y1, o.y1)};
  }
  NOTI_INLINE RectI intersected(const RectI& o) const noexcept {
    RectI r{std::max(x0, o.x0), std::max(y0, o.y0), std::min(x1, o.x1), std::min(y1, o.y1)};
    return r.empty() ? RectI{} : r;
  }
  bool operator==(const RectI&) const = default;
};

// Covers every pixel whose centre can receive coverage from `r`, i.e. the float
// bounds padded by the 1 px anti-aliasing ramp, then clipped to the surface.
NOTI_INLINE RectI pixel_bounds(const RectF& r, int32_t surface_w, int32_t surface_h) noexcept {
  if (r.empty()) return {};
  RectI out{static_cast<int32_t>(std::floor(r.x0 - 1.0f)), static_cast<int32_t>(std::floor(r.y0 - 1.0f)),
            static_cast<int32_t>(std::ceil(r.x1 + 1.0f)) + 1, static_cast<int32_t>(std::ceil(r.y1 + 1.0f)) + 1};
  return out.intersected({0, 0, surface_w, surface_h});
}

// Exact signed distance to a tapered capsule (Inigo Quilez, "uneven capsule"),
// with guards for the degenerate cases (zero length, one disc containing the
// other). The GLSL version in src/android/shaders.h is a line-by-line copy.
NOTI_INLINE float capsule_sdf(float px, float py, const Capsule& c) noexcept {
  const float bx = c.x1 - c.x0, by = c.y1 - c.y0;
  const float h = bx * bx + by * by;
  const float ax = px - c.x0, ay = py - c.y0;
  const float dr = c.r0 - c.r1;
  if (h <= dr * dr) {  // degenerate: one disc contains the other (or a dot)
    const float d0 = std::sqrt(ax * ax + ay * ay) - c.r0;
    const float qx = px - c.x1, qy = py - c.y1;
    const float d1 = std::sqrt(qx * qx + qy * qy) - c.r1;
    return std::min(d0, d1);
  }
  const float inv_h = 1.0f / h;
  // q = (|distance across|, distance along) in units normalised by |b|^2.
  const float qx = std::fabs(ax * by - ay * bx) * inv_h;
  const float qy = (ax * bx + ay * by) * inv_h;
  const float cx = std::sqrt(h - dr * dr), cy = dr;
  const float k = cx * qy - cy * qx;
  const float m = cx * qx + cy * qy;
  const float n = qx * qx + qy * qy;
  if (k < 0.0f) return std::sqrt(h * n) - c.r0;
  if (k > cx) return std::sqrt(h * (n + 1.0f - 2.0f * qy)) - c.r1;
  return m - c.r0;
}

// 1 px box-filter approximation of pixel coverage from a signed distance.
NOTI_INLINE float coverage(float sd) noexcept { return std::clamp(0.5f - sd, 0.0f, 1.0f); }

// Pressure → width response. Piecewise-linear 64-segment LUT so the hot path
// is a multiply, a truncation and one lerp, whatever the curve shape.
class PressureCurve {
 public:
  static constexpr int kSegments = 64;

  // Default: gamma 0.7 (light strokes don't drop out) with a floor so a
  // barely-touching nib still leaves a hairline.
  PressureCurve() noexcept { set_gamma(0.7f, 0.12f); }

  void set_gamma(float gamma, float floor) noexcept {
    for (int i = 0; i <= kSegments; ++i) {
      const float p = float(i) / kSegments;
      lut_[i] = floor + (1.0f - floor) * std::pow(p, gamma);
    }
  }
  // Arbitrary measured curve (e.g. from the calibration UI): kSegments+1 points.
  void set_table(const float* values) noexcept {
    for (int i = 0; i <= kSegments; ++i) lut_[i] = values[i];
  }

  NOTI_INLINE float operator()(float p) const noexcept {
    p = std::clamp(p, 0.0f, 1.0f) * kSegments;
    int i = static_cast<int>(p);
    if (i >= kSegments) i = kSegments - 1;
    const float f = p - float(i);
    return lut_[i] + (lut_[i + 1] - lut_[i]) * f;
  }

 private:
  float lut_[kSegments + 1];
};

struct Brush {
  float color[4] = {0.f, 0.f, 0.f, 1.f};  // straight (non-premultiplied) RGBA, 0..1
  float width_px = 4.0f;                  // diameter at full pressure
  PressureCurve curve;

  NOTI_INLINE float radius(float pressure) const noexcept { return 0.5f * width_px * curve(pressure); }
};

// view → surface (buffer) pixel transform: rotation/pre-rotation, density
// scaling and offsets in one 2×3 affine.
struct Affine2 {
  float a = 1, b = 0, tx = 0;
  float c = 0, d = 1, ty = 0;

  NOTI_INLINE void apply(float x, float y, float* ox, float* oy) const noexcept {
    *ox = a * x + b * y + tx;
    *oy = c * x + d * y + ty;
  }
  // Radius scale (assumes a similarity transform).
  NOTI_INLINE float scale() const noexcept { return std::sqrt(std::fabs(a * d - b * c)); }
};

// Where a page sits on the surface: page units → surface px is
// p_surface = origin + p_page * scale (uniform scale, no rotation). A page is
// kPageUnits wide in page units, whatever its size on screen, so ink stored in
// page units survives zoom, pan, rotation and page switches exactly.
struct PageMap {
  static constexpr float kPageUnits = 1000.0f;
  float ox = 0, oy = 0, s = 1;  // identity: page units == surface px

  NOTI_INLINE Capsule to_page(const Capsule& c) const noexcept {
    const float k = 1.0f / s;
    return {(c.x0 - ox) * k, (c.y0 - oy) * k, c.r0 * k, (c.x1 - ox) * k, (c.y1 - oy) * k, c.r1 * k};
  }
  NOTI_INLINE Capsule to_surface(const Capsule& c) const noexcept {
    return {ox + c.x0 * s, oy + c.y0 * s, c.r0 * s, ox + c.x1 * s, oy + c.y1 * s, c.r1 * s};
  }
  bool operator==(const PageMap&) const = default;
};

}  // namespace noti
