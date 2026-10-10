/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

/** Krita-style brush grouping: strictly aligned with Krita default presets. */
fun inferBrushGroup(name: String): String =
    when {
        name.startsWith("a)_Eraser", ignoreCase = true) || name.contains("Eraser", ignoreCase = true) -> "橡皮擦"
        name.startsWith("e)") || name.contains("Marker", ignoreCase = true) -> "马克笔"
        name.startsWith("t)") || name.contains("Shape", ignoreCase = true) || (name.contains("Fill", ignoreCase = true) && !name.contains("starfield", ignoreCase = true)) -> "形状"
        name.startsWith("u)") || name.contains("Pixel", ignoreCase = true) || name.contains("pixel") -> "像素画"
        name.startsWith("l)") || name.startsWith("x)") || name.contains("Adjust", ignoreCase = true) || name.contains("FX", ignoreCase = true) || name.contains("Distort", ignoreCase = true) || name.contains("Clone", ignoreCase = true) || name.contains("Filter", ignoreCase = true) || name.contains("Move_tool") -> "特效与滤镜"
        name.startsWith("d)") || name.startsWith("Ink", ignoreCase = true) || name.contains("_Ink", ignoreCase = true) || name.contains("Gpen", ignoreCase = true) || name.contains("ballpen", ignoreCase = true) || name.contains("sumi-e", ignoreCase = true) -> "勾线"
        name.startsWith("c)") || name.startsWith("h)") || name.contains("Pencil", ignoreCase = true) || name.contains("Charcoal", ignoreCase = true) || name.contains("Chalk", ignoreCase = true) || name.contains("Pastel", ignoreCase = true) -> "铅笔"
        name.startsWith("b)") || name.contains("Airbrush", ignoreCase = true) || name.contains("Basic", ignoreCase = true) || name.startsWith("Layout") || name.startsWith("Quick") -> "基础"
        name.startsWith("i)") || name.startsWith("j)") || name.contains("Wet", ignoreCase = true) || name.contains("Water", ignoreCase = true) || name.contains("Sparkle_wet") || name.contains("Splat_wet") -> "水彩"
        name.startsWith("k)") || name.contains("Blender", ignoreCase = true) || name.contains("Smudge", ignoreCase = true) -> "混合"
        name.startsWith("v)_Sketching") || name.contains("Sketch", ignoreCase = true) || name.contains("Curve", ignoreCase = true) -> "速写"
        name.startsWith("f)") || name.startsWith("g)") || name.contains("Bristle", ignoreCase = true) || name.contains("Oils", ignoreCase = true) || name.contains("Block") || name.contains("Dry") -> "绘画"
        name.startsWith("w)") || name.startsWith("y)") || name.contains("Texture", ignoreCase = true) || name.contains("Textured", ignoreCase = true) || name.contains("Screentone", ignoreCase = true) || name.contains("Hatch", ignoreCase = true) || name.contains("Tangent", ignoreCase = true) || name.contains("Grid", ignoreCase = true) || name.contains("Sponge", ignoreCase = true) || name.contains("Rake", ignoreCase = true) || name.contains("Brush_", ignoreCase = true) -> "纹理与排线"
        name.startsWith("z)") || name.contains("Stamp", ignoreCase = true) || name.contains("Spray", ignoreCase = true) || name.contains("Splat", ignoreCase = true) || name.contains("particles", ignoreCase = true) || name.contains("Fuzzy", ignoreCase = true) || name.contains("dyna_dots", ignoreCase = true) || name.contains("Experimental", ignoreCase = true) -> "印章与喷溅"
        else -> "基础"
    }

