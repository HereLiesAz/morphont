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
        assertEquals(12, st.selection.size)
    }

    @Test
    fun reductionRemovesCollinearPointsFirstAndKeepsAnchorsCompatible() {
        // A square with a redundant midpoint on one edge.
        fun g(dx: Float) = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f + dx, 0f, true), Pt(0f + dx, 5f, true), Pt(0f + dx, 10f, true), Pt(10f + dx, 10f, true), Pt(10f + dx, 0f, true),
        ))))
        val order = reductionOrder(listOf(g(0f), g(3f)))
        assertEquals(0 to 1, order.first().key)
        assertTrue(kotlin.math.abs(order.first().error) < 0.001f)
        val reduced = withoutPoints(g(0f), setOf(order.first().key))
        assertEquals(4, reduced.contours[0].points.size)
        // A single closed contour retains a triangle; do not erase it.
        assertEquals(2, order.size)
        val triangle = withoutPoints(g(0f), order.map { it.key }.toSet())
        assertEquals(3, triangle.contours.single().points.count { it.onCurve })
        assertTrue(isValidCubicContour(triangle.contours.single()))
    }

    @Test
    fun simplificationMeasuresCurvedCornersAndMirroredHandles() {
        // A six-node rectangle has two redundant nodes: B on the bottom,
        // D on the right. D stays straight; B becomes a large curve.
        val raw = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(25f, 0f, true), Pt(50f, 0f, true),
            Pt(50f, 50f, true), Pt(50f, 100f, true), Pt(0f, 100f, true),
        ))))
        val unchanged = AnchorState(raw).glyph
        assertEquals(0 to 3, reductionOrder(listOf(unchanged)).first().key)

        val corner = unchanged.deepCopy()
        val c = corner.contours.single().points
        c[2].y = 70f // incoming control for B: independent corner arm
        c[4].y = -45f // outgoing control for B: independent corner arm
        assertEquals(0 to 9, reductionOrder(listOf(corner)).first().key)

        val mirrored = unchanged.deepCopy()
        val m = mirrored.contours.single().points
        m[2].y = 65f
        m[4].y = -65f
        m[3].smooth = true
        assertEquals(0 to 9, reductionOrder(listOf(mirrored)).first().key)
        assertEquals(3, reductionOrder(listOf(mirrored)).size)
    }

    @Test
    fun simplifySliderCanReachThreePerContourNotCountHandles() {
        val app = AppState()
        val g = Glyph()
        g.corners["regular"] = GlyphCorner(500f, mutableListOf(
            ContourData(mutableListOf(
                Pt(0f, 0f, true), Pt(0f, 100f, true), Pt(100f, 100f, true),
                Pt(100f, 0f, true), Pt(50f, 0f, true),
            )),
        ))
        app.loadGlyph("test", g)
        app.startReduction()
        val preview = app.reduction!!
        assertEquals(5, preview.totalPoints)
        assertEquals(2, preview.order.size)
        preview.removeCount = preview.order.size
        assertEquals(3, preview.totalPoints - preview.removeCount)
        app.applyReduction()
        val contour = app.anchors.getValue("regular").glyph.contours.single()
        assertEquals(3, contour.points.count { it.onCurve })
        assertEquals(9, contour.points.size) // two handles per node are not extra nodes
        assertTrue(isValidCubicContour(contour))
    }

    @Test
    fun overSimplificationCanReachThreeNodesAcrossMultipleContours() {
        val app = AppState()
        val outer = ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 100f, true), Pt(100f, 100f, true),
            Pt(100f, 0f, true), Pt(50f, 0f, true),
        ))
        val inner = ContourData(mutableListOf(
            Pt(20f, 20f, true), Pt(20f, 40f, true),
            Pt(40f, 40f, true), Pt(40f, 20f, true),
        ))
        val glyph = Glyph()
        glyph.corners["regular"] = GlyphCorner(500f, mutableListOf(outer, inner))
        app.loadGlyph("O", glyph)
        app.startReduction()
        val session = app.reduction!!
        assertEquals(9, session.totalPoints)
        assertEquals(6, session.order.size)
        session.removeCount = session.order.size
        val preview = withoutPoints(app.anchors.getValue("regular").glyph, session.removed)
        assertEquals(3, preview.contours.sumOf { contour -> contour.points.count { it.onCurve } })
        app.applyReduction()
        val remaining = app.anchors.getValue("regular").glyph.contours
        assertEquals(1, remaining.size)
        assertEquals(3, remaining.single().points.count { it.onCurve })
        assertTrue(isValidCubicContour(remaining.single()))
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

    @Test
    fun offsetEmboldensAStemByTwiceTheOffsetAndKeepsPoints() {
        val stem = listOf(square(100f, 0f, 80f))
        val bold = offsetOutline(stem, 10f, 10f)
        assertEquals(4, bold[0].points.size)
        assertEquals(100f, boundsOfContours(bold)!!.width)
    }

    @Test
    fun suggestionsOfferEmboldenOnTheBoldAnchorAndItStaysCompatible() {
        val app = AppState()
        val g = Glyph()
        ANCHORS.forEach { g.corners[it] = GlyphCorner(300f, mutableListOf(square(100f, 0f, 80f))) }
        app.loadGlyph("i", g)
        app.activeAnchor = Axis.WEIGHT.hi
        val s = suggestionsFor(app).first { it.label.startsWith("Embolden") }
        s.apply(app)
        assertNull(app.compatibility())
        assertTrue(boundsOfContours(app.anchors.getValue(Axis.WEIGHT.hi).glyph.contours)!!.width > 80f)
    }

    @Test
    fun suggestsSnappingPointsJustOffTheBaseline() {
        val app = AppState()
        val g = Glyph()
        g.corners["regular"] = GlyphCorner(300f, mutableListOf(square(100f, 3f, 200f)))
        app.loadGlyph("x", g)
        val s = suggestionsFor(app).first { it.label.contains("baseline") }
        s.apply(app)
        assertEquals(0f, boundsOfContours(app.anchors.getValue("regular").glyph.contours)!!.minY)
    }

    @Test
    fun editingAGhostLocksTheGlyph() {
        val app = AppState()
        val g = Glyph()
        g.corners["regular"] = GlyphCorner(300f, mutableListOf(square(100f, 0f, 80f)))
        app.loadGlyph("i", g)
        app.addGhost("box", listOf(square(0f, 0f, 10f)))
        app.editGhost(app.ghosts.single().id)
        val before = app.toGlyph().corners
        app.commandTarget.selectAll()
        app.commandTarget.deleteSelected()
        app.copyActiveToOthers()
        app.startReduction()
        assertEquals(before, app.toGlyph().corners)
        assertNull(app.reduction)
        assertTrue(suggestionsFor(app).isEmpty())
    }
}
