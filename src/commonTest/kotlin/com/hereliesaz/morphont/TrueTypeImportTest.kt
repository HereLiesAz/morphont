package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exact SFNT fixture: a valid three-node glyph A and a truncated glyph B.
 * The expected A/B mapping comes from the cmap's explicit ID delta (-64),
 * independently of the importer. No system font or network required.
 */
class TrueTypeImportTest {
    private fun fixture(): ByteArray {
        val b = ByteArray(356)
        fun u16(p: Int, v: Int) {
            b[p] = (v ushr 8).toByte()
            b[p + 1] = v.toByte()
        }
        fun u32(p: Int, v: Int) {
            b[p] = (v ushr 24).toByte()
            b[p + 1] = (v ushr 16).toByte()
            b[p + 2] = (v ushr 8).toByte()
            b[p + 3] = v.toByte()
        }
        u32(0, 0x00010000)
        u16(4, 7)
        val tables = listOf(
            Triple("head", 160, 54), Triple("maxp", 214, 6),
            Triple("hhea", 220, 36), Triple("hmtx", 256, 12),
            Triple("loca", 268, 16), Triple("glyf", 284, 28),
            Triple("cmap", 312, 44),
        )
        tables.forEachIndexed { i, (tag, pos, size) ->
            val record = 12 + i * 16
            tag.forEachIndexed { j, c -> b[record + j] = c.code.toByte() }
            u32(record + 8, pos)
            u32(record + 12, size)
        }
        u16(160 + 18, 1000) // head units per em
        u16(160 + 50, 1) // 32-bit loca
        u16(214 + 4, 3) // maxp glyph count: .notdef, A, B
        u16(220 + 4, 800) // ascender
        u16(220 + 6, 0xFF38) // descender -200
        u16(220 + 34, 3) // three long horizontal metrics
        repeat(3) { u16(256 + it * 4, 500) }
        // glyph 0 empty, glyph A spans 0..20, glyph B spans 20..28.
        for ((i, offset) in listOf(0, 0, 20, 28).withIndex()) u32(268 + i * 4, offset)
        u16(284, 1) // A has one contour
        u16(284 + 10, 2) // contour ends with point index 2
        u16(284 + 12, 0) // no instructions
        b[284 + 14] = 0x31 // three on-curve points with zero x/y deltas
        b[284 + 15] = 0x31
        b[284 + 16] = 0x31
        u16(284 + 20, 1) // B claims a contour, but is only 8 bytes long
        // Unicode BMP format 4: A=gid1, B=gid2 via delta -64.
        u16(312, 0)
        u16(314, 1)
        u16(316, 3)
        u16(318, 1)
        u32(320, 12)
        val sub = 324
        u16(sub, 4)
        u16(sub + 2, 32)
        u16(sub + 6, 4)
        u16(sub + 8, 4)
        u16(sub + 10, 1)
        u16(sub + 14, 66)
        u16(sub + 16, 0xFFFF)
        u16(sub + 20, 65)
        u16(sub + 22, 0xFFFF)
        u16(sub + 24, 0xFFC0)
        u16(sub + 26, 1)
        return b
    }

    @Test
    fun badGlyphIsReportedWhileValidGlyphImports() {
        val result = buildFamilyFromVariableFont(fixture())
        assertEquals(setOf("A"), result.glyphs.keys)
        assertEquals(1, result.skippedCharacters.size)
        assertEquals(66, result.skippedCharacters.single().first)
        assertTrue(result.skippedCharacters.single().second.contains("Truncated TrueType"))
        val glyph = result.glyphs.getValue("A")
        assertEquals(500f, glyph.corners.getValue("regular").width)
        assertEquals(3, glyph.corners.getValue("regular").contours.single().points.count { it.onCurve })
    }

    @Test
    fun outOfRangeTableIsRejectedWithoutAnArrayIndexException() {
        val bytes = fixture()
        // Make glyf's table directory claim data beyond the end of the font.
        bytes[12 + 5 * 16 + 12] = 0x7F
        val ex = assertFailsWith<IllegalArgumentException> {
            VariableFont.parse(bytes, requireVariable = false)
        }
        assertTrue(ex.message.orEmpty().contains("table bounds"))
    }

    @Test
    fun readerChecksTheLastByteAndGlyphSubranges() {
        val r = Reader(byteArrayOf(10, 20, 30, 40))
        assertEquals(40, r.u8(3))
        assertFailsWith<IllegalArgumentException> { r.u8(4) }
        assertFailsWith<IllegalArgumentException> { r.check(2, 2, 3) }
        assertFailsWith<IllegalArgumentException> { r.check(-1, 1) }
    }
}
