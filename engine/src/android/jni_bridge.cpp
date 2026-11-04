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
//
// Palm rejection (noti/palm_filter.h) also lives here, on the ink thread:
//   pen samples   → filter.observe_pen → (finger-ink cancel) → engine
//   finger/palm   → nativeTouch: TouchSample records in a second staging
//                   buffer → filter → finger ink → engine; deliberate touches
//                   → gesture events in an output buffer Kotlin reads after
//                   every call (header: int32 count, int64 next deadline)
//   timers        → nativeIdle ticks the filter too
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <new>

#include "gl_renderer.h"
#include "noti/clock.h"
#include "noti/engine.h"
#include "noti/palm_filter.h"

namespace {

using noti::Command;
using noti::CommandType;

constexpr uint32_t kStagingSamples = 512;  // > max historical samples in one MotionEvent
constexpr uint32_t kTouchStaging = 512;

// Gesture output read by Kotlin (InkWindow.drainGestures).
struct GestureOut {
  int32_t count;
  int32_t reserved;
  int64_t deadline_ns;
  noti::GestureEvent events[noti::PalmFilter::kMaxGestures];
};
static_assert(offsetof(GestureOut, events) == 16, "GestureOut layout is part of the JNI contract");

struct PalmCounters {
  uint64_t palms = 0, touches = 0, strokes = 0, flashes = 0, system = 0;
  double latency_sum_ms = 0;
};

struct Instance {
  noti::android::GlFrontBufferRenderer renderer;
  noti::InkEngine engine;
  noti::PalmFilter palm;
  PalmCounters counters;
  alignas(64) noti::InkSample staging[kStagingSamples];
  alignas(64) noti::TouchSample touch_staging[kTouchStaging];
  alignas(64) GestureOut out;

