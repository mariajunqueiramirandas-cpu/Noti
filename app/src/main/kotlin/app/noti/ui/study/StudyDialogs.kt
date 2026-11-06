package app.noti.ui.study

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.data.Notebook
import app.noti.pdf.PagePlacement
import app.noti.pdf.PdfImport
import app.noti.study.BoardLayer
import app.noti.study.CardFace
import app.noti.study.DeckRequest
import app.noti.study.ItemKind
import app.noti.study.StudyImages
import app.noti.study.StudyItem
import app.noti.study.Verdict
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiDialogFrame
import app.noti.ui.common.NotiFilterChip
import app.noti.ui.common.NotiIconButton
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every dialog and menu the Study board opens, driven by [study]'s state. */
@Composable
internal fun StudyDialogs(study: StudyController, geometry: SheetGeometry, placement: () -> PagePlacement?, serial: Long) {
    if (study.generateOpen) GenerateDialog(study, serial) { study.generateOpen = false }
    if (study.deckOpen) DeckDialog(study) { study.deckOpen = false }
    if (study.examSetupOpen) ExamSetupDialog(study) { study.examSetupOpen = false }
    study.examResults?.let { id -> ExamResultsDialog(study, id, geometry, placement) { study.examResults = null } }
    study.sourceOf?.let { item -> SourceDialog(study, item, geometry, placement) { study.sourceOf = null } }
    study.menu?.let { (hit, at) -> BoardMenu(study, hit, at, geometry, placement) { study.menu = null } }
}

// ---- make questions ----

@Composable
private fun GenerateDialog(study: StudyController, serial: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = study.library
    val pdfs = library.notebooks.filter { it.isPdf && it.trashedAt == null }
    val linked = study.data.sources.map { it.id }.toSet()
    var choice by remember { mutableStateOf(pdfs.sortedByDescending { it.id in linked }.firstOrNull()?.id ?: NOTES) }
    var kinds by remember { mutableStateOf(ItemKind.entries.toSet()) }
    var count by remember { mutableIntStateOf(study.settings.studyCount) }
    var level by remember { mutableIntStateOf(study.settings.studyLevel) }
    var range by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PdfImport.import(context, uri, library.pdfDir) } }
            importing = false
            result.onSuccess { choice = library.addPdf(it.id, it.name, it.pageCount, study.notebook.folderId).id }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't import that PDF.", Toast.LENGTH_LONG).show() }
        }
    }
    val chosen = pdfs.firstOrNull { it.id == choice }
    val pageCount = chosen?.pageCount ?: 0
    val first = from.toIntOrNull()
    val last = to.toIntOrNull() ?: pageCount
    val rangeOk = !range || chosen == null || (first != null && first in 1..pageCount && last in first..pageCount)
    val hasKey = study.settings.studyKey.hasKey

    NotiDialogFrame(onClose, title = { Text("Make questions") }, maxWidth = 600.dp, actions = {
        NotiTextButton(onClick = onClose) { Text("Cancel") }
        NotiButton(enabled = hasKey && kinds.isNotEmpty() && rangeOk && !importing && (chosen != null || choice == NOTES), onClick = {
            study.settings.studyCount = count; study.settings.studyLevel = level
            val request = DeckRequest(kinds, count, level, if (range && chosen != null) first!!..last else null, focus)
            onClose()
            if (chosen != null) study.generate(chosen, request) else study.startNotes(request, serial)
        }) { Text(if (choice == NOTES) "Loop the notes" else "Make ${count} items") }
    }) {
        if (!hasKey) Text("Add your Mistral API key in Settings → Study first.", color = MaterialTheme.colorScheme.error)
        Text("From", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            pdfs.sortedByDescending { it.id in linked }.forEach { nb ->
                SourceRow(nb.name, "${nb.pageCount} pages" + if (nb.id in linked) " · linked" else "", Icons.Outlined.PictureAsPdf,
                    choice == nb.id) { choice = nb.id; to = "" }
            }
            SourceRow("Notes on this board", "Loop handwriting on the board after this", Icons.Outlined.Gesture, choice == NOTES) { choice = NOTES }
            Row(Modifier.fillMaxWidth().clickable(enabled = !importing) { import.launch(arrayOf("application/pdf")) }
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text("Import a PDF…", Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall)
            }
        }
        if (chosen != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotiFilterChip(selected = !range, onClick = { range = false }, label = { Text("All ${chosen.pageCount} pages") })
                NotiFilterChip(selected = range, onClick = { range = true }, label = { Text("Pages") })
                if (range) {
                    NumberField(from, { from = it }, "From")
                    NumberField(to, { to = it }, "To", placeholder = "$pageCount")
                }
            }
            if (!rangeOk) Text("Pick pages between 1 and $pageCount.", color = MaterialTheme.colorScheme.error)
        }
        Text("What to make", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemKind.entries.forEach { k ->
                NotiFilterChip(selected = k in kinds, onClick = { kinds = if (k in kinds) kinds - k else kinds + k },
                    label = { Text(when (k) { ItemKind.Short -> "Questions"; ItemKind.Choice -> "Multiple choice"; ItemKind.Problem -> "Problems"; ItemKind.Flash -> "Flash cards" }) })
            }
        }
        Text("How many", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 25, 40).forEach { n -> NotiFilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") }) }
        }
        Text("Level", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Review", 2 to "Exam", 3 to "Hard").forEach { (v, l) -> NotiFilterChip(selected = level == v, onClick = { level = v }, label = { Text(l) }) }
        }
        OutlinedTextField(focus, { focus = it.take(200) }, label = { Text("Focus on (optional)") },
            placeholder = { Text("Projectile motion, chapter 3, integration by parts…") }, singleLine = true,
            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
        Text("Math and physics only, for now. The PDF goes to Mistral Large 4 with your key and is deleted there once the " +
            "questions are made.", style = MaterialTheme.typography.bodySmall)
    }
}

