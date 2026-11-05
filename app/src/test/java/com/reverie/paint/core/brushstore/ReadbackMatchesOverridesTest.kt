/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ReadbackMatchesOverrides（执行文档 W-05 不变式）：覆盖 → 有效 kpp → 回读，每个被覆盖的控件
 * 回读值都等于覆盖值；未覆盖的控件回读值等于快照值。全部内置 kpp。
 */
class ReadbackMatchesOverridesTest {

    @Test
    fun `每个覆盖控件回读等于覆盖值，其余控件等于快照值`() {
        var checked = 0
        val failures = ArrayList<String>()
        for ((stem, _) in KppFixtures.all) {
            val snap = KppFixtures.bytes(stem)
            val xml0 = KppHelper.readPresetXml(snap, stripEmbeddedResources = true)!!
            val edits = OverrideFixtures.editsFor(xml0, max = 8)
            if (edits.isEmpty()) continue
            val eff = EffectivePresetBuilder.build(snap, "kpp", OverrideFixtures.overrides(edits))
            val before = PresetReadback.controlValues(snap)
            val after = PresetReadback.controlValues(eff)
            val ui = PresetReadback.fromEffectiveBytes(eff)
            for ((id, v) in edits) {
                if (!PresetPropertyMap.same(id, after[id], v)) failures += "$stem ${id.key}: kpp=${after[id]} 期望=$v"
                val uiV = PresetPropertyMap.spec(id).get(ui)
                if (!PresetPropertyMap.same(id, uiV, v)) failures += "$stem ${id.key}: ui=$uiV 期望=$v"
            }
            for ((id, v) in before) {
                if (id in edits) continue
                // 同组联动的控件（如曲线开关影响 pressure* 派生）不算在内：只比 kpp 层控件值
                if (!PresetPropertyMap.same(id, after[id], v)) failures += "$stem ${id.key}: 未覆盖却变了 ${v} -> ${after[id]}"
            }
            checked++
        }
        if (failures.isNotEmpty()) fail("${failures.size} 处不一致，前 20 条:\n" + failures.take(20).joinToString("\n"))
        assertTrue("参与的预设太少: $checked", checked >= 260)
    }
}
