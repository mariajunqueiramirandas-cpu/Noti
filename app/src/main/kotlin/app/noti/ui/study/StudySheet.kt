package app.noti.ui.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.noti.exams.StudyOverview
import app.noti.pdf.PagePlacement
import app.noti.study.CardFace
import app.noti.study.ItemKind
import app.noti.study.Rating
import app.noti.study.StudyItem
import app.noti.study.Verdict
import app.noti.ui.common.FloatingOverlay
import app.noti.ui.common.clampTo
import app.noti.ui.common.dragFloatingOverlay
import app.noti.ui.common.rememberFloatingOverlayMove
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
 * The floating study sheet: Deck, Assaad, and Past Exams over any canvas, which stays live
 * underneath. Drag its header to move it.
 */
@Composable
internal fun BoxScope.StudySheet(
    study: StudyController,
    geometry: SheetGeometry,
    fonts: CardFace.Fonts,
    placement: () -> PagePlacement?,
    maxWidth: Dp,
    maxHeight: Dp,
    /** In a sub-panel window above the ink (the app); inline for screenshot tests. */
    floating: Boolean = true,
) {
    if (!study.open) return
    val density = LocalDensity.current
    fun px(dp: Dp) = with(density) { dp.roundToPx() }
    val compact = maxWidth < 600.dp
    val sheetWidth = if (compact) maxWidth - 16.dp else (maxWidth * 0.42f).coerceIn(340.dp, 440.dp)
    val maxSheetHeight = if (compact) maxHeight * 0.58f else (maxHeight - 140.dp).coerceAtLeast(260.dp)
    val width = px(maxWidth)
    val height = px(maxHeight)
    val expandedWidth = px(sheetWidth) + 2 * px(Halo)
    val default = IntOffset(width - expandedWidth - px(6.dp), px(66.dp))
    val bounds = IntRect(-px(Halo), 0, (width - expandedWidth).coerceAtLeast(0), (height - px(56.dp)).coerceAtLeast(0))
    val bottomInset = px(4.dp)
    val offset = remember(compact, study, width, height, expandedWidth, default, bounds, bottomInset) {
        { size: IntSize ->
            if (compact) IntOffset((width - expandedWidth) / 2, height - size.height - bottomInset)
            else (study.offset ?: default).clampTo(bounds)
        }
    }

    val move = if (compact) null else rememberFloatingOverlayMove { study.offset = it }
    FloatingOverlay(floating, position = offset, clippingEnabled = false) {
        Box(Modifier.padding(Halo).onGloballyPositioned { geometry.window = it }) {
            val drag = if (move == null) Modifier else Modifier.pointerInput(study, bounds, default, move) {
                dragFloatingOverlay(bounds, position = { study.offset ?: default }, moveTo = move)
            }
            if (study.collapsed) CollapsedSheet(study, drag) else ExpandedSheet(study, geometry, fonts, placement,
                sheetWidth, maxSheetHeight, drag)
        }
    }
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
    val total = study.activeOrder.size
    return if (total == 0) study.effectiveTab.label else "Q ${study.position + 1} of $total"
}

