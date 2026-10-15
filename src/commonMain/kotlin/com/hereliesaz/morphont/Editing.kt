package com.hereliesaz.morphont

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/*
 * Pure, UI-free editing geometry: bounds, baked transforms, primitive shapes,
 * clipboard extraction, snapping and node reduction. Everything here works in
 * font-design units and returns fresh data -- callers own history/undo.
 */

/** An axis-aligned rectangle in font units. */
data class FontRect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val cx get() = (minX + maxX) / 2f
    val cy get() = (minY + maxY) / 2f
}

fun boundsOf(points: Iterable<Pt>): FontRect? {
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    var any = false
    for (p in points) {
        any = true
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    return if (any) FontRect(minX, minY, maxX, maxY) else null
}

fun boundsOfContours(contours: List<ContourData>): FontRect? = boundsOf(contours.flatMap { it.points })

fun boundsOfSelection(glyph: GlyphCorner, selection: Set<PointKey>): FontRect? =
    boundsOf(selection.mapNotNull { (ci, pi) -> glyph.contours.getOrNull(ci)?.points?.getOrNull(pi) })

/** Applies [f] to every point, keeping on-curve/smooth flags. */
fun mapContours(contours: List<ContourData>, f: (Float, Float) -> Pair<Float, Float>): MutableList<ContourData> =
    contours.map { c ->
        ContourData(c.points.map { p -> val (x, y) = f(p.x, p.y); Pt(x, y, p.onCurve, p.smooth) }.toMutableList())
    }.toMutableList()

/**
 * Mirroring reverses winding; reversing point order restores it so even-odd
 * fill and any later export keep the original direction.
 */
fun reverseWinding(contours: List<ContourData>): MutableList<ContourData> =
    contours.map { ContourData(it.points.reversed().map { p -> p.copy2() }.toMutableList()) }.toMutableList()

fun translateContours(contours: List<ContourData>, dx: Float, dy: Float): MutableList<ContourData> =
    mapContours(contours) { x, y -> (x + dx) to (y + dy) }

/** Primitive reference shapes for ghosts. */
enum class ShapeKind(val label: String) { RECTANGLE("Rectangle"), ELLIPSE("Ellipse"), TRIANGLE("Triangle"), LINE_BAR("Bar") }

fun shapeContours(kind: ShapeKind, b: FontRect): MutableList<ContourData> {
    fun on(x: Float, y: Float) = Pt(x, y, onCurve = true)
    val pts: List<Pt> = when (kind) {
        ShapeKind.RECTANGLE -> listOf(on(b.minX, b.minY), on(b.minX, b.maxY), on(b.maxX, b.maxY), on(b.maxX, b.minY))
        ShapeKind.TRIANGLE -> listOf(on(b.minX, b.minY), on(b.cx, b.maxY), on(b.maxX, b.minY))
        ShapeKind.LINE_BAR -> {
            val t = (b.height * 0.06f).coerceAtLeast(4f)
            listOf(on(b.minX, b.cy - t), on(b.minX, b.cy + t), on(b.maxX, b.cy + t), on(b.maxX, b.cy - t))
        }
        ShapeKind.ELLIPSE -> {
            // Four smooth nodes at the extremes, counter-clockwise, each arm KAPPA of the
            // radius along the tangent: the standard cubic circle (max radial error ~0.03%).
            val rx = b.width / 2f; val ry = b.height / 2f
            val ex = listOf(1f, 0f, -1f, 0f); val ey = listOf(0f, 1f, 0f, -1f)
            (0 until 4).flatMap { i ->
                val j = (i + 1) % 4
                listOf(
                    Pt(b.cx + ex[i] * rx, b.cy + ey[i] * ry, onCurve = true, smooth = true),
                    Pt(b.cx + (ex[i] - KAPPA * ey[i]) * rx, b.cy + (ey[i] + KAPPA * ex[i]) * ry, onCurve = false),
                    Pt(b.cx + (ex[j] + KAPPA * ey[j]) * rx, b.cy + (ey[j] - KAPPA * ex[j]) * ry, onCurve = false),
                )
            }
        }
    }
    return mutableListOf(ContourData(pts.toMutableList()))
}

/** Arm length, as a fraction of the radius, of a four-node cubic circle: 4/3 * (sqrt(2) - 1). */
const val KAPPA = 0.5522848f

/**
 * The clipboard unit is whole contours: a glyph contour is always closed, so
 * a partial run of points has no meaning on its own. Every contour touched by
 * [selection] is taken whole; an empty selection takes the entire outline.
 */
fun contoursForClipboard(glyph: GlyphCorner, selection: Set<PointKey>): List<ContourData> {
    if (selection.isEmpty()) return glyph.contours.map { it.deepCopy() }
    val touched = selection.map { it.first }.distinct().sorted()
    return touched.mapNotNull { glyph.contours.getOrNull(it)?.deepCopy() }
}

/** Contour indices touched by [selection]. */
fun touchedContours(selection: Set<PointKey>): Set<Int> = selection.map { it.first }.toSet()

// ---------------------------------------------------------------- matrices

/** 2D affine as `[a, b, c, d, e, f]`: `x' = a*x + c*y + e`, `y' = b*x + d*y + f` (same layout as SVG/PDF). */
typealias Affine = List<Float>

/** [m] applied after [n]: `(m * n)(p) = m(n(p))`. */
fun compose(m: Affine, n: Affine): Affine = listOf(
    m[0] * n[0] + m[2] * n[1],
    m[1] * n[0] + m[3] * n[1],
    m[0] * n[2] + m[2] * n[3],
    m[1] * n[2] + m[3] * n[3],
    m[0] * n[4] + m[2] * n[5] + m[4],
    m[1] * n[4] + m[3] * n[5] + m[5],
)

fun translation(dx: Float, dy: Float): Affine = listOf(1f, 0f, 0f, 1f, dx, dy)

fun scalingAbout(sx: Float, sy: Float, ox: Float, oy: Float): Affine = listOf(sx, 0f, 0f, sy, ox - sx * ox, oy - sy * oy)

fun rotationAbout(degrees: Float, cx: Float, cy: Float): Affine {
    val r = degrees * PI.toFloat() / 180f
    val c = cos(r); val s = sin(r)
    return listOf(c, s, -s, c, cx - c * cx + s * cy, cy - s * cx - c * cy)
}

fun applyAffine(contours: List<ContourData>, m: Affine): List<ContourData> {
    if (m == IDENTITY_MATRIX) return contours
    val mapped = mapContours(contours) { x, y -> (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5]) }
    // A mirroring matrix flips winding; reverse so fills stay consistent.
    return if (m[0] * m[3] - m[1] * m[2] < 0f) reverseWinding(mapped) else mapped
}

