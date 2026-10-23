package com.hereliesaz.morphont

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeResolveTest {
    /** A polygon of straight edges from flat x,y pairs, as the editor stores one (node, out-arm, in-arm per edge). */
    private fun poly(vararg xy: Float): ContourData {
        val n = xy.size / 2
        val pts = mutableListOf<Pt>()
        for (i in 0 until n) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]
            val bx = xy[2 * ((i + 1) % n)]; val by = xy[2 * ((i + 1) % n) + 1]
            pts += Pt(ax, ay, true)
            pts += Pt(ax + (bx - ax) / 3f, ay + (by - ay) / 3f, false)
            pts += Pt(bx - (bx - ax) / 3f, by - (by - ay) / 3f, false)
        }
        return ContourData(pts)
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): ContourData {
        val k = 0.5523f
        val dirs = listOf(1f to 0f, 0f to 1f, -1f to 0f, 0f to -1f) // counter-clockwise from the right
        val out = mutableListOf<Pt>()
        for (i in 0 until 4) {
            val (ux, uy) = dirs[i]; val (vx, vy) = dirs[(i + 1) % 4]
            val a = Pt(cx + rx * ux, cy + ry * uy, true, smooth = true)
            val b = Pt(cx + rx * vx, cy + ry * vy, true)
            out += a
            out += Pt(a.x - uy * rx * k, a.y + ux * ry * k, false)
            out += Pt(b.x + vy * rx * k, b.y - vx * ry * k, false)
        }
        return ContourData(out)
    }

    private fun g(vararg c: ContourData) = GlyphCorner(500f, mutableListOf(*c))
    private fun nodes(c: ContourData) = c.points.filter { it.onCurve }
    private fun count(c: ContourData) = nodes(c).size

    /** Each node's place in its own contour's box, 0..1 both ways. */
    private fun rel(c: ContourData): List<Pair<Float, Float>> {
        val ns = nodes(c)
        val minX = ns.minOf { it.x }; val maxX = ns.maxOf { it.x }; val minY = ns.minOf { it.y }; val maxY = ns.maxOf { it.y }
        return ns.map { (it.x - minX) / maxOf(1e-3f, maxX - minX) to (it.y - minY) / maxOf(1e-3f, maxY - minY) }
    }

    private fun assertCorresponds(a: ContourData, b: ContourData, tol: Float, why: String = "") {
        assertEquals(count(a), count(b), "node counts differ $why")
        val ra = rel(a); val rb = rel(b)
        for (i in ra.indices) {
            val d = hypot(ra[i].first - rb[i].first, ra[i].second - rb[i].second)
            assertTrue(d <= tol, "node $i is ${ra[i]} in one extreme and ${rb[i]} in the other $why")
        }
    }

    private fun resolve(a: GlyphCorner, b: GlyphCorner, mode: ResolveMode = ResolveMode.ADD_MISSING): Pair<GlyphCorner, GlyphCorner> {
        val out = resolveExtremes(mapOf("a" to a, "b" to b), mode, hub = "a")
        assertNull(out.problem)
        val r = assertNotNull(out.glyphs)
        assertNull(compatibilityIssue(r, r.keys.toList()))
        return r.getValue("a") to r.getValue("b")
    }

    @Test
    fun theSameShapeStartedElsewhereAndDrawnTheOtherWayRoundIsPairedCornerForCorner() {
        val thin = poly(0f, 0f, 300f, 0f, 300f, 40f, 40f, 40f, 40f, 500f, 0f, 500f)
        // The bold L: heavier strokes, listed from its fourth corner and clockwise.
        val bold = poly(130f, 120f, 400f, 120f, 400f, 0f, 10f, 0f, 10f, 600f, 130f, 600f).let { it }
        val (a, b) = resolve(g(thin), g(bold))
        assertEquals(6, count(a.contours[0]), "nothing was missing, so nothing is added")
        // Strokes change from 13% to 31% of the glyph, so even right partners differ by ~0.2; a wrong one is >0.5.
        assertCorresponds(a.contours[0], b.contours[0], 0.3f)
        val ra = rel(a.contours[0]); val rb = rel(b.contours[0])
        for (i in ra.indices) {
            val corner = (ra[i].first < 0.01f || ra[i].first > 0.99f) && (ra[i].second < 0.01f || ra[i].second > 0.99f)
            if (corner) assertTrue(hypot(ra[i].first - rb[i].first, ra[i].second - rb[i].second) < 0.01f, "outer corner $i is not paired with the same corner")
        }
    }

    @Test
    fun aNodeMissingFromOneEdgeLandsTheSameFractionAlongTheOthers() {
        val narrow = poly(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        // Wider, with an extra node a quarter of the way along the top edge (from the left).
        val wide = poly(0f, 0f, 200f, 0f, 200f, 100f, 50f, 100f, 0f, 100f)
        val (a, b) = resolve(g(narrow), g(wide))
        assertEquals(5, count(a.contours[0]))
        assertCorresponds(a.contours[0], b.contours[0], 0.05f)
        val top = nodes(a.contours[0]).single { abs(it.y - 100f) < 0.5f && it.x > 1f && it.x < 99f }
        assertTrue(abs(top.x - 25f) < 2f, "expected the new node a quarter of the way along, got x=${top.x}")
        assertEquals(5, nodes(b.contours[0]).size)
        // Adding a node never changes the outline: the rectangle is still 100 x 100.
        val area = nodes(a.contours[0]).let { ns -> var s2 = 0f; for (i in ns.indices) { val p = ns[i]; val q = ns[(i + 1) % ns.size]; s2 += p.x * q.y - q.x * p.y }; abs(s2) / 2f }
        assertEquals(10000f, area, 1f)
    }

    @Test
    fun missingNodesOnOppositeSidesAreNotMistakenForEachOther() {
        val topExtra = poly(0f, 0f, 100f, 0f, 100f, 100f, 50f, 100f, 0f, 100f)
        val bottomExtra = poly(0f, 0f, 100f, 0f, 200f, 0f, 200f, 100f, 0f, 100f)
        val (a, b) = resolve(g(topExtra), g(bottomExtra))
        assertEquals(6, count(a.contours[0]), "each gains the other's extra node")
        assertCorresponds(a.contours[0], b.contours[0], 0.05f)
        // A gained a bottom-middle node, B a top-middle one.
        assertTrue(nodes(a.contours[0]).any { abs(it.y) < 0.5f && abs(it.x - 50f) < 3f })
        assertTrue(nodes(b.contours[0]).any { abs(it.y - 100f) < 0.5f && abs(it.x - 100f) < 3f })
    }

    @Test
    fun smoothCurvesGainTheirMissingNodesAtTheMatchingPlace() {
        val round = ellipse(100f, 100f, 100f, 100f)
        val four = ellipse(150f, 100f, 150f, 100f)
        // Split the first segment, then the segment that starts at the (old) third node, now at point index 9.
        val once = splitSegment(four, 0, 0.5f)!!.first
        val withTwoMore = splitSegment(once, 9, 0.5f)!!.first
        val (a, b) = resolve(g(round), g(withTwoMore))
        assertEquals(6, count(a.contours[0]))
        assertCorresponds(a.contours[0], b.contours[0], 0.08f)
    }

    @Test
    fun matchToFewestTakesTheUnpairedNodeOutOfTheExtremeThatHasIt() {
        val plain = poly(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val extra = poly(0f, 0f, 200f, 0f, 200f, 100f, 100f, 100f, 0f, 100f)
        val (a, b) = resolve(g(plain), g(extra), ResolveMode.MATCH_FEWEST)
        assertEquals(4, count(a.contours[0]))
        assertCorresponds(a.contours[0], b.contours[0], 0.05f)
        val bx = nodes(b.contours[0]).map { it.x }
        assertEquals(200f, bx.max(), 0.5f)
    }

    @Test
    fun threeExtremesEndUpOnOneStructureWithEveryonesNodesInEveryExtreme() {
        val base = poly(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val topExtra = poly(0f, 0f, 100f, 0f, 100f, 100f, 50f, 100f, 0f, 100f)
        val sideExtra = poly(0f, 0f, 100f, 0f, 100f, 50f, 100f, 100f, 0f, 100f)
        val out = resolveExtremes(mapOf("hub" to g(topExtra), "x" to g(base), "y" to g(sideExtra)), ResolveMode.ADD_MISSING, hub = "hub")
        assertNull(out.problem)
        val r = assertNotNull(out.glyphs)
        assertNull(compatibilityIssue(r, r.keys.toList()))
        assertEquals(6, count(r.getValue("x").contours[0]), "base gains both extra nodes")
        for (name in r.keys) assertTrue(nodes(r.getValue(name).contours[0]).any { abs(it.y - 100f) < 0.5f && abs(it.x - 50f) < 3f }, "$name has the top node")
    }

    @Test
    fun aContourOnlyOneExtremeHasIsSuppliedOrDroppedAsAsked() {
        val outer = poly(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f)
        val counter = poly(60f, 60f, 60f, 140f, 140f, 140f, 140f, 60f)
        val (_, withCounter) = resolve(g(outer), g(outer, counter))
        val added = resolveExtremes(mapOf("a" to g(outer), "b" to g(outer, counter)), ResolveMode.ADD_MISSING, hub = "b")
        assertEquals(2, added.glyphs!!.getValue("a").contours.size)
        assertTrue(added.copied > 0)
        val fewest = resolveExtremes(mapOf("a" to g(outer), "b" to g(outer, counter)), ResolveMode.MATCH_FEWEST, hub = "b")
        assertEquals(1, fewest.glyphs!!.getValue("b").contours.size)
        assertTrue(fewest.dropped > 0)
        assertEquals(2, withCounter.contours.size)
    }

    @Test
    fun extremesThatAlreadyAgreeComeBackWithTheirShapes() {
        val a = g(poly(0f, 0f, 120f, 0f, 120f, 90f, 0f, 90f))
        val b = g(poly(0f, 0f, 260f, 0f, 260f, 90f, 0f, 90f))
        val (ra, rb) = resolve(a, b)
        assertEquals(4, count(ra.contours[0]))
        val xs = nodes(rb.contours[0]).map { it.x }
        assertEquals(260f, xs.max(), 0.5f)
        assertEquals(0f, xs.min(), 0.5f)
    }

    // ------------------------------------------------------------ randomized

    private class Case(val a: GlyphCorner, val b: GlyphCorner, val expectedNodes: Int, val seed: Int, val honestPairs: Int)

    /**
     * A star-shaped polygon of 8-13 corners drawn two ways: extreme A as it is, extreme B stretched, jittered,
     * started elsewhere and sometimes wound the other way. Each gets up to two extra nodes along its edges --
     * the outline doesn't change, but each is missing from the other extreme. When an A extra and a B extra
     * land on the same edge close together they honestly are one node, so such pairs may merge.
     */
    private fun randomCase(seed: Int): Case {
        val rnd = kotlin.random.Random(seed)
        val n = 8 + rnd.nextInt(6)
        val angles = (0 until n).map { (it + rnd.nextFloat() * 0.5f) / n * 2f * kotlin.math.PI.toFloat() }.sorted()
        val radii = (0 until n).map { 60f + rnd.nextFloat() * 90f }
        val pa = angles.indices.map { Pt(200f + radii[it] * cos(angles[it]), 200f + radii[it] * sin(angles[it]), true) }
        fun jitter() = 1f + (rnd.nextFloat() - 0.5f) * 0.08f
        val pb = pa.map { Pt(200f + (it.x - 200f) * 1.35f * jitter(), 200f + (it.y - 200f) * 1.1f * jitter(), true) }
        // Extras: (edge, fraction) for each side.
        val ea = (0 until rnd.nextInt(3)).map { rnd.nextInt(n) to (0.25f + rnd.nextFloat() * 0.5f) }
        val eb = (0 until rnd.nextInt(3)).map { rnd.nextInt(n) to (0.25f + rnd.nextFloat() * 0.5f) }
        fun withExtras(ps: List<Pt>, extras: List<Pair<Int, Float>>): MutableList<Pt> {
            val out = mutableListOf<Pt>()
            for (i in ps.indices) {
                out += ps[i]
                // Same edge twice: spread them so they stay in order.
                for ((e, f) in extras.filter { it.first == i }.sortedBy { it.second }) {
                    val q = ps[(i + 1) % ps.size]
                    out += Pt(ps[i].x + (q.x - ps[i].x) * f, ps[i].y + (q.y - ps[i].y) * f, true)
                }
            }
            return out
        }
        val la = withExtras(pa, ea)
        var lb = withExtras(pb, eb)
        val shift = rnd.nextInt(lb.size)
        lb = (lb.drop(shift) + lb.take(shift)).toMutableList()
        if (rnd.nextBoolean()) lb = lb.reversed().toMutableList()
        val honest = ea.count { (e, f) -> eb.any { (e2, f2) -> e2 == e && abs(f - f2) < 0.25f } }
        fun contour(ps: List<Pt>) = poly(*ps.flatMap { listOf(it.x, it.y) }.toFloatArray())
        return Case(g(contour(la)), g(contour(lb)), n + ea.size + eb.size, seed, honest)
    }

    @Test
    fun warpedRestartedAndReversedExtremesWithMissingNodesStillLineUp() {
        var bad = 0
        val problems = mutableListOf<String>()
        for (seed in 1..60) {
            val c = randomCase(seed)
            val out = resolveExtremes(mapOf("a" to c.a, "b" to c.b), ResolveMode.ADD_MISSING, hub = "a")
            val r = out.glyphs
            val ok = r != null && compatibilityIssue(r, r.keys.toList()) == null && run {
                val ra = rel(r.getValue("a").contours[0]); val rb = rel(r.getValue("b").contours[0])
                count(r.getValue("a").contours[0]).let { it in (c.expectedNodes - c.honestPairs)..c.expectedNodes } &&
                    ra.indices.all { hypot(ra[it].first - rb[it].first, ra[it].second - rb[it].second) < 0.3f }
            }
            if (!ok) {
                bad++
                problems += "seed $seed (expected ${c.expectedNodes - c.honestPairs}..${c.expectedNodes}, got ${r?.getValue("a")?.contours?.get(0)?.let(::count)})"
                if (System.getenv("RESOLVE_DEBUG") != null) {
                    println("--- seed $seed in A ${rel(c.a.contours[0]).joinToString { "(%.2f,%.2f)".format(it.first, it.second) }}")
                    println("    in B ${rel(c.b.contours[0]).joinToString { "(%.2f,%.2f)".format(it.first, it.second) }}")
                    if (r != null) {
                        println("    out A ${rel(r.getValue("a").contours[0]).joinToString { "(%.2f,%.2f)".format(it.first, it.second) }}")
                        println("    out B ${rel(r.getValue("b").contours[0]).joinToString { "(%.2f,%.2f)".format(it.first, it.second) }}")
                    }
                }
            }
        }
        // A flat extra node beside a mild corner on a nearly straight stretch is honestly ambiguous; allow a few.
        assertTrue(bad <= 3, "$bad of 60 randomized pairs failed: ${problems.joinToString()}")
    }

    @Test
    fun regularFromOneAxisIsHalfwayBetweenItsExtremes() {
        val thin = g(poly(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f))
        val bold = g(poly(0f, 0f, 300f, 0f, 300f, 100f, 0f, 100f))
        val reg = naturalRegularOf(mapOf(Axis.WIDTH.lo to thin, Axis.WIDTH.hi to bold))!!
        assertEquals(200f, nodes(reg.contours[0]).maxOf { it.x }, 0.01f)
    }

    @Test
    fun regularAveragesTheAxesThatAreComplete() {
        val a = g(poly(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f))
        val b = g(poly(0f, 0f, 300f, 0f, 300f, 100f, 0f, 100f))
        val c = g(poly(0f, 0f, 200f, 0f, 200f, 100f, 0f, 100f))
        val d = g(poly(0f, 0f, 200f, 0f, 200f, 100f, 0f, 100f))
        // Width's midpoint is 200, weight's is 200: Regular is 200. A lone Serif extreme doesn't count.
        val lone = g(poly(0f, 0f, 900f, 0f, 900f, 100f, 0f, 100f))
        val reg = naturalRegularOf(mapOf(Axis.WIDTH.lo to a, Axis.WIDTH.hi to b, Axis.WEIGHT.lo to c, Axis.WEIGHT.hi to d, Axis.SERIF.lo to lone))!!
        assertEquals(200f, nodes(reg.contours[0]).maxOf { it.x }, 0.01f)
    }

    @Test
    fun withNoCompleteAxisRegularIsTheAverageOfWhatThereIs() {
        val a = g(poly(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f))
        val b = g(poly(0f, 0f, 300f, 0f, 300f, 100f, 0f, 100f))
        val reg = naturalRegularOf(mapOf(Axis.WIDTH.lo to a, Axis.WEIGHT.hi to b))!!
        assertEquals(200f, nodes(reg.contours[0]).maxOf { it.x }, 0.01f)
    }
}
