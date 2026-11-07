package app.noti.lines

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A line to write on, in page units (a page is 1000 wide). Text sits on its top edge: at x
 * that is [y] + (x - [x0]) · [slope]. Writing runs from [start] to [x1].
 */
data class WriteLine(
    val x0: Float,
    val x1: Float,
    val y: Float,
    val slope: Float = 0f,
    /** Clear space above it, for sizing the text. */
    val room: Float,
    val start: Float = x0,
    val kind: LineKind = LineKind.Rule,
    val sure: Boolean = true,
    /** [LineKind.Comb]: the x of each box wall. */
    val cells: FloatArray? = null,
    /** The printed label before it (left, top, right, bottom), for naming form fields. */
    val label: FloatArray? = null,
) {
    val length get() = x1 - x0
    fun yAt(x: Float) = y + (x - x0) * slope

    override fun equals(other: Any?) = other is WriteLine && x0 == other.x0 && x1 == other.x1 && y == other.y &&
        slope == other.slope && room == other.room && start == other.start && kind == other.kind &&
        cells.contentEquals(other.cells)
    override fun hashCode() = ((x0.hashCode() * 31 + x1.hashCode()) * 31 + y.hashCode()) * 31 + kind.hashCode()

    /** The same line moved by ([dx], [dy]). */
    fun moved(dx: Float, dy: Float) = copy(x0 = x0 + dx, x1 = x1 + dx, y = y + dy, start = start + dx,
        cells = cells?.let { c -> FloatArray(c.size) { c[it] + dx } },
        label = label?.let { floatArrayOf(it[0] + dx, it[1] + dy, it[2] + dx, it[3] + dy) })
}

enum class TextAlign { Left, Center }

/** What to do with text that doesn't fit the lines. */
enum class Overflow {
    /** Leave it out (the panel says how much); shown faded below the lines meanwhile. */
    Stop,
    /** Smaller text on the same lines, down to a readable floor. */
    Shrink,
    /** More lines below the last one, at the same spacing. */
    KeepGoing,
}

data class TypeStyle(
    /** Em size in page units; null fits it to the lines. */
    val size: Float? = null,
    val align: TextAlign = TextAlign.Left,
    val bold: Boolean = false,
    val overflow: Overflow = Overflow.Stop,
)

/** One word's place: the left end of its baseline, and the line's angle. */
class PlacedWord(val text: String, val x: Float, val y: Float, val slope: Float, val line: Int)

/** Widths of text in a font, at an em of 1. */
fun interface Measure { fun width(text: String): Float }

/**
 * Text laid out on lines.
 *
 * [words] are on the given lines (and any extra lines [Overflow.KeepGoing] added); [overflow]
 * are words that didn't fit, placed on make-believe lines below for a faded preview. [caret]
 * is where the next character would go: x, y (baseline), slope.
 */
class Laid(
    val em: Float,
    val words: List<PlacedWord>,
    val overflow: List<PlacedWord>,
    val lines: List<WriteLine>,
    val caret: FloatArray?,
) {
    val fits get() = overflow.isEmpty()
}

/**
 * Lays typed text onto writing lines: one size for the whole block, fitted to the room above
 * the lines; each line from its start (after a label) to its end, words wrapping to the next
 * line and Return starting one; one character per box on combs (docs/TYPE_ON_LINES.md §7).
 *
 * Pure arithmetic over [Measure], so it is tested without fonts and shared by the live
 * preview and the ink, which therefore always agree.
 */
class LineLayout(private val measure: Measure, private val mmPerUnit: Float) {
    private fun mm(v: Float) = v / mmPerUnit

    /** The em that fits [lines]: about 0.62 of the room above them, within readable sizes. */
    fun autoEm(lines: List<WriteLine>): Float {
        if (lines.isEmpty()) return mm(3.5f)
        val rooms = lines.map { it.room }.sorted()
        var em = (rooms[rooms.size / 2] * 0.62f).coerceIn(mm(MIN_EM_MM), mm(MAX_EM_MM))
        for (l in lines) {
            val c = l.cells ?: continue
            val narrow = (1 until c.size).minOf { c[it] - c[it - 1] }
            em = min(em, narrow * 0.85f / DIGIT)
        }
        return em
    }

