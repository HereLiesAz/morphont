package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs

// The outline, guide lines and background stay neutral; on-curve/off-curve/selected points use
// Theme.kt's Primary/Secondary roles, so the one thing selected right now is also the one thing
// colored -- Conveyance's "contrasting tone prioritizes implicitly" rule applied to the canvas.
private val bgColor = Mono.ground
private val outlineFill = Mono.ink.copy(alpha = 0.55f)
private val outlineStroke = Mono.inkDim
private val baselineColor = Color(0xFF2A2A2A)
private val ctrlLineColor = Color(0xFF4A4A4A)
private val onCurveColor = Mono.primary
private val offCurveColor = Mono.secondary
private val selectedFill = Mono.primary
private val selectedRing = Mono.ground
private val rubberFill = Mono.primary.copy(alpha = 0.15f)
private val rubberStroke = Mono.primary
private val pathCurveColor = Mono.secondary.copy(alpha = 0.8f)

/**
 * One anchor's editable canvas: renders the outline, control-point handles
 * and (for Regular) the travel-path overlay, and handles all pointer
 * interaction -- click/select, shift-click multi-select, drag-to-move,
 * rubber-band select on empty space, and click-to-place while a new
 * contour is being drawn.
 */
@Composable
fun AnchorCanvas(
    anchorName: String,
    state: AnchorState,
    isActive: Boolean,
    onActivate: () -> Unit,
    travelPathOverlay: TravelPathOverlay? = null,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var rubberRect by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    val vb = remember(state.glyph) { computeViewBox(state.glyph) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state, vb, canvasSize) {
                if (canvasSize.width <= 0f || canvasSize.height <= 0f) return@pointerInput
                handleAnchorGestures(
                    state = state,
                    vb = vb,
                    canvasSize = canvasSize,
                    onActivate = onActivate,
                    onRubberUpdate = { rubberRect = it },
                )
            },
    ) {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f) return@Canvas
        val mapper = SpaceMapper(vb, canvasSize)
        val map: (Float, Float) -> Offset = { x, y -> mapper.toCanvas(x, y) }

        drawRect(bgColor, size = size)

        // baseline
        drawLine(baselineColor, map(-10000f, 0f), map(10000f, 0f), strokeWidth = 1f)

        // travel-path overlay (Regular panel only). A quadratic Bezier does
        // NOT pass through its own control point -- to draw the curve that
        // actually passes through Regular's point (r) at the midpoint, the
        // real control point has to be derived via bezierControlFor(), not
        // r itself.
        travelPathOverlay?.let { overlay ->
            for ((a, r, b) in overlay.segments) {
                val controlX = bezierControlFor(a.x, b.x, r.x)
                val controlY = bezierControlFor(a.y, b.y, r.y)
                val ca = map(a.x, a.y)
                val cControl = map(controlX, controlY)
                val cb = map(b.x, b.y)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(ca.x, ca.y)
                    quadraticTo(cControl.x, cControl.y, cb.x, cb.y)
                }
                drawPath(
                    path,
                    color = pathCurveColor,
                    style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
                )
                drawCircle(pathCurveColor, radius = 3f, center = ca, style = Stroke(width = 1f))
                drawCircle(pathCurveColor, radius = 3f, center = cb, style = Stroke(width = 1f))
            }
        }

        // filled outline
        val outlinePath = buildOutlinePath(state.glyph.contours, map)
        drawPath(outlinePath, color = outlineFill)
        drawPath(outlinePath, color = outlineStroke, style = Stroke(width = 1f))

        // control-point dashed lines + handles
        state.glyph.contours.forEachIndexed { ci, c ->
            val n = c.points.size
            c.points.forEachIndexed { pi, p ->
                val next = c.points[(pi + 1) % n]
                if (!p.onCurve || !next.onCurve) {
                    drawLine(ctrlLineColor, map(p.x, p.y), map(next.x, next.y), strokeWidth = 1f)
                }
            }
            c.points.forEachIndexed { pi, p ->
                val selected = (ci to pi) in state.selection
                val center = map(p.x, p.y)
                if (selected) {
                    // A "target" mark -- filled, with a punched-out ring -- instead of a
                    // third hue, so selection reads as a state change, not a new color.
                    val radius = if (p.onCurve) 6f else 4.5f
                    drawCircle(selectedFill, radius = radius, center = center)
                    drawCircle(selectedRing, radius = radius * 0.5f, center = center)
                } else if (p.onCurve) {
                    drawCircle(onCurveColor, radius = 5f, center = center)
                    drawCircle(bgColor, radius = 5f, center = center, style = Stroke(width = 0.6f))
                } else {
                    drawCircle(offCurveColor, radius = 3.5f, center = center, style = Stroke(width = 1.5f))
                }
            }
        }

        rubberRect?.let { (start, current) ->
            val x0 = minOf(start.x, current.x)
            val y0 = minOf(start.y, current.y)
            val w = abs(current.x - start.x)
            val h = abs(current.y - start.y)
            drawRect(rubberFill, topLeft = Offset(x0, y0), size = Size(w, h))
            drawRect(rubberStroke, topLeft = Offset(x0, y0), size = Size(w, h), style = Stroke(width = 1f))
        }
    }
}
