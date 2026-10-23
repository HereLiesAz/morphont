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

/**
 * Static + user + alignment snap targets for one anchor, excluding the points being moved. Nodes snap to
 * other nodes and, when [forHandles] is set, handles snap to other handles (never to nodes); a handle
 * retracted onto its node is not a target.
 */
fun buildSnapTargets(
    glyph: GlyphCorner,
    exclude: Set<PointKey>,
    metrics: FontMetrics,
    guides: List<Guide>,
    ghosts: List<List<ContourData>>,
    gridStep: Float?,
    forHandles: Boolean = false,
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
            val wanted = if (forHandles) !p.onCurve && !isRetracted(c.points, pi) else p.onCurve
            if ((ci to pi) !in exclude && wanted) { xs.add(SnapTarget(p.x)); ys.add(SnapTarget(p.y)) }
        }
    }
    if (!forHandles) for (gh in ghosts) for (c in gh) for (p in c.points) if (p.onCurve) {
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

private const val FIT_SAMPLES_PER_SPAN = 16

/** The original spans from node ordinal [from] up to node ordinal [to] (going round the contour), one cubic each. */
private fun chainOf(pts: List<Pt>, nodes: List<Int>, from: Int, to: Int): List<CubicSpan> {
    val n = nodes.size
    val out = ArrayList<CubicSpan>()
    var o = from
    do {
        out.add(spanBetween(pts, nodes[o], nodes[(o + 1) % n]))
        o = (o + 1) % n
    } while (o != to)
    return out
}

private fun unit(x: Float, y: Float): Pair<Float, Float>? {
    val l = hypot(x, y)
    return if (l < 1e-6f) null else (x / l) to (y / l)
}

/** Distance from [p] to the polyline [line]. */
private fun distanceToPolyline(p: Pair<Float, Float>, line: List<Pair<Float, Float>>): Float {
    var best = Float.POSITIVE_INFINITY
    for (i in 0 until line.lastIndex) {
        val a = line[i]; val b = line[i + 1]
        val dx = b.first - a.first; val dy = b.second - a.second
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-9f) 0f else (((p.first - a.first) * dx + (p.second - a.second) * dy) / l2).coerceIn(0f, 1f)
        val d = hypot(p.first - a.first - t * dx, p.second - a.second - t * dy)
        if (d < best) best = d
    }
    return best
}

private class Fit(val span: CubicSpan, val error: Float)

/**
 * One cubic standing in for a whole [chain] of original spans, between the chain's two end nodes.
 * The end nodes stay put and so do their tangent directions (so a smooth join stays smooth and a
 * corner stays a corner); only the two handle lengths are free, solved by least squares against the
 * ORIGINAL outline, then refined by re-parameterising. [Fit.error] is the worst gap between the new
 * curve and the original, measured both ways -- always against the original, never against an
 * earlier simplification, so error cannot accumulate from one step to the next. A one-span chain is
 * returned untouched. A chain that is exactly representable (collinear points, or an exact cubic)
 * fits with ~0 error.
 */
