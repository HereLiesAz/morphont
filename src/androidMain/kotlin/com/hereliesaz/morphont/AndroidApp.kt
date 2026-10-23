package com.hereliesaz.morphont

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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
    /** Writes a font file where the user picks; onError also when they cancel. */
    val saveFont: (filename: String, bytes: ByteArray, onSaved: () -> Unit, onError: (String) -> Unit) -> Unit,
)

private enum class OpenGlyphOutcome { OPENED, EMPTY, UNREADABLE }

/**
 * Writes the open glyph now. MainActivity calls it from onStop -- going to the background, rotating,
 * being closed -- so an edit still inside the autosave debounce is never lost.
 */
object AndroidAutosave {
    var flush: () -> Unit = {}
}

/**
 * Android shell: persistence, file pickers and haptics around the shared
 * [EditorScreen] -- the phone layout on phones, the desktop layout on wide
 * tablets, exactly as on the web.
 */
@Composable
fun AndroidApp(storage: AndroidStorage, files: AndroidFileActions, updates: UpdateChecker? = null) {
    // Read before the theme composes, so a saved light theme never flashes dark first.
    val saved = remember { storage.settings.load().also { ThemeState.dark = it.dark } }
    MorphontTheme {
        ConveySystem {
            // Android starts in touch-first mode; a hardware mouse or stylus
            // can still change the default until Move circle is explicitly set.
            val app = remember {
                AppState().apply {
                    glyphLookup = { storage.loadGlyph(it) }
                    touchInput = true
                    applySettings(saved)
                }
            }
            val keyboardFocus = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            val haptics = LocalHapticFeedback.current
            val context = LocalContext.current
            var importingFont by remember { mutableStateOf(false) }
            var initialized by remember { mutableStateOf(false) }

            val loadPersistentGlyph: (String, Glyph) -> Unit = { name, glyph ->
                app.loadGlyph(name, glyph)
                storage.setLastGlyphName(name)
            }

            fun createGlyphNamed(raw: String, glyph: Glyph = Glyph()): Boolean {
                val name = raw.trim()
                when {
                    name.isEmpty() -> app.setStatus("Type a name first.", true)
                    storage.glyphExists(name) -> app.setStatus("A glyph named \"$name\" already exists.", true)
                    else -> {
                        storage.saveGlyph(name, glyph)
                        app.glyphNames = storage.listGlyphNames()
                        loadPersistentGlyph(name, glyph)
                        app.setStatus(createdMessage(name, glyph))
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

            val registry = storage.registry
            remember { app.projects = registry.projects }

            fun reportSaveFailure(name: String, e: Throwable) =
                app.setStatus("Couldn't save \"$name\": ${e.message ?: e::class.simpleName}. Your edits are still open; save the whole project to keep a copy.", true)

            /** Saves the open glyph into the project that's open now -- must run before storage is pointed elsewhere. */
            fun flushOpenGlyph() {
                if (app.currentProject == null) return
                app.currentGlyphName?.let { name ->
                    try { storage.saveGlyph(name, app.toGlyph(), app.currentProject?.id) } catch (e: Exception) { reportSaveFailure(name, e) }
                }
            }
            AndroidAutosave.flush = { if (initialized) flushOpenGlyph() }

            fun openProject(id: String) {
                if (app.currentProject?.id == id) return
                val info = registry.open(id) ?: return
                flushOpenGlyph()
                app.beginBulkImport()
                app.clearEditor()
                storage.useProject(id)
                app.currentProject = info
                app.projects = registry.projects
                val names = storage.listGlyphNames()
                app.glyphNames = names
                val last = storage.lastGlyphName()?.let { name -> storage.loadGlyph(name)?.let { name to it } }
                if (last != null) loadPersistentGlyph(last.first, last.second) else openFirstAvailableGlyph(names)
                app.setStatus("Opened \"${info.name}\".")
            }

            fun createProject(raw: String): Boolean {
                registry.nameProblem(raw)?.let { app.setStatus(it, true); return false }
                val info = registry.create(raw)
                app.projects = registry.projects
                openProject(info.id)
                app.setStatus("Started \"${info.name}\". Create a glyph or import a font; everything saves as you work.")
                return true
            }

            /** A fresh project for an import started from the projects screen, opened so the import lands in it. */
            fun newProjectFor(base: String) {
                val info = registry.create(unusedProjectName(registry, base))
                app.projects = registry.projects
                openProject(info.id)
            }

            fun renameProject(id: String, raw: String): Boolean {
                registry.nameProblem(raw, exceptId = id)?.let { app.setStatus(it, true); return false }
                val renamed = registry.rename(id, raw) ?: return false
                if (app.currentProject?.id == id) app.currentProject = renamed
                app.projects = registry.projects
                return true
            }

            fun deleteProject(id: String) {
                if (app.currentProject?.id == id) {
                    app.beginBulkImport()
                    app.clearEditor()
                    app.currentProject = null
                    app.glyphNames = emptyList()
                }
                storage.deleteProjectFiles(id)
                registry.delete(id)
                app.projects = registry.projects
            }

            fun importProjectIntoApp() {
                files.openJson(
                    { text ->
                        scope.launch {
                            try {
                                ProjectCodec.decodeProject(text) // fail before anything is created
                                val info = registry.create(unusedProjectName(registry, "Imported project"))
                                app.projects = registry.projects
                                openProject(info.id)
                                val names = withContext(Dispatchers.IO) { storage.importProjectText(text) }
                                app.beginBulkImport()
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

            fun importVariableFontIntoApp(intoNewProject: Boolean = false) {
                if (importingFont) return
                files.openTtf(
                    { bytes ->
                        if (intoNewProject) newProjectFor("Imported font")
                        importingFont = true
                        app.setStatus("Importing TrueType font…")
                        val forProject = app.currentProject?.id
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    buildFamilyFromVariableFont(bytes)
                                }
                                val collisions = withContext(Dispatchers.IO) { storage.saveNewGlyphs(result.glyphs, forProject) }
                                app.glyphNames = withContext(Dispatchers.IO) { storage.listGlyphNames() }
                                val inserted = result.glyphs.keys.filterNot { it in collisions }.sorted()
                                val firstImportedName = inserted.firstOrNull()
                                if (firstImportedName != null) {
                                    result.glyphs[firstImportedName]?.let {
                                        loadPersistentGlyph(firstImportedName, it)
                                    }
                                }
                                val warnings = listOfNotNull(
                                    result.skippedCharacters.takeIf { it.isNotEmpty() }?.let { "${it.size} malformed/unsupported" },
                                    collisions.takeIf { it.isNotEmpty() }?.let { "${it.size} existing preserved" },
                                )
                                val skippedNote = if (warnings.isEmpty()) "" else " Skipped: ${warnings.joinToString(", ")}."
                                val openedNote = if (firstImportedName == null) "" else " Opened $firstImportedName."
                                app.setStatus(
                                    "Imported ${inserted.size} character(s) from the TrueType font.$openedNote$skippedNote",
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
                        "Import a TrueType font (.ttf)" to { importVariableFontIntoApp() },
                        "Open a Morphont project (.json)" to { importProjectIntoApp() },
                        "Save the whole project (.json)" to {
                            scope.launch {
                                val unsaved = app.currentGlyphName?.let { it to app.toGlyph() }
                                var skipped = emptyList<String>()
                                val text = withContext(Dispatchers.IO) { storage.exportProjectText(unsaved) { skipped = it } }
                                files.saveJson("morphont-project.json", text, {
                                    if (skipped.isEmpty()) app.setStatus("Project saved.")
                                    else app.setStatus("Project saved without ${skipped.size} unreadable glyph file(s): ${skipped.joinToString()}.", true)
                                }, { app.setStatus(it, true) })
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
                                            app.setStatus("Replaced \"$targetName\" from the file.")
                                        } catch (e: Exception) {
                                            app.setStatus("Import failed: ${e.message}", true)
                                        }
                                    }
                                },
                                { app.setStatus(it, true) },
                            )
                        },
                    ),
                    onPointHit = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        runCatching { keyboardFocus.requestFocus() }
                    },
                    projects = ProjectActions(::createProject, ::openProject, ::renameProject, ::deleteProject),
                    touchScale = 1.35f,
                    settings = storage.settings,
                    fontStore = AndroidFontStore.host(context, files),
                    updates = updates,
                    createGlyphFrom = { name, glyph -> createGlyphNamed(name, glyph) },
                    glyphFiles = GlyphFiles(
                        exists = storage::glyphExists,
                        load = storage::loadGlyph,
                        save = { name, glyph -> storage.saveGlyph(name, glyph) },
                        delete = { name -> storage.deleteGlyph(name); if (storage.lastGlyphName() == name) storage.setLastGlyphName(null) },
                        list = storage::listGlyphNames,
                    ),
                    confirmBefore = mapOf("Replace this glyph from .json" to "This replaces the open glyph with the file's, on every anchor, and can't be undone. Export this glyph first to keep a copy."),
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

                // Reopen where the last session left off; with no project yet, the start screen asks for one.
                registry.last?.let { openProject(it.id) }
                initialized = true
            }
            LaunchedEffect(initialized) {
                if (initialized) runCatching { keyboardFocus.requestFocus() }
            }

            // Debounces edits within one glyph, but flushes immediately -- instead of
            // losing it to the debounce's cancellation -- the moment the open glyph
            // changes, so switching glyphs right after an edit can never drop it.
            LaunchedEffect(Unit) {
                var saveJob: Job? = null
                var pendingName: String? = null
                var pendingProject: String? = null
                var pendingGlyph: Glyph? = null
                var pendingEpoch = app.autosaveEpoch
                snapshotFlow { Triple(initialized, app.autosaveEpoch, app.currentGlyphName to app.toGlyph()) to app.currentProject?.id }
                    .collect { (state, projectId) ->
                        val (ready, epoch, named) = state
                        if (!ready) return@collect
                        val (name, glyph) = named
                        if (epoch != pendingEpoch) {
                            saveJob?.cancel()
                            pendingName = null
                            pendingProject = null
                            pendingGlyph = null
                        } else if (name != pendingName) {
                            val outgoingName = pendingName
                            val outgoingGlyph = pendingGlyph
                            if (outgoingName != null && outgoingGlyph != null) {
                                saveJob?.cancel()
                                val failure = withContext(Dispatchers.IO) { runCatching { storage.saveGlyph(outgoingName, outgoingGlyph, pendingProject) }.exceptionOrNull() }
                                failure?.let { reportSaveFailure(outgoingName, it) }
                            }
                        }
                        pendingEpoch = epoch
                        pendingName = name
                        pendingProject = projectId
                        pendingGlyph = glyph
                        if (name == null) return@collect
                        saveJob?.cancel()
                        saveJob = launch {
                            delay(ANDROID_AUTOSAVE_DEBOUNCE_MS)
                            if (epoch == app.autosaveEpoch) {
                                val failure = withContext(Dispatchers.IO) { runCatching { storage.saveGlyph(name, glyph, projectId) }.exceptionOrNull() }
                                failure?.let { reportSaveFailure(name, it) }
                            }
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


            Box(Modifier.fillMaxSize().background(Mono.ground)
                .focusRequester(keyboardFocus).focusable().editorKeys(app)) {
                if (app.currentProject == null) {
                    ProjectsScreen(app, host.projects!!, starts = listOf(
                        (if (importingFont) "Importing TrueType font…" else "Import a TrueType font (.ttf)") to { importVariableFontIntoApp(intoNewProject = true) },
                        "Open a Morphont project (.json)" to { importProjectIntoApp() },
                    ))
                } else if (app.currentGlyphName == null) {
                    WelcomeScreen(
                        app,
                        host,
                        actions = listOf(
                            (if (importingFont) "Importing TrueType font…" else "Import a TrueType font (.ttf)") to { importVariableFontIntoApp() },
                            "Open a Morphont project (.json)" to { importProjectIntoApp() },
                        ),
                    )
                } else {
                    EditorScreen(app, host)
                }
                AutoUpdatePrompt(host.updates)
            }
        }
    }
}
