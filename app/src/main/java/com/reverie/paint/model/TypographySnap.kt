/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs

/**
 * 文本排版磁吸参考线类型
 */
enum class SnapGuideType {
    CENTER,
    MARGIN,
}

/**
 * 文本对齐参考线
 * @param isVertical true 为垂直参考线 (X 坐标固定，沿 Y 轴贯穿)，false 为水平参考线 (Y 坐标固定，沿 X 轴贯穿)
 * @param position 在文档物理像素坐标系中的绝对坐标 (X 或 Y)
 * @param type 参考线类型 (中心线或安全边距线)
 */
data class TypographySnapGuide(
    val isVertical: Boolean,
    val position: Float,
    val type: SnapGuideType,
)

/**
 * 文本对齐吸附计算结果
 */
data class TypographySnapResult(
    val snappedLeft: Float,
    val snappedTop: Float,
    val guides: List<TypographySnapGuide>,
)

/**
 * 文本工具磁吸对齐计算引擎 (纯 Kotlin，零 Android 依赖)
 */
object TypographySnapHelper {

    private data class Candidate(
        val delta: Float,
        val guidePos: Float,
        val type: SnapGuideType,
        val targetCoord: Float,
    )

    /**
     * 计算文字框在画布上的磁吸吸附。
     * 支持画布水平/垂直中心线吸附以及画布边缘安全边距吸附。
     *
     * @param boxLeft 待吸附文字框左上角 X
     * @param boxTop 待吸附文字框左上角 Y
     * @param boxWidth 文字框宽度
     * @param boxHeight 文字框高度
     * @param canvasWidth 画布宽度
     * @param canvasHeight 画布高度
     * @param threshold 吸附磁力吸引距离阈值 (文档物理坐标)
     * @param safeMargin 画布边缘安全边距 (文档物理坐标)
     */
    fun calculateSnap(
        boxLeft: Float,
        boxTop: Float,
        boxWidth: Float,
        boxHeight: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        threshold: Float = 16f,
        safeMargin: Float = 48f,
    ): TypographySnapResult {
        if (canvasWidth <= 0 || canvasHeight <= 0 ||
            !boxLeft.isFinite() || !boxTop.isFinite() ||
            !boxWidth.isFinite() || !boxHeight.isFinite() ||
            boxWidth <= 0f || boxHeight <= 0f ||
            !threshold.isFinite() || threshold <= 0f
        ) {
            return TypographySnapResult(boxLeft, boxTop, emptyList())
        }

        var resultLeft = boxLeft
        var resultTop = boxTop
        val guides = mutableListOf<TypographySnapGuide>()

        // ================= X 轴 (垂直参考线) =================
        val boxCenterX = boxLeft + boxWidth * 0.5f
        val canvasCenterX = canvasWidth * 0.5f
        val leftMargin = safeMargin.coerceIn(0f, canvasWidth * 0.45f)
        val rightMargin = (canvasWidth - safeMargin).coerceIn(canvasWidth * 0.55f, canvasWidth.toFloat())

        val xCandidates = mutableListOf<Candidate>()

        // 1. 画布水平中心线对齐 (文字框中心对齐)
        val centerDiffX = canvasCenterX - boxCenterX
        if (abs(centerDiffX) <= threshold) {
            xCandidates.add(Candidate(abs(centerDiffX), canvasCenterX, SnapGuideType.CENTER, boxLeft + centerDiffX))
        }

        // 2. 左边缘安全边距对齐 (文字框左边对齐)
        val leftMarginDiff = leftMargin - boxLeft
        if (abs(leftMarginDiff) <= threshold) {
            xCandidates.add(Candidate(abs(leftMarginDiff), leftMargin, SnapGuideType.MARGIN, leftMargin))
        }

        // 3. 右边缘安全边距对齐 (文字框右边对齐)
        val rightMarginDiff = rightMargin - (boxLeft + boxWidth)
        if (abs(rightMarginDiff) <= threshold) {
            xCandidates.add(Candidate(abs(rightMarginDiff), rightMargin, SnapGuideType.MARGIN, rightMargin - boxWidth))
        }

        // 挑选最近的 X 轴吸附目标
        xCandidates.minByOrNull { it.delta }?.let { bestX ->
            resultLeft = bestX.targetCoord
            guides.add(TypographySnapGuide(isVertical = true, position = bestX.guidePos, type = bestX.type))
        }

        // ================= Y 轴 (水平参考线) =================
        val boxCenterY = boxTop + boxHeight * 0.5f
        val canvasCenterY = canvasHeight * 0.5f
        val topMargin = safeMargin.coerceIn(0f, canvasHeight * 0.45f)
        val bottomMargin = (canvasHeight - safeMargin).coerceIn(canvasHeight * 0.55f, canvasHeight.toFloat())

        val yCandidates = mutableListOf<Candidate>()

        // 1. 画布垂直中心线对齐 (文字框中心对齐)
        val centerDiffY = canvasCenterY - boxCenterY
        if (abs(centerDiffY) <= threshold) {
            yCandidates.add(Candidate(abs(centerDiffY), canvasCenterY, SnapGuideType.CENTER, boxTop + centerDiffY))
        }

        // 2. 顶部安全边距对齐 (文字框顶边对齐)
        val topMarginDiff = topMargin - boxTop
        if (abs(topMarginDiff) <= threshold) {
            yCandidates.add(Candidate(abs(topMarginDiff), topMargin, SnapGuideType.MARGIN, topMargin))
        }

        // 3. 底部安全边距对齐 (文字框底边对齐)
        val bottomMarginDiff = bottomMargin - (boxTop + boxHeight)
        if (abs(bottomMarginDiff) <= threshold) {
            yCandidates.add(Candidate(abs(bottomMarginDiff), bottomMargin, SnapGuideType.MARGIN, bottomMargin - boxHeight))
        }

        // 挑选最近的 Y 轴吸附目标
        yCandidates.minByOrNull { it.delta }?.let { bestY ->
            resultTop = bestY.targetCoord
            guides.add(TypographySnapGuide(isVertical = false, position = bestY.guidePos, type = bestY.type))
        }

        return TypographySnapResult(resultLeft, resultTop, guides)
    }
}
