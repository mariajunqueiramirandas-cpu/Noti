package app.noti.ink

import android.view.Surface
import dalvik.annotation.optimization.CriticalNative
import java.nio.ByteBuffer

/**
 * JNI surface of libnoti_ink.so (engine/src/android/jni_bridge.cpp).
 * Bound with RegisterNatives in JNI_OnLoad; @CriticalNative methods take only
 * primitives and skip JNIEnv entirely (compileSdk >= 34 for the annotation).
 *
 * Threading: everything except the setters runs on the ink thread (see [InkLayer]).
 * Setters are called from the UI thread only (single producer of the command ring).
 */
internal object NotiInk {
    init {
        System.loadLibrary("noti_ink")
    }

    /** Layout of one sample in the staging buffer (must match noti::InkSample). */
    const val SAMPLE_BYTES = 40
    const val STAGING_SAMPLES = 512

    // noti::Action
    const val ACTION_DOWN: Short = 0
    const val ACTION_MOVE: Short = 1
    const val ACTION_UP: Short = 2
    const val ACTION_CANCEL: Short = 3
    const val ACTION_HOVER_ENTER: Short = 4
    const val ACTION_HOVER_MOVE: Short = 5
    const val ACTION_HOVER_EXIT: Short = 6

    // noti::SampleFlags
    const val FLAG_HISTORICAL = 1
    const val FLAG_ERASER = 2
    const val FLAG_BUTTON_PRIMARY = 4
    const val FLAG_BUTTON_SECONDARY = 8

    // ---- lifecycle / ink thread ----
    @JvmStatic external fun nativeCreate(horizonMs: Float, gpuTiming: Boolean, flipY: Boolean, probe: Boolean): Long
    @JvmStatic external fun nativeAttach(handle: Long): Boolean
    @JvmStatic external fun nativeDetach(handle: Long)
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeStaging(handle: Long): ByteBuffer
    @JvmStatic external fun nativeSurfaceChanged(handle: Long, surface: Surface?, width: Int, height: Int)
    @JvmStatic external fun nativeSurfaceDestroyed(handle: Long)
    @JvmStatic external fun nativeExportRecording(handle: Long, path: String): Long
    @JvmStatic external fun nativeStats(handle: Long, out: LongArray)

    /** Predicts and draws `count` staged samples; returns the next housekeeping deadline (ns) or -1. */
    @CriticalNative @JvmStatic external fun nativeProcess(handle: Long, count: Int): Long
    @CriticalNative @JvmStatic external fun nativeIdle(handle: Long): Long
    @CriticalNative @JvmStatic external fun nativeVsync(handle: Long, presentNanos: Long, periodNanos: Long)

    // ---- UI thread setters ----
    @CriticalNative @JvmStatic external fun nativeSetColor(handle: Long, r: Float, g: Float, b: Float, a: Float)
    @CriticalNative @JvmStatic external fun nativeSetWidth(handle: Long, widthPx: Float)
    @CriticalNative @JvmStatic external fun nativeSetPressureCurve(handle: Long, gamma: Float, floor: Float)
    @CriticalNative @JvmStatic external fun nativeSetHorizon(handle: Long, ms: Float)
    @CriticalNative @JvmStatic external fun nativeSetPredictor(handle: Long, model: Int)
    @CriticalNative @JvmStatic external fun nativeSetTransform(
        handle: Long, a: Float, b: Float, tx: Float, c: Float, d: Float, ty: Float,
    )
    @CriticalNative @JvmStatic external fun nativeSetDisplay(
        handle: Long, panelMs: Float, gpuMs: Float, scanAxis: Int, reversed: Boolean,
    )
    @CriticalNative @JvmStatic external fun nativeSetRecording(handle: Long, on: Boolean)
    @CriticalNative @JvmStatic external fun nativeResetStats(handle: Long)
}
