/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.ui.home.SettingCategoryTitle
import com.reverie.paint.ui.home.SettingGroup
import com.reverie.paint.ui.home.SettingSwitchGroupItem
import com.reverie.paint.ui.home.settingGroupShape

/**
 * 性能标尺 (开发/量测用, **debug 构建专属**)。
 *
 * 定位: 它回答的是"卡在哪"这类研发问题 —— 渲染走的是全量/增量/跳过哪条路径、
 * 每帧纹理重传多少 MB、脏区占比多少(= 分块上传的收益上限)、保存耗时里快照/编码/
 * 写盘各占多少。这些数字是决定要不要做 tile 化缓冲、动态分辨率的前提。
 *
 * 释放版里没有这个东西: [`app/src/release/.../PerfHud.kt`] 提供同名同签名的空实现,
 * 正式包既没有设置入口、也不含 HUD 绘制代码与文案资源。要量数据时用 debug 包,
 * 或在正式包上用 `adb shell setprop debug.reverie.perf 1`(那种情况下只有 logcat,
 * 不显示 HUD)。
 */
internal object PerfHud {

    /** 是否显示标尺: 跟随设置页开关或 setprop (见 PerfTrace) */
    val enabled: Boolean get() = PerfTrace.enabled

    /** 设置页偏好 (仅 debug 构建读取; release 恒 false, 防止残留偏好默默开着标尺) */
    fun readPref(prefs: SharedPreferences): Boolean = prefs.getBoolean("perfHud", false)

    /** 记录一次 onDraw 的耗时, 汇总成 p95 */
    fun recordDraw(nanos: Long) = PerfTrace.drawFrame(nanos)

    // 颜色刻意不取主题色: 它要压在任意画布内容上, 只有"半透明黑底 + 白字"才稳定可读
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bgPaint = Paint().apply { color = 0x88101010.toInt() }
    private var cachedText = ""
    private var cachedLines = emptyArray<String>()

    /**
     * 在画布左侧偏中(避开顶栏与底部工具条)叠加标尺。
     * 文本每 250ms 才由 PerfTrace 重建一次, 这里再按"内容变化"才 split, 避免标尺自己
     * 成为掉帧与 GC 的来源 —— 否则量出来的数据不可信。
     */
    fun draw(canvas: Canvas, view: View) {
        val text = PerfTrace.hudText()
        if (text.isEmpty()) return
        if (text != cachedText) {
            cachedText = text
            cachedLines = text.split('\n').toTypedArray()
        }
        val d = view.resources.displayMetrics.density
        textPaint.textSize = 11f * d
        val lineHeight = 14f * d
        val x = 10f * d
        var y = view.height * 0.30f
        val save = canvas.save()
        canvas.setMatrix(null) // 忽略画布自身的缩放/旋转/平移, 固定画在屏幕坐标系
        canvas.drawRect(
            x - 6f * d,
            y - lineHeight + 2f * d,
            x + 215f * d,
            y + lineHeight * (cachedLines.size - 1) + 4f * d,
            bgPaint,
        )
        for (line in cachedLines) {
            canvas.drawText(line, x, y, textPaint)
            y += lineHeight
        }
        canvas.restoreToCount(save)
    }

    /** 设置页"诊断"分组里的标尺开关 (debug 构建才有这个入口) */
    @Composable
    fun SettingsSection(vm: PaintViewModel) {
        SettingCategoryTitle(stringResource(R.string.settings_diagnostics))
        SettingGroup {
            SettingSwitchGroupItem(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.settings_perf_hud),
                summary = stringResource(R.string.settings_perf_hud_sub),
                checked = vm.perfHudEnabled,
                shape = settingGroupShape(0, 1),
                onCheckedChange = { vm.updatePerfHudEnabled(it) },
            )
        }
    }
}
