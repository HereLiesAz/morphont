package com.hereliesaz.morphont

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * The cubic outline model (see [Pt]): a contour is a cyclic list of points
 * where every segment between consecutive on-curve nodes carries either no
 * off-curve points (a straight line) or exactly two (a cubic: the out-arm of
 * the node before it, then the in-arm of the node after it). Everything here
 * is pure and returns fresh data; callers own history/undo.
 */

/** True when every run of off-curve points between nodes has length 0 or 2 (an empty contour is valid). */
fun isValidCubicContour(c: ContourData): Boolean {
    val pts = c.points
    if (pts.isEmpty()) return true
    val start = pts.indexOfFirst { it.onCurve }
    if (start < 0) return false
    var run = 0
    for (step in 1..pts.size) {
        if (pts[(start + step) % pts.size].onCurve) {
            if (run != 0 && run != 2) return false
            run = 0
        } else run++
    }
    return true
}

/** Index of node [i]'s out-arm (the off-curve point right after it), or null on a straight side. */
fun outArmIndex(pts: List<Pt>, i: Int): Int? {
    if (pts.size < 2) return null
    val j = (i + 1) % pts.size
    return if (!pts[j].onCurve) j else null
}

/** Index of node [i]'s in-arm (the off-curve point right before it), or null on a straight side. */
fun inArmIndex(pts: List<Pt>, i: Int): Int? {
    if (pts.size < 2) return null
    val j = (i - 1 + pts.size) % pts.size
    return if (!pts[j].onCurve) j else null
}

/** The node that off-curve point [j] is an arm of: the node before it for an out-arm, after it for an in-arm. */
fun armOwner(pts: List<Pt>, j: Int): Int? {
    val n = pts.size
    if (n < 2 || pts[j].onCurve) return null
    val prev = (j - 1 + n) % n
    if (pts[prev].onCurve) return prev
    val next = (j + 1) % n
    return if (pts[next].onCurve) next else null
}

/**
 * True for a handle that has been retracted onto its own node (deleted from a corner node): it has no
 * length, so there is nothing to see, grab or snap to. Making the node mirrored brings it back.
 */
fun isRetracted(pts: List<Pt>, j: Int): Boolean {
    val p = pts.getOrNull(j) ?: return false
    if (p.onCurve) return false
    val owner = pts.getOrNull(armOwner(pts, j) ?: return false) ?: return false
    return abs(p.x - owner.x) < 0.01f && abs(p.y - owner.y) < 0.01f
}

/** The other arm of [j]'s owner node, if it has one. */
fun oppositeArm(pts: List<Pt>, j: Int): Int? {
    val o = armOwner(pts, j) ?: return null
    val isOut = (o + 1) % pts.size == j
    val k = if (isOut) inArmIndex(pts, o) else outArmIndex(pts, o)
    return k?.takeIf { it != j }
}

/** Photoshop's symmetric mirror: the arm opposite ([ax], [ay]) through node ([nx], [ny]) -- same line, same length. */
fun mirrorArm(nx: Float, ny: Float, ax: Float, ay: Float): Pair<Float, Float> = (2f * nx - ax) to (2f * ny - ay)

/**
 * Makes every straight segment a collinear cubic without changing its outline.
 * Every editable node therefore has two handles, even next to straight edges.
 * Curved controls remain unchanged; saved input is never mutated.
 */
fun completeNodeHandles(c: ContourData): ContourData {
    if (c.points.isEmpty() || !isValidCubicContour(c)) return c.deepCopy()
    val pts = c.points
    val out = mutableListOf<Pt>()
    for (s in segmentsOf(pts)) {
        val a = pts[s.from]
        val b = pts[s.to]
        out.add(a.copy2())
        val arms = s.arms
        if (arms != null) {
            out.add(arms.first.copy2())
            out.add(arms.second.copy2())
        } else {
            out.add(Pt(a.x + (b.x - a.x) / 3f, a.y + (b.y - a.y) / 3f, false))
            out.add(Pt(b.x - (b.x - a.x) / 3f, b.y - (b.y - a.y) / 3f, false))
        }
    }
    return ContourData(out)
}

/** Normalizes all editable contours, keeping their visible geometry. */
fun completeNodeHandles(g: GlyphCorner): GlyphCorner =
    GlyphCorner(g.width, g.contours.map(::completeNodeHandles).toMutableList())

