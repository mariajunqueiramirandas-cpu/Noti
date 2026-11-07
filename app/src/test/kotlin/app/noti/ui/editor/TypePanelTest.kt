package app.noti.ui.editor

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.noti.R
import app.noti.data.QuickFill
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.lines.LineLayout
import app.noti.lines.Overflow
import app.noti.lines.TextAlign
import app.noti.lines.TypeStyle
import app.noti.lines.WriteLine
import app.noti.pdf.PagePlacement
import app.noti.transcribe.TextInk
import app.noti.ui.captureRenderedView
import app.noti.ui.theme.NotiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var settings: SettingsStore
    private var done = 0; private var cancels = 0; private var fallbacks = 0
    private val fields = ArrayList<Int>()
    private val mmPerUnit = 0.21f
    private val ink = TextInk(app.resources.getFont(R.font.nunito_regular))

    @Before fun setUp() {
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = SettingsStore(app)
    }

    private fun answerLines() = List(3) { WriteLine(60f, 640f, 190f + it * 48f, room = 46f) }

    private fun session(lines: List<WriteLine> = answerLines(), text: String = "", form: Boolean = false,
        fallback: WriteLine? = null): TypeSession {
        val fs = if (form) listOf(
            TypeField(listOf(WriteLine(160f, 640f, 110f, room = 60f)), setOf(0), "", label = "Name"),
            TypeField(lines, lines.indices.toSet(), ""))
        else listOf(TypeField(lines, lines.indices.filter { lines[it].sure }.toSet(), text))
        return TypeSession(0, fs, mmPerUnit, form, floatArrayOf(0.12f, 0.12f, 0.14f, 1f), TypeStyle(), fallback)
    }

    /** The preview layout, as TypeTool keeps it. */
    private fun relayout(s: TypeSession) {
        val f = s.field
        s.laid = if (f.lines.isEmpty()) null else LineLayout(ink.measure(), mmPerUnit).layout(f.lines, f.text.text, s.style)
    }

    private fun show(s: TypeSession, theme: ThemeMode = ThemeMode.Light) {
        relayout(s)
        compose.setContent {
            NotiTheme(theme) {
                Surface(Modifier.width(620.dp).testTag("panel"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    TypePanelContent(s, settings, onDone = { done++ }, onCancel = { cancels++ }, onField = { fields += it },
                        onUseFallback = { fallbacks++ }, autoFocus = false)
                }
            }
        }
    }

    @Test fun typingFillsTheFieldAndPlaceOrCancelReport() {
        val s = session()
        show(s)
        compose.onNodeWithText("Type on lines").assertIsDisplayed()
        compose.onNodeWithText("3 lines · tap a line to leave it out").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Heat flows to the colder air")
        compose.runOnIdle { assertEquals("Heat flows to the colder air", s.field.text.text) }
        screenshot("panel-light")
        compose.onNodeWithText("Place").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, done); assertEquals(1, cancels) }
    }

    @Test fun tooMuchTextOffersShrinkOrKeepGoing() {
        val s = session(lines = answerLines().take(1), text = List(40) { "word" }.joinToString(" "))
        show(s, ThemeMode.Dark)
        compose.onNodeWithText("don't fit", substring = true).assertIsDisplayed()
        screenshot("panel-overflow-dark")
        compose.onNodeWithText("Shrink to fit").performClick()
        compose.runOnIdle { assertEquals(Overflow.Shrink, s.style.overflow) }
        compose.onNodeWithText("Shrinking to fit the lines.").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Keep going").performClick()
        compose.runOnIdle { assertEquals(Overflow.KeepGoing, s.style.overflow) }
    }

    @Test fun styleChipsChangeTheStyle() {
        val s = session()
        show(s)
        compose.onNodeWithText("Fit", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Bigger text").performClick()
        compose.runOnIdle { assertNotNull(s.style.size) }
        compose.onNodeWithText("Centre").performClick()
        compose.onNodeWithText("Bold").performClick()
        compose.runOnIdle { assertEquals(TextAlign.Center, s.style.align); assertTrue(s.style.bold) }
        compose.onNodeWithText("Fit", substring = true).performClick()
        compose.runOnIdle { assertNull(s.style.size) }
    }

    @Test fun quickFillsAndTodayGoInAtTheCursor() {
        settings.quickFills = listOf(QuickFill("Name", "Ada Lovelace"))
        val s = session(form = true)
        show(s)
        compose.onNodeWithText("Field 1 of 2 · Name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Insert Name: Ada Lovelace").performClick()
        compose.runOnIdle { assertEquals("Ada Lovelace", s.field.text.text) }
        compose.onNodeWithContentDescription("Insert Date", substring = true).performClick()
        compose.runOnIdle { assertTrue(s.field.text.text.startsWith("Ada Lovelace ")) }
    }

    @Test fun formModeMovesWithTabAndArrows() {
        val s = session(form = true)
        show(s)
        compose.onNodeWithContentDescription("Next field").performClick()
        compose.onNode(hasSetTextAction()).performClick()   // focus, as the panel does on open
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        compose.onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle {
            assertEquals(listOf(1, 1, -1), fields)
            assertEquals(1, cancels)
        }
        compose.onNodeWithContentDescription("Previous field").assertIsNotEnabled()
    }

    @Test fun noLinesOffersALineAlongTheLoop() {
        val s = session(lines = emptyList(), fallback = WriteLine(10f, 300f, 200f, room = 40f))
        show(s)
        compose.onNodeWithText("No lines found there.").assertIsDisplayed()
        compose.onNodeWithText("Type here anyway").performClick()
        compose.runOnIdle { assertEquals(1, fallbacks) }
        screenshot("panel-no-lines-light")
    }

    /** The page as it looks while typing: lines found and numbered, text on them, the caret, a left-out line. */
    @Test fun theOverlayDrawsLinesTextAndCaret() {
        val lines = answerLines() + WriteLine(60f, 640f, 334f, room = 46f, sure = false)
        val s = session(lines = lines, text = "It loses heat to the colder air around it until both are at the same temperature")
        relayout(s)
        val state = TypeState().apply {
            session = s
            regular = app.resources.getFont(R.font.nunito_regular); bold = app.resources.getFont(R.font.nunito_bold)
        }
        val placement = PagePlacement(0, 20f, 10f, 700f, 990f, 1f, 740, 420, true).let { it.copy(visiblePages = listOf(it)) }
        compose.setContent {
            Canvas(Modifier.size(740.dp, 420.dp).testTag("scene")) {
                drawRect(Color(0xFFF1EEF4))
                drawRect(Color.White, Offset(placement.left, placement.top), androidx.compose.ui.geometry.Size(placement.width, placement.height))
                val k = placement.width / 1000f
                for (l in lines) drawLine(Color(0xFF8FB3D9), Offset(20f + l.x0 * k, 10f + l.y * k), Offset(20f + l.x1 * k, 10f + l.y * k), 1f)
                state.draw(this, placement)
            }
        }
        val laid = s.laid!!
        assertTrue(laid.fits)
        assertEquals(setOf(0, 1, 2), s.field.included)
        val bmp = compose.captureRenderedView(compose.activity.window.decorView, "scene")
        // The caret is plum; the preview text is the ink color, on the page.
        var plum = 0; var inkPx = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) in 80..100 && android.graphics.Color.blue(c) in 120..145 && android.graphics.Color.green(c) in 64..84) plum++
            if (android.graphics.Color.red(c) < 60 && android.graphics.Color.green(c) < 60) inkPx++
        }
        assertTrue("numbers and caret drawn", plum > 50)
        assertTrue("text drawn", inkPx > 200)
        save(bmp, "overlay-scene")
    }

    private fun screenshot(name: String) = save(compose.captureRenderedView(compose.activity.window.decorView, "panel"), name)

    private fun save(bitmap: Bitmap, name: String) {
        File(File("build/reports/type-on-lines").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
