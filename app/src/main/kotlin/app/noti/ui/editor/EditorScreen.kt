package app.noti.ui.editor

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.ui.common.BrushSpec
import app.noti.ui.common.EngineSpec
import app.noti.ui.common.InkCanvas
import app.noti.ui.common.PaperPicker
import app.noti.ui.common.PaperPreview
import app.noti.ui.common.TextInputDialog
import app.noti.ui.common.comingSoon
import app.noti.ui.common.greyedOut
import app.noti.ui.settings.SettingsScreen

@Composable
fun EditorScreen(
    notebook: Notebook,
    library: LibraryStore,
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    var pagesOpen by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var choosingPaper by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val leave = { if (settings.confirmLeave) confirmingLeave = true else onBack() }
    BackHandler(enabled = !fullScreen) { leave() }
    BackHandler(enabled = fullScreen) { fullScreen = false }
    SystemBarsHidden(fullScreen)

    val slots = if (settings.tool == Tool.Highlighter) settings.highlighterSlots else settings.penSlots
    val brush = if (settings.tool == Tool.Highlighter) {
        BrushSpec(slots.currentColor, alpha = 0.4f, widthPx = slots.currentWidth, pressureGamma = null)
    } else {
        BrushSpec(
            slots.currentColor, alpha = 1f, widthPx = slots.currentWidth,
            pressureGamma = if (settings.pressureOn) settings.pressureGamma else null,
        )
    }
    val engine = EngineSpec(settings.predictor.engineId, settings.inkFeelMs)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (fullScreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
        ) {
            if (!fullScreen) {
                EditorToolbar(
                    title = notebook.name,
                    settings = settings,
                    pagesOpen = pagesOpen,
                    onBack = leave,
                    onRename = { renaming = true },
                    onTogglePages = { pagesOpen = !pagesOpen },
                    onPagePaper = { choosingPaper = true },
                    onFullScreen = {
                        fullScreen = true
                        Toast.makeText(context, "Two-finger double-tap or Back to exit full screen", Toast.LENGTH_SHORT).show()
                    },
                    onSettings = { settingsOpen = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(Modifier.fillMaxSize()) {
                if (pagesOpen && !fullScreen) {
                    PagesPanel(
                        notebook = notebook,
                        onClose = { pagesOpen = false },
                        onChangePaper = { choosingPaper = true },
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                InkCanvas(
                    paper = notebook.paper,
                    brush = brush,
                    engine = engine,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTwoFingerDoubleTap = { fullScreen = false },
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog("Rename notebook", notebook.name, "Rename", onDismiss = { renaming = false }) {
            library.rename(notebook.id, it)
            renaming = false
        }
    }
    if (choosingPaper) {
        var paper by remember { mutableStateOf(notebook.paper) }
        AlertDialog(
            onDismissRequest = { choosingPaper = false },
            title = { Text("Page paper") },
            text = { PaperPicker(paper, onChange = { paper = it }, showSize = false) },
            confirmButton = {
                TextButton(onClick = {
                    library.setPaper(notebook.id, paper)
                    choosingPaper = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { choosingPaper = false }) { Text("Cancel") } },
        )
    }
    if (confirmingLeave) {
        var dontAsk by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Leave this notebook?") },
            text = {
                Column {
                    Text("Saving ink is coming in a future update, so the ink on this page will be cleared when you leave.")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { dontAsk = !dontAsk },
                    ) {
                        Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                        Text("Don't ask again", modifier = Modifier.padding(end = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontAsk) settings.confirmLeave = false
                    confirmingLeave = false
                    onBack()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmingLeave = false }) { Text("Stay") } },
        )
    }
    if (settingsOpen) {
        // A full-screen dialog rather than a separate screen, so this notebook (and its
        // unsaved ink) stays alive underneath.
        Dialog(
            onDismissRequest = { settingsOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            SettingsScreen(settings, onBack = { settingsOpen = false })
        }
    }
}

@Composable
private fun PagesPanel(notebook: Notebook, onClose: () -> Unit, onChangePaper: () -> Unit) {
    val addSoon = comingSoon("Adding pages")
    val duplicateSoon = comingSoon("Duplicating pages")
    val deleteSoon = comingSoon("Deleting pages")
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close pages") }
        }
        Spacer(Modifier.height(8.dp))
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                PaperPreview(
                    notebook.paper,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .clip(MaterialTheme.shapes.small)
                        .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                        .clickable { menuOpen = true },
                )
                Spacer(Modifier.height(4.dp))
                Text("1", style = MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Change paper") },
                    leadingIcon = { Icon(Icons.Outlined.Description, null) },
                    onClick = {
                        menuOpen = false
                        onChangePaper()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = duplicateSoon,
                    modifier = Modifier.greyedOut(),
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = deleteSoon,
                    modifier = Modifier.greyedOut(),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = addSoon, modifier = Modifier.fillMaxWidth().greyedOut()) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Page")
        }
    }
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

internal val ToolbarHeight = 56.dp
internal val ToolbarSpacing = Arrangement.spacedBy(2.dp)
