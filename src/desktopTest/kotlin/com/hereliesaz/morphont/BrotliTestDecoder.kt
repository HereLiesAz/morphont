package com.hereliesaz.morphont

import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

/**
 * A Brotli (RFC 7932) decoder for the tests, written from the RFC. It reads only what `brotliCompress` writes:
 * one block type of each kind, explicit distance codes, uncompressed meta-blocks, no built-in dictionary. That
 * keeps it short; the streams were also checked against the reference decoder while the compressor was written,
 * and this keeps them readable from here on.
 */
internal object BrotliTestDecoder {
    private class Bits(val data: ByteArray) {
        var pos = 0 // in bits
        private fun at(i: Int): Int = if (i shr 3 < data.size) (data[i shr 3].toInt() shr (i and 7)) and 1 else 0
        fun peek(n: Int): Int { var v = 0; for (i in 0 until n) v = v or (at(pos + i) shl i); return v }
        fun skip(n: Int) { pos += n }
        fun bit(): Int { check(pos < data.size * 8) { "Ran off the end of the stream" }; return at(pos++) }
        fun bits(n: Int): Int { var v = 0; for (i in 0 until n) v = v or (bit() shl i); return v }
        fun alignToByte() { pos = (pos + 7) and 7.inv() }
    }

    /** A canonical prefix code, read one bit at a time, most significant bit of the code first. */
    private class Code(val lengths: IntArray) {
        private val bySymbol = HashMap<Long, Int>()
        private val only: Int? = lengths.indices.singleOrNull { lengths[it] > 0 }

        init {
            var code = 0
            for (len in 1..15) {
                for (s in lengths.indices) if (lengths[s] == len) { bySymbol[(len.toLong() shl 32) or code.toLong()] = s; code++ }
                code = code shl 1
            }
        }

        fun read(b: Bits): Int {
            only?.let { return it } // a code of one symbol costs no bits
            var code = 0
            for (len in 1..15) {
                code = (code shl 1) or b.bit()
                bySymbol[(len.toLong() shl 32) or code.toLong()]?.let { return it }
            }
            error("Not a symbol of this prefix code")
        }
    }

    private val codeLengthOrder = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    private val clLength = intArrayOf(2, 2, 2, 3, 2, 2, 2, 4, 2, 2, 2, 3, 2, 2, 2, 4)
    private val clValue = intArrayOf(0, 4, 3, 2, 0, 4, 3, 1, 0, 4, 3, 2, 0, 4, 3, 5)

    private fun readCode(b: Bits, alphabet: Int): Code {
        val hskip = b.bits(2)
        if (hskip == 1) {
            val n = b.bits(2) + 1
            var width = 0
            while ((1 shl width) < alphabet) width++
            val symbols = IntArray(n) { b.bits(width) }
            val lengths = IntArray(alphabet)
            when (n) {
                1 -> lengths[symbols[0]] = 1
                2 -> symbols.forEach { lengths[it] = 1 }
                3 -> { lengths[symbols[0]] = 1; lengths[symbols[1]] = 2; lengths[symbols[2]] = 2 }
                else -> if (b.bit() == 0) symbols.forEach { lengths[it] = 2 } else {
                    lengths[symbols[0]] = 1; lengths[symbols[1]] = 2; lengths[symbols[2]] = 3; lengths[symbols[3]] = 3
                }
            }
            return Code(lengths)
        }
        val clLengths = IntArray(18)
        var space = 32
        var used = 0
        for (k in hskip until 18) {
            val index = b.peek(4)
            b.skip(clLength[index])
            val v = clValue[index]
            clLengths[codeLengthOrder[k]] = v
            if (v != 0) { space -= 32 shr v; used++; if (space <= 0) break }
        }
        check(used == 1 || space == 0) { "The code length code is not complete" }
        val clCode = Code(clLengths)
        val lengths = IntArray(alphabet)
        var symbol = 0; var previous = 8; var left = 32768; var repeat = 0; var repeatValue = 0
        while (symbol < alphabet && left > 0) {
            val c = clCode.read(b)
            if (c < 16) {
                repeat = 0
                lengths[symbol++] = c
                if (c != 0) { previous = c; left -= 32768 shr c }
            } else {
                val extra = if (c == 16) 2 else 3
                val value = if (c == 16) previous else 0
                if (repeatValue != value) { repeat = 0; repeatValue = value }
                val old = repeat
                if (repeat > 0) { repeat -= 2; repeat = repeat shl extra }
                repeat += b.bits(extra) + 3
                repeat(repeat - old) { lengths[symbol++] = value; if (value != 0) left -= 32768 shr value }
            }
        }
        check(left == 0) { "The prefix code is not complete" }
        return Code(lengths)
    }

    private val insertBase = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594)
    private val insertExtra = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
    private val copyBase = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118)
    private val copyExtra = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)
    private val insertRange = intArrayOf(0, 0, 0, 0, 1, 1, 0, 2, 1, 2, 2) // by cell of 64 command symbols
    private val copyRange = intArrayOf(0, 1, 0, 1, 0, 1, 2, 0, 2, 1, 2)

    fun decode(stream: ByteArray): ByteArray {
        val b = Bits(stream)
        if (b.bit() == 1 && b.bits(3) == 0) b.bits(3) // window size; this decoder keeps all output, so it needs no limit
        val out = ByteArrayOutputStream()
        val history = ArrayList<Byte>() // everything written so far, for copies
        fun put(v: Int) { out.write(v); history.add(v.toByte()) }
        while (true) {
            val last = b.bit()
            if (last == 1 && b.bit() == 1) break
            val nibbles = b.bits(2)
            check(nibbles != 3) { "Metadata blocks are not supported here" }
            val length = b.bits((nibbles + 4) * 4) + 1
            if (last == 0 && b.bit() == 1) {
                b.alignToByte()
                repeat(length) { put(b.bits(8)) }
                continue
            }
            check(b.bit() == 0 && b.bit() == 0 && b.bit() == 0) { "Only one block type of each kind" }
            check(b.bits(2) == 0 && b.bits(4) == 0) { "No postfix or direct distance codes" }
            b.bits(2) // literal context mode
            check(b.bit() == 0 && b.bit() == 0) { "One literal tree and one distance tree" }
            val literals = readCode(b, 256)
            val commands = readCode(b, 704)
            val distances = readCode(b, 64)
            var produced = 0
            while (produced < length) {
                val symbol = commands.read(b)
                val cell = symbol shr 6
                check(cell >= 2) { "Implicit distances are not supported here" }
                val insertCode = insertRange[cell] * 8 + ((symbol shr 3) and 7)
                val copyCode = copyRange[cell] * 8 + (symbol and 7)
                val insertLength = insertBase[insertCode] + b.bits(insertExtra[insertCode])
                val copyLength = copyBase[copyCode] + b.bits(copyExtra[copyCode])
                repeat(insertLength) { put(literals.read(b)) }
                produced += insertLength
                if (produced >= length) break
                val code = distances.read(b)
                check(code >= 16) { "Only explicit distance codes are used" }
                val extraBits = 1 + ((code - 16) shr 1)
                val offset = ((2 + ((code - 16) and 1)) shl extraBits) - 4
                val distance = offset + b.bits(extraBits) + 1
                check(distance <= history.size) { "A copy reaches before the start" }
                repeat(copyLength) { put(history[history.size - distance].toInt() and 0xFF) }
                produced += copyLength
            }
            assertEquals(length, produced, "a meta-block holds exactly its declared length")
        }
        return out.toByteArray()
    }
}
