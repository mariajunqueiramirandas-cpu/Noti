package app.noti.lines

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** A grey image: [luma] is 0 (black) … 255 (white) per pixel, row-major. */
class Raster(val width: Int, val height: Int, val luma: ByteArray) {
    init { require(luma.size == width * height) }
    fun at(x: Int, y: Int) = luma[y * width + x].toInt() and 0xFF

    companion object {
        /** From ARGB pixels (Bitmap.getPixels), on white: transparent counts as paper. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): Raster {
            val out = ByteArray(width * height)
            for (i in out.indices) {
                val c = argb[i]
                val a = (c ushr 24) and 0xFF
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val l = (r * 299 + g * 587 + b * 114) / 1000
                out[i] = (255 - (255 - l) * a / 255).toByte()
            }
            return Raster(width, height, out)
        }
    }
}

/** What a writing line is: where text goes on it differs. */
enum class LineKind {
    /** A solid line: answer lines, blanks after a label, the bottom of an underlined space. */
    Rule,
    /** Dots or dashes: `Name: ..........`. */
    Dotted,
    /** The bottom of a table cell or a one-line box: text sits on it, between the walls. */
    Cell,
    /** One of the writing lines Noti lays inside a box too tall for one line. */
    Box,
    /** A row of boxes, one character each (ID numbers, postcodes). */
    Comb,
}

/**
 * A writing line found in a [Raster], in its pixels.
 *
 * Text sits on the line's top edge: at x it is [y] + (x - [x0]) · [slope]. Writing goes from
 * [start] (after a label printed on the line, if any) to [x1].
 */
class FoundLine(
    val x0: Float,
    val x1: Float,
    val y: Float,
    val slope: Float,
    val thickness: Float,
    /** Clear space above the line, up to the printed text, line or frame above. */
    val room: Float,
    val start: Float,
    val kind: LineKind,
    /** False when it might not be meant for writing: an underlined heading, a frame's top, words already on it. */
    val sure: Boolean,
    /** [LineKind.Comb]: the x of each box wall, left to right (one box between each pair). */
    val cells: FloatArray? = null,
    /** The printed label just before the line (`Name:`), as left, top, right, bottom; or null. */
    val label: FloatArray? = null,
) {
    val length get() = x1 - x0
    fun yAt(x: Float) = y + (x - x0) * slope
    override fun toString() = "FoundLine($kind ${"%.1f".format(x0)}..${"%.1f".format(x1)} y=${"%.1f".format(y)} " +
        "room=${"%.1f".format(room)} start=${"%.1f".format(start)} sure=$sure)"
}

/**
 * Finds the lines on a page that are meant to be written on, from its pixels: so scans,
 * photos and vector PDFs all go through the same code. No model, no network; one pass or
 * two over the image (Type on lines, docs/TYPE_ON_LINES.md §6).
 *
 *  1. **Ink**: darker than the paper around it ([paperLevels]), so pale blue rules on grey
 *     scans count as well as black lines on white.
 *  2. **Skew**: the angle (within ±3°) that lines the ink up best into rows, so a crooked
 *     scan is straightened before anything else.
 *  3. **Lines**: long, thin horizontal runs. Letters are short, so text drops out.
 *     Dotted and dashed lines are chains of small marks at one height.
 *  4. **Judging** each line: walls and teeth rising from it (table cells, boxes, combs),
 *     printed text on it (a label, or an underlined heading), the label before it, and how
 *     much clear room there is above it for writing.
 *
 * Any thread; allocation is proportional to the image.
 */
class LineFinder(private val pxPerMm: Float) {
    private fun mm(v: Float) = v * pxPerMm
    private fun px(v: Float) = max(1, (v * pxPerMm).roundToInt())

    /** Lines in reading order (top to bottom, then left to right). */
    fun find(raster: Raster): List<FoundLine> {
        if (raster.width < 4 || raster.height < 4) return emptyList()
        val ink = inkMask(raster)
        val angle = skew(ink, raster.width, raster.height)
        val sheet = Sheared(ink, raster.width, raster.height, tan(angle))
        val solids = solidLines(sheet)
        val dotted = dottedLines(sheet, solids)
        val out = ArrayList<FoundLine?>()
        for (s in solids) out += judge(sheet, s, dotted = false)
        for (d in dotted) out += judge(sheet, d, dotted = true)
        return dedupe(out.filterNotNull()).sortedWith(compareBy<FoundLine>({ (it.y / mm(3f)).toInt() }, { it.x0 }))
    }

    // ---------------------------------------------------------------------------------
    // 1. Ink
    // ---------------------------------------------------------------------------------

    /** True where a pixel is darker than its paper by enough to be printed on. */
    internal fun inkMask(r: Raster): BooleanArray {
        val levels = paperLevels(r)
        val block = blockSize()
        val bw = (r.width + block - 1) / block
        val out = BooleanArray(r.width * r.height)
        for (y in 0 until r.height) {
            val row = y * r.width
            val brow = (y / block) * bw
            for (x in 0 until r.width) {
                val paper = levels[brow + x / block]
                val l = r.luma[row + x].toInt() and 0xFF
                out[row + x] = l < paper - max(22, paper * 12 / 100)
            }
        }
        return out
    }

    private fun blockSize() = max(8, px(4f))

    /**
     * The paper's brightness per block of about 4 mm: the 80th percentile of the block, then
     * the brightest of its neighbours, so a block full of text or a dark box still gets the
     * paper around it, and a shaded scan gets its local paper.
     */
    private fun paperLevels(r: Raster): IntArray {
        val block = blockSize()
        val bw = (r.width + block - 1) / block
        val bh = (r.height + block - 1) / block
        val raw = IntArray(bw * bh)
        val hist = IntArray(256)
        for (by in 0 until bh) for (bx in 0 until bw) {
            hist.fill(0)
            val x0 = bx * block; val x1 = min(r.width, x0 + block)
            val y0 = by * block; val y1 = min(r.height, y0 + block)
            for (y in y0 until y1) for (x in x0 until x1) hist[r.luma[y * r.width + x].toInt() and 0xFF]++
            val want = ((x1 - x0) * (y1 - y0) * 0.2f).toInt()
            var seen = 0; var level = 255
            while (level > 0) { seen += hist[level]; if (seen > want) break; level-- }
            raw[by * bw + bx] = level
        }
        val out = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var best = 0
            for (dy in -1..1) for (dx in -1..1) {
                val x = bx + dx; val y = by + dy
                if (x in 0 until bw && y in 0 until bh) best = max(best, raw[y * bw + x])
            }
            out[by * bw + bx] = best
        }
        return out
    }

    // ---------------------------------------------------------------------------------
    // 2. Skew
    // ---------------------------------------------------------------------------------

    /**
     * The scan's tilt, in radians: the angle whose sheared rows stack the ink most sharply
     * (sum of squared row counts). Straight pages (every vector PDF) give 0.
     */
    internal fun skew(ink: BooleanArray, w: Int, h: Int): Float {
        // Every row (thin rules are a row or two), every few columns; rows binned in pairs.
        val step = max(1, (pxPerMm / 2f).roundToInt())
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until h) {
            var x = 0
            while (x < w) { if (ink[y * w + x]) { xs += x; ys += y }; x += step }
        }
        if (xs.size < 50) return 0f
        val px = xs.toIntArray(); val py = ys.toIntArray()
        val bins = IntArray(h / 2 + w / 2 + 8)
        fun score(deg: Float): Double {
            val t = tan(Math.toRadians(deg.toDouble())).toFloat()
            bins.fill(0)
            val off = w * abs(t) / 2 + 2
            for (i in px.indices) {
                val b = ((py[i] - px[i] * t) / 2 + off).toInt()
                if (b in bins.indices) bins[b]++
            }
            var s = 0.0
            for (c in bins) s += c.toDouble() * c
            return s
        }
        val flat = score(0f)
        var best = 0f; var bestScore = flat
        var d = -3f
        while (d <= 3.001f) { val s = score(d); if (s > bestScore) { bestScore = s; best = d }; d += 0.1f }
        if (best == 0f) return 0f
        // A real tilt stacks the ink clearly better; noise doesn't.
        if (bestScore < flat * 1.04) return 0f
        // Refine on every ink pixel, one-pixel rows: a long line then stays within a row end to end.
        val fx = ArrayList<Int>(); val fy = ArrayList<Int>()
        val stride = max(1, xs.size * step / 200_000)
        var seen = 0
        for (yy in 0 until h) for (xx in 0 until w) if (ink[yy * w + xx] && seen++ % stride == 0) { fx += xx; fy += yy }
        val qx = fx.toIntArray(); val qy = fy.toIntArray()
        val fine = IntArray(h + w + 8)
        fun fineScore(deg: Float): Double {
            val t = tan(Math.toRadians(deg.toDouble())).toFloat()
            fine.fill(0)
            val off = w * abs(t) + 2
            for (i in qx.indices) {
                val b = (qy[i] - qx[i] * t + off).roundToInt()
                if (b in fine.indices) fine[b]++
            }
            var s = 0.0
            for (c in fine) s += c.toDouble() * c
            return s
        }
        var angle = best; var angleScore = fineScore(best)
        d = best - 0.12f
        while (d <= best + 0.1201f) { val s = fineScore(d); if (s > angleScore) { angleScore = s; angle = d }; d += 0.01f }
        if (abs(angle) < 0.06f) return 0f
        return Math.toRadians(angle.toDouble()).toFloat()
    }

