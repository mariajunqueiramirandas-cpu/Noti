package app.noti.ink

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deliberate touches only: palms already removed by the native palm filter. Called on the ink thread. */
interface GestureListener {
    /** [action]: NotiInk.GESTURE_DOWN / MOVE / UP / CANCEL. [id] is stable for the life of the contact. */
    fun onGesture(action: Int, id: Int, x: Float, y: Float, timeNanos: Long)

    /** Stop scrolling before the nib touches the page. */
    fun onPenDown() {}

    /** End of one batch of events (everything one input event produced). */
    fun onGestureBatchEnd() {}
}

/**
 * The ink thread's input window: a transparent TYPE_APPLICATION_PANEL over the
 * canvas whose root is a SurfaceView. The native wet/dry layers are
 * SurfaceControl children of that SurfaceView's surface.
 *
 * Constructed, added, used and removed on the ink thread only. Input forwarding
 * allocates nothing:
 *  - earliest View-level hook: dispatchTouchEvent / dispatchGenericMotionEvent
 *    on the window root (no listener, touch-delegate or focus machinery)
 *  - unbuffered dispatch: each pen sample arrives as soon as it exists
 *  - every historical sample, with hardware timestamps (API 34 nanos)
 *  - routed per pointer, by tool type: the stylus/eraser pointer → ink, wherever
 *    it sits in the event (a resting palm is often pointer 0); every other
 *    pointer → the native palm filter, which passes deliberate touches on to
 *    [gestures] and, when drawing with a finger is on, draws them
 *  - hover position too: the palm filter models the hand around the nib
 *  - FLAG_CANCELED / ACTION_CANCEL roll the stroke back (and mark palms)
 *  - the window pins the display's highest refresh mode and asks for minimal
 *    post-processing; vsync timing is fed continuously (power is not a constraint)
 */
