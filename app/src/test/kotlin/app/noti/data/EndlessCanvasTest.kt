package app.noti.data

import org.junit.Assert.*
import org.junit.Test

class EndlessCanvasTest {
    private val pen = floatArrayOf(0f, 0f, 0f, 1f)

    @Test fun archiveRoundTripsExactly() {
        val strokes = listOf(
            InkArchive.Stroke(0, pen, floatArrayOf(1f, 2f, 0.5f, 3f, 4f, 0.75f)),
            InkArchive.Stroke(3, floatArrayOf(0.2f, 0.1f, 0f, 0.4f), floatArrayOf(-5f, 6f, 1f, 7f, 8f, 1f, 9f, 10f, 1f, 11f, 12f, 1f)),
        )
        val back = InkArchive.decode(InkArchive.encode(strokes))
        assertEquals(2, back.size)
        assertEquals(3, back[1].page)
        assertArrayEquals(strokes[1].color, back[1].color, 0f)
        assertArrayEquals(strokes[1].capsules, back[1].capsules, 0f)
        assertTrue(InkArchive.decode(null).isEmpty())
    }

    @Test fun formerPagesBecomeTheHomeColumnAtTheSameSizeOnScreen() {
        val k = EndlessCanvas.SPAN
        val bytes = InkArchive.encode(listOf(
            InkArchive.Stroke(0, pen, floatArrayOf(0f, 0f, 8f, 1000f, 100f, 16f)),
            InkArchive.Stroke(2, pen, floatArrayOf(500f, 10f, 4f, 500f, 20f, 4f)),
        ))
        val moved = InkArchive.decode(EndlessCanvas.fromPages(bytes))
        assertTrue(moved.all { it.page == 0 })
        val first = moved[0].capsules
        // A former page is one screen of canvas: its corner is home's, and sizes shrink by the
        // same factor the sheet is wider, so they look as they did.
        assertEquals(EndlessCanvas.HOME_LEFT_UNITS, first[0], 0f)
        assertEquals(EndlessCanvas.HOME_TOP_UNITS, first[1], 0f)
        assertEquals(8f / k, first[2], 0f)
        assertEquals(EndlessCanvas.HOME_LEFT_UNITS + EndlessCanvas.BASE_UNITS, first[3], 1e-4f)
        assertEquals(16f / k, first[5], 0f)
        // The home column is the middle of the sheet.
        assertEquals(500f, (first[0] + first[3]) / 2, 1e-4f)
        // Later pages continue below, in order, without overlapping.
        val third = moved[1].capsules
        assertEquals(500f, third[0], 0.01f)
        assertTrue(third[1] > EndlessCanvas.HOME_TOP_UNITS + 2 * 1414f / k)
        assertNull(EndlessCanvas.fromPages(null))
    }

    @Test fun sheetStartsTallEnoughForAllInkWithRoomBelow() {
        assertEquals(EndlessCanvas.MIN_ASPECT, EndlessCanvas.aspect(emptyList()), 0f)
        val deep = listOf(InkArchive.Stroke(0, pen, floatArrayOf(10f, 3000f, 2f, 20f, 3100f, 2f)))
        val aspect = EndlessCanvas.aspect(deep)
        assertEquals((3102f + EndlessCanvas.GROW * EndlessCanvas.BASE_UNITS) / 1000f, aspect, 1e-4f)
    }

    @Test fun onlyPaperlessEndlessNotebooksAreCanvases() {
        fun nb(size: PaperSize, pdf: String? = null) = Notebook("id", "n", Paper(size = size), createdAt = 0, openedAt = 0, pdf = pdf)
        assertTrue(nb(PaperSize.Endless).isCanvas)
        assertFalse(nb(PaperSize.A4).isCanvas)
        assertFalse(nb(PaperSize.Letter).isCanvas)
        assertFalse("an imported PDF keeps its pages", nb(PaperSize.Endless, pdf = "x.pdf").isCanvas)
    }
}
