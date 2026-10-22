package com.hereliesaz.morphont

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The Android font store: HTTPS through [HttpURLConnection], installed fonts in app-internal storage,
 * and -- since Android has no per-user font install -- "Save font file…" through the system file picker,
 * from where a font manager or any app that loads fonts can take it.
 */
object AndroidFontStore {
    val http = HttpGet { url ->
        withContext(Dispatchers.IO) {
            val connection = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Morphont")
                }
            } catch (e: Exception) {
                throw IllegalStateException("Couldn't reach the store: ${e.message}")
            }
            try {
                val code = try { connection.responseCode } catch (e: Exception) {
                    throw IllegalStateException("Couldn't reach the store: ${e.message ?: e::class.simpleName}")
                }
                check(code in 200..299) { "The store answered $code." }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }

    class FileBlobs(private val root: File) : BlobStore {
        private fun file(key: String) = File(root, key)

        override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) { file(key).takeIf { it.isFile }?.readBytes() }

        override suspend fun put(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
            val target = file(key)
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (t: Throwable) {
                atomic.failWrite(out)
                throw t
            }
        }

        override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
            val f = file(key)
            f.delete()
            f.parentFile?.takeIf { it != root && it.list()?.isEmpty() == true }?.delete()
            Unit
        }
    }

    fun host(context: Context, files: AndroidFileActions) = FontStoreHost(
        client = FontStoreClient(http),
        shelf = FontShelf(FileBlobs(File(context.applicationContext.filesDir, "morphont-fonts"))),
        installLabel = "Save font file…",
        installToSystem = { fileName, bytes ->
            suspendCancellableCoroutine { cont ->
                files.saveFont(fileName, bytes, { cont.resume("Saved $fileName.") }, { cont.resumeWithException(IllegalStateException(it)) })
            }
        },
    )
}
