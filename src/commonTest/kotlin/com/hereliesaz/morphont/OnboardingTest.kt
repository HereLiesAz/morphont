package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTest {
    private fun signedArea(c: ContourData): Float {
        val pts = c.points.filter { it.onCurve }
        var a = 0f
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2f
    }

    @Test
    fun theSampleInterpolatesOnEveryAnchor() {
        val g = sampleGlyph()
        assertEquals(ANCHORS.toSet(), g.corners.keys)
        assertNull(compatibilityIssue(g.corners))
        for (corner in g.corners.values) {
            assertEquals(2, corner.contours.size)
            // Node, out-arm, in-arm, four times: the shape [Pt] describes for a closed cubic contour.
            for (c in corner.contours) assertEquals(listOf(true, false, false), c.points.take(3).map { it.onCurve })
        }
    }

    @Test
    fun theSampleRunsOuterClockwiseAndCounterCounterClockwise() {
        val regular = sampleGlyph().corners.getValue("regular")
        assertTrue(signedArea(regular.contours[0]) < 0f, "outer contour should be clockwise")
        assertTrue(signedArea(regular.contours[1]) > 0f, "counter should be counter-clockwise")
    }

    @Test
    fun theWeightExtremesDifferFromRegular() {
        val g = sampleGlyph()
        val thin = g.corners.getValue(Axis.WEIGHT.lo).contours[1].points[0].y
        val black = g.corners.getValue(Axis.WEIGHT.hi).contours[1].points[0].y
        val regular = g.corners.getValue("regular").contours[1].points[0].y
        assertTrue(thin > regular && regular > black, "the counter's top should sink as weight grows")
    }

    @Test
    fun theSampleNeverOverwritesAGlyph() {
        assertEquals("o", sampleGlyphName(emptyList()))
        assertEquals("o.sample", sampleGlyphName(listOf("o")))
        assertEquals("o.sample3", sampleGlyphName(listOf("o", "o.sample")))
    }

    @Test
    fun aDrawnGlyphIsAnnouncedAsSuch() {
        assertTrue(createdMessage("o", sampleGlyph()).contains("already drawn"))
        assertTrue(createdMessage("a", Glyph()).contains("Regular first"))
    }
}
