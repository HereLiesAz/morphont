package com.hereliesaz.morphont

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The open project as the export sees it: every glyph that reads, and the names of those that didn't. */
class FontSnapshot(val glyphs: Map<String, Glyph>, val unreadable: List<String>)

/** What the shell supplies for compiled-font export. */
class FontExportHost(
    /** Every glyph in the open project, the one being edited included. */
    val snapshot: suspend () -> FontSnapshot,
    /** Hands [bytes] to the person to keep as [fileName] (a picker, a download); says when it is saved or why it was not. */
    val save: (fileName: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit, onError: (String) -> Unit) -> Unit,
)

@Composable
private fun Choice(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) Mono.ink else Mono.border, RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = Type.Row, color = if (selected) Mono.ink else Mono.inkDim)
        Text(detail, fontSize = Type.Small, color = Mono.inkDim)
    }
}

private fun fmtRange(v: Float): String = if (v == kotlin.math.round(v)) v.toInt().toString() else v.toString()

/** What a static font freezes, in words: the axes the Font overview has moved off Regular. */
private fun instanceSummary(values: Map<String, Float>): String {
    val moved = Axis.ALL.filter { kotlin.math.abs((values[it.tag] ?: 0.5f) - 0.5f) > 0.001f }
    return if (moved.isEmpty()) "Regular." else moved.joinToString(", ") { "${it.label} ${fmt2(values.getValue(it.tag))}" } + "."
}

/**
 * Exports the open project as a font file: pick the outlines and the wrapper, name the family, and (for a
 * variable font) give each axis the numbers it reaches. A static font freezes the Font overview's instance.
 */
