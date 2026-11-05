# Xiaomi system pen shortcuts: investigation

Target reported by the user on 2026-11-04: **Xiaomi Pad 7, original three-button
Focus Pen, HyperOS 3.0.303.0**. Region suffix and actual device fingerprint have
not been captured. Requirement: prevent system screenshot/quick-note actions
while using held pen buttons inside Noti, without root or ADB.

## What Noti already does

Noti is native Android: Compose UI with a C++ ink engine and dedicated input
window. `MainActivity.dispatchKeyEvent` routes through `StylusKeyDispatcher`.
`StylusDialogKeys` handles focused settings dialogs. `InkLayer` consumes recognized
pen keys, then passes event fields to `StylusButtons` on the ink thread. Both
key holds and stylus motion-button flags already contribute temporary tool flags.

This routes events delivered to Noti. It does not cancel a system policy timer
started before delivery. Another `return true` in these handlers is not evidence
that HyperOS's shortcut was suppressed. The current device report establishes
that system actions still occur with the existing app implementation.

## Source evidence and its limits

The inspected [decompiled MiuiStylusShortcutManager snapshot][shortcuts] schedules
380 ms handlers for the screenshot and quick-note keys (Page Up 92 and Page Down
93). Its quick-note handler skips packages in a fixed set including
`com.miui.notes`, `com.miui.pen.demo`, and `com.miui.creation`, checking the focused
window's `getOwningPackage()`. Its screenshot handler in this snapshot has no
equivalent package exemption. Both paths are bypassed when `gb_boosting` is 1.

An [older decompiled implementation][older] skips the screenshot timer when the
focused window has `FLAG_SECURE`. The newer inspected snapshot does not contain
that early check. Blocking screenshot contents is different from suppressing its
UI, timer, or consumption of the pen event.

**Neither snapshot is established as the framework binary from this user's
3.0.303.0 build.** They provide concrete hypotheses, not a verified explanation of
every behavior on that build. No device-verified complete workaround was found.

The official [Xiaomi SDK guide][sdk] assigns touch-film/button integration to
Focus Pen Pro. It does not establish a supported opt-out for the original Focus
Pen's physical-button hold shortcuts.

## App-window experiment

One possible nonprivileged route needs device verification:

- In [Android 16 WindowState][windowstate], `getOwningPackage()` returns
  `mAttrs.packageName`.
- `WindowManager.LayoutParams.packageName` is a public SDK field. Its
  [copyFrom implementation][params] copies it only when the destination field is
  null. Any identity experiment must set the attribute **before the window is
  first attached**; updating an existing window does not establish this test.
- If HyperOS accepts that initial attribute and its exemption still reads it,
  setting a test window's package attribute to `com.miui.creation` could reach
  the quick-note exception without changing the installed package ID.

This is an inference from code, not a confirmed API or a verified fix. HyperOS
may validate, normalize, or otherwise ignore the attribute, and window attribution
may influence unrelated system behavior. The inspected screenshot path does not
show a package exemption, so this alone cannot be claimed to suppress both keys.

The isolated [pen shortcut probe](../tools/pen-shortcut-probe/README.md) tests normal,
secure, note-window identity, and combined modes without modifying Noti. It also
collects the actual firmware fingerprint and reports failures without requiring
logcat or ADB. Any system warning, shortcut UI, lost pen input, or missing usable
held-button events prevents treating a mode as a complete solution.

## Excluded or incomplete approaches

- Writing `Settings.Secure.gb_boosting` directly requires privileges that an
  ordinarily installed app cannot request through a runtime permission dialog.
  ADB/Shizuku or root-based grants do not meet this task's constraints.
- Manually placing Noti in Game Turbo is a conditional user-settings experiment,
  not a verified app implementation. Xiaomi [documents][faq] that Game Turbo
  disables the stylus by default; disabling the pen would not satisfy the goal.
- `FLAG_SECURE` can restrict capture of the app and may still permit system
  warnings or input interruption. It does not establish quick-note suppression.
- Rooted modules hooking system-server interception are outside scope.

Production Noti is unchanged by this investigation. Integrate a window workaround
only if the target-device experiment establishes the desired behavior and its
capture/attribution tradeoffs are acceptable.

[shortcuts]: https://github.com/raghavt20/miui-services/blob/d9e4df4bed682f316f555858588e2130611ad768/sources/com/miui/server/input/stylus/MiuiStylusShortcutManager.java
[older]: https://github.com/chrmhoffmann/miui-services/blob/fba6b27f43ee39c1a5d5a62d1dcfb45246eddb27/sources/com/miui/server/input/stylus/MiuiStylusPageKeyListener.java
[sdk]: https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1884
[windowstate]: https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/wm/WindowState.java
[params]: https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/view/WindowManager.java
[faq]: https://www.mi.com/global/support/faq/details/KA-630877/
