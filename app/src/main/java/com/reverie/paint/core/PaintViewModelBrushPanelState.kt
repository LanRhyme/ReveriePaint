/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.toCurveXml
import com.reverie.paint.model.*
import org.json.JSONArray
import org.json.JSONObject
import com.reverie.paint.core.PaintViewModel.ToolBrushState

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.pushTextInputActive() {
    textInputCounter++
    isTextInputActive = true
}

internal fun PaintViewModel.popTextInputActive() {
    textInputCounter = (textInputCounter - 1).coerceAtLeast(0)
    isTextInputActive = textInputCounter > 0
}

internal fun PaintViewModel.restorePreviousTool() {
    if (!isTemporaryPicker) return
    isTemporaryPicker = false
    val prev = lastToolId.ifEmpty { "brush" }
    applyTool(if (prev == "picker") "brush" else prev)
}

internal fun PaintViewModel.getBrushDynamicOption(key: String): com.reverie.paint.model.DynamicOptionConfig {
    val canonicalKey = when (key.lowercase()) {
        "hue" -> "h"
        "saturation", "sat" -> "s"
        "value", "val" -> "v"
        else -> key
    }
    return brushDynamicOptions[canonicalKey] ?: brushDynamicOptions[key] ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor(canonicalKey).let { def ->
        val sensor = when (canonicalKey.lowercase()) {
            "size" -> brushParams.sizeSensor
            "opacity" -> brushParams.opacitySensor
            "flow" -> brushParams.flowSensor
            "rotation" -> brushParams.rotationSensor
            "scatter" -> brushParams.scatterSensor
            "h", "s", "v" -> com.reverie.paint.model.BrushSensor.FUZZY.id
            else -> def.sensorId
        }
        def.copy(sensorId = sensor.ifBlank { def.sensorId })
    }.also {
        brushDynamicOptions[canonicalKey] = it
    }
}

internal fun PaintViewModel.updateBrushDynamicOption(config: com.reverie.paint.model.DynamicOptionConfig) {
    updateBrushDynamicOption(config.optionKey, config)
}

