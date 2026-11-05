/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.BrushPresetInfo
import com.reverie.paint.core.BrushStoreVm
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ReverieCoreBridge
import com.reverie.paint.core.applySensorXmlToDynamicOptions
import com.reverie.paint.core.fetchBrushOutlinePolygons
import java.io.File
import kotlin.math.abs

/**
 * 引擎侧唯一笔刷状态施加入口（S4：原生分发只走 BrushApply）。
 *
 * 有效 kpp 已经包含了完整的 XML 参数。在 loadBrushPreset 之后，这里只施加
 * "非 preset 状态"：颜色、副色、橡皮标志、橡皮 compositeOp 覆盖、工具级尺寸记忆 paramMemory overlay，
 * 以及附加旋转 liveAngle。严禁调用任何 REBUILD 类 setter。
 */
object BrushApply {

    interface EngineFacade {
        fun loadBrushPreset(index: Int): Boolean
        fun setPresetIsEraser(isEraser: Boolean)
        fun setBrushCompositeOp(op: String)
        fun setBrushColor(color: String)
        fun setBrushSecondaryColor(color: String)
        fun setBrushSize(v: Double)
        fun setBrushOpacity(v: Double)
        fun setBrushFlow(v: Double)
        fun setBrushAngle(deg: Double)
        fun loadBrushPresetFile(path: String): Boolean = true
        fun currentTipResolved(): Boolean = true
        fun loadPatternResources(path: String): Int = 0
        fun setToolMode(mode: Int) {}
        fun beginPresetOverrideSnapshot(): Boolean = true
        fun endPresetOverrideSnapshot(): Boolean = true
        fun brushPresetCount(): Int = ReverieCoreBridge.brushPresetCount()
        fun brushPresetName(index: Int): String = ReverieCoreBridge.brushPresetName(index)
        fun brushPresetDefaults(index: Int): DoubleArray = ReverieCoreBridge.brushPresetDefaults(index)
    }

    object NativeEngine : EngineFacade {
        override fun loadBrushPreset(index: Int): Boolean = ReverieCoreBridge.loadBrushPreset(index)
        override fun loadBrushPresetFile(path: String): Boolean = try {
            ReverieCoreBridge.loadBrushPresetFile(path)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        override fun currentTipResolved(): Boolean = try {
            ReverieCoreBridge.currentTipResolved()
        } catch (_: UnsatisfiedLinkError) {
            true
        }
        override fun loadPatternResources(path: String): Int = ReverieCoreBridge.loadPatternResources(path)
        override fun setPresetIsEraser(isEraser: Boolean) = ReverieCoreBridge.setPresetIsEraser(isEraser)
        override fun setBrushCompositeOp(op: String) = ReverieCoreBridge.setBrushCompositeOp(BrushCompositeIds.canonical(op))
        override fun setBrushColor(color: String) = ReverieCoreBridge.setBrushColor(color)
        override fun setBrushSecondaryColor(color: String) = ReverieCoreBridge.setBrushSecondaryColor(color)
        override fun setBrushSize(v: Double) = ReverieCoreBridge.setBrushSize(v)
        override fun setBrushOpacity(v: Double) = ReverieCoreBridge.setBrushOpacity(v)
        override fun setBrushFlow(v: Double) = ReverieCoreBridge.setBrushFlow(v)
        override fun setBrushAngle(deg: Double) = ReverieCoreBridge.setBrushAngle(deg)
        override fun setToolMode(mode: Int) = ReverieCoreBridge.setToolMode(mode)
        override fun beginPresetOverrideSnapshot(): Boolean = ReverieCoreBridge.beginPresetOverrideSnapshot()
        override fun endPresetOverrideSnapshot(): Boolean = ReverieCoreBridge.endPresetOverrideSnapshot()
        override fun brushPresetCount(): Int = ReverieCoreBridge.brushPresetCount()
        override fun brushPresetName(index: Int): String = ReverieCoreBridge.brushPresetName(index)
        override fun brushPresetDefaults(index: Int): DoubleArray = ReverieCoreBridge.brushPresetDefaults(index)
    }

