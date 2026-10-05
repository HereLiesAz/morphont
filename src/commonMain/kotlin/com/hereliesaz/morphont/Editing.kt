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
            // Eight off-curve controls on a circle of radius r/cos(22.5deg): TrueType's
            // implied on-curve midpoints then land exactly on the true ellipse.
            val k = 1f / cos(PI.toFloat() / 8f)
            (0 until 8).map { i ->
                val a = PI.toFloat() / 8f + i * PI.toFloat() / 4f
                Pt(b.cx + cos(a) * b.width / 2f * k, b.cy + sin(a) * b.height / 2f * k, onCurve = false)
            }
        }
    }
    return mutableListOf(ContourData(pts.toMutableList()))
}

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

private fun segDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val vx = bx - ax; val vy = by - ay
    val len2 = vx * vx + vy * vy
    if (len2 <= 1e-9f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * vx), py - (ay + t * vy))
}

/**
 * Greedy simplification order shared by every anchor in [glyphs] so they stay
 * point-compatible: at each step, remove the point whose removal deviates the
 * outline least in the anchor where it hurts most (distance from the point to
 * the chord between its surviving neighbours). Contours keep at least three
 * points and at least one on-curve point. The topology is taken from the first
 * glyph; callers must pass only mutually compatible glyphs.
 */
fun reductionOrder(glyphs: List<GlyphCorner>): List<Removal> {
    val ref = glyphs.firstOrNull() ?: return emptyList()
    val out = mutableListOf<Removal>()
    ref.contours.forEachIndexed { ci, contour ->
        val alive = contour.points.indices.toMutableList()
        while (alive.size > 3) {
            var bestIdx = -1
            var bestCost = Float.POSITIVE_INFINITY
            for (k in alive.indices) {
                val pi = alive[k]
                val remainingOn = alive.count { it != pi && contour.points[it].onCurve }
                if (remainingOn == 0) continue
                val prev = alive[(k - 1 + alive.size) % alive.size]
                val next = alive[(k + 1) % alive.size]
                var cost = 0f
                for (g in glyphs) {
                    val pts = g.contours[ci].points
                    val p = pts[pi]; val a = pts[prev]; val b = pts[next]
                    val d = segDistance(p.x, p.y, a.x, a.y, b.x, b.y)
                    if (d > cost) cost = d
                }
                if (cost < bestCost) { bestCost = cost; bestIdx = k }
            }
            if (bestIdx < 0) break
            out.add(Removal(ci to alive[bestIdx], bestCost))
            alive.removeAt(bestIdx)
        }
    }
    // Interleave contours by cost so the slider always removes the cheapest point next.
    // A removal can only happen after its contour's earlier ones, so rank by the running
    // max within that contour (monotone per contour), ties broken by original order.
    val runMax = FloatArray(out.size)
    val perContour = HashMap<Int, Float>()
    out.forEachIndexed { i, r ->
        val m = maxOf(perContour[r.key.first] ?: 0f, r.error)
        perContour[r.key.first] = m
        runMax[i] = m
    }
    return out.indices.sortedWith(compareBy({ runMax[it] }, { it })).map { out[it] }
}

fun withoutPoints(glyph: GlyphCorner, removed: Set<PointKey>): GlyphCorner =
    GlyphCorner(
        glyph.width,
        glyph.contours.mapIndexed { ci, c ->
            ContourData(c.points.filterIndexed { pi, _ -> (ci to pi) !in removed }.map { it.copy2() }.toMutableList())
        }.toMutableList(),
    )
