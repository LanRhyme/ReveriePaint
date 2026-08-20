package com.reverie.paint.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
)

/** Default Morandi Dark palette - deep elegant charcoal slate. */
val MorandiDarkColors =
    AppColors(
        bg = Color(0xFF121316), // Deep refined Morandi charcoal
        panel = Color(0xFF1E2024), // Balanced low-saturation slate panel
        panelHi = Color(0xFF2A2D33), // Raised button and card surface
        accent = Color(0xFF5E8BA8), // Refined Morandi blue
        accentHi = Color(0xFF7CA4BE),
        onAccent = Color(0xFFFFFFFF),
        text = Color(0xFFE8EAED), // Clean readable text
        subText = Color(0xFF9DA1A7), // Gentle subtext
        canvasBg = Color(0xFF191A1D), // Neutral workspace background
        border = Color(0xFF32353C), // Muted crisp border
        icon = Color(0xFFBAC0C7),
        scrim = Color(0x99000000),
        gridLine = Color(0xFF383B42),
        canvasShadow = Color(0x73000000),
    )

/** Default Morandi Light palette - serene light grey background with pure white cards. */
val MorandiLightColors =
    AppColors(
        bg = Color(0xFFE6E8EC), // Page background: clearly deeper/darker than foreground white cards
        panel = Color(0xFFFFFFFF), // Pure white foreground cards/panels
        panelHi = Color(0xFFF0F2F5), // Inner card surfaces/pills
        accent = Color(0xFF3E6988), // Refined Morandi blue with crisp legibility on white and grey
        accentHi = Color(0xFF5681A0),
        onAccent = Color(0xFFFFFFFF),
        text = Color(0xFF141619), // Crisp deep dark text
        subText = Color(0xFF666B74), // Balanced secondary text
        canvasBg = Color(0xFFD8DCE2), // Clean neutral workspace backdrop
        border = Color(0xFFD3D7DF), // Subtle light hairline border
        icon = Color(0xFF313640),
        scrim = Color(0x66000000),
        gridLine = Color(0xFFCCD1DA),
        canvasShadow = Color(0x2E000000),
    )

/** Alias for backward compatibility */
val MorandiColors = MorandiDarkColors

/** Soften and calibrate Monet dynamic color to Morandi saturation and luminance range */
private fun Color.toMorandiAccent(isDark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt()
        ),
        hsv
    )
    // Coerce saturation to calm Morandi muted range (25% - 48%)
    hsv[1] = hsv[1].coerceIn(0.24f, 0.46f)
    // Coerce brightness/value for optimal contrast and elegance
    hsv[2] = if (isDark) hsv[2].coerceIn(0.62f, 0.78f) else hsv[2].coerceIn(0.42f, 0.58f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun blendColors(base: Color, tint: Color, tintWeight: Float): Color {
    val r = base.red * (1f - tintWeight) + tint.red * tintWeight
    val g = base.green * (1f - tintWeight) + tint.green * tintWeight
    val b = base.blue * (1f - tintWeight) + tint.blue * tintWeight
    return Color(red = r, green = g, blue = b, alpha = base.alpha)
}

/** Build Monet dynamic color theme for Android 12+ (Material You) with Morandi tuning */
fun getMonetColors(
    context: Context,
    isDark: Boolean = true,
    fallbackAccent: Color = Color(0xFF5E8BA8)
): AppColors {
    val fallbackBase = if (isDark) MorandiDarkColors else MorandiLightColors
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            val morandiAccent = scheme.primary.toMorandiAccent(isDark)
            val morandiAccentHi = scheme.primaryContainer.toMorandiAccent(isDark)

            val tintColor = scheme.primary
            val bgTintWeight = if (isDark) 0.05f else 0.03f
            val panelTintWeight = if (isDark) 0.06f else 0.02f
            val panelHiTintWeight = if (isDark) 0.08f else 0.04f

            fallbackBase.copy(
                bg = blendColors(fallbackBase.bg, tintColor, bgTintWeight),
                panel = blendColors(fallbackBase.panel, tintColor, panelTintWeight),
                panelHi = blendColors(fallbackBase.panelHi, tintColor, panelHiTintWeight),
                canvasBg = blendColors(fallbackBase.canvasBg, tintColor, bgTintWeight),
                accent = morandiAccent,
                accentHi = morandiAccentHi,
                border = blendColors(fallbackBase.border, tintColor, 0.05f),
            )
        } catch (_: Throwable) {
            fallbackBase.copy(accent = fallbackAccent, accentHi = fallbackAccent)
        }
    }
    return fallbackBase.copy(accent = fallbackAccent, accentHi = fallbackAccent)
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
        "#5A6E8A",
        "#9A8F7B",
        "#FFFFFF",
        "#000000",
    )

/** Parse a "#rrggbb" hex string into a Compose Color. */
fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))
