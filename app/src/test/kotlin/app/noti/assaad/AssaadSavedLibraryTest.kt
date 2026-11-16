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
    @Test fun queueWaitsForBrowserPersistsBeforeSendAndAcceptsOldSetFeedback() {
        val messages = mutableListOf<JSONObject>()
        val first = session { raw ->
            val message = JSONObject(raw)
            if (message.getString("type") == "answer") assertTrue(saved.readText().contains("pending"))
            messages += message; true
        }
        val a = fixture(); import(first, a); first.saveDraft("a-1"); first.confirm("a-1")
        import(first, fixture("b"))
        assertEquals(1, first.queuedCount); assertTrue(messages.isEmpty())
        first.start(); online(first)
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
        val a = fixture(); import(first, a); first.confirm("a-1"); first.start(); online(first)
        waitUntil { messages.any { it.getString("type") == "answer" } }
        runBlocking { first.awaitSaved() }; first.dispose(); sessions.remove(first)
        val nextMessages = mutableListOf<JSONObject>(); val restored = session { nextMessages += JSONObject(it); true }
        assertEquals("unknown", restored.answers["q-1"]?.status)
        restored.start(); online(restored); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(nextMessages.none { it.getString("type") == "answer" })
        restored.allowRetry("q-1"); assertNull(restored.answers["q-1"]); assertEquals("a-1", restored.drafts["q-1"])
    }
    @Test fun pausedOrCancelledQueuedAnswersKeepTheirDrafts() {
        val first = session(); import(first, fixture()); first.confirm("a-2"); first.pauseQueue()
        assertEquals(1, first.queuedCount); assertTrue(first.queuePaused)
        first.cancelQueue(); assertEquals(0, first.queuedCount); assertEquals("a-2", first.drafts["q-1"])
        first.submitSavedAnswers(); assertEquals(1, first.queuedCount)
    }
    @Test fun tabletModeRequiresReadOnlyConnectionCheckAndSubmitsWithoutTheRelay() {
        var answersSent = 0
        val first = session { error("Tablet mode must never send a relay frame") }
        import(first, fixture()); first.useTablet(true)
        first.confirm("a-1"); first.start()
        assertEquals("queued", first.answers["q-1"]?.status)
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
        waitUntil { first.answers["q-1"]?.status == "confirmed" }
        assertTrue(first.tabletVerified); assertEquals(1, answersSent)
    }

}
