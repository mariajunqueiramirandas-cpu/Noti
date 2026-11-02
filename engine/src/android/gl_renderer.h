// OpenGL ES 3 front-buffer renderer on ASurfaceControl (plan §3, §4.2, §4.4).
//
//   SurfaceView window
//    ├─ dry layer  (z=1)  3 × AHardwareBuffer, multi-buffered, committed ink
//    └─ wet layer  (z=2)  2 × AHardwareBuffer with USAGE_FRONT_BUFFER,
//                          drawn into *while scanned out*; ping-ponged on commit
//   private: backing texture (real wet ink, no prediction) + scratch texture
//
// Hot path (render), visible work first:
//   blit (erase old tail) + ink draw + tail draw into the front buffer → glFlush
//   then ink into the backing texture → glFlush (GPU does it while we wait for
//   the next sample). All scissored to a few thousand pixels; instance data is
//   written straight into a persistently mapped ring. No CPU wait, no
//   allocation, and no binder call on this thread during or after a stroke:
//   every ASurfaceTransaction is applied by a separate transaction thread.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/surface_control.h>

#include <atomic>
#include <thread>

#include "noti/renderer.h"
#include "noti/spsc_ring.h"
#include "noti/wake_event.h"
#include "perf_session.h"

namespace noti::android {

struct GlRendererOptions {
  float frame_rate = 144.0f;          // requested on both layers, always (Pad 7 max)
  bool flip_y = false;                // set if ink appears vertically mirrored on a device
  bool gpu_timing = false;            // native fence per frame → probe.submit_to_gpu (debug)
  // Re-attach the front buffer with a transaction after drawing (rate-limited
  // to once per refresh). Needed only if HWC composites the wet layer on the
  // GPU (CLIENT); turn off once Phase 0 confirms DEVICE (overlay) composition.
  bool resubmit_front_buffer = true;
  int64_t adpf_target_ns = 500'000;
  // Power is not a constraint: touch the GPU this often, always, so it never
  // drops into a low-power state it must wake from on the next draw.
  int64_t gpu_keepalive_ns = 4'000'000;
  // Pin the ink thread to the fastest core and keep that core hot with a
  // SCHED_IDLE spinner (see perf_session.h).
  bool pin_and_heat_core = true;
};

class GlFrontBufferRenderer final : public InkRenderer {
 public:
  explicit GlFrontBufferRenderer(const GlRendererOptions& opt);
  ~GlFrontBufferRenderer() override;

  bool thread_init() override;
  void thread_shutdown() override;
  void surface_changed(void* native_window, int32_t w, int32_t h) override;
  void surface_destroyed() override;
  void render(const FrameUpdate& f) override;
  void commit(const StrokeStore& store, uint32_t id, const RectI& wet_rect) override;
  void cancel(const RectI& wet_rect) override;
  void batch_done(int64_t cpu_ns) override;
  int64_t idle() override;

  bool front_buffer_supported() const { return front_buffer_supported_; }

 private:
  struct Target {
    AHardwareBuffer* ahb = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint tex = 0, fbo = 0;
    std::atomic<int> release_fence{-1};  // written by the transaction callback (binder thread)
    RectI dirty;                         // wet: region that holds ink
    uint32_t seq = 0;                    // dry: committed strokes [0, seq) are drawn in it
  };
  struct TxContext {
    GlFrontBufferRenderer* self;
    int8_t prev_wet, prev_dry;
  };
  // Work for the transaction thread (POD: travels through an SPSC ring).
  struct TxJob {
    enum Kind : uint8_t { kResubmit, kCommit, kFrameRate } kind;
    int fence;
    AHardwareBuffer* wet;
    AHardwareBuffer* dry;
    TxContext* ctx;
    ARect damage;
  };

