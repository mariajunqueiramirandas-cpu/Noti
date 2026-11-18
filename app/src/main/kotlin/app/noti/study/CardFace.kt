package app.noti.study

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * The face of a study item: what the floating sheet shows and what a printed card on the
 * board shows, from one layout, so printing looks exactly like the sheet did. Laid out in
 * dp ([widthDp] wide) and drawn in dp: scale the canvas by px per dp first.
 */
class CardFace private constructor(
    val widthDp: Float,
    val heightDp: Float,
    /** Multiple choice options, in dp from the face's corner (to make them tappable on the sheet). */
    val optionRects: List<RectF>,
    private val ops: List<(Canvas) -> Unit>,
) {
    fun draw(canvas: Canvas) = ops.forEach { it(canvas) }

    /** How the face is shown. */
    enum class Mode {
        /** On the floating sheet: no card chrome (the sheet is the card). */
        Sheet,
        /** Printed on the board: a card with a status edge. */
        Board,
        /** During an exam: no right/wrong, hints, answers or sources. */
        Exam,
    }

    class Fonts(val regular: Typeface, val medium: Typeface, val bold: Typeface)

    companion object {
        const val PAD = 18f
        /** In a prompt, where the next of the item's [StudyItem.figures] goes. */
        const val FIGURE = '\uFFFC'
        private const val FIGURE_MAX = 260f
        private const val CHOICE_FIGURE_MAX = 150f
        private const val INK = 0xFF2A2830.toInt()
        private const val MUTED = 0xFF6C6873.toInt()
        private const val PLUM = 0xFF5C4A87.toInt()
        private const val LILAC = 0xFFE9E3F4.toInt()
        private const val LINE = 0x1F2A2830
        private const val MINT = 0xFFDCEBDF.toInt()
        private const val MINT_INK = 0xFF2F6B43.toInt()
        private const val BUTTER = 0xFFF7EDC9.toInt()
        private const val BUTTER_INK = 0xFF7A5A0E.toInt()
        private const val PEACH = 0xFFF8DDD4.toInt()
        private const val PEACH_INK = 0xFF9A3A22.toInt()
        /** Plum at 12%: present on every copy, quiet enough to read through. */
        private const val WATERMARK = 0x1F5C4A87

        fun statusColors(v: Verdict?): Pair<Int, Int>? = when (v) {
            Verdict.Correct -> MINT to MINT_INK
            Verdict.Partial -> BUTTER to BUTTER_INK
            Verdict.Incorrect -> PEACH to PEACH_INK
            null -> null
        }

        /**
         * [number] is the item's place in the deck (1-based) or 0 to leave it out; [source]
         * names the PDF the item came from.
         */
        /**
         * [picked] is an option chosen but not yet checked (checked elsewhere, as Plataforma
         * Assaad questions are): it shows as chosen, without right or wrong. [figure] turns
         * an image URL into its picture, or null while it's not available.
         */
        fun layout(item: StudyItem, progress: Progress, widthDp: Float, fonts: Fonts, mode: Mode,
            number: Int = 0, source: String? = null, picked: Int = -1, figure: ((String) -> Bitmap?)? = null): CardFace {
            val w = max(widthDp, 160f)
            val inner = w - 2 * PAD
            val ops = ArrayList<(Canvas) -> Unit>()
            val options = ArrayList<RectF>()
            val exam = mode == Mode.Exam
            val outcome = if (exam) null else progress.outcome(item)
            var y = PAD

            fun text(face: Typeface, size: Float, color: Int) =
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { typeface = face; textSize = size; this.color = color }
            fun math(src: String, size: Float, color: Int, width: Float = inner) =
                MathText.layout(src, width, MathText.Style(size, color, fonts.regular, fonts.bold))
            fun label(s: String, x: Float, baseline: Float, color: Int = MUTED, size: Float = 11.5f, face: Typeface = fonts.bold) {
                val p = text(face, size, color).apply { letterSpacing = 0.06f }
                ops += { it.drawText(s, x, baseline, p) }
            }
            fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

            // Header: what it is, its topic, and how it went.
            val kindText = item.kind.label.uppercase()
            val head = text(fonts.bold, 11.5f, PLUM).apply { letterSpacing = 0.06f }
            val kindW = head.measureText(kindText)
            label(kindText, PAD, y + 12f, PLUM)
            val topic = (if (number > 0) "Q$number · " else "") + item.topic
            val topicPaint = text(fonts.medium, 12.5f, MUTED)
            val status = when {
                exam && (progress.grade != null || progress.chosen >= 0) -> "ANSWERED" to (LILAC to PLUM)
                outcome != null -> outcome.label.uppercase() to statusColors(outcome)!!
                else -> null
            }
            val statusW = status?.let { text(fonts.bold, 10.5f, 0).apply { letterSpacing = 0.06f }.measureText(it.first) + 16f } ?: 0f
            val topicRoom = inner - kindW - 10f - (if (status != null) statusW + 8f else 0f)
            val shownTopic = ellipsize(topic, topicPaint, topicRoom)
            val headY = y
            ops += { it.drawText(shownTopic, PAD + kindW + 10f, headY + 12f, topicPaint) }
            status?.let { (s, colors) ->
                val r = RectF(w - PAD - statusW, y - 1f, w - PAD, y + 17f)
                val bg = fill(colors.first); val p = text(fonts.bold, 10.5f, colors.second).apply { letterSpacing = 0.06f }
                ops += { it.drawRoundRect(r, 9f, 9f, bg); it.drawText(s, r.left + 8f, r.top + 13f, p) }
            }
            y += 28f

            /** A picture [room] wide at most, at (x, y); a quiet placeholder while it loads. */
            fun picture(url: String, x: Float, top: Float, room: Float, maxH: Float): Float {
                val bitmap = figure?.invoke(url)?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                if (bitmap == null) {
                    val r = RectF(x, top, x + room, top + 56f)
                    val bg = fill(0xFFF3F1EE.toInt())
                    val p = text(fonts.medium, 12.5f, MUTED).apply { textAlign = Paint.Align.CENTER }
                    ops += { it.drawRoundRect(r, 10f, 10f, bg); it.drawText("Image", r.centerX(), r.centerY() + 4.5f, p) }
                    return r.height()
                }
                val scale = minOf(room / bitmap.width, maxH / bitmap.height, 2f)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                val r = RectF(x + (room - w) / 2, top, x + (room - w) / 2 + w, top + h)
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                ops += { it.drawBitmap(bitmap, null, r, p) }
                return h
            }

            // The question, with its pictures where the prompt puts them.
            val promptSize = if (item.kind == ItemKind.Flash) 19f else 17f
            val parts = if (item.figures.isEmpty()) listOf(item.prompt) else item.prompt.split(FIGURE)
            var nextFigure = 0
            parts.forEachIndexed { i, part ->
                if (part.isNotBlank()) {
                    val prompt = math(part.trim(), promptSize, INK)
                    val promptY = y
                    ops += { prompt.draw(it, PAD, promptY) }
                    y += prompt.height + 12f
                }
                if (i < parts.lastIndex && nextFigure < item.figures.size) {
                    y += picture(item.figures[nextFigure++], PAD, y, inner, FIGURE_MAX) + 12f
                }
            }
            while (nextFigure < item.figures.size) y += picture(item.figures[nextFigure++], PAD, y, inner, FIGURE_MAX) + 12f

            // Multiple choice options.
            if (item.kind == ItemKind.Choice) {
                item.choices.forEachIndexed { i, choice ->
                    val body = math(choice, 16f, INK, inner - 44f)
                    val pictures = item.choiceFigures.getOrNull(i).orEmpty()
                    // Pictures go under the option's text; measured in place, drawn after the frame.
                    val frameAt = ops.size
                    var pictureY = y + body.height + 14f
                    pictures.forEach { url -> pictureY += picture(url, PAD + 40f, pictureY, inner - 52f, CHOICE_FIGURE_MAX) + 8f }
                    val pictureOps = ops.subList(frameAt, ops.size).toList()
                    repeat(pictureOps.size) { ops.removeAt(ops.lastIndex) }
                    val h = max(36f, if (pictures.isEmpty()) body.height + 14f else pictureY - y)
                    val r = RectF(PAD, y, w - PAD, y + h)
                    options += RectF(r)
                    val answered = progress.chosen >= 0 && !exam
                    val chosen = progress.chosen == i || (!answered && picked == i)
                    val bg = when {
                        answered && i == item.correctChoice -> MINT
                        answered && chosen -> PEACH
                        chosen -> LILAC
                        else -> 0
                    }
                    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = if (chosen) PLUM else LINE }
                    val bgPaint = fill(bg)
                    val circle = fill(if (chosen) PLUM else 0xFFF0EEEB.toInt())
                    val letter = text(fonts.bold, 13f, if (chosen) 0xFFFFFFFF.toInt() else INK).apply { textAlign = Paint.Align.CENTER }
                    val cx = r.left + 20f; val cy = if (pictures.isEmpty()) r.top + h / 2 else r.top + 7f + max(body.height, 24f) / 2
                    val by = if (pictures.isEmpty()) r.top + (h - body.height) / 2 else r.top + 7f
                    ops += {
                        if (bg != 0) it.drawRoundRect(r, 12f, 12f, bgPaint)
                        it.drawRoundRect(r, 12f, 12f, border)
                        it.drawCircle(cx, cy, 12f, circle)
                        it.drawText(('A' + i).toString(), cx, cy + 4.5f, letter)
                        body.draw(it, r.left + 40f, by)
                    }
                    ops += pictureOps
                    y += h + 8f
                }
                y += 4f
            }

            // Flash card back.
            if (item.kind == ItemKind.Flash && (progress.flipped || mode == Mode.Board && progress.srs != null) && !exam) {
                val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) }
                val ly = y
                ops += { it.drawLine(PAD, ly, w - PAD, ly, dash) }
                y += 14f
                label("BACK", PAD, y + 10f)
                y += 18f
                val back = math(item.answer, 17f, INK)
                val by = y
                ops += { back.draw(it, PAD, by) }
                y += back.height + 12f
            }

            // Hints asked for.
            if (!exam) for (h in 0 until progress.hintsShown.coerceAtMost(item.hints.size)) {
                val body = math(item.hints[h], 15f, INK, inner - 24f)
                val r = RectF(PAD, y, w - PAD, y + body.height + 34f)
                val bg = fill(BUTTER)
                val by = y + 26f
                ops += { it.drawRoundRect(r, 12f, 12f, bg) }
                label("HINT ${h + 1}", PAD + 12f, y + 17f, BUTTER_INK)
                ops += { body.draw(it, PAD + 12f, by) }
                y = r.bottom + 8f
            }

            // Answer and worked solution.
            if (!exam && progress.answerShown && item.kind != ItemKind.Flash) {
                y += 4f
                label("ANSWER", PAD, y + 10f, PLUM)
                y += 18f
                val answer = math(item.answer, 16f, INK)
                val ay = y
                ops += { answer.draw(it, PAD, ay) }
                y += answer.height + 8f
                if (item.solution.isNotEmpty()) {
                    label("SOLUTION", PAD, y + 10f, PLUM)
                    y += 18f
                    item.solution.forEachIndexed { i, step ->
                        val body = math(step, 15f, INK, inner - 22f)
                        val n = text(fonts.bold, 13f, MUTED)
                        val sy = y
                        ops += { it.drawText("${i + 1}.", PAD, sy + 14f, n); body.draw(it, PAD + 22f, sy) }
                        y += body.height + 6f
                    }
                }
                y += 6f
            }

            // What checking said.
            val grade = progress.grade
            if (!exam && grade != null) {
                val colors = statusColors(grade.verdict)!!
                val fb = math(grade.feedback.ifBlank { grade.verdict.label + "." }, 15f, INK, inner - 24f)
                val read = grade.read.takeIf { it.isNotBlank() }?.let { math("I read: $it", 13f, MUTED, inner - 24f) }
                val steps = grade.steps.map { s -> s to math(s.note, 14f, if (s.ok) INK else colors.second, inner - 46f) }
                var h = 30f + fb.height + 10f
                if (read != null) h += read.height + 8f
                steps.forEach { h += it.second.height + 6f }
                val r = RectF(PAD, y, w - PAD, y + h)
                val bg = fill(colors.first)
                ops += { it.drawRoundRect(r, 12f, 12f, bg) }
                label("CHECKED · ${(grade.score * 100).toInt()}%", PAD + 12f, y + 18f, colors.second)
                var gy = y + 28f
                val fy = gy
                ops += { fb.draw(it, PAD + 12f, fy) }
                gy += fb.height + 8f
                steps.forEach { (s, body) ->
                    val mark = if (s.ok) "✓" else "✗"
                    val mp = text(fonts.bold, 14f, if (s.ok) MINT_INK else PEACH_INK)
                    val sy = gy
                    ops += { it.drawText(mark, PAD + 14f, sy + 14f, mp); body.draw(it, PAD + 34f, sy) }
                    gy += body.height + 6f
                }
                read?.let { rd -> val ry = gy + 2f; ops += { rd.draw(it, PAD + 12f, ry) } }
                y = r.bottom + 10f
            }

            // Where it came from.
            if (!exam && (item.pages.isNotEmpty() || source != null)) {
                val where = buildString {
                    if (item.pages.isNotEmpty()) append(pagesLabel(item.pages))
                    if (source != null) { if (isNotEmpty()) append(" · "); append(source) }
                }
                val p = text(fonts.medium, 12f, MUTED)
                val shown = ellipsize(where, p, inner)
                val fy = y + 10f
                ops += { it.drawText(shown, PAD, fy, p) }
                y += 18f
            }

            // Whose question it is, in words at the foot of the card.
            val credit = item.credit.trim()
            if (credit.isNotEmpty()) {
                val p = text(fonts.bold, 11.5f, PLUM).apply { letterSpacing = 0.04f }
                val shown = ellipsize(credit, p, inner)
                val fy = y + 10f
                ops += { it.drawText(shown, PAD, fy, p) }
                y += 18f
            }
            val height = y + PAD - 6f

            // And faintly across the whole card, diagonally, so it stays with every copy: on the
            // sheet, printed on the board and in exported PDFs.
            if (credit.isNotEmpty()) {
                val mark = text(fonts.bold, 13f, WATERMARK).apply { letterSpacing = 0.12f; textAlign = Paint.Align.CENTER }
                val label = credit.uppercase()
                val step = mark.measureText(label) + 56f
                val reach = kotlin.math.hypot(w, height)
                val bounds = RectF(0f, 0f, w, height)
                ops += { c ->
                    val s = c.save()
                    c.clipRect(bounds)
                    c.rotate(-30f, w / 2, height / 2)
                    var row = -reach / 2; var line = 0
                    while (row < reach / 2 + 40f) {
                        var x = -reach / 2 + if (line % 2 == 1) step / 2 else 0f
                        while (x < reach / 2 + step) { c.drawText(label, w / 2 + x, height / 2 + row, mark); x += step }
                        row += 64f; line++
                    }
                    c.restoreToCount(s)
                }
            }

            if (mode == Mode.Board) {
                // A card resting on the board: white, rounded, a soft shadow and the status edge.
                val shape = RectF(0f, 0f, w, height)
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
                val shadowShape = RectF(1f, 3f, w + 1f, height + 4f)
                val bg = fill(0xFFFFFFFF.toInt())
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0x26000000 }
                val edge = statusColors(outcome)?.second?.let { fill(it) }
                ops.add(0) { c ->
                    c.drawRoundRect(shadowShape, 16f, 16f, shadow)
                    c.drawRoundRect(shape, 14f, 14f, bg)
                    edge?.let { e ->
                        val s = c.save(); c.clipRect(0f, 0f, 6f, height)
                        c.drawRoundRect(shape, 14f, 14f, e); c.restoreToCount(s)
                    }
                    c.drawRoundRect(shape, 14f, 14f, border)
                }
            }
            return CardFace(w, height, options, ops)
        }

        fun pagesLabel(pages: List<Int>): String {
            if (pages.isEmpty()) return ""
            val sorted = pages.sorted()
            val ranges = ArrayList<String>()
            var start = sorted[0]; var prev = start
            for (p in sorted.drop(1) + Int.MAX_VALUE) {
                if (p == prev + 1) { prev = p; continue }
                ranges += if (start == prev) "$start" else "$start–$prev"
                start = p; prev = p
            }
            return (if (sorted.size == 1) "p. " else "pp. ") + ranges.joinToString(", ")
        }

        private fun ellipsize(s: String, p: Paint, room: Float): String {
            if (p.measureText(s) <= room) return s
            var end = s.length
            while (end > 0 && p.measureText(s, 0, end) + p.measureText("…") > room) end--
            return s.substring(0, end).trimEnd() + "…"
        }
    }
}

