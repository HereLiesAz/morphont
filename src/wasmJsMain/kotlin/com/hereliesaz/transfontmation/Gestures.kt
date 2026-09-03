package com.hereliesaz.transfontmation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One anchor panel's full pointer-gesture handling: click-to-select,
 * drag-to-move, rubber-band select on empty space, and click-to-place
 * while a new contour is being drawn. Mirrors the
 * mousedown/mousemove/mouseup handling of the original browser tool this
 * was ported from.
 *
 * Known gap vs. the original: shift-click additive/toggle selection isn't
 * implemented here. `PointerEvent.keyboardModifiers.isShiftPressed`,
 * expected to carry this, didn't resolve against this Compose Multiplatform
 * version's wasmJs pointer-input API -- rather than guess at an
 * unconfirmed alternative, this drops shift-multi-select for now.
 * Rubber-band selection (drag from empty space) still covers most
 * multi-select needs.
 */
suspend fun PointerInputScope.handleAnchorGestures(
    state: AnchorState,
    vb: ViewBox,
    canvasSize: Size,
    onActivate: () -> Unit,
    onRubberUpdate: (Pair<Offset, Offset>?) -> Unit,
) {
    val mapper = SpaceMapper(vb, canvasSize)
    awaitEachGesture {
        val event = awaitPointerEvent()
        val down = event.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
        onActivate()
        val startPos = down.position

        // Draw mode: a single click places a new on-curve point; no drag needed.
        val drawingIndex = state.drawingContourIndex
        if (drawingIndex != null) {
            val hit = hitTestPoint(state.glyph, mapper, startPos)
            if (hit == null) {
                val font = mapper.toFont(startPos)
                state.addDrawPoint(font.x, font.y)
            }
            down.consume()
            drag(down.id) { change -> change.consume() }
            return@awaitEachGesture
        }

        val hitKey = hitTestPoint(state.glyph, mapper, startPos)
        if (hitKey != null) {
            if (hitKey !in state.selection) state.selection = setOf(hitKey)
            val origGlyph = state.glyph.deepCopy()
            val keys = state.selection.toList()
            var moved = false
            down.consume()
            drag(down.id) { change ->
                change.consume()
                val deltaCanvas = change.position - startPos
                if (abs(deltaCanvas.x) > 0.5f || abs(deltaCanvas.y) > 0.5f) moved = true
                val deltaFontX = deltaCanvas.x / mapper.scale
                val deltaFontY = -deltaCanvas.y / mapper.scale
                val updated = origGlyph.deepCopy()
                for ((ci, pi) in keys) {
                    val op = origGlyph.contours[ci].points[pi]
                    updated.contours[ci].points[pi] = Pt(op.x + deltaFontX, op.y + deltaFontY, op.onCurve, op.smooth)
                }
                state.replaceGlyph(updated)
            }
            if (moved) state.pushHistory()
        } else {
            state.selection = emptySet()
            var current = startPos
            down.consume()
            onRubberUpdate(startPos to current)
            drag(down.id) { change ->
                change.consume()
                current = change.position
                onRubberUpdate(startPos to current)
                val x0 = min(startPos.x, current.x)
                val x1 = max(startPos.x, current.x)
                val y0 = min(startPos.y, current.y)
                val y1 = max(startPos.y, current.y)
                val newSel = mutableSetOf<PointKey>()
                state.glyph.contours.forEachIndexed { ci, c ->
                    c.points.forEachIndexed { pi, p ->
                        val pos = mapper.toCanvas(p.x, p.y)
                        if (pos.x in x0..x1 && pos.y in y0..y1) newSel.add(ci to pi)
                    }
                }
                state.selection = newSel
            }
            onRubberUpdate(null)
        }
    }
}
