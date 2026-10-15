package com.hereliesaz.morphont

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/** A (contour index, point index) key identifying one point within a corner's contours. */
typealias PointKey = Pair<Int, Int>

/**
 * Editable state for one anchor (a corner or Regular). Edits replace the
 * whole [glyph] value (rather than mutating Pt fields in place) so Compose
 * observes every change; drag gestures build each frame's glyph from a
 * captured start-of-drag snapshot, the same way the original tool
 * recomputed from an `orig` snapshot on every mousemove.
 */
class AnchorState(initial: GlyphCorner) {
    private var glyphState by mutableStateOf(completeNodeHandles(initial))

    var glyph: GlyphCorner
        get() = glyphState
        private set(value) { glyphState = completeNodeHandles(value) }

    var selection by mutableStateOf<Set<PointKey>>(emptySet())
    var drawingContourIndex by mutableStateOf<Int?>(null)

    private val history: SnapshotStateList<GlyphCorner> = SnapshotStateList<GlyphCorner>().apply { add(glyph.deepCopy()) }
    /** AppState registers this so undo spans geometry, guides and layer actions. */
    var onHistoryPush: (() -> Unit)? = null

    fun replaceGlyph(new: GlyphCorner) {
        glyph = new
    }

    /** Records the state to return to on the next [undo]. Call before mutating [glyph]. */
    fun pushHistory() {
        history.add(glyph.deepCopy())
        if (history.size > 80) history.removeAt(0)
        onHistoryPush?.invoke()
    }

    /** Pops the most recently pushed pre-edit state and makes it current. */
    fun undo() {
        if (history.size <= 1) return
        glyph = history.removeAt(history.lastIndex).deepCopy()
        selection = emptySet()
    }

    /** Replaces [glyph] as one undoable edit: pushes the current state first, same as any other edit. */
    fun replaceWithHistory(new: GlyphCorner) {
        pushHistory()
        glyph = new
        selection = emptySet()
        drawingContourIndex = null
    }

    fun loadFresh(new: GlyphCorner) {
        glyph = new
        selection = emptySet()
        drawingContourIndex = null
        history.clear()
        history.add(glyph.deepCopy())
    }

    /**
     * Starts a contour when idle. If the editor is already drawing, the same
     * action finishes that contour instead. The toggle behavior keeps older
     * callers functional while surfaces with room can label the action
     * explicitly as "Finish contour".
     */
    fun startNewContour() {
        if (drawingContourIndex != null) {
            finishContour()
            return
        }
        pushHistory()
        val updated = glyph.deepCopy()
        updated.contours.add(ContourData())
        drawingContourIndex = updated.contours.size - 1
        selection = emptySet()
        glyph = updated
    }

    /** Leaves point-placement mode. Empty accidental contours are discarded. */
    fun finishContour() {
        val ci = drawingContourIndex ?: return
        val updated = glyph.deepCopy()
        val contour = updated.contours.getOrNull(ci)
        if (contour != null && contour.points.isEmpty()) {
            updated.contours.removeAt(ci)
            glyph = updated
            // Starting and cancelling an empty contour is not a new edit.
            if (history.size > 1) history.removeAt(history.lastIndex)
        }
        drawingContourIndex = null
        selection = emptySet()
    }

    /** Pen tap: appends a corner node to the contour being drawn (see [appendDrawNode]). */
    fun addDrawPoint(x: Float, y: Float) {
        val ci = drawingContourIndex ?: return
        pushHistory()
        val updated = glyph.deepCopy()
        updated.contours[ci] = appendDrawNode(updated.contours[ci], x, y)
        glyph = updated
    }

    /**
     * Pen drag, continuing the [addDrawPoint] that started it (same undo
     * step): the node just placed turns smooth, its out-arm follows the
     * pointer and its in-arm mirrors it (see [setDrawArm]).
     */
    fun dragDrawArm(x: Float, y: Float) {
        val ci = drawingContourIndex ?: return
        val updated = glyph.deepCopy()
        val c = updated.contours.getOrNull(ci) ?: return
        if (c.points.none { it.onCurve }) return
        updated.contours[ci] = setDrawArm(c, x, y)
        glyph = updated
    }

    /** Make curve / make straight on the selected segments (see [toggleCurveSelection]). */
    fun toggleCurveSelected() {
        if (selection.isEmpty()) return
        pushHistory()
        val (g, sel) = toggleCurveSelection(glyph, selection)
        glyph = g
        selection = sel
    }

