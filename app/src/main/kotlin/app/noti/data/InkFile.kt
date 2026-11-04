package app.noti.data

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/** Reads on IO; snapshots arrive from the ink thread and are written in order off it. */
class InkFile(val file: File, val initial: ByteArray?) {
    @Volatile var onSaved: ((Boolean) -> Unit)? = null
    fun save(bytes: ByteArray, onResult: ((Boolean) -> Unit)? = null) {
        writer.execute {
            val atomic = AtomicFile(file)
            val ok = runCatching {
                val out = atomic.startWrite()
                try { out.write(bytes); atomic.finishWrite(out) }
                catch (e: Exception) { atomic.failWrite(out); throw e }
            }.isSuccess
            main.post { onSaved?.invoke(ok); onResult?.invoke(ok) }
        }
    }
    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "noti-save").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
        fun delete(file: File) { writer.execute { AtomicFile(file).delete() } }
        fun open(file: File): InkFile = writer.submit<InkFile> {
            InkFile(file, if (file.exists() || File(file.path + ".bak").exists()) AtomicFile(file).readFully() else null)
        }.get()
    }
}
