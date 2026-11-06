package app.noti.study

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.noti.data.InkArchive
import app.noti.pdf.DocumentExport
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pictures for the model: looped handwriting, and page ranges of a PDF. Any thread. */
object StudyImages {
    /** The strokes of InkLayer.selectionInk (see InkLines.parse), as they look on the board. */
    fun strokes(selection: FloatArray): List<InkArchive.Stroke> {
        if (selection.isEmpty()) return emptyList()
        val count = selection[0].toInt()
        val out = ArrayList<InkArchive.Stroke>(count.coerceAtLeast(0))
        var at = 1
        repeat(count) {
            if (at + 7 > selection.size) return out
            val page = selection[at + 1].toInt()
            val color = selection.copyOfRange(at + 2, at + 6)
            val n = selection[at + 6].toInt()
            at += 7
            if (n <= 0 || at + n * 6 > selection.size) return out
            out += InkArchive.Stroke(page, color, selection.copyOfRange(at, at + n * 6))
            at += n * 6
        }
        return out
    }

    /**
     * The looped ink on white, scaled so the longer side is at most [maxPx] (and the ink
     * no thinner than readable), as a PNG. Null if there is no ink.
     */
    fun inkPng(selection: FloatArray, maxPx: Int = 1400): ByteArray? {
        val strokes = strokes(selection)
        if (strokes.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var radius = 0f; var n = 0
        for (s in strokes) {
            val c = s.capsules
            for (i in c.indices step 3) {
                x0 = min(x0, c[i] - c[i + 2]); x1 = max(x1, c[i] + c[i + 2])
                y0 = min(y0, c[i + 1] - c[i + 2]); y1 = max(y1, c[i + 1] + c[i + 2])
                radius += c[i + 2]; n++
            }
        }
        val w = max(x1 - x0, 1e-3f); val h = max(y1 - y0, 1e-3f)
        val pad = max(w, h) * 0.04f
        // The long side fills the picture; ink thinner than ~2 px there is thickened to stay legible.
        val k = maxPx / (max(w, h) + 2 * pad)
        val thin = n > 0 && radius / n * k < 1f
        val bw = ((w + 2 * pad) * k).roundToInt().coerceIn(16, maxPx)
        val bh = ((h + 2 * pad) * k).roundToInt().coerceIn(16, maxPx)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(k, k)
            canvas.translate(pad - x0, pad - y0)
            val drawn = if (!thin) strokes else strokes.map { st ->
                val c = st.capsules.copyOf()
                for (i in 2 until c.size step 3) c[i] = max(c[i], 1f / k)
                InkArchive.Stroke(st.page, st.color, c)
            }
            drawn.forEach { DocumentExport.drawInk(canvas, it) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    /** Pages [first]..[last] (1-based) of [source] as a new PDF of page images, for sending a range. */
    fun extractPages(source: File, first: Int, last: Int, out: File, dpi: Int = 130) {
        PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            val from = first.coerceIn(1, renderer.pageCount)
            val to = last.coerceIn(from, renderer.pageCount)
            val doc = android.graphics.pdf.PdfDocument()
            try {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (index in from - 1 until to) {
                    renderer.openPage(index).use { page ->
                        val scale = dpi / 72f
                        val bw = (page.width * scale).roundToInt().coerceIn(1, 2600)
                        val bh = (page.height * scale).roundToInt().coerceIn(1, 3600)
                        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val outPage = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(page.width, page.height, index - from + 2).create())
                            outPage.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint)
                            doc.finishPage(outPage)
                        } finally { bitmap.recycle() }
                    }
                }
                out.outputStream().use { doc.writeTo(it) }
            } finally { doc.close() }
        }
    }

    /** One page as a bitmap [widthPx] wide (source previews). */
    fun renderPage(source: File, index: Int, widthPx: Int): Bitmap? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            if (index !in 0 until renderer.pageCount) return null
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(64, 2400)
                val h = (w * page.height.toFloat() / page.width).roundToInt().coerceIn(64, 4000)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }.getOrNull()

    /** Page count of a PDF, or 0 if it can't be opened. */
    fun pageCount(source: File): Int = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
    }.getOrDefault(0)
}
