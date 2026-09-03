package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Per-node (cornerA, regular, cornerB) triples in font space, for the Regular panel's travel-path overlay. */
data class TravelPathOverlay(val segments: List<Triple<Offset, Offset, Offset>>)

/**
 * Builds the travel-path overlay for the currently picked axis ([AppState.pathAxis]),
 * or null if either of that axis's two anchors isn't loaded or the trio
 * isn't point-compatible with Regular (same silent-skip behavior as the
 * original tool -- the panel's own mismatch text already explains
 * incompatibility).
 */
fun computeTravelPathOverlay(app: AppState): TravelPathOverlay? {
    val axis = app.pathAxis
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
        Button(onClick = { state.startNewContour() }) { Text("New contour", fontSize = 11.sp) }
        Button(onClick = { state.toggleTypeSelected() }) { Text("Toggle on/off", fontSize = 11.sp) }
        Button(onClick = { state.deleteSelected() }) { Text("Delete sel.", fontSize = 11.sp) }
        Button(onClick = { state.undo() }) { Text("Undo", fontSize = 11.sp) }
    }
}

@Composable
fun AxisToggle(value: Axis, onSelect: (Axis) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (axis in Axis.entries) {
            Button(
                onClick = { onSelect(axis) },
                colors = if (axis == value) {
                    ButtonDefaults.buttonColors(containerColor = Color(0xFF2D6CDF))
                } else {
                    ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3A))
                },
            ) { Text(axis.label, fontSize = 11.sp) }
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
    val headerColor = when (anchorName) {
        "regular" -> Color(0xFF4A3D2D)
        else -> if (isActive) Color(0xFF2D6CDF) else Color(0xFF2D2D2D)
    }
    Column(
        modifier
            .background(Color(0xFF262626))
            .border(1.dp, Color(0xFF3A3A3A)),
    ) {
        Box(Modifier.fillMaxWidth().background(headerColor).padding(6.dp)) {
            Text(ANCHOR_LABELS[anchorName] ?: anchorName, color = Color(0xFFDDDDDD), fontSize = 13.sp)
        }
        if (anchorName == "regular") {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AxisToggle(app.pathAxis) { app.pathAxis = it }
            }
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
        }
        AnchorToolbar(state)
    }
}

@Composable
fun PreviewPanel(app: AppState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0xFF262626))
            .border(1.dp, Color(0xFF3A3A3A)),
    ) {
        Box(Modifier.fillMaxWidth().background(Color(0xFF2D4A2D)).padding(6.dp)) {
            Text("Preview (read-only, interpolated)", color = Color(0xFFDDDDDD), fontSize = 13.sp)
        }
        Column(Modifier.padding(8.dp)) {
            Text("Weight: extra thin ${fmt2(app.previewWeight)} extra black", fontSize = 10.sp, color = Color(0xFFAAAAAA))
            Slider(value = app.previewWeight, onValueChange = { app.previewWeight = it }, valueRange = 0f..1f)
            Text("Width: condensed ${fmt2(app.previewWidth)} wide", fontSize = 10.sp, color = Color(0xFFAAAAAA))
            Slider(value = app.previewWidth, onValueChange = { app.previewWidth = it }, valueRange = 0f..1f)
            Button(onClick = { app.previewWeight = 0.5f; app.previewWidth = 0.5f }) {
                Text("Jump to regular (0.5 / 0.5)", fontSize = 11.sp)
            }
        }

        val corners = app.cornersSnapshot()
        val issue = app.compatibility()
        Box(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            if (issue != null) {
                Text(
                    "Anchors aren't interpolation-compatible yet:\n$issue\n\nUse \"Copy active anchor's outline to other 4\" to seed matching topology, then reshape each without adding/removing points.",
                    color = Color(0xFFEE7777),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                val inst = interpolateGlyph(corners, app.previewWeight, app.previewWidth)
                Canvas(Modifier.fillMaxSize().background(Color(0xFF111111))) {
                    if (size.width <= 0f || size.height <= 0f) return@Canvas
                    val vb = computeViewBox(inst)
                    val mapper = SpaceMapper(vb, size)
                    val path = buildOutlinePath(inst.contours) { x, y -> mapper.toCanvas(x, y) }
                    drawPath(path, color = Color(0xFFDDDDDD).copy(alpha = 0.55f))
                    drawPath(path, color = Color(0xFF888888), style = Stroke(width = 1f))
                }
            }
        }
    }
}
