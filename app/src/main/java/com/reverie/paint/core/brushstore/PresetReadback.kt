/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import com.reverie.paint.core.resolvePresetSize
import java.io.File

/**
 * 有效字节 → 工作台 UI 值（执行文档 W-05）。VM 的 brush* 字段在 v2 下只从这里赋值，
 * 因而 UI 显示的就是引擎刚加载的那份 kpp。
 *
 * APP_ONLY 控件（streamline / taper / 作者…）不在 kpp 里，取自 [appBase]（旧 legacyParams 记录或默认值）。
 */
object PresetReadback {

    /** 只保留 [legacy] 中 APP_ONLY 控件的值，其余字段为 [BrushParams] 默认值 */
    fun appBase(legacy: BrushParams?): BrushParams {
        var p = BrushParams()
        if (legacy == null) return p
        for (id in ControlId.entries) {
            if (id.klass != ControlClass.APP_ONLY) continue
            val sp = PresetPropertyMap.spec(id)
            p = sp.set(p, sp.get(legacy))
        }
        return p
    }

    /** 预设元数据：重置时保留（导入时的作者/说明属于预设本身，不是用户调参） */
    val METADATA = setOf(ControlId.AUTHOR, ControlId.AUTHOR_LOCKED, ControlId.DESCRIPTION, ControlId.VERSION)
    val METADATA_KEYS: Set<String> = METADATA.map { it.key }.toSet()

