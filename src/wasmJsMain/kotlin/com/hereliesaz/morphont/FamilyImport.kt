package com.hereliesaz.morphont

/**
 * Builds a whole roster of glyphs from ONE variable TTF: for every
 * character it defines, extract that character's outline at five points
 * in the font's own (wght, wdth) design space -- extraThin/extraBlack
 * varying weight alone at regular width, condensed/wide varying width
 * alone at regular weight, regular at both axes' default -- matching this
 * app's five-anchor, single-axis-from-center model exactly (see
 * Interpolation.kt). Every other axis the font defines (e.g. Azrienoch's
 * `SERF`) is held at its own default throughout.
 */

data class FamilyImportResult(
    val glyphs: Map<String, Glyph>,
    val skippedCharacters: List<Pair<Int, String>>, // codepoint -> reason
)

private fun axisCoordsFor(anchor: String, axes: List<AxisInfo>): Map<String, Float> {
    val coords = axes.associate { it.tag to it.default }.toMutableMap()
    val wght = axes.find { it.tag == "wght" }
    val wdth = axes.find { it.tag == "wdth" }
    when (anchor) {
        "extraThin" -> wght?.let { coords[it.tag] = it.min }
        "extraBlack" -> wght?.let { coords[it.tag] = it.max }
        "condensed" -> wdth?.let { coords[it.tag] = it.min }
        "wide" -> wdth?.let { coords[it.tag] = it.max }
        "regular" -> {} // already all defaults
    }
    return coords
}

fun buildFamilyFromVariableFont(bytes: ByteArray): FamilyImportResult {
    val font = VariableFont.parse(bytes)
    require(font.axes.any { it.tag == "wght" } && font.axes.any { it.tag == "wdth" }) {
        "This font doesn't define both a 'wght' and a 'wdth' axis -- can't derive all five anchors from it."
    }

    val coordsPerAnchor = ANCHORS.associateWith { axisCoordsFor(it, font.axes) }
    val glyphs = LinkedHashMap<String, Glyph>()
    val skipped = mutableListOf<Pair<Int, String>>()

    for (codepoint in font.characterCodepoints()) {
        if (codepoint > 0xFFFF) {
            skipped.add(codepoint to "astral codepoint, not representable by this app's single-Char glyph naming yet")
            continue
        }
        val corners = LinkedHashMap<String, GlyphCorner>()
        val width = font.advanceWidth(codepoint) ?: 0f
        var ok = true
        for (anchor in ANCHORS) {
            val outline = font.outlineAt(codepoint, coordsPerAnchor.getValue(anchor))
            if (outline == null) { ok = false; break }
            corners[anchor] = GlyphCorner(width, outline.toMutableList())
        }
        if (!ok) {
            skipped.add(codepoint to "composite glyph nested too deeply or uses an unsupported component transform")
            continue
        }
        glyphs[codepoint.toChar().toString()] = Glyph(corners)
    }
    return FamilyImportResult(glyphs, skipped)
}
