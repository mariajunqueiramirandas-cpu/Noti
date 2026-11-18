# Noti ink engine (C++20)

The native ink engine and stroke predictor from [the low-latency plan](../docs/LOW_LATENCY_PLAN.md)
(Phase 1 + Phase 2). Nothing on the per-sample path allocates, takes a lock, logs, or makes a binder call. The
portable core builds and is tested on any Linux host.

## The ink path

```
            ┌──────────────────────── "noti-ink" thread ────────────────────────┐
 digitizer  │ nice −10, pinned to the prime core (kept hot by a SCHED_IDLE      │
            │ spinner), ADPF at max boost · owns: input window, EGL context,    │
 240 Hz ───►│ engine (inline mode), its own Choreographer                       │
            │                                                                   │
            │ InkWindow.dispatchTouchEvent every historical sample, hw time;    │
            │  routed per pointer: pen → ink, fingers → palm filter (below)     │
            │  └ 40 B records → direct ByteBuffer (= native memory)             │
            │  └ @CriticalNative nativeProcess(h, n)  ── plain C call ──┐       │
            │                                                           ▼       │
            │ InkEngine::process   predictor (~120 ns/sample)                   │
            │                      horizon = exact time-to-photon for THIS      │
            │                        frame (sample age + GPU + scan line        │
            │                        reaching the ink's row + LCD)              │
            │                      tapered capsules → stroke store (0-copy)     │
            │ GL renderer          blit (erase old tail) + ink + tail           │
            │                        → FRONT BUFFER → glFlush   ◄── visible     │
            │                      ink → backing texture → glFlush (off-path)   │
            └───────────────────────────────────────────────────────────────────┘
 UI thread: setters only (lock-free command ring)    tx thread: every ASurfaceTransaction
```

**Fingers** take a parallel path on the same thread: 40-byte contact records → `@CriticalNative nativeTouch` →
`PalmFilter` → finger ink straight into the engine, deliberate touches into a gesture buffer that Kotlin drains
after each call (pan, zoom, page turns). Pen samples inform the filter first (`observe_pen`), so a pen arriving
rolls back a finger stroke before its own ink is drawn. The filter costs ~80 ns per event.

**Why a separate thread with its own window:** a window added from a Looper thread has its `ViewRootImpl`, and
therefore its input dispatch, on that thread. The UI thread (Compose, layout, GC: the 0–20+ ms spikes in plan §2,
stage 4) is not on the ink path at all. Because the same thread renders, there's also no hand-off between "input
thread" and "render thread". The input event is drawn before `onTouchEvent` returns.

## Ink and the moving page

The page under the ink (PDF or paper) is drawn by the editor's window; committed ink is the engine's own dry layer.
Updated independently, the two reach SurfaceFlinger on different frames, so while a document is panned or zoomed the
ink would lead or trail its page by a frame or two and settle when the motion stops. On Android 14+ the dry layer
follows the editor window's frames instead (`GlFrontBufferRenderer::host_frame`):

- a viewport change renders a dry buffer for the new layout and parks it;
- just before the editor window draws (`OnPreDrawListener`), the parked buffer, if any, and the scale/offset that maps
  the newest buffer's layout onto the layout being drawn go into a transaction applied with that very frame
  (`ASurfaceTransaction_fromJava` + `applyTransactionOnDraw`). Pages sit on whole pixels, so a pan is an exact integral
  offset; between buffers the geometry alone keeps the ink on its page. The dry layer sits in a container cropped to
  the canvas, so a moved layer never paints outside it;
- pen commits and edits stay on the transaction thread. They end the session and reset the geometry, and the
  transaction thread first lets host frames already carrying dry-layer state land, so nothing older can cover a commit.
  Watchdogs fall back to the engine's own path if the host stops drawing or its callbacks never return.

The wet (front-buffer) layer and the pen's latency path are unchanged. Before Android 14 the dry layer follows the
viewport on its own, as before.

## Performance policy: power is not a constraint

The engine is built for minimum latency, with battery life and thermals explicitly out of scope. Everything that
trades latency for power is off, permanently, from the moment the canvas is on screen:

