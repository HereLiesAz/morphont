package com.hereliesaz.morphont

/*
 * WOFF and WOFF2 containers around a finished sfnt. Neither changes a table's
 * contents here: WOFF compresses each table on its own; WOFF2 compresses all
 * of them as one stream (see Brotli.kt) and leaves glyf/loca untransformed.
 */

private class SfntTable(val tag: String, val checksum: Long, val data: ByteArray)

private fun readSfnt(sfnt: ByteArray): Pair<Long, List<SfntTable>> {
    val r = Reader(sfnt)
    val flavor = r.u32(0)
    val count = r.u16(4)
    val tables = (0 until count).map { i ->
        val rec = 12 + i * 16
        val offset = r.u32(rec + 8).toInt(); val length = r.u32(rec + 12).toInt()
        SfntTable(r.tag(rec), r.u32(rec + 4), sfnt.copyOfRange(offset, offset + length))
    }
    return flavor to tables
}

/** Size of the sfnt the tables came from: directory plus every table padded to four bytes. */
private fun sfntSize(tables: List<SfntTable>): Long = 12L + 16L * tables.size + tables.sumOf { pad4(it.data.size).toLong() }

/** [sfnt] as a WOFF 1.0 file: each table zlib-compressed unless that would not make it smaller. */
internal fun woffFromSfnt(sfnt: ByteArray): ByteArray {
    val (flavor, tables) = readSfnt(sfnt)
    val packed = tables.map { t ->
        val z = zlibCompress(t.data)
        if (z.size < t.data.size) z else t.data
    }
    val directorySize = 44 + 20 * tables.size
    var offset = directorySize
    val offsets = packed.map { val o = offset; offset += pad4(it.size); o }
    val out = ByteWriter(offset)
    out.tag("wOFF"); out.u32(flavor)
    out.u32(offset.toLong())
    out.u16(tables.size); out.u16(0)
    out.u32(sfntSize(tables))
    out.u16(1); out.u16(0) // font version 1.0
    for (k in 0 until 5) out.u32(0) // no metadata, no private data
    for ((i, t) in tables.withIndex()) {
        out.tag(t.tag); out.u32(offsets[i].toLong()); out.u32(packed[i].size.toLong()); out.u32(t.data.size.toLong()); out.u32(t.checksum)
    }
    for (p in packed) { out.bytes(p); out.align(4) }
    return out.toByteArray()
}

// WOFF2's table directory names the usual tables by number; anything else carries its tag.
private val WOFF2_KNOWN_TAGS = listOf(
    "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG",
    "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC",
    "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc",
    "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat",
    "Gloc", "Feat", "Sill",
)

/** A WOFF2 variable-length unsigned integer: seven bits at a time, high bit set on all but the last byte. */
private fun base128(w: ByteWriter, value: Long) {
    var groups = 1
    while (value shr (7 * groups) != 0L) groups++
    for (g in groups - 1 downTo 0) {
        val part = ((value shr (7 * g)) and 0x7F).toInt()
        w.u8(if (g == 0) part else part or 0x80)
    }
}

/** [sfnt] as a WOFF2 file, its tables stored as they are in one Brotli stream. */
internal fun woff2FromSfnt(sfnt: ByteArray): ByteArray {
    val (flavor, tables) = readSfnt(sfnt)
    // glyf must be followed directly by loca.
    val ordered = ArrayList<SfntTable>()
    for (t in tables) {
        if (t.tag == "loca") continue
        ordered.add(t)
        if (t.tag == "glyf") tables.firstOrNull { it.tag == "loca" }?.let { ordered.add(it) }
    }
    val directory = ByteWriter(16 * ordered.size)
    for (t in ordered) {
        val index = WOFF2_KNOWN_TAGS.indexOf(t.tag)
        val nullTransform = t.tag == "glyf" || t.tag == "loca" // transform version 3: stored as is
        directory.u8((if (index >= 0) index else 63) or (if (nullTransform) 0xC0 else 0))
        if (index < 0) directory.tag(t.tag)
        base128(directory, t.data.size.toLong())
    }
    val stream = ByteWriter(sfnt.size)
    for (t in ordered) stream.bytes(t.data)
    val compressed = brotliStored(stream.toByteArray())
    val total = pad4(48 + directory.size + compressed.size)
    return ByteWriter(total).apply {
        tag("wOF2"); u32(flavor)
        u32(total.toLong())
        u16(ordered.size); u16(0)
        u32(sfntSize(tables))
        u32(compressed.size.toLong())
        u16(1); u16(0)
        for (k in 0 until 5) u32(0)
        writer(directory)
        bytes(compressed); align(4)
    }.toByteArray()
}
