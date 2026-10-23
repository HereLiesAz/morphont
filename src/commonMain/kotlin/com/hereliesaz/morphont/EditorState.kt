package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Offset
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
    /** AppState registers this to forget the oldest ([oldest] = true) or newest undo action when history drops an entry. */
    var onHistoryDrop: ((oldest: Boolean) -> Unit)? = null
    /** AppState registers this to forget this anchor's undo action [fromNewest] places back (0 = its newest). */
    var onHistoryDropAt: ((fromNewest: Int) -> Unit)? = null

    /** Where in [history] the snapshot [startNewContour] pushed sits, while that contour is open. */
    private var contourStartIndex: Int? = null

    fun replaceGlyph(new: GlyphCorner) {
        glyph = new
    }

    /** Records the state to return to on the next [undo]. Call before mutating [glyph]. */
    fun pushHistory() {
        history.add(glyph.deepCopy())
        onHistoryPush?.invoke()
        if (history.size > 80) {
            history.removeAt(0)
            contourStartIndex = contourStartIndex?.minus(1)?.takeIf { it >= 1 }
            onHistoryDrop?.invoke(true)
        }
    }

    /** Drops the oldest undo step without notifying [onHistoryDrop] (the app-level entry is already gone). */
    fun forgetOldestHistory() { if (history.size > 1) history.removeAt(0) }

    /** Pops the most recently pushed pre-edit state and makes it current. */
    fun undo() {
        if (history.size <= 1) return
        glyph = history.removeAt(history.lastIndex).deepCopy()
        if ((contourStartIndex ?: -1) > history.lastIndex) contourStartIndex = null
        selection = emptySet()
        // Keep drawing if the restored state still has the active contour; otherwise leave drawing mode.
        if ((drawingContourIndex ?: 0) >= glyph.contours.size) drawingContourIndex = null
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
        contourStartIndex = null
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
        contourStartIndex = history.lastIndex
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
            // Starting and cancelling an empty contour is not a new edit: drop the entry startNewContour
            // pushed, wherever it now sits, and take the empty contour out of every snapshot taken since,
            // so Undo neither resurrects it nor needs an extra step to get past it.
            val start = contourStartIndex
            if (start != null && start in 1..history.lastIndex) {
                for (i in start + 1..history.lastIndex) {
                    val snap = history[i]
                    if (ci < snap.contours.size && snap.contours[ci].points.isEmpty()) {
                        history[i] = snap.deepCopy().also { it.contours.removeAt(ci) }
                    }
                }
                val fromNewest = history.lastIndex - start
                history.removeAt(start)
                onHistoryDropAt?.invoke(fromNewest)
            }
        }
        contourStartIndex = null
        drawingContourIndex = null
        selection = emptySet()
    }

    /** Pen tap: appends a corner node to the contour being drawn (see [appendDrawNode]). */
    fun addDrawPoint(x: Float, y: Float) {
        val ci = drawingContourIndex ?: return
        if (ci !in glyph.contours.indices) { drawingContourIndex = null; return }
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

    /**
     * What the trash does. A selected node goes, with its handles. A selected handle of a corner node is
     * retracted onto its node, leaving the node's other handle; making the node mirrored brings it back.
     * A mirrored node keeps both handles, so a lone handle of one cannot be deleted. Returns a message
     * when something was refused, null otherwise.
     */
    fun deleteSelected(): String? {
        if (selection.isEmpty()) return null
        val nodes = selection.filter { (ci, pi) -> glyph.contours.getOrNull(ci)?.points?.getOrNull(pi)?.onCurve == true }.toSet()
        val handles = selection.filter { (ci, pi) -> glyph.contours.getOrNull(ci)?.points?.getOrNull(pi)?.onCurve == false }
        val retract = mutableSetOf<PointKey>()
        var refused = false
        for ((ci, pi) in handles) {
            val pts = glyph.contours[ci].points
            val owner = armOwner(pts, pi) ?: continue
            if ((ci to owner) in nodes) continue // goes with its node
            if (pts[owner].smooth) refused = true else retract.add(ci to pi)
        }
        val message = if (refused) "A mirrored node keeps both handles. Make it a corner to delete one." else null
        if (nodes.isEmpty() && retract.isEmpty()) return message
        pushHistory()
        val updated = deleteSelection(glyph, nodes + retract)
        selection = emptySet()
        drawingContourIndex = null
        glyph = updated
        return message
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
    initialAxisOverrides: Map<String, Float> = emptyMap(),
    referenceCorners: Map<String, GlyphCorner> = emptyMap(),
    supportedAxes: List<String> = emptyList(),
    solid: Boolean = false,
    color: Int = 0,
) {
    var label by mutableStateOf(label)
    /** Outline solid instead of dotted, and its colour (an index into [GhostStyle.colors]). */
    var solid by mutableStateOf(solid)
    var color by mutableStateOf(color)
    val axisOverrides = mutableStateMapOf<String, Float>().apply { putAll(initialAxisOverrides) }
    var referenceCorners by mutableStateOf(referenceCorners)
    var supportedAxes by mutableStateOf(supportedAxes)
    var beside by mutableStateOf(beside)
    var visible by mutableStateOf(visible)
    var sourceGlyph by mutableStateOf(sourceGlyph)
    var matrix by mutableStateOf(matrix)
    val state = AnchorState(GlyphCorner(0f, contours.map { it.deepCopy() }.toMutableList()))
    private val matrixHistory = mutableListOf<Affine>()
    var onMatrixHistoryPush: (() -> Unit)? = null
    var onMatrixDrop: (() -> Unit)? = null

    val isLinked: Boolean get() = sourceGlyph != null || referenceCorners.isNotEmpty()

    fun pushMatrixHistory() {
        matrixHistory.add(matrix)
        onMatrixHistoryPush?.invoke()
        if (matrixHistory.size > 80) {
            matrixHistory.removeAt(0)
            onMatrixDrop?.invoke()
        }
    }

    fun forgetOldestMatrixHistory() { if (matrixHistory.isNotEmpty()) matrixHistory.removeAt(0) }

    fun undoMatrix(): Boolean {
        if (matrixHistory.isEmpty()) return false
        matrix = matrixHistory.removeAt(matrixHistory.lastIndex)
        return true
    }

    /**
     * Folds the placement into the owned contours (static ghosts only) so nodes edit exactly what's drawn.
     * A ghost set [beside] the glyph is drawn shifted by [advance], so that shift is baked in too.
     * Touches neither history: the caller records the whole bake as one undo step that restores the
     * placement and geometry, after which the older entries apply to the state they were made on.
     */
    fun bake(advance: Float = 0f) {
        if (isLinked || (matrix == IDENTITY_MATRIX && !beside)) return
        val placed = if (beside) compose(translation(advance, 0f), matrix) else matrix
        state.replaceGlyph(GlyphCorner(0f, applyAffine(state.glyph.contours, placed).map { it.deepCopy() }.toMutableList()))
        matrix = IDENTITY_MATRIX
        beside = false
    }

    /** Turns a linked ghost into a static one holding [contours] (already placed), e.g. before node editing. Like [bake], history is the caller's. */
    fun detach(contours: List<ContourData>) {
        sourceGlyph = null
        referenceCorners = emptyMap()
        supportedAxes = emptyList()
        axisOverrides.clear()
        beside = false
        matrix = IDENTITY_MATRIX
        state.replaceGlyph(GlyphCorner(0f, contours.map { it.deepCopy() }.toMutableList()))
    }

    /**
     * Takes on [d]'s look, placement and geometry in place. Redo uses this rather than rebuilding the
     * layer, so the undo entries already recorded against this ghost (and its private histories) keep
     * pointing at the object that is on screen.
     */
    fun applyData(d: GhostData) {
        label = d.label
        visible = d.visible
        sourceGlyph = d.sourceGlyph
        matrix = d.matrix.takeIf { it.size == 6 } ?: IDENTITY_MATRIX
        beside = d.beside
        axisOverrides.clear(); axisOverrides.putAll(d.axisOverrides)
        referenceCorners = d.referenceCorners
        supportedAxes = d.supportedAxes
        solid = d.solid
        color = d.color
        val contours = d.contours.map { it.deepCopy() }.toMutableList()
        if (state.glyph.contours != contours) state.replaceGlyph(GlyphCorner(0f, contours))
    }

    fun toData() = GhostData(
        label, if (isLinked) emptyList() else state.glyph.contours.map { it.deepCopy() },
        visible, sourceGlyph, matrix, beside, axisOverrides.toMap(),
        referenceCorners, supportedAxes, solid, color,
    )
}

/** What dragging the touch proxy pad does to the selection. */
enum class PadMode { MOVE, SCALE, ROTATE }

/** Photoshop's anchor-point tools: Add Anchor Point, Delete Anchor Point, Convert Point. */
enum class NodeTool { ADD, SUBTRACT, CONVERT }

/** View toggles shared by every editing canvas. */
class ViewSettings {
    var showGrid by mutableStateOf(false)
    var gridStep by mutableStateOf(50f)
    var showMetrics by mutableStateOf(true)
    var showRulers by mutableStateOf(true)
    var showGuides by mutableStateOf(true)
    var showMeasure by mutableStateOf(true)
    var showGhosts by mutableStateOf(true)
    /** How opaque the working glyph's fill is while ghosts are showing, so they can be seen through it. */
    var glyphOpacity by mutableStateOf(0.5f)
    /** Regular's per-node travel paths to the extremes. Off by default: they look like nodes you could grab, and aren't. */
    var showTravel by mutableStateOf(false)
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
    /** [owners] are the objects whose private history holds one snapshot for this entry (several for a grouped edit). */
    private class UndoEntry(val owners: MutableList<Any>, val action: () -> Unit)
    private val undoActions = mutableListOf<UndoEntry>()

    private companion object {
        /** As long as the undo history ([undoActions] keeps 150): no older retired ghost can be asked for. */
        const val MAX_RETIRED_GHOSTS = 150
    }
    private var recording = true

    /** Everything Undo and Redo move between: the outlines, guides, metrics and ghosts at one moment. */
    private class EditorSnapshot(
        val corners: Map<String, GlyphCorner>,
        val guides: List<Guide>,
        val metrics: FontMetrics,
        val ghosts: List<GhostData>,
        /** [ghosts]' layer ids, so redo can update the same layers instead of replacing them. */
        val ghostIds: List<Int>,
    )

    /** States Undo stepped back from, newest last; Redo steps forward to them. Cleared by any new edit. */
    private val redoStack = mutableStateListOf<EditorSnapshot>()
    private var redoing = false

    /** Whether there is anything to Redo. */
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    /** Increment before bulk imports so pending autosaves cannot overwrite newly imported records. */
    var autosaveEpoch by mutableStateOf(0)
        private set

    init {
        anchors.values.forEach { state ->
            state.onHistoryPush = { recordUndo(state) { state.undo() } }
            state.onHistoryDrop = { oldest -> dropUndo(state, oldest) }
            state.onHistoryDropAt = { n -> dropUndoAt(state, n) }
        }
    }

    private fun recordUndo(owner: Any? = null, action: () -> Unit) = recordUndo(listOfNotNull(owner), action)

    private fun recordUndo(owners: List<Any>, action: () -> Unit) {
        if (!recording) return
        if (!redoing) redoStack.clear() // a new edit ends the line Redo would have followed
        undoActions.add(UndoEntry(owners.toMutableList(), action))
        if (undoActions.size > 150) {
            // Keep the owner's private history in step with the evicted global entry.
            for (evicted in undoActions.removeAt(0).owners) when (evicted) {
                is AnchorState -> evicted.forgetOldestHistory()
                is GhostLayer -> evicted.forgetOldestMatrixHistory()
            }
        }
    }

    /** Forgets the oldest or newest undo action owned by [owner] when its own history dropped the matching entry. */
    /** Forgets the undo action owned by [owner] that sits [fromNewest] places back among its own (0 = newest). */
    private fun dropUndoAt(owner: Any, fromNewest: Int) {
        val owned = undoActions.indices.filter { i -> undoActions[i].owners.any { it === owner } }
        val i = owned.getOrNull(owned.size - 1 - fromNewest) ?: return
        undoActions[i].owners.removeAll { it === owner }
        if (undoActions[i].owners.isEmpty()) undoActions.removeAt(i)
    }

    private fun dropUndo(owner: Any, oldest: Boolean) {
        val i = if (oldest) undoActions.indexOfFirst { e -> e.owners.any { it === owner } } else undoActions.indexOfLast { e -> e.owners.any { it === owner } }
        if (i < 0) return
        // A grouped entry stays (its other owners still hold snapshots); this owner's share is gone.
        undoActions[i].owners.removeAll { it === owner }
        if (undoActions[i].owners.isEmpty()) undoActions.removeAt(i)
    }

    private fun attachGhost(g: GhostLayer) {
        g.state.onHistoryPush = { recordUndo(g.state) { g.state.undo() } }
        g.state.onHistoryDrop = { oldest -> dropUndo(g.state, oldest) }
        g.state.onHistoryDropAt = { n -> dropUndoAt(g.state, n) }
        g.onMatrixHistoryPush = { recordUndo(g) { g.undoMatrix() } }
        g.onMatrixDrop = { dropUndo(g, true) }
    }

    private fun groupUndo(owners: List<Any>, block: () -> Unit, undo: () -> Unit) {
        recording = false
        try { block() } finally { recording = true }
        recordUndo(owners, undo)
    }

    fun beginBulkImport() {
        autosaveEpoch++
        glyphPreviewValues.clear()
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

    /** Non-destructive font overview: all glyphs preview the same instance. */
    val fontPreviewValues = mutableStateMapOf<String, Float>().apply {
        Axis.ALL.forEach { put(it.tag, 0.5f) }
    }
    /** Optional local instance previews, independent of the font-wide setting. */
    val glyphPreviewValues = mutableStateMapOf<String, Map<String, Float>>()

    fun fontValuesFor(name: String): Map<String, Float> =
        glyphPreviewValues[name] ?: fontPreviewValues.toMap()

    fun setFontPreviewAxis(tag: String, value: Float) {
        if (Axis.ALL.none { it.tag == tag }) return
        fontPreviewValues[tag] = value.coerceIn(0f, 1f)
        glyphPreviewValues.clear()
    }

    fun setGlyphPreviewAxis(name: String, tag: String, value: Float) {
        if (Axis.ALL.none { it.tag == tag }) return
        val updated = fontValuesFor(name).toMutableMap()
        updated[tag] = value.coerceIn(0f, 1f)
        glyphPreviewValues[name] = updated
    }

    fun applyGlyphPreviewToAll(name: String) {
        val values = fontValuesFor(name).toMap()
        glyphPreviewValues.clear()
        fontPreviewValues.putAll(values)
    }

    /**
     * Which axis is currently being hand-edited: drives both which lo/hi
     * anchor pair the two corner panels show, and which axis's travel-path
     * overlay the Regular panel draws -- the only pairs guaranteed to pass
     * through Regular by construction, and naturally the same axis a
     * designer editing one wants to see the other for.
     */
    var selectedAxis by mutableStateOf(Axis.WEIGHT)

    /** The project whose glyphs are being edited; null until one is started or opened. */
    var currentProject by mutableStateOf<ProjectInfo?>(null)
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
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

    /** The active Add / Subtract / Convert node tool, or null. Exclusive with Move node and the Pen. */
    var nodeTool by mutableStateOf<NodeTool?>(null)

    /** Whether the suggested-edits row is expanded (it's behind a button, not always on screen). */
    var showSuggestions by mutableStateOf(false)

    /** Last pointer device, used only for the default cross-circle preference. */
    var touchInput by mutableStateOf(false)

    /** Null follows pointer type: enabled for touch, disabled for mouse/stylus. */
    var nodeMoveCircleOverride by mutableStateOf<Boolean?>(null)
    val nodeMoveCircleEnabled: Boolean get() = nodeMoveCircleOverride ?: touchInput

    /**
     * The move circle exists only from the moment a node is picked on the canvas until the next touch
     * anywhere else on the screen (see EditorScreen); the selection itself outlives it.
     */
    var moveCircleVisible by mutableStateOf(false)

    /** The circle's touch area in window pixels (centre, radius), so a touch anywhere else can tell it is elsewhere. Null when it isn't shown. */
    var moveCircleArea: Pair<Offset, Float>? = null

    /** While the circle is being dragged: its fixed offset from the selection, in canvas pixels. */
    var moveCircleHold: Offset? = null

    /** What persists between sessions, read off the live state. */
    fun settings() = AppSettings(
        dark = ThemeState.dark, snap = view.snap, showMetrics = view.showMetrics, showRulers = view.showRulers,
        showGuides = view.showGuides, showMeasure = view.showMeasure, showGhosts = view.showGhosts,
        showTravel = view.showTravel, showGrid = view.showGrid, gridStep = view.gridStep,
        glyphOpacity = view.glyphOpacity, moveCircle = nodeMoveCircleOverride,
    )

    /** Puts saved [s] back: theme, view toggles, grid step, glyph opacity and the move-circle choice. */
    fun applySettings(s: AppSettings) {
        ThemeState.dark = s.dark
        view.snap = s.snap; view.showMetrics = s.showMetrics; view.showRulers = s.showRulers
        view.showGuides = s.showGuides; view.showMeasure = s.showMeasure; view.showGhosts = s.showGhosts
        view.showTravel = s.showTravel; view.showGrid = s.showGrid
        view.gridStep = s.gridStep.takeIf { it > 0f } ?: 50f
        view.glyphOpacity = s.glyphOpacity.coerceIn(0.1f, 1f)
        nodeMoveCircleOverride = s.moveCircle
    }

    fun setNodeMoveCircleEnabled(enabled: Boolean) {
        nodeMoveCircleOverride = enabled
    }

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
        val corners = when {
            g.referenceCorners.isNotEmpty() -> g.referenceCorners
            g.sourceGlyph != null -> sourceCorners(g.sourceGlyph!!) ?: return emptyList()
            else -> return applyAffine(g.state.glyph.contours, placed)
        }
        val result = if (g.axisOverrides.isEmpty() || compatibilityIssue(corners) != null) {
            corners[anchorName] ?: corners["regular"] ?: return emptyList()
        } else {
            val values = Axis.ALL.associate { axis ->
                axis.tag to when (anchorName) {
                    axis.lo -> 0f
                    axis.hi -> 1f
                    else -> 0.5f
                }
            }.toMutableMap().also { it.putAll(g.axisOverrides) }
            interpolateGlyph(corners, values)
        }
        return applyAffine(result.contours, placed)
    }

    /** The ghost's matrix, shifted past the working glyph's advance when it's set to sit beside rather than over it. */
    private fun placement(g: GhostLayer, advance: Float): Affine =
        if (g.beside) compose(translation(advance, 0f), g.matrix) else g.matrix

    /** The ghost as drawn in the Preview: a linked ghost interpolated at the same slider values as the working glyph. */
    fun ghostPreviewContours(g: GhostLayer, advance: Float): List<ContourData> {
        val placed = placement(g, advance)
        val corners = when {
            g.referenceCorners.isNotEmpty() -> g.referenceCorners
            g.sourceGlyph != null -> sourceCorners(g.sourceGlyph!!) ?: return emptyList()
            else -> return applyAffine(g.state.glyph.contours, placed)
        }
        val values = previewValues.toMutableMap().also { it.putAll(g.axisOverrides) }
        val inst = if (compatibilityIssue(corners) == null) interpolateGlyph(corners, values)
            else corners["regular"] ?: return emptyList()
        return applyAffine(inst.contours, placed)
    }

    /** Every visible ghost's outline in [anchorName]'s panel, optionally skipping one (the one being edited). */
    fun visibleGhostContours(anchorName: String, skipId: Int? = null): List<List<ContourData>> =
        if (!view.showGhosts) emptyList() else ghosts.filter { it.visible && it.id != skipId }.map { ghostContours(it, anchorName) }

    /** Undo the most recent edit anywhere: one chronological stack across every anchor, ghost and guide action. */
    fun undo() {
        if (undoActions.isEmpty()) {
            setStatus("Nothing to undo.")
            return
        }
        if (reduction != null) {
            setStatus("Apply or cancel Simplify before undoing.", true)
            return
        }
        val before = snapshot()
        val action = undoActions.removeAt(undoActions.lastIndex).action
        val previous = recording
        recording = false
        try { action() } finally { recording = previous }
        redoStack.add(before)
        if (redoStack.size > 150) redoStack.removeAt(0)
    }

    private fun snapshot() = EditorSnapshot(
        corners = anchors.mapValues { it.value.glyph.deepCopy() },
        guides = guides.toList(),
        metrics = metrics,
        ghosts = ghosts.map { it.toData() },
        ghostIds = ghosts.map { it.id },
    )

    private fun ghostFromData(it: GhostData) = GhostLayer(
        nextGhostId++, it.label, it.contours, it.visible, it.sourceGlyph,
        it.matrix.takeIf { m -> m.size == 6 } ?: IDENTITY_MATRIX, it.beside,
        it.axisOverrides, it.referenceCorners, it.supportedAxes, it.solid, it.color,
    )

    /**
     * Ghost layers taken off the list by [setGhosts], kept by id: older undo entries still point at them,
     * so bringing one back must bring back that same object, not a lookalike. Oldest first, and capped at
     * the undo history's own length ([MAX_RETIRED_GHOSTS]): a layer retired longer ago than that has no
     * history entry left that could bring it back, so remove/undo/redo cycles can't grow memory forever.
     */
    private val retiredGhosts = LinkedHashMap<Int, GhostLayer>()

    /** Makes the ghost list [list], reusing the layers -- shown or retired -- whose ids are in [ids] (see [GhostLayer.applyData]). */
    private fun setGhosts(list: List<GhostData>, ids: List<Int>) {
        activeGhostId = null
        val known = retiredGhosts + ghosts.associateBy { it.id }
        val next = list.mapIndexed { i, d ->
            ids.getOrNull(i)?.let { known[it] }?.also { it.applyData(d) }
                ?: ghostFromData(d).also { attachGhost(it) }
        }
        for (g in ghosts) if (next.none { it === g }) { retiredGhosts.remove(g.id); retiredGhosts[g.id] = g }
        for (g in next) retiredGhosts.remove(g.id)
        while (retiredGhosts.size > MAX_RETIRED_GHOSTS) retiredGhosts.remove(retiredGhosts.keys.first())
        ghosts.clear()
        ghosts.addAll(next)
        for (d in list) d.sourceGlyph?.let { src -> cacheLinked(src) }
    }

    /**
     * Redo the edit Undo last stepped back from: moves outlines, guides, metrics and ghosts to how they were,
     * as ordinary edits of their own (so the redo can itself be undone). Any new edit forgets what was left.
     */
    fun redo() {
        if (reduction != null) {
            setStatus("Apply or cancel Simplify before redoing.", true)
            return
        }
        val target = redoStack.removeLastOrNull() ?: run { setStatus("Nothing to redo."); return }
        val now = snapshot()
        redoing = true
        try {
            val changed = ANCHORS.filter { target.corners[it] != now.corners[it] }
            if (changed.isNotEmpty()) {
                val owners = changed.map { anchors.getValue(it) }
                groupUndo(
                    owners = owners,
                    block = { for (n in changed) anchors.getValue(n).replaceWithHistory(target.corners.getValue(n).deepCopy()) },
                    undo = { owners.forEach { it.undo() } },
                )
            }
            if (target.guides != now.guides) {
                val before = now.guides
                recordUndo { guides.clear(); guides.addAll(before) }
                guides.clear(); guides.addAll(target.guides)
            }
            if (target.metrics != now.metrics) {
                val before = now.metrics
                recordUndo { metrics = before }
                metrics = target.metrics
            }
            if (target.ghosts != now.ghosts || target.ghostIds != now.ghostIds) {
                val before = now.ghosts
                val beforeIds = now.ghostIds
                recordUndo { setGhosts(before, beforeIds) }
                setGhosts(target.ghosts, target.ghostIds)
            }
        } finally { redoing = false }
    }

    /** Switches the active ghost between placement and node editing; node editing bakes/detaches first. */
    fun setGhostNodeMode(nodes: Boolean) {
        val g = activeGhost ?: return
        if (nodes && ghostTransformMode) {
            val original = g.toData()
            recordUndo {
                g.sourceGlyph = original.sourceGlyph
                g.referenceCorners = original.referenceCorners
                g.supportedAxes = original.supportedAxes
                g.axisOverrides.clear()
                g.axisOverrides.putAll(original.axisOverrides)
                g.beside = original.beside
                g.matrix = original.matrix
                g.state.replaceGlyph(GlyphCorner(0f, original.contours.map { it.deepCopy() }.toMutableList()))
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
                } else g.bake(anchors.getValue(activeAnchor).glyph.width)
            } finally {
                recording = true
            }
        }
        ghostTransformMode = !nodes
    }

    /**
     * Select is always an exit from modes which intentionally block normal
     * node hit-testing. It finishes a drawn contour without applying a
     * simplify preview, deleting a ghost or resetting the canvas view.
     */
    fun resumeGlyphSelection() {
        for (state in anchors.values) if (state.drawingContourIndex != null) state.finishContour()
        for (ghost in ghosts) if (ghost.state.drawingContourIndex != null) ghost.state.finishContour()
        reduction = null
        activeGhostId = null
        ghostTransformMode = true
        nodeMoveMode = false
        nodeTool = null
    }

    /** The edit target for menu/keyboard commands: the active ghost, else the active anchor. */
    val commandTarget: AnchorState get() = editTarget(activeAnchor)

    /**
     * True while node-level edits must not run: a Simplify preview is open (its removal keys refer to
     * the current nodes) or a ghost is in Place mode (its nodes aren't shown, so they can't be acted on).
     */
    val nodeEditsLocked: Boolean get() = reduction != null || (activeGhost != null && ghostTransformMode)

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
            val layer = ghostFromData(it)
            attachGhost(layer)
            ghosts.add(layer)
            it.sourceGlyph?.let { src -> cacheLinked(src) }
        }
        metrics = glyph.metrics ?: FontMetrics()
        undoActions.clear()
        retiredGhosts.clear()
        redoStack.clear()
        activeGhostId = null
        reduction = null
        activeAnchor = "regular"
        nodeMoveMode = false
        nodeTool = null
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
        retiredGhosts.clear()
        redoStack.clear()
    }

    fun toGlyph(): Glyph = Glyph(
        corners = cornersSnapshot().mapValues { it.value.deepCopy() }.toMutableMap(),
        guides = guides.toList(),
        ghosts = ghosts.map { it.toData() },
        metrics = metrics,
        outlineFormat = OUTLINE_CUBIC,
    )

    /** Copies the active anchor's outline into every other anchor, seeding matching topology. */
    /**
     * Replaces each of [names]' outlines with [transform] of it as one undo step, so a structural edit
     * that must hit several anchors together is also undone together (never leaving them mismatched).
     */
    fun replaceAnchorsWithHistory(names: List<String>, transform: (GlyphCorner) -> GlyphCorner) {
        val targets = names.map { anchors.getValue(it) }
        if (targets.size == 1) {
            targets[0].replaceWithHistory(transform(targets[0].glyph))
            return
        }
        groupUndo(
            owners = targets,
            block = { targets.forEach { it.replaceWithHistory(transform(it.glyph)) } },
            undo = { targets.forEach { it.undo() } },
        )
    }

    fun copyActiveToOthers() {
        if (activeGhost != null || reduction != null) return // never touch the glyph while a ghost is being edited or a Simplify preview is open
        val src = anchors.getValue(activeAnchor)
        val targets = ANCHORS.filter { it != activeAnchor }.map { anchors.getValue(it) }
        val count = targets.size
        groupUndo(
            owners = targets,
            block = { targets.forEach { it.replaceWithHistory(src.glyph.deepCopy()) } },
            undo = { targets.forEach { it.undo() } },
        )
        setStatus("Copied ${ANCHOR_LABELS[activeAnchor]}'s outline to the other $count anchor(s) — reshape each toward its extreme without adding or removing points.")
    }

    // ------------------------------------------------------------ clipboard

    /**
     * Anchors whose outline has exactly the open anchor's structure (same contours, points and node
     * types). Deleting nodes from one of these deletes the same nodes from all of them, so states that
     * match keep matching. Just the open anchor when it matches nothing, is empty, or a ghost is open.
     */
    fun matchingAnchors(): List<String> {
        if (activeGhost != null) return listOf(activeAnchor)
        val open = anchors.getValue(activeAnchor).glyph
        if (open.contours.isEmpty()) return listOf(activeAnchor)
        val snapshot = cornersSnapshot()
        return ANCHORS.filter {
            it == activeAnchor ||
                (anchors.getValue(it).glyph.contours.isNotEmpty() && compatibilityIssue(snapshot, listOf(activeAnchor, it)) == null)
        }
    }

    private fun linkedNote(group: List<String>): String =
        group.joinToString(", ") { ANCHOR_LABELS[it] ?: it }

    /**
     * The trash. Nodes selected here are deleted from every anchor that matches this one, as a single undo
     * step; handles are this anchor's alone (see [AnchorState.deleteSelected]). A refusal is shown as a status.
     */
    fun trashSelection() {
        val t = commandTarget
        val nodes = t.selection.filter { (ci, pi) -> t.glyph.contours.getOrNull(ci)?.points?.getOrNull(pi)?.onCurve == true }.toSet()
        val group = if (nodes.isEmpty()) listOf(activeAnchor) else matchingAnchors()
        if (group.size < 2 || t !== anchors.getValue(activeAnchor)) {
            t.deleteSelected()?.let { setStatus(it, true) }
            return
        }
        val targets = group.map { anchors.getValue(it) }
        var message: String? = null
        groupUndo(
            owners = targets,
            block = {
                message = t.deleteSelected()
                for (st in targets) if (st !== t) st.replaceWithHistory(deleteSelection(st.glyph, nodes))
            },
            undo = { targets.forEach { it.undo() } },
        )
        setStatus(message ?: "Deleted ${nodes.size} node(s) from ${linkedNote(group)} (they match).")
    }

    /**
     * Add Anchor Point: inserts a node on [hit]'s segment without changing the outline. Every anchor that
     * matches this one gets the same node on the same segment, as one undo step. Returns the new node's key
     * in the edit target, or null if nothing was added.
     */
    fun addNodeAt(hit: SegmentHit): PointKey? {
        if (nodeEditsLocked) return null
        val t = commandTarget
        val linked = t === anchors.getValue(activeAnchor)
        val group = if (linked) matchingAnchors() else listOf(activeAnchor)
        val targets = if (linked) group.map { anchors.getValue(it) } else listOf(t)
        val made = arrayOfNulls<PointKey>(1)
        groupUndo(
            owners = targets,
            block = {
                for (st in targets) {
                    val c = st.glyph.contours.getOrNull(hit.contour) ?: continue
                    val (nc, index) = splitSegment(c, hit.from, hit.t) ?: continue
                    val g = st.glyph.deepCopy()
                    g.contours[hit.contour] = nc
                    st.replaceWithHistory(g)
                    if (st === t) made[0] = hit.contour to index
                }
            },
            undo = { targets.forEach { it.undo() } },
        )
        val key = made[0] ?: return null
        t.selection = setOf(key)
        setStatus(if (targets.size > 1) "Added a node to ${linkedNote(group)} (they match)." else "Added a node.")
        return key
    }

    /** Delete Anchor Point: removes node [key] (from every matching anchor, see [trashSelection]). */
    fun subtractNode(key: PointKey) {
        if (nodeEditsLocked) return
        val t = commandTarget
        if (t.glyph.contours.getOrNull(key.first)?.points?.getOrNull(key.second)?.onCurve != true) return
        t.selection = setOf(key)
        trashSelection()
    }

    /** Convert Point, click: node [key] becomes a corner with no handles. Nothing to do if it already is one. */
    fun convertNodeToCorner(key: PointKey) {
        if (nodeEditsLocked) return
        val t = commandTarget
        val pts = t.glyph.contours.getOrNull(key.first)?.points ?: return
        val node = pts.getOrNull(key.second)?.takeIf { it.onCurve } ?: return
        val arms = listOfNotNull(outArmIndex(pts, key.second), inArmIndex(pts, key.second))
        t.selection = setOf(key)
        if (!node.smooth && arms.all { isRetracted(pts, it) }) return
        t.pushHistory()
        t.replaceGlyph(retractNodeHandles(t.glyph, key))
    }

    /** Regular moved to the midpoint of the open axis's two extremes (see [midpointGlyph]), or null if that isn't possible. */
    fun naturalRegular(): GlyphCorner? {
        val (lo, mid, hi) = axisTriple().map { anchors.getValue(it).glyph }
        if (lo.contours.isEmpty() || hi.contours.isEmpty() || mid.contours.isEmpty()) return null
        return midpointGlyph(lo, mid, hi)
    }

    /** True when Regular is viewable-resettable: the three states match and Regular isn't already at the midpoint. */
    fun regularCanReset(): Boolean {
        val natural = naturalRegular() ?: return false
        return maxPointDistance(natural, anchors.getValue("regular").glyph) > 0.5f
    }

    /** Puts Regular back on the natural path: halfway between the extremes, point for point. One undo step. */
    fun resetRegularToNaturalPath() {
        if (activeGhost != null || reduction != null) return
        val natural = naturalRegular()
        if (natural == null) { setStatus("The extremes must have matching nodes first — use \"Match the extremes' nodes\".", true); return }
        anchors.getValue("regular").replaceWithHistory(natural)
        setStatus("Regular reset to halfway between ${ANCHOR_LABELS[axisTriple()[0]]} and ${ANCHOR_LABELS[axisTriple()[2]]}. Undo puts it back.")
    }

    /** The three anchors of the axis being worked on: its low extreme, Regular, its high extreme. */
    fun axisTriple(): List<String> {
        val axis = Axis.ALL.firstOrNull { it.lo == activeAnchor || it.hi == activeAnchor } ?: selectedAxis
        return listOf(axis.lo, "regular", axis.hi)
    }

    /** The drawn states of [axisTriple]. */
    private fun drawnTriple() = axisTriple().filter { anchors.getValue(it).glyph.contours.isNotEmpty() }

    /**
     * The drawn EXTREMES, on every axis. Node correspondence is worked out between these and only these:
     * Regular is derived from them, so its own layout is never evidence of which node answers to which.
     */
    private fun drawnExtremes() = ANCHORS.filter { it != "regular" && anchors.getValue(it).glyph.contours.isNotEmpty() }

    /** True when Regular is drawn but no longer has the structure the extremes do (so it must be rebuilt from them). */
    private fun regularNeedsRebuild(extremes: List<String>): Boolean {
        val reg = anchors.getValue("regular").glyph
        val first = extremes.firstOrNull() ?: return false
        if (reg.contours.isEmpty()) return false
        val snapshot = cornersSnapshot()
        return compatibilityIssue(mapOf("regular" to reg, first to snapshot.getValue(first)), listOf("regular", first)) != null
    }

    /** True when the drawn extremes don't share one structure, or Regular has fallen out of step with them, so matching has work to do. */
    fun statesDiffer(): Boolean {
        val names = drawnExtremes()
        if (names.size >= 2 && compatibilityIssue(cornersSnapshot(), names) != null) return true
        return regularNeedsRebuild(names)
    }

    /** "Extra Thin 7 · Regular 8 · Extra Black 7": each drawn state's node count. */
    fun nodeCountSummary(): String =
        drawnTriple().joinToString(" · ") { n -> "${ANCHOR_LABELS[n] ?: n} ${anchors.getValue(n).glyph.contours.sumOf { c -> c.points.count { it.onCurve } }}" }

    /**
     * Brings the drawn extremes to one shared structure (see [resolveExtremes]) as one undo step: each node
     * with no counterpart in the other extreme is added there, at the matching place along its outline, or --
     * with [fewest] -- taken out of the extreme that has it. The correspondence is between the extremes; Regular
     * plays no part in it. Afterwards Regular, which can't be matched by itself, is rebuilt from the resolved
     * extremes if its structure no longer fits (a Regular that still fits is left exactly as it is). Says why
     * when it can't.
     */
    private fun matchStates(most: Boolean) {
        if (activeGhost != null || reduction != null) return
        val names = drawnExtremes()
        if (names.size < 2) {
            setStatus("Draw at least two extremes first. Nodes are matched from one extreme to the other, never through Regular.", true)
            return
        }
        val before = names.associateWith { anchors.getValue(it).glyph }
        val out = resolveExtremes(before, if (most) ResolveMode.ADD_MISSING else ResolveMode.MATCH_FEWEST, hub = activeAnchor.takeIf { it in names })
        val resolved = out.glyphs ?: run { setStatus(out.problem ?: "Couldn't match them.", true); return }
        val regOld = anchors.getValue("regular").glyph
        val sample = resolved.values.first()
        val regularFits = regOld.contours.isNotEmpty() && compatibilityIssue(mapOf("regular" to regOld, "x" to sample), listOf("regular", "x")) == null
        val regularNew = if (regularFits) regOld else naturalRegularOf(resolved) ?: regOld
        val next = resolved + ("regular" to regularNew)
        val prior = before + ("regular" to regOld)
        val targets = next.keys.filter { next[it] != prior[it] }.map { anchors.getValue(it) }
        if (targets.isEmpty()) { setStatus("They already match."); return }
        groupUndo(
            owners = targets,
            block = { for ((n, gl) in next) if (gl != prior[n]) anchors.getValue(n).replaceWithHistory(gl) },
            undo = { targets.forEach { it.undo() } },
        )
        val count = sample.contours.sumOf { c -> c.points.count { it.onCurve } }
        val lost = if (out.dropped > 0) " Dropped ${out.dropped} contour(s) the others lacked." else ""
        val supplied = if (out.copied > 0) " ${out.copied} contour(s) were supplied from the extreme that has them, as they are; reshape them." else ""
        val reg = if (regularFits || regOld.contours.isEmpty()) "" else " Regular was rebuilt from the extremes."
        setStatus((if (most) "Added the missing nodes" else "Matched to the fewest nodes") + " across ${names.size} extreme(s): $count nodes in each.$supplied$lost$reg Undo puts it back.")
    }

    fun matchToFewestNodes() = matchStates(most = false)

    /** Gives every sparser state the nodes it is missing, up to the densest state's count. */
    fun addMissingNodes() = matchStates(most = true)

    fun copySelection() {
        if (nodeEditsLocked) return
        val t = commandTarget
        val contours = contoursForClipboard(t.glyph, t.selection)
        if (contours.isEmpty()) { setStatus("Nothing to copy.", true); return }
        clipboard = contours
        setStatus("Copied ${contours.size} contour(s).")
    }

    fun cutSelection() {
        if (nodeEditsLocked) return
        val t = commandTarget
        if (t.selection.isEmpty()) { setStatus("Select points to cut; every contour they touch is cut whole.", true); return }
        copySelection()
        val touched = touchedContours(t.selection)
        val group = if (touched.isEmpty()) listOf(activeAnchor) else matchingAnchors()
        if (group.size < 2) { t.deleteTouchedContours(); return }
        val targets = group.map { anchors.getValue(it) }
        groupUndo(
            owners = targets,
            block = {
                t.deleteTouchedContours()
                for (st in targets) if (st !== t) {
                    val kept = st.glyph.deepCopy().contours.filterIndexed { ci, _ -> ci !in touched }
                    st.replaceWithHistory(GlyphCorner(st.glyph.width, kept.toMutableList()))
                }
            },
            undo = { targets.forEach { it.undo() } },
        )
        setStatus("Cut ${touched.size} contour(s) from ${linkedNote(group)} (they match).")
    }

    fun paste() {
        if (nodeEditsLocked) return
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

    /** A linked reference can vary independently of the glyph being edited. */
    fun setGhostAxis(id: Int, axis: Axis, value: Float?) {
        val ghost = ghosts.firstOrNull { it.id == id } ?: return
        if (!ghost.isLinked) return
        if (ghost.referenceCorners.isNotEmpty() && axis.tag !in ghost.supportedAxes) return
        val old = ghost.axisOverrides[axis.tag]
        val next = value?.coerceIn(0f, 1f)
        if (old == next) return
        recordUndo {
            if (old == null) ghost.axisOverrides.remove(axis.tag) else ghost.axisOverrides[axis.tag] = old
        }
        if (next == null) ghost.axisOverrides.remove(axis.tag) else ghost.axisOverrides[axis.tag] = next
    }

    /**
     * Snapshot source masters so a variable-font ghost remains adjustable
     * after the source file is closed or the project is reopened.
     */
    fun addFontGhost(fontName: String, font: VariableFont, codepoint: Int): Boolean {
        val scale = metrics.unitsPerEm / font.unitsPerEm.toFloat().coerceAtLeast(1f)
        val defaults = font.axes.associate { it.tag to it.default }
        val advance = (font.advanceWidth(codepoint) ?: return false) * scale
        val corners = linkedMapOf<String, GlyphCorner>()
        for (anchor in ANCHORS) {
            val axis = Axis.ALL.firstOrNull { anchor == it.lo || anchor == it.hi }
            val coords = defaults.toMutableMap()
            if (axis != null) font.axes.firstOrNull { it.tag == axis.tag }?.let { info ->
                coords[axis.tag] = if (anchor == axis.lo) info.min else info.max
            }
            val outline = font.outlineAt(codepoint, coords) ?: return false
            val transformed = applyAffine(outline, scalingAbout(scale, scale, 0f, 0f))
            corners[anchor] = GlyphCorner(advance, transformed.map { it.deepCopy() }.toMutableList())
        }
        val label = if (codepoint <= 0xFFFF) codepoint.toChar().toString()
            else "U+${codepoint.toString(16).uppercase()}"
        val layer = GhostLayer(
            nextGhostId++, "$label · $fontName", emptyList(),
            referenceCorners = corners, supportedAxes = font.axes.map { it.tag },
        )
        attachGhost(layer)
        ghosts.add(layer)
        recordUndo { ghosts.remove(layer); if (activeGhostId == layer.id) activeGhostId = null }
        return true
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
                owners = targets,
                block = {
                    for (st in targets) {
                        val reduced = reducedGlyph(st.glyph, removed)
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
