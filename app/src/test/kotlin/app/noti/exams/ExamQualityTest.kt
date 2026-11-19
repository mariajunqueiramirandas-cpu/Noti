package app.noti.exams

import app.noti.openrouter.OpenRouterClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExamQualityTest {
    @Test
    fun checksExactAffineBoundariesAndSignChanges() {
        assertEquals(1, wrongLinearEquivalences("\$20+2x<5x\$ equivale a \$x>20\$.").size)
        assertTrue(wrongLinearEquivalences("\$20+2x<5x\$ equivale a \$x>\\frac{20}{3}\$.").isEmpty())
        assertEquals(1, wrongLinearEquivalences("\$-2x\\leq 4\\iff x< -2\$").size)
        assertTrue(wrongLinearEquivalences("\$-2x\\leq 4\\iff x\\geq -2\$").isEmpty())
        assertEquals(1, wrongLinearEquivalences("\$x/3+2=4\\Leftrightarrow x=2\$").size)
        assertTrue(wrongLinearEquivalences("\$x/3+2=4\\Leftrightarrow x=6\$").isEmpty())
        assertTrue(wrongLinearEquivalences("R$ 40 para \$x^2<5\$ equivale a \$x>3\$.").isEmpty())
    }

    @Test
    fun catchesTheRecycledExamExercisesAndKeepsIndependentPractice() {
        val valid = sampleLesson("exam/M01").copy(exercises = originalExercises)
        val recycled = valid.copy(exercises = listOf(
            ExamExercise("Escreva a função da tarifa da empresa A para uma encomenda de x quilos.", "Dica.", "Resposta."),
            ExamExercise("Considere as tarifas da questão. Determine o intervalo.", "Dica.", "Resposta."),
            ExamExercise("Envie 200 quilos em duas encomendas usando as empresas da questão por R$ 850.", "Dica.", "Resposta."),
        ))
        val notes = checkExamLesson(recycled, deliveryQuestion)
        assertEquals(3, notes.practice.size)
        assertTrue(checkExamLesson(valid, deliveryQuestion).passed)
    }

    @Test
    fun excludesStrayLanguagesAndCleansBoilerplateWithoutChangingTheSolution() {
        val lesson = sampleLesson("exam/M01").copy(exercises = originalExercises)
        val contaminated = lesson.copy(exercises = lesson.exercises.toMutableList().apply {
            this[1] = this[1].copy(prompt = "Em uma城区 de eventos, um sensor mede a temperatura.")
        })
        assertTrue(checkExamLesson(contaminated, deliveryQuestion).practice.any { it.contains("português fluente") })
        assertEquals(
            "Item a. Some a taxa fixa.",
            polishSolutionStep("item a — check: Some a taxa fixa.\\n\\npayload: {\"answer\":\"Resultado\"}"),
        )
    }

    @Test
    fun catchesPhysicalSelfCompositionWithIncompatibleUnits() {
        val lesson = sampleLesson("exam/M01").copy(exercises = originalExercises)
        val prompt = "Uma máquina define \$h(t)\$, em litros por minuto, conforme a temperatura \$t\$, em graus Celsius: " +
            "\$h(t)=t+4\$. Calcule \$h(2)\$ e \$h(h(0))\$."
        fun withExercise(value: String) = lesson.copy(exercises = lesson.exercises.toMutableList().apply {
            this[1] = this[1].copy(prompt = value)
        })
        assertTrue(checkExamLesson(withExercise(prompt), deliveryQuestion).practice.any { it.contains("grandezas compatíveis") })
        assertTrue(checkExamLesson(withExercise(prompt.replace("litros por minuto", "graus Celsius")), deliveryQuestion).passed)
        assertTrue(checkExamLesson(withExercise(prompt.replace("Calcule", "Discuta se faz sentido calcular")), deliveryQuestion).passed)
    }

    @Test
    fun distinguishesDiscontinuityFromTheJumpCase() {
        val lesson = sampleLesson("exam/M01").copy(exercises = originalExercises)
        val original = lesson.sections.first()
        fun withText(text: String) = lesson.copy(sections = listOf(original.copy(body = text)) + lesson.sections.drop(1))
        assertTrue(
            checkExamLesson(withText("Há descontinuidade, isto é, um “salto” no gráfico."), deliveryQuestion)
                .fundamentals.any { it.contains("descontinuidade por salto") },
        )
        assertTrue(checkExamLesson(withText("Um salto é um tipo de descontinuidade."), deliveryQuestion).passed)
    }

    @Test
    fun aDraftIsRepairedAndReviewedAgainBeforePublication() = runBlocking {
        val requests = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
        val practiceCalls = AtomicInteger()
        val reviews = AtomicInteger()
        val (topic, reference) = topicFor(deliveryQuestion)
        val client = client { name, request ->
            requests += name to request
            when (name) {
                "exam_fundamentals" -> fundamentals()
                "exam_guided_practice" -> if (practiceCalls.getAndIncrement() == 0) {
                    practice("\$20+2x<5x\$ equivale a \$x>20\$.")
                } else practice()
                "exam_solution" -> sampleLesson(reference.key).solution.json()
                "exam_review_fundamentals", "exam_review_practice", "exam_review_solution" -> {
                    reviews.incrementAndGet()
                    review(name)
                }
                else -> error("Unexpected model request: $name")
            }
        }
        val lesson = ExamLessons(client).generate("test-key", topic, reference, render = { byteArrayOf(1) })
        assertTrue(lesson.isReviewed)
        assertTrue(ExamLesson.from(lesson.json()).isReviewed)
        assertFalse(ExamLesson.from(lesson.json().apply { remove("review_version") }).isReviewed)
        assertEquals(2, practiceCalls.get())
        assertEquals(3, reviews.get())
        assertEquals(1, requests.count { it.first == "exam_fundamentals" })
        assertEquals(1, requests.count { it.first == "exam_solution" })
        assertTrue(lesson.sections.all { it.answer == "A condição do intervalo decide qual regra usar." })
    }

    @Test
    fun anUnresolvedReviewerFindingBlocksPublication() {
        val reviewCount = AtomicInteger()
        val (topic, reference) = topicFor(deliveryQuestion)
        val client = client { name, _ ->
            when (name) {
                "exam_fundamentals" -> fundamentals()
                "exam_guided_practice" -> practice()
                "exam_solution" -> sampleLesson(reference.key).solution.json()
                "exam_review_fundamentals", "exam_review_practice" -> review(name)
                "exam_review_solution" -> {
                    reviewCount.incrementAndGet()
                    review(name, "A resolução não justifica o domínio do segundo item.")
                }
                else -> error("Unexpected model request: $name")
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ExamLessons(client).generate("test-key", topic, reference, render = { byteArrayOf(1) }) }
        }
        assertEquals(3, reviewCount.get())
    }

    private fun topicFor(question: ExamQuestion): Pair<ExamTopic, ExamReference> {
        val exam = PastExam("UNICAMP 2024", "UNICAMP", 2024, "2ª Fase", "exam", listOf(question))
        val reference = ExamReference(exam, question)
        return ExamTopic(question.subjectKey, question.subject, "Funções por partes", listOf(reference)) to reference
    }

    private fun fundamentals() = JSONObject().put("goal", "Explicar a função por partes.")
        .put("fundamentos", section()).put("modelo_mental", section()).put("raciocinio", section())
        .put("practice_targets", JSONObject().put("explicar", "Identificar o ponto de mudança.")
            .put("justificar", "Aplicar uma regra dentro do domínio.").put("transferir", "Escolher regras para um caso novo."))

    private fun practice(example: String = "Estude as faixas antes de operar.") =
        JSONObject().put("exemplos", section(example)).put("estrategia", section())
            .put("exercicio_aplicacao", originalExercises[1].json()).put("exercicio_transferencia", originalExercises[2].json())
            .put("exercicio_basico", originalExercises[0].json())
            .put("checklist", JSONObject().put("explicar", "Identifico a entrada.").put("justificar", "Separo as faixas.")
                .put("transferir", "Aplico em outro contexto."))

    private fun section(body: String = "Uma função atribui uma saída a cada entrada.") =
        ExamLessonSection("Título", body, "Como separar as faixas?", "Resposta provisória.").json()

    private fun review(stage: String, issue: String? = null): JSONObject {
        val keys = when (stage) {
            "exam_review_fundamentals" -> listOf("fundamentos", "modelo_mental", "raciocinio")
            "exam_review_practice" -> listOf("exemplos", "estrategia")
            else -> emptyList()
        }
        return JSONObject().put("issues", JSONArray(listOfNotNull(issue))).put("check_answers",
            JSONObject().apply { keys.forEach { put(it, "A condição do intervalo decide qual regra usar.") } },
        )
    }

    private fun client(respond: (String, JSONObject) -> JSONObject): OpenRouterClient =
        OpenRouterClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val schema = body.getJSONObject("response_format").getJSONObject("json_schema").getString("name")
            val payload = JSONObject().put("id", "gen-test").put("model", OpenRouterClient.MODEL)
                .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("content", respond(schema, body).toString()))))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())

    private val deliveryQuestion = sampleQuestion.copy(
        stem = "Duas empresas de entrega cobram uma tarifa por encomenda. A empresa A cobra R$ 4 por quilo mais R$ 30.",
        parts = listOf(
            QuestionPart("a", "Dê a função da tarifa da empresa A em termos do peso em quilos."),
            QuestionPart("b", "Compare as tarifas das empresas."),
            QuestionPart("c", "Distribua duas encomendas de 200 quilos ao todo pagando R$ 850."),
        ),
    )

    private val originalExercises = listOf(
        ExamExercise("Uma piscina enche 2 litros por minuto até 12 minutos e depois enche 1 litro por minuto. Qual o volume aos 14 minutos?",
            "Separe os tempos.", "Ao fim de 12 minutos há 24 litros. Nos dois minutos seguintes entram mais 2."),
        ExamExercise("Um sensor mostra 18 graus até meia-noite e sobe 2 graus por hora depois. Quando chega a 26 graus?",
            "Considere o trecho crescente.", "São necessárias quatro horas após meia-noite."),
        ExamExercise("Um jogo dobra pontos até 9 rodadas e depois acrescenta um bônus fixo. Proponha duas regras contínuas na transição.",
            "Iguale as pontuações na fronteira.", "A segunda expressão deve começar no valor da primeira."),
    )
}
