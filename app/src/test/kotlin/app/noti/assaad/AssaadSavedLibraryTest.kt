package app.noti.assaad

import android.os.Looper
import app.noti.data.LibraryStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssaadSavedLibraryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sessions = mutableListOf<AssaadSession>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val saved get() = File(context.filesDir, "assaad_session.json")
    @Before fun reset() { saved.delete(); context.getSharedPreferences("platform_keys", 0).edit().clear().commit() }
    @After fun close() { sessions.forEach { it.dispose() }; scopes.forEach { it.cancel() } }
    private fun session(send: ((String) -> Boolean)? = null): AssaadSession {
        val scope = MainScope(); scopes += scope
        return AssaadSession(context, LibraryStore(context), scope, send).also { sessions += it; waitUntil { it.libraryLoaded } }
    }
    private fun waitUntil(predicate: () -> Boolean) {
        repeat(300) { shadowOf(Looper.getMainLooper()).idle(); if (predicate()) return; Thread.sleep(5) }
        error("Session did not settle")
    }
    private fun fixture(account: String = "a", id: String = UUID.randomUUID().toString()) = JSONObject(javaClass.getResource("/assaad-batch.json")!!.readText()).apply {
        put("batchId", id); put("transport", "api-v1"); put("accountRef", account.repeat(64))
    }
    private fun import(session: AssaadSession, value: JSONObject) = session.receive(JSONObject().put("type", "questions").put("payload", value).toString())
    private fun online(session: AssaadSession) = session.receive("""{"type":"session","payload":{"paired":true,"peerOnline":true,"presence":{"active":false,"ready":true,"accountRef":"${"a".repeat(64)}"}}}""")
    private fun result(session: AssaadSession, command: JSONObject, status: String = "confirmed") {
        session.receive(JSONObject().put("type", "answer-result").put("payload", JSONObject(command.toString()).apply {
            put("status", status); put("correct", if (status == "confirmed") true else JSONObject.NULL); put("correctAlternativeId", if (status == "confirmed") "a-1" else JSONObject.NULL); put("feedback", "Official test feedback")
        }).toString())
    }
    @Test fun multipleSetsAndOfflineDraftsSurviveRestartWithoutCrossAccountCollisions() {
        val first = session(); val a = fixture(); val b = fixture("b")
        import(first, a); first.saveDraft("a-1")
        import(first, b); first.saveDraft("a-2")
        assertEquals(2, first.savedQuestionCount)
        runBlocking { first.awaitSaved() }; first.dispose(); sessions.remove(first)
        val restored = session()
        assertEquals(2, restored.batches.size)
        restored.selectBatch(a.getString("batchId")); assertEquals("a-1", restored.drafts["q-1"])
        restored.selectBatch(b.getString("batchId")); assertEquals("a-2", restored.drafts["q-1"])
        assertEquals(2, restored.draftCount)
    }
    @Test fun answersGoOneAtATimeOnlyWhileConnectedAndPersistBeforeSending() {
        val messages = mutableListOf<JSONObject>()
        val first = session { raw ->
            val message = JSONObject(raw)
            if (message.getString("type") == "answer") assertTrue(saved.readText().contains("pending"))
            messages += message; true
        }
        val a = fixture(); import(first, a); first.saveDraft("a-1")
        // Offline: nothing is queued to go later; the pick just stays a pick.
        assertFalse(first.confirm("a-1"))
        assertNull(first.answers["q-1"]); assertEquals("a-1", first.drafts["q-1"]); assertTrue(messages.isEmpty())
        first.start(); online(first)
        assertTrue(first.confirm("a-1"))
        // While it's on its way, another answer can't go.
        import(first, fixture("b")); first.saveDraft("a-2")
        assertFalse(first.canSendAnswer); assertFalse(first.confirm("a-2"))
        waitUntil { messages.any { it.getString("type") == "answer" } }
        assertEquals(listOf("restore-questions", "answer"), messages.take(2).map { it.getString("type") })
        val command = messages.first { it.getString("type") == "answer" }.getJSONObject("payload")
        assertEquals(a.getString("batchId"), command.getString("batchId"))
        result(first, command)
        first.selectBatch(a.getString("batchId")); assertEquals("confirmed", first.answers["q-1"]?.status)
        online(first); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, messages.count { it.getString("type") == "answer" })
    }
    @Test fun anUncertainSubmissionIsNeverSentAgainAfterRestart() {
        val messages = mutableListOf<JSONObject>()
        val first = session { messages += JSONObject(it); true }
        val a = fixture(); import(first, a); first.start(); online(first); first.confirm("a-1")
        waitUntil { messages.any { it.getString("type") == "answer" } }
        runBlocking { first.awaitSaved() }; first.dispose(); sessions.remove(first)
        val nextMessages = mutableListOf<JSONObject>(); val restored = session { nextMessages += JSONObject(it); true }
        assertEquals("unknown", restored.answers["q-1"]?.status)
        restored.start(); online(restored); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(nextMessages.none { it.getString("type") == "answer" })
        restored.allowRetry("q-1"); assertNull(restored.answers["q-1"]); assertEquals("a-1", restored.drafts["q-1"])
    }
    @Test fun answersAnOlderVersionLeftQueuedBecomePicksAndNeverSendThemselves() {
        val a = fixture(); val batch = AssaadBatch.parse(a)
        saved.writeText(JSONObject().put("version", 2).put("batches", org.json.JSONArray().put(a))
            .put("records", JSONObject().put(batch.recordKey("q-1"), AssaadAnswer("old", "a-2", "queued", "Waiting", batchId = batch.id).json())).toString())
        val messages = mutableListOf<JSONObject>()
        val restored = session { messages += JSONObject(it); true }
        assertNull(restored.answers["q-1"]); assertEquals("a-2", restored.drafts["q-1"])
        restored.start(); online(restored); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(messages.none { it.getString("type") == "answer" })
    }
    @Test fun tabletModeRequiresReadOnlyConnectionCheckAndSubmitsWithoutTheRelay() {
        var answersSent = 0
        val first = session { error("Tablet mode must never send a relay frame") }
        import(first, fixture()); first.useTablet(true); first.start()
        // Not connected yet: the answer doesn't go, and doesn't wait to go later either.
        assertFalse(first.confirm("a-1")); assertNull(first.answers["q-1"])
        first.attachTabletBrowser(object : AssaadLocalBrowser {
            override suspend fun readQuestions() = JSONObject()
            override suspend fun operation(input: JSONObject): JSONObject = when (input.getString("operation")) {
                "connect" -> JSONObject().put("ok", true).put("corsVerified", true).put("accountRef", "a".repeat(64))
                "answer" -> {
                    assertTrue(saved.readText().contains("pending")); answersSent++
                    JSONObject().put("status", "confirmed").put("correct", true).put("correctAlternativeId", "a-1").put("feedback", "Official feedback")
                }
                else -> error("Unexpected operation")
            }
        })
        first.tabletPage(true, ASSAAD_WEBSITE, null)
        waitUntil { first.canSendAnswer }
        assertTrue(first.confirm("a-1"))
        waitUntil { first.answers["q-1"]?.status == "confirmed" }
        assertTrue(first.tabletVerified); assertEquals(1, answersSent)
    }

}
