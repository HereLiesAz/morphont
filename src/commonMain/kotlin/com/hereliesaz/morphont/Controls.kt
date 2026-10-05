package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.conveyance.tokens.ConveyShape

/*
 * Shared editor chrome used by both the web and Android shells: the axis
 * picker, View and Ghost menus, the context bar (ghost placement / node
 * reduction), dialogs, and keyboard shortcuts.
 */

/** Compact axis picker -- one button and a menu, instead of fourteen wrapping toggles eating the canvas. */
@Composable
fun AxisPicker(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        MonoButton(onClick = { open = true }) { Text(app.selectedAxis.label, fontSize = 12.sp, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (axis in Axis.ALL) {
                DropdownMenuItem(
                    text = { Text((if (axis == app.selectedAxis) "● " else "   ") + axis.toggleLabel, fontSize = 13.sp) },
                    onClick = { app.selectedAxis = axis; open = false },
                )
            }
        }
    }
}

private fun check(on: Boolean) = if (on) "✓ " else "    "

@Composable
fun ViewMenu(app: AppState) {
    var open by remember { mutableStateOf(false) }
    var metricsOpen by remember { mutableStateOf(false) }
    val v = app.view
    Box {
        MonoButton(onClick = { open = true }) { Text("View", fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) =
                DropdownMenuItem(text = { Text(check(on) + label, fontSize = 13.sp) }, onClick = { set(!on) })
            toggle("Snap", v.snap) { v.snap = it }
            toggle("Rulers", v.showRulers) { v.showRulers = it }
            toggle("Metric lines", v.showMetrics) { v.showMetrics = it }
            toggle("Guides", v.showGuides) { v.showGuides = it }
            toggle("Measurements", v.showMeasure) { v.showMeasure = it }
            toggle("Ghosts", v.showGhosts) { v.showGhosts = it }
            toggle("Grid", v.showGrid) { v.showGrid = it }
            DropdownMenuItem(
                text = { Text("    Grid step: ${v.gridStep.toInt()} (tap to cycle)", fontSize = 13.sp) },
                onClick = {
                    val steps = listOf(10f, 20f, 25f, 50f, 100f)
                    v.gridStep = steps[(steps.indexOf(v.gridStep) + 1).mod(steps.size)]
                    v.showGrid = true
                },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("    Zoom in", fontSize = 13.sp) }, onClick = { v.zoom = (v.zoom * 1.25f).coerceAtMost(40f) })
            DropdownMenuItem(text = { Text("    Zoom out", fontSize = 13.sp) }, onClick = { v.zoom = (v.zoom / 1.25f).coerceAtLeast(0.1f) })
            DropdownMenuItem(text = { Text("    Fit", fontSize = 13.sp) }, onClick = { v.resetView(); open = false })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("    Metrics…", fontSize = 13.sp) }, onClick = { open = false; metricsOpen = true })
            DropdownMenuItem(
                text = { Text("    Clear guides (${app.guides.size})", fontSize = 13.sp) },
                enabled = app.guides.isNotEmpty(),
                onClick = { app.guides.clear(); open = false },
            )
        }
    }
    if (metricsOpen) MetricsDialog(app) { metricsOpen = false }
}

