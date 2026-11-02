# Noti — Low-Latency Ink Engine Plan

**Target hardware:** Xiaomi Pad 7 + Xiaomi Focus Pen (1st gen)
**Goal:** the lowest *measured* pen-to-pixel latency of any note/PDF app on this device, so writing feels like ink on paper.

---

## 1. What we are working with

### Xiaomi Pad 7

| Item | Spec | Why it matters for ink |
|---|---|---|
| Display | 11.2" LCD, 3200×2136, ~345 ppi, 12-bit, DCI-P3 | LCD pixel response adds a few ms we cannot remove. The high ppi means anti-aliasing quality is visible. |
| Refresh | Up to 144 Hz, adaptive with 7 fixed steps from 30 to 144 Hz | One frame is 6.94 ms at 144 Hz and 16.7 ms at 60 Hz. **We must keep the panel at 144 Hz while the pen is near the screen.** |
| Touch sampling | Up to 360 Hz (finger), **240 Hz (pen)** | A new pen sample arrives every ~4.17 ms, about 1.67 samples per display frame. |
| SoC | Snapdragon 7+ Gen 3 (4 nm, octa-core up to 2.8 GHz, Adreno 732) | Far more power than ink needs. The limit is the *pipeline*, not raw compute. |
| RAM | 8 / 12 GB | Room for a large PDF tile cache. |
| OS | Shipped with HyperOS 2 (Android 15). HyperOS 3 (Android 16) rolled out from Nov 2025. | We can use every modern API: front-buffer `AHardwareBuffer` (API 33), `MotionPredictor` (API 34), ADPF power-efficiency hints (API 35). |

### Xiaomi Focus Pen

| Item | Spec | Notes |
|---|---|---|
| Pressure | 8192 levels | Arrives as `MotionEvent.AXIS_PRESSURE`. We need to measure the real resolution and curve on the device. |
| Sampling | 240 Hz | Same as the tablet's pen sampling rate. |
| Latency claim | "3 ms", from Xiaomi's internal lab | Xiaomi says this **applies only to Xiaomi's own apps; third-party apps may vary**. The definition is not published and is almost certainly not full motion-to-photon (see §2). |
| Tilt | Reported as supported, but inconsistent between apps (Parkablogs review of the same pen on the Pad Mini) | We need to check `AXIS_TILT` and `AXIS_ORIENTATION` on the device. |
| Buttons | 2 buttons, 3 functions | HyperOS may intercept them for screenshots or the "spotlight" feature. We need to check whether we get `BUTTON_STYLUS_PRIMARY` / `SECONDARY`. |
| Hover | Expected (active pen) | Hover is the key to pre-warming the pipeline (§4.6). |

> ⚠️ **Known hazard (GSMArena, Pad 7 review):** in automatic refresh mode, using the stylus *dropped some apps from 90 Hz to 60 Hz*. If that happens to us, we lose up to ~10 ms per frame. Locking the refresh rate is a must-have item, not a nice-to-have (§4.4).

---

## 2. The physics: where pen latency comes from

Motion-to-photon latency is the sum of the following stages. The numbers are estimates until we measure them in Phase 0.

| # | Stage | Typical Android app | Our target | How |
|---|---|---|---|---|
| 1 | Digitizer scan + pen report (240 Hz) | 2–4 ms | 2–4 ms | Hardware. We can't change it, but **prediction hides it**. |
| 2 | Kernel → `InputReader` → `InputDispatcher` | 1–2 ms | 1–2 ms | OS. |
| 3 | Waiting for vsync batching in the app | 0–7 ms (60 Hz: 0–17 ms) | **~0 ms** | `requestUnbufferedDispatch()`: each sample is delivered as soon as it arrives. |
| 4 | UI thread busy (Compose, GC, layout) | 0–20+ ms (spikes) | **~0 ms** | Ink never waits on the UI thread. Native engine, no allocations on the hot path. |
| 5 | Render | 1–5 ms | **< 0.5 ms** | Incremental scissored draw of only the new segment. |
| 6 | BufferQueue + SurfaceFlinger composition (2–3 frames) | 14–21 ms at 144 Hz (33–50 ms at 60 Hz) | **0 ms** | **Front-buffer rendering**: we draw into the buffer that is being scanned out. |
| 7 | Scan-out to the pixel's row | 0–6.9 ms (avg 3.5) | 0–6.9 ms (avg 3.5) | Not quantized to vsync with a front buffer: if the beam hasn't reached the row yet, the ink shows up *this* refresh. |
| 8 | LCD pixel response | ~4–8 ms | ~4–8 ms | Hardware. Prediction hides part of it. |
| | **Total (measured)** | **~30–60 ms** | **~10–16 ms** | |
| | **Perceived (with prediction)** | — | **≈ 0–5 ms** | Prediction draws ink where the nib *will be*, ~8–12 ms ahead. |

