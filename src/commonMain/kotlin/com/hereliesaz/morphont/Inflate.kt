package com.hereliesaz.morphont

/**
 * A raw DEFLATE (RFC 1951) decoder in plain Kotlin: what reading a zip-based `.azp` package needs on
 * every target, the browser build included. Input is untrusted, so output is capped and every malformed
 * stream ends in an [IllegalArgumentException], never an index error.
 */
object Inflate {
    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** Canonical Huffman table: symbol count per code length, and the symbols in code order. */
    private class Huffman(lengths: IntArray) {
        val counts = IntArray(16)
        val symbols = IntArray(lengths.size)

        init {
            for (l in lengths) counts[l]++
            counts[0] = 0
            val offsets = IntArray(16)
            for (i in 1 until 16) offsets[i] = offsets[i - 1] + counts[i - 1]
            for ((sym, l) in lengths.withIndex()) if (l != 0) symbols[offsets[l]++] = sym
        }
    }

    private val fixedLit by lazy { Huffman(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }) }
    private val fixedDist by lazy { Huffman(IntArray(30) { 5 }) }

    private class Decoder(val data: ByteArray, var pos: Int, val limit: Int, sizeHint: Int) {
        private var bitBuf = 0
        private var bitCount = 0
        var out = ByteArray(sizeHint.coerceIn(1024, maxOf(1024, limit)))
        var n = 0

        fun bit(): Int {
            if (bitCount == 0) {
                require(pos < data.size) { "Truncated deflate stream" }
                bitBuf = data[pos++].toInt() and 0xff
                bitCount = 8
            }
            val b = bitBuf and 1
            bitBuf = bitBuf ushr 1
            bitCount--
            return b
        }

        fun bits(count: Int): Int {
            var v = 0
            for (i in 0 until count) v = v or (bit() shl i)
            return v
        }

        fun alignToByte() { bitCount = 0 }

        fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1 until 16) {
                code = code or bit()
                val count = h.counts[len]
                if (code - count < first) return h.symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("Invalid Huffman code")
        }

        fun put(b: Byte) {
            require(n < limit) { "Deflate output exceeds $limit bytes" }
            if (n == out.size) out = out.copyOf(minOf(limit, out.size * 2))
            out[n++] = b
        }

        fun stored() {
            alignToByte()
            require(pos + 4 <= data.size) { "Truncated stored block" }
            val len = (data[pos].toInt() and 0xff) or (data[pos + 1].toInt() and 0xff shl 8)
            val nlen = (data[pos + 2].toInt() and 0xff) or (data[pos + 3].toInt() and 0xff shl 8)
            require(len == nlen.inv() and 0xffff) { "Corrupt stored block" }
            pos += 4
            require(pos + len <= data.size) { "Truncated stored block" }
            for (i in 0 until len) put(data[pos + i])
            pos += len
        }

        fun dynamicTables(): Pair<Huffman, Huffman> {
            val hlit = bits(5) + 257
            val hdist = bits(5) + 1
            val hclen = bits(4) + 4
            require(hlit <= 286 && hdist <= 30) { "Invalid dynamic block header" }
            val clLengths = IntArray(19)
            for (i in 0 until hclen) clLengths[CL_ORDER[i]] = bits(3)
            val cl = Huffman(clLengths)
            val lengths = IntArray(hlit + hdist)
            var i = 0
            while (i < lengths.size) {
                val sym = decode(cl)
                val (value, count) = when {
                    sym < 16 -> sym to 1
                    sym == 16 -> { require(i > 0) { "Repeat with no previous length" }; lengths[i - 1] to 3 + bits(2) }
                    sym == 17 -> 0 to 3 + bits(3)
                    else -> 0 to 11 + bits(7)
                }
                require(i + count <= lengths.size) { "Too many code lengths" }
                repeat(count) { lengths[i++] = value }
            }
            require(lengths[256] != 0) { "No end-of-block code" }
            return Huffman(lengths.copyOfRange(0, hlit)) to Huffman(lengths.copyOfRange(hlit, lengths.size))
        }

        fun codes(lit: Huffman, dist: Huffman) {
            while (true) {
                val sym = decode(lit)
                when {
                    sym < 256 -> put(sym.toByte())
                    sym == 256 -> return
                    else -> {
                        val li = sym - 257
                        require(li < LEN_BASE.size) { "Invalid length code" }
                        val length = LEN_BASE[li] + bits(LEN_EXTRA[li])
                        val di = decode(dist)
                        require(di < DIST_BASE.size) { "Invalid distance code" }
                        val distance = DIST_BASE[di] + bits(DIST_EXTRA[di])
                        require(distance <= n) { "Distance too far back" }
                        // Byte by byte: a match may overlap the bytes it is copying.
                        repeat(length) { put(out[n - distance]) }
                    }
                }
            }
        }
    }

    /** Decompresses the raw deflate stream in [data] starting at [offset]; at most [limit] output bytes. */
    fun inflate(data: ByteArray, offset: Int = 0, limit: Int = 64 * 1024 * 1024, sizeHint: Int = 0): ByteArray {
        val d = Decoder(data, offset, limit, sizeHint)
        do {
            val last = d.bit()
            when (d.bits(2)) {
                0 -> d.stored()
                1 -> d.codes(fixedLit, fixedDist)
                2 -> d.dynamicTables().let { (lit, dist) -> d.codes(lit, dist) }
                else -> throw IllegalArgumentException("Invalid deflate block type")
            }
        } while (last == 0)
        return d.out.copyOf(d.n)
    }
}
