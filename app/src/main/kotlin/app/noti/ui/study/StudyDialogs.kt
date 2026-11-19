package app.noti.ui.study

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.pdf.PagePlacement
import app.noti.study.BoardLayer
import app.noti.study.CardFace
import app.noti.study.ItemKind
import app.noti.study.StudyImages
import app.noti.study.StudyItem
import app.noti.study.Verdict
import app.noti.ui.common.NotiButton
import app.noti.study.readableStudyText
import app.noti.ui.common.StudyText
import app.noti.ui.common.NotiDialogFrame
import app.noti.ui.common.NotiFilterChip
import app.noti.ui.common.NotiIconButton
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every dialog and menu the Study board opens, driven by [study]'s state. */
@Composable
internal fun StudyDialogs(study: StudyController, geometry: SheetGeometry, placement: () -> PagePlacement?) {
    study.editorKind?.let { kind ->
        StudyItemDialog(kind, study.editingItem, onClose = study::closeItemEditor, onSave = study::saveItem)
    }
    if (study.deckOpen) DeckDialog(study) { study.deckOpen = false }
    if (study.examSetupOpen) ExamSetupDialog(study) { study.examSetupOpen = false }
    study.examResults?.let { id -> ExamResultsDialog(study, id, geometry, placement) { study.examResults = null } }
    study.sourceOf?.let { item -> SourceDialog(study, item, geometry, placement) { study.sourceOf = null } }
    study.menu?.let { (hit, at) -> BoardMenu(study, hit, at, geometry, placement) { study.menu = null } }
}

