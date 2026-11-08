/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 变换工具预览位图的分辨率预算 (纯逻辑, 无 Android 依赖, 可 JVM 单测)。
 *
 * 为什么: `startTransformPreview()` 原来按文档尺寸分配预览位图, 6400x10240 上就是一张
 * 250 MB 的 ARGB_8888。而 `CanvasTransformOverlay` 会直接 `drawBitmap` /
 * `drawBitmapMesh` 它 —— 超过 HWUI 单次绘制上限 (实测 250 MB 直接抛
 * `Canvas: trying to draw too large(262144000bytes) bitmap`), **点变换就崩**。
 *
 * 预览只需要"看得出内容大概在哪"; 变换框/手柄/网格全都按文档坐标画, 与位图分辨率无关。
 * 因此这里把长边封顶到 [MAX_PREVIEW_DIM], 保持长宽比、只缩不放:
 *   - 6400x10240 -> 2560x4096 (10.5 MP / 42 MB)
 *   - 2048x2048  -> 不缩
 * 绘制侧按 `previewW / docW` 的比例把这张位图拉伸回文档空间即可
 * (见 `CanvasTransformOverlay` 的 kx/ky)。
 */
object TransformPreviewPolicy {
    /** 预览位图长边上限。4096 让 42 MB 封顶, 远离 HWUI 的单次绘制上限, 缩放下也够看清内容。 */
    const val MAX_PREVIEW_DIM = 4096

    /** 通常低于 [MAX_PREVIEW_DIM] 的小图就直接按原尺寸预览 (不放大)。 */
    fun previewSize(docW: Int, docH: Int, maxDim: Int = MAX_PREVIEW_DIM): Pair<Int, Int> {
        if (docW <= 0 || docH <= 0) return 1 to 1
        val cap = if (maxDim <= 0) MAX_PREVIEW_DIM else maxDim
        val longest = maxOf(docW, docH)
        if (longest <= cap) return docW to docH
        val scale = cap.toDouble() / longest.toDouble()
        val w = (docW * scale).toInt().coerceIn(1, docW)
        val h = (docH * scale).toInt().coerceIn(1, docH)
        return w to h
    }
}
