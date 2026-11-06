package app.noti.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudyAiTest {
    private class Call(val method: String, val url: String, val headers: Map<String, String>, val contentType: String?, val body: ByteArray)

    /** Replies in order; records every request. */
    private class FakeTransport(vararg replies: Pair<Int, String>) : MistralClient.Transport {
        val queue = ArrayDeque(replies.toList())
        val calls = ArrayList<Call>()
        override fun open(method: String, url: String, headers: Map<String, String>, body: ((OutputStream) -> Unit)?,
            contentType: String?): MistralClient.Reply {
            val out = ByteArrayOutputStream()
            body?.invoke(out)
            calls += Call(method, url, headers, contentType, out.toByteArray())
            val (status, text) = queue.removeFirst()
            return MistralClient.Reply(status, ByteArrayInputStream(text.toByteArray())) {}
        }
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("") {
        "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
            .put("delta", JSONObject().put("content", it)))).toString() + "\n\n"
    } + "data: [DONE]\n\n"

    private val itemJson = JSONObject()
        .put("kind", "choice").put("topic", "Kinematics").put("prompt", "Which is the SI unit of acceleration?")
        .put("choices", JSONArray(listOf("\$\\mathrm{m/s}\$", "\$\\mathrm{m/s^2}\$", "N", "J"))).put("correct_choice", 1)
        .put("answer", "\$\\mathrm{m/s^2}\$").put("solution", JSONArray(listOf("Acceleration is change of velocity per time.")))
        .put("hints", JSONArray(listOf("Velocity per time.", "m/s per s", "Squared seconds")))
        .put("pages", JSONArray(listOf(2, 99))).put("excerpt", "a = dv/dt").put("difficulty", 1)

    @Test fun streamedItemsArriveOneByOneAndPagesMapBackToTheSource() {
        val deck = JSONObject().put("items", JSONArray().put(itemJson).put(JSONObject(itemJson.toString()).put("kind", "flash")
            .put("prompt", "Newton's second law").put("answer", "\$\\vec F = m\\vec a\$"))).toString()
        // Split the reply mid-string and mid-object, as a stream would.
        val parts = deck.chunked(17)
        val transport = FakeTransport(
            200 to """{"id":"file-1"}""",
            200 to """{"url":"https://files.example/signed"}""",
            200 to sse(*parts.toTypedArray()),
            200 to "{}",
        )
        val ai = StudyAi(MistralClient("sk-test", "https://api.test/v1", transport))
        val pdf = File.createTempFile("source", ".pdf").apply { writeBytes("%PDF-1.4 test".toByteArray()) }
        val seen = ArrayList<StudyItem>()
        val items = ai.fromPdf(pdf, "Physics 1", StudySource("src", "Physics 1", 40), firstPage = 11,
            req = DeckRequest(pages = 11..20), onItem = { seen += it }, cancelled = { false })
        assertEquals(2, items.size)
        assertEquals(items, seen)
        val choice = items[0]
        assertEquals(ItemKind.Choice, choice.kind)
        assertEquals(1, choice.correctChoice)
        assertEquals("page 2 of the excerpt is page 12; 99 is out of range", listOf(12), choice.pages)
        assertEquals("src", choice.sourceId)
        assertEquals(ItemKind.Flash, items[1].kind)
        assertTrue("flash cards have no hints", items[1].hints.isEmpty())

        val (upload, url, chat, delete) = transport.calls
        assertEquals("POST", upload.method)
        assertEquals("https://api.test/v1/files", upload.url)
        assertTrue(upload.contentType!!.startsWith("multipart/form-data; boundary="))
        val multipart = String(upload.body)
        assertTrue(multipart.contains("name=\"purpose\"\r\n\r\nocr"))
        assertTrue(multipart.contains("filename=\"Physics 1.pdf\""))
        assertTrue(multipart.contains("%PDF-1.4 test"))
        assertEquals("Bearer sk-test", upload.headers["Authorization"])
        assertEquals("https://api.test/v1/files/file-1/url?expiry=2", url.url)
        val body = JSONObject(String(chat.body))
        assertEquals("mistral-large-4", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val format = body.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getJSONObject("json_schema").getBoolean("strict"))
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("https://files.example/signed", content.getJSONObject(1).getString("document_url"))
        assertTrue(content.getJSONObject(0).getString("text").contains("pages 11–20"))
        assertEquals("the upload is deleted afterwards", "DELETE", delete.method)
        assertEquals("https://api.test/v1/files/file-1", delete.url)
    }

    @Test fun theUploadIsDeletedEvenWhenGenerationFails() {
        val transport = FakeTransport(200 to """{"id":"f"}""", 200 to """{"url":"u"}""", 429 to """{"message":"rate"}""", 200 to "{}")
        val ai = StudyAi(MistralClient("k", "https://api.test/v1", transport))
        val pdf = File.createTempFile("source", ".pdf").apply { writeBytes(byteArrayOf(1)) }
        val e = runCatching { ai.fromPdf(pdf, "x", StudySource("s", "x", 1), 1, DeckRequest(), {}, { false }) }.exceptionOrNull()
        assertTrue(e is StudyApiException)
        assertEquals(429, (e as StudyApiException).status)
        assertEquals("DELETE", transport.calls.last().method)
    }

    @Test fun gradingSendsTheAnswerImageAndReadsTheVerdict() {
        val reply = JSONObject().put("read", "v = 6 m/s").put("verdict", "partial").put("score", 0.6)
            .put("feedback", "Right method; check the units.").put("steps", JSONArray().put(JSONObject().put("ok", true).put("note", "v = at"))
                .put(JSONObject().put("ok", false).put("note", "units")))
        val transport = FakeTransport(200 to sse(*reply.toString().chunked(9).toTypedArray()))
        val ai = StudyAi(MistralClient("k", "https://api.test/v1", transport))
        val item = StudyPrompts.item(itemJson, null, 1, 0, 0L)!!
        val grade = ai.grade(item, byteArrayOf(1, 2, 3), 42L)
        assertEquals(Verdict.Partial, grade.verdict)
        assertEquals(0.6f, grade.score, 1e-6f)
        assertEquals(listOf(true, false), grade.steps.map { it.ok })
        assertEquals(42L, grade.at)
        val content = JSONObject(String(transport.calls.single().body)).getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("data:image/png;base64,AQID", content.getJSONObject(1).getString("image_url"))
        assertTrue(content.getJSONObject(0).getString("text").contains("B. "))
    }

    @Test fun badItemsAreRepairedOrDropped() {
        assertNull(StudyPrompts.item(JSONObject().put("prompt", " "), null, 1, 0, 0))
        val noOptions = StudyPrompts.item(JSONObject(itemJson.toString()).put("correct_choice", 7), null, 1, 0, 0)!!
        assertEquals("options that don't add up make it a plain question", ItemKind.Short, noOptions.kind)
        assertTrue(noOptions.choices.isEmpty())
    }

    @Test fun serverSentEventsAndContentChunks() {
        val sse = Sse()
        assertNull(sse.line(": keep-alive"))
        assertEquals("""{"a":1}""", sse.line("""data: {"a":1}"""))
        assertNull(sse.line(""))
        assertEquals("[DONE]", sse.line("data: [DONE]"))
        val chunked = """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[]},{"type":"text","text":"hi"}]},"finish_reason":null}]}"""
        assertEquals("hi", Sse.delta(chunked).text)
        assertNull(Sse.delta(chunked).finish)
        assertEquals("length", Sse.delta("""{"choices":[{"delta":{"content":""},"finish_reason":"length"}]}""").finish)
        assertEquals("boom", Sse.delta("""{"error":{"message":"boom"}}""").error)
    }

    @Test fun partialJsonOnlyReturnsFinishedElements() {
        val text = """{"items":[{"prompt":"a {tricky} \"quote\" ]"},{"prompt":"b"},{"prompt":"unfinish"""
        assertEquals(2, PartialJson.completeElements(text, "items").size)
        assertEquals(emptyList<String>(), PartialJson.completeElements("""{"title":"x","it""", "items"))
        assertEquals(1, PartialJson.completeElements("""{"items":[{"x":[1,2]}],"more":[{"y":1}]}""", "items").size)
    }

    @Test fun errorsReadForPeople() {
        assertTrue(MistralClient.errorMessage(401, "").contains("API key"))
        assertTrue(MistralClient.errorMessage(429, "").contains("Try again"))
        assertTrue(MistralClient.errorMessage(400, """{"message":"Bad document"}""").contains("Bad document"))
    }

    @Test fun schemasAreStrict() {
        fun check(o: JSONObject) {
            if (o.optString("type") == "object") {
                assertFalse(o.getBoolean("additionalProperties"))
                val props = o.getJSONObject("properties")
                assertEquals(props.keys().asSequence().toSet(), o.getJSONArray("required").strings().toSet())
                props.keys().forEach { check(props.getJSONObject(it)) }
            }
            o.optJSONObject("items")?.let(::check)
        }
        check(StudyPrompts.deckSchema())
        check(StudyPrompts.gradeSchema())
    }
}
