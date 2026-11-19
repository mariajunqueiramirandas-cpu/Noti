package app.noti.fuvest

import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal enum class FuvestSubject(val label: String, val prefix: Char) {
    Math("Matemática", 'M'), Physics("Física", 'F'), Chemistry("Química", 'Q');

    val codes get() = (1..4).map { "${prefix}0$it" }
}

internal data class FuvestPart(val label: String, val text: String) {
    fun json() = JSONObject().put("label", label).put("text", text)

    companion object {
        fun from(json: JSONObject) = FuvestPart(json.getString("label"), json.getString("text"))
    }
}

internal data class FuvestQuestion(
    val code: String,
    val stem: String,
    val parts: List<FuvestPart>,
    val pages: List<Int>,
    val topics: List<String>,
    val answerSheet: Boolean,
) {
    val subject get() = FuvestSubject.entries.first { code in it.codes }
    val prompt get() = (listOf(stem) + parts.map { "${it.label}) ${it.text}" }).joinToString("\n\n")

    fun json() = JSONObject().put("code", code).put("stem", stem)
        .put("parts", JSONArray(parts.map { it.json() })).put("pages", JSONArray(pages))
        .put("topics", JSONArray(topics)).put("answer_sheet", answerSheet)

    companion object {
        fun from(json: JSONObject) = FuvestQuestion(
            json.getString("code"), json.getString("stem"), json.getJSONArray("parts").objects(FuvestPart::from),
            json.getJSONArray("pages").let { array -> List(array.length()) { array.getInt(it) } },
            json.getJSONArray("topics").strings(), json.getBoolean("answer_sheet"),
        )
    }
}

internal data class FuvestExam(
    val year: Int,
    val sourceId: String,
    val official2023: Boolean,
    val questions: List<FuvestQuestion>,
) {
    val boardId get() = UUID.nameUUIDFromBytes("fuvest-board-$sourceId".toByteArray(Charsets.UTF_8)).toString()
    val title get() = "FUVEST $year · Engenharia"
    fun key(question: FuvestQuestion) = "$sourceId/${question.code}"

    fun json() = JSONObject().put("year", year).put("source", sourceId).put("official_2023", official2023)
        .put("questions", JSONArray(questions.map { it.json() }))

    companion object {
        fun from(json: JSONObject) = FuvestExam(
            json.getInt("year"), json.getString("source"), json.optBoolean("official_2023"),
            json.getJSONArray("questions").objects(FuvestQuestion::from),
        )
    }
}

internal data class FuvestReference(val exam: FuvestExam, val question: FuvestQuestion) {
    val key get() = exam.key(question)
    val label get() = "${exam.year} · ${question.code}"
}

internal data class FuvestTopic(
    val subject: FuvestSubject,
    val name: String,
    val questions: List<FuvestReference>,
) {
    val key get() = topicId(subject, name)
}

internal fun topicId(subject: FuvestSubject, name: String) = "${subject.name}/${normalizeTopic(name)}"

internal fun normalizeTopic(name: String): String =
    Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

internal val fuvestMathInstructions = """
    Use LaTeX com \(…\) para símbolos e fórmulas curtas e \[…\] para fórmulas longas, funções por partes,
    matrizes e contas em várias linhas. Nas contas em várias linhas, use aligned com & nos sinais de igualdade
    e \\ para mudar de linha. Use \frac para frações, \mathrm para unidades e fórmulas químicas, ^{…} e _{…}
    para cargas, índices e expoentes, \rightarrow para setas de reação e {,} para a vírgula decimal.
    Escreva valores em reais fora da fórmula ou use \$ dentro dela. Feche todos os delimitadores e chaves.
""".trimIndent()

internal data class FuvestCatalog(
    val exams: List<FuvestExam> = emptyList(),
    val lessons: Map<String, FuvestLesson> = emptyMap(),
    val journey: FuvestJourney = FuvestJourney(),
) {
    fun topics(subject: FuvestSubject): List<FuvestTopic> {
        val tags = exams.sortedByDescending { it.year }.flatMap { exam ->
            exam.questions.filter { it.subject == subject }.flatMap { question ->
                question.topics.map { it to FuvestReference(exam, question) }
            }
        }
        return tags.groupBy { normalizeTopic(it.first) }.values.map { group ->
            FuvestTopic(subject, group.first().first, group.map { it.second }.distinctBy { it.key })
        }.sortedBy { normalizeTopic(it.name) }
    }

    fun officialSolution(reference: FuvestReference): String? =
        if (reference.exam.official2023) Fuvest2023.solutionUrl(reference.question.code) else null

    fun json() = JSONObject().put("version", 1).put("exams", JSONArray(exams.map { it.json() }))
        .put("lessons", JSONObject().apply { lessons.forEach { (key, lesson) -> put(key, lesson.json()) } })
        .put("journey", journey.json())

    companion object {
        fun from(json: JSONObject): FuvestCatalog {
            require(json.getInt("version") == 1)
            val lessons = json.getJSONObject("lessons")
            return FuvestCatalog(
                json.getJSONArray("exams").objects(FuvestExam::from),
                lessons.keys().asSequence().associateWith { FuvestLesson.from(lessons.getJSONObject(it)) },
                json.optJSONObject("journey")?.let(FuvestJourney::from) ?: FuvestJourney(),
            )
        }
    }
}

/** Single writer for the source index and the saved lessons. Publish state after the disk write. */
internal class FuvestStore(file: File) {
    private val disk = AtomicFile(file)
    private val mutex = Mutex()
    var catalog by mutableStateOf<FuvestCatalog?>(null)
        private set

    suspend fun load() = mutex.withLock {
        if (catalog == null) {
            catalog = withContext(Dispatchers.IO) {
                try {
                    FuvestCatalog.from(JSONObject(String(disk.readFully(), Charsets.UTF_8)))
                } catch (error: java.io.FileNotFoundException) {
                    if (disk.baseFile.exists() || File(disk.baseFile.path + ".bak").exists()) throw error
                    FuvestCatalog()
                }
            }
        }
    }

    suspend fun update(change: (FuvestCatalog) -> FuvestCatalog) = mutex.withLock {
        val next = change(checkNotNull(catalog))
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                val bytes = next.json().toString().toByteArray(Charsets.UTF_8)
                val output = disk.startWrite()
                try {
                    output.write(bytes)
                    disk.finishWrite(output)
                } catch (error: Exception) {
                    disk.failWrite(output)
                    throw error
                }
            }
            catalog = next
        }
    }
}

internal fun <T> JSONArray.objects(read: (JSONObject) -> T) = List(length()) { read(getJSONObject(it)) }
internal fun JSONArray.strings() = List(length()) { getString(it) }
