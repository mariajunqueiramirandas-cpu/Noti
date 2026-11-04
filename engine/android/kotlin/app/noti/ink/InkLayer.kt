package app.noti.ink

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.View
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 *     prediction horizon;
 *  4. runs palm rejection (native, see engine/include/noti/palm_filter.h): finger
 *     contacts go through it; deliberate touches reach [gestures] (on this thread),
 *     palms never do.
 *
 * Public methods are called from the UI thread unless they say otherwise.
 */
class InkLayer(
    private val anchor: View,
    initialHorizonMs: Float = 10f,
    gpuTiming: Boolean = false,
    flipY: Boolean = false,
    /** Latency/prediction statistics (a few hundred ns per frame). */
    probe: Boolean = true,
    /** Deliberate touches over the canvas (palms removed), delivered on the ink thread. */
    private val gestures: GestureListener? = null,
    /** A single deliberate finger draws (palm rejection still applies). */
    fingerDrawing: Boolean = false,
    /** The palm filter's learned model from a previous session (see [onPalmModel]). */
    palmModel: FloatArray? = null,
    /** Receives the learned model when the layer is released (ink thread); persist it. */
    private val onPalmModel: ((FloatArray) -> Unit)? = null,
    initialInk: ByteArray? = null,
    private val onSnapshot: ((ByteArray) -> Unit)? = null,
    private val onDocumentState: ((DoubleArray) -> Unit)? = null,
    private val onRestoreError: (() -> Unit)? = null,
) {
    private val thread = HandlerThread("noti-ink", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val ink = Handler(thread.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val handle = NotiInk.nativeCreate(initialHorizonMs, gpuTiming, flipY, probe)
    private var window: InkWindow? = null  // ink thread only
    @Volatile private var released = false
    private var fingerDrawing = fingerDrawing
    private val initialModel = palmModel?.copyOf()
    private val initialInk = initialInk
    private var persistenceEnabled = true
    private var lastRevision = -1.0
    private var lastTool = -1
    private var lastDocumentState: DoubleArray? = null
    private val documentPoll = object : Runnable {
        override fun run() {
            if (released) return
            val state = NotiInk.nativeDocumentState(handle)
            if (lastDocumentState?.contentEquals(state) != true) {
                lastDocumentState = state
                main.post { onDocumentState?.invoke(state) }
            }
            if (state[8] == 0.0 && state[0] != lastRevision) {
                lastRevision = state[0]
                if (persistenceEnabled) onSnapshot?.invoke(NotiInk.nativeSnapshot(handle))
            }
            ink.postDelayed(this, 350)
        }
    }

    // Latest page placement, applied on the ink thread at the next vsync (so a
    // pinch updates the engine at most once per frame, however fast it reports).
    private class PageRequest(val page: Int, val x: Float, val y: Float, val width: Float)
    private val pendingPage = AtomicReference<PageRequest?>(null)

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
        if (released) return
        pinHostDisplayMode()
        val token = anchor.windowToken
        val bounds = anchorRect()
        val context = anchor.context
        anchor.addOnLayoutChangeListener(layoutListener)
        ink.post {
            check(NotiInk.nativeAttach(handle)) { "noti ink engine failed to initialise EGL" }
            initialInk?.let {
                if (!NotiInk.nativeRestore(handle, it)) {
                    persistenceEnabled = false
                    main.post { onRestoreError?.invoke() }
                }
            }
            ink.post(documentPoll)
            initialModel?.let { NotiInk.nativeSetPalmModel(handle, it) }
            NotiInk.nativeSetFingerInk(handle, fingerDrawing)
            window = InkWindow(context, handle, ink, gestures, ::flushPage).also { it.add(token, bounds) }
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

    // ---- palm rejection ----

    /** A single deliberate finger draws (on) or only gestures (off). */
    fun setFingerDrawing(on: Boolean) {
        if (on == fingerDrawing) return
        fingerDrawing = on
        ink.post {
            NotiInk.nativeSetFingerInk(handle, on)
            window?.drainGestures()
        }
    }

    /**
     * Palm lab: records every pen and touch sample with [label] ("palm" when only the
     * writing hand touches, "touch" when every touch is deliberate) for palm_bench.
     */
    fun setPalmRecording(on: Boolean, label: String) = ink.post { NotiInk.nativeSetPalmRecording(handle, on, label) }

    /** Writes the palm recording as CSV; [onDone] gets the rows written (-1 on error) on the main thread. */
    fun exportPalmRecording(path: String, onDone: (Long) -> Unit) {
        ink.post {
            val n = if (released) -1L else NotiInk.nativeExportPalmRecording(handle, path)
            main.post { onDone(n) }
        }
    }

    /** Palm filter counters (see jni_bridge.cpp nativePalmStats), delivered on the main thread. */
    fun palmStats(onResult: (LongArray) -> Unit) {
        ink.post {
            if (released) return@post
            val out = LongArray(9)
            NotiInk.nativePalmStats(handle, out)
            main.post { onResult(out) }
        }
    }

    fun setTool(tool: Int) {
        if (lastTool == tool) return
        lastTool = tool
        ink.post { if (!released) NotiInk.nativeSetTool(handle, tool) }
    }

    fun documentAction(action: Int, page: Int = 0, onDone: ((Boolean) -> Unit)? = null) {
        ink.post {
            if (released) return@post
            val ok = NotiInk.nativeDocumentAction(handle, action, page)
            if (ok && persistenceEnabled) onSnapshot?.invoke(NotiInk.nativeSnapshot(handle))
            main.post { onDone?.invoke(ok) }
        }
    }

    fun snapshot(onDone: (ByteArray) -> Unit) {
        ink.post {
            if (released) return@post
            NotiInk.nativeDocumentAction(handle, 5, 0)
            val bytes = NotiInk.nativeSnapshot(handle)
            main.post { onDone(bytes) }
        }
    }

    fun saveNow() {
        ink.post {
            if (!released && persistenceEnabled) {
                NotiInk.nativeDocumentAction(handle, 5, 0)
                onSnapshot?.invoke(NotiInk.nativeSnapshot(handle))
            }
        }
    }

    // ---- pages (any thread) ----
    fun setPages(placements: FloatArray) { pendingPages.set(placements) }
    private val pendingPages = AtomicReference<FloatArray?>(null)


    /**
     * The page on screen and where it is: [x], [y] its top-left corner and [width] its width,
     * in canvas pixels. Ink is kept per page, in page coordinates, so it follows zoom and pan.
     * Applied at the next frame; only the latest request counts.
     */
    fun setPage(page: Int, x: Float, y: Float, width: Float) {
        pendingPage.set(PageRequest(page, x, y, width))
    }

    fun scheduleInk(task: Runnable, delayMs: Long) { ink.postDelayed(task, delayMs) }
    fun cancelInk(task: Runnable) { ink.removeCallbacks(task) }

    /** Runs [block] on the ink thread (where gestures arrive). */
    fun runOnInkThread(block: () -> Unit) {
        ink.post(block)
    }

    private fun flushPage() {
        pendingPages.getAndSet(null)?.let { NotiInk.nativeSetPages(handle, it) }
        val r = pendingPage.getAndSet(null) ?: return
        NotiInk.nativeSetPage(handle, r.page, r.x, r.y, r.width)
    }

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
        ink.removeCallbacks(documentPoll)
        ink.post {
            NotiInk.nativeIdle(handle)
            NotiInk.nativeDocumentAction(handle, 5, 0)
            if (persistenceEnabled) onSnapshot?.invoke(NotiInk.nativeSnapshot(handle))
            onPalmModel?.let { save ->
                val model = FloatArray(NotiInk.PALM_MODEL_FLOATS)
                NotiInk.nativePalmModel(handle, model)
                save(model)
            }
            window?.remove()
            window = null
            NotiInk.nativeDetach(handle)
            NotiInk.nativeDestroy(handle)
            thread.quitSafely()
        }
    }
}
