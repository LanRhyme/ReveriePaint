/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 选区叠加层(遮罩位图)的分辨率预算 (纯逻辑, 无 Android 依赖, 可 JVM 单测)。
 *
 * 这张位图只用来在画布上画"未选中区域的斑马纹/半透明遮罩", 它会被
 * `CanvasOverlay` 拉伸到画布矩形 (`drawBitmap(bmp, null, sceneRect)`), 因此**不需要**文档
 * 分辨率 —— 屏幕上看得出 2~3 像素的边界就够了。
 *
 * 但历史上它一直按 `renderW/renderH`(= 文档尺寸)要像素, 于是在 6400x10240 上:
 *   - C++ `selectionOverlayScaled` 里一份 `QVector<quint32>` = 250 MB;
 *   - JNI 回的 `IntArray` = 250 MB;
 *   - Kotlin 的 ARGB_8888 位图 = 250 MB。
 * 真机实测: 在大画布上做一次套索, Native Heap 从 1.77 GB 涨到 2.87 GB (+1.1 GB);
 * 而自由套索拖动时这条路径还是**节流重入**的 (每次拖动预览都重来一遍)。
 *
 * 这里把总像素封顶, 保持文档长宽比、只缩不放:
 *   - 6400x10240 -> 1146x1834 (2.1 MP, 每份 8 MB)
 *   - 3840x2160  -> 1932x1087 (2.1 MP)
 *   - 1920x1080  -> 不缩 (本来就低于预算)
 * 2 MP 是"看得清边界 + 三份合计 ≤ 24 MB"的折中; 需要更省时调用方可传更小的 `maxPx`。
 */
object SelectionOverlayPolicy {
    /** 叠加层默认总像素上限: 正好容纳 1920x1080 (2.07 MP), 常见 1080p 文档完全不缩放。 */
    const val MAX_OVERLAY_PX = 2_100_000L

    /**
     * 计算叠加层尺寸 (与文档同比例, 总像素 ≤ [maxPx], 不放大, 每边 ≥ 2)。
     *
     * @return `width to height`;文档尺寸非法时返回 `1 to 1`。
     */
    fun overlaySize(docW: Int, docH: Int, maxPx: Long = MAX_OVERLAY_PX): Pair<Int, Int> {
        if (docW <= 0 || docH <= 0) return 1 to 1
        val budget = if (maxPx <= 0L) MAX_OVERLAY_PX else maxPx
        val px = docW.toLong() * docH.toLong()
        if (px <= budget) return docW to docH
        val scale = kotlin.math.sqrt(budget.toDouble() / px.toDouble())
        val w = (docW * scale).toInt().coerceIn(2, docW)
        val h = (docH * scale).toInt().coerceIn(2, docH)
        return w to h
    }
}
