# Noti pen shortcut probe

An isolated Android test app for **Xiaomi Pad 7 / HyperOS 3.0.303.0**, investigating
suppression of the original Focus Pen's system hold actions. This is an experiment,
not a verified workaround. It is not included in Noti or its release build.

The installed package is `app.noti.penprobe`. No Android permissions are declared;
no root, ADB, accessibility service, network access, Xiaomi SDK, or global-setting
changes are used. The app stores its last report in its own private data and copies
reports only when you press a copy button.

Four modes open fresh Activity windows:

1. Normal window (control).
2. `FLAG_SECURE` only.
3. Initial window `packageName = "com.miui.creation"` only.
4. Both attributes.

The third mode changes a window attribute, not the installed app ID or UID. The
hypothesis is that Xiaomi's note exemption trusts that attribute. Its acceptance
and effects on the target firmware have **not** been tested. Screenshot protection
also blocks normal system screen capture of the test window; a screenshot error
message or interrupted pen input counts as a failed workaround.

## Build

Requirements: Python 3, JDK 17+, Android SDK platform 36 and Build Tools 36.0.0.

```sh
python3 tools/pen-shortcut-probe/build.py \
  --sdk "$ANDROID_SDK_ROOT" --jdk "$JAVA_HOME" \
  --out work/pen-shortcut-probe
```

The APK is `work/pen-shortcut-probe/noti-pen-probe.apk`. The script compiles against
the public Android SDK, aligns the APK, generates a local test signing key, signs
and verifies the APK, and rejects an APK declaring permissions. The test signing
key is regenerated on each build, so uninstall an older probe before installing a
fresh build. This does not affect the separately installed Noti app.

## Device test without ADB

Install the APK by opening it on the tablet. For **each mode**, test writing and
screenshot buttons separately:

- Click, then hold for two seconds away from the display.
- Hold near the display, then keep holding while drawing on the blue area.
- Release and draw again. Observe Mi Canvas, screenshot selection, warnings,
  missing ink, and whether ordinary ink still works.
- Press **Copy report** before leaving the test. Paste the report somewhere you
  control and add what system UI appeared, or state that none appeared. A trace
  alone cannot establish that no system action occurred.

Reports include the actual OS fingerprint, requested attributes, raw key codes,
press durations, motion-button changes, focus changes, and pauses. The view logs
all keys but consumes only stylus codes and pen-origin Page Up/Down. It does not
implement Noti's erase/lasso actions. The mode's success requires both suppression
of the unwanted system behavior and usable input; screenshot blocking alone is
insufficient.

If a mode crashes, reopen the app and use **Copy last test / failure report**.
Rejecting the window attribute is a useful negative result. Do not infer system
acceptance from the displayed requested attribute.

See [the investigation](../../docs/XIAOMI_PEN_SHORTCUTS.md) for evidence and limits.
