package com.hereliesaz.morphont

import kotlin.math.max
import kotlin.math.min

/**
 * A from-scratch, read-only parser and evaluator for the subset of
 * OpenType/TrueType variable-font binary structure this app needs: given
 * ONE variable TTF (multiple "frames" -- weights, widths -- baked into a
 * single file via `fvar`/`gvar`, not five separate files), extract every
 * character's outline at five specific points in the font's own design
 * space, matching this app's five anchors.
 *
 * No compiled-font *writing* here -- see the README for why that's a
 * separate, deferred effort. And no CFF/OTF (PostScript outlines)
 * support -- only TrueType (`glyf`/`loca`/`gvar`) fonts, which is what
 * Azrienoch (and most variable fonts) actually ship as.
 *
 * Scope deliberately excludes:
 * - `avar` (segment remapping) -- rare, and the font this was built
 *   against doesn't have one; axis normalization here is the plain
 *   piecewise-linear default the spec falls back to without it.
 * - Per-anchor advance-width variation (`HVAR` / gvar phantom points) --
 *   this app cares about node *shape*, not spacing; every anchor gets the
 *   glyph's single default advance width. A real limitation, not an
 *   oversight -- documented in the README rather than silently wrong.
 * - Composite glyphs' own gvar deltas (which move a component's
 *   *offset*, e.g. shifting an accent) -- each component's shape is
 *   varied correctly via its own glyph's gvar data, but the static
 *   placement offset between components doesn't shift with weight/width.
 */

