/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.ShadingAssistConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 智能阴影辅助 (Shading Assist) ViewModel 扩展。
 * 负责底色与线稿位图采样、法线生成调度、实时交互光照渲染、模型管理联动以及图层盖印生成。
 */

/**
 * 打开阴影辅助面板并完成首帧采样与法线预计算
 */
internal fun PaintViewModel.openShadingAssist() {
    if (coreW <= 0 || coreH <= 0) return

    val currentIdx = currentLayerIndex
    val currentLayer = layers.firstOrNull { it.index == currentIdx } ?: return
    if (currentLayer.isGroup) return

    // 检查并刷新 ONNX 模型状态
    ShadingModelManager.checkStatus(appContext)

    // 自动寻找潜在的线稿图层：优先寻找底色图层上方最近的一个可见普通绘制图层
    val candidateLineLayer = layers.filter {
        it.index > currentIdx && !it.isGroup && it.nodeType == 0 && it.visible
    }.minByOrNull { it.index }

    val initialLineLayerId = candidateLineLayer?.index
    shadingAssistConfig = shadingAssistConfig.copy(referenceLineLayerId = initialLineLayerId)

    // 计算交互预览尺寸 (最大边 512px，保证流畅 60fps 实时光源拖动)
    val maxDim = max(coreW, coreH).coerceAtLeast(1)
    val previewScale = if (maxDim > 512) 512f / maxDim else 1f
    val previewW = (coreW * previewScale).toInt().coerceAtLeast(64)
    val previewH = (coreH * previewScale).toInt().coerceAtLeast(64)

    // 采样底色图层缩略位图
    val baseBmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
    if (!ReverieCoreBridge.renderLayerThumb(currentIdx, baseBmp)) {
        baseBmp.recycle()
        Toast.makeText(appContext, R.string.shading_assist_toast_need_layer, Toast.LENGTH_SHORT).show()
        return
    }
    shadingAssistBaseBitmap?.recycle()
    shadingAssistBaseBitmap = baseBmp

    // 采样线稿图层缩略位图 (如有)
    shadingAssistLineBitmap?.recycle()
    shadingAssistLineBitmap = null
    if (initialLineLayerId != null) {
        val lineBmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
        if (ReverieCoreBridge.renderLayerThumb(initialLineLayerId, lineBmp)) {
            shadingAssistLineBitmap = lineBmp
        } else {
            lineBmp.recycle()
        }
    }

    shadingAssistDialogOpen = true
    shadingAssistIsProcessing = true

    viewModelScope.launch(Dispatchers.Default) {
        val modelFile = ShadingModelManager.getActiveModelFile(appContext)
        val normalField = ShadingAssistEngine.generateNormalField(
            baseBitmap = baseBmp,
            lineBitmap = shadingAssistLineBitmap,
            modelFile = modelFile,
            targetResolution = previewW
        )
        shadingAssistNormalField = normalField

        val preview = ShadingAssistEngine.renderShadingBitmap(
            normalField = normalField,
            baseBitmap = baseBmp,
            lineBitmap = shadingAssistLineBitmap,
            config = shadingAssistConfig,
            outWidth = previewW,
            outHeight = previewH
        )

        withContext(Dispatchers.Main) {
            shadingAssistPreviewBitmap?.recycle()
            shadingAssistPreviewBitmap = preview
            shadingAssistIsProcessing = false
        }
    }
}

/**
 * 拖动 3D 光源小球时高频触发更新 (60 FPS 瞬时响应)
 */
internal fun PaintViewModel.updateShadingLight(azimuth: Float, elevation: Float) {
    shadingAssistConfig = shadingAssistConfig.copy(
        lightAzimuthDeg = azimuth,
        lightElevationDeg = elevation
    )
    triggerShadingPreviewRender()
}

/**
 * 更新阴影配置参数 (赛璐珞/平滑切换、阈值、软硬度、阴影色或参考线稿图层)
 */
internal fun PaintViewModel.updateShadingConfig(
    newConfig: ShadingAssistConfig,
    reloadLineart: Boolean = false
) {
    val lineLayerChanged = reloadLineart || (newConfig.referenceLineLayerId != shadingAssistConfig.referenceLineLayerId)
    shadingAssistConfig = newConfig

    val baseBmp = shadingAssistBaseBitmap ?: return
    if (lineLayerChanged) {
        shadingAssistLineBitmap?.recycle()
        shadingAssistLineBitmap = null
        val lineId = newConfig.referenceLineLayerId
        if (lineId != null) {
            val lineBmp = Bitmap.createBitmap(baseBmp.width, baseBmp.height, Bitmap.Config.ARGB_8888)
            if (ReverieCoreBridge.renderLayerThumb(lineId, lineBmp)) {
                shadingAssistLineBitmap = lineBmp
            } else {
                lineBmp.recycle()
            }
        }

        // 重新计算表面法线场
        shadingAssistIsProcessing = true
        viewModelScope.launch(Dispatchers.Default) {
            val modelFile = ShadingModelManager.getActiveModelFile(appContext)
            val normalField = ShadingAssistEngine.generateNormalField(
                baseBitmap = baseBmp,
                lineBitmap = shadingAssistLineBitmap,
                modelFile = modelFile,
                targetResolution = baseBmp.width
            )
            shadingAssistNormalField = normalField

            val preview = ShadingAssistEngine.renderShadingBitmap(
                normalField = normalField,
                baseBitmap = baseBmp,
                lineBitmap = shadingAssistLineBitmap,
                config = shadingAssistConfig,
                outWidth = baseBmp.width,
                outHeight = baseBmp.height
            )

            withContext(Dispatchers.Main) {
                shadingAssistPreviewBitmap?.recycle()
                shadingAssistPreviewBitmap = preview
                shadingAssistIsProcessing = false
            }
        }
    } else {
        triggerShadingPreviewRender()
    }
}

