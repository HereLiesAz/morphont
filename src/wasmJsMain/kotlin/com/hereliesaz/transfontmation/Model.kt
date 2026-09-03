package com.hereliesaz.transfontmation

import kotlinx.serialization.Serializable

/**
 * A single glyph point in font-design space (not screen space). [onCurve]
 * true = on-curve; false = an off-curve quadratic control point, using the
 * same TrueType-style convention (runs of consecutive off-curve points
 * imply on-curve midpoints between them) as the original point-editing
 * tools this was ported from.
 */
@Serializable
data class Pt(var x: Float, var y: Float, var onCurve: Boolean, var smooth: Boolean = false) {
    fun copy2() = Pt(x, y, onCurve, smooth)
}

@Serializable
data class ContourData(val points: MutableList<Pt> = mutableListOf()) {
    fun deepCopy() = ContourData(points.map { it.copy2() }.toMutableList())
}

/** One anchor's drawn shape for a glyph: its advance width and contours. */
@Serializable
data class GlyphCorner(
    var width: Float = 500f,
    val contours: MutableList<ContourData> = mutableListOf(),
) {
    fun deepCopy() = GlyphCorner(width, contours.map { it.deepCopy() }.toMutableList())
}

/** The five fixed, hand-editable anchors. Order matters for UI layout. */
val ANCHORS = listOf("extraThin", "extraBlack", "condensed", "wide", "regular")

val ANCHOR_LABELS = mapOf(
    "extraThin" to "Extra Thin",
    "extraBlack" to "Extra Black",
    "condensed" to "Condensed",
    "wide" to "Wide",
    "regular" to "Regular",
)

/** The four grid corners only, i.e. every anchor except `regular`. */
val CORNER_ANCHORS = ANCHORS.filter { it != "regular" }

@Serializable
data class Glyph(
    val corners: MutableMap<String, GlyphCorner> = ANCHORS.associateWith { GlyphCorner() }.toMutableMap(),
)
