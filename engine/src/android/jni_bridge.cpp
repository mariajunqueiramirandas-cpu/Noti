// JNI surface of libnoti_ink.so. Kotlin side: android/kotlin/app/noti/ink/
//
// Threading model (inline mode):
//   ink thread  (Kotlin "noti-ink" HandlerThread, URGENT_DISPLAY + ADPF):
//     owns the input window, the EGL context and the engine loop.
//     nativeAttach / nativeProcess / nativeIdle / nativeVsync /
//     nativeSurfaceChanged / nativeSurfaceDestroyed / nativeExportRecording /
//     nativeDetach must be called here.
//   UI thread: setters only (nativeSet*, nativeResetStats). They go through a
//     lock-free command ring (single producer = the UI thread) and take effect
//     at the next nativeProcess/nativeIdle on the ink thread.
//
// Per MotionEvent the ink thread writes N InkSample records (40 B, native byte
// order) into a direct ByteBuffer that *is* native memory and calls
// @CriticalNative nativeProcess(handle, N): a plain C call (no JNIEnv, no
// marshalling, no pinning) that predicts and draws before returning.
// All natives are bound with RegisterNatives (required for @CriticalNative).
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <new>

#include "gl_renderer.h"
#include "noti/engine.h"

namespace {

using noti::Command;
using noti::CommandType;

constexpr uint32_t kStagingSamples = 512;  // > max historical samples in one MotionEvent

struct Instance {
  noti::android::GlFrontBufferRenderer renderer;
  noti::InkEngine engine;
  alignas(64) noti::InkSample staging[kStagingSamples];

  Instance(const noti::EngineConfig& cfg, const noti::android::GlRendererOptions& opt)
      : renderer(opt), engine(cfg, renderer) {}
};

inline Instance* inst(jlong h) { return reinterpret_cast<Instance*>(h); }

// ---- lifecycle (regular JNI) ----

jlong nativeCreate(JNIEnv*, jclass, jfloat horizon_ms, jboolean gpu_timing, jboolean flip_y, jboolean probe) {
  noti::EngineConfig cfg;
  cfg.predictor.horizon_ms = horizon_ms;  // used until vsync timing arrives
  cfg.probe = probe;
  noti::android::GlRendererOptions opt;
  opt.gpu_timing = gpu_timing;
  opt.flip_y = flip_y;
  return reinterpret_cast<jlong>(new (std::nothrow) Instance(cfg, opt));
}

jboolean nativeAttach(JNIEnv*, jclass, jlong h) { return inst(h)->engine.attach_inline() ? JNI_TRUE : JNI_FALSE; }
void nativeDetach(JNIEnv*, jclass, jlong h) { inst(h)->engine.detach_inline(); }
void nativeDestroy(JNIEnv*, jclass, jlong h) { delete inst(h); }

jobject nativeStaging(JNIEnv* env, jclass, jlong h) {
  return env->NewDirectByteBuffer(inst(h)->staging, jlong(sizeof(inst(h)->staging)));
}

void nativeSurfaceChanged(JNIEnv* env, jclass, jlong h, jobject surface, jint w, jint hh) {
  Command c{CommandType::kSurfaceChanged};
  c.ptr = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;  // reference passed to the renderer
  c.i0 = w;
  c.i1 = hh;
  inst(h)->engine.execute(c);
}

void nativeSurfaceDestroyed(JNIEnv*, jclass, jlong h) {
  inst(h)->engine.execute(Command{CommandType::kSurfaceDestroyed});
}

jlong nativeExportRecording(JNIEnv* env, jclass, jlong h, jstring path) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  const jlong n = inst(h)->engine.export_recording(p);
  env->ReleaseStringUTFChars(path, p);
  return n;
}

// out: [samples, frames, strokes, dropped,
//       in→recv p50, p99, recv→submit p50, p99, in→submit p50, p99, gpu p50, p99,
//       frame-cpu p50, p99, pred-error p50 (1/100 px), p99, horizon p50 (µs), p99]
void nativeStats(JNIEnv* env, jclass, jlong h, jlongArray out) {
  const noti::InkEngine& e = inst(h)->engine;
  const noti::LatencyProbe& p = e.probe();
  const jlong v[] = {
      jlong(e.stats().samples.load()), jlong(e.stats().frames.load()), jlong(e.stats().strokes.load()),
      jlong(p.dropped_samples.load()),
      jlong(p.in_to_recv.percentile(50)), jlong(p.in_to_recv.percentile(99)),
      jlong(p.recv_to_submit.percentile(50)), jlong(p.recv_to_submit.percentile(99)),
      jlong(p.in_to_submit.percentile(50)), jlong(p.in_to_submit.percentile(99)),
      jlong(p.submit_to_gpu.percentile(50)), jlong(p.submit_to_gpu.percentile(99)),
      jlong(p.frame_cpu.percentile(50)), jlong(p.frame_cpu.percentile(99)),
      jlong(p.pred_error_cpx.percentile(50)), jlong(p.pred_error_cpx.percentile(99)),
      jlong(p.horizon_us.percentile(50)), jlong(p.horizon_us.percentile(99)),
  };
  const jsize n = std::min<jsize>(env->GetArrayLength(out), jsize(sizeof(v) / sizeof(v[0])));
  env->SetLongArrayRegion(out, 0, n, v);
}

