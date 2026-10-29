package com.hereliesaz.morphont

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import kotlinx.serialization.json.jsonObject

private const val PREFS_NAME = "morphont"
private const val PROJECT_KEY = "project"
private const val PROJECT_INDEX_KEY = "project_index"
private const val SETTINGS_KEY = "settings"
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
 * Each project is a folder of one-file-per-glyph JSON, by default in app-internal storage, or in a folder the
 * person chose ([ProjectInfo.location], a document tree; see [GlyphFolder]). [registry] tracks their names and
 * where they are. Older builds kept everything in a single folder, or before that in one SharedPreferences
 * value; both are migrated once into a project called "Untitled", and removed only after the file-backed copy
 * has been committed.
 */
class AndroidStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val projectsRoot = File(appContext.filesDir, PROJECTS_DIRECTORY)

    val registry = ProjectRegistry(
        read = { prefs.getString(PROJECT_INDEX_KEY, null) },
        write = { prefs.edit().putString(PROJECT_INDEX_KEY, it).apply() },
        keepDamaged = { prefs.edit().putString("$PROJECT_INDEX_KEY.damaged", it).apply() },
    )

    /** Theme and view settings, kept with the rest of the app's preferences. */
    val settings = SettingsStore(
        read = { prefs.getString(SETTINGS_KEY, null) },
        write = { prefs.edit().putString(SETTINGS_KEY, it).apply() },
    )

    private var projectId: String? = null

    /** Where the open project's glyphs are; null exactly when [projectId] is. */
    private var folder: GlyphFolder? = null

    /** While a project's files are being copied somewhere else: the glyphs saved or deleted meanwhile, to copy again at the end. */
    private class Move(val touched: MutableSet<String> = HashSet())

    private var moving: Move? = null

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
     * since [folderFor] would otherwise just create a fresh, empty one.
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

    /** The glyph folder of [info]: its chosen folder when it has one, else its folder in app storage (made if need be). */
    private fun folderFor(info: ProjectInfo): GlyphFolder {
        val where = info.location
            ?: return FileGlyphFolder(File(projectsRoot, info.id)).also { it.dir.mkdirs() }
        return TreeGlyphFolder(appContext.contentResolver, Uri.parse(where.tree), where.folder)
    }

    /**
     * Why project [id] can't be opened, or null if it can: a project kept in a folder of the person's choosing
     * stops opening when the folder is deleted or the app's access to it is taken back.
     */
    fun openProblem(id: String): String? {
        val info = registry.find(id) ?: return "There is no such project."
        val where = info.location ?: return null
        return if (folderFor(info).isPresent()) null
        else "Morphont can't reach \"${info.name}\" in ${where.label}: the folder is gone, or access to it was taken back. " +
            "If it moved, use \"Open a project from a folder\" to pick it again, then remove this entry."
    }

    /** Points every glyph operation at project [id], creating its folder if needed. */
    fun useProject(id: String) {
        val next = folderFor(checkNotNull(registry.find(id)) { "There is no such project." })
        // Switched under the same lock [saveNewGlyphs] checks it with.
        synchronized(this) { projectId = id; folder = next; lastNames = emptyList() }
    }

    /**
     * Forgets project [id]'s glyphs. A project in the app's own storage loses its files; one in a folder of the
     * person's choosing keeps them, since that folder is theirs.
     */
    fun deleteProjectFiles(id: String) {
        val info = registry.find(id)
        // Under the lock every write takes, so no import or save is mid-write while the folder goes.
        synchronized(this) {
            if (projectId == id) { projectId = null; folder = null }
            if (info?.location == null) File(projectsRoot, id).deleteRecursively()
            committed.keys.removeAll { it.startsWith("$id/") }
        }
        prefs.edit().remove(LAST_GLYPH_KEY + "_" + id).apply()
    }

    private val tickets = java.util.concurrent.atomic.AtomicLong(0)

    /** The newest save of each glyph that has reached disk, by ticket, keyed "project/glyph". */
    private val committed = HashMap<String, Long>()

    /**
     * A number for a snapshot of the open glyph, taken when the snapshot is: later snapshots get larger
     * ones. [saveGlyph] refuses to write a snapshot older than one already on disk, so a slow background
     * save can never land on top of a newer one (the stop-time flush, say).
     */
    fun nextTicket(): Long = tickets.incrementAndGet()

    private fun activeFolder(): GlyphFolder = checkNotNull(folder) { "No project is open." }

    private fun glyphBytes(glyph: Glyph): ByteArray = ProjectCodec.encodeGlyphCompact(glyph).toByteArray(Charsets.UTF_8)

    private fun readGlyph(from: GlyphFolder, name: String): Glyph? = try {
        from.read(name)?.let { ProjectCodec.decodeGlyph(it.toString(Charsets.UTF_8)) }
    } catch (_: Throwable) {
        null
    }

    private fun projectSnapshot(): MutableMap<String, Glyph> {
        val from = activeFolder()
        val project = mutableMapOf<String, Glyph>()
        for (name in from.names()) project[name] = readGlyph(from, name) ?: continue
        return project
    }

    private fun replaceProjectDirectory(project: Map<String, Glyph>, live: File) {
        val stage = File(projectsRoot, STAGE_DIRECTORY)
        val backup = File(projectsRoot, BACKUP_PREFIX + live.name)

        stage.deleteRecursively()
        backup.deleteRecursively()
        stage.mkdirs()

        try {
            val staged = FileGlyphFolder(stage)
            project.forEach { (name, glyph) -> staged.write(name, glyphBytes(glyph)) }

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

    /**
     * The open project's glyph names, from the file names alone -- cheap enough for the main thread even
     * with a whole font imported, and a damaged file still shows up (opening it says it can't be read)
     * instead of silently vanishing from the list.
     */
    fun listGlyphNames(): List<String> {
        val from = folder ?: return emptyList()
        // A folder the person chose can stop answering (deleted, access taken back, provider gone); the editor asks
        // for this list while it draws, so it gets the last list that worked rather than an exception.
        return try {
            from.names().sorted().also { lastNames = it }
        } catch (_: Exception) {
            lastNames
        }
    }

    private var lastNames: List<String> = emptyList()

    fun loadGlyph(name: String): Glyph? = folder?.let { readGlyph(it, name) }

    /** False when the folder can't say; whatever then tries to save the glyph meets the real error. */
    fun glyphExists(name: String): Boolean = try { folder?.exists(name) == true } catch (_: Exception) { false }

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
    fun saveGlyph(name: String, glyph: Glyph, forProject: String? = null, ticket: Long = nextTicket()) {
        synchronized(this) {
            if (forProject != null && forProject != projectId) return
            val key = "${checkNotNull(projectId) { "No project is open." }}/$name"
            if ((committed[key] ?: 0L) > ticket) return // a newer snapshot is already saved
            committed[key] = ticket
            activeFolder().write(name, glyphBytes(glyph))
            moving?.touched?.add(name)
        }
    }

    /** Removes a glyph's file from the open project. */
    fun deleteGlyph(name: String) = synchronized(this) {
        val id = projectId ?: return@synchronized
        val from = activeFolder()
        check(from.delete(name)) { "Couldn't delete \"$name\"." }
        // A snapshot older than this delete mustn't bring the glyph back.
        committed["$id/$name"] = tickets.incrementAndGet()
        moving?.touched?.add(name)
    }

    /**
     * An imported font never silently overwrites an existing project glyph. [forProject] is the project
     * the import started in; if another was opened meanwhile nothing is written. Every glyph is encoded
     * before the first is written, so an unencodable one aborts cleanly instead of half saving.
     */
    fun saveNewGlyphs(glyphs: Map<String, Glyph>, forProject: String? = null): List<String> {
        val encoded = glyphs.mapValues { glyphBytes(it.value) } // an unencodable glyph aborts here, before anything is written
        val id = synchronized(this) {
            check(forProject == null || forProject == projectId) { "You opened another project while it was importing, so nothing was saved. Import it again." }
            checkNotNull(projectId) { "No project is open." }
        }
        val existing = ArrayList<String>()
        for ((name, bytes) in encoded) {
            // Each glyph is checked and written under the lock a move takes, against the folder the project is in
            // at that moment: a move that starts mid-import carries what is already written and this carries the rest.
            synchronized(this) {
                check(projectId == id) { "The project was closed or deleted while it was importing." }
                val target = activeFolder()
                check(target.isPresent()) { "The project's folder is gone." }
                if (target.exists(name)) existing.add(name)
                else {
                    target.write(name, bytes)
                    moving?.touched?.add(name)
                }
            }
        }
        return existing
    }

    /**
     * Every glyph of the open project that reads, with [unsaved] (the one being edited) in place of its stored copy,
     * and the names of glyph files too damaged to include.
     */
    fun readProject(unsaved: Pair<String, Glyph>? = null): Pair<Map<String, Glyph>, List<String>> {
        val project = if (projectId == null) mutableMapOf() else projectSnapshot().toMutableMap()
        val skipped = listGlyphNames().filter { it !in project && it != unsaved?.first }
        unsaved?.let { (name, glyph) -> project[name] = glyph }
        return project to skipped
    }

    /** The whole project as text; [onSkipped] gets the names of glyph files too damaged to include. */
    fun exportProjectText(unsaved: Pair<String, Glyph>? = null, onSkipped: (List<String>) -> Unit = {}): String {
        val (project, skipped) = readProject(unsaved)
        if (skipped.isNotEmpty()) onSkipped(skipped)
        return ProjectCodec.encodeProject(project)
    }

    /** Makes the open project hold exactly [project]'s glyphs. */
    private fun replaceAll(project: Map<String, Glyph>) {
        val target = synchronized(this) { activeFolder() }
        if (target is FileGlyphFolder) { replaceProjectDirectory(project, target.dir); return }
        // A folder the person chose can't be swapped as a whole: write every glyph, then remove the ones the new project lacks.
        val encoded = project.mapValues { glyphBytes(it.value) }
        synchronized(this) {
            for ((name, bytes) in encoded) target.write(name, bytes)
            for (name in target.names()) if (name !in project) target.delete(name)
        }
    }

    // ------------------------------------------------------------ projects in folders the person chose

    private val resolver get() = appContext.contentResolver

    /** What to call the folder [tree] points at. */
    private fun treeLabel(tree: Uri): String =
        DocumentTree.displayName(resolver, tree, DocumentsContract.getTreeDocumentId(tree)) ?: "the chosen folder"

    /** Copies [names] from [from] to [to], reading each back to be sure it arrived whole. */
    private fun copyGlyphs(from: GlyphFolder, to: GlyphFolder, names: Collection<String> = from.names()) {
        for (name in names) {
            // A glyph deleted since the list was made is simply not copied; one that is there but unreadable stops the move.
            val bytes = from.read(name) ?: if (from.exists(name)) throw java.io.IOException("Couldn't read \"$name\" to copy it.") else continue
            to.write(name, bytes)
            val back = to.read(name)
            if (back == null || !back.contentEquals(bytes)) throw java.io.IOException("\"$name\" didn't copy cleanly.")
        }
    }

    /** Starts a move of project [id]: only one runs at a time, since two would each treat the other's changes as deletions. */
    private fun beginMove(id: String, info: ProjectInfo): GlyphFolder = synchronized(this) {
        check(moving == null) { "Another move is still running. Wait for it to finish." }
        moving = Move()
        if (projectId == id) activeFolder() else folderFor(info)
    }

    /**
     * Copies what was saved or deleted in [source] while [Move] was running into [dest], then runs [switch], all
     * under the lock saves take, so no later save can land in [source] and be lost.
     */
    private fun finishMove(source: GlyphFolder, dest: GlyphFolder, switch: () -> Unit) {
        synchronized(this) {
            for (name in moving?.touched.orEmpty()) {
                val bytes = if (source.exists(name)) source.read(name) else null
                if (bytes != null) dest.write(name, bytes) else dest.delete(name)
            }
            switch()
            moving = null
        }
    }

    /**
     * Moves project [id] into a new folder named for it inside [tree], a folder the person picked, and makes
     * [tree] the place new projects start. The project keeps working throughout: saves made while it copies are
     * copied again at the end. Its copy in app storage goes only after the new one has been read back whole.
     */
    fun moveProjectToFolder(id: String, tree: Uri): ProjectLocation {
        val info = checkNotNull(registry.find(id)) { "There is no such project." }
        val source = beginMove(id, info)
        var madeFolder: String? = null
        try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val rootLabel = treeLabel(tree)
            val (folderId, folderName) = DocumentTree.createFolder(resolver, tree, parentId, info.name)
            madeFolder = folderId
            val dest = TreeGlyphFolder(resolver, tree, folderId)
            val where = ProjectLocation(tree.toString(), folderId, "$rootLabel/$folderName")
            copyGlyphs(source, dest)
            finishMove(source, dest) {
                registry.setLocation(id, where)
                registry.setNewProjectsIn(ProjectLocation(tree.toString(), parentId, rootLabel))
                if (projectId == id) folder = dest
            }
            // The registry must be on disk before the app's copy goes, or a kill in between would point it at files that are gone.
            prefs.edit().commit()
            if (info.location == null) File(projectsRoot, id).deleteRecursively()
            releaseUnusedFolders()
            return where
        } catch (t: Throwable) {
            synchronized(this) { moving = null }
            madeFolder?.let { runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) } }
            releaseUnusedFolders()
            throw t
        }
    }

    /** Brings project [id] back into app storage. The folder it came from is left as it is, a copy of everything. */
    fun moveProjectToAppStorage(id: String) {
        val info = checkNotNull(registry.find(id)) { "There is no such project." }
        check(info.location != null) { "\"${info.name}\" is already in app storage." }
        val target = File(projectsRoot, id)
        target.deleteRecursively()
        target.mkdirs()
        val dest = FileGlyphFolder(target)
        val source = beginMove(id, info)
        try {
            copyGlyphs(source, dest)
            finishMove(source, dest) {
                registry.setLocation(id, null)
                if (projectId == id) folder = dest
            }
        } catch (t: Throwable) {
            synchronized(this) { moving = null }
            target.deleteRecursively()
            throw t
        }
        releaseUnusedFolders()
    }

    /**
     * Registers the project already living in [tree] (the folder itself is the project), or finds the entry that
     * already points there. An empty folder becomes an empty project.
     */
    fun adoptFolder(tree: Uri): ProjectInfo {
        val folderId = DocumentsContract.getTreeDocumentId(tree)
        // The same folder may have been picked as a tree of its own or made as a subfolder of one we hold: its document id is the same either way.
        registry.projects.firstOrNull { it.location?.let { l -> l.folder == folderId && Uri.parse(l.tree).authority == tree.authority } == true }?.let { return it }
        val where = TreeGlyphFolder(resolver, tree, folderId)
        check(where.isPresent()) { "Morphont can't read that folder." }
        // A glyph file is JSON with a "corners" object. Anything else with a .json name (notes, settings) isn't ours, and
        // opening the folder would list it as a glyph and let a save or a delete overwrite the person's file.
        val strangers = where.names().filter { name ->
            val text = where.read(name)?.toString(Charsets.UTF_8)
            text == null || runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject.containsKey("corners") }.getOrDefault(false).not()
        }
        check(strangers.isEmpty()) {
            "That folder holds ${strangers.size} .json file(s) that aren't Morphont glyphs (${strangers.take(3).joinToString { "$it.json" }}). " +
                "Pick the project's own folder, or an empty one."
        }
        val label = treeLabel(tree)
        val info = registry.create(unusedProjectName(registry, label))
        return checkNotNull(registry.setLocation(info.id, ProjectLocation(tree.toString(), folderId, label)))
    }

    /** The folder new projects are made in, if the person chose one. */
    val newProjectsFolder: ProjectLocation? get() = registry.newProjectsIn

    fun useAppStorageForNewProjects() {
        registry.setNewProjectsIn(null)
        releaseUnusedFolders()
    }

    /**
     * Gives back Android's standing permission for every folder no project and no "new projects" setting uses any
     * more: after a project is removed or moved home, a move that failed, a setting undone. The system keeps only a
     * limited number of these grants and drops the oldest, which would silently cut off a project still in use.
     */
    fun releaseUnusedFolders() {
        val inUse = registry.projects.mapNotNull { it.location?.tree }.toSet() + listOfNotNull(registry.newProjectsIn?.tree)
        for (grant in resolver.persistedUriPermissions) {
            if (grant.uri.toString() in inUse) continue
            runCatching {
                resolver.releasePersistableUriPermission(grant.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    /**
     * Gives the just-made project [info] a folder of its own inside the chosen one for new projects. Null when
     * it worked or there is no such folder; otherwise why it didn't (the project stays in app storage).
     */
    fun placeInNewProjectsFolder(info: ProjectInfo): String? {
        val home = registry.newProjectsIn ?: return null
        return try {
            val (folderId, folderName) = DocumentTree.createFolder(resolver, Uri.parse(home.tree), home.folder, info.name)
            registry.setLocation(info.id, ProjectLocation(home.tree, folderId, "${home.label}/$folderName"))
            null
        } catch (t: Throwable) {
            "Couldn't start it in ${home.label} (${t.message ?: t::class.simpleName}); it's in app storage instead."
        }
    }

    fun importProjectText(text: String): List<String> {
        val project = ProjectCodec.decodeProject(text)
        replaceAll(project)
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
