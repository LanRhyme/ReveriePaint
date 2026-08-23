/*
 * Copyright (c) 2026 ReveriePaint contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core

import com.reverie.paint.model.AdjustmentConfigCodec
import com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE
import com.reverie.paint.model.RecordingEvents.L_ADJ_CONFIG


// PaintViewModel 调整图层扩展 (同包 partial 模式)。
// 创建真 KisAdjustmentLayer 并把滤镜参数写入层配置 (非破坏, 不落像素)。

internal fun PaintViewModel.addAdjustmentLayer(
    name: String = "滤镜图层",
    filterType: Int = 0,
    p1: Double = 0.0,
    p2: Double = 0.0,
    p3: Double = 0.0,
    p4: Double = 0.0,
    lut: ByteArray? = null,
    onCreated: (Int) -> Unit = {},
) {
    if (recorder.recording) {
        // 与 addFillLayer 同构: type=3 回放端建调整层, 初始参数由随后的
        // L_ADJ_CONFIG 事件补齐 (见 PlaybackEngine)。
        recorder.layerOp(L_ADD_LAYER_TYPE, 0, "$name|3|0xFFFFFFFF")
        if (p1 != 0.0 || p2 != 0.0 || p3 != 0.0 || p4 != 0.0 || lut != null) {
            recorder.layerOp(
                L_ADJ_CONFIG,
                -1,
                AdjustmentConfigCodec.encode(filterType, p1, p2, p3, p4, lut),
            )
        }
    }
    runCore(after = {
        notifyLayerChanged()
        onCreated(currentLayerIndex)
    }) {
        ReverieCoreBridge.createAdjustmentLayer(name, filterType, p1, p2, p3, p4)
    }
}

/**
 * 面板滑条实时预览: 只把参数推入层配置触发 merger 重算, 不录事件、不动临时字段。
 * 调整层模式不走像素预览三步 (与 merger 重算互踩)。
 */
internal fun PaintViewModel.previewAdjustmentConfig(
    index: Int,
    filterId: Int,
    p1: Double,
    p2: Double,
    p3: Double,
    p4: Double,
    lut: ByteArray? = null,
) {
    runCore(render = false) {
        ReverieCoreBridge.setAdjustmentLayerConfig(index, filterId, p1, p2, p3, p4, lut)
    }
}

/**
 * 把调整面板当前参数提交为持久配置 (✓ 按钮): 记录事件 + 推入引擎。
 * 参数由面板直接给出 (标量类来自 adjustParamsOf, LUT 类打包字节)。
 */
internal fun PaintViewModel.commitAdjustmentConfig(
    index: Int,
    filterId: Int,
    p1: Double,
    p2: Double,
    p3: Double,
    p4: Double,
    lut: ByteArray? = null,
) {
    if (recorder.recording) {
        recorder.layerOp(
            L_ADJ_CONFIG,
            index,
            AdjustmentConfigCodec.encode(filterId, p1, p2, p3, p4, lut),
        )
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setAdjustmentLayerConfig(index, filterId, p1, p2, p3, p4, lut)
    }
}

/**
 * 取消编辑: 把进入面板时的配置快照原样推回 (不录事件)。
 */
internal fun PaintViewModel.restoreAdjustmentConfig(index: Int, savedJson: String) {
    val cfg = AdjustmentConfigCodec.decodeJson(savedJson) ?: return
    runCore(render = false) {
        ReverieCoreBridge.setAdjustmentLayerConfig(index, cfg.type, cfg.p1, cfg.p2, cfg.p3, cfg.p4, cfg.lut)
    }
}

/** 面板进入时抓取当前配置 JSON 快照 (取消回滚用)。 */
internal fun PaintViewModel.snapshotAdjustmentConfig(index: Int): String =
    ReverieCoreBridge.getAdjustmentLayerConfig(index)

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
