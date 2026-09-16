package com.hereliesaz.morphont

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader

/** Global JS `encodeURIComponent`, used to build a `data:` URL for export. */
external fun encodeURIComponent(str: String): String

private const val STORAGE_KEY = "morphont:project"

private fun readProject(): MutableMap<String, Glyph> {
    val raw = window.localStorage.getItem(STORAGE_KEY) ?: return mutableMapOf()
    return try {
        ProjectCodec.decodeProject(raw)
    } catch (_: Exception) {
        mutableMapOf()
    }
}

private fun writeProject(project: Map<String, Glyph>) {
    window.localStorage.setItem(STORAGE_KEY, ProjectCodec.encodeProject(project))
}

object Storage {
    fun listGlyphNames(): List<String> = readProject().keys.sorted()

    fun loadGlyph(name: String): Glyph? = readProject()[name]

    fun saveGlyph(name: String, glyph: Glyph) {
        val project = readProject()
        project[name] = glyph
        writeProject(project)
    }

    fun saveGlyphs(glyphs: Map<String, Glyph>) {
        val project = readProject()
        project.putAll(glyphs)
        writeProject(project)
    }

    fun glyphExists(name: String): Boolean = readProject().containsKey(name)

    fun exportProject() {
        downloadText("morphont-project.json", ProjectCodec.encodeProject(readProject()))
    }

    fun importProject(onLoaded: (List<String>) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json") { text ->
            try {
                val project = ProjectCodec.decodeProject(text)
                writeProject(project)
                onLoaded(project.keys.sorted())
            } catch (e: Exception) {
                onError("Import failed: ${e.message}")
            }
        }
    }

    fun exportGlyph(name: String, glyph: Glyph) {
        downloadText("$name.morphont.json", ProjectCodec.encodeGlyph(glyph))
    }

    fun importGlyph(name: String, onLoaded: (Glyph) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json") { text ->
            try {
                val glyph = ProjectCodec.decodeGlyph(text)
                saveGlyph(name, glyph)
                onLoaded(glyph)
            } catch (e: Exception) {
                onError("Import failed: ${e.message}")
            }
        }
    }

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

    private fun downloadText(filename: String, text: String) {
        val href = "data:application/json;charset=utf-8," + encodeURIComponent(text)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = href
        anchor.download = filename
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
    }

    private fun pickTextFile(accept: String, onLoaded: (String) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = accept
        input.addEventListener("change", { _: Event ->
            val file: File? = input.files?.item(0) ?: return@addEventListener
            val reader = FileReader()
            reader.onload = { onLoaded(reader.result as String) }
            reader.readAsText(file)
        })
        input.click()
    }
}