/** [keys] plus the arms of every node among them, so a selected node always moves with its arms. */
fun withArms(g: GlyphCorner, keys: Collection<PointKey>): Set<PointKey> {
    val out = keys.toMutableSet()
    for ((ci, pi) in keys) {
        val pts = g.contours.getOrNull(ci)?.points ?: continue
        if (pts.getOrNull(pi)?.onCurve != true) continue
        inArmIndex(pts, pi)?.let { out.add(ci to it) }
        outArmIndex(pts, pi)?.let { out.add(ci to it) }
    }
    return out
}

/**
 * Moves [keys] of [orig] by ([dx], [dy]): a node carries its arms along;
 * an arm dragged on its own re-mirrors the opposite arm of a smooth node
 * through it. A corner node's arms move independently.
 */
fun dragPoints(orig: GlyphCorner, keys: Collection<PointKey>, dx: Float, dy: Float): GlyphCorner {
    val moving = withArms(orig, keys)
    val out = orig.deepCopy()
    for ((ci, pi) in moving) {
        val p = out.contours.getOrNull(ci)?.points?.getOrNull(pi) ?: continue
        p.x += dx; p.y += dy
    }
    for ((ci, pi) in keys) {
        val pts = out.contours.getOrNull(ci)?.points ?: continue
        val p = pts.getOrNull(pi) ?: continue
        if (p.onCurve) continue
        val o = armOwner(pts, pi) ?: continue
        if ((ci to o) in moving) continue
        val node = pts[o]
        if (!node.smooth) continue
        val k = oppositeArm(pts, pi) ?: continue
        if ((ci to k) in moving) continue
        val (mx, my) = mirrorArm(node.x, node.y, p.x, p.y)
        pts[k].x = mx; pts[k].y = my
    }
    return out
}

/** Moves node [i] of [pts] to ([x], [y]) and its arms by the same delta. */
fun moveNodeWithArms(pts: MutableList<Pt>, i: Int, x: Float, y: Float) {
    val p = pts[i]
    val dx = x - p.x; val dy = y - p.y
    inArmIndex(pts, i)?.let { pts[it].x += dx; pts[it].y += dy }
    outArmIndex(pts, i)?.let { pts[it].x += dx; pts[it].y += dy }
    p.x = x; p.y = y
}

/**
 * Sets the smooth flag on every selected node (and the node of every
 * selected arm). Turning a node with two arms smooth re-aligns them: the
 * new direction is the average of the out-arm's direction and the reversed
 * in-arm's, and both arms take the average of their two lengths -- so the
 * node ends up exactly mirrored with neither arm favoured.
 */
fun setSmooth(g: GlyphCorner, keys: Collection<PointKey>, smooth: Boolean): GlyphCorner {
    val out = g.deepCopy()
    val nodes = keys.mapNotNull { (ci, pi) ->
        val pts = out.contours.getOrNull(ci)?.points ?: return@mapNotNull null
        val p = pts.getOrNull(pi) ?: return@mapNotNull null
        if (p.onCurve) ci to pi else armOwner(pts, pi)?.let { ci to it }
    }.toSet()
    for ((ci, i) in nodes) {
        val pts = out.contours[ci].points
        val node = pts[i]
        node.smooth = smooth
        if (!smooth) continue
        val a = outArmIndex(pts, i) ?: continue
        val b = inArmIndex(pts, i) ?: continue
        if (a == b) continue
        val ox = pts[a].x - node.x; val oy = pts[a].y - node.y
        val ix = pts[b].x - node.x; val iy = pts[b].y - node.y
        val lo = hypot(ox, oy); val li = hypot(ix, iy)
        var ux = (if (lo > 1e-4f) ox / lo else 0f) - (if (li > 1e-4f) ix / li else 0f)
        var uy = (if (lo > 1e-4f) oy / lo else 0f) - (if (li > 1e-4f) iy / li else 0f)
        val lu = hypot(ux, uy)
        if (lu < 1e-4f) continue // arms folded onto each other or both retracted: nothing to align
        ux /= lu; uy /= lu
        // One handle retracted: mirror the surviving one at full length, so the deleted handle comes back
        // exactly opposite it instead of at half size.
        val len = if (minOf(lo, li) < 1e-4f) maxOf(lo, li) else (lo + li) / 2f
        pts[a].x = node.x + ux * len; pts[a].y = node.y + uy * len
        pts[b].x = node.x - ux * len; pts[b].y = node.y - uy * len
    }
    return out
}

