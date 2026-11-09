/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.reverie.paint.model.BrushPreset
import com.reverie.paint.model.CanvasPreset

/**
 * All UI colors as a data class so a theme system (light / dark / accent /
 * user-defined) can swap in different instances later. Every composable
 * must read colors from [Theme.current] - never hardcode a color.
 *
 * Components reference tokens by semantic name (bg / panel / accent /
 * onAccent / scrim ...) so a new theme only needs a different instance.
 */
data class AppColors(
    val bg: Color, // app / page background
    val panel: Color, // panels, rails, top bar
    val panelHi: Color, // raised surfaces, sliders track
    val accent: Color, // selection, primary actions
    val accentHi: Color, // brighter accent (borders, pressed)
    val onAccent: Color, // text/icon on accent (usually white)
    val text: Color, // primary text
    val subText: Color, // secondary text, labels
    val canvasBg: Color, // workspace behind the document
    val border: Color, // hairline separators
    val icon: Color, // idle icons
    val scrim: Color, // full-screen backdrop over the canvas
    val gridLine: Color, // canvas grid
    val canvasShadow: Color, // drop shadow under the document
    val error: Color = Color(0xFFD46A6A), // destructive / error actions
)

/** Morandi Dark palette - refined neutral studio workspace, elevated dark graphite panels, specular hairline borders, elegant Morandi misty blue accent. */
val MorandiDarkColors =
    AppColors(
        bg = Color(0xFF141416), // Procreate deep graphite background
        panel = Color(0xFF1E1E22), // Sleek dark graphite panel (iPadOS dark chrome)
        panelHi = Color(0xFF2A2A2E), // Raised surfaces, cards, slider tracks
        accent = Color(0xFF879ED1), // Morandi signature periwinkle blue
        accentHi = Color(0xFFA5B8E2),
        onAccent = Color(0xFF262D38),
        text = Color(0xFFF5F5F7), // Crisp readable off-white text
        subText = Color(0xFF8E8E93), // Apple secondary label gray
        canvasBg = Color(0xFF28282C), // Neutral studio workspace backdrop behind document
        border = Color.Transparent, // Borderless studio aesthetic (relies on elevation & tonal steps)
        icon = Color(0xFF98989D), // Apple neutral silver icon
        scrim = Color(0x80000000),
        gridLine = Color(0x1FFFFFFF),
        canvasShadow = Color(0x80000000),
        error = Color(0xFFD46A6A),
    )

/** Default iOS Light palette - soft Morandi warm studio workspace with balanced contrast. */
val MorandiLightColors =
    AppColors(
        bg = Color(0xFFF2F2F7), // iOS Grouped Background: refined light gray
        panel = Color(0xFFF7F7F9), // Soft warm off-white panels (eliminates harsh pure white glare)
        panelHi = Color(0xFFEAEAEE), // Refined raised surface gray
        accent = Color(0xFF879ED1), // Morandi signature periwinkle blue
        accentHi = Color(0xFF6F86B8),
        onAccent = Color(0xFF262D38),
        text = Color(0xFF242426), // Deep warm graphite text (replaces harsh #000000)
        subText = Color(0xFF8A8A8E), // Morandi balanced secondary label gray
        canvasBg = Color(0xFFE5E5EA), // Clean neutral workspace backdrop
        border = Color.Transparent, // Borderless studio aesthetic
        icon = Color(0xFF636366), // Balanced medium graphite icon (eliminates excessive contrast)
        scrim = Color(0x3D000000),
        gridLine = Color(0x1F000000),
        canvasShadow = Color(0x1F000000),
        error = Color(0xFFD04444),
    )

/** Alias for backward compatibility */
val MorandiColors = MorandiDarkColors
val IosDarkColors = MorandiDarkColors
val IosLightColors = MorandiLightColors

fun blendColors(base: Color, tint: Color, tintWeight: Float): Color {
    val r = base.red * (1f - tintWeight) + tint.red * tintWeight
    val g = base.green * (1f - tintWeight) + tint.green * tintWeight
    val b = base.blue * (1f - tintWeight) + tint.blue * tintWeight
    return Color(red = r, green = g, blue = b, alpha = base.alpha)
}

object ThemeStyle {
    const val MORANDI_TINTED = "MORANDI_TINTED"
    const val NEUTRAL_STUDIO = "NEUTRAL_STUDIO"
    const val WARM_EARTHY = "WARM_EARTHY"
    const val VIBRANT_GLOW = "VIBRANT_GLOW"
}