/**
 * 刷新交互预览渲染位图
 */
private fun PaintViewModel.triggerShadingPreviewRender() {
    val normalField = shadingAssistNormalField ?: return
    val baseBmp = shadingAssistBaseBitmap ?: return

    shadingAssistRenderJob?.cancel()
    shadingAssistRenderJob = viewModelScope.launch(Dispatchers.Default) {
        val preview = ShadingAssistEngine.renderShadingBitmap(
            normalField = normalField,
            baseBitmap = baseBmp,
            lineBitmap = shadingAssistLineBitmap,
            config = shadingAssistConfig,
            outWidth = baseBmp.width,
            outHeight = baseBmp.height
        )
        withContext(Dispatchers.Main) {
            shadingAssistPreviewBitmap?.recycle()
            shadingAssistPreviewBitmap = preview
        }
    }
}

/**
 * 关闭阴影辅助面板并清理位图缓存
 */
internal fun PaintViewModel.closeShadingAssist() {
    shadingAssistDialogOpen = false
    shadingAssistRenderJob?.cancel()
    shadingAssistRenderJob = null
    shadingAssistNormalField = null

    shadingAssistPreviewBitmap?.recycle()
    shadingAssistPreviewBitmap = null

    shadingAssistBaseBitmap?.recycle()
    shadingAssistBaseBitmap = null

    shadingAssistLineBitmap?.recycle()
    shadingAssistLineBitmap = null
}

/**
 * 确认应用：在全画布分辨率下计算高质量阴影，并在底色上方创建带剪贴蒙版的正片叠底图层
 */
internal fun PaintViewModel.applyShadingAssist(onComplete: () -> Unit = {}) {
    val baseLayerIdx = currentLayerIndex
    if (coreW <= 0 || coreH <= 0 || shadingAssistIsProcessing) return

    shadingAssistIsProcessing = true

    viewModelScope.launch(Dispatchers.Default) {
        val fullW = coreW
        val fullH = coreH

        // 1. 全分辨率采样底色与线稿
        val fullBase = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        if (!ReverieCoreBridge.renderLayerThumb(baseLayerIdx, fullBase)) {
            fullBase.recycle()
            withContext(Dispatchers.Main) {
                shadingAssistIsProcessing = false
                Toast.makeText(appContext, R.string.shading_assist_toast_need_layer, Toast.LENGTH_SHORT).show()
            }
            return@launch
        }

        val lineId = shadingAssistConfig.referenceLineLayerId
        val fullLine = if (lineId != null) {
            val lb = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
            if (ReverieCoreBridge.renderLayerThumb(lineId, lb)) lb else {
                lb.recycle()
                null
            }
        } else {
            null
        }

        // 2. 生成高精度全尺寸法线场
        val modelFile = ShadingModelManager.getActiveModelFile(appContext)
        val normalField = ShadingAssistEngine.generateNormalField(
            baseBitmap = fullBase,
            lineBitmap = fullLine,
            modelFile = modelFile,
            targetResolution = min(fullW, fullH).coerceIn(512, shadingAssistConfig.downsampleTargetSize)
        )

        // 3. 全分辨率光照与线稿遮挡计算
        val fullShadow = ShadingAssistEngine.renderShadingBitmap(
            normalField = normalField,
            baseBitmap = fullBase,
            lineBitmap = fullLine,
            config = shadingAssistConfig,
            outWidth = fullW,
            outHeight = fullH
        )

        fullBase.recycle()
        fullLine?.recycle()

        // 4. 转换 R/B 通道适配 Krita 原生内存布局
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(fullShadow)

        // 5. 提交引擎线程创建图层并盖印
        runCore(after = {
            clearLayerSelection()
            notifyLayerChanged(pixelChanged = true)
            closeShadingAssist()
            Toast.makeText(appContext, R.string.shading_assist_toast_success, Toast.LENGTH_SHORT).show()
            onComplete()
        }) {
            val shadowName = appContext.getString(R.string.shading_assist_layer_name)
            // 新建图层默认插入在当前激活图层 (底色图层) 之上
            ReverieCoreBridge.addLayer(shadowName)
            val newLayerIdx = currentLayerIndex
            // 设定正片叠底
            ReverieCoreBridge.setLayerBlendMode(newLayerIdx, "multiply")
            // 开启剪贴蒙版 (Clipped Mask)
            ReverieCoreBridge.setLayerClipped(newLayerIdx, true)
            // 盖印阴影位图
            try {
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
            } finally {
                stampBmp.recycle()
            }
        }
    }
}
