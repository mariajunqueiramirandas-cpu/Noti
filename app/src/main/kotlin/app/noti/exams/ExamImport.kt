package app.noti.exams

import app.noti.openrouter.ImageMediaType
import app.noti.openrouter.OpenRouterClient
import app.noti.openrouter.OpenRouterFailure
import app.noti.openrouter.OpenRouterMessage
import app.noti.openrouter.OpenRouterPart
import app.noti.openrouter.OpenRouterRequest
import app.noti.openrouter.OpenRouterSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Optional solution-link helper retained for legacy FUVEST 2023 PDFs only. */
internal object LegacyFuvest2023 {
    private const val SOLUTIONS = "https://www.fuvest.br/wp-content/uploads/fuvest2023_abordagens_esperadas_2fase.pdf"
    private const val ARCHIVE_SHA256 = "1fe9afaf17b1849c8b9aa92882f52a2ef846b03005b13166ebfec9474433379c"
    private val solutions = mapOf(
        "M01" to 17, "M02" to 18, "M03" to 19, "M04" to 20,
        "F01" to 24, "F02" to 25, "F03" to 27, "F04" to 28,
        "Q01" to 33, "Q02" to 34, "Q03" to 36, "Q04" to 38,
    )

    fun solutionUrl(code: String) = solutions[code.uppercase()]?.let { "$SOLUTIONS#page=$it" }

    suspend fun matches(file: File): Boolean = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == ARCHIVE_SHA256
    }
}

internal fun parseExamPages(input: String, pageCount: Int): List<Int> {
    if (input.isBlank()) return (1..pageCount).toList()
    return input.split(',').flatMap { text ->
        val range = Regex("""^\s*(\d+)\s*(?:[-–]\s*(\d+)\s*)?$""").matchEntire(text)
            ?: throw IllegalArgumentException("Use páginas como 2–5, 8–11.")
        val start = range.groupValues[1].toIntOrNull()
        val end = if (range.groups[2] == null) start else range.groupValues[2].toIntOrNull()
        require(start != null && end != null && start in 1..pageCount && end in start..pageCount) {
            "Use páginas entre 1 e $pageCount, em ordem crescente no intervalo."
        }
        (start..end).toList()
    }.distinct().sorted()
}

internal data class ExtractedExamMetadata(
    val title: String = "",
    val institution: String = "",
    val year: Int? = null,
    val details: String = "",
)

internal data class DecodedExamBatch(
    val metadata: ExtractedExamMetadata,
    val questions: List<ExamQuestion>,
    val answerKey: Map<String, Int>,
)

