package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    /** Start, open, rename and delete projects; null on a surface without them. */
    val projects: ProjectActions? = null,
    /** Handle/hit-target scale for touch-first surfaces. */
    val touchScale: Float = 1.3f,
    /** Where theme and view settings are kept between sessions; null keeps them for this session only. */
    val settings: SettingsStore? = null,
    /** The azphalt font store and this device's installed fonts; null on a surface without one. */
    val fontStore: FontStoreHost? = null,
    /** Looks for a newer Morphont on the channel this copy came from; null where the app updates itself. */
    val updates: UpdateChecker? = null,
    /** Like [createGlyph] but starting from a drawn glyph (the sample); null where the shell can't. */
    val createGlyphFrom: ((String, Glyph) -> Boolean)? = null,
    /** The open project's glyph files, for renaming and deleting glyphs; null where the shell can't. */
    val glyphFiles: GlyphFiles? = null,
    /** File actions (by label) that ask first, with the question: those that overwrite without undo. */
    val confirmBefore: Map<String, String> = emptyMap(),
    /** Exports the project as a compiled font (TrueType, OpenType, variable, WOFF); null where the shell can't save files. */
    val fontExport: FontExportHost? = null,
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

/**
 * One menu entry. [detail] is a second, dimmer line saying what it does -- or, when the item is disabled, why
 * (so a greyed-out item never looks merely broken).
 */
