package com.hereliesaz.morphont

/*
 * A small zlib compressor (RFC 1950/1951) in plain Kotlin, so WOFF can be
 * written on every platform without a platform codec. LZ77 with hash chains
 * and lazy matching, coded with the fixed Huffman tables. Not as tight as
 * zlib's dynamic blocks, but every stream it writes is a valid zlib stream.
 */

private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

private const val WINDOW = 32768
private const val MIN_MATCH = 3
private const val MAX_MATCH = 258
private const val HASH_BITS = 15
private const val MAX_CHAIN = 64

/** Bits packed least-significant first, the way DEFLATE reads them. */
private class BitSink {
    private val out = ByteWriter(1024)
    private var acc = 0L
    private var count = 0

    fun bits(value: Int, n: Int) {
        acc = acc or ((value.toLong() and ((1L shl n) - 1)) shl count)
        count += n
        while (count >= 8) { out.u8((acc and 0xFF).toInt()); acc = acc ushr 8; count -= 8 }
    }

    /** A Huffman code goes out most-significant bit first. */
    fun code(code: Int, length: Int) {
        var reversed = 0
        for (i in 0 until length) if (code and (1 shl i) != 0) reversed = reversed or (1 shl (length - 1 - i))
        bits(reversed, length)
    }

    fun finish(): ByteArray {
        if (count > 0) { out.u8((acc and 0xFF).toInt()); acc = 0; count = 0 }
        return out.toByteArray()
    }
}

private fun literalOrLength(sink: BitSink, symbol: Int) {
    when {
        symbol <= 143 -> sink.code(0x30 + symbol, 8)
        symbol <= 255 -> sink.code(0x190 + (symbol - 144), 9)
        symbol <= 279 -> sink.code(symbol - 256, 7)
        else -> sink.code(0xC0 + (symbol - 280), 8)
    }
}

private fun lengthSymbol(length: Int): Int {
    var i = LENGTH_BASE.size - 1
    while (LENGTH_BASE[i] > length) i--
    return i
}

private fun distanceSymbol(distance: Int): Int {
    var i = DIST_BASE.size - 1
    while (DIST_BASE[i] > distance) i--
    return i
}

private fun adler32(data: ByteArray): Long {
    var a = 1L; var b = 0L
    for (byte in data) { a = (a + (byte.toLong() and 0xFF)) % 65521; b = (b + a) % 65521 }
    return (b shl 16) or a
}

/** [data] as a zlib stream. */
internal fun zlibCompress(data: ByteArray): ByteArray {
    val sink = BitSink()
    sink.bits(1, 1) // final block
    sink.bits(1, 2) // fixed Huffman codes
    val n = data.size
    val head = IntArray(1 shl HASH_BITS) { -1 }
    val prev = IntArray(maxOf(n, 1))

    fun hash(i: Int): Int =
        (((data[i].toInt() and 0xFF) shl 10) xor ((data[i + 1].toInt() and 0xFF) shl 5) xor (data[i + 2].toInt() and 0xFF)) and ((1 shl HASH_BITS) - 1)

    fun insert(i: Int) {
        if (i + MIN_MATCH > n) return
        val h = hash(i)
        prev[i] = head[h]
        head[h] = i
    }

    var bestLength = 0; var bestDistance = 0
    fun findMatch(i: Int) {
        bestLength = 0; bestDistance = 0
        if (i + MIN_MATCH > n) return
        var candidate = head[hash(i)]
        var chain = 0
        val limit = minOf(MAX_MATCH, n - i)
        while (candidate >= 0 && i - candidate <= WINDOW && chain < MAX_CHAIN) {
            var l = 0
            while (l < limit && data[candidate + l] == data[i + l]) l++
            if (l > bestLength) { bestLength = l; bestDistance = i - candidate; if (l == limit) break }
            candidate = prev[candidate]
            chain++
        }
        if (bestLength < MIN_MATCH) bestLength = 0
    }

    var i = 0
    while (i < n) {
        findMatch(i)
        var length = bestLength; var distance = bestDistance
        if (length in MIN_MATCH until 32 && i + 1 < n) {
            // Lazy matching: if starting one byte later finds a longer match, emit this byte as a literal instead.
            insert(i)
            findMatch(i + 1)
            if (bestLength > length) { literalOrLength(sink, data[i].toInt() and 0xFF); i++; continue }
            // Keep the first match; it was already inserted.
            var k = 1
            val ls = lengthSymbol(length)
            literalOrLength(sink, 257 + ls); sink.bits(length - LENGTH_BASE[ls], LENGTH_EXTRA[ls])
            val ds = distanceSymbol(distance)
            sink.code(ds, 5); sink.bits(distance - DIST_BASE[ds], DIST_EXTRA[ds])
            while (k < length) { insert(i + k); k++ }
            i += length
            continue
        }
        if (length >= MIN_MATCH) {
            val ls = lengthSymbol(length)
            literalOrLength(sink, 257 + ls); sink.bits(length - LENGTH_BASE[ls], LENGTH_EXTRA[ls])
            val ds = distanceSymbol(distance)
            sink.code(ds, 5); sink.bits(distance - DIST_BASE[ds], DIST_EXTRA[ds])
            for (k in 0 until length) insert(i + k)
            i += length
        } else {
            literalOrLength(sink, data[i].toInt() and 0xFF)
            insert(i)
            i++
        }
    }
    sink.code(0, 7) // end of block (symbol 256)
    val body = sink.finish()
    return ByteWriter(body.size + 6).apply {
        u8(0x78); u8(0x9C)
        bytes(body)
        u32(adler32(data))
    }.toByteArray()
}