    /** Whether every selected node (or node of a selected arm) is already smooth. */
    val selectionIsSmooth: Boolean
        get() = selectedNodes().let { n -> n.isNotEmpty() && n.all { (ci, pi) -> glyph.contours[ci].points[pi].smooth } }

    fun selectedNodes(): Set<PointKey> = selection.mapNotNull { (ci, pi) ->
        val pts = glyph.contours.getOrNull(ci)?.points ?: return@mapNotNull null
        val p = pts.getOrNull(pi) ?: return@mapNotNull null
        if (p.onCurve) ci to pi else armOwner(pts, pi)?.let { ci to it }
    }.toSet()

    /** Corner <-> smooth: all smooth nodes become corners, otherwise all become smooth (and re-aligned, see [setSmooth]). */
    fun toggleSmoothSelected() {
        if (selectedNodes().isEmpty()) return
        pushHistory()
        glyph = setSmooth(glyph, selection, !selectionIsSmooth)
    }

    /** Flips one node between smooth (mirrored arms) and corner: Alt-click, or a touch long-press. */
    fun toggleNodeSmooth(key: PointKey) {
        val node = glyph.contours.getOrNull(key.first)?.points?.getOrNull(key.second) ?: return
        if (!node.onCurve) return
        pushHistory()
        glyph = setSmooth(glyph, listOf(key), !node.smooth)
    }

    fun selectAll() {
        selection = glyph.contours.flatMapIndexed { ci, c -> c.points.indices.map { ci to it } }.toSet()
    }

    /** Appends [contours] in place (no offset, like a font editor's paste) and selects exactly what was pasted. */
    fun pasteContours(contours: List<ContourData>) {
        if (contours.isEmpty()) return
        finishContourIfDrawing()
        pushHistory()
        val updated = glyph.deepCopy()
        val first = updated.contours.size
        contours.forEach { updated.contours.add(it.deepCopy()) }
        glyph = updated
        selection = (first until glyph.contours.size).flatMap { ci -> glyph.contours[ci].points.indices.map { ci to it } }.toSet()
    }

    /** Removes every contour touched by the selection (the clipboard's unit -- see [contoursForClipboard]). */
    fun deleteTouchedContours() {
        val touched = touchedContours(selection)
        if (touched.isEmpty()) return
        pushHistory()
        val updated = glyph.deepCopy()
        val kept = updated.contours.filterIndexed { ci, _ -> ci !in touched }
        updated.contours.clear()
        updated.contours.addAll(kept)
        selection = emptySet()
        drawingContourIndex = null
        glyph = updated
    }

    /** Arrow keys always move whole nodes, even when only an arm is selected. */
    fun nudgeSelected(dx: Float, dy: Float) {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        pushHistory()
        glyph = dragPoints(glyph, nodes, dx, dy)
    }

    /** Applies [t] to the selected points as one undoable edit (flip/rotate buttons). */
    fun transformSelected(t: Affine) {
        if (selection.isEmpty()) return
        pushHistory()
        glyph = transformPoints(glyph, selection, t)
    }

    fun flipSelected(horizontal: Boolean) {
        val b = boundsOfSelection(glyph, selection) ?: return
        transformSelected(if (horizontal) scalingAbout(-1f, 1f, b.cx, b.cy) else scalingAbout(1f, -1f, b.cx, b.cy))
    }

    fun rotateSelected(degrees: Float) {
        val b = boundsOfSelection(glyph, selection) ?: return
        transformSelected(rotationAbout(degrees, b.cx, b.cy))
    }

    private fun finishContourIfDrawing() {
        if (drawingContourIndex != null) finishContour()
    }

    /** Trash deletes whole nodes, even when just a handle is selected. */
    fun deleteSelected() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        pushHistory()
        val updated = deleteSelection(glyph, nodes)
        selection = emptySet()
        drawingContourIndex = null
        glyph = updated
    }
}

/**
 * One ghosted reference outline (see [GhostData]). A static ghost's own
 * geometry lives in an ordinary [AnchorState] so node editing, selection,
 * undo and the touch proxy reuse the anchor machinery unchanged; placement
 * lives in [matrix] with its own small undo stack.
 */
