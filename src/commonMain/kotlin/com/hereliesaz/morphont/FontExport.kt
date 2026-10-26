package com.hereliesaz.morphont

import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun currentEpochSeconds(): Long = Clock.System.now().epochSeconds

/*
 * Compiled-font export: a project's glyphs become a TrueType, OpenType/CFF or
 * variable TrueType font, optionally wrapped as WOFF or WOFF2. The writers
 * are in Font*.kt; this file decides what goes into them -- which glyph is
 * which character, which instance a static font freezes, which axes a
 * variable font gets and how far each one reaches.
 */

/** What kind of outlines the font carries. */
enum class FontOutlines(val label: String, val detail: String) {
    TRUETYPE("TrueType", "Static .ttf, one instance of the font."),
    CFF("OpenType / CFF", "Static .otf, with Morphont's cubic curves as drawn."),
    VARIABLE("Variable TrueType", "One .ttf holding every axis you drew."),
}

/** What the font file is wrapped in. */
enum class FontContainer(val label: String, val detail: String) {
    PLAIN("Plain", "The font file itself."),
    WOFF("WOFF", "Compressed for the web."),
    WOFF2("WOFF2", "The web's current format. Packed without compression for now, so larger than it could be."),
}

/**
 * The real numbers behind an axis's 0..1 span: [min] at its low anchor, [default] at Regular, [max] at its
 * high anchor. Morphont draws no numbers of its own, so these decide what a viewer's `font-weight: 700` or
 * `font-stretch: 80%` asks of the font.
 */
data class AxisRange(val min: Float, val default: Float, val max: Float) {
    /** True when the low anchor is reachable: the axis runs below its default. */
    val reachesLow: Boolean get() = default > min
    val reachesHigh: Boolean get() = max > default

    /** Why this range can't be used, or null when it can. */
    val problem: String? get() = when {
        min > default || default > max -> "Needs minimum ≤ default ≤ maximum."
        min == max -> "Minimum and maximum can't be the same."
        else -> null
    }
}

/** Where each axis's range starts, until someone edits it: weight and width by the usual scales, the rest around Roboto Flex's. */
val DEFAULT_AXIS_RANGES: Map<String, AxisRange> = mapOf(
    Axis.WEIGHT.tag to AxisRange(100f, 400f, 900f),
    Axis.WIDTH.tag to AxisRange(75f, 100f, 125f),
    Axis.SERIF.tag to AxisRange(0f, 50f, 100f),
    Axis.GRADE.tag to AxisRange(-200f, 0f, 150f),
    Axis.SLANT.tag to AxisRange(-10f, -5f, 0f),
    Axis.OPTICAL_SIZE.tag to AxisRange(8f, 14f, 144f),
    Axis.COUNTER_WIDTH.tag to AxisRange(323f, 468f, 603f),
    Axis.STEM_THICKNESS_X.tag to AxisRange(27f, 96f, 175f),
    Axis.STEM_THICKNESS_Y.tag to AxisRange(25f, 79f, 135f),
    Axis.LOWERCASE_HEIGHT.tag to AxisRange(416f, 514f, 570f),
    Axis.UPPERCASE_HEIGHT.tag to AxisRange(528f, 712f, 760f),
    Axis.ASCENDER_HEIGHT.tag to AxisRange(649f, 750f, 854f),
    Axis.DESCENDER_DEPTH.tag to AxisRange(-305f, -203f, -98f),
    Axis.FIGURE_HEIGHT.tag to AxisRange(560f, 738f, 788f),
)

data class FontExportOptions(
    val family: String,
    val style: String = "Regular",
    val outlines: FontOutlines = FontOutlines.TRUETYPE,
    val container: FontContainer = FontContainer.PLAIN,
    /** A static font freezes each axis at this 0..1 position; an axis not listed sits at Regular (0.5). */
    val instance: Map<String, Float> = emptyMap(),
    /** A variable font's axes take their numbers from here. */
    val axisRanges: Map<String, AxisRange> = DEFAULT_AXIS_RANGES,
    val version: String = "1.000",
)

class FontExportResult(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
    val glyphCount: Int,
    /** Things that didn't go in as drawn: glyphs exported from Regular alone, characters claimed twice, and so on. */
    val warnings: List<String>,
)

// ---------------------------------------------------------------- naming

