package com.hereliesaz.morphont

import android.content.Context

private const val PREFS_NAME = "morphont"
private const val PROJECT_KEY = "project"

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
        return project.keys.sorted()
    }

    fun exportGlyphText(glyph: Glyph): String = ProjectCodec.encodeGlyph(glyph)

    fun importGlyphText(name: String, text: String): Glyph {
        val glyph = ProjectCodec.decodeGlyph(text)
        saveGlyph(name, glyph)
        return glyph
    }
}
