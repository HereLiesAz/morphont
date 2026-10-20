package com.hereliesaz.morphont

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three states of an axis keep the same nodes: delete in one deletes in all while they match; trim fixes a mismatch. */
class LinkedStatesTest {
    private val lo = Axis.WEIGHT.lo
    private val hi = Axis.WEIGHT.hi

    private fun ring(n: Int, r: Float): GlyphCorner {
        val k = 4f / 3f * tan(PI.toFloat() / (2f * n))
        val pts = mutableListOf<Pt>()
        for (i in 0 until n) {
            val a = 2 * PI.toFloat() * i / n; val a2 = 2 * PI.toFloat() * (i + 1) / n
            val l = r * k
            pts.add(Pt(r * cos(a), r * sin(a), true, smooth = true))
            pts.add(Pt(r * cos(a) - r * sin(a) * k, r * sin(a) + r * cos(a) * k, false))
            pts.add(Pt(r * cos(a2) + r * sin(a2) * k, r * sin(a2) - r * cos(a2) * k, false))
        }
        return GlyphCorner(600f, mutableListOf(ContourData(pts)))
    }

    private fun app(thin: Int, regular: Int, black: Int): AppState {
        val app = AppState()
        app.anchors.getValue(lo).loadFresh(ring(thin, 150f))
        app.anchors.getValue("regular").loadFresh(ring(regular, 200f))
        app.anchors.getValue(hi).loadFresh(ring(black, 260f))
        app.selectedAxis = Axis.WEIGHT
        return app
    }

    private fun nodes(app: AppState, name: String) = app.anchors.getValue(name).glyph.contours.sumOf { c -> c.points.count { it.onCurve } }

    @Test fun deletingANodeInOneMatchingStateDeletesItInAllThree() {
        val app = app(8, 8, 8)
        app.activeAnchor = hi
        app.anchors.getValue(hi).selection = setOf(0 to 3) // node 1 (points 0,1,2 | 3,4,5 ...)
        app.trashSelection()
        for (n in listOf(lo, "regular", hi)) assertEquals(7, nodes(app, n), n)
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)), "they still match")
        // One undo puts all three back.
        app.undo()
        for (n in listOf(lo, "regular", hi)) assertEquals(8, nodes(app, n), n)
    }

    @Test fun aStateThatDoesNotMatchIsLeftAlone() {
        val app = app(8, 8, 6)
        app.activeAnchor = "regular"
        app.anchors.getValue("regular").selection = setOf(0 to 3)
        app.trashSelection()
        assertEquals(7, nodes(app, "regular"))
        assertEquals(7, nodes(app, lo), "the matching state follows")
        assertEquals(6, nodes(app, hi), "the one that did not match is untouched")
    }

    @Test fun deletingAHandleIsNotLinked() {
        val app = app(8, 8, 8)
        app.activeAnchor = hi
        val st = app.anchors.getValue(hi)
        st.glyph.contours[0].points[0].smooth = false
        val before = app.anchors.getValue("regular").glyph
        st.selection = setOf(0 to 1)
        app.trashSelection()
        assertTrue(isRetracted(st.glyph.contours[0].points, 1))
        assertEquals(before, app.anchors.getValue("regular").glyph, "other states are not touched by a handle edit")
    }

    @Test fun cuttingAContourCutsItFromEveryMatchingState() {
        val app = AppState()
        fun two() = GlyphCorner(600f, mutableListOf(ring(6, 200f).contours[0], ring(5, 80f).contours[0]))
        for (n in listOf(lo, "regular", hi)) app.anchors.getValue(n).loadFresh(two())
        app.activeAnchor = "regular"
        app.anchors.getValue("regular").selection = setOf(1 to 0)
        app.cutSelection()
        for (n in listOf(lo, "regular", hi)) assertEquals(1, app.anchors.getValue(n).glyph.contours.size, n)
        app.undo()
        for (n in listOf(lo, "regular", hi)) assertEquals(2, app.anchors.getValue(n).glyph.contours.size, n)
    }

    @Test fun trimGivesEveryStateOnlyAsManyNodesAsTheFewest() {
        val app = app(10, 12, 7)
        app.activeAnchor = "regular"
        val plan = assertNotNull(app.trimPlan())
        assertEquals(setOf(lo, "regular"), plan.keys, "the smallest state is left alone")
        app.trimToFewestNodes()
        for (n in listOf(lo, "regular", hi)) assertEquals(7, nodes(app, n), n)
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
        assertNull(app.trimPlan(), "nothing left to trim")
        app.undo()
        assertEquals(10, nodes(app, lo)); assertEquals(12, nodes(app, "regular")); assertEquals(7, nodes(app, hi))
    }

    @Test fun trimmedStatesStayOnTheirOwnOriginalShape() {
        val app = app(10, 12, 7)
        val original = app.anchors.getValue("regular").glyph
        app.trimToFewestNodes()
        val trimmed = app.anchors.getValue("regular").glyph
        // The ring has radius 200; every trimmed point must still be on (or very near) the circle.
        val worst = trimmed.contours[0].points.filter { it.onCurve }.maxOf { kotlin.math.abs(kotlin.math.hypot(it.x, it.y) - 200f) }
        assertTrue(worst < 0.5f, "trimmed nodes should stay on the original outline, off by $worst")
        assertTrue(original.contours[0].points.size > trimmed.contours[0].points.size)
    }

    @Test fun nothingToTrimWhenTheStatesAgreeOrCannotBeCompared() {
        assertNull(app(8, 8, 8).trimPlan())
        val app = AppState()
        app.anchors.getValue("regular").loadFresh(ring(8, 200f))
        app.selectedAxis = Axis.WEIGHT
        assertNull(app.trimPlan(), "only one state is drawn")
        app.anchors.getValue(hi).loadFresh(GlyphCorner(600f, mutableListOf(ring(6, 200f).contours[0], ring(5, 50f).contours[0])))
        assertNull(app.trimPlan(), "different contour counts can't be compared")
    }
}
