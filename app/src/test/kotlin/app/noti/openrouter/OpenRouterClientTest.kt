package app.noti.openrouter

import app.noti.openrouter.OpenRouterMessage.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class OpenRouterClientTest {
    private val input = OpenRouterRequest(listOf(OpenRouterMessage(Role.User, "Explain circles.")), 64)

    @Test
    fun completionUsesPinnedModelAndReportsUsage() = runBlocking {
        val client = client(completion) { request ->
            assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
            assertEquals("Bearer test-key", request.header("Authorization"))
            assertTrue(request.body!!.isOneShot())
            val json = payload(request)
            assertEquals(OpenRouterClient.MODEL, json.getString("model"))
            assertFalse(json.getBoolean("stream"))
            assertEquals(64, json.getInt("max_completion_tokens"))
            assertEquals("Explain circles.", json.getJSONArray("messages").getJSONObject(0).getString("content"))
        }
        val result = client.complete(" test-key ", input)
        assertEquals("Olá!", result.text)
        assertEquals("gen-test", result.id)
        assertEquals("Tencent", result.provider)
        assertEquals(0, BigDecimal("0.000002").compareTo(requireNotNull(result.usage?.costUsd)))
    }

    @Test
    fun encodesImagePdfAndStrictSchema() {
        val content = OpenRouterMessage(Role.User, listOf(
            OpenRouterPart.Text("Read this."),
            OpenRouterPart.Image(ImageMediaType.Png, byteArrayOf(1, 2, 3)),
            OpenRouterPart.Pdf("exam.pdf", byteArrayOf(4, 5, 6)),
        ))
        val json = input.copy(
            messages = listOf(content), schema = OpenRouterSchema("questions", JSONObject().put("type", "object")),
            pdfEngine = OpenRouterPdfEngine.MistralOcr,
        ).json(stream = true)
        val parts = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("Read this.", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/png;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        val file = parts.getJSONObject(2).getJSONObject("file")
        assertEquals("exam.pdf", file.getString("filename"))
        assertEquals("data:application/pdf;base64,BAUG", file.getString("file_data"))
        assertEquals("mistral-ocr", json.getJSONArray("plugins").getJSONObject(0).getJSONObject("pdf").getString("engine"))
        assertTrue(json.getJSONObject("provider").getBoolean("require_parameters"))
        assertTrue(json.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
        assertTrue(json.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun streamReadsKeepAlivesMultilineDeltasAndFinalUsage() = runBlocking {
        val stream = ": OPENROUTER PROCESSING\r\n\r\n" +
            "data: {\"id\":\"gen-test\",\"model\":\"tencent/hy4-flash\",\r\n" +
            "data: \"choices\":[{\"delta\":{\"content\":\"Olá\",\"reasoning\":\"hidden\"}}]}\r\n\r\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}\r\n\r\n" +
            "data: {\"choices\":[],\"usage\":{\"total_tokens\":19}}\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        val events = client(stream, mediaType = "text/event-stream") { request ->
            assertEquals("text/event-stream", request.header("Accept"))
            assertTrue(payload(request).getBoolean("stream"))
        }.stream("test-key", input).toList()
        assertEquals(listOf("Olá", "!"), events.filterIsInstance<OpenRouterStreamEvent.TextDelta>().map { it.text })
        val finished = (events.last() as OpenRouterStreamEvent.Finished).completion
        assertEquals("Olá!", finished.text)
        assertEquals(19L, finished.usage?.totalTokens)
    }

    @Test
    fun preservesRateLimitAndIgnoresRawProviderMessage() {
        val response = """{"error":{"code":429,"message":"test-key private prompt","metadata":{"error_type":"rate_limit_exceeded"}}}"""
        val error = assertThrows(OpenRouterFailure.Api::class.java) {
            runBlocking { client(response, 429, retryAfter = "17").complete("test-key", input) }
        }
        assertEquals(429, error.httpStatus)
        assertEquals(429, error.status)
        assertEquals(17L, error.retryAfterSeconds)
        assertEquals("rate_limit_exceeded", error.errorType)
        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    @Test
    fun http200ErrorAndInterruptedStreamNeverFinish() {
        val errorJson = """{"id":"gen-test","error":{"code":502,"message":"upstream failed"}}"""
        val badCompletion = assertThrows(OpenRouterFailure.Api::class.java) {
            runBlocking { client(errorJson).complete("test-key", input) }
        }
        assertEquals(200, badCompletion.httpStatus)
        assertEquals(502, badCompletion.status)
        val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        val events = mutableListOf<OpenRouterStreamEvent>()
        val streamError = assertThrows(OpenRouterFailure.Api::class.java) {
            runBlocking {
                client(partial + "data: $errorJson\n\n", mediaType = "text/event-stream").stream("test-key", input).toList(events)
            }
        }
        assertEquals("gen-test", streamError.requestId)
        assertEquals(listOf(OpenRouterStreamEvent.TextDelta("partial")), events)
        assertThrows(OpenRouterFailure.Protocol::class.java) {
            runBlocking { client(partial, mediaType = "text/event-stream").stream("test-key", input).toList() }
        }
    }

    @Test
    fun lengthLimitFailsEvenWithOutput() {
        val partial = completion.replace("\"stop\"", "\"length\"")
        val error = assertThrows(OpenRouterFailure.Incomplete::class.java) {
            runBlocking { client(partial).complete("test-key", input) }
        }
        assertEquals("length", error.finishReason)
        assertEquals("gen-test", error.generationId)
    }

    @Test
    fun cancellingCollectorCancelsHttpCall() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            release.await(5, TimeUnit.SECONDS)
            throw InterruptedIOException()
        }.build()
        val job = launch { OpenRouterClient(transport).stream("test-key", input).collect {} }
        try {
            val call = withTimeout(5_000) { started.await() }
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    private fun payload(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun client(body: String, status: Int = 200, mediaType: String = "application/json", retryAfter: String? = null,
                       inspect: (Request) -> Unit = {}): OpenRouterClient {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody(mediaType.toMediaType()))
                .apply { retryAfter?.let { header("Retry-After", it) } }.build()
        }.build()
        return OpenRouterClient(transport)
    }

    private companion object {
        const val completion = """{"id":"gen-test","model":"tencent/hy4-flash","provider":"Tencent",
            "choices":[{"message":{"content":"Olá!"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":15,"completion_tokens":4,"total_tokens":19,"cost":0.000002}}"""
    }
}
