package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class InterpolationTest {
    private fun corner(width: Float, x: Float, y: Float) =
        GlyphCorner(width, mutableListOf(ContourData(mutableListOf(Pt(x, y, onCurve = true)))))

    private val glyphs = mapOf(
        "regular" to corner(500f, 50f, 50f),
        Axis.WEIGHT.lo to corner(100f, 10f, 10f),
        Axis.WEIGHT.hi to corner(900f, 90f, 90f),
    )

    @Test
    fun interpolateGlyphReproducesLoExactlyAtTZero() {
        val out = interpolateGlyph(glyphs, mapOf("wght" to 0f), axes = listOf(Axis.WEIGHT))
        assertEquals(100f, out.width)
        assertEquals(10f, out.contours[0].points[0].x)
        assertEquals(10f, out.contours[0].points[0].y)
    }

    @Test
    fun interpolateGlyphReproducesHiExactlyAtTOne() {
        val out = interpolateGlyph(glyphs, mapOf("wght" to 1f), axes = listOf(Axis.WEIGHT))
        assertEquals(900f, out.width)
        assertEquals(90f, out.contours[0].points[0].x)
        assertEquals(90f, out.contours[0].points[0].y)
    }

    @Test
    fun interpolateGlyphReproducesRegularExactlyAtCenter() {
        val out = interpolateGlyph(glyphs, mapOf("wght" to 0.5f), axes = listOf(Axis.WEIGHT))
        assertEquals(500f, out.width)
        assertEquals(50f, out.contours[0].points[0].x)
        assertEquals(50f, out.contours[0].points[0].y)
    }

    @Test
    fun interpolateGlyphDefaultsAMissingAxisToRegular() {
        val out = interpolateGlyph(glyphs, emptyMap(), axes = listOf(Axis.WEIGHT))
        assertEquals(500f, out.width)
    }

    @Test
    fun compatibilityIssueIsNullWhenAnchorsMatch() {
        val corners = mapOf<String, GlyphCorner?>(
            "regular" to corner(500f, 1f, 1f),
            Axis.WEIGHT.lo to corner(100f, 2f, 2f),
        )
        assertNull(compatibilityIssue(corners, listOf("regular", Axis.WEIGHT.lo)))
    }

    @Test
    fun compatibilityIssueFlagsMismatchedPointCounts() {
        val extraPoint = GlyphCorner(
            500f,
            mutableListOf(ContourData(mutableListOf(Pt(0f, 0f, true), Pt(1f, 1f, true)))),
        )
        val corners = mapOf<String, GlyphCorner?>(
            "regular" to corner(500f, 1f, 1f),
            Axis.WEIGHT.lo to extraPoint,
        )
        assertNotNull(compatibilityIssue(corners, listOf("regular", Axis.WEIGHT.lo)))
    }
}
