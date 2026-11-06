package app.noti.study

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Something went wrong talking to Mistral; [message] is written for the person using the app. */
class StudyApiException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/**
 * Mistral's API, for Mistral Large 4: PDFs are uploaded to the Files API (purpose "ocr")
 * and passed to chat completions as a signed `document_url`, images as `image_url` data
 * URLs, and replies are constrained to a JSON schema and streamed (server-sent events).
 * Blocking; call on a background thread. The key never leaves this class except in the
 * Authorization header.
 */
class MistralClient(
    private val apiKey: String,
    private val base: String = "https://api.mistral.ai/v1",
    private val transport: Transport = Transport.Default,
) {
    /** The HTTP layer, swappable for tests. */
    interface Transport {
        fun open(method: String, url: String, headers: Map<String, String>, body: ((OutputStream) -> Unit)?, contentType: String?): Reply

        companion object {
            val Default = object : Transport {
                override fun open(method: String, url: String, headers: Map<String, String>, body: ((OutputStream) -> Unit)?,
                    contentType: String?): Reply {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = method
                    c.connectTimeout = 20_000
                    // Generating a long deck streams for minutes; the timeout is between bytes.
                    c.readTimeout = 180_000
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (body != null) {
                        c.doOutput = true
                        contentType?.let { c.setRequestProperty("Content-Type", it) }
                        c.setChunkedStreamingMode(64 * 1024)
                        c.outputStream.use(body)
                    }
                    val status = c.responseCode
                    val stream = if (status in 200..299) c.inputStream else c.errorStream
                    return Reply(status, stream ?: java.io.ByteArrayInputStream(ByteArray(0))) { c.disconnect() }
                }
            }
        }
    }

    class Reply(val status: Int, val body: InputStream, val close: () -> Unit)

    private val auth get() = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json")

    /** Whether the key works (lists models). */
    fun check() {
        call("GET", "$base/models", null, null).use { }
    }

    /** Uploads a PDF for document understanding; returns its file id. */
    fun uploadPdf(file: File, name: String): String {
        val boundary = "noti-" + UUID.randomUUID().toString()
        val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").ifBlank { "document" }.let { if (it.endsWith(".pdf")) it else "$it.pdf" }
        val reply = call("POST", "$base/files", "multipart/form-data; boundary=$boundary") { out ->
            out.write(multipartField(boundary, "purpose", "ocr"))
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safe\"\r\n" +
                "Content-Type: application/pdf\r\n\r\n").toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return reply.use { JSONObject(it.text()).getString("id") }
    }

    fun signedUrl(fileId: String, hours: Int = 2): String =
        call("GET", "$base/files/$fileId/url?expiry=$hours", null, null).use { JSONObject(it.text()).getString("url") }

    fun deleteFile(fileId: String) {
        runCatching { call("DELETE", "$base/files/$fileId", null, null).use { } }
    }

    /**
     * One chat completion constrained to [schema], streamed: [onText] gets the reply so far
     * after every chunk. Returns the whole reply text. [cancelled] is polled between chunks.
     */
    fun chat(system: String, user: JSONArray, schemaName: String, schema: JSONObject, onText: (String) -> Unit = {},
        cancelled: () -> Boolean = { false }, temperature: Double = 0.3): String {
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", schemaName).put("schema", schema).put("strict", true)))
            .put("temperature", temperature)
            .put("stream", true)
        val reply = call("POST", "$base/chat/completions", "application/json") { it.write(body.toString().toByteArray()) }
        val text = StringBuilder()
        reply.use { r ->
            val reader = BufferedReader(InputStreamReader(r.body, Charsets.UTF_8))
            val events = Sse()
            while (true) {
                if (cancelled()) throw StudyApiException("Stopped.")
                val line = reader.readLine() ?: break
                val data = events.line(line) ?: continue
                if (data == "[DONE]") break
                val delta = Sse.delta(data)
                if (delta.error != null) throw StudyApiException(delta.error)
                if (delta.text.isNotEmpty()) { text.append(delta.text); onText(text.toString()) }
                if (delta.finish == "length") throw StudyApiException("The reply was cut short. Try asking for fewer items.")
            }
        }
        return text.toString()
    }

    private class Open(val reply: Reply) : AutoCloseable {
        fun text() = reply.body.bufferedReader(Charsets.UTF_8).readText()
        override fun close() { runCatching { reply.body.close() }; reply.close() }
        val body get() = reply.body
    }

    private fun call(method: String, url: String, contentType: String?, body: ((OutputStream) -> Unit)?): Open {
        val reply = try {
            transport.open(method, url, auth, body, contentType)
        } catch (e: StudyApiException) { throw e } catch (e: IOException) {
            throw StudyApiException("Couldn't reach Mistral. Check your connection and try again.", 0, e)
        }
        if (reply.status !in 200..299) {
            val detail = runCatching { reply.body.bufferedReader().readText() }.getOrDefault("")
            reply.close()
            throw StudyApiException(errorMessage(reply.status, detail), reply.status)
        }
        return Open(reply)
    }

    companion object {
        /** Mistral Large 4. */
        const val MODEL = "mistral-large-4"
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

        private fun multipartField(boundary: String, name: String, value: String) =
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()

        fun errorMessage(status: Int, body: String): String {
            val detail = runCatching {
                val o = JSONObject(body)
                o.optString("message").ifEmpty { o.optJSONObject("error")?.optString("message").orEmpty() }
                    .ifEmpty { o.optString("detail") }
            }.getOrDefault("").take(200)
            return when (status) {
                401, 403 -> "Mistral didn't accept the API key. Check it in Settings → Study."
                402 -> "Your Mistral account needs billing set up to use this model."
                413 -> "That PDF is too large to send. Choose a page range."
                429 -> "Mistral is busy or your plan's limit was reached. Try again in a minute."
                in 500..599 -> "Mistral had a problem ($status). Try again in a moment."
                else -> "Mistral couldn't do that ($status)" + if (detail.isNotBlank()) ": $detail" else "."
            }
        }
    }
}

