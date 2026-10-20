package com.hereliesaz.morphont

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectStorageTest {
    private class Disk { var text: String? = null }

    private fun temp(): File = Files.createTempDirectory("morphont-projects-test").toFile()

    private fun registry(disk: Disk = Disk()) = ProjectRegistry({ disk.text }, { disk.text = it })

    private fun glyphFile(folder: File, name: String) =
        File(folder.apply { mkdirs() }, desktopGlyphFileName(name)).apply { writeText(ProjectCodec.encodeGlyph(Glyph())) }

    @Test fun glyphsSavedBeforeProjectsBecomeUntitled() {
        val base = temp(); val root = File(base, "projects")
        glyphFile(File(base, "glyphs"), "a")
        val r = adoptStoredProjects(base, root, registry())
        val project = r.projects.single()
        assertEquals("Untitled", project.name)
        assertTrue(File(root, project.id, ).resolve(desktopGlyphFileName("a")).isFile)
        assertFalse(File(base, "glyphs").exists())
    }

    @Test fun nothingToAdoptLeavesTheRegistryEmpty() {
        val base = temp()
        assertEquals(emptyList(), adoptStoredProjects(base, File(base, "projects"), registry()).projects)
    }

    @Test fun adoptingTwiceDoesNotDuplicate() {
        val base = temp(); val root = File(base, "projects"); val disk = Disk()
        glyphFile(File(base, "glyphs"), "a")
        adoptStoredProjects(base, root, registry(disk))
        assertEquals(1, adoptStoredProjects(base, root, registry(disk)).projects.size)
    }

    @Test fun anUnregisteredFolderWithGlyphsIsRecoveredNotOrphaned() {
        val base = temp(); val root = File(base, "projects")
        glyphFile(File(root, "abc123"), "x")
        val project = adoptStoredProjects(base, root, registry()).projects.single()
        assertEquals("abc123", project.id)
        assertEquals("Recovered abc123", project.name)
    }

    @Test fun emptyFoldersAndSwapScratchDirsAreIgnored() {
        val base = temp(); val root = File(base, "projects")
        File(root, "empty").mkdirs()
        glyphFile(File(root, "morphont-backup-xyz"), "x")
        assertEquals(emptyList(), adoptStoredProjects(base, root, registry()).projects)
    }

    @Test fun recoveredNamesNeverCollide() {
        val r = registry()
        r.create("Untitled")
        assertEquals("Untitled 2", unusedProjectName(r, "Untitled"))
        r.create("Untitled 2")
        assertEquals("Untitled 3", unusedProjectName(r, "Untitled"))
        assertNotNull(r.find(r.projects.first().id))
    }
}
