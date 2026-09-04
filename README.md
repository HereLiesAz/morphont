# Morphont

A Progressive Web App for building a variable-font character from five
hand-drawn anchors — **extra thin**, **extra black**, **condensed**,
**wide**, and **regular** at the dead center of that weight x width space
— and watching every other weight and width fall out of the math rather
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

Every glyph is drawn at five fixed points, each varying only **one axis**
from the center:

```
                     extraBlack (wght=1, wdth=0.5)
                            |
condensed (wght=0.5, wdth=0) --- regular (0.5, 0.5) --- wide (wght=0.5, wdth=1)
                            |
                     extraThin (wght=0, wdth=0.5)
```

`extraThin`/`extraBlack` are the weight extremes at regular width;
`condensed`/`wide` are the width extremes at regular weight. This is
deliberately **not** a 2x2 grid of four joint corners (there's no
"simultaneously thin and condensed" drawing) -- nobody draws that shape,
and a real variable font's own named instances (Thin, Black, Regular
Condensed, ...) are structured the same single-axis-from-center way, not
as joint corners either.

The interpolated value at any (weight, width) is the weight axis's
forced-parabola curve plus the width axis's, minus Regular once so it
isn't counted twice: an additive combination, the same "sum the per-axis
deltas from default" model real OpenType variable fonts use internally
(`gvar` tuples are literally added together).

Each axis curve reproduces its own three points (an extreme, Regular, the
opposite extreme) exactly, and that curve isn't a tunable choice — it's
forced. A real quadratic Bezier only passes through a chosen midpoint `M`
(with fixed endpoints `A`, `B`) if its control point is
`C = 2M - (A+B)/2`, and substituting that in simplifies to
`linear(t) + 4t(1-t) * (M - linear(0.5))`. So dragging Regular's point is
the only control a designer needs; the curve in between is a forced
consequence of that drag, not a second, independent decision. See
`Interpolation.kt` for the derivation.

Editing is deliberately confined to these five anchors — not to arbitrary
interpolated weights, the way most variable-font editors work. If an
automatic in-between shape looks wrong, the fix is to adjust `regular`,
not to add another editable point. The Regular panel's travel-path
overlay only offers the two axis sweeps (Weight, Width) rather than an
arbitrary pair of anchors, since those are the only two pairs guaranteed
to pass through Regular.

Interpolation requires the five anchors to be point-for-point compatible
(same contour count, same points per contour, same on/off-curve types) --
`compatibilityIssue()` checks this and the Preview panel reports exactly
where they disagree. "Copy [anchor] to other 4" seeds matching topology
so anchors can then be reshaped without adding or removing points.

## Project layout

- `Model.kt` -- the glyph data model (points, contours, corners)
- `Interpolation.kt` -- the interpolation math (additive per-axis forced-Bezier), independent of any UI
- `Geometry.kt` -- font-space <-> canvas-space mapping, outline path building
- `Hit.kt` / `Gestures.kt` -- point hit-testing and pointer-gesture handling (select, drag, rubber-band, draw)
- `EditorState.kt` -- Compose state holders (`AnchorState` per anchor, `AppState` overall)
- `AnchorCanvas.kt` / `Panels.kt` -- the actual UI
- `Theme.kt` -- the visual language, built on `HereLiesAz/convey` directly (see below)
- `Storage.kt` -- `localStorage` persistence + JSON export/import
- `App.kt` / `Main.kt` -- top-level layout and the PWA entry point
- `VariableFont.kt` / `FamilyImport.kt` -- the from-scratch OpenType variable-font parser used to import a whole character family from one variable TTF

## Design

Built directly on [HereLiesAz/convey](https://github.com/HereLiesAz/convey)
-- the Compose Multiplatform implementation of the Conveyance manifesto
-- as a real Gradle dependency (`build.gradle.kts`, resolved through
JitPack since `convey` isn't published to Maven Central), not a
reimplementation of its ideas:

- **Color.** A semantic role vocabulary (surface/onSurface/outline/
  primary/secondary/tertiary/error) filled with Morphont's own values
  rather than `ConveyColor`'s own reference palette -- its own doc
  comment says as much ("match your brand colors to these roles, not
  to arbitrary hex values"). Its actual rule for color itself --
  "Dynamic Color: use contrasting primary, secondary, and tertiary
  tones to prioritize actions implicitly" -- is spent deliberately
  rather than everywhere: most of the interface stays a dark,
  near-neutral ground, and color marks only the few things that carry
  real hierarchy. `Mono.primary` (indigo) marks the active corner
  panel and a selected point, `Mono.secondary` (verdigris) marks
  off-curve handles, `Mono.tertiary` (brass) marks the Preview panel
  alone -- and `Mono.error` (the palette's only red) stays visually
  unambiguous from `Mono.primary` precisely because primary isn't a
  shade of red too.
- **Shape.** `MonoButton` uses `ConveyShape.CutSmall` -- the library's
  own chamfered-corner token, not a locally redefined lookalike.
- **Hierarchy enforcement.** Every `MonoButton` tags itself with
  `Modifier.conveyWeight(ConveyWeight.Primary/.Secondary)`, and the
  whole app is wrapped in `ConveySystem` (`App.kt`), which actually
  enforces that hierarchy at runtime -- too many `Primary`-weighted
  elements on screen at once throws in debug builds, the same as it
  would in any other Conveyance-built surface. The visual weight
  (`Mono.primary`'s color) and the structural weight are the same
  claim, not two independent ones that could drift apart.
- **Typeface.** The UI is set in [Azrienoch](https://github.com/HereLiesAz/Azrienoch)
  (SIL OFL 1.1; `licenses/Azrienoch-OFL.txt` documents the license
  here too) -- the multiplex variable font this tool exists to help
  shape -- loaded via `convey`'s own `conveyTypeFontFamily()`
  (`tokens/ConveyType.kt`) directly: the actual composable and its
  actual bundled font resource, not a copy of either. `convey`'s
  pinned Compose Multiplatform version (1.8.2, which this project
  matches -- see `build.gradle.kts`'s own comment on why the two must
  agree) supports `Font(variationSettings = ...)`, so this is Azrienoch
  rendered with real, live `wght`/`wdth` control, not a single baked
  instance.

## Known gaps

- Shift-click additive/toggle point selection isn't implemented --
  `PointerEvent.keyboardModifiers.isShiftPressed` didn't resolve against
  this Compose Multiplatform version's wasmJs pointer-input API, and
  rather than guess at an unconfirmed alternative this was dropped for
  now. Rubber-band selection (drag from empty space) still covers most
  multi-select needs.
- Verified so far: the production build compiles and bundles cleanly, and
  a manual smoke pass in headless Chromium confirms the five-panel layout
  renders, glyph creation and naming work, contour drawing places points
  on the correct anchor, and the live compatibility-mismatch message
  updates correctly. Full interactive coverage (drag-to-reshape across all
  five anchors, the travel-path overlay, save/export/import round-trips)
  has not yet had a dedicated automated test pass.
