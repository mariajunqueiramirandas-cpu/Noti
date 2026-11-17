package app.noti.assaad

import android.content.Context
import androidx.compose.runtime.*
import app.noti.data.*
import app.noti.data.EncryptedSecretStore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import kotlin.coroutines.resume
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Foreground-only relay client. All Compose state is updated on the main scope. */
@Stable
class AssaadSession(context: Context, private val library: LibraryStore, private val scope: CoroutineScope, private val relaySend: ((String) -> Boolean)? = null) {
    private val secrets = EncryptedSecretStore(context, "platform_keys", "noti-platform-session")
    private var credentials = secrets.load()?.let { runCatching { JSONObject(it) }.getOrNull() }
    private val prefs = context.getSharedPreferences("platform_preferences", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "assaad_session.json")
    var useTabletBrowser by mutableStateOf(prefs.getBoolean("tabletBrowser", false)); private set
    var browserOpen by mutableStateOf(false)
    var browserGeneration by mutableIntStateOf(0)
    var tabletPageLoaded by mutableStateOf(false); private set
    var tabletVerified by mutableStateOf(false); private set
    var tabletBusy by mutableStateOf(false); private set
    var tabletStatus by mutableStateOf("Sign into Assaad on this tablet."); private set
    var tabletUrl by mutableStateOf(ASSAAD_WEBSITE); private set
    /** Page load progress, 0–100. */
    var tabletProgress by mutableIntStateOf(0); private set
    /** Why the last save of a page didn't work, until the page changes. */
    var tabletNotice by mutableStateOf<String?>(null); private set
    /** The last check found no Assaad login on this tablet. */
    var tabletNeedsLogin by mutableStateOf(false); private set
    /** The tablet browser shows a question search that can be saved. */
    val tabletOnQuestionBank get() = tabletPageLoaded && assaadQuestionBank(tabletUrl)
    /** What the study board should open on next: a question id, or "" for wherever is next. Consumed by the board. */
    var focus by mutableStateOf<String?>(null)
    private var tabletBrowser: AssaadLocalBrowser? = null
    val hasPendingAnswer get() = records.values.any { it.status == "pending" }
    fun attachTabletBrowser(browser: AssaadLocalBrowser?) { tabletBrowser = browser; if (foreground) browser?.resume() }
    fun tabletPage(loaded: Boolean, url: String, error: String?) {
        if (url != tabletUrl) tabletNotice = null
        tabletPageLoaded = loaded; tabletUrl = url
        if (!loaded) { tabletVerified = false; if (error == null) tabletStatus = "Loading Assaad…" }
        if (error != null) { tabletStatus = error; tabletVerified = false; queuePaused = true }
        // Assaad is a single-page site: signing in changes the address without a new page load.
        else if (loaded && useTabletBrowser && !tabletVerified) checkTabletConnection()
    }
    fun tabletLoading(progress: Int) { tabletProgress = progress.coerceIn(0, 100) }
    fun useTablet(enabled: Boolean) {
        if (enabled == useTabletBrowser) return
        if (hasPendingAnswer) { problem = "Wait for the current submission before changing connection mode."; return }
        val resume = foreground; stop(); useTabletBrowser = enabled
        prefs.edit().putBoolean("tabletBrowser", enabled).apply()
        if (!enabled) browserOpen = false
        if (resume) start()
    }
    /** Shows the tablet browser (switching to it when Edge was in use). */
    fun openTabletBrowser() {
        if (!useTabletBrowser) useTablet(true)
        if (useTabletBrowser) { dialogOpen = false; browserOpen = true }
    }
    fun checkTabletConnection() {
        val browser = tabletBrowser ?: return
        if (tabletBusy || !tabletPageLoaded || hasPendingAnswer) return
        tabletBusy = true
        if (!tabletVerified) tabletStatus = "Checking your Assaad login…"
        val checkedUrl = tabletUrl
        scope.launch {
            try {
                val result = browser.operation(JSONObject().put("operation", "connect"))
                tabletVerified = result.optBoolean("ok") && result.optBoolean("corsVerified")
                tabletNeedsLogin = !tabletVerified && result.optString("error") == "login_required"
                tabletStatus = if (tabletVerified) "Connected on this tablet." else if (tabletNeedsLogin) "Sign in to Assaad to continue." else assaadOperationProblem(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { tabletVerified = false; tabletStatus = "Connection check failed. Open Assaad and try again." }
            finally {
                tabletBusy = false; pump()
                // Signed in while this check ran: check the new page too.
                if (!tabletVerified && tabletPageLoaded && tabletUrl != checkedUrl) checkTabletConnection()
            }
        }
    }
    /** Saves the questions on the tablet browser's page, then [then] (to go study them). */
    fun importTabletQuestions(then: (() -> Unit)? = null) {
        val browser = tabletBrowser ?: return
        if (tabletBusy || !tabletPageLoaded || hasPendingAnswer) return
        tabletBusy = true
        tabletStatus = "Saving the questions on this page…"; tabletNotice = null
        scope.launch {
            try {
                val read = browser.readQuestions()
                check(read.optBoolean("ok") && read.optInt("skipped") == 0 && read.optJSONArray("questions")?.length()?.let { it > 0 } == true) {
                    "No questions found yet. Run a search in the question bank and wait for the results."
                }
                val batch = JSONObject().put("batchId", UUID.randomUUID().toString()).put("source", read.getJSONObject("source"))
                    .put("capturedAt", read.getString("capturedAt")).put("questions", read.getJSONArray("questions"))
                val imported = browser.operation(JSONObject().put("operation", "import").put("batch", batch))
                check(imported.optBoolean("ok")) { assaadOperationProblem(imported) }
                val value = imported.getJSONObject("batch")
                AssaadBatch.parse(value)
                receive(JSONObject().put("type", "questions").put("payload", value).toString())
                tabletVerified = true; tabletNeedsLogin = false
                tabletStatus = "Saved " + value.getJSONArray("questions").length() + " questions on this tablet."
                then?.invoke()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { tabletStatus = e.message ?: "Could not import this page."; tabletNotice = tabletStatus }
            finally { tabletBusy = false; pump() }
        }
    }
    private fun canSubmit() = if (useTabletBrowser) tabletBrowser != null && tabletVerified && tabletPageLoaded && !tabletBusy else connected && paired && browserOnline

    val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val media = AssaadMediaCache(context, http)
    var savedImages by mutableIntStateOf(0); private set
    var totalImages by mutableIntStateOf(0); private set
    var savingImages by mutableStateOf(false); private set
    private var mediaJob: Job? = null
    private fun imageUrls() = batches.flatMap { b -> b.questions.flatMap { q -> q.images + q.alternatives.flatMap { it.images } } }.mapNotNull { it.url }.distinct()
    suspend fun imageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) { media.load(url) }
    fun saveImages() {
        if (mediaJob?.isActive == true) return
        val urls = imageUrls(); totalImages = urls.size; savedImages = urls.count(media::contains)
        mediaJob = scope.launch {
            savingImages = true
            try { for (url in urls) { imageBytes(url); savedImages = imageUrls().count(media::contains) } }
            finally { totalImages = imageUrls().size; savedImages = imageUrls().count(media::contains); savingImages = false }
        }
    }
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "noti-platform-save").apply { isDaemon = true } }
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var heartbeat: Job? = null
    private var foreground = false
    private var backoff = 1000L

