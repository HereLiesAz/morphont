package com.hereliesaz.morphont

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipScope
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Slider
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
 * needed. Monochrome -- hierarchy is carried by brightness, so the selected
 * node, the active tool and the anchor being edited are simply the whitest
 * things on screen. Colour appears in two places only: [Mono.error], reserved
 * for genuine errors, and [GhostStyle], the outline colours a user may give a
 * ghost to tell several apart.
 *
 * Built on [HereLiesAz/convey](https://github.com/HereLiesAz/convey): the UI
 * is set in its Azrienoch (`conveyTypeFontFamily`), and every [MonoButton]
 * still registers its weight with [compose.conveyance.ConveySystem]'s
 * hierarchy enforcement via [compose.conveyance.conveyWeight]. Icons are
 * drawn in-house ([MIcons]) rather than pulled from an icon library.
 */
/** Every colour the UI and the canvas draw with, for one theme. */
class Palette(
    val ground: Color, val panel: Color, val panelHeader: Color, val border: Color, val borderBright: Color,
    val ink: Color, val inkDim: Color, val inkFaint: Color,
    val primary: Color, val onPrimary: Color, val secondary: Color, val onSecondary: Color,
    val tertiary: Color, val onTertiary: Color, val error: Color, val onError: Color,
    // The drawing canvas.
    val glow: Color, val outline: Color, val baseline: Color, val metric: Color, val grid: Color,
    val ctrlLine: Color, val nodeRing: Color, val offCurve: Color, val pathCurve: Color,
    val ghostStroke: Color, val ghostActive: Color, val rulerBg: Color, val rulerLine: Color, val measure: Color,
    /** The mark colour for selection boxes, snap lines and the like: white on dark, near-black on light. */
    val mark: Color,
    val hairline: Color, val previewGround: Color,
)

val DarkPalette = Palette(
    ground = Color(0xFF070707), panel = Color(0xFF121212), panelHeader = Color(0xFF1C1C1C),
    border = Color(0xFF262626), borderBright = Color(0xFF3A3A3A),
    ink = Color(0xFFEDEDED), inkDim = Color(0xFF8C8C8C), inkFaint = Color(0xFF4A4A4A),
    primary = Color(0xFFEDEDED), onPrimary = Color(0xFF070707),
    secondary = Color(0xFF7A7A7A), onSecondary = Color(0xFF070707),
    tertiary = Color(0xFFBDBDBD), onTertiary = Color(0xFF070707),
    error = Color(0xFFE4573D), onError = Color(0xFF1A0704),
    glow = Color(0xFF161616), outline = Color(0xFFD9D9D9), baseline = Color(0xFF5A5A5A), metric = Color(0xFF505050),
    grid = Color(0xFF3A3A3A), ctrlLine = Color(0xFF666666), nodeRing = Color(0xFFBDBDBD), offCurve = Color(0xFF7A7A7A),
    pathCurve = Color(0xFF6A6A6A), ghostStroke = Color(0xFF5A5A5A), ghostActive = Color(0xFFBDBDBD),
    rulerBg = Color(0xFF0A0A0A), rulerLine = Color(0xFF1A1A1A), measure = Color(0xFF9A9A9A),
    mark = Color.White, hairline = Color(0xFF181818), previewGround = Color(0xFF0D0D0D),
)

/**
 * "Light": a dark grey, not paper. Lifted well off the near-black of [DarkPalette] but still dark enough for
 * white text and icons, so the same ink, marks and hairlines work on both.
 */
val LightPalette = Palette(
    ground = Color(0xFF2E2E2E), panel = Color(0xFF3A3A3A), panelHeader = Color(0xFF454545),
    border = Color(0xFF4E4E4E), borderBright = Color(0xFF626262),
    ink = Color(0xFFF2F2F2), inkDim = Color(0xFFB4B4B4), inkFaint = Color(0xFF7E7E7E),
    primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF2E2E2E),
    secondary = Color(0xFF9A9A9A), onSecondary = Color(0xFF2E2E2E),
    tertiary = Color(0xFFD2D2D2), onTertiary = Color(0xFF2E2E2E),
    error = Color(0xFFF58C75), onError = Color(0xFF1A0704),
    glow = Color(0xFF3E3E3E), outline = Color(0xFFE8E8E8), baseline = Color(0xFF7A7A7A), metric = Color(0xFF707070),
    grid = Color(0xFF585858), ctrlLine = Color(0xFF8A8A8A), nodeRing = Color(0xFFD0D0D0), offCurve = Color(0xFF9E9E9E),
    pathCurve = Color(0xFF909090), ghostStroke = Color(0xFF909090), ghostActive = Color(0xFFD2D2D2),
    rulerBg = Color(0xFF292929), rulerLine = Color(0xFF3C3C3C), measure = Color(0xFFB4B4B4),
    mark = Color.White, hairline = Color(0xFF3A3A3A), previewGround = Color(0xFF262626),
)