/**
 * Applies [t] to just the [keys] points of [g], plus the arms of every
 * selected node (they travel with it; an affine map keeps mirrored arms
 * mirrored). Point order is never changed (even for a mirror), so anchors
 * stay point-compatible.
 */
fun transformPoints(g: GlyphCorner, keys: Collection<PointKey>, t: Affine): GlyphCorner {
    val out = g.deepCopy()
    for ((ci, pi) in withArms(g, keys)) {
        val p = out.contours.getOrNull(ci)?.points?.getOrNull(pi) ?: continue
        val x = t[0] * p.x + t[2] * p.y + t[4]
        val y = t[1] * p.x + t[3] * p.y + t[5]
        p.x = x; p.y = y
    }
    return out
}

// ---------------------------------------------------------------- snapping

/** One snap line: a coordinate plus whether it came from something worth labelling. */
data class SnapTarget(val pos: Float, val label: String? = null)

data class SnapTargets(val xs: List<SnapTarget>, val ys: List<SnapTarget>, val gridStep: Float?)

data class SnapResult(val dx: Float, val dy: Float, val xLine: Float?, val yLine: Float?)

private fun nearestGrid(v: Float, step: Float): Float = round(v / step) * step

/**
 * Light snapping: of the moving coordinates ([movingXs]/[movingYs] after the
 * raw delta), the one closest to any target within [tolerance] font units
 * pulls the whole move onto it. Each axis snaps independently; beyond the
 * tolerance the move is left exactly as dragged.
 */
