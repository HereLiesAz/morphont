package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exact SFNT fixture with a simple triangle (A, gid 1) and two composites that
 * reference it with a component transform: B (gid 2) scales by 0.5 and offsets
 * by (10, 20); C (gid 3) applies a full 2x2 matrix (90 degree turn) and the same offset.
 */
class CompositeGlyphTest {
    private fun fixture(): ByteArray {
        val glyf = 68
        val tables = listOf(
            Triple("head", 54, 0), Triple("maxp", 6, 0), Triple("hhea", 36, 0),
            Triple("hmtx", 16, 0), Triple("loca", 20, 0), Triple("glyf", glyf, 0), Triple("cmap", 44, 0),
        )
        val offsets = HashMap<String, Int>()
        var pos = 12 + tables.size * 16
        for ((tag, size, _) in tables) { offsets[tag] = pos; pos += size }
        val b = ByteArray(pos)
        fun u16(p: Int, v: Int) { b[p] = (v ushr 8).toByte(); b[p + 1] = v.toByte() }
        fun u32(p: Int, v: Int) { u16(p, v ushr 16); u16(p + 2, v and 0xFFFF) }
        u32(0, 0x00010000)
        u16(4, tables.size)
        tables.forEachIndexed { i, (tag, size, _) ->
            val record = 12 + i * 16
            tag.forEachIndexed { j, c -> b[record + j] = c.code.toByte() }
            u32(record + 8, offsets.getValue(tag))
            u32(record + 12, size)
        }
        val head = offsets.getValue("head"); val maxp = offsets.getValue("maxp"); val hhea = offsets.getValue("hhea")
        val hmtx = offsets.getValue("hmtx"); val loca = offsets.getValue("loca"); val g = offsets.getValue("glyf")
        val cmap = offsets.getValue("cmap")
        u16(head + 18, 1000); u16(head + 50, 1) // units per em, 32-bit loca
        u16(maxp + 4, 4)
        u16(hhea + 4, 800); u16(hhea + 6, 0xFF38); u16(hhea + 34, 4)
        repeat(4) { u16(hmtx + it * 4, 500) }
        for ((i, o) in listOf(0, 0, 20, 40, 68).withIndex()) u32(loca + i * 4, o)
        // A: (100,0) (100,100) (0,100), all on-curve.
        u16(g, 1); u16(g + 10, 2); u16(g + 12, 0)
        b[g + 14] = 0x13; b[g + 15] = 0x35; b[g + 16] = 0x23
        b[g + 17] = 100; b[g + 18] = 100; b[g + 19] = 100
        // B: composite of A, words + xy + scale (0.5 = 0x2000), offset (10, 20).
        val gb = g + 20
        u16(gb, 0xFFFF); u16(gb + 10, 0x000B); u16(gb + 12, 1); u16(gb + 14, 10); u16(gb + 16, 20); u16(gb + 18, 0x2000)
        // C: composite of A, words + xy + 2x2 (xx=0, xy=1, yx=-1, yy=0), offset (10, 20).
        val gc = g + 40
        u16(gc, 0xFFFF); u16(gc + 10, 0x0083); u16(gc + 12, 1); u16(gc + 14, 10); u16(gc + 16, 20)
        u16(gc + 18, 0); u16(gc + 20, 0x4000); u16(gc + 22, 0xC000); u16(gc + 24, 0)
        // cmap format 4: A..C -> gid 1..3 via delta -64.
        u16(cmap, 0); u16(cmap + 2, 1); u16(cmap + 4, 3); u16(cmap + 6, 1); u32(cmap + 8, 12)
        val sub = cmap + 12
        u16(sub, 4); u16(sub + 2, 32); u16(sub + 6, 4); u16(sub + 8, 4); u16(sub + 10, 1)
        u16(sub + 14, 67); u16(sub + 16, 0xFFFF); u16(sub + 20, 65); u16(sub + 22, 0xFFFF)
        u16(sub + 24, 0xFFC0); u16(sub + 26, 1)
        return b
    }

    private fun nodes(name: String): Set<Pair<Float, Float>> {
        val result = buildFamilyFromVariableFont(fixture())
        return result.glyphs.getValue(name).corners.getValue("regular").contours.single()
            .points.filter { it.onCurve }.map { it.x to it.y }.toSet()
    }

    @Test
    fun uniformComponentScaleIsApplied() {
        assertEquals(setOf(100f to 0f, 100f to 100f, 0f to 100f), nodes("A"))
        assertEquals(setOf(60f to 20f, 60f to 70f, 10f to 70f), nodes("B"))
    }

    @Test
    fun twoByTwoComponentMatrixIsApplied() {
        assertEquals(setOf(10f to 120f, -90f to 120f, -90f to 20f), nodes("C"))
    }
}
