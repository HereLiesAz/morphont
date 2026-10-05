package com.hereliesaz.morphont

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import compose.conveyance.ConveySystem
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

private enum class OpenGlyphOutcome { OPENED, EMPTY, UNREADABLE }

/**
 * Android shell: persistence, file pickers and haptics around the shared
 * [EditorScreen] -- the phone layout on phones, the desktop layout on wide
 * tablets, exactly as on the web.
 */
@Composable
fun AndroidApp(storage: AndroidStorage, files: AndroidFileActions) {
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState().apply { glyphLookup = { storage.loadGlyph(it) } } }
            val scope = rememberCoroutineScope()
            val haptics = LocalHapticFeedback.current
            var importingFont by remember { mutableStateOf(false) }
            var initialized by remember { mutableStateOf(false) }

            val loadPersistentGlyph: (String, Glyph) -> Unit = { name, glyph ->
                app.loadGlyph(name, glyph)
                storage.setLastGlyphName(name)
            }

            fun createGlyphNamed(raw: String): Boolean {
                val name = raw.trim()
                when {
                    name.isEmpty() -> app.setStatus("Type a name first.", true)
                    storage.glyphExists(name) -> app.setStatus("A glyph named \"$name\" already exists.", true)
                    else -> {
                        val glyph = Glyph()
                        storage.saveGlyph(name, glyph)
                        app.glyphNames = storage.listGlyphNames()
                        loadPersistentGlyph(name, glyph)
                        app.setStatus("Created \"$name\". Pick the pen and start in Regular.")
                        return true
                    }
                }
                return false
            }

            fun openFirstAvailableGlyph(names: List<String>): OpenGlyphOutcome {
                val firstName = names.firstOrNull() ?: return OpenGlyphOutcome.EMPTY
                val glyph = storage.loadGlyph(firstName) ?: return OpenGlyphOutcome.UNREADABLE
                loadPersistentGlyph(firstName, glyph)
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

            val host = remember {
                EditorHost(
                    pickFontBytes = { onLoaded, onError -> files.openTtf(onLoaded, onError) },
                    openGlyph = { name -> storage.loadGlyph(name)?.let { loadPersistentGlyph(name, it) } },
                    createGlyph = { createGlyphNamed(it) },
                    listGlyphNames = { storage.listGlyphNames().also { app.glyphNames = it } },
                    fileActions = listOf(
                        "Import a variable font (.ttf)" to { importVariableFontIntoApp() },
                        "Open a project (.json)" to { importProjectIntoApp() },
                        "Export the whole project" to {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) { storage.exportProjectText() }
                                files.saveJson("morphont-project.json", text, { app.setStatus("Project exported.") }, { app.setStatus(it, true) })
                            }
                            Unit
                        },
                        "Export this glyph (.json)" to {
                            val name = app.currentGlyphName
                            if (name != null) files.saveJson(
                                "$name.morphont.json",
                                storage.exportGlyphText(app.toGlyph()),
                                { app.setStatus("Exported \"$name\".") },
                                { app.setStatus(it, true) },
                            )
                        },
                        "Replace this glyph from .json" to {
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
                        },
                    ),
                    onPointHit = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    touchScale = 1.35f,
                )
            }

            BackHandler(enabled = app.activeGhost != null || app.reduction != null) {
                when {
                    app.reduction != null -> app.reduction = null
                    app.activeGhost != null -> app.activeGhostId = null
                }
            }

            LaunchedEffect(Unit) {
                app.glyphNames = storage.listGlyphNames()

                Axis.ALL.firstOrNull { it.tag == storage.selectedAxisTag() }?.let {
                    app.selectedAxis = it
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

            LaunchedEffect(initialized, app.selectedAxis) {
                if (!initialized) return@LaunchedEffect
                storage.setSelectedAxisTag(app.selectedAxis.tag)
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


            Box(Modifier.fillMaxSize().background(Mono.ground).editorKeys(app)) {
                if (app.currentGlyphName == null) {
                    WelcomeScreen(
                        app,
                        host,
                        actions = listOf(
                            (if (importingFont) "Importing variable font…" else "Import a variable font") to { importVariableFontIntoApp() },
                            "Open a Morphont project" to { importProjectIntoApp() },
                        ),
                    )
                } else {
                    EditorScreen(app, host)
                }
            }
        }
    }
}
