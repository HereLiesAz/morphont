package com.hereliesaz.morphont

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.morphont.resources.Res
import com.hereliesaz.morphont.resources.morphont_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * What a newcomer needs before the editor makes sense: a glyph already drawn on every anchor (so the Preview
 * moves on the first try), the idea in two sentences, and the keys.
 */

/** The cubic arm length that makes four arcs a near-perfect ellipse. */
private const val ELLIPSE_ARM = 0.5523f

/**
 * An ellipse as four smooth nodes with their arms, in the node/arm order [Pt] describes, starting at the top.
 * [clockwise] for an outer contour, counter-clockwise for a counter -- the direction TrueType wants.
 */
private fun ellipse(cx: Float, cy: Float, a: Float, b: Float, clockwise: Boolean): ContourData {
    val dirs = if (clockwise) listOf(0f to 1f, 1f to 0f, 0f to -1f, -1f to 0f) else listOf(0f to 1f, -1f to 0f, 0f to -1f, 1f to 0f)
    fun node(i: Int) = dirs[i % 4].let { (ux, uy) -> Pt(cx + a * ux, cy + b * uy, onCurve = true, smooth = true) }
    val points = mutableListOf<Pt>()
    for (i in 0 until 4) {
        val n = node(i)
        val (nx, ny) = dirs[(i + 1) % 4]
        val next = node(i + 1)
        val (ux, uy) = dirs[i]
        points += n
        points += Pt(n.x + ELLIPSE_ARM * a * nx, n.y + ELLIPSE_ARM * b * ny, onCurve = false)
        points += Pt(next.x + ELLIPSE_ARM * a * ux, next.y + ELLIPSE_ARM * b * uy, onCurve = false)
    }
    // The loop's last entry is the first node's in-arm, which [Pt] expects at the end of a closed contour.
    return ContourData(points)
}

/** One "o" for one anchor: an outer ellipse and its counter, [sx]/[sy] the horizontal and vertical stroke. */
private fun letterO(width: Float, a: Float, sx: Float, sy: Float): GlyphCorner {
    val cx = width / 2f
    val cy = 250f
    val b = 260f
    return GlyphCorner(width, mutableListOf(ellipse(cx, cy, a, b, clockwise = true), ellipse(cx, cy, a - sx, b - sy, clockwise = false)))
}

/**
 * A lowercase "o" already drawn on every anchor: thin to black on Weight, condensed to wide on Width, and
 * Regular everywhere else. Every anchor has the same nodes in the same order, so it interpolates as soon as it
 * opens -- the quickest way to see what the Preview sliders do before drawing anything.
 */
fun sampleGlyph(): Glyph {
    val regular = letterO(560f, 240f, 72f, 56f)
    val corners = ANCHORS.associateWith { regular.deepCopy() }.toMutableMap()
    corners[Axis.WEIGHT.lo] = letterO(540f, 232f, 18f, 14f)
    corners[Axis.WEIGHT.hi] = letterO(620f, 268f, 150f, 96f)
    corners[Axis.WIDTH.lo] = letterO(400f, 160f, 60f, 56f)
    corners[Axis.WIDTH.hi] = letterO(780f, 350f, 82f, 56f)
    return Glyph(corners, outlineFormat = OUTLINE_CUBIC)
}

/** The first of "o", "o.sample", "o.sample2"… that this project doesn't use yet. */
fun sampleGlyphName(taken: Collection<String>): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) "o" else if (it == 2) "o.sample" else "o.sample$it" }.first { it !in taken }

/** Three stops of the sample, drawn small: the picture for "three drawings per axis". */
@Composable
private fun ThreeStops() {
    val g = sampleGlyph()
    val stops = listOf(Axis.WEIGHT.lo to "Extra Thin", "regular" to "Regular", Axis.WEIGHT.hi to "Extra Black")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((anchor, label) in stops) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val corner = g.corners.getValue(anchor)
                Canvas(Modifier.width(72.dp).height(72.dp)) {
                    val mapper = SpaceMapper(editorViewBox(FontMetrics(), corner.width, 1.0f, 0f, 0f), size)
                    val path = buildOutlinePath(corner.contours) { x, y -> mapper.toCanvas(x, y) }
                    path.fillType = PathFillType.EvenOdd
                    drawPath(path, color = if (anchor == "regular") Mono.ink else Mono.inkDim)
                }
                Caps(label, size = 10)
            }
        }
    }
}

