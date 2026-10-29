package com.hereliesaz.morphont

import android.net.Uri
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
    /** Like [saveFont] for any file: [mimeType] should be the one [filename]'s extension means (font/otf, font/woff2...). */
    val saveFile: (filename: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit, onError: (String) -> Unit) -> Unit,
    /** Asks for a folder and keeps access to it across restarts. Says nothing if they back out. */
    val pickFolder: (onPicked: (Uri) -> Unit, onError: (String) -> Unit) -> Unit,
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
                        try {
                            storage.saveGlyph(name, glyph)
                        } catch (e: Exception) {
                            app.setStatus("Couldn't create \"$name\": ${e.message ?: e::class.simpleName}", true)
                            return false
                        }
                        app.glyphNames = storage.listGlyphNames()
                        loadPersistentGlyph(name, glyph)
                        app.setStatus(createdMessage(name, glyph))
                        return true
                    }
                }
                return false
            }

            /** Opens the first glyph that reads; a damaged file stays in the list but doesn't block the ones after it. */
            fun openFirstAvailableGlyph(names: List<String>): OpenGlyphOutcome {
                if (names.isEmpty()) return OpenGlyphOutcome.EMPTY
                for (name in names) {
                    val glyph = storage.loadGlyph(name) ?: continue
                    loadPersistentGlyph(name, glyph)
                    return OpenGlyphOutcome.OPENED
                }
                return OpenGlyphOutcome.UNREADABLE
            }

            val registry = storage.registry
            remember { app.projects = registry.projects; app.newProjectsFolder = storage.newProjectsFolder }

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
                storage.openProblem(id)?.let { app.setStatus(it, true); return }
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

            /** Registers a new project, in the folder new projects go in when the person chose one; [warning] says why not, if it couldn't. */
            fun registerProject(name: String): Pair<ProjectInfo, String?> {
                val made = registry.create(name)
                val warning = storage.placeInNewProjectsFolder(made)
                return (registry.find(made.id) ?: made) to warning
            }

            fun createProject(raw: String): Boolean {
                registry.nameProblem(raw)?.let { app.setStatus(it, true); return false }
                val (info, warning) = registerProject(raw)
                app.projects = registry.projects
                openProject(info.id)
                app.setStatus(
                    "Started \"${info.name}\"" + (info.location?.let { " in ${it.label}" } ?: "") + ". Create a glyph or import a font; everything saves as you work." +
                        (warning?.let { " $it" } ?: ""),
                    warning != null,
                )
                return true
            }

            /** A fresh project for an import started from the projects screen, opened so the import lands in it. */
            fun newProjectFor(base: String) {
                val (info, warning) = registerProject(unusedProjectName(registry, base))
                app.projects = registry.projects
                openProject(info.id)
                warning?.let { app.setStatus(it, true) }
            }

            fun renameProject(id: String, raw: String): Boolean {
                registry.nameProblem(raw, exceptId = id)?.let { app.setStatus(it, true); return false }
                val renamed = registry.rename(id, raw) ?: return false
                if (app.currentProject?.id == id) app.currentProject = renamed
                app.projects = registry.projects
                return true
            }

            fun deleteProject(id: String) {
                val kept = registry.find(id)?.location
                if (app.currentProject?.id == id) {
                    app.beginBulkImport()
                    app.clearEditor()
                    app.currentProject = null
                    app.glyphNames = emptyList()
                }
                storage.deleteProjectFiles(id)
                registry.delete(id)
                app.projects = registry.projects
                storage.releaseUnusedFolders()
                if (kept != null) app.setStatus("Removed from Morphont. Its glyph files are still in ${kept.label}.")
            }

            /** Saves what's open, then moves the open project into a folder the person picks (and starts new projects there). */
            fun chooseFolderForProject() {
                val current = app.currentProject ?: return
                files.pickFolder(
                    { tree ->
                        scope.launch {
                            try {
                                flushOpenGlyph()
                                app.setStatus("Moving \"${current.name}\"\u2026")
                                val where = withContext(Dispatchers.IO) { storage.moveProjectToFolder(current.id, tree) }
                                registry.find(current.id)?.let { if (app.currentProject?.id == it.id) app.currentProject = it }
                                app.projects = registry.projects
                                app.newProjectsFolder = storage.newProjectsFolder
                                app.setStatus("Moved \"${current.name}\" to ${where.label}. It saves there as you work, and new projects start there too.")
                            } catch (e: Exception) {
                                app.setStatus("Couldn't move \"${current.name}\": ${e.message ?: e::class.simpleName}. It's still where it was.", true)
                            }
                        }
                    },
                    { app.setStatus(it, true) },
                )
            }

            fun useAppStorageForProject() {
                val current = app.currentProject ?: return
                val from = current.location ?: return
                scope.launch {
                    try {
                        flushOpenGlyph()
                        withContext(Dispatchers.IO) { storage.moveProjectToAppStorage(current.id) }
                        registry.find(current.id)?.let { if (app.currentProject?.id == it.id) app.currentProject = it }
                        app.projects = registry.projects
                        app.setStatus("Moved \"${current.name}\" back into app storage. The folder ${from.label} still holds a copy.")
                    } catch (e: Exception) {
                        app.setStatus("Couldn't move \"${current.name}\": ${e.message ?: e::class.simpleName}. It's still where it was.", true)
                    }
                }
            }

            /** Opens a project that already lives in a folder the person picks (made by this app earlier, or copied there). */
            fun openProjectFromFolder() {
                files.pickFolder(
                    { tree ->
                        scope.launch {
                            try {
                                val info = withContext(Dispatchers.IO) { storage.adoptFolder(tree) }
                                app.projects = registry.projects
                                openProject(info.id)
                                if (app.currentProject?.id == info.id && app.glyphNames.isEmpty()) {
                                    app.setStatus("\"${info.name}\" holds no glyphs yet. If your project is in a folder inside it, open that folder instead.")
                                }
                            } catch (e: Exception) {
                                storage.releaseUnusedFolders()
                                app.setStatus("Couldn't open that folder: ${e.message ?: e::class.simpleName}", true)
                            }
                        }
                    },
                    { app.setStatus(it, true) },
                )
            }

            fun importProjectIntoApp() {
                files.openJson(
                    { text ->
                        scope.launch {
                            try {
                                ProjectCodec.decodeProject(text) // fail before anything is created
                                val (info, warning) = registerProject(unusedProjectName(registry, "Imported project"))
                                app.projects = registry.projects
                                openProject(info.id)
                                warning?.let { app.setStatus(it, true) }
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
                                // Still in the project the import began in? If another was opened meanwhile, its glyph
                                // list and open glyph are left alone: the imported glyphs are in the first project.
                                val stillHere = app.currentProject?.id == forProject
                                if (stillHere) app.glyphNames = withContext(Dispatchers.IO) { storage.listGlyphNames() }
                                val inserted = result.glyphs.keys.filterNot { it in collisions }.sorted()
                                val firstImportedName = inserted.firstOrNull()?.takeIf { stillHere }
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
                                val elsewhereNote = if (stillHere) "" else " They're in the project you imported into, not the one open now."
                                app.setStatus(
                                    "Imported ${inserted.size} character(s) from the TrueType font.$openedNote$skippedNote$elsewhereNote",
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
                    projects = ProjectActions(::createProject, ::openProject, ::renameProject, ::deleteProject, ::openProjectFromFolder),
                    location = ProjectLocationHost(
                        chooseFolder = ::chooseFolderForProject,
                        useAppStorage = ::useAppStorageForProject,
                        newProjectsInAppStorage = {
                            storage.useAppStorageForNewProjects()
                            app.newProjectsFolder = null
                            app.setStatus("New projects start in app storage.")
                        },
                    ),
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
                        renamed = { old, name -> if (storage.lastGlyphName() == old) storage.setLastGlyphName(name) },
                    ),
                    confirmBefore = mapOf("Replace this glyph from .json" to "This replaces the open glyph with the file's, on every anchor, and can't be undone. Export this glyph first to keep a copy."),
                    fontExport = FontExportHost(
                        snapshot = {
                            val unsaved = app.currentGlyphName?.let { it to app.toGlyph() }
                            withContext(Dispatchers.IO) {
                                val (glyphs, unreadable) = storage.readProject(unsaved)
                                FontSnapshot(glyphs, unreadable)
                            }
                        },
                        save = { fileName, mimeType, bytes, onSaved, onError -> files.saveFile(fileName, mimeType, bytes, onSaved, onError) },
                    ),
                )
            }

            // Back backs out of the current edit the way Esc does on a keyboard, before it leaves the app.
            BackHandler(
                enabled = app.activeGhost != null || app.reduction != null ||
                    app.commandTarget.drawingContourIndex != null || app.commandTarget.selection.isNotEmpty(),
            ) {
                when {
                    app.reduction != null -> app.reduction = null
                    app.commandTarget.drawingContourIndex != null -> app.commandTarget.finishContour()
                    app.activeGhost != null -> app.activeGhostId = null
                    else -> app.commandTarget.selection = emptySet()
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
                var pendingTicket = 0L
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
                                val failure = withContext(Dispatchers.IO) { runCatching { storage.saveGlyph(outgoingName, outgoingGlyph, pendingProject, pendingTicket) }.exceptionOrNull() }
                                failure?.let { reportSaveFailure(outgoingName, it) }
                            }
                        }
                        pendingEpoch = epoch
                        pendingName = name
                        pendingProject = projectId
                        pendingGlyph = glyph
                        // Numbered when the snapshot is taken, so an older save can't overwrite a newer one.
                        val ticket = storage.nextTicket()
                        pendingTicket = ticket
                        if (name == null) return@collect
                        saveJob?.cancel()
                        saveJob = launch {
                            delay(ANDROID_AUTOSAVE_DEBOUNCE_MS)
                            if (epoch == app.autosaveEpoch) {
                                val failure = withContext(Dispatchers.IO) { runCatching { storage.saveGlyph(name, glyph, projectId, ticket) }.exceptionOrNull() }
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
