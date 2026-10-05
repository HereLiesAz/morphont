package com.hereliesaz.morphont

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private fun Offset.exceedsSlop(slopPx: Float): Boolean =
    abs(x) >= slopPx || abs(y) >= slopPx

/** Transient, draw-only feedback a gesture publishes to its canvas. */
class CanvasFeedback {
    var rubberRect by mutableStateOf<Pair<Offset, Offset>?>(null)
    var snapX by mutableStateOf<Float?>(null)
    var snapY by mutableStateOf<Float?>(null)

    /** A guide being created or moved, not yet committed to [AppState.guides]. */
    var guideDraft by mutableStateOf<Guide?>(null)

    fun clearSnap() { snapX = null; snapY = null }
}

/**
 * Everything one gesture needs about its canvas, captured at gesture start.
 * The canvas rebuilds it every composition and hands it over through a
 * provider, so the pointer coroutine never restarts mid-drag.
 */
class CanvasFrame(
    val app: AppState,
    val anchorName: String,
    val canvasSize: Size,
    val mapper: SpaceMapper,
    val mapperFor: (zoom: Float, panX: Float, panY: Float) -> SpaceMapper,
    val density: Float,
    val hitRadiusPx: Float,
    val dragStartSlopPx: Float,
    val rubberBandSlopPx: Float,
    val rulerPx: Float,
    val proxyCenter: Offset?,
    val proxyRadiusPx: Float,
    val ghostHandles: GhostHandles?,
)

/** Canvas-space handles of the active ghost's transform box. */
data class GhostHandles(
    val corners: List<Offset>, // minX/minY, minX/maxY, maxX/maxY, maxX/minY in font terms
    val rotate: Offset,
    val boundsFont: FontRect,
)

/**
 * Where the touch proxy pad sits for a selection whose canvas bounds are
 * [selTop]..[selBottom] around [selCx]: below the selection by default,
 * above it when that would leave the canvas -- always far enough away that
 * the finger on the pad never covers the nodes it moves.
 */
fun proxyPadCenter(selCx: Float, selTop: Float, selBottom: Float, canvasSize: Size, density: Float): Offset {
    val gap = 76f * density
    val margin = 36f * density
    var y = selBottom + gap
    if (y > canvasSize.height - margin) y = selTop - gap
    y = y.coerceIn(margin, max(margin, canvasSize.height - margin))
    val x = selCx.coerceIn(margin, max(margin, canvasSize.width - margin))
    return Offset(x, y)
}

/**
 * Pointer handling shared by wasm and Android, in priority order: wheel zoom,
 * two-finger pinch/pan, the touch proxy pad, the active ghost's transform
 * box, rulers (drag out a guide), contour drawing, points, existing guides,
 * then rubber-band selection on empty space.
 */
