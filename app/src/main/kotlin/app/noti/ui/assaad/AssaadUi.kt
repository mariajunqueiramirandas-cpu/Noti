package app.noti.ui.assaad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noti.assaad.*
import app.noti.ui.common.*
import kotlinx.coroutines.delay

val LocalAssaadSession = staticCompositionLocalOf<AssaadSession?> { null }

private val Connected = Color(0xFF3E8E5A)
private val Attention = Color(0xFFE3AE3F)

/** How the connection looks right now, in a dot and a few words. */
private data class Status(val color: Color, val headline: String, val detail: String)

@Composable
private fun AssaadSession.status(): Status {
    val colors = MaterialTheme.colorScheme
    return if (useTabletBrowser) when {
        tabletVerified -> Status(Connected, "Connected on this tablet", "$savedQuestionCount saved questions. Answers go through your Assaad login.")
        tabletNeedsLogin -> Status(Attention, "Sign in to Assaad", "Open Assaad and sign in. Noti connects as soon as you're in.")
        tabletBusy -> Status(colors.tertiary, "Connecting…", tabletStatus)
        !tabletPageLoaded -> Status(colors.outline, "Not open yet", "Open Assaad to sign in and find questions.")
        else -> Status(colors.error, "Not connected", tabletStatus)
    } else when {
        platformActive -> Status(Connected, "Active in your browser", "Assaad is active in your browser.")
        paired && browserOnline -> Status(Connected, "Browser connected", "Open Assaad in Edge or Chrome to continue.")
        paired -> Status(Attention, "Waiting for your browser", "Open Edge or Chrome with the Noti extension.")
        else -> Status(colors.outline, "Not paired", "Pair the Noti extension in Edge or Chrome with a one-time code.")
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun AssaadLibraryEntry() {
    val session = LocalAssaadSession.current ?: return
    val colors = MaterialTheme.colorScheme
    val status = session.status()
    Surface(onClick = { session.dialogOpen = true }, shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.School, null, Modifier.size(20.dp), tint = colors.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Plataforma Assaad", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status.color, Modifier.size(8.dp)); Spacer(Modifier.width(6.dp))
                    Text(status.headline + if (session.savedQuestionCount > 0) " · ${session.savedQuestionCount} questions" else "",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
        }
    }
}

/** Connection, saved sets and the answer queue. [onOpenBoard] opens the Assaad study board. */
@Composable
fun AssaadPairingDialog(session: AssaadSession, onOpenBoard: (String) -> Unit) {
    if (!session.dialogOpen) return
    val goToBoard = { session.dialogOpen = false; onOpenBoard(session.openBoard()) }
    NotiDialogFrame(onDismissRequest = { session.dialogOpen = false }, title = { Text("Plataforma Assaad") }, maxWidth = 520.dp,
        actions = {
            NotiTextButton(onClick = { session.dialogOpen = false }) { Text("Close") }
            if (session.useTabletBrowser) {
                NotiTonalButton(onClick = { session.studyNext(); goToBoard() }) { Text("Open canvas") }
                NotiButton(onClick = session::openTabletBrowser) { Text("Open Assaad") }
            } else NotiButton(onClick = { session.studyNext(); goToBoard() }) { Text("Open canvas") }
        }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotiFilterChip(selected = session.useTabletBrowser, onClick = { session.useTablet(true) }, enabled = !session.hasPendingAnswer,
                label = { Text("On this tablet") }, leadingIcon = { Icon(Icons.Outlined.TabletAndroid, null, Modifier.size(18.dp)) })
            NotiFilterChip(selected = !session.useTabletBrowser, onClick = { session.useTablet(false) }, enabled = !session.hasPendingAnswer,
                label = { Text("Edge or Chrome") }, leadingIcon = { Icon(Icons.Outlined.Laptop, null, Modifier.size(18.dp)) })
        }
        val status = session.status()
        NotiGroup {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(status.headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(status.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!session.useTabletBrowser && !session.paired) PairingCode(session)
        }
        if (session.batches.isNotEmpty()) {
            Text("Saved question sets", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            SavedAssaadSets(session) { id ->
                session.selectBatch(id)
                session.focus = session.batches.find { it.id == id }?.questions?.firstOrNull()?.id ?: ""
                goToBoard()
            }
            OfflineImages(session)
        } else if (session.useTabletBrowser) {
            Text("Open Assaad, search the question bank and tap Study on canvas. The questions open on your study board, ready to work through.")
        }
        session.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (session.hasSession && !session.useTabletBrowser) NotiTextButton(onClick = session::disconnect, enabled = !session.busy, destructive = true,
            contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Disconnect devices") }
    }
}

@Composable
private fun PairingCode(session: AssaadSession) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.codeExpiresAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val code = session.code?.takeIf { session.codeExpiresAt > now }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (code != null) {
            Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp), color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = "Pairing code $code" })
            val left = session.codeExpiresAt - now
            Text("Type it in the Noti extension · expires in ${left / 60_000}:${(left / 1000 % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall)
        } else {
            NotiButton(onClick = session::createCode, enabled = !session.busy) { Text(if (session.busy) "Connecting…" else "Generate pairing code") }
            Text("Server: noti.vendua.com.br", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SavedAssaadSets(session: AssaadSession, onStudy: (String) -> Unit) {
    NotiGroup {
        session.batches.asReversed().forEachIndexed { i, batch ->
            if (i > 0) NotiGroupDivider()
            val answered = batch.questions.count { session.answerFor(it.id)?.status == "confirmed" }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Study this set") { onStudy(batch.id) }
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(batch.questions.firstOrNull()?.tags?.joinToString(" · ")?.takeIf { it.isNotBlank() } ?: "Assaad questions", maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("${batch.questions.size} questions · $answered checked · ${org.json.JSONObject(batch.json).optString("capturedAt").take(10)}",
                        style = MaterialTheme.typography.labelSmall)
                }
                NotiIconButton(onClick = { session.removeBatch(batch.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove saved set") }
            }
        }
    }
}

@Composable
private fun OfflineImages(session: AssaadSession) {
    if (session.totalImages > 0 && session.savedImages < session.totalImages) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Images offline: ${session.savedImages}/${session.totalImages}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        NotiTextButton(onClick = session::saveImages, enabled = !session.savingImages) { Text(if (session.savingImages) "Saving…" else "Save images") }
    }
}
