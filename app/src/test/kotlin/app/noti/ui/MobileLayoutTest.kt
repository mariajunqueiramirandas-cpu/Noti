package app.noti.ui

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import app.noti.ui.common.PaperPicker
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.noti.data.LibraryStore
import app.noti.data.Paper
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.ui.library.LibraryScreen
import app.noti.ui.settings.SettingsScreen
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
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: View
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun phoneLibraryNavigationSearchAndSelectionStayUsable() {
        File(context.filesDir, "library.json").delete()
        val library = LibraryStore(context)
        library.create("Meeting notes", Paper(), null)
        library.create("Shopping list", Paper(), null)
        render { LibraryScreen(library, SettingsStore(context), onOpen = {}, onSettings = {}) }
        val search = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        assertTrue("Search should span the phone", search.width >= root.width - 40f)
        assertInside(compose.onNodeWithContentDescription("Open navigation"))
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Favorites", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No favorites yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("All", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Meeting")
        compose.onNodeWithText("Meeting notes").assertIsDisplayed()
        compose.onNodeWithText("Shopping list").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        screenshot("phone-library")
        compose.onNodeWithText("Meeting notes").performTouchInput { longClick() }
        assertInside(compose.onNodeWithContentDescription("Clear selection"))
        assertInside(compose.onNodeWithContentDescription("Selection actions"))
        compose.onNodeWithContentDescription("Selection actions").performClick()
        listOf("Favorite", "Move", "Rename", "Duplicate", "Export", "Delete").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Favorite", substring = false).performClick()
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.runOnIdle { assertTrue(library.notebooks.first { it.name == "Meeting notes" }.favorite) }
    }

    @Test
    @Config(qualifiers = "w423dp-h919dp-xxhdpi")
    fun pocoX8ProScreenAtThreeTimesDensityUsesPhoneLibraryLayout() {
        // 1269 × 2757 px is the 1268 × 2756 display rounded to whole dp at 3× density.
        File(context.filesDir, "library.json").delete()
        val library = LibraryStore(context)
        repeat(4) { library.create("Notebook ${it + 1}", Paper(), null) }
        render { LibraryScreen(library, SettingsStore(context), onOpen = {}, onSettings = {}) }
        assertInside(compose.onNodeWithContentDescription("Open navigation"))
        val root = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val search = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Search must span the phone at its native density", search.width > root.width * 0.9f)
        screenshot("poco-x8-pro-library")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun paperChoicesWrapAtDialogWidthWithLargeText() {
        val paper = mutableStateOf(Paper())
        render(fontScale = 1.5f) {
            Column(Modifier.width(248.dp).height(360.dp).verticalScroll(rememberScrollState()).testTag("picker")) {
                PaperPicker(paper.value, onChange = { paper.value = it })
            }
        }
        val picker = compose.onNodeWithTag("picker").fetchSemanticsNode().boundsInRoot
        listOf("Blank", "Lined", "Grid", "Dots", "White", "Cream", "Dark", "A4", "Letter", "Endless").forEach {
            val bounds = compose.onNodeWithText(it).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$it extends beyond the picker: $bounds / $picker", bounds.left >= picker.left && bounds.right <= picker.right)
        }
        compose.onNodeWithText("Grid", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Cream").performScrollTo().performClick()
        compose.onNodeWithText("Letter").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Grid", paper.value.template.label)
            assertEquals("Cream", paper.value.color.label)
            assertEquals("Letter", paper.value.size.label)
        }
        screenshot("phone-paper-picker-large-text")
    }

    @Test fun phoneSettingsStackCanvasAndExposeEverySection() {
        render(inspection = true) { SettingsScreen(SettingsStore(context), onBack = {}) }
        val pressure = compose.onNodeWithText("Pressure sensitivity").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithText("Try it here").fetchSemanticsNode().boundsInRoot
        assertTrue("Phone preview must be below settings", preview.top > pressure.bottom)
        assertInside(compose.onNodeWithText("Clear", substring = false))
        compose.onNodeWithText("Prediction", substring = false).performScrollTo().assertIsDisplayed()
        screenshot("phone-pen-settings")
        chooseSection("Writing")
        compose.onNodeWithText("Draw with finger").assertIsDisplayed()
        chooseSection("Pages")
        compose.onNodeWithText("Dots").assertIsDisplayed()
        chooseSection("Appearance")
        compose.onNodeWithText("Theme", substring = false).assertIsDisplayed()
        chooseSection("About")
        compose.onNodeWithText("Noti", substring = false).assertIsDisplayed()
        chooseSection("Palm rejection")
        compose.onNodeWithText("Record a test session").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Deliberate touches", substring = false) and hasClickAction()).performScrollTo().performClick()
        assertInside(compose.onNodeWithText("Clear", substring = false))
        screenshot("phone-palm-settings")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-mdpi")
    fun landscapeSettingsKeepScrollableControlsAndCanvasVisible() {
        render(fontScale = 1.5f, inspection = true) { SettingsScreen(SettingsStore(context), onBack = {}) }
        // Short landscape windows use the phone section menu and put the canvas beside the controls.
        compose.onNodeWithText("Pen buttons").performScrollTo().assertIsDisplayed()
        assertInside(compose.onNodeWithText("Clear", substring = false))
        chooseSection("Pages")
        compose.onNodeWithText("Endless", substring = false).performScrollTo().performClick()
        screenshot("phone-landscape-pages")
    }

    private fun chooseSection(label: String) {
        compose.onNodeWithContentDescription("Choose settings section").performClick()
        compose.onNodeWithText(label, substring = false).performClick()
    }

    private fun render(fontScale: Float = 1f, inspection: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            CompositionLocalProvider(LocalInspectionMode provides inspection) {
                NotiTheme(ThemeMode.Light) {
                    Box(Modifier.fillMaxSize().testTag("screen")) { content() }
                }
            }
        }
    }

    private fun assertInside(node: SemanticsNodeInteraction) {
        val b = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        assertTrue("Control lies outside the screen: $b / $root", b.left >= root.left && b.right <= root.right && b.top >= root.top && b.bottom <= root.bottom)
    }

    private fun screenshot(name: String) {
        val directory = File("build/reports/mobile-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.captureRenderedView(view, "screen").compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
