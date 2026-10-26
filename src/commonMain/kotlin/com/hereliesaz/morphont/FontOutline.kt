package com.hereliesaz.morphont

import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/*
 * Outlines on their way into a compiled font. Morphont's contours are cubic
 * nodes-and-arms (see Pt); a font file wants either cubic segments (CFF) or
 * quadratic ones (glyf). Everything here works on whole closed contours as a
 * start point plus a list of segments, rounded to the integer grid fonts use.
 */

/**
 * One segment of a closed contour, ending at ([x], [y]). [order] is 1 for a
 * line, 2 for a quadratic (control ([c1x], [c1y])) and 3 for a cubic (controls
 * [c1x],[c1y] and [c2x],[c2y]).
 */
internal class FontSeg(val order: Int, val c1x: Int, val c1y: Int, val c2x: Int, val c2y: Int, val x: Int, val y: Int)

/** A closed contour: it starts at ([startX], [startY]) and each segment runs on from the one before. The last segment ends back at the start. */
internal class FontContour(val startX: Int, val startY: Int, val segs: List<FontSeg>)

private fun Float.grid(scale: Float): Int = round(this * scale).toInt()

/**
 * Reads [contour] (Morphont's cubic model) as a closed [FontContour] on the integer grid, multiplying every
 * coordinate by [scale] first. Null only for a contour with no node at all (the same test for every anchor of a glyph, so masters keep their contours in step). Between two nodes there are no
 * arms (a line), one (read as a quadratic), or two (a cubic); more than two, which only a damaged file has,
 * keep the first and last as a cubic's controls.
 */
internal fun fontContourOf(contour: ContourData, scale: Float = 1f): FontContour? {
    val pts = contour.points
    val n = pts.size
    val first = pts.indexOfFirst { it.onCurve }
    if (first < 0) return null
    val segs = ArrayList<FontSeg>()
    var i = first
    var walked = 0
    do {
        var j = (i + 1) % n
        val arms = ArrayList<Pt>(2)
        var steps = 0
        while (!pts[j].onCurve && steps < n) { arms.add(pts[j]); j = (j + 1) % n; steps++ }
        val end = pts[j]
        val ex = end.x.grid(scale); val ey = end.y.grid(scale)
        segs.add(
            when (arms.size) {
                0 -> FontSeg(1, 0, 0, 0, 0, ex, ey)
                1 -> FontSeg(2, arms[0].x.grid(scale), arms[0].y.grid(scale), 0, 0, ex, ey)
                else -> FontSeg(3, arms.first().x.grid(scale), arms.first().y.grid(scale), arms.last().x.grid(scale), arms.last().y.grid(scale), ex, ey)
            },
        )
        walked += 1 + arms.size
        i = j
    } while (i != first && walked <= n)
    val start = pts[first]
    val sx = start.x.grid(scale); val sy = start.y.grid(scale)
    return FontContour(sx, sy, segs)
}

/** Every drawable contour of [corner] on the integer grid. */
internal fun fontContoursOf(corner: GlyphCorner, scale: Float = 1f): List<FontContour> =
    corner.contours.mapNotNull { fontContourOf(it, scale) }

// ---------------------------------------------------------------- cubic -> quadratic

/**
 * The quadratic that stands in for the cubic p0..p3 over [t0]..[t1]: its control point is where the end
 * tangents of that stretch of cubic would meet if the two controls were halfway along them, the usual
 * `(3(c1 + c2) - p0 - p3) / 4`. Exact when the cubic is itself a quadratic; otherwise it improves roughly
 * fourfold each time a stretch is halved.
 */
private class CubicPiece(
    val p0x: Double, val p0y: Double, val c1x: Double, val c1y: Double,
    val c2x: Double, val c2y: Double, val p3x: Double, val p3y: Double,
) {
    val qx: Double get() = (3 * (c1x + c2x) - p0x - p3x) / 4
    val qy: Double get() = (3 * (c1y + c2y) - p0y - p3y) / 4

    fun cubicAt(t: Double): Pair<Double, Double> {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return (a * p0x + b * c1x + c * c2x + d * p3x) to (a * p0y + b * c1y + c * c2y + d * p3y)
    }

    fun quadAt(t: Double): Pair<Double, Double> {
        val u = 1 - t
        val a = u * u; val b = 2 * u * t; val c = t * t
        return (a * p0x + b * qx + c * p3x) to (a * p0y + b * qy + c * p3y)
    }
}

