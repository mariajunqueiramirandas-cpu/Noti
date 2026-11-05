package app.noti.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
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
import app.noti.R
import app.noti.data.*
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
@Config(sdk = [35], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: View
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun tabletLibraryShowsBrandAndOpensNotebooks() {
        val library = libraryWithNotes()
        var opened: String? = null
        render { LibraryScreen(library, SettingsStore(context), onOpen = { opened = it }, onSettings = {}) }
        compose.onNodeWithText("noti.").assertIsDisplayed()
        compose.onAllNodesWithText("Little ideas welcome.").assertCountEquals(2)
        compose.onNodeWithText("Small bright things").assertIsDisplayed()
        screenshot("tablet-library-light")
        compose.onNodeWithText("Small bright things").performClick()
        compose.runOnIdle { assertEquals(library.notebooks.first { it.name == "Small bright things" }.id, opened) }
    }

    @Test fun darkTabletLibraryRetainsWorkingFiltersAndSearch() {
        val library = libraryWithNotes()
        render(ThemeMode.Dark) { LibraryScreen(library, SettingsStore(context), onOpen = {}, onSettings = {}) }
        screenshot("tablet-library-dark")
        compose.onNodeWithText("Favorites", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Small bright things").assertIsDisplayed()
        compose.onNodeWithText("Sunday scribbles").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("missing")
        compose.onNodeWithText("Nothing found").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Small bright things").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun emptyLibraryIntroducesDotiAndCreatesFirstNotebook() {
        val library = emptyLibrary()
        var opened: String? = null
        render { LibraryScreen(library, SettingsStore(context), onOpen = { opened = it }, onSettings = {}) }
        compose.onNodeWithText("A fresh start. A little possibility.").assertIsDisplayed()
        screenshot("phone-empty-library")
        compose.onNodeWithText("New notebook", substring = false).performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("My first little idea")
        dialogScreenshot("phone-new-notebook")
        compose.onNodeWithText("Create notebook").performClick()
        compose.runOnIdle { assertEquals("My first little idea", library.notebook(opened!!)?.name) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun brandedCreationDialogKeepsPaperAndActionAccessibleAtLargeText() {
        val library = emptyLibrary()
        render(fontScale = 1.5f) { LibraryScreen(library, SettingsStore(context), onOpen = {}, onSettings = {}) }
        compose.onNodeWithText("New notebook", substring = false).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("A tiny notebook")
        compose.onNodeWithText("Dots", substring = false).performScrollTo().performClick()
        dialogScreenshot("phone-new-notebook-large-text")
        compose.onNodeWithText("Create notebook").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(PaperTemplate.Dots, library.notebooks.single().paper.template) }
    }

    @Test fun tabletSettingsKeepPreviewAndControlsVisible() {
        render(inspection = true) { SettingsScreen(SettingsStore(context), onBack = {}) }
        compose.onNodeWithText("Make your mark.").assertIsDisplayed()
        val controls = compose.onNodeWithText("Pressure sensitivity").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithText("Try it here").fetchSemanticsNode().boundsInRoot
        assertTrue("Tablet preview stays beside the controls", preview.left > controls.right)
        screenshot("tablet-settings-pen")
        compose.onNodeWithText("Pages", substring = false).performClick()
        compose.onNodeWithText("Your kind of paper.").assertIsDisplayed()
        compose.onNodeWithText("Dots", substring = false).performClick()
        screenshot("tablet-settings-paper")
    }

    @Test fun aboutIntroducesMascotAndExposesBundledTypefaceLicense() {
        render(inspection = true) { SettingsScreen(SettingsStore(context), onBack = {}) }
        compose.onNodeWithText("About", substring = false).performClick()
        compose.onNodeWithContentDescription("Doti, Noti’s little ink companion").assertIsDisplayed()
        compose.onNodeWithText("Little ideas welcome.").assertIsDisplayed()
        screenshot("tablet-about")
        compose.onNodeWithText("Typeface license").performScrollTo().performClick()
        compose.onNodeWithText("Nunito", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Close", substring = false).performClick()
        compose.onNodeWithText("Meet Doti", substring = false).assertIsDisplayed()
    }

    @Test fun installedLauncherResourceHasAdaptiveAndMonochromeArtwork() {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 256, 256)
        icon.draw(Canvas(bitmap))
        save(bitmap, "app-icon")
    }

    private fun emptyLibrary(): LibraryStore {
        File(context.filesDir, "library.json").delete()
        return LibraryStore(context)
    }

    private fun libraryWithNotes(): LibraryStore = emptyLibrary().also { library ->
        val names = listOf("Small bright things", "A little daydream", "Things to remember", "Sunday scribbles")
        names.forEachIndexed { i, name ->
            val notebook = library.create(name, Paper(PaperTemplate.entries[i], if (i == 1) PaperColor.Cream else PaperColor.White), null)
            if (i == 0) library.setFavorite(setOf(notebook.id), true)
        }
        library.createFolder("Personal")
        library.createFolder("A little learning")
    }

    private fun render(theme: ThemeMode = ThemeMode.Light, fontScale: Float = 1f, inspection: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            CompositionLocalProvider(LocalInspectionMode provides inspection) {
                NotiTheme(theme) { Box(Modifier.fillMaxSize().testTag("brand-screen")) { content() } }
            }
        }
    }

    private fun dialogScreenshot(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(dialog.width, dialog.height, Bitmap.Config.ARGB_8888)
            dialog.draw(Canvas(bitmap))
            save(bitmap, name)
        }
    }

    private fun screenshot(name: String) = save(compose.captureRenderedView(view, "brand-screen"), name)
    private fun save(bitmap: Bitmap, name: String) {
        val directory = File("build/reports/brand-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
