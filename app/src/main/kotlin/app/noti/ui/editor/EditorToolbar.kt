package app.noti.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.data.ToolSlots

/** Document actions stay fixed; drawing controls adapt without scrolling offscreen. */
@Composable
internal fun EditorToolbar(
    title: String,
    settings: SettingsStore,
    pagesOpen: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onTogglePages: () -> Unit,
    onPagePaper: () -> Unit,
    onFullScreen: () -> Unit,
    onSettings: () -> Unit,
    pdfPage: Pair<Int, Int>? = null,
    onPage: (Int) -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onExport: () -> Unit = {},
    onAddPage: (() -> Unit)? = null,
    saveStatus: String = "Autosave on",
    onSave: () -> Unit = {},
    selectionCount: Int = 0,
    isPdf: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
                Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(onClick = onRename).padding(horizontal = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(saveStatus, style = MaterialTheme.typography.labelSmall, color = if (saveStatus.startsWith("Save failed")) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onSave), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pdfPage != null) {
                    val (page, count) = pdfPage
                    IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous page") }
                    Text("${page + 1} / $count", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onPage(page + 1) }, enabled = page < count - 1) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next page") }
                }
                IconToggleButton(checked = pagesOpen, onCheckedChange = { onTogglePages() }) {
                    Icon(Icons.AutoMirrored.Outlined.ViewSidebar, "Pages", tint = if (pagesOpen) colors.primary else colors.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Export PDF") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) }, onClick = { menuOpen = false; onExport() })
                        if (!isPdf) DropdownMenuItem(text = { Text("Page paper") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = { menuOpen = false; onPagePaper() })
                        onAddPage?.let { add -> DropdownMenuItem(text = { Text("Add page") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menuOpen = false; add() }) }
                        DropdownMenuItem(text = { Text("Full screen") }, leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) }, onClick = { menuOpen = false; onFullScreen() })
                        DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menuOpen = false; onSettings() })
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            DrawingControls(settings, canUndo, canRedo, onUndo, onRedo, optionsOpen,
                onOptions = { optionsOpen = true }, onCloseOptions = { optionsOpen = false }, selectionCount = selectionCount)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawingControls(
    settings: SettingsStore, canUndo: Boolean, canRedo: Boolean,
    onUndo: () -> Unit, onRedo: () -> Unit, optionsOpen: Boolean,
    onOptions: () -> Unit, onCloseOptions: () -> Unit, selectionCount: Int,
) {
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Keep 48dp targets. Labels yield to icons before controls need a second row.
        val labels = maxWidth >= 1076.dp * LocalDensity.current.fontScale
        val oneLine = maxWidth >= 736.dp
        val narrow = maxWidth < 376.dp
        val selectTool: (Tool) -> Unit = { tool ->
            if (settings.tool == tool && drawing) onOptions()
            else { settings.tool = tool; onCloseOptions() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!narrow) HistoryActions(canUndo, canRedo, onUndo, onRedo)
                ToolGroup(settings.tool, labels, if (oneLine) Modifier else Modifier.weight(1f), selectTool)
                if (oneLine && drawing) QuickSlots(settings, onOptions)
                if (oneLine && !drawing) ToolHint(settings.tool, selectionCount, Modifier.weight(1f))
                Box {
                    IconButton(onClick = onOptions, enabled = drawing) { Icon(Icons.Outlined.Tune, "Brush options") }
                    if (drawing && optionsOpen) ToolOptionsPopup(settings, onCloseOptions)
                }
            }
            if (!oneLine) {
                if (drawing) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (narrow) HistoryActions(canUndo, canRedo, onUndo, onRedo)
                        ColorSlots(settings, onOptions)
                        WidthSlots(settings, onOptions)
                    }
                } else {
                    if (narrow) HistoryActions(canUndo, canRedo, onUndo, onRedo)
                    ToolHint(settings.tool, selectionCount)
                }
            }
        }
    }
}

@Composable
private fun HistoryActions(canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    Row {
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
    }
}

@Composable
private fun ToolGroup(current: Tool, labels: Boolean, modifier: Modifier, onTool: (Tool) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
        Row(Modifier.padding(4.dp)) {
            Tool.entries.forEach { tool ->
                val active = current == tool
                Row(Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (active) colors.primaryContainer else Color.Transparent)
                    .clickable { onTool(tool) }
                    .semantics { contentDescription = tool.label; selected = active }
                    .heightIn(min = 48.dp).widthIn(min = 48.dp)
                    .padding(horizontal = if (labels) 12.dp else 13.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(tool.icon(), null, tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp))
                    if (labels) Text(tool.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ToolHint(tool: Tool, count: Int, modifier: Modifier = Modifier) {
    Text(if (tool == Tool.Eraser) "Erase whole strokes · undo to restore"
        else if (count > 0) "$count selected · drag to move" else "Circle ink, then drag to move it",
        modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun QuickSlots(settings: SettingsStore, onOptions: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ColorSlots(settings, onOptions)
        WidthSlots(settings, onOptions)
    }
}

private fun SettingsStore.slots() = if (tool == Tool.Highlighter) highlighterSlots else penSlots
private fun SettingsStore.updateSlots(slots: ToolSlots) {
    if (tool == Tool.Highlighter) highlighterSlots = slots else penSlots = slots
}

@Composable
private fun ColorSlots(settings: SettingsStore, onOptions: () -> Unit) {
    val slots = settings.slots()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        slots.colors.forEachIndexed { i, color ->
            Slot(i == slots.color, {
                if (i == slots.color) onOptions() else settings.updateSlots(slots.copy(color = i))
            }, "Color ${i + 1}") {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

@Composable
private fun WidthSlots(settings: SettingsStore, onOptions: () -> Unit) {
    val slots = settings.slots()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        slots.widths.forEachIndexed { i, width ->
            Slot(i == slots.width, {
                if (i == slots.width) onOptions() else settings.updateSlots(slots.copy(width = i))
            }, "Width $width") {
                Box(Modifier.width(20.dp).height((2 + 7 * width / slots.widths.max()).dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
private fun Slot(active: Boolean, onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .border(if (active) 2.dp else 0.dp, if (active) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
        .clickable(onClick = onClick).semantics { contentDescription = label; selected = active },
        contentAlignment = Alignment.Center) { content() }
}

private fun Tool.icon(): ImageVector = when (this) {
    Tool.Pen -> Icons.Outlined.Create
    Tool.Highlighter -> Icons.Outlined.BorderColor
    Tool.Eraser -> EraserIcon
    Tool.Lasso -> Icons.Outlined.Gesture
}

/** Material Icons has no eraser; a simple outlined one in the same 24 dp grid. */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(14.5f, 4f); lineTo(20f, 9.5f); lineTo(11f, 18.5f); lineTo(7.5f, 18.5f); lineTo(4f, 15f); close()
        moveTo(9.25f, 9.25f); lineTo(14.75f, 14.75f)
        moveTo(7.5f, 18.5f); lineTo(20f, 18.5f)
    }.build()
}
