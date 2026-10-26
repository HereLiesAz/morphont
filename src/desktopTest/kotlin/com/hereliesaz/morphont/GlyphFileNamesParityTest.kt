package com.hereliesaz.morphont

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared file-name scheme must stay the one the desktop app already writes, or a folder stops being readable by both. */
class GlyphFileNamesParityTest {
    @Test
    fun matchesTheDesktopSchemeOnEveryNameTried() {
        val random = Random(11)
        val pool = "abcXYZ019 .-_*+%/:é中😀".toList()
        val fixed = listOf("con", "CON", "Com1", "LPT3", "aux.x", "nul", "PRN", "A", "a", ".", "..", "uni00e9")
        // The pool holds the two halves of the emoji separately, so some names have lone surrogates: both sides write those as "?".
        val generated = List(3000) { String(CharArray(random.nextInt(1, 8)) { pool[random.nextInt(pool.size)] }) }
        for (name in fixed + generated) {
            assertEquals(desktopGlyphFileName(name), glyphFileName(name), "encode $name")
            assertEquals(desktopGlyphName(desktopGlyphFileName(name)), glyphNameOfFile(glyphFileName(name)), "decode $name")
        }
    }
}
