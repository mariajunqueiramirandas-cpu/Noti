package app.noti.exams

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.noti.data.LibraryStore
import app.noti.data.Paper
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.ui.exams.EditReadQuestion
import app.noti.ui.exams.StudyHubScreen
import app.noti.ui.study.StudyLaunchTarget
import app.noti.ui.theme.NotiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExamUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun importReviewShowsFormattedMathAndReflectsEdits() {
        val question = sampleQuestion.copy(stem = """Produz-se CO\(_2\).""")
        var saved: ExamQuestion? = null
        compose.setContent {
            NotiTheme(ThemeMode.Light) {
                EditReadQuestion(question, onClose = {}, onSave = { saved = it })
            }
        }
        compose.onNodeWithText("Produz-se CO₂.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Editar texto").performClick()
        val edited = """Produz-se \(\mathrm{H_2O}\)."""
        compose.onNode(hasSetTextAction() and hasText("Enunciado")).performTextReplacement(edited)
        compose.onNodeWithText("Prévia").performClick()
        compose.onNodeWithText("Produz-se H₂O.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Salvar revisão").performClick()
        compose.runOnIdle { assertEquals(edited, saved?.stem) }
    }

    @Test
    fun studyHubShowsAnyExamSubjectKeepsGamificationAndAlwaysAsksWhichCanvasToOpen() {
        val library = LibraryStore(compose.activity)
        val settings = SettingsStore(compose.activity)
        val myNotebook = library.create("Meu caderno de estudo", Paper(), null)
        val source = library.addPdf("enem-ui-pdf", "ENEM 2024", 24, null)
        checkNotNull(library.pdfFile(source)).writeBytes(byteArrayOf(1))
        val question = ExamQuestion(
            code = "91",
            subject = "Biologia",
            format = QuestionFormat.Choice,
            stem = "Qual organela realiza a respiração celular?",
            choices = listOf("Ribossomo", "Mitocôndria", "Lisossomo", "Golgi"),
            correctChoice = 1,
            pages = listOf(4),
            topics = listOf("Citologia"),
        )
        val exam = PastExam("ENEM 2024 · 2º Dia", "ENEM", 2024, "Caderno Azul", source.id, listOf(question))
        val reference = ExamReference(exam, question)
        val store = ExamStore(directory.newFolder().resolve("exams.json"))
        val topic = topicId("Biologia", "Citologia")
        runBlocking {
            store.load()
            store.update { ExamCatalog(listOf(exam), mapOf(topic to sampleLesson(reference.key))) }
        }
        var openedSource: Pair<String, Int>? = null
        var openedCanvas: Pair<String, StudyLaunchTarget>? = null
        compose.setContent {
            NotiTheme(ThemeMode.Light) {
                StudyHubScreen(
                    library = library,
                    settings = settings,
                    store = store,
                    onBack = {},
                    onSettings = {},
                    onSource = { id, page -> openedSource = id to page },
                    onOpenOnCanvas = { id, target -> openedCanvas = id to target },
                )
            }
        }
        compose.onNodeWithText("Biologia").performClick()
        compose.onNodeWithText("Citologia").performClick()
        compose.onNodeWithText("Abrir aula").performClick()
        compose.onNodeWithText("Trilha da aula").assertIsDisplayed()
        compose.onNodeWithText("Começar trilha").performClick()
        compose.onNodeWithText("Concluir etapa").assertIsNotEnabled()
        compose.onNodeWithText("Conferir resposta").performScrollTo().performClick()
        compose.onNodeWithText("Concluir etapa").performScrollTo().performClick()
        compose.waitUntil(8_000) { StudyMilestone.Foundations in store.catalog?.journey?.milestones(topic).orEmpty() }
        compose.onNodeWithText("Etapa concluída · +10 XP").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithText("Abrir PDF · p. 4").performClick()
        compose.runOnIdle { assertEquals(source.id to 4, openedSource) }
        // Tapping "Resolver no quadro" always asks which notebook to open!
        compose.onNodeWithText("Resolver no quadro").performClick()
        compose.onNodeWithText("Escolher caderno").assertIsDisplayed()
        compose.onNodeWithText(myNotebook.name).performClick()
        compose.runOnIdle {
            assertEquals(myNotebook.id to StudyLaunchTarget.Exam(reference.key), openedCanvas)
        }
    }
}
