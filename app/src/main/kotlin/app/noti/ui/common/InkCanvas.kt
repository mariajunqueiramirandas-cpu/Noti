package app.noti.ui.common

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.noti.data.Paper
import app.noti.ink.InkLayer
import app.noti.pdf.PageViewport
import app.noti.pdf.PagePlacement
import app.noti.pdf.PdfDocument
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** What the pen draws with. [pressureGamma] null = constant width. */
data class BrushSpec(
    val argb: Long,
    val alpha: Float,
    val widthPx: Float,
    val pressureGamma: Float?,
)

/** Engine settings that aren't part of the brush. */
data class EngineSpec(val predictor: Int, val inkFeelMs: Float)

/** Palm rejection settings and its learned model's persistence. */
data class PalmSpec(
    val fingerDrawing: Boolean = false,
    val model: FloatArray? = null,
    /** Called on the main thread with the model the canvas learned, when it closes. */
    val onModel: ((FloatArray) -> Unit)? = null,
)

/**
 * The PDF page on screen, for the editor around the canvas (page panel, page buttons).
 * Main thread.
 */
@Stable
class PageController {
    var placement by mutableStateOf<PagePlacement?>(null)
        internal set
    val page get() = placement?.page ?: 0
    internal var navigate: ((Int) -> Unit)? = null

    fun goTo(page: Int) {
        navigate?.invoke(page)
    }
}

/**
 * Paper (or a PDF page) plus the low-latency ink engine on top of it.
 *
 * The engine puts its own input window over this composable's bounds, so nothing
 * touchable may be drawn over it: toolbars and panels are docked beside it. Each
 * InkCanvas owns one engine instance; leaving composition releases it (and its ink,
 * since saving isn't built yet).
 *
 * Fingers go through the engine's palm rejection first: only deliberate touches pan,
 * zoom or turn PDF pages (or draw, with [PalmSpec.fingerDrawing]).
 *
 * With a [pdf], one page is shown at a time; ink is kept per page, in page coordinates,
 * so it stays on the page through zoom, pan and rotation.
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
    palm: PalmSpec = PalmSpec(),
    pdf: PdfDocument? = null,
    pages: PageController? = null,
    onTwoFingerDoubleTap: (() -> Unit)? = null,
    onLayer: ((InkLayer?) -> Unit)? = null,
) {
    // Previews and screenshot tests have no native engine: paper only.
    if (LocalInspectionMode.current) {
        PaperBackground(paper, modifier)
        return
    }
    val context = LocalContext.current
    val marginPx = with(LocalDensity.current) { 16.dp.toPx() }
    val anchor = remember { View(context) }
    val doubleTap = rememberUpdatedState(onTwoFingerDoubleTap)
    val onModel = rememberUpdatedState(palm.onModel)
    val controller = pages ?: remember { PageController() }

    val rig = remember(pdf) {
        val main = Handler(Looper.getMainLooper())
        // Placement updates come from the ink thread at up to the touch rate; the UI
        // takes the latest once per main-thread turn.
        val latest = AtomicReference<PagePlacement?>(null)
        val posted = AtomicBoolean(false)
        lateinit var created: InkLayer
        val viewport = if (pdf == null) null else PageViewport(marginPx) { p ->
            created.setPage(p.page, p.left, p.top, p.width)
            latest.set(p)
            if (posted.compareAndSet(false, true)) {
                main.post {
                    posted.set(false)
                    latest.get()?.let { controller.placement = it }
                }
            }
        }
        val gestures = CanvasGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), viewport) {
            main.post { doubleTap.value?.invoke() }
        }
        created = InkLayer(
            anchor,
            gestures = gestures,
            fingerDrawing = palm.fingerDrawing,
            palmModel = palm.model,
            onPalmModel = { m -> main.post { onModel.value?.invoke(m) } },
        )
        if (viewport != null) controller.navigate = { page -> created.runOnInkThread { viewport.goTo(page) } }
        Rig(created, viewport)
    }
    val layer = rig.layer
    DisposableEffect(layer) {
        onLayer?.invoke(layer)
        onDispose {
            onLayer?.invoke(null)
            controller.navigate = null
            layer.release()
        }
    }

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
        layer.setFingerDrawing(palm.fingerDrawing)
    }

    if (pdf != null) {
        val viewport = rig.viewport
        val sizes = pdf.pageSizes
        LaunchedEffect(sizes) {
            if (sizes.isEmpty()) return@LaunchedEffect
            val aspects = FloatArray(sizes.size) { sizes[it].height / sizes[it].width }
            layer.runOnInkThread { viewport?.setDocument(aspects) }
        }
        val placement = controller.placement
        // Sharp detail once a zoomed page comes to rest; neighbours ready for a page turn.
        LaunchedEffect(placement) {
            val p = placement ?: return@LaunchedEffect
            if (!p.settled) return@LaunchedEffect
            if (p.zoom > 1.05f) {
                val visible = Rect(
                    p.left.coerceAtLeast(0f).roundToInt(), p.top.coerceAtLeast(0f).roundToInt(),
                    (p.left + p.width).coerceAtMost(p.canvasWidth.toFloat()).roundToInt(),
                    (p.top + p.height).coerceAtMost(p.canvasHeight.toFloat()).roundToInt(),
                )
                pdf.requestDetail(p.page, RectF(p.left, p.top, p.left + p.width, p.top + p.height), visible)
            } else {
                pdf.clearDetail()
            }
            val fit = p.fitWidth.roundToInt()
            pdf.prefetch(p.page + 1, fit)
            pdf.prefetch(p.page - 1, fit)
        }
    }

    Box(modifier.onSizeChanged { size -> rig.viewport?.let { vp -> layer.runOnInkThread { vp.setCanvas(size.width, size.height) } } }) {
        if (pdf == null) {
            PaperBackground(paper, Modifier.fillMaxSize())
        } else {
            PdfBackground(pdf, controller.placement, Modifier.fillMaxSize())
            pdf.error?.let { Text(it, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error) }
        }
        AndroidView(factory = { anchor }, modifier = Modifier.fillMaxSize())
    }
}

/** One canvas's engine and, for a PDF, its page viewport (owned by the ink thread). */
private class Rig(val layer: InkLayer, val viewport: PageViewport?)

