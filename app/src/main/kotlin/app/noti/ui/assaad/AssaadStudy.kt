package app.noti.ui.assaad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.*
import app.noti.assaad.*
import app.noti.ui.study.RemoteChoices
import app.noti.ui.study.RemoteState
import app.noti.ui.study.StudyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts Plataforma Assaad on its study board: every saved question is an item on the
 * board's ordinary study sheet, answers go to Assaad and come back as checked cards, and
 * a newly saved set opens the sheet on its first question. Only on the Assaad board.
 */
@Composable
internal fun AssaadStudyBridge(notebookId: String, study: StudyController?) {
    val session = LocalAssaadSession.current ?: return
    if (study == null || session.boardId != notebookId) return
    val scope = rememberCoroutineScope()
    val figures = remember(session) { AssaadFigures(session, scope) }
    val remote = remember(session, figures) { AssaadRemote(session, figures) }
    DisposableEffect(study, remote) {
        study.remote = remote
        study.board?.figure = figures::get
        onDispose { if (study.remote === remote) study.remote = null; study.board?.figure = null }
    }
    LaunchedEffect(study, session) {
        snapshotFlow {
            val questions = session.studyQuestions.map { it.second }
            Triple(questions, questions.associate { it.id to session.answerFor(it.id) }, session.libraryLoaded to session.focus)
        }.collectLatest { (questions, answers, state) ->
            val (loaded, focus) = state
            if (!loaded) return@collectLatest
            study.store.update { it.withAssaad(questions, answers::get, System.currentTimeMillis()) }
            if (focus != null) {
                session.focus = null
                study.showItem(focus.takeIf { it.isNotEmpty() }?.let(::assaadItemId))
            }
        }
    }
}

/** The board's study sheet, talking to Assaad through the session. */
@Stable
private class AssaadRemote(private val session: AssaadSession, private val figures: AssaadFigures) : RemoteChoices {
    override val name = "Assaad"
    override fun owns(itemId: String) = assaadQuestionId(itemId)?.let { session.question(it) } != null

    override fun state(itemId: String): RemoteState? {
        val qid = assaadQuestionId(itemId) ?: return null
        val answer = session.answerFor(qid)
        return when (answer?.status) {
            "confirmed" -> RemoteState(RemoteState.Phase.Done, "")
            "pending" -> RemoteState(RemoteState.Phase.Checking, "Checking with Assaad…")
            "queued" -> RemoteState(RemoteState.Phase.Queued,
                if (session.queuePaused) "Saved and queued. The queue is paused." else "Saved. It goes to Assaad once it's connected.")
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
