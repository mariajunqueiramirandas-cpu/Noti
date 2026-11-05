# Xiaomi system pen shortcuts: investigation

Target: **Xiaomi Pad 7, original three-button Focus Pen, Android 16 / API 36,
HyperOS 3.0.303.0.WOZMIXM**. The user's probe reports identify model `2410CRP4CG`
and fingerprint:

```text
Xiaomi/uke_global/uke:16/BP2A.250605.031.A3/OS3.0.303.0.WOZMIXM:user/release-keys
```

Requirement: prevent system screenshot/quick-note actions
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

## Target-device probe results

The user supplied traces for all four v1 probe modes, then confirmed that the
writing-button hold did not open Mi Canvas in modes 3 and 4. The screenshot-button
short sequences were physical hold attempts, and screenshot protection produced
system warnings. Observed input behavior:

| Mode | Writing key (93) | Screenshot key (92) |
| --- | --- | --- |
| Normal | A 469 ms sequence ends with `canceled=true`; focus is subsequently lost. | No key-92 events in the supplied trace. |
| Secure only | One uncanceled 259 ms short press; no delivered long hold demonstrated. | Physical hold attempts delivered only short key sequences of 78–290 ms. |
| Note-window identity only | Repeated uncanceled holds of 1,140–1,761 ms. A stroke occurs between key-down and key-up. | No key-92 events in the supplied trace. |
| Combined | Uncanceled holds of 877–1,969 ms, including strokes while held. No focus-loss event follows initial focus gain in this trace. | Physical hold attempts delivered only short key sequences of 99–141 ms. System warnings appeared. |

The alias modes deliver the initial writing down promptly, with the first repeat
about 400 ms later. In the normal trace the initial down and 411 ms repeat arrive
11 ms apart, followed by a canceled up. This is consistent with policy buffering
in the control and a working writing-key exemption in the alias modes. The user
confirmed that Mi Canvas did not launch. The screenshot warnings demonstrate that
absence of focus loss alone does not establish silent suppression.

The pen's key device is `18`, named `Xiaomi Focus Pen Mouse`, source `0x101`;
its digitizer is device `14`, stylus tool type `2`. Recorded motion button states
remain `0x0`. Thus these successful delivered holds use the separate key stream,
which Noti already supports, rather than motion-button flags.

**The writing-key workaround is device-confirmed; the secure screenshot workaround
failed.** Physical screenshot holds did not yield a usable continuous key stream
in these traces. The user did not perform the short clicks that the delivered
events might suggest. Do not ignore those key-ups or invent holds with a timer.
The warnings and missing continuous key stream rule out treating `FLAG_SECURE` as
a complete workaround.

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
every behavior on that build. The probe results above provide target-device
evidence for writing-key delivery, but not a verified complete workaround.

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

The supplied traces support this hypothesis for writing-key delivery on the
identified build, with the user confirming no Mi Canvas launch. It is not a
supported Xiaomi API, and window attribution may
influence unrelated system behavior. The inspected screenshot path does not show
a package exemption, so this alone cannot be claimed to suppress both keys.

The isolated [pen shortcut probe](../tools/pen-shortcut-probe/README.md) tests normal,
secure, note-window identity, and combined modes without modifying Noti. It also
collects the actual firmware fingerprint and reports failures without requiring
logcat or ADB. Any system warning, shortcut UI, lost pen input, or missing usable
held-button events prevents treating a mode as a complete solution.

## Earlier keyboard-policy bypass

The user supplied another agent's finding that an early return from
`MiuiKeyInterceptExtend.getKeyInterceptTypeBeforeQueueing` can bypass scheduling
the stylus timers. This is a useful additional path to investigate. In the
[inspected implementation][keyextend], however, `getKeyInterceptType` first
returns 0 unless the event has a real device, that device is a full keyboard, and
the system is a tablet. Android's [full-keyboard test][inputdevice] requires both
keyboard source support and `KEYBOARD_TYPE_ALPHABETIC`; source `0x101` alone is
insufficient evidence. The v1 reports did not include keyboard type.

After that gate, type 1 can come from kid mode, disabled keyboard shortcuts, a
matching entry in the private skip-interception map, or desktop-mode handling.
The inspected built-in package exception is `com.aliyun.wuying.enterprise` for
Alt+Tab / Meta-related shortcuts, not a blanket exemption for Page Up/Down.
Disabling keyboard shortcuts or editing the private map is not established as an
unprivileged app operation. Type 2 sends qualifying events to AOSP handling.

The before-dispatching early return is readable in the inspected
`BaseMiuiPhoneWindowManager` snapshot. Its before-queueing method was not
successfully decompiled in that snapshot, so the exact queue-stage ordering in
the other agent's report needs its source/disassembly or the target firmware.
Skipping only dispatch-stage handling would not cancel an already scheduled timer.

Probe v2's explicit firmware export records pen-device keyboard types and copies
a fixed allowlist of readable Xiaomi framework JARs and screenshot/security-core
code. No root, ADB, privileged grant, or system write is used. The raw code budget
is 24 MiB; denied reads and size-budget skips are recorded. No user data is
collected, and nothing is uploaded automatically. This supplies the exact-build
code and device-capability evidence needed for this path.

## Excluded or incomplete approaches

- Writing `Settings.Secure.gb_boosting` directly requires privileges that an
  ordinarily installed app cannot request through a runtime permission dialog.
  ADB/Shizuku or root-based grants do not meet this task's constraints.
- Manually placing Noti in Game Turbo is a conditional user-settings experiment,
  not a verified app implementation. Xiaomi [documents][faq] that Game Turbo
  disables the stylus by default; disabling the pen would not satisfy the goal.
- `FLAG_SECURE` failed the target-device test: warnings appeared and usable
  screenshot-button holds did not arrive. It is not a production workaround.
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

[keyextend]: https://github.com/raghavt20/miui-services/blob/d9e4df4bed682f316f555858588e2130611ad768/sources/com/android/server/policy/MiuiKeyInterceptExtend.java
[inputdevice]: https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/view/InputDevice.java
