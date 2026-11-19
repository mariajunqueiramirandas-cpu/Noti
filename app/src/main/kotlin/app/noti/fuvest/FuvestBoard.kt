package app.noti.fuvest

import android.graphics.Bitmap
import app.noti.data.LibraryStore
import app.noti.data.Paper
import app.noti.data.PaperTemplate
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

internal suspend fun renderFuvestPage(file: File, page: Int): ByteArray = withContext(Dispatchers.IO) {
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

/** One deck and one official question ID per exam, even when opened from different topics. */
internal suspend fun prepareFuvestBoard(
    library: LibraryStore,
    catalog: FuvestCatalog,
    exam: FuvestExam,
    focus: FuvestQuestion,
) {
    val source = library.notebook(exam.sourceId)
    val file = File(library.studyDir, "${exam.boardId}.json")
    val solutions = catalog.lessons.values.sortedBy { it.isReviewed }.associateBy { it.questionKey }
    withContext(Dispatchers.IO) {
        StudyStore.editSaved(file) { deck ->
            val generated = exam.questions.map { question ->
                val id = exam.key(question)
                val previous = deck.item(id)
                val lesson = solutions[id]
                val solution = lesson?.solution
                val official = catalog.officialSolution(FuvestReference(exam, question))
                val item = previous ?: StudyItem(
                    id = id, kind = ItemKind.Problem, topic = question.topics.first(),
                    prompt = question.prompt, credit = "FUVEST",
                )
                item.copy(
                    topic = question.topics.first(),
                    prompt = "${exam.year} · ${question.code}\n\n${question.prompt}",
                    sourceId = exam.sourceId,
                    pages = question.pages,
                    answer = lesson?.let {
                        val label = if (it.isReviewed) "Resolução revisada por IA" else "Resolução gerada por IA · aula sem revisão"
                        "$label\n\n${it.solution.answer}"
                    }
                        ?: previous?.answer?.takeIf { it.isNotBlank() }
                        ?: if (official != null) "Consulte a resolução da banca abaixo." else "Compare seu raciocínio com o enunciado e registre sua avaliação.",
                    hints = solution?.hints ?: item.hints,
                    solution = solution?.steps ?: item.solution,
                    officialSolutionUrl = official,
                )
            }
            val sourceInfo = source?.let { StudySource(it.id, it.name, it.pageCount) }
            deck.copy(
                sources = if (sourceInfo == null) deck.sources else deck.sources.filterNot { it.id == sourceInfo.id } + sourceInfo,
                items = generated + deck.items.filterNot { old -> generated.any { it.id == old.id } },
                current = exam.key(focus),
            )
        }
    }
    val board = library.notebook(exam.boardId)
    if (board == null) {
        library.create(exam.title, Paper(PaperTemplate.Grid), folderId = null, board = true, id = exam.boardId)
    } else if (board.trashedAt != null) library.restore(setOf(board.id))
}

internal suspend fun fuvestQuestionProgress(library: LibraryStore, exams: List<FuvestExam>): Map<String, Progress> =
    withContext(Dispatchers.IO) {
        exams.flatMap { exam ->
            val board = library.notebook(exam.boardId) ?: return@flatMap emptyList()
            val progress = StudyStore.load(library.studyFile(board)).data.progress
            exam.questions.mapNotNull { question -> exam.key(question).let { key -> progress[key]?.let { key to it } } }
        }.toMap()
    }
