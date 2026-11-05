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
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import app.noti.pdf.PdfImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import app.noti.ui.brand.DotiMascot
import app.noti.ui.brand.NotiWordmark
import app.noti.ui.brand.LibraryWelcome
import app.noti.ui.brand.FriendlyNote
import app.noti.ui.theme.NotiBrand
import app.noti.data.Folder
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.Paper
import app.noti.data.SettingsStore
import app.noti.data.SortOrder
import app.noti.ui.common.PaperPicker
import app.noti.ui.common.PaperPreview
import app.noti.ui.common.TextInputDialog
import app.noti.data.InkFile
import app.noti.pdf.DocumentExport
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

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
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = compact,
                drawerContent = { if (compact) ModalDrawerSheet { navigation() } },
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (!compact) {
                        navigation()
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (selection.isEmpty()) {
                            TopBar(
                                title = title,
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
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        if (visible.isEmpty()) {
                            EmptyState(filter, query.isNotBlank(), onNew = { creating = true }, onImportPdf = onImportPdf)
                        } else {
                            val showRecentRow = filter == Filter.All && query.isBlank() && visible.size > 6
                            val recent = if (showRecentRow) visible.sortedByDescending { it.openedAt }.take(6) else emptyList()
                            val onTap: (Notebook) -> Unit = { nb ->
                                when {
                                    selection.isNotEmpty() -> selection = selection.toggle(nb.id)
                                    inTrash -> selection = setOf(nb.id)
                                    else -> onOpen(nb.id)
                                }
                            }
                            val onLongPress: (Notebook) -> Unit = { selection = selection.toggle(it.id) }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(if (compact) 140.dp else 168.dp),
                                contentPadding = PaddingValues(if (compact) 16.dp else 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                if (filter == Filter.All && query.isBlank() && selection.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { LibraryWelcome(compact) }
                                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Your notebooks · ${visible.size}") }
                                }
                                if (showRecentRow) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Recent") }
                                    items(recent, key = { "r-" + it.id }) { nb ->
                                        NotebookCard(nb, library.thumbnailFile(nb), nb.id in selection, inTrash, onTap, onLongPress)
                                    }
                                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("All") }
                                }
                                items(visible, key = { it.id }) { nb ->
                                    NotebookCard(nb, library.thumbnailFile(nb), nb.id in selection, inTrash, onTap, onLongPress)
                                }
                            }
                        }
                    }
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
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        NotiWordmark(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
        Text(NotiBrand.Tagline, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 24.dp))
        SideItem("All", Icons.AutoMirrored.Outlined.LibraryBooks, filter == Filter.All) { onFilter(Filter.All) }
        SideItem("Recent", Icons.Outlined.History, filter == Filter.Recent) { onFilter(Filter.Recent) }
        SideItem("Favorites", Icons.Outlined.StarBorder, filter == Filter.Favorites) { onFilter(Filter.Favorites) }
        SideItem("PDFs", Icons.Outlined.PictureAsPdf, filter == Filter.Pdfs) { onFilter(Filter.Pdfs) }

        Text(
            "FOLDERS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
        )
        library.folders.sortedBy { it.name.lowercase() }.forEach { f ->
            var menu by remember { mutableStateOf(false) }
            Box {
                SideItem(
                    f.name, Icons.Outlined.Folder, filter == Filter.InFolder(f.id),
                    badge = {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Folder options", modifier = Modifier.size(18.dp))
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
        SideItem("Folder", Icons.Outlined.CreateNewFolder, false, onClick = onNewFolder)

        Spacer(Modifier.height(20.dp))
        SideItem("Trash", Icons.Outlined.Delete, filter == Filter.Trash) { onFilter(Filter.Trash) }
        SideItem("Settings", Icons.Outlined.Settings, false, onClick = onSettings)
        Spacer(Modifier.height(28.dp))
        FriendlyNote("Room for a little doodle", "It doesn't have to be a big idea.")
    }
}

@Composable
private fun SideItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, null) },
        selected = selected,
        onClick = onClick,
        badge = badge,
        shape = MaterialTheme.shapes.small,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier.heightIn(min = 48.dp),
    )
}

// ---- top bars ----

