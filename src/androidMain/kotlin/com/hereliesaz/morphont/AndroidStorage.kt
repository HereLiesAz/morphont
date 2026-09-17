package com.hereliesaz.morphont

import android.content.Context

private const val PREFS_NAME = "morphont"
private const val PROJECT_KEY = "project"
private const val LAST_GLYPH_KEY = "last_glyph"
private const val SELECTED_AXIS_KEY = "selected_axis"
private const val MOBILE_PANE_KEY = "mobile_pane"
private const val PREVIEW_VALUE_PREFIX = "preview_"

/** Android persistence for the same JSON project contract the PWA uses. */
class AndroidStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readProject(): MutableMap<String, Glyph> {
        val raw = prefs.getString(PROJECT_KEY, null) ?: return mutableMapOf()
        return try {
            ProjectCodec.decodeProject(raw)
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeProject(project: Map<String, Glyph>) {
        prefs.edit().putString(PROJECT_KEY, ProjectCodec.encodeProject(project)).apply()
    }

    fun listGlyphNames(): List<String> = readProject().keys.sorted()

    fun loadGlyph(name: String): Glyph? = readProject()[name]

    fun glyphExists(name: String): Boolean = readProject().containsKey(name)

    fun lastGlyphName(): String? = prefs.getString(LAST_GLYPH_KEY, null)

    fun setLastGlyphName(name: String?) {
        val edit = prefs.edit()
        if (name == null) edit.remove(LAST_GLYPH_KEY) else edit.putString(LAST_GLYPH_KEY, name)
        edit.apply()
    }

    fun selectedAxisTag(): String? = prefs.getString(SELECTED_AXIS_KEY, null)

    fun setSelectedAxisTag(tag: String) {
        prefs.edit().putString(SELECTED_AXIS_KEY, tag).apply()
    }

    fun mobilePaneName(): String? = prefs.getString(MOBILE_PANE_KEY, null)

    fun setMobilePaneName(name: String) {
        prefs.edit().putString(MOBILE_PANE_KEY, name).apply()
    }

    fun previewValue(tag: String): Float? {
        val key = PREVIEW_VALUE_PREFIX + tag
        return if (prefs.contains(key)) prefs.getFloat(key, 0.5f) else null
    }

    fun setPreviewValues(values: Map<String, Float>) {
        val edit = prefs.edit()
        values.forEach { (tag, value) ->
            edit.putFloat(PREVIEW_VALUE_PREFIX + tag, value.coerceIn(0f, 1f))
        }
        edit.apply()
    }

    fun saveGlyph(name: String, glyph: Glyph) {
        val project = readProject()
        project[name] = glyph
        writeProject(project)
    }

    fun saveGlyphs(glyphs: Map<String, Glyph>) {
        val project = readProject()
        project.putAll(glyphs)
        writeProject(project)
    }

    fun exportProjectText(): String = ProjectCodec.encodeProject(readProject())

    fun importProjectText(text: String): List<String> {
        val project = ProjectCodec.decodeProject(text)
        writeProject(project)
        setLastGlyphName(null)
        return project.keys.sorted()
    }

    fun exportGlyphText(glyph: Glyph): String = ProjectCodec.encodeGlyph(glyph)

    fun importGlyphText(name: String, text: String): Glyph {
        val glyph = ProjectCodec.decodeGlyph(text)
        saveGlyph(name, glyph)
        return glyph
    }
}
