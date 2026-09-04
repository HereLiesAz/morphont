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
With today's three axes (weight, width, serif):

```
                     Extra Black (wght=1, wdth=0.5, SERF=0.5)
                            |
Condensed (wght=0.5, wdth=0, SERF=0.5) --- regular (0.5, 0.5, 0.5) --- Wide (wght=0.5, wdth=1, SERF=0.5)
                            |
                     Extra Thin (wght=0, wdth=0.5, SERF=0.5)

                         (Sans/Slab sweep Serif alone, off this diagram)
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
many axes that list holds. Today: `wght` (Weight), `wdth` (Width), and
Azrienoch's own `SERF` (Serif). Roboto Flex's remaining axes (`GRAD`,
`slnt`, `opsz`, `XTRA`, `XOPQ`, `YOPQ`, `YTLC`, `YTUC`, `YTAS`, `YTDE`,
`YTFI`) are a planned fast-follow, not yet added.

Interpolation requires every anchor to be point-for-point compatible
(same contour count, same points per contour, same on/off-curve types) --
`compatibilityIssue()` checks this and the Preview panel reports exactly
where they disagree. "Copy [anchor] to other N" seeds matching topology
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
- **Depends on `com.github.HereLiesAz.convey:convey` resolving its
  Compose Multiplatform resources artifact correctly through JitPack**,
  which required a fix on `convey`'s own side (no `jitpack.yml` existed
  there before, so JitPack's default build died on that project's
  `androidTarget` -- no Android SDK on JitPack's runner -- before ever
  publishing the wasmJs resources classifier a consumer's font loading
  needs; see HereLiesAz/convey#26). `build.gradle.kts`'s pinned commit
  must be that fix or later, or the app compiles clean and then throws
  `MissingResourceException` trying to load Azrienoch at runtime.
- A project saved under the pre-N-axis anchor names (`extraThin`/
  `extraBlack`/`condensed`/`wide`) migrates automatically on load
  (`Storage.kt`'s `migrateGlyph`) to today's tag-based names
  (`wght_lo`/`wght_hi`/`wdth_lo`/`wdth_hi`); `regular` is unchanged in
  both schemes.
- Verified so far: the production build compiles and bundles cleanly, and
  a manual smoke pass in headless Chromium confirms the axis-selector
  layout renders and switches correctly (Weight/Width/Serif), glyph
  creation and naming work, contour drawing places points on the correct
  anchor, and the live compatibility-mismatch message updates correctly.
  Full interactive coverage (drag-to-reshape across every anchor, the
  travel-path overlay, save/export/import round-trips) has not yet had a
  dedicated automated test pass.