@Composable
private fun TopBar(
    title: String,
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 720.dp * LocalDensity.current.fontScale
        val search: @Composable () -> Unit = {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Find a little idea…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onNavigation?.let { open ->
                    IconButton(onClick = open) { Icon(Icons.Outlined.Menu, "Open navigation") }
                }
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!stacked) Box(Modifier.width(240.dp)) { search() }
                if (showSort) {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort: ${sort.label}")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortOrder.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = { sortMenu = false; onSort(s) })
                            }
                        }
                    }
                }
                if (inTrash) {
                    TextButton(onClick = onEmptyTrash, enabled = trashNotEmpty, destructive = true) { Text("Empty trash") }
                } else {
                    Box {
                        Button(onClick = { newMenu = true }, shape = MaterialTheme.shapes.small) {
                            Icon(Icons.Outlined.Add, null)
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
            if (stacked) search()
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
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)) {
        val compact = maxWidth < 1000.dp * LocalDensity.current.fontScale
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (compact) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Selection actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val action: @Composable (String, ImageVector, () -> Unit) -> Unit = { label, icon, run ->
                            DropdownMenuItem(text = { Text(label) }, destructive = label == "Delete" || label == "Delete forever", leadingIcon = { Icon(icon, null) },
                                onClick = { menu = false; run() })
                        }
                        if (inTrash) {
                            action("Restore", Icons.Outlined.RestoreFromTrash, onRestore)
                            action("Delete forever", Icons.Outlined.DeleteForever, onDeleteForever)
                        } else {
                            action(if (allFavorite) "Unfavorite" else "Favorite", if (allFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder) { onFavorite(!allFavorite) }
                            action("Move", Icons.AutoMirrored.Outlined.DriveFileMove, onMove)
                            if (count == 1) action("Rename", Icons.Outlined.DriveFileRenameOutline, onRename)
                            action("Duplicate", Icons.Outlined.ContentCopy, onDuplicate)
                            action("Export", Icons.Outlined.IosShare, onExport)
                            action("Delete", Icons.Outlined.Delete, onDelete)
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
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(icon, null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

// ---- content ----

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

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
    val coverColors = listOf(NotiBrand.Lilac, NotiBrand.Butter, NotiBrand.Mint, NotiBrand.Peach)
    val cover = coverColors[Math.floorMod(nb.id.hashCode(), coverColors.size)]
    Column(
        Modifier.clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .combinedClickable(onClick = { onTap(nb) }, onLongClick = { onLongPress(nb) })
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.88f).clip(MaterialTheme.shapes.medium)
            .background(if (nb.isPdf) colors.surfaceContainerLow else cover)
            .border(if (selected) 2.dp else 0.5.dp,
                if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f), MaterialTheme.shapes.medium)) {
            val frame = Modifier.fillMaxSize().padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 18.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(0.5.dp, Color(0x1A30332E), RoundedCornerShape(4.dp))
            if (nb.isPdf) PdfThumbnail(thumbnail, frame) else {
                PaperPreview(nb.paper, frame)
                Box(Modifier.fillMaxHeight().width(7.dp).background(NotiBrand.Ink.copy(alpha = 0.08f)))
            }
            if (nb.isPdf) Icon(Icons.Outlined.PictureAsPdf, contentDescription = "PDF",
                tint = colors.primary, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(20.dp))
            if (nb.favorite && !inTrash) Icon(Icons.Outlined.Star, contentDescription = "Favorite",
                tint = NotiBrand.Ink, modifier = Modifier.align(Alignment.TopEnd)
                    .padding(8.dp).background(NotiBrand.Butter, CircleShape).padding(4.dp).size(16.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(nb.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (inTrash) "Deleted " + relative(nb.trashedAt ?: 0)
            else "${nb.pageCount} ${if (nb.pageCount == 1) "page" else "pages"} · ${relative(nb.openedAt)}",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
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
        else -> "A fresh start. A little possibility." to "Meet Doti, your little ink companion. Let’s make room for your first idea."
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        DotiMascot(Modifier.size(136.dp))
        Spacer(Modifier.height(20.dp))
        Text(headline, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp), style = MaterialTheme.typography.bodyMedium)
        if (!searching && filter == Filter.Pdfs) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onImportPdf, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Outlined.PictureAsPdf, null)
                Spacer(Modifier.width(6.dp))
                Text("Import PDF")
            }
        } else if (!searching && filter != Filter.Trash && filter != Filter.Favorites) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onNew, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(6.dp))
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
        title = {
            Column {
                Text("New notebook", style = MaterialTheme.typography.titleLarge)
                Text("A new little beginning.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, icon = { DotiMascot(Modifier.size(48.dp)) }, maxWidth = 560.dp,
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
    DropdownMenuItem(text = { Text(label) }, destructive = label == "Delete" || label == "Delete forever", leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
