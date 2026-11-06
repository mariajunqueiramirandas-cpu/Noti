package app.noti.transcribe

import org.junit.Assert.*
import org.junit.Test

class PageFrameTest {
    private val pen = floatArrayOf(0f, 0f, 0f, 1f)

    @Test fun canvasWritingIsReadInAPageFrameStartingAtItsLeftEdge() {
        // Handwriting 2 units tall on a 16-screen sheet is 32 units tall on a page.
        val stroke = SelectedStroke(4, 0, pen, floatArrayOf(400f, 300f, 450f, 302f))
        val frame = PageFrame.canvas(listOf(stroke), 16f)
        val local = frame.toPage(stroke)
        assertEquals(4, local.id)
        assertEquals(TextInk.Margin, local.x0, 1e-3f)
        assertEquals(0f, local.y0, 1e-3f)
        assertEquals(32f, local.height, 1e-3f)
        assertEquals(800f, local.width, 1e-3f)
    }

    @Test fun typedTextGoesBackWhereTheWritingWas() {
        val stroke = SelectedStroke(0, 0, pen, floatArrayOf(400f, 300f, 450f, 302f))
        val frame = PageFrame.canvas(listOf(stroke), 16f)
        val back = frame.toEngine(TextInk.Strokes(floatArrayOf(24f, 0f, 16f, 824f, 32f, 8f), intArrayOf(1)))
        assertArrayEquals(floatArrayOf(400f, 300f, 1f, 450f, 302f, 0.5f), back.capsules, 1e-3f)
        assertArrayEquals(intArrayOf(1), back.sizes)
    }

    @Test fun pagesUseTheirOwnUnits() {
        val stroke = SelectedStroke(0, 0, pen, floatArrayOf(1f, 2f, 3f, 4f))
        assertSame(stroke, PageFrame.Identity.toPage(stroke))
        assertSame(PageFrame.Identity, PageFrame.canvas(emptyList(), 16f))
    }
}
