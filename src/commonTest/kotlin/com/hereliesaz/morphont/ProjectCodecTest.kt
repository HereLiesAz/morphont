package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectCodecTest {
    @Test
    fun projectRoundTripPreservesGlyphData() {
        val glyph = Glyph()
        glyph.corners.getValue("regular").width = 612f
        glyph.corners.getValue("regular").contours += ContourData(
            mutableListOf(Pt(10f, 20f, true), Pt(30f, 40f, false)),
        )

        val decoded = ProjectCodec.decodeProject(ProjectCodec.encodeProject(mapOf("A" to glyph)))
        val regular = decoded.getValue("A").corners.getValue("regular")

        assertEquals(612f, regular.width)
        assertEquals(2, regular.contours.single().points.size)
        assertEquals(false, regular.contours.single().points[1].onCurve)
    }

    @Test
    fun legacyAnchorNamesMigrateOnEveryPlatform() {
        val old = Glyph(
            mutableMapOf(
                "extraThin" to GlyphCorner(width = 111f),
                "extraBlack" to GlyphCorner(width = 999f),
                "regular" to GlyphCorner(width = 500f),
            ),
        )

        val migrated = ProjectCodec.migrateGlyph(old)

        assertTrue(Axis.WEIGHT.lo in migrated.corners)
        assertTrue(Axis.WEIGHT.hi in migrated.corners)
        assertEquals(111f, migrated.corners.getValue(Axis.WEIGHT.lo).width)
        assertEquals(999f, migrated.corners.getValue(Axis.WEIGHT.hi).width)
    }
}
