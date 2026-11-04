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

    /** Layout of one finger/palm contact in the touch staging buffer (must match noti::TouchSample). */
    const val TOUCH_BYTES = 40
    const val TOUCH_STAGING = 512

    // noti::TouchAction
    const val TOUCH_DOWN: Byte = 0
    const val TOUCH_MOVE: Byte = 1
    const val TOUCH_UP: Byte = 2
    const val TOUCH_CANCEL: Byte = 3

    // noti::TouchFlags
    const val TOUCH_FLAG_CANCELED = 1
    const val TOUCH_FLAG_HISTORICAL = 2

    /** Gesture output buffer: int32 count, int32 reserved, int64 deadline, then 24-byte events. */
    const val GESTURE_HEADER_BYTES = 16
    const val GESTURE_BYTES = 24

    // noti::GestureAction
    const val GESTURE_DOWN = 0
    const val GESTURE_MOVE = 1
    const val GESTURE_UP = 2
    const val GESTURE_CANCEL = 3

    /** Floats in the palm filter's learned model (noti::PalmFilter::kModelFloats). */
    const val PALM_MODEL_FLOATS = 20

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

    // ---- palm rejection + pages (ink thread) ----
    @JvmStatic external fun nativeTouchStaging(handle: Long): ByteBuffer
    @JvmStatic external fun nativeGestureOut(handle: Long): ByteBuffer
    @JvmStatic external fun nativeSetPalmView(handle: Long, width: Float, height: Float, pxPerMm: Float)
    @JvmStatic external fun nativeSetFingerInk(handle: Long, on: Boolean)
    @JvmStatic external fun nativeSetPalmModel(handle: Long, model: FloatArray): Boolean
    @JvmStatic external fun nativePalmModel(handle: Long, out: FloatArray)
    @JvmStatic external fun nativePalmStats(handle: Long, out: LongArray)
    @JvmStatic external fun nativeSetPalmRecording(handle: Long, on: Boolean, label: String?)
    @JvmStatic external fun nativeExportPalmRecording(handle: Long, path: String): Long
    @JvmStatic external fun nativeSetPage(handle: Long, page: Int, x: Float, y: Float, width: Float)

    @JvmStatic external fun nativeSetTool(handle: Long, tool: Int)
    @JvmStatic external fun nativeSetPages(handle: Long, pages: FloatArray)
    @JvmStatic external fun nativeSnapshot(handle: Long): ByteArray
    @JvmStatic external fun nativeRestore(handle: Long, data: ByteArray): Boolean
    @JvmStatic external fun nativeDocumentAction(handle: Long, action: Int, page: Int): Boolean
    @JvmStatic external fun nativeDocumentState(handle: Long): DoubleArray

    /** Runs the palm filter on `count` staged contacts (and any finger ink); returns the next deadline (ns) or -1. */
    @CriticalNative @JvmStatic external fun nativeTouch(handle: Long, count: Int): Long

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
