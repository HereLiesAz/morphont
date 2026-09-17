package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun AnchorToolbar(state: AnchorState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.drawingContourIndex != null) {
            MonoButton(onClick = { state.finishContour() }, selected = true) {
                Text("Finish contour", fontSize = 11.sp)
            }
        } else {
            MonoButton(onClick = { state.startNewContour() }) {
                Text("New contour", fontSize = 11.sp)
            }
        }
        MonoButton(
            onClick = { state.toggleTypeSelected() },
            enabled = state.selection.isNotEmpty(),
        ) { Text("Toggle on/off", fontSize = 11.sp) }
        MonoButton(
            onClick = { state.deleteSelected() },
            enabled = state.selection.isNotEmpty(),
        ) { Text("Delete sel.", fontSize = 11.sp) }
        MonoButton(onClick = { state.undo() }) { Text("Undo", fontSize = 11.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AxisToggle(value: Axis, onSelect: (Axis) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (axis in Axis.ALL) {
            MonoButton(onClick = { onSelect(axis) }, selected = axis == value) {
                Text(axis.label, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun AnchorPanel(
    anchorName: String,
    app: AppState,
    modifier: Modifier = Modifier,
) {
    val state = app.anchors.getValue(anchorName)
    val isActive = app.activeAnchor == anchorName
    val headerActive = isActive && anchorName != "regular"
    val pointCount = state.glyph.contours.sumOf { it.points.size }
    Column(
        modifier
            .background(Mono.panel)
            .border(1.dp, if (isActive) Mono.primary else Mono.border),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (headerActive) Mono.primary else Mono.panelHeader)
                .padding(6.dp),
        ) {
            Text(
                ANCHOR_LABELS[anchorName] ?: anchorName,
                color = if (headerActive) Mono.onPrimary else Mono.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        if (anchorName == "regular") {
            AxisToggle(app.selectedAxis, onSelect = { app.selectedAxis = it }, modifier = Modifier.fillMaxWidth().padding(4.dp))
        }
        Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            AnchorCanvas(
                anchorName = anchorName,
                state = state,
                isActive = isActive,
                onActivate = { app.activeAnchor = anchorName },
                travelPathOverlay = if (anchorName == "regular") computeTravelPathOverlay(app) else null,
                modifier = Modifier.fillMaxSize(),
            )
            val hint = when {
                app.currentGlyphName == null -> "Create or open a glyph to begin."
                state.drawingContourIndex != null && pointCount == 0 -> "Click the canvas to place the first point. Finish contour when you're done."
                state.drawingContourIndex != null -> "Drawing contour: click to add points, then Finish contour."
                pointCount == 0 && anchorName == "regular" -> "Start here: New contour → click points → Finish contour."
                pointCount == 0 -> "Draw Regular first, then copy its outline here and reshape it."
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    color = Mono.inkDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        AnchorToolbar(state)
    }
}

@Composable
fun PreviewPanel(app: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Mono.panel)
            .border(1.dp, Mono.tertiary),
    ) {
        Box(Modifier.fillMaxWidth().background(Mono.tertiary).padding(6.dp)) {
            Text("Preview (read-only, interpolated)", color = Mono.onTertiary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            for (axis in Axis.ALL) {
                val t = app.previewValues[axis.tag] ?: 0.5f
                Text("${axis.label}: ${axis.loLabel} ${fmt2(t)} ${axis.hiLabel}", fontSize = 10.sp, color = Mono.inkDim)
                Slider(
                    value = t,
                    onValueChange = { app.previewValues[axis.tag] = it },
                    valueRange = 0f..1f,
                    colors = monoSliderColors(),
                )
            }
            MonoButton(onClick = { Axis.ALL.forEach { app.previewValues[it.tag] = 0.5f } }) {
                Text("Jump to regular", fontSize = 11.sp)
            }
        }

        val corners = app.cornersSnapshot()
        val totalPoints = corners.values.sumOf { corner -> corner.contours.sumOf { it.points.size } }
        val issue = app.compatibility()
        Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            when {
                app.currentGlyphName == null -> {
                    Text(
                        "Create or open a glyph. Start in Regular, draw one contour, then copy that outline to the other anchors.",
                        color = Mono.inkDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                totalPoints == 0 -> {
                    Text(
                        "Start in Regular: New contour → click points → Finish contour. Then copy Regular to all anchors before reshaping the extremes.",
                        color = Mono.inkDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                issue != null -> {
                    Text(
                        "Preview unlocks when every anchor has matching point topology. Copy the active outline to the other anchors, then reshape without adding/removing points.\n\nDetails: $issue",
                        color = Mono.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                else -> {
                    val inst = interpolateGlyph(corners, app.previewValues)
                    Canvas(Modifier.fillMaxSize().background(Mono.ground)) {
                        if (size.width <= 0f || size.height <= 0f) return@Canvas
                        val vb = computeViewBox(inst)
                        val mapper = SpaceMapper(vb, size)
                        val path = buildOutlinePath(inst.contours) { x, y -> mapper.toCanvas(x, y) }
                        drawPath(path, color = Mono.ink.copy(alpha = 0.55f))
                        drawPath(path, color = Mono.inkDim, style = Stroke(width = 1f))
                    }
                }
            }
        }
    }
}
