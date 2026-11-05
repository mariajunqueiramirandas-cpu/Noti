package app.noti.ui.editor

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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.AbsoluteAlignment
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    Box(modifier.shadow(2.dp, shape).clip(shape).background(colors.surface.copy(alpha = 0.98f))
        .border(0.5.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)) {
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

/** Read drag position during placement, without recomposing the toolbar on every pointer event. */
@Composable
private fun BoxScope.MovableToolIsland(
    position: State<Offset>, minX: Float, minY: Float, inset: IntOffset,
    floating: Boolean, content: @Composable () -> Unit,
) {
    val provider = remember(position, minX, minY, inset) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val offset = position.value
                return IntOffset(anchorBounds.right - popupContentSize.width + inset.x + offset.x.coerceIn(minX, 0f).roundToInt(),
                    anchorBounds.bottom - popupContentSize.height + inset.y + offset.y.coerceIn(minY, 0f).roundToInt())
            }
        }
    }
    if (floating) Popup(popupPositionProvider = provider, properties = PopupProperties(
        focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false), content = content)
    else Box(Modifier.align(AbsoluteAlignment.BottomRight).absoluteOffset {
        IntOffset(position.value.x.coerceIn(minX, 0f).roundToInt(), position.value.y.coerceIn(minY, 0f).roundToInt()) + inset
    }) { content() }
}

@Composable
private fun BoxScope.RadialTools(settings: SettingsStore, selectionCount: Int, width: Dp, height: Dp, floating: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val position = remember { mutableStateOf(Offset.Zero) }
    val opacity by animateFloatAsState(if (expanded) 1f else 0f, tween(100), label = "Tool palette opacity")
    val density = LocalDensity.current
    fun px(value: Dp) = with(density) { value.roundToPx() }
    // Reserve the radial selector's footprint in both states. Keep clear of the scroll gutter.
    val minX = -px((width - 332.dp).coerceAtLeast(0.dp)).toFloat()
    val minY = -px((height - 272.dp).coerceAtLeast(0.dp)).toFloat()
    val inset = IntOffset(-px(60.dp), -px(80.dp))
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    val colors = MaterialTheme.colorScheme
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val drag = Modifier.onGloballyPositioned { coordinates = it }.pointerInput(minX, minY) {
        var startScreen = Offset.Zero
        var startPosition = Offset.Zero
        detectDragGestures(
            onDragStart = { local ->
                startScreen = coordinates?.localToScreen(local) ?: local
                startPosition = Offset(position.value.x.coerceIn(minX, 0f), position.value.y.coerceIn(minY, 0f))
                dragging = true; expanded = false; options = false
            },
            onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
        ) { change, _ ->
            change.consume()
            val screen = coordinates?.localToScreen(change.position) ?: change.position
            val next = startPosition + screen - startScreen
            position.value = Offset(next.x.coerceIn(minX, 0f), next.y.coerceIn(minY, 0f))
        }
    }
    MovableToolIsland(position, minX, minY, inset, floating) {
        GlassSurface(Modifier.size(52.dp).then(drag), RoundedCornerShape(18.dp)) {
            IconButton(onClick = { expanded = !expanded; options = false }, modifier = Modifier.fillMaxSize().semantics {
                stateDescription = if (expanded) "${settings.tool.label}. Tools expanded" else if (settings.tool == Tool.Lasso && selectionCount > 0)
                    "$selectionCount selected. Drag ink to move" else settings.tool.label
            }) {
                Icon(settings.tool.icon(), if (expanded) "Close tools" else "Open tools", Modifier.size(22.dp))
            }
            if (drawing) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(12.dp, 3.dp)
                .background(Color(if (settings.tool == Tool.Highlighter) settings.highlighterSlots.currentColor else settings.penSlots.currentColor), CircleShape))
        }
    }
    if (!dragging && (expanded || opacity > 0f)) {
        MovableToolIsland(position, minX, minY, inset + IntOffset(0, -px(60.dp)), floating) {
            val ring = 66.dp
            val step = 360f / (Tool.entries.size + 1)
            GlassSurface(Modifier.size(192.dp).graphicsLayer { alpha = opacity }, CircleShape) {
                Tool.entries.forEachIndexed { index, tool ->
                    val angle = (-90f + index * step) * (PI / 180.0).toFloat()
                    val active = settings.tool == tool
                    IconButton(onClick = { settings.tool = tool; expanded = false; options = false },
                        enabled = expanded, modifier = Modifier.align(Alignment.Center)
                            .offset((cos(angle) * ring.value).dp, (sin(angle) * ring.value).dp)
                            .size(48.dp).clip(CircleShape)
                            .background(if (active) colors.primaryContainer else Color.Transparent)
                            .semantics { selected = active }) {
                        Icon(tool.icon(), tool.label, Modifier.size(22.dp),
                            tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant)
                    }
                }
                val tuneAngle = (-90f + Tool.entries.size * step) * (PI / 180.0).toFloat()
                Box(Modifier.align(Alignment.Center)
                    .offset((cos(tuneAngle) * ring.value).dp, (sin(tuneAngle) * ring.value).dp)) {
                    IconButton(onClick = { options = !options }, enabled = expanded && drawing,
                        modifier = Modifier.size(48.dp).clip(CircleShape)) {
                        Icon(Icons.Outlined.Tune, "Brush options", Modifier.size(20.dp))
                    }
                    if (options) ToolOptionsPopup(settings) { options = false }
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
