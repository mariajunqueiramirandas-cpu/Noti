package app.noti.transcribe

import org.junit.Assert.*
import org.junit.Test

class InkLinesTest {
    private var nextId = 0
    private val pen = floatArrayOf(0.1f, 0.1f, 0.1f, 1f)
    private val highlighter = floatArrayOf(0.4f, 0.36f, 0.04f, 0.4f)

    private fun stroke(vararg xy: Float, color: FloatArray = pen) = SelectedStroke(nextId++, 0, color, xy)

    /** A handwritten line: letters 10 tall resting on [baseline], starting at [x]. */
    private fun line(x: Float, baseline: Float): List<SelectedStroke> = listOf(
        stroke(x, baseline - 18, x, baseline),                     // l: ascender
        stroke(x + 6, baseline - 10, x + 12, baseline, x + 6, baseline - 10),  // o-ish: x-height
        stroke(x + 16, baseline - 10, x + 16, baseline + 7),       // g/p: descender
        stroke(x + 22, baseline - 10, x + 22, baseline),           // i stem
        stroke(x + 22, baseline - 15, x + 22.5f, baseline - 14.5f), // i dot
        stroke(x + 28, baseline - 16, x + 28, baseline),           // t stem
        stroke(x + 25, baseline - 11, x + 31, baseline - 11),      // t crossbar
        stroke(x + 34, baseline, x + 35, baseline + 2),            // comma
    )

    @Test fun parsesTheEngineSelectionFormat() {
        // [count, id, page, r, g, b, a, n, capsules…]: two strokes, the second on page 3.
        val data = floatArrayOf(2f,
            5f, 0f, 0.1f, 0.2f, 0.3f, 1f, 2f, 10f, 20f, 1f, 30f, 40f, 1f, 30f, 40f, 1f, 50f, 60f, 1f,
            9f, 3f, 0.4f, 0.4f, 0.1f, 0.4f, 1f, 1f, 2f, 0.5f, 3f, 4f, 0.5f)
        val strokes = InkLines.parse(data)
        assertEquals(2, strokes.size)
        assertEquals(5, strokes[0].id); assertEquals(0, strokes[0].page)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f), strokes[0].points, 0f)
        assertTrue(strokes[0].isWriting)
        assertEquals(9, strokes[1].id); assertEquals(3, strokes[1].page)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), strokes[1].points, 0f)
        assertFalse("highlighter ink is translucent", strokes[1].isWriting)
        assertEquals(10f, strokes[0].x0); assertEquals(60f, strokes[0].y1)
    }

    @Test fun truncatedDataStopsWithoutCrashing() {
        assertTrue(InkLines.parse(floatArrayOf()).isEmpty())
        assertEquals(0, InkLines.parse(floatArrayOf(3f, 1f, 0f, 0f, 0f, 0f, 1f, 4f, 1f, 2f)).size)
    }

    @Test fun groupsTwoLinesKeepingDotsCommasAndDescendersOnTheirLine() {
        val first = line(100f, 100f)
        val second = line(100f, 140f)
        // Written in order, but grouping must not depend on it: shuffle the input.
        val lines = InkLines.group((second + first).shuffled(java.util.Random(7)))
        assertEquals(2, lines.size)
        assertEquals(first.map { it.id }, lines[0].strokes.map { it.id })
        assertEquals(second.map { it.id }, lines[1].strokes.map { it.id })
        assertEquals(40f, InkLines.pitch(lines), 0.5f)
    }

    @Test fun sideBySideWordsOnOneLineStayOneLine() {
        val words = line(100f, 100f) + line(160f, 101f) + line(220f, 99f)
        val lines = InkLines.group(words)
        assertEquals(1, lines.size)
        assertEquals(100f, lines[0].x0)
    }

    @Test fun aLoneDotOrCommaStillMakesALine() {
        val lines = InkLines.group(listOf(stroke(10f, 10f, 11f, 11f)))
        assertEquals(1, lines.size)
    }

    @Test fun pitchOfOneLineFollowsItsHeight() {
        val lines = InkLines.group(line(0f, 50f))
        assertEquals(1, lines.size)
        assertEquals(lines[0].height * 1.6f, InkLines.pitch(lines), 0.01f)
    }

    @Test fun highlighterStrokesAreNotWriting() {
        assertFalse(stroke(0f, 0f, 10f, 0f, color = highlighter).isWriting)
        assertTrue(stroke(0f, 0f, 10f, 0f).isWriting)
    }
}
