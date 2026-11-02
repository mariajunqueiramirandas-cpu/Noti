// GLSL ES 3.00 capsule shaders. The SDF is a line-by-line port of
// noti::capsule_sdf (include/noti/geometry.h); the CPU reference renderer and
// the tests pin down its behaviour.
#pragma once

namespace noti::android {

// One instance per capsule, 4 vertices (triangle strip) per instance, no vertex
// buffer for the corners: gl_VertexID picks the corner of the capsule's
// bounding box (padded by the 1 px anti-aliasing ramp).
inline constexpr const char* kCapsuleVS = R"(#version 300 es
layout(location = 0) in vec3 a_c0;   // x0 y0 r0 (surface px)
layout(location = 1) in vec3 a_c1;   // x1 y1 r1
uniform vec2 u_inv_size;             // 1 / surface size
uniform float u_flip;                // 0: GL row 0 is the top row (AHardwareBuffer FBO); 1: bottom
flat out vec3 v_c0;
flat out vec3 v_c1;
out vec2 v_px;
void main() {
  vec2 lo = min(a_c0.xy - a_c0.z, a_c1.xy - a_c1.z) - 1.0;
  vec2 hi = max(a_c0.xy + a_c0.z, a_c1.xy + a_c1.z) + 1.0;
  vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vec2 px = mix(lo, hi, corner);
  v_c0 = a_c0;
  v_c1 = a_c1;
  v_px = px;
  vec2 ndc = px * u_inv_size * 2.0 - 1.0;
  ndc.y = mix(ndc.y, -ndc.y, u_flip);
  gl_Position = vec4(ndc, 0.0, 1.0);
}
)";

inline constexpr const char* kCapsuleFS = R"(#version 300 es
precision highp float;
flat in vec3 v_c0;
flat in vec3 v_c1;
in vec2 v_px;
uniform vec4 u_color;  // premultiplied
out vec4 o_color;

float capsule_sdf(vec2 p, vec3 c0, vec3 c1) {
  vec2 b = c1.xy - c0.xy;
  float h = dot(b, b);
  vec2 a = p - c0.xy;
  float dr = c0.z - c1.z;
  if (h <= dr * dr) {
    return min(length(a) - c0.z, length(p - c1.xy) - c1.z);
  }
  float inv_h = 1.0 / h;
  vec2 q = vec2(abs(a.x * b.y - a.y * b.x), dot(a, b)) * inv_h;
  vec2 c = vec2(sqrt(h - dr * dr), dr);
  float k = c.x * q.y - c.y * q.x;
  float m = dot(c, q);
  float n = dot(q, q);
  if (k < 0.0) return sqrt(h * n) - c0.z;
  if (k > c.x) return sqrt(h * (n + 1.0 - 2.0 * q.y)) - c1.z;
  return m - c0.z;
}

void main() {
  // Pixel centre in top-left-origin surface pixels (v_px interpolates exactly that).
  float cov = clamp(0.5 - capsule_sdf(v_px, v_c0, v_c1), 0.0, 1.0);
  if (cov <= 0.0) discard;
  o_color = u_color * cov;
}
)";

// Full-rect textured quad: composites a premultiplied texture (src-over) into
// the dry layer. Rect given in surface px; sampled 1:1.
inline constexpr const char* kCompositeVS = R"(#version 300 es
uniform vec4 u_rect;       // x0 y0 x1 y1 (px)
uniform vec2 u_inv_size;
uniform float u_flip;
out vec2 v_uv;
void main() {
  vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vec2 px = mix(u_rect.xy, u_rect.zw, corner);
  v_uv = px * u_inv_size;
  v_uv.y = mix(v_uv.y, 1.0 - v_uv.y, u_flip);  // texel rows follow GL window rows
  vec2 ndc = px * u_inv_size * 2.0 - 1.0;
  ndc.y = mix(ndc.y, -ndc.y, u_flip);
  gl_Position = vec4(ndc, 0.0, 1.0);
}
)";

inline constexpr const char* kCompositeFS = R"(#version 300 es
precision mediump float;
uniform sampler2D u_tex;
in vec2 v_uv;
out vec4 o_color;
void main() { o_color = texture(u_tex, v_uv); }
)";

}  // namespace noti::android
