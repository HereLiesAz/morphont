package com.hereliesaz.morphont

import kotlin.math.max
import kotlin.math.min

/**
 * A from-scratch, read-only parser and evaluator for the subset of
 * OpenType/TrueType outline tables this app needs. Static fonts have
 * one default outline; variable fonts sample supported axes from their own
 * `fvar`/`gvar` design space. Every imported anchor uses the same fixed
 * `ANCHORS` layout from `Model.kt`. Bounds are checked against the font,
 * its containing table and each glyph/variation block.
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
    /**
     * Checked byte ranges for all untrusted TTF offsets and lengths.
     * Glyph parsing also supplies its own narrower endExclusive bound.
     */
    fun check(offset: Int, count: Int, endExclusive: Int = bytes.size) {
        require(offset >= 0 && count >= 0 && endExclusive in 0..bytes.size &&
            offset.toLong() + count <= endExclusive.toLong()) {
            "Truncated TrueType: need ${count} bytes at ${offset}, limit ${endExclusive} (file size ${bytes.size})"
        }
    }
    fun u8(offset: Int): Int { check(offset, 1); return bytes[offset].toInt() and 0xFF }
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
    fun tag(offset: Int): String { check(offset, 4); return buildString {
        for (i in 0 until 4) append(u8(offset + i).toChar())
    } }
}

internal data class TableEntry(val tag: String, val offset: Int, val length: Int)

data class AxisInfo(val tag: String, val min: Float, val default: Float, val max: Float)

/** Raw, unvaried (default) glyph data: flat point arrays plus contour boundaries, before grouping into contours or applying any gvar delta. */
private data class RawSimpleGlyph(val xs: IntArray, val ys: IntArray, val onCurve: BooleanArray, val endPtsOfContours: IntArray)

