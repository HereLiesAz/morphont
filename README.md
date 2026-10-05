# Morphont

A Progressive Web App for building a variable-font character from a
handful of hand-drawn anchors -- each axis's two extremes, plus one
shared **regular** at the dead center of the whole design space -- and
watching every other value along every axis fall out of the math rather
than being drawn separately.


## Running it

```
./gradlew wasmJsBrowserDevelopmentRun
```

Opens a dev server (with hot reload) at `http://localhost:8080/`.

To build the production PWA bundle:

```
./gradlew wasmJsBrowserDistribution
```

Output lands in `build/dist/wasmJs/productionExecutable/` — a fully
static site (HTML, JS, Wasm, manifest, service worker, icons) you can
host anywhere. It's installable as a PWA from a supporting browser.

## The model

Every glyph is drawn at `2N + 1` fixed points for `N` axes -- each
axis's own two extremes, each varying **that axis alone** from the
center, plus the one shared `regular` at every axis's center at once.
With weight and width (the two easiest to draw on paper):

```
                     Extra Black (wght=1, wdth=0.5, ...)
                            |
Condensed (wght=0.5, wdth=0, ...) --- regular (0.5, 0.5, ...) --- Wide (wght=0.5, wdth=1, ...)
                            |
                     Extra Thin (wght=0, wdth=0.5, ...)

               (every other axis sweeps the same way, off this diagram)
```

Each axis's lo/hi extremes hold every *other* axis at Regular's own
value. This is deliberately **not** a joint grid of simultaneous
corners (there's no "simultaneously thin and condensed and slab-serif"
drawing) -- nobody draws that shape, and a real variable font's own
named instances (Thin, Black, Regular Condensed, ...) are structured
the same single-axis-from-center way, not as joint corners either.

The interpolated value at any point in the design space is the sum of
every axis's own forced-parabola curve, minus Regular counted
`N - 1` extra times so it isn't counted once per axis: an additive
combination, the same "sum the per-axis deltas from default" model
real OpenType variable fonts use internally (`gvar` tuples are
literally added together).

Each axis curve reproduces its own three points (an extreme, Regular, the
opposite extreme) exactly, and that curve isn't a tunable choice — it's
forced. A real quadratic Bezier only passes through a chosen midpoint `M`
(with fixed endpoints `A`, `B`) if its control point is
`C = 2M - (A+B)/2`, and substituting that in simplifies to
`linear(t) + 4t(1-t) * (M - linear(0.5))`. So dragging Regular's point is
the only control a designer needs; the curve in between is a forced
consequence of that drag, not a second, independent decision. See
`Interpolation.kt` for the derivation.

Editing is deliberately confined to these anchors — not to arbitrary
interpolated values, the way most variable-font editors work. If an
automatic in-between shape looks wrong, the fix is to adjust `regular`,
not to add another editable point. The Regular panel's travel-path
overlay only offers each axis's own sweep rather than an arbitrary pair
of anchors, since an axis's own lo/hi is the only pair guaranteed to
pass through Regular.

Every axis Morphont currently exposes is defined once, in `Model.kt`'s
`Axis.ALL` -- adding one is only ever a matter of appending another
`Axis` value there; the anchor naming, interpolation, UI, storage
format and variable-font import are all already general over however
many axes that list holds. Today, all 14: `wght` (Weight), `wdth`
(Width), Azrienoch's own `SERF` (Serif), and Roboto Flex's remaining
registered and parametric axes -- `GRAD` (Grade), `slnt` (Slant),
`opsz` (Optical), `XTRA` (Counters), `XOPQ`/`YOPQ` (X/Y Thickness),
`YTLC`/`YTUC` (x-height/Cap Height), `YTAS`/`YTDE` (Ascender/Descender),
and `YTFI` (Figures) -- for `2*14 + 1 = 29` total anchors. The axis
label (on the axis strip or filmstrip) opens a menu of all 14; the Preview
shows four sliders and expands to the rest.

Interpolation requires every anchor to be point-for-point compatible
(same contour count, same points per contour, same on/off-curve types) --
`compatibilityIssue()` checks this and the Preview panel reports exactly
where they disagree. "Copy [anchor] to other N" seeds matching topology
so anchors can then be reshaped without adding or removing points.

## Editing tools

