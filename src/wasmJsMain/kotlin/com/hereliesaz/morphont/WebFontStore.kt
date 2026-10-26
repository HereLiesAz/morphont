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

/** Hands [bytes] to the browser as a download named [fileName]. */
internal fun downloadBytes(fileName: String, mimeType: String, bytes: ByteArray) {
    val blob = Blob(arrayOf<JsAny?>(bytes.toInt8Array()).toJsArray(), BlobPropertyBag(type = mimeType))
    val url = URL.createObjectURL(blob)
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = fileName
    document.body?.appendChild(anchor)
    anchor.click()
    document.body?.removeChild(anchor)
    window.setTimeout({ URL.revokeObjectURL(url); null }, 10_000)
}

private fun bodyReader(response: Response): JsAny? = js("(response.body ? response.body.getReader() : null)")

private fun readChunk(reader: JsAny): kotlin.js.Promise<JsAny> = js("reader.read()")

private fun chunkDone(chunk: JsAny): Boolean = js("chunk.done === true")

private fun chunkValue(chunk: JsAny): org.khronos.webgl.Uint8Array = js("chunk.value")

private fun cancelReader(reader: JsAny) { js("reader.cancel()") }

/** A growing byte buffer for streamed chunks. */
private class ByteArrayBuilder {
    private var data = ByteArray(64 * 1024)
    var size = 0
        private set

    fun append(part: org.khronos.webgl.Uint8Array) {
        val need = size + part.length
        if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2))
        for (i in 0 until part.length) data[size + i] = part[i]
        size = need
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

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
        response.headers.get("content-length")?.toLongOrNull()?.let { check(it <= MAX_DOWNLOAD_BYTES) { "The download is larger than Morphont accepts." } }
        // Content-Length can be missing or wrong, and a compressed body decodes larger than it says, so the
        // cap is enforced on the decoded bytes as they arrive, and the transfer is abandoned past it.
        val reader = bodyReader(response)
        if (reader == null) {
            val bytes = Int8Array(response.arrayBuffer().await<ArrayBuffer>()).toByteArray()
            check(bytes.size <= MAX_DOWNLOAD_BYTES) { "The download is larger than Morphont accepts." }
            bytes
        } else {
            val out = ByteArrayBuilder()
            while (true) {
                val chunk = readChunk(reader).await<JsAny>()
                if (chunkDone(chunk)) break
                val part = chunkValue(chunk)
                if (out.size + part.length > MAX_DOWNLOAD_BYTES) {
                    cancelReader(reader)
                    throw IllegalStateException("The download is larger than Morphont accepts.")
                }
                out.append(part)
            }
            out.toByteArray()
        }
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

    private fun download(fileName: String, bytes: ByteArray) = downloadBytes(fileName, "font/ttf", bytes)

    val host: FontStoreHost by lazy {
        FontStoreHost(
            client = FontStoreClient(http),
            shelf = FontShelf(Blobs),
            installLabel = "Download font file",
            installToSystem = { _, fileName, bytes ->
                download(fileName, bytes)
                "Downloaded $fileName; open it to install it."
            },
        )
    }
}
