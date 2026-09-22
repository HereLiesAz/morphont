package com.hereliesaz.morphont

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.conveyance.ConveySystem
import compose.conveyance.tokens.ConveyShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANDROID_AUTOSAVE_DEBOUNCE_MS = 400L
private const val ANDROID_SESSION_SAVE_DEBOUNCE_MS = 200L

class AndroidFileActions(
    val openJson: (onLoaded: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    val saveJson: (filename: String, text: String, onSaved: () -> Unit, onError: (String) -> Unit) -> Unit,
    val openTtf: (onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) -> Unit,
)

private enum class MobilePane { LOW, REGULAR, HIGH, PREVIEW }

private enum class OpenGlyphOutcome { OPENED, EMPTY, UNREADABLE }

/**
 * Android is not the web layout squeezed until it squeaks. Phones keep the
 * one-canvas thumb workflow; larger screens switch to a rail and use the
 * extra width for the active editor plus live preview.
 */
@Composable
fun AndroidApp(storage: AndroidStorage, files: AndroidFileActions) {
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState() }
            val scope = rememberCoroutineScope()
            val focusManager = LocalFocusManager.current
            val newNameFocusRequester = remember { FocusRequester() }
            var pane by remember { mutableStateOf(MobilePane.REGULAR) }
            var glyphMenuOpen by remember { mutableStateOf(false) }
            var actionMenuOpen by remember { mutableStateOf(false) }
            var showNewGlyph by remember { mutableStateOf(false) }
            var newName by remember { mutableStateOf("") }
            var importingFont by remember { mutableStateOf(false) }
            var initialized by remember { mutableStateOf(false) }

            val loadPersistentGlyph: (String, Glyph) -> Unit = { name, glyph ->
                app.loadGlyph(name, glyph)
                storage.setLastGlyphName(name)
            }

            fun closeNewGlyph() {
                showNewGlyph = false
                focusManager.clearFocus()
            }

            fun createNewGlyph() {
                val name = newName.trim()
                when {
                    name.isEmpty() -> app.setStatus("Type a name first.", true)
                    storage.glyphExists(name) -> app.setStatus("A glyph named \"$name\" already exists.", true)
                    else -> {
                        val glyph = Glyph()
                        scope.launch {
                            withContext(Dispatchers.IO) { storage.saveGlyph(name, glyph) }
                            loadPersistentGlyph(name, glyph)
                            app.setStatus("Created \"$name\" — start with New contour in Regular.")
                            newName = ""
                            pane = MobilePane.REGULAR
                            closeNewGlyph()
                        }
                    }
                }
            }

            fun openFirstAvailableGlyph(names: List<String>): OpenGlyphOutcome {
                val firstName = names.firstOrNull() ?: return OpenGlyphOutcome.EMPTY
                val glyph = storage.loadGlyph(firstName) ?: return OpenGlyphOutcome.UNREADABLE
                loadPersistentGlyph(firstName, glyph)
                pane = MobilePane.REGULAR
                return OpenGlyphOutcome.OPENED
            }

            fun importProjectIntoApp() {
                files.openJson(
                    { text ->
                        scope.launch {
                            try {
                                val names = withContext(Dispatchers.IO) { storage.importProjectText(text) }
                                app.glyphNames = names
                                when (openFirstAvailableGlyph(names)) {
                                    OpenGlyphOutcome.OPENED -> app.setStatus("Loaded project (${names.size} glyph(s)); opened ${names.first()}.")
                                    OpenGlyphOutcome.EMPTY -> {
                                        app.clearEditor()
                                        app.setStatus("Loaded an empty project.")
                                    }
                                    OpenGlyphOutcome.UNREADABLE -> {
                                        app.clearEditor()
                                        app.setStatus(
                                            "Loaded project (${names.size} glyph(s)), but \"${names.first()}\" could not be read.",
                                            true,
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                app.setStatus("Import failed: ${e.message}", true)
                            }
                        }
                    },
                    { app.setStatus(it, true) },
                )
            }

            fun importVariableFontIntoApp() {
                if (importingFont) return
                files.openTtf(
                    { bytes ->
                        importingFont = true
                        app.setStatus("Importing variable font…")
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    buildFamilyFromVariableFont(bytes)
                                }
                                withContext(Dispatchers.IO) { storage.saveGlyphs(result.glyphs) }
                                app.glyphNames = withContext(Dispatchers.IO) { storage.listGlyphNames() }
                                val firstImportedName = result.glyphs.keys.sorted().firstOrNull()
                                if (firstImportedName != null) {
                                    result.glyphs[firstImportedName]?.let {
                                        loadPersistentGlyph(firstImportedName, it)
                                        pane = MobilePane.REGULAR
                                    }
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
                                app.setStatus("Import failed: ${e.message}", true)
                            } finally {
                                importingFont = false
                            }
                        }
                    },
                    { app.setStatus(it, true) },
                )
            }

            BackHandler(enabled = showNewGlyph || actionMenuOpen || glyphMenuOpen) {
                when {
                    showNewGlyph -> closeNewGlyph()
                    actionMenuOpen -> actionMenuOpen = false
                    glyphMenuOpen -> glyphMenuOpen = false
                }
            }

            LaunchedEffect(showNewGlyph) {
                if (showNewGlyph) {
                    delay(80)
                    newNameFocusRequester.requestFocus()
                }
            }

            LaunchedEffect(Unit) {
                app.glyphNames = storage.listGlyphNames()

                Axis.ALL.firstOrNull { it.tag == storage.selectedAxisTag() }?.let {
                    app.selectedAxis = it
                }
                storage.mobilePaneName()?.let { storedPane ->
                    MobilePane.entries.firstOrNull { it.name == storedPane }?.let {
                        pane = it
                    }
                }
                Axis.ALL.forEach { axis ->
                    storage.previewValue(axis.tag)?.let { storedValue ->
                        app.previewValues[axis.tag] = storedValue.coerceIn(0f, 1f)
                    }
                }

                val lastName = storage.lastGlyphName()
                var restored = false
                if (lastName != null) {
                    val glyph = storage.loadGlyph(lastName)
                    if (glyph != null) {
                        loadPersistentGlyph(lastName, glyph)
                        restored = true
                    } else {
                        storage.setLastGlyphName(null)
                    }
                }
                if (!restored) {
                    openFirstAvailableGlyph(app.glyphNames)
                }
                initialized = true
            }

            // Debounces edits within one glyph, but flushes immediately -- instead of
            // losing it to the debounce's cancellation -- the moment the open glyph
            // changes, so switching glyphs right after an edit can never drop it.
            LaunchedEffect(Unit) {
                var saveJob: Job? = null
                var pendingName: String? = null
                var pendingGlyph: Glyph? = null
                snapshotFlow { Triple(initialized, app.currentGlyphName, app.toGlyph()) }
                    .collect { (ready, name, glyph) ->
                        if (!ready) return@collect
                        if (name != pendingName) {
                            val outgoingName = pendingName
                            val outgoingGlyph = pendingGlyph
                            if (outgoingName != null && outgoingGlyph != null) {
                                saveJob?.cancel()
                                withContext(Dispatchers.IO) { storage.saveGlyph(outgoingName, outgoingGlyph) }
                            }
                        }
                        pendingName = name
                        pendingGlyph = glyph
                        if (name == null) return@collect
                        saveJob?.cancel()
                        saveJob = launch {
                            delay(ANDROID_AUTOSAVE_DEBOUNCE_MS)
                            withContext(Dispatchers.IO) { storage.saveGlyph(name, glyph) }
                        }
                    }
            }

            LaunchedEffect(initialized, pane, app.selectedAxis) {
                if (!initialized) return@LaunchedEffect
                storage.setSelectedAxisTag(app.selectedAxis.tag)
                storage.setMobilePaneName(pane.name)
            }

            LaunchedEffect(Unit) {
                snapshotFlow {
                    val values = Axis.ALL.associate { axis ->
                        axis.tag to (app.previewValues[axis.tag] ?: 0.5f)
                    }
                    initialized to values
                }.collectLatest { (ready, values) ->
                    if (!ready) return@collectLatest
                    delay(ANDROID_SESSION_SAVE_DEBOUNCE_MS)
                    storage.setPreviewValues(values)
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
                    onDismissRequest = { closeNewGlyph() },
                    title = { Text("New glyph") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            modifier = Modifier.focusRequester(newNameFocusRequester),
                            singleLine = true,
                            placeholder = { Text("glyph name") },
                            textStyle = TextStyle(color = Mono.ink),
                            colors = monoTextFieldColors(),
                            shape = ConveyShape.CutSmall,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { createNewGlyph() }),
                        )
                    },
                    confirmButton = {
                        MonoButton(onClick = { createNewGlyph() }) { Text("Create") }
                    },
                    dismissButton = {
                        MonoButton(onClick = { closeNewGlyph() }) { Text("Cancel") }
                    },
                    containerColor = Mono.panel,
                    titleContentColor = Mono.ink,
                    textContentColor = Mono.ink,
                )
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wideLayout = maxWidth >= 720.dp
                Scaffold(
                    containerColor = Mono.ground,
                    bottomBar = {
                        if (!wideLayout && app.currentGlyphName != null) {
                            MobilePaneBar(pane = pane, app = app, onSelect = { pane = it })
                        }
                    },
                ) { insets ->
                    Row(Modifier.fillMaxSize().padding(insets).background(Mono.ground)) {
                        if (wideLayout && app.currentGlyphName != null) {
                            MobilePaneRail(pane = pane, app = app, onSelect = { pane = it })
                        }

                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Mono.panel)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                                                    storage.loadGlyph(name)?.let { loadPersistentGlyph(name, it) }
                                                    glyphMenuOpen = false
                                                },
                                            )
                                        }
                                    }
                                }

                                MonoButton(onClick = {
                                    newName = ""
                                    showNewGlyph = true
                                }) { Text("New") }

                                Box {
                                    val activeLabel = ANCHOR_LABELS[app.activeAnchor] ?: app.activeAnchor
                                    MonoButton(onClick = { actionMenuOpen = true }) { Text("Actions") }
                                    DropdownMenu(expanded = actionMenuOpen, onDismissRequest = { actionMenuOpen = false }) {
                                        DropdownMenuItem(text = { Text("Copy $activeLabel outline to all anchors") }, onClick = {
                                            actionMenuOpen = false
                                            app.copyActiveToOthers()
                                        })
                                        DropdownMenuItem(text = { Text("Undo $activeLabel") }, onClick = {
                                            actionMenuOpen = false
                                            app.anchors.getValue(app.activeAnchor).undo()
                                        })
                                        DropdownMenuItem(text = { Text("Save glyph now") }, onClick = {
                                            actionMenuOpen = false
                                            val name = app.currentGlyphName
                                            if (name == null) app.setStatus("No glyph loaded.", true)
                                            else {
                                                val glyph = app.toGlyph()
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { storage.saveGlyph(name, glyph) }
                                                    app.setStatus("Saved \"$name\".")
                                                }
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
                                                    scope.launch {
                                                        try {
                                                            val glyph = withContext(Dispatchers.IO) { storage.importGlyphText(targetName, text) }
                                                            loadPersistentGlyph(targetName, glyph)
                                                        } catch (e: Exception) {
                                                            app.setStatus("Import failed: ${e.message}", true)
                                                        }
                                                    }
                                                },
                                                { app.setStatus(it, true) },
                                            )
                                        })
                                        DropdownMenuItem(text = { Text("Export whole project") }, onClick = {
                                            actionMenuOpen = false
                                            scope.launch {
                                                val text = withContext(Dispatchers.IO) { storage.exportProjectText() }
                                                files.saveJson(
                                                    "morphont-project.json",
                                                    text,
                                                    { app.setStatus("Project exported.") },
                                                    { app.setStatus(it, true) },
                                                )
                                            }
                                        })
                                        DropdownMenuItem(text = { Text("Import whole project") }, onClick = {
                                            actionMenuOpen = false
                                            importProjectIntoApp()
                                        })
                                        DropdownMenuItem(
                                            enabled = !importingFont,
                                            text = { Text(if (importingFont) "Importing variable font…" else "Import variable font (.ttf)") },
                                            onClick = {
                                                actionMenuOpen = false
                                                importVariableFontIntoApp()
                                            },
                                        )
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

                            if (app.currentGlyphName == null) {
                                AndroidWelcomePanel(
                                    app = app,
                                    storage = storage,
                                    importingFont = importingFont,
                                    onCreateGlyph = {
                                        newName = ""
                                        showNewGlyph = true
                                    },
                                    onImportProject = { importProjectIntoApp() },
                                    onImportVariableFont = { importVariableFontIntoApp() },
                                    onOpenGlyph = { name ->
                                        storage.loadGlyph(name)?.let {
                                            loadPersistentGlyph(name, it)
                                            pane = MobilePane.REGULAR
                                        }
                                    },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            } else {
                                AndroidWorkspace(
                                    pane = pane,
                                    app = app,
                                    wideLayout = wideLayout,
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidWelcomePanel(
    app: AppState,
    storage: AndroidStorage,
    importingFont: Boolean,
    onCreateGlyph: () -> Unit,
    onImportProject: () -> Unit,
    onImportVariableFont: () -> Unit,
    onOpenGlyph: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var savedGlyphMenuOpen by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).background(Mono.panel).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Morphont", color = Mono.ink, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text(
                "Start with a variable font, a Morphont project, or a new glyph. " +
                    "The editor opens only after there is something real to edit.",
                color = Mono.inkDim,
                fontSize = 13.sp,
            )

            MonoButton(
                onClick = onImportVariableFont,
                enabled = !importingFont,
            ) {
                Text(if (importingFont) "Importing variable font…" else "Import variable font (.ttf)")
            }
            MonoButton(onClick = onImportProject) {
                Text("Load Morphont project (.json)")
            }

            if (app.glyphNames.isNotEmpty()) {
                Box {
                    MonoButton(onClick = {
                        app.glyphNames = storage.listGlyphNames()
                        savedGlyphMenuOpen = true
                    }) {
                        Text("Open saved glyph")
                    }
                    DropdownMenu(
                        expanded = savedGlyphMenuOpen,
                        onDismissRequest = { savedGlyphMenuOpen = false },
                    ) {
                        app.glyphNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onOpenGlyph(name)
                                    savedGlyphMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            MonoButton(onClick = onCreateGlyph) {
                Text("Create blank glyph")
            }

            if (app.status.isNotEmpty()) {
                Text(
                    (if (app.statusIsError) "! " else "") + app.status,
                    color = if (app.statusIsError) Mono.error else Mono.inkDim,
                    fontWeight = if (app.statusIsError) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun MobilePaneBar(pane: MobilePane, app: AppState, onSelect: (MobilePane) -> Unit) {
    NavigationBar(containerColor = Mono.panel) {
        MobilePane.entries.forEach { item ->
            NavigationBarItem(
                selected = pane == item,
                onClick = { onSelect(item) },
                icon = { Text(paneMark(item)) },
                label = { Text(paneLabel(item, app), maxLines = 1, fontSize = 10.sp) },
            )
        }
    }
}

@Composable
private fun MobilePaneRail(pane: MobilePane, app: AppState, onSelect: (MobilePane) -> Unit) {
    NavigationRail(containerColor = Mono.panel) {
        MobilePane.entries.forEach { item ->
            NavigationRailItem(
                selected = pane == item,
                onClick = { onSelect(item) },
                icon = { Text(paneMark(item)) },
                label = { Text(paneLabel(item, app), maxLines = 1, fontSize = 9.sp) },
            )
        }
    }
}

private fun paneLabel(item: MobilePane, app: AppState): String = when (item) {
    MobilePane.LOW -> app.selectedAxis.loLabel
    MobilePane.REGULAR -> "Regular"
    MobilePane.HIGH -> app.selectedAxis.hiLabel
    MobilePane.PREVIEW -> "Preview"
}

private fun paneMark(item: MobilePane): String = when (item) {
    MobilePane.LOW -> "−"
    MobilePane.REGULAR -> "R"
    MobilePane.HIGH -> "+"
    MobilePane.PREVIEW -> "◇"
}

@Composable
private fun AndroidWorkspace(
    pane: MobilePane,
    app: AppState,
    wideLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    if (wideLayout && pane != MobilePane.PREVIEW) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1.6f).fillMaxHeight()) {
                EditablePane(pane = pane, app = app)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                PreviewPanel(app, Modifier.fillMaxSize())
            }
        }
    } else {
        Box(modifier) {
            if (pane == MobilePane.PREVIEW) {
                PreviewPanel(app, Modifier.fillMaxSize())
            } else {
                EditablePane(pane = pane, app = app)
            }
        }
    }
}

@Composable
private fun EditablePane(pane: MobilePane, app: AppState) {
    when (pane) {
        MobilePane.LOW -> TouchAnchorEditor(app.selectedAxis.lo, app)
        MobilePane.REGULAR -> TouchAnchorEditor("regular", app)
        MobilePane.HIGH -> TouchAnchorEditor(app.selectedAxis.hi, app)
        MobilePane.PREVIEW -> PreviewPanel(app, Modifier.fillMaxSize())
    }
}

@Composable
private fun TouchAnchorEditor(anchorName: String, app: AppState) {
    val state = app.anchors.getValue(anchorName)
    val active = app.activeAnchor == anchorName
    val haptics = LocalHapticFeedback.current
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
                onPointHit = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        LazyRow(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Mono.panelHeader).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                if (state.drawingContourIndex != null) {
                    MonoButton(onClick = { state.finishContour() }, selected = true) {
                        Text("Finish contour")
                    }
                } else {
                    MonoButton(onClick = { state.startNewContour() }) {
                        Text("New contour")
                    }
                }
            }
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
