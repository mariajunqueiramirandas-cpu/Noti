package app.noti.study

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.noti.R
import java.io.File

/**
 * A Study board's printed cards, headings, score cards and source pages for PDF export,
 * drawn in the Endless sheet's page units (under the ink, as on the board). Any thread.
 */
class BoardExport private constructor(val bounds: RectF?, private val drawAll: (Canvas) -> Unit) {
    fun draw(canvas: Canvas) = drawAll(canvas)

    companion object {
        /** [pdf] finds a source's file; null if it's gone. Null when there's nothing to draw. */
        fun of(context: Context, study: BoardStudy, pdf: (String) -> File?): BoardExport? {
            if (study.cards.isEmpty() && study.labels.isEmpty() && study.pages.isEmpty() && study.exams.none { it.card != null }) return null
            val res = context.resources
            val fonts = CardFace.Fonts(res.getFont(R.font.nunito_regular), res.getFont(R.font.nunito_semibold), res.getFont(R.font.nunito_bold))
            val ops = ArrayList<(Canvas) -> Unit>()
            val bounds = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            fun place(x: Float, y: Float, scale: Float, w: Float, h: Float, draw: (Canvas) -> Unit) {
                bounds.union(x, y, x + w * scale, y + h * scale)
                ops += { c -> val s = c.save(); c.translate(x, y); c.scale(scale, scale); draw(c); c.restoreToCount(s) }
            }
            for (p in study.pages) {
                val file = pdf(p.sourceId) ?: continue
                // About 150 dpi for a page a screen wide.
                val bitmap = StudyImages.renderPage(file, p.page, 1400) ?: continue
                val h = p.width * bitmap.height / bitmap.width
                val dst = RectF(p.x, p.y, p.x + p.width, p.y + h)
                bounds.union(dst)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                ops += { c -> c.drawBitmap(bitmap, null, dst, paint) }
            }
            for (label in study.labels) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.bold; color = 0xFF5C4A87.toInt(); textSize = 22f * label.unitsPerDp }
                bounds.union(label.x, label.y - paint.textSize, label.x + paint.measureText(label.text), label.y + paint.textSize * 0.3f)
                ops += { c -> c.drawText(label.text, label.x, label.y, paint) }
            }
            for (card in study.cards) {
                val item = study.item(card.itemId) ?: continue
                val face = CardFace.layout(item, study.progressOf(item.id), card.widthDp, fonts, CardFace.Mode.Board,
                    study.items.indexOf(item) + 1, study.source(item.sourceId)?.name)
                place(card.x, card.y, card.unitsPerDp, face.widthDp, face.heightDp, face::draw)
            }
            for (exam in study.exams) {
                val card = exam.card ?: continue
                val (h, draw) = ScoreFace.layout(exam, study, card.widthDp, fonts)
                place(card.x, card.y, card.unitsPerDp, card.widthDp, h, draw)
            }
            return BoardExport(if (bounds.left > bounds.right) null else bounds) { c -> ops.forEach { it(c) } }
        }

        /** Reads a board's study file (IO); null if it has none or can't be read. */
        fun read(file: File): BoardStudy? = runCatching {
            if (!file.exists() && !File(file.path + ".bak").exists()) return null
            BoardStudy.decode(String(android.util.AtomicFile(file).readFully(), Charsets.UTF_8))
        }.getOrNull()
    }
}
