package app.noti.ui.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.pdf.PagePlacement
import app.noti.study.CardFace
import app.noti.study.DeckRequest
import app.noti.study.ItemKind
import app.noti.study.Rating
import app.noti.study.StudyItem
import app.noti.ui.brand.DotiMascot
import app.noti.ui.theme.Noti
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Where things are on screen, for printing at the spot the sheet shows. */
@Stable
internal class SheetGeometry {
    /** The editor's canvas (the ink's coordinate space). */
    var editor: LayoutCoordinates? = null
    var window: LayoutCoordinates? = null
    var face: LayoutCoordinates? = null

    /** The face's top-left in editor px. */
    fun faceInEditor(): Offset? {
        val e = editor?.takeIf { it.isAttached } ?: return null
        val f = face?.takeIf { it.isAttached } ?: return null
        return f.positionOnScreen() - e.positionOnScreen()
    }

    fun sheetInEditor(): Offset? {
        val e = editor?.takeIf { it.isAttached } ?: return null
        val w = window?.takeIf { it.isAttached } ?: return null
        return w.positionOnScreen() - e.positionOnScreen()
    }

    /** Where the face sits inside the sheet window. */
    fun faceInSheet(): IntOffset {
        val w = window?.takeIf { it.isAttached } ?: return IntOffset.Zero
        val f = face?.takeIf { it.isAttached } ?: return IntOffset.Zero
        val d = f.positionOnScreen() - w.positionOnScreen()
        return IntOffset(d.x.roundToInt(), d.y.roundToInt())
    }

    fun sheetWidthPx(): Int = window?.takeIf { it.isAttached }?.size?.width ?: 0
}

/** Shadow room around the floating sheet, inside its popup. */
private val Halo = 10.dp

/**
 * The floating study sheet: the current item over the board, which stays live underneath.
 * Drag its header to move it. It never covers ink with a writing area: you work on the
 * board and loop your answer to check it. [serial] is the engine's loop count.
 */
@Composable
internal fun StudySheet(
    study: StudyController,
    geometry: SheetGeometry,
    fonts: CardFace.Fonts,
    placement: () -> PagePlacement?,
    serial: Long,
    maxWidth: Dp,
    maxHeight: Dp,
    onSettings: () -> Unit,
    /** In a sub-panel window above the ink (the app); inline for screenshot tests. */
    floating: Boolean = true,
) {
    if (!study.open) return
    val density = LocalDensity.current
    val compact = maxWidth < 600.dp
    val sheetWidth = if (compact) maxWidth - 16.dp else (maxWidth * 0.42f).coerceIn(340.dp, 440.dp)
    val maxSheetHeight = if (compact) maxHeight * 0.58f else (maxHeight - 140.dp).coerceAtLeast(260.dp)
    val boundsW = with(density) { maxWidth.roundToPx() }
    val boundsH = with(density) { maxHeight.roundToPx() }
    val windowW = with(density) { (sheetWidth + Halo * 2).roundToPx() }
    var windowH by remember { mutableIntStateOf(with(density) { 200.dp.roundToPx() }) }
    val default = if (compact) IntOffset((boundsW - windowW) / 2, boundsH - windowH - with(density) { 4.dp.roundToPx() })
        else IntOffset(boundsW - windowW - with(density) { 6.dp.roundToPx() }, with(density) { 66.dp.roundToPx() })
    val offset = if (compact) default else (study.offset ?: default).let {
        IntOffset(it.x.coerceIn(-with(density) { Halo.roundToPx() }, (boundsW - windowW).coerceAtLeast(0)),
            it.y.coerceIn(0, (boundsH - with(density) { 56.dp.roundToPx() }).coerceAtLeast(0)))
    }

    val content: @Composable () -> Unit = {
        Box(Modifier.padding(Halo).onGloballyPositioned { geometry.window = it; windowH = it.size.height + with(density) { (Halo * 2).roundToPx() } }) {
            val drag = Modifier.pointerInput(compact) {
                if (!compact) detectDragGestures { change, amount ->
                    change.consume()
                    val o = study.offset ?: offset
                    study.offset = IntOffset(o.x + amount.x.roundToInt(), o.y + amount.y.roundToInt())
                }
            }
            if (study.collapsed) CollapsedSheet(study, drag) else ExpandedSheet(study, geometry, fonts, placement, serial,
                sheetWidth, maxSheetHeight, drag, onSettings)
        }
    }
    // TYPE_APPLICATION_SUB_PANEL (Compose Popup) sorts above the ink's panel, so taps reach the sheet.
    if (floating) Popup(alignment = Alignment.TopStart, offset = offset,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = false),
        content = content)
    else Box(Modifier.offset { offset }) { content() }
}

