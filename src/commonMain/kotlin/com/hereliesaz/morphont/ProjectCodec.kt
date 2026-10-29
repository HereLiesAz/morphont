package com.hereliesaz.morphont

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The platform-independent project file contract. Browser localStorage,
 * Android SharedPreferences, JSON import/export, and future platforms all
 * pass through this codec so persistence cannot quietly fork by platform.
 */
object ProjectCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val legacyAnchorNames = mapOf(
        "extraThin" to Axis.WEIGHT.lo,
        "extraBlack" to Axis.WEIGHT.hi,
        "condensed" to Axis.WIDTH.lo,
        "wide" to Axis.WIDTH.hi,
    )

    fun migrateGlyph(glyph: Glyph): Glyph {
        var g = glyph
        if (legacyAnchorNames.keys.any { it in g.corners }) {
            val migrated = g.corners.mapKeys { (name, _) -> legacyAnchorNames[name] ?: name }
            g = g.copy(corners = migrated.toMutableMap())
        }
        // Quadratic saves convert exactly to the cubic model (see quadraticToCubic), ghosts included.
        if (g.outlineFormat < OUTLINE_CUBIC) g = g.copy(
            corners = g.corners.mapValues { (_, c) -> GlyphCorner(c.width, quadraticToCubic(c.contours)) }.toMutableMap(),
            ghosts = g.ghosts.map { it.copy(contours = quadraticToCubic(it.contours)) },
            outlineFormat = OUTLINE_CUBIC,
        )
        return g
    }

    private val compactJson = Json {
        ignoreUnknownKeys = true
    }

    fun encodeGlyph(glyph: Glyph): String = json.encodeToString(glyph.copy(outlineFormat = OUTLINE_CUBIC))

    /**
     * The same document without the indentation, for what is stored rather than shown: a glyph has 29 anchors, so
     * pretty-printing roughly triples what a font import (hundreds of glyphs) holds in memory and writes to disk.
     * [decodeGlyph] reads either; use [encodeGlyph] for a file a person might open.
     */
    fun encodeGlyphCompact(glyph: Glyph): String = compactJson.encodeToString(glyph.copy(outlineFormat = OUTLINE_CUBIC))

    /** The keys a saved glyph can carry. Every field has a default, so a file with none of them would still "decode". */
    private val glyphKeys = setOf("corners", "guides", "ghosts", "metrics", "outlineFormat")

    /** Throws unless [text] is a JSON object with at least one glyph key: `{}` or a settings file is not a glyph. */
    fun decodeGlyph(text: String): Glyph {
        val element = json.parseToJsonElement(text)
        require(element is JsonObject && element.keys.any { it in glyphKeys }) { "This file isn't a Morphont glyph." }
        return migrateGlyph(json.decodeFromJsonElement(Glyph.serializer(), element))
    }

    fun encodeProject(project: Map<String, Glyph>): String {
        val ordered = project.entries
            .sortedBy { it.key }
            .associate { (name, glyph) -> name to glyph.copy(outlineFormat = OUTLINE_CUBIC) }
        return json.encodeToString(ordered)
    }

    fun decodeProject(text: String): MutableMap<String, Glyph> =
        json.decodeFromString<Map<String, Glyph>>(text)
            .mapValues { migrateGlyph(it.value) }
            .toMutableMap()
}
