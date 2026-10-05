package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val bgColor = Mono.ground
private val glowColor = Color(0xFF161616)
private val outlineFill = Color(0xFFD9D9D9).copy(alpha = 0.92f)
private val outlineStroke = Color(0xFFD9D9D9)
private val baselineColor = Color(0xFF2C2C2C)
private val metricColor = Color(0xFF1C1C1C)
private val gridColor = Color(0xFF121212)
private val ctrlLineColor = Color(0xFF3A3A3A)
private val nodeRing = Color(0xFFBDBDBD)
private val offCurveColor = Color(0xFF7A7A7A)
private val selectedFill = Color.White
private val rubberFill = Color.White.copy(alpha = 0.06f)
private val rubberStroke = Color.White.copy(alpha = 0.6f)
private val pathCurveColor = Color(0xFF6A6A6A)
private val guideColor = Color.White.copy(alpha = 0.35f)
private val snapColor = Color.White
private val ghostFill = Color.White.copy(alpha = 0.04f)
private val ghostStroke = Color(0xFF5A5A5A)
private val ghostActive = Color(0xFFBDBDBD)
private val rulerBg = Color(0xFF0A0A0A)
private val measureColor = Color(0xFF9A9A9A)
private val labelFamily = androidx.compose.ui.text.font.FontFamily.Monospace

/** A "nice" ruler/grid step (1, 2 or 5 x 10^n font units) at least [minPx] wide on screen. */
private fun niceStep(scale: Float, minPx: Float): Float {
    val raw = minPx / scale
    val mag = 10f.pow(floor(log10(raw)))
    for (m in listOf(1f, 2f, 5f, 10f)) if (m * mag >= raw) return m * mag
    return 10f * mag
}

/**
 * Shared editable canvas. [interactionScale] is 1 for the web/mouse surface
 * and larger on Android; all handles and hit targets are also density-aware.
 * Touch surfaces additionally get real gesture slop so sub-finger jitter does
 * not become a drag or rubber-band selection.
 *
 * Draws, back to front: grid, metric lines, ghosts, the travel-path overlay,
 * a node-reduction preview, the outline, measurements, guides, snap lines,
 * nodes, the active ghost's transform box, the touch proxy pad, and rulers.
 */
