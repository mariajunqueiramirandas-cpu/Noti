package app.noti.exams

import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.noti.study.Progress
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

internal enum class QuestionFormat(val wire: String, val label: String) {
    Written("written", "Discursiva"),
    Choice("choice", "Objetiva");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: Written
    }
}

internal data class QuestionPart(val label: String, val text: String) {
    fun json() = JSONObject().put("label", label).put("text", text)

    companion object {
        fun from(json: JSONObject) = QuestionPart(json.getString("label"), json.getString("text"))
    }
}

internal data class ExamQuestion(
    val code: String,
    val subject: String,
    val format: QuestionFormat = QuestionFormat.Written,
    val stem: String,
    val parts: List<QuestionPart> = emptyList(),
    val choices: List<String> = emptyList(),
    val correctChoice: Int = -1,
    val pages: List<Int>,
    val topics: List<String>,
    val answerSheet: Boolean = false,
    val officialSolutionUrl: String? = null,
) {
    val subjectKey: String get() = normalizeTopic(subject).ifEmpty { "geral" }
    val prompt: String
        get() = if (parts.isNotEmpty()) {
            (listOf(stem) + parts.map { "${it.label}) ${it.text}" })
                .filter(String::isNotBlank)
                .joinToString("\n\n")
        } else {
            stem
        }

    fun json(): JSONObject = JSONObject()
        .put("code", code)
        .put("subject", subject)
        .put("format", format.wire)
        .put("stem", stem)
        .put("parts", JSONArray(parts.map { it.json() }))
        .put("choices", JSONArray(choices))
        .put("correct_choice", correctChoice)
        .put("pages", JSONArray(pages))
        .put("topics", JSONArray(topics))
        .put("answer_sheet", answerSheet)
        .apply { officialSolutionUrl?.let { put("official_solution_url", it) } }

    companion object {
        fun from(json: JSONObject, legacyOfficial2023: Boolean = false): ExamQuestion {
            val code = json.getString("code")
            val subject = json.optString("subject").ifBlank { legacySubjectForCode(code) }
            val choices = json.optJSONArray("choices")?.strings().orEmpty()
            val format = if (json.has("format")) QuestionFormat.of(json.optString("format"))
            else if (choices.isNotEmpty()) QuestionFormat.Choice else QuestionFormat.Written
            val parts = json.optJSONArray("parts")?.objects(QuestionPart::from).orEmpty()
            val pages = json.optJSONArray("pages")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
            val topics = json.optJSONArray("topics")?.strings().orEmpty()
            val officialUrl = json.optString("official_solution_url").ifBlank { null }
                ?: if (legacyOfficial2023) LegacyFuvest2023.solutionUrl(code) else null
            return ExamQuestion(
                code = code,
                subject = subject,
                format = format,
                stem = json.optString("stem"),
                parts = parts,
                choices = choices,
                correctChoice = json.optInt("correct_choice", -1),
                pages = pages,
                topics = topics,
                answerSheet = json.optBoolean("answer_sheet"),
                officialSolutionUrl = officialUrl,
            )
        }
    }
}

internal data class PastExam(
    val title: String,
    val institution: String = "",
    val year: Int? = null,
    val details: String = "",
    val sourceId: String,
    val questions: List<ExamQuestion>,
) {
    val creditLabel: String get() = institution.ifBlank { title.ifBlank { "Prova" } }
    val shortLabel: String
        get() = listOfNotNull(
            institution.takeIf { it.isNotBlank() },
            year?.toString(),
        ).joinToString(" ").ifBlank { title.ifBlank { "Prova" } }

    val subtitle: String
        get() = listOfNotNull(
            details.takeIf { it.isNotBlank() },
            "${questions.size} ${if (questions.size == 1) "questão" else "questões"}",
        ).joinToString(" · ")

    fun key(question: ExamQuestion) = "$sourceId/${question.code}"

    fun json(): JSONObject = JSONObject()
        .put("title", title)
        .put("institution", institution)
        .apply { year?.let { put("year", it) } }
        .put("details", details)
        .put("source", sourceId)
        .put("questions", JSONArray(questions.map { it.json() }))

    companion object {
        fun from(json: JSONObject): PastExam {
            val year = if (json.has("year") && !json.isNull("year")) json.optInt("year").takeIf { it > 0 } else null
            val official2023 = json.optBoolean("official_2023")
            val institution = json.optString("institution").ifBlank { if (json.has("official_2023")) "FUVEST" else "" }
            val title = json.optString("title").ifBlank {
                if (institution.isNotBlank() && year != null) "$institution $year · Engenharia"
                else institution.ifBlank { "Prova" }
            }
            val details = json.optString("details").ifBlank {
                if (json.has("official_2023")) "2ª fase · 2º dia · Engenharia" else ""
            }
            return PastExam(
                title = title,
                institution = institution,
                year = year,
                details = details,
                sourceId = json.getString("source"),
                questions = json.getJSONArray("questions").objects { ExamQuestion.from(it, official2023) },
            )
        }
    }
}

