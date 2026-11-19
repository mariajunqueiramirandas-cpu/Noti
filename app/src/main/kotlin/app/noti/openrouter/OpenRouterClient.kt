package app.noti.openrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class OpenRouterCompletion(
    val text: String,
    val id: String,
    val model: String,
    val provider: String?,
    val usage: OpenRouterUsage?,
)

internal data class OpenRouterUsage(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
    val reasoningTokens: Long?,
    val costUsd: BigDecimal?,
)

internal sealed interface OpenRouterStreamEvent {
    data class TextDelta(val text: String) : OpenRouterStreamEvent
    data class Finished(val completion: OpenRouterCompletion) : OpenRouterStreamEvent
}

internal sealed class OpenRouterFailure(message: String) : IOException(message) {
    class Api(
        val httpStatus: Int,
        val status: Int,
        val errorType: String?,
        val limitSource: String?,
        val retryAfterSeconds: Long?,
        val requestId: String?,
    ) : OpenRouterFailure("OpenRouter returned an error ($status).")

    class Incomplete(val finishReason: String, val generationId: String?, val usage: OpenRouterUsage?) :
        OpenRouterFailure("OpenRouter did not finish the answer.")

    class Protocol(message: String) : OpenRouterFailure(message)

    class Network(val timedOut: Boolean) : OpenRouterFailure(
        if (timedOut) "The OpenRouter request timed out." else "The connection to OpenRouter was interrupted.",
    )
}

/** A cancellable, text-output Chat Completions client pinned to Tencent HY4 Flash. */
internal class OpenRouterClient(private val calls: Call.Factory = http) {
    suspend fun complete(apiKey: String, input: OpenRouterRequest): OpenRouterCompletion =
        execute(request(apiKey, input, stream = false)) { response ->
            val payload = response.body.readJson()
            response.checkApiError(payload)
            val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            val message = choice?.optJSONObject("message")
            CompletionBuilder().apply {
                update(payload, choice, message)
                text.append(message?.text("content").orEmpty())
            }.finish()
        }

    /** Collect once per generation. Only Finished represents a complete answer. */
    fun stream(apiKey: String, input: OpenRouterRequest): Flow<OpenRouterStreamEvent> = channelFlow {
        try {
            val result = execute(request(apiKey, input, stream = true)) { response ->
                response.readStream { text ->
                    if (trySendBlocking(OpenRouterStreamEvent.TextDelta(text)).isFailure) throw IOException()
                }
            }
            send(OpenRouterStreamEvent.Finished(result))
        } catch (failure: OpenRouterFailure) {
            close(failure)
        }
    }

    suspend fun testConnection(apiKey: String): OpenRouterCompletion = complete(apiKey, OpenRouterRequest(
        messages = listOf(OpenRouterMessage(OpenRouterMessage.Role.User, "Reply with just the word OK.")),
        maxCompletionTokens = 32,
        reasoning = false,
    ))

