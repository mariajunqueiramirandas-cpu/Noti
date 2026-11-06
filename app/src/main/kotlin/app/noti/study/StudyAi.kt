package app.noti.study

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What to make. [pages] is 1-based and inclusive, or null for the whole PDF. */
data class DeckRequest(
    val kinds: Set<ItemKind> = ItemKind.entries.toSet(),
    val count: Int = 15,
    /** 1 review · 2 exam · 3 hard. */
    val level: Int = 2,
    val pages: IntRange? = null,
    /** Optional: what to concentrate on ("rotational motion", "chapter 3"). */
    val focus: String = "",
)

/**
 * The prompts, schemas and parsing for study material, independent of the network
 * (see [StudyAi] for the calls). Math and physics only, for now.
 */
object StudyPrompts {
    private fun str() = JSONObject().put("type", "string")
    private fun int() = JSONObject().put("type", "integer")
    private fun arr(of: JSONObject) = JSONObject().put("type", "array").put("items", of)
    private fun obj(vararg props: Pair<String, JSONObject>) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(props.map { it.first }))
        .put("additionalProperties", false)

    /** Items come first, so they can be shown while the rest is still being written. */
    fun deckSchema(): JSONObject = obj(
        "items" to arr(obj(
            "kind" to str().put("enum", JSONArray(ItemKind.entries.map { it.wire })),
            "topic" to str(),
            "prompt" to str(),
            "choices" to arr(str()),
            "correct_choice" to int(),
            "answer" to str(),
            "solution" to arr(str()),
            "hints" to arr(str()),
            "pages" to arr(int()),
            "excerpt" to str(),
            "difficulty" to int(),
        )),
    )

    fun gradeSchema(): JSONObject = obj(
        "read" to str(),
        "verdict" to str().put("enum", JSONArray(Verdict.entries.map { it.wire })),
        "score" to JSONObject().put("type", "number"),
        "feedback" to str(),
        "steps" to arr(obj("ok" to JSONObject().put("type", "boolean"), "note" to str())),
    )

    private val MATH = """Write every piece of math in LaTeX between single dollar signs (${'$'}…${'$'}) inline, or double dollar signs (${'$'}${'$'}…${'$'}${'$'}) for an equation on its own line. Use standard commands only: \frac, \sqrt, ^, _, \vec, \hat, \cdot, \times, \int, \sum, \lim, \left( \right), \begin{pmatrix}, \text, \mathrm. Write units upright with a thin space, like ${'$'}9.8\,\mathrm{m\,s^{-2}}${'$'}. Never use Markdown headings, tables or code blocks."""

    val deckSystem = """You are a careful, encouraging tutor in mathematics and physics. You turn course material into study items for a student who answers by hand on a tablet.

Rules:
- Use only what the material teaches: its definitions, notation, results and worked examples. Do not invent facts.
- Every item is self-contained: give all values, units and assumptions the student needs.
- Prefer items that make the student think and calculate over items that test memorized wording.
- "short": a question with a short final answer (a number with units, an expression, or one sentence).
- "choice": exactly 4 options in "choices" and the index (0-3) of the right one in "correct_choice". Wrong options are plausible mistakes (sign errors, wrong formula, unit slips). Leave "choices" empty and "correct_choice" -1 for every other kind.
- "problem": a multi-step problem whose full solution is 3 to 6 steps.
- "flash": "prompt" is the front (a term, law or formula to recall), "answer" is the back.
- "answer" is the final answer only. "solution" is the worked solution, one step per entry (empty for flash cards).
- "hints": 3 hints, from a gentle nudge to nearly the answer, never the answer itself (empty for flash cards).
- "pages": the page numbers of the material (1 = its first page) where this is taught.
- "excerpt": up to 300 characters stating what the material says that settles the answer (key formula or statement).
- "topic": a short topic name (2 to 4 words) shared by items on the same topic.
- "difficulty": 1 (review), 2 (exam level) or 3 (hard).
- Write in the same language as the material.
$MATH"""

    fun deckUser(req: DeckRequest, from: String): String = buildString {
        append("Make ${req.count.coerceIn(1, 60)} study items from $from.\n")
        val kinds = req.kinds.ifEmpty { ItemKind.entries.toSet() }
        if (kinds.size == ItemKind.entries.size) append("Make a mix: about 40% short questions, 25% multiple choice, 20% problems and 15% flash cards.\n")
        else append("Only make these kinds: ${kinds.joinToString(", ") { it.wire }}.\n")
        append(when (req.level.coerceIn(1, 3)) {
            1 -> "Level: review. Check understanding of definitions and direct use of the main formulas.\n"
            2 -> "Level: exam. Typical exam questions and problems that take a few steps.\n"
            else -> "Level: hard. Challenging items that combine several ideas.\n"
        })
        if (req.focus.isNotBlank()) append("Concentrate on: ${req.focus.trim().take(200)}.\n")
        append("Cover the material evenly, in the order it is taught.")
    }

    val gradeSystem = """You check a student's handwritten answer to a mathematics or physics question. The image shows what they wrote on a tablet.

Rules:
- First transcribe what they wrote into "read", math in LaTeX.
- Judge the mathematics and physics, not the handwriting. Accept equivalent forms, rearrangements, exact or sensibly rounded values. Units must be right when the question has units.
- "verdict": "correct" if the final answer is right and the reasoning holds; "partial" for the right method with a slip, or a right answer with a real gap; "incorrect" otherwise. If nothing readable is there, say so and use "incorrect".
- "score": 0 to 1.
- "feedback": one to three short, kind, specific sentences addressed to the student ("you"). Point at the first mistake and how to fix it. Don't just restate the full solution.
- "steps": one entry per step they wrote, in order, "ok" false where it goes wrong, with a short note.
- Write in the same language as the question.
$MATH"""

    fun gradeUser(item: StudyItem): String = buildString {
        append("Question (${item.kind.label}):\n${item.prompt}\n\n")
        if (item.kind == ItemKind.Choice) item.choices.forEachIndexed { i, c -> append("${'A' + i}. $c\n") }
        append("Expected answer: ${item.answer}\n")
        if (item.solution.isNotEmpty()) append("Model solution:\n" + item.solution.joinToString("\n") { "- $it" } + "\n")
        if (item.excerpt.isNotBlank()) append("From the course material: ${item.excerpt}\n")
        append("\nCheck the student's answer in the image.")
    }

    /**
     * An item from the model, validated: null if unusable. Page numbers of an excerpt
     * starting at [firstPage] are mapped back to the source and dropped if out of range.
     */
    fun item(o: JSONObject, sourceId: String?, firstPage: Int, pageCount: Int, now: Long): StudyItem? {
        val prompt = o.optString("prompt").trim()
        if (prompt.isEmpty()) return null
        var kind = ItemKind.of(o.optString("kind"))
        val choices = o.optJSONArray("choices").strings().map { it.trim() }.filter { it.isNotEmpty() }
        var correct = o.optInt("correct_choice", -1)
        if (kind == ItemKind.Choice && (choices.size < 2 || correct !in choices.indices)) {
            // Options that don't add up: keep the question, without them.
            kind = ItemKind.Short
        }
        if (kind != ItemKind.Choice) correct = -1
        val answer = o.optString("answer").trim().ifEmpty { if (kind == ItemKind.Choice) choices[correct] else "" }
        if (answer.isEmpty()) return null
        val pages = o.optJSONArray("pages").ints().map { it + firstPage - 1 }
            .filter { pageCount <= 0 || it in 1..pageCount }.distinct().sorted().take(6)
        return StudyItem(
            id = UUID.randomUUID().toString(),
            kind = kind,
            topic = o.optString("topic").trim().take(60).ifEmpty { "General" },
            prompt = prompt,
            choices = if (kind == ItemKind.Choice) choices.take(6) else emptyList(),
            correctChoice = correct,
            answer = answer,
            solution = if (kind == ItemKind.Flash) emptyList() else o.optJSONArray("solution").strings().map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            hints = if (kind == ItemKind.Flash) emptyList() else o.optJSONArray("hints").strings().map { it.trim() }.filter { it.isNotEmpty() }.take(3),
            sourceId = sourceId,
            pages = pages,
            excerpt = o.optString("excerpt").trim().take(600),
            difficulty = o.optInt("difficulty", 2).coerceIn(1, 3),
            createdAt = now,
        )
    }

    fun grade(text: String, now: Long): Grade {
        val o = runCatching { JSONObject(text.trim()) }.getOrElse { throw StudyApiException("Couldn't understand the check's reply. Try again.") }
        return Grade(
            verdict = Verdict.of(o.optString("verdict")),
            score = o.optDouble("score", 0.0).toFloat().coerceIn(0f, 1f).let { if (it.isNaN()) 0f else it },
            feedback = o.optString("feedback").trim(),
            steps = o.optJSONArray("steps").mapObjects { StepNote(it.optBoolean("ok", true), it.optString("note").trim()) }
                .filter { it.note.isNotEmpty() }.take(12),
            read = o.optString("read").trim().take(2000),
            at = now,
        )
    }
}

