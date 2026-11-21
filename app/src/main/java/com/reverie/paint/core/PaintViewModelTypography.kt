/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.ui.geometry.Offset
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.model.TypographyConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文本排版图层与画布交互生命周期管理 (Vector Text Layers with Multi-Text Support)
 */

private fun renderAndStampTextItems(items: List<TypographyConfig>, brushOpacity: Double) {
    for (item in items) {
        if (item.text.isBlank()) continue
        val renderResult = TypographyEngine.renderToBitmap(item, brushOpacity) ?: continue
        val (textBmp, docLeft, docTop) = renderResult
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(textBmp)
        try {
            ReverieCoreBridge.stampBitmap(docLeft, docTop, stampBmp)
        } finally {
            stampBmp.recycle()
            textBmp.recycle()
        }
    }
}

internal fun PaintViewModel.commitTypographyToCanvas() {
    val cfg = typographyConfig
    val existingLayerId = editingTextLayerId
    val currentItemId = editingTextItemId

    if (cfg.text.isBlank()) {
        cancelTypographyEditing()
        return
    }

    if (existingLayerId != null) {
        val targetIdx = layers.indexOfFirst { it.id == existingLayerId }
        if (targetIdx >= 0) {
            val currentItems = activeTextLayers[existingLayerId] ?: emptyList()
            val existingItemIdx = currentItems.indexOfFirst { it.id == currentItemId }
            val updatedItems = if (existingItemIdx >= 0) {
                currentItems.toMutableList().apply { set(existingItemIdx, cfg) }
            } else {
                currentItems + cfg
            }

            activeTextLayers = activeTextLayers + (existingLayerId to updatedItems)
            runCore(render = true, after = {
                notifyLayerChanged(forceThumbs = true, pixelChanged = true)
            }) {
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(targetIdx)
                ReverieCoreBridge.clearLayer(targetIdx)
                renderAndStampTextItems(updatedItems, brushOpacity)
                ReverieCoreBridge.setCurrentLayer(cur)
            }
        }
    } else {
        // 创建新的独立文字图层
        val pids = currentPaintingPanelIds()
        val wasInside = pids.isNotEmpty()
        if (wasInside) {
            collapsedPanelIds = collapsedPanelIds - pids
        }
        val textSnippet = cfg.text.replace("\n", " ").trim().take(8)
        val defaultTextName = if (LanguageManager.isChinese()) {
            "文本: $textSnippet"
        } else {
            "Text: $textSnippet"
        }
        val initialItems = listOf(cfg)
        runCore(render = true, after = {
            notifyLayerChanged(forceThumbs = true, pixelChanged = true)
            val newLayer = layers.getOrNull(currentLayerIndex)
            if (newLayer != null) {
                activeTextLayers = activeTextLayers + (newLayer.id to initialItems)
            }
        }) {
            executeAddLayerSafely(wasInside) {
                ReverieCoreBridge.addLayer(defaultTextName)
            }
            renderAndStampTextItems(initialItems, brushOpacity)
        }
    }

    editingTextLayerId = null
    editingTextItemId = null
    isTypographyEditing = false
    typographyInputFocusTrigger = 0
    typographySnapGuides = emptyList()
    showActionToast(R.string.toast_text_created, R.drawable.ic_check)
}

/**
 * 激活指定文字图层中的某个文字块进入画布交互编辑模式
 */
internal fun PaintViewModel.startEditingTextLayer(layerId: Long, itemId: String) {
    val items = activeTextLayers[layerId] ?: return
    val item = items.find { it.id == itemId } ?: return
    val targetIdx = layers.indexOfFirst { it.id == layerId }
    if (targetIdx < 0) return

    if (isTypographyEditing) {
        commitTypographyToCanvas()
    }

    selectLayer(targetIdx)
    currentToolId = Tool.TEXT.id
    typographyConfig = item
    brushColor = item.textColor
    if (colorPanelTab != 2) {
        colorSphereBaseHex = item.textColor
    }
    editingTextLayerId = layerId
    editingTextItemId = itemId
    isTypographyEditing = true
    typographyInputFocusTrigger = 0
    typographySnapGuides = emptyList()

    // 清空图层在底层 Krita 中的静态位图，由 CanvasOverlay 视口以 120Hz 实时渲染该图层全部文字
    runCore(render = true) {
        ReverieCoreBridge.clearLayer(targetIdx)
    }
}

