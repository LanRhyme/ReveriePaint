/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

/**
 * 笔刷引擎 id（Krita `paintopid`）的唯一真源。
 *
 * 为什么需要它：`KisPaintOpPreset::load()` 用 `paintopid` 去 `KisPaintOpRegistry` 查工厂，
 * 查不到就把预设标成 `setValid(false)`（libs/image/brushengine/kis_paintop_preset.cpp:338-343），
 * 引擎随后加载失败、停在上一支笔上 —— 用户看到的就是"切了引擎没反应"。
 * 曾经 Brush Studio 的引擎单选写的是 `"spray"/"sketch"/"hairy"`，而 Krita 注册的是
 * `"spraybrush"/"sketchbrush"/"hairybrush"`（plugins/paintops/{spray,sketch,hairy} 下各引擎的 plugin 源文件），
 * 选这三档会直接把预设写坏；[canonical] 把历史短名与空值统一改写成合法 id。
 */
object PaintOpIds {
    const val PAINTBRUSH = "paintbrush"
    const val COLOR_SMUDGE = "colorsmudge"
    const val SPRAY = "spraybrush"
    const val SKETCH = "sketchbrush"
    const val HAIRY = "hairybrush"
    const val ROUND_MARKER = "roundmarker"
    const val HATCHING = "hatchingbrush"
    const val CURVE = "curvebrush"
    const val PARTICLE = "particlebrush"
    const val GRID = "gridbrush"
    const val EXPERIMENT = "experimentbrush"
    const val TANGENT_NORMAL = "tangentnormal"
    const val DEFORM = "deformbrush"
    const val DUPLICATE = "duplicate"
    const val FILTER = "filter"

    /** 历史短名（曾经写进用户预设）→ Krita 真实注册 id */
    private val ALIASES = mapOf(
        "spray" to SPRAY,
        "sketch" to SKETCH,
        "hairy" to HAIRY,
        "particle" to PARTICLE,
        "deform" to DEFORM,
        "curve" to CURVE,
        "hatching" to HATCHING,
        "experiment" to EXPERIMENT,
        "grid" to GRID,
        "tangent" to TANGENT_NORMAL,
        "defaultpaintop" to PAINTBRUSH,
        "brush" to PAINTBRUSH,
    )

    /** 空值/别名 → 合法 id；已经是合法 id 的原样返回 */
    fun canonical(raw: String): String {
        val id = raw.trim()
        if (id.isEmpty()) return PAINTBRUSH
        return ALIASES[id.lowercase()] ?: id
    }
}
