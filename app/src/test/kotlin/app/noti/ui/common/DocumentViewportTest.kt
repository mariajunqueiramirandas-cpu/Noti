package app.noti.ui.common

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import app.noti.ui.captureRenderedView
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.noti.data.Paper
import app.noti.data.PaperColor
import app.noti.pdf.PageImages
import app.noti.pdf.PagePlacement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel checks against the production PDF drawing path, not just viewport arithmetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentViewportTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderView: View

    @Test fun scrolledAndZoomedPdfNeverPaintsOverChromeAndControlsStillWork() {
        var placement by mutableStateOf(page(-160f, 600f))
        var clicks = 0
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val images = object : PageImages {
            override fun drawPage(canvas: android.graphics.Canvas, p: PagePlacement) {
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(p.left, p.top, p.left + p.width, p.top + p.height), null)
            }
        }
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            Column(Modifier.requiredSize(320.dp, 400.dp).background(Color.Blue).testTag("screen")) {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Green).testTag("toolbar").clickable { clicks++ })
                DocumentViewport(Modifier.fillMaxWidth().height(300.dp)) {
                    DocumentBackground(Paper(), images, { placement }, Modifier.fillMaxSize())
                    // Selection and shadow-like drawing must be contained by the same clip.
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.Magenta, Offset(-100f, -100f), Size(600f, 110f))
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Blue))
            }
        }
        fun assertChrome() {
            val image = compose.captureRenderedView(renderView, "screen")
            assertEquals("PDF painted over toolbar", Color.Green.toArgb(), image.getPixel(160, 20))
            assertEquals("PDF painted below viewport", Color.Blue.toArgb(), image.getPixel(160, 380))
            assertEquals("Visible PDF content should still render", Color.Red.toArgb(), image.getPixel(160, 200))
        }
        assertChrome()
        compose.onNodeWithTag("toolbar").performClick()
        assertEquals(1, clicks)
        compose.runOnIdle { placement = page(-900f, 1500f) }
        assertChrome()
    }

    @Test fun paperAndSelectionAreClippedWhenViewportMovesForFullscreenOrPanels() {
        var top by mutableStateOf(100)
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            Column(Modifier.requiredSize(320.dp, 400.dp).background(Color.Green).testTag("screen")) {
                Spacer(Modifier.height(top.dp))
                DocumentViewport(Modifier.width(240.dp).height(200.dp)) {
                    DocumentBackground(Paper(color = PaperColor.Cream), null, { page(-100f, 500f) }, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) { drawRect(Color.Red, Offset(-300f, -300f), Size(1000f, 1000f)) }
                }
            }
        }
        fun assertBounds(y: Int) {
            val image = compose.captureRenderedView(renderView, "screen")
            if (y > 0) assertEquals(Color.Green.toArgb(), image.getPixel(100, y - 1))
            assertEquals(Color.Red.toArgb(), image.getPixel(100, y + 10))
            assertEquals(Color.Green.toArgb(), image.getPixel(280, y + 10))
            assertEquals(Color.Green.toArgb(), image.getPixel(100, y + 205))
        }
        assertBounds(100)
        compose.runOnIdle { top = 0 }
        assertBounds(0)
    }

    @Test fun pageImagesGetExactPagePositionsAndOneEndOfFramePerDraw() {
        val drawn = ArrayList<PagePlacement>()
        var frames = 0
        val images = object : PageImages {
            override fun drawPage(canvas: android.graphics.Canvas, p: PagePlacement) { drawn += p }
            override fun endFrame(placement: PagePlacement) { frames++ }
        }
        val first = PagePlacement(3, 12.5f, -40.25f, 280.5f, 392.7f, 1.5f, 320, 300, false)
        val second = first.copy(page = 4, top = first.top + first.height + 24f)
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            DocumentViewport(Modifier.requiredSize(320.dp, 300.dp).testTag("doc")) {
                DocumentBackground(Paper(), images, { first.copy(visiblePages = listOf(first, second)) }, Modifier.fillMaxSize())
            }
        }
        compose.captureRenderedView(renderView, "doc")
        assertTrue(frames >= 1)
        assertEquals(listOf(3, 4), drawn.take(2).map { it.page })
        assertEquals(12.5f, drawn[0].left, 0f)
        assertEquals(-40.25f, drawn[0].top, 0f)
        assertEquals(second.top, drawn[1].top, 0f)
    }

    @Test fun endlessSheetIsPaperEdgeToEdgeWithItsPatternAnchoredToTheSheet() {
        // A 16-screen sheet of 200 px screens: paper lines every 8 px, counted from its corner.
        var sheet by mutableStateOf(PagePlacement(0, -1500f, -797f, 3200f, 3200f, 1f, 320, 300, true))
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            DocumentViewport(Modifier.requiredSize(320.dp, 300.dp).testTag("doc")) {
                DocumentBackground(Paper(template = app.noti.data.PaperTemplate.Lined, color = PaperColor.Cream), null,
                    { sheet.copy(visiblePages = listOf(sheet)) }, Modifier.fillMaxSize(), sheet = true)
            }
        }
        fun rows(image: Bitmap) = (0 until 300).filter { image.getPixel(160, it) != PaperColor.Cream.argb.toInt() }
        val image = compose.captureRenderedView(renderView, "doc")
        val lines = rows(image)
        // No page card: paper right up to every edge, no shadow or border.
        val top = (0 until 300).first { it !in lines }
        val bottom = (0 until 300).last { it !in lines }
        listOf(0 to top, 319 to top, 0 to bottom, 319 to bottom).forEach { (x, y) ->
            assertEquals("paper at $x,$y", PaperColor.Cream.argb.toInt(), image.getPixel(x, y))
        }
        assertTrue(bottom >= 296)
        assertTrue("ruled lines are drawn", lines.size > 20)
        // The sheet's top is 797 px above the view: lines at 797 + 3 = 800 = 100 × 8 px, so one sits at y = 3.
        assertTrue(lines.first() in 2..4)
        // Moving the sheet moves its lines with it.
        compose.runOnIdle { sheet = sheet.copy(top = sheet.top + 5f) }
        val moved = rows(compose.captureRenderedView(renderView, "doc"))
        assertTrue(moved.first() in 7..9 || moved.first() in 0..1)
        // Past the sheet's side edge there is desk, not paper.
        compose.runOnIdle { sheet = sheet.copy(left = 100f) }
        val edge = compose.captureRenderedView(renderView, "doc")
        assertNotEquals(PaperColor.Cream.argb.toInt(), edge.getPixel(50, 150))
        assertEquals(PaperColor.Cream.argb.toInt(), edge.getPixel(150, (0 until 300).first { it !in moved }))
    }

    private fun page(top: Float, width: Float): PagePlacement {
        val p = PagePlacement(0, -100f, top, width, width * 1.4f, 2f, 320, 300, true)
        return p.copy(visiblePages = listOf(p))
    }
}
