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
/** Reads [input] whole, refusing more than [MAX_DOWNLOAD_BYTES] so a bad server can't exhaust memory. */
internal fun readCapped(input: java.io.InputStream, limit: Int = MAX_DOWNLOAD_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        check(out.size() + n <= limit) { "The download is larger than Morphont accepts." }
        out.write(buf, 0, n)
    }
}

object DesktopFontStore {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    }

    val http = HttpGet { url ->
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Morphont").GET().build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                throw IllegalStateException("Couldn't reach the store: ${e.message ?: e::class.simpleName}")
            }
            response.body().use { body ->
                check(response.statusCode() in 200..299) { "The store answered ${response.statusCode()}." }
                readCapped(body)
            }
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
            // Then the package's (and version's) folders, once their last face is gone.
            var dir = f.parentFile
            while (dir != null && dir != root && dir.list()?.isEmpty() == true) { dir.delete(); dir = dir.parentFile }
            Unit
        }
    }

    /** The user's font folder on this system, and how to tell the system a font arrived. */
    internal fun userFontDir(os: String = System.getProperty("os.name").lowercase(), env: (String) -> String? = System::getenv): File {
        val home = System.getProperty("user.home")
        return when {
            os.contains("win") -> File(env("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Local", "Microsoft/Windows/Fonts")
            os.contains("mac") -> File(home, "Library/Fonts")
            // An empty or relative XDG_DATA_HOME is to be ignored (XDG Base Directory spec).
            else -> File(env("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/share", "fonts/morphont")
        }
    }

    /**
     * The file name a store face is installed under: `<package id>-<face file>`, letters, digits and
     * `-_.` only, ending in `.ttf` or `.otf`. The package prefix keeps it from ever replacing a font the
     * user installed themselves (an `Arial.ttf` in a package lands as `com.acme.x-Arial.ttf`) or one
     * from another package, and means no file can take a Windows device name such as `CON`.
     */
    internal fun systemFileName(packageId: String, fileName: String): String {
        fun clean(s: String) = s.filter { it.isLetterOrDigit() || it in "-_." }.trim('.')
        val face = clean(fileName.substringAfterLast('/').substringAfterLast('\\'))
        val ext = face.substringAfterLast('.', "").lowercase()
        require(ext == "ttf" || ext == "otf") { "Only TrueType and OpenType fonts (.ttf, .otf) can be installed on this computer, not .$ext." }
        return "${clean(packageId).ifEmpty { "morphont" }}-${face.substringBeforeLast('.').ifEmpty { "font" }}.$ext"
    }

    private fun run(vararg command: String): Int? =
        runCatching { ProcessBuilder(*command).redirectErrorStream(true).start().let { p -> p.inputStream.readBytes(); p.waitFor() } }.getOrNull()

    suspend fun installToSystem(packageId: String, fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val safe = systemFileName(packageId, fileName)
        val dir = userFontDir().apply { mkdirs() }
        val target = File(dir, safe)
        val temp = File(dir, "$safe.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val os = System.getProperty("os.name").lowercase()
        val note = when {
            os.contains("win") -> {
                // Per-user fonts are found through HKCU; without the entry apps don't see the file until the next sign-in.
                val name = safe.substringBeforeLast('.') + if (safe.endsWith(".otf", true)) " (OpenType)" else " (TrueType)"
                val code = run("reg", "add", "HKCU\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
                    "/v", name, "/t", "REG_SZ", "/d", target.absolutePath, "/f")
                if (code == 0) "" else " Windows didn't register it (sign out and back in, or install it from the file)."
            }
            os.contains("mac") -> ""
            else -> if (run("fc-cache", "-f", dir.absolutePath) == 0) "" else " The font cache wasn't refreshed (run fc-cache, or restart apps)."
        }
        "Installed $safe in ${dir.absolutePath}.$note"
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
