package com.hereliesaz.morphont

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
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
    /** Transform box around a multi-point selection (handles sit just outside the points). */
    val selectionHandles: GhostHandles? = null,
)

/** Single-point selections must always move, even if Scale/Turn was previously active. */
fun effectivePadMode(configured: PadMode, selectionSize: Int): PadMode =
    if (selectionSize < 2) PadMode.MOVE else configured

/** Canvas-space handles of the active ghost's transform box. */
data class GhostHandles(
    val corners: List<Offset>, // minX/minY, minX/maxY, maxX/maxY, maxX/minY in font terms
    val rotate: Offset,
    val boundsFont: FontRect,
    /** Edge midpoints, left, top, right, bottom (a selection's transform box only). */
    val edges: List<Offset> = emptyList(),
)

/** What a drag on a selection's transform box grabbed. [Edge] indexes left, top, right, bottom. */
sealed class BoxGrab {
    data object Rotate : BoxGrab()
    data class Corner(val index: Int) : BoxGrab()
    data class Edge(val index: Int) : BoxGrab()
}

/**
 * Photoshop's Free Transform Points, for the box around selected nodes, in font units. A corner scales
 * about the opposite corner (Shift keeps the proportions); an edge scales along one axis only, or with
 * [skew] slides that edge sideways; outside a corner (or the knob) rotates about the centre (Shift snaps to
 * 15 degrees). [fromCentre] (Alt) makes every scale or skew pivot on the box's centre instead.
 */
fun selectionBoxTransform(b: FontRect, grab: BoxGrab, start: Offset, cur: Offset, shift: Boolean, fromCentre: Boolean, skew: Boolean): Affine {
    fun ratio(now: Float, then: Float, origin: Float) = if (abs(then - origin) < 1e-3f) 1f else (now - origin) / (then - origin)
    return when (grab) {
        BoxGrab.Rotate -> {
            val a0 = atan2(start.y - b.cy, start.x - b.cx)
            val a1 = atan2(cur.y - b.cy, cur.x - b.cx)
            var degrees = (a1 - a0) * 180f / kotlin.math.PI.toFloat()
            if (shift) degrees = kotlin.math.round(degrees / 15f) * 15f
            rotationAbout(degrees, b.cx, b.cy)
        }
        is BoxGrab.Corner -> {
            val c = grab.index
            val ox = if (fromCentre) b.cx else if (c == 0 || c == 1) b.maxX else b.minX
            val oy = if (fromCentre) b.cy else if (c == 0 || c == 3) b.maxY else b.minY
            var sx = ratio(cur.x, start.x, ox)
            var sy = ratio(cur.y, start.y, oy)
            if (shift) { val u = if (abs(sx - 1f) >= abs(sy - 1f)) sx else sy; sx = u; sy = u }
            scalingAbout(sx, sy, ox, oy)
        }
        is BoxGrab.Edge -> {
            val e = grab.index
            val ox = if (fromCentre) b.cx else if (e == 0) b.maxX else b.minX
            val oy = if (fromCentre) b.cy else if (e == 1) b.minY else b.maxY
            when {
                e == 0 || e == 2 -> if (skew) {
                    val k = (cur.y - start.y) / (if (abs(start.x - ox) < 1e-3f) 1f else (start.x - ox))
                    listOf(1f, k, 0f, 1f, 0f, -k * ox)
                } else scalingAbout(ratio(cur.x, start.x, ox), 1f, ox, oy)
                else -> if (skew) {
                    val k = (cur.x - start.x) / (if (abs(start.y - oy) < 1e-3f) 1f else (start.y - oy))
                    listOf(1f, 0f, k, 1f, -k * oy, 0f)
                } else scalingAbout(1f, ratio(cur.y, start.y, oy), ox, oy)
            }
        }
    }
}

/**
 * The transform a transform-box drag implies, in font units: rotate about the
 * box centre, scale about the opposite corner, or translate.
 */
