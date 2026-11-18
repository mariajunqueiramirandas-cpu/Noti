package app.noti.lines

import org.junit.Assert.*
import org.junit.Test

/** A monospace make-believe font: every character 0.5 em, so positions are easy to check. */
class LineLayoutTest {
    private val mono = Measure { it.length * 0.5f }
    /** 1 page unit = 1 mm, to keep the numbers readable. */
    private val layout = LineLayout(mono, mmPerUnit = 1f)

    private fun rules(n: Int, x0: Float = 10f, x1: Float = 110f, top: Float = 50f, pitch: Float = 8f) =
        List(n) { WriteLine(x0, x1, top + it * pitch, room = pitch) }

    @Test fun oneSizeFittedToTheSpacingAndBaselinesOnTheLines() {
        val lines = rules(3)
        val laid = layout.layout(lines, "hello world")
        assertEquals(8f * 0.62f, laid.em, 1e-4f)
        assertEquals(2, laid.words.size)
        val first = laid.words[0]
        assertEquals(50f - laid.em * LineLayout.LIFT, first.y, 1e-4f)
        assertEquals(10f + laid.em * 0.25f, first.x, 1e-4f)
        // "world" follows "hello" after a space.
        assertEquals(first.x + 5 * 0.5f * laid.em + 0.5f * laid.em, laid.words[1].x, 1e-3f)
        assertTrue(laid.fits)
    }

    @Test fun wordsWrapToTheNextLineAndNeverPassTheEnd() {
        val lines = rules(3, x1 = 50f)
        val text = List(12) { "word" }.joinToString(" ")
        val laid = layout.layout(lines, text, TypeStyle(size = 4f))
        assertTrue(laid.fits)
        val byLine = laid.words.groupBy { it.line }
        assertTrue("used several lines", byLine.size >= 2)
        for (w in laid.words) assertTrue("${w.text} at ${w.x} passes the end", w.x + w.text.length * 0.5f * 4f <= 50f - 1f + 1e-3f)
        // Lines are filled in order: no line skipped.
        assertEquals((0 until byLine.size).toList(), byLine.keys.sorted())
    }

    @Test fun returnStartsTheNextLineAndLabelsMoveTheStart() {
        val lines = listOf(WriteLine(10f, 110f, 50f, room = 8f, start = 40f), WriteLine(10f, 110f, 58f, room = 8f))
        val laid = layout.layout(lines, "Ada\nLovelace", TypeStyle(size = 4f))
        assertEquals(0, laid.words[0].line); assertEquals(1, laid.words[1].line)
        assertEquals(40f + 1f, laid.words[0].x, 1e-4f)
        assertEquals(10f + 1f, laid.words[1].x, 1e-4f)
    }

    @Test fun overflowIsReportedShrunkOrContinued() {
        val lines = rules(2, x1 = 40f)
        val text = List(12) { "abc" }.joinToString(" ")
        val stop = layout.layout(lines, text, TypeStyle(size = 4f))
        assertFalse(stop.fits)
        assertTrue(stop.overflow.all { it.line >= 2 })
        assertEquals(12, stop.words.size + stop.overflow.size)

        val shrink = layout.layout(lines, text, TypeStyle(size = 4f, overflow = Overflow.Shrink))
        assertTrue(shrink.fits)
        assertTrue(shrink.em < 4f)
        assertTrue(shrink.words.all { it.line < 2 })

        val more = layout.layout(lines, text, TypeStyle(size = 4f, overflow = Overflow.KeepGoing))
        assertTrue(more.fits)
        assertTrue(more.lines.size > 2)
        // Extra lines keep the spacing.
        assertEquals(8f, more.lines[2].y - more.lines[1].y, 1e-4f)
    }

    @Test fun aWordLongerThanALineIsSplitNotOverflowed() {
        val laid = layout.layout(rules(5, x1 = 30f), "a".repeat(30), TypeStyle(size = 4f))
        assertTrue(laid.fits)
        assertTrue(laid.words.size >= 2)
        assertEquals("a".repeat(30), laid.words.joinToString("") { it.text })
    }

    @Test fun centredTextSitsInTheMiddle() {
        val line = WriteLine(0f, 100f, 50f, room = 8f)
        val laid = layout.layout(listOf(line), "ab", TypeStyle(size = 4f, align = TextAlign.Center))
        val w = laid.words.single()
        val mid = w.x + 2 * 0.5f * 4f / 2
        assertEquals(50f, mid, 0.01f)
    }

    @Test fun tiltedLinesCarryTheirSlope() {
        val line = WriteLine(0f, 100f, 50f, slope = 0.035f, room = 8f)
        val laid = layout.layout(listOf(line), "one two", TypeStyle(size = 4f))
        for (w in laid.words) {
            assertEquals(0.035f, w.slope, 0f)
            assertEquals(line.yAt(w.x) - 4f * LineLayout.LIFT, w.y, 1e-4f)
        }
    }

    @Test fun combsTakeOneCharacterPerBoxCentred() {
        val cells = floatArrayOf(10f, 17f, 24f, 31f, 38f)
        val comb = WriteLine(10f, 38f, 50f, room = 6f, kind = LineKind.Comb, cells = cells)
        val laid = layout.layout(listOf(comb), "4 21")
        assertEquals(listOf("4", "2", "1"), laid.words.map { it.text })
        val centres = laid.words.map { it.x + 0.25f * laid.em }
        assertEquals(13.5f, centres[0], 1e-3f)
        assertEquals(27.5f, centres[1], 1e-3f)   // the space took box 2
        assertEquals(34.5f, centres[2], 1e-3f)
        // Sized to the boxes, not just the room.
        assertTrue(laid.em <= 7f * 0.85f / LineLayout.DIGIT + 1e-4f)
        assertTrue(layout.layout(listOf(comb), "12345").overflow.isNotEmpty())
    }

    @Test fun sizesStayReadable() {
        assertEquals(LineLayout.MAX_EM_MM, layout.autoEm(listOf(WriteLine(0f, 100f, 50f, room = 40f))), 1e-4f)
        assertEquals(LineLayout.MIN_EM_MM, layout.autoEm(listOf(WriteLine(0f, 100f, 50f, room = 1f))), 1e-4f)
    }

    @Test fun caretFollowsTheText() {
        val lines = rules(2)
        val empty = layout.layout(lines, "", TypeStyle(size = 4f))
        assertEquals(10f + 1f, empty.caret!![0], 1e-4f)
        val two = layout.layout(lines, "ab\n", TypeStyle(size = 4f))
        assertEquals(58f - 4f * LineLayout.LIFT, two.caret!![1], 1e-4f)
    }

    @Test fun movedLinesKeepTheirShape() {
        val l = WriteLine(10f, 50f, 20f, room = 8f, start = 15f, kind = LineKind.Comb, cells = floatArrayOf(10f, 30f, 50f))
        val m = l.moved(5f, -2f)
        assertEquals(15f, m.x0); assertEquals(55f, m.x1); assertEquals(18f, m.y); assertEquals(20f, m.start)
        assertArrayEquals(floatArrayOf(15f, 35f, 55f), m.cells, 0f)
    }
}