For reference, Apple says the Pencil on iPad Pro reaches about 9 ms. That is the bar we want to beat in *perceived* latency on this device.

**Key insight:** stages 3, 4 and 6 are where typical apps lose 20–50 ms. They are all software, so they are all ours to remove. Stages 1, 7 and 8 are physics, and we can only *hide* them with prediction. Winning means: (a) remove every software stage, and (b) build the most accurate predictor for *this specific pen*.

---

## 3. Architecture overview

```
┌─────────────────────────────── App process ───────────────────────────────┐
│                                                                           │
│  Kotlin / Jetpack Compose UI  (toolbars, library, settings — never on ink path)
│        │ commands (tool, color, page)        ▲ events (stroke committed)  │
│        ▼                                     │                            │
│  ┌──────────────────────── Native engine (C++20, NDK) ─────────────────┐  │
│  │                                                                     │  │
│  │  Input thread ──► lock-free SPSC ring ──► Ink render thread         │  │
│  │  (unbuffered,       (raw samples:          │  ├─ predictor          │  │
│  │   all historical    x,y,p,tilt,t)          │  ├─ stroke geometry    │  │
│  │   samples)                                 │  └─ FRONT BUFFER draw  │  │
│  │                                            │      (scissored)       │  │
│  │                                            ▼                        │  │
│  │                               Stroke store (append-only, mmap WAL)  │  │
│  │                                            │                        │  │
│  │  Page compositor thread (144 Hz, multi-buffered SurfaceControl)     │  │
│  │    ├─ PDF tile textures (PDFium workers, LRU GPU cache)             │  │
│  │    ├─ "dry" ink tiles (vector → raster cache)                       │  │
│  │    └─ pan / zoom transforms                                         │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
          │ SurfaceControl layers: [ page (multi-buffered) ] [ ink (front buffer) ]
          ▼
   SurfaceFlinger / HWC (both must be DEVICE-composited overlays) ─► 144 Hz LCD
```

Two surface layers:

1. **Wet layer (front-buffered):** a single `AHardwareBuffer` with `USAGE_FRONT_BUFFER`, attached to its own `SurfaceControl`. The ink thread draws new stroke segments into it *while it is on screen*. Only small regions change, so tearing is invisible.
2. **Dry layer (multi-buffered):** the page (PDF tiles + committed ink), rendered normally at 144 Hz. On pen-up (or every N ms during a long stroke) the wet stroke is committed to the dry layer and the wet buffer is cleared in the same transaction, so there's no flicker.

This is the same model as `androidx.graphics.lowlatency.GLFrontBufferedRenderer`. We use that library to prototype, then replace it with our own native implementation so we control threading, synchronization, the prediction overlay and redraw strategy.

---

## 4. Engine techniques, one by one

### 4.1 Input: get every sample, the moment it exists
- Call `view.requestUnbufferedDispatch(InputDevice.SOURCE_STYLUS)` on `ACTION_DOWN`. This turns off vsync batching (stage 3) for the stroke.
- Read **all** `getHistorical*` samples in each event. Never use only the latest point.
- Use event timestamps (`getEventTimeNanos`, API 34) for prediction and velocity, not arrival time.
- Pass samples straight to a native **lock-free SPSC ring buffer**. The Java/Kotlin handler does nothing else: no allocations, no logging.
- **Experiment:** compare View input against a native input queue (`AInputQueue` on a looper owned by our own thread, as `NativeActivity`/`GameActivity` do) so the UI thread is not in the path at all. We keep the faster option.
- **Palm rejection:** while the pen is hovering or touching, ignore `TOOL_TYPE_FINGER`. Honour `ACTION_CANCEL` and `FLAG_CANCELED` (Android 13+), and roll back a stroke the system cancels.