  bool create_target(Target& t, bool ahb_backed, bool front_buffer);
  void destroy_target(Target& t);
  void destroy_surface_resources();
  void gpu_wait_release(Target& t);
  int native_fence();
  uint32_t upload(const Capsule* c, uint32_t n);
  void draw_capsules(uint32_t first, uint32_t n);
  void bind_target(const Target& t);
  void scissor(const RectI& r);
  void clear_rect(const Target& t, const RectI& r);
  void set_color(const float* premul);
  void draw_stroke_into_dry(Target& dry, const StrokeStore& store, uint32_t id);
  void apply_frame_rate();
  void resubmit_front();
  void post_tx(const TxJob& job);
  void drain_tx();
  void tx_thread_main();
  static void on_tx_complete(void* ctx, ASurfaceTransactionStats* stats);
  void fence_watcher_main();
  void keep_gpu_awake(int64_t now);

  GlRendererOptions opt_;
  PerfSession perf_;

  // EGL / GL.
  EGLDisplay dpy_ = EGL_NO_DISPLAY;
  EGLContext ctx_ = EGL_NO_CONTEXT;
  EGLSurface pbuffer_ = EGL_NO_SURFACE;
  GLuint capsule_prog_ = 0, composite_prog_ = 0, vao_ = 0, vbo_ = 0, empty_vao_ = 0;
  GLint u_cap_inv_size_ = -1, u_cap_flip_ = -1, u_cap_color_ = -1;
  GLint u_comp_rect_ = -1, u_comp_inv_size_ = -1, u_comp_flip_ = -1, u_comp_tex_ = -1;
  uint32_t ring_pos_ = 0;
  static constexpr uint32_t kRingCapsules = 1u << 16;
  static constexpr uint32_t kRingHalf = kRingCapsules / 2;
  Capsule* ring_ptr_ = nullptr;  // persistent coherent mapping (GL_EXT_buffer_storage), or null
  GLsync half_fence_[2] = {nullptr, nullptr};
  int64_t last_gpu_ns_ = 0;
  float color_[4] = {-1, -1, -1, -1};

  PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC egl_get_native_client_buffer_ = nullptr;
  PFNEGLCREATEIMAGEKHRPROC egl_create_image_ = nullptr;
  PFNEGLDESTROYIMAGEKHRPROC egl_destroy_image_ = nullptr;
  PFNEGLCREATESYNCKHRPROC egl_create_sync_ = nullptr;
  PFNEGLDESTROYSYNCKHRPROC egl_destroy_sync_ = nullptr;
  PFNEGLWAITSYNCKHRPROC egl_wait_sync_ = nullptr;
  PFNEGLDUPNATIVEFENCEFDANDROIDPROC egl_dup_fence_ = nullptr;
  void (*gl_image_target_texture_)(GLenum, void*) = nullptr;

  // Surface.
  ANativeWindow* window_ = nullptr;
  ASurfaceControl* wet_sc_ = nullptr;
  ASurfaceControl* dry_sc_ = nullptr;
  int32_t w_ = 0, h_ = 0;
  Target wet_[2];
  Target dry_[3];
  Target backing_, scratch_;
  int cur_wet_ = 0, cur_dry_ = 0;
  bool front_buffer_supported_ = false;
  const StrokeStore* store_ = nullptr;

  // Transaction callbacks.
  TxContext tx_ctx_[16];
  uint32_t tx_next_ = 0;
  std::atomic<int32_t> tx_pending_{0};

  // Front-buffer resubmission (rate limited), damage accumulated in between.
  int64_t last_resubmit_ns_ = 0;
  bool resubmit_pending_ = false;
  RectI resubmit_damage_;

  // Transaction thread: the only thread that makes binder calls after setup.
  SpscRing<TxJob, 64> tx_jobs_;
  WakeEvent tx_wake_;
  std::atomic<bool> tx_stop_{false};
  std::atomic<uint32_t> tx_done_{0};
  uint32_t tx_posted_ = 0;
  std::thread tx_thread_;

  // GPU completion timing (debug).
  struct FenceItem { int fd; int64_t t_submit; };
  SpscRing<FenceItem, 256> fences_;
  WakeEvent fence_wake_;
  std::atomic<bool> fence_stop_{false};
  std::thread fence_thread_;
};

}  // namespace noti::android
