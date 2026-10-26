package com.hereliesaz.morphont

import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The zlib compressor and the WOFF/WOFF2 containers, checked with the JDK's own inflater. */
class FontContainerTest {
    private fun inflate(data: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArray(size + 1) // one spare byte, so a stream that runs long shows
        var at = 0
        while (!inflater.finished()) {
            val n = inflater.inflate(out, at, out.size - at)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            at += n
        }
        assertTrue(inflater.finished(), "the stream ends where the data does")
        assertEquals(size, at)
        inflater.end()
        return out.copyOf(size)
    }

    @Test
    fun zlibStreamsInflateToTheirInput() {
        val random = Random(7)
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(42),
            ByteArray(5000) { 0 },
            ByteArray(40_000) { (it % 251).toByte() },
            "the quick brown fox jumps over the lazy dog. ".repeat(900).encodeToByteArray(),
            random.nextBytes(30_000),
            ByteArray(70_000) { if (it % 7 == 0) random.nextInt(256).toByte() else (it % 13).toByte() },
        )
        for ((i, sample) in samples.withIndex()) {
            val packed = zlibCompress(sample)
            assertContentEquals(sample, inflate(packed, sample.size), "sample $i")
        }
    }

    @Test
    fun repetitiveDataShrinks() {
        val data = "abcabcabc".repeat(5000).encodeToByteArray()
        assertTrue(zlibCompress(data).size < data.size / 10)
    }

    private fun sampleFont(): ByteArray {
        val corner = GlyphCorner(600f, mutableListOf(ContourData(mutableListOf(
            Pt(100f, 0f, true), Pt(500f, 0f, true), Pt(500f, 700f, true), Pt(100f, 700f, true),
        ))))
        val glyph = Glyph().also { it.corners["regular"] = corner }
        return exportFont(mapOf("A" to glyph, "B" to glyph), FontExportOptions("Container Test")).bytes
    }

    private fun tablesOf(sfnt: ByteArray): Map<String, ByteArray> {
        val r = Reader(sfnt)
        return (0 until r.u16(4)).associate { i ->
            val rec = 12 + i * 16
            r.tag(rec) to sfnt.copyOfRange(r.u32(rec + 8).toInt(), (r.u32(rec + 8) + r.u32(rec + 12)).toInt())
        }
    }

    @Test
    fun woffHoldsTheSameTablesInflated() {
        val sfnt = sampleFont()
        val woff = woffFromSfnt(sfnt)
        val r = Reader(woff)
        assertEquals("wOFF", r.tag(0))
        assertEquals(r.u32(4), 0x00010000L)
        assertEquals(woff.size.toLong(), r.u32(8))
        val count = r.u16(12)
        val expected = tablesOf(sfnt)
        val sfntReader = Reader(sfnt)
        val checksums = (0 until sfntReader.u16(4)).associate { sfntReader.tag(12 + it * 16) to sfntReader.u32(12 + it * 16 + 4) }
        assertEquals(expected.size, count)
        var sfntSize = 12L + 16L * count
        for (i in 0 until count) {
            val rec = 44 + i * 20
            val tag = r.tag(rec)
            val offset = r.u32(rec + 4).toInt(); val packed = r.u32(rec + 8).toInt(); val original = r.u32(rec + 12).toInt()
            assertEquals(0, offset % 4, "$tag starts on a four-byte boundary")
            val body = woff.copyOfRange(offset, offset + packed)
            val table = if (packed < original) inflate(body, original) else body
            assertContentEquals(expected.getValue(tag), table, tag)
            assertEquals(checksums.getValue(tag), r.u32(rec + 16), "$tag checksum is the font's own")
            sfntSize += pad4(original)
        }
        assertEquals(sfntSize, r.u32(16))
    }

    @Test
    fun woff2HeaderAndDirectoryAreConsistent() {
        val sfnt = sampleFont()
        val woff2 = woff2FromSfnt(sfnt)
        val r = Reader(woff2)
        assertEquals("wOF2", r.tag(0))
        assertEquals(woff2.size.toLong(), r.u32(8), "length is the file's")
        assertEquals(0, woff2.size % 4)
        val count = r.u16(12)
        assertEquals(tablesOf(sfnt).size, count)
        // Walk the directory: glyf then loca, each stored untransformed.
        var pos = 48
        val tags = ArrayList<String>()
        var total = 0L
        repeat(count) {
            val flags = r.u8(pos++)
            val index = flags and 0x3F
            val tag = if (index == 63) r.tag(pos).also { pos += 4 } else listOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT", "EBLC", "gasp")[index]
            tags.add(tag)
            var length = 0L
            while (true) { val b = r.u8(pos++); length = (length shl 7) or (b and 0x7F).toLong(); if (b and 0x80 == 0) break }
            total += length
            if (tag == "glyf" || tag == "loca") assertEquals(3, flags ushr 6, "$tag uses the null transform")
        }
        assertEquals(tags.indexOf("glyf") + 1, tags.indexOf("loca"))
        assertEquals(tablesOf(sfnt).values.sumOf { it.size.toLong() }, total)
        // The Brotli stream: a 16-bit window, then uncompressed meta-blocks whose bytes are the tables.
        val compressedLength = r.u32(20).toInt()
        assertEquals(pad4(pos + compressedLength), woff2.size)
        assertEquals(0, woff2[pos].toInt() and 1, "WBITS: 16")
    }
}
