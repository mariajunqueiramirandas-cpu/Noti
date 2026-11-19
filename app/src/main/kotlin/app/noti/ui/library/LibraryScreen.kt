package app.noti.ui.library

import app.noti.ui.common.NotiButton as Button
import app.noti.ui.common.NotiTextButton as TextButton
import app.noti.ui.common.NotiIconButton as IconButton
import app.noti.ui.common.NotiAlertDialog as AlertDialog
import app.noti.ui.common.NotiDropdownMenu as DropdownMenu
import app.noti.ui.common.NotiMenuItem as DropdownMenuItem

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.noti.data.Folder
import app.noti.data.InkFile
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.Paper
import app.noti.data.PaperTemplate
import app.noti.data.SettingsStore
import app.noti.data.SortOrder
import app.noti.pdf.DocumentExport
import app.noti.pdf.PdfImport
import app.noti.ui.brand.DotiMascot
import app.noti.ui.brand.NotiWordmark
import app.noti.ui.common.PaperPicker
import app.noti.ui.common.PaperPreview
import app.noti.ui.common.TextInputDialog
import app.noti.ui.theme.Noti
import app.noti.ui.theme.NotiBrand
import java.io.File
import java.text.Collator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the library is showing. */
private sealed interface Filter {
    data object All : Filter
    data object Recent : Filter
    data object Favorites : Filter
    data object Pdfs : Filter
    data class InFolder(val id: String) : Filter
    data object Trash : Filter
}

private val filterSaver = Saver<Filter, String>(
    save = {
        when (it) {
            Filter.All -> "all"
            Filter.Recent -> "recent"
            Filter.Favorites -> "favorites"
            Filter.Pdfs -> "pdfs"
            Filter.Trash -> "trash"
            is Filter.InFolder -> "folder:${it.id}"
        }
    },
    restore = {
        when (it) {
            "recent" -> Filter.Recent
            "favorites" -> Filter.Favorites
            "pdfs" -> Filter.Pdfs
            "trash" -> Filter.Trash
            else -> if (it.startsWith("folder:")) Filter.InFolder(it.removePrefix("folder:")) else Filter.All
        }
    },
)

private enum class LibraryAction { Select, Favorite, Move, Rename, Duplicate, Export, Trash, Restore, DeleteForever }

