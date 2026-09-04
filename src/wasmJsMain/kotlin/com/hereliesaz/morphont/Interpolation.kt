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
 * `2 * axes.size + 1` shapes are drawn total, each varying only ONE axis
 * from the center: each axis's own lo/hi extremes at every other axis's
 * regular value, and the one shared Regular at every axis's center. This
 * is deliberately NOT a joint grid of simultaneous corners (thin+condensed,
 * thin+condensed+slab-serif, etc.) -- nobody draws a "thin AND condensed
 * simultaneously" shape here, and a real variable font's own named
 * instances (Thin, Black, Regular Condensed, ...) are structured the same
 * single-axis-from-center way, not as joint corners either.
 *
 * The interpolated value at a given point in [axisValues] is the sum of
 * every axis's own forced-parabola curve (see [axisInterp]), minus Regular
 * `axes.size - 1` times so it isn't counted once per axis -- an additive
 * combination, the same "sum the per-axis deltas from default" model real
 * OpenType variable fonts use internally (gvar tuples are literally added
 * together). This reproduces every anchor exactly along its own axis;
 * away from every axis (e.g. simultaneously thin AND condensed) it's a
 * reasoned extrapolation, not a measurement, since no anchor was ever
 * drawn there.
 *
 * [axisValues] maps each axis's tag (see [Axis.tag]) to its 0..1 position;
 * an axis missing from the map defaults to 0.5 (Regular). Requires
 * [glyphs] to already be point-compatible; see [compatibilityIssue].
 */
fun interpolateGlyph(
    glyphs: Map<String, GlyphCorner>,
    axisValues: Map<String, Float>,
    axes: List<Axis> = Axis.ALL,
): GlyphCorner {
    val gReg = glyphs.getValue("regular")
    val regularCountedExtra = (axes.size - 1).coerceAtLeast(0)

    var outWidth = 0f
    for (axis in axes) {
        val t = axisValues[axis.tag] ?: 0.5f
        val gLo = glyphs.getValue(axis.lo)
        val gHi = glyphs.getValue(axis.hi)
        outWidth += axisInterp(gLo.width, gHi.width, gReg.width, t)
    }
    outWidth -= regularCountedExtra * gReg.width

    val outContours = gReg.contours.mapIndexed { ci, c ->
        val pts = c.points.mapIndexed { pi, pReg ->
            var x = 0f
            var y = 0f
            for (axis in axes) {
                val t = axisValues[axis.tag] ?: 0.5f
                val pLo = glyphs.getValue(axis.lo).contours[ci].points[pi]
                val pHi = glyphs.getValue(axis.hi).contours[ci].points[pi]
                x += axisInterp(pLo.x, pHi.x, pReg.x, t)
                y += axisInterp(pLo.y, pHi.y, pReg.y, t)
            }
            x -= regularCountedExtra * pReg.x
            y -= regularCountedExtra * pReg.y
            Pt(x, y, pReg.onCurve, pReg.smooth)
        }.toMutableList()
        ContourData(pts)
    }.toMutableList()

    return GlyphCorner(outWidth, outContours)
}
