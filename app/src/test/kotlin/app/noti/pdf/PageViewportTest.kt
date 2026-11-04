package app.noti.pdf

import org.junit.Assert.*
import org.junit.Test

class PageViewportTest {
    private class Fixture(aspects: FloatArray = floatArrayOf(1f, 1f, 1f)) {
        lateinit var state: PagePlacement
        val viewport = PageViewport(10f) { state = it }.apply {
            setCanvas(300, 400)
            setDocument(aspects)
        }
    }

    @Test fun pagesAreWidthFittedAndNeighboursAreVisible() {
        val f = Fixture()
        assertEquals(280f, f.state.width, 0.001f)
        assertEquals(listOf(0, 1), f.state.visiblePages.map { it.page })
        assertEquals(10f, f.state.visiblePages.first().top, 0.001f)
        assertEquals(300f, f.state.visiblePages.last().top, 0.001f)
    }

    @Test fun verticalDragCrossesPageBoundaryWithoutSnapping() {
        val f = Fixture()
        f.viewport.begin(); f.viewport.pan(0f, -150f)
        assertFalse(f.state.settled)
        assertEquals(-140f, f.state.visiblePages.first().top, 0.001f)
        assertEquals(150f, f.state.visiblePages.last().top, 0.001f)
        assertEquals(1, f.state.page)
        f.viewport.end()
        assertTrue(f.state.settled)
        assertEquals(-140f, f.state.visiblePages.first().top, 0.001f)
    }

    @Test fun documentBoundsPreventScrollingPastFirstAndLastPage() {
        val f = Fixture()
        f.viewport.pan(0f, 100000f)
        assertEquals(10f, f.state.visiblePages.first().top, 0.001f)
        f.viewport.pan(0f, -100000f)
        val last = f.state.visiblePages.last()
        assertEquals(2, last.page)
        assertEquals(390f, last.top + last.height, 0.001f)
    }

    @Test fun pinchPreservesContentUnderFocalPoint() {
        val f = Fixture()
        val before = f.state.visiblePages.first()
        val x = (150f - before.left) / before.width
        val y = (200f - before.top) / before.width
        f.viewport.pinch(150f, 200f, 2f, 0f, 0f)
        val after = f.state.visiblePages.first()
        assertEquals(x, (150f - after.left) / after.width, 0.001f)
        assertEquals(y, (200f - after.top) / after.width, 0.001f)
        assertEquals(2f, f.state.zoom, 0.001f)
    }

    @Test fun navigationSupportsMixedPageSizesAndClampsIndices() {
        val f = Fixture(floatArrayOf(1.4f, 0.7f, 2f))
        f.viewport.goTo(2)
        assertEquals(2, f.state.page)
        assertEquals(10f, f.state.top, 0.001f)
        f.viewport.goTo(-10)
        assertEquals(0, f.state.page)
        f.viewport.goTo(200)
        assertEquals(2, f.state.page)
    }

    @Test fun resizeKeepsLocationInPageCoordinates() {
        val f = Fixture(FloatArray(10) { 1.4f })
        f.viewport.goTo(4); f.viewport.pan(0f, -80f)
        val before = f.state
        f.viewport.setCanvas(500, 400)
        assertEquals(4, f.state.page)
        assertEquals(before.top / before.width, f.state.top / f.state.width, 0.001f)
    }

    @Test fun largeDocumentsOnlyEmitVisiblePages() {
        val f = Fixture(FloatArray(2000) { 1.4f })
        f.viewport.goTo(1990)
        assertEquals(1990, f.state.page)
        assertTrue(f.state.visiblePages.size <= 3)
        f.viewport.setDocument(floatArrayOf(1.4f))
        assertEquals(0, f.state.page)
        assertEquals(listOf(0), f.state.visiblePages.map { it.page })
    }

    @Test fun invalidGestureAndPageValuesRemainFinite() {
        val f = Fixture(floatArrayOf(Float.NaN, -1f))
        f.viewport.pan(Float.NaN, 0f)
        f.viewport.pinch(150f, 200f, Float.POSITIVE_INFINITY, 0f, 0f)
        assertTrue(f.state.top.isFinite())
        assertTrue(f.state.height > 0f)
    }
}
