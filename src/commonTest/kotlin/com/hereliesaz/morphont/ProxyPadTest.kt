package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyPadTest {
    private val canvas = Size(400f, 800f)

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
