/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 按工程保存的画布方向二值状态（非文档宽高比、非页面/导出方向）。
 *
 * 只由退出时视图旋转角归一化而来：0°/180° → 横向，90°/270° → 竖向，中间角按 45° 中点就近归一，
 * 恢复只写回 0° 或 90°；只改视图旋转角，不碰缩放/平移/视图中心，不改像素、不进历史、不影响导出，与对称/分格/笔刷独立。
 */
enum class CanvasOrientation(
    /** 写进 .revp meta.json 的值，见 [META_KEY]。 */
    val jsonValue: String,
    /** 恢复时写回视图的旋转角（度）。 */
    val rotationDegrees: Float,
) {
    LANDSCAPE("landscape", 0f),
    PORTRAIT("portrait", 90f);

    companion object {
        /** .revp meta.json 顶层字段名；经 saveRevp 的 extraJson 合并口子写入，无需改 C++。 */
        const val META_KEY = "canvasOrientation"

        /**
         * 以 45° 为中点分段就近归一（中点归入下一段）；非有限值回退横向，不抛错。
         */
        fun fromAngleDegrees(angleDegrees: Float): CanvasOrientation {
            if (!angleDegrees.isFinite()) return LANDSCAPE
            val norm = ((angleDegrees % 360f) + 360f) % 360f
            return when {
                norm < 45f || norm >= 315f -> LANDSCAPE
                norm < 135f -> PORTRAIT
                norm < 225f -> LANDSCAPE
                else -> PORTRAIT
            }
        }

        /** 解析 meta.json 里的值；缺失、未知或空串一律返回 null，调用方走默认横向。 */
        fun fromJsonValue(value: String?): CanvasOrientation? = entries.firstOrNull { it.jsonValue == value }
    }
}
