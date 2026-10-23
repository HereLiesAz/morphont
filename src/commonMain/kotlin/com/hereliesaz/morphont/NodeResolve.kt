package com.hereliesaz.morphont

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/*
 * Resolving mismatched nodes between the EXTREMES.
 *
 * The extremes are the drawings; Regular is derived from them. So when two extremes disagree about their
 * nodes, the correspondence is worked out between the extremes themselves -- never through Regular, whose
 * layout says nothing about which node of one extreme answers to which node of the other.
 *
 * Two outlines are aligned as cyclic sequences of nodes (either start, the winding they share), scoring each
 * possible pairing by where the node sits in its own contour, which way the outline runs into and out of it,
 * how sharply it turns, and how far round the contour it is. A node with no good partner is *missing* from
 * the other extreme: it is added there at the point of the matching stretch of outline, the same fraction of
 * the way along it, with the outline unchanged (a de Casteljau split) -- or, the other way round, removed from
 * the extreme that has it. With more than two extremes they are aligned one at a time to a growing shared
 * structure, and every earlier extreme receives each node the structure gains.
 */

enum class ResolveMode {
    /** Every extreme gains the nodes it lacks, so no drawing is lost. */
    ADD_MISSING,

    /** Nodes with no counterpart are taken out of the extreme that has them. */
    MATCH_FEWEST,
}

/** [glyphs] (anchor name to its resolved outline), or a [problem]; [copied]/[dropped] count contours supplied from, or lost to, another extreme. */
class ResolveOutcome(val glyphs: Map<String, GlyphCorner>?, val copied: Int = 0, val dropped: Int = 0, val problem: String? = null)

// ----------------------------------------------------------------- geometry

private fun Loop.seg(j: Int): List<Pt> = listOf(nodes[j], outs[j], ins[(j + 1) % size], nodes[(j + 1) % size])

private fun bez(c: List<Pt>, t: Float): Pair<Float, Float> {
    val u = 1f - t
    return (u * u * u * c[0].x + 3f * u * u * t * c[1].x + 3f * u * t * t * c[2].x + t * t * t * c[3].x) to
        (u * u * u * c[0].y + 3f * u * u * t * c[1].y + 3f * u * t * t * c[2].y + t * t * t * c[3].y)
}

private const val ARC_SAMPLES = 24

/** Cumulative arc length at [ARC_SAMPLES]+1 evenly spaced parameters of segment [j]. */
private fun Loop.arcTable(j: Int): FloatArray {
    val c = seg(j)
    val out = FloatArray(ARC_SAMPLES + 1)
    var prev = bez(c, 0f)
    for (k in 1..ARC_SAMPLES) {
        val p = bez(c, k.toFloat() / ARC_SAMPLES)
        out[k] = out[k - 1] + hypot(p.first - prev.first, p.second - prev.second)
        prev = p
    }
    return out
}

private fun Loop.segLen(j: Int): Float = arcTable(j).last()

/** The parameter t of segment [j] at which the fraction [u] of its arc length has been covered. */
private fun Loop.tAtFraction(j: Int, u: Float): Float {
    val tbl = arcTable(j)
    val total = tbl.last()
    if (total < 1e-6f) return u
    val target = u.coerceIn(0f, 1f) * total
    for (k in 1..ARC_SAMPLES) {
        if (tbl[k] >= target) {
            val span = tbl[k] - tbl[k - 1]
            val f = if (span < 1e-9f) 0f else (target - tbl[k - 1]) / span
            return (k - 1 + f) / ARC_SAMPLES
        }
    }
    return 1f
}

private fun Loop.outline(perSeg: Int = 12): List<Pt> =
    (0 until size).flatMap { j -> val c = seg(j); (0 until perSeg).map { k -> bez(c, k.toFloat() / perSeg).let { Pt(it.first, it.second, true) } } }

