@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hereliesaz.morphont

import com.juul.indexeddb.Database
import com.juul.indexeddb.Key
import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.deleteDatabase
import com.juul.indexeddb.openDatabase
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.dom.url.URL
import org.w3c.files.FileReader
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.js.toList

/** Global JS `encodeURIComponent`. */
external fun encodeURIComponent(str: String): String

/** The names of this site's IndexedDB databases; an empty list where the browser can't enumerate them. */
private fun databaseNames(): kotlin.js.Promise<JsArray<JsString>> =
    js("(globalThis.indexedDB && indexedDB.databases) ? indexedDB.databases().then(function (l) { return l.map(function (d) { return d.name || ''; }); }) : Promise.resolve([])")

/**
 * Every project is its own IndexedDB database. The one browsers had before projects
 * existed is named plain "morphont"; it keeps that name and becomes the project "Untitled".
 */
private const val LEGACY_PROJECT_ID = "legacy"
private const val PROJECT_REGISTRY_KEY = "morphont:projects"
private const val DATABASE_VERSION = 1

private fun databaseName(projectId: String) =
    if (projectId == LEGACY_PROJECT_ID) "morphont" else "morphont-$projectId"
private const val GLYPH_STORE = "glyphs"
private const val LEGACY_STORAGE_KEY = "morphont:project"

private external interface StoredGlyph : JsAny {
    var name: String
    var json: String
}

private fun emptyStoredGlyph(): StoredGlyph = js("({})")

private fun storedGlyph(name: String, glyph: Glyph): StoredGlyph {
    val record = emptyStoredGlyph()
    record.name = name
    record.json = ProjectCodec.encodeGlyph(glyph)
    return record
}

private fun readLegacyProject(): MutableMap<String, Glyph> {
    val raw = try {
        window.localStorage.getItem(LEGACY_STORAGE_KEY)
    } catch (_: Throwable) {
        null
    } ?: return mutableMapOf()

    return try {
        ProjectCodec.decodeProject(raw)
    } catch (_: Throwable) {
        mutableMapOf()
    }
}

private fun clearLegacyProject() {
    try {
        window.localStorage.removeItem(LEGACY_STORAGE_KEY)
    } catch (_: Throwable) {
        // IndexedDB is authoritative. Browsers that block localStorage cleanup should
        // not prevent the editor from using the durable IndexedDB copy.
    }
}

object Storage {
    private val scope = MainScope()
    private val initializationMutex = Mutex()
    private val writeMutex = Mutex()
    private val project = mutableMapOf<String, Glyph>()

    /**
     * Which projects exist. Kept in localStorage: it is a few hundred bytes, read synchronously
     * at startup, and the glyphs themselves live in IndexedDB.
     */
    val registry = ProjectRegistry(
        read = { try { window.localStorage.getItem(PROJECT_REGISTRY_KEY) } catch (_: Throwable) { null } },
        // A failed write throws (ProjectRegistry then keeps its old list), so the caller can say the
        // project list couldn't be saved instead of showing a project that would vanish on reload.
        write = { text ->
            try { window.localStorage.setItem(PROJECT_REGISTRY_KEY, text) } catch (e: Throwable) {
                throw IllegalStateException("The browser wouldn't save the project list (storage full or blocked).")
            }
        },
    )

    /** Theme and view settings: a few dozen bytes, kept in localStorage next to the project list. */
    val settings = SettingsStore(
        read = { try { window.localStorage.getItem("morphont-settings") } catch (_: Throwable) { null } },
        write = { text -> try { window.localStorage.setItem("morphont-settings", text) } catch (_: Throwable) { } },
    )

    private var projectId: String? = null
    private var database: Database? = null
    private var initialized = false
    private var initializationError: Throwable? = null
    private var storageUnavailableWarned = false

    /** Glyph records the last project open couldn't decode; they aren't in the project or its exports. */
    var unreadableOnLoad: List<String> = emptyList()
        private set