@Composable
private fun ExpandedSheet(
    study: StudyController, geometry: SheetGeometry, fonts: CardFace.Fonts, placement: () -> PagePlacement?,
    width: Dp, maxHeight: Dp, drag: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val tab = study.effectiveTab
    val item = study.current
    val exam = study.exam
    val showTabs = exam == null && (study.remote != null || study.examFeed != null)
    SheetSurface(Modifier.width(width).heightIn(max = maxHeight)) {
        // Header: drag to move; previous, where you are, next; menu; collapse; close.
        Row(drag.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp).semantics { contentDescription = "Study sheet. Drag to move." },
            verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous", enabled = study.position > 0) { study.step(-1) }
            if (exam != null) ExamClock(study, exam.endsAt) else
                Text(positionLabel(study), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            SmallIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next", enabled = study.position < study.activeOrder.lastIndex) { study.step(1) }
            Spacer(Modifier.weight(1f))
            SheetMenu(study)
            SmallIcon(Icons.Outlined.CloseFullscreen, "Collapse the study sheet") { study.collapsed = true }
            SmallIcon(Icons.Outlined.Close, "Close the study sheet") { study.hide() }
        }
        if (showTabs) SourceSwitcher(study, tab)
        if (tab == StudySourceTab.Assaad) {
            study.remote?.let { r ->
                if (r.filters.size > 1) FilterStrip("All sets", r.filters, r.selectedFilterId, r::selectFilter)
                r.connectionNote?.let { note ->
                    Banner(note, Icons.Outlined.CloudOff, colors.surfaceContainerHigh) {
                        BannerAction("Open") { r.openPlatform() }
                    }
                }
            }
        } else if (tab == StudySourceTab.Exams) {
            study.examFeed?.let { feed ->
                if (feed.filters.size > 1) FilterStrip("All exams", feed.filters, feed.selectedFilterId, feed::selectFilter)
            }
        }
        if (item == null) {
            EmptyTabState(study, tab, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            return@SheetSurface
        }
        val progress = study.progressOf(item)
        val number = when (tab) {
            StudySourceTab.Deck -> study.data.items.indexOf(item) + 1
            else -> study.position + 1
        }
        val source = study.sourceNameOf(item)
        val widthDp = width.value
        val mode = if (exam != null && item.id in exam.itemIds) CardFace.Mode.Exam else CardFace.Mode.Sheet
        val remote = study.remote?.takeIf { it.owns(item.id) }
        val remoteState = remote?.state(item.id)
        val picked = remote?.picked(item.id) ?: -1
        // Reading the pictures here recomposes the face as each one arrives.
        val pictures = remote?.let { r -> (item.figures + item.choiceFigures.flatten()).count { r.figure(it) != null } } ?: 0
        val face = remember(item, progress, widthDp, mode, number, source, picked, pictures) {
            CardFace.layout(item, progress, widthDp, fonts, mode, number, source, picked, remote?.let { r -> r::figure })
        }
        RemoteBanner(remote, item.id, remoteState)
        val scroll = key(item.id) { rememberScrollState() }
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
            val canChoose = item.kind == ItemKind.Choice && remoteState?.locked != true &&
                (mode == CardFace.Mode.Exam || progress.chosen < 0 || item.correctChoice < 0)
            val choose = if (canChoose) study::choose else null
            Box(Modifier.size(face.widthDp.dp, face.heightDp.dp).onGloballyPositioned { geometry.face = it }) {
                Canvas(Modifier.matchParentSize().semantics {
                    contentDescription = plain(item.prompt) +
                        if (item.kind == ItemKind.Flash && progress.flipped) "\n${plain(item.answer)}" else ""
                }) {
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
                            .semantics { contentDescription = "${'A' + i}. ${plain(item.choices[i])}" }
                            .clickable(onClickLabel = "Choose ${'A' + i}") { onChoose(i) })
                    }
                }
            }
        }
        val canPrint = study.board != null
        Actions(study, item, progress, mode == CardFace.Mode.Exam, remote, remoteState, picked, canPrint) {
            val at = geometry.faceInEditor() ?: return@Actions
            val sheet = placement()?.visiblePages?.firstOrNull() ?: return@Actions
            study.print(at.x, at.y, face.widthDp, density.density, sheet)
        }
        val isExamItem = tab == StudySourceTab.Exams || study.isPastExam(item.id) || item.credit == "FUVEST"
        if (isExamItem && remote == null && progress.attempts > 0 && exam == null) {
            Box(Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Banner("Tentativa registrada · ${StudyOverview.QuestionXp} XP ganhos", Icons.Outlined.Star, colors.tertiaryContainer)
            }
        }
    }
}

