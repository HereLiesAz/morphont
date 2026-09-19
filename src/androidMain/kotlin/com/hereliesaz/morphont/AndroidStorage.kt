package com.hereliesaz.morphont

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.io.File

private const val PREFS_NAME = "morphont"
private const val PROJECT_KEY = "project"
private const val LAST_GLYPH_KEY = "last_glyph"
private const val SELECTED_AXIS_KEY = "selected_axis"
private const val MOBILE_PANE_KEY = "mobile_pane"
private const val PREVIEW_VALUE_PREFIX = "preview_"
private const val GLYPH_DIRECTORY = "morphont-glyphs-v2"
private const val STAGE_DIRECTORY = "morphont-glyphs-stage"
private const val BACKUP_DIRECTORY = "morphont-glyphs-backup"

/**
 * Android persistence for the same JSON glyph/project contract the web build uses.
 *
 * Glyphs are stored one-per-file in app-internal storage. Older builds wrote the
 * entire project into a single SharedPreferences value; that payload is migrated
 * once and removed only after the file-backed copy has been committed.
 */
class AndroidStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyProjectIfNeeded()
    }

    private fun liveDirectory(): File =
        File(appContext.filesDir, GLYPH_DIRECTORY).apply { mkdirs() }

    private fun encodedName(name: String): String =
        Base64.encodeToString(
            name.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun decodedName(encoded: String): String? = try {
        String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun glyphFile(directory: File, name: String): File =
        File(directory, "${encodedName(name)}.json")

    private fun writeGlyphFile(directory: File, name: String, glyph: Glyph) {
        directory.mkdirs()
        val atomic = AtomicFile(glyphFile(directory, name))
        val output = atomic.startWrite()
        try {
            output.write(ProjectCodec.encodeGlyph(glyph).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    private fun readGlyphFile(file: File): Glyph? = try {
        ProjectCodec.decodeGlyph(file.readText(Charsets.UTF_8))
    } catch (_: Throwable) {
        null
    }

    private fun projectSnapshot(): MutableMap<String, Glyph> {
        val directory = liveDirectory()
        val project = mutableMapOf<String, Glyph>()
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.forEach { file ->
                val name = decodedName(file.name.removeSuffix(".json")) ?: return@forEach
                val glyph = readGlyphFile(file) ?: return@forEach
                project[name] = glyph
            }
        return project
    }

    private fun replaceProjectDirectory(project: Map<String, Glyph>) {
        val stage = File(appContext.filesDir, STAGE_DIRECTORY)
        val live = File(appContext.filesDir, GLYPH_DIRECTORY)
        val backup = File(appContext.filesDir, BACKUP_DIRECTORY)

        stage.deleteRecursively()
        backup.deleteRecursively()
        stage.mkdirs()

        try {
            project.forEach { (name, glyph) -> writeGlyphFile(stage, name, glyph) }

            if (live.exists() && !live.renameTo(backup)) {
                error("Could not stage the existing Android project for replacement.")
            }
            if (!stage.renameTo(live)) {
                if (backup.exists()) {
                    live.deleteRecursively()
                    backup.renameTo(live)
                }
                error("Could not activate the imported Android project.")
            }
            backup.deleteRecursively()
        } catch (t: Throwable) {
            stage.deleteRecursively()
            if (!live.exists() && backup.exists()) {
                backup.renameTo(live)
            }
            throw t
        }
    }

    private fun migrateLegacyProjectIfNeeded() {
        val raw = prefs.getString(PROJECT_KEY, null) ?: return
        val project = try {
            ProjectCodec.decodeProject(raw)
        } catch (_: Throwable) {
            // Preserve an unreadable legacy payload instead of deleting the user's only copy.
            return
        }

        replaceProjectDirectory(project)
        prefs.edit().remove(PROJECT_KEY).apply()
    }

    fun listGlyphNames(): List<String> = projectSnapshot().keys.sorted()

    fun loadGlyph(name: String): Glyph? =
        readGlyphFile(glyphFile(liveDirectory(), name))

    fun glyphExists(name: String): Boolean =
        glyphFile(liveDirectory(), name).isFile

    fun lastGlyphName(): String? = prefs.getString(LAST_GLYPH_KEY, null)

    fun setLastGlyphName(name: String?) {
        val edit = prefs.edit()
        if (name == null) edit.remove(LAST_GLYPH_KEY) else edit.putString(LAST_GLYPH_KEY, name)
        edit.apply()
    }

    fun selectedAxisTag(): String? = prefs.getString(SELECTED_AXIS_KEY, null)

    fun setSelectedAxisTag(tag: String) {
        prefs.edit().putString(SELECTED_AXIS_KEY, tag).apply()
    }

    fun mobilePaneName(): String? = prefs.getString(MOBILE_PANE_KEY, null)

    fun setMobilePaneName(name: String) {
        prefs.edit().putString(MOBILE_PANE_KEY, name).apply()
    }

    fun previewValue(tag: String): Float? {
        val key = PREVIEW_VALUE_PREFIX + tag
        return if (prefs.contains(key)) prefs.getFloat(key, 0.5f) else null
    }

    fun setPreviewValues(values: Map<String, Float>) {
        val edit = prefs.edit()
        values.forEach { (tag, value) ->
            edit.putFloat(PREVIEW_VALUE_PREFIX + tag, value.coerceIn(0f, 1f))
        }
        edit.apply()
    }

    fun saveGlyph(name: String, glyph: Glyph) {
        writeGlyphFile(liveDirectory(), name, glyph)
    }

    fun saveGlyphs(glyphs: Map<String, Glyph>) {
        val directory = liveDirectory()
        glyphs.forEach { (name, glyph) -> writeGlyphFile(directory, name, glyph) }
    }

    fun exportProjectText(): String =
        ProjectCodec.encodeProject(projectSnapshot())

    fun importProjectText(text: String): List<String> {
        val project = ProjectCodec.decodeProject(text)
        replaceProjectDirectory(project)
        setLastGlyphName(null)
        return project.keys.sorted()
    }

    fun exportGlyphText(glyph: Glyph): String = ProjectCodec.encodeGlyph(glyph)

    fun importGlyphText(name: String, text: String): Glyph {
        val glyph = ProjectCodec.decodeGlyph(text)
        saveGlyph(name, glyph)
        return glyph
    }
}