| Knob | Setting |
|---|---|
| CPU | Ink thread at nice −10, **pinned to the fastest core** (highest `cpu_capacity`). A **`SCHED_IDLE` heater thread** spins on the same core whenever the ink thread sleeps: the core never enters an idle state (no idle-exit latency on wake-up) and the governor sees 100% load (max clock, no ramp-up). A pen event preempts the heater immediately. |
| ADPF | Session always told it is over budget, so it holds the **maximum boost** instead of backing off because batches take only a few µs. API 35 power-efficiency mode is off. |
| GPU | Touched every **4 ms**, always: it never enters a low-power state it has to wake from. Context priority is **realtime** where the driver allows it, otherwise high. |
| Display | **Highest refresh mode pinned** on both the ink window and the host window (`preferredDisplayModeId`), `FIXED_SOURCE` + `CHANGE_FRAME_RATE_ALWAYS` on the ink layers, minimal post-processing requested. Vsync timing is fed every frame. |
| Threaded mode | The render thread **never sleeps** (`spin_ns = -1`): it polls the ring continuously. |
| ART | `baseline-prof.txt` AOT-compiles the Kotlin input path, so the first strokes after install aren't interpreted. |

If sustained sessions ever show thermal throttling of the prime core, pin to an A720 core instead
(`GlRendererOptions::pin_and_heat_core`, `fastest_cpu()`).

## Layout

| Path | What |
|---|---|
| `include/noti/engine.h`, `src/engine.cpp` | Engine loop. **Inline mode** (production): the input thread processes and draws. **Threaded mode** (benchmarks, fallback): SPSC ring + render thread with predictive wake. |
| `include/noti/predictor.h`, `src/predictor.cpp` | Kalman / polynomial / linear predictor, confidence gating, optional One-Euro on the tail. |
| `include/noti/display_timing.h` | Scan-out-aware horizon: when will ink drawn *now* be visible? |
| `include/noti/geometry.h` | Exact SDF of a tapered capsule, pressure curve LUT, rects, view→surface affine. |
| `include/noti/spsc_ring.h`, `wake_event.h` | Wait-free SPSC ring; futex sleep/wake (syscall only if the consumer sleeps), timed wait. |
| `include/noti/stroke_store.h` | Append-only stroke arena; the active stroke writes into it directly (commit copies nothing). Strokes belong to pages; a canonical page-space copy lets `remap_page` re-place a page's ink exactly on zoom, pan or rotation (no drift). |
| `include/noti/palm_filter.h`, `src/palm_filter.cpp` | Palm rejection: every finger contact scored by sequential evidence (pen state, learned hand model, pen coupling, contact shape and motion, clusters), deliberate touches replayed to gestures, speculative finger ink with rollback, online learning. See [docs/PALM_REJECTION.md](../docs/PALM_REJECTION.md). |
| `include/noti/stroke_recorder.h` | Raw stroke recorder → CSV for `predictor_bench --csv`. |
| `include/noti/latency_probe.h` | Lock-free histograms (§5.1): in→recv, recv→submit, in→submit, submit→GPU, frame CPU, prediction error, horizon. |
| `include/noti/renderer.h`, `src/cpu_renderer.cpp` | Renderer contract and the reference software renderer used by the pixel tests. |
| `src/android/gl_renderer.*` | GLES 3 front-buffer renderer on `AHardwareBuffer` + `ASurfaceControl`. |
| `src/android/perf_session.*` | Core pinning, `SCHED_IDLE` core heater, nice −10, ADPF held at max boost. |
| `src/android/jni_bridge.cpp` | JNI (`RegisterNatives`, `@CriticalNative`). |
| `android/kotlin/app/noti/ink/` | `InkLayer` (public API), `InkWindow` (ink-thread input window), `NotiInk` (natives). |
| `android/baseline-profile/baseline-prof.txt` | Baseline profile: AOT-compile the input path. |
| `tools/predictor_bench.cpp` | Predictor harness: models × horizons, display-pipeline simulation, tuner, CI gate, `--csv`. |
| `tools/engine_bench.cpp` | Real-time 240 Hz replay (threaded and inline), burst throughput, gates. |
| `tools/palm_bench.cpp`, `tools/synthetic_touches.*` | Palm rejection harness: 20 labelled pen + multi-touch scenarios × seeds × hands × size reporting, CSV replay of on-device recordings, CI gate. |
| `tests/tests.cpp` | 42 unit tests (no framework dependency). |

## Latency techniques, by pipeline stage