    fun layout(lines: List<WriteLine>, text: String, style: TypeStyle = TypeStyle()): Laid {
        if (lines.isEmpty()) return Laid(mm(3.5f), emptyList(), emptyList(), lines, null)
        val em = style.size ?: autoEm(lines)
        val first = attempt(lines, text, em, style.align)
        if (first.fits || text.isBlank()) return first
        return when (style.overflow) {
            Overflow.Stop -> first
            Overflow.Shrink -> {
                var lo = min(mm(SHRINK_FLOOR_MM), em); var hi = em
                var best = attempt(lines, text, lo, style.align)
                if (!best.fits) return best
                repeat(14) {
                    val mid = (lo + hi) / 2
                    val t = attempt(lines, text, mid, style.align)
                    if (t.fits) { lo = mid; best = t } else hi = mid
                }
                best
            }
            Overflow.KeepGoing -> {
                val more = lines.toMutableList()
                var result = first
                while (!result.fits && more.size < lines.size + MAX_EXTRA) {
                    val need = result.overflow.maxOf { it.line } - more.size + 1
                    repeat(max(1, need)) { more += extraLine(more, em) }
                    result = attempt(more, text, em, style.align)
                }
                result
            }
        }
    }

    /** The next line below [lines]: same spacing and ends as the last. */
    private fun extraLine(lines: List<WriteLine>, em: Float): WriteLine {
        val last = lines.last()
        val gaps = lines.zipWithNext { a, b -> b.yAt(b.x0) - a.yAt(b.x0) }.filter { it > em }
        val pitch = if (gaps.isNotEmpty()) gaps.sorted()[gaps.size / 2] else max(last.room, em * 1.6f)
        return WriteLine(last.x0, last.x1, last.y + pitch, last.slope, pitch, last.start,
            if (last.kind == LineKind.Comb) LineKind.Rule else last.kind)
    }

