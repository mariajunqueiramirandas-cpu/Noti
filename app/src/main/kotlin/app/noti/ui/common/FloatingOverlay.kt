package app.noti.ui.common

import android.graphics.Region
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.dropWhile

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

/** Keep the native popup fixed while the content follows the pointer on a graphics layer. */
@Composable
internal fun BoxScope.DraggableFloatingOverlay(
    floating: Boolean,
    viewport: DpSize,
    position: (IntSize) -> IntOffset,
    drag: FloatingOverlayDrag,
    clippingEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!floating) {
        val inlinePosition = remember(position, drag) {
            { size: IntSize -> position(size) + drag.translation.round() }
        }
        FloatingOverlay(false, inlinePosition, content = content)
        return
    }

    val viewportPx = with(LocalDensity.current) { IntSize(viewport.width.roundToPx(), viewport.height.roundToPx()) }
    val windowPosition = remember(position, viewportPx) {
        // Use the measured size so the origin and size change together at drag boundaries.
        { size: IntSize -> if (size == viewportPx) IntOffset.Zero else position(size) }
    }
    // Resize only at the ends of the gesture. At rest the popup is the size of its content.
    FloatingOverlay(true, windowPosition, clippingEnabled) {
        val view = LocalView.current
        LaunchedEffect(view, drag) {
            snapshotFlow { drag.active }.dropWhile { !it }.collect { active ->
                // Android keeps delivering the current gesture to the window that received its down.
                // An empty region lets new touches through the temporary viewport to the ink below.
                view.rootSurfaceControl?.setTouchableRegion(if (active) Region() else null)
            }
        }
        Box(Modifier.layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            val dragging = drag.active
            val childSize = IntSize(child.width, child.height)
            val windowSize = if (dragging) viewportPx else childSize
            layout(windowSize.width, windowSize.height) {
                child.placeWithLayer(0, 0) {
                    val offset = if (dragging) position(childSize).toOffset() + drag.translation else Offset.Zero
                    translationX = offset.x
                    translationY = offset.y
                }
            }
        }) { content() }
    }
}

internal fun IntOffset.clampTo(bounds: IntRect): IntOffset =
    IntOffset(x.coerceIn(bounds.left, bounds.right), y.coerceIn(bounds.top, bounds.bottom))

@Stable
internal class FloatingOverlayDrag {
    var active by mutableStateOf(false)
        internal set
    var translation by mutableStateOf(Offset.Zero)
        internal set
}

internal suspend fun PointerInputScope.dragFloatingOverlay(
    drag: FloatingOverlayDrag,
    bounds: IntRect,
    position: () -> IntOffset,
    moveTo: (IntOffset) -> Unit,
    onStart: () -> Unit = {},
) {
    var start = Offset.Zero
    var current = Offset.Zero
    var initialSlop = Offset.Zero
    fun finish() {
        if (!drag.active) return
        moveTo(current.round())
        drag.translation = Offset.Zero
        drag.active = false
    }
    try {
        detectDragGestures(orientationLock = null, onDragStart = { down, trigger, overSlop ->
            start = position().clampTo(bounds).toOffset()
            current = start
            // Once it's a drag, catch up to the initial grip point as well as following later deltas.
            initialSlop = trigger.position - down.position - overSlop
            drag.active = true
            onStart()
        }, onDragEnd = { finish() }, onDragCancel = { finish() }) { change, delta ->
            change.consume()
            val next = current + delta + initialSlop
            initialSlop = Offset.Zero
            // Compose maps both ends of a delta into the current window. Keep fractional
            // movement and drop overshoot at the edges so reversing responds immediately.
            current = Offset(next.x.coerceIn(bounds.left.toFloat(), bounds.right.toFloat()),
                next.y.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat()))
            drag.translation = current - start
        }
    } finally {
        finish()
    }
}
