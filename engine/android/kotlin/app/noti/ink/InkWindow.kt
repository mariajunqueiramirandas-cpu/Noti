package app.noti.ink

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ink thread's input window: a transparent TYPE_APPLICATION_PANEL over the
 * canvas whose root is a SurfaceView. The native wet/dry layers are
 * SurfaceControl children of that SurfaceView's surface.
 *
 * Constructed, added, used and removed on the ink thread only. Input forwarding
 * allocates nothing:
 *  - unbuffered dispatch: each pen sample arrives as soon as it exists
 *  - every historical sample, with hardware timestamps (API 34 nanos)
 *  - stylus/eraser → ink; fingers → [onFinger] (palm rejection for ink)
 *  - FLAG_CANCELED / ACTION_CANCEL roll the stroke back
 *  - hover enter/exit pre-warm / relax the pipeline (144 Hz, ADPF, GPU keep-alive)
 */
internal class InkWindow(
    context: Context,
    private val handle: Long,
    private val ink: Handler,
    private val onFinger: (MotionEvent) -> Boolean,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val staging: ByteBuffer = NotiInk.nativeStaging(handle).order(ByteOrder.nativeOrder())
    private var pending = 0
    private var params: WindowManager.LayoutParams? = null

    // Housekeeping timer (GPU keep-alive, deferred power changes).
    private var idleAtMs = 0L
    private val idleTick = Runnable {
        idleAtMs = 0L
        schedule(NotiInk.nativeIdle(handle))
    }

    // Vsync feed for the scan-out-aware horizon, active while the pen is around.
    private val choreographer = Choreographer.getInstance()  // the ink thread's own
    private var lastPenMs = 0L
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
        if (SystemClock.uptimeMillis() - lastPenMs < 2_000) postVsync() else vsyncActive = false
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
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            title = "noti-ink"
        }
        params = lp
        wm.addView(this, lp)  // ViewRootImpl (and input dispatch) bound to this thread
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
        if (vsyncActive) choreographer.removeVsyncCallback(vsync)
        if (isAttachedToWindow) wm.removeViewImmediate(this)
    }

    // ---- surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NotiInk.nativeSurfaceChanged(handle, holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NotiInk.nativeSurfaceDestroyed(handle)
    }

    // ---- input (ink thread) ----

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) {
            return onFinger(e)
        }
        val recv = System.nanoTime()
        val flags = baseFlags(e, tool)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                put(e, -1, NotiInk.ACTION_DOWN, flags, recv)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    put(e, h, NotiInk.ACTION_MOVE, flags or NotiInk.FLAG_HISTORICAL, recv)
                }
                put(e, -1, NotiInk.ACTION_MOVE, flags, recv)
            }
            MotionEvent.ACTION_UP -> {
                val canceled = (e.flags and MotionEvent.FLAG_CANCELED) != 0  // API 33
                put(e, -1, if (canceled) NotiInk.ACTION_CANCEL else NotiInk.ACTION_UP, flags, recv)
            }
            MotionEvent.ACTION_CANCEL -> put(e, -1, NotiInk.ACTION_CANCEL, flags, recv)
            else -> return true
        }
        process()
        penActivity()
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> NotiInk.ACTION_HOVER_ENTER
            MotionEvent.ACTION_HOVER_EXIT -> NotiInk.ACTION_HOVER_EXIT
            else -> {
                penActivity()
                return true  // hover moves: nothing to draw yet (cursor preview is Phase 4)
            }
        }
        put(e, -1, action, 0, System.nanoTime())
        process()
        penActivity()
        return true
    }

    private fun penActivity() {
        lastPenMs = SystemClock.uptimeMillis()
        if (!vsyncActive) {
            vsyncActive = true
            postVsync()
        }
    }

    private fun postVsync() {
        choreographer.postVsyncCallback(vsync)
    }

    private fun baseFlags(e: MotionEvent, tool: Int): Int {
        var f = 0
        if (tool == MotionEvent.TOOL_TYPE_ERASER) f = f or NotiInk.FLAG_ERASER
        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) f = f or NotiInk.FLAG_BUTTON_PRIMARY
        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)) f = f or NotiInk.FLAG_BUTTON_SECONDARY
        return f
    }

    /** Writes one sample into the native staging buffer; h = -1 for the current sample. */
    private fun put(e: MotionEvent, h: Int, action: Short, flags: Int, recv: Long) {
        if (pending == NotiInk.STAGING_SAMPLES) process()
        val t = if (Build.VERSION.SDK_INT >= 34) {
            if (h < 0) e.eventTimeNanos else e.getHistoricalEventTimeNanos(h)
        } else {
            (if (h < 0) e.eventTime else e.getHistoricalEventTime(h)) * 1_000_000L
        }
        val o = pending * NotiInk.SAMPLE_BYTES
        val b = staging
        b.putLong(o, t)
        b.putLong(o + 8, recv)
        if (h < 0) {
            b.putFloat(o + 16, e.x)
            b.putFloat(o + 20, e.y)
            b.putFloat(o + 24, e.pressure)
            b.putFloat(o + 28, e.getAxisValue(MotionEvent.AXIS_TILT))
            b.putFloat(o + 32, e.orientation)
        } else {
            b.putFloat(o + 16, e.getHistoricalX(h))
            b.putFloat(o + 20, e.getHistoricalY(h))
            b.putFloat(o + 24, e.getHistoricalPressure(h))
            b.putFloat(o + 28, e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h))
            b.putFloat(o + 32, e.getHistoricalOrientation(h))
        }
        b.putShort(o + 36, action)
        b.putShort(o + 38, flags.toShort())
        pending++
    }

    /** Predict + draw everything staged, right now, on this thread. */
    private fun process() {
        val n = pending
        pending = 0
        schedule(NotiInk.nativeProcess(handle, n))
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
