package com.hereliesaz.morphont

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import compose.conveyance.ConveySystem
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.events.Event
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** `document.visibilityState == "hidden"` (not in this kotlinx-browser's Document). */
private fun pageHidden(): Boolean = js("document.visibilityState === 'hidden'")

/** How long an edit has to sit still before autosave writes it -- coalesces a whole drag gesture's frame-by-frame updates into one write. */
private const val AUTOSAVE_DEBOUNCE_MS = 400L


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

/** Saves the open glyph into the project that's open now -- must run before another project is opened. */
private fun flushOpenGlyph(app: AppState) {
    if (app.currentProject == null) return
    app.currentGlyphName?.let { Storage.saveGlyph(it, app.toGlyph()) }
}

/** Leaves the open project: the editor is cleared and the start screen shows until another is entered. */
private fun closeOpenProject(app: AppState) {
    flushOpenGlyph(app)
    app.beginBulkImport()
    app.clearEditor()
    app.currentProject = null
    app.glyphNames = emptyList()
}

/** Loads [info]'s glyphs (suspends while the browser database opens) and opens the first one. */
private suspend fun enterProject(app: AppState, info: ProjectInfo, message: String) {
    val names = Storage.openProject(info.id)
    app.currentProject = info
    app.projects = Storage.registry.projects
    app.glyphNames = names
    openFirstAvailableGlyph(app, names)
    val unreadable = Storage.unreadableOnLoad
    if (unreadable.isEmpty()) app.setStatus(message)
    else app.setStatus("$message ${unreadable.size} glyph record(s) couldn't be read and are left out: ${unreadable.joinToString()}.", isError = true)
}

private fun openProject(app: AppState, scope: CoroutineScope, id: String, message: (ProjectInfo) -> String = { "Opened \"${it.name}\"." }) {
    if (app.currentProject?.id == id) return
    // Remembering it as the last-opened project can fail (storage full); opening it shouldn't.
    val info = runCatching { Storage.registry.open(id) }.getOrNull() ?: Storage.registry.find(id) ?: return
    app.projects = Storage.registry.projects
    closeOpenProject(app)
    scope.launch {
        try {
            enterProject(app, info, message(info))
        } catch (e: Throwable) {
            app.setStatus("Could not open \"${info.name}\": ${e.message ?: e::class.simpleName}", isError = true)
        }
    }
}

private fun createProject(app: AppState, scope: CoroutineScope, raw: String): Boolean {
    Storage.registry.nameProblem(raw)?.let { app.setStatus(it, isError = true); return false }
    val info = try { Storage.registry.create(raw) } catch (e: Exception) {
        app.setStatus("Couldn't start the project: ${e.message}", isError = true)
        return false
    }
    app.projects = Storage.registry.projects
    openProject(app, scope, info.id) { "Started \"${it.name}\". Create a glyph or import a font; everything saves as you work." }
    return true
}

private fun renameProject(app: AppState, id: String, raw: String): Boolean {
    Storage.registry.nameProblem(raw, exceptId = id)?.let { app.setStatus(it, isError = true); return false }
    val renamed = try { Storage.registry.rename(id, raw) } catch (e: Exception) {
        app.setStatus("Couldn't rename the project: ${e.message}", isError = true)
        return false
    } ?: return false
    if (app.currentProject?.id == id) app.currentProject = renamed
    app.projects = Storage.registry.projects
    return true
}

private fun deleteProject(app: AppState, scope: CoroutineScope, id: String) {
    try { Storage.registry.delete(id) } catch (e: Exception) {
        app.setStatus("Couldn't delete the project: ${e.message}", isError = true)
        return
    }
    if (app.currentProject?.id == id) closeOpenProject(app)
    app.projects = Storage.registry.projects
    scope.launch {
        try {
            // Another Morphont tab holding the database open blocks the delete indefinitely.
            if (withTimeoutOrNull(8_000) { Storage.deleteProjectData(id) } == null) {
                app.setStatus("The project's data is still in use by another Morphont tab; close it and the data will be erased.", isError = true)
            }
        } catch (e: Throwable) {
            app.setStatus("Could not erase the project's data: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }
}

private fun importProjectIntoApp(app: AppState) {
    Storage.importProject(
        beforeSwitch = { closeOpenProject(app) },
        onLoaded = { info, names ->
            app.currentProject = info
            app.projects = Storage.registry.projects
            app.glyphNames = names
            when (openFirstAvailableGlyph(app, names)) {
                OpenGlyphOutcome.OPENED -> app.setStatus("Loaded project (${names.size} glyph(s)) as \"${info.name}\"; opened ${names.first()}.")
                OpenGlyphOutcome.EMPTY -> app.setStatus("Loaded an empty project as \"${info.name}\".")
                OpenGlyphOutcome.UNREADABLE -> app.setStatus(
                    "Loaded project (${names.size} glyph(s)), but \"${names.first()}\" could not be read.",
                    isError = true,
                )
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
                val collisions = Storage.saveNewGlyphs(result.glyphs)
                app.glyphNames = Storage.listGlyphNames()

                val inserted = result.glyphs.keys.filterNot { it in collisions }.sorted()
                val firstImportedName = inserted.firstOrNull()
                if (firstImportedName != null) {
                    result.glyphs[firstImportedName]?.let { app.loadGlyph(firstImportedName, it) }
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
                app.setStatus("Import failed: ${e.message}", isError = true)
            }
        },
        onError = { msg -> app.setStatus(msg, isError = true) },
    )
}

@Composable
fun App() {
    // Read before the theme composes, so a saved light theme never flashes dark first.
    val saved = remember { Storage.settings.load().also { ThemeState.dark = it.dark } }
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState().apply { glyphLookup = { Storage.loadGlyph(it) }; applySettings(saved) } }
            val focus = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            val host = remember {
                EditorHost(
                    pickFontBytes = { onLoaded, onError -> Storage.pickTtfBytes(onLoaded, onError) },
                    openGlyph = { name -> Storage.loadGlyph(name)?.let { app.loadGlyph(name, it) } },
                    createGlyph = { name -> createGlyph(app, name) },
                    listGlyphNames = { Storage.listGlyphNames().also { app.glyphNames = it } },
                    fileActions = listOf(
                        "Import a TrueType font (.ttf)" to { importVariableFontIntoApp(app) },
                        "Open a project (.json)" to { importProjectIntoApp(app) },
                        "Save the whole project" to {
                            Storage.exportProject(app.currentGlyphName?.let { it to app.toGlyph() })
                        },
                        "Export this glyph (.json)" to {
                            val name = app.currentGlyphName
                            if (name != null) Storage.exportGlyph(name, app.toGlyph())
                        },
                        "Replace this glyph from .json" to {
                            val name = app.currentGlyphName ?: "imported"
                            Storage.importGlyph(
                                name = name,
                                onLoaded = { glyph -> app.loadGlyph(name, glyph) },
                                onError = { msg -> app.setStatus(msg, isError = true) },
                            )
                        },
                    ),
                    projects = ProjectActions(
                        create = { createProject(app, scope, it) },
                        open = { openProject(app, scope, it) },
                        rename = { id, name -> renameProject(app, id, name) },
                        delete = { deleteProject(app, scope, it) },
                    ),
                    touchScale = 1.3f,
                    settings = Storage.settings,
                    fontStore = WebFontStore.host,
                )
            }

            // A returning user should never land in the empty editor: reopen the project they
            // left. With no project yet, the start screen asks for one.
            LaunchedEffect(Unit) {
                app.projects = Storage.registry.projects
                Storage.registry.last?.let { last ->
                    val info = runCatching { Storage.registry.open(last.id) }.getOrNull() ?: Storage.registry.find(last.id) ?: return@let
                    try {
                        enterProject(app, info, "Opened \"${info.name}\".")
                    } catch (e: Throwable) {
                        app.setStatus("Could not open \"${info.name}\": ${e.message ?: e::class.simpleName}", isError = true)
                    }
                }
                runCatching { focus.requestFocus() }
            }

            // Autosave, always on. Debounces edits within one glyph, but flushes
            // immediately -- instead of losing it to the debounce's cancellation --
            // the moment the open glyph changes, so switching glyphs right after an
            // edit can never drop that edit.
            LaunchedEffect(Unit) {
                var saveJob: Job? = null
                var pendingName: String? = null
                var pendingGlyph: Glyph? = null
                var pendingEpoch = app.autosaveEpoch
                snapshotFlow { Triple(app.autosaveEpoch, app.currentGlyphName, app.toGlyph()) }
                    .collect { (epoch, name, glyph) ->
                        if (epoch != pendingEpoch) {
                            saveJob?.cancel()
                            pendingName = null
                            pendingGlyph = null
                        } else if (name != pendingName) {
                            val outgoingName = pendingName
                            val outgoingGlyph = pendingGlyph
                            if (outgoingName != null && outgoingGlyph != null) {
                                saveJob?.cancel()
                                Storage.saveGlyph(outgoingName, outgoingGlyph)
                            }
                        }
                        pendingEpoch = epoch
                        pendingName = name
                        pendingGlyph = glyph
                        if (name == null) return@collect
                        saveJob?.cancel()
                        saveJob = launch {
                            delay(AUTOSAVE_DEBOUNCE_MS)
                            if (epoch == app.autosaveEpoch) Storage.saveGlyph(name, glyph)
                        }
                    }
            }

            // Hiding or leaving the tab writes the open glyph now, so an edit still inside the debounce
            // isn't lost when the tab is closed.
            DisposableEffect(Unit) {
                val flush: (Event) -> Unit = {
                    if (pageHidden() || it.type == "pagehide") {
                        val name = app.currentGlyphName
                        if (name != null && app.currentProject != null) Storage.saveGlyph(name, app.toGlyph())
                    }
                }
                document.addEventListener("visibilitychange", flush)
                window.addEventListener("pagehide", flush)
                onDispose {
                    document.removeEventListener("visibilitychange", flush)
                    window.removeEventListener("pagehide", flush)
                }
            }

            Box(
                Modifier.fillMaxSize()
                    // Any press returns keyboard focus to the editor (text fields still take it after).
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            runCatching { focus.requestFocus() }
                        }
                    }
                    .focusRequester(focus)
                    .focusable()
                    .editorKeys(app),
            ) {
                if (app.currentProject == null) {
                    ProjectsScreen(app, host.projects!!)
                } else if (app.currentGlyphName == null) {
                    WelcomeScreen(
                        app,
                        host,
                        actions = listOf(
                            "Import a TrueType font" to { importVariableFontIntoApp(app) },
                            "Open a Morphont project" to { importProjectIntoApp(app) },
                        ),
                    )
                } else {
                    EditorScreen(app, host)
                }
            }
        }
    }
}