    /**
     * 回放读的是旧录制格式（CONTEXT_EXT/CONTEXT_FADE 逐笔携带实时形状参数，v1 录制没有预设文件可载），
     * 所以这几个 REBUILD 类 setter 只给回放用，且只在这一处：不进 [EngineFacade]，实时编辑的 applier 看不到它们。
     */
    interface ReplayEngine {
        fun setBrushSoftness(v: Double)
        fun setBrushSpacing(v: Double)
        fun setBrushScatter(v: Double)
        fun setBrushRotation(v: Double)
        fun setBrushRatio(v: Double)
        fun setBrushSharpness(v: Double)
        fun setBrushSmudgeRate(v: Double)
        fun setBrushSmudgeLength(v: Double)
        fun setBrushAirbrush(enabled: Boolean, rate: Double)
        fun setBrushFade(v: Double)
    }

    object NativeReplay : ReplayEngine {
        override fun setBrushSoftness(v: Double) = ReverieCoreBridge.setBrushSoftness(v)
        override fun setBrushSpacing(v: Double) = ReverieCoreBridge.setBrushSpacing(v)
        override fun setBrushScatter(v: Double) = ReverieCoreBridge.setBrushScatter(v)
        override fun setBrushRotation(v: Double) = ReverieCoreBridge.setBrushRotation(v)
        override fun setBrushRatio(v: Double) = ReverieCoreBridge.setBrushRatio(v)
        override fun setBrushSharpness(v: Double) = ReverieCoreBridge.setBrushSharpness(v)
        override fun setBrushSmudgeRate(v: Double) = ReverieCoreBridge.setBrushSmudgeRate(v)
        override fun setBrushSmudgeLength(v: Double) = ReverieCoreBridge.setBrushSmudgeLength(v)
        override fun setBrushAirbrush(enabled: Boolean, rate: Double) = ReverieCoreBridge.setBrushAirbrush(enabled, rate)
        override fun setBrushFade(v: Double) = ReverieCoreBridge.setBrushFade(v)
    }

    var engine: EngineFacade = NativeEngine
    var replayEngine: ReplayEngine = NativeReplay

    fun resetEngineForTest() {
        engine = NativeEngine
        replayEngine = NativeReplay
    }

    /** CONTEXT 事件的实时值：必须在 loadBrushPreset(File) 之后施加，预设加载会重置尺寸/不透明度/流量 */
    fun applyReplayContext(toolMode: Int, size: Double, opacity: Double, flow: Double, compositeOp: String, color: String) {
        engine.setToolMode(toolMode)
        engine.setBrushSize(size)
        engine.setBrushOpacity(opacity)
        engine.setBrushFlow(flow)
        engine.setBrushCompositeOp(compositeOp)
        engine.setBrushColor(color)
    }

    /** CONTEXT_EXT：形状参数只在预设被自定义过（或无预设会话）时施加；定制过但没动间距的预设保留原生间距 */
    fun applyReplayExt(
        preset: Int, overrides: Int,
        softness: Double, spacing: Double, angle: Double, scatter: Double, rotation: Double, ratio: Double, sharpness: Double,
        smudgeRate: Double, smudgeLength: Double, secondaryColor: String, airbrushEnabled: Boolean, airbrushRate: Double,
    ) {
        if (com.reverie.paint.model.RecordedBrushOverrides.applyShape(preset, overrides)) {
            replayEngine.setBrushSoftness(softness)
            if (com.reverie.paint.model.RecordedBrushOverrides.applySpacing(preset, overrides)) replayEngine.setBrushSpacing(spacing)
            engine.setBrushAngle(angle)
            replayEngine.setBrushScatter(scatter)
            replayEngine.setBrushRotation(rotation)
            replayEngine.setBrushRatio(ratio)
            replayEngine.setBrushSharpness(sharpness)
        }
        replayEngine.setBrushSmudgeRate(smudgeRate)
        replayEngine.setBrushSmudgeLength(smudgeLength)
        engine.setBrushSecondaryColor(secondaryColor)
        replayEngine.setBrushAirbrush(airbrushEnabled, airbrushRate)
    }

