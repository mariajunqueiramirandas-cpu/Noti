package app.noti.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class PaperTemplate(val label: String) { Blank("Blank"), Lined("Lined"), Grid("Grid"), Dots("Dots") }

enum class PaperColor(val label: String, val argb: Long, val isDark: Boolean) {
    White("White", 0xFFFFFFFF, false),
    Cream("Cream", 0xFFFBF6E9, false),
    Dark("Dark", 0xFF24272C, true),
}

/** Notebook paper proportions. Endless is a free canvas with no pages (see [EndlessCanvas]). */
enum class PaperSize(val label: String) {
    A4("A4"), Letter("Letter"), Endless("Endless"),
}

data class Paper(
    val template: PaperTemplate = PaperTemplate.Lined,
    val color: PaperColor = PaperColor.White,
    val size: PaperSize = PaperSize.Endless,
)

data class Notebook(
    val id: String,
    val name: String,
    val paper: Paper,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long,
    val openedAt: Long,
    /** Set when moved to Trash. */
    val trashedAt: Long? = null,
    /** An annotated PDF: its file name in [LibraryStore.pdfDir]. Null for a plain notebook. */
    val pdf: String? = null,
    val pageCount: Int = 1,
    /** Its ink is laid out on the Endless canvas (older Endless notebooks used A4 pages). */
    val canvasInk: Boolean = false,
    /** A Study board: an Endless canvas that studies linked PDFs with you (see app.noti.study). */
    val board: Boolean = false,
) {
    val isPdf get() = pdf != null
    /** A free canvas: one endless sheet instead of pages. */
    val isCanvas get() = pdf == null && paper.size == PaperSize.Endless
}

data class Folder(val id: String, val name: String)

enum class SortOrder(val label: String) { Recent("Last opened"), Name("Name"), Created("Date created") }

/**
 * Notebooks and folders, kept in memory as Compose state and written to a small JSON
 * file on every change. Canonical ink is saved separately in [inkDir].
 * Imported PDFs are copied into [pdfDir], with a first-page thumbnail beside each.
 */
