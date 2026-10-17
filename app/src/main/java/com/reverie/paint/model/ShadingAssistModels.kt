/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 智能阴影辅助风格模式
 */
enum class ShadingMode(val id: String) {
    CEL("cel"),         // 赛璐珞硬切
    SMOOTH("smooth");   // 柔和过渡

    companion object {
        fun fromId(id: String): ShadingMode = entries.firstOrNull { it.id == id } ?: CEL
    }
}

/**
 * 赛璐珞阶梯级数
 */
enum class CelSteps(val count: Int) {
    ONE_STEP(1),    // 单阶阴影
    TWO_STEP(2);    // 双阶阴影 (深浅两层)

    companion object {
        fun fromCount(count: Int): CelSteps = entries.firstOrNull { it.count == count } ?: ONE_STEP
    }
}

/**
 * 智能阴影辅助核心配置模型
 */
data class ShadingAssistConfig(
    val lightAzimuthDeg: Float = 150f,
    val lightElevationDeg: Float = 50f,
    val mode: ShadingMode = ShadingMode.CEL,
    val celSteps: CelSteps = CelSteps.ONE_STEP,
    val threshold1: Float = 0.50f,
    val threshold2: Float = 0.25f,
    val softness: Float = 0.05f,
    val shadowColor: Long = 0xFF6E4D5AL,
    val shadowOpacity: Float = 0.55f,
    val lineArtOcclusion: Boolean = true,
    val lineCastDistance: Float = 22.0f,
    val lineCastIntensity: Float = 1.2f,
    val volumeSmoothness: Float = 30.0f,
    val referenceLineLayerId: Int? = null,
    val useAiModel: Boolean = true,
    val downsampleTargetSize: Int = 1024
) {
    /**
     * 计算归一化的三维光源方向向量 (Lx, Ly, Lz)
     * 屏幕空间中：+X 向右, +Y 向下, +Z 垂直屏幕朝向观察者
     */
    fun computeLightVector(): FloatArray {
        val azRad = Math.toRadians(lightAzimuthDeg.toDouble())
        val elRad = Math.toRadians(lightElevationDeg.coerceIn(30f, 85f).toDouble())
        val cosEl = cos(elRad)
        val lx = (cosEl * cos(azRad)).toFloat()
        // 图像 Y 轴向下，方位角顺时针旋转
        val ly = -(cosEl * sin(azRad)).toFloat()
        val lz = sin(elRad).toFloat()

        val len = sqrt(lx * lx + ly * ly + lz * lz)
        return if (len > 1e-6f) {
            floatArrayOf(lx / len, ly / len, lz / len)
        } else {
            floatArrayOf(0f, 0f, 1f)
        }
    }
}

/**
 * 模型下载与加载状态
 */
sealed class ModelStatus {
    object NotDownloaded : ModelStatus()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : ModelStatus()
    data class Ready(val modelPath: String, val isCustom: Boolean, val fileName: String) : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}