@Composable
fun MonoMenuItem(text: String, onClick: () -> Unit, checked: Boolean? = null, enabled: Boolean = true, detail: String? = null) {
    DropdownMenuItem(
        text = {
            Column {
                Text(text, fontSize = Type.Body, color = if (enabled) Mono.ink else Mono.inkFaint)
                if (detail != null) Text(detail, fontSize = Type.Small, color = Mono.inkDim, modifier = Modifier.widthIn(max = 280.dp))
            }
        },
        trailingIcon = if (checked == true) ({ Icon(MIcons.Check, null, tint = Mono.ink, modifier = Modifier.size(16.dp)) }) else null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** A menu's section label, set like every other section head ([Caps]), not as a disabled item. */
@Composable
fun MonoMenuHeader(title: String) {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) { Caps(title) }
}

/**
 * The top-bar menu: jump straight to the glyph list, projects, the font overview or (on a phone, where they live
 * in sheets) the Preview, Ghosts and Guides panels, and flip every view setting without opening anything.
 * [goTo] is null where those panels are always on screen.
 */
@Composable
fun EditorMenuButton(
    app: AppState,
    host: EditorHost,
    openBrowser: () -> Unit,
    openFontOverview: () -> Unit,
    goTo: ((String) -> Unit)?,
    size: Int = IconSize.LARGE,
) {
    var open by remember { mutableStateOf(false) }
    var projectsOpen by remember { mutableStateOf(false) }
    var storeOpen by remember { mutableStateOf(false) }
    var ghostFont by remember { mutableStateOf<String?>(null) }
    var updatesOpen by remember { mutableStateOf(false) }
    var howOpen by remember { mutableStateOf(false) }
    var keysOpen by remember { mutableStateOf(false) }
    val v = app.view
    @Composable fun section(title: String) = MonoMenuHeader(title)
    Box {
        IconAction(MIcons.More, "Menu", { open = true }, size = size)
        MonoMenu(open, { open = false }) {
            section("Go to")
            MonoMenuItem("Glyphs and files…", { open = false; openBrowser() })
            if (host.projects != null) MonoMenuItem("Projects…", { open = false; projectsOpen = true })
            MonoMenuItem("Font overview…", { open = false; openFontOverview() })
            if (host.fontStore != null) MonoMenuItem("Font store…", { open = false; storeOpen = true })
            if (goTo != null) {
                MonoMenuItem("Preview", { open = false; goTo("preview") })
                MonoMenuItem("Ghosts", { open = false; goTo("ghosts") })
                MonoMenuItem("Guides and metrics", { open = false; goTo("guides") })
            }
            section("Theme")
            MonoMenuItem("Dark", { ThemeState.dark = true }, checked = ThemeState.dark)
            MonoMenuItem("Light", { ThemeState.dark = false }, checked = !ThemeState.dark)
            section("View and settings")
            MonoMenuItem("Snap", { v.snap = !v.snap }, checked = v.snap)
            MonoMenuItem("Metrics", { v.showMetrics = !v.showMetrics }, checked = v.showMetrics)
            MonoMenuItem("Rulers", { v.showRulers = !v.showRulers }, checked = v.showRulers)
            MonoMenuItem("Guides", { v.showGuides = !v.showGuides }, checked = v.showGuides)
            MonoMenuItem("Measurements", { v.showMeasure = !v.showMeasure }, checked = v.showMeasure)
            MonoMenuItem("Ghosts", { v.showGhosts = !v.showGhosts }, checked = v.showGhosts)
            MonoMenuItem("Travel paths", { v.showTravel = !v.showTravel }, checked = v.showTravel)
            MonoMenuItem("Move circle", { app.setNodeMoveCircleEnabled(!app.nodeMoveCircleEnabled) }, checked = app.nodeMoveCircleEnabled)
            MonoMenuItem("Grid", { v.showGrid = !v.showGrid }, checked = v.showGrid)
            section("Grid step")
            for (step in GRID_STEPS) MonoMenuItem("${step.toInt()} units", { v.gridStep = step }, checked = v.gridStep == step)
            section("Help")
            MonoMenuItem("How Morphont works…", { open = false; howOpen = true })
            MonoMenuItem("Keys and gestures…", { open = false; keysOpen = true })
            if (host.updates != null) MonoMenuItem("Check for updates…", { open = false; updatesOpen = true })
        }
    }
    host.projects?.let { actions ->
        if (projectsOpen) ProjectsDialog(app, actions, onDismiss = { projectsOpen = false }, onSwitched = { projectsOpen = false })
    }
    host.fontStore?.let { store ->
        if (storeOpen) FontStoreDialog(app, store, onUseAsGhost = { storeOpen = false; ghostFont = it }) { storeOpen = false }
    }
    ghostFont?.let { name -> FontCharacterPicker(app, host, initialFont = name) { ghostFont = null } }
    host.updates?.let { checker -> if (updatesOpen) UpdateCheckDialog(checker) { updatesOpen = false } }
    if (howOpen) HowItWorksDialog({ howOpen = false })
    if (keysOpen) ShortcutsDialog { keysOpen = false }
}

/**
 * Resolving the nodes of the drawn extremes: add the nodes one extreme lacks (placed where they belong on its
 * outline), or take out the nodes the other doesn't have. It works between the extremes, never through Regular,
 * which is rebuilt from them. The last item puts Regular back halfway between the open axis's extremes.
 */
@Composable
fun StatesMenuButton(app: AppState, size: Int = IconSize.LARGE) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(MIcons.States, "Match the extremes' nodes", { open = true }, size = size, enabled = app.activeGhost == null,
            disabledReason = "finish editing the ghost first")
        MonoMenu(open, { open = false }) {
            val summary = app.nodeCountSummary()
            MonoMenuItem(summary.ifEmpty { "Nothing drawn yet" }, {}, enabled = false,
                detail = if (summary.isEmpty()) "Draw an extreme, then the other one." else "Nodes per anchor. The extremes must agree before the Preview can blend them.")
            val differ = app.statesDiffer()
            val same = "They already agree."
            MonoMenuItem("Add missing nodes", { open = false; app.addMissingNodes() }, enabled = differ,
                detail = if (differ) "Each extreme gains the nodes the other lacks, at the matching place on its outline. Nothing is lost." else same)
            MonoMenuItem("Match nodes to the fewest", { open = false; app.matchToFewestNodes() }, enabled = differ,
                detail = if (differ) "Takes out the nodes with no counterpart in the other extreme." else same)
            val canReset = app.regularCanReset()
            MonoMenuItem("Reset Regular to the middle", { open = false; app.resetRegularToNaturalPath() }, enabled = canReset,
                detail = when {
                    canReset -> "Puts Regular halfway between the two extremes. Undo puts it back."
                    differ -> "Match the nodes first."
                    else -> "Regular is already halfway."
                })
        }
    }
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
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = Type.Body, color = if (on) Mono.onPrimary else Mono.inkDim)
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
                    .selectable(selected = i == selected, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = Type.Small, color = if (i == selected) Mono.onPrimary else Mono.inkDim) }
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

/**
 * The selection's actions -- copy, cut, paste, delete, smooth/corner, line/curve, select all, flip and
 * rotate -- as ordinary tools in the tool dock or rail. They are always there, dimmed until they apply,
 * so the layout never shifts and there is no separate bar to find.
 */
