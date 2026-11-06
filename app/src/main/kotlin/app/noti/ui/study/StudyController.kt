package app.noti.ui.study

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.pdf.PagePlacement
import app.noti.study.BoardLayer
import app.noti.study.BoardStudy
import app.noti.study.DeckRequest
import app.noti.study.Exam
import app.noti.study.ItemKind
import app.noti.study.MistralClient
import app.noti.study.PinnedCard
import app.noti.study.PinnedLabel
import app.noti.study.PlacedPage
import app.noti.study.Progress
import app.noti.study.Rating
import app.noti.study.Srs
import app.noti.study.StudyAi
import app.noti.study.StudyApiException
import app.noti.study.StudyImages
import app.noti.study.StudyItem
import app.noti.study.StudySource
import app.noti.study.StudyStore
import app.noti.study.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Everything a Study board does around its ink: the floating sheet (which item, where it
 * floats), answering, hints, checking a looped answer, printing onto the board, making
 * questions, exams and the topic layout. State lives in [store] (saved) and here (this
 * visit). Main thread.
 */
@Stable
class StudyController(
    val notebook: Notebook,
    val library: LibraryStore,
    val settings: SettingsStore,
    val store: StudyStore,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val toast: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val main = Handler(Looper.getMainLooper())

    // ---- the sheet ----

    /** The floating sheet is showing (the Study button in the top bar brings it back). */
    var open by mutableStateOf(false)
    /** Shrunk to a small pill in place. */
    var collapsed by mutableStateOf(false)
    /** The sheet's top-left in the editor, px; null puts it at its usual spot. */
    var offset by mutableStateOf<IntOffset?>(null)
    /** The order the sheet walks this visit: a snapshot, so answering doesn't reshuffle it. */
    var order by mutableStateOf<List<String>>(emptyList())
        private set

    val data: BoardStudy get() = store.data
    val exam: Exam? get() = data.exams.lastOrNull { it.finishedAt == null }
    val current: StudyItem? get() = data.item(data.current) ?: order.firstNotNullOfOrNull { data.item(it) }
    val position: Int get() = current?.let { order.indexOf(it.id) } ?: -1
    val dueCount: Int get() = data.dueCount(now())

    // ---- loops: an answer to check, or notes to make questions from ----

    sealed interface Loop {
        data class Answer(val itemId: String) : Loop
        data class Notes(val request: DeckRequest) : Loop
    }

    /** Waiting for a loop on the board (the Transcribe loop tool is lent for it). */
    var loop by mutableStateOf<Loop?>(null)
        private set
    /** Loops counted up to when the current one was asked for (engine document state [9]). */
    var loopFrom = 0L
        private set
    private var toolBefore: Tool? = null
    /** The item whose answer is being checked. */
    var checking by mutableStateOf<String?>(null)
        private set

    // ---- making questions ----

    @Stable
    class Generation(val label: String) {
        var made by mutableIntStateOf(0)
        internal var job: Job? = null
    }

    var generating by mutableStateOf<Generation?>(null)
        private set
    /** A problem to show (no key, network…), or null. */
    var problem by mutableStateOf<String?>(null)

    // ---- dialogs and menus ----

    var generateOpen by mutableStateOf(false)
    var deckOpen by mutableStateOf(false)
    var examSetupOpen by mutableStateOf(false)
    /** Exam whose results to show. */
    var examResults by mutableStateOf<String?>(null)
    /** Item whose source pages to preview. */
    var sourceOf by mutableStateOf<StudyItem?>(null)
    /** The board's drawing layer (heights of printed cards), set by the editor. */
    var board: BoardLayer? = null

    /** Something on the board that was tapped, and where (editor px). */
    var menu by mutableStateOf<Pair<BoardLayer.Hit, IntOffset>?>(null)

    // ------------------------------------------------------------------------------

    /** Opens the sheet on what to study next: what's due, else what's new, else where you were. */
    fun show() {
        refreshOrder()
        val d = data
        val next = exam?.let { e -> e.itemIds.firstOrNull { d.progressOf(it).let { p -> p.grade == null && p.chosen < 0 } } ?: e.itemIds.firstOrNull() }
            ?: d.studyOrder(now()).firstOrNull { d.isNew(it.id) || d.progressOf(it.id).srs?.let { s -> s.due <= now() } == true }?.id
            ?: d.current ?: order.firstOrNull()
        if (next == null) { generateOpen = true; return }
        select(next)
        collapsed = false
        open = true
    }

    fun hide() { open = false; cancelLoop() }

    private fun refreshOrder() {
        order = exam?.itemIds ?: data.studyOrder(now()).map { it.id }
    }

    fun select(id: String) {
        if (order.isEmpty()) refreshOrder()
        if (id !in order) order = order + id
        if (data.current != id) store.update { it.copy(current = id) }
        if (loop is Loop.Answer) cancelLoop()
    }

    fun step(delta: Int) {
        if (order.isEmpty()) refreshOrder()
        val i = position
        val next = order.getOrNull(if (i < 0) 0 else (i + delta).coerceIn(0, order.lastIndex)) ?: return
        select(next)
    }

    private fun progress(id: String, f: (Progress) -> Progress) = store.update { it.withProgress(id, f) }

    fun hint() {
        val item = current ?: return
        progress(item.id) { it.copy(hintsShown = (it.hintsShown + 1).coerceAtMost(item.hints.size)) }
    }

    fun reveal() {
        val item = current ?: return
        progress(item.id) { it.copy(answerShown = true) }
    }

    fun flip() {
        val item = current ?: return
        progress(item.id) { it.copy(flipped = !it.flipped) }
    }

    /** Flash card: how well you knew it. */
    fun rate(rating: Rating) {
        val item = current ?: return
        progress(item.id) { it.copy(flipped = true, attempts = it.attempts + 1, srs = Srs.review(it.srs, rating, now())) }
        main.postDelayed({ if (current?.id == item.id) step(1) }, 350)
    }

    /** Multiple choice: checked here, no network. In an exam it can change until the end. */
    fun choose(index: Int) {
        val item = current ?: return
        if (item.kind != ItemKind.Choice) return
        val e = exam
        progress(item.id) { p ->
            if (e == null && p.chosen >= 0) return@progress p
            val verdict = if (index == item.correctChoice) Verdict.Correct else Verdict.Incorrect
            p.copy(chosen = index, attempts = p.attempts + 1,
                srs = if (e != null) p.srs else Srs.review(p.srs, Srs.ratingFor(verdict, p.hintsShown, p.answerShown, item.difficulty), now()))
        }
    }

    /** Try again: hides your answer, hints and the check (the review schedule stays). */
    fun reset(id: String) {
        progress(id) { Progress(srs = it.srs) }
    }

    // ---- loops ----

    /** Asks for a loop around an answer on the board, then checks it. [serial] is the loop count now. */
    fun startCheck(serial: Long) {
        val item = current ?: return
        if (!ensureKey()) return
        beginLoop(Loop.Answer(item.id), serial)
    }

    /** Asks for a loop around notes on the board, then makes questions from them. */
    fun startNotes(request: DeckRequest, serial: Long) {
        if (!ensureKey()) return
        beginLoop(Loop.Notes(request), serial)
    }

    private fun beginLoop(l: Loop, serial: Long) {
        if (loop == null) toolBefore = settings.tool
        loop = l
        loopFrom = serial
        settings.tool = Tool.Transcribe
    }

    fun cancelLoop() {
        if (loop == null) return
        loop = null
        toolBefore?.let { settings.tool = it }
        toolBefore = null
    }

    /** The ink of a finished loop (InkLayer.selectionInk); [clear] drops the selection. */
    fun onLoop(selection: FloatArray, clear: () -> Unit) {
        val l = loop ?: return
        cancelLoop()
        scope.launch {
            val png = withContext(Dispatchers.Default) { StudyImages.inkPng(selection) }
            clear()
            if (png == null) { toast("There's no writing in that loop. Loop around your work and try again."); return@launch }
            when (l) {
                is Loop.Answer -> grade(l.itemId, png)
                is Loop.Notes -> fromNotes(png, l.request)
            }
        }
    }

    private fun key(): String? = settings.studyKey.load()

    private fun ensureKey(): Boolean {
        if (settings.studyKey.hasKey && key() != null) return true
        problem = "Add your Mistral API key in Settings → Study to make and check questions."
        return false
    }

    private suspend fun grade(itemId: String, png: ByteArray) {
        val item = data.item(itemId) ?: return
        val key = key() ?: run { ensureKey(); return }
        checking = itemId
        val result = withContext(Dispatchers.IO) { runCatching { StudyAi(MistralClient(key)).grade(item, png, now()) } }
        checking = null
        result.onSuccess { grade ->
            val inExam = exam?.itemIds?.contains(itemId) == true
            progress(itemId) { p ->
                p.copy(grade = grade, attempts = p.attempts + 1,
                    srs = if (inExam) p.srs else Srs.review(p.srs, Srs.ratingFor(grade.verdict, p.hintsShown, p.answerShown, item.difficulty), now()))
            }
            if (inExam) toast("Answer saved.")
        }.onFailure { problem = (it as? StudyApiException)?.message ?: "Couldn't check that answer. Try again." }
    }

    // ---- making questions ----

    /** From a library PDF; [request.pages] picks a range. */
    fun generate(source: Notebook, request: DeckRequest) {
        if (generating != null) return
        val key = key() ?: run { ensureKey(); return }
        val file = library.pdfFile(source)?.takeIf { it.exists() } ?: run { problem = "That PDF isn't in the library anymore."; return }
        val gen = Generation(source.name)
        generating = gen
        val studySource = StudySource(source.id, source.name, source.pageCount)
        if (data.source(source.id) == null) store.update { it.copy(sources = it.sources + studySource) }
        gen.job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var upload = file
                    var first = 1
                    val range = request.pages
                    if (range != null) {
                        upload = File(cacheDir, "study-pages-${UUID.randomUUID()}.pdf")
                        StudyImages.extractPages(file, range.first, range.last, upload)
                        first = range.first
                    }
                    try {
                        StudyAi(MistralClient(key)).fromPdf(upload, source.name, studySource, first, request,
                            onItem = { item -> main.post { added(item, gen) } }, cancelled = { !isActive })
                    } finally { if (upload != file) upload.delete() }
                }
            }
            finished(gen, result)
        }
    }

    private suspend fun fromNotes(png: ByteArray, request: DeckRequest) {
        if (generating != null) return
        val key = key() ?: return
        val gen = Generation("your notes")
        generating = gen
        gen.job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { StudyAi(MistralClient(key)).fromNotes(png, request, { item -> main.post { added(item, gen) } }, { !isActive }) }
            }
            finished(gen, result)
        }
    }

    private fun added(item: StudyItem, gen: Generation) {
        if (generating !== gen) return
        store.update { it.copy(items = it.items + item) }
        order = order + item.id
        gen.made++
        // The first new item goes straight onto the sheet.
        if (gen.made == 1 && exam == null) { select(item.id); collapsed = false; open = true }
    }

    private fun finished(gen: Generation, result: Result<List<StudyItem>>) {
        if (generating !== gen) return
        generating = null
        result.onSuccess { toast("Made ${it.size} ${if (it.size == 1) "item" else "items"} from ${gen.label}.") }
            .onFailure { e ->
                if (e is CancellationException) return
                problem = (e as? StudyApiException)?.message ?: "Couldn't make questions: ${e.message ?: "something went wrong"}."
                if (gen.made > 0) toast("Kept the ${gen.made} items made before that.")
            }
    }

    fun stopGenerating() {
        val gen = generating ?: return
        gen.job?.cancel()
        generating = null
        if (gen.made > 0) toast("Stopped. Kept ${gen.made} items.")
    }

    // ---- the board ----

    /**
     * Pins the current item where the sheet shows it: its face's top-left at ([leftPx],
     * [topPx]) in the editor, [widthDp] wide at [density] px per dp, keeping its size on
     * screen. The sheet goes away until the Study button brings it back.
     */
    fun print(leftPx: Float, topPx: Float, widthDp: Float, density: Float, sheet: PagePlacement) {
        val item = current ?: return
        val k = sheet.width / 1000f
        if (!(k > 0f)) return
        val card = PinnedCard(UUID.randomUUID().toString(), item.id, (leftPx - sheet.left) / k, (topPx - sheet.top) / k,
            widthDp, density / k, now())
        store.update { d -> d.copy(cards = d.cards.filterNot { it.itemId == item.id } + card) }
        open = false
        cancelLoop()
    }

    /**
     * Lifts a printed card back onto the sheet, where it was on screen. [face] is where the
     * face sits inside the sheet window, px.
     */
    fun unpin(card: PinnedCard, sheet: PagePlacement, face: IntOffset) {
        val k = sheet.width / 1000f
        store.update { d -> d.copy(cards = d.cards.filterNot { it.id == card.id }) }
        offset = IntOffset((sheet.left + card.x * k).toInt() - face.x, (sheet.top + card.y * k).toInt() - face.y)
        select(card.itemId)
        collapsed = false
        open = true
    }

    fun remove(hit: BoardLayer.Hit) = store.update { d ->
        when (hit) {
            is BoardLayer.Hit.Card -> d.copy(cards = d.cards.filterNot { it.id == hit.card.id })
            is BoardLayer.Hit.Label -> d.copy(labels = d.labels.filterNot { it.id == hit.label.id })
            is BoardLayer.Hit.Page -> d.copy(pages = d.pages.filterNot { it.id == hit.page.id })
            is BoardLayer.Hit.Score -> d.copy(exams = d.exams.map { if (it.id == hit.exam.id) it.copy(card = null) else it })
        }
    }

    /** Lays a source page on the board, [widthDp] wide at the current zoom, top-left at ([leftPx], [topPx]). */
    fun placePage(sourceId: String, page: Int, leftPx: Float, topPx: Float, widthDp: Float, density: Float, sheet: PagePlacement) {
        val k = sheet.width / 1000f
        if (!(k > 0f)) return
        val placed = PlacedPage(UUID.randomUUID().toString(), sourceId, page, (leftPx - sheet.left) / k, (topPx - sheet.top) / k, widthDp * density / k)
        store.update { it.copy(pages = it.pages + placed) }
    }

    /**
     * Arranges printed cards in columns by topic, in deck order, with a heading above each
     * column, starting where the cards are now.
     */
    fun arrangeByTopic() {
        val layer = board ?: return
        val d = data
        if (d.cards.isEmpty()) { toast("Print some cards first: their topics become the columns."); return }
        val unit = d.cards.first().unitsPerDp
        val gap = 24f * unit
        val x0 = d.cards.minOf { it.x }
        val y0 = d.cards.minOf { it.y } + 40f * unit
        val byTopic = d.cards.groupBy { d.item(it.itemId)?.topic?.ifBlank { "General" } ?: "General" }
        val topics = d.topics().filter { it in byTopic } + byTopic.keys.filter { it !in d.topics() }
        val cards = ArrayList<PinnedCard>()
        val labels = ArrayList<PinnedLabel>()
        var x = x0
        for (topic in topics) {
            val column = byTopic[topic].orEmpty().sortedBy { c -> d.items.indexOfFirst { it.id == c.itemId } }
            labels += PinnedLabel(UUID.randomUUID().toString(), topic, x, y0 - 14f * unit, unit)
            var y = y0
            var width = 0f
            for (c in column) {
                val moved = c.copy(x = x, y = y)
                cards += moved
                y += layer.heightUnits(moved) + gap
                width = maxOf(width, c.widthDp * c.unitsPerDp)
            }
            x += width + gap * 2
        }
        val topicNames = topics.toSet()
        store.update { it.copy(cards = cards, labels = it.labels.filterNot { l -> l.text in topicNames } + labels) }
        toast("Arranged ${cards.size} cards in ${topics.size} ${if (topics.size == 1) "topic" else "topics"}.")
    }

    fun deleteItem(id: String) {
        store.update { d ->
            d.copy(items = d.items.filterNot { it.id == id }, progress = d.progress - id, cards = d.cards.filterNot { it.itemId == id },
                current = if (d.current == id) null else d.current)
        }
        order = order - id
    }

    // ---- exams ----

    fun startExam(count: Int, minutes: Int, topics: Set<String>) {
        val d = data
        val pool = d.studyOrder(now()).filter { topics.isEmpty() || it.topic in topics }.filter { it.kind != ItemKind.Flash }
        if (pool.isEmpty()) { toast("No questions to make an exam from yet."); return }
        val picked = (pool.take(count * 2).shuffled().take(count)).map { it.id }
        // Start each from scratch, keeping the review schedule.
        store.update { s ->
            var next = s.copy(exams = s.exams + Exam(UUID.randomUUID().toString(), picked, minutes, now()))
            picked.forEach { id -> next = next.withProgress(id) { Progress(srs = it.srs) } }
            next.copy(current = picked.first())
        }
        order = picked
        collapsed = false
        open = true
    }

    fun finishExam() {
        val e = exam ?: return
        val t = now()
        store.update { s ->
            var next = s.copy(exams = s.exams.map { if (it.id == e.id) it.copy(finishedAt = t) else it })
            for (id in e.itemIds) {
                val item = s.item(id) ?: continue
                next = next.withProgress(id) { p ->
                    val verdict = p.outcome(item) ?: Verdict.Incorrect
                    p.copy(srs = Srs.review(p.srs, Srs.ratingFor(verdict, 0, false, item.difficulty), t))
                }
            }
            next
        }
        refreshOrder()
        examResults = e.id
    }

    /** Pins a finished exam's score card at the sheet's spot. */
    fun printScore(examId: String, leftPx: Float, topPx: Float, density: Float, sheet: PagePlacement) {
        val k = sheet.width / 1000f
        if (!(k > 0f)) return
        val card = PinnedCard(UUID.randomUUID().toString(), "", (leftPx - sheet.left) / k, (topPx - sheet.top) / k, 280f, density / k, now())
        store.update { s -> s.copy(exams = s.exams.map { if (it.id == examId) it.copy(card = card) else it }) }
    }
}
