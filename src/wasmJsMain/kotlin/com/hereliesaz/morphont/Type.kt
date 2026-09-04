package com.hereliesaz.morphont

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.hereliesaz.morphont.generated.resources.Res
import com.hereliesaz.morphont.generated.resources.azrienoch_vf
import org.jetbrains.compose.resources.Font

/**
 * Morphont's own UI is set in [Azrienoch](https://github.com/HereLiesAz/Azrienoch), the
 * multiplex variable font this tool exists to help shape -- using a stock system sans for a
 * variable-font editor's own chrome never sat right. Licensed SIL Open Font License 1.1;
 * `licenses/Azrienoch-OFL.txt` travels with the compiled font in this repo.
 *
 * [HereLiesAz/Conveyance](https://github.com/HereLiesAz/Conveyance)'s own `conveyTypeFontFamily`
 * (`convey/.../tokens/ConveyType.kt`) bakes a specific `wght`/`wdth` point into its own
 * `Font(variationSettings = ...)` instance, since `TextStyle` has no live variation-settings
 * field. That overload isn't available in the Compose Multiplatform version this project is
 * pinned to (1.7.3; Conveyance's `convey` module is pinned far newer) -- `org.jetbrains.compose.
 * resources.Font` here only takes a resource, weight and style, no variation settings. So this
 * renders Azrienoch at its single default instance (`wght` 400, `wdth` 100) everywhere; a
 * declared [androidx.compose.ui.text.font.FontWeight.Bold] still asks Skia's own synthetic-bold
 * fallback for emphasis rather than a real heavier Azrienoch master. Worth revisiting once this
 * project's Compose Multiplatform pin moves past whatever version added that overload.
 */
@Composable
fun morphontFontFamily(): FontFamily = FontFamily(Font(Res.font.azrienoch_vf))
