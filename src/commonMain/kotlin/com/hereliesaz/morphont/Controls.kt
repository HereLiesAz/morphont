package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/*
 * The editor's building blocks, shared by the phone and desktop layouts in
 * Editor.kt: the selection pill, ghost bar, simplify card, axis strip and
 * filmstrip, the inspector sections (Preview, Ghosts, Guides), dialogs, and
 * keyboard shortcuts. Everything floats on the canvas; nothing is a boxed
 * panel.
 */

/** What the platform shell supplies to the shared editor. */
class EditorHost(
    val pickFontBytes: (onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) -> Unit,
    val openGlyph: (String) -> Unit,
    /** Creates and opens a glyph; false (with a status message) if the name is unusable. */
    val createGlyph: (String) -> Boolean,
    val listGlyphNames: () -> List<String>,
    /** Import/export and similar, shown in the glyph browser's File list. */
    val fileActions: List<Pair<String, () -> Unit>>,
    val onPointHit: () -> Unit = {},
    /** Handle/hit-target scale for touch-first surfaces. */
    val touchScale: Float = 1.3f,
)

// ------------------------------------------------------------ small parts

@Composable
fun MonoMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Mono.panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Mono.border),
    ) { content() }
}

@Composable
fun MonoMenuItem(text: String, onClick: () -> Unit, checked: Boolean? = null, enabled: Boolean = true) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 14.sp, color = if (enabled) Mono.ink else Mono.inkFaint) },
        trailingIcon = if (checked == true) ({ Icon(MIcons.Check, null, tint = Mono.ink, modifier = Modifier.size(16.dp)) }) else null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** A toggle chip: filled ink when on, hairline when off. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Mono.ink else Color.Transparent)
            .border(1.dp, if (on) Mono.ink else Mono.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = if (on) Mono.onPrimary else Mono.inkDim)
    }
}

