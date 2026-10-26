package com.hereliesaz.morphont

import kotlin.math.max
import kotlin.math.min

/*
 * The sfnt tables every Morphont font carries, whatever its outlines: head,
 * hhea, maxp, hmtx, OS/2, name, cmap, post and gasp, plus the container that
 * ties tables together. Outline tables are in FontTrueType.kt and FontCff.kt.
 */

/** A glyph ready to go into a font: its place, its advance, and its default-instance outline. */
internal class PlannedGlyph(
    val name: String,
    /** The one Unicode character it stands for; null for a glyph reached only by name. */
    val codepoint: Int?,
    val width: Int,
    val contours: List<FontContour>,
)

/** Everything the shared tables need to know about the font, fixed before any table is written. */
internal class FontPlan(
    val glyphs: List<PlannedGlyph>,
    val unitsPerEm: Int,
    val family: String,
    val style: String,
    val version: String,
    val ascender: Int,
    val descender: Int,
    val capHeight: Int,
    val xHeight: Int,
    val weightClass: Int,
    val widthClass: Int,
    /** Seconds since 1970-01-01 UTC. */
    val timestamp: Long,
    /** Each glyph's bounds (xMin, yMin, xMax, yMax) as the glyph table will carry them; empty glyphs are all zero. */
    val bounds: List<IntArray>,
) {
    val numGlyphs: Int get() = glyphs.size

    val fontBounds: IntArray by lazy {
        var xMin = Int.MAX_VALUE; var yMin = Int.MAX_VALUE; var xMax = Int.MIN_VALUE; var yMax = Int.MIN_VALUE
        for ((i, g) in glyphs.withIndex()) {
            if (g.contours.isEmpty()) continue
            val b = bounds[i]
            xMin = min(xMin, b[0]); yMin = min(yMin, b[1]); xMax = max(xMax, b[2]); yMax = max(yMax, b[3])
        }
        if (xMin == Int.MAX_VALUE) intArrayOf(0, 0, 0, 0) else intArrayOf(xMin, yMin, xMax, yMax)
    }

    /** PostScript name: no spaces, at most 63 characters. */
    val postScriptName: String get() {
        fun ascii(s: String) = s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        return (ascii(family).ifEmpty { "Font" } + "-" + ascii(style).ifEmpty { "Regular" }).take(63)
    }

    val fullName: String get() = if (style.equals("Regular", ignoreCase = true)) family else "$family $style"
}

/** Bounds of the control points of [contours]: what the glyph table records for the glyph. Zeros when there is nothing. */
internal fun controlBounds(contours: List<FontContour>): IntArray {
    var xMin = Int.MAX_VALUE; var yMin = Int.MAX_VALUE; var xMax = Int.MIN_VALUE; var yMax = Int.MIN_VALUE
    fun see(x: Int, y: Int) { xMin = min(xMin, x); yMin = min(yMin, y); xMax = max(xMax, x); yMax = max(yMax, y) }
    for (c in contours) {
        see(c.startX, c.startY)
        for (s in c.segs) {
            see(s.x, s.y)
            if (s.order >= 2) see(s.c1x, s.c1y)
            if (s.order >= 3) see(s.c2x, s.c2y)
        }
    }
    return if (xMin == Int.MAX_VALUE) intArrayOf(0, 0, 0, 0) else intArrayOf(xMin, yMin, xMax, yMax)
}

/** Bounds of a TrueType point list. Zeros when there is nothing. */
internal fun pointBounds(contours: List<TtPoints>): IntArray {
    var xMin = Int.MAX_VALUE; var yMin = Int.MAX_VALUE; var xMax = Int.MIN_VALUE; var yMax = Int.MIN_VALUE
    for (c in contours) for (i in 0 until c.size) {
        xMin = min(xMin, c.xs[i]); yMin = min(yMin, c.ys[i]); xMax = max(xMax, c.xs[i]); yMax = max(yMax, c.ys[i])
    }
    return if (xMin == Int.MAX_VALUE) intArrayOf(0, 0, 0, 0) else intArrayOf(xMin, yMin, xMax, yMax)
}

