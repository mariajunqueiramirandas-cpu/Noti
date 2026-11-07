package app.noti.ui.editor

import app.noti.ui.common.NotiTextButton as TextButton
import app.noti.ui.common.NotiIconButton as IconButton
import app.noti.ui.common.NotiFilterChip as FilterChip

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.data.QuickFill
import app.noti.data.SettingsStore
import app.noti.lines.Overflow
import app.noti.lines.TextAlign
import app.noti.transcribe.HandwritingLanguages
import app.noti.transcribe.HandwritingReader
import app.noti.transcribe.InkLines
import app.noti.transcribe.SelectedStroke
import app.noti.ui.common.NotiButton
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The window flags of the Type panel: focusable, so the keyboard types into it, but not
 * touch-modal, so the pen still reaches the page around it (to tap lines in and out, or
 * loop somewhere else). Compose's `focusable = true` would make it touch-modal.
 */
private const val PanelFlags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

/** Points of type in a page unit-sized em, for the size label. */
private fun points(em: Float, mmPerUnit: Float) = em * mmPerUnit / 0.3528f

/**
 * Where the text for the lines is typed: a floating panel near the top (the keyboard takes
 * the bottom), in its own window above the ink, like the dial. The text appears on the lines
 * as it is typed; the panel holds the words, the fit and the style.
 */
@Composable
internal fun TypePanel(
    session: TypeSession,
    settings: SettingsStore,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onField: (Int) -> Unit,
    onUseFallback: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Popup(
        alignment = Alignment.TopCenter,
        offset = with(density) { IntOffset(0, 70.dp.roundToPx()) },
        onDismissRequest = onDone,
        properties = PopupProperties(flags = PanelFlags, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        GlassSurface(Modifier.padding(10.dp).widthIn(max = 620.dp).fillMaxWidth(), RoundedCornerShape(24.dp)) {
            TypePanelContent(session, settings, onDone, onCancel, onField, onUseFallback)
        }
    }
}

@Composable
internal fun TypePanelContent(
    session: TypeSession,
    settings: SettingsStore,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onField: (Int) -> Unit,
    onUseFallback: () -> Unit,
    /** Off in previews and screenshots, where there is no keyboard to summon. */
    autoFocus: Boolean = true,
) {
    val field = session.field
    val laid = session.laid
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val noLines = field.candidates.isEmpty()
    LaunchedEffect(session, session.current, noLines) { if (autoFocus && !noLines) runCatching { focus.requestFocus() } }

    fun insert(value: String) {
        val t = field.text
        val before = t.text.substring(0, t.selection.min)
        val sep = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
        val next = before + sep + value + t.text.substring(t.selection.max)
        val at = before.length + sep.length + value.length
        field.text = TextFieldValue(next, TextRange(at))
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header: where we are.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Keyboard, null, Modifier.size(20.dp), tint = colors.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val title = if (session.form) "Field ${session.current + 1} of ${session.fields.size}" +
                    (field.label?.let { " · $it" } ?: "") else if (field.existing != null) "Edit typed text" else "Type on lines"
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = field.lines.size
                val sub = when {
                    noLines -> "No lines found there."
                    count == 0 -> "Tap a line on the page to write on it."
                    else -> "$count ${if (count == 1) "line" else "lines"} · tap a line to leave it out" +
                        (if (session.form) " · Tab for the next field" else "")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (session.form) {
                IconButton(onClick = { onField(session.current - 1) }, enabled = session.current > 0 && !session.busy) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous field")
                }
                IconButton(onClick = { onField(session.current + 1) }, enabled = session.current < session.fields.size - 1 && !session.busy) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next field")
                }
            }
        }

        if (noLines) {
            Text("Loop some lines, or tap one. On blank paper, Noti can put a line along your loop instead.",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Close") }
                if (session.fallback != null) NotiButton(onClick = onUseFallback) { Text("Type here anyway") }
            }
            return@Column
        }

        OutlinedTextField(
            value = field.text,
            onValueChange = { field.text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Tab && session.form -> { onField(session.current + if (e.isShiftPressed) -1 else 1); true }
                    e.key == Key.Escape -> { onCancel(); true }
                    e.key == Key.Enter && e.isCtrlPressed -> { onDone(); true }
                    else -> false
                }
            },
            minLines = 2, maxLines = 5,
            placeholder = { Text(if (session.form && field.label != null) field.label else "Type here. It goes on the lines.") },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            enabled = !session.busy,
        )

        // What doesn't fit, and what to do about it.
        if (laid != null && !laid.fits && session.style.overflow == Overflow.Stop) {
            val n = laid.overflow.size
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp), tint = colors.error)
                Text("$n ${if (n == 1) "word doesn't" else "words don't"} fit", style = MaterialTheme.typography.bodyMedium,
                    color = colors.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { session.style = session.style.copy(overflow = Overflow.Shrink) }) { Text("Shrink to fit") }
                TextButton(onClick = { session.style = session.style.copy(overflow = Overflow.KeepGoing) }) { Text("Keep going") }
            }
        } else if (session.style.overflow != Overflow.Stop) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (session.style.overflow == Overflow.Shrink) "Shrinking to fit the lines." else "Continuing on new lines below.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { session.style = session.style.copy(overflow = Overflow.Stop) }) { Text("Undo") }
            }
        }

        // Style: size, alignment, weight; and the pen pad.
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val em = laid?.em ?: session.style.size
            FilterChip(selected = session.style.size == null, onClick = { session.style = session.style.copy(size = null) },
                label = { Text(if (session.style.size == null && em != null) "Fit · ${points(em, session.mmPerUnit).roundToInt()} pt" else "Fit") })
            IconButton(onClick = { em?.let { session.style = session.style.copy(size = it * 0.9f) } }, enabled = em != null) {
                Icon(Icons.Outlined.TextDecrease, "Smaller text")
            }
            if (session.style.size != null && em != null) Text("${points(em, session.mmPerUnit).roundToInt()} pt", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { em?.let { session.style = session.style.copy(size = it / 0.9f) } }, enabled = em != null) {
                Icon(Icons.Outlined.TextIncrease, "Bigger text")
            }
            FilterChip(selected = session.style.align == TextAlign.Center, onClick = {
                session.style = session.style.copy(align = if (session.style.align == TextAlign.Center) TextAlign.Left else TextAlign.Center)
            }, label = { Text("Centre") }, leadingIcon = { Icon(Icons.Outlined.FormatAlignCenter, null, Modifier.size(18.dp)) })
            FilterChip(selected = session.style.bold, onClick = { session.style = session.style.copy(bold = !session.style.bold) },
                label = { Text("Bold") }, leadingIcon = { Icon(Icons.Outlined.FormatBold, null, Modifier.size(18.dp)) })
            FilterChip(selected = session.penPad, onClick = { session.penPad = !session.penPad },
                label = { Text("Write") }, leadingIcon = { Icon(Icons.Outlined.Draw, null, Modifier.size(18.dp)) })
        }

        QuickFillRow(settings.quickFills, field.label, ::insert)

        if (session.penPad) HandwritingPad(settings.handwritingLanguage, onText = ::insert)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !session.busy) { Text("Cancel") }
            NotiButton(onClick = onDone, enabled = !session.busy) { Text(if (session.form) "Done" else "Place") }
        }
    }
}

