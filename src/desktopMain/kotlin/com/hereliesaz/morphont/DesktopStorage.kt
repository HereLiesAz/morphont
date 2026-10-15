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
 * Transactional directory swap, injectable for temp-directory regression tests.
 * Failure while encoding/staging leaves the previous project untouched.
 */
internal fun replaceProjectDirectory(directory: File, project: Map<String, Glyph>): List<String> {
    require(directory.isDirectory || directory.mkdirs()) { "Cannot create glyph storage directory" }
    val parent = directory.parentFile.toPath()
    val stage = Files.createTempDirectory(parent, "morphont-stage-")
    val backup = Files.createTempDirectory(parent, "morphont-backup-")
    Files.delete(backup)
    var backedUp = false
    var installed = false
    try {
        project.forEach { (name, glyph) ->
            Files.writeString(stage.resolve(desktopGlyphFileName(name)), ProjectCodec.encodeGlyph(glyph))
        }
        Files.move(directory.toPath(), backup)
        backedUp = true
        try {
            Files.move(stage, directory.toPath())
            installed = true
        } catch (failure: Exception) {
            Files.move(backup, directory.toPath())
            backedUp = false
            throw failure
        }
        return project.keys.sorted()
    } finally {
        if (stage.toFile().exists()) stage.toFile().deleteRecursively()
        if (installed && backedUp && backup.toFile().exists()) backup.toFile().deleteRecursively()
    }
}

/** Crash recovery for a directory import interrupted between its two renames. */
internal fun recoverProjectDirectory(base: File, glyphs: File) {
    if (glyphs.exists()) return
    val backup = base.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("morphont-backup-") }
        .maxByOrNull { it.lastModified() }
    if (backup != null) Files.move(backup.toPath(), glyphs.toPath())
}

/** Merge the active working glyph into an export without writing it to storage. */
internal fun projectForExport(saved: Map<String, Glyph>, unsaved: Pair<String, Glyph>?): Map<String, Glyph> =
    saved.toMutableMap().apply {
        unsaved?.let { (name, glyph) -> put(name, glyph) }
    }

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
        val glyphs = File(base, "glyphs")
        // Recover the previous project if the process died between the two
        // directory renames of a project import.
        recoverProjectDirectory(base, glyphs)
        glyphs.apply { mkdirs() }
    }

    private fun fileFor(name: String) = File(dir, desktopGlyphFileName(name))

    private fun nameOf(file: File): String? = desktopGlyphName(file.name)

    fun listGlyphNames(): List<String> = dir.listFiles().orEmpty().mapNotNull { nameOf(it) }.sorted()

    fun glyphExists(name: String) = fileFor(name).exists()

    fun loadGlyph(name: String): Glyph? = runCatching { ProjectCodec.decodeGlyph(fileFor(name).readText()) }.getOrNull()

    fun saveGlyph(name: String, glyph: Glyph) {
        val target = fileFor(name)
        // Unique temp files make simultaneous imports/autosaves safe, unlike
        // the shared <glyph>.json.tmp filename.
        val temp = Files.createTempFile(dir.toPath(), "morphont-glyph-", ".tmp")
        try {
            Files.writeString(temp, ProjectCodec.encodeGlyph(glyph))
            try {
                Files.move(temp, target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Never overwrite an existing glyph when importing another font. */
    fun saveNewGlyphs(glyphs: Map<String, Glyph>): List<String> {
        val collisions = glyphs.keys.filter(::glyphExists)
        glyphs.forEach { (name, glyph) ->
            if (name !in collisions) saveGlyph(name, glyph)
        }
        return collisions
    }

    fun exportProjectText(unsaved: Pair<String, Glyph>? = null): String {
        val project = listGlyphNames().associateWith { name ->
            loadGlyph(name) ?: error("Cannot export unreadable glyph: $name")
        }.toMutableMap()
        return ProjectCodec.encodeProject(projectForExport(project, unsaved))
    }

    /** Decoding finishes before the live directory is touched. */
    fun importProjectText(text: String): List<String> =
        replaceProjectDirectory(dir, ProjectCodec.decodeProject(text))

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