private const val MAC_EPOCH_OFFSET = 2082844800L // seconds from 1904-01-01 to 1970-01-01

/** `head`. [checkSumAdjustment] is written as zero here and filled in once every table is in place. */
internal fun headTable(plan: FontPlan, indexToLocFormat: Int): ByteArray = ByteWriter(54).apply {
    val bold = plan.style.contains("bold", ignoreCase = true)
    val italic = plan.style.contains("italic", ignoreCase = true) || plan.style.contains("oblique", ignoreCase = true)
    u32(0x00010000L)
    fixed(plan.version.removePrefix("Version ").trim().toFloatOrNull() ?: 1f)
    u32(0) // checkSumAdjustment
    u32(0x5F0F3CF5L)
    u16(0x0003) // baseline at y = 0; left side bearing point at x = 0
    u16(plan.unitsPerEm)
    val stamp = plan.timestamp + MAC_EPOCH_OFFSET
    u32(stamp ushr 32); u32(stamp and 0xFFFFFFFFL) // created
    u32(stamp ushr 32); u32(stamp and 0xFFFFFFFFL) // modified
    val b = plan.fontBounds
    i16(b[0]); i16(b[1]); i16(b[2]); i16(b[3])
    u16((if (bold) 1 else 0) or (if (italic) 2 else 0))
    u16(8)
    i16(2)
    i16(indexToLocFormat)
    i16(0)
}.toByteArray()

/** `hhea`: every glyph has a full horizontal metric, so there is no run of shared advances. */
internal fun hheaTable(plan: FontPlan): ByteArray = ByteWriter(36).apply {
    var advanceMax = 0; var minLsb = Int.MAX_VALUE; var minRsb = Int.MAX_VALUE; var extentMax = Int.MIN_VALUE
    for ((i, g) in plan.glyphs.withIndex()) {
        advanceMax = max(advanceMax, g.width)
        if (g.contours.isEmpty()) continue
        val b = plan.bounds[i]
        minLsb = min(minLsb, b[0])
        minRsb = min(minRsb, g.width - b[2])
        extentMax = max(extentMax, b[2])
    }
    if (minLsb == Int.MAX_VALUE) { minLsb = 0; minRsb = 0; extentMax = 0 }
    u32(0x00010000L)
    i16(plan.ascender); i16(plan.descender); i16(0)
    u16(advanceMax)
    i16(minLsb); i16(minRsb); i16(extentMax)
    i16(1); i16(0); i16(0) // caret slope rise/run, offset
    for (k in 0 until 4) i16(0)
    i16(0)
    u16(plan.numGlyphs)
}.toByteArray()

/** `hmtx`: an advance and a left side bearing (the glyph's own xMin) for every glyph. */
internal fun hmtxTable(plan: FontPlan): ByteArray = ByteWriter(plan.numGlyphs * 4).apply {
    for ((i, g) in plan.glyphs.withIndex()) {
        u16(g.width.coerceIn(0, 0xFFFF))
        i16(if (g.contours.isEmpty()) 0 else plan.bounds[i][0])
    }
}.toByteArray()

/** `maxp` for glyf outlines (version 1.0), given the largest point and contour counts found. */
internal fun maxpTableTrueType(numGlyphs: Int, maxPoints: Int, maxContours: Int): ByteArray = ByteWriter(32).apply {
    u32(0x00010000L)
    u16(numGlyphs)
    u16(maxPoints); u16(maxContours)
    u16(0); u16(0) // composites
    u16(1) // maxZones: twilight zone unused
    for (k in 0 until 8) u16(0) // twilight points, storage, function and instruction defs, stack, instructions, component elements/depth
}.toByteArray()

/** `maxp` for CFF outlines (version 0.5). */
internal fun maxpTableCff(numGlyphs: Int): ByteArray = ByteWriter(6).apply {
    u32(0x00005000L)
    u16(numGlyphs)
}.toByteArray()

