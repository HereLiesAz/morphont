package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.conveyance.ConveySystem
import compose.conveyance.tokens.ConveyShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long an edit has to sit still before autosave writes it -- coalesces a whole drag gesture's frame-by-frame updates into one write. */
private const val AUTOSAVE_DEBOUNCE_MS = 400L

/** Below this width, the 4-panel grid stacks into one scrollable column instead of a 2x2 grid. */
private val MOBILE_BREAKPOINT = 700.dp

/** Each stacked panel's height on a narrow screen. */
private val MOBILE_PANEL_HEIGHT = 420.dp

private fun createGlyph(app: AppState, rawName: String): Boolean {
    val name = rawName.trim()
    if (name.isBlank()) {
        app.setStatus("Type a name for the new glyph first.", isError = true)
        return false
    }
    if (Storage.glyphExists(name)) {
        app.setStatus("A glyph named \"$name\" already exists.", isError = true)
        return false
    }

    val glyph = Glyph()
    Storage.saveGlyph(name, glyph)
    app.glyphNames = Storage.listGlyphNames()
    app.loadGlyph(name, glyph)
    app.setStatus("Created \"$name\". Choose New contour in Regular and start drawing.")
    return true
}

private enum class OpenGlyphOutcome { OPENED, EMPTY, UNREADABLE }

private fun openFirstAvailableGlyph(app: AppState, names: List<String>): OpenGlyphOutcome {
    val firstName = names.firstOrNull() ?: return OpenGlyphOutcome.EMPTY
    val glyph = Storage.loadGlyph(firstName) ?: return OpenGlyphOutcome.UNREADABLE
    app.loadGlyph(firstName, glyph)
    return OpenGlyphOutcome.OPENED
}

private fun importProjectIntoApp(app: AppState) {
    Storage.importProject(
        onLoaded = { names ->
            app.glyphNames = names
            when (openFirstAvailableGlyph(app, names)) {
                OpenGlyphOutcome.OPENED -> app.setStatus("Loaded project (${names.size} glyph(s)); opened ${names.first()}.")
                OpenGlyphOutcome.EMPTY -> {
                    app.clearEditor()
                    app.setStatus("Loaded an empty project.")
                }
                OpenGlyphOutcome.UNREADABLE -> {
                    app.clearEditor()
                    app.setStatus(
                        "Loaded project (${names.size} glyph(s)), but \"${names.first()}\" could not be read.",
                        isError = true,
                    )
                }
            }
        },
        onError = { msg -> app.setStatus(msg, isError = true) },
    )
}

private fun importVariableFontIntoApp(app: AppState) {
    Storage.pickTtfBytes(
        onLoaded = { bytes ->
            try {
                val result = buildFamilyFromVariableFont(bytes)
                Storage.saveGlyphs(result.glyphs)
                app.glyphNames = Storage.listGlyphNames()

                val firstImportedName = result.glyphs.keys.sorted().firstOrNull()
                if (firstImportedName != null) {
                    result.glyphs[firstImportedName]?.let { app.loadGlyph(firstImportedName, it) }
                }

                val skippedNote = if (result.skippedCharacters.isEmpty()) "" else
                    " Skipped: " + result.skippedCharacters.joinToString { (cp, reason) ->
                        "U+${cp.toString(16)} ($reason)"
                    }
                val openedNote = if (firstImportedName == null) "" else " Opened $firstImportedName."
                app.setStatus(
                    "Imported ${result.glyphs.size} character(s) from the variable font.$openedNote$skippedNote",
                )
            } catch (e: Exception) {
                app.setStatus("Import failed: ${e.message}", isError = true)
            }
        },
        onError = { msg -> app.setStatus(msg, isError = true) },
    )
}

