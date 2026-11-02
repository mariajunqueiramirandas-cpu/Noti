# Noti

The lowest-latency PDF annotator and note-taking app for the **Xiaomi Pad 7 + Xiaomi Focus Pen**. It should feel like writing on paper.

- 📄 [Low-latency ink engine plan](docs/LOW_LATENCY_PLAN.md): hardware analysis, latency budget, engine architecture, measurement lab, and roadmap.
- ⚙️ [Native ink engine + predictor (C++20)](engine/README.md): lock-free input path, front-buffer GL renderer, tuned Kalman predictor, latency probe, benchmarks.

## Android app & releases

`app/` is the Android app (Gradle, AGP 9). It builds `engine/` through CMake and compiles the ink Kotlin sources
from `engine/android/kotlin`. Only arm64 and Android 13+ are supported.

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
