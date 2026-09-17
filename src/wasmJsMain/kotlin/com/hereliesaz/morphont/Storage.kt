@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hereliesaz.morphont

import com.juul.indexeddb.Database
import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.openDatabase
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.js.toList

/** Global JS `encodeURIComponent`, used to build a `data:` URL for export. */
external fun encodeURIComponent(str: String): String

private const val DATABASE_NAME = "morphont"
private const val DATABASE_VERSION = 1
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

    private var database: Database? = null
    private var initialized = false

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

        val opened = openDatabase(DATABASE_NAME, DATABASE_VERSION) { db, oldVersion, _ ->
            if (oldVersion < 1) {
                db.createObjectStore(GLYPH_STORE, KeyPath("name"))
            }
        }

        try {
            val loaded = opened.transaction(GLYPH_STORE) {
                objectStore(GLYPH_STORE).getAll()
            }.toList().associate { raw ->
                val record = raw as StoredGlyph
                record.name to ProjectCodec.decodeGlyph(record.json)
            }.toMutableMap()

            if (loaded.isEmpty()) {
                val legacy = readLegacyProject()
                if (legacy.isNotEmpty()) {
                    opened.writeTransaction(GLYPH_STORE) {
                        val store = objectStore(GLYPH_STORE)
                        for ((name, glyph) in legacy) {
                            store.put(storedGlyph(name, glyph))
                        }
                    }
                    loaded.putAll(legacy)
                }
            }

            project.clear()
            project.putAll(loaded)
            database = opened
            initialized = true
            clearLegacyProject()
            listGlyphNames()
        } catch (t: Throwable) {
            opened.close()
            throw t
        }
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

    fun saveGlyphs(glyphs: Map<String, Glyph>) {
        project.putAll(glyphs)
        persistAsync("Imported font could not be saved") {
            writeMutex.withLock {
                requireDatabase().writeTransaction(GLYPH_STORE) {
                    val store = objectStore(GLYPH_STORE)
                    for ((name, glyph) in glyphs) {
                        store.put(storedGlyph(name, glyph))
                    }
                }
            }
        }
    }

    fun glyphExists(name: String): Boolean = project.containsKey(name)

    fun exportProject() {
        downloadText("morphont-project.json", ProjectCodec.encodeProject(project))
    }

    fun importProject(onLoaded: (List<String>) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json") { text ->
            val glyphs = try {
                ProjectCodec.decodeProject(text)
            } catch (e: Throwable) {
                onError("Import failed: ${e.message ?: e::class.simpleName}")
                return@pickTextFile
            }

            scope.launch {
                try {
                    initialize()
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
                    onLoaded(listGlyphNames())
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
        pickTextFile(".json,application/json") { text ->
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

    private fun persistAsync(label: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                initialize()
                block()
            } catch (e: Throwable) {
                // Persistence failures are exceptional and risk data loss. Make them visible instead
                // of allowing an uncaught DOMException to freeze or silently break the editor.
                window.alert(storageMessage(label, e))
            }
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

    private fun downloadText(filename: String, text: String) {
        val href = "data:application/json;charset=utf-8," + encodeURIComponent(text)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = href
        anchor.download = filename
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
    }

    private fun pickTextFile(accept: String, onLoaded: (String) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = accept
        input.addEventListener("change", { _: Event ->
            val file = input.files?.item(0) ?: return@addEventListener
            val reader = FileReader()
            reader.onload = { onLoaded(reader.result as String) }
            reader.readAsText(file)
        })
        input.click()
    }
}
