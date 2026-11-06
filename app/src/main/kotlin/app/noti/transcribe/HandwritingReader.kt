package app.noti.transcribe

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import java.io.Closeable
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The handwriting languages ML Kit can read on the device. */
object HandwritingLanguages {
    /** Every language with a text model, as (tag, name in the device language), by name. */
    fun available(): List<Pair<String, String>> =
        DigitalInkRecognitionModelIdentifier.allModelIdentifiers()
            .filter(::isText)
            .map { it.languageTag to displayName(it.languageTag) }
            .sortedBy { it.second.lowercase() }

    /**
     * The model for [tag], or for the device language when [tag] is empty: the exact
     * language and region if there is one, else the same language elsewhere, else US English.
     */
    fun resolve(tag: String): DigitalInkRecognitionModelIdentifier {
        val wanted = tag.ifEmpty { Locale.getDefault().toLanguageTag() }
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(wanted) }.getOrNull()
            ?.takeIf(::isText)?.let { return it }
        val language = Locale.forLanguageTag(wanted).language
        runCatching { DigitalInkRecognitionModelIdentifier.forLanguageSubtag(language) }.getOrNull().orEmpty()
            .filter(::isText).minByOrNull { it.languageTag.length }?.let { return it }
        return DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!
    }

    fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase(Locale.forLanguageTag(tag)) }

    /** Plain writing; not emoji, shapes, gestures or autodraw. */
    private fun isText(id: DigitalInkRecognitionModelIdentifier) =
        id.languageSubtag != "zxx" && !id.languageTag.contains("-x-")
}

/**
 * Reads lines of handwriting with ML Kit's on-device recognizer. The language's model
 * (around 20 MB) is downloaded once, then everything runs offline on the tablet.
 * Main thread; close when done.
 */
class HandwritingReader(val language: DigitalInkRecognitionModelIdentifier) : Closeable {
    private val model = DigitalInkRecognitionModel.builder(language).build()
    private val models = RemoteModelManager.getInstance()
    private val recognizer by lazy { DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()) }

    suspend fun isDownloaded(): Boolean = models.isModelDownloaded(model).await()

    suspend fun download() { models.download(model, DownloadConditions.Builder().build()).await() }

    /** One string per line. Each line is read with the end of the text before it as context. */
    suspend fun read(lines: List<InkLine>): List<String> {
        val pitch = InkLines.pitch(lines)
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            val ink = Ink.builder()
            for (stroke in line.strokes) {
                val builder = Ink.Stroke.builder()
                for (i in stroke.points.indices step 2) builder.addPoint(Ink.Point.create(stroke.points[i], stroke.points[i + 1]))
                ink.addStroke(builder.build())
            }
            // The writing area tells small letters from capitals; a line's room is its pitch.
            val context = RecognitionContext.builder()
                .setPreContext(out.joinToString(" ").takeLast(20))
                .setWritingArea(WritingArea(line.width.coerceAtLeast(1f), pitch.coerceAtLeast(line.height).coerceAtLeast(1f)))
                .build()
            out += recognizer.recognize(ink.build(), context).await().candidates.firstOrNull()?.text.orEmpty()
        }
        return out
    }

    override fun close() = recognizer.close()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