/** Which theme is showing. One value for the whole app, so the canvas and every panel switch together. */
object ThemeState {
    var dark by mutableStateOf(true)
}

/**
 * The type scale: every size the UI uses, by role. Screens name a role, not a number, so a size changes in one
 * place. ([Caps] has its own two sizes, 11 and 10.)
 */
object Type {
    /** Detail lines under a row, tiny readouts. */
    val Micro = 11.sp
    /** Secondary text, hints, dense controls. */
    val Small = 12.sp
    /** Body copy, buttons and menu items. */
    val Body = 14.sp
    /** A row's name in a list, a field's text. */
    val Row = 17.sp
    /** A dialog's title, a glyph in a tab. */
    val Title = 22.sp
    /** Button labels. */
    val Button = 13.sp
    /** A character on a glyph tile. */
    val TileGlyph = 26.sp
    /** The open glyph's name in the phone header. */
    val Name = 38.sp
    /** The wordmark. */
    val Hero = 64.sp
}

object Mono {
    private val p: Palette get() = if (ThemeState.dark) DarkPalette else LightPalette

    // Grounds, darkest first. The canvas is the room; everything else floats on it.
    val ground get() = p.ground
    val panel get() = p.panel
    val panelHeader get() = p.panelHeader
    val border get() = p.border
    val borderBright get() = p.borderBright
    val ink get() = p.ink
    val inkDim get() = p.inkDim
    val inkFaint get() = p.inkFaint

    /**
     * Monochrome by design: "primary" is the brightest ink, not a hue. Selection,
     * the active tool and the anchor being edited are white on near-black; the
     * hierarchy is carried by light, not colour.
     */
    val primary get() = p.primary
    val onPrimary get() = p.onPrimary

    /** Off-curve handles and other secondary marks: a mid grey. */
    val secondary get() = p.secondary
    val onSecondary get() = p.onSecondary

    /** Ghosts and reference material: a quieter grey than the glyph itself. */
    val tertiary get() = p.tertiary
    val onTertiary get() = p.onTertiary

    /** The only colour in the palette, kept for genuine errors. */
    val error get() = p.error
    val onError get() = p.onError

    /** Canvas and layout colours that aren't ink. */
    val canvas: Palette get() = p
    val mark get() = p.mark
    val hairline get() = p.hairline
    val previewGround get() = p.previewGround
}

/** Both themes are dark enough for white-on-dark, so one dark-based scheme serves both, fed by the live palette. */
@Composable
private fun morphontColorScheme() = darkColorScheme(
    primary = Mono.primary, onPrimary = Mono.onPrimary, secondary = Mono.secondary, onSecondary = Mono.onSecondary,
    tertiary = Mono.tertiary, onTertiary = Mono.onTertiary, background = Mono.ground, onBackground = Mono.ink,
    surface = Mono.panel, onSurface = Mono.ink, surfaceVariant = Mono.panelHeader, onSurfaceVariant = Mono.inkDim,
    outline = Mono.border, error = Mono.error, onError = Mono.onError,
)