/**
 * 发起新文字块创建（若当前处于文字图层则追加到该图层，否则准备新建图层）
 */
internal fun PaintViewModel.startNewTextItem(layerId: Long?, docPos: Offset) {
    if (isTypographyEditing) {
        commitTypographyToCanvas()
    }

    val newId = java.util.UUID.randomUUID().toString()
    val newCfg = TypographyConfig(
        id = newId,
        text = "",
        fontSize = typographyConfig.fontSize,
        fontFamilyName = typographyConfig.fontFamilyName,
        fontPath = typographyConfig.fontPath,
        textColor = brushColor,
        alignment = typographyConfig.alignment,
        isVertical = typographyConfig.isVertical,
        verticalRtl = typographyConfig.verticalRtl,
        posX = docPos.x,
        posY = docPos.y,
        boxWidth = typographyConfig.boxWidth,
        rotationDeg = 0f,
        snapEnabled = typographyConfig.snapEnabled,
    )

    typographyConfig = newCfg
    currentToolId = Tool.TEXT.id
    editingTextItemId = newId
    isTypographyEditing = true
    typographySnapGuides = emptyList()

    if (layerId != null && activeTextLayers.containsKey(layerId)) {
        editingTextLayerId = layerId
        val targetIdx = layers.indexOfFirst { it.id == layerId }
        if (targetIdx >= 0) {
            selectLayer(targetIdx)
            runCore(render = true) {
                ReverieCoreBridge.clearLayer(targetIdx)
            }
        }
    } else {
        editingTextLayerId = null
    }
}

/**
 * 删除文字图层中的某个文字块
 */
internal fun PaintViewModel.deleteTextItem(layerId: Long, itemId: String) {
    if (isTypographyEditing && editingTextLayerId == layerId && editingTextItemId == itemId) {
        cancelTypographyEditing()
    }
    val currentItems = activeTextLayers[layerId] ?: return
    val remaining = currentItems.filterNot { it.id == itemId }
    val targetIdx = layers.indexOfFirst { it.id == layerId }
    if (targetIdx >= 0) {
        runCore(render = true, after = {
            notifyLayerChanged(forceThumbs = true, pixelChanged = true)
            activeTextLayers = if (remaining.isEmpty()) {
                activeTextLayers - layerId
            } else {
                activeTextLayers + (layerId to remaining)
            }
        }) {
            val cur = ReverieCoreBridge.currentLayerIndex()
            ReverieCoreBridge.setCurrentLayer(targetIdx)
            ReverieCoreBridge.clearLayer(targetIdx)
            renderAndStampTextItems(remaining, brushOpacity)
            ReverieCoreBridge.setCurrentLayer(cur)
        }
    }
}

/**
 * 取消当前文字编辑并还原图层原有文本
 */
internal fun PaintViewModel.cancelTypographyEditing() {
    val editId = editingTextLayerId
    if (editId != null) {
        val origItems = activeTextLayers[editId]
        val targetIdx = layers.indexOfFirst { it.id == editId }
        if (origItems != null && targetIdx >= 0) {
            runCore(render = true, after = {
                notifyLayerChanged(forceThumbs = true, pixelChanged = true)
            }) {
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(targetIdx)
                ReverieCoreBridge.clearLayer(targetIdx)
                renderAndStampTextItems(origItems, brushOpacity)
                ReverieCoreBridge.setCurrentLayer(cur)
            }
        }
    }
    editingTextLayerId = null
    editingTextItemId = null
    isTypographyEditing = false
    typographyInputFocusTrigger = 0
    typographySnapGuides = emptyList()
}

