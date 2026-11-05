package app.noti.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import app.noti.ui.common.NotiTextButton
import app.noti.ui.common.NotiIconButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.data.ToolSlots
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val DialSize = 192.dp
private const val DialCenter = 96f
private const val TargetRadius = 72f

/** A persistent eight-sector dial, in its own window above the native ink input panel. */
@Composable
internal fun BoxScope.RadialToolSelector(
    settings: SettingsStore, selectionCount: Int, width: Dp, height: Dp, floating: Boolean,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
) {
    var options by remember { mutableStateOf(false) }
    val position = remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    fun px(value: Dp) = with(density) { value.roundToPx() }
    val inset = IntOffset(px(12.dp), px(84.dp))
    val isExpanded by rememberUpdatedState(expanded)
    LaunchedEffect(expanded) { if (!expanded) options = false }
    // Reserve the header, bottom navigation, and native scroll gutter at every window size.
    val maxX = px((width - DialSize - 60.dp).coerceAtLeast(0.dp)).toFloat()
    val maxY = px((height - 400.dp).coerceAtLeast(0.dp)).toFloat()
    val collapsedInset = IntOffset(px(68.dp), px(68.dp))
    val provider = remember(position, maxX, maxY, inset, collapsedInset) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                anchorBounds.topLeft + inset + (if (isExpanded) IntOffset.Zero else collapsedInset) + IntOffset(
                    position.value.x.coerceIn(0f, maxX).roundToInt(),
                    position.value.y.coerceIn(0f, maxY).roundToInt())
        }
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val drag = Modifier.onGloballyPositioned { coordinates = it }.pointerInput(maxX, maxY) {
        var startScreen = Offset.Zero
        var startPosition = Offset.Zero
        detectDragGestures(onDragStart = { local ->
            // Screen coordinates prevent the moving Popup from feeding its own movement back into the drag.
            startScreen = coordinates?.localToScreen(local) ?: local
            startPosition = Offset(position.value.x.coerceIn(0f, maxX), position.value.y.coerceIn(0f, maxY))
            options = false
        }) { change, _ ->
            change.consume()
            val screen = coordinates?.localToScreen(change.position) ?: change.position
            val next = startPosition + screen - startScreen
            position.value = Offset(next.x.coerceIn(0f, maxX), next.y.coerceIn(0f, maxY))
        }
    }
    val content: @Composable () -> Unit = {
        if (expanded) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ToolDial(settings, selectionCount, drag, onOptions = { options = !options }) {
                if (options) ToolOptionsPopup(settings) { options = false }
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                NotiTextButton(onClick = { options = false; onExpandedChange(false) },
                    modifier = Modifier.semantics { contentDescription = "Collapse tools" }) {
                    Icon(Icons.Outlined.ExpandLess, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Collapse", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Surface(Modifier.size(56.dp).then(drag), shape = CircleShape,
                color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp,
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Box {
                    NotiIconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "Expand tools"
                        stateDescription = if (settings.tool == Tool.Lasso) "$selectionCount selected. Drag to move tools"
                            else "${settings.tool.label}. Drag to move tools"
                    }) { Icon(settings.tool.icon(), null, Modifier.size(23.dp)) }
                    val slots = if (settings.tool == Tool.Highlighter) settings.highlighterSlots else settings.penSlots
                    if (settings.tool == Tool.Pen || settings.tool == Tool.Highlighter) Box(Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 7.dp).size(12.dp, 3.dp).background(Color(slots.currentColor), CircleShape))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (floating) Popup(popupPositionProvider = provider, properties = PopupProperties(
        focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false), content = content)
    else Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
        inset + (if (expanded) IntOffset.Zero else collapsedInset) + IntOffset(position.value.x.coerceIn(0f, maxX).roundToInt(), position.value.y.coerceIn(0f, maxY).roundToInt())
    }) { content() }
}

