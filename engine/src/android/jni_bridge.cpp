// JNI surface of libnoti_ink.so. Kotlin counterpart: android/kotlin/app/noti/ink/NotiInk.kt
//
// Input path (per MotionEvent, on the UI thread):
//   Kotlin writes N InkSample records (40 B each, native byte order) into a
//   direct ByteBuffer that *is* native memory, then calls
//   @CriticalNative nativePush(handle, N): no JNIEnv, no object marshalling,
//   no array pinning: a plain C call into InkEngine::push (memcpy into the
//   SPSC ring + one release store + one fence).
//
// All natives are bound with RegisterNatives in JNI_OnLoad (required for
// @CriticalNative, and avoids symbol lookup).
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

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

// ---- regular JNI ----

jlong nativeCreate(JNIEnv*, jclass, jfloat horizon_ms, jboolean gpu_timing, jboolean flip_y) {
  noti::EngineConfig cfg;
  cfg.predictor.horizon_ms = horizon_ms;
  noti::android::GlRendererOptions opt;
  opt.gpu_timing = gpu_timing;
  opt.flip_y = flip_y;
  auto* i = new (std::nothrow) Instance(cfg, opt);
  if (!i) return 0;
  i->engine.start();
  return reinterpret_cast<jlong>(i);
}

void nativeDestroy(JNIEnv*, jclass, jlong h) {
  Instance* i = inst(h);
  if (!i) return;
  i->engine.stop();
  delete i;
}

jobject nativeStaging(JNIEnv* env, jclass, jlong h) {
  return env->NewDirectByteBuffer(inst(h)->staging, jlong(sizeof(inst(h)->staging)));
}

void nativeSurfaceChanged(JNIEnv* env, jclass, jlong h, jobject surface, jint w, jint hh) {
  ANativeWindow* win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;  // ref passed to renderer
  Command c{CommandType::kSurfaceChanged};
  c.ptr = win;
  c.i0 = w;
  c.i1 = hh;
  inst(h)->engine.post_sync(c);
}

void nativeSurfaceDestroyed(JNIEnv*, jclass, jlong h) {
  // Synchronous: SurfaceHolder.Callback.surfaceDestroyed must not return
  // while we still use the surface.
  inst(h)->engine.post_sync(Command{CommandType::kSurfaceDestroyed});
}

// out: [samples, frames, strokes, dropped,
//       in→recv p50, p99, recv→submit p50, p99, in→submit p50, p99, gpu p50, p99, frame-cpu p50, p99,
//       pred-error p50 (1/100 px), p99]
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
  };
  const jsize n = std::min<jsize>(env->GetArrayLength(out), jsize(sizeof(v) / sizeof(v[0])));
  env->SetLongArrayRegion(out, 0, n, v);
}

// ---- @CriticalNative (no JNIEnv / jclass) ----

jint nativePush(jlong h, jint count) {
  Instance* i = inst(h);
  const uint32_t n = count < 0 ? 0u : (uint32_t(count) > kStagingSamples ? kStagingSamples : uint32_t(count));
  return jint(i->engine.push(i->staging, n));
}

void nativeSetColor(jlong h, jfloat r, jfloat g, jfloat b, jfloat a) {
  Command c{CommandType::kSetColor};
  c.f[0] = r; c.f[1] = g; c.f[2] = b; c.f[3] = a;
  inst(h)->engine.post(c);
}

void nativeSetWidth(jlong h, jfloat width_px) {
  Command c{CommandType::kSetWidth};
  c.f[0] = width_px;
  inst(h)->engine.post(c);
}

void nativeSetPressureCurve(jlong h, jfloat gamma, jfloat floor) {
  Command c{CommandType::kSetPressureGamma};
  c.f[0] = gamma; c.f[1] = floor;
  inst(h)->engine.post(c);
}

void nativeSetHorizon(jlong h, jfloat ms) {
  Command c{CommandType::kSetHorizon};
  c.f[0] = ms;
  inst(h)->engine.post(c);
}

void nativeSetPredictor(jlong h, jint model) {
  Command c{CommandType::kSetPredictor};
  c.i0 = model;
  inst(h)->engine.post(c);
}

void nativeSetTransform(jlong h, jfloat a, jfloat b, jfloat tx, jfloat c_, jfloat d, jfloat ty) {
  Command c{CommandType::kSetTransform};
  c.f[0] = a; c.f[1] = b; c.f[2] = tx; c.f[3] = c_; c.f[4] = d; c.f[5] = ty;
  inst(h)->engine.post(c);
}

void nativeResetStats(jlong h) { inst(h)->engine.post(Command{CommandType::kResetProbe}); }

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(FZZ)J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
    {"nativeStaging", "(J)Ljava/nio/ByteBuffer;", reinterpret_cast<void*>(nativeStaging)},
    {"nativeSurfaceChanged", "(JLandroid/view/Surface;II)V", reinterpret_cast<void*>(nativeSurfaceChanged)},
    {"nativeSurfaceDestroyed", "(J)V", reinterpret_cast<void*>(nativeSurfaceDestroyed)},
    {"nativeStats", "(J[J)V", reinterpret_cast<void*>(nativeStats)},
    {"nativePush", "(JI)I", reinterpret_cast<void*>(nativePush)},
    {"nativeSetColor", "(JFFFF)V", reinterpret_cast<void*>(nativeSetColor)},
    {"nativeSetWidth", "(JF)V", reinterpret_cast<void*>(nativeSetWidth)},
    {"nativeSetPressureCurve", "(JFF)V", reinterpret_cast<void*>(nativeSetPressureCurve)},
    {"nativeSetHorizon", "(JF)V", reinterpret_cast<void*>(nativeSetHorizon)},
    {"nativeSetPredictor", "(JI)V", reinterpret_cast<void*>(nativeSetPredictor)},
    {"nativeSetTransform", "(JFFFFFF)V", reinterpret_cast<void*>(nativeSetTransform)},
    {"nativeResetStats", "(J)V", reinterpret_cast<void*>(nativeResetStats)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = env->FindClass("app/noti/ink/NotiInk");
  if (!cls) return JNI_ERR;
  if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
