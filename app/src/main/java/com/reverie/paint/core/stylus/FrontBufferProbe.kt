/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.os.Build
import android.util.Log

/**
 * 运行时前缓冲探针与兼容性诊断工具。
 * 验证当前系统及硬件环境是否满足 `androidx.graphics.lowlatency.CanvasFrontBufferedRenderer` 运行要求 (API 29+)。
 */
object FrontBufferProbe {
    private const val TAG = "FrontBufferProbe"

    /** 最小支持 API 版本: Android 10 (Q / API 29) */
    const val MIN_SUPPORTED_API = Build.VERSION_CODES.Q

    /**
     * 判断当前设备是否具备运行 CanvasFrontBufferedRenderer 的基础软硬件条件。
     */
    fun isSupported(): Boolean {
        // 1. Android 版本要求 API 29+ (具备 SurfaceControl 与单缓冲支持)
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_API) {
            return false
        }

        // 2. 探活 androidx.graphics 类库是否可正确加载
        return runCatching {
            Class.forName("androidx.graphics.lowlatency.CanvasFrontBufferedRenderer")
            true
        }.getOrElse { e ->
            Log.w(TAG, "CanvasFrontBufferedRenderer class not found: ${e.message}")
            false
        }
    }

    /**
     * 获取详细诊断信息字符串 (供 Debug / 性能日志使用)。
     */
    fun getDiagnosticSummary(): String {
        val sdk = Build.VERSION.SDK_INT
        val supported = isSupported()
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return "FrontBuffer[supported=$supported, sdk=$sdk, device=$manufacturer $model]"
    }
}
