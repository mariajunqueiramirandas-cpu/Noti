package app.noti.assaad

import app.noti.data.LibraryStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssaadTest {
    private fun fixture() = JSONObject(javaClass.getResource("/assaad-batch.json")!!.readText())

    @Test fun sharedRelayFixtureParsesWithoutInventingAnAnswerKey() {
        val batch = AssaadBatch.parse(fixture())
        assertEquals("1", batch.page)
        val question = batch.questions.single()
        assertEquals(listOf("a-1", "a-2"), question.alternatives.map { it.id })
        assertTrue(question.printable.contains("A) Rio Nilo"))
        assertFalse(question.printable.contains("correctAnswer"))
    }
    @Test fun foreignOriginsDuplicateChoicesAndOversizedDecksAreRejected() {
        val foreign = fixture().apply { getJSONObject("source").put("url", "https://example.com/banco-de-questoes/explorar") }
        assertTrue(runCatching { AssaadBatch.parse(foreign) }.isFailure)
        val duplicate = fixture().apply { getJSONArray("questions").getJSONObject(0).getJSONArray("alternatives").getJSONObject(1).put("id", "a-1") }
        assertTrue(runCatching { AssaadBatch.parse(duplicate) }.isFailure)
        val oversized = fixture().apply { put("questions", JSONArray().apply { repeat(101) { put(fixture().getJSONArray("questions").getJSONObject(0)) } }) }
        assertTrue(runCatching { AssaadBatch.parse(oversized) }.isFailure)
        assertFalse(safeImageUrl("http://example.com/image.png"))
        assertFalse(safeImageUrl("https://127.0.0.1/image.png"))
        assertFalse(safeImageUrl("https://localhost/image.png"))
        assertTrue(safeImageUrl("https://cdn.plataformaassaad.com.br/image.webp"))
    }
    @Test fun serverFeedbackSettlesAnAnswerAndReplayKeepsTheSameDeck() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        val scope = MainScope()
        val session = AssaadSession(context, LibraryStore(context), scope) { true }
        session.receive(JSONObject().put("type", "questions").put("payload", fixture()).toString())
        val first = session.batch
        session.receive(JSONObject().put("type", "questions").put("payload", fixture()).toString())
        assertSame(first, session.batch)
        session.receive("""{"type":"session","payload":{"paired":true,"peerOnline":true,"presence":{"active":true,"path":"/banco-de-questoes/explorar"}}}""")
        assertTrue(session.platformActive)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        session.start()
        assertTrue(session.confirm("a-1"))
        assertTrue(session.answers["q-1"]?.status in listOf("queued", "pending"))
        assertNull(session.answers["q-1"]?.correct)
        val command = session.answers.getValue("q-1").commandId
        session.receive("""{"type":"answer-result","payload":{"commandId":"$command","status":"confirmed","correct":true,"correctAlternativeId":"a-1","feedback":"Resposta correta."}}""")
        assertEquals(true, session.answers["q-1"]?.correct)
        session.stop(); assertFalse(session.platformActive)
        session.dispose(); scope.cancel()
    }
    @Test fun replayedServerResultIncludesContextWhenTheAppLostItsPendingWrite() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("platform_keys", 0).edit().clear().commit()
        val scope = MainScope()
        val session = AssaadSession(context, LibraryStore(context), scope)
        val batch = fixture()
        session.receive(JSONObject().put("type", "questions").put("payload", batch).toString())
        session.receive("""{"type":"answer-result","payload":{"commandId":"restored","batchId":"${batch.getString("batchId")}","questionId":"q-1","alternativeId":"a-1","status":"confirmed","correct":true,"correctAlternativeId":"a-1","feedback":"Resposta correta."}}""")
        assertEquals(true, session.answers["q-1"]?.correct)
        assertEquals("confirmed", session.answers["q-1"]?.status)
        session.dispose(); scope.cancel()
    }
}
