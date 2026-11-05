# Focus Pen buttons

Noti supports the original three-button Xiaomi Focus Pen through Android input events. No root,
accessibility service, privileged permission or Xiaomi SDK is required. This does not override events
that HyperOS reserves for its own screenshot, Mi Canvas, pointer or camera features.

## Using the shortcuts

In an editor, a short **writing button** click toggles the stroke eraser; a short **screenshot button**
click toggles the lasso. Clicking the same tool again returns to the last pen or highlighter, including
after reopening the app. The floating selector and pen shortcuts use the same persistent tool state.

**Settings → Pen buttons** assigns each key-based click to no action, eraser, lasso, pen/highlighter,
next quick color, undo or redo. Focus defaults to no action and is passed to the system.
Assigning Focus only captures Android's tertiary stylus code if the OS actually delivers it.
Unknown vendor key codes are not guessed or captured.

With **Hold to erase** enabled, a real held writing button temporarily erases. A held key or stylus
motion flag applies to strokes started while pressed. The native engine latches the tool at
nib-down, so release never turns half an eraser gesture into ink: the selected tool returns on the
next stroke. Using a held button for ink suppresses its click, even if released quickly.
Long presses without ink also suppress clicks, using Android's configured long-press timeout.

Holds are supported **only on the writing button**. On HyperOS 3.0.303.0 (Xiaomi Pad 7) the OS
intercepts held pen keys for every package outside its hardcoded allowlist (see below), so on
that firmware neither button's held *key* reaches Noti and both stay click-only; the
writing-button hold path exists for platforms and firmwares that deliver real down/up timing.
Held stylus motion flags may still reach the app independently of key interception, but
HyperOS's own hold action still fires, so the stock-firmware experience stays click-only.
The screenshot button is click-only everywhere: HyperOS reserves its hold unconditionally,
with no package gate.

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
| `BUTTON_STYLUS_PRIMARY` in pen motion events | Temporary erase; no additional click |

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

## Why HyperOS's reserved hold actions cannot be suppressed from an app

HyperOS 3.0.303.0 on the Xiaomi Pad 7 intercepts Focus Pen keys in
`MiuiStylusShortcutManager`, hooked from `PhoneWindowManager.interceptKeyBeforeQueueing` —
the first policy gate in Android's `InputDispatcher::notifyKey`. On a stylus key down it
posts a hardcoded 380 ms timer and holds the key back from the focused app for up to 400 ms;
if the key is still down at 380 ms it consumes the event and fires the system action (quick
note mask for Page Down, partial screenshot for Page Up). Two consequences follow:

- **An accessibility key filter cannot stop this.** Accessibility key filtering
  (`FLAG_REQUEST_FILTER_KEY_EVENTS`) runs in the InputFilter, which the dispatcher consults
  *after* `interceptKeyBeforeQueueing`. The timer is already armed by the time the filter
  sees the event, and nothing an app can reach cancels it. Remapper-style accessibility
  services only ever receive the short-press key that HyperOS dispatches on an early release
  — which Noti already handles as a click.
- **The only app-reachable bypass is a hardcoded package allowlist.** The quick-note path
  skips interception entirely when the focused window's owning package is one of
  `com.miui.notes`, `com.miui.pen.demo` or `com.miui.creation` — a plain string comparison
  with no signature check. A build under such an identity would receive every pen key
  immediately with real down/up timing.

Noti ships as `app.noti` and does not adopt an allowlist identity. Renaming the application
id is a new Android identity that orphans existing installs, can never ship on Google Play
under `com.miui.pen.demo`, and risks an update conflict if a HyperOS update or GetApps ever
installs the real pen demo app. It would also buy only half a fix: the screenshot button's
hold action has no package gate in 3.0.303.0, so the partial-screenshot editor still opens
inside allowlisted packages. Suppressing these actions without root or adb requires patching
`miui-services.jar` (see the XDA guide in Sources), which Noti will not do.

The definitive in-app behavior on stock HyperOS is therefore **click-only for both buttons**.
The writing-button hold path stays implemented for platforms and firmwares that deliver held
keys with real timing. One package-preserving experiment remains unverified: Game Turbo's
per-app boost (`gb_boosting`) is checked before the stylus manager in
`interceptKeyBeforeQueueing`, so an active game session may pass stylus keys through to the
focused app with no package change required.

## Verify on the Xiaomi Pad 7

Automated tests validate input interpretation and native tool behavior, not the tablet's firmware.
There was no attached Android device during implementation. On the target tablet:

1. Open **Settings → Pen buttons**. Click writing and screenshot once each, near the screen and
   outside hover range. Confirm the names; swap the compatibility order if reversed.
2. Hold the writing button for two seconds. A down report that remains until physical release
   followed by a long-press report demonstrates key hold timing. An immediate click while still
   holding means that key stream exposes clicks only. Motion state reports may independently
   expose holds. Holding the screenshot button is expected to open HyperOS's partial-screenshot
   editor instead (its hold is OS-reserved; Noti supports writing-button holds only).
3. Draw a line in the scratch area. Hold writing before touching it to erase. Check
   away/near-screen transitions while keeping the button pressed.
4. In a notebook, verify click toggles, persistent return to pen/highlighter, undo/redo assignments,
   and that a real hold used for ink does not also toggle on release. Check both drawing tools.
5. While holding a button, open settings, leave/reopen the editor, switch apps, rotate, disconnect
   the pen, and turn shortcuts off/on. No tool should remain temporarily held after canceled input.
6. Verify ordinary Bluetooth keyboard Page Up/Down, and Xiaomi's unassigned Focus button. OS-reserved
   functions may still take precedence; absence of an event is not evidence of a physical pen failure.
7. Optional experiment, no package change needed: add Noti to Game Turbo's boost list, start a
   boosted session, and repeat steps 2–4. If held keys now arrive with real down/up timing,
   hold-to-erase works on stock HyperOS through `gb_boosting`; if the quick-note mask still
   appears, game mode does not pass stylus keys through on 3.0.303.0.

For lower-level investigation, Android's `getevent` utility can show kernel input events when device
permissions allow it. An app-level event trace is still needed to establish what Noti can receive.

## Sources

- [Xiaomi Focus Pen button functions](https://www.mi.com/global/product/xiaomi-focus-pen/)
- [Android KeyEvent press, release, repeat and cancellation](https://developer.android.com/reference/android/view/KeyEvent)
- [Android MotionEvent button state](https://developer.android.com/reference/android/view/MotionEvent#getButtonState())
- [Drawpile/Krita developer discussion of Xiaomi instant-release key behavior](https://krita-artists.org/t/my-styluss-side-button-is-not-recognised-by-krita/176767/7)
- [Android getevent](https://source.android.com/docs/core/interaction/input/getevent)
- [XDA: patching miui-services.jar to disable native stylus button overlays on HyperOS](https://xdaforums.com/t/guide-disable-native-stylus-button-overlays-on-hyperos-2-quick-note-screenshot.4795437/)
- [mi_penbridge: notes on MiuiStylusShortcutManager's hardcoded 380 ms timers and isXiaomiStylus gates](https://github.com/kquieter-debug/mi_penbridge)

Xiaomi's [touch-button SDK documentation](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1882)
assigns its newer gesture integration to **Focus Pen Pro**. It does not establish raw held-button
access for the original Focus Pen; Noti does not depend on it.