private fun fitChain(chain: List<CubicSpan>): Fit {
    if (chain.size == 1) return Fit(chain[0], 0f)
    val a = chain.first().a
    val b = chain.last().b
    val q = ArrayList<Pair<Float, Float>>()
    q.add(a.x to a.y)
    for (span in chain) for (i in 1..FIT_SAMPLES_PER_SPAN) q.add(span.at(i.toFloat() / FIT_SAMPLES_PER_SPAN))
    val first = chain.first()
    val last = chain.last()
    val d0 = unit(first.h1.x - a.x, first.h1.y - a.y) ?: unit(first.h2.x - a.x, first.h2.y - a.y)
        ?: unit(first.b.x - a.x, first.b.y - a.y) ?: (1f to 0f)
    val d3 = unit(last.h2.x - b.x, last.h2.y - b.y) ?: unit(last.h1.x - b.x, last.h1.y - b.y)
        ?: unit(last.a.x - b.x, last.a.y - b.y) ?: (1f to 0f)

    // Chord-length parameter along the original outline.
    val u = FloatArray(q.size)
    for (i in 1 until q.size) u[i] = u[i - 1] + hypot(q[i].first - q[i - 1].first, q[i].second - q[i - 1].second)
    val total = u.last()
    if (total < 1e-6f) return Fit(CubicSpan(a, a, b, b), 0f)
    for (i in u.indices) u[i] /= total

    val chord = hypot(b.x - a.x, b.y - a.y)
    var alpha = chord / 3f
    var beta = chord / 3f
    fun curve(t: Float, al: Float, be: Float): Pair<Float, Float> =
        CubicSpan(a, Pt(a.x + d0.first * al, a.y + d0.second * al, false), Pt(b.x + d3.first * be, b.y + d3.second * be, false), b).at(t)

    repeat(6) {
        var c00 = 0f; var c01 = 0f; var c11 = 0f; var x0 = 0f; var x1 = 0f
        for (i in q.indices) {
            val t = u[i]; val mt = 1f - t
            val b1 = 3f * t * mt * mt; val b2 = 3f * t * t * mt
            val a1x = d0.first * b1; val a1y = d0.second * b1
            val a2x = d3.first * b2; val a2y = d3.second * b2
            val rx = q[i].first - (a.x * (mt * mt * mt + b1) + b.x * (b2 + t * t * t))
            val ry = q[i].second - (a.y * (mt * mt * mt + b1) + b.y * (b2 + t * t * t))
            c00 += a1x * a1x + a1y * a1y; c01 += a1x * a2x + a1y * a2y; c11 += a2x * a2x + a2y * a2y
            x0 += a1x * rx + a1y * ry; x1 += a2x * rx + a2y * ry
        }
        val det = c00 * c11 - c01 * c01
        if (kotlin.math.abs(det) > 1e-9f) {
            val al = (x0 * c11 - x1 * c01) / det
            val be = (c00 * x1 - c01 * x0) / det
            if (al > chord * 1e-3f && be > chord * 1e-3f && al.isFinite() && be.isFinite()) { alpha = al; beta = be }
        }
        // Newton step: move each sample's parameter to the nearest point on the current curve.
        for (i in 1 until q.lastIndex) {
            val t = u[i]; val mt = 1f - t
            val p1x = a.x + d0.first * alpha; val p1y = a.y + d0.second * alpha
            val p2x = b.x + d3.first * beta; val p2y = b.y + d3.second * beta
            val (cx, cy) = curve(t, alpha, beta)
            val d1x = 3f * (mt * mt * (p1x - a.x) + 2f * mt * t * (p2x - p1x) + t * t * (b.x - p2x))
            val d1y = 3f * (mt * mt * (p1y - a.y) + 2f * mt * t * (p2y - p1y) + t * t * (b.y - p2y))
            val d2x = 6f * (mt * (p2x - 2f * p1x + a.x) + t * (b.x - 2f * p2x + p1x))
            val d2y = 6f * (mt * (p2y - 2f * p1y + a.y) + t * (b.y - 2f * p2y + p1y))
            val ex = cx - q[i].first; val ey = cy - q[i].second
            val den = d1x * d1x + d1y * d1y + ex * d2x + ey * d2y
            if (kotlin.math.abs(den) > 1e-9f) u[i] = (t - (ex * d1x + ey * d1y) / den).coerceIn(0f, 1f)
        }
    }

    val span = CubicSpan(a, Pt(a.x + d0.first * alpha, a.y + d0.second * alpha, false), Pt(b.x + d3.first * beta, b.y + d3.second * beta, false), b)
    val dense = (0..64).map { span.at(it / 64f) }
    val error = maxOf(q.maxOf { distanceToPolyline(it, dense) }, dense.maxOf { distanceToPolyline(it, q) })
    return Fit(span, error)
}

/** Worst deviation from the original outline if node ordinal [between] (and everything between its live neighbours) were merged into one span. */
private fun mergeError(pts: List<Pt>, nodes: List<Int>, prev: Int, next: Int): Float =
    fitChain(chainOf(pts, nodes, prev, next)).error

/**
 * Greedy, geometry-aware simplification shared by all compatible anchors.
 * Rank each candidate by its worst true cubic deviation across anchors,
 * recalculating after every removal. Both sides' handles matter, even at
 * corners or mirrored smooth nodes. The normal reduction preserves three
 * per contour; the extreme slider steps can discard smaller contours until
 * only three nodes remain in the entire glyph. Keys refer to the original contour point indices, so preview and apply
 * remove the same nodes without losing anchor compatibility.
 */
