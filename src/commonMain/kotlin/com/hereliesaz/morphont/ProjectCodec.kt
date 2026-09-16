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
        if (legacyAnchorNames.keys.none { it in glyph.corners }) return glyph
        val migrated = glyph.corners.mapKeys { (name, _) -> legacyAnchorNames[name] ?: name }
        return Glyph(migrated.toMutableMap())
    }

    fun encodeGlyph(glyph: Glyph): String = json.encodeToString(glyph)

    fun decodeGlyph(text: String): Glyph = migrateGlyph(json.decodeFromString(text))

    fun encodeProject(project: Map<String, Glyph>): String {
        val ordered = project.entries
            .sortedBy { it.key }
            .associate { (name, glyph) -> name to glyph }
        return json.encodeToString(ordered)
    }

    fun decodeProject(text: String): MutableMap<String, Glyph> =
        json.decodeFromString<Map<String, Glyph>>(text)
            .mapValues { migrateGlyph(it.value) }
            .toMutableMap()
}