    /**
     * Opens durable browser storage and loads the project into memory.
     *
     * Older Morphont builds stored one ever-growing project JSON string in localStorage.
     * On first successful IndexedDB startup, that project is migrated automatically and
     * the old key is removed. IndexedDB stores one record per glyph so autosave no longer
     * rewrites the complete font or runs into localStorage's small synchronous quota.
     */
    suspend fun initialize(): List<String> = initializationMutex.withLock {
        if (initialized) return@withLock listGlyphNames()
        // Once IndexedDB has failed to open, every future initialize() call is another
        // no-hope open attempt (and every autosave calls initialize()) -- fail fast
        // with the original error instead of retrying and stalling on each edit.
        initializationError?.let { throw it }

        val id = projectId ?: return@withLock emptyList()
        try {
            val opened = openDatabase(databaseName(id), DATABASE_VERSION) { db, oldVersion, _ ->
                if (oldVersion < 1) {
                    db.createObjectStore(GLYPH_STORE, KeyPath("name"))
                }
            }

            try {
                val unreadable = mutableListOf<String>()
                val loaded = opened.transaction(GLYPH_STORE) {
                    objectStore(GLYPH_STORE).getAll()
                }.toList().mapNotNull { raw ->
                    val record = raw as StoredGlyph
                    // One undecodable record must not disable persistence for every other glyph -- but
                    // it is reported (unreadableOnLoad), not silently left out of the project and its backups.
                    val decoded = runCatching { record.name to ProjectCodec.decodeGlyph(record.json) }.getOrNull()
                    if (decoded == null) runCatching { record.name }.getOrNull()?.let { unreadable.add(it) }
                    decoded
                }.toMap().toMutableMap()

                unreadableOnLoad = unreadable.toList()
                var legacyMigrated = false
                if (loaded.isEmpty() && id == LEGACY_PROJECT_ID) {
                    val legacy = readLegacyProject()
                    if (legacy.isNotEmpty()) {
                        opened.writeTransaction(GLYPH_STORE) {
                            val store = objectStore(GLYPH_STORE)
                            for ((name, glyph) in legacy) {
                                store.put(storedGlyph(name, glyph))
                            }
                        }
                        loaded.putAll(legacy)
                        legacyMigrated = true
                    }
                }

                project.clear()
                project.putAll(loaded)
                database = opened
                initialized = true
                // Only once its glyphs are safely in IndexedDB: an unreadable legacy copy is the user's only one.
                if (legacyMigrated) clearLegacyProject()
                listGlyphNames()
            } catch (t: Throwable) {
                opened.close()
                throw t
            }
        } catch (t: Throwable) {
            initializationError = t
            throw t
        }
    }

    /**
     * Registers the pre-projects database as "Untitled" if it holds anything. Safe to call on
     * every launch: it does nothing once that project is known (or was deleted and emptied).
     */
    /**
     * Registers every project database the list has lost track of (a project created while the list
     * couldn't be saved, or a list that was cleared or damaged) as "Recovered <id>", the same way the
     * desktop and Android builds adopt orphaned project folders.
     */
    /**
     * Projects whose deletion was asked for but may not have finished: a delete another tab is blocking
     * stays queued in the browser after Morphont stops waiting for it. Orphan recovery must not bring
     * those back (the queued delete would then erase the "recovered" copy), so they're remembered here
     * until a delete is seen through.
     */
    private const val DELETING_KEY = "morphont:deleting"

    private fun deleting(): Set<String> = try {
        window.localStorage.getItem(DELETING_KEY)?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    } catch (_: Throwable) { emptySet() }

    private fun setDeleting(ids: Set<String>) {
        try {
            if (ids.isEmpty()) window.localStorage.removeItem(DELETING_KEY)
            else window.localStorage.setItem(DELETING_KEY, ids.joinToString("\n"))
        } catch (_: Throwable) {}
    }

    suspend fun adoptOrphanProjects() {
        val names = runCatching { databaseNames().await<JsArray<JsString>>().toList().map { it.toString() } }.getOrDefault(emptyList())
        val pending = deleting()
        // A queued delete that has since finished leaves no database behind; forget it.
        val stillThere = pending.filter { databaseName(it) in names }.toSet()
        if (stillThere != pending) setDeleting(stillThere)
        for (name in names) {
            val id = name.removePrefix("morphont-").takeIf { name.startsWith("morphont-") && it.isNotEmpty() } ?: continue
            if (id == "fonts" || registry.find(id) != null || id in pending) continue
            runCatching { registry.create(unusedProjectName(registry, "Recovered $id"), id) }
        }
    }

    suspend fun adoptLegacyProject() {
        if (registry.find(LEGACY_PROJECT_ID) != null) return
        // Its delete may still be queued in the browser; adopting it now would have that delete erase it.
        if (LEGACY_PROJECT_ID in deleting()) return
        val inDatabase = try {
            val db = openDatabase(databaseName(LEGACY_PROJECT_ID), DATABASE_VERSION) { d, oldVersion, _ ->
                if (oldVersion < 1) d.createObjectStore(GLYPH_STORE, KeyPath("name"))
            }
            try {
                db.transaction(GLYPH_STORE) { objectStore(GLYPH_STORE).getAll() }.toList().isNotEmpty()
            } finally {
                db.close()
            }
        } catch (_: Throwable) {
            false
        }
        if (inDatabase || readLegacyProject().isNotEmpty()) {
            registry.create(unusedProjectName(registry, "Untitled"), LEGACY_PROJECT_ID)
        }
    }