  Instance(const noti::EngineConfig& cfg, const noti::android::GlRendererOptions& opt)
      : renderer(opt), engine(cfg, renderer) {
    out.count = 0;
    out.deadline_ns = -1;
  }
};

inline int64_t min_deadline(int64_t a, int64_t b) {
  if (a < 0) return b;
  if (b < 0) return a;
  return a < b ? a : b;
}

// After the filter ran: finger ink to the engine, gestures to Kotlin, reports
// to the counters. Returns the engine's deadline if it ran, else -1.
int64_t flush_palm(Instance* i) {
  int64_t d = -1;
  noti::PalmFilter& f = i->palm;
  if (f.ink_count()) d = i->engine.process(f.ink(), f.ink_count());
  const uint32_t n = f.gesture_count();
  for (uint32_t k = 0; k < n && i->out.count < int32_t(noti::PalmFilter::kMaxGestures); ++k)
    i->out.events[i->out.count++] = f.gestures()[k];
  f.clear_outputs();
  noti::ContactReport r[8];
  for (uint32_t m; (m = f.drain_reports(r, 8)) > 0;) {
    for (uint32_t k = 0; k < m; ++k) {
      PalmCounters& c = i->counters;
      if (r[k].verdict == noti::Verdict::kPalm) {
        ++c.palms;
        c.system += r[k].system_palm;
        c.flashes += r[k].ink_started && !r[k].ink_committed;
      } else if (r[k].ink_committed) {
        ++c.strokes;
      } else if (r[k].gesture && !r[k].gesture_cancelled) {
        ++c.touches;
        c.latency_sum_ms += double(r[k].t_decided_ns - r[k].t_down_ns) * 1e-6;
      }
    }
  }
  return d;
}

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

jobject nativeTouchStaging(JNIEnv* env, jclass, jlong h) {
  return env->NewDirectByteBuffer(inst(h)->touch_staging, jlong(sizeof(inst(h)->touch_staging)));
}

jobject nativeGestureOut(JNIEnv* env, jclass, jlong h) {
  return env->NewDirectByteBuffer(&inst(h)->out, jlong(sizeof(inst(h)->out)));
}

// ---- palm rejection: ink thread (regular JNI, off the per-sample path) ----

void nativeSetPalmView(JNIEnv*, jclass, jlong h, jfloat w, jfloat hh, jfloat px_per_mm) {
  inst(h)->palm.set_view(w, hh, px_per_mm);
}

void nativeSetFingerInk(JNIEnv*, jclass, jlong h, jboolean on) {
  Instance* i = inst(h);
  i->out.count = 0;
  i->palm.set_finger_ink(on);
  flush_palm(i);
}

jboolean nativeSetPalmModel(JNIEnv* env, jclass, jlong h, jfloatArray in) {
  if (env->GetArrayLength(in) < jsize(noti::PalmFilter::kModelFloats)) return JNI_FALSE;
  float m[noti::PalmFilter::kModelFloats];
  env->GetFloatArrayRegion(in, 0, noti::PalmFilter::kModelFloats, m);
  return inst(h)->palm.import_model(m) ? JNI_TRUE : JNI_FALSE;
}

void nativePalmModel(JNIEnv* env, jclass, jlong h, jfloatArray out) {
  float m[noti::PalmFilter::kModelFloats];
  inst(h)->palm.export_model(m);
  const jsize n = std::min<jsize>(env->GetArrayLength(out), jsize(noti::PalmFilter::kModelFloats));
  env->SetFloatArrayRegion(out, 0, n, m);
}

// out: [palms, touches, finger strokes kept, flashes (tentative ink rolled back),
//       system palms, mean touch confirmation (µs), right-handedness ×1000,
//       grace window (ms), finger/palm size boundary (µm)]
void nativePalmStats(JNIEnv* env, jclass, jlong h, jlongArray out) {
  const Instance* i = inst(h);
  const PalmCounters& c = i->counters;
  const jlong v[] = {
      jlong(c.palms), jlong(c.touches), jlong(c.strokes), jlong(c.flashes), jlong(c.system),
      jlong(c.touches ? c.latency_sum_ms * 1000.0 / double(c.touches) : 0.0),
      jlong(i->palm.right_handedness() * 1000.0f), jlong(i->palm.grace_ms()),
      jlong(i->palm.size_boundary_mm() * 1000.0f),
  };
  const jsize n = std::min<jsize>(env->GetArrayLength(out), jsize(sizeof(v) / sizeof(v[0])));
  env->SetLongArrayRegion(out, 0, n, v);
}

void nativeSetPalmRecording(JNIEnv* env, jclass, jlong h, jboolean on, jstring label) {
  const char* l = label ? env->GetStringUTFChars(label, nullptr) : nullptr;
  inst(h)->palm.set_recording(on, l ? l : "?");
  if (l) env->ReleaseStringUTFChars(label, l);
}

jlong nativeExportPalmRecording(JNIEnv* env, jclass, jlong h, jstring path) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  const jlong n = inst(h)->palm.export_recording(p);
  env->ReleaseStringUTFChars(path, p);
  return n;
}

// The visible page or its placement changed (ink thread, at most once per frame).
void nativeSetPage(JNIEnv*, jclass, jlong h, jint page, jfloat x, jfloat y, jfloat width) {
  Command c{CommandType::kSetPage};
  c.i0 = page;
  c.f[0] = x;
  c.f[1] = y;
  c.f[2] = width;
  inst(h)->engine.execute(c);
}

