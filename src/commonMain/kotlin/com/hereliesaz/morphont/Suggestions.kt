package com.hereliesaz.morphont

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.tan

/*
 * Suggested edits: a list recomputed from the glyph as it is right now, each
 * a one-tap, undoable change. Every suggestion that rewrites an anchor from
 * Regular keeps Regular's point order, so the result stays interpolatable.
 *
 * Kinds:
 *  - Generate: derive the active extreme from Regular (embolden, lighten,
 *    condense, extend, italicize, grade, x-height...).
 *  - Cleanup: duplicate points, zero-drift redundant points, contour direction.
 *  - Alignment: points a few units off a metric line; nearly straight stems.
 *  - Consistency: stems that almost match; uneven sidebearings.
 *  - Interpolation: anchors that don't match.
 */

/** One suggested edit. [detail] is a short readout shown after the label. */
class Suggestion(val label: String, val detail: String? = null, val apply: (AppState) -> Unit)

// ---------------------------------------------------------------- geometry

private fun signedArea(pts: List<Pt>): Float {
    var a = 0f
    for (i in pts.indices) {
        val p = pts[i]; val q = pts[(i + 1) % pts.size]
        a += p.x * q.y - q.x * p.y
    }
    return a / 2f
}

private fun inside(x: Float, y: Float, pts: List<Pt>): Boolean {
    var c = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
        j = i
    }
    return c
}

/** True when contour [ci] bounds filled area from outside (even nesting depth), false for a counter. */
private fun isOuter(contours: List<ContourData>, ci: Int): Boolean {
    val p = contours[ci].points.firstOrNull() ?: return true
    var depth = 0
    contours.forEachIndexed { j, c -> if (j != ci && c.points.size >= 3 && inside(p.x, p.y, c.points)) depth++ }
    return depth % 2 == 0
}

/**
 * Point-preserving outline offset: every point moves along its corner's
 * bisector, away from the filled side by [dx]/[dy] (negative thins). Same
 * points, same order -- the result interpolates with its source.
 */
fun offsetOutline(contours: List<ContourData>, dx: Float, dy: Float): MutableList<ContourData> =
    contours.mapIndexed { ci, c ->
        val pts = c.points
        if (pts.size < 3) return@mapIndexed c.deepCopy()
        // Outward-of-polygon normal is (ey, -ex) for a counter-clockwise polygon; flip for clockwise,
        // and flip again for a counter so "outward" always means away from the ink.
        var sign = if (signedArea(pts) > 0f) 1f else -1f
        if (!isOuter(contours, ci)) sign = -sign
        ContourData(pts.indices.map { i ->
            val p = pts[i]; val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[(i + 1) % pts.size]
            fun n(x0: Float, y0: Float, x1: Float, y1: Float): Pair<Float, Float> {
                val ex = x1 - x0; val ey = y1 - y0; val l = hypot(ex, ey).takeIf { it > 1e-4f } ?: return 0f to 0f
                return ey / l * sign to -ex / l * sign
            }
            val (n1x, n1y) = n(a.x, a.y, p.x, p.y)
            val (n2x, n2y) = n(p.x, p.y, b.x, b.y)
            var nx = n1x + n2x; var ny = n1y + n2y
            val l = hypot(nx, ny)
            if (l < 1e-4f) { nx = n1x; ny = n1y } else { nx /= l; ny /= l }
            // Miter so straight stems move by exactly the offset; capped on sharp corners.
            val miter = 1f / max(0.4f, nx * n1x + ny * n1y)
            Pt(p.x + nx * miter * dx, p.y + ny * miter * dy, p.onCurve, p.smooth)
        }.toMutableList())
    }.toMutableList()

/** Ink runs crossed by the horizontal line y, as (left, right) pairs, from the control polygons. */
fun inkRuns(contours: List<ContourData>, y: Float): List<Pair<Float, Float>> {
    val xs = mutableListOf<Float>()
    for (c in contours) {
        val pts = c.points
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            if ((a.y > y) != (b.y > y)) xs.add(a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x))
        }
    }
    xs.sort()
    return xs.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
}

