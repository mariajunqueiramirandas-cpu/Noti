package app.noti.ui.common

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import app.noti.data.EndlessCanvas
import app.noti.data.InkFile
import app.noti.data.Tool
import app.noti.data.Paper
import app.noti.ink.InkLayer
import app.noti.ink.PenButton
import app.noti.ink.StylusButtonOptions
import app.noti.pdf.PageViewport
import app.noti.pdf.PagePlacement
import app.noti.pdf.PageImages
import app.noti.pdf.PdfDocument
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the pen draws with. [pressureGamma] null = constant width. */
data class BrushSpec(
    val argb: Long,
    val alpha: Float,
    val widthPx: Float,
    val pressureGamma: Float?,
    val tool: Tool = Tool.Pen,
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
    var documentState by mutableStateOf(DoubleArray(8))
        internal set
    val canUndo get() = documentState[1] != 0.0
    val canRedo get() = documentState[2] != 0.0
    val page get() = placement?.page ?: 0
    internal var requestedPage by mutableStateOf<Int?>(null)

    internal var requestedScroll by mutableStateOf<Float?>(null)
    internal var resetZoomRequested by mutableStateOf(false)

    fun scrollTo(fraction: Float) { requestedScroll = fraction }
    fun resetZoom() { resetZoomRequested = true }

    fun goTo(page: Int) {
        requestedPage = page
    }
}

