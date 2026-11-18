package app.noti.ui.editor

import app.noti.ui.common.NotiTextButton as TextButton
import app.noti.ui.common.NotiIconButton as IconButton
import app.noti.ui.common.NotiTonalButton as FilledTonalButton
import app.noti.ui.common.NotiAlertDialog as AlertDialog
import app.noti.ui.common.NotiDropdownMenu as DropdownMenu
import app.noti.ui.common.NotiMenuItem as DropdownMenuItem

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.noti.data.*
import app.noti.ink.InkLayer
import app.noti.ink.PenHold
import app.noti.pdf.DocumentExport
import app.noti.pdf.PdfDocument
import app.noti.ui.common.*
import app.noti.ui.brand.DotiMascot
import app.noti.ui.settings.SettingsScreen
import app.noti.ui.study.StudyOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved ink, and for the Endless canvas the height its sheet starts at. */
private class OpenedInk(val file: InkFile, val sheetAspect: Float?)

/** IO. Ink from the A4-shaped pages Endless notebooks used to have is moved onto the canvas first. */
private fun openInk(notebook: Notebook, library: LibraryStore): OpenedInk {
    val file = library.inkFile(notebook)
    var ink = InkFile.open(file)
    if (!notebook.isCanvas) return OpenedInk(ink, null)
    if (!notebook.canvasInk) EndlessCanvas.fromPages(ink.initial)?.let { ink = InkFile.replace(file, it) }
    // Unreadable ink is the engine's to report (it keeps the file untouched).
    val aspect = runCatching { EndlessCanvas.aspect(InkArchive.decode(ink.initial)) }.getOrDefault(EndlessCanvas.MIN_ASPECT)
    return OpenedInk(ink, aspect)
}

@Composable
fun EditorScreen(notebook: Notebook, library: LibraryStore, settings: SettingsStore, onBack: () -> Unit) {
    val opened by produceState<Result<OpenedInk>?>(null, notebook.id) {
        val result = withContext(Dispatchers.IO) { runCatching { openInk(notebook, library) } }
        if (result.isSuccess && notebook.isCanvas && !notebook.canvasInk) library.setCanvasInk(notebook.id)
        value = result
    }
    val result = opened
    if (result == null) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                DotiMascot(Modifier.size(80.dp))
                Spacer(Modifier.height(16.dp))
                Text("Opening your page…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    } else if (result.isFailure) {
        AlertDialog(onDismissRequest = onBack, title = { Text("Couldn't open saved ink") },
            text = { Text("The saved file has been kept. Return to the library and try again.") },
            confirmButton = { app.noti.ui.common.NotiButton(onClick = onBack) { Text("Back to library") } })
    } else {
        val ink = result.getOrThrow()
        key(notebook.id) { EditorContent(notebook, library, settings, ink.file, ink.sheetAspect, onBack) }
    }
}