/** The study calls, on top of [MistralClient]. Blocking: call on a background thread. */
class StudyAi(private val client: MistralClient) {
    /**
     * Items from a PDF ([pdf] is the whole source, or an excerpt of it starting at
     * [firstPage]), handed to [onItem] as they arrive. The upload is deleted afterwards.
     */
    fun fromPdf(pdf: File, name: String, source: StudySource, firstPage: Int, req: DeckRequest,
        onItem: (StudyItem) -> Unit, cancelled: () -> Boolean): List<StudyItem> {
        if (pdf.length() > MistralClient.MAX_UPLOAD_BYTES) throw StudyApiException("That PDF is larger than 50 MB. Choose a page range.")
        val fileId = client.uploadPdf(pdf, name)
        try {
            val url = client.signedUrl(fileId)
            val from = if (req.pages != null) "pages ${req.pages.first}–${req.pages.last} of the course material “$name” (the attached document)"
                else "the attached course material “$name”"
            val user = JSONArray()
                .put(JSONObject().put("type", "text").put("text", StudyPrompts.deckUser(req, from)))
                .put(JSONObject().put("type", "document_url").put("document_url", url))
            return stream(user, source.id, firstPage, source.pageCount, onItem, cancelled)
        } finally {
            client.deleteFile(fileId)
        }
    }