/** Splits segment [j] at parameter [t]: the outline is unchanged, a node is added after node [j]. */
private fun Loop.splitAt(j: Int, t: Float): Loop {
    val c = seg(j)
    fun mid(p: Pt, q: Pt) = Pt(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, false)
    val q0 = mid(c[0], c[1]); val q1 = mid(c[1], c[2]); val q2 = mid(c[2], c[3])
    val r0 = mid(q0, q1); val r1 = mid(q1, q2)
    val m = mid(r0, r1)
    val inLen = hypot(m.x - r0.x, m.y - r0.y)
    val outLen = hypot(r1.x - m.x, r1.y - m.y)
    // Smooth means mirrored arms; a split's arms are collinear but in the ratio t : 1 - t, so only a split
    // near the middle of a real curve is smooth (see splitSegment).
    val bent = abs((c[1].x - c[0].x) * (c[3].y - c[0].y) - (c[1].y - c[0].y) * (c[3].x - c[0].x)) > 1e-3f ||
        abs((c[2].x - c[0].x) * (c[3].y - c[0].y) - (c[2].y - c[0].y) * (c[3].x - c[0].x)) > 1e-3f
    val node = Pt(m.x, m.y, true, smooth = bent && abs(inLen - outLen) <= 0.01f * maxOf(inLen, outLen, 1e-6f))
    val nodes = this.nodes.toMutableList(); val outs = this.outs.toMutableList(); val ins = this.ins.toMutableList()
    val next = (j + 1) % size
    outs[j] = q0
    ins[next] = q2
    nodes.add(j + 1, node); outs.add(j + 1, r1); ins.add(j + 1, r0)
    return Loop(nodes, outs, ins)
}

/** Where to add a node: after node [seg], [u] of the way along that segment's arc length. */
private class Ins(val seg: Int, val u: Float)

/** [loop] with the nodes of [specs] added. The specs name segments and arc fractions of [loop] as it is now. */
private fun insertNodes(loop: Loop, specs: List<Ins>): Loop {
    if (specs.isEmpty()) return loop
    val withT = specs.map { Triple(it.seg, loop.tAtFraction(it.seg, it.u), it) }
    // Last segment first, and within a segment the largest parameter first, so earlier positions stay valid.
    val order = withT.sortedWith(compareByDescending<Triple<Int, Float, Ins>> { it.first }.thenByDescending { it.second })
    var out = loop
    var lastSeg = -1
    var lastT = 1f
    for ((s, t, _) in order) {
        val local = if (s == lastSeg && lastT > 1e-6f) t / lastT else t
        out = out.splitAt(s, local.coerceIn(0.001f, 0.999f))
        lastSeg = s
        lastT = t
    }
    return out
}

/** [loop] without the nodes at [indices], the outline refitted over the gaps. */
private fun removeNodes(loop: Loop, indices: Set<Int>): Loop {
    if (indices.isEmpty()) return loop
    val g = GlyphCorner(0f, mutableListOf(loop.toContour()))
    val reduced = reducedGlyph(g, indices.map { 0 to 3 * it }.toSet()).contours[0]
    return loopOf(completeNodeHandles(reduced))
}

// ------------------------------------------------------------ node features

private fun wrap(a: Float): Float {
    var x = a
    while (x > PI) x -= (2 * PI).toFloat()
    while (x <= -PI) x += (2 * PI).toFloat()
    return x
}

private fun angDiff(a: Float, b: Float) = abs(wrap(a - b))

/** What identifies a node within its contour, in a frame that doesn't depend on how big or where the contour is. */
private class NodeF(val nx: Float, val ny: Float, val tin: Float, val tout: Float, val turn: Float, val sal: Float, val arc: Float)

private fun features(l: Loop): List<NodeF> {
    val n = l.size
    val box = boundsOf(l.outline())
    val w = box?.width?.takeIf { it > 1e-3f } ?: 1f
    val h = box?.height?.takeIf { it > 1e-3f } ?: 1f
    val lens = FloatArray(n) { l.segLen(it) }
    val total = lens.sum().coerceAtLeast(1e-3f)
    var acc = 0f
    return List(n) { j ->
        val nd = l.nodes[j]
        val nextN = l.nodes[(j + 1) % n]; val prevN = l.nodes[(j - 1 + n) % n]
        val o = l.outs[j]; val i2 = l.ins[(j + 1) % n]
        val tout = when {
            hypot(o.x - nd.x, o.y - nd.y) > 0.5f -> atan2(o.y - nd.y, o.x - nd.x)
            hypot(i2.x - nd.x, i2.y - nd.y) > 0.5f -> atan2(i2.y - nd.y, i2.x - nd.x)
            else -> atan2(nextN.y - nd.y, nextN.x - nd.x)
        }
        val inn = l.ins[j]; val po = l.outs[(j - 1 + n) % n]
        val tin = when {
            hypot(nd.x - inn.x, nd.y - inn.y) > 0.5f -> atan2(nd.y - inn.y, nd.x - inn.x)
            hypot(nd.x - po.x, nd.y - po.y) > 0.5f -> atan2(nd.y - po.y, nd.x - po.x)
            else -> atan2(nd.y - prevN.y, nd.x - prevN.x)
        }
        val turn = wrap(tout - tin)
        val f = NodeF((nd.x - (box?.minX ?: 0f)) / w, (nd.y - (box?.minY ?: 0f)) / h, tin, tout, turn, min(1f, abs(turn) / (PI.toFloat() / 2f)), acc / total)
        acc += lens[j]
        f
    }
}

