# Focus Pen buttons

Noti supports the original three-button Xiaomi Focus Pen through Android input events. No root,
accessibility service, privileged permission or Xiaomi SDK is required. On Xiaomi/Redmi/POCO devices
with **Pen shortcuts** and **Xiaomi button compatibility** enabled, Noti sets its attached
window's `WindowManager.LayoutParams.packageName` to `com.miui.notes` when windows are added so
HyperOS's `MiuiStylusShortcutManager` (`NOTE_PACKAGE_NAME.contains(mFocusedWindow.getOwningPackage())`)
disables the 380 ms OS Quick Note / Mi Canvas hold overlay and 400 ms dispatch delay on the writing
button (`KEYCODE_PAGE_DOWN`). Only the writing button supports holds in Noti; screenshot-button
holds remain with HyperOS. Delivered screenshot short clicks still work.

The rewrite only runs on Xiaomi **tablets** (smallest width ≥ 600 dp) that have `com.miui.notes`
installed: the Focus Pen only pairs with Xiaomi Pads, and on a Xiaomi phone (a POCO X8 Pro report) Noti
crashed on launch, with this rewrite the prime suspect. If a launch that used the rewrite
dies before its window is attached, Noti stops using it on that device and build fingerprint, so a
new HyperOS build can't leave the app crashing on every start; an app update tries again.

## Using the shortcuts

In an editor, a short **writing button** click toggles the stroke eraser; a short **screenshot button**
click toggles the lasso. Clicking the same tool again returns to the last pen or highlighter, including
after reopening the app. The floating selector and pen shortcuts use the same persistent tool state.

**Settings → Pen buttons** assigns each key-based click to no action, eraser, lasso, pen/highlighter,
next quick color, undo or redo. Focus defaults to no action and is passed to the system.
Assigning Focus only captures Android's tertiary stylus code if the OS actually delivers it.
Unknown vendor key codes are not guessed or captured.

## Holding the writing button

**Settings → Pen buttons → Hold writing button** chooses what a writing-button hold does when the
nib touches the page: **Open the tool dial** (the default), **Erase temporarily**, or **Nothing**.
Earlier versions had an on/off "Hold writing button to erase" switch; off stays off, on becomes the
tool dial.

### Tool dial

Hold the writing button and touch the page: no ink is drawn and the tool dial opens centred under
the nib. You can let go of the button now; only the nib matters. Drag to a segment (the label above
the dial names it and the centre previews the stroke) and lift to choose it:

| Lift the nib on… | Result |
| --- | --- |
| A tool, color or width segment | Applies it and closes the dial |
| The centre (hub) | Cancels; nothing changes |
| Far outside the ring | Cancels |
| The lock below the dial | Keeps the dial open (locked) |
| The spot it landed on, without dragging | Opens the dial locked |

Selection follows direction, so overshooting the ring still picks the segment you point at. Near an
edge the dial moves inward to stay on screen; lifting without dragging then locks it rather than
picking whatever ended up under the nib. A color or width picked while the eraser or lasso is in hand
returns to the last pen or highlighter, like **Next quick color**.

A locked dial takes taps like the docked one: the centre opens the full brush options. The next pen
touch or deliberate finger touch on the page closes it and never draws (finger drawing pauses while
the dial is locked; palms are ignored as usual). Tapping the lock or pressing Back also closes it.
Holding the button and touching the page elsewhere moves it there.

By default the dial is hidden until the pen summons it; **More → Show tools** opens it locked without
the pen, and the pen summons it in full screen too. **Always show the tool dial** keeps the docked dial
on the page (holding the button still opens a dial under the nib, and the docked one returns
afterwards). If pen shortcuts are off or the hold does something else, the docked dial always shows.

How it works: the ink window diverts a pen stream that starts with the writing button held to the UI
thread instead of the engine. The stream stays with the ink window, so the dial window opening under
the nib cannot take it over. While locked, the ink window swallows the next pen stream and reports it
as a dismissal. Settings scratch canvases have no dial; there a held stroke draws normally.

### Erase temporarily