fun colorToHsl(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, maxOf(g, b))
    val min = minOf(r, minOf(g, b))
    val d = max - min
    val l = (max + min) / 2f
    var h = 0f
    var s = 0f

    if (d > 0.0001f) {
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
    }
    return floatArrayOf(h, s, l)
}

fun hslToColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
    val hNorm = ((h % 360f) + 360f) % 360f / 360f
    val sNorm = s.coerceIn(0f, 1f)
    val lNorm = l.coerceIn(0f, 1f)

    if (sNorm <= 0.0001f) {
        return Color(red = lNorm, green = lNorm, blue = lNorm, alpha = alpha)
    }

    val q = if (lNorm < 0.5f) lNorm * (1f + sNorm) else lNorm + sNorm - lNorm * sNorm
    val p = 2f * lNorm - q

    fun hueToRgb(t: Float): Float {
        var tc = t
        if (tc < 0f) tc += 1f
        if (tc > 1f) tc -= 1f
        return when {
            tc < 1f / 6f -> p + (q - p) * 6f * tc
            tc < 1f / 2f -> q
            tc < 2f / 3f -> p + (q - p) * (2f / 3f - tc) * 6f
            else -> p
        }
    }

    val r = hueToRgb(hNorm + 1f / 3f)
    val g = hueToRgb(hNorm)
    val b = hueToRgb(hNorm - 1f / 3f)
    return Color(red = r, green = g, blue = b, alpha = alpha)
}

/**
 * Computes an optimal high-contrast foreground color (for text / icon) based on background luminance.
 *
 * - When [bg] is dark (luminance <= 0.45f): returns [lightText] (defaults to pure white [Color.White]).
 * - When [bg] is light (luminance > 0.45f): returns [darkText] if provided, or an algorithmically
 *   derived deeply-tinted dark tone (~12% lightness, subtle saturation of [bg]'s hue) to preserve
 *   the Morandi aesthetic without harsh pitch-black glare.
 */
fun optimalTextColor(
    bg: Color,
    lightText: Color = Color.White,
    darkText: Color? = null,
): Color {
    val lum = bg.luminance()
    val hsl = colorToHsl(bg)
    val isLight = lum > 0.30f || hsl[2] > 0.55f
    if (!isLight) {
        return lightText
    }
    if (darkText != null) {
        return darkText
    }
    val hue = hsl[0]
    val sat = hsl[1]
    val tintSat = (sat * 0.35f).coerceIn(0.08f, 0.22f)
    return hslToColor(hue, tintSat, 0.18f)
}

/**
 * Calibrates the accent color for light mode so that:
 * 1. Default Morandi misty blue (#879ED1, lightness 67%) translates to rich Morandi French Blue (#5271AD)
 * 2. Overly bright custom pastel colors are calibrated to ~50% lightness to maintain WCAG AA contrast against white surfaces
 */
fun adjustAccentForLightMode(accent: Color): Color {
    val hsl = colorToHsl(accent)
    // Default Morandi blue hue ~221°: map to calibrated light-mode French blue
    if (hsl[0] in 210f..230f && hsl[2] > 0.60f) {
        return Color(0xFF5271AD)
    }
    // Deepen high-lightness accents so white text and borders have strong contrast
    return if (hsl[2] > 0.52f) {
        hslToColor(hsl[0], (hsl[1] * 1.05f).coerceAtMost(1f), 0.50f)
    } else {
        accent
    }
}

/**
 * Computes the semantic onAccent color for the given accent color.
 */
fun computeOnAccent(accent: Color): Color = optimalTextColor(accent)

/**
 * Convenience extension to compute the optimal foreground text/icon color for this background.
 */
@JvmName("colorOptimalTextColor")
fun Color.optimalTextColor(lightText: Color = Color.White, darkText: Color? = null): Color =
    com.reverie.paint.ui.theme.optimalTextColor(this, lightText, darkText)

