package app.noti.openrouter

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal data class OpenRouterRequest(
    val messages: List<OpenRouterMessage>,
    val maxCompletionTokens: Int,
    val schema: OpenRouterSchema? = null,
    val reasoning: Boolean? = null,
    val pdfEngine: OpenRouterPdfEngine? = null,
) {
    fun json(stream: Boolean): JSONObject {
        require(messages.isNotEmpty())
        require(maxCompletionTokens in 1..65_536)
        require(messages.any { message -> message.parts.any { it is OpenRouterPart.Pdf } } == (pdfEngine != null)) {
            "Choose a PDF parsing engine when attaching a PDF."
        }
        return JSONObject()
            .put("model", OpenRouterClient.MODEL)
            .put("messages", JSONArray(messages.map { it.json() }))
            .put("max_completion_tokens", maxCompletionTokens)
            .put("stream", stream)
            .apply {
                if (stream) put("stream_options", JSONObject().put("include_usage", true))
                reasoning?.let { put("reasoning", JSONObject().put("enabled", it)) }
                schema?.let {
                    put("response_format", it.json())
                    put("provider", JSONObject().put("require_parameters", true))
                }
                pdfEngine?.let {
                    put("plugins", JSONArray().put(JSONObject().put("id", "file-parser")
                        .put("pdf", JSONObject().put("engine", it.wireName))))
                }
            }
    }
}

internal class OpenRouterMessage(val role: Role, val parts: List<OpenRouterPart>) {
    enum class Role(val wireName: String) { System("system"), User("user"), Assistant("assistant") }

    constructor(role: Role, text: String) : this(role, listOf(OpenRouterPart.Text(text)))

    fun json(): JSONObject {
        require(parts.isNotEmpty())
        require(role == Role.User || parts.all { it is OpenRouterPart.Text })
        val onlyText = parts.singleOrNull() as? OpenRouterPart.Text
        return JSONObject().put("role", role.wireName)
            .put("content", onlyText?.value ?: JSONArray(parts.map { it.json() }))
    }
}

internal sealed interface OpenRouterPart {
    fun json(): JSONObject

    class Text(val value: String) : OpenRouterPart {
        override fun json(): JSONObject = JSONObject().put("type", "text").put("text", value)
    }

    /** A public HTTPS URL or a base64 data URL for a local image. */
    class Image(private val url: String) : OpenRouterPart {
        constructor(mediaType: ImageMediaType, bytes: ByteArray) : this(dataUrl(mediaType.wireName, bytes))

        override fun json(): JSONObject {
            require(url.startsWith("https://") || url.startsWith("data:image/"))
            return JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))
        }
    }

    /** A public HTTPS URL or a base64 data URL for a local PDF. */
    class Pdf(private val filename: String, private val url: String) : OpenRouterPart {
        constructor(filename: String, bytes: ByteArray) : this(filename, dataUrl("application/pdf", bytes))

        override fun json(): JSONObject {
            require(filename.isNotBlank())
            require(url.startsWith("https://") || url.startsWith("data:application/pdf;base64,"))
            return JSONObject().put("type", "file")
                .put("file", JSONObject().put("filename", filename).put("file_data", url))
        }
    }
}

internal enum class ImageMediaType(val wireName: String) {
    Png("image/png"), Jpeg("image/jpeg"), Webp("image/webp"), Gif("image/gif"),
}

internal enum class OpenRouterPdfEngine(val wireName: String) {
    CloudflareAi("cloudflare-ai"), MistralOcr("mistral-ocr"),
}

internal class OpenRouterSchema(private val name: String, private val schema: JSONObject) {
    fun json(): JSONObject = JSONObject().put("type", "json_schema").put("json_schema",
        JSONObject().put("name", name).put("strict", true).put("schema", schema))
}

private fun dataUrl(mediaType: String, bytes: ByteArray): String {
    require(bytes.isNotEmpty())
    return "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}"
}