internal class Reader(val bytes: ByteArray) {
    fun u8(offset: Int): Int = bytes[offset].toInt() and 0xFF
    fun u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
    fun i16(offset: Int): Int {
        val v = u16(offset)
        return if (v >= 0x8000) v - 0x10000 else v
    }
    fun u32(offset: Int): Long =
        ((u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong())
    fun i8(offset: Int): Int {
        val v = u8(offset)
        return if (v >= 0x80) v - 0x100 else v
    }
    fun f2dot14(offset: Int): Float = i16(offset) / 16384f
    fun tag(offset: Int): String = buildString {
        for (i in 0 until 4) append(bytes[offset + i].toInt().toChar())
    }
}

internal data class TableEntry(val tag: String, val offset: Int, val length: Int)

data class AxisInfo(val tag: String, val min: Float, val default: Float, val max: Float)

/** Raw, unvaried (default) glyph data: flat point arrays plus contour boundaries, before grouping into contours or applying any gvar delta. */
private data class RawSimpleGlyph(val xs: IntArray, val ys: IntArray, val onCurve: BooleanArray, val endPtsOfContours: IntArray)

private data class CompositeComponent(val glyphId: Int, val dx: Float, val dy: Float)

private const val MAX_COMPOSITE_DEPTH = 1

private data class TupleVariation(
    val peak: FloatArray, // per-axis, or NaN if this axis unconstrained (peak==0)
    val intermediateStart: FloatArray?,
    val intermediateEnd: FloatArray?,
    val pointNumbers: IntArray?, // null = applies to ALL points (including phantoms)
    val deltaX: IntArray,
    val deltaY: IntArray,
)

class VariableFont private constructor(
    private val r: Reader,
    val unitsPerEm: Int,
    val axes: List<AxisInfo>,
    private val cmap: Map<Int, Int>,
    private val numGlyphs: Int,
    private val defaultAdvanceWidth: (Int) -> Float,
    private val glyfOffset: Int,
    private val glyphOffsetLength: (Int) -> Pair<Int, Int>,
    private val gvar: TableEntry?,
    private val gvarAxisCount: Int,
    private val sharedTuples: List<FloatArray>,
    private val glyphVariationDataOffsets: IntArray, // absolute file offsets, size numGlyphs+1; equal consecutive entries = no variation data
) {
    private val rawGlyphCache = HashMap<Int, RawSimpleGlyph?>() // null = this glyph id is composite (or empty)
    private val compositeCache = HashMap<Int, List<CompositeComponent>?>() // null = empty/unsupported
    private val tupleCache = HashMap<Int, List<TupleVariation>>()

    /** Every codepoint this font's cmap defines, mapped to its glyph id. */
    fun characterCodepoints(): List<Int> = cmap.keys.sorted()

    fun advanceWidth(codepoint: Int): Float? {
        val gid = cmap[codepoint] ?: return null
        return defaultAdvanceWidth(gid)
    }

    /**
     * The outline for [codepoint] at [axisCoords] (raw axis values, e.g.
     * `"wght" to 100f` -- not pre-normalized), or null if this font
     * doesn't define that character, or the outline couldn't be
     * resolved (nested/unsupported composite).
     */
    fun outlineAt(codepoint: Int, axisCoords: Map<String, Float>): List<ContourData>? {
        val gid = cmap[codepoint] ?: return null
        val normalized = axes.associate { it.tag to normalizeAxis(it, axisCoords[it.tag] ?: it.default) }
        return outlineForGlyph(gid, normalized, depth = 0)
    }

    private fun normalizeAxis(axis: AxisInfo, value: Float): Float {
        val clamped = value.coerceIn(axis.min, axis.max)
        return when {
            clamped < axis.default -> if (axis.default > axis.min) (clamped - axis.default) / (axis.default - axis.min) else 0f
            clamped > axis.default -> if (axis.max > axis.default) (clamped - axis.default) / (axis.max - axis.default) else 0f
            else -> 0f
        }
    }

    private fun outlineForGlyph(glyphId: Int, normalizedCoords: Map<String, Float>, depth: Int): List<ContourData>? {
        val raw = rawSimpleGlyph(glyphId)
        if (raw != null) {
            val varied = applyGvar(glyphId, raw, normalizedCoords)
            val contours = mutableListOf<ContourData>()
            var start = 0
            for (end in raw.endPtsOfContours) {
                val pts = mutableListOf<Pt>()
                for (pi in start..end) pts.add(Pt(varied.first[pi], varied.second[pi], raw.onCurve[pi]))
                contours.add(ContourData(pts))
                start = end + 1
            }
            return contours
        }
        val components = compositeComponents(glyphId) ?: return emptyList() // truly empty glyph (e.g. space)
        if (depth >= MAX_COMPOSITE_DEPTH) return null
        val merged = mutableListOf<ContourData>()
        for (comp in components) {
            val sub = outlineForGlyph(comp.glyphId, normalizedCoords, depth + 1) ?: return null
            for (c in sub) {
                merged.add(ContourData(c.points.map { Pt(it.x + comp.dx, it.y + comp.dy, it.onCurve, it.smooth) }.toMutableList()))
            }
        }
        return merged
    }

    private fun rawSimpleGlyph(glyphId: Int): RawSimpleGlyph? {
        if (rawGlyphCache.containsKey(glyphId)) return rawGlyphCache[glyphId]
        val (offset, length) = glyphOffsetLength(glyphId)
        if (length == 0) { rawGlyphCache[glyphId] = null; return null }
        val glyfPos = glyfOffset + offset
        val numContours = r.i16(glyfPos)
        if (numContours < 0) { rawGlyphCache[glyphId] = null; return null } // composite
        var p = glyfPos + 10
        val endPtsOfContours = IntArray(numContours) { i -> r.u16(p + i * 2) }
        p += numContours * 2
        val numPoints = if (numContours == 0) 0 else endPtsOfContours[numContours - 1] + 1
        val instructionLength = r.u16(p)
        p += 2 + instructionLength

        val flags = IntArray(numPoints)
        var i = 0
        while (i < numPoints) {
            val flag = r.u8(p); p += 1
            flags[i] = flag; i += 1
            if (flag and 0x08 != 0) {
                var repeat = r.u8(p); p += 1
                while (repeat > 0 && i < numPoints) { flags[i] = flag; i += 1; repeat -= 1 }
            }
        }
        val xs = IntArray(numPoints)
        var x = 0
        for (j in 0 until numPoints) {
            val flag = flags[j]
            val dx = when {
                flag and 0x02 != 0 -> { val v = r.u8(p); p += 1; if (flag and 0x10 != 0) v else -v }
                flag and 0x10 != 0 -> 0
                else -> { val v = r.i16(p); p += 2; v }
            }
            x += dx; xs[j] = x
        }
        val ys = IntArray(numPoints)
        var y = 0
        for (j in 0 until numPoints) {
            val flag = flags[j]
            val dy = when {
                flag and 0x04 != 0 -> { val v = r.u8(p); p += 1; if (flag and 0x20 != 0) v else -v }
                flag and 0x20 != 0 -> 0
                else -> { val v = r.i16(p); p += 2; v }
            }
            y += dy; ys[j] = y
        }
        val onCurve = BooleanArray(numPoints) { flags[it] and 0x01 != 0 }
        val result = RawSimpleGlyph(xs, ys, onCurve, endPtsOfContours)
        rawGlyphCache[glyphId] = result
        return result
    }

    private fun compositeComponents(glyphId: Int): List<CompositeComponent>? {
        if (compositeCache.containsKey(glyphId)) return compositeCache[glyphId]
        val (offset, length) = glyphOffsetLength(glyphId)
        if (length == 0) { compositeCache[glyphId] = null; return null }
        val glyfPos = glyfOffset + offset
        val numContours = r.i16(glyfPos)
        if (numContours >= 0) { compositeCache[glyphId] = null; return null } // simple glyph, not composite

        val components = mutableListOf<CompositeComponent>()
        var p = glyfPos + 10
        var more = true
        while (more) {
            val flags = r.u16(p)
            val componentGlyphId = r.u16(p + 2)
            p += 4
            val wordArgs = flags and 0x0001 != 0
            val argsAreXY = flags and 0x0002 != 0
            if (!argsAreXY) { compositeCache[glyphId] = null; return null } // point-matching composites unsupported
            var dx: Float; var dy: Float
            if (wordArgs) { dx = r.i16(p).toFloat(); dy = r.i16(p + 2).toFloat(); p += 4 }
            else { dx = r.i8(p).toFloat(); dy = r.i8(p + 1).toFloat(); p += 2 }
            if (flags and 0x0008 != 0) p += 2
            else if (flags and 0x0040 != 0) p += 4
            else if (flags and 0x0080 != 0) p += 8
            components.add(CompositeComponent(componentGlyphId, dx, dy))
            more = flags and 0x0020 != 0
        }
        compositeCache[glyphId] = components
        return components
    }

    /** Sums every applicable gvar tuple's contribution (with IUP-inferred deltas for untouched points) onto the default coordinates. */
    private fun applyGvar(glyphId: Int, raw: RawSimpleGlyph, normalizedCoords: Map<String, Float>): Pair<FloatArray, FloatArray> {
        val n = raw.xs.size
        val outX = FloatArray(n) { raw.xs[it].toFloat() }
        val outY = FloatArray(n) { raw.ys[it].toFloat() }
        val tuples = tuplesFor(glyphId, n + 4) // +4 phantom points, part of the packed delta stream even though we discard them
        if (tuples.isEmpty()) return outX to outY
        val userVec = axes.map { normalizedCoords[it.tag] ?: 0f }.toFloatArray()

        for (tv in tuples) {
            val scalar = tupleScalar(tv, userVec)
            if (scalar == 0f) continue

            // Expand this tuple's (possibly partial) point deltas to all n points via IUP.
            val dx = FloatArray(n)
            val dy = FloatArray(n)
            val touched = BooleanArray(n)
            if (tv.pointNumbers == null) {
                for (i in 0 until n) {
                    dx[i] = tv.deltaX.getOrElse(i) { 0 }.toFloat()
                    dy[i] = tv.deltaY.getOrElse(i) { 0 }.toFloat()
                    touched[i] = true
                }
            } else {
                for ((idx, pointIndex) in tv.pointNumbers.withIndex()) {
                    if (pointIndex >= n) continue // phantom point (advance width) -- ignored, see class doc
                    dx[pointIndex] = tv.deltaX.getOrElse(idx) { 0 }.toFloat()
                    dy[pointIndex] = tv.deltaY.getOrElse(idx) { 0 }.toFloat()
                    touched[pointIndex] = true
                }
                inferUntouchedDeltas(raw, touched, dx, dy)
            }
            for (i in 0 until n) {
                outX[i] += dx[i] * scalar
                outY[i] += dy[i] * scalar
            }
        }
        return outX to outY
    }

    /** The standard TrueType IUP (interpolate/extrapolate untouched points) algorithm, run separately per contour and per axis. */
    private fun inferUntouchedDeltas(raw: RawSimpleGlyph, touched: BooleanArray, dx: FloatArray, dy: FloatArray) {
        var start = 0
        for (end in raw.endPtsOfContours) {
            val indices = (start..end).toList()
            val touchedInContour = indices.filter { touched[it] }
            if (touchedInContour.isNotEmpty()) {
                if (touchedInContour.size == 1) {
                    val only = touchedInContour[0]
                    for (i in indices) if (!touched[i]) { dx[i] = dx[only]; dy[i] = dy[only] }
                } else {
                    iupAxis(indices, touchedInContour, raw.xs, dx)
                    iupAxis(indices, touchedInContour, raw.ys, dy)
                }
            }
            start = end + 1
        }
    }

    private fun iupAxis(contourIndices: List<Int>, touchedIndices: List<Int>, orig: IntArray, delta: FloatArray) {
        val m = contourIndices.size
        val posInContour = contourIndices.withIndex().associate { (k, idx) -> idx to k }
        val touchedSorted = touchedIndices.sortedBy { posInContour.getValue(it) }
        for (k in touchedSorted.indices) {
            val a = touchedSorted[k]
            val b = touchedSorted[(k + 1) % touchedSorted.size]
            val posA = posInContour.getValue(a)
            var posB = posInContour.getValue(b)
            if (posB <= posA) posB += m // wrap
            for (step in 1 until (posB - posA)) {
                val pos = (posA + step) % m
                val p = contourIndices[pos]
                if (p == a || p == b) continue
                val oA = orig[a].toFloat(); val oB = orig[b].toFloat(); val oP = orig[p].toFloat()
                delta[p] = when {
                    oA == oB -> delta[a]
                    oP <= min(oA, oB) -> if (oA < oB) delta[a] else delta[b]
                    oP >= max(oA, oB) -> if (oA < oB) delta[b] else delta[a]
                    else -> {
                        val t = (oP - oA) / (oB - oA)
                        delta[a] + t * (delta[b] - delta[a])
                    }
                }
            }
        }
    }

    private fun tupleScalar(tv: TupleVariation, userVec: FloatArray): Float {
        var scalar = 1f
        for (a in userVec.indices) {
            val peak = tv.peak[a]
            if (peak == 0f) continue
            val v = userVec[a]
            val (lo, hi) = if (tv.intermediateStart != null && tv.intermediateEnd != null) {
                tv.intermediateStart[a] to tv.intermediateEnd[a]
            } else {
                if (peak > 0f) 0f to peak else peak to 0f
            }
            val factor = when {
                v < lo || v > hi -> 0f
                v == peak -> 1f
                v < peak -> if (peak == lo) 1f else (v - lo) / (peak - lo)
                else -> if (peak == hi) 1f else (hi - v) / (hi - peak)
            }
            if (factor == 0f) return 0f
            scalar *= factor
        }
        return scalar
    }

    private fun tuplesFor(glyphId: Int, totalPointCountWithPhantoms: Int): List<TupleVariation> {
        tupleCache[glyphId]?.let { return it }
        val table = gvar
        if (table == null || glyphId + 1 >= glyphVariationDataOffsets.size) { tupleCache[glyphId] = emptyList(); return emptyList() }
        val startOffset = glyphVariationDataOffsets[glyphId]
        val endOffset = glyphVariationDataOffsets[glyphId + 1]
        if (endOffset <= startOffset) { tupleCache[glyphId] = emptyList(); return emptyList() }

        val blockStart = startOffset
        val header = r.u16(blockStart)
        val hasSharedPoints = header and 0x8000 != 0
        val tupleCount = header and 0x0FFF
        val dataOffset = blockStart + r.u16(blockStart + 2)

        data class HeaderInfo(val size: Int, val peak: FloatArray, val iStart: FloatArray?, val iEnd: FloatArray?, val hasPrivatePoints: Boolean)
        val headers = mutableListOf<HeaderInfo>()
        var hp = blockStart + 4
        for (t in 0 until tupleCount) {
            val variationDataSize = r.u16(hp)
            val tupleIndex = r.u16(hp + 2)
            hp += 4
            val embeddedPeak = tupleIndex and 0x8000 != 0
            val intermediate = tupleIndex and 0x4000 != 0
            val privatePoints = tupleIndex and 0x2000 != 0
            val sharedIndex = tupleIndex and 0x0FFF

            val peak: FloatArray
            if (embeddedPeak) {
                peak = FloatArray(gvarAxisCount) { r.f2dot14(hp + it * 2) }
                hp += gvarAxisCount * 2
            } else {
                peak = sharedTuples.getOrElse(sharedIndex) { FloatArray(gvarAxisCount) }
            }
            var iStart: FloatArray? = null
            var iEnd: FloatArray? = null
            if (intermediate) {
                iStart = FloatArray(gvarAxisCount) { r.f2dot14(hp + it * 2) }
                hp += gvarAxisCount * 2
                iEnd = FloatArray(gvarAxisCount) { r.f2dot14(hp + it * 2) }
                hp += gvarAxisCount * 2
            }
            headers.add(HeaderInfo(variationDataSize, peak, iStart, iEnd, privatePoints))
        }

        var cursor = dataOffset
        val sharedPoints: IntArray? = if (hasSharedPoints) {
            val (pts, newCursor) = readPackedPointNumbers(cursor)
            cursor = newCursor
            pts
        } else null

        val result = mutableListOf<TupleVariation>()
        for (hi in headers) {
            val points: IntArray?
            if (hi.hasPrivatePoints) {
                val (pts, newCursor) = readPackedPointNumbers(cursor)
                cursor = newCursor
                points = pts
            } else {
                points = sharedPoints
            }
            val count = points?.size ?: totalPointCountWithPhantoms // "all points" tuple
            val (dxArr, cursorAfterX) = readPackedDeltas(cursor, count)
            cursor = cursorAfterX
            val (dyArr, cursorAfterY) = readPackedDeltas(cursor, count)
            cursor = cursorAfterY
            result.add(TupleVariation(hi.peak, hi.iStart, hi.iEnd, points, dxArr, dyArr))
        }
        tupleCache[glyphId] = result
        return result
    }

    /** Packed point numbers: returns (indices, cursorAfter). Null means "all points" -- the caller resolves that against the glyph's own known total point count. */
    private fun readPackedPointNumbers(offset: Int): Pair<IntArray?, Int> {
        var p = offset
        val first = r.u8(p); p += 1
        if (first == 0) return null to p // all points
        val count = if (first and 0x80 != 0) {
            val second = r.u8(p); p += 1
            ((first and 0x7F) shl 8) or second
        } else first
        val points = IntArray(count)
        var i = 0
        var last = 0
        while (i < count) {
            val control = r.u8(p); p += 1
            val runLength = (control and 0x7F) + 1
            val wordDeltas = control and 0x80 != 0
            for (k in 0 until runLength) {
                if (i >= count) break
                val delta = if (wordDeltas) { val v = r.u16(p); p += 2; v } else { val v = r.u8(p); p += 1; v }
                last += delta
                points[i] = last
                i += 1
            }
        }
        return points to p
    }

    /** Packed deltas: reads exactly [count] run-length-encoded delta values. */
    private fun readPackedDeltas(offset: Int, count: Int): Pair<IntArray, Int> {
        var p = offset
        val target = count
        val out = IntArray(target)
        var i = 0
        while (i < target) {
            val control = r.u8(p); p += 1
            val runLength = (control and 0x3F) + 1
            when {
                control and 0x80 != 0 -> { for (k in 0 until runLength) { if (i >= target) break; out[i] = 0; i += 1 } }
                control and 0x40 != 0 -> { for (k in 0 until runLength) { if (i >= target) break; out[i] = r.i16(p); p += 2; i += 1 } }
                else -> { for (k in 0 until runLength) { if (i >= target) break; out[i] = r.i8(p); p += 1; i += 1 } }
            }
        }
        return out to p
    }

    companion object {
        fun parse(bytes: ByteArray): VariableFont {
            val r = Reader(bytes)
            val numTables = r.u16(4)
            val tables = LinkedHashMap<String, TableEntry>()
            for (i in 0 until numTables) {
                val rec = 12 + i * 16
                val tag = r.tag(rec)
                val offset = r.u32(rec + 8).toInt()
                val length = r.u32(rec + 12).toInt()
                tables[tag] = TableEntry(tag, offset, length)
            }

            val head = tables["head"] ?: error("Not a TrueType font: missing 'head' table.")
            val unitsPerEm = r.u16(head.offset + 18)
            val indexToLocFormat = r.i16(head.offset + 50)

            val maxp = tables["maxp"] ?: error("Missing 'maxp' table.")
            val numGlyphs = r.u16(maxp.offset + 4)

            val hhea = tables["hhea"] ?: error("Missing 'hhea' table.")
            val numHMetrics = r.u16(hhea.offset + 34)
            val hmtx = tables["hmtx"] ?: error("Missing 'hmtx' table.")
            val advanceWidth: (Int) -> Float = { gid ->
                val idx = if (gid < numHMetrics) gid else numHMetrics - 1
                r.u16(hmtx.offset + idx * 4).toFloat()
            }

            val loca = tables["loca"] ?: error("Missing 'loca' table (only TrueType-outline fonts are supported, not CFF/OTF).")
            val glyf = tables["glyf"] ?: error("Missing 'glyf' table (only TrueType-outline fonts are supported, not CFF/OTF).")
            val glyphOffsetLength: (Int) -> Pair<Int, Int> = { gid ->
                if (indexToLocFormat == 0) {
                    val o1 = r.u16(loca.offset + gid * 2) * 2
                    val o2 = r.u16(loca.offset + (gid + 1) * 2) * 2
                    o1 to (o2 - o1)
                } else {
                    val o1 = r.u32(loca.offset + gid * 4).toInt()
                    val o2 = r.u32(loca.offset + (gid + 1) * 4).toInt()
                    o1 to (o2 - o1)
                }
            }

            val cmapTable = tables["cmap"] ?: error("Missing 'cmap' table -- can't determine this font's character set.")
            val cmap = parseCmap(r, cmapTable.offset)

            val fvarTable = tables["fvar"] ?: error("Missing 'fvar' table -- this isn't a variable font.")
            val axesOffset = fvarTable.offset + r.u16(fvarTable.offset + 4)
            val axisCount = r.u16(fvarTable.offset + 8)
            val axisSize = r.u16(fvarTable.offset + 10)
            val axes = (0 until axisCount).map { i ->
                val ao = axesOffset + i * axisSize
                AxisInfo(
                    tag = r.tag(ao),
                    min = fixedToFloat(r.u32(ao + 4)),
                    default = fixedToFloat(r.u32(ao + 8)),
                    max = fixedToFloat(r.u32(ao + 12)),
                )
            }

            val gvarTable = tables["gvar"]
            var sharedTuples: List<FloatArray> = emptyList()
            var glyphVariationDataOffsets = IntArray(0)
            var gvarAxisCount = axisCount
            if (gvarTable != null) {
                val gvarAxisCountField = r.u16(gvarTable.offset + 4)
                gvarAxisCount = gvarAxisCountField
                val sharedTupleCount = r.u16(gvarTable.offset + 6)
                val sharedTuplesOffset = gvarTable.offset + r.u32(gvarTable.offset + 8).toInt()
                sharedTuples = (0 until sharedTupleCount).map { i ->
                    FloatArray(gvarAxisCount) { a -> r.f2dot14(sharedTuplesOffset + (i * gvarAxisCount + a) * 2) }
                }
                val glyphCount = r.u16(gvarTable.offset + 12)
                val flags = r.u16(gvarTable.offset + 14)
                val longOffsets = flags and 0x0001 != 0
                val dataArrayOffset = gvarTable.offset + r.u32(gvarTable.offset + 16).toInt()
                glyphVariationDataOffsets = IntArray(glyphCount + 1) { i ->
                    if (longOffsets) dataArrayOffset + r.u32(gvarTable.offset + 20 + i * 4).toInt()
                    else dataArrayOffset + r.u16(gvarTable.offset + 20 + i * 2) * 2
                }
            }

            return VariableFont(
                r = r,
                unitsPerEm = unitsPerEm,
                axes = axes,
                cmap = cmap,
                numGlyphs = numGlyphs,
                defaultAdvanceWidth = advanceWidth,
                glyfOffset = glyf.offset,
                glyphOffsetLength = glyphOffsetLength,
                gvar = gvarTable,
                gvarAxisCount = gvarAxisCount,
                sharedTuples = sharedTuples,
                glyphVariationDataOffsets = glyphVariationDataOffsets,
            )
        }

        private fun fixedToFloat(fixed: Long): Float = fixed.toInt() / 65536f
    }
}

/** Parses cmap subtables format 4 (BMP) and format 12 (full Unicode), preferring format 12 when both exist. */
private fun parseCmap(r: Reader, cmapOffset: Int): Map<Int, Int> {
    val numTables = r.u16(cmapOffset + 2)
    var bestOffset = -1
    var bestFormat = -1
    for (i in 0 until numTables) {
        val rec = cmapOffset + 4 + i * 8
        val platformId = r.u16(rec)
        val encodingId = r.u16(rec + 2)
        val offset = r.u32(rec + 4).toInt()
        val subtableOffset = cmapOffset + offset
        val format = r.u16(subtableOffset)
        val isUnicode = (platformId == 3 && (encodingId == 1 || encodingId == 10)) || platformId == 0
        if (!isUnicode) continue
        if (format == 12 && bestFormat != 12) { bestOffset = subtableOffset; bestFormat = 12 }
        else if (format == 4 && bestFormat == -1) { bestOffset = subtableOffset; bestFormat = 4 }
    }
    if (bestOffset < 0) return emptyMap()
    val result = LinkedHashMap<Int, Int>()
    when (bestFormat) {
        4 -> {
            val segCountX2 = r.u16(bestOffset + 6)
            val segCount = segCountX2 / 2
            val endCodesOffset = bestOffset + 14
            val startCodesOffset = endCodesOffset + segCountX2 + 2
            val idDeltaOffset = startCodesOffset + segCountX2
            val idRangeOffsetsOffset = idDeltaOffset + segCountX2
            for (seg in 0 until segCount) {
                val endCode = r.u16(endCodesOffset + seg * 2)
                val startCode = r.u16(startCodesOffset + seg * 2)
                val idDelta = r.i16(idDeltaOffset + seg * 2)
                val idRangeOffset = r.u16(idRangeOffsetsOffset + seg * 2)
                if (startCode == 0xFFFF && endCode == 0xFFFF) continue
                for (c in startCode..endCode) {
                    val glyphId = if (idRangeOffset == 0) {
                        (c + idDelta) and 0xFFFF
                    } else {
                        val glyphIndexAddr = idRangeOffsetsOffset + seg * 2 + idRangeOffset + (c - startCode) * 2
                        val g = r.u16(glyphIndexAddr)
                        if (g == 0) 0 else (g + idDelta) and 0xFFFF
                    }
                    if (glyphId != 0) result[c] = glyphId
                }
            }
        }
        12 -> {
            val numGroups = r.u32(bestOffset + 12).toInt()
            for (g in 0 until numGroups) {
                val rec = bestOffset + 16 + g * 12
                val startChar = r.u32(rec).toInt()
                val endChar = r.u32(rec + 4).toInt()
                val startGlyphId = r.u32(rec + 8).toInt()
                for (c in startChar..endChar) result[c] = startGlyphId + (c - startChar)
            }
        }
    }
    return result
}
