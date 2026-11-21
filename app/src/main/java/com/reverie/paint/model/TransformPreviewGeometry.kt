/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.roundToInt

/**
 * 变换预览绘制的纯 Kotlin 几何换算真身。
 *
 * 区分三个坐标空间:
 * - 文档空间 (docWidth x docHeight): `tfState.bounds` 所在空间
 * - 显示缓冲空间 (bmpWidth x bmpHeight): `CanvasTransformOverlay` 的绘制目标空间 (`scX = bmpW / docW`)
 * - 预览位图空间 (previewWidth x previewHeight): `TransformPreviewPolicy` 封顶后的预览位图像素空间
 */
object TransformPreviewGeometry {

    data class PixelRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /**
     * 仿射变换整图预览在显示缓冲空间中的目标尺寸。
     * `CanvasTransformOverlay` 的 `withTransform` 已按 `scX/scY` 将文档中心映射至显示缓冲空间,
     * 因此整图预览的目标边长就是显示缓冲尺寸本身, 不应再乘一次 `scX/scY`。
     */
    fun affineDestinationSize(bmpWidth: Int, bmpHeight: Int): Pair<Int, Int> {
        return bmpWidth.coerceAtLeast(1) to bmpHeight.coerceAtLeast(1)
    }

    /**
     * 将文档空间矩形 [left, top, right, bottom] 映射为预览位图空间中的整数裁剪矩形 (用于透视/扭曲模式)。
     */
    fun docBoundsToPreviewCrop(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        docWidth: Int,
        docHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): PixelRect {
        val safeDocW = docWidth.coerceAtLeast(1).toFloat()
        val safeDocH = docHeight.coerceAtLeast(1).toFloat()
        val safePrevW = previewWidth.coerceAtLeast(1)
        val safePrevH = previewHeight.coerceAtLeast(1)
        val docToPrevX = safePrevW.toFloat() / safeDocW
        val docToPrevY = safePrevH.toFloat() / safeDocH

        val cropLeft = (left * docToPrevX).toInt().coerceIn(0, safePrevW - 1)
        val cropTop = (top * docToPrevY).toInt().coerceIn(0, safePrevH - 1)
        val maxW = safePrevW - cropLeft
        val maxH = safePrevH - cropTop
        val cropW = ((right - left) * docToPrevX).roundToInt().coerceIn(1, maxW)
        val cropH = ((bottom - top) * docToPrevY).roundToInt().coerceIn(1, maxH)
        return PixelRect(
            left = cropLeft,
            top = cropTop,
            right = cropLeft + cropW,
            bottom = cropTop + cropH,
        )
    }
}