internal fun PaintViewModel.updateBrushDynamicOption(
    key: String,
    config: com.reverie.paint.model.DynamicOptionConfig,
) {
    val canonicalKey = when (key.lowercase()) {
        "hue" -> "h"
        "saturation", "sat" -> "s"
        "value", "val" -> "v"
        else -> key
    }
    val finalConfig = if (config.enabled && config.strength <= 0.001f) config.copy(strength = 1.0f) else config
    brushDynamicOptions[canonicalKey] = finalConfig
    val strength = if (finalConfig.enabled) finalConfig.strength.toDouble() else 0.0
    brushParams = when (canonicalKey.lowercase()) {
        "size" -> brushParams.copy(
            pressureSize = strength,
            pressureEnabled = brushPressureEnabled || finalConfig.enabled,
            sizeSensor = finalConfig.sensorId,
        )
        "opacity" -> brushParams.copy(
            pressureOpacity = strength,
            pressureEnabled = brushPressureEnabled || finalConfig.enabled,
            opacitySensor = finalConfig.sensorId,
        )
        "flow" -> brushParams.copy(
            pressureFlow = strength,
            pressureEnabled = brushPressureEnabled || finalConfig.enabled,
            flowSensor = finalConfig.sensorId,
        )
        "scatter" -> brushParams.copy(scatterSensor = finalConfig.sensorId)
        "rotation" -> brushParams.copy(rotationSensor = finalConfig.sensorId)
        "h" -> {
            if (finalConfig.enabled && brushParams.hueJitter <= 0.001) {
                val j = finalConfig.strength.toDouble().coerceIn(0.05, 1.0)
                brushParams = brushParams.copy(hueJitter = j)
                BrushStoreVm.stage(this, com.reverie.paint.core.brushstore.ControlId.HUE_JITTER, j)
            } else if (!finalConfig.enabled && brushParams.hueJitter > 0.001) {
                brushParams = brushParams.copy(hueJitter = 0.0)
                BrushStoreVm.stage(this, com.reverie.paint.core.brushstore.ControlId.HUE_JITTER, 0.0)
            }
            brushParams
        }
        "s" -> {
            if (finalConfig.enabled && brushParams.satJitter <= 0.001) {
                val j = finalConfig.strength.toDouble().coerceIn(0.05, 1.0)
                brushParams = brushParams.copy(satJitter = j)
                BrushStoreVm.stage(this, com.reverie.paint.core.brushstore.ControlId.SAT_JITTER, j)
            } else if (!finalConfig.enabled && brushParams.satJitter > 0.001) {
                brushParams = brushParams.copy(satJitter = 0.0)
                BrushStoreVm.stage(this, com.reverie.paint.core.brushstore.ControlId.SAT_JITTER, 0.0)
            }
            brushParams
        }
        "v" -> {
            if (finalConfig.enabled && brushParams.valJitter <= 0.001) {
                val j = finalConfig.strength.toDouble().coerceIn(0.05, 1.0)
                brushParams = brushParams.copy(valJitter = j)
                BrushStoreVm.stage(this, com.reverie.paint.core.brushstore.ControlId.VAL_JITTER, j)
            } else if (!finalConfig.enabled && brushParams.valJitter > 0.001) {
                brushParams = brushParams.copy(valJitter = 0.0)
                BrushStoreVm.stage(this, com.reverie.paint.core.brushstore.ControlId.VAL_JITTER, 0.0)
            }
            brushParams
        }
        else -> brushParams
    }
    val cid = when (canonicalKey.lowercase()) {
        "size" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SIZE
        "opacity" -> com.reverie.paint.core.brushstore.ControlId.CURVE_OPACITY
        "flow" -> com.reverie.paint.core.brushstore.ControlId.CURVE_FLOW
        "rotation" -> com.reverie.paint.core.brushstore.ControlId.CURVE_ROTATION
        "scatter" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SCATTER
        "softness" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SOFTNESS
        "spacing" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SPACING
        "smudgerate" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SMUDGE_RATE
        "colorrate" -> com.reverie.paint.core.brushstore.ControlId.CURVE_COLOR_RATE
        "ratio" -> com.reverie.paint.core.brushstore.ControlId.CURVE_RATIO
        "sharpness" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SHARPNESS
        "darken" -> com.reverie.paint.core.brushstore.ControlId.CURVE_DARKEN
        "lightnessstrength" -> com.reverie.paint.core.brushstore.ControlId.CURVE_LIGHTNESS_STRENGTH
        "smudgeradius" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SMUDGE_RADIUS
        "paintthickness" -> com.reverie.paint.core.brushstore.ControlId.CURVE_PAINT_THICKNESS
        "h" -> com.reverie.paint.core.brushstore.ControlId.CURVE_HUE
        "s" -> com.reverie.paint.core.brushstore.ControlId.CURVE_SATURATION
        "v" -> com.reverie.paint.core.brushstore.ControlId.CURVE_VALUE
        else -> null
    }
    if (cid != null) {
        BrushStoreVm.stage(this, cid, finalConfig.toCurveXml())
    }
}

internal fun PaintViewModel.checkBrushSizeLimit() {
    val maxL = effectiveBrushMaxSize
    if (brushSize > maxL) {
        updateBrushSize(maxL)
    }
}

internal fun PaintViewModel.toggleFavoriteBrush(id: String) {
    if (id.isBlank()) return
    favoriteBrushIds = if (favoriteBrushIds.contains(id)) {
        favoriteBrushIds - id
    } else {
        favoriteBrushIds + id
    }
    persistBrushPanelState()
}

internal fun PaintViewModel.isFavoriteBrush(id: String): Boolean = favoriteBrushIds.contains(id)

internal fun PaintViewModel.recordRecentBrush(id: String) {
    if (id.isBlank()) return
    val list = (listOf(id) + recentBrushIds.filter { it != id }).take(com.reverie.paint.core.brushstore.BrushIdPrefs.RECENT_MAX)
    recentBrushIds = list
    persistBrushPanelState()
}

