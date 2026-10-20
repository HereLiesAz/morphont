# Morphont

A Progressive Web App for building a variable-font character from a
handful of hand-drawn anchors -- each axis's two extremes, plus one
shared **regular** at the dead center of the whole design space -- and
watching every other value along every axis fall out of the math rather
than being drawn separately.


## Running it

~~~
./gradlew wasmJsBrowserDevelopmentRun   # web, with hot reload at http://localhost:8080/
./gradlew run                           # desktop (Windows, macOS, Linux)
./gradlew :androidApp:installDebug      # Android
~~~

`./gradlew wasmJsBrowserDistribution` builds the static PWA into
`build/dist/wasmJs/productionExecutable/`; `./gradlew packageDistributionForCurrentOS` builds this
machine's desktop installer.

## Where to get it

- **Web:** <https://hereliesaz.github.io/morphont/> (installable as a PWA, works offline)
- **Android:** Google Play, and the APK on [GitHub Releases](https://github.com/HereLiesAz/morphont/releases)
- **Desktop:** `.msi`, `.dmg` (Apple silicon and Intel), `.deb` and `.rpm` on
  [GitHub Releases](https://github.com/HereLiesAz/morphont/releases)

Releases are built centrally -- see [`docs/RELEASING.md`](docs/RELEASING.md). Privacy policy:
[`docs/PRIVACY.md`](docs/PRIVACY.md) (nothing is collected; everything stays on your device).

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
pass through Regular. The overlay is off by default (it can look like
nodes to grab); the **Travel paths** chip under Guides turns it on.

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
(same contour count, same points per contour, same node/arm types) --
`compatibilityIssue()` checks this and the Preview panel reports exactly
where they disagree. "Copy [anchor] to other N" seeds matching topology
so anchors can then be reshaped without adding or removing points.

## Editing tools

- **Nodes and arms.** Outlines are cubic Béziers edited the PostScript /
  Photoshop way: every editable node has an in-arm and an out-arm.
  Straight segments have collinear controls at one-third and two-thirds
  of the edge, preserving the line while making both handles available.
  The point after a node is its out-arm; the one before it is its in-arm.
  A **smooth** node (drawn as a circle) keeps its arms mirrored: dragging
  one swings the other through the node at the same angle and length. A
  **corner** node (drawn as a square) leaves them independent. Alt-click a
  node, or long-press it on a touchscreen, to switch it between smooth and
  corner. Arm ends are small
  Xs. Moving a node carries its arms. The single corner/smooth
  action flips node type: going smooth re-aligns the two arms on the
  average of their directions, each at the average of their lengths.
  Deleting a node joins
  its neighbours with their own outer arms; deleting an arm retracts it
  onto its node.
- **Pen.** Tap places a corner node; press and drag places a smooth node
  and pulls out its arms, as in Photoshop. A neighbour without an arm of
  its own gets one sitting on that neighbour, so the segment is a cubic
  that still meets it without a tangent.
- **Format.** TrueType imports and projects saved before the cubic model
  (quadratic, with implied on-curve midpoints) convert exactly on load:
  each implied midpoint becomes a smooth node and each quadratic
  `Q(P0, C, P1)` the cubic `(P0, P0 + 2/3 (C - P0), P1 + 2/3 (C - P1), P1)`.
  Smooth curves are assumed wherever possible: any other node whose two
  arms already leave it in opposite directions (within about 2 degrees)
  also comes in smooth.
  Saves carry `"outlineFormat": 2` so they are never converted twice.
- **Clipboard.** Copy / Cut / Paste / Select all, from the tool dock,
  or Ctrl/Cmd + C/X/V/A. The unit is whole contours (a glyph
  contour is always closed): every contour the selection touches is taken,
  or the whole outline when nothing is selected. Paste lands in place and
  selects what it pasted; the clipboard survives switching anchors and
  glyphs. Pasting changes point counts, so re-copy to the other anchors to
  keep interpolation alive. Also: Delete, Esc, arrow-key nudges (Shift = 10),
  `+`/`-`/`0` for zoom.
- **Node move circle.** Selecting a node (or one of its curve handles)
  offers a cross-circle. It sits outward -- away from the glyph's centre,
  through the selection -- and as far out as it takes for no other node or
  handle to be under its touch area (trying nearby outward angles if the
  straight line is blocked or off the canvas), and it keeps that spacing
  while dragged. It responds from the first touch, never vibrates, and goes
  away on the next touch anywhere else on the screen (the selection stays).
  **Guides → Move circle**
  toggles it in every platform layout; the default follows pointer type
  (on for touch, off for mouse/stylus) until the user chooses explicitly.
  The complete circle and 30% margin are reserved exclusively for moving
  selected on-curve nodes and their two handles. It cannot activate scale,
  rotate, point selection, guides, panning, snapping or pinch navigation.
  Dragging the circle moves exactly what is selected: a selected handle moves
  by itself (its twin re-mirrors on a smooth node), a selected node moves with
  its handles.
  The circle is hidden during pen drawing, ghost placement and simplify.
- **Simplify.** Every stage stays as close as possible to the ORIGINAL
  outline. Taking a node out refits the stretch between its surviving
  neighbours to the original curve (end points and tangent directions kept,
  handle lengths solved by least squares), and each candidate is scored by its
  worst distance from the original -- never from the previous step -- so error
  cannot pile up. A node that is redundant (collinear, or the join of one exact
  cubic) comes out with no change to the shape at all; beyond that, no cubic
  with fewer nodes can match the outline exactly, so the slider shows the
  smallest deviation that is possible. A smooth node whose handles can no
  longer mirror becomes a corner node on the same tangent, so the drawn curve
  never moves. It ranks by worst drift across compatible anchors.
  Normal reduction retains three nodes per contour. At the extreme end of the
  slider it can discard smaller contours and their counters, leaving three
  on-curve nodes in the whole glyph. Handles never count as nodes. The displayed
  `initial → remaining` count is clamped to the planned number of deletions,
  so moving the slider right cannot increase the count. The preview shows
  the lost geometry before Apply, which remains undoable.
- **Tool names.** The active tool in the dock or rail names itself in small
  text beneath its icon, and only while it is active.
- **Pointer actions.** Copy, Cut, Paste, Delete, smooth/corner, line/curve,
  Select all, flip and rotate are ordinary tools in the phone dock and the
  desktop rail -- always present, dimmed until they apply (there is no separate
  node toolbar). Selection hit-testing
  takes priority over overlapping transform handles, so dragging a node
  cannot accidentally start a box scale. The straight/curve toggle checks
  collinear cubic controls (all nodes have handles); converting a line to
  a curve now creates visible curvature, and back to a line is reversible.
- **Guides and measurement.** Measurement overlays are on by default;
  their toggle remains under **Guides**, rather than duplicating a tool in
  the desktop rail.
- **Navigation.** Desktop glyph tabs retain their original order when you
  switch glyphs. They scroll by trackpad/wheel, visible scrollbar, or
  left/right glyph-strip buttons. The inspector, glyph pickers, welcome
  page, overflowing phone tool dock and modal dialogs scroll when needed.
  Scrollbars use one shared pointer recognizer for tap/drag. Middle- or
  right-mouse dragging pans empty canvas space (including during simplify);
  a gesture beginning on the cross-circle moves only its node even with a
  secondary mouse button. The wheel zooms. Desktop zoom percentage is
  editable (Enter to confirm) or draggable (10–4000%). Reset View (0)
  resets pan and zoom.
- **Font overview.** In the phone's scrollable dock or desktop header,
  open the full-screen **Font** grid to inspect all glyphs as real
  interpolated outlines. Its shared Weight, Width and other axis sliders
  adjust **every glyph's preview together**. Tap a tile for an enlarged
  instance dialog with independent sliders. **Apply instance to all** copies
  that glyph's preview axes to the entire grid; **Edit glyph** opens the
  existing node editor. These controls are non-destructive previews:
  changing instances does not overwrite any stored anchor geometry.
- **Independent ghost axes.** Ghosts linked to another project glyph can
  override Weight, Width and the other axes using the Ghosts panel's
  axis button. Unset axes continue following the selected anchor and
  global preview. A reference glyph imported from a variable TTF retains
  its source masters inside the project, so axis changes survive reopening
  without the original font file. Static references have no invented
  variation axes. The Ghosts panel's action rows scroll horizontally with
  visible scrollbars on small screens. All changes remain undoable.
- **Node Move (M).** The dedicated move tool selects on-curve nodes only.
  Even grabbing a nearby handle moves its owning node and both handles rather
  than reshaping a control point. Select (V) always exits read-only simplify,
  ghost editing/placement and pen drawing so the actual glyph becomes editable
  again; the original geometry and current zoom remain intact. Move Node (M)
  similarly restores an editable glyph when those modes block selection.
  Pen (P) starts drawing. Desktop arrow keys always nudge whole nodes,
  including when just a handle is selected. Trash or Delete removes the full
  node with its handles when the selected target is either node or handle.
- **TrueType import.** Both static and variable `.ttf` files are accepted.
  Static font outlines populate every anchor identically; variable fonts
  sample their supported axis extremes. Imports never overwrite existing
  glyph records: collisions are skipped and counted. The desktop parser
  runs off the UI thread. The parser validates table bounds,
  cmap glyph IDs, per-glyph loca/glyf spans, and gvar tuple payloads before
  reading. A malformed individual glyph is skipped and reported without
  aborting otherwise importable characters; invalid font-wide tables reject
  the font with a descriptive error. A font with no importable characters is
  rejected rather than saving an empty import. This does not add CFF/OTF
  support or repair malformed font geometry.
- **Glyph labels.** UFO production identifiers such as `uni0041`,
  `unicode0041` and `u1F600` display as their Unicode characters in the
  browser, tabs and editor header. The original glyph name remains the saved
  key and is still used for opening, linking and export; older imports are
  displayed correctly without deleting or renaming any glyphs. Non-printing,
  invalid and alternate names remain literal.
- **UFO import (desktop).** In **Glyphs & file → Import UFO**, select a
  `.ufo` directory (not a GLIF by itself) or `.ufoz` ZIP archive. Reads UFO
  2/3 default-layer `glyphs/contents.plist`, GLIF glyph names, per-glyph
  advances, font metrics, closed cubic/quadratic contours and nested affine
  components. Quadratic curves convert exactly to editable cubic handles;
  components are decomposed into contours. Each anchor starts as an
  independently editable copy of the master, not a multi-master designspace.
  Skipped glyphs (e.g. unsupported open contours or component cycles) are
  reported instead of being silently altered. Extra UFO layers, kerning,
  custom lib metadata, hints and Unicode mappings are not retained by
  Morphont's existing glyph-project format. Android/web UFO import is not
  yet available; their TTF import remains unchanged.
- **Projects.** Work happens inside a named project: a start screen asks
  for one (or reopens the last), and **Glyphs → Projects** starts, opens,
  renames or deletes them (deleting asks first). Each project is its own set
  of glyphs -- a folder on Android and desktop, an IndexedDB database on the
  web -- and everything autosaves into the project that is open; switching
  saves the open glyph first. Glyphs saved before projects existed become a
  project called "Untitled" on first launch. Opening a project `.json`
  creates a new project from it instead of overwriting the open one.
- **Desktop glyph filenames.** Forbidden Windows characters (notably `*`)
  and reserved DOS device names are reversibly escaped on disk, and so are
  ASCII capitals, so `A` and `a` stay distinct files on case-insensitive
  Windows/macOS volumes. Files saved under the older scheme (plain `A.json`)
  are renamed on first launch. Individual saves have unique
  temporary files and atomic replacement where supported. Project imports
  stage all glyph files and restore the previous directory if installation
  fails. Project exports include the currently edited glyph even before
  its autosave debounce expires. Bulk project replacements invalidate
  outstanding autosaves on all three platforms.
- **Simplify input.** The floating simplify control uses Compose's native
  slider gestures, plus **−1** and **+1** buttons for exact node-by-node
  adjustment. The preview remains non-destructive until **Apply**.
- **Transform box.** Two or more selected points get a dashed box whose
  handles sit just outside the points: corner squares scale (about the
  opposite corner), the knob above rotates. The tool dock adds flip
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
  centre) pulls onto metrics, guides, other nodes, ghost nodes,
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
  copy first; Undo restores its link and placement). The ghost action bar
  has a visible horizontal scrollbar. Editing a ghost is entered only from
  its **Edit** button in
  the Ghosts panel; while it lasts the ghost draws solid and the working
  glyph as a bare outline, and the glyph is locked -- gestures, the
  tool dock, keys, Copy to anchors, Simplify and suggestions all
  apply to the ghost or are disabled. Never both at once. While a ghost is only
  being placed (Place mode), its nodes are hidden, so node commands (delete,
  nudge, cut, paste, select all) do nothing. Undo is one chronological stack
  across all anchors, ghosts and guides: it reverts whatever was done last, even
  on an anchor that is not on screen. Ghosts, guides and metrics save with the glyph.