- **Clipboard.** Copy / Cut / Paste / Select all, from each panel's toolbar,
  the Edit menu, or Ctrl/Cmd + C/X/V/A. The unit is whole contours (a glyph
  contour is always closed): every contour the selection touches is taken,
  or the whole outline when nothing is selected. Paste lands in place and
  selects what it pasted; the clipboard survives switching anchors and
  glyphs. Pasting changes point counts, so re-copy to the other anchors to
  keep interpolation alive. Also: Delete, Esc, arrow-key nudges (Shift = 10),
  `+`/`-`/`0` for zoom.
- **Touch proxy.** On touch input, a selection grows a pad ~76dp below it
  (above, near the bottom edge), joined by a dashed tether. Dragging the pad
  moves the selection 1:1 -- the finger never covers the nodes it moves.
  The selection pill's Move / Scale / Turn switch changes what the pad does:
  Scale grows the selection as you drag up, Turn rotates it as you drag
  sideways.
- **Transform box.** Two or more selected points get a dashed box whose
  handles sit just outside the points: corner squares scale (about the
  opposite corner), the knob above rotates. The selection pill adds flip
  ↔/↕ and ±15°. Point order never changes, so anchors stay compatible.
- **View.** A stable metric-based frame (it no longer refits while you drag),
  shared zoom/pan across panels: mouse wheel or pinch to zoom, two fingers to
  pan, View → Fit to reset.
- **Guides.** Static metric lines (ascender, cap, x-height, baseline,
  descender, origin/advance) from editable metrics (View → Metrics…; a font
  import brings its own `hhea`/`OS/2` values). Rulers on top and left: drag
  out of one to create a guide, drag a guide to move it, drop it back on a
  ruler to delete it. Optional grid (View → Grid step). **Measurements**
  draws every contour's width/height, each ghost's width, and the
  selection's extent, live -- for comparing the widths of a character's
  parts.
- **Snapping.** Light: within ~7px a dragged point (or a selection's edges/
  centre) pulls onto metrics, guides, other on-curve points, ghost points,
  and (more weakly) the grid; the line it caught flashes. View → Snap
  toggles it.
- **Ghosts.** Reference outlines behind every panel: primitive shapes, a
  character from any TrueType file (static or variable, scaled to this
  project's UPM; loaded per session), or a glyph from this project. A
  project glyph is ghosted **live**: each panel shows that glyph's own same
  anchor and the Preview interpolates it at the same slider values, so it
  always matches the working character's weight, width, and every other
  axis. Place mode: drag to move, corner squares to scale (about the
  opposite corner), the top knob to rotate; Flip ↔/↕ and ±15° buttons.
  Nodes mode edits the ghost's points (a live ghost detaches into a static
  copy first). Ghosts, guides and metrics save with the glyph.
- **Simplify.** Edit → Simplify shows a slider that removes points
  cheapest-first (distance from each point to the chord of its neighbours),
  with the original as a red hairline under the reduced fill and the worst
  drift in font units. When every anchor is compatible, the same points go
  from all of them, so interpolation survives. Apply or Cancel.

## Project layout

- `Model.kt` -- the glyph data model (points, contours, corners)
- `Interpolation.kt` -- the interpolation math (additive per-axis forced-Bezier), independent of any UI
- `Geometry.kt` -- font-space <-> canvas-space mapping, outline path building
- `Hit.kt` / `Gestures.kt` -- point hit-testing and pointer-gesture handling (zoom/pinch, touch proxy, ghost placement, rulers/guides, draw, drag with snapping, rubber-band)
- `Editing.kt` -- UI-free editing geometry: bounds, affine transforms, ghost shapes, clipboard extraction, snapping, node-reduction order
- `EditorState.kt` -- Compose state holders (`AnchorState` per anchor, `AppState` overall)
- `Editor.kt` -- the shared editor screen (phone and wide layouts) and the welcome screen, used by both shells
- `AnchorCanvas.kt` / `Panels.kt` / `Controls.kt` -- the canvas renderer, preview/thumbnail canvases, and the floating parts (selection pill, ghost bar, simplify card, axis strip, filmstrip, inspector sections, dialogs, keyboard shortcuts)
- `Icons.kt` -- the monoline icon set
- `Theme.kt` -- the palette and primitives (`MonoButton`, `IconAction`, `Caps`, `floating`, `HairSlider`), built on `HereLiesAz/convey` (see below)
- `Storage.kt` -- IndexedDB persistence (one record per glyph) + JSON export/import; migrates a legacy single-blob `localStorage` project automatically on first successful open
- `App.kt` / `Main.kt` -- top-level layout and the PWA entry point
- `VariableFont.kt` / `FamilyImport.kt` -- the from-scratch OpenType variable-font parser used to import a whole character family from one variable TTF

