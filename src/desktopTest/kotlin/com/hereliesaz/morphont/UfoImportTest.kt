package com.hereliesaz.morphont

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UfoImportTest {
    @Test
    fun glyphFileNamesAreSafeAndRoundTripOnWindows() {
        val names = listOf("*", "?", ":", "CON", "PRN", "AUX", "NUL", "com1", "lpt9", "A", "A.alt", "é")
        for (name in names) {
            val file = desktopGlyphFileName(name)
            assertFalse(file.contains('*'), file)
            assertFalse(file.contains('?'), file)
            assertFalse(file.contains(':'), file)
            assertEquals(name, desktopGlyphName(file))
        }
        assertEquals("%2A.json", desktopGlyphFileName("*"))
        assertEquals("%252%41.json", desktopGlyphFileName("%2A")) // literal "%2A"; its capital is escaped too
        assertEquals("%41.json", desktopGlyphFileName("A")) // capitals are escaped so "A" and "a" never share a file
        assertEquals("a.json", desktopGlyphFileName("a"))
    }

    private val plistHeader = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">"""
    private val meta = """$plistHeader<dict><key>formatVersion</key><integer>3</integer></dict></plist>"""
    private val contents = """$plistHeader<dict><key>A</key><string>A.glif</string><key>A.shift</key><string>shift.glif</string><key>curveQ</key><string>q.glif</string><key>open</key><string>open.glif</string></dict></plist>"""
    private val info = """$plistHeader<dict>
<key>unitsPerEm</key><integer>2048</integer><key>ascender</key><integer>1800</integer>
<key>descender</key><integer>-300</integer><key>capHeight</key><integer>1450</integer>
<key>xHeight</key><integer>950</integer></dict></plist>"""
    private val a = """<?xml version="1.0"?><glyph name="A" format="2"><advance width="620"/>
<unicode hex="0041"/><outline><contour>
<point x="0" y="0" type="line"/>
<point x="30" y="0"/><point x="70" y="0"/>
<point x="100" y="0" type="curve" smooth="yes"/>
<point x="100" y="100" type="line"/>
<point x="0" y="100" type="line"/>
</contour></outline></glyph>"""
    private val shift = """<?xml version="1.0"?><glyph name="A.shift" format="2">
<advance width="700"/><outline><component base="A" xOffset="30" yOffset="-10"/></outline></glyph>"""
    private val q = """<?xml version="1.0"?><glyph name="curveQ" format="2">
<advance width="480"/><outline><contour>
<point x="0" y="0" type="line"/>
<point x="50" y="100"/><point x="100" y="0" type="qcurve"/>
<point x="100" y="-100" type="line"/>
</contour></outline></glyph>"""
    private val open = """<?xml version="1.0"?><glyph name="open" format="2">
<outline><contour><point x="0" y="0" type="move"/><point x="100" y="0" type="line"/></contour></outline></glyph>"""

    private fun files() = linkedMapOf(
        "metainfo.plist" to meta,
        "fontinfo.plist" to info,
        "glyphs/contents.plist" to contents,
        "glyphs/A.glif" to a,
        "glyphs/shift.glif" to shift,
        "glyphs/q.glif" to q,
        "glyphs/open.glif" to open,
    )

    private fun folder(root: Path): Path {
        val ufo = Files.createDirectory(root.resolve("sample.ufo"))
        for ((path, content) in files()) {
            val target = ufo.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return ufo
    }

    @Test
    fun directoryImportKeepsContoursMetricsNamesAndComponents() {
        val temp = Files.createTempDirectory("morphont-ufo-")
        try {
            val result = importUfoFile(folder(temp).toFile())
            assertEquals(setOf("A", "A.shift", "curveQ"), result.glyphs.keys)
            assertEquals(1, result.skipped.size)
            assertTrue(result.skipped[0].contains("Open contour"))
            val glyph = result.glyphs.getValue("A")
            assertEquals(29, glyph.corners.size)
            assertEquals(620f, glyph.corners.getValue("regular").width)
            assertEquals(2048f, glyph.metrics!!.unitsPerEm)
            assertEquals(-300f, glyph.metrics!!.descender)
            val contour = glyph.corners.getValue("regular").contours.single()
            val p = contour.points
            assertEquals(12, p.size)
            assertEquals(30f, p[1].x)
            assertEquals(70f, p[2].x)
            assertTrue(isValidCubicContour(contour))
            val shifted = result.glyphs.getValue("A.shift")
            assertEquals(700f, shifted.corners.getValue("regular").width)
            val start = shifted.corners.getValue("regular").contours.single().points.first()
            assertEquals(30f, start.x)
            assertEquals(-10f, start.y)
            val qPoints = result.glyphs.getValue("curveQ").corners.getValue("regular").contours.single().points
            assertTrue(abs(qPoints[1].x - (100f / 3f)) < 0.001f)
            assertTrue(abs(qPoints[1].y - (200f / 3f)) < 0.001f)
            // Every anchor is independently editable; adjusting one cannot mutate another.
            glyph.corners.getValue("regular").contours.first().points[0].x = 999f
            assertEquals(0f, glyph.corners.getValue(Axis.WEIGHT.lo).contours.first().points[0].x)
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test
    fun zippedUfoImportsWithoutExtractingToDisk() {
        val dir = Files.createTempDirectory("morphont-ufoz-")
        try {
            val archive = dir.resolve("sample.ufoz")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                for ((path, value) in files()) {
                    zip.putNextEntry(ZipEntry("sample.ufo/$path"))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val result = importUfoFile(archive.toFile())
            assertEquals(setOf("A", "A.shift", "curveQ"), result.glyphs.keys)
            assertEquals(2048f, result.glyphs.getValue("A").metrics!!.unitsPerEm)
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun ufo(root: Path, glyphs: Map<String, String>): Path {
        val ufo = Files.createDirectory(root.resolve("hostile.ufo"))
        val entries = glyphs.keys.joinToString("") { "<key>$it</key><string>$it.glif</string>" }
        val all = mapOf("metainfo.plist" to meta, "fontinfo.plist" to info,
            "glyphs/contents.plist" to "$plistHeader<dict>$entries</dict></plist>") +
            glyphs.map { (name, body) -> "glyphs/$name.glif" to body }
        for ((path, content) in all) {
            val target = ufo.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return ufo
    }

    @Test
    fun nonFiniteCoordinatesSkipTheGlyphInsteadOfBreakingTheSave() {
        val temp = Files.createTempDirectory("morphont-ufo-")
        try {
            val bad = """<?xml version="1.0"?><glyph name="bad" format="2"><advance width="500"/><outline><contour>
<point x="NaN" y="0" type="line"/><point x="100" y="0" type="line"/><point x="100" y="Infinity" type="line"/>
</contour></outline></glyph>"""
            val result = importUfoFile(ufo(temp, mapOf("A" to a, "bad" to bad)).toFile())
            assertEquals(setOf("A"), result.glyphs.keys)
            assertTrue(result.skipped.single().startsWith("bad:"))
            // What was imported encodes: nothing non-finite reached the project.
            result.glyphs.values.forEach { ProjectCodec.encodeGlyph(it) }
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test
    fun chainedComponentsCannotMultiplyIntoMillionsOfContours() {
        val temp = Files.createTempDirectory("morphont-ufo-")
        try {
            // g1 is two copies of A, g2 two of g1, ... g20 would hold 2^20 contours.
            val glyphs = linkedMapOf("A" to a)
            var previous = "A"
            for (i in 1..20) {
                val name = "g$i"
                glyphs[name] = """<?xml version="1.0"?><glyph name="$name" format="2"><advance width="500"/><outline>
<component base="$previous"/><component base="$previous" xOffset="10"/></outline></glyph>"""
                previous = name
            }
            val result = importUfoFile(ufo(temp, glyphs).toFile())
            assertTrue("A" in result.glyphs)
            assertTrue("g20" !in result.glyphs)
            assertTrue(result.skipped.any { it.startsWith("g20:") && "Too many contours" in it })
        } finally { temp.toFile().deleteRecursively() }
    }
}
