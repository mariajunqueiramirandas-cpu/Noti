package app.noti.study

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Study board's study material, kept beside its ink (see [StudyStore]): the PDFs it
 * learns from, the items made from them, how each one went, and what was printed onto
 * the board. Math is written in LaTeX between `$…$` (inline) or `$$…$$` (display), see
 * [MathText].
 *
 * Board positions are in the Endless canvas's page units (the sheet is 1000 wide, see
 * app.noti.data.EndlessCanvas), so they move and zoom with the ink.
 */
data class BoardStudy(
    val sources: List<StudySource> = emptyList(),
    val items: List<StudyItem> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    val cards: List<PinnedCard> = emptyList(),
    val labels: List<PinnedLabel> = emptyList(),
    val pages: List<PlacedPage> = emptyList(),
    val exams: List<Exam> = emptyList(),
    /** The item the sheet shows, so it reopens where you were. */
    val current: String? = null,
) {
    fun item(id: String?): StudyItem? = items.firstOrNull { it.id == id }
    fun progressOf(id: String): Progress = progress[id] ?: Progress()
    fun source(id: String?): StudySource? = sources.firstOrNull { it.id == id }
    fun cardFor(itemId: String): PinnedCard? = cards.firstOrNull { it.itemId == itemId }
    fun exam(id: String?): Exam? = exams.firstOrNull { it.id == id }

    fun withProgress(id: String, f: (Progress) -> Progress) = copy(progress = progress + (id to f(progressOf(id))))

    /** Items whose review is due now, soonest first (new items are not due, they are new). */
    fun due(now: Long): List<StudyItem> =
        items.filter { progress[it.id]?.srs?.let { s -> s.due <= now } == true }.sortedBy { progress[it.id]!!.srs!!.due }

    fun dueCount(now: Long) = items.count { progress[it.id]?.srs?.let { s -> s.due <= now } == true }

    /** Never answered, checked, flipped or rated. */
    fun isNew(id: String) = progressOf(id).let { it.srs == null && it.grade == null && it.chosen < 0 }

    /**
     * The order the sheet walks: what's due first, then new items in deck order, then the
     * rest. Every item appears once.
     */
    fun studyOrder(now: Long): List<StudyItem> {
        val due = due(now)
        val dueIds = due.mapTo(HashSet()) { it.id }
        val fresh = items.filter { it.id !in dueIds && isNew(it.id) }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        return due + fresh + items.filter { it.id !in dueIds && it.id !in freshIds }
    }

    fun topics(): List<String> = items.map { it.topic.ifBlank { "General" } }.distinct()

    fun encode(): String = JSONObject()
        .put("version", VERSION)
        .put("sources", JSONArray(sources.map { it.json() }))
        .put("items", JSONArray(items.map { it.json() }))
        .put("progress", JSONObject().apply { progress.forEach { (k, v) -> put(k, v.json()) } })
        .put("cards", JSONArray(cards.map { it.json() }))
        .put("labels", JSONArray(labels.map { it.json() }))
        .put("pages", JSONArray(pages.map { it.json() }))
        .put("exams", JSONArray(exams.map { it.json() }))
        .apply { current?.let { put("current", it) } }
        .toString()

    companion object {
        const val VERSION = 1

        /** Unknown fields are ignored and broken entries skipped, so a newer file still opens. */
        fun decode(text: String): BoardStudy {
            val o = JSONObject(text)
            require(o.optInt("version", 1) >= 1)
            val progress = HashMap<String, Progress>()
            o.optJSONObject("progress")?.let { p -> p.keys().forEach { k -> runCatching { progress[k] = Progress.from(p.getJSONObject(k)) } } }
            return BoardStudy(
                sources = o.optJSONArray("sources").mapObjects(StudySource::from),
                items = o.optJSONArray("items").mapObjects(StudyItem::from),
                progress = progress,
                cards = o.optJSONArray("cards").mapObjects(PinnedCard::from),
                labels = o.optJSONArray("labels").mapObjects(PinnedLabel::from),
                pages = o.optJSONArray("pages").mapObjects(PlacedPage::from),
                exams = o.optJSONArray("exams").mapObjects(Exam::from),
                current = o.optString("current").ifEmpty { null },
            )
        }
    }
}

/** A PDF in the library (an imported PDF notebook) that a board studies from. */
data class StudySource(val id: String, val name: String, val pageCount: Int) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("pageCount", pageCount)
    companion object {
        fun from(o: JSONObject) = StudySource(o.getString("id"), o.optString("name"), o.optInt("pageCount"))
    }
}

