package app.noti.ui.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.ui.brand.DotiMascot
import kotlin.math.roundToInt

/** Compact controls in sub-panel windows, above the native ink input panel. */
@Composable
internal fun EditorToolbar(
    title: String, settings: SettingsStore, pagesOpen: Boolean,
    onBack: () -> Unit, onRename: () -> Unit, onTogglePages: () -> Unit,
    onPagePaper: () -> Unit, onFullScreen: () -> Unit, onSettings: () -> Unit,
    pdfPage: Pair<Int, Int>? = null, onPage: (Int) -> Unit = {},
    canUndo: Boolean = false, canRedo: Boolean = false,
    onUndo: () -> Unit = {}, onRedo: () -> Unit = {}, onExport: () -> Unit = {},
    onAddPage: (() -> Unit)? = null, saveStatus: String = "Autosave on", onSave: () -> Unit = {},
    selectionCount: Int = 0, isPdf: Boolean = false,
    zoom: Float = 1f, scrollFraction: Float = 0f, viewportFraction: Float = 1f,
    onResetZoom: () -> Unit = {}, onScroll: (Float) -> Unit = {},
    floatingWindows: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val saveFailed = saveStatus.contains("failed", true)
    var toolsVisible by remember { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        fun px(value: Dp) = with(density) { value.roundToPx() }
        val wide = maxWidth >= 600.dp
        FloatingIsland(Alignment.TopStart, IntOffset.Zero, floatingWindows) {
            // A single fine rule keeps the document as the dominant surface.
            Column(Modifier.width(maxWidth).background(colors.surface.copy(alpha = 0.97f))) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        DotiMascot(Modifier.size(30.dp), description = "Back to library")
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f, fill = false).heightIn(min = 48.dp).clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onRename).wrapContentHeight().padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (wide) {
                            Text("/", Modifier.padding(horizontal = 16.dp), color = colors.outline)
                            Text(settings.tool.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                    if (wide) {
                        ZoomControl(zoom, onResetZoom)
                        ToolbarButton(Icons.Outlined.Settings, "Settings", onSettings)
                    }
                    if (saveFailed) ToolbarButton(Icons.Outlined.ErrorOutline, saveStatus, onSave, tint = colors.error)
                    ToolbarButton(Icons.AutoMirrored.Outlined.Undo, "Undo", onUndo, canUndo)
                    ToolbarButton(Icons.AutoMirrored.Outlined.Redo, "Redo", onRedo, canRedo)
                    Box {
                        ToolbarButton(Icons.Outlined.MoreHoriz, "More", { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(saveStatus) }, leadingIcon = {
                                Icon(if (saveFailed) Icons.Outlined.ErrorOutline else Icons.Outlined.Check, null)
                            }, onClick = { menuOpen = false; onSave() })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menuOpen = false; onRename() })
                            pdfPage?.let { (page, count) ->
                                DropdownMenuItem(text = { Text("Previous page") }, enabled = page > 0,
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null) }, onClick = { menuOpen = false; onPage(page - 1) })
                                DropdownMenuItem(text = { Text("Next page") }, enabled = page < count - 1,
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }, onClick = { menuOpen = false; onPage(page + 1) })
                            }
                            DropdownMenuItem(text = { Text("Export PDF") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) }, onClick = { menuOpen = false; onExport() })
                            if (!isPdf) DropdownMenuItem(text = { Text("Page paper") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = { menuOpen = false; onPagePaper() })
                            onAddPage?.let { add -> DropdownMenuItem(text = { Text("Add page") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menuOpen = false; add() }) }
                            DropdownMenuItem(text = { Text(if (toolsVisible) "Hide tools" else "Show tools") },
                                leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = { menuOpen = false; toolsVisible = !toolsVisible })
                            DropdownMenuItem(text = { Text("Full screen") }, leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) }, onClick = { menuOpen = false; onFullScreen() })
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menuOpen = false; onSettings() })
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.55f))
            }
        }
        FloatingIsland(Alignment.BottomStart, IntOffset(px(12.dp), -px(12.dp)), floatingWindows) {
            Row(Modifier.background(colors.surface.copy(alpha = 0.94f), RoundedCornerShape(6.dp)), verticalAlignment = Alignment.CenterVertically) {
                pdfPage?.let { (page, count) ->
                    TextButton(onClick = onTogglePages, contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Pages"; stateDescription = "Page ${page + 1} of $count"
                        }) {
                        Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp), tint = colors.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("Pages", color = colors.onSurface, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(12.dp))
                        Text("${page + 1} / $count", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!wide) ZoomControl(zoom, onResetZoom)
            }
        }
        if (wide) FloatingIsland(Alignment.TopEnd, IntOffset(-px(12.dp), px(64.dp)), floatingWindows) {
            Column(Modifier.background(colors.surface.copy(alpha = 0.96f), RoundedCornerShape(6.dp)),
                horizontalAlignment = Alignment.End) {
                CanvasAction("Export", Icons.Outlined.IosShare, onExport)
                if (!isPdf) CanvasAction("Paper", Icons.Outlined.Description, onPagePaper)
                CanvasAction("Precision", Icons.Outlined.Tune, onSettings)
            }
        }
        if (viewportFraction < 0.999f) FloatingIsland(Alignment.CenterEnd, IntOffset(px((-4).dp), 0), floatingWindows) {
            ScrollIndicator(scrollFraction, viewportFraction, onScroll, Modifier.height((maxHeight * 0.25f).coerceIn(96.dp, 180.dp)))
        }
        if (toolsVisible) RadialToolSelector(settings, selectionCount, maxWidth, maxHeight, floatingWindows)
    }
}

