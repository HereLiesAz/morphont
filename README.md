# Morphont

A variable-font glyph editor -- on Android, Windows, macOS, Linux and the
web (a Progressive Web App) -- for building a variable-font character from a
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
interpolated values, the way most variable-font editors work. The work is
done at the **extremes**: they are the drawings. `regular` is derived from
them (see *Regular follows the extremes* below) and is nudged, never
drawn. If an automatic in-between shape looks wrong, the fix is a small move
of `regular`, not to add another editable point. The Regular panel's travel-path
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
where they disagree. An axis only varies once **both** its extremes are drawn;
until then it is inert (both its extremes read as Regular), so a glyph with
Extra Thin and Extra Black drawn needs nothing copied to the other 26 anchors.
To get the second extreme of an axis, **Start the other extreme from this one**
copies the open extreme into its empty partner and opens it to be reshaped
without adding or removing nodes. It never overwrites a drawn extreme and
never starts from Regular.

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
- **Free Transform Points.** Select two or more nodes and a box appears around
  them (as with Photoshop's Ctrl+T on selected anchors): drag a corner to scale
  about the opposite corner, an edge to scale along one axis, the knob above it
  (or, with a mouse, just outside a corner) to rotate about the centre. Shift
  keeps proportions / snaps rotation to 15°, Alt pivots on the centre, and
  Ctrl/Cmd on an edge skews. Each drag is one undo step (no separate commit).
- **Menu.** The ⋯ at the top opens a menu instead of the glyph list. **Go to**:
  Glyphs and files, Projects, Font overview, **Font store** and (on a phone, where they are
  sheets) Preview, Ghosts, Guides and metrics. **Theme**: Dark or Light.
  **View and settings**: Snap, Metrics, Rulers, Guides, Measurements, Ghosts,
  Travel paths, Move circle and Grid, each a checkable item that leaves the menu
  open so several can be flipped at once, then **Grid step** (10, 25, 50 or 100
  units). The desktop top bar has the same menu without the panel shortcuts
  (those panels are always on screen there).
- **Getting started.** The start screens open with the idea in a line and a
  **How Morphont works** button (also under **Help** in the menu): three
  thumbnails of one axis, and the anchors, Regular and Match steps in plain
  words. A project's first screen offers **Import a TrueType font**, **Import
  UFO** (desktop) and **Open a Morphont project** straight away -- each makes
  its own project -- and an open, empty project offers **Try the sample**, an
  "o" already drawn on every anchor so the Preview moves at once. **Help →
  Keys and gestures** lists every key and the touch gestures no button shows.
- **Every control names itself.** Hover (or long-press) any icon for its name;
  a disabled one says why ("select a node first", "finish the contour first").
  Icon buttons come in three sizes (28, 40, 48 dp; the phone dock is all 48),
  take keyboard focus with a ring, and report their selected state to screen
  readers; anchors, glyph tiles, chips and segmented controls carry button,
  tab or switch roles, and the canvas describes the anchor it shows. The
  Match menu explains each item and says when there's nothing to do.
- **Rename and delete glyphs.** **Glyphs and files** has a **This glyph** row
  for the open glyph: **Rename** (the new name keeps the edits and undo
  history; an existing name is refused) and **Delete** (asks first; the next
  glyph opens). **Replace this glyph from .json**, **Remove** in the font
  store and **Delete** project all ask before they overwrite something undo
  can't bring back.
- **Settings are remembered.** Theme, every view toggle, the grid and its step,
  the glyph opacity and the Move circle choice are saved as they change and
  restored at launch: Android in its preferences, desktop in `settings.json`
  beside the project list, the web build in localStorage. The Light theme is a dark grey (a step up from
  the near-black Dark one), still dark enough for white text and icons, so both
  themes share the same ink, marks and hairlines; on Android the status and
  navigation bar icons stay light on both.
- **Redo.** The arrow next to Undo (Ctrl/Cmd+Shift+Z or Ctrl/Cmd+Y) steps forward
  through what Undo stepped back from: outlines (all linked states together),
  guides, metrics and ghosts. A redo is an ordinary edit, so it can itself be
  undone; any new edit forgets what was left to redo, and so does opening
  another glyph.
- **Regular follows the extremes.** Regular is not drawn: with no Regular, it
  appears halfway between the extremes (for several complete axes, the average
  of each axis's midpoint) and then *moves by what the extremes move*, so
  reshaping an extreme carries Regular with it and a nudge you gave Regular (or
  an imported Regular's offset) is kept. The pen is off on Regular; moving and
  reshaping its nodes is allowed, and is meant to be slight. Those follow-moves
  are not edits of their own: Undo of an extreme brings Regular back with it.
  **Reset to the natural path** (suggestion) and **Reset Regular to the middle**
  put every Regular point exactly halfway between the extremes again, so each
  node's travel is a straight line with no bend. One undo step.
- **Anchor-point tools** (dock; keys A, D, C), as in Photoshop. **Add** (A): tap
  a segment to insert a node there without changing the outline (hold and drag
  to place it); a node on a curve is smooth, on a straight side a corner.
  **Subtract** (D): tap a node to remove it, the neighbouring curve reflowing.
  **Convert** (C): tap a node to strip its handles and make it a corner; drag
  from a node to pull out symmetric (smooth) handles; drag a handle to move it
  alone, which makes its node a corner. Add and Subtract act on every state
  that matches the open one (see below) as one undo step; Convert is per state.
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
  glyphs. Pasting changes point counts, so run **Match the extremes' nodes**
  to keep interpolation alive. Also: Delete, Esc, arrow-key nudges (Shift = 10),
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
  including when just a handle is selected. Trash or Delete removes a selected
  node with its handles. On a selected handle of a **corner** node it deletes
  just that handle (it retracts onto the node, which keeps its other handle and
  hides the retracted one); making the node mirrored brings the handle back at
  full length. A mirrored node keeps both handles, so Delete on a lone handle of
  one says so instead of acting.
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
- **UFO import (desktop).** In **Glyphs and files → Import UFO**, select a
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
- **Project folders (Android).** **Glyphs and files → Kept in → Change**
  moves the open project into a folder you pick (Android's document picker:
  internal storage, an SD card, or any provider that offers folders). Morphont
  makes a folder named for the project inside it and keeps the glyphs there as
  ordinary files, saved as you work, in place of its private storage. They
  survive uninstalling the app, and show up in your files app, backups and
  sync. The folder you pick also becomes where new projects start (undo that
  under Change → *Start new projects in app storage*). **Move back to app
  storage** copies the project home and leaves the folder's copy alone.
  **Open a project from a folder** (the project list) adopts a folder that
  already holds glyphs, such as one from before a reinstall. Moving copies
  every glyph, reads each back to check it, and picks up edits made meanwhile
  before it switches; the app's own copy is deleted only after that. Removing
  a project that lives in a folder forgets it and leaves the files. If the
  folder is deleted or access to it is taken back the project says so instead
  of opening empty; pick the folder again with *Open a project from a folder*.
  The files use the desktop app's naming (`%41.json` for `A`, `a.json` for
  `a`: percent-encoded, capitals escaped so `A` and `a` never share a file on
  a case-insensitive volume), so a folder is readable on either.
- **Export as a font.** **Glyphs and files → Export as a font…** writes the
  open project as a compiled font, in every shell. Choose the outlines:
  *TrueType* (static `.ttf`, quadratic curves), *OpenType / CFF* (static
  `.otf`, Morphont's cubic curves as drawn) or *Variable TrueType* (one `.ttf`
  holding every axis); and the wrapper: plain, WOFF or WOFF2. A static font
  freezes the **Font overview**'s instance (Regular unless you moved its
  sliders). A variable font gets every axis that has both extremes drawn on
  some glyph and actually differs from Regular; imported axes that never
  moved are left out. Morphont draws no numbers of its own, so the dialog
  asks what each axis reaches (minimum, default, maximum; weight starts at
  100/400/900, width at 75/100/125). Set an end equal to the default to leave
  that side out. Each glyph becomes its character when its name is one
  (`A`, `é`, an emoji) or a production name (`uni00E9`, `u1F600`); any other
  name is kept in the font without a character. A `.notdef` box is added when
  the project has none. Glyphs drawn on another grid are scaled to the
  project's most common units-per-em; one that reaches beyond a font's
  16-bit coordinates is refused by name. The editor fills even-odd and a font
  fills non-zero, so each contour is turned the way its nesting needs: outer
  contours clockwise for TrueType and counter-clockwise for CFF, holes against
  them, islands inside holes outer again. A ring drawn with both contours the
  same way therefore still comes out as a ring.
  The writers are from scratch (`Font*.kt`, no platform codec): `head`,
  `hhea`, `maxp`, `hmtx`, `OS/2`, `name`, `cmap` (format 4, and 12 for
  characters beyond the BMP), `post`, `gasp`, then `glyf`/`loca` or `CFF `,
  and for a variable font `fvar` and `gvar`. Cubic curves become quadratics
  within half a unit per thousand, cut so that every master of a variable
  font has the same points. A variable font carries intermediate stops at a
  quarter and three quarters of each side of every axis, so its straight-line
  `gvar` interpolation follows Morphont's own forced-Bezier curve to within a
  few units instead of the chord a three-master font would give; between
  stops it is the nearest straight line. Axes combine additively, as they do
  in the editor. Advance widths that change along an axis travel in `gvar`'s
  phantom points. Outside the test suite, every format was checked with
  fontTools (every table decompiles and recompiles; outlines and widths match
  the editor at each stop), with the OpenType Sanitizer browsers vet web fonts
  with, and by loading the files in Chromium through the web build.
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
- **Matching states stay matching.** While Extra Thin, Regular and Extra Black
  (or any anchors) have the same structure, deleting nodes (Delete/trash, or Cut
  for whole contours) in any one of them deletes the same nodes from all of
  them as a single undo step, and the status line says which states changed.
  Anchors that don't match are left alone, and deleting just a handle never
  touches the others.
- **Resolving mismatched nodes: extreme to extreme.** When the extremes
  disagree about their nodes, the app works out which node answers to which
  *between the extremes themselves* -- Regular's layout is never evidence, and
  Regular is never the template. Each pair of outlines is aligned as a cyclic
  sequence of nodes (starting anywhere, wound either way), scoring a pairing by
  where the node sits in its own contour, which way the outline runs into and
  out of it, how sharply it turns and how far round the contour it is. A node
  with no good partner is *missing* from the other extreme. **Add missing
  nodes** puts each one there, at the same fraction of the way along the
  matching stretch of outline (a de Casteljau split, so nothing moves), and
  nothing either extreme drew is lost; **Match nodes to the fewest** instead
  takes out the nodes with no counterpart (shape-preserving refit). With more
  than two extremes they are aligned one at a time to a growing shared
  structure, and every earlier extreme receives each node it gains. Contours are
  paired by position, size and outer/inner; a contour only one extreme has is
  supplied to the other as it stands (Add) or dropped (Fewest). Afterwards
  **Regular is rebuilt** from the resolved extremes -- the average of each
  complete axis's midpoint -- *only if* its structure no longer fits; a Regular
  that still fits (an imported one, or one you have moved) is left exactly as it
  is. One undo step. Both, and **Reset Regular to the middle** (every Regular
  point back halfway between the extremes), live in the dock's **match** menu
  (three-line icon, with each anchor's node count at the top) as well as in the
  sparkle suggestions.
- **Snapping.** Light: within ~7px a dragged point (or a selection's edges/
  centre) pulls onto metrics, guides, ghost nodes and (more weakly) the grid,
  and lines up -- vertically or horizontally -- with other **nodes** (when
  moving a node) or other **handles** (when moving a handle; handles never snap
  to nodes, but a handle of a corner node also lines up with its own node's
  other handle). Once a handle lines up with another handle on one axis it also
  takes that handle's length or angle (distance and direction from its own
  node), whichever lands closer on the same line. It applies to the move circle too. The line it caught flashes.
  View → Snap toggles it.
- **Updates.** On launch Morphont looks for a newer version on the channel it
  was installed from, and says so only if there is one; Menu → Check for
  updates… asks on demand and always answers. A copy installed by **Google
  Play** asks Play (its in-app update API) and opens the Play listing. Any
  other Android copy (the GitHub APK, sideloaded) and the desktop apps look at
  the GitHub Releases for the newest non-prerelease carrying their own
  installer (`.apk`; `.msi`, `.dmg`, or `.deb`/`.rpm`) and open that release.
  A patch release holds every build's installers, so the newest build in it is
  the one compared, on all four version parts. The web app updates itself on
  reload and has no check.
- **Font store.** Menu → Font store (or Ghosts → + → Character from the
  font store) browses the `kind: "font"` packages on
  [azphalt.store](https://azphalt.store): search, then **Install**. A package
  is checked before anything is kept -- zip paths, sizes, every file's
  SHA-256 against its manifest, and azphalt's font rules (faces under
  `fonts/` in their declared format, nothing else but the licence) -- and its
  faces are stored on this device, a new version beside the old until it's
  complete.
  Under **Installed**: **Use as ghost** (TrueType faces) opens the character
  picker on that font; **Install on this computer** copies it into the user's
  font folder on desktop (`~/Library/Fonts`, `~/.local/share/fonts/morphont`
  + `fc-cache`, or the per-user Windows font folder and its registry entry),
  as `<package id>-<file>` so it never replaces a font of your own, and says
  when the system didn't pick it up; only `.ttf`/`.otf` faces install;
  Android saves the file through the system picker and the web downloads it,
  since neither lets an app install fonts; **Remove**. Package signatures
  aren't checked yet (the store is reached over HTTPS), and paid fonts are
  bought in the store itself.
- **Ghost look.** While any ghost is showing the working glyph's fill is
  see-through (Ghosts panel → Glyph opacity, default 50%) so ghosts read
  underneath it. Each ghost's outline is dotted or solid and one of six
  colours, chosen on its row in the Ghosts panel; both save with the glyph.
- **Ghosts.** Reference outlines behind every panel: primitive shapes, a
  character from any TrueType file (static or variable, scaled to this
  project's UPM; loaded per session) or from a font installed from the
  store, or a glyph from this project. A
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
  tool dock, keys, Start the other extreme, Simplify and suggestions all
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
  - *Interpolation* -- start the other extreme from this one; when extremes
    disagree: add the missing nodes, match to the fewest, or jump to the first
    mismatch.

## Project layout

- `Model.kt` -- glyph data model, serializable per-ghost axis overrides and embedded variable-font reference masters
- `FontOverview.kt` -- lazy font-wide glyph grid, independent enlarged glyph dialogs, and shared or local variable-axis preview sliders
- `commonTest/FontOverviewTest.kt` -- touch-default circle preference, global versus per-glyph previews, independent ghost axes and project round-trip
- `Interpolation.kt` -- the interpolation math (additive per-axis forced-Bezier), independent of any UI
- `Geometry.kt` -- font-space <-> canvas-space mapping, outline path building
- `Hit.kt` / `Gestures.kt` -- point hit-testing and pointer-gesture handling (zoom/pinch, touch proxy, ghost placement, rulers/guides, draw, drag with snapping, rubber-band)
- `Outline.kt` -- the cubic node/arm model: arm lookup, mirrored drags, corner/smooth, line/curve, valid deletion, pen nodes, exact quadratic-to-cubic conversion
- `Editing.kt` -- UI-free editing geometry: bounds, affine transforms, ghost shapes, clipboard extraction, snapping, node-reduction order
- `NodeResolve.kt` -- extreme-to-extreme node correspondence (cyclic alignment, missing nodes placed at the matching arc position, multi-extreme merge) and Regular derived from the extremes
- `EditorState.kt` -- Compose state holders (`AnchorState` per anchor, `AppState` overall)
- `Editor.kt` -- the shared editor screen (phone and wide layouts) and the welcome screen, used by all shells
- `Onboarding.kt` -- the sample glyph, "How Morphont works", the shared start-screen head and the keys-and-gestures list; `GlyphFiles.kt` -- rename and delete for glyphs, written once over each shell's storage
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
- `Updates.kt` / `UpdatesUi.kt` -- version comparison, the GitHub Releases lookup and the update prompts; `DesktopUpdates.kt` and `AndroidUpdates.kt` (Play install source and in-app update API) supply each platform's check; `commonTest/UpdatesTest.kt` covers it
- `Azp.kt` / `Inflate.kt` / `Sha256.kt` -- reading azphalt `.azp` packages on every target: zip with a plain-Kotlin inflater, path and size limits, `manifest.files` digest checks, the `font` block
- `FontStore.kt` / `FontStoreUi.kt` -- the store client (Repository API search and download), the installed-font shelf over a platform `BlobStore`, and the store dialog; `DesktopFontStore.kt`, `AndroidFontStore.kt` and `WebFontStore.kt` supply HTTP, storage and install-to-system; `desktopTest/AzpTest.kt` and `commonTest/Sha256Test.kt` cover them
- `Format.kt` / `commonTest/FormatTest.kt` -- Unicode presentation for legacy production names, independent of glyph identity
- `FontExport.kt` / `FontExportUi.kt` -- compiled-font export: what goes in (names to characters, the frozen instance, variable axes, masters and `gvar` tuples) and the dialog and shell hook (`FontExportHost`) that asks for it; `commonTest/FontExportTest.kt` round-trips every format through `VariableFont` and checks the `gvar` encoding
- `FontOutline.kt` / `FontTables.kt` / `FontTrueType.kt` / `FontCff.kt` / `FontBytes.kt` -- the font writers: contours on the integer grid and cubic-to-quadratic conversion, the shared sfnt tables and container, `glyf`/`loca`/`fvar`/`gvar`, the `CFF ` table, and the big-endian byte buffer
- `FontWoff.kt` / `Deflate.kt` / `Brotli.kt` / `Huffman.kt` -- the WOFF and WOFF2 containers, a plain-Kotlin zlib compressor (fixed or dynamic Huffman) and Brotli compressor (LZ77, simple and complex prefix codes), and the length-limited Huffman and bit-writer code they share; `desktopTest/FontContainerTest.kt` inflates WOFF with the JDK and reads WOFF2 back through `BrotliTestDecoder.kt`, a small decoder written from the RFC, which `BrotliRoundTripTest.kt` also drives
- `GlyphFileNames.kt` -- the glyph-name-to-file-name scheme shared with the desktop app; `desktopTest/GlyphFileNamesParityTest.kt` keeps the two identical
- `ProjectLocationUi.kt` -- the Kept in row and its dialog; `androidMain/GlyphFolder.kt` -- a project's glyph files as one interface over app storage or a document-picker folder, and `AndroidStorage.kt`'s moves between them

## Design

The glyph is the room. One full-bleed canvas shows the anchor being edited;
every control floats on it as a rounded, hairline-edged surface and stays out
of the way until needed.

- **Phone:** glyph name and node count up top, with Start the other extreme, Undo and
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
  off-curve handle tips are X marks. Colour appears in two places only:
  `Mono.error`, kept for errors, and the ghost outline colours (`GhostStyle`),
  which a user picks to tell several ghosts apart.
- **One vocabulary of parts.** Every slider is `HairSlider` (Material's slider
  with a hairline track, so gestures and keyboard steps are stock). Buttons
  have three weights -- borderless, `outlined`, and `selected` (the one
  primary action on a surface) -- one label size, and dialog footers read
  Cancel then the primary. Text sizes are roles (`Type.Micro`, `Small`,
  `Body`, `Row`, `Title`...), never raw numbers; `Caps` is for labels of a few
  words and has two sizes. Icon buttons are 28, 40 or 48 dp (`IconSize`) with
  the icon at a fixed 16 or 20 dp inside. Glyph tiles (`GlyphTile`), menu
  section heads (`MonoMenuHeader`), the start-screen head (`MorphontHero`) and
  the start list are each defined once. Ripples are ink at low strength.
- **Contrast.** Placeholders and small labels use `inkDim` (above 4.5:1 on
  every panel); `inkFaint` is for disabled things only. The splash and the
  privacy page follow the saved theme and repeat the palette's hexes, which
  `PaletteResourcesTest` keeps in step with `Theme.kt`.
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
- Reference fonts loaded from a file are held for the session only (install
  from the store to keep one); either way they must be TrueType-outline
  (`glyf`); CFF/OTF isn't parsed.
- Metrics are stored per glyph (so imported fonts' values travel with each
  glyph); glyphs without saved metrics use the default FontMetrics instead
  of leaking the previously opened glyph's values. Guides, metrics,
  ghost add/remove, linked/static ghost mode changes and geometry edits are
  all part of chronological undo history.

- Shift-click toggles a node/handle in the current selection, and
  Shift-marquee adds points without clearing the previous selection.
  Multi-touch gesture interaction still requires device-level testing.
- **Font export.** No hinting, kerning, GPOS/GSUB, `STAT` or `HVAR`; advance
  variation uses `gvar` phantom points, which current browsers, FreeType and
  DirectWrite honour. WOFF (zlib) and WOFF2 (Brotli) are compressed by small
  from-scratch encoders: LZ77 with hash chains and one Huffman table set per
  table or block. They read correctly everywhere that was tried, but come out
  a little larger than zlib's and the reference Brotli's own (no Brotli
  dictionary, context modelling or block splitting, and WOFF2's glyf and
  hmtx transforms aren't applied). A glyph whose anchors don't match point
  for point is exported from one anchor alone (Regular, or the first drawn)
  and listed in the report. Contours that cross rather than nest fill as a
  union in the font, where the editor shows an XOR. A static font's style
  name, and the default instance of a variable one, are whatever the dialog
  says; a variable font's is always Regular. Imports still read only
  TrueType-outline fonts, so a CFF export can't be imported back.
- **Project folders.** Reads and writes go through Android's document
  providers, which can be slow (a cloud provider especially) and the glyph
  list is read on the main thread as it always was (it falls back to the last
  list that worked if the folder stops answering). A file is overwritten in
  place, not through a temporary file, since providers can't rename over an
  existing one, so a process killed mid-write can leave that one glyph empty.
  Only files named exactly as Morphont names them count as glyphs, and a
  folder is adopted only if every `.json` file in it is a glyph file, so
  notes or settings beside a project are never listed, overwritten or
  deleted. One move runs at a time; a second is refused. A font import that
  spans a move finishes in the folder the project is in by then. Android keeps
  a limited number of folder permissions, so Morphont gives one back as soon as
  no project and no "new projects" setting uses that folder. Moving and saving are checked in the Android host tests
  (`androidHostTest/AndroidFolderStorageTest.kt`, under Robolectric) against a
  stand-in document provider, not against every real one. Desktop and web have no folder
  choice yet; they use the file picker for **Save the whole project**.
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