// --------------------------------------------------------------- alignment

private const val MAX_MATCH = 0.62f
private const val GAP_BASE = 0.45f
private const val GAP_SALIENCE = 0.5f

private fun matchCost(a: NodeF, ra: Float, b: NodeF, rb: Float): Float {
    val pos = hypot(a.nx - b.nx, a.ny - b.ny)
    val dir = (angDiff(a.tin, b.tin) + angDiff(a.tout, b.tout)) / PI.toFloat()
    val turn = abs(a.turn - b.turn) / PI.toFloat()
    val arc = abs(ra - rb).let { min(it, 1f - it) }
    return 1.0f * pos + 1.0f * dir + 1.5f * turn + 2.0f * arc
}

private fun gapCost(a: NodeF) = GAP_BASE + GAP_SALIENCE * a.sal

/** One step of an alignment: both set is a pairing, one set is a node the other contour has no match for. */
private class Step(val a: Int?, val b: Int?)

private class Alignment(val cost: Float, val steps: List<Step>)

/**
 * The best alignment of [fa] and [fb] with node [ia] of the first forced onto node [ib] of the second, both
 * read round the contour from there, or null when that pairing is not even plausible.
 */
private fun align(fa: List<NodeF>, fb: List<NodeF>, ia: Int, ib: Int): Alignment? {
    val n = fa.size; val m = fb.size
    fun a(i: Int) = fa[(ia + i) % n]
    fun b(j: Int) = fb[(ib + j) % m]
    fun rel(f: NodeF, start: NodeF) = ((f.arc - start.arc) % 1f + 1f) % 1f
    val a0 = fa[ia]; val b0 = fb[ib]
    val first = matchCost(a0, 0f, b0, 0f)
    if (first > MAX_MATCH) return null
    val inf = Float.POSITIVE_INFINITY
    val f = Array(n + 1) { FloatArray(m + 1) { inf } }
    val back = Array(n + 1) { ByteArray(m + 1) } // 1 = pair, 2 = A alone, 3 = B alone
    f[1][1] = first
    for (j in 2..m) { f[1][j] = f[1][j - 1] + gapCost(b(j - 1)); back[1][j] = 3 }
    for (i in 2..n) { f[i][1] = f[i - 1][1] + gapCost(a(i - 1)); back[i][1] = 2 }
    for (i in 2..n) for (j in 2..m) {
        var best = f[i - 1][j] + gapCost(a(i - 1)); var how: Byte = 2
        val viaB = f[i][j - 1] + gapCost(b(j - 1))
        if (viaB < best) { best = viaB; how = 3 }
        val c = matchCost(a(i - 1), rel(a(i - 1), a0), b(j - 1), rel(b(j - 1), b0))
        if (c <= MAX_MATCH) {
            val viaPair = f[i - 1][j - 1] + c
            if (viaPair < best) { best = viaPair; how = 1 }
        }
        f[i][j] = best; back[i][j] = how
    }
    val steps = ArrayList<Step>()
    var i = n; var j = m
    while (i > 1 || j > 1) {
        when (back[i][j].toInt()) {
            1 -> { steps.add(Step(i - 1, j - 1)); i--; j-- }
            2 -> { steps.add(Step(i - 1, null)); i-- }
            else -> { steps.add(Step(null, j - 1)); j-- }
        }
    }
    steps.add(Step(0, 0))
    steps.reverse()
    return Alignment(f[n][m], steps)
}

/** How one contour was lined up with another, in the frames read from the chosen starting nodes. */
private class Plan(val rot: Int, val insert: List<Ins>, val remove: Set<Int>, val other: Loop) {
    /** [l], a loop with the structure the first contour had before this plan, brought to the structure it has after. */
    fun applyTo(l: Loop): Loop {
        var r = l.rotated(rot % l.size)
        if (insert.isNotEmpty()) r = insertNodes(r, insert)
        if (remove.isNotEmpty()) r = removeNodes(r, remove)
        return r
    }
}

