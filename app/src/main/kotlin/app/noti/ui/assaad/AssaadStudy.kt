package app.noti.ui.assaad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.*
import app.noti.assaad.*
import app.noti.study.Progress
import app.noti.study.Srs
import app.noti.study.StudyItem
import app.noti.study.Verdict
import app.noti.ui.study.RemoteChoices
import app.noti.ui.study.RemoteState
import app.noti.ui.study.StudyController
import app.noti.ui.study.StudyFilterOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects Plataforma Assaad to ANY open notebook's study sheet: saved question sets are
 * available right inside the Assaad tab on every canvas, answers submit live and come back
 * onto the card, and newly imported sets open on the sheet without forcing a special board.
 */
@Composable
internal fun AssaadStudyBridge(study: StudyController?) {
    val session = LocalAssaadSession.current ?: return
    if (study == null) return
    val scope = rememberCoroutineScope()
    val figures = remember(session) { AssaadFigures(session, scope) }
    val remote = remember(session, figures) { AssaadRemote(session, figures) }
    DisposableEffect(study, remote) {
        val lookup: (String) -> Bitmap? = figures::get
        study.remote = remote
        study.board?.figure = lookup
        onDispose {
            if (study.remote === remote) study.remote = null
            if (study.board?.figure === lookup) study.board?.figure = null
        }
    }
    LaunchedEffect(study, session, remote) {
        snapshotFlow {
            val questions = session.studyQuestions.map { it.second }
            Triple(questions, questions.associate { it.id to session.answerFor(it.id) }, session.libraryLoaded to session.focus)
        }.collectLatest { (questions, answers, state) ->
            val (loaded, focus) = state
            if (!loaded) return@collectLatest
            // Keep any Assaad questions already pinned onto THIS canvas updated with fresh verdicts.
            val printedIds = study.data.cards.mapTo(HashSet()) { it.itemId }
            val pinnedQuestions = questions.filter { assaadItemId(it.id) in printedIds }
            if (pinnedQuestions.isNotEmpty()) {
                val now = System.currentTimeMillis()
                study.store.update { board ->
                    var nextItems = board.items
                    var nextProgress = board.progress
                    for (q in pinnedQuestions) {
                        val id = assaadItemId(q.id)
                        val (item, prog) = assaadStudyEntry(q, answers[q.id], board.item(id), board.progress[id], now)
                        nextItems = nextItems.map { if (it.id == id) item else it }
                        nextProgress = nextProgress + (id to prog)
                    }
                    board.copy(items = nextItems, progress = nextProgress)
                }
            }
            if (focus != null) {
                session.focus = null
                study.showAssaad(focus.takeIf { it.isNotEmpty() }?.let(::assaadItemId))
            }
        }
    }
}

private fun assaadStudyEntry(
    q: AssaadQuestion,
    answer: AssaadAnswer?,
    existingItem: StudyItem?,
    existingProgress: Progress?,
    createdAt: Long,
    now: Long = System.currentTimeMillis(),
): Pair<StudyItem, Progress> {
    val checked = answer?.takeIf { it.status == "confirmed" }
    val chosen = checked?.let { a -> q.alternatives.indexOfFirst { it.id == a.alternativeId } } ?: -1
    val right = checked?.correctAlternativeId?.let { rid -> q.alternatives.indexOfFirst { it.id == rid } }?.takeIf { it >= 0 }
        ?: if (checked?.correct == true) chosen else -1
    val item = q.studyItem(existingItem?.createdAt?.takeIf { it > 0 } ?: createdAt, right)
    val base = existingProgress ?: Progress()
    val updated = when {
        chosen >= 0 && base.chosen != chosen -> base.copy(
            chosen = chosen,
            attempts = base.attempts + 1,
            srs = base.srs ?: Srs.review(
                null,
                Srs.ratingFor(if (chosen == right) Verdict.Correct else Verdict.Incorrect, 0, false, item.difficulty),
                now,
            ),
        )
        chosen < 0 && base.chosen >= 0 -> base.copy(chosen = -1)
        else -> base
    }
    return item to updated
}

/** The sheet's Assaad tab, talking directly to [AssaadSession] from any canvas. */
@Stable
private class AssaadRemote(private val session: AssaadSession, private val figures: AssaadFigures) : RemoteChoices {
    override val name = "Assaad"
    private var filterBatchId by mutableStateOf<String?>(null)
    private val srsCache = HashMap<String, Progress>()
    private val createdCache = HashMap<String, Long>()

    override val selectedFilterId: String?
        get() = filterBatchId?.takeIf { id -> session.batches.any { it.id == id } }

    override fun selectFilter(id: String?) {
        filterBatchId = id
        if (id != null) session.selectBatch(id)
    }