/** Per-preset independent brush parameters. */
data class BrushParams(
    /**
     * 动力学曲线 (optionKey -> Krita sensor param 全文)。
     * 曲线必须随预设落盘, 否则切换笔刷/重启后只剩引擎里的即时值, 曲线编辑等于白做。
     */
    val dynamicOptions: Map<String, String> = emptyMap(),
    val size: Double = 20.0,
    val opacity: Double = 1.0,
    val flow: Double = 1.0,
    val spacing: Double = 0.1,
    val angle: Double = 0.0,
    val scatter: Double = 0.0,
    val fade: Double = KppHelper.FADE_SOLID,
    val softness: Double = KppHelper.SOFTNESS_NEUTRAL,
    val ratio: Double = 1.0,
    val sharpness: Double = 0.0,
    val rotation: Double = 0.0,
    val compositeOp: String = "normal",
    val antiAliasing: Int = 1,
    val tipShape: Int = 0,
    val randomFlipX: Boolean = false,
    val randomFlipY: Boolean = false,
    val followDirection: Boolean = false,
    val streamline: Double = 0.0,
    val taper: Double = 0.0,
    val textureEnabled: Boolean = false,
    val textureScale: Double = 1.0,
    val textureStrength: Double = 0.5,
    val textureMode: String = "multiply",
    val texturePattern: String = "",
    val hueJitter: Double = 0.0,
    val satJitter: Double = 0.0,
    val valJitter: Double = 0.0,
    val secondaryMix: Double = 0.0,
    val pressureColorMix: Boolean = false,
    val pressureEnabled: Boolean = true,
    val pressureSize: Double = 1.0,
    val pressureOpacity: Double = 1.0,
    val pressureFlow: Double = 1.0,
    val speedSize: Double = 0.0,
    val pressureCurve: Int = 0,
    val minSizeLimit: Double = 1.0,
    val maxSizeLimit: Double = 500.0,
    val tipAsset: String = "",
    val paintOpId: String = "defaultpaintop",
    val airbrush: Boolean = false,
    val airbrushRate: Double = 30.0,
    val smudgeRate: Double = 0.5,
    val smudgeLength: Double = 0.5,
    val colorRate: Double = 0.5,
    val smudgeMode: Int = 0,
    val spikes: Int = 2,
    val jitterAngle: Double = 0.0,
    val jitterSize: Double = 0.0,
    val author: String = "ReveriePaint",
    val isAuthorLocked: Boolean = false,
    val description: String = "",
    val version: String = "1.0",
    val isCustomized: Boolean = false,
    val dynamicsCustomized: Boolean = false,
    val smudgeCustomized: Boolean = false,
    val spacingCustomized: Boolean = false,
    val maskingEnabled: Boolean = false,
    val maskingCompositeOp: String = "multiply",
    val maskingSizeRatio: Double = 1.0,
    val maskingSpacing: Double = 0.1,
    val maskingTipAsset: String = "",
    val maskingTipShape: Int = 0,
    val maskingFade: Double = 0.0,
    val maskingSoftness: Double = 1.0,
    val rotationSensor: String = "drawingangle",
    val scatterSensor: String = "fuzzy",
    val sizeSensor: String = "pressure",
    val opacitySensor: String = "pressure",
    val flowSensor: String = "pressure",
)

/** A bundled Krita brush preset (.kpp) with its PNG thumbnail. */
data class BrushPresetInfo(
    val index: Int,
    val name: String,
    val thumbBytes: ByteArray,
    val group: String = "", // effective group (custom override or inferred)
    val isBuiltIn: Boolean = false,
)

val BUILT_IN_BRUSH_GROUPS = setOf(
    "全部", "常用", "最近", "基础", "铅笔", "勾线", "马克笔", "绘画", "水彩", "混合",
    "速写", "形状", "特效与滤镜", "纹理与排线", "印章与喷溅", "像素画", "橡皮擦"
)

fun isBuiltInBrushGroup(group: String, presets: List<BrushPresetInfo> = emptyList()): Boolean {
    if (BUILT_IN_BRUSH_GROUPS.contains(group)) return true
    if (presets.any { it.isBuiltIn && it.group == group }) return true
    return false
}

fun PaintViewModel.isBuiltInGroup(group: String): Boolean {
    if (customBrushGroups.contains(group)) return false
    return isBuiltInBrushGroup(group, brushPresets)
}
