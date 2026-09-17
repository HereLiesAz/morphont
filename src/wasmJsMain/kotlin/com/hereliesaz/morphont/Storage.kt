@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hereliesaz.morphont

import com.juul.indexeddb.Database
import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.openDatabase
import kotlinx.browser.document
import kotlinx.browser.window
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

private fun storedGlyph(name: String, glyph: Glyph): StoredGlyph {
    val record = js("({})") as StoredGlyph
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
        // IndexedDB is now authoritative. A browser that blocks localStorage cleanup
        // should not prevent the editor from using its durable IndexedDB copy.
    }
}

object Storage {
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

    suspend fun saveGlyph(name: String, glyph: Glyph) {
        initialize()
        writeMutex.withLock {
            project[name] = glyph
            requireDatabase().writeTransaction(GLYPH_STORE) {
                objectStore(GLYPH_STORE).put(storedGlyph(name, glyph))
            }
        }
    }

    suspend fun saveGlyphs(glyphs: Map<String, Glyph>) {
        initialize()
        writeMutex.withLock {
            project.putAll(glyphs)
            requireDatabase().writeTransaction(GLYPH_STORE) {
                val store = objectStore(GLYPH_STORE)
                for ((name, glyph) in glyphs) {
                    store.put(storedGlyph(name, glyph))
                }
            }
        }
    }

    suspend fun replaceProject(glyphs: Map<String, Glyph>) {
        initialize()
        writeMutex.withLock {
            project.clear()
            project.putAll(glyphs)
            requireDatabase().writeTransaction(GLYPH_STORE) {
                val store = objectStore(GLYPH_STORE)
                store.clear()
                for ((name, glyph) in glyphs) {
                    store.put(storedGlyph(name, glyph))
                }
            }
        }
    }

    fun glyphExists(name: String): Boolean = project.containsKey(name)

    fun exportProject() {
        downloadText("morphont-project.json", ProjectCodec.encodeProject(project))
    }

    fun importProject(onLoaded: (Map<String, Glyph>) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json") { text ->
            try {
                onLoaded(ProjectCodec.decodeProject(text))
            } catch (e: Throwable) {
                onError("Import failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun exportGlyph(name: String, glyph: Glyph) {
        downloadText("$name.morphont.json", ProjectCodec.encodeGlyph(glyph))
    }

    fun importGlyph(onLoaded: (Glyph) -> Unit, onError: (String) -> Unit) {
        pickTextFile(".json,application/json") { text ->
            try {
                onLoaded(ProjectCodec.decodeGlyph(text))
            } catch (e: Throwable) {
                onError("Import failed: ${e.message ?: e::class.simpleName}")
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

    private fun requireDatabase(): Database =
        checkNotNull(database) { "Browser project storage is not initialized." }

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
