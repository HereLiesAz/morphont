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