/** `gasp`: grayscale smoothing at every size, for outlines that carry no hinting. */
internal fun gaspTable(): ByteArray = ByteWriter(8).apply {
    u16(1); u16(1)
    u16(0xFFFF); u16(0x000F)
}.toByteArray()

private val UNICODE_RANGE_BITS = listOf(
    // bit, first, last
    Triple(0, 0x0000, 0x007F), Triple(1, 0x0080, 0x00FF), Triple(2, 0x0100, 0x017F), Triple(3, 0x0180, 0x024F),
    Triple(4, 0x0250, 0x02AF), Triple(5, 0x02B0, 0x02FF), Triple(6, 0x0300, 0x036F), Triple(7, 0x0370, 0x03FF),
    Triple(9, 0x0400, 0x04FF), Triple(10, 0x0530, 0x058F), Triple(11, 0x0590, 0x05FF), Triple(13, 0x0600, 0x06FF),
    Triple(15, 0x0900, 0x097F), Triple(24, 0x10A0, 0x10FF), Triple(31, 0x2000, 0x206F), Triple(32, 0x2070, 0x209F),
    Triple(33, 0x20A0, 0x20CF), Triple(35, 0x2100, 0x214F), Triple(36, 0x2150, 0x218F), Triple(37, 0x2190, 0x21FF),
    Triple(38, 0x2200, 0x22FF), Triple(39, 0x2300, 0x23FF), Triple(43, 0x2500, 0x257F), Triple(45, 0x25A0, 0x25FF),
    Triple(46, 0x2600, 0x26FF), Triple(49, 0x3040, 0x309F), Triple(50, 0x30A0, 0x30FF), Triple(59, 0x4E00, 0x9FFF),
    Triple(62, 0xFB00, 0xFB4F), Triple(67, 0xFE20, 0xFE2F), Triple(69, 0xFE50, 0xFE6F), Triple(70, 0xFE70, 0xFEFF),
    Triple(71, 0xFF00, 0xFFEF), Triple(72, 0xFFF0, 0xFFFF),
)

/** `OS/2` version 4. */
internal fun os2Table(plan: FontPlan, maxYMax: Int, minYMin: Int): ByteArray = ByteWriter(96).apply {
    val coded = plan.glyphs.mapNotNull { it.codepoint }
    val bmp = coded.filter { it <= 0xFFFF }
    val range = LongArray(4)
    for (cp in coded) {
        if (cp > 0xFFFF) { range[1] = range[1] or (1L shl (57 - 32)); continue }
        for ((bit, lo, hi) in UNICODE_RANGE_BITS) if (cp in lo..hi) { range[bit / 32] = range[bit / 32] or (1L shl (bit % 32)) }
    }
    val widths = plan.glyphs.filter { it.width > 0 }.map { it.width }
    val averageWidth = if (widths.isEmpty()) 0 else (widths.sum() / widths.size)
    val bold = plan.style.contains("bold", ignoreCase = true)
    val italic = plan.style.contains("italic", ignoreCase = true) || plan.style.contains("oblique", ignoreCase = true)
    val regular = !bold && !italic
    val u = plan.unitsPerEm
    u16(4)
    i16(averageWidth)
    u16(plan.weightClass); u16(plan.widthClass)
    u16(0) // fsType: installable embedding
    i16(u * 650 / 1000); i16(u * 600 / 1000); i16(0); i16(u * 75 / 1000) // subscript size, offset
    i16(u * 650 / 1000); i16(u * 600 / 1000); i16(0); i16(u * 350 / 1000) // superscript size, offset
    i16(u * 50 / 1000); i16(u * 250 / 1000) // strikeout size, position
    i16(0) // family class
    for (k in 0 until 10) u8(0) // panose
    for (r in range) u32(r)
    tag("NONE")
    u16((if (italic) 0x01 else 0) or (if (bold) 0x20 else 0) or (if (regular) 0x40 else 0) or 0x80) // USE_TYPO_METRICS
    u16(bmp.minOrNull() ?: 0); u16(bmp.maxOrNull() ?: 0)
    i16(plan.ascender); i16(plan.descender); i16(0)
    u16(max(plan.ascender, maxYMax).coerceAtLeast(0)); u16(max(-plan.descender, -minYMin).coerceAtLeast(0))
    u32(1L); u32(0L) // code page: Latin 1
    i16(plan.xHeight); i16(plan.capHeight)
    u16(0) // default char: .notdef
    u16(0x20) // break char: space
    u16(1) // max context
}.toByteArray()

