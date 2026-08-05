# Analysis stream contract (Agent A ↔ Agent B)

Settled before either half was written, because two halves built against
different assumptions about buffer ownership and threading is exactly how this
goes wrong. The capture core (A) produces; the trigger pipeline (B) consumes.
Neither side may change this without changing this document.

The types live in `:core`: `dk.biomon.insect.core.AnalysisFrame`,
`dk.biomon.insect.core.trigger.MotionTrigger`,
`dk.biomon.insect.core.event.EventStateMachine`.

## Format

- Source: `ImageReader` of `ImageFormat.YUV_420_888`, **640×480**, requested at
  **5fps** (`CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE` is *not* the control
  here — the analysis rate is enforced by the repeating request cadence and by
  dropping frames at the reader, see below).
- Only the **Y plane** is copied. Chroma is never read: the background model is
  grayscale, and skipping chroma halves the copy cost.
- `rowStride` is taken from `image.planes[0].rowStride` and passed through
  verbatim. It commonly exceeds 640. Ignoring it shears the image.
- `pixelStride` **must be 1**. If a device ever reports otherwise, A must
  de-interleave into a stride-1 buffer before constructing the frame; B assumes
  `luma[y * rowStride + x]`.
- Luma bytes are **unsigned**. Use `AnalysisFrame.lumaAt`, or
  `luma[i].toInt() and 0xFF`.
- `timestampNs` is **load-bearing, not decoration**. Every duration B holds —
  warm-up, background convergence, forced refresh, the moving/stationary speed
  test — is derived from the interval between consecutive `timestampNs` values,
  because the analysis rate is not constant (thermal backoff halves it). A must
  pass `CaptureResult.SENSOR_TIMESTAMP` through unmodified: same monotonic
  clock, same units, never synthesised from a frame counter, never reset except
  across a `MotionTrigger.reset()`. B clamps implausibly long steps rather than
  trusting them, so a stall degrades gracefully, but a *wrong* timestamp
  silently rescales the whole trigger.

## Ownership

1. **A owns the buffer.** `AnalysisFrame.luma` is borrowed for the duration of
   the `MotionTrigger.onFrame` call and recycled immediately after. B must not
   retain it, wrap it in anything outliving the call, or pass it to another
   thread. B does not: `EmaBackgroundModel` immediately downsamples into its own
   float buffer.
2. **A closes the `Image`.** B never sees an `Image`, only the copied plane, so
   there is no path where B leaking a reference can stall the reader.
3. **A reuses one `ByteArray`** sized `rowStride * height`, reallocated only if
   the geometry changes.

## Threading

- One dedicated analysis `HandlerThread`. Frames are delivered **synchronously,
  in order, one at a time**.
- `MotionTrigger` and `EventStateMachine` are **not internally synchronised** and
  must be touched from nowhere else. UI reads go through a snapshot A publishes,
  not through the trigger objects.
- B must not block. Anything slower than the frame interval (200ms at 5fps)
  starves the reader. Disk, database and manifest writes belong on other threads;
  B returns actions, it does not perform them.
- Back-pressure is A's problem: if `onFrame` is still running when a new image
  arrives, A drops the new image (`acquireLatestImage`) rather than queueing.

## Dropped frames

`AnalysisFrame.index` is a monotonic counter assigned by A **before** any drop
decision. A gap at B is exactly the number of frames dropped. A logs the gap as a
`Degradation(kind = "dropped_frames")`. Non-negotiable #3: nothing is silently
dropped.

## Lifecycle

| Event | A does | B does |
| --- | --- | --- |
| Session start | configure session, lock focus/WB/OIS, start repeating request | construct `MotionTrigger`, warm up over `warmupSeconds` |
| Camera error / restart | tear down, rebuild session, log `ErrorRecord` | `MotionTrigger.reset()` — the scene may have moved |
| Thermal backoff | reduce repeating-request cadence | nothing; it just sees fewer frames, and every duration it holds is measured against `timestampNs` rather than counted in frames (DESIGN.md 3.7) |
| Capture stopped by a guard | keep the analysis stream running | `EventStateMachine.onDecision(captureAllowed = false)` closes the open event with its reason |
| Session stop | stop repeating request, close session | `EventStateMachine.close(reason)` |

The analysis stream keeps running when a guard stops *capture*: the preview and
the event bookkeeping stay live, and it costs almost nothing relative to
full-resolution readout.

## Return path

B returns a `TriggerDecision` per frame and a list of `EventAction`s from the
state machine. A acts on them:

- `CaptureRequested` → issue a `CAPTURE_TEMPLATE_STILL_CAPTURE` request against
  the already-configured JPEG surface, carrying the event ID and sequence through
  the request tag so the resulting image can be named without re-deriving state.
- `EventStarted` / `EventEnded` / `RateChanged` → hand to persistence (Agent C)
  for the manifest and the SQLite index.

Blob coordinates in `TriggerDecision` are in **downsampled working space**.
Convert to full-resolution capture coordinates with `Blob.scaleTo`, using the
factor from `MotionTrigger.captureScale`, before recording them. The laptop
expects boxes against the full frame.