fun boxDragTransform(b: FontRect, rotate: Boolean, corner: Int, start: Offset, cur: Offset): Affine = when {
    rotate -> {
        val a0 = atan2(start.y - b.cy, start.x - b.cx)
        val a1 = atan2(cur.y - b.cy, cur.x - b.cx)
        rotationAbout((a1 - a0) * 180f / kotlin.math.PI.toFloat(), b.cx, b.cy)
    }
    corner >= 0 -> {
        val ox = if (corner == 0 || corner == 1) b.maxX else b.minX
        val oy = if (corner == 0 || corner == 3) b.maxY else b.minY
        val sx = if (abs(start.x - ox) < 1e-3f) 1f else (cur.x - ox) / (start.x - ox)
        val sy = if (abs(start.y - oy) < 1e-3f) 1f else (cur.y - oy) / (start.y - oy)
        scalingAbout(sx, sy, ox, oy)
    }
    else -> translation(cur.x - start.x, cur.y - start.y)
}

/**
 * Where the move circle sits. It goes outward -- away from the glyph's centre, through the selection --
 * and as far out as it takes for no other point (node or handle) to fall inside its touch area
 * ([touchRadius]): the circle claims every touch in that area, so a point under it could not be tapped.
 * If the straight-out line is blocked or runs off the canvas it tries nearby outward angles, nearest to
 * straight-out first. It always stays inside the canvas and out from under the floating rows
 * ([coveredTop]/[coveredBottom]). Only when nothing is clear does it settle for the spot that covers the
 * fewest points.
 *
 * [selection] is the selected nodes' centre, [glyphCenter] the glyph's, [points] every point of the glyph
 * (the selected ones too); all in canvas pixels.
 */
fun placeMoveCircle(
    selection: Offset,
    glyphCenter: Offset,
    points: List<Offset>,
    canvasSize: Size,
    density: Float,
    touchRadius: Float,
    coveredTop: Float = 0f,
    coveredBottom: Float = 0f,
): Offset {
    val visible = touchRadius / 1.3f
    val edge = visible + 4f * density
    var minY = coveredTop + edge
    var maxY = canvasSize.height - coveredBottom - edge
    if (maxY < minY) { minY = edge; maxY = max(edge, canvasSize.height - edge) }
    val minX = edge
    val maxX = max(edge, canvasSize.width - edge)

    val raw = selection - glyphCenter
    val outward = if (raw.getDistance() > 1e-3f) raw / raw.getDistance()
        else if (selection.y > canvasSize.height / 2f) Offset(0f, -1f) else Offset(0f, 1f)
    val clear = touchRadius + 10f * density
    val start = touchRadius + 12f * density
    val step = 5f * density
    val reach = hypot(canvasSize.width, canvasSize.height)

    fun covered(p: Offset): Int = points.count { (it - p).getDistance() < clear }
    fun inside(p: Offset) = p.x in minX..maxX && p.y in minY..maxY

    var best: Offset? = null
    var bestCovered = Int.MAX_VALUE
    for (degrees in listOf(0f, 12f, -12f, 24f, -24f, 36f, -36f, 48f, -48f, 60f, -60f, 72f, -72f, 84f, -84f)) {
        val rad = degrees * (kotlin.math.PI.toFloat() / 180f)
        val c = kotlin.math.cos(rad); val sn = kotlin.math.sin(rad)
        val dir = Offset(outward.x * c - outward.y * sn, outward.x * sn + outward.y * c)
        var d = start
        while (d <= reach) {
            val p = selection + dir * d
            if (!inside(p)) break
            val n = covered(p)
            if (n == 0) return p
            if (n < bestCovered) { bestCovered = n; best = p }
            d += step
        }
    }
    return best ?: Offset(selection.x.coerceIn(minX, maxX), (selection.y + start).coerceIn(minY, maxY))
}

/**
 * The circle plus a 30% safety margin is one exclusive move hit region.
 * This is checked before panning, transform boxes, guides and node picking.
 */
internal fun hitsNodeMoveCircle(position: Offset, center: Offset?, radius: Float): Boolean =
    center != null && (position - center).getDistance() <= radius * 1.3f

