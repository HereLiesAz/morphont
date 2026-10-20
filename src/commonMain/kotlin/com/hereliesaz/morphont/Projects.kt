package com.hereliesaz.morphont

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** One named body of work: its own set of glyphs, saved continuously. [id] is the storage key; [name] is only a label. */
@Serializable
data class ProjectInfo(val id: String, val name: String)

@Serializable
private data class ProjectIndex(
    /** Most recently opened first. */
    val projects: List<ProjectInfo> = emptyList(),
    val lastId: String? = null,
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
        commit(ProjectIndex(listOf(info) + index.projects.filterNot { it.id == id }, id))
        return info
    }

    /** Marks [id] as the project in use now: first in the list, and the one to reopen next launch. */
    fun open(id: String): ProjectInfo? {
        val info = find(id) ?: return null
        commit(ProjectIndex(listOf(info) + index.projects.filterNot { it.id == id }, id))
        return info
    }

    fun rename(id: String, name: String): ProjectInfo? {
        val info = find(id) ?: return null
        val renamed = info.copy(name = name.trim())
        commit(index.copy(projects = index.projects.map { if (it.id == id) renamed else it }))
        return renamed
    }

    fun delete(id: String) {
        commit(ProjectIndex(index.projects.filterNot { it.id == id }, index.lastId.takeIf { it != id }))
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
)