/**
 * Paper (or a PDF page) plus the low-latency ink engine on top of it.
 *
 * The engine puts its own input window over this composable's bounds, so nothing
 * touchable may be drawn over it in the activity window. Floating controls use
 * Compose Popup sub-panel windows above the ink panel. Each
 * InkCanvas owns one engine instance and writes canonical snapshots through [inkFile].
 *
 * Fingers go through the engine's palm rejection first: only deliberate touches pan,
 * zoom or turn PDF pages (or draw, with [PalmSpec.fingerDrawing]).
 *
 * Pages scroll continuously; ink stays in page coordinates through zoom, pan and rotation.
 * With a [sheetAspect] there are no pages: the Endless paper's free canvas, one sheet
 * that starts this tall (height / width) and grows as you go (see [EndlessCanvas]).
 *
 * The native input window follows the anchor's global position as well as its size.
 * Document scrolling stays inside this fixed, clipped viewport.
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
    pageCount: Int = 1,
    sheetAspect: Float? = null,
    inkFile: InkFile? = null,
    tapUndo: Boolean = false,
    onDocumentState: ((DoubleArray) -> Unit)? = null,
    onRestoreError: (() -> Unit)? = null,
    topClearance: Dp = 16.dp,
    bottomClearance: Dp = 16.dp,
    buttons: StylusButtonOptions = StylusButtonOptions(),
    onButtonClick: ((PenButton) -> Unit)? = null,
    onButtonDiagnostic: ((String) -> Unit)? = null,
    /** A Study board's cards and pages, drawn on the sheet under the ink. */
    board: app.noti.study.BoardLayer? = null,
    /** A one-finger tap on the canvas, in its px (main thread). */
    onTap: ((Float, Float) -> Unit)? = null,
) {
    // Previews and screenshot tests have no native engine: paper only.
    if (LocalInspectionMode.current) {
        PaperBackground(paper, modifier)
        return
    }
    val context = LocalContext.current
    val marginPx = with(LocalDensity.current) { 16.dp.toPx() }
    val screenPx = with(LocalDensity.current) { EndlessCanvas.BASE_DP.dp.toPx() }
    val sheet = sheetAspect != null && pdf == null
    val anchor = remember { View(context) }
    val doubleTap = rememberUpdatedState(onTwoFingerDoubleTap)
    val onModel = rememberUpdatedState(palm.onModel)
    val undoTap = rememberUpdatedState(tapUndo)
    val documentState = rememberUpdatedState(onDocumentState)
    val restoreError = rememberUpdatedState(onRestoreError)
    val buttonClick = rememberUpdatedState(onButtonClick)
    val buttonDiagnostic = rememberUpdatedState(onButtonDiagnostic)
    val tap = rememberUpdatedState(onTap)
    val controller = pages ?: remember { PageController() }

    val rig = remember(pdf, inkFile) {
        val main = Handler(Looper.getMainLooper())
        // Placement updates come from the ink thread at up to the touch rate; the UI
        // takes the latest once per main-thread turn.
        val latest = AtomicReference<PagePlacement?>(null)
        val posted = AtomicBoolean(false)
        lateinit var created: InkLayer
        val viewport = PageViewport(marginPx) { p ->
            created.setPages(p.pageArray())
            latest.set(p)
            if (posted.compareAndSet(false, true)) {
                main.post {
                    posted.set(false)
                    latest.get()?.let { controller.placement = it }
                }
            }
        }
        // Not drawn yet (no canvas size or document), so safe before the ink thread owns it.
        if (sheet) viewport.setSheet(screenPx, EndlessCanvas.SPAN, EndlessCanvas.HOME_Y, EndlessCanvas.GROW)
        val gestures = CanvasGestures(
            ViewConfiguration.get(context).scaledTouchSlop.toFloat(), viewport,
            onTwoFingerTap = { if (undoTap.value) created.documentAction(0) },
            onTwoFingerDoubleTap = { main.post { doubleTap.value?.invoke() } },
            onTap = { x, y -> tap.value?.invoke(x, y) },
        )
        created = InkLayer(
            anchor,
            gestures = gestures,
            fingerDrawing = palm.fingerDrawing,
            palmModel = palm.model,
            onPalmModel = { m -> main.post { onModel.value?.invoke(m) } },
            initialInk = inkFile?.initial,
            onSnapshot = inkFile?.let { file -> { bytes -> file.save(bytes) } },
            onDocumentState = { controller.documentState = it; documentState.value?.invoke(it) },
            onRestoreError = { restoreError.value?.invoke() },
            stylusOptions = buttons,
            onButtonClick = { buttonClick.value?.invoke(it) },
            // Normal writing never formats or posts diagnostic events.
            onButtonDiagnostic = if (onButtonDiagnostic == null) null else { line -> buttonDiagnostic.value?.invoke(line) },
        )
        Rig(created, viewport, gestures)
    }
    val layer = rig.layer
    DisposableEffect(layer) {
        onLayer?.invoke(layer)
        onDispose {
            onLayer?.invoke(null)
            rig.gestures.close()
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
        layer.setTool(brush.tool.ordinal)
        if (brush.pressureGamma == null) layer.setPressureCurve(1f, 1f) else layer.setPressureCurve(brush.pressureGamma, 0.12f)
        layer.setPredictor(engine.predictor)
        layer.setDisplayTiming(panelMs = engine.inkFeelMs)
        layer.setFingerDrawing(palm.fingerDrawing)
        layer.setStylusOptions(buttons)
    }

    val sizes = pdf?.pageSizes
    val aspects = remember(sizes, pageCount, paper.size, sheet, sheetAspect) {
        if (sheet) listOf(sheetAspect!!)
        else sizes?.map { it.height / it.width }
            ?: List(pageCount) { if (paper.size == app.noti.data.PaperSize.Letter) 11f / 8.5f else 1.4142f }
    }
    LaunchedEffect(aspects, controller.requestedPage) {
        if (aspects.isNotEmpty()) {
            val target = controller.requestedPage
            layer.runOnInkThread {
                rig.viewport.setDocument(aspects.toFloatArray())
                if (target != null) { rig.gestures.stopMotion(); rig.viewport.goTo(target) }
            }
            controller.requestedPage = null
        }
    }
    val topPx = with(LocalDensity.current) { topClearance.toPx() }
    val bottomPx = with(LocalDensity.current) { bottomClearance.toPx() }
    LaunchedEffect(topPx, bottomPx) {
        layer.runOnInkThread { rig.viewport.setVerticalInsets(topPx, bottomPx) }
    }
    LaunchedEffect(controller.requestedScroll, controller.resetZoomRequested) {
        val scroll = controller.requestedScroll
        val reset = controller.resetZoomRequested
        if (scroll != null || reset) layer.runOnInkThread {
            rig.gestures.stopMotion()
            if (reset) rig.viewport.resetZoom()
            if (scroll != null) rig.viewport.scrollTo(scroll)
        }
        controller.requestedScroll = null
        controller.resetZoomRequested = false
    }
    // Ink follows the document in the same display frame: the engine's ink layer moves
    // with the placement the canvas is about to draw (Android 14+, see InkLayer).
    DisposableEffect(layer, controller) {
        layer.setHostFrameSource {
            // Deliver pending state changes first, so the canvas redraws the placement
            // read here in this very frame.
            Snapshot.sendApplyNotifications()
            controller.placement?.pageArray()
        }
        onDispose { layer.setHostFrameSource(null) }
    }

    DocumentViewport(modifier.onSizeChanged { size -> layer.runOnInkThread { rig.viewport.setCanvas(size.width, size.height) } }) {
        // Read in the draw phase only: scrolling and zooming redraw, never recompose.
        DocumentBackground(paper, pdf, { controller.placement }, Modifier.fillMaxSize(), sheet = sheet, board = board)
        if (pdf != null) {
            pdf.error?.let { Text(it, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error) }
        }
        val selection = controller.documentState
        if (selection[3] > 0) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF4167C4), Offset(selection[4].toFloat() - 6, selection[5].toFloat() - 6),
                    Size((selection[6] - selection[4]).toFloat() + 12, (selection[7] - selection[5]).toFloat() + 12),
                    style = Stroke(2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 5f))))
            }
        }
        AndroidView(factory = { anchor }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A drawing surface does not inherit a clip from its layout bounds in Compose.
 * Clip the entire document subtree, including PDF bitmaps, paper, shadows and
 * selection outlines, before it can paint over the toolbar or system insets.
 */
@Composable
internal fun DocumentViewport(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clipToBounds(), content = content)
}

