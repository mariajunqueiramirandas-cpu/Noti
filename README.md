# Noti

The lowest-latency PDF annotator and note-taking app for the **Xiaomi Pad 7 + Xiaomi Focus Pen**. It should feel like writing on paper.

- 📄 [Low-latency ink engine plan](docs/LOW_LATENCY_PLAN.md): hardware analysis, latency budget, engine architecture, measurement lab, and roadmap.
- ⚙️ [Native ink engine + predictor (C++20)](engine/README.md): lock-free input path, front-buffer GL renderer, tuned Kalman predictor, latency probe, benchmarks.
- ✋ [Palm rejection](docs/PALM_REJECTION.md): sequential evidence per contact (pen state, a learned model of your hand, pen coupling, contact shape, clusters), replayed gestures, speculative finger ink, measured by `palm_bench`.

## Android app & releases

`app/` is the Android app (Gradle, AGP 9). It builds `engine/` through CMake and compiles the ink Kotlin sources
from `engine/android/kotlin`. Only arm64 and Android 13+ are supported.

The UI is Jetpack Compose (Material 3): library, editor, settings. Phone windows, including the Poco X8 Pro, use a navigation drawer,
a full-width search field, a compact selection menu and an adaptive notebook grid. Settings use a section menu
and stack the stationary pen/palm test canvas below scrollable controls when space is narrow. Paper choices wrap,
and dialogs scroll on short screens or with larger text. Layouts follow Android's available dp size rather than
physical screen pixels, so display scaling, rotation and split-screen are supported.

The editor is a full canvas with translucent glass
islands and a draggable radial tool selector. Floating controls use sub-panel popup windows above the native
ink input panel, so taps reach the controls without changing the low-latency drawing path (`ui/common/InkCanvas.kt`).
**Pages**: notebooks and imported PDFs scroll vertically as a continuous document. Drag to scroll,
pinch to zoom, drag the right-hand scroll indicator, and use page arrows or the floating pages panel to jump.
The page counter, proportional scroll thumb and zoom percentage follow the live viewport; tap zoom to fit width. Momentum stops when the pen touches down.
Only visible pages are rendered; PDF neighbours are prefetched and zoomed detail is rendered after scrolling settles.
Ink uses canonical page coordinates, so scrolling, zooming, rotation and reopening do not move annotations.

**Writing**: tap the floating brush to open the radial pen, highlighter, stroke eraser and lasso selector; drag its
centre to reposition it. Quick colors, widths and brush options live inside the expanded selector. A separate
floating undo/redo island keeps editing history accessible. Circle whole strokes with the lasso, then drag inside
the selection to move them. Hold a primary stylus button (or use the eraser tip) for erasing; the secondary button
selects. Two-finger tap undoes (configurable); two-finger double-tap exits full screen.

**Saving**: completed edits are autosaved to versioned `.noti` files with `AtomicFile` on a separate IO executor.
Snapshots run between strokes; pause, leaving and release also save. Invalid archives are preserved instead of being
overwritten. Undo history lasts for the editing session (up to 256 operations); reopening restores the visible ink.
Notebooks can add, duplicate and delete pages, with the last page protected. A4 and Letter paper sizes work;
legacy Endless paper uses the A4 page proportion with additional pages available from the toolbar or page panel.

**Export**: export a flattened PDF with paper/source PDF and annotations from the editor or library. Multiple
library selections export as a ZIP of PDFs. Source PDF pages are rasterized at up to 2× for export; ink stays vector.
Duplicating a notebook copies its saved ink; permanent deletion removes it.

Release builds use R8, which runs in its own JVM with its own heap (`settings.gradle.kts`;
`-Pnoti.r8.maxHeap=…`, default 8 GB, CI gives it half the runner's RAM).

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

### Verification

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
cmake -S engine -B work/build-engine -DCMAKE_BUILD_TYPE=Release
cmake --build work/build-engine -j
ctest --test-dir work/build-engine --output-on-failure
```

The host suite covers editing history, eraser cancellation, lasso movement, archive validation,
multiple visible pages, page operations, palm rejection, prediction quality and the engine CPU budget.
JVM viewport tests cover continuous dragging, bounds, pinch anchoring, mixed page sizes, resize and large documents.
Validate actual Focus Pen button mappings, front-buffer rendering and visual appearance on an arm64 Android 13+ tablet.

Android graphics regression tests run with Robolectric and native Skia as part of `:app:testDebugUnitTest`.
They render scrolled/zoomed PDF bitmaps, paper and selection overlays to verify that pixels stay inside
the document viewport, and exercise toolbar controls at portrait, landscape, narrow-window and large-font sizes.
The native window's anchor is also tested after ancestor movement without an anchor layout change.
Rendered editor screenshots are written to `app/build/reports/editor-ui/`. Phone library, settings and paper-picker
regressions cover 412 × 915 dp, 360 × 640 dp with 1.5× text, and 915 × 412 dp landscape. A 423 × 919 dp
profile at 3× density approximates the Poco X8 Pro’s [1268 × 2756 display](https://www.mi.com/global/product/poco-x8-pro/specs/)
to whole dp. Their screenshots are in `app/build/reports/mobile-ui/`.
The Android UI regressions workflow runs these checks on PRs and uploads the reports and screenshots.
These host tests do not replace Focus Pen or SurfaceFlinger testing on the tablet.
