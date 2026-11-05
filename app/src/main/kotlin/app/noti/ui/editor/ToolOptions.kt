package app.noti.ui.editor

import app.noti.ui.common.NotiDropdownMenu as DropdownMenu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.noti.data.SettingsStore
import app.noti.data.Tool
import app.noti.data.ToolSlots
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val PenColors = listOf(
    0xFF1E1F24, 0xFF5E616B, 0xFF2D5BD3, 0xFF1B8A9E, 0xFF2E8B57, 0xFF7A4BD1,
    0xFFD23B3B, 0xFFE8742A, 0xFFC99A06, 0xFFB5427F, 0xFF8B5A2B, 0xFFFFFFFF,
)
private val HighlighterColors = listOf(
    0xFFFFE14D, 0xFFFFB347, 0xFFFF9ACB, 0xFFC9A0FF, 0xFF8FC9FF, 0xFF8BE38B,
)

/**
 * Options of the current drawing tool, opened from the radial selector. They edit the
 * selected color and width slots in place.
 *
 * The popup is its own window, added after the ink window, so it sits above it and gets
 * the taps inside its bounds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolOptionsPopup(settings: SettingsStore, onDismiss: () -> Unit) {
    val highlighter = settings.tool == Tool.Highlighter
    val slots = if (highlighter) settings.highlighterSlots else settings.penSlots
    val update: (ToolSlots) -> Unit = { if (highlighter) settings.highlighterSlots = it else settings.penSlots = it }
    val presets = if (highlighter) HighlighterColors else PenColors
    val widthRange = if (highlighter) 8f..60f else 1f..30f
    var custom by remember { mutableStateOf(slots.currentColor !in presets) }
    // Material's anchored menu stays inside the visible window and scrolls when
    // font scaling or a short landscape display leaves less vertical room.
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, containerColor = Color.Transparent, shadowElevation = 0.dp) {
        GlassSurface(modifier = Modifier.width(320.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(settings.tool.label, style = MaterialTheme.typography.titleSmall)
                Row {
                    slots.colors.forEachIndexed { index, color ->
                        PresetSlot("Color ${index + 1}", index == slots.color,
                            onClick = { update(slots.copy(color = index)) }) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(Color(color))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape))
                        }
                    }
                    slots.widths.forEachIndexed { index, width ->
                        PresetSlot("Width $width", index == slots.width,
                            onClick = { update(slots.copy(width = index)) }) {
                            Box(Modifier.width(18.dp).height((2 + 5 * width / slots.widths.max()).dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { c ->
                        ColorDot(Color(c), selected = !custom && c == slots.currentColor) {
                            custom = false
                            update(slots.withColor(c))
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (custom) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { custom = !custom },
                    ) {
                        Icon(Icons.Outlined.Palette, contentDescription = "Custom color")
                    }
                }
                if (custom) {
                    CustomColor(Color(slots.currentColor)) { update(slots.withColor(it.toArgb().toLong() and 0xFFFFFFFF)) }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Width", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                    Slider(
                        value = slots.currentWidth.coerceIn(widthRange),
                        onValueChange = { update(slots.withWidth((it * 2).roundToInt() / 2f)) },
                        valueRange = widthRange,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.1f".format(slots.currentWidth),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(40.dp).padding(start = 8.dp),
                    )
                }
                StrokePreview(
                    color = Color(slots.currentColor).copy(alpha = if (highlighter) 0.4f else 1f),
                    widthPx = slots.currentWidth,
                )
                if (!highlighter) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pressure sensitivity", modifier = Modifier.weight(1f))
                        Switch(checked = settings.pressureOn, onCheckedChange = { settings.pressureOn = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSlot(label: String, active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
        .clickable(onClick = onClick).semantics { contentDescription = label; selected = active },
        contentAlignment = Alignment.Center) { content() }
}

private fun ToolSlots.withColor(c: Long) = copy(colors = colors.toMutableList().also { it[color] = c })
private fun ToolSlots.withWidth(w: Float) = copy(widths = widths.toMutableList().also { it[width] = w })

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            val light = (color.red * 0.3f + color.green * 0.59f + color.blue * 0.11f) > 0.6f
            Icon(Icons.Outlined.Check, null, tint = if (light) Color.Black else Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Hue, saturation and brightness sliders. HSV is kept locally (not re-derived from the
 * color), so the hue survives while saturation or brightness is at zero.
 */
@Composable
private fun CustomColor(color: Color, onChange: (Color) -> Unit) {
    var hsv by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }.toList()) }
    fun emit(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2]) {
        hsv = listOf(h, s, v)
        onChange(Color.hsv(h, s, v))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HsvRow("Hue", Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) {
            Slider(value = hsv[0], onValueChange = { emit(h = it) }, valueRange = 0f..359f)
        }
        HsvRow("Color", Brush.horizontalGradient(listOf(Color.hsv(hsv[0], 0f, hsv[2]), Color.hsv(hsv[0], 1f, hsv[2])))) {
            Slider(value = hsv[1], onValueChange = { emit(s = it) })
        }
        HsvRow("Light", Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hsv[0], hsv[1], 1f)))) {
            Slider(value = hsv[2], onValueChange = { emit(v = it) })
        }
    }
}

@Composable
private fun HsvRow(label: String, track: Brush, slider: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(track))
            slider()
        }
    }
}

/** A sample squiggle at the real on-screen width (the engine's width is in pixels). */
@Composable
private fun StrokePreview(color: Color, widthPx: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color.White),
    ) {
        val path = Path()
        val pad = 24f + widthPx
        val w = size.width - 2 * pad
        val mid = size.height / 2
        val amp = size.height / 4
        for (i in 0..64) {
            val t = i / 64f
            val p = Offset(pad + t * w, mid + amp * sin(t * 2 * PI).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = widthPx, cap = StrokeCap.Round))
    }
}