| Stage (plan §2) | Technique |
|---|---|
| 3 · vsync batching | `requestUnbufferedDispatch`; all historical samples with hardware timestamps |
| 4 · UI thread | **Input window on the ink thread**: the UI thread never sees pen events; earliest View hook (`dispatchTouchEvent` on the window root) |
| wake-up | Ink thread on a pinned core that a `SCHED_IDLE` heater keeps out of idle states and at max clock |
| hand-off | **Inline mode**: the input thread is the render thread (threaded mode: SPSC ring + predictive wake) |
| 5 · render | One scissored instanced draw per update, SDF capsules (no tessellation), persistent coherent instance ring (`GL_EXT_buffer_storage`), **visible work submitted first**, backing update after, high-priority EGL context (`EGL_IMG_context_priority`) |
| 5 · cold GPU | **GPU keep-alive** every 4 ms, always; realtime/high GPU context priority |
| 5 · binder | **Transaction thread**: commit, front-buffer resubmit (with damage region) and frame-rate changes never block the ink thread |
| 6 · composition | `USAGE_FRONT_BUFFER` wet layer; atomic wet→dry commit with ping-ponged wet buffers |
| 7 · scan-out | Front buffer: ink is visible the moment the scan line passes its row |
| 1, 7, 8 · physics | **Predictor with a per-frame, scan-out-aware horizon** |
| clocks / display | ADPF held at max boost; highest refresh mode pinned on both windows, always |

## Predictor

Every model produces {velocity, acceleration} at the newest sample. The tail is `p + v·t + ½·g·a·t²`, drawn from the
**raw** newest point so there's no kink where real ink ends. Gates can only **shorten** the horizon: speed ramp, turn
cap, braking (never past the stop point), pen lift, jerk truncation error (|j|·h³/6 ≤ 0.72 px), Kalman innovation
(manoeuvre), and a distance cap. Confidence (gated ÷ requested horizon) drops instantly and recovers at a bounded
rate, so the tail never flickers.

### The horizon is computed per frame

With a front buffer, ink becomes visible when the scan line next passes its row, not at a vsync. That wait alone
swings by a full refresh period (0–6.9 ms at 144 Hz) from frame to frame. So for every frame the engine computes:

```
horizon = (now − t_sample)  +  GPU  +  wait for the scan line at the ink's row  +  LCD response
          exact                ~0.35 ms  vsync phase (ink thread's Choreographer)   feel-calibrated
                                         + panel scan geometry
```

`predictor_bench --display-sim` simulates the pipeline (dispatch 0.6 ms + exponential tail, 144 Hz scan-out, 5 ms LCD)
on 1,500 synthetic strokes:

| horizon policy | mean gap | p99 overshoot | frames with visible (>4 px) overshoot |
|---|---|---|---|
| fixed (the best constant) | 3.22 px | 9.33 px | 10.33% |
| sample age only | 3.18 px | 9.00 px | 9.67% |
| **scan-out-aware** | **2.31 px** | **3.49 px** | **0.54%** |

A fixed horizon overshoots visibly in one frame out of ten under realistic jitter. The scan-aware horizon cuts the
mean gap by 28% and visible overshoot by about 19×. Until the panel's scan direction is known (`ScanAxis::kUnknown`),
the engine uses the exact sample age plus the average scan wait.

### Models at a fixed 12 ms horizon (`predictor_bench`)

| model | mean gap | gain vs. no prediction | p99 overshoot | visible overshoot | cost |
|---|---|---|---|---|---|
| linear | 4.44 px | 72% | 12.35 px | 8.20% | 60 ns |
| polynomial | 2.76 px | 82% | 4.17 px | 1.23% | 160 ns |
| **Kalman (default)** | 3.14 px | 80% | **2.68 px** | **0.15%** | 120 ns |
| Kalman + One-Euro | 4.39 px | 72% | 5.27 px | 2.31% | — |

The defaults were tuned with `predictor_bench --tune` on synthetic strokes. **Re-tune on real Pad 7 strokes:**
`InkLayer.setRecording(true)`, write for a while, `exportRecording(path)`, `adb pull`, then
`predictor_bench --csv strokes.csv --tune 500`.

## Measured (CI host: 4-vCPU x86-64 VM, clang 18, -O3, null renderer)

| sample received → draw submitted | p50 | p90 | p99 | max |
|---|---|---|---|---|
| **inline mode (production path)** | **5.8 µs** | **7.8–10 µs** | **18–29 µs** | **37–102 µs** |
| threaded, render thread never sleeps | 4.0–7.0 µs | 9–16 µs | 38–250 µs | 0.4–3.2 ms |
| threaded, futex + predictive wake | 6.3 µs | 32 µs | 160 µs | 1.85 ms |

Ranges span repeated runs on two different VM hosts. On a shared VM a spinning second thread still gets descheduled by the hypervisor, which is why having *no* second thread (inline) has by far the tightest tail.

