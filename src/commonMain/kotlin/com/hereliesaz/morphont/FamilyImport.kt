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
        // Failure in one glyph must not abort the rest of the font. In
        // particular, malformed glyf/gvar offsets previously surfaced as a
        // raw "Index ... out of bounds" and discarded the entire import.
        try {
            val corners = LinkedHashMap<String, GlyphCorner>()
            val defaultWidth = font.advanceWidth(codepoint) ?: 0f
            val sharedOutline = if (defaultOnly) font.outlineAt(codepoint, emptyMap()) else null
            for (anchor in ANCHORS) {
                val outline = if (defaultOnly) sharedOutline else font.outlineAt(codepoint, coordsPerAnchor.getValue(anchor))
                requireNotNull(outline) { "unsupported or unresolved composite" }
                // Each anchor keeps the advance the font gives it there (a wide master is wider), not one default.
                val width = if (defaultOnly) defaultWidth else font.advanceWidthAt(codepoint, coordsPerAnchor.getValue(anchor)) ?: defaultWidth
                corners[anchor] = GlyphCorner(width, outline.map { it.deepCopy() }.toMutableList())
            }
            glyphs[codepoint.toChar().toString()] = Glyph(corners, metrics = font.metrics, outlineFormat = OUTLINE_CUBIC)
        } catch (e: RuntimeException) {
            skipped.add(codepoint to (e.message ?: "invalid glyph data"))
        }
    }
    require(glyphs.isNotEmpty()) {
        "No glyphs could be imported: ${skipped.take(3).joinToString { (cp, reason) -> "U+${cp.toString(16).uppercase()}: $reason" }}"
    }
    return FamilyImportResult(glyphs, skipped)
}
