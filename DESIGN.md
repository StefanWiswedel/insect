# Biomon Insect Trap — Android Capture App

Native Android capture app for autonomous insect monitoring. Deployed on a
Pixel 6a on a stand, in shade behind a stick pile, bait in frame, screen off,
~9 hours unattended.

It captures full-resolution stills of insects visiting bait. **It does not
classify.** On-device insect classification is not a solved problem the way
on-device bird audio is — there is no Perch equivalent and no labelled dataset
for this site. The first deployments exist to *produce* that dataset. The
trigger stage is architected so a TFLite model can be dropped in later
(`MotionTrigger` already produces per-blob crops as metadata); none is shipped.

---

## 0. Phase 0 — corpus checks and self-instrumentation

### 0.1 Crop scale from the existing corpus — **PENDING DATA**

The result is not recorded here because **`analysis/results.db` is not in this
repository**. This repo contained only a README at the start of this work; the
sessions `280726_1` and `290726_0` live on the analysis laptop. Numbers are not
invented here — an invented baseline crop size is worse than none, because it
would silently set the input resolution of a future classifier.

The query is written and ready to run: `analysis/phase0_crop_scale.py`. It is
schema-tolerant (it discovers the detection table and its bbox columns rather
than assuming a layout), so it should run against the real database unmodified:

```
python3 analysis/phase0_crop_scale.py /path/to/analysis/results.db \
    --sessions 280726_1 290726_0
```

Paste its output block into this section. Fill in:

| Metric | Median | p5–p95 | n |
| --- | --- | --- | --- |
| crop width (px) | _pending_ | _pending_ | _pending_ |
| crop height (px) | _pending_ | _pending_ | _pending_ |
| longest edge (px) | _pending_ | _pending_ | _pending_ |

**Nothing in this app is blocked on it.** The number sets the baseline input
size for a future classifier, and classification is explicitly out of scope for
this version. It is a documentation debt, not a design dependency.

### 0.2 Per-region sensitivity calibration — **PENDING DATA, design already settled**

Same situation: measuring mean luminance and background-subtraction residual
noise across a 3×3 grid needs a session's frames, which are on the laptop.
`analysis/phase0_region_calibration.py` does it, using the *same EMA background
the phone runs* rather than the laptop's rolling median, so the numbers describe
the on-device model:

```
python3 analysis/phase0_region_calibration.py /path/to/session/frames \
    --json docs/region_calibration.json
```

It prints the 3×3 luminance and residual-sigma tables, the corner/centre ratios,
and a verdict.

**The measurement calibrates constants; it does not decide the architecture.**
§3.2 mandates a locally normalised threshold regardless of what the spread turns
out to be, so implementation was not blocked on it. What the measurement will
tell us is whether the default `minContrastFraction` (0.03) and grid size are
right for this lens.

What *was* settled during implementation, because it changed the design:

- **A per-region threshold built only on noise statistics does not fix
  vignetting.** The analysis stream is downsampled 4× before differencing, which
  averages 16 pixels and cuts sensor noise by the same factor. On a daylight
  scene the noise term is then so small that whatever floor sits underneath it is
  what actually decides sensitivity. An absolute floor is a constant luma step,
  and a constant luma step in a corner at ~50% of centre brightness demands
  roughly twice the reflectance contrast. The bias survives per-region noise
  normalisation untouched.
- **So the floor is a fraction of local brightness, not an absolute step**
  (`TriggerConfig.minContrastFraction`). Each region tracks mean background luma
  as well as residual mean and sigma; the threshold is
  `max(minContrastFraction × regionLuma, regionMean + k × regionSigma)`, clamped,
  and bilinearly interpolated between cell centres so there are no seams.
- **The runtime grid is 8×6, not 3×3.** Vignetting is radial; a 3×3 corner cell
  reaches a third of the way to the centre and averages over too wide a
  brightness range to correct the corner itself. 8×6 costs nothing measurable.
  3×3 remains the *reporting* grid for 0.2 because it is a readable table.