    /**
     * Switches to project [id]: waits for queued writes to the project being left, closes
     * its database, then opens and loads the new one. Returns its glyph names.
     */
    suspend fun openProject(id: String): List<String> {
        writeMutex.withLock {
            database?.close()
            database = null
        }
        initializationMutex.withLock {
            initialized = false
            initializationError = null
            project.clear()
            projectId = id
        }
        return initialize()
    }

    /** Erases project [id]'s glyphs from the browser. Closes it first if it is the open one. */
    suspend fun deleteProjectData(id: String) {
        writeMutex.withLock {
            if (projectId == id) {
                database?.close()
                database = null
                projectId = null
                initialized = false
                project.clear()
            }
        }
        if (id == LEGACY_PROJECT_ID) clearLegacyProject()
        setDeleting(deleting() + id)
        deleteDatabase(databaseName(id))
        setDeleting(deleting() - id)
    }

    fun listGlyphNames(): List<String> = project.keys.sorted()

    fun loadGlyph(name: String): Glyph? = project[name]

    /**
     * Updates the in-memory project immediately so the editor stays synchronous, then writes
     * just this glyph to IndexedDB. Any storage error is contained instead of escaping through
     * Compose/Wasm as an uncaught browser exception.
     */
    fun saveGlyph(name: String, glyph: Glyph) {
        project[name] = glyph
        persistAsync("Autosave failed") {
            writeMutex.withLock {
                requireDatabase().writeTransaction(GLYPH_STORE) {
                    objectStore(GLYPH_STORE).put(storedGlyph(name, glyph))
                }
            }
        }
    }

    /**
     * Renames a glyph in one IndexedDB transaction -- the new record is written and the old one removed
     * together, so a failed write (quota, say) leaves the glyph under its old name instead of nowhere. The
     * in-memory project changes at once (the editor stays synchronous); if the transaction then fails it is
     * put back and [onFailed] runs, so the caller can undo what it showed.
     */
    fun moveGlyph(old: String, name: String, glyph: Glyph, onFailed: () -> Unit = {}) {
        project.remove(old)
        project[name] = glyph
        persistAsync("Couldn't rename the glyph", onFailure = {
            project.remove(name)
            project[old] = glyph
            onFailed()
        }) {
            writeMutex.withLock {
                requireDatabase().writeTransaction(GLYPH_STORE) {
                    val store = objectStore(GLYPH_STORE)
                    store.put(storedGlyph(name, glyph))
                    store.delete(Key(old.toJsString()))
                }
            }
        }
    }

    /** Removes a glyph from the open project (the store's key is the glyph name). */
    fun deleteGlyph(name: String) {
        project.remove(name)
        persistAsync("Couldn't delete the glyph") {
            writeMutex.withLock {
                requireDatabase().writeTransaction(GLYPH_STORE) {
                    objectStore(GLYPH_STORE).delete(Key(name.toJsString()))
                }
            }
        }
    }

    fun saveNewGlyphs(glyphs: Map<String, Glyph>): List<String> {
        val existing = glyphs.keys.filter { it in project }
        val fresh = glyphs.filterKeys { it !in project }
        project.putAll(fresh)
        if (fresh.isNotEmpty()) persistAsync("Imported font could not be saved") {
            writeMutex.withLock {
                requireDatabase().writeTransaction(GLYPH_STORE) {
                    val store = objectStore(GLYPH_STORE)
                    for ((name, glyph) in fresh) {
                        store.put(storedGlyph(name, glyph))
                    }
                }
            }
        }
        return existing
    }

    fun glyphExists(name: String): Boolean = project.containsKey(name)

    /** Every glyph of the open project, with [unsaved] (the one being edited) in place of its stored copy. */
    fun snapshotProject(unsaved: Pair<String, Glyph>? = null): Map<String, Glyph> {
        val snapshot = project.toMutableMap()
        unsaved?.let { (name, glyph) -> snapshot[name] = glyph }
        return snapshot
    }

    fun exportProject(unsaved: Pair<String, Glyph>? = null) {
        downloadText("morphont-project.json", ProjectCodec.encodeProject(snapshotProject(unsaved)))
    }

