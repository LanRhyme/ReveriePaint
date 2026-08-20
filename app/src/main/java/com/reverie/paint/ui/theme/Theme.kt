package com.reverie.paint.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
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

/** Default Morandi palette - low saturation, warm/cool elegant tones. */
val MorandiColors =
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

/** Build Monet dynamic color theme for Android 12+ (Material You) */
fun getMonetColors(context: Context, fallbackAccent: Color = Color(0xFF5E8BA8)): AppColors {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            val scheme = dynamicDarkColorScheme(context)
            AppColors(
                bg = scheme.background,
                panel = scheme.surface,
                panelHi = scheme.surfaceVariant,
                accent = scheme.primary,
                accentHi = scheme.primaryContainer,
                onAccent = scheme.onPrimary,
                text = scheme.onBackground,
                subText = scheme.onSurfaceVariant,
                canvasBg = scheme.background,
                border = scheme.outlineVariant.copy(alpha = 0.45f),
                icon = scheme.onSurfaceVariant,
                scrim = Color(0x99000000),
                gridLine = scheme.outline.copy(alpha = 0.25f),
                canvasShadow = Color(0x73000000),
            )
        } catch (_: Throwable) {
            MorandiColors.copy(accent = fallbackAccent, accentHi = fallbackAccent)
        }
    }
    return MorandiColors.copy(accent = fallbackAccent, accentHi = fallbackAccent)
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
