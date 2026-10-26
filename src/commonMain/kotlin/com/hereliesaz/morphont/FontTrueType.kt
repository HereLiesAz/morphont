package com.hereliesaz.morphont

import kotlin.math.abs

/*
 * TrueType outline tables: glyf and loca for the shapes, and for a variable
 * font fvar (the axes) and gvar (how every point moves along each axis).
 */

internal class GlyphTables(
    val glyf: ByteArray,
    val loca: ByteArray,
    val indexToLocFormat: Int,
    val maxPoints: Int,
    val maxContours: Int,
)

/** The glyph header and point arrays for one glyph, or an empty array when it has no outline. */
private fun simpleGlyph(contours: List<TtPoints>, bounds: IntArray, overlap: Boolean): ByteArray {
    val nonEmpty = contours.filter { it.size > 0 }
    if (nonEmpty.isEmpty()) return ByteArray(0)
    val w = ByteWriter(64)
    w.i16(nonEmpty.size)
    w.i16(bounds[0]); w.i16(bounds[1]); w.i16(bounds[2]); w.i16(bounds[3])
    var end = -1
    for (c in nonEmpty) { end += c.size; w.u16(end) }
    w.u16(0) // no instructions

    val xs = ArrayList<Int>(); val ys = ArrayList<Int>(); val on = ArrayList<Boolean>()
    for (c in nonEmpty) for (i in 0 until c.size) { xs.add(c.xs[i]); ys.add(c.ys[i]); on.add(c.onCurve[i]) }
    val n = xs.size
    val flags = IntArray(n)
    val xBytes = ByteWriter(n * 2); val yBytes = ByteWriter(n * 2)
    var px = 0; var py = 0
    for (i in 0 until n) {
        var f = if (on[i]) 0x01 else 0
        val dx = xs[i] - px; val dy = ys[i] - py
        when {
            dx == 0 -> f = f or 0x10
            abs(dx) <= 255 -> { f = f or 0x02 or (if (dx > 0) 0x10 else 0); xBytes.u8(abs(dx)) }
            else -> xBytes.i16(dx)
        }
        when {
            dy == 0 -> f = f or 0x20
            abs(dy) <= 255 -> { f = f or 0x04 or (if (dy > 0) 0x20 else 0); yBytes.u8(abs(dy)) }
            else -> yBytes.i16(dy)
        }
        if (i == 0 && overlap) f = f or 0x40
        flags[i] = f
        px = xs[i]; py = ys[i]
    }
    var i = 0
    while (i < n) {
        var run = 1
        while (i + run < n && flags[i + run] == flags[i] && run < 256) run++
        if (run > 1) { w.u8(flags[i] or 0x08); w.u8(run - 1) } else w.u8(flags[i])
        i += run
    }
    w.writer(xBytes); w.writer(yBytes)
    w.align(4)
    return w.toByteArray()
}

/**
 * `glyf` and `loca` for [glyphs] (a point list per contour, one list per glyph, in glyph order). [overlap] marks
 * glyphs whose contours may overlap, which variable fonts built from separate strokes usually do.
 */
internal fun buildGlyphTables(glyphs: List<List<TtPoints>>, bounds: List<IntArray>, overlap: Boolean): GlyphTables {
    val data = ByteWriter(4096)
    val offsets = IntArray(glyphs.size + 1)
    var maxPoints = 0; var maxContours = 0
    for ((i, contours) in glyphs.withIndex()) {
        offsets[i] = data.size
        data.bytes(simpleGlyph(contours, bounds[i], overlap))
        maxPoints = maxOf(maxPoints, contours.sumOf { it.size })
        maxContours = maxOf(maxContours, contours.count { it.size > 0 })
    }
    offsets[glyphs.size] = data.size
    val short = data.size / 2 <= 0xFFFF
    val loca = ByteWriter(offsets.size * 4)
    for (o in offsets) if (short) loca.u16(o / 2) else loca.u32(o.toLong())
    return GlyphTables(data.toByteArray(), loca.toByteArray(), if (short) 0 else 1, maxPoints, maxContours)
}

// ---------------------------------------------------------------- variations

/** One variable axis as written to fvar: [nameId] points into the name table. */
internal class FvarAxis(val tag: String, val min: Float, val default: Float, val max: Float, val nameId: Int)

/** A named stop along the axes, for fvar: [coordinates] in axis order, in the font's own units. */
internal class FvarInstance(val nameId: Int, val coordinates: List<Float>)

