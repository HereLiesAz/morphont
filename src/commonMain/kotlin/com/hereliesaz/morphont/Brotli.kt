package com.hereliesaz.morphont

/*
 * A Brotli compressor (RFC 7932) in plain Kotlin, for the one stream a WOFF2 file holds. Every dictionary-free
 * feature it needs is here and nothing else: one literal tree, one command tree, one distance tree per
 * meta-block, explicit distance codes, a 4 MB window, and LZ77 matches found with hash chains. It does not use
 * Brotli's built-in dictionary, context modelling, block splitting or the "last distance" codes, so it is not as
 * tight as the reference encoder, but every stream it writes is a valid Brotli stream.
 *
 * A meta-block that would come out larger than the bytes it holds is written uncompressed instead.
 */

private const val WINDOW_BITS = 22
private const val MAX_DISTANCE = (1 shl WINDOW_BITS) - 16
private const val MIN_MATCH = 4
private const val MAX_CHAIN = 96
private const val HASH_BITS = 16
private const val MAX_BLOCK = 1 shl 24
private const val MAX_CODE_BITS = 15

// ---------------------------------------------------------------- prefix codes

private val CODE_LENGTH_ORDER = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)

// How each code length code length (0..5) is written: the value for the bit writer (first bit read is the lowest) and its size.
private val CLC_BITS = intArrayOf(0b00, 0b0111, 0b011, 0b10, 0b01, 0b1111)
private val CLC_COUNT = intArrayOf(2, 4, 3, 2, 2, 4)

/** A prefix code ready to write symbols with. [lengths] are what the decoder will rebuild from what [writeCode] sends. */
private class PrefixCode(val lengths: IntArray, val codes: IntArray)

private fun alphabetBits(size: Int): Int { var n = 0; while ((1 shl n) < size) n++; return n }

/** Appends the symbols of a zero run, in Brotli's repeat-code form. Each entry is the code length symbol and its extra bits. */
private fun zeroRun(out: IntList, extra: IntList, count: Int) {
    var reps = count
    if (reps == 11) { out.add(0); extra.add(0); reps-- }
    if (reps < 3) { repeat(reps) { out.add(0); extra.add(0) }; return }
    reps -= 3
    val digits = ArrayList<Int>()
    while (true) { digits.add(reps and 7); reps = reps shr 3; if (reps == 0) break; reps-- }
    for (d in digits.reversed()) { out.add(17); extra.add(d) }
}

private fun repeatRun(out: IntList, extra: IntList, previous: Int, value: Int, count: Int) {
    var reps = count
    if (previous != value) { out.add(value); extra.add(0); reps-- }
    if (reps == 7) { out.add(value); extra.add(0); reps-- }
    if (reps < 3) { repeat(reps) { out.add(value); extra.add(0) }; return }
    reps -= 3
    val digits = ArrayList<Int>()
    while (true) { digits.add(reps and 3); reps = reps shr 2; if (reps == 0) break; reps-- }
    for (d in digits.reversed()) { out.add(16); extra.add(d) }
}

