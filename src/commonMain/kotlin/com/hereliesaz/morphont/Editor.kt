package com.hereliesaz.morphont

import androidx.compose.foundation.background
import com.hereliesaz.morphont.resources.Res
import com.hereliesaz.morphont.resources.morphont_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import kotlin.math.exp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Below this width the editor is the phone layout (one canvas, axis strip, dock); above it, the desktop layout. */
private val COMPACT_BREAKPOINT = 900.dp

private val hairline get() = Mono.hairline

private enum class Sheet { GUIDES, GHOSTS, PREVIEW }

/**
 * The whole editor, shared by the web and Android shells. The glyph is the
 * room: one full-bleed canvas for the anchor being edited, with every control
 * floating on it. Phones get an axis strip and a tool dock; wide screens get
 * a tool rail, an anchor filmstrip and an inspector column.
 */
@Composable
fun EditorScreen(app: AppState, host: EditorHost, modifier: Modifier = Modifier) {
    var browserOpen by remember { mutableStateOf(false) }
    var fontOverviewOpen by remember { mutableStateOf(false) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // Whatever changes in the settings is written back, so the next launch starts as this one left off.
    host.settings?.let { store ->
        androidx.compose.runtime.LaunchedEffect(store, app) {
            androidx.compose.runtime.snapshotFlow { app.settings() }.collect { store.save(it) }
        }
    }
    BoxWithConstraints(
        modifier.fillMaxSize().background(Mono.ground).statusBarsPadding().navigationBarsPadding()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() }
            // The move circle lasts until the next touch anywhere else on the screen. Watched on the way
            // down and never consumed, so the touch still does whatever it was aimed at; a touch that
            // picks a node brings the circle straight back.
            .pointerInput(app) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val area = app.moveCircleArea
                    if (area == null || (rootOrigin + down.position - area.first).getDistance() > area.second) {
                        app.moveCircleVisible = false
                    }
                }
            },
    ) {
        if (maxWidth < COMPACT_BREAKPOINT) CompactEditor(app, host, { browserOpen = true }, { fontOverviewOpen = true })
        else WideEditor(app, host, { browserOpen = true }, { fontOverviewOpen = true })
    }
    if (browserOpen) GlyphBrowser(app, host) { browserOpen = false }
    if (fontOverviewOpen) FontOverview(app, host) { fontOverviewOpen = false }
}

private fun nodeCount(app: AppState) = app.anchors.getValue(app.activeAnchor).glyph.contours.sumOf { c -> c.points.count { it.onCurve } }

/** Canvas plus everything that floats on it, identical in both layouts. */
@Composable
private fun Stage(app: AppState, host: EditorHost, touchScale: Float, modifier: Modifier, suggestions: List<Suggestion>, bottomInset: Int = 16, extraBottom: @Composable () -> Unit = {}) {
    // How far the floating rows reach into the canvas from the top and bottom, so the touch pad
    // is never placed under them (they'd take the touch first).
    var coveredTop by remember { mutableStateOf(0f) }
    var coveredBottom by remember { mutableStateOf(0f) }
    var stageTop by remember { mutableStateOf(0f) }
    var stageHeight by remember { mutableStateOf(0f) }
    Box(modifier.onGloballyPositioned { stageTop = it.positionInRoot().y; stageHeight = it.size.height.toFloat() }) {
        AnchorCanvas(
            anchorName = app.activeAnchor,
            app = app,
            travelPathOverlay = if (app.view.showTravel && app.activeAnchor == "regular") computeTravelPathOverlay(app) else null,
            interactionScale = touchScale,
            onPointHit = host.onPointHit,
            coveredTopPx = coveredTop,
            coveredBottomPx = coveredBottom,
            modifier = Modifier.fillMaxSize(),
        )
        val target = app.editTarget(app.activeAnchor)
        val hint = when {
            target.drawingContourIndex != null && target.glyph.contours.getOrNull(target.drawingContourIndex!!)?.points.isNullOrEmpty() ->
                "Tap for a corner node, drag for a smooth curve. Switch back to Select to close the contour."
            app.activeGhost == null && nodeCount(app) == 0 && app.activeAnchor == "regular" -> "Draw Regular first: pick the pen and tap out an outline."
            app.activeGhost == null && nodeCount(app) == 0 -> "Draw Regular first, then copy its outline here (Copy to every anchor) and reshape it."
            else -> null
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 30.dp, start = 24.dp, end = 12.dp)
                .onGloballyPositioned { coveredTop = it.positionInRoot().y + it.size.height - stageTop },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SuggestionsRow(app, suggestions)
            GhostBar(app)
            Toast(app)
        }
        if (hint != null) {
            Text(hint, fontSize = 14.sp, color = Mono.inkDim, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset.dp, start = 12.dp, end = 12.dp)
                .onGloballyPositioned { coveredBottom = stageTop + stageHeight - it.positionInRoot().y },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SimplifyCard(app)
            extraBottom()
        }
    }
}

