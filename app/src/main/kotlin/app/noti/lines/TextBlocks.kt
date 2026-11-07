package app.noti.lines

import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.noti.transcribe.SelectedStroke
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * One typed word on the page, recognisable among the ink: the engine keeps a stroke's
 * capsules exactly as given (and moves them all by the same amount), so its capsule count,
 * color and first point find it again, even after a save or a lasso move.
 */
data class WordMark(val capsules: Int, val x: Float, val y: Float)

/**
 * Text typed on lines, kept next to its ink so it can be reopened and edited
 * (docs/TYPE_ON_LINES.md §8.2). [lines] and [words] are where they were placed; a block whose
 * words were moved together is found at its new place, and one whose words were partly
 * erased is no longer a block, just ink.
 */
data class TextBlock(
    val id: String,
    val page: Int,
    val text: String,
    val style: TypeStyle,
    /** The em it was placed at (the style's, or the fitted one). */
    val em: Float,
    /** Premultiplied RGBA, as the engine stores it. */
    val color: FloatArray,
    val lines: List<WriteLine>,
    val words: List<WordMark>,
) {
    override fun equals(other: Any?) = other is TextBlock && id == other.id && text == other.text && page == other.page &&
        words == other.words && lines == other.lines && style == other.style && color.contentEquals(other.color)
    override fun hashCode() = id.hashCode()

    companion object {
        fun newId() = UUID.randomUUID().toString()

        /** The marks of [capsules] cut into strokes by [sizes] (TextInk output, in engine units). */
        fun marks(capsules: FloatArray, sizes: IntArray): List<WordMark> {
            var at = 0
            return sizes.map { n -> WordMark(n, capsules[at * 6], capsules[at * 6 + 1]).also { at += n } }
        }
    }
}

/** A block found on the page: its strokes' ids now, and how far it moved since it was placed. */
class BlockMatch(val block: TextBlock, val ids: IntArray, val dx: Float, val dy: Float, val strokes: List<SelectedStroke>) {
    /** The block's lines where its words are now. */
    val lines get() = block.lines.map { it.moved(dx, dy) }
    fun contains(x: Float, y: Float, margin: Float) = strokes.any {
        x >= it.x0 - margin && x <= it.x1 + margin && y >= it.y0 - margin && y <= it.y1 + margin
    }
}

object TextBlocksCodec {
    fun encode(blocks: List<TextBlock>): String = JSONObject().put("version", 1).put("blocks", JSONArray().apply {
        for (b in blocks) put(JSONObject()
            .put("id", b.id).put("page", b.page).put("text", b.text).put("em", b.em.toDouble())
            .put("color", floats(b.color))
            .put("style", JSONObject().put("size", b.style.size?.toDouble() ?: JSONObject.NULL)
                .put("align", b.style.align.name).put("bold", b.style.bold).put("overflow", b.style.overflow.name))
            .put("lines", JSONArray().apply {
                for (l in b.lines) put(JSONObject().put("x0", l.x0.toDouble()).put("x1", l.x1.toDouble()).put("y", l.y.toDouble())
                    .put("slope", l.slope.toDouble()).put("room", l.room.toDouble()).put("start", l.start.toDouble())
                    .put("kind", l.kind.name).apply { l.cells?.let { put("cells", floats(it)) } })
            })
            .put("words", JSONArray().apply { for (w in b.words) put(JSONArray().put(w.capsules).put(w.x.toDouble()).put(w.y.toDouble())) }))
    }).toString()

