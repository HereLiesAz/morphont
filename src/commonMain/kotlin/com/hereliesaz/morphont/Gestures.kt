package com.hereliesaz.morphont

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun Offset.exceedsSlop(slopPx: Float): Boolean =
    abs(x) >= slopPx || abs(y) >= slopPx

/**
 * Pointer handling shared by wasm and Android. [hitRadiusPx] and gesture slop
 * are supplied by the rendering surface so a mouse can stay precise while a
 * finger gets humane hit targets without turning tiny hand tremors into edits.
 */
suspend fun PointerInputScope.handleAnchorGestures(
    state: AnchorState,
    vb: ViewBox,
    canvasSize: Size,
    hitRadiusPx: Float = 12f,
    dragStartSlopPx: Float = 0.5f,
    rubberBandSlopPx: Float = 0.5f,
    onActivate: () -> Unit,
    onPointHit: () -> Unit = {},
    onRubberUpdate: (Pair<Offset, Offset>?) -> Unit,
) {
    val mapper = SpaceMapper(vb, canvasSize)
    awaitEachGesture {
        val event = awaitPointerEvent()
        val down = event.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
        onActivate()
        val startPos = down.position

        val drawingIndex = state.drawingContourIndex
        if (drawingIndex != null) {
            val hit = hitTestPoint(state.glyph, mapper, startPos, radiusPx = hitRadiusPx)
            if (hit == null) {
                val font = mapper.toFont(startPos)
                state.addDrawPoint(font.x, font.y)
            } else {
                onPointHit()
            }
            down.consume()
            drag(down.id) { change -> change.consume() }
            return@awaitEachGesture
        }

        val hitKey = hitTestPoint(state.glyph, mapper, startPos, radiusPx = hitRadiusPx)
        if (hitKey != null) {
            onPointHit()
            if (hitKey !in state.selection) state.selection = setOf(hitKey)
            val origGlyph = state.glyph.deepCopy()
            val keys = state.selection.toList()
            var dragging = false
            down.consume()
            drag(down.id) { change ->
                change.consume()
                val deltaCanvas = change.position - startPos
                if (!dragging && deltaCanvas.exceedsSlop(dragStartSlopPx)) {
                    dragging = true
                }
                if (!dragging) return@drag

                val deltaFontX = deltaCanvas.x / mapper.scale
                val deltaFontY = -deltaCanvas.y / mapper.scale
                val updated = origGlyph.deepCopy()
                for ((ci, pi) in keys) {
                    val op = origGlyph.contours[ci].points[pi]
                    updated.contours[ci].points[pi] = Pt(
                        op.x + deltaFontX,
                        op.y + deltaFontY,
                        op.onCurve,
                        op.smooth,
                    )
                }
                state.replaceGlyph(updated)
            }
            if (dragging) state.pushHistory()
        } else {
            state.selection = emptySet()
            var current = startPos
            var rubberBanding = false
            down.consume()
            drag(down.id) { change ->
                change.consume()
                current = change.position
                val deltaCanvas = current - startPos
                if (!rubberBanding && deltaCanvas.exceedsSlop(rubberBandSlopPx)) {
                    rubberBanding = true
                    onRubberUpdate(startPos to current)
                }
                if (!rubberBanding) return@drag

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
            if (rubberBanding) onRubberUpdate(null)
        }
    }
}