@Composable
fun FontExportDialog(app: AppState, host: FontExportHost, onDismiss: () -> Unit, onExported: () -> Unit = onDismiss) {
    var snapshot by remember { mutableStateOf<FontSnapshot?>(null) }
    var axes by remember { mutableStateOf<List<Axis>>(emptyList()) }
    var outlines by remember { mutableStateOf(FontOutlines.TRUETYPE) }
    var container by remember { mutableStateOf(FontContainer.PLAIN) }
    var family by remember { mutableStateOf(app.currentProject?.name ?: "") }
    var style by remember { mutableStateOf("Regular") }
    val ranges = remember { mutableStateMapOf<String, List<String>>() }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val s = host.snapshot()
            axes = withContext(Dispatchers.Default) { fontAxesInUse(s.glyphs) }
            for (axis in axes) {
                val r = DEFAULT_AXIS_RANGES.getValue(axis.tag)
                ranges[axis.tag] = listOf(fmtRange(r.min), fmtRange(r.default), fmtRange(r.max))
            }
            snapshot = s
        } catch (e: Throwable) {
            problem = "Couldn't read the project: ${e.message ?: e::class.simpleName}"
        }
    }

    val parsedRanges: Map<String, AxisRange?> = axes.associate { axis ->
        val t = ranges[axis.tag]?.map { it.trim().toFloatOrNull()?.takeIf { v -> v.isFinite() } }
        axis.tag to if (t == null || t.any { it == null }) null else AxisRange(t[0]!!, t[1]!!, t[2]!!)
    }
    val rangeProblem: String? = if (outlines != FontOutlines.VARIABLE) null else
        axes.firstNotNullOfOrNull { axis ->
            val r = parsedRanges[axis.tag]
            if (r == null) "${axis.label} needs three numbers." else r.problem?.let { "${axis.label}: $it" }
        }
    val nothingToVary = outlines == FontOutlines.VARIABLE && snapshot != null && axes.isEmpty()
    val ready = snapshot != null && !working && family.isNotBlank() && rangeProblem == null && !nothingToVary

    fun export() {
        val s = snapshot ?: return
        val options = FontExportOptions(
            family = family.trim(),
            style = style.trim().ifEmpty { "Regular" },
            outlines = outlines,
            container = container,
            instance = app.fontPreviewValues.toMap(),
            axisRanges = DEFAULT_AXIS_RANGES + parsedRanges.filterValues { it != null }.mapValues { it.value!! },
        )
        working = true
        problem = null
        scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { exportFont(s.glyphs, options) }
            } catch (e: IllegalArgumentException) {
                problem = e.message ?: "Nothing to export."; null
            } catch (e: Throwable) {
                problem = "Couldn't build the font: ${e.message ?: e::class.simpleName}"; null
            }
            working = false
            if (result != null) host.save(result.fileName, result.mimeType, result.bytes, {
                val notes = result.warnings + listOfNotNull(s.unreadable.takeIf { it.isNotEmpty() }?.let { "Left out unreadable glyph file(s): ${it.joinToString()}." })
                app.setStatus("Exported ${result.fileName}: ${result.glyphCount} glyphs, ${(result.bytes.size + 512) / 1024} KB." +
                    (if (notes.isEmpty()) "" else " " + notes.joinToString(" ")), notes.isNotEmpty())
                onExported()
            }, { problem = it })
        }
    }

    MonoDialog("Export as a font", onDismiss, actions = {
        MonoButton(onClick = onDismiss, outlined = true) { Text("Cancel") }
        MonoButton(onClick = ::export, selected = true, enabled = ready) { Text(if (working) "Building…" else "Export") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Caps("Outlines")
            for (o in FontOutlines.entries) Choice(o.label, o.detail, outlines == o) { outlines = o }
            Caps("File", modifier = Modifier.padding(top = 6.dp))
            for (c in FontContainer.entries) Choice(c.label, c.detail, container == c) { container = c }

            Caps("Name", modifier = Modifier.padding(top = 6.dp))
            MonoField(family, { family = it }, "Family name", Modifier.fillMaxWidth(), isError = family.isBlank())
            if (outlines != FontOutlines.VARIABLE) MonoField(style, { style = it }, "Style (Regular)", Modifier.fillMaxWidth())

            if (outlines == FontOutlines.VARIABLE) {
                Caps("Axes", modifier = Modifier.padding(top = 6.dp))
                if (snapshot == null) Text("Reading the project…", fontSize = Type.Small, color = Mono.inkDim)
                else if (axes.isEmpty()) Text(
                    "No axis has both its extremes drawn on any glyph, so there is nothing to vary. Draw an axis's two ends, or export a static font.",
                    fontSize = Type.Body, color = Mono.error,
                ) else {
                    Text("The numbers behind each axis: its low anchor, Regular, and its high anchor. Set a side's end equal to the default to leave that side out.",
                        fontSize = Type.Small, color = Mono.inkDim)
                    for (axis in axes) {
                        val fields = ranges[axis.tag] ?: continue
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${axis.label} (${axis.tag})", fontSize = Type.Body, color = Mono.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((i, caption) in listOf("Min", "Default", "Max").withIndex()) {
                                    MonoField(
                                        fields[i], { v -> ranges[axis.tag] = fields.toMutableList().also { it[i] = v.filter { c -> c.isDigit() || c == '-' || c == '.' } } },
                                        caption, Modifier.weight(1f), isError = fields[i].trim().toFloatOrNull() == null,
                                        keyboardType = KeyboardType.Decimal,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Freezes the Font overview's instance: " + instanceSummary(app.fontPreviewValues.toMap()),
                    fontSize = Type.Small, color = Mono.inkDim, modifier = Modifier.padding(top = 4.dp))
            }

            val shown = problem ?: rangeProblem
            if (shown != null) Text(shown, fontSize = Type.Body, color = Mono.error)
            else if (container == FontContainer.WOFF2) Text(
                "WOFF2 files from Morphont are valid but not yet compressed, so they're about the size of a plain font.",
                fontSize = Type.Small, color = Mono.inkDim,
            )
        }
    }
}