    /** Items from a picture of handwritten notes (PNG). */
    fun fromNotes(png: ByteArray, req: DeckRequest, onItem: (StudyItem) -> Unit, cancelled: () -> Boolean): List<StudyItem> {
        val user = JSONArray()
            .put(JSONObject().put("type", "text").put("text", StudyPrompts.deckUser(req, "the student's handwritten notes in the image") +
                "\nThe notes are the material: use what they say, and standard textbook knowledge only to fill obvious gaps. Leave \"pages\" empty."))
            .put(image(png))
        return stream(user, null, 1, 0, onItem, cancelled)
    }

    fun grade(item: StudyItem, png: ByteArray, now: Long): Grade {
        val user = JSONArray()
            .put(JSONObject().put("type", "text").put("text", StudyPrompts.gradeUser(item)))
            .put(image(png))
        return StudyPrompts.grade(client.chat(StudyPrompts.gradeSystem, user, "check", StudyPrompts.gradeSchema(), temperature = 0.1), now)
    }

    private fun image(png: ByteArray) = JSONObject().put("type", "image_url")
        .put("image_url", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))

    private fun stream(user: JSONArray, sourceId: String?, firstPage: Int, pageCount: Int, onItem: (StudyItem) -> Unit,
        cancelled: () -> Boolean): List<StudyItem> {
        val made = ArrayList<StudyItem>()
        var seen = 0
        val now = System.currentTimeMillis()
        fun take(text: String) {
            val done = PartialJson.completeElements(text, "items")
            while (seen < done.size) {
                val raw = done[seen++]
                val item = runCatching { StudyPrompts.item(JSONObject(raw), sourceId, firstPage, pageCount, now) }.getOrNull() ?: continue
                made += item
                onItem(item)
            }
        }
        val all = client.chat(StudyPrompts.deckSystem, user, "study_items", StudyPrompts.deckSchema(), onText = ::take, cancelled = cancelled)
        take(all)
        if (made.isEmpty()) throw StudyApiException("No questions came back. Try another page range, or fewer items.")
        return made
    }
}
