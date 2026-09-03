package com.hereliesaz.transfontmation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType

/** A glyph's visible bounds in font-design space, with padding, used to fit it into a panel. */
data class ViewBox(val minX: Float, val minY: Float, val w: Float, val h: Float)

fun computeViewBox(g: GlyphCorner): ViewBox {
    val xs = mutableListOf(0f, g.width)
    val ys = mutableListOf(0f, 0f)
    for (c in g.contours) for (p in c.points) {
        xs.add(p.x)
        ys.add(p.y)
    }
    val minX = minOf(0f, xs.min()) - 100f
    val maxX = maxOf(g.width, xs.max()) + 100f
    val minY = ys.min() - 100f
    val maxY = ys.max() + 200f
    return ViewBox(minX, minY, maxX - minX, maxY - minY)
}

/** Maps font-design-space coordinates into this panel's actual pixel canvas, flipping Y and fitting/centering [vb]. */
class SpaceMapper(private val vb: ViewBox, private val canvasSize: Size) {
    /** Font-design units per pixel; also usable to convert delta vectors, not just points. */
    val scale = if (vb.w <= 0f || vb.h <= 0f) 1f else minOf(canvasSize.width / vb.w, canvasSize.height / vb.h)
    private val offsetX = (canvasSize.width - vb.w * scale) / 2f
    private val offsetY = (canvasSize.height - vb.h * scale) / 2f

    fun toCanvas(x: Float, y: Float): Offset {
        val localX = x - vb.minX
        val localY = vb.h - (y - vb.minY)
        return Offset(localX * scale + offsetX, localY * scale + offsetY)
    }

    fun toFont(canvas: Offset): Offset {
        val localX = (canvas.x - offsetX) / scale
        val localY = (canvas.y - offsetY) / scale
        return Offset(localX + vb.minX, vb.h - localY + vb.minY)
    }
}

/**
 * Builds the fillable outline path for [contours], using TrueType-style
 * quadratic curves: a lone off-curve point is a normal quadratic control;
 * a run of consecutive off-curve points implies on-curve midpoints
 * between them. Uses even-odd fill so counters/holes render correctly.
 */
fun buildOutlinePath(contours: List<ContourData>, map: (Float, Float) -> Offset): Path {
    val path = Path().apply { fillType = PathFillType.EvenOdd }
    for (c in contours) {
        val pts = c.points
        if (pts.isEmpty()) continue
        val startIdx = pts.indexOfFirst { it.onCurve }
        if (startIdx < 0) continue
        val n = pts.size
        val start = map(pts[startIdx].x, pts[startIdx].y)
        path.moveTo(start.x, start.y)
        var offRun = mutableListOf<Pt>()
        for (step in 1..n) {
            val p = pts[(startIdx + step) % n]
            if (!p.onCurve) {
                offRun.add(p)
                continue
            }
            val end = map(p.x, p.y)
            when {
                offRun.isEmpty() -> path.lineTo(end.x, end.y)
                offRun.size == 1 -> {
                    val c1 = map(offRun[0].x, offRun[0].y)
                    path.quadraticTo(c1.x, c1.y, end.x, end.y)
                }
                else -> {
                    for (k in offRun.indices) {
                        val c1 = map(offRun[k].x, offRun[k].y)
                        if (k < offRun.size - 1) {
                            val nxt = offRun[k + 1]
                            val mid = map((offRun[k].x + nxt.x) / 2f, (offRun[k].y + nxt.y) / 2f)
                            path.quadraticTo(c1.x, c1.y, mid.x, mid.y)
                        } else {
                            path.quadraticTo(c1.x, c1.y, end.x, end.y)
                        }
                    }
                }
            }
            offRun = mutableListOf()
        }
        path.close()
    }
    return path
}
