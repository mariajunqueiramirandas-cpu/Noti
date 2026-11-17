package app.noti.assaad

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume

const val ASSAAD_WEBSITE = "https://app.plataformaassaad.com.br"
private const val QUESTION_BANK = "$ASSAAD_WEBSITE/banco-de-questoes/explorar"
/** The question bank's search, where questions can be saved. */
internal fun assaadQuestionBank(url: String?): Boolean = trustedAssaadPage(url) && runCatching {
    URI(url!!).path.trimEnd('/') == "/banco-de-questoes/explorar"
}.getOrDefault(false)
internal fun trustedAssaadPage(url: String?): Boolean = runCatching {
    val uri = URI(url ?: "")
    uri.scheme == "https" && uri.host == "app.plataformaassaad.com.br" && uri.port == -1 && uri.userInfo == null
}.getOrDefault(false)

interface AssaadLocalBrowser {
    suspend fun operation(input: JSONObject): JSONObject
    suspend fun readQuestions(): JSONObject
    fun resume() {}
    fun pause() {}
}

/** A real authenticated page: standard CORS, default browser identity, no native HTTP proxy. */
@SuppressLint("SetJavaScriptEnabled")
class AssaadTabletBrowser(
    context: Context,
    private val progress: (Int) -> Unit = {},
    private val state: (Boolean, String, String?) -> Unit,
) : AssaadLocalBrowser {
    val view = WebView(context)
    private val prefs = context.getSharedPreferences("assaad_browser_budget", Context.MODE_PRIVATE)
    private val operations = Mutex()
    private val api = context.assets.open("platform-api.js").bufferedReader().use { it.readText() }.replace("export async function", "async function")
    private val reader = context.assets.open("reader.js").bufferedReader().use { it.readText() }.replace("export function", "function")
    private var activeExchange: (() -> Unit)? = null
    private var alive = true
    var loaded = false; private set
    init {
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true; javaScriptCanOpenWindowsAutomatically = false
        }
        // No JavaScriptInterface, cookie export, shouldInterceptRequest, SSL bypass, or custom User-Agent.
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress(newProgress) }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || trustedAssaadPage(request.url.toString())) return false
                // Anything that isn't Assaad opens in the person's own browser, never in this one.
                if (request.hasGesture() && request.url.scheme == "https") runCatching {
                    view.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                return true
            }
            // Assaad is a single-page site: its own navigation (signing in, searching) changes the address only.
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (loaded) { loaded = trustedAssaadPage(url); state(loaded, url, null) }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                activeExchange?.invoke(); activeExchange = null
                loaded = false; state(false, url, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                loaded = trustedAssaadPage(url); state(loaded, url, null)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { loaded = false; state(false, view.url ?: ASSAAD_WEBSITE, "Could not load Assaad. Check your connection.") }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                loaded = false; state(false, view.url ?: ASSAAD_WEBSITE, "The browser stopped. Reopen it to continue."); destroy(); return true
            }
        }
        view.loadUrl(QUESTION_BANK)
    }
    /** The question bank, where questions are found and saved. */
    fun home() { if (alive) view.loadUrl(QUESTION_BANK) }
    override fun resume() { if (alive) view.onResume() }
    override fun pause() { if (alive) view.onPause() }
    fun back() { if (alive && view.canGoBack()) view.goBack() }
    fun reload() { if (alive) view.reload() }
    fun destroy() { if (alive) { activeExchange?.invoke(); activeExchange = null; alive = false; loaded = false; view.stopLoading(); view.destroy() } }
    override suspend fun readQuestions(): JSONObject = operations.withLock {
        exchange(reader, "readQuestions()")
    }
    override suspend fun operation(input: JSONObject): JSONObject = operations.withLock {
        val now = System.currentTimeMillis()
        val cooldown = prefs.getLong("cooldown", 0)
        if (cooldown > now) return@withLock if (input.optString("operation") == "answer") JSONObject()
            .put("commandId", input.getJSONObject("command").getString("commandId")).put("status", "blocked").put("reason", "rate_limited")
            .put("correct", JSONObject.NULL).put("correctAlternativeId", JSONObject.NULL).put("feedback", "Assaad requested a pause. Wait before resuming the queue.")
        else JSONObject().put("ok", false).put("error", "rate_limited")
        val answer = input.optString("operation") == "answer"
        val waitUntil = maxOf(prefs.getLong("next", 0), if (answer) prefs.getLong("answerNext", 0) else 0)
        if (waitUntil > now) delay((waitUntil - now).coerceAtMost(60_000))
        val next = System.currentTimeMillis()
        withContext(Dispatchers.IO) { prefs.edit().putLong("next", next + 5000).apply { if (answer) putLong("answerNext", next + 10000) }.commit() }
        val result = exchange(api, "runAssaadOperation(" + input.toString() + ")")
        val finished = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            prefs.edit().putLong("next", finished + 5000).apply {
                if (answer) putLong("answerNext", finished + 10000)
                if (result.has("retryAfterMs")) putLong("cooldown", finished + result.optLong("retryAfterMs").coerceIn(60000, 86400000))
            }.commit()
        }
        result.remove("retryAfterMs")
        result
    }
    /** A one-use port is transferred only to the exact Assaad main-frame origin. */
    private suspend fun exchange(definitions: String, call: String): JSONObject = try { withTimeout(115_000) {
        check(alive && loaded && trustedAssaadPage(view.url)) { "Open Assaad and wait for the page to finish loading." }
        suspendCancellableCoroutine { continuation ->
            val ports = view.createWebMessageChannel()
            val id = UUID.randomUUID().toString()
            fun close() { ports.forEach { runCatching { it.close() } } }
            ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                override fun onMessage(port: WebMessagePort, message: WebMessage) {
                    if (!continuation.isActive) return
                    val data = message.data
                    val result = runCatching {
                        check(trustedAssaadPage(view.url) && data != null && data.length <= 1_048_576)
                        JSONObject(data!!)
                    }
                    close(); activeExchange = null
                    continuation.resume(result.getOrElse { JSONObject().put("ok", false).put("error", "invalid_response") })
                }
            })
            activeExchange = {
                if (continuation.isActive) { close(); continuation.resume(JSONObject().put("ok", false).put("error", "page_changed")) }
            }
            continuation.invokeOnCancellation { view.post { close(); activeExchange = null } }
            val script = """
                (() => {
                  const receive = event => {
                    if (event.data !== "$id" || !event.ports || !event.ports[0]) return;
                    window.removeEventListener('message', receive);
                    const port = event.ports[0];
                    Promise.resolve().then(async () => { $definitions; return await $call; })
                      .then(value => port.postMessage(JSON.stringify(value)), () => port.postMessage(JSON.stringify({ok:false,error:'platform_unavailable'})))
                      .finally(() => port.close());
                  };
                  window.addEventListener('message', receive);
                })();
            """.trimIndent()
            view.evaluateJavascript(script) {
                if (continuation.isActive && trustedAssaadPage(view.url)) {
                    view.postWebMessage(WebMessage(id, arrayOf(ports[1])), Uri.parse(ASSAAD_WEBSITE))
                }
            }
        }
    } } catch (_: TimeoutCancellationException) {
        // The page may have reached a write. The caller must keep this outcome unknown.
        JSONObject().put("ok", false).put("error", "timeout")
    }
}