/** One canvas's engine and, for a PDF, its page viewport (owned by the ink thread). */
private class Rig(val layer: InkLayer, val viewport: PageViewport, val gestures: CanvasGestures)

/** The visible pages as the engine's flat [page, x, y, width, height] list. */
internal fun PagePlacement.pageArray(): FloatArray {
    val out = FloatArray(visiblePages.size * 5)
    visiblePages.forEachIndexed { i, p ->
        out[i * 5] = p.page.toFloat(); out[i * 5 + 1] = p.left; out[i * 5 + 2] = p.top
        out[i * 5 + 3] = p.width; out[i * 5 + 4] = p.height
    }
    return out
}

/**
 * Renders only pages intersecting the viewport; paper, PDF and ink share the same transform.
 * [placement] is read while drawing, so a moving document redraws without recomposing.
 * Positions are not rounded: the page sits exactly where the ink engine puts its ink.
 * A [sheet] (the Endless canvas) is paper edge to edge: no page shadow or border.
 */
@Composable
internal fun DocumentBackground(paper: Paper, images: PageImages?, placement: () -> PagePlacement?, modifier: Modifier,
    sheet: Boolean = false, board: app.noti.study.BoardLayer? = null) {
    val backdrop = MaterialTheme.colorScheme.surfaceContainerLow
    Canvas(modifier) {
        drawRect(backdrop)
        val current = placement() ?: return@Canvas
        if (sheet) {
            current.visiblePages.forEach { p ->
                drawPaperSheet(paper, p.left, p.top, p.width, p.height, p.width / (1000f * EndlessCanvas.SPAN))
                if (board != null) drawIntoCanvas { canvas -> board.draw(canvas.nativeCanvas, p) }
            }
            return@Canvas
        }
        current.visiblePages.forEach { p ->
            drawRect(Color(0x12000000), Offset(p.left + 2f, p.top + 4f), Size(p.width, p.height))
            if (images == null) {
                withTransform({ translate(p.left, p.top) }) {
                    clipRect(0f, 0f, p.width, p.height) {
                        drawPaperPage(paper, p.width, p.height, p.width / 1000f)
                    }
                }
            } else {
                drawRect(Color.White, Offset(p.left, p.top), Size(p.width, p.height))
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val save = native.save()
                    native.clipRect(p.left, p.top, p.left + p.width, p.top + p.height)
                    images.drawPage(native, p)
                    native.restoreToCount(save)
                }
            }
            drawRect(Color(0x18000000), Offset(p.left, p.top), Size(p.width, p.height), style = Stroke(1f))
        }
        images?.endFrame(current)
    }
}
