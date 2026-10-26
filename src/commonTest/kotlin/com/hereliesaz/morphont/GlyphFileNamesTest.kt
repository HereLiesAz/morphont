package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GlyphFileNamesTest {
    private val names = listOf(
        "a", "A", "b", "B", "0", "é", "é", "中", "😀", "uni0041", "A.alt", "a.alt",
        "space bar", "star*", "*", "con", "CON", "Com1", "lpt9", "aux.alt", "nul", "a+b", "a%b", "a/b", "a:b", "-", "_x_", "..",
    )

    @Test
    fun everyNameComesBackAsItself() {
        for (n in names) assertEquals(n, glyphNameOfFile(glyphFileName(n)), n)
    }

    @Test
    fun namesThatDifferOnlyInCaseGetFilesThatDoNot() {
        for ((a, b) in listOf("a" to "A", "con" to "CON", "x.Alt" to "x.alt")) {
            assertNotEquals(glyphFileName(a).lowercase(), glyphFileName(b).lowercase(), "$a vs $b")
        }
    }

    @Test
    fun fileNamesAreSafeOnEveryFileSystem() {
        val unsafe = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
        for (n in names) {
            val f = glyphFileName(n)
            assertNull(unsafe.find(f), "$n -> $f")
            assertEquals(f, f.filter { it.code in 0x21..0x7E }, "plain ASCII: $f")
        }
    }

    @Test
    fun knownNamesStayWhatTheyAre() {
        assertEquals("%41.json", glyphFileName("A"))
        assertEquals("a.json", glyphFileName("a"))
        assertEquals("%C3%A9.json", glyphFileName("é"))
        assertEquals("space+bar.json", glyphFileName("space bar"))
        assertEquals("%F0%9F%98%80.json", glyphFileName("😀"))
        assertEquals("%63on.json", glyphFileName("con"))
        assertEquals("%2A.json", glyphFileName("*"))
    }

    @Test
    fun otherFilesAreNotGlyphs() {
        assertNull(glyphNameOfFile("notes.txt"))
        assertNull(glyphNameOfFile(".json"))
        assertNull(glyphNameOfFile("%4.json"))
        assertNull(glyphNameOfFile("%zz.json"))
    }
}
