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

#### Measured baseline — Pixel 6a, session 050826_0

The first 6a run. Clean, no camera errors, warm-up timestamps correct.

| | Pixel 6a (050826_0) | Pixel 9a (earlier run) |
| --- | --- | --- |
| Draw | 86% → 84% in 5m18s ≈ **3.8W** | ≈ 2.0W |
| Battery temperature, indoors | **32.1–33.5 °C** | 28.7–30.4 °C |
| Hours on ~17Wh internal | **~4.5h** at the central estimate | ~8.5h |

Treat the figure as a **2–5W range**, not 3.8W: six samples at 1% battery
granularity over five minutes means one percentage point either way moves the
answer a long way. The conclusion survives the error bars regardless — **the 6a
will not do nine hours on internal battery.** It is roughly double the 9a's
draw against a budget of 1.89W, so the external power bank is not a
belt-and-braces measure on this device, it is the power supply. Internal battery
is the degraded mode.

**This changes the thermal picture.** The design previously treated 40 °C
backoff as a hot-afternoon exception. On the 6a it is a realistic outdoor
scenario: the device idles 3.4 °C warmer than the 9a *indoors*, before adding
direct ambient, an enclosure, and nine hours of continuous camera. The gap from
the measured 33.5 °C indoor ceiling to the 40 °C reduce threshold is ~6.5 °C,
which a Copenhagen late-summer afternoon in a closed housing will cover. So the
reduced-rate path (§4) is a normal operating mode to be validated on a real
deployment, not a corner case — which is also why every duration in the trigger
is measured in seconds rather than frames (§3.7): at 2fps those constants have
to still mean what they say.

---

## 0.4 Defaults

Set explicitly, and these are the values the code ships with. Everything except
storage location is adjustable; focus is on the main screen, the rest in
settings.

| Setting | Default | Why |
| --- | --- | --- |
| Focus | **3.23 D (31 cm)** | Measured phone-to-board distance. Adjustable from the main screen, applied live, and every change is recorded. |
| Trigger warm-up | **30s** | The model must converge before the trigger arms. Six seconds was not enough: the first field run opened with a 184-frame event seven seconds in. Recorded as `warmup_start` / `warmup_end`. |
| Analysis stream | **640x480 @ 5fps** | The always-on cost. Downsampled 4x before differencing. |
| Capture stream | **full sensor resolution, JPEG q85** | q85 is a hard floor: below ~q75 the blocking artefacts inflate blob counts in the laptop's residual. |
| Capture rate, blob moving | **4fps** | Within the 3-5fps band. Below 3fps in the field, suspect flash write throughput before JPEG encode. |
| Capture rate, blob stationary | **1fps** | Feeding is where the frames are redundant. Returns to full rate on the next frame of movement. |
| Stationary speed threshold | **10 px/s** (downsampled) | A *speed*, not a per-frame displacement. See "Durations are durations" below. |
| Stationary dwell | **1.0s** | How long a blob stays slow before the rate drops. |
| Background time constant | **10s** | The EMA's convergence time, in seconds. The per-frame α is derived from it and the interval that actually elapsed. |
| Noise-statistics time constant | **20s** | Same, for the per-region residual mean and sigma. |
| Post-roll | **5s** | Continued motion extends the same event rather than starting a new one. |
| `minContrastFraction` | **0.03** | The threshold floor, as a fraction of local brightness. This is what cancels vignetting. |
| `illuminationAreaFraction` | **0.02** | Upper blob-area bound, as a fraction of frame area. Above it is the light changing, not a subject: capture suppressed, background re-baselined, `illumination_event` recorded. |
| Disk guard | **degrade below 5GB, stop below 1GB** | Degrade = 1fps ceiling and a 1.5x threshold. Stop keeps the service alive so the manifest closes. |
| Battery | **graceful stop at 5%** | Stop capture, flush, close the manifest with a reason, stop the service. |
| Thermal | **reduce analysis rate above 40C, stop above 45C** | Battery temperature. Reduce halves the analysis rate (floored at 2fps); stop keeps the service alive. 2C of hysteresis on the way down. |

### Working distance and what it costs

The rig sits **31cm** above the board (measured), so focus is locked at
**3.23 D**. That is further than the 20cm originally assumed, and the cost falls
directly on the thing this rig exists to photograph: at 31cm a 7mm insect spans
roughly **55-60px** at full sensor resolution, down from ~90px at 20cm.

That is still comfortably enough for detection and for the coarse
classification the corpus is being built to support, but it is a real reduction
in the pixels-on-target a future classifier will have (see 0.1) and it is worth
knowing before anyone concludes the crops are disappointing. Lowering the stand
is the lever if more resolution is ever needed; nothing in software recovers it.

