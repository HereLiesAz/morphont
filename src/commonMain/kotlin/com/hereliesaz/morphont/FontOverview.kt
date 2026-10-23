package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Read-only, variable-axis instance preview. Never modifies source anchor geometry. */
@Composable
private fun FontGlyphPreview(glyph: Glyph?, values: Map<String, Float>, modifier: Modifier) {
    if (glyph == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Caps("Unavailable", size = 10) }
        return
    }
    val source = glyph.corners
    val instance = if (compatibilityIssue(source) == null)
        interpolateGlyph(source, values)
    else source["regular"] ?: GlyphCorner()
    val metrics = glyph.metrics ?: FontMetrics()
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val mapper = SpaceMapper(editorViewBox(metrics, instance.width, 1.15f, 0f, 0f), size)
        drawPath(buildOutlinePath(instance.contours) { x, y -> mapper.toCanvas(x, y) }, Mono.ink)
    }
}

/** Shared controls for all preview axes. Changes apply to one glyph or the entire gallery. */
@Composable
private fun FontAxisControls(values: Map<String, Float>, onChange: (String, Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val axes = if (expanded) Axis.ALL else Axis.ALL.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (axis in axes) {
            val value = values[axis.tag] ?: 0.5f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(axis.label, fontSize = Type.Small, color = Mono.ink, modifier = Modifier.width(86.dp))
                HairSlider(
                    value = value,
                    onValueChange = { onChange(axis.tag, it) },
                    modifier = Modifier.weight(1f),
                )
                Caps(fmt2(value), size = 10, modifier = Modifier.width(38.dp))
            }
        }
        MonoButton(onClick = { expanded = !expanded }, outlined = true) {
            Text(if (expanded) "Fewer axes" else "All ${Axis.ALL.size} axes", fontSize = Type.Small)
        }
    }
}

/**
 * The whole font at a chosen variable instance, across all platforms. The
 * lazily composed grid keeps large imported fonts usable without creating
 * hundreds of canvases at once. A tile opens its own enlargement dialog;
 * its slider changes only that glyph until Apply to all is pressed.
 */
@Composable
fun FontOverview(app: AppState, host: EditorHost, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val names = app.glyphNames
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Mono.ground).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Font · ${names.size} glyphs", fontSize = Type.Title, color = Mono.ink, modifier = Modifier.weight(1f))
                MonoButton(onClick = {
                    for (axis in Axis.ALL) app.setFontPreviewAxis(axis.tag, 0.5f)
                }, outlined = true) { Text("Regular", fontSize = Type.Small) }
                IconAction(MIcons.Close, "Close font overview", onDismiss, size = IconSize.LARGE)
            }
            Text("Adjust every glyph's preview together. Open a tile for individual axes. Source outlines are unchanged.",
                fontSize = Type.Small, color = Mono.inkDim)
            Box(Modifier.fillMaxWidth().heightIn(max = 215.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
                    FontAxisControls(app.fontPreviewValues.toMap(), app::setFontPreviewAxis)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(106.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(names, key = { it }) { name ->
                    val glyph = if (name == app.currentGlyphName) app.toGlyph()
                        else remember(name) { app.glyphLookup(name) }
                    val values = app.fontValuesFor(name)
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(Mono.panelHeader)
                            .border(1.dp, Mono.border, RoundedCornerShape(12.dp))
                            .clickable { selected = name }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FontGlyphPreview(glyph, values, Modifier.fillMaxWidth().height(88.dp))
                        Text(displayGlyphName(name), color = Mono.ink, fontSize = Type.Body, maxLines = 1)
                    }
                }
            }
        }
    }

    val current = selected
    if (current != null) {
        val glyph = if (current == app.currentGlyphName) app.toGlyph()
            else remember(current) { app.glyphLookup(current) }
        MonoDialog("Glyph: ${displayGlyphName(current)}", onDismiss = { selected = null }, actions = {
            MonoButton(onClick = {
                app.applyGlyphPreviewToAll(current)
                selected = null
            }, selected = true) { Text("Apply instance to all", fontSize = Type.Small) }
            MonoButton(onClick = {
                selected = null
                onDismiss()
                host.openGlyph(current)
            }) { Text("Edit glyph", fontSize = Type.Small) }
        }) {
            FontGlyphPreview(glyph, app.fontValuesFor(current),
                Modifier.fillMaxWidth().height(216.dp).background(Mono.panelHeader, RoundedCornerShape(12.dp)))
            Text("Adjust this glyph's preview. Apply to all to set the font-wide instance.",
                fontSize = Type.Small, color = Mono.inkDim)
            FontAxisControls(app.fontValuesFor(current)) { tag, value ->
                app.setGlyphPreviewAxis(current, tag, value)
            }
            MonoButton(onClick = { app.glyphPreviewValues.remove(current) }, outlined = true) {
                Text("Use font-wide values", fontSize = Type.Small)
            }
        }
    }
}
