package com.hereliesaz.transfontmation

import androidx.compose.ui.geometry.Offset

/** Finds the nearest point within [radiusPx] of [canvasPos], or null if none is close enough. */
fun hitTestPoint(glyph: GlyphCorner, mapper: SpaceMapper, canvasPos: Offset, radiusPx: Float = 12f): PointKey? {
    var best: PointKey? = null
    var bestDist = radiusPx
    glyph.contours.forEachIndexed { ci, c ->
        c.points.forEachIndexed { pi, p ->
            val pos = mapper.toCanvas(p.x, p.y)
            val d = (pos - canvasPos).getDistance()
            if (d <= bestDist) {
                bestDist = d
                best = ci to pi
            }
        }
    }
    return best
}