/**
 * Suggestions, recomputed a beat after the outline stops changing -- never
 * on every frame of a drag.
 */
@Composable
private fun rememberSuggestions(app: AppState): List<Suggestion> {
    val glyphs = app.anchors.values.map { it.glyph }
    val state = androidx.compose.runtime.produceState(emptyList<Suggestion>(), glyphs, app.activeAnchor, app.metrics, app.activeGhostId, app.reduction) {
        kotlinx.coroutines.delay(250)
        value = suggestionsFor(app)
    }
    return state.value
}

private fun selectTool(app: AppState) {
    app.resumeGlyphSelection()
}

private fun moveNodeTool(app: AppState) {
    // Ghost node editing remains supported; placement and simplify modes
    // instead return to editable glyph nodes.
    if (app.activeGhost == null || app.ghostTransformMode || app.reduction != null) {
        app.resumeGlyphSelection()
    } else {
        app.commandTarget.drawingContourIndex?.let { app.commandTarget.finishContour() }
    }
    app.nodeMoveMode = true
    app.nodeTool = null
}

private fun nodeToolSelect(app: AppState, tool: NodeTool) {
    if (app.activeGhost == null || app.ghostTransformMode || app.reduction != null) {
        app.resumeGlyphSelection()
    } else {
        app.commandTarget.drawingContourIndex?.let { app.commandTarget.finishContour() }
    }
    app.nodeMoveMode = false
    app.moveCircleVisible = false
    app.nodeTool = tool
}

private fun penTool(app: AppState) {
    // While a ghost is being placed there's nothing to draw on; the glyph itself is off-limits.
    if (app.activeGhost != null && app.ghostTransformMode) return
    if (app.reduction != null) app.resumeGlyphSelection()
    app.nodeMoveMode = false
    app.nodeTool = null
    val t = app.editTarget(app.activeAnchor)
    if (t.drawingContourIndex == null) t.startNewContour()
}

