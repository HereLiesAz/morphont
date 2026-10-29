package com.hereliesaz.morphont

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * Morphont's own monoline icon set, drawn on a 24-unit grid with a 1.6
 * stroke -- no icon library, so the line weight matches the hairline
 * canvas. Each is a list of SVG path strings; [filled] paths are solid.
 * The tint is applied by `Icon`, so the colour baked in here is irrelevant.
 */
object MIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, strokes: List<String>, filled: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(name, 24f.dp24, 24f.dp24, 24f, 24f)
        for (d in strokes) b.addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        for (d in filled) b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White))
        return b.build()
    }

    val Select = icon("select", emptyList(), listOf("M5 3l14 8-6 1.5L10 19z"))
    val Pen = icon("pen", listOf("M12 3 5 13l3 8h8l3-8z", circle(12f, 12f, 1.6f)))
    val AddNode = icon("addNode", listOf("M10 6 3 15l3 6h8l3-6z", "M18 3v6M15 6h6"))
    val SubtractNode = icon("subtractNode", listOf("M10 6 3 15l3 6h8l3-6z", "M15 6h6"))
    val ConvertNode = icon("convertNode", listOf("M4 20 12 5l8 15", "M8.5 13c2-1.4 5-1.4 7 0"))
    // Three states, one row each, with their nodes at different places -- the thing the menu lines up.
    val States = icon("states", listOf("M3 6h18M3 12h18M3 18h18"), listOf(circle(8f, 6f, 1.8f), circle(15f, 12f, 1.8f), circle(11f, 18f, 1.8f)))
    val Axes = icon("axes", listOf("M6 3v18M12 3v18M18 3v18"), listOf(circle(6f, 15f, 1.8f), circle(12f, 8f, 1.8f), circle(18f, 13f, 1.8f)))
    val Guides = icon("guides", listOf("M3 3h18v6H9v12H3z", "M7 3v3M11 3v3M15 3v3M3 13h3M3 17h3"))
    val Measure = icon("measure", listOf("M4 12h16M4 8v8M20 8v8"))
    // A ghost, not a copy: the silhouette with a ragged hem and two eyes.
    val Ghost = icon("ghost", listOf("M5 21V11a7 7 0 0 1 14 0v10l-2.3-2-2.4 2-2.3-2-2.3 2-2.4-2z"), listOf(circle(9.5f, 11f, 1.2f), circle(14.5f, 11f, 1.2f)))
    val Simplify = icon("simplify", listOf("M3 18c4-10 14-10 18 0"), listOf(circle(3f, 18f, 1.6f), circle(21f, 18f, 1.6f)))
    val Undo = icon("undo", listOf("M9 14 4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3"))
    val Redo = icon("redo", listOf("M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3"))
    val More = icon("more", emptyList(), listOf(circle(5f, 12f, 1.6f), circle(12f, 12f, 1.6f), circle(19f, 12f, 1.6f)))
    val Copy = icon("copy", listOf("M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"))
    val Paste = icon("paste", listOf("M9 4h6v3H9z", "M15 5h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h3"))
    val Cut = icon("cut", listOf(circle(6f, 18f, 3f), circle(18f, 18f, 3f), "M8 16 19 4M16 16 5 4"))
    // Node types, drawn as on the canvas: a square is a corner, a circle on a straight tangent is smooth.
    val Corner = icon("corner", listOf("M4 20 12 8l8 12", "M10 6h4v4h-4z"))
    // A cubic with its two handles drawn in -- distinct from Simplify's bare arc.
    val CurveLine = icon("curveLine", listOf("M5 19C5 10 10 5 19 5", "M5 19v-8M19 5h-8", circle(5f, 19f, 1.4f), circle(5f, 11f, 1.1f), circle(11f, 5f, 1.1f), circle(19f, 5f, 1.4f)))
    val Smooth = icon("smooth", listOf("M4 12h5.6M14.4 12H20", circle(12f, 12f, 2.4f)), listOf("M3 10.4 4.6 12 3 13.6 1.4 12z", "M21 10.4 22.6 12 21 13.6 19.4 12z"))
    val Delete = icon("delete", listOf("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"))
    val Plus = icon("plus", listOf("M12 5v14M5 12h14"))
    val Minus = icon("minus", listOf("M5 12h14"))
    val Close = icon("close", listOf("M6 6l12 12M18 6 6 18"))
    val Check = icon("check", listOf("M5 12.5 10 17 19 7"))
    val Chevron = icon("chevron", listOf("m6 9 6 6 6-6"))
    val Eye = icon("eye", listOf("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", circle(12f, 12f, 3f)))
    val EyeOff = icon("eyeOff", listOf("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", "M3 3l18 18"))
    val FlipH = icon("flipH", listOf("M12 3v18", "M9 7 4 12l5 5z", "M15 7l5 5-5 5z"))
    val FlipV = icon("flipV", listOf("M3 12h18", "M7 9l5-5 5 5z", "M7 15l5 5 5-5z"))
    val RotateLeft = icon("rotL", listOf("M4 4v5h5", "M4.5 9A8 8 0 1 1 6 17"))
    val RotateRight = icon("rotR", listOf("M20 4v5h-5", "M19.5 9A8 8 0 1 0 18 17"))
    val Nodes = icon("nodes", listOf("M4 18 10 6l10 6", circle(4f, 18f, 1.8f), circle(10f, 6f, 1.8f), circle(20f, 12f, 1.8f)))
    val Move = icon("move", listOf("M12 3v18M3 12h18M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3"))
    val FontGrid = icon("fontGrid", listOf("M3 3h8v8H3z", "M13 3h8v8h-8z", "M3 13h8v8H3z", "M13 13h8v8h-8z"))
    val SelectAll = icon("selectAll", listOf("M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4", "M9 9h6v6H9z"))
    val ResetView = icon("resetView", listOf("M20 9V4h-5", "M20 4a8 8 0 1 0 2 12", "M12 8v4l3 2"))
    @Deprecated("Use ResetView")
    val Fit = ResetView
    val ChevronLeft = icon("chevronLeft", listOf("m15 5-7 7 7 7"))
    val ChevronRight = icon("chevronRight", listOf("m9 5 7 7-7 7"))
    val Spark = icon("spark", listOf("M12 3c.6 4.6 2.4 6.4 7 7-4.6.6-6.4 2.4-7 7-.6-4.6-2.4-6.4-7-7 4.6-.6 6.4-2.4 7-7z", "M19 3v3M17.5 4.5h3"))
    val Duplicate = icon("dup", listOf("M8 8h12v12H8z", "M4 16V4h12"))
}

private val Float.dp24 get() = androidx.compose.ui.unit.Dp(this)
