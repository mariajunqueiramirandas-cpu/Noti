package app.noti.ink

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.util.SparseArray
import android.util.SparseIntArray
import java.io.Closeable

enum class PenButton(val label: String, val flag: Int) {
    Primary("Writing", NotiInk.FLAG_BUTTON_PRIMARY),
    // Screenshot supports delivered clicks only; its hold stays with HyperOS.
    Secondary("Screenshot", 0),
    Focus("Focus", 0),
}

/** What holding the writing button does when the nib touches the page. */
enum class PenHold(val label: String) {
    /** The stroke opens the tool dial under the nib instead of drawing. */
    Dial("Open the tool dial"),
    /** The stroke erases; the selected tool returns on the next stroke. */
    Erase("Erase temporarily"),
    Off("Nothing"),
}

data class StylusButtonOptions(
    val enabled: Boolean = true,
    val hold: PenHold = PenHold.Dial,
    val legacyKeys: Boolean = true,
    val swapLegacyKeys: Boolean = false,
    // The spotlight button belongs to HyperOS unless explicitly assigned in the app.
    val captureFocus: Boolean = false,
)

/** Map only documented Android codes and the Xiaomi Page Up/Down compatibility path. */
object StylusButtonKeys {
    private val penName = Regex("(?:^|[^a-z])(pen|stylus)(?:$|[^a-z])")
    fun button(keyCode: Int, source: Int, deviceName: String?, options: StylusButtonOptions): PenButton? {
        if (!options.enabled) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY -> PenButton.Primary
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY -> PenButton.Secondary
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY -> PenButton.Focus.takeIf { options.captureFocus }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!options.legacyKeys) return null
                // Never steal page navigation from an ordinary keyboard or remote.
                val name = deviceName.orEmpty().lowercase(java.util.Locale.ROOT)
                val stylus = (source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS ||
                    (penName.containsMatchIn(name) && "keyboard" !in name)
                if (!stylus) return null
                val primary = (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) xor options.swapLegacyKeys
                if (primary) PenButton.Primary else PenButton.Secondary
            }
            else -> null
        }
    }
}

/**
 * Ink-thread state. A synthetic down/up remains a click; we never invent a held
 * state after release. Motion flags and keys are merged per input device. Only
 * keys fire click actions, so a device reporting both paths cannot double-fire.
 */
internal class StylusButtons(
    private val longPressMs: Long,
    private val onClick: (PenButton) -> Unit,
    private val onDiagnostic: ((String) -> Unit)? = null,
) {
    private class Press(val downAt: Long) { var usedForInk = false }
    private val presses = SparseArray<Array<Press?>>()
    private val motionStates = SparseIntArray()
    var options = StylusButtonOptions()
        set(value) {
            if (field != value) reset()
            field = value
        }

    fun key(button: PenButton, device: Int, action: Int, time: Long, repeat: Int = 0, canceled: Boolean = false, downTime: Long = time) {
        if (!options.enabled) return
        val states = presses[device] ?: if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
            arrayOfNulls<Press>(PenButton.entries.size).also { presses.put(device, it) }
        } else return
        val index = button.ordinal
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeat != 0 || states[index]?.downAt == downTime) return
                // A new sequence recovers from a missing UP without firing the lost click.
                states[index] = Press(downTime)
                onDiagnostic?.invoke("${button.label}: down (device $device)")
            }
            KeyEvent.ACTION_UP -> {
                val press = states[index] ?: return
                states[index] = null
                if (states.all { it == null }) presses.remove(device)
                val duration = (time - press.downAt).coerceAtLeast(0)
                val click = !canceled && !press.usedForInk && duration < longPressMs
                onDiagnostic?.invoke("${button.label}: ${when {
                    canceled -> "canceled"
                    press.usedForInk -> "used while writing"
                    click -> "click"
                    else -> "long press"
                }} · $duration ms")
                if (click) onClick(button)
            }
        }
    }

    /** Called for pen events only, including hover and button press/release. */
    fun motion(device: Int, buttons: Int) {
        if (!options.enabled) return
        val bits = buttons and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)
        val old = motionStates.get(device)
        if (bits == old) return
        if (bits == 0) motionStates.delete(device) else motionStates.put(device, bits)
        onDiagnostic?.invoke("Pen state: writing ${if (bits and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) "down" else "up"}, " +
            "screenshot ${if (bits and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) "down" else "up"}")
    }

    /** Only writing can override ink while held. The native engine latches it at nib-down. */
    fun flags(device: Int, buttons: Int, contact: Boolean): Int =
        if (options.enabled && options.hold == PenHold.Erase && writingHeld(device, buttons, contact)) NotiInk.FLAG_BUTTON_PRIMARY else 0

    /**
     * Whether a nib touching down now opens the tool dial. Like a hold used for ink,
     * a hold used for the dial suppresses the button's click.
     */
    fun summonsDial(device: Int, buttons: Int): Boolean =
        options.enabled && options.hold == PenHold.Dial && writingHeld(device, buttons, contact = true)

    private fun writingHeld(device: Int, buttons: Int, contact: Boolean): Boolean {
        val motion = buttons and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        // Xiaomi can expose the Bluetooth keys and digitizer as separate devices.
        // Only pressed writing keys count toward association: a click-only
        // screenshot or Focus key on another device cannot block a writing hold.
        val press = presses[device]?.get(PenButton.Primary.ordinal) ?: singleWritingPress() ?: return motion
        if (contact) press.usedForInk = true
        return true
    }

    private fun singleWritingPress(): Press? {
        var result: Press? = null
        for (index in 0 until presses.size()) {
            val candidate = presses.valueAt(index)[PenButton.Primary.ordinal] ?: continue
            if (result != null) return null // Two pressed pens: do not guess their digitizer.
            result = candidate
        }
        return result
    }

    fun reset() {
        presses.clear()
        motionStates.clear()
        onDiagnostic?.invoke("Buttons released; waiting for new input")
    }

    fun removeDevice(device: Int) {
        presses.remove(device)
        motionStates.delete(device)
    }
}