@Composable
private fun ZoomControl(zoom: Float, onResetZoom: () -> Unit) {
    TextButton(onClick = onResetZoom, contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Zoom ${(zoom * 100).roundToInt()} percent. Reset to fit width"
        }) {
        Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CanvasAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(10.dp))
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

/** Quiet, nearly opaque chrome with a fine edge and a small shadow. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp), content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.shadow(1.dp, shape).clip(shape).background(colors.surface.copy(alpha = 0.98f))
        .border(0.5.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) { content() }
    }
}

@Composable
private fun BoxScope.FloatingIsland(alignment: Alignment, offset: IntOffset, floating: Boolean, content: @Composable () -> Unit) {
    if (floating) {
        // TYPE_APPLICATION_SUB_PANEL (Compose Popup) sorts above TYPE_APPLICATION_PANEL (ink).
        Popup(alignment = alignment, offset = offset, properties = PopupProperties(focusable = false,
            dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = true), content = content)
    } else Box(Modifier.align(alignment).offset { offset }, contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun ScrollIndicator(fraction: Float, visible: Float, onScroll: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(fraction.coerceIn(0f, 1f))
    val action by rememberUpdatedState(onScroll)
    val thumbFraction = visible.coerceIn(0.12f, 1f)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.width(48.dp).semantics {
        contentDescription = "Document scroll"
        progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
        setProgress { action(it.coerceIn(0f, 1f)); true }
    }.pointerInput(thumbFraction) {
        var start = 0f
        var travel = 0f
        detectVerticalDragGestures(onDragStart = { start = current; travel = 0f }) { change, delta ->
            change.consume(); travel += delta
            val range = (size.height * (1f - thumbFraction) - 16.dp.toPx()).coerceAtLeast(1f)
            action((start + travel / range).coerceIn(0f, 1f))
        }
    }) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 22.5.dp, vertical = 8.dp)) {
            val h = size.height * thumbFraction
            drawRoundRect(color.copy(alpha = 0.4f), topLeft = Offset(0f, (size.height - h) * current),
                size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width))
        }
    }
}

internal fun Tool.icon(): ImageVector = when (this) {
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
