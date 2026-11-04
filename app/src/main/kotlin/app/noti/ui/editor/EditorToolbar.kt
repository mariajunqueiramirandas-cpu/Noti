package app.noti.ui.editor

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        fun px(value: Dp) = with(density) { value.roundToPx() }
        FloatingIsland(Alignment.TopStart, IntOffset(px(12.dp), px(12.dp)), floatingWindows) {
            GlassSurface(Modifier.width((maxWidth - 24.dp).coerceAtMost(420.dp))) {
                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolbarButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", onBack)
                    Text(title, Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRename).wrapContentHeight().padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            DropdownMenuItem(text = { Text("Full screen") }, leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) }, onClick = { menuOpen = false; onFullScreen() })
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { menuOpen = false; onSettings() })
                        }
                    }
                }
            }
        }
        FloatingIsland(Alignment.BottomStart, IntOffset(px(12.dp), px((-12).dp)), floatingWindows) {
            GlassSurface(Modifier.widthIn(max = (maxWidth - 24.dp).coerceAtLeast(0.dp))) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    pdfPage?.let { (page, count) ->
                        TextButton(onClick = onTogglePages, contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp).semantics {
                                contentDescription = "Pages"; stateDescription = "Page ${page + 1} of $count"
                            }) {
                            Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${page + 1} / $count", color = if (pagesOpen) colors.primary else colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.width(1.dp).height(16.dp).background(colors.outlineVariant))
                    }
                    TextButton(onClick = onResetZoom, contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Zoom ${(zoom * 100).roundToInt()} percent. Reset to fit width"
                        }) {
                        Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (viewportFraction < 0.999f) FloatingIsland(Alignment.CenterEnd, IntOffset(px((-4).dp), 0), floatingWindows) {
            ScrollIndicator(scrollFraction, viewportFraction, onScroll, Modifier.height((maxHeight * 0.25f).coerceIn(96.dp, 180.dp)))
        }
        RadialTools(settings, selectionCount, maxWidth, maxHeight, floatingWindows)
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true,
    tint: Color = LocalContentColor.current) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

/** Quiet, nearly opaque chrome with a fine edge and a small shadow. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp), content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.shadow(3.dp, shape).clip(shape).background(colors.surface.copy(alpha = 0.98f))
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

/** The launcher stays anchored while four targets fan out. No animated window resizing. */
@Composable
private fun BoxScope.RadialTools(settings: SettingsStore, selectionCount: Int, width: Dp, height: Dp, floating: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val progress by animateFloatAsState(if (expanded) 1f else 0f,
        animationSpec = tween(160, easing = FastOutSlowInEasing), label = "Tool fan")
    val density = LocalDensity.current
    fun px(value: Dp) = with(density) { value.toPx() }
    val fanSize = 180.dp
    val fanLift = if (width < 600.dp) 60.dp else 0.dp
    val fanHeight = fanSize + fanLift
    // Reserve room for the full fan even when collapsed, so opening never moves the launcher.
    val minX = -(px(width - fanSize - 24.dp)).coerceAtLeast(0f)
    val minY = -(px(height - fanHeight - 84.dp)).coerceAtLeast(0f)
    val x = position.x.coerceIn(minX, 0f)
    val y = position.y.coerceIn(minY, 0f)
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    val drag = Modifier.pointerInput(minX, minY) {
        detectDragGestures { change, amount ->
            change.consume()
            position = Offset((position.x + amount.x).coerceIn(minX, 0f), (position.y + amount.y).coerceIn(minY, 0f))
        }
    }
    FloatingIsland(Alignment.BottomEnd, IntOffset((-px(12.dp) + x).roundToInt(), (-px(12.dp) + y).roundToInt()), floating) {
        val visible = expanded || progress > 0f
        Box(Modifier.size(if (visible) fanSize else 52.dp, if (visible) fanHeight else 52.dp)) {
            if (visible) {
                val offsets = listOf(Offset(-128f, 0f), Offset(-112f, -64f), Offset(-64f, -112f), Offset(0f, -128f))
                Tool.entries.forEachIndexed { i, tool ->
                    val active = settings.tool == tool
                    GlassSurface(Modifier.align(Alignment.BottomEnd).offset {
                        IntOffset(px(offsets[i].x.dp * progress).roundToInt(), px((offsets[i].y.dp - fanLift) * progress).roundToInt())
                    }.size(48.dp).graphicsLayer { alpha = progress }, CircleShape) {
                        IconButton(onClick = { settings.tool = tool; expanded = false; options = false },
                            enabled = expanded && progress >= 0.999f, modifier = Modifier.fillMaxSize().semantics { selected = active }
                                .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                            Icon(tool.icon(), tool.label, Modifier.size(22.dp),
                                tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                }
                if (drawing) Box(Modifier.align(Alignment.BottomEnd).offset {
                    IntOffset(px((-58).dp * progress).roundToInt(), px(((-58).dp - fanLift / 2) * progress).roundToInt())
                }.graphicsLayer { alpha = progress }) {
                    GlassSurface(Modifier.size(48.dp), CircleShape) {
                        IconButton(onClick = { options = true }, enabled = expanded && progress >= 0.999f, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Outlined.Tune, "Brush options", Modifier.size(20.dp))
                        }
                    }
                    if (options) ToolOptionsPopup(settings) { options = false }
                }
            }
            GlassSurface(Modifier.align(Alignment.BottomEnd).size(52.dp).then(drag), CircleShape) {
                IconButton(onClick = { expanded = !expanded; options = false }, modifier = Modifier.fillMaxSize().semantics {
                    stateDescription = if (expanded) "Tools expanded" else if (settings.tool == Tool.Lasso && selectionCount > 0)
                        "$selectionCount selected. Drag ink to move" else settings.tool.label
                }) {
                    Icon(if (expanded) Icons.Outlined.Close else settings.tool.icon(),
                        if (expanded) "Close tools" else "Open tools", Modifier.size(22.dp))
                }
                if (!expanded && drawing) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(4.dp)
                    .background(Color(if (settings.tool == Tool.Highlighter) settings.highlighterSlots.currentColor else settings.penSlots.currentColor), CircleShape))
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
