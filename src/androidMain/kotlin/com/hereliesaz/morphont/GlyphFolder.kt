package com.hereliesaz.morphont

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.IOException

/**
 * Where one project's glyph files are kept: a folder in the app's own storage, or a folder the person chose
 * through Android's document picker. Everything is by glyph name; how a name becomes a file name is the folder's
 * business.
 */
internal interface GlyphFolder {
    /** The names of the glyphs saved here, in no particular order. */
    fun names(): List<String>
    fun exists(name: String): Boolean

    /** The saved bytes, or null when there is no such glyph or it can't be read. */
    fun read(name: String): ByteArray?

    /**
     * Replaces (or creates) the glyph's file. A failure throws. In app storage the old file stays whole until the new
     * one is; in a chosen folder the file is rewritten in place (providers can't swap files), so a process killed
     * mid-write can leave that one glyph empty.
     */
    fun write(name: String, bytes: ByteArray)

    /** True when the glyph is gone afterwards, whether or not it was there. */
    fun delete(name: String): Boolean

    /** False once the folder itself has gone (deleted, or access taken back). */
    fun isPresent(): Boolean
}

// ---------------------------------------------------------------- the app's own storage

/** One file per glyph, named by the glyph's name in URL-safe Base64, in [dir]. Written atomically. */
internal class FileGlyphFolder(val dir: File) : GlyphFolder {
    private fun encodedName(name: String): String =
        Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodedName(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun file(name: String) = File(dir, "${encodedName(name)}.json")

    /**
     * [AtomicFile] may move the old file aside to "x.json.bak" while it writes a new one, and a process killed
     * in that window leaves only the backup. Reading through [AtomicFile] puts such a backup back; do it for every
     * leftover before listing or checking for a file.
     */
    private fun restoreLeftovers() {
        for (f in dir.listFiles().orEmpty()) {
            if (f.isFile && f.name.endsWith(".json.bak")) {
                runCatching { AtomicFile(File(dir, f.name.removeSuffix(".bak"))).openRead().close() }
            }
        }
    }

    override fun names(): List<String> {
        restoreLeftovers()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { decodedName(it.name.removeSuffix(".json")) }
    }

    override fun exists(name: String): Boolean {
        restoreLeftovers()
        return file(name).isFile
    }

    override fun read(name: String): ByteArray? = try {
        AtomicFile(file(name)).readFully()
    } catch (_: Throwable) {
        null
    }

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val atomic = AtomicFile(file(name))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    override fun delete(name: String): Boolean {
        val f = file(name)
        return !f.exists() || f.delete()
    }

    override fun isPresent() = dir.isDirectory
}

// ---------------------------------------------------------------- a folder the person chose

/**
 * Glyph files in a folder inside a document tree the person picked ([treeUri]); [folderId] is that folder's
 * document id. File names follow the shared scheme in GlyphFileNames.kt, so the folder reads the same as a
 * desktop project's. Document providers are slow and can be unreachable, so the id of each file is remembered
 * once found, and anything that goes wrong surfaces as an exception the caller reports.
 */
internal class TreeGlyphFolder(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val folderId: String,
) : GlyphFolder {
    private val ids = HashMap<String, String>()

    /** True once [ids] holds a full listing; until then (and after any failure) the next lookup lists the folder. */
    private var listed = false

    private fun folderUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, folderId)
    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    /** File name -> document id, for every file (not folder) directly inside, read afresh and remembered. */
    @Synchronized
    private fun listing(): Map<String, String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        var attempt = 0
        while (true) {
            val out = LinkedHashMap<String, String>()
            var loading = false
            val cursor = resolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                null, null, null,
            ) ?: throw IOException("The folder can't be read.")
            cursor.use {
                // Providers may return more columns than asked for, in their own order; read by name.
                val id = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val display = it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mime = it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                while (it.moveToNext()) {
                    if (it.getString(mime) == Document.MIME_TYPE_DIR) continue
                    out[it.getString(display)] = it.getString(id)
                }
                loading = it.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            }
            // A cloud provider can answer with what it has so far; a short wait, once or twice, usually gets the rest.
            if (loading && attempt < 4) { attempt++; Thread.sleep(300); continue }
            ids.clear()
            ids.putAll(out)
            listed = true
            return out
        }
    }

    @Synchronized
    private fun idOf(fileName: String): String? = ids[fileName] ?: if (listed) null else listing()[fileName]

    /**
     * The glyphs saved here. Only files whose name is exactly what this scheme writes count: a hand-made `A.json`
     * beside `%41.json` would be a second "A", and every other file in the folder is left alone.
     */
    override fun names(): List<String> =
        listing().keys.mapNotNull { f -> glyphNameOfFile(f)?.takeIf { glyphFileName(it) == f } }

    override fun exists(name: String) = idOf(glyphFileName(name)) != null

    @Synchronized
    override fun read(name: String): ByteArray? {
        val fileName = glyphFileName(name)
        val id = idOf(fileName) ?: return null
        return try {
            resolver.openInputStream(documentUri(id))?.use { it.readBytes() }
        } catch (_: Throwable) {
            listed = false // it may have been moved or deleted outside the app; look again next time
            null
        }
    }

    @Synchronized
    override fun write(name: String, bytes: ByteArray) {
        val fileName = glyphFileName(name)
        var id = idOf(fileName)
        if (id == null) {
            val created = try {
                DocumentsContract.createDocument(resolver, folderUri(), "application/json", fileName)
            } catch (t: Throwable) {
                // Most likely a file of that name is there after all (our listing was stale): look again, then write into it.
                listed = false
                id = idOf(fileName) ?: throw t
                null
            }
            if (created != null) {
                id = DocumentsContract.getDocumentId(created)
                ids[fileName] = id
            }
        }
        try {
            // "wt": truncate, then write. The file is small and goes out in one piece.
            resolver.openOutputStream(documentUri(checkNotNull(id)), "wt")?.use { it.write(bytes) }
                ?: throw IOException("Couldn't open $fileName for writing.")
        } catch (t: Throwable) {
            listed = false
            throw t
        }
    }

    @Synchronized
    override fun delete(name: String): Boolean {
        val fileName = glyphFileName(name)
        val id = idOf(fileName) ?: return true
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, documentUri(id))
        } catch (_: Throwable) {
            false
        }
        if (deleted) ids.remove(fileName) else listed = false
        return deleted
    }

    override fun isPresent(): Boolean = try {
        resolver.query(folderUri(), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (_: Throwable) {
        false
    }
}

/** Looking around inside a document tree: names, and making folders. */
internal object DocumentTree {
    /** The display name of [documentId] in [tree], or null. */
    fun displayName(resolver: ContentResolver, tree: Uri, documentId: String): String? = try {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) else null }
    } catch (_: Throwable) {
        null
    }

    /** The names of everything (files and folders) directly inside [parentId]. */
    fun childNames(resolver: ContentResolver, tree: Uri, parentId: String): Set<String> {
        val cursor = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null,
        ) ?: throw IOException("The folder can't be read.")
        return cursor.use { c ->
            val display = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            buildSet { while (c.moveToNext()) add(c.getString(display)) }
        }
    }

    /** A name safe to give a folder on any provider: no path or reserved characters, never empty. */
    fun folderName(wanted: String): String =
        wanted.map { if (it in "\\/:*?\"<>|" || it.code < 0x20) '_' else it }.joinToString("").trim().trim('.').ifEmpty { "Project" }

    /** Makes a folder named [wanted] (or "[wanted] 2" and so on, if that is taken) inside [parentId]; returns its document id and final name. */
    fun createFolder(resolver: ContentResolver, tree: Uri, parentId: String, wanted: String): Pair<String, String> {
        val taken = childNames(resolver, tree, parentId)
        val base = folderName(wanted)
        var name = base
        var n = 2
        while (name in taken) name = "$base ${n++}"
        val created = DocumentsContract.createDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), Document.MIME_TYPE_DIR, name,
        ) ?: throw IOException("Couldn't make the folder \"$name\".")
        return DocumentsContract.getDocumentId(created) to name
    }
}
