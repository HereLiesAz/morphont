package com.hereliesaz.morphont

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import compose.conveyance.ConveySystem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            val app = remember { AppState().apply { glyphLookup = { Storage.loadGlyph(it) } } }
            val focus = remember { FocusRequester() }
            val host = remember {
                EditorHost(
                    pickFontBytes = { onLoaded, onError -> Storage.pickTtfBytes(onLoaded, onError) },
                    openGlyph = { name -> Storage.loadGlyph(name)?.let { app.loadGlyph(name, it) } },
                    createGlyph = { name -> createGlyph(app, name) },
                    listGlyphNames = { Storage.listGlyphNames().also { app.glyphNames = it } },
                    fileActions = listOf(
                        "Import a variable font (.ttf)" to { importVariableFontIntoApp(app) },
                        "Open a project (.json)" to { importProjectIntoApp(app) },
                        "Save the whole project" to { Storage.exportProject() },
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
                    touchScale = 1.3f,
                )
            }

            // A returning user should never land in the empty editor. If local storage already
            // contains glyphs, open one immediately; otherwise the first-run screen is shown.
            LaunchedEffect(Unit) {
                val names = Storage.listGlyphNames()
                app.glyphNames = names
                openFirstAvailableGlyph(app, names)
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
                if (app.currentGlyphName == null) {
                    WelcomeScreen(
                        app,
                        host,
                        actions = listOf(
                            "Import a variable font" to { importVariableFontIntoApp(app) },
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