    override val filters: List<StudyFilterOption>
        get() = session.batches.asReversed().map { batch ->
            val label = batch.questions.firstOrNull()?.tags?.joinToString(" · ")?.takeIf { it.isNotBlank() } ?: "Assaad set"
            val answered = batch.questions.count { session.answerFor(it.id)?.status == "confirmed" }
            StudyFilterOption(batch.id, label, "${batch.questions.size} Q · $answered checked")
        }

    private fun entryFor(q: AssaadQuestion): Pair<StudyItem, Progress> {
        val id = assaadItemId(q.id)
        val createdAt = createdCache.getOrPut(id) { System.currentTimeMillis() }
        val (item, prog) = assaadStudyEntry(q, session.answerFor(q.id), null, srsCache[id], createdAt)
        srsCache[id] = prog
        return item to prog
    }

    override val allItems: List<StudyItem>
        get() = session.studyQuestions.map { (_, q) -> entryFor(q).first }

    override val items: List<StudyItem>
        get() {
            val activeBatch = selectedFilterId?.let { id -> session.batches.find { it.id == id } }
            val questions = activeBatch?.questions ?: session.studyQuestions.map { it.second }
            return questions.map { q -> entryFor(q).first }
        }

    override fun owns(itemId: String) = assaadQuestionId(itemId)?.let { session.question(it) } != null

    override fun item(itemId: String): StudyItem? =
        assaadQuestionId(itemId)?.let { session.question(it) }?.let { entryFor(it).first }

    override fun progressOf(itemId: String): Progress =
        assaadQuestionId(itemId)?.let { session.question(it) }?.let { entryFor(it).second } ?: Progress()

    override fun state(itemId: String): RemoteState? {
        val qid = assaadQuestionId(itemId) ?: return null
        val answer = session.answerFor(qid)
        return when (answer?.status) {
            "confirmed" -> RemoteState(RemoteState.Phase.Done, "")
            "pending", "queued" -> RemoteState(RemoteState.Phase.Checking, "Checking with Assaad…")
            "blocked", "failed" -> RemoteState(RemoteState.Phase.Blocked, answer.feedback.ifBlank { "Assaad didn't accept this answer." })
            "unknown" -> RemoteState(RemoteState.Phase.Unknown, answer.feedback.ifBlank { "Assaad didn't confirm. Check it there before another attempt." })
            else -> session.draftFor(qid)?.let { RemoteState(RemoteState.Phase.Draft, "") }
        }
    }

    override fun picked(itemId: String): Int {
        val qid = assaadQuestionId(itemId) ?: return -1
        val alternative = session.draftFor(qid) ?: return -1
        return session.question(qid)?.alternatives?.indexOfFirst { it.id == alternative } ?: -1
    }

    override fun pick(itemId: String, index: Int) {
        val qid = assaadQuestionId(itemId) ?: return
        val alternative = session.question(qid)?.alternatives?.getOrNull(index) ?: return
        session.pick(qid, alternative.id)
    }

    override fun submit(itemId: String) { assaadQuestionId(itemId)?.let(session::submit) }
    override fun retry(itemId: String) { assaadQuestionId(itemId)?.let(session::retry) }
    override val canSubmit get() = session.canSendAnswer
    override val sending get() = session.hasAnswerOnItsWay

    override fun openPlatform() { if (session.useTabletBrowser) session.openTabletBrowser() else session.dialogOpen = true }
    override fun openConnection() { session.dialogOpen = true }

    override val connectionNote: String? get() = when {
        session.useTabletBrowser -> when {
            session.tabletVerified -> null
            session.tabletNeedsLogin -> "Sign in to Assaad to send answers. Your picks are kept."
            session.tabletBusy || !session.tabletPageLoaded && session.tabletProgress in 1..99 -> null
            else -> "Assaad isn't connected. Your picks are kept until it is."
        }
        !session.paired -> "Connect Assaad to send answers. Your picks are kept."
        !session.browserOnline -> "Waiting for your paired browser. Your picks are kept."
        else -> null
    }

    override fun figure(url: String): Bitmap? = figures.get(url)
}

/** Question pictures, decoded once from the offline media cache and kept while they fit. */
@Stable
internal class AssaadFigures(private val session: AssaadSession, private val scope: CoroutineScope) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    /** Read with every lookup, so whatever drew a missing picture draws again when it arrives. */
    private var version by mutableIntStateOf(0)

    fun get(url: String): Bitmap? {
        version
        cache.get(url)?.let { return it }
        if (url.isBlank() || url in loading || url in failed || !safeImageUrl(url)) return null
        loading += url
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { session.imageBytes(url)?.let(::decode) }.getOrNull() }
            loading -= url
            if (bitmap != null) cache.put(url, bitmap) else failed += url
            version++
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
