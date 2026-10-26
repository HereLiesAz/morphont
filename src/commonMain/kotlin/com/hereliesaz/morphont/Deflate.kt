package com.hereliesaz.morphont

/*
 * A zlib compressor (RFC 1950/1951) in plain Kotlin, so WOFF can be written on every platform without a
 * platform codec. LZ77 with hash chains and lazy matching, then one DEFLATE block coded with whichever of the
 * fixed or the dynamic Huffman tables is smaller. Not zlib's own tuning, but every stream it writes is a valid
 * zlib stream.
 */

private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

private const val WINDOW = 32768
private const val MIN_MATCH = 3
private const val MAX_MATCH = 258
private const val HASH_BITS = 15
private const val MAX_CHAIN = 96
private const val END_OF_BLOCK = 256

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

/** The fixed Huffman code lengths of RFC 1951 section 3.2.6. */
private val FIXED_LITERAL_LENGTHS = IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 }
private val FIXED_DISTANCE_LENGTHS = IntArray(30) { 5 }

/** [data] as a zlib stream. */
internal fun zlibCompress(data: ByteArray): ByteArray {
    val n = data.size
    // ---- LZ77: a token is a literal (distance 0, value the byte) or a match (distance, value the length)
    val values = IntList(); val distances = IntList()
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
        val length = bestLength; val distance = bestDistance
        if (length in MIN_MATCH until 32 && i + 1 < n) {
            // Lazy matching: if starting one byte later finds a longer match, emit this byte as a literal instead.
            insert(i)
            findMatch(i + 1)
            if (bestLength > length) { values.add(data[i].toInt() and 0xFF); distances.add(0); i++; continue }
            values.add(length); distances.add(distance)
            for (k in 1 until length) insert(i + k)
            i += length
        } else if (length >= MIN_MATCH) {
            values.add(length); distances.add(distance)
            for (k in 0 until length) insert(i + k)
            i += length
        } else {
            values.add(data[i].toInt() and 0xFF); distances.add(0)
            insert(i)
            i++
        }
    }

    // ---- symbol counts, then the cheaper of the fixed and dynamic codings
    val literalCounts = IntArray(286); val distanceCounts = IntArray(30)
    var extraBits = 0L
    for (t in 0 until values.size) {
        if (distances.data[t] == 0) literalCounts[values.data[t]]++
        else {
            val ls = lengthSymbol(values.data[t]); val ds = distanceSymbol(distances.data[t])
            literalCounts[257 + ls]++; distanceCounts[ds]++
            extraBits += LENGTH_EXTRA[ls] + DIST_EXTRA[ds]
        }
    }
    literalCounts[END_OF_BLOCK]++

    var fixedBits = 3L + extraBits
    for (s in 0 until 286) fixedBits += literalCounts[s].toLong() * FIXED_LITERAL_LENGTHS[s]
    for (s in 0 until 30) fixedBits += distanceCounts[s].toLong() * FIXED_DISTANCE_LENGTHS[s]

    // Dynamic codes need two symbols at least; empty input has only the end of block, and is left to the fixed codes.
    val canBeDynamic = literalCounts.count { it > 0 } >= 2
    val literalLengths = if (canBeDynamic) huffmanLengths(literalCounts, 15) else IntArray(286)
    val distanceLengths = when (distanceCounts.count { it > 0 }) {
        0 -> IntArray(30)
        1 -> IntArray(30).also { it[distanceCounts.indexOfFirst { c -> c > 0 }] = 1 } // a lone distance code takes one bit
        else -> huffmanLengths(distanceCounts, 15)
    }
    val hlit = maxOf(257, literalLengths.indexOfLast { it > 0 } + 1)
    val hdist = maxOf(1, distanceLengths.indexOfLast { it > 0 } + 1)

    // The lengths themselves are run-length coded: 16 repeats the last length, 17 and 18 repeat zeros.
    val symbols = IntList(); val extras = IntList()
    val all = literalLengths.copyOf(hlit) + distanceLengths.copyOf(hdist)
    var at = 0
    while (at < all.size) {
        val v = all[at]
        var run = 1
        while (at + run < all.size && all[at + run] == v) run++
        at += run
        var left = run
        if (v == 0) {
            while (left >= 11) { val k = minOf(left, 138); symbols.add(18); extras.add(k - 11); left -= k }
            if (left >= 3) { symbols.add(17); extras.add(left - 3); left = 0 }
            repeat(left) { symbols.add(0); extras.add(0) }
        } else {
            symbols.add(v); extras.add(0); left--
            while (left >= 3) { val k = minOf(left, 6); symbols.add(16); extras.add(k - 3); left -= k }
            repeat(left) { symbols.add(v); extras.add(0) }
        }
    }
    val lengthCodeCounts = IntArray(19)
    for (k in 0 until symbols.size) lengthCodeCounts[symbols.data[k]]++
    if (lengthCodeCounts.count { it > 0 } < 2) lengthCodeCounts[if (lengthCodeCounts[0] > 0) 1 else 0] = 1
    val lengthCodeLengths = huffmanLengths(lengthCodeCounts, 7)
    val hclen = maxOf(4, CODE_LENGTH_ORDER.indexOfLast { lengthCodeLengths[it] > 0 } + 1)

    var dynamicBits = 3L + 14 + 3L * hclen + extraBits
    for (k in 0 until symbols.size) {
        val s = symbols.data[k]
        dynamicBits += lengthCodeLengths[s] + if (s == 16) 2 else if (s == 17) 3 else if (s == 18) 7 else 0
    }
    for (s in 0 until 286) dynamicBits += literalCounts[s].toLong() * literalLengths[s]
    for (s in 0 until 30) dynamicBits += distanceCounts[s].toLong() * distanceLengths[s]

    // ---- write
    val sink = BitWriter()
    sink.bits(1, 1) // the only block
    val litLengths: IntArray; val distLengths: IntArray
    if (!canBeDynamic || fixedBits <= dynamicBits) {
        sink.bits(1, 2)
        litLengths = FIXED_LITERAL_LENGTHS; distLengths = FIXED_DISTANCE_LENGTHS
    } else {
        sink.bits(2, 2)
        sink.bits(hlit - 257, 5); sink.bits(hdist - 1, 5); sink.bits(hclen - 4, 4)
        for (k in 0 until hclen) sink.bits(lengthCodeLengths[CODE_LENGTH_ORDER[k]], 3)
        val lengthCodes = canonicalCodes(lengthCodeLengths)
        for (k in 0 until symbols.size) {
            val s = symbols.data[k]
            sink.code(lengthCodes[s], lengthCodeLengths[s])
            if (s == 16) sink.bits(extras.data[k], 2) else if (s == 17) sink.bits(extras.data[k], 3) else if (s == 18) sink.bits(extras.data[k], 7)
        }
        litLengths = literalLengths; distLengths = distanceLengths
    }
    val litCodes = canonicalCodes(litLengths)
    val distCodes = canonicalCodes(distLengths)
    for (t in 0 until values.size) {
        if (distances.data[t] == 0) sink.code(litCodes[values.data[t]], litLengths[values.data[t]])
        else {
            val ls = lengthSymbol(values.data[t])
            sink.code(litCodes[257 + ls], litLengths[257 + ls]); sink.bits(values.data[t] - LENGTH_BASE[ls], LENGTH_EXTRA[ls])
            val ds = distanceSymbol(distances.data[t])
            sink.code(distCodes[ds], distLengths[ds]); sink.bits(distances.data[t] - DIST_BASE[ds], DIST_EXTRA[ds])
        }
    }
    sink.code(litCodes[END_OF_BLOCK], litLengths[END_OF_BLOCK])
    val body = sink.toByteArray()
    return ByteWriter(body.size + 6).apply {
        u8(0x78); u8(0x9C)
        bytes(body)
        u32(adler32(data))
    }.toByteArray()
}
