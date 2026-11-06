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

    @Test fun zoomOutShowsMultiplePagesAndClampsAtQuarterSize() {
        val f = Fixture(FloatArray(20) { 1.4f })
        f.viewport.goTo(8)
        f.viewport.pinch(150f, 200f, 0.01f, 0f, 0f)
        assertEquals(0.25f, f.state.zoom, 0.001f)
        assertEquals(70f, f.state.width, 0.001f)
        assertEquals(115f, f.state.left, 0.001f)
        assertTrue(f.state.visiblePages.size >= 4)
        f.viewport.scrollTo(1f)
        assertEquals(19, f.state.visiblePages.last().page)
        // Pages snap to whole pixels.
        assertEquals(390f, f.state.visiblePages.last().let { it.top + it.height }, 1f)
    }

    @Test fun zoomingOutPreservesVerticalFocalPointAndResetReturnsToFitWidth() {
        val f = Fixture(FloatArray(20) { 1.4f })
        f.viewport.goTo(8)
        val before = f.state
        f.viewport.pinch(150f, 200f, 0.5f, 0f, 0f)
        assertEquals(0.5f, f.state.zoom, 0.001f)
        assertEquals(before.page, f.state.page)
        assertEquals((200f - before.top) / before.width, (200f - f.state.top) / f.state.width, 0.001f)
        f.viewport.resetZoom()
        assertEquals(1f, f.state.zoom, 0.001f)
        assertEquals(before.top, f.state.top, 0.001f)
    }

    @Test fun shortZoomedOutDocumentIsCentredWithNoScrollRange() {
        val f = Fixture(floatArrayOf(1.4f))
        f.viewport.pinch(150f, 200f, 0.25f, 0f, 0f)
        assertEquals(151f, f.state.top, 0.001f)
        assertEquals(1f, f.state.viewportFraction, 0f)
        f.viewport.pan(100000f, -100000f)
        assertEquals(115f, f.state.left, 0.001f)
        assertEquals(151f, f.state.top, 0.001f)
        assertEquals(0f, f.state.scrollFraction, 0f)
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
    @Test fun scrollIndicatorTracksContinuousPositionAndScrubsMixedPages() {
        val f = Fixture(floatArrayOf(1.4f, 0.7f, 2f))
        assertEquals(0f, f.state.scrollFraction, 0.001f)
        assertTrue(f.state.viewportFraction in 0f..1f)
        f.viewport.scrollTo(0.5f)
        assertEquals(0.5f, f.state.scrollFraction, 0.001f)
        f.viewport.pinch(150f, 200f, 2f, 0f, 0f)
        val zoomedVisible = f.state.viewportFraction
        f.viewport.scrollTo(1f)
        assertEquals(1f, f.state.scrollFraction, 0.001f)
        assertEquals(2f, f.state.zoom, 0.001f)
        assertTrue(zoomedVisible < 0.5f)
        val last = f.state.visiblePages.last()
        assertEquals(390f, last.top + last.height, 0.001f)
        f.viewport.scrollTo(Float.NaN)
        assertEquals(1f, f.state.scrollFraction, 0.001f)
    }

    @Test fun zoomResetPreservesDocumentPointAtViewportCentre() {
        val f = Fixture(FloatArray(20) { 1.4f })
        f.viewport.goTo(8)
        f.viewport.pinch(150f, 200f, 3f, 0f, 0f)
        val before = f.state
        f.viewport.resetZoom()
        assertEquals(1f, f.state.zoom, 0.001f)
        assertEquals(before.page, f.state.page)
        assertEquals((200f - before.top) / before.width, (200f - f.state.top) / f.state.width, 0.001f)
    }

    @Test fun shortDocumentHasFullScrollThumbAndFinitePosition() {
        val f = Fixture(floatArrayOf(0.2f))
        f.viewport.scrollTo(1f)
        assertEquals(0f, f.state.scrollFraction, 0f)
        assertEquals(1f, f.state.viewportFraction, 0f)
    }

    @Test fun floatingChromeLeavesFirstAndLastPageContentReachable() {
        val f = Fixture()
        f.viewport.setVerticalInsets(96f, 88f)
        f.viewport.scrollTo(0f)
        assertEquals(96f, f.state.visiblePages.first().top, 0.001f)
        f.viewport.scrollTo(1f)
        val last = f.state.visiblePages.last()
        assertEquals(312f, last.top + last.height, 0.001f)
        f.viewport.setVerticalInsets(10f, 10f)
        f.viewport.scrollTo(1f)
        assertEquals(390f, f.state.visiblePages.last().let { it.top + it.height }, 0.001f)
    }

    @Test fun nearbyPagesReachAboutAScreenBeyondTheViewportForPrefetching() {
        val f = Fixture(FloatArray(20) { 1f })
        f.viewport.goTo(10)
        val visible = f.state.visiblePages.map { it.page }
        val nearby = f.state.nearbyPages.map { it.page }
        assertTrue(nearby.containsAll(visible))
        assertTrue("pages above are prefetched", nearby.first() < visible.first())
        assertTrue("pages below are prefetched", nearby.last() > visible.last())
        f.state.nearbyPages.forEach { p ->
            assertTrue(p.top + p.height >= -400f && p.top <= 800f)
        }
    }

    @Test fun pagesSnapToWholePixelsWithoutLosingSubPixelMotion() {
        val f = Fixture(FloatArray(5) { 1f })
        f.viewport.begin()
        f.viewport.pan(0f, -10.3f)
        f.viewport.pan(0f, -10.3f)
        val moving = f.state.visiblePages.first()
        assertFalse(f.state.settled)
        assertEquals(moving.top, Math.round(moving.top).toFloat(), 0f)
        // Integral page tops while moving: a pan is an integral translation for every page.
        val before = f.state.visiblePages.map { it.page to it.top }.toMap()
        f.viewport.pan(0f, -7.6f)
        f.state.visiblePages.forEach { p ->
            before[p.page]?.let { top -> assertEquals(Math.round(top - p.top).toFloat(), top - p.top, 0.001f) }
        }
        f.viewport.end()
        assertTrue(f.state.settled)
        assertEquals(-18f, f.state.visiblePages.first().top, 0f)
        // Sub-pixel motion isn't lost, only snapped when shown.
        f.viewport.pan(0f, -0.4f)
        assertEquals(-19f, f.state.visiblePages.first().top, 0f)
    }
}
