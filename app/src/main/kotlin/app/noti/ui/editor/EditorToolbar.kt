package app.noti.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
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
import app.noti.data.ToolSlots
import kotlin.math.roundToInt

/** Each island is a sub-panel window: it remains above the native ink input panel. */
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val density = LocalDensity.current
        fun px(value: Dp) = with(density) { value.roundToPx() }
        FloatingIsland(Alignment.TopStart, IntOffset(px(16.dp), px(16.dp)), floatingWindows) {
            GlassSurface(Modifier.width(if (wide) 340.dp else (maxWidth - 32.dp).coerceAtLeast(0.dp))) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(title, Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onRename),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.clip(CircleShape).clickable(onClick = onSave).padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(5.dp).background(if (saveStatus.contains("failed", true)) colors.error else Color(0xFF5C9988), CircleShape))
                            Text(saveStatus, style = MaterialTheme.typography.labelSmall,
                                color = if (saveStatus.contains("failed", true)) colors.error else colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreHoriz, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Export PDF") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) }, onClick = { menuOpen = false; onExport() })
                            if (!isPdf) DropdownMenuItem(text = { Text("Page paper") }, leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = { menuOpen = false; onPagePaper() })
                            onAddPage?.let { add -> DropdownMenuItem(text = { Text("Add page") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menuOpen = false; add() }) }
                            DropdownMenuItem(text = { Text("Full screen") }, leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) }, onClick = { menuOpen = false; onFullScreen() })
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menuOpen = false; onSettings() })
                        }
                    }
                }
            }
        }
        FloatingIsland(if (wide) Alignment.TopEnd else Alignment.TopStart,
            IntOffset(px(if (wide) (-16).dp else 16.dp), px(if (wide) 16.dp else 88.dp)), floatingWindows) {
            GlassSurface {
                Row(Modifier.padding(4.dp)) {
                    IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                    IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                }
            }
        }
        FloatingIsland(Alignment.BottomStart, IntOffset(px(16.dp), px((-16).dp)), floatingWindows) {
            Row(Modifier.widthIn(max = (maxWidth - 32.dp).coerceAtLeast(0.dp)),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                pdfPage?.let { (page, count) ->
                    GlassSurface(Modifier.weight(1f, fill = false)) {
                        Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous page") }
                            TextButton(onClick = onTogglePages, modifier = Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("${page + 1} / $count", Modifier.semantics { contentDescription = "Pages" },
                                    color = if (pagesOpen) colors.primary else colors.onSurface,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onPage(page + 1) }, enabled = page < count - 1) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next page") }
                        }
                    }
                }
                GlassSurface {
                    TextButton(onClick = onResetZoom, modifier = Modifier.heightIn(min = 52.dp)
                        .semantics { contentDescription = "Zoom ${(zoom * 100).roundToInt()} percent. Reset to fit width" }) {
                        Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        FloatingIsland(Alignment.CenterEnd, IntOffset(px((-12).dp), 0), floatingWindows) {
            ScrollIndicator(scrollFraction, viewportFraction, onScroll, Modifier.height((maxHeight * 0.25f).coerceIn(96.dp, 200.dp)))
        }
        RadialTools(settings, selectionCount, maxWidth, maxHeight, floatingWindows)
    }
}

/** Translucent tint, a specular edge and diffuse shadow; ink remains visible through it. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(24.dp), content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val glass = if (dark) Color(0xFF242B38) else Color(0xFFF7FAFF)
    Box(modifier.shadow(16.dp, shape, ambientColor = Color(0xFF142239).copy(alpha = 0.12f),
            spotColor = Color(0xFF142239).copy(alpha = 0.15f))
        .clip(shape)
        .background(Brush.linearGradient(listOf(glass.copy(alpha = 0.94f), glass.copy(alpha = 0.79f)), start = Offset.Zero, end = Offset(400f, 600f)))
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.24f else 0.95f),
            colors.outlineVariant.copy(alpha = 0.22f))), shape)) {
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
private fun BoxScope.RadialTools(settings: SettingsStore, selectionCount: Int, width: Dp, height: Dp, floating: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val inset = with(density) { 20.dp.roundToPx() }
    val availableW = with(density) { width.toPx() }
    val availableH = with(density) { height.toPx() }
    val compact = width < 600.dp
    val panelMaxHeight = (height - if (compact) 240.dp else 108.dp).coerceAtLeast(64.dp)
    val panelW = with(density) { (if (expanded) 280.dp else 64.dp).toPx() }
    val panelH = with(density) { (if (expanded) 402.dp.coerceAtMost(panelMaxHeight) else 64.dp).toPx() }
    val minX = -(availableW - panelW - inset * 2).coerceAtLeast(0f)
    // Keep the launcher below the document/history islands and above navigation.
    val bottom = with(density) { (if (width < 600.dp) 88.dp else 20.dp).roundToPx() }
    val top = with(density) { (if (width < 600.dp) 152.dp else 88.dp).toPx() }
    val minY = -(availableH - panelH - bottom - top).coerceAtLeast(0f)
    val x = position.x.coerceIn(minX, 0f)
    val y = position.y.coerceIn(minY, 0f)
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    val drag = Modifier.pointerInput(availableW, availableH, expanded) {
        detectDragGestures { change, amount ->
            change.consume()
            position = Offset((position.x + amount.x).coerceIn(minX, 0f), (position.y + amount.y).coerceIn(minY, 0f))
        }
    }
    FloatingIsland(Alignment.BottomEnd, IntOffset(-inset + x.roundToInt(), -bottom + y.roundToInt()), floating) {
        if (!expanded) {
            GlassSurface(Modifier.size(64.dp).then(drag), CircleShape) {
                IconButton(onClick = { expanded = true }, modifier = Modifier.fillMaxSize()
                    .semantics { stateDescription = settings.tool.label }) {
                    Icon(settings.tool.icon(), "Open tools", Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(5.dp)
                    .background(Color(settings.slots().currentColor), CircleShape))
            }
        } else {
            Column(Modifier.width(280.dp).heightIn(max = panelMaxHeight)
                .verticalScroll(rememberScrollState()).animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassSurface(Modifier.size(264.dp), CircleShape) {
                    Box(Modifier.size(244.dp).align(Alignment.Center)) {
                        val positions = listOf(Alignment.TopCenter, Alignment.CenterEnd, Alignment.BottomCenter, Alignment.CenterStart)
                        Tool.entries.forEachIndexed { i, tool ->
                            Column(Modifier.align(positions[i]).width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val active = settings.tool == tool
                                IconButton(onClick = { settings.tool = tool; expanded = false; options = false },
                                    modifier = Modifier.size(52.dp).clip(CircleShape)
                                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .semantics { selected = active }) {
                                    Icon(tool.icon(), tool.label, tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                }
                                Text(tool.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        Box(Modifier.align(Alignment.Center).size(64.dp).then(drag)) {
                            IconButton(onClick = { expanded = false }, modifier = Modifier.fillMaxSize().clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))) {
                                Icon(Icons.Outlined.Close, "Close tools")
                            }
                        }
                    }
                }
                if (drawing) {
                    GlassSurface(shape = RoundedCornerShape(28.dp)) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ColorSlots(settings) { options = true }
                                IconButton(onClick = { options = true }) { Icon(Icons.Outlined.Tune, "Brush options") }
                            }
                            WidthSlots(settings) { options = true }
                            if (options) ToolOptionsPopup(settings) { options = false }
                        }
                    }
                } else {
                    GlassSurface {
                        Text(if (settings.tool == Tool.Eraser) "Erase whole strokes" else if (selectionCount > 0) "$selectionCount selected · drag to move" else "Circle ink to select",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
    if (expanded) androidx.activity.compose.BackHandler { expanded = false; options = false }
}

@Composable
internal fun ScrollIndicator(fraction: Float, visible: Float, onScroll: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(fraction.coerceIn(0f, 1f))
    val action by rememberUpdatedState(onScroll)
    val thumbFraction = visible.coerceIn(0.12f, 1f)
    GlassSurface(modifier.width(48.dp).semantics {
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
    }, CircleShape) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp)) {
            drawRoundRect(Color(0xFF8290AA).copy(alpha = 0.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
            val h = size.height * thumbFraction
            drawRoundRect(Color(0xFF8290AA).copy(alpha = 0.85f), topLeft = Offset(0f, (size.height - h) * current),
                size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
        }
    }
}

private fun SettingsStore.slots() = if (tool == Tool.Highlighter) highlighterSlots else penSlots
private fun SettingsStore.updateSlots(value: ToolSlots) { if (tool == Tool.Highlighter) highlighterSlots = value else penSlots = value }

@Composable
private fun ColorSlots(settings: SettingsStore, onOptions: () -> Unit) {
    val slots = settings.slots()
    Row {
        slots.colors.forEachIndexed { i, color ->
            Slot(i == slots.color, { if (i == slots.color) onOptions() else settings.updateSlots(slots.copy(color = i)) }, "Color ${i + 1}") {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape))
            }
        }
    }
}

@Composable
private fun WidthSlots(settings: SettingsStore, onOptions: () -> Unit) {
    val slots = settings.slots()
    Row {
        slots.widths.forEachIndexed { i, width ->
            Slot(i == slots.width, { if (i == slots.width) onOptions() else settings.updateSlots(slots.copy(width = i)) }, "Width $width") {
                Box(Modifier.width(20.dp).height((2 + 5 * width / slots.widths.max()).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
private fun Slot(active: Boolean, onClick: () -> Unit, label: String, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else Color.Transparent)
        .clickable(onClick = onClick).semantics { contentDescription = label; selected = active },
        contentAlignment = Alignment.Center, content = content)
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
