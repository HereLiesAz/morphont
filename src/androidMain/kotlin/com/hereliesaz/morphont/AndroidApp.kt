package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.conveyance.ConveySystem
import compose.conveyance.tokens.ConveyShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val ANDROID_AUTOSAVE_DEBOUNCE_MS = 400L

class AndroidFileActions(
    val openJson: (onLoaded: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    val saveJson: (filename: String, text: String, onSaved: () -> Unit, onError: (String) -> Unit) -> Unit,
    val openTtf: (onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) -> Unit,
)

private enum class MobilePane { LOW, REGULAR, HIGH, PREVIEW }

/**
 * Android is not the web layout squeezed until it squeaks. The same editor
 * state and drawing engine are reflowed into one thumb-sized workspace:
 * one canvas at a time, persistent axis strip, bottom pane navigation, and
 * file/project operations behind a compact action menu.
 */
@Composable
fun AndroidApp(storage: AndroidStorage, files: AndroidFileActions) {
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState() }
            var pane by remember { mutableStateOf(MobilePane.REGULAR) }
            var glyphMenuOpen by remember { mutableStateOf(false) }
            var actionMenuOpen by remember { mutableStateOf(false) }
            var showNewGlyph by remember { mutableStateOf(false) }
            var newName by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                snapshotFlow { app.currentGlyphName to app.toGlyph() }
                    .collectLatest { (name, glyph) ->
                        if (name == null) return@collectLatest
                        delay(ANDROID_AUTOSAVE_DEBOUNCE_MS)
                        storage.saveGlyph(name, glyph)
                    }
            }

            LaunchedEffect(pane, app.selectedAxis) {
                app.activeAnchor = when (pane) {
                    MobilePane.LOW -> app.selectedAxis.lo
                    MobilePane.REGULAR -> "regular"
                    MobilePane.HIGH -> app.selectedAxis.hi
                    MobilePane.PREVIEW -> app.activeAnchor
                }
            }

            if (showNewGlyph) {
                AlertDialog(
                    onDismissRequest = { showNewGlyph = false },
                    title = { Text("New glyph") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            placeholder = { Text("glyph name") },
                            textStyle = TextStyle(color = Mono.ink),
                            colors = monoTextFieldColors(),
                            shape = ConveyShape.CutSmall,
                        )
                    },
                    confirmButton = {
                        MonoButton(onClick = {
                            val name = newName.trim()
                            when {
                                name.isEmpty() -> app.setStatus("Type a name first.", true)
                                storage.glyphExists(name) -> app.setStatus("A glyph named \"$name\" already exists.", true)
                                else -> {
                                    val glyph = Glyph()
                                    storage.saveGlyph(name, glyph)
                                    app.loadGlyph(name, glyph)
                                    newName = ""
                                    showNewGlyph = false
                                    pane = MobilePane.REGULAR
                                }
                            }
                        }) { Text("Create") }
                    },
                    dismissButton = {
                        MonoButton(onClick = { showNewGlyph = false }) { Text("Cancel") }
                    },
                    containerColor = Mono.panel,
                    titleContentColor = Mono.ink,
                    textContentColor = Mono.ink,
                )
            }

            Scaffold(
                containerColor = Mono.ground,
                bottomBar = {
                    NavigationBar(containerColor = Mono.panel) {
                        MobilePane.entries.forEach { item ->
                            val label = when (item) {
                                MobilePane.LOW -> app.selectedAxis.loLabel
                                MobilePane.REGULAR -> "Regular"
                                MobilePane.HIGH -> app.selectedAxis.hiLabel
                                MobilePane.PREVIEW -> "Preview"
                            }
                            val mark = when (item) {
                                MobilePane.LOW -> "−"
                                MobilePane.REGULAR -> "R"
                                MobilePane.HIGH -> "+"
                                MobilePane.PREVIEW -> "◇"
                            }
                            NavigationBarItem(
                                selected = pane == item,
                                onClick = { pane = item },
                                icon = { Text(mark) },
                                label = { Text(label, maxLines = 1, fontSize = 10.sp) },
                            )
                        }
                    }
                },
            ) { insets ->
                Column(Modifier.fillMaxSize().padding(insets).background(Mono.ground)) {
                    Row(
                        Modifier.fillMaxWidth().background(Mono.panel).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box {
                            MonoButton(onClick = {
                                app.glyphNames = storage.listGlyphNames()
                                glyphMenuOpen = true
                            }) {
                                Text(app.currentGlyphName ?: "Open glyph", maxLines = 1)
                            }
                            DropdownMenu(expanded = glyphMenuOpen, onDismissRequest = { glyphMenuOpen = false }) {
                                storage.listGlyphNames().forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            storage.loadGlyph(name)?.let { app.loadGlyph(name, it) }
                                            glyphMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        MonoButton(onClick = { showNewGlyph = true }) { Text("New") }

                        Box {
                            MonoButton(onClick = { actionMenuOpen = true }) { Text("Actions") }
                            DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { actionMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("Copy active outline to all anchors") }, onClick = {
                                    actionMenuOpen = false
                                    app.copyActiveToOthers()
                                })
                                DropdownMenuItem(text = { Text("Undo active anchor") }, onClick = {
                                    actionMenuOpen = false
                                    app.anchors.getValue(app.activeAnchor).undo()
                                })
                                DropdownMenuItem(text = { Text("Save glyph now") }, onClick = {
                                    actionMenuOpen = false
                                    val name = app.currentGlyphName
                                    if (name == null) app.setStatus("No glyph loaded.", true)
                                    else {
                                        storage.saveGlyph(name, app.toGlyph())
                                        app.setStatus("Saved \"$name\".")
                                    }
                                })
                                DropdownMenuItem(text = { Text("Export glyph JSON") }, onClick = {
                                    actionMenuOpen = false
                                    val name = app.currentGlyphName
                                    if (name == null) app.setStatus("No glyph loaded to export.", true)
                                    else files.saveJson(
                                        "$name.morphont.json",
                                        storage.exportGlyphText(app.toGlyph()),
                                        { app.setStatus("Exported \"$name\".") },
                                        { app.setStatus(it, true) },
                                    )
                                })
                                DropdownMenuItem(text = { Text("Import glyph JSON") }, onClick = {
                                    actionMenuOpen = false
                                    val targetName = app.currentGlyphName ?: "imported"
                                    files.openJson(
                                        { text ->
                                            try {
                                                app.loadGlyph(targetName, storage.importGlyphText(targetName, text))
                                            } catch (e: Exception) {
                                                app.setStatus("Import failed: ${e.message}", true)
                                            }
                                        },
                                        { app.setStatus(it, true) },
                                    )
                                })
                                DropdownMenuItem(text = { Text("Export whole project") }, onClick = {
                                    actionMenuOpen = false
                                    files.saveJson(
                                        "morphont-project.json",
                                        storage.exportProjectText(),
                                        { app.setStatus("Project exported.") },
                                        { app.setStatus(it, true) },
                                    )
                                })
                                DropdownMenuItem(text = { Text("Import whole project") }, onClick = {
                                    actionMenuOpen = false
                                    files.openJson(
                                        { text ->
                                            try {
                                                val names = storage.importProjectText(text)
                                                app.clearEditor()
                                                app.glyphNames = names
                                                app.setStatus("Loaded project (${names.size} glyph(s)).")
                                            } catch (e: Exception) {
                                                app.setStatus("Import failed: ${e.message}", true)
                                            }
                                        },
                                        { app.setStatus(it, true) },
                                    )
                                })
                                DropdownMenuItem(text = { Text("Import variable font (.ttf)") }, onClick = {
                                    actionMenuOpen = false
                                    files.openTtf(
                                        { bytes ->
                                            try {
                                                val result = buildFamilyFromVariableFont(bytes)
                                                storage.saveGlyphs(result.glyphs)
                                                app.glyphNames = storage.listGlyphNames()
                                                app.setStatus("Imported ${result.glyphs.size} character(s) from variable font.")
                                            } catch (e: Exception) {
                                                app.setStatus("Import failed: ${e.message}", true)
                                            }
                                        },
                                        { app.setStatus(it, true) },
                                    )
                                })
                            }
                        }
                    }

                    LazyRow(
                        Modifier.fillMaxWidth().background(Mono.panelHeader).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(Axis.ALL) { axis ->
                            MonoButton(
                                onClick = { app.selectedAxis = axis },
                                selected = axis == app.selectedAxis,
                            ) { Text(axis.label, fontSize = 11.sp) }
                        }
                    }

                    if (app.status.isNotEmpty()) {
                        Text(
                            (if (app.statusIsError) "! " else "") + app.status,
                            color = if (app.statusIsError) Mono.error else Mono.inkDim,
                            fontWeight = if (app.statusIsError) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                        when (pane) {
                            MobilePane.LOW -> TouchAnchorEditor(app.selectedAxis.lo, app)
                            MobilePane.REGULAR -> TouchAnchorEditor("regular", app)
                            MobilePane.HIGH -> TouchAnchorEditor(app.selectedAxis.hi, app)
                            MobilePane.PREVIEW -> PreviewPanel(app, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchAnchorEditor(anchorName: String, app: AppState) {
    val state = app.anchors.getValue(anchorName)
    val active = app.activeAnchor == anchorName
    Column(
        Modifier.fillMaxSize()
            .background(Mono.panel)
            .border(1.dp, if (active) Mono.primary else Mono.border),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Mono.panelHeader).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                ANCHOR_LABELS[anchorName] ?: anchorName,
                color = Mono.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            val count = state.selection.size
            if (count > 0) Text("$count selected", color = Mono.inkDim, fontSize = 11.sp)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnchorCanvas(
                anchorName = anchorName,
                state = state,
                isActive = active,
                onActivate = { app.activeAnchor = anchorName },
                travelPathOverlay = if (anchorName == "regular") computeTravelPathOverlay(app) else null,
                interactionScale = 1.35f,
                modifier = Modifier.fillMaxSize(),
            )
        }

        LazyRow(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Mono.panelHeader).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { MonoButton(onClick = { state.startNewContour() }) { Text("New contour") } }
            item {
                MonoButton(onClick = { state.toggleTypeSelected() }, enabled = state.selection.isNotEmpty()) {
                    Text("On / off")
                }
            }
            item {
                MonoButton(onClick = { state.deleteSelected() }, enabled = state.selection.isNotEmpty()) {
                    Text("Delete")
                }
            }
            item { MonoButton(onClick = { state.undo() }) { Text("Undo") } }
        }
    }
}