@Composable
fun NodeActions(app: AppState, size: Int = IconSize.LARGE) {
    val t = app.commandTarget
    // Nodes can't be edited while a Simplify preview is open or a ghost is being placed, or mid-contour.
    val free = !app.nodeEditsLocked && t.drawingContourIndex == null
    val has = free && t.selection.isNotEmpty()
    val several = free && t.selection.size >= 2
    val whyNot = when {
        app.nodeEditsLocked -> "unavailable while Simplify or ghost Place is open"
        t.drawingContourIndex != null -> "finish the contour first"
        else -> null
    }
    val needsNode = whyNot ?: "select a node first"
    val needsTwo = whyNot ?: "select two or more nodes"
    IconAction(MIcons.Copy, "Copy", { app.copySelection() }, size = size, enabled = has, disabledReason = needsNode)
    IconAction(MIcons.Cut, "Cut", { app.cutSelection() }, size = size, enabled = has, disabledReason = needsNode)
    IconAction(MIcons.Paste, "Paste", { app.paste() }, size = size, enabled = free && app.clipboard != null, disabledReason = whyNot ?: "copy or cut something first")
    IconAction(MIcons.Delete, "Delete", { app.trashSelection() }, size = size, enabled = has, disabledReason = needsNode)
    val smooth = has && t.selectionIsSmooth
    IconAction(if (smooth) MIcons.Corner else MIcons.Smooth, if (smooth) "Make corner" else "Make smooth", { t.toggleSmoothSelected() }, size = size, enabled = has, disabledReason = needsNode)
    IconAction(MIcons.CurveLine, "Toggle line / curve", { t.toggleCurveSelected() }, size = size, enabled = has, disabledReason = needsNode)
    IconAction(MIcons.SelectAll, "Select all", { t.selectAll() }, size = size, enabled = free, disabledReason = whyNot)
    IconAction(MIcons.FlipH, "Flip horizontally", { t.flipSelected(true) }, size = size, enabled = several, disabledReason = needsTwo)
    IconAction(MIcons.FlipV, "Flip vertically", { t.flipSelected(false) }, size = size, enabled = several, disabledReason = needsTwo)
    IconAction(MIcons.RotateLeft, "Rotate 15° left", { t.rotateSelected(15f) }, size = size, enabled = several, disabledReason = needsTwo)
    IconAction(MIcons.RotateRight, "Rotate 15° right", { t.rotateSelected(-15f) }, size = size, enabled = several, disabledReason = needsTwo)
}

/** Placement controls for the ghost being edited. */
@Composable
fun GhostBar(app: AppState, modifier: Modifier = Modifier) {
    val g = app.activeGhost ?: return
    var axesOpen by remember(g.id) { mutableStateOf(false) }
    val ghostScroll = rememberScrollState()
    Box(modifier.widthIn(max = 520.dp).floating()) {
        Row(
            Modifier.height(60.dp).horizontalScroll(ghostScroll)
                .padding(start = 14.dp, end = 4.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        Text(g.label, fontSize = Type.Body, color = Mono.ink, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Segmented(listOf("Place", "Nodes"), if (app.ghostTransformMode) 0 else 1) { app.setGhostNodeMode(it == 1) }
        IconAction(MIcons.FlipH, "Flip horizontally", { app.flipActiveGhost(true) }, size = IconSize.LARGE)
        IconAction(MIcons.FlipV, "Flip vertically", { app.flipActiveGhost(false) }, size = IconSize.LARGE)
        IconAction(MIcons.RotateLeft, "Rotate 15° left", { app.rotateActiveGhost(15f) }, size = IconSize.LARGE)
        IconAction(MIcons.RotateRight, "Rotate 15° right", { app.rotateActiveGhost(-15f) }, size = IconSize.LARGE)
        if (g.isLinked) IconAction(MIcons.Axes, "Ghost weight, width and axes", { axesOpen = true }, size = IconSize.LARGE)
        IconAction(MIcons.Check, "Done", { app.activeGhostId = null }, size = IconSize.LARGE, selected = true)
        }
        Scrollbar(ghostScroll, false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp))
    }
    if (axesOpen && g.isLinked) GhostAxesDialog(app, g) { axesOpen = false }
}

/** The node-reduction slider, floating while a simplify preview is open. */
@Composable
fun SimplifyCard(app: AppState, modifier: Modifier = Modifier) {
    val r = app.reduction ?: return
    Column(modifier.floating(20).widthIn(max = 460.dp).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caps("Simplify" + if (r.anchorNames.size > 1) " · all anchors" else "", color = Mono.ink)
                Caps("${r.totalPoints} → ${r.remainingNodes} nodes · min ${r.minimumNodes} · drift ${fmt2(r.error)}u", size = 10)
            }
            IconAction(MIcons.Close, "Cancel", { app.reduction = null }, size = IconSize.LARGE)
            IconAction(MIcons.Check, "Apply", { app.applyReduction() }, size = IconSize.LARGE, selected = true, enabled = r.removeCount > 0)
        }
        if (r.order.isNotEmpty()) {
            HairSlider(
                value = r.removeCount.toFloat(),
                onValueChange = { r.removeCount = kotlin.math.round(it).toInt().coerceIn(0, r.order.size) },
                valueRange = 0f..r.order.size.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(onClick = { r.removeCount = (r.removeCount - 1).coerceAtLeast(0) }, enabled = r.removeCount > 0) {
                Text("−1")
            }
            MonoButton(onClick = { r.removeCount = (r.removeCount + 1).coerceAtMost(r.order.size) }, enabled = r.removeCount < r.order.size) {
                Text("+1")
            }
        }
    }
}

