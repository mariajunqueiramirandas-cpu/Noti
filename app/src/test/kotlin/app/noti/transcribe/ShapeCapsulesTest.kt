package app.noti.transcribe

import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ShapeCapsulesTest {
    private fun mask(width: Int, height: Int, inside: (Float, Float) -> Boolean) =
        BooleanArray(width * height) { inside(it % width + 0.5f, it / width + 0.5f) }

    @Test fun distanceTransformMatchesBruteForce() {
        val random = java.util.Random(3)
        val w = 23; val h = 17
        val m = BooleanArray(w * h) { val x = it % w; val y = it / w; x in 1 until w - 1 && y in 1 until h - 1 && random.nextFloat() < 0.8f }
        val fast = ShapeCapsules.distanceToOutside(m, w, h)
        for (i in m.indices) {
            var best = 0f
            if (m[i]) {
                best = Float.MAX_VALUE
                for (j in m.indices) if (!m[j]) best = minOf(best, hypot((i % w - j % w).toFloat(), (i / w - j / w).toFloat()))
            }
            assertEquals("pixel $i", best, fast[i], 1e-3f)
        }
    }

    /** Rendered like the engine, the capsules fill the shape solidly and stop at its edge. */
    private fun assertDrawsAs(name: String, w: Int, h: Int, inside: (Float, Float) -> Boolean, maxCapsules: Int) {
        val m = mask(w, h, inside)
        val capsules = ShapeCapsules.of(m, w, h)
        assertTrue("$name: ${capsules.size / 6} capsules", capsules.size / 6 in 1..maxCapsules)
        val cov = engineCoverage(capsules, 1f, w, h)
        var wrong = 0; var area = 0
        for (i in m.indices) {
            if (m[i]) area++
            // Pixels well inside must be solid; well outside, empty. The edge is anti-aliased.
            val x = i % w + 0.5f; val y = i / w + 0.5f
            val deepInside = listOf(-1f, 0f, 1f).all { dx -> listOf(-1f, 0f, 1f).all { dy -> inside(x + dx, y + dy) } }
            val farOutside = listOf(-1f, 0f, 1f).none { dx -> listOf(-1f, 0f, 1f).any { dy -> inside(x + dx, y + dy) } }
            if (deepInside && cov[i] < 0.99f) wrong++
            if (farOutside && cov[i] > 0.01f) wrong++
        }
        assertTrue("$name: $wrong of $area pixels wrong", wrong <= maxOf(2, area / 100))
    }

    @Test fun evenAndOddBarsFillToTheirEdges() {
        assertDrawsAs("odd bar", 60, 20, { x, y -> x in 5f..55f && y in 5f..12f }, 4)
        assertDrawsAs("even bar", 60, 20, { x, y -> x in 5f..55f && y in 5f..13f }, 4)
        assertDrawsAs("tall bar", 20, 60, { x, y -> x in 6f..12f && y in 4f..56f }, 4)
    }

    @Test fun ringIsOneLoopWithoutHoles() {
        assertDrawsAs("ring", 64, 64, { x, y -> hypot(x - 32f, y - 32f) in 18f..26f }, 80)
    }

    @Test fun diagonalAndDotStaySolid() {
        assertDrawsAs("diagonal", 60, 60, { x, y -> kotlin.math.abs((x - y) / sqrt(2f)) < 4f && x in 6f..54f && y in 6f..54f }, 40)
        assertDrawsAs("dot", 20, 20, { x, y -> hypot(x - 10f, y - 10f) < 5f }, 12)
    }
}
