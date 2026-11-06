package app.noti

import android.content.Context
import android.os.Build
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

    /** Whether this device may rewrite its windows' packageName for HyperOS (see XiaomiStylusWindow). */
    private var xiaomiWindowHijack = false

    override fun getPackageName(): String =
        XiaomiStylusWindow.resolvePackageName(super.getPackageName(), xiaomiWindowHijack && stylusKeys.hijackXiaomiOsShortcuts)

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The system accepted the window: the packageName rewrite is safe on this device and build.
        if (xiaomiWindowHijack) hijackGuard().edit().remove(PENDING).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsStore(this)
        stylusKeys.hijackXiaomiOsShortcuts = settings.penButtonsEnabled && settings.penLegacyKeys
        xiaomiWindowHijack = hijackAllowed(applying = stylusKeys.hijackXiaomiOsShortcuts)
        XiaomiStylusWindow.applyBeforeAdd(window, xiaomiWindowHijack && stylusKeys.hijackXiaomiOsShortcuts)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = LibraryStore(this)
        setContent { NotiApp(library, settings) }
    }

    /**
     * The rewrite runs only on Xiaomi tablets, and stops for good on this device and build if a
     * launch that used it died before its window was attached, so an unexpected HyperOS build
     * can't leave the app crashing on every start. [applying]: this launch rewrites its own window.
     */
    private fun hijackAllowed(applying: Boolean): Boolean {
        if (!XiaomiStylusWindow.deviceSupportsHijack(this)) return false
        val guard = hijackGuard()
        val build = "${Build.FINGERPRINT}/${appVersion()}"
        if (guard.getString(BROKEN, null) == build) return false
        if (guard.getString(PENDING, null) == build) {
            guard.edit().putString(BROKEN, build).remove(PENDING).commit()
            return false
        }
        // Written synchronously: the window is added right after onCreate.
        if (applying) guard.edit().putString(PENDING, build).commit()
        return true
    }

    private fun hijackGuard() = getSharedPreferences("xiaomi_window", Context.MODE_PRIVATE)

    private fun appVersion(): Long = runCatching {
        packageManager.getPackageInfo(super.getPackageName(), 0).longVersionCode
    }.getOrDefault(0L)

    private companion object {
        const val PENDING = "pending"
        const val BROKEN = "broken"
    }
}