@Composable
fun LibraryScreen(
    library: LibraryStore,
    settings: SettingsStore,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onStudy: (() -> Unit)? = null,
) {
    var filter by rememberSaveable(stateSaver = filterSaver) { mutableStateOf<Filter>(Filter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    val sort = settings.librarySort
    val listView = settings.libraryList
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var creating by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingNotebook by remember { mutableStateOf<Notebook?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var movingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingFolder by remember { mutableStateOf<Folder?>(null) }
    var duplicating by remember { mutableStateOf(false) }
    var confirmDeleteForever by remember { mutableStateOf<Set<String>?>(null) }
    var importProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    fun changeFilter(next: Filter) {
        if (next != filter) {
            filter = next
            query = ""
            selection = emptySet()
            grid.requestScrollToItem(0)
        }
        scope.launch { drawer.close() }
    }
    fun changeQuery(next: String) {
        query = next
        grid.requestScrollToItem(0)
    }
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val folderId = (filter as? Filter.InFolder)?.id
        scope.launch {
            val imported = mutableListOf<Notebook>()
            val errors = mutableListOf<String>()
            for ((index, uri) in uris.withIndex()) {
                ensureActive()
                importProgress = (index + 1) to uris.size
                withContext(NonCancellable) {
                    val result = withContext(Dispatchers.IO) { runCatching { PdfImport.import(context, uri, library.pdfDir) } }
                    result.onSuccess { imported += library.addPdf(it.id, it.name, it.pageCount, folderId) }
                        .onFailure { errors += it.message ?: "Couldn't import that PDF." }
                }
            }
            ensureActive()
            importProgress = null
            if (uris.size == 1 && imported.isNotEmpty()) {
                onOpen(imported.single().id)
            } else {
                if (imported.isNotEmpty()) {
                    if (folderId == null) changeFilter(Filter.Pdfs)
                    changeQuery("")
                }
                val message = when {
                    errors.isEmpty() -> "${count(imported.size, "PDF")} imported"
                    imported.isEmpty() -> errors.first()
                    else -> "Imported ${imported.size} of ${uris.size} PDFs. ${errors.first()}"
                }
                snackbar.showSnackbar(message, withDismissAction = true)
            }
        }
    }
    var exportIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var exporting by remember { mutableStateOf(false) }
    fun exportTo(uri: android.net.Uri?) {
        if (uri == null) return
        val chosen = exportIds.mapNotNull(library::notebook)
        if (chosen.size != exportIds.size || chosen.isEmpty()) {
            Toast.makeText(context, "The selected documents are no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        exporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (chosen.size == 1) {
                        val nb = chosen.single()
                        DocumentExport.writePdf(context, nb, library, InkFile.open(library.inkFile(nb)).initial, uri)
                    } else {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                            ZipOutputStream(stream).use { zip ->
                                chosen.forEachIndexed { i, nb ->
                                    val safeName = nb.name.replace(Regex("[^a-zA-Z0-9 ._-]"), "_")
                                    zip.putNextEntry(ZipEntry("${i + 1}-$safeName.pdf"))
                                    DocumentExport.writePdf(nb, library, InkFile.open(library.inkFile(nb)).initial, zip)
                                    zip.closeEntry()
                                }
                            }
                        } ?: error("Couldn't open destination")
                    }
                }
            }
            exporting = false
            Toast.makeText(context, if (result.isSuccess) "Export complete" else "Couldn't export. Try another destination.", Toast.LENGTH_LONG).show()
        }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), ::exportTo)
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::exportTo)
    val onImportPdf = { importPdf.launch(arrayOf("application/pdf")) }

    fun runAction(action: LibraryAction, ids: Set<String>) {
        when (action) {
            LibraryAction.Select -> selection = ids
            LibraryAction.Favorite -> {
                library.setFavorite(ids, !ids.all { library.notebook(it)?.favorite == true })
                if (filter == Filter.Favorites) selection = selection.filterTo(mutableSetOf()) { library.notebook(it)?.favorite == true }
            }
            LibraryAction.Move -> movingIds = ids
            LibraryAction.Rename -> renamingNotebook = library.notebook(ids.single())
            LibraryAction.Duplicate -> if (!duplicating) {
                duplicating = true
                scope.launch {
                    val copied = try { library.duplicate(ids) } finally { duplicating = false }
                    selection = emptySet()
                    val message = if (copied == ids.size) "${count(copied, "copy", "copies")} created"
                        else "Copied $copied of ${ids.size} documents"
                    snackbar.showSnackbar(message, withDismissAction = true)
                }
            }
            LibraryAction.Export -> if (!exporting) {
                exportIds = ArrayList(library.notebooks.filter { it.id in ids }.map { it.id })
                if (exportIds.size == 1) {
                    val name = library.notebook(exportIds.single())!!.name.replace(Regex("[^a-zA-Z0-9 ._-]"), "_")
                    exportPdf.launch("$name.pdf")
                } else if (exportIds.isNotEmpty()) exportZip.launch("Noti documents.zip")
            }
            LibraryAction.Trash -> {
                library.trash(ids)
                selection = emptySet()
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar("${count(ids.size, "document")} moved to trash", actionLabel = "Undo",
                            duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        library.restore(ids)
                    }
                }
            }
            LibraryAction.Restore -> {
                library.restore(ids)
                selection = emptySet()
                scope.launch { snackbar.showSnackbar("${count(ids.size, "document")} restored", withDismissAction = true) }
            }
            LibraryAction.DeleteForever -> confirmDeleteForever = ids
        }
    }

    BackHandler(enabled = drawer.isClosed && (selection.isNotEmpty() || query.isNotEmpty() || filter != Filter.All)) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            query.isNotEmpty() -> changeQuery("")
            else -> changeFilter(Filter.All)
        }
    }

    val studyCandidates = library.notebooks.filter { !it.isPdf && it.trashedAt == null }
    val due by produceState(emptyMap<String, Int>(), studyCandidates.map { it.id }) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            studyCandidates.mapNotNull { nb ->
                val f = library.studyFile(nb)
                if (!f.exists() && !File(f.path + ".bak").exists()) null
                else nb.id to app.noti.study.StudyStore.dueCount(f, now)
            }.toMap()
        }
    }

    val inTrash = filter == Filter.Trash
    val folderNames = remember(library.folders) { library.folders.associate { it.id to it.name } }
    val collator = remember { Collator.getInstance().apply { strength = Collator.PRIMARY } }
    val filtered = library.notebooks.filter { nb ->
        if (inTrash) nb.trashedAt != null
        else nb.trashedAt == null && when (val active = filter) {
            Filter.Favorites -> nb.favorite
            Filter.Pdfs -> nb.isPdf
            is Filter.InFolder -> nb.folderId == active.id
            else -> true
        }
    }
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val visible = filtered.filter { nb ->
        val searchable = listOf(nb.name, nb.kind, folderNames[nb.folderId].orEmpty())
        terms.all { term -> searchable.any { it.contains(term, ignoreCase = true) } }
    }.let { list ->
        when {
            filter == Filter.Recent -> list.sortedByDescending { it.openedAt }.let { if (terms.isEmpty()) it.take(20) else it }
            sort == SortOrder.Name -> list.sortedWith(compareBy(collator) { it.name })
            sort == SortOrder.Created -> list.sortedByDescending { it.createdAt }
            inTrash -> list.sortedByDescending { it.trashedAt }
            else -> list.sortedByDescending { it.openedAt }
        }
    }
    val visibleIds = visible.mapTo(linkedSetOf()) { it.id }
    val title = when (val f = filter) {
        Filter.All -> "Library"
        Filter.Recent -> "Recent"
        Filter.Favorites -> "Favorites"
        Filter.Pdfs -> "PDFs"
        is Filter.InFolder -> library.folders.firstOrNull { it.id == f.id }?.name ?: "Folder"
        Filter.Trash -> "Trash"
    }

    val subtitle = when {
        terms.isNotEmpty() -> count(visible.size, "result")
        filter == Filter.Pdfs -> count(visible.size, "PDF")
        visible.all { !it.isPdf } && !inTrash -> count(visible.size, "notebook")
        else -> count(visible.size, "document")
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val compact = maxWidth < 840.dp || maxHeight < 480.dp
            LaunchedEffect(compact) { if (!compact) drawer.close() }
            val navigation: @Composable () -> Unit = {
                Sidebar(
                    library = library,
                    filter = filter,
                    onFilter = ::changeFilter,
                    onNewFolder = { creatingFolder = true; scope.launch { drawer.close() } },
                    onRenameFolder = { renamingFolder = it; scope.launch { drawer.close() } },
                    onDeleteFolder = { deletingFolder = it; scope.launch { drawer.close() } },
                    onSettings = { scope.launch { drawer.close(); onSettings() } },
                    onStudy = onStudy?.let { open -> { scope.launch { drawer.close(); open() }; Unit } },
                )
            }
            val content: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (selection.isEmpty()) {
                            TopBar(
                                title = title,
                                subtitle = subtitle,
                                onNavigation = if (compact) ({ scope.launch { drawer.open() }; Unit }) else null,
                                query = query,
                                onQuery = ::changeQuery,
                                sort = sort,
                                onSort = { settings.librarySort = it; grid.requestScrollToItem(0) },
                                showSort = filter != Filter.Recent,
                                listView = listView,
                                showLayout = visible.isNotEmpty(),
                                onToggleLayout = { settings.libraryList = !listView },
                                inTrash = inTrash,
                                trashNotEmpty = filtered.isNotEmpty(),
                                onNew = { creating = true },
                                onImportPdf = onImportPdf,
                                onEmptyTrash = { confirmDeleteForever = filtered.mapTo(mutableSetOf()) { it.id } },
                            )
                        } else {
                            SelectionBar(
                                count = selection.size,
                                allSelected = selection.containsAll(visibleIds),
                                inTrash = inTrash,
                                allFavorite = selection.all { id -> library.notebook(id)?.favorite == true },
                                onClear = { selection = emptySet() },
                                onSelectAll = { selection = visibleIds },
                                onAction = { runAction(it, selection) },
                            )
                        }

                        if (visible.isEmpty()) {
                            EmptyState(filter, terms.isNotEmpty(), onNew = { creating = true },
                                onImportPdf = onImportPdf, onClearSearch = { changeQuery("") })
                        } else {
                            val gutter = if (compact) 16.dp else 32.dp
                            LazyVerticalGrid(
                                columns = if (listView) GridCells.Fixed(1) else GridCells.Adaptive(if (compact) 136.dp else 164.dp),
                                state = grid,
                                contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp),
                                verticalArrangement = Arrangement.spacedBy(if (listView) 8.dp else 20.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(visible, key = { it.id }) { nb ->
                                    NotebookCard(
                                        nb = nb,
                                        thumbnail = library.thumbnailFile(nb),
                                        selected = nb.id in selection,
                                        selecting = selection.isNotEmpty(),
                                        inTrash = inTrash,
                                        listView = listView,
                                        folderName = folderNames[nb.folderId].takeUnless { filter is Filter.InFolder },
                                        onTap = {
                                            if (selection.isNotEmpty() || inTrash) selection = selection.toggle(nb.id)
                                            else onOpen(nb.id)
                                        },
                                        onLongPress = { selection = selection.toggle(nb.id) },
                                        onAction = { runAction(it, setOf(nb.id)) },
                                        due = due[nb.id] ?: 0,
                                    )
                                }
                            }
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = compact,
                drawerContent = {
                    if (compact) ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background,
                        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                        modifier = Modifier.width(280.dp)) { navigation() }
                },
            ) {
                if (compact) content() else Row(Modifier.fillMaxSize()) {
                    navigation()
                    // The content is a sheet of paper resting on the desk.
                    Surface(
                        color = Noti.extras.sheet,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 12.dp, bottom = 12.dp),
                    ) { content() }
                }
            }
        }
    }

    // ---- dialogs ----

    if (duplicating) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Making copies") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    }
    importProgress?.let { (current, total) ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(if (total == 1) "Importing PDF" else "Importing PDFs") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(if (total == 1) "Copying file…" else "Copying file $current of $total…")
                }
            },
        )
    }
    if (creating) {
        NewNotebookDialog(
            initialPaper = Paper(settings.defaultTemplate, settings.defaultColor),
            initialBoard = creatingBoard,
            onDismiss = { creating = false },
            onCreate = { name, paper, board ->
                creating = false
                val nb = library.create(name, paper, (filter as? Filter.InFolder)?.id, board)
                onOpen(nb.id)
            },
        )
    }
    if (creatingFolder) {
        TextInputDialog("New folder", "", "Create", onDismiss = { creatingFolder = false }) {
            val f = library.createFolder(it)
            creatingFolder = false
            changeFilter(Filter.InFolder(f.id))
        }
    }
    renamingNotebook?.let { nb ->
        TextInputDialog("Rename notebook", nb.name, "Rename", onDismiss = { renamingNotebook = null }) {
            library.rename(nb.id, it)
            renamingNotebook = null
            selection = emptySet()
        }
    }
    renamingFolder?.let { f ->
        TextInputDialog("Rename folder", f.name, "Rename", onDismiss = { renamingFolder = null }) {
            library.renameFolder(f.id, it)
            renamingFolder = null
        }
    }
    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text("Delete ${folder.name}?") },
            text = { Text("Its documents will stay in your library without a folder.") },
            confirmButton = {
                Button(onClick = {
                    if (filter == Filter.InFolder(folder.id)) changeFilter(Filter.All)
                    library.deleteFolder(folder.id)
                    deletingFolder = null
                }, destructive = true) { Text("Delete folder") }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text("Cancel") } },
        )
    }
    if (movingIds.isNotEmpty()) {
        fun moveTo(folderId: String?) {
            library.move(movingIds, folderId)
            movingIds = emptySet()
            selection = emptySet()
            val destination = folderNames[folderId] ?: library.folders.firstOrNull { it.id == folderId }?.name
            scope.launch { snackbar.showSnackbar(destination?.let { "Moved to $it" } ?: "Moved out of folders", withDismissAction = true) }
        }
        MoveDialog(
            folders = library.folders,
            onDismiss = { movingIds = emptySet() },
            onMove = ::moveTo,
            onNewFolder = { moveTo(library.createFolder(it).id) },
        )
    }
    confirmDeleteForever?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDeleteForever = null },
            title = { Text(if (ids.size == 1) "Delete forever?" else "Delete ${ids.size} documents forever?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                app.noti.ui.common.NotiButton(destructive = true, onClick = {
                    library.deleteForever(ids)
                    confirmDeleteForever = null
                    selection = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteForever = null }) { Text("Cancel") } },
        )
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private fun count(n: Int, singular: String, plural: String = "${singular}s") = "$n ${if (n == 1) singular else plural}"

