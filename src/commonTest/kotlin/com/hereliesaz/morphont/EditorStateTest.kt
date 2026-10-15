package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorStateTest {
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
    fun trashOnSelectedHandleDeletesItsOwnerAndBothHandles() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true),
            Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        state.selection = setOf(0 to 1) // just the outgoing handle of node 0
        state.deleteSelected()
        val contour = state.glyph.contours.single()
        assertEquals(3, contour.points.count { it.onCurve })
        assertEquals(9, contour.points.size)
        assertEquals(0f, contour.points.first().x)
        assertEquals(90f, contour.points.first().y)
        state.undo()
        assertEquals(4, state.glyph.contours.single().points.count { it.onCurve })
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
}