/** The glyph's typical vertical stem width: the median ink run across mid x-height (or mid-glyph). */
fun stemWidth(g: GlyphCorner, m: FontMetrics): Float? {
    val b = boundsOfContours(g.contours) ?: return null
    val y = if (b.maxY >= m.xHeight * 0.6f && b.minY <= m.xHeight * 0.4f) m.xHeight * 0.5f else b.cy
    val runs = inkRuns(g.contours, y).map { it.second - it.first }.filter { it > 0f }.sorted()
    return runs.getOrNull(runs.size / 2)
}

private fun corner(g: GlyphCorner, contours: List<ContourData>, width: Float = g.width) =
    GlyphCorner(width, contours.map { it.deepCopy() }.toMutableList())

private fun points(g: GlyphCorner) = g.contours.sumOf { it.points.size }

// ------------------------------------------------------------- suggestions

/** Anchors an outline-structure change must hit together to keep interpolation intact. */
private fun scopeFor(app: AppState): List<String> =
    if (app.compatibility() == null) ANCHORS else listOf(app.activeAnchor)

/** Replaces the active anchor with [make]'s result as one undoable edit, and says so. */
private fun replaceActive(app: AppState, what: String, make: (GlyphCorner) -> GlyphCorner): (AppState) -> Unit = { a ->
    val st = a.anchors.getValue(a.activeAnchor)
    st.replaceWithHistory(make(a.anchors.getValue("regular").glyph))
    a.setStatus("$what. Undo puts it back.")
}

