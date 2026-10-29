package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectRegistryTest {
    private class Disk { var text: String? = null }

    private fun registry(disk: Disk = Disk()) = ProjectRegistry({ disk.text }, { disk.text = it })

    @Test fun startsEmpty() {
        val r = registry()
        assertEquals(emptyList(), r.projects)
        assertNull(r.last)
    }

    @Test fun anUnreadableListIsSetAsideNotOverwritten() {
        val disk = Disk().apply { text = "{ not json" }
        var kept: String? = null
        val r = ProjectRegistry({ disk.text }, { disk.text = it }, keepDamaged = { kept = it })
        assertEquals("{ not json", kept)
        assertNotNull(r.loadProblem)
        assertEquals(emptyList(), r.projects)
        r.create("After")
        assertEquals("{ not json", kept) // still the damaged text, not the new list
    }

    @Test fun aReadableListHasNoLoadProblem() {
        val disk = Disk()
        registry(disk).create("One")
        assertNull(registry(disk).loadProblem)
    }

    @Test fun createTrimsAndBecomesLast() {
        val r = registry()
        val a = r.create("  Azrienoch  ")
        assertEquals("Azrienoch", a.name)
        assertEquals(a, r.last)
    }

    @Test fun survivesARestart() {
        val disk = Disk()
        val a = registry(disk).create("One")
        val b = registry(disk).create("Two")
        val again = registry(disk)
        assertEquals(listOf(b, a), again.projects)
        assertEquals(b, again.last)
    }

    @Test fun openMovesToFrontAndRemembersIt() {
        val r = registry()
        val a = r.create("One"); val b = r.create("Two")
        assertEquals(listOf(b, a), r.projects)
        r.open(a.id)
        assertEquals(listOf(a, b), r.projects)
        assertEquals(a, r.last)
    }

    @Test fun openUnknownIdChangesNothing() {
        val r = registry()
        val a = r.create("One")
        assertNull(r.open("nope"))
        assertEquals(a, r.last)
    }

    @Test fun renameKeepsIdAndOrder() {
        val r = registry()
        val a = r.create("One"); val b = r.create("Two")
        val renamed = assertNotNull(r.rename(a.id, " Uno "))
        assertEquals(a.id, renamed.id)
        assertEquals(listOf(b, renamed), r.projects)
        assertEquals("Uno", r.find(a.id)?.name)
    }

    @Test fun deletingTheLastProjectForgetsIt() {
        val r = registry()
        val a = r.create("One"); val b = r.create("Two")
        r.delete(b.id)
        assertEquals(listOf(a), r.projects)
        assertNull(r.last)
        r.delete(a.id)
        assertEquals(emptyList(), r.projects)
    }

    @Test fun deletingAnotherProjectKeepsTheLastOne() {
        val r = registry()
        val a = r.create("One"); val b = r.create("Two")
        r.delete(a.id)
        assertEquals(b, r.last)
    }

    @Test fun namesMustBeFilledAndUniqueIgnoringCase() {
        val r = registry()
        val a = r.create("Serif")
        assertEquals("Type a name first.", r.nameProblem("   "))
        assertEquals("A project named \"serif\" already exists.", r.nameProblem("serif"))
        assertNull(r.nameProblem("serif", exceptId = a.id)) // renaming to its own name is fine
        assertNull(r.nameProblem("Sans"))
    }

    @Test fun adoptingStorageKeepsItsId() {
        val r = registry()
        val info = r.create("Untitled", id = "legacy")
        assertEquals("legacy", info.id)
        assertEquals(info, r.find("legacy"))
    }

    @Test fun unreadableRegistryIsTreatedAsEmpty() {
        val disk = Disk().apply { text = "{ not json" }
        assertEquals(emptyList(), registry(disk).projects)
    }

    @Test fun idsAreUnique() {
        val r = registry()
        val ids = (1..50).map { r.create("P$it").id }
        assertEquals(50, ids.toSet().size)
    }

    private val folder = ProjectLocation("content://tree/primary%3ADocuments", "primary:Documents/One", "Documents/One")

    @Test fun aProjectRemembersWhereItIsKept() {
        val disk = Disk()
        val r = registry(disk)
        val a = r.create("One")
        assertNull(a.location)
        assertEquals("App storage", a.locationLabel)
        val moved = r.setLocation(a.id, folder)
        assertEquals(folder, moved?.location)
        assertEquals("Documents/One", moved?.locationLabel)
        assertEquals(folder, registry(disk).find(a.id)?.location)
        assertNull(r.setLocation(a.id, null)?.location)
        assertNull(registry(disk).find(a.id)?.location)
    }

    @Test fun renamingAndReopeningKeepTheLocation() {
        val r = registry()
        val a = r.create("One"); r.create("Two")
        r.setLocation(a.id, folder)
        r.rename(a.id, "Uno")
        r.open(a.id)
        assertEquals(folder, r.find(a.id)?.location)
        assertEquals("Uno", r.find(a.id)?.name)
    }

    @Test fun setLocationOnUnknownIdChangesNothing() {
        val r = registry()
        r.create("One")
        assertNull(r.setLocation("nope", folder))
        assertNull(r.projects.single().location)
    }

    @Test fun theFolderForNewProjectsIsKeptThroughEveryOtherChange() {
        val disk = Disk()
        val r = registry(disk)
        val home = ProjectLocation("content://tree/primary%3ADocuments", "primary:Documents", "Documents")
        r.setNewProjectsIn(home)
        val a = r.create("One")
        r.open(a.id)
        r.rename(a.id, "Uno")
        r.delete(a.id)
        assertEquals(home, registry(disk).newProjectsIn)
        r.setNewProjectsIn(null)
        assertNull(registry(disk).newProjectsIn)
    }

    @Test fun anOlderRegistryFileWithoutLocationsStillLoads() {
        val disk = Disk()
        disk.text = """{"projects":[{"id":"x1","name":"Old"}],"lastId":"x1"}"""
        val r = registry(disk)
        assertEquals("Old", r.last?.name)
        assertNull(r.last?.location)
        assertNull(r.newProjectsIn)
    }
}
