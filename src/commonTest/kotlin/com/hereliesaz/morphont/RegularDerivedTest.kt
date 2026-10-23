package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regular is derived from the extremes: it follows them, keeps a nudge, and never seeds an extreme. */
class RegularDerivedTest {
    private fun box(w: Float, h: Float, dx: Float = 0f) = GlyphCorner(w + 100f, mutableListOf(ContourData(mutableListOf(
        Pt(dx, 0f, true), Pt(dx, h, true), Pt(dx + w, h, true), Pt(dx + w, 0f, true),
    ))))

    /** Node x positions (the editor stores arms beside each node, so only on-curve points are the outline). */
    private fun xs(g: GlyphCorner) = g.contours.single().points.filter { it.onCurve }.map { it.x }

    /** A glyph with only Weight drawn: thin 100 wide, black 200 wide, no Regular. */
    private fun weightOnly() = AppState().apply {
        anchors.getValue(Axis.WEIGHT.lo).replaceGlyph(box(100f, 100f))
        anchors.getValue(Axis.WEIGHT.hi).replaceGlyph(box(200f, 100f))
        syncRegular()
    }

    @Test
    fun regularAppearsHalfwayBetweenTheExtremes() {
        val app = weightOnly()
        assertEquals(150f, xs(app.anchors.getValue("regular").glyph)[2])
    }

    @Test
    fun regularFollowsWhenAnExtremeMoves() {
        val app = weightOnly()
        app.anchors.getValue(Axis.WEIGHT.hi).replaceGlyph(box(300f, 100f))
        app.syncRegular()
        assertEquals(200f, xs(app.anchors.getValue("regular").glyph)[2])
    }

    @Test
    fun aNudgeToRegularSurvivesTheExtremesMoving() {
        val app = weightOnly()
        val regular = app.anchors.getValue("regular")
        regular.pushHistory()
        regular.replaceGlyph(regular.glyph.deepCopy().also { c -> c.contours[0].points.filter { it.onCurve }[2].x += 7f })
        app.anchors.getValue(Axis.WEIGHT.hi).replaceGlyph(box(300f, 100f))
        app.syncRegular()
        assertEquals(207f, xs(regular.glyph)[2], "moved by what the extremes moved, nudge kept")
        assertEquals(0f, xs(regular.glyph)[0])
    }

    @Test
    fun regularWhoseStructureNoLongerFitsIsRebuilt() {
        val app = weightOnly()
        fun five(k: Float) = GlyphCorner(300f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 100f, true), Pt(50f * k, 130f, true), Pt(100f * k, 100f, true), Pt(100f * k, 0f, true),
        ))))
        app.anchors.getValue(Axis.WEIGHT.lo).replaceGlyph(five(1f))
        app.anchors.getValue(Axis.WEIGHT.hi).replaceGlyph(five(2f))
        app.syncRegular()
        assertEquals(5, app.anchors.getValue("regular").glyph.contours.single().points.count { it.onCurve })
    }

    @Test
    fun undoingAnExtremeBringsRegularBackWithIt() {
        val app = weightOnly()
        val hi = app.anchors.getValue(Axis.WEIGHT.hi)
        val regular = app.anchors.getValue("regular")
        hi.pushHistory()
        hi.replaceGlyph(box(300f, 100f))
        app.syncRegular()
        assertEquals(200f, xs(regular.glyph)[2])
        hi.undo()
        app.syncRegular()
        assertEquals(150f, xs(regular.glyph)[2])
    }

    @Test
    fun anAxisWithOneExtremeDrawnIsInertAndTheOtherStillVaries() {
        val corners = mutableMapOf<String, GlyphCorner>(
            Axis.WEIGHT.lo to box(100f, 100f), Axis.WEIGHT.hi to box(200f, 100f),
            Axis.WIDTH.lo to box(100f, 300f), // Width.hi not drawn
        )
        val eff = effectiveCorners(corners)
        assertEquals(eff.getValue("regular"), eff.getValue(Axis.WIDTH.lo), "an incomplete axis collapses onto Regular")
        assertEquals(eff.getValue("regular"), eff.getValue(Axis.WIDTH.hi))
        assertEquals(100f, xs(eff.getValue(Axis.WEIGHT.lo))[2])
        val atWidthLo = interpolateGlyph(corners, mapOf(Axis.WEIGHT.tag to 0.5f, Axis.WIDTH.tag to 0f))
        val atWidthHi = interpolateGlyph(corners, mapOf(Axis.WEIGHT.tag to 0.5f, Axis.WIDTH.tag to 1f))
        assertEquals(atWidthLo, atWidthHi, "the unfinished axis does nothing")
    }

    @Test
    fun startingTheOtherExtremeCopiesIntoAnEmptyPartnerOnly() {
        val app = AppState()
        app.activeAnchor = Axis.WEIGHT.lo
        assertEquals("draw this extreme first", app.startOtherExtremeBlockedReason())
        app.anchors.getValue(Axis.WEIGHT.lo).replaceGlyph(box(100f, 100f))
        assertNull(app.startOtherExtremeBlockedReason())
        app.startOtherExtremeFromThis()
        assertEquals(Axis.WEIGHT.hi, app.activeAnchor)
        assertEquals(app.anchors.getValue(Axis.WEIGHT.lo).glyph, app.anchors.getValue(Axis.WEIGHT.hi).glyph)
        app.activeAnchor = Axis.WEIGHT.lo
        assertNotNull(app.startOtherExtremeBlockedReason(), "never overwrites a drawn extreme")
        app.activeAnchor = "regular"
        assertNotNull(app.startOtherExtremeBlockedReason(), "Regular is not a place to start from")
    }

    @Test
    fun theOtherExtremeIsNeverSeededFromRegular() {
        val app = AppState()
        app.anchors.getValue("regular").replaceGlyph(box(100f, 100f))
        app.activeAnchor = "regular"
        app.startOtherExtremeFromThis()
        assertTrue(app.anchors.getValue(Axis.WEIGHT.lo).glyph.contours.isEmpty())
        assertTrue(app.anchors.getValue(Axis.WEIGHT.hi).glyph.contours.isEmpty())
    }

    @Test
    fun thePenIsBlockedOnRegularButNotOnAnExtreme() {
        val app = AppState()
        app.activeAnchor = "regular"
        assertNotNull(app.penBlockedReason)
        app.activeAnchor = Axis.WEIGHT.hi
        assertNull(app.penBlockedReason)
    }

    @Test
    fun aNewGlyphOpensOnAnExtremeAndADrawnOneOnWhatIsDrawn() {
        val app = AppState()
        app.loadGlyph("n", Glyph())
        assertEquals(app.selectedAxis.lo, app.activeAnchor)
        app.loadGlyph("r", Glyph(mutableMapOf("regular" to box(100f, 100f))))
        assertEquals("regular", app.activeAnchor)
        app.loadGlyph("e", Glyph(mutableMapOf(Axis.WEIGHT.hi to box(100f, 100f))))
        assertEquals(Axis.WEIGHT.hi, app.activeAnchor)
    }
}
