package com.hereliesaz.morphont

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reading azphalt `.azp` packages: a zip holding `manifest.json`, `LICENSE`, an optional
 * `signature.json` and the payload, every payload file digested (`sha256-<hex>`) in
 * `manifest.files`. [read] does the container checks azphalt's `verifyAzp` does (zip-slip, size,
 * duplicate entries, every digest, no unlisted file); [validateFont] adds `verifyAzp`'s font-kind
 * rules. The digests only prove the archive agrees with itself -- the manifest that lists them is
 * inside it. Signatures aren't checked yet, so a package is as trustworthy as the HTTPS connection
 * to the store it came from.
 */
object Azp {
    const val MAX_ENTRIES = 4096
    const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    /** Kept well under a phone's heap: the archive, its inflated files and a digest copy coexist. */
    const val MAX_TOTAL_BYTES = 96 * 1024 * 1024
    /** azphalt's own per-face limit for a font package. */
    const val MAX_FONT_BYTES = 32 * 1024 * 1024
    private val FONT_FORMATS = setOf("ttf", "otf", "woff2")

    private fun startsWith(b: ByteArray, vararg sig: Int) = b.size >= sig.size && sig.indices.all { (b[it].toInt() and 0xff) == sig[it] }

    /** Whether [bytes] carry [format]'s file signature (SFNT for ttf/otf, `wOF2` for woff2). */
    fun fontSignatureMatches(bytes: ByteArray, format: String): Boolean = when (format) {
        "woff2" -> startsWith(bytes, 0x77, 0x4f, 0x46, 0x32)
        "ttf", "otf" -> startsWith(bytes, 0x00, 0x01, 0x00, 0x00) || startsWith(bytes, 0x74, 0x72, 0x75, 0x65) || startsWith(bytes, 0x4f, 0x54, 0x54, 0x4f)
        else -> false
    }

    /**
     * azphalt's font-kind rules (`validateFontManifest`), so nothing an install writes is other than
     * what the card said: a family and faces; each face under `fonts/`, ending in its declared format
     * (ttf, otf, woff2), carrying that format's signature, at most [MAX_FONT_BYTES]; no `fonts/` file
     * left undeclared; nothing outside `fonts/` but the licence.
     */
    fun validateFont(pkg: Package) {
        val m = pkg.manifest
        require(m.kind == "font") { "${m.name.ifEmpty { m.id }} is not a font package." }
        val font = requireNotNull(m.font) { "The package has no font block." }
        require(font.family.isNotBlank()) { "The package names no font family." }
        require(font.faces.isNotEmpty()) { "The package has no faces." }
        val declared = mutableSetOf<String>()
        for (face in font.faces) {
            val path = face.path
            require(path.startsWith("fonts/") && path.length > 6) { "Face $path is not under fonts/." }
            require(face.format in FONT_FORMATS) { "Face $path has an unknown format ${face.format}." }
            require(path.lowercase().endsWith("." + face.format)) { "Face $path doesn't end in .${face.format}." }
            require(declared.add(path)) { "Face $path is listed twice." }
            val bytes = requireNotNull(pkg.files[path]) { "Face $path is missing." }
            require(bytes.size <= MAX_FONT_BYTES) { "Face $path is larger than azphalt allows." }
            require(fontSignatureMatches(bytes, face.format)) { "Face $path is not a ${face.format} font." }
        }
        for (path in pkg.files.keys) {
            if (path.startsWith("fonts/")) require(path in declared) { "$path isn't declared by any face." }
            else require(path == "LICENSE") { "A font package can't carry $path." }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** A verified package: its manifest and every payload file (manifest and signature excluded). */
    class Package(val manifest: AzpManifest, val files: Map<String, ByteArray>)

    /** Unzips, checks every path and digest, and parses the manifest. Throws [IllegalArgumentException] with a readable reason. */
    fun read(bytes: ByteArray): Package {
        val entries = unzip(bytes)
        val raw = entries["manifest.json"] ?: throw IllegalArgumentException("Not an azphalt package: no manifest.json")
        // The signature (when there is one) covers these exact bytes; with a key twice, readers that keep
        // the first and readers that keep the last would disagree about what was signed.
        duplicateJsonKey(raw.decodeToString())?.let { throw IllegalArgumentException("The package manifest repeats the key \"$it\".") }
        val manifest = try {
            json.decodeFromString(AzpManifest.serializer(), raw.decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("The package manifest is unreadable: ${e.message}")
        }
        val payload = entries.filterKeys { it != "manifest.json" && it != "signature.json" }
        for ((path, want) in manifest.files) {
            require(unsafePathReason(path) == null) { "Unsafe path in manifest: $path" }
            val data = payload[path] ?: throw IllegalArgumentException("Missing file: $path")
            require("sha256-" + Sha256.hex(data) == want) { "Digest mismatch: $path" }
        }
        for (path in payload.keys) require(path in manifest.files) { "Unlisted file: $path" }
        return Package(manifest, payload)
    }

    /** The first key that appears twice in one JSON object of [text], or null. Strings are skipped correctly. */
    fun duplicateJsonKey(text: String): String? {
        val stack = ArrayDeque<MutableSet<String>?>() // a key set per open object; null for an array
        var i = 0
        var expectKey = false
        while (i < text.length) {
            when (val c = text[i]) {
                '{' -> { stack.addLast(mutableSetOf()); expectKey = true }
                '[' -> { stack.addLast(null); expectKey = false }
                '}', ']' -> { stack.removeLastOrNull(); expectKey = false }
                ',' -> expectKey = stack.lastOrNull() != null
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) { sb.append(text[i]).append(text[i + 1]); i += 2; continue }
                        sb.append(text[i]); i++
                    }
                    if (expectKey) {
                        val keys = stack.lastOrNull()
                        if (keys != null && !keys.add(sb.toString())) return sb.toString()
                        expectKey = false
                    }
                }
                else -> if (c == ':') expectKey = false
            }
            i++
        }
        return null
    }

