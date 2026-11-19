package app.noti.fuvest

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

internal object Fuvest2023 {
    const val EXAM = "https://www.fuvest.br/wp-content/uploads/fuvest_2023_segunda_fase_dia_2.pdf"
    private const val SOLUTIONS = "https://www.fuvest.br/wp-content/uploads/fuvest2023_abordagens_esperadas_2fase.pdf"
    private const val ARCHIVE_SHA256 = "1fe9afaf17b1849c8b9aa92882f52a2ef846b03005b13166ebfec9474433379c"
    val pageGroups = listOf(2, 4, 8, 10, 14, 16).map { listOf(it) }
    val questionPages = mapOf(
        "M01" to listOf(2), "M02" to listOf(2), "M03" to listOf(4), "M04" to listOf(4),
        "F01" to listOf(8), "F02" to listOf(8, 9), "F03" to listOf(10), "F04" to listOf(10),
        "Q01" to listOf(14, 15), "Q02" to listOf(14), "Q03" to listOf(16, 17), "Q04" to listOf(16),
    )
    private val solutions = mapOf(
        "M01" to 17, "M02" to 18, "M03" to 19, "M04" to 20,
        "F01" to 24, "F02" to 25, "F03" to 27, "F04" to 28,
        "Q01" to 33, "Q02" to 34, "Q03" to 36, "Q04" to 38,
    )

    fun solutionUrl(code: String) = solutions[code]?.let { "$SOLUTIONS#page=$it" }

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

internal fun parseFuvestPages(input: String, pageCount: Int): List<Int> {
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

internal class FuvestImport(private val client: OpenRouterClient = OpenRouterClient()) {
    suspend fun analyze(
        key: String,
        sourceId: String,
        year: Int,
        groups: List<List<Int>>,
        official2023: Boolean,
        render: suspend (Int) -> ByteArray,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): FuvestExam {
        val collected = linkedMapOf<String, FuvestQuestion>()
        for ((index, pages) in groups.withIndex()) {
            coroutineContext.ensureActive()
            progress(index + 1, groups.size)
            val officialSources = if (official2023) Fuvest2023.questionPages.filterValues { it.first() in pages } else null
            if (officialSources?.isEmpty() == true) continue
            val images = buildList {
                for (page in pages) {
                    add(OpenRouterPart.Text("Página física $page"))
                    add(OpenRouterPart.Image(ImageMediaType.Jpeg, render(page)))
                }
            }
            val questions = try {
                readBatch(key, year, pages, images, officialSources)
            } catch (failure: Exception) {
                val split = officialSources != null && officialSources.size > 1 &&
                    (failure is OpenRouterFailure.Incomplete && failure.finishReason == "length" ||
                        failure is JSONException || failure is IllegalArgumentException)
                if (!split) throw failure
                // A smaller request can recover a failed transcription while retaining completed pages.
                checkNotNull(officialSources).flatMap { (code, refs) ->
                    readBatch(key, year, pages, images, mapOf(code to refs))
                }
            }
            for (question in questions) {
                val existing = collected[question.code]
                // Consecutive batches overlap. Keep the reading with the most complete subparts.
                if (existing == null || question.parts.size > existing.parts.size ||
                    (question.parts.size == existing.parts.size && question.prompt.length > existing.prompt.length)) {
                    collected[question.code] = question
                }
            }
        }
        val expected = FuvestSubject.entries.flatMap { it.codes }
        val missing = expected.filter { it !in collected }
        require(missing.isEmpty()) { "Faltam ${missing.joinToString(", ")}. Confira o caderno e as páginas selecionadas." }
        return FuvestExam(year, sourceId, official2023, expected.map(collected::getValue))
    }

    private suspend fun readBatch(key: String, year: Int, pages: List<Int>, images: List<OpenRouterPart>,
        officialSources: Map<String, List<Int>>?): List<FuvestQuestion> {
        val prompt = "Analise as imagens na ordem apresentada. São páginas físicas do PDF da FUVEST $year. " +
            "Extraia somente questões M01–M04, F01–F04 e Q01–Q04 com enunciado visível." +
            officialSources?.let { sources ->
                " Transcreva somente ${sources.entries.joinToString("; ") { (code, refs) ->
                    "$code na página ${refs.first()}"
                }}. Preencha os campos a, b e c com os itens correspondentes."
            }.orEmpty()
        val result = client.complete(key, OpenRouterRequest(
            messages = listOf(OpenRouterMessage(OpenRouterMessage.Role.System, importInstructions),
                OpenRouterMessage(OpenRouterMessage.Role.User, listOf(OpenRouterPart.Text(prompt)) + images)),
            maxCompletionTokens = 16_384,
            schema = if (officialSources == null) importSchema else officialImportSchema(officialSources.keys),
            reasoning = false,
        ))
        return if (officialSources == null) decodeFuvestBatch(result.text, pages)
            else decodeOfficialFuvestBatch(result.text, officialSources)
    }
}