/** Today's date and the saved quick fills; the ones whose label matches the field's come first. */
@Composable
private fun QuickFillRow(fills: List<QuickFill>, label: String?, onInsert: (String) -> Unit) {
    val today = remember { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()) }
    val wanted = label?.lowercase().orEmpty()
    val chips = buildList {
        add(QuickFill("Date", today))
        addAll(fills.filter { it.value.isNotBlank() })
    }.sortedByDescending { wanted.isNotEmpty() && (wanted.contains(it.label.lowercase()) || it.label.lowercase().contains(wanted)) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in chips) AssistChip(onClick = { onInsert(c.value) }, label = { Text(c.value, maxLines = 1) },
            leadingIcon = { if (c.label == "Date" && c.value == today) Icon(Icons.Outlined.Today, null, Modifier.size(16.dp)) },
            modifier = Modifier.semantics { contentDescription = "Insert ${c.label}: ${c.value}" })
    }
}

/**
 * Write with the pen here instead of typing: what you write is read on the tablet (the
 * Transcribe tool's recognizer) and goes into the text, so it lands neatly on the lines.
 */
@Composable
private fun HandwritingPad(languageTag: String, onText: (String) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastStroke by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    fun read() {
        if (strokes.isEmpty()) return
        val pen = floatArrayOf(0f, 0f, 0f, 1f)
        val ink = strokes.mapIndexed { i, s -> SelectedStroke(i, 0, pen, FloatArray(s.size * 2) { k -> if (k % 2 == 0) s[k / 2].x else s[k / 2].y }) }
        strokes.clear()
        scope.launch {
            val reader = try { HandwritingReader(HandwritingLanguages.resolve(languageTag)) } catch (e: Exception) {
                status = "Handwriting recognition isn't available on this device."; return@launch
            }
            try {
                if (!reader.isDownloaded()) { status = "Getting handwriting ready (once, about 20 MB)…"; reader.download() }
                status = "Reading…"
                val text = reader.read(InkLines.group(ink)).joinToString(" ").trim()
                status = null
                if (text.isNotEmpty()) onText(text) else status = "Couldn't read that. Try again a little bigger."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "Couldn't read that handwriting. Check your connection the first time, or the language in Settings."
            } finally { reader.close() }
        }
    }
    // Read a moment after the pen stops.
    LaunchedEffect(lastStroke) {
        if (lastStroke == 0L) return@LaunchedEffect
        delay(1200)
        read()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(110.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Handwriting pad. Write here and it becomes text." }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val points = mutableListOf(down.position)
                    live = points.toList()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        points += change.position
                        live = points.toList()
                        change.consume()
                    }
                    if (points.size > 1) strokes += points.toList()
                    live = emptyList()
                    lastStroke = System.nanoTime()
                }
            }) {
            Canvas(Modifier.matchParentSize()) {
                for (s in strokes + listOf(live)) {
                    if (s.size < 2) continue
                    val path = Path().apply { moveTo(s[0].x, s[0].y); for (p in s.drop(1)) lineTo(p.x, p.y) }
                    drawPath(path, Color(0xFF1E1F24), style = Stroke(2.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // A baseline to write on.
                drawLine(Color(0x553A5A8C), Offset(12f * density, size.height * 0.72f), Offset(size.width - 12f * density, size.height * 0.72f), 1f * density)
            }
            if (strokes.isEmpty() && live.isEmpty()) Text("Write here", Modifier.align(Alignment.Center),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status ?: "Read on the tablet, then added at the cursor.", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
            TextButton(onClick = ::read, enabled = strokes.isNotEmpty()) { Text("Read now") }
        }
    }
}
