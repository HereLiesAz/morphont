package com.hereliesaz.morphont

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regressions from the editing-core audit: grouped undo, ghost redo, contour cancel, split nodes, mapping. */
class UndoAuditTest {
    private fun square(size: Float) = GlyphCorner(600f, mutableListOf(ContourData(mutableListOf(
        Pt(0f, 0f, true), Pt(size, 0f, true), Pt(size, size, true), Pt(0f, size, true),
    ))))

    @Test
    fun aMultiAnchorEditIsOneUndoStep() {
        val app = AppState()
        for (n in ANCHORS) app.anchors.getValue(n).loadFresh(square(100f))
        app.replaceAnchorsWithHistory(ANCHORS) { g -> g.deepCopy().also { it.contours[0].points[0].x = 7f } }
        assertTrue(ANCHORS.all { app.anchors.getValue(it).glyph.contours[0].points[0].x == 7f })
        app.undo()
        assertTrue(ANCHORS.all { app.anchors.getValue(it).glyph.contours[0].points[0].x == 0f }, "one Undo restores every anchor")
    }

    @Test
    fun ghostUndoStillWorksAfterRedo() {
        val app = AppState()
        app.loadGlyph("A", Glyph())
        app.addGhost("box", square(100f).contours)
        val g = app.ghosts.single()
        g.pushMatrixHistory(); g.matrix = translation(10f, 0f)
        g.pushMatrixHistory(); g.matrix = translation(20f, 0f)
        app.undo() // back to 10
        app.redo() // forward to 20
        assertEquals(translation(20f, 0f), app.ghosts.single().matrix)
        app.undo() // undoes the redo: 10
        app.undo() // the first move: identity -- this used to do nothing after a redo
        assertEquals(IDENTITY_MATRIX, app.ghosts.single().matrix)
        assertTrue(app.ghosts.single() === g, "redo updates the same layer")
    }

    @Test
    fun cancellingAnEmptyContourDropsOnlyItsOwnEntry() {
        val app = AppState()
        val a = app.anchors.getValue("regular")
        a.loadFresh(square(100f))
        a.startNewContour()
        // Something else records an edit of the anchor while the empty contour is still open.
        a.pushHistory()
        a.replaceGlyph(a.glyph.deepCopy().also { it.contours[0].points[0].x = 5f })
        a.finishContour()
        assertEquals(1, a.glyph.contours.size)
        assertEquals(5f, a.glyph.contours[0].points[0].x)
        app.undo()
        assertEquals(0f, a.glyph.contours[0].points[0].x, "the real edit is still undoable")
    }

    @Test
    fun anOffCentreSplitIsACornerSoItsArmsDoNotReMirror() {
        val c = ContourData(mutableListOf(
            Pt(0f, 0f, true), Pt(0f, 100f, false), Pt(100f, 200f, false), Pt(200f, 200f, true),
            Pt(200f, 0f, true),
        ))
        val (atThird, i3) = splitSegment(c, 0, 0.3f)!!
        assertFalse(atThird.points[i3].smooth)
        val (atHalf, i5) = splitSegment(c, 0, 0.5f)!!
        assertTrue(atHalf.points[i5].smooth)
        val n = atHalf.points[i5]; val inArm = atHalf.points[i5 - 1]; val outArm = atHalf.points[i5 + 1]
        assertEquals(hypot(n.x - inArm.x, n.y - inArm.y), hypot(outArm.x - n.x, outArm.y - n.y), 0.01f)
    }

    @Test
    fun mappingBeforeLayoutNeverDividesByZero() {
        val m = SpaceMapper(ViewBox(0f, 0f, 1000f, 1000f), Size.Zero)
        val p = m.toFont(Offset(10f, 10f))
        assertTrue(p.x.isFinite() && p.y.isFinite())
    }
}