/** [xx]/[xy]/[yx]/[yy] are the 2x2 transform (identity by default): x' = xx*x + yx*y + dx, y' = xy*x + yy*y + dy. */
private data class CompositeComponent(
    val glyphId: Int, val dx: Float, val dy: Float,
    val xx: Float = 1f, val xy: Float = 0f, val yx: Float = 0f, val yy: Float = 1f,
)

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
    /** This font's own vertical metrics (`hhea` ascender/descender, `OS/2` x/cap height when present). */
    val metrics: FontMetrics,
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
                // glyf outlines are quadratic; convert exactly to Morphont's cubic node model.
                quadraticToCubic(ContourData(pts)).takeIf { it.points.isNotEmpty() }?.let { contours.add(it) }
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
                merged.add(ContourData(c.points.map { Pt(comp.xx * it.x + comp.yx * it.y + comp.dx, comp.xy * it.x + comp.yy * it.y + comp.dy, it.onCurve, it.smooth) }.toMutableList()))
            }
        }
        return merged
    }

    private fun rawSimpleGlyph(glyphId: Int): RawSimpleGlyph? {
        if (rawGlyphCache.containsKey(glyphId)) return rawGlyphCache[glyphId]
        val (offset, length) = glyphOffsetLength(glyphId)
        if (length == 0) { rawGlyphCache[glyphId] = null; return null }
        val glyfPos = glyfOffset + offset
        val glyphEnd = glyfPos + length
        fun check(at: Int, size: Int) {
            require(at >= glyfPos) { "Glyph ${glyphId}: offset before glyph start" }
            r.check(at, size, glyphEnd)
        }
        check(glyfPos, 10)
        val numContours = r.i16(glyfPos)
        if (numContours < 0) { rawGlyphCache[glyphId] = null; return null } // composite
        var p = glyfPos + 10
        check(p, numContours * 2 + 2)
        val endPtsOfContours = IntArray(numContours) { i -> r.u16(p + i * 2) }
        require(endPtsOfContours.indices.all { i -> i == 0 || endPtsOfContours[i] > endPtsOfContours[i - 1] }) {
            "Glyph ${glyphId}: contour endpoints are not increasing"
        }
        p += numContours * 2
        val numPoints = if (numContours == 0) 0 else endPtsOfContours[numContours - 1] + 1
        val instructionLength = r.u16(p)
        p += 2
        check(p, instructionLength)
        p += instructionLength

        val flags = IntArray(numPoints)
        var i = 0
        while (i < numPoints) {
            check(p, 1)
            val flag = r.u8(p); p += 1
            flags[i] = flag; i += 1
            if (flag and 0x08 != 0) {
                check(p, 1)
                val count = r.u8(p); p += 1
                require(i + count <= numPoints) { "Glyph ${glyphId}: flag repeat exceeds point count" }
                repeat(count) { flags[i++] = flag }
            }
        }
        val xs = IntArray(numPoints)
        var x = 0
        for (j in 0 until numPoints) {
            val flag = flags[j]
            val dx = when {
                flag and 0x02 != 0 -> { check(p, 1); val v = r.u8(p); p += 1; if (flag and 0x10 != 0) v else -v }
                flag and 0x10 != 0 -> 0
                else -> { check(p, 2); val v = r.i16(p); p += 2; v }
            }
            x += dx; xs[j] = x
        }
        val ys = IntArray(numPoints)
        var y = 0
        for (j in 0 until numPoints) {
            val flag = flags[j]
            val dy = when {
                flag and 0x04 != 0 -> { check(p, 1); val v = r.u8(p); p += 1; if (flag and 0x20 != 0) v else -v }
                flag and 0x20 != 0 -> 0
                else -> { check(p, 2); val v = r.i16(p); p += 2; v }
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
        val glyphEnd = glyfPos + length
        fun check(at: Int, count: Int) = r.check(at, count, glyphEnd)
        check(glyfPos, 10)
        val numContours = r.i16(glyfPos)
        if (numContours >= 0) { compositeCache[glyphId] = null; return null } // simple glyph, not composite

        val components = mutableListOf<CompositeComponent>()
        var p = glyfPos + 10
        var more = true
        while (more) {
            check(p, 4)
            val flags = r.u16(p)
            val componentGlyphId = r.u16(p + 2)
            p += 4
            val wordArgs = flags and 0x0001 != 0
            val argsAreXY = flags and 0x0002 != 0
            if (!argsAreXY) { compositeCache[glyphId] = null; return null } // point-matching composites unsupported
            var dx: Float; var dy: Float
            if (wordArgs) { check(p, 4); dx = r.i16(p).toFloat(); dy = r.i16(p + 2).toFloat(); p += 4 }
            else { check(p, 2); dx = r.i8(p).toFloat(); dy = r.i8(p + 1).toFloat(); p += 2 }
            val transformSize = when {
                flags and 0x0008 != 0 -> 2
                flags and 0x0040 != 0 -> 4
                flags and 0x0080 != 0 -> 8
                else -> 0
            }
            check(p, transformSize)
            fun f2dot14(at: Int) = r.i16(at) / 16384f
            var xx = 1f; var xy = 0f; var yx = 0f; var yy = 1f
            when (transformSize) {
                2 -> { xx = f2dot14(p); yy = xx }
                4 -> { xx = f2dot14(p); yy = f2dot14(p + 2) }
                8 -> { xx = f2dot14(p); xy = f2dot14(p + 2); yx = f2dot14(p + 4); yy = f2dot14(p + 6) }
            }
            p += transformSize
            // Unless SCALED_COMPONENT_OFFSET (0x0800) is set (UNSCALED, 0x1000, is the default), the offset is not transformed.
            if (flags and 0x0800 != 0 && flags and 0x1000 == 0) {
                val sx = xx * dx + yx * dy; val sy = xy * dx + yy * dy
                dx = sx; dy = sy
            }
            components.add(CompositeComponent(componentGlyphId, dx, dy, xx, xy, yx, yy))
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
                    oA == oB -> if (delta[a] == delta[b]) delta[a] else 0f // equal reference coordinates: spec/fontTools give 0 unless the deltas agree
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
        r.check(blockStart, 4, endOffset)
        val header = r.u16(blockStart)
        val hasSharedPoints = header and 0x8000 != 0
        val tupleCount = header and 0x0FFF
        val dataOffset = blockStart + r.u16(blockStart + 2)
        require(dataOffset in blockStart + 4..endOffset) { "Invalid gvar data offset for glyph ${glyphId}" }

        data class HeaderInfo(val size: Int, val peak: FloatArray, val iStart: FloatArray?, val iEnd: FloatArray?, val hasPrivatePoints: Boolean)
        val headers = mutableListOf<HeaderInfo>()
        var hp = blockStart + 4
        for (t in 0 until tupleCount) {
            r.check(hp, 4, dataOffset)
            val variationDataSize = r.u16(hp)
            val tupleIndex = r.u16(hp + 2)
            hp += 4
            val embeddedPeak = tupleIndex and 0x8000 != 0
            val intermediate = tupleIndex and 0x4000 != 0
            val privatePoints = tupleIndex and 0x2000 != 0
            val sharedIndex = tupleIndex and 0x0FFF

            val peak: FloatArray
            if (embeddedPeak) {
                r.check(hp, gvarAxisCount * 2, dataOffset)
                peak = FloatArray(gvarAxisCount) { r.f2dot14(hp + it * 2) }
                hp += gvarAxisCount * 2
            } else {
                peak = sharedTuples.getOrElse(sharedIndex) { FloatArray(gvarAxisCount) }
            }
            var iStart: FloatArray? = null
            var iEnd: FloatArray? = null
            if (intermediate) {
                r.check(hp, gvarAxisCount * 2, dataOffset)
                iStart = FloatArray(gvarAxisCount) { r.f2dot14(hp + it * 2) }
                hp += gvarAxisCount * 2
                r.check(hp, gvarAxisCount * 2, dataOffset)
                iEnd = FloatArray(gvarAxisCount) { r.f2dot14(hp + it * 2) }
                hp += gvarAxisCount * 2
            }
            headers.add(HeaderInfo(variationDataSize, peak, iStart, iEnd, privatePoints))
        }

        var cursor = dataOffset
        val sharedPoints: IntArray? = if (hasSharedPoints) {
            val (pts, newCursor) = readPackedPointNumbers(cursor, endOffset)
            cursor = newCursor
            pts
        } else null

        val result = mutableListOf<TupleVariation>()
        require(hp <= dataOffset) { "Overlapping gvar header for glyph ${glyphId}" }
        for (hi in headers) {
            // Each variationDataSize bounds one tuple, including its private
            // points. Never let its runs consume subsequent tuples.
            val tupleEndLong = cursor.toLong() + hi.size
            require(tupleEndLong <= endOffset.toLong()) {
                "Truncated gvar tuple for glyph ${glyphId}"
            }
            val tupleEnd = tupleEndLong.toInt()
            var pos = cursor
            val points = if (hi.hasPrivatePoints) {
                val (pts, after) = readPackedPointNumbers(pos, tupleEnd)
                pos = after
                pts
            } else sharedPoints
            val count = points?.size ?: totalPointCountWithPhantoms
            val (dxArr, afterX) = readPackedDeltas(pos, count, tupleEnd)
            val (dyArr, afterY) = readPackedDeltas(afterX, count, tupleEnd)
            require(afterY <= tupleEnd) { "Truncated gvar deltas for glyph ${glyphId}" }
            cursor = tupleEnd
            result.add(TupleVariation(hi.peak, hi.iStart, hi.iEnd, points, dxArr, dyArr))
        }
        tupleCache[glyphId] = result
        return result
    }

    /** Packed point numbers: returns (indices, cursorAfter). Null means "all points" -- the caller resolves that against the glyph's own known total point count. */
    private fun readPackedPointNumbers(offset: Int, limit: Int): Pair<IntArray?, Int> {
        var p = offset
        r.check(p, 1, limit)
        val first = r.u8(p); p += 1
        if (first == 0) return null to p // all points
        val count = if (first and 0x80 != 0) {
            r.check(p, 1, limit)
            val second = r.u8(p); p += 1
            ((first and 0x7F) shl 8) or second
        } else first
        val points = IntArray(count)
        var i = 0
        var last = 0
        while (i < count) {
            r.check(p, 1, limit)
            val control = r.u8(p); p += 1
            val runLength = (control and 0x7F) + 1
            val wordDeltas = control and 0x80 != 0
            for (k in 0 until runLength) {
                if (i >= count) break
                val delta = if (wordDeltas) {
                    r.check(p, 2, limit); val v = r.u16(p); p += 2; v
                } else {
                    r.check(p, 1, limit); val v = r.u8(p); p += 1; v
                }
                last += delta
                points[i] = last
                i += 1
            }
        }
        return points to p
    }

    /** Packed deltas: reads exactly [count] run-length-encoded delta values. */
    private fun readPackedDeltas(offset: Int, count: Int, limit: Int): Pair<IntArray, Int> {
        var p = offset
        val target = count
        val out = IntArray(target)
        var i = 0
        while (i < target) {
            r.check(p, 1, limit)
            val control = r.u8(p); p += 1
            val runLength = (control and 0x3F) + 1
            when {
                control and 0x80 != 0 -> { for (k in 0 until runLength) { if (i >= target) break; out[i] = 0; i += 1 } }
                control and 0x40 != 0 -> { for (k in 0 until runLength) { if (i >= target) break; r.check(p, 2, limit); out[i] = r.i16(p); p += 2; i += 1 } }
                else -> { for (k in 0 until runLength) { if (i >= target) break; r.check(p, 1, limit); out[i] = r.i8(p); p += 1; i += 1 } }
            }
        }
        return out to p
    }

    companion object {
        /**
         * Parses a TrueType font. [requireVariable] false also accepts a
         * static font (no `fvar`), which then simply has no axes -- enough
         * for reference ghosts and for family imports with identical anchors.
         */
        fun parse(bytes: ByteArray, requireVariable: Boolean = true): VariableFont {
            val r = Reader(bytes)
            r.check(0, 12)
            require(r.u32(0) == 0x00010000L || r.u32(0) == 0x74727565L) {
                "Only TrueType-outline (.ttf) fonts are supported"
            }
            val numTables = r.u16(4)
            r.check(12, numTables * 16)
            val tables = LinkedHashMap<String, TableEntry>()
            for (i in 0 until numTables) {
                val rec = 12 + i * 16
                val tag = r.tag(rec)
                val offsetLong = r.u32(rec + 8)
                val lengthLong = r.u32(rec + 12)
                require(offsetLong <= bytes.size.toLong() &&
                    lengthLong <= bytes.size.toLong() - offsetLong) {
                    "Invalid TrueType '$tag' table bounds"
                }
                tables[tag] = TableEntry(tag, offsetLong.toInt(), lengthLong.toInt())
            }
            fun table(tag: String, minimum: Int): TableEntry {
                val entry = tables[tag] ?: error("Missing '$tag' TrueType table")
                require(entry.length >= minimum) { "Truncated '$tag' table" }
                return entry
            }

            val head = table("head", 54)
            val unitsPerEm = r.u16(head.offset + 18)
            val indexToLocFormat = r.i16(head.offset + 50)

            val maxp = table("maxp", 6)
            val numGlyphs = r.u16(maxp.offset + 4)
            require(numGlyphs > 0) { "TrueType font contains no glyphs" }

            val hhea = table("hhea", 36)
            val numHMetrics = r.u16(hhea.offset + 34)
            val hmtx = table("hmtx", 4)
            require(numHMetrics in 1..numGlyphs && hmtx.length >= numHMetrics * 4) {
                "Invalid horizontal metrics in TrueType font"
            }
            val advanceWidth: (Int) -> Float = { gid ->
                val idx = if (gid < numHMetrics) gid else numHMetrics - 1
                r.u16(hmtx.offset + idx * 4).toFloat()
            }

            val loca = table("loca", 2)
            val glyf = table("glyf", 0)
            require(indexToLocFormat == 0 || indexToLocFormat == 1) {
                "Unsupported TrueType loca format: $indexToLocFormat"
            }
            val locaEntrySize = if (indexToLocFormat == 0) 2 else 4
            require(loca.length >= (numGlyphs + 1) * locaEntrySize) {
                "Truncated TrueType loca offsets"
            }
            val glyphOffsetLength: (Int) -> Pair<Int, Int> = { gid ->
                require(gid in 0 until numGlyphs) { "Invalid TrueType glyph ID: $gid" }
                val o1: Long
                val o2: Long
                if (indexToLocFormat == 0) {
                    o1 = (r.u16(loca.offset + gid * 2) * 2).toLong()
                    o2 = (r.u16(loca.offset + (gid + 1) * 2) * 2).toLong()
                } else {
                    o1 = r.u32(loca.offset + gid * 4)
                    o2 = r.u32(loca.offset + (gid + 1) * 4)
                }
                require(o2 >= o1 && o2 <= glyf.length.toLong()) {
                    "Invalid loca offsets for glyph $gid: $o1..$o2 (glyf size ${glyf.length})"
                }
                o1.toInt() to (o2 - o1).toInt()
            }

            val cmapTable = table("cmap", 4)
            val cmap = parseCmap(r, cmapTable.offset, cmapTable.length, numGlyphs)
            require(cmap.isNotEmpty()) { "No usable Unicode glyphs in TrueType cmap" }

            val fvarTable = tables["fvar"]
            if (fvarTable == null && requireVariable) error("Missing 'fvar' table -- this isn't a variable font.")
            if (fvarTable != null) r.check(fvarTable.offset, 16, fvarTable.offset + fvarTable.length)
            val axesOffset = if (fvarTable == null) 0 else fvarTable.offset + r.u16(fvarTable.offset + 4)
            val axisCount = if (fvarTable == null) 0 else r.u16(fvarTable.offset + 8)
            val axisSize = if (fvarTable == null) 0 else r.u16(fvarTable.offset + 10)
            if (fvarTable != null) {
                require(axisSize >= 20 && axesOffset.toLong() + axisCount.toLong() * axisSize <=
                    fvarTable.offset.toLong() + fvarTable.length) { "Invalid fvar axes" }
            }

            val ascender = r.i16(hhea.offset + 4).toFloat()
            val descender = r.i16(hhea.offset + 6).toFloat()
            val os2 = tables["OS/2"]
            val os2Version = if (os2 != null && os2.length >= 2) r.u16(os2.offset) else 0
            // sxHeight/sCapHeight exist from OS/2 version 2 on, at offsets 86/88.
            val xHeight = if (os2 != null && os2Version >= 2 && os2.length >= 90) r.i16(os2.offset + 86).toFloat() else unitsPerEm * 0.5f
            val capHeight = if (os2 != null && os2Version >= 2 && os2.length >= 90) r.i16(os2.offset + 88).toFloat() else unitsPerEm * 0.7f
            val metrics = FontMetrics(unitsPerEm.toFloat(), ascender, capHeight, xHeight, descender)

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
                r.check(gvarTable.offset, 20, gvarTable.offset + gvarTable.length)
                val gvarAxisCountField = r.u16(gvarTable.offset + 4)
                gvarAxisCount = gvarAxisCountField
                require(gvarAxisCount == axisCount) { "gvar/fvar axis count mismatch" }
                val sharedTupleCount = r.u16(gvarTable.offset + 6)
                val sharedTuplesRelative = r.u32(gvarTable.offset + 8)
                require(sharedTuplesRelative + sharedTupleCount.toLong() * gvarAxisCount * 2 <= gvarTable.length) {
                    "Invalid shared gvar tuples"
                }
                val sharedTuplesOffset = gvarTable.offset + sharedTuplesRelative.toInt()
                sharedTuples = (0 until sharedTupleCount).map { i ->
                    FloatArray(gvarAxisCount) { a -> r.f2dot14(sharedTuplesOffset + (i * gvarAxisCount + a) * 2) }
                }
                val glyphCount = r.u16(gvarTable.offset + 12)
                require(glyphCount == numGlyphs) { "gvar/maxp glyph count mismatch" }
                val flags = r.u16(gvarTable.offset + 14)
                val longOffsets = flags and 0x0001 != 0
                val entrySize = if (longOffsets) 4 else 2
                r.check(gvarTable.offset + 20, (glyphCount + 1) * entrySize, gvarTable.offset + gvarTable.length)
                val dataRelative = r.u32(gvarTable.offset + 16)
                require(dataRelative <= gvarTable.length) { "Invalid gvar data start" }
                val dataArrayOffset = gvarTable.offset + dataRelative.toInt()
                glyphVariationDataOffsets = IntArray(glyphCount + 1) { i ->
                    val delta = if (longOffsets) r.u32(gvarTable.offset + 20 + i * 4)
                    else r.u16(gvarTable.offset + 20 + i * 2).toLong() * 2
                    require(delta <= gvarTable.length.toLong() - dataRelative) {
                        "Invalid gvar glyph variation offset at $i"
                    }
                    dataArrayOffset + delta.toInt()
                }
                require(glyphVariationDataOffsets.indices.all { i -> i == 0 || glyphVariationDataOffsets[i] >= glyphVariationDataOffsets[i - 1] }) {
                    "gvar glyph offsets are not sorted"
                }
            }

            return VariableFont(
                r = r,
                unitsPerEm = unitsPerEm,
                metrics = metrics,
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

/**
 * Parse a checked Unicode cmap (format 4 or 12). Subtable offsets are relative
 * to the cmap table, not the file; reject cross-table reads and ignore glyph
 * IDs that cannot occur in maxp. Bad glyph offsets are never guessed.
 */
private fun parseCmap(r: Reader, cmapOffset: Int, cmapLength: Int, numGlyphs: Int): Map<Int, Int> {
    val tableEnd = cmapOffset + cmapLength
    fun check(at: Int, count: Int) = r.check(at, count, tableEnd)
    check(cmapOffset, 4)
    val numTables = r.u16(cmapOffset + 2)
    check(cmapOffset + 4, numTables * 8)
    var bestOffset = -1
    var bestEnd = -1
    var bestFormat = -1
    for (i in 0 until numTables) {
        val rec = cmapOffset + 4 + i * 8
        val platform = r.u16(rec)
        val encoding = r.u16(rec + 2)
        if (!((platform == 3 && encoding in listOf(1, 10)) || platform == 0)) continue
        val relative = r.u32(rec + 4)
        if (relative > cmapLength.toLong() - 2) continue
        val sub = cmapOffset + relative.toInt()
        val format = r.u16(sub)
        if (format != 4 && format != 12) continue
        val minimum = if (format == 4) 16 else 16
        if (sub.toLong() + minimum > tableEnd) continue
        val length = if (format == 4) r.u16(sub + 2).toLong() else r.u32(sub + 4)
        if (length < minimum || sub.toLong() + length > tableEnd) continue
        if (format == 12 && bestFormat != 12 || format == 4 && bestFormat == -1) {
            bestOffset = sub
            bestEnd = (sub.toLong() + length).toInt()
            bestFormat = format
        }
    }
    require(bestOffset >= 0) { "TrueType cmap contains no supported Unicode subtable" }
    fun inSub(at: Int, count: Int) = r.check(at, count, bestEnd)
    val result = LinkedHashMap<Int, Int>()
    when (bestFormat) {
        4 -> {
            inSub(bestOffset, 16)
            val segCountX2 = r.u16(bestOffset + 6)
            require(segCountX2 > 0 && segCountX2 % 2 == 0) { "Invalid cmap format 4 segments" }
            val segCount = segCountX2 / 2
            val endCodes = bestOffset + 14
            val starts = endCodes + segCountX2 + 2
            val deltas = starts + segCountX2
            val offsets = deltas + segCountX2
            inSub(offsets, segCountX2)
            for (seg in 0 until segCount) {
                val end = r.u16(endCodes + seg * 2)
                val start = r.u16(starts + seg * 2)
                if (start == 0xFFFF || end == 0xFFFF) continue
                require(start <= end) { "Invalid cmap segment range" }
                val delta = r.i16(deltas + seg * 2)
                val rangeOffset = r.u16(offsets + seg * 2)
                for (c in start..end) {
                    val gid = if (rangeOffset == 0) (c + delta) and 0xFFFF else {
                        val addr = offsets.toLong() + seg * 2L + rangeOffset + (c - start) * 2L
                        require(addr <= bestEnd.toLong() - 2) { "Invalid cmap glyph offset" }
                        val raw = r.u16(addr.toInt())
                        if (raw == 0) 0 else (raw + delta) and 0xFFFF
                    }
                    if (gid in 1 until numGlyphs) result[c] = gid
                }
            }
        }
        12 -> {
            inSub(bestOffset, 16)
            val groups = r.u32(bestOffset + 12)
            require(groups <= (bestEnd - bestOffset - 16L) / 12) { "Truncated cmap format 12 groups" }
            for (g in 0 until groups.toInt()) {
                val rec = bestOffset + 16 + g * 12
                val start = r.u32(rec)
                val end = r.u32(rec + 4)
                val gidStart = r.u32(rec + 8)
                require(start <= end && end <= 0x10FFFFL) { "Invalid cmap Unicode range" }
                if (gidStart >= numGlyphs) continue
                // No need to materialize characters mapped beyond maxp.
                val length = minOf(end - start, numGlyphs.toLong() - 1 - gidStart)
                for (d in 0..length) {
                    val gid = (gidStart + d).toInt()
                    if (gid != 0) result[(start + d).toInt()] = gid
                }
            }
        }
    }
    return result
}
