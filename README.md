# Noti

The lowest-latency PDF annotator and note-taking app for the **Xiaomi Pad 7 + Xiaomi Focus Pen**. It should feel like writing on paper.

- 📄 [Low-latency ink engine plan](docs/LOW_LATENCY_PLAN.md): hardware analysis, latency budget, engine architecture, measurement lab, and roadmap.
- ⚙️ [Native ink engine + predictor (C++20)](engine/README.md): lock-free input path, front-buffer GL renderer, tuned Kalman predictor, latency probe, benchmarks.
- ✋ [Palm rejection](docs/PALM_REJECTION.md): sequential evidence per contact (pen state, a learned model of your hand, pen coupling, contact shape, clusters), replayed gestures, speculative finger ink, measured by `palm_bench`.

## Android app & releases

`app/` is the Android app (Gradle, AGP 9). It builds `engine/` through CMake and compiles the ink Kotlin sources
from `engine/android/kotlin`. Only arm64 and Android 13+ are supported.

The UI is Jetpack Compose (Material 3): library, editor, settings. Each writing area hosts the native engine's own
input window over it, so toolbars and panels are docked beside it, never over it (`ui/common/InkCanvas.kt`).
**PDFs**: New → Import PDF copies a PDF into the app (rendered with the platform's PdfRenderer on its own thread).
One page at a time, fitted to the canvas; pinch to zoom (sharp re-render when the page comes to rest), drag to pan,
swipe or use the toolbar arrows / page panel to turn pages. Ink is kept per page in page coordinates, so it stays
on the page through zoom, pan and rotation. Fingers pass palm rejection first.

Controls whose engine support isn't built yet (undo/redo, eraser, lasso, more notebook pages, export, saving ink) are
shown greyed out and say "coming soon" when tapped. Release builds use R8, which runs in its own JVM with its own
heap (`settings.gradle.kts`; `-Pnoti.r8.maxHeap=…`, default 8 GB, CI gives it half the runner's RAM).

```sh
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:assembleDebug     # debug-signed, installable
```

**Every commit to `main` publishes a signed APK as a GitHub Release** (`.github/workflows/release-apk.yml`,
tag `v0.1.<run number>`; the version code is the run number, so each release installs over the previous one).
A warm run builds in about 10 s. All tools are preinstalled on the runner, one cache holds Gradle's dependencies,
build cache and configuration cache, and the heap and worker count are sized to the runner.

### Signing key

Add these repository secrets (Settings → Secrets and variables → Actions) so every release is signed with the same
key and updates install in place:

```sh
keytool -genkeypair -keystore noti-release.jks -alias noti -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Noti" -storepass '<password>' -keypass '<password>'
base64 -w0 noti-release.jks   # → SIGNING_KEYSTORE_BASE64
```

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the base64 output above |
| `SIGNING_KEYSTORE_PASSWORD` | `<password>` |
| `SIGNING_KEY_ALIAS` | `noti` |
| `SIGNING_KEY_PASSWORD` | `<password>` (same as the store password for PKCS12) |

Keep `noti-release.jks` somewhere safe and out of the repo. Without these secrets, CI signs with a fallback key it
generates once and keeps in the Actions cache. That works, but if the cache entry is ever evicted the key changes,
and you'll have to uninstall before the next APK will install.
