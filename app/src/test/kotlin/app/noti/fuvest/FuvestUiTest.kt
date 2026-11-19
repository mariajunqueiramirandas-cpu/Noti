package app.noti.fuvest

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.noti.data.LibraryStore
import app.noti.data.ThemeMode
import app.noti.ui.fuvest.FuvestScreen
import app.noti.ui.fuvest.EditReadQuestion
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
class FuvestUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun importReviewShowsFormattedMathAndReflectsEdits() {
        val question = sampleQuestion.copy(stem = """Produz-se CO\(_2\).""")
        var saved: FuvestQuestion? = null
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
    fun topicOpensSavedLessonOriginalAndSharedPracticeBoard() {
        val library = LibraryStore(compose.activity)
        val source = library.addPdf("fuvest-ui-pdf", "FUVEST 2023", 38, null)
        checkNotNull(library.pdfFile(source)).writeBytes(byteArrayOf(1))
        val exam = FuvestExam(2023, source.id, true, listOf(sampleQuestion))
        val reference = FuvestReference(exam, sampleQuestion)
        val store = FuvestStore(directory.newFolder().resolve("fuvest.json"))
        runBlocking {
            store.load()
            store.update {
                FuvestCatalog(listOf(exam), mapOf(topicId(FuvestSubject.Math, sampleQuestion.topics.single()) to sampleLesson(reference.key)))
            }
        }
        var openedSource: Pair<String, Int>? = null
        var openedPractice: Pair<String, String>? = null
        compose.setContent {
            NotiTheme(ThemeMode.Light) {
                FuvestScreen(library, store, onBack = {}, onSettings = {},
                    onSource = { id, page -> openedSource = id to page },
                    onPractice = { id, item -> openedPractice = id to item })
            }
        }
        compose.onNodeWithText("Matemática").performClick()
        compose.onNodeWithText("Funções por partes").performClick()
        compose.onNodeWithText("Abrir aula").performClick()
        compose.onNodeWithText("Trilha da aula").assertIsDisplayed()
        compose.onNodeWithText("Explicar como o preço varia.").assertIsDisplayed()
        compose.onNodeWithText("Começar trilha").performClick()
        compose.onNodeWithText("Concluir etapa").assertIsNotEnabled()
        compose.onNodeWithText("Conferir resposta").performScrollTo().performClick()
        compose.onNodeWithText("Concluir etapa").performScrollTo().performClick()
        val topic = topicId(FuvestSubject.Math, sampleQuestion.topics.single())
        compose.waitUntil(8_000) { FuvestMilestone.Foundations in store.catalog?.journey?.milestones(topic).orEmpty() }
        compose.onNodeWithText("Etapa concluída · +10 XP").assertIsDisplayed()
        compose.onNodeWithText("Próxima etapa").performScrollTo().performClick()
        compose.waitUntil(8_000) { store.catalog?.journey?.inProgress?.get(topic) == FuvestMilestone.MentalModel }
        compose.onNodeWithText("Conceito 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithText("1 de 8 etapas concluídas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithText("Abrir PDF · p. 2").performClick()
        compose.runOnIdle { assertEquals(source.id to 2, openedSource) }
        compose.onNodeWithText("Resolver no quadro").performClick()
        compose.waitUntil(8_000) { openedPractice != null }
        compose.runOnIdle { assertEquals(exam.boardId to reference.key, openedPractice) }
    }
}
