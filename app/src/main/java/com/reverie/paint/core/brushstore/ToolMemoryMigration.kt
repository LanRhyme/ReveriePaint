/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.PaintViewModel

/**
 * 大小/不透明度/流量只归按工具记忆（paramMemory）。旧版把它们也写进 overrides.json（对所有工具生效）：
 * 启动时把这些值搬进画笔/橡皮/智能涂抹三个工具槽里**还没有该笔刷条目**的那些（保持用户现在看到的值），再从覆盖里删掉。
 *
 * 不加版本号：每次启动扫一遍，覆盖里没有这三个键就什么都不做（幂等）；已有记忆条目的工具保持原值。
 * 旧记录迁移（[LegacyBrushParamsMigrator]）产生的这三个值也走这里，所以两条来源最终都落在工具记忆。
 */
object ToolMemoryMigration {
    val TOOLS = listOf("brush", "eraser", "smudge")

    /**
     * 返回新的工具槽映射。有改动时先调用 [persist]（写工具槽），再清覆盖，避免中途退出丢值。
     * 只处理已存在的工具槽；某工具从没选过笔（没有槽）则它第一次使用时就是出厂值。
     */
    fun run(
        states: Map<String, PaintViewModel.ToolBrushState>,
        persist: (Map<String, PaintViewModel.ToolBrushState>) -> Unit,
    ): Map<String, PaintViewModel.ToolBrushState> {
        val keys = PresetReadback.TOOL_MEMORY_KEYS
        val ids = BrushOverrideStore.ids().filter { id -> BrushOverrideStore.get(id).controls.keys.any { it in keys } }
        if (ids.isEmpty()) return states

        var next = states
        for (id in ids) {
            val controls = BrushOverrideStore.get(id).controls
            val factory = PresetReadback.snapshotParams(id)?.second
            val size = (controls[ControlId.SIZE.key] as? Number)?.toDouble() ?: factory?.size
            val opacity = (controls[ControlId.OPACITY.key] as? Number)?.toDouble() ?: factory?.opacity
            val flow = (controls[ControlId.FLOW.key] as? Number)?.toDouble() ?: factory?.flow
            if (size == null || opacity == null || flow == null) {
                BrushDiagnostics.log("tool_memory_skip", "id" to id, "reason" to "no_factory")
                continue
            }
            for (tool in TOOLS) {
                val s = next[tool] ?: continue
                if (id in s.paramMemory) continue
                next = next + (tool to s.copy(paramMemory = s.paramMemory + (id to listOf(size, opacity, flow))))
            }
        }
        if (next != states) persist(next)
        for (id in ids) {
            val cur = BrushOverrideStore.get(id)
            val left = cur.copy(controls = cur.controls - keys, dirty = cur.dirty - keys)
            if (left.isEmpty() && left.dirty.isEmpty()) BrushOverrideStore.clear(id) else BrushOverrideStore.replace(id, left)
        }
        BrushDiagnostics.log("tool_memory_migrated", "presets" to ids.size)
        return next
    }
}
