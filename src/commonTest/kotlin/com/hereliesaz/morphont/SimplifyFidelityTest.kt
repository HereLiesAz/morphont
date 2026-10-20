package com.hereliesaz.morphont

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simplify must stay on the ORIGINAL outline: redundant nodes cost nothing, and error never accumulates. */
class SimplifyFidelityTest {
    /** Points along every segment of every contour (straight sides when a node has no arms). */
    private fun outline(g: GlyphCorner, perSpan: Int = 40): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        for (c in g.contours) {
            val pts = c.points
            val nodes = pts.indices.filter { pts[it].onCurve }
            for ((k, a) in nodes.withIndex()) {
                val b = nodes[(k + 1) % nodes.size]
                val pa = pts[a]; val pb = pts[b]
                val h1 = outArmIndex(pts, a)?.let { pts[it] } ?: Pt(pa.x + (pb.x - pa.x) / 3f, pa.y + (pb.y - pa.y) / 3f, false)
                val h2 = inArmIndex(pts, b)?.let { pts[it] } ?: Pt(pb.x - (pb.x - pa.x) / 3f, pb.y - (pb.y - pa.y) / 3f, false)
                for (i in 0..perSpan) {
                    val t = i / perSpan.toFloat(); val u = 1 - t
                    out.add(
                        (u * u * u * pa.x + 3 * u * u * t * h1.x + 3 * u * t * t * h2.x + t * t * t * pb.x) to
                            (u * u * u * pa.y + 3 * u * u * t * h1.y + 3 * u * t * t * h2.y + t * t * t * pb.y),
                    )
                }
            }
        }
        return out
    }

    private fun gap(p: Pair<Float, Float>, line: List<Pair<Float, Float>>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until line.lastIndex) {
            val a = line[i]; val b = line[i + 1]
            val dx = b.first - a.first; val dy = b.second - a.second
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9f) 0f else (((p.first - a.first) * dx + (p.second - a.second) * dy) / l2).coerceIn(0f, 1f)
            best = minOf(best, hypot(p.first - a.first - t * dx, p.second - a.second - t * dy))
        }
        return best
    }

    /** Worst distance between two outlines, both directions. */
    private fun deviation(a: GlyphCorner, b: GlyphCorner): Float {
        val pa = outline(a); val pb = outline(b)
        return maxOf(pa.maxOf { gap(it, pb) }, pb.maxOf { gap(it, pa) })
    }

    /** A circle of [n] smooth nodes with proper tangent handles. */
    private fun circle(n: Int, r: Float = 200f): GlyphCorner {
        val k = 4f / 3f * kotlin.math.tan(PI.toFloat() / (2f * n))
        val pts = mutableListOf<Pt>()
        for (i in 0 until n) {
            val a = 2 * PI.toFloat() * i / n
            val x = r * cos(a); val y = r * sin(a)
            val tx = -sin(a); val ty = cos(a)
            val l = r * k
            pts.add(Pt(x, y, true, smooth = true))
            pts.add(Pt(x + tx * l, y + ty * l, false))
            val a2 = 2 * PI.toFloat() * (i + 1) / n
            pts.add(Pt(r * cos(a2) - (-sin(a2)) * l, r * sin(a2) - cos(a2) * l, false))
        }
        return GlyphCorner(600f, mutableListOf(ContourData(pts)))
    }

    private fun stage(g: GlyphCorner, order: List<Removal>, count: Int) =
        reducedGlyph(g, order.take(count).map { it.key }.toSet())

    @Test fun aRedundantNodeOnAStraightSideLeavesTheShapeExactlyAsItWas() {
        val g = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 50f, true), Pt(0f, 100f, true), Pt(100f, 100f, true), Pt(100f, 0f, true),
        )))).let { AnchorState(it).glyph }
        val order = reductionOrder(listOf(g))
        assertTrue(order.first().error < 0.01f)
        assertTrue(deviation(g, stage(g, order, 1)) < 0.05f)
    }

    @Test fun aCubicSplitInTwoComesBackAsExactlyTheSameCurve() {
        // One cubic from (0,0) to (300,0), split at its middle by a smooth node: removing the node is lossless.
        val p0 = 0f to 0f; val p1 = 0f to 200f; val p2 = 300f to 200f; val p3 = 300f to 0f
        fun mid(a: Pair<Float, Float>, b: Pair<Float, Float>) = ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f)
        val m01 = mid(p0, p1); val m12 = mid(p1, p2); val m23 = mid(p2, p3)
        val m012 = mid(m01, m12); val m123 = mid(m12, m23); val m = mid(m012, m123)
        val g = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(p0.first, p0.second, true), Pt(m01.first, m01.second, false), Pt(m012.first, m012.second, false),
            Pt(m.first, m.second, true, smooth = true), Pt(m123.first, m123.second, false), Pt(m23.first, m23.second, false),
            Pt(p3.first, p3.second, true), Pt(150f, -100f, true),
        ))))).glyph
        val order = reductionOrder(listOf(g))
        val midNode = g.contours[0].points.indexOfFirst { it.x == m.first && it.y == m.second && it.onCurve }
        val cost = order.first { it.key == 0 to midNode }.error
        assertTrue(cost < 0.5f, "splitting a cubic and merging it back should be (near) lossless, was $cost")
        assertTrue(deviation(g, reducedGlyph(g, setOf(0 to midNode))) < 0.5f)
    }

    @Test fun everyStageStaysCloseToTheOriginalNotJustToThePreviousStage() {
        val g = circle(16)
        val order = reductionOrder(listOf(g))
        var runMax = 0f
        for (count in 1..order.size) {
            runMax = maxOf(runMax, order[count - 1].error)
            val d = deviation(g, stage(g, order, count))
            // Measured against the original at every stage, within the reported running maximum.
            assertTrue(d <= runMax + 1.5f, "stage $count: deviation $d from the original exceeds reported $runMax")
        }
    }

    @Test fun refittingBeatsJustDeletingTheNode() {
        val g = circle(12)
        val order = reductionOrder(listOf(g))
        val removed = order.take(4).map { it.key }.toSet()
        val refit = deviation(g, reducedGlyph(g, removed))
        val plain = deviation(g, withoutPoints(g, removed))
        assertTrue(refit < plain, "refit $refit should be closer to the original than plain deletion $plain")
    }

    @Test fun nodeCountMatchesTheSliderAndContoursStayValid() {
        val g = circle(10)
        val order = reductionOrder(listOf(g))
        for (count in 0..order.size) {
            val r = stage(g, order, count)
            assertEquals(10 - count, r.contours.single().points.count { it.onCurve })
            assertTrue(isValidCubicContour(r.contours.single()))
        }
    }

    @Test fun aSmoothNodeThatCannotMirrorBecomesACornerNotABentCurve() {
        val g = circle(8)
        val order = reductionOrder(listOf(g))
        val r = stage(g, order, 3)
        for (c in r.contours) {
            val pts = c.points
            for (i in pts.indices) {
                if (!pts[i].onCurve || !pts[i].smooth) continue
                val o = outArmIndex(pts, i); val a = inArmIndex(pts, i)
                if (o == null || a == null) continue
                val sx = (pts[o].x - pts[i].x) + (pts[a].x - pts[i].x)
                val sy = (pts[o].y - pts[i].y) + (pts[a].y - pts[i].y)
                assertTrue(hypot(sx, sy) <= 0.5f, "node $i is marked smooth but its handles do not mirror")
            }
        }
    }
}
