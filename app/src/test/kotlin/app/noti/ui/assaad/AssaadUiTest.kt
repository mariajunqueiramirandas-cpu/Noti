package app.noti.ui.assaad

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import app.noti.R
import app.noti.assaad.AssaadSession
import app.noti.assaad.assaadItemId
import app.noti.data.*
import app.noti.study.*
import app.noti.ui.study.SheetGeometry
import app.noti.ui.study.StudyController
import app.noti.ui.study.StudySheet
import app.noti.ui.captureRenderedView
import app.noti.ui.theme.NotiTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssaadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = MainScope()
    private lateinit var session: AssaadSession
    private lateinit var view: View
    @After fun close() { if (::session.isInitialized) session.dispose(); scope.cancel() }

    @Test fun libraryShowsLiveSessionAndOpensPairing() {
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        context.getSharedPreferences("platform_preferences", 0).edit().clear().commit()
        session = AssaadSession(context, LibraryStore(context), scope)
        session.receive("""{"type":"session","payload":{"paired":true,"peerOnline":true,"presence":{"active":true,"path":"/"}}}""")
        compose.setContent {
            SideEffect { view = compose.activity.window.decorView }
            NotiTheme(ThemeMode.Light) { CompositionLocalProvider(LocalAssaadSession provides session) {
                Box(Modifier.fillMaxSize().testTag("platform")) { AssaadLibraryEntry() }
                AssaadPairingDialog(session) {}
            } }
        }
        compose.onNodeWithText("Active in your browser", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Plataforma Assaad").performClick()
        compose.onNodeWithText("Open canvas").assertIsDisplayed()
        compose.onNodeWithText("Assaad is active in your browser.").assertIsDisplayed()
    }

    private lateinit var study: StudyController

    /** The Assaad board with its study sheet, inline, and the bridge that puts the saved questions on it. */
    private fun board(w: Int, h: Int) {
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        context.getSharedPreferences("platform_preferences", 0).edit().clear().commit()
        java.io.File(context.filesDir, "assaad_session.json").delete()
        val library = LibraryStore(context)
        session = AssaadSession(context, library, scope) { true }
        val id = session.openBoard()
        val notebook = library.notebook(id)!!
        val file = library.studyFile(notebook).apply { parentFile?.mkdirs(); delete() }
        val store = StudyStore.load(file)
        study = StudyController(notebook, library, store, {})
        val res = context.resources
        val fonts = CardFace.Fonts(res.getFont(R.font.nunito_regular), res.getFont(R.font.nunito_semibold), res.getFont(R.font.nunito_bold))
        compose.setContent {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            NotiTheme(ThemeMode.Light) { CompositionLocalProvider(LocalAssaadSession provides session) {
                BoxWithConstraints(Modifier.size(w.dp, h.dp).testTag("platform")) {
                    AssaadStudyBridge(id, study)
                    StudySheet(study, SheetGeometry(), fonts, { null }, 0L, maxWidth, maxHeight, {}, floating = false)
                }
            } }
        }
        compose.waitUntil(5_000) { compose.runOnIdle { session.libraryLoaded } }
        session.receive(JSONObject().put("type", "questions").put("payload", JSONObject(javaClass.getResource("/assaad-batch.json")!!.readText())).toString())
    }
    private fun choose(letter: Char) = compose.onAllNodes(SemanticsMatcher("click label Choose $letter") {
        androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config && it.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label == "Choose $letter"
    }).onFirst().performClick()

    @Test
    @Config(qualifiers = "w1280dp-h900dp-mdpi")
    fun assaadQuestionsUseTheStudySheetAndAssaadChecksTheAnswer() {
        board(1280, 900)
        // A new set opens the ordinary study sheet on its first question.
        compose.waitUntil(5_000) { compose.runOnIdle { study.open && study.current?.id == assaadItemId("q-1") } }
        compose.onNodeWithContentDescription("Study sheet. Drag to move.").assertIsDisplayed()
        compose.onNodeWithText("Pick an option").assertIsNotEnabled()
        choose('A')
        compose.runOnIdle { assertEquals("a-1", session.draftFor("q-1")); assertEquals(-1, study.data.progressOf(assaadItemId("q-1")).chosen) }
        // Not connected: nothing queues up to go later.
        compose.onNodeWithText("Connect Assaad to check").assertIsDisplayed()
        compose.runOnIdle {
            session.start()
            session.receive("""{"type":"session","payload":{"paired":true,"peerOnline":true,"presence":{"active":true,"path":"/"}}}""")
        }
        screenshot("assaad-sheet-picked")
        compose.onNodeWithText("Check with Assaad").assertIsEnabled().performClick()
        compose.onNodeWithText("Checking with Assaad…").assertIsDisplayed()
        compose.onNodeWithText("Checking…").assertIsNotEnabled()
        // Assaad's answer comes back: the card is checked like any other.
        val command = compose.runOnIdle { session.answerFor("q-1")!!.commandId }
        session.receive("""{"type":"answer-result","payload":{"commandId":"$command","status":"confirmed","correct":false,"correctAlternativeId":"a-2","feedback":"Resposta incorreta."}}""")
        compose.waitUntil(5_000) { compose.runOnIdle { study.data.progressOf(assaadItemId("q-1")).chosen == 0 } }
        compose.runOnIdle {
            val item = study.data.item(assaadItemId("q-1"))!!
            assertEquals(1, item.correctChoice)
            assertEquals(Verdict.Incorrect, study.data.progressOf(item.id).outcome(item))
            assertNotNull("a checked answer is scheduled for review", study.data.progressOf(item.id).srs)
        }
        screenshot("assaad-sheet-checked")
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun phoneBoardShowsAssaadQuestionsInTheDockedSheet() {
        board(412, 915)
        compose.waitUntil(5_000) { compose.runOnIdle { study.open } }
        compose.onNodeWithText("Pick an option").assertIsDisplayed()
        screenshot("assaad-sheet-phone")
    }

    @Test fun tabletBrowserOpensAboveEverythingAndGoesToTheCanvas() {
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        context.getSharedPreferences("platform_preferences", 0).edit().clear().commit()
        session = AssaadSession(context, LibraryStore(context), scope)
        session.openTabletBrowser()
        var canvasOpened = false
        compose.setContent {
            NotiTheme(ThemeMode.Light) {
                androidx.compose.material3.TextButton(onClick = { session.openTabletBrowser() }) { androidx.compose.material3.Text("Reopen tablet browser") }
                AssaadTabletBrowserHost(session) { canvasOpened = true }
            }
        }
        compose.onNodeWithText("Opening Assaad").assertIsDisplayed()
        compose.onNodeWithText("Canvas").performClick()
        compose.runOnIdle {
            assertTrue(canvasOpened); assertTrue(session.useTabletBrowser); assertFalse(session.browserOpen)
            assertEquals("the board opens on what's next", "", session.focus)
        }
        compose.onNodeWithText("Opening Assaad").assertDoesNotExist()
        compose.onNodeWithText("Reopen tablet browser").performClick()
        compose.onNodeWithText("Opening Assaad").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close Assaad").performClick()
        compose.runOnIdle { assertFalse(session.browserOpen) }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun browserScreenOffersOneNextStepForEachState() {
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        session = AssaadSession(context, LibraryStore(context), scope)
        compose.setContent {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            NotiTheme(ThemeMode.Light) { Box(Modifier.fillMaxSize().testTag("platform")) { AssaadBrowserScreen(session, null, {}, {}) } }
        }
        compose.onNodeWithText("Opening Assaad").assertIsDisplayed()
        compose.onNodeWithText("Plataforma Assaad").assertIsDisplayed()
        screenshot("assaad-browser-loading")
    }

    private fun screenshot(name: String) {
        val bitmap = compose.captureRenderedView(view, "platform")
        val dir = File("build/reports/assaad-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
