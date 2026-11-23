/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.*
import com.reverie.paint.core.PaintViewModel.ColorPaletteItem
import com.reverie.paint.core.PaintViewModel.PredictionFidelityTier

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal val PaintViewModel.brushSize: Double get() = brushParams.size

internal val PaintViewModel.brushOpacity: Double get() = brushParams.opacity

internal val PaintViewModel.brushFlow: Double get() = brushParams.flow

internal val PaintViewModel.brushSpacing: Double get() = brushParams.spacing

internal val PaintViewModel.brushAngle: Double get() = brushParams.angle

internal val PaintViewModel.brushScatter: Double get() = brushParams.scatter

internal val PaintViewModel.brushFade: Double get() = brushParams.fade

internal val PaintViewModel.brushSoftness: Double get() = brushParams.softness

internal val PaintViewModel.brushRatio: Double get() = brushParams.ratio

internal val PaintViewModel.brushSharpness: Double get() = brushParams.sharpness

internal val PaintViewModel.brushRotation: Double get() = brushParams.rotation

internal val PaintViewModel.brushCompositeOp: String get() = brushParams.compositeOp

internal val PaintViewModel.brushAntiAliasing: Int get() = brushParams.antiAliasing

internal val PaintViewModel.brushTipShape: Int get() = brushParams.tipShape

internal val PaintViewModel.brushRandomFlipX: Boolean get() = brushParams.randomFlipX

internal val PaintViewModel.brushRandomFlipY: Boolean get() = brushParams.randomFlipY

internal val PaintViewModel.brushFollowDirection: Boolean get() = brushParams.followDirection

internal val PaintViewModel.brushStreamline: Double get() = brushParams.streamline

internal val PaintViewModel.brushTaper: Double get() = brushParams.taper

internal val PaintViewModel.brushTextureEnabled: Boolean get() = brushParams.textureEnabled

internal val PaintViewModel.brushTextureScale: Double get() = brushParams.textureScale

internal val PaintViewModel.brushTextureStrength: Double get() = brushParams.textureStrength

internal val PaintViewModel.brushTextureMode: String get() = brushParams.textureMode

internal val PaintViewModel.brushTexturePattern: String get() = brushParams.texturePattern

internal val PaintViewModel.brushHueJitter: Double get() = brushParams.hueJitter

internal val PaintViewModel.brushSatJitter: Double get() = brushParams.satJitter

internal val PaintViewModel.brushValJitter: Double get() = brushParams.valJitter

internal val PaintViewModel.brushSecondaryMix: Double get() = brushParams.secondaryMix

internal val PaintViewModel.brushPressureColorMix: Boolean get() = brushParams.pressureColorMix

internal val PaintViewModel.brushPressureEnabled: Boolean get() = brushParams.pressureEnabled

internal val PaintViewModel.brushPressureSize: Double get() = brushParams.pressureSize

internal val PaintViewModel.brushPressureOpacity: Double get() = brushParams.pressureOpacity

internal val PaintViewModel.brushPressureFlow: Double get() = brushParams.pressureFlow

internal val PaintViewModel.brushSpeedSize: Double get() = brushParams.speedSize

internal val PaintViewModel.brushPressureCurve: Int get() = brushParams.pressureCurve

internal val PaintViewModel.brushMinSizeLimit: Double get() = brushParams.minSizeLimit

internal val PaintViewModel.brushMaxSizeLimit: Double get() = brushParams.maxSizeLimit

internal val PaintViewModel.brushTipAsset: String get() = brushParams.tipAsset

internal val PaintViewModel.brushPaintOpId: String get() = brushParams.paintOpId

internal val PaintViewModel.brushAirbrush: Boolean get() = brushParams.airbrush

internal val PaintViewModel.brushAirbrushRate: Double get() = brushParams.airbrushRate

internal val PaintViewModel.brushSmudgeRate: Double get() = brushParams.smudgeRate

internal val PaintViewModel.brushSmudgeLength: Double get() = brushParams.smudgeLength

internal val PaintViewModel.brushColorRate: Double get() = brushParams.colorRate

internal val PaintViewModel.brushSmudgeMode: Int get() = brushParams.smudgeMode

internal val PaintViewModel.brushSpikes: Int get() = brushParams.spikes

