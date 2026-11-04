package app.noti.pdf

/** A page in canvas pixels. [visiblePages] is populated on the current-page update. */
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
        documentTop = marginPx - starts[page]
        emit()
    }

    fun begin() { moving = true }
    fun pan(dx: Float, dy: Float) {
        if (!ready() || !dx.isFinite() || !dy.isFinite()) return
        left += dx; documentTop += dy
        emit()
    }

    fun pinch(cx: Float, cy: Float, scale: Float, dx: Float, dy: Float) {
        if (!ready() || !scale.isFinite() || scale <= 0 || !cx.isFinite() || !cy.isFinite()) return
        val nextZoom = (zoom * scale).coerceIn(1f, MAX_ZOOM)
        val k = nextZoom / zoom
        left = cx - (cx - left - dx) * k
        documentTop = cy - (cy - documentTop - dy) * k
        zoom = nextZoom
        emit()
    }

    fun end() { moving = false; emit() }
    private fun ready() = canvasW > 0 && canvasH > 0 && aspects.isNotEmpty()
    private fun layout() {
        baseWidth = (canvasW - 2 * marginPx).coerceAtLeast(1f)
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
        documentTop = if (height < canvasH - 2 * marginPx) (canvasH - height) / 2 else documentTop.coerceIn(canvasH - height - marginPx, marginPx)
        // Binary search the page intersecting the viewport centre.
        val centre = (canvasH / 2f - documentTop) / zoom
        var lo = 0; var hi = starts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= centre) lo = mid else hi = mid - 1
        }
        page = lo
        fun placement(i: Int) = PagePlacement(i, left, documentTop + starts[i] * zoom, width,
            width * aspects[i], zoom, canvasW, canvasH, settled = !moving)
        val visible = ArrayList<PagePlacement>()
        // Walk only pages around the current one, even in a thousand-page PDF.
        var first = page
        while (first > 0 && documentTop + (starts[first - 1] + baseWidth * aspects[first - 1]) * zoom >= 0) first--
        var i = first
        while (i < aspects.size) {
            val p = placement(i)
            if (p.top > canvasH) break
            if (p.top + p.height >= 0) visible.add(p)
            i++
        }
        onChange(placement(page).copy(visiblePages = visible))
    }

    companion object { const val MAX_ZOOM = 6f }
}