/** Two-way segmented switch. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Mono.border, RoundedCornerShape(14.dp))) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier
                    .height(28.dp)
                    .background(if (i == selected) Mono.ink else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 12.sp, color = if (i == selected) Mono.onPrimary else Mono.inkDim) }
        }
    }
}

@Composable
fun SectionHead(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Caps(title, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ------------------------------------------------------- floating overlays

/** Actions on the current selection, floating over the canvas only while something is selected (or pasteable). */
@Composable
fun SelectionPill(app: AppState, modifier: Modifier = Modifier) {
    val t = app.commandTarget
    if (app.reduction != null || (app.activeGhost != null && app.ghostTransformMode)) return
    val has = t.selection.isNotEmpty()
    if (!has && app.clipboard == null) return
    Row(modifier.floating().height(44.dp).padding(horizontal = 4.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (has) {
            IconAction(MIcons.Copy, "Copy", { app.copySelection() }, size = 40)
            IconAction(MIcons.Cut, "Cut", { app.cutSelection() }, size = 40)
        }
        if (app.clipboard != null) IconAction(MIcons.Paste, "Paste", { app.paste() }, size = 40)
        if (has) {
            IconAction(MIcons.Curve, "Toggle on-curve", { t.toggleTypeSelected() }, size = 40)
            IconAction(MIcons.SelectAll, "Select all", { t.selectAll() }, size = 40)
            if (t.selection.size >= 2) {
                IconAction(MIcons.FlipH, "Flip horizontally", { t.flipSelected(true) }, size = 40)
                IconAction(MIcons.FlipV, "Flip vertically", { t.flipSelected(false) }, size = 40)
                IconAction(MIcons.RotateLeft, "Rotate 15° left", { t.rotateSelected(15f) }, size = 40)
                IconAction(MIcons.RotateRight, "Rotate 15° right", { t.rotateSelected(-15f) }, size = 40)
            }
            if (app.touchInput && t.selection.size >= 2) {
                // What the touch pad does: move, scale or turn the selection.
                Box(Modifier.padding(horizontal = 4.dp)) {
                    Segmented(listOf("Move", "Scale", "Turn"), app.padMode.ordinal) { app.padMode = PadMode.entries[it] }
                }
            }
            Box(Modifier.width(1.dp).height(20.dp).background(Mono.border))
            IconAction(MIcons.Delete, "Delete", { t.deleteSelected() }, size = 40)
            val b = boundsOfSelection(t.glyph, t.selection)
            val dims = if (b != null && t.selection.size > 1) " · ${b.width.toInt()} × ${b.height.toInt()}" else ""
            Caps("${t.selection.size} sel$dims", size = 10, modifier = Modifier.padding(start = 4.dp, end = 10.dp))
        }
    }
}

/** Placement controls for the ghost being edited. */
@Composable
fun GhostBar(app: AppState, modifier: Modifier = Modifier) {
    val g = app.activeGhost ?: return
    Row(
        modifier.floating().height(48.dp).padding(start = 14.dp, end = 4.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(g.label, fontSize = 14.sp, color = Mono.ink, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Segmented(listOf("Place", "Nodes"), if (app.ghostTransformMode) 0 else 1) { app.setGhostNodeMode(it == 1) }
        IconAction(MIcons.FlipH, "Flip horizontally", { app.flipActiveGhost(true) }, size = 40)
        IconAction(MIcons.FlipV, "Flip vertically", { app.flipActiveGhost(false) }, size = 40)
        IconAction(MIcons.RotateLeft, "Rotate 15° left", { app.rotateActiveGhost(15f) }, size = 40)
        IconAction(MIcons.RotateRight, "Rotate 15° right", { app.rotateActiveGhost(-15f) }, size = 40)
        IconAction(MIcons.Check, "Done", { app.activeGhostId = null }, size = 40, selected = true)
    }
}

/** The node-reduction slider, floating while a simplify preview is open. */
@Composable
fun SimplifyCard(app: AppState, modifier: Modifier = Modifier) {
    val r = app.reduction ?: return
    Column(modifier.floating(20).widthIn(max = 460.dp).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caps("Simplify" + if (r.anchorNames.size > 1) " · all anchors" else "", color = Mono.ink)
                Caps("${r.totalPoints} → ${r.totalPoints - r.removeCount} nodes · drift ${r.error.toInt()}u", size = 10)
            }
            IconAction(MIcons.Close, "Cancel", { app.reduction = null }, size = 40)
            IconAction(MIcons.Check, "Apply", { app.applyReduction() }, size = 40, selected = true, enabled = r.removeCount > 0)
        }
        HairSlider(
            value = r.removeCount.toFloat(),
            onValueChange = { r.removeCount = kotlin.math.round(it).toInt().coerceIn(0, r.order.size) },
            valueRange = 0f..r.order.size.toFloat(),
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
        )
    }
}

/** The suggestion button: a spark with a count; tap to expand or collapse the row. */
@Composable
fun SuggestionsButton(app: AppState, suggestions: List<Suggestion>, size: Int = 44) {
    Box {
        IconAction(MIcons.Spark, "Suggested edits (${suggestions.size})", { app.showSuggestions = !app.showSuggestions }, selected = app.showSuggestions, size = size, enabled = suggestions.isNotEmpty() || app.showSuggestions)
        if (suggestions.isNotEmpty() && !app.showSuggestions) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(Mono.ink),
                contentAlignment = Alignment.Center,
            ) { Text(suggestions.size.coerceAtMost(9).toString(), fontSize = 10.sp, color = Mono.onPrimary) }
        }
    }
}

/** Expanded suggested edits: one chip per applicable edit, each a single undoable tap. */
@Composable
fun SuggestionsRow(app: AppState, suggestions: List<Suggestion>, modifier: Modifier = Modifier) {
    if (!app.showSuggestions) return
    Row(
        modifier.floating(20).height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (suggestions.isEmpty()) Text("Nothing to suggest right now.", fontSize = 13.sp, color = Mono.inkDim, modifier = Modifier.padding(horizontal = 10.dp))
        for (sg in suggestions) {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Mono.border, RoundedCornerShape(18.dp))
                    .clickable { sg.apply(app) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(sg.label, fontSize = 13.sp, color = Mono.ink, maxLines = 1)
                sg.detail?.let { Caps(it, size = 9) }
            }
        }
        IconAction(MIcons.Close, "Hide suggestions", { app.showSuggestions = false }, size = 36)
    }
}

