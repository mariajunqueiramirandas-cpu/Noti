package app.noti.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.FormWidgetInfo
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.SizeF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Draws the content of document pages; [PdfDocument] is the production implementation. */
interface PageImages {
    /** Draws page [p] (canvas px) into [canvas]. The caller clips to the page and paints it white. */
    fun drawPage(canvas: Canvas, p: PagePlacement)

    /** Called once per frame, after every visible page of [placement] was drawn. */
    fun endFrame(placement: PagePlacement) {}
}

/**
 * An open PDF, rendered with the platform's PdfRenderer (PDFium) on its own thread, so a
 * heavy page never touches the UI thread, let alone the ink thread.
 *
 * Pages are drawn from two kinds of images, cached on the main thread:
 *  - a small **preview** of each page (a few ms to render), drawn first, so a page
 *    scrolled into view shows its content right away;
 *  - **tiles** of [TILE] px at the zoom level closest to the screen's resolution
 *    (levels are √2 apart, anchored at the fit width, so 100%, 200% and 400% are
 *    pixel-exact), so text stays sharp at any zoom and panning only renders the tiles
 *    that come into view. While a level's tiles render, the tiles of the neighbouring
 *    levels that are already cached are drawn scaled.
 *
 * Every frame rebuilds a priority list of what is missing: previews of visible pages,
 * visible tiles (centre first), then previews and tiles about a screen ahead in every
 * direction. The worker always takes the most urgent item of the newest list, so work
 * for pages that already scrolled away is simply dropped. A page stays open between
 * its tiles. Finished images are uploaded to the GPU on the worker (hardware bitmaps),
 * so drawing a new tile never stalls the render thread.
 *
 * The page count is known immediately; page sizes start as the first page's size and are
 * measured in the background (most PDFs have a single page size).
 *
 * Main thread only, except where noted. Bitmaps land through [version], a Compose state,
 * so whatever draws them in its draw phase is redrawn.
 */
class PdfDocument(file: File) : PageImages {
    private val main = Handler(Looper.getMainLooper())

    /** Page sizes in PDF points; empty until the document is open. */
    var pageSizes by mutableStateOf<List<SizeF>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped whenever a new image is available. */
    var version by mutableIntStateOf(0)
        private set

    val pageCount get() = pageSizes.size

    /** Height / width of a page (1.414 for A4 portrait). */
    fun aspect(index: Int): Float = pageSizes.getOrNull(index)?.let { it.height / it.width } ?: 1.4142f

    // ---- images (main thread) ----

    /** Tiles: [base] is the fit width the [level] is relative to. Previews: [level] is the preview width. */
    private data class Key(val tile: Boolean, val page: Int, val base: Int, val level: Int, val col: Int, val row: Int)
    private class Entry(val bitmap: Bitmap, val bytes: Long, var used: Long)

    private val cache = HashMap<Key, Entry>()
    private var tileBytes = 0L
    private var previewBytes = 0L
    private var frame = 1L
    private var closed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val src = Rect()
    private val dst = RectF()
    // This frame's missing images, by urgency.
    private val missingPreviews = ArrayList<Key>()
    private val missingTiles = ArrayList<Key>()
    private val missingDistance = HashMap<Key, Float>()

    // ---- shared with the worker (guarded by [lock]) ----
    private val lock = Object()
    private var wanted = LinkedHashSet<Key>()
    private val extra = LinkedHashSet<Key>()   // thumbnails for the pages panel
    private val claimed = HashSet<Key>()       // being rendered, or rendered and on its way
    private val failed = HashSet<Key>()
    private var stop = false
    /** One-off work for the worker's open renderer (Type on lines), ahead of any tile. */
    private val jobs = ArrayDeque<(Pages) -> Unit>()

    init {
        Thread({ work(file) }, "noti-pdf").start()
    }

    // ---------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------