/**
 * Pointer handling shared by wasm and Android, in priority order: wheel zoom,
 * two-finger pinch/pan, the touch proxy pad, the active ghost's transform
 * box, rulers (drag out a guide), contour drawing, points, existing guides,
 * then rubber-band selection on empty space.
 *
 * Pen: a tap places a corner node; press-and-drag places a smooth node and
 * pulls out its out-arm (the in-arm mirrors it), as in Photoshop. Dragging
 * a lone arm of a smooth node mirrors the opposite arm. Alt-click on a node
 * (where a keyboard reports Alt), or a touch long-press on it, toggles it
 * between smooth and corner.
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
            val dx = change.scrollDelta.x
            val dy = change.scrollDelta.y
            val shift = event.keyboardModifiers.isShiftPressed
            // Sideways swipes (and Shift + wheel) pan; a wheel notch (+-1) zooms 1.15x as before, and the small
            // fractional deltas of a trackpad zoom in proportion instead of jumping a full notch per event.
            if (dx != 0f || (shift && dy != 0f)) {
                val step = if (dx != 0f) dx else dy
                panBy(f0, Offset(-step * 40f * f0.density, 0f))
            } else if (dy != 0f) {
                zoomAbout(f0, change.position, 1.15f.pow(-dy.coerceIn(-3f, 3f)))
            }
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

        // The cross-circle is exclusively a node mover. Reserve its full
        // visible radius plus a generous margin before *all* other handlers,
        // including mouse panning, transform boxes, guides and node hit tests.
        // Once captured, it cannot switch actions during the drag.
        val proxy = f.proxyCenter
        if (hitsNodeMoveCircle(startPos, proxy, f.proxyRadiusPx)) {
            val selected = f.app.editTarget(f.anchorName)
            // Move exactly what is selected: a picked handle moves by itself (re-mirroring its twin on a
            // smooth node), a picked node carries its handles.
            val nodes = selected.selection.filter { (ci, pi) ->
                selected.glyph.contours.getOrNull(ci)?.points?.getOrNull(pi) != null
            }
            if (nodes.isNotEmpty()) {
                // No haptic and no wait: the circle responds from the first touch. While it is dragged it
                // keeps its spacing from the node instead of re-choosing a spot under the finger.
                boundsOfSelection(selected.glyph, selected.selection)?.let { b ->
                    f.app.moveCircleHold = proxy!! - f.mapper.toCanvas(b.cx, b.cy)
                }
                dragSelection(
                    f, down.id, selected, nodes, startPos,
                    slop = 0.5f, feedback = feedback, snapEnabled = true,
                    allowPinch = false,
                )
                f.app.moveCircleHold = null
            } else {
                followOrPinch(f, down.id, Float.MAX_VALUE) { _, _ -> }
            }
            return@awaitEachGesture
        }

        // Middle/right mouse drag pans regardless of the current tool. It must
        // run before the read-only reduction and all selection hit tests.
        if (down.type == PointerType.Mouse &&
            (event.buttons.isTertiaryPressed || event.buttons.isSecondaryPressed)
        ) {
            var last = startPos
            followOrPinch(f, down.id, 0.5f) { pos, _ ->
                panBy(f, pos - last)
                last = pos
            }
            return@awaitEachGesture
        }

        // Node reduction preview is read-only: only zoom/pan.
        if (app.reduction != null) {
            followOrPinch(f, down.id, f.dragStartSlopPx) { _, _ -> }
            return@awaitEachGesture
        }

        val ghost = app.activeGhost
        val target = app.editTarget(f.anchorName)

        // Cross-circle input was already captured before all other tools.

        // Selection transform box: corner squares scale, the knob rotates.
        val sh = f.selectionHandles
        // Selection handles have generous touch targets and can overlap real
        // nodes. A hit on a real point always wins over the transform box.
        val pointUnderPointer = hitTestPoint(
            target.glyph, f.mapper, startPos, radiusPx = f.hitRadiusPx,
            nodeOnly = app.nodeMoveMode,
        )
        if (sh != null && pointUnderPointer == null) {
            val reach = f.hitRadiusPx * 1.3f
            val box = sh.boundsFont
            val grab: BoxGrab? = when {
                (startPos - sh.rotate).getDistance() <= reach -> BoxGrab.Rotate
                sh.corners.indexOfFirst { (startPos - it).getDistance() <= reach } >= 0 ->
                    BoxGrab.Corner(sh.corners.indexOfFirst { (startPos - it).getDistance() <= reach })
                sh.edges.indexOfFirst { (startPos - it).getDistance() <= reach } >= 0 ->
                    BoxGrab.Edge(sh.edges.indexOfFirst { (startPos - it).getDistance() <= reach })
                // With a mouse, just outside a corner rotates, as in Photoshop; touch keeps to the knob.
                !app.touchInput && sh.corners.any { (startPos - it).getDistance() in reach..reach * 2.4f } -> BoxGrab.Rotate
                else -> null
            }
            if (grab != null) {
                onPointHit()
                val keys = target.selection.toList()
                val orig = target.glyph.deepCopy()
                val startFont = f.mapper.toFont(startPos)
                val shift = event.keyboardModifiers.isShiftPressed
                val fromCentre = event.keyboardModifiers.isAltPressed
                val skew = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                var pushed = false
                followOrPinch(f, down.id, f.dragStartSlopPx) { pos, _ ->
                    if (!pushed) { target.pushHistory(); pushed = true }
                    val t = selectionBoxTransform(box, grab, startFont, f.mapper.toFont(pos), shift, fromCentre, skew)
                    target.replaceGlyph(transformPoints(orig, keys, t))
                }
                return@awaitEachGesture
            }
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
                    val t = boxDragTransform(b, rotateHit, cornerHit, startFont, f.mapper.toFont(pos))
                    ghost.matrix = compose(app.localGhostTransform(ghost, t, f.anchorName), orig)
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
                // Dragging on from the new node pulls out its arms (same undo step as the node).
                val placed = followOrPinch(f, down.id, f.dragStartSlopPx) { pos, _ ->
                    val arm = f.mapper.toFont(pos)
                    target.dragDrawArm(arm.x, arm.y)
                }
                // A second finger turned this touch into a pinch: it was never a tap, so take the node back.
                if (!placed) target.discardLastDrawPoint()
            } else {
                onPointHit()
                followOrPinch(f, down.id, Float.MAX_VALUE) { _, _ -> }
            }
            return@awaitEachGesture
        }

        val nodeTool = app.nodeTool
        if (nodeTool != null && !app.nodeEditsLocked) {
            nodeToolGesture(f, nodeTool, down.id, target, startPos, feedback)
            return@awaitEachGesture
        }

        val hitKey = pointUnderPointer
        if (hitKey != null) {
            onPointHit()
            if (event.keyboardModifiers.isShiftPressed && !app.nodeMoveMode) {
                target.selection = if (hitKey in target.selection)
                    target.selection - hitKey else target.selection + hitKey
                followOrPinch(f, down.id, Float.MAX_VALUE) { _, _ -> }
                return@awaitEachGesture
            }
            if (hitKey !in target.selection) target.selection = setOf(hitKey)
            app.moveCircleVisible = true
            val hitNode = target.glyph.contours[hitKey.first].points[hitKey.second].onCurve
            // Alt-click on a node toggles it between smooth (mirrored arms) and corner.
            if (hitNode && !app.nodeMoveMode && event.keyboardModifiers.isAltPressed) {
                target.toggleNodeSmooth(hitKey)
                followOrPinch(f, down.id, Float.MAX_VALUE) { _, _ -> }
                return@awaitEachGesture
            }
            // Touch: holding a node still for the long-press timeout and then lifting does the same.
            // Moving at any point -- even after the timeout -- is a drag, never a toggle.
            var movedDuringHold = false
            if (hitNode && !app.nodeMoveMode && down.type == PointerType.Touch) {
                val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    awaitHoldEnd(down.id, startPos, f.dragStartSlopPx)
                }
                // The hold has now lasted long enough to count: a second buzz and a note say releasing will flip the node.
                if (early == null) { onPointHit(); app.setStatus("Release to switch this node between smooth and corner.") }
                when (early ?: awaitHoldEnd(down.id, startPos, f.dragStartSlopPx)) {
                    HoldEnd.RELEASED -> {
                        if (early == null) target.toggleNodeSmooth(hitKey)
                        return@awaitEachGesture
                    }
                    HoldEnd.MOVED -> movedDuringHold = true
                }
            }
            val keys = if (app.nodeMoveMode) listOf(hitKey) else target.selection.toList()
            dragSelection(f, down.id, target, keys, startPos, if (movedDuringHold) 0.5f else f.dragStartSlopPx, feedback, primary = hitKey)
            return@awaitEachGesture
        }

        if (app.view.showGuides) {
            val gi = app.guides.indexOfFirst { g ->
                val c = if (g.vertical) f.mapper.toCanvas(g.pos, 0f).x else f.mapper.toCanvas(0f, g.pos).y
                abs((if (g.vertical) startPos.x else startPos.y) - c) <= f.hitRadiusPx * 0.6f
            }
            if (gi >= 0) {
                val g = app.guides[gi]
                feedback.guideDraft = g
                dragGuide(f, g.vertical, feedback, down.id, existing = g)
                return@awaitEachGesture
            }
        }

        // Node Move never starts a marquee that could select handles.
        if (app.nodeMoveMode) {
            followOrPinch(f, down.id, f.dragStartSlopPx) { _, _ -> }
            return@awaitEachGesture
        }

        // Empty space: rubber band (or pinch on touch).
        val preserved = if (event.keyboardModifiers.isShiftPressed) target.selection else emptySet()
        // Ctrl/Cmd + drag with a mouse pans (no middle button needed); it never starts a marquee.
        if (down.type == PointerType.Mouse && (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed)) {
            var last = startPos
            followOrPinch(f, down.id, 0.5f) { pos, _ ->
                panBy(f, pos - last)
                last = pos
            }
            return@awaitEachGesture
        }
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
                    if (isRetracted(c.points, pi)) return@forEachIndexed
                    val pos = f.mapper.toCanvas(p.x, p.y)
                    if (pos.x in x0..x1 && pos.y in y0..y1) newSel.add(ci to pi)
                }
            }
            target.selection = preserved + newSel
        }
        // The selection is cleared only once this is known to be a tap on empty space or a marquee: a pinch that
        // begins on empty space keeps what was selected.
        if (completed && !rubberBanding && preserved.isEmpty()) target.selection = emptySet()
        if (rubberBanding || !completed) feedback.rubberRect = null
        if (rubberBanding && target.selection.isNotEmpty()) app.moveCircleVisible = true
    }
}

/**
 * One press with an anchor-point tool, as in Photoshop. Add: tap a segment to insert a node there (hold and
 * drag to place it). Subtract: tap a node to remove it. Convert: tap a node to strip its handles and make
 * it a corner, drag from a node to pull out symmetric handles, drag a handle to move it alone.
 */
