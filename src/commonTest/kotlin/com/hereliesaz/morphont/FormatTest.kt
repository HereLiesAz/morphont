package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun productionNamesDisplayAsUnicodeCharacters() {
        assertEquals("A", displayGlyphName("uni0041"))
        assertEquals("A", displayGlyphName("unicode0041"))
        assertEquals("é", displayGlyphName("uni00E9"))
        assertEquals("!", displayGlyphName("uni0021"))
        assertEquals("😀", displayGlyphName("u1F600"))
        assertEquals("😀", displayGlyphName("uni1F600"))
        assertEquals("😀", displayGlyphName("unicode1F600"))
    }

    @Test
    fun nonCharacterAndCustomGlyphNamesStayLiteral() {
        assertEquals(".notdef", displayGlyphName(".notdef"))
        assertEquals("uni0041.alt", displayGlyphName("uni0041.alt"))
        assertEquals("uni0020", displayGlyphName("uni0020"))
        assertEquals("uni000A", displayGlyphName("uni000A"))
        assertEquals("uniD800", displayGlyphName("uniD800"))
        assertEquals("uni110000", displayGlyphName("uni110000"))
        assertEquals("uniFE0F", displayGlyphName("uniFE0F"))
        assertEquals("unicodeZZZZ", displayGlyphName("unicodeZZZZ"))
        assertEquals("A", displayGlyphName("A"))
    }
}
