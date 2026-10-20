package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Size
import kotlin.math.hypot
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorStateTest {
    @Test
    fun measurementsAreOnByDefault() {
        val app = AppState()
        assertEquals(true, app.view.showMeasure)
    }

    @Test
    fun simplifyNeverShowsMoreNodesThanItStartedWith() {
        val removals = (0 until 7).map { i -> Removal(0 to (i * 3), i.toFloat()) }
        val preview = ReductionSession(listOf("regular"), removals, 10)
        assertEquals(10, preview.remainingNodes)
        assertEquals(3, preview.minimumNodes)
        preview.removeCount = 3
        assertEquals(7, preview.remainingNodes)
        assertEquals(3, preview.removed.size)
        preview.removeCount = removals.size
        assertEquals(3, preview.remainingNodes)
        preview.removeCount = -5
        assertEquals(10, preview.remainingNodes)
        assertEquals(0, preview.removed.size)
        preview.removeCount = 200
        assertEquals(3, preview.remainingNodes)
        assertEquals(removals.size, preview.removed.size)
    }

    @Test
    fun simplifyCountTracksActualOnCurveNodesAcrossItsRange() {
        val app = AppState()
        val glyph = Glyph()
        glyph.corners["regular"] = GlyphCorner(500f, mutableListOf(
            ContourData((0 until 10).map { i -> Pt(i * 25f, if (i % 2 == 0) 0f else 80f, true) }.toMutableList()),
        ))
        app.loadGlyph("zigzag", glyph)
        app.startReduction()
        val preview = requireNotNull(app.reduction)
        assertEquals(10, preview.totalPoints)
        for (count in 0..preview.order.size) {
            preview.removeCount = count
            val actual = withoutPoints(app.anchors.getValue("regular").glyph, preview.removed)
                .contours.sumOf { contour -> contour.points.count { it.onCurve } }
            assertEquals(actual, preview.remainingNodes)
            assertEquals(10 - count, preview.remainingNodes)
        }
    }

    /**
     * Reproduces the visually inactive glyph: a ghost is active in placement
     * mode, so canvas gestures are intentionally routed away from glyph nodes.
     * Select must return to the glyph in one action, preserving its geometry.
     */
    @Test
    fun selectRecoversFromGhostPlacementWithoutDeletingArtwork() {
        val app = AppState()
        val outline = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 100f, true),
            Pt(100f, 100f, true), Pt(100f, 0f, true),
        ))))
        app.loadGlyph("A", Glyph(corners = mutableMapOf("regular" to outline)))
        val anchor = app.anchors.getValue("regular")
        app.addGhost("reference", outline.contours)
        val layer = app.ghosts.single()
        app.editGhost(layer.id)
        app.nodeMoveMode = true
        app.view.zoom = 2.5f
        assertEquals(layer.state, app.editTarget("regular"))
        assertEquals(true, app.ghostTransformMode)

        app.resumeGlyphSelection()

        assertEquals(null, app.activeGhost)
        assertEquals(anchor, app.editTarget("regular"))
        assertEquals(false, app.nodeMoveMode)
        assertEquals(2.5f, app.view.zoom)
        assertEquals(1, app.ghosts.size) // Exit editing; do not delete the reference.
        assertEquals(4, anchor.glyph.contours.single().points.count { it.onCurve })
        val mapper = SpaceMapper(ViewBox(-50f, -50f, 200f, 200f), androidx.compose.ui.geometry.Size(200f, 200f))
        assertEquals(0 to 0, hitTestPoint(anchor.glyph, mapper, mapper.toCanvas(0f, 0f), 8f, nodeOnly = true))
    }

    @Test
    fun selectCancelsSimplificationInsteadOfKeepingCanvasReadOnly() {
        val app = AppState()
        app.loadGlyph("A", Glyph(corners = mutableMapOf(
            "regular" to GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
                Pt(0f, 0f, true), Pt(0f, 100f, true), Pt(50f, 120f, true),
                Pt(100f, 100f, true), Pt(100f, 0f, true),
            )))),
        )))
        app.startReduction()
        val reduction = requireNotNull(app.reduction)
        reduction.removeCount = 1

        app.resumeGlyphSelection()

        assertEquals(null, app.reduction)
        assertEquals(5, app.anchors.getValue("regular").glyph.contours.single().points.count { it.onCurve })
        assertEquals(null, app.activeGhost)
    }

    @Test
    fun selectEndsPenModeOnHiddenAnchorsAndRestoresPointerTools() {
        val app = AppState()
        val regular = app.anchors.getValue("regular")
        val weight = app.anchors.getValue(Axis.WEIGHT.lo)
        regular.startNewContour()
        regular.addDrawPoint(20f, 30f)
        weight.startNewContour()
        app.activeAnchor = "regular"

        app.resumeGlyphSelection()

        assertEquals(null, regular.drawingContourIndex)
        assertEquals(null, weight.drawingContourIndex)
        assertEquals(1, regular.glyph.contours.single().points.count { it.onCurve })
        assertEquals(0, weight.glyph.contours.size)
    }

    @Test
    fun undoRevertsOnlyTheMostRecentEdit() {
        val state = AnchorState(GlyphCorner())
        state.startNewContour()
        state.addDrawPoint(1f, 1f)
        state.addDrawPoint(2f, 2f)
        assertEquals(6, state.glyph.contours[0].points.size)

        state.undo()
        assertEquals(3, state.glyph.contours[0].points.size)
        assertEquals(1f, state.glyph.contours[0].points[0].x)

        state.undo()
        assertEquals(0, state.glyph.contours[0].points.size)
    }

    @Test
    fun undoStopsAtTheInitialStateInsteadOfNoOpingEarly() {
        val state = AnchorState(GlyphCorner())
        state.startNewContour()
        state.addDrawPoint(1f, 1f)

        state.undo()
        state.undo()
        // No more edits to undo past the initial (empty) state.
        assertEquals(0, state.glyph.contours.size)
    }

    @Test
    fun arrowNudgeMovesNodeAndBothHandlesWhenOnlyHandleIsSelected() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true),
            Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        val before = state.glyph.contours[0].points.map { it.copy2() }
        state.selection = setOf(0 to 1) // out-handle of node 0, not the node
        state.nudgeSelected(5f, 7f)
        val after = state.glyph.contours[0].points
        assertEquals(5f, after[0].x)
        assertEquals(7f, after[0].y)
        assertEquals(before[1].x + 5f, after[1].x)
        assertEquals(before[1].y + 7f, after[1].y)
        assertEquals(before[11].x + 5f, after[11].x)
        assertEquals(before[11].y + 7f, after[11].y)
        assertEquals(before[3].x, after[3].x)
        state.undo()
        assertEquals(0f, state.glyph.contours[0].points[0].x)
    }

    @Test
    fun trashOnAHandleOfACornerNodeDeletesJustThatHandle() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true),
            Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        val before = state.glyph.contours.single().points.map { it.copy2() }
        state.selection = setOf(0 to 1) // just the outgoing handle of corner node 0
        assertEquals(null, state.deleteSelected())
        val pts = state.glyph.contours.single().points
        assertEquals(4, pts.count { it.onCurve }, "the node stays")
        assertTrue(isRetracted(pts, 1), "the handle is retracted onto its node")
        // The node's other handle (its in-arm) is untouched.
        val inArm = inArmIndex(pts, 0)!!
        assertEquals(before[inArm].x, pts[inArm].x)
        assertEquals(before[inArm].y, pts[inArm].y)
        assertTrue(!isRetracted(pts, inArm))
        state.undo()
        assertTrue(!isRetracted(state.glyph.contours.single().points, 1))
    }

    @Test
    fun aMirroredNodeKeepsBothHandlesAndSaysSo() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true), Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        state.glyph.contours.single().points[0].smooth = true
        val before = state.glyph
        state.selection = setOf(0 to 1)
        val message = state.deleteSelected()
        assertTrue(message != null && "corner" in message, "expected a hint to make it a corner, was $message")
        assertEquals(before, state.glyph)
        assertEquals(setOf(0 to 1), state.selection)
    }

    @Test
    fun deletingANodeStillTakesItsHandlesAndASelectedHandleOfItGoesWithIt() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true), Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        state.selection = setOf(0 to 0, 0 to 1)
        state.deleteSelected()
        val contour = state.glyph.contours.single()
        assertEquals(3, contour.points.count { it.onCurve })
        assertEquals(9, contour.points.size)
    }

    @Test
    fun makingTheNodeMirroredBringsADeletedHandleBackAtFullLength() {
        val g = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true), Pt(90f, 90f, true), Pt(90f, 0f, true),
        ))))).glyph
        val pts = g.contours.single().points
        val outLen = hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
        val state = AnchorState(g)
        state.selection = setOf(0 to 1)
        state.deleteSelected()
        assertTrue(isRetracted(state.glyph.contours.single().points, 1))
        state.selection = setOf(0 to 0)
        state.toggleSmoothSelected()
        val back = state.glyph.contours.single().points
        assertTrue(!isRetracted(back, 1) || !isRetracted(back, inArmIndex(back, 0)!!), "a handle should be back")
        val a = hypot(back[1].x - back[0].x, back[1].y - back[0].y)
        val b = inArmIndex(back, 0)!!.let { hypot(back[it].x - back[0].x, back[it].y - back[0].y) }
        assertTrue(kotlin.math.abs(a - b) < 0.01f, "mirrored: both handles the same length ($a vs $b)")
        assertTrue(a >= outLen * 0.99f || b >= outLen * 0.99f)
    }

    @Test
    fun aRetractedHandleCannotBeHitOrSnappedTo() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true), Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        state.selection = setOf(0 to 1)
        state.deleteSelected()
        val mapper = SpaceMapper(ViewBox(-10f, -10f, 120f, 120f), Size(120f, 120f))
        val at = mapper.toCanvas(0f, 0f)
        assertEquals(0 to 0, hitTestPoint(state.glyph, mapper, at, 6f), "the node, not the invisible handle")
        val targets = buildSnapTargets(state.glyph, emptySet(), FontMetrics(), emptyList(), emptyList(), null, forHandles = true)
        val pts = state.glyph.contours.single().points
        val live = pts.indices.count { !pts[it].onCurve && !isRetracted(pts, it) }
        assertEquals(live, targets.xs.count { it.label == null }, "only visible handles are snap targets")
    }

    @Test
    fun nodesSnapToNodesAndHandlesToHandles() {
        val g = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(10f, 10f, true), Pt(10f, 40f, true), Pt(80f, 90f, true), Pt(90f, 10f, true),
        ))))).glyph
        val pts = g.contours.single().points
        val handleXs = pts.filter { !it.onCurve }.map { it.x }.toSet()
        val nodeXs = pts.filter { it.onCurve }.map { it.x }.toSet()
        val forNodes = buildSnapTargets(g, emptySet(), FontMetrics(), emptyList(), emptyList(), null)
        val forHandles = buildSnapTargets(g, emptySet(), FontMetrics(), emptyList(), emptyList(), null, forHandles = true)
        // beyond the origin/advance lines every target is a node (resp. a handle)
        val extraNode = forNodes.xs.filter { it.label == null }.map { it.pos }.toSet()
        val extraHandle = forHandles.xs.filter { it.label == null }.map { it.pos }.toSet()
        assertEquals(nodeXs, extraNode)
        assertEquals(handleXs, extraHandle)
    }

    @Test
    fun copyActiveToOthersIsUndoable() {
        val app = AppState()
        app.anchors.getValue("regular").startNewContour()
        app.anchors.getValue("regular").addDrawPoint(5f, 5f)

        val target = Axis.WEIGHT.lo
        app.anchors.getValue(target).startNewContour()
        app.anchors.getValue(target).addDrawPoint(9f, 9f)
        val before = app.anchors.getValue(target).glyph.deepCopy()

        app.copyActiveToOthers()

        val copied = app.anchors.getValue(target).glyph
        assertEquals(3, copied.contours[0].points.size)
        assertEquals(5f, copied.contours[0].points[0].x)

        app.anchors.getValue(target).undo()

        val restored = app.anchors.getValue(target).glyph
        assertEquals(before.contours[0].points.size, restored.contours[0].points.size)
        assertEquals(before.contours[0].points[0].x, restored.contours[0].points[0].x)
    }

    @Test
    fun globalUndoEvictionTrimsTheOwnersPrivateHistory() {
        val app = AppState()
        val a = app.anchors.getValue(ANCHORS[0])
        val b = app.anchors.getValue(ANCHORS[1])
        a.pushHistory()
        a.replaceGlyph(GlyphCorner(0f, mutableListOf(ContourData(mutableListOf(Pt(1f, 1f, true))))))
        repeat(150) { b.pushHistory() } // 151 global entries: a's only entry is evicted
        repeat(150) { app.undo() }
        app.undo() // nothing left globally
        a.undo() // a's private history must have been trimmed too
        assertEquals(1, a.glyph.contours.size)
    }

    @Test
    fun undoingTheFirstPointKeepsDrawingModeSoTheNextTapAddsAPoint() {
        val state = AnchorState(GlyphCorner())
        state.startNewContour()
        state.addDrawPoint(1f, 1f)
        state.undo()
        assertEquals(0, state.drawingContourIndex)
        state.addDrawPoint(5f, 5f)
        assertEquals(1, state.glyph.contours.size)
        assertEquals(5f, state.glyph.contours[0].points[0].x)
    }

    @Test
    fun groupedUndoSurvivesATargetHistoryHittingItsCap() {
        val app = AppState()
        val src = app.anchors.getValue(app.activeAnchor)
        src.replaceGlyph(GlyphCorner(0f, mutableListOf(ContourData(mutableListOf(Pt(1f, 1f, true))))))
        app.copyActiveToOthers()
        val target = app.anchors.getValue(ANCHORS.first { it != app.activeAnchor })
        val other = app.anchors.getValue(ANCHORS.last { it != app.activeAnchor && app.anchors.getValue(it) !== target })
        repeat(80) { target.pushHistory() } // pushes the target's copy step out of its private history
        repeat(79) { app.undo() }
        assertEquals(1, other.glyph.contours.size) // the grouped step must not have been consumed early
        app.undo()
        assertEquals(0, other.glyph.contours.size)
    }
}