/** Transient status line; tap to dismiss. */
@Composable
fun Toast(app: AppState, modifier: Modifier = Modifier) {
    if (app.status.isEmpty()) return
    androidx.compose.runtime.LaunchedEffect(app.status) {
        kotlinx.coroutines.delay(if (app.statusIsError) 7000 else 3500)
        app.setStatus("")
    }
    Text(
        app.status,
        color = if (app.statusIsError) Mono.error else Mono.ink,
        fontSize = 13.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.floating(16).widthIn(max = 520.dp).clickable { app.setStatus("") }.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// ------------------------------------------------------------- axis pieces

@Composable
fun AxisLabel(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Caps(app.selectedAxis.label, color = Mono.ink)
            Icon(MIcons.Chevron, "Change axis", tint = Mono.inkDim, modifier = Modifier.size(14.dp))
        }
        MonoMenu(open, { open = false }) {
            for (axis in Axis.ALL) {
                MonoMenuItem(axis.toggleLabel, { app.selectedAxis = axis; open = false }, checked = axis == app.selectedAxis)
            }
        }
    }
}

/**
 * The axis's three anchors as live thumbnails -- picker and at-a-glance
 * preview in one. Desktop floats it under the canvas; phones run it full
 * width under the header ([compact]).
 */
@Composable
fun AnchorFilmstrip(app: AppState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val axis = app.selectedAxis
    val stops = listOf(axis.lo to axis.loLabel, "regular" to "Regular", axis.hi to axis.hiLabel)
    @Composable
    fun Thumb(name: String, label: String, m: Modifier) {
        val active = app.activeAnchor == name
        Column(
            m.clip(RoundedCornerShape(12.dp)).clickable { app.activeAnchor = name },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnchorThumb(
                app, name, bright = active,
                modifier = (if (compact) Modifier.fillMaxWidth().height(52.dp) else Modifier.size(64.dp, 56.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Mono.panelHeader else Color.Transparent)
                    .border(if (active) 1.5.dp else 1.dp, if (active) Mono.ink else Mono.border, RoundedCornerShape(10.dp))
                    .padding(4.dp),
            )
            Text(label, fontSize = 11.sp, color = if (active) Mono.ink else Mono.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (compact) {
        Column(modifier) {
            AxisLabel(app)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((name, label) in stops) Thumb(name, label, Modifier.weight(1f))
            }
        }
    } else {
        Row(
            modifier.floating(18).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterVertically)) { AxisLabel(app) }
            for ((name, label) in stops) Thumb(name, label, Modifier)
        }
    }
}

// --------------------------------------------------------- inspector parts

@Composable
fun AxisSliders(app: AppState, initiallyShown: Int = 4) {
    var all by remember { mutableStateOf(false) }
    val axes = if (all) Axis.ALL else Axis.ALL.take(initiallyShown)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (axis in axes) {
            val t = app.previewValues[axis.tag] ?: 0.5f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(axis.label, fontSize = 13.sp, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp))
                HairSlider(
                    value = t,
                    onValueChange = { app.previewValues[axis.tag] = it },
                    modifier = Modifier.weight(1f).height(32.dp),
                )
                Caps(fmt2(t), size = 10, modifier = Modifier.width(40.dp).padding(start = 8.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (Axis.ALL.size > initiallyShown) {
                MonoButton(onClick = { all = !all }) { Text(if (all) "Fewer axes" else "${Axis.ALL.size - initiallyShown} more axes", fontSize = 12.sp, color = Mono.inkDim) }
            }
            MonoButton(onClick = { Axis.ALL.forEach { app.previewValues[it.tag] = 0.5f } }) { Text("Reset to Regular", fontSize = 12.sp, color = Mono.inkDim) }
        }
    }
}

@Composable
fun PreviewSection(app: AppState, previewHeight: Int = 170) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHead("Preview")
        PreviewCanvas(
            app,
            Modifier.fillMaxWidth().height(previewHeight.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0D0D0D))
                .border(1.dp, Mono.border, RoundedCornerShape(14.dp)).padding(10.dp),
        )
        AxisSliders(app)
    }
}

