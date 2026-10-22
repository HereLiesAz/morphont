package com.hereliesaz.morphont

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Redo steps forward through what Undo stepped back from, and any new edit ends that line. */
class RedoTest {
    private fun ring(n: Int, r: Float): GlyphCorner {
        val k = 4f / 3f * tan(PI.toFloat() / (2f * n))
        val pts = mutableListOf<Pt>()
        for (i in 0 until n) {
            val a = 2 * PI.toFloat() * i / n; val a2 = 2 * PI.toFloat() * (i + 1) / n
            pts.add(Pt(r * cos(a), r * sin(a), true, smooth = true))
            pts.add(Pt(r * cos(a) - r * sin(a) * k, r * sin(a) + r * cos(a) * k, false))
            pts.add(Pt(r * cos(a2) + r * sin(a2) * k, r * sin(a2) - r * cos(a2) * k, false))
        }
        return GlyphCorner(600f, mutableListOf(ContourData(pts)))
    }

    private fun app(): AppState {
        val app = AppState()
        for ((n, r) in listOf(Axis.WEIGHT.lo to 150f, "regular" to 200f, Axis.WEIGHT.hi to 260f)) app.anchors.getValue(n).loadFresh(ring(8, r))
        app.selectedAxis = Axis.WEIGHT
        app.currentGlyphName = "o"
        return app
    }

    private fun nodes(app: AppState, n: String) = app.anchors.getValue(n).glyph.contours.sumOf { c -> c.points.count { it.onCurve } }

    @Test fun redoRestoresWhatUndoRemoved() {
        val app = app()
        val st = app.anchors.getValue("regular")
        val before = st.glyph.deepCopy()
        st.selection = setOf(0 to 0)
        st.nudgeSelected(25f, 0f)
        val edited = st.glyph.deepCopy()
        assertTrue(!app.canRedo)
        app.undo()
        assertEquals(before, st.glyph)
        assertTrue(app.canRedo)
        app.redo()
        assertEquals(edited, st.glyph)
        assertTrue(!app.canRedo)
        app.undo()
        assertEquals(before, st.glyph, "the redo itself can be undone")
    }

    @Test fun aNewEditEndsTheRedoLine() {
        val app = app()
        val st = app.anchors.getValue("regular")
        st.selection = setOf(0 to 0)
        st.nudgeSelected(25f, 0f)
        app.undo()
        assertTrue(app.canRedo)
        st.selection = setOf(0 to 3)
        st.nudgeSelected(0f, 10f)
        assertTrue(!app.canRedo)
    }

    @Test fun redoReplaysAGroupedEditAcrossStates() {
        val app = app()
        app.activeAnchor = "regular"
        app.addNodeAt(SegmentHit(0, 0, 0.5f))
        for (n in listOf(Axis.WEIGHT.lo, "regular", Axis.WEIGHT.hi)) assertEquals(9, nodes(app, n))
        app.undo()
        for (n in listOf(Axis.WEIGHT.lo, "regular", Axis.WEIGHT.hi)) assertEquals(8, nodes(app, n))
        app.redo()
        for (n in listOf(Axis.WEIGHT.lo, "regular", Axis.WEIGHT.hi)) assertEquals(9, nodes(app, n), n)
        app.undo()
        for (n in listOf(Axis.WEIGHT.lo, "regular", Axis.WEIGHT.hi)) assertEquals(8, nodes(app, n), n)
    }

    @Test fun redoReplaysGuides() {
        val app = app()
        app.replaceGuide(null, Guide(true, 40f))
        assertEquals(1, app.guides.size)
        app.undo()
        assertEquals(0, app.guides.size)
        app.redo()
        assertEquals(listOf(Guide(true, 40f)), app.guides.toList())
    }

    @Test fun nothingToRedoIsHarmless() {
        val app = app()
        val before = app.anchors.getValue("regular").glyph.deepCopy()
        app.redo()
        assertEquals(before, app.anchors.getValue("regular").glyph)
    }
}
