/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.CanvasOrientation

/**
 * 画布方向按工程保存/恢复的唯一出入口：.revp meta.json 顶层 [META_KEY]，经 saveRevp 的 extraJson 合并写入，
 * 无需改 C++，随工程文件走；读失败/缺失/非法值当无记录走默认横向；与像素同写同败，无独立失败模式。
 */

/** 当前视图旋转角归一化后的方向；画布视图尚未建立时返回 null，调用方不写该字段。 */
internal fun PaintViewModel.readCurrentCanvasOrientation(): CanvasOrientation? {
    val angle = runCatching {
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.canvasRotation
    }.getOrNull() ?: return null
    return CanvasOrientation.fromAngleDegrees(angle)
}

/** 拼进 extraJson 的一行（有方向时 `"canvasOrientation": "landscape",`，无方向时空串）。 */
internal fun PaintViewModel.canvasOrientationJsonFragment(): String {
    val orientation = readCurrentCanvasOrientation() ?: pendingCanvasOrientation ?: return ""
    return "\"${CanvasOrientation.META_KEY}\": \"${orientation.jsonValue}\","
}
