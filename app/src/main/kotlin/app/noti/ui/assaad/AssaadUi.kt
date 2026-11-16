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
import okhttp3.Request

val LocalAssaadSession = staticCompositionLocalOf<AssaadSession?> { null }

@Composable
fun AssaadLibraryEntry() {
    val session = LocalAssaadSession.current ?: return
    OutlinedCard(onClick = { session.dialogOpen = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Devices, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Plataforma Assaad", style = MaterialTheme.typography.titleSmall)
                Text(if (session.platformActive) "Active in this session" else if (session.paired) "Paired · ${session.batch?.questions?.size ?: 0} questions" else "Connect your browser to your canvas", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (session.paired) "Open" else "Connect", color = MaterialTheme.colorScheme.primary)
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
            Text("Pair the extension in your Edge or Chrome browser, then send questions from Assaad to study on your canvas.")
            if (session.paired) {
                Text(if (session.platformActive) "Assaad is active in your browser." else if (session.browserOnline) "Browser connected. Open Assaad to continue." else "Waiting for the paired browser.")
                Text("${session.batch?.questions?.size ?: 0} questions received. Your drawings stay on this device.")
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
            session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (session.hasSession) TextButton(onClick = session::disconnect, enabled = !session.busy) { Text("Disconnect devices") }
        } },
        confirmButton = { Button(onClick = { session.dialogOpen = false; onOpenBoard(session.openBoard()) }) { Text("Open canvas") } },
        dismissButton = { TextButton(onClick = { session.dialogOpen = false }) { Text("Close") } })
}

/** A sub-panel above the native ink window, with the drawing canvas left free. */
@Composable
internal fun AssaadOverlay(notebookId: String, study: StudyController?, placement: () -> PagePlacement?) {
    val session = LocalAssaadSession.current ?: return
    if (session.boardId != notebookId) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = (maxWidth - 24.dp).coerceAtMost(430.dp)
        val height = (maxHeight * 0.58f).coerceAtLeast(200.dp)
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
                        IconButton(onClick = { session.sheetOpen = !session.sheetOpen }, modifier = Modifier.size(32.dp)) {
                            Icon(if (session.sheetOpen) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull, if (session.sheetOpen) "Collapse Assaad questions" else "Open Assaad questions")
                        }
                    }
                    if (!session.sheetOpen) return@Column
                    Text(if (session.platformActive) "Active in this session" else "Browser tab inactive · saved questions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                        var selected by remember(question.id, session.batch?.id) { mutableStateOf<String?>(null) }
                        val answer = session.answers[question.id]
                        val locked = answer?.status in listOf("pending", "unknown", "confirmed")
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(listOf(question.source, question.tags.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n"), style = MaterialTheme.typography.labelSmall)
                            PlatformText(question.prompt)
                            question.images.forEach { PlatformImage(it, session) }
                            question.alternatives.forEach { alternative ->
                                OutlinedCard(onClick = { selected = alternative.id }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
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
                        Button(onClick = { selected?.let(session::confirm) }, enabled = selected != null && !locked && session.connected && session.paired && session.browserOnline && session.platformActive,
                            modifier = Modifier.fillMaxWidth()) { Text(if (answer?.status == "pending") "Waiting for Assaad…" else "Confirm in Assaad") }
                        Text("Confirms your choice on the open website.", style = MaterialTheme.typography.labelSmall)
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
            session.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.byteStream()?.readNBytes(2_097_153) ?: return@use null
                if (bytes.size > 2_097_152) return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                    inSampleSize = sample
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), image.alt.ifBlank { "Question image" }, Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
        ?: Text(image.alt.ifBlank { "Image unavailable. Check the question in the browser." }, style = MaterialTheme.typography.bodySmall)
}
