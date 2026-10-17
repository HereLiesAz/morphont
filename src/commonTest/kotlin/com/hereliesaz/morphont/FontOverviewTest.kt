package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontOverviewTest {
    @Test
    fun crossCircleDefaultsToTouchAndCanBeOverriddenOnEveryInterface() {
        val app = AppState()
        assertFalse(app.nodeMoveCircleEnabled)
        app.touchInput = true
        assertTrue(app.nodeMoveCircleEnabled)
        app.touchInput = false
        assertFalse(app.nodeMoveCircleEnabled)
        app.setNodeMoveCircleEnabled(true)
        assertTrue(app.nodeMoveCircleEnabled)
        app.touchInput = true
        app.setNodeMoveCircleEnabled(false)
        assertFalse(app.nodeMoveCircleEnabled)
    }

    @Test
    fun glyphPreviewIsIndependentUntilAppliedToAll() {
        val app = AppState()
        assertEquals(0.5f, app.fontValuesFor("A").getValue("wght"))
        assertEquals(0.5f, app.fontValuesFor("B").getValue("wght"))
        app.setGlyphPreviewAxis("A", "wght", 0.83f)
        assertEquals(0.83f, app.fontValuesFor("A").getValue("wght"))
        assertEquals(0.5f, app.fontValuesFor("B").getValue("wght"))
        app.applyGlyphPreviewToAll("A")
        assertEquals(0.83f, app.fontValuesFor("B").getValue("wght"))
        assertTrue(app.glyphPreviewValues.isEmpty())
        app.setGlyphPreviewAxis("A", "wdth", 0.1f)
        app.setFontPreviewAxis("wght", 0.25f)
        assertTrue(app.glyphPreviewValues.isEmpty())
        assertEquals(0.25f, app.fontValuesFor("A").getValue("wght"))
        assertEquals(0.5f, app.fontValuesFor("A").getValue("wdth"))
    }

    private fun glyph(): Glyph {
        val corners = ANCHORS.associateWith { name ->
            val x = when (name) {
                Axis.WEIGHT.lo -> -20f
                Axis.WEIGHT.hi -> 40f
                else -> 0f
            }
            GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
                Pt(x, 0f, true), Pt(x, 100f, true),
                Pt(x + 100f, 100f, true), Pt(x + 100f, 0f, true),
            ))))
        }.toMutableMap()
        return Glyph(corners = corners, outlineFormat = OUTLINE_CUBIC)
    }

    @Test
    fun ghostOwnAxisOverridesChangeOnlyTheGhostAndSurviveReload() {
        val app = AppState()
        app.loadGlyph("A", glyph())
        app.addLinkedGhost("A")
        val layer = app.ghosts.single()
        val regular = app.anchors.getValue("regular").glyph.contours.single().points.first()
        assertEquals(0f, regular.x)
        assertEquals(0f, app.ghostContours(layer, "regular").single().points.first().x)

        app.setGhostAxis(layer.id, Axis.WEIGHT, 1f)
        assertEquals(40f, app.ghostContours(layer, "regular").single().points.first().x)
        assertEquals(0f, app.anchors.getValue("regular").glyph.contours.single().points.first().x)

        val decoded = ProjectCodec.decodeGlyph(ProjectCodec.encodeGlyph(app.toGlyph()))
        val reopened = AppState()
        reopened.loadGlyph("A", decoded)
        val restored = reopened.ghosts.single()
        assertEquals(1f, restored.axisOverrides.getValue("wght"))
        assertEquals(40f, reopened.ghostContours(restored, "regular").single().points.first().x)
        reopened.setGhostAxis(restored.id, Axis.WEIGHT, null)
        assertEquals(0f, reopened.ghostContours(restored, "regular").single().points.first().x)
    }

    @Test
    fun embeddedReferenceMastersAndAxesSurviveProjectRoundTrip() {
        val source = glyph().corners.toMap()
        val ghost = GhostData(
            label = "B · sample", referenceCorners = source,
            supportedAxes = listOf("wght"), axisOverrides = mapOf("wght" to 1f),
        )
        val g = glyph().copy(ghosts = listOf(ghost))
        val decoded = ProjectCodec.decodeGlyph(ProjectCodec.encodeGlyph(g))
        val restored = decoded.ghosts.single()
        assertTrue(restored.referenceCorners.containsKey("regular"))
        assertEquals(listOf("wght"), restored.supportedAxes)
        assertEquals(1f, restored.axisOverrides.getValue("wght"))
        val app = AppState()
        app.loadGlyph("A", decoded)
        assertEquals(40f, app.ghostContours(app.ghosts.single(), "regular").single().points.first().x)
        // Unsupported axes are ignored rather than inventing glyph geometry.
        app.setGhostAxis(app.ghosts.single().id, Axis.WIDTH, 0f)
        assertFalse(app.ghosts.single().axisOverrides.containsKey("wdth"))
    }
}
