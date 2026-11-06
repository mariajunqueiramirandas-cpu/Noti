package app.noti.transcribe

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A filled shape as the engine's capsules. Renderers merge a stroke's capsules by
 * coverage (GL_MAX of each capsule's anti-aliased edge), so a shape filled with thin
 * strips renders grey and striped. Instead the shape is drawn from its middle: every
 * point on its medial axis (the ridge of its distance to the edge) carries a disc that
 * reaches the edge, and capsules join neighbouring discs. Their union has the shape's own
 * signed distance: solid inside, anti-aliased at the edge, at any zoom.
 */
internal object ShapeCapsules {
    /**
     * [inside] is a [width] × [height] mask (row-major) with outside pixels around it.
     * Returns capsules (x0 y0 r0 x1 y1 r1) in pixels, pixel (i, j) centred at (i + 0.5, j + 0.5).
     * [tolerance] (pixels) is how far simplified chains may stray from the ridge.
     */
    fun of(inside: BooleanArray, width: Int, height: Int, tolerance: Float = 0.5f): FloatArray {
        val dist = distanceToOutside(inside, width, height)
        // Ridge pixels: a strict maximum of the distance across some direction. A ridge two
        // pixels wide (an even stroke) keeps the second of the pair, moved half a step back
        // to the true middle.
        val ridge = BooleanArray(width * height)
        // (A pixel centre is about half a pixel inside the true edge: discs reach dist - 0.5.)
        val shiftX = FloatArray(width * height); val shiftY = FloatArray(width * height)
        val directions = intArrayOf(1, 0, 0, 1, 1, 1, 1, -1)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if (!inside[i]) continue
            val d = dist[i]
            for (k in 0 until 4) {
                val dx = directions[k * 2]; val dy = directions[k * 2 + 1]
                val o = dy * width + dx
                if (d >= dist[i - o] && d > dist[i + o]) {
                    ridge[i] = true
                    if (d == dist[i - o]) { shiftX[i] = -dx / 2f; shiftY[i] = -dy / 2f }
                    break
                }
            }
        }
        // A disc that a neighbouring ridge disc (nearly) holds adds nothing: drop it. This
        // trims the medial axis' branches into corners, and the pixel ridge's spurs, which
        // would cost many capsules for a sliver of edge.
        val cx = FloatArray(width * height) { it % width + 0.5f + shiftX[it] }
        val cy = FloatArray(width * height) { it / width + 0.5f + shiftY[it] }
        fun radius(i: Int) = max(dist[i] - 0.5f + hypot(shiftX[i], shiftY[i]), 0.5f)
        val around = intArrayOf(-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1)
        for (i in ridge.indices.sortedBy { dist[it] }) if (ridge[i]) {
            val x = i % width
            for (o in around) {
                val j = i + o
                if (j < 0 || j >= ridge.size || !ridge[j] || abs(j % width - x) > 1) continue
                if (hypot(cx[i] - cx[j], cy[i] - cy[j]) + radius(i) <= radius(j) + Slack) { ridge[i] = false; break }
            }
        }
        thin(ridge, width, height)
        // Each ridge pixel links to its ridge neighbours, each link once; a diagonal only
        // where no straight step already joins the two.
        val links = HashMap<Int, MutableList<Int>>()
        fun link(i: Int, j: Int) { links.getValue(i) += j; links.getOrPut(j) { ArrayList(2) } += i }
        for (i in ridge.indices) if (ridge[i]) links.getOrPut(i) { ArrayList(2) }
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if (!ridge[i]) continue
            val east = ridge[i + 1]; val south = ridge[i + width]
            if (east) link(i, i + 1)
            if (south) link(i, i + width)
            if (ridge[i + width + 1] && !east && !south) link(i, i + width + 1)
            if (ridge[i + width - 1] && !ridge[i - 1] && !south) link(i, i + width - 1)
        }
        val out = FloatList()
        fun emit(chain: List<Int>) {
            val points = FloatArray(chain.size * 3)
            chain.forEachIndexed { n, i ->
                points[n * 3] = cx[i]; points[n * 3 + 1] = cy[i]; points[n * 3 + 2] = radius(i)
            }
            val kept = simplify(points, tolerance)
            if (kept.size == 1) out.capsule(points, kept[0], kept[0])
            for (n in 1 until kept.size) out.capsule(points, kept[n - 1], kept[n])
        }
        // Walk chains between ends and junctions, then what's left: closed loops (an "o").
        val used = HashSet<Long>()
        fun key(a: Int, b: Int) = if (a < b) a.toLong() * ridge.size + b else b.toLong() * ridge.size + a
        fun walk(start: Int, next: Int) {
            val chain = arrayListOf(start)
            var prev = start; var at = next
            used += key(prev, at)
            while (true) {
                chain += at
                val onward = links.getValue(at)
                if (onward.size != 2) break
                val step = if (onward[0] == prev) onward[1] else onward[0]
                if (!used.add(key(at, step))) break
                prev = at; at = step
            }
            emit(chain)
        }
        for ((i, n) in links) {
            if (n.isEmpty()) emit(listOf(i))
            else if (n.size != 2) for (j in n) if (key(i, j) !in used) walk(i, j)
        }
        for ((i, n) in links) if (n.size == 2 && key(i, n[0]) !in used) walk(i, n[0])
        return out.toArray()
    }

    /**
     * Zhang–Suen thinning: peels [mask] to lines one pixel wide without breaking or
     * shortening them, so the ridge links into long chains that simplify well.
     */
    private fun thin(mask: BooleanArray, width: Int, height: Int) {
        val remove = ArrayList<Int>()
        var changed = true
        while (changed) {
            changed = false
            for (pass in 0..1) {
                remove.clear()
                for (y in 1 until height - 1) for (x in 1 until width - 1) {
                    val i = y * width + x
                    if (!mask[i]) continue
                    // Neighbours clockwise from north: P2 … P9.
                    val p = booleanArrayOf(mask[i - width], mask[i - width + 1], mask[i + 1], mask[i + width + 1],
                        mask[i + width], mask[i + width - 1], mask[i - 1], mask[i - width - 1])
                    val count = p.count { it }
                    if (count < 2 || count > 6) continue
                    var transitions = 0
                    for (k in 0 until 8) if (!p[k] && p[(k + 1) % 8]) transitions++
                    if (transitions != 1) continue
                    if (pass == 0 && ((p[0] && p[2] && p[4]) || (p[2] && p[4] && p[6]))) continue
                    if (pass == 1 && ((p[0] && p[2] && p[6]) || (p[0] && p[4] && p[6]))) continue
                    remove += i
                }
                for (i in remove) mask[i] = false
                if (remove.isNotEmpty()) changed = true
            }
        }
    }

    /** How much of a disc (pixels) may poke out of the neighbour that holds it. */
    private const val Slack = 0.5f

    /** Euclidean distance from each pixel to the nearest outside pixel (0 outside). */
    internal fun distanceToOutside(inside: BooleanArray, width: Int, height: Int): FloatArray {
        // Farther than any real distance, yet small enough to keep the sums exact.
        val far = 2f * (width.toFloat() * width + height.toFloat() * height) + 1f
        val g = FloatArray(width * height) { if (inside[it]) far else 0f }
        val n = max(width, height)
        val f = FloatArray(n); val d = FloatArray(n); val v = IntArray(n); val z = DoubleArray(n + 1)
        for (x in 0 until width) {
            for (y in 0 until height) f[y] = g[y * width + x]
            squared1d(f, height, d, v, z)
            for (y in 0 until height) g[y * width + x] = d[y]
        }
        for (y in 0 until height) {
            for (x in 0 until width) f[x] = g[y * width + x]
            squared1d(f, width, d, v, z)
            for (x in 0 until width) g[y * width + x] = sqrt(d[x])
        }
        return g
    }

    /** Felzenszwalb–Huttenlocher squared distance transform of [f] in one dimension. */
    private fun squared1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: DoubleArray) {
        fun meet(q: Int, p: Int) = ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * (q - p))
        var k = 0
        v[0] = 0; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            var s = meet(q, v[k])
            while (s <= z[k]) { k--; s = meet(q, v[k]) }
            k++
            v[k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toFloat() * (q - p) + f[p]
        }
    }

    /** Douglas–Peucker over (x, y, r): indices of the points kept, first and last always. */
    private fun simplify(points: FloatArray, tolerance: Float): List<Int> {
        val count = points.size / 3
        if (count <= 2) return (0 until count).toList()
        val keep = BooleanArray(count).also { it[0] = true; it[count - 1] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to count - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1; var worstError = tolerance
            val ax = points[a * 3]; val ay = points[a * 3 + 1]; val ar = points[a * 3 + 2]
            val bx = points[b * 3]; val by = points[b * 3 + 1]; val br = points[b * 3 + 2]
            val len = hypot(bx - ax, by - ay)
            for (i in a + 1 until b) {
                val px = points[i * 3]; val py = points[i * 3 + 1]
                val t = if (len > 0f) (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / (len * len)).coerceIn(0f, 1f) else 0f
                val off = hypot(ax + t * (bx - ax) - px, ay + t * (by - ay) - py)
                val error = max(off, abs(ar + t * (br - ar) - points[i * 3 + 2]))
                if (error > worstError) { worst = i; worstError = error }
            }
            if (worst >= 0) { keep[worst] = true; stack.add(a to worst); stack.add(worst to b) }
        }
        return (0 until count).filter { keep[it] }
    }

    private class FloatList {
        private var data = FloatArray(256)
        private var size = 0
        fun capsule(points: FloatArray, a: Int, b: Int) {
            if (size + 6 > data.size) data = data.copyOf(data.size * 2)
            for (i in 0 until 3) data[size + i] = points[a * 3 + i]
            for (i in 0 until 3) data[size + 3 + i] = points[b * 3 + i]
            size += 6
        }
        fun toArray() = data.copyOf(size)
    }
}