@Composable
private fun ToolDial(
    settings: SettingsStore, selectionCount: Int, drag: Modifier, onOptions: () -> Unit,
    options: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val highlighter = settings.tool == Tool.Highlighter ||
        (settings.tool != Tool.Pen && settings.lastDrawingTool == Tool.Highlighter)
    val slots = if (highlighter) settings.highlighterSlots else settings.penSlots
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    val update: (ToolSlots) -> Unit = { if (highlighter) settings.highlighterSlots = it else settings.penSlots = it }
    val activeAngle = -45f + Tool.entries.indexOf(settings.tool) * 45f
    Box(Modifier.size(DialSize)) {
        Canvas(Modifier.fillMaxSize().pointerInput(settings.tool, slots) {
            detectTapGestures { point ->
                val local = point - Offset(size.width / 2f, size.height / 2f)
                val radius = hypot(local.x, local.y) / (size.width / 192f)
                if (radius in 50f..94f) {
                    val angle = Math.toDegrees(atan2(local.y, local.x).toDouble()).toFloat()
                    val sector = ((floor((angle + 67.5f) / 45f).toInt() % 8) + 8) % 8
                    when {
                        sector < 4 -> settings.tool = Tool.entries[sector]
                        drawing && sector < 7 -> update(slots.copy(width = sector - 4))
                        drawing -> update(slots.copy(color = (slots.color + 1) % slots.colors.size))
                    }
                }
            }
        }) {
            val scale = size.width / 192f
            val c = Offset(DialCenter * scale, DialCenter * scale)
            drawCircle(colors.surface.copy(alpha = 0.98f), 94f * scale, c)
            // One continuous annulus. The selected sector uses ink-black rather than an accent pill.
            drawArc(colors.onSurface, activeAngle - 22.5f, 45f, false,
                Offset(24f * scale, 24f * scale), Size(144f * scale, 144f * scale),
                style = Stroke(44f * scale))
            val edge = colors.outlineVariant.copy(alpha = 0.65f)
            drawCircle(edge, 94f * scale, c, style = Stroke(0.65f * scale))
            drawCircle(edge, 50f * scale, c, style = Stroke(0.65f * scale))
            repeat(8) { index ->
                val a = Math.toRadians((-67.5 + index * 45))
                fun point(r: Float) = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * scale)
                drawLine(edge, point(50f), point(94f), 0.5f * scale)
            }
        }
        Tool.entries.forEachIndexed { index, tool ->
            val active = settings.tool == tool
            DialTarget(-45f + index * 45f, tool.label, selected = active,
                onClick = { settings.tool = tool }) {
                Icon(tool.icon(), null, Modifier.size(21.dp), tint = if (active) colors.surface else colors.onSurface)
            }
        }
        slots.widths.forEachIndexed { index, value ->
            DialTarget(135f + index * 45f, "Quick width $value", selected = drawing && slots.width == index,
                enabled = drawing, onClick = { update(slots.copy(width = index)) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { rotationZ = (135f + index * 45f) - 90f }) {
                    Box(Modifier.width(17.dp).height((1.5f + index * 1.5f).dp)
                        .background(colors.onSurface.copy(alpha = if (drawing) 1f else 0.3f), CircleShape))
                    Spacer(Modifier.height(4.dp))
                    Text(widthLabel(value), fontSize = 9.sp, lineHeight = 10.sp,
                        fontWeight = if (slots.width == index) FontWeight.Bold else FontWeight.Normal,
                        color = colors.onSurface.copy(alpha = if (drawing) 1f else 0.3f))
                }
            }
        }
        DialTarget(270f, "Next color", enabled = drawing, onClick = { update(slots.copy(color = (slots.color + 1) % slots.colors.size)) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                slots.colors.forEachIndexed { index, color ->
                    Box(Modifier.size(if (index == slots.color) 10.dp else 7.dp)
                        .background(Color(color), CircleShape)
                        .border(0.5.dp, colors.outline.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
        Text(if (drawing) "${widthLabel(slots.currentWidth)} px" else settings.tool.label,
            Modifier.align(Alignment.TopCenter).padding(top = 57.dp), color = colors.onSurface,
            fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.align(Alignment.Center).size(48.dp).then(drag)
            .clip(CircleShape).clickable(enabled = drawing, role = Role.Button, onClick = onOptions)
            .semantics {
                contentDescription = "Brush options"
                stateDescription = if (settings.tool == Tool.Lasso) "$selectionCount selected. Drag ink to move"
                    else "${settings.tool.label}. Drag to move tools"
            }, contentAlignment = Alignment.Center) {
            if (drawing) Box(Modifier.size(36.dp).background(Color(slots.currentColor), CircleShape)
                .border(0.5.dp, colors.outline.copy(alpha = 0.4f), CircleShape))
            else Icon(settings.tool.icon(), null, Modifier.size(28.dp), tint = colors.onSurface)
            options()
        }
        Text(if (drawing) if (highlighter) "40%" else "100%" else if (settings.tool == Tool.Lasso) "$selectionCount selected" else "Stroke",
            Modifier.align(Alignment.TopCenter).padding(top = 124.dp), color = colors.onSurfaceVariant,
            fontSize = 9.sp, lineHeight = 11.sp)
    }
}

/** Separate 48 dp targets keep the whole ring accessible without overlapping neighbours. */
@Composable
private fun BoxScope.DialTarget(
    angle: Float, label: String, selected: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, content: @Composable () -> Unit,
) {
    val radians = Math.toRadians(angle.toDouble())
    Box(Modifier.align(AbsoluteAlignment.TopLeft)
        .absoluteOffset((DialCenter + TargetRadius * cos(radians).toFloat() - 24f).dp,
            (DialCenter + TargetRadius * sin(radians).toFloat() - 24f).dp)
        .size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center) { content() }
}

private fun widthLabel(width: Float): String =
    if (width == width.toInt().toFloat()) width.toInt().toString() else String.format(Locale.ROOT, "%.1f", width)
