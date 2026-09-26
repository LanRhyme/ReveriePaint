/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.content.SharedPreferences
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import com.reverie.paint.core.PaintViewModel

/**
 * 性能标尺在**正式版里不存在**: 这里是与 debug 版同签名同语义的空实现。
 *
 * 用变体源集而不是 `if (BuildConfig.DEBUG)` 的原因: release 当前 `isMinifyEnabled = false`,
 * 常量分支不会被 R8 消除, 代码与文案资源仍会留在包里。放在 `src/release/` 的这份空实现
 * 让正式包既没有设置入口, 也不含 HUD 的绘制逻辑与字符串。
 * (调试用的脏区占比/纹理重传等底层打点仍在 [com.reverie.paint.core.PerfTrace] 里,
 * 默认关闭, 需要时用 debug 包或 `setprop debug.reverie.perf 1` 打开。)
 */
internal object PerfHud {

    val enabled: Boolean = false

    fun readPref(prefs: SharedPreferences): Boolean = false

    /** 实验 A 的档位在正式版不存在: 恒返回"不覆盖", 不 honor 任何残留偏好 */
    fun readLiquifyProxyPercent(prefs: SharedPreferences): Int = 0

    /** Phase 3B 的档位在正式版不存在: 恒返回"不覆盖" */
    fun readLiquifyCoalesceSteps(prefs: SharedPreferences): Int = -1

    /** Phase 5 · C2 的预览方式档位在正式版不存在: 恒返回"自动" */
    fun readLiquifyHostDraw(prefs: SharedPreferences): Int = 0

    fun recordDraw(nanos: Long) {}

    fun draw(canvas: Canvas, view: View) {}

    /** 液化网格可视化在正式版不存在(恒关, 也没有网格数据) */
    val gridOverlayEnabled: Boolean = false

    fun setLiquifyGrid(data: FloatArray?) {}

    fun drawLiquifyGrid(canvas: Canvas, vt: com.reverie.paint.model.CanvasViewTransform) {}

    @Composable
    fun SettingsSection(vm: PaintViewModel) {
        // 正式版不提供该入口
    }
}
