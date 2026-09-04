package com.hereliesaz.morphont

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Morphont's visual language, following [HereLiesAz/Conveyance](https://github.com/HereLiesAz/Conveyance)'s
 * manifesto in structure -- a semantic role vocabulary (surface/onSurface/outline/...), shape as
 * a deliberate signal rather than decoration -- while keeping every value strictly monochromatic
 * (grayscale + white). Conveyance's own `ConveyColor` reference palette is explicitly *not*
 * meant to be imported as-is ("match your brand colors to these roles, not to arbitrary hex
 * values" -- `tokens/ConveyColor.kt`); this is that mapping, done for a tool whose whole subject
 * is monochrome type.
 *
 * Shape follows `tokens/ConveyShape.kt`'s own rationale for its `Cut`/`CutSmall` tokens
 * (45-degree chamfered corners) to the letter: "mechanical, precise, systematic... developer
 * tools, system UI, anything that signals this is infrastructure, not content." That is exactly
 * what a node-editing glyph tool is, so [Mono.buttonShape] uses a cut corner rather than a
 * plain right angle or Material's default rounded pill.
 */
object Mono {
    val ground = Color(0xFF060606)
    val panel = Color(0xFF101010)
    val panelHeader = Color(0xFF161616)
    val border = Color(0xFF3A3A3A)
    val borderBright = Color(0xFF6E6E6E)
    val ink = Color(0xFFEDEDED)
    val inkDim = Color(0xFF9A9A9A)
    val inkFaint = Color(0xFF5A5A5A)

    /** Conveyance's `ConveyShape.CutSmall` rationale, sized for Morphont's compact toolbar buttons. */
    val buttonShape: Shape = CutCornerShape(4.dp)
}

val MorphontColorScheme = darkColorScheme(
    primary = Mono.ink,
    onPrimary = Mono.ground,
    background = Mono.ground,
    onBackground = Mono.ink,
    surface = Mono.panel,
    onSurface = Mono.ink,
    surfaceVariant = Mono.panelHeader,
    onSurfaceVariant = Mono.inkDim,
    outline = Mono.border,
    error = Mono.ink,
    onError = Mono.ground,
)

/**
 * The one button used throughout Morphont: [Mono.buttonShape]'s cut corner, a thin border
 * instead of Material's default elevation/shadow, and a [selected] state that inverts to a
 * solid white fill rather than reaching for an accent color.
 */
@Composable
fun MonoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = Mono.buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Mono.ink else Mono.panel,
            contentColor = if (selected) Mono.ground else Mono.ink,
            disabledContainerColor = Mono.panel,
            disabledContentColor = Mono.inkFaint,
        ),
        border = BorderStroke(1.dp, if (selected) Mono.ink else Mono.border),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        content = { content() },
    )
}

@Composable
fun monoTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mono.ink,
    unfocusedBorderColor = Mono.border,
    focusedTextColor = Mono.ink,
    unfocusedTextColor = Mono.ink,
    cursorColor = Mono.ink,
    focusedPlaceholderColor = Mono.inkFaint,
    unfocusedPlaceholderColor = Mono.inkFaint,
    focusedContainerColor = Mono.panel,
    unfocusedContainerColor = Mono.panel,
)

@Composable
fun monoSliderColors() = SliderDefaults.colors(
    thumbColor = Mono.ink,
    activeTrackColor = Mono.ink,
    inactiveTrackColor = Mono.border,
)

/**
 * Material3's `Typography()` in this project's pinned Compose Multiplatform version has no
 * single "default font family" constructor parameter (added in a later release) -- so every
 * named text style is stamped with [family] individually, the manual equivalent of one.
 */
private fun typographyIn(family: FontFamily): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun MorphontTheme(content: @Composable () -> Unit) {
    val typography = typographyIn(morphontFontFamily())
    MaterialTheme(colorScheme = MorphontColorScheme, typography = typography, content = content)
}
