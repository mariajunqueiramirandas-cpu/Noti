# Noti ink engine (C++20)

The native ink engine and stroke predictor from [the low-latency plan](../docs/LOW_LATENCY_PLAN.md)
(Phase 1 + Phase 2). There's no allocation, lock, log or syscall on the per-sample path, and the
portable core builds and is tested on any Linux host.

```
UI thread (Kotlin)                         ink render thread (C++, nice −8, ADPF session)
──────────────────                         ───────────────────────────────────────────────
onTouchEvent                               wake: predictive (spin just before the next
 ├ every historical sample,                       240 Hz sample is due) or futex
 │ hardware timestamps                      drain ALL pending samples
 ├ write 40 B records into a direct        ├ predictor.add()       ~110 ns/sample
 │ ByteBuffer (= native memory)            ├ tapered capsules → stroke store (zero-copy)
 └ @CriticalNative nativePush(h, n) ──┐    ├ predicted tail (≤ 8 capsules)
                                      │    └ ONE renderer call, scissored:
   InkEngine::push                    │        draw ink → backing + front buffer
   memcpy → SPSC ring, 1 release      └──►     blit backing → front (erase old tail)
   store, 1 fence, futex only if               draw new tail → front buffer
   the render thread sleeps                    glFlush (never glFinish)
```

## Layout

| Path | What |
|---|---|
| `include/noti/spsc_ring.h` | Wait-free SPSC ring: cache-line-separated indices with cached copies, batch push/pop with one release store. |
| `include/noti/wake_event.h` | Futex sleep/wake with a Dekker handshake: the producer makes a syscall only if the consumer is asleep. Also has a timed wait. |
| `include/noti/predictor.h`, `src/predictor.cpp` | Kalman / polynomial / linear predictor, confidence gating, optional One-Euro on the tail. |
| `include/noti/geometry.h` | Exact SDF of a tapered capsule, pressure curve LUT, rects, view→surface affine. |
| `include/noti/engine.h`, `src/engine.cpp` | Render thread, stroke lifecycle, prediction erase/redraw, probe, predictive wake. |
| `include/noti/stroke_store.h` | Append-only stroke arena. The active stroke writes into it directly, so commit copies nothing. |
| `include/noti/latency_probe.h` | Lock-free log-linear histograms (§5.1): in→recv, recv→submit, in→submit, submit→GPU, frame CPU, prediction error. |
| `include/noti/renderer.h` | Renderer contract (front-buffer model, MAX-union blending, commit/cancel). |
| `src/cpu_renderer.cpp` | Reference software renderer with identical semantics, used by tests for pixel-exact invariants. |
| `src/android/gl_renderer.*` | GLES 3 front-buffer renderer on `AHardwareBuffer` + `ASurfaceControl`. |
| `src/android/perf_session.*` | Thread priority, ADPF session, API 35 power-efficiency toggle. |
| `src/android/jni_bridge.cpp` | JNI (`RegisterNatives`, `@CriticalNative`). |
| `android/kotlin/app/noti/ink/` | Kotlin side: `NotiInk` (natives) and `InkSurfaceView` (input forwarding). |
| `tools/predictor_bench.cpp` | Offline predictor harness: model × horizon comparison, tuner, CI gate, `--csv` for recorded Pad 7 strokes. |
| `tools/engine_bench.cpp` | End-to-end benchmark: real-time 240 Hz replay into a running engine, plus burst throughput and gates. |
| `tests/tests.cpp` | 25 unit tests (no framework dependency). |

## Renderer design (Android)

- **Wet layer:** two `AHardwareBuffer`s with `USAGE_FRONT_BUFFER` (API 33), drawn into while they're scanned out. On commit, the
  renderer switches to the other, already-cleared buffer in the **same** `ASurfaceTransaction` that shows the new dry
  buffer. The stroke moves from wet to dry in one frame, with no flicker and no hide/show gap.
- **Backing texture** holds the real wet ink. The previous predicted tail is erased with one small
  `glBlitFramebuffer` of its rectangle, then the new tail is drawn. Every operation is scissored to a few thousand pixels.
- **Ink = union of tapered capsules** via `GL_MAX` blending: joints are never double-blended, and redraws are idempotent.
  On commit, each stroke is union-rendered into a scratch texture, then composited src-over into the dry layer, so
  translucent ink looks identical wet and dry (pixel-tested by `engine_wet_equals_dry`).
- **Dry layer** is triple-buffered and incremental: each buffer draws only the strokes it is missing.
- **High-priority EGL context** (`EGL_IMG_context_priority`, supported on Adreno), so ink preempts page/PDF GPU work.
- Instance data goes through a 64 K-capsule ring VBO with unsynchronised maps. Quads come from
  `gl_VertexID`, so each update is one instanced draw call.
- Release fences from transaction callbacks are waited on the **GPU** (`eglWaitSyncKHR`); the CPU never blocks.
- Hover enter switches to 144 Hz (`FIXED_SOURCE`, `CHANGE_FRAME_RATE_ALWAYS`) and turns off ADPF power-efficiency.
  "Pen away" is applied only after 1.5 s, because `HOVER_EXIT` also precedes every pen-down.

## Predictor

Every model produces {velocity, acceleration} at the newest sample. The tail is
`p + v·t + ½·g·a·t²`, drawn from the **raw** newest point so there's no kink where real ink ends. Gates can only
**shorten** the horizon:

| Gate | Rule |
|---|---|
| speed ramp | no prediction below ~1 cm/s (lag < 2 px anyway) |
| turn cap | heading change over the tail ≤ 0.95 rad |
| braking | h ≤ 0.9 × time-to-stop: never extrapolate past where the pen stops |
| pen lift | low and falling pressure: h ≤ 0.5 × time-to-lift |
| jerk (truncation) cap | \|j\|·h³/6 ≤ 0.72 px: prediction shortens when motion stops being smooth |
| manoeuvre | Kalman innovation NIS > 18 scales h down |
| distance cap | tail ≤ 90 px |
| fast attack / slow release | the horizon shrinks instantly but grows ≤ 1 ms per ms, so the tail length never flickers |

Defaults were tuned with `predictor_bench --tune` to minimise the mean ink-to-nib gap, subject to fewer than 1% of
frames showing more than 4 px (≈ 0.3 mm) of overshoot. Results on 1,500 synthetic 240 Hz strokes (cursive, lines,
zigzags, loops, signatures; σ = 0.2 px noise, ±0.15 ms jitter) at a 12 ms horizon:

| model | mean gap | p95 | gain vs. no prediction | p99 overshoot | frames with visible overshoot | cost |
|---|---|---|---|---|---|---|
| linear | 4.44 px | 14.4 px | 72% | 12.35 px | 8.20% | 60 ns |
| polynomial | 2.76 px | 9.2 px | 82% | 4.17 px | 1.23% | 160 ns |
| **Kalman (default)** | 3.14 px | 10.3 px | 80% | **2.68 px** | **0.15%** | 110 ns |
| Kalman + One-Euro | 4.39 px | 13.8 px | 72% | 5.27 px | 2.31% | — |

The Kalman model with gating gives up about 0.4 px of mean gap compared with the polynomial model, in exchange for 8×
less visible overshoot ("a wrong prediction is worse than latency"). One-Euro smoothing of the tail made every metric
worse, so it's off by default. **These defaults come from synthetic strokes.** Record real strokes on the Pad 7 and
re-tune with `predictor_bench --csv strokes.csv --tune 500` (CSV columns: `stroke_id,t_ns,x,y,pressure`).

## Measured (this repo's CI host: 4-vCPU x86-64 VM, clang 18, -O3)

| | p50 | p90 | p99 |
|---|---|---|---|
| input → render thread hand-off, **predictive wake** | **1.7 µs** | 24 µs | 168 µs |
| input → render thread hand-off, futex only | 100 µs | 160 µs | 303 µs |
| sample received → renderer call returned (null renderer) | 6.3 µs | 32 µs | 168 µs |
| engine CPU per batch | 4.7 µs | 6.5 µs | 19 µs |

Burst throughput is about 280 ns per sample end to end (ring + predictor + geometry + frame, one frame per sample). On
the VM, predictive wake caught 88% of samples without a futex wake-up; the p99/max values are the VM's scheduler. On the
Pad 7 the hand-off runs under ADPF on a big core, so expect better numbers; the on-device probe reports the real ones.

## Build

Host (core, tests, tools):

```sh
cmake -S engine -B engine/build -G Ninja && cmake --build engine/build
engine/build/noti_tests
engine/build/predictor_bench               # model × horizon table
engine/build/predictor_bench --by-kind     # per stroke type
engine/build/engine_bench --seconds 5      # real-time 240 Hz replay
(cd engine/build && ctest)                 # unit tests + predictor quality gate + engine cost gate
```

Sanitizers: `-DNOTI_TSAN=ON` or `-DNOTI_SANITIZE=ON` (ASan+UBSan). The suite is clean under both (GCC 13).

Android (arm64, minSdk 33):

```sh
cmake -S engine -B engine/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 -DANDROID_STL=c++_static
cmake --build engine/build-android        # → libnoti_ink.so (~280 KB stripped, 16 KB-page aligned)
```

From Gradle, point `externalNativeBuild.cmake.path` at `engine/CMakeLists.txt` and add
`engine/android/kotlin` as a source directory. `@CriticalNative` needs `compileSdk >= 34`.

## Verification status

| | Status |
|---|---|
| Portable core (ring, wake, predictor, geometry, engine, probe, store) | Unit-tested, including pixel-exact invariants against the reference renderer: the predicted tail leaves no residue, wet = dry, and cancel erases. Clean under TSan, ASan+UBSan, clang and GCC. |
| Android layer (`src/android`) | **Compiles warning-free with NDK r27c for arm64 / API 33. Not yet run on a device.** |
| Kotlin binding | **Not compiled here (no Android SDK in this environment).** |

The Phase 0 device checks from the plan apply to the Android layer before relying on it:

1. Ink orientation: if strokes appear vertically mirrored, set `GlRendererOptions::flip_y`.
2. `dumpsys SurfaceFlinger`: the `noti-wet` layer must be **DEVICE** (overlay) composited and the panel must stay at 144 Hz.
   If it's DEVICE, set `resubmit_front_buffer = false`. That removes a rate-limited `setBuffer` transaction that
   exists only to cover CLIENT (GPU) composition.
3. `GlRendererOptions::gpu_timing = true` fills `submit_to_gpu` in the probe (adds a native fence per frame: debug only).
