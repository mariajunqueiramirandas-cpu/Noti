#include "gl_renderer.h"

#include <android/log.h>
#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>

#include "noti/clock.h"
#include "noti/latency_probe.h"
#include "shaders.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "noti-ink", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "noti-ink", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "noti-ink", __VA_ARGS__)

#ifndef EGL_CONTEXT_PRIORITY_LEVEL_IMG
#define EGL_CONTEXT_PRIORITY_LEVEL_IMG 0x3100
#define EGL_CONTEXT_PRIORITY_HIGH_IMG 0x3101
#endif

namespace noti::android {
namespace {

bool has_ext(const char* list, const char* name) {
  if (!list) return false;
  const size_t n = std::strlen(name);
  for (const char* p = list; (p = std::strstr(p, name)); p += n)
    if ((p == list || p[-1] == ' ') && (p[n] == ' ' || p[n] == '\0')) return true;
  return false;
}

GLuint compile(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  GLint ok = 0;
  glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[1024];
    glGetShaderInfoLog(s, sizeof(log), nullptr, log);
    LOGE("shader compile failed: %s", log);
  }
  return s;
}

GLuint link(const char* vs, const char* fs) {
  GLuint p = glCreateProgram();
  GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
  glAttachShader(p, v);
  glAttachShader(p, f);
  glLinkProgram(p);
  glDeleteShader(v);
  glDeleteShader(f);
  GLint ok = 0;
  glGetProgramiv(p, GL_LINK_STATUS, &ok);
  if (!ok) {
    char log[1024];
    glGetProgramInfoLog(p, sizeof(log), nullptr, log);
    LOGE("program link failed: %s", log);
  }
  return p;
}

constexpr uint64_t kBaseUsage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
constexpr uint64_t kFrontBufferUsage = AHARDWAREBUFFER_USAGE_FRONT_BUFFER;  // API 33

}  // namespace

GlFrontBufferRenderer::GlFrontBufferRenderer(const GlRendererOptions& opt) : opt_(opt) {}

GlFrontBufferRenderer::~GlFrontBufferRenderer() = default;

// ---------------------------------------------------------------------------
// Thread / context lifecycle
// ---------------------------------------------------------------------------

