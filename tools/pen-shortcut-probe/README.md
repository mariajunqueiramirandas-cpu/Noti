# Noti pen shortcut probe

An isolated Android test app for **Xiaomi Pad 7 / HyperOS 3.0.303.0**, investigating
suppression of the original Focus Pen's system hold actions. The note-window
identity was confirmed to suppress Mi Canvas and deliver writing holds on this
build. Screenshot protection produced warnings and did not yield usable holds.
This is not a verified solution for both buttons. It is not included in Noti.

The installed package is `app.noti.penprobe`. No Android permissions are declared;
no root, ADB, accessibility service, network access, Xiaomi SDK, or global-setting
changes are used. The app stores its last report in its own private data and copies
reports only when you press a copy button. Version 2 also offers an explicit
user-selected ZIP export of a fixed list of readable system-code files.

Four modes open fresh Activity windows:

1. Normal window (control).
2. `FLAG_SECURE` only.
3. Initial window `packageName = "com.miui.creation"` only.
4. Both attributes.

The third mode changes a window attribute, not the installed app ID or UID. The
hypothesis is that Xiaomi's note exemption trusts that attribute. The user has
confirmed suppression of Mi Canvas with usable writing holds. Screenshot protection
also blocks normal system screen capture of the test window; a screenshot error
message or interrupted pen input counts as a failed workaround.

## Build

Requirements: Python 3, JDK 17+, Android SDK platform 36 and Build Tools 36.0.0.

```sh
python3 tools/pen-shortcut-probe/build.py \
  --sdk "$ANDROID_SDK_ROOT" --jdk "$JAVA_HOME" \
  --out work/pen-shortcut-probe
```

The APK is `work/pen-shortcut-probe/noti-pen-probe.apk`. The script downloads
checksum-pinned build dependencies, compiles against the public Android SDK,
aligns the APK, generates a local test signing key if absent, signs and verifies
the APK, and rejects an APK declaring permissions. From v3, the key is kept in
the chosen output directory so subsequent builds there can update in place.
V1/v2 regenerated signing keys, so uninstall the old probe once before installing
v3. This does not affect the separately installed Noti app.

The bytecode-report fixture checks run with:

```sh
python3 tools/pen-shortcut-probe/verify.py \
  --sdk "$ANDROID_SDK_ROOT" --jdk "$JAVA_HOME" \
  --build-out work/pen-shortcut-probe
```

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

## Export exact firmware without ADB

In v2, open **Export pen firmware → Save firmware ZIP** and choose a destination.
Keep the pen connected. Attach the resulting `noti-pen-firmware.zip` in the chat.
There is no need to repeat the four hold modes for this export.

The ZIP includes the build fingerprint, pen-device keyboard types/source masks,
readable `miui-services.jar` / `miui-framework.jar` files, and code/manifest entries
from `com.miui.screenshot` and `com.miui.securitycore` when available. It does not
collect notes, accounts, personal files, settings or other apps' data. Nothing is
uploaded automatically. Package visibility is declared for only those two system
packages; no Android permissions are declared.

Code payload is bounded to 24 MiB before compression. The report records hashes,
read failures and size-budget skips; the ZIP can be incomplete or report-only on
firmware that denies reads. Source-read failures are distinct from destination
write failures, which are reported as an unsuccessful export. No system binaries
are committed to this repository or published in the build artifact.

## Copy exact firmware inspection as text (v3)

If ZIP upload is unavailable, choose **Inspect firmware (copy text)**. The app
reads a fixed allowlist of Xiaomi framework JARs locally and disassembles selected
pen-policy methods with smali-dexlib2. It does not load or execute the system
classes, change system files or settings, or use network access. Parser libraries
are packaged in the APK with license notices under `assets/`.

Copy **Summary**, then **Screenshot handler**, into the chat. Each section is
split into numbered parts small enough to paste; use **Next** when needed. There
is no need to repeat the four button tests or upload the ZIP. If further evidence
is needed, the same report also contains dispatch/delay policy, queue interception,
keyboard interception, writing references and remaining pen-policy methods.

The report gives raw DEX code-unit addresses, registers, resolved references,
literal values, branches and exception ranges, not guessed Java source. Switch
payload offsets are explicitly relative to their switch opcode. Missing, unreadable,
unsupported or truncated code is labeled; none proves a system condition is absent.
Host fixture checks cover literal/reference resolution, branches, switch payloads,
exception ranges, missing implementations, class scope, and rejection of corrupt
input. Actual framework readability still needs the target-device report.