suspend fun PointerInputScope.handleAnchorGestures(
    frameProvider: () -> CanvasFrame?,
    feedback: CanvasFeedback,
    onActivate: () -> Unit,
    onPointHit: () -> Unit = {},
) {
    awaitEachGesture {
        val event = awaitPointerEvent()
        val f0 = frameProvider() ?: return@awaitEachGesture

        if (event.type == PointerEventType.Scroll) {
            val change = event.changes.firstOrNull() ?: return@awaitEachGesture
            val dir = change.scrollDelta.y
            if (dir != 0f) zoomAbout(f0, change.position, if (dir > 0f) 1f / 1.15f else 1.15f)
            change.consume()
            return@awaitEachGesture
        }

        val down = event.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
        f0.app.touchInput = down.type == PointerType.Touch
        onActivate()
        val f = frameProvider() ?: return@awaitEachGesture
        val app = f.app
        val startPos = down.position
        down.consume()

        // Node reduction preview is read-only: only zoom/pan.
        if (app.reduction != null) {
            followOrPinch(f, down.id, f.dragStartSlopPx) { _, _ -> }
            return@awaitEachGesture
        }

        val ghost = app.activeGhost
        val target = app.editTarget(f.anchorName)

        // Touch proxy: moving the selection from a pad offset away from it.
        val proxy = f.proxyCenter
        if (proxy != null && (startPos - proxy).getDistance() <= f.proxyRadiusPx * 1.3f) {
            onPointHit()
            dragSelection(f, down.id, target, target.selection.toList(), startPos, slop = 0.5f, feedback)
            return@awaitEachGesture
        }

        // Active ghost in placement mode: handles, then body.
        val handles = f.ghostHandles
        if (ghost != null && app.ghostTransformMode && handles != null) {
            val rotateHit = (startPos - handles.rotate).getDistance() <= f.hitRadiusPx * 1.4f
            val cornerHit = handles.corners.indexOfFirst { (startPos - it).getDistance() <= f.hitRadiusPx * 1.4f }
            val b = handles.boundsFont
            val startFont = f.mapper.toFont(startPos)
            val inside = startFont.x in b.minX..b.maxX && startFont.y in b.minY..b.maxY
            if (rotateHit || cornerHit >= 0 || inside) {
                val orig = ghost.matrix
                var pushed = false
                followOrPinch(f, down.id, f.dragStartSlopPx) { pos, _ ->
                    if (!pushed) { ghost.pushMatrixHistory(); pushed = true }
                    val cur = f.mapper.toFont(pos)
                    val t: Affine = when {
                        rotateHit -> {
                            val a0 = atan2(startFont.y - b.cy, startFont.x - b.cx)
                            val a1 = atan2(cur.y - b.cy, cur.x - b.cx)
                            rotationAbout((a1 - a0) * 180f / kotlin.math.PI.toFloat(), b.cx, b.cy)
                        }
                        cornerHit >= 0 -> {
                            // Scale about the opposite corner.
                            val ox = if (cornerHit == 0 || cornerHit == 1) b.maxX else b.minX
                            val oy = if (cornerHit == 0 || cornerHit == 3) b.maxY else b.minY
                            val sx = if (abs(startFont.x - ox) < 1e-3f) 1f else (cur.x - ox) / (startFont.x - ox)
                            val sy = if (abs(startFont.y - oy) < 1e-3f) 1f else (cur.y - oy) / (startFont.y - oy)
                            scalingAbout(sx, sy, ox, oy)
                        }
                        else -> translation(cur.x - startFont.x, cur.y - startFont.y)
                    }
                    ghost.matrix = compose(t, orig)
                }
                return@awaitEachGesture
            }
        }

        // Rulers: drag out a new guide.
        if (app.view.showRulers && (startPos.y < f.rulerPx || startPos.x < f.rulerPx)) {
            val vertical = startPos.x < f.rulerPx && startPos.y >= f.rulerPx
            dragGuide(f, vertical, feedback, down.id)
            return@awaitEachGesture
        }

        // Placing a ghost: outside its box only zoom/pan apply (its raw nodes aren't what's drawn).
        if (ghost != null && app.ghostTransformMode) {
            followOrPinch(f, down.id, f.dragStartSlopPx) { _, _ -> }
            return@awaitEachGesture
        }

        if (target.drawingContourIndex != null) {
            val hit = hitTestPoint(target.glyph, f.mapper, startPos, radiusPx = f.hitRadiusPx)
            if (hit == null) {
                val font = f.mapper.toFont(startPos)
                var x = font.x; var y = font.y
                if (app.view.snap) {
                    val targets = snapTargetsFor(f, target, emptySet())
                    val r = snapDelta(listOf(x), listOf(y), 0f, 0f, targets, snapTolerance(f))
                    x += r.dx; y += r.dy
                }
                target.addDrawPoint(x, y)
            } else {
                onPointHit()
            }
            followOrPinch(f, down.id, Float.MAX_VALUE) { _, _ -> }
            return@awaitEachGesture
        }

        val hitKey = hitTestPoint(target.glyph, f.mapper, startPos, radiusPx = f.hitRadiusPx)
        if (hitKey != null) {
            onPointHit()
            if (hitKey !in target.selection) target.selection = setOf(hitKey)
            val keys = target.selection.toList()
            dragSelection(f, down.id, target, keys, startPos, f.dragStartSlopPx, feedback, primary = hitKey)
            return@awaitEachGesture
        }

        if (app.view.showGuides) {
            val gi = app.guides.indexOfFirst { g ->
                val c = if (g.vertical) f.mapper.toCanvas(g.pos, 0f).x else f.mapper.toCanvas(0f, g.pos).y
                abs((if (g.vertical) startPos.x else startPos.y) - c) <= f.hitRadiusPx * 0.6f
            }
            if (gi >= 0) {
                val g = app.guides.removeAt(gi)
                feedback.guideDraft = g
                dragGuide(f, g.vertical, feedback, down.id, existing = g)
                return@awaitEachGesture
            }
        }

        // Empty space: rubber band (or pinch on touch).
        target.selection = emptySet()
        var rubberBanding = false
        val completed = followOrPinch(f, down.id, f.rubberBandSlopPx) { current, _ ->
            rubberBanding = true
            feedback.rubberRect = startPos to current
            val x0 = min(startPos.x, current.x)
            val x1 = max(startPos.x, current.x)
            val y0 = min(startPos.y, current.y)
            val y1 = max(startPos.y, current.y)
            val newSel = mutableSetOf<PointKey>()
            target.glyph.contours.forEachIndexed { ci, c ->
                c.points.forEachIndexed { pi, p ->
                    val pos = f.mapper.toCanvas(p.x, p.y)
                    if (pos.x in x0..x1 && pos.y in y0..y1) newSel.add(ci to pi)
                }
            }
            target.selection = newSel
        }
        if (rubberBanding || !completed) feedback.rubberRect = null
    }
}