@Composable
fun App() {
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState() }

            // A returning user should never land in the empty editor. If local storage already
            // contains glyphs, open one immediately; otherwise the first-run screen is shown.
            LaunchedEffect(Unit) {
                val names = Storage.listGlyphNames()
                app.glyphNames = names
                openFirstAvailableGlyph(app, names)
            }

            // Autosave, always on. Debounces edits within one glyph, but flushes
            // immediately -- instead of losing it to the debounce's cancellation --
            // the moment the open glyph changes, so switching glyphs right after an
            // edit can never drop that edit.
            LaunchedEffect(Unit) {
                var saveJob: Job? = null
                var pendingName: String? = null
                var pendingGlyph: Glyph? = null
                snapshotFlow { app.currentGlyphName to app.toGlyph() }
                    .collect { (name, glyph) ->
                        if (name != pendingName) {
                            val outgoingName = pendingName
                            val outgoingGlyph = pendingGlyph
                            if (outgoingName != null && outgoingGlyph != null) {
                                saveJob?.cancel()
                                Storage.saveGlyph(outgoingName, outgoingGlyph)
                            }
                        }
                        pendingName = name
                        pendingGlyph = glyph
                        if (name == null) return@collect
                        saveJob?.cancel()
                        saveJob = launch {
                            delay(AUTOSAVE_DEBOUNCE_MS)
                            Storage.saveGlyph(name, glyph)
                        }
                    }
            }

            Column(Modifier.fillMaxSize().background(Mono.ground)) {
                if (app.currentGlyphName == null) {
                    WelcomePanel(app)
                } else {
                    Toolbar(app)
                    StatusLine(app)
                    EditorGrid(app, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StatusLine(app: AppState) {
    if (app.status.isEmpty()) return
    Text(
        (if (app.statusIsError) "! " else "") + app.status,
        color = if (app.statusIsError) Mono.error else Mono.ink,
        fontWeight = if (app.statusIsError) FontWeight.Bold else FontWeight.Normal,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun WelcomePanel(app: AppState) {
    var newName by remember { mutableStateOf("") }
    var savedGlyphMenuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 720.dp).background(Mono.panel).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Morphont", color = Mono.ink, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            Text(
                "Start with an existing variable font, a Morphont project, or one new glyph. " +
                    "The editor opens only after there is something real to edit.",
                color = Mono.inkDim,
                fontSize = 14.sp,
            )

            MonoButton(onClick = { importVariableFontIntoApp(app) }) {
                Text("Import variable font (.ttf)", fontSize = 14.sp)
            }
            MonoButton(onClick = { importProjectIntoApp(app) }) {
                Text("Load Morphont project (.json)", fontSize = 14.sp)
            }

            if (app.glyphNames.isNotEmpty()) {
                Box {
                    MonoButton(onClick = {
                        app.glyphNames = Storage.listGlyphNames()
                        savedGlyphMenuOpen = true
                    }) {
                        Text("Open saved glyph", fontSize = 14.sp)
                    }
                    DropdownMenu(
                        expanded = savedGlyphMenuOpen,
                        onDismissRequest = { savedGlyphMenuOpen = false },
                    ) {
                        for (name in app.glyphNames) {
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    Storage.loadGlyph(name)?.let { app.loadGlyph(name, it) }
                                    savedGlyphMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            Text("Or create a blank glyph", color = Mono.inkDim, fontSize = 12.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("glyph name", fontSize = 12.sp) },
                    textStyle = TextStyle(fontSize = 12.sp, color = Mono.ink),
                    colors = monoTextFieldColors(),
                    shape = ConveyShape.CutSmall,
                    modifier = Modifier.height(48.dp).widthIn(min = 180.dp, max = 260.dp),
                )
                MonoButton(onClick = {
                    if (createGlyph(app, newName)) newName = ""
                }) {
                    Text("Create glyph", fontSize = 12.sp)
                }
            }
            StatusLine(app)
        }
    }
}

@Composable
private fun EditorGrid(app: AppState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        if (maxWidth < MOBILE_BREAKPOINT) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnchorPanel(app.selectedAxis.lo, app, modifier = Modifier.fillMaxWidth().height(MOBILE_PANEL_HEIGHT))
                AnchorPanel(app.selectedAxis.hi, app, modifier = Modifier.fillMaxWidth().height(MOBILE_PANEL_HEIGHT))
                AnchorPanel("regular", app, modifier = Modifier.fillMaxWidth().height(MOBILE_PANEL_HEIGHT))
                PreviewPanel(app, modifier = Modifier.fillMaxWidth().height(MOBILE_PANEL_HEIGHT))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnchorPanel(app.selectedAxis.lo, app, modifier = Modifier.weight(1f).fillMaxWidth())
                    AnchorPanel(app.selectedAxis.hi, app, modifier = Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    var moreMenuOpen by remember { mutableStateOf(false) }

    FlowRow(
        Modifier.fillMaxWidth().background(Mono.panel).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
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
        }

        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            placeholder = { Text("new glyph", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 12.sp, color = Mono.ink),
            colors = monoTextFieldColors(),
            shape = ConveyShape.CutSmall,
            modifier = Modifier.height(48.dp).widthIn(min = 150.dp, max = 220.dp),
        )
        MonoButton(onClick = {
            if (createGlyph(app, newName)) newName = ""
        }) { Text("New", fontSize = 12.sp) }

        MonoButton(onClick = { app.anchors.getValue(app.activeAnchor).undo() }) {
            Text("Undo", fontSize = 12.sp)
        }
        MonoButton(onClick = { app.copyActiveToOthers() }) {
            Text("Copy active to anchors", fontSize = 12.sp)
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

        Box {
            MonoButton(onClick = { moreMenuOpen = true }) { Text("More...", fontSize = 12.sp) }
            DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                DropdownMenuItem(text = { Text("Import variable font (.ttf)") }, onClick = {
                    moreMenuOpen = false
                    importVariableFontIntoApp(app)
                })
                DropdownMenuItem(text = { Text("Load project") }, onClick = {
                    moreMenuOpen = false
                    importProjectIntoApp(app)
                })
                DropdownMenuItem(text = { Text("Save project") }, onClick = {
                    moreMenuOpen = false
                    Storage.exportProject()
                })
                DropdownMenuItem(text = { Text("Import glyph JSON") }, onClick = {
                    moreMenuOpen = false
                    val name = app.currentGlyphName ?: "imported"
                    Storage.importGlyph(
                        name = name,
                        onLoaded = { glyph -> app.loadGlyph(name, glyph) },
                        onError = { msg -> app.setStatus(msg, isError = true) },
                    )
                })
                DropdownMenuItem(text = { Text("Export current glyph JSON") }, onClick = {
                    moreMenuOpen = false
                    val name = app.currentGlyphName
                    if (name == null) {
                        app.setStatus("No glyph loaded to export.", isError = true)
                    } else {
                        Storage.exportGlyph(name, app.toGlyph())
                    }
                })
            }
        }
    }
}