internal val PaintViewModel.brushJitterAngle: Double get() = brushParams.jitterAngle

internal val PaintViewModel.brushJitterSize: Double get() = brushParams.jitterSize

internal val PaintViewModel.brushMaskingEnabled: Boolean get() = brushParams.maskingEnabled

internal val PaintViewModel.brushMaskingCompositeOp: String get() = brushParams.maskingCompositeOp

internal val PaintViewModel.brushMaskingSizeRatio: Double get() = brushParams.maskingSizeRatio

internal val PaintViewModel.brushMaskingSpacing: Double get() = brushParams.maskingSpacing

internal val PaintViewModel.brushMaskingTipAsset: String get() = brushParams.maskingTipAsset

internal val PaintViewModel.brushMaskingTipShape: Int get() = brushParams.maskingTipShape

internal val PaintViewModel.brushMaskingFade: Double get() = brushParams.maskingFade

internal val PaintViewModel.brushMaskingSoftness: Double get() = brushParams.maskingSoftness

internal val PaintViewModel.brushRotationSensor: String get() = brushParams.rotationSensor

internal val PaintViewModel.brushScatterSensor: String get() = brushParams.scatterSensor

internal val PaintViewModel.brushSizeSensor: String get() = brushParams.sizeSensor

internal val PaintViewModel.brushOpacitySensor: String get() = brushParams.opacitySensor

internal val PaintViewModel.brushFlowSensor: String get() = brushParams.flowSensor

internal val PaintViewModel.brushAuthor: String get() = brushParams.author

internal val PaintViewModel.brushIsAuthorLocked: Boolean get() = brushParams.isAuthorLocked

internal val PaintViewModel.brushDescription: String get() = brushParams.description

internal val PaintViewModel.brushVersion: String get() = brushParams.version

internal val PaintViewModel.effectiveBrushMaxSize: Double
    get() {
        val minL = brushMinSizeLimit.coerceAtLeast(0.5)
        return if (brushSizeScalesWithCanvas) {
            (maxOf(docWidth, docHeight) / 2.0).coerceAtLeast(minL + 0.1)
        } else {
            brushMaxSizeLimit.coerceAtLeast(minL + 0.1)
        }
    }

internal val PaintViewModel.brushPanelGridView: Boolean get() = brushPanelViewMode.isGrid

/** 画布背景是否不透明: 背景层可见且 opacity≈1。保留备用, 不参与 ShiftTrace 门控。 */
internal val PaintViewModel.canvasBackgroundOpaque: Boolean
    get() = layers.firstOrNull { it.isBackground }
        ?.let { it.visible && it.opacity >= 0.999 } ?: false

internal val PaintViewModel.oppoPencilModel: com.reverie.paint.core.stylus.OppoPencilModel
    get() = when (oppoPencilModelMode) {
        "STANDARD" -> com.reverie.paint.core.stylus.OppoPencilModel.STANDARD
        "PRO" -> com.reverie.paint.core.stylus.OppoPencilModel.PRO
        else -> detectedOppoPencilModel
    }

/**
 * 笔迹预测总开关 (APP 超低延迟前向预测)
 */
internal val PaintViewModel.stylusPredictionMasterEnabled: Boolean
    get() = frontBufferPredictionEnabled

internal val PaintViewModel.detectedHuaweiPencilModel: com.reverie.paint.core.stylus.HuaweiPencilModel
    get() = stylusDriver?.detectHuaweiPencilModel() ?: com.reverie.paint.core.stylus.HuaweiPencilModel.GEN2

internal val PaintViewModel.huaweiPencilModel: com.reverie.paint.core.stylus.HuaweiPencilModel
    get() = when (huaweiPencilModelMode) {
        "PRO" -> com.reverie.paint.core.stylus.HuaweiPencilModel.PRO
        "GEN3_NEARLINK" -> com.reverie.paint.core.stylus.HuaweiPencilModel.GEN3_NEARLINK
        "GEN2" -> com.reverie.paint.core.stylus.HuaweiPencilModel.GEN2
        "GEN1" -> com.reverie.paint.core.stylus.HuaweiPencilModel.GEN1
        else -> detectedHuaweiPencilModel
    }

internal val PaintViewModel.detectedHonorPencilModel: com.reverie.paint.core.stylus.HonorPencilModel
    get() = stylusDriver?.detectHonorPencilModel() ?: com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PENCIL_3

