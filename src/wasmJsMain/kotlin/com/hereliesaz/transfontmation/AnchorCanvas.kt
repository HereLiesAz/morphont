package com.hereliesaz.transfontmation

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

private val bgColor = Color(0xFF111111)
private val outlineFill = Color(0xFFDDDDDD).copy(alpha = 0.55f)
private val outlineStroke = Color(0xFF888888)
private val baselineColor = Color(0xFF224422)
private val ctrlLineColor = Color(0xFF555555)
private val onCurveColor = Color(0xFF4EA1FF)
private val offCurveColor = Color(0xFFFF9D4E)
private val selectedColor = Color(0xFFFFE14E)
private val rubberFill = Color(0xFF4EA1FF).copy(alpha = 0.15f)
private val rubberStroke = Color(0xFF4EA1FF)
private val pathCurveColor = Color(0xFF4EC9FF).copy(alpha = 0.65f)

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
                val color = if (selected) selectedColor else if (p.onCurve) onCurveColor else offCurveColor
                if (p.onCurve) {
                    drawCircle(color, radius = 5f, center = center)
                    drawCircle(Color.White, radius = 5f, center = center, style = Stroke(width = 0.6f))
                } else {
                    drawCircle(color, radius = 3.5f, center = center, style = Stroke(width = 1.5f))
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
