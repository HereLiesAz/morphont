package com.hereliesaz.morphont

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Desktop persistence: one JSON file per glyph (through [ProjectCodec], the
 * same contract as web and Android) in the OS's per-user app-data folder.
 * Writes go to a temp file and are renamed into place, so a crash mid-save
 * never leaves a half-written glyph.
 */
object DesktopStorage {
    private val dir: File by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.let { File(it, "Morphont") } ?: File(home, "AppData/Roaming/Morphont")
            os.contains("mac") -> File(home, "Library/Application Support/Morphont")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "morphont")
        }
        File(base, "glyphs").apply { mkdirs() }
    }

    private fun fileFor(name: String) = File(dir, URLEncoder.encode(name, Charsets.UTF_8) + ".json")

    private fun nameOf(file: File): String? =
        if (file.name.endsWith(".json")) runCatching { URLDecoder.decode(file.name.removeSuffix(".json"), Charsets.UTF_8) }.getOrNull() else null

    fun listGlyphNames(): List<String> = dir.listFiles().orEmpty().mapNotNull { nameOf(it) }.sorted()

    fun glyphExists(name: String) = fileFor(name).exists()

    fun loadGlyph(name: String): Glyph? = runCatching { ProjectCodec.decodeGlyph(fileFor(name).readText()) }.getOrNull()

    fun saveGlyph(name: String, glyph: Glyph) {
        val target = fileFor(name)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(ProjectCodec.encodeGlyph(glyph))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun saveGlyphs(glyphs: Map<String, Glyph>) = glyphs.forEach { (n, g) -> saveGlyph(n, g) }

    fun exportProjectText(): String = ProjectCodec.encodeProject(listGlyphNames().associateWith { loadGlyph(it) ?: Glyph() })

    /** Replaces the whole project with [text]'s glyphs; returns their names. */
    fun importProjectText(text: String): List<String> {
        val project = ProjectCodec.decodeProject(text)
        dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach { it.delete() }
        saveGlyphs(project)
        return listGlyphNames()
    }

    // ------------------------------------------------------------ dialogs

    private fun dialog(title: String, mode: Int, extensions: List<String>, suggested: String? = null): File? {
        val d = FileDialog(null as Frame?, title, mode)
        if (extensions.isNotEmpty()) d.setFilenameFilter { _, n -> extensions.any { n.lowercase().endsWith(it) } }
        suggested?.let { d.file = it }
        d.isVisible = true
        val name = d.file ?: return null
        return File(d.directory, name)
    }

    fun pickBytes(title: String, extensions: List<String>, onLoaded: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val f = dialog(title, FileDialog.LOAD, extensions) ?: return
        runCatching { f.readBytes() }.onSuccess(onLoaded).onFailure { onError("Couldn't read ${f.name}: ${it.message}") }
    }

    fun pickText(title: String, onLoaded: (String) -> Unit, onError: (String) -> Unit) =
        pickBytes(title, listOf(".json"), { onLoaded(it.decodeToString()) }, onError)

    fun saveText(title: String, suggested: String, text: String, onSaved: () -> Unit, onError: (String) -> Unit) {
        val f = dialog(title, FileDialog.SAVE, listOf(".json"), suggested) ?: return
        runCatching { f.writeText(text) }.onSuccess { onSaved() }.onFailure { onError("Couldn't write ${f.name}: ${it.message}") }
    }
}