    private fun attempt(lines: List<WriteLine>, text: String, em: Float, align: TextAlign): Laid {
        val all = lines.toMutableList()
        val rows = ArrayList<MutableList<Pending>>()
        repeat(all.size) { rows += ArrayList<Pending>() }
        var at = 0          // line index
        var used = 0f       // advance used on that line
        val space = measure.width(" ") * em
        var caretLine = 0; var caretAdvance = 0f

        fun ensure(i: Int) {
            while (all.size <= i) { all += extraLine(all, em); rows += ArrayList<Pending>() }
        }
        fun width(i: Int): Float { ensure(i); return available(all[i], em) }

        val paragraphs = text.split('\n')
        for ((p, paragraph) in paragraphs.withIndex()) {
            if (p > 0) { at++; used = 0f }
            ensure(at)
            if (all[at].cells != null) {
                // One character per box; Return or running out of boxes goes to the next line.
                for (ch in paragraph.codePointsList()) {
                    ensure(at)
                    var cells = all[at].cells
                    if (cells == null) { at = placeWords(listOf(ch), at, 0f, em, space, rows, ::width, ::ensure).first; continue }
                    val box = used.toInt()
                    if (box >= cells.size - 1) {
                        at++; used = 0f; ensure(at); cells = all[at].cells
                        if (cells == null) { at = placeWords(listOf(ch), at, 0f, em, space, rows, ::width, ::ensure).first; continue }
                    }
                    if (ch != " ") rows[at] += Pending(ch, used.toInt().toFloat(), measure.width(ch) * em, comb = true)
                    used += 1f
                }
                caretLine = at; caretAdvance = used
                continue
            }
            val words = paragraph.split(' ').filter { it.isNotEmpty() }
            val (line, adv) = placeWords(words, at, used, em, space, rows, ::width, ::ensure)
            at = line; used = adv
            caretLine = at
            caretAdvance = used + if (paragraph.endsWith(" ") && used > 0f) space else 0f
        }

        val placed = ArrayList<PlacedWord>()
        val over = ArrayList<PlacedWord>()
        for ((i, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            val line = all[i]
            val target = if (i < lines.size) placed else over
            if (line.cells != null) {
                for (w in row) {
                    val box = w.advance.toInt()
                    val center = (line.cells[box] + line.cells[box + 1]) / 2f
                    target += word(line, w.text, center - w.width / 2f, em, i)
                }
                continue
            }
            val usedWidth = row.last().advance + row.last().width
            val shift = if (align == TextAlign.Center) max(0f, (available(line, em) - usedWidth) / 2f) else 0f
            for (w in row) target += word(line, w.text, lineStart(line, em) + (shift + w.advance) * cosine(line), em, i)
        }
        val caret = run {
            ensure(caretLine)
            val line = all[caretLine]
            val x = if (line.cells != null) {
                val c = line.cells; val box = caretAdvance.toInt().coerceAtMost(c.size - 2)
                if (caretAdvance.toInt() >= c.size - 1) c.last() else (c[box] + c[box + 1]) / 2f
            } else {
                val usedHere = rows[caretLine].lastOrNull()?.let { it.advance + it.width } ?: 0f
                val shift = if (align == TextAlign.Center) max(0f, (available(line, em) - usedHere) / 2f) else 0f
                lineStart(line, em) + (shift + caretAdvance) * cosine(line)
            }
            floatArrayOf(x, baseline(line, x, em), line.slope)
        }
        return Laid(em, placed, over, lines.toList(), caret)
    }

    private class Pending(val text: String, val advance: Float, val width: Float, val comb: Boolean = false)

    /** Places [words] from line [at] (with [used] taken), wrapping; returns the line and advance after the last word. */
    private fun placeWords(words: List<String>, at0: Int, used0: Float, em: Float, space: Float,
        rows: MutableList<MutableList<Pending>>, width: (Int) -> Float, ensure: (Int) -> Unit): Pair<Int, Float> {
        var at = at0; var used = used0
        for (word in words) {
            var rest = word
            while (rest.isNotEmpty()) {
                val avail = width(at)
                val gap = if (used > 0f) space else 0f
                val w = measure.width(rest) * em
                if (used + gap + w <= avail + 1e-3f) {
                    rows[at] += Pending(rest, used + gap, w)
                    used += gap + w
                    rest = ""
                } else if (used > 0f) {
                    at++; used = 0f; ensure(at)
                } else {
                    // Longer than a whole line: as much as fits here, the rest on the next line.
                    val cut = fitting(rest, avail, em)
                    rows[at] += Pending(rest.substring(0, cut), 0f, measure.width(rest.substring(0, cut)) * em)
                    rest = rest.substring(cut)
                    at++; used = 0f; ensure(at)
                }
            }
        }
        return at to used
    }

    /** How many leading characters of [s] fit in [width] (at least one). */
    private fun fitting(s: String, width: Float, em: Float): Int {
        var n = 1
        while (n < s.length && measure.width(s.substring(0, n + 1)) * em <= width) n++
        return n
    }

    private fun cosine(line: WriteLine) = 1f / sqrt(1f + line.slope * line.slope)

    /** Where text starts on [line], in x: its start plus a small inset. */
    fun lineStart(line: WriteLine, em: Float) = max(line.start, line.x0) + inset(line, em)

    private fun inset(line: WriteLine, em: Float) = if (line.kind == LineKind.Cell || line.kind == LineKind.Box) em * 0.12f else em * 0.25f

    /** The advance available along [line]. */
    fun available(line: WriteLine, em: Float): Float =
        max(0f, (line.x1 - inset(line, em) - lineStart(line, em)) / cosine(line))

    /** The baseline at x: on the line's top edge, raised a hair so letters sit on it, not in it. */
    fun baseline(line: WriteLine, x: Float, em: Float) = line.yAt(x) - em * LIFT

    private fun word(line: WriteLine, text: String, x: Float, em: Float, index: Int) =
        PlacedWord(text, x, baseline(line, x, em), line.slope, index)

    companion object {
        const val MIN_EM_MM = 2.2f
        const val MAX_EM_MM = 5.2f
        const val SHRINK_FLOOR_MM = 1.6f
        /** Baseline lift above the line, in ems. */
        const val LIFT = 0.05f
        /** A digit's advance in ems, about (Nunito's are 0.57). */
        const val DIGIT = 0.62f
        const val MAX_EXTRA = 200
    }
}

/** The text's characters, keeping surrogate pairs together. */
internal fun String.codePointsList(): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < length) { val n = Character.charCount(codePointAt(i)); out += substring(i, i + n); i += n }
    return out
}