    /** Why [path] is unsafe to extract, or null: absolute, backslashes, drive letters, NUL, or `.`/`..`/empty segments. */
    fun unsafePathReason(path: String): String? = when {
        path.isEmpty() -> "empty"
        path.startsWith("/") -> "absolute"
        '\\' in path -> "backslash"
        '\u0000' in path -> "NUL"
        path.length >= 2 && path[1] == ':' -> "drive letter"
        path.split('/').any { it.isEmpty() || it == "." || it == ".." } -> "dot or empty segment"
        else -> null
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8)
    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    /** Zip entries by name (directories skipped). Stored and deflated entries only; no zip64. */
    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size >= 22) { "Not a zip archive" }
        var eocd = -1
        val earliest = maxOf(0, bytes.size - 22 - 0xffff)
        for (i in bytes.size - 22 downTo earliest) {
            if (u32(bytes, i) == 0x06054b50L) { eocd = i; break }
        }
        require(eocd >= 0) { "Not a zip archive" }
        val count = u16(bytes, eocd + 10)
        val cdOffset = u32(bytes, eocd + 16)
        require(count <= MAX_ENTRIES) { "Too many files in the package" }
        require(cdOffset < bytes.size) { "Corrupt zip directory" }
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var p = cdOffset.toInt()
        repeat(count) {
            require(p + 46 <= bytes.size && u32(bytes, p) == 0x02014b50L) { "Corrupt zip directory" }
            val method = u16(bytes, p + 10)
            val compressed = u32(bytes, p + 20)
            val size = u32(bytes, p + 24)
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val local = u32(bytes, p + 42)
            require(p + 46 + nameLen <= bytes.size) { "Corrupt zip directory" }
            val name = bytes.copyOfRange(p + 46, p + 46 + nameLen).decodeToString()
            p += 46 + nameLen + extraLen + commentLen
            if (name.endsWith("/")) return@repeat
            unsafePathReason(name)?.let { throw IllegalArgumentException("Unsafe path in package: $name ($it)") }
            require(name !in out) { "Duplicate file in package: $name" }
            require(size <= MAX_ENTRY_BYTES && compressed < bytes.size) { "File too large: $name" }
            total += size
            require(total <= MAX_TOTAL_BYTES) { "Package too large" }
            require(local + 30 <= bytes.size) { "Corrupt zip entry: $name" }
            val lh = local.toInt()
            require(u32(bytes, lh) == 0x04034b50L) { "Corrupt zip entry: $name" }
            val start = lh + 30 + u16(bytes, lh + 26) + u16(bytes, lh + 28)
            require(start + compressed <= bytes.size) { "Truncated zip entry: $name" }
            val data = when (method) {
                0 -> bytes.copyOfRange(start, start + compressed.toInt())
                8 -> Inflate.inflate(bytes, start, limit = size.toInt(), sizeHint = size.toInt())
                else -> throw IllegalArgumentException("Unsupported compression in $name")
            }
            require(data.size.toLong() == size) { "Size mismatch: $name" }
            out[name] = data
        }
        return out
    }
}

@Serializable
data class AzpManifest(
    val id: String,
    val name: String = "",
    val version: String,
    val kind: String,
    val license: String = "",
    val author: String? = null,
    val description: String? = null,
    val files: Map<String, String> = emptyMap(),
    val font: AzpFont? = null,
)

@Serializable
data class AzpFont(
    val family: String,
    val designer: String? = null,
    val sampleText: String? = null,
    val faces: List<AzpFontFace> = emptyList(),
)

@Serializable
data class AzpFontFace(
    val path: String,
    val format: String,
    val style: String? = null,
    val weight: Int? = null,
    val italic: Boolean = false,
    val variable: Boolean = false,
    val axes: List<AzpFontAxis> = emptyList(),
)

@Serializable
data class AzpFontAxis(val tag: String, val name: String? = null, val min: Float, val default: Float, val max: Float)
