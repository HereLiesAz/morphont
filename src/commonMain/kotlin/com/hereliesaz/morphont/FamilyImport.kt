package com.hereliesaz.morphont

/**
 * Builds a whole roster of glyphs from a static or variable TrueType font: for every
 * character it defines, extract that character's outline at every point
 * [Axis.ALL] defines in the font's own design space -- each axis's own
 * lo/hi extremes at every other axis's default, and regular at every
 * axis's default -- matching this app's anchor model exactly (see
 * Interpolation.kt). An axis the font doesn't define at all is simply
 * left out of that character's coordinates (every anchor renders
 * identically along it, contributing no variation); an axis the font
 * defines but Morphont doesn't yet expose is held at its own default
 * throughout, same as before.
 */

data class FamilyImportResult(
    val glyphs: Map<String, Glyph>,
    val skippedCharacters: List<Pair<Int, String>>, // codepoint -> reason
)

private fun axisCoordsFor(anchorName: String, fontAxes: List<AxisInfo>): Map<String, Float> {
    val coords = fontAxes.associate { it.tag to it.default }.toMutableMap()
    for (axis in Axis.ALL) {
        val info = fontAxes.find { it.tag == axis.tag } ?: continue
        when (anchorName) {
            axis.lo -> coords[axis.tag] = info.min
            axis.hi -> coords[axis.tag] = info.max
        }
    }
    return coords
}

fun buildFamilyFromVariableFont(bytes: ByteArray): FamilyImportResult {
    val font = VariableFont.parse(bytes, requireVariable = false)
    // Static TTFs fill every anchor with their default outline. Fonts whose
    // variable axes are unsupported likewise retain their default geometry.

    val coordsPerAnchor = ANCHORS.associateWith { axisCoordsFor(it, font.axes) }
    val defaultOnly = font.axes.none { info -> Axis.ALL.any { it.tag == info.tag } }
    val glyphs = LinkedHashMap<String, Glyph>()
    val skipped = mutableListOf<Pair<Int, String>>()

    for (codepoint in font.characterCodepoints()) {
        if (codepoint > 0xFFFF) {
            skipped.add(codepoint to "astral codepoint, not representable by this app's single-Char glyph naming yet")
            continue
        }
        val corners = LinkedHashMap<String, GlyphCorner>()
        val width = font.advanceWidth(codepoint) ?: 0f
        // A static font needs just one evaluation per codepoint, not one for
        // each of Morphont's many axis anchors. Copy points to keep anchors
        // independently editable even when their initial coordinates match.
        val sharedOutline = if (defaultOnly) font.outlineAt(codepoint, emptyMap()) else null
        var ok = !defaultOnly || sharedOutline != null
        if (ok) for (anchor in ANCHORS) {
            val outline = if (defaultOnly) sharedOutline else font.outlineAt(codepoint, coordsPerAnchor.getValue(anchor))
            if (outline == null) { ok = false; break }
            corners[anchor] = GlyphCorner(width, outline.map { it.deepCopy() }.toMutableList())
        }
        if (!ok) {
            skipped.add(codepoint to "composite glyph nested too deeply or uses an unsupported component transform")
            continue
        }
        glyphs[codepoint.toChar().toString()] = Glyph(corners, metrics = font.metrics, outlineFormat = OUTLINE_CUBIC)
    }
    return FamilyImportResult(glyphs, skipped)
}
