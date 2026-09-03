# Transfontmation

A Progressive Web App for building a variable-font character from five
hand-drawn anchors — **extra thin**, **extra black**, **condensed**,
**wide**, and **regular** at the dead center of that weight x width space
— and watching every other weight and width fall out of the math rather
than being drawn separately.

This is a Kotlin/Compose Multiplatform (Wasm, web target) port of the
`corner-editor` tool originally prototyped in
[Azrienoch](https://github.com/HereLiesAz/azrienoch), rebuilt from scratch
as its own standalone client-only app: no server, no dependency on any
particular font project. Everything lives in the browser (`localStorage`,
plus JSON export/import for backup and sharing).

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

Every glyph is drawn at five fixed points in (weight, width) space:

```
              condensed (wdth=0)   wide (wdth=1)
extra thin  (wght=0)   extraThin        condensed*
extra black (wght=1)   extraBlack       wide*
                    regular (wght=0.5, wdth=0.5)
```

The four named drawings are the corners of that square; `regular` sits at
its exact center. Any other instance is produced by bilinear
interpolation of the four corners, plus a displacement term that pulls
the result toward `regular`'s hand-corrected shape at the center and
fades to nothing at the edges. That fade isn't a tunable curve — it's
forced: a real quadratic Bezier only passes through a chosen midpoint `M`
(with fixed endpoints `A`, `B`) if its control point is
`C = 2M - (A+B)/2`, and substituting that in simplifies to
`linear(t) + 4t(1-t) * (M - linear(0.5))`. So dragging Regular's point is
the only control a designer needs; the curve in between is a forced
consequence of that drag, not a second, independent decision. See
`Interpolation.kt` for the derivation.

Editing is deliberately confined to these five anchors — not to arbitrary
interpolated weights, the way most variable-font editors work. If an
automatic in-between shape looks wrong, the fix is to adjust `regular`,
not to add another editable point.

Interpolation requires the five anchors to be point-for-point compatible
(same contour count, same points per contour, same on/off-curve types) --
`compatibilityIssue()` checks this and the Preview panel reports exactly
where they disagree. "Copy [anchor] to other 4" seeds matching topology
so anchors can then be reshaped without adding or removing points.

## Project layout

- `Model.kt` -- the glyph data model (points, contours, corners)
- `Interpolation.kt` -- the interpolation math (bilinear + forced-Bezier displacement), independent of any UI
- `Geometry.kt` -- font-space <-> canvas-space mapping, outline path building
- `Hit.kt` / `Gestures.kt` -- point hit-testing and pointer-gesture handling (select, drag, rubber-band, draw)
- `EditorState.kt` -- Compose state holders (`AnchorState` per anchor, `AppState` overall)
- `AnchorCanvas.kt` / `Panels.kt` -- the actual UI
- `Storage.kt` -- `localStorage` persistence + JSON export/import
- `App.kt` / `Main.kt` -- top-level layout and the PWA entry point

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
