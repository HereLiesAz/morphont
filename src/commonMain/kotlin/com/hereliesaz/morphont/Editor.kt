package com.hereliesaz.morphont

import androidx.compose.foundation.background
import com.hereliesaz.morphont.resources.Res
import com.hereliesaz.morphont.resources.morphont_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Below this width the editor is the phone layout (one canvas, axis strip, dock); above it, the desktop layout. */
private val COMPACT_BREAKPOINT = 900.dp

private val hairline = Color(0xFF181818)

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
    BoxWithConstraints(modifier.fillMaxSize().background(Mono.ground).statusBarsPadding().navigationBarsPadding()) {
        if (maxWidth < COMPACT_BREAKPOINT) CompactEditor(app, host) { browserOpen = true }
        else WideEditor(app, host) { browserOpen = true }
    }
    if (browserOpen) GlyphBrowser(app, host) { browserOpen = false }
}

private fun nodeCount(app: AppState) = app.anchors.getValue(app.activeAnchor).glyph.contours.sumOf { it.points.size }

/** Canvas plus everything that floats on it, identical in both layouts. */
@Composable
private fun Stage(app: AppState, host: EditorHost, touchScale: Float, modifier: Modifier, bottomInset: Int = 16, extraBottom: @Composable () -> Unit = {}) {
    Box(modifier) {
        AnchorCanvas(
            anchorName = app.activeAnchor,
            app = app,
            travelPathOverlay = if (app.activeAnchor == "regular") computeTravelPathOverlay(app) else null,
            interactionScale = touchScale,
            onPointHit = host.onPointHit,
            modifier = Modifier.fillMaxSize(),
        )
        val target = app.editTarget(app.activeAnchor)
        val hint = when {
            target.drawingContourIndex != null && target.glyph.contours.getOrNull(target.drawingContourIndex!!)?.points.isNullOrEmpty() ->
                "Tap to place points. Switch back to Select to close the contour."
            app.activeGhost == null && nodeCount(app) == 0 && app.activeAnchor == "regular" -> "Draw Regular first: pick the pen and tap out an outline."
            app.activeGhost == null && nodeCount(app) == 0 -> "Draw Regular first, then copy its outline here (Copy to every anchor) and reshape it."
            else -> null
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 30.dp, start = 24.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GhostBar(app)
            SelectionPill(app)
            Toast(app)
        }
        if (hint != null) {
            Text(hint, fontSize = 14.sp, color = Mono.inkDim, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SimplifyCard(app)
            extraBottom()
        }
    }
}

private fun selectTool(app: AppState) {
    val t = app.editTarget(app.activeAnchor)
    if (t.drawingContourIndex != null) t.finishContour()
}

private fun penTool(app: AppState) {
    if (app.activeGhost != null && app.ghostTransformMode) app.activeGhostId = null
    val t = app.editTarget(app.activeAnchor)
    if (t.drawingContourIndex == null) t.startNewContour()
}

// ------------------------------------------------------------------ phone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactEditor(app: AppState, host: EditorHost, openBrowser: () -> Unit) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val drawing = app.editTarget(app.activeAnchor).drawingContourIndex != null
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = openBrowser)) {
                Text(app.currentGlyphName ?: "", fontSize = 38.sp, lineHeight = 40.sp, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Caps("${ANCHOR_LABELS[app.activeAnchor]} · ${nodeCount(app)} nodes")
            }
            IconAction(MIcons.Duplicate, "Copy this outline to every anchor", { app.copyActiveToOthers() })
            IconAction(MIcons.Undo, "Undo", { app.undo() })
            IconAction(MIcons.More, "Glyphs and file", openBrowser)
        }
        AnchorFilmstrip(app, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp), compact = true)
        Stage(app, host, host.touchScale, Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                PreviewCanvas(
                    app,
                    Modifier.align(Alignment.BottomEnd).size(84.dp, 104.dp).floating(14).clickable { sheet = Sheet.PREVIEW }.padding(10.dp),
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().floating(30).height(60.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(MIcons.Select, "Select", { selectTool(app) }, selected = !drawing, size = 48)
            IconAction(MIcons.Pen, "Pen", { penTool(app) }, selected = drawing, size = 48)
            IconAction(MIcons.Guides, "Guides", { sheet = Sheet.GUIDES }, size = 48)
            IconAction(MIcons.Ghost, "Ghosts", { sheet = Sheet.GHOSTS }, selected = app.activeGhost != null, size = 48)
            IconAction(MIcons.Simplify, "Simplify", { app.startReduction() }, selected = app.reduction != null, size = 48)
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
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                when (s) {
                    Sheet.GUIDES -> GuidesSection(app)
                    Sheet.GHOSTS -> GhostsSection(app, host)
                    Sheet.PREVIEW -> PreviewSection(app, previewHeight = 280)
                }
            }
        }
    }
}

