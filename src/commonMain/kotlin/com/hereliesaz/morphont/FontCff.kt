package com.hereliesaz.morphont

import kotlin.math.round

/*
 * The `CFF ` table: PostScript (cubic) outlines for an OpenType font. One
 * font, no subroutines, no hints -- the outlines are exactly what Morphont
 * draws.
 */

private const val OP_RLINETO = 5
private const val OP_RRCURVETO = 8
private const val OP_ENDCHAR = 14
private const val OP_RMOVETO = 21

/** A Type 2 charstring number. */
private fun t2number(w: ByteWriter, v: Int) {
    when {
        v in -107..107 -> w.u8(v + 139)
        v in 108..1131 -> { val x = v - 108; w.u8(247 + x / 256); w.u8(x % 256) }
        v in -1131..-108 -> { val x = -v - 108; w.u8(251 + x / 256); w.u8(x % 256) }
        else -> { w.u8(28); w.i16(v.coerceIn(-32768, 32767)) }
    }
}

private fun cubicOf(px: Int, py: Int, s: FontSeg): IntArray =
    if (s.order == 2) intArrayOf(
        round(px + 2f / 3f * (s.c1x - px)).toInt(), round(py + 2f / 3f * (s.c1y - py)).toInt(),
        round(s.x + 2f / 3f * (s.c1x - s.x)).toInt(), round(s.y + 2f / 3f * (s.c1y - s.y)).toInt(),
    ) else intArrayOf(s.c1x, s.c1y, s.c2x, s.c2y)

private fun charString(g: PlannedGlyph, defaultWidth: Int, nominalWidth: Int): ByteArray {
    val w = ByteWriter(64)
    var widthPending = g.width != defaultWidth
    var cx = 0; var cy = 0
    for (c in g.contours) {
        if (widthPending) { t2number(w, g.width - nominalWidth); widthPending = false }
        t2number(w, c.startX - cx); t2number(w, c.startY - cy); w.u8(OP_RMOVETO)
        cx = c.startX; cy = c.startY
        // The last segment returns to the start; a closing line is implied by the path ending, so it is not drawn.
        val segs = if (c.segs.last().order == 1) c.segs.dropLast(1) else c.segs
        var i = 0
        while (i < segs.size) {
            if (segs[i].order == 1) {
                var run = 0
                while (i + run < segs.size && segs[i + run].order == 1 && run < 24) {
                    val s = segs[i + run]
                    t2number(w, s.x - cx); t2number(w, s.y - cy); cx = s.x; cy = s.y; run++
                }
                w.u8(OP_RLINETO)
                i += run
            } else {
                var run = 0
                while (i + run < segs.size && segs[i + run].order != 1 && run < 8) {
                    val s = segs[i + run]
                    val k = cubicOf(cx, cy, s)
                    t2number(w, k[0] - cx); t2number(w, k[1] - cy)
                    t2number(w, k[2] - k[0]); t2number(w, k[3] - k[1])
                    t2number(w, s.x - k[2]); t2number(w, s.y - k[3])
                    cx = s.x; cy = s.y; run++
                }
                w.u8(OP_RRCURVETO)
                i += run
            }
        }
    }
    if (widthPending) t2number(w, g.width - nominalWidth)
    w.u8(OP_ENDCHAR)
    return w.toByteArray()
}

/** A CFF INDEX: a count, an offset size, 1-based offsets, then the items. */
private fun cffIndex(items: List<ByteArray>): ByteArray {
    if (items.isEmpty()) return ByteWriter(2).apply { u16(0) }.toByteArray()
    val total = items.sumOf { it.size } + 1
    val offSize = when { total <= 0xFF -> 1; total <= 0xFFFF -> 2; total <= 0xFFFFFF -> 3; else -> 4 }
    return ByteWriter(total + items.size * offSize + 8).apply {
        u16(items.size); u8(offSize)
        var offset = 1
        fun off(v: Int) = when (offSize) { 1 -> u8(v); 2 -> u16(v); 3 -> u24(v); else -> u32(v.toLong()) }
        off(offset)
        for (item in items) { offset += item.size; off(offset) }
        for (item in items) bytes(item)
    }.toByteArray()
}

/** A DICT integer, in the shortest form. */
private fun dictInt(w: ByteWriter, v: Int) {
    when {
        v in -107..107 -> w.u8(v + 139)
        v in 108..1131 -> { val x = v - 108; w.u8(247 + x / 256); w.u8(x % 256) }
        v in -1131..-108 -> { val x = -v - 108; w.u8(251 + x / 256); w.u8(x % 256) }
        v in -32768..32767 -> { w.u8(28); w.i16(v) }
        else -> { w.u8(29); w.i32(v) }
    }
}