class LibraryStore(context: Context) {
    /** For resources (fonts) when exporting outside the UI. */
    val appContext: Context = context.applicationContext
    private val file = File(context.filesDir, "library.json")
    private val readingPositions = context.getSharedPreferences("reading_positions", Context.MODE_PRIVATE)
    val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }

    val inkDir = File(context.filesDir, "ink").apply { mkdirs() }
    fun inkFile(nb: Notebook) = File(inkDir, "${nb.id}.noti")

    /** A Study board's study material (app.noti.study.StudyStore). */
    val studyDir = File(context.filesDir, "study").apply { mkdirs() }
    fun studyFile(nb: Notebook) = File(studyDir, "${nb.id}.json")

    fun pdfFile(nb: Notebook): File? = nb.pdf?.let { File(pdfDir, it) }
    fun thumbnailFile(nb: Notebook): File? = nb.pdf?.let { File(pdfDir, it.removeSuffix(".pdf") + ".png") }

    var notebooks by mutableStateOf<List<Notebook>>(emptyList())
        private set
    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set

    init {
        load()
    }

    fun notebook(id: String): Notebook? = notebooks.firstOrNull { it.id == id }

    fun lastPage(id: String): Int = readingPositions.getInt(id, 0).coerceAtLeast(0)

    fun rememberPage(id: String, page: Int) {
        if (page != lastPage(id)) readingPositions.edit().putInt(id, page).apply()
    }

    /** A [board] is always an Endless canvas. */
    fun create(name: String, paper: Paper, folderId: String?, board: Boolean = false, id: String = UUID.randomUUID().toString()): Notebook {
        require(notebook(id) == null)
        val now = System.currentTimeMillis()
        val sheet = if (board) paper.copy(size = PaperSize.Endless) else paper
        val nb = Notebook(
            id = id,
            name = name.ifBlank { if (board) "Study board" else "Untitled" },
            paper = sheet,
            folderId = folderId,
            createdAt = now,
            openedAt = now,
            canvasInk = sheet.size == PaperSize.Endless,
            board = board,
        )
        notebooks = notebooks + nb
        save()
        return nb
    }

    /**
     * Adds a notebook for a PDF already copied to [pdfDir] as `<id>.pdf` (see
     * app.noti.pdf.PdfImport). Main thread.
     */
    fun addPdf(id: String, name: String, pageCount: Int, folderId: String?): Notebook {
        val now = System.currentTimeMillis()
        val nb = Notebook(
            id = id,
            name = name.ifBlank { "Untitled PDF" },
            paper = Paper(PaperTemplate.Blank, PaperColor.White, PaperSize.Endless),
            folderId = folderId,
            createdAt = now,
            openedAt = now,
            pdf = "$id.pdf",
            pageCount = pageCount,
        )
        notebooks = notebooks + nb
        save()
        return nb
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.ifBlank { it.name }) }
    fun setPaper(id: String, paper: Paper) = update(id) { it.copy(paper = if (it.board) paper.copy(size = PaperSize.Endless) else paper) }
    fun setPageCount(id: String, count: Int) = update(id) { it.copy(pageCount = count.coerceAtLeast(1)) }
    /** The notebook's saved ink has been moved onto the Endless canvas. */
    fun setCanvasInk(id: String) = update(id) { it.copy(canvasInk = true, pageCount = 1) }
    fun markOpened(id: String) = update(id) { it.copy(openedAt = System.currentTimeMillis()) }

    fun setFavorite(ids: Set<String>, favorite: Boolean) = updateAll(ids) { it.copy(favorite = favorite) }
    fun move(ids: Set<String>, folderId: String?) = updateAll(ids) { it.copy(folderId = folderId) }
    fun trash(ids: Set<String>) = updateAll(ids) { it.copy(trashedAt = System.currentTimeMillis()) }
    fun restore(ids: Set<String>) = updateAll(ids) { it.copy(trashedAt = null) }

    fun deleteForever(ids: Set<String>) {
        val deleted = notebooks.filter { it.id in ids && it.trashedAt != null }
        val positions = readingPositions.edit()
        deleted.forEach { nb ->
            InkFile.delete(inkFile(nb))
            if (!nb.isPdf) app.noti.study.StudyStore.delete(studyFile(nb))
            pdfFile(nb)?.delete()
            thumbnailFile(nb)?.delete()
            positions.remove(nb.id)
        }
        positions.apply()
        notebooks = notebooks - deleted.toSet()
        save()
    }

    /** Main thread. Each copy is added to the library only after its files are ready. */
    suspend fun duplicate(ids: Set<String>): Int = withContext(NonCancellable) {
        val now = System.currentTimeMillis()
        val sources = notebooks.filter { it.id in ids }
        val copies = withContext(Dispatchers.IO) {
            sources.mapNotNull { nb ->
                val id = UUID.randomUUID().toString()
                val copy = nb.copy(id = id, name = "${nb.name} (copy)", createdAt = now, openedAt = now,
                    pdf = if (nb.isPdf) "$id.pdf" else null)
                runCatching {
                    if (nb.isPdf) {
                        pdfFile(nb)!!.copyTo(pdfFile(copy)!!)
                        thumbnailFile(nb)?.takeIf(File::exists)?.copyTo(thumbnailFile(copy)!!)
                    }
                    InkFile.open(inkFile(nb)).initial?.let { InkFile.replace(inkFile(copy), it) }
                    if (!nb.isPdf) app.noti.study.StudyStore.copy(studyFile(nb), studyFile(copy))
                    copy
                }.getOrElse {
                    InkFile.delete(inkFile(copy))
                    if (!nb.isPdf) app.noti.study.StudyStore.delete(studyFile(copy))
                    pdfFile(copy)?.delete()
                    thumbnailFile(copy)?.delete()
                    null
                }
            }
        }
        if (copies.isNotEmpty()) {
            val folderIds = folders.mapTo(mutableSetOf()) { it.id }
            notebooks = notebooks + copies.map { if (it.folderId in folderIds) it else it.copy(folderId = null) }
            save()
        }
        copies.size
    }

    fun createFolder(name: String): Folder {
        val f = Folder(UUID.randomUUID().toString(), name.ifBlank { "New folder" })
        folders = folders + f
        save()
        return f
    }

    fun renameFolder(id: String, name: String) {
        folders = folders.map { if (it.id == id && name.isNotBlank()) it.copy(name = name) else it }
        save()
    }

    /** Deletes the folder; its notebooks stay in the library, outside any folder. */
    fun deleteFolder(id: String) {
        folders = folders.filterNot { it.id == id }
        notebooks = notebooks.map { if (it.folderId == id) it.copy(folderId = null) else it }
        save()
    }

    private fun update(id: String, f: (Notebook) -> Notebook) = updateAll(setOf(id), f)

    private fun updateAll(ids: Set<String>, f: (Notebook) -> Notebook) {
        notebooks = notebooks.map { if (it.id in ids) f(it) else it }
        save()
    }

    // ---- persistence ----

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            folders = root.optJSONArray("folders").objects().map { Folder(it.getString("id"), it.getString("name")) }
            notebooks = root.optJSONArray("notebooks").objects().map { o ->
                Notebook(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    paper = Paper(
                        template = enumOr(o.optString("template"), PaperTemplate.Lined),
                        color = enumOr(o.optString("color"), PaperColor.White),
                        size = enumOr(o.optString("size"), PaperSize.Endless),
                    ),
                    folderId = o.optString("folderId").ifEmpty { null },
                    favorite = o.optBoolean("favorite"),
                    createdAt = o.optLong("createdAt"),
                    openedAt = o.optLong("openedAt"),
                    trashedAt = if (o.has("trashedAt")) o.getLong("trashedAt") else null,
                    pdf = o.optString("pdf").ifEmpty { null },
                    pageCount = o.optInt("pageCount", 1).coerceAtLeast(1),
                    canvasInk = o.optBoolean("canvasInk"),
                    board = o.optBoolean("board"),
                )
            }
        }
    }

    private fun save() {
        val root = JSONObject()
        root.put("folders", JSONArray(folders.map { JSONObject().put("id", it.id).put("name", it.name) }))
        root.put("notebooks", JSONArray(notebooks.map { nb ->
            JSONObject()
                .put("id", nb.id)
                .put("name", nb.name)
                .put("template", nb.paper.template.name)
                .put("color", nb.paper.color.name)
                .put("size", nb.paper.size.name)
                .put("folderId", nb.folderId ?: "")
                .put("favorite", nb.favorite)
                .put("createdAt", nb.createdAt)
                .put("openedAt", nb.openedAt)
                .apply { nb.trashedAt?.let { put("trashedAt", it) } }
                .put("pageCount", nb.pageCount)
                .put("canvasInk", nb.canvasInk)
                .put("board", nb.board)
                .apply { nb.pdf?.let { put("pdf", it) } }
        }))
        // Write-then-rename so a crash mid-write never leaves a truncated library.
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(root.toString())
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}

internal inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