@Composable
private fun EditorContent(notebook: Notebook, library: LibraryStore, settings: SettingsStore, inkFile: InkFile,
    sheetAspect: Float?, onBack: () -> Unit) {
    var pagesOpen by remember { mutableStateOf(false) }
    var jumping by rememberSaveable { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var choosingPaper by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf(false) }
    var deletingPage by remember { mutableStateOf<Int?>(null) }
    var layer by remember { mutableStateOf<InkLayer?>(null) }
    var saveStatus by remember { mutableStateOf("Autosave on") }
    var exporting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var pageBusy by remember { mutableStateOf(false) }
    var annotationsEnabled by remember { mutableStateOf(false) }
    val readOnly = notebook.isPdf && !annotationsEnabled
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(inkFile) {
        inkFile.onSaved = { ok -> saveStatus = if (ok) "Saved on device" else "Save failed · tap to retry" }
        onDispose { inkFile.onSaved = null }
    }
    DisposableEffect(layer) {
        val app = context.applicationContext as android.app.Application
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) { if (activity == context) layer?.saveNow() }
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        onDispose { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }
    fun saveAndLeave() {
        if (leaving) return
        val current = layer
        if (current == null) onBack() else {
            leaving = true
            current.snapshot { bytes ->
                inkFile.save(bytes) { ok ->
                    leaving = false
                    if (ok) onBack() else Toast.makeText(context, "Couldn't save. Keep this notebook open and tap the save status to retry.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val leave = { if (settings.confirmLeave) confirmingLeave = true else saveAndLeave() }
    BackHandler(enabled = !fullScreen) { leave() }
    BackHandler(enabled = fullScreen) { fullScreen = false }
    // Registered last, so Back closes an open tool dial first.
    val dial = remember { PenDialState() }
    BackHandler(enabled = dial.phase != PenDialState.Phase.Hidden) { dial.dismiss() }
    SystemBarsHidden(fullScreen)

    val slots = if (settings.tool == Tool.Highlighter) settings.highlighterSlots else settings.penSlots
    val brush = BrushSpec(slots.currentColor, if (settings.tool == Tool.Highlighter) 0.4f else 1f,
        slots.currentWidth, if (settings.tool == Tool.Highlighter || !settings.pressureOn) null else settings.pressureGamma, settings.tool)
    val engine = EngineSpec(settings.predictor.engineId, settings.inkFeelMs)
    val palm = PalmSpec(settings.fingerDrawing, settings.palmModel, onModel = { settings.palmModel = it })
    val pdf = remember(notebook.pdf) { library.pdfFile(notebook)?.let { PdfDocument(it) } }
    DisposableEffect(pdf) { onDispose { pdf?.close() } }
    val pages = remember { PageController(if (notebook.isCanvas) null else library.lastPage(notebook.id)) }
    val pageCount = pdf?.pageCount ?: notebook.pageCount
    LaunchedEffect(pages, notebook.isCanvas) {
        if (!notebook.isCanvas) snapshotFlow { pages.placement?.takeIf { it.settled }?.page }.collect { page ->
            if (page != null) library.rememberPage(notebook.id, page)
        }
    }
    // The placement changes every frame while scrolling; the chrome only needs these.
    val currentPage by remember { derivedStateOf { pages.page } }
    val zoom by remember { derivedStateOf { ((pages.placement?.zoom ?: 1f) * 100).roundToInt() / 100f } }
    val viewportFraction by remember { derivedStateOf { ((pages.placement?.viewportFraction ?: 1f) * 1000).roundToInt() / 1000f } }

    fun changePages(operation: Int, index: Int) {
        if (pageBusy || notebook.isPdf || notebook.isCanvas || layer == null) return
        if (operation == 2 && notebook.pageCount <= 1) return
        pageBusy = true
        layer?.documentAction(operation + 2, index) { ok ->
            pageBusy = false
            if (ok) {
                val count = notebook.pageCount + if (operation == 2) -1 else 1
                library.setPageCount(notebook.id, count)
                pages.goTo((if (operation == 1) index + 1 else index).coerceIn(0, count - 1))
            } else Toast.makeText(context, "This notebook has reached its ink capacity. The original page is unchanged.", Toast.LENGTH_LONG).show()
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val current = layer
        if (uri != null && current != null) {
            exporting = true
            current.snapshot { bytes ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DocumentExport.writePdf(context, notebook, library, bytes, uri) } }
                    exporting = false
                    Toast.makeText(context, if (result.isSuccess) "PDF exported" else "Couldn't export PDF. Try another destination.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val buttonsEnabled = !readOnly && !settingsOpen && !renaming && !choosingPaper && !confirmingLeave && !restoreError &&
        deletingPage == null && !leaving && !exporting && !pageBusy && !pagesOpen && !jumping
    // Holding the writing button and touching the page summons the dial instead of drawing.
    val dialGesture = buttonsEnabled && settings.penButtonsEnabled && settings.penButtonHold == PenHold.Dial
    DisposableEffect(layer, dialGesture) {
        val current = layer
        if (!dialGesture) dial.dismiss()
        current?.setPenDial(if (!dialGesture) null else { action, x, y -> dial.onPen(action, x, y)?.let(settings::applyDialSegment) })
        onDispose { current?.setPenDial(null) }
    }
    // A Study board: its sheet, printed cards and the loops the sheet asks for.
    val study = app.noti.ui.study.rememberStudyBoard(notebook, library)
    val dialLocked = dial.phase == PenDialState.Phase.Locked
    LaunchedEffect(layer, dialLocked) { layer?.setDialLocked(dialLocked) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
            InkCanvas(
                paper = notebook.paper, brush = brush, engine = engine,
                modifier = Modifier.fillMaxSize().then(if (study != null) Modifier.onGloballyPositioned { study.geometry.editor = it } else Modifier),
                palm = palm, pdf = pdf, pages = pages, pageCount = notebook.pageCount, sheetAspect = sheetAspect, inkFile = inkFile,
                tapUndo = settings.tapUndo, onLayer = { layer = it }, onRestoreError = { restoreError = true },
                onTwoFingerDoubleTap = { fullScreen = false },
                topClearance = if (fullScreen) 16.dp else 72.dp, bottomClearance = if (fullScreen) 16.dp else 72.dp,
                buttons = settings.penButtonOptions(enabled = buttonsEnabled),
                onButtonClick = { button ->
                    settings.performButtonAction(settings.buttonAction(button)) { layer?.documentAction(it) }
                },
                board = study?.layer,
                onTap = study?.let { b -> { x, y -> b.tap(x, y, pages.placement) } },
                readOnly = readOnly,
            )
            EditorToolbar(
                title = notebook.name, settings = settings, pagesOpen = pagesOpen,
                onBack = leave, onRename = { renaming = true }, onTogglePages = { pagesOpen = !pagesOpen },
                onPagePaper = { choosingPaper = true },
                onFullScreen = {
                    fullScreen = true
                    Toast.makeText(context, "Two-finger double-tap or Back to exit full screen", Toast.LENGTH_SHORT).show()
                },
                // The Endless canvas has no pages to count, add or turn.
                onSettings = { settingsOpen = true },
                pdfPage = if (notebook.isCanvas || pageCount == 0) null else currentPage to pageCount, onPage = pages::goTo,
                onJumpPage = { jumping = true }, onCanvasHome = if (notebook.isCanvas) ({ pages.goTo(0) }) else null,
                canUndo = pages.canUndo, canRedo = pages.canRedo,
                onUndo = { layer?.documentAction(0) }, onRedo = { layer?.documentAction(1) },
                onExport = { if (!exporting && layer != null) export.launch("${notebook.name.replace(Regex("[^a-zA-Z0-9 ._-]"), "_")}.pdf") },
                onAddPage = if (notebook.isPdf || notebook.isCanvas) null else { { changePages(0, notebook.pageCount) } },
                zoom = { zoom }, scrollFraction = { pages.placement?.scrollFraction ?: 0f },
                viewportFraction = { viewportFraction }, onResetZoom = pages::resetZoom, onFitPage = pages::fitPage,
                onScroll = pages::scrollTo,
                saveStatus = saveStatus, onSave = { layer?.saveNow() }, selectionCount = pages.documentState[3].toInt(), isPdf = notebook.isPdf,
                dial = dial, fullScreen = fullScreen,
                annotationsEnabled = annotationsEnabled,
                onToggleAnnotations = {
                    annotationsEnabled = !annotationsEnabled
                    if (!annotationsEnabled) dial.dismiss()
                },
                studyButton = study?.let { b -> { compact -> app.noti.ui.study.StudyButton(b.controller, compact) } },
            )
            if (study != null) BoxWithConstraints(Modifier.fillMaxSize()) {
                StudyOverlay(study, { pages.placement }, maxWidth, maxHeight, hidden = settingsOpen || fullScreen)
            }
            app.noti.ui.assaad.AssaadStudyBridge(notebook.id, study?.controller)
            if (pagesOpen && !fullScreen && !notebook.isCanvas && !notebook.isPdf) {
                androidx.compose.ui.window.Popup(alignment = Alignment.TopStart,
                    offset = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.unit.IntOffset(2.dp.roundToPx(), 62.dp.roundToPx()) },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                    onDismissRequest = { pagesOpen = false }) {
                    GlassSurface(Modifier.padding(10.dp).fillMaxHeight(0.7f), MaterialTheme.shapes.extraLarge) {
                        val selectPage: (Int) -> Unit = { pages.goTo(it); pagesOpen = false }
                        val jump = { pagesOpen = false; jumping = true }
                        PagesPanel(notebook, currentPage, pageBusy, selectPage, onClose = { pagesOpen = false }, onJump = jump,
                            onAdd = { changePages(0, notebook.pageCount) }, onDuplicate = { changePages(1, it) },
                            onDelete = { deletingPage = it }, onChangePaper = { choosingPaper = true })
                    }
                }
            }
        }
    }
    if (pdf != null && pageCount > 0 && pagesOpen && !fullScreen) {
        PdfPagesDialog(pageCount, currentPage, onPage = { pages.goTo(it); pagesOpen = false },
            onClose = { pagesOpen = false }, thumbnail = { pdf.thumbnail(it) })
    }
    if (jumping && pageCount > 0) JumpToPageDialog(currentPage, pageCount, onDismiss = { jumping = false }) {
        pages.goTo(it)
        jumping = false
    }
    if (restoreError) AlertDialog(onDismissRequest = {}, title = { Text("Couldn't read saved ink") },
        text = { Text("The saved file has been kept without changes. Return to the library to keep your original ink safe.") },
        confirmButton = { app.noti.ui.common.NotiButton(onClick = onBack) { Text("Back to library") } })
    if (renaming) TextInputDialog("Rename notebook", notebook.name, "Rename", onDismiss = { renaming = false }) {
        library.rename(notebook.id, it); renaming = false
    }
    if (choosingPaper) {
        var paper by remember { mutableStateOf(notebook.paper) }
        AlertDialog(onDismissRequest = { choosingPaper = false }, title = { Text("Page paper") },
            text = { Column { PaperPicker(paper, onChange = { paper = it }, showSize = false) } },
            confirmButton = { app.noti.ui.common.NotiButton(onClick = { library.setPaper(notebook.id, paper); choosingPaper = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { choosingPaper = false }) { Text("Cancel") } })
    }
    deletingPage?.let { index ->
        AlertDialog(onDismissRequest = { deletingPage = null }, title = { Text("Delete page ${index + 1}?") },
            text = { Text("This removes the page and its ink.") },
            confirmButton = { app.noti.ui.common.NotiButton(destructive = true, onClick = { changePages(2, index); deletingPage = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingPage = null }) { Text("Cancel") } })
    }
    if (confirmingLeave) AlertDialog(onDismissRequest = { confirmingLeave = false }, title = { Text("Return to library?") },
        text = { Text("Your ink is saved automatically on this device.") },
        confirmButton = { app.noti.ui.common.NotiButton(onClick = { confirmingLeave = false; saveAndLeave() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmingLeave = false }) { Text("Stay") } })
    if (settingsOpen) Dialog(onDismissRequest = { settingsOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SettingsScreen(settings, onBack = { settingsOpen = false })
    }
}

@Composable
private fun PagesPanel(notebook: Notebook, current: Int, busy: Boolean, onPage: (Int) -> Unit,
    onClose: () -> Unit, onJump: () -> Unit, onAdd: () -> Unit, onDuplicate: (Int) -> Unit, onDelete: (Int) -> Unit, onChangePaper: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.animateScrollToItem(current.coerceAtLeast(0))
    }
    Column(Modifier.width(184.dp).fillMaxHeight().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onJump, enabled = !busy) { Icon(Icons.Outlined.Numbers, "Go to page") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close pages") }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(notebook.pageCount) { index ->
                var menu by remember { mutableStateOf(false) }
                Column {
                    PaperPreview(notebook.paper, Modifier.fillMaxWidth().aspectRatio(if (notebook.paper.size == PaperSize.Letter) 8.5f/11f else 1f/1.4142f)
                        .clip(MaterialTheme.shapes.medium)
                        .border(if (current == index) 2.dp else 1.dp, if (current == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                        .clickable(enabled = !busy) { onPage(index) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreHoriz, "Page ${index + 1} options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Change paper") }, onClick = { menu = false; onChangePaper() })
                                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate(index) })
                                DropdownMenuItem(text = { Text("Delete") }, destructive = true, enabled = notebook.pageCount > 1, onClick = { menu = false; onDelete(index) })
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add page")
        }
    }
}

@Composable
internal fun JumpToPageDialog(current: Int, count: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val number = input.toIntOrNull()?.takeIf { it in 1..count }
    val submit = { number?.let { onPage(it - 1) }; Unit }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Page number") },
                placeholder = { Text("${current + 1}") },
                supportingText = { Text("Enter a page from 1 to $count") },
                isError = input.isNotEmpty() && number == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { NotiButton(onClick = submit, enabled = number != null) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hides the status and navigation bars while [hidden] (full screen); a swipe shows them briefly. */
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { if (hidden) controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
