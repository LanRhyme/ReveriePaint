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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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

    // 自动寻找潜在的线稿图层：优先保留用户已选图层，若未选或失效则智能推测
    val existingLineId = shadingAssistConfig.referenceLineLayerId
    val isExistingValid = existingLineId != null && existingLineId != currentIdx &&
        layers.any { it.index == existingLineId && !it.isGroup && it.nodeType == 0 }

    val activeLineLayerId = if (isExistingValid) {
        existingLineId
    } else {
        val candidateLineLayer = layers.firstOrNull {
            it.index != currentIdx && !it.isGroup && it.nodeType == 0 &&
            (it.name.contains("线", ignoreCase = true) || it.name.contains("line", ignoreCase = true))
        } ?: layers.filter {
            it.index > currentIdx && !it.isGroup && it.nodeType == 0 && it.visible
        }.minByOrNull { it.index }
        candidateLineLayer?.index
    }

    shadingAssistConfig = shadingAssistConfig.copy(referenceLineLayerId = activeLineLayerId)

    // 计算交互预览尺寸 (最大边 512px，保证流畅 60fps 实时光源拖动)
    val maxDim = max(coreW, coreH).coerceAtLeast(1)
    val previewScale = if (maxDim > 512) 512f / maxDim else 1f
    val previewW = (coreW * previewScale).toInt().coerceAtLeast(64)
    val previewH = (coreH * previewScale).toInt().coerceAtLeast(64)

    shadingAssistDialogOpen = true
    shadingAssistIsProcessing = true

    shadingAssistComputeJob?.cancel()
    shadingAssistRenderJob?.cancel()

    shadingAssistComputeJob = viewModelScope.launch(Dispatchers.Default) {
        // 采样底色图层画布空间位图 (通过 runCore 在渲染线程安全执行，严禁阻塞 UI 线程)
        val baseBmp = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
        val baseOk = renderLayerPixels(currentIdx, baseBmp)
        if (!baseOk || !isActive) {
            baseBmp.recycle()
            withContext(Dispatchers.Main) {
                shadingAssistDialogOpen = false
                shadingAssistIsProcessing = false
                if (baseOk) {
                    // cancelled
                } else {
                    Toast.makeText(appContext, R.string.shading_assist_toast_need_layer, Toast.LENGTH_SHORT).show()
                }
            }
            return@launch
        }

        withContext(Dispatchers.Main) {
            shadingAssistBaseBitmap?.recycle()
            shadingAssistBaseBitmap = baseBmp
        }

        // 采样线稿图层画布空间位图 (如有)
        var lineBmp: Bitmap? = null
        if (activeLineLayerId != null) {
            val lb = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
            if (renderLayerPixels(activeLineLayerId, lb)) {
                lineBmp = lb
            } else {
                lb.recycle()
            }
        }

        withContext(Dispatchers.Main) {
            shadingAssistLineBitmap?.recycle()
            shadingAssistLineBitmap = lineBmp
        }

        val modelFile = ShadingModelManager.getActiveModelFile(appContext)
        val rawDepth = if (modelFile != null) {
            ShadingAssistEngine.estimateRawDepth(
                baseBitmap = baseBmp,
                lineBitmap = lineBmp,
                modelFile = modelFile,
                targetResolution = previewW
            )
        } else {
            null
        }

        if (!isActive) {
            return@launch
        }

        shadingAssistRawDepth = rawDepth

        val normalField = if (rawDepth != null) {
            ShadingAssistEngine.computeNormalFieldFromDepth(rawDepth, shadingAssistConfig.volumeSmoothness)
        } else {
            ShadingAssistEngine.generateCspNormalField(baseBmp, shadingAssistConfig.volumeSmoothness, previewW)
        }
        shadingAssistNormalField = normalField

        val preview = ShadingAssistEngine.renderShadingBitmap(
            normalField = normalField,
            baseBitmap = baseBmp,
            lineBitmap = lineBmp,
            config = shadingAssistConfig,
            outWidth = previewW,
            outHeight = previewH
        )

        withContext(Dispatchers.Main) {
            if (!isActive) {
                preview?.recycle()
            } else {
                shadingAssistPreviewBitmap?.recycle()
                shadingAssistPreviewBitmap = preview
                shadingAssistIsProcessing = false
            }
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
    val modelModeChanged = newConfig.useAiModel != shadingAssistConfig.useAiModel
    val smoothnessChanged = newConfig.volumeSmoothness != shadingAssistConfig.volumeSmoothness
    shadingAssistConfig = newConfig

    val baseBmp = shadingAssistBaseBitmap ?: return
    if (baseBmp.isRecycled) return

    if (lineLayerChanged || modelModeChanged) {
        shadingAssistComputeJob?.cancel()
        shadingAssistIsProcessing = true
        shadingAssistComputeJob = viewModelScope.launch(Dispatchers.Default) {
            if (lineLayerChanged) {
                val lineId = newConfig.referenceLineLayerId
                val lineBmp = if (lineId != null) {
                    val lb = Bitmap.createBitmap(baseBmp.width, baseBmp.height, Bitmap.Config.ARGB_8888)
                    if (renderLayerPixels(lineId, lb)) lb else {
                        lb.recycle()
                        null
                    }
                } else {
                    null
                }
                withContext(Dispatchers.Main) {
                    shadingAssistLineBitmap?.recycle()
                    shadingAssistLineBitmap = lineBmp
                }
            }

            val curLineBmp = shadingAssistLineBitmap
            val modelFile = ShadingModelManager.getActiveModelFile(appContext)
            val rawDepth = if (modelFile != null && newConfig.useAiModel) {
                ShadingAssistEngine.estimateRawDepth(
                    baseBitmap = baseBmp,
                    lineBitmap = curLineBmp,
                    modelFile = modelFile,
                    targetResolution = baseBmp.width
                )
            } else {
                null
            }

            if (!isActive) return@launch

            shadingAssistRawDepth = rawDepth
            val normalField = if (rawDepth != null) {
                ShadingAssistEngine.computeNormalFieldFromDepth(rawDepth, newConfig.volumeSmoothness)
            } else {
                ShadingAssistEngine.generateCspNormalField(baseBmp, newConfig.volumeSmoothness, baseBmp.width)
            }
            shadingAssistNormalField = normalField

            val preview = ShadingAssistEngine.renderShadingBitmap(
                normalField = normalField,
                baseBitmap = baseBmp,
                lineBitmap = curLineBmp,
                config = shadingAssistConfig,
                outWidth = baseBmp.width,
                outHeight = baseBmp.height
            )

            withContext(Dispatchers.Main) {
                if (!isActive) {
                    preview?.recycle()
                } else {
                    shadingAssistPreviewBitmap?.recycle()
                    shadingAssistPreviewBitmap = preview
                    shadingAssistIsProcessing = false
                }
            }
        }
    } else if (smoothnessChanged) {
        // 仅平滑度改变：直接使用已缓存的原始深度图重算法线场 (耗时 ~3ms，无需重复执行 ONNX 推理)
        val rawDepth = shadingAssistRawDepth
        if (rawDepth != null) {
            shadingAssistComputeJob?.cancel()
            shadingAssistComputeJob = viewModelScope.launch(Dispatchers.Default) {
                val normalField = ShadingAssistEngine.computeNormalFieldFromDepth(rawDepth, newConfig.volumeSmoothness)
                if (!isActive) return@launch
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
                    if (!isActive) {
                        preview?.recycle()
                    } else {
                        shadingAssistPreviewBitmap?.recycle()
                        shadingAssistPreviewBitmap = preview
                    }
                }
            }
        } else {
            triggerShadingPreviewRender()
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
    if (baseBmp.isRecycled) return

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
        if (!isActive) {
            preview?.recycle()
            return@launch
        }
        withContext(Dispatchers.Main) {
            if (!isActive) {
                preview?.recycle()
            } else {
                shadingAssistPreviewBitmap?.recycle()
                shadingAssistPreviewBitmap = preview
            }
        }
    }
}

/**
 * 关闭阴影辅助面板并清理位图缓存
 */
internal fun PaintViewModel.closeShadingAssist() {
    shadingAssistDialogOpen = false
    shadingAssistComputeJob?.cancel()
    shadingAssistComputeJob = null
    shadingAssistRenderJob?.cancel()
    shadingAssistRenderJob = null
    shadingAssistRawDepth = null
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
    shadingAssistComputeJob?.cancel()
    shadingAssistComputeJob = viewModelScope.launch(Dispatchers.Default) {
        val fullW = coreW
        val fullH = coreH

        // 1. 全分辨率采样底色与线稿 (通过 runCore 在渲染线程安全执行，严禁直接调用 JNI)
        val fullBase = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        if (!renderLayerPixels(baseLayerIdx, fullBase) || !isActive) {
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
            if (renderLayerPixels(lineId, lb)) lb else {
                lb.recycle()
                null
            }
        } else {
            null
        }

        // 2. 生成高精度全尺寸法线场
        val modelFile = ShadingModelManager.getActiveModelFile(appContext)
        if (modelFile == null && shadingAssistConfig.useAiModel) {
            fullBase.recycle()
            fullLine?.recycle()
            withContext(Dispatchers.Main) {
                shadingAssistIsProcessing = false
                Toast.makeText(appContext, R.string.shading_assist_model_not_installed, Toast.LENGTH_SHORT).show()
            }
            return@launch
        }

        val targetRes = min(fullW, fullH).coerceIn(512, shadingAssistConfig.downsampleTargetSize)
        val rawDepth = if (modelFile != null && shadingAssistConfig.useAiModel) {
            ShadingAssistEngine.estimateRawDepth(
                baseBitmap = fullBase,
                lineBitmap = fullLine,
                modelFile = modelFile,
                targetResolution = targetRes
            )
        } else {
            null
        }

        if (!isActive) {
            fullBase.recycle()
            fullLine?.recycle()
            return@launch
        }

        val normalField = if (rawDepth != null) {
            ShadingAssistEngine.computeNormalFieldFromDepth(rawDepth, shadingAssistConfig.volumeSmoothness)
        } else {
            ShadingAssistEngine.generateCspNormalField(fullBase, shadingAssistConfig.volumeSmoothness, targetRes)
        }

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

        if (fullShadow == null || !isActive) {
            fullShadow?.recycle()
            withContext(Dispatchers.Main) {
                shadingAssistIsProcessing = false
            }
            return@launch
        }

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
            val newLayerIdx = ReverieCoreBridge.currentLayerIndex()
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

/**
 * 采样图层在画布坐标空间中的像素内容 (必须在 runCore 渲染线程执行以保证底层 KisPaintDevice 线程安全)
 */
private suspend fun PaintViewModel.renderLayerPixels(layerIndex: Int, bitmap: Bitmap): Boolean =
    suspendCancellableCoroutine { cont ->
        runCore(render = false) {
            val success = try {
                ReverieCoreBridge.renderLayerCanvas(layerIndex, bitmap)
            } catch (e: UnsatisfiedLinkError) {
                ReverieCoreBridge.renderLayerThumb(layerIndex, bitmap)
            }
            if (cont.isActive) {
                cont.resume(success)
            }
        }
    }
