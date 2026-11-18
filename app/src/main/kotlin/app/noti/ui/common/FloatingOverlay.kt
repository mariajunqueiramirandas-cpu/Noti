package app.noti.ui.common

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

internal fun IntOffset.clampTo(bounds: IntRect): IntOffset =
    IntOffset(x.coerceIn(bounds.left, bounds.right), y.coerceIn(bounds.top, bounds.bottom))

internal suspend fun PointerInputScope.dragFloatingOverlay(
    bounds: IntRect,
    position: () -> IntOffset,
    moveTo: (IntOffset) -> Unit,
    onStart: () -> Unit = {},
) {
    var current = Offset.Zero
    // Compose maps both ends of each delta into the current window. Keep fractions across
    // samples and clamp before accumulating the next delta, so reversing at an edge moves immediately.
    detectDragGestures(onDragStart = {
        current = position().clampTo(bounds).toOffset()
        onStart()
    }) { change, delta ->
        change.consume()
        current = Offset((current.x + delta.x).coerceIn(bounds.left.toFloat(), bounds.right.toFloat()),
            (current.y + delta.y).coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat()))
        moveTo(current.round())
    }
}