### 4.2 Rendering: draw only what changed
- Native **OpenGL ES 3.2 via EGL** on an `AHardwareBuffer`. Adreno's GLES drivers are mature, and for a draw this small, GLES and Vulkan have the same latency. Vulkan (`VK_ANDROID_external_memory_android_hardware_buffer`) is an option for later.
- Strokes are drawn as **analytic signed-distance-field capsules** (one instanced quad per segment, with pressure-interpolated radius) in the fragment shader. This gives perfect anti-aliasing at any zoom level, no tessellation on the CPU, and a single draw call per input event.
- **Scissor** every front-buffer draw to the bounding box of (old prediction ∪ new segment ∪ new prediction). This touches a few thousand pixels, not 6.8 million.
- **Shaders are precompiled and pipelines warmed at startup** so the first stroke of the day isn't slower than the rest.
- The render thread uses `THREAD_PRIORITY_URGENT_DISPLAY` plus an **ADPF `PerformanceHintManager` session** with a ~1 ms target, so the governor keeps it on fast cores without us having to pin cores.

### 4.3 Prediction: hide the physics (this is where we beat everyone)
- **Baseline:** `androidx.input.motionprediction.MotionEventPredictor`. It uses the system `MotionPredictor` (API 34) where available, and a Kalman filter otherwise.
- **Our own predictor, tuned for this pen:**
  1. Record thousands of real strokes on the Pad 7 + Focus Pen with full timestamps (handwriting, diagrams, fast annotations, signatures).
  2. Train and compare offline: tuned Kalman, polynomial extrapolation, and a **tiny on-device model** (small MLP/GRU over the last ~8 deltas, < 50 k params, < 50 µs inference on CPU).
  3. Measure error against horizon. Set the **horizon from measured software latency (§5.1) plus the feel-calibrated slider value (§5.4)**, not a guess.
  4. **Confidence gating:** shorten or stop prediction on sharp curvature, sudden deceleration and pen-lift approach (falling pressure), which is where predictors overshoot and look "wrong".
- The predicted tail is drawn into the front buffer each event and **erased and replaced** by the next event. Erasing means restoring the scissor rect from a cached copy of the committed content, which is a cheap blit.
- Rule: a wrong prediction is worse than latency. We tune for *zero visible overshoot* first, then extend the horizon.

### 4.4 Display: never let the panel slow down
- While the pen is hovering or touching, request 144 Hz explicitly: `Surface.setFrameRate(144f, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` on both layers, plus `WindowManager.LayoutParams.preferredDisplayModeId` set to the 144 Hz mode.
- Check with `adb shell dumpsys SurfaceFlinger` that the mode actually changes. If HyperOS overrides app requests (its per-app refresh policy), document the fix for users ("Settings → Display → Refresh rate → Custom / High") and contact Xiaomi.
- Confirm both layers are **HWC `DEVICE` composition (overlay)**, not `CLIENT` (GPU). If SurfaceFlinger composes our front buffer on the GPU, the front-buffer benefit is gone. This is the **#1 go/no-go check** in Phase 0.

### 4.5 Zero jank, zero GC
- The whole ink path is C++. Kotlin only handles UI chrome.
- No heap allocations after pen-down: preallocated rings, vertex buffers and stroke arenas.
- Saving never blocks: points go to an **append-only memory-mapped write-ahead log**. Compaction and PDF export run on a background thread.
- While a stroke is active, the UI is frozen: no recomposition, no animations, no toolbar updates.

### 4.6 Hover pre-warming (a trick almost no one uses)
When `ACTION_HOVER_ENTER` arrives (pen ~1 cm above the glass, tens of ms before contact):
- lock 144 Hz,
- open or boost the ADPF session,
- wake the render thread and make the EGL context current,
- prefetch PDF tiles under the nib at the current zoom level.

So the first sample of a stroke never pays for cold caches, a down-clocked CPU or a 60 Hz panel. Hover also drives a nib-shaped cursor preview for each tool.