/** Chooses lengths for [counts] and writes the prefix code that describes them. Returns the code to write symbols with. */
private fun writePrefixCode(w: BitWriter, counts: IntArray): PrefixCode {
    val alphabet = counts.size
    val used = counts.indices.filter { counts[it] > 0 }
    if (used.isEmpty()) {
        // Nothing will ever be written with this code; the shortest legal description is one symbol.
        w.bits(1, 2); w.bits(0, 2); w.bits(0, alphabetBits(alphabet))
        return PrefixCode(IntArray(alphabet), IntArray(alphabet))
    }
    if (used.size <= 4) {
        // Simple prefix code: up to four symbols listed outright, with their lengths fixed by how many there are.
        val byCount = used.sortedByDescending { counts[it] }
        val lengths = IntArray(alphabet)
        val listed: List<Int>
        var treeSelect = 0
        when (used.size) {
            1 -> { listed = used; lengths[used[0]] = 0 }
            2 -> { listed = used; for (s in used) lengths[s] = 1 }
            3 -> {
                listed = listOf(byCount[0]) + byCount.drop(1).sorted()
                lengths[byCount[0]] = 1; lengths[byCount[1]] = 2; lengths[byCount[2]] = 2
            }
            else -> {
                val flat = 2L * used.sumOf { counts[it].toLong() }
                val skewed = counts[byCount[0]].toLong() + 2L * counts[byCount[1]] + 3L * (counts[byCount[2]].toLong() + counts[byCount[3]])
                if (skewed < flat) {
                    treeSelect = 1
                    listed = listOf(byCount[0], byCount[1]) + listOf(byCount[2], byCount[3]).sorted()
                    lengths[byCount[0]] = 1; lengths[byCount[1]] = 2; lengths[byCount[2]] = 3; lengths[byCount[3]] = 3
                } else {
                    listed = used
                    for (s in used) lengths[s] = 2
                }
            }
        }
        w.bits(1, 2) // HSKIP = 1: a simple code
        w.bits(used.size - 1, 2)
        for (s in listed) w.bits(s, alphabetBits(alphabet))
        if (used.size == 4) w.bits(treeSelect, 1)
        return PrefixCode(lengths, canonicalCodes(lengths))
    }

    // Complex prefix code: Huffman lengths, written run-length coded through a small code of their own.
    val lengths = huffmanLengths(counts, MAX_CODE_BITS)
    val last = lengths.indexOfLast { it > 0 }
    val symbols = IntList(); val extras = IntList()
    var previous = 8
    var i = 0
    while (i <= last) {
        val v = lengths[i]
        var run = 1
        while (i + run <= last && lengths[i + run] == v) run++
        i += run
        if (v == 0) zeroRun(symbols, extras, run) else { repeatRun(symbols, extras, previous, v, run); previous = v }
    }
    val symbolCounts = IntArray(18)
    for (k in 0 until symbols.size) symbolCounts[symbols.data[k]]++
    // The code for the lengths needs two symbols at least; a spare one costs nothing.
    if (symbolCounts.count { it > 0 } < 2) symbolCounts[if (symbolCounts[0] > 0) 1 else 0] = 1
    val clLengths = huffmanLengths(symbolCounts, 5)
    val clCodes = canonicalCodes(clLengths)

    w.bits(0, 2) // HSKIP = 0
    val lastOrdered = CODE_LENGTH_ORDER.indexOfLast { clLengths[it] > 0 }
    for (k in 0..lastOrdered) { val l = clLengths[CODE_LENGTH_ORDER[k]]; w.bits(CLC_BITS[l], CLC_COUNT[l]) }
    for (k in 0 until symbols.size) {
        val s = symbols.data[k]
        w.code(clCodes[s], clLengths[s])
        if (s == 16) w.bits(extras.data[k], 2) else if (s == 17) w.bits(extras.data[k], 3)
    }
    return PrefixCode(lengths, canonicalCodes(lengths))
}

// ---------------------------------------------------------------- commands

private val INSERT_BASE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594)
private val INSERT_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
private val COPY_BASE = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118)
private val COPY_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)

private fun codeFor(base: IntArray, value: Int): Int {
    var i = base.size - 1
    while (base[i] > value) i--
    return i
}

/** The insert-and-copy symbol for an insert code and copy code, in a cell that carries an explicit distance. */
private fun commandSymbol(insertCode: Int, copyCode: Int): Int {
    val cell = when {
        insertCode < 8 && copyCode < 8 -> 2
        insertCode < 8 && copyCode < 16 -> 3
        insertCode < 16 && copyCode < 8 -> 4
        insertCode < 16 && copyCode < 16 -> 5
        insertCode < 8 -> 6
        copyCode < 8 -> 7
        insertCode < 16 -> 8
        copyCode < 16 -> 9
        else -> 10
    }
    return cell * 64 + (insertCode and 7) * 8 + (copyCode and 7)
}

/** Distance symbol and extra bits for [distance], with no direct codes and no postfix bits. */
private fun distanceSymbol(distance: Int): Pair<Int, Pair<Int, Int>> {
    val x = distance + 3
    var bits = 0
    while ((x shr (bits + 1)) != 0) bits++ // floor(log2(x))
    val extraBits = bits - 1
    val low = (x shr extraBits) and 1
    return (16 + (extraBits - 1) * 2 + low) to ((x and ((1 shl extraBits) - 1)) to extraBits)
}

