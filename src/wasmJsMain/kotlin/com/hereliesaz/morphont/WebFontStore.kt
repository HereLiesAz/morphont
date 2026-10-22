@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hereliesaz.morphont

import com.juul.indexeddb.Database
import com.juul.indexeddb.Key
import com.juul.indexeddb.KeyPath
import com.juul.indexeddb.openDatabase
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.fetch.Response
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.JsAny
import kotlin.js.js

private const val FONT_DATABASE = "morphont-fonts"
private const val BLOB_STORE = "blobs"

private external interface StoredBlob : JsAny {
    var key: String
    var data: Int8Array
}

private fun emptyStoredBlob(): StoredBlob = js("({})")

private fun ByteArray.toInt8Array(): Int8Array = Int8Array(size).also { a -> for (i in indices) a[i] = this[i] }

private fun Int8Array.toByteArray(): ByteArray = ByteArray(length) { this[it] }

/**
 * The browser font store: `fetch` against the store (which serves its read API cross-origin), installed
 * fonts in their own IndexedDB database, and "Download font file", since a page can't install fonts.
 */
object WebFontStore {
    val http = HttpGet { url ->
        val response: Response = try {
            window.fetch(url).await()
        } catch (e: Throwable) {
            throw IllegalStateException("Couldn't reach the store: ${e.message ?: "network error"}")
        }
        check(response.ok) { "The store answered ${response.status}." }
        Int8Array(response.arrayBuffer().await<ArrayBuffer>()).toByteArray()
    }

    private object Blobs : BlobStore {
        private val mutex = Mutex()
        private var database: Database? = null

        private suspend fun db(): Database = mutex.withLock {
            database ?: openDatabase(FONT_DATABASE, 1) { d, oldVersion, _ ->
                if (oldVersion < 1) d.createObjectStore(BLOB_STORE, KeyPath("key"))
            }.also { database = it }
        }

        override suspend fun get(key: String): ByteArray? {
            val raw = db().transaction(BLOB_STORE) { objectStore(BLOB_STORE).get(Key(key.toJsString())) } ?: return null
            return (raw as StoredBlob).data.toByteArray()
        }

        override suspend fun put(key: String, bytes: ByteArray) {
            val record = emptyStoredBlob()
            record.key = key
            record.data = bytes.toInt8Array()
            db().writeTransaction(BLOB_STORE) { objectStore(BLOB_STORE).put(record) }
        }

        override suspend fun delete(key: String) {
            db().writeTransaction(BLOB_STORE) { objectStore(BLOB_STORE).delete(Key(key.toJsString())) }
        }
    }

    private fun download(fileName: String, bytes: ByteArray) {
        val blob = Blob(arrayOf<JsAny?>(bytes.toInt8Array()).toJsArray(), BlobPropertyBag(type = "font/ttf"))
        val url = URL.createObjectURL(blob)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = url
        anchor.download = fileName
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
        window.setTimeout({ URL.revokeObjectURL(url); null }, 10_000)
    }

    val host: FontStoreHost by lazy {
        FontStoreHost(
            client = FontStoreClient(http),
            shelf = FontShelf(Blobs),
            installLabel = "Download font file",
            installToSystem = { fileName, bytes ->
                download(fileName, bytes)
                "Downloaded $fileName; open it to install it."
            },
        )
    }
}