/** The cubic [t0]..[t1] of (p0, c1, c2, p3), cut out by de Casteljau. */
private fun cubicSlice(s: FontSeg, p0x: Int, p0y: Int, t0: Double, t1: Double): CubicPiece {
    val whole = CubicPiece(p0x.toDouble(), p0y.toDouble(), s.c1x.toDouble(), s.c1y.toDouble(), s.c2x.toDouble(), s.c2y.toDouble(), s.x.toDouble(), s.y.toDouble())
    if (t0 == 0.0 && t1 == 1.0) return whole
    // Cut at t1 (keeping the start), then at t0/t1 of what is left (keeping the end).
    fun split(p: CubicPiece, t: Double): Pair<CubicPiece, CubicPiece> {
        fun lerp(a: Double, b: Double) = a + (b - a) * t
        val ab = lerp(p.p0x, p.c1x) to lerp(p.p0y, p.c1y)
        val bc = lerp(p.c1x, p.c2x) to lerp(p.c1y, p.c2y)
        val cd = lerp(p.c2x, p.p3x) to lerp(p.c2y, p.p3y)
        val abc = lerp(ab.first, bc.first) to lerp(ab.second, bc.second)
        val bcd = lerp(bc.first, cd.first) to lerp(bc.second, cd.second)
        val m = lerp(abc.first, bcd.first) to lerp(abc.second, bcd.second)
        return CubicPiece(p.p0x, p.p0y, ab.first, ab.second, abc.first, abc.second, m.first, m.second) to
            CubicPiece(m.first, m.second, bcd.first, bcd.second, cd.first, cd.second, p.p3x, p.p3y)
    }
    val head = split(whole, t1).first
    return if (t0 == 0.0) head else split(head, t0 / t1).second
}

/** How far the cubic strays from its [pieces] quadratics at the worst of a few sample points, in font units. */
private fun cubicToQuadError(s: FontSeg, p0x: Int, p0y: Int, pieces: Int): Double {
    var worst = 0.0
    for (k in 0 until pieces) {
        val piece = cubicSlice(s, p0x, p0y, k.toDouble() / pieces, (k + 1).toDouble() / pieces)
        for (sample in 1..7) {
            val t = sample / 8.0
            val (cx, cy) = piece.cubicAt(t)
            val (qx, qy) = piece.quadAt(t)
            val dx = cx - qx; val dy = cy - qy
            worst = max(worst, sqrt(dx * dx + dy * dy))
        }
    }
    return worst
}

private const val MAX_QUADS_PER_CUBIC = 12

/**
 * How many equal quadratics a cubic needs to stay within [tolerance] font units of itself. A variable font's
 * masters must all use one count per segment (so their points still line up); the caller takes the largest.
 */
internal fun quadsNeeded(s: FontSeg, p0x: Int, p0y: Int, tolerance: Float): Int {
    if (s.order != 3) return 1
    for (n in 1..MAX_QUADS_PER_CUBIC) if (cubicToQuadError(s, p0x, p0y, n) <= tolerance) return n
    return MAX_QUADS_PER_CUBIC
}

/** The point list of one TrueType contour: a flag per point for on-curve, plus its coordinates. */
internal class TtPoints(val xs: IntArray, val ys: IntArray, val onCurve: BooleanArray) {
    val size: Int get() = xs.size
}

/**
 * [contour] as TrueType points: lines stay lines, quadratics stay one off-curve point, and each cubic becomes
 * [quadCounts] quadratics (one count per segment, in order). Every join stays an explicit on-curve point, so a
 * variable font's masters, whatever their shapes, always have the same points in the same order.
 */
internal fun fontContourToTrueType(contour: FontContour, quadCounts: IntArray): TtPoints {
    val xs = ArrayList<Int>(); val ys = ArrayList<Int>(); val on = ArrayList<Boolean>()
    fun add(x: Int, y: Int, onCurve: Boolean) { xs.add(x); ys.add(y); on.add(onCurve) }
    add(contour.startX, contour.startY, true)
    var px = contour.startX; var py = contour.startY
    val lastIndex = contour.segs.lastIndex
    for ((index, s) in contour.segs.withIndex()) {
        val closes = index == lastIndex
        when (s.order) {
            1 -> if (!closes) add(s.x, s.y, true)
            2 -> { add(s.c1x, s.c1y, false); if (!closes) add(s.x, s.y, true) }
            else -> {
                val n = quadCounts[index]
                for (k in 0 until n) {
                    val piece = cubicSlice(s, px, py, k.toDouble() / n, (k + 1).toDouble() / n)
                    add(round(piece.qx).toInt(), round(piece.qy).toInt(), false)
                    val last = k == n - 1
                    if (last) { if (!closes) add(s.x, s.y, true) }
                    else {
                        val (jx, jy) = piece.cubicAt(1.0)
                        add(round(jx).toInt(), round(jy).toInt(), true)
                    }
                }
            }
        }
        px = s.x; py = s.y
    }
    return TtPoints(xs.toIntArray(), ys.toIntArray(), on.toBooleanArray())
}

