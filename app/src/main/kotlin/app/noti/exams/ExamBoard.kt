package app.noti.exams

import android.graphics.Bitmap
import app.noti.data.LibraryStore
import app.noti.study.ItemKind
import app.noti.study.Progress
import app.noti.study.StudyImages
import app.noti.study.StudyItem
import app.noti.study.StudySource
import app.noti.study.StudyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal suspend fun renderExamPage(file: File, page: Int): ByteArray = withContext(Dispatchers.IO) {
    val image = StudyImages.renderPage(file, page - 1, 1500) ?: error("Não foi possível abrir a página $page.")
    try {
        ByteArrayOutputStream().use { bytes ->
            check(image.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) { "Não foi possível preparar a página $page." }
            bytes.toByteArray()
        }
    } finally {
        image.recycle()
    }
}

/** Converts any past exam question (written or multiple-choice) into a StudyItem for the sheet or board. */
internal fun ExamReference.toStudyItem(
    lesson: ExamLesson? = null,
    previous: StudyItem? = null,
): StudyItem {
    val solution = lesson?.solution
    val official = question.officialSolutionUrl
    val isChoice = question.format == QuestionFormat.Choice && question.choices.isNotEmpty()
    val kind = if (isChoice) ItemKind.Choice else ItemKind.Problem
    val resolvedCorrect = when {
        !isChoice -> -1
        question.correctChoice in question.choices.indices -> question.correctChoice
        solution?.correctChoice != null && solution.correctChoice in question.choices.indices -> solution.correctChoice
        previous != null && previous.correctChoice in question.choices.indices -> previous.correctChoice
        else -> -1
    }
    val defaultAnswer = when {
        lesson != null -> {
            val label = if (lesson.isReviewed) "Resolução revisada por IA" else "Resolução gerada por IA · aula sem revisão"
            "$label\n\n${lesson.solution.answer}"
        }
        isChoice && resolvedCorrect in question.choices.indices ->
            "${'A' + resolvedCorrect}) ${question.choices[resolvedCorrect]}"
        previous?.answer?.isNotBlank() == true -> previous.answer
        official != null -> "Consulte a resolução oficial abaixo."
        else -> "Compare seu raciocínio com o enunciado e registre sua avaliação."
    }
    val base = previous ?: StudyItem(
        id = key,
        kind = kind,
        topic = question.topics.firstOrNull() ?: question.subject,
        prompt = question.prompt,
        credit = exam.creditLabel,
    )
    return base.copy(
        kind = kind,
        topic = question.topics.firstOrNull() ?: question.subject,
        prompt = "${exam.shortLabel} · ${question.code}\n\n${question.prompt}",
        choices = if (isChoice) question.choices else emptyList(),
        correctChoice = resolvedCorrect,
        sourceId = exam.sourceId,
        pages = question.pages,
        answer = defaultAnswer,
        hints = solution?.hints ?: base.hints,
        solution = solution?.steps ?: base.solution,
        credit = exam.creditLabel,
        officialSolutionUrl = official,
    )
}

/**
 * Merges global exam progress saved in [catalog] with any legacy progress previously stored
 * on FUVEST auto-generated boards, so existing users keep all their attempts and XP.
 */
internal suspend fun examQuestionProgress(library: LibraryStore, catalog: ExamCatalog): Map<String, Progress> =
    withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, Progress>()
        for (exam in catalog.exams) {
            val legacyBoardId = UUID.nameUUIDFromBytes("fuvest-board-${exam.sourceId}".toByteArray(Charsets.UTF_8)).toString()
            val board = library.notebook(legacyBoardId) ?: continue
            val studyFile = library.studyFile(board)
            if (!studyFile.exists() && !File(studyFile.path + ".bak").exists()) continue
            val boardProgress = StudyStore.load(studyFile).data.progress
            for (q in exam.questions) {
                val key = exam.key(q)
                boardProgress[key]?.let { merged[key] = it }
            }
        }
        for ((key, value) in catalog.progress) {
            val existing = merged[key]
            merged[key] = if (existing == null || value.attempts >= existing.attempts) value else existing
        }
        merged
    }

/** Ensures a notebook's source list includes this exam's PDF when pinning or viewing pages. */
internal fun StudySource?.orExamSource(library: LibraryStore, exam: PastExam): StudySource? =
    this ?: library.notebook(exam.sourceId)?.let { StudySource(it.id, it.name, it.pageCount) }