    fun appOnlyControls(p: BrushParams, base: BrushParams, includeMetadata: Boolean): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (id in ControlId.entries) {
            if (id.klass != ControlClass.APP_ONLY) continue
            if (!includeMetadata && id in METADATA) continue
            val sp = PresetPropertyMap.spec(id)
            val vp = sp.get(p)
            val vb = sp.get(base)
            if (!PresetPropertyMap.same(id, vp, vb)) {
                out[id.key] = BrushOverrideStore.normalize(vp)
            }
        }
        return out
    }

    fun withAppControls(base: BrushParams, controls: Map<String, Any>): BrushParams {
        var p = base
        for ((k, v) in controls) {
            val id = ControlId.fromKey(k) ?: continue
            if (id.klass != ControlClass.APP_ONLY) continue
            val sp = PresetPropertyMap.spec(id)
            p = sp.set(p, v)
        }
        return p
    }

    /**
     * 大小/不透明度/流量的唯一归属是 [com.reverie.paint.core.PaintViewModel.ToolBrushState.paramMemory]（按工具×笔刷），
     * 不进 overrides.json；这里只留出厂值（快照）。旧版遗留的覆盖由 [ToolMemoryMigration] 搬走。
     */
    val TOOL_MEMORY: Set<ControlId> = setOf(ControlId.SIZE, ControlId.OPACITY, ControlId.FLOW)
    val TOOL_MEMORY_KEYS: Set<String> = TOOL_MEMORY.map { it.key }.toSet()

    /** 笔刷调参（"已修改"依据）：不含元数据，也不含按工具记忆的大小/不透明度/流量 */
    fun hasTuning(controls: Map<String, Any>): Boolean = controls.keys.any { it !in METADATA_KEYS && it !in TOOL_MEMORY_KEYS }

    /** 旧记录里是否有偏离默认值的 APP_ONLY 调参（不含元数据）——v2 下"已修改"的另一半依据 */
    fun appOnlyModified(legacy: BrushParams?, base: BrushParams = BrushParams()): Boolean {
        if (legacy == null) return false
        val d = base
        return ControlId.entries.any { id ->
            id.klass == ControlClass.APP_ONLY && id !in METADATA &&
                PresetPropertyMap.spec(id).let { !PresetPropertyMap.same(id, it.get(legacy), it.get(d)) }
        }
    }

    /**
     * 重置后的旧记录：[base]（快照自带的 APP_ONLY 基线，见 [baseline]）+ 元数据；
     * 两者都是默认值时返回 null（删除记录）
     */
    fun metadataOnly(legacy: BrushParams?, base: BrushParams = BrushParams()): BrushParams? {
        val d = BrushParams()
        var any = base != d
        if (legacy == null) return if (any) base else null
        var p = base
        for (id in METADATA) {
            val sp = PresetPropertyMap.spec(id)
            val v = sp.get(legacy)
            if (!PresetPropertyMap.same(id, v, sp.get(d))) {
                p = sp.set(p, v)
                any = true
            }
        }
        return if (any) p else null
    }

    /**
     * 每个预设一份缓存：基线（快照 source.json 的 APP_ONLY 基线）+ 快照 kpp 回读出的参数。
     * 为什么留：BrushStoreVm.mergeControl 每次滑块抬手/拖动都要读它，不缓存就是每次读文件 + 解 PNG + 解析 XML。
     * 校验：快照文件和 source.json 的 路径|大小|mtime 都进指纹——任一被换/重写，下一次读取自动重算，
     * 所以主代码里没有任何"手动清缓存"的调用（只有测试复位用 [clearForTest]）。
     */
    private class Cached(val key: String, val baseline: BrushParams, snapFile: File?) {
        val snap: BrushParams? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            snapFile?.let { f -> KppHelper.readPresetXml(f.readBytes(), stripEmbeddedResources = true) }
                ?.let { xml -> fromXml(xml, baseline) }
        }
    }

    private val baselineCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    private fun stat(f: File?): String = f?.let { "${it.path}|${it.length()}|${it.lastModified()}" } ?: ""

    private fun cached(id: String): Cached {
        val f = PresetSnapshotStore.snapshotFile(id)
        val key = stat(f) + "#" + stat(PresetSnapshotStore.sourceFile(id))
        baselineCache[id]?.takeIf { it.key == key }?.let { return it }
        if (f == null) BrushDiagnostics.log("baseline_snapshot_missing", "id" to id)
        // ABR 导入自带 appBaseline（空 {} 也算带）；kpp/bundle 导入和老快照没有 → 读时按笔刷自己的压感推导（不落盘、无需补写）
        val j = PresetSnapshotStore.source(id)?.optJSONObject("appBaseline")
        val c = Cached(key, baselineFromJson(j ?: f?.takeIf { BrushIdentity.isImported(id) }?.let { deriveBaselineJson(it.readBytes()) }), f)
        baselineCache[id] = c
        return c
    }

    /**
     * 该预设"出厂/导入时"的 APP_ONLY 值：重置回到它，偏离它才算已修改。
     * 只认标量 APP_ONLY 非元数据控件；没有 appBaseline 时就是 [BrushParams] 默认值。
     */
    fun baseline(id: String): BrushParams = cached(id).baseline

    /** (基线, 快照 kpp 回读参数)；没有快照或读不出 XML 时为 null */
    fun snapshotParams(id: String): Pair<BrushParams, BrushParams>? {
        val c = cached(id)
        return c.snap?.let { c.baseline to it }
    }

    fun baselineFromJson(j: org.json.JSONObject?): BrushParams {
        var p = BrushParams()
        if (j == null) return p
        for (k in j.keys()) {
            val id = ControlId.entries.firstOrNull { it.key == k && it.klass == ControlClass.APP_ONLY && it !in METADATA } ?: continue
            p = try {
                PresetPropertyMap.spec(id).set(p, j.get(k))
            } catch (t: Exception) {
                BrushDiagnostics.log("baseline_value_rejected", "key" to k, "err" to t.javaClass.simpleName)
                p
            }
        }
        return p
    }

    /** [p] 中偏离默认值的标量 APP_ONLY 非元数据控件，写进 source.json 的 appBaseline */
    fun baselineJson(p: BrushParams): org.json.JSONObject {
        val d = BrushParams()
        val o = org.json.JSONObject()
        for (id in ControlId.entries) {
            if (id.klass != ControlClass.APP_ONLY || id in METADATA) continue
            val sp = PresetPropertyMap.spec(id)
            val v = sp.get(p)
            if ((v is Boolean || v is Number || v is String) && !PresetPropertyMap.same(id, v, sp.get(d))) o.put(id.key, v)
        }
        return o
    }

    /**
     * 没有 appBaseline 的 kpp 快照（kpp/bundle 导入、旧版本落盘的快照）：基线取笔刷自己的压感设置——
     * Size/Opacity/Flow 任一由压力驱动才为 true，否则重置/首次选中都不会凭空加上压感宽度。
     */
    private fun deriveBaselineJson(kppBytes: ByteArray): org.json.JSONObject {
        val xml = KppHelper.readPresetXml(kppBytes, stripEmbeddedResources = true) ?: run {
            BrushDiagnostics.log("baseline_derive_failed", "reason" to "no_preset_xml")
            return org.json.JSONObject()
        }
        val a = fromXml(xml)
        val pressure = listOf(a.pressureSize, a.pressureOpacity, a.pressureFlow).any { it > 0.001 }
        return baselineJson(BrushParams(pressureEnabled = pressure))
    }

    /** 仅测试复位用：不同测试的临时目录之间不共享缓存 */
    fun clearForTest() = baselineCache.clear()

    fun fromXml(xml: String?, appBase: BrushParams = BrushParams()): BrushParams {
        if (xml == null) BrushDiagnostics.log("readback_xml_null")
        return PresetPropertyMap.readParams(xml, appBase).copy(
            isCustomized = true,
            dynamicsCustomized = true,
            smudgeCustomized = true,
            spacingCustomized = true,
        )
    }

    fun fromEffectiveBytes(bytes: ByteArray, appBase: BrushParams = BrushParams()): BrushParams =
        fromXml(KppHelper.readPresetXml(bytes, stripEmbeddedResources = true), appBase)

    /**
     * .myb 没有 kpp XML：引擎把它转成 Krita 预设后解出的原生默认值（`ReverieCoreBrush.cpp` info.defaults，
     * 21 项）就是该预设的出厂值。映射口径与上游 f55b4a0e0 选笔的非定制分支一致
     * （大小用 [resolvePresetSize]，间距夹到 0.01..2.5，原生 scatter >= 2 视为无）。
     */
    fun fromNativeDefaults(d: DoubleArray, appBase: BrushParams = BrushParams()): BrushParams {
        if (d.size < 8) {
            BrushDiagnostics.log("native_defaults_short", "size" to d.size)
            return appBase
        }
        fun at(i: Int, dflt: Double) = d.getOrNull(i) ?: dflt
        val size = resolvePresetSize(d[0], null)
        val rawScatter = at(9, 0.0)
        return appBase.copy(
            size = size,
            opacity = d[1].coerceIn(0.0, 1.0),
            flow = d[2].coerceIn(0.0, 1.0),
            spacing = d[3].coerceIn(0.01, 2.5),
            airbrush = d[4] > 0.5,
            airbrushRate = if (d[5] >= 5.0) d[5] else 30.0,
            smudgeRate = d[6],
            colorRate = d[6],
            smudgeLength = d[7],
            angle = at(8, 0.0),
            scatter = if (rawScatter >= 2.0) 0.0 else rawScatter.coerceIn(0.0, 1.0),
            ratio = at(11, 1.0),
            sharpness = at(12, 0.0),
            pressureSize = at(14, 1.0),
            pressureOpacity = at(15, 0.0),
            pressureFlow = at(16, 0.0),
            followDirection = at(17, 0.0) > 0.5,
            randomFlipX = at(18, 0.0) > 0.5,
            randomFlipY = at(19, 0.0) > 0.5,
            antiAliasing = if (at(20, 1.0) > 0.5) 1 else 0,
            maxSizeLimit = maxOf(500.0, size),
            isCustomized = true,
            dynamicsCustomized = true,
            smudgeCustomized = true,
            spacingCustomized = true,
        )
    }

    /** 每个适用控件的回读值（与覆盖逐项比对用） */
    fun controlValues(bytes: ByteArray): Map<ControlId, Any> {
        val xml = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) ?: return emptyMap()
        return PresetPropertyMap.readValues(xml)
    }
}