    fun applyReplayFade(v: Double) = replayEngine.setBrushFade(v)

    data class NonPresetState(
        val isEraser: Boolean,
        val color: String,
        val secondaryColor: String,
        val compositeOverride: String? = null,
        val size: Double? = null,
        val opacity: Double? = null,
        val flow: Double? = null,
        val angleDeg: Double? = null,
    )

    fun applyAfterLoad(engine: EngineFacade, st: NonPresetState) {
        engine.setPresetIsEraser(st.isEraser)
        st.compositeOverride?.let { engine.setBrushCompositeOp(it) }
        engine.setBrushColor(st.color)
        engine.setBrushSecondaryColor(st.secondaryColor)
        st.size?.takeIf { it.isFinite() && it > 0.0 }?.let { engine.setBrushSize(it) }
        st.opacity?.takeIf { it.isFinite() }?.let { engine.setBrushOpacity(it.coerceIn(0.0, 1.0)) }
        st.flow?.takeIf { it.isFinite() }?.let { engine.setBrushFlow(it.coerceIn(0.0, 1.0)) }
        st.angleDeg?.takeIf { it.isFinite() }?.let { engine.setBrushAngle(it) }
    }

    fun liveAngle(eff: BrushParams): Double? {
        if (abs(eff.rotation) < 1e-9) return null
        val a = (eff.angle + eff.rotation) % 360.0
        return if (a < 0.0) a + 360.0 else a
    }

    inline fun <T> override(block: () -> T): T {
        beginOverride()
        try {
            return block()
        } finally {
            endOverride()
        }
    }

    fun beginOverride(): Boolean {
        return engine.beginPresetOverrideSnapshot()
    }

    fun endOverride(): Boolean {
        return engine.endPresetOverrideSnapshot()
    }

    fun beginEraserOverride(eraserPresetIdx: Int, currentPresetIdx: Int, size: Double, opacity: Double, flow: Double) {
        engine.beginPresetOverrideSnapshot()
        if (eraserPresetIdx >= 0 && eraserPresetIdx != currentPresetIdx) {
            engine.loadBrushPreset(eraserPresetIdx)
        }
        engine.setPresetIsEraser(true)
        engine.setBrushCompositeOp("erase")
        engine.setBrushSize(size)
        engine.setBrushOpacity(opacity)
        engine.setBrushFlow(flow)
        engine.setToolMode(1)
    }

    fun applySymmetry(mode: Int, isEraser: Boolean) {
        engine.setToolMode(mode)
        if (isEraser) {
            engine.setPresetIsEraser(true)
            engine.setBrushCompositeOp("erase")
        }
    }

    fun applyToolMode(mode: Int) {
        engine.setToolMode(mode)
    }

    fun applyToolResume(size: Double, opacity: Double, flow: Double) {
        engine.setBrushSize(size)
        engine.setBrushOpacity(opacity)
        engine.setBrushFlow(flow)
    }

    fun applyLive(controlId: ControlId, v: Any?) {
        when (controlId) {
            ControlId.SIZE -> (v as? Number)?.toDouble()?.let { engine.setBrushSize(it) }
            ControlId.OPACITY -> (v as? Number)?.toDouble()?.let { engine.setBrushOpacity(it.coerceIn(0.0, 1.0)) }
            ControlId.FLOW -> (v as? Number)?.toDouble()?.let { engine.setBrushFlow(it.coerceIn(0.0, 1.0)) }
            ControlId.ANGLE -> (v as? Number)?.toDouble()?.let { engine.setBrushAngle(it) }
            ControlId.ROTATION -> (v as? Number)?.toDouble()?.let { engine.setBrushAngle(it) }
            ControlId.PRESSURE_ENABLED -> (v as? Boolean)?.let {
                ReverieCoreBridge.setBrushPressureDynamics(it, 1.0, 1.0, 1.0, 0)
            }
            ControlId.PRESSURE_CURVE -> (v as? Number)?.toInt()?.let {
                ReverieCoreBridge.setBrushPressureDynamics(true, 1.0, 1.0, 1.0, it)
            }
            else -> {}
        }
    }

