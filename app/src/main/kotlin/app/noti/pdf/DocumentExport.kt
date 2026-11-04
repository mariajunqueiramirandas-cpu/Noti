package app.noti.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.Paper
import app.noti.data.PaperSize
import app.noti.data.PaperTemplate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.sqrt

/** A canonical archive can be exported without a live engine, including from the library. */
object DocumentExport {
    private data class InkStroke(val page: Int, val color: FloatArray, val capsules: FloatArray)
    private fun decode(bytes: ByteArray?): List<InkStroke> {
        if (bytes == null) return emptyList()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 12 && b.int == 0x3149544e && b.int == 1) { "Invalid ink file" }
        val count = b.int
        require(count in 0..262144)
        val result = ArrayList<InkStroke>(count)
        repeat(count) {
            require(b.remaining() >= 24)
            val page = b.int; val n = b.int
            require(page >= 0 && n > 0 && n <= (b.remaining() - 16) / 24)
            val color = FloatArray(4) { b.float.also { require(it.isFinite() && it in 0f..1f) } }
            val caps = FloatArray(n * 6) { b.float.also { require(it.isFinite()) } }
            result += InkStroke(page, color, caps)
        }
        require(!b.hasRemaining())
        return result
    }

    /** Call on IO. Output is flattened, with source PDF pages and all current ink. */
    fun writePdf(context: Context, notebook: Notebook, library: LibraryStore, bytes: ByteArray?, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { writePdf(notebook, library, bytes, it) }
            ?: error("Couldn't open the export destination")
    }

    fun writePdf(notebook: Notebook, library: LibraryStore, bytes: ByteArray?, stream: java.io.OutputStream) {
        val strokes = decode(bytes).groupBy { it.page }
        val source = library.pdfFile(notebook)?.let {
            PdfRenderer(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        try {
            val output = android.graphics.pdf.PdfDocument()
            try {
                val count = source?.pageCount ?: notebook.pageCount
                repeat(count) { index ->
                    val original = source?.openPage(index)
                    try {
                        val width = original?.width ?: if (notebook.paper.size == PaperSize.Letter) 612 else 595
                        val height = original?.height ?: if (notebook.paper.size == PaperSize.Letter) 792 else 842
                        val page = output.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                        val canvas = page.canvas
                        if (original != null) {
                            // Bound memory for unusually large source pages.
                            val scale = minOf(2f, 4096f / maxOf(width, height))
                            val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                original.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                            } finally { bitmap.recycle() }
                        } else drawPaper(canvas, notebook.paper, width.toFloat(), height.toFloat())
                        val save = canvas.save()
                        canvas.clipRect(0, 0, width, height)
                        canvas.scale(width / 1000f, width / 1000f)
                        strokes[index]?.forEach { drawInk(canvas, it) }
                        canvas.restoreToCount(save)
                        output.finishPage(page)
                    } finally { original?.close() }
                }
                output.writeTo(stream)
            } finally { output.close() }
        } finally { source?.close() }
    }

    private fun drawInk(canvas: Canvas, stroke: InkStroke) {
        val alpha = stroke.color[3]
        if (alpha <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb((stroke.color[0] / alpha).coerceIn(0f, 1f), (stroke.color[1] / alpha).coerceIn(0f, 1f), (stroke.color[2] / alpha).coerceIn(0f, 1f))
        }
        val path = Path()
        val c = stroke.capsules
        var i = 0
        while (i < c.size) {
            val x0 = c[i]; val y0 = c[i+1]; val r0 = c[i+2].coerceAtLeast(0f)
            val x1 = c[i+3]; val y1 = c[i+4]; val r1 = c[i+5].coerceAtLeast(0f)
            path.addCircle(x0, y0, r0, Path.Direction.CCW)
            path.addCircle(x1, y1, r1, Path.Direction.CCW)
            val dx = x1 - x0; val dy = y1 - y0; val length = hypot(dx, dy)
            if (length > kotlin.math.abs(r0 - r1)) {
                val dr = (r0 - r1) / length
                val side = sqrt((1 - dr * dr).coerceAtLeast(0f))
                val nx = dx / length * dr - dy / length * side
                val ny = dy / length * dr + dx / length * side
                val mx = dx / length * dr + dy / length * side
                val my = dy / length * dr - dx / length * side
                path.moveTo(x0 + nx*r0, y0 + ny*r0)
                path.lineTo(x1 + nx*r1, y1 + ny*r1)
                path.lineTo(x1 + mx*r1, y1 + my*r1)
                path.lineTo(x0 + mx*r0, y0 + my*r0)
                path.close()
            }
            i += 6
        }
        // One filled union gives a highlighter uniform opacity at capsule joints.
        paint.alpha = (alpha * 255).toInt()
        canvas.drawPath(path, paint)
    }

    private fun drawPaper(canvas: Canvas, paper: Paper, width: Float, height: Float) {
        canvas.drawColor(paper.color.argb.toInt())
        val scale = width / 1000f
        val step = 40f * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (paper.color.isDark) 0x33FFFFFF else 0x2E3A5A8C
            strokeWidth = scale
        }
        if (paper.template == PaperTemplate.Blank) return
        var y = if (paper.template == PaperTemplate.Lined) step * 2 else step
        while (y < height) {
            if (paper.template == PaperTemplate.Dots) {
                var x = step
                while (x < width) { canvas.drawCircle(x, y, scale * 1.4f, paint); x += step }
            } else canvas.drawLine(0f, y, width, y, paint)
            y += step
        }
        if (paper.template == PaperTemplate.Grid) {
            var x = step
            while (x < width) { canvas.drawLine(x, 0f, x, height, paint); x += step }
        }
    }
}
