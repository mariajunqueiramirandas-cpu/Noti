package app.noti.ui.editor

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.data.ToolSlots
import app.noti.data.DialColors
import app.noti.data.DialSegments
import app.noti.data.DialWidths
import app.noti.data.applyDialSegment
import app.noti.ui.theme.Noti
import app.noti.ui.theme.NotiBrand
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Geometry, in dp. Eleven equal segments (DialSegments) in three groups, read clockwise
 * from the upper left:
 *   tools   (pen, highlighter, eraser, lasso, transcribe) across the top, centred on -90°
 *   colors  (the three quick color slots) down the right
 *   widths  (the three quick widths) back up the left
 * Wider gaps separate the groups. The hub is a small page that previews the brush.
 */
private val DialSize = 208.dp
internal const val DialCenter = 104f
internal const val RingInner = 52f
internal const val RingOuter = 100f
private const val TargetRadius = 78f
/** Eleven segments leave room for 30 dp targets; the ring itself takes taps anywhere by angle. */
private const val TargetSize = 30f
private const val HubSize = 84f
private val SegmentSweep = 360f / DialSegments
/** The tools are centred on the top of the dial. */
private val FirstSegment = -90f - (DialColors - 1) * SegmentSweep / 2f
private const val CollapsedSize = 56f
/** The collapse knob below the docked dial, and the lock pip below the summoned one. */
internal const val LockSize = 44f
/** Transparent room around floating chrome so its shadow isn't cut off by the popup window. */
internal const val Halo = 10f

private fun segmentAngle(index: Number) = FirstSegment + index.toFloat() * SegmentSweep

/** Segment under an angle in degrees (screen coordinates, 0° east, clockwise). */
internal fun segmentAt(angle: Float): Int =
    Math.floorMod(floor((angle - FirstSegment + SegmentSweep / 2f) / SegmentSweep).toInt(), DialSegments)

/**
 * The segmented dial, in its own window above the native ink input panel. [docked]
 * keeps it on the page; the pen can also summon it under the nib ([dial]).
 */
@Composable
internal fun BoxScope.RadialToolSelector(
    settings: SettingsStore, selectionCount: Int, width: Dp, height: Dp, floating: Boolean,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    dial: PenDialState = remember { PenDialState() }, docked: Boolean = true,
) {
    val density = LocalDensity.current
    // The dial gets pen positions in window pixels; it places itself in this area.
    Spacer(Modifier.matchParentSize().onGloballyPositioned { dial.origin = it.positionInWindow() })
    SideEffect { dial.area = Size(width.value, height.value); dial.density = density.density }
    // Kept here so the docked dial returns to where it was after the pen summons it.
    val position = remember { mutableStateOf(Offset.Zero) }
    if (dial.phase != PenDialState.Phase.Hidden) SummonedDial(settings, selectionCount, dial, floating)
    else if (docked) DockedDial(settings, selectionCount, width, height, floating, expanded, onExpandedChange, position)
}