/** `post`, with glyph names in the table (format 2) or left to the outline table (format 3, for CFF). */
internal fun postTable(plan: FontPlan, withNames: Boolean): ByteArray = ByteWriter(64).apply {
    u32(if (withNames) 0x00020000L else 0x00030000L)
    i32(0) // italic angle
    i16(-plan.unitsPerEm / 10); i16(plan.unitsPerEm / 20) // underline position, thickness
    u32(0); u32(0); u32(0); u32(0); u32(0) // fixed pitch, memory use
    if (withNames) {
        u16(plan.numGlyphs)
        val names = ArrayList<String>()
        for ((i, g) in plan.glyphs.withIndex()) {
            if (i == 0) { u16(0); continue } // .notdef is Macintosh standard glyph 0
            names.add(g.name)
            u16(257 + names.size)
        }
        for (n in names) { u8(n.length); for (c in n) u8(c.code) }
    }
}.toByteArray()

/** One `name` record: a name ID and its text. All are written for the Windows platform, Unicode BMP, US English. */
internal class NameRecord(val id: Int, val text: String)

internal fun nameTable(records: List<NameRecord>): ByteArray {
    val sorted = records.sortedBy { it.id }
    val strings = ByteWriter(256)
    val offsets = ArrayList<Pair<Int, Int>>()
    for (r in sorted) {
        val start = strings.size
        for (c in r.text) strings.u16(c.code)
        offsets.add(start to (strings.size - start))
    }
    return ByteWriter(64).apply {
        u16(0); u16(sorted.size); u16(6 + 12 * sorted.size)
        for ((i, r) in sorted.withIndex()) {
            u16(3); u16(1); u16(0x0409); u16(r.id)
            u16(offsets[i].second); u16(offsets[i].first)
        }
        writer(strings)
    }.toByteArray()
}

/** The standard name records every font carries. [extra] adds more (axis and instance names). */
internal fun standardNames(plan: FontPlan, extra: List<NameRecord> = emptyList()): List<NameRecord> = buildList {
    add(NameRecord(1, plan.family))
    add(NameRecord(2, plan.style))
    add(NameRecord(3, "${plan.version};${plan.postScriptName}"))
    add(NameRecord(4, plan.fullName))
    add(NameRecord(5, "Version ${plan.version.removePrefix("Version ").trim()}"))
    add(NameRecord(6, plan.postScriptName))
    addAll(extra)
}

// ---------------------------------------------------------------- cmap

/** `cmap`: a format 4 subtable for the BMP, and format 12 as well when anything lies beyond it. */
internal fun cmapTable(plan: FontPlan): ByteArray {
    val pairs = plan.glyphs.mapIndexedNotNull { gid, g -> g.codepoint?.let { it to gid } }.sortedBy { it.first }
    val bmp = pairs.filter { it.first < 0xFFFF }
    val format4 = cmapFormat4(bmp)
    val astral = pairs.any { it.first > 0xFFFF }
    val format12 = if (astral) cmapFormat12(pairs) else null
    val recordCount = if (astral) 4 else 2
    return ByteWriter(format4.size + 64).apply {
        u16(0); u16(recordCount)
        val headerSize = 4 + 8 * recordCount
        val format4Offset = headerSize
        val format12Offset = headerSize + format4.size
        // Unicode platform, then Windows; records are in platform order.
        u16(0); u16(3); u32(format4Offset.toLong())
        if (astral) { u16(0); u16(4); u32(format12Offset.toLong()) }
        u16(3); u16(1); u32(format4Offset.toLong())
        if (astral) { u16(3); u16(10); u32(format12Offset.toLong()) }
        bytes(format4)
        if (format12 != null) bytes(format12)
    }.toByteArray()
}

