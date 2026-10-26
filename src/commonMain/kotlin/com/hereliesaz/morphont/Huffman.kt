package com.hereliesaz.morphont

/*
 * Prefix-code building shared by the two compressors (DEFLATE for WOFF, Brotli for WOFF2): code lengths from
 * symbol counts with a length limit, and the canonical codes those lengths give.
 */

internal class IntList {
    var data = IntArray(256)
    var size = 0
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
}

/** Code lengths (none above [maxBits]) for [counts]; 0 for a symbol never used. Needs at least two used symbols. */
internal fun huffmanLengths(counts: IntArray, maxBits: Int): IntArray {
    val used = counts.indices.filter { counts[it] > 0 }
    require(used.size >= 2)
    var floor = 1
    while (true) {
        // Raising every small count to [floor] flattens the tree until it fits.
        val weights = LongArray(used.size) { maxOf(counts[used[it]].toLong(), floor.toLong()) }
        val order = used.indices.sortedBy { weights[it] }
        val n = used.size
        val weight = LongArray(2 * n - 1)
        val parent = IntArray(2 * n - 1) { -1 }
        for ((rank, leaf) in order.withIndex()) weight[rank] = weights[leaf]
        var leafAt = 0; var nodeAt = n; var made = n
        fun take(): Int = when {
            leafAt < n && (nodeAt >= made || weight[leafAt] <= weight[nodeAt]) -> leafAt++
            else -> nodeAt++
        }
        while (made < 2 * n - 1) {
            val a = take(); val b = take()
            weight[made] = weight[a] + weight[b]
            parent[a] = made; parent[b] = made
            made++
        }
        val depth = IntArray(2 * n - 1)
        for (i in 2 * n - 3 downTo 0) depth[i] = depth[parent[i]] + 1
        val lengths = IntArray(counts.size)
        for ((rank, leaf) in order.withIndex()) lengths[used[leaf]] = depth[rank]
        if (lengths.max() <= maxBits) return lengths
        floor *= 2
    }
}

/** The codes for [lengths], shortest first and by symbol within a length. */
internal fun canonicalCodes(lengths: IntArray): IntArray {
    val maxLength = lengths.maxOrNull() ?: 0
    val perLength = IntArray(maxLength + 2)
    for (l in lengths) if (l > 0) perLength[l]++
    val next = IntArray(maxLength + 2)
    var code = 0
    for (l in 1..maxLength) { code = (code + perLength[l - 1]) shl 1; next[l] = code }
    val codes = IntArray(lengths.size)
    for (s in lengths.indices) if (lengths[s] > 0) codes[s] = next[lengths[s]]++
    return codes
}

/** Bits packed least-significant first, the way both DEFLATE and Brotli read them. */
internal class BitWriter {
    private val out = ByteWriter(4096)
    private var acc = 0L
    private var count = 0

    fun bits(value: Int, n: Int) {
        if (n == 0) return
        acc = acc or ((value.toLong() and ((1L shl n) - 1)) shl count)
        count += n
        while (count >= 8) { out.u8((acc and 0xFF).toInt()); acc = acc ushr 8; count -= 8 }
    }

    /** A prefix code is read most-significant bit first, so its bits go out from the top. */
    fun code(code: Int, length: Int) {
        var reversed = 0
        for (i in 0 until length) if (code and (1 shl i) != 0) reversed = reversed or (1 shl (length - 1 - i))
        bits(reversed, length)
    }

    fun alignToByte() { if (count > 0) { out.u8((acc and 0xFF).toInt()); acc = 0; count = 0 } }
    fun bytes(b: ByteArray, from: Int, to: Int) { check(count == 0); out.bytes(b, from, to) }
    fun toByteArray(): ByteArray { alignToByte(); return out.toByteArray() }

    /** What has been written, padded to a byte, and how many of those bits are real. */
    fun finish(): Pair<ByteArray, Int> { val length = out.size * 8 + count; return toByteArray() to length }

    /** Continues this stream with the first [length] bits of [source], whatever bit this one is at. */
    fun append(source: ByteArray, length: Int) {
        var left = length
        for (b in source) {
            if (left >= 8) { bits(b.toInt() and 0xFF, 8); left -= 8 }
            else { if (left > 0) bits(b.toInt() and ((1 shl left) - 1), left); left = 0 }
        }
    }
}