// ------------------------------------------------------------------ phone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactEditor(
    app: AppState, host: EditorHost, openBrowser: () -> Unit,
    openFontOverview: () -> Unit,
) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val suggestions = rememberSuggestions(app)
    // Starting a ghost edit closes the sheet so the canvas is in view.
    androidx.compose.runtime.LaunchedEffect(app.activeGhostId) { if (app.activeGhostId != null) sheet = null }
    val drawing = app.editTarget(app.activeAnchor).drawingContourIndex != null
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = openBrowser)) {
                Text(app.currentGlyphName?.let(::displayGlyphName) ?: "", fontSize = 38.sp, lineHeight = 40.sp, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Caps("${ANCHOR_LABELS[app.activeAnchor]} · ${nodeCount(app)} nodes")
            }
            SuggestionsButton(app, suggestions)
            IconAction(MIcons.Undo, "Undo", { app.undo() })
            IconAction(MIcons.Redo, "Redo", { app.redo() }, enabled = app.canRedo)
            EditorMenuButton(app, host, openBrowser, openFontOverview, goTo = { sheet = when (it) { "preview" -> Sheet.PREVIEW; "ghosts" -> Sheet.GHOSTS; else -> Sheet.GUIDES } })
        }
        AnchorFilmstrip(app, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp), compact = true)
        Stage(app, host, host.touchScale, Modifier.weight(1f).fillMaxWidth(), suggestions) {
            Box(Modifier.fillMaxWidth()) {
                PreviewCanvas(
                    app,
                    Modifier.align(Alignment.BottomEnd).size(84.dp, 104.dp).floating(14).clickable { sheet = Sheet.PREVIEW }.padding(10.dp),
                )
            }
        }
        val dockScroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).floating(30)) {
            Row(
                Modifier.fillMaxWidth().height(72.dp).horizontalScroll(dockScroll)
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            ToolIcon(MIcons.Select, "Select", { selectTool(app) }, selected = !drawing && !app.nodeMoveMode && app.nodeTool == null && app.activeGhost == null && app.reduction == null, size = 44)
            ToolIcon(MIcons.Move, "Move node", { moveNodeTool(app) }, selected = !drawing && app.nodeMoveMode, size = 42)
            ToolIcon(MIcons.Pen, "Pen", { penTool(app) }, selected = drawing, size = 42, enabled = !(app.activeGhost != null && app.ghostTransformMode))
            ToolIcon(MIcons.AddNode, "Add node", { nodeToolSelect(app, NodeTool.ADD) }, selected = app.nodeTool == NodeTool.ADD, size = 42, name = "Add")
            ToolIcon(MIcons.SubtractNode, "Subtract node", { nodeToolSelect(app, NodeTool.SUBTRACT) }, selected = app.nodeTool == NodeTool.SUBTRACT, size = 42, name = "Subtract")
            ToolIcon(MIcons.ConvertNode, "Convert node", { nodeToolSelect(app, NodeTool.CONVERT) }, selected = app.nodeTool == NodeTool.CONVERT, size = 42, name = "Convert")
            NodeActions(app, size = 42)
            IconAction(MIcons.ResetView, "Reset zoom and pan", { app.view.resetView() }, size = 42)
            IconAction(MIcons.FontGrid, "View entire font", openFontOverview, size = 42)
            IconAction(MIcons.Guides, "Guides", { sheet = Sheet.GUIDES }, size = 48)
            ToolIcon(MIcons.Ghost, "Ghosts", { sheet = Sheet.GHOSTS }, selected = app.activeGhost != null, size = 48)
            ToolIcon(MIcons.Simplify, "Simplify", { app.startReduction() }, selected = app.reduction != null, size = 48, enabled = app.activeGhost == null)
            StatesMenuButton(app, size = 48)
            IconAction(MIcons.Duplicate, "Copy this outline to every anchor", { app.copyActiveToOthers() }, size = 48, enabled = app.activeGhost == null)
            }
            Scrollbar(dockScroll, false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp))
        }
    }
    val s = sheet
    if (s != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Mono.panel,
            contentColor = Mono.ink,
            scrimColor = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                val sheetScroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(sheetScroll).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                    when (s) {
                        Sheet.GUIDES -> GuidesSection(app)
                        Sheet.GHOSTS -> GhostsSection(app, host)
                        Sheet.PREVIEW -> PreviewSection(app, previewHeight = 280)
                    }
                }
                Scrollbar(sheetScroll, true, Modifier.align(Alignment.CenterEnd).width(7.dp).fillMaxHeight())
            }
        }
    }
}

// ----------------------------------------------------------------- desktop