private val Notebook.kind: String get() = if (isPdf) "PDF" else "Notebook"

// ---- sidebar ----

@Composable
private fun Sidebar(
    library: LibraryStore,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    onNewFolder: () -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onSettings: () -> Unit,
    onStudy: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(248.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 20.dp),
    ) {
        NotiWordmark(Modifier.padding(start = 10.dp), size = 26)
        Text(NotiBrand.Tagline, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 28.dp))
        SideItem("All", Icons.AutoMirrored.Outlined.LibraryBooks, filter == Filter.All) { onFilter(Filter.All) }
        SideItem("Recent", Icons.Outlined.History, filter == Filter.Recent) { onFilter(Filter.Recent) }
        SideItem("Favorites", Icons.Outlined.StarBorder, filter == Filter.Favorites) { onFilter(Filter.Favorites) }
        SideItem("PDFs", Icons.Outlined.PictureAsPdf, filter == Filter.Pdfs) { onFilter(Filter.Pdfs) }
        onStudy?.let { SideItem("Study", Icons.Outlined.School, false, onClick = it) }

        Row(Modifier.padding(start = 14.dp, top = 24.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Folders", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onNewFolder) {
                Icon(Icons.Outlined.Add, contentDescription = "New folder", modifier = Modifier.size(20.dp),
                    tint = colors.onSurfaceVariant)
            }
        }
        val folders = library.folders.sortedBy { it.name.lowercase() }
        if (folders.isEmpty()) {
            Text("Group notebooks by subject, project or mood.", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, end = 8.dp, bottom = 8.dp))
        }
        folders.forEach { f ->
            var menu by remember { mutableStateOf(false) }
            Box {
                SideItem(
                    f.name, Icons.Outlined.Folder, filter == Filter.InFolder(f.id),
                    trailing = {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreHoriz, contentDescription = "Folder options", modifier = Modifier.size(18.dp),
                                tint = colors.onSurfaceVariant)
                        }
                    },
                ) { onFilter(Filter.InFolder(f.id)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                        onClick = { menu = false; onRenameFolder(f) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete folder") }, destructive = true,
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menu = false
                            onDeleteFolder(f)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SideItem("Trash", Icons.Outlined.Delete, filter == Filter.Trash) { onFilter(Filter.Trash) }
        SideItem("Settings", Icons.Outlined.Settings, false, onClick = onSettings)
    }
}

/** A quiet pill row: tinted when it is where you are, transparent otherwise. */
@Composable
private fun SideItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 14.dp, end = if (trailing == null) 14.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ---- top bars ----

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onNavigation: (() -> Unit)?,
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    showSort: Boolean,
    listView: Boolean,
    showLayout: Boolean,
    onToggleLayout: () -> Unit,
    inTrash: Boolean,
    trashNotEmpty: Boolean,
    onNew: () -> Unit,
    onImportPdf: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    var viewMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val compact = onNavigation != null
    val gutter = if (compact) 16.dp else 32.dp
    val layoutAction = if (listView) "Show grid view" else "Show list view"
    Column(
        Modifier.fillMaxWidth().padding(start = if (compact) 8.dp else gutter, end = gutter,
            top = if (compact) 8.dp else 28.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            onNavigation?.let { open ->
                IconButton(onClick = open) { Icon(Icons.Outlined.Menu, "Open navigation") }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showSort || showLayout) Box {
                IconButton(onClick = { viewMenu = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "View options", tint = colors.onSurfaceVariant)
                }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    if (showLayout) DropdownMenuItem(
                        text = { Text(layoutAction) },
                        leadingIcon = { Icon(if (listView) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList, null) },
                        onClick = { viewMenu = false; onToggleLayout() },
                    )
                    if (showSort) {
                        if (showLayout) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Sort by", Modifier.padding(start = 18.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        SortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(if (inTrash && order == SortOrder.Recent) "Date deleted" else order.label) },
                                onClick = { viewMenu = false; onSort(order) },
                                trailingIcon = if (order == sort) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                            )
                        }
                    }
                }
            }
            if (inTrash) {
                TextButton(onClick = onEmptyTrash, enabled = trashNotEmpty, destructive = true) { Text("Empty trash") }
            } else {
                Box {
                    Button(onClick = { newMenu = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New")
                    }
                    DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Notebook") },
                            leadingIcon = { Icon(Icons.Outlined.Description, null) },
                            onClick = { newMenu = false; onNew() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import PDF") },
                            leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                            onClick = { newMenu = false; onImportPdf() },
                        )
                    }
                }
            }
        }
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(if (inTrash) "Search trash" else "Search this view", maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = colors.onSurfaceVariant) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Outlined.Close, "Clear search")
                }
            },
            singleLine = true,
            shape = CircleShape,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = colors.surfaceContainer,
                focusedContainerColor = colors.surfaceContainerHigh,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(start = if (compact) 8.dp else 0.dp).heightIn(min = 52.dp),
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    inTrash: Boolean,
    allFavorite: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(12.dp).clip(CircleShape).background(colors.primaryContainer).padding(4.dp),
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "Clear selection", tint = colors.onPrimaryContainer) }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSelectAll, enabled = !allSelected) {
            Icon(Icons.Outlined.SelectAll, "Select all visible documents")
        }
        ActionMenu("Selection actions", inTrash, allFavorite, single = count == 1, selecting = true, onAction)
    }
}

