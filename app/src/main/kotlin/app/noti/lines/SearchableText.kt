package app.noti.lines

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import app.noti.data.InkArchive
import app.noti.transcribe.SelectedStroke
import kotlin.math.atan

/**
 * Typed text in exported PDFs as real text too: the ink shows it, and an all-but-invisible
 * copy of the words in the same font, size and place lets PDF readers search and copy it.
 * Only blocks still on the page (found among the exported ink) are written, where they are now.
 */
object SearchableText {
    /** The archive's strokes as the matcher sees ink: centreline from the first capsule's start. */
    fun strokes(archive: List<InkArchive.Stroke>): List<SelectedStroke> = archive.mapIndexed { i, s ->
        val c = s.capsules
        val n = c.size / 6
        val points = FloatArray(2 + n * 2)
        if (n > 0) { points[0] = c[0]; points[1] = c[1] }
        for (j in 0 until n) { points[2 + j * 2] = c[j * 6 + 3]; points[3 + j * 2] = c[j * 6 + 4] }
        SelectedStroke(i, s.page, s.color, points)
    }

    /**
     * Writes the text of [blocks] found on [page] among [ink] into [canvas], which draws in
     * page units. The words are laid out exactly as their ink was.
     */
    fun draw(canvas: Canvas, page: Int, ink: List<SelectedStroke>, blocks: List<TextBlock>, regular: Typeface, bold: Typeface, mmPerUnit: Float) {
        if (blocks.none { it.page == page }) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        for (m in BlockMatcher.find(blocks, page, ink)) {
            val b = m.block
            val face = if (b.style.bold) bold else regular
            paint.typeface = face
            paint.textSize = b.em
            // Barely there: readers find and copy it; nobody sees it over the ink.
            paint.color = 0x01000000
            val measure = Paint(paint).apply { textSize = 100f }
            val laid = LineLayout({ t -> measure.measureText(t) / 100f }, mmPerUnit)
                .layout(m.lines, b.text, b.style.copy(size = b.em))
            for (w in laid.words) {
                if (w.slope == 0f) { canvas.drawText(w.text, w.x, w.y, paint); continue }
                val save = canvas.save()
                canvas.rotate(Math.toDegrees(atan(w.slope.toDouble())).toFloat(), w.x, w.y)
                canvas.drawText(w.text, w.x, w.y, paint)
                canvas.restoreToCount(save)
            }
        }
    }
}