    /** The ink with each column shifted up by x · [tilt], so tilted lines become rows. */
    internal class Sheared(private val ink: BooleanArray, val width: Int, private val srcHeight: Int, val tilt: Float) {
        /** Rows are shifted by this much so none go negative. */
        val offset = if (tilt > 0) ceil((width - 1) * tilt) else 0f
        val height = if (tilt == 0f) srcHeight else srcHeight + ceil(abs(tilt) * width).toInt() + 1
        private val data: BooleanArray? = if (tilt == 0f) null else BooleanArray(width * height).also { out ->
            for (y in 0 until srcHeight) for (x in 0 until width) if (ink[y * width + x]) {
                val sy = (y - x * tilt + offset).roundToInt()
                if (sy in 0 until height) out[sy * width + x] = true
            }
        }
        operator fun get(x: Int, y: Int): Boolean {
            if (x < 0 || x >= width || y < 0 || y >= height) return false
            return if (data == null) ink[y * width + x] else data[y * width + x]
        }
        /** Back to the raster: the y of sheared row [sy] at column [x]. */
        fun sourceY(x: Float, sy: Float) = sy - offset + x * tilt
    }

    // ---------------------------------------------------------------------------------
    // 3. Lines
    // ---------------------------------------------------------------------------------

    /**
     * A horizontal mark in sheared pixels: rows [top]..[bottom], columns [x0]..[x1]. Its runs
     * give its exact course: a line a hair off the straightened tilt steps from row to row, and
     * the fit follows it ([topAt], [slope]).
     */
    internal class Segment(var x0: Int, var x1: Int, var top: Int, var bottom: Int) {
        val length get() = x1 - x0 + 1
        val runs = ArrayList<IntArray>()   // y, x0, x1
        private var a = top.toFloat()
        var slope = 0f; private set
        var thickness = (bottom - top + 1).toFloat(); private set