private fun generate(app: AppState): List<Suggestion> {
    val name = app.activeAnchor
    if (name == "regular") return emptyList()
    val reg = app.anchors.getValue("regular").glyph
    if (points(reg) == 0) return emptyList()
    val m = app.metrics
    val upm = m.unitsPerEm
    val axis = Axis.ALL.firstOrNull { it.lo == name || it.hi == name } ?: return emptyList()
    val hi = axis.hi == name
    val stem = stemWidth(reg, m) ?: upm * 0.08f
    val out = mutableListOf<Suggestion>()
    fun s(label: String, detail: String, make: (GlyphCorner) -> GlyphCorner) =
        out.add(Suggestion(label, detail, replaceActive(app, label, make)))
    when (axis.tag) {
        "wght" -> {
            val d = round(stem * (if (hi) 0.45f else -0.3f))
            s(if (hi) "Embolden from Regular" else "Lighten from Regular", "stems ${stem.toInt()} → ${(stem + 2 * d).toInt()}u") { g ->
                corner(g, translateContours(offsetOutline(g.contours, d, d * 0.6f), d, 0f), g.width + 2 * d)
            }
        }
        "GRAD" -> {
            val d = round(stem * (if (hi) 0.15f else -0.12f))
            s(if (hi) "Raise grade from Regular" else "Lower grade from Regular", "same advance, stems ${if (d > 0) "+" else ""}${(2 * d).toInt()}u") { g ->
                corner(g, offsetOutline(g.contours, d, d * 0.5f))
            }
        }
        "wdth" -> {
            val k = if (hi) 1.2f else 0.8f
            // Scale horizontally, then give back the stem thickness the scale took (or added).
            val comp = stem * (1f - k) / 2f
            s(if (hi) "Extend from Regular" else "Condense from Regular", "${(k * 100).toInt()}% width, stems kept") { g ->
                val scaled = applyAffine(g.contours, scalingAbout(k, 1f, 0f, 0f))
                corner(g, offsetOutline(scaled, comp, 0f), round(g.width * k))
            }
        }
        "slnt" -> if (!hi) {
            val deg = 12f
            s("Italicize from Regular", "${deg.toInt()}° slant") { g ->
                val t = tan(deg * PI.toFloat() / 180f)
                corner(g, applyAffine(g.contours, listOf(1f, 0f, t, 1f, -t * m.xHeight / 2f, 0f)))
            }
        } else s("Upright from Regular", "copy, 0°") { g -> g.deepCopy() }
        "XOPQ" -> {
            val d = round(stem * (if (hi) 0.3f else -0.25f))
            s(if (hi) "Thicken verticals" else "Thin verticals", "x only, ${if (d > 0) "+" else ""}${(2 * d).toInt()}u") { g ->
                corner(g, translateContours(offsetOutline(g.contours, d, 0f), d, 0f), g.width + 2 * d)
            }
        }
        "YOPQ" -> {
            val d = round(stem * (if (hi) 0.2f else -0.15f))
            s(if (hi) "Thicken horizontals" else "Thin horizontals", "y only, ${if (d > 0) "+" else ""}${(2 * d).toInt()}u") { g ->
                corner(g, offsetOutline(g.contours, 0f, d))
            }
        }
        "YTLC" -> {
            val d = round(upm * (if (hi) 0.06f else -0.06f))
            s(if (hi) "Raise x-height" else "Lower x-height", "${if (d > 0) "+" else ""}${d.toInt()}u above mid") { g ->
                corner(g, mapContours(g.contours) { x, y -> x to (if (y > m.xHeight * 0.5f) y + d else y) })
            }
        }
        "YTUC", "YTAS", "YTFI" -> {
            val line = if (axis.tag == "YTUC" || axis.tag == "YTFI") m.capHeight else m.ascender
            val d = round(upm * (if (hi) 0.05f else -0.05f))
            s(if (hi) "Raise ${axis.label.lowercase()}" else "Lower ${axis.label.lowercase()}", "${if (d > 0) "+" else ""}${d.toInt()}u at the top") { g ->
                corner(g, mapContours(g.contours) { x, y -> x to (if (y > line * 0.6f) y + d else y) })
            }
        }
        "YTDE" -> {
            val d = round(upm * (if (hi) -0.05f else 0.05f))
            s(if (hi) "Deepen descender" else "Shorten descender", "${d.toInt()}u below baseline") { g ->
                corner(g, mapContours(g.contours) { x, y -> x to (if (y < 0f) y + d else y) })
            }
        }
    }
    return out
}

private fun cleanup(app: AppState): List<Suggestion> {
    val out = mutableListOf<Suggestion>()
    val scope = scopeFor(app)
    val glyphs = scope.map { app.anchors.getValue(it).glyph }
    val g = app.anchors.getValue(app.activeAnchor).glyph
    // Duplicates: a point on top of its predecessor -- in every anchor of the scope.
    val dupes = mutableSetOf<PointKey>()
    g.contours.forEachIndexed { ci, c ->
        if (c.points.size > 3) c.points.indices.forEach { pi ->
            val ok = glyphs.all { gg ->
                val pts = gg.contours[ci].points; val p = pts[pi]; val q = pts[(pi - 1 + pts.size) % pts.size]
                hypot(p.x - q.x, p.y - q.y) < 0.5f
            }
            if (ok) dupes.add(ci to pi)
        }
    }
    if (dupes.isNotEmpty()) out.add(Suggestion("Merge ${dupes.size} duplicate point${if (dupes.size > 1) "s" else ""}", scopeLabel(scope)) { a ->
        scope.forEach { n -> a.anchors.getValue(n).let { it.replaceWithHistory(withoutPoints(it.glyph, dupes)) } }
        a.setStatus("Merged ${dupes.size} duplicate point(s).")
    })
    // Redundant: points whose removal changes nothing (zero drift).
    val redundant = reductionOrder(glyphs).takeWhile { it.error < 0.5f }.map { it.key }.toSet() - dupes
    if (redundant.isNotEmpty()) out.add(Suggestion("Remove ${redundant.size} redundant point${if (redundant.size > 1) "s" else ""}", "no visible change") { a ->
        scope.forEach { n -> a.anchors.getValue(n).let { it.replaceWithHistory(withoutPoints(it.glyph, redundant)) } }
        a.setStatus("Removed ${redundant.size} point(s) that added no shape.")
    })
    // Direction: TrueType wants outer contours clockwise, counters counter-clockwise.
    val wrong = g.contours.indices.filter { ci ->
        val pts = g.contours[ci].points
        pts.size >= 3 && (signedArea(pts) < 0f) != isOuter(g.contours, ci)
    }
    if (wrong.isNotEmpty()) out.add(Suggestion("Fix direction of ${wrong.size} contour${if (wrong.size > 1) "s" else ""}", "outer clockwise") { a ->
        scope.forEach { n ->
            val st = a.anchors.getValue(n)
            val ng = st.glyph.deepCopy()
            for (ci in wrong) ng.contours[ci] = ContourData(ng.contours[ci].points.reversed().toMutableList())
            st.replaceWithHistory(ng)
        }
        a.setStatus("Reversed ${wrong.size} contour(s).")
    })
    return out
}

