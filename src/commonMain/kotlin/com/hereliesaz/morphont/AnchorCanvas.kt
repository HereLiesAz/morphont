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
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

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
 * Shared editable canvas. [interactionScale] is 1 for the web/mouse surface
 * and larger on Android; all handles and hit targets are also density-aware.
 * Touch surfaces additionally get real gesture slop so sub-finger jitter does
 * not become a drag or rubber-band selection.
 */
@Composable
fun AnchorCanvas(
    anchorName: String,
    state: AnchorState,
    isActive: Boolean,
    onActivate: () -> Unit,
    travelPathOverlay: TravelPathOverlay? = null,
    interactionScale: Float = 1f,
    onPointHit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var rubberRect by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val density = LocalDensity.current.density
    val uiScale = density * interactionScale
    val hitRadiusPx = 12f * uiScale
    val isTouchSurface = interactionScale > 1f
    val dragStartSlopPx = if (isTouchSurface) 6f * density else 0.5f
    val rubberBandSlopPx = if (isTouchSurface) 8f * density else 0.5f

    val vb = remember(state.glyph) { computeViewBox(state.glyph) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state, vb, canvasSize, hitRadiusPx, dragStartSlopPx, rubberBandSlopPx) {
                if (canvasSize.width <= 0f || canvasSize.height <= 0f) return@pointerInput
                handleAnchorGestures(
                    state = state,
                    vb = vb,
                    canvasSize = canvasSize,
                    hitRadiusPx = hitRadiusPx,
                    dragStartSlopPx = dragStartSlopPx,
                    rubberBandSlopPx = rubberBandSlopPx,
                    onActivate = onActivate,
                    onPointHit = onPointHit,
                    onRubberUpdate = { rubberRect = it },
                )
            },
    ) {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f) return@Canvas
        val mapper = SpaceMapper(vb, canvasSize)
        val map: (Float, Float) -> Offset = { x, y -> mapper.toCanvas(x, y) }

        drawRect(bgColor, size = size)
        drawLine(baselineColor, map(-10000f, 0f), map(10000f, 0f), strokeWidth = uiScale.coerceAtLeast(1f))

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
                    style = Stroke(
                        width = uiScale.coerceAtLeast(1f),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * uiScale, 3f * uiScale)),
                    ),
                )
                drawCircle(pathCurveColor, radius = 3f * uiScale, center = ca, style = Stroke(width = uiScale))
                drawCircle(pathCurveColor, radius = 3f * uiScale, center = cb, style = Stroke(width = uiScale))
            }
        }

        val outlinePath = buildOutlinePath(state.glyph.contours, map)
        drawPath(outlinePath, color = outlineFill)
        drawPath(outlinePath, color = outlineStroke, style = Stroke(width = uiScale.coerceAtLeast(1f)))

        state.glyph.contours.forEachIndexed { ci, c ->
            val n = c.points.size
            c.points.forEachIndexed { pi, p ->
                val next = c.points[(pi + 1) % n]
                if (!p.onCurve || !next.onCurve) {
                    drawLine(ctrlLineColor, map(p.x, p.y), map(next.x, next.y), strokeWidth = uiScale.coerceAtLeast(1f))
                }
            }
            c.points.forEachIndexed { pi, p ->
                val selected = (ci to pi) in state.selection
                val center = map(p.x, p.y)
                if (selected) {
                    val radius = (if (p.onCurve) 6f else 4.5f) * uiScale
                    drawCircle(selectedFill, radius = radius, center = center)
                    drawCircle(selectedRing, radius = radius * 0.5f, center = center)
                } else if (p.onCurve) {
                    drawCircle(onCurveColor, radius = 5f * uiScale, center = center)
                    drawCircle(bgColor, radius = 5f * uiScale, center = center, style = Stroke(width = uiScale.coerceAtLeast(1f)))
                } else {
                    drawCircle(offCurveColor, radius = 3.5f * uiScale, center = center, style = Stroke(width = 1.5f * uiScale))
                }
            }
        }

        rubberRect?.let { (start, current) ->
            val x0 = minOf(start.x, current.x)
            val y0 = minOf(start.y, current.y)
            val w = abs(current.x - start.x)
            val h = abs(current.y - start.y)
            drawRect(rubberFill, topLeft = Offset(x0, y0), size = Size(w, h))
            drawRect(rubberStroke, topLeft = Offset(x0, y0), size = Size(w, h), style = Stroke(width = uiScale.coerceAtLeast(1f)))
        }
    }
}