@Composable
private fun WideEditor(
    app: AppState, host: EditorHost, openBrowser: () -> Unit,
    openFontOverview: () -> Unit,
) {
    val suggestions = rememberSuggestions(app)
    val drawing = app.editTarget(app.activeAnchor).drawingContourIndex != null
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.Image(
                    painterResource(Res.drawable.morphont_logo),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Text("Morphont", fontSize = 22.sp, fontStyle = FontStyle.Italic, color = Mono.ink)
            }
            GlyphTabs(app, host, openBrowser, Modifier.weight(1f))
            ZoomControl(app.view)
            IconAction(MIcons.FontGrid, "View entire font", openFontOverview, size = 40)
            IconAction(MIcons.Undo, "Undo", { app.undo() }, size = 40)
        IconAction(MIcons.Redo, "Redo", { app.redo() }, size = 40, enabled = app.canRedo)
        EditorMenuButton(app, host, openBrowser, openFontOverview, goTo = null, size = 40)
            MonoButton(onClick = openBrowser, selected = true) { Text("Glyphs & file", fontSize = 13.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(60.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolIcon(MIcons.Select, "Select (V)", { selectTool(app) }, selected = !drawing && !app.nodeMoveMode && app.nodeTool == null && app.activeGhost == null && app.reduction == null, size = 42)
                ToolIcon(MIcons.Move, "Move node (M)", { moveNodeTool(app) }, selected = !drawing && app.nodeMoveMode, size = 42)
                ToolIcon(MIcons.Pen, "Pen (P)", { penTool(app) }, selected = drawing, size = 42, enabled = !(app.activeGhost != null && app.ghostTransformMode))
                ToolIcon(MIcons.AddNode, "Add node (A)", { nodeToolSelect(app, NodeTool.ADD) }, selected = app.nodeTool == NodeTool.ADD, size = 42, name = "Add")
                ToolIcon(MIcons.SubtractNode, "Subtract node (D)", { nodeToolSelect(app, NodeTool.SUBTRACT) }, selected = app.nodeTool == NodeTool.SUBTRACT, size = 42, name = "Subtract")
                ToolIcon(MIcons.ConvertNode, "Convert node (C)", { nodeToolSelect(app, NodeTool.CONVERT) }, selected = app.nodeTool == NodeTool.CONVERT, size = 42, name = "Convert")
                NodeActions(app, size = 42)
                ToolIcon(MIcons.Simplify, "Simplify", { app.startReduction() }, selected = app.reduction != null, size = 42, enabled = app.activeGhost == null)
                StatesMenuButton(app, size = 42)
                IconAction(MIcons.Duplicate, "Copy this outline to every anchor", { app.copyActiveToOthers() }, size = 42, enabled = app.activeGhost == null)
                SuggestionsButton(app, suggestions, size = 42)
                IconAction(MIcons.ResetView, "Reset zoom and pan (0)", { app.view.resetView() }, size = 42)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
            Stage(app, host, 1f, Modifier.weight(1f).fillMaxHeight(), suggestions, bottomInset = 18) {
                AnchorFilmstrip(app)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
            Box(Modifier.width(360.dp).fillMaxHeight()) {
                val inspectorScroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(inspectorScroll).padding(end = 8.dp)) {
                    Box(Modifier.padding(20.dp)) { PreviewSection(app) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                    Box(Modifier.padding(20.dp)) { GhostsSection(app, host) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                    Box(Modifier.padding(20.dp)) { GuidesSection(app) }
                }
                Scrollbar(inspectorScroll, true, Modifier.align(Alignment.CenterEnd).width(8.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun GlyphTabs(app: AppState, host: EditorHost, openBrowser: () -> Unit, modifier: Modifier) {
    val names = remember(app.currentGlyphName, app.glyphNames) { host.listGlyphNames() }
    val index = names.indexOf(app.currentGlyphName).coerceAtLeast(0)
    val shown = names
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(app.currentGlyphName, names, scroll.maxValue) {
        // Keep glyph ordering stable. Bring the active tile into view instead
        // of rotating the whole list on each selection.
        if (names.isNotEmpty()) scroll.animateScrollTo((index * 45).coerceAtMost(scroll.maxValue))
    }
    val scope = rememberCoroutineScope()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconAction(MIcons.ChevronLeft, "Scroll glyphs left",
            { scope.launch { scroll.animateScrollTo((scroll.value - 280).coerceAtLeast(0)) } },
            enabled = scroll.value > 0, size = 28)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(scroll).padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (name in shown) {
                    val active = name == app.currentGlyphName
                    Box(
                        Modifier.height(34.dp).widthIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (active) Mono.panelHeader else Color.Transparent)
                            .clickable { host.openGlyph(name) }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(displayGlyphName(name), fontSize = 20.sp, color = if (active) Mono.ink else Mono.inkDim, maxLines = 1)
                    }
                }
            }
            Scrollbar(scroll, false, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(7.dp))
        }
        IconAction(MIcons.ChevronRight, "Scroll glyphs right",
            { scope.launch { scroll.animateScrollTo((scroll.value + 280).coerceAtMost(scroll.maxValue)) } },
            enabled = scroll.value < scroll.maxValue, size = 28)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = openBrowser)
            .padding(horizontal = 10.dp, vertical = 8.dp)) { Caps("All ${names.size}") }
    }
}

/**
 * Click to enter a percentage; drag left/right to change it continuously.
 * Shares the editor's canonical zoom value and the same 10–4000% limits as
 * the mouse wheel and shortcut controls.
 */
@Composable
private fun ZoomControl(view: ViewSettings) {
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) inputFocus.requestFocus()
    }
    fun commit() {
        val next = input.trim().removeSuffix("%").trim().toFloatOrNull()
        if (next != null && next.isFinite()) view.zoom = (next / 100f).coerceIn(0.1f, 40f)
        editing = false
        focusManager.clearFocus()
    }
    if (editing) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.width(92.dp).focusRequester(inputFocus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.Enter -> { commit(); true }
                    Key.Escape -> { editing = false; true }
                    else -> false
                }
            },
            singleLine = true,
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
    } else {
        Text(
            "${(view.zoom * 100f).toInt()}%",
            color = Mono.ink, fontSize = 14.sp,
            modifier = Modifier
                .pointerInput(view) {
                    var original = 1f
                    var movement = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { original = view.zoom; movement = 0f },
                        onHorizontalDrag = { change, dx ->
                            movement += dx
                            view.zoom = (original * exp(movement / 180f)).coerceIn(0.1f, 40f)
                            change.consume()
                        },
                    )
                }
                .clickable { input = (view.zoom * 100f).toInt().toString(); editing = true }
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

// ----------------------------------------------------------------- welcome

/** First run: a title, the three ways in, and any glyphs already saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(app: AppState, host: EditorHost, actions: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    Box(modifier.fillMaxSize().background(Mono.ground).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        val welcomeScroll = rememberScrollState()
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(welcomeScroll).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            androidx.compose.foundation.Image(
                painterResource(Res.drawable.morphont_logo),
                contentDescription = null,
                modifier = Modifier.size(112.dp),
            )
            Column {
                Text("Morphont", fontSize = 64.sp, lineHeight = 66.sp, fontStyle = FontStyle.Italic, color = Mono.ink)
                Caps("Three drawings per axis. The rest is arithmetic.")
            }
            Column {
                for ((label, action) in actions) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = action).padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 18.sp, color = Mono.ink, modifier = Modifier.weight(1f))
                        Icon(MIcons.Chevron, null, tint = Mono.inkDim, modifier = Modifier.size(16.dp).rotate(-90f))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoField(name, { name = it }, "New glyph", Modifier.weight(1f))
                MonoButton(onClick = { if (host.createGlyph(name)) name = "" }, selected = true, enabled = name.isNotBlank()) { Text("Create") }
            }
            if (app.glyphNames.isNotEmpty()) {
                Caps("Saved")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in app.glyphNames) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Mono.panel).border(1.dp, Mono.border, RoundedCornerShape(12.dp))
                                .clickable { host.openGlyph(n) },
                            contentAlignment = Alignment.Center,
                        ) { val label = displayGlyphName(n)
                            Text(label, fontSize = if (label.length <= 2) 26.sp else 12.sp, color = Mono.ink, maxLines = 1)
                        }
                    }
                }
            }
            if (app.status.isNotEmpty()) Text(app.status, fontSize = 13.sp, color = if (app.statusIsError) Mono.error else Mono.inkDim)
            Spacer(Modifier.height(8.dp))
        }
        Scrollbar(welcomeScroll, true, Modifier.align(Alignment.CenterEnd).width(8.dp).fillMaxHeight())
    }
}