/** [data] [from] until [to] as the body of one compressed Brotli meta-block (without its header): the bytes, and how many bits of them count. */
private fun compressBlock(data: ByteArray, from: Int, to: Int): Pair<ByteArray, Int> {
    val n = to - from
    // ---- LZ77: commands are (literals before, copy length, distance)
    val inserts = IntList(); val copies = IntList(); val distances = IntList()
    val head = IntArray(1 shl HASH_BITS) { -1 }
    val prev = IntArray(n)
    fun at(i: Int) = data[from + i].toInt() and 0xFF
    fun hash(i: Int): Int =
        ((at(i) * 506832829) xor (at(i + 1) * 2654435761L.toInt()) xor (at(i + 2) * 40503) xor (at(i + 3) * 69069)) ushr (32 - HASH_BITS)
    fun insert(i: Int) { if (i + MIN_MATCH <= n) { val h = hash(i); prev[i] = head[h]; head[h] = i } }
    var bestLength = 0; var bestDistance = 0
    fun find(i: Int) {
        bestLength = 0; bestDistance = 0
        if (i + MIN_MATCH > n) return
        var candidate = head[hash(i)]
        var chain = 0
        val limit = n - i
        while (candidate >= 0 && i - candidate <= MAX_DISTANCE && chain < MAX_CHAIN) {
            var l = 0
            while (l < limit && data[from + candidate + l] == data[from + i + l]) l++
            if (l > bestLength) { bestLength = l; bestDistance = i - candidate; if (l == limit) break }
            candidate = prev[candidate]
            chain++
        }
        if (bestLength < MIN_MATCH) bestLength = 0
    }

    var i = 0; var literalStart = 0
    while (i < n) {
        find(i)
        var length = bestLength; var distance = bestDistance
        if (length > 0 && length < 24 && i + 1 < n) {
            insert(i)
            find(i + 1)
            if (bestLength > length + 1) { i++; continue } // a longer match starts one byte on: this byte is a literal
            for (k in 1 until length) insert(i + k)
        } else if (length > 0) {
            for (k in 0 until length) insert(i + k)
        }
        if (length == 0) { insert(i); i++; continue }
        inserts.add(i - literalStart); copies.add(length); distances.add(distance)
        i += length
        literalStart = i
    }
    val trailing = n - literalStart
    if (trailing > 0 || inserts.size == 0) { inserts.add(trailing); copies.add(0); distances.add(0) }

    // ---- statistics
    val literalCounts = IntArray(256); val commandCounts = IntArray(704); val distanceCounts = IntArray(64)
    var cursor = 0
    for (c in 0 until inserts.size) {
        val insertLength = inserts.data[c]; val copyLength = copies.data[c]
        val ic = codeFor(INSERT_BASE, insertLength)
        val cc = if (copyLength == 0) 0 else codeFor(COPY_BASE, copyLength)
        commandCounts[commandSymbol(ic, cc)]++
        for (k in 0 until insertLength) literalCounts[at(cursor + k)]++
        cursor += insertLength + copyLength
        if (copyLength > 0) distanceCounts[distanceSymbol(distances.data[c]).first]++
    }

    // ---- write
    val w = BitWriter()
    w.bits(0, 1); w.bits(0, 1); w.bits(0, 1)   // one block type for literals, commands and distances
    w.bits(0, 2); w.bits(0, 4)                 // no postfix bits, no direct distance codes
    w.bits(0, 2)                               // literal context mode
    w.bits(0, 1); w.bits(0, 1)                 // one literal tree, one distance tree
    val literalCode = writePrefixCode(w, literalCounts)
    val commandCode = writePrefixCode(w, commandCounts)
    val distanceCode = writePrefixCode(w, distanceCounts)
    cursor = 0
    for (c in 0 until inserts.size) {
        val insertLength = inserts.data[c]; val copyLength = copies.data[c]
        val ic = codeFor(INSERT_BASE, insertLength)
        val cc = if (copyLength == 0) 0 else codeFor(COPY_BASE, copyLength)
        val symbol = commandSymbol(ic, cc)
        w.code(commandCode.codes[symbol], commandCode.lengths[symbol])
        w.bits(insertLength - INSERT_BASE[ic], INSERT_EXTRA[ic])
        if (copyLength > 0) w.bits(copyLength - COPY_BASE[cc], COPY_EXTRA[cc])
        for (k in 0 until insertLength) {
            val b = at(cursor + k)
            w.code(literalCode.codes[b], literalCode.lengths[b])
        }
        cursor += insertLength + copyLength
        if (copyLength > 0) {
            val (symbolD, extra) = distanceSymbol(distances.data[c])
            w.code(distanceCode.codes[symbolD], distanceCode.lengths[symbolD])
            w.bits(extra.first, extra.second)
        }
    }
    return w.finish()
}

/** [data] as a Brotli stream: compressed meta-blocks, each stored uncompressed instead if that is smaller. */
internal fun brotliCompress(data: ByteArray): ByteArray {
    val stream = BitWriter()
    // WBITS: 22 is a 1, then 22 - 17 in three bits.
    stream.bits(1, 1); stream.bits(WINDOW_BITS - 17, 3)
    var at = 0
    while (at < data.size) {
        val size = minOf(MAX_BLOCK, data.size - at)
        val (packed, packedBits) = compressBlock(data, at, at + size)
        val minusOne = size - 1
        val nibbles = when { minusOne < (1 shl 16) -> 4; minusOne < (1 shl 20) -> 5; else -> 6 }
        stream.bits(0, 1)                  // ISLAST = 0
        stream.bits(nibbles - 4, 2)        // MNIBBLES
        stream.bits(minusOne, nibbles * 4) // MLEN - 1
        if (packedBits >= size * 8) {
            stream.bits(1, 1)              // ISUNCOMPRESSED
            stream.alignToByte()
            stream.bytes(data, at, at + size)
        } else {
            stream.bits(0, 1)              // ISUNCOMPRESSED = 0
            stream.append(packed, packedBits)
        }
        at += size
    }
    stream.bits(1, 1); stream.bits(1, 1)   // ISLAST, ISLASTEMPTY
    return stream.toByteArray()
}