/** The one Unicode character a glyph name stands for: the character itself, or a `uniXXXX` / `uXXXXX` production name. */
internal fun codepointOfGlyphName(name: String): Int? {
    fun valid(cp: Int) = cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF && cp != 0xFFFE && cp != 0xFFFF
    if (name.length == 1) return name[0].code.takeIf { valid(it) }
    if (name.length == 2 && name[0].isHighSurrogate() && name[1].isLowSurrogate())
        return (((name[0].code - 0xD800) shl 10) or (name[1].code - 0xDC00)) + 0x10000
    val hex = Regex("^(?:uni|unicode|u)([0-9a-fA-F]{4,6})$").matchEntire(name)?.groupValues?.get(1) ?: return null
    return hex.toIntOrNull(16)?.takeIf { valid(it) }
}

private fun postScriptGlyphName(name: String, codepoint: Int?): String = when {
    name == ".notdef" -> ".notdef"
    codepoint != null && (codepoint in 'A'.code..'Z'.code || codepoint in 'a'.code..'z'.code) -> codepoint.toChar().toString()
    codepoint == 0x20 -> "space"
    codepoint != null && codepoint <= 0xFFFF -> "uni" + codepoint.toString(16).uppercase().padStart(4, '0')
    codepoint != null -> "u" + codepoint.toString(16).uppercase()
    else -> {
        val clean = name.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_') it else '_' }.joinToString("")
        (if (clean.isEmpty() || clean[0].isDigit() || (clean[0] == '.' && clean != ".notdef")) "g$clean" else clean).take(63)
    }
}

// ---------------------------------------------------------------- the pieces of a font under construction

private class Entry(
    val name: String,
    val codepoint: Int?,
    /** Null for the generated .notdef. */
    val glyph: Glyph?,
    /** False when its anchors don't line up point for point, so it can only be exported from Regular. */
    val compatible: Boolean,
    val scale: Float,
)

private fun scaled(v: Float, scale: Float) = round(v * scale).toInt()

private fun notdefContours(upm: Int, clockwise: Boolean): Pair<Int, List<FontContour>> {
    val w = upm / 2
    val l = upm / 20; val r = w - upm / 20; val b = 0; val t = upm * 7 / 10
    val inset = upm / 14
    fun box(x0: Int, y0: Int, x1: Int, y1: Int, cw: Boolean): FontContour {
        // Corners in clockwise order starting bottom left; reversed for counter-clockwise.
        val pts = if (cw) listOf(x0 to y0, x0 to y1, x1 to y1, x1 to y0) else listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        return FontContour(pts[0].first, pts[0].second, listOf(1, 2, 3, 0).map { FontSeg(1, 0, 0, 0, 0, pts[it].first, pts[it].second) })
    }
    return w to listOf(box(l, b, r, t, clockwise), box(l + inset, b + inset, r - inset, t - inset, !clockwise))
}

/** Where on the axis a font's axis tuple sits: its normalized coordinate (-1..1) and the region it covers. */
private class Stop(val t: Float, val normalized: Float, val start: Float, val end: Float)

// The samples between an axis's ends and its default. Morphont's interpolation bends between them (see
// axisInterp); a variable font's gvar is straight lines, so intermediate stops follow the bend closely.
private val LOW_STOPS: List<Stop> = listOf(
    Stop(0f, -1f, -1f, -0.5f),
    Stop(0.25f, -0.5f, -1f, 0f),
)
private val HIGH_STOPS: List<Stop> = listOf(
    Stop(0.75f, 0.5f, 0f, 1f),
    Stop(1f, 1f, 0.5f, 1f),
)

private fun cornersDiffer(a: GlyphCorner, b: GlyphCorner): Boolean {
    if (a.width != b.width || a.contours.size != b.contours.size) return true
    for (ci in a.contours.indices) {
        val pa = a.contours[ci].points; val pb = b.contours[ci].points
        if (pa.size != pb.size) return true
        for (i in pa.indices) if (pa[i].x != pb[i].x || pa[i].y != pb[i].y) return true
    }
    return false
}

/**
 * The axes a variable font needs: those with both extremes drawn on at least one glyph, and at least one
 * extreme actually different from Regular. (A font imported from one with fewer axes carries copies of Regular
 * on the axes it never had; they move nothing, so they are left out.)
 */