private val Backdrop = Color(0xFF8A8F98)

/** The PDF page at its placement, sharp detail on top when zoomed. */
@Composable
private fun PdfBackground(pdf: PdfDocument, placement: PagePlacement?, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Backdrop)
        val p = placement ?: return@Canvas
        @Suppress("UNUSED_VARIABLE") val v = pdf.version  // redraw when a bitmap lands
        drawRect(Color.White, Offset(p.left, p.top), Size(p.width, p.height))
        pdf.page(p.page, p.fitWidth.roundToInt())?.let { bmp ->
            drawBitmap(bmp.asImageBitmap(), bmp.width, bmp.height, p.left, p.top, p.width, p.height)
        }
        pdf.detail?.takeIf { it.index == p.page }?.let { d ->
            // Rendered for d.pageRect; map it to where the page is now (exact once settled).
            val k = p.width / d.pageRect.width()
            val x = p.left + (d.visible.left - d.pageRect.left) * k
            val y = p.top + (d.visible.top - d.pageRect.top) * k
            drawBitmap(d.bitmap.asImageBitmap(), d.bitmap.width, d.bitmap.height, x, y, d.visible.width() * k, d.visible.height() * k)
        }
        drawRect(Color(0x33000000), Offset(p.left, p.top), Size(p.width, p.height), style = Stroke(1f))
    }
}

private fun DrawScope.drawBitmap(
    img: androidx.compose.ui.graphics.ImageBitmap,
    srcW: Int,
    srcH: Int,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) {
    drawImage(
        img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium,
    )
}
