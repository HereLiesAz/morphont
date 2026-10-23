package com.hereliesaz.morphont

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The splash, the privacy page and the manifest repeat a few colours from [DarkPalette] and [LightPalette] --
 * they paint before any Kotlin runs. This keeps them from drifting: it fails when a hex there stops matching
 * the palette. (Set MORPHONT_ROOT when the tests don't run from the repository root.)
 */
class PaletteResourcesTest {
    private val root: File = generateSequence(File(System.getenv("MORPHONT_ROOT") ?: ".").absoluteFile) { it.parentFile }
        .first { File(it, "src/wasmJsMain/resources/index.html").exists() }

    private fun hex(c: Color): String = "#%06X".format(0xFFFFFF and c.toArgb())

    private fun text(path: String) = File(root, path).readText()

    private fun assertHas(file: String, colors: List<Color>) {
        val body = text(file).uppercase()
        for (c in colors) assertTrue(hex(c) in body, "$file should contain ${hex(c)} from the palette")
    }

    @Test
    fun theSplashPaintsEachThemesGround() = assertHas("src/wasmJsMain/resources/index.html", listOf(DarkPalette.ground, LightPalette.ground))

    @Test
    fun thePrivacyPageUsesEachThemesInk() = assertHas(
        "src/wasmJsMain/resources/privacy.html",
        listOf(
            DarkPalette.ground, DarkPalette.ink, DarkPalette.inkDim, DarkPalette.tertiary,
            LightPalette.ground, LightPalette.ink, LightPalette.inkDim, LightPalette.tertiary,
        ),
    )

    @Test
    fun theManifestAndAndroidStartOnTheDarkGround() {
        assertHas("src/wasmJsMain/resources/manifest.webmanifest", listOf(DarkPalette.ground))
        assertHas("androidApp/src/main/res/values/colors.xml", listOf(DarkPalette.ground, DarkPalette.panel, DarkPalette.ink))
    }
}
