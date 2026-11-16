package app.noti.assaad

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Anonymous image copies for saved sets. Serialized, bounded, and kept across app restarts. */
internal class AssaadMediaCache(context: Context, private val http: OkHttpClient) {
    private val directory = File(context.filesDir, "assaad_media").apply { mkdirs() }
    private val lock = Mutex()
    private fun file(url: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
    fun contains(url: String) = file(url).let { it.isFile && it.length() in 1..4_194_304 }
    suspend fun retain(urls: List<String>) = lock.withLock {
        val retained = urls.map { file(it).name }.toSet()
        directory.listFiles()?.filter { it.isFile && it.name !in retained }?.forEach { it.delete() }
    }
    suspend fun load(url: String): ByteArray? = lock.withLock {
        if (!safeImageUrl(url)) return@withLock null
        val target = file(url)
        if (contains(url)) return@withLock runCatching { target.readBytes() }.getOrNull()
        runCatching {
            // No relay bearer, Assaad token, cookie jar, or redirects are used for media.
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful || response.header("Content-Type")?.startsWith("image/") != true) return@use null
                val bytes = response.body?.byteStream()?.readNBytes(4_194_305) ?: return@use null
                if (bytes.size !in 1..4_194_304) return@use null
                if ((directory.listFiles()?.sumOf { it.length() } ?: 0L) + bytes.size > 67_108_864L) return@use null
                writeAssaadFile(target, bytes)
                bytes
            }
        }.getOrNull()
    }
}
