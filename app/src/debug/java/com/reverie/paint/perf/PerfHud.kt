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
import com.reverie.paint.model.CanvasViewTransform
import com.reverie.paint.ui.home.SettingCategoryTitle
import com.reverie.paint.ui.home.SettingDropdownGroupItem
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

    /** 实验 A: 应用内代理分辨率档位 (0 = 跟随 property/构建档位) */
    fun readLiquifyProxyPercent(prefs: SharedPreferences): Int =
        prefs.getInt("liquifyProxyPercent", 0)

    /** Phase 3B: 应用内合并步数档位 (-1 = 跟随 property/构建档位) */
    fun readLiquifyCoalesceSteps(prefs: SharedPreferences): Int =
        prefs.getInt("liquifyCoalesceSteps", -1)

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

    // ---- 液化网格可视化 (Phase 2 自检用, 见 docs/RENDER-OPTIMIZATION.md §9.1) ----
    /** 是否把当前液化网格叠加到画布上: `setprop debug.reverie.lqgrid 1` */
    val gridOverlayEnabled: Boolean get() = enabled && PerfTrace.gridOverlayByProp

    private var gridData: FloatArray? = null

    /** 由引擎线程每秒取一次的快照 (见 PaintViewModel.pollLiquifyGrid) */
    fun setLiquifyGrid(data: FloatArray?) {
        gridData = data
    }

    private val gridLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0xFFFFC107.toInt() // 琥珀: 位移方向
    }
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt() // 浅蓝: 位移后的位置
    }
    private val gridScratch = FloatArray(2)

    /**
     * 把当前液化网格画成位移场: 每个采样点从 `original` 画一条线到 `original + offset`, 终点画点。
     * 用途是在写 CPU/GPU Preview 之前, 肉眼确认三件事与最终结果一致:
     * ① 网格几何(单元格步长与 bounds) ② 位移方向(向右拖 ⇒ dx > 0) ③ 文档→屏幕的坐标映射。
     *
     * 直接画在屏幕坐标系(复用与手势/光标同一套 [CanvasViewTransform.docToScreen]), 且**只画采样点**
     * (最多约 40×40), 避免标尺自己成为掉帧来源。
     */
    fun drawLiquifyGrid(canvas: Canvas, vt: CanvasViewTransform) {
        val g = gridData ?: return
        if (g.size < 8) return
        val cols = g[4].toInt()
        val rows = g[5].toInt()
        val count = g[7].toInt()
        if (cols <= 0 || rows <= 0 || count <= 0) return
        val step = maxOf(1, maxOf(cols, rows) / 40)
        val save = canvas.save()
        canvas.setMatrix(null) // 屏幕坐标系
        var i = 0
        while (i < count) {
            val base = 8 + i * 4
            if (base + 3 < g.size) {
                val col = i % cols
                val row = i / cols
                if (col % step == 0 && row % step == 0) {
                    val ox = g[base]
                    val oy = g[base + 1]
                    val dx = g[base + 2]
                    val dy = g[base + 3]
                    vt.docToScreen(ox, oy, gridScratch)
                    val sx = gridScratch[0]
                    val sy = gridScratch[1]
                    if (dx != 0f || dy != 0f) {
                        vt.docToScreen(ox + dx, oy + dy, gridScratch)
                        canvas.drawLine(sx, sy, gridScratch[0], gridScratch[1], gridLinkPaint)
                        canvas.drawCircle(gridScratch[0], gridScratch[1], 2f, gridDotPaint)
                    }
                }
            }
            i++
        }
        canvas.restoreToCount(save)
    }

    /** 液化预览代理分辨率档位的可选值(与下拉项一一对应) */
    private val PROXY_VALUES = intArrayOf(0, 100, 75, 50, 25)

    /** latest-state-wins 合并步数的可选值(与下拉项一一对应) */
    private val COALESCE_VALUES = intArrayOf(-1, 0, 2, 4, 8)

    /** 设置页"诊断"分组: 标尺开关 + 液化实验档位 (debug 构建才有这个入口) */
    @Composable
    fun SettingsSection(vm: PaintViewModel) {
        SettingCategoryTitle(stringResource(R.string.settings_diagnostics))
        SettingGroup {
            SettingSwitchGroupItem(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.settings_perf_hud),
                summary = stringResource(R.string.settings_perf_hud_sub),
                checked = vm.perfHudEnabled,
                shape = settingGroupShape(0, 3),
                onCheckedChange = { vm.updatePerfHudEnabled(it) },
            )
            val autoText = stringResource(R.string.settings_experiment_auto)
            val offText = stringResource(R.string.settings_experiment_off)
            SettingDropdownGroupItem(
                title = stringResource(R.string.settings_liquify_proxy),
                summary = stringResource(R.string.settings_liquify_proxy_sub),
                currentText = if (vm.liquifyProxyPercent <= 0) autoText else "${vm.liquifyProxyPercent}%",
                options = listOf(autoText, "100%", "75%", "50%", "25%"),
                shape = settingGroupShape(1, 3),
                onSelect = { idx -> vm.updateLiquifyProxyPercent(PROXY_VALUES[idx]) },
            )
            SettingDropdownGroupItem(
                title = stringResource(R.string.settings_liquify_coalesce),
                summary = stringResource(R.string.settings_liquify_coalesce_sub),
                currentText = when {
                    vm.liquifyCoalesceSteps < 0 -> autoText
                    vm.liquifyCoalesceSteps == 0 -> offText
                    else -> vm.liquifyCoalesceSteps.toString()
                },
                options = listOf(autoText, offText, "2", "4", "8"),
                shape = settingGroupShape(2, 3),
                onSelect = { idx -> vm.updateLiquifyCoalesceSteps(COALESCE_VALUES[idx]) },
            )
        }
    }
}
