/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.toCurveXml
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import com.reverie.paint.core.stylus.StylusAudioType
import com.reverie.paint.model.*
import com.reverie.paint.perf.PerfHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import com.reverie.paint.ui.painting.canvas.invalidateFromRender
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
    return brushDynamicOptions[key] ?: com.reverie.paint.model.DynamicOptionConfig.defaultFor(key).let { def ->
        val sensor = when (key.lowercase()) {
            "size" -> brushParams.sizeSensor
            "opacity" -> brushParams.opacitySensor
            "flow" -> brushParams.flowSensor
            "rotation" -> brushParams.rotationSensor
            "scatter" -> brushParams.scatterSensor
            else -> def.sensorId
        }
        def.copy(sensorId = sensor.ifBlank { def.sensorId })
    }.also {
        brushDynamicOptions[key] = it
    }
}

internal fun PaintViewModel.updateBrushDynamicOption(config: com.reverie.paint.model.DynamicOptionConfig) {
    updateBrushDynamicOption(config.optionKey, config)
}

internal fun PaintViewModel.updateBrushDynamicOption(
    key: String,
    config: com.reverie.paint.model.DynamicOptionConfig,
) {
    val finalConfig = if (config.enabled && config.strength <= 0.001f) config.copy(strength = 1.0f) else config
    brushDynamicOptions[key] = finalConfig
    val strength = if (finalConfig.enabled) finalConfig.strength.toDouble() else 0.0
    brushParams = when (key.lowercase()) {
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
        else -> brushParams
    }
    val cid = when (key.lowercase()) {
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
        val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
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
