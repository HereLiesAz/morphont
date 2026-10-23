package com.hereliesaz.morphont

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The azphalt store Morphont browses by default. */
const val AZPHALT_STORE = "https://azphalt.store"

/** One font family as the store lists it: enough for a row, without downloading the package. */
data class StoreFont(
    val id: String,
    val name: String,
    val version: String,
    val family: String,
    val author: String? = null,
    val description: String? = null,
    val variable: Boolean = false,
    val axes: List<String> = emptyList(),
    val faces: Int = 1,
    val paid: Boolean = false,
)

/** The most any store or update request may download: a package is capped at [Azp.MAX_TOTAL_BYTES] anyway. */
const val MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024

/** Fetches a URL's body; throws on a network failure, a non-2xx status, or a body over [MAX_DOWNLOAD_BYTES]. One per platform. */
fun interface HttpGet {
    suspend fun get(url: String): ByteArray
}

/**
 * The azphalt Repository API, read side, for `kind: "font"` (spec/repository-api.md § 2–4 in the
 * azphalt repo): search the catalog, download a version, verify it.
 */
class FontStoreClient(private val http: HttpGet, private val base: String = AZPHALT_STORE) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String = ""): List<StoreFont> {
        val q = query.trim()
        val url = "$base/packages?kind=font" + if (q.isEmpty()) "" else "&q=" + urlEncode(q)
        return parseListing(http.get(url).decodeToString())
    }

    /** Downloads [font] and returns the verified package. Throws with a readable reason. */
    suspend fun download(font: StoreFont): Azp.Package {
        require(!font.paid) { "${font.name} is sold in the store; buy it there." }
        val bytes = http.get("$base/packages/${urlEncode(font.id)}/versions/${urlEncode(font.version)}/download")
        val pkg = Azp.read(bytes)
        require(pkg.manifest.id == font.id) { "The store sent a different package than ${font.id}." }
        require(pkg.manifest.version == font.version) { "The store sent ${font.name} ${pkg.manifest.version}, not ${font.version}." }
        Azp.validateFont(pkg)
        return pkg
    }

    internal fun parseListing(text: String): List<StoreFont> {
        val root = json.parseToJsonElement(text).jsonObject
        val rows = root["packages"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            fun s(k: String, from: JsonObject = o) = from[k]?.jsonPrimitive?.contentOrNull
            if (s("kind") != "font") return@mapNotNull null
            val id = s("id") ?: return@mapNotNull null
            val version = s("latest") ?: s("version") ?: return@mapNotNull null
            // The browse summary's `font` card, or, failing that, the full manifest some stores inline.
            val card = o["font"] as? JsonObject
            val manifestFont = (o["manifest"] as? JsonObject)?.get("font") as? JsonObject
            val faces = (manifestFont?.get("faces") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val axes = card?.get("axes")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: faces.flatMap { f -> (f["axes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { a -> s("tag", a) } } }.distinct()
            StoreFont(
                id = id,
                name = s("name") ?: id,
                version = version,
                family = card?.let { s("family", it) } ?: manifestFont?.let { s("family", it) } ?: s("name") ?: id,
                author = s("author"),
                description = s("description"),
                variable = card?.get("variable")?.jsonPrimitive?.booleanOrNull
                    ?: faces.any { it["variable"]?.jsonPrimitive?.booleanOrNull == true },
                axes = axes,
                faces = card?.get("faces")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: faces.size.coerceAtLeast(1),
                paid = s("priceStatus") == "paid",
            )
        }
    }
}

internal fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") append(c.toChar())
        else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
}

/** Bytes kept by key on the platform: files on desktop and Android, IndexedDB in the browser. */
interface BlobStore {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, bytes: ByteArray)
    suspend fun delete(key: String)
}

/** One face of an installed family; [key] is where its bytes live in the [BlobStore]. */
@Serializable
data class ShelfFace(
    val key: String,
    val fileName: String,
    val format: String,
    val style: String? = null,
    val variable: Boolean = false,
) {
    /** Morphont reads TrueType outlines only; `.otf` may be either flavour, so it is tried. */
    val readable: Boolean get() = format == "ttf" || format == "otf"
}

/** An installed font package. */
@Serializable
data class InstalledFont(
    val id: String,
    val version: String,
    val family: String,
    val name: String,
    val author: String? = null,
    val license: String = "",
    val faces: List<ShelfFace> = emptyList(),
)

@Serializable
private data class ShelfIndex(val fonts: List<InstalledFont> = emptyList())

/**
 * The fonts installed from the store, kept on this device: an index plus each face's verified
 * bytes. A version's faces are written under their own keys before the index points at them, so an
 * interrupted install or update leaves the previous version whole. An index that can't be read is
 * never taken for an empty one (which the next install would then write over).
 */
class FontShelf(private val blobs: BlobStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cache: List<InstalledFont>? = null

    /** The installed fonts. Throws if the list exists but can't be read right now, rather than reporting none. */
    suspend fun list(): List<InstalledFont> = cache ?: run {
        val raw = try { blobs.get(INDEX) } catch (e: Exception) {
            throw IllegalStateException("Couldn't read the installed-fonts list: ${e.message ?: "storage error"}. Try again.")
        }
        val fonts = if (raw == null) emptyList() else try {
            json.decodeFromString(ShelfIndex.serializer(), raw.decodeToString()).fonts
        } catch (e: Exception) {
            // Damaged beyond reading: keep the bytes for recovery, then start a fresh list.
            runCatching { blobs.put(UNREADABLE_INDEX, raw) }
            emptyList()
        }
        fonts.also { cache = it }
    }

    suspend fun install(pkg: Azp.Package): InstalledFont {
        val m = pkg.manifest
        val font = requireNotNull(m.font) { "Not a font package." }
        require(font.faces.isNotEmpty()) { "The package has no faces." }
        val installed = list()
        val faces = font.faces.mapIndexed { i, face ->
            val bytes = requireNotNull(pkg.files[face.path]) { "Missing ${face.path}" }
            val fileName = face.path.substringAfterLast('/')
            val key = "face/${safeKey(m.id)}/${safeKey(m.version)}/$i-${safeKey(fileName)}"
            blobs.put(key, bytes)
            ShelfFace(key, fileName, face.format, face.style, face.variable)
        }
        val entry = InstalledFont(m.id, m.version, font.family, m.name.ifEmpty { font.family }, m.author, m.license, faces)
        val previous = installed.firstOrNull { it.id == m.id }
        commit(installed.filter { it.id != m.id } + entry)
        // The old version's faces, once nothing points at them.
        previous?.faces?.filter { old -> faces.none { it.key == old.key } }?.forEach { runCatching { blobs.delete(it.key) } }
        return entry
    }

    suspend fun remove(id: String) {
        val font = list().firstOrNull { it.id == id } ?: return
        commit(list().filter { it.id != id })
        for (face in font.faces) runCatching { blobs.delete(face.key) }
    }

    suspend fun bytes(face: ShelfFace): ByteArray? = blobs.get(face.key)

    private suspend fun commit(fonts: List<InstalledFont>) {
        blobs.put(INDEX, json.encodeToString(ShelfIndex.serializer(), ShelfIndex(fonts)).encodeToByteArray())
        cache = fonts
    }

    private fun safeKey(s: String) = s.map { if (it.isLetterOrDigit() || it in "-._") it else '_' }.joinToString("")

    private companion object {
        const val INDEX = "index.json"
        const val UNREADABLE_INDEX = "index-unreadable.json"
    }
}

/** How [face] of [font] is listed among reference fonts: its family, plus the style when the family has several faces. */
fun shelfFaceLabel(font: InstalledFont, face: ShelfFace, all: List<InstalledFont>): String {
    val base = if (font.faces.size > 1) "${font.family} ${face.style ?: face.fileName}" else font.family
    // Two installed packages may share a family name; the second is told apart by its package id.
    val clash = all.any { it.id != font.id && it.family == font.family }
    return if (clash) "$base (${font.id})" else base
}

/** What a platform gives the editor for the font store; null on a surface without one. */
class FontStoreHost(
    val client: FontStoreClient,
    val shelf: FontShelf,
    /** "Install on this computer", "Save font file…", "Download font file": what [installToSystem] does here. */
    val installLabel: String,
    /**
     * Puts a face where the OS (or the user) can use it; returns a status line. [packageId] keeps
     * the file's name apart from the user's own fonts of the same name.
     */
    val installToSystem: suspend (packageId: String, fileName: String, bytes: ByteArray) -> String,
) {
    /** Reference-font names loaded from each installed package, so updating or removing it unloads exactly those. */
    val loaded = mutableMapOf<String, MutableSet<String>>()
}
