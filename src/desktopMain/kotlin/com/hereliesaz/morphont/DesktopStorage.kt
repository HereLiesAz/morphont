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
 * Windows and macOS file systems are case-insensitive by default, so "A" and
 * "a" would share one file: ASCII capitals are percent-escaped too, which
 * keeps every name distinct after case folding. [migrateLegacyGlyphFileNames]
 * renames files written under the older scheme.
 */
internal fun desktopGlyphFileName(name: String): String {
    val legacy = desktopLegacyGlyphFileName(name)
    val encoded = legacy.removeSuffix(".json")
    val folded = StringBuilder()
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        when {
            c == '%' -> { folded.append(encoded, i, i + 3); i += 3 }
            c in 'A'..'Z' -> { folded.append('%').append(c.code.toString(16).uppercase()); i++ }
            else -> { folded.append(c); i++ }
        }
    }
    return folded.toString() + ".json"
}

/** The filename scheme used before capitals were escaped; kept so unmigrated files stay loadable. */
internal fun desktopLegacyGlyphFileName(name: String): String {
    require(name.isNotEmpty()) { "Glyph name must not be empty" }
    var encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("*", "%2A")
    val base = encoded.substringBefore('.').uppercase()
    if (base in setOf("CON", "PRN", "AUX", "NUL") ||
        Regex("^(COM[1-9]|LPT[1-9])$").matches(base)) {
        encoded = "%" + encoded[0].code.toString(16).padStart(2, '0').uppercase() + encoded.drop(1)
    }
    return encoded + ".json"
}

/** The file to read for [name]: the current name, else the legacy one while its migration has not succeeded. */
internal fun resolveGlyphFile(directory: File, name: String): File {
    val current = File(directory, desktopGlyphFileName(name))
    if (current.exists()) return current
    val legacy = File(directory, desktopLegacyGlyphFileName(name))
    return if (legacy.exists()) legacy else current
}

/** Renames glyph files saved before capitals were escaped to their current names (never overwriting). */
internal fun migrateLegacyGlyphFileNames(directory: File) {
    for (file in directory.listFiles().orEmpty()) {
        val name = desktopGlyphName(file.name) ?: continue
        val canonical = runCatching { desktopGlyphFileName(name) }.getOrNull() ?: continue
        if (canonical == file.name) continue
        val target = File(directory, canonical)
        if (target.exists()) continue
        runCatching { Files.move(file.toPath(), target.toPath()) }
    }
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

/** Splits [names] into the glyphs [load] could read and the names of those it could not. */
internal fun readableGlyphs(names: List<String>, load: (String) -> Glyph?): Pair<MutableMap<String, Glyph>, List<String>> {
    val readable = mutableMapOf<String, Glyph>()
    val skipped = mutableListOf<String>()
    for (name in names) {
        val glyph = load(name)
        if (glyph == null) skipped.add(name) else readable[name] = glyph
    }
    return readable to skipped
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
        migrateLegacyGlyphFileNames(glyphs)
        glyphs
    }

    private fun fileFor(name: String) = File(dir, desktopGlyphFileName(name))

    private fun readableFileFor(name: String) = resolveGlyphFile(dir, name)

    private fun nameOf(file: File): String? = desktopGlyphName(file.name)

    fun listGlyphNames(): List<String> = dir.listFiles().orEmpty().mapNotNull { nameOf(it) }.distinct().sorted()

    fun glyphExists(name: String) = readableFileFor(name).exists()

    fun loadGlyph(name: String): Glyph? = runCatching { ProjectCodec.decodeGlyph(readableFileFor(name).readText()) }.getOrNull()

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

    /**
     * One damaged glyph file must not block the whole-project backup: unreadable glyphs
     * are left out of the export and reported through [onSkipped].
     */
    fun exportProjectText(unsaved: Pair<String, Glyph>? = null, onSkipped: (List<String>) -> Unit = {}): String {
        val (project, skipped) = readableGlyphs(listGlyphNames(), ::loadGlyph)
        if (skipped.isNotEmpty()) onSkipped(skipped)
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