@Composable
fun GhostsSection(app: AppState, host: EditorHost) {
    var addOpen by remember { mutableStateOf(false) }
    var glyphPicker by remember { mutableStateOf(false) }
    var fontPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHead("Ghosts") {
            Box {
                IconAction(MIcons.Plus, "Add ghost", { addOpen = true }, size = 36)
                MonoMenu(addOpen, { addOpen = false }) {
                    val glyph = app.anchors.getValue(app.activeAnchor).glyph
                    val m = app.metrics
                    MonoMenuItem("Glyph from this project…", { addOpen = false; glyphPicker = true })
                    MonoMenuItem("Character from a font file…", { addOpen = false; fontPicker = true })
                    for (kind in ShapeKind.entries) MonoMenuItem(kind.label, {
                        addOpen = false
                        val w = maxOf(glyph.width, m.unitsPerEm * 0.5f)
                        app.addGhost(kind.label, shapeContours(kind, FontRect(w * 0.1f, 0f, w * 0.9f, m.xHeight)))
                    })
                }
            }
        }
        if (app.ghosts.isEmpty()) Text("Lay a shape, another glyph, or a character from any font over this one to compare.", fontSize = 13.sp, color = Mono.inkDim)
        for (g in app.ghosts) {
            val active = g.id == app.activeGhostId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) Mono.panelHeader else Color.Transparent)
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(g.label, fontSize = 14.sp, color = if (g.visible) Mono.ink else Mono.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (g.isLinked) Text("follows every axis", fontSize = 11.sp, color = Mono.inkDim)
                }
                Segmented(listOf("Over", "Beside"), if (g.beside) 1 else 0) { g.beside = it == 1 }
                IconAction(MIcons.Pen, if (active) "Done editing ghost" else "Edit ghost", { if (active) app.activeGhostId = null else app.editGhost(g.id) }, selected = active, size = 36)
                IconAction(if (g.visible) MIcons.Eye else MIcons.EyeOff, if (g.visible) "Hide" else "Show", { g.visible = !g.visible }, size = 36)
                IconAction(MIcons.Delete, "Remove ghost", { app.removeGhost(g.id) }, size = 36)
            }
        }
    }
    if (glyphPicker) ProjectGlyphPicker(app, host) { glyphPicker = false }
    if (fontPicker) FontCharacterPicker(app, host) { fontPicker = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidesSection(app: AppState) {
    val v = app.view
    var metricsOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHead("Guides")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Snap", v.snap) { v.snap = !v.snap }
            Chip("Metrics", v.showMetrics) { v.showMetrics = !v.showMetrics }
            Chip("Rulers", v.showRulers) { v.showRulers = !v.showRulers }
            Chip("Guides", v.showGuides) { v.showGuides = !v.showGuides }
            Chip("Measure", v.showMeasure) { v.showMeasure = !v.showMeasure }
            Chip("Ghosts", v.showGhosts) { v.showGhosts = !v.showGhosts }
            Chip(if (v.showGrid) "Grid ${v.gridStep.toInt()}" else "Grid", v.showGrid) {
                // Off → 50 → 25 → 10 → 100 → off.
                val cycle = listOf(50f, 25f, 10f, 100f)
                if (!v.showGrid) { v.showGrid = true; v.gridStep = cycle[0] }
                else {
                    val i = cycle.indexOf(v.gridStep)
                    if (i < 0 || i == cycle.lastIndex) v.showGrid = false else v.gridStep = cycle[i + 1]
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MonoButton(onClick = { metricsOpen = true }, outlined = true) { Text("Metrics…", fontSize = 12.sp) }
            MonoButton(onClick = { app.guides.clear() }, enabled = app.guides.isNotEmpty(), outlined = true) { Text("Clear ${app.guides.size} guides", fontSize = 12.sp) }
        }
        Text("Drag from a ruler to place a guide; drop it back on the ruler to remove it.", fontSize = 12.sp, color = Mono.inkDim)
    }
    if (metricsOpen) MetricsDialog(app) { metricsOpen = false }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun MonoDialog(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.floating(24).widthIn(min = 300.dp, max = 520.dp).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 22.sp, color = Mono.ink, modifier = Modifier.weight(1f))
                IconAction(MIcons.Close, "Close", onDismiss, size = 40)
            }
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) { actions() }
        }
    }
}

@Composable
fun MonoField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, big: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = Mono.inkFaint) },
        textStyle = TextStyle(fontSize = if (big) 22.sp else 15.sp, color = Mono.ink),
        colors = monoTextFieldColors(),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    )
}