private const val NOTES = "\u0000notes"

@Composable
private fun SourceRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, placeholder: String = "") {
    OutlinedTextField(value, { v -> onChange(v.filter(Char::isDigit).take(5)) }, label = { Text(label) },
        placeholder = { Text(placeholder) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.small, modifier = Modifier.width(92.dp))
}

// ---- the deck ----

@Composable
private fun DeckDialog(study: StudyController, onClose: () -> Unit) {
    val data = study.data
    val now = System.currentTimeMillis()
    var confirm by remember { mutableStateOf<StudyItem?>(null) }
    NotiDialogFrame(onClose, title = { Text("Deck") }, maxWidth = 640.dp, actions = {
        NotiOutlinedButton(onClick = { onClose(); study.generateOpen = true }, enabled = study.generating == null) { Text("Make more") }
        NotiButton(onClick = onClose) { Text("Done") }
    }) {
        val due = data.dueCount(now)
        Text("${data.items.size} items · $due due · ${data.cards.size} on the board" +
            if (data.sources.isNotEmpty()) " · from ${data.sources.joinToString { it.name }}" else "")
        if (data.items.isEmpty()) Text("No items yet. Make questions from a PDF or from your notes.")
        data.topics().forEach { topic ->
            val items = data.items.filter { it.topic.ifBlank { "General" } == topic }
            Text(topic, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                items.forEach { item ->
                    val p = data.progressOf(item.id)
                    val outcome = p.outcome(item)
                    Row(Modifier.fillMaxWidth().clickable { onClose(); study.select(item.id); study.collapsed = false; study.open = true }
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
                        NotiIconButton(onClick = { confirm = item }) { Icon(Icons.Outlined.DeleteOutline, "Delete this item") }
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

/** LaTeX made readable enough for a one-line preview. */
internal fun plain(s: String): String = s.replace("$$", " ").replace("$", "")
    .replace(Regex("\\\\(frac|dfrac|tfrac)\\{([^{}]*)\\}\\{([^{}]*)\\}"), "($2)/($3)")
    .replace(Regex("\\\\(mathrm|text|mathbf|vec|hat|bar|operatorname)\\{([^{}]*)\\}"), "$2")
    .replace(Regex("\\\\(left|right|,|;|!|quad|qquad)"), " ")
    .replace(Regex("\\\\([A-Za-z]+)")) { m -> app.noti.study.Parser.SYMBOLS[m.groupValues[1]]?.first ?: m.groupValues[1] }
    .replace(Regex("[{}]"), "").replace(Regex("\\s+"), " ").trim()

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
        Text("A timed run: no hints, answers or sources. Answer multiple choice on the sheet; work the rest on the board and " +
            "loop it to submit. You see how it went at the end.")
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
    val verdicts = items.map { study.data.progressOf(it.id).outcome(it) }
    val score = verdicts.sumOf { when (it) { Verdict.Correct -> 1.0; Verdict.Partial -> 0.5; else -> 0.0 } }
    NotiDialogFrame(onClose, title = { Text("Exam done") }, maxWidth = 560.dp, actions = {
        NotiTextButton(onClick = onClose) { Text("Close") }
        val wrong = items.filterIndexed { i, _ -> verdicts[i] != Verdict.Correct }
        if (wrong.isNotEmpty()) NotiOutlinedButton(onClick = { onClose(); study.select(wrong.first().id); study.collapsed = false; study.open = true }) { Text("Review mistakes") }
        NotiButton(onClick = {
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
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(CardFace.statusColors(verdicts[i])?.let { Color(it.second) } ?: Color(0xFFCFCBD3)))
                Text("${i + 1}. ${plain(item.prompt)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                Text(verdicts[i]?.label ?: "Not answered", style = MaterialTheme.typography.labelMedium)
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
        NotiButton(enabled = source != null, onClick = {
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
        if (item.excerpt.isNotBlank()) Text(plain(item.excerpt), style = MaterialTheme.typography.bodySmall)
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
                        entry("Open on the sheet", Icons.Outlined.OpenInNew) { study.select(hit.card.itemId); study.collapsed = false; study.open = true }
                        entry("Move", Icons.Outlined.OpenWith) {
                            val sheet = placement()?.visiblePages?.firstOrNull() ?: return@entry
                            study.unpin(hit.card, sheet, geometry.faceInSheet().takeIf { it != IntOffset.Zero } ?: IntOffset(DefaultFaceX, DefaultFaceY))
                        }
                        entry("Try it again", Icons.Outlined.Replay) {
                            study.reset(hit.card.itemId); study.select(hit.card.itemId); study.collapsed = false; study.open = true
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