/** A finished exam on the board: score and per-item results. Drawn in dp like [CardFace]. */
object ScoreFace {
    fun layout(exam: Exam, study: BoardStudy, widthDp: Float, fonts: CardFace.Fonts): Pair<Float, (Canvas) -> Unit> {
        val w = max(widthDp, 200f)
        val pad = CardFace.PAD
        val items = exam.itemIds.mapNotNull { study.item(it) }
        val verdicts = items.map { exam.outcome(it, study) }
        val score = verdicts.sumOf { when (it) { Verdict.Correct -> 1.0; Verdict.Partial -> 0.5; else -> 0.0 } }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.bold; textSize = 13f; color = 0xFF5C4A87.toInt(); letterSpacing = 0.06f }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.bold; textSize = 34f; color = 0xFF2A2830.toInt() }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts.medium; textSize = 13f; color = 0xFF6C6873.toInt() }
        val rowH = 22f
        val height = pad + 18f + 44f + 10f + items.size * rowH + pad
        val minutes = ((exam.finishedAt ?: exam.endsAt) - exam.startedAt).coerceAtLeast(0) / 60_000
        val scoreText = (if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(score)) + " / ${items.size}"
        return height to { c ->
            val shape = RectF(0f, 0f, w, height)
            c.drawRoundRect(RectF(1f, 3f, w + 1f, height + 4f), 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 })
            c.drawRoundRect(shape, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            c.drawRoundRect(shape, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0x26000000 })
            c.drawText("EXAM · $minutes MIN", pad, pad + 12f, title)
            c.drawText(scoreText, pad, pad + 18f + 36f, big)
            var y = pad + 18f + 44f + 10f
            items.forEachIndexed { i, item ->
                val colors = CardFace.statusColors(verdicts[i])
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors?.second ?: 0xFFB9B4BF.toInt() }
                c.drawCircle(pad + 5f, y + 7f, 5f, dot)
                val label = "${i + 1}. ${item.topic.ifBlank { item.kind.label }} · ${verdicts[i]?.label ?: "Not answered"}"
                c.drawText(label, pad + 18f, y + 12f, small)
                y += rowH
            }
        }
    }
}
