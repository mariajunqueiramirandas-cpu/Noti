package app.noti.transcribe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.noti.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextInkTest {
    private val typeface = RuntimeEnvironment.getApplication().resources.getFont(R.font.nunito_regular)
    private val ink = TextInk(typeface)
    private val pen = floatArrayOf(0f, 0f, 0f, 1f)

    /** A handwritten line [height] tall, centred on [centerY], from [x0] to [x1]. */
    private fun inkLine(x0: Float, x1: Float, centerY: Float, height: Float = 20f, id: Int = 0) = InkLine(listOf(
        SelectedStroke(id, 0, pen, floatArrayOf(x0, centerY - height / 2, x1, centerY + height / 2))))

    @Test fun oneStrokePerWordPlacedOnEachHandwrittenLine() {
        val lines = listOf(inkLine(100f, 400f, 200f), inkLine(120f, 300f, 240f, id = 1))
        val strokes = ink.layout("Hello there\nsecond", lines)
        assertEquals(3, strokes.sizes.size)
        assertEquals(strokes.capsules.size, strokes.sizes.sum() * 6)
        val words = boxes(strokes)
        // Each line starts where its handwriting started; its lowercase letters sit on the ink line.
        assertEquals(100f, words[0][0], 2f)
        assertEquals(120f, words[2][0], 2f)
        assertTrue(words[1][0] > words[0][2])                // "there" follows "Hello"
        assertEquals(200f, (words[1][1] + words[1][3]) / 2f, 8f)
        assertEquals(240f, (words[2][1] + words[2][3]) / 2f, 8f)
        // Sized to the handwriting: 20 tall ink gives text of a similar height.
        assertEquals(16f * 0.7f, words[0][3] - words[0][1], 5f)
    }

    @Test fun longLinesWrapInsideThePageAndPushLaterLinesDown() {
        val lines = listOf(inkLine(600f, 900f, 100f), inkLine(600f, 900f, 130f, id = 1))
        val long = List(12) { "word" }.joinToString(" ")
        val strokes = ink.layout("$long\nlast", lines)
        val words = boxes(strokes)
        assertEquals(13, words.size)
        words.forEach { assertTrue("inside the page: ${it.toList()}", it[2] <= TextInk.PageWidth && it[0] >= 0f) }
        val rows = words.dropLast(1).map { it[3] }.distinct().size
        assertTrue("wrapped into several rows", rows > 1)
        // The next paragraph starts below the wrapped rows, not over them.
        assertTrue(words.last()[1] > words[11][3])
    }

    @Test fun extraLinesContinueAtTheLineSpacingAndBlankTextIsNothing() {
        val lines = listOf(inkLine(100f, 300f, 100f), inkLine(100f, 300f, 140f, id = 1))
        val words = boxes(ink.layout("a\nb\nc", lines))
        assertEquals(3, words.size)
        val centers = words.map { (it[1] + it[3]) / 2f }
        assertEquals(40f, centers[1] - centers[0], 4f)
        assertEquals(40f, centers[2] - centers[1], 4f)
        assertTrue(ink.layout("   \n ", lines).isEmpty)
        assertTrue(ink.layout("text", emptyList()).isEmpty)
    }

    /** Drawn by the engine's rules (each capsule a stadium), the ink matches the font itself. */
    @Test fun inkLooksLikeTheTypedText() {
        val line = inkLine(20f, 600f, 60f, height = 50f)
        val text = "Quick notes, gentle ink & 2026!"
        val strokes = ink.layout(text, listOf(line))
        val scale = 2f
        val w = 1300; val h = 200
        // Rendered by the engine's rules: each capsule's anti-aliased SDF, merged by maximum.
        val coverage = engineCoverage(strokes.capsules, scale, w, h)
        val fromInk = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            for (i in coverage.indices) bmp.setPixel(i % w, i / w, Color.argb((coverage[i] * 255).toInt(), 0, 0, 0))
        }
        val fromFont = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = this@TextInkTest.typeface; color = Color.BLACK; textSize = 50f * 0.8f * scale }
            // Word by word, advancing like TextInk does, so this compares letters, not layout.
            var x = 20f * scale
            for (word in text.split(' ')) {
                Canvas(bmp).drawText(word, x, (60f + 50f * 0.8f * 0.35f) * scale, paint)
                x += paint.measureText(word) + paint.measureText(" ")
            }
        }
        File("build/reports/transcribe").mkdirs()
        save(fromInk, "build/reports/transcribe/text-ink.png")
        save(fromFont, "build/reports/transcribe/text-font.png")
        val ink = BooleanArray(w * h) { coverage[it] > 0.5f }
        val font = BooleanArray(w * h) { Color.alpha(fromFont.getPixel(it % w, it / w)) > 128 }
        // The same shape, give or take a pixel of edge: anything farther off is wrong.
        fun near(mask: BooleanArray, x: Int, y: Int) =
            (-1..1).any { dy -> (-1..1).any { dx -> mask.getOrElse((y + dy) * w + x + dx) { false } } }
        var wrong = 0; var area = 0; var grey = 0; var solid = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (font[i]) area++
            if (ink[i] != font[i] && !(if (ink[i]) near(font, x, y) else near(ink, x, y))) wrong++
            // Inside the letters (away from their anti-aliased edges) the ink is solid: no
            // grey or striped letters.
            val deep = (-1..1).all { dy -> (-1..1).all { dx -> Color.alpha(fromFont.getPixel(x + dx, y + dy)) == 255 } }
            if (deep) { solid++; if (coverage[i] < 0.99f) grey++ }
        }
        assertTrue("$wrong of $area pixels off by more than a pixel", wrong <= area / 100)
        assertTrue("$grey of $solid pixels inside letters are faint in ink", grey <= solid / 200)
        // Stays light: about 76 capsules a letter at this (large) size; handwriting is 20-40.
        assertTrue("capsules per letter ${strokes.sizes.sum() / text.length}", strokes.sizes.sum() / text.length < 120)
    }

    /** [x0, y0, x1, y1] of each stroke's capsule centres. */
    @Test fun placedWordsSitOnTheirBaselinesAndFollowATiltedLine() {
        val em = 20f
        val words = listOf(app.noti.lines.PlacedWord("xxx", 100f, 300f, 0f, 0), app.noti.lines.PlacedWord(" ", 200f, 300f, 0f, 0),
            app.noti.lines.PlacedWord("mmm", 100f, 400f, 0.05f, 1))
        val strokes = ink.place(words, em)
        assertEquals("one stroke per non-blank word", 2, strokes.sizes.size)
        val (flat, tilted) = boxes(strokes)
        // x-height letters: bottom on the baseline, left at the word's point, width as measured.
        assertEquals(300f, flat[3], 1f)
        assertEquals(100f, flat[0], 2f)
        val width = ink.measure().width("xxx") * em
        assertTrue("ends within its measured width", flat[2] <= 100f + width + 0.5f)
        // Turned with the line: the right end sits lower (y grows down) by about slope · width.
        assertTrue("tilted word's bottom-right is lower than a flat one would be", tilted[3] > 400f + 0.03f * (tilted[2] - tilted[0]))
    }

    private fun boxes(strokes: TextInk.Strokes): List<FloatArray> {
        var at = 0
        return strokes.sizes.map { n ->
            val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            repeat(n) {
                val c = at * 6
                for ((x, y) in listOf(strokes.capsules[c] to strokes.capsules[c + 1], strokes.capsules[c + 3] to strokes.capsules[c + 4])) {
                    box[0] = minOf(box[0], x); box[1] = minOf(box[1], y); box[2] = maxOf(box[2], x); box[3] = maxOf(box[3], y)
                }
                at++
            }
            box
        }
    }

    private fun save(bitmap: Bitmap, path: String) {
        val white = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(white).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        File(path).outputStream().use { white.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