- **Simplify preview is read-only.** While the Simplify card is open the glyph
  cannot be edited: node keys, cut/paste, Undo and the pen are ignored until
  you Apply or Cancel. The original outline shows as a white dashed line under
  the reduced fill (see **Simplify** above for the ranking).

- **Suggested edits.** The sparkle button (header on phones, tool rail on
  wide screens) shows how many edits apply right now; tap it to expand a
  row of one-tap, undoable chips. Recomputed a beat after the outline stops
  changing:
  - *Generate* -- on an extreme anchor, derive it from Regular: embolden /
    lighten (point-preserving offset), condense / extend (stems kept),
    italicize (12° slant), grade, X/Y thickness, x-height, cap, ascender,
    descender. Same points, same order, so it stays interpolatable.
  - *Cleanup* -- merge duplicate **on-curve** nodes (skipping their handles),
    remove points that add no shape and fix contour direction (all compatible
    anchors share topology).
  - *Alignment* -- snap points a few units off a metric line; straighten
    nearly vertical/horizontal node-to-node spans even when each segment
    has two Bézier handles.
  - *Consistency* -- sample true Bézier ink boundaries for stem widths and
    contour nesting; match stems that almost agree and centre on the advance.
    Offset operations carry both handles with their owning nodes rather than
    deforming smooth/mirrored pairs.
  - *Interpolation* -- when anchors disagree: copy this anchor to all, or
    jump to the first mismatch.