class GhostLayer(
    val id: Int,
    label: String,
    contours: List<ContourData>,
    visible: Boolean = true,
    sourceGlyph: String? = null,
    matrix: Affine = IDENTITY_MATRIX,
    beside: Boolean = false,
) {
    var label by mutableStateOf(label)
    var beside by mutableStateOf(beside)
    var visible by mutableStateOf(visible)
    var sourceGlyph by mutableStateOf(sourceGlyph)
    var matrix by mutableStateOf(matrix)
    val state = AnchorState(GlyphCorner(0f, contours.map { it.deepCopy() }.toMutableList()))
    private val matrixHistory = mutableListOf<Affine>()
    var onMatrixHistoryPush: (() -> Unit)? = null

    val isLinked: Boolean get() = sourceGlyph != null

    fun pushMatrixHistory() {
        matrixHistory.add(matrix)
        if (matrixHistory.size > 80) matrixHistory.removeAt(0)
        onMatrixHistoryPush?.invoke()
    }

    fun undoMatrix(): Boolean {
        if (matrixHistory.isEmpty()) return false
        matrix = matrixHistory.removeAt(matrixHistory.lastIndex)
        return true
    }

    /** Folds [matrix] into the owned contours (static ghosts only) so nodes edit exactly what's drawn. */
    fun bake() {
        if (isLinked || matrix == IDENTITY_MATRIX) return
        state.replaceWithHistory(GlyphCorner(0f, applyAffine(state.glyph.contours, matrix).map { it.deepCopy() }.toMutableList()))
        matrix = IDENTITY_MATRIX
        matrixHistory.clear()
    }

    /** Turns a linked ghost into a static one holding [contours] (already placed), e.g. before node editing. */
    fun detach(contours: List<ContourData>) {
        sourceGlyph = null
        beside = false
        matrix = IDENTITY_MATRIX
        matrixHistory.clear()
        state.loadFresh(GlyphCorner(0f, contours.map { it.deepCopy() }.toMutableList()))
    }

    fun toData() = GhostData(label, if (isLinked) emptyList() else state.glyph.contours.map { it.deepCopy() }, visible, sourceGlyph, matrix, beside)
}

/** What dragging the touch proxy pad does to the selection. */
enum class PadMode { MOVE, SCALE, ROTATE }

/** View toggles shared by every editing canvas. */
class ViewSettings {
    var showGrid by mutableStateOf(false)
    var gridStep by mutableStateOf(50f)
    var showMetrics by mutableStateOf(true)
    var showRulers by mutableStateOf(true)
    var showGuides by mutableStateOf(true)
    var showMeasure by mutableStateOf(true)
    var showGhosts by mutableStateOf(true)
    var snap by mutableStateOf(true)

    /** Shared zoom/pan so every anchor panel frames the glyph identically for comparison. */
    var zoom by mutableStateOf(1f)
    var panX by mutableStateOf(0f)
    var panY by mutableStateOf(0f)

    fun resetView() { zoom = 1f; panX = 0f; panY = 0f }
}

/**
 * A node-reduction preview in progress: [order] is the precomputed removal
 * sequence shared by every anchor in [anchorNames] (all of them when the glyph
 * is point-compatible, so interpolation survives; otherwise just the one
 * being edited), and [removeCount] is the slider.
 */
class ReductionSession(val anchorNames: List<String>, val order: List<Removal>, val totalPoints: Int) {
    var removeCount by mutableStateOf(0)
    val actualRemoved: Int get() = removeCount.coerceIn(0, order.size)
    val remainingNodes: Int get() = totalPoints - actualRemoved
    val minimumNodes: Int get() = totalPoints - order.size
    val removed: Set<PointKey> get() = order.take(actualRemoved).map { it.key }.toSet()
    val error: Float get() = order.take(actualRemoved).maxOfOrNull { it.error } ?: 0f
}

/** Overall application state: every axis's anchors, preview and UI selections. */
class AppState {
    val anchors: Map<String, AnchorState> = ANCHORS.associateWith { AnchorState(GlyphCorner()) }
    private val undoActions = mutableListOf<() -> Unit>()
    private var recording = true
    /** Increment before bulk imports so pending autosaves cannot overwrite newly imported records. */
    var autosaveEpoch by mutableStateOf(0)
        private set