/** The suggestion button: a spark with a count; tap to expand or collapse the row. */
@Composable
fun SuggestionsButton(app: AppState, suggestions: List<Suggestion>, size: Int = IconSize.LARGE) {
    Box {
        IconAction(MIcons.Spark, "Suggested edits (${suggestions.size})", { app.showSuggestions = !app.showSuggestions }, selected = app.showSuggestions, size = size, enabled = suggestions.isNotEmpty() || app.showSuggestions)
        if (suggestions.isNotEmpty() && !app.showSuggestions) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(Mono.ink),
                contentAlignment = Alignment.Center,
            ) { Text(suggestions.size.coerceAtMost(9).toString(), fontSize = Type.Micro, color = Mono.onPrimary) }
        }
    }
}

/** Expanded suggested edits: one chip per applicable edit, each a single undoable tap. */
@Composable
fun SuggestionsRow(app: AppState, suggestions: List<Suggestion>, modifier: Modifier = Modifier) {
    if (!app.showSuggestions) return
    Row(
        modifier.floating(20).height(56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (suggestions.isEmpty()) Text("Nothing to suggest: no duplicate or redundant points, and the outline's direction is right.", fontSize = Type.Body, color = Mono.inkDim, modifier = Modifier.padding(horizontal = 10.dp))
        for (sg in suggestions) {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Mono.border, RoundedCornerShape(18.dp))
                    .clickable { sg.apply(app) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(sg.label, fontSize = Type.Body, color = Mono.ink, maxLines = 1)
                sg.detail?.let { Caps(it, size = 10) }
            }
        }
        IconAction(MIcons.Close, "Hide suggestions", { app.showSuggestions = false }, size = IconSize.LARGE)
    }
}

/** Transient status line; tap to dismiss. */
@Composable
fun Toast(app: AppState, modifier: Modifier = Modifier) {
    if (app.status.isEmpty()) return
    androidx.compose.runtime.LaunchedEffect(app.status) {
        // Long messages stay up long enough to read: a base, plus time per character, doubled for errors.
        kotlinx.coroutines.delay((3500L + app.status.length * 45L) * (if (app.statusIsError) 2 else 1))
        app.setStatus("")
    }
    Text(
        app.status,
        color = if (app.statusIsError) Mono.error else Mono.ink,
        fontSize = Type.Body,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.floating(16).widthIn(max = 520.dp).clickable(role = Role.Button, onClickLabel = "Dismiss") { app.setStatus("") }
            .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 16.dp, vertical = 10.dp),
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
            m.clip(RoundedCornerShape(12.dp))
                .selectable(selected = active, role = Role.Tab, onClick = { app.activeAnchor = name })
                .semantics(mergeDescendants = true) { contentDescription = "$label anchor" },
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
            Text(label, fontSize = Type.Micro, color = if (active) Mono.ink else Mono.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text(axis.label, fontSize = Type.Body, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp))
                HairSlider(
                    value = t,
                    onValueChange = { app.previewValues[axis.tag] = it },
                    modifier = Modifier.weight(1f),
                )
                Caps(fmt2(t), size = 10, modifier = Modifier.width(40.dp).padding(start = 8.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (Axis.ALL.size > initiallyShown) {
                MonoButton(onClick = { all = !all }) { Text(if (all) "Fewer axes" else "${Axis.ALL.size - initiallyShown} more axes") }
            }
            MonoButton(onClick = { Axis.ALL.forEach { app.previewValues[it.tag] = 0.5f } }) { Text("Reset to Regular") }
        }
    }
}

