package app.noti.ui.common

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import app.noti.data.Paper
import app.noti.ink.InkLayer
import kotlin.math.hypot
import kotlin.math.max

/** What the pen draws with. [pressureGamma] null = constant width. */
data class BrushSpec(
    val argb: Long,
    val alpha: Float,
    val widthPx: Float,
    val pressureGamma: Float?,
)

/** Engine settings that aren't part of the brush. */
data class EngineSpec(val predictor: Int, val inkFeelMs: Float)

/**
 * Paper plus the low-latency ink engine on top of it.
 *
 * The engine puts its own input window over this composable's bounds, so nothing
 * touchable may be drawn over it: toolbars and panels are docked beside it. Each
 * InkCanvas owns one engine instance; leaving composition releases it (and its ink,
 * since saving isn't built yet).
 *
 * Keep it at a fixed position on screen (not inside a scrolling container): the ink
 * window follows size changes, not pure moves.
 */
@Composable
fun InkCanvas(
    paper: Paper,
    brush: BrushSpec,
    engine: EngineSpec,
    modifier: Modifier = Modifier,
    onTwoFingerDoubleTap: (() -> Unit)? = null,
) {
    // Previews and screenshot tests have no native engine: paper only.
    if (LocalInspectionMode.current) {
        PaperBackground(paper, modifier)
        return
    }
    val context = LocalContext.current
    val anchor = remember { View(context) }
    val doubleTap = rememberUpdatedState(onTwoFingerDoubleTap)
    val layer = remember {
        val main = Handler(Looper.getMainLooper())
        val detector = TwoFingerDoubleTap(ViewConfiguration.get(context).scaledTouchSlop * 2f) {
            main.post { doubleTap.value?.invoke() }
        }
        InkLayer(anchor, onFingerEvent = detector::onEvent)
    }
    DisposableEffect(layer) { onDispose { layer.release() } }

    // Setters are cheap lock-free commands; push the current values after every recomposition.
    SideEffect {
        val c = brush.argb
        layer.setColor(
            ((c shr 16) and 0xFF) / 255f,
            ((c shr 8) and 0xFF) / 255f,
            (c and 0xFF) / 255f,
            brush.alpha,
        )
        layer.setWidth(brush.widthPx)
        if (brush.pressureGamma == null) layer.setPressureCurve(1f, 1f) else layer.setPressureCurve(brush.pressureGamma, 0.12f)
        layer.setPredictor(engine.predictor)
        layer.setDisplayTiming(panelMs = engine.inkFeelMs)
    }

    Box(modifier) {
        PaperBackground(paper, Modifier.fillMaxSize())
        AndroidView(factory = { anchor }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Recognises a two-finger double tap among the finger events the ink window passes on
 * (fingers never draw). Runs on the ink thread; [onDoubleTap] is called there.
 */
private class TwoFingerDoubleTap(private val slopPx: Float, private val onDoubleTap: () -> Unit) {
    private var downTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var startX = 0f
    private var startY = 0f
    private var lastTapTime = 0L

    fun onEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = e.eventTime
                maxPointers = 1
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                startX = centroidX(e)
                startY = centroidY(e)
            }
            MotionEvent.ACTION_MOVE -> if (maxPointers >= 2 && e.pointerCount >= 2 &&
                hypot(centroidX(e) - startX, centroidY(e) - startY) > slopPx
            ) {
                moved = true
            }
            MotionEvent.ACTION_UP -> {
                val tap = maxPointers == 2 && !moved && e.eventTime - downTime < 300
                if (tap) {
                    if (e.eventTime - lastTapTime < 450) {
                        lastTapTime = 0L
                        onDoubleTap()
                    } else {
                        lastTapTime = e.eventTime
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> maxPointers = 0
        }
        return true
    }

    private fun centroidX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun centroidY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }
}