        fun add(y: Int, from: Int, to: Int) { runs += intArrayOf(y, from, to) }



        /**
         * Fits the top edge through the runs: the median top row of the left half and of the
         * right half give the slope (robust to ragged, noisy edges), the median of each
         * column's top against that slope the height. The thickness is the most rows any
         * column has.
         */
        fun fit() {
            if (runs.isEmpty()) { a = top.toFloat(); thickness = (bottom - top + 1).toFloat(); return }
            val tops = IntArray(length) { Int.MAX_VALUE }
            val cover = IntArray(length)
            for (r in runs) for (x in r[1]..r[2]) { val i = x - x0; tops[i] = min(tops[i], r[0]); cover[i]++ }
            fun median(from: Int, to: Int): Pair<Float, Float>? {
                val ys = ArrayList<Int>(); var sx = 0.0
                for (i in from until to) if (tops[i] != Int.MAX_VALUE) { ys += tops[i]; sx += i }
                if (ys.isEmpty()) return null
                ys.sort()
                return (x0 + (sx / ys.size).toFloat()) to ys[ys.size / 2].toFloat()
            }
            val left = median(0, length / 2); val right = median(length / 2, length)
            slope = if (left != null && right != null && right.first - left.first > 1f)
                ((right.second - left.second) / (right.first - left.first)).coerceIn(-0.02f, 0.02f) else 0f
            val offsets = ArrayList<Float>()
            for (i in 0 until length) if (tops[i] != Int.MAX_VALUE) offsets += tops[i] - slope * (x0 + i)
            offsets.sort()
            a = offsets[offsets.size / 2]
            thickness = cover.max().toFloat()
        }