fun snapDelta(
    movingXs: List<Float>,
    movingYs: List<Float>,
    dx: Float,
    dy: Float,
    targets: SnapTargets,
    tolerance: Float,
): SnapResult {
    fun axis(moving: List<Float>, d: Float, ts: List<SnapTarget>): Pair<Float, Float?> {
        var best = tolerance
        var bestAdjust = 0f
        var line: Float? = null
        for (m in moving) {
            val v = m + d
            for (t in ts) {
                val dist = abs(t.pos - v)
                if (dist < best) { best = dist; bestAdjust = t.pos - v; line = t.pos }
            }
            val step = targets.gridStep
            if (step != null && step > 0f) {
                val g = nearestGrid(v, step)
                val dist = abs(g - v)
                // Grid pulls a little weaker than real guides so it never steals them.
                if (dist < best * 0.6f) { best = dist / 0.6f; bestAdjust = g - v; line = g }
            }
        }
        return (d + bestAdjust) to line
    }
    val (sx, lx) = axis(movingXs, dx, targets.xs)
    val (sy, ly) = axis(movingYs, dy, targets.ys)
    return SnapResult(sx, sy, lx, ly)
}

/** Static + user + alignment snap targets for one anchor, excluding the points being moved. */
fun buildSnapTargets(
    glyph: GlyphCorner,
    exclude: Set<PointKey>,
    metrics: FontMetrics,
    guides: List<Guide>,
    ghosts: List<List<ContourData>>,
    gridStep: Float?,
): SnapTargets {
    val xs = mutableListOf(SnapTarget(0f, "origin"), SnapTarget(glyph.width, "advance"))
    val ys = mutableListOf(
        SnapTarget(0f, "baseline"),
        SnapTarget(metrics.xHeight, "x-height"),
        SnapTarget(metrics.capHeight, "cap"),
        SnapTarget(metrics.ascender, "ascender"),
        SnapTarget(metrics.descender, "descender"),
    )
    for (g in guides) if (g.vertical) xs.add(SnapTarget(g.pos)) else ys.add(SnapTarget(g.pos))
    glyph.contours.forEachIndexed { ci, c ->
        c.points.forEachIndexed { pi, p ->
            if ((ci to pi) !in exclude && p.onCurve) { xs.add(SnapTarget(p.x)); ys.add(SnapTarget(p.y)) }
        }
    }
    for (gh in ghosts) for (c in gh) for (p in c.points) if (p.onCurve) {
        xs.add(SnapTarget(p.x)); ys.add(SnapTarget(p.y))
    }
    return SnapTargets(xs, ys, gridStep)
}

// ---------------------------------------------------------- node reduction

/**
 * One planned point removal, in the order points disappear as the reduction
 * slider moves. [error] is the worst deviation (font units, across every
 * anchor considered) that removing this point introduced.
 */
data class Removal(val key: PointKey, val error: Float)

/**
 * One cubic span. Straight edges are represented by one-third controls,
 * so this calculation sees the same geometry whether handles are explicit
 * or a saved contour predates handle completion.
 */
private data class CubicSpan(val a: Pt, val h1: Pt, val h2: Pt, val b: Pt) {
    fun at(t: Float): Pair<Float, Float> {
        val u = 1f - t
        val w0 = u * u * u
        val w1 = 3f * u * u * t
        val w2 = 3f * u * t * t
        val w3 = t * t * t
        return (w0 * a.x + w1 * h1.x + w2 * h2.x + w3 * b.x) to
            (w0 * a.y + w1 * h1.y + w2 * h2.y + w3 * b.y)
    }

}

/** Uses both control handles of every cubic, including sharp corners and mirrored nodes. */
private fun spanBetween(pts: List<Pt>, from: Int, to: Int): CubicSpan {
    val a = pts[from]
    val b = pts[to]
    val out = outArmIndex(pts, from)
    val inn = inArmIndex(pts, to)
    val h1 = out?.let { pts[it] } ?: Pt(a.x + (b.x - a.x) / 3f, a.y + (b.y - a.y) / 3f, false)
    val h2 = inn?.let { pts[it] } ?: Pt(b.x - (b.x - a.x) / 3f, b.y - (b.y - a.y) / 3f, false)
    return CubicSpan(a, h1, h2, b)
}

/**
 * Deviation of the two true Bézier spans around the candidate node from
 * the cubic that [deleteFromContour] will actually produce. The old in/out
 * handles and both surviving neighbours' handles all contribute, whether
 * or not a node is marked smooth.
 */
