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
        assertEquals(2, state.glyph.contours[0].points.size)

        state.undo()
        assertEquals(1, state.glyph.contours[0].points.size)
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
        assertEquals(1, copied.contours[0].points.size)
        assertEquals(5f, copied.contours[0].points[0].x)

        app.anchors.getValue(target).undo()

        val restored = app.anchors.getValue(target).glyph
        assertEquals(before.contours[0].points.size, restored.contours[0].points.size)
        assertEquals(before.contours[0].points[0].x, restored.contours[0].points[0].x)
    }
}