fun fontAxesInUse(project: Map<String, Glyph>): List<Axis> {
    val usable = project.values.filter { compatibilityIssue(effectiveCorners(it.corners)) == null }
    return Axis.ALL.filter { axis ->
        usable.any { g ->
            val drawn = g.corners[axis.lo]?.contours?.isNotEmpty() == true && g.corners[axis.hi]?.contours?.isNotEmpty() == true
            drawn && effectiveCorners(g.corners).let { eff ->
                val regular = eff.getValue("regular")
                cornersDiffer(eff.getValue(axis.lo), regular) || cornersDiffer(eff.getValue(axis.hi), regular)
            }
        }
    }
}

private fun weightClassOf(options: FontExportOptions): Int {
    val range = options.axisRanges[Axis.WEIGHT.tag] ?: DEFAULT_AXIS_RANGES.getValue(Axis.WEIGHT.tag)
    val t = if (options.outlines == FontOutlines.VARIABLE) 0.5f else options.instance[Axis.WEIGHT.tag] ?: 0.5f
    return axisValueAt(range, t).let { round(it).toInt() }.coerceIn(1, 1000)
}

private fun widthClassOf(options: FontExportOptions): Int {
    val range = options.axisRanges[Axis.WIDTH.tag] ?: DEFAULT_AXIS_RANGES.getValue(Axis.WIDTH.tag)
    val t = if (options.outlines == FontOutlines.VARIABLE) 0.5f else options.instance[Axis.WIDTH.tag] ?: 0.5f
    val percent = axisValueAt(range, t)
    val stops = listOf(50f, 62.5f, 75f, 87.5f, 100f, 112.5f, 125f, 150f, 200f)
    return stops.indices.minByOrNull { kotlin.math.abs(stops[it] - percent) }!! + 1
}

/** The number an axis reads at 0..1 position [t]: [AxisRange.min] at 0, its default at 0.5, its max at 1. */
internal fun axisValueAt(range: AxisRange, t: Float): Float =
    if (t <= 0.5f) range.min + (range.default - range.min) * (t / 0.5f)
    else range.default + (range.max - range.default) * ((t - 0.5f) / 0.5f)

private fun mimeOf(extension: String) = when (extension) {
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "font/ttf"
}

private fun fileStem(family: String, suffix: String): String =
    family.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").trim('-').ifEmpty { "font" } + "-" + suffix

// ---------------------------------------------------------------- export

/**
 * Builds the font file for [project]. Throws [IllegalArgumentException] with a sentence a person can act on
 * when there is nothing to export (no glyphs, a variable font with no drawn axis, a range that makes no sense).
 */