@Composable
fun PreviewSection(app: AppState, previewHeight: Int = 170) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHead("Preview")
        PreviewCanvas(
            app,
            Modifier.fillMaxWidth().height(previewHeight.dp).clip(RoundedCornerShape(14.dp)).background(Mono.previewGround)
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
    var pickerFont by remember { mutableStateOf<String?>(null) }
    var storeOpen by remember { mutableStateOf(false) }
    var ghostAxisId by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHead("Ghosts") {
            Box {
                IconAction(MIcons.Plus, "Add ghost", { addOpen = true }, size = IconSize.LARGE)
                MonoMenu(addOpen, { addOpen = false }) {
                    val glyph = app.anchors.getValue(app.activeAnchor).glyph
                    val m = app.metrics
                    MonoMenuItem("Glyph from this project…", { addOpen = false; glyphPicker = true })
                    MonoMenuItem("Character from a font file…", { addOpen = false; fontPicker = true })
                    if (host.fontStore != null) MonoMenuItem("Character from the font store…", { addOpen = false; storeOpen = true })
                    for (kind in ShapeKind.entries) MonoMenuItem(kind.label, {
                        addOpen = false
                        val w = maxOf(glyph.width, m.unitsPerEm * 0.5f)
                        app.addGhost(kind.label, shapeContours(kind, FontRect(w * 0.1f, 0f, w * 0.9f, m.xHeight)))
                    })
                }
            }
        }
        if (app.ghosts.isEmpty()) Text("Lay a shape, another glyph, or a character from any font over this one to compare.", fontSize = Type.Body, color = Mono.inkDim)
        if (app.ghosts.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Glyph opacity", fontSize = Type.Body, color = Mono.ink, maxLines = 1, modifier = Modifier.width(110.dp))
            HairSlider(
                value = app.view.glyphOpacity,
                onValueChange = { app.view.glyphOpacity = it },
                modifier = Modifier.weight(1f),
                valueRange = 0.1f..1f,
            )
            Caps("${(app.view.glyphOpacity * 100).toInt()}%", size = 10, modifier = Modifier.width(44.dp).padding(start = 8.dp))
        }
        for (g in app.ghosts) {
            val active = g.id == app.activeGhostId
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (active) Mono.panelHeader else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(g.label, fontSize = Type.Body, color = if (g.visible) Mono.ink else Mono.inkDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (g.isLinked) Text("Variable source · independent axes", fontSize = Type.Micro, color = Mono.inkDim)
                val rowScroll = rememberScrollState()
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rowScroll).padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Segmented(listOf("Over", "Beside"), if (g.beside) 1 else 0) { g.beside = it == 1 }
                        Segmented(listOf("Dotted", "Solid"), if (g.solid) 1 else 0) { g.solid = it == 1 }
                        for ((i, entry) in GhostStyle.colors.withIndex()) {
                            Box(
                                Modifier.size(24.dp).clip(CircleShape)
                                    .background(entry.second)
                                    .border(if (g.color == i) 2.dp else 0.dp, Mono.ink, CircleShape)
                                    .clickable(onClickLabel = "Outline colour: ${entry.first}") { g.color = i }
                                    .semantics { contentDescription = "Outline colour: ${entry.first}" },
                            )
                        }
                        if (g.isLinked) IconAction(
                            MIcons.Axes, "Ghost weight, width and axes",
                            { ghostAxisId = g.id }, size = IconSize.LARGE,
                        )
                        IconAction(MIcons.Pen, if (active) "Done editing ghost" else "Edit ghost",
                            { if (active) app.activeGhostId = null else app.editGhost(g.id) },
                            selected = active, size = IconSize.LARGE)
                        IconAction(if (g.visible) MIcons.Eye else MIcons.EyeOff,
                            if (g.visible) "Hide" else "Show", { g.visible = !g.visible }, size = IconSize.LARGE)
                        IconAction(MIcons.Delete, "Remove ghost", { app.removeGhost(g.id) }, size = IconSize.LARGE)
                    }
                    Scrollbar(rowScroll, false,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp))
                }
            }
        }
    }
    if (glyphPicker) ProjectGlyphPicker(app, host) { glyphPicker = false }
    if (fontPicker) FontCharacterPicker(app, host, initialFont = pickerFont) { fontPicker = false; pickerFont = null }
    host.fontStore?.let { store ->
        if (storeOpen) FontStoreDialog(app, store, onUseAsGhost = { storeOpen = false; pickerFont = it; fontPicker = true }) { storeOpen = false }
    }
    val ghost = app.ghosts.firstOrNull { it.id == ghostAxisId }
    if (ghost != null) GhostAxesDialog(app, ghost) { ghostAxisId = null }
}

