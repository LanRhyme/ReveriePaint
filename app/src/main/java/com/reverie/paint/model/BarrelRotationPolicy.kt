/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 笔身旋转 (Barrel Rotation) 口径换算 —— 纯函数, 供 JVM 单测。
 *
 * 背景: Android 手写笔没有专用"笔轴自转"接口, 但 Wacom 系笔尖 (Art Pen 2 等) 会把笔轴自转
 * 塞进通用旋转轴 `MotionEvent.AXIS_RZ`(弧度)。Krita/Qt 的 Android 端就是这么取的
 * (qtbase 提交 11238262 "[android] Support stylus barrel rotation"), 口径为:
 *
 * ```
 * rotationDeg = remainder(toDegrees(rzRadians) + 180, 360)   // (-180, 180]
 * ```
 *
 * 即 Android 的 0° 指向屏幕下方, 而 Krita(Qt) 的 0° 指向上方, 故 +180° 翻转后再归一。
 *
 * 与倾角方位角 (AXIS_ORIENTATION) 完全无关: 后者是笔尖指向的方位角, 拿去当自转会在画弧线时
 * 让 dab 剧烈撕裂 (见 [com.reverie.paint.ui.painting.canvas.CanvasTouchView.updateStylusSensors]
 * 的历史结论), 因此本换算只接受 AXIS_RZ。
 *
 * 若设备不上报 AXIS_RZ (绝大多数 Android 笔), 调用方必须保持 0 —— 因为"没有数据"的原始值恰好
 * 是 0 弧度, 直接换算会得到 180°, 把 Rotation 动态顶到曲线末端, 反而破坏老用户的笔刷表现。
 */
object BarrelRotationPolicy {
    /** 弧度 → Krita 口径的角度, (-180, 180]; 非有限值按 0 (无旋转输入) 处理 */
    fun fromRadians(rzRadians: Float): Double = fromRadians(rzRadians.toDouble())

    fun fromRadians(rzRadians: Double): Double {
        if (!rzRadians.isFinite()) return 0.0
        return normalizeDegrees(Math.toDegrees(rzRadians) + 180.0)
    }

    /**
     * 笔画采样下发前的兜底: 非有限值归 0, 幅值钳到 [-180, 180]。
     * Krita 的 Rotation 传感器按 rotation()/180 取值, 只有这个区间能落进曲线 [0,1]。
     */
    fun strokeComponent(deg: Double): Double =
        if (deg.isFinite()) deg.coerceIn(-180.0, 180.0) else 0.0

    /**
     * 归一化到 (-180, 180] (与 Qt/Krita 同口径的 remainder 语义)。
     * 注意 +180 与 -180 在 remainder 下同值, 这里统一取 +180, 避免曲线上出现方向突变。
     */
    fun normalizeDegrees(deg: Double): Double {
        if (!deg.isFinite()) return 0.0
        var r = ((deg % 360.0) + 540.0) % 360.0 - 180.0
        if (r <= -180.0) r += 360.0
        return r
    }
}