/**
 * 将文字图层栅格化为普通光栅像素图层（解除文字矢量绑定）
 */
internal fun PaintViewModel.rasterizeTextLayer(layerId: Long) {
    if (isTypographyEditing && editingTextLayerId == layerId) {
        commitTypographyToCanvas()
    }
    activeTextLayers = activeTextLayers - layerId
    showActionToast(R.string.toast_text_rasterized, R.drawable.ic_layers)
}

/**
 * 确保所有正在编辑的文字图层在保存与导出前完成烘焙
 */
internal fun PaintViewModel.commitAllActiveTextLayersSync() {
    if (isTypographyEditing) {
        commitTypographyToCanvas()
    }
}

internal fun TypographyConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        put("id", id)
        put("text", text)
        put("fontFamilyName", fontFamilyName)
        if (fontPath != null) put("fontPath", fontPath)
        put("fontSize", fontSize.toDouble())
        put("letterSpacingSp", letterSpacingSp.toDouble())
        put("lineHeightMultiplier", lineHeightMultiplier.toDouble())
        put("isBold", isBold)
        put("isItalic", isItalic)
        put("isUnderline", isUnderline)
        put("isAllCaps", isAllCaps)
        put("alignment", alignment)
        put("textColor", textColor)
        put("posX", posX.toDouble())
        put("posY", posY.toDouble())
        put("boxWidth", boxWidth.toDouble())
        put("boxHeight", boxHeight.toDouble())
        put("rotationDeg", rotationDeg.toDouble())
        put("snapEnabled", snapEnabled)
        put("isVertical", isVertical)
        put("verticalRtl", verticalRtl)
    }
}

internal fun typographyConfigFromJson(obj: JSONObject): TypographyConfig {
    val text = obj.optString("text", "")
    return TypographyConfig(
        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
        text = text,
        fontFamilyName = obj.optString("fontFamilyName", "bundled:lxgw_wenkai"),
        fontPath = if (obj.has("fontPath")) obj.optString("fontPath") else null,
        fontSize = obj.optDouble("fontSize", 48.0).toFloat(),
        letterSpacingSp = obj.optDouble("letterSpacingSp", 0.0).toFloat(),
        lineHeightMultiplier = obj.optDouble("lineHeightMultiplier", 1.2).toFloat(),
        isBold = obj.optBoolean("isBold", false),
        isItalic = obj.optBoolean("isItalic", false),
        isUnderline = obj.optBoolean("isUnderline", false),
        isAllCaps = obj.optBoolean("isAllCaps", false),
        alignment = obj.optInt("alignment", 0),
        textColor = obj.optString("textColor", "#FFFFFF"),
        posX = obj.optDouble("posX", 100.0).toFloat(),
        posY = obj.optDouble("posY", 100.0).toFloat(),
        boxWidth = obj.optDouble("boxWidth", 400.0).toFloat(),
        boxHeight = obj.optDouble("boxHeight", 0.0).toFloat(),
        rotationDeg = obj.optDouble("rotationDeg", 0.0).toFloat(),
        snapEnabled = obj.optBoolean("snapEnabled", true),
        isVertical = obj.optBoolean("isVertical", false),
        verticalRtl = obj.optBoolean("verticalRtl", true),
    )
}

internal fun parseTextLayerJson(obj: JSONObject): Pair<Long, List<TypographyConfig>>? {
    val layerId = obj.optLong("layerId", 0L)
    if (layerId == 0L) return null

    if (obj.has("items")) {
        val arr = obj.optJSONArray("items") ?: return layerId to emptyList()
        val list = mutableListOf<TypographyConfig>()
        for (i in 0 until arr.length()) {
            val itemObj = arr.optJSONObject(i) ?: continue
            list.add(typographyConfigFromJson(itemObj))
        }
        return layerId to list
    } else if (obj.has("text")) {
        // 兼容单文本图层格式
        val cfg = typographyConfigFromJson(obj)
        return layerId to listOf(cfg)
    }
    return null
}
