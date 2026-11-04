package app.noti.ui.common

import app.noti.ink.GestureListener
import app.noti.ink.NotiInk
import app.noti.pdf.PageViewport
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns the palm filter's stream of deliberate touches into canvas gestures:
 *  - one finger scrolls continuously with momentum,
 *  - two fingers pinch-zoom and pan,
 *  - a two-finger double tap calls [onTwoFingerDoubleTap].
 *
 * Palms never get here: the native filter only passes on contacts it has confirmed,
 * replaying the samples it held back meanwhile, and cancels a contact it later finds
 * was a palm (which aborts the tap). Runs on the ink thread.
 */
internal class CanvasGestures(
    private val slopPx: Float,
    private val viewport: PageViewport?,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onTwoFingerTap: () -> Unit = {},
    private val onTwoFingerDoubleTap: () -> Unit,

) : GestureListener {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val tapAction = Runnable { onTwoFingerTap() }
    private var vx = 0f
    private var vy = 0f
    private var batchTime = 0L
    private var eventTime = 0L
    private val fling = object : Runnable {
        override fun run() {
            viewport?.pan(vx * 0.016f, vy * 0.016f)
            vx *= 0.91f; vy *= 0.91f
            if (hypot(vx, vy) > 30f) schedule(this, 16) else viewport?.end()
        }
    }
    fun stopMotion() { cancel(fling); viewport?.end() }
    override fun onPenDown() { stopMotion(); main.removeCallbacks(tapAction) }
    fun close() { main.removeCallbacks(tapAction); cancel(fling) }
    private class Pointer(var x: Float, var y: Float, val x0: Float, val y0: Float)

    private val pointers = LinkedHashMap<Int, Pointer>()
    // The current session: from the first finger down to the last finger up.
    private var sessionStart = 0L
    private var maxPointers = 0
    private var moved = false
    private var cancelled = false
    private var engaged = false
    private var lastTapEnd = 0L
    // Transform tracking between batches.
    private var lastCount = 0
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastDist = 0f

    override fun onGesture(action: Int, id: Int, x: Float, y: Float, timeNanos: Long) {
        eventTime = timeNanos
        when (action) {
            NotiInk.GESTURE_DOWN -> {
                if (pointers.isEmpty()) {
                    cancel(fling)
                    vx = 0f; vy = 0f; batchTime = 0L
                    sessionStart = timeNanos
                    maxPointers = 0
                    moved = false
                    cancelled = false
                    engaged = false
                    viewport?.begin()
                }
                pointers[id] = Pointer(x, y, x, y)
                maxPointers = max(maxPointers, pointers.size)
            }
            NotiInk.GESTURE_MOVE -> pointers[id]?.let { p ->
                p.x = x
                p.y = y
                if (hypot(x - p.x0, y - p.y0) > slopPx) moved = true
            }
            NotiInk.GESTURE_UP, NotiInk.GESTURE_CANCEL -> {
                if (pointers.remove(id) == null) return
                if (action == NotiInk.GESTURE_CANCEL) cancelled = true
                if (pointers.isEmpty()) endSession(timeNanos)
            }
        }
    }

    override fun onGestureBatchEnd() {
        val vp = viewport ?: return
        val n = pointers.size
        if (n == 0) {
            lastCount = 0
            return
        }
        val it = pointers.values.iterator()
        val a = it.next()
        val b = if (n >= 2) it.next() else null
        val cx = if (b == null) a.x else (a.x + b.x) / 2
        val cy = if (b == null) a.y else (a.y + b.y) / 2
        val dist = if (b == null) 0f else hypot(a.x - b.x, a.y - b.y)
        if (n != lastCount) {
            // Fingers added or lifted: restart from here so the page doesn't jump.
            lastCount = n
            lastCx = cx
            lastCy = cy
            lastDist = dist
            batchTime = eventTime
            return
        }
        if (!engaged) {
            if (!moved) return  // within touch slop: maybe a tap
            engaged = true
        }
        val elapsed = (eventTime - batchTime) / 1e9f
        if (elapsed > 0f && elapsed < 0.1f) {
            vx = 0.4f * vx + 0.6f * (cx - lastCx) / elapsed
            vy = 0.4f * vy + 0.6f * (cy - lastCy) / elapsed
        }
        batchTime = eventTime
        if (b == null) vp.pan(cx - lastCx, cy - lastCy)
        else vp.pinch(cx, cy, if (lastDist > 1f) dist / lastDist else 1f, cx - lastCx, cy - lastCy)
        lastCx = cx
        lastCy = cy
        lastDist = dist
    }

    private fun endSession(t: Long) {
        if (!cancelled && moved && t - batchTime < 100_000_000 && hypot(vx, vy) > 150f) {
            viewport?.begin()
            schedule(fling, 16)
        } else viewport?.end()
        lastCount = 0
        val tap = !cancelled && maxPointers == 2 && !moved && t - sessionStart < TAP_NS
        if (!tap) { lastTapEnd = 0L; return }
        if (lastTapEnd != 0L && t - lastTapEnd < DOUBLE_TAP_NS) {
            lastTapEnd = 0L
            main.removeCallbacks(tapAction)
            onTwoFingerDoubleTap()
        } else {
            lastTapEnd = t
            main.postDelayed(tapAction, DOUBLE_TAP_NS / 1_000_000)
        }
    }

    private companion object {
        const val TAP_NS = 300_000_000L
        const val DOUBLE_TAP_NS = 450_000_000L
    }
}
