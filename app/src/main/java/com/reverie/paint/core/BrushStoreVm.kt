/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.lifecycle.viewModelScope
import com.reverie.paint.core.brushstore.BrushApply
import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.BrushEditSession
import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushOverrideStore
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.ControlClass
import com.reverie.paint.core.brushstore.ControlId
import com.reverie.paint.core.brushstore.EffectivePresetBuilder
import com.reverie.paint.core.brushstore.PresetPropertyMap
import com.reverie.paint.core.brushstore.PresetReadback
import com.reverie.paint.core.brushstore.PresetSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * v2 笔刷存储与 [PaintViewModel] 的接线（S6 编辑与重置统一走 stage / materialize）。
 */
internal object BrushStoreVm {

    fun isEraserPreset(p: BrushPresetInfo?): Boolean =
        p?.group == "橡皮擦" || p?.name?.startsWith("a)_Eraser", ignoreCase = true) == true ||
            p?.name?.contains("Eraser", ignoreCase = true) == true

    private fun presetDir(): File? = BrushStoreBootstrap.presetDir

    /**
     * 引擎扫描磁盘得到的预设名 → id（全工程唯一的 名字→id 步骤，只在 scanNativePresets 里用）。
     * 没有 identity 的文件按内置规则兜底并记日志，不再静默。
     */
    fun idForScanned(fileStem: String): String =
        BrushIdentity.idForName(fileStem) ?: BrushIdentity.builtinId(fileStem).also {
            BrushDiagnostics.log("scan_unregistered", "name" to fileStem)
        }

    /** id → 条目的唯一解析规则（readback 与 hasEffectivePreset 共用）；名字不是查找键 */
    fun resolveEntry(id: String): BrushIdentity.Entry? =
        BrushIdentity.entry(id)
            ?: if (BrushIdentity.isBuiltin(id)) {
                BrushIdentity.Entry(id, id.removePrefix(BrushIdentity.BUILTIN_PREFIX), "kpp", BrushIdentity.Kind.BUILTIN)
            } else null

    fun readback(id: String): Pair<BrushParams, File>? =
        resolveEntry(id)?.let { readback(it) }

    /**
     * 笔画起手用：与 [readback] 是否返回非空等价，但只做 stat，不读、不解析、不 materialize
     *（materialize 在选笔时的 readback 已做）。录制里的 isCustomized/spacingCustomized 由它决定
     *（回读结果恒标这两项为 true，见 PresetReadback.fromXml）。
     */
    fun hasEffectivePreset(id: String, dir: File? = presetDir()): Boolean {
        val e = resolveEntry(id) ?: return false
        return dir != null && EffectivePresetBuilder.targetFile(dir, e).exists()
    }

    fun readback(e: BrushIdentity.Entry): Pair<BrushParams, File>? {
        val dir = presetDir() ?: return null
        val f = EffectivePresetBuilder.targetFile(dir, e)
        if (!f.exists()) EffectivePresetBuilder.materialize(e.id, dir)
        if (!f.exists()) return null
        val base = PresetReadback.withAppControls(PresetReadback.baseline(e.id), BrushOverrideStore.get(e.id).controls)
        val eff = if (e.ext.equals("kpp", ignoreCase = true)) {
            PresetReadback.fromEffectiveBytes(f.readBytes(), base)
        } else {
            // .myb：没有 XML，取引擎对该预设解析出的原生默认值
            val engine = BrushApply.engine
            val idx = (0 until engine.brushPresetCount()).firstOrNull { engine.brushPresetName(it) == e.name }
            if (idx == null) {
                BrushDiagnostics.log("native_index_missing", "id" to e.id, "name" to e.name)
                base
            } else {
                PresetReadback.fromNativeDefaults(engine.brushPresetDefaults(idx), base)
            }
        }
        return eff to f
    }

    // ------------------------------------------------------------ 统一编辑与防抖落盘 (stage / flush)

