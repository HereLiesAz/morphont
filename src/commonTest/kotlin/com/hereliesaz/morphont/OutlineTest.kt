package com.hereliesaz.morphont

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlineTest {
    private fun on(x: Float, y: Float, smooth: Boolean = false) = Pt(x, y, true, smooth)
    private fun off(x: Float, y: Float) = Pt(x, y, false)
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
    private fun assertPt(x: Float, y: Float, p: Pt) = assertTrue(near(x, p.x) && near(y, p.y), "expected ($x, $y), got (${p.x}, ${p.y})")

    private fun cubicAt(p0: Pt, c1: Pt, c2: Pt, p1: Pt, t: Float): Pair<Float, Float> {
        val u = 1f - t
        fun f(a: Float, b: Float, c: Float, d: Float) = u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
        return f(p0.x, c1.x, c2.x, p1.x) to f(p0.y, c1.y, c2.y, p1.y)
    }

    private fun quadAt(p0: Pt, c: Pt, p1: Pt, t: Float): Pair<Float, Float> {
        val u = 1f - t
        return (u * u * p0.x + 2 * u * t * c.x + t * t * p1.x) to (u * u * p0.y + 2 * u * t * c.y + t * t * p1.y)
    }

    @Test
    fun singleOffCurveBecomesAnExactCubic() {
        val q = ContourData(mutableListOf(on(0f, 0f), off(30f, 90f), on(90f, 0f)))
        val c = quadraticToCubic(q)
        assertEquals(listOf(true, false, false, true), c.points.map { it.onCurve })
        assertPt(20f, 60f, c.points[1])
        assertPt(50f, 60f, c.points[2])
        for (t in listOf(0.1f, 0.37f, 0.5f, 0.9f)) {
            val (qx, qy) = quadAt(q.points[0], q.points[1], q.points[2], t)
            val (cx, cy) = cubicAt(c.points[0], c.points[1], c.points[2], c.points[3], t)
            assertTrue(near(qx, cx) && near(qy, cy))
        }
        assertTrue(isValidCubicContour(c))
    }

    @Test
    fun runOfOffCurvesGetsRealSmoothMidpointNodes() {
        val q = ContourData(mutableListOf(on(0f, 0f), off(0f, 100f), off(100f, 100f), on(100f, 0f)))
        val c = quadraticToCubic(q)
        // node, 2 arms, implied midpoint node, 2 arms, node, closing line.
        assertEquals(listOf(true, false, false, true, false, false, true), c.points.map { it.onCurve })
        assertPt(50f, 100f, c.points[3])
        assertTrue(c.points[3].smooth)
        // Mirrored through the midpoint.
        assertPt(2 * 50f - c.points[2].x, 2 * 100f - c.points[2].y, c.points[4])
        assertTrue(isValidCubicContour(c))
    }

    @Test
    fun importedNodesAreSmoothWhereverTheirArmsAreTangent() {
        // A real on-curve node between two collinear controls is tangent: smooth.
        val q = ContourData(mutableListOf(on(0f, 0f), off(0f, 100f), on(50f, 100f), off(100f, 100f), on(100f, 0f)))
        val c = quadraticToCubic(q)
        assertTrue(c.points[3].smooth)
        // A sharp join, or a node beside a straight segment, stays a corner.
        assertTrue(!c.points[0].smooth)
        val sharp = quadraticToCubic(ContourData(mutableListOf(on(0f, 0f), off(0f, 100f), on(50f, 100f), off(50f, 0f), on(100f, 0f))))
        assertTrue(!sharp.points[3].smooth)
    }

    @Test
    fun allOffCurveContourGetsANodeAtEveryMidpoint() {
        val q = ContourData(mutableListOf(off(0f, 0f), off(0f, 100f), off(100f, 100f), off(100f, 0f)))
        val c = quadraticToCubic(q)
        assertEquals(4, c.points.count { it.onCurve })
        assertEquals(12, c.points.size)
        assertTrue(c.points.filter { it.onCurve }.all { it.smooth })
        assertTrue(isValidCubicContour(c))
    }

    @Test
    fun oldQuadraticProjectsMigrateAndNewSavesStayCubic() {
        val old = """{"A":{"corners":{"regular":{"width":500.0,"contours":[{"points":[
            {"x":0.0,"y":0.0,"onCurve":true},{"x":30.0,"y":90.0,"onCurve":false},{"x":90.0,"y":0.0,"onCurve":true}]}]}}}}"""
        val loaded = ProjectCodec.decodeProject(old).getValue("A")
        val pts = loaded.corners.getValue("regular").contours.single().points
        assertEquals(4, pts.size)
        assertPt(20f, 60f, pts[1])
        // Saving stamps the cubic marker, so reloading converts nothing again.
        val text = ProjectCodec.encodeProject(mapOf("A" to loaded))
        assertTrue("outlineFormat" in text)
        val again = ProjectCodec.decodeProject(text).getValue("A").corners.getValue("regular").contours.single().points
        assertEquals(pts.map { Triple(it.x, it.y, it.onCurve) }, again.map { Triple(it.x, it.y, it.onCurve) })
    }

    private fun smoothNode() = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
        on(0f, 0f), off(0f, 50f), off(50f, 100f), on(100f, 100f, smooth = true), off(150f, 100f), off(200f, 50f), on(200f, 0f),
    ))))

    @Test
    fun draggingOneArmOfASmoothNodeMirrorsTheOther() {
        val g = dragPoints(smoothNode(), listOf(0 to 4), 0f, 30f)
        val pts = g.contours[0].points
        assertPt(150f, 130f, pts[4])
        assertPt(50f, 70f, pts[2]) // same line through the node, same length
    }

    @Test
    fun movingANodeCarriesItsArms() {
        val pts = dragPoints(smoothNode(), listOf(0 to 3), 10f, -10f).contours[0].points
        assertPt(110f, 90f, pts[3]); assertPt(60f, 90f, pts[2]); assertPt(160f, 90f, pts[4])
        assertPt(0f, 50f, pts[1])
    }

    @Test
    fun makingANodeSmoothAveragesItsArms() {
        val g = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            on(0f, 0f), off(0f, 0f), off(-10f, 0f), on(0f, 0f), off(0f, 30f), off(0f, 0f), on(100f, 0f),
        ))))
        // Node 3 at the origin: in-arm 10 to the left, out-arm 30 up -- a right-angle corner.
        val pts = setSmooth(g, setOf(0 to 3), true).contours[0].points
        assertTrue(pts[3].smooth)
        val s = kotlin.math.sqrt(0.5f) * 20f
        assertPt(s, s, pts[4])
        assertPt(-s, -s, pts[2])
    }

    @Test
    fun everyEditableNodeHasTwoHandlesIncludingStraightCorners() {
        val square = ContourData(mutableListOf(
            on(0f, 0f), on(0f, 90f), on(90f, 90f), on(90f, 0f),
        ))
        val st = AnchorState(GlyphCorner(500f, mutableListOf(square)))
        val pts = st.glyph.contours.single().points
        assertEquals(12, pts.size)
        assertTrue(isValidCubicContour(st.glyph.contours.single()))
        for (i in listOf(0, 3, 6, 9)) {
            assertTrue(inArmIndex(pts, i) != null)
            assertTrue(outArmIndex(pts, i) != null)
        }
        assertPt(0f, 30f, pts[1])
        assertPt(0f, 60f, pts[2])
        val dragged = dragPoints(st.glyph, setOf(0 to 3), 10f, 5f).contours[0].points
        assertPt(10f, 95f, dragged[3])
        assertPt(10f, 65f, dragged[2])
        assertPt(40f, 95f, dragged[4])
        assertPt(0f, 0f, dragged[0])
    }

    @Test
    fun lineCurveToggleKeepsTheContourValid() {
        val sq = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(on(0f, 0f), on(0f, 90f), on(90f, 90f), on(90f, 0f)))))
        val (curved, sel) = toggleCurveSelection(sq, setOf(0 to 1, 0 to 2))
        val pts = curved.contours[0].points
        assertEquals(6, pts.size)
        assertTrue(isValidCubicContour(curved.contours[0]))
        assertPt(30f, 108f, pts[2]); assertPt(60f, 108f, pts[3])
        assertEquals(setOf(0 to 1, 0 to 4), sel)
        val (straight, _) = toggleCurveSelection(curved, sel)
        assertEquals(sq.contours[0].points.map { it.x to it.y }, straight.contours[0].points.map { it.x to it.y })
    }

    @Test
    fun curveButtonWorksAfterAutomaticHandleCompletion() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(
            ContourData(mutableListOf(on(0f, 0f), on(0f, 90f), on(90f, 90f), on(90f, 0f))),
        )))
        state.selection = setOf(0 to 3, 0 to 6) // adjacent nodes, not their handles
        state.toggleCurveSelected()
        val bent = state.glyph.contours.single().points
        assertPt(30f, 108f, bent[4])
        assertPt(60f, 108f, bent[5])
        state.toggleCurveSelected()
        val flat = state.glyph.contours.single().points
        assertPt(30f, 90f, flat[4])
        assertPt(60f, 90f, flat[5])
        state.undo()
        assertPt(30f, 108f, state.glyph.contours.single().points[4])
    }

    @Test
    fun deletingNodesKeepsEveryContourValid() {
        val g = smoothNode()
        for (k in g.contours[0].points.indices) {
            val out = deleteSelection(g, setOf(0 to k))
            out.contours.forEach { assertTrue(isValidCubicContour(it), "after deleting point $k") }
        }
        // Deleting the middle node joins its neighbours with their own outer arms.
        val pts = deleteSelection(g, setOf(0 to 3)).contours[0].points
        assertEquals(listOf(true, false, false, true), pts.map { it.onCurve })
        assertPt(0f, 50f, pts[1]); assertPt(200f, 50f, pts[2])
        // Deleting one arm retracts it onto its node; deleting both arms of a segment straightens it.
        val oneArm = deleteSelection(g, setOf(0 to 1)).contours[0].points
        assertEquals(7, oneArm.size); assertPt(0f, 0f, oneArm[1])
        assertEquals(5, deleteSelection(g, setOf(0 to 1, 0 to 2)).contours[0].points.size)
        // Deleting every node removes the contour.
        assertTrue(deleteSelection(g, setOf(0 to 0, 0 to 3, 0 to 6)).contours.isEmpty())
    }

    @Test
    fun penTapsCornersAndDragPullsMirroredArms() {
        val st = AnchorState(GlyphCorner())
        st.startNewContour()
        st.addDrawPoint(0f, 0f)
        st.addDrawPoint(100f, 0f)
        st.dragDrawArm(150f, 50f)
        val pts = st.glyph.contours[0].points
        assertTrue(isValidCubicContour(st.glyph.contours[0]))
        val node = pts.indexOfLast { it.onCurve }
        assertTrue(pts[node].smooth)
        assertPt(150f, 50f, pts[node + 1])
        assertPt(50f, -50f, pts[node - 1])
        st.addDrawPoint(100f, 100f)
        assertTrue(isValidCubicContour(st.glyph.contours[0]))
        assertEquals(3, st.glyph.contours[0].points.count { it.onCurve })
        assertTrue(!st.glyph.contours[0].points.last { it.onCurve }.smooth)
    }

    @Test
    fun ellipseIsFourSmoothCubicNodes() {
        val c = shapeContours(ShapeKind.ELLIPSE, FontRect(0f, 0f, 200f, 100f)).single()
        assertEquals(4, c.points.count { it.onCurve })
        assertTrue(isValidCubicContour(c))
        assertPt(200f, 50f, c.points[0])
        assertPt(200f, 50f + KAPPA * 50f, c.points[1])
    }
}