    /** 笔尖能解析：只对 kpp 有意义；.myb 由引擎转换成 MyPaint 预设，没有位图笔尖可查 */
    private fun tipOk(f: File): Boolean = !f.extension.equals("kpp", ignoreCase = true) || engine.currentTipResolved()

    /** 回放选笔：engine 里的目标序号 + 是否橡皮擦。 */
    data class ReplayTarget(val index: Int, val isEraser: Boolean)

    /**
     * 新录制（选笔事件带 md5 / presetId）按 id 找当前的那支笔，改名不影响；
     * 找不到（已删除）就沿用录制的序号，橡皮擦标志按录制的名字判断，绝不看"序号上现在是谁"。
     * 旧录制（没有 md5）保持原来的"名字 → 序号"。
     */
    fun replayTarget(sel: PlaybackPresetCache.Select, presets: List<BrushPresetInfo>): ReplayTarget {
        if (sel.md5.isNotEmpty()) {
            val byId = if (sel.presetId.isNotEmpty()) presets.firstOrNull { it.id == sel.presetId } else null
            if (byId != null) return ReplayTarget(byId.index, BrushStoreVm.isEraserPreset(byId))
            val ghost = BrushPresetInfo(index = sel.index, id = sel.presetId, name = sel.name, thumbBytes = ByteArray(0))
            return ReplayTarget(sel.index, BrushStoreVm.isEraserPreset(ghost))
        }
        val byName = if (sel.name.isNotEmpty()) presets.firstOrNull { it.name == sel.name } else null
        val index = byName?.index ?: sel.index
        val info = byName ?: presets.firstOrNull { it.index == index }
        val eraser = info != null && BrushStoreVm.isEraserPreset(info)
        return ReplayTarget(index, eraser)
    }

    fun applyReplay(sel: PlaybackPresetCache.Select, isEraser: Boolean = false): PlaybackPresetCache.Resolution {
        val res = PlaybackPresetCache.resolve(sel)
        val root = BrushStoreBootstrap.storeRootDir
        when (res) {
            PlaybackPresetCache.Resolution.EXACT -> {
                val presetDir = BrushStoreBootstrap.presetDir
                val e = BrushStoreBootstrap.managedEntry(sel.presetId)
                val eff = if (e != null && presetDir != null) EffectivePresetBuilder.targetFile(presetDir, e) else null
                var ok = false
                if (eff != null && eff.isFile) {
                    val loaded = engine.loadBrushPresetFile(eff.absolutePath)
                    ok = loaded && tipOk(eff)
                }
                if (!ok) {
                    BrushDiagnostics.log("playback_missing", "id" to sel.presetId, "md5" to sel.md5, "reason" to "exact_load_or_tip_failed")
                    engine.setPresetIsEraser(isEraser)
                    return PlaybackPresetCache.Resolution.MISSING
                }
            }
            PlaybackPresetCache.Resolution.CACHED -> {
                val cached = root?.let { PlaybackPresetCache.findCached(it, sel.md5) }
                var ok = false
                if (cached != null) {
                    val loaded = engine.loadBrushPresetFile(cached.absolutePath)
                    ok = loaded && tipOk(cached)
                }
                if (!ok) {
                    BrushDiagnostics.log("playback_missing", "id" to sel.presetId, "md5" to sel.md5, "reason" to "cached_load_or_tip_failed")
                    engine.setPresetIsEraser(isEraser)
                    return PlaybackPresetCache.Resolution.MISSING
                }
            }
            PlaybackPresetCache.Resolution.MISSING -> {
                // 新录制（有 md5）但录制时的字节找不到：用该笔的快照（原始字节）重建，
                // 之后由 CONTEXT_EXT 的形状值叠加（= 快照 + 覆盖）。旧录制（没有 md5）不走这里，沿用当前笔。
                val rebuilt = if (sel.md5.isNotEmpty()) PlaybackPresetCache.rebuildFromSnapshot(sel.presetId) else null
                if (rebuilt != null && engine.loadBrushPresetFile(rebuilt.absolutePath) && tipOk(rebuilt)) {
                    BrushDiagnostics.log("playback_rebuilt", "id" to sel.presetId, "md5" to sel.md5, "file" to rebuilt.name)
                    engine.setPresetIsEraser(isEraser)
                    return PlaybackPresetCache.Resolution.REBUILT
                }
                BrushDiagnostics.log("playback_missing", "id" to sel.presetId, "md5" to sel.md5, "rebuilt" to (rebuilt != null))
            }
            PlaybackPresetCache.Resolution.REBUILT -> {}
        }
        engine.setPresetIsEraser(isEraser)
        return res
    }