@Composable
private fun BoxScope.DockedDial(
    settings: SettingsStore, selectionCount: Int, width: Dp, height: Dp, floating: Boolean,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, position: MutableState<Offset>,
) {
    var options by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    fun px(value: Dp) = with(density) { value.roundToPx() }
    val inset = IntOffset(px((12f - Halo).dp), px((84f - Halo).dp))
    val isExpanded by rememberUpdatedState(expanded)
    LaunchedEffect(expanded) { if (!expanded) options = false }
    // Reserve the header, bottom dock, and native scroll gutter at every window size.
    val maxX = px((width - DialSize - 60.dp).coerceAtLeast(0.dp)).toFloat()
    val maxY = px((height - 400.dp).coerceAtLeast(0.dp)).toFloat()
    // The collapsed button keeps the dial's centre.
    val collapsedInset = IntOffset(px((DialCenter - CollapsedSize / 2).dp), px((DialCenter - CollapsedSize / 2).dp))
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
        if (expanded) Column(Modifier.padding(Halo.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ToolDial(settings, selectionCount, drag, onOptions = { options = !options }) {
                if (options) ToolOptionsPopup(settings) { options = false }
            }
            Spacer(Modifier.height(4.dp))
            CollapseKnob { options = false; onExpandedChange(false) }
        } else Box(Modifier.padding(Halo.dp)) { CollapsedTool(settings, selectionCount, drag) { onExpandedChange(true) } }
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
    hovered: Int? = null, interactive: Boolean = true,
    options: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    val highlighter = settings.tool == Tool.Highlighter ||
        (settings.tool != Tool.Pen && settings.lastDrawingTool == Tool.Highlighter)
    val slots = if (highlighter) settings.highlighterSlots else settings.penSlots
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    val toolIndex = Tool.entries.indexOf(settings.tool)
    // The highlight glides between tools, so the change reads as one motion.
    val active by animateFloatAsState(toolIndex.toFloat(), tween(220), label = "active tool")
    val presetAlpha = if (drawing) 1f else 0.35f
    val dark = colors.surface.luminance() < 0.5f

    Box(Modifier.size(DialSize).shadow(10.dp, CircleShape, ambientColor = extras.shadow, spotColor = extras.shadow)
        .clip(CircleShape).background(colors.surface)) {
        Canvas(Modifier.fillMaxSize().then(if (!interactive) Modifier else Modifier.pointerInput(Unit) {
            detectTapGestures { point ->
                val unit = size.width / (DialCenter * 2f)
                val local = point - Offset(size.width / 2f, size.height / 2f)
                val radius = hypot(local.x, local.y) / unit
                if (radius !in (RingInner - 2f)..(RingOuter + 4f)) return@detectTapGestures
                settings.applyDialSegment(segmentAt(Math.toDegrees(atan2(local.y, local.x).toDouble()).toFloat()))
            }
        })) {
            val unit = size.width / (DialCenter * 2f)
            val c = Offset(size.width / 2f, size.height / 2f)
            val rest = if (dark) colors.surfaceContainerHigh else colors.surfaceContainer
            val chosen = if (dark) Color(0xFF45424C) else colors.surfaceContainerHighest
            repeat(DialSegments) { i ->
                val fill = when (i) {
                    in DialColors until DialWidths -> if (drawing && slots.color == i - DialColors) chosen else rest
                    in DialWidths until DialSegments -> if (drawing && slots.width == i - DialWidths) chosen else rest
                    else -> rest
                }
                drawSegment(c, unit, segmentAngle(i), if (i >= DialColors && !drawing) fill.copy(alpha = 0.5f) else fill)
            }
            if (hovered != null) drawSegment(c, unit, segmentAngle(hovered), colors.primary.copy(alpha = if (dark) 0.42f else 0.24f))
            drawSegment(c, unit, segmentAngle(active), colors.primary)
        }

        Tool.entries.forEachIndexed { index, tool ->
            val selected = settings.tool == tool
            DialTarget(segmentAngle(index), tool.label, selected = selected, enabled = interactive, onClick = { settings.tool = tool }) {
                Icon(tool.icon(), null, Modifier.size(22.dp), tint = if (selected) colors.onPrimary else colors.onSurface)
            }
        }
        slots.colors.forEachIndexed { index, color ->
            val selected = drawing && slots.color == index
            DialTarget(segmentAngle(DialColors + index), "Quick color ${index + 1}", selected = selected, enabled = interactive,
                onClick = { settings.applyDialSegment(DialColors + index) }) {
                Box(Modifier.size(if (selected) 24.dp else 18.dp)
                    .then(if (selected) Modifier.border(2.dp, colors.onSurface.copy(alpha = presetAlpha), CircleShape).padding(4.dp) else Modifier)
                    .clip(CircleShape).background(Color(color).copy(alpha = presetAlpha))
                    .border(0.75.dp, colors.outline.copy(alpha = 0.45f * presetAlpha), CircleShape))
            }
        }
        slots.widths.forEachIndexed { index, value ->
            val selected = drawing && slots.width == index
            DialTarget(segmentAngle(DialWidths + index), "Quick width $value", selected = selected, enabled = interactive,
                onClick = { settings.applyDialSegment(DialWidths + index) }) {
                val tint = if (selected) colors.onSurface else colors.onSurfaceVariant
                Box(Modifier.width(18.dp).height((1.5f + index * 2f).dp)
                    .background(tint.copy(alpha = presetAlpha), CircleShape))
            }
        }

        Box(Modifier.align(Alignment.Center).size(HubSize.dp).then(drag)
            .clip(CircleShape).clickable(enabled = drawing && interactive, role = Role.Button, onClick = onOptions)
            .semantics {
                contentDescription = "Brush options"
                stateDescription = when (settings.tool) {
                    Tool.Lasso -> "$selectionCount selected. Drag ink to move"
                    Tool.Transcribe -> "Transcribe. Draw a loop around handwriting to turn it into text"
                    else -> "${settings.tool.label}. Drag to move tools"
                }
            }, contentAlignment = Alignment.Center) {
            // The hub previews what lifting the nib would give.
            when (hovered) {
                in 0 until DialColors -> {
                    val tool = Tool.entries[hovered!!]
                    val hubHighlighter = tool == Tool.Highlighter || (tool != Tool.Pen && highlighter)
                    BrushHub(tool, if (hubHighlighter) settings.highlighterSlots else settings.penSlots, hubHighlighter,
                        tool == Tool.Pen || tool == Tool.Highlighter, selectionCount)
                }
                in DialColors until DialWidths -> BrushHub(settings.lastDrawingTool, slots.copy(color = hovered!! - DialColors), highlighter, true, selectionCount)
                in DialWidths until DialSegments -> BrushHub(settings.lastDrawingTool, slots.copy(width = hovered!! - DialWidths), highlighter, true, selectionCount)
                else -> BrushHub(settings.tool, slots, highlighter, drawing, selectionCount)
            }
            options()
        }
    }
}

/** The hub is a small page: it shows the stroke you'll get, or the tool you hold. */
@Composable
private fun BrushHub(tool: Tool, slots: ToolSlots, highlighter: Boolean, drawing: Boolean, selectionCount: Int) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val paper = if (dark) Color(0xFFDCD8E2) else Color.White
    Box(Modifier.fillMaxSize().clip(CircleShape).background(paper)
        .border(0.75.dp, if (dark) Color.Transparent else colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center) {
        if (drawing) {
            val maxWidth = slots.widths.max().coerceAtLeast(1f)
            val stroke = if (highlighter) 6f + 8f * slots.currentWidth / maxWidth else 1.5f + 6.5f * slots.currentWidth / maxWidth
            Canvas(Modifier.size(52.dp, 30.dp)) {
                val path = Path()
                val w = size.width - stroke.dp.toPx()
                val start = stroke.dp.toPx() / 2
                for (i in 0..40) {
                    val t = i / 40f
                    val p = Offset(start + t * w, size.height / 2 + size.height * 0.28f * sin(t * 2 * PI).toFloat())
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, Color(slots.currentColor).copy(alpha = if (highlighter) 0.55f else 1f),
                    style = Stroke(stroke.dp.toPx(), cap = StrokeCap.Round))
            }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(tool.icon(), null, Modifier.size(26.dp), tint = NotiBrand.Ink)
            if (tool == Tool.Lasso && selectionCount > 0) Text("$selectionCount", style = MaterialTheme.typography.labelMedium,
                color = NotiBrand.Ink, textAlign = TextAlign.Center)
        }
    }
}

/**
 * One rounded annular segment: true arcs on the inner and outer edges, softly rounded
 * corners, and gaps that keep a constant width from the inner to the outer edge. Gaps are
 * wider where one group meets the next.
 */
private fun DrawScope.drawSegment(center: Offset, unit: Float, centerAngle: Float, color: Color, groupGaps: Boolean = true) {
    val index = Math.floorMod(((centerAngle - FirstSegment) / SegmentSweep).roundToInt(), DialSegments)
    fun gapBefore(i: Int) = if (groupGaps && (i == 0 || i == DialColors || i == DialWidths)) 8f else 3f
    drawPath(segmentPath(center, unit, centerAngle, gapBefore(index) * unit, gapBefore((index + 1) % DialSegments) * unit), color)
}

private fun segmentPath(center: Offset, unit: Float, centerAngle: Float, startGap: Float, endGap: Float): Path {
    val inner = RingInner * unit
    val outer = RingOuter * unit
    val corner = 7f * unit
    fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()
    fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()
    fun at(radius: Float, angle: Float) = center + Offset(cos(rad(angle)), sin(rad(angle))) * radius
    // Edge angles on each circle, shifted so the gap is a constant width.
    val start = centerAngle - SegmentSweep / 2f
    val end = centerAngle + SegmentSweep / 2f
    val outerStart = start + deg(startGap / 2f / outer)
    val outerEnd = end - deg(endGap / 2f / outer)
    val innerStart = start + deg(startGap / 2f / inner)
    val innerEnd = end - deg(endGap / 2f / inner)
    // Corner insets, as angles along each arc and as distances along each edge.
    val outerTurn = deg(corner / outer)
    val innerTurn = deg(corner / inner)
    fun along(from: Offset, to: Offset, distance: Float): Offset {
        val d = to - from
        return from + d * (distance / d.getDistance())
    }
    val oS = at(outer, outerStart); val oE = at(outer, outerEnd)
    val iS = at(inner, innerStart); val iE = at(inner, innerEnd)
    return Path().apply {
        val first = along(oS, iS, corner)
        moveTo(first.x, first.y)
        at(outer, outerStart + outerTurn).let { quadraticTo(oS.x, oS.y, it.x, it.y) }
        arcTo(Rect(center, outer), outerStart + outerTurn, outerEnd - outerStart - 2 * outerTurn, false)
        along(oE, iE, corner).let { quadraticTo(oE.x, oE.y, it.x, it.y) }
        along(iE, oE, corner).let { lineTo(it.x, it.y) }
        at(inner, innerEnd - innerTurn).let { quadraticTo(iE.x, iE.y, it.x, it.y) }
        arcTo(Rect(center, inner), innerEnd - innerTurn, -(innerEnd - innerStart - 2 * innerTurn), false)
        along(iS, oS, corner).let { quadraticTo(iS.x, iS.y, it.x, it.y) }
        close()
    }
}

/** Separate targets keep every segment reachable without overlapping its neighbours. */
@Composable
private fun BoxScope.DialTarget(
    angle: Float, label: String, selected: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, content: @Composable () -> Unit,
) {
    val radians = Math.toRadians(angle.toDouble())
    Box(Modifier.align(AbsoluteAlignment.TopLeft)
        .absoluteOffset((DialCenter + TargetRadius * cos(radians).toFloat() - TargetSize / 2).dp,
            (DialCenter + TargetRadius * sin(radians).toFloat() - TargetSize / 2).dp)
        .size(TargetSize.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CollapseKnob(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    Box(Modifier.size(44.dp).padding(4.dp).shadow(4.dp, CircleShape, ambientColor = extras.shadow, spotColor = extras.shadow)
        .clip(CircleShape).background(colors.surface).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Collapse tools" }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.ExpandLess, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

/** The dial folded into one button: the tool you hold, with its color as a small bead. */
@Composable
private fun CollapsedTool(settings: SettingsStore, selectionCount: Int, drag: Modifier, onExpand: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    val drawing = settings.tool == Tool.Pen || settings.tool == Tool.Highlighter
    val slots = if (settings.tool == Tool.Highlighter) settings.highlighterSlots else settings.penSlots
    Box(Modifier.size(CollapsedSize.dp).then(drag)
        .shadow(8.dp, CircleShape, ambientColor = extras.shadow, spotColor = extras.shadow)
        .clip(CircleShape).background(colors.surface)
        .clickable(role = Role.Button, onClick = onExpand)
        .semantics {
            contentDescription = "Expand tools"
            stateDescription = if (settings.tool == Tool.Lasso) "$selectionCount selected. Drag to move tools"
                else "${settings.tool.label}. Drag to move tools"
        }, contentAlignment = Alignment.Center) {
        Icon(settings.tool.icon(), null, Modifier.size(24.dp), tint = colors.onSurface)
        if (drawing) Box(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp).size(12.dp)
            .background(colors.surface, CircleShape).padding(2.dp).background(Color(slots.currentColor), CircleShape))
    }
}

/**
 * The dial the pen summoned (see [PenDialState]): centred on the nib while picking, its
 * label naming what lifting would choose. Locked, it takes taps like the docked dial.
 */
@Composable
private fun BoxScope.SummonedDial(settings: SettingsStore, selectionCount: Int, dial: PenDialState, floating: Boolean) {
    val locked = dial.phase == PenDialState.Phase.Locked
    val hovered = dial.hovered
    var options by remember { mutableStateOf(false) }
    LaunchedEffect(locked) { if (!locked) options = false }
    val view = LocalView.current
    LaunchedEffect(hovered) { if (hovered != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    val density = LocalDensity.current
    val center = dial.center
    val topLeft = with(density) {
        IntOffset((center.x - Halo - DialCenter).dp.roundToPx(), (center.y - Halo - LabelSlot - DialCenter).dp.roundToPx())
    }
    val appear = remember(center) { Animatable(0.85f) }
    LaunchedEffect(appear) { appear.animateTo(1f, tween(120)) }
    val height = Halo * 2 + LabelSlot + DialCenter * 2 + 4f + LockSize
    val highlighter = settings.tool == Tool.Highlighter || (settings.tool != Tool.Pen && settings.lastDrawingTool == Tool.Highlighter)
    val label = dialLabel(hovered, if (highlighter) settings.highlighterSlots else settings.penSlots)
        ?: if (locked) "Tap outside to close" else null
    val content: @Composable () -> Unit = {
        Column(Modifier.graphicsLayer {
            scaleX = appear.value; scaleY = appear.value; alpha = ((appear.value - 0.85f) / 0.15f).coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(0.5f, (Halo + LabelSlot + DialCenter) / height)
        }.padding(Halo.dp).width(DialSize), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height((LabelSlot - 6f).dp), contentAlignment = Alignment.Center) { label?.let { DialLabel(it) } }
            Spacer(Modifier.height(6.dp))
            ToolDial(settings, selectionCount, Modifier, onOptions = { options = !options },
                hovered = (hovered as? DialPick.Segment)?.index, interactive = locked) {
                if (options) ToolOptionsPopup(settings) { options = false }
            }
            Spacer(Modifier.height(4.dp))
            LockPip(locked, hovered == DialPick.Lock) { dial.dismiss() }
        }
    }
    if (floating) {
        val provider = remember(topLeft) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                    layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = anchorBounds.topLeft + topLeft
            }
        }
        Popup(popupPositionProvider = provider, properties = PopupProperties(
            focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false), content = content)
    } else Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { topLeft }) { content() }
}

/** What lifting the nib on [pick] would do, in a word or two. */
internal fun dialLabel(pick: DialPick?, slots: ToolSlots): String? = when (pick) {
    null -> null
    DialPick.Lock -> "Keep open"
    DialPick.Cancel -> "Cancel"
    is DialPick.Segment -> when (val i = pick.index) {
        in 0 until DialColors -> Tool.entries[i].label
        in DialColors until DialWidths -> "Color ${i - DialColors + 1}"
        else -> slots.widths[i - DialWidths].let { "Width ${if (it % 1f == 0f) it.toInt().toString() else it.toString()}" }
    }
}

@Composable
private fun DialLabel(text: String) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    Text(text, Modifier.shadow(4.dp, CircleShape, ambientColor = extras.shadow, spotColor = extras.shadow)
        .clip(CircleShape).background(colors.surface).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1)
}

/** Lift the nib here to keep the dial open; tap it again to close. */
@Composable
private fun LockPip(locked: Boolean, hovered: Boolean, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val extras = Noti.extras
    val lit = locked || hovered
    Box(Modifier.size(LockSize.dp).padding(4.dp).shadow(4.dp, CircleShape, ambientColor = extras.shadow, spotColor = extras.shadow)
        .clip(CircleShape).background(if (lit) colors.primary else colors.surface)
        .clickable(enabled = locked, role = Role.Button, onClick = onClose)
        .semantics { contentDescription = if (locked) "Close tools" else "Keep tools open"; selected = locked },
        contentAlignment = Alignment.Center) {
        Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null, Modifier.size(18.dp),
            tint = if (lit) colors.onPrimary else colors.onSurfaceVariant)
    }
}
