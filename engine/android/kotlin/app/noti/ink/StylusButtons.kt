package app.noti.ink

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.util.SparseArray
import android.util.SparseIntArray
import kotlin.math.abs
import java.io.Closeable

/**
 * The pen's buttons. [label] and [title] follow the tablet: on Xiaomi (and by default) they
 * are the Focus Pen's writing / screenshot / Focus buttons; on Samsung, the S Pen's side button.
 */
enum class PenButton(private val xiaomi: String, private val samsung: String, val flag: Int) {
    Primary("Writing", "S Pen", NotiInk.FLAG_BUTTON_PRIMARY),
    // Screenshot supports delivered clicks only; its hold stays with HyperOS.
    Secondary("Screenshot", "Second", 0),
    Focus("Focus", "Focus", 0);

    /** Short name, for diagnostics: "Writing", or "S Pen" on Samsung. */
    val label: String get() = if (PenVendor.isSamsung()) samsung else xiaomi
    /** "Writing button", or "S Pen button" on Samsung. */
    val title: String get() = "$label button"
    /** [title] inside a sentence: "writing button", but still "S Pen button". */
    val phrase: String get() = if (PenVendor.isSamsung()) title else title.lowercase()
}

/** Which pen family the tablet ships with, for labels and vendor-specific input quirks. */
object PenVendor {
    fun isSamsung(manufacturer: String? = Build.MANUFACTURER, brand: String? = Build.BRAND): Boolean =
        manufacturer.orEmpty().equals("samsung", ignoreCase = true) || brand.orEmpty().equals("samsung", ignoreCase = true)
}

/**
 * Samsung S Pen quirk: some One UI digitizers report a stroke drawn with the side button held
 * as private actions 211–214 (Samsung's pen down / up / move / cancel) instead of
 * ACTION_DOWN / UP / MOVE / CANCEL, sometimes without the button in getButtonState(). Mapping
 * them back lets such a stroke draw, and lets the held button open the dial or erase.
 */
object SamsungPenActions {
    const val DOWN = 211
    const val UP = 212
    const val MOVE = 213
    const val CANCEL = 214

    /** The standard action for [actionMasked], or [actionMasked] unchanged. */
    fun action(actionMasked: Int): Int = when (actionMasked) {
        DOWN -> MotionEvent.ACTION_DOWN
        UP -> MotionEvent.ACTION_UP
        MOVE -> MotionEvent.ACTION_MOVE
        CANCEL -> MotionEvent.ACTION_CANCEL
        else -> actionMasked
    }

