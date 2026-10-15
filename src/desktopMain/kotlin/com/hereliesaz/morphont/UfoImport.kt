package com.hereliesaz.morphont

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Import a UFO directory or UFOZ archive, preserving editable outline geometry. */
data class UfoImportResult(val glyphs: Map<String, Glyph>, val skipped: List<String>)

private fun Element.elements(tag: String? = null): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { tag == null || it.tagName == tag }

private fun Element.first(tag: String) = elements(tag).firstOrNull()

private fun readXml(bytes: ByteArray): Element {
    val f = DocumentBuilderFactory.newInstance()
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    f.isXIncludeAware = false
    f.isExpandEntityReferences = false
    return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
}

private fun readPlist(bytes: ByteArray): Map<String, String> {
    val root = readXml(bytes)
    require(root.tagName == "plist") { "Expected XML plist" }
    val values = root.first("dict")?.elements() ?: error("Missing plist dictionary")
    require(values.size % 2 == 0) { "Malformed plist dictionary" }
    return values.chunked(2).associate { pair ->
        require(pair[0].tagName == "key") { "Malformed plist key" }
        pair[0].textContent to pair[1].textContent.trim()
    }
}

private fun safePath(path: String): String {
    val parts = path.replace('\\', '/').split('/')
    require(parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe UFO path: $path" }
    return parts.joinToString("/")
}

private interface UfoSource { fun read(path: String): ByteArray? }

private class UfoDirectory(private val folder: File) : UfoSource {
    private val root = folder.canonicalFile.toPath()
    override fun read(path: String): ByteArray? {
        val file = folder.resolve(safePath(path)).canonicalFile
        require(file.toPath().startsWith(root)) { "UFO file escapes package" }
        return if (file.isFile) file.readBytes() else null
    }
}

private class UfoArchive(private val zip: ZipFile) : UfoSource {
    private val root = zip.entries().asSequence().map { it.name }
        .firstOrNull { it.endsWith("/metainfo.plist") && it.count { c -> c == '/' } == 1 }
        ?.removeSuffix("metainfo.plist") ?: error("UFOZ must contain one root package directory")
    override fun read(path: String): ByteArray? {
        val entry = zip.getEntry(root + safePath(path)) ?: return null
        require(!entry.isDirectory && entry.size <= 16L * 1024 * 1024) { "Oversized UFO file: $path" }
        return zip.getInputStream(entry).use {
            val bytes = it.readNBytes(16 * 1024 * 1024 + 1)
            require(bytes.size <= 16 * 1024 * 1024) { "Oversized UFO file: $path" }
            bytes
        }
    }
}

private data class UfoPoint(val x: Float, val y: Float, val type: String, val smooth: Boolean) {
    fun node() = Pt(x, y, true, smooth)
    fun handle() = Pt(x, y, false)
}

/**
 * Each GLIF point with a type completes the preceding segment. Preserve
 * cubic handles exactly; convert quadratic handles to cubic 2/3 controls.
 * UFO open contours cannot be represented by the editor's closed paths.
 */
private fun parseUfoContour(element: Element): ContourData {
    val points = element.elements("point").map { p ->
        UfoPoint(p.getAttribute("x").toFloat(), p.getAttribute("y").toFloat(),
            p.getAttribute("type").ifEmpty { "offcurve" }, p.getAttribute("smooth") == "yes")
    }
    if (points.isEmpty()) return ContourData()
    require(points.none { it.type == "move" }) { "Open contour is not supported" }
    require(points.all { it.type in setOf("line", "curve", "qcurve", "offcurve") }) { "Unknown GLIF point type" }
    if (points.all { it.type == "offcurve" }) {
        return completeNodeHandles(quadraticToCubic(ContourData(points.map { it.handle() }.toMutableList())))
    }
    val first = points.indexOfFirst { it.type != "offcurve" }
    val rotated = points.drop(first) + points.take(first)
    val indices = rotated.indices.filter { rotated[it].type != "offcurve" }
    val result = mutableListOf<Pt>()
    for (i in indices.indices) {
        val startIndex = indices[i]
        val nextIndex = indices[(i + 1) % indices.size]
        val start = rotated[startIndex]
        val end = rotated[nextIndex]
        result.add(start.node())
        val controls = mutableListOf<UfoPoint>()
        var pos = (startIndex + 1) % rotated.size
        while (pos != nextIndex) {
            require(rotated[pos].type == "offcurve") { "Invalid GLIF segment" }
            controls.add(rotated[pos])
            pos = (pos + 1) % rotated.size
        }
        when (end.type) {
            "line" -> require(controls.isEmpty()) { "Line has handles" }
            "curve" -> when (controls.size) {
                0 -> Unit
                1 -> {
                    val c = controls.single()
                    result.add(Pt(start.x + 2f / 3f * (c.x - start.x), start.y + 2f / 3f * (c.y - start.y), false))
                    result.add(Pt(end.x + 2f / 3f * (c.x - end.x), end.y + 2f / 3f * (c.y - end.y), false))
                }
                2 -> { result.add(controls[0].handle()); result.add(controls[1].handle()) }
                else -> error("Cubic segment has ${controls.size} controls")
            }
            "qcurve" -> {
                var begin = start
                for (j in controls.indices) {
                    val control = controls[j]
                    val finish = if (j == controls.lastIndex) end else UfoPoint(
                        (control.x + controls[j + 1].x) / 2f, (control.y + controls[j + 1].y) / 2f, "qcurve", true)
                    result.add(Pt(begin.x + 2f / 3f * (control.x - begin.x), begin.y + 2f / 3f * (control.y - begin.y), false))
                    result.add(Pt(finish.x + 2f / 3f * (control.x - finish.x), finish.y + 2f / 3f * (control.y - finish.y), false))
                    if (j != controls.lastIndex) result.add(finish.node())
                    begin = finish
                }
            }
            else -> error("Invalid segment endpoint")
        }
    }
    val contour = ContourData(result)
    require(isValidCubicContour(contour)) { "Malformed cubic GLIF contour" }
    return completeNodeHandles(contour)
}

private data class UfoComponent(val base: String, val matrix: List<Float>)
private data class UfoGlyph(val width: Float, val contours: List<ContourData>, val components: List<UfoComponent>)

private fun parseUfoGlyph(bytes: ByteArray): UfoGlyph {
    val root = readXml(bytes)
    require(root.tagName == "glyph") { "Expected GLIF glyph" }
    val width = root.first("advance")?.getAttribute("width")?.toFloatOrNull() ?: 0f
    val outline = root.first("outline")
    val contours = outline?.elements("contour")?.map(::parseUfoContour) ?: emptyList()
    val components = outline?.elements("component")?.map { e ->
        UfoComponent(
            e.getAttribute("base").also { require(it.isNotEmpty()) { "Component base is missing" } },
            listOf(e.getAttribute("xScale").toFloatOrNull() ?: 1f,
                e.getAttribute("yxScale").toFloatOrNull() ?: 0f,
                e.getAttribute("xyScale").toFloatOrNull() ?: 0f,
                e.getAttribute("yScale").toFloatOrNull() ?: 1f,
                e.getAttribute("xOffset").toFloatOrNull() ?: 0f,
                e.getAttribute("yOffset").toFloatOrNull() ?: 0f),
        )
    } ?: emptyList()
    return UfoGlyph(width, contours, components)
}

private fun readUfo(source: UfoSource): UfoImportResult {
    require(source.read("metainfo.plist") != null) { "Missing metainfo.plist" }
    val files = readPlist(source.read("glyphs/contents.plist") ?: error("Missing glyphs/contents.plist"))
    val fontInfo = source.read("fontinfo.plist")?.let(::readPlist).orEmpty()
    fun number(key: String, fallback: Float) = fontInfo[key]?.toFloatOrNull() ?: fallback
    val metrics = FontMetrics(
        unitsPerEm = number("unitsPerEm", 1000f),
        ascender = number("ascender", 800f), capHeight = number("capHeight", 700f),
        xHeight = number("xHeight", 500f), descender = number("descender", -200f))
    val parsed = mutableMapOf<String, UfoGlyph>()
    val skipped = mutableListOf<String>()
    for ((name, file) in files) {
        try {
            require(name.isNotEmpty()) { "Empty glyph name" }
            parsed[name] = parseUfoGlyph(source.read("glyphs/$file") ?: error("Missing glyph file"))
        } catch (e: Exception) { skipped.add("$name: ${e.message}") }
    }
    val cache = mutableMapOf<String, List<ContourData>>()
    fun resolve(name: String, ancestors: Set<String> = emptySet()): List<ContourData> {
        cache[name]?.let { return it }
        require(name !in ancestors && ancestors.size < 24) { "Component cycle or excessive depth: $name" }
        val glyph = parsed[name] ?: error("Missing component base: $name")
        val contours = glyph.contours.map { it.deepCopy() }.toMutableList()
        for (component in glyph.components) {
            val matrix = component.matrix
            contours.addAll(mapContours(resolve(component.base, ancestors + name)) { x, y ->
                (matrix[0] * x + matrix[2] * y + matrix[4]) to
                    (matrix[1] * x + matrix[3] * y + matrix[5])
            })
        }
        cache[name] = contours
        return contours
    }
    val glyphs = linkedMapOf<String, Glyph>()
    for ((name, record) in parsed) {
        try {
            val regular = GlyphCorner(record.width, resolve(name).map { it.deepCopy() }.toMutableList())
            glyphs[name] = Glyph(
                corners = ANCHORS.associateWith { regular.deepCopy() }.toMutableMap(),
                metrics = metrics, outlineFormat = OUTLINE_CUBIC)
        } catch (e: Exception) { skipped.add("$name: ${e.message}") }
    }
    require(glyphs.isNotEmpty()) { "UFO contains no importable glyphs. ${skipped.take(3).joinToString()}" }
    return UfoImportResult(glyphs, skipped)
}

/**
 * One UFO master populates every editable anchor independently. This is a
 * lossless outline import, not an import of UFO layers or Designspace axes.
 */
fun importUfoFile(file: File): UfoImportResult {
    if (file.isDirectory) {
        require(file.name.endsWith(".ufo", true)) { "Select a .ufo directory" }
        return readUfo(UfoDirectory(file))
    }
    require(file.name.endsWith(".ufoz", true)) { "Select a .ufoz archive" }
    return ZipFile(file).use { readUfo(UfoArchive(it)) }
}