/** The idea, in the app's own words, with the sample to try it on. [onTrySample] is null where there's no project to add it to. */
@Composable
fun HowItWorksDialog(onDismiss: () -> Unit, onTrySample: (() -> Unit)? = null) {
    MonoDialog("How Morphont works", onDismiss, actions = {
        MonoButton(onClick = onDismiss, outlined = true) { Text("Close") }
        if (onTrySample != null) MonoButton(onClick = { onDismiss(); onTrySample() }, selected = true) { Text("Open the sample") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThreeStops()
            Body("A variable font is a few drawings and the arithmetic between them. For each axis — Weight, Width, " +
                "Serif and the rest — you draw its two extremes. Every axis shares one middle drawing, Regular.")
            Body("Those drawings are the anchors: the strip of three under the canvas picks which one you're editing, " +
                "and the axis name beside it switches axes. Draw Regular first, copy it to every anchor, then " +
                "reshape each extreme. Every in-between weight is calculated, never drawn.")
            Body("Calculating needs the anchors to agree: the same contours, with the same number of nodes, in the same " +
                "order. When they don't, the Preview says so, and the \"Match the three anchors\" tool lines them up.")
            Body("The Preview sliders sweep the result. Ghosts lay another glyph, a shape or a character from any font " +
                "behind yours for comparison.")
        }
    }
}

/** The editor's keyboard shortcuts, as [editorKeys] binds them. */
val KEYBOARD_SHORTCUTS: List<Pair<String, String>> = listOf(
    "V" to "Select",
    "M" to "Move node",
    "P" to "Pen: start a contour",
    "A / D / C" to "Add / subtract / convert node",
    "Esc" to "Close the contour, leave Simplify or the ghost, or clear the selection",
    "Delete" to "Delete the selection",
    "Arrows" to "Nudge 1 unit (Shift: 10)",
    "Ctrl/⌘ C, X, V" to "Copy, cut, paste",
    "Ctrl/⌘ A" to "Select all",
    "Ctrl/⌘ Z" to "Undo",
    "Ctrl/⌘ Shift Z, Ctrl Y" to "Redo",
    "+ / −" to "Zoom in / out",
    "0" to "Reset zoom and pan",
)

/** What the touch and mouse gestures do, which no button shows. */
val GESTURES: List<Pair<String, String>> = listOf(
    "Tap a node" to "Select it (Shift-click adds to the selection)",
    "Long-press a node" to "Toggle it between smooth and corner (Alt-click with a mouse)",
    "Drag on empty canvas" to "Select with a box",
    "Pinch, two-finger drag" to "Zoom and pan (wheel and trackpad on desktop)",
    "Move circle" to "The circle that appears beside a selected node drags it without your finger covering it; " +
        "turn it on or off in Guides",
    "Long-press any tool" to "Shows its name",
    "Drag from a ruler" to "Places a guide; drop it back on the ruler to remove it",
)

@Composable
private fun Body(text: String) = Text(text, fontSize = Type.Body, color = Mono.ink, lineHeight = 20.sp)

@Composable
private fun KeyRows(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, what) in rows) Row {
            Text(key, fontSize = Type.Body, color = Mono.ink, modifier = Modifier.width(150.dp).padding(end = 8.dp))
            Text(what, fontSize = Type.Body, color = Mono.inkDim, modifier = Modifier.weight(1f))
        }
    }
}

/** Keys and gestures in one list, opened from the menu. */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    MonoDialog("Keys and gestures", onDismiss, actions = { MonoButton(onClick = onDismiss, outlined = true) { Text("Close") } }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Caps("Keyboard")
            KeyRows(KEYBOARD_SHORTCUTS)
            Caps("Gestures")
            KeyRows(GESTURES)
        }
    }
}

/**
 * The start screens' shared head: logo, wordmark, the idea in one line, and a way to read the rest. Both
 * [ProjectsScreen] and [WelcomeScreen] open with it.
 */
@Composable
fun MorphontHero(onTrySample: (() -> Unit)? = null) {
    var howOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Image(
            painterResource(Res.drawable.morphont_logo),
            contentDescription = null,
            modifier = Modifier.width(112.dp).height(112.dp),
        )
        Column {
            Text("Morphont", fontSize = Type.Hero, lineHeight = 66.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Mono.ink)
            Text("Three drawings per axis. The rest is arithmetic.", fontSize = Type.Row, color = Mono.inkDim)
        }
        MonoButton(onClick = { howOpen = true }, outlined = true) { Text("How Morphont works") }
    }
    if (howOpen) HowItWorksDialog({ howOpen = false }, onTrySample)
}

/** What to say once a glyph exists: where to start drawing, or -- for one that arrived drawn -- what to try. */
fun createdMessage(name: String, glyph: Glyph): String =
    if (glyph.corners.values.any { it.contours.isNotEmpty() })
        "Created \"$name\", already drawn on every anchor. Drag the Preview sliders, then reshape any anchor."
    else "Created \"$name\". Pick the pen and draw Regular first."
