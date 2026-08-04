#!/usr/bin/env python3
"""Phase 0.3 -- answer the power question from a deployment's own manifest.

There is no bench test for this rig. The app instruments itself, and the
deployment is the measurement. This script reads `manifest.jsonl` from a session
directory and reports:

  * mean and peak draw in watts, by two independent methods
  * whether the session clears the ~1.8W budget a 9-hour run on the Pixel 6a's
    ~17Wh internal battery requires
  * the battery temperature envelope (the Tensor G1 runs warm and the phone sits
    in a closed position outdoors)
  * every degradation the session recorded, because a session that quietly
    captured nothing for four hours is worse than one that failed loudly at
    hour one

Usage:
    python3 analysis/power_report.py /path/to/sessions/040826_0/manifest.jsonl
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter
from pathlib import Path

# Pixel 6a: 4410mAh at a nominal 3.85V is a little under 17Wh.
BATTERY_WH = 16.98
DEPLOYMENT_HOURS = 9.0
BUDGET_W = BATTERY_WH / DEPLOYMENT_HOURS  # ~1.89W to survive exactly nine hours


def read(path: Path) -> list[dict]:
    records, bad = [], 0
    with path.open() as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                records.append(json.loads(raw))
            except json.JSONDecodeError:
                # A truncated final line is expected: the session may have ended
                # abruptly mid-write. Anything more than that is worth knowing.
                bad += 1
    if bad:
        print(f"note: {bad} unparseable line(s); a single trailing one is normal", file=sys.stderr)
    return records


def fmt_duration(ms: float) -> str:
    s = ms / 1000
    return f"{int(s // 3600)}h{int(s % 3600 // 60):02d}m"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest", type=Path)
    ap.add_argument("--budget", type=float, default=BUDGET_W)
    args = ap.parse_args()

    records = read(args.manifest)
    if not records:
        raise SystemExit("empty manifest")

    by_type: Counter[str] = Counter(r.get("type", "?") for r in records)
    power = [r for r in records if r.get("type") == "power"]
    start = next((r for r in records if r.get("type") == "session_start"), None)
    end = next((r for r in records if r.get("type") == "session_end"), None)

    print(f"manifest: {args.manifest}")
    if start:
        print(f"session:  {start.get('session_id')} on {start.get('device')} / {start.get('android')}")
        print(f"          lens {start.get('lens')} focus {start.get('focus_diopters')}D "
              f"capture {start.get('capture')} q{start.get('jpeg_quality')}")
    print("records:  " + ", ".join(f"{k}={v}" for k, v in sorted(by_type.items())))

    if not power:
        raise SystemExit("no power samples: the app did not instrument itself, which is a bug")

    span_ms = power[-1]["t"] - power[0]["t"]
    print(f"\nsampled {len(power)} times over {fmt_duration(span_ms)} "
          f"(expected ~{int(span_ms / 60000)} at 60s intervals)")
    gaps = [b["t"] - a["t"] for a, b in zip(power, power[1:])]
    if gaps and max(gaps) > 150_000:
        print(f"  WARNING: largest sampling gap {max(gaps)/1000:.0f}s -- the service "
              f"was probably suspended; check Doze and the wake lock")

    # Method 1: the device's own current sensor.
    watts = [r["watts"] for r in power if r.get("watts") is not None]
    discharging = [r for r in power if not r.get("charging")]
    if watts:
        print(f"\ndraw, from BatteryManager current x voltage:")
        print(f"  mean {statistics.mean(watts):.2f}W   median {statistics.median(watts):.2f}W   "
              f"peak {max(watts):.2f}W")

    # Method 2: battery percentage actually consumed. Cruder, but it is the
    # ground truth the deployment cares about, and it cannot be fooled by a
    # current sensor that reports the charger rather than the load.
    if len(discharging) >= 2 and span_ms > 0:
        first, last = discharging[0], discharging[-1]
        drop = first["battery_pct"] - last["battery_pct"]
        hours = (last["t"] - first["t"]) / 3_600_000
        if drop > 0 and hours > 0:
            observed = BATTERY_WH * drop / 100 / hours
            print(f"\ndraw, from battery percentage consumed while discharging:")
            print(f"  {drop}% over {hours:.2f}h = {observed:.2f}W")
            print(f"  projected runtime on a full internal battery: {BATTERY_WH / observed:.1f}h")
            verdict = "CLEARS" if observed < args.budget else "EXCEEDS"
            print(f"\n  {verdict} the {args.budget:.2f}W budget for a {DEPLOYMENT_HOURS:.0f}h "
                  f"deployment on internal battery alone.")
        else:
            print("\nbattery did not measurably discharge (external power bank attached "
                  "for the whole session, most likely) -- the percentage method cannot "
                  "answer the budget question from this run.")
    else:
        print("\nno discharging samples: the phone was on external power throughout.")

    temps = [r["temp_c"] for r in power if r.get("temp_c") is not None]
    if temps:
        print(f"\nbattery temperature: min {min(temps):.1f}C  median "
              f"{statistics.median(temps):.1f}C  max {max(temps):.1f}C")
        if max(temps) >= 40:
            print("  crossed the 40C thermal backoff threshold at least once")

    free = [r["free_bytes"] for r in power if r.get("free_bytes") is not None]
    if free:
        used = (free[0] - free[-1]) / 1e9
        print(f"\nstorage: {free[0]/1e9:.1f}GB free at start, {free[-1]/1e9:.1f}GB at end "
              f"({used:.1f}GB written)")

    degradations = [r for r in records if r.get("type") == "degradation"]
    print(f"\ndegradations: {len(degradations)}")
    for kind, count in Counter(d.get("kind") for d in degradations).most_common():
        print(f"  {kind}: {count}")
    for d in degradations[:20]:
        print(f"    [{d['t']}] {d.get('kind')}: {d.get('detail')}")
    if len(degradations) > 20:
        print(f"    ... {len(degradations) - 20} more")

    errors = [r for r in records if r.get("type") == "error"]
    if errors:
        print(f"\nerrors: {len(errors)} "
              f"({sum(1 for e in errors if e.get('recovered'))} recovered)")
        for e in errors[:10]:
            print(f"    [{e['t']}] {e.get('component')}: {e.get('message')}")

    events = [r for r in records if r.get("type") == "event_end"]
    frames = [r for r in records if r.get("type") == "frame"]
    if events:
        durations = [e["duration_ms"] for e in events]
        print(f"\nevents: {len(events)}, {sum(e['frames'] for e in events)} frames")
        print(f"  duration median {statistics.median(durations)/1000:.1f}s  "
              f"max {max(durations)/1000:.1f}s")
        for reason, count in Counter(e.get("reason") for e in events).most_common():
            print(f"  ended by {reason}: {count}")
    if frames:
        total = sum(f.get("bytes", 0) for f in frames)
        print(f"  written {total/1e9:.2f}GB, mean frame {total/len(frames)/1e6:.2f}MB")

    print()
    if end:
        print(f"session ended cleanly: {end.get('reason')} after "
              f"{fmt_duration(end.get('duration_ms', 0))}")
    else:
        print("NO session_end record. The session did not close cleanly -- the phone "
              "died, was killed, or lost power. Everything above is still valid; that "
              "is the point of an append-only manifest.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