private fun curveRemovalError(contour: ContourData, nodeOrdinal: Int): Float {
    val pts = contour.points
    val nodes = pts.indices.filter { pts[it].onCurve }
    val count = nodes.size
    val before = nodes[(nodeOrdinal + count - 1) % count]
    val mid = nodes[nodeOrdinal]
    val after = nodes[(nodeOrdinal + 1) % count]
    val first = spanBetween(pts, before, mid)
    val second = spanBetween(pts, mid, after)
    val replacement = spanBetween(pts, before, after)

    // Compare the drawn curves, not their arbitrary parameter speeds.
    // Bidirectional polyline distance catches missed lobes and preserves
    // geometric equivalence when an outline's two old spans share one new one.
    val source = (0..12).map { first.at(it / 12f) } +
        (1..12).map { second.at(it / 12f) }
    val merged = (0..24).map { replacement.at(it / 24f) }
    fun pointToLine(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1e-9f) return hypot(p.first - a.first, p.second - a.second)
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(p.first - a.first - t * dx, p.second - a.second - t * dy)
    }
    fun worstDistance(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>): Float =
        from.maxOf { point ->
            (0 until to.lastIndex).minOf { i -> pointToLine(point, to[i], to[i + 1]) }
        }
    return maxOf(worstDistance(source, merged), worstDistance(merged, source))
}

/**
 * Greedy, geometry-aware simplification shared by all compatible anchors.
 * Rank each candidate by its worst true cubic deviation across anchors,
 * recalculating after every removal. Both sides' handles matter, even at
 * corners or mirrored smooth nodes. A contour always retains three nodes.
 * Keys refer to the original contour point indices, so preview and apply
 * remove the same nodes without losing anchor compatibility.
 */
fun reductionOrder(glyphs: List<GlyphCorner>): List<Removal> {
    val ref = glyphs.firstOrNull() ?: return emptyList()
    val out = mutableListOf<Removal>()
    ref.contours.forEachIndexed { ci, original ->
        val alive = original.points.indices.filter { original.points[it].onCurve }.toMutableList()
        val working = glyphs.map { it.contours[ci].deepCopy() }.toMutableList()
        // Only the removed node and its immediate neighbours' segments change.
        // Cache everyone else's true-curve loss across steps.
        val costs = HashMap<Int, Float>()
        while (alive.size > 3) {
            var bestOrdinal = -1
            var bestCost = Float.POSITIVE_INFINITY
            for (ordinal in alive.indices) {
                val cost = costs.getOrPut(alive[ordinal]) {
                    working.maxOf { curveRemovalError(it, ordinal) }
                }
                if (cost < bestCost) {
                    bestCost = cost
                    bestOrdinal = ordinal
                }
            }
            if (bestOrdinal < 0) break
            val removed = alive.removeAt(bestOrdinal)
            out.add(Removal(ci to removed, bestCost))
            costs.remove(removed)
            for (i in working.indices) {
                val nodes = working[i].points.indices.filter { working[i].points[it].onCurve }
                working[i] = deleteFromContour(working[i], setOf(nodes[bestOrdinal]))
            }
            costs.remove(alive[(bestOrdinal + alive.size - 1) % alive.size])
            costs.remove(alive[bestOrdinal % alive.size])
        }
    }
    // A contour's removals depend on its earlier ones. Compare running
    // maxima when interleaving contours to keep each prefix valid.
    val runMax = FloatArray(out.size)
    val perContour = HashMap<Int, Float>()
    out.forEachIndexed { i, r ->
        val cost = maxOf(perContour[r.key.first] ?: 0f, r.error)
        perContour[r.key.first] = cost
        runMax[i] = cost
    }
    return out.indices.sortedWith(compareBy({ runMax[it] }, { it })).map { out[it] }
}

/**
 * [glyph] with the [removed] nodes deleted, each with its arms, keeping every
 * contour valid (see [deleteFromContour]). Contours are never dropped here, so
 * the result's contour indices still match [glyph]'s.
 */
fun withoutPoints(glyph: GlyphCorner, removed: Set<PointKey>): GlyphCorner =
    GlyphCorner(
        glyph.width,
        glyph.contours.mapIndexed { ci, c ->
            deleteFromContour(c, c.points.indices.filter { (ci to it) in removed && c.points[it].onCurve }.toSet())
        }.toMutableList(),
    )
