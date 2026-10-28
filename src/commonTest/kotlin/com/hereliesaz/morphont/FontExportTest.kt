package com.hereliesaz.morphont

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontExportTest {
    /** A smooth four-node blob of radius [r] around ([cx], [cy]): nodes at the compass points, cubic arms between. */
    private fun blob(r: Float, cx: Float = 400f, cy: Float = 350f): ContourData {
        val k = r * 0.5523f
        val pts = mutableListOf(
            Pt(cx + r, cy, true, true), Pt(cx + r, cy + k, false), Pt(cx + k, cy + r, false),
            Pt(cx, cy + r, true, true), Pt(cx - k, cy + r, false), Pt(cx - r, cy + k, false),
            Pt(cx - r, cy, true, true), Pt(cx - r, cy - k, false), Pt(cx - k, cy - r, false),
            Pt(cx, cy - r, true, true), Pt(cx + k, cy - r, false), Pt(cx + r, cy - k, false),
        )
        return ContourData(pts)
    }

    private fun corner(width: Float, r: Float) = GlyphCorner(width, mutableListOf(blob(r)))

    /** A glyph drawn at Regular and at both ends of weight; every other anchor is left empty. */
    private fun weighted(regular: Float = 250f, thin: Float = 150f, black: Float = 330f): Glyph {
        val g = Glyph(metrics = FontMetrics())
        g.corners["regular"] = corner(800f, regular)
        g.corners[Axis.WEIGHT.lo] = corner(700f, thin)
        g.corners[Axis.WEIGHT.hi] = corner(900f, black)
        return g
    }

    private fun parse(bytes: ByteArray) = VariableFont.parse(bytes, requireVariable = false)

    private fun onCurveExtent(contours: List<ContourData>): List<Float> {
        val on = contours.flatMap { it.points }.filter { it.onCurve }
        return listOf(on.minOf { it.x }, on.maxOf { it.x }, on.minOf { it.y }, on.maxOf { it.y })
    }

    @Test
    fun staticTrueTypeCarriesTheRegularOutlineAndAdvance() {
        val result = exportFont(mapOf("A" to weighted()), FontExportOptions("Test Face"))
        val font = parse(result.bytes)
        assertEquals(1000, font.unitsPerEm)
        assertEquals(800f, font.advanceWidth('A'.code))
        val contours = assertNotNull(font.outlineAt('A'.code, emptyMap()))
        assertEquals(1, contours.size)
        val (minX, maxX, minY, maxY) = onCurveExtent(contours)
        assertEquals(150f, minX, 1f); assertEquals(650f, maxX, 1f)
        assertEquals(100f, minY, 1f); assertEquals(600f, maxY, 1f)
        assertEquals("Test-Face-Regular.ttf", result.fileName)
        assertEquals("font/ttf", result.mimeType)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test
    fun staticInstanceFreezesTheChosenAxisPosition() {
        val thin = exportFont(mapOf("A" to weighted()), FontExportOptions("Test", instance = mapOf(Axis.WEIGHT.tag to 0f)))
        val font = parse(thin.bytes)
        assertEquals(700f, font.advanceWidth('A'.code))
        val (minX, maxX) = onCurveExtent(assertNotNull(font.outlineAt('A'.code, emptyMap())))
        assertEquals(250f, minX, 1f); assertEquals(550f, maxX, 1f)
    }

    @Test
    fun everyGlyphIsReachableByItsCharacter() {
        val project = mapOf("A" to weighted(), "b" to weighted(), "uni00E9" to weighted(), "😀" to weighted(), "alt.one" to weighted())
        val font = parse(exportFont(project, FontExportOptions("Test")).bytes)
        assertEquals(listOf('A'.code, 'b'.code, 0xE9, 0x1F600), font.characterCodepoints())
    }

    @Test
    fun aCharacterClaimedTwiceGoesToTheGlyphNamedForIt() {
        val result = exportFont(mapOf("A" to weighted(), "uni0041" to weighted(250f, 150f, 300f)), FontExportOptions("Test"))
        assertEquals(listOf('A'.code), parse(result.bytes).characterCodepoints())
        assertTrue(result.warnings.any { "uni0041" in it && "U+0041" in it }, result.warnings.toString())
    }

    @Test
    fun codepointOfGlyphNameReadsCharactersAndProductionNames() {
        assertEquals(0x41, codepointOfGlyphName("A"))
        assertEquals(0x41, codepointOfGlyphName("uni0041"))
        assertEquals(0x1F600, codepointOfGlyphName("u1F600"))
        assertEquals(0x1F600, codepointOfGlyphName("😀"))
        assertNull(codepointOfGlyphName("A.alt"))
        assertNull(codepointOfGlyphName("uniD800"))
        assertNull(codepointOfGlyphName("space"))
    }

    @Test
    fun variableFontHasAnAxisAndEveryDrawnStopLandsWhereMorphontPutsIt() {
        val glyph = weighted()
        val result = exportFont(mapOf("A" to glyph), FontExportOptions("Test", outlines = FontOutlines.VARIABLE))
        val font = parse(result.bytes)
        assertEquals(listOf("wght"), font.axes.map { it.tag })
        val wght = font.axes.single()
        assertEquals(100f, wght.min); assertEquals(400f, wght.default); assertEquals(900f, wght.max)
        assertEquals("Test-VF.ttf", result.fileName)

        // Each stop of the axis (0, 0.25, 0.5, 0.75, 1 of the way) reproduces the interpolated outline to the grid.
        val range = DEFAULT_AXIS_RANGES.getValue("wght")
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val expected = onCurveExtent(interpolateGlyph(glyph.corners, mapOf("wght" to t)).contours)
            val got = onCurveExtent(assertNotNull(font.outlineAt('A'.code, mapOf("wght" to axisValueAt(range, t)))))
            for (i in expected.indices) assertEquals(expected[i], got[i], 1.5f, "t=$t extent $i")
        }
    }

    @Test
    fun variableFontBetweenStopsStaysCloseToMorphontsOwnCurve() {
        val glyph = weighted(regular = 300f, thin = 100f, black = 400f)
        val font = parse(exportFont(mapOf("A" to glyph), FontExportOptions("Test", outlines = FontOutlines.VARIABLE)).bytes)
        val range = DEFAULT_AXIS_RANGES.getValue("wght")
        for (t in listOf(0.1f, 0.35f, 0.6f, 0.9f)) {
            val expected = onCurveExtent(interpolateGlyph(glyph.corners, mapOf("wght" to t)).contours)
            val got = onCurveExtent(assertNotNull(font.outlineAt('A'.code, mapOf("wght" to axisValueAt(range, t)))))
            for (i in expected.indices) assertTrue(abs(expected[i] - got[i]) < 6f, "t=$t extent $i: ${expected[i]} vs ${got[i]}")
        }
    }

    @Test
    fun variableAxisRangesComeFromTheOptions() {
        val ranges = DEFAULT_AXIS_RANGES + (Axis.WEIGHT.tag to AxisRange(1f, 300f, 1000f))
        val font = parse(exportFont(mapOf("A" to weighted()), FontExportOptions("T", outlines = FontOutlines.VARIABLE, axisRanges = ranges)).bytes)
        val wght = font.axes.single()
        assertEquals(1f, wght.min); assertEquals(300f, wght.default); assertEquals(1000f, wght.max)
    }

    @Test
    fun aRangeThatStopsAtItsDefaultLeavesThatSideOut() {
        val ranges = DEFAULT_AXIS_RANGES + (Axis.WEIGHT.tag to AxisRange(100f, 400f, 400f))
        val font = parse(exportFont(mapOf("A" to weighted()), FontExportOptions("T", outlines = FontOutlines.VARIABLE, axisRanges = ranges)).bytes)
        val (_, maxX) = onCurveExtent(assertNotNull(font.outlineAt('A'.code, mapOf("wght" to 400f))))
        assertEquals(650f, maxX, 1f)
        val (minThin, _) = onCurveExtent(assertNotNull(font.outlineAt('A'.code, mapOf("wght" to 100f))))
        assertEquals(250f, minThin, 1.5f)
    }

    @Test
    fun variableExportNeedsADrawnAxis() {
        val plain = Glyph().also { it.corners["regular"] = corner(500f, 200f) }
        val e = assertFailsWith<IllegalArgumentException> {
            exportFont(mapOf("A" to plain), FontExportOptions("T", outlines = FontOutlines.VARIABLE))
        }
        assertContains(e.message!!, "nothing to vary")
    }

    @Test
    fun aBackwardsRangeIsRefused() {
        val ranges = DEFAULT_AXIS_RANGES + (Axis.WEIGHT.tag to AxisRange(500f, 400f, 900f))
        val e = assertFailsWith<IllegalArgumentException> {
            exportFont(mapOf("A" to weighted()), FontExportOptions("T", outlines = FontOutlines.VARIABLE, axisRanges = ranges))
        }
        assertContains(e.message!!, "Weight")
    }

    @Test
    fun aGlyphWhoseAnchorsDisagreeIsExportedFromRegularAndSaysSo() {
        val broken = weighted()
        broken.corners[Axis.WEIGHT.hi] = GlyphCorner(900f, mutableListOf(ContourData(mutableListOf(Pt(0f, 0f, true), Pt(100f, 0f, true), Pt(100f, 100f, true)))))
        val result = exportFont(mapOf("A" to broken, "B" to weighted()), FontExportOptions("T", outlines = FontOutlines.VARIABLE))
        assertTrue(result.warnings.any { "(Regular, or the first one drawn): A" in it }, result.warnings.toString())
        val font = parse(result.bytes)
        assertNotNull(font.outlineAt('A'.code, mapOf("wght" to 100f)))
    }

    @Test
    fun anEmptyProjectCannotBeExported() {
        assertFailsWith<IllegalArgumentException> { exportFont(emptyMap(), FontExportOptions("T")) }
    }

    @Test
    fun anEmptyGlyphKeepsItsAdvance() {
        val space = Glyph().also { it.corners["regular"] = GlyphCorner(310f) }
        val font = parse(exportFont(mapOf(" " to space, "A" to weighted()), FontExportOptions("T")).bytes)
        assertEquals(310f, font.advanceWidth(' '.code))
        assertTrue(assertNotNull(font.outlineAt(' '.code, emptyMap())).isEmpty())
    }

    @Test
    fun glyphsDrawnOnAnotherGridAreScaledToTheFonts() {
        val big = weighted().copy(metrics = FontMetrics(unitsPerEm = 2000f, ascender = 1600f, capHeight = 1400f, xHeight = 1000f, descender = -400f))
        val small = weighted().copy(metrics = FontMetrics(unitsPerEm = 1000f))
        val font = parse(exportFont(mapOf("A" to big, "B" to small, "C" to small), FontExportOptions("T")).bytes)
        assertEquals(1000, font.unitsPerEm)
        assertEquals(400f, font.advanceWidth('A'.code))
    }

    @Test
    fun cubicCurvesBecomeQuadraticsThatStayWithinTolerance() {
        val contour = fontContourOf(blob(300f), 1f)!!
        val counts = quadCountsFor(contour, 0.5f)
        val tt = fontContourToTrueType(contour, counts)
        // Every join is an explicit on-curve point, so the four compass nodes are all there.
        assertTrue(tt.onCurve.count { it } >= 4)
        assertTrue(counts.all { it in 1..12 })
        assertTrue(counts.any { it > 1 }, "a quarter circle needs more than one quadratic at half a unit")
    }

    @Test
    fun theSameCubicNeedsOneQuadraticWhenItWasOne() {
        // A cubic raised from a quadratic is described exactly by that quadratic.
        val q = FontSeg(3, 100 + 2 * (150 - 100) / 3, 0 + 2 * (300 - 0) / 3, 200 + 2 * (150 - 200) / 3, 0 + 2 * (300 - 0) / 3, 200, 0)
        assertEquals(1, quadsNeeded(q, 100, 0, 1f))
    }

    @Test
    fun theSfntDirectoryChecksumsAddUpToTheMagicNumber() {
        val bytes = exportFont(mapOf("A" to weighted()), FontExportOptions("T")).bytes
        assertEquals(0xB1B0AFBAL, tableChecksum(bytes))
    }

    @Test
    fun axisValuesWalkFromMinThroughDefaultToMax() {
        val r = AxisRange(100f, 400f, 900f)
        assertEquals(100f, axisValueAt(r, 0f)); assertEquals(250f, axisValueAt(r, 0.25f))
        assertEquals(400f, axisValueAt(r, 0.5f)); assertEquals(650f, axisValueAt(r, 0.75f)); assertEquals(900f, axisValueAt(r, 1f))
    }

    /** The bytes of table [tag] in a finished font file, found through its table directory. */
    private fun table(font: ByteArray, tag: String): ByteArray {
        val r = Reader(font)
        for (i in 0 until r.u16(4)) {
            val rec = 12 + i * 16
            if (r.tag(rec) == tag) return font.copyOfRange(r.u32(rec + 8).toInt(), (r.u32(rec + 8) + r.u32(rec + 12)).toInt())
        }
        error("No $tag table")
    }

    @Test
    fun everyGvarTupleSetSaysExplicitlyThatItMovesEveryPoint() {
        // A tuple with no point numbers of its own takes the shared ones; without a shared set (a count of zero
        // meaning "all points") other readers find no points at all, so the marker has to be there.
        val bytes = exportFont(mapOf("A" to weighted()), FontExportOptions("T", outlines = FontOutlines.VARIABLE)).bytes
        val gvar = Reader(table(bytes, "gvar"))
        val glyphCount = gvar.u16(12)
        val dataStart = gvar.u32(16).toInt()
        var withData = 0
        for (g in 0 until glyphCount) {
            val from = dataStart + gvar.u32(20 + 4 * g).toInt()
            val to = dataStart + gvar.u32(24 + 4 * g).toInt()
            if (to == from) continue
            withData++
            val header = gvar.u16(from)
            assertTrue(header and 0x8000 != 0, "glyph $g: shared point numbers flag")
            assertEquals(0, gvar.u8(from + gvar.u16(from + 2)), "glyph $g: shared point numbers are \"all points\"")
            for (tuple in 0 until (header and 0x0FFF)) { /* tuples are present */ }
        }
        assertEquals(1, withData)
    }

    /** Reads [count] packed deltas starting at [pos]; returns them and the position after. */
    private fun unpack(r: Reader, pos: Int, count: Int): Pair<IntArray, Int> {
        var p = pos
        val out = IntArray(count)
        var i = 0
        while (i < count) {
            val control = r.u8(p++)
            val run = (control and 0x3F) + 1
            for (k in 0 until run) {
                if (i >= count) break
                out[i++] = when {
                    control and 0x80 != 0 -> 0
                    control and 0x40 != 0 -> { val v = r.i16(p); p += 2; v }
                    else -> r.i8(p++)
                }
            }
        }
        return out to p
    }

    @Test
    fun advanceWidthsTravelWithTheAxisThroughThePhantomPoints() {
        val glyph = weighted()
        val bytes = exportFont(mapOf("A" to glyph), FontExportOptions("T", outlines = FontOutlines.VARIABLE)).bytes
        val gvar = Reader(table(bytes, "gvar"))
        val dataStart = gvar.u32(16).toInt()
        val from = dataStart + gvar.u32(20 + 4 * 1).toInt() // glyph 1 is "A": glyph 0 is .notdef
        val count = gvar.u16(from) and 0x0FFF
        var header = from + 4
        val tuples = ArrayList<Pair<Float, Int>>() // peak, size of data
        val intermediates = ArrayList<Boolean>()
        repeat(count) {
            val size = gvar.u16(header)
            val index = gvar.u16(header + 2)
            val peak = gvar.f2dot14(header + 4)
            header += 6
            val intermediate = index and 0x4000 != 0
            if (intermediate) header += 4
            tuples.add(peak to size); intermediates.add(intermediate)
        }
        // Serialized data: the shared point numbers, then every tuple's x deltas followed by its y deltas.
        var data = from + gvar.u16(from + 2)
        assertEquals(0, gvar.u8(data)); data += 1
        // The glyph's point count, from the glyph itself: its last contour's end point, plus one.
        val head = Reader(table(bytes, "head"))
        val loca = Reader(table(bytes, "loca"))
        val glyf = Reader(table(bytes, "glyf"))
        val glyphStart = if (head.i16(50) == 0) loca.u16(2) * 2 else loca.u32(4).toInt()
        val contours = glyf.i16(glyphStart)
        val n = glyf.u16(glyphStart + 10 + (contours - 1) * 2) + 1 + 4
        val widths = HashMap<Float, Int>()
        for ((peak, size) in tuples) {
            val end = data + size
            val (dx, afterX) = unpack(gvar, data, n)
            val (_, afterY) = unpack(gvar, afterX, n)
            assertEquals(end, afterY, "tuple data is exactly its x and y deltas")
            widths[peak] = dx[n - 3] // phantom points are the last four: left, right (advance), top, bottom
            data = end
        }
        // Regular is 800 wide; the thin end 700, the black end 900, and the stops between follow Morphont's own curve.
        for ((peak, delta) in widths) {
            val t = 0.5f + peak / 2f
            val expected = interpolateGlyph(glyph.corners, mapOf("wght" to t)).width - 800f
            assertEquals(expected, delta.toFloat(), 1f, "advance change at normalized $peak")
        }
        assertEquals(setOf(-1f, -0.5f, 0.5f, 1f), widths.keys)
    }

    private fun area(c: ContourData): Float {
        val p = c.points.filter { it.onCurve }
        var a = 0f
        for (i in p.indices) { val q = p[(i + 1) % p.size]; a += p[i].x * q.y - q.x * p[i].y }
        return a / 2f
    }

    private fun squareContour(x0: Float, y0: Float, x1: Float, y1: Float, counterClockwise: Boolean): ContourData {
        val pts = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        return ContourData((if (counterClockwise) pts else pts.reversed()).map { Pt(it.first, it.second, true) }.toMutableList())
    }

    private fun ringGlyph(innerSameWay: Boolean): Glyph {
        val g = Glyph(metrics = FontMetrics())
        g.corners["regular"] = GlyphCorner(600f, mutableListOf(
            squareContour(50f, 0f, 550f, 700f, counterClockwise = true),
            squareContour(150f, 100f, 450f, 600f, counterClockwise = innerSameWay),
        ))
        return g
    }

    @Test
    fun aHoleThatRunsTheOuterWayStillComesOutAHole() {
        // The editor fills even-odd, so a ring drawn with both contours the same way shows a hole; a font fills
        // non-zero, where that ring would be solid. The export turns the inner contour against the outer.
        for (outlines in listOf(FontOutlines.TRUETYPE, FontOutlines.CFF)) {
            val ring = ringGlyph(innerSameWay = true)
            val contours = fontContoursOf(ring.corners.getValue("regular"))
            val flips = contourFlips(contours, outerClockwise = outlines == FontOutlines.TRUETYPE)
            val fixed = applyFlips(contours, flips)
            val areas = fixed.map { c -> var a = 0L; var px = c.startX; var py = c.startY; for (sg in c.segs) { a += px.toLong() * sg.y - sg.x.toLong() * py; px = sg.x; py = sg.y }; a }
            assertTrue(areas[0] * areas[1] < 0, "the hole runs against its outline: $areas")
            assertEquals(outlines == FontOutlines.TRUETYPE, areas[0] < 0, "outer is clockwise for TrueType, counter-clockwise for CFF")
        }
        val font = parse(exportFont(mapOf("O" to ringGlyph(true)), FontExportOptions("Ring")).bytes)
        val shape = assertNotNull(font.outlineAt('O'.code, emptyMap()))
        assertTrue(area(shape[0]) * area(shape[1]) < 0)
    }

    @Test
    fun anIslandInsideAHoleIsOuterAgain() {
        val outer = FontContour(0, 0, listOf(FontSeg(1, 0, 0, 0, 0, 1000, 0), FontSeg(1, 0, 0, 0, 0, 1000, 1000), FontSeg(1, 0, 0, 0, 0, 0, 1000), FontSeg(1, 0, 0, 0, 0, 0, 0)))
        fun box(a: Int, b: Int) = FontContour(a, a, listOf(FontSeg(1, 0, 0, 0, 0, b, a), FontSeg(1, 0, 0, 0, 0, b, b), FontSeg(1, 0, 0, 0, 0, a, b), FontSeg(1, 0, 0, 0, 0, a, a)))
        val flips = contourFlips(listOf(outer, box(100, 900), box(300, 700)), outerClockwise = false)
        // all three run counter-clockwise: outer stays, the hole flips, the island inside it stays
        assertEquals(listOf(false, true, false), flips.toList())
    }

    @Test
    fun aReversedContourDrawsTheSameOutlineTheOtherWay() {
        val c = fontContourOf(blob(200f), 1f)!!
        val back = reversedContour(reversedContour(c))
        assertEquals(c.segs.map { listOf(it.order, it.c1x, it.c1y, it.c2x, it.c2y, it.x, it.y) }, back.segs.map { listOf(it.order, it.c1x, it.c1y, it.c2x, it.c2y, it.x, it.y) })
        assertTrue(sameShape(c, reversedContour(c)))
    }

    @Test
    fun aGlyphBeyondTheGridIsRefusedWithItsName() {
        val g = Glyph(metrics = FontMetrics())
        g.corners["regular"] = GlyphCorner(500f, mutableListOf(blob(40_000f, 0f, 0f)))
        val e = assertFailsWith<IllegalArgumentException> { exportFont(mapOf("W" to g), FontExportOptions("T")) }
        assertContains(e.message!!, "\"W\"")
    }

    @Test
    fun aGlyphWithNothingDrawnKeepsItsOneAdvance() {
        val space = Glyph().also { it.corners["regular"] = GlyphCorner(310f) }
        for (t in listOf(0f, 1f)) {
            val font = parse(exportFont(mapOf(" " to space, "A" to weighted()), FontExportOptions("T", instance = mapOf("wght" to t))).bytes)
            assertEquals(310f, font.advanceWidth(' '.code), "wght at $t")
        }
    }

    @Test
    fun aGlyphWithNoRegularAndDisagreeingAnchorsStillExportsSomething() {
        val g = Glyph(metrics = FontMetrics())
        g.corners[Axis.WEIGHT.lo] = corner(700f, 150f)
        g.corners[Axis.WEIGHT.hi] = GlyphCorner(900f, mutableListOf(ContourData(mutableListOf(Pt(0f, 0f, true), Pt(100f, 0f, true), Pt(100f, 100f, true)))))
        val result = exportFont(mapOf("A" to g), FontExportOptions("T"))
        assertTrue(assertNotNull(parse(result.bytes).outlineAt('A'.code, emptyMap())).isNotEmpty())
        assertTrue(result.warnings.any { "first one drawn" in it })
    }
}