internal fun fvarTable(axes: List<FvarAxis>, instances: List<FvarInstance>): ByteArray {
    val instanceSize = 4 + axes.size * 4
    return ByteWriter(16 + axes.size * 20 + instances.size * instanceSize).apply {
        u16(1); u16(0)
        u16(16) // axes start right after this header
        u16(2) // reserved
        u16(axes.size); u16(20)
        u16(instances.size); u16(instanceSize)
        for (a in axes) {
            tag(a.tag)
            fixed(a.min); fixed(a.default); fixed(a.max)
            u16(0)
            u16(a.nameId)
        }
        for (inst in instances) {
            u16(inst.nameId); u16(0)
            for (c in inst.coordinates) fixed(c)
        }
    }.toByteArray()
}

/**
 * One tuple of a glyph's variation: how far every point (and the four phantom points that carry the advance)
 * moves when the axes sit at [peak]. [start] and [end] bound the tuple's region when it is an intermediate one.
 */
internal class GvarTuple(
    val peak: FloatArray,
    val start: FloatArray?,
    val end: FloatArray?,
    val dx: IntArray,
    val dy: IntArray,
)

/** Packs a list of deltas the way gvar wants them: runs of zeros, of bytes and of words. */
private fun packDeltas(w: ByteWriter, values: IntArray) {
    var i = 0
    val n = values.size
    fun fitsByte(v: Int) = v in -128..127
    while (i < n) {
        val v = values[i]
        when {
            v == 0 -> {
                var run = 1
                while (i + run < n && values[i + run] == 0 && run < 64) run++
                w.u8(0x80 or (run - 1)); i += run
            }
            fitsByte(v) -> {
                var run = 1
                while (i + run < n && fitsByte(values[i + run]) && run < 64 &&
                    !(values[i + run] == 0 && i + run + 1 < n && values[i + run + 1] == 0)) run++
                w.u8(run - 1)
                for (k in 0 until run) w.u8(values[i + k] and 0xFF)
                i += run
            }
            else -> {
                var run = 1
                while (i + run < n && !fitsByte(values[i + run]) && run < 64) run++
                w.u8(0x40 or (run - 1))
                for (k in 0 until run) w.i16(values[i + k])
                i += run
            }
        }
    }
}

private fun glyphVariationData(tuples: List<GvarTuple>, axisCount: Int): ByteArray {
    if (tuples.isEmpty()) return ByteArray(0)
    val serialized = tuples.map { t -> ByteWriter(64).also { packDeltas(it, t.dx); packDeltas(it, t.dy) }.toByteArray() }
    val headerSize = tuples.sumOf { 4 + axisCount * 2 * (if (it.start != null) 3 else 1) }
    val w = ByteWriter(64)
    w.u16(0x8000 or tuples.size) // the tuples share one set of point numbers, written below
    w.u16(4 + headerSize)
    for ((i, t) in tuples.withIndex()) {
        w.u16(serialized[i].size)
        w.u16(0x8000 or (if (t.start != null) 0x4000 else 0)) // embedded peak; no private point numbers
        for (v in t.peak) w.f2dot14(v)
        if (t.start != null && t.end != null) {
            for (v in t.start) w.f2dot14(v)
            for (v in t.end) w.f2dot14(v)
        }
    }
    w.u8(0) // shared point numbers: a count of zero means every point, phantoms included
    for (s in serialized) w.bytes(s)
    w.align(2)
    return w.toByteArray()
}

/** `gvar`: [perGlyph] holds each glyph's tuples in glyph order (empty for a glyph that does not vary). */
internal fun gvarTable(axisCount: Int, perGlyph: List<List<GvarTuple>>): ByteArray {
    val blocks = perGlyph.map { glyphVariationData(it, axisCount) }
    val offsetsSize = (blocks.size + 1) * 4
    val dataStart = 20 + offsetsSize
    val w = ByteWriter(dataStart + blocks.sumOf { it.size })
    w.u16(1); w.u16(0)
    w.u16(axisCount)
    w.u16(0) // no shared tuples
    w.u32(dataStart.toLong()) // where the shared tuples would start
    w.u16(blocks.size)
    w.u16(1) // long offsets
    w.u32(dataStart.toLong())
    var offset = 0L
    w.u32(offset)
    for (b in blocks) { offset += b.size; w.u32(offset) }
    for (b in blocks) w.bytes(b)
    return w.toByteArray()
}
