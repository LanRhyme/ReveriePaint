/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

    internal fun PaintViewModel.updateBrushFlow(v: Double, commit: Boolean = true) {
        brushFlow = v
        if (commit) {
            saveBrushParam()
            rememberToolParamSnapshot()
        }
        runCore(render = false) { ReverieCoreBridge.setBrushFlow(v) }
    }

    internal fun PaintViewModel.updateBrushSpacing(v: Double) {
        brushSpacing = v
        saveBrushParam(spacingChanged = true)
        runCore(render = false) { ReverieCoreBridge.setBrushSpacing(v) }
    }

    internal fun PaintViewModel.updateBrushAngle(v: Double) {
        brushAngle = v
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushAngle(v) }
    }

    internal fun PaintViewModel.updateBrushScatter(v: Double) {
        brushScatter = v
        saveBrushParam(dynamicsChanged = true)
        runCore(render = false) { ReverieCoreBridge.setBrushScatter(v) }
    }

    internal fun PaintViewModel.updateBrushFade(v: Double) {
        brushFade = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushFade(v) }
    }

    internal fun PaintViewModel.updateBrushSoftness(v: Double) {
        brushSoftness = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushSoftness(v) }
    }

    internal fun PaintViewModel.updateBrushRatio(v: Double) {
        brushRatio = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushRatio(v) }
    }

    internal fun PaintViewModel.updateBrushSharpness(v: Double) {
        brushSharpness = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushSharpness(v) }
    }

    internal fun PaintViewModel.updateBrushRotation(v: Double) {
        brushRotation = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushRotation(v) }
    }

    internal fun PaintViewModel.updateBrushCompositeOp(op: String) {
        brushCompositeOp = op
        saveBrushParam()
        runCore(render = false) { ReverieCoreBridge.setBrushCompositeOp(op) }
    }

    internal fun PaintViewModel.updateBrushAntiAliasing(v: Int) {
        brushAntiAliasing = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) { ReverieCoreBridge.setBrushAntiAliasing(v) }
    }

    internal fun PaintViewModel.updateBrushTipShape(v: Int) {
        brushTipShape = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushRandomFlipX(v: Boolean) {
        brushRandomFlipX = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) { ReverieCoreBridge.setBrushMirror(brushRandomFlipX, brushRandomFlipY) }
    }

    internal fun PaintViewModel.updateBrushRandomFlipY(v: Boolean) {
        brushRandomFlipY = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) { ReverieCoreBridge.setBrushMirror(brushRandomFlipX, brushRandomFlipY) }
    }

    internal fun PaintViewModel.updateBrushFollowDirection(v: Boolean) {
        brushFollowDirection = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) { ReverieCoreBridge.setBrushFollowDirection(v) }
    }

    internal fun PaintViewModel.updateBrushTextureEnabled(v: Boolean) {
        brushTextureEnabled = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushTexture(brushTextureEnabled, brushTextureScale, brushTextureStrength, brushTextureMode, brushTexturePattern)
        }
    }

    internal fun PaintViewModel.updateBrushTextureScale(v: Double) {
        brushTextureScale = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushTexture(brushTextureEnabled, brushTextureScale, brushTextureStrength, brushTextureMode, brushTexturePattern)
        }
    }

    internal fun PaintViewModel.updateBrushTextureStrength(v: Double) {
        brushTextureStrength = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushTexture(brushTextureEnabled, brushTextureScale, brushTextureStrength, brushTextureMode, brushTexturePattern)
        }
    }

    internal fun PaintViewModel.updateBrushTextureMode(v: String) {
        brushTextureMode = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushTexture(brushTextureEnabled, brushTextureScale, brushTextureStrength, brushTextureMode, brushTexturePattern)
        }
    }

    internal fun PaintViewModel.updateBrushTexturePattern(v: String) {
        brushTexturePattern = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushTexture(brushTextureEnabled, brushTextureScale, brushTextureStrength, brushTextureMode, brushTexturePattern)
        }
    }

    internal fun PaintViewModel.updateBrushHueJitter(v: Double) {
        brushHueJitter = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushSatJitter(v: Double) {
        brushSatJitter = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushValJitter(v: Double) {
        brushValJitter = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushSecondaryMix(v: Double) {
        brushSecondaryMix = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushPressureColorMix(v: Boolean) {
        brushPressureColorMix = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushPressureEnabled(v: Boolean) {
        brushPressureEnabled = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushPressureDynamics(brushPressureEnabled, brushPressureSize, brushPressureOpacity, brushPressureFlow, brushPressureCurve)
        }
    }

    internal fun PaintViewModel.updateBrushPressureSize(v: Double) {
        brushPressureSize = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushPressureDynamics(brushPressureEnabled, brushPressureSize, brushPressureOpacity, brushPressureFlow, brushPressureCurve)
        }
    }

    internal fun PaintViewModel.updateBrushPressureOpacity(v: Double) {
        brushPressureOpacity = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushPressureDynamics(brushPressureEnabled, brushPressureSize, brushPressureOpacity, brushPressureFlow, brushPressureCurve)
        }
    }

    internal fun PaintViewModel.updateBrushPressureFlow(v: Double) {
        brushPressureFlow = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushPressureDynamics(brushPressureEnabled, brushPressureSize, brushPressureOpacity, brushPressureFlow, brushPressureCurve)
        }
    }

    internal fun PaintViewModel.updateBrushSpeedSize(v: Double) {
        brushSpeedSize = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushPressureCurve(v: Int) {
        brushPressureCurve = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
        runCore(render = false) {
            ReverieCoreBridge.setBrushPressureDynamics(brushPressureEnabled, brushPressureSize, brushPressureOpacity, brushPressureFlow, brushPressureCurve)
        }
    }

    internal fun PaintViewModel.updateBrushTipAsset(asset: String) {
        brushTipAsset = asset
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) { ReverieCoreBridge.setBrushTipAsset(asset) }
    }

    internal fun PaintViewModel.updateBrushPaintOpId(id: String) {
        val resolved = if (id == "defaultpaintop") "paintbrush" else id
        brushPaintOpId = resolved
        saveBrushParam(reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushAirbrush(v: Boolean) {
        brushAirbrush = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
        runCore(render = false) { ReverieCoreBridge.setBrushAirbrush(v, brushAirbrushRate) }
    }

    internal fun PaintViewModel.updateBrushAirbrushRate(v: Double) {
        brushAirbrushRate = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushAirbrush(brushAirbrush, v) }
    }

    internal fun PaintViewModel.updateBrushSmudgeRate(v: Double) {
        brushSmudgeRate = v
        saveBrushParam(smudgeChanged = true)
        runCore(render = false) { ReverieCoreBridge.setBrushSmudgeRate(v) }
    }

    internal fun PaintViewModel.updateBrushSmudgeLength(v: Double) {
        brushSmudgeLength = v
        saveBrushParam(smudgeChanged = true)
        runCore(render = false) { ReverieCoreBridge.setBrushSmudgeLength(v) }
    }

    internal fun PaintViewModel.updateBrushColorRate(v: Double) {
        brushColorRate = v.coerceIn(0.0, 1.0)
        saveBrushParam(smudgeChanged = true)
        runCore(render = false) { ReverieCoreBridge.setBrushSmudgeRate(brushColorRate) }
    }

    internal fun PaintViewModel.updateBrushSmudgeMode(v: Int) {
        brushSmudgeMode = v.coerceIn(0, 1)
        saveBrushParam(smudgeChanged = true)
    }

    internal fun PaintViewModel.updateBrushSpikes(v: Int) {
        brushSpikes = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushJitterAngle(v: Double) {
        brushJitterAngle = v
        saveBrushParam(reloadEngine = true)
        runCore(render = false) { ReverieCoreBridge.setBrushJitter(brushJitterAngle, brushJitterSize) }
    }

    internal fun PaintViewModel.updateBrushMinSizeLimit(v: Double) {
        brushMinSizeLimit = v
        if (brushSize < v) updateBrushSize(v)
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushMaxSizeLimit(v: Double) {
        brushMaxSizeLimit = v
        if (brushSize > effectiveBrushMaxSize) updateBrushSize(effectiveBrushMaxSize)
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushAuthor(v: String) {
        if (brushIsAuthorLocked) return
        brushAuthor = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushDescription(v: String) {
        brushDescription = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushVersion(v: String) {
        brushVersion = v
        saveBrushParam()
    }

    internal fun PaintViewModel.updateBrushMaskingEnabled(v: Boolean) {
        brushMaskingEnabled = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushMaskingCompositeOp(v: String) {
        brushMaskingCompositeOp = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushMaskingSizeRatio(v: Double) {
        brushMaskingSizeRatio = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushMaskingSpacing(v: Double) {
        brushMaskingSpacing = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushMaskingTipAsset(asset: String) {
        brushMaskingTipAsset = asset
        saveBrushParam(reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushMaskingFade(v: Double) {
        brushMaskingFade = v
        saveBrushParam(reloadEngine = true)
    }

    internal fun PaintViewModel.updateBrushRotationSensor(v: String) {
        brushRotationSensor = v
        saveBrushParam(reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushSizeSensor(v: String) {
        brushSizeSensor = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushOpacitySensor(v: String) {
        brushOpacitySensor = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushFlowSensor(v: String) {
        brushFlowSensor = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushScatterSensor(v: String) {
        brushScatterSensor = v
        saveBrushParam(dynamicsChanged = true, reloadEngine = true, immediateReload = true)
    }

    internal fun PaintViewModel.updateBrushStreamline(v: Double) {
        brushStreamline = v
        saveBrushParam()
    }

    /** Capture scratchpad raster as the preset's official PNG thumbnail */
    fun PaintViewModel.capturePresetThumbnail(bitmap: Bitmap): Boolean {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return false
        val dir = File(appContext.filesDir, "paintoppresets")
        val kppFile = File(dir, "${preset.name}.kpp")
        if (!kppFile.exists()) {
            try {
                appContext.assets.open("paintoppresets/${preset.name}.kpp").use { input ->
                    kppFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {}
        }
        if (!kppFile.exists()) return false

        val targetSize = 256
        val thumbBmp = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val stream = java.io.ByteArrayOutputStream()
        thumbBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        val success = KppHelper.updateKppThumbnail(kppFile, pngBytes)
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
        }
        return success
    }

    internal var pendingKppReloadJob: Job? = null

    /**
     * 把内存里的动力学曲线序列化成 Krita sensor param 全文, 供 kpp 落盘。
     *
     * 曲线本身住在 `brushDynamicOptions` (DynamicOptionConfig.points), 而 Krita 把它放在
     * `<Key>Sensor` param 的 XML 里, 由 `<Key>UseCurve` 开关。此前 updateBrushDynamicOption
     * 只写内存 + 下发引擎, 不落盘, 于是切笔刷/重启后曲线丢失。
     * 只有"已启用"或"曲线非线性"的选项才写, 避免把一堆恒等曲线塞进每个预设。
     */
    private fun PaintViewModel.buildDynamicOptionSensorXml(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((key, cfg) in brushDynamicOptions) {
            if (key.isBlank()) continue
            val curve = cfg.toKritaCurveString()
            val nonLinear = curve != "0.000,0.000;1.000,1.000;"
            if (!cfg.enabled && !nonLinear) continue
            val id = cfg.sensorId.ifBlank { "pressure" }
            out[key] = "<!DOCTYPE params><params id=\"$id\"><curve>$curve</curve></params>"
        }
        return out
    }

    /**
     * 将预设中的传感器 XML (SizeSensor/SpacingSensor 等) 反向灌回 UI 状态。
     * 每次切换笔刷时必须先彻底重置内存 Map，彻底杜绝跨笔刷曲线污染。
     */
    internal fun PaintViewModel.applySensorXmlToDynamicOptions(sensorXml: Map<String, String>) {
        val standardKeys = listOf("Size", "Opacity", "Flow", "Spacing", "Scatter", "Rotation", "SmudgeRate", "ColorRate")
        val newMap = mutableMapOf<String, com.reverie.paint.model.DynamicOptionConfig>()
        for (k in standardKeys) {
            val defSensor = when (k) {
                "Rotation" -> com.reverie.paint.model.BrushSensor.DRAWING_ANGLE.id
                "Scatter" -> com.reverie.paint.model.BrushSensor.FUZZY.id
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
            val id = Regex("""id="([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
            val curve = Regex("""<curve>([^<]*)</curve>""").find(body)?.groupValues?.getOrNull(1)
            if (curve.isNullOrBlank()) continue
            val points = com.reverie.paint.model.DynamicOptionConfig.parseKritaCurve(curve)
            val existing = newMap[key]
            newMap[key] = (existing ?: com.reverie.paint.model.DynamicOptionConfig(optionKey = key)).copy(
                enabled = true,
                sensorId = id ?: existing?.sensorId ?: "pressure",
                points = points,
            )
        }
        brushDynamicOptions.clear()
        brushDynamicOptions.putAll(newMap)
    }

    internal fun PaintViewModel.saveBrushParam(
        dynamicsChanged: Boolean = false,
        smudgeChanged: Boolean = false,
        spacingChanged: Boolean = false,
        reloadEngine: Boolean = false,
        immediateReload: Boolean = false,
    ) {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return
        val name = preset.name
        val isEraserPreset = preset.group == "橡皮擦" || name.startsWith("a)_Eraser", ignoreCase = true) || name.contains("Eraser", ignoreCase = true)
        val existing = brushParams[name]
        val dc = dynamicsChanged || (existing?.dynamicsCustomized == true)
        val sc = smudgeChanged || (existing?.smudgeCustomized == true)
        val spc = spacingChanged || (existing?.spacingCustomized == true)
        val p = BrushParams(
            dynamicOptions = buildDynamicOptionSensorXml(),
            size = brushSize,
            opacity = brushOpacity,
            flow = brushFlow,
            spacing = brushSpacing,
            angle = brushAngle,
            scatter = brushScatter,
            fade = brushFade,
            softness = brushSoftness,
            ratio = brushRatio,
            sharpness = brushSharpness,
            rotation = brushRotation,
            compositeOp = if (isEraserPreset) "erase" else if (brushCompositeOp != "erase") brushCompositeOp else (existing?.compositeOp?.takeIf { it != "erase" } ?: "normal"),
            antiAliasing = brushAntiAliasing,
            tipShape = brushTipShape,
            randomFlipX = brushRandomFlipX,
            randomFlipY = brushRandomFlipY,
            followDirection = brushFollowDirection,
            streamline = brushStreamline,
            taper = brushTaper,
            textureEnabled = brushTextureEnabled,
            textureScale = brushTextureScale,
            textureStrength = brushTextureStrength,
            textureMode = brushTextureMode,
            texturePattern = brushTexturePattern,
            hueJitter = brushHueJitter,
            satJitter = brushSatJitter,
            valJitter = brushValJitter,
            secondaryMix = brushSecondaryMix,
            pressureColorMix = brushPressureColorMix,
            pressureEnabled = brushPressureEnabled,
            pressureSize = brushPressureSize,
            pressureOpacity = brushPressureOpacity,
            pressureFlow = brushPressureFlow,
            speedSize = brushSpeedSize,
            pressureCurve = brushPressureCurve,
            minSizeLimit = brushMinSizeLimit,
            maxSizeLimit = brushMaxSizeLimit,
            tipAsset = brushTipAsset,
            paintOpId = brushPaintOpId,
            airbrush = brushAirbrush,
            airbrushRate = brushAirbrushRate,
            smudgeRate = brushSmudgeRate,
            smudgeLength = brushSmudgeLength,
            colorRate = brushColorRate,
            smudgeMode = brushSmudgeMode,
            spikes = brushSpikes,
            jitterAngle = brushJitterAngle,
            jitterSize = brushJitterSize,
            author = brushAuthor,
            isAuthorLocked = brushIsAuthorLocked,
            description = brushDescription,
            version = brushVersion,
            isCustomized = true,
            dynamicsCustomized = dc,
            smudgeCustomized = sc,
            spacingCustomized = spc,
            maskingEnabled = brushMaskingEnabled,
            maskingCompositeOp = brushMaskingCompositeOp,
            maskingSizeRatio = brushMaskingSizeRatio,
            maskingSpacing = brushMaskingSpacing,
            maskingTipAsset = brushMaskingTipAsset,
            maskingTipShape = brushMaskingTipShape,
            maskingFade = brushMaskingFade,
            maskingSoftness = brushMaskingSoftness,
            rotationSensor = brushRotationSensor,
            scatterSensor = brushScatterSensor,
            sizeSensor = brushSizeSensor,
            opacitySensor = brushOpacitySensor,
            flowSensor = brushFlowSensor,
        )
        brushParams[name] = p
        schedulePersistBrushParams()
        val dir = File(appContext.filesDir, "paintoppresets")
        val kppFile = File(dir, "$name.kpp")
        if (kppFile.exists()) {
            val targetIdx = brushPresetIndex
            if (reloadEngine) {
                pendingKppReloadJob?.cancel()
                val delayMs = if (immediateReload) 0L else 120L
                pendingKppReloadJob = viewModelScope.launch(Dispatchers.Default) {
                    if (delayMs > 0) delay(delayMs)
                    if (brushPresetIndex != targetIdx) return@launch
                    runCore(render = false) {
                        if (brushPresetIndex != targetIdx) return@runCore
                        // A spacing/size edit may arrive during the debounce without requesting a
                        // reload. Read the latest immutable snapshot so it is not overwritten here.
                        val pSnapshot = brushParams[name] ?: return@runCore
                        KppHelper.updateKppFile(kppFile, name, pSnapshot)
                        if (ReverieCoreBridge.loadBrushPreset(targetIdx)) {
                            ReverieCoreBridge.setPresetIsEraser(isEraserPreset)
                            ReverieCoreBridge.setBrushColor(brushColor)
                            ReverieCoreBridge.setBrushSecondaryColor(brushSecondaryColor)
                            ReverieCoreBridge.setBrushSize(pSnapshot.size)
                            ReverieCoreBridge.setBrushOpacity(pSnapshot.opacity)
                            ReverieCoreBridge.setBrushFlow(pSnapshot.flow)
                            if (pSnapshot.spacingCustomized) {
                                ReverieCoreBridge.setBrushSpacing(pSnapshot.spacing)
                            }
                            ReverieCoreBridge.setBrushAngle(pSnapshot.angle)
                            if (pSnapshot.dynamicsCustomized) {
                                ReverieCoreBridge.setBrushScatter(pSnapshot.scatter)
                            }
                            ReverieCoreBridge.setBrushFade(pSnapshot.fade)
                            ReverieCoreBridge.setBrushSoftness(pSnapshot.softness)
                            ReverieCoreBridge.setBrushRatio(pSnapshot.ratio)
                            ReverieCoreBridge.setBrushSharpness(pSnapshot.sharpness)
                            ReverieCoreBridge.setBrushRotation(pSnapshot.rotation)
                            ReverieCoreBridge.setBrushCompositeOp(brushCompositeOp)
                            if (pSnapshot.dynamicsCustomized) {
                                if (pSnapshot.dynamicOptions.isNotEmpty()) {
                                    for ((opt, xml) in pSnapshot.dynamicOptions) {
                                        val sensorId = Regex("""id="([^"]+)"""").find(xml)?.groupValues?.getOrNull(1) ?: "pressure"
                                        val curve = Regex("""<curve>([^<]*)</curve>""").find(xml)?.groupValues?.getOrNull(1) ?: "0,0;1,1;"
                                        val strength = when (opt.lowercase()) {
                                            "size" -> pSnapshot.pressureSize
                                            "opacity" -> pSnapshot.pressureOpacity
                                            "flow" -> pSnapshot.pressureFlow
                                            else -> 1.0
                                        }
                                        ReverieCoreBridge.setBrushOptionDynamics(
                                            opt,
                                            true,
                                            sensorId,
                                            curve,
                                            strength,
                                        )
                                    }
                                } else {
                                    ReverieCoreBridge.setBrushPressureDynamics(
                                        pSnapshot.pressureEnabled,
                                        pSnapshot.pressureSize,
                                        pSnapshot.pressureOpacity,
                                        pSnapshot.pressureFlow,
                                        pSnapshot.pressureCurve,
                                    )
                                }
                            }
                            ReverieCoreBridge.setBrushTexture(
                                pSnapshot.textureEnabled,
                                pSnapshot.textureScale,
                                pSnapshot.textureStrength,
                                pSnapshot.textureMode,
                                pSnapshot.texturePattern,
                            )
                            ReverieCoreBridge.setBrushFollowDirection(pSnapshot.followDirection)
                            ReverieCoreBridge.setBrushMirror(pSnapshot.randomFlipX, pSnapshot.randomFlipY)
                            ReverieCoreBridge.setBrushAntiAliasing(pSnapshot.antiAliasing)
                            ReverieCoreBridge.setBrushJitter(pSnapshot.jitterAngle, pSnapshot.jitterSize)
                            ReverieCoreBridge.setBrushSmudgeRate(pSnapshot.colorRate)
                            ReverieCoreBridge.setBrushSmudgeLength(pSnapshot.smudgeLength)
                            ReverieCoreBridge.setBrushAirbrush(pSnapshot.airbrush, pSnapshot.airbrushRate)
                        }
                    }
                }
            } else {
                runCore(render = false) {
                    KppHelper.updateKppFile(kppFile, name, p)
                }
            }
        }
    }