### Where sessions are written

```
/storage/emulated/0/DCIM/Biomon/<sessionId>/
    SUMMARY.md          human-readable, written as the session runs
    manifest.jsonl      append-only, one JSON record per line
    index.db            SQLite, WAL, a convenience and not a dependency
    frames/<sessionId>_e00007_20260804T112233456Z_f00042.jpg
```

**Shared storage under DCIM, deliberately, not app-specific storage.** Three
reasons, in order of how much they hurt:

- An app-specific directory (`Android/data/dk.biomon.insect/files`) is **deleted
  when the app is uninstalled**, so a reinstall between deployments would take a
  day's frames with it.
- Most USB hosts hide or block `Android/data` entirely.
- **DCIM is what the media scanner and MTP expect to hold images.** Files
  written anywhere are invisible over USB until something indexes them —
  historically a reboot. Frames are handed to `MediaScannerConnection` in
  batches as they are written, so the folder appears in the gallery and over USB
  during the session rather than after a restart. Batched rather than per-file:
  a scan is a binder round trip plus a media-provider insert, and one per JPEG
  at 4fps would put real load on the capture path for something nobody looks at
  mid-deployment.

The cost is that this needs **All Files Access**, granted by hand in system
settings; the app asks on first run. If it is refused the session still runs,
falls back to app-specific storage, and records a manifest line saying so --
losing the frames would be worse than putting them somewhere inconvenient, and
the manifest says which happened.

### SUMMARY.md

One file per session, written **as the session runs** and rewritten on every
event, guard transition, error and focus change (and at most every 10s for
frames). Never composed at shutdown: sessions end abruptly by default, and a
summary written at the end is a summary that does not exist for exactly the
deployments most in need of explaining. If the session is still open, or died
without warning, the file says so at the top rather than looking complete.

It carries session ID, start/end/duration, device, focus in dioptres and cm,
capture and analysis resolution and quality, event and frame counts, bytes and
mean frame size, battery start/end/minimum, temperature min/mean/max, every
degradation and camera error with timestamps, every focus change, the
termination reason, the illumination-event count with first, last and largest
(§3.2), and a per-event table of start time, duration, frame count and peak blob
area.

The aggregation lives in `:core` (`SessionSummary`) and is unit-tested, because
it is the artefact most likely to be read by someone who was not there.

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

**Illumination events: the upper area bound.** There is a minimum blob area to
reject sensor noise and a *maximum* to reject the light changing. Measured from
the rig: an insect at 25cm is roughly 4,900 full-resolution pixels; walking past
the rig produced blobs of ~2.9M, about a quarter of the frame. The default bound
is `illuminationAreaFraction = 0.02` — ~240,000 px of a 12MP frame, about 40×
the target and two orders of magnitude below the walk-past.

It is a **fraction of frame area, not a pixel count**, so it survives a
resolution or downsample change. A pixel count here would be the same failure
shape as the frames-vs-time class in §3.7, one axis over.

An oversized blob is **not discarded**. It raises an illumination event:

1. **Capture is suppressed for the frame** — and the small blobs on it too,
   because when the whole residual field has just moved, none of them are
   trustworthy.
2. **An `illumination_event` record is written**, carrying the area both as
   working pixels and as a fraction. Outdoors these are the weather: cloud
   shadow crossing the board raises them repeatedly, and their rate is how the
   laptop side distinguishes a cloudy afternoon's thin detection record from a
   broken rig's. Discarding them would throw that away.
3. **The background model is re-baselined wholesale**, because a global
   brightness shift makes it stale everywhere at once.

**Interaction with the forced refresh (§3.3).** These are two answers to "the
model is stale", at different scales, and they must not both fire for one cause.
The per-pixel forced refresh releases pixels individually after
`forcedRefreshSeconds` — right for a moved bait dish, wrong for a shadow, which
it would spend two minutes letting go of while reporting the whole board as
motion. So on an illumination frame the re-baseline supersedes it: the trigger
zeroes `forcedRefreshPixels`, and `rebaseline()` clears every pixel's foreground
timer so none can reach its own deadline immediately afterwards. One stale-model
event, one refresh, one record.

**Known cost.** Re-baselining adopts whatever is in frame, so an insect present
when a cloud shadow crosses is absorbed into the background and its event
truncated. It re-triggers when it next moves, and the `illumination_event`
record sits in the manifest at the truncation point, so the gap is explicable
rather than mysterious. Accepted as the lesser harm: the alternative is a model
that stays wrong about the whole board for two minutes every time the sun goes
in.

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

