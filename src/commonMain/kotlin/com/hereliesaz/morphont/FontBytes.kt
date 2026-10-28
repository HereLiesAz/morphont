package com.hereliesaz.morphont

/**
 * Growable big-endian byte buffer: every binary font format Morphont writes
 * (sfnt, CFF, WOFF, WOFF2) is built from this. Nothing here reads.
 */
internal class ByteWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))
    var size = 0
        private set

    private fun room(extra: Int) {
        if (size + extra <= buf.size) return
        var capacity = buf.size
        while (capacity < size + extra) capacity = capacity * 2
        buf = buf.copyOf(capacity)
    }

    fun u8(v: Int) { room(1); buf[size++] = v.toByte() }
    fun u16(v: Int) { room(2); buf[size++] = (v shr 8).toByte(); buf[size++] = v.toByte() }
    fun i16(v: Int) = u16(v and 0xFFFF)
    fun u24(v: Int) { u8(v shr 16); u16(v and 0xFFFF) }
    fun u32(v: Long) { room(4); buf[size++] = (v shr 24).toByte(); buf[size++] = (v shr 16).toByte(); buf[size++] = (v shr 8).toByte(); buf[size++] = v.toByte() }
    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    /** A 16.16 fixed-point number. */
    fun fixed(v: Float) = i32(kotlin.math.round(v.toDouble() * 65536.0).toInt())

    /** A 2.14 fixed-point number, as used for variation coordinates. */
    fun f2dot14(v: Float) = i16(kotlin.math.round(v.toDouble() * 16384.0).toInt().coerceIn(-32768, 32767))

    fun tag(t: String) {
        require(t.length == 4) { "A font table tag is four characters: \"$t\"" }
        for (c in t) u8(c.code)
    }

    fun bytes(b: ByteArray, from: Int = 0, to: Int = b.size) {
        room(to - from)
        b.copyInto(buf, size, from, to)
        size += to - from
    }

    fun writer(w: ByteWriter) = bytes(w.buf, 0, w.size)

    /** Zero-fills up to a multiple of [n] bytes. */
    fun align(n: Int) { while (size % n != 0) u8(0) }

    fun setU16(at: Int, v: Int) { buf[at] = (v shr 8).toByte(); buf[at + 1] = v.toByte() }
    fun setU32(at: Int, v: Long) {
        buf[at] = (v shr 24).toByte(); buf[at + 1] = (v shr 16).toByte(); buf[at + 2] = (v shr 8).toByte(); buf[at + 3] = v.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** The sfnt table checksum: the sum of the table read as big-endian 32-bit words, zero-padded, modulo 2^32. */
internal fun tableChecksum(data: ByteArray): Long {
    var sum = 0L
    var i = 0
    while (i < data.size) {
        var word = 0L
        for (k in 0 until 4) word = (word shl 8) or (if (i + k < data.size) (data[i + k].toLong() and 0xFF) else 0L)
        sum = (sum + word) and 0xFFFFFFFFL
        i += 4
    }
    return sum
}

internal fun pad4(n: Int): Int = (n + 3) and 3.inv()