@Composable
private fun ActionMenu(
    description: String,
    inTrash: Boolean,
    favorite: Boolean,
    single: Boolean,
    selecting: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreHoriz, description) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(action: LibraryAction, label: String, icon: ImageVector, destructive: Boolean = false) {
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, destructive = destructive,
                    onClick = { open = false; onAction(action) })
            }
            if (!selecting) item(LibraryAction.Select, "Select", Icons.Outlined.Check)
            if (inTrash) {
                item(LibraryAction.Restore, "Restore", Icons.Outlined.RestoreFromTrash)
                item(LibraryAction.DeleteForever, "Delete forever", Icons.Outlined.DeleteForever, destructive = true)
            } else {
                item(LibraryAction.Favorite, if (favorite) "Unfavorite" else "Favorite",
                    if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder)
                if (single) item(LibraryAction.Rename, "Rename", Icons.Outlined.DriveFileRenameOutline)
                item(LibraryAction.Move, "Move to folder", Icons.AutoMirrored.Outlined.DriveFileMove)
                item(LibraryAction.Duplicate, "Duplicate", Icons.Outlined.ContentCopy)
                item(LibraryAction.Export, if (single) "Export PDF" else "Export", Icons.Outlined.IosShare)
                item(LibraryAction.Trash, "Move to trash", Icons.Outlined.Delete, destructive = true)
            }
        }
    }
}