With **Erase temporarily** selected, a real held writing button temporarily erases.
A held writing key or primary stylus motion-button flag applies to strokes
started while pressed. The native engine latches the tool at nib-down, so release never turns half
an eraser gesture into ink: the selected tool returns on the next stroke. The screenshot button never
sets a temporary tool override, including when both buttons are pressed.

### Both

Using a held writing button for ink or the dial suppresses its click, even if released quickly.
Long presses without ink also suppress clicks, using Android's configured long-press timeout.

Set the hold to **Nothing** to keep only key click shortcuts. Turn **Pen shortcuts** off to pass keys through
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
| `BUTTON_STYLUS_PRIMARY` in pen motion events | Tool dial or temporary erase, per the hold setting; no additional click |
| `BUTTON_STYLUS_SECONDARY` in pen motion events | Diagnostic state only; no temporary tool override |

The Xiaomi mapping is a compatibility default, not a guarantee for every firmware. **Swap Xiaomi
button order** reverses it; **Xiaomi button compatibility** disables that path. Page keys from
ordinary keyboards and remotes remain untouched. Tail, camera, volume and arbitrary gamepad codes
are not intercepted. Standard stylus codes are recognized even when their input source is keyboard-like.

Key states are tracked per input device. A single pressed writing-key device may control a separate
digitizer device (Bluetooth and tip events can come from different nodes). Click-only buttons on other
devices do not block this association. With multiple pressed writing-key devices, Noti requires a
matching device rather than guessing. Hover exit or motion events with
zero button flags do not erase a still-held key state. Physical release, canceled input, focus loss,
pause, canvas disposal, device removal/change and configuration changes clear the applicable states.
Repeats do not produce extra clicks. Motion events stay on the dedicated ink thread; diagnostic
messages are enabled only in the tester, whose history is bounded to 16 entries and never saved.

Only the active canvas receives key shortcuts. Settings scratch canvases supersede the underlying
editor, including settings opened in a dialog; dialogs and background windows cannot edit a hidden
notebook through its shortcuts. The tester never invokes notebook actions or changes the selected tool.

## Verify on the Xiaomi Pad 7

The independent probe on Pad 7 (`uke`), Android 16 / OS3.0.303.0.WOZMIXM, established the
quick-note whitelist bypass using the `com.miui.creation` window alias. The writing key remained
uncanceled for 4.327 seconds while drawing; its key device was separate from the digitizer, whose
motion button flags stayed zero. Noti's existing integration uses `com.miui.notes` from the same
whitelist. The integrated app still needs a physical-device check.

Screenshot protection (`FLAG_SECURE`) produced warnings and did not yield usable screenshot holds
in the probe. Noti does not use it. Screenshot holds can still invoke the OS screenshot action.

Automated tests cover input interpretation, the existing window helper and native tool behavior,
not the tablet's firmware. On the target tablet:

1. Open **Settings → Pen buttons**. Click writing and screenshot once each, near the screen and
   outside hover range. Confirm the names; swap the compatibility order if reversed.
2. Hold only the writing button for two seconds. Mi Canvas should stay closed. A down report that remains until physical release followed
   by a long-press report demonstrates key hold timing. An immediate click while still holding
   means that key stream exposes clicks only. Motion state reports may independently expose holds.
3. With the hold set to **Erase temporarily**, draw a line in the scratch area. Hold writing before
   touching it to erase. Check away/near-screen transitions while keeping the writing button pressed,
   including the editor's settings dialog.
   With the default **Open the tool dial**, in a notebook: hold writing and touch the page. The dial
   should open under the nib with no ink and no Mi Canvas. Release the button mid-drag and confirm the
   pick still completes on lift; lift on the hub (no change), on the lock and with a press-tap (both
   lock), then touch the page outside with the pen and with a finger (closes, draws nothing). Rest a
   palm on the screen while the dial is locked: it should stay open.
4. In a notebook, verify click toggles, persistent return to pen/highlighter, undo/redo assignments,
   and that a real hold used for ink or the dial does not also toggle on release. Check both drawing tools.
5. While holding the writing button, open settings, leave/reopen the editor, switch apps, rotate, disconnect
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
