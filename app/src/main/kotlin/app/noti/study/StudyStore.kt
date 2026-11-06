package app.noti.study

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * A board's [BoardStudy] as Compose state, written to its own JSON file after every
 * change (in order, off the main thread, with AtomicFile, like the ink). A file that
 * can't be read is kept aside as `.broken` instead of being overwritten.
 * Main thread, except [load] (IO).
 */
class StudyStore private constructor(private val file: File, initial: BoardStudy, val loadError: Boolean) {
    var data by mutableStateOf(initial)
        private set
    /** Called on the main thread after each write, with whether it worked. */
    var onSaved: ((Boolean) -> Unit)? = null

    fun update(f: (BoardStudy) -> BoardStudy) {
        val next = f(data)
        if (next == data) return
        data = next
        val text = next.encode()
        writer.execute {
            val ok = write(file, text)
            main.post { onSaved?.invoke(ok) }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "noti-study-save").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())

        /** IO. */
        fun load(file: File): StudyStore {
            val atomic = AtomicFile(file)
            if (!file.exists() && !File(file.path + ".bak").exists()) return StudyStore(file, BoardStudy(), false)
            return try {
                StudyStore(file, BoardStudy.decode(String(atomic.readFully(), Charsets.UTF_8)), false)
            } catch (e: Exception) {
                runCatching { file.copyTo(File(file.path + ".broken"), overwrite = true) }
                StudyStore(file, BoardStudy(), true)
            }
        }

        /** IO: what's due on a board, without keeping it open (library badges). */
        fun dueCount(file: File, now: Long): Int =
            runCatching { BoardStudy.decode(String(AtomicFile(file).readFully(), Charsets.UTF_8)).dueCount(now) }.getOrDefault(0)

        /** IO, in order with saves. */
        fun delete(file: File) { writer.execute { AtomicFile(file).delete(); File(file.path + ".broken").delete() } }

        /** IO, in order with saves. */
        fun copy(from: File, to: File) {
            writer.submit {
                if (from.exists()) runCatching { write(to, String(AtomicFile(from).readFully(), Charsets.UTF_8)) }
            }.get()
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