private val PageShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    nb: Notebook,
    thumbnail: File?,
    selected: Boolean,
    selecting: Boolean,
    inTrash: Boolean,
    listView: Boolean,
    folderName: String?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onAction: (LibraryAction) -> Unit,
    due: Int,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val target = Modifier.fillMaxWidth().clip(shape)
        .background(when {
            selected -> colors.primaryContainer
            listView -> colors.surfaceContainerLow
            else -> Color.Transparent
        })
        .combinedClickable(
            onClick = onTap,
            onClickLabel = if (inTrash || selecting) "Toggle selection" else "Open ${nb.kind.lowercase()}",
            onLongClick = onLongPress,
            onLongClickLabel = "Toggle selection",
        )
        .semantics { if (selecting) this.selected = selected }
    val options: @Composable () -> Unit = {
        if (!selecting) ActionMenu("Options for ${nb.name}", inTrash, nb.favorite, single = true, selecting = false, onAction)
    }
    if (listView) {
        Row(target.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DocumentCover(nb, thumbnail, inTrash, selected, Modifier.size(width = 46.dp, height = 56.dp))
            DocumentDetails(nb, folderName, inTrash, due, Modifier.weight(1f).padding(horizontal = 12.dp))
            options()
        }
    } else {
        Column(target.padding(4.dp)) {
            DocumentCover(nb, thumbnail, inTrash, selected, Modifier.fillMaxWidth().aspectRatio(0.84f))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                DocumentDetails(nb, folderName, inTrash, due, Modifier.weight(1f).padding(start = 2.dp))
                options()
            }
        }
    }
}

