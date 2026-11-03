package app.noti.pdf

import kotlin.math.abs

/** Where the current page sits on the canvas (canvas pixels). */
data class PagePlacement(
    val page: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val zoom: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** False while a finger is moving it; true once it rests. */
    val settled: Boolean,
) {
    /** The page's width at zoom 1 (fitted to the canvas). */
    val fitWidth get() = width / zoom
}

/**
 * One page at a time, fitted to the canvas, zoomable to [MAX_ZOOM]×. At fit zoom a
 * horizontal swipe turns the page; zoomed in, fingers pan within the page.
 *
 * Owned by the ink thread: gestures arrive there, and every change goes straight to the
 * engine (ink follows the page in the same frame) and to [onChange].
 */
class PageViewport(private val marginPx: Float, private val onChange: (PagePlacement) -> Unit) {
    private var aspects = FloatArray(0)  // height / width per page
    private var canvasW = 0
    private var canvasH = 0
    private var page = 0
    private var zoom = 1f
    private var left = 0f
    private var top = 0f
    private var swipe = 0f
    private var moving = false

    val pageCount get() = aspects.size

    fun setDocument(aspects: FloatArray) {
        this.aspects = aspects.copyOf()
        page = page.coerceIn(0, (aspects.size - 1).coerceAtLeast(0))
        relayout()
    }

    fun setCanvas(width: Int, height: Int) {
        if (width == canvasW && height == canvasH) return
        canvasW = width
        canvasH = height
        relayout()
    }

    fun goTo(index: Int) {
        if (aspects.isEmpty()) return
        page = index.coerceIn(0, aspects.size - 1)
        zoom = 1f
        swipe = 0f
        relayout()
    }

    // ---- gestures ----

    fun begin() {
        moving = true
    }

    fun pan(dx: Float, dy: Float) {
        if (!ready()) return
        if (zoom < 1.01f) {
            swipe += dx  // unzoomed: a horizontal swipe, resolved when the fingers lift
        } else {
            left += dx
            top += dy
        }
        clampAndEmit()
    }

    /** Scale by [scale] about ([cx], [cy]) after moving by ([dx], [dy]). */
    fun pinch(cx: Float, cy: Float, scale: Float, dx: Float, dy: Float) {
        if (!ready() || !scale.isFinite() || scale <= 0f) return
        val newZoom = (zoom * scale).coerceIn(1f, MAX_ZOOM)
        val k = newZoom / zoom
        swipe = 0f
        left = cx - (cx - (left + dx)) * k
        top = cy - (cy - (top + dy)) * k
        zoom = newZoom
        clampAndEmit()
    }

    fun end() {
        moving = false
        if (swipe != 0f && ready()) {
            val threshold = canvasW * SWIPE_FRACTION
            if (swipe < -threshold && page < aspects.size - 1) page++
            else if (swipe > threshold && page > 0) page--
            swipe = 0f
            relayout()
        } else {
            clampAndEmit()
        }
    }

    // ---- layout ----

    private fun ready() = canvasW > 0 && canvasH > 0 && aspects.isNotEmpty()

    private fun aspect() = aspects[page]

    private fun fitWidth(): Float {
        val availW = (canvasW - 2 * marginPx).coerceAtLeast(1f)
        val availH = (canvasH - 2 * marginPx).coerceAtLeast(1f)
        return minOf(availW, availH / aspect())
    }

    /** Fit the page (keeping the zoom) and centre it. */
    private fun relayout() {
        if (!ready()) return
        val w = fitWidth() * zoom
        left = (canvasW - w) / 2
        top = (canvasH - w * aspect()) / 2
        clampAndEmit()
    }

    private fun clampAndEmit() {
        if (!ready()) return
        val w = fitWidth() * zoom
        val h = w * aspect()
        left = if (w <= canvasW) (canvasW - w) / 2 + swipe else left.coerceIn(canvasW - w - marginPx, marginPx)
        top = if (h <= canvasH) (canvasH - h) / 2 else top.coerceIn(canvasH - h - marginPx, marginPx)
        if (abs(zoom - 1f) < 1e-3f) zoom = 1f
        onChange(PagePlacement(page, left, top, w, h, zoom, canvasW, canvasH, settled = !moving))
    }

    companion object {
        const val MAX_ZOOM = 6f
        private const val SWIPE_FRACTION = 0.18f
    }
}