enum class ItemKind(val wire: String, val label: String) {
    /** A question with a short final answer (a value, an expression, a sentence). */
    Short("short", "Question"),
    /** Four options, one right; checked on the device. */
    Choice("choice", "Multiple choice"),
    /** Front and back; you rate how well you knew it. */
    Flash("flash", "Flash card"),
    /** A problem worked in steps on the board. */
    Problem("problem", "Problem");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Short
    }
}

/** One question, flash card or problem. Text fields may hold `$…$` math. */
data class StudyItem(
    val id: String,
    val kind: ItemKind,
    val topic: String,
    val prompt: String,
    /** Multiple choice options, in order (A, B, C, D). */
    val choices: List<String> = emptyList(),
    val correctChoice: Int = -1,
    /** The final answer; for a flash card, its back. */
    val answer: String = "",
    /** Worked solution, one step per entry. */
    val solution: List<String> = emptyList(),
    /** From a nudge to almost the answer. */
    val hints: List<String> = emptyList(),
    val sourceId: String? = null,
    /** Source pages, 1-based as printed in the PDF viewer. */
    val pages: List<Int> = emptyList(),
    /** What the source says, briefly: used to check answers without sending the PDF again. */
    val excerpt: String = "",
    /** 1 review · 2 exam · 3 hard. */
    val difficulty: Int = 2,
    val createdAt: Long = 0L,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.wire).put("topic", topic).put("prompt", prompt)
        .put("choices", JSONArray(choices)).put("correctChoice", correctChoice)
        .put("answer", answer).put("solution", JSONArray(solution)).put("hints", JSONArray(hints))
        .put("pages", JSONArray(pages)).put("excerpt", excerpt).put("difficulty", difficulty).put("createdAt", createdAt)
        .apply { sourceId?.let { put("sourceId", it) } }

    companion object {
        fun from(o: JSONObject) = StudyItem(
            id = o.getString("id"),
            kind = ItemKind.of(o.optString("kind")),
            topic = o.optString("topic"),
            prompt = o.getString("prompt"),
            choices = o.optJSONArray("choices").strings(),
            correctChoice = o.optInt("correctChoice", -1),
            answer = o.optString("answer"),
            solution = o.optJSONArray("solution").strings(),
            hints = o.optJSONArray("hints").strings(),
            sourceId = o.optString("sourceId").ifEmpty { null },
            pages = o.optJSONArray("pages").ints(),
            excerpt = o.optString("excerpt"),
            difficulty = o.optInt("difficulty", 2).coerceIn(1, 3),
            createdAt = o.optLong("createdAt"),
        )
    }
}

enum class Verdict(val wire: String, val label: String) {
    Correct("correct", "Correct"), Partial("partial", "Partly right"), Incorrect("incorrect", "Not yet");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Incorrect }
}

/** One note on a step of the answer. */
data class StepNote(val ok: Boolean, val note: String)

/** What checking an answer said. */
data class Grade(
    val verdict: Verdict,
    val score: Float,
    val feedback: String,
    val steps: List<StepNote> = emptyList(),
    /** What the reader understood the handwriting to say (math in LaTeX). */
    val read: String = "",
    val at: Long = 0L,
) {
    fun json(): JSONObject = JSONObject().put("verdict", verdict.wire).put("score", score.toDouble()).put("feedback", feedback)
        .put("steps", JSONArray(steps.map { JSONObject().put("ok", it.ok).put("note", it.note) })).put("read", read).put("at", at)

    companion object {
        fun from(o: JSONObject) = Grade(
            verdict = Verdict.of(o.optString("verdict")),
            score = o.optDouble("score", 0.0).toFloat().coerceIn(0f, 1f),
            feedback = o.optString("feedback"),
            steps = o.optJSONArray("steps").mapObjects { StepNote(it.optBoolean("ok"), it.optString("note")) },
            read = o.optString("read"),
            at = o.optLong("at"),
        )
    }
}