// Document operations are dispatched by InkLayer onto the ink thread.
void nativeSetTool(JNIEnv*, jclass, jlong h, jint tool) { inst(h)->engine.set_tool(tool); }
void nativeSetPages(JNIEnv* env, jclass, jlong h, jfloatArray input) {
  const jsize n = env->GetArrayLength(input);
  if (n % 5 != 0) return;
  std::vector<jfloat> values(n);
  env->GetFloatArrayRegion(input, 0, n, values.data());
  std::vector<noti::InkEngine::VisiblePage> pages;
  for (int k = 0; k < n; k += 5) pages.push_back({uint32_t(values[k]), values[k+1], values[k+2], values[k+3], values[k+4]});
  inst(h)->engine.set_pages(pages);
}
jbyteArray nativeSnapshot(JNIEnv* env, jclass, jlong h) {
  const auto data = inst(h)->engine.snapshot();
  auto out = env->NewByteArray(jsize(data.size()));
  if (out) env->SetByteArrayRegion(out, 0, jsize(data.size()), reinterpret_cast<const jbyte*>(data.data()));
  return out;
}
jboolean nativeRestore(JNIEnv* env, jclass, jlong h, jbyteArray input) {
  const jsize n = env->GetArrayLength(input);
  std::vector<uint8_t> data(n);
  env->GetByteArrayRegion(input, 0, n, reinterpret_cast<jbyte*>(data.data()));
  return inst(h)->engine.restore(data.data(), data.size());
}
jboolean nativeDocumentAction(JNIEnv*, jclass, jlong h, jint action, jint page) {
  auto& e = inst(h)->engine;
  if (action == 0) e.undo();
  else if (action == 1) e.redo();
  else if (action == 5) e.finish_input();
  else return e.edit_page(action - 2, uint32_t(page));
  return JNI_TRUE;
}
jdoubleArray nativeDocumentState(JNIEnv* env, jclass, jlong h) {
  const auto& e = inst(h)->engine;
  const auto b = e.selection_bounds();
  const jdouble values[] = {double(e.revision()), double(e.can_undo()), double(e.can_redo()), double(e.selection_count()),
    b.empty() ? 0.0 : b.x0, b.empty() ? 0.0 : b.y0, b.empty() ? 0.0 : b.x1, b.empty() ? 0.0 : b.y1, double(e.document_busy())};
  auto out = env->NewDoubleArray(9); env->SetDoubleArrayRegion(out, 0, 9, values); return out;
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
  i->out.count = 0;
  // The pen is never filtered; it informs the filter first, which may roll back
  // a finger stroke (that cancel must reach the engine before the pen does).
  i->palm.observe_pen(i->staging, n);
  if (NOTI_UNLIKELY(i->palm.ink_count() || i->palm.gesture_count())) flush_palm(i);
  const int64_t d = i->engine.process(i->staging, n);
  i->out.deadline_ns = i->palm.next_deadline(noti::now_ns());
  return min_deadline(d, i->out.deadline_ns);
}

// Finger / palm contacts staged in touch_staging.
jlong nativeTouch(jlong h, jint count) {
  Instance* i = inst(h);
  const uint32_t n = count < 0 ? 0u : std::min(uint32_t(count), kTouchStaging);
  i->out.count = 0;
  i->palm.process(i->touch_staging, n);
  const int64_t d = flush_palm(i);
  i->out.deadline_ns = i->palm.next_deadline(noti::now_ns());
  return min_deadline(d, i->out.deadline_ns);
}

jlong nativeIdle(jlong h) {
  Instance* i = inst(h);
  i->out.count = 0;
  i->palm.tick(noti::now_ns());
  flush_palm(i);
  i->out.deadline_ns = i->palm.next_deadline(noti::now_ns());
  return min_deadline(i->engine.idle(), i->out.deadline_ns);
}

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
    M(nativeTouchStaging, "(J)Ljava/nio/ByteBuffer;"),
    M(nativeGestureOut, "(J)Ljava/nio/ByteBuffer;"),
    M(nativeSetPalmView, "(JFFF)V"),
    M(nativeSetFingerInk, "(JZ)V"),
    M(nativeSetPalmModel, "(J[F)Z"),
    M(nativePalmModel, "(J[F)V"),
    M(nativePalmStats, "(J[J)V"),
    M(nativeSetPalmRecording, "(JZLjava/lang/String;)V"),
    M(nativeExportPalmRecording, "(JLjava/lang/String;)J"),
    M(nativeSetPage, "(JIFFF)V"),
    M(nativeSetTool, "(JI)V"),
    M(nativeSetPages, "(J[F)V"),
    M(nativeSnapshot, "(J)[B"),
    M(nativeRestore, "(J[B)Z"),
    M(nativeDocumentAction, "(JII)Z"),
    M(nativeDocumentState, "(J)[D"),
    M(nativeTouch, "(JI)J"),
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
