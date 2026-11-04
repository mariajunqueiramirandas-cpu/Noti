package app.noti.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

/** Document actions and writing controls have separate rows, with a scrollable tool strip. */
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
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row {
                    IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                    IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                }
                VerticalDivider(Modifier.height(28.dp), color = colors.outlineVariant)
                Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                    Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tool.entries.forEach { tool ->
                            val active = settings.tool == tool
                            Row(
                                Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(if (active) colors.primaryContainer else Color.Transparent)
                                    .clickable {
                                        if (active && (tool == Tool.Pen || tool == Tool.Highlighter)) optionsOpen = true
                                        else { settings.tool = tool; optionsOpen = false }
                                    }.heightIn(min = 44.dp).padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Icon(tool.icon(), tool.label, tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(21.dp))
                                Text(tool.label, style = MaterialTheme.typography.labelLarge, color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (settings.tool == Tool.Pen || settings.tool == Tool.Highlighter) {
                    Box {
                        SlotGroup(settings.tool, settings) { optionsOpen = true }
                        if (optionsOpen) ToolOptionsPopup(settings) { optionsOpen = false }
                    }
                    IconButton(onClick = { optionsOpen = true }) { Icon(Icons.Outlined.Tune, "Brush options") }
                } else {
                    Text(if (settings.tool == Tool.Eraser) "Erase whole strokes · undo to restore"
                        else if (selectionCount > 0) "$selectionCount selected · drag to move" else "Circle ink, then drag to move it",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SlotGroup(tool: Tool, settings: SettingsStore, onOpenOptions: () -> Unit) {
    val slots = if (tool == Tool.Highlighter) settings.highlighterSlots else settings.penSlots
    val update: (ToolSlots) -> Unit = { if (tool == Tool.Highlighter) settings.highlighterSlots = it else settings.penSlots = it }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        slots.colors.forEachIndexed { i, c ->
            Slot(i == slots.color, {
                if (i == slots.color) onOpenOptions() else update(slots.copy(color = i))
            }, "Color ${i + 1}") {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape))
            }
        }
        Spacer(Modifier.width(4.dp))
        slots.widths.forEachIndexed { i, width ->
            Slot(i == slots.width, {
                if (i == slots.width) onOpenOptions() else update(slots.copy(width = i))
            }, "Width $width") {
                Box(Modifier.width(20.dp).height((2 + 7 * width / slots.widths.max()).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
private fun Slot(selected: Boolean, onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape)
        .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
        .clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) { content() }
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
