/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.AbrSnapshotBuilder
import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.CurveValue
import com.reverie.paint.core.brushstore.KppSensors
import com.reverie.paint.core.brushstore.KppXml
import com.reverie.paint.core.brushstore.PresetPropertyMap
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
    // v12: 最小直径取值规则修正——szVr.Mnm>0 才优先，Mnm 缺失/为 0 时吃顶层 minimumDiameter
    // （#1 十九号 70% 与 #48 肌理-粗糙 72% 下限保真修正）
    // v13: 颜色动态 fgBgControl==0 时 fgBgJitter 不再映射恒定 secondaryMix（置 0）；启动支持版本漂移自动重转
    const val CONVERTER_VERSION = 13

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

        // 映射倾斜与动力学传感器（R1 复合动态：主控制源与抖动不再互斥）
        // 每个通道收集全部生效控制源：主源（优先级 pressure > tilt > fade，与原分支的主源顺序一致）+
        // 抖动 fuzzy。单一源保持既有单传感器写法（含 enabled 语义）；多源组 sensorslist——主源作第一个
        // ChildSensor，fuzzy 作追加 ChildSensor（下限曲线 0,(1-j);1,1;）。最终 kpp 里的
        // <Id>UseSameCurve=false 与 <Id>curveMode=0（多传感器相乘）由 AbrSnapshotBuilder 显式写入：
        // 上游 KisKritaSensorPack.cpp:188 UseSameCurve 缺省 true，会被 commonCurve 覆盖子传感器曲线。
        val sizePrimary = when {
            preset.pressureSize -> CurveSource("pressure", sizeCurveWithFloor(preset.minDiameterRatio), false)
            preset.tiltSize -> CurveSource("declination", sizeCurveWithFloor(preset.minDiameterRatio), false)
            else -> null
        }
        // sizeJitter 抖动曲线下限 f = max(1 − sizeJitter, minimumDiameter)（HC02 0.25/0.10 → 0.750）
        val sizeFuzzy = if (preset.sizeJitter > 0.05) {
            CurveSource("fuzzy", jitterFloorCurve(preset.sizeJitter, preset.minDiameterRatio), false)
        } else null
        val sizeSensor = primarySensorId(sizePrimary, sizeFuzzy, fallback = "pressure")

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

        val opacityPrimary = when {
            effectivePressureOpacity -> CurveSource("pressure", transferCurve(0.0, safeOpacityMin), true)
            preset.tiltOpacity -> CurveSource("declination", transferCurve(0.0, safeOpacityMin), false)
            else -> null
        }
        val opacityFuzzy = if (preset.opacityJitter > 0.05) CurveSource("fuzzy", opacityCurve, true) else null
        val opacitySensor = primarySensorId(opacityPrimary, opacityFuzzy, fallback = "pressure")

        val flowPrimary = when {
            preset.pressureFlow -> CurveSource("pressure", transferCurve(0.0, preset.flowMin), true)
            preset.tiltFlow -> CurveSource("declination", transferCurve(0.0, preset.flowMin), false)
            else -> null
        }
        val flowFuzzy = if (preset.flowJitter > 0.05) CurveSource("fuzzy", flowCurve, true) else null
        val flowSensor = primarySensorId(flowPrimary, flowFuzzy, fallback = "pressure")

        val rotationPrimary = when {
            preset.followDirection -> CurveSource("drawingangle", KppSensors.LINEAR, false)
            preset.tiltAngle -> CurveSource("ascension", KppSensors.LINEAR, false)
            else -> null
        }
        val rotationFuzzy = if (preset.angleJitter > 0.05) CurveSource("fuzzy", KppSensors.LINEAR, false) else null
        val rotationSensor = primarySensorId(rotationPrimary, rotationFuzzy, fallback = "drawingangle")

        val dynamicOptions = mutableMapOf<String, String>()
        if (sizePrimary != null || sizeFuzzy != null) {
            dynamicOptions["Size"] = channelSensorXml(sizePrimary, sizeFuzzy)!!
        }
        if (opacityPrimary != null || opacityFuzzy != null) {
            dynamicOptions["Opacity"] = channelSensorXml(opacityPrimary, opacityFuzzy)!!
        }
        if (flowPrimary != null || flowFuzzy != null) {
            dynamicOptions["Flow"] = channelSensorXml(flowPrimary, flowFuzzy)!!
        }
        if (rotationPrimary != null || rotationFuzzy != null) {
            // 单一 drawingangle 源沿用带 fanCorners 属性的历史写法；复合/其余源走通用组表
            dynamicOptions["Rotation"] = if (rotationFuzzy == null && rotationPrimary?.id == "drawingangle") {
                """<!DOCTYPE params><params fanCornersStep="30" fanCornersEnabled="0" angleOffset="0" id="drawingangle"><curve>0,0;1,1;</curve></params>"""
            } else {
                channelSensorXml(rotationPrimary, rotationFuzzy)!!
            }
        }
        if (preset.scatter > 0.001) {
            // scatterDynamics 控制=钢笔压力：散布量随压力放大（KisScatterOption 的抖动幅度×传感器值），在 fuzzy 之外再叠 pressure
            dynamicOptions["Scatter"] = if (preset.scatterPressure) {
                KppSensors.addSensor(KppSensors.single("fuzzy"), "pressure")
            } else """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        // 圆度控制为压感/倾斜时，minimumRoundness 是 Ratio 曲线下限（压感 0 → minimum，满压 → 笔尖圆度）
        val ratioPrimary = when {
            preset.pressureRoundness -> CurveSource("pressure", sizeCurveWithFloor(preset.minRoundness), false)
            preset.tiltRoundness -> CurveSource("declination", sizeCurveWithFloor(preset.minRoundness), false)
            else -> null
        }
        // Ratio 抖动曲线下限 f = max(1 − jitter, minimumRoundness)：抖动在 [f,1] 内随机，
        // 永不低于 Photoshop 的 minimum；只有真有 roundnessJitter 才写 fuzzy
        val ratioFuzzy = if (preset.roundnessJitter > 0.05) {
            val floor = maxOf(1.0 - preset.roundnessJitter, preset.minRoundness).coerceIn(0.0, 1.0)
            if (floor < 0.999) CurveSource("fuzzy", jitterFloorCurve(preset.roundnessJitter, preset.minRoundness), true) else null
        } else null
        channelSensorXml(ratioPrimary, ratioFuzzy)?.let { dynamicOptions["Ratio"] = it }

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
            // PS 语义：bVTy==0 的 clVr.jitter 是逐印章随机前背景抖动，不是常量混色；仅 bVTy 非 0
            // （压感等控制态）才映射 secondaryMix，否则置 0（writeMix 不再写 PressureMix=true，
            // 根除 29 干木炭/31 圆炭条混白）。解析层 AbrColorDynamicsInfo.secondaryMix 保留原始值供诊断。
            secondaryMix = if (cd != null && cd.fgBgControl != 0) cd.secondaryMix else 0.0,
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

    /** 通道的一个控制源：传感器 id + 曲线 + 单源写法时的 enabled 语义（与旧 if/else 分支一致） */
    private data class CurveSource(val id: String, val curve: String, val forceEnabled: Boolean)

    /** 主源优先级 pressure > tilt > fade（与原分支主源顺序一致）；都没有时用 [fallback] 作元数据镜像 */
    private fun primarySensorId(primary: CurveSource?, fuzzy: CurveSource?, fallback: String): String =
        primary?.id ?: fuzzy?.id ?: fallback

    /**
     * 单一源保持既有单传感器写法（[CurveSource.forceEnabled] 与旧分支一致）；
     * 主源 + 抖动双源时组 sensorslist：主源在前、fuzzy 追加在后（多传感器相乘，curveMode=0）。
     */
    private fun channelSensorXml(primary: CurveSource?, fuzzy: CurveSource?): String? = when {
        primary == null && fuzzy == null -> null
        primary == null -> sensorParams(fuzzy!!.id, fuzzy.curve, fuzzy.forceEnabled)
        fuzzy == null -> sensorParams(primary.id, primary.curve, primary.forceEnabled)
        else -> KppSensors.addSensor(KppSensors.single(primary.id, primary.curve), "fuzzy", fuzzy.curve)
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
            .put("angleControl", p.angleControl)
            .put("sizeJitter", p.sizeJitter).put("angleJitter", p.angleJitter).put("roundnessJitter", p.roundnessJitter)
            .put("opacityJitter", p.opacityJitter).put("flowJitter", p.flowJitter)
            .put("minDiameterRatio", p.minDiameterRatio).put("minRoundness", p.minRoundness)
            .put("followDirection", p.followDirection).put("flipX", p.flipX).put("flipY", p.flipY)
            .put("flipXJitter", p.flipXJitter).put("flipYJitter", p.flipYJitter)
            .put("scatterPressure", p.scatterPressure).put("scatterCount", p.scatterCount)
            .put("opacityMin", p.opacityMin).put("flowMin", p.flowMin)
            .put("isComputed", p.isComputed).put("airbrush", p.airbrush).put("compositeOp", p.compositeOp)
            // v13 起序列化工具类型与涂抹参数（此前版本缺失，重建时按快照 XML paintop 回填，见 fromAbrParamsJson）
            .put("toolClass", p.toolClass).put("smudgeStrength", p.smudgeStrength)
            .put("wetness", p.wetness).put("mix", p.mix).put("dryness", p.dryness)
        p.dualBrush?.let { d ->
            o.put(
                "dualBrush",
                JSONObject().put("tipUuid", d.tipUuid ?: JSONObject.NULL).put("diameter", d.diameter).put("spacing", d.spacing)
                    .put("scatter", d.scatter).put("compositeOp", d.compositeOp).put("rawMode", d.rawMode)
                    .put("flipX", d.flipX).put("flipY", d.flipY)
                    .put("scatterBothAxes", d.scatterBothAxes).put("count", d.count)
                    .put("angle", d.angle).put("roundness", d.roundness),
            )
        }
        p.texture?.let { t ->
            o.put(
                "texture",
                JSONObject().put("patternName", t.patternName).put("patternUuid", t.patternUuid).put("scale", t.scale)
                    .put("depth", t.depth).put("mode", t.mode).put("rawMode", t.rawMode).put("invert", t.invert)
                    .put("brightness", t.brightness).put("contrast", t.contrast)
                    .put("depthPressure", t.depthPressure).put("depthMin", t.depthMin),
            )
        }
        p.colorDynamics?.let { c ->
            o.put(
                "colorDynamics",
                JSONObject().put("hueJitter", c.hueJitter).put("satJitter", c.satJitter).put("valJitter", c.valJitter)
                    .put("secondaryMix", c.secondaryMix).put("fgBgControl", c.fgBgControl),
            )
        }
        return o
    }

    // ---- fromAbrParamsJson / reconvertSnapshot（Round3-B 版本漂移自愈） ----

    private fun jStr(o: JSONObject, k: String, d: String = ""): String = if (o.isNull(k)) d else o.optString(k, d)
    private fun jDbl(o: JSONObject, k: String, d: Double = 0.0): Double = if (o.isNull(k)) d else o.optDouble(k, d)
    private fun jInt(o: JSONObject, k: String, d: Int = 0): Int = if (o.isNull(k)) d else o.optInt(k, d)
    private fun jBool(o: JSONObject, k: String, d: Boolean = false): Boolean = if (o.isNull(k)) d else o.optBoolean(k, d)

    private fun jUuid(o: JSONObject, k: String): String? =
        if (o.isNull(k)) null else o.optString(k, "").ifBlank { null }

    /**
     * [abrParamsJson] 的逆读：显式键名重建 [AbrParser.AbrPresetInfo]，缺失键吃 data class 默认值
     * （旧版快照的 abrParams 没有后加的键）。
     *
     * [fallbackXml] 是旧快照的 preset XML：旧版（<v13）abrParamsJson 未序列化 toolClass/smudge 系字段，
     * 当快照 paintop==colorsmudge 时按旧 XML 回填（smudgeMode==1 → MixB，否则 SmTl；涂抹强度/颜色比例
     * 读回 SmudgeRate/ColorRate），防止重建把涂抹笔退化为 paintbrush（33/34 案例）。仅 toolClass 为空时回填。
     */
    fun fromAbrParamsJson(o: JSONObject, fallbackXml: String? = null): AbrParser.AbrPresetInfo {
        var p = AbrParser.AbrPresetInfo(
            name = jStr(o, "name"),
            tipUuid = jUuid(o, "tipUuid"),
            tipIndex = jInt(o, "tipIndex", -1),
            diameter = jDbl(o, "diameter", 30.0),
            spacing = jDbl(o, "spacing", 0.25),
            angle = jDbl(o, "angle", 0.0),
            roundness = jDbl(o, "roundness", 1.0),
            hardness = jDbl(o, "hardness", 1.0),
            scatter = jDbl(o, "scatter", 0.0),
            scatterBothAxes = jBool(o, "scatterBothAxes", true),
            toolOpacity = jDbl(o, "toolOpacity", 1.0),
            toolFlow = jDbl(o, "toolFlow", 1.0),
            pressureRoundness = jBool(o, "pressureRoundness"),
            tiltRoundness = jBool(o, "tiltRoundness"),
            pressureSize = jBool(o, "pressureSize"),
            pressureOpacity = jBool(o, "pressureOpacity"),
            pressureFlow = jBool(o, "pressureFlow"),
            tiltSize = jBool(o, "tiltSize"),
            tiltOpacity = jBool(o, "tiltOpacity"),
            tiltFlow = jBool(o, "tiltFlow"),
            tiltAngle = jBool(o, "tiltAngle"),
            angleControl = jInt(o, "angleControl", 0),
            sizeJitter = jDbl(o, "sizeJitter", 0.0),
            angleJitter = jDbl(o, "angleJitter", 0.0),
            roundnessJitter = jDbl(o, "roundnessJitter", 0.0),
            opacityJitter = jDbl(o, "opacityJitter", 0.0),
            flowJitter = jDbl(o, "flowJitter", 0.0),
            minDiameterRatio = jDbl(o, "minDiameterRatio", 0.0),
            minRoundness = jDbl(o, "minRoundness", 0.0),
            followDirection = jBool(o, "followDirection"),
            flipX = jBool(o, "flipX"),
            flipY = jBool(o, "flipY"),
            flipXJitter = jBool(o, "flipXJitter"),
            flipYJitter = jBool(o, "flipYJitter"),
            scatterPressure = jBool(o, "scatterPressure"),
            scatterCount = jInt(o, "scatterCount", 1),
            opacityMin = jDbl(o, "opacityMin", 0.0),
            flowMin = jDbl(o, "flowMin", 0.0),
            isComputed = jBool(o, "isComputed"),
            dualBrush = o.optJSONObject("dualBrush")?.let { d ->
                AbrParser.AbrDualBrushInfo(
                    tipUuid = jUuid(d, "tipUuid"),
                    diameter = jDbl(d, "diameter", 30.0),
                    spacing = jDbl(d, "spacing", 0.25),
                    scatter = jDbl(d, "scatter", 0.0),
                    compositeOp = jStr(d, "compositeOp", "multiply"),
                    rawMode = jStr(d, "rawMode"),
                    flipX = jBool(d, "flipX"),
                    flipY = jBool(d, "flipY"),
                    scatterBothAxes = jBool(d, "scatterBothAxes", true),
                    count = jInt(d, "count", 1),
                    angle = jDbl(d, "angle", 0.0),
                    roundness = jDbl(d, "roundness", 1.0),
                )
            },
            texture = o.optJSONObject("texture")?.let { t ->
                AbrParser.AbrTextureInfo(
                    patternName = jStr(t, "patternName"),
                    patternUuid = jStr(t, "patternUuid"),
                    scale = jDbl(t, "scale", 1.0),
                    depth = jDbl(t, "depth", 0.5),
                    mode = jStr(t, "mode", "multiply"),
                    rawMode = jStr(t, "rawMode"),
                    invert = jBool(t, "invert"),
                    brightness = jInt(t, "brightness", 0),
                    contrast = jInt(t, "contrast", 0),
                    depthPressure = jBool(t, "depthPressure"),
                    depthMin = jDbl(t, "depthMin", 0.0),
                )
            },
            colorDynamics = o.optJSONObject("colorDynamics")?.let { c ->
                AbrParser.AbrColorDynamicsInfo(
                    hueJitter = jDbl(c, "hueJitter", 0.0),
                    satJitter = jDbl(c, "satJitter", 0.0),
                    valJitter = jDbl(c, "valJitter", 0.0),
                    secondaryMix = jDbl(c, "secondaryMix", 0.0),
                    fgBgControl = jInt(c, "fgBgControl", 0),
                )
            },
            airbrush = jBool(o, "airbrush"),
            compositeOp = jStr(o, "compositeOp", "normal"),
            toolClass = jStr(o, "toolClass"),
            smudgeStrength = jDbl(o, "smudgeStrength", 1.0),
            wetness = jDbl(o, "wetness", 0.0),
            mix = jDbl(o, "mix", 0.0),
            dryness = jDbl(o, "dryness", 0.0),
        )
        if (fallbackXml != null) {
            // 旧版（<v13）abrParamsJson 未序列化、但 convert 已消费的字段：从旧快照 XML 回填，
            // 防止重建漂移。只回填 o 里缺失（吃默认值）的字段。
            val old = PresetPropertyMap.readParams(fallbackXml)
            // ① toolClass/smudge 系：快照 paintop==colorsmudge 时必回填，否则涂抹笔退化为 paintbrush（33/34）。
            //    口径与 convert 的 Mixer/Smudge 分支互逆：MixB 的 smudgeLength 源自 wetness（或 0.5 缺省）、
            //    colorRate 源自 mix（或 1-dryness）；SmTl 的 smudgeLength 源自 smudgeStrength、colorRate 恒 0
            if (p.toolClass.isBlank() && KppXml.paintOpId(fallbackXml) == "colorsmudge") {
                val mixer = old.smudgeMode == 1
                p = p.copy(
                    toolClass = if (mixer) "MixB" else "SmTl",
                    smudgeStrength = old.smudgeLength,
                    wetness = if (mixer) old.smudgeLength else 0.0,
                    mix = 0.0,
                    dryness = if (mixer) (1.0 - old.colorRate).coerceIn(0.0, 1.0) else 0.0,
                )
            }
            // ② dualBrush.angle：convert 只写进蒙版 brush_definition 的 angle 属性（弧度）
            p.dualBrush?.let { d ->
                if (d.angle == 0.0) {
                    val rad = KppXml.get(fallbackXml, KppXml.MASK_BRUSH_DEF)
                        ?.let { KppXml.tagAttr(it, "Brush", "angle") }?.toDoubleOrNull()
                    if (rad != null) {
                        // 4 位小数去弧度往返的浮点噪声（30° → 29.999999999999996 → 30.0）
                        p = p.copy(dualBrush = d.copy(angle = Math.round(Math.toDegrees(rad) * 1e4) / 1e4))
                    }
                }
            }
            // ③ texture.brightness/contrast：旧 XML 存的是 Krita 域（Brightness = -ps/255，Contrast = 1+ps/100，
            //    见 convert 的 textureBrightness/textureContrast 与 PresetPropertyMap 的读键）
            p.texture?.let { t ->
                val b = KppXml.getDouble(fallbackXml, "Texture/Pattern/Brightness")
                val c = KppXml.getDouble(fallbackXml, "Texture/Pattern/Contrast")
                if (b != null || c != null) {
                    p = p.copy(
                        texture = t.copy(
                            brightness = b?.let { Math.round(-it * 255.0).toInt().coerceIn(-150, 150) } ?: t.brightness,
                            contrast = c?.let { Math.round((it - 1.0) * 100.0).toInt().coerceIn(-50, 100) } ?: t.contrast,
                        ),
                    )
                }
            }
            // ④ flip 系（形状动态随机翻转）：convert 写进 Mirror 选项（PressureMirror + Horizontal/VerticalMirrorEnabled）
            if (!p.flipXJitter && old.randomFlipX) p = p.copy(flipXJitter = true)
            if (!p.flipYJitter && old.randomFlipY) p = p.copy(flipYJitter = true)
            // ⑤ scatterPressure：convert 把 pressure 挂进 Scatter 传感器列表；该 sensorslist 经
            //    writeCurve 的 CurveValue 往返会退化成无子传感器的 `<params id="sensorslist">` 形态
            //    （fuzzy 单传感器则是 `<params id="fuzzy">`），两种形态都认
            if (!p.scatterPressure && p.scatter > 0.001) {
                val scat = KppSensors.sensorXml(fallbackXml, "Scatter")
                val hasPressure = scat != null &&
                    ("pressure" in KppSensors.sensorIds(scat) || KppSensors.topId(scat) == "sensorslist")
                if (hasPressure) p = p.copy(scatterPressure = true)
            }
            // ⑥ opacityMin/flowMin：convert 把有效下限写进 Opacity/Flow 曲线起点（transferCurve 的 floor），
            //    取全部曲线起点最小值（主源曲线 floor = safeOpacityMin，恒 ≤ 抖动曲线 floor）
            minCurveFloor(KppSensors.sensorXml(fallbackXml, "Opacity"))?.let { if (it > p.opacityMin) p = p.copy(opacityMin = it) }
            minCurveFloor(KppSensors.sensorXml(fallbackXml, "Flow"))?.let { if (it > p.flowMin) p = p.copy(flowMin = it) }
            // ⑦ texture.depthPressure/depthMin：PS 深度随压力 → Texture/Strength 压力曲线（起点即 minimumDepth，
            //    判据与 TEXTURE_STRENGTH_PRESSURE 的 read 一致；depthPressure=false 时该选项只有 UseCurve=false）
            p.texture?.let { t ->
                if (!t.depthPressure) {
                    val o = PresetPropertyMap.TEXTURE_STRENGTH_OPTION
                    val sx = KppSensors.sensorXml(fallbackXml, o)
                    if (sx != null && KppSensors.isChecked(fallbackXml, o) && KppSensors.useCurve(fallbackXml, o) &&
                        "pressure" in KppSensors.sensorIds(sx)
                    ) {
                        val min = KppSensors.firstCurve(sx)?.split(';')?.firstOrNull()?.split(',')?.getOrNull(1)?.toDoubleOrNull()
                        if (min != null) p = p.copy(texture = t.copy(depthPressure = true, depthMin = min.coerceIn(0.0, 1.0)))
                    }
                }
            }
        }
        return p
    }

    /** 传感器 XML 里全部曲线起点 y 的最小值（transferCurve 写的下限 floor）；无曲线返回 null */
    private fun minCurveFloor(sensorXml: String?): Double? {
        val s = sensorXml ?: return null
        return KppSensors.sensorIds(s).mapNotNull { id ->
            KppSensors.sensorCurve(s, id)?.let { CurveValue.points(it).firstOrNull()?.second }
        }.minOrNull()
    }

    /**
     * 从旧快照重建 ABR 快照字节（Round3-B）：[preset] 来自 source.json 的 abrParams
     * （旧版缺字段已由 [fromAbrParamsJson] 从旧 XML 回填）。
     *
     * 资源绑定（主/副笔尖、图案）以旧快照 XML 已绑定的文件名为准——此处没有原始 ABR 可重新匹配，
     * 旧绑定就是上次转换的权威结果；旧 XML 未绑定的资源保持未绑定（不按名字/显示名猜）。
     * 预览 PNG 块原样保留（只换 XML）。读不出旧/新 XML 时返回 null。
     *
     * @param resourceStore 可选；用于按文件名查图案登记 Ref（拿规范 md5），查不到退回旧 XML 里的 MD5Sum
     */
    fun reconvertSnapshot(
        oldKppBytes: ByteArray,
        preset: AbrParser.AbrPresetInfo,
        candidateName: String,
        packBaseName: String,
        resourceStore: ResourceStore? = null,
        policy: AbrBlendModes.Policy = AbrBlendModes.Policy.COMPAT_SAFE,
    ): Converted? {
        val oldXml = KppHelper.readPresetXml(oldKppBytes, stripEmbeddedResources = true) ?: return null
        val oldTipFile = PresetPropertyMap.readTipAsset(oldXml)
        val oldMaskFile = KppXml.get(oldXml, KppXml.MASK_BRUSH_DEF)
            ?.let { KppXml.tagAttr(it, "Brush", "filename") }.orEmpty()
        val oldScale = KppXml.brushAttr(oldXml, "scale")?.toDoubleOrNull()
        val oldWidth = KppXml.brushAttr(oldXml, "width")
        val oldHeight = KppXml.brushAttr(oldXml, "height")

        val tipNames = buildMap {
            preset.tipUuid?.takeIf { oldTipFile.isNotBlank() }?.let { put(it, oldTipFile) }
            preset.dualBrush?.tipUuid?.takeIf { oldMaskFile.isNotBlank() }?.let { put(it, oldMaskFile) }
        }

        val patternRefs: Map<String, ResourceStore.Ref> = preset.texture?.let { tex ->
            val oldFile = KppXml.get(oldXml, "Texture/Pattern/PatternFileName")?.ifBlank { null }
                ?: KppXml.get(oldXml, "Texture/Pattern/Name")?.ifBlank { null }
            val ref = oldFile?.let { f ->
                resourceStore?.resolveByName(f, ResourceStore.Kind.PATTERN)
                    ?: ResourceStore.Ref(
                        md5 = KppXml.get(oldXml, "Texture/Pattern/PatternMD5Sum").orEmpty(),
                        file = f,
                        kind = ResourceStore.Kind.PATTERN,
                    )
            }
            if (ref != null && tex.patternUuid.isNotBlank()) mapOf(tex.patternUuid to ref) else emptyMap()
        } ?: emptyMap()

        val converted = convert(
            preset, candidateName, matchedTip = null, matchedTipFileName = oldTipFile,
            tipUuidToFileName = tipNames, packBaseName = packBaseName,
            patternRefs = patternRefs, policy = policy,
        )
        var xml = KppHelper.readPresetXml(converted.kppBytes, stripEmbeddedResources = true) ?: return null
        if (oldTipFile.isNotBlank()) {
            // matchedTip 不可用（无原始 ABR）→ build 不会写笔尖 scale/width/height；
            // 旧绑定上的这些值是报告尺寸的权威值（diameter / max(w,h)），缺了就按旧值回写
            if (oldScale != null) xml = KppXml.setBrushAttr(xml, "scale", formatScaleAttr(oldScale))
            if (oldWidth != null && KppXml.brushAttr(xml, "width") == null) xml = KppXml.setBrushAttr(xml, "width", oldWidth)
            if (oldHeight != null && KppXml.brushAttr(xml, "height") == null) xml = KppXml.setBrushAttr(xml, "height", oldHeight)
            // 双重画笔副笔尖：uuid 映射不上（旧 abrParams 缺副 uuid）时回写旧文件名，保住蒙版绑定
            if (preset.dualBrush != null && oldMaskFile.isNotBlank()) {
                val def = KppXml.get(xml, KppXml.MASK_BRUSH_DEF)
                if (def != null && KppXml.tagAttr(def, "Brush", "filename").isNullOrBlank()) {
                    xml = KppXml.set(xml, KppXml.MASK_BRUSH_DEF, KppXml.setTagAttr(def, "Brush", "filename", oldMaskFile))
                }
            }
        }
        return converted.copy(kppBytes = KppHelper.replacePresetXml(oldKppBytes, xml))
    }

    /** 与 AbrSnapshotBuilder.formatScale 同款（reports size = max(w,h) × scale） */
    private fun formatScaleAttr(v: Double): String {
        if (!v.isFinite() || v <= 0.0) return "1"
        val rounded = String.format(Locale.US, "%.6f", v)
        return if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
    }
}
