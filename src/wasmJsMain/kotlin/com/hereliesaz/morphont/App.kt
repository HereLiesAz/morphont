package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import compose.conveyance.tokens.ConveyShape

/** How long an edit has to sit still before autosave writes it -- coalesces a whole drag gesture's frame-by-frame updates into one write. */
private const val AUTOSAVE_DEBOUNCE_MS = 400L

@Composable
fun App() {
    MorphontTheme {
        // The real Conveyance behavioral contract, not just a copied philosophy: every
        // MonoButton registers its own visual weight here via Modifier.conveyWeight, and
        // this is what actually enforces it (throws in debug if e.g. two Hero elements or
        // too many Primary ones land on screen at once).
        ConveySystem {
        val app = remember { AppState() }

        // Autosave, always on: every edit to the open glyph's five anchors
        // -- point drags, new/deleted contours, toggled point types --
        // lands in localStorage on its own. No "unsaved changes" state to
        // lose if the tab closes; the toolbar's "Save" button still exists
        // for an explicit JSON export/import round-trip, not because a
        // click is required to persist.
        LaunchedEffect(Unit) {
            snapshotFlow { app.currentGlyphName to app.toGlyph() }
                .collectLatest { (name, glyph) ->
                    if (name == null) return@collectLatest
                    delay(AUTOSAVE_DEBOUNCE_MS)
                    Storage.saveGlyph(name, glyph)
                }
        }

        Column(Modifier.fillMaxSize().background(Mono.ground)) {
            Toolbar(app)
            if (app.status.isNotEmpty()) {
                Text(
                    (if (app.statusIsError) "! " else "") + app.status,
                    color = if (app.statusIsError) Mono.error else Mono.ink,
                    fontWeight = if (app.statusIsError) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            // 3 columns x 2 rows, matching the original tool's layout:
            //   [Extra Thin] [Condensed] [Regular]
            //   [Extra Black] [Wide]     [Preview]
            Row(Modifier.weight(1f).fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnchorPanel("extraThin", app, modifier = Modifier.weight(1f).fillMaxWidth())
                    AnchorPanel("extraBlack", app, modifier = Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnchorPanel("condensed", app, modifier = Modifier.weight(1f).fillMaxWidth())
                    AnchorPanel("wide", app, modifier = Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnchorPanel("regular", app, modifier = Modifier.weight(1f).fillMaxWidth())
                    PreviewPanel(app, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(app: AppState) {
    var newName by remember { mutableStateOf("") }
    var glyphMenuOpen by remember { mutableStateOf(false) }

    FlowRow(
        Modifier.fillMaxWidth().background(Mono.panel).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MonoButton(onClick = {
            app.glyphNames = Storage.listGlyphNames()
            glyphMenuOpen = true
        }) {
            Text(app.currentGlyphName ?: "Open glyph...", fontSize = 12.sp)
        }
        DropdownMenu(expanded = glyphMenuOpen, onDismissRequest = { glyphMenuOpen = false }) {
            for (name in app.glyphNames) {
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    Storage.loadGlyph(name)?.let { app.loadGlyph(name, it) }
                    glyphMenuOpen = false
                })
            }
        }

        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            placeholder = { Text("new glyph name", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 12.sp, color = Mono.ink),
            colors = monoTextFieldColors(),
            shape = ConveyShape.CutSmall,
            modifier = Modifier.height(48.dp),
        )
        MonoButton(onClick = {
            if (newName.isBlank()) {
                app.setStatus("Type a name for the new glyph first.", isError = true)
            } else if (Storage.glyphExists(newName)) {
                app.setStatus("A glyph named \"$newName\" already exists.", isError = true)
            } else {
                val glyph = Glyph()
                Storage.saveGlyph(newName, glyph)
                app.loadGlyph(newName, glyph)
                app.setStatus("Created \"$newName\" -- click \"New contour\" in a panel to start drawing.")
                newName = ""
            }
        }) { Text("New", fontSize = 12.sp) }

        MonoButton(onClick = { app.copyActiveToOthers() }) {
            Text("Copy ${ANCHOR_LABELS[app.activeAnchor]} to other 4", fontSize = 12.sp)
        }
        MonoButton(onClick = { app.anchors.getValue(app.activeAnchor).undo() }) {
            Text("Undo (active)", fontSize = 12.sp)
        }
        MonoButton(onClick = {
            val name = app.currentGlyphName
            if (name == null) {
                app.setStatus("No glyph loaded.", isError = true)
            } else {
                Storage.saveGlyph(name, app.toGlyph())
                app.setStatus("Saved \"$name\".")
            }
        }) { Text("Save", fontSize = 12.sp) }
        MonoButton(onClick = {
            val name = app.currentGlyphName
            if (name == null) {
                app.setStatus("No glyph loaded to export.", isError = true)
            } else {
                Storage.exportGlyph(name, app.toGlyph())
            }
        }) { Text("Export JSON", fontSize = 12.sp) }
        MonoButton(onClick = {
            val name = app.currentGlyphName ?: newName.ifBlank { "imported" }
            Storage.importGlyph(
                name = name,
                onLoaded = { glyph -> app.loadGlyph(name, glyph) },
                onError = { msg -> app.setStatus(msg, isError = true) },
            )
        }) { Text("Import JSON", fontSize = 12.sp) }
        MonoButton(onClick = { Storage.exportProject() }) { Text("Save project", fontSize = 12.sp) }
        MonoButton(onClick = {
            Storage.importProject(
                onLoaded = { names ->
                    app.clearEditor()
                    app.glyphNames = names
                    app.setStatus("Loaded project (${names.size} glyph(s)).")
                },
                onError = { msg -> app.setStatus(msg, isError = true) },
            )
        }) { Text("Load project", fontSize = 12.sp) }
        MonoButton(onClick = {
            Storage.pickTtfBytes(
                onLoaded = { bytes ->
                    try {
                        val result = buildFamilyFromVariableFont(bytes)
                        Storage.saveGlyphs(result.glyphs)
                        app.glyphNames = Storage.listGlyphNames()
                        val skippedNote = if (result.skippedCharacters.isEmpty()) "" else
                            " Skipped: " + result.skippedCharacters.joinToString { (cp, reason) -> "U+${cp.toString(16)} ($reason)" }
                        app.setStatus("Imported ${result.glyphs.size} character(s) from the variable font.$skippedNote")
                    } catch (e: Exception) {
                        app.setStatus("Import failed: ${e.message}", isError = true)
                    }
                },
                onError = { msg -> app.setStatus(msg, isError = true) },
            )
        }) { Text("Import variable font (.ttf)", fontSize = 12.sp) }
    }
}