private suspend fun AwaitPointerEventScope.nodeToolGesture(
    f: CanvasFrame,
    tool: NodeTool,
    downId: PointerId,
    target: AnchorState,
    startPos: Offset,
    feedback: CanvasFeedback,
) {
    val app = f.app
    val hit = hitTestPoint(target.glyph, f.mapper, startPos, radiusPx = f.hitRadiusPx)
    val hitPoint = hit?.let { (ci, pi) -> target.glyph.contours.getOrNull(ci)?.points?.getOrNull(pi) }
    when (tool) {
        NodeTool.ADD -> {
            val seg = if (hit == null) hitTestSegment(target.glyph, f.mapper, startPos, f.hitRadiusPx) else null
            if (seg == null) { followOrPinch(f, downId, f.dragStartSlopPx) { _, _ -> }; return }
            when (awaitTapOrMove(downId, startPos, f.dragStartSlopPx)) {
                TapEnd.RELEASED -> app.addNodeAt(seg)
                TapEnd.PINCHED -> pinch(f)
                TapEnd.MOVED -> {
                    val key = app.addNodeAt(seg)
                    if (key != null) dragSelection(f, downId, target, listOf(key), startPos, 0.5f, feedback, primary = key, recordHistory = false)
                    else followOrPinch(f, downId, Float.MAX_VALUE) { _, _ -> }
                }
            }
        }
        NodeTool.SUBTRACT -> {
            if (hit == null || hitPoint?.onCurve != true) { followOrPinch(f, downId, f.dragStartSlopPx) { _, _ -> }; return }
            when (awaitTapOrMove(downId, startPos, f.dragStartSlopPx)) {
                TapEnd.RELEASED -> app.subtractNode(hit)
                TapEnd.PINCHED -> pinch(f)
                TapEnd.MOVED -> followOrPinch(f, downId, Float.MAX_VALUE) { _, _ -> }
            }
        }
        NodeTool.CONVERT -> {
            if (hit == null || hitPoint == null) { followOrPinch(f, downId, f.dragStartSlopPx) { _, _ -> }; return }
            if (!hitPoint.onCurve) {
                target.selection = setOf(hit)
                dragSelection(f, downId, target, listOf(hit), startPos, f.dragStartSlopPx, feedback, breakSymmetry = true)
                return
            }
            when (awaitTapOrMove(downId, startPos, f.dragStartSlopPx)) {
                TapEnd.RELEASED -> app.convertNodeToCorner(hit)
                TapEnd.PINCHED -> pinch(f)
                TapEnd.MOVED -> {
                    target.selection = setOf(hit)
                    val orig = target.glyph.deepCopy()
                    val targets = if (app.view.snap) snapTargetsFor(f, target, setOf(hit), forHandles = true) else null
                    var pushed = false
                    fun pull(pos: Offset) {
                        if (!pushed) { target.pushHistory(); pushed = true }
                        val p = f.mapper.toFont(pos)
                        var x = p.x; var y = p.y
                        if (targets != null) {
                            val r = snapDelta(listOf(x), listOf(y), 0f, 0f, targets, snapTolerance(f))
                            x += r.dx; y += r.dy
                            feedback.snapX = r.xLine; feedback.snapY = r.yLine
                        }
                        target.replaceGlyph(pullSymmetricHandles(orig, hit, x, y))
                    }
                    pull(lastTapPosition)
                    followOrPinch(f, downId, 0.5f, allowPinch = false) { pos, _ -> pull(pos) }
                    feedback.clearSnap()
                }
            }
        }
    }
}

