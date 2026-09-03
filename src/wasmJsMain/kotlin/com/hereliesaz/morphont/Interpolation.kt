package com.hereliesaz.morphont

/** T=thin/B=black, C=condensed/W=wide. Plain bilinear interpolation of the four grid corners. */
fun bilerp(vTC: Float, vTW: Float, vBC: Float, vBW: Float, wght: Float, wdth: Float): Float {
    val top = vTC + (vTW - vTC) * wdth
    val bot = vBC + (vBW - vBC) * wdth
    return top + (bot - top) * wght
}

/**
 * 0 at t=0 and t=1, exactly 1 at t=0.5 -- used to blend the regular
 * anchor's correction in at the center and fade it out to nothing at the
 * four corners.
 *
 * This specific parabola isn't a style choice, it's forced. A quadratic
 * Bezier does NOT pass through its own control point (a common
 * misconception) -- to make one pass exactly through a chosen midpoint
 * `M` with fixed endpoints `A` and `B`, the real control point has to be
 * `C = 2M - (A+B)/2` (see [bezierControlFor]). Substituting that into the
 * standard quadratic Bezier formula and simplifying leaves exactly
 * `linear(t) + bump(t) * (M - linear(0.5))` with this parabola as
 * `bump(t)` -- no free parameter. So dragging Regular's point already
 * *is* the easing curve; there's nothing else to tune.
 */
fun bump(t: Float): Float = 4f * t * (1f - t)

/** The quadratic-Bezier control point that makes the curve from [a] to [b] pass exactly through [m] at t=0.5. */
fun bezierControlFor(a: Float, b: Float, m: Float): Float = 2f * m - (a + b) / 2f

/**
 * Checks that [names] (default: all five anchors) are point-for-point
 * compatible: same contour count, same point count per contour, same
 * on/off-curve type per point index. Interpolation only works when this
 * returns null, since it matches points by index rather than guessing
 * correspondences.
 */
fun compatibilityIssue(glyphs: Map<String, GlyphCorner?>, names: List<String> = ANCHORS): String? {
    val counts = names.map { glyphs[it]?.contours?.size ?: -1 }
    if (counts.any { it < 0 }) return "Not every anchor has a glyph loaded yet."
    if (counts.toSet().size > 1) {
        return "Contour count differs: " + names.zip(counts).joinToString(", ") { "${it.first}=${it.second}" }
    }
    val nContours = counts.firstOrNull() ?: 0
    for (ci in 0 until nContours) {
        val ptCounts = names.map { glyphs.getValue(it)!!.contours[ci].points.size }
        if (ptCounts.toSet().size > 1) {
            return "Contour $ci: point count differs: " + names.zip(ptCounts).joinToString(", ") { "${it.first}=${it.second}" }
        }
        val n = ptCounts.firstOrNull() ?: 0
        for (pi in 0 until n) {
            val types = names.map { glyphs.getValue(it)!!.contours[ci].points[pi].onCurve }
            if (types.toSet().size > 1) {
                return "Contour $ci, point $pi: on/off-curve type differs."
            }
        }
    }
    return null
}

/**
 * Five shapes are drawn total: four grid corners plus a regular anchor at
 * the dead center. The corners are treated directly as the corners of one
 * square in (weight, width) space: extraThin=(0,0) extraBlack=(1,0)
 * condensed=(0,1) wide=(1,1). Plain bilinear interpolation of just those
 * four corners already passes through all four exactly, but at the center
 * (0.5, 0.5) it can only ever land on their average -- it has no way to
 * reproduce a `regular` shape that was hand-corrected to be anything
 * else. So the interpolated value at (wght, wdth) is corner-bilinear plus
 * a displacement term: the difference between the drawn `regular` anchor
 * and what bilinear alone would have predicted there, scaled by [bump].
 * This reproduces all five anchors exactly and blends smoothly between
 * them, without needing the four additional edge-midpoint masters a true
 * biquadratic patch would require, and without needing any adjustable
 * easing curve either.
 *
 * Requires [glyphs] to already be point-compatible; see
 * [compatibilityIssue].
 */
fun interpolateGlyph(glyphs: Map<String, GlyphCorner>, wght: Float, wdth: Float): GlyphCorner {
    val g00 = glyphs.getValue("extraThin")
    val g10 = glyphs.getValue("extraBlack")
    val g01 = glyphs.getValue("condensed")
    val g11 = glyphs.getValue("wide")
    val gR = glyphs.getValue("regular")

    val b = bump(wght) * bump(wdth)
    val bilerpWidth = bilerp(g00.width, g01.width, g10.width, g11.width, wght, wdth)
    val widthDisplacement = gR.width - (g00.width + g01.width + g10.width + g11.width) / 4f
    val outWidth = bilerpWidth + widthDisplacement * b

    val outContours = g00.contours.mapIndexed { ci, c ->
        val pts = c.points.mapIndexed { pi, p ->
            val p10 = g10.contours[ci].points[pi]
            val p01 = g01.contours[ci].points[pi]
            val p11 = g11.contours[ci].points[pi]
            val pR = gR.contours[ci].points[pi]
            val bx = bilerp(p.x, p01.x, p10.x, p11.x, wght, wdth)
            val by = bilerp(p.y, p01.y, p10.y, p11.y, wght, wdth)
            val dx = pR.x - (p.x + p01.x + p10.x + p11.x) / 4f
            val dy = pR.y - (p.y + p01.y + p10.y + p11.y) / 4f
            Pt(bx + dx * b, by + dy * b, p.onCurve, p.smooth)
        }.toMutableList()
        ContourData(pts)
    }.toMutableList()

    return GlyphCorner(outWidth, outContours)
}