## Design

The glyph is the room. One full-bleed canvas shows the anchor being edited;
every control floats on it as a rounded, hairline-edged surface and stays out
of the way until needed.

- **Phone:** glyph name and node count up top, with Copy to anchors, Undo and
  the glyph browser; directly beneath, the **anchor filmstrip** (the picked
  axis's three anchors as live thumbnails -- tap one to edit it). A tool
  **dock** (Select, Pen, Guides, Ghosts, Simplify) at the bottom, and a
  picture-in-picture Preview that opens a sheet with the sliders.
- **Gestures:** two-finger pinch zooms, two-finger drag pans; one finger
  edits. Mouse wheel zooms on desktop.
- **Wide screens (web ≥900dp, tablets):** glyph tabs, a tool rail, an
  **anchor filmstrip** of live thumbnails under the canvas, and an inspector
  column (Preview + sliders, Ghosts, Guides).
- **Floating, contextual:** the selection pill (copy, cut, paste, on/off,
  select all, delete, with a size readout) only while something is selected;
  the ghost bar only while placing a ghost; the Simplify card only while
  simplifying; status as a transient toast.
- **Monochrome.** Hierarchy is carried by brightness: the selected node, the
  active tool and the anchor being edited are the whitest things on screen.
  On-curve nodes are rings, off-curve handles diamonds. The palette's one
  colour, `Mono.error`, is kept for errors.
- **Own parts:** a monoline icon set (`Icons.kt`, no icon library) and a
  hairline slider (`HairSlider`) in place of Material's thick one.
- **Logo:** `branding/logo-inverted.png` (light, for this dark UI) is the
  app icon, the web splash and the welcome mark; `branding/logo-light.png`
  is kept for a future light theme; `branding/logo-mono.png` is Android's
  themed (monochrome) icon.
- **Typeface:** [Azrienoch](https://github.com/HereLiesAz/Azrienoch)
  (SIL OFL 1.1; `licenses/Azrienoch-OFL.txt`) via
  [HereLiesAz/convey](https://github.com/HereLiesAz/convey)'s
  `conveyTypeFontFamily()`, with monospace small caps for readouts.
  `MonoButton` still registers its weight with `ConveySystem`'s hierarchy
  enforcement.

## Known gaps

- The clipboard is in-app only (not the OS clipboard), and Android's shell
  doesn't yet wire hardware-keyboard shortcuts.
- Reference fonts for ghosts are held for the session only and must be
  TrueType-outline (`glyf`); CFF/OTF isn't parsed.
- Metrics are stored per glyph (so imported fonts' values travel with each
  glyph) and carry over to the next opened glyph that has none of its own.

- Shift-click additive/toggle point selection isn't implemented --
  `PointerEvent.keyboardModifiers.isShiftPressed` didn't resolve against
  this Compose Multiplatform version's wasmJs pointer-input API, and
  rather than guess at an unconfirmed alternative this was dropped for
  now. Rubber-band selection (drag from empty space) still covers most
  multi-select needs.
- **Depends on `compose.conveyance:convey` resolving its Compose
  Multiplatform resources artifact correctly through GitHub Packages**
  (`build.gradle.kts` / `settings.gradle.kts`, authenticated with
  `GITHUB_ACTOR`/`GITHUB_TOKEN`). `build.gradle.kts`'s pinned commit
  must publish the wasmJs resources classifier a consumer's font
  loading needs, or the app compiles clean and then throws
  `MissingResourceException` trying to load Azrienoch at runtime.
- A project saved under the pre-N-axis anchor names (`extraThin`/
  `extraBlack`/`condensed`/`wide`) migrates automatically on load
  (`ProjectCodec.kt`'s `migrateGlyph`) to today's tag-based names
  (`wght_lo`/`wght_hi`/`wdth_lo`/`wdth_hi`); `regular` is unchanged in
  both schemes.
- Verified so far: shared and web code compile, the unit tests pass, and
  headless-Chromium screenshots of the phone and wide layouts match the
  approved mock-ups. Full interactive
  coverage (drag-to-reshape across every anchor, the travel-path overlay,
  save/export/import round-trips) has not yet had a dedicated automated
  test pass.
