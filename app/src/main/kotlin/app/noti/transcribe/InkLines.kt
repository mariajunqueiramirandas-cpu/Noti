package app.noti.transcribe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One selected stroke, in page units (a page is 1000 wide). [color] is premultiplied RGBA. */
class SelectedStroke(
    val id: Int,
    val page: Int,
    val color: FloatArray,
    /** The centreline: x0 y0 x1 y1 … */
    val points: FloatArray,
) {
    val x0: Float; val y0: Float; val x1: Float; val y1: Float
    init {
        var ax = Float.MAX_VALUE; var ay = Float.MAX_VALUE; var bx = -Float.MAX_VALUE; var by = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            ax = min(ax, points[i]); bx = max(bx, points[i]); ay = min(ay, points[i + 1]); by = max(by, points[i + 1])
        }
        x0 = ax; y0 = ay; x1 = bx; y1 = by
    }
    val width get() = x1 - x0
    val height get() = y1 - y0
    val centerY get() = (y0 + y1) / 2f
    /** Highlighter ink is translucent (40% alpha); pen ink is opaque. */
    val isWriting get() = color[3] >= 0.9f
}

/** One line of handwriting: its strokes in the order they were written, and its box. */
class InkLine(val strokes: List<SelectedStroke>) {
    val x0 = strokes.minOf { it.x0 }
    val y0 = strokes.minOf { it.y0 }
    val x1 = strokes.maxOf { it.x1 }
    val y1 = strokes.maxOf { it.y1 }
    val width get() = x1 - x0
    val height get() = y1 - y0
    val centerY get() = (y0 + y1) / 2f
}

object InkLines {
    /**
     * Reads InkEngine::selection_ink: [count, then per stroke: id, page, r, g, b, a, n,
     * then n capsules of x0 y0 r0 x1 y1 r1]. A stroke's capsules are a chain, so its
     * centreline is the first capsule's start and then every capsule's end.
     */
    fun parse(data: FloatArray): List<SelectedStroke> {
        if (data.isEmpty()) return emptyList()
        val count = data[0].toInt()
        val out = ArrayList<SelectedStroke>(count)
        var at = 1
        repeat(count) {
            if (at + 7 > data.size) return out
            val id = data[at].toInt(); val page = data[at + 1].toInt()
            val color = data.copyOfRange(at + 2, at + 6)
            val n = data[at + 6].toInt()
            at += 7
            if (n <= 0 || at + n * 6 > data.size) return out
            val points = FloatArray(2 + n * 2)
            points[0] = data[at]; points[1] = data[at + 1]
            for (j in 0 until n) {
                points[2 + j * 2] = data[at + j * 6 + 3]
                points[3 + j * 2] = data[at + j * 6 + 4]
            }
            out += SelectedStroke(id, page, color, points)
            at += n * 6
        }
        return out
    }

    /**
     * Groups handwriting into lines, top to bottom; each line keeps writing order.
     *
     * Letter-sized strokes join the line whose centre is nearest theirs, within 0.7 of a
     * letter's height. Dots, accents and commas are too small to say where a line is, so they
     * join the nearest line afterwards rather than starting one of their own.
     */
    fun group(strokes: List<SelectedStroke>): List<InkLine> {
        if (strokes.isEmpty()) return emptyList()
        // A letter's height: strokes' heights, leaving out dots and crossbars. (Widths would
        // mislead: one cursive stroke can be a whole word.)
        val tallest = strokes.maxOf { it.height }
        val letter = median(strokes.map { it.height }.filter { it >= tallest * 0.2f }).coerceAtLeast(1f)
        val (marks, small) = strokes.partition { it.height >= letter * 0.35f || it.width >= letter * 0.6f }
        class Building(var center: Float, val members: MutableList<SelectedStroke>)
        val lines = ArrayList<Building>()
        for (s in (marks.ifEmpty { small }).sortedBy { it.centerY }) {
            val near = lines.minByOrNull { abs(it.center - s.centerY) }
            // Descenders and ascenders sit off the line's centre by about a third of a letter.
            if (near != null && abs(near.center - s.centerY) <= letter * 0.7f) {
                near.members += s
                near.center = median(near.members.map { it.centerY })
            } else lines += Building(s.centerY, mutableListOf(s))
        }
        if (marks.isNotEmpty()) for (s in small) {
            // Prefer a line whose box holds the mark (a dot above an "i" sits just over it).
            val holding = lines.filter { line ->
                val top = line.members.minOf { it.y0 } - letter * 0.6f
                val bottom = line.members.maxOf { it.y1 } + letter * 0.3f
                s.centerY in top..bottom
            }
            (holding.ifEmpty { lines }).minBy { abs(it.center - s.centerY) }.members += s
        }
        return lines.map { b -> InkLine(b.members.sortedBy { it.id }) }.sortedBy { it.centerY }
    }

    /** The gap from one line to the next, for laying out text that runs past the last line. */
    fun pitch(lines: List<InkLine>): Float {
        val gaps = lines.zipWithNext { a, b -> b.centerY - a.centerY }.filter { it > 0 }
        return if (gaps.isEmpty()) median(lines.map { it.height }) * 1.6f else median(gaps)
    }

    internal fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
