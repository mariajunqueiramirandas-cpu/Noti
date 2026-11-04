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
        val images = DocumentImages(page = { _, _ -> bitmap })
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            Column(Modifier.requiredSize(320.dp, 400.dp).background(Color.Blue).testTag("screen")) {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Green).testTag("toolbar").clickable { clicks++ })
                DocumentViewport(Modifier.fillMaxWidth().height(300.dp)) {
                    DocumentBackground(Paper(), images, placement, Modifier.fillMaxSize())
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
                    DocumentBackground(Paper(color = PaperColor.Cream), null, page(-100f, 500f), Modifier.fillMaxSize())
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

    private fun page(top: Float, width: Float): PagePlacement {
        val p = PagePlacement(0, -100f, top, width, width * 1.4f, 2f, 320, 300, true)
        return p.copy(visiblePages = listOf(p))
    }
}