    fun select(vm: PaintViewModel, index: Int, preset: BrushPresetInfo?) {
        if (preset == null) {
            BrushDiagnostics.log("select_preset_missing", "index" to index)
            return
        }
        val id = preset.id
        val read = BrushStoreVm.readback(id)
        val eff = read?.first ?: run {
            BrushDiagnostics.log("select_readback_missing", "id" to id, "fallback" to "baseline")
            PresetReadback.baseline(id)
        }
        val isEraserPreset = BrushStoreVm.isEraserPreset(preset)
        val nativeCompOp = if (index >= 0) ReverieCoreBridge.brushPresetCompositeOp(index) else "normal"
        val effectiveCompOp = if (vm.currentToolId == "eraser" || isEraserPreset) {
            "erase"
        } else {
            if (eff.compositeOp.isNotBlank() && eff.compositeOp != "erase") eff.compositeOp else nativeCompOp
        }
        // 大小/不透明度/流量只由按工具记忆决定；没有记忆就是快照里的出厂值（overrides 不再保存它们）
        val mem = vm.toolBrushStates[vm.currentToolId]?.paramMemory?.get(id)
        val finalSize = mem?.getOrNull(0) ?: eff.size
        val finalOpacity = mem?.getOrNull(1) ?: eff.opacity
        val finalFlow = mem?.getOrNull(2) ?: eff.flow

        vm.runCore(after = {
            // 唯一所有者 brushParams 一次整体换成有效值；大小/不透明度/流量取按工具记忆，合成模式取有效模式，
            // 绘制算子缺省时取原生预设的，内置笔固定署名 Krita。
            vm.brushParams = eff.copy(
                size = finalSize,
                opacity = finalOpacity,
                flow = finalFlow,
                compositeOp = effectiveCompOp,
                paintOpId = if (eff.paintOpId.isBlank() || eff.paintOpId == "defaultpaintop") {
                    if (index >= 0) ReverieCoreBridge.brushPresetPaintOpId(index) else "paintbrush"
                } else {
                    eff.paintOpId
                },
                author = if (preset?.isBuiltIn == true) "Krita" else eff.author,
                isAuthorLocked = if (preset?.isBuiltIn == true) true else eff.isAuthorLocked,
            )
            // 曲线 UI 只从有效 kpp 回读值灌入：不清掉就会沿用上一支笔的曲线
            vm.applySensorXmlToDynamicOptions(eff.dynamicOptions)
            vm.checkBrushSizeLimit()
            vm.fetchBrushOutlinePolygons()
        }) {
            val loaded = engine.loadBrushPreset(index)
            if (loaded) {
                com.reverie.paint.core.Breadcrumbs.record("Brush", "Load preset [$index]: ${preset?.name}")
            }
            // 预设加载失败也**必须**把"是不是橡皮 / 混合模式"这两个引擎状态推下去:
            // 引擎里"预设是橡皮"是个显式覆盖位 (setPresetIsEraser), 加载失败时若整段
            // 跳过, 它会保留上一支笔(常常是橡皮)的值 —— 用户看到的就是"切回自定义画笔
            // 却还在擦除"。加载成功与否只影响预设内部参数, 不影响这两项的归属。
            val nonPreset = NonPresetState(
                isEraser = vm.currentToolId == "eraser" || isEraserPreset,
                color = vm.brushColor,
                secondaryColor = vm.brushSecondaryColor,
                compositeOverride = effectiveCompOp,
                size = finalSize,
                opacity = finalOpacity,
                flow = finalFlow,
                angleDeg = liveAngle(eff),
            )
            applyAfterLoad(engine, nonPreset)
        }
    }

