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

    // Housekeeping timer (GPU keep-alive, deferred power changes, palm filter timers).
    private var idleAtMs = 0L
    private val idleTick = Runnable {
        idleAtMs = 0L
        val next = NotiInk.nativeIdle(handle)
        drainGestures()
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
        val ai = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    requestUnbufferedDispatch(e)
                    penId = -1  // a new stream: forget a pen whose UP never came (the engine keeps its ink)
                }
                if (isPen(e, ai)) {
                    if (penId < 0) {
                        gestures?.onPenDown()
                        onFrame() // apply the final viewport before accepting this stroke
                        penId = e.getPointerId(ai)
                        penDevice = e.deviceId
                        put(e, ai, -1, NotiInk.ACTION_DOWN, penFlags(e, ai), recv)
                    }
                } else {
                    putTouch(e, ai, -1, NotiInk.TOUCH_DOWN, 0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = penIndex(e)
                if (pi >= 0) {
                    val flags = penFlags(e, pi)
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
                    put(e, ai, -1, if (canceled) NotiInk.ACTION_CANCEL else NotiInk.ACTION_UP, penFlags(e, ai), recv)
                    penId = -1
                } else if (!isPen(e, ai)) {
                    putTouch(e, ai, -1, NotiInk.TOUCH_UP, if (canceled) NotiInk.TOUCH_FLAG_CANCELED else 0)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val pi = penIndex(e)
                if (penId >= 0) {
                    put(e, if (pi >= 0) pi else 0, -1, NotiInk.ACTION_CANCEL, 0, recv)
                    penId = -1
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
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> NotiInk.ACTION_HOVER_ENTER
            MotionEvent.ACTION_HOVER_EXIT -> NotiInk.ACTION_HOVER_EXIT
            // The engine ignores hover moves; the palm filter uses them to know where the hand is.
            MotionEvent.ACTION_HOVER_MOVE -> NotiInk.ACTION_HOVER_MOVE
            else -> return false
        }
        if (!isPen(e, 0)) return false
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

    private fun penFlags(e: MotionEvent, i: Int): Int {
        var f = 0
        if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) f = f or NotiInk.FLAG_ERASER
        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) f = f or NotiInk.FLAG_BUTTON_PRIMARY
        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)) f = f or NotiInk.FLAG_BUTTON_SECONDARY
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
