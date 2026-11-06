package app.noti.ui.editor

import app.noti.ui.common.NotiTextButton as TextButton
import app.noti.ui.common.NotiOutlinedButton as OutlinedButton

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.noti.R
import app.noti.data.EndlessCanvas
import app.noti.ink.InkLayer
import app.noti.transcribe.HandwritingLanguages
import app.noti.transcribe.HandwritingReader
import app.noti.transcribe.InkLine
import app.noti.transcribe.InkLines
import app.noti.transcribe.PageFrame
import app.noti.transcribe.TextInk
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiDialogFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** InkLayer.documentAction: drop the selection (jni_bridge.cpp nativeDocumentAction). */
private const val ClearSelection = 6

/** One loop's handwriting, on its way to text. Main thread. */
@Stable
internal class TranscribeSession(
    val page: Int,
    val lines: List<InkLine>,
    /** The strokes that were read, which "Replace on page" swaps for the text. */
    val ids: IntArray,
    /** The most-used ink color among them, premultiplied: the typed text's color. */
    val color: FloatArray,
    /** [lines] are in this frame's units; typed text is placed back through it. */
    val frame: PageFrame = PageFrame.Identity,
) {
    sealed interface Phase {
        data object Preparing : Phase
        data class Downloading(val language: String) : Phase
        data object Reading : Phase
        data object Done : Phase
        data class Failed(val message: String) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Preparing)
        internal set
    var text by mutableStateOf("")
    var replacing by mutableStateOf(false)

    suspend fun read(languageTag: String) {
        val reader = try { HandwritingReader(HandwritingLanguages.resolve(languageTag)) } catch (e: Exception) {
            phase = Phase.Failed("Handwriting recognition isn't available on this device.")
            return
        }
        val language = HandwritingLanguages.displayName(reader.language.languageTag)
        try {
            phase = Phase.Preparing
            if (!reader.isDownloaded()) {
                phase = Phase.Downloading(language)
                try { reader.download() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    phase = Phase.Failed("Couldn't download $language handwriting. Check your connection and try again.")
                    return
                }
            }
            phase = Phase.Reading
            text = reader.read(lines).joinToString("\n").trim()
            phase = Phase.Done
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            phase = Phase.Failed("Couldn't read this handwriting. Try a smaller loop, or check the handwriting language in Settings.")
        } finally {
            reader.close()
        }
    }

    companion object {
        /**
         * From InkLayer.selectionInk; null when the loop held no pen writing (highlights don't count).
         * On the Endless [canvas] the writing is read in a one-screen page frame.
         */
        fun from(selection: FloatArray, canvas: Boolean = false): TranscribeSession? {
            val writing = InkLines.parse(selection).filter { it.isWriting }
            if (writing.isEmpty()) return null
            // A loop stays on one page; keep the first stroke's in any case.
            val page = writing.first().page
            val onPage = writing.filter { it.page == page }
            val color = onPage.groupBy { it.color.toList() }
                .maxBy { (_, strokes) -> strokes.sumOf { it.points.size } }.key.toFloatArray()
            val frame = if (canvas) PageFrame.canvas(onPage, EndlessCanvas.SPAN) else PageFrame.Identity
            return TranscribeSession(page, InkLines.group(onPage.map(frame::toPage)), onPage.map { it.id }.toIntArray(), color, frame)
        }
    }
}

/**
 * The Transcribe tool's half outside the engine: each loop the engine reports (document
 * state [9], see jni_bridge.cpp nativeDocumentState) reads the ink it selected and opens
 * the panel with the text.
 */
@Composable
internal fun TranscribeTool(layer: InkLayer?, documentState: DoubleArray, languageTag: String, canvas: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serial = documentState.getOrNull(9)?.toLong() ?: 0L
    // Each canvas's engine counts from zero.
    var handled by remember(layer) { mutableLongStateOf(0L) }
    var session by remember { mutableStateOf<TranscribeSession?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun close() {
        job?.cancel(); job = null
        session = null
        layer?.documentAction(ClearSelection)
    }
    LaunchedEffect(layer, serial) {
        val current = layer ?: return@LaunchedEffect
        if (serial <= handled) return@LaunchedEffect
        handled = serial
        current.selectionInk { data ->
            val next = TranscribeSession.from(data, canvas)
            if (next == null) {
                current.documentAction(ClearSelection)
                Toast.makeText(context, if ((data.firstOrNull() ?: 0f) > 0f) "Only highlighter ink in that loop. Loop around pen writing to turn it into text."
                    else "Draw a loop around your handwriting to turn it into text.", Toast.LENGTH_SHORT).show()
                return@selectionInk
            }
            job?.cancel()
            session = next
            job = scope.launch { next.read(languageTag) }
        }
    }
    val open = session ?: return
    TranscribePanel(open, onClose = ::close, onCopy = { copy(context, open.text) }, onReplace = {
        val current = layer ?: return@TranscribePanel
        open.replacing = true
        scope.launch {
            val typeface = context.resources.getFont(R.font.nunito_regular)
            val strokes = withContext(Dispatchers.Default) { open.frame.toEngine(TextInk(typeface).layout(open.text, open.lines)) }
            current.replaceStrokes(open.ids, open.page, open.color, strokes.capsules, strokes.sizes) { ok ->
                open.replacing = false
                if (ok) {
                    close()
                    Toast.makeText(context, "Replaced with text. Undo brings your handwriting back.", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "Couldn't replace the handwriting: this notebook has reached its ink capacity.", Toast.LENGTH_LONG).show()
            }
        }
    })
}

private fun copy(context: Context, text: String) {
    // Android 13+ confirms the copy itself.
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Handwriting", text))
}

/** Shows what the loop said: edit it, copy it, or swap the handwriting for it on the page. */
@Composable
internal fun TranscribePanel(session: TranscribeSession, onClose: () -> Unit, onCopy: () -> Unit, onReplace: () -> Unit) {
    val phase = session.phase
    val done = phase == TranscribeSession.Phase.Done
    NotiDialogFrame(onDismissRequest = onClose, title = { Text("Handwriting to text") }, maxWidth = 560.dp, actions = {
        TextButton(onClick = onClose) { Text("Close") }
        OutlinedButton(onClick = onCopy, enabled = done && session.text.isNotBlank()) {
            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
        }
        NotiButton(onClick = onReplace, enabled = done && session.text.isNotBlank() && !session.replacing) { Text("Replace on page") }
    }) {
        when (phase) {
            TranscribeSession.Phase.Preparing, TranscribeSession.Phase.Reading -> Working("Reading your handwriting…")
            is TranscribeSession.Phase.Downloading -> Working(
                "Getting ${phase.language} handwriting ready. This downloads once (about 20 MB), then works offline.")
            is TranscribeSession.Phase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            TranscribeSession.Phase.Done -> {
                OutlinedTextField(
                    value = session.text, onValueChange = { session.text = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 12,
                    shape = MaterialTheme.shapes.small,
                    placeholder = { Text("No words found. Type them here, or close and try another loop.") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                Text("Fix anything it misread before you copy or replace. Replacing swaps the handwriting for typed ink; Undo brings it back.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Working(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(message)
    }
}