private fun scopeLabel(scope: List<String>) = if (scope.size > 1) "all anchors" else null

private fun alignment(app: AppState): List<Suggestion> {
    val out = mutableListOf<Suggestion>()
    val st = app.anchors.getValue(app.activeAnchor)
    val g = st.glyph
    val m = app.metrics
    val tol = max(4f, m.unitsPerEm * 0.012f)
    val lines = listOf("baseline" to 0f, "x-height" to m.xHeight, "cap height" to m.capHeight, "ascender" to m.ascender, "descender" to m.descender)
    for ((label, y) in lines) {
        val keys = mutableListOf<PointKey>()
        g.contours.forEachIndexed { ci, c -> c.points.forEachIndexed { pi, p -> if (p.onCurve && abs(p.y - y) in 0.01f..tol) keys.add(ci to pi) } }
        val worst = keys.maxOfOrNull { (ci, pi) -> abs(g.contours[ci].points[pi].y - y) } ?: 0f
        if (keys.isNotEmpty()) out.add(Suggestion("Snap ${keys.size} point${if (keys.size > 1) "s" else ""} to $label", "up to ${kotlin.math.ceil(worst).toInt()}u off") { a ->
            val ng = st.glyph.deepCopy()
            for ((ci, pi) in keys) ng.contours[ci].points[pi].y = y
            st.replaceWithHistory(ng)
            a.setStatus("Snapped ${keys.size} point(s) to $label.")
        })
    }
    val slack = max(1f, m.unitsPerEm * 0.006f)
    val minLen = m.unitsPerEm * 0.05f
    for (vertical in listOf(true, false)) {
        val pairs = mutableListOf<Pair<PointKey, PointKey>>()
        g.contours.forEachIndexed { ci, c ->
            val n = c.points.size
            c.points.forEachIndexed { pi, p ->
                val q = c.points[(pi + 1) % n]
                if (!p.onCurve || !q.onCurve) return@forEachIndexed
                val off = if (vertical) abs(p.x - q.x) else abs(p.y - q.y)
                val len = if (vertical) abs(p.y - q.y) else abs(p.x - q.x)
                if (off in 0.01f..slack && len > minLen) pairs.add((ci to pi) to (ci to (pi + 1) % n))
            }
        }
        if (pairs.isNotEmpty()) out.add(Suggestion("Straighten ${pairs.size} ${if (vertical) "near-vertical" else "near-horizontal"} line${if (pairs.size > 1) "s" else ""}") { a ->
            val ng = st.glyph.deepCopy()
            for ((k1, k2) in pairs) {
                val p = ng.contours[k1.first].points[k1.second]; val q = ng.contours[k2.first].points[k2.second]
                if (vertical) { val x = round((p.x + q.x) / 2f); p.x = x; q.x = x } else { val y = round((p.y + q.y) / 2f); p.y = y; q.y = y }
            }
            st.replaceWithHistory(ng)
            a.setStatus("Straightened ${pairs.size} line(s).")
        })
    }
    return out
}