internal val PaintViewModel.honorPencilModel: com.reverie.paint.core.stylus.HonorPencilModel
    get() = when (honorPencilModelMode) {
        "MAGIC_PENCIL_3" -> com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PENCIL_3
        "MAGIC_PENCIL_2" -> com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PENCIL_2
        "MAGIC_PEN" -> com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PEN
        else -> detectedHonorPencilModel
    }

internal val PaintViewModel.detectedXiaomiPencilModel: com.reverie.paint.core.stylus.XiaomiPencilModel
    get() = stylusDriver?.detectXiaomiPencilModel() ?: com.reverie.paint.core.stylus.XiaomiPencilModel.SMART_PEN_2

internal val PaintViewModel.xiaomiPencilModel: com.reverie.paint.core.stylus.XiaomiPencilModel
    get() = when (xiaomiPencilModelMode) {
        "FOCUS_PEN_PRO" -> com.reverie.paint.core.stylus.XiaomiPencilModel.FOCUS_PEN_PRO
        "FOCUS_PEN" -> com.reverie.paint.core.stylus.XiaomiPencilModel.FOCUS_PEN
        "SMART_PEN_2" -> com.reverie.paint.core.stylus.XiaomiPencilModel.SMART_PEN_2
        "SMART_PEN_1" -> com.reverie.paint.core.stylus.XiaomiPencilModel.SMART_PEN_1
        else -> detectedXiaomiPencilModel
    }

internal val PaintViewModel.detectedVivoPencilModel: com.reverie.paint.core.stylus.VivoPencilModel
    get() = stylusDriver?.detectVivoPencilModel() ?: com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2

internal val PaintViewModel.vivoPencilModel: com.reverie.paint.core.stylus.VivoPencilModel
    get() = when (vivoPencilModelMode) {
        "VIVO_PENCIL2" -> com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2
        "VIVO_PENCIL2_NV" -> com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2_NV
        "VIVO_PENCIL2S" -> com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2S
        "VIVO_PENCIL3" -> com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL3
        "VIVO_PENCIL1" -> com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL1
        else -> detectedVivoPencilModel
    }

/**
 * 判定当前笔刷的保真度分级 (带 O(1) 状态缓存，防热路径每帧线性扫描)。
 * 遵循 docs/WET-INK-EXPERIMENT.md 历史教训，杜绝图像笔尖替换跳变与软硬边突变：
 * - NONE: 印章/喷溅/特效/网格/双重蒙版/涂抹，以及严重低透明度或极端散布；
 * - TIER_2: 铅笔/速写，或启用材质纹理、喷枪/软笔尖、中低流量笔刷（仅绘制微细笔锋导引，防 Overdraw 加深）；
 * - TIER_1: 基础线稿/勾线/纯色墨水/圆笔/平头 (高保真全量放行，支持完整空窗回填)。
 * - STAMP: 水彩/纹理与排线/绘画类 (真实笔尖戳印，形状颜色与真墨一致)。
 */
internal val PaintViewModel.currentBrushPredictionTier: PredictionFidelityTier
    get() {
        if (!frontBufferPredictionEnabled) return PredictionFidelityTier.NONE
        if (currentToolId != "brush" && currentToolId != "eraser") return PredictionFidelityTier.NONE
        if (currentToolId == "smudge") return PredictionFidelityTier.NONE

        if (cachedPredictionTier != null &&
            cachedTierBrushPresetIndex == brushPresetIndex &&
            cachedTierToolId == currentToolId &&
            cachedTierPredictionEnabled == frontBufferPredictionEnabled &&
            cachedTierTextureEnabled == brushTextureEnabled &&
            cachedTierOpacity == brushOpacity &&
            cachedTierFlow == brushFlow &&
            cachedTierCompositeOp == brushCompositeOp
        ) {
            return cachedPredictionTier!!
        }

        val tier = computeCurrentBrushPredictionTier()
        cachedPredictionTier = tier
        cachedTierBrushPresetIndex = brushPresetIndex
        cachedTierToolId = currentToolId
        cachedTierPredictionEnabled = frontBufferPredictionEnabled
        cachedTierTextureEnabled = brushTextureEnabled
        cachedTierOpacity = brushOpacity
        cachedTierFlow = brushFlow
        cachedTierCompositeOp = brushCompositeOp
        return tier
    }