/** Build theme colors with algorithmic style generation (Morandi Tinted, Neutral Studio, Warm Earthy, Vibrant Glow) */
fun buildThemeColors(
    isDark: Boolean,
    accent: Color,
    contrast: String = "STANDARD",
    style: String = ThemeStyle.NEUTRAL_STUDIO,
): AppColors {
    val hsl = colorToHsl(accent)
    val hue = hsl[0]
    val accentSat = hsl[1]

    val baseColors: AppColors = if (isDark) {
        when (style) {
            ThemeStyle.NEUTRAL_STUDIO -> {
                when (contrast) {
                    "SOFT" -> MorandiDarkColors.copy(
                        bg = Color(0xFF161619),
                        panel = Color(0xFF222226),
                        panelHi = Color(0xFF2D2D32),
                        canvasBg = Color(0xFF242428),
                        text = Color(0xFFE2E2E6),
                        subText = Color(0xFF828286),
                        icon = Color(0xFF88888D),
                    )
                    "HIGH" -> MorandiDarkColors.copy(
                        bg = Color(0xFF101012),
                        panel = Color(0xFF161618),
                        panelHi = Color(0xFF242428),
                        canvasBg = Color(0xFF1F1F23),
                        text = Color(0xFFFFFFFF),
                        subText = Color(0xFF9E9EA4),
                        icon = Color(0xFFAFAFB5),
                    )
                    else -> MorandiDarkColors
                }
            }
            ThemeStyle.WARM_EARTHY -> {
                // 大地暖调：底色向琥珀大地暖色相偏移 (~38°)，温润低饱和
                val earthHue = (hue * 0.25f + 38f * 0.75f) % 360f
                val earthSat = 0.11f
                when (contrast) {
                    "SOFT" -> AppColors(
                        bg = hslToColor(earthHue, earthSat, 0.10f),
                        panel = hslToColor(earthHue, earthSat, 0.145f),
                        panelHi = hslToColor(earthHue, earthSat, 0.19f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.20f),
                        onAccent = Color.White,
                        text = Color(0xFFEDE8E0),
                        subText = Color(0xFF968F84),
                        canvasBg = hslToColor(earthHue, earthSat * 0.35f, 0.16f),
                        border = hslToColor(earthHue, earthSat, 0.28f).copy(alpha = 0.25f),
                        icon = Color(0xFFA39C91),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                    "HIGH" -> AppColors(
                        bg = hslToColor(earthHue, earthSat, 0.065f),
                        panel = hslToColor(earthHue, earthSat, 0.105f),
                        panelHi = hslToColor(earthHue, earthSat, 0.165f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.25f),
                        onAccent = Color.White,
                        text = Color(0xFFFAF6EE),
                        subText = Color(0xFFAA9F92),
                        canvasBg = hslToColor(earthHue, earthSat * 0.35f, 0.12f),
                        border = hslToColor(earthHue, earthSat, 0.35f).copy(alpha = 0.35f),
                        icon = Color(0xFFB5ACA0),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                    else -> AppColors(
                        bg = hslToColor(earthHue, earthSat, 0.08f),
                        panel = hslToColor(earthHue, earthSat, 0.125f),
                        panelHi = hslToColor(earthHue, earthSat, 0.18f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.20f),
                        onAccent = Color.White,
                        text = Color(0xFFF5F1E8),
                        subText = Color(0xFF9E978C),
                        canvasBg = hslToColor(earthHue, earthSat * 0.35f, 0.14f),
                        border = hslToColor(earthHue, earthSat, 0.30f).copy(alpha = 0.30f),
                        icon = Color(0xFFA8A196),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                }
            }
            ThemeStyle.VIBRANT_GLOW -> {
                // 明澈通透：极深纯粹黑阶，通透微冷高对比与光影质感
                val coolHue = if (accentSat > 0.05f) hue else 220f
                val glowSat = 0.08f
                when (contrast) {
                    "SOFT" -> AppColors(
                        bg = hslToColor(coolHue, glowSat, 0.08f),
                        panel = hslToColor(coolHue, glowSat, 0.13f),
                        panelHi = hslToColor(coolHue, glowSat, 0.18f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.22f),
                        onAccent = Color.White,
                        text = Color(0xFFF2F4F8),
                        subText = Color(0xFF8C93A4),
                        canvasBg = hslToColor(coolHue, glowSat * 0.3f, 0.15f),
                        border = Color(0x24FFFFFF),
                        icon = Color(0xFF98A0B2),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                    "HIGH" -> AppColors(
                        bg = Color(0xFF0A0B0E),
                        panel = Color(0xFF13151A),
                        panelHi = Color(0xFF1F222A),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.30f),
                        onAccent = Color.White,
                        text = Color(0xFFFFFFFF),
                        subText = Color(0xFFA6AFBF),
                        canvasBg = Color(0xFF14161C),
                        border = Color(0x38FFFFFF),
                        icon = Color(0xFFB8C0D2),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                    else -> AppColors(
                        bg = Color(0xFF0F1014),
                        panel = Color(0xFF181A20),
                        panelHi = Color(0xFF242730),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.25f),
                        onAccent = Color.White,
                        text = Color(0xFFF6F8FC),
                        subText = Color(0xFF939BAA),
                        canvasBg = Color(0xFF1A1C22),
                        border = Color(0x2BFFFFFF),
                        icon = Color(0xFFA0A8B8),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                }
            }
            else -> {
                // MORANDI_TINTED (默认 - 莫兰迪微浸润风格)
                // 按主色调色相推导低饱和表面 (8%~13% 饱和度上限)
                val tintSat = (accentSat * 0.40f).coerceIn(0.08f, 0.13f)
                when (contrast) {
                    "SOFT" -> AppColors(
                        bg = hslToColor(hue, tintSat, 0.10f),
                        panel = hslToColor(hue, tintSat, 0.145f),
                        panelHi = hslToColor(hue, tintSat, 0.19f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.18f),
                        onAccent = Color.White,
                        text = hslToColor(hue, 0.04f, 0.90f),
                        subText = hslToColor(hue, 0.08f, 0.55f),
                        canvasBg = hslToColor(hue, tintSat * 0.35f, 0.16f),
                        border = hslToColor(hue, tintSat, 0.28f).copy(alpha = 0.25f),
                        icon = hslToColor(hue, 0.06f, 0.62f),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                    "HIGH" -> AppColors(
                        bg = hslToColor(hue, tintSat, 0.065f),
                        panel = hslToColor(hue, tintSat, 0.105f),
                        panelHi = hslToColor(hue, tintSat, 0.16f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.25f),
                        onAccent = Color.White,
                        text = Color(0xFFFFFFFF),
                        subText = hslToColor(hue, 0.08f, 0.65f),
                        canvasBg = hslToColor(hue, tintSat * 0.35f, 0.12f),
                        border = hslToColor(hue, tintSat, 0.35f).copy(alpha = 0.35f),
                        icon = hslToColor(hue, 0.08f, 0.72f),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                    else -> AppColors(
                        bg = hslToColor(hue, tintSat, 0.08f),
                        panel = hslToColor(hue, tintSat, 0.125f),
                        panelHi = hslToColor(hue, tintSat, 0.175f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.White, 0.20f),
                        onAccent = Color.White,
                        text = hslToColor(hue, 0.04f, 0.95f),
                        subText = hslToColor(hue, 0.08f, 0.60f),
                        canvasBg = hslToColor(hue, tintSat * 0.35f, 0.14f),
                        border = hslToColor(hue, tintSat, 0.30f).copy(alpha = 0.30f),
                        icon = hslToColor(hue, 0.06f, 0.66f),
                        scrim = Color(0x80000000),
                        gridLine = Color(0x1FFFFFFF),
                        canvasShadow = Color(0x80000000),
                    )
                }
            }
        }
    } else {
        // Light Mode
        when (style) {
            ThemeStyle.NEUTRAL_STUDIO -> {
                when (contrast) {
                    "SOFT" -> MorandiLightColors.copy(
                        panel = Color(0xFFF4F4F6),
                        panelHi = Color(0xFFE5E5E9),
                        text = Color(0xFF333336),
                        subText = Color(0xFF949498),
                        icon = Color(0xFF757579),
                    )
                    "HIGH" -> MorandiLightColors.copy(
                        panel = Color(0xFFFFFFFF),
                        panelHi = Color(0xFFE2E2E7),
                        text = Color(0xFF111113),
                        subText = Color(0xFF707075),
                        icon = Color(0xFF48484C),
                    )
                    else -> MorandiLightColors
                }
            }
            ThemeStyle.WARM_EARTHY -> {
                val earthHue = (hue * 0.25f + 38f * 0.75f) % 360f
                val earthSat = 0.10f
                when (contrast) {
                    "SOFT" -> AppColors(
                        bg = hslToColor(earthHue, earthSat, 0.94f),
                        panel = hslToColor(earthHue, earthSat * 0.7f, 0.97f),
                        panelHi = hslToColor(earthHue, earthSat, 0.90f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.12f),
                        onAccent = Color.White,
                        text = Color(0xFF38332C),
                        subText = Color(0xFF8F867B),
                        canvasBg = hslToColor(earthHue, earthSat * 0.4f, 0.89f),
                        border = Color.Transparent,
                        icon = Color(0xFF736A5E),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                    "HIGH" -> AppColors(
                        bg = hslToColor(earthHue, earthSat, 0.92f),
                        panel = Color(0xFFFFFFFF),
                        panelHi = hslToColor(earthHue, earthSat, 0.88f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.16f),
                        onAccent = Color.White,
                        text = Color(0xFF1F1A14),
                        subText = Color(0xFF756C60),
                        canvasBg = hslToColor(earthHue, earthSat * 0.4f, 0.86f),
                        border = Color.Transparent,
                        icon = Color(0xFF5A5145),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                    else -> AppColors(
                        bg = hslToColor(earthHue, earthSat, 0.93f),
                        panel = hslToColor(earthHue, earthSat * 0.6f, 0.965f),
                        panelHi = hslToColor(earthHue, earthSat, 0.89f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.12f),
                        onAccent = Color.White,
                        text = Color(0xFF2C2720),
                        subText = Color(0xFF857C70),
                        canvasBg = hslToColor(earthHue, earthSat * 0.4f, 0.88f),
                        border = Color.Transparent,
                        icon = Color(0xFF696054),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                }
            }
            ThemeStyle.VIBRANT_GLOW -> {
                val coolHue = if (accentSat > 0.05f) hue else 220f
                val glowSat = 0.06f
                when (contrast) {
                    "SOFT" -> AppColors(
                        bg = hslToColor(coolHue, glowSat, 0.95f),
                        panel = Color(0xFFFAFBFC),
                        panelHi = hslToColor(coolHue, glowSat, 0.92f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.12f),
                        onAccent = Color.White,
                        text = Color(0xFF2E333D),
                        subText = Color(0xFF838B9B),
                        canvasBg = hslToColor(coolHue, glowSat * 0.3f, 0.90f),
                        border = Color.Transparent,
                        icon = Color(0xFF6B7485),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                    "HIGH" -> AppColors(
                        bg = hslToColor(coolHue, glowSat, 0.93f),
                        panel = Color(0xFFFFFFFF),
                        panelHi = hslToColor(coolHue, glowSat, 0.89f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.18f),
                        onAccent = Color.White,
                        text = Color(0xFF0F1217),
                        subText = Color(0xFF666E7E),
                        canvasBg = hslToColor(coolHue, glowSat * 0.3f, 0.87f),
                        border = Color.Transparent,
                        icon = Color(0xFF4B5363),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                    else -> AppColors(
                        bg = hslToColor(coolHue, glowSat, 0.94f),
                        panel = Color(0xFFFFFFFF),
                        panelHi = hslToColor(coolHue, glowSat, 0.905f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.14f),
                        onAccent = Color.White,
                        text = Color(0xFF1E222A),
                        subText = Color(0xFF757D8E),
                        canvasBg = hslToColor(coolHue, glowSat * 0.3f, 0.885f),
                        border = Color.Transparent,
                        icon = Color(0xFF5B6373),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                }
            }
            else -> {
                // MORANDI_TINTED
                val tintSat = (accentSat * 0.35f).coerceIn(0.06f, 0.11f)
                when (contrast) {
                    "SOFT" -> AppColors(
                        bg = hslToColor(hue, tintSat, 0.95f),
                        panel = hslToColor(hue, tintSat * 0.6f, 0.975f),
                        panelHi = hslToColor(hue, tintSat, 0.915f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.12f),
                        onAccent = Color.White,
                        text = hslToColor(hue, 0.08f, 0.22f),
                        subText = hslToColor(hue, 0.06f, 0.55f),
                        canvasBg = hslToColor(hue, tintSat * 0.35f, 0.90f),
                        border = Color.Transparent,
                        icon = hslToColor(hue, 0.06f, 0.44f),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                    "HIGH" -> AppColors(
                        bg = hslToColor(hue, tintSat, 0.92f),
                        panel = Color(0xFFFFFFFF),
                        panelHi = hslToColor(hue, tintSat, 0.88f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.16f),
                        onAccent = Color.White,
                        text = Color(0xFF111114),
                        subText = hslToColor(hue, 0.08f, 0.45f),
                        canvasBg = hslToColor(hue, tintSat * 0.35f, 0.86f),
                        border = Color.Transparent,
                        icon = hslToColor(hue, 0.08f, 0.35f),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                    else -> AppColors(
                        bg = hslToColor(hue, tintSat, 0.94f),
                        panel = hslToColor(hue, tintSat * 0.5f, 0.97f),
                        panelHi = hslToColor(hue, tintSat, 0.90f),
                        accent = accent,
                        accentHi = blendColors(accent, Color.Black, 0.12f),
                        onAccent = Color.White,
                        text = hslToColor(hue, 0.08f, 0.16f),
                        subText = hslToColor(hue, 0.06f, 0.52f),
                        canvasBg = hslToColor(hue, tintSat * 0.35f, 0.88f),
                        border = Color.Transparent,
                        icon = hslToColor(hue, 0.06f, 0.40f),
                        scrim = Color(0x3D000000),
                        gridLine = Color(0x1F000000),
                        canvasShadow = Color(0x1F000000),
                    )
                }
            }
        }
    }

    val effectiveAccent = if (!isDark) {
        adjustAccentForLightMode(accent)
    } else {
        accent
    }

    val accentHi = if (isDark) {
        blendColors(effectiveAccent, Color.White, 0.18f)
    } else {
        blendColors(effectiveAccent, Color.Black, 0.14f)
    }
    return baseColors.copy(
        accent = effectiveAccent,
        accentHi = accentHi,
        onAccent = computeOnAccent(effectiveAccent),
    )
}

/** Build Monet dynamic color theme for Android 12+ (Material You) with style generation */
fun getMonetColors(
    context: Context,
    isDark: Boolean = true,
    fallbackAccent: Color = Color(0xFF879ED1),
    contrast: String = "STANDARD",
    style: String = ThemeStyle.MORANDI_TINTED,
): AppColors {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            buildThemeColors(isDark = isDark, accent = scheme.primary, contrast = contrast, style = style)
        } catch (_: Throwable) {
            buildThemeColors(isDark = isDark, accent = fallbackAccent, contrast = contrast, style = style)
        }
    }
    return buildThemeColors(isDark = isDark, accent = fallbackAccent, contrast = contrast, style = style)
}

/**
 * Active theme. UI code reads [Theme.current]; a theme system can swap
 * this (light/dark/accent presets or user-defined colors) at runtime.
 */
object Theme {
    var current by mutableStateOf(MorandiColors)
}

/** Backwards-compatible alias so existing call sites can migrate gradually. */
val Morandi: AppColors
    get() = Theme.current

val BrushPresets =
    listOf(
        BrushPreset("钢笔", 4.0),
        BrushPreset("铅笔", 2.0),
        BrushPreset("马克笔", 18.0),
        BrushPreset("毛笔", 12.0),
        BrushPreset("喷枪", 30.0),
        BrushPreset("圆头", 40.0),
    )

val CanvasPresets =
    listOf(
        CanvasPreset("手机", 1080, 1920),
        CanvasPreset("方形", 2000, 2000),
        CanvasPreset("横屏", 1920, 1080),
        CanvasPreset("A4", 2480, 3508),
        CanvasPreset("壁纸", 1440, 3120),
        CanvasPreset("2K", 2560, 1440),
    )

val ColorSwatches =
    listOf(
        "#262A30",
        "#7C8F9E",
        "#8D9E8F",
        "#C9ADA7",
        "#F2F0EA",
        "#B4552D",
        "#879ED1",
        "#9A8F7B",
        "#FFFFFF",
        "#000000",
    )

val PresetAccentSwatches =
    listOf(
        "#879ED1",
        "#768A5A",
        "#A8B490",
        "#BDB79A",
        "#BDA989",
        "#8A665A",
        "#BD8D8B",
        "#9D8BC9",
        "#625A8A",
    )

/** Parse a "#rrggbb" hex string into a Compose Color. */
fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/**
 * 显式强制使用系统标准箭头指针（避免受 View 级透明指针影响）
 */
fun systemDefaultPointerIcon(context: Context): PointerIcon {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon(
            android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_DEFAULT)
        )
    } else {
        PointerIcon.Default
    }
}

/**
 * 显式使用 Android 官方系统 TYPE_NULL 隐藏光标（避免使用自定义 Bitmap 导致的硬件切换闪烁）
 */
fun systemNullPointerIcon(context: Context): PointerIcon {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon(
            android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL)
        )
    } else {
        PointerIcon.Default
    }
}

fun Modifier.systemHoverIcon(context: Context): Modifier =
    this.pointerHoverIcon(systemDefaultPointerIcon(context), overrideDescendants = true)



