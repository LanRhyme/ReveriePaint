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
    val lightAzimuthDeg: Float = 135f,
    val lightElevationDeg: Float = 55f,
    val mode: ShadingMode = ShadingMode.CEL,
    val celSteps: CelSteps = CelSteps.ONE_STEP,
    val threshold1: Float = 0.50f,
    val threshold2: Float = 0.30f,
    val softness: Float = 0.05f,
    val shadowColor: Long = 0xFF6E4D5AL,
    val shadowOpacity: Float = 0.60f,
    val lineArtOcclusion: Boolean = false,
    val lineCastDistance: Float = 10.0f,
    val lineCastIntensity: Float = 0.35f,
    val volumeSmoothness: Float = 30.0f,
    val colorEdgeSnapping: Boolean = true,
    val colorEdgeSnapTolerance: Float = 0.50f,
    val morphologicalSmoothRadius: Int = 2,
    val ambientOcclusionIntensity: Float = 0.15f,
    val referenceLineLayerId: Int? = null,
    val useAiModel: Boolean = true,
    val downsampleTargetSize: Int = 1024,
    val materialAdaptiveMetering: Boolean = true,
    val materialMeteringStrength: Float = 0.65f,
    val rollingBallContour: Boolean = true,
    val rollingBallRadius: Int = 8,
    val cleanSmallIslands: Boolean = true,
    val islandThreshold: Int = 18,
    val artistExperienceCurve: Boolean = true
) {
    /**
     * 计算归一化的三维光源方向向量 (Lx, Ly, Lz)
     * 屏幕空间中：+X 向右, +Y 向下, +Z 垂直屏幕朝向观察者
     */
    fun computeLightVector(): FloatArray {
        val azRad = Math.toRadians(lightAzimuthDeg.toDouble())
        val elRad = Math.toRadians(lightElevationDeg.coerceIn(30f, 85f).toDouble())
        val cosEl = kotlin.math.cos(elRad)
        val lx = (cosEl * kotlin.math.cos(azRad)).toFloat()
        // 图像 Y 轴向下，方位角顺时针旋转
        val ly = -(cosEl * kotlin.math.sin(azRad)).toFloat()
        val lz = kotlin.math.sin(elRad).toFloat()

        val len = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
        return if (len > 1e-6f) {
            floatArrayOf(lx / len, ly / len, lz / len)
        } else {
            floatArrayOf(0f, 0f, 1f)
        }
    }
}

/**
 * 智能阴影模型预设
 */
enum class ShadingModelPreset(
    val id: String,
    val fileName: String,
    val defaultSizeBytes: Long,
    val isNormalMap: Boolean
) {
    DEPTH_ANYTHING("depth_anything", "depth_anything_v2_small.onnx", 35_615_956L, false);

    companion object {
        fun fromId(id: String): ShadingModelPreset = DEPTH_ANYTHING
    }
}

/**
 * 模型下载与加载状态
 */
sealed class ModelStatus {
    object NotDownloaded : ModelStatus()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : ModelStatus()
    data class Ready(val modelPath: String, val isCustom: Boolean, val fileName: String, val preset: ShadingModelPreset? = null) : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}
