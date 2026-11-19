package app.noti.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.noti.openrouter.OpenRouterClient
import app.noti.openrouter.OpenRouterFailure
import app.noti.openrouter.openRouterKeys
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun OpenRouterSettings() {
    val context = LocalContext.current.applicationContext
    val keys = remember(context) { openRouterKeys(context) }
    val client = remember { OpenRouterClient() }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Text("OpenRouter", style = MaterialTheme.typography.headlineSmall)
    Text("Model · ${OpenRouterClient.MODEL}")
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it; status = "" },
        label = { Text("API key") },
        placeholder = { if (keys.hasKey) Text("Saved key ${keys.hint}") },
        supportingText = { Text("Testing sends a short prompt to OpenRouter using your key.") },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NotiButton(enabled = !busy && draft.isNotBlank(), onClick = {
            val key = draft.trim()
            if (!key.all { it in '!'..'~' }) {
                status = "Enter a valid API key."
                return@NotiButton
            }
            busy = true
            status = ""
            scope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) { runCatching { keys.save(key) }.isSuccess }
                    if (saved) draft = ""
                    status = if (saved) "API key saved on this device." else "This device could not save the API key."
                } finally {
                    busy = false
                }
            }
        }) { Text("Save key") }
        NotiOutlinedButton(enabled = !busy && (keys.hasKey || draft.isNotBlank()), onClick = {
            val entered = draft.trim()
            busy = true
            testing = true
            status = ""
            scope.launch {
                try {
                    val key = entered.ifEmpty { withContext(Dispatchers.IO) { keys.load() }.orEmpty() }
                    status = when {
                        key.isEmpty() -> "Enter your OpenRouter API key again."
                        withTimeoutOrNull(45_000) { client.testConnection(key) } == null -> "The OpenRouter request timed out."
                        else -> "Connected to HY4 Flash."
                    }
                } catch (error: OpenRouterFailure) {
                    status = connectionError(error)
                } catch (_: IllegalArgumentException) {
                    status = "Enter a valid API key."
                } finally {
                    busy = false
                    testing = false
                }
            }
        }) { Text(if (testing) "Testing…" else "Test connection") }
        if (keys.hasKey) NotiTextButton(enabled = !busy, onClick = {
            keys.clear()
            status = "API key removed."
        }) { Text("Remove key") }
    }
    if (status.isNotEmpty()) Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private fun connectionError(error: OpenRouterFailure): String {
    if (error !is OpenRouterFailure.Api) return error.message.orEmpty()
    val wait = error.retryAfterSeconds?.takeIf { it > 0 }?.let { " Try again in $it seconds." }.orEmpty()
    return when (error.status) {
        401 -> "OpenRouter rejected this API key."
        402 -> if (error.limitSource == "openrouter_in_flight_budget") "OpenRouter's spending limit is temporarily reached.$wait"
            else "Check your OpenRouter credits and API key spending limit."
        403 -> "OpenRouter denied access. Check this API key’s permissions and limits."
        429 -> "OpenRouter is rate limiting requests.$wait"
        502, 503 -> "HY4 Flash is temporarily unavailable on OpenRouter.$wait"
        else -> error.message.orEmpty()
    }
}