/** One axis picker shared by the Ghosts panel and the active ghost's bar. */
@Composable
private fun GhostAxesDialog(app: AppState, ghost: GhostLayer, onDismiss: () -> Unit) {
    MonoDialog("Ghost axes: ${ghost.label}", onDismiss, actions = {
        MonoButton(onClick = { ghost.axisOverrides.keys.toList().forEach { tag ->
            Axis.ALL.firstOrNull { it.tag == tag }?.let { app.setGhostAxis(ghost.id, it, null) }
        } }) { Text("Follow glyph axes", fontSize = Type.Small) }
    }) {
        Text("These values affect this ghost only. Untouched axes follow the working glyph's selected anchor and preview.",
            fontSize = Type.Small, color = Mono.inkDim)
        for (axis in Axis.ALL) {
            if (ghost.referenceCorners.isNotEmpty() && axis.tag !in ghost.supportedAxes) continue
            val value = ghost.axisOverrides[axis.tag] ?: 0.5f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(axis.label, color = Mono.ink, fontSize = Type.Body, modifier = Modifier.width(94.dp))
                HairSlider(
                    value = value, onValueChange = { app.setGhostAxis(ghost.id, axis, it) },
                    modifier = Modifier.weight(1f),
                )
                IconAction(MIcons.Close, "Follow ${axis.label}", {
                    app.setGhostAxis(ghost.id, axis, null)
                }, size = IconSize.LARGE, enabled = ghost.axisOverrides.containsKey(axis.tag))
            }
        }
    }
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
            Chip("Measurements", v.showMeasure) { v.showMeasure = !v.showMeasure }
            Chip("Ghosts", v.showGhosts) { v.showGhosts = !v.showGhosts }
            Chip("Travel paths", v.showTravel) { v.showTravel = !v.showTravel }
            Chip("Move circle", app.nodeMoveCircleEnabled) {
                app.setNodeMoveCircleEnabled(!app.nodeMoveCircleEnabled)
            }
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
            MonoButton(onClick = { metricsOpen = true }, outlined = true) { Text("Metrics…") }
            MonoButton(onClick = { app.clearGuides() }, enabled = app.guides.isNotEmpty(), outlined = true) { Text("Clear ${app.guides.size} guides") }
        }
        Text("Drag from a ruler to place a guide; drop it back on the ruler to remove it.", fontSize = Type.Small, color = Mono.inkDim)
    }
    if (metricsOpen) MetricsDialog(app) { metricsOpen = false }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun MonoDialog(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.floating(24).widthIn(min = 280.dp, max = 520.dp)
                .heightIn(max = 560.dp).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = Type.Title, color = Mono.ink, modifier = Modifier.weight(1f))
                IconAction(MIcons.Close, "Close", onDismiss, size = IconSize.LARGE)
            }
            val dialogScroll = rememberScrollState()
            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().verticalScroll(dialogScroll).padding(end = 9.dp)) {
                    content()
                }
                Scrollbar(dialogScroll, true, Modifier.align(Alignment.CenterEnd).width(7.dp).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) { actions() }
        }
    }
}

@Composable
fun MonoField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    isError: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        isError = isError,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        placeholder = { Text(placeholder, color = Mono.inkDim) },
        textStyle = TextStyle(fontSize = if (big) Type.Title else Type.Row, color = Mono.ink),
        colors = monoTextFieldColors(),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    )
}

/** Why a metrics entry can't be used, or null: every field a number, units per em at least 16, and ascender above descender. */
fun metricsProblem(unitsPerEm: String, ascender: String, capHeight: String, xHeight: String, descender: String): String? {
    val names = listOf("Units per em", "Ascender", "Cap height", "x-height", "Descender")
    val values = listOf(unitsPerEm, ascender, capHeight, xHeight, descender).map { it.trim().toFloatOrNull()?.takeIf { v -> v.isFinite() } }
    values.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { return "${names[it]} needs a number." }
    if (values[0]!! < 16f) return "Units per em must be at least 16 (fonts use 1000 or 2048)."
    if (values[1]!! <= values[4]!!) return "The ascender has to sit above the descender."
    return null
}