private fun snapTolerance(f: CanvasFrame): Float = 7f * f.density / f.mapper.scale

private fun snapTargetsFor(f: CanvasFrame, target: AnchorState, exclude: Set<PointKey>): SnapTargets {
    val app = f.app
    val anchorGlyph = app.anchors.getValue(f.anchorName).glyph
    val editingGhost = app.activeGhost?.takeIf { it.state === target }
    // Editing a ghost snaps to the glyph's own points as well as metrics/guides.
    val base = buildSnapTargets(
        glyph = target.glyph,
        exclude = exclude,
        metrics = app.metrics,
        guides = if (app.view.showGuides) app.guides.toList() else emptyList(),
        ghosts = app.visibleGhostContours(f.anchorName, skipId = editingGhost?.id) +
            (if (editingGhost != null) listOf(anchorGlyph.contours) else emptyList()),
        gridStep = if (app.view.showGrid) app.view.gridStep else null,
    )
    if (editingGhost == null) return base
    return base.copy(xs = base.xs + SnapTarget(anchorGlyph.width, "advance"))
}

/** Drags [keys] of [target] by the pointer delta, with light snapping, recording one undo step. */
private suspend fun AwaitPointerEventScope.dragSelection(
    f: CanvasFrame,
    downId: PointerId,
    target: AnchorState,
    keys: List<PointKey>,
    startPos: Offset,
    slop: Float,
    feedback: CanvasFeedback,
    primary: PointKey? = null,
) {
    if (keys.isEmpty()) return
    val origGlyph = target.glyph.deepCopy()
    val origPts = keys.mapNotNull { (ci, pi) -> origGlyph.contours.getOrNull(ci)?.points?.getOrNull(pi) }
    val primaryPt = primary?.let { (ci, pi) -> origGlyph.contours.getOrNull(ci)?.points?.getOrNull(pi) }
    // A grabbed point snaps by itself; a selection snaps by its edges and centre.
    val (movingXs, movingYs) = if (primaryPt != null) listOf(primaryPt.x) to listOf(primaryPt.y) else {
        val b = boundsOf(origPts)!!
        listOf(b.minX, b.cx, b.maxX) to listOf(b.minY, b.cy, b.maxY)
    }
    val targets = if (f.app.view.snap) snapTargetsFor(f, target, keys.toSet()) else null
    var pushed = false
    followOrPinch(f, downId, slop) { pos, _ ->
        if (!pushed) { target.pushHistory(); pushed = true }
        val delta = pos - startPos
        var dx = delta.x / f.mapper.scale
        var dy = -delta.y / f.mapper.scale
        if (targets != null) {
            val r = snapDelta(movingXs, movingYs, dx, dy, targets, snapTolerance(f))
            dx = r.dx; dy = r.dy
            feedback.snapX = r.xLine; feedback.snapY = r.yLine
        }
        val updated = origGlyph.deepCopy()
        for ((ci, pi) in keys) {
            val op = origGlyph.contours.getOrNull(ci)?.points?.getOrNull(pi) ?: continue
            updated.contours[ci].points[pi] = Pt(op.x + dx, op.y + dy, op.onCurve, op.smooth)
        }
        target.replaceGlyph(updated)
    }
    feedback.clearSnap()
}