    interface FlushHost {
        val scope: CoroutineScope
        fun strokeActive(): Boolean
        fun activeId(): String?
        fun reload(id: String)
        fun notifyBrushModified()
    }

    class VmFlushHost(private val vm: PaintViewModel) : FlushHost {
        override val scope: CoroutineScope get() = vm.viewModelScope
        override fun strokeActive(): Boolean = vm.activeStrokeToolOverride != null
        override fun activeId(): String? = vm.brushPresets.firstOrNull { it.index == vm.brushPresetIndex }?.id
        override fun reload(id: String) {
            val p = vm.brushPresets.firstOrNull { it.index == vm.brushPresetIndex }
            if (p != null && p.id == id) {
                vm.runCore(render = false) {
                    BrushApply.select(vm, vm.brushPresetIndex, p)
                }
            }
        }
        override fun notifyBrushModified() = vm.notifyBrushModified()
    }

    private val lock = Any()
    private val persistJobs = HashMap<String, Job>()
    private val pendingControls = HashMap<String, MutableMap<String, Any>>()

    /**
     * 正在进行落盘的 id。暂存表在 materialize **之前**就被取走（其余逻辑要在同一把锁里
     * 完成合并），所以「暂存为空」不等于「已落盘」。调用方（工作台快照/测试/UI）把
     * [hasPending] 当「还有没写完的修改」用，这里必须把 in-flight 一起算上，否则会在
     * 写盘与重载之间读到假的「干净」状态。
     */
    private val inflightFlush = HashSet<String>()

    fun hasPending(id: String): Boolean = synchronized(lock) {
        pendingControls[id]?.isNotEmpty() == true || id in inflightFlush
    }

    fun stage(vm: PaintViewModel, controlId: ControlId, v: Any) {
        val preset = vm.brushPresets.firstOrNull { it.index == vm.brushPresetIndex } ?: return
        stage(preset.id, controlId, v, VmFlushHost(vm))
    }

    fun mergeControl(id: String, controlId: ControlId, value: Any?): BrushOverrideStore.Overrides {
        val entry = resolveEntry(id)
        val cur = BrushOverrideStore.get(id)
        val updatedControls = cur.controls.toMutableMap()
        val updatedDirty = cur.dirty.toMutableSet()

        if (controlId.key in PresetReadback.METADATA_KEYS) {
            if (entry?.kind != BrushIdentity.Kind.BUILTIN) {
                if (value != null) {
                    updatedControls[controlId.key] = value
                } else {
                    updatedControls.remove(controlId.key)
                }
            }
            val res = cur.copy(controls = updatedControls, dirty = updatedDirty)
            BrushOverrideStore.replace(id, res)
            return res
        }

        // .myb 的快照是 JSON，没有 kpp 回读：只有 APP_ONLY 控件进覆盖（与基线比较）；LIVE/REBUILD 控件只在引擎侧本次会话生效
        val isKpp = entry == null || entry.ext.equals("kpp", ignoreCase = true)
        if (!isKpp && controlId.klass != ControlClass.APP_ONLY) return cur
        val params = if (isKpp) PresetReadback.snapshotParams(id) else PresetReadback.baseline(id).let { it to it }
        if (params == null) {
            BrushDiagnostics.log("merge_control_baseline_missing", "id" to id, "control" to controlId.key)
            return cur
        }
        val (baseline, snapP) = params

        val baseVal = if (controlId.klass == ControlClass.APP_ONLY) {
            PresetPropertyMap.spec(controlId).get(baseline)
        } else {
            PresetPropertyMap.spec(controlId).get(snapP)
        }

        val isSame = PresetPropertyMap.same(controlId, baseVal, value)
        if (isSame || value == null) {
            updatedControls.remove(controlId.key)
            updatedDirty.remove(controlId.key)
        } else {
            updatedControls[controlId.key] = BrushOverrideStore.normalize(value)
            updatedDirty += controlId.key
        }

        val res = cur.copy(controls = updatedControls, dirty = updatedDirty)
        BrushOverrideStore.replace(id, res)
        return res
    }

