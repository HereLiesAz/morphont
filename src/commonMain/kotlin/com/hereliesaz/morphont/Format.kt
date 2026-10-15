package com.hereliesaz.morphont

import kotlin.math.abs
import kotlin.math.round

/** `String.format("%.2f", ...)` isn't available outside the JVM -- this is the KMP-safe equivalent. */
fun fmt2(v: Float): String {
    val sign = if (v < 0) "-" else ""
    val scaled = round(abs(v) * 100f).toInt()
    val whole = scaled / 100
    val frac = scaled % 100
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$sign$whole.$fracStr"
}

/**
 * Display legacy UFO production names as characters without changing their
 * persisted glyph IDs. Unencoded, alternate and non-printing glyph names
 * remain literal, so selecting a tile always opens its original record.
 */
fun displayGlyphName(name: String): String {
    val hex = Regex("^(?:uni|unicode|u)([0-9a-f]{4,6})$", RegexOption.IGNORE_CASE)
        .matchEntire(name)?.groupValues?.get(1) ?: return name
    val codepoint = hex.toIntOrNull(16) ?: return name
    if (codepoint < 0x21 || codepoint > 0x10FFFF ||
        codepoint in 0x7F..0x9F || codepoint in 0xD800..0xDFFF ||
        codepoint in 0x200B..0x200F || codepoint in 0x202A..0x202E ||
        codepoint in 0x2060..0x206F || codepoint in 0xFE00..0xFE0F ||
        codepoint == 0xFEFF || codepoint in 0xE0100..0xE01EF
    ) return name
    if (codepoint <= 0xFFFF) return codepoint.toChar().toString()
    val value = codepoint - 0x10000
    return ((0xD800 + (value shr 10)).toChar().toString() +
        (0xDC00 + (value and 0x3FF)).toChar())
}
