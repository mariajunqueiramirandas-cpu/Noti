package app.noti.lines

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.noti.R
import app.noti.transcribe.TextInk
import app.noti.transcribe.engineCoverage
import java.io.File
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End to end, without the UI: a printed worksheet's pixels → lines found → a loop picks
 * them → text laid out → ink placed. Then the ink is drawn the way the engine draws it and
 * checked against "perfectly" (docs/TYPE_ON_LINES.md §1): letters sit on each line, the same
 * size on every line, never past a line's end, nothing skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeOnLinesTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val ink = TextInk(res.getFont(R.font.nunito_regular))
    /** An A4 page: 1000 units across 210 mm. */
    private val mmPerUnit = 210f / 1000f
    private val k = LineFinder.PX_PER_MM

    /** A worksheet region 150 × 70 mm at the page's top-left, three answer lines and a name blank. */
    private fun worksheet(tiltDegrees: Float = 0f): Bitmap {
        val bmp = Bitmap.createBitmap((150 * k).toInt(), (70 * k).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = res.getFont(R.font.nunito_regular); textSize = 11 * 0.3528f * k }
        c.rotate(tiltDegrees)
        c.drawText("Name:", 10 * k, 12 * k, p)
        val nameStart = 10 + p.measureText("Name:") / k + 1.5f
        c.drawRect(nameStart * k, 12.4f * k, 90 * k, 12.65f * k, p)
        c.drawText("Why does a hot cup of tea cool down?", 10 * k, 25 * k, p)
        p.color = Color.rgb(0x8F, 0xB3, 0xD9)
        for (i in 0 until 3) c.drawRect(10 * k, (36 + i * 10) * k, 140 * k, (36.25f + i * 10) * k, p)
        return bmp
    }

    private fun lines(bmp: Bitmap): List<WriteLine> {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val u = 1f / (k * mmPerUnit)   // page units per pixel
        return LineFinder(k).find(Raster.fromArgb(bmp.width, bmp.height, px)).map { f ->
            WriteLine(f.x0 * u, f.x1 * u, f.y * u, f.slope, f.room * u, f.start * u, f.kind, f.sure, label = f.label)
        }
    }

    private fun units(mm: Float) = mm / mmPerUnit
    private fun loop(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(units(l), units(t), units(r), units(t), units(r), units(b), units(l), units(b))

    @Test fun typedTextSitsOnEveryLoopedLine() = check(tilt = 0f, name = "worksheet-flat")

    @Test fun andFollowsATiltedScan() = check(tilt = 1.5f, name = "worksheet-tilted")

    private fun check(tilt: Float, name: String) {
        val page = worksheet(tilt)
        val found = lines(page)
        val picked = LinePicker.inLoop(found, loop(6f, 29f, 146f, 70f), units(12f))
        assertEquals("the three answer lines: $found", 3, picked.size)

        val text = "It loses heat to the colder air around it until both are at the same temperature, which takes a while."
        val laid = LineLayout(ink.measure(), mmPerUnit).layout(picked, text)
        assertTrue("fits", laid.fits)
        val strokes = ink.place(laid.words, laid.em)
        assertEquals("one stroke per word", laid.words.size, strokes.sizes.size)
        // Lines used in order, none skipped.
        val used = laid.words.map { it.line }.distinct()
        assertEquals((0 until used.size).toList(), used)

        // The ink as the engine draws it, on the page at the detector's resolution.
        val scale = k * mmPerUnit
        val cov = engineCoverage(strokes.capsules, scale, page.width, page.height)
        var at = 0
        for ((i, w) in laid.words.withIndex()) {
            val n = strokes.sizes[i]
            val line = picked[w.line]
            var right = -Float.MAX_VALUE
            for (c in at until at + n) {
                val o = c * 6
                right = max(right, max(strokes.capsules[o] + strokes.capsules[o + 2], strokes.capsules[o + 3] + strokes.capsules[o + 5]))
            }
            assertTrue("'${w.text}' stays before the line's end", right <= line.x1)
            // Words without descenders: their ink comes down to the line along the whole word, and never into it.
            if (w.text.none { it in "gjpqy,;" }) {
                var lowest = -Float.MAX_VALUE   // how far below the line any ink reaches (negative: above it)
                for (c in at until at + n) {
                    val o = c * 6
                    for (e in 0..1) {
                        val x = strokes.capsules[o + e * 3]; val y = strokes.capsules[o + e * 3 + 1]; val r = strokes.capsules[o + e * 3 + 2]
                        lowest = max(lowest, y + r - line.yAt(x))
                    }
                }
                assertTrue("'${w.text}' doesn't sink into the line ($lowest)", lowest <= 0.5f / scale)
                assertTrue("'${w.text}' sits on its line ($lowest)", lowest >= -(0.5f / scale + laid.em * 0.08f))
            }
            at += n
        }
        // One size: every x-height word's ink is the same height on every line (same em).
        assertTrue(laid.em in units(LineLayout.MIN_EM_MM)..units(LineLayout.MAX_EM_MM))

        // A picture of it, for people.
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val a = cov[y * out.width + x]
            if (a > 0f) {
                val c = out.getPixel(x, y)
                fun mix(bg: Int, fg: Int) = (bg * (1 - a) + fg * a).toInt()
                out.setPixel(x, y, Color.rgb(mix(Color.red(c), 0x1E), mix(Color.green(c), 0x1F), mix(Color.blue(c), 0x24)))
            }
        }
        File(File("build/reports/type-on-lines").apply { mkdirs() }, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theNameBlankStartsAfterItsLabel() {
        val found = lines(worksheet())
        val name = LinePicker.atPoint(found, units(50f), units(11f), units(2.5f))
        assertNotNull(name)
        val laid = LineLayout(ink.measure(), mmPerUnit).layout(listOf(name!!), "Ada Lovelace")
        assertTrue("starts after 'Name:'", laid.words.first().x > units(10f) + units(8f))
        assertEquals(min(name.yAt(laid.words.first().x), name.y), laid.words.first().y + laid.em * LineLayout.LIFT, 0.5f)
    }
}
