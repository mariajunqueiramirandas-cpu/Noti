package app.noti.assaad

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One writer per target: sync the complete replacement before publishing it atomically. */
internal fun writeAssaadFile(file: File, bytes: ByteArray) {
    val pending = File(file.parentFile, file.name + ".pending")
    FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
    try {
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