**Motion-adaptive capture rate.** Storage is the binding constraint: ~30GB free
and no expandable storage. Frame size is scene-dependent and the first field run
measured it: **929.7 kB mean** on a dark indoor scene at 4000×3000 q85. Daylight
on a white board — high contrast, high detail, which is what JPEG charges for —
should run **2–4 MB**. So the realistic budget is:

| Scene | Mean frame | Frames in 30GB | Flat 3fps lasts |
| --- | --- | --- | --- |
| Dark indoor (measured) | 0.93 MB | ~32,000 | ~3.0 hours |
| Daylight, white board (expected) | 2–4 MB | **~8,000–15,000** | **~45–85 minutes** |

The daylight row is the one that governs a deployment, and it is roughly half
what an earlier ~4MB/30,000-frame assumption implied. It does not change the
design — the motion-adaptive rate is exactly the response to it — but it does
mean a flat 3fps would exhaust the card before lunch, and that a single insect
feeding for five minutes at full rate costs 1.8–3.6GB.

Because the real number depends on the day's light, **`SUMMARY.md` projects
remaining capacity from the session's own running mean frame size** rather than
from any constant here. That is the figure to trust in the field.

So the rate varies *within* an event:

| State | Rate |
| --- | --- |
| Blob moving | full, 3–5fps |
| Blob present but stationary (centroid **speed** below `stationaryDisplacementPxPerSecond` for `stationarySeconds`) | 1fps |
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
into the background` holds a target still for 40 seconds and asserts it is still
detected on the last frame; an unmasked EMA with a 10s time constant loses it in
a few seconds.

That alone would pin the model forever if the scene genuinely changed — bait
moved, shadow shifted. So a pixel held foreground for `forcedRefreshSeconds`
(default 120) is folded into the background regardless, and **the forced refresh
is logged** (`forced_refresh` records) so a re-baselining event is visible in the
data rather than looking like an insect that vanished.

That is the *local* release valve, for a change confined to part of the frame. A
change covering more of the frame than an insect ever could is handled by the
illumination path in §3.2 instead, which re-baselines the whole model at once.
The two never both fire for the same cause — see the interaction note there.

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

### 3.5 The preview is not a camera stream

The Camera2 session is configured **once**, with the analysis and capture
surfaces only, and is never reconfigured for the lifetime of the session.

This is not an implementation detail. A preview `Surface` appears and disappears
with the screen, and including one in the session means reconfiguring the
session every time — tearing down and rebuilding both streams, dropping frames,
and resetting the EMA background model. That would fire at precisely the worst
moment: the operator aims the rig, locks the screen, walks away, and the first
thing the unattended session does is throw away the background it just spent
thirty frames learning.

So the UI preview is rendered from **copies of the analysis luma** the trigger is
already looking at — half resolution, grayscale, a few hertz, and produced only
while a screen is actually attached. Screen on and screen off are invisible to
the camera. It also means the mask overlay lands exactly on the pixels that
produced it, because both come from the same frame in the same coordinates.

Focus is the one capture parameter adjustable mid-session, because it has to be
re-aimed per deployment. Changing it reissues the repeating request only; the
session, both streams and the background model are untouched. Every change is
recorded (`focus` records), because refocusing shifts sharpness across the whole
frame and the background model reads that as motion everywhere at once.

### 3.6 Foreground service and screen-off

There is no existing Kotlin audio recorder in this repository to reuse — it was
not present. The bird station's conventions are followed from the brief rather
than from code.

Requirements: foreground service with a persistent notification, `WAKE_LOCK`
held (partial), screen fully off during capture, behaviour verified across a Doze
transition. **Non-negotiable #5: a one-hour screen-off test producing correctly
timestamped, correctly focused, correctly exposed frames is the milestone that
matters.** Everything else is secondary.

### 3.7 Durations are durations, not frame counts

**The analysis rate is not a constant.** Thermal backoff halves it to a floor of
2fps, and on a hot Copenhagen afternoon in a closed enclosure that is the normal
case, not the exception. So any constant that is conceptually about *elapsed
time* but stored as a frame count means something different in the afternoon than
it did in the morning — and changes meaning without a manifest record, which is
non-negotiable #3 broken by arithmetic rather than by omission.

The rule: **anything that answers "how long" is stored in seconds or
milliseconds and evaluated against frame timestamps.** Specifically —

| Quantity | Expressed as |
| --- | --- |
| Trigger warm-up | seconds, against `AnalysisFrame.timestampNs` |
| Background EMA | a time constant in seconds; per-frame α = `1 − e^(−Δt/τ)` |
| Per-region noise statistics | the same, with its own time constant |
| Forced background refresh | milliseconds of accumulated foreground time per pixel |
| Moving vs stationary | centroid **speed** in px/s, not displacement per frame |
| Stationary dwell before the rate drops | seconds |
| Post-roll | milliseconds, against wall clock |

`EmaBackgroundModel` is deliberately **never told the frame rate**. It derives
every interval from the timestamps it is given, which makes it correct at rates
nobody anticipated — including a stalled stream, where the step is clamped so one
late frame cannot be credited with five minutes of confident observation and
overwrite the background wholesale.

The one deliberate exception is `maxFramesPerEvent` (default 3000). That is a
storage cap — 3000 frames is 3000 frames' worth of disk whatever rate produced
them — so a frame count is the correct unit and it is left alone.

**Capture rate is coupled to analysis rate, and this is intended.** Captures are
requested from the analysis thread, at most one per analysis frame, so capture
can never exceed the analysis rate: when thermal backoff drops analysis to 2fps,
the 4fps moving rate becomes 2fps. `GuardState.maxCaptureFps` is therefore
`min(disk ceiling, analysis rate)` rather than the disk ceiling alone, and
`captureBoundByAnalysisRate` says which constraint is binding, so the manifest
records a ceiling that can actually be delivered and the reduction is attributed
to the thermal guard rather than to storage.

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
  halves (floored at 2fps, below which the interval between centroid fixes is
  long enough that a feeding insect's own jitter dominates the speed estimate);
  the capture ceiling follows it down, because capture is requested from the
  analysis thread (§3.7). Above 45°C capture stops but the service stays alive.
  Every transition is logged, including the capture ceiling's.
  Transitions carry 2°C of hysteresis so a temperature sitting on a boundary
  cannot flap and fill the manifest with noise. A degraded session is better
  than a lost one.
- **Export** produces a directory the existing laptop pipeline consumes without
  modification.

---

## 5. UI

Minimal, and not the point. One screen: preview with the trigger mask overlaid,
current focus distance, battery percentage and temperature, free space, events so
far this session, start/stop. A settings screen for thresholds and framerate.

The screen is locked to **portrait**: the rig is a phone on a stand pointing
straight down at a board, which is a portrait posture. UI orientation says
nothing about the sensor, and captured frames are never rotated to match it —
doing so would silently change the coordinate system the blob boxes are recorded
in.

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

## 7. State of verification — read this before deploying

Being precise about what has and has not been checked, because the difference
matters more here than in most projects.

**Verified by test, on every commit:**

- The trigger pipeline: background model, local threshold, blob detection.
- That a stationary target survives 40 seconds without being absorbed (§3.3),
  and that a permanently changed scene does re-baseline afterwards.
- That warm-up, background convergence, forced refresh, the moving/stationary
  decision, the stationary dwell and post-roll all behave identically in
  wall-clock terms at 2, 3, 5 and 10fps (§3.7).
- That an oversized blob raises an illumination event rather than a detection,
  that an insect-sized one does not, that the verdict is the same at downsample
  1/2/4/8, and that one illumination event never produces two refresh records
  (§3.2).
- That local normalisation equalises corner and centre sensitivity, and that a
  global threshold does not (§0.2).
- Event assembly: post-roll, event extension, the frame cap, the motion-adaptive
  rate and its recovery on renewed movement.
- The storage argument itself: a simulated five-minute feeding visit costs a
  fraction of a flat 3fps, and an empty scene produces almost nothing.
- Guard tiers and thermal hysteresis; the filename scheme's round trip and sort
  order; that manifest lines never break the one-record-per-line contract.

42 tests. `./gradlew :core:test`.

**Verified by CI, on every push to `main`:**

- `:app` compiles and assembles. `.github/workflows/build.yml` runs the core
  tests, then `assembleDebug`, then publishes the APK to a `build-N` release.
  The core tests gate the APK: an APK shipping a broken background model is
  worse than no APK.

Note what that does and does not mean. It means the Android half type-checks
against the real SDK and links against the real AndroidX and Camera2 APIs, which
is more than could be said before. It does not mean any of it runs.

**Not verified, and honestly not verifiable without the device:**

- Everything Camera2 touches: whether the chosen back camera is the right lens,
  whether the analysis and JPEG sizes negotiate, whether AWB has converged by the
  time it is locked, whether the still-request queue stays aligned with the JPEG
  stream under load.
- Doze survival, wake-lock behaviour over nine hours, and whether the 60s tick
  actually fires sixty times an hour with the screen off.
- Sustained capture rate. If it comes in under 3fps, DESIGN.md 3.2 says to
  suspect flash write throughput before JPEG encode.
- The power figure. That is the whole point of the first deployment.

The milestone that closes this gap is the one in §3.5: a one-hour screen-off run
with correctly timestamped, focused and exposed frames and sixty power samples in
the manifest. Nothing else counts as "it works".

## 8. Rig and bait notes (for the human)

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