    var dialogOpen by mutableStateOf(false)
    var connected by mutableStateOf(false); private set
    var paired by mutableStateOf(false); private set
    var browserOnline by mutableStateOf(false); private set
    var platformActive by mutableStateOf(false); private set
    var activePath by mutableStateOf<String?>(null); private set
    var code by mutableStateOf<String?>(null); private set
    var codeExpiresAt by mutableLongStateOf(0L); private set
    var busy by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null)
    var batches by mutableStateOf<List<AssaadBatch>>(emptyList()); private set
    private var selectedBatchId by mutableStateOf<String?>(null)
    val batch get() = batches.find { it.id == selectedBatchId } ?: batches.lastOrNull()
    private var records by mutableStateOf<Map<String, AssaadAnswer>>(emptyMap())
    private var choices by mutableStateOf<Map<String, String>>(emptyMap())
    private var removedBatchIds = emptySet<String>()
    val answers get() = batch?.let { b -> b.questions.mapNotNull { q -> records[b.recordKey(q.id)]?.let { q.id to it } }.toMap() } ?: emptyMap()
    val drafts get() = batch?.let { b -> b.questions.mapNotNull { q -> choices[b.recordKey(q.id)]?.let { q.id to it } }.toMap() } ?: emptyMap()
    val draftCount get() = choices.keys.count { records[it]?.status !in listOf("queued", "pending", "confirmed", "unknown") }
    val savedQuestionCount get() = batches.flatMap { b -> b.questions.map { b.recordKey(it.id) } }.distinct().size
    var queuePaused by mutableStateOf(false); private set
    private var archiveLoaded by mutableStateOf(false)
    internal val libraryLoaded get() = archiveLoaded
    private var dirty = false
    private var dispatchJob: Job? = null
    private var browserReady = false
    private var browserAccount: String? = null
    var currentIndex by mutableIntStateOf(0)
    var boardId by mutableStateOf(prefs.getString("board", null)); private set
    val hasSession get() = credentials != null
    val current get() = batch?.questions?.getOrNull(currentIndex)

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { JSONObject((if (file.exists()) file else File(file.path + ".bak")).readText(Charsets.UTF_8)) }.getOrNull() }
            if (saved != null) runCatching {
                val raw = saved.optJSONArray("batches") ?: JSONArray().apply { saved.optJSONObject("batch")?.let(::put) }
                val removed = saved.optJSONArray("removedBatchIds")
                removedBatchIds = removedBatchIds + (0 until (removed?.length() ?: 0)).map { removed!!.getString(it) }
                val restored = (0 until raw.length().coerceAtMost(100)).mapNotNull { runCatching { AssaadBatch.parse(raw.getJSONObject(it)) }.getOrNull() }
                batches = (restored + batches).distinctBy { it.id }.filterNot { it.id in removedBatchIds }
                if (selectedBatchId == null) selectedBatchId = saved.optString("selectedBatchId").takeIf { it.isNotBlank() }
                val oldRecords = mutableMapOf<String, AssaadAnswer>()
                saved.optJSONObject("records")?.let { obj -> obj.keys().forEach { key -> oldRecords[key] = AssaadAnswer.parse(obj.getJSONObject(key)) } }
                // Migrate the v1 single-batch session into the permanent library.
                saved.optJSONObject("answers")?.let { obj -> restored.firstOrNull()?.let { b -> obj.keys().forEach { q -> oldRecords[b.recordKey(q)] = AssaadAnswer.parse(obj.getJSONObject(q)).copy(batchId = b.id) } } }
                val oldChoices = saved.optJSONObject("drafts")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } } ?: emptyMap()
                // Answers are sent one at a time, by hand. Anything an older version left waiting
                // to send (queued, or not accepted) goes back to being a pick; nothing sends by itself.
                val unsent = oldRecords.filterValues { it.status in listOf("queued", "blocked", "failed") }
                records = (oldRecords - unsent.keys).mapValues { (_, a) -> if (a.status == "pending") a.copy(status = "unknown", feedback = "Confirmation interrupted. Reconnect or check Assaad before another attempt.") else a } + records
                choices = unsent.mapValues { it.value.alternativeId } + oldChoices + choices
                queuePaused = saved.optBoolean("queuePaused") || records.values.any { it.status == "unknown" }
            }.onFailure { problem = "Some saved session data could not be restored. Your canvas is saved separately." }
            archiveLoaded = true
            if (connected) removedBatchIds.forEach { send("forget-questions", JSONObject().put("batchId", it)) }
            totalImages = imageUrls().size; savedImages = imageUrls().count(media::contains)
            if (dirty) save()
            pump()
        }
    }
    fun start() { foreground = true; if (useTabletBrowser) tabletBrowser?.resume() else connect(); pump() }
    fun stop() {
        foreground = false; tabletBrowser?.pause(); reconnect?.cancel(); heartbeat?.cancel(); socket?.close(1000, "App in background"); socket = null
        connected = false; browserOnline = false; platformActive = false
    }
    fun dispose() { stop(); writer.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    private fun connect() {
        if (useTabletBrowser) return
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
    private fun send(type: String, payload: JSONObject? = null): Boolean {
        val json = JSONObject().put("type", type).apply { payload?.let { put("payload", it) } }.toString()
        return relaySend?.invoke(json) ?: (socket?.send(json) == true)
    }
    internal fun receive(text: String) {
        runCatching {
            val message = JSONObject(text); val payload = message.optJSONObject("payload") ?: JSONObject()
            when (message.optString("type")) {
                "session" -> {
                    val first = !connected
                    connected = true; backoff = 1000; paired = payload.optBoolean("paired"); browserOnline = payload.optBoolean("peerOnline")
                    val presence = payload.optJSONObject("presence")
                    platformActive = presence?.optBoolean("active") == true
                    activePath = presence?.optString("path")?.takeIf { it != "null" }
                    browserReady = presence?.optBoolean("ready") == true
                    browserAccount = presence?.optString("accountRef")?.takeIf { Regex("[a-f0-9]{64}").matches(it) }
                    if (paired) code = null
                    if (first) { removedBatchIds.forEach { send("forget-questions", JSONObject().put("batchId", it)) }; heartbeat?.cancel(); heartbeat = scope.launch { while (isActive) { delay(20_000); send("ping") } } }
                    pump()
                }
                "questions" -> {
                    val incoming = AssaadBatch.parse(payload)
                    if (incoming.id !in removedBatchIds && batches.none { it.id == incoming.id }) {
                        if (batches.size >= 100) { problem = "The saved library is full. Remove a saved set before importing another."; return }
                        batches = batches + incoming; selectedBatchId = incoming.id; currentIndex = 0; save(); saveImages()
                        focus = incoming.questions.first().id
                    }
                }
                "forgotten" -> {
                    removedBatchIds = removedBatchIds - payload.optString("batchId"); save()
                }
                "answer-result" -> {
                    val id = payload.getString("commandId")
                    val entry = records.entries.find { it.value.commandId == id }
                    val b = batches.find { it.id == payload.optString("batchId") } ?: entry?.value?.batchId?.let { bid -> batches.find { it.id == bid } }
                    val qid = payload.optString("questionId").takeIf { it.isNotBlank() } ?: b?.questions?.find { b.recordKey(it.id) == entry?.key }?.id
                    val key = entry?.key ?: if (b != null && qid != null) b.recordKey(qid) else return
                    val previous = entry?.value ?: run {
                        val q = b?.questions?.find { it.id == qid } ?: return
                        val alternative = payload.optString("alternativeId")
                        if (q.alternatives.none { it.id == alternative }) return
                        AssaadAnswer(id, alternative, batchId = b.id)
                    }
                    val result = AssaadAnswer.parse(JSONObject(previous.json().toString()).apply {
                        for (field in listOf("status", "feedback", "correct", "correctAlternativeId", "reason")) if (payload.has(field)) put(field, payload.get(field))
                    })
                    if (result.status !in listOf("confirmed", "failed", "unknown", "blocked")) return
                    if (records[key] != result) {
                        records = records + (key to result)
                        if (result.status != "confirmed") queuePaused = true
                        save(); pump()
                    }
                }
                "error" -> {
                    val id = payload.optString("commandId")
                    records.entries.find { it.value.commandId == id }?.let { entry ->
                        val code = payload.optString("error")
                        records = records + (entry.key to entry.value.copy(status = "blocked", reason = code, feedback = when (code) {
                            "browser_offline" -> "Waiting for the paired browser."
                            "stale_question" -> "This saved set was not accepted. Update it from Assaad."
                            else -> "Submission was not accepted. Resume after checking the connection."
                        })); queuePaused = true; save()
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
            result.onSuccess { val resume = foreground; stop(); credentials = null; secrets.clear(); code = null; paired = false; if (resume) start() }
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
    fun selectBatch(id: String) {
        if (batches.none { it.id == id }) return
        selectedBatchId = id; currentIndex = 0; save()
    }
    fun removeBatch(id: String) {
        val b = batches.find { it.id == id } ?: return
        if (b.questions.any { records[b.recordKey(it.id)]?.status in listOf("pending", "unknown", "queued") }) {
            problem = "Finish or cancel this set's queue before removing it."; return
        }
        batches = batches.filterNot { it.id == id }
        removedBatchIds = removedBatchIds + id
        if (connected) send("forget-questions", JSONObject().put("batchId", id))
        val remainingKeys = batches.flatMap { other -> other.questions.map { other.recordKey(it.id) } }.toSet()
        records = records.filterKeys { it in remainingKeys }; choices = choices.filterKeys { it in remainingKeys }
        if (selectedBatchId == id) selectedBatchId = batches.lastOrNull()?.id
        currentIndex = 0; save()
        val urls = imageUrls()
        scope.launch { withContext(Dispatchers.IO) { media.retain(urls) }; totalImages = imageUrls().size; savedImages = imageUrls().count(media::contains) }
    }
    fun saveDraft(alternativeId: String) {
        val b = batch ?: return; val q = current ?: return
        val key = b.recordKey(q.id)
        if (q.alternatives.none { it.id == alternativeId } || records[key]?.status in listOf("pending", "queued", "unknown", "confirmed")) return
        choices = choices + (key to alternativeId)
        if (records[key]?.status in listOf("failed", "blocked")) records = records - key
        save()
    }
    /** Picks [alternativeId] for the current question and sends it, when an answer can go now. */
    fun confirm(alternativeId: String): Boolean {
        saveDraft(alternativeId)
        val q = current ?: return false
        return submit(q.id)
    }
    /**
     * An answer can go now: Assaad is connected and nothing else is on its way. Answers are
     * sent one at a time, each by its own tap, never queued up to go later in a run.
     */
    val canSendAnswer get() = archiveLoaded && canSubmit() && !hasAnswerOnItsWay
    val hasAnswerOnItsWay get() = records.values.any { it.status == "pending" || it.status == "queued" }
    private fun sendAnswer(b: AssaadBatch, qid: String): Boolean {
        val key = b.recordKey(qid); val alternative = choices[key] ?: return false
        if (records[key]?.status in listOf("pending", "queued", "unknown", "confirmed")) return false
        if (!foreground || !canSendAnswer) {
            problem = if (records.values.any { it.status == "pending" || it.status == "queued" }) "Wait for the answer on its way to Assaad."
                else "Connect Assaad to send this answer. Your pick is kept."
            return false
        }
        problem = null
        records = records + (key to AssaadAnswer(UUID.randomUUID().toString(), alternative, "queued", "Sending to Assaad…", batchId = b.id))
        queuePaused = false; save(); pump()
        return true
    }
    // ---- by question, for the study board (the newest saved set holding it wins) ----

    /** Every saved question once, oldest set first, each from the newest set that holds it. */
    val studyQuestions: List<Pair<AssaadBatch, AssaadQuestion>> get() {
        val seen = HashMap<String, Pair<AssaadBatch, AssaadQuestion>>()
        val order = ArrayList<String>()
        batches.forEach { b -> b.questions.forEach { q -> if (q.id !in seen) order += q.id; seen[q.id] = b to q } }
        return order.map { seen.getValue(it) }
    }
    private fun holder(questionId: String): Pair<AssaadBatch, AssaadQuestion>? =
        batches.asReversed().firstNotNullOfOrNull { b -> b.questions.find { it.id == questionId }?.let { b to it } }
    fun question(questionId: String): AssaadQuestion? = holder(questionId)?.second
    fun answerFor(questionId: String): AssaadAnswer? = holder(questionId)?.let { (b, q) -> records[b.recordKey(q.id)] }
    fun draftFor(questionId: String): String? = holder(questionId)?.let { (b, q) -> choices[b.recordKey(q.id)] }
    fun pick(questionId: String, alternativeId: String) {
        val (b, q) = holder(questionId) ?: return
        val key = b.recordKey(q.id)
        if (q.alternatives.none { it.id == alternativeId } || records[key]?.status in listOf("pending", "queued", "unknown", "confirmed")) return
        choices = choices + (key to alternativeId)
        if (records[key]?.status in listOf("failed", "blocked")) records = records - key
        save()
    }
    /** Sends this one question's pick, now. False (with [problem] saying why) when it can't go. */
    fun submit(questionId: String): Boolean {
        val (b, q) = holder(questionId) ?: return false
        return sendAnswer(b, q.id)
    }
    /** Unknown: the person checked Assaad, so allow another attempt. Not accepted: try this one again. */
    fun retry(questionId: String) {
        val (b, q) = holder(questionId) ?: return
        val key = b.recordKey(q.id)
        when (records[key]?.status) {
            "unknown" -> { choices = choices + (key to records.getValue(key).alternativeId); records = records - key; save() }
            "blocked", "failed" -> { choices = choices + (key to records.getValue(key).alternativeId); records = records - key; save(); sendAnswer(b, q.id) }
        }
    }
    /** Ask the study board to open on what's next. */
    fun studyNext() { focus = "" }

    fun allowRetry(questionId: String) {
        val b = batch ?: return; val key = b.recordKey(questionId)
        if (records[key]?.status != "unknown") return
        choices = choices + (key to records.getValue(key).alternativeId)
        records = records - key; save()
    }
    private fun pump() {
        if (!archiveLoaded || !foreground || !canSubmit() || queuePaused || dispatchJob?.isActive == true || records.values.any { it.status == "pending" }) return
        val entry = records.entries.firstOrNull { it.value.status == "queued" } ?: return
        val b = batches.find { it.id == entry.value.batchId } ?: return
        val q = b.questions.find { b.recordKey(it.id) == entry.key } ?: return
        dispatchJob = scope.launch {
            val pending = entry.value.copy(status = "pending", feedback = "Waiting for Assaad…")
            records = records + (entry.key to pending)
            // Persist BEFORE sending; process death can never turn a sent answer back into an unsent draft.
            val snapshot = snapshot()
            val stored = suspendCancellableCoroutine<Boolean> { continuation ->
                writer.execute { val ok = runCatching { writeSnapshot(snapshot) }.isSuccess; if (continuation.isActive) continuation.resume(ok) }
            }
            if (!stored) {
                records = records + (entry.key to pending.copy(status = "failed", feedback = "Couldn't save the submission. Nothing was sent.")); queuePaused = true; return@launch
            }
            if (!foreground || !canSubmit() || queuePaused) {
                // Nothing was sent: it goes back to being a pick, to send by hand later.
                records = records - entry.key; problem = "The connection dropped before sending. Your pick is kept."; save(); return@launch
            }
            if (useTabletBrowser) {
                val command = JSONObject().put("commandId", pending.commandId).put("batchId", b.id).put("questionId", q.id).put("alternativeId", pending.alternativeId)
                val result = try {
                    tabletBrowser!!.operation(JSONObject().put("operation", "answer").put("batch", JSONObject(b.json)).put("command", command))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { JSONObject().put("status", "unknown").put("feedback", "Check Assaad before another attempt.").put("correct", JSONObject.NULL).put("correctAlternativeId", JSONObject.NULL) }
                if (result.optString("status") !in listOf("confirmed", "blocked", "unknown", "failed")) {
                    result.put("status", "unknown").put("feedback", "Confirmation could not be verified. Check Assaad before another attempt.")
                }
                result.put("commandId", pending.commandId).put("batchId", b.id).put("questionId", q.id).put("alternativeId", pending.alternativeId)
                if (result.optString("reason") in listOf("login_required", "account_mismatch")) tabletVerified = false
                receive(JSONObject().put("type", "answer-result").put("payload", result).toString())
                return@launch
            }
            val restored = b.transport != "api-v1" || send("restore-questions", JSONObject(b.json))
            val accepted = restored && send("answer", JSONObject().put("commandId", pending.commandId).put("batchId", b.id).put("questionId", q.id).put("alternativeId", pending.alternativeId))
            if (!accepted) {
                records = records + (entry.key to pending.copy(status = "blocked", reason = "browser_offline", feedback = "Waiting for the paired browser.")); queuePaused = true; browserOnline = false; save(); return@launch
            }
            // The result handler pumps the next item. A missing acknowledgement pauses the queue.
            scope.launch {
                delay(120_000)
                if (records[entry.key]?.let { it.commandId == pending.commandId && it.status == "pending" } == true) {
                    records = records + (entry.key to pending.copy(status = "unknown", feedback = "No confirmation received. Check Assaad before trying again."))
                    queuePaused = true; save()
                }
            }
        }
        dispatchJob?.invokeOnCompletion { scope.launch { pump() } }
    }
    private fun snapshot(): String = JSONObject().put("version", 2)
        .put("batches", JSONArray().apply { batches.forEach { put(JSONObject(it.json)) } })
        .put("removedBatchIds", JSONArray(removedBatchIds.toList())).put("selectedBatchId", selectedBatchId ?: JSONObject.NULL).put("queuePaused", queuePaused)
        .put("drafts", JSONObject().apply { choices.forEach { (key, value) -> put(key, value) } })
        .put("records", JSONObject().apply { records.forEach { (key, value) -> put(key, value.json()) } }).toString()
    private fun writeSnapshot(json: String) { writeAssaadFile(file, json.toByteArray(Charsets.UTF_8)) }
    private fun save() {
        dirty = true
        if (!archiveLoaded) return
        val json = snapshot()
        writer.execute { runCatching { writeSnapshot(json) }.onFailure { scope.launch { problem = "Couldn't save the platform library. Your canvas ink is saved separately." } } }
    }
    internal suspend fun awaitSaved() {
        while (!archiveLoaded) delay(5)
        suspendCancellableCoroutine<Unit> { continuation -> writer.execute { if (continuation.isActive) continuation.resume(Unit) } }
    }
}
