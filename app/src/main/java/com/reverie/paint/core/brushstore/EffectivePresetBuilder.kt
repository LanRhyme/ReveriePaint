/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import java.io.File

/**
 * 有效预设 = 不可变快照 + 稀疏覆盖（执行文档 W-04）。
 *
 * - 覆盖里没有进 kpp 的控件（只有 APP_ONLY 或为空）且没有 rawKeys 时，**原样返回快照字节**，
 *   保证"重置后有效文件 md5 == 快照 md5"。
 * - `.myb` 没有 XML 映射（NEEDS_EVIDENCE），P0 只能原样回退，LIVE 控件由 [BrushApply] 补调 setter。
 * - [materialize] 写 `paintoppresets/<name>.<ext>`，先写 .tmp 再 rename；字节未变时不写。
 */
object EffectivePresetBuilder {

    data class Result(val id: String, val file: File, val written: Boolean, val md5: String, val buildMs: Long)

    /** 覆盖中会写进 kpp 的控件（LIVE + REBUILD），按 [ControlId] 解析；未知键忽略并记日志 */
    fun kppControls(o: BrushOverrideStore.Overrides): Map<ControlId, Any> {
        val out = LinkedHashMap<ControlId, Any>()
        for ((k, v) in o.controls) {
            val id = ControlId.fromKey(k)
            if (id == null) {
                BrushDiagnostics.log("override_unknown_key", "key" to k)
                continue
            }
            if (id.klass != ControlClass.APP_ONLY) out[id] = v
        }
        return out
    }

    /**
     * [thumb] 非空时用它当 PNG 底（kpp 就是 PNG 加 preset 块，旧更新缩略图同理）；
     * 为 null 时行为与之前完全一致（无覆盖时返回同一个 snapshot 实例）。
     */
    fun build(snapshot: ByteArray, ext: String, o: BrushOverrideStore.Overrides, thumb: ByteArray? = null): ByteArray {
        val controls = kppControls(o)
        if (thumb == null && controls.isEmpty() && o.rawKeys.isEmpty()) return snapshot
        if (!ext.equals("kpp", ignoreCase = true)) return snapshot
        val xml = KppHelper.readPresetXml(snapshot, stripEmbeddedResources = false) ?: return snapshot
        // rawKeys 是"修正过的快照"（导入时的资源改名等，W-08），先写；控件覆盖叠在其上，
        // 这样改笔尖柔边等会重写 brush_definition 的控件不会把改名后的 filename 冲掉。
        var x = xml
        for ((k, v) in o.rawKeys) x = KppXml.set(x, k, v)
        if (controls.isNotEmpty()) {
            // 基线读取用去掉内嵌资源的 XML（值相同，扫描量小几个数量级）
            var readXml = KppHelper.readPresetXml(snapshot, stripEmbeddedResources = true) ?: xml
            for ((k, v) in o.rawKeys) readXml = KppXml.set(readXml, k, v)
            val base = PresetPropertyMap.readParams(readXml)
            val eff = PresetPropertyMap.applyToParams(base, controls)
            x = PresetPropertyMap.applyToXml(x, controls, eff)
        }
        if (thumb != null) return KppHelper.replacePresetXml(thumb, x)
        if (x == xml) return snapshot
        return KppHelper.replacePresetXml(snapshot, x)
    }

    /** 按 id 生成有效字节；没有快照返回 null */
    fun buildFor(id: String): ByteArray? {
        val snap = PresetSnapshotStore.snapshotFile(id) ?: return null
        return build(snap.readBytes(), snap.extension, BrushOverrideStore.get(id), BrushThumbStore.get(id))
    }

    fun targetFile(presetDir: File, e: BrushIdentity.Entry): File = File(presetDir, "${e.name}.${e.ext}")

    fun materialize(id: String, presetDir: File): Result? {
        val e = BrushIdentity.entry(id) ?: return null
        val t0 = System.nanoTime()
        val bytes = try {
            buildFor(id)
        } catch (t: Throwable) {
            BrushDiagnostics.log("build_failed", "id" to id, "err" to t.toString())
            null
        } ?: return null
        val target = targetFile(presetDir, e)
        val same = target.exists() && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)
        if (!same) AtomicFiles.write(target, bytes)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val md5 = BrushDiagnostics.md5(bytes)
        BrushDiagnostics.log("materialize", "id" to id, "name" to e.name, "written" to !same, "md5" to md5, "ms" to ms)
        return Result(id, target, !same, md5, ms)
    }

    fun materializeAll(presetDir: File): List<Result> = BrushIdentity.all().mapNotNull { materialize(it.id, presetDir) }
}