/** A DICT integer always in five bytes, for offsets whose value is not known until the layout is. */
private fun dictOffset(w: ByteWriter, v: Int) { w.u8(29); w.i32(v) }

/** A DICT real number, as packed decimal digits. */
private fun dictReal(w: ByteWriter, v: Double) {
    val nibbles = ArrayList<Int>()
    val text = v.toString()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c in '0'..'9' -> nibbles.add(c - '0')
            c == '.' -> nibbles.add(0xA)
            c == 'E' || c == 'e' -> if (i + 1 < text.length && text[i + 1] == '-') { nibbles.add(0xC); i++ } else nibbles.add(0xB)
            c == '-' -> nibbles.add(0xE)
        }
        i++
    }
    nibbles.add(0xF)
    if (nibbles.size % 2 == 1) nibbles.add(0xF)
    w.u8(30)
    for (k in nibbles.indices step 2) w.u8((nibbles[k] shl 4) or nibbles[k + 1])
}

/** `CFF `: one font whose glyphs are [plan]'s, in order. */
internal fun cffTable(plan: FontPlan): ByteArray {
    val widths = plan.glyphs.map { it.width }
    val common = widths.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
    val charStrings = plan.glyphs.map { charString(it, common, common) }

    // Strings: family, full name, then every glyph name but .notdef. SIDs 0..390 are the standard strings.
    val strings = ArrayList<String>()
    // CFF strings are ASCII; anything else in a name becomes a question mark rather than corrupting the INDEX.
    fun sid(s: String): Int { strings.add(s.map { if (it.code in 32..126) it else '?' }.joinToString("")); return 390 + strings.size }
    val familySid = sid(plan.family)
    val fullNameSid = sid(plan.fullName)
    val glyphSids = plan.glyphs.drop(1).map { sid(it.name) }

    val header = ByteWriter(4).apply { u8(1); u8(0); u8(4); u8(4) }.toByteArray()
    val nameIndex = cffIndex(listOf(plan.postScriptName.encodeToByteArray()))
    val stringIndex = cffIndex(strings.map { it.encodeToByteArray() })
    val globalSubrs = cffIndex(emptyList())
    val charStringIndex = cffIndex(charStrings)
    val charset = ByteWriter(1 + glyphSids.size * 2).apply { u8(0); for (s in glyphSids) u16(s) }.toByteArray()
    val privateDict = ByteWriter(16).apply {
        dictInt(this, common); u8(20) // defaultWidthX
        dictInt(this, common); u8(21) // nominalWidthX
    }.toByteArray()

    val bounds = plan.fontBounds
    fun topDict(charsetOffset: Int, charStringsOffset: Int, privateOffset: Int) = ByteWriter(64).apply {
        dictInt(this, fullNameSid); u8(2)
        dictInt(this, familySid); u8(3)
        for (b in bounds) dictInt(this, b); u8(5)
        if (plan.unitsPerEm != 1000) {
            for (v in doubleArrayOf(1.0 / plan.unitsPerEm, 0.0, 0.0, 1.0 / plan.unitsPerEm, 0.0, 0.0)) {
                if (v == 0.0) dictInt(this, 0) else dictReal(this, v)
            }
            u8(12); u8(7)
        }
        dictOffset(this, charsetOffset); u8(15)
        dictOffset(this, charStringsOffset); u8(17)
        dictInt(this, privateDict.size); dictOffset(this, privateOffset); u8(18)
    }.toByteArray()

    // Every offset in the top DICT has a fixed width, so its size is known before the offsets are.
    val topDictIndexSize = cffIndex(listOf(topDict(0, 0, 0))).size
    val charsetOffset = header.size + nameIndex.size + topDictIndexSize + stringIndex.size + globalSubrs.size
    val charStringsOffset = charsetOffset + charset.size
    val privateOffset = charStringsOffset + charStringIndex.size
    val topDictIndex = cffIndex(listOf(topDict(charsetOffset, charStringsOffset, privateOffset)))
    check(topDictIndex.size == topDictIndexSize) { "CFF top DICT changed size between passes." }

    return ByteWriter(privateOffset + privateDict.size).apply {
        bytes(header); bytes(nameIndex); bytes(topDictIndex); bytes(stringIndex); bytes(globalSubrs)
        bytes(charset); bytes(charStringIndex); bytes(privateDict)
    }.toByteArray()
}
