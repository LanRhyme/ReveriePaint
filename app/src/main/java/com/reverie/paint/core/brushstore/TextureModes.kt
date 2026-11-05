/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

/**
 * Krita 纹理模式枚举（KisTextureOptionData.h:29-46 `TexturingMode`），按整数下标排列（W-03 / D-3）。
 *
 * 旧版 UI 用 hard_light/soft_light 等 Krita 不存在的名字，[toInt] 把旧名按旧写入器的整数
 * 映射兼容过来（旧写入器 KppHelper:953-965 / 原生 RCB:1735-1742 的同一张表），并记日志。
 */
object TextureModes {
    val NAMES: List<String> = listOf(
        "multiply", "subtract", "lightness", "gradient", "darken", "overlay",
        "color_dodge", "color_burn", "linear_dodge", "linear_burn",
        "hard_mix_photoshop", "hard_mix_softer_photoshop",
        "height", "linear_height", "height_photoshop", "linear_height_photoshop",
    )

    /** 旧名 → 旧写入器实际写下的整数（保持已存数据的效果不变） */
    private val LEGACY_ALIASES = mapOf(
        "dodge" to 6, "burn" to 7, "hard_light" to 10, "soft_light" to 11, "screen" to 0,
    )

    fun toInt(name: String?): Int {
        val n = name?.trim()?.lowercase().orEmpty()
        val idx = NAMES.indexOf(n)
        if (idx >= 0) return idx
        n.toIntOrNull()?.let { if (it in NAMES.indices) return it }
        LEGACY_ALIASES[n]?.let {
            BrushDiagnostics.log("texture_mode_alias", "from" to n, "to" to it)
            return it
        }
        return 0
    }

    fun nameOf(code: Int): String = NAMES.getOrElse(code) { NAMES[0] }
}
