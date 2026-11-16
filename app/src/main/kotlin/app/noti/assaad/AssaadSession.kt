package app.noti.assaad

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import app.noti.data.*
import app.noti.study.ApiKeyStore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Foreground-only relay client. All Compose state is updated on the main scope. */
@Stable
class AssaadSession(context: Context, private val library: LibraryStore, private val scope: CoroutineScope) {
    private val secrets = ApiKeyStore(context, "platform_keys", "noti-platform-session")
    private var credentials = secrets.load()?.let { runCatching { JSONObject(it) }.getOrNull() }
    private val prefs = context.getSharedPreferences("platform_preferences", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.filesDir, "assaad_session.json"))
    val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "noti-platform-save").apply { isDaemon = true } }
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var heartbeat: Job? = null
    private var foreground = false
    private var backoff = 1000L

    var dialogOpen by mutableStateOf(false)
    var sheetOpen by mutableStateOf(true)
    var connected by mutableStateOf(false); private set
    var paired by mutableStateOf(false); private set
    var browserOnline by mutableStateOf(false); private set
    var platformActive by mutableStateOf(false); private set
    var activePath by mutableStateOf<String?>(null); private set
    var code by mutableStateOf<String?>(null); private set
    var codeExpiresAt by mutableLongStateOf(0L); private set
    var busy by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null)
    var batch by mutableStateOf<AssaadBatch?>(null); private set
    var answers by mutableStateOf<Map<String, AssaadAnswer>>(emptyMap()); private set
    var currentIndex by mutableIntStateOf(0)
    var boardId by mutableStateOf(prefs.getString("board", null)); private set
    val hasSession get() = credentials != null
    val current get() = batch?.questions?.getOrNull(currentIndex)

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { JSONObject(String(file.readFully(), Charsets.UTF_8)) }.getOrNull() }
            if (batch == null && saved != null) runCatching {
                batch = saved.optJSONObject("batch")?.let(AssaadBatch::parse)
                answers = saved.optJSONObject("answers")?.let { a -> a.keys().asSequence().associateWith { AssaadAnswer.parse(a.getJSONObject(it)) } } ?: emptyMap()
                // A process death leaves a submitted command uncertain; a replayed server
                // result can settle it, but the app must not submit it a second time.
                answers = answers.mapValues { (_, a) -> if (a.status == "pending") a.copy(status = "unknown", feedback = "Confirmation interrupted. Reconnect or check the browser.") else a }
            }
        }
    }
    fun start() { foreground = true; connect() }
    fun stop() {
        foreground = false; reconnect?.cancel(); heartbeat?.cancel(); socket?.close(1000, "App in background"); socket = null
        connected = false; browserOnline = false; platformActive = false
    }
    fun dispose() { stop(); writer.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    private fun connect() {
        val saved = credentials ?: return
        if (!foreground || socket != null) return
        val request = Request.Builder().url(ASSAAD_SERVER.replace("https:", "wss:") + "/v1/stream").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "authenticate").put("token", saved.getString("token")).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { if (socket === webSocket && text.length <= 1_048_576) receive(text) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { lost(webSocket, code) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { lost(webSocket, 0) } }
        })
    }
    private fun lost(closed: WebSocket, closeCode: Int) {
        if (socket !== closed) return
        socket = null; connected = false; browserOnline = false; platformActive = false; heartbeat?.cancel()
        if (closeCode == 4001) {
            credentials = null; secrets.clear(); paired = false; code = null
            problem = "This connection has ended. Generate a new pairing code."
        } else if (foreground && credentials != null) {
            reconnect?.cancel(); reconnect = scope.launch { delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000); connect() }
        }
    }
    private fun send(type: String, payload: JSONObject? = null): Boolean = socket?.send(JSONObject().put("type", type).apply { payload?.let { put("payload", it) } }.toString()) == true
    internal fun receive(text: String) {
        runCatching {
            val message = JSONObject(text); val payload = message.optJSONObject("payload") ?: JSONObject()
            when (message.optString("type")) {
                "session" -> {
                    val first = !connected
                    connected = true; backoff = 1000; paired = payload.optBoolean("paired"); browserOnline = payload.optBoolean("peerOnline")
                    platformActive = payload.optJSONObject("presence")?.optBoolean("active") == true
                    activePath = payload.optJSONObject("presence")?.optString("path")?.takeIf { it != "null" }
                    if (paired) code = null
                    if (first) { heartbeat?.cancel(); heartbeat = scope.launch { while (isActive) { delay(20_000); send("ping") } } }
                }
                "questions" -> {
                    val incoming = AssaadBatch.parse(payload)
                    if (incoming.id != batch?.id) {
                        batch = incoming; currentIndex = 0; sheetOpen = true
                        answers = answers.filterKeys { key -> incoming.questions.any { it.id == key } }
                        save()
                    }
                }
                "answer-result" -> {
                    val id = payload.getString("commandId")
                    val entry = answers.entries.find { it.value.commandId == id }
                    val questionId = entry?.key ?: payload.optString("questionId")
                    val previous = entry?.value ?: run {
                        // The server also returns command context so a process death
                        // between sending and a local disk write cannot lose feedback.
                        if (payload.optString("batchId") != batch?.id) return
                        val question = batch?.questions?.find { it.id == questionId } ?: return
                        val alternative = payload.optString("alternativeId")
                        if (question.alternatives.none { it.id == alternative }) return
                        AssaadAnswer(id, alternative)
                    }
                    val result = AssaadAnswer.parse(JSONObject(previous.json().toString()).apply {
                        for (key in listOf("status", "feedback", "correct", "correctAlternativeId")) if (payload.has(key)) put(key, payload.get(key))
                    })
                    if (answers[questionId] != result) { answers = answers + (questionId to result); save() }
                }
                "error" -> {
                    val id = payload.optString("commandId")
                    answers.entries.find { it.value.commandId == id }?.let { entry ->
                        answers = answers + (entry.key to entry.value.copy(status = "failed", feedback = when (payload.optString("error")) {
                            "browser_offline" -> "Keep the Assaad question tab open in the paired browser."
                            "stale_question" -> "Send the current questions from the browser again."
                            else -> "The request was not accepted. Check the browser before trying again."
                        })); save()
                    }
                }
            }
        }.onFailure { problem = "The platform message could not be read. Send the questions again." }
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject {
        val builder = Request.Builder().url(ASSAAD_SERVER + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            "DELETE" -> builder.delete()
        }
        return http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.readNBytes(16_385) ?: error("Empty response")
            check(bytes.size <= 16_384)
            if (response.code == 401 && method == "DELETE") return@use JSONObject()
            check(response.isSuccessful) { if (response.code == 429) "Too many attempts. Wait a minute." else "Couldn't reach the Noti server. Try again." }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
    fun createCode() {
        if (busy) return
        busy = true; problem = null
        scope.launch {
            val previous = credentials
            val result = withContext(Dispatchers.IO) { runCatching {
                if (previous != null) request("/v1/session", "DELETE", token = previous.getString("token"))
                request("/v1/pairings/create", "POST")
            } }
            result.onSuccess { value ->
                runCatching {
                    secrets.save(value.toString())
                    val resume = foreground
                    stop(); credentials = value; code = value.getString("code"); codeExpiresAt = value.getLong("expiresAt")
                    paired = false; if (resume) start()
                }.onFailure { problem = "This device couldn't store the pairing securely. Try again." }
            }.onFailure { problem = it.message ?: "Couldn't create a pairing code." }
            busy = false
        }
    }
    fun disconnect() {
        val value = credentials ?: return
        if (busy) return
        busy = true; problem = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { request("/v1/session", "DELETE", token = value.getString("token")) } }
            result.onSuccess { stop(); credentials = null; secrets.clear(); code = null; paired = false }
                .onFailure { problem = it.message ?: "Couldn't end the session. Try again." }
            busy = false
        }
    }
    fun openBoard(): String {
        val existing = boardId?.let(library::notebook)?.takeIf { it.trashedAt == null }
        if (existing != null) return existing.id
        val board = library.create("Plataforma Assaad", Paper(PaperTemplate.Grid), null, board = true)
        boardId = board.id; prefs.edit().putString("board", board.id).apply(); return board.id
    }
    fun confirm(alternativeId: String) {
        val batch = batch ?: return; val question = current ?: return
        if (!connected || !paired || !browserOnline || !platformActive || question.alternatives.none { it.id == alternativeId }) return
        if (answers[question.id]?.status in listOf("pending", "unknown", "confirmed")) return
        val commandId = UUID.randomUUID().toString()
        answers = answers + (question.id to AssaadAnswer(commandId, alternativeId)); save()
        val accepted = send("answer", JSONObject().put("commandId", commandId).put("batchId", batch.id).put("questionId", question.id).put("alternativeId", alternativeId))
        if (!accepted) { answers = answers + (question.id to answers.getValue(question.id).copy(status = "failed", feedback = "Not sent. Reconnect and try again.")); save(); return }
        scope.launch {
            delay(120_000)
            val pending = answers[question.id]
            if (pending?.commandId == commandId && pending.status == "pending") {
                answers = answers + (question.id to pending.copy(status = "unknown", feedback = "No confirmation received. Check the browser before answering again.")); save()
            }
        }
    }
    fun allowRetry(questionId: String) {
        if (answers[questionId]?.status != "unknown") return
        answers = answers - questionId; save()
    }
    private fun save() {
        val json = JSONObject().put("batch", batch?.json?.let(::JSONObject) ?: JSONObject.NULL)
            .put("answers", JSONObject().apply { answers.forEach { (id, answer) -> put(id, answer.json()) } }).toString()
        writer.execute { runCatching {
            val output = file.startWrite()
            try { output.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        }.onFailure { scope.launch { problem = "Couldn't save the platform session. Your canvas ink is saved separately." } } }
    }
}
