package com.hereliesaz.morphont

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** Global JS `encodeURIComponent`, used to build a `data:` URL for export (avoids Blob/JsArray interop). */
external fun encodeURIComponent(str: String): String

/**
 * A whole project (every glyph, keyed by name) lives in a single
 * localStorage entry -- this tool has no server, so the browser's own
 * storage is the only persistence unless the user exports/imports JSON.
 */
private const val STORAGE_KEY = "morphont:project"

/**
 * Anchor names from before the N-axis rework (`extraThin`/`extraBlack`/
 * `condensed`/`wide`) -> today's tag-based ones (`wght_lo`/`wght_hi`/...),
 * so a glyph saved under the old scheme still loads instead of silently
 * losing its extremes. `regular` is unchanged in both schemes.
 */
private val LEGACY_ANCHOR_NAMES = mapOf(
    "extraThin" to Axis.WEIGHT.lo,
    "extraBlack" to Axis.WEIGHT.hi,
    "condensed" to Axis.WIDTH.lo,
    "wide" to Axis.WIDTH.hi,
)

private fun migrateGlyph(glyph: Glyph): Glyph {
    if (LEGACY_ANCHOR_NAMES.keys.none { it in glyph.corners }) return glyph
    val migrated = glyph.corners.mapKeys { (name, _) -> LEGACY_ANCHOR_NAMES[name] ?: name }
    return Glyph(migrated.toMutableMap())
}

private fun readProject(): MutableMap<String, Glyph> {
    val raw = window.localStorage.getItem(STORAGE_KEY) ?: return mutableMapOf()
    return try {
        json.decodeFromString<Map<String, Glyph>>(raw).mapValues { migrateGlyph(it.value) }.toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }
}

private fun writeProject(project: Map<String, Glyph>) {
    window.localStorage.setItem(STORAGE_KEY, json.encodeToString(project))
}

object Storage {
    fun listGlyphNames(): List<String> = readProject().keys.sorted()

    fun loadGlyph(name: String): Glyph? = readProject()[name]

    fun saveGlyph(name: String, glyph: Glyph) {
        val project = readProject()
        project[name] = glyph
        writeProject(project)
    }

    /** Merges every entry in [glyphs] into the project in one write, overwriting any existing glyph with the same name. */
    fun saveGlyphs(glyphs: Map<String, Glyph>) {
        val project = readProject()
        project.putAll(glyphs)
        writeProject(project)
    }

    fun glyphExists(name: String): Boolean = readProject().containsKey(name)

    /** Triggers a browser download of the whole project (every glyph) as a single JSON file. */
    fun exportProject() {
        val text = json.encodeToString(readProject())
        val href = "data:application/json;charset=utf-8," + encodeURIComponent(text)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = href
        anchor.download = "morphont-project.json"
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
    }

    /**
     * Opens a file picker for a whole-project JSON file (as written by
     * [exportProject]); on selection, replaces the entire stored project
     * with its contents and invokes [onLoaded] with the new glyph name
     * list. Errors are reported via [onError].
     */
    fun importProject(onLoaded: (List<String>) -> Unit, onError: (String) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = ".json,application/json"
        input.addEventListener("change", { _: Event ->
            val file: File? = input.files?.item(0)
            if (file == null) {
                onError("No file selected.")
                return@addEventListener
            }
            val reader = FileReader()
            reader.onload = {
                try {
                    val text = reader.result as String
                    val project = json.decodeFromString<Map<String, Glyph>>(text).mapValues { migrateGlyph(it.value) }
                    writeProject(project)
                    onLoaded(project.keys.sorted())
                } catch (e: Exception) {
                    onError("Import failed: ${e.message}")
                }
            }
            reader.readAsText(file)
        })
        input.click()
    }

    /** Triggers a browser download of [glyph] as a standalone JSON file. */
    fun exportGlyph(name: String, glyph: Glyph) {
        val text = json.encodeToString(glyph)
        val href = "data:application/json;charset=utf-8," + encodeURIComponent(text)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = href
        anchor.download = "$name.morphont.json"
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
    }

    /**
     * Opens a file picker; on selection, parses the chosen JSON file as a
     * [Glyph], saves it into the project under [name], and invokes
     * [onLoaded] with the parsed glyph. Errors are reported via [onError].
     */
    fun importGlyph(name: String, onLoaded: (Glyph) -> Unit, onError: (String) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = ".json,application/json"
        input.addEventListener("change", { _: Event ->
            val file: File? = input.files?.item(0)
            if (file == null) {
                onError("No file selected.")
                return@addEventListener
            }
            val reader = FileReader()
            reader.onload = {
                try {
                    val text = reader.result as String
                    val glyph = migrateGlyph(json.decodeFromString<Glyph>(text))
                    saveGlyph(name, glyph)
                    onLoaded(glyph)
                } catch (e: Exception) {
                    onError("Import failed: ${e.message}")
                }
            }
            reader.readAsText(file)
        })
        input.click()
    }

    /**
     * Opens a file picker for a single `.ttf`; on selection, reads its raw
     * bytes and invokes [onLoaded]. Parsing/importing those bytes into
     * glyphs is [buildFamilyFromVariableFont]'s job, not this function's --
     * this only handles getting the file's bytes out of the browser.
     */
    fun pickTtfBytes(onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = ".ttf,font/ttf"
        input.addEventListener("change", { _: Event ->
            val file: File? = input.files?.item(0)
            if (file == null) {
                onError("No file selected.")
                return@addEventListener
            }
            val reader = FileReader()
            reader.onload = {
                try {
                    val buffer = reader.result as ArrayBuffer
                    val view = DataView(buffer)
                    val bytes = ByteArray(buffer.byteLength) { view.getUint8(it).toByte() }
                    onLoaded(bytes)
                } catch (e: Exception) {
                    onError("Couldn't read file: ${e.message}")
                }
            }
            reader.readAsArrayBuffer(file)
        })
        input.click()
    }
}
