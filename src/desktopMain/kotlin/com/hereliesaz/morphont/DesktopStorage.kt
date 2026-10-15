package com.hereliesaz.morphont

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * URLEncoder intentionally leaves '*' unescaped. That character cannot be
 * used in a Windows filename; nor can bare DOS device names, even with .json.
 * Encode only the necessary cases so existing stored glyph filenames remain
 * readable without migration or renaming.
 */
internal fun desktopGlyphFileName(name: String): String {
    require(name.isNotEmpty()) { "Glyph name must not be empty" }
    var encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("*", "%2A")
    val base = encoded.substringBefore('.').uppercase()
    if (base in setOf("CON", "PRN", "AUX", "NUL") ||
        Regex("^(COM[1-9]|LPT[1-9])$").matches(base)) {
        encoded = "%" + encoded[0].code.toString(16).padStart(2, '0').uppercase() + encoded.drop(1)
    }
    return encoded + ".json"
}

internal fun desktopGlyphName(fileName: String): String? =
    if (fileName.endsWith(".json")) runCatching {
        URLDecoder.decode(fileName.removeSuffix(".json"), Charsets.UTF_8)
    }.getOrNull() else null

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

    private fun fileFor(name: String) = File(dir, desktopGlyphFileName(name))

    private fun nameOf(file: File): String? = desktopGlyphName(file.name)

    fun listGlyphNames(): List<String> = dir.listFiles().orEmpty().mapNotNull { nameOf(it) }.sorted()

    fun glyphExists(name: String) = fileFor(name).exists()

    fun loadGlyph(name: String): Glyph? = runCatching { ProjectCodec.decodeGlyph(fileFor(name).readText()) }.getOrNull()

    fun saveGlyph(name: String, glyph: Glyph) {
        val target = fileFor(name)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(ProjectCodec.encodeGlyph(glyph))
        try {
            Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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

    /**
     * Select either a .ufo directory or a .ufoz ZIP; JFileChooser can select
     * directories on Windows, unlike the native FileDialog LOAD picker.
     */
    fun pickUfo(onLoaded: (File) -> Unit, onError: (String) -> Unit) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Import UFO package"
            fileSelectionMode = javax.swing.JFileChooser.FILES_AND_DIRECTORIES
            fileFilter = object : javax.swing.filechooser.FileFilter() {
                override fun accept(file: File): Boolean =
                    file.isDirectory || file.name.endsWith(".ufoz", ignoreCase = true)
                override fun getDescription() = "UFO folders (*.ufo) and zipped UFO (*.ufoz)"
            }
        }
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val chosen = chooser.selectedFile ?: return
        if (!(chosen.isDirectory && chosen.name.endsWith(".ufo", true)) &&
            !(chosen.isFile && chosen.name.endsWith(".ufoz", true))) {
            onError("Select a .ufo folder or .ufoz archive.")
            return
        }
        onLoaded(chosen)
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
