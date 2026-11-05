package app.noti

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.noti.data.LibraryStore
import app.noti.data.SettingsStore
import app.noti.ui.NotiApp
import app.noti.ink.StylusKeyDispatcher
import app.noti.ink.StylusKeyHost
import app.noti.ink.XiaomiStylusWindow

/**
 * The whole app is Compose. The ink itself never goes through Compose: each writing area
 * hosts the native engine's own input window and layers (app.noti.ink.InkLayer), so
 * recomposition, layout and GC on this thread can't delay the pen.
 */
class MainActivity : ComponentActivity(), StylusKeyHost {
    override val stylusKeys = StylusKeyDispatcher()
    private var resumed = false

    override fun getPackageName(): String =
        XiaomiStylusWindow.resolvePackageName(super.getPackageName(), stylusKeys.hijackXiaomiOsShortcuts)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        stylusKeys.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onResume() {
        super.onResume()
        resumed = true
        stylusKeys.setActive(hasWindowFocus())
    }

    override fun onPause() {
        resumed = false
        stylusKeys.setActive(false)
        // A foreground Dialog may own keys while the Activity window is already unfocused.
        stylusKeys.reset()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        stylusKeys.setActive(resumed && hasFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsStore(this)
        stylusKeys.hijackXiaomiOsShortcuts = settings.penButtonsEnabled && settings.penLegacyKeys
        XiaomiStylusWindow.applyBeforeAdd(window, stylusKeys.hijackXiaomiOsShortcuts)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = LibraryStore(this)
        setContent { NotiApp(library, settings) }
    }
}