/**
 * 加权平滑 / 拉绳防抖是否生效。读内存字段, 无 IO, 热路径可直接读。
 * (与 PaintViewModelTools.touchEnd 的 needCatchUp 判定同源)
 */
internal val PaintViewModel.isStrokeSmoothingActive: Boolean
    get() = when (strokeSmoothingType) {
        PaintViewModel.SMOOTHING_OFF -> false
        PaintViewModel.SMOOTHING_WEIGHTED -> (strokeSmoothnessDistanceMin > 0.0 || strokeSmoothnessDistanceMax > 0.0)
        else -> maxOf(strokeStabilizer.toDouble(), brushStreamline) > 0.0
    }

/**
 * 前缓冲预览有效分级 (PR #82 review 问题 4)。
 * 平滑/防抖开启时引擎落墨走平滑后轨迹 (几何内切+滞后), 而预览回填段走原始
 * 物理点 —— TIER_1 全宽假线必与真墨撕裂, 故钳制为 TIER_2 发丝导引线。
 * 注意: 卡尔曼输入保持原始物理点 (预测的是物理笔尖), 只降级回填段渲染,
 * 不碰预测本身。
 */
internal val PaintViewModel.effectivePredictionTier: PredictionFidelityTier
    get() {
        val tier = currentBrushPredictionTier
        return if (tier == PredictionFidelityTier.TIER_1 && isStrokeSmoothingActive) {
            PredictionFidelityTier.TIER_2
        } else {
            tier
        }
    }

/**
 * 上游原版判定: 当前笔刷是否适用于 OEM 硬件前向预测尾线。
 * (前缓冲预测开启时由 [currentBrushPredictionTier] 分级接管, 本判定仅服务上游尾线路径)
 */
internal val PaintViewModel.isCurrentBrushPredictionEligible: Boolean
    get() {
        if (!stylusStrokePredictionEnabled) return false
        if (currentToolId != "brush" && currentToolId != "eraser") return false

        // 1. 混合模式与涂抹检查：非 normal/erase 模式或涂抹不进行矢量假线预测
        if (currentToolId == "smudge") return false
        if (brushCompositeOp != "normal" && brushCompositeOp != "erase" && brushCompositeOp.isNotEmpty()) return false

        // 2. 纹理与颗粒检查：启用了纹理贴图或散布则不进行假线延伸
        if (brushTextureEnabled) return false
        if (brushScatter > 0.01) return false
        if (brushSpacing > 0.25) return false

        // 3. 透明度与流量检查：低不透明度（如淡彩、喷笔罩染等）不拉实体线
        if (brushOpacity < 0.65 || (brushFlow > 0.0 && brushFlow < 0.65)) return false

        // 4. 笔刷预设与分组检查：排除天然带纹理、特殊印章或水彩混合类材质笔刷
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex }
        val grp = preset?.group?.ifEmpty { null } ?: inferBrushGroup(preset?.name ?: "")
        val excludedGroups = setOf("铅笔", "水彩", "混合", "绘画", "纹理与排线", "印章与喷溅", "特效与滤镜", "速写", "形状")
        if (excludedGroups.contains(grp)) return false

        val name = preset?.name ?: ""
        if (name.contains("Pencil", ignoreCase = true) ||
            name.contains("Chalk", ignoreCase = true) ||
            name.contains("Charcoal", ignoreCase = true) ||
            name.contains("Pastel", ignoreCase = true) ||
            name.contains("Bristle", ignoreCase = true) ||
            name.contains("Dry", ignoreCase = true) ||
            name.contains("Texture", ignoreCase = true) ||
            name.contains("Wet", ignoreCase = true) ||
            name.contains("Water", ignoreCase = true) ||
            name.contains("Stamp", ignoreCase = true) ||
            name.contains("Spray", ignoreCase = true) ||
            name.contains("Splat", ignoreCase = true) ||
            name.contains("Sponge", ignoreCase = true) ||
            name.contains("Airbrush", ignoreCase = true) ||
            name.contains("Sketch", ignoreCase = true) ||
            name.contains("Curve", ignoreCase = true) ||
            name.contains("Blender", ignoreCase = true) ||
            name.contains("Smudge", ignoreCase = true) ||
            name.contains("Rake", ignoreCase = true) ||
            name.contains("Hatch", ignoreCase = true) ||
            name.contains("Screentone", ignoreCase = true) ||
            name.contains("Noise", ignoreCase = true) ||
            name.contains("Grain", ignoreCase = true) ||
            name.contains("Blur", ignoreCase = true) ||
            name.contains("Shade", ignoreCase = true) ||
            name.contains("Fuzzy", ignoreCase = true)) {
            return false
        }

        return true
    }