/**
 * Waits for [downId] to lift, stray past [slop] from [startPos], or for a second finger to land.
 * Where it strayed to is left in [lastTapPosition], so the drag it starts can use that first move.
 */
private var lastTapPosition = Offset.Zero

private suspend fun AwaitPointerEventScope.awaitTapOrMove(downId: PointerId, startPos: Offset, slop: Float): TapEnd {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.count { it.pressed } >= 2) { ev.changes.forEach { it.consume() }; return TapEnd.PINCHED }
        val c = ev.changes.firstOrNull { it.id == downId } ?: return TapEnd.RELEASED
        c.consume()
        lastTapPosition = c.position
        when {
            !c.pressed -> return TapEnd.RELEASED
            (c.position - startPos).getDistance() > slop -> return TapEnd.MOVED
        }
    }
}

private enum class TapEnd { RELEASED, MOVED, PINCHED }

private fun snapTolerance(f: CanvasFrame): Float = 7f * f.density / f.mapper.scale

private fun snapTargetsFor(f: CanvasFrame, target: AnchorState, exclude: Set<PointKey>, forHandles: Boolean = false): SnapTargets {
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
        forHandles = forHandles,
    )
    if (editingGhost == null || forHandles) return base
    return base.copy(xs = base.xs + SnapTarget(anchorGlyph.width, "advance"))
}

