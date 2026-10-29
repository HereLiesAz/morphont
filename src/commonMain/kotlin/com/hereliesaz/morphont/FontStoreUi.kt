package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Loads an installed face into [AppState.referenceFonts] so the ghost picker can draw from it, and
 * returns the name it is listed under. Throws when the face isn't TrueType or can't be read.
 */
suspend fun loadInstalledFace(app: AppState, store: FontStoreHost, font: InstalledFont, face: ShelfFace): String {
    val name = shelfFaceLabel(font, face, store.shelf.list())
    // Already loaded from this package (an update or removal unloads it, so it is the current version).
    if (app.referenceFonts.containsKey(name) && name in store.loaded[font.id].orEmpty()) return name
    require(face.readable) { "${face.fileName} is ${face.format.uppercase()}; Morphont reads TrueType fonts." }
    val bytes = requireNotNull(store.shelf.bytes(face)) { "${face.fileName} is missing from this device; install it again." }
    app.referenceFonts[name] = try {
        VariableFont.parse(bytes, requireVariable = false)
    } catch (e: Exception) {
        throw IllegalArgumentException("${face.fileName} has no TrueType outlines Morphont can read (${e.message}).")
    }
    store.loaded.getOrPut(font.id) { mutableSetOf() }.add(name)
    return name
}

/** Drops the reference fonts loaded from package [id], so a ghost picker never serves a removed or outdated version. */
fun unloadInstalledFonts(app: AppState, store: FontStoreHost, id: String) {
    store.loaded.remove(id)?.forEach { app.referenceFonts.remove(it) }
}

private fun StoreFont.detail(): String = listOfNotNull(
    author?.let { "by $it" },
    if (variable) "Variable" + if (axes.isEmpty()) "" else " · " + axes.joinToString(" ") else "Static",
    if (faces > 1) "$faces faces" else null,
).joinToString(" · ")

/**
 * The azphalt font store: search and install font families, and manage the ones on this device --
 * use one as a ghost, put it on the system, or remove it. [onUseAsGhost] gets the reference-font
 * name the chosen face was loaded under.
 */
@Composable
fun FontStoreDialog(app: AppState, store: FontStoreHost, onUseAsGhost: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StoreFont>?>(null) }
    var installed by remember { mutableStateOf<List<InstalledFont>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var removing by remember { mutableStateOf<InstalledFont?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = (e.message ?: "Something went wrong.") to true
            } finally {
                busy = null
            }
        }
    }

    removing?.let { font ->
        ConfirmDialog(
            "Remove ${font.family}?",
            "It's deleted from this device, and any ghost made from it stops following it. You can install it again from the store.",
            confirmLabel = "Remove", onDismiss = { removing = null },
        ) {
            removing = null
            run("Removing…") {
                store.shelf.remove(font.id)
                unloadInstalledFonts(app, store, font.id)
                installed = store.shelf.list()
            }
        }
    }

    fun search() = run("Searching…") {
        results = store.client.search(query)
    }

    LaunchedEffect(Unit) {
        try { installed = store.shelf.list() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = (e.message ?: "Couldn't read the installed fonts.") to true }
        search()
    }

    MonoDialog("Font store", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(listOf("azphalt.store", "Installed (${installed.size})"), tab) { tab = it }
            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoField(query, { query = it }, "Search fonts", Modifier.weight(1f), onDone = { if (busy == null) search() })
                    MonoButton(onClick = { search() }, enabled = busy == null) { Text("Search") }
                }
                val list = results
                when {
                    list == null -> {}
                    list.isEmpty() -> Text(
                        if (query.isBlank()) "No fonts in the store yet." else "No fonts match \"$query\".",
                        fontSize = Type.Body, color = Mono.inkDim,
                    )
                    else -> for (f in list) {
                        val mine = installed.firstOrNull { it.id == f.id }
                        StoreRow(f.family, f.detail()) {
                            when {
                                f.paid -> Caps("Sold in the store")
                                mine != null && mine.version == f.version -> Caps("Installed")
                                else -> MonoButton(onClick = {
                                    run("Installing ${f.family}…") {
                                        store.shelf.install(store.client.download(f))
                                        unloadInstalledFonts(app, store, f.id)
                                        installed = store.shelf.list()
                                        message = "Installed ${f.family}." to false
                                    }
                                }, enabled = busy == null, selected = true) { Text(if (mine != null) "Update" else "Install", fontSize = Type.Body) }
                            }
                        }
                    }
                }
            } else {
                if (installed.isEmpty()) Text("Nothing installed yet. Fonts you install from the store appear here.", fontSize = Type.Body, color = Mono.inkDim)
                for (font in installed) {
                    val faces = font.faces
                    StoreRow(font.family, listOfNotNull(font.author?.let { "by $it" }, "v${font.version}", font.license.ifEmpty { null }).joinToString(" · ")) {}
                    Row(Modifier.padding(start = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val readable = faces.firstOrNull { it.readable }
                        if (readable != null) MonoButton(onClick = {
                            run("Loading ${font.family}…") { onUseAsGhost(loadInstalledFace(app, store, font, readable)) }
                        }, enabled = busy == null, outlined = true) { Text("Use as ghost", fontSize = Type.Small) }
                        MonoButton(onClick = {
                            run("${store.installLabel}…") {
                                // One face failing doesn't hide what happened to the others.
                                var failed = false
                                val lines = faces.map { face ->
                                    try {
                                        val bytes = requireNotNull(store.shelf.bytes(face)) { "${face.fileName} is missing; install it again." }
                                        store.installToSystem(font.id, face.fileName, bytes)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed = true
                                        "${face.fileName}: ${e.message ?: "failed"}."
                                    }
                                }
                                message = lines.joinToString(" ") to failed
                            }
                        }, enabled = busy == null, outlined = true) { Text(store.installLabel, fontSize = Type.Small) }
                        MonoButton(onClick = { removing = font }, enabled = busy == null, outlined = true) { Text("Remove") }
                    }
                }
            }
            busy?.let { Text(it, fontSize = Type.Body, color = Mono.inkDim) }
            message?.let { (text, error) -> Text(text, fontSize = Type.Body, color = if (error) Mono.error else Mono.ink) }
        }
    }
}

@Composable
private fun StoreRow(title: String, detail: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mono.panelHeader).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = Type.Row, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, fontSize = Type.Micro, color = Mono.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
    Spacer(Modifier.height(4.dp))
}