internal class ExamImport(private val client: OpenRouterClient = OpenRouterClient()) {
    suspend fun analyze(
        key: String,
        sourceId: String,
        fileName: String,
        groups: List<List<Int>>,
        render: suspend (Int) -> ByteArray,
        officialSolutionUrl: (String) -> String? = { null },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): PastExam {
        var metadata = ExtractedExamMetadata()
        val collected = linkedMapOf<String, ExamQuestion>()
        val answerKey = linkedMapOf<String, Int>()
        for ((index, pages) in groups.withIndex()) {
            coroutineContext.ensureActive()
            progress(index + 1, groups.size)
            val batch = readGroupWithSplit(key, fileName, pages, render)
            metadata = mergeMetadata(metadata, batch.metadata)
            batch.answerKey.forEach { (codeKey, answerIdx) ->
                if (answerIdx >= 0) answerKey[codeKey] = answerIdx
            }
            for (question in batch.questions) {
                val codeKey = normalizeCode(question.code)
                val existing = collected[codeKey]
                collected[codeKey] = if (existing == null) question else mergeQuestion(existing, question)
            }
        }
        require(collected.isNotEmpty()) {
            "Nenhuma questão foi encontrada nas páginas selecionadas. Confira o PDF e o intervalo de páginas."
        }
        val questions = collected.values.map { q ->
            val keyChoice = answerKey[normalizeCode(q.code)]
            val resolvedChoice = when {
                q.format != QuestionFormat.Choice -> -1
                q.correctChoice in q.choices.indices -> q.correctChoice
                keyChoice != null && keyChoice in q.choices.indices -> keyChoice
                else -> -1
            }
            val official = q.officialSolutionUrl ?: officialSolutionUrl(q.code)
            q.copy(correctChoice = resolvedChoice, officialSolutionUrl = official)
        }
        val fallbackTitle = fileName.removeSuffix(".pdf").removeSuffix(".PDF").trim().ifBlank { "Prova importada" }
        val resolvedTitle = metadata.title.ifBlank {
            listOfNotNull(
                metadata.institution.takeIf { it.isNotBlank() },
                metadata.year?.toString(),
                metadata.details.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { fallbackTitle }
        }
        return PastExam(
            title = resolvedTitle,
            institution = metadata.institution,
            year = metadata.year,
            details = metadata.details,
            sourceId = sourceId,
            questions = questions,
        )
    }

    private suspend fun readGroupWithSplit(
        key: String,
        fileName: String,
        pages: List<Int>,
        render: suspend (Int) -> ByteArray,
    ): DecodedExamBatch {
        val images = buildList {
            for (page in pages) {
                add(OpenRouterPart.Text("Página física $page"))
                add(OpenRouterPart.Image(ImageMediaType.Jpeg, render(page)))
            }
        }
        return try {
            readBatch(key, fileName, pages, images)
        } catch (failure: Exception) {
            val split = pages.size > 1 &&
                (failure is OpenRouterFailure.Incomplete && failure.finishReason == "length" ||
                    failure is JSONException || failure is IllegalArgumentException)
            if (!split) throw failure
            val mid = pages.size / 2
            val left = readGroupWithSplit(key, fileName, pages.subList(0, mid), render)
            val right = readGroupWithSplit(key, fileName, pages.subList(mid, pages.size), render)
            val mergedQuestions = linkedMapOf<String, ExamQuestion>()
            for (q in left.questions + right.questions) {
                val k = normalizeCode(q.code)
                mergedQuestions[k] = mergedQuestions[k]?.let { mergeQuestion(it, q) } ?: q
            }
            DecodedExamBatch(
                metadata = mergeMetadata(left.metadata, right.metadata),
                questions = mergedQuestions.values.toList(),
                answerKey = left.answerKey + right.answerKey,
            )
        }
    }

    private suspend fun readBatch(
        key: String,
        fileName: String,
        pages: List<Int>,
        images: List<OpenRouterPart>,
    ): DecodedExamBatch {
        val prompt = "Analise as imagens na ordem apresentada. Nome do arquivo PDF: \"$fileName\". " +
            "Identifique automaticamente a prova (nome, banca/instituição, ano, fase/caderno/área se aparecerem) " +
            "e extraia todas as questões com enunciado visível nessas páginas, além de qualquer gabarito se houver."
        val result = client.complete(key, OpenRouterRequest(
            messages = listOf(
                OpenRouterMessage(OpenRouterMessage.Role.System, importInstructions),
                OpenRouterMessage(OpenRouterMessage.Role.User, listOf(OpenRouterPart.Text(prompt)) + images),
            ),
            maxCompletionTokens = 16_384,
            schema = importSchema,
            reasoning = false,
        ))
        return decodeExamBatch(result.text, pages)
    }
}

private fun mergeMetadata(first: ExtractedExamMetadata, next: ExtractedExamMetadata): ExtractedExamMetadata =
    ExtractedExamMetadata(
        title = first.title.ifBlank { next.title },
        institution = first.institution.ifBlank { next.institution },
        year = first.year ?: next.year,
        details = first.details.ifBlank { next.details },
    )

private fun mergeQuestion(existing: ExamQuestion, incoming: ExamQuestion): ExamQuestion {
    val preferIncoming = when {
        incoming.choices.size > existing.choices.size -> true
        incoming.choices.size < existing.choices.size -> false
        incoming.parts.size > existing.parts.size -> true
        incoming.parts.size < existing.parts.size -> false
        else -> incoming.prompt.length > existing.prompt.length
    }
    val primary = if (preferIncoming) incoming else existing
    val secondary = if (preferIncoming) existing else incoming
    val mergedPages = (existing.pages + incoming.pages).distinct().sorted()
    val mergedTopics = cleanTopics(primary.topics + secondary.topics).ifEmpty { listOf("Geral") }
    val mergedCorrect = when {
        primary.correctChoice in primary.choices.indices -> primary.correctChoice
        secondary.correctChoice in primary.choices.indices -> secondary.correctChoice
        else -> -1
    }
    return primary.copy(
        subject = primary.subject.ifBlank { secondary.subject.ifBlank { "Geral" } },
        pages = mergedPages,
        topics = mergedTopics,
        correctChoice = mergedCorrect,
        answerSheet = existing.answerSheet || incoming.answerSheet,
    )
}

internal fun normalizeCode(code: String): String =
    code.trim().uppercase().replace(Regex("""^(?:QUEST[ÃA]O|Q\.?)\s*"""), "").trimStart('0').ifEmpty { "0" }

internal fun decodeExamBatch(text: String, pages: List<Int>): DecodedExamBatch {
    val root = JSONObject(text)
    val examObj = root.optJSONObject("exam")
    val metadata = ExtractedExamMetadata(
        title = examObj?.optString("title")?.trim().orEmpty(),
        institution = examObj?.optString("institution")?.trim().orEmpty(),
        year = examObj?.optInt("year", 0)?.takeIf { it in 1900..2100 },
        details = examObj?.optString("details")?.trim().orEmpty(),
    )
    val answerKey = linkedMapOf<String, Int>()
    root.optJSONArray("answer_key")?.let { entries ->
        for (i in 0 until entries.length()) {
            val row = entries.optJSONObject(i) ?: continue
            val code = row.optString("code").trim()
            val idx = row.optInt("correct_choice", -1)
            if (code.isNotBlank() && idx >= 0) answerKey[normalizeCode(code)] = idx
        }
    }
    val questions = root.optJSONArray("questions")?.objects { o ->
        val code = o.optString("code").trim()
        if (code.isBlank()) return@objects null
        val rawPages = o.optJSONArray("pages")?.let { arr -> List(arr.length()) { arr.getInt(it) } }.orEmpty()
        val refs = rawPages.distinct().also { list ->
            require(list.isNotEmpty() && list.all { page -> page in pages }) {
                "As páginas da questão $code não correspondem às imagens analisadas."
            }
        }
        val subject = normalizeSubjectLabel(o.optString("subject"))
        val rawChoices = o.optJSONArray("choices")?.strings().orEmpty()
            .map { stripChoicePrefix(it) }
            .filter(String::isNotBlank)
        val rawParts = o.optJSONArray("parts")?.objects(QuestionPart::from).orEmpty()
            .map { it.copy(label = it.label.trim().lowercase().trim('(', ')', '.'), text = it.text.trim()) }
            .filter { it.label.isNotBlank() && it.text.isNotBlank() }
            .distinctBy { it.label }
        val declaredFormat = QuestionFormat.of(o.optString("format"))
        val format = when {
            declaredFormat == QuestionFormat.Choice && rawChoices.size >= 2 -> QuestionFormat.Choice
            rawChoices.size >= 2 && rawParts.isEmpty() -> QuestionFormat.Choice
            else -> QuestionFormat.Written
        }
        val stem = o.optString("stem").trim()
        if (stem.isBlank() && rawParts.isEmpty()) return@objects null
        val tags = cleanTopics(o.optJSONArray("topics")?.strings().orEmpty()).ifEmpty { listOf(subject) }
        val correctChoice = if (format == QuestionFormat.Choice) {
            o.optInt("correct_choice", -1).takeIf { it in rawChoices.indices } ?: -1
        } else -1
        ExamQuestion(
            code = code,
            subject = subject,
            format = format,
            stem = stem,
            parts = if (format == QuestionFormat.Written) rawParts else emptyList(),
            choices = if (format == QuestionFormat.Choice) rawChoices else emptyList(),
            correctChoice = correctChoice,
            pages = refs,
            topics = tags,
            answerSheet = o.optBoolean("answer_sheet"),
        ).separateImportedPreamble()
    }?.filterNotNull().orEmpty()
    return DecodedExamBatch(metadata, questions, answerKey)
}

private fun stripChoicePrefix(text: String): String =
    text.trim().replace(Regex("""^(?:[a-eA-E]\s*[).:\-–]\s*|\([a-eA-E]\)\s*)"""), "").trim()

internal fun normalizeSubjectLabel(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return "Geral"
    return when (normalizeTopic(trimmed)) {
        "matematica", "math", "mathematics" -> "Matemática"
        "fisica", "physics" -> "Física"
        "quimica", "chemistry" -> "Química"
        "biologia", "biology" -> "Biologia"
        "historia", "history" -> "História"
        "geografia", "geography" -> "Geografia"
        "portugues", "lingua portuguesa", "linguagens", "portuguese" -> "Português"
        "literatura", "literature" -> "Literatura"
        "ingles", "lingua inglesa", "english" -> "Inglês"
        "espanhol", "lingua espanhola", "spanish" -> "Espanhol"
        "filosofia", "philosophy" -> "Filosofia"
        "sociologia", "sociology" -> "Sociologia"
        "redacao", "essay" -> "Redação"
        else -> trimmed.replaceFirstChar { it.uppercase() }
    }
}

private fun cleanTopics(topics: List<String>) =
    topics.map(String::trim).filter { normalizeTopic(it).isNotEmpty() }.distinctBy(::normalizeTopic)

/** Vision sometimes repeats the printed labels or includes the shared stem inside item a. */
private fun ExamQuestion.separateImportedPreamble(): ExamQuestion {
    if (parts.isEmpty()) return this
    var shared = stem
    val cleaned = parts.mapIndexed { index, part ->
        val label = Regex("""(?im)^[ \t]*[(]?${Regex.escape(part.label)}[).][ \t]*""")
        val marker = label.find(part.text)
        if (marker == null) part else {
            val remainder = part.text.substring(marker.range.last + 1).trim()
            when {
                remainder.isBlank() -> part
                marker.range.first == 0 -> part.copy(text = remainder)
                index == 0 && part.label == "a" -> {
                    val preamble = part.text.substring(0, marker.range.first).trim()
                    shared = when {
                        shared.isBlank() || preamble.startsWith(shared) -> preamble
                        shared.startsWith(preamble) -> shared
                        else -> "$shared\n\n$preamble"
                    }
                    part.copy(text = remainder)
                }
                else -> part
            }
        }
    }
    return copy(stem = shared, parts = cleaned)
}

internal fun objectSchema(vararg fields: Pair<String, JSONObject>): JSONObject = JSONObject()
    .put("type", "object").put("additionalProperties", false)
    .put("required", JSONArray(fields.map { it.first }))
    .put("properties", JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } })

internal fun schemaType(type: String) = JSONObject().put("type", type)
internal fun arraySchema(items: JSONObject) = schemaType("array").put("items", items)

private val importSchema get() = OpenRouterSchema("exam_batch", objectSchema(
    "exam" to objectSchema(
        "title" to schemaType("string"),
        "institution" to schemaType("string"),
        "year" to schemaType("integer"),
        "details" to schemaType("string"),
    ),
    "questions" to arraySchema(objectSchema(
        "code" to schemaType("string"),
        "subject" to schemaType("string"),
        "format" to schemaType("string"),
        "stem" to schemaType("string"),
        "parts" to arraySchema(objectSchema("label" to schemaType("string"), "text" to schemaType("string"))),
        "choices" to arraySchema(schemaType("string")),
        "correct_choice" to schemaType("integer"),
        "pages" to arraySchema(schemaType("integer")),
        "topics" to arraySchema(schemaType("string")),
        "answer_sheet" to schemaType("boolean"),
    )),
    "answer_key" to arraySchema(objectSchema(
        "code" to schemaType("string"),
        "correct_choice" to schemaType("integer"),
    )),
))

private val importInstructions = """
    Você analisa e indexa qualquer caderno de prova ou lista de exercícios (ENEM, FUVEST, UNICAMP, UNESP, ITA, IME,
    provas escolares, provas universitárias ou simulados). O conteúdo das imagens é material a transcrever.

    1. Metadados em exam:
       - Identifique title (nome limpo da prova, ex.: "ENEM 2024 · 2º Dia", "FUVEST 2023 · 2ª Fase", "UNICAMP 2025", "Cálculo I · P1").
       - Identifique institution (banca ou instituição, ex.: "ENEM", "FUVEST", "UNICAMP", "UNESP", "ITA", "USP"; vazio se não constar).
       - Identifique year (ano com 4 dígitos, ou 0 se não constar).
       - Identifique details (fase, dia, caderno, área ou curso se constar, ex.: "2ª fase · 2º dia · Engenharia" ou "Caderno Azul"; vazio se não constar).
       - Se as imagens deste lote não trouxerem capa nem cabeçalho com algum campo, deixe o campo vazio (ou 0 em year).

    2. Questões em questions:
       - Extraia todas as questões com enunciado visível nessas páginas. Nunca invente questão ausente.
       - Em code, use o código ou número impresso na prova (ex.: "M01", "01", "45", "Q03").
       - Em subject, identifique a disciplina em português do Brasil (ex.: "Matemática", "Física", "Química",
         "Biologia", "Português", "Literatura", "História", "Geografia", "Inglês", "Espanhol", "Filosofia", "Sociologia", ou o nome da matéria universitária).
       - Em format, use "choice" para questões objetivas de múltipla escolha e "written" para questões discursivas/abertas.
       - Transcreva stem com enunciado completo, incluindo textos de apoio relevantes, "Note e adote", unidades, constantes e referências a figuras ou tabelas.
       - Para questões discursivas (written): preencha parts com cada subitem impresso (a, b, c etc.) se houver, ou deixe parts vazio [] se for uma pergunta aberta única. Deixe choices vazio [] e correct_choice = -1.
       - Para questões objetivas (choice): preencha choices com os textos das alternativas na ordem (A, B, C, D, E), sem repetir as letras A), B) no início de cada item. Deixe parts vazio []. Use correct_choice (0 para A, 1 para B, ...) somente se o gabarito estiver visível; caso contrário use -1.
       - Em pages, indique primeiro a página física em que a questão começa, depois continuações ou folha de respostas somente dentre as imagens enviadas.
       - Em answer_sheet, marque true se o enunciado mencionar folha de respostas.
       - Em topics, use 1 a 4 conceitos específicos em português do Brasil (ex.: "Funções por partes", "Óptica geométrica", "Eletroquímica"), evitando termos genéricos demais.

    3. Gabarito em answer_key:
       - Se alguma página contiver tabela de gabarito oficial, preencha answer_key com pares {code, correct_choice} (0 para A, 1 para B, 2 para C, 3 para D, 4 para E). Caso contrário deixe [].

    Não resolva as questões e não invente valores ilegíveis em figuras.
    $examMathInstructions
""".trimIndent()