@Composable
fun AnchorCanvas(
    anchorName: String,
    app: AppState,
    travelPathOverlay: TravelPathOverlay? = null,
    interactionScale: Float = 1f,
    onPointHit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val feedback = remember { CanvasFeedback() }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val uiScale = density * interactionScale
    val isTouchSurface = interactionScale > 1f
    val hitRadiusPx = 12f * uiScale
    val rulerPx = 18f * density
    val proxyRadiusPx = 26f * density

    val anchor = app.anchors.getValue(anchorName)
    val target = app.editTarget(anchorName)
    val ghost = app.activeGhost
    val view = app.view
    val metrics = app.metrics
    val advance = anchor.glyph.width
    val vbFor = { z: Float, px: Float, py: Float -> editorViewBox(metrics, advance, z, px, py) }
    val vb = vbFor(view.zoom, view.panX, view.panY)

    val showProxy = (app.touchInput || isTouchSurface) && target.selection.isNotEmpty() &&
        target.drawingContourIndex == null && app.reduction == null && !(ghost != null && app.ghostTransformMode)

    fun frame(): CanvasFrame? {
        if (canvasSize.width <= 0f || canvasSize.height <= 0f) return null
        val mapper = SpaceMapper(vb, canvasSize)
        val proxy = if (!showProxy) null else boundsOfSelection(target.glyph, target.selection)?.let { b ->
            val tl = mapper.toCanvas(b.minX, b.maxY)
            val br = mapper.toCanvas(b.maxX, b.minY)
            proxyPadCenter((tl.x + br.x) / 2f, tl.y, br.y, canvasSize, density)
        }
        val handles = if (ghost != null && app.ghostTransformMode && ghost.visible) {
            boundsOfContours(app.ghostContours(ghost, anchorName))?.let { b ->
                val corners = listOf(
                    mapper.toCanvas(b.minX, b.minY), mapper.toCanvas(b.minX, b.maxY),
                    mapper.toCanvas(b.maxX, b.maxY), mapper.toCanvas(b.maxX, b.minY),
                )
                val top = mapper.toCanvas(b.cx, b.maxY)
                GhostHandles(corners, Offset(top.x, top.y - 30f * uiScale), b)
            }
        } else null
        val selHandles = if (target.selection.size >= 2 && target.drawingContourIndex == null && app.reduction == null &&
            !(ghost != null && app.ghostTransformMode)
        ) {
            boundsOfSelection(target.glyph, target.selection)?.let { b ->
                // Handles sit just outside the box so they never land on a point.
                val pad = 12f * uiScale
                val tl = mapper.toCanvas(b.minX, b.maxY); val br = mapper.toCanvas(b.maxX, b.minY)
                val l = tl.x - pad; val t = tl.y - pad; val r = br.x + pad; val btm = br.y + pad
                GhostHandles(
                    listOf(Offset(l, btm), Offset(l, t), Offset(r, t), Offset(r, btm)),
                    Offset((l + r) / 2f, t - 26f * uiScale),
                    b,
                )
            }
        } else null
        return CanvasFrame(
            app = app,
            anchorName = anchorName,
            canvasSize = canvasSize,
            mapper = mapper,
            mapperFor = { z, px, py -> SpaceMapper(vbFor(z, px, py), canvasSize) },
            density = density,
            hitRadiusPx = hitRadiusPx,
            dragStartSlopPx = if (isTouchSurface || app.touchInput) 6f * density else 0.5f,
            rubberBandSlopPx = if (isTouchSurface || app.touchInput) 8f * density else 0.5f,
            rulerPx = if (view.showRulers) rulerPx else 0f,
            proxyCenter = proxy,
            proxyRadiusPx = proxyRadiusPx,
            ghostHandles = handles,
            selectionHandles = selHandles,
        )
    }

    val currentFrame by rememberUpdatedState(::frame)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(anchorName, app) {
                handleAnchorGestures(
                    frameProvider = { currentFrame() },
                    feedback = feedback,
                    onActivate = { app.activeAnchor = anchorName },
                    onPointHit = onPointHit,
                )
            },
    ) {
        val f = frame() ?: return@Canvas
        val mapper = f.mapper
        val map: (Float, Float) -> Offset = { x, y -> mapper.toCanvas(x, y) }
        val hair = uiScale.coerceAtLeast(1f)
        val labelStyle = TextStyle(color = Mono.inkFaint, fontSize = 9.sp, fontFamily = labelFamily, letterSpacing = 1.sp)

        drawRect(bgColor, size = size)
        // A faint pool of light behind the glyph.
        val glyphCenter = map(advance / 2f, (metrics.xHeight + metrics.capHeight) / 4f)
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(glowColor, bgColor),
                center = glyphCenter,
                radius = maxOf(size.width, size.height) * 0.6f,
            ),
            size = size,
        )

        if (view.showGrid && view.gridStep > 0f) {
            val step = view.gridStep
            if (step * mapper.scale >= 4f) {
                val tl = mapper.toFont(Offset.Zero)
                val br = mapper.toFont(Offset(size.width, size.height))
                var x = ceil(tl.x / step) * step
                while (x <= br.x) { drawLine(gridColor, map(x, tl.y), map(x, br.y), 1f); x += step }
                var y = ceil(br.y / step) * step
                while (y <= tl.y) { drawLine(gridColor, map(tl.x, y), map(br.x, y), 1f); y += step }
            }
        }

        if (view.showMetrics) {
            val lines = listOf(
                metrics.ascender to "ascender", metrics.capHeight to "cap", metrics.xHeight to "x-height",
                metrics.descender to "descender",
            )
            for ((y, label) in lines) {
                drawLine(metricColor, map(-100000f, y), map(100000f, y), hair)
                drawLabel(textMeasurer, "${label.uppercase()} ${y.toInt()}", Offset(size.width - 4f, map(0f, y).y - 2f), labelStyle, alignRight = true)
            }
            drawLine(metricColor, map(0f, -100000f), map(0f, 100000f), hair)
            drawLine(metricColor, map(advance, -100000f), map(advance, 100000f), hair)
        }
        drawLine(baselineColor, map(-100000f, 0f), map(100000f, 0f), hair)

        // Ghosts sit behind the working outline.
        if (view.showGhosts) for (g in app.ghosts) {
            if (!g.visible) continue
            val contours = app.ghostContours(g, anchorName)
            val isActive = g.id == app.activeGhostId
            val path = buildOutlinePath(contours, map)
            drawPath(path, color = if (isActive) ghostActive.copy(alpha = 0.12f) else ghostFill)
            drawPath(
                path,
                color = if (isActive) ghostActive else ghostStroke,
                style = Stroke(width = hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * uiScale, 4f * uiScale))),
            )
        }

        travelPathOverlay?.let { overlay ->
            for ((a, r, b) in overlay.segments) {
                val ca = map(a.x, a.y)
                val cControl = map(bezierControlFor(a.x, b.x, r.x), bezierControlFor(a.y, b.y, r.y))
                val cb = map(b.x, b.y)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(ca.x, ca.y)
                    quadraticTo(cControl.x, cControl.y, cb.x, cb.y)
                }
                drawPath(
                    path,
                    color = pathCurveColor,
                    style = Stroke(width = hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * uiScale, 3f * uiScale))),
                )
                drawCircle(pathCurveColor, radius = 3f * uiScale, center = ca, style = Stroke(width = uiScale))
                drawCircle(pathCurveColor, radius = 3f * uiScale, center = cb, style = Stroke(width = uiScale))
            }
        }

        val reduction = app.reduction?.takeIf { anchorName in it.anchorNames }
        val editingGhostNodes = ghost != null && !app.ghostTransformMode
        if (reduction != null) {
            // Original as a hairline, reduced shape filled: the gap between them is the loss.
            val original = buildOutlinePath(anchor.glyph.contours, map)
            drawPath(original, color = Color.White, style = Stroke(width = hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * uiScale, 3f * uiScale))))
            val reduced = withoutPoints(anchor.glyph, reduction.removed)
            val path = buildOutlinePath(reduced.contours, map)
            drawPath(path, color = outlineFill)
            drawNodes(reduced, emptySet(), map, uiScale, dim = false)
        } else {
            val outlinePath = buildOutlinePath(anchor.glyph.contours, map)
            drawPath(outlinePath, color = if (editingGhostNodes) outlineFill.copy(alpha = 0.3f) else outlineFill)
            drawPath(outlinePath, color = outlineStroke.copy(alpha = 0.5f), style = Stroke(width = hair))
        }

        if (view.showMeasure) drawMeasurements(app, anchorName, anchor, target, map, textMeasurer, uiScale)

        if (view.showGuides) {
            for (g in app.guides) drawGuide(g, map, guideColor, hair)
        }
        feedback.guideDraft?.let { drawGuide(it, map, snapColor, hair) }
        feedback.snapX?.let { drawLine(snapColor, map(it, -100000f), map(it, 100000f), hair) }
        feedback.snapY?.let { drawLine(snapColor, map(-100000f, it), map(100000f, it), hair) }

        if (reduction == null) {
            val showNodesOf = if (ghost != null && app.ghostTransformMode) null else target
            if (showNodesOf != null) {
                if (editingGhostNodes) {
                    // Draw the ghost's own outline crisply while its nodes are live.
                    drawPath(buildOutlinePath(target.glyph.contours, map), color = ghostActive, style = Stroke(width = hair))
                }
                drawNodes(showNodesOf.glyph, showNodesOf.selection, map, uiScale, dim = false)
            }
        }

        f.ghostHandles?.let { h ->
            val (c0, c1, c2, c3) = h.corners
            val box = androidx.compose.ui.graphics.Path().apply {
                moveTo(c0.x, c0.y); lineTo(c1.x, c1.y); lineTo(c2.x, c2.y); lineTo(c3.x, c3.y); close()
            }
            drawPath(box, ghostActive, style = Stroke(width = hair))
            val top = Offset((c1.x + c2.x) / 2f, (c1.y + c2.y) / 2f)
            drawLine(ghostActive, top, h.rotate, hair)
            for (c in h.corners) {
                drawRect(ghostActive, topLeft = c - Offset(5f * uiScale, 5f * uiScale), size = Size(10f * uiScale, 10f * uiScale))
            }
            drawCircle(ghostActive, radius = 6f * uiScale, center = h.rotate)
        }

        f.selectionHandles?.let { h ->
            val (c0, c1, c2, c3) = h.corners
            val box = androidx.compose.ui.graphics.Path().apply {
                moveTo(c0.x, c0.y); lineTo(c1.x, c1.y); lineTo(c2.x, c2.y); lineTo(c3.x, c3.y); close()
            }
            drawPath(box, Color.White.copy(alpha = 0.5f), style = Stroke(width = hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * uiScale, 4f * uiScale))))
            drawLine(Color.White.copy(alpha = 0.5f), Offset((c1.x + c2.x) / 2f, c1.y), h.rotate, hair)
            for (c in h.corners) {
                drawRect(bgColor, topLeft = c - Offset(4.5f * uiScale, 4.5f * uiScale), size = Size(9f * uiScale, 9f * uiScale))
                drawRect(Color.White, topLeft = c - Offset(4.5f * uiScale, 4.5f * uiScale), size = Size(9f * uiScale, 9f * uiScale), style = Stroke(width = 1.4f * uiScale))
            }
            drawCircle(bgColor, radius = 5.5f * uiScale, center = h.rotate)
            drawCircle(Color.White, radius = 5.5f * uiScale, center = h.rotate, style = Stroke(width = 1.4f * uiScale))
        }

        feedback.rubberRect?.let { (start, current) ->
            val x0 = minOf(start.x, current.x)
            val y0 = minOf(start.y, current.y)
            val w = abs(current.x - start.x)
            val h = abs(current.y - start.y)
            drawRect(rubberFill, topLeft = Offset(x0, y0), size = Size(w, h))
            drawRect(rubberStroke, topLeft = Offset(x0, y0), size = Size(w, h), style = Stroke(width = hair))
        }

        f.proxyCenter?.let { pad ->
            boundsOfSelection(target.glyph, target.selection)?.let { b ->
                drawLine(Color.White.copy(alpha = 0.45f), map(b.cx, b.cy), pad, hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 4f * density)))
            }
            drawCircle(Color.White.copy(alpha = 0.06f), radius = f.proxyRadiusPx, center = pad)
            drawCircle(Color.White, radius = f.proxyRadiusPx, center = pad, style = Stroke(width = 1.5f * density))
            val arm = f.proxyRadiusPx * 0.45f
            drawLine(Color.White, pad - Offset(arm, 0f), pad + Offset(arm, 0f), 1.5f * density)
            drawLine(Color.White, pad - Offset(0f, arm), pad + Offset(0f, arm), 1.5f * density)
        }

        if (view.showRulers) drawRulers(mapper, rulerPx, textMeasurer, labelStyle)
    }
}