    override fun drawPage(canvas: Canvas, p: PagePlacement) {
        @Suppress("UNUSED_VARIABLE") val v = version  // redraw when images land
        if (closed || p.page !in pageSizes.indices || p.width <= 0f || p.height <= 0f) return
        val previewKey = Key(false, p.page, 0, PREVIEW_WIDTH, 0, 0)
        val preview = cache[previewKey]
        if (preview != null) {
            preview.used = frame
            dst.set(p.left, p.top, p.left + p.width, p.top + p.height)
            canvas.drawBitmap(preview.bitmap, null, dst, paint)
        } else if (previewKey !in missingDistance) {
            missingPreviews += previewKey
            missingDistance[previewKey] = 0f
        }
        if (!needsTiles(p.width)) return
        val l = max(p.left, 0f)
        val t = max(p.top, 0f)
        val r = min(p.left + p.width, p.canvasWidth.toFloat())
        val b = min(p.top + p.height, p.canvasHeight.toFloat())
        if (r <= l || b <= t) return
        val base = baseOf(p)
        val target = levelFor(p.width, base)
        // Cached neighbouring levels first (coarse to fine), then the sharp level on top.
        for (level in intArrayOf(target - 2, target - 1, target + 1)) {
            if (level in MIN_LEVEL..MAX_LEVEL && levelWidth(base, level) > PREVIEW_WIDTH) {
                tiles(canvas, p, base, level, l, t, r, b, collect = false)
            }
        }
        tiles(canvas, p, base, target, l, t, r, b, collect = true)
    }

    override fun endFrame(placement: PagePlacement) {
        if (closed) return
        val list = LinkedHashSet<Key>(missingPreviews.size + missingTiles.size + 64)
        list.addAll(missingPreviews)
        missingTiles.sortBy { missingDistance[it] }
        list.addAll(missingTiles)
        // Ahead of time: previews of the pages around, then sharp tiles about a screen
        // beyond the viewport in every direction.
        val cx = placement.canvasWidth / 2f
        val cy = placement.canvasHeight / 2f
        val nearby = placement.nearbyPages.ifEmpty { placement.visiblePages }
        val around = nearby.map { it.page }.toMutableSet().apply { for (d in -2..2) add(placement.page + d) }
        around.sortedBy { abs(it - placement.page) }.forEach { i ->
            if (i !in pageSizes.indices) return@forEach
            val key = Key(false, i, 0, PREVIEW_WIDTH, 0, 0)
            val e = cache[key]
            if (e != null) e.used = frame else list += key
        }
        val ahead = ArrayList<Key>()
        val aheadDistance = HashMap<Key, Float>()
        val w = placement.canvasWidth.toFloat()
        val h = placement.canvasHeight.toFloat()
        for (p in nearby) {
            if (!needsTiles(p.width)) continue
            val base = baseOf(p)
            val level = levelFor(p.width, base)
            val l = max(p.left, -0.25f * w)
            val t = max(p.top, -0.75f * h)
            val r = min(p.left + p.width, 1.25f * w)
            val b = min(p.top + p.height, 1.75f * h)
            if (r <= l || b <= t) continue
            forTiles(p, base, level, l, t, r, b) { key, x, y ->
                val e = cache[key]
                if (e != null) e.used = frame
                else if (key !in list) {
                    ahead += key
                    aheadDistance[key] = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                }
            }
        }
        ahead.sortBy { aheadDistance[it] }
        for (k in 0 until min(ahead.size, MAX_AHEAD)) list += ahead[k]
        synchronized(lock) {
            list.removeAll(failed)
            wanted = list
            lock.notifyAll()
        }
        missingPreviews.clear()
        missingTiles.clear()
        missingDistance.clear()
        frame++
    }