/**
 * Morphont's text button: a rounded pill in three weights -- borderless (a quiet, in-place action), [outlined]
 * (a secondary action, and every dialog's Cancel), or filled with [Mono.primary] when [selected] (the one
 * primary action on a surface). Dialog footers put Cancel first and the primary last. [Modifier.conveyWeight] still registers
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
        // One label size for every button, so no call site picks its own.
        content = { ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = Type.Button)) { content() } },
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

/**
 * A ring that shows while the element after it in the chain has keyboard focus, as [IconAction] draws. Put it
 * before the `clickable`/`selectable`/`toggleable` that takes the focus.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this.border(if (focused) 1.5.dp else 0.dp, if (focused) Mono.ink else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }
}

/** A floating rounded surface (dock, pills, sheets' cards): one step above the ground, hairline edge. */
fun Modifier.floating(radius: Int = 22): Modifier = this
    .shadow(16.dp, RoundedCornerShape(radius.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(RoundedCornerShape(radius.dp))
    .background(Mono.panel)
    .border(1.dp, Mono.border, RoundedCornerShape(radius.dp))

/**
 * A tool button that names itself, in small text beneath its icon, while it is the active tool and only then.
 * [name] defaults to [label] without any trailing "(key)" hint. Like every [IconAction] its full label (with the
 * key) shows on hover, and on a long press on touch.
 */
@Composable
fun ToolIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = IconSize.LARGE,
    name: String = label.substringBefore(" ("),
    disabledReason: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconAction(icon, label, onClick, selected = selected, enabled = enabled, size = size, disabledReason = disabledReason)
        if (selected) Caps(name, color = Mono.ink, size = 10)
    }
}

/**
 * The three icon-button sizes: [SMALL] for inline affordances beside text (chevrons, a row's clear button) on
 * pointer-first surfaces, [MEDIUM] for the desktop rail and dialog headers, [LARGE] -- the 48dp touch
 * guideline -- for anything a finger has to hit. The glyph inside stays at one of two sizes whatever the
 * hit area, so neighbouring buttons of different sizes still draw the same icon.
 */
object IconSize {
    const val SMALL = 28
    const val MEDIUM = 40
    const val LARGE = 48
}

/**
 * Circular icon button ([IconSize]); [selected] fills it with ink. [label] is its accessible name and its
 * tooltip (hover with a mouse, long-press on touch); when it is disabled and [disabledReason] is given, the
 * tooltip says why. It takes keyboard focus like any button and shows a ring while focused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = IconSize.LARGE,
    disabledReason: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val tip = if (!enabled && disabledReason != null) "$label — $disabledReason" else label
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { MonoTooltip(tip) },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(if (selected) Mono.primary else Color.Transparent)
                .border(if (focused) 1.5.dp else 0.dp, if (focused) Mono.ink else Color.Transparent, CircleShape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                    if (!enabled && disabledReason != null) stateDescription = disabledReason
                },
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
                modifier = Modifier.size(if (size < IconSize.MEDIUM) 16.dp else 20.dp),
            )
        }
    }
}

/** The one tooltip look: a small panel-coloured label with a hairline edge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipScope.MonoTooltip(text: String) {
    PlainTooltip(
        containerColor = Mono.panelHeader,
        contentColor = Mono.ink,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.border(1.dp, Mono.border, RoundedCornerShape(10.dp)),
    ) { Text(text, fontSize = Type.Small) }
}

@Composable
fun monoTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mono.primary,
    unfocusedBorderColor = Mono.border,
    focusedTextColor = Mono.ink,
    unfocusedTextColor = Mono.ink,
    cursorColor = Mono.primary,
    focusedPlaceholderColor = Mono.inkDim,
    unfocusedPlaceholderColor = Mono.inkDim,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorphontTheme(content: @Composable () -> Unit) {
    // Azrienoch itself, loaded straight from HereLiesAz/convey's own tokens/ConveyType.kt --
    // not a copy of its technique, the actual composable, its actual bundled font resource.
    // No more of a stretch for this library's "official typeface" than it is for Morphont, a
    // tool for shaping that exact font, to use it as its own UI typeface too.
    val typography = typographyIn(conveyTypeFontFamily(ConveyTypePreset.Regular))
    // Ripples are ink at low strength, not Material's tinted default.
    CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(color = Mono.ink, rippleAlpha = RippleAlpha(0.16f, 0.16f, 0.10f, 0.16f))) {
        MaterialTheme(colorScheme = morphontColorScheme(), typography = typography, content = content)
    }
}

/**
 * The one slider: a hairline track with an ink fill up to a small round thumb, in place of Material's thick
 * stock track. It is Material's [Slider] underneath -- same gestures, keyboard steps and accessibility, and a
 * 48dp-tall touch target -- with only the thumb and track redrawn. (An earlier hand-rolled version kept its
 * own pointer capture and could leave a drag stuck.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HairSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    /** What the slider sets, for screen readers; without it a reader announces a bare percentage. */
    label: String? = null,
) {
    val ink = if (enabled) Mono.ink else Mono.inkFaint
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = if (label != null) modifier.semantics { contentDescription = label } else modifier,
        enabled = enabled,
        valueRange = valueRange,
        colors = monoSliderColors(),
        thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(ink)) },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val y = size.height / 2f
                drawLine(Mono.secondary, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                drawLine(ink, Offset(0f, y), Offset(fraction * size.width, y), 1.5.dp.toPx())
            }
        },
    )
}