## Project layout

- `Model.kt` -- glyph data model, serializable per-ghost axis overrides and embedded variable-font reference masters
- `FontOverview.kt` -- lazy font-wide glyph grid, independent enlarged glyph dialogs, and shared or local variable-axis preview sliders
- `commonTest/FontOverviewTest.kt` -- touch-default circle preference, global versus per-glyph previews, independent ghost axes and project round-trip
- `Interpolation.kt` -- the interpolation math (additive per-axis forced-Bezier), independent of any UI
- `Geometry.kt` -- font-space <-> canvas-space mapping, outline path building
- `Hit.kt` / `Gestures.kt` -- point hit-testing and pointer-gesture handling (zoom/pinch, touch proxy, ghost placement, rulers/guides, draw, drag with snapping, rubber-band)
- `Outline.kt` -- the cubic node/arm model: arm lookup, mirrored drags, corner/smooth, line/curve, valid deletion, pen nodes, exact quadratic-to-cubic conversion
- `Editing.kt` -- UI-free editing geometry: bounds, affine transforms, ghost shapes, clipboard extraction, snapping, node-reduction order
- `EditorState.kt` -- Compose state holders (`AnchorState` per anchor, `AppState` overall)
- `Editor.kt` -- the shared editor screen (phone and wide layouts) and the welcome screen, used by both shells
- `AnchorCanvas.kt` / `Panels.kt` / `Controls.kt` -- the canvas renderer, preview/thumbnail canvases, and the floating parts (ghost bar, simplify card, axis strip, filmstrip, inspector sections, dialogs, keyboard shortcuts)
- `Icons.kt` -- the monoline icon set
- `Suggestions.kt` -- the suggested-edits engine (outline offset, stem measurement, and each suggestion)
- `Theme.kt` -- palette and primitives (`MonoButton`, `IconAction`, `Caps`, `floating`, `HairSlider`, draggable `Scrollbar`), built on `HereLiesAz/convey` (see below)
- `commonTest/ControlAuditTest.kt` / `desktopTest/DesktopStorageTest.kt` -- regression coverage for mixed undo, suggestions, curve measurements, ghost linking, and transactional desktop projects
- `desktopMain/` -- desktop window, per-user storage (Windows-safe glyph filenames), UFO/ZIP import and platform file pickers
- `Projects.kt` / `ProjectsUi.kt` -- the project list (`ProjectRegistry`, platform-agnostic) and the start screen / switcher; `commonTest/ProjectRegistryTest.kt` and `desktopTest/ProjectStorageTest.kt` cover it
- `Storage.kt` -- IndexedDB persistence (one database per project, one record per glyph) + JSON export/import; migrates a legacy single-blob `localStorage` project automatically on first successful open
- `App.kt` / `Main.kt` -- top-level layout and the PWA entry point
- `VariableFont.kt` / `FamilyImport.kt` -- static and variable TrueType-outline glyph family import
- `commonTest/TrueTypeImportTest.kt` -- independently constructed SFNT fixture for damaged-glyph recovery, table bounds, and end-of-buffer reads
- `desktopMain/UfoImport.kt` -- secure XML plist/GLIF parser and directory/ZIP UFO package import; component expansion and curve conversion
- `desktopTest/UfoImportTest.kt` -- UFO/UFOZ, geometry, metrics, component, filename-safety regression tests
- `Format.kt` / `commonTest/FormatTest.kt` -- Unicode presentation for legacy production names, independent of glyph identity

