package app.noti.ui.study

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.noti.R
import app.noti.data.*
import app.noti.pdf.PagePlacement
import app.noti.study.*
import app.noti.ui.captureRenderedView
import app.noti.ui.common.DocumentBackground
import app.noti.ui.theme.NotiTheme
import kotlinx.coroutines.MainScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyBoardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var view: View
    private lateinit var study: StudyController
    private lateinit var layer: BoardLayer
    private lateinit var fonts: CardFace.Fonts
    private val now = 1_800_000_000_000L

    private val items = listOf(
        StudyItem("q1", ItemKind.Choice, "Kinematics", "A ball is thrown straight up at $12\\,\\mathrm{m\\,s^{-1}}$. How high does it rise? Take \$g = 9.8\\,\\mathrm{m\\,s^{-2}}$.",
            listOf("$3.7\\,\\mathrm{m}$", "$7.3\\,\\mathrm{m}$", "$14.7\\,\\mathrm{m}$", "$1.2\\,\\mathrm{m}$"), 1,
            "\$h = \\dfrac{v_0^2}{2g} \\approx 7.3\\,\\mathrm{m}$", listOf("At the top \$v = 0$.", "\$v^2 = v_0^2 - 2gh$ gives \$h = \\frac{v_0^2}{2g}$."),
            listOf("What is the speed at the top?", "Use \$v^2 = v_0^2 - 2gh$.", "Set \$v = 0$ and solve for \$h$."), "src", listOf(12, 13),
            "Uniformly accelerated motion: \$v^2 = v_0^2 + 2a\\Delta x$.", 2, now),
        StudyItem("q2", ItemKind.Problem, "Energy", "A $2\\,\\mathrm{kg}$ block slides from rest down a frictionless ramp of height \$h = 1.5\\,\\mathrm{m}$. Find its speed at the bottom.",
            answer = "\$v = \\sqrt{2gh} \\approx 5.4\\,\\mathrm{m/s}$", solution = listOf("Energy is conserved: \$mgh = \\tfrac12 mv^2$.", "\$v = \\sqrt{2gh}$"),
            hints = listOf("No friction: what is conserved?", "\$mgh = \\frac{1}{2}mv^2$", "Solve for \$v$."), sourceId = "src", pages = listOf(20), createdAt = now),
        StudyItem("q3", ItemKind.Flash, "Dynamics", "Newton's second law", answer = "$\\vec F_{\\text{net}} = m\\vec a$", createdAt = now),
        StudyItem("q4", ItemKind.Short, "Calculus", "Evaluate $\\displaystyle\\int_0^{\\pi} \\sin x\\,dx$.", answer = "$2$",
            hints = listOf("Antiderivative of $\\sin x$?", "$-\\cos x$", "$-\\cos\\pi + \\cos 0$"), createdAt = now),
    )

    @Before fun setup() {
        val file = File(context.filesDir, "study/test.json").apply { parentFile?.mkdirs(); delete() }
        val store = StudyStore.load(file)
        store.update {
            BoardStudy(sources = listOf(StudySource("src", "Physics 1", 40)), items = items, progress = mapOf(
                "q1" to Progress(chosen = 2, hintsShown = 1),
                "q2" to Progress(hintsShown = 1, grade = Grade(Verdict.Partial, 0.6f, "Right idea: energy is conserved. You dropped the square root at the end, so \$v^2$ became \$v$.",
                    listOf(StepNote(true, "\$mgh = \\frac12 mv^2$"), StepNote(false, "\$v = 2gh$")), read = "v = 2gh = 29.4 m/s", at = now)),
                "q3" to Progress(flipped = true),
            ), current = "q1")
        }
        val library = LibraryStore(context)
        val settings = SettingsStore(context)
        val res = context.resources
        fonts = CardFace.Fonts(res.getFont(R.font.nunito_regular), res.getFont(R.font.nunito_semibold), res.getFont(R.font.nunito_bold))
        val nb = Notebook("board", "Physics 1", Paper(PaperTemplate.Grid, PaperColor.White, PaperSize.Endless), createdAt = now, openedAt = now, board = true)
        study = StudyController(nb, library, settings, store, MainScope(), context.cacheDir, {}, now = { now })
        layer = BoardLayer(store, library, fonts)
        study.board = layer
    }

    /** The Endless sheet at 100%: a screen is 1000 dp of a 16 000 dp sheet. */
    private fun sheet(w: Int, h: Int) = PagePlacement(0, -7500f, -1000f, 16000f, 16000f, 1f, w, h, true).let { it.copy(visiblePages = listOf(it)) }

    private fun render(w: Int, h: Int, open: Boolean = true) {
        val placement = sheet(w, h)
        // Two cards already printed on the board, at the size they had on screen.
        study.store.update { d -> d.copy(cards = listOf(
            PinnedCard("c2", "q2", (40f + 7500f) / 16f, (90f + 1000f) / 16f, 380f, 1f / 16f, now),
            PinnedCard("c3", "q3", (40f + 7500f) / 16f, (560f + 1000f) / 16f, 300f, 1f / 16f, now),
        )) }
        study.select(study.data.current!!)
        study.open = open
        val geometry = SheetGeometry()
        compose.setContent {
            view = LocalView.current
            NotiTheme(ThemeMode.Light) {
                BoxWithConstraints(Modifier.size(w.dp, h.dp).testTag("board").onGloballyPositioned { geometry.editor = it }) {
                    DocumentBackground(Paper(PaperTemplate.Grid), null, { placement }, Modifier.fillMaxSize(), sheet = true, board = layer)
                    StudySheet(study, geometry, fonts, { placement }, 0L, maxWidth, maxHeight, {}, floating = false)
                }
            }
        }
        this.geometry = geometry
    }
    private lateinit var geometry: SheetGeometry

    private fun screenshot(name: String) {
        val bitmap = compose.captureRenderedView(view, "board")
        val dir = File("build/reports/study-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tabletBoardWithSheetAndPrintedCards() {
        render(1280, 900)
        compose.onNodeWithContentDescription("Study sheet. Drag to move.").assertIsDisplayed()
        compose.onNodeWithText(" of 4", substring = true).assertExists()
        screenshot("board-tablet")
    }

    @Test fun choosingAnOptionChecksItOnTheDevice() {
        study.store.update { it.withProgress("q1") { Progress() } }
        render(1280, 900)
        compose.onNodeWithContentDescription("Choose B", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher("click label Choose B") {
            androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config && it.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label == "Choose B"
        }).onFirst().performClick()
        compose.runOnIdle {
            val p = study.data.progressOf("q1")
            assertEquals(1, p.chosen)
            assertEquals(Verdict.Correct, p.outcome(items[0]))
            assertNotNull("a right answer is scheduled for review", p.srs)
        }
        screenshot("choice-correct")
    }

    @Test fun hintsComeOneAtATime() {
        study.store.update { it.copy(current = "q4") }
        render(1280, 900)
        compose.onNodeWithText("Hint").performClick()
        compose.onNodeWithText("Hint 2").performClick()
        compose.runOnIdle { assertEquals(2, study.data.progressOf("q4").hintsShown) }
        compose.onNodeWithText("Answer").performClick()
        compose.runOnIdle { assertTrue(study.data.progressOf("q4").answerShown) }
        screenshot("hints-and-answer")
    }

    @Test fun printPinsTheFaceWhereTheSheetShowsItAtTheSameSize() {
        render(1280, 900)
        val face = compose.runOnIdle { geometry.faceInEditor()!! }
        compose.onNodeWithText("Print").performClick()
        compose.runOnIdle {
            assertFalse("the sheet goes away", study.open)
            val card = study.data.cardFor("q1")!!
            val k = 16000f / 1000f
            assertEquals(face.x, -7500f + card.x * k, 0.5f)
            assertEquals(face.y, -1000f + card.y * k, 0.5f)
            // One dp on screen is one dp of card: the same size it had on the sheet.
            assertEquals(1f, card.unitsPerDp * k, 1e-4f)
        }
        screenshot("after-print")
        // The Study button's job: bring it back on what's next.
        compose.runOnIdle { study.show() }
        compose.runOnIdle { assertTrue(study.open); assertNotEquals("q1", study.current?.id) }
    }

    @Test fun tappingAPrintedCardFindsIt() {
        render(1280, 900, open = false)
        val placement = sheet(1280, 900)
        val hit = compose.runOnIdle { layer.hit(100f, 120f, placement) }
        assertEquals("q2", (hit as BoardLayer.Hit.Card).card.itemId)
        assertNull(compose.runOnIdle { layer.hit(1000f, 850f, placement) })
    }

    @Test fun flashCardsAreRated() {
        study.store.update { it.copy(current = "q3") }
        render(1280, 900)
        compose.onNodeWithText("Good").performClick()
        compose.runOnIdle { assertEquals(Rating.Good, study.data.progressOf("q3").srs?.lastRating) }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun phoneDocksTheSheetAtTheBottom() {
        render(412, 915)
        val bounds = compose.onNodeWithContentDescription("Study sheet. Drag to move.").fetchSemanticsNode().boundsInRoot
        assertTrue("the sheet sits in the lower part: \$bounds", bounds.top > 915 * 0.3f)
        screenshot("board-phone")
    }

    @Test fun checkingWithoutAKeyAsksForOneAndLeavesTheToolAlone() {
        study.settings.tool = Tool.Pen
        study.settings.studyKey.clear()
        study.select("q4")
        study.startCheck(serial = 3)
        assertNull(study.loop)
        assertEquals(Tool.Pen, study.settings.tool)
        assertTrue(study.problem!!.contains("Settings"))
    }

    @Test fun anExamHidesItsResultsUntilTheEndThenSchedulesThem() {
        study.startExam(count = 2, minutes = 10, topics = setOf("Kinematics"))
        val exam = study.exam!!
        assertEquals(listOf("q1"), exam.itemIds)
        assertEquals("the exam starts from scratch", -1, study.data.progressOf("q1").chosen)
        study.choose(0)
        study.choose(1)
        assertEquals("answers can change until the end", 1, study.data.progressOf("q1").chosen)
        assertNull("no review until it's over", study.data.progressOf("q1").srs)
        val face = CardFace.layout(items[0], study.data.progressOf("q1"), 360f, fonts, CardFace.Mode.Exam)
        assertTrue(face.heightDp > 0f)
        study.finishExam()
        assertNull(study.exam)
        assertEquals(exam.id, study.examResults)
        assertEquals(Rating.Good, study.data.progressOf("q1").srs!!.lastRating)
    }

    @Test fun arrangingByTopicMakesAColumnPerTopicWithAHeading() {
        study.store.update { d -> d.copy(cards = listOf(
            PinnedCard("a", "q2", 700f, 90f, 360f, 0.0625f), PinnedCard("b", "q1", 500f, 60f, 360f, 0.0625f),
            PinnedCard("c", "q4", 900f, 300f, 300f, 0.0625f),
        )) }
        study.arrangeByTopic()
        val d = study.data
        assertEquals(setOf("Kinematics", "Energy", "Calculus"), d.labels.map { it.text }.toSet())
        val xs = d.topics().mapNotNull { t -> d.cards.firstOrNull { d.item(it.itemId)?.topic == t }?.x }
        assertEquals("columns follow the deck's topic order, left to right", xs.sorted(), xs)
        assertEquals(3, xs.distinct().size)
        assertEquals("cards keep their size", setOf(360f, 300f), d.cards.map { it.widthDp }.toSet())
    }

    @Test fun cardFacesForEveryState() {
        val faces = items.map { item ->
            CardFace.layout(item, study.data.progressOf(item.id).copy(answerShown = item.id == "q4"), 360f, fonts, CardFace.Mode.Board,
                study.data.items.indexOf(item) + 1, "Physics 1")
        } + CardFace.layout(items[0], Progress(chosen = 1), 360f, fonts, CardFace.Mode.Exam, 1)
        val width = 360 * 2 + 72
        val height = faces.chunked(2).sumOf { row -> row.maxOf { it.heightDp }.toInt() + 24 } + 24
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF6F4F2.toInt())
        var y = 24f
        faces.chunked(2).forEach { row ->
            row.forEachIndexed { i, f -> val s = canvas.save(); canvas.translate(24f + i * 384f, y); f.draw(canvas); canvas.restoreToCount(s) }
            y += row.maxOf { it.heightDp } + 24f
        }
        File(File("build/reports/study-ui").apply { mkdirs() }, "card-faces.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(4, faces[0].optionRects.size)
        assertTrue(faces.all { it.heightDp > 60f })
    }
}