    init {
        anchors.values.forEach { state ->
            state.onHistoryPush = { recordUndo { state.undo() } }
        }
    }

    private fun recordUndo(action: () -> Unit) {
        if (!recording) return
        undoActions.add(action)
        if (undoActions.size > 150) undoActions.removeAt(0)
    }

    private fun attachGhost(g: GhostLayer) {
        g.state.onHistoryPush = { recordUndo { g.state.undo() } }
        g.onMatrixHistoryPush = { recordUndo { g.undoMatrix() } }
    }

    private fun groupUndo(block: () -> Unit, undo: () -> Unit) {
        recording = false
        try { block() } finally { recording = true }
        recordUndo(undo)
    }

    fun beginBulkImport() {
        autosaveEpoch++
    }

    fun clearGuides() {
        if (guides.isEmpty()) return
        val before = guides.toList()
        recordUndo { guides.clear(); guides.addAll(before) }
        guides.clear()
    }

    fun updateMetrics(newMetrics: FontMetrics) {
        val before = metrics
        if (before == newMetrics) return
        recordUndo { metrics = before }
        metrics = newMetrics
    }

    fun replaceGuide(old: Guide?, new: Guide?) {
        val before = guides.toList()
        if (old != null) guides.remove(old)
        if (new != null) guides.add(new)
        if (guides.toList() != before) recordUndo {
            guides.clear()
            guides.addAll(before)
        }
    }

    // Regular is the natural starting point: draw one usable outline there,
    // copy its topology outward, then reshape the extremes.
    var activeAnchor by mutableStateOf("regular")

    /** 0..1 position per axis tag, for the read-only Preview panel. Starts at Regular (0.5) everywhere. */
    val previewValues = mutableStateMapOf<String, Float>().apply {
        Axis.ALL.forEach { put(it.tag, 0.5f) }
    }

    /**
     * Which axis is currently being hand-edited: drives both which lo/hi
     * anchor pair the two corner panels show, and which axis's travel-path
     * overlay the Regular panel draws -- the only pairs guaranteed to pass
     * through Regular by construction, and naturally the same axis a
     * designer editing one wants to see the other for.
     */
    var selectedAxis by mutableStateOf(Axis.WEIGHT)

    var currentGlyphName by mutableStateOf<String?>(null)
    var glyphNames by mutableStateOf<List<String>>(emptyList())
    var status by mutableStateOf("")
    var statusIsError by mutableStateOf(false)
    /** Persistent last report; a fleeting toast must not hide import diagnostics. */
    var lastReport by mutableStateOf("")

    val view = ViewSettings()

    /** Vertical metrics for the static guides; per glyph so an imported font's own values travel with it. */
    var metrics by mutableStateOf(FontMetrics())

    /** User guides for the open glyph. */
    val guides = mutableStateListOf<Guide>()

    val ghosts = mutableStateListOf<GhostLayer>()
    private var nextGhostId = 1

    /** The ghost currently being edited instead of the anchor, or null. */
    var activeGhostId by mutableStateOf<Int?>(null)

    /** True = the active ghost shows a move/scale/rotate box; false = its nodes are editable. */
    var ghostTransformMode by mutableStateOf(true)

    var padMode by mutableStateOf(PadMode.MOVE)

    /** Move on-curve nodes with their handles instead of editing individual handles. */
    var nodeMoveMode by mutableStateOf(false)

    /** Restore the pointer's contextual actions at any time from the tool rail/dock. */
    var showNodeTools by mutableStateOf(true)

    /** Whether the suggested-edits row is expanded (it's behind a button, not always on screen). */
    var showSuggestions by mutableStateOf(false)

    /** Whether the most recent pointer was a finger: drives the touch proxy handle. */
    var touchInput by mutableStateOf(false)

    /** In-app clipboard: whole contours, shared across anchors, ghosts and glyphs. */
    var clipboard by mutableStateOf<List<ContourData>?>(null)

    var reduction by mutableStateOf<ReductionSession?>(null)

    /**
     * Reads another glyph of this project by name, for linked ghosts. Set by
     * the platform shell; results are cached in [linkedGlyphs] so drawing
     * never touches storage.
     */
    var glyphLookup: (String) -> Glyph? = { null }
    val linkedGlyphs = mutableStateMapOf<String, Glyph>()

    /** Reference fonts loaded this session for ghosting, by display name. */
    val referenceFonts = mutableStateMapOf<String, VariableFont>()

