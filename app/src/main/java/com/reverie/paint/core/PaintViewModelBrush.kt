/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

    internal fun PaintViewModel.prefs() =
        appContext.getSharedPreferences("brush_groups", android.content.Context.MODE_PRIVATE)

    internal fun PaintViewModel.loadBrushGroups() {
        val groupsJson = prefs().getString("user_groups", null)
        val customsJson = prefs().getString("custom_groups", null)
        userBrushGroups = if (groupsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(groupsJson)
                (0 until arr.length()).associate { i ->
                    val o = arr.getJSONObject(i)
                    o.getString("n") to o.getString("g")
                }
            }.getOrDefault(emptyMap())
        } else emptyMap()
        customBrushGroups = if (customsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(customsJson)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    internal fun PaintViewModel.saveBrushGroups() {
        try {
            val arr = org.json.JSONArray()
            for ((n, g) in userBrushGroups) {
                arr.put(org.json.JSONObject().put("n", n).put("g", g))
            }
            val cust = org.json.JSONArray()
            for (g in customBrushGroups) cust.put(g)
            prefs().edit()
                .putString("user_groups", arr.toString())
                .putString("custom_groups", cust.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushGroups failed", e)
        }
    }

    /** Create a new user brush group. Returns false if the name exists. */
    internal fun PaintViewModel.createBrushGroup(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (customBrushGroups.contains(n) || n == "全部") return false
        customBrushGroups = customBrushGroups + n
        saveBrushGroups()
        return true
    }

    /** Move a preset into a group (or back to its inferred group). */
    internal fun PaintViewModel.moveBrushToGroup(presetName: String, group: String) {
        userBrushGroups = userBrushGroups + (presetName to group)
        saveBrushGroups()
        // Refresh the displayed group of this preset
        brushPresets = brushPresets.map {
            if (it.name == presetName) it.copy(group = group) else it
        }
    }

    internal fun PaintViewModel.saveBrushOrder() {
        try {
            val arr = org.json.JSONArray()
            for (n in brushOrder) arr.put(n)
            prefs().edit().putString("brush_order", arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushOrder failed", e)
        }
    }

    /** Move a preset up/down within its current list position. */
    internal fun PaintViewModel.moveBrushUp(presetName: String) {
        reorderBrush(presetName, -1)
    }

    internal fun PaintViewModel.moveBrushDown(presetName: String) {
        reorderBrush(presetName, 1)
    }

    internal fun PaintViewModel.reorderBrush(presetName: String, delta: Int) {
        val cur = brushPresets
        val idx = cur.indexOfFirst { it.name == presetName }
        val to = idx + delta
        if (idx < 0 || to < 0 || to >= cur.size) return
        val newList = cur.toMutableList()
        val t = newList[idx]
        newList[idx] = newList[to]
        newList[to] = t
        brushPresets = newList
        brushOrder = newList.map { it.name }
        saveBrushOrder()
    }

    /** 拖拽直接指定位置重排序 */
    internal fun PaintViewModel.reorderBrushPresets(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in brushPresets.indices || toIndex !in brushPresets.indices) return
        val newList = brushPresets.toMutableList()
        val item = newList.removeAt(fromIndex)
        newList.add(toIndex, item)
        brushPresets = newList
        brushOrder = newList.map { it.name }
        saveBrushOrder()
    }

    internal fun PaintViewModel.saveCategoryOrder() {
        try {
            val arr = org.json.JSONArray()
            for (c in categoryOrder) arr.put(c)
            prefs().edit().putString("category_order", arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    internal fun PaintViewModel.loadCategoryOrder() {
        try {
            val raw = prefs().getString("category_order", null) ?: return
            val arr = org.json.JSONArray(raw)
            categoryOrder = (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
        }
    }

    internal fun PaintViewModel.reorderCategory(from: Int, to: Int, allCategories: List<String>) {
        val list = allCategories.toMutableList()
        if (from == to || from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        categoryOrder = list
        saveCategoryOrder()
    }

    internal fun PaintViewModel.moveCategoryUp(cat: String, allCategories: List<String>) {
        val idx = allCategories.indexOf(cat)
        if (idx > 0) {
            reorderCategory(idx, idx - 1, allCategories)
        }
    }

    internal fun PaintViewModel.moveCategoryDown(cat: String, allCategories: List<String>) {
        val idx = allCategories.indexOf(cat)
        if (idx >= 0 && idx < allCategories.size - 1) {
            reorderCategory(idx, idx + 1, allCategories)
        }
    }

    internal fun PaintViewModel.renameBrushGroup(oldName: String, newName: String): Boolean {
        if (isBuiltInGroup(oldName)) return false
        val clean = newName.trim().ifEmpty { return false }
        if (clean == oldName) return true
        if (customBrushGroups.contains(oldName)) {
            customBrushGroups = customBrushGroups.map { if (it == oldName) clean else it }
        }
        userBrushGroups = userBrushGroups.mapValues { if (it.value == oldName) clean else it.value }
        saveBrushGroups()
        if (categoryOrder.contains(oldName)) {
            categoryOrder = categoryOrder.map { if (it == oldName) clean else it }
            saveCategoryOrder()
        }
        reloadBrushPresets()
        return true
    }

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

    private var pendingKppReloadJob: Job? = null

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
    private fun PaintViewModel.applySensorXmlToDynamicOptions(sensorXml: Map<String, String>) {
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

    internal fun PaintViewModel.persistBrushParams() {
        try {
            val json = org.json.JSONArray()
            for ((name, p) in brushParams) {
                val o = org.json.JSONObject()
                o.put("n", name)
                o.put("s", p.size)
                o.put("o", p.opacity)
                o.put("f", p.flow)
                o.put("sp", p.spacing)
                o.put("ang", p.angle)
                o.put("sc", p.scatter)
                o.put("fa", p.fade)
                o.put("so", p.softness)
                o.put("ra", p.ratio)
                o.put("sh", p.sharpness)
                o.put("ro", p.rotation)
                o.put("cop", p.compositeOp)
                o.put("aa", p.antiAliasing)
                o.put("ts", p.tipShape)
                o.put("rfx", p.randomFlipX)
                o.put("rfy", p.randomFlipY)
                o.put("fd", p.followDirection)
                o.put("sl", p.streamline)
                o.put("tp", p.taper)
                o.put("te", p.textureEnabled)
                o.put("tscl", p.textureScale)
                o.put("tstr", p.textureStrength)
                o.put("tm", p.textureMode)
                o.put("txp", p.texturePattern)
                o.put("hj", p.hueJitter)
                o.put("sj", p.satJitter)
                o.put("vj", p.valJitter)
                o.put("sm", p.secondaryMix)
                o.put("pcm", p.pressureColorMix)
                o.put("pe", p.pressureEnabled)
                o.put("ps", p.pressureSize)
                o.put("po", p.pressureOpacity)
                o.put("pf", p.pressureFlow)
                o.put("ss", p.speedSize)
                o.put("pc", p.pressureCurve)
                o.put("mins", p.minSizeLimit)
                o.put("maxs", p.maxSizeLimit)
                o.put("ta", p.tipAsset)
                o.put("poid", p.paintOpId)
                o.put("ab", p.airbrush)
                o.put("abr", p.airbrushRate)
                o.put("smr", p.smudgeRate)
                o.put("sml", p.smudgeLength)
                o.put("spk", p.spikes)
                o.put("ja", p.jitterAngle)
                o.put("js", p.jitterSize)
                o.put("aut", p.author)
                o.put("autl", p.isAuthorLocked)
                o.put("desc", p.description)
                o.put("ver", p.version)
                o.put("cus", p.isCustomized)
                o.put("dc", p.dynamicsCustomized)
                o.put("scus", p.smudgeCustomized)
                o.put("spc", p.spacingCustomized)
                o.put("m_en", p.maskingEnabled)
                o.put("m_op", p.maskingCompositeOp)
                o.put("m_sr", p.maskingSizeRatio)
                o.put("m_sp", p.maskingSpacing)
                o.put("m_ta", p.maskingTipAsset)
                o.put("m_ts", p.maskingTipShape)
                o.put("m_fa", p.maskingFade)
                o.put("m_so", p.maskingSoftness)
                o.put("r_se", p.rotationSensor)
                o.put("sc_se", p.scatterSensor)
                o.put("sz_se", p.sizeSensor)
                o.put("op_se", p.opacitySensor)
                o.put("fl_se", p.flowSensor)
                o.put("cr", p.colorRate)
                o.put("smm", p.smudgeMode)
                if (p.dynamicOptions.isNotEmpty()) {
                    val dynObj = org.json.JSONObject()
                    for ((k, v) in p.dynamicOptions) dynObj.put(k, v)
                    o.put("dyn_opt", dynObj)
                }
                json.put(o)
            }
            prefs().edit().putString("brush_params", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.persistToolBrushStates() {
        try {
            val json = org.json.JSONArray()
            for ((id, s) in toolBrushStates) {
                val o = org.json.JSONObject()
                o.put("id", id)
                o.put("pi", s.presetIndex)
                o.put("c", s.category)
                o.put("csi", s.categoryScrollIndex)
                o.put("cso", s.categoryScrollOffset)
                o.put("psi", s.presetScrollIndex)
                o.put("pso", s.presetScrollOffset)
                if (s.paramMemory.isNotEmpty()) {
                    val pm = org.json.JSONArray()
                    for ((n, v) in s.paramMemory) {
                        if (v.size >= 3) {
                            pm.put(org.json.JSONArray().apply {
                                put(n); put(v[0]); put(v[1]); put(v[2])
                            })
                        }
                    }
                    o.put("pm", pm)
                }
                json.put(o)
            }
            prefs().edit().putString("tool_brush_states", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.savePinnedTools(tools: List<com.reverie.paint.model.Tool>) {
        pinnedTools = tools
        try {
            val ids = tools.map { it.id }.joinToString(",")
            prefs().edit().putString("pinned_tools", ids).apply()
        } catch (_: Exception) {
        }
    }

    internal fun PaintViewModel.loadPinnedTools() {
        try {
            val pinned = prefs().getString("pinned_tools", null) ?: return
            val ids = pinned.split(",").filter { it.isNotEmpty() }
            pinnedTools = ids.mapNotNull { com.reverie.paint.model.Tool.fromId(it) }
        } catch (_: Exception) {
        }
    }

    /**
     * 一次性迁移 (2026-09): 旧版 updateParam 因把属性顺序写死成 `type` 在前而从未匹配成功，
     * 全部参数修改都被追加成重复键写进 .kpp（引擎按文档序取最后一个 → 新旧值谁生效看运气，
     * 旧默认 softness≈0.5 由此混进文件，实心核只剩半径的一半，大笔触边缘明显发糊）。
     *
     * 二轮 (2026-09)：① 内置预设文件整体回滚为 asset 原始内容 —— 旧版把实心率 fade 当羽化量
     * 写成 0（= 全羽化），且部分文件的 brush_definition 已被引擎 40px 兜底笔尖污染；
     * 用户自定义参数都存在 brushParams 里，选笔时经 setter 重新下发，回滚是安全的。
     * ② 去重清扫（非内置文件也可能带重复键）。
     */
    internal fun PaintViewModel.migrateDuplicatedPresetParams(dir: File) {
        val dedupeDone = prefs().getBoolean("kpp_dedupe_migrated", false)
        val rollbackDone = prefs().getBoolean("kpp_edge_semantics_migrated", false)
        val jitterCleanDone = prefs().getBoolean("kpp_jitter_clean_migrated", false)
        val spacingCleanDone = prefs().getBoolean("kpp_spacing_clean_migrated", false)
        if (dedupeDone && rollbackDone && jitterCleanDone && spacingCleanDone) return
        var fixed = 0
        try {
            if (!rollbackDone || !jitterCleanDone || !spacingCleanDone) {
                for (name in appContext.assets.list("paintoppresets") ?: emptyArray()) {
                    val target = File(dir, name)
                    appContext.assets.open("paintoppresets/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            dir.listFiles { f -> f.isFile && f.name.endsWith(".kpp") }?.forEach { f ->
                if (KppHelper.dedupePresetFile(f)) fixed++
            }
        } catch (_: Exception) {
        }
        prefs().edit().putBoolean("kpp_dedupe_migrated", true).apply()
        prefs().edit().putBoolean("kpp_edge_semantics_migrated", true).apply()
        prefs().edit().putBoolean("kpp_jitter_clean_migrated", true).apply()
        prefs().edit().putBoolean("kpp_spacing_clean_migrated", true).apply()
        android.util.Log.d("ReveriePaint", "kpp migration: dedupe fixed=$fixed")
    }

    internal fun PaintViewModel.loadBrushParams() {
        // 一次性迁移 (2026-09): 旧版 softness 通道从未生效 (PressureSoftness 没写)，
        // 但旧默认值 0.5 被随任意一次保存写进了全部记录。通道打通后这些旧值会让
        // 所有笔刷边缘变糊 (KisCircleMaskGenerator 的实心核 = fade × softness × 半径)，
        // 这里统一归一为 neutral(1.0 = 不改动笔尖羽化)。
        val resetLegacySoftness = !prefs().getBoolean("brush_softness_neutral_migrated", false)
        // 一次性迁移 (2026-09 二轮): fade(hfade) 实为"实心率"(1=锐利, 0=全羽化，实测
        // Eraser_hard=1.0 / Eraser_Soft=0.0)，而旧默认值 0.0 会在每次选笔时经
        // setBrushFade(0.0) 把笔尖打到全羽化 —— 这就是大笔触边缘发糊的直接来源。
        val resetLegacyFade = !prefs().getBoolean("brush_fade_solidity_migrated", false)
        // 一次性迁移: 修复因未判断 Pressureh/PressureMix 开关导致的 100% 杂色抖动与副色混合残留
        val resetLegacyJitter = !prefs().getBoolean("brush_jitter_mix_migrated", false)
        // 一次性迁移: 修复自动间距错误换算与历史脏数据导致间距异常变点状的问题
        val resetLegacySpacing = !prefs().getBoolean("brush_spacing_dotted_migrated_v4", false)
        // 一次性迁移: 修复出厂预设未勾选散布但存在历史非零 ScatterValue 导致笔刷变成散点的问题
        val resetLegacyScatter = !prefs().getBoolean("brush_scatter_migrated_v1", false)
        try {
            val raw = prefs().getString("brush_params", null) ?: return
            val json = org.json.JSONArray(raw)
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val name = o.getString("n")
                val isEraser = name.startsWith("a)_") || name.contains("Eraser", ignoreCase = true)
                val rawCop = o.optString("cop", "normal")
                val rawSp = o.optDouble("sp", 0.1)
                val spc = o.optBoolean("spc", false)
                val healedSpacing = if (!spc || resetLegacySpacing) 0.1 else rawSp
                val dc = o.optBoolean("dc", false)
                val rawSc = o.optDouble("sc", 0.0)
                val healedScatter = if (!dc || resetLegacyScatter || rawSc >= 2.0) 0.0 else rawSc
                brushParams[name] = BrushParams(
                    size = o.optDouble("s", 20.0),
                    opacity = o.optDouble("o", 1.0),
                    flow = o.optDouble("f", 1.0),
                    spacing = healedSpacing,
                    angle = o.optDouble("ang", 0.0),
                    scatter = healedScatter,
                    fade = if (resetLegacyFade) KppHelper.FADE_SOLID else o.optDouble("fa", KppHelper.FADE_SOLID),
                    softness = if (resetLegacySoftness) KppHelper.SOFTNESS_NEUTRAL else o.optDouble("so", KppHelper.SOFTNESS_NEUTRAL),
                    ratio = o.optDouble("ra", 1.0),
                    sharpness = o.optDouble("sh", 0.0),
                    rotation = o.optDouble("ro", 0.0),
                    compositeOp = if (!isEraser && rawCop == "erase") "normal" else rawCop,
                    antiAliasing = o.optInt("aa", 1),
                    tipShape = o.optInt("ts", 0),
                    randomFlipX = o.optBoolean("rfx", false),
                    randomFlipY = o.optBoolean("rfy", false),
                    followDirection = o.optBoolean("fd", false),
                    streamline = o.optDouble("sl", 0.0),
                    taper = o.optDouble("tp", 0.0),
                    textureEnabled = o.optBoolean("te", false),
                    textureScale = o.optDouble("tscl", 1.0),
                    textureStrength = o.optDouble("tstr", 0.5),
                    textureMode = o.optString("tm", "multiply"),
                    texturePattern = o.optString("txp", ""),
                    hueJitter = if (resetLegacyJitter) 0.0 else o.optDouble("hj", 0.0),
                    satJitter = if (resetLegacyJitter) 0.0 else o.optDouble("sj", 0.0),
                    valJitter = if (resetLegacyJitter) 0.0 else o.optDouble("vj", 0.0),
                    secondaryMix = if (resetLegacyJitter) 0.0 else o.optDouble("sm", 0.0),
                    pressureColorMix = o.optBoolean("pcm", false),
                    pressureEnabled = o.optBoolean("pe", true),
                    pressureSize = o.optDouble("ps", 1.0),
                    pressureOpacity = o.optDouble("po", 1.0),
                    pressureFlow = o.optDouble("pf", 1.0),
                    speedSize = o.optDouble("ss", 0.0),
                    pressureCurve = o.optInt("pc", 0),
                    minSizeLimit = o.optDouble("mins", 1.0),
                    maxSizeLimit = o.optDouble("maxs", 500.0),
                    tipAsset = o.optString("ta", ""),
                    paintOpId = o.optString("poid", "defaultpaintop"),
                    airbrush = o.optBoolean("ab", false),
                    airbrushRate = o.optDouble("abr", 30.0).let { if (it < 5.0) 30.0 else it },
                    smudgeRate = o.optDouble("smr", 0.5),
                    smudgeLength = o.optDouble("sml", 0.5),
                    spikes = o.optInt("spk", 2),
                    jitterAngle = o.optDouble("ja", 0.0),
                    jitterSize = o.optDouble("js", 0.0),
                    author = o.optString("aut", "ReveriePaint"),
                    isAuthorLocked = o.optBoolean("autl", false),
                    description = o.optString("desc", ""),
                    version = o.optString("ver", "1.0"),
                    isCustomized = run {
                        if (o.has("cus")) {
                            o.optBoolean("cus", false)
                        } else {
                            // Legacy cache without "cus": heal contaminated imported/default presets
                            val aut = o.optString("aut", "")
                            val s = o.optDouble("s", 20.0)
                            !(aut == "外部创作者 (分享)" || (aut == "ReveriePaint" && s == 20.0))
                        }
                    },
                    dynamicsCustomized = o.optBoolean("dc", false),
                    smudgeCustomized = o.optBoolean("scus", false),
                    spacingCustomized = if (resetLegacySpacing) false else spc,
                    maskingEnabled = o.optBoolean("m_en", false),
                    maskingCompositeOp = o.optString("m_op", "multiply"),
                    maskingSizeRatio = o.optDouble("m_sr", 1.0),
                    maskingSpacing = o.optDouble("m_sp", 0.1),
                    maskingTipAsset = o.optString("m_ta", ""),
                    maskingTipShape = o.optInt("m_ts", 0),
                    maskingFade = o.optDouble("m_fa", 0.0),
                    maskingSoftness = o.optDouble("m_so", 1.0),
                    rotationSensor = o.optString("r_se", "drawingangle"),
                    scatterSensor = o.optString("sc_se", "fuzzy"),
                    sizeSensor = o.optString("sz_se", "pressure"),
                    opacitySensor = o.optString("op_se", "pressure"),
                    flowSensor = o.optString("fl_se", "pressure"),
                    colorRate = o.optDouble("cr", 0.5),
                    smudgeMode = o.optInt("smm", 0),
                    dynamicOptions = run {
                        val dynObj = o.optJSONObject("dyn_opt")
                        if (dynObj != null) {
                            val map = mutableMapOf<String, String>()
                            val keys = dynObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                map[k] = dynObj.getString(k)
                            }
                            map
                        } else emptyMap()
                    },
                )
            }
            if (resetLegacySoftness || resetLegacyFade || resetLegacyJitter || resetLegacySpacing || resetLegacyScatter) {
                prefs().edit().putBoolean("brush_softness_neutral_migrated", true).apply()
                prefs().edit().putBoolean("brush_fade_solidity_migrated", true).apply()
                prefs().edit().putBoolean("brush_jitter_mix_migrated", true).apply()
                prefs().edit().putBoolean("brush_spacing_dotted_migrated_v4", true).apply()
                prefs().edit().putBoolean("brush_scatter_migrated_v1", true).apply()
                persistBrushParams()
            }
        } catch (_: Exception) {
        }
        
        try {
            val raw = prefs().getString("tool_brush_states", null) ?: return
            val json = org.json.JSONArray(raw)
            val map = mutableMapOf<String, PaintViewModel.ToolBrushState>()
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                val pmJson = o.optJSONArray("pm")
                var paramMemory: Map<String, List<Double>> = emptyMap()
                if (pmJson != null) {
                    val m = mutableMapOf<String, List<Double>>()
                    for (j in 0 until pmJson.length()) {
                        val e = pmJson.optJSONArray(j) ?: continue
                        if (e.length() >= 4) {
                            m[e.optString(0)] = listOf(e.optDouble(1), e.optDouble(2), e.optDouble(3))
                        }
                    }
                    paramMemory = m
                }
                map[o.getString("id")] = PaintViewModel.ToolBrushState(
                    presetIndex = o.optInt("pi", -1),
                    category = o.optString("c", "全部"),
                    categoryScrollIndex = o.optInt("csi", 0),
                    categoryScrollOffset = o.optInt("cso", 0),
                    presetScrollIndex = o.optInt("psi", 0),
                    presetScrollOffset = o.optInt("pso", 0),
                    paramMemory = paramMemory
                )
            }
            toolBrushStates = map
            val curId = prefs().getString("current_tool_id", "brush") ?: "brush"
            val activeState = map[curId] ?: map["brush"]
            if (activeState != null) {
                brushPanelSelectedCategory = activeState.category
                brushCategoryScrollIndex = activeState.categoryScrollIndex
                brushCategoryScrollOffset = activeState.categoryScrollOffset
                brushPresetScrollIndex = activeState.presetScrollIndex
                brushPresetScrollOffset = activeState.presetScrollOffset
            }
        } catch (_: Exception) {
        }
        
        try {
            currentToolId = prefs().getString("current_tool_id", "brush") ?: "brush"
        } catch (_: Exception) {
        }
    }

    /** Reset all parameters for the current brush back to factory defaults */
    internal fun PaintViewModel.resetBrushParams() {
        val index = brushPresetIndex
        val preset = brushPresets.firstOrNull { it.index == index }
        if (preset != null) {
            val dir = File(appContext.filesDir, "paintoppresets")
            val target = File(dir, "${preset.name}.kpp")
            try {
                val assetNames = appContext.assets.list("paintoppresets") ?: emptyArray()
                if (assetNames.contains("${preset.name}.kpp")) {
                    appContext.assets.open("paintoppresets/${preset.name}.kpp").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            } catch (_: Exception) {}

            brushParams.remove(preset.name)
            persistBrushParams()
            toolBrushStates = toolBrushStates.mapValues { (_, s) ->
                s.copy(paramMemory = s.paramMemory - preset.name)
            }
            persistToolBrushStates()
        }
        if (index >= 0) {
            selectBrushPreset(index)
        }
        android.widget.Toast.makeText(
            appContext,
            appContext.getString(R.string.brush_reset_toast),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** Check if a brush preset has modified parameters */
    internal fun PaintViewModel.isBrushModified(name: String): Boolean {
        return brushParams.containsKey(name)
    }

    /** Capture initial parameter snapshot when opening Brush Studio */
    internal fun PaintViewModel.captureBrushStudioSnapshot() {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return
        brushStudioInitialParams = brushParams[preset.name]?.copy()
        val dir = File(appContext.filesDir, "paintoppresets")
        val kppFile = File(dir, "${preset.name}.kpp")
        brushStudioInitialKppBytes = if (kppFile.exists()) kppFile.readBytes() else null
    }

    /** Check if the current brush has changes compared to the studio initial snapshot */
    internal fun PaintViewModel.hasBrushStudioChanges(): Boolean {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return false
        val initial = brushStudioInitialParams
        val current = brushParams[preset.name]
        val paramsChanged = if (initial == null) {
            current != null
        } else {
            current != initial
        }
        if (paramsChanged) return true
        val kppBytes = brushStudioInitialKppBytes ?: return false
        val dir = File(appContext.filesDir, "paintoppresets")
        val kppFile = File(dir, "${preset.name}.kpp")
        return !kppFile.exists() || !kppFile.readBytes().contentEquals(kppBytes)
    }

    /** Revert parameters to the initial state when Brush Studio was entered */
    internal fun PaintViewModel.revertBrushStudioSnapshot(): Boolean {
        val preset = brushPresets.firstOrNull { it.index == brushPresetIndex } ?: return false
        val initial = brushStudioInitialParams
        if (initial != null) {
            brushParams[preset.name] = initial.copy()
        } else {
            brushParams.remove(preset.name)
        }
        val kppBytes = brushStudioInitialKppBytes
        if (kppBytes != null) {
            val dir = File(appContext.filesDir, "paintoppresets")
            val kppFile = File(dir, "${preset.name}.kpp")
            runCatching { kppFile.writeBytes(kppBytes) }
        }
        persistBrushParams()
        brushDynamicOptions.clear()
        selectBrushPreset(preset.index)
        return true
    }

    /** Reset a brush preset back to factory default parameters */
    internal fun PaintViewModel.resetBrushPresetToDefault(presetName: String) {
        val dir = File(appContext.filesDir, "paintoppresets")
        val target = File(dir, "$presetName.kpp")
        try {
            val assetNames = appContext.assets.list("paintoppresets") ?: emptyArray()
            if (assetNames.contains("$presetName.kpp")) {
                appContext.assets.open("paintoppresets/$presetName.kpp").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (_: Exception) {}

        brushParams.remove(presetName)
        persistBrushParams()
        toolBrushStates = toolBrushStates.mapValues { (_, s) ->
            s.copy(paramMemory = s.paramMemory - presetName)
        }
        persistToolBrushStates()

        val preset = brushPresets.firstOrNull { it.name == presetName }
        if (preset != null && preset.index == brushPresetIndex) {
            selectBrushPreset(preset.index)
        }
        android.widget.Toast.makeText(
            appContext,
            appContext.getString(R.string.brush_reset_toast),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** Share a brush preset (.kpp or .bundle if it has custom tip) via system share sheet */
    internal fun PaintViewModel.shareBrushPreset(context: android.content.Context, presetName: String): Boolean {
        return try {
            val dir = File(appContext.filesDir, "paintoppresets")
            val brushDir = File(appContext.filesDir, "brushes")
            val srcFile = File(dir, "$presetName.kpp")
            if (!srcFile.exists()) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    appContext.assets.open("paintoppresets/$presetName.kpp").use { input ->
                        srcFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                }
            }
            if (!srcFile.exists()) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.brush_share_failed, presetName),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return false
            }

            // Sync latest parameters into .kpp file
            val params = brushParams[presetName]
            if (params != null) {
                KppHelper.updateKppFile(srcFile, presetName, params)
            }

            val tipName = params?.tipAsset?.ifBlank { null }
                ?: KppHelper.extractTipAssetFilename(srcFile.readBytes())

            // If the preset has a custom or separate tip asset, export as .bundle so the tip pattern is preserved
            if (!tipName.isNullOrBlank()) {
                val tipFile = File(brushDir, tipName)
                if (!tipFile.exists()) {
                    try {
                        appContext.assets.open("brushes/$tipName").use { inS ->
                            tipFile.outputStream().use { inS.copyTo(it) }
                        }
                    } catch (_: Exception) {}
                }
                if (tipFile.exists()) {
                    val group = userBrushGroups[presetName] ?: "分享"
                    val bundleFile = KritaBundleManager.exportBundle(
                        context = context,
                        bundleName = presetName,
                        groupName = group,
                        presets = listOf(presetName to srcFile),
                        tipAssets = listOf(tipFile),
                    )
                    return KritaBundleManager.shareFile(
                        context = context,
                        file = bundleFile,
                        mimeType = "application/x-krita-resourcebundle",
                        title = context.getString(R.string.brush_share_title, presetName),
                    )
                }
            }

            // Standard procedural/algorithm brush without external tip: share .kpp
            val exportDir = File(appContext.cacheDir, "export_brushes").apply { if (!exists()) mkdirs() }
            val exportFile = File(exportDir, "$presetName.kpp")
            srcFile.copyTo(exportFile, overwrite = true)

            KritaBundleManager.shareFile(
                context = context,
                file = exportFile,
                mimeType = "application/x-krita-paintoppreset",
                title = context.getString(R.string.brush_share_title, presetName),
            )
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "shareBrushPreset failed", e)
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.brush_share_failed, presetName),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    /** Export an entire brush group as a standard Krita .bundle file */
    internal fun PaintViewModel.exportBrushGroup(context: android.content.Context, groupName: String): Boolean {
        val targetPresets = if (groupName == "全部") {
            brushPresets
        } else {
            brushPresets.filter { it.group == groupName }
        }
        if (targetPresets.isEmpty()) {
            android.widget.Toast.makeText(context, context.getString(R.string.brush_group_empty_export), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }

        val dir = File(appContext.filesDir, "paintoppresets")
        val brushDir = File(appContext.filesDir, "brushes")

        val presetsToPack = mutableListOf<Pair<String, File>>()
        val tipAssetsToPack = mutableListOf<File>()

        for (p in targetPresets) {
            val file = File(dir, "${p.name}.kpp")
            if (!file.exists()) {
                try {
                    appContext.assets.open("paintoppresets/${p.name}.kpp").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                } catch (_: Exception) {}
            }
            if (file.exists()) {
                val params = brushParams[p.name]
                if (params != null) {
                    KppHelper.updateKppFile(file, p.name, params)
                }
                presetsToPack.add(p.name to file)

                val tipName = params?.tipAsset?.ifBlank { null }
                    ?: KppHelper.extractTipAssetFilename(file.readBytes())
                if (!tipName.isNullOrBlank()) {
                    val tipFile = File(brushDir, tipName)
                    if (!tipFile.exists()) {
                        try {
                            appContext.assets.open("brushes/$tipName").use { input ->
                                tipFile.outputStream().use { input.copyTo(it) }
                            }
                        } catch (_: Exception) {}
                    }
                    if (tipFile.exists() && tipAssetsToPack.none { it.name == tipFile.name }) {
                        tipAssetsToPack.add(tipFile)
                    }
                }
            }
        }

        if (presetsToPack.isEmpty()) {
            android.widget.Toast.makeText(context, context.getString(R.string.brush_group_empty_export), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val bundleFile = KritaBundleManager.exportBundle(
                context = context,
                bundleName = groupName,
                groupName = groupName,
                presets = presetsToPack,
                tipAssets = tipAssetsToPack,
            )
            KritaBundleManager.shareFile(
                context = context,
                file = bundleFile,
                mimeType = "application/x-krita-resourcebundle",
                title = context.getString(R.string.brush_share_group_title, groupName),
            )
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "exportBrushGroup failed", e)
            android.widget.Toast.makeText(context, context.getString(R.string.brush_export_group_failed), android.widget.Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Picks the brush size to show when a preset is selected.
     *
     * The engine can report a literal `1.0` that does **not** mean "1 pixel". Two paths produce
     * it: `KisBrushBasedPaintOpSettings::paintOpSize()` returns
     * `KIS_SAFE_ASSERT_RECOVER_RETURN_VALUE(this->brush(), 1.0)` when the brush failed to parse,
     * and the `auto_brush` that `KisBrush::fromXML` falls back to has no `<MaskGenerator>`, whose
     * diameter default happens to be `1.0` as well (`KisAutoBrushFactory` reads
     * `attr("diameter", "1.0")`). Either way "the brush did not resolve" is reported as 1px, and
     * because 1.0 satisfies the generic `size > 0` guard the UI happily parks at the minimum —
     * which is exactly why every imported brush had to be re-adjusted by hand.
     *
     * Only when the engine value is <= 1.0 **and** we actually remember a size > 1.0 for this
     * preset do we substitute the remembered one. A user who deliberately set 1px is not
     * overridden, and built-in presets (no remembered entry) behave exactly as before.
     */
    internal fun resolvePresetSize(engineSize: Double, savedSize: Double?): Double {
        if (engineSize.isFinite() && engineSize > 1.0) return engineSize
        if (savedSize == null || !savedSize.isFinite() || savedSize <= 1.0) return engineSize
        return savedSize
    }

    internal fun PaintViewModel.selectBrushPreset(index: Int) {
        // brushPresetIndex and every native API are indexed by the NATIVE
        // preset table (filename-sorted). Resolve by BrushPresetInfo.index,
        // never by list position: the Compose list may be reordered by the
        // user's brushOrder and diverge from native indices.
        val preset = brushPresets.firstOrNull { it.index == index }
        if (preset != null) {
            recordRecentBrush(preset.name)
        }
        val isBuiltIn = preset?.isBuiltIn == true
        val isEraserPreset = preset?.group == "橡皮擦" || preset?.name?.startsWith("a)_Eraser", ignoreCase = true) == true || preset?.name?.contains("Eraser", ignoreCase = true) == true

        if (preset != null && recorder.recording) {
            // Replay resolves the preset by NAME (the native table may be
            // rebuilt mid-session by BrushStudio mutations), and the context
            // diff reset forces the next stroke to re-send the full parameter
            // context (preset load resets native params during replay).
            recorder.toolOp(com.reverie.paint.model.RecordingEvents.T_PRESET_SELECT) {
                it.u16(if (index < 0) 0xFFFF else index.coerceIn(0, 0xFFFE))
                it.str(preset.name)
            }
            recorder.resetContextDiff()
        }

        pendingKppReloadJob?.cancel()
        pendingKppReloadJob = null

        brushPresetIndex = index
        updateCurrentToolBrushState { it.copy(presetIndex = index) }

        val saved = if (preset != null) brushParams[preset.name] else null
        val isCustomized = saved?.isCustomized == true
        val nativeCompOp = if (index >= 0) ReverieCoreBridge.brushPresetCompositeOp(index) else "normal"
        val effectiveCompOp = if (currentToolId == "eraser" || isEraserPreset) {
            "erase"
        } else {
            val savedOp = saved?.compositeOp
            if (isCustomized && !savedOp.isNullOrBlank() && savedOp != "erase") savedOp else nativeCompOp
        }
        runCore(after = {
            if (saved != null && isCustomized) {
                brushSize = saved.size
                brushOpacity = saved.opacity
                brushFlow = saved.flow
                brushSpacing = if (saved.spacingCustomized) {
                    saved.spacing
                } else {
                    val d = ReverieCoreBridge.brushPresetDefaults(index)
                    val rawSp = d.getOrNull(3) ?: 0.1
                    if (rawSp >= 0.75) 0.1 else rawSp.coerceIn(0.01, 2.5)
                }
                brushAngle = saved.angle
                brushScatter = if (saved.dynamicsCustomized) {
                    saved.scatter.coerceIn(0.0, 1.0)
                } else {
                    val d = ReverieCoreBridge.brushPresetDefaults(index)
                    val rawSc = d.getOrNull(9) ?: 0.0
                    if (rawSc >= 2.0) 0.0 else rawSc.coerceIn(0.0, 1.0)
                }
                brushFade = saved.fade
                brushSoftness = saved.softness
                brushRatio = saved.ratio
                brushSharpness = saved.sharpness
                brushRotation = saved.rotation
                brushCompositeOp = effectiveCompOp
                brushAntiAliasing = saved.antiAliasing
                brushTipShape = saved.tipShape
                brushRandomFlipX = saved.randomFlipX
                brushRandomFlipY = saved.randomFlipY
                brushFollowDirection = saved.followDirection
                brushStreamline = saved.streamline
                brushTaper = saved.taper
                brushTextureEnabled = saved.textureEnabled
                brushTextureScale = saved.textureScale
                brushTextureStrength = saved.textureStrength
                brushTextureMode = saved.textureMode
                brushTexturePattern = saved.texturePattern
                brushHueJitter = saved.hueJitter
                brushSatJitter = saved.satJitter
                brushValJitter = saved.valJitter
                brushSecondaryMix = saved.secondaryMix
                brushPressureColorMix = saved.pressureColorMix
                brushPressureEnabled = saved.pressureEnabled
                brushPressureSize = saved.pressureSize
                brushPressureOpacity = saved.pressureOpacity
                brushPressureFlow = saved.pressureFlow
                brushSpeedSize = saved.speedSize
                brushPressureCurve = saved.pressureCurve
                brushMinSizeLimit = saved.minSizeLimit
                brushMaxSizeLimit = saved.maxSizeLimit
                val realTip = if (index >= 0) ReverieCoreBridge.brushPresetTipFilename(index) else ""
                brushTipAsset = if (saved.tipAsset.isNotBlank()) saved.tipAsset else realTip
                brushPaintOpId = if (saved.paintOpId.isBlank() || saved.paintOpId == "defaultpaintop") {
                    if (index >= 0) ReverieCoreBridge.brushPresetPaintOpId(index) else "paintbrush"
                } else {
                    saved.paintOpId
                }
                brushAirbrush = saved.airbrush
                brushAirbrushRate = if (saved.airbrushRate >= 5.0) saved.airbrushRate else 30.0
                brushSmudgeRate = saved.smudgeRate
                brushSmudgeLength = saved.smudgeLength
                brushSpikes = saved.spikes
                brushJitterAngle = saved.jitterAngle
                brushJitterSize = saved.jitterSize
                brushMaskingEnabled = saved.maskingEnabled
                brushMaskingCompositeOp = saved.maskingCompositeOp
                brushMaskingSizeRatio = saved.maskingSizeRatio
                brushMaskingSpacing = saved.maskingSpacing
                brushMaskingTipAsset = saved.maskingTipAsset
                brushMaskingTipShape = saved.maskingTipShape
                brushMaskingFade = saved.maskingFade
                brushMaskingSoftness = saved.maskingSoftness
                brushRotationSensor = saved.rotationSensor
                brushScatterSensor = saved.scatterSensor
                brushSizeSensor = saved.sizeSensor
                brushOpacitySensor = saved.opacitySensor
                brushFlowSensor = saved.flowSensor
                brushAuthor = if (isBuiltIn) "Krita" else saved.author
                brushIsAuthorLocked = if (isBuiltIn) true else saved.isAuthorLocked
                brushDescription = saved.description
                brushVersion = saved.version
                brushColorRate = saved.colorRate
                brushSmudgeMode = saved.smudgeMode
                val kppFile = preset?.name?.let { File(File(appContext.filesDir, "paintoppresets"), "$it.kpp") }
                val parsed = if (kppFile?.exists() == true) KppHelper.parseKppFile(kppFile) else KppHelper.KppParsedAttributes()
                val effectiveSensors = if (saved.dynamicOptions.isNotEmpty()) saved.dynamicOptions else parsed.sensorXml
                applySensorXmlToDynamicOptions(effectiveSensors)
            } else {
                // 原生 Krita 预设: 读取预设自身在引擎中解析得到的默认参数与 XML 原生配置
                val d = ReverieCoreBridge.brushPresetDefaults(index)
                val kppFile = preset?.name?.let { File(File(appContext.filesDir, "paintoppresets"), "$it.kpp") }
                val parsed = if (kppFile?.exists() == true) KppHelper.parseKppFile(kppFile) else KppHelper.KppParsedAttributes()

                if (d.size >= 8) {
                    brushSize = resolvePresetSize(d[0], saved?.size)
                    brushOpacity = d[1].coerceIn(0.0, 1.0)
                    brushFlow = d[2].coerceIn(0.0, 1.0)
                    val rawSp = d[3]
                    brushSpacing = if (rawSp >= 0.75) 0.1 else rawSp.coerceIn(0.01, 2.5)
                    brushAirbrush = d[4] > 0.5
                    val defaultRate = d[5]
                    brushAirbrushRate = if (defaultRate >= 5.0) defaultRate else 30.0
                    brushSmudgeRate = d[6]
                    brushSmudgeLength = d[7]
                    brushColorRate = d[6]
                    brushSmudgeMode = 0
                } else if (d.size >= 3) {
                    brushSize = resolvePresetSize(d[0], saved?.size)
                    brushOpacity = d[1].coerceIn(0.0, 1.0)
                    brushFlow = d[2].coerceIn(0.0, 1.0)
                    brushSpacing = 0.1
                }
                brushAngle = d.getOrNull(8) ?: 0.0
                val rawSc = d.getOrNull(9) ?: 0.0
                brushScatter = if (rawSc >= 2.0) 0.0 else rawSc.coerceIn(0.0, 1.0)
                // SoftnessValue 缺省即 1.0(neutral/不改动笔尖羽化)；引擎侧兜底报告的是 0.5，
                // 那是"半羽化"而不是中性值，所以这里不拿 d[10] 兜底，直接落到 neutral。
                brushSoftness = parsed.softness ?: KppHelper.SOFTNESS_NEUTRAL
                applySensorXmlToDynamicOptions(parsed.sensorXml)
                brushRatio = parsed.ratio ?: d.getOrNull(11) ?: 1.0
                brushSharpness = d.getOrNull(12) ?: 0.0
                brushRotation = d.getOrNull(13) ?: 0.0
                brushPressureSize = parsed.pressureSize ?: d.getOrNull(14) ?: 1.0
                brushPressureOpacity = parsed.pressureOpacity ?: d.getOrNull(15) ?: 0.0
                brushPressureFlow = parsed.pressureFlow ?: d.getOrNull(16) ?: 0.0
                brushFollowDirection = (d.getOrNull(17) ?: 0.0) > 0.5
                brushRandomFlipX = (d.getOrNull(18) ?: 0.0) > 0.5
                brushRandomFlipY = (d.getOrNull(19) ?: 0.0) > 0.5
                brushAntiAliasing = parsed.antiAliasing ?: if ((d.getOrNull(20) ?: 1.0) > 0.5) 1 else 0

                brushCompositeOp = effectiveCompOp
                brushMinSizeLimit = 1.0
                brushMaxSizeLimit = maxOf(500.0, brushSize)
                brushAuthor = if (isBuiltIn) "Krita" else (saved?.author ?: "外部创作者 (分享)")
                brushIsAuthorLocked = if (isBuiltIn) true else (saved?.isAuthorLocked ?: true)
                val realTip = if (index >= 0) ReverieCoreBridge.brushPresetTipFilename(index) else ""
                brushTipAsset = if (realTip.isNotBlank()) realTip else (saved?.tipAsset ?: "")
                brushPaintOpId = if (index >= 0) ReverieCoreBridge.brushPresetPaintOpId(index) else "paintbrush"

                brushFade = parsed.fade ?: KppHelper.FADE_SOLID
                brushTipShape = parsed.tipShape ?: 0
                brushStreamline = 0.0
                brushTaper = 0.0
                brushTextureEnabled = parsed.textureEnabled ?: false
                brushTextureScale = parsed.textureScale ?: 1.0
                brushTextureStrength = parsed.textureStrength ?: 0.5
                brushTextureMode = parsed.textureMode ?: "multiply"
                brushTexturePattern = parsed.texturePattern ?: ""
                brushHueJitter = parsed.hueJitter ?: 0.0
                brushSatJitter = parsed.satJitter ?: 0.0
                brushValJitter = parsed.valJitter ?: 0.0
                brushSecondaryMix = parsed.secondaryMix ?: 0.0
                brushPressureColorMix = false
                brushPressureEnabled = true
                brushSpeedSize = 0.0
                brushPressureCurve = 0
                brushSpikes = parsed.spikes ?: 2
                brushJitterAngle = 0.0
                brushJitterSize = 0.0

                brushMaskingEnabled = parsed.maskingEnabled ?: false
                brushMaskingCompositeOp = parsed.maskingCompositeOp ?: "multiply"
                brushMaskingSizeRatio = parsed.maskingSizeRatio ?: 1.0
                brushMaskingSpacing = parsed.maskingSpacing ?: 0.1
                brushMaskingTipAsset = parsed.maskingTipAsset ?: ""
                brushMaskingTipShape = 0
                brushMaskingFade = 0.0
                brushMaskingSoftness = 1.0

                brushRotationSensor = parsed.rotationSensor ?: (if (brushFollowDirection) "drawingangle" else "")
                brushScatterSensor = parsed.scatterSensor ?: "fuzzy"
                brushSizeSensor = parsed.sizeSensor ?: "pressure"
                brushOpacitySensor = parsed.opacitySensor ?: "pressure"
                brushFlowSensor = parsed.flowSensor ?: "pressure"

                brushDescription = saved?.description ?: ""
                brushVersion = saved?.version ?: "1.0"
            }
            // 主线程先写预设值, 再由 overlay 用当前工具记忆覆盖;
            // overlay 内部的引擎 setter 经 runCore 追加在预设 setter 之后, 写序确定。
            applyToolParamMemoryOverlay(preset?.name)
            checkBrushSizeLimit()
        }) {
            if (ReverieCoreBridge.loadBrushPreset(index)) {
                Breadcrumbs.record("Brush", "Load preset [$index]: ${preset?.name}")
                // 分组元数据覆盖 C++ 名字启发式; 必须在 loadBrushPreset 之后下发,
                // 否则会被加载成功路径里的 override 重置抹掉
                ReverieCoreBridge.setPresetIsEraser(currentToolId == "eraser" || isEraserPreset)
                ReverieCoreBridge.setBrushCompositeOp(effectiveCompOp)
                ReverieCoreBridge.setBrushColor(brushColor)
                ReverieCoreBridge.setBrushSecondaryColor(brushSecondaryColor)
                try {
                    prefs().edit().putInt("last_brush_preset_index", index).apply()
                } catch (_: Exception) {
                }
            }
            if (saved != null && isCustomized) {
                ReverieCoreBridge.setBrushSize(saved.size)
                ReverieCoreBridge.setBrushOpacity(saved.opacity)
                ReverieCoreBridge.setBrushFlow(saved.flow)
                if (saved.spacingCustomized) {
                    ReverieCoreBridge.setBrushSpacing(saved.spacing)
                }
                ReverieCoreBridge.setBrushAngle(saved.angle)
                if (saved.dynamicsCustomized) {
                    ReverieCoreBridge.setBrushScatter(saved.scatter)
                }
                ReverieCoreBridge.setBrushFade(saved.fade)
                ReverieCoreBridge.setBrushSoftness(saved.softness)
                ReverieCoreBridge.setBrushRatio(saved.ratio)
                ReverieCoreBridge.setBrushSharpness(saved.sharpness)
                ReverieCoreBridge.setBrushRotation(saved.rotation)
                ReverieCoreBridge.setBrushCompositeOp(effectiveCompOp)
                if (saved.dynamicsCustomized) {
                    if (saved.dynamicOptions.isNotEmpty()) {
                        for ((opt, xml) in saved.dynamicOptions) {
                            val sensorId = Regex("""id="([^"]+)"""").find(xml)?.groupValues?.getOrNull(1) ?: "pressure"
                            val curve = Regex("""<curve>([^<]*)</curve>""").find(xml)?.groupValues?.getOrNull(1) ?: "0,0;1,1;"
                            val strength = when (opt.lowercase()) {
                                "size" -> saved.pressureSize
                                "opacity" -> saved.pressureOpacity
                                "flow" -> saved.pressureFlow
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
                            saved.pressureEnabled,
                            saved.pressureSize,
                            saved.pressureOpacity,
                            saved.pressureFlow,
                            saved.pressureCurve,
                        )
                    }
                }
                ReverieCoreBridge.setBrushFollowDirection(saved.followDirection)
                ReverieCoreBridge.setBrushJitter(saved.jitterAngle, saved.jitterSize)
                ReverieCoreBridge.setBrushMirror(saved.randomFlipX, saved.randomFlipY)
                ReverieCoreBridge.setBrushAntiAliasing(saved.antiAliasing)
                if (saved.smudgeCustomized) {
                    ReverieCoreBridge.setBrushSmudgeRate(saved.smudgeRate)
                    ReverieCoreBridge.setBrushSmudgeLength(saved.smudgeLength)
                }
                val effectiveAirbrushRate = if (saved.airbrushRate >= 5.0) saved.airbrushRate else 30.0
                ReverieCoreBridge.setBrushAirbrush(saved.airbrush, effectiveAirbrushRate)
                if (saved.tipAsset.isNotEmpty()) {
                    ReverieCoreBridge.setBrushTipAsset(saved.tipAsset)
                } else {
                    ReverieCoreBridge.setBrushTipAsset("")
                }
                ReverieCoreBridge.setBrushTexture(
                    saved.textureEnabled,
                    saved.textureScale,
                    saved.textureStrength,
                    saved.textureMode,
                    saved.texturePattern,
                )
            } else {
                ReverieCoreBridge.setBrushCompositeOp(effectiveCompOp)
                if (saved?.tipAsset?.isNotEmpty() == true) {
                    ReverieCoreBridge.setBrushTipAsset(saved.tipAsset)
                } else {
                    ReverieCoreBridge.setBrushTipAsset("")
                }
            }
        }
    }

    internal fun PaintViewModel.updateCurrentToolBrushState(updater: (PaintViewModel.ToolBrushState) -> PaintViewModel.ToolBrushState) {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        val targetToolId = if (t == com.reverie.paint.model.Tool.BRUSH ||
            t == com.reverie.paint.model.Tool.ERASER ||
            t == com.reverie.paint.model.Tool.SMUDGE
        ) {
            currentToolId
        } else {
            if (lastDrawingToolId.isNotBlank()) lastDrawingToolId else "brush"
        }
        val state = toolBrushStates[targetToolId] ?: PaintViewModel.ToolBrushState()
        toolBrushStates = toolBrushStates.toMutableMap().apply { put(targetToolId, updater(state)) }
        schedulePersistToolBrushStates()
    }

    /** Krita saved{Mode}Size 语义: 把当前 size/opacity/flow 快照进
     *  (当前工具 × 当前预设) 的记忆, 让每个工具各自记住自己的数值。 */
    internal fun PaintViewModel.rememberToolParamSnapshot() {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        val targetToolId = if (t == com.reverie.paint.model.Tool.BRUSH ||
            t == com.reverie.paint.model.Tool.ERASER ||
            t == com.reverie.paint.model.Tool.SMUDGE
        ) {
            currentToolId
        } else {
            lastDrawingToolId
        }
        val name = brushPresets.firstOrNull { it.index == brushPresetIndex }?.name ?: return
        val currentMap = toolBrushStates[targetToolId] ?: return
        toolBrushStates = toolBrushStates.toMutableMap().apply {
            put(
                targetToolId,
                currentMap.copy(
                    paramMemory = currentMap.paramMemory.toMutableMap().apply {
                        put(name, listOf(brushSize, brushOpacity, brushFlow))
                    }
                )
            )
        }
    }

    /** 预设参数加载完成后, 用当前工具对该预设的记忆覆盖 size/opacity/flow。
     *  无记忆时保持预设参数不变 (首次使用语义)。 */
    internal fun PaintViewModel.applyToolParamMemoryOverlay(targetPresetName: String? = null) {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t != com.reverie.paint.model.Tool.BRUSH && t != com.reverie.paint.model.Tool.ERASER &&
            t != com.reverie.paint.model.Tool.SMUDGE
        ) return
        val name = targetPresetName ?: brushPresets.firstOrNull { it.index == brushPresetIndex }?.name ?: return
        val mem = toolBrushStates[t.id]?.paramMemory?.get(name) ?: return
        if (mem.size < 3) return
        // 损坏数据防护: 每个值做 isFinite() 检查, 非有限值跳过该值, 防 NaN 流入引擎
        if (mem[0].isFinite()) {
            brushSize = mem[0].coerceAtMost(effectiveBrushMaxSize)
        }
        if (mem[1].isFinite()) {
            brushOpacity = mem[1]
        }
        if (mem[2].isFinite()) {
            brushFlow = mem[2]
        }
        runCore(render = false) {
            if (mem[0].isFinite()) ReverieCoreBridge.setBrushSize(brushSize)
            if (mem[1].isFinite()) ReverieCoreBridge.setBrushOpacity(mem[1])
            if (mem[2].isFinite()) ReverieCoreBridge.setBrushFlow(mem[2])
        }
    }

    internal fun PaintViewModel.updateBrushPanelCategory(cat: String) {
        brushPanelSelectedCategory = cat
        updateCurrentToolBrushState { it.copy(category = cat) }
        persistBrushPanelState()
    }

    internal fun PaintViewModel.updateBrushSize(v: Double, commit: Boolean = true) {
        val minL = brushMinSizeLimit.coerceAtLeast(0.5)
        val maxL = effectiveBrushMaxSize.coerceAtLeast(minL)
        val clamped = v.coerceIn(minL, maxL)
        brushSize = clamped
        if (commit) {
            saveBrushParam()
            rememberToolParamSnapshot()
        }
        runCore(render = false) { ReverieCoreBridge.setBrushSize(clamped) }
    }

    internal fun PaintViewModel.updateBrushColor(c: String) {
        brushColor = c
        if (colorPanelTab != 2) {
            colorSphereBaseHex = c
        }
        runCore(render = false) { ReverieCoreBridge.setBrushColor(c) }
        if (isAppContextReady()) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("brushColor", c).apply()
        }
    }

    internal fun PaintViewModel.updateBrushSecondaryColor(c: String) {
        brushSecondaryColor = c
        if (isAppContextReady()) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("brushSecondaryColor", c).apply()
        }
        runCore(render = false) { ReverieCoreBridge.setBrushSecondaryColor(c) }
    }


    internal fun PaintViewModel.updateBrushOpacity(v: Double, commit: Boolean = true) {
        brushOpacity = v
        if (commit) {
            saveBrushParam()
            rememberToolParamSnapshot()
        }
        runCore(render = false) { ReverieCoreBridge.setBrushOpacity(v) }
    }

    /** Reload all brush presets from filesDir and update Compose state */
    internal fun PaintViewModel.reloadBrushPresets(selectName: String? = null) {
        val dir = File(appContext.filesDir, "paintoppresets")
        val brushDir = File(appContext.filesDir, "brushes")
        val builtInNames = getBuiltInBrushNames()
        val list = ArrayList<BrushPresetInfo>()
        runCore(after = {
            // Every reload re-assigns native indices, so keep the selection
            // by NAME; re-apply the persisted user order (reorderBrush only
            // stores it — the native table stays filename-sorted). Names not
            // present in brushOrder keep their native order, appended after.
            val keepName = selectName
                ?: brushPresets.firstOrNull { it.index == brushPresetIndex }?.name
            val rank = brushOrder.withIndex().associate { it.value to it.index }
            val ordered =
                if (rank.isEmpty()) list.toList()
                else list.toList().sortedBy { rank[it.name] ?: (rank.size + it.index) }
            brushPresets = ordered
            brushPresetsLoaded = true
            if (ordered.isNotEmpty()) {
                val target = keepName?.let { n -> ordered.firstOrNull { it.name == n } }
                selectBrushPreset(target?.index ?: ordered[0].index)
            }
        }) {
            ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
            val n = ReverieCoreBridge.loadBrushPresetsFromDir(dir.absolutePath)
            list.clear()
            for (i in 0 until n) {
                val nm = ReverieCoreBridge.brushPresetName(i)
                list.add(
                    BrushPresetInfo(
                        index = i,
                        name = nm,
                        thumbBytes = ReverieCoreBridge.brushPresetThumbData(i),
                        group = userBrushGroups[nm] ?: inferBrushGroup(nm),
                        isBuiltIn = builtInNames.contains(nm),
                    ),
                )
            }
        }
    }

    /**
     * 读取预设 .kpp 自身的 Fade / Softness 原值。
     *
     * 复制、派生笔刷时若直接拿 [BrushParams] 的默认值落盘，会把源预设 MaskGenerator 的
     * hfade/vfade（笔尖羽化）覆盖成 0（锐利硬边），所以这里先把原值取回来。
     */
    private fun reverieReadTipAttrs(kppFile: File?): KppHelper.KppParsedAttributes =
        if (kppFile != null && kppFile.exists()) KppHelper.parseKppFile(kppFile) else KppHelper.KppParsedAttributes()

    /** 复制指定笔刷 (复制出的笔刷不受内置作者锁定及不可删除限制) */
    internal fun PaintViewModel.duplicateBrushPreset(presetIndex: Int, newName: String? = null): Boolean {
        // Native-table index (BrushPresetInfo.index), not list position.
        val src = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        val cleanName = newName?.trim()?.ifEmpty { null } ?: "${src.name} 副本"
        val dir = File(appContext.filesDir, "paintoppresets")
        val srcFile = File(dir, "${src.name}.kpp")
        val dstFile = File(dir, "$cleanName.kpp")
        if (srcFile.exists()) {
            srcFile.copyTo(dstFile, overwrite = true)
        }
        val d = ReverieCoreBridge.brushPresetDefaults(src.index)
        val srcTip = reverieReadTipAttrs(srcFile)
        val srcParams = brushParams[src.name] ?: if (d.size >= 8) {
            BrushParams(
                size = d[0],
                opacity = d[1].coerceIn(0.0, 1.0),
                flow = d[2].coerceIn(0.0, 1.0),
                spacing = d[3],
                airbrush = d[4] > 0.5,
                airbrushRate = if (d[5] >= 5.0) d[5] else 30.0,
                smudgeRate = d[6],
                smudgeLength = d[7],
                fade = srcTip.fade ?: KppHelper.FADE_SOLID,
                softness = srcTip.softness ?: KppHelper.SOFTNESS_NEUTRAL,
            )
        } else {
            BrushParams(
                fade = srcTip.fade ?: KppHelper.FADE_SOLID,
                softness = srcTip.softness ?: KppHelper.SOFTNESS_NEUTRAL,
            )
        }
        // 复制出的笔刷作者可自由修改，且非内置
        val cleanParams = srcParams.copy(
            author = if (src.isBuiltIn) "Krita (副本)" else srcParams.author,
            isAuthorLocked = false,
        )
        brushParams[cleanName] = cleanParams
        if (dstFile.exists()) {
            KppHelper.updateKppFile(dstFile, cleanName, cleanParams)
        }
        persistBrushParams()
        if (userBrushGroups.containsKey(src.name)) {
            userBrushGroups = userBrushGroups + (cleanName to userBrushGroups[src.name]!!)
            saveBrushGroups()
        }
        reloadBrushPresets(selectName = cleanName)
        return true
    }

    /** 删除指定笔刷 (内置笔刷不可删除) */
    internal fun PaintViewModel.deleteBrushPreset(presetIndex: Int): Boolean {
        // Native-table index (BrushPresetInfo.index), not list position.
        val preset = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷禁止删除
        }
        val dir = File(appContext.filesDir, "paintoppresets")
        val file = File(dir, "${preset.name}.kpp")
        if (file.exists()) file.delete()
        brushParams.remove(preset.name)
        persistBrushParams()
        userBrushGroups = userBrushGroups - preset.name
        favoriteBrushNames = favoriteBrushNames - preset.name
        recentBrushNames = recentBrushNames.filter { it != preset.name }
        brushOrder = brushOrder.filter { it != preset.name }
        persistBrushPanelState()
        saveBrushOrder()
        saveBrushGroups()
        reloadBrushPresets()
        return true
    }

    /** 删除笔刷组 (内置组不可删除)，支持联动物理删除组内所有自定义笔刷 */
    internal fun PaintViewModel.deleteBrushGroup(name: String, deletePresets: Boolean = true): Boolean {
        if (isBuiltInGroup(name)) return false // 内置组禁止删除
        val dir = File(appContext.filesDir, "paintoppresets")
        val presetsInGroup = brushPresets.filter { it.group == name }

        if (deletePresets) {
            val customPresets = presetsInGroup.filter { !it.isBuiltIn }
            val deletedNames = customPresets.map { it.name }.toSet()
            for (p in customPresets) {
                val file = File(dir, "${p.name}.kpp")
                if (file.exists()) file.delete()
                brushParams.remove(p.name)
            }
            if (deletedNames.isNotEmpty()) {
                persistBrushParams()
                userBrushGroups = userBrushGroups - deletedNames
                favoriteBrushNames = favoriteBrushNames - deletedNames
                recentBrushNames = recentBrushNames.filter { it !in deletedNames }
                brushOrder = brushOrder.filter { it !in deletedNames }
                persistBrushPanelState()
                saveBrushOrder()
            }
        }

        // 解除该分组下残留预设（如内置笔刷，或未勾选删除笔刷时的自定义笔刷）与当前组的映射
        userBrushGroups = userBrushGroups.filterValues { it != name }
        customBrushGroups = customBrushGroups.filter { it != name }
        categoryOrder = categoryOrder.filter { it != name }
        saveBrushGroups()
        saveCategoryOrder()
        reloadBrushPresets()
        return true
    }

    /** 批量删除笔刷 (内置只读笔刷会自动跳过并保留) */
    internal fun PaintViewModel.deleteBrushPresetsBatch(presetNames: List<String>): Int {
        val dir = File(appContext.filesDir, "paintoppresets")
        val targets = brushPresets.filter { it.name in presetNames && !it.isBuiltIn }
        if (targets.isEmpty()) return 0

        val deletedNames = targets.map { it.name }.toSet()
        for (p in targets) {
            val file = File(dir, "${p.name}.kpp")
            if (file.exists()) file.delete()
            brushParams.remove(p.name)
            userBrushGroups = userBrushGroups - p.name
        }
        persistBrushParams()
        favoriteBrushNames = favoriteBrushNames - deletedNames
        recentBrushNames = recentBrushNames.filter { it !in deletedNames }
        brushOrder = brushOrder.filter { it !in deletedNames }
        persistBrushPanelState()
        saveBrushOrder()
        saveBrushGroups()

        reloadBrushPresets()
        return targets.size
    }

    /** 批量移动笔刷到指定分组 */
    internal fun PaintViewModel.moveBrushPresetsBatch(presetNames: List<String>, targetGroup: String) {
        val cleanGroup = targetGroup.trim()
        if (presetNames.isEmpty() || cleanGroup.isBlank()) return
        var updated = userBrushGroups
        for (name in presetNames) {
            updated = updated + (name to cleanGroup)
        }
        userBrushGroups = updated
        if (!customBrushGroups.contains(cleanGroup) && !BUILT_IN_BRUSH_GROUPS.contains(cleanGroup)) {
            customBrushGroups = customBrushGroups + cleanGroup
        }
        saveBrushGroups()
        reloadBrushPresets()
    }

    /** 创建全新自定义笔刷 (默认基于 Basic-1) */
    internal fun PaintViewModel.createNewBrushPreset(
        name: String,
        group: String = "自定义",
        basePresetIndex: Int = -1,
        tipAsset: String = "",
        paintOpId: String = "defaultpaintop",
    ): Boolean {
        val cleanName = name.trim().ifEmpty { "自定义笔刷_${(System.currentTimeMillis() % 10000)}" }
        val dir = File(appContext.filesDir, "paintoppresets")
        if (!dir.exists()) dir.mkdirs()
        
        // 默认基准预设选择 b)_Basic-1
        val base = brushPresets.firstOrNull { it.index == basePresetIndex }
            ?: brushPresets.firstOrNull { it.name == "b)_Basic-1" || it.name.contains("Basic-1") }
            ?: brushPresets.firstOrNull()
        val baseFile = base?.let { File(dir, "${it.name}.kpp") }
        val targetFile = File(dir, "$cleanName.kpp")
        if (baseFile != null && baseFile.exists()) {
            baseFile.copyTo(targetFile, overwrite = true)
        } else {
            val first = dir.listFiles()?.firstOrNull { it.name.endsWith(".kpp") }
            first?.copyTo(targetFile, overwrite = true)
        }
        val baseD = base?.let { ReverieCoreBridge.brushPresetDefaults(it.index) }
        val baseTip = reverieReadTipAttrs(baseFile)
        val baseParams = base?.name?.let { brushParams[it] } ?: if (baseD != null && baseD.size >= 8) {
            BrushParams(
                size = baseD[0],
                opacity = baseD[1].coerceIn(0.0, 1.0),
                flow = baseD[2].coerceIn(0.0, 1.0),
                spacing = baseD[3],
                airbrush = baseD[4] > 0.5,
                airbrushRate = if (baseD[5] >= 5.0) baseD[5] else 30.0,
                smudgeRate = baseD[6],
                smudgeLength = baseD[7],
                fade = baseTip.fade ?: KppHelper.FADE_SOLID,
                softness = baseTip.softness ?: KppHelper.SOFTNESS_NEUTRAL,
            )
        } else null
        val newParams = (baseParams?.copy() ?: BrushParams()).copy(
            tipAsset = if (tipAsset.isNotEmpty()) tipAsset else (baseParams?.tipAsset ?: ""),
            paintOpId = if (paintOpId.isNotEmpty() && paintOpId != "defaultpaintop") paintOpId else (baseParams?.paintOpId ?: "defaultpaintop"),
            author = "原创创作者",
            isAuthorLocked = false,
        )
        brushParams[cleanName] = newParams
        if (targetFile.exists()) {
            KppHelper.updateKppFile(targetFile, cleanName, newParams)
        }
        persistBrushParams()
        userBrushGroups = userBrushGroups + (cleanName to group)
        if (!customBrushGroups.contains(group) && group != "全部") {
            customBrushGroups = customBrushGroups + group
        }
        saveBrushGroups()
        reloadBrushPresets(selectName = cleanName)
        return true
    }

    data class BrushImportResult(
        val success: Boolean,
        val presetName: String? = null,
        val groupName: String? = null,
        val count: Int = 1,
    )

    internal suspend fun PaintViewModel.importSingleBrushInternal(
        uri: android.net.Uri,
        targetGroup: String? = null,
    ): BrushImportResult {
        return try {
            val resolver = appContext.contentResolver
            val filename = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "import_${System.currentTimeMillis()}"

            val presetDir = File(appContext.filesDir, "paintoppresets")
            if (!presetDir.exists()) presetDir.mkdirs()
            val brushDir = File(appContext.filesDir, "brushes")
            if (!brushDir.exists()) brushDir.mkdirs()
            val patternDir = File(appContext.filesDir, "patterns")
            if (!patternDir.exists()) patternDir.mkdirs()

            val chosenGroup = targetGroup?.trim()?.takeIf { it.isNotEmpty() }

            if (filename.endsWith(".kpp", ignoreCase = true)) {
                val target = File(presetDir, filename)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val presetName = filename.substringBeforeLast(".")
                brushParams[presetName] = BrushParams(
                    author = "外部创作者 (分享)",
                    isAuthorLocked = true,
                    description = "导入自外部创作者分享的笔刷预设",
                    isCustomized = false,
                    dynamicsCustomized = false,
                    smudgeCustomized = false,
                )
                persistBrushParams()
                val group = chosenGroup ?: "导入"
                userBrushGroups = userBrushGroups + (presetName to group)
                if (!customBrushGroups.contains(group)) {
                    customBrushGroups = customBrushGroups + group
                }
                saveBrushGroups()
                BrushImportResult(success = true, presetName = presetName, groupName = group)
            } else if (filename.endsWith(".abr", ignoreCase = true)) {
                val packBaseName = filename.substringBeforeLast(".").trim().ifBlank { "ABR" }
                val targetGroupName = chosenGroup ?: packBaseName

                val safeGroupPrefix = packBaseName.replace(Regex("""[^\w\u4e00-\u9fa5]"""), "_")
                val tipFileNameMap = mutableMapOf<Int, String>()
                val tipUuidToFileName = mutableMapOf<String, String>()

                val parseResult = resolver.openInputStream(uri)?.use { inStream ->
                    AbrParser.parse(inStream, basePackName = targetGroupName) { decodedTip ->
                        // Stream decoded tip PNG directly to disk, freeing the raw full-res byte array immediately
                        val tipFileName = "${safeGroupPrefix}_tip_${decodedTip.index}.png"
                        val tipFile = File(brushDir, tipFileName)
                        val tipBytes = AbrParser.encodeTipPng(decodedTip)
                        tipFile.writeBytes(tipBytes)
                        tipFileNameMap[decodedTip.index] = tipFileName
                        tipUuidToFileName[decodedTip.uuid] = tipFileName
                    }
                }

                if (parseResult == null || (parseResult.tips.isEmpty() && parseResult.presets.isEmpty())) {
                    return BrushImportResult(success = false)
                }

                val tipsByUuid = parseResult.tips.associateBy { it.uuid }
                val tipsByIndex = parseResult.tips.associateBy { it.index }

                // 2. Export preset .kpp files to filesDir/paintoppresets/
                val totalPresets = parseResult.presets.size
                withContext(Dispatchers.Main) {
                    brushImportProgress = Pair(0, totalPresets)
                }

                val importedPresetNames = mutableListOf<String>()
                val existingKppNames = (presetDir.list() ?: emptyArray()).map { it.removeSuffix(".kpp") }.toMutableSet()

                for ((idx, preset) in parseResult.presets.withIndex()) {
                    // Computed presets must NOT bind a sampled tip; see AbrParser.matchTipForPreset.
                    val matchedTip = AbrParser.matchTipForPreset(
                        preset, tipsByUuid, tipsByIndex, parseResult.tips,
                    )
                    val matchedTipFileName = (preset.tipUuid?.let { tipUuidToFileName[it] })
                        ?: (matchedTip?.let { tipFileNameMap[it.index] })
                        ?: ""

                    val rawName = abrPresetName(preset.name, targetGroupName, idx)
                    var candidateName = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    var counter = 2
                    while (existingKppNames.contains(candidateName)) {
                        candidateName = "${rawName}_$counter"
                        counter++
                    }
                    existingKppNames.add(candidateName)
                    importedPresetNames.add(candidateName)

                    val previewBytes = AbrParser.encodePreviewPng(
                        tip = matchedTip,
                        diameter = preset.diameter,
                        roundness = preset.roundness,
                    )

                    val hasPressureDynamics = preset.pressureSize || preset.pressureOpacity || preset.pressureFlow
                    val hasDynamics = hasPressureDynamics || preset.scatter > 0.001 || preset.followDirection || preset.flipX || preset.flipY
                    val bp = BrushParams(
                        size = preset.diameter,
                        opacity = 1.0,
                        flow = 1.0,
                        spacing = preset.spacing,
                        angle = preset.angle,
                        scatter = preset.scatter,
                        ratio = preset.roundness,
                        followDirection = preset.followDirection,
                        randomFlipX = preset.flipX,
                        randomFlipY = preset.flipY,
                        pressureEnabled = hasPressureDynamics,
                        pressureSize = if (preset.pressureSize) 1.0 else 0.0,
                        pressureOpacity = if (preset.pressureOpacity) 1.0 else 0.0,
                        pressureFlow = if (preset.pressureFlow) 1.0 else 0.0,
                        tipAsset = matchedTipFileName,
                        paintOpId = "paintbrush",
                        compositeOp = "normal",
                        author = "外部创作者 (ABR)",
                        isAuthorLocked = true,
                        description = "导入自 Photoshop ABR 笔刷包: $packBaseName",
                        isCustomized = false,
                        dynamicsCustomized = hasDynamics,
                        smudgeCustomized = false,
                    )

                    val kppFile = File(presetDir, "$candidateName.kpp")
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
                    val kppBytes = KppHelper.updateKppBytes(previewBytes, candidateName, bp, tipScale)
                    kppFile.writeBytes(kppBytes)

                    brushParams[candidateName] = bp

                    withContext(Dispatchers.Main) {
                        brushImportProgress = Pair(idx + 1, totalPresets)
                    }
                }

                // 3. Update groups and state
                var newCustomGroups = customBrushGroups
                if (!newCustomGroups.contains(targetGroupName) && targetGroupName != "全部") {
                    newCustomGroups = newCustomGroups + targetGroupName
                }
                var newUserGroups = userBrushGroups
                for (pName in importedPresetNames) {
                    newUserGroups = newUserGroups + (pName to targetGroupName)
                }

                withContext(Dispatchers.Main) {
                    customBrushGroups = newCustomGroups
                    userBrushGroups = newUserGroups
                    saveBrushGroups()
                    persistBrushParams()
                    brushImportProgress = null
                    reloadBrushPresets(selectName = importedPresetNames.firstOrNull())
                }

                BrushImportResult(
                    success = true,
                    presetName = importedPresetNames.firstOrNull(),
                    groupName = targetGroupName,
                    count = importedPresetNames.size,
                )
            } else if (filename.endsWith(".png", true) || filename.endsWith(".gbr", true) || filename.endsWith(".gih", true)) {
                val target = File(brushDir, filename)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val presetName = filename.substringBeforeLast(".")
                val group = chosenGroup ?: "导入"
                withContext(Dispatchers.Main) {
                    createNewBrushPreset(name = presetName, group = group, tipAsset = filename)
                    brushParams[presetName] = brushParams[presetName]?.copy(
                        author = "外部创作者 (分享)",
                        isAuthorLocked = true,
                    ) ?: BrushParams(author = "外部创作者 (分享)", isAuthorLocked = true)
                    persistBrushParams()
                }
                BrushImportResult(success = true, presetName = presetName, groupName = group)
            } else if (filename.endsWith(".bundle", true) || filename.endsWith(".zip", true)) {
                val tempZip = File.createTempFile("bundle_temp_", ".zip", appContext.cacheDir)
                val presetToTagMap = mutableMapOf<String, String>()
                val discoveredTags = mutableSetOf<String>()
                val importedPresets = mutableListOf<String>()

                val defaultGroupName = chosenGroup ?: filename.substringBeforeLast(".")
                    .removeSuffix(".bundle")
                    .removeSuffix(".zip")
                    .trim()
                    .ifBlank { "导入" }

                try {
                    resolver.openInputStream(uri)?.use { input ->
                        tempZip.outputStream().use { output -> input.copyTo(output) }
                    }

                    ZipFile(tempZip).use { zip ->
                        // 1. Check for META-INF/manifest.xml or *.tag files to extract tags
                        val manifestEntry = zip.getEntry("META-INF/manifest.xml")
                        if (manifestEntry != null) {
                            runCatching {
                                val parser = android.util.Xml.newPullParser()
                                parser.setInput(zip.getInputStream(manifestEntry), "UTF-8")
                                var eventType = parser.eventType
                                var currentPath: String? = null
                                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                                    if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                                        val tag = parser.name.substringAfterLast(":")
                                        if (tag.equals("file-entry", ignoreCase = true)) {
                                            currentPath = parser.getAttributeValue(null, "full-path")
                                                ?: parser.getAttributeValue("http://openoffice.org/2001/manifest", "full-path")
                                        } else if (tag.equals("tag", ignoreCase = true) && currentPath != null) {
                                            val tagText = parser.nextText()?.trim()
                                            if (!tagText.isNullOrBlank()) {
                                                val presetName = currentPath.substringAfterLast("/").substringBeforeLast(".")
                                                if (presetName.isNotBlank()) {
                                                    presetToTagMap[presetName] = tagText
                                                    discoveredTags.add(tagText)
                                                }
                                            }
                                        }
                                    } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG) {
                                        val tag = parser.name.substringAfterLast(":")
                                        if (tag.equals("file-entry", ignoreCase = true)) {
                                            currentPath = null
                                        }
                                    }
                                    eventType = parser.next()
                                }
                            }
                        }

                        // 2. Also check any *.tag files in the zip
                        for (entry in zip.entries()) {
                            if (entry.name.endsWith(".tag", ignoreCase = true)) {
                                runCatching {
                                    val content = zip.getInputStream(entry).bufferedReader().readText()
                                    var tagName = ""
                                    for (line in content.lines()) {
                                        val trimmed = line.trim()
                                        if (trimmed.startsWith("Name[zh_CN]=", ignoreCase = true)) {
                                            tagName = trimmed.substringAfter("=").trim()
                                            break
                                        } else if (trimmed.startsWith("Name=", ignoreCase = true) && tagName.isEmpty()) {
                                            tagName = trimmed.substringAfter("=").trim()
                                        }
                                    }
                                    if (tagName.isNotBlank()) {
                                        discoveredTags.add(tagName)
                                    }
                                }
                            }
                        }

                        // 3. Extract presets (.kpp), brushes and patterns
                        for (entry in zip.entries()) {
                            val entryName = entry.name
                            if (entryName.contains("paintoppresets/") && entryName.endsWith(".kpp", ignoreCase = true)) {
                                val kppName = entryName.substringAfterLast("/")
                                val out = File(presetDir, kppName)
                                zip.getInputStream(entry).use { inS -> out.outputStream().use { inS.copyTo(it) } }
                                val presetBaseName = kppName.substringBeforeLast(".")
                                importedPresets.add(presetBaseName)
                            } else if (entryName.contains("brushes/")) {
                                val brushName = entryName.substringAfterLast("/")
                                if (brushName.isNotBlank() && !entry.isDirectory) {
                                    val out = File(brushDir, brushName)
                                    zip.getInputStream(entry).use { inS -> out.outputStream().use { inS.copyTo(it) } }
                                }
                            } else if (entryName.contains("patterns/")) {
                                val patName = entryName.substringAfterLast("/")
                                if (patName.isNotBlank() && !entry.isDirectory) {
                                    val out = File(patternDir, patName)
                                    zip.getInputStream(entry).use { inS -> out.outputStream().use { inS.copyTo(it) } }
                                }
                            }
                        }
                    }
                } finally {
                    tempZip.delete()
                }

                if (patternDir.exists()) {
                    try {
                        ReverieCoreBridge.loadPatternResources(patternDir.absolutePath)
                    } catch (_: Throwable) {
                    }
                }

                if (importedPresets.isNotEmpty()) {
                    var newCustomGroups = customBrushGroups
                    var newUserGroups = userBrushGroups
                    var targetFinalGroup = defaultGroupName

                    for (preset in importedPresets) {
                        val tag = chosenGroup ?: presetToTagMap[preset] ?: defaultGroupName
                        if (!newCustomGroups.contains(tag)) {
                            newCustomGroups = newCustomGroups + tag
                        }
                        newUserGroups = newUserGroups + (preset to tag)
                        targetFinalGroup = tag
                    }

                    customBrushGroups = newCustomGroups
                    userBrushGroups = newUserGroups
                    saveBrushGroups()

                    BrushImportResult(
                        success = true,
                        presetName = importedPresets.firstOrNull(),
                        groupName = targetFinalGroup,
                    )
                } else {
                    BrushImportResult(success = false)
                }
            } else {
                BrushImportResult(success = false)
            }
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importSingleBrushInternal failed", e)
            BrushImportResult(success = false)
        }
    }

    /**
     * ABR 预设的落盘名。
     *
     * Photoshop 给很多 ABR 包的笔刷名就是纯数字 ("1"、"2"、"3"…)，Krita 自己的
     * 资源名也是把包名拼在前面 (`brushes_by_mar_ka_d338ela_2`)。裸数字名有两个
     * 实际问题: 用户在笔刷列表里完全认不出这是哪个包; 再导入第二个同命名的包
     * 会走重名兜底变成 "1_2"、"2_2"，越导越乱。
     *
     * 所以纯数字名补包名前缀，自带描述的名字（"Round 10 Hardness 80%"）保持原样。
     */
    internal fun abrPresetName(rawName: String, packBaseName: String, index: Int): String {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) return "$packBaseName ${index + 1}"
        val hasLetter = trimmed.any { it.isLetter() }
        return if (hasLetter) trimmed else "${packBaseName}_$trimmed"
    }

    /** 导入外部笔刷文件 (.kpp, .bundle, .gbr, .png, .abr, .zip) */
    internal fun PaintViewModel.importBrushFromUri(
        uri: android.net.Uri,
        targetGroup: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        viewModelScope.launch(Dispatchers.IO) {
            val result = importSingleBrushInternal(uri, targetGroup)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    if (result.groupName != null) {
                        brushPanelSelectedCategory = result.groupName
                    }
                    reloadBrushPresets(selectName = result.presetName)
                }
                if (onComplete != null) {
                    onComplete(result.success)
                } else {
                    val toastMsg = if (result.success) {
                        if (result.count > 1 && result.groupName != null) {
                            appContext.getString(R.string.brush_import_abr_success, result.count, result.groupName)
                        } else {
                            appContext.getString(R.string.brush_studio_toast_imported)
                        }
                    } else {
                        appContext.getString(R.string.brush_studio_toast_import_failed)
                    }
                    android.widget.Toast.makeText(
                        appContext,
                        toastMsg,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        return true
    }

    /** 批量导入外部笔刷文件列表 */
    internal fun PaintViewModel.importBrushesFromUris(
        uris: List<android.net.Uri>,
        targetGroup: String? = null,
        onComplete: ((Boolean, Int) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            var lastPreset: String? = null
            var lastGroup: String? = targetGroup

            for (u in uris) {
                val res = importSingleBrushInternal(u, targetGroup)
                if (res.success) {
                    successCount++
                    if (res.presetName != null) lastPreset = res.presetName
                    if (res.groupName != null) lastGroup = res.groupName
                }
            }

            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    if (lastGroup != null) {
                        brushPanelSelectedCategory = lastGroup
                    }
                    reloadBrushPresets(selectName = lastPreset)
                }
                onComplete?.invoke(successCount > 0, successCount)
            }
        }
    }

    /** 重命名笔刷 (内置笔刷固定禁止重命名) */
    internal fun PaintViewModel.renameBrushPreset(presetIndex: Int, newName: String): Boolean {
        // Native-table index (BrushPresetInfo.index), not list position.
        val preset = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷固定名称，禁止修改
        }
        val clean = newName.trim().ifEmpty { return false }
        if (clean == preset.name) return true
        val dir = File(appContext.filesDir, "paintoppresets")
        val src = File(dir, "${preset.name}.kpp")
        val dst = File(dir, "$clean.kpp")
        if (src.exists()) src.renameTo(dst)
        val p = brushParams.remove(preset.name)
        if (p != null) brushParams[clean] = p
        persistBrushParams()
        val g = userBrushGroups[preset.name]
        if (g != null) {
            userBrushGroups = (userBrushGroups - preset.name) + (clean to g)
        }
        saveBrushGroups()
        if (brushOrder.contains(preset.name)) {
            brushOrder = brushOrder.map { if (it == preset.name) clean else it }
            saveBrushOrder()
        }
        reloadBrushPresets(selectName = clean)
        return true
    }

    /** 导入用户自定义笔尖贴图 (PNG, GBR, GIH, JPG) 并设置为当前笔刷笔尖 */
    internal fun PaintViewModel.importCustomBrushTip(uri: android.net.Uri): String? {
        return try {
            val resolver = appContext.contentResolver
            val rawName = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "tip_${System.currentTimeMillis()}"

            val baseName = if (rawName.contains('.')) rawName.substringBeforeLast(".") else rawName
            val ext = if (rawName.contains('.')) rawName.substringAfterLast(".").lowercase() else "png"
            val isKritaNative = ext == "gbr" || ext == "gih" || ext == "svg"

            val brushDir = File(appContext.filesDir, "brushes")
            if (!brushDir.exists()) brushDir.mkdirs()

            val cleanName: String
            if (isKritaNative) {
                cleanName = "$baseName.$ext"
                val target = File(brushDir, cleanName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                // Decode any image format and write as standard lossless PNG for KisPngBrush
                cleanName = "$baseName.png"
                val target = File(brushDir, cleanName)
                val bmp = resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return null
                target.outputStream().use { output ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            updateBrushTipAsset(cleanName)
            cleanName
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importCustomBrushTip failed", e)
            null
        }
    }

    /** 导入用户自定义材质纹理贴图 (.pat, .png, .jpg) 并设置为当前笔刷纹理 */
    internal fun PaintViewModel.importCustomPattern(uri: android.net.Uri): String? {
        return try {
            val resolver = appContext.contentResolver
            val rawName = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "pat_${System.currentTimeMillis()}"

            val baseName = if (rawName.contains('.')) rawName.substringBeforeLast(".") else rawName
            val ext = if (rawName.contains('.')) rawName.substringAfterLast(".").lowercase() else "png"
            val isPat = ext == "pat"

            val patternDir = File(appContext.filesDir, "patterns")
            if (!patternDir.exists()) patternDir.mkdirs()

            val cleanName: String
            if (isPat) {
                cleanName = "$baseName.pat"
                val target = File(patternDir, cleanName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                cleanName = "$baseName.png"
                val target = File(patternDir, cleanName)
                val bmp = resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return null
                target.outputStream().use { output ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            updateBrushTexturePattern(cleanName)
            cleanName
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importCustomPattern failed", e)
            null
        }
    }



