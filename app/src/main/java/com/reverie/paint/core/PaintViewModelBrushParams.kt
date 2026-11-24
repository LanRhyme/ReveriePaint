/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.ResourceDirs
import com.reverie.paint.core.brushstore.toCurveXml
import android.graphics.Bitmap
import com.reverie.paint.R
import com.reverie.paint.core.brushstore.BrushApply
import com.reverie.paint.core.brushstore.BrushCompositeIds
import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.BrushThumbStore
import com.reverie.paint.core.brushstore.ControlId
import com.reverie.paint.core.brushstore.EffectivePresetBuilder
import com.reverie.paint.core.brushstore.KppSensors
import java.io.File
import kotlinx.coroutines.Job

    internal fun PaintViewModel.updateBrushFlow(v: Double, commit: Boolean = true) {
        brushParams = brushParams.copy(flow = v)
        if (commit) {
            rememberToolParamSnapshot()
        }
        BrushApply.applyLive(ControlId.FLOW, v)
    }

    internal fun PaintViewModel.updateBrushSpacing(v: Double) {
        brushParams = brushParams.copy(spacing = v)
        BrushStoreVm.stage(this, ControlId.SPACING, v)
    }

    internal fun PaintViewModel.updateBrushAngle(v: Double) {
        brushParams = brushParams.copy(angle = v)
        BrushStoreVm.stage(this, ControlId.ANGLE, v)
    }

    internal fun PaintViewModel.updateBrushScatter(v: Double) {
        brushParams = brushParams.copy(scatter = v)
        BrushStoreVm.stage(this, ControlId.SCATTER, v)
    }

    internal fun PaintViewModel.updateBrushFade(v: Double) {
        brushParams = brushParams.copy(fade = v)
        BrushStoreVm.stage(this, ControlId.FADE, v)
    }

    internal fun PaintViewModel.updateBrushSoftness(v: Double) {
        brushParams = brushParams.copy(softness = v)
        BrushStoreVm.stage(this, ControlId.SOFTNESS, v)
    }

    internal fun PaintViewModel.updateBrushRatio(v: Double) {
        // 0 圆度会让 MaskGenerator::effectiveSrcHeight 归零 (Krita fromXML 不夹取), UI 下限 0.05
        val ratio = if (v.isFinite()) v.coerceIn(0.05, 1.0) else 1.0
        brushParams = brushParams.copy(ratio = ratio)
        BrushStoreVm.stage(this, ControlId.RATIO, ratio)
    }

    internal fun PaintViewModel.updateBrushSharpness(v: Double) {
        brushParams = brushParams.copy(sharpness = v)
        BrushStoreVm.stage(this, ControlId.SHARPNESS, v)
    }

    internal fun PaintViewModel.updateBrushRotation(v: Double) {
        brushParams = brushParams.copy(rotation = v)
        BrushStoreVm.stage(this, ControlId.ROTATION, v)
    }

    internal fun PaintViewModel.updateBrushCompositeOp(rawOp: String) {
        val op = BrushCompositeIds.canonical(rawOp)
        brushParams = brushParams.copy(compositeOp = op)
        BrushStoreVm.stage(this, ControlId.COMPOSITE_OP, op)
    }

    internal fun PaintViewModel.updateBrushAntiAliasing(v: Int) {
        brushParams = brushParams.copy(antiAliasing = v)
        BrushStoreVm.stage(this, ControlId.ANTI_ALIASING, v)
    }

    internal fun PaintViewModel.updateBrushTipShape(v: Int) {
        brushParams = brushParams.copy(tipShape = v)
        BrushStoreVm.stage(this, ControlId.TIP_SHAPE, v)
    }

    internal fun PaintViewModel.updateBrushRandomFlipX(v: Boolean) {
        brushParams = brushParams.copy(randomFlipX = v)
        BrushStoreVm.stage(this, ControlId.RANDOM_FLIP_X, v)
    }

    internal fun PaintViewModel.updateBrushRandomFlipY(v: Boolean) {
        brushParams = brushParams.copy(randomFlipY = v)
        BrushStoreVm.stage(this, ControlId.RANDOM_FLIP_Y, v)
    }

    /**
     * 跟随方向走统一的 CURVE_ROTATION 通道（与工作台曲线控件同源）：
     * 构造 CurveValue(enabled = v, sensorId = "drawingangle", curve = 当前 Rotation 曲线,
     * strength = 当前强度) 并 stage，不再单独 stage FOLLOW_DIRECTION / 直改引擎。
     */
    internal fun PaintViewModel.updateBrushFollowDirection(v: Boolean) {
        brushParams = brushParams.copy(followDirection = v)
        val opt = brushDynamicOptions["Rotation"]
            ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Rotation")
        updateBrushDynamicOption(
            "Rotation",
            opt.copy(enabled = v, sensorId = "drawingangle"),
        )
    }

    internal fun PaintViewModel.updateBrushTextureEnabled(v: Boolean) {
        brushParams = brushParams.copy(textureEnabled = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_ENABLED, v)
    }

    internal fun PaintViewModel.updateBrushTextureScale(v: Double) {
        brushParams = brushParams.copy(textureScale = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_SCALE, v)
    }

    internal fun PaintViewModel.updateBrushTextureStrength(v: Double) {
        brushParams = brushParams.copy(textureStrength = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_STRENGTH, v)
    }

    internal fun PaintViewModel.updateBrushTextureMode(v: String) {
        brushParams = brushParams.copy(textureMode = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_MODE, v)
    }

    internal fun PaintViewModel.updateBrushTexturePattern(v: String) {
        brushParams = brushParams.copy(texturePattern = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_PATTERN, v)
    }

    internal fun PaintViewModel.updateBrushHueJitter(v: Double) {
        brushParams = brushParams.copy(hueJitter = v)
        val opt = brushDynamicOptions["h"]
        if (v > 1e-4) {
            if (opt?.enabled != true) {
                brushDynamicOptions["h"] = (opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("h")).copy(enabled = true, sensorId = "fuzzy")
            }
        } else if (opt?.enabled == true && opt.sensorId == "fuzzy") {
            brushDynamicOptions["h"] = opt.copy(enabled = false)
        }
        BrushStoreVm.stage(this, ControlId.HUE_JITTER, v)
    }

    internal fun PaintViewModel.updateBrushSatJitter(v: Double) {
        brushParams = brushParams.copy(satJitter = v)
        val opt = brushDynamicOptions["s"]
        if (v > 1e-4) {
            if (opt?.enabled != true) {
                brushDynamicOptions["s"] = (opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("s")).copy(enabled = true, sensorId = "fuzzy")
            }
        } else if (opt?.enabled == true && opt.sensorId == "fuzzy") {
            brushDynamicOptions["s"] = opt.copy(enabled = false)
        }
        BrushStoreVm.stage(this, ControlId.SAT_JITTER, v)
    }

    internal fun PaintViewModel.updateBrushValJitter(v: Double) {
        brushParams = brushParams.copy(valJitter = v)
        val opt = brushDynamicOptions["v"]
        if (v > 1e-4) {
            if (opt?.enabled != true) {
                brushDynamicOptions["v"] = (opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("v")).copy(enabled = true, sensorId = "fuzzy")
            }
        } else if (opt?.enabled == true && opt.sensorId == "fuzzy") {
            brushDynamicOptions["v"] = opt.copy(enabled = false)
        }
        BrushStoreVm.stage(this, ControlId.VAL_JITTER, v)
    }

    internal fun PaintViewModel.updateBrushSecondaryMix(v: Double) {
        brushParams = brushParams.copy(secondaryMix = v)
        BrushStoreVm.stage(this, ControlId.MIX, v)
    }

    internal fun PaintViewModel.updateBrushPressureColorMix(v: Boolean) {
        brushParams = brushParams.copy(pressureColorMix = v)
        BrushStoreVm.stage(this, ControlId.MIX_PRESSURE, v)
    }

    internal fun PaintViewModel.updateBrushPressureEnabled(v: Boolean) {
        brushParams = brushParams.copy(pressureEnabled = v)
        BrushStoreVm.stage(this, ControlId.PRESSURE_ENABLED, v)
    }

    internal fun PaintViewModel.updateBrushPressureSize(v: Double) {
        brushParams = brushParams.copy(pressureSize = v)
        val opt = brushDynamicOptions["Size"]
        val base = opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Size").copy(
            sensorId = brushParams.sizeSensor.ifBlank { "pressure" },
        )
        val cfg = base.copy(
            strength = v.toFloat(),
            enabled = v > 0.001,
        )
        brushDynamicOptions["Size"] = cfg
        BrushStoreVm.stage(this, ControlId.CURVE_SIZE, cfg.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushPressureOpacity(v: Double) {
        brushParams = brushParams.copy(pressureOpacity = v)
        val opt = brushDynamicOptions["Opacity"]
        val base = opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Opacity").copy(
            sensorId = brushParams.opacitySensor.ifBlank { "pressure" },
        )
        val cfg = base.copy(
            strength = v.toFloat(),
            enabled = v > 0.001,
        )
        brushDynamicOptions["Opacity"] = cfg
        BrushStoreVm.stage(this, ControlId.CURVE_OPACITY, cfg.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushPressureFlow(v: Double) {
        brushParams = brushParams.copy(pressureFlow = v)
        val opt = brushDynamicOptions["Flow"]
        val base = opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Flow").copy(
            sensorId = brushParams.flowSensor.ifBlank { "pressure" },
        )
        val cfg = base.copy(
            strength = v.toFloat(),
            enabled = v > 0.001,
        )
        brushDynamicOptions["Flow"] = cfg
        BrushStoreVm.stage(this, ControlId.CURVE_FLOW, cfg.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushSpeedSize(v: Double) {
        brushParams = brushParams.copy(speedSize = v)
        BrushStoreVm.stage(this, ControlId.SPEED_SIZE, v)
    }

    internal fun PaintViewModel.updateBrushPressureCurve(v: Int) {
        brushParams = brushParams.copy(pressureCurve = v)
        BrushStoreVm.stage(this, ControlId.PRESSURE_CURVE, v)
    }

    internal fun PaintViewModel.updateBrushTipAsset(asset: String) {
        brushParams = brushParams.copy(tipAsset = asset)
        BrushStoreVm.stage(this, ControlId.TIP_ASSET, asset)
    }

    internal fun PaintViewModel.updateBrushPaintOpId(id: String) {
        // 必须归一到 Krita 真实注册 id: 未注册的 paintopid 会让 KisPaintOpPreset::load 直接 setValid(false)
        val resolved = com.reverie.paint.core.brushstore.PaintOpIds.canonical(id)
        brushParams = brushParams.copy(paintOpId = resolved)
        BrushStoreVm.stage(this, ControlId.PAINTOP_ID, resolved)
    }

    internal fun PaintViewModel.updateBrushAirbrush(v: Boolean) {
        brushParams = brushParams.copy(airbrush = v)
        BrushStoreVm.stage(this, ControlId.AIRBRUSH, v)
    }

    internal fun PaintViewModel.updateBrushAirbrushRate(v: Double) {
        brushParams = brushParams.copy(airbrushRate = v)
        BrushStoreVm.stage(this, ControlId.AIRBRUSH_RATE, v)
    }

    internal fun PaintViewModel.updateBrushSmudgeRate(v: Double) {
        brushParams = brushParams.copy(smudgeRate = v)
        val opt = brushDynamicOptions["SmudgeRate"]
        val cfg = (opt ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("SmudgeRate")).copy(
            strength = v.toFloat(),
            enabled = v > 0.001,
        )
        brushDynamicOptions["SmudgeRate"] = cfg
        BrushStoreVm.stage(this, ControlId.CURVE_SMUDGE_RATE, cfg.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushSmudgeLength(v: Double) {
        brushParams = brushParams.copy(smudgeLength = v)
        BrushStoreVm.stage(this, ControlId.SMUDGE_LENGTH, v)
    }

    internal fun PaintViewModel.updateBrushColorRate(v: Double) {
        brushParams = brushParams.copy(colorRate = v.coerceIn(0.0, 1.0))
        BrushStoreVm.stage(this, ControlId.COLOR_RATE, brushColorRate)
    }

    internal fun PaintViewModel.updateBrushSmudgeMode(v: Int) {
        brushParams = brushParams.copy(smudgeMode = v.coerceIn(0, 1))
        BrushStoreVm.stage(this, ControlId.SMUDGE_MODE, brushSmudgeMode)
    }

    internal fun PaintViewModel.updateBrushSpikes(v: Int) {
        brushParams = brushParams.copy(spikes = v)
        BrushStoreVm.stage(this, ControlId.SPIKES, v)
    }

    internal fun PaintViewModel.updateBrushJitterAngle(v: Double) {
        brushParams = brushParams.copy(jitterAngle = v)
        BrushStoreVm.stage(this, ControlId.JITTER_ANGLE, v)
    }

    internal fun PaintViewModel.updateBrushMinSizeLimit(v: Double) {
        brushParams = brushParams.copy(minSizeLimit = v)
        if (brushSize < v) updateBrushSize(v)
        BrushStoreVm.stage(this, ControlId.MIN_SIZE_LIMIT, v)
    }

    internal fun PaintViewModel.updateBrushMaxSizeLimit(v: Double) {
        brushParams = brushParams.copy(maxSizeLimit = v)
        if (brushSize > effectiveBrushMaxSize) updateBrushSize(effectiveBrushMaxSize)
        BrushStoreVm.stage(this, ControlId.MAX_SIZE_LIMIT, v)
    }

    internal fun PaintViewModel.updateBrushAuthor(v: String) {
        if (brushIsAuthorLocked) return
        brushParams = brushParams.copy(author = v)
        BrushStoreVm.stage(this, ControlId.AUTHOR, v)
    }

    internal fun PaintViewModel.updateBrushDescription(v: String) {
        brushParams = brushParams.copy(description = v)
        BrushStoreVm.stage(this, ControlId.DESCRIPTION, v)
    }

    internal fun PaintViewModel.updateBrushVersion(v: String) {
        brushParams = brushParams.copy(version = v)
        BrushStoreVm.stage(this, ControlId.VERSION, v)
    }

    internal fun PaintViewModel.updateBrushMaskingEnabled(v: Boolean) {
        brushParams = brushParams.copy(maskingEnabled = v)
        BrushStoreVm.stage(this, ControlId.MASKING_ENABLED, v)
    }

    internal fun PaintViewModel.updateBrushMaskingCompositeOp(v: String) {
        brushParams = brushParams.copy(maskingCompositeOp = v)
        BrushStoreVm.stage(this, ControlId.MASKING_COMPOSITE_OP, v)
    }

    internal fun PaintViewModel.updateBrushMaskingSizeRatio(v: Double) {
        brushParams = brushParams.copy(maskingSizeRatio = v)
        BrushStoreVm.stage(this, ControlId.MASKING_SIZE_RATIO, v)
    }

    internal fun PaintViewModel.updateBrushMaskingSpacing(v: Double) {
        brushParams = brushParams.copy(maskingSpacing = v)
        BrushStoreVm.stage(this, ControlId.MASKING_SPACING, v)
    }

    internal fun PaintViewModel.updateBrushMaskingTipAsset(asset: String) {
        brushParams = brushParams.copy(maskingTipAsset = asset)
        BrushStoreVm.stage(this, ControlId.MASKING_TIP_ASSET, asset)
    }

    internal fun PaintViewModel.updateBrushMaskingFade(v: Double) {
        brushParams = brushParams.copy(maskingFade = v)
        BrushStoreVm.stage(this, ControlId.MASKING_FADE, v)
    }

    internal fun PaintViewModel.updateBrushEraserMode(v: Boolean) {
        brushParams = brushParams.copy(eraserMode = v)
        BrushStoreVm.stage(this, ControlId.ERASER_MODE, v)
    }

    internal fun PaintViewModel.updateBrushPaintingMode(v: Int) {
        val mode = if (v == 1) 1 else 2
        brushParams = brushParams.copy(paintingMode = mode)
        BrushStoreVm.stage(this, ControlId.PAINTING_MODE, mode)
    }

    internal fun PaintViewModel.updateBrushTipMaskType(v: Int) {
        val t = v.coerceIn(0, 2)
        brushParams = brushParams.copy(tipMaskType = t)
        BrushStoreVm.stage(this, ControlId.TIP_MASK_TYPE, t)
    }

    internal fun PaintViewModel.updateBrushTipDensity(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(tipDensity = clamped)
        BrushStoreVm.stage(this, ControlId.TIP_DENSITY, clamped)
    }

    internal fun PaintViewModel.updateBrushTipRandomness(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(tipRandomness = clamped)
        BrushStoreVm.stage(this, ControlId.TIP_RANDOMNESS, clamped)
    }

    internal fun PaintViewModel.updateBrushUseAutoSpacing(v: Boolean) {
        brushParams = brushParams.copy(useAutoSpacing = v)
        BrushStoreVm.stage(this, ControlId.USE_AUTO_SPACING, v)
    }

    internal fun PaintViewModel.updateBrushIsotropicSpacing(v: Boolean) {
        brushParams = brushParams.copy(isotropicSpacing = v)
        BrushStoreVm.stage(this, ControlId.ISOTROPIC_SPACING, v)
    }

    internal fun PaintViewModel.updateBrushApplication(v: Int) {
        val app = v.coerceIn(0, 3)
        brushParams = brushParams.copy(brushApplication = app)
        BrushStoreVm.stage(this, ControlId.BRUSH_APPLICATION, app)
    }

    internal fun PaintViewModel.updateBrushLightnessStrength(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(lightnessStrength = clamped)
        BrushStoreVm.stage(this, ControlId.LIGHTNESS_STRENGTH, clamped)
    }

    internal fun PaintViewModel.updateBrushSharpnessSoftness(v: Int) {
        val clamped = v.coerceIn(0, 100)
        brushParams = brushParams.copy(sharpnessSoftness = clamped)
        BrushStoreVm.stage(this, ControlId.SHARPNESS_SOFTNESS, clamped)
    }

    internal fun PaintViewModel.updateBrushScatterBothAxes(v: Boolean) {
        brushParams = brushParams.copy(scatterBothAxes = v)
        BrushStoreVm.stage(this, ControlId.SCATTER_BOTH_AXES, v)
    }

    internal fun PaintViewModel.updateBrushJitterSize(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(jitterSize = clamped)
        BrushStoreVm.stage(this, ControlId.JITTER_SIZE, clamped)
    }

    internal fun PaintViewModel.updateBrushColorSourceType(v: String) {
        val clean = v.trim().ifBlank { "plain" }
        brushParams = brushParams.copy(colorSourceType = clean)
        BrushStoreVm.stage(this, ControlId.COLOR_SOURCE_TYPE, clean)
    }

    internal fun PaintViewModel.updateBrushDarken(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(darken = clamped)
        BrushStoreVm.stage(this, ControlId.DARKEN, clamped)
    }

    internal fun PaintViewModel.updateBrushPrecisionLevel(v: Int) {
        val clamped = v.coerceIn(1, 5)
        brushParams = brushParams.copy(precisionLevel = clamped)
        BrushStoreVm.stage(this, ControlId.PRECISION_LEVEL, clamped)
    }

    internal fun PaintViewModel.updateBrushPrecisionAuto(v: Boolean) {
        brushParams = brushParams.copy(precisionAuto = v)
        BrushStoreVm.stage(this, ControlId.PRECISION_AUTO, v)
    }

    internal fun PaintViewModel.updateBrushTextureInvert(v: Boolean) {
        brushParams = brushParams.copy(textureInvert = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_INVERT, v)
    }

    internal fun PaintViewModel.updateBrushTextureSoft(v: Boolean) {
        brushParams = brushParams.copy(textureSoft = v)
        BrushStoreVm.stage(this, ControlId.TEXTURE_SOFT, v)
    }

    internal fun PaintViewModel.updateBrushTextureBrightness(v: Double) {
        val clamped = v.coerceIn(-1.0, 1.0)
        brushParams = brushParams.copy(textureBrightness = clamped)
        BrushStoreVm.stage(this, ControlId.TEXTURE_BRIGHTNESS, clamped)
    }

    internal fun PaintViewModel.updateBrushTextureContrast(v: Double) {
        val clamped = v.coerceIn(0.0, 2.0)
        brushParams = brushParams.copy(textureContrast = clamped)
        BrushStoreVm.stage(this, ControlId.TEXTURE_CONTRAST, clamped)
    }

    internal fun PaintViewModel.updateBrushTextureStrengthPressureMin(v: Double) {
        val normalized = if (v < 0.0) -1.0 else v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(textureStrengthPressureMin = normalized)
        BrushStoreVm.stage(this, ControlId.TEXTURE_STRENGTH_PRESSURE, normalized)
    }

    internal fun PaintViewModel.updateBrushSmudgeSmearAlpha(v: Boolean) {
        brushParams = brushParams.copy(smudgeSmearAlpha = v)
        BrushStoreVm.stage(this, ControlId.SMUDGE_SMEAR_ALPHA, v)
    }

    internal fun PaintViewModel.updateBrushSmudgeUseNewEngine(v: Boolean) {
        brushParams = brushParams.copy(smudgeUseNewEngine = v)
        BrushStoreVm.stage(this, ControlId.SMUDGE_USE_NEW_ENGINE, v)
    }

    internal fun PaintViewModel.updateBrushSmudgeOverlayMode(v: Boolean) {
        brushParams = brushParams.copy(smudgeOverlayMode = v)
        BrushStoreVm.stage(this, ControlId.SMUDGE_OVERLAY_MODE, v)
    }

    internal fun PaintViewModel.updateBrushSmudgeRadius(v: Double) {
        val clamped = v.coerceIn(0.0, 3.0)
        brushParams = brushParams.copy(smudgeRadius = clamped)
        BrushStoreVm.stage(this, ControlId.SMUDGE_RADIUS, clamped)
    }

    internal fun PaintViewModel.updateBrushPaintThickness(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(paintThickness = clamped)
        BrushStoreVm.stage(this, ControlId.PAINT_THICKNESS, clamped)
    }

    internal fun PaintViewModel.updateBrushPaintThicknessMode(v: Int) {
        val mode = if (v == 1) 1 else 2
        brushParams = brushParams.copy(paintThicknessMode = mode)
        BrushStoreVm.stage(this, ControlId.PAINT_THICKNESS_MODE, mode)
    }

    internal fun PaintViewModel.updateBrushMaskingTipShape(v: Int) {
        val shape = if (v == 1) 1 else 0
        brushParams = brushParams.copy(maskingTipShape = shape)
        BrushStoreVm.stage(this, ControlId.MASKING_TIP_SHAPE, shape)
    }

    internal fun PaintViewModel.updateBrushMaskingSoftness(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(maskingSoftness = clamped, maskingFade = clamped)
        BrushStoreVm.stage(this, ControlId.MASKING_SOFTNESS, clamped)
    }

    internal fun PaintViewModel.updateBrushMaskingAngle(v: Double) {
        brushParams = brushParams.copy(maskingAngle = v)
        BrushStoreVm.stage(this, ControlId.MASKING_ANGLE, v)
    }

    internal fun PaintViewModel.updateBrushMaskingScatter(v: Double) {
        val clamped = v.coerceIn(0.0, 1.0)
        brushParams = brushParams.copy(maskingScatter = clamped)
        BrushStoreVm.stage(this, ControlId.MASKING_SCATTER, clamped)
    }

    internal fun PaintViewModel.updateBrushMaskingScatterBothAxes(v: Boolean) {
        brushParams = brushParams.copy(maskingScatterBothAxes = v)
        BrushStoreVm.stage(this, ControlId.MASKING_SCATTER_BOTH_AXES, v)
    }

    internal fun PaintViewModel.updateBrushMaskingFlipX(v: Boolean) {
        brushParams = brushParams.copy(maskingFlipX = v)
        BrushStoreVm.stage(this, ControlId.MASKING_FLIP_X, v)
    }

    internal fun PaintViewModel.updateBrushMaskingFlipY(v: Boolean) {
        brushParams = brushParams.copy(maskingFlipY = v)
        BrushStoreVm.stage(this, ControlId.MASKING_FLIP_Y, v)
    }

    internal fun PaintViewModel.updateBrushRotationSensor(v: String) {
        brushParams = brushParams.copy(rotationSensor = v)
        val opt = (brushDynamicOptions["Rotation"]
            ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Rotation")).copy(sensorId = v, enabled = v.isNotBlank())
        brushDynamicOptions["Rotation"] = opt
        BrushStoreVm.stage(this, ControlId.CURVE_ROTATION, opt.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushSizeSensor(v: String) {
        val enabled = v.isNotBlank()
        val opt = (brushDynamicOptions["Size"] ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Size"))
        val newStrength = if (enabled && opt.strength <= 0.001f) 1.0f else opt.strength
        val newOpt = opt.copy(sensorId = v, enabled = enabled, strength = newStrength)
        val newPressureSize = if (enabled && brushParams.pressureSize <= 0.001) newStrength.toDouble() else brushParams.pressureSize
        brushParams = brushParams.copy(sizeSensor = v, pressureSize = newPressureSize)
        brushDynamicOptions["Size"] = newOpt
        BrushStoreVm.stage(this, ControlId.CURVE_SIZE, newOpt.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushOpacitySensor(v: String) {
        val enabled = v.isNotBlank()
        val opt = (brushDynamicOptions["Opacity"] ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Opacity"))
        val newStrength = if (enabled && opt.strength <= 0.001f) 1.0f else opt.strength
        val newOpt = opt.copy(sensorId = v, enabled = enabled, strength = newStrength)
        val newPressureOpacity = if (enabled && brushParams.pressureOpacity <= 0.001) newStrength.toDouble() else brushParams.pressureOpacity
        brushParams = brushParams.copy(opacitySensor = v, pressureOpacity = newPressureOpacity)
        brushDynamicOptions["Opacity"] = newOpt
        BrushStoreVm.stage(this, ControlId.CURVE_OPACITY, newOpt.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushFlowSensor(v: String) {
        val enabled = v.isNotBlank()
        val opt = (brushDynamicOptions["Flow"] ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Flow"))
        val newStrength = if (enabled && opt.strength <= 0.001f) 1.0f else opt.strength
        val newOpt = opt.copy(sensorId = v, enabled = enabled, strength = newStrength)
        val newPressureFlow = if (enabled && brushParams.pressureFlow <= 0.001) newStrength.toDouble() else brushParams.pressureFlow
        brushParams = brushParams.copy(flowSensor = v, pressureFlow = newPressureFlow)
        brushDynamicOptions["Flow"] = newOpt
        BrushStoreVm.stage(this, ControlId.CURVE_FLOW, newOpt.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushScatterSensor(v: String) {
        brushParams = brushParams.copy(scatterSensor = v)
        val opt = (brushDynamicOptions["Scatter"] ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor("Scatter")).copy(
            sensorId = v,
            enabled = v.isNotBlank(),
        )
        brushDynamicOptions["Scatter"] = opt
        BrushStoreVm.stage(this, ControlId.CURVE_SCATTER, opt.toCurveXml())
    }

    internal fun PaintViewModel.updateBrushStreamline(v: Double) {
        brushParams = brushParams.copy(streamline = v)
        BrushStoreVm.stage(this, ControlId.STREAMLINE, v)
    }

    internal fun PaintViewModel.updateBrushTaper(v: Double) {
        brushParams = brushParams.copy(taper = v)
        BrushStoreVm.stage(this, ControlId.TAPER, v)
    }

    /** Capture scratchpad raster as the preset's official PNG thumbnail */
    fun PaintViewModel.capturePresetThumbnail(bitmap: Bitmap, targetPresetIndex: Int = brushPresetIndex): Boolean {
        val preset = brushPresets.firstOrNull { it.index == targetPresetIndex } ?: run {
            android.util.Log.e("ReveriePaint", "capturePresetThumbnail: preset not found for index $targetPresetIndex")
            return false
        }
        val e = BrushStoreBootstrap.managedEntry(preset.id) ?: run {
            android.util.Log.e("ReveriePaint", "capturePresetThumbnail: managedEntry not found for ${preset.name}")
            return false
        }
        if (!e.ext.equals("kpp", ignoreCase = true)) {
            // .myb 不是 PNG，缩略图没有地方嵌；缩略图只对 kpp 生效
            android.util.Log.w("ReveriePaint", "capturePresetThumbnail: ${preset.name} is .${e.ext}, thumbnail not supported")
            return false
        }
        val dir = ResourceDirs.presets(appContext.filesDir)
        if (!dir.exists()) dir.mkdirs()

        val targetSize = 256
        val thumbBmp = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val stream = java.io.ByteArrayOutputStream()
        thumbBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        BrushThumbStore.put(e.id, pngBytes)
        val result = EffectivePresetBuilder.materialize(e.id, dir)
        val success = result != null
        if (success) {
            BrushThumbCache.put(preset.name, thumbBmp)
            brushPresets = brushPresets.map {
                if (it.index == preset.index) it.copy(thumbBytes = pngBytes) else it
            }
            android.widget.Toast.makeText(
                appContext,
                appContext.getString(R.string.brush_studio_toast_thumbnail_updated),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            android.util.Log.e("ReveriePaint", "capturePresetThumbnail: EffectivePresetBuilder.materialize failed for ${preset.name}")
        }
        return success
    }

    internal var pendingKppReloadJob: Job? = null

    /**
     * 出厂预设常带全通道 Sensor/curve，但压感未必打开。曲线为空则关闭；XML 自带 enabled 时以它为准；
     * 都没有时只有 Size 默认开（其余通道的开关不在 sensor 体里，调用方拿不到就当关）。
     */
    internal fun sensorEnabledFromXml(key: String, body: String): Boolean {
        val curve = Regex("""<curve>([^<]*)</curve>""").find(body)?.groupValues?.getOrNull(1)
        if (curve.isNullOrBlank()) return false
        val enabledAttr = Regex("""enabled="([^"]+)"""").find(body)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
        if (enabledAttr != null) return enabledAttr
        return key.equals("size", ignoreCase = true)
    }

    /**
     * 将预设中的传感器 XML (SizeSensor/SpacingSensor 等) 反向灌回 UI 状态。
     * 每次切换笔刷时必须先彻底重置内存 Map，彻底杜绝跨笔刷曲线污染。
     */
    internal fun PaintViewModel.applySensorXmlToDynamicOptions(sensorXml: Map<String, String>) {
        val standardKeys = listOf(
            "Size", "Opacity", "Flow", "Spacing", "Scatter", "Rotation",
            "Ratio", "Softness", "Sharpness", "Darken", "LightnessStrength",
            "SmudgeRate", "ColorRate", "SmudgeRadius", "PaintThickness",
            "h", "s", "v",
        )
        val newMap = mutableMapOf<String, com.reverie.paint.model.DynamicOptionConfig>()
        for (k in standardKeys) {
            val defSensor = when (k) {
                "Rotation" -> com.reverie.paint.model.BrushSensor.DRAWING_ANGLE.id
                "Scatter", "h", "s", "v" -> com.reverie.paint.model.BrushSensor.FUZZY.id
                else -> com.reverie.paint.model.BrushSensor.PRESSURE.id
            }
            newMap[k] = com.reverie.paint.model.DynamicOptionConfig(
                optionKey = k,
                enabled = false,
                sensorId = defSensor,
                points = com.reverie.paint.model.CurvePreset.LINEAR.createPoints(),
            )
        }
        for ((key, body) in sensorXml) {
            val sensorList = KppSensors.sensorIds(body)
            val id = KppSensors.primarySensorId(body, key) ?: KppSensors.topId(body)
            val curve = (if (id != null) KppSensors.sensorCurve(body, id) else null)
                ?: KppSensors.firstCurve(body)
                ?: Regex("""<curve>([^<]*)</curve>""").find(body)?.groupValues?.getOrNull(1)
            if (curve.isNullOrBlank()) continue
            val points = com.reverie.paint.model.DynamicOptionConfig.parseKritaCurve(curve)
            val existing = newMap[key]
            val strengthAttr = Regex("""strength="([^"]+)"""").find(body)?.groupValues?.getOrNull(1)?.toFloatOrNull()

            val isEnabled = sensorEnabledFromXml(key, body)
            val strength = strengthAttr ?: 1.0f

            newMap[key] = (existing ?: com.reverie.paint.model.DynamicOptionConfig(optionKey = key)).copy(
                enabled = isEnabled,
                sensorId = id ?: existing?.sensorId ?: "pressure",
                points = points,
                strength = strength,
            )
        }
        brushDynamicOptions.clear()
        brushDynamicOptions.putAll(newMap)
    }

