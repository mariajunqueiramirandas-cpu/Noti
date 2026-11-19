package app.noti.ui.exams

import androidx.compose.runtime.*
import app.noti.data.LibraryStore
import app.noti.exams.*
import app.noti.study.Progress
import app.noti.study.Srs
import app.noti.study.StudyItem
import app.noti.study.StudySource
import app.noti.study.Verdict
import app.noti.ui.study.ExamFeed
import app.noti.ui.study.StudyController
import app.noti.ui.study.StudyFilterOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Connects imported Past Exams to ANY open notebook's study sheet: browse questions from
 * any exam, reveal hints/solutions, self-assess or answer multiple-choice, earn XP globally,
 * and print cards or source PDF pages onto whichever canvas you are using.
 */
@Composable
internal fun ExamStudyBridge(
    examStore: ExamStore,
    library: LibraryStore,
    study: StudyController?,
    onOpenHub: () -> Unit,
) {
    if (study == null) return
    val scope = rememberCoroutineScope()
    LaunchedEffect(examStore) { runCatching { examStore.load() } }
    val feed = remember(examStore, library, study, scope, onOpenHub) {
        LiveExamFeed(examStore, library, study, scope, onOpenHub)
    }
    DisposableEffect(study, feed) {
        study.examFeed = feed
        onDispose { if (study.examFeed === feed) study.examFeed = null }
    }
}

@Stable
private class LiveExamFeed(
    private val store: ExamStore,
    private val library: LibraryStore,
    private val study: StudyController,
    private val scope: CoroutineScope,
    private val onOpenHub: () -> Unit,
) : ExamFeed {
    private var filterExamId by mutableStateOf<String?>(null)

    private val catalog: ExamCatalog get() = store.catalog ?: ExamCatalog()

    override val selectedFilterId: String?
        get() = filterExamId?.takeIf { id -> catalog.exams.any { it.sourceId == id } }

    override fun selectFilter(id: String?) {
        filterExamId = id
    }

    override val filters: List<StudyFilterOption>
        get() = catalog.exams.sortedWith(compareByDescending<PastExam> { it.year ?: 0 }.thenBy { it.title })
            .map { exam ->
                StudyFilterOption(exam.sourceId, exam.title, exam.subtitle)
            }

    private fun solutionsByKey(): Map<String, ExamLesson> =
        catalog.lessons.values.sortedBy { it.isReviewed }.associateBy { it.questionKey }

    override val items: List<StudyItem>
        get() {
            val activeExam = selectedFilterId
            val solutions = solutionsByKey()
            val refs = catalog.allReferences().let { list ->
                if (activeExam == null) list else list.filter { it.exam.sourceId == activeExam }
            }
            return refs.map { ref -> ref.toStudyItem(solutions[ref.key], study.data.item(ref.key)) }
        }

    override fun owns(itemId: String): Boolean = catalog.reference(itemId) != null

    override fun item(itemId: String): StudyItem? {
        val ref = catalog.reference(itemId) ?: return null
        return ref.toStudyItem(solutionsByKey()[ref.key], study.data.item(ref.key))
    }

    override fun sourceOf(itemId: String): StudySource? {
        val ref = catalog.reference(itemId) ?: return null
        return study.data.source(ref.exam.sourceId).orExamSource(library, ref.exam)
    }

    override fun progressOf(itemId: String): Progress = catalog.progressOf(itemId)

    private fun updateProgress(itemId: String, change: (Progress) -> Progress) {
        scope.launch {
            runCatching {
                store.update { cat -> cat.withProgress(itemId, change) }
                // Keep any pinned copy of this question on the current canvas in sync.
                if (study.data.item(itemId) != null) {
                    study.store.update { board -> board.withProgress(itemId, change) }
                }
            }
        }
    }

    override fun hint(itemId: String) {
        val item = item(itemId) ?: return
        updateProgress(itemId) { it.copy(hintsShown = (it.hintsShown + 1).coerceAtMost(item.hints.size)) }
    }

    override fun reveal(itemId: String) {
        updateProgress(itemId) { it.copy(answerShown = true) }
    }

    override fun choose(itemId: String, index: Int) {
        val item = item(itemId) ?: return
        if (index !in item.choices.indices) return
        val now = System.currentTimeMillis()
        updateProgress(itemId) { p ->
            val graded = item.correctChoice >= 0
            if (graded && p.chosen >= 0) return@updateProgress p
            val verdict = if (index == item.correctChoice) Verdict.Correct else Verdict.Incorrect
            p.copy(
                chosen = index,
                attempts = if (graded) p.attempts + 1 else p.attempts,
                srs = if (!graded) p.srs else Srs.review(p.srs, Srs.ratingFor(verdict, p.hintsShown, p.answerShown, item.difficulty), now),
            )
        }
    }

    override fun assessWritten(itemId: String, verdict: Verdict) {
        val item = item(itemId) ?: return
        val now = System.currentTimeMillis()
        updateProgress(itemId) { p ->
            if (!p.answerShown || p.selfAssessment != null) p else p.copy(
                selfAssessment = verdict,
                attempts = p.attempts + 1,
                srs = Srs.review(p.srs, Srs.ratingFor(verdict, p.hintsShown, false, item.difficulty), now),
            )
        }
    }

    override fun reset(itemId: String) {
        updateProgress(itemId) { Progress(attempts = it.attempts, srs = it.srs) }
    }

    override fun openHub() = onOpenHub()
}