internal fun PaintViewModel.updateBrushPanelViewMode(mode: BrushPanelViewMode) {
    if (brushPanelViewMode == mode) return
    brushPanelViewMode = mode
    persistBrushPanelState()
}

internal fun PaintViewModel.toggleBrushPanelGridView() {
    brushPanelViewMode = if (brushPanelViewMode.isGrid) {
        BrushPanelViewMode.STROKE_ICON_NAME
    } else {
        BrushPanelViewMode.GRID
    }
    persistBrushPanelState()
}

internal fun PaintViewModel.getCategoryPresetScroll(cat: String): Pair<Int, Int> {
    return categoryPresetScrollMap[cat] ?: Pair(brushPresetScrollIndex, brushPresetScrollOffset)
}

internal fun PaintViewModel.saveCategoryPresetScroll(cat: String, index: Int, offset: Int, persist: Boolean = false) {
    brushPresetScrollIndex = index
    brushPresetScrollOffset = offset
    categoryPresetScrollMap = categoryPresetScrollMap.toMutableMap().apply {
        put(cat, Pair(index, offset))
    }
    val t = com.reverie.paint.model.Tool.fromId(currentToolId)
    if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER || t == com.reverie.paint.model.Tool.SMUDGE) {
        val state = toolBrushStates[t.id] ?: ToolBrushState()
        toolBrushStates = toolBrushStates.toMutableMap().apply {
            put(t.id, state.copy(presetScrollIndex = index, presetScrollOffset = offset))
        }
    }
    if (persist) {
        persistBrushPanelState()
    }
}

/**
 * 获取指定工具 (如 "eraser") 的当前有效笔尖尺寸
 */
internal fun PaintViewModel.getToolEffectiveSize(toolId: String): Double {
    val state = toolBrushStates[toolId]
    val curPreset = brushPresets.firstOrNull { it.index == state?.presetIndex }
    val pid = curPreset?.id ?: state?.presetId
    val mem = pid?.takeIf { it.isNotBlank() }?.let { state?.paramMemory?.get(it) }
    mem?.getOrNull(0)?.let { return it }
    val saved = pid?.takeIf { it.isNotBlank() }?.let { BrushStoreVm.readback(it)?.first }
    return saved?.size ?: brushSize
}

internal fun PaintViewModel.persistBrushPanelState() {
    if (!isAppContextReady()) return
    try {
        val p = prefs.edit()
        p.putString("brush_panel_category", brushPanelSelectedCategory)
        p.putInt("brush_cat_scroll_idx", brushCategoryScrollIndex)
        p.putInt("brush_cat_scroll_offset", brushCategoryScrollOffset)
        p.putInt("brush_preset_scroll_idx", brushPresetScrollIndex)
        p.putInt("brush_preset_scroll_offset", brushPresetScrollOffset)
        p.putInt("brush_prop_scroll_val", brushPropertyScrollValue)

        // Persist per-category scroll positions as JSON
        val json = org.json.JSONObject()
        categoryPresetScrollMap.forEach { (cat, pair) ->
            val arr = org.json.JSONArray().apply {
                put(pair.first)
                put(pair.second)
            }
            json.put(cat, arr)
        }
        p.putString("brush_category_preset_scroll_map", json.toString())

        if (brushPanelDetailIndex != null) {
            p.putInt("brush_panel_detail_idx", brushPanelDetailIndex!!)
        } else {
            p.remove("brush_panel_detail_idx")
        }

        p.putString("brush_panel_view_mode", brushPanelViewMode.prefValue)
        p.putBoolean("brush_panel_grid_view", brushPanelViewMode.isGrid)
        if (brushIdPrefsLoaded) {
            p.putString(com.reverie.paint.core.brushstore.BrushIdPrefs.FAV_IDS, com.reverie.paint.core.brushstore.BrushIdPrefs.encodeList(favoriteBrushIds))
            p.putString(com.reverie.paint.core.brushstore.BrushIdPrefs.RECENT_IDS, com.reverie.paint.core.brushstore.BrushIdPrefs.encodeList(recentBrushIds))
        }

        p.apply()
    } catch (_: Exception) {}
}
