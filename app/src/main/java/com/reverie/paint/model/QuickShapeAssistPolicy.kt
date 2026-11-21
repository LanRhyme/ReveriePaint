/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 辅助绘画模式下速创形状 (QuickShape) 的分模式准入策略与对称重放采样转换 (纯 Kotlin 逻辑)。
 *
 * 分模式准入原则:
 * - OFF 或未启用辅助吸附 (`assistedDrawing == false`): 允许
 * - SYMMETRY: `applyAssistedDrawing` 不改写笔画点, 仅对结果施加对称镜像 -> 允许, 且速创预览/提交/取消同步镜像全部分支
 * - PERSPECTIVE (3D 空间网格): `applyAssistedDrawing` 直接返回 `currentPt`, 仅作视觉空间参考 -> 允许
 * - GRID_2D / ISOMETRIC / PERSPECTIVE (2D 灭点): `applyAssistedDrawing` 实时将笔画锁在轴向/灭点射线上,
 *   与速创停顿拟合存在语义冲突与对齐破坏 -> 维持禁用
 */
object QuickShapeAssistPolicy {

    const val STRIDE_6 = 6
    const val STRIDE_7 = 7

    fun isQuickShapeAllowed(guide: DrawingGuideConfig): Boolean {
        if (!guide.assistedDrawing || guide.mode == GuideMode.OFF) return true
        return when (guide.mode) {
            GuideMode.OFF -> true
            GuideMode.SYMMETRY -> true
            GuideMode.PERSPECTIVE -> guide.perspective3d
            GuideMode.GRID_2D, GuideMode.ISOMETRIC -> false
        }
    }

    /**
     * 将速创主笔的 6 元采样流 (`[x, y, pressure, tiltX, tiltY, rotation]`) 映射为各对称镜像分支的
     * 7 元采样流 (`[mx, my, pressure, tiltX, tiltY, rotation, 0f]`), 供 `touchStrokeMirrorBatch`
     * 实时镜像出墨或提交阶段回退重放使用。
     */
    fun buildSymmetricReplayBranches7(
        primarySamples6: FloatArray,
        guide: DrawingGuideConfig,
        docWidth: Int,
        docHeight: Int,
    ): List<FloatArray> {
        if (guide.mode != GuideMode.SYMMETRY || !guide.assistedDrawing) return emptyList()
        if (docWidth <= 0 || docHeight <= 0) return emptyList()
        if (primarySamples6.size < STRIDE_6 * 2 || primarySamples6.size % STRIDE_6 != 0) return emptyList()

        val sampleCount = primarySamples6.size / STRIDE_6
        val firstBranches = guide.computeSymmetricPoints(
            Point2D(primarySamples6[0], primarySamples6[1]),
            docWidth,
            docHeight,
        )
        val branchCount = firstBranches.size
        if (branchCount <= 0) return emptyList()

        val branches = List(branchCount) { FloatArray(sampleCount * STRIDE_7) }
        val tmpX = FloatArray(branchCount)
        val tmpY = FloatArray(branchCount)

        for (i in 0 until sampleCount) {
            val base6 = i * STRIDE_6
            val x = primarySamples6[base6]
            val y = primarySamples6[base6 + 1]
            val p = primarySamples6[base6 + 2]
            val tx = primarySamples6[base6 + 3]
            val ty = primarySamples6[base6 + 4]
            val rot = primarySamples6[base6 + 5]

            val written = guide.writeSymmetricPoints(x, y, docWidth, docHeight, tmpX, tmpY)
            if (written < branchCount) return emptyList()

            val base7 = i * STRIDE_7
            for (b in 0 until branchCount) {
                val dst = branches[b]
                dst[base7] = tmpX[b]
                dst[base7 + 1] = tmpY[b]
                dst[base7 + 2] = p
                dst[base7 + 3] = tx
                dst[base7 + 4] = ty
                dst[base7 + 5] = rot
                dst[base7 + 6] = 0f
            }
        }
        return branches
    }

    /**
     * 将 7 元镜像分支采样重打包为 6 元采样, 供 `touchStrokeMoveBatch` 回退重放使用。
     */
    fun repackBranch7To6(branch7: FloatArray): FloatArray {
        if (branch7.isEmpty() || branch7.size % STRIDE_7 != 0) return FloatArray(0)
        val count = branch7.size / STRIDE_7
        val out = FloatArray(count * STRIDE_6)
        for (i in 0 until count) {
            val src = i * STRIDE_7
            val dst = i * STRIDE_6
            out[dst] = branch7[src]
            out[dst + 1] = branch7[src + 1]
            out[dst + 2] = branch7[src + 2]
            out[dst + 3] = branch7[src + 3]
            out[dst + 4] = branch7[src + 4]
            out[dst + 5] = branch7[src + 5]
        }
        return out
    }
}