// ----------------------------------------------------------------- desktop

@Composable
private fun WideEditor(app: AppState, host: EditorHost, openBrowser: () -> Unit) {
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
            Caps("${(app.view.zoom * 100).toInt()}%")
            IconAction(MIcons.Undo, "Undo", { app.undo() }, size = 40)
            MonoButton(onClick = openBrowser, selected = true) { Text("Glyphs & file", fontSize = 13.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(60.dp).fillMaxHeight().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IconAction(MIcons.Select, "Select (V)", { selectTool(app) }, selected = !drawing, size = 42)
                IconAction(MIcons.Pen, "Pen (P)", { penTool(app) }, selected = drawing, size = 42)
                IconAction(MIcons.Measure, "Measure", { app.view.showMeasure = !app.view.showMeasure }, selected = app.view.showMeasure, size = 42)
                IconAction(MIcons.Simplify, "Simplify", { app.startReduction() }, selected = app.reduction != null, size = 42)
                IconAction(MIcons.Duplicate, "Copy this outline to every anchor", { app.copyActiveToOthers() }, size = 42)
                IconAction(MIcons.Fit, "Fit (0)", { app.view.resetView() }, size = 42)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
            Stage(app, host, 1f, Modifier.weight(1f).fillMaxHeight(), bottomInset = 18) {
                AnchorFilmstrip(app)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(hairline))
            Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(20.dp)) { PreviewSection(app) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                Box(Modifier.padding(20.dp)) { GhostsSection(app, host) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                Box(Modifier.padding(20.dp)) { GuidesSection(app) }
            }
        }
    }
}

@Composable
private fun GlyphTabs(app: AppState, host: EditorHost, openBrowser: () -> Unit, modifier: Modifier) {
    val names = remember(app.currentGlyphName, app.glyphNames) { host.listGlyphNames() }
    // The open glyph first, then its neighbours in the project's order.
    val i = names.indexOf(app.currentGlyphName).coerceAtLeast(0)
    val shown = (names.drop(i) + names.take(i)).take(10)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (n in shown) {
            val current = n == app.currentGlyphName
            Box(
                Modifier.height(34.dp).widthIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (current) Mono.panelHeader else Color.Transparent)
                    .clickable { host.openGlyph(n) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(n, fontSize = 20.sp, color = if (current) Mono.ink else Mono.inkDim, maxLines = 1) }
        }
        Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = openBrowser).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Caps("All ${names.size}")
        }
    }
}

// ----------------------------------------------------------------- welcome

/** First run: a title, the three ways in, and any glyphs already saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(app: AppState, host: EditorHost, actions: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    Box(modifier.fillMaxSize().background(Mono.ground).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
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
                        Icon(MIcons.Chevron, null, tint = Mono.inkDim, modifier = Modifier.size(16.dp).padding(0.dp))
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
                        ) { Text(n, fontSize = if (n.length <= 2) 26.sp else 12.sp, color = Mono.ink, maxLines = 1) }
                    }
                }
            }
            if (app.status.isNotEmpty()) Text(app.status, fontSize = 13.sp, color = if (app.statusIsError) Mono.error else Mono.inkDim)
            Spacer(Modifier.height(8.dp))
        }
    }
}