bool GlFrontBufferRenderer::thread_init() {
  perf_.init(opt_.adpf_target_ns);

  dpy_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (dpy_ == EGL_NO_DISPLAY || !eglInitialize(dpy_, nullptr, nullptr)) {
    LOGE("eglInitialize failed");
    return false;
  }
  const char* exts = eglQueryString(dpy_, EGL_EXTENSIONS);
  egl_get_native_client_buffer_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
      eglGetProcAddress("eglGetNativeClientBufferANDROID"));
  egl_create_image_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
  egl_destroy_image_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
  if (has_ext(exts, "EGL_ANDROID_native_fence_sync")) {
    egl_create_sync_ = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    egl_destroy_sync_ = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    egl_dup_fence_ = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
  }
  if (has_ext(exts, "EGL_KHR_wait_sync"))
    egl_wait_sync_ = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(eglGetProcAddress("eglWaitSyncKHR"));
  gl_image_target_texture_ = reinterpret_cast<void (*)(GLenum, void*)>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
  if (!egl_get_native_client_buffer_ || !egl_create_image_ || !gl_image_target_texture_) {
    LOGE("AHardwareBuffer → EGLImage path unavailable");
    return false;
  }

  const EGLint cfg_attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
  EGLConfig cfg;
  EGLint n = 0;
  if (!eglChooseConfig(dpy_, cfg_attrs, &cfg, 1, &n) || n < 1) {
    LOGE("eglChooseConfig failed");
    return false;
  }
  // High-priority GPU context: our tiny ink draws preempt page/PDF rendering.
  EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE, EGL_NONE, EGL_NONE};
  if (has_ext(exts, "EGL_IMG_context_priority")) {
    ctx_attrs[2] = EGL_CONTEXT_PRIORITY_LEVEL_IMG;
    ctx_attrs[3] = EGL_CONTEXT_PRIORITY_HIGH_IMG;
  }
  ctx_ = eglCreateContext(dpy_, cfg, EGL_NO_CONTEXT, ctx_attrs);
  if (ctx_ == EGL_NO_CONTEXT) {
    LOGE("eglCreateContext failed");
    return false;
  }
  if (!has_ext(exts, "EGL_KHR_surfaceless_context")) {
    const EGLint pb[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer_ = eglCreatePbufferSurface(dpy_, cfg, pb);
  }
  if (!eglMakeCurrent(dpy_, pbuffer_, pbuffer_, ctx_)) {
    LOGE("eglMakeCurrent failed");
    return false;
  }

  // Pipelines are created (and warmed by the clear+draw in surface_changed)
  // before the first stroke, so stroke #1 is as fast as stroke #1000.
  capsule_prog_ = link(kCapsuleVS, kCapsuleFS);
  u_cap_inv_size_ = glGetUniformLocation(capsule_prog_, "u_inv_size");
  u_cap_flip_ = glGetUniformLocation(capsule_prog_, "u_flip");
  u_cap_color_ = glGetUniformLocation(capsule_prog_, "u_color");
  composite_prog_ = link(kCompositeVS, kCompositeFS);
  u_comp_rect_ = glGetUniformLocation(composite_prog_, "u_rect");
  u_comp_inv_size_ = glGetUniformLocation(composite_prog_, "u_inv_size");
  u_comp_flip_ = glGetUniformLocation(composite_prog_, "u_flip");
  u_comp_tex_ = glGetUniformLocation(composite_prog_, "u_tex");

  glGenBuffers(1, &vbo_);
  glBindBuffer(GL_ARRAY_BUFFER, vbo_);  // stays bound for the context's lifetime
  glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(kRingCapsules * sizeof(Capsule)), nullptr, GL_STREAM_DRAW);
  glGenVertexArrays(1, &vao_);
  glBindVertexArray(vao_);
  glEnableVertexAttribArray(0);
  glEnableVertexAttribArray(1);
  glVertexAttribDivisor(0, 1);
  glVertexAttribDivisor(1, 1);
  glGenVertexArrays(1, &empty_vao_);

  glDisable(GL_DEPTH_TEST);
  glDisable(GL_DITHER);  // dithering would break MAX-blend idempotence
  glEnable(GL_SCISSOR_TEST);
  glEnable(GL_BLEND);
  glClearColor(0, 0, 0, 0);

  if (opt_.gpu_timing && egl_dup_fence_) {
    fence_stop_.store(false);
    fence_thread_ = std::thread([this] { fence_watcher_main(); });
  }
  LOGI("ink renderer ready (high-priority ctx: %s, native fences: %s)", ctx_attrs[2] != EGL_NONE ? "yes" : "no",
       egl_dup_fence_ ? "yes" : "no");
  return true;
}

void GlFrontBufferRenderer::thread_shutdown() {
  surface_destroyed();
  if (fence_thread_.joinable()) {
    fence_stop_.store(true);
    fence_wake_.notify();
    fence_thread_.join();
  }
  if (ctx_ != EGL_NO_CONTEXT) {
    glDeleteProgram(capsule_prog_);
    glDeleteProgram(composite_prog_);
    glDeleteBuffers(1, &vbo_);
    glDeleteVertexArrays(1, &vao_);
    glDeleteVertexArrays(1, &empty_vao_);
    eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy_, ctx_);
    ctx_ = EGL_NO_CONTEXT;
  }
  if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(dpy_, pbuffer_);
  pbuffer_ = EGL_NO_SURFACE;
  perf_.close();
}

// ---------------------------------------------------------------------------
// Targets
// ---------------------------------------------------------------------------

