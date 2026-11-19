package app.noti.fuvest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.noti.data.LibraryStore
import app.noti.study.Progress
import app.noti.study.Rating
import app.noti.study.Srs
import app.noti.study.StudyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class FuvestTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun rangesUsePhysicalOneBasedPages() {
        assertEquals(listOf(2, 3, 4, 5, 8, 9, 10, 11), parseFuvestPages("2–5, 8-11, 3", 38))
        assertEquals(listOf(1, 2, 3), parseFuvestPages("", 3))
        listOf("0", "5-2", "1, 39", "página 2", "2-2147483648").forEach { range ->
            assertThrows(IllegalArgumentException::class.java) { parseFuvestPages(range, 38) }
        }
    }

    @Test
    fun verifiesCitationsAndCombinesEquivalentTopicLabels() {
        val row = sampleQuestion.copy(topics = listOf("Funções por partes", "funcoes  por partes", "Função afim"))
        val json = JSONObject().put("questions", JSONArray().put(row.json()).put(row.copy(code = "M06").json()))
        val parsed = decodeFuvestBatch(json.toString(), listOf(2, 3)).single()
        assertEquals(listOf("Funções por partes", "Função afim"), parsed.topics)
        assertEquals(listOf(2), parsed.pages)
        assertThrows(IllegalArgumentException::class.java) { decodeFuvestBatch(json.toString(), listOf(5)) }
        val catalog = FuvestCatalog(listOf(
            FuvestExam(2023, "exam-a", true, listOf(parsed)),
            FuvestExam(2022, "exam-b", false, listOf(row.copy(topics = listOf("funcoes por partes")))),
        ))
        val topic = catalog.topics(FuvestSubject.Math).single { it.name == "Funções por partes" }
        assertEquals(listOf("exam-a/M01", "exam-b/M01"), topic.questions.map { it.key })
        assertTrue(catalog.officialSolution(topic.questions.first())!!.endsWith("#page=17"))
        assertNull(catalog.officialSolution(topic.questions.last()))
    }

    @Test
    fun officialReadingUsesFixedSubpartsAndIncludesTheFacingPage() {
        val payload = JSONObject().put("F02", JSONObject()
            .put("stem", "Compare os telescópios.")
            .put("a", "Compare os telescópios.\n\na) Trace os raios.")
            .put("b", "b) Calcule a razão.").put("c", "Compare as imagens.")
            .put("topics", JSONArray().put("Óptica geométrica")))
        val question = decodeOfficialFuvestBatch(payload.toString(), mapOf("F02" to Fuvest2023.questionPages.getValue("F02"))).single()
        assertEquals(listOf("a", "b", "c"), question.parts.map { it.label })
        assertEquals(listOf(8, 9), question.pages)
        assertTrue(question.answerSheet)
        assertEquals("Compare os telescópios.", question.stem)
        assertEquals(listOf("Trace os raios.", "Calcule a razão.", "Compare as imagens."), question.parts.map { it.text })
    }

    @Test
    fun savedLessonAndSharedBoardProgressSurviveReopening() = runBlocking {
        val library = LibraryStore(ApplicationProvider.getApplicationContext<Application>())
        val source = library.addPdf("fuvest-source", "FUVEST 2023", 38, null)
        val question = sampleQuestion.copy(topics = listOf("Funções por partes", "Função afim"))
        val exam = FuvestExam(2023, source.id, true, listOf(question))
        val reference = FuvestReference(exam, question)
        val lesson = sampleLesson(reference.key)
        val path = directory.newFolder().resolve("fuvest.json")
        val store = FuvestStore(path)
        store.load()
        store.update { it.copy(exams = listOf(exam), lessons = mapOf(topicId(question.subject, question.topics[0]) to lesson)) }
        val reopened = FuvestStore(path)
        reopened.load()
        val catalog = checkNotNull(reopened.catalog)
        assertEquals(lesson, catalog.lessons.values.single())
        val topics = catalog.topics(question.subject)
        assertEquals(reference.key, topics[0].questions.single().key)
        assertEquals(reference.key, topics[1].questions.single().key)
        prepareFuvestBoard(library, catalog, exam, question)
        val board = checkNotNull(library.notebook(exam.boardId))
        val boardFile = library.studyFile(board)
        StudyStore.editSaved(boardFile) { it.withProgress(reference.key) { progress -> progress.copy(attempts = 1) } }
        prepareFuvestBoard(library, catalog, exam, question)
        assertEquals(1, library.notebooks.count { it.board })
        val deck = StudyStore.load(boardFile).data
        assertEquals(reference.key, deck.current)
        assertEquals(question.pages, deck.items.single().pages)
        assertTrue(deck.items.single().answer.contains("Resolução gerada por IA"))
        assertTrue(deck.items.single().officialSolutionUrl!!.endsWith("#page=17"))
        assertEquals(1, fuvestQuestionProgress(library, listOf(exam))[reference.key]?.attempts)
    }

    @Test
    fun journeyRetainsTheOpenStepAndCountsEachRewardOnce() = runBlocking {
        val day = LocalDate.of(2026, 11, 19)
        val now = Instant.parse("2026-11-19T02:00:00Z").toEpochMilli()
        val yesterday = Instant.parse("2026-11-18T02:00:00Z").toEpochMilli()
        val topic = topicId(FuvestSubject.Math, sampleQuestion.topics.single())
        val path = directory.newFolder().resolve("fuvest.json")
        path.writeText(FuvestCatalog().json().apply { remove("journey") }.toString())
        val store = FuvestStore(path)
        store.load()
        assertEquals(FuvestJourney(), store.catalog?.journey)
        store.update { catalog ->
            val journey = catalog.journey
                .complete(topic, FuvestMilestone.Foundations, yesterday)
                .complete(topic, FuvestMilestone.Foundations, now)
                .complete(topic, FuvestMilestone.Basics, now)
                .open(topic, FuvestMilestone.MentalModel)
            catalog.copy(journey = journey)
        }
        val reopened = FuvestStore(path)
        reopened.load()
        val journey = checkNotNull(reopened.catalog).journey
        val question = Progress(attempts = 3, srs = Srs.review(null, Rating.Good, now))
        assertEquals(topic, journey.lastTopic)
        assertEquals(FuvestMilestone.MentalModel, journey.inProgress[topic])
        assertEquals(FuvestOverview(45, 2), journey.overview(mapOf("exam/M01" to question), day, ZoneOffset.UTC))
        val completed = journey.complete(topic, FuvestMilestone.MentalModel, now)
        assertTrue(completed.inProgress.isEmpty())
        assertEquals(FuvestOverview(55, 3), completed.overview(mapOf("exam/M01" to question), day, ZoneOffset.UTC))
        assertEquals(2, FuvestOverview(100, 0).level)
    }

    @Test
    fun incompleteLessonsAreRejected() {
        val lesson = sampleLesson("exam/M01")
        val fundamentals = JSONObject().put("goal", lesson.goal)
        val practice = JSONObject().put("checklist", JSONObject().put("explicar", lesson.checklist[0])
                .put("justificar", lesson.checklist[1]).put("transferir", lesson.checklist[2]))
        listOf("fundamentos", "modelo_mental", "raciocinio", "exemplos", "estrategia").forEachIndexed { i, key ->
            (if (i < 3) fundamentals else practice).put(key, lesson.sections[i].json())
        }
        listOf("exercicio_basico", "exercicio_aplicacao", "exercicio_transferencia").forEachIndexed { i, key ->
            practice.put(key, lesson.exercises[i].json())
        }
        val decoded = decodeFuvestLesson(fundamentals, practice, lesson.solution.json(), "exam/M01")
        assertEquals(lesson.sections.map { it.body }, decoded.sections.map { it.body })
        assertEquals(lesson.solution, decoded.solution)
        practice.getJSONObject("exercicio_transferencia").put("hint", "")
        assertThrows(IllegalArgumentException::class.java) {
            decodeFuvestLesson(fundamentals, practice, lesson.solution.json(), "exam/M01")
        }
    }
}

internal val sampleQuestion = FuvestQuestion("M01", "Compare os preços das duas empresas.",
    listOf(FuvestPart("a", "Modele os preços.")), listOf(2), listOf("Funções por partes"), false)

internal fun sampleLesson(key: String) = FuvestLesson(
    goal = "Explicar como o preço varia.",
    sections = List(5) { FuvestLessonSection("Conceito ${it + 1}", "Modelamos a situação com uma função.",
        "Como você identifica o ponto de mudança?", "Pela condição que separa os dois regimes.") },
    exercises = List(3) { FuvestExercise("Compare as duas funções.", "Considere cada intervalo.", "Use os limites de cada trecho.") },
    checklist = listOf("Defino uma função.", "Explico o domínio.", "Justifico a resposta."),
    questionKey = key,
    solution = FuvestSolution("Modele cada preço.", listOf("Separe em casos."), listOf("Compare em cada intervalo.")),
)