/** Server-sent events: collects `data:` lines into events. */
internal class Sse {
    private val data = StringBuilder()
    private var has = false

    /** Feeds one line; returns an event's data when a blank line ends it (or the line is a lone `data:` line). */
    fun line(line: String): String? {
        if (line.isEmpty()) {
            if (!has) return null
            val out = data.toString(); data.clear(); has = false
            return out
        }
        if (line.startsWith(":")) return null
        if (line.startsWith("data:")) {
            val value = line.removePrefix("data:").removePrefix(" ")
            // Mistral sends one JSON object per data line; deliver it at once rather than wait for the blank line.
            if (!has && (value == "[DONE]" || value.startsWith("{"))) {
                if (runCatching { JSONObject(value) }.isSuccess || value == "[DONE]") return value
            }
            if (has) data.append('\n')
            data.append(value); has = true
        }
        return null
    }

    class Delta(val text: String, val finish: String?, val error: String?)

    companion object {
        /** The text a chunk adds: `content` is a string, or a list of chunks of which "text" ones count. */
        fun delta(json: String): Delta {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return Delta("", null, null)
            o.optJSONObject("error")?.let { return Delta("", null, it.optString("message").ifEmpty { "Mistral reported an error." }) }
            val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return Delta("", null, null)
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            val finish = choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }
            val content = delta?.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val c = content.optJSONObject(i) ?: continue
                        if (c.optString("type") == "text") append(c.optString("text"))
                    }
                }
                else -> ""
            }
            return Delta(text, finish, null)
        }
    }
}

/**
 * Pulls finished objects out of a JSON array while it is still streaming: the elements
 * of the array under [key] that are complete so far, as raw JSON. String-aware, so braces
 * inside text don't confuse it.
 */
internal object PartialJson {
    fun completeElements(text: String, key: String): List<String> {
        val k = text.indexOf("\"$key\"")
        if (k < 0) return emptyList()
        var i = text.indexOf('[', k)
        if (i < 0) return emptyList()
        i++
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> { if (depth == 0) start = i; depth++ }
                '}', ']' -> {
                    if (depth == 0) return out  // the array itself closed
                    depth--
                    if (depth == 0 && start >= 0) { out += text.substring(start, i + 1); start = -1 }
                }
            }
            i++
        }
        return out
    }
}
