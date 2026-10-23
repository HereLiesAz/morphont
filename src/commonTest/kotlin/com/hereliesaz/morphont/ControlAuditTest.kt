package com.hereliesaz.morphont

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlAuditTest {
    private fun square() = ContourData(mutableListOf(
        Pt(0f, 0f, true), Pt(0f, 100f, true),
        Pt(100f, 100f, true), Pt(100f, 0f, true),
    ))

    private fun loaded(contour: ContourData = square()): AppState = AppState().apply {
        val glyph = Glyph(corners = mutableMapOf("regular" to
            GlyphCorner(500f, mutableListOf(contour))))
        loadGlyph("A", glyph)
    }

    @Test
    fun undoRunsInActionOrderAcrossGeometryGuidesAndMetrics() {
        val app = loaded()
        val node = app.anchors.getValue("regular")
        node.selection = setOf(0 to 0)
        node.nudgeSelected(12f, 0f)
        assertEquals(12f, node.glyph.contours.single().points.first().x)
        app.replaceGuide(null, Guide(true, 42f))
        app.updateMetrics(FontMetrics(unitsPerEm = 2048f))
        app.undo()
        assertEquals(1000f, app.metrics.unitsPerEm)
        assertEquals(listOf(Guide(true, 42f)), app.guides.toList())
        app.undo()
        assertTrue(app.guides.isEmpty())
        app.undo()
        assertEquals(0f, node.glyph.contours.single().points.first().x)
    }

    @Test
    fun clearingGuidesAndRemovingGhostsAreUndoable() {
        val app = loaded()
        app.replaceGuide(null, Guide(false, 30f))
        app.clearGuides()
        assertTrue(app.guides.isEmpty())
        app.undo()
        assertEquals(Guide(false, 30f), app.guides.single())
        app.addGhost("reference", listOf(square()))
        val id = app.ghosts.single().id
        app.removeGhost(id)
        assertTrue(app.ghosts.isEmpty())
        app.undo()
        assertEquals(id, app.ghosts.single().id)
    }

    @Test
    fun detachingLinkedGhostCanBeUndoneWithoutLosingItsReference() {
        val app = loaded()
        app.addLinkedGhost("A")
        val ghost = app.ghosts.single()
        app.editGhost(ghost.id)
        app.setGhostNodeMode(true)
        assertEquals(null, ghost.sourceGlyph)
        assertFalse(app.ghostTransformMode)
        app.undo()
        assertEquals("A", ghost.sourceGlyph)
        assertTrue(app.ghostTransformMode)
    }

    @Test
    fun copyingAnchorsIsOneUndoAction() {
        val app = AppState()
        val source = Axis.WEIGHT.lo
        val target = Axis.WEIGHT.hi
        app.anchors.getValue(source).replaceGlyph(GlyphCorner(500f, mutableListOf(square())))
        app.activeAnchor = source
        assertTrue(app.anchors.getValue(target).glyph.contours.isEmpty())
        app.startOtherExtremeFromThis()
        assertEquals(target, app.activeAnchor, "opens the extreme it just started")
        assertEquals(4, app.anchors.getValue(target).glyph.contours.single().points.count { it.onCurve })
        app.undo()
        assertTrue(app.anchors.getValue(target).glyph.contours.isEmpty())
        assertEquals(4, app.anchors.getValue(source).glyph.contours.single().points.count { it.onCurve })
    }

    @Test
    fun duplicateNodesWithHandlesAreRecognized() {
        val app = loaded(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 0f, true),
            Pt(0f, 100f, true), Pt(100f, 100f, true), Pt(100f, 0f, true),
        )))
        assertTrue(suggestionsFor(app).any { it.label.startsWith("Merge 1 duplicate") })
    }

    @Test
    fun almostVerticalEdgesAreRecognizedDespiteBezierHandles() {
        val app = loaded(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(3f, 100f, true),
            Pt(110f, 100f, true), Pt(110f, 0f, true),
        )))
        assertTrue(suggestionsFor(app).any { it.label.contains("near-vertical") })
    }

    @Test
    fun curveStemSamplingUsesInkInsteadOfControlPolygon() {
        val contour = ContourData(mutableListOf(
            Pt(0f, 0f, true),
            Pt(0f, 100f, false), Pt(100f, 100f, false),
            Pt(100f, 0f, true),
            Pt(0f, 0f, true),
        ))
        val spans = inkRuns(listOf(contour), 50f)
        assertEquals(1, spans.size)
        val width = spans.single().second - spans.single().first
        // Exact quadratic-height reasoning predicts two crossings inside the
        // outer x=0 and x=100 control hull; the control polygon spans 100.
        assertTrue(width in 60f..90f, "Curve ink width should exclude its controls: $width")
    }

    @Test
    fun offsetMovesSmoothHandlePairTogetherWithItsNode() {
        val original = ContourData(mutableListOf(
            Pt(0f, 0f, true, true),
            Pt(20f, 0f, false), Pt(80f, 0f, false),
            Pt(100f, 0f, true, true),
            Pt(100f, 20f, false), Pt(100f, 80f, false),
            Pt(100f, 100f, true, true),
            Pt(80f, 100f, false), Pt(20f, 100f, false),
            Pt(0f, 100f, true, true),
            Pt(0f, 80f, false), Pt(0f, 20f, false),
        ))
        val moved = offsetOutline(listOf(original), 5f, 5f).single().points
        for (node in listOf(0, 3, 6, 9)) {
            val p = original.points[node]
            val q = moved[node]
            for (arm in listOf((node + 1) % 12, (node + 11) % 12)) {
                val a = original.points[arm]
                val b = moved[arm]
                assertTrue(abs((b.x - a.x) - (q.x - p.x)) < 0.0001f)
                assertTrue(abs((b.y - a.y) - (q.y - p.y)) < 0.0001f)
            }
        }
    }

    @Test
    fun bulkProjectSwitchInvalidatesAutosaveGeneration() {
        val app = loaded()
        val before = app.autosaveEpoch
        app.beginBulkImport()
        assertEquals(before + 1, app.autosaveEpoch)
    }
}
