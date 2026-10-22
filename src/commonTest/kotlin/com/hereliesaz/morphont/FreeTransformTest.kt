package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Free Transform Points: the box around selected nodes, with Photoshop's handles and modifiers. */
class FreeTransformTest {
    private val box = FontRect(0f, 0f, 100f, 50f)
    private fun apply(t: Affine, x: Float, y: Float) = (t[0] * x + t[2] * y + t[4]) to (t[1] * x + t[3] * y + t[5])
    private fun near(a: Pair<Float, Float>, x: Float, y: Float) = abs(a.first - x) < 1e-3f && abs(a.second - y) < 1e-3f

    @Test fun aCornerScalesAboutTheOppositeCorner() {
        // corner 2 is maxX/maxY; dragging it from (100,50) to (200,100) doubles about (0,0)
        val t = selectionBoxTransform(box, BoxGrab.Corner(2), Offset(100f, 50f), Offset(200f, 100f), false, false, false)
        assertTrue(near(apply(t, 100f, 50f), 200f, 100f)); assertTrue(near(apply(t, 0f, 0f), 0f, 0f))
    }

    @Test fun shiftKeepsTheProportionsOfACornerDrag() {
        val t = selectionBoxTransform(box, BoxGrab.Corner(2), Offset(100f, 50f), Offset(200f, 55f), true, false, false)
        assertEquals(t[0], t[3], 1e-4f)
        assertEquals(2f, t[0], 1e-4f)
    }

    @Test fun altScalesAboutTheCentre() {
        val t = selectionBoxTransform(box, BoxGrab.Corner(2), Offset(100f, 50f), Offset(150f, 75f), false, true, false)
        assertTrue(near(apply(t, 50f, 25f), 50f, 25f), "the centre stays put")
        assertTrue(near(apply(t, 100f, 50f), 150f, 75f))
    }

    @Test fun anEdgeScalesOnlyAlongItsAxis() {
        // edge 2 is the right edge: stretch x about minX, leave y alone
        val t = selectionBoxTransform(box, BoxGrab.Edge(2), Offset(100f, 25f), Offset(150f, 90f), false, false, false)
        assertTrue(near(apply(t, 100f, 30f), 150f, 30f))
        assertTrue(near(apply(t, 0f, 30f), 0f, 30f))
        // edge 1 is the top: stretch y about minY
        val u = selectionBoxTransform(box, BoxGrab.Edge(1), Offset(50f, 50f), Offset(70f, 100f), false, false, false)
        assertTrue(near(apply(u, 30f, 50f), 30f, 100f)); assertTrue(near(apply(u, 30f, 0f), 30f, 0f))
    }

    @Test fun skewSlidesAnEdgeSidewaysAndKeepsTheOppositeEdgeStill() {
        // top edge dragged 10 to the right: the top moves, the bottom stays
        val t = selectionBoxTransform(box, BoxGrab.Edge(1), Offset(50f, 50f), Offset(60f, 50f), false, false, true)
        assertTrue(near(apply(t, 20f, 50f), 30f, 50f), "top moved by 10")
        assertTrue(near(apply(t, 20f, 0f), 20f, 0f), "bottom stayed")
        // right edge dragged 10 up: the right moves, the left stays
        val u = selectionBoxTransform(box, BoxGrab.Edge(2), Offset(100f, 25f), Offset(100f, 35f), false, false, true)
        assertTrue(near(apply(u, 100f, 20f), 100f, 30f), "right moved by 10")
        assertTrue(near(apply(u, 0f, 20f), 0f, 20f), "left stayed")
    }

    @Test fun rotationTurnsAboutTheCentreAndShiftSnapsToFifteenDegrees() {
        val t = selectionBoxTransform(box, BoxGrab.Rotate, Offset(150f, 25f), Offset(50f, 125f), false, false, false)
        assertTrue(near(apply(t, 100f, 25f), 50f, 75f), "quarter turn about (50,25)")
        // 20 degrees snaps to 15
        val a = 20f * kotlin.math.PI.toFloat() / 180f
        val s = selectionBoxTransform(box, BoxGrab.Rotate, Offset(150f, 25f), Offset(50f + 100f * kotlin.math.cos(a), 25f + 100f * kotlin.math.sin(a)), true, false, false)
        val d = 15f * kotlin.math.PI.toFloat() / 180f
        assertEquals(kotlin.math.cos(d), s[0], 1e-3f); assertEquals(kotlin.math.sin(d), s[1], 1e-3f)
    }

    @Test fun aDegenerateBoxDoesNotProduceNaN() {
        val flat = FontRect(0f, 10f, 100f, 10f)
        val t = selectionBoxTransform(flat, BoxGrab.Edge(1), Offset(50f, 10f), Offset(50f, 30f), false, false, false)
        assertTrue(t.all { it.isFinite() })
    }
}