bool GlFrontBufferRenderer::create_target(Target& t, bool ahb_backed, bool front_buffer) {
  glGenTextures(1, &t.tex);
  glBindTexture(GL_TEXTURE_2D, t.tex);
  if (ahb_backed) {
    AHardwareBuffer_Desc d{};
    d.width = uint32_t(w_);
    d.height = uint32_t(h_);
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = kBaseUsage | (front_buffer ? kFrontBufferUsage : 0);
    if (AHardwareBuffer_allocate(&d, &t.ahb) != 0) {
      LOGE("AHardwareBuffer_allocate %dx%d failed", w_, h_);
      return false;
    }
    const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    t.image = egl_create_image_(dpy_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                egl_get_native_client_buffer_(t.ahb), attrs);
    if (t.image == EGL_NO_IMAGE_KHR) {
      LOGE("eglCreateImageKHR failed");
      return false;
    }
    gl_image_target_texture_(GL_TEXTURE_2D, t.image);
  } else {
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w_, h_);
  }
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glGenFramebuffers(1, &t.fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
  if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
    LOGE("framebuffer incomplete");
    return false;
  }
  t.dirty = {};
  t.seq = 0;
  return true;
}

void GlFrontBufferRenderer::destroy_target(Target& t) {
  if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
  if (t.tex) glDeleteTextures(1, &t.tex);
  if (t.image != EGL_NO_IMAGE_KHR && egl_destroy_image_) egl_destroy_image_(dpy_, t.image);
  if (t.ahb) AHardwareBuffer_release(t.ahb);
  const int fd = t.release_fence.exchange(-1);
  if (fd >= 0) close(fd);
  t.fbo = t.tex = 0;
  t.image = EGL_NO_IMAGE_KHR;
  t.ahb = nullptr;
  t.dirty = {};
  t.seq = 0;
}

NOTI_INLINE void GlFrontBufferRenderer::bind_target(const Target& t) { glBindFramebuffer(GL_FRAMEBUFFER, t.fbo); }

NOTI_INLINE void GlFrontBufferRenderer::scissor(const RectI& r) {
  const int32_t y = opt_.flip_y ? h_ - r.y1 : r.y0;
  glScissor(r.x0, y, r.width(), r.height());
}

void GlFrontBufferRenderer::clear_rect(const Target& t, const RectI& r) {
  if (r.empty()) return;
  bind_target(t);
  scissor(r);
  glClear(GL_COLOR_BUFFER_BIT);
}

NOTI_INLINE void GlFrontBufferRenderer::set_color(const float* c) {
  if (std::memcmp(c, color_, sizeof(color_)) != 0) {
    std::memcpy(color_, c, sizeof(color_));
    glUniform4fv(u_cap_color_, 1, color_);
  }
}

// Ring-buffered instance upload. Unsynchronised maps never stall on the GPU:
// by the time the ring wraps (65 536 capsules) those draws are long done, and
// the wrap itself orphans the buffer.
uint32_t GlFrontBufferRenderer::upload(const Capsule* c, uint32_t n) {
  GLbitfield flags = GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT;
  if (ring_pos_ + n > kRingCapsules) {
    ring_pos_ = 0;
    flags |= GL_MAP_INVALIDATE_BUFFER_BIT;
  } else {
    flags |= GL_MAP_INVALIDATE_RANGE_BIT;
  }
  void* p = glMapBufferRange(GL_ARRAY_BUFFER, GLintptr(ring_pos_ * sizeof(Capsule)), GLsizeiptr(n * sizeof(Capsule)), flags);
  if (NOTI_LIKELY(p)) {
    std::memcpy(p, c, n * sizeof(Capsule));
    glUnmapBuffer(GL_ARRAY_BUFFER);
  }
  const uint32_t first = ring_pos_;
  ring_pos_ += n;
  return first;
}

NOTI_INLINE void GlFrontBufferRenderer::draw_capsules(uint32_t first, uint32_t n) {
  const uintptr_t off = uintptr_t(first) * sizeof(Capsule);
  glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Capsule), reinterpret_cast<const void*>(off));
  glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Capsule), reinterpret_cast<const void*>(off + 12));
  glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, GLsizei(n));
}

// ---------------------------------------------------------------------------
// Fences
// ---------------------------------------------------------------------------

int GlFrontBufferRenderer::native_fence() {
  if (!egl_create_sync_ || !egl_dup_fence_) {
    glFlush();
    return -1;
  }
  const EGLint attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE};
  EGLSyncKHR s = egl_create_sync_(dpy_, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
  glFlush();
  if (s == EGL_NO_SYNC_KHR) return -1;
  const int fd = egl_dup_fence_(dpy_, s);
  egl_destroy_sync_(dpy_, s);
  return fd;
}

