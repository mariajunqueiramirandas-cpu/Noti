package app.noti.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.LruCache
import android.util.SizeF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.roundToInt

/**
 * An open PDF, rendered with the platform's PdfRenderer (PDFium) on its own thread, so
 * a heavy page never touches the UI thread, let alone the ink thread.
 *
 * Three kinds of bitmaps, all cached on the main thread:
 *  - **pages** at the canvas's fit width (what you see unzoomed; scaled while zooming),
 *  - one **detail** bitmap: exactly the visible part of the page at the current zoom,
 *    rendered once a pinch settles, so zoomed text is sharp,
 *  - small **thumbnails** for the pages panel.
 * Visible-page work jumps the queue ahead of thumbnails. Bitmaps land through
 * [version], a Compose state, so whatever draws them recomposes.
 *
 * Main thread only, except where noted.
 */
class PdfDocument(file: File) {
    private val thread = HandlerThread("noti-pdf", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Worker thread only.
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /** Page sizes in PDF points; empty until the document is open. */
    var pageSizes by mutableStateOf<List<SizeF>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped whenever a new bitmap is available. */
    var version by mutableIntStateOf(0)
        private set

    val pageCount get() = pageSizes.size

    private data class PageKey(val index: Int, val width: Int)
    private val pages = object : LruCache<PageKey, Bitmap>(PAGE_CACHE_BYTES) {
        override fun sizeOf(key: PageKey, value: Bitmap) = value.allocationByteCount
    }
    private val thumbs = object : LruCache<Int, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private val inflight = HashSet<Any>()
    private var closed = false

    /** The sharp, zoomed-in rendering of the visible part of one page. */
    class Detail(val index: Int, val pageRect: RectF, val visible: Rect, val bitmap: Bitmap)
    var detail: Detail? = null
        private set
    @Volatile private var detailGeneration = 0

    init {
        worker.post {
            try {
                val f = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(f)
                fd = f
                renderer = r
                val sizes = (0 until r.pageCount).map { i ->
                    r.openPage(i).use { SizeF(it.width.toFloat().coerceAtLeast(1f), it.height.toFloat().coerceAtLeast(1f)) }
                }
                main.post { if (!closed) pageSizes = sizes }
            } catch (e: Exception) {
                main.post { if (!closed) error = "Couldn't open this PDF." }
            }
        }
    }

    /** Height / width of a page (1.414 for A4 portrait). */
    fun aspect(index: Int): Float = pageSizes.getOrNull(index)?.let { it.height / it.width } ?: 1.4142f

    /**
     * The page at about [widthPx] wide, or the best cached rendering of it at another
     * width (drawn scaled) while the right one renders. Null if nothing is ready yet.
     */
    fun page(index: Int, widthPx: Int): Bitmap? {
        if (index !in pageSizes.indices || widthPx <= 0) return null
        val w = bucket(widthPx)
        val key = PageKey(index, w)
        pages.get(key)?.let { return it }
        request(key, front = true) { r -> renderPage(r, index, w) }
        return pages.snapshot().entries.filter { it.key.index == index }.maxByOrNull { it.key.width }?.value
    }

    /** Renders neighbours ahead of time so turning the page is instant. */
    fun prefetch(index: Int, widthPx: Int) {
        if (index !in pageSizes.indices || widthPx <= 0) return
        val key = PageKey(index, bucket(widthPx))
        if (pages.get(key) == null) request(key, front = false) { r -> renderPage(r, index, key.width) }
    }

    fun thumbnail(index: Int, widthPx: Int): Bitmap? {
        if (index !in pageSizes.indices) return null
        thumbs.get(index)?.let { return it }
        request("t$index", front = false) { r -> renderPage(r, index, widthPx) }
        return null
    }

    /**
     * Requests a sharp rendering of [visible] (canvas px) for a page placed at
     * [pageRect] (canvas px). Supersedes any earlier request.
     */
    fun requestDetail(index: Int, pageRect: RectF, visible: Rect) {
        if (index !in pageSizes.indices || visible.isEmpty) return
        val d = detail
        if (d != null && d.index == index && d.pageRect == pageRect && d.visible == visible) return
        val gen = ++detailGeneration
        val pr = RectF(pageRect)
        val vis = Rect(visible)
        worker.postAtFrontOfQueue {
            if (gen != detailGeneration) return@postAtFrontOfQueue  // superseded (racy read is fine: only skips work)
            val r = renderer ?: return@postAtFrontOfQueue
            val bmp = runCatching {
                r.openPage(index).use { page ->
                    val b = Bitmap.createBitmap(vis.width(), vis.height(), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    val m = Matrix()
                    val s = pr.width() / page.width
                    m.setScale(s, s)
                    m.postTranslate(pr.left - vis.left, pr.top - vis.top)
                    page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }.getOrNull() ?: return@postAtFrontOfQueue
            main.post {
                if (closed || gen != detailGeneration) return@post
                detail = Detail(index, pr, vis, bmp)
                version++
            }
        }
    }

    fun clearDetail() {
        ++detailGeneration
        if (detail != null) {
            detail = null
            version++
        }
    }

    fun close() {
        closed = true
        ++detailGeneration
        worker.post {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
            renderer = null
            fd = null
        }
        thread.quitSafely()
        pages.evictAll()
        thumbs.evictAll()
    }

    private fun request(key: Any, front: Boolean, render: (PdfRenderer) -> Bitmap?) {
        if (closed || !inflight.add(key)) return
        val job = Runnable {
            val bmp = renderer?.let { r -> runCatching { render(r) }.getOrNull() }
            main.post {
                inflight.remove(key)
                if (closed || bmp == null) return@post
                when (key) {
                    is PageKey -> pages.put(key, bmp)
                    is String -> thumbs.put(key.substring(1).toInt(), bmp)
                }
                version++
            }
        }
        if (front) worker.postAtFrontOfQueue(job) else worker.post(job)
    }

    // Worker thread.
    private fun renderPage(r: PdfRenderer, index: Int, widthPx: Int): Bitmap =
        r.openPage(index).use { page ->
            val w = widthPx.coerceIn(16, 8192)
            val h = (w.toFloat() * page.height / page.width).roundToInt().coerceIn(16, 8192)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        }

    private fun bucket(widthPx: Int) = ((widthPx + 63) / 64) * 64

    private companion object {
        const val PAGE_CACHE_BYTES = 160 * 1024 * 1024
        const val THUMB_CACHE_BYTES = 24 * 1024 * 1024
    }
}