// ---- ink thread hot path (@CriticalNative: no JNIEnv / jclass) ----

jlong nativeProcess(jlong h, jint count) {
  Instance* i = inst(h);
  const uint32_t n = count < 0 ? 0u : std::min(uint32_t(count), kStagingSamples);
  return i->engine.process(i->staging, n);
}

jlong nativeIdle(jlong h) { return inst(h)->engine.idle(); }

void nativeVsync(jlong h, jlong present_ns, jlong period_ns) {
  Command c{CommandType::kVsync};
  c.t = present_ns;
  c.i0 = jint(period_ns);
  inst(h)->engine.execute(c);
}

// ---- UI thread setters (@CriticalNative, lock-free command ring) ----

void post(jlong h, const Command& c) { inst(h)->engine.post(c); }

void nativeSetColor(jlong h, jfloat r, jfloat g, jfloat b, jfloat a) {
  Command c{CommandType::kSetColor};
  c.f[0] = r; c.f[1] = g; c.f[2] = b; c.f[3] = a;
  post(h, c);
}
void nativeSetWidth(jlong h, jfloat width_px) {
  Command c{CommandType::kSetWidth};
  c.f[0] = width_px;
  post(h, c);
}
void nativeSetPressureCurve(jlong h, jfloat gamma, jfloat floor) {
  Command c{CommandType::kSetPressureGamma};
  c.f[0] = gamma; c.f[1] = floor;
  post(h, c);
}
void nativeSetHorizon(jlong h, jfloat ms) {
  Command c{CommandType::kSetHorizon};
  c.f[0] = ms;
  post(h, c);
}
void nativeSetPredictor(jlong h, jint model) {
  Command c{CommandType::kSetPredictor};
  c.i0 = model;
  post(h, c);
}
void nativeSetTransform(jlong h, jfloat a, jfloat b, jfloat tx, jfloat c_, jfloat d, jfloat ty) {
  Command c{CommandType::kSetTransform};
  c.f[0] = a; c.f[1] = b; c.f[2] = tx; c.f[3] = c_; c.f[4] = d; c.f[5] = ty;
  post(h, c);
}
void nativeSetDisplay(jlong h, jfloat panel_ms, jfloat gpu_ms, jint scan_axis, jboolean reversed) {
  Command c{CommandType::kSetDisplay};
  c.f[0] = panel_ms; c.f[1] = gpu_ms; c.i0 = scan_axis; c.i1 = reversed ? 1 : 0;
  post(h, c);
}
void nativeSetRecording(jlong h, jboolean on) {
  Command c{CommandType::kSetRecording};
  c.i0 = on ? 1 : 0;
  post(h, c);
}
void nativeResetStats(jlong h) { post(h, Command{CommandType::kResetProbe}); }

#define M(name, sig) {#name, sig, reinterpret_cast<void*>(name)}
const JNINativeMethod kMethods[] = {
    M(nativeCreate, "(FZZZ)J"),
    M(nativeAttach, "(J)Z"),
    M(nativeDetach, "(J)V"),
    M(nativeDestroy, "(J)V"),
    M(nativeStaging, "(J)Ljava/nio/ByteBuffer;"),
    M(nativeSurfaceChanged, "(JLandroid/view/Surface;II)V"),
    M(nativeSurfaceDestroyed, "(J)V"),
    M(nativeExportRecording, "(JLjava/lang/String;)J"),
    M(nativeStats, "(J[J)V"),
    M(nativeProcess, "(JI)J"),
    M(nativeIdle, "(J)J"),
    M(nativeVsync, "(JJJ)V"),
    M(nativeSetColor, "(JFFFF)V"),
    M(nativeSetWidth, "(JF)V"),
    M(nativeSetPressureCurve, "(JFF)V"),
    M(nativeSetHorizon, "(JF)V"),
    M(nativeSetPredictor, "(JI)V"),
    M(nativeSetTransform, "(JFFFFFF)V"),
    M(nativeSetDisplay, "(JFFIZ)V"),
    M(nativeSetRecording, "(JZ)V"),
    M(nativeResetStats, "(J)V"),
};
#undef M

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = env->FindClass("app/noti/ink/NotiInk");
  if (!cls) return JNI_ERR;
  if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
