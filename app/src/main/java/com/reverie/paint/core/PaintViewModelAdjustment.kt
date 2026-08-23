/*
 * Copyright (c) 2026 ReveriePaint contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core

import com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE


// PaintViewModel 调整图层扩展 (同包 partial 模式)。
// 创建真 KisAdjustmentLayer 并把滤镜参数写入层配置 (非破坏, 不落像素)。

internal fun PaintViewModel.addAdjustmentLayer(
    name: String = "滤镜图层",
    filterType: Int = 0,
    onCreated: (Int) -> Unit = {},
) {
    if (recorder.recording) {
        // 与 addFillLayer 同构: type=3 回放端建中性调整层, 参数由随后的
        // L_ADJ_CONFIG 事件补齐 (见 commitAdjustmentConfig / PlaybackEngine)。
        recorder.layerOp(L_ADD_LAYER_TYPE, 0, "$name|3|0xFFFFFFFF")
    }
    runCore(after = {
        notifyLayerChanged()
        onCreated(currentLayerIndex)
    }) {
        ReverieCoreBridge.createAdjustmentLayer(name, filterType, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * 把调整面板的当前预览参数提交为调整层的持久配置。
 * 复用单层滤镜预览管线留下的临时字段: 标量类取 lastFilterPreviewParams,
 * 曲线/渐变映射取 lastCurvesLUT / lastGradientMapLut。
 */
internal fun PaintViewModel.commitAdjustmentConfig(index: Int, filterId: Int) {
    var p1 = 0.0
    var p2 = 0.0
    var p3 = 0.0
    var p4 = 0.0
    var lut: ByteArray? = null
    when (filterId) {
        13 -> lut = lastCurvesLUT?.takeIf { it.size >= 768 }
        30 -> lut = lastGradientMapLut?.let(::packIntsLE1024)
        else -> lastFilterPreviewParams?.let { params ->
            p1 = params.getOrElse(1) { 0.0 }
            p2 = params.getOrElse(2) { 0.0 }
            p3 = params.getOrElse(3) { 0.0 }
            p4 = params.getOrElse(4) { 0.0 }
        }
    }
    lastFilterPreviewParams = null
    lastCurvesLUT = null
    lastGradientMapLut = null
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setAdjustmentLayerConfig(index, filterId, p1, p2, p3, p4, lut)
    }
}

/** 256 个 int 按 little-endian 打包为 1024 字节 (渐变映射 LUT 的流内表示)。 */
internal fun packIntsLE1024(lut: IntArray): ByteArray {
    val bytes = ByteArray(1024)
    for (i in 0 until minOf(256, lut.size)) {
        val v = lut[i]
        bytes[i * 4] = (v and 0xFF).toByte()
        bytes[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
    }
    return bytes
}