This is verified, not asserted:
`BackgroundModelTest.local normalisation equalises sensitivity across the frame`
measures the minimum reflectance contrast needed to trigger at centre versus at a
corner bait station, on a synthetic scene with radial vignetting and
shot-noise-dominated sensor noise. A global threshold needs >1.5× more contrast
in the corner; the local threshold gets the ratio under 1.2×. The test also fails
if the synthetic scene ever stops reproducing the bias, so it cannot quietly
degrade into a tautology.

### 0.3 The app measures its own power draw — **IMPLEMENTED**

There is no bench test. The first deployment is the experiment.

Every 60s, for the entire session, a `power` record goes into the manifest:
battery percentage, `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (µA), battery
temperature, voltage, charging state, free bytes, and derived watts. Flushed as
written, never buffered.

**Budget to check against:** the Pixel 6a battery is ~17Wh. Nine hours on
internal battery alone needs an average under ~1.89W. `analysis/power_report.py`
answers this from the manifest directly, by two independent methods: the device's
own current sensor, and the battery percentage actually consumed. The second
matters because a current sensor can report the charger rather than the load, and
because the deployment runs with a power bank attached — if the battery never
discharges, the run cannot answer the internal-battery question at all, and the
script says so rather than reporting a number that means nothing.

Deploy with the external power bank regardless. The app is designed to survive on
internal battery so a cable failure degrades the session rather than killing it.

Battery temperature is logged throughout to establish the thermal baseline: the
Tensor G1 runs warm and the device sits in a closed position outdoors.

---

## 1. What replaces what

The current insect pipeline captures JPEG stills at 3fps from a stock camera app
with the screen on, pulls frames to a laptop, and runs rolling-median background
subtraction plus a classifier over the crops. Three failures: the screen must
stay on, the battery dies well short of a full day, and continuous 3fps produces
~65GB for a 12-hour session.

This app addresses all three. The laptop pipeline downstream is unchanged — the
export path produces a directory it can consume without modification.

---

## 2. Module layout

```
core/    Pure Kotlin/JVM. Trigger pipeline, event assembly, guards, record
         formats, filename scheme. No Android dependencies, so all of it is
         unit-testable without a device. `./gradlew :core:test`
app/     Android. Camera2 session, foreground service, storage, UI. Requires
         the Android SDK; :app is only included in the build when one is present.
