package com.hereliesaz.morphont

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A document provider over a temp directory: document id "root" is the directory, "root/a/b" is a/b inside it. */
class FakeDocs : DocumentsProvider() {
    companion object {
        lateinit var root: File
        const val AUTH = "fake.docs"
        @Volatile var beforeCreateFile: (() -> Unit)? = null
    }

    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    private fun file(id: String): File = if (id == "root") root else File(root, id.removePrefix("root/"))
    private fun idOf(f: File): String = if (f == root) "root" else "root/" + f.relativeTo(root).path
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun add(c: MatrixCursor, f: File) {
        val flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or (if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        c.addRow(arrayOf(idOf(f), if (f == root) "Documents" else f.name, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/json", flags, f.length(), f.lastModified()))
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(columns).also { add(it, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = file(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(columns).also { c -> dir.listFiles().orEmpty().forEach { add(c, it) } }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        val f = File(parent, displayName)
        if (f.exists()) throw IllegalStateException("exists: $displayName")
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else { beforeCreateFile?.invoke(); f.createNewFile() }
        return idOf(f)
    }

    override fun deleteDocument(documentId: String) { file(documentId).deleteRecursively() }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith(parentDocumentId)
}

/**
 * Robolectric routes the five-argument query to the provider's legacy entry point, which a DocumentsProvider
 * refuses; a real device calls the Bundle one. This stand-in makes Robolectric's call reach the real
 * DocumentsProvider code path the way a device's would.
 */
class DocsAdapter : android.content.ContentProvider() {
    private lateinit var docs: FakeDocs
    override fun onCreate() = true
    override fun attachInfo(context: Context, info: android.content.pm.ProviderInfo) {
        docs = FakeDocs()
        docs.attachInfo(context, info)
        super.attachInfo(context, info)
    }
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        docs.query(uri, projection, android.os.Bundle.EMPTY, null)
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? = docs.call(method, arg, extras)
    override fun openFile(uri: android.net.Uri, mode: String): ParcelFileDescriptor? = docs.openFile(uri, mode)
    override fun getType(uri: android.net.Uri): String? = docs.getType(uri)
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * Project storage on Android, in app storage and in a folder behind a document provider: moves in both
 * directions, saves made while a move copies, folders that vanish, folders that already hold a project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFolderStorageTest {
    private lateinit var context: Context
    private lateinit var storage: AndroidStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakeDocs.root = java.nio.file.Files.createTempDirectory("docs").toFile()
        FakeDocs.beforeCreateFile = null
        val info = android.content.pm.ProviderInfo().apply {
            authority = FakeDocs.AUTH
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(DocsAdapter::class.java).create(info)
        storage = AndroidStorage(context)
    }

    private fun tree() = DocumentsContract.buildTreeDocumentUri(FakeDocs.AUTH, "root")

    private fun glyph(width: Float = 500f) = Glyph().also {
        it.corners["regular"] = GlyphCorner(width, mutableListOf(ContourData(mutableListOf(Pt(0f, 0f, true), Pt(10f, 0f, true), Pt(10f, 10f, true)))))
    }

    private fun project(name: String = "Mine"): ProjectInfo {
        val info = storage.registry.create(name)
        storage.useProject(info.id)
        return info
    }

    private fun onDisk(vararg path: String) = File(FakeDocs.root, path.joinToString("/"))

    // ---- the folder itself

    @Test
    fun aTreeFolderStoresReadsAndForgetsGlyphs() {
        val (id, _) = DocumentTree.createFolder(context.contentResolver, tree(), "root", "Folder")
        val folder = TreeGlyphFolder(context.contentResolver, tree(), id)
        assertTrue(folder.isPresent())
        assertEquals(emptyList(), folder.names())
        folder.write("A", "long long long".encodeToByteArray())
        folder.write("a", "x".encodeToByteArray())
        folder.write("é", "e".encodeToByteArray())
        assertEquals(setOf("A", "a", "é"), folder.names().toSet())
        assertTrue(onDisk("Folder", "%41.json").isFile)
        assertTrue(onDisk("Folder", "a.json").isFile)
        // overwriting with less truncates
        folder.write("A", "short".encodeToByteArray())
        assertContentEquals("short".encodeToByteArray(), folder.read("A"))
        assertEquals("short", onDisk("Folder", "%41.json").readText())
        assertTrue(folder.exists("a"))
        assertTrue(folder.delete("a"))
        assertFalse(folder.exists("a"))
        assertNull(folder.read("a"))
        assertTrue(folder.delete("never-there"))
        onDisk("Folder").deleteRecursively()
        assertFalse(folder.isPresent())
    }

    @Test
    fun twoFoldersWithOneNameGetNumbered() {
        val a = DocumentTree.createFolder(context.contentResolver, tree(), "root", "Same")
        val b = DocumentTree.createFolder(context.contentResolver, tree(), "root", "Same")
        assertEquals("Same", a.second)
        assertEquals("Same 2", b.second)
        assertEquals("_x_", DocumentTree.folderName("/x\\"))
        assertEquals("Project", DocumentTree.folderName("   "))
    }

    // ---- the app's own storage still behaves

    @Test
    fun appStorageStillWorksAsBefore() {
        project()
        storage.saveGlyph("A", glyph(321f))
        storage.saveGlyph("b", glyph())
        assertEquals(listOf("A", "b"), storage.listGlyphNames())
        assertEquals(321f, storage.loadGlyph("A")!!.corners["regular"]!!.width)
        assertTrue(storage.glyphExists("b"))
        storage.deleteGlyph("b")
        assertFalse(storage.glyphExists("b"))
        val (all, skipped) = storage.readProject(unsaved = "Z" to glyph())
        assertEquals(setOf("A", "Z"), all.keys)
        assertTrue(skipped.isEmpty())
        val names = storage.importProjectText(ProjectCodec.encodeProject(mapOf("Q" to glyph())))
        assertEquals(listOf("Q"), names)
        assertEquals(listOf("Q"), storage.listGlyphNames())
    }

    // ---- moving into a folder

    @Test
    fun movingToAFolderCopiesSwitchesAndClearsTheAppCopy() {
        val info = project("My Font")
        storage.saveGlyph("A", glyph(111f))
        storage.saveGlyph("a", glyph(222f))
        val where = storage.moveProjectToFolder(info.id, tree())
        assertEquals("Documents/My Font", where.label)
        assertEquals(where, storage.registry.find(info.id)!!.location)
        assertEquals(where.copy(folder = "root", label = "Documents"), storage.registry.newProjectsIn!!.copy(tree = where.tree))
        assertTrue(onDisk("My Font", "%41.json").isFile)
        assertTrue(onDisk("My Font", "a.json").isFile)
        assertFalse(File(context.filesDir, "morphont-projects/${info.id}").exists(), "the app's copy is gone")
        // the open project now reads and saves in the folder
        assertEquals(listOf("A", "a"), storage.listGlyphNames())
        assertEquals(222f, storage.loadGlyph("a")!!.corners["regular"]!!.width)
        storage.saveGlyph("a", glyph(333f))
        assertTrue(onDisk("My Font", "a.json").readText().contains("333"))
        storage.saveGlyph("new", glyph())
        assertTrue(onDisk("My Font", "new.json").isFile)
        storage.deleteGlyph("new")
        assertFalse(onDisk("My Font", "new.json").exists())
    }

    @Test
    fun aProjectInAFolderOpensAgainAfterARestart() {
        val info = project("Persist")
        storage.saveGlyph("A", glyph(77f))
        storage.moveProjectToFolder(info.id, tree())
        val again = AndroidStorage(context)
        assertNotNull(again.registry.find(info.id)!!.location)
        assertNull(again.openProblem(info.id))
        again.useProject(info.id)
        assertEquals(77f, again.loadGlyph("A")!!.corners["regular"]!!.width)
        assertEquals("Documents", again.newProjectsFolder!!.label)
    }

    @Test
    fun savesMadeWhileCopyingAreCopiedToo() {
        val info = project("Busy")
        for (n in listOf("A", "B", "C")) storage.saveGlyph(n, glyph(10f))
        val reachedCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeDocs.beforeCreateFile = { reachedCopy.countDown(); release.await(10, TimeUnit.SECONDS) }
        var failure: Throwable? = null
        val mover = thread { try { storage.moveProjectToFolder(info.id, tree()) } catch (t: Throwable) { failure = t } }
        assertTrue(reachedCopy.await(10, TimeUnit.SECONDS))
        // mid-copy: an edit, a new glyph, and a deletion, all made in the project that is still in app storage
        storage.saveGlyph("A", glyph(999f))
        storage.saveGlyph("D", glyph(44f))
        storage.deleteGlyph("B")
        FakeDocs.beforeCreateFile = null
        release.countDown()
        mover.join(20_000)
        assertNull(failure)
        assertEquals(listOf("A", "C", "D"), storage.listGlyphNames())
        assertEquals(999f, storage.loadGlyph("A")!!.corners["regular"]!!.width)
        assertEquals(44f, storage.loadGlyph("D")!!.corners["regular"]!!.width)
        assertFalse(onDisk("Busy", "%42.json").exists())
    }

    @Test
    fun aFailedMoveLeavesTheProjectWhereItWasAndCleansUp() {
        val info = project("Fails")
        storage.saveGlyph("A", glyph())
        FakeDocs.beforeCreateFile = { throw IllegalStateException("provider went away") }
        val failure = runCatching { storage.moveProjectToFolder(info.id, tree()) }.exceptionOrNull()
        FakeDocs.beforeCreateFile = null
        assertNotNull(failure)
        assertNull(storage.registry.find(info.id)!!.location)
        assertFalse(onDisk("Fails").exists(), "the half-made folder is removed")
        assertEquals(listOf("A"), storage.listGlyphNames())
        storage.saveGlyph("B", glyph()) // still saving in app storage
        assertEquals(listOf("A", "B"), storage.listGlyphNames())
    }

    @Test
    fun movingBackBringsEverythingHomeAndLeavesTheFolderAlone() {
        val info = project("Roundtrip")
        storage.saveGlyph("A", glyph(5f))
        storage.moveProjectToFolder(info.id, tree())
        storage.saveGlyph("B", glyph(6f))
        storage.moveProjectToAppStorage(info.id)
        assertNull(storage.registry.find(info.id)!!.location)
        assertEquals(listOf("A", "B"), storage.listGlyphNames())
        assertTrue(File(context.filesDir, "morphont-projects/${info.id}").isDirectory)
        assertTrue(onDisk("Roundtrip", "%41.json").isFile, "the folder keeps its copy")
        storage.saveGlyph("C", glyph())
        assertFalse(onDisk("Roundtrip", "c.json").exists())
        assertEquals(listOf("A", "B", "C"), storage.listGlyphNames())
    }

    // ---- opening a folder, new projects, deleting

    @Test
    fun aFolderOfGlyphsBecomesAProject() {
        val (id, _) = DocumentTree.createFolder(context.contentResolver, tree(), "root", "Elsewhere")
        val folder = TreeGlyphFolder(context.contentResolver, tree(), id)
        folder.write("A", ProjectCodec.encodeGlyph(glyph(8f)).encodeToByteArray())
        val sub = DocumentsContract.buildTreeDocumentUri(FakeDocs.AUTH, "root/Elsewhere")
        val info = storage.adoptFolder(sub)
        assertEquals("Elsewhere", info.name)
        assertEquals("Elsewhere", info.location!!.label)
        assertEquals(info.id, storage.adoptFolder(sub).id, "the same folder is the same project")
        storage.useProject(info.id)
        assertEquals(listOf("A"), storage.listGlyphNames())
        assertEquals(8f, storage.loadGlyph("A")!!.corners["regular"]!!.width)
    }

    @Test
    fun newProjectsStartInTheChosenFolder() {
        val first = project("First")
        storage.moveProjectToFolder(first.id, tree())
        val second = storage.registry.create("Second")
        assertNull(storage.placeInNewProjectsFolder(second))
        assertEquals("Documents/Second", storage.registry.find(second.id)!!.location!!.label)
        assertTrue(onDisk("Second").isDirectory)
        storage.useProject(second.id)
        storage.saveGlyph("A", glyph())
        assertTrue(onDisk("Second", "%41.json").isFile)
        storage.useAppStorageForNewProjects()
        val third = storage.registry.create("Third")
        assertNull(storage.placeInNewProjectsFolder(third))
        assertNull(storage.registry.find(third.id)!!.location)
    }

    @Test
    fun aProjectWhoseFolderIsGoneSaysSoInsteadOfOpening() {
        val info = project("Lost")
        storage.moveProjectToFolder(info.id, tree())
        onDisk("Lost").deleteRecursively()
        val problem = storage.openProblem(info.id)
        assertNotNull(problem)
        assertTrue("Documents/Lost" in problem)
    }

    @Test
    fun forgettingAProjectInAFolderLeavesItsFiles() {
        val info = project("Keep")
        storage.saveGlyph("A", glyph())
        storage.moveProjectToFolder(info.id, tree())
        storage.deleteProjectFiles(info.id)
        storage.registry.delete(info.id)
        assertTrue(onDisk("Keep", "%41.json").isFile)
    }

    @Test
    fun importingAProjectIntoAFolderReplacesWhatWasThere() {
        val info = project("Import")
        storage.saveGlyph("old", glyph())
        storage.moveProjectToFolder(info.id, tree())
        val names = storage.importProjectText(ProjectCodec.encodeProject(mapOf("N" to glyph(), "M" to glyph())))
        assertEquals(listOf("M", "N"), names.sorted())
        assertEquals(listOf("M", "N"), storage.listGlyphNames())
        assertFalse(onDisk("Import", "old.json").exists())
    }

    @Test
    fun importingFontGlyphsNeverOverwritesInAFolder() {
        val info = project("Font")
        storage.saveGlyph("A", glyph(1f))
        storage.moveProjectToFolder(info.id, tree())
        val collisions = storage.saveNewGlyphs(mapOf("A" to glyph(2f), "B" to glyph(3f)), info.id)
        assertEquals(listOf("A"), collisions)
        assertEquals(1f, storage.loadGlyph("A")!!.corners["regular"]!!.width)
        assertEquals(3f, storage.loadGlyph("B")!!.corners["regular"]!!.width)
    }
}
