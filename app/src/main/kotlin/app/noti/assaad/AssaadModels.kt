package app.noti.assaad

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

const val ASSAAD_SERVER = "https://noti.vendua.com.br"
private val safeId = Regex("[A-Za-z0-9_-]{1,100}")

data class AssaadImage(val alt: String, val url: String?)
data class AssaadAlternative(val id: String, val label: String, val text: String, val images: List<AssaadImage>)
data class AssaadQuestion(val id: String, val prompt: String, val source: String, val tags: List<String>, val difficulty: String?,
    val images: List<AssaadImage>, val alternatives: List<AssaadAlternative>) {
    val contentRef: String by lazy {
        val value = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(prompt); field(images.size.toString()); images.forEach { field(it.alt); field(it.url ?: "") }
            field(alternatives.size.toString())
            alternatives.sortedBy { it.id }.forEach { a -> field(a.id); field(a.text); field(a.images.size.toString()); a.images.forEach { field(it.alt); field(it.url ?: "") } }
        }
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val printable: String get() = prompt + "\n\n" + alternatives.joinToString("\n\n") { "${it.label}) ${it.text}" }
}
data class AssaadBatch(val id: String, val url: String, val page: String?, val questions: List<AssaadQuestion>, val json: String, val accountRef: String? = null, val transport: String? = null) {
    companion object {
        fun parse(o: JSONObject): AssaadBatch {
            val id = o.getString("batchId").also { java.util.UUID.fromString(it) }
            val source = o.getJSONObject("source")
            val url = source.getString("url")
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "app.plataformaassaad.com.br" && uri.port == -1 && uri.userInfo == null &&
                Regex("/banco-de-questoes/explorar/?").matches(uri.path))
            val raw = o.getJSONArray("questions")
            require(raw.length() in 1..100 && o.toString().length <= 1_048_576)
            val questions = (0 until raw.length()).map { index ->
                val q = raw.getJSONObject(index)
                val choices = q.getJSONArray("alternatives")
                require(choices.length() in 2..12)
                val alternatives = (0 until choices.length()).map { n ->
                    val a = choices.getJSONObject(n)
                    AssaadAlternative(a.id(), a.getString("label").take(10), a.getString("text").also { require(it.length <= 20000) }, images(a))
                }
                require(alternatives.map { it.id }.distinct().size == alternatives.size)
                require(images(q).size + alternatives.sumOf { it.images.size } <= 16)
                AssaadQuestion(q.id(), q.getString("prompt").also { require(it.length <= 50000) }, q.optString("source").take(2000),
                    q.optJSONArray("tags")?.let { tags -> (0 until tags.length()).map { tags.getString(it).take(200) } } ?: emptyList(),
                    q.optString("difficulty").takeIf { it != "null" && it.isNotBlank() }, images(q), alternatives)
            }
            require(questions.map { it.id }.distinct().size == questions.size)
            val accountRef = o.optString("accountRef").takeIf { it.isNotBlank() && it != "null" }
            require(accountRef == null || Regex("[a-f0-9]{64}").matches(accountRef))
            val transport = o.optString("transport").takeIf { it.isNotBlank() && it != "null" }
            require(transport == null || transport == "api-v1")
            require(transport != "api-v1" || accountRef != null)
            return AssaadBatch(id, url, source.optString("page").takeIf { it != "null" && it.isNotBlank() }, questions, o.toString(), accountRef, transport)
        }
        private fun JSONObject.id(): String = getString("id").also { require(safeId.matches(it)) }
        private fun images(o: JSONObject): List<AssaadImage> {
            val images = o.optJSONArray("images") ?: return emptyList()
            require(images.length() <= 20)
            return (0 until images.length()).map { i ->
                val image = images.getJSONObject(i)
                AssaadImage(image.optString("alt").take(2000), image.optString("url").takeIf { safeImageUrl(it) })
            }
        }
    }
}

/** Image requests never carry the relay bearer or platform cookies, and never follow redirects. */
fun safeImageUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase() ?: return false
    uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && value.length <= 4096 &&
        host != "localhost" && !host.endsWith(".localhost") && !host.contains(':') && !host.matches(Regex("[0-9.]+"))
}.getOrDefault(false)

data class AssaadAnswer(val commandId: String, val alternativeId: String, val status: String = "pending", val feedback: String = "Waiting for Assaad…",
    val correct: Boolean? = null, val correctAlternativeId: String? = null, val batchId: String? = null, val reason: String? = null) {
    fun json(): JSONObject = JSONObject().put("commandId", commandId).put("alternativeId", alternativeId).put("status", status)
        .put("feedback", feedback).put("correct", correct ?: JSONObject.NULL).put("correctAlternativeId", correctAlternativeId ?: JSONObject.NULL).put("batchId", batchId ?: JSONObject.NULL).put("reason", reason ?: JSONObject.NULL)
    companion object {
        fun parse(o: JSONObject) = AssaadAnswer(o.getString("commandId"), o.getString("alternativeId"), o.getString("status"), o.optString("feedback"),
            if (o.isNull("correct")) null else o.optBoolean("correct"), o.optString("correctAlternativeId").takeIf { it != "null" && it.isNotBlank() }, o.optString("batchId").takeIf { it != "null" && it.isNotBlank() }, o.optString("reason").takeIf { it != "null" && it.isNotBlank() })
    }
}

internal fun AssaadBatch.recordKey(questionId: String) = (accountRef ?: "legacy") + ":" + questionId + ":" + (questions.find { it.id == questionId }?.contentRef ?: "missing")