@Composable
private fun MetricsDialog(app: AppState, onDismiss: () -> Unit) {
    val m = app.metrics
    val fields = remember {
        listOf(
            "Units per em" to mutableStateOf(m.unitsPerEm.toInt().toString()),
            "Ascender" to mutableStateOf(m.ascender.toInt().toString()),
            "Cap height" to mutableStateOf(m.capHeight.toInt().toString()),
            "x-height" to mutableStateOf(m.xHeight.toInt().toString()),
            "Descender" to mutableStateOf(m.descender.toInt().toString()),
        )
    }
    MonoDialog("Metrics", onDismiss, actions = {
        MonoButton(onClick = {
            val v = fields.map { it.second.value.toFloatOrNull() }
            app.metrics = FontMetrics(
                v[0]?.coerceAtLeast(16f) ?: m.unitsPerEm, v[1] ?: m.ascender, v[2] ?: m.capHeight, v[3] ?: m.xHeight, v[4] ?: m.descender,
            )
            onDismiss()
        }, selected = true) { Text("Apply") }
    }) {
        for ((label, state) in fields) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, color = Mono.inkDim, modifier = Modifier.width(120.dp))
                MonoField(state.value, { s -> state.value = s.filter { it.isDigit() || it == '-' || it == '.' } }, "", Modifier.weight(1f))
            }
        }
    }
}

/** Every glyph in the project as a grid of large letters; also where new glyphs and file actions live. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlyphBrowser(app: AppState, host: EditorHost, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val names = remember { host.listGlyphNames() }
    MonoDialog("Glyphs", onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoField(name, { name = it }, "New glyph name", Modifier.weight(1f))
            MonoButton(onClick = { if (host.createGlyph(name)) onDismiss() }, selected = true, enabled = name.isNotBlank()) { Text("Create") }
        }
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in names) {
                    val current = n == app.currentGlyphName
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (current) Mono.ink else Mono.panelHeader)
                            .clickable { host.openGlyph(n); onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Text(n, fontSize = if (n.length <= 2) 26.sp else 12.sp, color = if (current) Mono.onPrimary else Mono.ink, maxLines = 1) }
                }
            }
        }
        if (host.fileActions.isNotEmpty()) {
            Caps("File")
            Column {
                for ((label, action) in host.fileActions) {
                    Text(
                        label,
                        fontSize = 15.sp,
                        color = Mono.ink,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onDismiss(); action() }.padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectGlyphPicker(app: AppState, host: EditorHost, onDismiss: () -> Unit) {
    val names = remember { host.listGlyphNames() }
    MonoDialog("Ghost a glyph", onDismiss) {
        Text("Live: it takes the working character's weight, width and every other axis -- here and in the Preview.", fontSize = 13.sp, color = Mono.inkDim)
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in names) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Mono.panelHeader)
                            .clickable { app.addLinkedGhost(n); onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Text(n, fontSize = if (n.length <= 2) 26.sp else 12.sp, color = Mono.ink, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun FontCharacterPicker(app: AppState, host: EditorHost, onDismiss: () -> Unit) {
    var chars by remember { mutableStateOf("") }
    var fontName by remember { mutableStateOf(app.referenceFonts.keys.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    MonoDialog("Ghost from a font", onDismiss, actions = {
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
            if (added == 0) error = "That font has none of those characters." else onDismiss()
        }, selected = true) { Text("Add") }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in app.referenceFonts.keys) Chip(name, name == fontName) { fontName = name }
            Chip("Load .ttf…", false) {
                host.pickFontBytes({ bytes ->
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
            }
        }
        MonoField(chars, { chars = it.take(8) }, "Characters", Modifier.fillMaxWidth(), big = true)
        Text("Each character becomes its own ghost, scaled to this project's units per em.", fontSize = 12.sp, color = Mono.inkDim)
        error?.let { Text(it, color = Mono.error, fontSize = 13.sp) }
    }
}

// --------------------------------------------------------------- keyboard

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
        e.key == Key.P -> if (t.drawingContourIndex == null) t.startNewContour()
        e.key == Key.V -> if (t.drawingContourIndex != null) t.finishContour()
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