@Composable
private fun MetricsDialog(app: AppState, onDismiss: () -> Unit) {
    val m = app.metrics
    val fields = remember(m) {
        listOf(
            Triple("Units per em", mutableStateOf(m.unitsPerEm.toInt().toString()), "The grid the font is drawn on: 1000 or 2048"),
            Triple("Ascender", mutableStateOf(m.ascender.toInt().toString()), "Top of the tallest letters, above the baseline"),
            Triple("Cap height", mutableStateOf(m.capHeight.toInt().toString()), "Height of capital letters"),
            Triple("x-height", mutableStateOf(m.xHeight.toInt().toString()), "Height of lowercase letters like x"),
            Triple("Descender", mutableStateOf(m.descender.toInt().toString()), "Bottom of letters like p and g (negative)"),
        )
    }
    val v = fields.map { it.second.value }
    val problem = metricsProblem(v[0], v[1], v[2], v[3], v[4])
    MonoDialog("Metrics", onDismiss, actions = {
        MonoButton(onClick = onDismiss, outlined = true) { Text("Cancel") }
        MonoButton(onClick = {
            val n = v.map { it.trim().toFloat() }
            app.updateMetrics(FontMetrics(n[0], n[1], n[2], n[3], n[4]))
            onDismiss()
        }, selected = true, enabled = problem == null) { Text("Apply") }
    }) {
        Text("The horizontal guides the canvas draws, in font units.", fontSize = Type.Small, color = Mono.inkDim)
        for ((label, state, help) in fields) {
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = Type.Body, color = Mono.ink, modifier = Modifier.width(120.dp))
                    MonoField(state.value, { s -> state.value = s.filter { it.isDigit() || it == '-' || it == '.' } }, "", Modifier.weight(1f),
                        isError = state.value.trim().toFloatOrNull() == null,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                }
                Text(help, fontSize = Type.Micro, color = Mono.inkDim)
            }
        }
        problem?.let { Text(it, fontSize = Type.Body, color = Mono.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Every glyph in the project as a grid of large letters; also where new glyphs and file actions live. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlyphBrowser(app: AppState, host: EditorHost, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val names = app.glyphNames
    confirming?.let { label ->
        ConfirmDialog(label, host.confirmBefore.getValue(label), confirmLabel = "Replace", onDismiss = { confirming = null }) {
            confirming = null
            onDismiss()
            host.fileActions.firstOrNull { it.first == label }?.second?.invoke()
        }
    }
    MonoDialog("Glyphs and files", onDismiss) {
        host.projects?.let { projectActions ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Caps("Project")
                    Text(app.currentProject?.name ?: "", fontSize = Type.Row, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MonoButton(onClick = { showProjects = true }, outlined = true) { Text("Projects") }
            }
            if (showProjects) ProjectsDialog(app, projectActions, onDismiss = { showProjects = false }, onSwitched = { showProjects = false; onDismiss() })
        }
        NewGlyphRow(name, { name = it }) { if (host.createGlyph(name)) onDismiss() }
        val current = app.currentGlyphName
        if (host.glyphFiles != null && current != null) ThisGlyphRow(app, host, host.glyphFiles, current)
        Box(Modifier.heightIn(max = 340.dp)) {
            val gridScroll = rememberScrollState()
            Column(Modifier.verticalScroll(gridScroll).padding(end = 10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in names) GlyphTile(n, current = n == app.currentGlyphName) { host.openGlyph(n); onDismiss() }
                }
            }
            Scrollbar(gridScroll, true, Modifier.align(Alignment.CenterEnd).width(7.dp).fillMaxHeight())
        }
        if (app.lastReport.isNotEmpty()) {
            MonoButton(onClick = { showReport = true }, outlined = true) {
                Text("View latest report", fontSize = Type.Body)
            }
        }
        if (host.fileActions.isNotEmpty()) {
            Caps("File")
            Column {
                for ((label, action) in host.fileActions) {
                    Text(
                        label,
                        fontSize = Type.Row,
                        color = Mono.ink,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) {
                                if (label in host.confirmBefore) confirming = label else { onDismiss(); action() }
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
                if (host.fontExport != null) Text(
                    "Export as a font\u2026",
                    fontSize = Type.Row,
                    color = Mono.ink,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { showExport = true }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }
    }
    host.fontExport?.let { exportHost ->
        if (showExport) FontExportDialog(app, exportHost, onDismiss = { showExport = false }, onExported = { showExport = false; onDismiss() })
    }
    if (showReport) MonoDialog("Latest report", { showReport = false }) {
        Text(app.lastReport, color = Mono.ink, fontSize = Type.Body)
    }
}

/** The open glyph's own actions: rename it in place, or delete it after asking. */
@Composable
private fun ThisGlyphRow(app: AppState, host: EditorHost, files: GlyphFiles, current: String) {
    var renaming by remember(current) { mutableStateOf(false) }
    var newName by remember(current) { mutableStateOf(current) }
    var deleting by remember(current) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caps("This glyph")
        when {
            renaming -> {
                MonoField(newName, { newName = it }, "Glyph name", Modifier.fillMaxWidth(),
                    onDone = { if (renameGlyph(app, files, current, newName)) renaming = false })
                Text(GLYPH_NAME_HINT, fontSize = Type.Small, color = Mono.inkDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton(onClick = { renaming = false }, outlined = true) { Text("Cancel") }
                    MonoButton(onClick = { if (renameGlyph(app, files, current, newName)) renaming = false },
                        selected = true, enabled = newName.isNotBlank()) { Text("Rename") }
                }
            }
            deleting -> {
                Text("Delete \"${displayGlyphName(current)}\" and every anchor drawn for it? This can't be undone, and " +
                    "ghosts of it in other glyphs stop following it.", fontSize = Type.Body, color = Mono.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoButton(onClick = { deleting = false }, outlined = true) { Text("Cancel") }
                    MonoButton(onClick = { deleting = false; deleteGlyph(app, files, current, host.openGlyph) }, selected = true) { Text("Delete") }
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayGlyphName(current), fontSize = Type.Row, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                MonoButton(onClick = { newName = current; renaming = true }, outlined = true) { Text("Rename") }
                MonoButton(onClick = { deleting = true }, outlined = true) { Text("Delete") }
            }
        }
    }
}

/** Asks before something that can't be undone: Cancel first, the named action last. */
@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    MonoDialog(title, onDismiss, actions = {
        MonoButton(onClick = onDismiss, outlined = true) { Text("Cancel") }
        MonoButton(onClick = onConfirm, selected = true) { Text(confirmLabel) }
    }) {
        Text(message, fontSize = Type.Body, color = Mono.ink)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectGlyphPicker(app: AppState, host: EditorHost, onDismiss: () -> Unit) {
    val names = app.glyphNames
    MonoDialog("Ghost a glyph", onDismiss) {
        Text("Live: it takes the working character's weight, width and every other axis — here and in the Preview.", fontSize = Type.Body, color = Mono.inkDim)
        Box(Modifier.heightIn(max = 340.dp)) {
            val gridScroll = rememberScrollState()
            Column(Modifier.verticalScroll(gridScroll).padding(end = 10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in names) GlyphTile(n, onClickLabel = "Ghost it") { app.addLinkedGhost(n); onDismiss() }
                }
            }
            Scrollbar(gridScroll, true, Modifier.align(Alignment.CenterEnd).width(7.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun FontCharacterPicker(app: AppState, host: EditorHost, initialFont: String? = null, onDismiss: () -> Unit) {
    var chars by remember { mutableStateOf("") }
    var fontName by remember { mutableStateOf(initialFont ?: app.referenceFonts.keys.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val store = host.fontStore
    var shelf by remember { mutableStateOf<List<InstalledFont>>(emptyList()) }
    if (store != null) LaunchedEffect(store) {
        try { shelf = store.shelf.list() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    MonoDialog("Ghost from a font", onDismiss, actions = {
        MonoButton(onClick = {
            val font = fontName?.let { app.referenceFonts[it] }
            if (font == null) { error = "No font is loaded yet. Tap \"Load .ttf…\" above, or pick one from the font store."; return@MonoButton }
            var added = 0
            for (ch in chars) {
                try {
                    if (app.addFontGhost(fontName ?: "Font", font, ch.code)) added++
                } catch (e: Exception) {
                    error = "Could not read ${ch}: ${e.message}"
                }
            }
            if (added == 0) error = "That font has none of those characters." else onDismiss()
        }, selected = true) { Text("Add") }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in app.referenceFonts.keys) Chip(name, name == fontName) { fontName = name }
            // Installed store fonts not loaded yet: a tap reads the face from this device.
            if (store != null) for (font in shelf) for (face in font.faces.filter { it.readable }) {
                val label = shelfFaceLabel(font, face, shelf)
                if (app.referenceFonts.containsKey(label)) continue
                Chip(label, false) {
                    scope.launch {
                        try { fontName = loadInstalledFace(app, store, font, face); error = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message }
                    }
                }
            }
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
        Text("Each character becomes an independently adjustable ghost; weight, width and other source axes are preserved with the project.", fontSize = Type.Small, color = Mono.inkDim)
        error?.let { Text(it, color = Mono.error, fontSize = Type.Body) }
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
    // Simplify preview and ghost Place mode: node-level commands would act on nodes the user can't see.
    val locked = app.nodeEditsLocked
    when {
        locked && (mod && (e.key == Key.C || e.key == Key.X || e.key == Key.V || e.key == Key.A) ||
            e.key == Key.Delete || e.key == Key.Backspace || e.key == Key.DirectionLeft || e.key == Key.DirectionRight ||
            e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> Unit
        mod && e.key == Key.C -> app.copySelection()
        mod && e.key == Key.X -> app.cutSelection()
        mod && e.key == Key.V -> app.paste()
        mod && e.key == Key.A -> t.selectAll()
        mod && e.key == Key.Z && e.isShiftPressed -> app.redo()
        mod && e.key == Key.Y -> app.redo()
        mod && e.key == Key.Z -> app.undo()
        e.key == Key.Delete || e.key == Key.Backspace -> app.trashSelection()
        e.key == Key.Escape -> when {
            app.reduction != null -> app.reduction = null
            t.drawingContourIndex != null -> t.finishContour()
            app.activeGhost != null -> app.activeGhostId = null
            else -> t.selection = emptySet()
        }
        e.key == Key.P -> {
            val blocked = app.penBlockedReason
            if (blocked != null) app.setStatus(blocked.replaceFirstChar(Char::uppercase) + ".", true)
            else if (!app.nodeEditsLocked) {
                app.nodeMoveMode = false
                app.nodeTool = null
                if (t.drawingContourIndex == null) t.startNewContour()
            }
        }
        e.key == Key.V -> {
            if (t.drawingContourIndex != null) t.finishContour()
            app.nodeMoveMode = false
            app.nodeTool = null
        }
        e.key == Key.M -> {
            if (!app.nodeEditsLocked) {
                if (t.drawingContourIndex != null) t.finishContour()
                app.nodeMoveMode = true
                app.nodeTool = null
            }
        }
        e.key == Key.A || e.key == Key.D || e.key == Key.C -> {
            if (!app.nodeEditsLocked) {
                if (t.drawingContourIndex != null) t.finishContour()
                app.nodeMoveMode = false
                app.moveCircleVisible = false
                app.nodeTool = when (e.key) { Key.A -> NodeTool.ADD; Key.D -> NodeTool.SUBTRACT; else -> NodeTool.CONVERT }
            }
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