private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, at: Offset, style: TextStyle, alignRight: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, style)
    val x = when {
        alignRight -> at.x - layout.size.width
        center -> at.x - layout.size.width / 2f
        else -> at.x
    }
    val y = at.y - layout.size.height
    if (x > size.width || y > size.height || x + layout.size.width < 0f || y + layout.size.height < 0f) return
    drawText(layout, topLeft = Offset(x, y))
}

private fun DrawScope.drawGuide(g: Guide, map: (Float, Float) -> Offset, color: Color, width: Float) {
    if (g.vertical) drawLine(color, map(g.pos, -100000f), map(g.pos, 100000f), width)
    else drawLine(color, map(-100000f, g.pos), map(100000f, g.pos), width)
}

private fun DrawScope.drawNodes(glyph: GlyphCorner, selection: Set<PointKey>, map: (Float, Float) -> Offset, uiScale: Float, dim: Boolean) {
    val hair = uiScale.coerceAtLeast(1f)
    val alpha = if (dim) 0.4f else 1f
    glyph.contours.forEachIndexed { ci, c ->
        val n = c.points.size
        c.points.forEachIndexed { pi, p ->
            val next = c.points[(pi + 1) % n]
            if (!p.onCurve || !next.onCurve) drawLine(ctrlLineColor, map(p.x, p.y), map(next.x, next.y), strokeWidth = hair)
        }
        c.points.forEachIndexed { pi, p ->
            val selected = (ci to pi) in selection
            val center = map(p.x, p.y)
            if (p.onCurve) {
                if (selected) {
                    drawCircle(selectedFill, radius = 6.5f * uiScale, center = center)
                    drawCircle(bgColor, radius = 2.3f * uiScale, center = center)
                } else {
                    drawCircle(bgColor, radius = 4.8f * uiScale, center = center)
                    drawCircle(nodeRing.copy(alpha = alpha), radius = 4.8f * uiScale, center = center, style = Stroke(width = 1.5f * uiScale))
                }
            } else {
                // Off-curve handles are diamonds, so the two kinds read apart without colour.
                val r = (if (selected) 5.5f else 4.2f) * uiScale
                val d = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y - r); lineTo(center.x + r, center.y)
                    lineTo(center.x, center.y + r); lineTo(center.x - r, center.y); close()
                }
                if (selected) drawPath(d, selectedFill)
                else {
                    drawPath(d, bgColor)
                    drawPath(d, offCurveColor.copy(alpha = alpha), style = Stroke(width = 1.4f * uiScale))
                }
            }
        }
    }
}

