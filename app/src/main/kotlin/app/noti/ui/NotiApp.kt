package app.noti.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.noti.assaad.AssaadSession
import app.noti.ui.assaad.LocalAssaadSession
import app.noti.ui.assaad.AssaadPairingDialog
import app.noti.ui.assaad.AssaadTabletBrowserHost
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.noti.data.LibraryStore
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.ink.StylusKeyHost
import app.noti.ui.editor.EditorScreen
import app.noti.ui.library.LibraryScreen
import app.noti.ui.settings.SettingsScreen
import app.noti.ui.theme.NotiTheme

private const val LIBRARY = "library"
private const val SETTINGS = "settings"
private const val EDITOR = "editor:"

/** Three destinations; plain state instead of a navigation library. */
@Composable
fun NotiApp(library: LibraryStore, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val platform = remember(library) { AssaadSession(context.applicationContext, library, scope) }
    DisposableEffect(platform) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) platform.start()
            if (event == Lifecycle.Event.ON_STOP) platform.stop()
        }
        lifecycle?.addObserver(observer)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) platform.start()
        onDispose { lifecycle?.removeObserver(observer); platform.dispose() }
    }
    CompositionLocalProvider(LocalAssaadSession provides platform) {
    NotiTheme(settings.theme) {
        SystemBarIcons(settings.theme)
        XiaomiStylusHostSync(settings.penButtonsEnabled && settings.penLegacyKeys)
        var route by rememberSaveable { mutableStateOfRoute() }
        AssaadPairingDialog(platform, onOpenBoard = { route = EDITOR + it })
        when {
            route == SETTINGS -> SettingsScreen(settings, onBack = { route = LIBRARY })
            route.startsWith(EDITOR) -> {
                val id = route.removePrefix(EDITOR)
                val notebook = library.notebook(id)
                if (notebook == null) {
                    LaunchedEffect(id) { route = LIBRARY }
                } else {
                    LaunchedEffect(id) { library.markOpened(id) }
                    EditorScreen(notebook, library, settings, onBack = { route = LIBRARY })
                }
            }
            else -> LibraryScreen(
                library = library,
                settings = settings,
                onOpen = { route = EDITOR + it },
                onSettings = { route = SETTINGS },
            )
        }
        AssaadTabletBrowserHost(platform, onCanvas = { route = EDITOR + platform.openBoard() })
    }
    }
}

private fun mutableStateOfRoute() = androidx.compose.runtime.mutableStateOf(LIBRARY)

@Composable
private fun XiaomiStylusHostSync(hijack: Boolean) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        (activity as? StylusKeyHost)?.stylusKeys?.hijackXiaomiOsShortcuts = hijack
    }
}

/** Dark status/navigation bar icons on the light theme, light ones on the dark theme. */
@Composable
private fun SystemBarIcons(mode: ThemeMode) {
    val view = LocalView.current
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