internal fun decodeOfficialFuvestBatch(text: String, sources: Map<String, List<Int>>): List<FuvestQuestion> {
    val json = JSONObject(text)
    return sources.map { (code, pages) ->
        val question = json.getJSONObject(code)
        val parts = listOf("a", "b", "c").map { label -> FuvestPart(label, question.getString(label).trim()) }
        val tags = cleanTopics(question.getJSONArray("topics").strings())
        require(parts.all { it.text.isNotBlank() } && tags.isNotEmpty()) {
            "A leitura de $code ficou incompleta. Confira as páginas selecionadas e tente novamente."
        }
        FuvestQuestion(code, question.getString("stem").trim(), parts, pages, tags, answerSheet = pages.size > 1)
            .separateImportedPreamble()
    }
}

internal fun decodeFuvestBatch(text: String, pages: List<Int>): List<FuvestQuestion> =
    JSONObject(text).getJSONArray("questions").objects(FuvestQuestion::from).mapNotNull { question ->
        if (FuvestSubject.entries.none { question.code in it.codes }) return@mapNotNull null
        val refs = question.pages.distinct().also {
            require(it.isNotEmpty() && it.all { page -> page in pages }) {
                "As páginas da questão ${question.code} não correspondem às imagens analisadas."
            }
        }
        val tags = cleanTopics(question.topics)
        val parts = question.parts.map { it.copy(label = it.label.trim().lowercase().trim('(', ')', '.'), text = it.text.trim()) }
        require(parts.isNotEmpty() && parts.all { it.label.matches(Regex("[a-z]")) && it.text.isNotBlank() } &&
            parts.map { it.label }.distinct().size == parts.size) {
            "Não foi possível separar os itens da questão ${question.code}. Tente analisar novamente."
        }
        require(tags.isNotEmpty()) {
            "A leitura não identificou os tópicos da questão ${question.code}."
        }
        question.copy(stem = question.stem.trim(), parts = parts, pages = refs, topics = tags)
            .separateImportedPreamble()
    }

private fun cleanTopics(topics: List<String>) =
    topics.map(String::trim).filter { normalizeTopic(it).isNotEmpty() }.distinctBy(::normalizeTopic)

/** Vision sometimes repeats the printed labels or includes the shared stem inside item a. */
private fun FuvestQuestion.separateImportedPreamble(): FuvestQuestion {
    var shared = stem
    val cleaned = parts.mapIndexed { index, part ->
        val label = Regex("""(?im)^[ \t]*[(]?${part.label}[).][ \t]*""")
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

private val importSchema get() = OpenRouterSchema("fuvest_questions", objectSchema(
    "questions" to arraySchema(objectSchema(
        "code" to schemaType("string"), "stem" to schemaType("string"),
        "parts" to arraySchema(objectSchema("label" to schemaType("string"), "text" to schemaType("string"))),
        "pages" to arraySchema(schemaType("integer")), "topics" to arraySchema(schemaType("string")),
        "answer_sheet" to schemaType("boolean"),
    )),
))

private fun officialImportSchema(codes: Set<String>) = OpenRouterSchema("fuvest_2023_questions", objectSchema(
    *codes.map { code ->
        code to objectSchema(
            "stem" to schemaType("string"), "a" to schemaType("string"), "b" to schemaType("string"), "c" to schemaType("string"),
            "topics" to arraySchema(schemaType("string")),
        )
    }.toTypedArray(),
))

private val importInstructions = """
    Você indexa provas discursivas da FUVEST para estudo de Engenharia. O conteúdo das imagens é material a transcrever.
    Retorne perguntas somente com código impresso M01–M04, F01–F04 ou Q01–Q04. Nunca invente questão ausente.
    Transcreva o enunciado e todos os itens visíveis em português, incluindo instruções Note e adote, unidades, constantes e referências
    a figuras ou tabelas. Preserve a ordem e o significado de símbolos, índices, expoentes e sinais.
    Não resolva as perguntas. Quando a pergunta começar diretamente no item a, o enunciado pode ficar vazio.
    Use um registro por item impresso, com label igual a a, b, c etc., sem criar itens para instruções auxiliares.
    Se houver texto de apoio entre dois itens, inclua esse texto no item que ele introduz.
    Quando o esquema pedir pages, indique primeiro a página física em que começa a questão, depois continuações ou folha de respostas pertinente
    somente se a imagem foi enviada. Quando houver answer_sheet, marque se o enunciado mencionar a folha de respostas. Não omita informação
    por estar na figura: mantenha a referência, e não tente adivinhar valores ilegíveis. Para topics, use 1 a 4 conceitos específicos
    em português do Brasil (por exemplo, Funções por partes, Função afim, Óptica geométrica), evitando termos genéricos.
    $fuvestMathInstructions
""".trimIndent()