analysis/  Phase 0 scripts, run on the laptop against the existing corpus.
```

The split is deliberate: everything that can be wrong in a way a test can catch
lives in `:core`.

---

## 3. Architecture

### 3.1 Two-stream capture

Camera2, not CameraX — CameraX abstracts away the manual control this needs. Two
concurrent streams from **one configured session**:

- **Analysis stream:** `YUV_420_888` `ImageReader` at 640×480, 5fps. Runs
  continuously. Never written to disk.
- **Capture stream:** full-resolution JPEG `ImageReader`. Fires only on trigger.

The analysis stream is the always-on cost; the capture stream is near-free when
idle. That is the whole battery argument.

Both surfaces belong to the same session so the capture surface is already
allocated when a trigger fires. Configuring a session on demand would add
hundreds of milliseconds.

### 3.2 Trigger logic

**Recall-oriented.** Be generous, save anything plausible, let the laptop supply
precision. Better to save 500 frames of a wind-blown leaf than to miss the one
hoverfly.

Not a port of the laptop's rolling median — that holds too many frames in memory.
Instead: an EMA background on a downsampled grayscale of the analysis stream,
one frame of state, with an adaptive threshold and a minimum blob area to reject
sensor noise.

The threshold is locally normalised (see 0.2 above for why, and for the part of
the design that changed once the numbers were worked through).

**Capture on demand, not pre-roll.** On trigger, full-resolution JPEGs are
requested from the already-configured capture stream. The full-resolution stream
is *not* run continuously into a ring buffer: that would remove trigger latency
but keep the sensor reading out at full resolution all day, which is most of the
power saving this design exists to achieve.

The cost is ~200–300ms of pipeline and encode latency between motion onset and
the first written frame, so the arrival instant is not captured. Accepted: a
feeding insect stays in frame for seconds, and the questions this rig answers —
what visited, how long did it stay — survive losing the first quarter-second.

Sustained rate once triggered is 3–5fps. **If it falls below 3fps, flash write
throughput is the likely bottleneck, not JPEG encode** — check that first.

**Motion-adaptive capture rate.** Storage is the binding constraint: ~30GB free,
no expandable storage, ~4MB a frame at 12MP q85. A flat 3fps burns ~12MB/s while
triggered — about 40 minutes of total triggered capture for a whole day, and a
single insect feeding for five minutes would take over 3GB.

So the rate varies *within* an event:

| State | Rate |
| --- | --- |
| Blob moving | full, 3–5fps |
| Blob present but stationary (centroid displacement below threshold across consecutive analysis frames) | 1fps |
| Blob resumes movement | full rate, immediately, without waiting out the 1fps interval |

This preserves the whole behavioural sequence — arrival, movement, departure —
and samples sparsely only through stationary feeding, where the frames are
redundant. **Every rate change is logged** (`rate_change` records) so frame
intervals are reconstructable and a 1fps stretch is not mistaken downstream for
dropped frames.

**Post-roll.** Capture continues for a configurable interval after motion ceases
(default 5s), and continued motion extends the same event rather than starting a
new one.

**Frame cap.** A single event is capped (default 3000 frames) so one waving leaf
cannot fill the disk. Hitting the cap ends the event with reason `frame_cap` and
suppresses new events until motion actually stops — and is logged, because it is
a degradation.

### 3.3 The success paradox

The laptop pipeline has a known failure: an insect that sits still long enough is
absorbed into the background model and its residence time is silently truncated.
The same failure applies to an EMA background, and worse, because an EMA adapts
faster than a median.

Mitigation: **pixels currently flagged foreground are excluded from the
background update.** A stationary insect therefore stays visible indefinitely
rather than dissolving. `BackgroundModelTest.stationary target is not absorbed
into the background` holds a target still for 200 frames (40s at 5fps) and
asserts it is still detected on the last one; an unmasked EMA at α=0.02 loses it
in a couple of seconds.

That alone would pin the model forever if the scene genuinely changed — bait
moved, shadow shifted. So a pixel held foreground for `forcedRefreshSeconds`
(default 120) is folded into the background regardless, and **the forced refresh
is logged** (`forced_refresh` records) so a re-baselining event is visible in the
data rather than looking like an insect that vanished.

### 3.4 Locked capture parameters

Autofocus hunting is a background-model catastrophe and a battery drain. At
session start:

- **Focus locked** at the measured working distance (~20cm ⇒ ~5 dioptres),
  `CONTROL_AF_MODE_OFF` with an explicit `LENS_FOCUS_DISTANCE`.
- **White balance locked** (`CONTROL_AWB_LOCK`).
- **OIS disabled** (`LENS_OPTICAL_STABILIZATION_MODE_OFF`), and video
  stabilisation off.
- **Auto-exposure allowed** — light changes across 9 hours — but rate-limited,
  and **every exposure change is logged** (`exposure` records: exposure time,
  ISO, AE state, frame index) so background-model discontinuities can be
  correlated afterwards.

**JPEG quality floor is q85.** Below roughly q75, 8×8 blocking artefacts show up
in the laptop's background-subtraction residual and inflate blob counts. The
config constructor rejects anything lower.

### 3.5 Foreground service and screen-off

There is no existing Kotlin audio recorder in this repository to reuse — it was
not present. The bird station's conventions are followed from the brief rather
than from code.

Requirements: foreground service with a persistent notification, `WAKE_LOCK`
held (partial), screen fully off during capture, behaviour verified across a Doze
transition. **Non-negotiable #5: a one-hour screen-off test producing correctly
timestamped, correctly focused, correctly exposed frames is the milestone that
matters.** Everything else is secondary.

---

## 4. Session management

The phone captures full frames only. It does not crop and it does not classify.
Blob bounding boxes are recorded as metadata so cropping stays a lossless
laptop-side operation, re-runnable with different parameters later.

**Assume the session ends abruptly.** A dead battery, a pulled cable, an OS kill
— none give warning. Everything already captured must remain interpretable
without any clean shutdown having occurred.

- **Filenames are self-describing:**
  `<sessionId>_e<event:5>_<yyyyMMddTHHmmssSSS>Z_f<seq:5>.jpg`, e.g.
  `040826_0_e00007_20260804T112233456Z_f00042.jpg`. If the database is lost
  entirely, frames still group into events and order in time by filename alone.
  The database is a convenience, not a dependency. UTC, so a session that crosses
  a DST boundary or is analysed in another timezone still sorts correctly.
  Session IDs keep the laptop pipeline's existing `DDMMYY_N` convention.
- **SQLite in WAL mode, committed per event.** Never a transaction held open
  across events.
- **The manifest is append-only JSON Lines, written continuously.** Power
  samples, exposure changes, degradations and event records are flushed as they
  occur, never buffered to session end. A power cut costs at most the line being
  written.
- One session per deployment. The manifest records start time, device, lens,
  locked focus distance, exposure history, app version, and — when available —
  the reason the session ended. **The absence of a `session_end` record is itself
  informative:** it means the phone died or was killed outright.
- **Graceful shutdown at low battery** (~5%): stop capture, flush, close the
  manifest with an explicit termination reason, stop the service. A session that
  records why it ended is data; a session that simply stops is an evening of
  forensics.
- **Disk guard, tiered.** Below ~5GB free: drop the maximum capture rate to 1fps
  and raise the trigger threshold to become more selective, recording the
  degradation. Below ~1GB: stop capture, close the manifest with an explicit
  reason, **keep the service alive** so the session ends cleanly. A full disk
  never crashes the service, and never stops it without recording why.
- **Thermal backoff:** above 40°C battery temperature the analysis framerate
  halves, above 45°C it drops to 1fps, above 50°C capture stops but the service
  stays alive. Every transition is logged. Transitions carry 2°C of hysteresis so
  a temperature sitting on a boundary cannot flap and fill the manifest with
  noise. A degraded session is better than a lost one.
- **Export** produces a directory the existing laptop pipeline consumes without
  modification.

---

## 5. UI

Minimal, and not the point. One screen: preview with the trigger mask overlaid,
current focus distance, battery percentage and temperature, free space, events so
far this session, start/stop. A settings screen for thresholds and framerate.

`.claude/skills/biomon-ui/SKILL.md` is **not present** in this repository, so
there are no Biomon tokens to follow. Per the brief, the UI is therefore kept
plain and dark rather than inventing a second visual identity — a neutral dark
Material 3 scheme, no accent colour beyond a single state green/amber/red for
capture state, no decoration. If the skill later lands, restyling is confined to
the theme file.

---

## 6. Non-negotiables, and where each is enforced

| # | Rule | Where |
| --- | --- | --- |
| 1 | The app instruments itself: battery %, current draw, temperature at 60s into every manifest | `PowerLogger`, `GuardConfig.powerSampleIntervalMillis` |
| 2 | No classification in this version | Nothing in `:core` or `:app` loads a model |
| 3 | Nothing is silently dropped — every degradation surfaces in the manifest | `Degradation`, `ForcedRefresh`, `RateChanged`, `ErrorRecord`; `GuardEvaluator.lastTransitions` |
| 4 | The app survives its own failures — camera, storage and service errors resume the session | `CaptureService` restart path, `ErrorRecord(recovered=true)` |
| 5 | Screen-off capture verified end-to-end before adding features | one-hour screen-off test; see §3.5 |

---

## 7. Rig and bait notes (for the human)

Bait in four corners of the board, one station per corner. The board is planar
and parallel to the sensor about 20cm below it, so the whole surface lies in the
focal plane and all four corners are in focus. **This geometry is settled and is
not up for redesign.** The only positional cost is corner vignetting and slight
optical softness, handled in software by the local threshold in §3.2.

Four separated stations beats one central station: with visit rate as the
limiting factor, four independent stations multiply encounter opportunities, and
position becomes a variable you can analyse — which corner, which bait type, what
time of day. Consider varying bait between corners rather than replicating one,
so each session tests a comparison rather than accumulating counts.

Sugar water on sponge or cotton wool in a shallow matte dark dish: open liquid
reflects sky movement and ripples when insects land, both of which the background
model reads as motion. Fix every container down against wind. Overripe or
fermenting fruit skews the catch toward Drosophila, other flies and wasps; expect
wasps to dominate sugar in August.

Only the phone needs shade. The bait benefits from sun.
