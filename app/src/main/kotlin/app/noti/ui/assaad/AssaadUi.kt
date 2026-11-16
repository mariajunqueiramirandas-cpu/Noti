package app.noti.ui.assaad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.assaad.*
import app.noti.pdf.PagePlacement
import app.noti.study.ItemKind
import app.noti.study.StudyItem
import app.noti.study.MathText
import app.noti.R
import app.noti.ui.study.StudyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val LocalAssaadSession = staticCompositionLocalOf<AssaadSession?> { null }

@Composable
fun AssaadLibraryEntry() {
    val session = LocalAssaadSession.current ?: return
    OutlinedCard(onClick = { session.dialogOpen = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Devices, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Plataforma Assaad", style = MaterialTheme.typography.titleSmall)
                Text(if (session.useTabletBrowser) if (session.tabletVerified) "Connected on this tablet" else "Open Assaad on this tablet" else if (session.platformActive) "Active in this session" else if (session.paired) "Paired · ${session.savedQuestionCount} saved questions" else "Connect your browser to your canvas", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (session.useTabletBrowser || session.paired) "Open" else "Connect", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun AssaadPairingDialog(session: AssaadSession, onOpenBoard: (String) -> Unit) {
    if (!session.dialogOpen) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.codeExpiresAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    AlertDialog(onDismissRequest = { session.dialogOpen = false },
        title = { Text("Plataforma Assaad") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = session.useTabletBrowser, onClick = { session.useTablet(true) }, enabled = !session.hasPendingAnswer, label = { Text("This tablet") })
                FilterChip(selected = !session.useTabletBrowser, onClick = { session.useTablet(false) }, enabled = !session.hasPendingAnswer, label = { Text("Edge / Chrome") })
            }
            if (session.useTabletBrowser) {
                Text("Sign into Assaad here. Its browser can stay minimized while you work on the canvas.")
                Text(session.tabletStatus)
                Button(onClick = { session.dialogOpen = false; session.browserOpen = true }) { Text("Open Assaad") }
            } else if (session.paired) {
                Text(if (session.platformActive) "Assaad is active in your browser." else if (session.browserOnline) "Browser connected. Open Assaad to continue." else "Waiting for the paired browser.")
                Text("${session.savedQuestionCount} saved questions. Your drafts and drawings stay on this device.")
            } else {
                val code = session.code?.takeIf { session.codeExpiresAt > now }
                if (code != null) {
                    Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { contentDescription = "Pairing code $code" })
                    Text("Expires in ${(session.codeExpiresAt - now) / 60_000}:${((session.codeExpiresAt - now) / 1000 % 60).toString().padStart(2, '0')}")
                }
                if (code == null) Button(onClick = session::createCode, enabled = !session.busy) { Text(if (session.busy) "Connecting…" else "Generate pairing code") }
                Text("Server: noti.vendua.com.br", style = MaterialTheme.typography.bodySmall)
            }
            if (session.batches.isNotEmpty()) {
                Text("Saved question sets", style = MaterialTheme.typography.titleSmall)
                SavedAssaadSets(session); AssaadQueueControls(session)
            }
            session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (session.hasSession) TextButton(onClick = session::disconnect, enabled = !session.busy) { Text("Disconnect devices") }
        } },
        confirmButton = { Button(onClick = { session.dialogOpen = false; onOpenBoard(session.openBoard()) }) { Text("Open canvas") } },
        dismissButton = { TextButton(onClick = { session.dialogOpen = false }) { Text("Close") } })
}

