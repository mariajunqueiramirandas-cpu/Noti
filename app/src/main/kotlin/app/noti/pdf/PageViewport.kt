package app.noti.pdf

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A page in canvas pixels. [visiblePages] is populated on the current-page update, and
 * [nearbyPages] adds the pages within about a screen above and below (for prefetching).
 */
data class PagePlacement(
    val page: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val zoom: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val settled: Boolean,
    val visiblePages: List<PagePlacement> = emptyList(),
    val scrollFraction: Float = 0f,
    val viewportFraction: Float = 1f,
    val nearbyPages: List<PagePlacement> = emptyList(),
) {
    val fitWidth get() = width / zoom
}

/**
 * A continuous, width-fitted vertical document. Owned by the ink thread, so page
 * geometry reaches native ink before the next input batch. Only visible pages render.
 */
class PageViewport(private val marginPx: Float, private val onChange: (PagePlacement) -> Unit) {
    private var aspects = FloatArray(0)
    private var starts = FloatArray(0)
    private var canvasW = 0
    private var canvasH = 0
    private var page = 0
    private var zoom = 1f
    private var left = 0f
    private var topInset = marginPx
    private var bottomInset = marginPx
    private var documentTop = marginPx
    private var moving = false
    private var totalHeight = 0f
    private var baseWidth = 1f
    val pageCount get() = aspects.size

    fun setDocument(aspects: FloatArray) {
        if (this.aspects.contentEquals(aspects)) return
        this.aspects = aspects.map { if (it.isFinite() && it > 0) it else 1.4142f }.toFloatArray()
        page = page.coerceIn(0, (aspects.size - 1).coerceAtLeast(0))
        layout()
        emit()
    }

    fun setCanvas(width: Int, height: Int) {
        if (width == canvasW && height == canvasH) return
        val local = if (starts.isNotEmpty()) (-documentTop / zoom - starts[page]) / baseWidth else 0f
        canvasW = width; canvasH = height
        layout()
        if (starts.isNotEmpty()) documentTop = -(starts[page] + local * baseWidth) * zoom
        left = (canvasW - baseWidth * zoom) / 2
        emit()
    }

    fun goTo(index: Int) {
        if (!ready()) return
        page = index.coerceIn(aspects.indices)
        zoom = 1f
        left = marginPx
        documentTop = topInset - starts[page]
        emit()
    }

    fun setVerticalInsets(top: Float, bottom: Float) {
        if (!top.isFinite() || !bottom.isFinite()) return
        val nextTop = top.coerceAtLeast(marginPx)
        val nextBottom = bottom.coerceAtLeast(marginPx)
        if (nextTop == topInset && nextBottom == bottomInset) return
        documentTop += nextTop - topInset
        topInset = nextTop; bottomInset = nextBottom
        emit()
    }

    fun scrollTo(fraction: Float) {
        if (!ready() || !fraction.isFinite()) return
        val range = (totalHeight * zoom + topInset + bottomInset - canvasH).coerceAtLeast(0f)
        documentTop = topInset - fraction.coerceIn(0f, 1f) * range
        emit()
    }

    fun resetZoom() {
        if (!ready()) return
        // Keep the same document point under the viewport centre.
        val centre = (canvasH / 2f - documentTop) / zoom
        zoom = 1f
        left = marginPx
        documentTop = canvasH / 2f - centre
        emit()
    }

    fun begin() { moving = true }
    fun pan(dx: Float, dy: Float) {
        if (!ready() || !dx.isFinite() || !dy.isFinite()) return
        left += dx; documentTop += dy
        emit()
    }

    fun pinch(cx: Float, cy: Float, scale: Float, dx: Float, dy: Float) {
        if (!ready() || !scale.isFinite() || scale <= 0 || !cx.isFinite() || !cy.isFinite() || !dx.isFinite() || !dy.isFinite()) return
        val nextZoom = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = nextZoom / zoom
        left = cx - (cx - left - dx) * k
        documentTop = cy - (cy - documentTop - dy) * k
        zoom = nextZoom
        emit()
    }

    fun end() { moving = false; emit() }
    private fun ready() = canvasW > 0 && canvasH > 0 && aspects.isNotEmpty()
    private fun layout() {
        // Whole pixels at 100%, so a page's pixels map 1:1 onto the screen's.
        baseWidth = floor(canvasW - 2 * marginPx).coerceAtLeast(1f)
        starts = FloatArray(aspects.size)
        var y = 0f
        aspects.forEachIndexed { i, aspect -> starts[i] = y; y += baseWidth * aspect + marginPx }
        totalHeight = (y - marginPx).coerceAtLeast(0f)
    }

    private fun emit() {
        if (!ready()) return
        val width = baseWidth * zoom
        left = if (width <= canvasW - 2 * marginPx) (canvasW - width) / 2 else left.coerceIn(canvasW - width - marginPx, marginPx)
        val height = totalHeight * zoom
        val availableHeight = (canvasH - topInset - bottomInset).coerceAtLeast(1f)
        documentTop = if (height < availableHeight) topInset + (availableHeight - height) / 2
            else documentTop.coerceIn((canvasH - height - bottomInset).coerceAtMost(topInset), topInset)
        // Pages sit on whole pixels: sharp PDF text at rest, and a pan is an integral
        // translation that the ink layer can follow exactly in the same display frame.
        val x = left.roundToInt().toFloat()
        val y = documentTop.roundToInt().toFloat()
        // Binary search the page intersecting the viewport centre.
        val centre = (canvasH / 2f - y) / zoom
        var lo = 0; var hi = starts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= centre) lo = mid else hi = mid - 1
        }
        page = lo
        fun placement(i: Int) = PagePlacement(i, x, (y + starts[i] * zoom).roundToInt().toFloat(), width,
            width * aspects[i], zoom, canvasW, canvasH, settled = !moving)
        val visible = ArrayList<PagePlacement>()
        val nearby = ArrayList<PagePlacement>()
        // Walk only pages around the current one, even in a thousand-page PDF.
        val above = -canvasH.toFloat()
        val below = 2f * canvasH
        var first = page
        while (first > 0 && y + (starts[first - 1] + baseWidth * aspects[first - 1]) * zoom >= above) first--
        var i = first
        while (i < aspects.size) {
            val p = placement(i)
            if (p.top > below) break
            if (p.top + p.height >= above) nearby.add(p)
            if (p.top <= canvasH && p.top + p.height >= 0) visible.add(p)
            i++
        }
        val range = (height + topInset + bottomInset - canvasH).coerceAtLeast(0f)
        onChange(placement(page).copy(visiblePages = visible, nearbyPages = nearby,
            scrollFraction = if (range > 0f) ((topInset - y) / range).coerceIn(0f, 1f) else 0f,
            viewportFraction = (availableHeight / height).coerceIn(0f, 1f)))
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 6f
    }
}
