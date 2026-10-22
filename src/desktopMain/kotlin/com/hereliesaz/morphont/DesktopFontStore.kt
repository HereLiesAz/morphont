package com.hereliesaz.morphont

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop font store: HTTPS through the JDK client, installed fonts as files in the app-data folder,
 * and "Install on this computer" into the user's own font folder -- no administrator rights needed on
 * any of the three systems.
 */
object DesktopFontStore {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    }

    val http = HttpGet { url ->
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Morphont").GET().build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                throw IllegalStateException("Couldn't reach the store: ${e.message ?: e::class.simpleName}")
            }
            check(response.statusCode() in 200..299) { "The store answered ${response.statusCode()}." }
            response.body()
        }
    }

    /** Keys are the shelf's own ("index.json", "face/<id>/<n>-<file>"): already safe path segments. */
    class FileBlobs(private val root: File) : BlobStore {
        private fun file(key: String) = File(root, key)

        override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) { file(key).takeIf { it.isFile }?.readBytes() }

        override suspend fun put(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
            val target = file(key)
            target.parentFile.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Unit
        }

        override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
            val f = file(key)
            f.delete()
            // Drop the package's folder once its last face is gone.
            f.parentFile?.takeIf { it != root && it.list()?.isEmpty() == true }?.delete()
            Unit
        }
    }

    /** The user's font folder on this system, and how to tell the system a font arrived. */
    internal fun userFontDir(os: String = System.getProperty("os.name").lowercase(), env: (String) -> String? = System::getenv): File {
        val home = System.getProperty("user.home")
        return when {
            os.contains("win") -> File(env("LOCALAPPDATA") ?: "$home/AppData/Local", "Microsoft/Windows/Fonts")
            os.contains("mac") -> File(home, "Library/Fonts")
            else -> File(env("XDG_DATA_HOME") ?: "$home/.local/share", "fonts/morphont")
        }
    }

    suspend fun installToSystem(fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val safe = fileName.substringAfterLast('/').substringAfterLast('\\').filter { it.isLetterOrDigit() || it in "-_. " }
            .trimStart('.').ifEmpty { "font.ttf" }
        val dir = userFontDir().apply { mkdirs() }
        val target = File(dir, safe)
        target.writeBytes(bytes)
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> {
                // Per-user fonts are found through HKCU; without the entry the file sits unused until a reboot scan.
                val name = safe.substringBeforeLast('.') + if (safe.endsWith(".otf", true)) " (OpenType)" else " (TrueType)"
                runCatching {
                    ProcessBuilder("reg", "add", "HKCU\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
                        "/v", name, "/t", "REG_SZ", "/d", target.absolutePath, "/f").redirectErrorStream(true).start().waitFor()
                }
            }
            !os.contains("mac") -> runCatching { ProcessBuilder("fc-cache", "-f", dir.absolutePath).redirectErrorStream(true).start().waitFor() }
        }
        "Installed $safe in ${dir.absolutePath}."
    }

    val host: FontStoreHost by lazy {
        FontStoreHost(
            client = FontStoreClient(http),
            shelf = FontShelf(FileBlobs(DesktopStorage.fontShelfDir)),
            installLabel = "Install on this computer",
            installToSystem = ::installToSystem,
        )
    }
}
