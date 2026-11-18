package app.noti.ui.common

import android.view.Choreographer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** A sub-panel above the native ink window. Position changes are observed in layout. */
@Composable
internal fun BoxScope.FloatingOverlay(
    floating: Boolean,
    position: (IntSize) -> IntOffset,
    clippingEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (floating) {
        val provider = remember(position) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                    anchorBounds.topLeft + position(popupContentSize)
            }
        }
        Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false,
            dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = clippingEnabled), content = content)
    } else {
        Box(Modifier.align(AbsoluteAlignment.TopLeft).layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            layout(child.width, child.height) {
                child.place(position(IntSize(child.width, child.height)))
            }
        }) { content() }
    }
}

/** Move the popup with the latest input once per display frame, without recomposing its content. */
@Composable
internal fun rememberFloatingOverlayMove(moveTo: (IntOffset) -> Unit): (IntOffset) -> Unit {
    val latest = rememberUpdatedState(moveTo)
    val move = remember { PopupFrameMove { latest.value(it) } }
    DisposableEffect(move) { onDispose { move.cancel() } }
    return remember(move) { move::schedule }
}

private class PopupFrameMove(private val moveTo: (IntOffset) -> Unit) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var pending: IntOffset? = null

    fun schedule(position: IntOffset) {
        if (pending == null) choreographer.postFrameCallback(this)
        pending = position
    }

    override fun doFrame(frameTimeNanos: Long) {
        val position = pending ?: return
        pending = null
        // Notify the position provider in this frame's animation phase, before window traversal.
        Snapshot.withMutableSnapshot { moveTo(position) }
    }

    fun cancel() {
        pending = null
        choreographer.removeFrameCallback(this)
    }
}

internal fun IntOffset.clampTo(bounds: IntRect): IntOffset =
    IntOffset(x.coerceIn(bounds.left, bounds.right), y.coerceIn(bounds.top, bounds.bottom))

internal suspend fun PointerInputScope.dragFloatingOverlay(
    bounds: IntRect,
    position: () -> IntOffset,
    moveTo: (IntOffset) -> Unit,
    onStart: () -> Unit = {},
) {
    var current = Offset.Zero
    var initialSlop = Offset.Zero
    detectDragGestures(orientationLock = null, onDragStart = { down, trigger, overSlop ->
        current = position().clampTo(bounds).toOffset()
        // Catch up to the pointer once its movement distinguishes a drag from a tap.
        initialSlop = trigger.position - down.position - overSlop
        onStart()
    }) { change, delta ->
        change.consume()
        val next = current + delta + initialSlop
        initialSlop = Offset.Zero
        // Both ends of the delta use the current window coordinates. Drop overshoot
        // at the edges so the popup can reverse immediately.
        current = Offset(next.x.coerceIn(bounds.left.toFloat(), bounds.right.toFloat()),
            next.y.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat()))
        moveTo(current.round())
    }
}
