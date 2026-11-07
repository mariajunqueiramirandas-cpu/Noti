package app.noti.ink

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.hardware.input.InputManager
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
    stylusOptions: StylusButtonOptions = StylusButtonOptions(),
    private val onButtonClick: ((PenButton) -> Unit)? = null,
    private val onButtonDiagnostic: ((String) -> Unit)? = null,
) {
    private val thread = HandlerThread("noti-ink", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val ink = Handler(thread.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val handle = NotiInk.nativeCreate(initialHorizonMs, gpuTiming, flipY, probe)
    private var window: InkWindow? = null  // ink thread only
    @Volatile private var released = false
    @Volatile private var stylusOptions = stylusOptions
    @Volatile private var buttonGeneration = 0
    private var buttonEventGeneration = 0 // ink thread: generation of the key being processed
    private val buttons = StylusButtons(
        ViewConfiguration.getLongPressTimeout().toLong(),
        onClick = { button ->
            val generation = buttonEventGeneration
            main.post { if (!released && stylusOptions.enabled && generation == buttonGeneration) onButtonClick?.invoke(button) }
        },
        onDiagnostic = onButtonDiagnostic?.let { report -> { line -> main.post { if (!released) report(line) } } },
    ).apply { options = stylusOptions }
    private val inputManager = anchor.context.getSystemService(InputManager::class.java)
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) = onInputDeviceRemoved(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) {
            ink.post { if (!released) buttons.removeDevice(deviceId) }
        }
    }
    private val keyRegistration = (anchor.context.findActivity() as? StylusKeyHost)?.stylusKeys?.register(
        key = ::dispatchStylusKey,
        reset = ::resetButtons,
    )
    private var fingerDrawing = fingerDrawing
    private val dial = PenDialRelay(main, anchor) { released }
    // A deliberate finger touch also closes a locked dial (palms never reach gestures).
    private val windowGestures = object : GestureListener {
        override fun onGesture(action: Int, id: Int, x: Float, y: Float, timeNanos: Long) {
            if (action == NotiInk.GESTURE_DOWN && dial.locked) dial.report(PenDialRelay.DISMISS, x, y)
            gestures?.onGesture(action, id, x, y, timeNanos)
        }
        override fun onPenDown() { gestures?.onPenDown() }
        override fun onGestureBatchEnd() { gestures?.onGestureBatchEnd() }
    }
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

    private val boundsObserver = InkAnchorBounds(anchor) { bounds ->
        ink.post { if (!released) window?.updateBounds(bounds) }
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            attach()
        }
        override fun onViewDetachedFromWindow(v: View) {}
    }

    init {
        inputManager.registerInputDeviceListener(deviceListener, main)
        if (anchor.isAttachedToWindow) attach() else anchor.addOnAttachStateChangeListener(attachListener)
    }

    private fun attach() {
        if (released) return
        pinHostDisplayMode()
        val token = anchor.windowToken
        val bounds = boundsObserver.current()
        val context = anchor.context
        boundsObserver.start()
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
            window = InkWindow(context, handle, ink, windowGestures, ::flushPage, buttons, onIdle = ::requestHostFrame, dial = dial)
                .also { it.add(token, bounds) }
        }
        anchor.viewTreeObserver.addOnPreDrawListener(preDraw)
        observer = anchor.viewTreeObserver
    }

    // ---- host-frame sync (Android 14+) ----
    //
    // The document under the ink (PDF, paper) is drawn by the host window, the ink by
    // this layer's own SurfaceFlinger layers. Updated separately, they reach the screen
    // on different frames, so the ink trails or leads the page while it moves. Instead,
    // just before the host draws a frame, the engine adds its dry layer's newest buffer
    // and the exact scale/offset from that buffer's layout to the layout being drawn to
    // a transaction that SurfaceFlinger applies together with that frame. Pen ink (the
    // front-buffer wet layer) stays on the engine's own low-latency path.

    private var hostSource: (() -> FloatArray?)? = null  // main thread
    private var observer: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { syncHostFrame(); true }

    /**
     * [source] returns the visible pages (as for [setPages]) of the frame the host is about
     * to draw, or null. Main thread. Without a source (or before Android 14) the ink layer
     * follows the viewport on its own.
     */
    fun setHostFrameSource(source: (() -> FloatArray?)?) {
        if (released) return
        hostSource = source
        NotiInk.nativeSetHostSync(handle, source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }

    private fun syncHostFrame() {
        if (released || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val source = hostSource ?: return
        val root = anchor.rootSurfaceControl ?: return
        val pages = source() ?: return
        val t = SurfaceControl.Transaction()
        if (NotiInk.nativeHostFrame(handle, t, pages)) root.applyTransactionOnDraw(t) else t.close()
    }

    /** Any thread: makes sure the host draws a frame soon if a redraw is waiting for one. */
    private fun requestHostFrame() {
        if (!released && NotiInk.nativeHostFrameWanted(handle)) anchor.postInvalidateOnAnimation()
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
        applyFingerInk()
    }

    private fun applyFingerInk() {
        // A finger that closes a locked dial must not also draw.
        val on = fingerDrawing && !dial.locked
        ink.post {
            if (released) return@post
            NotiInk.nativeSetFingerInk(handle, on)
            window?.drainGestures()
        }
    }

    // ---- tool dial ----

    /**
     * [listener] receives pen streams started with the writing button held (with
     * [StylusButtonOptions.hold] = [PenHold.Dial]) instead of the engine drawing them:
     * PenDialRelay.DOWN / MOVE / UP / CANCEL, and DISMISS for a touch outside a locked
     * dial. Main thread, window coordinates in pixels. Null turns the dial gesture off.
     */
    fun setPenDial(listener: ((action: Int, x: Float, y: Float) -> Unit)?) {
        dial.listener = listener
        if (listener == null) setDialLocked(false)
    }

    /** While locked, the next pen or deliberate finger touch on the canvas closes the dial; it never draws. */
    fun setDialLocked(locked: Boolean) {
        if (dial.locked == locked) return
        dial.locked = locked
        applyFingerInk()
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

    fun setStylusOptions(options: StylusButtonOptions) {
        if (stylusOptions == options) return
        buttonGeneration++
        stylusOptions = options
        ink.post { if (!released) buttons.options = options }
    }

    private fun dispatchStylusKey(event: KeyEvent): Boolean {
        if (released || (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)) return false
        val button = StylusButtonKeys.button(event.keyCode, event.source, event.device?.name, stylusOptions) ?: return false
        // KeyEvent instances belong to Android; never retain them across the thread hand-off.
        val device = event.deviceId
        val action = event.action
        val time = event.eventTime
        val downTime = event.downTime
        val repeat = event.repeatCount
        val canceled = event.isCanceled
        val code = event.keyCode
        val generation = buttonGeneration
        ink.post {
            if (!released && generation == buttonGeneration) {
                buttonEventGeneration = generation
                onButtonDiagnostic?.let { report -> main.post { if (!released) report("Key $code · ${button.label} · device $device") } }
                buttons.key(button, device, action, time, repeat, canceled, downTime)
            }
        }
        return true
    }

    private fun resetButtons() {
        buttonGeneration++
        ink.post { if (!released) buttons.reset() }
    }

    fun documentAction(action: Int, page: Int = 0, onDone: ((Boolean) -> Unit)? = null) {
        ink.post {
            if (released) return@post
            val ok = NotiInk.nativeDocumentAction(handle, action, page)
            if (ok && persistenceEnabled) onSnapshot?.invoke(NotiInk.nativeSnapshot(handle))
            main.post { onDone?.invoke(ok) }
        }
    }

    /** The Type tool's last loop or tap (see [NotiInk.nativeTypeLoop]), delivered on the main thread. */
    fun typeLoop(onResult: (FloatArray) -> Unit) {
        ink.post {
            if (released) return@post
            val data = NotiInk.nativeTypeLoop(handle)
            main.post { if (!released) onResult(data) }
        }
    }

    /** Every visible stroke of [page] (see [NotiInk.nativePageInk]), delivered on the main thread. */
    fun pageInk(page: Int, onResult: (FloatArray) -> Unit) {
        ink.post {
            if (released) return@post
            val data = NotiInk.nativePageInk(handle, page)
            main.post { if (!released) onResult(data) }
        }
    }

    /** The selected strokes (see [NotiInk.nativeSelectionInk]), delivered on the main thread. */
    fun selectionInk(onResult: (FloatArray) -> Unit) {
        ink.post {
            if (released) return@post
            val data = NotiInk.nativeSelectionInk(handle)
            main.post { if (!released) onResult(data) }
        }
    }

    /**
     * Swaps strokes [ids] of [page] for new ones: [capsules] (x0 y0 r0 x1 y1 r1 in page units)
     * cut into strokes by [sizes], all in [color] (premultiplied RGBA). One undo step; saved at once.
     */
    fun replaceStrokes(ids: IntArray, page: Int, color: FloatArray, capsules: FloatArray, sizes: IntArray,
        onDone: (Boolean) -> Unit) {
        ink.post {
            if (released) return@post
            val ok = NotiInk.nativeReplaceStrokes(handle, ids, page, color, capsules, sizes)
            if (ok && persistenceEnabled) onSnapshot?.invoke(NotiInk.nativeSnapshot(handle))
            main.post { onDone(ok) }
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
        pendingPages.getAndSet(null)?.let {
            NotiInk.nativeSetPages(handle, it)
            requestHostFrame()
        }
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
        keyRegistration?.close()
        inputManager.unregisterInputDeviceListener(deviceListener)
        anchor.removeOnAttachStateChangeListener(attachListener)
        boundsObserver.stop()
        hostSource = null
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        anchor.viewTreeObserver.removeOnPreDrawListener(preDraw)
        observer = null
        ink.removeCallbacks(documentPoll)
        ink.post {
            buttons.reset()
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
