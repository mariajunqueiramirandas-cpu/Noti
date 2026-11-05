# Focus Pen buttons

Noti supports the original three-button Xiaomi Focus Pen through Android input events. No root,
accessibility service, privileged permission or Xiaomi SDK is required. On Xiaomi/Redmi/POCO devices
with **Pen shortcuts** and **Xiaomi button compatibility** enabled, Noti sets its attached
window's `WindowManager.LayoutParams.packageName` to `com.miui.notes` when windows are added so
HyperOS's `MiuiStylusShortcutManager` (`NOTE_PACKAGE_NAME.contains(mFocusedWindow.getOwningPackage())`)
disables the 380 ms OS Quick Note / Mi Canvas hold overlay and 400 ms dispatch delay on the writing
button (`KEYCODE_PAGE_DOWN`).

## Using the shortcuts

In an editor, a short **writing button** click toggles the stroke eraser; a short **screenshot button**
click toggles the lasso. Clicking the same tool again returns to the last pen or highlighter, including
after reopening the app. The floating selector and pen shortcuts use the same persistent tool state.

**Settings → Pen buttons** assigns each key-based click to no action, eraser, lasso, pen/highlighter,
next quick color, undo or redo. Focus defaults to no action and is passed to the system.
Assigning Focus only captures Android's tertiary stylus code if the OS actually delivers it.
Unknown vendor key codes are not guessed or captured.

With **Hold to erase or select** enabled, a real held writing button temporarily erases; a real held
screenshot button temporarily selects. A held key or stylus motion-button flag applies to strokes
started while pressed. The native engine latches the tool at nib-down, so release never turns half
an eraser gesture into ink: the selected tool returns on the next stroke. Both held together give
eraser priority. Using a held button for ink suppresses its click, even if released quickly.
Long presses without ink also suppress clicks, using Android's configured long-press timeout.

Turn holds off to keep only key click shortcuts. Turn **Pen shortcuts** off to pass keys through
and disable button-based tool overrides; the physical eraser tip remains available.

## What a tablet exposes

The OS can distinguish its own click/hold actions without exposing that information to applications.
Some Xiaomi implementations deliver an immediate key-down/key-up pair even when a button remains
physically pressed. Noti treats that pair as a click and clears its held state immediately. It does
not use a timer to invent a hold or promise hold-to-erase on that firmware.

Supported inputs:

| Input | Interpretation |
| --- | --- |
| Android key codes 308 / 309 | Primary (writing) / secondary (screenshot) |
| Android key code 310 | Focus, only when assigned |
| Page Down / Page Up (93 / 92) from a stylus source or a pen-named device | Xiaomi writing / screenshot compatibility mapping |
| `BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY` in pen motion events | Temporary erase / lasso; no additional click |

The Xiaomi mapping is a compatibility default, not a guarantee for every firmware. **Swap Xiaomi
button order** reverses it; **Xiaomi button compatibility** disables that path. Page keys from
ordinary keyboards and remotes remain untouched. Tail, camera, volume and arbitrary gamepad codes
are not intercepted. Standard stylus codes are recognized even when their input source is keyboard-like.

Key states are tracked per input device. A single pressed pen key device may control a separate
digitizer device (Bluetooth and tip events can come from different nodes). With multiple pressed
key devices, Noti requires a matching device rather than guessing. Hover exit or motion events with
zero button flags do not erase a still-held key state. Physical release, canceled input, focus loss,
pause, canvas disposal, device removal/change and configuration changes clear the applicable states.
Repeats do not produce extra clicks. Motion events stay on the dedicated ink thread; diagnostic
messages are enabled only in the tester, whose history is bounded to 16 entries and never saved.

Only the active canvas receives key shortcuts. Settings scratch canvases supersede the underlying
editor, including settings opened in a dialog; dialogs and background windows cannot edit a hidden
notebook through its shortcuts. The tester never invokes notebook actions or changes the selected tool.

## Verify on the Xiaomi Pad 7

Automated tests validate input interpretation and native tool behavior, not the tablet's firmware.
There was no attached Android device during implementation. On the target tablet:

1. Open **Settings → Pen buttons**. Click writing and screenshot once each, near the screen and
   outside hover range. Confirm the names; swap the compatibility order if reversed.
2. Hold each button for two seconds. A down report that remains until physical release followed
   by a long-press report demonstrates key hold timing. An immediate click while still holding
   means that key stream exposes clicks only. Motion state reports may independently expose holds.
3. Draw a line in the scratch area. Hold writing before touching it to erase; hold screenshot
   before tracing a lasso. Check away/near-screen transitions while keeping the button pressed.
4. In a notebook, verify click toggles, persistent return to pen/highlighter, undo/redo assignments,
   and that a real hold used for ink does not also toggle on release. Check both drawing tools.
5. While holding a button, open settings, leave/reopen the editor, switch apps, rotate, disconnect
   the pen, and turn shortcuts off/on. No tool should remain temporarily held after canceled input.
6. Verify ordinary Bluetooth keyboard Page Up/Down, and Xiaomi's unassigned Focus button. OS-reserved
   functions may still take precedence; absence of an event is not evidence of a physical pen failure.

For lower-level investigation, Android's `getevent` utility can show kernel input events when device
permissions allow it. An app-level event trace is still needed to establish what Noti can receive.

## Sources

- [Xiaomi Focus Pen button functions](https://www.mi.com/global/product/xiaomi-focus-pen/)
- [Android KeyEvent press, release, repeat and cancellation](https://developer.android.com/reference/android/view/KeyEvent)
- [Android MotionEvent button state](https://developer.android.com/reference/android/view/MotionEvent#getButtonState())
- [Drawpile/Krita developer discussion of Xiaomi instant-release key behavior](https://krita-artists.org/t/my-styluss-side-button-is-not-recognised-by-krita/176767/7)
- [Android getevent](https://source.android.com/docs/core/interaction/input/getevent)

Xiaomi's [touch-button SDK documentation](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1882)
assigns its newer gesture integration to **Focus Pen Pro**. It does not establish raw held-button
access for the original Focus Pen; Noti does not depend on it.