/** Lines [b] up with [a] (see the file comment), or null if the contours are too small to align. */
private fun planFor(a: Loop, b0: Loop, mode: ResolveMode): Plan? {
    if (a.size < 2 || b0.size < 2) return null
    // Read B the way A runs round, whichever way it was drawn.
    val b = if ((nodeArea(a.toContour()) < 0f) != (nodeArea(b0.toContour()) < 0f)) b0.reversed() else b0
    val fa = features(a); val fb = features(b)
    // A's starting candidates: its sharpest nodes (a missing node is rarely a corner), else the first few.
    val starts = (fa.indices.sortedByDescending { fa[it].sal }.take(4)).sorted()
    var best: Triple<Int, Int, Alignment>? = null
    for (ia in starts) for (ib in fb.indices) {
        val al = align(fa, fb, ia, ib) ?: continue
        if (best == null || al.cost < best.third.cost) best = Triple(ia, ib, al)
    }
    // No pairing of nodes is plausible (a very small contour, say): pin the two nodes that sit closest in their
    // contours and treat every other node as missing from the other side, so the nodes of both survive and are
    // placed along the other outline by arc length.
    val (ia, ib, al) = best ?: run {
        var bi = 0; var bj = 0; var bd = Float.POSITIVE_INFINITY
        for (i in fa.indices) for (j in fb.indices) {
            val d = hypot(fa[i].nx - fb[j].nx, fa[i].ny - fb[j].ny)
            if (d < bd) { bd = d; bi = i; bj = j }
        }
        val steps = listOf(Step(0, 0)) + (1 until fa.size).map { Step(it, null) } + (1 until fb.size).map { Step(null, it) }
        Triple(bi, bj, Alignment(bd, steps))
    }
    val ao = a.rotated(ia); val bo = b.rotated(ib)
    val n = ao.size; val m = bo.size
    val aLens = FloatArray(n) { ao.segLen(it) }; val bLens = FloatArray(m) { bo.segLen(it) }
    val matched = al.steps.filter { it.a != null && it.b != null }
    val aOnly = al.steps.mapNotNull { s -> if (s.b == null) s.a else null }.toSet()
    val bOnly = al.steps.mapNotNull { s -> if (s.a == null) s.b else null }.toSet()
    if (mode == ResolveMode.MATCH_FEWEST && matched.size >= 3) {
        return Plan(ia, emptyList(), aOnly, removeNodes(bo, bOnly))
    }
    // ADD_MISSING (or too little in common to take nodes out): runs of unmatched nodes between matched ones.
    val insA = ArrayList<Ins>(); val insB = ArrayList<Ins>()
    val eps = 1e-6f
    for (k in matched.indices) {
        val p = matched[k]; val q = matched.getOrNull(k + 1)
        val pa = p.a!!; val pb = p.b!!
        val qa = q?.a ?: n; val qb = q?.b ?: m
        val aNodes = (pa + 1 until qa).toList(); val bNodes = (pb + 1 until qb).toList()
        if (aNodes.isEmpty() && bNodes.isEmpty()) continue
        fun chain(lens: FloatArray, size: Int, from: Int, to: Int): Pair<FloatArray, Float> {
            val cum = FloatArray(to - from + 1)
            for (s in from until to) cum[s - from + 1] = cum[s - from] + lens[s % size]
            return cum to cum.last()
        }
        val (cumA, lenA) = chain(aLens, n, pa, qa)
        val (cumB, lenB) = chain(bLens, m, pb, qb)
        fun frac(cum: FloatArray, len: Float, idx: Int, from: Int, count: Int) =
            if (len < 1e-6f) (idx - from).toFloat() / count else cum[idx - from] / len
        // Where each unmatched node sits along its own chain, 0 (the matched node before) to 1 (the one after).
        val fa2 = aNodes.associateWith { frac(cumA, lenA, it, pa, qa - pa) }
        val fb2 = bNodes.associateWith { frac(cumB, lenB, it, pb, qb - pb) }
        fun spot(cum: FloatArray, len: Float, from: Int, to: Int, size: Int, f: Float): Ins {
            val target = f.coerceIn(0f, 1f) * len
            for (s in from until to) {
                val c0 = cum[s - from]; val l = cum[s - from + 1] - c0
                if (target <= cum[s - from + 1] + 1e-6f || s == to - 1) {
                    val u = if (l < 1e-6f) 0.5f else ((target - c0) / l).coerceIn(0.08f, 0.92f)
                    return Ins(s % size, u)
                }
            }
            return Ins(from % size, 0.5f)
        }
        for ((_, f) in fb2) insA.add(spot(cumA, lenA, pa, qa, n, f + eps))
        for ((_, f) in fa2) insB.add(spot(cumB, lenB, pb, qb, m, f - eps))
    }
    return Plan(ia, insA, emptySet(), insertNodes(bo, insB))
}