/** The non-focusable ink panel receives motion; its activity receives keys. */
interface StylusKeyHost { val stylusKeys: StylusKeyDispatcher }

/** Main-thread registrations form a stack: a settings scratch canvas supersedes its editor. */
class StylusKeyDispatcher {
    private class Client(val key: (KeyEvent) -> Boolean, val reset: () -> Unit)
    private val clients = mutableListOf<Client>()
    private var active = false
    var hijackXiaomiOsShortcuts: Boolean = true

    fun register(key: (KeyEvent) -> Boolean, reset: () -> Unit): Closeable {
        clients.lastOrNull()?.reset?.invoke()
        val client = Client(key, reset)
        clients.add(client)
        return Closeable {
            val wasTop = clients.lastOrNull() === client
            if (clients.remove(client)) client.reset()
            if (wasTop) clients.lastOrNull()?.reset?.invoke()
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        clients.forEach { it.reset() }
    }

    fun dispatch(event: KeyEvent): Boolean = active && clients.lastOrNull()?.key?.invoke(event) == true

    /** A focused app Dialog has its own Window.Callback; Activity.dispatchKeyEvent isn't called. */
    fun dispatchFromFocusedWindow(event: KeyEvent): Boolean = clients.lastOrNull()?.key?.invoke(event) == true

    fun reset() { clients.forEach { it.reset() } }
}

/**
 * HyperOS's MiuiStylusShortcutManager checks NOTE_PACKAGE_NAME.contains(mFocusedWindow.getOwningPackage())
 * before arming the 380 ms Quick Note / Mi Canvas hold action and 400 ms key dispatch delay on
 * KEYCODE_PAGE_DOWN (93). In system_server, WindowState.getOwningPackage() returns mAttrs.packageName.
 *
 * Because WindowManager.LayoutParams.copyFrom only copies packageName when the destination field is null,
 * win.mAttrs.packageName in WindowManagerService is latched when the window is first added (addWindow).
 * Setting window.attributes.packageName to "com.miui.notes" before WindowManagerGlobal.addView runs (and
 * returning "com.miui.notes" when Window.adjustLayoutParamsForSubWindow or WindowManagerGlobal.addView
 * queries Context.getPackageName() for Dialog/sub-windows) causes ViewRootImpl.setView and WindowState.<init>
 * to latch "com.miui.notes" without root or ADB.
 */
object XiaomiStylusWindow {
    const val NOTES_PACKAGE = "com.miui.notes"

    fun isXiaomi(manufacturer: String? = Build.MANUFACTURER, brand: String? = Build.BRAND): Boolean {
        val m = manufacturer.orEmpty().lowercase(java.util.Locale.ROOT)
        val b = brand.orEmpty().lowercase(java.util.Locale.ROOT)
        return m == "xiaomi" || b == "xiaomi" || b == "redmi" || b == "poco"
    }

    fun applyBeforeAdd(
        attrs: WindowManager.LayoutParams,
        hijack: Boolean = true,
        isXiaomi: Boolean = isXiaomi(),
    ): Boolean {
        if (!isXiaomi || !hijack) return false
        attrs.packageName = NOTES_PACKAGE
        return true
    }

    fun applyBeforeAdd(
        window: Window,
        hijack: Boolean = true,
        isXiaomi: Boolean = isXiaomi(),
    ): Boolean {
        if (!isXiaomi || !hijack) return false
        val attrs = window.attributes
        if (!applyBeforeAdd(attrs, hijack = true, isXiaomi = true)) return false
        window.attributes = attrs
        return true
    }

    fun isWindowAddCaller(stack: Array<StackTraceElement> = Throwable().stackTrace): Boolean =
        stack.any { element ->
            (element.className == "android.view.Window" && element.methodName == "adjustLayoutParamsForSubWindow") ||
                (element.className == "android.view.WindowManagerGlobal" && element.methodName == "addView")
        }

    fun resolvePackageName(
        appPackage: String,
        hijack: Boolean = true,
        isXiaomi: Boolean = isXiaomi(),
        stack: Array<StackTraceElement>? = null,
    ): String {
        if (!isXiaomi || !hijack) return appPackage
        val trace = stack ?: Throwable().stackTrace
        return if (isWindowAddCaller(trace)) NOTES_PACKAGE else appPackage
    }
}
