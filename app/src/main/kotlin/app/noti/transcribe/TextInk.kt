package app.noti.transcribe

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Typed text as Noti ink: the engine draws only capsules, so each word's shape from the
 * font becomes capsules along the middle of its letters, as wide as the letters
 * ([ShapeCapsules]). One stroke per word, so the eraser and lasso take words like they
 * take handwriting.
 *
 * All sizes are page units (a page is [PageWidth] wide). Any thread.
 */
class TextInk(typeface: Typeface) {
    /** Strokes for InkLayer.replaceStrokes: capsules (x0 y0 r0 x1 y1 r1) cut by [sizes]. */
    class Strokes(val capsules: FloatArray, val sizes: IntArray) {
        val isEmpty get() = sizes.isEmpty()
    }

    // Linear metrics: advances scale exactly with the size, not rounded to font pixels.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
    }

    /**
     * Lays [text] out where the handwriting [lines] were: line i starts where ink line i
     * started, at a size matched to the handwriting. Lines too long for the page wrap, and
     * text that runs past the handwriting keeps the same line spacing.
     */
    fun layout(text: String, lines: List<InkLine>): Strokes {
        val out = Builder()
        if (lines.isEmpty() || text.isBlank()) return out.build()
        val size = (InkLines.median(lines.map { it.height }) * 0.8f).coerceIn(6f, 120f)
        val pitch = max(InkLines.pitch(lines), size * 1.3f)
        paint.textSize = size * Scale
        var baseline = Float.NaN
        for ((i, paragraph) in text.trimEnd().split('\n').withIndex()) {
            val anchor = lines.getOrNull(i)
            val left = (anchor ?: lines.first()).x0.coerceIn(0f, PageWidth - Margin - size)
            // Centre the lowercase letters (about 0.35 em above the baseline) on the ink line.
            val wanted = anchor?.let { it.centerY + size * 0.35f }
            baseline = when {
                baseline.isNaN() -> wanted ?: (lines.first().centerY + size * 0.35f)
                wanted == null -> baseline + pitch
                else -> max(wanted, baseline + size * 1.2f)
            }
            for ((r, row) in wrap(paragraph, PageWidth - Margin - left).withIndex()) {
                if (r > 0) baseline += pitch
                var x = left
                for (word in row) {
                    if (word.isNotBlank()) {
                        val path = Path()
                        paint.getTextPath(word, 0, word.length, 0f, 0f, path)
                        out.stroke { addPath(path, x, baseline) }
                    }
                    x += (paint.measureText(word) + paint.measureText(" ")) / Scale
                }
            }
        }
        return out.build()
    }

    /** Words of [paragraph] in rows no wider than [width] (a longer word gets a row to itself). */
    private fun wrap(paragraph: String, width: Float): List<List<String>> {
        val rows = ArrayList<MutableList<String>>()
        var used = 0f
        val space = paint.measureText(" ") / Scale
        for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
            val w = paint.measureText(word) / Scale
            if (rows.isEmpty() || (used + space + w > width && rows.last().isNotEmpty())) {
                rows += mutableListOf(word); used = w
            } else { rows.last() += word; used += space + w }
        }
        return rows.ifEmpty { listOf(emptyList()) }
    }

    /** [path] is in font pixels at [Scale] with its baseline at y = 0; place it at ([x], [y]). */
    private fun Builder.addPath(path: Path, x: Float, y: Float) {
        val k = 1f / Scale
        fun add(x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float) =
            capsule(x + x0 * k, y + y0 * k, r0 * k, x + x1 * k, y + y1 * k, r1 * k)
        // The word as a mask of font pixels, with a border of outside around it.
        val bounds = RectF().also { path.computeBounds(it, true) }
        if (bounds.isEmpty) return
        val left = floor(bounds.left).toInt() - 2; val top = floor(bounds.top).toInt() - 2
        val width = ceil(bounds.right).toInt() + 2 - left; val height = ceil(bounds.bottom).toInt() + 2 - top
        val region = Region().apply { setPath(path, Region(left + 1, top + 1, left + width - 1, top + height - 1)) }
        val inside = BooleanArray(width * height)
        val rect = Rect()
        val spans = RegionIterator(region)
        while (spans.next(rect)) for (py in rect.top until rect.bottom) for (px in rect.left until rect.right) {
            inside[(py - top) * width + (px - left)] = true
        }
        val c = ShapeCapsules.of(inside, width, height)
        for (i in c.indices step 6) add(c[i] + left, c[i + 1] + top, c[i + 2], c[i + 3] + left, c[i + 4] + top, c[i + 5])
    }

    private class Builder {
        private var capsules = FloatArray(4096)
        private var count = 0
        private val sizes = ArrayList<Int>()
        private var strokeStart = 0

        fun capsule(x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float) {
            if (count + 6 > capsules.size) capsules = capsules.copyOf(capsules.size * 2)
            capsules[count++] = x0; capsules[count++] = y0; capsules[count++] = r0
            capsules[count++] = x1; capsules[count++] = y1; capsules[count++] = r1
        }

        fun stroke(block: Builder.() -> Unit) {
            strokeStart = count
            block()
            if (count > strokeStart) sizes += (count - strokeStart) / 6
        }

        fun build() = Strokes(capsules.copyOf(count), sizes.toIntArray())
    }

    companion object {
        /** Page width in page units (PageMap::kPageUnits). */
        const val PageWidth = 1000f
        private const val Margin = 24f
        /** Font pixels per page unit: shapes are traced to a quarter of a unit. */
        private const val Scale = 4f
    }
}
