package com.hereliesaz.morphont

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Packages built with the JDK's own zip and deflate, so the reader is checked against a real encoder. */
class AzpTest {
    private fun sha(b: ByteArray) = "sha256-" + MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun zip(entries: Map<String, ByteArray>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val ttf = byteArrayOf(0, 1, 0, 0) + ByteArray(2000) { (it % 7).toByte() }

    private fun fontPackage(font: ByteArray = ttf, tamper: Boolean = false, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val license = "OFL-1.1".encodeToByteArray()
        val manifest = """
            {"azphalt":"0.1","id":"com.acme.fancy","name":"Fancy","version":"1.0.0","kind":"font","license":"OFL-1.1","compat":">=0.1",
             "files":{"fonts/Fancy.ttf":"${sha(font)}","LICENSE":"${sha(license)}"},
             "font":{"family":"Fancy","faces":[{"path":"fonts/Fancy.ttf","format":"ttf","variable":true,
               "axes":[{"tag":"wght","min":100,"default":400,"max":900}]}]}}
        """.trimIndent().encodeToByteArray()
        val body = if (tamper) font.copyOf().also { it[10] = 99 } else font
        return zip(mapOf("manifest.json" to manifest, "LICENSE" to license, "fonts/Fancy.ttf" to body) + extra)
    }

    @Test
    fun inflateMatchesTheJdk() {
        val rnd = Random(7)
        for (size in listOf(0, 1, 100, 70_000, 300_000)) {
            // Mixed: compressible runs (back-references, dynamic tables) and noise (literals).
            val data = ByteArray(size) { if (it % 1000 < 600) (it % 13).toByte() else rnd.nextInt().toByte() }
            for (level in listOf(0, 1, 9)) {
                val d = Deflater(level, true).apply { setInput(data); finish() }
                val buf = ByteArray(size + 1024)
                val n = d.deflate(buf)
                assertContentEquals(data, Inflate.inflate(buf.copyOf(n)), "size $size level $level")
            }
        }
    }

    @Test
    fun readsAVerifiedFontPackage() {
        for (stored in listOf(false, true)) {
            val pkg = Azp.read(if (stored) fontPackageStored() else fontPackage())
            assertEquals("font", pkg.manifest.kind)
            assertEquals("Fancy", pkg.manifest.font!!.family)
            assertEquals(listOf("wght"), pkg.manifest.font.faces.single().axes.map { it.tag })
            assertContentEquals(ttf, pkg.files.getValue("fonts/Fancy.ttf"))
        }
    }

    private fun fontPackageStored(): ByteArray {
        val license = "OFL-1.1".encodeToByteArray()
        val manifest = """{"id":"com.acme.fancy","version":"1.0.0","kind":"font","files":{"fonts/Fancy.ttf":"${sha(ttf)}","LICENSE":"${sha(license)}"},"font":{"family":"Fancy","faces":[{"path":"fonts/Fancy.ttf","format":"ttf","variable":true,"axes":[{"tag":"wght","min":100,"default":400,"max":900}]}]}}""".encodeToByteArray()
        return zip(mapOf("manifest.json" to manifest, "LICENSE" to license, "fonts/Fancy.ttf" to ttf), stored = true)
    }

    @Test
    fun rejectsTamperingAndUnsafeFiles() {
        assertFailsWith<IllegalArgumentException> { Azp.read(fontPackage(tamper = true)) }.also { assertEquals("Digest mismatch: fonts/Fancy.ttf", it.message) }
        assertFailsWith<IllegalArgumentException> { Azp.read(fontPackage(extra = mapOf("fonts/sneaky.ttf" to ttf))) }
        assertFailsWith<IllegalArgumentException> { Azp.read(fontPackage(extra = mapOf("../evil" to ttf))) }
        assertFailsWith<IllegalArgumentException> { Azp.read(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { Azp.read(fontPackage().copyOf(300)) }
    }

    private class MemoryBlobs : BlobStore {
        val map = mutableMapOf<String, ByteArray>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, bytes: ByteArray) { map[key] = bytes }
        override suspend fun delete(key: String) { map.remove(key) }
    }

    @Test
    fun shelfInstallsReplacesAndRemoves() = runBlocking {
        val blobs = MemoryBlobs()
        val shelf = FontShelf(blobs)
        val installed = shelf.install(Azp.read(fontPackage()))
        assertEquals("Fancy", installed.family)
        assertContentEquals(ttf, shelf.bytes(installed.faces.single()))
        // A fresh shelf over the same store sees it: the index is persisted.
        assertEquals(listOf("com.acme.fancy"), FontShelf(blobs).list().map { it.id })
        shelf.install(Azp.read(fontPackage()))
        assertEquals(1, shelf.list().size)
        shelf.remove("com.acme.fancy")
        assertEquals(emptyList<InstalledFont>(), FontShelf(blobs).list())
        assertEquals(setOf("index.json"), blobs.map.keys)
    }

    @Test
    fun clientParsesListingsAndVerifiesDownloads() = runBlocking {
        val listing = """{"packages":[
            {"id":"com.acme.fancy","name":"Fancy","version":"1.0.0","latest":"1.0.0","kind":"font","author":"Acme",
             "font":{"family":"Fancy","variable":true,"axes":["wght","wdth"],"faces":2}},
            {"id":"com.acme.inline","name":"Inline","version":"2.0.0","kind":"font",
             "manifest":{"font":{"family":"Inline Sans","faces":[{"path":"fonts/a.ttf","format":"ttf","variable":true,"axes":[{"tag":"wght","min":1,"default":1,"max":2}]}]}}},
            {"id":"x.brush","name":"Brush","version":"1.0.0","kind":"asset"},
            {"id":"com.acme.paid","name":"Paid","version":"1.0.0","kind":"font","priceStatus":"paid"}
        ],"total":3}"""
        val urls = mutableListOf<String>()
        val client = FontStoreClient({ url ->
            urls += url
            if (url.contains("/download")) fontPackage() else listing.encodeToByteArray()
        }, "https://store.test")
        val fonts = client.search("fan cy")
        assertEquals("https://store.test/packages?kind=font&q=fan%20cy", urls.last())
        assertEquals(listOf("com.acme.fancy", "com.acme.inline", "com.acme.paid"), fonts.map { it.id })
        assertEquals(listOf("wght", "wdth"), fonts[0].axes)
        assertEquals("Inline Sans", fonts[1].family)
        assertEquals(listOf("wght"), fonts[1].axes)
        assertEquals(true, fonts[2].paid)
        val pkg = client.download(fonts[0])
        assertEquals("https://store.test/packages/com.acme.fancy/versions/1.0.0/download", urls.last())
        assertEquals("Fancy", pkg.manifest.font!!.family)
        assertFailsWith<IllegalArgumentException> { client.download(fonts[2]) }
        assertFailsWith<IllegalArgumentException> { client.download(fonts[1]) } // the store sent com.acme.fancy
        Unit
    }

    @Test
    fun fontRulesMatchAzphalts() {
        Azp.validateFont(Azp.read(fontPackage()))
        // A face that isn't the format it claims, a stray file, and a duplicate manifest key are all refused.
        val notTtf = assertFailsWith<IllegalArgumentException> { Azp.validateFont(Azp.read(fontPackage(font = byteArrayOf(1, 2, 3, 4, 5)))) }
        assertTrue("not a ttf font" in notTtf.message!!)
        val license = "OFL-1.1".encodeToByteArray()
        val stray = "evil".encodeToByteArray()
        val manifest = """{"id":"com.acme.fancy","version":"1.0.0","kind":"font","files":{"fonts/Fancy.ttf":"${sha(ttf)}","LICENSE":"${sha(license)}","code/x.js":"${sha(stray)}"},"font":{"family":"Fancy","faces":[{"path":"fonts/Fancy.ttf","format":"ttf"}]}}""".encodeToByteArray()
        val withCode = Azp.read(zip(mapOf("manifest.json" to manifest, "LICENSE" to license, "fonts/Fancy.ttf" to ttf, "code/x.js" to stray)))
        assertFailsWith<IllegalArgumentException> { Azp.validateFont(withCode) }
        assertEquals("kind", Azp.duplicateJsonKey("""{"kind":"font","a":{"kind":1},"kind":"code"}"""))
        assertEquals(null, Azp.duplicateJsonKey("""{"a":"x\"kind","kind":1,"b":[{"kind":2},{"kind":3}]}"""))
    }

    @Test
    fun aHugeLocalHeaderOffsetIsAReadableError() {
        val good = fontPackage()
        // Point the first central-directory entry's local header offset at 0xFFFFFF00.
        val eocd = (good.size - 22 downTo 0).first { good[it] == 0x50.toByte() && good[it + 1] == 0x4b.toByte() && good[it + 2] == 5.toByte() && good[it + 3] == 6.toByte() }
        val cd = ((good[eocd + 16].toInt() and 0xff) or ((good[eocd + 17].toInt() and 0xff) shl 8) or ((good[eocd + 18].toInt() and 0xff) shl 16) or ((good[eocd + 19].toInt() and 0xff) shl 24))
        val bad = good.copyOf().also { b -> b[cd + 42] = 0; b[cd + 43] = 0xFF.toByte(); b[cd + 44] = 0xFF.toByte(); b[cd + 45] = 0xFF.toByte() }
        assertFailsWith<IllegalArgumentException> { Azp.read(bad) }
    }

    private class FlakyBlobs(val inner: MemoryBlobs) : BlobStore {
        var failReads = false
        override suspend fun get(key: String): ByteArray? { if (failReads) error("disk busy"); return inner.get(key) }
        override suspend fun put(key: String, bytes: ByteArray) = inner.put(key, bytes)
        override suspend fun delete(key: String) = inner.delete(key)
    }

    @Test
    fun anUnreadableIndexIsNeverTakenForAnEmptyOne() = runBlocking<Unit> {
        val blobs = MemoryBlobs()
        FontShelf(blobs).install(Azp.read(fontPackage()))
        val flaky = FlakyBlobs(blobs).apply { failReads = true }
        assertFailsWith<IllegalStateException> { FontShelf(flaky).list() }
        assertFailsWith<IllegalStateException> { FontShelf(flaky).install(Azp.read(fontPackage())) }
        assertEquals(1, FontShelf(blobs).list().size, "the real list is intact")
        // A damaged index is kept aside, not silently dropped.
        blobs.map["index.json"] = "{not json".encodeToByteArray()
        assertEquals(emptyList<InstalledFont>(), FontShelf(blobs).list())
        assertTrue("index-unreadable.json" in blobs.map)
    }

    @Test
    fun anUpdateWritesNewFacesBeforeTheOldOnesGo() = runBlocking<Unit> {
        val blobs = MemoryBlobs()
        val shelf = FontShelf(blobs)
        val v1 = shelf.install(Azp.read(fontPackage()))
        val oldKey = v1.faces.single().key
        assertTrue("/1.0.0/" in oldKey)
        // The new version's faces never overwrite the old version's bytes in place.
        val v2pkg = Azp.read(fontPackage()).let { p -> Azp.Package(p.manifest.copy(version = "1.1.0"), p.files) }
        val v2 = shelf.install(v2pkg)
        assertTrue("/1.1.0/" in v2.faces.single().key)
        assertTrue(oldKey !in blobs.map, "the old face is removed once the index moved on")
    }

    @Test
    fun systemFileNamesStayClearOfTheUsersOwnFonts() {
        assertEquals("com.acme.fancy-Arial.ttf", DesktopFontStore.systemFileName("com.acme.fancy", "Arial.ttf"))
        assertEquals("com.acme.fancy-CON.otf", DesktopFontStore.systemFileName("com.acme.fancy", "fonts/../CON.OTF"))
        assertFailsWith<IllegalArgumentException> { DesktopFontStore.systemFileName("a.b", "payload.exe") }
        assertFailsWith<IllegalArgumentException> { DesktopFontStore.systemFileName("a.b", "Fancy.woff2") }
    }
}