    /** The page's preview, for the pages panel; requested (at low priority) if missing. */
    fun thumbnail(index: Int, @Suppress("UNUSED_PARAMETER") widthPx: Int): Bitmap? {
        if (closed || index !in pageSizes.indices) return null
        val key = Key(false, index, 0, PREVIEW_WIDTH, 0, 0)
        cache[key]?.let { it.used = frame; return it.bitmap }
        synchronized(lock) {
            if (key !in failed && key !in extra) {
                extra += key
                while (extra.size > MAX_EXTRA) extra.remove(extra.first())
                lock.notifyAll()
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // Type on lines: looking inside a page
    // ---------------------------------------------------------------------------------

    /** Millimetres of paper per page unit (a page is 1000 units wide). */
    fun mmPerUnit(page: Int): Float = (pageSizes.getOrNull(page)?.width ?: 595f) * 25.4f / 72f / 1000f

    private fun enqueue(job: (Pages) -> Unit): Boolean = synchronized(lock) {
        if (stop) return false
        jobs.addLast(job)
        lock.notifyAll()
        true
    }

    /**
     * Renders [left], [top], [right], [bottom] (page units) of page [page] in grey at about
     * [pxPerMm], for finding lines in. Runs ahead of tiles on the worker; [onResult] gets
     * null if the page can't be rendered. Main thread.
     */
    fun renderRegion(page: Int, left: Float, top: Float, right: Float, bottom: Float, pxPerMm: Float,
        onResult: (RegionImage?) -> Unit) {
        val queued = enqueue { pages ->
            val image = runCatching { region(pages, page, left, top, right, bottom, pxPerMm) }.getOrNull()
            main.post { if (!closed) onResult(image) }
        }
        if (!queued) onResult(null)
    }

    private fun region(pages: Pages, index: Int, left: Float, top: Float, right: Float, bottom: Float, pxPerMm: Float): RegionImage? {
        if (right <= left || bottom <= top) return null
        val page = pages.page(index)
        val pw = page.width.toFloat().coerceAtLeast(1f)
        val ptPerUnit = pw / 1000f
        var pxPerPt = pxPerMm * 25.4f / 72f
        val wPt = (right - left) * ptPerUnit; val hPt = (bottom - top) * ptPerUnit
        // Big regions are rendered coarser rather than huge.
        val maxSide = 2600f
        if (wPt * pxPerPt > maxSide) pxPerPt = maxSide / wPt
        if (hPt * pxPerPt > maxSide * 1.6f) pxPerPt = maxSide * 1.6f / hPt
        val w = ceil(wPt * pxPerPt).toInt().coerceAtLeast(1); val h = ceil(hPt * pxPerPt).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setScale(pxPerPt, pxPerPt)
            m.postTranslate(-left * ptPerUnit * pxPerPt, -top * ptPerUnit * pxPerPt)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            return RegionImage(app.noti.lines.Raster.fromArgb(w, h, px), left, top, 1f / (ptPerUnit * pxPerPt), pxPerPt * 72f / 25.4f)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * The fillable text fields of page [page] (a real PDF form), in page units; empty for a
     * plain PDF or before Android 15. Main thread.
     */
    fun formFields(page: Int, onResult: (List<FormField>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) { onResult(emptyList()); return }
        val queued = enqueue { pages ->
            val fields = runCatching { fields35(pages, page) }.getOrDefault(emptyList())
            main.post { if (!closed) onResult(fields) }
        }
        if (!queued) onResult(emptyList())
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun fields35(pages: Pages, index: Int): List<FormField> {
        val page = pages.page(index)
        val k = 1000f / page.width.toFloat().coerceAtLeast(1f)
        return page.getFormWidgetInfos(intArrayOf(FormWidgetInfo.WIDGET_TYPE_TEXTFIELD))
            .filter { it.isEditableText && !it.isReadOnly }
            .map { f ->
                val r = f.widgetRect
                FormField(r.left * k, r.top * k, r.right * k, r.bottom * k, f.isMultiLineText,
                    f.accessibilityLabel?.takeIf { it.isNotBlank() }, f.maxLength)
            }
    }

    /** The PDF's own text inside each of [boxes] (left, top, right, bottom in page units); null where none or before Android 15. */
    fun textIn(page: Int, boxes: List<FloatArray>, onResult: (List<String?>) -> Unit) {
        if (boxes.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) { onResult(boxes.map { null }); return }
        val queued = enqueue { pages ->
            val out = runCatching { text35(pages, page, boxes) }.getOrDefault(boxes.map { null })
            main.post { if (!closed) onResult(out) }
        }
        if (!queued) onResult(boxes.map { null })
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun text35(pages: Pages, index: Int, boxes: List<FloatArray>): List<String?> {
        val page = pages.page(index)
        val k = page.width.toFloat().coerceAtLeast(1f) / 1000f
        return boxes.map { b ->
            runCatching {
                val y = ((b[1] + b[3]) / 2f * k).toInt()
                val sel = page.selectContent(SelectionBoundary(Point((b[0] * k).toInt() + 1, y)),
                    SelectionBoundary(Point((b[2] * k).toInt() - 1, y)))
                sel?.selectedTextContents?.joinToString(" ") { it.text }?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(lock) {
            stop = true
            jobs.clear()
            wanted = LinkedHashSet()
            extra.clear()
            lock.notifyAll()
        }
        cache.values.forEach { it.bitmap.recycle() }
        cache.clear()
        tileBytes = 0
        previewBytes = 0
    }

    private fun tiles(canvas: Canvas, p: PagePlacement, base: Int, level: Int, l: Float, t: Float, r: Float, b: Float, collect: Boolean) {
        val k = p.width / levelWidth(base, level)
        val cx = p.canvasWidth / 2f
        val cy = p.canvasHeight / 2f
        forTiles(p, base, level, l, t, r, b) { key, x, y ->
            val e = cache[key]
            if (e != null) {
                e.used = frame
                val bmp = e.bitmap
                val cw = bmp.width - 2 * GUTTER
                val ch = bmp.height - 2 * GUTTER
                src.set(GUTTER, GUTTER, GUTTER + cw, GUTTER + ch)
                // Integer sums first, so neighbouring tiles share their edges exactly.
                val x0 = key.col * TILE
                val y0 = key.row * TILE
                dst.set(p.left + x0 * k, p.top + y0 * k, p.left + (x0 + cw) * k, p.top + (y0 + ch) * k)
                canvas.drawBitmap(bmp, src, dst, paint)
            } else if (collect && key !in missingDistance) {
                missingTiles += key
                missingDistance[key] = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            }
        }
    }

    /** Tiles of [level] intersecting the canvas rect [l, t, r, b]; [visit] gets each tile's centre. */
    private inline fun forTiles(p: PagePlacement, base: Int, level: Int, l: Float, t: Float, r: Float, b: Float,
                                visit: (Key, Float, Float) -> Unit) {
        val lw = levelWidth(base, level)
        val lh = ceil(lw * p.height / p.width).toInt().coerceAtLeast(1)
        val k = p.width / lw
        val cols = (lw + TILE - 1) / TILE
        val rows = (lh + TILE - 1) / TILE
        val c0 = floor((l - p.left) / k / TILE).toInt().coerceIn(0, cols - 1)
        val c1 = floor(((r - p.left) / k - 0.01f) / TILE).toInt().coerceIn(0, cols - 1)
        val r0 = floor((t - p.top) / k / TILE).toInt().coerceIn(0, rows - 1)
        val r1 = floor(((b - p.top) / k - 0.01f) / TILE).toInt().coerceIn(0, rows - 1)
        for (row in r0..r1) for (col in c0..c1) {
            visit(Key(true, p.page, base, level, col, row), p.left + (col + 0.5f) * TILE * k, p.top + (row + 0.5f) * TILE * k)
        }
    }

    // Worker → main.
    private fun deliver(key: Key, bmp: Bitmap?) {
        synchronized(lock) {
            claimed.remove(key)
            wanted.remove(key)
            extra.remove(key)
            if (bmp == null) failed += key
        }
        if (closed) { bmp?.recycle(); return }
        if (bmp == null) return
        val bytes = bmp.width.toLong() * bmp.height * 4
        cache.put(key, Entry(bmp, bytes, frame))?.let { old ->
            if (key.tile) tileBytes -= old.bytes else previewBytes -= old.bytes
            old.bitmap.recycle()
        }
        if (key.tile) tileBytes += bytes else previewBytes += bytes
        trim(key.tile)
        version++
    }

    /** Evicts the least recently drawn images of one kind; never anything drawn in the last frame. */
    private fun trim(tiles: Boolean) {
        val budget = if (tiles) TILE_CACHE_BYTES else PREVIEW_CACHE_BYTES
        if ((if (tiles) tileBytes else previewBytes) <= budget) return
        val candidates = cache.entries.filter { it.key.tile == tiles && it.value.used < frame - 1 }.sortedBy { it.value.used }
        for (c in candidates) {
            if ((if (tiles) tileBytes else previewBytes) <= budget) break
            cache.remove(c.key)
            if (tiles) tileBytes -= c.value.bytes else previewBytes -= c.value.bytes
            c.value.bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------------------------
    // Worker thread
    // ---------------------------------------------------------------------------------

    private fun nextLocked(): Key? {
        for (k in wanted) if (k !in claimed) return k
        for (k in extra) if (k !in claimed) return k
        return null
    }

    private fun work(file: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var pages: Pages? = null
        try {
            val f = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { fd = it }
            val r = PdfRenderer(f).also { renderer = it }
            val count = r.pageCount
            if (count <= 0) {
                main.post { if (!closed) error = "This PDF has no pages." }
                return
            }
            val doc = Pages(r, count).also { pages = it }
            val initial = doc.sizes.toList()
            main.post { if (!closed) pageSizes = initial }
            var sinceScan = 0
            while (true) {
                var job: Key? = null
                var task: ((Pages) -> Unit)? = null
                synchronized(lock) {
                    while (!stop && jobs.isEmpty() && nextLocked() == null && doc.measured >= count) lock.wait()
                    if (!stop) {
                        task = jobs.removeFirstOrNull()
                        if (task == null) job = nextLocked()?.also { claimed += it }
                    }
                }
                if (stop) break
                val t = task
                if (t != null) {
                    try { t(doc) } catch (e: Exception) { /* each job reports its own failure */ }
                    continue
                }
                if (doc.measured < count && (job == null || sinceScan >= SCAN_EVERY)) {
                    if (doc.measureNext()) doc.learned = true
                    sinceScan = 0
                }
                val key = job
                var bmp: Bitmap? = null
                if (key != null) {
                    sinceScan++
                    bmp = try { render(doc, key) } catch (e: Exception) { null }
                }
                if (doc.learned) {
                    doc.learned = false
                    val sizes = doc.sizes.toList()
                    main.post { if (!closed) pageSizes = sizes }
                }
                if (key != null) main.post { deliver(key, bmp) }
            }
        } catch (e: Exception) {
            main.post { if (!closed) error = "Couldn't open this PDF." }
        } finally {
            pages?.closePage()
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }

    /** Worker-thread page access: one page open at a time (a PdfRenderer rule), kept open between tiles. */
    private class Pages(val renderer: PdfRenderer, count: Int) {
        val sizes: Array<SizeF>
        var measured = 1
        var learned = false
        private var open: PdfRenderer.Page? = null

        init {
            val first = page(0).let { SizeF(it.width.toFloat().coerceAtLeast(1f), it.height.toFloat().coerceAtLeast(1f)) }
            sizes = Array(count) { first }
        }

        fun page(index: Int): PdfRenderer.Page {
            open?.let { if (it.index == index) return it }
            closePage()
            return renderer.openPage(index).also { open = it }
        }

        fun closePage() {
            runCatching { open?.close() }
            open = null
        }

        /** Records [page]'s real size; true if it differs from the assumed one. */
        fun learn(page: PdfRenderer.Page): Boolean {
            val s = SizeF(page.width.toFloat().coerceAtLeast(1f), page.height.toFloat().coerceAtLeast(1f))
            val old = sizes[page.index]
            if (abs(old.width - s.width) < 0.5f && abs(old.height - s.height) < 0.5f) return false
            sizes[page.index] = s
            return true
        }

        /** Measures the next batch of pages; true if any size changed. */
        fun measureNext(): Boolean {
            var changed = false
            val end = min(sizes.size, measured + SCAN_BATCH)
            for (i in measured until end) changed = runCatching { learn(page(i)) }.getOrDefault(false) or changed
            measured = end
            return changed
        }
    }

    private fun render(pages: Pages, key: Key): Bitmap {
        val page = pages.page(key.page)
        if (pages.learn(page)) pages.learned = true
        val pw = page.width.toFloat().coerceAtLeast(1f)
        val ph = page.height.toFloat().coerceAtLeast(1f)
        val m = Matrix()
        if (!key.tile) {
            val w = key.level
            val h = (w * ph / pw).roundToInt().coerceIn(1, 4096)
            m.setScale(w / pw, h / ph)
            return image(w, h) { page.render(it, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
        val lw = levelWidth(key.base, key.level)
        val s = lw / pw
        val lh = ceil(ph * s).toInt()
        val x0 = key.col * TILE
        val y0 = key.row * TILE
        val w = min(TILE, lw - x0).coerceAtLeast(1)
        val h = min(TILE, lh - y0).coerceAtLeast(1)
        // A one-pixel gutter of real content around the tile: filtering at the tile's edge
        // then samples its neighbour's pixels, so no seams show between tiles.
        m.setScale(s, s)
        m.postTranslate((GUTTER - x0).toFloat(), (GUTTER - y0).toFloat())
        return image(w + 2 * GUTTER, h + 2 * GUTTER) { page.render(it, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    private inline fun image(w: Int, h: Int, draw: (Bitmap) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        draw(b)
        // Upload here, not on the render thread at first draw.
        val gpu = runCatching { b.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return b
        b.recycle()
        return gpu
    }

    internal companion object {
        const val TILE = 512
        const val GUTTER = 1
        const val PREVIEW_WIDTH = 600
        const val MIN_LEVEL = -6
        const val MAX_LEVEL = 6
        const val MAX_LEVEL_WIDTH = 24_000
        const val MAX_AHEAD = 48
        const val MAX_EXTRA = 96
        const val SCAN_EVERY = 4
        const val SCAN_BATCH = 16
        const val TILE_CACHE_BYTES = 192L * 1024 * 1024
        const val PREVIEW_CACHE_BYTES = 64L * 1024 * 1024

        /** The fit width (100% zoom) of a placed page, in whole pixels. */
        fun baseOf(p: PagePlacement): Int = (p.width / p.zoom).roundToInt().coerceAtLeast(1)

        /** Page width in px at [level]: [base] · √2^level, so even levels are exact zooms (½×, 1×, 2×, 4×). */
        fun levelWidth(base: Int, level: Int): Int =
            (base * 2.0.pow(level / 2.0)).roundToInt().coerceIn(1, MAX_LEVEL_WIDTH)

        /** The coarsest level about as sharp as the screen for a page [widthPx] wide. */
        fun levelFor(widthPx: Float, base: Int): Int {
            for (level in MIN_LEVEL..MAX_LEVEL) if (levelWidth(base, level) >= widthPx * 0.92f) return level
            return MAX_LEVEL
        }

        /** Below this width the preview alone is sharp enough. */
        fun needsTiles(widthPx: Float) = widthPx > PREVIEW_WIDTH * 1.15f
    }
}

/** Part of a page rendered for line finding: pixel (x, y) is page units ([left] + x · [unitsPerPx], [top] + y · [unitsPerPx]). */
class RegionImage(val raster: app.noti.lines.Raster, val left: Float, val top: Float, val unitsPerPx: Float, val pxPerMm: Float)

/** A fillable text field of a PDF form, in page units. */
class FormField(val left: Float, val top: Float, val right: Float, val bottom: Float, val multiLine: Boolean,
    val label: String?, val maxLength: Int)
