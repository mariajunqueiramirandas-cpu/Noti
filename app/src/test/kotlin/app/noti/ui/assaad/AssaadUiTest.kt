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
import app.noti.assaad.AssaadSession
import app.noti.data.LibraryStore
import app.noti.data.ThemeMode
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
        session = AssaadSession(context, LibraryStore(context), scope)
        session.receive("""{"type":"session","payload":{"paired":true,"peerOnline":true,"presence":{"active":true,"path":"/"}}}""")
        compose.setContent {
            SideEffect { view = compose.activity.window.decorView }
            NotiTheme(ThemeMode.Light) { CompositionLocalProvider(LocalAssaadSession provides session) {
                Box(Modifier.fillMaxSize().testTag("platform")) { AssaadLibraryEntry() }
                AssaadPairingDialog(session) {}
            } }
        }
        compose.onNodeWithText("Active in this session").assertIsDisplayed()
        compose.onNodeWithText("Plataforma Assaad").performClick()
        compose.onNodeWithText("Open canvas").assertIsDisplayed()
        compose.onNodeWithText("Assaad is active in your browser.").assertIsDisplayed()
    }
    @Test fun phoneQuestionSheetHasExplicitConfirmationAndCanCollapse() {
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        session = AssaadSession(context, LibraryStore(context), scope)
        val id = session.openBoard()
        session.receive(JSONObject().put("type", "questions").put("payload", JSONObject(javaClass.getResource("/assaad-batch.json")!!.readText())).toString())
        compose.setContent {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            NotiTheme(ThemeMode.Light) { CompositionLocalProvider(LocalAssaadSession provides session) {
                Box(Modifier.fillMaxSize().testTag("platform")) { AssaadOverlay(id, null) { null } }
            } }
        }
        compose.onNodeWithText("Confirm in Assaad").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("A) Rio Nilo").performClick()
        compose.onNodeWithText("Draft saved. Confirm to submit when Edge is available.").assertIsDisplayed()
        compose.onNodeWithText("Confirm in Assaad").assertIsEnabled().performClick()
        compose.onNodeWithText("Queued for Assaad").assertIsDisplayed()
        compose.onNodeWithText("Saved sets").performClick()
        compose.onNodeWithText("1 in queue · waiting for Edge").assertIsDisplayed()
        compose.onNodeWithText("Cancel queued answers").performClick()
        compose.onNodeWithText("Submit saved answers (1)").assertIsEnabled()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Collapse Assaad questions").performClick()
        compose.onNodeWithContentDescription("Open Assaad questions").assertIsDisplayed()
    }
    @Test fun tabletBrowserCanMinimizeForCanvasAndReopenWithoutChangingMode() {
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        session = AssaadSession(context, LibraryStore(context), scope)
        session.useTablet(true)
        var canvasOpened = false
        compose.setContent {
            NotiTheme(ThemeMode.Light) {
                androidx.compose.material3.TextButton(onClick = { session.browserOpen = true }) { androidx.compose.material3.Text("Reopen tablet browser") }
                AssaadTabletBrowserHost(session) { canvasOpened = true }
            }
        }
        compose.onNodeWithText("Save questions").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Canvas").performClick()
        compose.runOnIdle { assertTrue(canvasOpened); assertTrue(session.useTabletBrowser); assertFalse(session.browserOpen) }
        compose.onNodeWithText("Save questions").assertDoesNotExist()
        compose.onNodeWithText("Reopen tablet browser").performClick()
        compose.onNodeWithText("Check connection").assertIsDisplayed()
    }

}
