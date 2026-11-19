package app.noti.ui.assaad

import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.noti.assaad.AssaadSession
import app.noti.assaad.AssaadTabletBrowser
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiIconButton
import app.noti.ui.common.NotiTextButton
import app.noti.ui.common.NotiTonalButton

/**
 * The on-tablet Assaad browser. Open, it's a full-screen window above everything,
 * canvas chrome and ink included (a dialog window, not a popup, so nothing the editor
 * adds later can cover it). Closed, the same WebView stays parked, invisible, in the
 * activity window: its login and its page stay alive so queued answers can go while you
 * write. Only while the person chose tablet mode.
 */
@Composable
fun AssaadTabletBrowserHost(session: AssaadSession, onCanvas: () -> Unit) {
    if (!session.useTabletBrowser) return
    val context = LocalContext.current
    key(session.browserGeneration) {
        val browser = remember { AssaadTabletBrowser(context, session::tabletLoading, session::tabletPage) }
        DisposableEffect(browser) {
            session.attachTabletBrowser(browser)
            onDispose { session.attachTabletBrowser(null); browser.destroy() }
        }
        if (session.browserOpen) {
            var useWebsite by remember { mutableStateOf(false) }
            val showFinder = session.tabletVerified && session.tabletPageLoaded && !useWebsite
            val keyboard = LocalSoftwareKeyboardController.current
            val close = { keyboard?.hide(); browser.view.clearFocus(); session.browserOpen = false }
            Dialog(onDismissRequest = { if (!showFinder && browser.view.canGoBack()) browser.back() else close() },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                    dismissOnClickOutside = false, dismissOnBackPress = true)) {
                AssaadBrowserScreen(session, browser, onClose = close, onCanvas = { close(); onCanvas() },
                    showFinder = showFinder, onShowWebsite = { useWebsite = true }, onShowFinder = { useWebsite = false })
            }
        } else {
            BrowserView(browser, Modifier.size(1.dp).alpha(0f).clearAndSetSemantics { })
        }
    }
}

/** The one WebView, moved between its parked spot and the open window. */
@Composable
private fun BrowserView(browser: AssaadTabletBrowser, modifier: Modifier) {
    AndroidView(factory = { browser.view.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = modifier,
        onRelease = {})
}

/** What the person can do next on the page they're on, from the session's state. */
private enum class Step { Loading, Checking, SignIn, Problem, FindQuestions, Ready, Saving }

private fun AssaadSession.step(): Step = when {
    !tabletPageLoaded -> Step.Loading
    tabletBusy && tabletVerified -> Step.Saving
    tabletBusy -> Step.Checking
    tabletNeedsLogin -> Step.SignIn
    !tabletVerified -> Step.Problem
    !tabletOnQuestionBank -> Step.FindQuestions
    else -> Step.Ready
}

@Composable
internal fun AssaadBrowserScreen(
    session: AssaadSession, browser: AssaadTabletBrowser?, onClose: () -> Unit, onCanvas: () -> Unit,
    showFinder: Boolean = false, onShowWebsite: () -> Unit = {}, onShowFinder: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(session, browser, onClose, onCanvas, showFinder, onShowWebsite, onShowFinder)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (browser != null) {
                    BrowserView(browser, if (showFinder) Modifier.size(1.dp).alpha(0f).clearAndSetSemantics { } else Modifier.fillMaxSize())
                    if (showFinder) AssaadQuestionFinder(session, browser, onCanvas)
                }
                val loading = !session.tabletPageLoaded && session.tabletProgress < 100
                val progress by animateFloatAsState(session.tabletProgress / 100f, label = "page progress")
                androidx.compose.animation.AnimatedVisibility(loading, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    LinearProgressIndicator(progress = { progress.coerceAtLeast(0.05f) }, modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = colors.primary, trackColor = Color.Transparent, drawStopIndicator = {})
                }
            }
            if (!showFinder) ActionBar(session, browser, onCanvas)
        }
    }
}