// -------------------------------------------------------------- contours

private fun polyDepth(loops: List<Loop?>): List<Int> {
    val polys = loops.map { it?.outline() }
    return loops.indices.map { i ->
        val p = polys[i]?.firstOrNull() ?: return@map 0
        polys.indices.count { j -> j != i && polys[j]?.let { pointInside(p.x, p.y, it) } == true }
    }
}

private fun pointInside(x: Float, y: Float, pts: List<Pt>): Boolean {
    var c = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
        j = i
    }
    return c
}

/** For each of [a]'s slots, the index in [b] of the contour standing for it (or -1): by position, size and outer/inner. */
private fun pairContours(a: List<Loop?>, b: List<Loop>): IntArray {
    val out = IntArray(a.size) { -1 }
    val da = polyDepth(a); val db = polyDepth(b)
    val costs = ArrayList<Triple<Float, Int, Int>>()
    for (t in a.indices) {
        val la = a[t] ?: continue
        val ba = boundsOf(la.outline()) ?: continue
        for (s in b.indices) {
            val bb = boundsOf(b[s].outline()) ?: continue
            val scale = maxOf(1f, hypot(ba.width, ba.height))
            val size = abs(ln((bb.width * bb.height + 1f) / (ba.width * ba.height + 1f))) / 2f
            val nesting = if (da[t] % 2 != db[s] % 2) 1.5f else 0f
            costs.add(Triple(hypot(ba.cx - bb.cx, ba.cy - bb.cy) / scale + size + nesting, t, s))
        }
    }
    val used = HashSet<Int>()
    for ((_, t, s) in costs.sortedBy { it.first }) if (out[t] == -1 && s !in used) { out[t] = s; used.add(s) }
    return out
}

private fun nodeArea(c: ContourData): Float {
    val ns = c.points.filter { it.onCurve }
    var a = 0f
    for (i in ns.indices) { val p = ns[i]; val q = ns[(i + 1) % ns.size]; a += p.x * q.y - q.x * p.y }
    return a
}

private class Work(val width: Float, val loops: MutableList<Loop?>)

/**
 * Gives the drawn EXTREMES [glyphs] (anchor name to outline; Regular is not among them) one shared structure,
 * as described at the top of this file. [hub] names the extreme the others are aligned to (the open axis's,
 * say); by default the one with the most nodes. With [ResolveMode.ADD_MISSING] a contour one extreme lacks is
 * supplied from the extreme that has it, as it stands.
 */
