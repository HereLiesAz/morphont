package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditingTest {
    private fun square(x0: Float, y0: Float, s: Float) = ContourData(
        mutableListOf(Pt(x0, y0, true), Pt(x0, y0 + s, true), Pt(x0 + s, y0 + s, true), Pt(x0 + s, y0, true)),
    )

    @Test
    fun snapPullsWithinToleranceOnly() {
        val targets = SnapTargets(listOf(SnapTarget(100f)), listOf(SnapTarget(500f)), gridStep = null)
        val near = snapDelta(listOf(0f), listOf(0f), 97f, 300f, targets, tolerance = 5f)
        assertEquals(100f, near.dx)
        assertEquals(300f, near.dy) // 200 away from 500: untouched
        assertEquals(100f, near.xLine)
        assertNull(near.yLine)
    }

    @Test
    fun affineFlipIsSelfInverseAndKeepsWinding() {
        val c = listOf(square(0f, 0f, 10f))
        val flip = scalingAbout(-1f, 1f, 5f, 5f)
        val once = applyAffine(c, flip)
        val twice = applyAffine(once, flip)
        assertEquals(c[0].points.map { it.x to it.y }, twice[0].points.map { it.x to it.y })
        // Mirrored + reversed: the first point of the original is now last.
        assertEquals(10f, once[0].points.last().x)
    }

    @Test
    fun rotationAboutCenterKeepsCenter() {
        val r = rotationAbout(90f, 5f, 5f)
        val p = applyAffine(listOf(ContourData(mutableListOf(Pt(5f, 5f, true)))), r)[0].points[0]
        assertTrue(kotlin.math.abs(p.x - 5f) < 1e-4f && kotlin.math.abs(p.y - 5f) < 1e-4f)
    }

    @Test
    fun clipboardTakesWholeTouchedContours() {
        val g = GlyphCorner(500f, mutableListOf(square(0f, 0f, 10f), square(20f, 0f, 10f)))
        val clip = contoursForClipboard(g, setOf(1 to 2))
        assertEquals(1, clip.size)
        assertEquals(20f, clip[0].points[0].x)
        val st = AnchorState(g)
        st.pasteContours(clip)
        assertEquals(3, st.glyph.contours.size)
        assertEquals(4, st.selection.size)
    }

    @Test
    fun reductionRemovesCollinearPointsFirstAndKeepsAnchorsCompatible() {
        // A square with a redundant midpoint on one edge.
        fun g(dx: Float) = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f + dx, 0f, true), Pt(0f + dx, 5f, true), Pt(0f + dx, 10f, true), Pt(10f + dx, 10f, true), Pt(10f + dx, 0f, true),
        ))))
        val order = reductionOrder(listOf(g(0f), g(3f)))
        assertEquals(0 to 1, order.first().key)
        assertEquals(0f, order.first().error)
        val reduced = withoutPoints(g(0f), setOf(order.first().key))
        assertEquals(4, reduced.contours[0].points.size)
        // Never below three points per contour.
        assertEquals(2, order.size)
    }

    @Test
    fun glyphWithGhostsRoundTripsAndOldFilesStillDecode() {
        val glyph = Glyph(
            guides = listOf(Guide(vertical = true, pos = 120f)),
            ghosts = listOf(GhostData("o", sourceGlyph = "o", matrix = translation(10f, 0f))),
            metrics = FontMetrics(2048f, 1900f, 1456f, 1082f, -500f),
        )
        val back = ProjectCodec.decodeGlyph(ProjectCodec.encodeGlyph(glyph))
        assertEquals(glyph.guides, back.guides)
        assertEquals(glyph.ghosts, back.ghosts)
        assertEquals(glyph.metrics, back.metrics)
        val legacy = ProjectCodec.decodeGlyph("""{"corners":{}}""")
        assertTrue(legacy.ghosts.isEmpty() && legacy.metrics == null)
    }

    @Test
    fun linkedGhostFollowsTheSameAnchor() {
        val app = AppState()
        val other = Glyph()
        other.corners[Axis.WEIGHT.hi] = GlyphCorner(500f, mutableListOf(square(0f, 0f, 99f)))
        other.corners["regular"] = GlyphCorner(500f, mutableListOf(square(0f, 0f, 50f)))
        app.glyphLookup = { if (it == "o") other else null }
        app.loadGlyph("n", Glyph())
        app.addLinkedGhost("o")
        val g = app.ghosts.single()
        assertEquals(99f, boundsOfContours(app.ghostContours(g, Axis.WEIGHT.hi))!!.width)
        assertEquals(50f, boundsOfContours(app.ghostContours(g, "regular"))!!.width)
    }

    @Test
    fun transformingASelectionKeepsPointOrderAndLeavesOthers() {
        val g = GlyphCorner(500f, mutableListOf(square(0f, 0f, 10f)))
        val flipped = transformPoints(g, listOf(0 to 0, 0 to 1), scalingAbout(-1f, 1f, 5f, 5f))
        assertEquals(listOf(10f, 10f, 10f, 10f), flipped.contours[0].points.map { it.x })
        assertEquals(4, flipped.contours[0].points.size)
    }

    @Test
    fun cornerDragScalesAboutTheOppositeCorner() {
        val b = FontRect(0f, 0f, 10f, 10f)
        // Corner 2 is (maxX, maxY); dragging it to (20, 20) doubles the box about (0, 0).
        val t = boxDragTransform(b, rotate = false, corner = 2, start = androidx.compose.ui.geometry.Offset(10f, 10f), cur = androidx.compose.ui.geometry.Offset(20f, 20f))
        assertEquals(listOf(2f, 0f, 0f, 2f, 0f, 0f), t)
    }
}
