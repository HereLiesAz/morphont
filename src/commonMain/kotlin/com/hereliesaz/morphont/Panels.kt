package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign

/** Per-node (cornerA, regular, cornerB) triples in font space, for the Regular panel's travel-path overlay. */
data class TravelPathOverlay(val segments: List<Triple<Offset, Offset, Offset>>)

/**
 * Builds the travel-path overlay for the currently picked axis
 * ([AppState.selectedAxis]), or null if either of that axis's two anchors
 * isn't loaded or the trio isn't point-compatible with Regular (same
 * silent-skip behavior as the original tool -- the panel's own mismatch
 * text already explains incompatibility).
 */
fun computeTravelPathOverlay(app: AppState): TravelPathOverlay? {
    val axis = app.selectedAxis
    val regular = app.anchors.getValue("regular").glyph
    val a = app.anchors[axis.lo]?.glyph ?: return null
    val b = app.anchors[axis.hi]?.glyph ?: return null
    val issue = compatibilityIssue(
        mapOf(axis.lo to a, axis.hi to b, "regular" to regular),
        listOf(axis.lo, axis.hi, "regular"),
    )
    if (issue != null) return null

    val segments = mutableListOf<Triple<Offset, Offset, Offset>>()
    regular.contours.forEachIndexed { ci, c ->
        c.points.forEachIndexed { pi, p ->
            val pa = a.contours[ci].points[pi]
            val pb = b.contours[ci].points[pi]
            segments.add(Triple(Offset(pa.x, pa.y), Offset(p.x, p.y), Offset(pb.x, pb.y)))
        }
    }
    return TravelPathOverlay(segments)
}

/** The interpolated glyph at the Preview sliders, with linked ghosts interpolated alongside it -- or a quiet note when it can't be shown yet. */
@Composable
fun PreviewCanvas(app: AppState, modifier: Modifier = Modifier, showGhosts: Boolean = true, compact: Boolean = false) {
    val corners = app.cornersSnapshot()
    val total = corners.values.sumOf { c -> c.contours.sumOf { it.points.size } }
    val issue = if (total == 0) null else app.compatibility()
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            total == 0 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caps("Nothing drawn yet", size = 10)
                if (!compact) Text("Draw an extreme, then start the other from it.", fontSize = Type.Micro, color = Mono.inkDim, textAlign = TextAlign.Center)
            }
            issue != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
                // The thumbnail is too small for the explanation: it names the problem, and a tap opens the full preview.
                if (compact) { Caps("Anchors", color = Mono.error, size = 10); Caps("differ", color = Mono.error, size = 10) }
                else Caps("Anchors differ", color = Mono.error, size = 10)
                if (!compact) {
                    Text("Their nodes don't line up, so there's nothing to blend. Use \"Match the extremes' nodes\".",
                        fontSize = Type.Micro, color = Mono.inkDim, textAlign = TextAlign.Center)
                    app.nodeCountSummary().takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = Type.Micro, color = Mono.ink, textAlign = TextAlign.Center) }
                }
            }
            else -> {
                val inst = interpolateGlyph(corners, app.previewValues)
                val ghosts = if (showGhosts && app.view.showGhosts) app.ghosts.filter { it.visible }.map { it to app.ghostPreviewContours(it, inst.width) } else emptyList()
                Canvas(Modifier.fillMaxSize()) {
                    if (size.width <= 0f || size.height <= 0f) return@Canvas
                    val mapper = SpaceMapper(editorViewBox(app.metrics, inst.width, 1.15f, 0f, 0f), size)
                    val map: (Float, Float) -> Offset = { x, y -> mapper.toCanvas(x, y) }
                    drawPath(buildOutlinePath(inst.contours, map), color = Mono.ink)
                    for ((layer, g) in ghosts) {
                        drawPath(
                            buildOutlinePath(g, map),
                            color = if (layer.color == 0) Mono.secondary else GhostStyle.color(layer.color),
                            style = if (layer.solid) Stroke(width = 1.4f) else Stroke(width = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))),
                        )
                    }
                }
            }
        }
    }
}

/** A small static rendering of one anchor, for the anchor filmstrip. */
@Composable
fun AnchorThumb(app: AppState, anchorName: String, modifier: Modifier = Modifier, bright: Boolean = false) {
    val g = app.anchors.getValue(anchorName).glyph
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val mapper = SpaceMapper(editorViewBox(app.metrics, g.width, 1.15f, 0f, 0f), size)
        drawPath(buildOutlinePath(g.contours) { x, y -> mapper.toCanvas(x, y) }, color = if (bright) Mono.ink else Mono.inkDim)
    }
}
