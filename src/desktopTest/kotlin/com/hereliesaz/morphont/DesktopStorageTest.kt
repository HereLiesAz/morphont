package com.hereliesaz.morphont

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStorageTest {
    @Test
    fun failedProjectStageKeepsPreviousProjectFiles() {
        val root = Files.createTempDirectory("morphont-storage-test-")
        try {
            val live = Files.createDirectory(root.resolve("glyphs"))
            val old = live.resolve("A.json")
            Files.writeString(old, "previous, intentionally not valid JSON")
            assertFailsWith<IllegalArgumentException> {
                replaceProjectDirectory(live.toFile(), mapOf("" to Glyph()))
            }
            assertEquals("previous, intentionally not valid JSON", Files.readString(old))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun strayStagingLeftoversAreSweptButFreshOnesAndBackupsStay() {
        val root = Files.createTempDirectory("morphont-sweep-test-")
        try {
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            val oldStage = Files.createDirectory(root.resolve("morphont-stage-old")).toFile().apply { setLastModified(now - 2 * day) }
            val freshStage = Files.createDirectory(root.resolve("morphont-stage-fresh")).toFile()
            val backup = Files.createDirectory(root.resolve("morphont-backup-p-x")).toFile().apply { setLastModified(now - 2 * day) }
            val project = Files.createDirectory(root.resolve("p1"))
            val oldTmp = Files.createFile(project.resolve("morphont-glyph-1.tmp")).toFile().apply { setLastModified(now - 2 * day) }
            val freshTmp = Files.createFile(project.resolve("morphont-glyph-2.tmp")).toFile()
            val glyph = Files.createFile(project.resolve("A.json")).toFile().apply { setLastModified(now - 2 * day) }
            sweepStrays(root.toFile(), now)
            assertFalse(oldStage.exists()); assertFalse(oldTmp.exists())
            assertTrue(freshStage.exists()); assertTrue(freshTmp.exists()); assertTrue(backup.exists()); assertTrue(glyph.exists())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun interruptedSwapRecoversPriorDirectoryAtStartup() {
        val root = Files.createTempDirectory("morphont-recovery-test-")
        try {
            val backup = Files.createDirectory(root.resolve("morphont-backup-glyphs-crash"))
            Files.writeString(backup.resolve("Existing.json"), "preserve this glyph")
            val live = root.resolve("glyphs")
            assertFalse(Files.exists(live))
            recoverProjectDirectory(root.toFile(), live.toFile())
            assertEquals("preserve this glyph", Files.readString(live.resolve("Existing.json")))
            assertFalse(Files.exists(backup))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun anotherProjectsBackupIsNeverAdopted() {
        val root = Files.createTempDirectory("morphont-recovery-test-")
        try {
            // Project A crashed mid-swap; project C is new and has no folder yet.
            val aBackup = Files.createDirectory(root.resolve("morphont-backup-A-crash"))
            Files.writeString(aBackup.resolve("Existing.json"), "A's glyph")
            val c = root.resolve("C")
            recoverProjectDirectory(root.toFile(), c.toFile())
            assertFalse(Files.exists(c), "C must not receive A's glyphs")
            recoverProjectDirectory(root.toFile(), root.resolve("A").toFile())
            assertEquals("A's glyph", Files.readString(root.resolve("A").resolve("Existing.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun successfulReplacementDropsObsoleteGlyphsAndKeepsNewOnes() {
        val root = Files.createTempDirectory("morphont-storage-test-")
        try {
            val live = Files.createDirectory(root.resolve("glyphs"))
            Files.writeString(live.resolve("Old.json"), "old")
            val names = replaceProjectDirectory(live.toFile(), mapOf(
                "*" to Glyph(), "New" to Glyph(),
            ))
            assertEquals(listOf("*", "New"), names)
            assertFalse(Files.exists(live.resolve("Old.json")))
            assertTrue(Files.exists(live.resolve("%2A.json")))
            assertTrue(Files.exists(live.resolve("%4Eew.json"))) // capitals are escaped: see caseVariantsGetDistinctFiles
            assertTrue(Files.exists(live.resolve("%2A.json")) &&
                ProjectCodec.decodeGlyph(Files.readString(live.resolve("%2A.json"))) != null)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun currentUnsavedGlyphWinsWhenExporting() {
        val previous = Glyph(corners = mutableMapOf("regular" to GlyphCorner(123f)))
        val edited = Glyph(corners = mutableMapOf("regular" to GlyphCorner(456f)))
        val exported = projectForExport(mapOf("A" to previous, "B" to Glyph()), "A" to edited)
        assertEquals(456f, exported.getValue("A").corners.getValue("regular").width)
        assertEquals(2, exported.size)
    }

    @Test
    fun caseVariantsGetDistinctFilesEvenOnCaseInsensitiveFileSystems() {
        val names = listOf("A", "a", "Ab", "aB", "con")
        val folded = names.map { desktopGlyphFileName(it).lowercase() }
        assertEquals(names.size, folded.toSet().size)
        for (n in names) assertEquals(n, desktopGlyphName(desktopGlyphFileName(n)))
        val root = Files.createTempDirectory("morphont-case-test-")
        try {
            val live = Files.createDirectory(root.resolve("glyphs"))
            val upper = Glyph(corners = mutableMapOf("regular" to GlyphCorner(111f)))
            val lower = Glyph(corners = mutableMapOf("regular" to GlyphCorner(222f)))
            replaceProjectDirectory(live.toFile(), mapOf("A" to upper, "a" to lower))
            val files = live.toFile().listFiles().orEmpty().map { it.name.lowercase() }
            assertEquals(2, files.toSet().size)
            assertEquals(2, files.size)
            val read = { n: String -> ProjectCodec.decodeGlyph(Files.readString(live.resolve(desktopGlyphFileName(n)))) }
            assertEquals(111f, read("A").corners.getValue("regular").width)
            assertEquals(222f, read("a").corners.getValue("regular").width)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun legacyCapitalGlyphFilesAreRenamedOnMigration() {
        val root = Files.createTempDirectory("morphont-migrate-test-")
        try {
            Files.writeString(root.resolve("A.json"), "legacy")
            Files.writeString(root.resolve("%2A.json"), "star")
            migrateLegacyGlyphFileNames(root.toFile())
            assertFalse(root.toFile().list().orEmpty().contains("A.json"))
            assertTrue(root.toFile().list().orEmpty().contains(desktopGlyphFileName("A")))
            assertEquals("legacy", Files.readString(root.resolve(desktopGlyphFileName("A"))))
            assertEquals("star", Files.readString(root.resolve("%2A.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun unmigratedLegacyFileIsStillResolved() {
        val root = Files.createTempDirectory("morphont-resolve-test-")
        try {
            Files.writeString(root.resolve("A.json"), "legacy")
            assertEquals("A.json", resolveGlyphFile(root.toFile(), "A").name)
            Files.writeString(root.resolve(desktopGlyphFileName("A")), "new")
            assertEquals(desktopGlyphFileName("A"), resolveGlyphFile(root.toFile(), "A").name)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun unreadableGlyphIsSkippedNotFatalForExport() {
        val good = Glyph()
        val (readable, skipped) = readableGlyphs(listOf("ok", "broken")) { if (it == "ok") good else null }
        assertEquals(setOf("ok"), readable.keys)
        assertEquals(listOf("broken"), skipped)
    }
}