    val activeGhost: GhostLayer? get() = ghosts.firstOrNull { it.id == activeGhostId }

    /** What a canvas showing [anchorName] edits: the active ghost if one is being edited, else that anchor. */
    fun editTarget(anchorName: String): AnchorState = activeGhost?.state ?: anchors.getValue(anchorName)

    private fun sourceCorners(name: String): Map<String, GlyphCorner>? =
        if (name == currentGlyphName) cornersSnapshot() else linkedGlyphs[name]?.corners

    /**
     * The ghost's outline as drawn in the panel for [anchorName]: a linked
     * ghost shows its source glyph's own same anchor, so it always matches the
     * working character's position on every axis.
     */
    fun ghostContours(g: GhostLayer, anchorName: String): List<ContourData> {
        val placed = placement(g, anchors.getValue(anchorName).glyph.width)
        val src = g.sourceGlyph ?: return applyAffine(g.state.glyph.contours, placed)
        val corners = sourceCorners(src) ?: return emptyList()
        val corner = corners[anchorName] ?: corners["regular"] ?: return emptyList()
        return applyAffine(corner.contours, placed)
    }

    /** The ghost's matrix, shifted past the working glyph's advance when it's set to sit beside rather than over it. */
    private fun placement(g: GhostLayer, advance: Float): Affine =
        if (g.beside) compose(translation(advance, 0f), g.matrix) else g.matrix

    /** The ghost as drawn in the Preview: a linked ghost interpolated at the same slider values as the working glyph. */
    fun ghostPreviewContours(g: GhostLayer, advance: Float): List<ContourData> {
        val placed = placement(g, advance)
        val src = g.sourceGlyph ?: return applyAffine(g.state.glyph.contours, placed)
        val corners = sourceCorners(src) ?: return emptyList()
        val inst = if (compatibilityIssue(corners) == null) interpolateGlyph(corners, previewValues) else corners["regular"] ?: return emptyList()
        return applyAffine(inst.contours, placed)
    }

    /** Every visible ghost's outline in [anchorName]'s panel, optionally skipping one (the one being edited). */
    fun visibleGhostContours(anchorName: String, skipId: Int? = null): List<List<ContourData>> =
        if (!view.showGhosts) emptyList() else ghosts.filter { it.visible && it.id != skipId }.map { ghostContours(it, anchorName) }

    /** Undo for whatever the user is acting on: a ghost's placement, a ghost's nodes, or the active anchor. */
    fun undo() {
        if (undoActions.isEmpty()) {
            setStatus("Nothing to undo.")
            return
        }
        val action = undoActions.removeAt(undoActions.lastIndex)
        val previous = recording
        recording = false
        try { action() } finally { recording = previous }
    }

    /** Switches the active ghost between placement and node editing; node editing bakes/detaches first. */
    fun setGhostNodeMode(nodes: Boolean) {
        val g = activeGhost ?: return
        if (nodes && ghostTransformMode) {
            val original = g.toData()
            recordUndo {
                g.sourceGlyph = original.sourceGlyph
                g.beside = original.beside
                g.matrix = original.matrix
                g.state.loadFresh(GlyphCorner(0f, original.contours.map { it.deepCopy() }.toMutableList()))
                ghostTransformMode = true
                activeGhostId = g.id
            }
            // Detaching or baking is one undo step, not another geometry
            // history entry whose restoration forgets the placement matrix.
            recording = false
            try {
                if (g.isLinked) {
                    g.detach(ghostContours(g, activeAnchor))
                    setStatus("Node editing detached \"${g.label}\"; Undo restores its live link.")
                } else g.bake()
            } finally {
                recording = true
            }
        }
        ghostTransformMode = !nodes
    }

    /** The edit target for menu/keyboard commands: the active ghost, else the active anchor. */
    val commandTarget: AnchorState get() = editTarget(activeAnchor)

    fun setStatus(msg: String, isError: Boolean = false) {
        status = msg
        statusIsError = isError
        if (msg.isNotEmpty()) lastReport = msg
    }

    fun cornersSnapshot(): Map<String, GlyphCorner> = anchors.mapValues { it.value.glyph }

    fun compatibility(names: List<String> = ANCHORS): String? =
        compatibilityIssue(cornersSnapshot(), names)