private suspend fun AwaitPointerEventScope.dragGuide(
    f: CanvasFrame,
    vertical: Boolean,
    feedback: CanvasFeedback,
    downId: PointerId,
    existing: Guide? = null,
) {
    fun posFor(p: Offset): Float {
        val font = f.mapper.toFont(p)
        return kotlin.math.round(if (vertical) font.x else font.y)
    }
    var last: Offset? = null
    followOrPinch(f, downId, if (existing != null) f.dragStartSlopPx else 0.5f) { pos, _ ->
        last = pos
        feedback.guideDraft = Guide(vertical, posFor(pos))
    }
    val end = last
    val draft = feedback.guideDraft
    feedback.guideDraft = null
    if (end == null) {
        existing?.let { f.app.guides.add(it) } // a tap on a guide leaves it alone
        return
    }
    // Releasing back over a ruler deletes the guide, like most editors.
    val overRuler = f.app.view.showRulers && (end.x < f.rulerPx || end.y < f.rulerPx)
    if (!overRuler && draft != null) f.app.guides.add(draft)
}

/** Zooms the shared view by [factor], keeping the font point under [anchor] fixed on screen. */
private fun zoomAbout(f: CanvasFrame, anchor: Offset, factor: Float) {
    val v = f.app.view
    val before = f.mapperFor(v.zoom, v.panX, v.panY)
    val fp = before.toFont(anchor)
    val newZoom = (v.zoom * factor).coerceIn(0.1f, 40f)
    val after = f.mapperFor(newZoom, v.panX, v.panY)
    val c = after.toCanvas(fp.x, fp.y)
    v.zoom = newZoom
    v.panX += (c.x - anchor.x) / after.scale
    v.panY -= (c.y - anchor.y) / after.scale
}

private fun panBy(f: CanvasFrame, deltaPx: Offset) {
    val v = f.app.view
    val m = f.mapperFor(v.zoom, v.panX, v.panY)
    v.panX -= deltaPx.x / m.scale
    v.panY += deltaPx.y / m.scale
}

/**
 * Follows [downId] until release, calling [onMove] once movement passes
 * [slop]. A second finger before that point turns the gesture into a
 * two-finger pinch-zoom/pan instead (and [onMove] never fires). Returns
 * true if the gesture ended normally rather than becoming a pinch.
 */
private suspend fun AwaitPointerEventScope.followOrPinch(
    f: CanvasFrame,
    downId: PointerId,
    slop: Float,
    onMove: (Offset, PointerInputChange) -> Unit,
): Boolean {
    var start: Offset? = null
    var committed = false
    while (true) {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.filter { it.pressed }
        if (!committed && pressed.size >= 2) {
            ev.changes.forEach { it.consume() }
            pinch(f)
            return false
        }
        val ch = ev.changes.firstOrNull { it.id == downId } ?: return true
        if (!ch.pressed) { ch.consume(); return true }
        if (start == null) start = ch.previousPosition
        ch.consume()
        if (!committed && (ch.position - start!!).exceedsSlop(slop)) committed = true
        if (committed) onMove(ch.position, ch)
    }
}

private suspend fun AwaitPointerEventScope.pinch(f: CanvasFrame) {
    var prevCentroid: Offset? = null
    var prevSpan = 0f
    while (true) {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.filter { it.pressed }
        ev.changes.forEach { it.consume() }
        if (pressed.size < 2) { if (pressed.isEmpty()) return else { prevCentroid = null; continue } }
        val a = pressed[0].position; val b = pressed[1].position
        val centroid = (a + b) / 2f
        val span = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        val pc = prevCentroid
        if (pc != null && prevSpan > 0f) {
            panBy(f, centroid - pc)
            if (span > 0f) zoomAbout(f, centroid, span / prevSpan)
        }
        prevCentroid = centroid
        prevSpan = span
    }
}
