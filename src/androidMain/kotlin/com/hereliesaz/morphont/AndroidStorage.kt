package com.hereliesaz.morphont

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.io.File

private const val PREFS_NAME = "morphont"
private const val PROJECT_KEY = "project"
private const val PROJECT_INDEX_KEY = "project_index"
private const val LAST_GLYPH_KEY = "last_glyph"
private const val SELECTED_AXIS_KEY = "selected_axis"
private const val MOBILE_PANE_KEY = "mobile_pane"
private const val PREVIEW_VALUE_PREFIX = "preview_"
/** Where glyphs lived before projects: one folder for everything. It becomes the project "Untitled". */
private const val LEGACY_GLYPH_DIRECTORY = "morphont-glyphs-v2"
private const val PROJECTS_DIRECTORY = "morphont-projects"
private const val LEGACY_PROJECT_ID = "legacy"
private const val STAGE_DIRECTORY = ".stage"
private const val BACKUP_PREFIX = ".backup-"

/**
 * Android persistence for the same JSON glyph/project contract the web build uses.
 *
 * Each project is a folder of one-file-per-glyph JSON in app-internal storage;
 * [registry] tracks their names. Older builds kept everything in a single
 * folder, or before that in one SharedPreferences value; both are migrated
 * once into a project called "Untitled", and removed only after the
 * file-backed copy has been committed.
 */
class AndroidStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val projectsRoot = File(appContext.filesDir, PROJECTS_DIRECTORY)

    val registry = ProjectRegistry(
        read = { prefs.getString(PROJECT_INDEX_KEY, null) },
        write = { prefs.edit().putString(PROJECT_INDEX_KEY, it).apply() },
    )

    private var projectId: String? = null

    init {
        projectsRoot.mkdirs()
        recoverStrandedDirectorySwapsIfNeeded()
        migrateLegacyProjectIfNeeded()
        adoptStoredProjects()
    }

    /**
     * [replaceProjectDirectory] swaps directories with two renames that are not atomic
     * together. If the process died between them, a project's folder can be missing while
     * its glyphs sit under a [BACKUP_PREFIX] folder -- restore it before anything else runs,
     * since [liveDirectory] would otherwise just create a fresh, empty one.
     */
    private fun recoverStrandedDirectorySwapsIfNeeded() {
        projectsRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(BACKUP_PREFIX) }
            ?.forEach { backup ->
                val live = File(projectsRoot, backup.name.removePrefix(BACKUP_PREFIX))
                if (!live.exists()) backup.renameTo(live)
            }
        File(projectsRoot, STAGE_DIRECTORY).deleteRecursively()
    }

    /**
     * Brings the folders and [registry] into agreement: the pre-projects folder becomes
     * the project "Untitled", and any project folder the registry has lost track of is
     * registered rather than left orphaned.
     */
    private fun adoptStoredProjects() {
        val legacy = File(appContext.filesDir, LEGACY_GLYPH_DIRECTORY)
        val legacyTarget = File(projectsRoot, LEGACY_PROJECT_ID)
        if (legacy.isDirectory && !legacyTarget.exists() && !legacy.renameTo(legacyTarget)) {
            legacy.copyRecursively(legacyTarget, overwrite = true)
            legacy.deleteRecursively()
        }
        for (folder in projectsRoot.listFiles().orEmpty()) {
            if (!folder.isDirectory || folder.name.startsWith(".")) continue
            if (registry.find(folder.name) != null || folder.listFiles().isNullOrEmpty()) continue
            val label = if (folder.name == LEGACY_PROJECT_ID) "Untitled" else "Recovered ${folder.name}"
            registry.create(unusedProjectName(registry, label), folder.name)
        }
    }

    /** Points every glyph operation at project [id], creating its folder if needed. */
    fun useProject(id: String) {
        projectId = id
        liveDirectory()
    }

    /** Removes a project's glyphs from disk. Closes it first if it is the open one. */
    fun deleteProjectFiles(id: String) {
        if (projectId == id) projectId = null
        File(projectsRoot, id).deleteRecursively()
        prefs.edit().remove(LAST_GLYPH_KEY + "_" + id).apply()
    }

    private fun liveDirectory(): File =
        File(projectsRoot, checkNotNull(projectId) { "No project is open." }).apply { mkdirs() }

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

    private fun replaceProjectDirectory(project: Map<String, Glyph>, live: File) {
        val stage = File(projectsRoot, STAGE_DIRECTORY)
        val backup = File(projectsRoot, BACKUP_PREFIX + live.name)

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

        // Lands in the legacy project folder; adoptStoredProjects registers it as "Untitled".
        replaceProjectDirectory(project, File(projectsRoot, LEGACY_PROJECT_ID))
        prefs.edit().remove(PROJECT_KEY).apply()
    }

    fun listGlyphNames(): List<String> = if (projectId == null) emptyList() else projectSnapshot().keys.sorted()

    fun loadGlyph(name: String): Glyph? =
        if (projectId == null) null else readGlyphFile(glyphFile(liveDirectory(), name))

    fun glyphExists(name: String): Boolean =
        projectId != null && glyphFile(liveDirectory(), name).isFile

    /** The glyph that was open when the current project was last used. */
    fun lastGlyphName(): String? = projectId?.let { prefs.getString(LAST_GLYPH_KEY + "_" + it, null) }

    fun setLastGlyphName(name: String?) {
        val id = projectId ?: return
        val edit = prefs.edit()
        if (name == null) edit.remove(LAST_GLYPH_KEY + "_" + id) else edit.putString(LAST_GLYPH_KEY + "_" + id, name)
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

    /**
     * [forProject], when given, is the project the edit was made in: a save that finishes after
     * another project was opened is dropped (the switch already saved that glyph) instead of
     * landing in the wrong project.
     */
    fun saveGlyph(name: String, glyph: Glyph, forProject: String? = null) {
        if (forProject != null && forProject != projectId) return
        writeGlyphFile(liveDirectory(), name, glyph)
    }

    /** An imported font never silently overwrites an existing project glyph. */
    fun saveNewGlyphs(glyphs: Map<String, Glyph>): List<String> {
        val directory = liveDirectory()
        val existing = glyphs.keys.filter(::glyphExists)
        for ((name, glyph) in glyphs) {
            if (name !in existing) writeGlyphFile(directory, name, glyph)
        }
        return existing
    }

    fun exportProjectText(unsaved: Pair<String, Glyph>? = null): String {
        val project = if (projectId == null) mutableMapOf() else projectSnapshot().toMutableMap()
        unsaved?.let { (name, glyph) -> project[name] = glyph }
        return ProjectCodec.encodeProject(project)
    }

    fun importProjectText(text: String): List<String> {
        val project = ProjectCodec.decodeProject(text)
        replaceProjectDirectory(project, liveDirectory())
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