internal data class ExamReference(val exam: PastExam, val question: ExamQuestion) {
    val key get() = exam.key(question)
    val label get() = "${exam.shortLabel} · ${question.code}"
}

internal data class ExamSubjectGroup(
    val key: String,
    val label: String,
    val questions: List<ExamReference>,
)

internal data class ExamTopic(
    val subjectKey: String,
    val subjectLabel: String,
    val name: String,
    val questions: List<ExamReference>,
) {
    val key get() = topicId(subjectKey, name)
}

internal fun topicId(subject: String, name: String) = "${normalizeTopic(subject).ifEmpty { "geral" }}/${normalizeTopic(name)}"

internal fun normalizeTopic(name: String): String =
    Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

internal val examMathInstructions = """
    Use LaTeX com \(…\) para símbolos e fórmulas curtas e \[…\] para fórmulas longas, funções por partes,
    matrizes e contas em várias linhas. Nas contas em várias linhas, use aligned com & nos sinais de igualdade
    e \\ para mudar de linha. Use \frac para frações, \mathrm para unidades e fórmulas químicas, ^{…} e _{…}
    para cargas, índices e expoentes, \rightarrow para setas de reação e {,} para a vírgula decimal.
    Escreva valores em reais fora da fórmula ou use \$ dentro dela. Feche todos os delimitadores e chaves.
""".trimIndent()