/**
 * Dynamic measurement guides: every contour's width (below it) and height
 * (left of it), every visible ghost's width, and the current selection's
 * extent -- so the widths of a character's parts, and of a reference beside
 * it, can be compared at a glance as they're reshaped.
 */
private fun DrawScope.drawMeasurements(
    app: AppState,
    anchorName: String,
    anchor: AnchorState,
    target: AnchorState,
    map: (Float, Float) -> Offset,
    tm: TextMeasurer,
    uiScale: Float,
) {
    val hair = uiScale.coerceAtLeast(1f)
    val tick = 4f * uiScale
    val style = TextStyle(color = measureColor, fontSize = 9.sp, fontFamily = labelFamily)
    fun hBar(b: FontRect, color: Color, below: Float) {
        val a = map(b.minX, b.minY); val c = map(b.maxX, b.minY)
        val y = a.y + below
        drawLine(color.copy(alpha = 0.35f), a, Offset(a.x, y), hair)
        drawLine(color.copy(alpha = 0.35f), c, Offset(c.x, y), hair)
        drawLine(color, Offset(a.x, y), Offset(c.x, y), hair)
        drawLine(color, Offset(a.x, y - tick), Offset(a.x, y + tick), hair)
        drawLine(color, Offset(c.x, y - tick), Offset(c.x, y + tick), hair)
        drawLabel(tm, b.width.toInt().toString(), Offset((a.x + c.x) / 2f, y + 12f * uiScale), style.copy(color = color), center = true)
    }
    fun vBar(b: FontRect, color: Color) {
        val a = map(b.minX, b.minY); val c = map(b.minX, b.maxY)
        val x = a.x - 10f * uiScale
        drawLine(color, Offset(x, a.y), Offset(x, c.y), hair)
        drawLine(color, Offset(x - tick, a.y), Offset(x + tick, a.y), hair)
        drawLine(color, Offset(x - tick, c.y), Offset(x + tick, c.y), hair)
        drawLabel(tm, b.height.toInt().toString(), Offset(x - 3f * uiScale, (a.y + c.y) / 2f), style.copy(color = color), alignRight = true)
    }
    anchor.glyph.contours.forEachIndexed { i, c ->
        val b = boundsOf(c.points) ?: return@forEachIndexed
        hBar(b, measureColor, 14f * uiScale + (i % 3) * 14f * uiScale)
        vBar(b, measureColor)
    }
    for (g in app.ghosts) if (g.visible) boundsOfContours(app.ghostContours(g, anchorName))?.let { hBar(it, Color(0xFF6A6A6A), 56f * uiScale) }
    if (target.selection.size >= 2) boundsOfSelection(target.glyph, target.selection)?.let {
        hBar(it, Color.White, 8f * uiScale)
        vBar(it, Color.White)
    }
}

