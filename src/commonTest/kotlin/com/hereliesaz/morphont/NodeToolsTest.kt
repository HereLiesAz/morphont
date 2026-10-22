package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Photoshop's anchor-point tools: add a node without changing the outline, delete one, convert between corner and smooth. */
class NodeToolsTest {
    private val lo = Axis.WEIGHT.lo
    private val hi = Axis.WEIGHT.hi

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

    private fun app(thin: Int, regular: Int, black: Int): AppState {
        val app = AppState()
        app.anchors.getValue(lo).loadFresh(ring(thin, 150f))
        app.anchors.getValue("regular").loadFresh(ring(regular, 200f))
        app.anchors.getValue(hi).loadFresh(ring(black, 260f))
        app.selectedAxis = Axis.WEIGHT
        app.currentGlyphName = "o"
        return app
    }

    private fun nodes(app: AppState, name: String) = app.anchors.getValue(name).glyph.contours.sumOf { c -> c.points.count { it.onCurve } }

    private fun cubic(a: Pt, p1: Pt, p2: Pt, b: Pt, u: Float): Pair<Float, Float> {
        val v = 1f - u
        return (v * v * v * a.x + 3 * v * v * u * p1.x + 3 * v * u * u * p2.x + u * u * u * b.x) to
            (v * v * v * a.y + 3 * v * v * u * p1.y + 3 * v * u * u * p2.y + u * u * u * b.y)
    }

    @Test fun splittingASegmentKeepsTheOutline() {
        val c = completeNodeHandles(ring(4, 100f)).contours[0]
        val t = 0.3f
        val (nc, index) = assertNotNull(splitSegment(c, 0, t))
        val p = c.points
        // The new node lies on the original curve...
        val (ex, ey) = cubic(p[0], p[1], p[2], p[3], t)
        assertTrue(abs(nc.points[index].x - ex) < 1e-3f && abs(nc.points[index].y - ey) < 1e-3f)
        // ...and each half traces the original curve.
        val q = nc.points
        for (u in listOf(0.25f, 0.5f, 0.75f)) {
            val first = cubic(q[0], q[1], q[2], q[index], u)
            val orig1 = cubic(p[0], p[1], p[2], p[3], u * t)
            assertTrue(abs(first.first - orig1.first) < 1e-3f && abs(first.second - orig1.second) < 1e-3f, "first half at $u")
            val second = cubic(q[index], q[index + 1], q[index + 2], q[index + 3], u)
            val orig2 = cubic(p[0], p[1], p[2], p[3], t + u * (1 - t))
            assertTrue(abs(second.first - orig2.first) < 1e-3f && abs(second.second - orig2.second) < 1e-3f, "second half at $u")
        }
        assertEquals(c.points.count { it.onCurve } + 1, nc.points.count { it.onCurve })
        assertTrue(isValidCubicContour(nc))
        assertTrue(nc.points[index].smooth, "a node on a curve is smooth")
    }

    @Test fun aNodeOnAStraightSideIsACorner() {
        val sq = completeNodeHandles(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(100f, 0f, true), Pt(100f, 100f, true), Pt(0f, 100f, true),
        ))))).contours[0]
        val (nc, index) = assertNotNull(splitSegment(sq, 0, 0.5f))
        assertTrue(!nc.points[index].smooth)
        assertEquals(50f, nc.points[index].x, 1e-3f)
        assertEquals(0f, nc.points[index].y, 1e-3f)
    }

    @Test fun addingANodeAddsItInEveryMatchingStateAsOneUndoStep() {
        val app = app(8, 8, 8)
        val key = assertNotNull(app.addNodeAt(SegmentHit(0, 0, 0.5f)))
        for (n in listOf(lo, "regular", hi)) assertEquals(9, nodes(app, n), n)
        assertNull(compatibilityIssue(app.cornersSnapshot(), listOf(lo, "regular", hi)))
        assertEquals(setOf(key), app.commandTarget.selection)
        app.undo()
        for (n in listOf(lo, "regular", hi)) assertEquals(8, nodes(app, n), n)
    }

    @Test fun aStateThatDoesNotMatchIsLeftAloneWhenAdding() {
        val app = app(8, 8, 6)
        app.addNodeAt(SegmentHit(0, 0, 0.5f))
        assertEquals(9, nodes(app, "regular")); assertEquals(9, nodes(app, lo))
        assertEquals(6, nodes(app, hi))
    }

    @Test fun subtractingANodeRemovesItFromEveryMatchingState() {
        val app = app(8, 8, 8)
        app.subtractNode(0 to 3)
        for (n in listOf(lo, "regular", hi)) assertEquals(7, nodes(app, n), n)
        app.undo()
        for (n in listOf(lo, "regular", hi)) assertEquals(8, nodes(app, n), n)
    }

    @Test fun subtractIgnoresAHandle() {
        val app = app(8, 8, 8)
        app.subtractNode(0 to 1)
        assertEquals(8, nodes(app, "regular"))
    }

    @Test fun convertingASmoothNodeStripsItsHandlesAndMakesItACorner() {
        val app = app(8, 8, 8)
        app.convertNodeToCorner(0 to 0)
        val pts = app.anchors.getValue("regular").glyph.contours[0].points
        assertTrue(!pts[0].smooth)
        assertTrue(isRetracted(pts, 1) && isRetracted(pts, inArmIndex(pts, 0)!!))
        app.undo()
        assertTrue(app.anchors.getValue("regular").glyph.contours[0].points[0].smooth)
    }

    @Test fun convertingACornerWithoutHandlesChangesNothing() {
        val app = app(8, 8, 8)
        app.convertNodeToCorner(0 to 0)
        val after = app.anchors.getValue("regular").glyph
        app.convertNodeToCorner(0 to 0)
        assertEquals(after, app.anchors.getValue("regular").glyph)
    }

    @Test fun draggingFromACornerPullsOutSymmetricHandles() {
        val g = retractNodeHandles(completeNodeHandles(ring(6, 100f)), 0 to 0)
        val out = pullSymmetricHandles(g, 0 to 0, 130f, 40f)
        val pts = out.contours[0].points
        val node = pts[0]
        assertTrue(node.smooth)
        assertEquals(130f, pts[1].x, 1e-3f); assertEquals(40f, pts[1].y, 1e-3f)
        val inn = pts[inArmIndex(pts, 0)!!]
        assertEquals(hypot(pts[1].x - node.x, pts[1].y - node.y), hypot(inn.x - node.x, inn.y - node.y), 1e-3f)
        assertEquals(node.x * 2 - 130f, inn.x, 1e-3f)
    }

    @Test fun aSegmentIsHitNearItsMiddleAndNotFarAway() {
        val g = completeNodeHandles(ring(4, 100f))
        val mapper = SpaceMapper(ViewBox(-150f, -150f, 300f, 300f), Size(300f, 300f))
        val p = g.contours[0].points
        val mid = cubic(p[0], p[1], p[2], p[3], 0.5f)
        val hit = assertNotNull(hitTestSegment(g, mapper, mapper.toCanvas(mid.first, mid.second), 6f))
        assertEquals(SegmentHit(0, 0, hit.t), hit.copy(t = hit.t))
        assertTrue(abs(hit.t - 0.5f) < 0.05f, "t=${hit.t}")
        assertNull(hitTestSegment(g, mapper, mapper.toCanvas(0f, 0f), 6f), "the centre of the ring is not on any segment")
    }
}
