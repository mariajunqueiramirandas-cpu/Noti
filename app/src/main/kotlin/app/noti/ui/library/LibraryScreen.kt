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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
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

@Composable
fun LibraryScreen(
    library: LibraryStore,
    settings: SettingsStore,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.Recent) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var creating by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingNotebook by remember { mutableStateOf<Notebook?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var moving by remember { mutableStateOf(false) }
    var confirmDeleteForever by remember { mutableStateOf<Set<String>?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        val folderId = (filter as? Filter.InFolder)?.id
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PdfImport.import(context, uri, library.pdfDir) } }
            importing = false
            result
                .onSuccess { onOpen(library.addPdf(it.id, it.name, it.pageCount, folderId).id) }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't import that PDF.", Toast.LENGTH_LONG).show() }
        }
    }
    var exportNotebooks by remember { mutableStateOf<List<Notebook>>(emptyList()) }
    var exporting by remember { mutableStateOf(false) }
    fun exportTo(uri: android.net.Uri?) {
        if (uri == null) return
        val chosen = exportNotebooks
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

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    val inTrash = filter == Filter.Trash
    val visible = library.notebooks
        .filter { if (inTrash) it.trashedAt != null else it.trashedAt == null }
        .filter {
            when (val f = filter) {
                Filter.Favorites -> it.favorite
                Filter.Pdfs -> it.isPdf
                is Filter.InFolder -> it.folderId == f.id
                else -> true
            }
        }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .let { list ->
            when {
                filter == Filter.Recent -> list.sortedByDescending { it.openedAt }.take(20)
                sort == SortOrder.Recent -> list.sortedByDescending { it.openedAt }
                sort == SortOrder.Name -> list.sortedBy { it.name.lowercase() }
                else -> list.sortedByDescending { it.createdAt }
            }
        }
    val title = when (val f = filter) {
        Filter.All -> "All notebooks"
        Filter.Recent -> "Recent"
        Filter.Favorites -> "Favorites"
        Filter.Pdfs -> "PDFs"
        is Filter.InFolder -> library.folders.firstOrNull { it.id == f.id }?.name ?: "Folder"
        Filter.Trash -> "Trash"
    }

    val subtitle = when {
        inTrash -> if (visible.isEmpty()) null else "Deleted notebooks wait here until you delete them forever"
        query.isNotBlank() -> null
        visible.isEmpty() -> null
        else -> count(visible.size, "notebook")
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val compact = maxWidth < 840.dp || maxHeight < 480.dp
            LaunchedEffect(compact) { if (!compact) drawer.close() }
            val navigation: @Composable () -> Unit = {
                Sidebar(
                    library = library,
                    filter = filter,
                    onFilter = {
                        filter = it
                        selection = emptySet()
                        scope.launch { drawer.close() }
                    },
                    onNewFolder = { creatingFolder = true; scope.launch { drawer.close() } },
                    onRenameFolder = { renamingFolder = it; scope.launch { drawer.close() } },
                    onSettings = { scope.launch { drawer.close() }; onSettings() },
                )
            }
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    if (selection.isEmpty()) {
                        TopBar(
                            title = title,
                            subtitle = subtitle,
                            onNavigation = if (compact) ({ scope.launch { drawer.open() }; Unit }) else null,
                            query = query,
                            onQuery = { query = it },
                            sort = sort,
                            onSort = { sort = it },
                            showSort = filter != Filter.Recent,
                            inTrash = inTrash,
                            trashNotEmpty = visible.isNotEmpty(),
                            onNew = { creating = true },
                            onImportPdf = onImportPdf,
                            onEmptyTrash = { confirmDeleteForever = visible.map { it.id }.toSet() },
                        )
                    } else {
                        SelectionBar(
                            count = selection.size,
                            inTrash = inTrash,
                            allFavorite = selection.all { id -> library.notebook(id)?.favorite == true },
                            onClear = { selection = emptySet() },
                            onFavorite = { fav -> library.setFavorite(selection, fav) },
                            onMove = { moving = true },
                            onRename = { renamingNotebook = library.notebook(selection.first()) },
                            onExport = {
                                if (!exporting) {
                                    exportNotebooks = library.notebooks.filter { it.id in selection }
                                    if (exportNotebooks.size == 1) exportPdf.launch("${exportNotebooks.single().name.replace(Regex("[^a-zA-Z0-9 ._-]"), "_")}.pdf")
                                    else exportZip.launch("Noti notebooks.zip")
                                }
                            },
                            onDuplicate = {
                                library.duplicate(selection)
                                selection = emptySet()
                            },
                            onDelete = {
                                library.trash(selection)
                                selection = emptySet()
                            },
                            onRestore = {
                                library.restore(selection)
                                selection = emptySet()
                            },
                            onDeleteForever = { confirmDeleteForever = selection },
                        )
                    }

                    if (visible.isEmpty()) {
                        EmptyState(filter, query.isNotBlank(), onNew = { creating = true }, onImportPdf = onImportPdf)
                    } else {
                        val showRecentRow = filter == Filter.All && query.isBlank() && visible.size > 6
                        val recent = if (showRecentRow) visible.sortedByDescending { it.openedAt }.take(if (compact) 4 else 6) else emptyList()
                        val onTap: (Notebook) -> Unit = { nb ->
                            when {
                                selection.isNotEmpty() -> selection = selection.toggle(nb.id)
                                inTrash -> selection = setOf(nb.id)
                                else -> onOpen(nb.id)
                            }
                        }
                        val onLongPress: (Notebook) -> Unit = { selection = selection.toggle(it.id) }
                        val gutter = if (compact) 20.dp else 32.dp
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (compact) 136.dp else 164.dp),
                            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 28.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (showRecentRow) {
                                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Jump back in") }
                                items(recent, key = { "r-" + it.id }) { nb ->
                                    NotebookCard(nb, library.thumbnailFile(nb), nb.id in selection, inTrash, onTap, onLongPress)
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Everything") }
                            }
                            items(visible, key = { it.id }) { nb ->
                                NotebookCard(nb, library.thumbnailFile(nb), nb.id in selection, inTrash, onTap, onLongPress)
                            }
                        }
                    }
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

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing PDF") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Copying it into Noti…")
                }
            },
        )
    }
    if (creating) {
        NewNotebookDialog(
            initialPaper = Paper(settings.defaultTemplate, settings.defaultColor),
            onDismiss = { creating = false },
            onCreate = { name, paper ->
                creating = false
                val nb = library.create(name, paper, (filter as? Filter.InFolder)?.id)
                onOpen(nb.id)
            },
        )
    }
    if (creatingFolder) {
        TextInputDialog("New folder", "", "Create", onDismiss = { creatingFolder = false }) {
            val f = library.createFolder(it)
            creatingFolder = false
            filter = Filter.InFolder(f.id)
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
    if (moving) {
        MoveDialog(
            folders = library.folders,
            onDismiss = { moving = false },
            onMove = { folderId ->
                library.move(selection, folderId)
                moving = false
                selection = emptySet()
            },
            onNewFolder = { name ->
                val f = library.createFolder(name)
                library.move(selection, f.id)
                moving = false
                selection = emptySet()
            },
        )
    }
    confirmDeleteForever?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDeleteForever = null },
            title = { Text(if (ids.size == 1) "Delete forever?" else "Delete ${ids.size} notebooks forever?") },
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

private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

// ---- sidebar ----

@Composable
private fun Sidebar(
    library: LibraryStore,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    onNewFolder: () -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onSettings: () -> Unit,
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
                            if (filter == Filter.InFolder(f.id)) onFilter(Filter.All)
                            library.deleteFolder(f.id)
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
    subtitle: String?,
    onNavigation: (() -> Unit)?,
    query: String,
    onQuery: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    showSort: Boolean,
    inTrash: Boolean,
    trashNotEmpty: Boolean,
    onNew: () -> Unit,
    onImportPdf: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 720.dp * LocalDensity.current.fontScale
        val search: @Composable (Modifier) -> Unit = { modifier ->
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search notebooks", color = colors.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = colors.onSurfaceVariant) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
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
                modifier = modifier.heightIn(min = 52.dp),
            )
        }
        val gutter = if (onNavigation != null) 20.dp else 32.dp
        Column(Modifier.fillMaxWidth().padding(start = if (onNavigation != null) 8.dp else gutter, end = gutter,
            top = if (onNavigation != null) 8.dp else 28.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                onNavigation?.let { open ->
                    IconButton(onClick = open) { Icon(Icons.Outlined.Menu, "Open navigation") }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = if (onNavigation != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null && onNavigation == null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!stacked) search(Modifier.width(260.dp))
                if (showSort) {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort: ${sort.label}", tint = colors.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortOrder.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { sortMenu = false; onSort(s) },
                                    trailingIcon = if (s == sort) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null)
                            }
                        }
                    }
                }
                if (inTrash) {
                    TextButton(onClick = onEmptyTrash, enabled = trashNotEmpty, destructive = true) { Text("Empty trash") }
                } else {
                    Box {
                        Button(onClick = { newMenu = true }, contentPadding = PaddingValues(start = 16.dp, end = 20.dp)) {
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
            if (stacked) search(Modifier.fillMaxWidth().padding(start = if (onNavigation != null) 12.dp else 0.dp))
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    inTrash: Boolean,
    allFavorite: Boolean,
    onClear: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        val compact = maxWidth < 1000.dp * LocalDensity.current.fontScale
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(colors.primaryContainer).padding(4.dp),
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection", tint = colors.onPrimaryContainer) }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (compact) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Selection actions", tint = colors.onPrimaryContainer) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val action: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { label, icon, destructive, run ->
                            DropdownMenuItem(text = { Text(label) }, destructive = destructive, leadingIcon = { Icon(icon, null) },
                                onClick = { menu = false; run() })
                        }
                        if (inTrash) {
                            action("Restore", Icons.Outlined.RestoreFromTrash, false, onRestore)
                            action("Delete forever", Icons.Outlined.DeleteForever, true, onDeleteForever)
                        } else {
                            action(if (allFavorite) "Unfavorite" else "Favorite", if (allFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, false) { onFavorite(!allFavorite) }
                            action("Move", Icons.AutoMirrored.Outlined.DriveFileMove, false, onMove)
                            if (count == 1) action("Rename", Icons.Outlined.DriveFileRenameOutline, false, onRename)
                            action("Duplicate", Icons.Outlined.ContentCopy, false, onDuplicate)
                            action("Export", Icons.Outlined.IosShare, false, onExport)
                            action("Delete", Icons.Outlined.Delete, true, onDelete)
                        }
                    }
                }
            } else {
                if (inTrash) {
                    BarAction("Restore", Icons.Outlined.RestoreFromTrash, onClick = onRestore)
                    BarAction("Delete forever", Icons.Outlined.DeleteForever, onClick = onDeleteForever)
                } else {
                    BarAction(
                        if (allFavorite) "Unfavorite" else "Favorite",
                        if (allFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    ) { onFavorite(!allFavorite) }
                    BarAction("Move", Icons.AutoMirrored.Outlined.DriveFileMove, onClick = onMove)
                    if (count == 1) BarAction("Rename", Icons.Outlined.DriveFileRenameOutline, onClick = onRename)
                    BarAction("Duplicate", Icons.Outlined.ContentCopy, onClick = onDuplicate)
                    BarAction("Export", Icons.Outlined.IosShare, onClick = onExport)
                    BarAction("Delete", Icons.Outlined.Delete, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun BarAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

// ---- content ----

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private val PageShape = RoundedCornerShape(14.dp)

/**
 * A notebook is drawn as its own first page: real paper, a colored spine on the left,
 * a honey bookmark when it's a favorite. No invented content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    nb: Notebook,
    thumbnail: File?,
    selected: Boolean,
    inTrash: Boolean,
    onTap: (Notebook) -> Unit,
    onLongPress: (Notebook) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    val cover = extras.covers[Math.floorMod(nb.id.hashCode(), extras.covers.size)]
    Column(
        Modifier.clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = { onTap(nb) }, onLongClick = { onLongPress(nb) })
            .semantics { this.selected = selected }
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.76f)) {
            Box(
                Modifier.matchParentSize()
                    .shadow(if (selected) 0.dp else 3.dp, PageShape, ambientColor = extras.shadow, spotColor = extras.shadow)
                    .clip(PageShape)
                    .background(Color.White)
                    .border(if (selected) 2.5.dp else 0.5.dp,
                        if (selected) colors.primary else colors.outlineVariant, PageShape),
            ) {
                if (nb.isPdf) PdfThumbnail(thumbnail, Modifier.fillMaxSize())
                else {
                    PaperPreview(nb.paper, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxHeight().width(9.dp).background(cover))
                    Box(Modifier.padding(start = 9.dp).fillMaxHeight().width(1.dp).background(Color(0x14000000)))
                }
            }
            if (nb.favorite && !inTrash) Bookmark(extras.bookmark, Modifier.align(Alignment.TopEnd).padding(end = 14.dp))
            if (selected) Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).size(24.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = colors.onPrimary) }
            val tag = when {
                nb.isPdf -> "PDF"
                nb.pageCount > 1 -> count(nb.pageCount, "page")
                else -> null
            }
            if (tag != null) Text(
                tag, style = MaterialTheme.typography.labelSmall, color = NotiBrand.Ink,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color.White.copy(alpha = 0.92f), CircleShape)
                    .border(0.5.dp, Color(0x1F000000), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(nb.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
        Text(if (inTrash) "Deleted " + relative(nb.trashedAt ?: 0) else relative(nb.openedAt).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
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
private fun EmptyState(filter: Filter, searching: Boolean, onNew: () -> Unit, onImportPdf: () -> Unit) {
    val (headline, body) = when {
        searching -> "Nothing found" to "Try a different word. Your idea might be hiding."
        filter == Filter.Pdfs -> "No PDFs yet" to "Import a PDF to write on it."
        filter == Filter.Trash -> "Trash is empty" to "Deleted notebooks wait here until you delete them forever."
        filter == Filter.Favorites -> "No favorites yet" to "Long-press a notebook and tap Favorite."
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
        if (!searching && filter == Filter.Pdfs) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onImportPdf) {
                Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import PDF")
            }
        } else if (!searching && filter != Filter.Trash && filter != Filter.Favorites) {
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
    var name by remember { mutableStateOf("") }
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
        PaperPicker(paper, onChange = { paper = it })
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
