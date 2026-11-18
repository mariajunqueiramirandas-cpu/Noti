package app.noti.lines

import kotlin.math.max
import kotlin.math.min

/**
 * A stroke on a page as the engine lists it (InkEngine::page_ink), in page units: its id,
 * page, premultiplied color, and centreline (the first capsule's start, then each capsule's
 * end), with its bounds. Typed words are found among these again ([BlockMatcher]).
 */
class PageStroke(val id: Int, val page: Int, val color: FloatArray, val points: FloatArray) {
    val x0: Float; val y0: Float; val x1: Float; val y1: Float
    init {
        var ax = Float.MAX_VALUE; var ay = Float.MAX_VALUE; var bx = -Float.MAX_VALUE; var by = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            ax = min(ax, points[i]); bx = max(bx, points[i]); ay = min(ay, points[i + 1]); by = max(by, points[i + 1])
        }
        x0 = ax; y0 = ay; x1 = bx; y1 = by
    }

    companion object {
        /** Reads [count, then per stroke: id, page, r, g, b, a, n, then n capsules of x0 y0 r0 x1 y1 r1]. */
        fun parse(data: FloatArray): List<PageStroke> {
            if (data.isEmpty()) return emptyList()
            val count = data[0].toInt()
            val out = ArrayList<PageStroke>(count)
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
                out += PageStroke(id, page, color, points)
                at += n * 6
            }
            return out
        }
    }
}
