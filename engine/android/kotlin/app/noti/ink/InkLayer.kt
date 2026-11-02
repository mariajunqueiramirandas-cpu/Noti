package app.noti.ink

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Low-latency ink over [anchor] (the canvas view).
 *
 * Owns a dedicated "noti-ink" thread (THREAD_PRIORITY_URGENT_DISPLAY, plus an
 * ADPF session from native code) which
 *  1. owns a transparent input window over the anchor. A window added from a
 *     Looper thread has its ViewRootImpl, and therefore its input dispatch, on
 *     that thread, so pen MotionEvents never touch the UI thread: Compose
 *     recomposition, layout or GC on the UI thread cannot delay ink;
 *  2. is the native engine's render thread (inline mode): each MotionEvent is
 *     predicted and drawn before onTouchEvent returns. No thread hand-off;
 *  3. feeds display vsync timing (its own Choreographer) for the scan-out-aware
 *     prediction horizon.
 *
 * All public methods are called from the UI thread.
 */
class InkLayer(
    private val anchor: View,
    initialHorizonMs: Float = 10f,
    gpuTiming: Boolean = false,
    flipY: Boolean = false,
    /** Latency/prediction statistics (a few hundred ns per frame). */
    probe: Boolean = true,
    /** Finger events over the canvas arrive here, on the ink thread (pan/zoom). Return true if consumed. */
    var onFingerEvent: ((MotionEvent) -> Boolean)? = null,
) {
    private val thread = HandlerThread("noti-ink", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val ink = Handler(thread.looper)
    private val handle = NotiInk.nativeCreate(initialHorizonMs, gpuTiming, flipY, probe)
    private var window: InkWindow? = null  // ink thread only
    private var released = false

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val r = anchorRect()
        ink.post { window?.updateBounds(r) }
    }

    init {
        if (anchor.isAttachedToWindow) attach() else anchor.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    attach()
                }
                override fun onViewDetachedFromWindow(v: View) {}
            },
        )
    }

    private fun attach() {
        pinHostDisplayMode()
        val token = anchor.windowToken
        val bounds = anchorRect()
        val context = anchor.context
        anchor.addOnLayoutChangeListener(layoutListener)
        ink.post {
            check(NotiInk.nativeAttach(handle)) { "noti ink engine failed to initialise EGL" }
            window = InkWindow(context, handle, ink) { e -> onFingerEvent?.invoke(e) ?: false }
                .also { it.add(token, bounds) }
        }
    }

    /**
     * Pins the host window to the display's highest refresh rate too, and asks for
     * minimal display post-processing. Power is not a constraint: the panel
     * stays at max refresh for as long as the canvas is on screen.
     */
    private fun pinHostDisplayMode() {
        val activity = anchor.context.findActivity() ?: return
        val window = activity.window
        val display = anchor.display ?: return
        val cur = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
        window.setPreferMinimalPostProcessing(true)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun anchorRect(): Rect {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height)
    }

    // ---- brush & predictor (UI thread) ----

    fun setColor(r: Float, g: Float, b: Float, a: Float = 1f) = NotiInk.nativeSetColor(handle, r, g, b, a)
    fun setWidth(px: Float) = NotiInk.nativeSetWidth(handle, px)
    fun setPressureCurve(gamma: Float, floor: Float) = NotiInk.nativeSetPressureCurve(handle, gamma, floor)
    /** Horizon used until vsync timing is known. */
    fun setFallbackHorizonMs(ms: Float) = NotiInk.nativeSetHorizon(handle, ms)
    /** 0 none, 1 linear, 2 polynomial, 3 Kalman (default). */
    fun setPredictor(model: Int) = NotiInk.nativeSetPredictor(handle, model)

    /**
     * Display timing for the scan-out-aware horizon. [panelMs] is the feel-calibration
     * slider (plan §5.4): raise until ink sticks to the nib, stop before it overshoots.
     * [scanAxis]: 0 unknown (uses the average scan wait), 1 rows (y), 2 columns (x).
     */
    fun setDisplayTiming(panelMs: Float, gpuMs: Float = 0.35f, scanAxis: Int = 0, reversed: Boolean = false) =
        NotiInk.nativeSetDisplay(handle, panelMs, gpuMs, scanAxis, reversed)

    // ---- debug / tuning ----

    /** See jni_bridge.cpp nativeStats for the layout. */
    fun latencyStats(): LongArray = LongArray(18).also { NotiInk.nativeStats(handle, it) }
    fun resetLatencyStats() = NotiInk.nativeResetStats(handle)

    /** Record raw strokes for `predictor_bench --csv` (allocates ~6 MB on first use). */
    fun setRecording(on: Boolean) = NotiInk.nativeSetRecording(handle, on)

    /** Writes recorded strokes as CSV; blocks the caller until done. Returns samples written. */
    fun exportRecording(path: String): Long {
        var n = -1L
        val done = CountDownLatch(1)
        ink.post {
            n = NotiInk.nativeExportRecording(handle, path)
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return n
    }

    fun release() {
        if (released) return
        released = true
        anchor.removeOnLayoutChangeListener(layoutListener)
        ink.post {
            window?.remove()
            window = null
            NotiInk.nativeDetach(handle)
            NotiInk.nativeDestroy(handle)
            thread.quitSafely()
        }
    }
}
