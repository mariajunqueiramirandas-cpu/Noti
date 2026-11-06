package app.noti.study

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import app.noti.data.LibraryStore
import app.noti.pdf.PagePlacement
import app.noti.pdf.PdfDocument
import java.io.Closeable

/**
 * What a Study board shows under the ink besides paper: printed cards, topic headings,
 * exam score cards and source pages. Drawn in the canvas's draw phase from [StudyStore]
 * state, so a change redraws it. Positions are page units of the Endless sheet ([sheet]
 * is page 0's placement): they move and zoom with the ink. Main thread.
 */
class BoardLayer(private val store: StudyStore, private val library: LibraryStore, private val fonts: CardFace.Fonts) : Closeable {
    private data class FaceKey(val item: StudyItem, val progress: Progress, val widthDp: Float, val number: Int, val source: String?)
    private val faces = HashMap<String, Pair<FaceKey, CardFace>>()
    private val pdfs = HashMap<String, PdfDocument?>()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply { typeface = fonts.bold; color = 0xFF5C4A87.toInt() }
    private val pageShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F000000 }
    private val pageBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x26000000 }
    private val white = Paint().apply { color = Color.WHITE }
    private var closed = false

    sealed interface Hit {
        data class Card(val card: PinnedCard) : Hit
        data class Label(val label: PinnedLabel) : Hit
        data class Page(val page: PlacedPage) : Hit
        data class Score(val exam: Exam) : Hit
    }

    /** The face of a printed card, laid out once per change. */
    fun face(card: PinnedCard, data: BoardStudy = store.data): CardFace? {
        val item = data.item(card.itemId) ?: return null
        val key = FaceKey(item, data.progressOf(item.id), card.widthDp, data.items.indexOf(item) + 1, data.source(item.sourceId)?.name)
        faces[card.id]?.let { (k, f) -> if (k == key) return f }
        val f = CardFace.layout(item, key.progress, card.widthDp, fonts, CardFace.Mode.Board, key.number, key.source)
        faces[card.id] = key to f
        return f
    }

    /** A card's height in page units. */
    fun heightUnits(card: PinnedCard): Float = (face(card)?.heightDp ?: 0f) * card.unitsPerDp

    fun scoreHeightUnits(exam: Exam, card: PinnedCard): Float = ScoreFace.layout(exam, store.data, card.widthDp, fonts).first * card.unitsPerDp

    fun draw(canvas: Canvas, sheet: PagePlacement) {
        if (closed) return
        val data = store.data
        val k = sheet.width / 1000f
        val cw = sheet.canvasWidth.toFloat(); val ch = sheet.canvasHeight.toFloat()
        fun visible(l: Float, t: Float, r: Float, b: Float) = r >= 0 && b >= 0 && l <= cw && t <= ch

        // Source pages, under everything else.
        val drawn = HashMap<PdfDocument, ArrayList<PagePlacement>>()
        for (p in data.pages) {
            val pdf = pdf(p.sourceId) ?: continue
            val aspect = pdf.aspect(p.page)
            val l = sheet.left + p.x * k; val t = sheet.top + p.y * k
            val w = p.width * k; val h = w * aspect
            if (!visible(l, t, l + w, t + h)) continue
            canvas.drawRect(l + 2f, t + 4f, l + w + 2f, t + h + 4f, pageShadow)
            canvas.drawRect(l, t, l + w, t + h, white)
            val placement = PagePlacement(p.page, l, t, w, h, sheet.zoom, sheet.canvasWidth, sheet.canvasHeight, sheet.settled)
            val s = canvas.save()
            canvas.clipRect(l, t, l + w, t + h)
            pdf.drawPage(canvas, placement)
            canvas.restoreToCount(s)
            canvas.drawRect(l, t, l + w, t + h, pageBorder)
            drawn.getOrPut(pdf) { ArrayList() } += placement
        }
        drawn.forEach { (pdf, list) -> pdf.endFrame(list[0].copy(visiblePages = list, nearbyPages = list)) }

        for (label in data.labels) {
            val size = 22f * label.unitsPerDp * k
            if (size < 2f) continue
            val x = sheet.left + label.x * k; val y = sheet.top + label.y * k
            labelPaint.textSize = size
            if (!visible(x, y - size, x + labelPaint.measureText(label.text), y + size * 0.3f)) continue
            canvas.drawText(label.text, x, y, labelPaint)
        }

        for (card in data.cards) {
            val face = face(card, data) ?: continue
            val scale = card.unitsPerDp * k
            val x = sheet.left + card.x * k; val y = sheet.top + card.y * k
            if (!visible(x, y, x + face.widthDp * scale, y + face.heightDp * scale)) continue
            val s = canvas.save()
            canvas.translate(x, y); canvas.scale(scale, scale)
            face.draw(canvas)
            canvas.restoreToCount(s)
        }

        for (exam in data.exams) {
            val card = exam.card ?: continue
            val (h, draw) = ScoreFace.layout(exam, data, card.widthDp, fonts)
            val scale = card.unitsPerDp * k
            val x = sheet.left + card.x * k; val y = sheet.top + card.y * k
            if (!visible(x, y, x + card.widthDp * scale, y + h * scale)) continue
            val s = canvas.save()
            canvas.translate(x, y); canvas.scale(scale, scale)
            draw(canvas)
            canvas.restoreToCount(s)
        }
        // Forget faces of cards that are gone.
        if (faces.size > data.cards.size) faces.keys.retainAll(data.cards.map { it.id }.toSet())
    }

    /** What's under ([x], [y]) in canvas px, topmost first. */
    fun hit(x: Float, y: Float, sheet: PagePlacement): Hit? {
        val data = store.data
        val k = sheet.width / 1000f
        val u = (x - sheet.left) / k; val v = (y - sheet.top) / k
        for (exam in data.exams.asReversed()) {
            val card = exam.card ?: continue
            if (u in card.x..card.x + card.widthDp * card.unitsPerDp && v in card.y..card.y + scoreHeightUnits(exam, card)) return Hit.Score(exam)
        }
        for (card in data.cards.asReversed()) {
            if (u in card.x..card.x + card.widthDp * card.unitsPerDp && v in card.y..card.y + heightUnits(card)) return Hit.Card(card)
        }
        for (label in data.labels.asReversed()) {
            labelPaint.textSize = 22f * label.unitsPerDp
            val w = labelPaint.measureText(label.text)
            if (u in label.x..label.x + w && v in label.y - 22f * label.unitsPerDp..label.y + 6f * label.unitsPerDp) return Hit.Label(label)
        }
        for (p in data.pages.asReversed()) {
            val pdf = pdf(p.sourceId) ?: continue
            if (u in p.x..p.x + p.width && v in p.y..p.y + p.width * pdf.aspect(p.page)) return Hit.Page(p)
        }
        return null
    }

    /** The bounds of everything on the board, in page units, or null when it's empty (export). */
    fun bounds(): RectF? {
        val data = store.data
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        data.cards.forEach { r.union(it.x, it.y, it.x + it.widthDp * it.unitsPerDp, it.y + heightUnits(it)) }
        data.pages.forEach { p -> pdf(p.sourceId)?.let { r.union(p.x, p.y, p.x + p.width, p.y + p.width * it.aspect(p.page)) } }
        data.exams.forEach { e -> e.card?.let { c -> r.union(c.x, c.y, c.x + c.widthDp * c.unitsPerDp, c.y + scoreHeightUnits(e, c)) } }
        return if (r.left > r.right) null else r
    }

    private fun pdf(sourceId: String): PdfDocument? = pdfs.getOrPut(sourceId) {
        library.notebook(sourceId)?.let { library.pdfFile(it) }?.takeIf { it.exists() }?.let { PdfDocument(it) }
    }

    override fun close() {
        closed = true
        pdfs.values.forEach { it?.close() }
        pdfs.clear()
    }
}
