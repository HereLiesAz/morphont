package com.hereliesaz.morphont

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The settings that outlive a session: theme and the view toggles, including the grid step. One small JSON
 * document, kept by the platform ([SettingsStore]). Anything missing or unreadable falls back to its default.
 */
@Serializable
data class AppSettings(
    val dark: Boolean = true,
    val snap: Boolean = true,
    val showMetrics: Boolean = true,
    val showRulers: Boolean = true,
    val showGuides: Boolean = true,
    val showMeasure: Boolean = true,
    val showGhosts: Boolean = true,
    val showTravel: Boolean = false,
    val showGrid: Boolean = false,
    val gridStep: Float = 50f,
    val glyphOpacity: Float = 0.5f,
    /** Null follows the pointer type (on for touch); true/false is the user's choice. */
    val moveCircle: Boolean? = null,
)

/** Reads and writes [AppSettings] through whatever the platform stores a string in. */
class SettingsStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppSettings {
        val raw = runCatching(read).getOrNull() ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        runCatching { write(json.encodeToString(AppSettings.serializer(), settings)) }
    }
}

/** The grid steps the settings offer. */
val GRID_STEPS = listOf(10f, 25f, 50f, 100f)