    fun restore(vm: PaintViewModel, reloadPreset: Boolean = false) {
        val saved = vm.strokeSaved
        val origPreset = saved.presetIndex
        val origSize = saved.size
        val origOpacity = saved.opacity
        val origFlow = saved.flow
        val origOp = saved.compositeOp
        val origMode = saved.toolMode
        val origIsEraser = (vm.currentToolId == "eraser")

        val preset = if (reloadPreset && origPreset >= 0) vm.brushPresets.firstOrNull { it.index == origPreset } else null
        if (reloadPreset && origPreset >= 0 && preset == null) {
            BrushDiagnostics.log("restore_preset_missing", "index" to origPreset)
        }
        if (preset != null) {
            val isEraserPreset = BrushStoreVm.isEraserPreset(preset)
            val id = preset.id
            val eff = BrushStoreVm.readback(id)?.first ?: run {
                BrushDiagnostics.log("restore_readback_missing", "id" to id, "fallback" to "baseline")
                PresetReadback.baseline(id)
            }
            val nativeCompOp = ReverieCoreBridge.brushPresetCompositeOp(origPreset)
            val effectiveCompOp = if (origIsEraser || isEraserPreset) {
                "erase"
            } else {
                if (eff.compositeOp.isNotBlank() && eff.compositeOp != "erase") eff.compositeOp else nativeCompOp
            }

            if (engine.loadBrushPreset(origPreset)) {
                com.reverie.paint.core.Breadcrumbs.record("Brush", "Restore preset [$origPreset]: ${preset.name}")
                val nonPreset = NonPresetState(
                    isEraser = origIsEraser || isEraserPreset,
                    color = vm.brushColor,
                    secondaryColor = vm.brushSecondaryColor,
                    compositeOverride = effectiveCompOp,
                    size = origSize,
                    opacity = origOpacity,
                    flow = origFlow,
                    angleDeg = liveAngle(eff),
                )
                applyAfterLoad(engine, nonPreset)
            }
            engine.setToolMode(origMode)
            vm.fetchBrushOutlinePolygons()
        } else {
            engine.setPresetIsEraser(origIsEraser)
            engine.setBrushCompositeOp(origOp)
            engine.setBrushSize(origSize)
            engine.setBrushOpacity(origOpacity)
            engine.setBrushFlow(origFlow)
            engine.setToolMode(origMode)
        }
    }

    fun restoreFromUi(vm: PaintViewModel) {
        val mode = when (vm.currentToolId) {
            "brush" -> 0
            "eraser" -> 1
            "smudge" -> 3
            else -> -1
        }
        if (mode >= 0) {
            engine.setToolMode(mode)
        }
        if (vm.brushPresetIndex >= 0) {
            engine.loadBrushPreset(vm.brushPresetIndex)
            val p = vm.brushPresets.firstOrNull { it.index == vm.brushPresetIndex }
            engine.setPresetIsEraser(BrushStoreVm.isEraserPreset(p))
            engine.setBrushSize(vm.brushSize)
            engine.setBrushOpacity(vm.brushOpacity)
            engine.setBrushFlow(vm.brushFlow)
        }
        engine.setBrushCompositeOp(vm.brushCompositeOp)
        engine.setBrushColor(vm.brushColor)
    }
}
