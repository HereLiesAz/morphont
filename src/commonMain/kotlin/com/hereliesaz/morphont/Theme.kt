package com.hereliesaz.morphont

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
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
import compose.conveyance.tokens.ConveyTypePreset
import compose.conveyance.tokens.conveyTypeFontFamily

/**
 * Morphont's visual language: the glyph is the room. A near-black ground with
 * a faint pool of light behind the outline; every control floats on it as a
 * rounded, hairline-edged surface ([floating]) and stays out of the way until
 * needed. Strictly monochrome -- hierarchy is carried by brightness, so the
 * selected node, the active tool and the anchor being edited are simply the
 * whitest things on screen. The one colour left, [Mono.error], is reserved
 * for genuine errors.
 *
 * Built on [HereLiesAz/convey](https://github.com/HereLiesAz/convey): the UI
 * is set in its Azrienoch (`conveyTypeFontFamily`), and every [MonoButton]
 * still registers its weight with [compose.conveyance.ConveySystem]'s
 * hierarchy enforcement via [compose.conveyance.conveyWeight]. Icons are
 * drawn in-house ([MIcons]) rather than pulled from an icon library.
 */
object Mono {
    // Grounds, darkest first. The canvas is the room; everything else floats on it.
    val ground = Color(0xFF070707)
    val panel = Color(0xFF121212)
    val panelHeader = Color(0xFF1C1C1C)
    val border = Color(0xFF262626)
    val borderBright = Color(0xFF3A3A3A)
    val ink = Color(0xFFEDEDED)
    val inkDim = Color(0xFF8C8C8C)
    val inkFaint = Color(0xFF4A4A4A)

    /**
     * Monochrome by design: "primary" is the brightest ink, not a hue. Selection,
     * the active tool and the anchor being edited are white on near-black; the
     * hierarchy is carried by light, not colour.
     */
    val primary = Color(0xFFEDEDED)
    val onPrimary = Color(0xFF070707)

    /** Off-curve handles and other secondary marks: a mid grey. */
    val secondary = Color(0xFF7A7A7A)
    val onSecondary = Color(0xFF070707)

    /** Ghosts and reference material: a quieter grey than the glyph itself. */
    val tertiary = Color(0xFFBDBDBD)
    val onTertiary = Color(0xFF070707)

    /** The only colour in the palette, kept for genuine errors. */
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
 * Morphont's text button: a rounded pill, borderless when idle, filled with
 * [Mono.primary] when [selected]. [Modifier.conveyWeight] still registers
 * selected/unselected with Conveyance's hierarchy enforcement, so the visual
 * weight and the structural weight stay the same claim.
 */
@Composable
fun MonoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    outlined: Boolean = false,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp).conveyWeight(if (selected) ConveyWeight.Primary else ConveyWeight.Secondary),
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Mono.primary else Color.Transparent,
            contentColor = if (selected) Mono.onPrimary else Mono.ink,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Mono.inkFaint,
        ),
        border = if (outlined && !selected) BorderStroke(1.dp, Mono.border) else null,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        content = { content() },
    )
}

/** Small-caps monospace label: section heads, readouts, metric names. */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Mono.inkDim, size: Int = 11) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        letterSpacing = 1.6.sp,
        maxLines = 1,
    )
}

/** A floating rounded surface (dock, pills, sheets' cards): one step above the ground, hairline edge. */
fun Modifier.floating(radius: Int = 22): Modifier = this
    .shadow(16.dp, RoundedCornerShape(radius.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(RoundedCornerShape(radius.dp))
    .background(Mono.panel)
    .border(1.dp, Mono.border, RoundedCornerShape(radius.dp))

/** Circular icon button with a 44dp touch target; [selected] fills it with ink. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 44,
) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (selected) Mono.primary else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> Mono.inkFaint
                selected -> Mono.onPrimary
                else -> Mono.ink
            },
            modifier = Modifier.size((size * 0.45f).dp),
        )
    }
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
    focusedContainerColor = Mono.ground,
    unfocusedContainerColor = Mono.ground,
)

@Composable
fun monoSliderColors() = SliderDefaults.colors(
    thumbColor = Mono.ink,
    activeTrackColor = Mono.ink,
    inactiveTrackColor = Mono.borderBright,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
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

/**
 * A hairline slider: 1dp track, ink fill up to a small white thumb. Replaces
 * Material's thick track, which reads as a stock form control. The whole
 * 32dp-tall row is the touch target; tap or drag anywhere on it.
 */
@Composable
fun HairSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val latest = androidx.compose.runtime.rememberUpdatedState(onValueChange)
    androidx.compose.foundation.Canvas(
        modifier
            .heightIn(min = 32.dp)
            .pointerInput(valueRange, enabled) {
                if (!enabled) return@pointerInput
                val pad = 8.dp.toPx()
                fun emit(x: Float) {
                    val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    latest.value(valueRange.start + f * span)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    emit(down.position.x)
                    down.consume()
                    drag(down.id) { change -> change.consume(); emit(change.position.x) }
                }
            },
    ) {
        val pad = 8.dp.toPx()
        val y = size.height / 2f
        val x = pad + fraction * (size.width - 2 * pad)
        drawLine(Mono.borderBright, androidx.compose.ui.geometry.Offset(pad, y), androidx.compose.ui.geometry.Offset(size.width - pad, y), 1.dp.toPx())
        drawLine(if (enabled) Mono.ink else Mono.inkFaint, androidx.compose.ui.geometry.Offset(pad, y), androidx.compose.ui.geometry.Offset(x, y), 1.5.dp.toPx())
        drawCircle(if (enabled) Mono.ink else Mono.inkFaint, radius = 6.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
    }
}
