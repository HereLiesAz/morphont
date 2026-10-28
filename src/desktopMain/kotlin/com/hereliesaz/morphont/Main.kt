package com.hereliesaz.morphont

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hereliesaz.morphont.resources.Res
import com.hereliesaz.morphont.resources.morphont_logo
import compose.conveyance.ConveySystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private const val AUTOSAVE_DEBOUNCE_MS = 400L

/**
 * The autosave not yet written (it waits [AUTOSAVE_DEBOUNCE_MS] for typing to settle). Closing the
 * window writes it first, so the last edit before quitting is never lost.
 */
private object PendingSave {
    var name: String? = null
    var glyph: Glyph? = null
    var onError: (String) -> Unit = {}

    fun write(name: String, glyph: Glyph) {
        try {
            DesktopStorage.saveGlyph(name, glyph)
        } catch (e: Exception) {
            onError("Couldn't save \"$name\": ${e.message ?: e::class.simpleName}. Your edits are still open; save the whole project to keep a copy.")
        }
    }

    fun flush() {
        val n = name ?: return
        val g = glyph ?: return
        name = null
        glyph = null
        write(n, g)
    }
}

/** Desktop entry point: one window around the shared [EditorScreen]. */
fun main() = application {
    Window(
        onCloseRequest = { PendingSave.flush(); exitApplication() },
        title = "Morphont",
        icon = painterResource(Res.drawable.morphont_logo),
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        DesktopApp()
    }
}