@Composable
private fun StudyItemDialog(initialKind: ItemKind, item: StudyItem?, onClose: () -> Unit, onSave: (StudyItem) -> Unit) {
    var kind by rememberSaveable(item?.id, initialKind) { mutableStateOf(initialKind) }
    var prompt by rememberSaveable(item?.id) { mutableStateOf(item?.prompt.orEmpty()) }
    var answer by rememberSaveable(item?.id) { mutableStateOf(item?.answer.orEmpty()) }
    var topic by rememberSaveable(item?.id) { mutableStateOf(item?.topic.orEmpty()) }
    var choices by rememberSaveable(item?.id) { mutableStateOf(ArrayList(List(4) { item?.choices?.getOrNull(it).orEmpty() })) }
    var correct by rememberSaveable(item?.id) { mutableIntStateOf(item?.correctChoice ?: -1) }
    val flash = kind == ItemKind.Flash
    val multipleChoice = kind == ItemKind.Choice
    val validAnswer = if (multipleChoice) choices.all(String::isNotBlank) && correct in choices.indices else answer.isNotBlank()
    val title = if (flash) {
        if (item == null) "New flash card" else "Edit flash card"
    } else {
        if (item == null) "New question" else "Edit question"
    }
    NotiDialogFrame(onClose, title = { Text(title) }, actions = {
        NotiTextButton(onClick = onClose) { Text("Cancel") }
        NotiButton(enabled = prompt.isNotBlank() && validAnswer, onClick = {
            val base = item ?: StudyItem("", kind, "", "")
            onSave(base.copy(
                kind = kind,
                topic = topic.trim(),
                prompt = prompt.trim(),
                answer = if (multipleChoice) choices[correct].trim() else answer.trim(),
                choices = if (multipleChoice) choices.map(String::trim) else emptyList(),
                correctChoice = if (multipleChoice) correct else -1,
            ))
        }) {
            Text(when {
                flash && item == null -> "Add card"
                flash -> "Save card"
                item == null -> "Add question"
                else -> "Save question"
            })
        }
    }) {
        if (!flash && item == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotiFilterChip(kind == ItemKind.Short, { kind = ItemKind.Short }, label = { Text("Written answer") })
                NotiFilterChip(kind == ItemKind.Choice, { kind = ItemKind.Choice }, label = { Text("Multiple choice") })
            }
        }
        OutlinedTextField(prompt, { prompt = it }, label = { Text(if (flash) "Front" else "Question") },
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        if (multipleChoice) {
            Text("Select the correct option and fill in all four choices.")
            choices.forEachIndexed { index, text ->
                val letter = 'A' + index
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = correct == index, onClick = { correct = index },
                        modifier = Modifier.semantics { contentDescription = "Mark choice $letter as correct" })
                    OutlinedTextField(text, { value -> choices = ArrayList(choices).apply { set(index, value) } },
                        label = { Text("Choice $letter") }, modifier = Modifier.weight(1f))
                }
            }
        } else {
            OutlinedTextField(answer, { answer = it }, label = { Text(if (flash) "Back" else "Answer") },
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(topic, { topic = it }, label = { Text("Topic (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
    }
}

// ---- the deck ----

@Composable
private fun DeckDialog(study: StudyController, onClose: () -> Unit) {
    val data = study.data
    val now = System.currentTimeMillis()
    var confirm by remember { mutableStateOf<StudyItem?>(null) }
    NotiDialogFrame(onClose, title = { Text("Deck") }, maxWidth = 640.dp, actions = {
        if (study.exam == null) {
            NotiOutlinedButton(onClick = { onClose(); study.editItem(ItemKind.Flash) }) { Text("Add flash card") }
            NotiOutlinedButton(onClick = { onClose(); study.editItem(ItemKind.Short) }) { Text("Add question") }
        }
        NotiButton(onClick = onClose) { Text("Done") }
    }) {
        val due = data.dueCount(now)
        Text("${data.items.size} items · $due due · ${data.cards.size} on the board" +
            if (data.sources.isNotEmpty()) " · from ${data.sources.joinToString { it.name }}" else "")
        if (data.items.isEmpty()) Text("Add a flash card or question to start your deck.")
        data.topics().forEach { topic ->
            val items = data.items.filter { it.topic.ifBlank { "General" } == topic }
            Text(topic, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                items.forEach { item ->
                    val p = data.progressOf(item.id)
                    val outcome = p.outcome(item)
                    Row(Modifier.fillMaxWidth().clickable(enabled = study.exam?.let { item.id in it.itemIds } ?: true) {
                        onClose(); study.select(item.id); study.collapsed = false; study.open = true
                    }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(CardFace.statusColors(outcome)?.let { Color(it.second) } ?: Color(0xFFCFCBD3)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(plain(item.prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val due = p.srs?.due
                            Text(item.kind.label + (when {
                                due == null -> " · new"
                                due <= now -> " · due now"
                                else -> " · next in ${((due - now) / 86_400_000L).coerceAtLeast(1)} d"
                            }) + if (data.cardFor(item.id) != null) " · on the board" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        NotiIconButton(onClick = { confirm = item }, enabled = study.exam == null) {
                            Icon(Icons.Outlined.DeleteOutline, "Delete this item")
                        }
                    }
                }
            }
        }
    }
    confirm?.let { item ->
        app.noti.ui.common.NotiAlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete this item?") },
            text = { Text("It leaves the deck, and its card leaves the board. Your ink stays.") },
            confirmButton = { NotiButton(destructive = true, onClick = { study.deleteItem(item.id); confirm = null }) { Text("Delete") } },
            dismissButton = { NotiTextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

/** Compact preview in the deck and in completed exam results. */
internal fun plain(s: String): String = readableStudyText(s).replace(Regex("\\s+"), " ").trim()

// ---- exams ----

@Composable
private fun ExamSetupDialog(study: StudyController, onClose: () -> Unit) {
    val topics = study.data.topics()
    var count by remember { mutableIntStateOf(10) }
    var minutes by remember { mutableIntStateOf(20) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    NotiDialogFrame(onClose, title = { Text("Exam") }, maxWidth = 560.dp, actions = {
        NotiTextButton(onClick = onClose) { Text("Cancel") }
        NotiButton(onClick = { onClose(); study.startExam(count, minutes, chosen) }) { Text("Start") }
    }) {
        Text("Answer multiple choice on the sheet. For written questions, work on the board and mark them answered. " +
            "Compare your work with the answers when the timer ends.")
        Text("Questions", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 20).forEach { n -> NotiFilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") }) }
        }
        Text("Time", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30, 45, 60, 90).forEach { m -> NotiFilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") }) }
        }
        if (topics.size > 1) {
            Text("Topics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NotiFilterChip(selected = chosen.isEmpty(), onClick = { chosen = emptySet() }, label = { Text("All") })
                topics.forEach { t -> NotiFilterChip(selected = t in chosen, onClick = { chosen = if (t in chosen) chosen - t else chosen + t }, label = { Text(t) }) }
            }
        }
    }
}

@Composable
private fun ExamResultsDialog(study: StudyController, examId: String, geometry: SheetGeometry, placement: () -> PagePlacement?, onClose: () -> Unit) {
    val exam = study.data.exam(examId) ?: return onClose()
    val density = LocalDensity.current.density
    val items = exam.itemIds.mapNotNull { study.data.item(it) }
    val verdicts = items.map { exam.outcome(it, study.data) }
    val pending = items.count { exam.needsAssessment(it.id) }
    val score = verdicts.sumOf { when (it) { Verdict.Correct -> 1.0; Verdict.Partial -> 0.5; else -> 0.0 } }
    NotiDialogFrame(onClose, title = { Text("Exam done") }, maxWidth = 560.dp, actions = {
        NotiTextButton(onClick = onClose) { Text("Close") }
        val wrong = items.filterIndexed { i, item -> !exam.needsAssessment(item.id) && verdicts[i] != Verdict.Correct }
        if (wrong.isNotEmpty()) NotiOutlinedButton(onClick = { onClose(); study.select(wrong.first().id); study.collapsed = false; study.open = true }) { Text("Review mistakes") }
        NotiButton(enabled = pending == 0, onClick = {
            val sheet = placement()?.visiblePages?.firstOrNull()
            if (sheet != null) {
                val at = geometry.sheetInEditor() ?: androidx.compose.ui.geometry.Offset(sheet.canvasWidth / 2f - 140 * density, sheet.canvasHeight / 3f)
                study.printScore(examId, at.x + 10 * density, at.y + 10 * density, density, sheet)
            }
            onClose()
        }) { Text("Print score card") }
    }) {
        Text((if (score % 1.0 == 0.0) "${score.toInt()}" else "%.1f".format(score)) + " of ${items.size}",
            style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        if (pending > 0) Text("$pending written ${if (pending == 1) "answer to review" else "answers to review"}")
        items.forEachIndexed { i, item ->
            if (exam.needsAssessment(item.id)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudyText("${i + 1}. ${item.prompt}", style = MaterialTheme.typography.titleSmall)
                    StudyText(item.answer, style = MaterialTheme.typography.bodyMedium)
                    item.solution.forEach { step -> StudyText(step, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Verdict.entries.forEach { verdict ->
                            NotiTextButton(onClick = { study.assessExamAnswer(examId, item.id, verdict) }) { Text(verdict.label) }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(CardFace.statusColors(verdicts[i])?.let { Color(it.second) } ?: Color(0xFFCFCBD3)))
                    Text("${i + 1}. ${plain(item.prompt)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Text(verdicts[i]?.label ?: "Not answered", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("Each answer counts toward its review schedule.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---- source pages ----

@Composable
private fun SourceDialog(study: StudyController, item: StudyItem, geometry: SheetGeometry, placement: () -> PagePlacement?, onClose: () -> Unit) {
    val source = study.library.notebook(item.sourceId ?: "")
    val file = source?.let { study.library.pdfFile(it) }
    val density = LocalDensity.current.density
    var page by remember { mutableIntStateOf(item.pages.firstOrNull() ?: 1) }
    val bitmap by produceState<Bitmap?>(null, file, page) {
        value = null
        value = withContext(Dispatchers.IO) { file?.let { StudyImages.renderPage(it, page - 1, (520 * density).toInt()) } }
    }
    NotiDialogFrame(onClose, title = { Text(source?.name ?: "Source") }, maxWidth = 620.dp, actions = {
        NotiTextButton(onClick = onClose) { Text("Close") }
        if (study.board != null) NotiButton(enabled = source != null, onClick = {
            val sheet = placement()?.visiblePages?.firstOrNull() ?: return@NotiButton
            val origin = geometry.sheetInEditor()
            val width = 360f
            // Beside the sheet when there's room on its left, else in the middle of the view.
            val left = origin?.let { it.x - (width + 16) * density }?.takeIf { it > 0 } ?: (sheet.canvasWidth / 2f - width * density / 2)
            val top = origin?.y?.plus(10 * density) ?: (sheet.canvasHeight / 4f)
            study.placePage(source!!.id, page - 1, left, top, width, density, sheet)
            onClose()
        }) { Text("Place on board") }
    }) {
        if (source == null) { Text("That PDF isn't in the library anymore."); return@NotiDialogFrame }
        if (item.pages.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.pages.forEach { p -> NotiFilterChip(selected = page == p, onClick = { page = p }, label = { Text("p. $p") }) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f / (bitmap?.let { it.height.toFloat() / it.width } ?: 1.414f))
            .clip(RoundedCornerShape(8.dp)).background(Color.White).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), "Page $page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        if (item.excerpt.isNotBlank()) StudyText(item.excerpt, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- tapping things on the board ----

@Composable
private fun BoardMenu(study: StudyController, hit: BoardLayer.Hit, at: IntOffset, geometry: SheetGeometry, placement: () -> PagePlacement?,
    onClose: () -> Unit) {
    Popup(offset = at, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.width(240.dp).padding(vertical = 6.dp)) {
                @Composable fun entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) =
                    app.noti.ui.common.NotiMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, destructive = destructive,
                        onClick = { onClose(); action() })
                when (hit) {
                    is BoardLayer.Hit.Card -> {
                        entry("Open on the sheet", Icons.Outlined.OpenInNew) { study.openFromBoard(hit.card.itemId) }
                        entry("Move", Icons.Outlined.OpenWith) {
                            val sheet = placement()?.visiblePages?.firstOrNull() ?: return@entry
                            study.unpin(hit.card, sheet, geometry.faceInSheet().takeIf { it != IntOffset.Zero } ?: IntOffset(DefaultFaceX, DefaultFaceY))
                        }
                        if (!study.isRemote(hit.card.itemId)) {
                            entry("Try it again", Icons.Outlined.Replay) {
                                study.reset(hit.card.itemId); study.openFromBoard(hit.card.itemId)
                            }
                        }
                        entry("Remove from the board", Icons.Outlined.DeleteOutline, destructive = true) { study.remove(hit) }
                    }
                    is BoardLayer.Hit.Page -> entry("Remove this page", Icons.Outlined.DeleteOutline, destructive = true) { study.remove(hit) }
                    is BoardLayer.Hit.Label -> entry("Remove this heading", Icons.Outlined.DeleteOutline, destructive = true) { study.remove(hit) }
                    is BoardLayer.Hit.Score -> {
                        entry("Results", Icons.Outlined.Assessment) { study.examResults = hit.exam.id }
                        entry("Remove from the board", Icons.Outlined.DeleteOutline, destructive = true) { study.remove(hit) }
                    }
                }
            }
        }
    }
}

/** Until the sheet has been laid out once: its face's usual offset inside the window, px. */
private const val DefaultFaceX = 30
private const val DefaultFaceY = 150
