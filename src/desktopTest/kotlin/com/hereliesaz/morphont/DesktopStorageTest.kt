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
    fun interruptedSwapRecoversPriorDirectoryAtStartup() {
        val root = Files.createTempDirectory("morphont-recovery-test-")
        try {
            val backup = Files.createDirectory(root.resolve("morphont-backup-crash"))
            Files.writeString(backup.resolve("Existing.json"), "preserve this glyph")
            val live = root.resolve("glyphs")
            assertFalse(Files.exists(live))
            recoverProjectDirectory(root.toFile(), live.toFile())
            assertEquals("preserve this glyph", Files.readString(live.resolve("Existing.json")))
            assertFalse(Files.exists(backup))
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
            assertTrue(Files.exists(live.resolve("New.json")))
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
}