/**
 * A handle that has just lined up with another handle -- on the vertical line x = [line] ([vertical]) or the
 * horizontal line y = [line] -- also takes that handle's length (its distance to its own node) or its angle,
 * whichever puts it somewhere on the same line close to where it is. [node] is the dragged handle's node,
 * [matched] the handle it lined up with and [matchedNode] that handle's node; ([cx], [cy]) is where the
 * dragged handle is now. Returns the new position, or null when neither is within [tolerance] (font units).
 */
internal fun matchedHandleSnap(
    node: Offset, matched: Offset, matchedNode: Offset, vertical: Boolean, line: Float, cx: Float, cy: Float, tolerance: Float,
): Offset? {
    val vx = matched.x - matchedNode.x; val vy = matched.y - matchedNode.y
    val length = hypot(vx, vy)
    if (length < 1e-3f) return null
    val candidates = mutableListOf<Offset>()
    val off = if (vertical) line - node.x else line - node.y
    // The same distance from the node, on the line.
    if (length >= abs(off)) {
        val along = sqrt(length * length - off * off)
        if (vertical) { candidates.add(Offset(line, node.y + along)); candidates.add(Offset(line, node.y - along)) }
        else { candidates.add(Offset(node.x + along, line)); candidates.add(Offset(node.x - along, line)) }
    }
    // The same angle from the node, on the line.
    val ux = vx / length; val uy = vy / length
    val component = if (vertical) ux else uy
    if (abs(component) > 1e-4f) {
        val t = off / component
        if (t > 0f) candidates.add(Offset(node.x + t * ux, node.y + t * uy))
    }
    return candidates.minByOrNull { hypot(it.x - cx, it.y - cy) }?.takeIf { hypot(it.x - cx, it.y - cy) <= tolerance }
}

