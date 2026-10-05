package com.hereliesaz.morphont

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

/**
 * One variable-font axis Morphont can shape by hand: an OpenType axis tag
 * (registered, like `wght`, or custom, like Azrienoch's own `SERF`), and
 * the two hand-drawn extreme anchors that sweep it -- named `<tag>_lo`/
 * `<tag>_hi` so a saved project stays keyed the same way regardless of
 * how many axes are defined. Every axis shares the single `regular`
 * anchor as its own midpoint (see [Interpolation.kt]'s `axisInterp`).
 */
data class Axis(val tag: String, val label: String, val loLabel: String, val hiLabel: String) {
    val lo: String get() = "${tag}_lo"
    val hi: String get() = "${tag}_hi"

    /** e.g. "Weight: Extra Thin -> Extra Black", for the axis picker. */
    val toggleLabel: String get() = "$label: $loLabel -> $hiLabel"

    companion object {
        val WEIGHT = Axis("wght", "Weight", "Extra Thin", "Extra Black")
        val WIDTH = Axis("wdth", "Width", "Condensed", "Wide")
        val SERIF = Axis("SERF", "Serif", "Sans", "Slab")

        // Roboto Flex's own remaining registered + parametric axes. Every one
        // of these sweeps a real dimension that font already exposes;
        // importing from it (see FamilyImport.kt) extracts each straight
        // from its own fvar min/max, same as weight/width/serif above.
        // Labels stay short (the axis toggle wraps, but 14 axes' worth of
        // multi-word labels would still crowd it) -- standard typographic
        // terms (x-height, cap-height) where one exists, otherwise a short
        // description rather than the literal parametric-axis name.
        val GRADE = Axis("GRAD", "Grade", "Low", "High")
        val SLANT = Axis("slnt", "Slant", "Slanted", "Upright")
        val OPTICAL_SIZE = Axis("opsz", "Optical", "Small", "Large")
        val COUNTER_WIDTH = Axis("XTRA", "Counters", "Narrow", "Wide")
        val STEM_THICKNESS_X = Axis("XOPQ", "X Thickness", "Thin", "Thick")
        val STEM_THICKNESS_Y = Axis("YOPQ", "Y Thickness", "Thin", "Thick")
        val LOWERCASE_HEIGHT = Axis("YTLC", "x-height", "Short", "Tall")
        val UPPERCASE_HEIGHT = Axis("YTUC", "Cap Height", "Short", "Tall")
        val ASCENDER_HEIGHT = Axis("YTAS", "Ascender", "Short", "Tall")
        val DESCENDER_DEPTH = Axis("YTDE", "Descender", "Shallow", "Deep")
        val FIGURE_HEIGHT = Axis("YTFI", "Figures", "Short", "Tall")

        /**
         * Every axis Morphont currently exposes for hand-editing, in UI
         * order. This list, and every anchor/interpolation/import path
         * built on it, is N-axis-general; adding one more axis (whether
         * from another font or a wholly custom one) is only ever a matter
         * of appending another [Axis] value here.
         */
        val ALL = listOf(
            WEIGHT, WIDTH, SERIF,
            GRADE, SLANT, OPTICAL_SIZE, COUNTER_WIDTH,
            STEM_THICKNESS_X, STEM_THICKNESS_Y,
            LOWERCASE_HEIGHT, UPPERCASE_HEIGHT, ASCENDER_HEIGHT, DESCENDER_DEPTH, FIGURE_HEIGHT,
        )
    }
}

/** Every hand-editable anchor: each axis's lo/hi extremes, in axis order, plus the one shared Regular. */
val ANCHORS: List<String> = Axis.ALL.flatMap { listOf(it.lo, it.hi) } + "regular"

val ANCHOR_LABELS: Map<String, String> = buildMap {
    for (axis in Axis.ALL) {
        put(axis.lo, axis.loLabel)
        put(axis.hi, axis.hiLabel)
    }
    put("regular", "Regular")
}

/** Every anchor except `regular` -- the hand-drawn extremes of every axis. */
val CORNER_ANCHORS = ANCHORS.filter { it != "regular" }

/**
 * A glyph's whole editable record. [guides], [ghosts] and [metrics] are
 * editor-only reference material: interpolation and export ignore them,
 * and every one defaults empty/null so files saved before they existed
 * decode unchanged (and files saved now stay readable by older builds,
 * whose codec ignores unknown keys).
 */
@Serializable
data class Glyph(
    val corners: MutableMap<String, GlyphCorner> = ANCHORS.associateWith { GlyphCorner() }.toMutableMap(),
    val guides: List<Guide> = emptyList(),
    val ghosts: List<GhostData> = emptyList(),
    val metrics: FontMetrics? = null,
)

/** A user-placed guide line: [vertical] ones sit at x = [pos], horizontal ones at y = [pos] (font units). */
@Serializable
data class Guide(val vertical: Boolean, val pos: Float)

/**
 * A ghosted reference outline drawn behind every anchor for comparison.
 *
 * A static ghost (a primitive shape, or a character from a reference font)
 * owns its [contours]. A linked ghost names [sourceGlyph] in this project
 * instead and is drawn live: each anchor panel shows that glyph's own same
 * anchor, and the Preview shows it interpolated at the same slider values,
 * so the reference always carries the working character's weight, width,
 * and every other axis. [matrix] (`a b c d e f`, mapping `x' = a*x + c*y + e`,
 * `y' = b*x + d*y + f`) places either kind; static ghosts bake it into their
 * contours before node editing, linked ones detach into static ones first.
 */
@Serializable
data class GhostData(
    val label: String,
    val contours: List<ContourData> = emptyList(),
    val visible: Boolean = true,
    val sourceGlyph: String? = null,
    val matrix: List<Float> = IDENTITY_MATRIX,
    /** Drawn beside the working glyph (shifted by its advance) instead of over it. Overlay is the default. */
    val beside: Boolean = false,
)

val IDENTITY_MATRIX = listOf(1f, 0f, 0f, 1f, 0f, 0f)

/**
 * Vertical metrics the static guides are drawn from. Defaults are a
 * conventional 1000-UPM layout; importing a font replaces them with that
 * font's own `hhea`/`OS/2` values.
 */
@Serializable
data class FontMetrics(
    val unitsPerEm: Float = 1000f,
    val ascender: Float = 800f,
    val capHeight: Float = 700f,
    val xHeight: Float = 500f,
    val descender: Float = -200f,
)