@Composable
private fun DocumentCover(nb: Notebook, thumbnail: File?, inTrash: Boolean, selected: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    val cover = extras.covers[Math.floorMod(nb.id.hashCode(), extras.covers.size)]
    Box(modifier) {
        Box(
            Modifier.matchParentSize()
                .shadow(if (selected) 0.dp else 2.dp, PageShape, ambientColor = extras.shadow, spotColor = extras.shadow)
                .clip(PageShape)
                .background(Color.White)
                .border(if (selected) 2.dp else 0.5.dp, if (selected) colors.primary else colors.outlineVariant, PageShape),
        ) {
            if (nb.isPdf) PdfThumbnail(thumbnail, Modifier.fillMaxSize())
            else {
                PaperPreview(nb.paper, Modifier.fillMaxSize())
                Box(Modifier.fillMaxHeight().width(7.dp).background(cover))
            }
        }
        if (nb.favorite && !inTrash) Bookmark(extras.bookmark, Modifier.align(Alignment.TopEnd).padding(end = 10.dp))
        if (selected) Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).background(colors.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.onPrimary) }
    }
}

@Composable
private fun DocumentDetails(nb: Notebook, folderName: String?, inTrash: Boolean, due: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = when {
        nb.isCanvas -> "Endless notebook"
        else -> "${nb.kind} · ${count(nb.pageCount, "page")}"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(nb.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (inTrash) "Deleted ${relative(nb.trashedAt ?: 0)}" else type,
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!inTrash) {
            val detail = when {
                due > 0 -> count(due, "review") + " due"
                folderName != null -> folderName
                else -> relative(nb.openedAt).replaceFirstChar { it.uppercase() }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = if (due > 0) colors.primary else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A ribbon hanging from the top edge of the page. */
@Composable
private fun Bookmark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp, 24.dp).semantics { contentDescription = "Favorite" }) {
        val notch = size.height * 0.72f
        drawPath(Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
            lineTo(size.width / 2f, notch); lineTo(0f, size.height); close()
        }, color)
    }
}