    fun loadGlyph(name: String, glyph: Glyph) {
        currentGlyphName = name
        for (anchor in ANCHORS) {
            anchors.getValue(anchor).loadFresh(glyph.corners[anchor]?.deepCopy() ?: GlyphCorner())
        }
        guides.clear()
        guides.addAll(glyph.guides)
        ghosts.clear()
        linkedGlyphs.clear()
        glyph.ghosts.forEach {
            val layer = GhostLayer(nextGhostId++, it.label, it.contours, it.visible, it.sourceGlyph, it.matrix.takeIf { m -> m.size == 6 } ?: IDENTITY_MATRIX, it.beside)
            attachGhost(layer)
            ghosts.add(layer)
            it.sourceGlyph?.let { src -> cacheLinked(src) }
        }
        metrics = glyph.metrics ?: FontMetrics()
        undoActions.clear()
        activeGhostId = null
        reduction = null
        activeAnchor = "regular"
        nodeMoveMode = false
        showNodeTools = true
        setStatus("Loaded \"$name\".")
    }

    /** Clears the open glyph (used after loading a whole project, since the previously open glyph may no longer exist under that name). */
    fun clearEditor() {
        currentGlyphName = null
        activeAnchor = "regular"
        for (anchor in ANCHORS) {
            anchors.getValue(anchor).loadFresh(GlyphCorner())
        }
        guides.clear()
        ghosts.clear()
        activeGhostId = null
        reduction = null
        metrics = FontMetrics()
        undoActions.clear()
    }

    fun toGlyph(): Glyph = Glyph(
        corners = cornersSnapshot().mapValues { it.value.deepCopy() }.toMutableMap(),
        guides = guides.toList(),
        ghosts = ghosts.map { it.toData() },
        metrics = metrics,
        outlineFormat = OUTLINE_CUBIC,
    )

    /** Copies the active anchor's outline into every other anchor, seeding matching topology. */
    fun copyActiveToOthers() {
        if (activeGhost != null) return // never touch the glyph while a ghost is being edited
        val src = anchors.getValue(activeAnchor)
        val targets = ANCHORS.filter { it != activeAnchor }.map { anchors.getValue(it) }
        val count = targets.size
        groupUndo(
            block = { targets.forEach { it.replaceWithHistory(src.glyph.deepCopy()) } },
            undo = { targets.forEach { it.undo() } },
        )
        setStatus("Copied ${ANCHOR_LABELS[activeAnchor]}'s outline to the other $count anchor(s) -- reshape each toward its extreme without adding or removing points.")
    }

    // ------------------------------------------------------------ clipboard

    fun copySelection() {
        val t = commandTarget
        val contours = contoursForClipboard(t.glyph, t.selection)
        if (contours.isEmpty()) { setStatus("Nothing to copy.", true); return }
        clipboard = contours
        setStatus("Copied ${contours.size} contour(s).")
    }

    fun cutSelection() {
        val t = commandTarget
        if (t.selection.isEmpty()) { setStatus("Select points to cut; every contour they touch is cut whole.", true); return }
        copySelection()
        t.deleteTouchedContours()
    }

    fun paste() {
        val clip = clipboard ?: run { setStatus("Clipboard is empty.", true); return }
        commandTarget.pasteContours(clip)
        setStatus("Pasted ${clip.size} contour(s) in place. Point counts changed: re-copy to other anchors to keep them compatible.")
    }

    // --------------------------------------------------------------- ghosts

    private fun cacheLinked(name: String) {
        if (name == currentGlyphName) return
        glyphLookup(name)?.let { linkedGlyphs[name] = it }
    }

    fun addGhost(label: String, contours: List<ContourData>) {
        if (contours.isEmpty()) { setStatus("That ghost has no outline.", true); return }
        val layer = GhostLayer(nextGhostId++, label, contours)
        attachGhost(layer)
        ghosts.add(layer)
        recordUndo { ghosts.remove(layer); if (activeGhostId == layer.id) activeGhostId = null }
        setStatus("Added ghost \"$label\". Use its Edit button in Ghosts to move, scale, rotate or reshape it.")
    }

