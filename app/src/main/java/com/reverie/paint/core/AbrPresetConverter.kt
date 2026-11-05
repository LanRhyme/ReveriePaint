/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.AbrSnapshotBuilder
import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.KppSensors
import com.reverie.paint.core.brushstore.ResourceStore
import java.util.Locale
import org.json.JSONObject

/**
 * ABR 单个预设 → kpp 的转换（W-09 从 importSingleBrushInternal 原样抽出，行为不变）。
 *
 * 转换结果的 kpp 字节就是 ABR 预设的快照（重置 = 回到这份字节）；[abrParamsJson] 把 AbrParser 的原始结构
 * 和 [CONVERTER_VERSION] 一起写进 source.json，仅作快照标签与诊断（转换器只有当前这一套规则，无版本分支，也没有重新转换入口）。
 */
internal object AbrPresetConverter {
    /** 快照标签：转换逻辑有改动（会改变产出字节）时 +1；不参与任何分支 */
    const val CONVERTER_VERSION = 10

    data class Converted(val bp: BrushParams, val kppBytes: ByteArray, val tipScale: Double?)

    fun convert(
        preset: AbrParser.AbrPresetInfo,
        candidateName: String,
        matchedTip: AbrParser.AbrDecodedTip?,
        matchedTipFileName: String,
        tipUuidToFileName: Map<String, String>,
        packBaseName: String,
        patternRefs: Map<String, ResourceStore.Ref> = emptyMap(),
        policy: AbrBlendModes.Policy = AbrBlendModes.Policy.COMPAT_SAFE,
    ): Converted {
        val previewBytes = AbrParser.encodePreviewPng(
            tip = matchedTip,
            diameter = preset.diameter,
            roundness = preset.roundness,
        )

        // 映射倾斜与动力学传感器
        val sizeSensor = when {
            preset.tiltSize -> "declination"
            preset.sizeJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val opacitySensor = when {
            preset.tiltOpacity -> "declination"
            preset.opacityJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val flowSensor = when {
            preset.tiltFlow -> "declination"
            preset.flowJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val rotationSensor = when {
            preset.followDirection -> "drawingangle"
            preset.tiltAngle -> "ascension"
            preset.angleJitter > 0.05 -> "fuzzy"
            else -> "drawingangle"
        }

        val dynamicOptions = mutableMapOf<String, String>()
        // sizeJitter 抖动曲线下限 f = max(1 − sizeJitter, minimumDiameter)（HC02 0.25/0.10 → 0.750）；
        // 其余分支（压感/倾斜）用 minimumDiameter 作下限
        val sizeCurve = if (!preset.tiltSize && preset.sizeJitter > 0.05) {
            jitterFloorCurve(preset.sizeJitter, preset.minDiameterRatio)
        } else {
            sizeCurveWithFloor(preset.minDiameterRatio)
        }
        if (preset.tiltSize) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="declination"><curve>$sizeCurve</curve></params>"""
        } else if (preset.sizeJitter > 0.05) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="fuzzy"><curve>$sizeCurve</curve></params>"""
        } else if (preset.pressureSize) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="pressure"><curve>$sizeCurve</curve></params>"""
        }
        // 流量压感镜像：部分计算圆笔刷（如 #2 硬边圆压力不透明度）原生配置仅开启流量压感 prVr 而无 opVr，
        // 在 COMPAT_SAFE 模式下将流量压感等效镜像至不透明度，确保半透明叠色效果
        val effectivePressureOpacity = preset.pressureOpacity ||
            (policy == AbrBlendModes.Policy.COMPAT_SAFE && preset.isComputed && preset.pressureFlow)

        // 压感分支：Photoshop 传递面板的 Minimum（压感 0 时的不透明度/流量）→ 曲线起点 `0,min;1,1`（同 sizeCurveWithFloor 的下限做法）；
        // 抖动分支仍用 1−jitter 下限，取两者较大者，永不低于 PS 的 minimum
        // COMPAT_SAFE 模式下，针对喷枪或超小间距压感笔刷，设置 0.12 的保底起笔透明度，防止轻触屏幕时无墨
        val safeOpacityMin = if (policy == AbrBlendModes.Policy.COMPAT_SAFE && (preset.airbrush || preset.spacing <= 0.02) && effectivePressureOpacity) {
            maxOf(preset.opacityMin, 0.12)
        } else {
            preset.opacityMin
        }
        val opacityCurve = transferCurve(preset.opacityJitter, safeOpacityMin)
        val flowCurve = transferCurve(preset.flowJitter, preset.flowMin)
        if (preset.tiltOpacity) {
            dynamicOptions["Opacity"] = sensorParams("declination", opacityCurve, forceEnabled = false)
        } else if (preset.opacityJitter > 0.05) {
            dynamicOptions["Opacity"] = sensorParams(
                "fuzzy",
                opacityCurve,
                forceEnabled = true,
            )
        } else if (effectivePressureOpacity) {
            dynamicOptions["Opacity"] = sensorParams("pressure", opacityCurve, forceEnabled = true)
        }
        if (preset.tiltFlow) {
            dynamicOptions["Flow"] = sensorParams("declination", flowCurve, forceEnabled = false)
        } else if (preset.flowJitter > 0.05) {
            dynamicOptions["Flow"] = sensorParams(
                "fuzzy",
                flowCurve,
                forceEnabled = true,
            )
        } else if (preset.pressureFlow) {
            dynamicOptions["Flow"] = sensorParams("pressure", flowCurve, forceEnabled = true)
        }
        if (preset.followDirection) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params fanCornersStep="30" fanCornersEnabled="0" angleOffset="0" id="drawingangle"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.tiltAngle) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params id="ascension"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.angleJitter > 0.05) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (preset.scatter > 0.001) {
            // scatterDynamics 控制=钢笔压力：散布量随压力放大（KisScatterOption 的抖动幅度×传感器值），在 fuzzy 之外再叠 pressure
            dynamicOptions["Scatter"] = if (preset.scatterPressure) {
                KppSensors.addSensor(KppSensors.single("fuzzy"), "pressure")
            } else """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        // 圆度控制为压感/倾斜时，minimumRoundness 是 Ratio 曲线下限（压感 0 → minimum，满压 → 笔尖圆度）
        if (preset.roundnessJitter <= 0.05 && (preset.pressureRoundness || preset.tiltRoundness)) {
            dynamicOptions["Ratio"] = sensorParams(
                if (preset.tiltRoundness) "declination" else "pressure",
                sizeCurveWithFloor(preset.minRoundness),
                forceEnabled = false,
            )
        }
        // Ratio 抖动曲线下限 f = max(1 − jitter, minimumRoundness)：抖动在 [f,1] 内随机，
        // 永不低于 Photoshop 的 minimum；只有真有 roundnessJitter 才写 Ratio
        if (preset.roundnessJitter > 0.05) {
            val floor = maxOf(1.0 - preset.roundnessJitter, preset.minRoundness).coerceIn(0.0, 1.0)
            if (floor < 0.999) {
                dynamicOptions["Ratio"] = sensorParams(
                    "fuzzy",
                    jitterFloorCurve(preset.roundnessJitter, preset.minRoundness),
                    forceEnabled = true,
                )
            }
        }

        // 映射双重画笔 (Dual Brush -> MaskingBrush)
        val dual = preset.dualBrush
        val hasDual = dual != null
        val dualTipFileName = dual?.tipUuid?.let { tipUuidToFileName[it] } ?: ""
        val maskingSizeRatio = if (hasDual && preset.diameter > 0) {
            (dual!!.diameter / preset.diameter).coerceIn(0.05, 5.0)
        } else 1.0

        // 映射纹理贴图 (Texture)：只认内容寻址 Ref（无 Ref 即无纹理，不回落显示名）
        val tex = preset.texture
        val resolvedRef = resolvePatternRef(tex, patternRefs)
        val textureApplied = tex != null && resolvedRef != null
        val hasTexture = textureApplied

        // 映射色彩动力学 (Color Dynamics)
        val cd = preset.colorDynamics

        val hasPressureDynamics = preset.pressureSize || preset.pressureOpacity || preset.pressureFlow ||
            preset.tiltSize || preset.tiltOpacity || preset.tiltFlow || preset.tiltAngle
        val hasDynamics = hasPressureDynamics || preset.scatter > 0.001 || preset.followDirection ||
            preset.flipX || preset.flipY || preset.flipXJitter || preset.flipYJitter || preset.sizeJitter > 0.001 || preset.angleJitter > 0.001 ||
            preset.opacityJitter > 0.001 || preset.flowJitter > 0.001 || dynamicOptions.isNotEmpty() ||
            hasDual || hasTexture || cd != null

        if (dual != null && (dual.roundness < 0.99 || dual.count > 1)) {
            // masking brush 每个主 dab 只盖一枚 mask：双重画笔圆度 / 散布数量无对应项，保留圆形单枚并记录
            BrushDiagnostics.log(
                "abr_dual_unsupported", "preset" to preset.name, "roundness" to dual.roundness, "count" to dual.count,
            )
        }

        if (preset.angleControl == 1 || preset.angleControl == 4 || preset.angleControl == 5) {
            // PS 角度控制：渐隐 / 手写笔滚轮 / 笔身旋转——本应用不上报，且无对应传感器；保持静态角度
            BrushDiagnostics.log("abr_angle_control_unmapped", "preset" to preset.name, "control" to preset.angleControl)
        }

        // 映射原生工具类型（Tool Preset）：严格基于 ActionDescriptor 的 toolOptions.classId 识别
        // "SmTl" -> Photoshop Smudge Tool（涂抹工具）
        // "MixB" -> Photoshop Mixer Brush（混合器画笔）
        // 其余默认为 "paintbrush" 传统画笔
        val isSmudge = preset.toolClass == "SmTl"
        val isMixer = preset.toolClass == "MixB"
        val isSmudgeFamily = isSmudge || isMixer
        val targetPaintOpId = if (isSmudgeFamily) "colorsmudge" else "paintbrush"
        val smudgeLength = when {
            isSmudge -> preset.smudgeStrength.coerceIn(0.1, 1.0)
            isMixer -> if (preset.wetness > 0.0) preset.wetness.coerceIn(0.1, 1.0) else 0.5
            else -> 0.0
        }
        val colorRate = when {
            isSmudge -> 0.0
            isMixer -> if (preset.mix > 0.0) preset.mix.coerceIn(0.0, 1.0) else (1.0 - preset.dryness).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val smudgeMode = if (isMixer) 1 else 0

        if (isSmudgeFamily && preset.pressureFlow) {
            dynamicOptions["SmudgeRate"] = sensorParams("pressure", flowCurve, forceEnabled = true)
        }

        // COMPAT_SAFE 下双重画笔 linear_burn 回退至 burn，避免蒙版下溢吃光墨水；纹理减去在 100% 深度时保留 0.85 留底
        val dualCompositeOp = if (dual != null) {
            if (policy == AbrBlendModes.Policy.COMPAT_SAFE && (dual.compositeOp == "linear_burn" || dual.compositeOp == "burn" || dual.rawMode.equals("linearBurn", ignoreCase = true) || dual.rawMode.equals("CBrn", ignoreCase = true))) {
                "multiply"
            } else {
                dual.compositeOp
            }
        } else "multiply"

        val effectiveTexMode = if (tex != null) {
            if (policy == AbrBlendModes.Policy.COMPAT_SAFE && (tex.mode == "linear_burn" || tex.rawMode.equals("linearBurn", ignoreCase = true))) {
                "color_burn"
            } else {
                tex.mode
            }
        } else "multiply"

        // 减法纹理防吞噬：当预设同时具备双重画笔与减法纹理时，主笔尖 Alpha 经双重正片叠底后已降至 0.05~0.15，
        // Krita 减法公式 max(0, dst - src * depth) 在 depth > 0.10 时会扣光残留 Alpha 造成完全不出墨。
        // 在 COMPAT_SAFE 下，将有双重画笔时的减法纹理深度锁定在 0.08，既保留纹理雕刻感又确保炭墨丰盈。
        val isSubtractTex = effectiveTexMode == "subtract" || tex?.rawMode.equals("Sbtr", ignoreCase = true)
        val effectiveTexDepth = if (tex != null) {
            if (policy == AbrBlendModes.Policy.COMPAT_SAFE && isSubtractTex && (tex.depth >= 0.99 || hasDual)) {
                if (hasDual) 0.08 else 0.30
            } else {
                tex.depth
            }
        } else 0.5

        val effectiveTexDepthMin = if (tex?.depthPressure == true) {
            if (policy == AbrBlendModes.Policy.COMPAT_SAFE && hasDual && isSubtractTex) {
                minOf(tex.depthMin, 0.08)
            } else {
                tex.depthMin
            }
        } else -1.0

        // 物理间距防饱和校准：计算圆笔刷在 1% 极小间距且无散布时（如 19号画笔），
        // Krita 逐印章 Over 叠加导致 1 像素内累积 100 次印章瞬间饱和至纯黑死硬边缘。
        // COMPAT_SAFE 模式下平滑校准至 0.06（对齐 Krita 官方 Basic-5 间距标准），消除累积死黑并降低 83% 渲染负荷。
        val safeSpacing = if (policy == AbrBlendModes.Policy.COMPAT_SAFE && preset.isComputed && preset.spacing <= 0.02 && preset.scatter <= 0.001) {
            0.06
        } else {
            preset.spacing
        }

        val effectiveSpacing = if (preset.scatterCount > 1 && preset.scatter > 0.001) {
            (safeSpacing / preset.scatterCount).coerceIn(0.01, 5.0).also {
                BrushDiagnostics.log(
                    "abr_count_approx", "preset" to preset.name, "count" to preset.scatterCount,
                    "spacing" to preset.spacing, "to" to it,
                )
            }
        } else safeSpacing

        val bp = BrushParams(
            size = preset.diameter,
            opacity = preset.toolOpacity,
            flow = preset.toolFlow,
            // 计算笔（无采样笔尖）遵循 ABR 原始硬度作为羽化；
            // 仅当声明了纹理但未能绑定（未匹配到资产）且无双重画笔时，保持 FADE_SOLID 避免退化成全羽化糊团（HC02 特例）
            fade = if (matchedTipFileName.isEmpty()) {
                if (tex != null && !textureApplied && !hasDual) {
                    KppHelper.FADE_SOLID
                } else {
                    preset.hardness.coerceIn(0.0, 1.0)
                }
            } else {
                KppHelper.FADE_SOLID
            },
            // 散布「数量」Cnt：PS 每个间距步长盖 Cnt 个印章（各自散布）；Krita 画笔没有 Count 选项，
            // 用 间距/Cnt 近似同样的沿线密度（Krita 间距下限 0.01）。【近似：Cnt 的抖动/控制未映射】
            spacing = effectiveSpacing,
            angle = preset.angle,
            scatter = preset.scatter,
            scatterBothAxes = preset.scatterBothAxes,
            ratio = preset.roundness,
            followDirection = preset.followDirection,
            // 形状动态的 Flip X/Y Jitter（顶层 flipX/flipY）→ Krita 随机镜像（KisMirrorOption fuzzy）；
            // 笔尖静态翻转（Brsh.flipX/flipY）不再冒充随机翻转
            randomFlipX = preset.flipXJitter,
            randomFlipY = preset.flipYJitter,
            pressureEnabled = hasPressureDynamics,
            pressureSize = if (preset.pressureSize || preset.tiltSize) 1.0 else 0.0,
            pressureOpacity = if (effectivePressureOpacity || preset.tiltOpacity) 1.0 else 0.0,
            pressureFlow = if (preset.pressureFlow || preset.tiltFlow) 1.0 else 0.0,
            sizeSensor = sizeSensor,
            opacitySensor = opacitySensor,
            flowSensor = flowSensor,
            rotationSensor = rotationSensor,
            scatterSensor = "fuzzy",
            // 抖动幅度走上面的 fuzzy 曲线（下限曲线），不走表里的 jitter 控件：
            // 引擎 sizeLikeValue = constant × scaling（KisCurveOption.cpp），把幅度写进 SizeValue 会变成 [0, j] 而不是 [1−j, 1]
            dynamicOptions = dynamicOptions,
            maskingEnabled = hasDual,
            maskingCompositeOp = dualCompositeOp,
            maskingSizeRatio = maskingSizeRatio,
            maskingSpacing = dual?.spacing ?: 0.1,
            maskingTipAsset = dualTipFileName,
            maskingScatter = if (hasDual) dual!!.scatter else 0.0,
            maskingScatterBothAxes = dual?.scatterBothAxes ?: true,
            maskingAngle = dual?.angle ?: 0.0,
            maskingFlipX = dual?.flipX == true,
            maskingFlipY = dual?.flipY == true,
            textureEnabled = textureApplied,
            textureScale = tex?.scale ?: 1.0,
            textureStrength = effectiveTexDepth,
            textureStrengthPressureMin = effectiveTexDepthMin,
            textureMode = effectiveTexMode,
            texturePattern = if (textureApplied) resolvedRef!!.file else "",
            textureInvert = tex?.invert == true,
            // Krita 的 SUBTRACT 非软模式是 dst-(src+inv(strength))，深度一低就几乎不剩纹理；
            // 软纹理 dst-mul(src,strength) 才是 PS 深度的语义（KisMaskingBrushCompositeOp.h）。
            textureSoft = textureApplied,
            // Krita 在 mask 上做 (v-brightness)、再以 0.5 为中心乘 contrast（KisTextureMaskInfo.cpp:192-194）；
            // PS 亮度 +=更亮 对应 Krita 负号，除以 255 取 8 位灰度偏移；对比度 -50..100 对应倍率 0.5..2。【推测：PS 精确曲线未验证】
            textureBrightness = if (textureApplied) -(tex!!.brightness / 255.0) else 0.0,
            textureContrast = if (textureApplied) (1.0 + tex!!.contrast / 100.0).coerceIn(0.0, 2.0) else 1.0,
            hueJitter = cd?.hueJitter ?: 0.0,
            satJitter = cd?.satJitter ?: 0.0,
            valJitter = cd?.valJitter ?: 0.0,
            secondaryMix = cd?.secondaryMix ?: 0.0,
            airbrush = preset.airbrush,
            tipAsset = matchedTipFileName,
            paintOpId = targetPaintOpId,
            smudgeRate = smudgeLength,
            smudgeLength = smudgeLength,
            colorRate = colorRate,
            smudgeMode = smudgeMode,
            compositeOp = preset.compositeOp,
            author = "外部创作者 (ABR)",
            isAuthorLocked = true,
            description = "导入自 Photoshop ABR 笔刷包: $packBaseName",
            isCustomized = true,
            dynamicsCustomized = hasDynamics || isSmudgeFamily,
            smudgeCustomized = isSmudgeFamily,
        )

        // File-backed tips report `max(tipW, tipH) * scale` as the brush size
        // (KisScalingSizeBrush::userEffectiveSize), so `scale` is the only way to
        // make that number match the diameter the ABR declares. Without this the
        // size silently becomes the raw tip pixel size, and a preset declaring
        // diameter=80 shows up as 282 for a 282x282 tip.
        val tipScale = if (matchedTip != null && preset.diameter > 0.0 &&
            matchedTip.width > 0 && matchedTip.height > 0
        ) {
            (preset.diameter / maxOf(matchedTip.width, matchedTip.height))
                .coerceIn(0.01, 8.0)
        } else {
            null
        }
        // 快照纹理绑定到内容寻址 Ref（写 PatternMD5Sum 四键）
        val kppBytes = AbrSnapshotBuilder.build(
            previewBytes, candidateName, bp, tipScale,
            if (textureApplied) resolvedRef else null,
        )
        return Converted(bp, kppBytes, tipScale)
    }

    /** 图案按内容寻址注册；key 只用 uuid（空 uuid 跳过），单个失败跳过并记日志。preferredName 与 VM 旧实现一致。 */
    fun registerPatterns(
        patterns: List<AbrParser.AbrPatternResource>,
        store: ResourceStore,
        groupPrefix: String,
    ): Map<String, ResourceStore.Ref> {
        val refs = LinkedHashMap<String, ResourceStore.Ref>()
        patterns.forEachIndexed { index, pattern ->
            try {
                val token = pattern.uuid.ifBlank { index.toString() }
                    .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                    .ifBlank { index.toString() }
                val ref = store.register(pattern.pngBytes, ResourceStore.Kind.PATTERN, "abr_pat_${groupPrefix}_$token.png")
                if (pattern.uuid.isNotEmpty()) refs[pattern.uuid] = ref
            } catch (t: Throwable) {
                BrushDiagnostics.log("abr_pattern_register_failed", "uuid" to pattern.uuid, "err" to t.javaClass.simpleName)
            }
        }
        return refs
    }

    /** 不透明度/流量抖动是在满值 1.0 上向下波动，不把笔刷本身写成抖动数字。 */
    private fun transferCurve(jitter: Double, minimum: Double = 0.0): String {
        val floor = (if (jitter <= 0.05) minimum else maxOf(1.0 - jitter, minimum)).coerceIn(0.0, 1.0)
        if (floor <= 0.0) return "0,0;1,1;"
        return "0,${String.format(Locale.US, "%.3f", floor)};1,1;"
    }

    /** 抖动曲线下限 f = max(1 − jitter, minimum)：抖动在 [f,1] 内随机，永不低于 Photoshop 的 minimum。 */
    private fun jitterFloorCurve(jitter: Double, minimum: Double): String {
        val floor = maxOf(1.0 - jitter, minimum).coerceIn(0.0, 1.0)
        return "0,${String.format(Locale.US, "%.3f", floor)};1,1;"
    }

    private fun sensorParams(id: String, curve: String, forceEnabled: Boolean): String {
        val enabled = if (forceEnabled) """ enabled="true"""" else ""
        return """<!DOCTYPE params><params id="$id"$enabled><curve>$curve</curve></params>"""
    }

    /**
     * 最小直径是尺寸曲线的下限，叠在当前传感器上。
     * 下限为 0 时保持 `0,0;1,1;`。
     */
    private fun sizeCurveWithFloor(minDiameterRatio: Double): String {
        val floor = minDiameterRatio.coerceIn(0.0, 0.95)
        if (floor <= 0.0) return "0,0;1,1;"
        val y0 = String.format(Locale.US, "%.3f", floor)
        return "0,$y0;1,1;"
    }

    /** 只按 uuid 查内容寻址 Ref；没有名字或显示名回落。 */
    private fun resolvePatternRef(
        tex: AbrParser.AbrTextureInfo?,
        patternRefs: Map<String, ResourceStore.Ref>,
    ): ResourceStore.Ref? {
        if (tex == null || tex.patternUuid.isEmpty()) return null
        return patternRefs[tex.patternUuid]
    }

    /** AbrParser 原始结构（显式键名，不依赖反射——release 包会混淆字段名） */
    fun abrParamsJson(p: AbrParser.AbrPresetInfo): JSONObject {
        val o = JSONObject()
            .put("name", p.name).put("tipUuid", p.tipUuid ?: JSONObject.NULL).put("tipIndex", p.tipIndex)
            .put("diameter", p.diameter).put("spacing", p.spacing).put("angle", p.angle)
            .put("roundness", p.roundness).put("hardness", p.hardness).put("scatter", p.scatter).put("scatterBothAxes", p.scatterBothAxes)
            .put("toolOpacity", p.toolOpacity).put("toolFlow", p.toolFlow)
            .put("pressureRoundness", p.pressureRoundness).put("tiltRoundness", p.tiltRoundness)
            .put("pressureSize", p.pressureSize).put("pressureOpacity", p.pressureOpacity).put("pressureFlow", p.pressureFlow)
            .put("tiltSize", p.tiltSize).put("tiltOpacity", p.tiltOpacity).put("tiltFlow", p.tiltFlow).put("tiltAngle", p.tiltAngle)
            .put("sizeJitter", p.sizeJitter).put("angleJitter", p.angleJitter).put("roundnessJitter", p.roundnessJitter)
            .put("opacityJitter", p.opacityJitter).put("flowJitter", p.flowJitter)
            .put("minDiameterRatio", p.minDiameterRatio).put("minRoundness", p.minRoundness)
            .put("followDirection", p.followDirection).put("flipX", p.flipX).put("flipY", p.flipY)
            .put("isComputed", p.isComputed).put("airbrush", p.airbrush).put("compositeOp", p.compositeOp)
        p.dualBrush?.let { d ->
            o.put(
                "dualBrush",
                JSONObject().put("tipUuid", d.tipUuid ?: JSONObject.NULL).put("diameter", d.diameter).put("spacing", d.spacing)
                    .put("scatter", d.scatter).put("compositeOp", d.compositeOp).put("flipX", d.flipX).put("flipY", d.flipY),
            )
        }
        p.texture?.let { t ->
            o.put(
                "texture",
                JSONObject().put("patternName", t.patternName).put("patternUuid", t.patternUuid).put("scale", t.scale)
                    .put("depth", t.depth).put("mode", t.mode).put("invert", t.invert),
            )
        }
        p.colorDynamics?.let { c ->
            o.put(
                "colorDynamics",
                JSONObject().put("hueJitter", c.hueJitter).put("satJitter", c.satJitter).put("valJitter", c.valJitter)
                    .put("secondaryMix", c.secondaryMix),
            )
        }
        return o
    }
}