// Wait for SurfaceFlinger to release a buffer on the GPU timeline (the CPU
// never blocks). EGL takes ownership of the fd.
void GlFrontBufferRenderer::gpu_wait_release(Target& t) {
  const int fd = t.release_fence.exchange(-1);
  if (fd < 0) return;
  if (egl_create_sync_ && egl_wait_sync_) {
    const EGLint attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE};
    EGLSyncKHR s = egl_create_sync_(dpy_, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (s != EGL_NO_SYNC_KHR) {
      egl_wait_sync_(dpy_, s, 0);
      egl_destroy_sync_(dpy_, s);
      return;
    }
  }
  pollfd p{fd, POLLIN, 0};
  poll(&p, 1, 50);
  close(fd);
}

void GlFrontBufferRenderer::on_tx_complete(void* ctx, ASurfaceTransactionStats* stats) {
  auto* c = static_cast<TxContext*>(ctx);
  GlFrontBufferRenderer* self = c->self;
  auto store_fence = [&](Target& t, ASurfaceControl* sc) {
    const int fd = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, sc);
    const int old = t.release_fence.exchange(fd);
    if (old >= 0) close(old);
  };
  if (c->prev_wet >= 0 && self->wet_sc_) store_fence(self->wet_[c->prev_wet], self->wet_sc_);
  if (c->prev_dry >= 0 && self->dry_sc_) store_fence(self->dry_[c->prev_dry], self->dry_sc_);
  self->tx_pending_.fetch_sub(1, std::memory_order_acq_rel);
}

void GlFrontBufferRenderer::fence_watcher_main() {
  FenceItem items[32];
  for (;;) {
    const size_t n = fences_.pop(items, 32);
    if (n == 0) {
      if (fence_stop_.load(std::memory_order_acquire)) break;
      fence_wake_.prepare_wait();
      if (!fences_.empty_acquire() || fence_stop_.load(std::memory_order_acquire)) {
        fence_wake_.cancel_wait();
        continue;
      }
      fence_wake_.wait();
      continue;
    }
    for (size_t i = 0; i < n; ++i) {
      pollfd p{items[i].fd, POLLIN, 0};
      poll(&p, 1, 100);
      if (probe_) probe_->submit_to_gpu.record_signed(now_ns() - items[i].t_submit);
      close(items[i].fd);
    }
  }
}

// ---------------------------------------------------------------------------
// Surface
// ---------------------------------------------------------------------------

void GlFrontBufferRenderer::destroy_surface_resources() {
  for (Target& t : wet_) destroy_target(t);
  for (Target& t : dry_) destroy_target(t);
  destroy_target(backing_);
  destroy_target(scratch_);
  if (wet_sc_) ASurfaceControl_release(wet_sc_);
  if (dry_sc_) ASurfaceControl_release(dry_sc_);
  wet_sc_ = dry_sc_ = nullptr;
  if (window_) ANativeWindow_release(window_);
  window_ = nullptr;
  w_ = h_ = 0;
}