/** One segment of a contour: node indices [from] -> [to], with arm points (null pair = straight). */
private class Seg(val from: Int, val to: Int, val arms: Pair<Pt, Pt>?)

/** The contour's segments in order, starting at its first node. Requires a valid contour. */
private fun segmentsOf(pts: List<Pt>): List<Seg> {
    val nodes = pts.indices.filter { pts[it].onCurve }
    return nodes.mapIndexed { k, a ->
        val b = nodes[(k + 1) % nodes.size]
        val out = outArmIndex(pts, a)
        Seg(a, b, if (out != null) pts[out] to pts[inArmIndex(pts, b)!!] else null)
    }
}

/** Rebuilds a contour from segments; returns the new points and each old node index's new index. */
private fun fromSegments(pts: List<Pt>, segs: List<Seg>): Pair<ContourData, Map<Int, Int>> {
    val out = mutableListOf<Pt>()
    val map = HashMap<Int, Int>()
    for (s in segs) {
        map[s.from] = out.size
        out.add(pts[s.from].copy2())
        s.arms?.let { (a, b) -> out.add(a.copy2()); out.add(b.copy2()) }
    }
    return ContourData(out) to map
}

/**
 * Deletes [nodes] (on-curve indices) and retracts [arms] (off-curve indices)
 * of one contour, keeping it valid: the segment joining two surviving nodes
 * keeps the first's out-arm and the second's in-arm; a missing one sits on
 * its own node, and a segment left with neither becomes straight. Returns
 * an empty contour when no node survives.
 */
fun deleteFromContour(c: ContourData, nodes: Set<Int>, arms: Set<Int> = emptySet(), mergeIntoPrevious: Boolean = false): ContourData {
    val pts = c.points
    val alive = pts.indices.filter { pts[it].onCurve && it !in nodes }
    if (alive.isEmpty()) return ContourData()
    val onCurve = pts.indices.filter { pts[it].onCurve }
    val segs = alive.mapIndexed { k, a ->
        val b = alive[(k + 1) % alive.size]
        // Merging duplicates: a removed node sits on [a], so the curve leaving the pair is the removed node's out-arm.
        var outFrom = a
        if (mergeIntoPrevious) {
            var oi = (onCurve.indexOf(a) + 1) % onCurve.size
            while (onCurve[oi] in nodes && onCurve[oi] != a) { outFrom = onCurve[oi]; oi = (oi + 1) % onCurve.size }
        }
        val out = outArmIndex(pts, outFrom)?.takeIf { it !in arms }?.let { pts[it] }
        val inn = inArmIndex(pts, b)?.takeIf { it !in arms }?.let { pts[it] }
        val pair = when {
            out == null && inn == null -> null
            else -> (out ?: pts[outFrom].copy2().also { it.onCurve = false; it.smooth = false }) to
                (inn ?: pts[b].copy2().also { it.onCurve = false; it.smooth = false })
        }
        Seg(a, b, pair)
    }
    return fromSegments(pts, segs).first
}

/** Deletes the selected nodes (with their arms) and retracts selected arms of surviving nodes. Empty contours are dropped. */
fun deleteSelection(g: GlyphCorner, selection: Set<PointKey>): GlyphCorner =
    GlyphCorner(
        g.width,
        g.contours.mapIndexed { ci, c ->
            val sel = c.points.indices.filter { (ci to it) in selection }
            deleteFromContour(c, sel.filter { c.points[it].onCurve }.toSet(), sel.filter { !c.points[it].onCurve }.toSet())
        }.filter { it.points.isNotEmpty() }.toMutableList(),
    )

/**
 * Line <-> curve for the selection. The segments acted on are those with
 * both end nodes selected or one of their arms selected; failing that,
 * every segment touching a selected node. Every editor segment has handles,
 * even lines; identify a line by its collinear 1/3 and 2/3 controls.
 * Convert lines into visibly bowed curves and existing curves into lines.
 * Returns the new glyph and the selection remapped onto the same nodes.
 */