fun reductionOrder(glyphs: List<GlyphCorner>): List<Removal> {
    val ref = glyphs.firstOrNull() ?: return emptyList()
    val out = mutableListOf<Removal>()
    ref.contours.forEachIndexed { ci, original ->
        // Node ordinals are shared by every anchor (they are point-compatible); keys use the reference
        // glyph's point indices. Every cost is measured against the ORIGINAL outlines of every anchor.
        val sources = glyphs.map { it.contours[ci].points }
        val nodes = sources.map { pts -> pts.indices.filter { pts[it].onCurve } }
        val alive = nodes[0].indices.toMutableList()
        // Only the removed node's two neighbours change which spans they join; cache everyone else's cost.
        val costs = HashMap<Int, Float>()
        while (alive.size > 3) {
            var bestIdx = -1
            var bestCost = Float.POSITIVE_INFINITY
            for (idx in alive.indices) {
                val ord = alive[idx]
                val cost = costs.getOrPut(ord) {
                    val prev = alive[(idx + alive.size - 1) % alive.size]
                    val next = alive[(idx + 1) % alive.size]
                    sources.indices.maxOf { g -> mergeError(sources[g], nodes[g], prev, next) }
                }
                if (cost < bestCost) {
                    bestCost = cost
                    bestIdx = idx
                }
            }
            if (bestIdx < 0) break
            val removed = alive.removeAt(bestIdx)
            out.add(Removal(ci to nodes[0][removed], bestCost))
            costs.remove(removed)
            costs.remove(alive[(bestIdx + alive.size - 1) % alive.size])
            costs.remove(alive[bestIdx % alive.size])
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
    val ranked = out.indices.sortedWith(compareBy({ runMax[it] }, { it })).map { out[it] }

    // Ordinary reduction preserves each contour as a three-node minimum.
    // At the deliberate over-simplification end of the slider, retain the
    // largest contour and discard other contours, node by node, until the
    // *whole glyph* reaches three on-curve nodes. The preview makes this loss
    // of counters/disconnected shapes visible before Apply.
    val alreadyRemoved = ranked.map { it.key }.toSet()
    val surviving = ref.contours.mapIndexed { ci, c ->
        c.points.indices.filter { c.points[it].onCurve && (ci to it) !in alreadyRemoved }
    }
    val nonempty = surviving.indices.filter { surviving[it].isNotEmpty() }
    if (nonempty.size <= 1) return ranked
    val keep = nonempty.maxBy { ci ->
        glyphs.maxOf { g ->
            val rect = boundsOf(g.contours[ci].points)
            if (rect == null) 0f else rect.width * rect.height
        }
    }
    val additional = mutableListOf<Removal>()
    var remaining = surviving.sumOf { it.size }
    for (ci in nonempty) {
        if (ci == keep || remaining <= 3) continue
        val size = glyphs.maxOf { g ->
            val rect = boundsOf(g.contours[ci].points)
            if (rect == null) 0f else hypot(rect.width, rect.height)
        }
        for (pi in surviving[ci]) {
            if (remaining <= 3) break
            additional.add(Removal(ci to pi, size))
            remaining--
        }
    }
    return ranked + additional
}

/**
 * [glyph] with the [removed] nodes taken out and the outline kept as close to the original as the
 * remaining nodes allow: each stretch between two surviving nodes is refitted to the original curve
 * through the removed nodes (see [fitChain]) instead of just inheriting its neighbours' old handles.
 * A removed node that was redundant leaves the shape exactly as it was. Untouched stretches keep
 * their handles verbatim. A smooth node whose two handles no longer mirror becomes a corner node
 * (same position, same tangent line) so that the drawn shape never moves. Contours are never
 * dropped here, so contour indices still match [glyph]'s.
 */
fun reducedGlyph(glyph: GlyphCorner, removed: Set<PointKey>): GlyphCorner =
    GlyphCorner(
        glyph.width,
        glyph.contours.mapIndexed { ci, c ->
            reduceContour(c, c.points.indices.filter { (ci to it) in removed && c.points[it].onCurve }.toSet())
        }.toMutableList(),
    )

private fun reduceContour(c: ContourData, removed: Set<Int>): ContourData {
    val pts = c.points
    val nodes = pts.indices.filter { pts[it].onCurve }
    if (removed.isEmpty() || nodes.isEmpty() || !isValidCubicContour(c)) return deleteFromContour(c, removed)
    val alive = nodes.indices.filter { nodes[it] !in removed }
    if (alive.size < 3) return deleteFromContour(c, removed)
    val n = nodes.size
    // Per surviving node: its new out-arm and the in-arm of the node that follows it.
    class Join(val out: Pt?, val inNext: Pt?)
    val joins = alive.mapIndexed { k, a ->
        val b = alive[(k + 1) % alive.size]
        if ((a + 1) % n == b) {
            Join(outArmIndex(pts, nodes[a])?.let { pts[it].copy2() }, inArmIndex(pts, nodes[b])?.let { pts[it].copy2() })
        } else {
            val fit = fitChain(chainOf(pts, nodes, a, b)).span
            Join(Pt(fit.h1.x, fit.h1.y, false), Pt(fit.h2.x, fit.h2.y, false))
        }
    }
    val out = mutableListOf<Pt>()
    alive.forEachIndexed { k, a ->
        val node = pts[nodes[a]].copy2()
        val arrive = joins[(k + alive.size - 1) % alive.size].inNext
        val leave = joins[k].out
        if (node.smooth && arrive != null && leave != null) {
            // Mirrored means equal length, opposite direction: the two arm vectors cancel.
            val sx = (arrive.x - node.x) + (leave.x - node.x)
            val sy = (arrive.y - node.y) + (leave.y - node.y)
            if (hypot(sx, sy) > 0.5f) node.smooth = false
        }
        out.add(node)
        joins[k].out?.let { out.add(it) }
        joins[k].inNext?.let { out.add(it) }
    }
    return ContourData(out)
}

/**
 * [glyph] with the [removed] nodes deleted, each with its arms, keeping every
 * contour valid (see [deleteFromContour]). Contours are never dropped here, so
 * the result's contour indices still match [glyph]'s.
 */
fun withoutPoints(glyph: GlyphCorner, removed: Set<PointKey>, mergeIntoPrevious: Boolean = false): GlyphCorner =
    GlyphCorner(
        glyph.width,
        glyph.contours.mapIndexed { ci, c ->
            deleteFromContour(c, c.points.indices.filter { (ci to it) in removed && c.points[it].onCurve }.toSet(), mergeIntoPrevious = mergeIntoPrevious)
        }.toMutableList(),
    )

// ------------------------------------------------------- match to the fewest

/**
 * What [matchToFewest] produced: [glyphs] (state name to its rebuilt outline) and [dropped] (contours a
 * state had that no other state could pair with), or a [problem] when it can't be done.
 */
class MatchOutcome(val glyphs: Map<String, GlyphCorner>?, val dropped: Map<String, Int> = emptyMap(), val problem: String? = null)

private fun nodesIn(c: ContourData) = c.points.count { it.onCurve }

private fun nodeArea(c: ContourData): Float {
    val ns = c.points.filter { it.onCurve }
    var a = 0f
    for (i in ns.indices) { val p = ns[i]; val q = ns[(i + 1) % ns.size]; a += p.x * q.y - q.x * p.y }
    return a
}

/** One completed contour (node, out-arm, in-arm per segment) as parallel lists, so it can be reversed and rotated. */
private class Loop(val nodes: List<Pt>, val outs: List<Pt>, val ins: List<Pt>) {
    val size get() = nodes.size

    fun toContour() = ContourData(
        (0 until size).flatMap { j -> listOf(nodes[j].copy2(), outs[j].copy2(), ins[(j + 1) % size].copy2()) }.toMutableList(),
    )

    /** Same outline walked the other way: each node keeps its place, its two arms swap roles. */
    fun reversed(): Loop {
        val order = List(size) { (size - it) % size }
        return Loop(order.map { nodes[it] }, order.map { ins[it] }, order.map { outs[it] })
    }

    fun rotated(by: Int) = Loop(List(size) { nodes[(it + by) % size] }, List(size) { outs[(it + by) % size] }, List(size) { ins[(it + by) % size] })
}

private fun loopOf(c: ContourData): Loop {
    val pts = c.points
    val n = pts.size / 3
    return Loop(List(n) { pts[3 * it] }, List(n) { pts[3 * it + 1] }, List(n) { pts[(3 * (it - 1) + 2 + pts.size) % pts.size] })
}

/** [c] with its longest segments split in two (the outline does not move) until it has [target] nodes. */
private fun subdividedTo(c: ContourData, target: Int): ContourData {
    var loop = loopOf(c)
    fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f, false)
    while (loop.size < target) {
        val n = loop.size
        val j = (0 until n).maxBy { i -> val a = loop.nodes[i]; val b = loop.nodes[(i + 1) % n]; hypot(b.x - a.x, b.y - a.y) + hypot(loop.outs[i].x - a.x, loop.outs[i].y - a.y) }
        val p0 = loop.nodes[j]; val p1 = loop.outs[j]; val p2 = loop.ins[(j + 1) % n]; val p3 = loop.nodes[(j + 1) % n]
        val q0 = mid(p0, p1); val q1 = mid(p1, p2); val q2 = mid(p2, p3)
        val r0 = mid(q0, q1); val r1 = mid(q1, q2)
        val s = Pt((r0.x + r1.x) / 2f, (r0.y + r1.y) / 2f, true, smooth = true)
        val nodes = loop.nodes.toMutableList(); val outs = loop.outs.toMutableList(); val ins = loop.ins.toMutableList()
        outs[j] = q0
        ins[(j + 1) % n] = q2
        nodes.add(j + 1, s); outs.add(j + 1, r1); ins.add(j + 1, r0)
        loop = Loop(nodes, outs, ins)
    }
    return loop.toContour()
}

/**
 * [c] with the cheapest nodes (by the simplifier, against [c]'s own outline) removed until [target] remain,
 * or, when it has fewer than [target], extra nodes added without changing the outline.
 */
private fun reducedTo(c: ContourData, target: Int): ContourData {
    if (nodesIn(c) < target) return subdividedTo(c, target)
    val extra = nodesIn(c) - target
    if (extra <= 0) return c
    val g = GlyphCorner(0f, mutableListOf(c))
    val keys = reductionOrder(listOf(g)).take(extra).map { it.key }.toSet()
    return completeNodeHandles(reducedGlyph(g, keys).contours[0])
}

/**
 * Gives the drawn [glyphs] (state name to outline) one shared structure with as few nodes as the sparsest
 * state has. The state with the fewest nodes is the template: its contours are the ones kept. Each other state
 * pairs its contours with the template's by position and size (contours with no partner are dropped), then every
 * paired contour is cut down to the fewest nodes any state has for it by the shape-preserving simplifier,
 * turned to run the same way round as the template, and started at the node that lines up best with the
 * template's. Every node ends with both handles, so the states interpolate point for point.
 *
 * With [mostNodes] it goes the other way: the state with the most nodes is the template and every sparser state
 * gains nodes (by splitting its longest segments, which never moves its outline) up to that count. That needs
 * the same number of contours in every state.
 */
fun matchToFewest(glyphs: Map<String, GlyphCorner>, mostNodes: Boolean = false): MatchOutcome {
    if (glyphs.size < 2) return MatchOutcome(null, problem = "Draw at least two anchors first.")
    val done = glyphs.mapValues { completeNodeHandles(it.value) }
    if (done.values.any { g -> g.contours.isEmpty() || g.contours.any { it.points.isEmpty() || !isValidCubicContour(it) } }) {
        return MatchOutcome(null, problem = "An anchor has an empty or malformed outline.")
    }
    // Adding nodes can't invent a missing contour, and dropping one would lose drawing: refuse instead.
    if (mostNodes && done.values.map { it.contours.size }.toSet().size > 1) {
        return MatchOutcome(null, problem = "The anchors have different numbers of contours, so nodes alone can't match them. Draw the missing contour on the others.")
    }
    val ref = if (mostNodes) done.entries.maxBy { (_, g) -> g.contours.sumOf(::nodesIn) }.key
        else done.entries.minBy { (_, g) -> g.contours.sumOf(::nodesIn) }.key
    val k = done.values.minOf { it.contours.size }
    fun area(c: ContourData) = boundsOf(c.points)?.let { it.width * it.height } ?: 0f
    // The template: the reference's k largest contours, in their own order.
    val keep = done.getValue(ref).contours.indices.sortedByDescending { area(done.getValue(ref).contours[it]) }.take(k).sorted()
    val template = keep.map { done.getValue(ref).contours[it] }

    // paired[name][t] = the contour of that state standing for template contour t.
    val paired = HashMap<String, List<ContourData>>()
    val dropped = HashMap<String, Int>()
    for ((name, g) in done) {
        if (name == ref) { paired[name] = template; dropped[name] = g.contours.size - k; continue }
        val boxes = g.contours.map { boundsOf(it.points)!! }
        val tboxes = template.map { boundsOf(it.points)!! }
        val costs = ArrayList<Triple<Float, Int, Int>>()
        for (t in template.indices) for (s in g.contours.indices) {
            val a = tboxes[t]; val b = boxes[s]
            val scale = maxOf(1f, hypot(a.width, a.height))
            val size = kotlin.math.abs(kotlin.math.ln((b.width * b.height + 1f) / (a.width * a.height + 1f))) / 2f
            costs.add(Triple(hypot(a.cx - b.cx, a.cy - b.cy) / scale + size, t, s))
        }
        val pick = arrayOfNulls<Int>(template.size)
        val used = HashSet<Int>()
        for ((_, t, s) in costs.sortedBy { it.first }) if (pick[t] == null && s !in used) { pick[t] = s; used.add(s) }
        paired[name] = pick.map { g.contours[it!!] }
        dropped[name] = g.contours.size - k
    }

    val rebuilt = HashMap<String, MutableList<ContourData>>()
    for (t in template.indices) {
        // A closed contour needs three nodes to be a shape the simplifier and the editor can work with.
        val target = if (mostNodes) done.keys.maxOf { nodesIn(paired.getValue(it)[t]) }
            else maxOf(3, done.keys.minOf { nodesIn(paired.getValue(it)[t]) })
        val reduced = done.keys.associateWith { reducedTo(paired.getValue(it)[t], target) }
        val base = loopOf(reduced.getValue(ref))
        fun norm(l: Loop): List<Pair<Float, Float>> {
            val b = boundsOf(l.nodes) ?: return l.nodes.map { 0f to 0f }
            val w = if (b.width < 1e-3f) 1f else b.width; val h = if (b.height < 1e-3f) 1f else b.height
            return l.nodes.map { (it.x - b.minX) / w to (it.y - b.minY) / h }
        }
        val want = norm(base)
        for ((name, c) in reduced) {
            var loop = loopOf(c)
            if (name != ref) {
                if ((nodeArea(c) < 0f) != (nodeArea(reduced.getValue(ref)) < 0f)) loop = loop.reversed()
                if (loop.size == base.size) {
                    val best = (0 until loop.size).minBy { s ->
                        val got = norm(loop.rotated(s))
                        got.indices.sumOf { i -> ((got[i].first - want[i].first).let { it * it } + (got[i].second - want[i].second).let { it * it }).toDouble() }
                    }
                    loop = loop.rotated(best)
                }
            }
            rebuilt.getOrPut(name) { mutableListOf() }.add(loop.toContour())
        }
    }
    val out = done.mapValues { (name, g) -> GlyphCorner(g.width, rebuilt.getValue(name)) }
    compatibilityIssue(out, out.keys.toList())?.let { return MatchOutcome(null, problem = "Couldn't reconcile them: $it") }
    return MatchOutcome(out, dropped.filterValues { it > 0 })
}

// ------------------------------------------------------- natural path

/**
 * [regular] with every point moved to the straight-line midpoint of the same point in [lo] and [hi] -- the
 * natural path, where each node's travel from one extreme to the other is a straight line with Regular
 * exactly halfway. Structure and node types stay [regular]'s. Null unless the three match point for point.
 */
fun midpointGlyph(lo: GlyphCorner, regular: GlyphCorner, hi: GlyphCorner): GlyphCorner? {
    val names = listOf("lo", "regular", "hi")
    if (compatibilityIssue(mapOf("lo" to lo, "regular" to regular, "hi" to hi), names) != null) return null
    val out = regular.deepCopy()
    out.width = (lo.width + hi.width) / 2f
    out.contours.forEachIndexed { ci, c ->
        c.points.forEachIndexed { pi, p ->
            val a = lo.contours[ci].points[pi]; val b = hi.contours[ci].points[pi]
            p.x = (a.x + b.x) / 2f; p.y = (a.y + b.y) / 2f
        }
    }
    return out
}

/** The largest distance any point of [a] sits from the same point of [b]; infinite if their structures differ. */
fun maxPointDistance(a: GlyphCorner, b: GlyphCorner): Float {
    if (a.contours.size != b.contours.size) return Float.POSITIVE_INFINITY
    var worst = 0f
    for (ci in a.contours.indices) {
        val pa = a.contours[ci].points; val pb = b.contours[ci].points
        if (pa.size != pb.size) return Float.POSITIVE_INFINITY
        for (i in pa.indices) worst = maxOf(worst, hypot(pa[i].x - pb[i].x, pa[i].y - pb[i].y))
    }
    return worst
}
