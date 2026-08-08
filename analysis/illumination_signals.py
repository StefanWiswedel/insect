#!/usr/bin/env python3
"""Score a session's blobs against the proposed illumination signals.

The upper area bound at 0.02 is too loose -- session 050826_0 recorded four
events with blobs of 88k-236k px on a static indoor scene -- but tightening it
is not safe, because a moth with wings spread (~50-60k px) sits close under the
false detections. So the proposal is to keep size as a *gate* and require
corroboration from shape and position before calling a blob illumination.

This script does not implement that classifier. It reports what each signal
would have said about blobs already on disk, so the thresholds can be argued
against real numbers before any of it reaches the device. Nothing here runs on
the phone.

    python3 analysis/illumination_signals.py /path/to/session/manifest.jsonl

Everything it needs is already in the manifest: `frame` records carry
`boxes` as [left, top, right, bottom, area] per blob in full-resolution
coordinates, and `session_start` carries the capture geometry.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from dataclasses import dataclass, field

# --- proposed thresholds, all in one place so they are easy to argue with ----

# Single-signal certainty: nothing biological is this big at 31cm.
CERTAIN_FRACTION = 0.02
# Gate into corroboration. Sits in the gap between a wings-spread moth
# (~60k px, 0.49% of a 12.19MP frame) and the smallest observed false
# detection (88k px, 0.72%).
SUSPECT_FRACTION = 0.005
# A blob whose bounding box comes within this fraction of frame width/height
# of an edge counts as touching it.
EDGE_MARGIN_FRACTION = 0.01
# Below this, the blob is too sparse in its own bounding box to be a body.
FILL_RATIO_MAX = 0.35
# Simultaneous blobs in one frame, spread this far apart, is a global change.
BLOB_COUNT_MIN = 3
SPREAD_FRACTION_MIN = 0.5
# Corroborating signals required before calling a suspect blob illumination.
SIGNALS_REQUIRED = 2


@dataclass
class FrameScore:
    seq: int
    area: int
    fraction: float
    edges: int
    opposite_edges: bool
    fill: float
    blob_count: int
    spread_fraction: float

    def edge_signal(self) -> bool:
        # Two opposite edges, or three of four. An insect at a corner bait
        # station can reach two *adjacent* edges, so adjacency alone is not
        # evidence of anything.
        return self.opposite_edges or self.edges >= 3

    def fill_signal(self) -> bool:
        return self.fill < FILL_RATIO_MAX

    def count_signal(self) -> bool:
        return self.blob_count >= BLOB_COUNT_MIN and \
            self.spread_fraction > SPREAD_FRACTION_MIN

    def signals(self) -> int:
        return sum([self.edge_signal(), self.fill_signal(), self.count_signal()])

    def verdict(self) -> str:
        if self.fraction >= CERTAIN_FRACTION:
            return "ILLUMINATION (size alone)"
        if self.fraction < SUSPECT_FRACTION:
            return "detection (under gate)"
        if self.signals() >= SIGNALS_REQUIRED:
            return f"ILLUMINATION ({self.signals()}/3 signals)"
        return f"detection ({self.signals()}/3 signals, needs {SIGNALS_REQUIRED})"


@dataclass
class EventScore:
    event_id: int
    frames: int = 0
    peak: FrameScore | None = None
    scores: list[FrameScore] = field(default_factory=list)


def score_frame(boxes, seq, width, height) -> FrameScore | None:
    """Score one frame's blob list. Returns None when the frame had no blobs."""
    if not boxes:
        return None
    frame_area = width * height
    diagonal = math.hypot(width, height)
    margin_x = width * EDGE_MARGIN_FRACTION
    margin_y = height * EDGE_MARGIN_FRACTION

    # The largest blob is the one the size test would have judged.
    largest = max(boxes, key=lambda b: b[4])
    left, top, right, bottom, area = largest[:5]

    edges = 0
    touch_left = left <= margin_x
    touch_right = right >= width - 1 - margin_x
    touch_top = top <= margin_y
    touch_bottom = bottom >= height - 1 - margin_y
    edges = sum([touch_left, touch_right, touch_top, touch_bottom])
    opposite = (touch_left and touch_right) or (touch_top and touch_bottom)

    box_area = max(1, (right - left + 1) * (bottom - top + 1))
    fill = area / box_area

    # Centroid spread across every blob in the frame, as a fraction of the
    # frame diagonal. Bounding-box centres stand in for centroids: the
    # manifest records boxes, and for this purpose the difference is noise.
    centres = [((b[0] + b[2]) / 2, (b[1] + b[3]) / 2) for b in boxes]
    spread = 0.0
    for i, a in enumerate(centres):
        for b in centres[i + 1:]:
            spread = max(spread, math.hypot(a[0] - b[0], a[1] - b[1]))

    return FrameScore(
        seq=seq,
        area=area,
        fraction=area / frame_area,
        edges=edges,
        opposite_edges=opposite,
        fill=fill,
        blob_count=len(boxes),
        spread_fraction=spread / diagonal,
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest", help="path to a session's manifest.jsonl")
    ap.add_argument("--all-frames", action="store_true",
                    help="score every frame, not just each event's largest blob")
    args = ap.parse_args()

    width = height = 0
    events: dict[int, EventScore] = defaultdict(lambda: EventScore(event_id=-1))
    malformed = 0

    with open(args.manifest, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                rec = json.loads(raw)
            except json.JSONDecodeError:
                # A session that lost power mid-line leaves a partial record.
                # That is expected, not a reason to refuse to report.
                malformed += 1
                continue
            kind = rec.get("type")
            if kind == "session_start":
                # "capture" is [width, height].
                cap = rec.get("capture") or []
                if len(cap) == 2:
                    width, height = int(cap[0]), int(cap[1])
            elif kind == "frame":
                eid = int(rec.get("event", -1))
                ev = events[eid]
                ev.event_id = eid
                ev.frames += 1
                boxes = rec.get("boxes") or []
                if not width or not height:
                    continue
                fs = score_frame(boxes, int(rec.get("seq", 0)), width, height)
                if fs is None:
                    continue
                ev.scores.append(fs)
                if ev.peak is None or fs.area > ev.peak.area:
                    ev.peak = fs

    if not width or not height:
        print("No session_start with capture geometry; cannot compute fractions.",
              file=sys.stderr)
        return 2

    frame_px = width * height
    print(f"Capture {width}x{height} = {frame_px:,} px")
    print(f"Gate: suspect >= {SUSPECT_FRACTION:.3%} "
          f"({int(frame_px * SUSPECT_FRACTION):,} px), "
          f"certain >= {CERTAIN_FRACTION:.3%} "
          f"({int(frame_px * CERTAIN_FRACTION):,} px)")
    print(f"Corroboration: {SIGNALS_REQUIRED} of 3 "
          f"(edges, fill < {FILL_RATIO_MAX}, "
          f"{BLOB_COUNT_MIN}+ blobs spread > {SPREAD_FRACTION_MIN:.0%})")
    print()

    header = (f"{'Ev':>3} {'frames':>6} {'peak px':>10} {'% frame':>8} "
              f"{'edges':>5} {'opp':>4} {'fill':>5} {'blobs':>5} {'spread':>7}  verdict")
    print(header)
    print("-" * len(header))
    for eid in sorted(events):
        ev = events[eid]
        p = ev.peak
        if p is None:
            print(f"{eid:>3} {ev.frames:>6} {'-':>10} {'-':>8} "
                  f"{'-':>5} {'-':>4} {'-':>5} {'-':>5} {'-':>7}  no blobs recorded")
            continue
        print(f"{eid:>3} {ev.frames:>6} {p.area:>10,} {p.fraction:>7.2%} "
              f"{p.edges:>5} {'yes' if p.opposite_edges else 'no':>4} "
              f"{p.fill:>5.2f} {p.blob_count:>5} {p.spread_fraction:>6.0%}  "
              f"{p.verdict()}")

    if args.all_frames:
        print("\nPer-frame detail:")
        for eid in sorted(events):
            for fs in events[eid].scores:
                print(f"  ev{eid} seq{fs.seq}: {fs.area:,} px "
                      f"({fs.fraction:.2%}) edges={fs.edges} "
                      f"opp={fs.opposite_edges} fill={fs.fill:.2f} "
                      f"blobs={fs.blob_count} spread={fs.spread_fraction:.0%} "
                      f"-> {fs.verdict()}")

    if malformed:
        print(f"\n{malformed} malformed line(s) skipped "
              f"(expected if the session lost power mid-write).")
    print("\nThe verdict column is what the PROPOSED rule would have said. "
          "Nothing on the device behaves this way yet.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
