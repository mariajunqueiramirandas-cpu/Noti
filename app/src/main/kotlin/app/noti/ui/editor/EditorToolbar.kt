package app.noti.ui.editor

import app.noti.ui.common.NotiTextButton as TextButton
import app.noti.ui.common.NotiIconButton as IconButton
import app.noti.ui.common.NotiDropdownMenu as DropdownMenu
import app.noti.ui.common.NotiMenuItem as DropdownMenuItem

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.noti.ui.theme.Noti
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
    zoom: Float = 1f, scrollFraction: () -> Float = { 0f }, viewportFraction: Float = 1f,
    onResetZoom: () -> Unit = {}, onScroll: (Float) -> Unit = {},
    floatingWindows: Boolean = true,
    dial: PenDialState = remember { PenDialState() },
    /** Full screen hides the chrome; the pen can still summon the dial. */
    fullScreen: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable { mutableStateOf(true) }
    val colors = MaterialTheme.colorScheme
    val saveFailed = saveStatus.contains("failed", true)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        fun px(value: Dp) = with(density) { value.roundToPx() }
        val wide = maxWidth >= 760.dp
        // Islands sit 12 dp from the edges; the popup includes a halo for their shadow.
        val halo = (12f - Halo).dp
        val headerWidth = if (wide) (maxWidth - 260.dp).coerceAtMost(440.dp) else maxWidth - 24.dp
        val dockWidth = maxWidth - 24.dp
        val history: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolbarButton(Icons.AutoMirrored.Outlined.Undo, "Undo", onUndo, canUndo)
                ToolbarButton(Icons.AutoMirrored.Outlined.Redo, "Redo", onRedo, canRedo)
                if (wide) ToolbarButton(Icons.Outlined.Fullscreen, "Full screen", onFullScreen)
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(colors.outlineVariant))
                Box {
                    ToolbarButton(Icons.Outlined.MoreHoriz, "More", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(saveStatus) }, leadingIcon = {
                            Icon(if (saveFailed) Icons.Outlined.ErrorOutline else Icons.Outlined.Check, null)
                        }, onClick = { menuOpen = false; onSave() })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menuOpen = false; onRename() })
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
                        pdfPage?.let { (page, count) ->
                            DropdownMenuItem(text = { Text("Previous page") }, enabled = page > 0,
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null) }, onClick = { menuOpen = false; onPage(page - 1) })
                            DropdownMenuItem(text = { Text("Next page") }, enabled = page < count - 1,
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }, onClick = { menuOpen = false; onPage(page + 1) })
                        }
                        onAddPage?.let { add -> DropdownMenuItem(text = { Text("Add page") }, leadingIcon = { Icon(Icons.Outlined.Add, null) },
                            onClick = { menuOpen = false; add() }) }
                        if (!isPdf) DropdownMenuItem(text = { Text("Page paper") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                            onClick = { menuOpen = false; onPagePaper() })
                        DropdownMenuItem(text = { Text("Export PDF") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                            onClick = { menuOpen = false; onExport() })
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
                        if (settings.toolDialOnHold) DropdownMenuItem(text = { Text("Show tools") },
                            leadingIcon = { Icon(Icons.Outlined.Palette, null) },
                            onClick = { menuOpen = false; dial.open() })
                        else DropdownMenuItem(text = { Text(if (toolsExpanded) "Collapse tools" else "Expand tools") },
                            leadingIcon = { Icon(if (toolsExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore, null) },
                            onClick = { menuOpen = false; toolsExpanded = !toolsExpanded })
                        if (!wide) DropdownMenuItem(text = { Text("Full screen") }, leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) },
                            onClick = { menuOpen = false; onFullScreen() })
                        DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = { menuOpen = false; onSettings() })
                    }
                }
            }
        }
        if (!fullScreen) FloatingIsland(Alignment.TopStart, IntOffset(px(halo), px(halo)), floatingWindows) {
            GlassSurface(if (wide) Modifier.widthIn(max = headerWidth) else Modifier.width(headerWidth)) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToolbarButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", onBack)
                    Text(title, Modifier.weight(1f, fill = !wide).heightIn(min = 48.dp).clip(CircleShape)
                        .clickable(onClickLabel = "Rename", onClick = onRename).wrapContentHeight()
                        .padding(start = 6.dp, end = 14.dp),
                        style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (saveFailed) ToolbarButton(Icons.Outlined.ErrorOutline, saveStatus, onSave, tint = colors.error)
                    if (!wide) history()
                }
            }
        }
        if (wide && !fullScreen) FloatingIsland(Alignment.TopEnd, IntOffset(-px(halo), px(halo)), floatingWindows) {
            GlassSurface { Box(Modifier.padding(4.dp)) { history() } }
        }
        if (!fullScreen) FloatingIsland(Alignment.BottomCenter, IntOffset(0, -px(halo)), floatingWindows) {
            GlassSurface(Modifier.widthIn(max = dockWidth)) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    pdfPage?.let { (page, count) ->
                        TextButton(onClick = onTogglePages, contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.weight(1f, fill = false).semantics {
                                contentDescription = "Pages"; stateDescription = "Page ${page + 1} of $count"; selected = pagesOpen
                            }) {
                            Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp), tint = if (pagesOpen) colors.primary else colors.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("${page + 1} of $count", Modifier.weight(1f, fill = false),
                                color = colors.onSurface, style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(colors.outlineVariant))
                    }
                    ZoomControl(zoom, onResetZoom)
                }
            }
        }
        if (viewportFraction < 0.999f && !fullScreen) FloatingIsland(Alignment.CenterEnd, IntOffset(px((-4).dp), 0), floatingWindows, halo = 0.dp) {
            ScrollIndicator(scrollFraction, viewportFraction, onScroll, Modifier.height((maxHeight * 0.25f).coerceIn(96.dp, 180.dp)))
        }
        RadialToolSelector(settings, selectionCount, maxWidth, maxHeight, floatingWindows,
            expanded = toolsExpanded, onExpandedChange = { toolsExpanded = it },
            dial = dial, docked = !fullScreen && !settings.toolDialOnHold)
    }
}