private fun consistency(app: AppState): List<Suggestion> {
    val out = mutableListOf<Suggestion>()
    val st = app.anchors.getValue(app.activeAnchor)
    val g = st.glyph
    val m = app.metrics
    val b = boundsOfContours(g.contours) ?: return out
    // Stems that almost match: move each odd one's right edge to the median.
    val y = if (b.maxY >= m.xHeight * 0.6f && b.minY <= m.xHeight * 0.4f) m.xHeight * 0.5f else b.cy
    val runs = inkRuns(g.contours, y).filter { it.second - it.first > 1f }
    if (runs.size >= 2) {
        val widths = runs.map { it.second - it.first }
        val target = round(widths.sorted()[widths.size / 2])
        val off = runs.filter { abs((it.second - it.first) - target) >= 2f && (it.second - it.first) / target in 0.85f..1.15f }
        if (off.isNotEmpty()) out.add(Suggestion("Match ${off.size} stem${if (off.size > 1) "s" else ""} to ${target.toInt()}u", widths.joinToString(" / ") { it.toInt().toString() }) { a ->
            val ng = st.glyph.deepCopy()
            for ((l, r) in off) {
                val dx = target - (r - l)
                for (c in ng.contours) for (p in c.points) if (abs(p.x - r) <= 2f) p.x += dx
            }
            st.replaceWithHistory(ng)
            a.setStatus("Matched ${off.size} stem(s) to ${target.toInt()}u.")
        })
    }
    // Sidebearings.
    val lsb = b.minX; val rsb = g.width - b.maxX
    if (lsb >= 0f && rsb >= 0f && abs(lsb - rsb) > max(8f, m.unitsPerEm * 0.01f)) {
        val shift = round((rsb - lsb) / 2f)
        out.add(Suggestion("Centre on advance", "LSB ${lsb.toInt()} / RSB ${rsb.toInt()}") { a ->
            st.replaceWithHistory(corner(st.glyph, translateContours(st.glyph.contours, shift, 0f)))
            a.setStatus("Centred: both sidebearings ${(lsb + shift).toInt()}u.")
        })
    }
    return out
}

private fun interpolation(app: AppState): List<Suggestion> {
    val issue = app.compatibility() ?: return emptyList()
    val corners = app.cornersSnapshot()
    if (corners.values.sumOf { points(it) } == 0) return emptyList()
    val out = mutableListOf<Suggestion>()
    val src = app.activeAnchor
    val srcLabel = ANCHOR_LABELS[src] ?: src
    out.add(Suggestion("Copy $srcLabel to every anchor", "replaces the others") { a -> a.copyActiveToOthers() })
    val first = ANCHORS.firstOrNull { it != "regular" && compatibilityIssue(mapOf("regular" to corners["regular"], it to corners[it]), listOf("regular", it)) != null }
    if (first != null && first != src) out.add(Suggestion("Go to the mismatch", ANCHOR_LABELS[first]) { a ->
        Axis.ALL.firstOrNull { it.lo == first || it.hi == first }?.let { a.selectedAxis = it }
        a.activeAnchor = first
        a.setStatus(issue)
    })
    return out
}

/** Every suggestion that applies to the active anchor right now, most useful first. */
fun suggestionsFor(app: AppState): List<Suggestion> {
    if (app.currentGlyphName == null || app.activeGhost != null || app.reduction != null) return emptyList()
    val g = app.anchors.getValue(app.activeAnchor).glyph
    return interpolation(app) + generate(app) +
        (if (points(g) == 0) emptyList() else cleanup(app) + alignment(app) + consistency(app))
}