internal data class ExamCatalog(
    val exams: List<PastExam> = emptyList(),
    val lessons: Map<String, ExamLesson> = emptyMap(),
    val journey: StudyJourney = StudyJourney(),
    val progress: Map<String, Progress> = emptyMap(),
) {
    fun allReferences(): List<ExamReference> = exams
        .sortedWith(compareByDescending<PastExam> { it.year ?: 0 }.thenBy { it.title.lowercase(Locale.ROOT) })
        .flatMap { exam -> exam.questions.map { ExamReference(exam, it) } }

    fun subjects(): List<ExamSubjectGroup> {
        val refs = allReferences()
        return refs.groupBy { it.question.subjectKey }.values.map { group ->
            val label = group.first().question.subject.ifBlank { "Geral" }
            ExamSubjectGroup(group.first().question.subjectKey, label, group)
        }.sortedBy { normalizeTopic(it.label) }
    }

    fun topics(subjectKey: String): List<ExamTopic> {
        val normalizedSubject = normalizeTopic(subjectKey)
        val tags = allReferences()
            .filter { it.question.subjectKey == normalizedSubject }
            .flatMap { ref ->
                ref.question.topics.map { it to ref }
            }
        val subjectLabel = tags.firstOrNull()?.second?.question?.subject?.ifBlank { "Geral" } ?: subjectKey
        return tags.groupBy { normalizeTopic(it.first) }.values.map { group ->
            ExamTopic(normalizedSubject, subjectLabel, group.first().first, group.map { it.second }.distinctBy { it.key })
        }.sortedBy { normalizeTopic(it.name) }
    }

    fun allTopics(): List<ExamTopic> = subjects().flatMap { topics(it.key) }

    fun reference(key: String?): ExamReference? =
        if (key == null) null else allReferences().firstOrNull { it.key == key }

    fun progressOf(key: String): Progress = progress[key] ?: Progress()

    fun withProgress(key: String, change: (Progress) -> Progress): ExamCatalog =
        copy(progress = progress + (key to change(progressOf(key))))

    fun officialSolution(reference: ExamReference): String? = reference.question.officialSolutionUrl

    fun json(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("exams", JSONArray(exams.map { it.json() }))
        .put("lessons", JSONObject().apply { lessons.forEach { (key, lesson) -> put(key, lesson.json()) } })
        .put("journey", journey.json())
        .put("progress", JSONObject().apply { progress.forEach { (key, value) -> put(key, value.json()) } })

    companion object {
        const val VERSION = 2

        fun from(json: JSONObject): ExamCatalog {
            val version = json.getInt("version")
            require(version == 1 || version == VERSION)
            val exams = json.getJSONArray("exams").objects(PastExam::from)
            val rawLessons = json.optJSONObject("lessons") ?: JSONObject()
            val lessons = rawLessons.keys().asSequence().associate { key ->
                migrateLegacyTopicKey(key, version) to ExamLesson.from(rawLessons.getJSONObject(key))
            }
            val rawJourney = json.optJSONObject("journey")?.let(StudyJourney::from) ?: StudyJourney()
            val journey = if (version == 1) rawJourney.migrateKeys { migrateLegacyTopicKey(it, 1) } else rawJourney
            val progressMap = LinkedHashMap<String, Progress>()
            json.optJSONObject("progress")?.let { obj ->
                obj.keys().forEach { key ->
                    runCatching { progressMap[key] = Progress.from(obj.getJSONObject(key)) }
                }
            }
            return ExamCatalog(exams, lessons, journey, progressMap)
        }
    }
}

private fun legacySubjectForCode(code: String): String = when (code.firstOrNull()?.uppercaseChar()) {
    'M' -> "Matemática"
    'F' -> "Física"
    'Q' -> "Química"
    else -> "Geral"
}

private fun migrateLegacyTopicKey(key: String, version: Int): String {
    if (version >= ExamCatalog.VERSION || '/' !in key) return key
    val (prefix, topic) = key.split('/', limit = 2)
    val subject = when (prefix) {
        "Math" -> "Matemática"
        "Physics" -> "Física"
        "Chemistry" -> "Química"
        else -> prefix
    }
    return topicId(subject, topic)
}

/** Single writer for imported past exams, lessons, progress and journey state. */
internal class ExamStore(file: File, private val legacyFile: File? = null) {
    private val disk = AtomicFile(file)
    private val mutex = Mutex()
    var catalog by mutableStateOf<ExamCatalog?>(null)
        private set

    suspend fun load() = mutex.withLock {
        if (catalog == null) {
            catalog = withContext(Dispatchers.IO) {
                try {
                    ExamCatalog.from(JSONObject(String(disk.readFully(), Charsets.UTF_8)))
                } catch (error: java.io.FileNotFoundException) {
                    if (disk.baseFile.exists() || File(disk.baseFile.path + ".bak").exists()) throw error
                    val legacy = legacyFile?.takeIf { it.exists() || File(it.path + ".bak").exists() }
                    if (legacy != null) {
                        val migrated = ExamCatalog.from(JSONObject(String(AtomicFile(legacy).readFully(), Charsets.UTF_8)))
                        writeBytes(migrated.json().toString().toByteArray(Charsets.UTF_8))
                        migrated
                    } else {
                        ExamCatalog()
                    }
                }
            }
        }
    }

    suspend fun update(change: (ExamCatalog) -> ExamCatalog) = mutex.withLock {
        val current = catalog ?: run {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { ExamCatalog.from(JSONObject(String(disk.readFully(), Charsets.UTF_8))) }.getOrElse { ExamCatalog() }
            }
            catalog = loaded
            loaded
        }
        val next = change(current)
        if (next == current) return@withLock
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                writeBytes(next.json().toString().toByteArray(Charsets.UTF_8))
            }
            catalog = next
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        disk.baseFile.parentFile?.mkdirs()
        val output = disk.startWrite()
        try {
            output.write(bytes)
            disk.finishWrite(output)
        } catch (error: Exception) {
            disk.failWrite(output)
            throw error
        }
    }
}

internal fun <T> JSONArray.objects(read: (JSONObject) -> T) = List(length()) { read(getJSONObject(it)) }
internal fun JSONArray.strings() = List(length()) { getString(it) }
