package app.noti.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import app.noti.ui.common.comingSoon
import app.noti.ui.common.greyedOut

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
    /** For a PDF: the page on screen (0-based) and the page count; null for a notebook. */
    pdfPage: Pair<Int, Int>? = null,
    onPage: (Int) -> Unit = {},
) {
    val undoSoon = comingSoon("Undo")
    val redoSoon = comingSoon("Redo")
    val exportSoon = comingSoon("Export")
    val toolSoon = mapOf(Tool.Eraser to comingSoon("The eraser"), Tool.Lasso to comingSoon("The lasso"))
    var menuOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = ToolbarSpacing,
            modifier = Modifier.fillMaxWidth().height(ToolbarHeight).padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library") }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onRename)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Rename")
            }
            if (pdfPage != null) {
                val (page, count) = pdfPage
                IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous page")
                }
                Text(
                    "${page + 1} / $count",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                IconButton(onClick = { onPage(page + 1) }, enabled = page < count - 1) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next page")
                }
            }

            ToolbarDivider()
            IconButton(onClick = undoSoon, modifier = Modifier.greyedOut()) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = redoSoon, modifier = Modifier.greyedOut()) {
                Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
            }

            ToolbarDivider()
            Tool.entries.forEach { tool ->
                IconToggleButton(
                    checked = settings.tool == tool,
                    onCheckedChange = {
                        if (!tool.available) {
                            toolSoon[tool]?.invoke()
                        } else if (settings.tool == tool) {
                            optionsOpen = true  // tap the active tool again: its options
                        } else {
                            settings.tool = tool
                        }
                    },
                    modifier = Modifier
                        .greyedOut(!tool.available)
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (settings.tool == tool) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        ),
                ) {
                    Icon(tool.icon(), contentDescription = tool.label)
                }
            }

            ToolbarDivider()
            Box {
                SlotGroup(
                    tool = settings.tool,
                    settings = settings,
                    onOpenOptions = { optionsOpen = true },
                )
                if (optionsOpen) {
                    ToolOptionsPopup(settings = settings, onDismiss = { optionsOpen = false })
                }
            }

            ToolbarDivider()
            IconToggleButton(
                checked = pagesOpen,
                onCheckedChange = { onTogglePages() },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (pagesOpen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            ) {
                // Mirrored: the panel docks on the left.
                Icon(
                    Icons.AutoMirrored.Outlined.ViewSidebar,
                    contentDescription = "Pages",
                    modifier = Modifier.graphicsLayer(scaleX = -1f),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Export") },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                        onClick = exportSoon,
                        modifier = Modifier.greyedOut(),
                    )
                    if (pdfPage == null) {
                        DropdownMenuItem(
                            text = { Text("Page paper") },
                            leadingIcon = { Icon(Icons.Outlined.Description, null) },
                            onClick = { menuOpen = false; onPagePaper() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Full screen") },
                        leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) },
                        onClick = { menuOpen = false; onFullScreen() },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                        onClick = { menuOpen = false; onSettings() },
                    )
                }
            }
        }
    }
}

/**
 * The 3 quick colors and 3 quick widths of the current tool. Tapping a slot selects it;
 * tapping the selected slot again opens the tool's options. Eraser and lasso have no
 * slots yet, so the group shows the pen's, greyed.
 */
@Composable
private fun SlotGroup(tool: Tool, settings: SettingsStore, onOpenOptions: () -> Unit) {
    val drawing = tool == Tool.Pen || tool == Tool.Highlighter
    val slots = if (tool == Tool.Highlighter) settings.highlighterSlots else settings.penSlots
    val update: (ToolSlots) -> Unit = {
        if (tool == Tool.Highlighter) settings.highlighterSlots = it else settings.penSlots = it
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.greyedOut(!drawing),
    ) {
        slots.colors.forEachIndexed { i, c ->
            Slot(selected = i == slots.color, onClick = {
                if (!drawing) return@Slot
                if (i == slots.color) onOpenOptions() else update(slots.copy(color = i))
            }) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        slots.widths.forEachIndexed { i, w ->
            Slot(selected = i == slots.width, onClick = {
                if (!drawing) return@Slot
                if (i == slots.width) onOpenOptions() else update(slots.copy(width = i))
            }) {
                val max = slots.widths.max()
                val d = (6f + 16f * (w / max)).dp
                Box(Modifier.size(d).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
private fun Slot(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
    ) { content() }
}

@Composable
private fun ToolbarDivider() {
    VerticalDivider(
        modifier = Modifier.height(28.dp).padding(horizontal = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun Tool.icon(): ImageVector = when (this) {
    Tool.Pen -> Icons.Outlined.Create
    Tool.Highlighter -> Icons.Outlined.BorderColor
    Tool.Eraser -> EraserIcon
    Tool.Lasso -> Icons.Outlined.HighlightAlt
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
