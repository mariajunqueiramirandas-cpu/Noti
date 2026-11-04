package app.noti.ui.editor

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import app.noti.ui.captureRenderedView
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.noti.data.Paper
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.data.Tool
import app.noti.pdf.PagePlacement
import app.noti.ui.common.DocumentBackground
import app.noti.ui.common.DocumentImages
import app.noti.ui.common.DocumentViewport
import app.noti.ui.theme.NotiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var renderView: View
    private lateinit var settings: SettingsStore
    private var undos = 0
    private var redos = 0

    @Test fun portraitTabletKeepsEveryControlVisibleInDarkMode() { render(800, ThemeMode.Dark); assertControls(); screenshot("portrait-dark") }
    @Test fun landscapeTabletKeepsEveryControlVisibleInLightMode() { render(1200, ThemeMode.Light); assertControls(); screenshot("landscape-light") }
    @Test fun narrowWindowWrapsControlsInsteadOfHidingThem() { render(360, ThemeMode.Dark); assertControls(); screenshot("narrow-dark") }
    @Test fun largeFontsKeepControlsInsideWindow() { render(800, ThemeMode.Light, 2f); assertControls(); screenshot("large-font") }

    @Test fun brushOptionsAndPresetsRemainUsableInPortrait() {
        render(800, ThemeMode.Dark)
        compose.onNodeWithContentDescription("Color 2").performClick()
        compose.onNodeWithContentDescription("Width 9.0").performClick()
        compose.runOnIdle { assertEquals(1, settings.penSlots.color); assertEquals(2, settings.penSlots.width) }
        compose.onNodeWithContentDescription("Highlighter").performClick()
        compose.onNodeWithContentDescription("Width 40.0").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Tool.Highlighter, settings.tool); assertEquals(2, settings.highlighterSlots.width) }
        compose.onNodeWithContentDescription("Pen").performClick()
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithText("Pressure sensitivity").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun brushOptionsFitShortNarrowWindowWithLargeFonts() {
        render(360, ThemeMode.Light, 1.5f)
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithText("Width").performScrollTo().assertIsDisplayed()
        val popup = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
        assertTrue("Brush menu is wider than the window: $popup", popup.width <= 360f)
        assertTrue("Brush menu is taller than the window: $popup", popup.height <= 640f)
    }

    private fun render(width: Int, theme: ThemeMode, fontScale: Float = 1f) {
        settings = SettingsStore(RuntimeEnvironment.getApplication()).also {
            it.tool = Tool.Pen; it.penSlots = SettingsStore.DEFAULT_PEN; it.highlighterSlots = SettingsStore.DEFAULT_HIGHLIGHTER
        }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val images = DocumentImages(page = { _, _ -> bitmap })
        val page = PagePlacement(2, 16f, -160f, width - 32f, width * 1.4f, 1f, width, 600, true)
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                NotiTheme(theme) {
                    Column(Modifier.requiredWidth(width.dp).background(Color.Gray).testTag("editor")) {
                        Box(Modifier.testTag("toolbar")) {
                            EditorToolbar(
                                title = "SPRINT 02 - Argumentação Blindada", settings = settings, pagesOpen = false,
                                onBack = {}, onRename = {}, onTogglePages = {}, onPagePaper = {}, onFullScreen = {}, onSettings = {},
                                pdfPage = 2 to 30, canUndo = true, canRedo = true,
                                onUndo = { undos++ }, onRedo = { redos++ },
                            )
                        }
                        DocumentViewport(Modifier.fillMaxWidth().height(500.dp)) {
                            DocumentBackground(Paper(), images, page.copy(visiblePages = listOf(page)), Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private fun assertControls() {
        val toolbar = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        val labels = listOf("Undo", "Redo", "Pen", "Highlighter", "Eraser", "Lasso", "Color 1", "Color 2", "Color 3",
            "Width 3.0", "Width 5.0", "Width 9.0", "Brush options", "Back to library", "Previous page", "Next page", "Pages", "More")
        val boxes = labels.map { label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
            val b = node.boundsInRoot
            assertTrue("$label extends past the left edge: $b", b.left >= toolbar.left - 0.5f)
            assertTrue("$label extends past the right edge: $b", b.right <= toolbar.right + 0.5f)
            label to b
        }
        for ((i, a) in boxes.withIndex()) for (b in boxes.drop(i + 1)) {
            assertFalse("Controls overlap: ${a.first} and ${b.first}", a.second.overlaps(b.second))
        }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithContentDescription("Redo").performClick()
        assertEquals(1, undos); assertEquals(1, redos)
    }

    private fun screenshot(name: String) {
        val directory = File("build/reports/editor-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.captureRenderedView(renderView, "editor").compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