fun resolveExtremes(glyphs: Map<String, GlyphCorner>, mode: ResolveMode, hub: String? = null): ResolveOutcome {
    if (glyphs.size < 2) return ResolveOutcome(null, problem = "Draw at least two extremes first.")
    val done = glyphs.mapValues { completeNodeHandles(it.value) }
    if (done.values.any { g -> g.contours.isEmpty() || g.contours.any { it.points.isEmpty() || !isValidCubicContour(it) } }) {
        return ResolveOutcome(null, problem = "An extreme has an empty or malformed outline.")
    }
    val order = done.keys.sortedByDescending { name ->
        if (name == hub) Int.MAX_VALUE else done.getValue(name).contours.sumOf { c -> c.points.count { it.onCurve } }
    }
    val works = LinkedHashMap<String, Work>()
    val first = order.first()
    works[first] = Work(done.getValue(first).width, done.getValue(first).contours.map { loopOf(it) as Loop? }.toMutableList())
    var copied = 0
    var dropped = 0
    for (name in order.drop(1)) {
        val g = done.getValue(name)
        val xs = g.contours.map { loopOf(it) }
        val hubWork = works.getValue(first)
        val pairing = pairContours(hubWork.loops, xs)
        val mine = MutableList<Loop?>(hubWork.loops.size) { null }
        val usedX = HashSet<Int>()
        for (t in hubWork.loops.indices) {
            val s = pairing[t]
            val h = hubWork.loops[t] ?: continue
            if (s < 0) {
                // This extreme has no contour for slot t.
                if (mode == ResolveMode.ADD_MISSING) { mine[t] = h; copied++ } else { for (w in works.values) w.loops[t] = null; dropped++ }
                continue
            }
            usedX.add(s)
            val plan = planFor(h, xs[s], mode)
            if (plan == null) { for (w in works.values) w.loops[t] = null; dropped++; continue }
            for (w in works.values) w.loops[t] = w.loops[t]?.let { plan.applyTo(it) }
            mine[t] = plan.other
        }
        for (s in xs.indices) if (s !in usedX) {
            if (mode == ResolveMode.ADD_MISSING) {
                // A contour only this extreme has: every other extreme gets it as it is.
                for (w in works.values) w.loops.add(xs[s])
                mine.add(xs[s])
                copied += works.size
            } else dropped++
        }
        works[name] = Work(g.width, mine)
    }
    val out = works.mapValues { (_, w) -> GlyphCorner(w.width, w.loops.filterNotNull().map { it.toContour() }.toMutableList()) }
    // The hub's own contours that no extreme could pair are gone from everyone, so every anchor lists the same ones.
    compatibilityIssue(out, out.keys.toList())?.let { return ResolveOutcome(null, problem = "Couldn't reconcile them: $it") }
    return ResolveOutcome(out, copied, dropped)
}

// ------------------------------------------------------------ Regular, derived

/**
 * Regular as the extremes imply it: every axis that has both its extremes contributes their point-for-point
 * midpoint, and Regular is the average of those (so the one-axis case is simply halfway between them). With no
 * complete axis it is the average of whatever extremes there are -- a placeholder in the right structure, not a
 * claim about where Regular sits. [extremes] must already share one structure (see [resolveExtremes]); the
 * result takes its node types from the first of them. Null when there are none.
 */
fun naturalRegularOf(extremes: Map<String, GlyphCorner>): GlyphCorner? {
    val base = extremes.values.firstOrNull() ?: return null
    val pairs = Axis.ALL.mapNotNull { a -> extremes[a.lo]?.let { lo -> extremes[a.hi]?.let { hi -> lo to hi } } }
    val sources: List<List<GlyphCorner>> = if (pairs.isNotEmpty()) pairs.map { listOf(it.first, it.second) } else listOf(extremes.values.toList())
    val out = base.deepCopy()
    out.width = sources.map { s -> s.map { it.width }.average().toFloat() }.average().toFloat()
    out.contours.forEachIndexed { ci, c ->
        c.points.forEachIndexed { pi, p ->
            p.x = sources.map { s -> s.map { it.contours[ci].points[pi].x }.average().toFloat() }.average().toFloat()
            p.y = sources.map { s -> s.map { it.contours[ci].points[pi].y }.average().toFloat() }.average().toFloat()
        }
    }
    return out
}

/**
 * The anchors as interpolation should see them. An axis only varies once BOTH its extremes are drawn; until
 * then it is inert -- both its extremes are Regular -- so a glyph with only Extra Thin and Extra Black drawn
 * needs nothing copied to the other 26 anchors. Regular is whatever is stored, or, when nothing is, derived from
 * the extremes ([naturalRegularOf]) when those share a structure. Returns [corners] unchanged when there is
 * nothing to fill in (nothing drawn, or extremes that don't agree yet, which [compatibilityIssue] will report).
 */
fun effectiveCorners(corners: Map<String, GlyphCorner>): Map<String, GlyphCorner> {
    val stored = corners["regular"]?.takeIf { it.contours.isNotEmpty() }
    val drawn = corners.filter { it.key != "regular" && it.value.contours.isNotEmpty() }
    val base = stored ?: run {
        if (drawn.isEmpty() || compatibilityIssue(drawn, drawn.keys.toList()) != null) return corners
        naturalRegularOf(drawn) ?: return corners
    }
    val out = LinkedHashMap<String, GlyphCorner>()
    out["regular"] = base
    for (a in Axis.ALL) {
        val complete = a.lo in drawn && a.hi in drawn
        out[a.lo] = if (complete) drawn.getValue(a.lo) else base
        out[a.hi] = if (complete) drawn.getValue(a.hi) else base
    }
    return out
}