@Composable
fun DesktopApp() {
    // Read before the theme composes, so a saved light theme never flashes dark first.
    val saved = remember { DesktopStorage.settings.load().also { ThemeState.dark = it.dark } }
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState().apply { glyphLookup = { DesktopStorage.loadGlyph(it) }; applySettings(saved) } }
            val focus = remember { FocusRequester() }
            val scope = rememberCoroutineScope()
            PendingSave.onError = { app.setStatus(it, true) }

            fun open(name: String) { DesktopStorage.loadGlyph(name)?.let { app.loadGlyph(name, it) } }

            fun createGlyph(raw: String, glyph: Glyph = Glyph()): Boolean {
                val name = raw.trim()
                when {
                    name.isEmpty() -> app.setStatus("Type a name first.", true)
                    DesktopStorage.glyphExists(name) -> app.setStatus("A glyph named \"$name\" already exists.", true)
                    else -> {
                        DesktopStorage.saveGlyph(name, glyph)
                        app.glyphNames = DesktopStorage.listGlyphNames()
                        app.loadGlyph(name, glyph)
                        app.setStatus(createdMessage(name, glyph))
                        return true
                    }
                }
                return false
            }

            val registry = DesktopStorage.registry
            remember { app.projects = registry.projects }

            /** Saves the open glyph into the project that's open now -- must run before storage is pointed elsewhere. */
            fun flushOpenGlyph() {
                // Whatever autosave still holds belongs to this project; write it now, never later.
                PendingSave.name = null
                PendingSave.glyph = null
                if (app.currentProject == null) return
                app.currentGlyphName?.let { PendingSave.write(it, app.toGlyph()) }
            }

            fun openProject(id: String) {
                if (app.currentProject?.id == id) return
                val info = registry.open(id) ?: return
                flushOpenGlyph()
                app.beginBulkImport()
                app.clearEditor()
                DesktopStorage.useProject(id)
                app.currentProject = info
                app.projects = registry.projects
                val names = DesktopStorage.listGlyphNames()
                app.glyphNames = names
                names.firstOrNull()?.let(::open)
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

            fun importFont(intoNewProject: Boolean = false) = DesktopStorage.pickBytes("Import a TrueType font", listOf(".ttf"), { bytes ->
                scope.launch {
                    try {
                        if (intoNewProject) newProjectFor("Imported font")
                        app.setStatus("Importing TrueType font…")
                        val forProject = DesktopStorage.openProjectId
                        val result = withContext(Dispatchers.Default) { buildFamilyFromVariableFont(bytes) }
                        val collisions = withContext(Dispatchers.IO) { DesktopStorage.saveNewGlyphs(result.glyphs, forProject) }
                        val inserted = result.glyphs.keys.filterNot { it in collisions }
                        // If another project was opened meanwhile, its list and open glyph are left alone.
                        val stillHere = DesktopStorage.openProjectId == forProject
                        if (stillHere) {
                            app.glyphNames = DesktopStorage.listGlyphNames()
                            inserted.firstOrNull()?.let(::open)
                        }
                        val warnings = listOfNotNull(
                            result.skippedCharacters.takeIf { it.isNotEmpty() }?.let { "${it.size} malformed/unsupported" },
                            collisions.takeIf { it.isNotEmpty() }?.let { "${it.size} existing preserved" },
                        )
                        app.setStatus("Imported ${inserted.size} TrueType glyph(s)." +
                            (if (warnings.isEmpty()) "" else " Skipped: ${warnings.joinToString(", ")}.") +
                            (if (stillHere) "" else " They're in the project you imported into, not the one open now."))
                    } catch (e: Exception) {
                        app.setStatus("Import failed: ${e.message}", true)
                    }
                }
            }, { app.setStatus(it, true) })

            fun importUfo(intoNewProject: Boolean = false) = DesktopStorage.pickUfo({ file ->
                scope.launch {
                    try {
                        if (intoNewProject) newProjectFor("Imported UFO")
                        app.setStatus("Importing UFO…")
                        val forProject = DesktopStorage.openProjectId
                        val result = withContext(Dispatchers.IO) { importUfoFile(file) }
                        val collisions = withContext(Dispatchers.IO) { DesktopStorage.saveNewGlyphs(result.glyphs, forProject) }
                        val inserted = result.glyphs.keys.filterNot { it in collisions }
                        // If another project was opened meanwhile, its list and open glyph are left alone.
                        val stillHere = DesktopStorage.openProjectId == forProject
                        if (stillHere) {
                            app.glyphNames = DesktopStorage.listGlyphNames()
                            inserted.firstOrNull()?.let(::open)
                        }
                        val warnings = listOfNotNull(
                            result.skipped.takeIf { it.isNotEmpty() }?.let { "${it.size} malformed/unsupported" },
                            collisions.takeIf { it.isNotEmpty() }?.let { "${it.size} existing preserved" },
                        )
                        app.setStatus("Imported ${inserted.size} UFO glyph(s)." +
                            (if (warnings.isEmpty()) "" else " Skipped: ${warnings.joinToString(", ")}.") +
                            (if (stillHere) "" else " They're in the project you imported into, not the one open now."))
                    } catch (e: Exception) {
                        app.setStatus("UFO import failed: ${e.message}", true)
                    }
                }
            }, { app.setStatus(it, true) })

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
                DesktopStorage.deleteProjectFiles(id)
                registry.delete(id)
                app.projects = registry.projects
            }

            fun importProject() = DesktopStorage.pickText("Open a Morphont project", { text ->
                try {
                    ProjectCodec.decodeProject(text) // fail before anything is created
                    val info = registry.create(unusedProjectName(registry, "Imported project"))
                    app.projects = registry.projects
                    openProject(info.id)
                    val names = DesktopStorage.importProjectText(text)
                    app.beginBulkImport()
                    app.glyphNames = names
                    if (names.isEmpty()) { app.clearEditor(); app.setStatus("Loaded an empty project.") }
                    else { open(names.first()); app.setStatus("Loaded project (${names.size} glyph(s)) as \"${info.name}\".") }
                } catch (e: Exception) {
                    app.setStatus("Import failed: ${e.message}", true)
                }
            }, { app.setStatus(it, true) })

            val host = remember {
                EditorHost(
                    pickFontBytes = { onLoaded, onError -> DesktopStorage.pickBytes("Choose a font", listOf(".ttf"), onLoaded, onError) },
                    openGlyph = { open(it) },
                    createGlyph = { createGlyph(it) },
                    listGlyphNames = { DesktopStorage.listGlyphNames().also { app.glyphNames = it } },
                    fileActions = listOf(
                        "Import a TrueType font (.ttf)" to { importFont() },
                        "Import UFO (.ufo / .ufoz)" to { importUfo() },
                        "Open a Morphont project (.json)" to { importProject() },
                        "Save the whole project (.json)" to {
                            var skippedAny = false
                            DesktopStorage.saveText("Save project", "morphont-project.json", DesktopStorage.exportProjectText(
                                app.currentGlyphName?.let { it to app.toGlyph() },
                                onSkipped = { skipped -> skippedAny = true; app.setStatus("Skipped unreadable glyph file(s): ${skipped.joinToString()}.", true) },
                            ),
                                { if (!skippedAny) app.setStatus("Project saved.") }, { app.setStatus(it, true) })
                        },
                        "Export this glyph (.json)" to {
                            val name = app.currentGlyphName
                            if (name != null) DesktopStorage.saveText("Export glyph", "$name.morphont.json", ProjectCodec.encodeGlyph(app.toGlyph()),
                                { app.setStatus("Exported \"$name\".") }, { app.setStatus(it, true) })
                        },
                        "Replace this glyph from .json" to {
                            val name = app.currentGlyphName
                            if (name != null) DesktopStorage.pickText("Replace this glyph", { text ->
                                try {
                                    val glyph = ProjectCodec.decodeGlyph(text)
                                    PendingSave.name = null
                                    PendingSave.glyph = null
                                    app.beginBulkImport()
                                    DesktopStorage.saveGlyph(name, glyph)
                                    app.loadGlyph(name, glyph)
                                    app.setStatus("Replaced \"$name\" from the file.")
                                } catch (e: Exception) {
                                    app.setStatus("Import failed: ${e.message}", true)
                                }
                            }, { app.setStatus(it, true) })
                        },
                    ),
                    projects = ProjectActions(::createProject, ::openProject, ::renameProject, ::deleteProject),
                    touchScale = 1.3f,
                    settings = DesktopStorage.settings,
                    fontStore = DesktopFontStore.host,
                    updates = DesktopUpdates.checker,
                    createGlyphFrom = { name, glyph -> createGlyph(name, glyph) },
                    glyphFiles = GlyphFiles(
                        exists = DesktopStorage::glyphExists,
                        load = DesktopStorage::loadGlyph,
                        save = { name, glyph -> DesktopStorage.saveGlyph(name, glyph) },
                        // The pending autosave is dropped only once storage has really deleted (or renamed) the
                        // glyph it belongs to, so a failure leaves the snapshot for the next flush.
                        delete = { name -> DesktopStorage.deleteGlyph(name); if (PendingSave.name == name) { PendingSave.name = null; PendingSave.glyph = null } },
                        list = DesktopStorage::listGlyphNames,
                        renamed = { old, _ -> if (PendingSave.name == old) { PendingSave.name = null; PendingSave.glyph = null } },
                    ),
                    confirmBefore = mapOf("Replace this glyph from .json" to "This replaces the open glyph with the file's, on every anchor, and can't be undone. Export this glyph first to keep a copy."),
                    fontExport = FontExportHost(
                        snapshot = {
                            val unsaved = app.currentGlyphName?.let { it to app.toGlyph() }
                            withContext(Dispatchers.IO) {
                                val (project, skipped) = readableGlyphs(DesktopStorage.listGlyphNames(), DesktopStorage::loadGlyph)
                                FontSnapshot(projectForExport(project, unsaved), skipped)
                            }
                        },
                        save = { fileName, _, bytes, onSaved, onError -> DesktopStorage.saveBytes("Export font", fileName, bytes, onSaved, onError) },
                    ),
                )
            }

            // Reopen where the last session left off; with no project yet, the start screen asks for one.
            LaunchedEffect(Unit) {
                registry.last?.let { openProject(it.id) }
                runCatching { focus.requestFocus() }
            }

            // Autosave: debounced within a glyph, flushed at once when the open glyph changes.
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
                            val oldName = pendingName
                            val oldGlyph = pendingGlyph
                            if (oldName != null && oldGlyph != null) {
                                saveJob?.cancel()
                                PendingSave.write(oldName, oldGlyph)
                            }
                        }
                        pendingEpoch = epoch
                        pendingName = name
                        pendingGlyph = glyph
                        PendingSave.name = name
                        PendingSave.glyph = glyph
                        if (name == null) return@collect
                        saveJob?.cancel()
                        saveJob = launch {
                            delay(AUTOSAVE_DEBOUNCE_MS)
                            if (epoch == app.autosaveEpoch) PendingSave.flush()
                        }
                    }
                
            }

            Box(Modifier.fillMaxSize().focusRequester(focus).focusable().editorKeys(app)) {
                if (app.currentProject == null) {
                    ProjectsScreen(app, host.projects!!, starts = listOf(
                        "Import a TrueType font (.ttf)" to { importFont(intoNewProject = true) },
                        "Import UFO (.ufo / .ufoz)" to { importUfo(intoNewProject = true) },
                        "Open a Morphont project (.json)" to { importProject() },
                    ))
                } else if (app.currentGlyphName == null) {
                    WelcomeScreen(app, host, actions = listOf(
                        "Import a TrueType font (.ttf)" to { importFont() },
                        "Import UFO (.ufo / .ufoz)" to { importUfo() },
                        "Open a Morphont project (.json)" to { importProject() },
                    ))
                } else {
                    EditorScreen(app, host)
                }
                AutoUpdatePrompt(host.updates)
            }
        }
    }
}
