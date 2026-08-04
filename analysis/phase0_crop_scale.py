#!/usr/bin/env python3
"""Phase 0.1 -- confirm insect crop scale from the existing laptop corpus.

This is a query against work already done, not a new experiment. It reports the
pixel dimensions of confirmed fly crops so that DESIGN.md can record the
baseline crop size any future on-device classifier would have to work with.

The results database is not in this repository (it lives on the analysis
laptop), so this script is written to be schema-tolerant: it discovers the
detection table and its bounding-box columns rather than assuming a layout.

Usage:
    python3 analysis/phase0_crop_scale.py path/to/results.db \
        --sessions 280726_1 290726_0

Output is a markdown block intended to be pasted into DESIGN.md section 0.1.
"""

from __future__ import annotations

import argparse
import sqlite3
import statistics
import sys
from dataclasses import dataclass

# Column name candidates, in preference order. Each entry is a tuple of
# (x, y, w, h) or (x1, y1, x2, y2) style column groups.
WH_GROUPS = [
    ("x", "y", "w", "h"),
    ("x", "y", "width", "height"),
    ("bbox_x", "bbox_y", "bbox_w", "bbox_h"),
    ("crop_x", "crop_y", "crop_w", "crop_h"),
    ("left", "top", "width", "height"),
]
XY_GROUPS = [
    ("x1", "y1", "x2", "y2"),
    ("xmin", "ymin", "xmax", "ymax"),
    ("bbox_x1", "bbox_y1", "bbox_x2", "bbox_y2"),
    ("left", "top", "right", "bottom"),
]
SESSION_COLS = ["session", "session_id", "session_name", "run", "run_id"]
LABEL_COLS = ["label", "class", "class_name", "species", "taxon", "prediction"]
# Substrings that count as "confirmed fly" for 0.1. Deliberately broad: the
# corpus predates a settled label vocabulary.
FLY_TOKENS = ["fly", "diptera", "drosophila", "musca", "hoverfly", "syrphid"]


@dataclass
class TableChoice:
    table: str
    cols: tuple[str, str, str, str]
    style: str  # "wh" or "xy"
    session_col: str | None
    label_col: str | None
    n_rows: int


def columns_of(con: sqlite3.Connection, table: str) -> list[str]:
    return [r[1] for r in con.execute(f'PRAGMA table_info("{table}")')]


def pick_first(available: list[str], candidates: list[str]) -> str | None:
    lower = {c.lower(): c for c in available}
    for cand in candidates:
        if cand in lower:
            return lower[cand]
    return None


def find_table(con: sqlite3.Connection) -> TableChoice:
    """Find the table that most plausibly holds detection bounding boxes."""
    tables = [
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view')"
        )
    ]
    best: TableChoice | None = None
    for table in tables:
        cols = columns_of(con, table)
        lower = {c.lower(): c for c in cols}
        choice = None
        for group in WH_GROUPS:
            if all(g in lower for g in group):
                choice = (tuple(lower[g] for g in group), "wh")
                break
        if choice is None:
            for group in XY_GROUPS:
                if all(g in lower for g in group):
                    choice = (tuple(lower[g] for g in group), "xy")
                    break
        if choice is None:
            continue
        n = con.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
        cand = TableChoice(
            table=table,
            cols=choice[0],
            style=choice[1],
            session_col=pick_first(cols, SESSION_COLS),
            label_col=pick_first(cols, LABEL_COLS),
            n_rows=n,
        )
        # Prefer the biggest table that also carries a label column.
        key = (cand.label_col is not None, cand.n_rows)
        if best is None or key > (best.label_col is not None, best.n_rows):
            best = cand
    if best is None:
        raise SystemExit(
            "No table with recognisable bounding-box columns found. Tables seen: "
            + ", ".join(tables)
        )
    return best


def summarise(values: list[float], name: str) -> str:
    if not values:
        return f"- {name}: no rows"
    values = sorted(values)
    n = len(values)

    def pct(p: float) -> float:
        idx = min(n - 1, max(0, int(round((n - 1) * p))))
        return values[idx]

    return (
        f"- {name}: median {statistics.median(values):.0f} px, "
        f"p5-p95 {pct(0.05):.0f}-{pct(0.95):.0f} px, "
        f"min-max {values[0]:.0f}-{values[-1]:.0f} px (n={n})"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("db", help="path to analysis/results.db")
    ap.add_argument("--sessions", nargs="*", default=["280726_1", "290726_0"])
    ap.add_argument(
        "--all-labels",
        action="store_true",
        help="do not filter to fly-like labels",
    )
    args = ap.parse_args()

    con = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    choice = find_table(con)
    print(f"# table: {choice.table} ({choice.n_rows} rows, style={choice.style})")
    print(f"# bbox columns: {choice.cols}")
    print(f"# session column: {choice.session_col}, label column: {choice.label_col}")

    where, params = [], []
    if choice.session_col and args.sessions:
        marks = ",".join("?" * len(args.sessions))
        where.append(f'"{choice.session_col}" IN ({marks})')
        params.extend(args.sessions)
    if choice.label_col and not args.all_labels:
        where.append(
            "(" + " OR ".join(f'LOWER("{choice.label_col}") LIKE ?' for _ in FLY_TOKENS) + ")"
        )
        params.extend(f"%{t}%" for t in FLY_TOKENS)

    sel = ", ".join(f'"{c}"' for c in choice.cols)
    sql = f'SELECT {sel} FROM "{choice.table}"'
    if where:
        sql += " WHERE " + " AND ".join(where)
    rows = con.execute(sql, params).fetchall()

    widths: list[float] = []
    heights: list[float] = []
    for a, b, c, d in rows:
        if None in (a, b, c, d):
            continue
        if choice.style == "wh":
            w, h = float(c), float(d)
        else:
            w, h = abs(float(c) - float(a)), abs(float(d) - float(b))
        if w <= 0 or h <= 0:
            continue
        widths.append(w)
        heights.append(h)

    longest = [max(w, h) for w, h in zip(widths, heights)]
    areas = [w * h for w, h in zip(widths, heights)]

    print()
    print("## Phase 0.1 -- confirmed fly crop scale")
    print(f"sessions: {', '.join(args.sessions) if args.sessions else 'all'}")
    print(summarise(widths, "crop width"))
    print(summarise(heights, "crop height"))
    print(summarise(longest, "longest edge"))
    print(summarise([a**0.5 for a in areas], "sqrt(area)"))
    if longest:
        med = statistics.median(longest)
        print()
        print(
            f"# note: median longest edge {med:.0f}px. A 224x224 classifier input "
            f"would upsample by {224/med:.1f}x."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