private fun cmapFormat4(pairs: List<Pair<Int, Int>>): ByteArray {
    // Runs of consecutive characters on consecutive glyphs share one segment (an id delta).
    class Seg(val start: Int, var end: Int, val delta: Int)
    val segs = ArrayList<Seg>()
    for ((cp, gid) in pairs) {
        val last = segs.lastOrNull()
        if (last != null && cp == last.end + 1 && ((gid - cp) and 0xFFFF) == last.delta) last.end = cp
        else segs.add(Seg(cp, cp, (gid - cp) and 0xFFFF))
    }
    segs.add(Seg(0xFFFF, 0xFFFF, 1))
    val segCount = segs.size
    var searchRange = 2; var entrySelector = 0
    while (searchRange * 2 <= segCount * 2) { searchRange *= 2; entrySelector++ }
    val length = 16 + segCount * 8
    return ByteWriter(length).apply {
        u16(4); u16(length); u16(0)
        u16(segCount * 2); u16(searchRange); u16(entrySelector); u16(segCount * 2 - searchRange)
        for (s in segs) u16(s.end)
        u16(0)
        for (s in segs) u16(s.start)
        for (s in segs) u16(s.delta)
        for (s in segs) u16(0)
    }.toByteArray()
}

private fun cmapFormat12(pairs: List<Pair<Int, Int>>): ByteArray {
    class Group(val start: Int, var end: Int, val gid: Int)
    val groups = ArrayList<Group>()
    for ((cp, gid) in pairs) {
        val last = groups.lastOrNull()
        if (last != null && cp == last.end + 1 && gid == last.gid + (cp - last.start)) last.end = cp
        else groups.add(Group(cp, cp, gid))
    }
    return ByteWriter(16 + groups.size * 12).apply {
        u16(12); u16(0); u32((16 + groups.size * 12).toLong()); u32(0)
        u32(groups.size.toLong())
        for (g in groups) { u32(g.start.toLong()); u32(g.end.toLong()); u32(g.gid.toLong()) }
    }.toByteArray()
}

// ---------------------------------------------------------------- sfnt container

/** Lays out [tables] (by tag) as an sfnt file of kind [version] with correct checksums and `head.checkSumAdjustment`. */
internal fun assembleSfnt(version: Long, tables: Map<String, ByteArray>): ByteArray {
    val ordered = tables.entries.sortedBy { it.key }
    val count = ordered.size
    var searchRange = 16; var entrySelector = 0
    while (searchRange * 2 <= count * 16) { searchRange *= 2; entrySelector++ }
    val out = ByteWriter(1024)
    out.u32(version)
    out.u16(count); out.u16(searchRange); out.u16(entrySelector); out.u16(count * 16 - searchRange)
    var offset = 12 + 16 * count
    val offsets = ArrayList<Int>()
    for ((_, data) in ordered) { offsets.add(offset); offset += pad4(data.size) }
    val headIndex = ordered.indexOfFirst { it.key == "head" }
    val headCopy = tables["head"]?.copyOf()
    headCopy?.let { for (k in 8 until 12) it[k] = 0 }
    for ((i, e) in ordered.withIndex()) {
        out.tag(e.key)
        out.u32(tableChecksum(if (i == headIndex) headCopy!! else e.value))
        out.u32(offsets[i].toLong())
        out.u32(e.value.size.toLong())
    }
    for ((i, e) in ordered.withIndex()) {
        out.bytes(if (i == headIndex) headCopy!! else e.value)
        out.align(4)
    }
    val file = out.toByteArray()
    if (headIndex >= 0) {
        val adjustment = (0xB1B0AFBAL - tableChecksum(file)) and 0xFFFFFFFFL
        val at = offsets[headIndex] + 8
        file[at] = (adjustment shr 24).toByte(); file[at + 1] = (adjustment shr 16).toByte()
        file[at + 2] = (adjustment shr 8).toByte(); file[at + 3] = adjustment.toByte()
    }
    return file
}
