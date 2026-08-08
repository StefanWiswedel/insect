---
name: biomon-ui
description: The Biomon visual design system — colour tokens, typography and state semantics for the insect trap and the bird station. Use when writing or reviewing any UI in either app.
---

# Biomon UI

**Shared between the insect trap and the bird station. Neither app changes it
unilaterally.** If a screen needs something this file does not cover, the file
changes first and both apps follow. A token that exists in one app and not the
other is a bug in whichever diverged.

This copy lives in the insect repo so it can be read without fetching another
one; it is a mirror, not a fork.

## Concept

**Nocturnal field ledger.** Warm near-black ground, specimen-label typography,
mostly still. The visual peak is reserved for rare events.

"Mostly still" is the operative word. These are instruments read at a glance —
in sun, outdoors, usually by someone already walking away — not apps to be
browsed. Motion, colour and weight are budget, and almost all of it is unspent
almost all of the time.

## Colour

| Token | Value | Use |
| --- | --- | --- |
| `--bg` | `#100D0B` | Base ground |
| `--bg-raised` | `#1A1512` | Raised surfaces |
| `--bg-sunken` | `#0A0807` | Sunken wells |
| `--ink` | `#F4EDE2` | Primary text |
| `--ink-soft` | `#A99B8B` | Secondary text |
| `--ink-faint` | `#6B5F55` | Tertiary, labels, disabled |
| `--alive` | `#9CC471` | Confirmed, capturing, healthy state |
| `--ember` | `#E8A33D` | Activity, warnings, the mask overlay |
| `--signal` | `#FF6B4A` | **RESERVED** — never on ordinary UI |

### `--signal` is reserved

For rare significant events only. In the insect trap that means **at most three
states, all terminal**:

* thermal stop
* disk stop
* camera error

Not warnings. Not degradations. Not "recording". Not low battery — that is a
*planned* graceful shutdown rather than a fault, and takes `--ember`.

If a fourth use appears, check whether all four are genuinely terminal. Usually
one of them is not, and the colour is being spent on something ordinary. Once
`--signal` means "something is a bit wrong", the screen has lost its only way of
saying "this is broken", which is the one thing it must be able to say from
across a field.

### Candidate state gets no colour

**Absence of colour means "not yet real".** Anything unconfirmed uses
`--ink-soft` and nothing else.

This is load-bearing in the insect trap, not decorative. A blob that has crossed
the illumination *suspect* gate but has not collected its corroborating signals
has been captured, but it has not been confirmed — and the first sessions
recorded exactly that class of thing as detections when they were artefacts. It
must not read as a catch. Same for warm-up: the background model has not
converged, so nothing it says is real yet.

### Spectrogram ramp

For anything plotted, in either app:

```
#100D0B → #3B2416 → #8A4A1E → #E8A33D → #FFF3D6
```

The insect trap plots nothing today. The ramp is recorded here so that if it
ever does, it stays in the family instead of acquiring a viridis.

## Typography

| Face | Use |
| --- | --- |
| **Fraunces** | Display and headings. Has a WONK axis — use it sparingly. |
| **Instrument Sans** | UI body |
| **Martian Mono** | **All** numerals, tabular figures, always |

**Never Inter, Roboto, Arial or Space Grotesk.**

That rules out the Android system families too: `FontFamily.SansSerif` *is*
Roboto and `FontFamily.Default` resolves to it. There is no compliant fallback,
which is why the faces are bundled rather than fallen back to.

### Numerals

Every number that can change while the screen is up uses Martian Mono with
`tnum`. Not a preference: a reading whose glyph widths change as the value
changes makes the row twitch, and on a screen read at a glance to answer "is this
still working", movement in the layout reads as movement in the data.

### Bundling

Ship the `.ttf` files in `app/src/main/res/font/`. **Do not use the
downloadable-fonts certificate array.** A field instrument must not depend on
Play Services to render its own numbers — the phone is in a box behind a stick
pile and the failure mode is silent.

All three are SIL Open Font License 1.1. Include the licence files; they ship in
`app/src/main/assets/licenses/`.

Sources:

* Fraunces — `github.com/undercasetype/Fraunces`
* Instrument Sans — `github.com/Instrument/instrument-sans`
* Martian Mono — `github.com/evilmartians/mono`

Fraunces ships several optical-size cuts. Use the **9pt** cut for on-screen
headings: the 72pt and 144pt cuts are display sizes with higher stroke contrast,
which is the wrong trade for direct sun.

## Hierarchy

Three tiers, in the order the field actually asks for them:

1. **State.** First on the screen, and the only thing carrying colour when
   everything is fine.
2. **The numbers that decide whether the rig survives the day.** In the insect
   trap: free space, battery, temperature. Large, and coloured only when their
   own guard trips — each independently, so disk pressure ambers the free-space
   figure and nothing else.
3. **Everything else.** Small. Present to be auditable, not to be read before
   walking away.

## What this file does not specify

Recorded so the next person knows which values are authored and which were
derived locally:

* Surface elevation steps beyond the three ground tokens
* Spacing scale
* Type scale (sizes, line heights, weights)
* Material 3 container and outline roles

The insect trap derives these in `app/src/main/kotlin/dk/biomon/insect/ui/`
(`Theme.kt`, `Type.kt`). If the bird station has derived them differently, they
have already diverged and this file should absorb whichever is better.
