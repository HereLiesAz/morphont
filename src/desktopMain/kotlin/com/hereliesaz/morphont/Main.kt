package com.hereliesaz.morphont

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private const val AUTOSAVE_DEBOUNCE_MS = 400L

/** Desktop entry point: one window around the shared [EditorScreen]. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Morphont",
        icon = painterResource(Res.drawable.morphont_logo),
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        DesktopApp()
    }
}

@Composable
fun DesktopApp() {
    MorphontTheme {
        ConveySystem {
            val app = remember { AppState().apply { glyphLookup = { DesktopStorage.loadGlyph(it) } } }
            val focus = remember { FocusRequester() }

            fun open(name: String) { DesktopStorage.loadGlyph(name)?.let { app.loadGlyph(name, it) } }

            fun createGlyph(raw: String): Boolean {
                val name = raw.trim()
                when {
                    name.isEmpty() -> app.setStatus("Type a name first.", true)
                    DesktopStorage.glyphExists(name) -> app.setStatus("A glyph named \"$name\" already exists.", true)
                    else -> {
                        val glyph = Glyph()
                        DesktopStorage.saveGlyph(name, glyph)
                        app.glyphNames = DesktopStorage.listGlyphNames()
                        app.loadGlyph(name, glyph)
                        app.setStatus("Created \"$name\". Pick the pen and start in Regular.")
                        return true
                    }
                }
                return false
            }

            fun importFont() = DesktopStorage.pickBytes("Import a variable font", listOf(".ttf"), { bytes ->
                try {
                    val result = buildFamilyFromVariableFont(bytes)
                    DesktopStorage.saveGlyphs(result.glyphs)
                    app.glyphNames = DesktopStorage.listGlyphNames()
                    result.glyphs.keys.sorted().firstOrNull()?.let { open(it) }
                    app.setStatus("Imported ${result.glyphs.size} character(s) from the variable font.")
                } catch (e: Exception) {
                    app.setStatus("Import failed: ${e.message}", true)
                }
            }, { app.setStatus(it, true) })

            fun importProject() = DesktopStorage.pickText("Open a Morphont project", { text ->
                try {
                    val names = DesktopStorage.importProjectText(text)
                    app.glyphNames = names
                    if (names.isEmpty()) { app.clearEditor(); app.setStatus("Loaded an empty project.") }
                    else { open(names.first()); app.setStatus("Loaded project (${names.size} glyph(s)).") }
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
                        "Import a variable font (.ttf)" to { importFont() },
                        "Open a project (.json)" to { importProject() },
                        "Save the whole project" to {
                            DesktopStorage.saveText("Save project", "morphont-project.json", DesktopStorage.exportProjectText(),
                                { app.setStatus("Project saved.") }, { app.setStatus(it, true) })
                        },
                        "Export this glyph (.json)" to {
                            val name = app.currentGlyphName
                            if (name != null) DesktopStorage.saveText("Export glyph", "$name.morphont.json", ProjectCodec.encodeGlyph(app.toGlyph()),
                                { app.setStatus("Exported \"$name\".") }, { app.setStatus(it, true) })
                        },
                    ),
                    touchScale = 1.3f,
                )
            }

            LaunchedEffect(Unit) {
                val names = DesktopStorage.listGlyphNames()
                app.glyphNames = names
                names.firstOrNull()?.let { open(it) }
                runCatching { focus.requestFocus() }
            }

            // Autosave: debounced within a glyph, flushed at once when the open glyph changes.
            LaunchedEffect(Unit) {
                var saveJob: Job? = null
                var pendingName: String? = null
                var pendingGlyph: Glyph? = null
                snapshotFlow { app.currentGlyphName to app.toGlyph() }.collect { (name, glyph) ->
                    if (name != pendingName) {
                        val n = pendingName; val g = pendingGlyph
                        if (n != null && g != null) { saveJob?.cancel(); DesktopStorage.saveGlyph(n, g) }
                    }
                    pendingName = name; pendingGlyph = glyph
                    if (name == null) return@collect
                    saveJob?.cancel()
                    saveJob = launch { delay(AUTOSAVE_DEBOUNCE_MS); DesktopStorage.saveGlyph(name, glyph) }
                }
            }

            Box(Modifier.fillMaxSize().focusRequester(focus).focusable().editorKeys(app)) {
                if (app.currentGlyphName == null) {
                    WelcomeScreen(app, host, actions = listOf(
                        "Import a variable font" to { importFont() },
                        "Open a Morphont project" to { importProject() },
                    ))
                } else {
                    EditorScreen(app, host)
                }
            }
        }
    }
}
