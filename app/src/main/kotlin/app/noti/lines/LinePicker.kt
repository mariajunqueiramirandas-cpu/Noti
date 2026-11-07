package app.noti.lines

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Which lines a loop or a tap means, all in page units. Pure geometry: shared by PDFs (lines
 * found in the pixels) and Noti's own lined paper (lines known exactly).
 */
object LinePicker {
    /** [loop] as x y pairs: left, top, right, bottom. */
    fun bounds(loop: FloatArray): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until loop.size - 1 step 2) {
            l = min(l, loop[i]); r = max(r, loop[i]); t = min(t, loop[i + 1]); b = max(b, loop[i + 1])
        }
        return floatArrayOf(l, t, r, b)
    }

    /** A tap, not a loop: every point within [tolerance] of the first. */
    fun isTap(loop: FloatArray, tolerance: Float): Boolean {
        if (loop.size < 2) return true
        val b = bounds(loop)
        return b[2] - b[0] <= tolerance && b[3] - b[1] <= tolerance
    }

    /** Even-odd test against the closed polygon [loop]. */
    fun inside(loop: FloatArray, x: Float, y: Float): Boolean {
        val n = loop.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = loop[i * 2]; val yi = loop[i * 2 + 1]; val xj = loop[j * 2]; val yj = loop[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Where the closed [loop] crosses the height [y]: the leftmost and rightmost crossing, or null. */
    fun span(loop: FloatArray, y: Float): Pair<Float, Float>? {
        val n = loop.size / 2
        if (n < 3) return null
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        var j = n - 1
        for (i in 0 until n) {
            val xi = loop[i * 2]; val yi = loop[i * 2 + 1]; val xj = loop[j * 2]; val yj = loop[j * 2 + 1]
            if ((yi > y) != (yj > y)) {
                val x = (xj - xi) * (y - yi) / (yj - yi) + xi
                lo = min(lo, x); hi = max(hi, x)
            }
            j = i
        }
        return if (hi > lo) lo to hi else null
    }

    /**
     * The lines a loop takes: a line counts when most of it is inside (60%), else the part
     * inside counts when it's at least [minLength] long (loop half a line to write on half
     * of it). Points are tested on the line and just above it, where the writing goes.
     */
    fun inLoop(lines: List<WriteLine>, loop: FloatArray, minLength: Float): List<WriteLine> {
        val out = ArrayList<WriteLine>()
        for (l in lines) {
            val from = l.x0; val to = l.x1
            val n = 48
            val hits = BooleanArray(n + 1) { i ->
                val x = from + (to - from) * i / n
                val y = l.yAt(x)
                inside(loop, x, y) || inside(loop, x, y - min(l.room, 40f) * 0.35f)
            }
            val count = hits.count { it }
            if (count == 0) continue
            if (count >= 0.6f * (n + 1)) { out += l; continue }
            val first = hits.indexOfFirst { it }; val last = hits.indexOfLast { it }
            val a = from + (to - from) * first / n; val b = from + (to - from) * last / n
            if (b - a < minLength || l.cells != null) continue
            out += l.copy(x0 = max(l.x0, a), x1 = min(l.x1, b), start = max(l.start, a), y = l.yAt(max(l.x0, a)))
        }
        return readingOrder(out)
    }

    /** The line a tap means: the nearest one the tap is on or just above, within its room. */
    fun atPoint(lines: List<WriteLine>, x: Float, y: Float, slack: Float): WriteLine? =
        lines.filter { l -> x >= l.x0 - slack && x <= l.x1 + slack && y <= l.yAt(x) + slack && y >= l.yAt(x) - max(l.room, slack * 2) }
            .minByOrNull { l -> abs(l.yAt(x) - y) }

    /** Top to bottom; lines at one height (cells in a table row) left to right. */
    fun readingOrder(lines: List<WriteLine>): List<WriteLine> {
        if (lines.size < 2) return lines
        val sorted = lines.sortedBy { it.yAt(it.x0) }
        val rows = ArrayList<MutableList<WriteLine>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            val ref = row?.first()
            if (ref != null && abs(ref.yAt(l.x0) - l.yAt(l.x0)) <= min(ref.room, l.room).coerceAtLeast(1f) * 0.4f) row += l
            else rows += mutableListOf(l)
        }
        return rows.flatMap { r -> r.sortedBy { it.x0 } }
    }

    /** A line along the bottom of the loop, for when there are no lines to find (blank paper). */
    fun oneLine(loop: FloatArray, maxRoom: Float): WriteLine {
        val b = bounds(loop)
        val room = min(b[3] - b[1], maxRoom).coerceAtLeast(1f)
        val y = b[3] - (b[3] - b[1]) * 0.12f
        return WriteLine(b[0], b[2], y, room = room)
    }

    /**
     * Noti's own lined paper: lines every [step] units from the top, the first [skip] left
     * out (the Lined template leaves a top margin). Those inside the loop, as wide as it is
     * there.
     */
    fun paperLines(loop: FloatArray, step: Float, skip: Int, minLength: Float): List<WriteLine> {
        if (step <= 0f) return emptyList()
        val b = bounds(loop)
        val out = ArrayList<WriteLine>()
        var k = max(skip, ceil(b[1] / step).toInt())
        while (k * step <= b[3]) {
            val y = k * step
            // Writing sits above the line: the loop is checked where letters would be.
            val s = span(loop, y - step * 0.35f) ?: span(loop, y)
            if (s != null && s.second - s.first >= minLength) out += WriteLine(s.first, s.second, y, room = step)
            k++
        }
        return out
    }

    /** The paper line a tap means: the first one at or below it, from the tap to [right]. */
    fun paperLineAt(x: Float, y: Float, step: Float, skip: Int, right: Float): WriteLine? {
        if (step <= 0f || right - x <= 0f) return null
        val k = max(skip, ceil(y / step - 0.05f).toInt())
        return WriteLine(x, right, k * step, room = step)
    }

    /**
     * The fields of a form page, in reading order: each blank is one field, but consecutive
     * answer lines (same ends, regular spacing, nothing printed between) are one field, and
     * so are the lines of one box.
     */
    fun fields(lines: List<WriteLine>, mmPerUnit: Float): List<List<WriteLine>> {
        val tol = 3f / mmPerUnit
        val out = ArrayList<MutableList<WriteLine>>()
        for (l in readingOrder(lines.filter { it.sure })) {
            val field = out.lastOrNull()
            val prev = field?.last()
            val joins = prev != null && l.kind == prev.kind && l.cells == null && prev.cells == null &&
                l.kind != LineKind.Cell && abs(l.x0 - prev.x0) <= tol && abs(l.x1 - prev.x1) <= tol &&
                l.start <= l.x0 + tol && l.label == null &&
                (l.yAt(l.x0) - prev.yAt(l.x0)).let { gap -> gap > 0f && gap <= max(l.room, prev.room) * 1.7f + tol }
            if (joins) field!! += l else out += mutableListOf(l)
        }
        return out
    }
}
