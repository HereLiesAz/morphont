package com.hereliesaz.morphont

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import compose.conveyance.ConveyWeight
import compose.conveyance.conveyWeight
import compose.conveyance.tokens.ConveyShape
import compose.conveyance.tokens.ConveyTypePreset
import compose.conveyance.tokens.conveyTypeFontFamily

/**
 * Morphont's visual language, built directly on [HereLiesAz/convey](https://github.com/HereLiesAz/convey)
 * -- a real dependency (see `build.gradle.kts`), not just a copied philosophy. A semantic role
 * vocabulary (surface/onSurface/outline/primary/secondary/tertiary...) filled with Morphont's
 * own values rather than `ConveyColor`'s own reference palette -- its own doc comment says as
 * much ("match your brand colors to these roles, not to arbitrary hex values"). Most of the
 * interface stays a dark, near-neutral ground; color is spent deliberately, on the few things
 * that actually carry the app's hierarchy, per `ConveyColor`'s own rationale for the three-tier
 * system ("Dynamic Color: use contrasting primary, secondary, and tertiary tones to prioritize
 * actions implicitly") -- not spread across every surface as decoration.
 *
 * - [Mono.primary] (crimson) -- the one thing on a panel demanding action: the active corner,
 *   a selected point.
 * - [Mono.secondary] (verdigris) -- available but not insistent: off-curve handles, the axis
 *   toggle's unselected state.
 * - [Mono.tertiary] (brass) -- rare, for the one genuinely emotional moment in this tool: the
 *   Preview panel, where five hand-drawn anchors resolve into a shape nobody drew directly.
 *
 * Shape and hierarchy come straight from the library's own tokens/enforcement, not a
 * reimplementation: [MonoButton] uses [ConveyShape.CutSmall] (the real chamfered-corner shape
 * token) and tags itself with [ConveyWeight] via [compose.conveyance.conveyWeight], which
 * [compose.conveyance.ConveySystem] (wrapping the whole app in `App.kt`) actually enforces --
 * too many [ConveyWeight.Primary] elements on screen at once throws in debug builds, the same
 * as it would in any other Conveyance-built surface.
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

    /**
     * The active corner, a selected point, the single most important control on a panel.
     * Indigo, not red -- a red primary reads as "something's wrong" the instant [error] also
     * exists in the same palette, no matter how far apart the two hex values actually are.
     */
    val primary = Color(0xFF5A4FB8)
    val onPrimary = Color(0xFFF3F1FA)

    /** Available but not insistent -- off-curve handles, an unselected axis. */
    val secondary = Color(0xFF4E8C7C)
    val onSecondary = Color(0xFF0A0F0E)

    /** Rare, for the Preview panel's own hero moment. */
    val tertiary = Color(0xFFC99A3D)
    val onTertiary = Color(0xFF0F0B02)

    /** The only red in the palette -- a compatibility error is a warning, not "the important action." */
    val error = Color(0xFFE4573D)
    val onError = Color(0xFF1A0704)
}

val MorphontColorScheme = darkColorScheme(
    primary = Mono.primary,
    onPrimary = Mono.onPrimary,
    secondary = Mono.secondary,
    onSecondary = Mono.onSecondary,
    tertiary = Mono.tertiary,
    onTertiary = Mono.onTertiary,
    background = Mono.ground,
    onBackground = Mono.ink,
    surface = Mono.panel,
    onSurface = Mono.ink,
    surfaceVariant = Mono.panelHeader,
    onSurfaceVariant = Mono.inkDim,
    outline = Mono.border,
    error = Mono.error,
    onError = Mono.onError,
)

/**
 * The one button used throughout Morphont: [ConveyShape.CutSmall] -- the real token from
 * `HereLiesAz/convey`, not a locally redefined lookalike -- for the cut corner, a thin border
 * instead of Material's default elevation/shadow, and a [selected] state that fills with
 * [Mono.primary] -- Conveyance's own "contrasting tone prioritizes implicitly" rule, spent on
 * the one state (selected/active) that's actually the important one. [Modifier.conveyWeight]
 * registers that same selected/unselected distinction with Conveyance's own hierarchy
 * enforcement ([ConveyWeight.Primary]/[ConveyWeight.Secondary]), so the visual weight and the
 * structural weight are the same claim, not two independent ones that could drift apart.
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
        modifier = modifier.conveyWeight(if (selected) ConveyWeight.Primary else ConveyWeight.Secondary),
        enabled = enabled,
        shape = ConveyShape.CutSmall,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Mono.primary else Mono.panel,
            contentColor = if (selected) Mono.onPrimary else Mono.ink,
            disabledContainerColor = Mono.panel,
            disabledContentColor = Mono.inkFaint,
        ),
        border = BorderStroke(1.dp, if (selected) Mono.primary else Mono.border),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        content = { content() },
    )
}

@Composable
fun monoTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mono.primary,
    unfocusedBorderColor = Mono.border,
    focusedTextColor = Mono.ink,
    unfocusedTextColor = Mono.ink,
    cursorColor = Mono.primary,
    focusedPlaceholderColor = Mono.inkFaint,
    unfocusedPlaceholderColor = Mono.inkFaint,
    focusedContainerColor = Mono.panel,
    unfocusedContainerColor = Mono.panel,
)

@Composable
fun monoSliderColors() = SliderDefaults.colors(
    thumbColor = Mono.primary,
    activeTrackColor = Mono.primary,
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
    // Azrienoch itself, loaded straight from HereLiesAz/convey's own tokens/ConveyType.kt --
    // not a copy of its technique, the actual composable, its actual bundled font resource.
    // No more of a stretch for this library's "official typeface" than it is for Morphont, a
    // tool for shaping that exact font, to use it as its own UI typeface too.
    val typography = typographyIn(conveyTypeFontFamily(ConveyTypePreset.Regular))
    MaterialTheme(colorScheme = MorphontColorScheme, typography = typography, content = content)
}