    /** [buttonState] with the side button held when [actionMasked] is one of Samsung's button actions. */
    fun buttons(actionMasked: Int, buttonState: Int): Int =
        if (actionMasked in DOWN..CANCEL) buttonState or MotionEvent.BUTTON_STYLUS_PRIMARY else buttonState
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
    /**
     * Clicks from the pen's motion-event button flags, for pens that never send key events:
     * the Samsung S Pen and most Wacom-style digitizer pens. Keys win wherever both exist.
     */
    val motionClicks: Boolean = true,
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
 * state after release. Motion flags and keys are merged per input device.
 *
 * Keys fire click actions. Pens that report their buttons only in motion events (the
 * Samsung S Pen) fire clicks from those flags when [StylusButtonOptions.motionClicks] is
 * on. A device reporting both paths cannot double-fire: once a button has arrived as a
 * key, its motion clicks stop, and a key click that repeats a motion click just made is
 * dropped.
 */
internal class StylusButtons(
    private val longPressMs: Long,
    private val onClick: (PenButton) -> Unit,
    private val onDiagnostic: ((String) -> Unit)? = null,
) {
    private class Press(val downAt: Long) { var usedForInk = false }
    private val presses = SparseArray<Array<Press?>>()
    // Pen motion button bits per device; a device is absent until its state is known again.
    private val motionStates = SparseIntArray()
    private val motionPresses = SparseArray<Array<Press?>>()
    // Buttons this tablet has delivered as keys: the key path is authoritative for them.
    private val keyed = BooleanArray(PenButton.entries.size)
    // Press time of each button's latest motion click, so a late key for it cannot click again.
    private val motionClickAt = LongArray(PenButton.entries.size) { NEVER }
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
                keyed[index] = true
                // A new sequence recovers from a missing UP without firing the lost click.
                states[index] = Press(downTime)
                onDiagnostic?.invoke("${button.label}: down (device $device)")
            }
            KeyEvent.ACTION_UP -> {
                val press = states[index] ?: return
                states[index] = null
                if (states.all { it == null }) presses.remove(device)
                val duration = (time - press.downAt).coerceAtLeast(0)
                val pen = motionClickAt[index]
                val alreadyClicked = pen != NEVER && abs(press.downAt - pen) < longPressMs
                val click = !canceled && !press.usedForInk && !alreadyClicked && duration < longPressMs
                onDiagnostic?.invoke("${button.label}: ${when {
                    canceled -> "canceled"
                    press.usedForInk -> "used while writing"
                    alreadyClicked -> "already clicked by the pen"
                    click -> "click"
                    else -> "long press"
                }} · $duration ms")
                if (click) onClick(button)
            }
        }
    }

    /**
     * Called for pen events only, including hover and button press/release. [time] is the
     * event's uptime in ms. [contact]: the nib is on the page, so a held button is used for
     * ink and will not click. [leaving]: the pen leaves hover range (or is about to touch);
     * its held buttons are forgotten without clicking, and a button already held when the pen
     * comes back is not a new press.
     */
    fun motion(device: Int, buttons: Int, time: Long = 0, contact: Boolean = false, leaving: Boolean = false) {
        if (!options.enabled) return
        val bits = buttons and MOTION_BUTTONS
        val known = motionStates.indexOfKey(device) >= 0
        val old = motionStates.get(device)
        if (bits != old) {
            onDiagnostic?.invoke("Pen state: ${PenButton.Primary.label} ${if (bits and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) "down" else "up"}, " +
                "${PenButton.Secondary.label} ${if (bits and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) "down" else "up"}")
        }
        if (known && bits != old) {
            motionEdge(device, PenButton.Primary, MotionEvent.BUTTON_STYLUS_PRIMARY, old, bits, time)
            motionEdge(device, PenButton.Secondary, MotionEvent.BUTTON_STYLUS_SECONDARY, old, bits, time)
        }
        if (contact) motionPresses[device]?.forEach { it?.usedForInk = true }
        if (leaving) {
            motionStates.delete(device)
            motionPresses.remove(device)
        } else {
            motionStates.put(device, bits)
        }
    }

    private fun motionEdge(device: Int, button: PenButton, flag: Int, old: Int, bits: Int, time: Long) {
        val was = old and flag != 0
        val now = bits and flag != 0
        val index = button.ordinal
        if (!was && now) {
            val states = motionPresses[device] ?: arrayOfNulls<Press>(PenButton.entries.size).also { motionPresses.put(device, it) }
            states[index] = Press(time)
        } else if (was && !now) {
            val states = motionPresses[device] ?: return
            val press = states[index] ?: return
            states[index] = null
            if (states.all { it == null }) motionPresses.remove(device)
            // Keys are authoritative wherever the tablet sends them.
            if (!options.motionClicks || keyed[index] || keyHeld(index)) return
            val duration = (time - press.downAt).coerceAtLeast(0)
            val click = !press.usedForInk && duration < longPressMs
            onDiagnostic?.invoke("${button.label} (pen): ${when {
                press.usedForInk -> "used while writing"
                click -> "click"
                else -> "long press"
            }} · $duration ms")
            if (click) {
                motionClickAt[index] = press.downAt
                onClick(button)
            }
        }
    }

    private fun keyHeld(index: Int): Boolean {
        for (i in 0 until presses.size()) if (presses.valueAt(i)[index] != null) return true
        return false
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
        motionPresses.clear()
        onDiagnostic?.invoke("Buttons released; waiting for new input")
    }

    fun removeDevice(device: Int) {
        presses.remove(device)
        motionStates.delete(device)
        motionPresses.remove(device)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val MOTION_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
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