/** How an item went so far. */
data class Progress(
    val hintsShown: Int = 0,
    val answerShown: Boolean = false,
    /** Flash card: the back is showing. */
    val flipped: Boolean = false,
    /** Multiple choice: the option picked, or -1. */
    val chosen: Int = -1,
    val grade: Grade? = null,
    val attempts: Int = 0,
    val srs: SrsState? = null,
) {
    /** Correct, partly right or not yet, from a check, a choice or a rating; null if untried. */
    fun outcome(item: StudyItem): Verdict? = when {
        item.kind == ItemKind.Choice && chosen >= 0 -> if (chosen == item.correctChoice) Verdict.Correct else Verdict.Incorrect
        grade != null -> grade.verdict
        item.kind == ItemKind.Flash && srs != null -> when (srs.lastRating) {
            Rating.Again -> Verdict.Incorrect
            Rating.Hard -> Verdict.Partial
            else -> Verdict.Correct
        }
        else -> null
    }

    fun json(): JSONObject = JSONObject().put("hintsShown", hintsShown).put("answerShown", answerShown)
        .put("flipped", flipped).put("chosen", chosen).put("attempts", attempts)
        .apply { grade?.let { put("grade", it.json()) }; srs?.let { put("srs", it.json()) } }

    companion object {
        fun from(o: JSONObject) = Progress(
            hintsShown = o.optInt("hintsShown").coerceAtLeast(0),
            answerShown = o.optBoolean("answerShown"),
            flipped = o.optBoolean("flipped"),
            chosen = o.optInt("chosen", -1),
            grade = o.optJSONObject("grade")?.let(Grade::from),
            attempts = o.optInt("attempts"),
            srs = o.optJSONObject("srs")?.let(SrsState::from),
        )
    }
}

/**
 * A printed item: its card is drawn on the board at ([x], [y]), its top-left corner, in
 * page units. The card is laid out [widthDp] wide (the sheet's width when it was printed)
 * and one dp is [unitsPerDp] page units, so it keeps the size it had on screen then.
 */
data class PinnedCard(val id: String, val itemId: String, val x: Float, val y: Float, val widthDp: Float, val unitsPerDp: Float,
    val at: Long = 0L) {
    fun json(): JSONObject = JSONObject().put("id", id).put("itemId", itemId).put("x", x.toDouble()).put("y", y.toDouble())
        .put("widthDp", widthDp.toDouble()).put("unitsPerDp", unitsPerDp.toDouble()).put("at", at)

    companion object {
        fun from(o: JSONObject) = PinnedCard(o.getString("id"), o.getString("itemId"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
            o.getDouble("widthDp").toFloat().coerceIn(120f, 1200f), o.getDouble("unitsPerDp").toFloat().also { require(it > 0f && it.isFinite()) },
            o.optLong("at"))
    }
}

/** A heading on the board (topic map), its baseline-left corner at ([x], [y]). */
data class PinnedLabel(val id: String, val text: String, val x: Float, val y: Float, val unitsPerDp: Float) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("x", x.toDouble()).put("y", y.toDouble())
        .put("unitsPerDp", unitsPerDp.toDouble())

    companion object {
        fun from(o: JSONObject) = PinnedLabel(o.getString("id"), o.getString("text"), o.getDouble("x").toFloat(),
            o.getDouble("y").toFloat(), o.getDouble("unitsPerDp").toFloat().also { require(it > 0f && it.isFinite()) })
    }
}

/** A source page laid on the board: [page] (0-based) of [sourceId], [width] page units wide. */
data class PlacedPage(val id: String, val sourceId: String, val page: Int, val x: Float, val y: Float, val width: Float) {
    fun json(): JSONObject = JSONObject().put("id", id).put("sourceId", sourceId).put("page", page)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("width", width.toDouble())

    companion object {
        fun from(o: JSONObject) = PlacedPage(o.getString("id"), o.getString("sourceId"), o.getInt("page").also { require(it >= 0) },
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("width").toFloat().also { require(it > 0f) })
    }
}

/**
 * A timed run through [itemIds] without hints, answers or sources. Answers are checked as
 * they come in; the result is shown when it ends, and can be printed as a score card.
 */
data class Exam(
    val id: String,
    val itemIds: List<String>,
    val minutes: Int,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** Score card on the board (top-left, page units), once printed. */
    val card: PinnedCard? = null,
) {
    val endsAt get() = startedAt + minutes * 60_000L
    fun json(): JSONObject = JSONObject().put("id", id).put("itemIds", JSONArray(itemIds)).put("minutes", minutes)
        .put("startedAt", startedAt).apply { finishedAt?.let { put("finishedAt", it) }; card?.let { put("card", it.json()) } }

    companion object {
        fun from(o: JSONObject) = Exam(o.getString("id"), o.optJSONArray("itemIds").strings(), o.optInt("minutes", 30),
            o.optLong("startedAt"), if (o.has("finishedAt")) o.getLong("finishedAt") else null,
            o.optJSONObject("card")?.let { runCatching { PinnedCard.from(it) }.getOrNull() })
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }
internal fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optInt(i, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
internal fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { runCatching { f(it) }.getOrNull() } }