## Design

The glyph is the room. One full-bleed canvas shows the anchor being edited;
every control floats on it as a rounded, hairline-edged surface and stays out
of the way until needed.

- **Phone:** glyph name and node count up top, with Copy to anchors, Undo and
  the glyph browser; directly beneath, the **anchor filmstrip** (the picked
  axis's three anchors as live thumbnails -- tap one to edit it). A tool
  **dock** (Select, Move node, Node tools, Pen, Reset View, Guides, Ghosts,
  Simplify) at the bottom; it scrolls horizontally as needed. Preview opens a sheet
  with the sliders.
- **Gestures:** two-finger pinch zooms, two-finger drag pans; one finger
  edits. Mouse wheel zooms on desktop.
- **Wide screens (web ≥900dp, tablets):** glyph tabs, a tool rail, an
  **anchor filmstrip** of live thumbnails under the canvas, and an inspector
  column (Preview + sliders, Ghosts, Guides).
- **Floating, contextual:** the node actions live in the tool rail/dock and
  are dimmed until a selection exists. The ghost bar
  appears only while placing a ghost; the Simplify card only during a
  preview; status appears as a transient toast.
- **Monochrome.** Hierarchy is carried by brightness: the selected node, the
  active tool and the anchor being edited are the whitest things on screen.
  Smooth nodes are circles, corner nodes squares (filled when selected),
  off-curve handle tips are X marks. The palette's one
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

- The clipboard is in-app only (not the OS clipboard). Android's root now
  receives keyboard focus for editor shortcuts; actual hardware combinations
  still need device-level verification.
- Reference fonts for ghosts are held for the session only and must be
  TrueType-outline (`glyf`); CFF/OTF isn't parsed.
- Metrics are stored per glyph (so imported fonts' values travel with each
  glyph); glyphs without saved metrics use the default FontMetrics instead
  of leaking the previously opened glyph's values. Guides, metrics,
  ghost add/remove, linked/static ghost mode changes and geometry edits are
  all part of chronological undo history.

- Shift-click toggles a node/handle in the current selection, and
  Shift-marquee adds points without clearing the previous selection.
  Multi-touch gesture interaction still requires device-level testing.
- Native UFO import is currently desktop-only. Android and web do not
  yet offer a cross-platform UFO directory/ZIP parser; their TrueType
  import remains available.
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
- Current control-audit fixes include source-level checks and new
  regression cases; Gradle builds, interactive mouse/touch tests, Android
  hardware-keyboard tests and cross-platform file round-trips still require
  execution. Do not infer validation from a successful source commit.