@Composable
private fun SourceSwitcher(study: StudyController, active: StudySourceTab) {
    val colors = MaterialTheme.colorScheme
    val tabs = buildList {
        add(StudySourceTab.Deck)
        if (study.remote != null) add(StudySourceTab.Assaad)
        if (study.examFeed != null) add(StudySourceTab.Exams)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(CircleShape).background(colors.surfaceContainerLow).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tabs.forEach { tab ->
            val selected = tab == active
            Box(
                Modifier.weight(1f).heightIn(min = 34.dp).clip(CircleShape)
                    .background(if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { study.switchTab(tab) }
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FilterStrip(allLabel: String, options: List<StudyFilterOption>, selectedId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val current = options.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerLow)
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current?.label ?: allLabel,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
        if (open) app.noti.ui.common.NotiDropdownMenu(expanded = true, onDismissRequest = { open = false }) {
            app.noti.ui.common.NotiMenuItem(
                text = { Text(allLabel) },
                trailingIcon = if (selectedId == null) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                onClick = { open = false; onSelect(null) },
            )
            options.forEach { opt ->
                app.noti.ui.common.NotiMenuItem(
                    text = {
                        Column {
                            Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (opt.detail.isNotBlank()) Text(opt.detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    },
                    trailingIcon = if (opt.id == selectedId) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                    onClick = { open = false; onSelect(opt.id) },
                )
            }
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

/** Where a remotely checked answer is: sending, queued, not accepted, or unconfirmed. */
@Composable
private fun RemoteBanner(remote: RemoteChoices?, itemId: String, state: RemoteState?) {
    if (remote == null || state == null) return
    val colors = MaterialTheme.colorScheme
    when (state.phase) {
        RemoteState.Phase.Checking -> Banner(state.message, null, colors.secondaryContainer, busy = true)
        RemoteState.Phase.Blocked -> Banner(state.message, Icons.Outlined.ErrorOutline, colors.errorContainer) {
            BannerAction("Open ${remote.name}") { remote.openPlatform() }
            if (remote.canSubmit) BannerAction("Try again") { remote.retry(itemId) }
        }
        RemoteState.Phase.Unknown -> Banner(state.message, Icons.Outlined.HelpOutline, colors.errorContainer) {
            BannerAction("Open") { remote.openPlatform() }
            BannerAction("Retry") { remote.retry(itemId) }
        }
        RemoteState.Phase.Done -> if (state.message.isNotBlank()) Banner(state.message, null, colors.surfaceContainerHigh)
        RemoteState.Phase.Draft -> Unit
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
private fun EmptyTabState(study: StudyController, tab: StudySourceTab, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (tab) {
            StudySourceTab.Deck -> {
                Text("Nothing to study yet", style = MaterialTheme.typography.titleMedium)
                Text("Add flash cards and practice questions to build your deck.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    app.noti.ui.common.NotiButton(onClick = { study.editItem(ItemKind.Flash) }) { Text("Add flash card") }
                    app.noti.ui.common.NotiOutlinedButton(onClick = { study.editItem(ItemKind.Short) }) { Text("Add question") }
                }
            }
            StudySourceTab.Assaad -> {
                val remote = study.remote
                Text("No Assaad questions yet", style = MaterialTheme.typography.titleMedium)
                Text("Find questions in Plataforma Assaad and study them right here on this canvas.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (remote != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    app.noti.ui.common.NotiButton(onClick = remote::openPlatform) { Text("Open ${remote.name}") }
                    app.noti.ui.common.NotiOutlinedButton(onClick = remote::openConnection) { Text("Connection") }
                }
            }
            StudySourceTab.Exams -> {
                Text("No past exams imported yet", style = MaterialTheme.typography.titleMedium)
                Text("Import any exam PDF — AI identifies the exam, subjects, and questions automatically.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                study.examFeed?.let { feed ->
                    app.noti.ui.common.NotiButton(onClick = feed::openHub) { Text("Open Past Exams") }
                }
            }
        }
    }
}

@Composable
private fun Actions(
    study: StudyController,
    item: StudyItem,
    p: app.noti.study.Progress,
    exam: Boolean,
    remote: RemoteChoices?,
    remoteState: RemoteState?,
    picked: Int,
    canPrint: Boolean,
    onPrint: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            remote != null -> {
                val phase = remoteState?.phase
                when (phase) {
                    RemoteState.Phase.Done -> if (study.position < study.activeOrder.lastIndex)
                        Pill(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next question", primary = true) { study.step(1) }
                    RemoteState.Phase.Checking -> Pill(null, "Checking…", enabled = false, primary = true) {}
                    RemoteState.Phase.Unknown -> Pill(Icons.Outlined.Replay, "Allow another attempt") { remote.retry(item.id) }
                    else -> when {
                        picked < 0 -> Pill(Icons.Outlined.Check, "Pick an option", enabled = false, primary = true) {}
                        remote.sending -> Pill(null, "Another answer is on its way…", enabled = false, primary = true) {}
                        !remote.canSubmit -> Pill(Icons.Outlined.TravelExplore, "Connect ${remote.name} to check", primary = true) { remote.openPlatform() }
                        else -> Pill(Icons.Outlined.Check, "Check with ${remote.name}", primary = true) { remote.submit(item.id) }
                    }
                }
                if (canPrint) Pill(Icons.Outlined.PushPin, "Print", primary = false) { onPrint() }
            }
            exam -> {
                if (item.kind in writtenKinds && p.grade == null) {
                    val answered = study.exam?.submitted?.contains(item.id) == true
                    Pill(if (answered) Icons.Outlined.Check else null, if (answered) "Marked answered" else "Mark answered",
                        primary = !answered) { study.toggleExamAnswer() }
                }
                Pill(Icons.Outlined.Flag, "Finish exam") { study.finishExam() }
            }
            item.kind == ItemKind.Flash -> {
                if (!p.flipped) Pill(Icons.Outlined.Flip, "Show answer", primary = true) { study.flip() }
                else {
                    Rating.entries.forEach { r -> Pill(null, r.label, primary = r == Rating.Good) { study.rate(r) } }
                }
                if (canPrint) Pill(Icons.Outlined.PushPin, "Print") { onPrint() }
            }
            else -> {
                val selfAssessable = item.kind in writtenKinds || (item.kind == ItemKind.Choice && item.correctChoice < 0)
                if (item.hints.isNotEmpty() && p.hintsShown < item.hints.size && !p.answerShown)
                    Pill(Icons.Outlined.Lightbulb, if (p.hintsShown == 0) "Hint" else "Hint ${p.hintsShown + 1}") { study.hint() }
                if (!p.answerShown) Pill(Icons.Outlined.Visibility, "Answer", primary = selfAssessable) { study.reveal() }
                if (selfAssessable && p.answerShown && p.selfAssessment == null) {
                    Verdict.entries.forEach { verdict ->
                        Pill(null, verdict.label) { study.assessWrittenAnswer(verdict) }
                    }
                }
                if (p.answerShown) item.officialSolutionUrl?.let { url ->
                    Pill(Icons.Outlined.MenuBook, "Resolução oficial") {
                        try { uriHandler.openUri(url) } catch (_: IllegalArgumentException) {
                            android.widget.Toast.makeText(study.library.appContext, "Não foi possível abrir o PDF oficial.",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (item.pages.isNotEmpty() && item.sourceId != null) Pill(Icons.Outlined.MenuBook, "Source") { study.sourceOf = item }
                if (canPrint) Pill(Icons.Outlined.PushPin, "Print", primary = p.grade != null || p.chosen >= 0 || p.answerShown) { onPrint() }
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
private fun SheetMenu(study: StudyController) {
    var open by remember { mutableStateOf(false) }
    val item = study.current
    val tab = study.effectiveTab
    Box {
        SmallIcon(Icons.Outlined.MoreHoriz, "Study options") { open = true }
        if (open) app.noti.ui.common.NotiDropdownMenu(expanded = true, onDismissRequest = { open = false }) {
            @Composable fun entry(label: String, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, action: () -> Unit) =
                app.noti.ui.common.NotiMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled,
                    destructive = destructive, onClick = { open = false; action() })
            if (tab == StudySourceTab.Deck) {
                entry("Deck", Icons.Outlined.Style) { study.deckOpen = true }
                if (study.exam == null) {
                    entry("Add flash card", Icons.AutoMirrored.Outlined.NoteAdd) { study.editItem(ItemKind.Flash) }
                    entry("Add question", Icons.Outlined.Add) { study.editItem(ItemKind.Short) }
                    if (item != null && !study.isRemote(item.id) && !study.isPastExam(item.id)) entry(
                        if (item.kind == ItemKind.Flash) "Edit flash card" else "Edit question", Icons.Outlined.Edit,
                    ) { study.editItem(item.kind, item) }
                }
                if (study.exam == null) {
                    entry("Start an exam…", Icons.Outlined.Timer,
                        enabled = study.data.items.any { it.kind != ItemKind.Flash && !study.isRemote(it.id) }) { study.examSetupOpen = true }
                    study.data.exams.lastOrNull { it.finishedAt != null }?.let { exam ->
                        entry("Last exam results", Icons.Outlined.Assessment) { study.examResults = exam.id }
                    }
                } else entry("Finish the exam", Icons.Outlined.Flag) { study.finishExam() }
            }
            if (study.board != null) {
                entry("Arrange cards by topic", Icons.Outlined.ViewColumn, enabled = study.data.cards.isNotEmpty()) { study.arrangeByTopic() }
            }
            if (item != null && study.exam == null && !study.isRemote(item.id)) {
                entry("Try this one again", Icons.Outlined.Replay) { study.reset(item.id) }
            }
            study.remote?.let { r ->
                entry("Open ${r.name}", Icons.Outlined.TravelExplore) { r.openPlatform() }
                entry("Assaad sets & connection", Icons.Outlined.Devices) { r.openConnection() }
            }
            study.examFeed?.let { feed ->
                entry("Manage past exams", Icons.Outlined.School) { feed.openHub() }
            }
        }
    }
}
