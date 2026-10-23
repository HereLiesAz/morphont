package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions for the editor audit: undo bookkeeping, ghost placement, locked editing and duplicate merging. */
class AuditRegressionTest {
    private fun square() = ContourData(mutableListOf(
        Pt(0f, 0f, true), Pt(0f, 100f, true),
        Pt(100f, 100f, true), Pt(100f, 0f, true),
    ))

    private fun octagon() = ContourData(mutableListOf(
        Pt(30f, 0f, true), Pt(70f, 0f, true), Pt(100f, 30f, true), Pt(100f, 70f, true),
        Pt(70f, 100f, true), Pt(30f, 100f, true), Pt(0f, 70f, true), Pt(0f, 30f, true),
    ))

    private fun loaded(contour: ContourData = square()): AppState = AppState().apply {
        loadGlyph("A", Glyph(corners = mutableMapOf("regular" to GlyphCorner(500f, mutableListOf(contour)))))
    }

    @Test
    fun penUndoThenTapDoesNotCrashOrDrawOnAStaleContour() {
        val app = loaded()
        val node = app.anchors.getValue("regular")
        node.startNewContour()
        assertNotNull(node.drawingContourIndex)
        app.undo() // removes the freshly started contour
        assertNull(node.drawingContourIndex)
        node.addDrawPoint(10f, 10f) // must be a harmless no-op
        assertEquals(1, node.glyph.contours.size)
    }

    @Test
    fun cancelledEmptyContourLeavesNoDeadUndoAction() {
        val app = loaded()
        val regular = app.anchors.getValue("regular")
        regular.selection = setOf(0 to 0)
        regular.nudgeSelected(12f, 0f)
        val other = app.anchors.getValue(Axis.WEIGHT.hi)
        other.startNewContour()
        other.finishContour() // empty contour cancelled: not an edit
        app.undo()
        assertEquals(0f, regular.glyph.contours.single().points.first().x)
    }

    @Test
    fun besideGhostBakesItsOffsetWhenNodeEditingStarts() {
        val app = loaded()
        app.addGhost("ref", listOf(square()))
        val ghost = app.ghosts.single()
        ghost.beside = true
        app.editGhost(ghost.id)
        app.setGhostNodeMode(true)
        assertTrue(!ghost.beside)
        // Drawn one advance (500) to the right before baking; the baked nodes must sit there.
        assertEquals(500f, ghost.state.glyph.contours.single().points.minOf { it.x })
        assertEquals(600f, ghost.state.glyph.contours.single().points.maxOf { it.x })
        app.undo()
        assertTrue(ghost.beside)
        assertTrue(app.ghostTransformMode)
    }

    @Test
    fun nodeCommandsAreIgnoredWhileAGhostIsOnlyBeingPlaced() {
        val app = loaded()
        app.addGhost("ref", listOf(square()))
        val ghost = app.ghosts.single()
        app.editGhost(ghost.id)
        assertTrue(app.nodeEditsLocked)
        val before = ghost.state.glyph.deepCopy()
        ghost.state.selection = setOf(0 to 0, 0 to 1)
        app.cutSelection()
        assertEquals(before, ghost.state.glyph)
        assertNull(app.clipboard)
        app.clipboard = listOf(square())
        app.paste()
        assertEquals(before, ghost.state.glyph)
    }

    @Test
    fun simplifyPreviewIsReadOnly() {
        val app = loaded(octagon())
        val node = app.anchors.getValue("regular")
        node.selection = setOf(0 to 0)
        node.nudgeSelected(5f, 0f)
        app.startReduction()
        assertNotNull(app.reduction)
        assertTrue(app.nodeEditsLocked)
        val before = node.glyph.deepCopy()
        app.clipboard = listOf(square())
        app.cutSelection()
        app.paste()
        app.undo() // would shift the nodes the preview's removal keys point at
        app.startOtherExtremeFromThis()
        assertEquals(before, node.glyph)
        assertNotNull(app.reduction)
        assertTrue(app.anchors.getValue(Axis.WEIGHT.hi).glyph.contours.isEmpty())
    }

    @Test
    fun mergingADuplicateKeepsTheCurveLeavingTheMergedNode() {
        // N0, N1, N2 (a duplicate of N1 with the real out-arm), N3, N4.
        val c = ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(30f, -20f, false), Pt(70f, -20f, false),
            Pt(100f, 0f, true),
            Pt(100f, 0f, true), Pt(130f, 30f, false), Pt(130f, 70f, false),
            Pt(100f, 100f, true), Pt(0f, 100f, true),
        ))
        val merged = withoutPoints(GlyphCorner(500f, mutableListOf(c)), setOf(0 to 4), mergeIntoPrevious = true)
        val got = merged.contours.single().points.map { Triple(it.x, it.y, it.onCurve) }
        assertEquals(listOf(
            Triple(0f, 0f, true), Triple(30f, -20f, false), Triple(70f, -20f, false),
            Triple(100f, 0f, true), Triple(130f, 30f, false), Triple(130f, 70f, false),
            Triple(100f, 100f, true), Triple(0f, 100f, true),
        ), got)
    }
}