internal class InkWindow(
    context: Context,
    private val handle: Long,
    private val ink: Handler,
    private val gestures: GestureListener?,
    private val onFrame: () -> Unit,
    private val buttons: StylusButtons,
    /** After housekeeping (which may have run a deferred redraw). */
    private val onIdle: () -> Unit = {},
    /** Pen streams that open the tool dial instead of drawing. */
    private val dial: PenDialRelay? = null,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val staging: ByteBuffer = NotiInk.nativeStaging(handle).order(ByteOrder.nativeOrder())
    private val touchStaging: ByteBuffer = NotiInk.nativeTouchStaging(handle).order(ByteOrder.nativeOrder())
    private val gestureOut: ByteBuffer = NotiInk.nativeGestureOut(handle).order(ByteOrder.nativeOrder())
    private var pending = 0
    private var touchPending = 0
    private var params: WindowManager.LayoutParams? = null
    private val pxPerMm = context.resources.displayMetrics.let { m ->
        (if (m.xdpi > 100f) m.xdpi else m.densityDpi.toFloat()) / 25.4f
    }

    // The pen pointer of the current stream (id + device), or -1.
    private var penId = -1
    private var penDevice = -1
    // What the current pen stream does: draws, drives the tool dial, or is swallowed
    // because it closed a locked dial.
    private var penMode = PEN_INK

    // Housekeeping timer (GPU keep-alive, deferred power changes, palm filter timers).
    private var idleAtMs = 0L
    private val idleTick = Runnable {
        idleAtMs = 0L
        val next = NotiInk.nativeIdle(handle)
        drainGestures()
        onIdle()
        schedule(next)
    }

    // Vsync feed for the scan-out-aware horizon: every frame, always.
    private val choreographer = Choreographer.getInstance()  // the ink thread's own
    private var vsyncActive = false
    private val vsync: Choreographer.VsyncCallback = Choreographer.VsyncCallback { data ->
        val timelines = data.frameTimelines
        val present = data.preferredFrameTimeline.expectedPresentationTimeNanos
        val period = if (timelines.size >= 2) {
            timelines[1].expectedPresentationTimeNanos - timelines[0].expectedPresentationTimeNanos
        } else {
            (1e9 / display.refreshRate).toLong()
        }
        NotiInk.nativeVsync(handle, present, period)
        onFrame()
        if (vsyncActive) postVsync()
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSPARENT)
        requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
    }

    fun add(parentToken: IBinder, bounds: Rect) {
        val lp = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            token = parentToken
            // Bounds are physical window coordinates, including in edge-to-edge mode.
            // START would mirror x in RTL; fitting insets would offset it a second time.
            @Suppress("RtlHardcoded")
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            x = bounds.left
            y = bounds.top
            title = "noti-ink"
            preferMinimalPostProcessing = true
            fastestMode(context.display)?.let { preferredDisplayModeId = it.modeId }
        }
        params = lp
        wm.addView(this, lp)  // ViewRootImpl (and input dispatch) bound to this thread
        vsyncActive = true
        postVsync()
        schedule(NotiInk.nativeIdle(handle))  // start GPU keep-alive
    }

    fun updateBounds(bounds: Rect) {
        val lp = params ?: return
        if (lp.x == bounds.left && lp.y == bounds.top && lp.width == bounds.width() && lp.height == bounds.height()) return
        lp.x = bounds.left
        lp.y = bounds.top
        lp.width = bounds.width()
        lp.height = bounds.height()
        wm.updateViewLayout(this, lp)
    }

    fun remove() {
        buttons.reset()
        ink.removeCallbacks(idleTick)
        vsyncActive = false
        choreographer.removeVsyncCallback(vsync)
        if (isAttachedToWindow) wm.removeViewImmediate(this)
    }

    // ---- surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NotiInk.nativeSurfaceChanged(handle, holder.surface, width, height)
        NotiInk.nativeSetPalmView(handle, width.toFloat(), height.toFloat(), pxPerMm)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NotiInk.nativeSurfaceDestroyed(handle)
    }

    // ---- input (ink thread) ----

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val recv = System.nanoTime()
        // Samsung S Pen: a stroke drawn with the side button held may arrive as actions 211–214.
        val action = SamsungPenActions.action(e.actionMasked)
        val buttonState = SamsungPenActions.buttons(e.actionMasked, e.buttonState)
        for (i in 0 until e.pointerCount) {
            if (isPen(e, i)) { buttons.motion(e.deviceId, buttonState, e.eventTime, contact = true); break }
        }
        val ai = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN) {
                    requestUnbufferedDispatch(e)
                    // A new stream: forget a pen whose UP never came (the engine keeps its ink).
                    if (penId >= 0 && penMode == PEN_DIAL) dial?.report(PenDialRelay.CANCEL, 0f, 0f)
                    penId = -1
                    penMode = PEN_INK
                }
                if (isPen(e, ai)) {
                    if (penId < 0) {
                        gestures?.onPenDown()
                        penId = e.getPointerId(ai)
                        penDevice = e.deviceId
                        penMode = when {
                            dial != null && dial.enabled && e.getToolType(ai) == MotionEvent.TOOL_TYPE_STYLUS &&
                                buttons.summonsDial(e.deviceId, buttonState) -> {
                                dial.report(PenDialRelay.DOWN, e.getX(ai), e.getY(ai)); PEN_DIAL
                            }
                            dial != null && dial.locked -> {
                                dial.report(PenDialRelay.DISMISS, e.getX(ai), e.getY(ai)); PEN_SWALLOW
                            }
                            else -> PEN_INK
                        }
                        if (penMode == PEN_INK) {
                            onFrame() // apply the final viewport before accepting this stroke
                            put(e, ai, -1, NotiInk.ACTION_DOWN, penFlags(e, ai, buttonState), recv)
                        }
                    }
                } else {
                    putTouch(e, ai, -1, NotiInk.TOUCH_DOWN, 0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = penIndex(e)
                if (pi >= 0 && penMode == PEN_DIAL) {
                    dial?.report(PenDialRelay.MOVE, e.getX(pi), e.getY(pi))
                } else if (pi >= 0 && penMode == PEN_INK) {
                    val flags = penFlags(e, pi, buttonState)
                    for (h in 0 until e.historySize) {
                        put(e, pi, h, NotiInk.ACTION_MOVE, flags or NotiInk.FLAG_HISTORICAL, recv)
                    }
                    put(e, pi, -1, NotiInk.ACTION_MOVE, flags, recv)
                }
                for (i in 0 until e.pointerCount) {
                    if (i == pi || isPen(e, i)) continue
                    for (h in 0 until e.historySize) putTouch(e, i, h, NotiInk.TOUCH_MOVE, NotiInk.TOUCH_FLAG_HISTORICAL)
                    putTouch(e, i, -1, NotiInk.TOUCH_MOVE, 0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0  // API 33
                if (penIndex(e) == ai) {
                    if (canceled) buttons.reset()
                    when (penMode) {
                        PEN_INK -> put(e, ai, -1, if (canceled) NotiInk.ACTION_CANCEL else NotiInk.ACTION_UP, penFlags(e, ai, buttonState), recv)
                        PEN_DIAL -> dial?.report(if (canceled) PenDialRelay.CANCEL else PenDialRelay.UP, e.getX(ai), e.getY(ai))
                    }
                    penId = -1
                    penMode = PEN_INK
                } else if (!isPen(e, ai)) {
                    putTouch(e, ai, -1, NotiInk.TOUCH_UP, if (canceled) NotiInk.TOUCH_FLAG_CANCELED else 0)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                buttons.reset()
                val pi = penIndex(e)
                if (penId >= 0) {
                    when (penMode) {
                        PEN_INK -> put(e, if (pi >= 0) pi else 0, -1, NotiInk.ACTION_CANCEL, 0, recv)
                        PEN_DIAL -> dial?.report(PenDialRelay.CANCEL, 0f, 0f)
                    }
                    penId = -1
                    penMode = PEN_INK
                }
                for (i in 0 until e.pointerCount) {
                    if (i != pi && !isPen(e, i)) putTouch(e, i, -1, NotiInk.TOUCH_CANCEL, 0)
                }
            }
            else -> return true
        }
        if (pending > 0) process()
        if (touchPending > 0) processTouch()
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!isPen(e, 0)) return false
        // A hover exit also comes just before the nib touches down; either way, a button held
        // across it is not a click (S Pen clicks come from these flags, see StylusButtons).
        buttons.motion(e.deviceId, e.buttonState, e.eventTime, leaving = e.actionMasked == MotionEvent.ACTION_HOVER_EXIT)
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> NotiInk.ACTION_HOVER_ENTER
            MotionEvent.ACTION_HOVER_EXIT -> NotiInk.ACTION_HOVER_EXIT
            // The engine ignores hover moves; the palm filter uses them to know where the hand is.
            MotionEvent.ACTION_HOVER_MOVE -> NotiInk.ACTION_HOVER_MOVE
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return true
            else -> return false
        }
        put(e, 0, -1, action, 0, System.nanoTime())
        process()
        return true
    }

    private fun postVsync() {
        choreographer.postVsyncCallback(vsync)
    }

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        val tool = e.getToolType(i)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Index of the current stream's pen pointer in [e], or -1. */
    private fun penIndex(e: MotionEvent): Int =
        if (penId < 0 || e.deviceId != penDevice) -1 else e.findPointerIndex(penId)

    private fun penFlags(e: MotionEvent, i: Int, buttonState: Int): Int {
        var f = 0
        if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) f = f or NotiInk.FLAG_ERASER
        f = f or buttons.flags(e.deviceId, buttonState, contact = true)
        return f
    }

    private fun time(e: MotionEvent, h: Int): Long = if (Build.VERSION.SDK_INT >= 34) {
        if (h < 0) e.eventTimeNanos else e.getHistoricalEventTimeNanos(h)
    } else {
        (if (h < 0) e.eventTime else e.getHistoricalEventTime(h)) * 1_000_000L
    }

    /** Writes one pen sample of pointer [i] into the native staging buffer; h = -1 for the current sample. */
    private fun put(e: MotionEvent, i: Int, h: Int, action: Short, flags: Int, recv: Long) {
        if (pending == NotiInk.STAGING_SAMPLES) process()
        val o = pending * NotiInk.SAMPLE_BYTES
        val b = staging
        b.putLong(o, time(e, h))
        b.putLong(o + 8, recv)
        if (h < 0) {
            b.putFloat(o + 16, e.getX(i))
            b.putFloat(o + 20, e.getY(i))
            b.putFloat(o + 24, e.getPressure(i))
            b.putFloat(o + 28, e.getAxisValue(MotionEvent.AXIS_TILT, i))
            b.putFloat(o + 32, e.getOrientation(i))
        } else {
            b.putFloat(o + 16, e.getHistoricalX(i, h))
            b.putFloat(o + 20, e.getHistoricalY(i, h))
            b.putFloat(o + 24, e.getHistoricalPressure(i, h))
            b.putFloat(o + 28, e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h))
            b.putFloat(o + 32, e.getHistoricalOrientation(i, h))
        }
        b.putShort(o + 36, action)
        b.putShort(o + 38, flags.toShort())
        pending++
    }

    /** Writes one finger/palm contact sample of pointer [i] (noti::TouchSample); h = -1 for the current sample. */
    private fun putTouch(e: MotionEvent, i: Int, h: Int, action: Byte, flags: Int) {
        if (touchPending == NotiInk.TOUCH_STAGING) processTouch()
        val o = touchPending * NotiInk.TOUCH_BYTES
        val b = touchStaging
        b.putLong(o, time(e, h))
        if (h < 0) {
            b.putFloat(o + 8, e.getX(i))
            b.putFloat(o + 12, e.getY(i))
            b.putFloat(o + 16, e.getTouchMajor(i))
            b.putFloat(o + 20, e.getTouchMinor(i))
            b.putFloat(o + 24, e.getOrientation(i))
            b.putFloat(o + 28, e.getPressure(i))
        } else {
            b.putFloat(o + 8, e.getHistoricalX(i, h))
            b.putFloat(o + 12, e.getHistoricalY(i, h))
            b.putFloat(o + 16, e.getHistoricalTouchMajor(i, h))
            b.putFloat(o + 20, e.getHistoricalTouchMinor(i, h))
            b.putFloat(o + 24, e.getHistoricalOrientation(i, h))
            b.putFloat(o + 28, e.getHistoricalPressure(i, h))
        }
        // Contact id: unique per (device, pointer).
        b.putInt(o + 32, (e.deviceId shl 5) or (e.getPointerId(i) and 31))
        b.put(o + 36, action)
        b.put(o + 37, e.getToolType(i).toByte())  // TOOL_TYPE_PALM (5) is passed through as such
        b.put(o + 38, flags.toByte())
        b.put(o + 39, 0)
        touchPending++
    }

    /** Predict + draw everything staged, right now, on this thread. */
    private fun process() {
        val n = pending
        pending = 0
        schedule(NotiInk.nativeProcess(handle, n))
        drainGestures()
    }

    /** Palm filter: staged contacts → finger ink (drawn now) + deliberate touches → [gestures]. */
    private fun processTouch() {
        val n = touchPending
        touchPending = 0
        schedule(NotiInk.nativeTouch(handle, n))
        drainGestures()
    }

    /** Delivers the gesture events the last native call produced. */
    fun drainGestures() {
        val b = gestureOut
        val n = b.getInt(0)
        if (n <= 0) return
        b.putInt(0, 0)
        val g = gestures ?: return
        for (k in 0 until n) {
            val o = NotiInk.GESTURE_HEADER_BYTES + k * NotiInk.GESTURE_BYTES
            g.onGesture(b.get(o + 20).toInt(), b.getInt(o + 16), b.getFloat(o + 8), b.getFloat(o + 12), b.getLong(o))
        }
        g.onGestureBatchEnd()
    }

    private companion object {
        const val PEN_INK = 0
        const val PEN_DIAL = 1
        const val PEN_SWALLOW = 2
    }

    private fun fastestMode(d: Display?): Display.Mode? {
        val cur = d?.mode ?: return null
        return d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
    }

    /** Arms the housekeeping timer if the engine wants it sooner than already armed. */
    private fun schedule(deadlineNs: Long) {
        if (deadlineNs < 0) return
        val at = SystemClock.uptimeMillis() + maxOf(1L, deadlineNs / 1_000_000L)
        if (idleAtMs != 0L && at >= idleAtMs) return
        ink.removeCallbacks(idleTick)
        idleAtMs = at
        ink.postAtTime(idleTick, at)
    }
}