@Composable
private fun SavedAssaadSets(session: AssaadSession) {
    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        session.batches.asReversed().forEach { batch ->
            OutlinedCard(onClick = { session.selectBatch(batch.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(batch.questions.firstOrNull()?.tags?.joinToString(" · ")?.takeIf { it.isNotBlank() } ?: "Assaad questions", maxLines = 1)
                        Text("${batch.questions.size} questions · ${org.json.JSONObject(batch.json).optString("capturedAt").take(10)}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (session.batch?.id == batch.id) Icon(Icons.Outlined.Check, "Selected set", Modifier.size(18.dp))
                    IconButton(onClick = { session.removeBatch(batch.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove saved set") }
                }
            }
        }
    }
}

@Composable
private fun AssaadQueueControls(session: AssaadSession) {
    Button(onClick = session::submitSavedAnswers, enabled = session.draftCount > 0, modifier = Modifier.fillMaxWidth()) { Text("Submit saved answers (${session.draftCount})") }
    if (session.queuedCount > 0) {
        Text("${session.queuedCount} in queue" + if (session.queuePaused) " · paused" else if (session.useTabletBrowser) " · waiting for Assaad" else " · waiting for Edge", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { if (session.queuePaused) session.resumeQueue() else session.pauseQueue() }) { Text(if (session.queuePaused) "Resume queue" else "Pause queue") }
            TextButton(onClick = session::cancelQueue) { Text("Cancel queued answers") }
        }
    }
    if (session.totalImages > 0) {
        Text("Images available offline: ${session.savedImages}/${session.totalImages}", style = MaterialTheme.typography.bodySmall)
        if (session.savedImages < session.totalImages) TextButton(onClick = session::saveImages, enabled = !session.savingImages) { Text(if (session.savingImages) "Saving images…" else "Save missing images") }
    }
    session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** A sub-panel above the native ink window, with the drawing canvas left free. */
@Composable
internal fun AssaadOverlay(notebookId: String, study: StudyController?, placement: () -> PagePlacement?) {
    val session = LocalAssaadSession.current ?: return
    var setsOpen by remember { mutableStateOf(false) }
    if (setsOpen) AlertDialog(onDismissRequest = { setsOpen = false }, title = { Text("Saved question sets") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SavedAssaadSets(session); AssaadQueueControls(session) } }, confirmButton = { TextButton(onClick = { setsOpen = false }) { Text("Done") } })
    if (session.boardId != notebookId) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = (maxWidth - 24.dp).coerceAtMost(430.dp)
        val height = (maxHeight * (if (maxHeight < 700.dp) 0.85f else 0.68f)).coerceAtLeast(200.dp)
        var offset by remember(notebookId) { mutableStateOf(IntOffset(0, with(density) { 74.dp.roundToPx() })) }
        val minX = -with(density) { (maxWidth - (if (session.sheetOpen) width else 210.dp) - 16.dp).roundToPx() }.coerceAtLeast(0)
        val maxY = with(density) { (maxHeight - 64.dp).roundToPx() }.coerceAtLeast(0)
        val safeOffset = IntOffset(offset.x.coerceIn(minX, 0), offset.y.coerceIn(0, maxY))
        Popup(alignment = Alignment.TopEnd, offset = safeOffset,
            properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = true)) {
            Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp, tonalElevation = 2.dp,
                modifier = Modifier.padding(8.dp).width(if (session.sheetOpen) width else 210.dp).heightIn(max = height)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().pointerInput(minX, maxY) { detectDragGestures { change, delta ->
                        change.consume(); offset = IntOffset((offset.x + delta.x.toInt()).coerceIn(minX, 0), (offset.y + delta.y.toInt()).coerceIn(0, maxY))
                    } }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DragIndicator, null, Modifier.size(18.dp))
                        Text("Plataforma Assaad", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (session.sheetOpen) TextButton(onClick = { setsOpen = true }) { Text("Saved sets") }
                        IconButton(onClick = { session.sheetOpen = !session.sheetOpen }, modifier = Modifier.size(32.dp)) {
                            Icon(if (session.sheetOpen) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull, if (session.sheetOpen) "Collapse Assaad questions" else "Open Assaad questions")
                        }
                    }
                    if (!session.sheetOpen) return@Column
                    Text(if (session.useTabletBrowser) if (session.tabletVerified) "On-tablet browser connected" else "Open Assaad and check the connection" else if (session.browserOnline) "Browser connected · drafts saved locally" else "Study offline · submit when your browser reconnects", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (session.useTabletBrowser) TextButton(onClick = { session.browserOpen = true }) { Text("Open Assaad") }
                    val question = session.current
                    if (question == null) {
                        Text("Use ‘Enviar questões ao Noti’ in the Assaad question bank. You can draw on the canvas while you wait.")
                        TextButton(onClick = { session.dialogOpen = true }) { Text("Connection") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { session.currentIndex-- }, enabled = session.currentIndex > 0) { Text("‹") }
                            Text("${session.currentIndex + 1} / ${session.batch?.questions?.size}", Modifier.weight(1f))
                            TextButton(onClick = { session.currentIndex++ }, enabled = session.currentIndex < (session.batch?.questions?.lastIndex ?: 0)) { Text("›") }
                        }
                        val selected = session.drafts[question.id]
                        val answer = session.answers[question.id]
                        val locked = answer?.status in listOf("queued", "pending", "unknown", "confirmed")
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(listOf(question.source, question.tags.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n"), style = MaterialTheme.typography.labelSmall)
                            PlatformText(question.prompt)
                            question.images.forEach { PlatformImage(it, session) }
                            question.alternatives.forEach { alternative ->
                                OutlinedCard(onClick = { session.saveDraft(alternative.id) }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = (if (locked) answer?.alternativeId else selected ?: answer?.alternativeId) == alternative.id, onClick = null)
                                            PlatformText("${alternative.label}) ${alternative.text}", Modifier.weight(1f))
                                        }
                                        alternative.images.forEach { PlatformImage(it, session) }
                                    }
                                }
                            }
                            answer?.let { Text(it.feedback, style = MaterialTheme.typography.bodySmall,
                                color = if (it.correct == false || it.status in listOf("failed", "unknown")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                            answer?.correctAlternativeId?.let { id -> question.alternatives.find { it.id == id }?.let {
                                Text("Correct option: ${it.label}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            } }
                        }
                        Button(onClick = { selected?.let(session::confirm) }, enabled = selected != null && !locked ,
                            modifier = Modifier.fillMaxWidth()) { Text(if (answer?.status == "pending") "Waiting for Assaad…" else if (answer?.status == "queued") "Queued for Assaad" else "Confirm in Assaad") }
                        Text(if (session.useTabletBrowser) "Drafts stay on this tablet. Confirm to submit through your Assaad login." else if (selected != null && answer == null) "Draft saved. Confirm to submit when Edge is available." else "Queued answers submit through your paired Edge login.", style = MaterialTheme.typography.labelSmall)
                        if (answer?.status == "unknown") TextButton(onClick = { session.allowRetry(question.id) }) { Text("Checked browser · allow another attempt") }
                        TextButton(onClick = {
                            val sheet = placement()?.visiblePages?.firstOrNull() ?: return@TextButton
                            val controller = study ?: return@TextButton
                            val item = StudyItem("assaad-${question.id}", ItemKind.Short, question.tags.firstOrNull() ?: "Assaad", question.printable,
                                excerpt = question.source, createdAt = System.currentTimeMillis())
                            controller.store.update { data -> data.copy(items = data.items.filterNot { it.id == item.id } + item) }
                            controller.select(item.id)
                            controller.print(with(density) { 40.dp.toPx() }, with(density) { 130.dp.toPx() }, width.value, density.density, sheet)
                            session.sheetOpen = false
                        }, enabled = study != null) { Text("Pin question on canvas") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformText(text: String, modifier: Modifier = Modifier) {
    if (!text.contains("\\(") && !text.contains('$')) { Text(text, modifier, style = MaterialTheme.typography.bodyMedium); return }
    val density = LocalDensity.current
    val resources = LocalContext.current.resources
    val prose = remember { resources.getFont(R.font.nunito_regular) }
    val bold = remember { resources.getFont(R.font.nunito_bold) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = text }) {
        val width = with(density) { maxWidth.toPx() }
        val size = with(density) { 14.sp.toPx() }
        val block = remember(text, width, size, color) { MathText.layout(text, width, MathText.Style(size, color, prose, bold)) }
        Canvas(Modifier.fillMaxWidth().height(with(density) { block.height.toDp() })) {
            block.draw(drawContext.canvas.nativeCanvas, 0f, 0f)
        }
    }
}

@Composable
private fun PlatformImage(image: AssaadImage, session: AssaadSession) {
    val bitmap by produceState<Bitmap?>(null, image.url) {
        value = withContext(Dispatchers.IO) { runCatching {
            val url = image.url?.takeIf(::safeImageUrl) ?: return@runCatching null
            val bytes = session.imageBytes(url) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                inSampleSize = sample
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), image.alt.ifBlank { "Question image" }, Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
        ?: Text(image.alt.ifBlank { "Image unavailable. Check the question in the browser." }, style = MaterialTheme.typography.bodySmall)
}