internal val PaintViewModel.currentTextLayerConfig: TypographyConfig?
    get() = editingTextLayerId?.let { id -> activeTextLayers[id]?.find { it.id == editingTextItemId } } ?: typographyConfig

internal val PaintViewModel.allPalettes: List<ColorPaletteItem>
    get() = userPalettes

internal val PaintViewModel.defaultPalette: ColorPaletteItem?
    get() = allPalettes.firstOrNull { it.id == defaultPaletteId } ?: allPalettes.firstOrNull()

/**
 * 画布当前有可上屏的像素: 瓦片网格已发布, 或整图路径已有 [displayBitmap]。
 * 液化这类"必须有画布画面"的功能用它做门 —— 瓦片路径下不一定存在整图位图。
 */
internal val PaintViewModel.hasDisplaySurface: Boolean
    get() = displayTiles.drawable() != null || displayBitmap != null

/** 镜像已实时画进打开的笔触 (预览关闭、抬笔不重放)。只读, 由 [mirrorGate] 决定。 */
internal val PaintViewModel.symmetryMirrorLive: Boolean get() = mirrorGate.isLive

/** s13-C5: 本笔已被撤销/重做/取消中止, 抬笔不得重放镜像 (见 [MirrorLiveGate.abort])。 */
internal val PaintViewModel.mirrorStrokeAborted: Boolean get() = mirrorGate.isAborted

internal val PaintViewModel.lastRenderedFrontierValid: Boolean
    get() = currentRenderedFrontier != null

internal val PaintViewModel.lastRenderedFrontierDocX: Float
    get() = currentRenderedFrontier?.docX ?: Float.NaN

internal val PaintViewModel.lastRenderedFrontierDocY: Float
    get() = currentRenderedFrontier?.docY ?: Float.NaN

internal val PaintViewModel.lastRenderedFrontierTimeMs: Long
    get() = currentRenderedFrontier?.timeMs ?: 0L

/**
 * 估计的"已绘制→已上屏"呈现延迟 (invalidate→onDraw + HWUI/合成尾)。
 * 预览锚点需要它把起点从"引擎已绘制前沿"回退到"屏幕上真实可见的墨迹末端"。
 */
internal val PaintViewModel.presentLagEstimateMs: Long
    get() {
        // 真墨按住期间笔迹由前缓冲呈现, 不再经过主画布的 HWUI/SF 合成。
        if (realInkSink?.wantsHold() == true) return PaintViewModel.FRONT_BUFFER_PRESENT_MS
        val measuredPresent = PerfTrace.presentWaitP50Ms
        return if (measuredPresent in 1L..35L) {
            (measuredPresent + PaintViewModel.PRESENT_TAIL_MS).coerceAtLeast(PaintViewModel.PRESENT_ESTIMATE_MS)
        } else {
            PaintViewModel.PRESENT_ESTIMATE_MS
        }
    }

internal val PaintViewModel.effectivePipelineDelayMs: Long
    get() = (lastE2ePipelineMs + presentLagEstimateMs).coerceIn(4L, 85L)

internal val PaintViewModel.canUndo3D: Boolean
    get() = undo3DStack.isNotEmpty()

internal val PaintViewModel.canRedo3D: Boolean
    get() = redo3DStack.isNotEmpty()

internal val PaintViewModel.currentLayer3DState: com.reverie.paint.model.Model3DState?
    get() = layers.getOrNull(currentLayerIndex)?.let { active3DLayers[it.id] }

internal val PaintViewModel.isCurrentLayer3D: Boolean
    get() = currentLayer3DState != null

internal val PaintViewModel.layerCount: Int get() = layers.size

internal val PaintViewModel.appContextReady: Boolean get() = isAppContextReady()
