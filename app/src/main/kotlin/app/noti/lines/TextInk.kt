package app.noti.lines

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Typeface
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Typed text as Noti ink: the engine draws only capsules, so each word's shape from the
 * font becomes capsules along the middle of its letters, as wide as the letters
 * ([ShapeCapsules]). One stroke per word, so the eraser and lasso take words like they
 * take handwriting.
 *
 * All sizes are page units. One instance per thread (it keeps a paint).
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
     * Strokes for [words] already placed (Type on lines, [app.noti.lines.LineLayout]): each
     * word at [em], its baseline starting at its point, turned to its line's slope. One
     * stroke per word, in order; blank words make none.
     */
    fun place(words: List<PlacedWord>, em: Float): Strokes {
        val out = Builder()
        paint.textSize = em * Scale
        val turn = Matrix()
        for (w in words) {
            if (w.text.isBlank()) continue
            val path = Path()
            paint.getTextPath(w.text, 0, w.text.length, 0f, 0f, path)
            if (w.slope != 0f) {
                turn.setRotate(Math.toDegrees(atan(w.slope.toDouble())).toFloat())
                path.transform(turn)
            }
            out.stroke { addPath(path, w.x, w.y) }
        }
        return out.build()
    }

    /** Text widths in this font at an em of 1, for laying out before placing. Its own paint: any thread. */
    fun measure(): Measure {
        val m = Paint(paint).apply { textSize = 100f }
        return Measure { text -> synchronized(m) { m.measureText(text) } / 100f }
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
        /** Font pixels per page unit: shapes are traced to a quarter of a unit. */
        private const val Scale = 4f
    }
}
