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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val appBg = Color(0xFF1E1E1E)
private val toolbarBg = Color(0xFF262626)

private val MorphontColors = darkColorScheme(
    primary = Color(0xFF2D6CDF),
    background = appBg,
    surface = Color(0xFF262626),
    onBackground = Color(0xFFDDDDDD),
    onSurface = Color(0xFFDDDDDD),
)

@Composable
fun App() {
    MaterialTheme(colorScheme = MorphontColors) {
        val app = remember { AppState() }

        Column(Modifier.fillMaxSize().background(appBg)) {
            Toolbar(app)
            if (app.status.isNotEmpty()) {
                Text(
                    app.status,
                    color = if (app.statusIsError) Color(0xFFEE7777) else Color(0xFF99AA99),
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(app: AppState) {
    var newName by remember { mutableStateOf("") }
    var glyphMenuOpen by remember { mutableStateOf(false) }

    FlowRow(
        Modifier.fillMaxWidth().background(toolbarBg).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = {
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
            textStyle = TextStyle(fontSize = 12.sp),
            modifier = Modifier.height(48.dp),
        )
        Button(onClick = {
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

        Button(onClick = { app.copyActiveToOthers() }) {
            Text("Copy ${ANCHOR_LABELS[app.activeAnchor]} to other 4", fontSize = 12.sp)
        }
        Button(onClick = { app.anchors.getValue(app.activeAnchor).undo() }) {
            Text("Undo (active)", fontSize = 12.sp)
        }
        Button(onClick = {
            val name = app.currentGlyphName
            if (name == null) {
                app.setStatus("No glyph loaded.", isError = true)
            } else {
                Storage.saveGlyph(name, app.toGlyph())
                app.setStatus("Saved \"$name\".")
            }
        }) { Text("Save", fontSize = 12.sp) }
        Button(onClick = {
            val name = app.currentGlyphName
            if (name == null) {
                app.setStatus("No glyph loaded to export.", isError = true)
            } else {
                Storage.exportGlyph(name, app.toGlyph())
            }
        }) { Text("Export JSON", fontSize = 12.sp) }
        Button(onClick = {
            val name = app.currentGlyphName ?: newName.ifBlank { "imported" }
            Storage.importGlyph(
                name = name,
                onLoaded = { glyph -> app.loadGlyph(name, glyph) },
                onError = { msg -> app.setStatus(msg, isError = true) },
            )
        }) { Text("Import JSON", fontSize = 12.sp) }
        Button(onClick = {
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