    fun decode(text: String): List<TextBlock> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("blocks") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val st = o.getJSONObject("style")
            val lines = o.getJSONArray("lines")
            val words = o.getJSONArray("words")
            TextBlock(
                id = o.getString("id"), page = o.getInt("page"), text = o.getString("text"), em = o.getDouble("em").toFloat(),
                color = floatsOf(o.getJSONArray("color")),
                style = TypeStyle(
                    size = if (st.isNull("size")) null else st.getDouble("size").toFloat(),
                    align = enumOr(st.optString("align"), TextAlign.Left), bold = st.optBoolean("bold"),
                    overflow = enumOr(st.optString("overflow"), Overflow.Stop)),
                lines = (0 until lines.length()).map { j ->
                    val l = lines.getJSONObject(j)
                    WriteLine(l.getDouble("x0").toFloat(), l.getDouble("x1").toFloat(), l.getDouble("y").toFloat(),
                        l.getDouble("slope").toFloat(), l.getDouble("room").toFloat(), l.getDouble("start").toFloat(),
                        enumOr(l.optString("kind"), LineKind.Rule), cells = l.optJSONArray("cells")?.let(::floatsOf))
                },
                words = (0 until words.length()).map { j ->
                    val w = words.getJSONArray(j)
                    WordMark(w.getInt(0), w.getDouble(1).toFloat(), w.getDouble(2).toFloat())
                },
            )
        }
    }

    private fun floats(a: FloatArray) = JSONArray().apply { for (v in a) put(v.toDouble()) }
    private fun floatsOf(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

/**
 * Finds [blocks] among a page's ink (InkLayer.pageInk, parsed): a block is there when every
 * one of its words is, all moved by the same amount.
 */
object BlockMatcher {
    private const val EPS = 0.05f

    fun find(blocks: List<TextBlock>, page: Int, ink: List<SelectedStroke>): List<BlockMatch> {
        val onPage = ink.filter { it.page == page }
        val byCount = onPage.groupBy { it.points.size / 2 - 1 }
        val out = ArrayList<BlockMatch>()
        for (b in blocks) {
            if (b.page != page || b.words.isEmpty()) continue
            val first = b.words.first()
            for (cand in byCount[first.capsules].orEmpty()) {
                if (!sameColor(cand.color, b.color)) continue
                val dx = cand.points[0] - first.x; val dy = cand.points[1] - first.y
                val used = HashSet<Int>()
                val found = ArrayList<SelectedStroke>()
                for (w in b.words) {
                    val s = byCount[w.capsules].orEmpty().firstOrNull { s ->
                        s.id !in used && sameColor(s.color, b.color) &&
                            abs(s.points[0] - w.x - dx) <= EPS && abs(s.points[1] - w.y - dy) <= EPS
                    } ?: break
                    used += s.id; found += s
                }
                if (found.size == b.words.size) {
                    out += BlockMatch(b, found.map { it.id }.toIntArray(), dx, dy, found)
                    break
                }
            }
        }
        return out
    }

    private fun sameColor(a: FloatArray, b: FloatArray) = a.size == 4 && b.size == 4 && (0 until 4).all { abs(a[it] - b[it]) < 1e-3f }
}

/**
 * A notebook's text blocks as Compose state, written to its own JSON file after every change
 * (in order, off the main thread, with AtomicFile, like Study boards). Main thread, except [load].
 */
class TextBlockStore private constructor(private val file: File, initial: List<TextBlock>) {
    var blocks by mutableStateOf(initial)
        private set

    fun put(block: TextBlock) = update { list -> list.filterNot { it.id == block.id } + block }
    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    private fun update(f: (List<TextBlock>) -> List<TextBlock>) {
        val next = f(blocks)
        if (next == blocks) return
        blocks = next
        val text = TextBlocksCodec.encode(next)
        writer.execute { write(file, text) }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "noti-type-save").apply { isDaemon = true } }

        /** IO. An unreadable file is kept aside as `.broken`, and the notebook starts with no blocks. */
        fun load(file: File): TextBlockStore {
            if (!file.exists() && !File(file.path + ".bak").exists()) return TextBlockStore(file, emptyList())
            return try {
                TextBlockStore(file, TextBlocksCodec.decode(String(AtomicFile(file).readFully(), Charsets.UTF_8)))
            } catch (e: Exception) {
                runCatching { file.copyTo(File(file.path + ".broken"), overwrite = true) }
                TextBlockStore(file, emptyList())
            }
        }

        /** IO: blocks without keeping a store open (PDF export). */
        fun read(file: File): List<TextBlock> =
            runCatching { TextBlocksCodec.decode(String(AtomicFile(file).readFully(), Charsets.UTF_8)) }.getOrDefault(emptyList())

        /** IO, in order with saves. */
        fun delete(file: File) { writer.execute { AtomicFile(file).delete(); File(file.path + ".broken").delete() } }

        /** IO, in order with saves. */
        fun copy(from: File, to: File) {
            writer.submit { if (from.exists()) runCatching { write(to, String(AtomicFile(from).readFully(), Charsets.UTF_8)) } }.get()
        }

        private fun write(file: File, text: String): Boolean = runCatching {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
        }.isSuccess
    }
}