fun toggleCurveSelection(g: GlyphCorner, selection: Set<PointKey>): Pair<GlyphCorner, Set<PointKey>> {
    val targets = HashMap<Int, Set<Int>>() // contour -> the `from` node of each segment acted on
    g.contours.forEachIndexed { ci, c ->
        if (c.points.none { it.onCurve }) return@forEachIndexed
        val segs = segmentsOf(c.points)
        fun selected(i: Int?) = i != null && (ci to i) in selection
        var picked = segs.filter { s ->
            (s.from != s.to && selected(s.from) && selected(s.to)) ||
                (s.arms != null && (selected(outArmIndex(c.points, s.from)) || selected(inArmIndex(c.points, s.to))))
        }
        if (picked.isEmpty()) picked = segs.filter { selected(it.from) || selected(it.to) }
        if (picked.isNotEmpty()) targets[ci] = picked.map { it.from }.toSet()
    }
    fun straightSegment(points: List<Pt>, segment: Seg): Boolean {
        val controls = segment.arms ?: return true
        val start = points[segment.from]
        val end = points[segment.to]
        val dx = end.x - start.x
        val dy = end.y - start.y
        val eps = 0.001f
        return kotlin.math.abs(controls.first.x - (start.x + dx / 3f)) <= eps &&
            kotlin.math.abs(controls.first.y - (start.y + dy / 3f)) <= eps &&
            kotlin.math.abs(controls.second.x - (start.x + dx * 2f / 3f)) <= eps &&
            kotlin.math.abs(controls.second.y - (start.y + dy * 2f / 3f)) <= eps
    }
    val anyLine = targets.any { (ci, froms) ->
        val points = g.contours[ci].points
        segmentsOf(points).any { it.from in froms && straightSegment(points, it) }
    }
    val out = g.deepCopy()
    val newSel = selection.filter { it.first !in targets }.toMutableSet()
    for ((ci, froms) in targets) {
        val pts = g.contours[ci].points
        val segs = segmentsOf(pts).map { s ->
            when {
                s.from !in froms -> s
                !anyLine -> Seg(s.from, s.to, null)
                !straightSegment(pts, s) -> s
                else -> {
                    val a = pts[s.from]; val b = pts[s.to]
                    val vx = b.x - a.x; val vy = b.y - a.y
                    // A collinear cubic is still a line. Give the Curve tool a
                    // meaningful initial bow, proportional to segment length.
                    val liftX = -vy * 0.2f
                    val liftY = vx * 0.2f
                    Seg(s.from, s.to,
                        Pt(a.x + vx / 3f + liftX, a.y + vy / 3f + liftY, false) to
                        Pt(a.x + vx * 2f / 3f + liftX, a.y + vy * 2f / 3f + liftY, false))
                }
            }
        }
        val (contour, map) = fromSegments(pts, segs)
        out.contours[ci] = contour
        for ((sci, pi) in selection) if (sci == ci) map[pi]?.let { newSel.add(ci to it) }
    }
    return out to newSel
}

/** Appends a corner node at ([x], [y]) to a contour being drawn, splitting the closing segment around it. */
fun appendDrawNode(c: ContourData, x: Float, y: Float): ContourData {
    val pts = c.points.map { it.copy2() }.toMutableList()
    val node = Pt(x, y, onCurve = true)
    if (pts.isEmpty()) return ContourData(mutableListOf(node))
    val last = pts.indexOfLast { it.onCurve }
    val tail = pts.subList(last + 1, pts.size).toList() // the closing segment's arms: [lastOut, firstIn] or none
    while (pts.size > last + 1) pts.removeAt(pts.lastIndex)
    // A side without an arm of its own gets one on its node (retracted), so the new segment stays a cubic.
    if (tail.size == 2) { pts.add(tail[0]); pts.add(Pt(x, y, false)) }
    pts.add(node)
    if (tail.size == 2) { pts.add(Pt(x, y, false)); pts.add(tail[1]) }
    return ContourData(pts)
}

/**
 * Photoshop-pen drag on the last-placed node: makes it smooth with its
 * out-arm at ([ax], [ay]) and its in-arm mirrored, turning the segments on
 * both sides of it into cubics (a neighbour without an arm gets a
 * retracted one, sitting on that neighbour).
 */
