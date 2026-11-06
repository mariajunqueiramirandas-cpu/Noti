package app.noti.ui.editor

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.noti.data.ThemeMode
import app.noti.transcribe.InkLine
import app.noti.transcribe.SelectedStroke
import app.noti.ui.theme.NotiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscribePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var copies = 0
    private var replaces = 0
    private var closes = 0

    private fun session() = TranscribeSession(0,
        listOf(InkLine(listOf(SelectedStroke(0, 0, floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(10f, 10f, 80f, 30f))))),
        intArrayOf(0), floatArrayOf(0f, 0f, 0f, 1f))

    private fun show(session: TranscribeSession, theme: ThemeMode = ThemeMode.Light) {
        compose.setContent {
            NotiTheme(theme) {
                TranscribePanel(session, onClose = { closes++ }, onCopy = { copies++ }, onReplace = { replaces++ })
            }
        }
    }

    @Test fun readTextCanBeFixedCopiedAndReplaced() {
        val session = session().apply { text = "Buy oat milk\ncall Ana at 5"; phase = TranscribeSession.Phase.Done }
        show(session)
        compose.onNodeWithText("Handwriting to text").assertIsDisplayed()
        compose.onNodeWithText("Buy oat milk\ncall Ana at 5").assertIsDisplayed()
        screenshot("panel-done-light")
        compose.onNode(hasSetTextAction()).performTextReplacement("Buy oat milk\ncall Ana at 6")
        compose.runOnIdle { assertEquals("Buy oat milk\ncall Ana at 6", session.text) }
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Replace on page").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, copies); assertEquals(1, replaces); assertEquals(1, closes) }
    }

    @Test fun nothingToCopyOrReplaceUntilThereIsText() {
        val session = session().apply { phase = TranscribeSession.Phase.Done }
        show(session, ThemeMode.Dark)
        compose.onNodeWithText("Replace on page").assertIsNotEnabled()
        compose.onNodeWithText("Copy").assertIsNotEnabled()
        screenshot("panel-empty-dark")
        compose.onNode(hasSetTextAction()).performTextInput("typed by hand")
        compose.onNodeWithText("Replace on page").assertIsEnabled()
        // While replacing, a second tap can't start another replacement.
        compose.runOnIdle { session.replacing = true }
        compose.onNodeWithText("Replace on page").assertIsNotEnabled()
    }

    @Test fun downloadingAndFailureExplainThemselves() {
        val session = session().apply { phase = TranscribeSession.Phase.Downloading("Português (Brasil)") }
        show(session)
        compose.onNodeWithText("Getting Português (Brasil) handwriting ready", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Replace on page").assertIsNotEnabled()
        screenshot("panel-downloading-light")
        compose.runOnIdle { session.phase = TranscribeSession.Phase.Failed("Couldn't download Português (Brasil) handwriting. Check your connection and try again.") }
        compose.onNodeWithText("Check your connection", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    /** The dialog's own window, drawn on its own. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(android.graphics.Canvas(bitmap))
            File(File("build/reports/transcribe").apply { mkdirs() }, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
