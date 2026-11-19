package app.noti.exams

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.noti.data.LibraryStore
import app.noti.openrouter.OpenRouterClient
import app.noti.study.ItemKind
import app.noti.study.Progress
import app.noti.study.Rating
import app.noti.study.Srs
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class ExamTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun rangesUsePhysicalOneBasedPages() {
        assertEquals(listOf(2, 3, 4, 5, 8, 9, 10, 11), parseExamPages("2–5, 8-11, 3", 38))
        assertEquals(listOf(1, 2, 3), parseExamPages("", 3))
        listOf("0", "5-2", "1, 39", "página 2", "2-2147483648").forEach { range ->
            assertThrows(IllegalArgumentException::class.java) { parseExamPages(range, 38) }
        }
    }

    @Test
    fun aiImportExtractsExamMetadataDynamicSubjectsMultipleChoiceAndAnswerKey() = runBlocking {
        val batches = listOf(
            JSONObject()
                .put("exam", JSONObject()
                    .put("title", "ENEM 2024 · 2º Dia")
                    .put("institution", "ENEM")
                    .put("year", 2024)
                    .put("details", "Caderno Azul · Ciências da Natureza"))
                .put("questions", JSONArray()
                    .put(JSONObject()
                        .put("code", "91")
                        .put("subject", "Biologia")
                        .put("format", "choice")
                        .put("stem", "Qual organela realiza a respiração celular aeróbica?")
                        .put("parts", JSONArray())
                        .put("choices", JSONArray(listOf("A) Ribossomo", "B) Mitocôndria", "C) Lisossomo", "D) Complexo de Golgi", "E) Peroxissomo")))
                        .put("correct_choice", -1)
                        .put("pages", JSONArray(listOf(1)))
                        .put("topics", JSONArray(listOf("Citologia", "Respiração celular")))
                        .put("answer_sheet", false)))
                .put("answer_key", JSONArray()),
            JSONObject()
                .put("exam", JSONObject().put("title", "").put("institution", "").put("year", 0).put("details", ""))
                .put("questions", JSONArray())
                .put("answer_key", JSONArray().put(JSONObject().put("code", "91").put("correct_choice", 1))),
        )
        var call = 0
        val client = OpenRouterClient(OkHttpClient.Builder().addInterceptor { chain ->
            val payload = JSONObject().put("id", "gen-$call").put("model", OpenRouterClient.MODEL)
                .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("content", batches[call++].toString()))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())

        val exam = ExamImport(client).analyze(
            key = "test-key",
            sourceId = "enem-2024",
            fileName = "enem_2024_dia2.pdf",
            groups = listOf(listOf(1), listOf(2)),
            render = { byteArrayOf(1) },
        )
        assertEquals("ENEM 2024 · 2º Dia", exam.title)
        assertEquals("ENEM", exam.institution)
        assertEquals(2024, exam.year)
        assertEquals("Caderno Azul · Ciências da Natureza", exam.details)
        val q = exam.questions.single()
        assertEquals("91", q.code)
        assertEquals("Biologia", q.subject)
        assertEquals(QuestionFormat.Choice, q.format)
        assertEquals(listOf("Ribossomo", "Mitocôndria", "Lisossomo", "Complexo de Golgi", "Peroxissomo"), q.choices)
        assertEquals(1, q.correctChoice)

        val item = ExamReference(exam, q).toStudyItem()
        assertEquals(ItemKind.Choice, item.kind)
        assertEquals(1, item.correctChoice)
        assertEquals("ENEM", item.credit)
        assertTrue(item.answer.contains("B) Mitocôndria"))
    }

    @Test
    fun legacyFuvestV1CatalogMigratesSeamlesslyIntoGeneralizedExamStore() = runBlocking {
        val dir = directory.newFolder()
        val legacy = dir.resolve("fuvest.json")
        val target = dir.resolve("exams.json")
        val v1 = JSONObject()
            .put("version", 1)
            .put("exams", JSONArray().put(JSONObject()
                .put("year", 2023)
                .put("source", "fuvest-2023-src")
                .put("official_2023", true)
                .put("questions", JSONArray().put(JSONObject()
                    .put("code", "M01")
                    .put("stem", "Compare os preços.")
                    .put("parts", JSONArray().put(JSONObject().put("label", "a").put("text", "Modele os preços.")))
                    .put("pages", JSONArray(listOf(2)))
                    .put("topics", JSONArray(listOf("Funções por partes")))
                    .put("answer_sheet", false)))))
            .put("lessons", JSONObject().put("Math/funcoes por partes", sampleLesson("fuvest-2023-src/M01").json()))
            .put("journey", JSONObject()
                .put("completed", JSONObject().put("Math/funcoes por partes", JSONObject().put("foundations", 1_700_000_000_000L)))
                .put("in_progress", JSONObject().put("Math/funcoes por partes", "mental_model"))
                .put("last_topic", "Math/funcoes por partes"))
        legacy.writeText(v1.toString())

        val store = ExamStore(target, legacy)
        store.load()
        val catalog = checkNotNull(store.catalog)
        assertTrue(target.exists())
        val exam = catalog.exams.single()
        assertEquals("FUVEST", exam.institution)
        assertEquals(2023, exam.year)
        val q = exam.questions.single()
        assertEquals("Matemática", q.subject)
        assertTrue(q.officialSolutionUrl!!.endsWith("#page=17"))
        val migratedKey = topicId("Matemática", "Funções por partes")
        assertNotNull(catalog.lessons[migratedKey])
        assertTrue(StudyMilestone.Foundations in catalog.journey.milestones(migratedKey))
        assertEquals(StudyMilestone.MentalModel, catalog.journey.inProgress[migratedKey])
        assertEquals(migratedKey, catalog.journey.lastTopic)
    }

    @Test
    fun journeyRetainsTheOpenStepAndCountsEachRewardOnce() = runBlocking {
        val day = LocalDate.of(2026, 11, 19)
        val now = Instant.parse("2026-11-19T02:00:00Z").toEpochMilli()
        val yesterday = Instant.parse("2026-11-18T02:00:00Z").toEpochMilli()
        val topic = topicId("Matemática", sampleQuestion.topics.single())
        val path = directory.newFolder().resolve("exams.json")
        path.writeText(ExamCatalog().json().apply { remove("journey") }.toString())
        val store = ExamStore(path)
        store.load()
        assertEquals(StudyJourney(), store.catalog?.journey)
        store.update { catalog ->
            val journey = catalog.journey
                .complete(topic, StudyMilestone.Foundations, yesterday)
                .complete(topic, StudyMilestone.Foundations, now)
                .complete(topic, StudyMilestone.Basics, now)
                .open(topic, StudyMilestone.MentalModel)
            catalog.copy(journey = journey)
        }
        val reopened = ExamStore(path)
        reopened.load()
        val journey = checkNotNull(reopened.catalog).journey
        val question = Progress(attempts = 3, srs = Srs.review(null, Rating.Good, now))
        assertEquals(topic, journey.lastTopic)
        assertEquals(StudyMilestone.MentalModel, journey.inProgress[topic])
        assertEquals(StudyOverview(45, 2), journey.overview(mapOf("exam/M01" to question), day, ZoneOffset.UTC))
        val completed = journey.complete(topic, StudyMilestone.MentalModel, now)
        assertTrue(completed.inProgress.isEmpty())
        assertEquals(StudyOverview(55, 3), completed.overview(mapOf("exam/M01" to question), day, ZoneOffset.UTC))
        assertEquals(2, StudyOverview(100, 0).level)
    }

    @Test
    fun incompleteLessonsAreRejected() {
        val lesson = sampleLesson("exam/M01")
        val fundamentals = JSONObject().put("goal", lesson.goal)
        val practice = JSONObject().put(
            "checklist",
            JSONObject().put("explicar", lesson.checklist[0])
                .put("justificar", lesson.checklist[1]).put("transferir", lesson.checklist[2]),
        )
        listOf("fundamentos", "modelo_mental", "raciocinio", "exemplos", "estrategia").forEachIndexed { i, key ->
            (if (i < 3) fundamentals else practice).put(key, lesson.sections[i].json())
        }
        listOf("exercicio_basico", "exercicio_aplicacao", "exercicio_transferencia").forEachIndexed { i, key ->
            practice.put(key, lesson.exercises[i].json())
        }
        val decoded = decodeExamLesson(fundamentals, practice, lesson.solution.json(), "exam/M01")
        assertEquals(lesson.sections.map { it.body }, decoded.sections.map { it.body })
        assertEquals(lesson.solution, decoded.solution)
        practice.getJSONObject("exercicio_transferencia").put("hint", "")
        assertThrows(IllegalArgumentException::class.java) {
            decodeExamLesson(fundamentals, practice, lesson.solution.json(), "exam/M01")
        }
    }

    @Test
    fun globalProgressMergesAcrossExamsWithoutCreatingExtraBoards() = runBlocking {
        val library = LibraryStore(ApplicationProvider.getApplicationContext<Application>())
        val source = library.addPdf("unicamp-2025", "UNICAMP 2025", 20, null)
        val exam = PastExam("UNICAMP 2025", "UNICAMP", 2025, "1ª Fase", source.id, listOf(sampleQuestion))
        val ref = ExamReference(exam, sampleQuestion)
        val catalog = ExamCatalog(
            exams = listOf(exam),
            progress = mapOf(ref.key to Progress(attempts = 2)),
        )
        val merged = examQuestionProgress(library, catalog)
        assertEquals(2, merged[ref.key]?.attempts)
    }
}

internal val sampleQuestion = ExamQuestion(
    code = "M01",
    subject = "Matemática",
    format = QuestionFormat.Written,
    stem = "Compare os preços das duas empresas.",
    parts = listOf(QuestionPart("a", "Modele os preços.")),
    pages = listOf(2),
    topics = listOf("Funções por partes"),
    answerSheet = false,
)

internal fun sampleLesson(key: String) = ExamLesson(
    goal = "Explicar como o preço varia.",
    sections = List(5) {
        ExamLessonSection(
            "Conceito ${it + 1}", "Modelamos a situação com uma função.",
            "Como você identifica o ponto de mudança?", "Pela condição que separa os dois regimes.",
        )
    },
    exercises = List(3) { ExamExercise("Compare as duas funções.", "Considere cada intervalo.", "Use os limites de cada trecho.") },
    checklist = listOf("Defino uma função.", "Explico o domínio.", "Justifico a resposta."),
    questionKey = key,
    solution = ExamSolution("Modele cada preço.", listOf("Separe em casos."), listOf("Compare em cada intervalo.")),
)