/** The per-segment quadratic counts for [contour] on its own, to [tolerance] font units. */
internal fun quadCountsFor(contour: FontContour, tolerance: Float): IntArray {
    var px = contour.startX; var py = contour.startY
    return IntArray(contour.segs.size) { i ->
        val s = contour.segs[i]
        val n = quadsNeeded(s, px, py, tolerance)
        px = s.x; py = s.y
        n
    }
}

/** True when two contours have the same segment kinds, so their TrueType points can line up. */
internal fun sameShape(a: FontContour, b: FontContour): Boolean =
    a.segs.size == b.segs.size && a.segs.indices.all { a.segs[it].order == b.segs[it].order }

// ---------------------------------------------------------------- contour direction

/**
 * [contour] traced the other way round: the same start point and the same nodes, segments in reverse order with
 * their controls swapped. A variable font reverses every master of a contour or none, so the points still line up.
 */
internal fun reversedContour(contour: FontContour): FontContour {
    val n = contour.segs.size
    val xs = IntArray(n + 1); val ys = IntArray(n + 1)
    xs[0] = contour.startX; ys[0] = contour.startY
    for (k in 1..n) { xs[k] = contour.segs[k - 1].x; ys[k] = contour.segs[k - 1].y }
    val segs = ArrayList<FontSeg>(n)
    for (k in n downTo 1) {
        val s = contour.segs[k - 1]
        segs.add(FontSeg(s.order, if (s.order == 3) s.c2x else s.c1x, if (s.order == 3) s.c2y else s.c1y, s.c1x, s.c1y, xs[k - 1], ys[k - 1]))
    }
    return FontContour(contour.startX, contour.startY, segs)
}

/** The contour as a polygon, curves sampled: enough to measure its area and test points against it. */
private fun flatten(c: FontContour): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var px = c.startX.toDouble(); var py = c.startY.toDouble()
    out.add(px to py)
    for (s in c.segs) {
        val steps = when (s.order) { 1 -> 1; 2 -> 6; else -> 10 }
        for (k in 1..steps) {
            val t = k.toDouble() / steps; val u = 1 - t
            val x: Double; val y: Double
            when (s.order) {
                1 -> { x = s.x.toDouble(); y = s.y.toDouble() }
                2 -> { x = u * u * px + 2 * u * t * s.c1x + t * t * s.x; y = u * u * py + 2 * u * t * s.c1y + t * t * s.y }
                else -> {
                    x = u * u * u * px + 3 * u * u * t * s.c1x + 3 * u * t * t * s.c2x + t * t * t * s.x
                    y = u * u * u * py + 3 * u * u * t * s.c1y + 3 * u * t * t * s.c2y + t * t * t * s.y
                }
            }
            out.add(x to y)
        }
        px = s.x.toDouble(); py = s.y.toDouble()
    }
    return out
}

private fun signedArea(p: List<Pair<Double, Double>>): Double {
    var a = 0.0
    for (i in p.indices) { val (x0, y0) = p[i]; val (x1, y1) = p[(i + 1) % p.size]; a += x0 * y1 - x1 * y0 }
    return a / 2
}

private fun contains(p: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
    var inside = false
    var j = p.size - 1
    for (i in p.indices) {
        val (xi, yi) = p[i]; val (xj, yj) = p[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/**
 * Which of [contours] have to be reversed for the font to fill them the way the editor does. The editor fills
 * even-odd (a contour inside another is a hole whichever way it runs); a font fills non-zero, where a hole must
 * run against the shape around it. So each contour gets the outer direction when an even number of others
 * contain it and the opposite when an odd number do. Outer is clockwise for TrueType ([outerClockwise]) and
 * counter-clockwise for PostScript outlines. Contours that cross rather than nest fill as a union, not as the
 * editor's XOR; nothing here can express that.
 */
internal fun contourFlips(contours: List<FontContour>, outerClockwise: Boolean): BooleanArray {
    val polygons = contours.map { flatten(it) }
    val areas = polygons.map { signedArea(it) }
    return BooleanArray(contours.size) { i ->
        if (areas[i] == 0.0) return@BooleanArray false
        var depth = 0
        // Any point of the contour will do for the test, but not one on a neighbour's edge: use the middle of its first segment.
        val probe = polygons[i].let { p -> (p[0].first + p[1].first) / 2 to (p[0].second + p[1].second) / 2 }
        for (j in contours.indices) if (j != i && contains(polygons[j], probe.first, probe.second)) depth++
        val wantClockwise = (depth % 2 == 0) == outerClockwise
        (areas[i] < 0) != wantClockwise
    }
}

internal fun applyFlips(contours: List<FontContour>, flips: BooleanArray): List<FontContour> =
    contours.mapIndexed { i, c -> if (flips[i]) reversedContour(c) else c }