### 4.7 PDF: fast, and kept away from the ink path
- **PDFium** (BSD-3/Apache-2.0, the Chrome engine, and also what Android's own `PdfRenderer` uses) built in our NDK library. We avoid MuPDF because it's **AGPL**, which is incompatible with a closed-source commercial app unless we buy a license.
- **Tiled rendering**: 512×512 tiles at discrete zoom levels, rendered by a worker pool on the efficiency cores, then uploaded as GPU textures into an LRU cache sized to RAM. A low-res page thumbnail is always shown while sharp tiles load, so we never show a blank page.
- Pan and zoom only change transforms on the compositor thread, so they run at 144 Hz independent of PDFium.
- Annotations are stored in our own vector format (full fidelity: pressure, tilt, timing). Export writes standard PDF `/Ink` annotations (editable in other readers) or flattened content.

### 4.8 "Feels like paper" beyond latency
- **Pressure curve calibration:** measure the raw pressure response of the Focus Pen and ship a per-device curve so light strokes don't drop out and heavy strokes don't saturate.
- **Minimal smoothing:** smoothing adds lag. We use a lightly tuned One-Euro filter *only* on the predicted tail, and keep the committed stroke faithful to the raw samples (with sub-pixel resolution).
- **Nib geometry:** model pen tip width against tilt if tilt is reliable on this pen.
- Optional texture brushes (pencil grain) done in the shader at no latency cost.

---

## 5. Measurement: you can't beat what you can't measure

**No camera rig, no manual lab work.** Everything here is either built into the app or runs with a single `adb` command.

This works because of how the latency budget in §2 splits:
- Stages 1 and 8 (digitizer and LCD pixel response) are **the same for every app on this device**. They don't change any comparison, so we don't need to measure them; we only use a fixed estimate when choosing the prediction horizon.
- Stages 2–7 are **software**, and the app can timestamp them itself.

### 5.1 Built-in latency probe (debug builds)
For every pen sample, the engine records:

| Timestamp | Source |
|---|---|
| `t_in`: when the hardware produced the sample | `MotionEvent.getEventTimeNanos()` (kernel evdev timestamp, API 34) |
| `t_recv`: when our code received it | `System.nanoTime()` in the input handler |
| `t_submit`: when the draw was submitted to the GPU | Engine clock |
| `t_gpu`: when the GPU finished | Native fence fd from `EGL_ANDROID_native_fence_sync`, polled on a watcher thread |
| `t_visible`: when that row of pixels was scanned out | **Front buffer:** next scan of the stroke's row after `t_gpu`, from vsync timestamps (`Choreographer`) and the display period. **Dry layer:** the actual present time from `ASurfaceTransactionStats` present fences. |

`t_visible − t_in` is our software pen-to-pixel latency (stages 2–7). It is shown live in a small debug overlay as p50 and p99, plus prediction error and dropped samples. It is also written to a CSV that can be pulled with `adb pull`.

### 5.2 Hands-free automated runs
- **Synthetic strokes:** `adb shell input stylus motionevent DOWN|MOVE|UP x y` injects stylus events through the real `InputDispatcher`. A script replays recorded handwriting into the app and reports the latency distribution. This needs no human and no pen.
- **Raw digitizer facts:** `adb shell getevent -lt` while writing for 10 seconds tells us the true pen report rate, the pressure range and whether tilt axes exist.
- **CI performance gate:** a recorded-stroke replay benchmark fails the build if p99 sample-to-submit time grows above 0.5 ms.

### 5.3 Competitors without a camera
A system-wide **Perfetto** trace (`adb shell perfetto`, works on retail builds) while the same synthetic strokes are injected into each app:
- **SurfaceFlinger FrameTimeline** gives the real present time of each frame for normally buffered apps (most competitors). Present time minus injection time is their software latency, measured the same way as ours.
- The same trace shows each app's layer **composition type** and refresh rate, which reveals whether it uses a front buffer and whether it keeps 144 Hz.
- Android's input-event tracing can add per-event dispatch times. On retail builds parts of it may be redacted; if so, we fall back to injection timestamps.
- **Limit:** apps that also draw to a front buffer (probably Xiaomi Notes) don't produce a frame per stroke, so FrameTimeline can't time them. For those we compare by feel (§5.4).

**Competitors:** Xiaomi Notes (the "3 ms" reference), Goodnotes, Notewise, Squid, Noteshelf, Nebo, OneNote, Xodo, Flexcil, and any app using `androidx.ink`.

### 5.4 Calibrating by feel (about 2 minutes)
Some things only your hand and eyes can judge, but they don't need equipment:
- **Prediction slider:** a debug setting for prediction horizon (0–20 ms). Write a few lines at different speeds and stop at the value where the ink stays glued to the nib *without* ever shooting past it. That value, plus the software latency from §5.1, calibrates the predictor.
- **Blind A/B toggle:** a debug button randomly switches between two engine configurations (for example, our predictor vs. androidx's) without saying which is which. You pick the one that feels better; after ~10 rounds the app tells you whether your preference was real or chance.

### 5.5 Optional: one slow-motion video
If you ever want an absolute number that includes the hardware stages, prop any phone that records 240 fps slow-motion over the tablet and draw a few fast lines for 10 seconds. A script (not a person) then analyses the clip. It's optional and nothing in the roadmap depends on it.

---

## 6. Phased roadmap

### Phase 0 — Feasibility and lab (1–2 weeks) · **go/no-go**
- [ ] `getevent` dump: true pen report rate, pressure resolution, tilt and orientation axes, hover range, button events.
- [ ] Minimal app using `androidx.graphics` `GLFrontBufferedRenderer` + `MotionEventPredictor` + unbuffered dispatch.
- [ ] Confirm in `dumpsys SurfaceFlinger` that the front buffer layer is **DEVICE-composited** and the panel stays at 144 Hz.
- [ ] Built-in latency probe (§5.1) and synthetic-stroke script (§5.2).
- [ ] Perfetto traces of our prototype, Xiaomi Notes and 3 competitors with the same injected strokes (§5.3): software latency, composition type, refresh rate.
- [ ] 2-minute feel check against Xiaomi Notes (§5.4).
- **Exit criterion:** front buffer is overlay-composited, the panel stays at 144 Hz, the prototype's software latency is at or below every competitor that FrameTimeline can measure, and it feels at least as good as Xiaomi Notes. If the front buffer isn't overlay-composited, escalate to Xiaomi developer relations before Phase 1.

### Phase 1 — Native ink engine (3–5 weeks)
- [ ] C++ engine: SPSC input ring, EGL front-buffer `AHardwareBuffer` + `ASurfaceControl` (replacing androidx), SDF capsule shader, scissored redraw.
- [ ] Wet → dry commit with an atomic `ASurfaceTransaction`.
- [ ] ADPF session, thread priorities, hover pre-warming, 144 Hz lock.
- [ ] Native vs. View input path experiment.
- **Exit criterion:** p99 sample-to-submit < 1 ms; p99 software pen-to-pixel latency (§5.1) at least 30% lower than the best competitor measured in §5.3.

### Phase 2 — Custom predictor (2–4 weeks, in parallel with Phase 1)
- [ ] Stroke recorder and dataset (a few hundred thousand samples).
- [ ] Offline harness: Kalman vs. polynomial vs. tiny NN; error against horizon, and overshoot rate.
- [ ] Ship the winner with confidence gating; calibrate the horizon from Phase 0 measurements.
- **Exit criterion:** in blind A/B tests, users can't tell the prediction is there (no visible overshoot), and perceived lag is lower than with the androidx predictor.

### Phase 3 — PDF and notebook (4–6 weeks)
- [ ] PDFium integration, tiled renderer, GPU tile cache, 144 Hz pan and zoom.
- [ ] Notebooks, page templates, infinite canvas.
- [ ] Vector stroke store + WAL, undo/redo, eraser (stroke and pixel), lasso.
- [ ] PDF export (`/Ink` annotations + flattened).

### Phase 4 — Polish and "paper feel" (ongoing)
- [ ] Pressure curve calibration UI, nib/tilt modelling, texture brushes.
- [ ] Pen button mapping (if HyperOS passes the events through).
- [ ] Battery: drop to 30 Hz and release ADPF when the pen is away, so we still get full speed when the pen is near.

---

## 7. Risks and how we handle them

| Risk | Impact | Mitigation |
|---|---|---|
| HWC composites the front-buffer layer on the GPU (CLIENT) | Lose ~2 frames of improvement | Phase 0 gate. Try different buffer formats or sizes; contact Xiaomi/Qualcomm. As a fallback, use the best possible multi-buffered pipeline with 144 Hz + prediction. |
| HyperOS forces 60/90 Hz while the pen is in use | +3–10 ms | Explicit mode request; user guidance; Xiaomi developer relations. |
| Xiaomi's 3 ms path uses private system APIs | Their own app may stay ahead in raw numbers | Measure, don't assume. Their definition of "3 ms" isn't public. Our edge is pipeline + prediction + PDF performance together. Ask Xiaomi whether a partner API exists. |
| Prediction overshoot looks "wrong" | Feels worse than lag | Confidence gating, conservative horizon, blind user tests. |
| Tilt / buttons unreliable on Focus Pen | Missing features | Feature-detect at runtime and degrade gracefully. |
| Front-buffer tearing visible on large redraws | Visual artefacts | Front buffer only for small wet strokes; all large redraws (pan/zoom/erase) go through the multi-buffered layer. |
| Over-fitting to one device | Hard to support other tablets later | The engine has a device-profile layer (sampling rate, pressure curve, refresh modes); the Pad 7 profile is just the first one. |

---

## 8. Why this beats existing apps

1. **Native, allocation-free ink path.** Most apps go through the UI thread and Canvas/Skia with a normal BufferQueue. We skip both.
2. **Front buffer + unbuffered input + 144 Hz lock + hover pre-warming.** Each one is documented separately; few apps combine all four and verify each with a profiler.
3. **A predictor trained on this exact pen**, with a horizon calibrated from measured software latency and a quick feel check, rather than a generic one.
4. **PDF rendering completely separate from ink**, so a heavy PDF never adds a millisecond to a stroke.
5. **We measure everything automatically, including competitors, with the same injected strokes and traces**, and treat latency as a CI-gated metric, not a marketing claim.

---

## Sources

- [Xiaomi Pad 7 specs (mi.com)](https://www.mi.com/global/product/xiaomi-pad-7/specs/) · [Malaysia overview](https://www.mi.com/my/xiaomi-pad-7/)
- [Notebookcheck: Xiaomi Pad 7](https://www.notebookcheck.net/Xiaomi-Pad-7.919710.0.html) · [Smartprix: Pad 7 India launch](https://www.smartprix.com/bytes/xiaomi-pad-7-released-in-india-specs-price/) · [T2 review](https://t2online.in/tech/tech-reviews/review-xiaomi-pad-7-is-an-all-round-package-of-hardware-power-and-software-magic/1311852)
- [GSMArena Pad 7 hands-on (stylus dropping refresh rate to 60 Hz)](https://m.gsmarena.com/xiaomi_pad_7_handson-review-2792p3.php)
- [Xiaomi Focus Pen specs (UK)](https://www.mi.com/uk/product/xiaomi-focus-pen/specs/) · [Pad 7 Focus Pen page (3 ms, Xiaomi apps only)](https://www.mi.com/np/product/xiaomi-pad-7-focus-pen/) · [Focus Pen FAQ](https://www.mi.com/uk/product/xiaomi-focus-pen/faq/)
- [Parkablogs: Focus Pen tilt behaviour](https://www.parkablogs.com/index.php/node/15833)
- [Notebookcheck: HyperOS 3 rolling out to Pad 7](https://www.notebookcheck.net/Stable-HyperOS-3-rolls-out-to-Xiaomi-Pad-7-with-more-Xiaomi-and-Redmi-devices-to-follow-later-this-month.1160353.0.html)
- [Android Developers: stylus low latency](https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982)
- [GLFrontBufferedRenderer](https://developer.android.com/reference/androidx/graphics/lowlatency/GLFrontBufferedRenderer) · [CanvasFrontBufferedRenderer](https://developer.android.com/reference/androidx/graphics/lowlatency/CanvasFrontBufferedRenderer) · [androidx.graphics.lowlatency](https://developer.android.com/reference/androidx/graphics/lowlatency/package-summary)
- [MotionPredictor](https://developer.android.com/reference/android/view/MotionPredictor) · [MotionEventPredictor](https://developer.android.com/reference/androidx/input/motionprediction/MotionEventPredictor)
- [androidx.ink releases](https://developer.android.com/jetpack/androidx/releases/ink)
- [Codelab: advanced stylus support](https://developer.android.com/codelabs/large-screens/advanced-stylus-support)
- [ADPF setPreferPowerEfficiency (API 35)](https://learn.microsoft.com/en-us/dotnet/api/android.os.performancehintmanager.session.setpreferpowerefficiency?view=net-android-37.0)
- [MuPDF overview (licensing)](https://mupdf.readthedocs.io/en/1.27.1/guide/what-is-mupdf.html) · [Syncfusion: PDF engine comparison](https://www.syncfusion.com/blogs/post/pdf-rendering-engines-comparison)