@Composable
private fun TopBar(
    session: AssaadSession, browser: AssaadTabletBrowser?, onClose: () -> Unit, onCanvas: () -> Unit,
    showFinder: Boolean, onShowWebsite: () -> Unit, onShowFinder: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NotiIconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close Assaad") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(if (compact) "Assaad" else "Plataforma Assaad", style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                ConnectionLine(session, compact)
            }
            if (showFinder) {
                NotiIconButton(onClick = onShowWebsite) { Icon(Icons.Outlined.Language, "Open Assaad website") }
            } else {
                NotiIconButton(onClick = { browser?.back() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back in Assaad")
                }
                NotiIconButton(onClick = { browser?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload Assaad") }
                if (browser != null && session.tabletVerified) {
                    NotiIconButton(onClick = onShowFinder) { Icon(Icons.Outlined.Search, "Find questions in Noti") }
                }
            }
            Spacer(Modifier.width(4.dp))
            NotiTonalButton(onClick = { session.studyNext(); onCanvas() }) {
                Icon(Icons.Outlined.Draw, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Canvas")
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
}

/** A colored dot and a few words: is Noti connected to the person's Assaad login? */
@Composable
private fun ConnectionLine(session: AssaadSession, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (tint, label) = when (session.step()) {
        Step.Loading -> colors.outline to "Loading…"
        Step.Checking -> colors.tertiary to "Checking your login…"
        Step.SignIn -> Color(0xFFE3AE3F) to "Not signed in"
        Step.Problem -> colors.error to "Not connected"
        else -> Color(0xFF3E8E5A) to if (compact) "Connected" else "Connected · app.plataformaassaad.com.br"
    }
    val dot by animateColorAsState(tint, label = "connection")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The bottom bar: one clear next step for where the person is. */
@Composable
private fun ActionBar(session: AssaadSession, browser: AssaadTabletBrowser?, onCanvas: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val step = session.step()
    val waiting = session.hasPendingAnswer
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val busy = step == Step.Loading || step == Step.Checking || step == Step.Saving
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimaryContainer)
                else Icon(stepIcon(step), null, Modifier.size(20.dp), tint = colors.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(stepTitle(step), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (waiting) "Finishing an answer you sent. One moment." else stepDetail(step, session),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (step) {
                Step.SignIn, Step.Problem -> NotiTextButton(onClick = session::checkTabletConnection, enabled = !waiting) { Text("Check again") }
                Step.FindQuestions -> NotiTonalButton(onClick = { browser?.home() }) {
                    Icon(Icons.Outlined.TravelExplore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Question bank")
                }
                Step.Ready, Step.Saving -> NotiButton(onClick = { session.importTabletQuestions(then = onCanvas) },
                    enabled = step == Step.Ready && !waiting) {
                    Text(if (step == Step.Saving) "Saving…" else "Study on canvas")
                    Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
                else -> Unit
            }
        }
    }
}

private fun stepIcon(step: Step): ImageVector = when (step) {
    Step.SignIn -> Icons.Outlined.Login
    Step.Problem -> Icons.Outlined.ErrorOutline
    Step.FindQuestions -> Icons.Outlined.Search
    else -> Icons.Outlined.School
}

private fun stepTitle(step: Step) = when (step) {
    Step.Loading -> "Opening Assaad"
    Step.Checking -> "Connecting"
    Step.SignIn -> "Sign in to Assaad"
    Step.Problem -> "Couldn't connect"
    Step.FindQuestions -> "Find some questions"
    Step.Ready -> "Ready when you are"
    Step.Saving -> "Saving questions"
}

private fun stepDetail(step: Step, session: AssaadSession) = when (step) {
    Step.Loading -> "Loading app.plataformaassaad.com.br"
    Step.Checking -> "Checking your Assaad login on this tablet."
    Step.SignIn -> "Use the form above. Noti connects as soon as you're in."
    Step.Problem -> session.tabletStatus
    Step.FindQuestions -> session.tabletNotice ?: "Open the question bank and run a search."
    Step.Ready -> session.tabletNotice ?: "Search, then study this page's questions on your canvas."
    Step.Saving -> session.tabletStatus
}