void GlFrontBufferRenderer::surface_changed(void* native_window, int32_t w, int32_t h) {
  auto* window = static_cast<ANativeWindow*>(native_window);
  if (window == window_ && w == w_ && h == h_) {
    if (window) ANativeWindow_release(window);  // we already hold a reference
    return;
  }
  surface_destroyed();
  window_ = window;
  w_ = w;
  h_ = h;
  if (!window_ || w <= 0 || h <= 0) return;

  AHardwareBuffer_Desc probe_desc{};
  probe_desc.width = uint32_t(w);
  probe_desc.height = uint32_t(h);
  probe_desc.layers = 1;
  probe_desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
  probe_desc.usage = kBaseUsage | kFrontBufferUsage;
  front_buffer_supported_ = AHardwareBuffer_isSupported(&probe_desc) == 1;
  if (!front_buffer_supported_) LOGW("USAGE_FRONT_BUFFER not supported: wet layer relies on resubmission");

  bool ok = true;
  for (Target& t : wet_) ok &= create_target(t, true, front_buffer_supported_);
  for (Target& t : dry_) ok &= create_target(t, true, false);
  ok &= create_target(backing_, false, false);
  ok &= create_target(scratch_, false, false);
  if (!ok) {
    LOGE("surface resources failed; ink disabled for this surface");
    destroy_surface_resources();
    return;
  }

  glViewport(0, 0, w_, h_);
  glUseProgram(composite_prog_);
  glUniform2f(u_comp_inv_size_, 1.0f / float(w_), 1.0f / float(h_));
  glUniform1f(u_comp_flip_, opt_.flip_y ? 1.0f : 0.0f);
  glUniform1i(u_comp_tex_, 0);
  glUseProgram(capsule_prog_);
  glUniform2f(u_cap_inv_size_, 1.0f / float(w_), 1.0f / float(h_));
  glUniform1f(u_cap_flip_, opt_.flip_y ? 1.0f : 0.0f);
  color_[0] = -1;  // force colour upload

  const RectI all{0, 0, w_, h_};
  for (Target& t : wet_) clear_rect(t, all);
  for (Target& t : dry_) clear_rect(t, all);
  clear_rect(backing_, all);
  clear_rect(scratch_, all);
  // Restore committed ink (e.g. after rotation or app resume).
  if (store_) {
    for (uint32_t s = 0; s < store_->stroke_count(); ++s) draw_stroke_into_dry(dry_[0], *store_, s);
    dry_[0].seq = store_->stroke_count();
  }
  const int fence = native_fence();

  wet_sc_ = ASurfaceControl_createFromWindow(window_, "noti-wet");
  dry_sc_ = ASurfaceControl_createFromWindow(window_, "noti-dry");
  if (!wet_sc_ || !dry_sc_) {
    LOGE("ASurfaceControl_createFromWindow failed");
    if (fence >= 0) close(fence);
    destroy_surface_resources();
    return;
  }
  ASurfaceTransaction* tx = ASurfaceTransaction_create();
  ASurfaceTransaction_setZOrder(tx, dry_sc_, 1);
  ASurfaceTransaction_setZOrder(tx, wet_sc_, 2);
  ASurfaceTransaction_setBuffer(tx, dry_sc_, dry_[0].ahb, fence >= 0 ? dup(fence) : -1);
  ASurfaceTransaction_setBuffer(tx, wet_sc_, wet_[0].ahb, fence);
  ASurfaceTransaction_setBufferTransparency(tx, dry_sc_, ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
  ASurfaceTransaction_setBufferTransparency(tx, wet_sc_, ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
  ASurfaceTransaction_setVisibility(tx, dry_sc_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
  ASurfaceTransaction_setVisibility(tx, wet_sc_, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
  ASurfaceTransaction_apply(tx);
  ASurfaceTransaction_delete(tx);
  cur_wet_ = 0;
  cur_dry_ = 0;
  apply_frame_rate(pen_near_);
  LOGI("surface %dx%d attached (front buffer: %s)", w_, h_, front_buffer_supported_ ? "yes" : "no");
}

void GlFrontBufferRenderer::surface_destroyed() {
  if (wet_sc_ || dry_sc_) {
    ASurfaceTransaction* tx = ASurfaceTransaction_create();
    if (wet_sc_) ASurfaceTransaction_reparent(tx, wet_sc_, nullptr);
    if (dry_sc_) ASurfaceTransaction_reparent(tx, dry_sc_, nullptr);
    ASurfaceTransaction_apply(tx);
    ASurfaceTransaction_delete(tx);
    // Outstanding completion callbacks reference our layers: let them land.
    const int64_t deadline = now_ns() + 250'000'000;
    while (tx_pending_.load(std::memory_order_acquire) > 0 && now_ns() < deadline) usleep(1000);
  }
  destroy_surface_resources();
}

void GlFrontBufferRenderer::apply_frame_rate(bool near) {
  if (!wet_sc_ || !dry_sc_) return;
  ASurfaceTransaction* tx = ASurfaceTransaction_create();
  for (ASurfaceControl* sc : {wet_sc_, dry_sc_}) {
    // Pen near: demand the panel's max rate, even if the switch isn't seamless
    // (HyperOS may otherwise drop to 60 Hz while the stylus is in use).
    ASurfaceTransaction_setFrameRateWithChangeStrategy(
        tx, sc, near ? opt_.pen_frame_rate : 0.0f,
        near ? ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE : ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT,
        near ? ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS : ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
  }
  ASurfaceTransaction_apply(tx);
  ASurfaceTransaction_delete(tx);
}

void GlFrontBufferRenderer::hover(bool near) {
  // HOVER_EXIT also precedes every pen-down, so "far" is deferred (see idle()).
  if (near) {
    far_since_ns_ = 0;
    if (!pen_near_) {
      pen_near_ = true;
      perf_.set_pen_near(true);
      apply_frame_rate(true);
    }
  } else {
    far_since_ns_ = now_ns();
  }
}

// ---------------------------------------------------------------------------
// Hot path
// ---------------------------------------------------------------------------

void GlFrontBufferRenderer::render(const FrameUpdate& f) {
  if (NOTI_UNLIKELY(!wet_sc_)) return;
  far_since_ns_ = 0;  // drawing: the pen is on the glass
  if (NOTI_UNLIKELY(!pen_near_)) hover(true);

  Target& wet = wet_[cur_wet_];
  glUseProgram(capsule_prog_);
  glBindVertexArray(vao_);
  glBlendEquation(GL_MAX);  // union of capsules: no double-blended joints
  set_color(f.color);

  if (f.ink_count && !f.ink_rect.empty()) {
    const uint32_t first = upload(f.ink, std::min<uint32_t>(f.ink_count, kRingCapsules));
    scissor(f.ink_rect);
    bind_target(backing_);
    draw_capsules(first, f.ink_count);
    bind_target(wet);
    draw_capsules(first, f.ink_count);
  }
  if (!f.restore_rect.empty()) {
    // Erase the previous predicted tail: wet ← backing in its rect.
    const RectI& r = f.restore_rect;
    const int32_t y0 = opt_.flip_y ? h_ - r.y1 : r.y0, y1 = opt_.flip_y ? h_ - r.y0 : r.y1;
    scissor(r);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, backing_.fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, wet.fbo);
    glBlitFramebuffer(r.x0, y0, r.x1, y1, r.x0, y0, r.x1, y1, GL_COLOR_BUFFER_BIT, GL_NEAREST);
  }
  if (f.pred_count && !f.pred_rect.empty()) {
    const uint32_t first = upload(f.pred, f.pred_count);
    scissor(f.pred_rect);
    bind_target(wet);
    draw_capsules(first, f.pred_count);
  }
  wet.dirty = wet.dirty.united(f.ink_rect).united(f.pred_rect);

  if (NOTI_UNLIKELY(probe_ && opt_.gpu_timing && egl_dup_fence_)) {
    const int fd = native_fence();  // includes the flush
    if (fd >= 0) {
      if (fences_.try_push(FenceItem{fd, now_ns()})) fence_wake_.notify();
      else close(fd);
    }
  } else {
    glFlush();  // submit now; never glFinish
  }
  if (opt_.resubmit_front_buffer) resubmit_front();
}

void GlFrontBufferRenderer::resubmit_front() {
  const int64_t now = now_ns();
  if (now - last_resubmit_ns_ < 3'000'000) {  // ≤ one per refresh at 144 Hz
    resubmit_pending_ = true;
    return;
  }
  ASurfaceTransaction* tx = ASurfaceTransaction_create();
  ASurfaceTransaction_setBuffer(tx, wet_sc_, wet_[cur_wet_].ahb, -1);
  ASurfaceTransaction_apply(tx);
  ASurfaceTransaction_delete(tx);
  last_resubmit_ns_ = now;
  resubmit_pending_ = false;
}

void GlFrontBufferRenderer::batch_done(int64_t cpu_ns) { perf_.report(cpu_ns); }

int64_t GlFrontBufferRenderer::idle() {
  if (resubmit_pending_ && wet_sc_) resubmit_front();
  if (far_since_ns_ > 0) {
    const int64_t remaining = far_since_ns_ + kFarDelayNs - now_ns();
    if (remaining > 0) return remaining;  // wake us when it is time
    far_since_ns_ = 0;
    pen_near_ = false;
    perf_.set_pen_near(false);
    apply_frame_rate(false);
  }
  return -1;
}

// ---------------------------------------------------------------------------
// Commit / cancel (pen-up path: off the per-sample hot path)
// ---------------------------------------------------------------------------

void GlFrontBufferRenderer::draw_stroke_into_dry(Target& dry, const StrokeStore& store, uint32_t id) {
  const StrokeRecord& s = store.stroke(id);
  const RectI r = pixel_bounds(s.bounds, w_, h_);
  if (r.empty()) return;
  // 1. Union-render the stroke on its own (MAX blend) into scratch.
  scissor(r);
  bind_target(scratch_);
  glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(capsule_prog_);
  glBindVertexArray(vao_);
  glBlendEquation(GL_MAX);
  set_color(s.color);
  const Capsule* caps = store.capsules(id);
  for (uint32_t done = 0; done < s.capsule_count;) {
    const uint32_t n = std::min(s.capsule_count - done, kRingCapsules);
    draw_capsules(upload(caps + done, n), n);
    done += n;
  }
  // 2. Composite it over the dry layer (premultiplied src-over), so
  //    translucent ink looks identical wet and dry.
  bind_target(dry);
  glUseProgram(composite_prog_);
  glBindVertexArray(empty_vao_);
  glBlendEquation(GL_FUNC_ADD);
  glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, scratch_.tex);
  glUniform4f(u_comp_rect_, float(r.x0), float(r.y0), float(r.x1), float(r.y1));
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void GlFrontBufferRenderer::commit(const StrokeStore& store, uint32_t id, const RectI& wet_rect) {
  store_ = &store;
  if (!wet_sc_) return;
  const int next_wet = 1 - cur_wet_;
  const int next_dry = (cur_dry_ + 1) % 3;
  Target& nw = wet_[next_wet];
  Target& nd = dry_[next_dry];

  // Next wet buffer: wait (GPU-side) until SurfaceFlinger let go of it, then
  // clear the stroke it showed last time.
  gpu_wait_release(nw);
  clear_rect(nw, nw.dirty);
  nw.dirty = {};
  // Next dry buffer: bring it up to date with every stroke it lacks.
  gpu_wait_release(nd);
  for (uint32_t s = nd.seq; s <= id; ++s) draw_stroke_into_dry(nd, store, s);
  nd.seq = id + 1;
  clear_rect(backing_, wet_rect);
  const int fence = native_fence();

  // One atomic transaction: new dry content appears in the same frame the
  // wet stroke disappears. No flicker, no gap, no hide/show of the wet layer.
  ASurfaceTransaction* tx = ASurfaceTransaction_create();
  ASurfaceTransaction_setBuffer(tx, dry_sc_, nd.ahb, fence >= 0 ? dup(fence) : -1);
  ASurfaceTransaction_setBuffer(tx, wet_sc_, nw.ahb, fence);
  TxContext& c = tx_ctx_[tx_next_++ % (sizeof(tx_ctx_) / sizeof(tx_ctx_[0]))];
  c = {this, int8_t(cur_wet_), int8_t(cur_dry_)};
  tx_pending_.fetch_add(1, std::memory_order_acq_rel);
  ASurfaceTransaction_setOnComplete(tx, &c, &GlFrontBufferRenderer::on_tx_complete);
  ASurfaceTransaction_apply(tx);
  ASurfaceTransaction_delete(tx);
  cur_wet_ = next_wet;
  cur_dry_ = next_dry;
  last_resubmit_ns_ = now_ns();
  resubmit_pending_ = false;
}

void GlFrontBufferRenderer::cancel(const RectI& wet_rect) {
  if (!wet_sc_) return;
  clear_rect(backing_, wet_rect);
  clear_rect(wet_[cur_wet_], wet_rect);
  glFlush();
  if (opt_.resubmit_front_buffer) resubmit_front();
}

}  // namespace noti::android
