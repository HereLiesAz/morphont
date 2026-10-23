package com.hereliesaz.morphont

/**
 * The open project's glyph storage, as each shell supplies it -- enough for the rename and delete below to be
 * written once, here, instead of three times.
 */
class GlyphFiles(
    val exists: (String) -> Boolean,
    val load: (String) -> Glyph?,
    val save: (String, Glyph) -> Unit,
    val delete: (String) -> Unit,
    val list: () -> List<String>,
    /**
     * Moves a glyph to a new name as one step that either happens whole or not at all, for storage whose
     * writes are queued and so can't be ordered by [save] then [delete] (the browser's). Null where those
     * two calls are already safe in order.
     */
    val move: ((String, String, Glyph) -> Unit)? = null,
)

/**
 * Renames glyph [old] to [raw] (trimmed): written under the new name first, then the old one removed, so a
 * failure in between leaves a copy rather than nothing. Renaming the open glyph keeps it open with its undo
 * history; the autosave epoch moves on so a save still queued under the old name is dropped instead of
 * bringing that file back. False, with a status message, when the name is unusable.
 */
fun renameGlyph(app: AppState, files: GlyphFiles, old: String, raw: String): Boolean {
    val name = raw.trim()
    when {
        name.isEmpty() -> { app.setStatus("Type a name first.", true); return false }
        name == old -> return true
        files.exists(name) -> { app.setStatus("A glyph named \"$name\" already exists.", true); return false }
    }
    val open = old == app.currentGlyphName
    val glyph = (if (open) app.toGlyph() else files.load(old)) ?: run {
        app.setStatus("\"$old\" couldn't be read, so it wasn't renamed.", true)
        return false
    }
    try {
        if (open) app.beginBulkImport()
        val move = files.move
        if (move != null) move(old, name, glyph)
        else {
            files.save(name, glyph)
            files.delete(old)
        }
    } catch (e: Exception) {
        app.glyphNames = files.list()
        app.setStatus("Couldn't rename \"$old\": ${e.message ?: e::class.simpleName}.", true)
        return false
    }
    if (open) app.currentGlyphName = name
    app.glyphNames = files.list()
    app.setStatus("Renamed \"$old\" to \"$name\".")
    return true
}

/**
 * Deletes glyph [name]. If it was open, the editor clears first (so autosave can't write it back) and [open]
 * is called on the first glyph left, if any.
 */
fun deleteGlyph(app: AppState, files: GlyphFiles, name: String, open: (String) -> Unit) {
    val wasOpen = name == app.currentGlyphName
    if (wasOpen) {
        app.beginBulkImport()
        app.clearEditor()
    }
    try {
        files.delete(name)
    } catch (e: Exception) {
        app.setStatus("Couldn't delete \"$name\": ${e.message ?: e::class.simpleName}.", true)
    }
    val names = files.list()
    app.glyphNames = names
    if (wasOpen) names.firstOrNull()?.let(open)
    if (!files.exists(name)) app.setStatus("Deleted \"$name\".")
}