    /** Adds a live ghost of another glyph in this project (see [GhostData.sourceGlyph]). */
    fun addLinkedGhost(name: String) {
        cacheLinked(name)
        if (name != currentGlyphName && name !in linkedGlyphs) { setStatus("Couldn't read \"$name\".", true); return }
        val layer = GhostLayer(nextGhostId++, name, emptyList(), sourceGlyph = name)
        attachGhost(layer)
        ghosts.add(layer)
        recordUndo { ghosts.remove(layer); if (activeGhostId == layer.id) activeGhostId = null }
        setStatus("Ghosting \"$name\" live: it follows every axis, here and in the Preview.")
    }

    /**
     * Enters ghost editing -- the only way in, from the Ghosts panel's Edit
     * button. While it lasts the ghost draws solid, the glyph as an outline,
     * and every gesture edits the ghost.
     */
    fun editGhost(id: Int) {
        val g = ghosts.firstOrNull { it.id == id } ?: return
        g.visible = true
        activeGhostId = id
        ghostTransformMode = true
        reduction = null
    }

    fun removeGhost(id: Int) {
        val index = ghosts.indexOfFirst { it.id == id }
        if (index < 0) return
        val layer = ghosts.removeAt(index)
        val wasActive = activeGhostId == id
        if (wasActive) activeGhostId = null
        recordUndo {
            ghosts.add(index.coerceAtMost(ghosts.size), layer)
            if (wasActive) activeGhostId = id
        }
    }

    /** Bounds of the active ghost as drawn in the active anchor's panel. */
    fun activeGhostBounds(): FontRect? = activeGhost?.let { boundsOfContours(ghostContours(it, activeAnchor)) }

    /** Composes [t] (expressed in drawn, on-canvas coordinates) onto the active ghost's placement as one undoable step. */
    fun transformActiveGhost(t: Affine) {
        val g = activeGhost ?: return
        g.pushMatrixHistory()
        g.matrix = compose(localGhostTransform(g, t, activeAnchor), g.matrix)
    }

    /**
     * Converts a transform measured on the drawn ghost into one for its
     * matrix: a ghost set beside the glyph is drawn shifted by the advance,
     * so the shift is undone around [t] (conjugation).
     */
    fun localGhostTransform(g: GhostLayer, t: Affine, anchorName: String): Affine {
        if (!g.beside) return t
        val adv = anchors.getValue(anchorName).glyph.width
        return compose(translation(-adv, 0f), compose(t, translation(adv, 0f)))
    }

    fun flipActiveGhost(horizontal: Boolean) {
        val b = activeGhostBounds() ?: return
        transformActiveGhost(if (horizontal) scalingAbout(-1f, 1f, b.cx, b.cy) else scalingAbout(1f, -1f, b.cx, b.cy))
    }

    fun rotateActiveGhost(degrees: Float) {
        val b = activeGhostBounds() ?: return
        transformActiveGhost(rotationAbout(degrees, b.cx, b.cy))
    }

    // ------------------------------------------------------- node reduction

    /** Starts a reduction preview on the active anchor -- on every anchor at once when they're compatible. */
    fun startReduction() {
        if (activeGhost != null) return // never touch the glyph while a ghost is being edited
        val names = if (compatibility() == null) listOf(activeAnchor) + ANCHORS.filter { it != activeAnchor } else listOf(activeAnchor)
        val glyphs = names.map { anchors.getValue(it).glyph }
        val total = glyphs.first().contours.sumOf { c -> c.points.count { it.onCurve } }
        val order = reductionOrder(glyphs)
        if (order.isEmpty()) { setStatus("Nothing to reduce: every contour is already at its minimum.", true); return }
        reduction = ReductionSession(names, order, total)
        activeGhostId = null
    }

    fun applyReduction() {
        val r = reduction ?: return
        val removed = r.removed
        if (removed.isNotEmpty()) {
            val targets = r.anchorNames.map { anchors.getValue(it) }
            groupUndo(
                block = {
                    for (st in targets) {
                        val reduced = withoutPoints(st.glyph, removed)
                        st.replaceWithHistory(GlyphCorner(reduced.width, reduced.contours.filter { it.points.isNotEmpty() }.toMutableList()))
                    }
                },
                undo = { targets.forEach { it.undo() } },
            )
        }
        val scope = if (r.anchorNames.size > 1) "every anchor" else (ANCHOR_LABELS[r.anchorNames.first()] ?: r.anchorNames.first())
        setStatus("Removed ${removed.size} point(s) from $scope.")
        reduction = null
    }
}
