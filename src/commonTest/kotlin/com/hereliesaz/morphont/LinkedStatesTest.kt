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

/**
 * The three states of an axis keep the same nodes: delete in one deletes in all while they match. A mismatch is
 * resolved between the two EXTREMES; Regular is rebuilt from them and never decides anything.
 */
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

    @Test fun matchGivesEveryStateOnlyAsManyNodesAsTheFewest() {
        val app = app(10, 12, 7)
        app.activeAnchor = "regular"
        assertTrue(app.statesDiffer())
        app.matchToFewestNodes()
        for (n in listOf(lo, "regular", hi)) assertEquals(7, nodes(app, n), n)
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
        assertTrue(!app.statesDiffer(), "nothing left to match")
        app.undo()
        assertEquals(10, nodes(app, lo)); assertEquals(12, nodes(app, "regular")); assertEquals(7, nodes(app, hi))
    }

    @Test fun matchedExtremesStayOnTheirOwnOriginalShapeAndRegularIsRebuiltBetweenThem() {
        val app = app(10, 12, 7)
        app.matchToFewestNodes()
        for ((n, r) in listOf(lo to 150f, hi to 260f)) {
            val worst = app.anchors.getValue(n).glyph.contours[0].points.filter { it.onCurve }.maxOf { kotlin.math.abs(kotlin.math.hypot(it.x, it.y) - r) }
            assertTrue(worst < 0.5f, "$n nodes should stay on the original outline, off by $worst")
        }
        // Regular's own 12-node ring was no evidence of anything: it is rebuilt halfway between the extremes (~205).
        val reg = app.anchors.getValue("regular").glyph.contours[0].points.filter { it.onCurve }.map { kotlin.math.hypot(it.x, it.y) }
        assertTrue(reg.all { it in 150f..260f } && kotlin.math.abs(reg.average() - 205f) < 12f, "Regular should sit between the extremes, mean ${reg.average()}")
    }

    @Test fun aRegularThatStillFitsIsLeftExactlyAsItIs() {
        val app = app(8, 8, 8)
        val nudged = app.anchors.getValue("regular").glyph.deepCopy().also { it.contours[0].points[0].x += 7f }
        app.anchors.getValue("regular").loadFresh(nudged)
        app.addMissingNodes()
        assertEquals(nudged, app.anchors.getValue("regular").glyph, "nothing to resolve, so Regular, nudge and all, is untouched")
    }

    @Test fun matchNeedsTwoExtremesAndNeverUsesRegularAsOne() {
        val app = AppState()
        app.anchors.getValue(lo).loadFresh(ring(6, 150f))
        app.anchors.getValue("regular").loadFresh(ring(9, 200f))
        app.selectedAxis = Axis.WEIGHT
        app.matchToFewestNodes()
        assertTrue(app.statusIsError)
        assertEquals(9, nodes(app, "regular"), "a lone extreme and Regular are not a pair to match")
    }

    @Test fun matchTurnsAndStartsEachExtremeToLineUpWithTheOther() {
        val thin = ring(6, 150f)
        val turned = ring(6, 200f).contours[0].points.let { p -> ContourData(p.reversed().let { r -> (r.drop(7) + r.take(7)).toMutableList() }) }
        val out = resolveExtremes(mapOf(lo to thin, hi to GlyphCorner(600f, mutableListOf(turned))), ResolveMode.MATCH_FEWEST)
        val g = assertNotNull(out.glyphs, out.problem)
        fun angles(n: String) = g.getValue(n).contours[0].points.filter { it.onCurve }.map { kotlin.math.atan2(it.y, it.x) }
        for ((a, b) in angles(lo).zip(angles(hi))) assertTrue(kotlin.math.abs(a - b) < 1e-2f, "node angles $a vs $b")
    }

    @Test fun matchPairsContoursBySizeAndDropsTheUnpaired() {
        val app = AppState()
        app.anchors.getValue(lo).loadFresh(GlyphCorner(600f, mutableListOf(ring(8, 150f).contours[0], ring(5, 40f).contours[0])))
        app.anchors.getValue("regular").loadFresh(ring(6, 200f))
        app.anchors.getValue(hi).loadFresh(ring(7, 260f))
        app.selectedAxis = Axis.WEIGHT
        app.matchToFewestNodes()
        // The extremes are 8 and 7 nodes; the lone small contour has no partner and goes.
        for (n in listOf(lo, "regular", hi)) { assertEquals(1, app.anchors.getValue(n).glyph.contours.size, n); assertEquals(7, nodes(app, n), n) }
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
    }

    @Test fun aTwoNodeContourKeepsItsNodesWhereTheyWereAndStillMatches() {
        val lens = ContourData(mutableListOf(
            Pt(-50f, 0f, true), Pt(-50f, 40f, false), Pt(50f, 40f, false),
            Pt(50f, 0f, true), Pt(50f, -40f, false), Pt(-50f, -40f, false),
        ))
        val app = AppState()
        app.anchors.getValue(lo).loadFresh(GlyphCorner(600f, mutableListOf(ring(6, 150f).contours[0], lens)))
        app.anchors.getValue("regular").loadFresh(GlyphCorner(600f, mutableListOf(ring(6, 200f).contours[0], ring(3, 50f).contours[0])))
        app.anchors.getValue(hi).loadFresh(GlyphCorner(600f, mutableListOf(ring(6, 260f).contours[0], ring(4, 60f).contours[0])))
        app.selectedAxis = Axis.WEIGHT
        app.matchToFewestNodes()
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
        val counts = listOf(lo, "regular", hi).map { app.anchors.getValue(it).glyph.contours[1].points.count { p -> p.onCurve } }
        assertEquals(1, counts.toSet().size, "every anchor ends with the same node count: $counts")
        assertTrue(counts[0] >= 3)
        val before = lens.points.filter { it.onCurve }.map { it.x to it.y }
        val after = app.anchors.getValue(lo).glyph.contours[1].points.filter { it.onCurve }.map { it.x to it.y }
        assertTrue(before.all { b -> after.any { a -> kotlin.math.abs(a.first - b.first) < 0.01f && kotlin.math.abs(a.second - b.second) < 0.01f } }, "the original nodes are still where they were")
    }

    @Test fun nothingToMatchWhenTheStatesAgreeOrOnlyOneIsDrawn() {
        assertTrue(!app(8, 8, 8).statesDiffer())
        val app = AppState()
        app.anchors.getValue("regular").loadFresh(ring(8, 200f))
        app.selectedAxis = Axis.WEIGHT
        assertTrue(!app.statesDiffer(), "only one state is drawn")
    }

    @Test fun addMissingNodesGivesEveryAnchorTheNodesTheOtherExtremeHad() {
        val app = app(10, 12, 7)
        app.activeAnchor = "regular"
        app.addMissingNodes()
        val counts = listOf(lo, "regular", hi).map { nodes(app, it) }
        assertEquals(1, counts.toSet().size, "one structure everywhere: $counts")
        assertTrue(counts[0] >= 10, "nothing the 10-node extreme had is lost: $counts")
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
        assertTrue(!app.statesDiffer())
        app.undo()
        assertEquals(10, nodes(app, lo)); assertEquals(12, nodes(app, "regular")); assertEquals(7, nodes(app, hi))
    }

    @Test fun addedNodesDoNotMoveTheOutline() {
        val app = app(6, 6, 12)
        app.addMissingNodes()
        // ring of radius 150 sampled at its nodes: every node, old or new, still sits on the circle.
        val worst = app.anchors.getValue(lo).glyph.contours[0].points.filter { it.onCurve }
            .maxOf { kotlin.math.abs(kotlin.math.hypot(it.x, it.y) - 150f) }
        assertTrue(worst < 1.5f, "added nodes should stay on the outline, off by $worst")
    }

    @Test fun addMissingNodesSuppliesAContourOnlyOneExtremeHas() {
        val app = AppState()
        app.anchors.getValue(lo).loadFresh(GlyphCorner(600f, mutableListOf(ring(8, 150f).contours[0], ring(5, 40f).contours[0])))
        app.anchors.getValue("regular").loadFresh(ring(6, 200f))
        app.anchors.getValue(hi).loadFresh(ring(7, 260f))
        app.selectedAxis = Axis.WEIGHT
        app.addMissingNodes()
        for (n in listOf(lo, "regular", hi)) assertEquals(2, app.anchors.getValue(n).glyph.contours.size, n)
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
        assertTrue(app.status.contains("supplied"), app.status)
    }

    @Test fun matchingReachesTheOtherAxesSoRegularStaysCompatibleWithAll() {
        // Weight is 8/8/6; Width's extremes (drawn too) have 8. Matching Weight to the fewest changes
        // Regular, so Width's extremes must come along or the whole glyph stops interpolating.
        val app = app(8, 8, 6)
        app.anchors.getValue(Axis.WIDTH.lo).loadFresh(ring(8, 180f))
        app.anchors.getValue(Axis.WIDTH.hi).loadFresh(ring(8, 220f))
        app.matchToFewestNodes()
        val drawn = listOf(lo, "regular", hi, Axis.WIDTH.lo, Axis.WIDTH.hi)
        for (n in drawn) assertEquals(6, nodes(app, n), n)
        assertNull(app.compatibility(drawn))
        // One undo step puts all five back.
        app.undo()
        assertEquals(8, nodes(app, Axis.WIDTH.lo))
        assertEquals(6, nodes(app, hi))
        assertEquals(8, nodes(app, "regular"))
    }
}
