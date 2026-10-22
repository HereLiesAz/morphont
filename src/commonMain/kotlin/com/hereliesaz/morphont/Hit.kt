package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Offset

/**
 * Normal selection finds the nearest point. Node Move prefers an anchor, and
 * accepts a nearby handle only as a way to grab the node that owns it.
 * Node-only mode never returns an off-curve handle.
 */
fun hitTestPoint(
    glyph: GlyphCorner,
    mapper: SpaceMapper,
    canvasPos: Offset,
    radiusPx: Float = 12f,
    nodeOnly: Boolean = false,
): PointKey? {
    fun nearest(anchorsOnly: Boolean): PointKey? {
        var best: PointKey? = null
        var distance = radiusPx
        glyph.contours.forEachIndexed { ci, contour ->
            contour.points.forEachIndexed { pi, p ->
                if (anchorsOnly && !p.onCurve) return@forEachIndexed
                if (!p.onCurve && isRetracted(contour.points, pi)) return@forEachIndexed
                val d = (mapper.toCanvas(p.x, p.y) - canvasPos).getDistance()
                if (d <= distance) {
                    distance = d
                    best = ci to pi
                }
            }
        }
        return best
    }
    if (!nodeOnly) return nearest(false)
    nearest(true)?.let { return it }
    val (ci, pi) = nearest(false) ?: return null
    val owner = armOwner(glyph.contours[ci].points, pi) ?: return null
    return ci to owner
}

/** Where a pointer is over a segment: the segment leaves node [from] of contour [contour], at parameter [t]. */
data class SegmentHit(val contour: Int, val from: Int, val t: Float)

/** The segment nearest [canvasPos] within [radiusPx], sampled as a polyline in canvas space; null if none is that close. */
fun hitTestSegment(glyph: GlyphCorner, mapper: SpaceMapper, canvasPos: Offset, radiusPx: Float = 12f): SegmentHit? {
    var best: SegmentHit? = null
    var bestDistance = radiusPx
    glyph.contours.forEachIndexed { ci, c ->
        val pts = c.points
        if (!isValidCubicContour(c)) return@forEachIndexed
        val nodes = pts.indices.filter { pts[it].onCurve }
        nodes.forEachIndexed { k, from ->
            val to = nodes[(k + 1) % nodes.size]
            val a = pts[from]; val b = pts[to]
            val p1 = outArmIndex(pts, from)?.let { pts[it] } ?: Pt(a.x + (b.x - a.x) / 3f, a.y + (b.y - a.y) / 3f, false)
            val p2 = inArmIndex(pts, to)?.let { pts[it] } ?: Pt(b.x - (b.x - a.x) / 3f, b.y - (b.y - a.y) / 3f, false)
            fun at(t: Float): Offset {
                val u = 1f - t
                return mapper.toCanvas(
                    u * u * u * a.x + 3f * u * u * t * p1.x + 3f * u * t * t * p2.x + t * t * t * b.x,
                    u * u * u * a.y + 3f * u * u * t * p1.y + 3f * u * t * t * p2.y + t * t * t * b.y,
                )
            }
            val steps = 32
            var prev = at(0f)
            for (i in 1..steps) {
                val cur = at(i.toFloat() / steps)
                val dx = cur.x - prev.x; val dy = cur.y - prev.y
                val l2 = dx * dx + dy * dy
                val u = if (l2 < 1e-9f) 0f else (((canvasPos.x - prev.x) * dx + (canvasPos.y - prev.y) * dy) / l2).coerceIn(0f, 1f)
                val d = (Offset(prev.x + dx * u, prev.y + dy * u) - canvasPos).getDistance()
                if (d <= bestDistance) {
                    bestDistance = d
                    best = SegmentHit(ci, from, (i - 1 + u) / steps)
                }
                prev = cur
            }
        }
    }
    return best
}