fun setDrawArm(c: ContourData, ax: Float, ay: Float): ContourData {
    val pts = c.points.map { it.copy2() }.toMutableList()
    val li = pts.indexOfLast { it.onCurve }
    if (li < 0) return c
    val node = pts[li]
    node.smooth = true
    val (mx, my) = mirrorArm(node.x, node.y, ax, ay)
    val first = pts.indexOfFirst { it.onCurve }
    if (first == li) {
        // The only node: its one segment closes back onto itself.
        while (pts.size > li + 1) pts.removeAt(pts.lastIndex)
        pts.add(Pt(ax, ay, false)); pts.add(Pt(mx, my, false))
        return ContourData(pts)
    }
    // Closing segment (after the node, at the end of the list).
    if (li + 1 < pts.size) { pts[li + 1].x = ax; pts[li + 1].y = ay }
    else { val f = pts[first]; pts.add(Pt(ax, ay, false)); pts.add(Pt(f.x, f.y, false)) }
    // Segment into the node.
    if (!pts[li - 1].onCurve) { pts[li - 1].x = mx; pts[li - 1].y = my }
    else { val p = pts[li - 1]; pts.add(li, Pt(mx, my, false)); pts.add(li, Pt(p.x, p.y, false)) }
    return ContourData(pts)
}

// ------------------------------------------------- quadratic (TrueType) import

/**
 * Converts a TrueType quadratic contour (runs of off-curve points implying
 * on-curve midpoints) into the cubic model, exactly: each implied midpoint
 * becomes a real smooth node, and each quadratic `Q(P0, C, P1)` becomes the
 * cubic `(P0, P0 + 2/3 (C - P0), P1 + 2/3 (C - P1), P1)`, which traces the
 * same curve. An all-off-curve contour (a TrueType circle) gets a node at
 * every midpoint. Existing on-curve flags are kept.
 */
fun quadraticToCubic(c: ContourData): ContourData {
    val src = c.points
    if (src.isEmpty()) return ContourData()
    val n = src.size
    // Expand implied midpoints so no two off-curve points are adjacent.
    val q = mutableListOf<Pt>()
    for (i in 0 until n) {
        val p = src[i]
        q.add(p.copy2())
        val nx = src[(i + 1) % n]
        if (!p.onCurve && !nx.onCurve) q.add(Pt((p.x + nx.x) / 2f, (p.y + nx.y) / 2f, onCurve = true, smooth = true))
    }
    val start = q.indexOfFirst { it.onCurve }
    if (start < 0) return ContourData() // a lone off-curve point: no outline
    val out = mutableListOf<Pt>()
    val m = q.size
    var i = start
    do {
        val p0 = q[i]
        out.add(p0)
        val nx = q[(i + 1) % m]
        if (nx.onCurve) i = (i + 1) % m
        else {
            val p1 = q[(i + 2) % m]
            out.add(Pt(p0.x + 2f / 3f * (nx.x - p0.x), p0.y + 2f / 3f * (nx.y - p0.y), false))
            out.add(Pt(p1.x + 2f / 3f * (nx.x - p1.x), p1.y + 2f / 3f * (nx.y - p1.y), false))
            i = (i + 2) % m
        }
    } while (i != start)
    markTangentNodesSmooth(out)
    return ContourData(out)
}

/**
 * Converted outlines assume smooth curves wherever they can: a node whose
 * in- and out-arms already leave it in opposite directions (within about
 * 2 degrees) is marked smooth. Nodes with a missing or zero-length arm, or a
 * real angle between the arms, stay corners.
 */
private fun markTangentNodesSmooth(pts: MutableList<Pt>) {
    val n = pts.size
    if (n < 3) return
    for (i in 0 until n) {
        val p = pts[i]
        if (!p.onCurve) continue
        val a = pts[(i - 1 + n) % n]
        val b = pts[(i + 1) % n]
        if (a.onCurve || b.onCurve) continue
        val ax = a.x - p.x; val ay = a.y - p.y
        val bx = b.x - p.x; val by = b.y - p.y
        val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
        if (la < 1e-3f || lb < 1e-3f) continue
        val cross = (ax * by - ay * bx) / (la * lb)
        val dot = (ax * bx + ay * by) / (la * lb)
        if (dot < 0f && abs(cross) < 0.035f) p.smooth = true
    }
}

fun quadraticToCubic(contours: List<ContourData>): MutableList<ContourData> =
    contours.map { quadraticToCubic(it) }.filter { it.points.isNotEmpty() }.toMutableList()
