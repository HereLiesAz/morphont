package com.hereliesaz.morphont

/**
 * 0 at t=0 and t=1, exactly 1 at t=0.5 -- used to blend the regular
 * anchor's correction in at the center and fade it out to nothing at
 * the two extremes.
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
 * Forced-parabola interpolation along ONE axis: [lo] at t=0, [hi] at t=1,
 * exactly [center] at t=0.5 (Regular's value, since Regular sits at the
 * midpoint of every axis by definition). See [bump] for why this
 * specific curve and not some tunable one.
 */
fun axisInterp(lo: Float, hi: Float, center: Float, t: Float): Float {
    val linear = lo + (hi - lo) * t
    val linearAtCenter = (lo + hi) / 2f
    return linear + bump(t) * (center - linearAtCenter)
}

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
 * Five shapes are drawn total, each varying only ONE axis from the
 * center: extraThin/extraBlack are the weight extremes at regular width;
 * condensed/wide are the width extremes at regular weight; regular sits
 * at both axes' center. This is deliberately NOT a 2x2 grid of four joint
 * corners (thin+condensed, thin+wide, etc.) -- nobody draws a "thin AND
 * condensed simultaneously" shape here, and a real variable font's own
 * named instances (Thin, Black, Regular Condensed, ...) are structured
 * the same single-axis-from-center way, not as joint corners either.
 *
 * The interpolated value at (wght, wdth) is the weight axis's forced-
 * parabola curve (see [axisInterp]) plus the width axis's, minus Regular
 * once so it isn't counted twice -- an additive combination, the same
 * "sum the per-axis deltas from default" model real OpenType variable
 * fonts use internally (gvar tuples are literally added together). This
 * reproduces all five anchors exactly along their own axis; away from
 * both axes (e.g. simultaneously thin AND condensed) it's a reasoned
 * extrapolation, not a measurement, since no anchor was ever drawn there.
 *
 * Requires [glyphs] to already be point-compatible; see
 * [compatibilityIssue].
 */
fun interpolateGlyph(glyphs: Map<String, GlyphCorner>, wght: Float, wdth: Float): GlyphCorner {
    val gThin = glyphs.getValue("extraThin")
    val gBlack = glyphs.getValue("extraBlack")
    val gCond = glyphs.getValue("condensed")
    val gWide = glyphs.getValue("wide")
    val gReg = glyphs.getValue("regular")

    val outWidth = axisInterp(gThin.width, gBlack.width, gReg.width, wght) +
        axisInterp(gCond.width, gWide.width, gReg.width, wdth) - gReg.width

    val outContours = gReg.contours.mapIndexed { ci, c ->
        val pts = c.points.mapIndexed { pi, pReg ->
            val pThin = gThin.contours[ci].points[pi]
            val pBlack = gBlack.contours[ci].points[pi]
            val pCond = gCond.contours[ci].points[pi]
            val pWide = gWide.contours[ci].points[pi]
            val x = axisInterp(pThin.x, pBlack.x, pReg.x, wght) + axisInterp(pCond.x, pWide.x, pReg.x, wdth) - pReg.x
            val y = axisInterp(pThin.y, pBlack.y, pReg.y, wght) + axisInterp(pCond.y, pWide.y, pReg.y, wdth) - pReg.y
            Pt(x, y, pReg.onCurve, pReg.smooth)
        }.toMutableList()
        ContourData(pts)
    }.toMutableList()

    return GlyphCorner(outWidth, outContours)
}
