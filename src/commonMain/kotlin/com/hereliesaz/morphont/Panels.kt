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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/**
 * The per-panel action strip: contour drawing, point actions and the
 * clipboard. One horizontally scrolling row, so on a phone it never wraps
 * into a second line that eats the canvas.
 */
@Composable
fun AnchorToolbar(app: AppState, anchorName: String, modifier: Modifier = Modifier) {
    val state = app.editTarget(anchorName)
    val hasSel = state.selection.isNotEmpty()
    val placingGhost = app.activeGhost != null && app.ghostTransformMode
    Row(
        modifier.fillMaxWidth().background(Mono.panelHeader).horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val act: (() -> Unit) -> () -> Unit = { f -> { app.activeAnchor = anchorName; f() } }
        if (state.drawingContourIndex != null) {
            MonoButton(onClick = act { state.finishContour() }, selected = true) { Text("Finish contour", fontSize = 12.sp) }
        } else {
            MonoButton(onClick = act { state.startNewContour() }, enabled = !placingGhost) { Text("Draw", fontSize = 12.sp) }
        }
        MonoButton(onClick = act { state.toggleTypeSelected() }, enabled = hasSel) { Text("On/off", fontSize = 12.sp) }
        MonoButton(onClick = act { state.deleteSelected() }, enabled = hasSel) { Text("Delete", fontSize = 12.sp) }
        MonoButton(onClick = act { app.copySelection() }, enabled = !placingGhost) { Text("Copy", fontSize = 12.sp) }
        MonoButton(onClick = act { app.cutSelection() }, enabled = hasSel) { Text("Cut", fontSize = 12.sp) }
        MonoButton(onClick = act { app.paste() }, enabled = app.clipboard != null && !placingGhost) { Text("Paste", fontSize = 12.sp) }
        MonoButton(onClick = act { state.selectAll() }, enabled = !placingGhost) { Text("All", fontSize = 12.sp) }
        MonoButton(onClick = act { app.undo() }) { Text("Undo", fontSize = 12.sp) }
    }
}

@Composable
fun AnchorPanel(
    anchorName: String,
    app: AppState,
    modifier: Modifier = Modifier,
    interactionScale: Float = 1f,
    onPointHit: () -> Unit = {},
) {
    val anchor = app.anchors.getValue(anchorName)
    val target = app.editTarget(anchorName)
    val isActive = app.activeAnchor == anchorName
    val pointCount = anchor.glyph.contours.sumOf { it.points.size }
    Column(
        modifier
            .background(Mono.panel)
            .border(1.dp, if (isActive) Mono.primary else Mono.border),
    ) {
        Row(
            Modifier.fillMaxWidth().background(if (isActive) Mono.primary else Mono.panelHeader).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val ink = if (isActive) Mono.onPrimary else Mono.ink
            Text(ANCHOR_LABELS[anchorName] ?: anchorName, color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            val info = buildString {
                append("$pointCount pts")
                if (target.selection.isNotEmpty()) append(" · ${target.selection.size} sel")
            }
            Text(info, color = ink.copy(alpha = 0.75f), fontSize = 11.sp)
        }
        Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            AnchorCanvas(
                anchorName = anchorName,
                app = app,
                travelPathOverlay = if (anchorName == "regular") computeTravelPathOverlay(app) else null,
                interactionScale = interactionScale,
                onPointHit = onPointHit,
                modifier = Modifier.fillMaxSize(),
            )
            val hint = when {
                app.currentGlyphName == null -> "Create or open a glyph to begin."
                target.drawingContourIndex != null && target.glyph.contours.getOrNull(target.drawingContourIndex!!)?.points.isNullOrEmpty() ->
                    "Tap to place the first point. Finish contour when done."
                target.drawingContourIndex != null -> "Drawing: tap to add points, then Finish contour."
                app.activeGhost != null -> null
                pointCount == 0 && anchorName == "regular" -> "Start here: Draw → tap points → Finish contour."
                pointCount == 0 -> "Draw Regular first, then copy its outline here and reshape it."
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    color = Mono.inkDim,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 26.dp, bottom = 8.dp, end = 8.dp),
                )
            }
        }
        AnchorToolbar(app, anchorName)
    }
}

@Composable
fun PreviewPanel(app: AppState, modifier: Modifier = Modifier) {
    var slidersOpen by remember { mutableStateOf(true) }
    Column(
        modifier
            .background(Mono.panel)
            .border(1.dp, Mono.tertiary),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Mono.tertiary).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Preview", color = Mono.onTertiary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Regular",
                    color = Mono.onTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { Axis.ALL.forEach { app.previewValues[it.tag] = 0.5f } },
                )
                Text(
                    if (slidersOpen) "Hide axes" else "Axes",
                    color = Mono.onTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { slidersOpen = !slidersOpen },
                )
            }
        }
        if (slidersOpen) {
            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                for (axis in Axis.ALL) {
                    val t = app.previewValues[axis.tag] ?: 0.5f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            axis.label,
                            fontSize = 10.sp,
                            color = Mono.inkDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.32f),
                        )
                        Slider(
                            value = t,
                            onValueChange = { app.previewValues[axis.tag] = it },
                            valueRange = 0f..1f,
                            colors = monoSliderColors(),
                            modifier = Modifier.weight(0.68f).heightIn(max = 32.dp),
                        )
                    }
                }
            }
        }

        val corners = app.cornersSnapshot()
        val totalPoints = corners.values.sumOf { corner -> corner.contours.sumOf { it.points.size } }
        val issue = app.compatibility()
        Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            when {
                app.currentGlyphName == null -> PanelNote("Create or open a glyph. Start in Regular, draw one contour, then copy that outline to the other anchors.")
                totalPoints == 0 -> PanelNote("Start in Regular: Draw → tap points → Finish contour. Then copy Regular to all anchors before reshaping the extremes.")
                issue != null -> Text(
                    "Preview unlocks when every anchor has matching point topology. Copy the active outline to the other anchors, then reshape without adding/removing points.\n\nDetails: $issue",
                    color = Mono.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp),
                )
                else -> {
                    val inst = interpolateGlyph(corners, app.previewValues)
                    // Linked ghosts are interpolated at these same slider values (see AppState.ghostPreviewContours).
                    val ghosts = if (app.view.showGhosts) app.ghosts.filter { it.visible }.map { app.ghostPreviewContours(it) } else emptyList()
                    Canvas(Modifier.fillMaxSize().background(Mono.ground)) {
                        if (size.width <= 0f || size.height <= 0f) return@Canvas
                        val vb = editorViewBox(app.metrics, inst.width, 1f, 0f, 0f)
                        val mapper = SpaceMapper(vb, size)
                        val map: (Float, Float) -> Offset = { x, y -> mapper.toCanvas(x, y) }
                        for (g in ghosts) {
                            val gp = buildOutlinePath(g, map)
                            drawPath(gp, color = Mono.ink.copy(alpha = 0.07f))
                            drawPath(gp, color = Mono.inkFaint, style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
                        }
                        val path = buildOutlinePath(inst.contours, map)
                        drawPath(path, color = Mono.ink.copy(alpha = 0.55f))
                        drawPath(path, color = Mono.inkDim, style = Stroke(width = 1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelNote(text: String) {
    Text(text, color = Mono.inkDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
}
