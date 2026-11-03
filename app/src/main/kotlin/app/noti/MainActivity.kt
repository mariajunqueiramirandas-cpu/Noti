package app.noti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.noti.data.LibraryStore
import app.noti.data.SettingsStore
import app.noti.ui.NotiApp

/**
 * The whole app is Compose. The ink itself never goes through Compose: each writing area
 * hosts the native engine's own input window and layers (app.noti.ink.InkLayer), so
 * recomposition, layout and GC on this thread can't delay the pen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = LibraryStore(this)
        val settings = SettingsStore(this)
        setContent { NotiApp(library, settings) }
    }
}
