package com.reverie.paint.ui.theme

import androidx.compose.ui.graphics.Color
import com.reverie.paint.model.BrushPreset
import com.reverie.paint.model.CanvasPreset

/**
 * Morandi palette - low saturation, warm/cool tones.
 * Centralized so a theme system can be added later.
 */
object Morandi {
    val bg = Color(0xFF262A30) // deep blue-grey
    val panel = Color(0xFF30353D)
    val panelHi = Color(0xFF3A4049)
    val accent = Color(0xFF7C8F9E) // misty blue
    val accentHi = Color(0xFF92A5B5)
    val text = Color(0xFFE2E0DA) // warm white
    val subText = Color(0xFF93989F)
    val canvasBg = Color(0xFF3D424A)
    val border = Color(0xFF444A53)
    val icon = Color(0xFFC9C6BD)

    val gridLine = Color(0xFF484E57)
    val canvasShadow = Color(0x66000000)
}

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