/**
 * Scrollbar shared by desktop, browser and phone layouts. Track taps move
 * directly; dragging the thumb updates the same [ScrollState] used by wheel,
 * trackpad and touch scrolling. Hidden when the content fits.
 */
@Composable
fun Scrollbar(state: ScrollState, vertical: Boolean, modifier: Modifier = Modifier) {
    if (state.maxValue <= 0) return
    val scope = rememberCoroutineScope()
    val max = state.maxValue
    Canvas(modifier.pointerInput(state, vertical, max) {
        // One recognizer handles track taps and thumb drags. Two competing
        // pointerInput modifiers were consuming the same gesture on desktop.
        val pad = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val length = if (vertical) size.height.toFloat() else size.width.toFloat()
            val thumb = (length * length / (length + max)).coerceIn(pad.coerceAtMost(length), length)
            val usable = (length - thumb).coerceAtLeast(1f)
            val pos = if (vertical) down.position.y else down.position.x
            val current = usable * (state.value.toFloat() / max)
            var origin = if (pos in current..(current + thumb)) current
                else (pos - thumb / 2f).coerceIn(0f, usable)
            val tapOrigin = origin
            down.consume()
            var moved = false
            drag(down.id) { change ->
                moved = true
                val delta = if (vertical) change.position.y - change.previousPosition.y
                    else change.position.x - change.previousPosition.x
                origin = (origin + delta).coerceIn(0f, usable)
                scope.launch { state.scrollTo((origin / usable * max).roundToInt()) }
                change.consume()
            }
            if (!moved) {
                scope.launch { state.scrollTo((tapOrigin / usable * max).roundToInt()) }
            }
        }
    }) {
        val length = if (vertical) size.height else size.width
        val cross = if (vertical) size.width else size.height
        val thumb = (length * length / (length + max)).coerceIn(24.dp.toPx().coerceAtMost(length), length)
        val start = (length - thumb) * state.value.toFloat() / max
        drawRoundRect(
            color = Mono.borderBright.copy(alpha = 0.55f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cross / 2),
        )
        drawRoundRect(
            color = Mono.inkDim,
            topLeft = if (vertical) androidx.compose.ui.geometry.Offset(0f, start)
                else androidx.compose.ui.geometry.Offset(start, 0f),
            size = if (vertical) androidx.compose.ui.geometry.Size(cross, thumb)
                else androidx.compose.ui.geometry.Size(thumb, cross),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cross / 2),
        )
    }
}

/** How a ghost's outline can look: dotted or solid, in one of a few colours (index 0 is the quiet grey ghosts always had). */
object GhostStyle {
    val colors: List<Pair<String, Color>> = listOf(
        "Grey" to Color(0xFF8C8C8C),
        "Red" to Color(0xFFE5484D),
        "Amber" to Color(0xFFF5A623),
        "Green" to Color(0xFF46C37B),
        "Blue" to Color(0xFF4C8DFF),
        "Violet" to Color(0xFFA06BFF),
    )

    fun color(index: Int): Color = colors[index.coerceIn(0, colors.lastIndex)].second
}
