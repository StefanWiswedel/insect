# insect

Persistent insect monitoring device — native Android capture app for the Biomon
project.

A Pixel 6a on a stand, in shade behind a stick pile, bait in frame, screen off,
~9 hours unattended. It captures full-resolution stills of insects visiting bait
and writes a self-describing session directory the existing laptop pipeline
consumes without modification.

**It does not classify.** There is no on-device insect classifier and no
labelled dataset for this site yet — the first deployments exist to produce one.
The trigger stage is architected so a TFLite model can be added later; none is
shipped.

Read [`DESIGN.md`](DESIGN.md) for the design and the Phase 0 record, and
[`CLAUDE.md`](CLAUDE.md) for working in the repo.

## Build

```bash
./gradlew :core:test           # no Android SDK needed
./gradlew :app:assembleDebug   # needs the Android SDK
```

`:core` is pure Kotlin and holds the trigger pipeline, event assembly, guards,
record formats and the filename scheme — everything that can be wrong in a way a
test can catch. `:app` is Android plumbing: Camera2, the foreground service,
storage and one screen. `:app` is included in the build only when an Android SDK
is present, so `:core` stays testable anywhere.

## Analysis scripts

Run on the analysis laptop, against the existing corpus and against session
manifests the app produces.

```bash
python3 analysis/phase0_crop_scale.py results.db --sessions 280726_1 290726_0
python3 analysis/phase0_region_calibration.py /path/to/frames --json docs/region_calibration.json
python3 analysis/power_report.py /path/to/sessions/040826_0/manifest.jsonl
```

`power_report.py` is the one that matters after a deployment: the app has no
bench test, so it instruments itself and the deployment *is* the power
measurement.

## CI

`.github/workflows/build.yml` runs the core tests and assembles a debug APK on
every push to `main`, publishing it to a `build-N` release. The tests gate the
APK deliberately: the trigger pipeline is not verifiable on a device, so an APK
that ships a broken background model is worse than no APK.

A green build means `:app` type-checks against the real SDK. It does not mean
anything runs — see DESIGN.md section 7.

## The milestone that matters

Not a build that compiles. A one-hour screen-off run producing correctly
timestamped, correctly focused, correctly exposed frames, with a manifest
containing sixty power samples. Verify that before adding anything.