@Composable
private fun SheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.shadow(10.dp, shape, ambientColor = Noti.extras.shadow, spotColor = Noti.extras.shadow)
        .clip(shape).background(colors.surface).border(0.5.dp, colors.outlineVariant.copy(alpha = 0.6f), shape), content = content)
}

@Composable
private fun CollapsedSheet(study: StudyController, drag: Modifier) {
    val colors = MaterialTheme.colorScheme
    SheetSurface(drag.semantics { contentDescription = "Study sheet, collapsed" }) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DragIndicator, null, Modifier.padding(start = 6.dp).size(18.dp), tint = colors.onSurfaceVariant)
            Text(positionLabel(study), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            SmallIcon(Icons.Outlined.OpenInFull, "Expand the study sheet") { study.collapsed = false }
            SmallIcon(Icons.Outlined.Close, "Close the study sheet") { study.hide() }
        }
    }
}

private fun positionLabel(study: StudyController): String {
    val total = study.order.size
    return if (total == 0) "Study" else "Q ${study.position + 1} of $total"
}

@Composable
private fun ExpandedSheet(
    study: StudyController, geometry: SheetGeometry, fonts: CardFace.Fonts, placement: () -> PagePlacement?, serial: Long,
    width: Dp, maxHeight: Dp, drag: Modifier, onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val item = study.current
    val exam = study.exam
    SheetSurface(Modifier.width(width).heightIn(max = maxHeight)) {
        // Header: drag to move; previous, where you are, next; menu; collapse; close.
        Row(drag.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp).semantics { contentDescription = "Study sheet. Drag to move." },
            verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous", enabled = study.position > 0) { study.step(-1) }
            if (exam != null) ExamClock(study, exam.endsAt) else
                Text(positionLabel(study), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            SmallIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next", enabled = study.position < study.order.lastIndex) { study.step(1) }
            Spacer(Modifier.weight(1f))
            SheetMenu(study, serial)
            SmallIcon(Icons.Outlined.CloseFullscreen, "Collapse the study sheet") { study.collapsed = true }
            SmallIcon(Icons.Outlined.Close, "Close the study sheet") { study.hide() }
        }
        Banners(study, onSettings)
        if (item == null) {
            EmptyDeck(study)
            return@SheetSurface
        }
        val progress = study.data.progressOf(item.id)
        val number = study.data.items.indexOf(item) + 1
        val source = study.data.source(item.sourceId)?.name
        val widthDp = width.value
        val mode = if (exam != null && item.id in exam.itemIds) CardFace.Mode.Exam else CardFace.Mode.Sheet
        val face = remember(item, progress, widthDp, mode, number, source) {
            CardFace.layout(item, progress, widthDp, fonts, mode, number, source)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            val choose = if (item.kind == ItemKind.Choice && (mode == CardFace.Mode.Exam || progress.chosen < 0)) study::choose else null
            Box(Modifier.size(face.widthDp.dp, face.heightDp.dp).onGloballyPositioned { geometry.face = it }) {
                Canvas(Modifier.matchParentSize()) {
                    drawIntoCanvas { c ->
                        val n = c.nativeCanvas
                        val s = n.save()
                        n.scale(density.density, density.density)
                        face.draw(n)
                        n.restoreToCount(s)
                    }
                }
                choose?.let { onChoose ->
                    face.optionRects.forEachIndexed { i, r ->
                        Box(Modifier.offset(r.left.dp, r.top.dp).size(r.width().dp, r.height().dp).clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = "Choose ${'A' + i}") { onChoose(i) })
                    }
                }
            }
        }
        Actions(study, item, serial, mode == CardFace.Mode.Exam) {
            val at = geometry.faceInEditor() ?: return@Actions
            val sheet = placement()?.visiblePages?.firstOrNull() ?: return@Actions
            study.print(at.x, at.y, face.widthDp, density.density, sheet)
        }
    }
}

@Composable
private fun ExamClock(study: StudyController, endsAt: Long) {
    var left by remember { mutableLongStateOf(endsAt - System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (true) {
            left = endsAt - System.currentTimeMillis()
            if (left <= 0) { study.finishExam(); break }
            delay(500)
        }
    }
    val s = (left.coerceAtLeast(0) / 1000).toInt()
    Text("Exam · %d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelLarge,
        color = if (s < 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { contentDescription = "Exam, ${s / 60} minutes ${s % 60} seconds left" })
}

@Composable
private fun Banners(study: StudyController, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    study.loop?.let { l ->
        Banner(if (l is StudyController.Loop.Answer) "Draw a loop around your answer on the board." else
            "Draw a loop around the notes to make questions from.", Icons.Outlined.Gesture, colors.primaryContainer) {
            BannerAction("Cancel") { study.cancelLoop() }
        }
    }
    if (study.checking != null && study.checking == study.current?.id) Banner("Checking your answer…", null, colors.surfaceContainer, busy = true)
    study.generating?.let { g ->
        Banner("Making questions from ${g.label}… ${if (g.made > 0) "${g.made} so far" else ""}".trim(), null, colors.surfaceContainer, busy = true) {
            BannerAction("Stop") { study.stopGenerating() }
        }
    }
    study.problem?.let { p ->
        Banner(p, Icons.Outlined.ErrorOutline, colors.errorContainer) {
            if (p.contains("Settings")) BannerAction("Settings") { study.problem = null; onSettings() }
            BannerAction("OK") { study.problem = null }
        }
    }
}

@Composable
private fun Banner(text: String, icon: ImageVector?, color: androidx.compose.ui.graphics.Color, busy: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color)
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        actions()
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    app.noti.ui.common.NotiTextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EmptyDeck(study: StudyController) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DotiMascot(Modifier.size(72.dp))
        Text("Nothing to study yet", style = MaterialTheme.typography.titleMedium)
        Text("Link a PDF and Noti makes math and physics questions from it. You can also loop notes on the board.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        app.noti.ui.common.NotiButton(onClick = { study.generateOpen = true }, enabled = study.generating == null) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Make questions")
        }
    }
}

