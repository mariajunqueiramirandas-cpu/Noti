package app.noti.pdf

import app.noti.pdf.PdfDocument.Companion.MAX_LEVEL
import app.noti.pdf.PdfDocument.Companion.PREVIEW_WIDTH
import app.noti.pdf.PdfDocument.Companion.baseOf
import app.noti.pdf.PdfDocument.Companion.levelFor
import app.noti.pdf.PdfDocument.Companion.levelWidth
import app.noti.pdf.PdfDocument.Companion.needsTiles
import org.junit.Assert.*
import org.junit.Test

class PdfTilesTest {
    private val base = 3046

    @Test fun levelsAreHalfOctavesAnchoredAtTheFitWidth() {
        assertEquals(base, levelWidth(base, 0))
        assertEquals(base * 2, levelWidth(base, 2))
        assertEquals(base * 4, levelWidth(base, 4))
        assertEquals(base / 2, levelWidth(base, -2))
        for (l in -3..MAX_LEVEL - 1) {
            val ratio = levelWidth(base, l).toDouble() / levelWidth(base, l - 1)
            assertEquals(Math.sqrt(2.0), ratio, 0.01)
        }
    }

    @Test fun fitWidthAndDoubleZoomArePixelExact() {
        assertEquals(0, levelFor(base.toFloat(), base))
        assertEquals(2, levelFor(base * 2f, base))
        assertEquals(base, baseOf(PagePlacement(0, 0f, 0f, base * 2f, base * 2.8f, 2f, 3136, 2000, true)))
    }

    @Test fun chosenLevelIsAboutAsSharpAsTheScreenWithoutWastingMemory() {
        for (zoom in listOf(0.3f, 0.6f, 1f, 1.3f, 2.5f, 4f, 6f)) {
            val w = base * zoom
            val l = levelFor(w, base)
            assertTrue("level $l too coarse at $zoom×", levelWidth(base, l) >= w * 0.92f)
            // The next coarser level would be visibly soft: at most √2 oversampling.
            assertTrue("level $l wastes memory at $zoom×", levelWidth(base, l - 1) < w * 0.92f)
        }
    }

    @Test fun smallPagesUseThePreviewAlone() {
        assertFalse(needsTiles(PREVIEW_WIDTH.toFloat()))
        assertFalse(needsTiles(PREVIEW_WIDTH * 1.1f))
        assertTrue(needsTiles(PREVIEW_WIDTH * 1.5f))
        assertTrue(needsTiles(2800f))
    }
}
