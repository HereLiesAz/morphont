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
    fun crossCircleMovesExactlyWhatIsSelected() {
        val glyph = GlyphCorner(500f, mutableListOf(ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 100f, true),
            Pt(100f, 100f, true), Pt(100f, 0f, true),
        ))))
        val state = AnchorState(glyph)
        val previous = state.glyph.contours.single().points
        // A selected handle moves by itself: the node and every other point stay put.
        val handle = 1 // node 0's out-arm
        assertTrue(!previous[handle].onCurve)
        val moved = dragPoints(state.glyph, listOf(0 to handle), 17f, -9f).contours.single().points
        assertEquals(previous[handle].x + 17f, moved[handle].x)
        assertEquals(previous[handle].y - 9f, moved[handle].y)
        assertEquals(previous[0].x, moved[0].x)
        assertEquals(previous[0].y, moved[0].y)
        for (i in previous.indices.filter { it != handle && !previous[it].onCurve }) {
            assertEquals(previous[i].x, moved[i].x, "handle $i must not move")
            assertEquals(previous[i].y, moved[i].y, "handle $i must not move")
        }
        // A selected node carries its own two handles and nothing else.
        val carried = dragPoints(state.glyph, listOf(0 to 0), 17f, -9f).contours.single().points
        for (i in listOf(0, 1, 11)) {
            assertEquals(previous[i].x + 17f, carried[i].x)
            assertEquals(previous[i].y - 9f, carried[i].y)
        }
        for (i in listOf(3, 4, 5, 6, 7, 8, 9)) {
            assertEquals(previous[i].x, carried[i].x)
            assertEquals(previous[i].y, carried[i].y)
        }
    }

    private val radius = 34f // touch radius (26 * 1.3)
    private fun clearOf(pad: Offset, points: List<Offset>) = points.none { (it - pad).getDistance() < radius }

    @Test
    fun circleGoesOutwardFromTheGlyphCentre() {
        val center = Offset(200f, 400f)
        val node = Offset(300f, 400f) // right of centre
        val pad = placeMoveCircle(node, center, listOf(center, node), canvas, 1f, radius)
        assertTrue(pad.x > node.x, "expected the circle to the right of the node, was $pad")
        assertTrue(kotlin.math.abs(pad.y - 400f) < 1f, "straight out, was $pad")
        // Below the node for a node below centre, above for one above.
        assertTrue(placeMoveCircle(Offset(200f, 500f), center, listOf(center), canvas, 1f, radius).y > 500f)
        assertTrue(placeMoveCircle(Offset(200f, 300f), center, listOf(center), canvas, 1f, radius).y < 300f)
    }

    @Test
    fun circleGoesAsFarOutAsItTakesToClearEveryOtherPoint() {
        val center = Offset(100f, 400f)
        val node = Offset(200f, 400f)
        // A run of points straight out from the node: the circle must sit beyond all of them.
        val wall = (1..8).map { Offset(200f + it * 20f, 400f) }
        val pad = placeMoveCircle(node, center, wall + node, canvas, 1f, radius)
        assertTrue(clearOf(pad, wall + node), "no other point may be under the circle, was $pad")
        assertTrue(pad.x > 200f + 8 * 20f - 1f || kotlin.math.abs(pad.y - 400f) > 1f)
    }

    @Test
    fun aCrowdedStraightLineMakesItTryNearbyOutwardAngles() {
        val center = Offset(100f, 400f)
        val node = Offset(200f, 400f)
        val blockers = (1..30).map { Offset(200f + it * 6f, 400f) } // blocks straight out for a long way
        val pad = placeMoveCircle(node, center, blockers + node, canvas, 1f, radius)
        assertTrue(clearOf(pad, blockers + node))
        assertTrue(pad.x > node.x, "still outward, was $pad")
    }

    @Test
    fun circleStaysOnTheCanvasAndOutFromUnderTheFloatingRows() {
        val pad = placeMoveCircle(Offset(380f, 60f), Offset(200f, 400f), listOf(Offset(380f, 60f)), canvas, 1f, radius, coveredTop = 150f, coveredBottom = 120f)
        assertTrue(pad.x in 0f..400f && pad.y in 150f..680f, "was $pad")
    }

    @Test
    fun whenNothingIsClearItCoversTheFewestPoints() {
        val dense = (0..80).flatMap { x -> (0..160).map { y -> Offset(x * 5f, y * 5f) } }
        val pad = placeMoveCircle(Offset(200f, 400f), Offset(100f, 300f), dense, canvas, 1f, radius)
        assertTrue(pad.x in 0f..400f && pad.y in 0f..800f)
    }
}
