package com.hereliesaz.morphont

import androidx.compose.runtime.getValue
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
    var glyph by mutableStateOf(initial)
        private set

    var selection by mutableStateOf<Set<PointKey>>(emptySet())
    var drawingContourIndex by mutableStateOf<Int?>(null)

    private val history: SnapshotStateList<GlyphCorner> = SnapshotStateList<GlyphCorner>().apply { add(initial.deepCopy()) }

    fun replaceGlyph(new: GlyphCorner) {
        glyph = new
    }

    fun pushHistory() {
        history.add(glyph.deepCopy())
        if (history.size > 80) history.removeAt(0)
    }

    fun undo() {
        if (history.size <= 1) return
        history.removeAt(history.lastIndex)
        glyph = history.last().deepCopy()
        selection = emptySet()
    }

    fun loadFresh(new: GlyphCorner) {
        glyph = new
        selection = emptySet()
        drawingContourIndex = null
        history.clear()
        history.add(new.deepCopy())
    }

    fun startNewContour() {
        pushHistory()
        val updated = glyph.deepCopy()
        updated.contours.add(ContourData())
        drawingContourIndex = updated.contours.size - 1
        selection = emptySet()
        glyph = updated
    }

    fun addDrawPoint(x: Float, y: Float) {
        val ci = drawingContourIndex ?: return
        pushHistory()
        val updated = glyph.deepCopy()
        updated.contours[ci].points.add(Pt(x, y, onCurve = true))
        glyph = updated
    }

    fun toggleTypeSelected() {
        if (selection.isEmpty()) return
        pushHistory()
        val updated = glyph.deepCopy()
        for ((ci, pi) in selection) {
            val p = updated.contours[ci].points[pi]
            p.onCurve = !p.onCurve
        }
        glyph = updated
    }

    fun deleteSelected() {
        if (selection.isEmpty()) return
        pushHistory()
        val updated = glyph.deepCopy()
        val newContours = updated.contours.mapIndexed { ci, c ->
            ContourData(c.points.filterIndexed { pi, _ -> (ci to pi) !in selection }.toMutableList())
        }.filter { it.points.isNotEmpty() }.toMutableList()
        updated.contours.clear()
        updated.contours.addAll(newContours)
        selection = emptySet()
        drawingContourIndex = null
        glyph = updated
    }
}

/** Overall application state: every axis's anchors, preview and UI selections. */
class AppState {
    val anchors: Map<String, AnchorState> = ANCHORS.associateWith { AnchorState(GlyphCorner()) }

    var activeAnchor by mutableStateOf(Axis.WEIGHT.lo)

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

    fun setStatus(msg: String, isError: Boolean = false) {
        status = msg
        statusIsError = isError
    }

    fun cornersSnapshot(): Map<String, GlyphCorner> = anchors.mapValues { it.value.glyph }

    fun compatibility(names: List<String> = ANCHORS): String? =
        compatibilityIssue(cornersSnapshot(), names)

    fun loadGlyph(name: String, glyph: Glyph) {
        currentGlyphName = name
        for (anchor in ANCHORS) {
            anchors.getValue(anchor).loadFresh(glyph.corners[anchor]?.deepCopy() ?: GlyphCorner())
        }
        setStatus("Loaded \"$name\".")
    }

    /** Clears the open glyph (used after loading a whole project, since the previously open glyph may no longer exist under that name). */
    fun clearEditor() {
        currentGlyphName = null
        for (anchor in ANCHORS) {
            anchors.getValue(anchor).loadFresh(GlyphCorner())
        }
    }

    fun toGlyph(): Glyph = Glyph(
        corners = cornersSnapshot().mapValues { it.value.deepCopy() }.toMutableMap(),
    )

    /** Copies the active anchor's outline into every other anchor, seeding matching topology. */
    fun copyActiveToOthers() {
        val src = anchors.getValue(activeAnchor)
        var count = 0
        for (name in ANCHORS) {
            if (name == activeAnchor) continue
            val dst = anchors.getValue(name)
            dst.pushHistory()
            dst.loadFresh(src.glyph.deepCopy())
            count++
        }
        setStatus("Copied ${ANCHOR_LABELS[activeAnchor]}'s outline to the other $count anchor(s) -- reshape each toward its extreme without adding or removing points.")
    }
}
