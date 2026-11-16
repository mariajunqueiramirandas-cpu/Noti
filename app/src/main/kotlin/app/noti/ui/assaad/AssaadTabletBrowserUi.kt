package app.noti.ui.assaad

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.noti.assaad.AssaadSession
import app.noti.assaad.AssaadTabletBrowser

/** Kept minimized only while the user has selected tablet mode; no service or external browser is needed. */
@Composable
fun AssaadTabletBrowserHost(session: AssaadSession, onCanvas: () -> Unit) {
    if (!session.useTabletBrowser) return
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    key(session.browserGeneration) {
        val browser = remember { AssaadTabletBrowser(context, session::tabletPage) }
        DisposableEffect(browser) {
            session.attachTabletBrowser(browser)
            onDispose { session.attachTabletBrowser(null); browser.destroy() }
        }
        Popup(alignment = Alignment.TopStart,
            onDismissRequest = { keyboard?.hide(); browser.view.clearFocus(); session.browserOpen = false },
            properties = PopupProperties(focusable = session.browserOpen, dismissOnBackPress = true, dismissOnClickOutside = false, clippingEnabled = true)) {
            Surface(modifier = if (session.browserOpen) Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing) else Modifier.size(0.dp)) {
                Column {
                    if (session.browserOpen) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = browser::back) { Icon(Icons.Outlined.ArrowBack, "Back in Assaad") }
                            Text("Plataforma Assaad", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { session.browserGeneration++ }) { Icon(Icons.Outlined.Refresh, "Reload Assaad") }
                            TextButton(onClick = { keyboard?.hide(); browser.view.clearFocus(); session.browserOpen = false; onCanvas() }) { Text("Canvas") }
                        }
                        Text("app.plataformaassaad.com.br", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = session::checkTabletConnection, enabled = session.tabletPageLoaded && !session.tabletBusy && !session.hasPendingAnswer, modifier = Modifier.weight(1f)) { Text("Check connection") }
                            Button(onClick = session::importTabletQuestions, enabled = session.tabletPageLoaded && !session.tabletBusy && !session.hasPendingAnswer, modifier = Modifier.weight(1f)) { Text("Save questions") }
                        }
                        Text(session.tabletStatus, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                        if (session.tabletBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    AndroidView(factory = { browser.view }, modifier = if (session.browserOpen) Modifier.weight(1f).fillMaxWidth() else Modifier.size(0.dp))
                }
            }
        }
    }
}