@Composable
private fun Actions(study: StudyController, item: StudyItem, serial: Long, exam: Boolean, onPrint: () -> Unit) {
    val p = study.data.progressOf(item.id)
    val waiting = study.loop != null || study.checking == item.id
    FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            exam -> {
                if (item.kind != ItemKind.Choice) Pill(Icons.Outlined.Gesture, if (p.grade != null) "Answer again" else "Submit answer",
                    enabled = !waiting, primary = p.grade == null) { study.startCheck(serial) }
                Pill(Icons.Outlined.Flag, "Finish exam") { study.finishExam() }
            }
            item.kind == ItemKind.Flash -> {
                if (!p.flipped && p.srs == null) Pill(Icons.Outlined.Flip, "Flip", primary = true) { study.flip() }
                else {
                    Rating.entries.forEach { r -> Pill(null, r.label, primary = r == Rating.Good) { study.rate(r) } }
                }
                Pill(Icons.Outlined.PushPin, "Print") { onPrint() }
            }
            else -> {
                if (item.hints.isNotEmpty() && p.hintsShown < item.hints.size && !p.answerShown)
                    Pill(Icons.Outlined.Lightbulb, if (p.hintsShown == 0) "Hint" else "Hint ${p.hintsShown + 1}") { study.hint() }
                if (item.kind != ItemKind.Choice) Pill(Icons.Outlined.Gesture, if (p.grade != null) "Check again" else "Check",
                    enabled = !waiting, primary = p.grade == null) { study.startCheck(serial) }
                if (!p.answerShown) Pill(Icons.Outlined.Visibility, "Answer") { study.reveal() }
                if (item.pages.isNotEmpty() && item.sourceId != null) Pill(Icons.Outlined.MenuBook, "Source") { study.sourceOf = item }
                Pill(Icons.Outlined.PushPin, "Print", primary = p.grade != null || p.chosen >= 0 || p.answerShown) { onPrint() }
            }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector?, label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg = if (primary) colors.primary else colors.surfaceContainerHigh
    val fg = if (primary) colors.onPrimary else colors.onSurface
    Row(Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (enabled) bg else colors.surfaceContainer)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp), tint = if (enabled) fg else fg.copy(alpha = 0.38f)); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) fg else fg.copy(alpha = 0.38f))
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    app.noti.ui.common.NotiIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, label, Modifier.size(20.dp))
    }
}

@Composable
private fun SheetMenu(study: StudyController, serial: Long) {
    var open by remember { mutableStateOf(false) }
    val item = study.current
    Box {
        SmallIcon(Icons.Outlined.MoreHoriz, "Study options") { open = true }
        app.noti.ui.common.NotiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun entry(label: String, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, action: () -> Unit) =
                app.noti.ui.common.NotiMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled,
                    destructive = destructive, onClick = { open = false; action() })
            entry("Make questions…", Icons.Outlined.AutoAwesome, enabled = study.generating == null) { study.generateOpen = true }
            entry("Questions from notes on the board", Icons.Outlined.Gesture, enabled = study.generating == null && study.loop == null) {
                study.startNotes(DeckRequest(count = 6, level = study.settings.studyLevel), serial)
            }
            entry("Deck", Icons.Outlined.Style) { study.deckOpen = true }
            if (study.exam == null) entry("Start an exam…", Icons.Outlined.Timer) { study.examSetupOpen = true }
            else entry("Finish the exam", Icons.Outlined.Flag) { study.finishExam() }
            entry("Arrange cards by topic", Icons.Outlined.ViewColumn, enabled = study.data.cards.isNotEmpty()) { study.arrangeByTopic() }
            if (item != null && study.exam == null) entry("Try this one again", Icons.Outlined.Replay) { study.reset(item.id) }
        }
    }
}
