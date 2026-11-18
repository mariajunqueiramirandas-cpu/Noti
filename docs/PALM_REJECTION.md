# Palm rejection

Every finger contact on the canvas goes through a native filter (`engine/include/noti/palm_filter.h`) before it can
do anything. Only the pen and confirmed deliberate touches get through. Palms never move the page, never trigger a
gesture and never leave ink.

## What it protects

1. **Pen strokes.** The ink window routes each pointer by its tool type, wherever it sits in the event. Before
   this, only pointer 0 was checked. A palm that landed first became pointer 0, and the pen's stroke was handed to
   the finger handler and never drawn.
2. **Gestures.** Pan, pinch-zoom, swipe to turn a PDF page, and the two-finger double tap only see confirmed
   touches. A palm plus a pinky tapping twice doesn't count as a two-finger double tap.
3. **Finger ink** (Settings → Writing → Draw with finger). Palms never leave a mark.

## How a contact is decided

Each contact carries a score: the log-odds that it's a palm rather than a touch. The score is the sum of
independent pieces of evidence, each a log-likelihood ratio. It's re-evaluated on every sample and on a 40 ms
timer, and the contact is decided as soon as the score leaves the band between −2 and +3. This is Wald's sequential
probability ratio test: the shortest average wait for a given error rate.

| Layer | Evidence |
|---|---|
| L0 system | `TOOL_TYPE_PALM`, or a pointer the platform cancelled (`FLAG_CANCELED`) → palm, final |
| L1 pen | Pen touching (+5.5) or hovering (+5.0), or lifted a moment ago (+4.5, fading over a grace window learned from your own pauses between strokes); the pen going down while the contact is on the glass (+3) |
| L2 hand model | Where your palm rests relative to the nib: a two-component (right hand / left hand) Gaussian model of the palm's offset, learned online and rotated with the pen's azimuth when the pen reports one. Gives a palm zone before the pen is in hover range, and strong "not the writing hand" evidence (down to −4.5) for contacts the hand can't reach while the nib's position is known |
| L2 coupling | The writing hand moves rigidly with the pen. A contact that travels on its own while the tracked nib stays put is not that hand (up to −2.5) |
| L3 contact | Peak size against a learned fingertip/palm boundary. Palms roll on over ~25 ms, so small size only counts as touch evidence after that window. Also elongation, growth over the first 25 ms, purposeful versus resting motion, clean taps, and the bezel edge |
| L4 clusters | A decided palm makes nearby contacts more likely to belong to the writing hand. Two fingertips landing close together are evidence of a gesture. When contact sizes are missing, opposing movement outside the likely palm zone can confirm a pinch while the pen hovers. |
| L5 commitment | Confirmed gestures replay held samples in time order. Small, round contacts can draw immediately. Ambiguous contacts wait for deliberate motion or a tap, then draw from the landing position. Finger ink can roll back for 80 ms after lift. Starting another deliberate stroke during that window keeps the preceding stroke. |
| L6 learning | Fingertip and palm sizes, the hand model, handedness and the grace window adapt from confident decisions only (with the hand-model term itself excluded, so it can't reinforce itself), inside fixed bounds. The model is saved between sessions |

A contact still undecided when it lifts counts as a tap only with real evidence for it (score < −1). When in
doubt, the filter drops it: a missed tap costs less than a stray mark or a jump.

## Measuring it

`palm_bench` replays synthetic pen + multi-touch sessions with ground-truth labels. There are 20 scenarios, each a
known failure mode:

- the palm lands before the pen, cold and warm;
- the palm lands after the pen;
- palm + pinky double tap, while hovering and after the pen leaves;
- the palm re-lands mid-stroke, stays after the pen leaves, or slides along the line;
- the other hand pinches while the pen hovers, or pans after writing;
- the writing hand's own finger scrolls;
- a thumb grips the bezel;
- idle pans, taps and two-finger double taps;
- four finger-ink cases.

Each runs × seeds × both hands × with and without contact-size reporting (the worst case for a touchscreen):

```
palm_bench --seeds 20
  TOTAL (screen reports contact size)  palms 880  touches 560  strokes 80 | FA 0  FR 0  flash 0 | latency p50 33 ms  p95 33 ms
  TOTAL (no contact size)              palms 880  touches 560  strokes 80 | FA 0  FR 0  flash 0 | latency p50 70 ms  p95 90 ms
```

FA = palms that became a gesture or kept ink. FR = deliberate touches or strokes that were lost. Flash counts palms
that briefly drew tentative finger ink before rollback. `palm_bench --gate`
runs in ctest and fails on any false accept, any false reject when sizes are reported, more than 5 % false rejects
without sizes, or p95 confirmation above 60 ms.

**These scenarios are a model of real hands, written alongside the filter.** Passing them shows that the filter
handles each failure mode as designed. It doesn't prove the numbers carry over to your hand on the Pad 7. Get real
numbers like this:

1. Settings → Palm rejection → Record a test session. Record a few minutes of **Writing hand** (write normally,
   palm resting; every finger contact is labelled palm), then **Deliberate touches** (no pen; tap, pan, pinch;
   every contact is labelled touch).
2. `adb pull /sdcard/Android/data/app.noti/files/palm-lab`
3. `palm_bench --csv palm-lab/*.csv` prints every contact's verdict, timing and score, plus the totals.

The same page shows live counters and what the filter has learned: writing hand, grace window, size boundary.

## Things to check on the device

- Whether the Focus Pen and the touchscreen are one input device or two. Routing handles both; with two, contact
  ids include the device id.
- Whether the touchscreen reports `touchMajor`. Without it, the filter falls back to pen, hand-model, motion and
  cluster evidence. It still had no misses in the scenarios, but confirmation is slower.
- Whether the pen reports `AXIS_ORIENTATION`. Without it, the hand model stays in screen coordinates. With it, the
  filter tries the azimuth frame both ways, and no rotation, and keeps whichever fits your palms best.