    /**
     * Reads a project file into a new project (never over the open one). [beforeSwitch] runs
     * once the file has decoded, just before the new project opens -- the caller saves and
     * closes whatever is open there.
     */
    fun importProject(beforeSwitch: () -> Unit, onLoaded: (ProjectInfo, List<String>) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json", onError) { text ->
            val glyphs = try {
                ProjectCodec.decodeProject(text)
            } catch (e: Throwable) {
                onError("Import failed: ${e.message ?: e::class.simpleName}")
                return@pickTextFile
            }

            scope.launch {
                try {
                    beforeSwitch()
                    val info = registry.create(unusedProjectName(registry, "Imported project"))
                    openProject(info.id)
                    writeMutex.withLock {
                        val db = requireDatabase()
                        db.writeTransaction(GLYPH_STORE) {
                            val store = objectStore(GLYPH_STORE)
                            store.clear()
                            for ((name, glyph) in glyphs) {
                                store.put(storedGlyph(name, glyph))
                            }
                        }
                        project.clear()
                        project.putAll(glyphs)
                    }
                    onLoaded(info, listGlyphNames())
                } catch (e: Throwable) {
                    onError(storageMessage("Import failed", e))
                }
            }
        }
    }

    fun exportGlyph(name: String, glyph: Glyph) {
        downloadText("$name.morphont.json", ProjectCodec.encodeGlyph(glyph))
    }

    fun importGlyph(name: String, onLoaded: (Glyph) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json", onError) { text ->
            val glyph = try {
                ProjectCodec.decodeGlyph(text)
            } catch (e: Throwable) {
                onError("Import failed: ${e.message ?: e::class.simpleName}")
                return@pickTextFile
            }

            project[name] = glyph
            scope.launch {
                try {
                    initialize()
                    writeMutex.withLock {
                        requireDatabase().writeTransaction(GLYPH_STORE) {
                            objectStore(GLYPH_STORE).put(storedGlyph(name, glyph))
                        }
                    }
                    onLoaded(glyph)
                } catch (e: Throwable) {
                    onError(storageMessage("Import failed", e))
                }
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
                } catch (e: Throwable) {
                    onError("Couldn't read file: ${e.message ?: e::class.simpleName}")
                }
            }
            reader.onerror = {
                onError("Couldn't read file.")
            }
            reader.readAsArrayBuffer(file)
        })
        input.click()
    }

    private fun persistAsync(label: String, onFailure: ((Throwable) -> Unit)? = null, block: suspend () -> Unit) {
        val pinned = projectId ?: return
        scope.launch {
            try {
                initialize()
                // A write that outlived its project (another was opened meanwhile) must not land in the new one.
                if (projectId != pinned) return@launch
                block()
            } catch (e: Throwable) {
                // Persistence failures are exceptional and risk data loss. Make them visible instead
                // of allowing an uncaught DOMException to freeze or silently break the editor -- but
                // only once: once storage is known broken, every subsequent autosave would otherwise
                // pop another blocking alert and freeze the single-threaded UI on every edit.
                if (!storageUnavailableWarned) {
                    storageUnavailableWarned = true
                    window.alert(storageMessage(label, e))
                }
                onFailure?.invoke(e)
                return@launch
            }
            // A write got through again: the next failure is news, so it is reported again.
            storageUnavailableWarned = false
        }
    }

    private fun requireDatabase(): Database =
        checkNotNull(database) { "Browser project storage is not initialized." }

    private fun storageMessage(prefix: String, error: Throwable): String {
        val detail = error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName
            ?: "browser storage error"
        return "$prefix: $detail. Use Save project for a portable backup."
    }

    /** Downloads [text] as [filename] through a Blob URL (a `data:` URL is capped at a few MB in some browsers). */
    private fun downloadText(filename: String, text: String) {
        val blob = Blob(arrayOf<JsAny?>(text.toJsString()).toJsArray(), BlobPropertyBag(type = "application/json;charset=utf-8"))
        val href = URL.createObjectURL(blob)
        window.setTimeout({ URL.revokeObjectURL(href); null }, 60_000)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = href
        anchor.download = filename
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
    }

    private fun pickTextFile(accept: String, onError: (String) -> Unit, onLoaded: (String) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = accept
        input.addEventListener("change", { _: Event ->
            val file = input.files?.item(0) ?: return@addEventListener
            val reader = FileReader()
            reader.onload = { onLoaded(reader.result as String) }
            reader.onerror = { onError("Couldn't read file.") }
            reader.readAsText(file)
        })
        input.click()
    }
}
