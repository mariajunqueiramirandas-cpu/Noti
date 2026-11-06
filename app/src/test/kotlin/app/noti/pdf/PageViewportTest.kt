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

    // ---- the Endless canvas: one sheet, no pages ----

    /** A 300×400 view onto a sheet of 16 screens of 100 px, home 4 screens down, growing 8 ahead. */
    private class SheetFixture(aspect: Float = 1f) {
        lateinit var state: PagePlacement
        val viewport = PageViewport(10f) { state = it }.apply {
            setSheet(100f, 16f, 4f, 8f)
            setCanvas(300, 400)
            setDocument(floatArrayOf(aspect))
        }
        val sheet get() = state.visiblePages.single()
    }

    @Test fun sheetOpensAtHomeFillingTheViewWithoutPages() {
        val f = SheetFixture()
        assertEquals(0, f.state.page)
        assertEquals(1600f, f.sheet.width, 0f)
        // The middle column sits in the middle of the view, home row at the top (below the inset).
        assertEquals(150f - 800f, f.sheet.left, 0f)
        assertEquals(10f - 400f, f.sheet.top, 0f)
        assertTrue("paper reaches past every edge of the view", f.sheet.left < 0 && f.sheet.top < 0 &&
            f.sheet.left + f.sheet.width > 300 && f.sheet.top + f.sheet.height > 400)
        assertEquals("no scroll thumb", 1f, f.state.viewportFraction, 0f)
    }

    @Test fun sheetPansFreelyInEveryDirectionUntilItsSideAndTopEdges() {
        val f = SheetFixture()
        val home = f.sheet
        f.viewport.pan(250f, 300f)
        assertEquals(home.left + 250f, f.sheet.left, 0f)
        assertEquals(home.top + 300f, f.sheet.top, 0f)
        f.viewport.pan(-500f, -200f)
        assertEquals(home.left - 250f, f.sheet.left, 0f)
        f.viewport.pan(1e6f, 1e6f)
        assertEquals(0f, f.sheet.left, 0f)
        assertEquals(0f, f.sheet.top, 0f)
        f.viewport.pan(-1e6f, 0f)
        assertEquals(300f, f.sheet.left + f.sheet.width, 0f)
    }

    @Test fun sheetGrowsAheadOfTheViewSoItNeverEnds() {
        val f = SheetFixture()
        val start = f.sheet.height
        repeat(200) { f.viewport.pan(0f, -400f) }
        // 200 screens of 400 px down: never stopped by the bottom, always 8 screens of room left.
        assertEquals(-400f * 200 + (10f - 400f), f.sheet.top, 1f)
        assertTrue(f.sheet.height > start)
        assertEquals(400f + 8 * 100f, f.sheet.top + f.sheet.height, 1f)
        // One huge jump only grows the sheet by a step, not by the whole jump.
        val before = f.sheet.height
        f.viewport.pan(0f, -1e7f)
        assertTrue(f.sheet.height < before + 2000f)
    }

    @Test fun sheetKeepsInkFromAShorterDocumentAndNeverShrinks() {
        val f = SheetFixture(aspect = 3f)
        assertEquals(4800f, f.sheet.height, 0f)
        f.viewport.setDocument(floatArrayOf(1f))
        assertEquals(4800f, f.sheet.height, 0f)
    }

    @Test fun sheetZoomsAroundTheFingersAndResetKeepsTheCentre() {
        val f = SheetFixture()
        val before = f.sheet
        val x = (100f - before.left) / before.width
        val y = (300f - before.top) / before.width
        f.viewport.pinch(100f, 300f, 0.5f, 0f, 0f)
        assertEquals(0.5f, f.state.zoom, 0.001f)
        assertEquals(x, (100f - f.sheet.left) / f.sheet.width, 0.001f)
        assertEquals(y, (300f - f.sheet.top) / f.sheet.width, 0.001f)
        val centre = (150f - f.sheet.left) / f.sheet.width to (200f - f.sheet.top) / f.sheet.width
        f.viewport.resetZoom()
        assertEquals(1f, f.state.zoom, 0f)
        assertEquals(centre.first, (150f - f.sheet.left) / f.sheet.width, 0.001f)
        assertEquals(centre.second, (200f - f.sheet.top) / f.sheet.width, 0.001f)
        f.viewport.pinch(150f, 200f, 0.001f, 0f, 0f)
        assertEquals(PageViewport.MIN_ZOOM, f.state.zoom, 0f)
    }

    @Test fun sheetKeepsTheSameInkSizeAndCentreWhenTheViewResizes() {
        val f = SheetFixture()
        f.viewport.pan(-120f, -80f)
        val before = f.sheet
        val cx = 150f - before.left; val cy = 200f - before.top
        f.viewport.setCanvas(400, 300)
        assertEquals("a screen is a fixed size, not the view's width", before.width, f.sheet.width, 0f)
        assertEquals(cx, 200f - f.sheet.left, 1f)
        assertEquals(cy, 150f - f.sheet.top, 1f)
    }

    @Test fun sheetGoesHomeForAnyPage() {
        val f = SheetFixture()
        val home = f.sheet
        f.viewport.pinch(150f, 200f, 2f, 0f, 0f)
        f.viewport.pan(-300f, -900f)
        f.viewport.goTo(7)
        assertEquals(0, f.state.page)
        assertEquals(1f, f.state.zoom, 0f)
        assertEquals(home.left, f.sheet.left, 0f)
        assertEquals(home.top, f.sheet.top, 0f)
    }

    @Test fun sheetSetBeforeTheViewHasASizeStillOpensAtHome() {
        lateinit var state: PagePlacement
        val viewport = PageViewport(10f) { state = it }
        viewport.setCanvas(300, 400)
        viewport.setSheet(100f, 16f, 4f, 8f)
        viewport.setDocument(floatArrayOf(1f))
        assertEquals(-650f, state.left, 0f)
        assertEquals(-390f, state.top, 0f)
    }
}
