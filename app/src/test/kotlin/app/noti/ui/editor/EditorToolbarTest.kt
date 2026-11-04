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

    @Test fun portraitTabletKeepsFloatingControlsVisibleInDarkMode() { render(800, ThemeMode.Dark); assertControls(); screenshot("portrait-dark") }
    @Test fun landscapeTabletKeepsFloatingControlsVisibleInLightMode() { render(1200, ThemeMode.Light); assertControls(); screenshot("landscape-light") }
    @Test fun narrowWindowKeepsFloatingControlsApart() { render(360, ThemeMode.Dark); assertControls(); screenshot("narrow-dark") }
    @Test fun largeFontsKeepControlsInsideWindow() { render(800, ThemeMode.Light, 2f); assertControls(); screenshot("large-font") }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun phoneWithLargeTextKeepsEditorActionsApart() { render(412, ThemeMode.Light, 1.5f); assertControls(); screenshot("phone-large-text") }

    @Test
    @Config(qualifiers = "w915dp-h412dp-mdpi")
    fun shortLandscapeToolsStayClearOfHeaderAndNavigation() {
        render(915, ThemeMode.Light, height = 412)
        assertControls()
        compose.onNodeWithContentDescription("Open tools").performClick()
        val root = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        val obstacles = listOf("Back to library", "More", "Undo", "Redo", "Pages", "Zoom 175 percent. Reset to fit width")
            .map { it to compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        listOf("Pen", "Highlighter", "Eraser", "Lasso", "Brush options", "Close tools").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label extends outside the screen: $bounds", bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
            obstacles.forEach { (name, obstacle) -> assertFalse("$label overlaps $name", bounds.overlaps(obstacle)) }
        }
        screenshot("phone-landscape-tools")
    }

    @Test fun radialSelectionKeepsPresetsBehindBrushOptions() {
        render(800, ThemeMode.Dark)
        compose.onNodeWithContentDescription("Pen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open tools").performClick()
        compose.onNodeWithContentDescription("Color 2").assertDoesNotExist()
        screenshot("radial-dark")
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithContentDescription("Color 2").performClick()
        compose.onNodeWithContentDescription("Width 9.0").performClick()
        compose.runOnIdle { assertEquals(1, settings.penSlots.color); assertEquals(2, settings.penSlots.width) }
        compose.onNodeWithText("Pressure sensitivity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close tools").performClick()
        compose.onNodeWithContentDescription("Open tools").performClick()
        compose.onNodeWithContentDescription("Highlighter").performClick()
        compose.onNodeWithContentDescription("Highlighter").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open tools").performClick()
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithContentDescription("Width 40.0").performClick()
        compose.runOnIdle { assertEquals(Tool.Highlighter, settings.tool); assertEquals(2, settings.highlighterSlots.width) }
        compose.onNodeWithText("Pressure sensitivity").assertDoesNotExist()
    }

    @Test fun fanAnimationKeepsLauncherFixedAndFinishesQuickly() {
        render(800, ThemeMode.Light)
        val before = compose.onNodeWithContentDescription("Open tools").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Open tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        val during = compose.onNodeWithContentDescription("Close tools").fetchSemanticsNode().boundsInRoot
        assertEquals(before, during)
        compose.mainClock.advanceTimeBy(112)
        listOf("Pen", "Highlighter", "Eraser", "Lasso").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Close tools").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(208)
        assertEquals(before, compose.onNodeWithContentDescription("Open tools").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Pen").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun narrowFanStaysClearOfNavigationAndOtherTools() {
        render(360, ThemeMode.Light)
        compose.onNodeWithContentDescription("Open tools").performClick()
        val labels = listOf("Pen", "Highlighter", "Eraser", "Lasso", "Brush options", "Close tools", "Pages",
            "Zoom 175 percent. Reset to fit width")
        val boxes = labels.map { it to compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        for ((i, a) in boxes.withIndex()) for (b in boxes.drop(i + 1)) {
            assertFalse("Controls overlap: ${a.first} and ${b.first}", a.second.overlaps(b.second))
        }
        screenshot("radial-narrow")
    }

    @Test fun overflowKeepsSaveStatusAndPageActionsAccessible() {
        render(360, ThemeMode.Light)
        compose.onNodeWithText("Autosave on").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Autosave on").assertIsDisplayed()
        compose.onNodeWithText("Next page").performClick()
        compose.runOnIdle { assertEquals(3, requestedPage) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Previous page").performClick()
        compose.runOnIdle { assertEquals(1, requestedPage) }
    }

    @Test fun saveFailureExposesRetryWithoutOpeningMenu() {
        render(360, ThemeMode.Light, saveStatus = "Save failed · tap to retry")
        compose.onNodeWithContentDescription("Save failed · tap to retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun brushOptionsFitShortNarrowWindowWithLargeFonts() {
        render(360, ThemeMode.Light, 1.5f)
        compose.onNodeWithContentDescription("Open tools").performClick()
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithText("Width").performScrollTo().assertIsDisplayed()
        val popup = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
        assertTrue("Brush menu is wider than the window: $popup", popup.width <= 360f)
        assertTrue("Brush menu is taller than the window: $popup", popup.height <= 640f)
    }

    @Test fun radialLauncherCanBeMovedAndAllToolsStayInsideCanvas() {
        render(800, ThemeMode.Light)
        val before = compose.onNodeWithContentDescription("Open tools").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Open tools").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-220f, -150f), 600) }
        val after = compose.onNodeWithContentDescription("Open tools").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left < before.left - 100f)
        assertTrue(after.top < before.top - 70f)
        compose.onNodeWithContentDescription("Open tools").performClick()
        listOf("Pen", "Highlighter", "Eraser", "Lasso", "Close tools").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    @Test fun realPopupIslandsReceiveActionsAndExposeLiveIndicators() {
        render(800, ThemeMode.Light, floating = true)
        compose.onNodeWithContentDescription("Open tools").performClick()
        compose.onNodeWithContentDescription("Lasso").performClick()
        compose.runOnIdle { assertEquals(Tool.Lasso, settings.tool) }
        compose.onNodeWithContentDescription("Zoom 175 percent. Reset to fit width").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Document scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle { assertEquals(1, resets); assertEquals(0.75f, scrolled, 0.001f) }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.runOnIdle { assertEquals(1, undos); assertEquals(1, redos) }
    }

    private var resets = 0
    private var scrolled = 0f
    private var requestedPage = -1
    private var saves = 0

    private fun render(width: Int, theme: ThemeMode, fontScale: Float = 1f, floating: Boolean = false, saveStatus: String = "Autosave on", height: Int = 800) {
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
                    Box(Modifier.requiredWidth(width.dp).height(height.dp).background(Color.Gray).testTag("editor")) {
                        DocumentViewport(Modifier.fillMaxSize()) {
                            DocumentBackground(Paper(), images, page.copy(visiblePages = listOf(page)), Modifier.fillMaxSize())
                        }
                        Box(Modifier.fillMaxSize().testTag("toolbar")) {
                            EditorToolbar(
                                title = "Ideas for a quieter day", settings = settings, pagesOpen = false,
                                onBack = {}, onRename = {}, onTogglePages = {}, onPagePaper = {}, onFullScreen = {}, onSettings = {},
                                pdfPage = 2 to 30, onPage = { requestedPage = it }, canUndo = true, canRedo = true, zoom = 1.75f,
                                saveStatus = saveStatus, onSave = { saves++ },
                                scrollFraction = 0.3f, viewportFraction = 0.15f, onResetZoom = { resets++ }, onScroll = { scrolled = it },
                                onUndo = { undos++ }, onRedo = { redos++ }, floatingWindows = floating,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertControls() {
        val toolbar = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        val labels = listOf("Undo", "Redo", "Open tools", "Back to library", "Pages", "More",
            "Document scroll", "Zoom 175 percent. Reset to fit width")
        val boxes = labels.map { label ->
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
            val b = node.boundsInRoot
            assertTrue("$label extends past the left edge: $b", b.left >= toolbar.left - 0.5f)
            assertTrue("$label extends past the right edge: $b", b.right <= toolbar.right + 0.5f)
            assertTrue("$label extends past the top edge: $b", b.top >= toolbar.top - 0.5f)
            assertTrue("$label extends past the bottom edge: $b", b.bottom <= toolbar.bottom + 0.5f)
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
