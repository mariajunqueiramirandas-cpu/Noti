package app.noti.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.noti.ink.PenDialRelay
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * The summoned dial, in dp. Its window stacks a label slot, the dial and the lock pip:
 *
 *        [ label ]        LabelSlot (28 dp label + 6 dp gap) above the dial
 *       (  dial   )       DialSize, centred on the nib
 *          (🔒)           LockOffset below the dial's centre
 */
internal const val LabelSlot = 34f
internal const val LockOffset = DialCenter + 4f + LockSize / 2f
private const val LockReach = 26f
/** Selection goes by direction: a nib past the outer edge still picks the segment it points at. */
private const val PickReach = RingOuter + 52f
/** Lifting within this distance of where the nib landed is a press-tap: it opens the dial locked. */
internal const val PressSlop = 8f

/** What a point of the summoned dial picks, given in dp from the dial's centre. */
internal sealed interface DialPick {
    data class Segment(val index: Int) : DialPick
    data object Lock : DialPick
    data object Cancel : DialPick
}

internal fun pickAt(dx: Float, dy: Float): DialPick {
    if (hypot(dx, dy - LockOffset) <= LockReach) return DialPick.Lock
    val radius = hypot(dx, dy)
    if (radius < RingInner - 2f || radius > PickReach) return DialPick.Cancel
    return DialPick.Segment(segmentAt(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()))
}

/**
 * The tool dial the pen summons: hold the writing button and touch the page, drag to a
 * segment and lift to choose it. Lifting on the hub or far outside cancels, on the lock
 * keeps the dial open, and a press-tap without dragging opens it locked. A locked dial
 * closes on the next touch outside it.
 *
 * Positions are in dp relative to the editor area. Main thread.
 */
@Stable
internal class PenDialState {
    enum class Phase { Hidden, Picking, Locked }

    var phase by mutableStateOf(Phase.Hidden)
        private set
    /** The dial's centre: under the nib, moved inward only as far as the edges need. */
    var center by mutableStateOf(Offset.Zero)
        private set
    /** What lifting the nib now would do, or null until the nib has moved. */
    var hovered by mutableStateOf<DialPick?>(null)
        private set

    /** The editor area, in dp, and its window position in pixels. Set by the host. */
    var area = Size.Zero
    var origin = Offset.Zero
    var density = 1f

    private var start = Offset.Zero
    private var moved = false
    private var opened = false

    fun press(at: Offset) {
        center = clamp(at)
        start = at
        moved = false
        opened = true
        hovered = null
        phase = Phase.Picking
    }

    fun drag(at: Offset) {
        if (phase != Phase.Picking) return
        if (!moved && (at - start).getDistance() > PressSlop) moved = true
        if (moved) hovered = pickAt(at.x - center.x, at.y - center.y)
    }

    /** The nib lifted at [at]. Returns the segment to apply, if one was chosen. */
    fun release(at: Offset): Int? {
        if (phase != Phase.Picking) return null
        drag(at)
        val pick = if (moved) hovered else DialPick.Lock
        hovered = null
        phase = if (pick == DialPick.Lock) Phase.Locked else Phase.Hidden
        return (pick as? DialPick.Segment)?.index
    }

    /** The system took the stream away (palm rejection, focus loss): nothing changes. */
    fun cancel() {
        if (phase != Phase.Picking) return
        hovered = null
        phase = Phase.Hidden
    }

    fun dismiss() {
        hovered = null
        phase = Phase.Hidden
    }

    /** Opens the dial locked, without the pen: where it was last, or in the middle of the area. */
    fun open() {
        center = clamp(if (opened) center else Offset(area.width / 2f, area.height / 2f))
        opened = true
        hovered = null
        phase = Phase.Locked
    }

    /** Events from InkLayer.setPenDial, in window pixels. Returns a segment to apply. */
    fun onPen(action: Int, x: Float, y: Float): Int? {
        val at = Offset((x - origin.x) / density, (y - origin.y) / density)
        when (action) {
            PenDialRelay.DOWN -> press(at)
            PenDialRelay.MOVE -> drag(at)
            PenDialRelay.UP -> return release(at)
            PenDialRelay.CANCEL -> cancel()
            PenDialRelay.DISMISS -> dismiss()
        }
        return null
    }

    private fun clamp(at: Offset): Offset {
        val half = DialCenter + EdgeMargin
        fun axis(value: Float, low: Float, high: Float) = if (high < low) (low + high) / 2f else value.coerceIn(low, high)
        return Offset(
            axis(at.x, half, area.width - half),
            axis(at.y, LabelSlot + half, area.height - LockOffset - LockSize / 2f - EdgeMargin),
        )
    }

    private companion object {
        const val EdgeMargin = 4f
    }
}
