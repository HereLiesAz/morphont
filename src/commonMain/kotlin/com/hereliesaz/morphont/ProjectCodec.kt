package com.hereliesaz.morphont

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun encodeGlyph(glyph: Glyph): String = json.encodeToString(glyph.copy(outlineFormat = OUTLINE_CUBIC))

    fun decodeGlyph(text: String): Glyph = migrateGlyph(json.decodeFromString(text))

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