private fun DrawScope.drawRulers(mapper: SpaceMapper, rulerPx: Float, tm: TextMeasurer, style: TextStyle) {
    drawRect(rulerBg, size = Size(size.width, rulerPx))
    drawRect(rulerBg, size = Size(rulerPx, size.height))
    // Majors at least five ruler-widths apart (~90dp) so labels never collide.
    val step = niceStep(mapper.scale, 5f * rulerPx)
    val minor = step / 5f
    val tl = mapper.toFont(Offset.Zero)
    val br = mapper.toFont(Offset(size.width, size.height))
    var x = floor(tl.x / minor) * minor
    while (x <= br.x) {
        val cx = mapper.toCanvas(x, 0f).x
        val major = abs(x / step - kotlin.math.round(x / step)) < 1e-3f
        drawLine(Mono.inkFaint, Offset(cx, rulerPx), Offset(cx, rulerPx - if (major) rulerPx * 0.6f else rulerPx * 0.25f), 1f)
        if (major && cx > rulerPx) drawLabel(tm, x.toInt().toString(), Offset(cx + 2f, rulerPx * 0.7f), style)
        x += minor
    }
    var y = floor(br.y / minor) * minor
    while (y <= tl.y) {
        val cy = mapper.toCanvas(0f, y).y
        val major = abs(y / step - kotlin.math.round(y / step)) < 1e-3f
        drawLine(Mono.inkFaint, Offset(rulerPx, cy), Offset(rulerPx - if (major) rulerPx * 0.6f else rulerPx * 0.25f, cy), 1f)
        if (major && cy > rulerPx) drawLabel(tm, y.toInt().toString(), Offset(1f, cy - 1f), style)
        y += minor
    }
    drawRect(rulerBg, size = Size(rulerPx, rulerPx))
    drawLine(Color(0xFF1A1A1A), Offset(0f, rulerPx), Offset(size.width, rulerPx), 1f)
    drawLine(Color(0xFF1A1A1A), Offset(rulerPx, 0f), Offset(rulerPx, size.height), 1f)
}