/**
 * Points a dragged handle must not snap to: the handles being dragged, and -- on a mirrored node only --
 * the twin that moves with them. On a corner node the other handle stays put, so it is a target: a handle
 * lines up vertically or horizontally with its own node's other handle.
 */
internal fun handleSnapSkip(glyph: GlyphCorner, keys: Collection<PointKey>): Set<PointKey> {
    val skip = keys.toMutableSet()
    for ((ci, pi) in keys) {
        val pts = glyph.contours.getOrNull(ci)?.points ?: continue
        val owner = armOwner(pts, pi) ?: continue
        if (!pts[owner].smooth) continue
        inArmIndex(pts, owner)?.let { skip.add(ci to it) }
        outArmIndex(pts, owner)?.let { skip.add(ci to it) }
    }
    return skip
}

/** Drags [keys] of [target] by the pointer delta (nodes carry their arms, see [dragPoints]), with light snapping, recording one undo step. */
private suspend fun AwaitPointerEventScope.dragSelection(
    f: CanvasFrame,
    downId: PointerId,
    target: AnchorState,
    keys: List<PointKey>,
    startPos: Offset,
    slop: Float,
    feedback: CanvasFeedback,
    primary: PointKey? = null,
    snapEnabled: Boolean = true,
    allowPinch: Boolean = true,
    /** False when the drag continues an edit that already recorded its undo step (Add Anchor Point). */
    recordHistory: Boolean = true,
    /** Convert Point: a dragged handle moves alone, so its node stops being mirrored. */
    breakSymmetry: Boolean = false,
) {
    if (keys.isEmpty()) return
    val origGlyph = target.glyph.deepCopy()
    if (breakSymmetry) for ((ci, pi) in keys) {
        val pts = origGlyph.contours.getOrNull(ci)?.points ?: continue
        armOwner(pts, pi)?.let { pts[it].smooth = false }
    }
    val origPts = keys.mapNotNull { (ci, pi) -> origGlyph.contours.getOrNull(ci)?.points?.getOrNull(pi) }
    val primaryPt = primary?.let { (ci, pi) -> origGlyph.contours.getOrNull(ci)?.points?.getOrNull(pi) }
    // A grabbed point snaps by itself; a selection snaps by its edges and centre.
    val (movingXs, movingYs) = if (primaryPt != null) listOf(primaryPt.x) to listOf(primaryPt.y) else {
        val b = boundsOf(origPts)!!
        listOf(b.minX, b.cx, b.maxX) to listOf(b.minY, b.cy, b.maxY)
    }
    // Nodes lightly snap to other nodes, handles to other handles (a handle's mirrored twin moves with it,
    // so it is not a target either), each vertically or horizontally.
    val handleMode = keys.none { (ci, pi) -> origGlyph.contours.getOrNull(ci)?.points?.getOrNull(pi)?.onCurve == true }
    val skip = if (handleMode) handleSnapSkip(origGlyph, keys) else keys.toSet()
    val targets = if (snapEnabled && f.app.view.snap) snapTargetsFor(f, target, skip, handleMode) else null
    var pushed = false
    followOrPinch(f, downId, slop, allowPinch = allowPinch) { pos, _ ->
        if (!pushed) { if (recordHistory) target.pushHistory(); pushed = true }
        val delta = pos - startPos
        var dx = delta.x / f.mapper.scale
        var dy = -delta.y / f.mapper.scale
        if (targets != null) {
            val r = snapDelta(movingXs, movingYs, dx, dy, targets, snapTolerance(f))
            dx = r.dx; dy = r.dy
            feedback.snapX = r.xLine; feedback.snapY = r.yLine
            // Lined up with another handle on one axis: also take its length / angle to its node.
            if (handleMode && primaryPt != null && !primaryPt.onCurve && (r.xLine == null) != (r.yLine == null)) {
                val own = primary?.let { (ci, pi) -> origGlyph.contours.getOrNull(ci)?.points?.let { pts -> armOwner(pts, pi)?.let { pts[it] } } }
                val vertical = r.xLine != null
                val line = r.xLine ?: r.yLine!!
                var matched: Pair<Pt, Pt>? = null
                var nearest = Float.POSITIVE_INFINITY
                for ((ci, c) in origGlyph.contours.withIndex()) for ((pi, p) in c.points.withIndex()) {
                    if (p.onCurve || (ci to pi) in skip || isRetracted(c.points, pi)) continue
                    if (abs((if (vertical) p.x else p.y) - line) > 0.01f) continue
                    val owner = armOwner(c.points, pi)?.let { c.points[it] } ?: continue
                    val d = hypot(p.x - (primaryPt.x + dx), p.y - (primaryPt.y + dy))
                    if (d < nearest) { nearest = d; matched = p to owner }
                }
                if (own != null && matched != null) {
                    matchedHandleSnap(
                        Offset(own.x, own.y), Offset(matched.first.x, matched.first.y), Offset(matched.second.x, matched.second.y),
                        vertical, line, primaryPt.x + dx, primaryPt.y + dy, snapTolerance(f) * 1.6f,
                    )?.let { dx = it.x - primaryPt.x; dy = it.y - primaryPt.y }
                }
            }
        }
        target.replaceGlyph(dragPoints(origGlyph, keys, dx, dy))
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
    if (end == null) return // tapping an existing guide changes nothing
    // Releasing back over a ruler deletes the guide, like most editors.
    val overRuler = f.app.view.showRulers && (end.x < f.rulerPx || end.y < f.rulerPx)
    if (overRuler) f.app.replaceGuide(existing, null)
    else if (draft != null) f.app.replaceGuide(existing, draft)
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
    allowPinch: Boolean = true,
    onMove: (Offset, PointerInputChange) -> Unit,
): Boolean {
    var start: Offset? = null
    var committed = false
    while (true) {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.filter { it.pressed }
        if (allowPinch && !committed && pressed.size >= 2) {
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

/** Waits until [downId] lifts or strays past [slop] from [startPos]. */
private suspend fun AwaitPointerEventScope.awaitHoldEnd(downId: PointerId, startPos: Offset, slop: Float): HoldEnd {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull { it.id == downId }
        when {
            c == null || !c.pressed -> return HoldEnd.RELEASED
            (c.position - startPos).getDistance() > slop -> return HoldEnd.MOVED
        }
    }
}

/** How a touch hold on a node ended. */
private enum class HoldEnd { RELEASED, MOVED }
