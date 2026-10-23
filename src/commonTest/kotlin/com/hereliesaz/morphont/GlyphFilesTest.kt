package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlyphFilesTest {
    private val store = mutableMapOf<String, Glyph>()
    private val files = GlyphFiles(
        exists = { it in store },
        load = { store[it] },
        save = { n, g -> store[n] = g },
        delete = { store.remove(it) },
        list = { store.keys.sorted() },
    )

    private fun appWith(vararg names: String): AppState {
        for (n in names) store[n] = Glyph()
        return AppState().apply { glyphNames = files.list() }
    }

    @Test
    fun renamingTheOpenGlyphKeepsItOpenWithItsEdits() {
        val app = appWith("a", "b")
        app.loadGlyph("a", sampleGlyph())
        assertTrue(renameGlyph(app, files, "a", "  c "))
        assertEquals("c", app.currentGlyphName)
        assertEquals(listOf("b", "c"), app.glyphNames)
        assertTrue(store.getValue("c").corners.getValue("regular").contours.isNotEmpty(), "the open edits move with it")
        assertNull(store["a"])
    }

    @Test
    fun renamingOntoAnotherGlyphIsRefused() {
        val app = appWith("a", "b")
        assertFalse(renameGlyph(app, files, "a", "b"))
        assertTrue(app.statusIsError)
        assertEquals(setOf("a", "b"), store.keys)
    }

    @Test
    fun deletingTheOpenGlyphOpensTheNext() {
        val app = appWith("a", "b")
        app.loadGlyph("a", Glyph())
        val opened = mutableListOf<String>()
        deleteGlyph(app, files, "a") { opened += it }
        assertEquals(listOf("b"), app.glyphNames)
        assertEquals(listOf("b"), opened)
        assertNull(app.currentGlyphName, "cleared before the next opens, so autosave can't write it back")
    }

    @Test
    fun deletingAnotherGlyphLeavesTheOpenOneAlone() {
        val app = appWith("a", "b")
        app.loadGlyph("a", Glyph())
        deleteGlyph(app, files, "b") { error("nothing should open") }
        assertEquals("a", app.currentGlyphName)
        assertEquals(listOf("a"), app.glyphNames)
    }
}

class GlyphMoveTest {
    @Test
    fun aStoreThatCanMoveARecordIsAskedToDoItInOneStep() {
        val store = mutableMapOf("a" to Glyph())
        val calls = mutableListOf<String>()
        val files = GlyphFiles(
            exists = { it in store }, load = { store[it] },
            save = { _, _ -> calls += "save" }, delete = { calls += "delete" }, list = { store.keys.sorted() },
            move = { old, name, g -> calls += "move"; store.remove(old); store[name] = g },
        )
        val app = AppState().apply { glyphNames = files.list() }
        assertTrue(renameGlyph(app, files, "a", "b"))
        assertEquals(listOf("move"), calls, "save then delete would be two queued writes")
        assertEquals(listOf("b"), app.glyphNames)
    }
}
