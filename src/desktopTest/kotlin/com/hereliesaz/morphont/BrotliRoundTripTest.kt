package com.hereliesaz.morphont

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Round trips for the Brotli compressor through [BrotliTestDecoder]. */
class BrotliRoundTripTest {
    private fun roundTrip(label: String, data: ByteArray): ByteArray {
        val packed = brotliCompress(data)
        assertContentEquals(data, BrotliTestDecoder.decode(packed), label)
        return packed
    }

    @Test
    fun smallAndDegenerateInputs() {
        for (data in listOf(ByteArray(0), byteArrayOf(0), byteArrayOf(9, 9), ByteArray(3) { 5 }, "ab".encodeToByteArray(), "abcdabcdabcdabcd".encodeToByteArray())) {
            roundTrip("size ${data.size}", data)
        }
    }

    @Test
    fun alphabetsOfEverySizeAndSkew() {
        val random = Random(2)
        for (alphabet in listOf(1, 2, 3, 4, 5, 6, 8, 9, 17, 40, 100, 256)) for (skew in listOf(0.0, 0.5, 0.9)) {
            val data = ByteArray(6000) {
                var v = 0
                while (v < alphabet - 1 && random.nextDouble() < skew) v++
                (v * (256 / alphabet)).toByte()
            }
            roundTrip("alphabet $alphabet skew $skew", data)
        }
    }

    @Test
    fun repeatsAtEveryDistanceScale() {
        val random = Random(3)
        val pattern = random.nextBytes(900)
        for (gap in listOf(0, 1, 7, 100, 3000, 70_000)) {
            val out = ByteArrayOutputStream()
            out.write(pattern); out.write(random.nextBytes(gap)); out.write(pattern); out.write(pattern, 0, 400)
            roundTrip("gap $gap", out.toByteArray())
        }
    }

    @Test
    fun longRunsAndLongInserts() {
        roundTrip("run", ByteArray(300_000) { 7 })
        roundTrip("random", Random(4).nextBytes(100_000))
        val mixed = ByteArray(200_000) { if ((it / 5000) % 2 == 0) (it % 13).toByte() else Random(it).nextInt(256).toByte() }
        roundTrip("mixed", mixed)
    }

    @Test
    fun compressibleDataShrinksAndRandomDataDoesNot() {
        val text = "the quick brown fox jumps over the lazy dog. ".repeat(500).encodeToByteArray()
        assertTrue(roundTrip("text", text).size < text.size / 20)
        val random = Random(5).nextBytes(20_000)
        assertTrue(roundTrip("random", random).size <= random.size + 8, "incompressible data is stored, plus a few bytes of framing")
    }

    @Test
    fun aRealFontShrinks() {
        val corner = GlyphCorner(600f, mutableListOf(ContourData(mutableListOf(
            Pt(100f, 0f, true), Pt(500f, 0f, true), Pt(500f, 700f, true), Pt(100f, 700f, true),
        ))))
        val glyph = Glyph().also { it.corners["regular"] = corner }
        val project = (0 until 200).associate { ('A' + it).toString() to glyph }
        val font = exportFont(project, FontExportOptions("Round Trip")).bytes
        assertTrue(roundTrip("font", font).size < font.size * 3 / 4)
    }
}
