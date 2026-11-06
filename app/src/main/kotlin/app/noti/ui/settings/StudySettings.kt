package app.noti.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.noti.data.SettingsStore
import app.noti.study.MistralClient
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiGroup
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Study: the Mistral API key Study boards use, and what gets sent. */
@Composable
internal fun StudySection(settings: SettingsStore) {
    val keys = settings.studyKey
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    fun test(key: String, saveAfter: Boolean) {
        busy = true; status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { MistralClient(key).check() } }
            busy = false
            if (result.isSuccess) {
                if (saveAfter) runCatching { keys.save(key) }.onFailure {
                    status = false to "This device couldn't store the key safely, so it wasn't saved."
                    return@launch
                }
                if (saveAfter) draft = ""
                status = true to if (saveAfter) "Saved. Study boards can make questions now." else "The key works."
            } else status = false to (result.exceptionOrNull()?.message ?: "That didn't work.")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Mistral API key", style = MaterialTheme.typography.titleMedium)
        Text("Study boards use Mistral Large 4 to turn a linked PDF into questions, flash cards and problems, " +
            "and to check the answers you loop. Paste your own key from Mistral's console; you pay Mistral " +
            "for what you use.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        NotiGroup {
            if (keys.hasKey) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, null, tint = colors.primary)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Key saved", style = MaterialTheme.typography.titleSmall)
                    Text("Ends in ${keys.hint.removePrefix("…")} · encrypted on this tablet", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                NotiTextButton(onClick = { keys.load()?.let { test(it, false) } }, enabled = !busy) { Text("Test") }
                NotiTextButton(onClick = { keys.clear(); status = null }, destructive = true, enabled = !busy) { Text("Remove") }
            }
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.trim() },
                    label = { Text(if (keys.hasKey) "Replace with another key" else "Paste your API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NotiButton(onClick = { test(draft, true) }, enabled = draft.length >= 16 && !busy) { Text("Check and save") }
                    NotiOutlinedButton(onClick = { uri.openUri("https://console.mistral.ai/api-keys") }) { Text("Get a key") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        status?.let { (ok, message) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                    tint = if (ok) colors.primary else colors.error, modifier = Modifier.size(20.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = if (ok) colors.onSurface else colors.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("What leaves the tablet", style = MaterialTheme.typography.titleMedium)
        Text("Only on Study boards, and only when you ask: the PDF (or page range) you make questions from, a picture of " +
            "an answer you loop for checking, or of notes you loop to make questions. Uploaded PDFs are deleted from " +
            "Mistral right after the questions are made. Everything else (your boards, flash card reviews, multiple " +
            "choice checking) stays on the tablet.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text("Model: Mistral Large 4 (${MistralClient.MODEL}).", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