/** The first page of an imported PDF (rendered at import), white until it loads. */
@Composable
private fun PdfThumbnail(file: File?, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { file?.takeIf(File::exists)?.let { BitmapFactory.decodeFile(it.path) } }
    }
    Box(modifier.background(Color.White)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun relative(t: Long): String =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().let {
        if (it == "0 minutes ago" || it == "In 0 minutes") "just now" else it.replaceFirstChar { c -> c.lowercase() }
    }

@Composable
private fun EmptyState(filter: Filter, searching: Boolean, onNew: () -> Unit, onImportPdf: () -> Unit, onClearSearch: () -> Unit) {
    val (headline, body) = when {
        searching -> "Nothing found" to "Try a document name, folder, or type."
        filter == Filter.Pdfs -> "No PDFs yet" to "Import a PDF to write on it."
        filter == Filter.Trash -> "Trash is empty" to "Deleted notebooks wait here until you delete them forever."
        filter == Filter.Favorites -> "No favorites yet" to "Open a document’s options and choose Favorite."
        filter is Filter.InFolder -> "This folder is empty" to "Create a notebook here, or move notebooks in from All."
        else -> "A fresh start. A little possibility." to "Start a notebook, or import a PDF to write on."
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        DotiMascot(Modifier.size(if (searching || filter == Filter.Trash) 72.dp else 112.dp))
        Spacer(Modifier.height(20.dp))
        Text(headline, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp), style = MaterialTheme.typography.bodyMedium)
        if (searching) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onClearSearch) { Text("Clear search") }
        } else if (filter == Filter.Pdfs) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onImportPdf) {
                Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import PDF")
            }
        } else if (filter != Filter.Trash && filter != Filter.Favorites) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNew) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("New notebook")
            }
        }
    }
}

// ---- dialogs ----

@Composable
private fun NewNotebookDialog(initialPaper: Paper, onDismiss: () -> Unit, onCreate: (String, Paper) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var paper by remember { mutableStateOf(initialPaper) }
    app.noti.ui.common.NotiDialogFrame(onDismiss,
        title = { Text("New notebook") }, icon = { DotiMascot(Modifier.size(40.dp)) }, maxWidth = 540.dp,
        actions = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onCreate(name.trim(), paper) }) { Text("Create notebook") }
        }) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
            placeholder = { Text("What’s on your mind?") }, singleLine = true,
            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
        PaperPicker(paper, onChange = { paper = it }, showSize = true)
    }
}

@Composable
private fun MoveDialog(
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
    onNewFolder: (String) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        TextInputDialog("New folder", "", "Create and move", onDismiss = { naming = false }) { onNewFolder(it) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            Column {
                MoveRow("No folder", Icons.AutoMirrored.Outlined.LibraryBooks) { onMove(null) }
                folders.sortedBy { it.name.lowercase() }.forEach { f -> MoveRow(f.name, Icons.Outlined.Folder) { onMove(f.id) } }
                MoveRow("New folder…", Icons.Outlined.CreateNewFolder) { naming = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