Other numbers: the predictor costs about 120 ns per sample; burst throughput is about 300–370 ns per sample end to end;
and engine CPU per batch is about 5 µs p50 (cold caches after the 4 ms gap between samples). On the device, the GPU
time and the scan-out wait dominate. The on-device probe and `latencyStats()` report the real numbers.

## Build

Host (core, tests, tools):

```sh
cmake -S engine -B engine/build -G Ninja && cmake --build engine/build
engine/build/noti_tests
engine/build/predictor_bench                 # models × horizons
engine/build/predictor_bench --display-sim   # horizon policies under realistic display timing
engine/build/predictor_bench --by-kind       # per stroke type
engine/build/engine_bench --seconds 5        # threaded vs. inline, real-time 240 Hz replay
(cd engine/build && ctest)                   # unit tests + predictor quality gate + engine cost gate
```

Sanitizers: `-DNOTI_TSAN=ON` or `-DNOTI_SANITIZE=ON` (ASan+UBSan). The suite is clean under both (GCC 13).

Android (arm64, minSdk 33):

```sh
cmake -S engine -B engine/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 -DANDROID_STL=c++_static
cmake --build engine/build-android        # → libnoti_ink.so (16 KB-page aligned, exports only JNI_OnLoad)
```

The app module (`app/build.gradle.kts`) does this from Gradle: `externalNativeBuild.cmake.path` points at
`engine/CMakeLists.txt`, `engine/android/kotlin` is a Kotlin source directory and `engine/android/baseline-profile` a
baseline-profile directory (`compileSdk >= 34` for `@CriticalNative`). Usage:

```kotlin
val ink = InkLayer(canvasView)            // after setContentView; attaches when the view does
ink.setColor(0.1f, 0.1f, 0.12f); ink.setWidth(5f)
ink.setDisplayTiming(panelMs = 5f)        // feel slider (plan §5.4)
// onDestroy: ink.release()
```

## Verification status

| | Status |
|---|---|
| Portable core | 29 unit tests, including pixel-exact invariants against the reference renderer: no tail residue, wet = dry, cancel erases, a long stroke is never split. Also covered: scan-timing math, inline mode with real-time vsync, recorder round trip. Clean under TSan, ASan+UBSan, clang and GCC. |
| Android native layer | Compiles warning-free with NDK r27c for arm64 / API 33. **Not yet run on a device.** |
| Kotlin | Type-checked against the API 35 `android.jar` with kotlinc. **Not yet run on a device.** |

Phase 0 device checks before relying on it:

1. Ink orientation: if strokes appear vertically mirrored, pass `flipY = true`.
2. The input window sits over the canvas and receives the pen (`adb shell dumpsys window | grep noti-ink`).
3. `dumpsys SurfaceFlinger`: the `noti-wet` layer must be **DEVICE** (overlay) composited, and the panel must stay
   at 144 Hz. If it's DEVICE, set `resubmit_front_buffer = false` (it only covers CLIENT composition).
4. Panel scan geometry for `setDisplayTiming(scanAxis=…)`: until known, leave it at 0 (unknown).
5. `gpuTiming = true` fills `submit_to_gpu` (adds a native fence per frame: debug only). Feed its p50 into
   `setDisplayTiming(gpuMs=…)`.

## Document editing and persistence

`set_pages()` places the visible pages of a continuous document; input chooses its page at pen-down,
and the renderer composites all visible page ink. Identical placements are ignored so touching down after
scrolling cannot split a stroke. `set_page()` remains available for single-page hosts and benchmarks.

`document.cpp` implements stroke erasing (a swept hit test), polygon lasso selection, bounded movement,
and transactional undo/redo. Cancellation rolls an erase or move back. `edit_page()` inserts, duplicates
or removes page ink and rejects a duplicate before mutation if the arena lacks capacity.

`StrokeStore::encode/decode` uses the versioned little-endian NTI1 format: magic, version, stroke count,
then each visible stroke's page, capsule count, four premultiplied color floats and canonical capsule floats.
Decode validates the complete bounded archive before replacing existing ink. Hidden undo/eraser records
and session history are omitted. Android snapshots between strokes, writes through `AtomicFile` on IO,
and takes a final snapshot before destroying the engine. The original sample/predictor path stays unchanged.

The Compose document subtree is clipped to its viewport before it can paint over editor chrome.
`InkAnchorBounds` tracks the anchor's window-relative position on pre-draw, including ancestor movement,
translation and inset changes. It sends only changed bounds to the ink window, whose coordinates use
physical left/top without applying system insets a second time. None of that work runs on pen dispatch.
