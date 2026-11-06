package app.noti.ink

import android.os.Handler
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hands a pen stream that summons the tool dial to the UI thread.
 *
 * The ink window keeps receiving the stream (Android routes a whole touch stream to the
 * window that got its DOWN), so the dial can open under the nib without taking input
 * from the ink thread. Events are posted in order; moves are coalesced to the latest
 * position per main-thread turn. Positions are in the anchor's window coordinates.
 */
internal class PenDialRelay(private val main: Handler, private val anchor: View, private val isReleased: () -> Boolean) {
    /** Main thread writes, ink thread reads. Null: holds never summon the dial. */
    @Volatile var listener: ((action: Int, x: Float, y: Float) -> Unit)? = null

    /** While the dial is locked open, the next touch outside it closes it. */
    @Volatile var locked = false

    val enabled get() = listener != null

    private val latestMove = AtomicLong()
    private val movePosted = AtomicBoolean(false)
    private val location = IntArray(2)

    /** Ink thread. [x], [y] are ink-window (anchor-local) pixels. */
    fun report(action: Int, x: Float, y: Float) {
        if (action == MOVE) {
            latestMove.set(pack(x, y))
            if (movePosted.compareAndSet(false, true)) main.post {
                movePosted.set(false)
                val packed = latestMove.get()
                deliver(MOVE, Float.fromBits((packed ushr 32).toInt()), Float.fromBits(packed.toInt()))
            }
        } else {
            main.post { deliver(action, x, y) }
        }
    }

    private fun deliver(action: Int, x: Float, y: Float) {
        if (isReleased()) return
        val target = listener ?: return
        // The ink window sits exactly over the anchor (see InkAnchorBounds).
        anchor.getLocationInWindow(location)
        target(action, x + location[0], y + location[1])
    }

    private fun pack(x: Float, y: Float) = (x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xFFFFFFFFL)

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
        /** A touch outside the locked dial. Carries that touch's position. */
        const val DISMISS = 4
    }
}
