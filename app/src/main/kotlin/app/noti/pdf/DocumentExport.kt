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
import app.noti.data.EndlessCanvas
import app.noti.data.InkArchive
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.Paper
import app.noti.data.PaperSize
import app.noti.data.PaperTemplate
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** A canonical archive can be exported without a live engine, including from the library. */
object DocumentExport {
    /** Call on IO. Output is flattened, with source PDF pages and all current ink. */
    fun writePdf(context: Context, notebook: Notebook, library: LibraryStore, bytes: ByteArray?, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { writePdf(notebook, library, bytes, it) }
            ?: error("Couldn't open the export destination")
    }

    fun writePdf(notebook: Notebook, library: LibraryStore, bytes: ByteArray?, stream: java.io.OutputStream) {
        if (notebook.isCanvas) {
            // A notebook never opened since Endless became a canvas still has page-shaped ink.
            val ink = if (notebook.canvasInk) bytes else EndlessCanvas.fromPages(bytes)
            // A Study board also prints its cards, headings, score cards and source pages.
            val board = if (!notebook.board) null else app.noti.study.BoardExport.read(library.studyFile(notebook))?.let { study ->
                app.noti.study.BoardExport.of(library.appContext, study) { id -> library.notebook(id)?.let(library::pdfFile)?.takeIf { it.exists() } }
            }
            val output = android.graphics.pdf.PdfDocument()
            try { writeCanvas(notebook.paper, InkArchive.decode(ink), output, board); output.writeTo(stream) }
            finally { output.close() }
            return
        }
        val strokes = InkArchive.decode(bytes).groupBy { it.page }
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

    /**
     * The Endless canvas as one page around all of its ink (or home, when there is none),
     * a screen of canvas as wide as A4, smaller if the page would exceed what PDF viewers open.
     */
    private fun writeCanvas(paper: Paper, strokes: List<InkArchive.Stroke>, output: android.graphics.pdf.PdfDocument,
        board: app.noti.study.BoardExport? = null) {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in strokes) {
            val c = s.capsules
            for (i in c.indices step 3) {
                x0 = minOf(x0, c[i] - c[i + 2]); x1 = max(x1, c[i] + c[i + 2])
                y0 = minOf(y0, c[i + 1] - c[i + 2]); y1 = max(y1, c[i + 1] + c[i + 2])
            }
        }
        board?.bounds?.let { b -> x0 = minOf(x0, b.left); y0 = minOf(y0, b.top); x1 = max(x1, b.right); y1 = max(y1, b.bottom) }
        val unit = EndlessCanvas.BASE_UNITS
        if (x0 > x1) {
            x0 = EndlessCanvas.HOME_LEFT_UNITS; y0 = EndlessCanvas.HOME_TOP_UNITS
            x1 = x0 + unit; y1 = y0 + unit * 842f / 595f
        } else {
            // A ruled line's worth of paper around the ink.
            val pad = unit * 0.04f
            x0 -= pad; y0 -= pad; x1 += pad; y1 += pad
        }
        val k = minOf(595f / unit, MAX_PDF_PT / max(x1 - x0, y1 - y0))
        val width = ceil((x1 - x0) * k).toInt().coerceAtLeast(1)
        val height = ceil((y1 - y0) * k).toInt().coerceAtLeast(1)
        val page = output.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, 1).create())
        val canvas = page.canvas
        // The pattern stays where it is on the sheet: anchored at the sheet's corner.
        drawPaper(canvas, paper, width.toFloat(), height.toFloat(), k * unit / 1000f, x0 * k, y0 * k)
        canvas.scale(k, k)
        canvas.translate(-x0, -y0)
        board?.draw(canvas)
        strokes.forEach { drawInk(canvas, it) }
        output.finishPage(page)
    }

    /** About 5 m: the largest page size common PDF viewers open. */
    private const val MAX_PDF_PT = 14400f

    internal fun drawInk(canvas: Canvas, stroke: InkArchive.Stroke) {
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

    /**
     * [scale] is output units per page unit (a page is 1000 wide); the pattern is anchored
     * ([originX], [originY]) to the left of and above the output's corner.
     */
    private fun drawPaper(canvas: Canvas, paper: Paper, width: Float, height: Float, scale: Float = width / 1000f,
        originX: Float = 0f, originY: Float = 0f) {
        canvas.drawColor(paper.color.argb.toInt())
        val step = 40f * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (paper.color.isDark) 0x33FFFFFF else 0x2E3A5A8C
            strokeWidth = scale
        }
        if (paper.template == PaperTemplate.Blank || !(step > 0f)) return
        // The first pattern position inside the output, [skip] steps from the pattern's origin at least.
        fun first(origin: Float, skip: Int) = step * max(skip.toFloat(), ceil(origin / step)) - origin
        var y = first(originY, if (paper.template == PaperTemplate.Lined) 2 else 1)
        while (y < height) {
            if (paper.template == PaperTemplate.Dots) {
                var x = first(originX, 1)
                while (x < width) { canvas.drawCircle(x, y, scale * 1.4f, paint); x += step }
            } else canvas.drawLine(0f, y, width, y, paint)
            y += step
        }
        if (paper.template == PaperTemplate.Grid) {
            var x = first(originX, 1)
            while (x < width) { canvas.drawLine(x, 0f, x, height, paint); x += step }
        }
    }
}