    private suspend fun request(key: String, input: OpenRouterRequest, stream: Boolean): Request =
        withContext(Dispatchers.Default) {
            val token = key.trim()
            require(token.isNotEmpty() && token.all { it in '!'..'~' }) { "Enter a valid API key." }
            val payload = input.json(stream).toString().toByteArray(Charsets.UTF_8)
            Request.Builder().url(ENDPOINT)
                .header("Authorization", "Bearer $token")
                .header("X-OpenRouter-Title", "Noti")
                .header("Accept", if (stream) "text/event-stream" else "application/json")
                .post(object : RequestBody() {
                    override fun contentType() = JSON
                    override fun contentLength() = payload.size.toLong()
                    // Avoid resending a potentially billable generation, including a 503 follow-up.
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) { sink.write(payload) }
                })
                .build()
        }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(OpenRouterFailure.Network(e is InterruptedIOException))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) it.checkApiError(it.readErrorBody())
                            read(it)
                        }
                    }.recoverCatching { failure ->
                        throw when (failure) {
                            is OpenRouterFailure -> failure
                            is JSONException -> OpenRouterFailure.Protocol("OpenRouter returned invalid JSON.")
                            is EOFException -> OpenRouterFailure.Protocol("The OpenRouter response ended unexpectedly.")
                            is IOException -> OpenRouterFailure.Network(failure is InterruptedIOException)
                            else -> failure
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    companion object {
        const val MODEL = "tencent/hy4-flash"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
private const val MAX_EVENT_SIZE = 2 * 1024 * 1024
private const val MAX_ERROR_BYTES = 64L * 1024

private fun ResponseBody?.readJson(): JSONObject {
    val source = this?.source() ?: throw OpenRouterFailure.Protocol("OpenRouter returned no response body.")
    if (source.request(MAX_RESPONSE_BYTES + 1)) throw OpenRouterFailure.Protocol("The OpenRouter response is too large.")
    return JSONObject(source.readUtf8())
}

private fun Response.readErrorBody(): JSONObject? {
    val source = body?.source() ?: return null
    if (source.request(MAX_ERROR_BYTES + 1)) return null
    return try { JSONObject(source.readUtf8()) } catch (_: JSONException) { null }
}

private fun Response.checkApiError(payload: JSONObject?, generationId: String? = null) {
    val error = payload?.optJSONObject("error")
    if (isSuccessful && error == null) return
    val metadata = error?.optJSONObject("metadata")
    val status = error?.number("code")?.toIntOrNull()?.takeIf { it in 400..599 }
        ?: if (isSuccessful) 502 else code
    throw OpenRouterFailure.Api(
        httpStatus = code,
        status = status,
        errorType = metadata?.text("error_type") ?: error?.text("code"),
        limitSource = metadata?.text("limit_source"),
        retryAfterSeconds = header("Retry-After")?.let(::retryAfterSeconds),
        requestId = payload?.text("id") ?: generationId ?: header("x-request-id"),
    )
}

private fun retryAfterSeconds(value: String): Long? {
    value.trim().toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
    return try {
        val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        (deadline - Instant.now().epochSecond).coerceAtLeast(0)
    } catch (_: DateTimeParseException) { null }
}

private fun Response.readStream(onText: (String) -> Unit): OpenRouterCompletion {
    val contentType = body?.contentType()
    if (contentType?.type != "text" || contentType.subtype != "event-stream") {
        checkApiError(readErrorBody())
        throw OpenRouterFailure.Protocol("OpenRouter did not return an event stream.")
    }
    val source = requireNotNull(body).source()
    val result = CompletionBuilder()
    val data = StringBuilder()
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(MAX_EVENT_SIZE.toLong())
        if (line.isEmpty()) {
            if (data.isEmpty()) continue
            val event = data.toString()
            data.clear()
            if (event == "[DONE]") return result.finish()
            val payload = JSONObject(event)
            checkApiError(payload, result.id)
            val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            result.update(payload, choice, delta)
            val chunk = delta?.text("content").orEmpty()
            if (chunk.isNotEmpty()) {
                if (result.text.length + chunk.length > MAX_RESPONSE_BYTES) {
                    throw OpenRouterFailure.Protocol("The OpenRouter response is too large.")
                }
                result.text.append(chunk)
                onText(chunk)
            }
        } else if (line == "data" || line.startsWith("data:")) {
            val field = line.substringAfter(':', "").removePrefix(" ")
            if (data.length + field.length > MAX_EVENT_SIZE) {
                throw OpenRouterFailure.Protocol("An OpenRouter stream event is too large.")
            }
            if (data.isNotEmpty()) data.append('\n')
            data.append(field)
        }
        // Comment keep-alives and other SSE fields do not carry completion data.
    }
    throw OpenRouterFailure.Protocol("The OpenRouter stream ended before its final event.")
}

private class CompletionBuilder {
    val text = StringBuilder()
    var id: String? = null
        private set
    private var model: String? = null
    private var provider: String? = null
    private var finishReason: String? = null
    private var usage: OpenRouterUsage? = null
    private var refused = false

    fun update(payload: JSONObject, choice: JSONObject?, message: JSONObject?) {
        id = payload.text("id") ?: id
        model = payload.text("model") ?: model
        provider = payload.text("provider") ?: provider
        finishReason = choice?.text("finish_reason") ?: finishReason
        refused = refused || !message?.text("refusal").isNullOrBlank()
        payload.optJSONObject("usage")?.let {
            usage = OpenRouterUsage(
                promptTokens = it.number("prompt_tokens")?.toLongOrNull(),
                completionTokens = it.number("completion_tokens")?.toLongOrNull(),
                totalTokens = it.number("total_tokens")?.toLongOrNull(),
                reasoningTokens = it.optJSONObject("completion_tokens_details")?.number("reasoning_tokens")?.toLongOrNull(),
                costUsd = it.number("cost")?.toBigDecimalOrNull(),
            )
        }
    }

    fun finish(): OpenRouterCompletion {
        val reason = finishReason ?: throw OpenRouterFailure.Protocol("OpenRouter returned no finish reason.")
        if (refused || reason != "stop") throw OpenRouterFailure.Incomplete(if (refused) "refusal" else reason, id, usage)
        if (text.isBlank()) throw OpenRouterFailure.Protocol("OpenRouter returned an empty answer.")
        val generationId = id ?: throw OpenRouterFailure.Protocol("OpenRouter returned no generation ID.")
        val responseModel = model ?: throw OpenRouterFailure.Protocol("OpenRouter returned no model ID.")
        return OpenRouterCompletion(text.toString(), generationId, responseModel, provider, usage)
    }
}

private fun JSONObject.text(name: String): String? = opt(name) as? String

private fun JSONObject.number(name: String): String? = when (val value = opt(name)) {
    is Number, is String -> value.toString()
    else -> null
}
