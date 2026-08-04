# CLAUDE.md — Biomon insect trap

Read `DESIGN.md` first. It is the source of truth; this file is about working in
the repo.

## What this is

A native Android capture app for autonomous insect monitoring: full-resolution
stills of insects visiting bait, screen off, ~9 hours unattended on a Pixel 6a.
It detects motion and saves frames. **It does not classify** — see DESIGN.md.

## Layout

```
core/      Pure Kotlin/JVM. Trigger pipeline, event assembly, guards, record
           formats, filenames. No Android dependencies.
app/       Android. Camera2, foreground service, storage, UI.
analysis/  Python, runs on the analysis laptop against the existing corpus.
docs/      analysis-stream-contract.md — the capture/trigger interface.
```

## Building

```bash
./gradlew :core:test      # works anywhere with a JDK. Do this constantly.
./gradlew :app:assembleDebug   # needs the Android SDK
```

`:app` is only included in the build when an Android SDK is present
(`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`).
Without one, Gradle says so and `:core` still builds and tests. This is
deliberate — the algorithmic half must stay verifiable without a device.

## Conventions

- **Anything that can be tested without a phone lives in `:core`.** If you are
  about to put arithmetic, a state machine, a threshold or a policy decision into
  `:app`, it belongs in `:core` with a test. `:app` should be Android plumbing
  and little else.
- **Nothing degrades silently.** Thermal backoff, disk pressure, dropped frames,
  forced background refresh, capture-rate changes — each one gets a manifest
  record. If you add a new way for the app to do less than it was asked to, add
  the record in the same commit. This is non-negotiable #3 and it is the rule
  most easily broken by accident.
- **Assume the session ends abruptly.** Nothing may depend on a clean shutdown
  having run. Write records as they occur; never buffer to session end.
- **The database is a convenience, not a dependency.** Filenames carry session,
  event, timestamp and sequence. If you find yourself needing the database to
  interpret a frame, the filename scheme has been broken.
- **No classifier.** Do not add one to this version, and do not add a dependency
  that implies one.

## Gotchas

- `AnalysisFrame.luma` is a **borrowed** buffer, valid only for the duration of
  the call. See `docs/analysis-stream-contract.md` before touching either side of
  the analysis stream.
- Luma bytes are unsigned; `luma[i].toInt() and 0xFF`, or `lumaAt`.
- JPEG quality has a hard floor of q85 — `CaptureConfig` rejects lower. Below
  ~q75 the blocking artefacts inflate blob counts in the laptop's residual.
- The trigger threshold floor is a *fraction of local brightness*, not an
  absolute luma step. That is what cancels vignetting; an absolute floor
  reintroduces a corner/centre sensitivity bias. See DESIGN.md 0.2.
- Camera2 (not CameraX) is a requirement, not a preference: the app needs manual
  focus, AWB lock and OIS control that CameraX abstracts away.

## Before you claim it works

The milestone that matters is a one-hour screen-off run producing correctly
timestamped, correctly focused, correctly exposed frames, with a manifest that
contains 60 power samples. Not a build that compiles.
