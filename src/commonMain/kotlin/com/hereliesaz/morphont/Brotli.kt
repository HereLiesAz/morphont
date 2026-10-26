package com.hereliesaz.morphont

/*
 * WOFF2 wraps its tables in one Brotli stream (RFC 7932). Morphont writes a
 * valid stream of uncompressed meta-blocks: every Brotli decoder reads it,
 * and it costs nothing in correctness, but it saves no space either. A real
 * Brotli encoder is a project of its own; the format leaves room to add one
 * here without touching anything else.
 */

private const val MAX_STORED_BLOCK = 1 shl 20

/** [data] as a Brotli stream made only of uncompressed meta-blocks. */
internal fun brotliStored(data: ByteArray): ByteArray {
    val out = ByteWriter(data.size + 32)
    // Bit-level header writing: bits go in least-significant first, and a stored block starts on a byte boundary.
    var acc = 0; var count = 0
    fun bits(value: Int, n: Int) {
        acc = acc or (value shl count); count += n
        while (count >= 8) { out.u8(acc and 0xFF); acc = acc ushr 8; count -= 8 }
    }
    fun flush() { if (count > 0) { out.u8(acc and 0xFF); acc = 0; count = 0 } }

    bits(0, 1) // WBITS: a 16-bit window; the data is never referred back to
    var at = 0
    while (at < data.size) {
        val size = minOf(MAX_STORED_BLOCK, data.size - at)
        val minusOne = size - 1
        val nibbles = when { minusOne < (1 shl 16) -> 4; minusOne < (1 shl 20) -> 5; else -> 6 }
        bits(0, 1)                      // ISLAST = 0
        bits(nibbles - 4, 2)            // MNIBBLES
        bits(minusOne, nibbles * 4)     // MLEN - 1
        bits(1, 1)                      // ISUNCOMPRESSED
        flush()
        out.bytes(data, at, at + size)
        at += size
    }
    bits(1, 1) // ISLAST
    bits(1, 1) // ISLASTEMPTY
    flush()
    return out.toByteArray()
}
