package app.noti.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class PaperTemplate(val label: String) { Blank("Blank"), Lined("Lined"), Grid("Grid"), Dots("Dots") }

enum class PaperColor(val label: String, val argb: Long, val isDark: Boolean) {
    White("White", 0xFFFFFFFF, false),
    Cream("Cream", 0xFFFBF6E9, false),
    Dark("Dark", 0xFF24272C, true),
}

/** Notebook paper proportions; Endless pages use the A4 writing proportion. */
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
) {
    val isPdf get() = pdf != null
}

data class Folder(val id: String, val name: String)

enum class SortOrder(val label: String) { Recent("Last opened"), Name("Name"), Created("Date created") }

/**
 * Notebooks and folders, kept in memory as Compose state and written to a small JSON
 * file on every change. Canonical ink is saved separately in [inkDir].
 * Imported PDFs are copied into [pdfDir], with a first-page thumbnail beside each.
 */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")
    val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }

    val inkDir = File(context.filesDir, "ink").apply { mkdirs() }
    fun inkFile(nb: Notebook) = File(inkDir, "${nb.id}.noti")

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

    fun create(name: String, paper: Paper, folderId: String?): Notebook {
        val now = System.currentTimeMillis()
        val nb = Notebook(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled" },
            paper = paper,
            folderId = folderId,
            createdAt = now,
            openedAt = now,
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
    fun setPaper(id: String, paper: Paper) = update(id) { it.copy(paper = paper) }
    fun setPageCount(id: String, count: Int) = update(id) { it.copy(pageCount = count.coerceAtLeast(1)) }
    fun markOpened(id: String) = update(id) { it.copy(openedAt = System.currentTimeMillis()) }

    fun setFavorite(ids: Set<String>, favorite: Boolean) = updateAll(ids) { it.copy(favorite = favorite) }
    fun move(ids: Set<String>, folderId: String?) = updateAll(ids) { it.copy(folderId = folderId) }
    fun trash(ids: Set<String>) = updateAll(ids) { it.copy(trashedAt = System.currentTimeMillis()) }
    fun restore(ids: Set<String>) = updateAll(ids) { it.copy(trashedAt = null) }

    fun deleteForever(ids: Set<String>) {
        notebooks.filter { it.id in ids }.forEach { nb ->
            InkFile.delete(inkFile(nb))
            pdfFile(nb)?.delete()
            thumbnailFile(nb)?.delete()
        }
        notebooks = notebooks.filterNot { it.id in ids }
        save()
    }

    fun duplicate(ids: Set<String>) {
        val now = System.currentTimeMillis()
        val copies = notebooks.filter { it.id in ids }.mapNotNull { nb ->
            val id = UUID.randomUUID().toString()
            // A copy of an annotated PDF gets its own copy of the file; skip it if that fails.
            val pdf = if (nb.pdf == null) null else runCatching {
                pdfFile(nb)!!.copyTo(File(pdfDir, "$id.pdf"))
                thumbnailFile(nb)?.takeIf(File::exists)?.copyTo(File(pdfDir, "$id.png"))
                "$id.pdf"
            }.getOrNull() ?: return@mapNotNull null
            runCatching { InkFile.open(inkFile(nb)).initial?.let { File(inkDir, "$id.noti").writeBytes(it) } }
                .getOrElse { return@mapNotNull null }
            nb.copy(id = id, name = "${nb.name} (copy)", createdAt = now, openedAt = now, pdf = pdf)
        }
        notebooks = notebooks + copies
        save()
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
                .apply { nb.pdf?.let { put("pdf", it) } }
        }))
        // Write-then-rename so a crash mid-write never leaves a truncated library.
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}

internal inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