fun exportFont(project: Map<String, Glyph>, options: FontExportOptions, nowSeconds: Long = currentEpochSeconds()): FontExportResult {
    require(project.isNotEmpty()) { "The project has no glyphs to export." }
    require(options.family.isNotBlank()) { "Give the font a family name." }
    val warnings = ArrayList<String>()

    // ---- which glyph is which character
    val claimed = HashMap<Int, String>()
    val names = project.keys.sorted()
    val codepoints = HashMap<String, Int?>()
    // A glyph named by the character itself outranks a production name for the same character.
    for (n in names.sortedBy { if (codepointOfGlyphName(it) != null && it.length <= 2) 0 else 1 }) {
        val cp = if (n == ".notdef") null else codepointOfGlyphName(n)
        if (cp != null && claimed.containsKey(cp)) {
            warnings.add("\"$n\" and \"${claimed[cp]}\" are both U+${cp.toString(16).uppercase().padStart(4, '0')}; \"$n\" goes in without a character.")
            codepoints[n] = null
        } else {
            if (cp != null) claimed[cp] = n
            codepoints[n] = cp
        }
    }

    val withMetrics = project.values.mapNotNull { it.metrics }
    val upm = withMetrics.groupingBy { it.unitsPerEm }.eachCount().maxByOrNull { it.value }?.key?.toInt()?.takeIf { it in 16..16384 } ?: 1000
    val baseMetrics = withMetrics.firstOrNull { it.unitsPerEm.toInt() == upm } ?: FontMetrics(unitsPerEm = upm.toFloat())
    val ascender = scaled(baseMetrics.ascender, upm / baseMetrics.unitsPerEm)
    val descender = scaled(baseMetrics.descender, upm / baseMetrics.unitsPerEm)
    val capHeight = scaled(baseMetrics.capHeight, upm / baseMetrics.unitsPerEm)
    val xHeight = scaled(baseMetrics.xHeight, upm / baseMetrics.unitsPerEm)

    val sources = names.map { n ->
        val g = project.getValue(n)
        val cp = codepoints[n]
        Entry(n, cp, g, compatible = compatibilityIssue(effectiveCorners(g.corners)) == null, scale = upm / (g.metrics?.unitsPerEm ?: upm.toFloat()))
    }
    val explicitNotdef = sources.firstOrNull { it.name == ".notdef" }
    val others = sources.filter { it.name != ".notdef" }
    val ordered = listOf(explicitNotdef ?: Entry(".notdef", null, null, true, 1f)) +
        others.filter { it.codepoint != null }.sortedBy { it.codepoint } +
        others.filter { it.codepoint == null }.sortedBy { it.name }

    val used = HashSet<String>()
    val glyphNames = ordered.map { e ->
        val base = postScriptGlyphName(e.name, e.codepoint)
        var candidate = base; var k = 1
        while (!used.add(candidate)) candidate = "$base.${k++}"
        candidate
    }

    val fallback = ordered.filter { it.glyph != null && !it.compatible && it.glyph.corners.values.any { c -> c.contours.isNotEmpty() } }.map { it.name }
    if (fallback.isNotEmpty()) {
        warnings.add("${fallback.size} glyph(s) don't match point for point across their anchors, so they're exported from Regular alone: " +
            fallback.take(8).joinToString(", ") + if (fallback.size > 8) ", …" else "")
    }

    val instanceValues = Axis.ALL.associate { it.tag to (options.instance[it.tag]?.coerceIn(0f, 1f) ?: 0.5f) }
    val tolerance = upm / 2000f

    fun corner(e: Entry, values: Map<String, Float>): GlyphCorner {
        val g = e.glyph ?: return GlyphCorner()
        return if (e.compatible) interpolateGlyph(g.corners, values) else (g.corners["regular"] ?: GlyphCorner())
    }

    val timestamp = nowSeconds
    val tables = LinkedHashMap<String, ByteArray>()
    val isVariable = options.outlines == FontOutlines.VARIABLE
    val style = options.style.ifBlank { "Regular" }

    fun plan(planned: List<PlannedGlyph>, bounds: List<IntArray>) = FontPlan(
        glyphs = planned, unitsPerEm = upm, family = options.family.trim(), style = style, version = options.version,
        ascender = ascender, descender = descender, capHeight = capHeight, xHeight = xHeight,
        weightClass = weightClassOf(options), widthClass = widthClassOf(options), timestamp = timestamp, bounds = bounds,
    )

    val sfntVersion: Long
    when (options.outlines) {
        FontOutlines.CFF -> {
            sfntVersion = 0x4F54544FL // OTTO
            val planned = ordered.mapIndexed { i, e ->
                if (e.glyph == null) notdefContours(upm, clockwise = false).let { (w, c) -> PlannedGlyph(glyphNames[i], null, w, c) }
                else {
                    val c = corner(e, instanceValues)
                    PlannedGlyph(glyphNames[i], e.codepoint, scaled(c.width, e.scale).coerceIn(0, 0xFFFF), fontContoursOf(c, e.scale))
                }
            }
            val bounds = planned.map { controlBounds(it.contours) }
            val p = plan(planned, bounds)
            tables["CFF "] = cffTable(p)
            tables["maxp"] = maxpTableCff(p.numGlyphs)
            tables["post"] = postTable(p, withNames = false)
            tables["head"] = headTable(p, 0)
            addCommonTables(tables, p, standardNames(p), bounds)
        }
        FontOutlines.TRUETYPE -> {
            sfntVersion = 0x00010000L
            val points = ArrayList<List<TtPoints>>()
            val planned = ordered.mapIndexed { i, e ->
                if (e.glyph == null) {
                    val (w, c) = notdefContours(upm, clockwise = true)
                    points.add(c.map { fontContourToTrueType(it, quadCountsFor(it, tolerance)) })
                    PlannedGlyph(glyphNames[i], null, w, c)
                } else {
                    val c = corner(e, instanceValues)
                    val contours = fontContoursOf(c, e.scale)
                    points.add(contours.map { fontContourToTrueType(it, quadCountsFor(it, tolerance)) })
                    PlannedGlyph(glyphNames[i], e.codepoint, scaled(c.width, e.scale).coerceIn(0, 0xFFFF), contours)
                }
            }
            val bounds = points.map { pointBounds(it) }
            val p = plan(planned, bounds)
            val glyf = buildGlyphTables(points, bounds, overlap = false)
            tables["glyf"] = glyf.glyf
            tables["loca"] = glyf.loca
            tables["maxp"] = maxpTableTrueType(p.numGlyphs, glyf.maxPoints, glyf.maxContours)
            tables["post"] = postTable(p, withNames = true)
            tables["head"] = headTable(p, glyf.indexToLocFormat)
            tables["gasp"] = gaspTable()
            addCommonTables(tables, p, standardNames(p), bounds)
        }
        FontOutlines.VARIABLE -> {
            sfntVersion = 0x00010000L
            val axes = fontAxesInUse(project).filter { axis ->
                val range = options.axisRanges[axis.tag] ?: DEFAULT_AXIS_RANGES.getValue(axis.tag)
                require(range.problem == null) { "${axis.label}: ${range.problem}" }
                range.reachesLow || range.reachesHigh
            }
            require(axes.isNotEmpty()) {
                "No axis has both of its extremes drawn on any glyph, so there is nothing to vary. Export a static font instead."
            }
            val ranges = axes.map { options.axisRanges[it.tag] ?: DEFAULT_AXIS_RANGES.getValue(it.tag) }

            val points = ArrayList<List<TtPoints>>()
            val perGlyphTuples = ArrayList<List<GvarTuple>>()
            var variedMaxY = Int.MIN_VALUE; var variedMinY = Int.MAX_VALUE
            val planned = ordered.mapIndexed { i, e ->
                if (e.glyph == null) {
                    val (w, c) = notdefContours(upm, clockwise = true)
                    points.add(c.map { fontContourToTrueType(it, quadCountsFor(it, tolerance)) })
                    perGlyphTuples.add(emptyList())
                    return@mapIndexed PlannedGlyph(glyphNames[i], null, w, c)
                }
                val base = corner(e, emptyMap())
                val baseWidth = scaled(base.width, e.scale).coerceIn(0, 0xFFFF)
                val baseContours = fontContoursOf(base, e.scale)

                // Every stop of every axis, as drawn at that stop with the other axes at Regular.
                class Master(val axisIndex: Int, val stop: Stop, val contours: List<FontContour>, val width: Int)
                val masters = ArrayList<Master>()
                if (e.compatible) {
                    for ((ai, axis) in axes.withIndex()) {
                        val range = ranges[ai]
                        val stops = (if (range.reachesLow) LOW_STOPS else emptyList()) + (if (range.reachesHigh) HIGH_STOPS else emptyList())
                        for (stop in stops) {
                            val c = interpolateGlyph(e.glyph.corners, mapOf(axis.tag to stop.t))
                            masters.add(Master(ai, stop, fontContoursOf(c, e.scale), scaled(c.width, e.scale).coerceIn(0, 0xFFFF)))
                        }
                    }
                }
                val aligned = masters.filter { m -> m.contours.size == baseContours.size && m.contours.indices.all { sameShape(baseContours[it], m.contours[it]) } }
                if (aligned.size != masters.size) warnings.add("\"${e.name}\" has anchors whose segments differ; it is exported from Regular alone.")
                val usable = if (aligned.size == masters.size) aligned else emptyList()

                // One quadratic count per segment, the largest any master needs, so every master has the same points.
                val counts = baseContours.mapIndexed { ci, c ->
                    val own = quadCountsFor(c, tolerance)
                    for (m in usable) { val other = quadCountsFor(m.contours[ci], tolerance); for (k in own.indices) if (other[k] > own[k]) own[k] = other[k] }
                    own
                }
                val defaultPoints = baseContours.mapIndexed { ci, c -> fontContourToTrueType(c, counts[ci]) }
                points.add(defaultPoints)
                val total = defaultPoints.sumOf { it.size }

                val tuples = ArrayList<GvarTuple>()
                for (m in usable) {
                    val mp = m.contours.mapIndexed { ci, c -> fontContourToTrueType(c, counts[ci]) }
                    val dx = IntArray(total + 4); val dy = IntArray(total + 4)
                    var k = 0
                    for (ci in defaultPoints.indices) for (pi in 0 until defaultPoints[ci].size) {
                        dx[k] = mp[ci].xs[pi] - defaultPoints[ci].xs[pi]
                        dy[k] = mp[ci].ys[pi] - defaultPoints[ci].ys[pi]
                        variedMaxY = maxOf(variedMaxY, mp[ci].ys[pi]); variedMinY = minOf(variedMinY, mp[ci].ys[pi])
                        k++
                    }
                    dx[total + 1] = m.width - baseWidth // the right-hand phantom point carries the advance
                    if (dx.all { it == 0 } && dy.all { it == 0 }) continue
                    val peak = FloatArray(axes.size); peak[m.axisIndex] = m.stop.normalized
                    val start = FloatArray(axes.size); start[m.axisIndex] = m.stop.start
                    val end = FloatArray(axes.size); end[m.axisIndex] = m.stop.end
                    tuples.add(GvarTuple(peak, start, end, dx, dy))
                }
                perGlyphTuples.add(tuples)
                PlannedGlyph(glyphNames[i], e.codepoint, baseWidth, baseContours)
            }
            val bounds = points.map { pointBounds(it) }
            val p = plan(planned, bounds)

            // ---- names: ids 256 up are the axes, then the named instances
            val extraNames = ArrayList<NameRecord>()
            val fvarAxes = axes.mapIndexed { i, axis ->
                extraNames.add(NameRecord(256 + i, axis.label))
                FvarAxis(axis.tag, ranges[i].min, ranges[i].default, ranges[i].max, 256 + i)
            }
            val instances = ArrayList<FvarInstance>()
            val instanceNames = HashSet<String>()
            fun addInstance(label: String, coordinates: List<Float>) {
                if (!instanceNames.add(label)) return
                val id = 256 + axes.size + instances.size
                extraNames.add(NameRecord(id, label))
                instances.add(FvarInstance(id, coordinates))
            }
            addInstance(style, ranges.map { it.default })
            for ((i, axis) in axes.withIndex()) {
                if (ranges[i].reachesLow) addInstance(ANCHOR_LABELS.getValue(axis.lo), ranges.mapIndexed { j, r -> if (j == i) r.min else r.default })
                if (ranges[i].reachesHigh) addInstance(ANCHOR_LABELS.getValue(axis.hi), ranges.mapIndexed { j, r -> if (j == i) r.max else r.default })
            }

            val glyf = buildGlyphTables(points, bounds, overlap = true)
            tables["glyf"] = glyf.glyf
            tables["loca"] = glyf.loca
            tables["maxp"] = maxpTableTrueType(p.numGlyphs, glyf.maxPoints, glyf.maxContours)
            tables["post"] = postTable(p, withNames = true)
            tables["head"] = headTable(p, glyf.indexToLocFormat)
            tables["gasp"] = gaspTable()
            tables["fvar"] = fvarTable(fvarAxes, instances)
            tables["gvar"] = gvarTable(axes.size, perGlyphTuples)
            val widened = bounds.toMutableList()
            if (variedMaxY != Int.MIN_VALUE) widened.add(intArrayOf(0, variedMinY, 0, variedMaxY))
            addCommonTables(tables, p, standardNames(p, extraNames), widened)
        }
    }

    val sfnt = assembleSfnt(sfntVersion, tables)
    val glyphCount = ordered.size
    val suffix = if (isVariable) "VF" else style.filter { it.isLetterOrDigit() }.ifEmpty { "Regular" }
    val stem = fileStem(options.family, suffix)
    val extension = when (options.container) {
        FontContainer.PLAIN -> if (options.outlines == FontOutlines.CFF) "otf" else "ttf"
        FontContainer.WOFF -> "woff"
        FontContainer.WOFF2 -> "woff2"
    }
    val bytes = when (options.container) {
        FontContainer.PLAIN -> sfnt
        FontContainer.WOFF -> woffFromSfnt(sfnt)
        FontContainer.WOFF2 -> woff2FromSfnt(sfnt)
    }
    return FontExportResult(bytes, "$stem.$extension", mimeOf(extension), glyphCount, warnings)
}

/** The tables every flavour shares. [reach] is the list of bounds the OS/2 window metrics must cover. */
private fun addCommonTables(tables: MutableMap<String, ByteArray>, plan: FontPlan, names: List<NameRecord>, reach: List<IntArray>) {
    tables["hhea"] = hheaTable(plan)
    tables["hmtx"] = hmtxTable(plan)
    tables["cmap"] = cmapTable(plan)
    tables["name"] = nameTable(names)
    val filled = reach.filter { it[3] != 0 || it[1] != 0 }
    tables["OS/2"] = os2Table(plan, filled.maxOfOrNull { it[3] } ?: 0, filled.minOfOrNull { it[1] } ?: 0)
}
