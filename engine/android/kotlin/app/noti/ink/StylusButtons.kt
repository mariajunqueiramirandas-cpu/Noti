package app.noti.ink

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.SparseArray
import android.util.SparseIntArray
import java.io.Closeable

enum class PenButton(val label: String, val flag: Int) {
    Primary("Writing", NotiInk.FLAG_BUTTON_PRIMARY),
    Secondary("Screenshot", NotiInk.FLAG_BUTTON_SECONDARY),
    Focus("Focus", 0),
}

data class StylusButtonOptions(
    val enabled: Boolean = true,
    val holds: Boolean = true,
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

    /**
     * No allocations on the pen sample path. The native engine latches a tool at nib-down.
     * Holds are writing-button only: HyperOS reserves the screenshot button's hold for its
     * own partial-screenshot editor with no package gate, so it never reaches the app.
     */
    fun flags(device: Int, buttons: Int, contact: Boolean): Int {
        if (!options.enabled || !options.holds) return 0
        var flags = 0
        if (buttons and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) flags = flags or NotiInk.FLAG_BUTTON_PRIMARY
        // Xiaomi can expose the Bluetooth keys and digitizer as separate devices.
        // With one pressed pen the association is unambiguous. Never guess when
        // multiple key devices are pressed and none matches the digitizer.
        val states = presses[device] ?: if (presses.size() == 1) presses.valueAt(0) else return flags
        val press = states[PenButton.Primary.ordinal] ?: return flags
        flags = flags or NotiInk.FLAG_BUTTON_PRIMARY
        if (contact) press.usedForInk = true
        return flags
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
