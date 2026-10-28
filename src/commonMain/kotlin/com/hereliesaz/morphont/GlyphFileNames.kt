package com.hereliesaz.morphont

/*
 * How a glyph's name becomes a file name when the glyphs are kept as plain
 * files that people can see: the scheme the desktop app uses, written without
 * a platform codec so every shell can read and write the same folder.
 *
 * The name is UTF-8 percent-encoded (a space is "+"), then adjusted so it is
 * also a safe name on Windows and on case-insensitive volumes: "*" is escaped,
 * DOS device names ("CON", "COM1"...) get their first letter escaped, and so
 * does every ASCII capital, so "A" and "a" never share a file.
 */

private const val HEX = "0123456789ABCDEF"

private fun StringBuilder.percent(byte: Int): StringBuilder =
    append('%').append(HEX[(byte shr 4) and 0xF]).append(HEX[byte and 0xF])

/** [name] as a file name (with the ".json" ending). */
fun glyphFileName(name: String): String {
    require(name.isNotEmpty()) { "Glyph name must not be empty" }
    var encoded = StringBuilder().also { out ->
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' -> out.append(c)
                c == ' ' -> out.append('+')
                c == '*' -> out.append("%2A")
                else -> {
                    // A surrogate pair is one character; a lone surrogate has no UTF-8 form and is written as "?".
                    val scalar = if (c.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate()) {
                        i++
                        (((c.code - 0xD800) shl 10) or (name[i].code - 0xDC00)) + 0x10000
                    } else c.code
                    if (scalar in 0xD800..0xDFFF) out.percent('?'.code) else for (b in utf8(scalar)) out.percent(b)
                }
            }
            i++
        }
    }.toString()
    val base = encoded.substringBefore('.').uppercase()
    if (base in setOf("CON", "PRN", "AUX", "NUL") || Regex("^(COM[1-9]|LPT[1-9])$").matches(base)) {
        encoded = "%" + encoded[0].code.toString(16).padStart(2, '0').uppercase() + encoded.drop(1)
    }
    val folded = StringBuilder()
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        when {
            c == '%' -> { folded.append(encoded, i, i + 3); i += 3 }
            c in 'A'..'Z' -> { folded.percent(c.code); i++ }
            else -> { folded.append(c); i++ }
        }
    }
    return folded.toString() + ".json"
}

private fun utf8(scalar: Int): IntArray = when {
    scalar < 0x80 -> intArrayOf(scalar)
    scalar < 0x800 -> intArrayOf(0xC0 or (scalar shr 6), 0x80 or (scalar and 0x3F))
    scalar < 0x10000 -> intArrayOf(0xE0 or (scalar shr 12), 0x80 or ((scalar shr 6) and 0x3F), 0x80 or (scalar and 0x3F))
    else -> intArrayOf(0xF0 or (scalar shr 18), 0x80 or ((scalar shr 12) and 0x3F), 0x80 or ((scalar shr 6) and 0x3F), 0x80 or (scalar and 0x3F))
}

/** The glyph a file name stands for, or null when [fileName] isn't one of ours. */
fun glyphNameOfFile(fileName: String): String? {
    if (!fileName.endsWith(".json")) return null
    val encoded = fileName.removeSuffix(".json")
    if (encoded.isEmpty()) return null
    val bytes = ArrayList<Byte>(encoded.length)
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        when {
            c == '%' -> {
                if (i + 2 >= encoded.length) return null
                val hi = encoded[i + 1].digitToIntOrNull(16) ?: return null
                val lo = encoded[i + 2].digitToIntOrNull(16) ?: return null
                bytes.add(((hi shl 4) or lo).toByte()); i += 3
            }
            c == '+' -> { bytes.add(' '.code.toByte()); i++ }
            c.code < 0x80 -> { bytes.add(c.code.toByte()); i++ }
            else -> {
                // Not something this scheme writes, but a person may have named a file by hand: read the character as it is.
                val scalar = if (c.isHighSurrogate() && i + 1 < encoded.length && encoded[i + 1].isLowSurrogate()) {
                    i++
                    (((c.code - 0xD800) shl 10) or (encoded[i].code - 0xDC00)) + 0x10000
                } else c.code
                if (scalar in 0xD800..0xDFFF) bytes.add('?'.code.toByte()) else for (b in utf8(scalar)) bytes.add(b.toByte())
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
