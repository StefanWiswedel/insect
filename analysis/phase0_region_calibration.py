#!/usr/bin/env python3
"""Phase 0.2 -- per-region sensitivity calibration.

The lens vignettes and is optically softer toward the frame corners, so a
single global motion threshold is less sensitive at corner bait stations than
at centre. Bait is deliberately placed in all four corners, so that bias would
be a bias in the results.

This script measures, over a 3x3 grid across the frame:

  * mean luminance (vignetting)
  * background-subtraction residual noise, sigma, using the same EMA background
    the phone runs (not the laptop's rolling median) so that the numbers
    describe the on-device model

and reports the corner/centre spread. It writes an optional JSON calibration
profile that the app can load as a starting point; the app still normalises
locally at runtime, so this is confirmatory rather than load-bearing.

Usage:
    python3 analysis/phase0_region_calibration.py /path/to/session/frames \
        --alpha 0.02 --limit 600 --json docs/region_calibration.json

Requires numpy and Pillow (analysis laptop only).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    import numpy as np
    from PIL import Image
except ImportError:  # pragma: no cover - laptop-only dependency
    print("This script needs numpy and Pillow: pip install numpy pillow", file=sys.stderr)
    raise

GRID = 3
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}


def load_luma(path: Path, downsample: int) -> np.ndarray:
    img = Image.open(path).convert("L")
    if downsample > 1:
        img = img.resize(
            (img.width // downsample, img.height // downsample), Image.BILINEAR
        )
    return np.asarray(img, dtype=np.float32)


def region_slices(h: int, w: int):
    ys = [round(i * h / GRID) for i in range(GRID + 1)]
    xs = [round(i * w / GRID) for i in range(GRID + 1)]
    for r in range(GRID):
        for c in range(GRID):
            yield r, c, slice(ys[r], ys[r + 1]), slice(xs[c], xs[c + 1])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("frames", help="directory of session frames")
    ap.add_argument("--alpha", type=float, default=0.02, help="EMA learning rate")
    ap.add_argument("--limit", type=int, default=600, help="max frames to read")
    ap.add_argument("--warmup", type=int, default=30, help="frames before measuring")
    ap.add_argument(
        "--downsample",
        type=int,
        default=8,
        help="factor to shrink by; the phone analyses a downsampled grayscale too",
    )
    ap.add_argument("--json", help="write a calibration profile here")
    args = ap.parse_args()

    paths = sorted(
        p
        for p in Path(args.frames).rglob("*")
        if p.suffix.lower() in IMAGE_SUFFIXES
    )[: args.limit]
    if len(paths) < args.warmup + 10:
        raise SystemExit(f"need > {args.warmup + 10} frames, found {len(paths)}")

    background: np.ndarray | None = None
    luma_sum = np.zeros((GRID, GRID), dtype=np.float64)
    resid_sq_sum = np.zeros((GRID, GRID), dtype=np.float64)
    resid_abs_sum = np.zeros((GRID, GRID), dtype=np.float64)
    counted = 0

    for i, path in enumerate(paths):
        frame = load_luma(path, args.downsample)
        if background is None:
            background = frame.copy()
            continue
        if background.shape != frame.shape:
            print(f"skipping {path.name}: shape mismatch", file=sys.stderr)
            continue
        residual = np.abs(frame - background)
        background += args.alpha * (frame - background)
        if i < args.warmup:
            continue
        counted += 1
        for r, c, ys, xs in region_slices(*frame.shape):
            luma_sum[r, c] += float(frame[ys, xs].mean())
            resid_sq_sum[r, c] += float((residual[ys, xs] ** 2).mean())
            resid_abs_sum[r, c] += float(residual[ys, xs].mean())

    if counted == 0:
        raise SystemExit("no frames measured")

    luma = luma_sum / counted
    sigma = np.sqrt(resid_sq_sum / counted)
    resid_mean = resid_abs_sum / counted

    centre_luma = float(luma[1, 1])
    centre_sigma = float(sigma[1, 1])
    corners = [(0, 0), (0, 2), (2, 0), (2, 2)]

    print(f"frames measured: {counted} (alpha={args.alpha}, downsample={args.downsample})")
    print("\nmean luminance (0-255):")
    for r in range(GRID):
        print("  " + "  ".join(f"{luma[r, c]:7.1f}" for c in range(GRID)))
    print("\nresidual sigma (EMA background):")
    for r in range(GRID):
        print("  " + "  ".join(f"{sigma[r, c]:7.2f}" for c in range(GRID)))
    print("\nmean |residual|:")
    for r in range(GRID):
        print("  " + "  ".join(f"{resid_mean[r, c]:7.2f}" for c in range(GRID)))

    print("\ncorner vs centre:")
    worst_luma = 1.0
    worst_sigma = 1.0
    for r, c in corners:
        lr = float(luma[r, c]) / centre_luma
        sr = float(sigma[r, c]) / centre_sigma if centre_sigma else float("nan")
        worst_luma = min(worst_luma, lr)
        worst_sigma = max(worst_sigma, sr)
        print(f"  ({r},{c}): luminance {lr*100:5.1f}% of centre, sigma {sr:4.2f}x centre")

    print()
    if worst_luma < 0.85 or worst_sigma > 1.25:
        print(
            "VERDICT: corners differ materially from centre. A global threshold "
            "would bias detection by bait position -- use the locally normalised "
            "threshold (DESIGN.md 3.2)."
        )
    else:
        print(
            "VERDICT: corner/centre spread is small on this session. The local "
            "threshold is still used (it costs little and the finding may not "
            "hold across light conditions), but the bias is not large here."
        )

    if args.json:
        # Gain relative to centre: regions with lower sigma need a proportionally
        # lower absolute threshold to reach the same sensitivity.
        profile = {
            "grid": GRID,
            "alpha": args.alpha,
            "downsample": args.downsample,
            "frames": counted,
            "mean_luma": luma.round(2).tolist(),
            "residual_sigma": sigma.round(3).tolist(),
            "sigma_gain_vs_centre": (sigma / centre_sigma).round(3).tolist()
            if centre_sigma
            else None,
        }
        Path(args.json).write_text(json.dumps(profile, indent=2) + "\n")
        print(f"\nwrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