    fun stage(id: String, controlId: ControlId, v: Any, host: FlushHost? = null) {
        if (BrushStoreBootstrap.readOnly) return
        // 大小/不透明度/流量归按工具记忆，不写 overrides（UI 直接 applyLive + rememberToolParamSnapshot）
        if (controlId in PresetReadback.TOOL_MEMORY) return
        val e = resolveEntry(id) ?: return

        when (controlId.klass) {
            ControlClass.LIVE -> {
                BrushApply.applyLive(controlId, v)
                synchronized(lock) {
                    mergeControl(e.id, controlId, v)
                }
                host?.notifyBrushModified()
            }
            ControlClass.APP_ONLY -> {
                if (controlId == ControlId.PRESSURE_ENABLED || controlId == ControlId.PRESSURE_CURVE) {
                    BrushApply.applyLive(controlId, v)
                }
                synchronized(lock) {
                    mergeControl(e.id, controlId, v)
                }
                host?.notifyBrushModified()
            }
            ControlClass.REBUILD -> {
                synchronized(lock) {
                    val map = pendingControls.getOrPut(e.id) { HashMap() }
                    map[controlId.key] = v
                }
                scheduleFlush(e.id, host)
                host?.notifyBrushModified()
            }
        }
    }

    private fun scheduleFlush(id: String, host: FlushHost?, delayMs: Long = 120L) {
        synchronized(lock) {
            persistJobs[id]?.cancel()
            val scope = host?.scope ?: CoroutineScope(Dispatchers.Default)
            val job = scope.launch(Dispatchers.Default) {
                delay(delayMs)
                while (host?.strokeActive() == true) {
                    delay(40L)
                }
                flush(id, host, onlyIfPending = true)
            }
            persistJobs[id] = job
        }
    }

    /**
     * [onlyIfPending]：防抖任务用——暂存已被别处（captureStudio / flushNow）取走并落盘时，不再重复 materialize 与重载。
     * 取走暂存和合并进覆盖在同一把锁里完成，后到的调用要么看到合并后的覆盖，要么什么都不做。
     */
    fun flush(id: String, host: FlushHost? = null, onlyIfPending: Boolean = false): EffectivePresetBuilder.Result? {
        if (BrushStoreBootstrap.readOnly) return null
        val dir = presetDir() ?: return null
        val had = synchronized(lock) {
            persistJobs.remove(id)?.cancel()
            val pending = pendingControls.remove(id)
            pending?.forEach { (k, v) -> ControlId.fromKey(k)?.let { mergeControl(id, it, v) } }
            val hadAny = !pending.isNullOrEmpty()
            if (hadAny) inflightFlush += id
            hadAny
        }
        if (onlyIfPending && !had) return null
        try {
            val res = EffectivePresetBuilder.materialize(id, dir)
            if (host != null) {
                if (host.activeId() == id) {
                    host.reload(id)
                }
                host.notifyBrushModified()
            }
            return res
        } finally {
            synchronized(lock) { inflightFlush -= id }
        }
    }

    /** 作用域被取消（VM 清理）时：已暂存还没写盘的重建类修改补写一次，并丢掉已取消的防抖任务 */
    fun flushAllPending() {
        val ids = synchronized(lock) {
            persistJobs.values.forEach { it.cancel() }
            persistJobs.clear()
            pendingControls.filterValues { it.isNotEmpty() }.keys.toList()
        }
        ids.forEach { flush(it) }
    }

    fun flushNow(id: String, vm: PaintViewModel? = null) {
        if (BrushStoreBootstrap.readOnly) return
        val e = BrushStoreBootstrap.managedEntry(id) ?: return
        flush(e.id, vm?.let { VmFlushHost(it) })
    }

    // ------------------------------------------------------------ 工作台快照 / 回滚（W-05）

