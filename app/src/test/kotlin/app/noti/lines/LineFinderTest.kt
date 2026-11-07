package app.noti.lines

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.noti.R
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Synthetic pages drawn with real text (Nunito, like a worksheet would use some font) at
 * LineFinder's own resolution, 6 px per mm. Positions are in mm on the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineFinderTest {
    private val k = LineFinder.PX_PER_MM
    private val typeface = RuntimeEnvironment.getApplication().resources.getFont(R.font.nunito_regular)

    private inner class Page(val wMm: Float = 160f, val hMm: Float = 120f, paper: Int = Color.WHITE) {
        val bitmap: Bitmap = Bitmap.createBitmap((wMm * k).toInt(), (hMm * k).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).also { it.drawColor(paper) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun rule(x0: Float, x1: Float, y: Float, color: Int = Color.BLACK, thick: Float = 0.25f) {
            paint.color = color; paint.style = Paint.Style.FILL
            canvas.drawRect(x0 * k, y * k, x1 * k, (y + thick) * k, paint)
        }
        fun vertical(x: Float, y0: Float, y1: Float, thick: Float = 0.25f) {
            paint.color = Color.BLACK; paint.style = Paint.Style.FILL
            canvas.drawRect(x * k, y0 * k, (x + thick) * k, y1 * k, paint)
        }
        /** Text with its baseline at [y] mm, [size] pt (1 pt = 0.3528 mm). */
        fun text(s: String, x: Float, y: Float, size: Float = 11f, color: Int = Color.BLACK) {
            paint.color = color; paint.typeface = typeface; paint.textSize = size * 0.3528f * k
            canvas.drawText(s, x * k, y * k, paint)
        }
        fun textWidth(s: String, size: Float = 11f): Float {
            paint.typeface = typeface; paint.textSize = size * 0.3528f * k
            return paint.measureText(s) / k
        }
        fun find(): List<FoundLine> {
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return LineFinder(k).find(Raster.fromArgb(bitmap.width, bitmap.height, px))
        }
    }

    private fun assertMm(expected: Float, actualPx: Float, tolMm: Float = 0.25f, what: String = "") =
        assertEquals(what, expected, actualPx / k, tolMm)

    @Test fun answerLinesUnderAQuestion() {
        val p = Page()
        p.text("2. Why does a hot cup of tea cool down? Explain.", 10f, 20f)
        for (i in 0 until 4) p.rule(10f, 150f, 32f + i * 9f, Color.rgb(0x8F, 0xB3, 0xD9))
        val lines = p.find()
        assertEquals(lines.toString(), 4, lines.size)
        lines.forEachIndexed { i, l ->
            assertTrue(l.sure)
            assertEquals(LineKind.Rule, l.kind)
            assertMm(10f, l.x0, what = "left end"); assertMm(150f, l.x1, what = "right end")
            assertMm(32f + i * 9f, l.y, what = "top edge")
            assertEquals(0f, l.slope, 0f)
        }
        // Room above the first line reaches up to the question; the others to the line above.
        assertMm(9f - 0.25f, lines[2].room, 0.4f, "room between lines")
        assertTrue("room above the first line stops at the question", lines[0].room / k in 8f..12f)
    }

    @Test fun aBlankAfterALabelStartsAfterItAndKnowsTheLabel() {
        val p = Page()
        p.text("Name:", 10f, 30f)
        val start = 10f + p.textWidth("Name:") + 1.5f
        p.rule(start, 90f, 30.4f)
        val l = p.find().single()
        assertTrue(l.sure)
        assertMm(start, l.x0); assertMm(start, l.start)
        val label = requireNotNull(l.label)
        assertMm(10f, label[0], 0.4f, "label left")
        assertTrue("label ends before the line", label[2] <= l.x0)
        assertTrue("no room limit from the page above", l.room / k > 20f)
    }

    @Test fun aLabelPrintedOnAFullLineMovesTheStartPastIt() {
        val p = Page()
        p.text("Date", 12f, 40f)
        p.rule(10f, 140f, 40.4f)
        val l = p.find().single()
        assertTrue(l.sure)
        assertMm(10f, l.x0)
        assertTrue("start after the label: ${l.start / k}", l.start / k in (12f + p.textWidth("Date"))..(12f + p.textWidth("Date") + 3f))
        assertNotNull(l.label)
    }

    @Test fun dotsAfterALabelAreALine() {
        val p = Page()
        p.text("Date: " + ".".repeat(40), 10f, 30f)
        val l = p.find().single()
        assertEquals(LineKind.Dotted, l.kind)
        assertTrue(l.sure)
        assertTrue(l.length / k > 20f)
        assertNotNull(l.label)
    }

    @Test fun dashedLinesToo() {
        val p = Page()
        var x = 20f
        while (x < 100f) { p.rule(x, x + 1.5f, 50f); x += 2.5f }
        val l = p.find().single()
        assertEquals(LineKind.Dotted, l.kind)
        assertMm(20f, l.x0, 0.4f)
        assertMm(50f, l.y, 0.3f)
    }

    @Test fun anUnderlinedHeadingIsNotABlank() {
        val p = Page()
        p.text("Chapter four: thermodynamics", 10f, 30f, size = 14f)
        p.rule(10f, 10f + p.textWidth("Chapter four: thermodynamics", 14f), 31f)
        p.rule(10f, 140f, 60f)
        val lines = p.find()
        assertEquals(lines.toString(), 2, lines.size)
        assertFalse("underline", lines[0].sure)
        assertTrue("blank", lines[1].sure)
    }

    @Test fun plainTextHasNoLines() {
        val p = Page()
        val words = "The quick brown fox jumps over the lazy dog while the THERMOMETER reads TTTT EEEE ----- ====="
        for (i in 0 until 8) p.text(words, 8f, 15f + i * 7f, size = if (i == 3) 28f else 11f)
        val lines = p.find().filter { it.sure }
        assertTrue("no blanks in a paragraph: $lines", lines.isEmpty())
    }

    @Test fun tableCellsAreLinesBetweenTheirWalls() {
        val p = Page()
        val xs = listOf(10f, 50f, 90f, 130f)
        for (y in listOf(20f, 30f, 40f)) p.rule(10f, 130.25f, y)
        for (x in xs) p.vertical(x, 20f, 40.25f)
        val lines = p.find()
        val cells = lines.filter { it.kind == LineKind.Cell }
        assertEquals(lines.toString(), 6, cells.size)
        val first = cells.first()
        assertMm(30f, first.y, 0.3f)
        assertTrue("inside the left wall", first.x0 / k > 10.25f && first.x0 / k < 11.5f)
        assertTrue("inside the right wall", first.x1 / k < 50f && first.x1 / k > 48.5f)
        assertMm(10f - 0.25f, first.room, 0.6f, "room is the cell's height")
        // The table's top edge isn't a writing line.
        assertTrue(lines.none { it.sure && abs(it.y / k - 20f) < 0.5f })
    }

    @Test fun aTallBoxGetsSeveralLinesInside() {
        val p = Page()
        p.rule(20f, 120.25f, 20f); p.rule(20f, 120.25f, 60f)
        p.vertical(20f, 20f, 60.25f); p.vertical(120f, 20f, 60.25f)
        val box = p.find().filter { it.kind == LineKind.Box }
        assertTrue("box lines: $box", box.size in 4..5)
        assertMm(60f, box.last().y, 0.3f)
        box.zipWithNext { a, b -> assertEquals(8f, (b.y - a.y) / k, 1.5f) }
    }

    @Test fun aCombIsOneBoxPerCharacter() {
        val p = Page()
        p.text("Student number", 50f - 2f - p.textWidth("Student number"), 34.8f)
        p.rule(50f, 106.25f, 35f)
        for (i in 0..8) p.vertical(50f + i * 7f, 31f, 35.25f)
        val comb = p.find().single { it.kind == LineKind.Comb }
        val cells = requireNotNull(comb.cells)
        assertEquals(9, cells.size)
        assertMm(50.12f, cells.first(), 0.3f)
        assertMm(106.12f, cells.last(), 0.3f)
        assertNotNull(comb.label)
    }

    @Test fun aTiltedNoisyScanStillGivesStraightLinesAlongTheTilt() {
        val p = Page(paper = Color.rgb(226, 224, 218))
        val r = Random(7)
        // Paper grain.
        val paint = Paint()
        repeat(6000) {
            paint.color = Color.rgb(200 + r.nextInt(30), 198 + r.nextInt(30), 190 + r.nextInt(30))
            p.canvas.drawPoint(r.nextFloat() * p.bitmap.width, r.nextFloat() * p.bitmap.height, paint)
        }
        p.canvas.save()
        p.canvas.rotate(2f, 0f, 0f)
        p.text("Describe what you see in the picture.", 12f, 14f)
        for (i in 0 until 5) p.rule(12f, 140f, 24f + i * 10f, Color.rgb(120, 120, 120), thick = 0.3f)
        p.canvas.restore()
        val lines = p.find().filter { it.sure }
        assertEquals(lines.toString(), 5, lines.size)
        val rad = Math.toRadians(2.0)
        lines.forEachIndexed { i, l ->
            assertEquals(2f, LineFinder.degrees(l.slope), 0.15f)
            // Where the rule really is at both ends (rotated about the origin).
            for (x in listOf(20f, 130f)) {
                val yRot = x * Math.sin(rad) + (24f + i * 10f) * Math.cos(rad)
                assertEquals("line $i at x=$x", yRot.toFloat(), l.yAt(x * k) / k, 0.35f)
            }
        }
    }

    @Test fun faintGreyLinesOnShadedPaper() {
        val p = Page(paper = Color.rgb(205, 205, 205))
        p.rule(10f, 150f, 30f, Color.rgb(165, 165, 165))
        p.rule(10f, 150f, 39f, Color.rgb(165, 165, 165))
        assertEquals(2, p.find().count { it.sure })
    }

    /** A whole A4 form page ("Fill this page" renders all of it): found, and quickly. */
    @Test fun aWholeFormPageIsQuick() {
        val p = Page(210f, 297f)
        p.text("Application form", 20f, 20f, size = 18f)
        for (i in 0 until 10) {
            val y = 40f + i * 12f
            p.text("Field $i:", 20f, y)
            p.rule(20f + p.textWidth("Field $i:") + 2f, 190f, y + 0.4f)
        }
        for (r in 0 until 6) for (w in 0 until 9) p.text("Some printed paragraph text to make the page realistic", 20f, 170f + r * 6f + w * 0.01f)
        for (i in 0 until 6) p.rule(20f, 190f, 220f + i * 9f, Color.rgb(0x8F, 0xB3, 0xD9))
        p.find() // warm up
        val t0 = System.nanoTime()
        val lines = p.find()
        val ms = (System.nanoTime() - t0) / 1e6
        println("Whole A4 page at 6 px/mm: ${lines.size} lines in ${"%.0f".format(ms)} ms")
        assertEquals(16, lines.count { it.sure })
        assertTrue("took $ms ms", ms < 1500)
    }

    @Test fun tinyRegionsAndBlankPagesFindNothing() {
        assertTrue(LineFinder(k).find(Raster(2, 2, ByteArray(4) { -1 })).isEmpty())
        assertTrue(Page().find().isEmpty())
    }
}
