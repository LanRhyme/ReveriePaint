/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import java.io.File

/**
 * 笔刷重置与测试会话管理 (W-06)。
 * 实际编辑与防抖统一走 BrushStoreVm，此处仅收拢 reset 逻辑。
 */
object BrushEditSession {

    private val lock = Any()

    /** 提交互斥执行（回滚与提交锁） */
    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    /** 重置（W-06）：保留 rawKeys 与元数据，清空覆盖，有效文件退回初始字节 */
    fun reset(id: String, presetDir: File): EffectivePresetBuilder.Result? = synchronized(lock) {
        if (BrushStoreBootstrap.readOnly) return null
        // rawKeys 是导入时生成的外部引用（W-08），METADATA_KEYS 是预设元数据（保存时填写的作者信息）
        val cur = BrushOverrideStore.get(id)
        val raw = cur.rawKeys
        val meta = cur.controls.filterKeys { it in PresetReadback.METADATA_KEYS }
        if (raw.isEmpty() && meta.isEmpty()) {
            BrushOverrideStore.clear(id)
        } else {
            BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = meta, rawKeys = raw))
        }
        val r = EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("reset", "id" to id, "md5" to r?.md5)
        r
    }

    /** 仅供单测重置测试状态使用 */
    fun resetForTest() {
        // no-op, kept for test suite compatibility
    }
}