        /** The top edge at column [x], in sheared rows. */
        fun topAt(x: Float) = a + slope * x
    }

    /**
     * Long, thin horizontal marks: runs of at least 2 mm in a row, joined into lines when they
     * overlap or nearly touch in rows up to two apart. A line a hair off the straightened tilt
     * steps between neighbouring rows; its pieces join into one line all the same.
     */
    internal fun solidLines(s: Sheared): List<Segment> {
        val minLen = px(6f)
        val piece = px(2f)
        val gap = max(1, px(0.15f))
        val reach = gap + 2
        val maxThick = max(3, px(1.6f))
        // Runs per row: y, x0, x1.
        val runs = ArrayList<IntArray>()
        val rowStart = IntArray(s.height + 1)
        for (y in 0 until s.height) {
            rowStart[y] = runs.size
            var x = 0
            while (x < s.width) {
                if (!s[x, y]) { x++; continue }
                val start = x
                var end = x
                var miss = 0
                while (x < s.width) {
                    if (s[x, y]) { end = x; miss = 0 } else if (++miss > gap) break
                    x++
                }
                if (end - start + 1 >= piece) runs += intArrayOf(y, start, end)
            }
        }
        rowStart[s.height] = runs.size
        val parent = IntArray(runs.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun near(a: IntArray, b: IntArray) = min(a[2], b[2]) - max(a[1], b[1]) >= -reach
        for (i in runs.indices) {
            val y = runs[i][0]
            for (dy in 0..2) {
                val row = y - dy
                if (row < 0) continue
                for (j in rowStart[row] until rowStart[row + 1]) {
                    if (j >= i) break
                    if (near(runs[i], runs[j])) { val a = find(i); val b = find(j); if (a != b) parent[a] = b }
                }
            }
        }
        val segments = HashMap<Int, Segment>()
        for (i in runs.indices) {
            val r = runs[i]
            val seg = segments.getOrPut(find(i)) { Segment(r[1], r[2], r[0], r[0]) }
            seg.x0 = min(seg.x0, r[1]); seg.x1 = max(seg.x1, r[2]); seg.top = min(seg.top, r[0]); seg.bottom = max(seg.bottom, r[0])
            seg.add(r[0], r[1], r[2])
        }
        return segments.values.filter { it.length >= minLen }.onEach { it.fit() }
            .filter { it.thickness <= maxThick }.sortedWith(compareBy({ it.top }, { it.x0 })).onEach { seg -> absorbEdges(s, seg) }
    }

    /**
     * A tilted line, straightened, has a ragged edge: part of a row above or below it. Those
     * rows are the line, not text resting on it, so they join it (a row or two at most).
     */
    private fun absorbEdges(s: Sheared, seg: Segment) {
        fun cover(y: Int): Float {
            var n = 0
            for (x in seg.x0..seg.x1) if (s[x, y]) n++
            return n.toFloat() / seg.length
        }
        val rows = if (s.tilt == 0f) 1 else 2
        repeat(rows) { if (cover(seg.top - 1) >= 0.25f) seg.top-- }
        repeat(rows) { if (cover(seg.bottom + 1) >= 0.25f) seg.bottom++ }
    }

    /** Dots and dashes at one height, close and regular enough to be a line. */
    internal fun dottedLines(s: Sheared, solids: List<Segment>): List<Segment> {
        val marks = smallMarks(s, solids)
        if (marks.size < 5) return emptyList()
        val sameRow = mm(0.4f)
        val out = ArrayList<Segment>()
        val byY = marks.sortedBy { it.cy }
        val used = BooleanArray(byY.size)
        for (i in byY.indices) {
            if (used[i]) continue
            val row = ArrayList<Mark>()
            for (j in i until byY.size) {
                if (byY[j].cy - byY[i].cy > sameRow) break
                if (!used[j]) row += byY[j]
            }
            row.sortBy { it.x0 }
            var chain = ArrayList<Mark>()
            fun close() {
                if (chain.size >= 5) {
                    val len = chain.last().x1 - chain.first().x0 + 1
                    val gaps = chain.zipWithNext { a, b -> (b.x0 - a.x1).toFloat() }
                    val mean = gaps.average().toFloat()
                    val spread = gaps.map { abs(it - mean) }.average().toFloat()
                    if (len >= mm(8f) && spread <= max(mean * 0.6f, 1.5f)) {
                        out += Segment(chain.first().x0, chain.last().x1, chain.minOf { it.y0 }, chain.maxOf { it.y1 })
                        chain.forEach { m -> used[byY.indexOf(m)] = true }
                    }
                }
                chain = ArrayList()
            }
            for (m in row) {
                val last = chain.lastOrNull()
                val maxGap = max(mm(2.5f), 3f * (m.x1 - m.x0 + 1))
                if (last != null && (m.x0 - last.x1 > maxGap || m.h > 2.2f * last.h || last.h > 2.2f * m.h)) close()
                chain += m
            }
            close()
        }
        return out
    }

    internal class Mark(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val cy get() = (y0 + y1) / 2f
        val h get() = y1 - y0 + 1
    }

    /** Connected marks no taller than a dot or dash, outside the solid lines. */
    private fun smallMarks(s: Sheared, solids: List<Segment>): List<Mark> {
        val maxH = max(2, px(1.0f)); val maxW = px(4f)
        // Runs per row, labelled with union-find; bounding boxes per label.
        val parent = ArrayList<Int>()
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val runX0 = ArrayList<Int>(); val runX1 = ArrayList<Int>(); val runY = ArrayList<Int>()
        var prev = IntArray(0); var prevCount = 0
        val inSolid = { x: Int, y: Int -> solids.any { y >= it.top - 1 && y <= it.bottom + 1 && x >= it.x0 && x <= it.x1 } }
        for (y in 0 until s.height) {
            val cur = ArrayList<Int>()
            var x = 0
            while (x < s.width) {
                if (!s[x, y]) { x++; continue }
                val start = x
                while (x < s.width && s[x, y]) x++
                val id = parent.size
                parent += id; runX0 += start; runX1 += x - 1; runY += y
                cur += id
            }
            var p = 0
            for (id in cur) {
                while (p < prevCount && runX1[prev[p]] < runX0[id] - 1) p++
                var q = p
                while (q < prevCount && runX0[prev[q]] <= runX1[id] + 1) {
                    val a = find(id); val b = find(prev[q]); if (a != b) parent[a] = b
                    q++
                }
            }
            prev = cur.toIntArray(); prevCount = prev.size
        }
        val boxes = HashMap<Int, IntArray>()
        for (id in parent.indices) {
            val r = find(id)
            val b = boxes.getOrPut(r) { intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1) }
            b[0] = min(b[0], runX0[id]); b[1] = min(b[1], runY[id]); b[2] = max(b[2], runX1[id]); b[3] = max(b[3], runY[id])
        }
        return boxes.values.filter { b ->
            val w = b[2] - b[0] + 1; val h = b[3] - b[1] + 1
            h <= maxH && w <= maxW && w >= max(1, px(0.12f)) && w * 3 >= h && !inSolid((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
        }.map { Mark(it[0], it[2], it[1], it[3]) }
    }

    // ---------------------------------------------------------------------------------
    // 4. Judging
    // ---------------------------------------------------------------------------------

    /** Thin marks rising from (or hanging below) a line: walls, teeth, stems. */
    private class Attachment(val x0: Int, val x1: Int, val height: Int) { val cx get() = (x0 + x1) / 2f }

    private fun attachments(s: Sheared, seg: Segment, up: Boolean): List<Attachment> {
        val maxW = max(2, px(1.6f)); val minH = max(2, px(0.8f)); val look = px(40f)
        val heights = IntArray(seg.x1 - seg.x0 + 1) { i ->
            val x = seg.x0 + i
            var k = 0
            if (up) { while (k < look && s[x, seg.top - 1 - k]) k++ }
            else { while (k < look && s[x, seg.bottom + 1 + k]) k++ }
            k
        }
        val out = ArrayList<Attachment>()
        var i = 0
        while (i < heights.size) {
            if (heights[i] < minH) { i++; continue }
            val start = i
            var tall = 0
            while (i < heights.size && heights[i] >= minH) { tall = max(tall, heights[i]); i++ }
            // Wider than a wall: printed text resting on the line, not a wall.
            if (i - start <= maxW) out += Attachment(seg.x0 + start, seg.x0 + i - 1, tall)
        }
        return out
    }

    private fun judge(s: Sheared, seg: Segment, dotted: Boolean): List<FoundLine?> {
        val ups = if (dotted) emptyList() else attachments(s, seg, up = true)
        val downs = if (dotted) emptyList() else attachments(s, seg, up = false)
        val wallH = px(4f)
        val near = mm(1.5f)
        val atLeft = { a: Attachment -> a.cx - seg.x0 <= near }
        val atRight = { a: Attachment -> seg.x1 - a.cx <= near }

        // A short mark with stems is a letter's bar ('T', 'E'), not a line, unless it's a cell between two walls.
        if (seg.length < mm(15f) && (ups.isNotEmpty() || downs.isNotEmpty())) {
            val walled = ups.count { it.height >= wallH && (atLeft(it) || atRight(it)) } >= 2
            if (!walled) return emptyList()
        }

        // A row of character boxes: three or more even teeth along the line.
        comb(seg, ups)?.let { teeth ->
            val thick = seg.thickness
            val room = teeth.second.toFloat()
            val cells = teeth.first
            return listOf(line(s, seg, cells.first(), cells.last(), 0f, thick, room, cells.first(), LineKind.Comb, true,
                cells = cells, label = labelBefore(s, cells.first().toInt(), seg.top)))
        }

        val walls = ups.filter { it.height >= wallH }.sortedBy { it.cx }
        // Only walls hanging down at its ends: the top edge of a box or table. Nothing goes on it.
        val frameTop = walls.isEmpty() && downs.count { it.height >= wallH && (atLeft(it) || atRight(it)) } >= 2

        if (walls.size >= 2) return cells(s, seg, walls)
        return listOf(plain(s, seg, if (dotted) LineKind.Dotted else LineKind.Rule, seg.x0.toFloat(), seg.x1.toFloat(),
            exclude = ups, forceUnsure = frameTop))
    }

    /** The cells between walls: a line inside each pair, or several in a tall box. */
    private fun cells(s: Sheared, seg: Segment, walls: List<Attachment>): List<FoundLine?> {
        val out = ArrayList<FoundLine?>()
        val inset = mm(0.6f)
        // Parts of the line outside the frame are ordinary lines.
        if (walls.first().x0 - seg.x0 >= mm(6f)) out += plain(s, seg, LineKind.Rule, seg.x0.toFloat(), walls.first().x0 - 1f, walls, false)
        if (seg.x1 - walls.last().x1 >= mm(6f)) out += plain(s, seg, LineKind.Rule, walls.last().x1 + 1f, seg.x1.toFloat(), walls, false)
        val thick = seg.thickness
        for ((a, b) in walls.zipWithNext()) {
            val left = a.x1 + 1 + inset; val right = b.x0 - 1 - inset
            if (right - left < mm(4f)) continue
            // Walls can run on past this cell (a table's middle border): the cell ends at its lid.
            val height = lid(s, left.toInt(), right.toInt(), seg.top, min(a.height, b.height)).toFloat()
            if (height >= mm(18f)) {
                // A box: lines from the top down, about a handwriting line apart.
                val pitch = mm(8f)
                val n = max(1, (height / pitch).roundToInt())
                val gap = height / n
                for (k in 1..n) {
                    out += line(s, seg, left, right, if (k == n) 0f else -(height - gap * k), if (k == n) thick else 0f,
                        gap, left, LineKind.Box, true)
                }
            } else {
                val room = clearRoom(s, left.toInt(), right.toInt(), seg.top, emptyList(), limit = height.toInt())
                out += line(s, seg, left, right, 0f, thick, room, left, LineKind.Cell, true)
            }
        }
        return out
    }

    /** Rows from [top] up to the first row that is ink across most of [a]..[b] (a cell's top), at most [limit]. */
    private fun lid(s: Sheared, a: Int, b: Int, top: Int, limit: Int): Int {
        val need = (b - a + 1) * 0.7f
        for (k in 1..limit) {
            var n = 0
            for (x in a..b) if (s[x, top - k]) n++
            if (n >= need) return k - 1
        }
        return limit
    }

    /** Evenly spaced teeth (with walls at both ends) make a comb: returns its walls' x and their height. */
    private fun comb(seg: Segment, ups: List<Attachment>): Pair<FloatArray, Int>? {
        val teeth = ups.filter { it.height >= px(1.2f) && it.height <= px(14f) }.sortedBy { it.cx }
        if (teeth.size < 4) return null
        val gaps = teeth.zipWithNext { a, b -> b.cx - a.cx }
        val mean = gaps.average().toFloat()
        if (mean < mm(2.5f) || mean > mm(15f)) return null
        if (gaps.any { abs(it - mean) > mean * 0.2f }) return null
        val heights = teeth.map { it.height }
        if (heights.max() > heights.min() * 1.6f + px(0.5f)) return null
        // The teeth must span the line, end to end.
        if (teeth.first().cx - seg.x0 > mean * 0.6f || seg.x1 - teeth.last().cx > mean * 0.6f) return null
        return FloatArray(teeth.size) { teeth[it].cx } to heights.min()
    }

    /** A line with no walls: blank, labelled, or with text on it. */
    private fun plain(s: Sheared, seg: Segment, kind: LineKind, x0: Float, x1: Float, exclude: List<Attachment>,
        forceUnsure: Boolean): FoundLine? {
        val thick = seg.thickness
        val a = x0.toInt(); val b = x1.toInt()
        // Printed marks resting on the line: a band just above it.
        val band = max(2, px(1.2f))
        val occupied = BooleanArray(b - a + 1) { i ->
            val x = a + i
            if (exclude.any { x >= it.x0 - 1 && x <= it.x1 + 1 }) false
            else (1..band).any { k -> s[x, seg.top - k] }
        }
        val total = occupied.count { it }.toFloat() / occupied.size
        // A label printed at the start of the line: occupied from the start, then clear.
        var start = x0
        var label: FloatArray? = null
        var labelled = false
        val headStart = (0 until min(occupied.size, px(3f))).firstOrNull { occupied[it] }
        if (headStart != null) {
            var end: Int = headStart; var miss = 0
            val bridge = px(2.5f)
            for (i in headStart until occupied.size) {
                if (occupied[i]) { end = i; miss = 0 } else if (++miss > bridge) break
            }
            val rest = occupied.size - end - 1
            val restOccupied = (end + 1 until occupied.size).count { occupied[it] }
            if (end - headStart >= px(2f) && rest >= mm(15f) && restOccupied <= rest * 0.1f) {
                start = a + end + mm(1.5f)
                labelled = true
                label = textBox(s, a + headStart, a + end, seg.top)
            }
        }
        val written = if (labelled) {
            val from = (start - a).toInt().coerceIn(0, occupied.size)
            (from until occupied.size).count { occupied[it] }.toFloat() / max(1, occupied.size - from)
        } else total
        val sure = !forceUnsure && written < 0.2f && (x1 - start) >= mm(6f)
        val room = clearRoom(s, start.roundToInt(), b, seg.top, exclude, limit = px(30f))
        if (label == null) label = labelBefore(s, a, seg.top)
        return line(s, seg, x0, x1, 0f, thick, room, start, kind, sure, label = label)
    }

    /** How far up the page is clear above the line: the 25th percentile over its columns. */
    private fun clearRoom(s: Sheared, a: Int, b: Int, top: Int, exclude: List<Attachment>, limit: Int): Float {
        val dists = ArrayList<Int>()
        for (x in a..b) {
            if (exclude.any { x >= it.x0 - 1 && x <= it.x1 + 1 }) continue
            var k = 1
            while (k <= limit && !s[x, top - k]) k++
            dists += k - 1
        }
        if (dists.isEmpty()) return limit.toFloat()
        dists.sort()
        return dists[dists.size / 4].toFloat()
    }

    /** The ink box of printed text in columns [a]..[b] just above row [top]. */
    private fun textBox(s: Sheared, a: Int, b: Int, top: Int): FloatArray? {
        val look = px(6f)
        var t = Int.MAX_VALUE; var bottom = -1; var l = Int.MAX_VALUE; var r = -1
        for (x in a..b) for (k in 1..look) if (s[x, top - k]) {
            val y = top - k
            t = min(t, y); bottom = max(bottom, y); l = min(l, x); r = max(r, x)
        }
        if (r < 0) return null
        return floatArrayOf(l.toFloat(), s.sourceY(l.toFloat(), t.toFloat()), r + 1f, s.sourceY(r.toFloat(), bottom + 1f))
    }

    /** The printed text just left of [x0] resting at the line's height (`Name: ____`), or null. */
    private fun labelBefore(s: Sheared, x0: Int, top: Int): FloatArray? {
        val up = px(6f); val down = px(1f)
        fun columnInk(x: Int) = (top - up..top + down).any { s[x, it] }
        var x = x0 - 1
        val reach = x0 - px(5f)
        while (x >= max(0, reach) && !columnInk(x)) x--
        if (x < max(0, reach)) return null
        val right = x
        var left = x; var miss = 0
        val bridge = px(2.5f)
        val far = max(0, x0 - px(60f))
        while (x >= far) {
            if (columnInk(x)) { left = x; miss = 0 } else if (++miss > bridge) break
            x--
        }
        val box = textBoxRange(s, left, right, top - up, top + down) ?: return null
        // Text resting on the line's height, not a paragraph somewhere above it.
        if (box[3] < s.sourceY(box[2], top.toFloat()) - mm(2.5f)) return null
        return box
    }

    private fun textBoxRange(s: Sheared, a: Int, b: Int, y0: Int, y1: Int): FloatArray? {
        var t = Int.MAX_VALUE; var bottom = -1
        for (x in a..b) for (y in y0..y1) if (s[x, y]) { t = min(t, y); bottom = max(bottom, y) }
        if (bottom < 0) return null
        return floatArrayOf(a.toFloat(), s.sourceY(a.toFloat(), t.toFloat()), b + 1f, s.sourceY(b.toFloat(), bottom + 1f))
    }

    /** A found line along [seg]'s fitted top edge, [dy] rows below it (negative: a line laid above it, in a box). */
    private fun line(s: Sheared, seg: Segment, x0: Float, x1: Float, dy: Float, thick: Float, room: Float, start: Float, kind: LineKind,
        sure: Boolean, cells: FloatArray? = null, label: FloatArray? = null) =
        FoundLine(x0, x1 + 1f, s.sourceY(x0, seg.topAt(x0) + dy), s.tilt + seg.slope, thick, room, start, kind, sure, cells, label)

    /** Two finds of one line (a double rule, dots over a solid line): keep the top one. */
    private fun dedupe(lines: List<FoundLine>): List<FoundLine> {
        val sorted = lines.sortedBy { it.y }
        val out = ArrayList<FoundLine>()
        for (l in sorted) {
            val twin = out.firstOrNull { o ->
                o.kind != LineKind.Box && l.kind != LineKind.Box &&
                    abs(o.yAt(l.x0) - l.y) <= mm(1.2f) && min(o.x1, l.x1) - max(o.x0, l.x0) >= 0.5f * min(o.length, l.length)
            }
            if (twin == null) out += l
        }
        return out
    }

    companion object {
        /** Detection resolution: about 6 px per mm of paper (a 0.1 mm hairline is still a pixel). */
        const val PX_PER_MM = 6f
        /** The tilt of a line in degrees, for messages and tests. */
        fun degrees(slope: Float) = Math.toDegrees(atan(slope.toDouble())).toFloat()
    }
}
