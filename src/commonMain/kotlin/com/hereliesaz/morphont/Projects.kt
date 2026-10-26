package com.hereliesaz.morphont

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * A folder the person chose, in place of the app's own storage. [tree] is the folder they picked (on Android a
 * document-tree URI), [folder] the document inside it that holds the glyph files, and [label] says where that is
 * in words. Only the shell that made it can open it.
 */
@Serializable
data class ProjectLocation(val tree: String, val folder: String, val label: String)

/**
 * One named body of work: its own set of glyphs, saved continuously. [id] is the storage key; [name] is only a
 * label. [location] is null for a project kept in the app's own storage.
 */
@Serializable
data class ProjectInfo(val id: String, val name: String, val location: ProjectLocation? = null) {
    /** Where the glyphs are kept, for people to read. */
    val locationLabel: String get() = location?.label ?: "App storage"
}

@Serializable
private data class ProjectIndex(
    /** Most recently opened first. */
    val projects: List<ProjectInfo> = emptyList(),
    val lastId: String? = null,
    /** A folder new projects are made in, when the person chose one; its [ProjectLocation.folder] is the parent. */
    val newProjectsIn: ProjectLocation? = null,
)

/**
 * The list of projects, kept as one small JSON document that each platform
 * stores however suits it ([read]/[write]). The glyphs themselves live in
 * per-project storage owned by the platform; this only tracks which projects
 * exist, what they are called, and which was open last.
 */
class ProjectRegistry(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    private var index: ProjectIndex = load()

    private fun load(): ProjectIndex {
        val raw = read() ?: return ProjectIndex()
        return runCatching { json.decodeFromString<ProjectIndex>(raw) }.getOrDefault(ProjectIndex())
    }

    private fun commit(next: ProjectIndex) {
        write(json.encodeToString(ProjectIndex.serializer(), next))
        index = next
    }

    val projects: List<ProjectInfo> get() = index.projects

    /** The project to reopen on launch, if it still exists. */
    val last: ProjectInfo? get() = index.projects.firstOrNull { it.id == index.lastId }

    fun find(id: String): ProjectInfo? = index.projects.firstOrNull { it.id == id }

    /** Why [name] can't be used (blank, or taken by another project), or null if it's fine. */
    fun nameProblem(name: String, exceptId: String? = null): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Type a name first."
            index.projects.any { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) } ->
                "A project named \"$trimmed\" already exists."
            else -> null
        }
    }

    /** Registers a new project and makes it the most recent. [id] is supplied only when adopting storage that already exists. */
    fun create(name: String, id: String = newId()): ProjectInfo {
        val info = ProjectInfo(id, name.trim())
        commit(index.copy(projects = listOf(info) + index.projects.filterNot { it.id == id }, lastId = id))
        return info
    }

    /** Marks [id] as the project in use now: first in the list, and the one to reopen next launch. */
    fun open(id: String): ProjectInfo? {
        val info = find(id) ?: return null
        commit(index.copy(projects = listOf(info) + index.projects.filterNot { it.id == id }, lastId = id))
        return info
    }

    fun rename(id: String, name: String): ProjectInfo? {
        val info = find(id) ?: return null
        val renamed = info.copy(name = name.trim())
        commit(index.copy(projects = index.projects.map { if (it.id == id) renamed else it }))
        return renamed
    }

    fun delete(id: String) {
        commit(index.copy(projects = index.projects.filterNot { it.id == id }, lastId = index.lastId.takeIf { it != id }))
    }

    /** Records where [id]'s glyphs are kept: a folder the person chose, or null for the app's own storage. */
    fun setLocation(id: String, location: ProjectLocation?): ProjectInfo? {
        val info = find(id) ?: return null
        val moved = info.copy(location = location)
        commit(index.copy(projects = index.projects.map { if (it.id == id) moved else it }))
        return moved
    }

    /** The folder new projects are made in, or null when they go in the app's own storage. */
    val newProjectsIn: ProjectLocation? get() = index.newProjectsIn

    fun setNewProjectsIn(location: ProjectLocation?) {
        commit(index.copy(newProjectsIn = location))
    }

    private fun newId(): String {
        while (true) {
            val id = Random.nextLong().toULong().toString(36)
            if (index.projects.none { it.id == id }) return id
        }
    }
}

/** [name], or [name] 2, [name] 3 ... -- the first one no project in [registry] already has. */
fun unusedProjectName(registry: ProjectRegistry, name: String): String {
    if (registry.nameProblem(name) == null) return name
    var n = 2
    while (registry.nameProblem("$name $n") != null) n++
    return "$name $n"
}

/** What the project screens can ask the platform to do. Each returns after the editor and storage have switched. */
class ProjectActions(
    /** Creates, opens and returns true; false (with a status message) if the name is unusable. */
    val create: (String) -> Boolean,
    val open: (String) -> Unit,
    /** True if renamed; false (with a status message) if the name is unusable. */
    val rename: (String, String) -> Boolean,
    val delete: (String) -> Unit,
    /** Opens a project that already lives in a folder the person points at; null on a surface without folders. */
    val openFolder: (() -> Unit)? = null,
)
