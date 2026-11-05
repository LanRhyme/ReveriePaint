/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushIdentity

/**
 * 工具槽（`toolBrushStates`）里记录的预设，如何映射到当前预设表（#136 A / v5 S3）。
 *
 * 以 presetId 为身份；缺失（老数据，prefs 只有 pi）才退回索引。
 * 老 prefs 里的名字（pn）在读入时已转成 id（loadToolBrushStates），这里不再认名字：
 * 1. id 命中 → 用它（重排安全、重命名安全）；
 * 2. id 存在但找不到（预设已被删除）→ null，绝不退回索引；
 * 3. id 缺失 → 退回索引，并由调用方回填 id；
 * 4. 都不命中 → null。
 *
 * 纯函数，JVM 单测直接可用。
 */
internal object ToolPresetMemory {

    fun resolve(
        storedId: String,
        storedIndex: Int,
        presets: List<BrushPresetInfo>,
    ): BrushPresetInfo? {
        if (storedId.isNotBlank()) {
            val entry = BrushIdentity.entry(storedId)
            if (entry != null) {
                presets.firstOrNull { it.name == entry.name }?.let { return it }
            }
            // 被删除的 id：绝不退回索引
            return null
        }
        if (storedIndex < 0) return null
        return presets.firstOrNull { it.index == storedIndex }
    }
}
