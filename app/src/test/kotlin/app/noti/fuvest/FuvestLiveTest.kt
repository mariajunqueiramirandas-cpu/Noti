package app.noti.fuvest

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class FuvestLiveTest {
    @Test
    fun reviewsNewPracticeForTheRealM01() = runBlocking {
        val key = System.getenv("NOTI_OPENROUTER_TEST_KEY").orEmpty()
        val assets = System.getenv("NOTI_FUVEST_TEST_ASSETS").orEmpty()
        assumeTrue(key.isNotBlank() && assets.isNotBlank())
        val directory = File(assets)
        val exam = FuvestExam.from(JSONObject(directory.resolve("fuvest-test-import.json").readText()))
        assertTrue(exam.official2023)
        val reference = FuvestReference(exam, exam.questions.single { it.code == "M01" })
        val topic = FuvestTopic(FuvestSubject.Math, "Funções por partes", listOf(reference))
        val lesson = withTimeout(600_000) {
            FuvestLessons().generate(key, topic, reference, render = { page ->
                directory.resolve("fuvest-vision/page-%02d.jpg".format(Locale.ROOT, page)).readBytes()
            })
        }
        assertTrue(lesson.isReviewed)
        assertTrue(checkFuvestLesson(lesson, reference.question).passed)
        assertEquals(3, lesson.exercises.size)
        assertTrue(lesson.sections.sumOf { it.body.length } >= 4_000)
        assertTrue(lesson.exercises.none { it.prompt.contains("encomenda", ignoreCase = true) ||
            it.prompt.contains("FUVEST", ignoreCase = true) })
        val answer = lesson.solution.answer + lesson.solution.steps.joinToString(" ")
        listOf("15", "45", "190", "150").forEach { assertTrue("Missing verified M01 result $it", answer.contains(it)) }
        directory.resolve("fuvest-reviewed-m01.json").writeText(lesson.json().toString(2))
    }

    @Test
    fun officialEngineeringExamAndDeepLesson() = runBlocking {
        val key = System.getenv("NOTI_OPENROUTER_TEST_KEY").orEmpty()
        val assets = System.getenv("NOTI_FUVEST_TEST_ASSETS").orEmpty()
        assumeTrue(key.isNotBlank() && assets.isNotBlank())
        val directory = File(assets)
        assertTrue(Fuvest2023.matches(File(directory, "fuvest-2023-dia2.pdf")))
        val render: suspend (Int) -> ByteArray = { page ->
            directory.resolve("fuvest-vision/page-%02d.jpg".format(Locale.ROOT, page)).readBytes()
        }
        val exam = withTimeout(240_000) {
            FuvestImport().analyze(key, "official-2023", 2023, Fuvest2023.pageGroups, true, render)
        }
        assertEquals(Fuvest2023.questionPages, exam.questions.associate { it.code to it.pages })
        assertEquals(36, exam.questions.sumOf { it.parts.size })
        assertTrue(exam.questions.single { it.code == "Q01" }.answerSheet)
        val cues = mapOf("M01" to "encomenda", "F02" to "telescópio", "F04" to "resistor", "Q04" to "ureia")
        cues.forEach { (code, cue) ->
            assertTrue("Wrong subject matter for $code", exam.questions.single { it.code == code }.prompt.contains(cue, ignoreCase = true))
        }
        directory.resolve("fuvest-test-import.json").writeText(exam.json().toString(2))
        val m01 = FuvestReference(exam, exam.questions.first())
        val topic = FuvestTopic(FuvestSubject.Math, "Funções por partes", listOf(m01))
        val lesson = withTimeout(600_000) { FuvestLessons().generate(key, topic, m01, render) }
        directory.resolve("fuvest-test-lesson.json").writeText(lesson.json().toString(2))
        assertTrue(lesson.sections.sumOf { it.body.length } >= 4_000)
        val answer = lesson.solution.answer + lesson.solution.steps.joinToString(" ")
        listOf("15", "45", "190", "150").forEach { assertTrue("Missing verified M01 result $it", answer.contains(it)) }
    }
}