@Composable
private fun ZoomControl(zoom: Float, onResetZoom: () -> Unit) {
    TextButton(onClick = onResetZoom, contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Zoom ${(zoom * 100).roundToInt()} percent. Reset to fit width"
        }) {
        Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (enabled) tint else tint.copy(alpha = 0.28f))
    }
}

/** Quiet floating chrome: a soft pill, a whisper of edge, a warm low shadow. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shadow = Noti.extras.shadow
    Box(modifier.shadow(6.dp, shape, ambientColor = shadow, spotColor = shadow).clip(shape).background(colors.surface)
        .border(0.5.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) { content() }
    }
}

@Composable
private fun BoxScope.FloatingIsland(alignment: Alignment, offset: IntOffset, floating: Boolean,
    halo: Dp = Halo.dp, content: @Composable () -> Unit) {
    if (floating) {
        // TYPE_APPLICATION_SUB_PANEL (Compose Popup) sorts above TYPE_APPLICATION_PANEL (ink).
        Popup(alignment = alignment, offset = offset, properties = PopupProperties(focusable = false,
            dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = true)) {
            Box(Modifier.padding(halo)) { content() }
        }
    } else Box(Modifier.align(alignment).offset { offset }.padding(halo), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun ScrollIndicator(fraction: () -> Float, visible: Float, onScroll: (Float) -> Unit, modifier: Modifier = Modifier) {
    // Read in semantics, gestures and drawing only: scrolling moves the thumb without recomposing.
    val source by rememberUpdatedState(fraction)
    fun current() = source().coerceIn(0f, 1f)
    val percent by remember { derivedStateOf { (current() * 100).roundToInt() / 100f } }
    val action by rememberUpdatedState(onScroll)
    val thumbFraction = visible.coerceIn(0.12f, 1f)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.width(48.dp).semantics {
        contentDescription = "Document scroll"
        progressBarRangeInfo = ProgressBarRangeInfo(percent, 0f..1f)
        setProgress { action(it.coerceIn(0f, 1f)); true }
    }.pointerInput(thumbFraction) {
        var start = 0f
        var travel = 0f
        detectVerticalDragGestures(onDragStart = { start = current(); travel = 0f }) { change, delta ->
            change.consume(); travel += delta
            val range = (size.height * (1f - thumbFraction) - 16.dp.toPx()).coerceAtLeast(1f)
            action((start + travel / range).coerceIn(0f, 1f))
        }
    }) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 21.5.dp, vertical = 8.dp)) {
            val h = size.height * thumbFraction
            drawRoundRect(color.copy(alpha = 0.35f), topLeft = Offset(0f, (size.height - h) * current()),
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
