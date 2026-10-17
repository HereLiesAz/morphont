package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyPadTest {
    private val canvas = Size(400f, 800f)

    @Test
    fun singleNodeAlwaysMovesRegardlessOfStalePadMode() {
        assertEquals(PadMode.MOVE, effectivePadMode(PadMode.SCALE, 1))
        assertEquals(PadMode.MOVE, effectivePadMode(PadMode.ROTATE, 1))
        assertEquals(PadMode.SCALE, effectivePadMode(PadMode.SCALE, 2))
        assertEquals(PadMode.ROTATE, effectivePadMode(PadMode.ROTATE, 3))
    }

    @Test
    fun nodeMoveHitTestResolvesAnOverlappingHandleToItsOwner() {
        val state = AnchorState(GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 90f, true),
            Pt(90f, 90f, true), Pt(90f, 0f, true),
        )))))
        val mapper = SpaceMapper(ViewBox(0f, 0f, 100f, 100f), Size(100f, 100f))
        val handlePosition = Offset(0f, 70f)
        assertEquals(0 to 1, hitTestPoint(state.glyph, mapper, handlePosition, 8f))
        assertEquals(0 to 0, hitTestPoint(state.glyph, mapper, handlePosition, 8f, nodeOnly = true))
    }

    @Test
    fun circleAndNearbyAreaBelongExclusivelyToNodeMove() {
        val center = Offset(100f, 120f)
        assertTrue(hitsNodeMoveCircle(Offset(100f, 120f), center, 26f))
        assertTrue(hitsNodeMoveCircle(Offset(133f, 120f), center, 26f))
        assertFalse(hitsNodeMoveCircle(Offset(140f, 120f), center, 26f))
        assertFalse(hitsNodeMoveCircle(Offset(100f, 120f), null, 26f))
    }

    @Test
    fun crossCircleMovesOwningNodeAndBothHandlesNotAnotherNode() {
        val app = AppState()
        val glyph = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 100f, true),
            Pt(100f, 100f, true), Pt(100f, 0f, true),
        ))))
        val state = app.anchors.getValue("regular")
        state.loadFresh(glyph)
        // A handle is selected; the cross-circle must still move its owner,
        // not reshape the selected handle or act as a scale/rotation control.
        state.selection = setOf(0 to 1)
        app.padMode = PadMode.ROTATE
        val keys = state.selectedNodes()
        assertEquals(setOf(0 to 0), keys)
        val before = state.glyph
        val after = dragPoints(before, keys, 17f, -9f).contours.single().points
        val previous = before.contours.single().points
        for (i in listOf(0, 1, 11)) {
            assertEquals(previous[i].x + 17f, after[i].x)
            assertEquals(previous[i].y - 9f, after[i].y)
        }
        for (i in listOf(3, 4, 5, 6, 7, 8, 9)) {
            assertEquals(previous[i].x, after[i].x)
            assertEquals(previous[i].y, after[i].y)
        }
    }

    @Test
    fun padSitsBelowTheSelectionWhenThereIsRoom() {
        val pad = proxyPadCenter(200f, 300f, 400f, canvas, density = 1f)
        assertEquals(476f, pad.y)
    }

    @Test
    fun padNeverLandsUnderTheBottomRows() {
        // 200px of bottom cards: below the selection would be covered, so the pad goes above.
        val pad = proxyPadCenter(200f, 300f, 600f, canvas, density = 1f, coveredBottom = 200f)
        assertEquals(224f, pad.y)
    }

    @Test
    fun padNeverLandsUnderTheTopRows() {
        // Selection near the top with no room below it: clamp below the 150px of top rows.
        val pad = proxyPadCenter(200f, 40f, 760f, canvas, density = 1f, coveredTop = 150f)
        assertTrue(pad.y >= 150f + 36f)
        assertTrue(pad.y <= 800f - 36f)
    }

    @Test
    fun coveredRowsThatLeaveNoRoomFallBackToTheCanvasEdges() {
        val pad = proxyPadCenter(200f, 300f, 400f, canvas, density = 1f, coveredTop = 500f, coveredBottom = 500f)
        assertEquals(476f, pad.y)
    }
}