    /**
     * 工作台入口快照：覆盖 + 当前工具对这支笔的大小/不透明度/流量记忆（它们不在覆盖里）。
     * [memory] 为 null 表示进入时该工具没有记忆条目（回滚时删除，退回出厂值）。
     */
    data class StudioSnapshot(
        val overrides: BrushOverrideStore.Overrides,
        val toolId: String,
        val memory: List<Double>?,
    )

    fun captureStudio(
        id: String,
        host: FlushHost?,
        states: Map<String, PaintViewModel.ToolBrushState>,
        toolId: String,
    ): StudioSnapshot? {
        val e = BrushStoreBootstrap.managedEntry(id) ?: return null
        flush(e.id, if (hasPending(e.id)) host else null)
        return StudioSnapshot(BrushOverrideStore.get(e.id), toolId, states[toolId]?.paramMemory?.get(e.id))
    }

    fun studioChanged(id: String, initial: StudioSnapshot, states: Map<String, PaintViewModel.ToolBrushState>): Boolean {
        val e = BrushStoreBootstrap.managedEntry(id) ?: return false
        if (states[initial.toolId]?.paramMemory?.get(e.id) != initial.memory) return true
        val cur = BrushOverrideStore.get(e.id)
        val pending = synchronized(lock) { pendingControls[e.id] }
        if (pending != null && pending != initial.overrides.controls) return true
        return cur != initial.overrides
    }

    /** 回滚覆盖并还原记忆；返回新的工具槽映射（调用方赋值并落盘），失败返回 null */
    fun revertStudio(
        id: String,
        initial: StudioSnapshot,
        states: Map<String, PaintViewModel.ToolBrushState>,
    ): Map<String, PaintViewModel.ToolBrushState>? {
        if (BrushStoreBootstrap.readOnly) return null
        val e = BrushStoreBootstrap.managedEntry(id) ?: return null
        val dir = presetDir() ?: return null
        synchronized(lock) {
            persistJobs.remove(e.id)?.cancel()
            pendingControls.remove(e.id)
        }
        BrushOverrideStore.replace(e.id, initial.overrides)
        EffectivePresetBuilder.materialize(e.id, dir)
        BrushDiagnostics.log("studio_revert", "id" to e.id, "controls" to initial.overrides.controls.size)
        val s = states[initial.toolId] ?: return states
        val mem = if (initial.memory != null) s.paramMemory + (e.id to initial.memory) else s.paramMemory - e.id
        return states + (initial.toolId to s.copy(paramMemory = mem))
    }

    // ------------------------------------------------------------ 重置 / 已修改（W-06）

    fun reset(vm: PaintViewModel, id: String): Boolean {
        if (BrushStoreBootstrap.readOnly) return false
        val e = BrushStoreBootstrap.managedEntry(id) ?: return false
        val dir = presetDir() ?: return false
        synchronized(lock) {
            persistJobs.remove(e.id)?.cancel()
            pendingControls.remove(e.id)
        }
        BrushEditSession.reset(e.id, dir) ?: return false
        val pid = e.id
        vm.toolBrushStates = vm.toolBrushStates.mapValues { (_, s) -> s.copy(paramMemory = s.paramMemory - pid) }
        vm.persistToolBrushStates()
        val preset = vm.brushPresets.firstOrNull { it.id == id }
        if (preset != null && preset.index == vm.brushPresetIndex) {
            BrushApply.select(vm, preset.index, preset)
        }
        vm.notifyBrushModified()
        return true
    }

    fun isModified(vm: PaintViewModel, id: String): Boolean? {
        val e = BrushStoreBootstrap.managedEntry(id) ?: return null
        return PresetReadback.hasTuning(BrushOverrideStore.get(e.id).controls) || hasPending(e.id)
    }

    fun resetForTest() {
        synchronized(lock) {
            persistJobs.values.forEach { it.cancel() }
            persistJobs.clear()
            pendingControls.clear()
        }
        PresetReadback.clearForTest()
    }
}
