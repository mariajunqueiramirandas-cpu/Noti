package app.noti.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.noti.ink.StylusKeyHost

/** Settings can live in the activity or its own Compose Dialog window. */
@Composable
internal fun StylusDialogKeys(hijackXiaomiOsShortcuts: Boolean = true) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val host = view.context.activity() as? StylusKeyHost
    SideEffect {
        host?.stylusKeys?.hijackXiaomiOsShortcuts = hijackXiaomiOsShortcuts
    }
    DisposableEffect(window, host) {
        val previous = window?.callback
        val callback = if (window != null && previous != null && host != null) object : Window.Callback by previous {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                (view.hasWindowFocus() && host.stylusKeys.dispatchFromFocusedWindow(event)) || previous.dispatchKeyEvent(event)

            override fun onWindowFocusChanged(hasFocus: Boolean) {
                host.stylusKeys.reset()
                previous.onWindowFocusChanged(hasFocus)
            }
        } else null
        if (callback != null) window?.callback = callback
        onDispose {
            host?.stylusKeys?.reset()
            if (callback != null && window?.callback === callback) window.callback = previous
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