@Composable
private fun MetricsDialog(app: AppState, onDismiss: () -> Unit) {
    val m = app.metrics
    var upm by remember { mutableStateOf(m.unitsPerEm.toInt().toString()) }
    var asc by remember { mutableStateOf(m.ascender.toInt().toString()) }
    var cap by remember { mutableStateOf(m.capHeight.toInt().toString()) }
    var xh by remember { mutableStateOf(m.xHeight.toInt().toString()) }
    var desc by remember { mutableStateOf(m.descender.toInt().toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("Units per em", upm) { upm = it }
                NumberField("Ascender", asc) { asc = it }
                NumberField("Cap height", cap) { cap = it }
                NumberField("x-height", xh) { xh = it }
                NumberField("Descender", desc) { desc = it }
            }
        },
        confirmButton = {
            MonoButton(onClick = {
                app.metrics = FontMetrics(
                    upm.toFloatOrNull()?.coerceAtLeast(16f) ?: m.unitsPerEm,
                    asc.toFloatOrNull() ?: m.ascender,
                    cap.toFloatOrNull() ?: m.capHeight,
                    xh.toFloatOrNull() ?: m.xHeight,
                    desc.toFloatOrNull() ?: m.descender,
                )
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { MonoButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Mono.panel,
        titleContentColor = Mono.ink,
        textContentColor = Mono.ink,
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = Mono.inkDim, modifier = Modifier.widthIn(min = 96.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '-' || it == '.' }) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = Mono.ink),
            colors = monoTextFieldColors(),
            shape = ConveyShape.CutSmall,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

/**
 * Ghost menu: primitive shapes, a live ghost of any glyph in this project,
 * or a character from any TrueType file. [pickFontBytes] is the platform's
 * file picker.
 */
@Composable
fun GhostMenu(app: AppState, pickFontBytes: (onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var glyphPicker by remember { mutableStateOf(false) }
    var fontPicker by remember { mutableStateOf(false) }
    Box {
        MonoButton(onClick = { open = true }, selected = app.activeGhost != null) { Text("Ghosts", fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val glyph = app.anchors.getValue(app.activeAnchor).glyph
            val m = app.metrics
            for (kind in ShapeKind.entries) {
                DropdownMenuItem(text = { Text("+ ${kind.label}", fontSize = 13.sp) }, onClick = {
                    open = false
                    val w = maxOf(glyph.width, m.unitsPerEm * 0.5f)
                    app.addGhost(kind.label, shapeContours(kind, FontRect(w * 0.1f, 0f, w * 0.9f, m.xHeight)))
                })
            }
            DropdownMenuItem(text = { Text("+ Glyph from this project…", fontSize = 13.sp) }, onClick = { open = false; glyphPicker = true })
            DropdownMenuItem(text = { Text("+ Character from a font file…", fontSize = 13.sp) }, onClick = { open = false; fontPicker = true })
            if (app.ghosts.isNotEmpty()) {
                HorizontalDivider()
                for (g in app.ghosts) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                (if (g.id == app.activeGhostId) "● " else "   ") + g.label + (if (g.isLinked) "  (live)" else "") + (if (!g.visible) "  (hidden)" else ""),
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            open = false
                            g.visible = true
                            app.activeGhostId = g.id
                            app.ghostTransformMode = true
                        },
                    )
                }
            }
        }
    }
    if (glyphPicker) ProjectGlyphPicker(app) { glyphPicker = false }
    if (fontPicker) FontCharacterPicker(app, pickFontBytes) { fontPicker = false }
}

@Composable
private fun ProjectGlyphPicker(app: AppState, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ghost a glyph") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Live: it follows the working character on every axis, here and in the Preview.", fontSize = 12.sp, color = Mono.inkDim)
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("filter") },
                    textStyle = TextStyle(color = Mono.ink),
                    colors = monoTextFieldColors(),
                    shape = ConveyShape.CutSmall,
                )
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    val names = app.glyphNames.filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
                    if (names.isEmpty()) Text("No glyphs.", color = Mono.inkDim, fontSize = 12.sp)
                    for (name in names) {
                        Text(
                            name,
                            color = Mono.ink,
                            fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable { app.addLinkedGhost(name); onDismiss() }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { MonoButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Mono.panel,
        titleContentColor = Mono.ink,
        textContentColor = Mono.ink,
    )
}

@Composable
private fun FontCharacterPicker(
    app: AppState,
    pickFontBytes: (onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var chars by remember { mutableStateOf("") }
    var fontName by remember { mutableStateOf(app.referenceFonts.keys.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ghost from a font") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (name in app.referenceFonts.keys) {
                        MonoButton(onClick = { fontName = name }, selected = name == fontName) { Text(name, fontSize = 12.sp, maxLines = 1) }
                    }
                    MonoButton(onClick = {
                        pickFontBytes({ bytes ->
                            try {
                                val font = VariableFont.parse(bytes, requireVariable = false)
                                val name = "Font ${app.referenceFonts.size + 1}"
                                app.referenceFonts[name] = font
                                fontName = name
                                error = null
                            } catch (e: Exception) {
                                error = "Couldn't read that font: ${e.message}"
                            }
                        }, { error = it })
                    }) { Text("Load .ttf…", fontSize = 12.sp) }
                }
                OutlinedTextField(
                    value = chars,
                    onValueChange = { chars = it.take(8) },
                    singleLine = true,
                    placeholder = { Text("character(s)") },
                    textStyle = TextStyle(color = Mono.ink, fontSize = 18.sp),
                    colors = monoTextFieldColors(),
                    shape = ConveyShape.CutSmall,
                )
                Text("Each character becomes its own ghost, scaled to this project's units per em.", fontSize = 11.sp, color = Mono.inkDim)
                error?.let { Text(it, color = Mono.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            MonoButton(onClick = {
                val font = fontName?.let { app.referenceFonts[it] }
                if (font == null) { error = "Load a font first."; return@MonoButton }
                val scale = app.metrics.unitsPerEm / font.unitsPerEm.toFloat().coerceAtLeast(1f)
                val defaults = font.axes.associate { it.tag to it.default }
                var added = 0
                for (ch in chars) {
                    val outline = font.outlineAt(ch.code, defaults) ?: continue
                    app.addGhost("$ch · $fontName", applyAffine(outline, scalingAbout(scale, scale, 0f, 0f)))
                    added++
                }
                if (added == 0) error = "That font has none of those characters (or only unsupported composites)." else onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { MonoButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Mono.panel,
        titleContentColor = Mono.ink,
        textContentColor = Mono.ink,
    )
}

/**
 * The context strip under the top bar: active-ghost controls while a ghost
 * is selected, the node-reduction slider while simplifying, else nothing.
 */
@Composable
fun ContextBar(app: AppState, modifier: Modifier = Modifier) {
    val ghost = app.activeGhost
    val reduction = app.reduction
    when {
        reduction != null -> Column(modifier.fillMaxWidth().background(Mono.panelHeader).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val kept = reduction.totalPoints - reduction.removeCount
            val scope = if (reduction.anchorNames.size > 1) "all anchors" else (ANCHOR_LABELS[reduction.anchorNames.first()] ?: "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Simplify ($scope): $kept / ${reduction.totalPoints} points · worst drift ${reduction.error.toInt()} u",
                    color = Mono.ink,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MonoButton(onClick = { app.applyReduction() }, enabled = reduction.removeCount > 0, selected = true) { Text("Apply", fontSize = 12.sp) }
                MonoButton(onClick = { app.reduction = null }) { Text("Cancel", fontSize = 12.sp) }
            }
            Slider(
                value = reduction.removeCount.toFloat(),
                onValueChange = { reduction.removeCount = it.toInt().coerceIn(0, reduction.order.size) },
                valueRange = 0f..reduction.order.size.toFloat(),
                colors = monoSliderColors(),
                modifier = Modifier.heightIn(max = 32.dp),
            )
        }
        ghost != null -> Row(
            modifier.fillMaxWidth().background(Mono.panelHeader).horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ghost: ${ghost.label}" + if (ghost.isLinked) " (live)" else "",
                color = Mono.tertiary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            MonoButton(onClick = { app.setGhostNodeMode(false) }, selected = app.ghostTransformMode) { Text("Place", fontSize = 12.sp) }
            MonoButton(onClick = { app.setGhostNodeMode(true) }, selected = !app.ghostTransformMode) { Text("Nodes", fontSize = 12.sp) }
            MonoButton(onClick = { app.flipActiveGhost(horizontal = true) }) { Text("Flip ↔", fontSize = 12.sp) }
            MonoButton(onClick = { app.flipActiveGhost(horizontal = false) }) { Text("Flip ↕", fontSize = 12.sp) }
            MonoButton(onClick = { app.rotateActiveGhost(15f) }) { Text("⟲ 15°", fontSize = 12.sp) }
            MonoButton(onClick = { app.rotateActiveGhost(-15f) }) { Text("⟳ 15°", fontSize = 12.sp) }
            MonoButton(onClick = { ghost.visible = !ghost.visible }) { Text(if (ghost.visible) "Hide" else "Show", fontSize = 12.sp) }
            MonoButton(onClick = { app.removeGhost(ghost.id) }) { Text("Remove", fontSize = 12.sp) }
            MonoButton(onClick = { app.activeGhostId = null }) { Text("Done", fontSize = 12.sp) }
        }
    }
}

/** Overflow "Edit" actions that apply to the whole glyph rather than one panel. */
@Composable
fun GlyphMenu(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val label = ANCHOR_LABELS[app.activeAnchor] ?: app.activeAnchor
    Box {
        MonoButton(onClick = { open = true }) { Text("Edit", fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Copy $label outline to all anchors", fontSize = 13.sp) }, onClick = { open = false; app.copyActiveToOthers() })
            DropdownMenuItem(text = { Text("Simplify (reduce nodes)…", fontSize = 13.sp) }, onClick = { open = false; app.startReduction() })
            DropdownMenuItem(text = { Text("Copy  (Ctrl+C)", fontSize = 13.sp) }, onClick = { open = false; app.copySelection() })
            DropdownMenuItem(text = { Text("Cut  (Ctrl+X)", fontSize = 13.sp) }, onClick = { open = false; app.cutSelection() })
            DropdownMenuItem(text = { Text("Paste  (Ctrl+V)", fontSize = 13.sp) }, enabled = app.clipboard != null, onClick = { open = false; app.paste() })
            DropdownMenuItem(text = { Text("Undo  (Ctrl+Z)", fontSize = 13.sp) }, onClick = { open = false; app.undo() })
        }
    }
}

/** A one-line, tap-to-dismiss status strip. */
@Composable
fun StatusStrip(app: AppState, modifier: Modifier = Modifier) {
    if (app.status.isEmpty()) return
    Text(
        (if (app.statusIsError) "! " else "") + app.status,
        color = if (app.statusIsError) Mono.error else Mono.inkDim,
        fontWeight = if (app.statusIsError) FontWeight.Bold else FontWeight.Normal,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().clickable { app.setStatus("") }.padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Editor keyboard shortcuts. Uses [onKeyEvent] (bubbling, not preview) so a
 * focused text field handles its own copy/paste first.
 */
fun Modifier.editorKeys(app: AppState): Modifier = onKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || app.currentGlyphName == null) return@onKeyEvent false
    val mod = e.isCtrlPressed || e.isMetaPressed
    val t = app.commandTarget
    val step = if (e.isShiftPressed) 10f else 1f
    when {
        mod && e.key == Key.C -> app.copySelection()
        mod && e.key == Key.X -> app.cutSelection()
        mod && e.key == Key.V -> app.paste()
        mod && e.key == Key.A -> t.selectAll()
        mod && e.key == Key.Z -> app.undo()
        e.key == Key.Delete || e.key == Key.Backspace -> t.deleteSelected()
        e.key == Key.Escape -> when {
            app.reduction != null -> app.reduction = null
            t.drawingContourIndex != null -> t.finishContour()
            app.activeGhost != null -> app.activeGhostId = null
            else -> t.selection = emptySet()
        }
        e.key == Key.DirectionLeft -> t.nudgeSelected(-step, 0f)
        e.key == Key.DirectionRight -> t.nudgeSelected(step, 0f)
        e.key == Key.DirectionUp -> t.nudgeSelected(0f, step)
        e.key == Key.DirectionDown -> t.nudgeSelected(0f, -step)
        e.key == Key.Equals || e.key == Key.Plus -> app.view.zoom = (app.view.zoom * 1.25f).coerceAtMost(40f)
        e.key == Key.Minus -> app.view.zoom = (app.view.zoom / 1.25f).coerceAtLeast(0.1f)
        e.key == Key.Zero -> app.view.resetView()
        else -> return@onKeyEvent false
    }
    true
}
