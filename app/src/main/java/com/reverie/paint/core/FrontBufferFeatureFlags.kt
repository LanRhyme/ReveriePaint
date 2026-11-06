/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.FrontBufferHolePolicy
import com.reverie.paint.model.HoleMaskGrid

/**
 * 前缓冲低延迟路径的功能开关。进程内只读一次系统属性, 改完需重启 App (`adb shell am force-stop`):
 *
 * ```
 * adb shell setprop debug.reverie.fb_ready_gate 0      # 关闭 L-02 就绪门控 (退回 3.2s 静默窗)
 * adb shell setprop debug.reverie.fb_holes 0           # 关闭孔洞 (前缓冲盖住画布装饰, 旧行为)
 * adb shell setprop debug.reverie.fb_hole_mode hmin    # compose (默认: 覆盖层画进前缓冲) | hmin: 挖孔+窗口重画 (A/B) | e: 命中孔洞即整笔交还主画布
 * adb shell setprop debug.reverie.fb_hole_margin 2     # 掩码膨胀像素
 * adb shell setprop debug.reverie.fb_hole_max_cov 35   # 掩码占屏 > 35% 时这一笔不按住
 * adb shell setprop debug.reverie.fb_snap_hold 1       # 1a: 吸附线笔画也按住 (默认 0 = 走常规路径, 严格同图)
 * adb shell setprop debug.reverie.fb_strict 1          # 严格同一性: 关预测笔尖 (默认 1)
 * adb shell setprop debug.reverie.fb_clear_delay 0     # 抬笔清前缓冲再多等的帧数
 * adb shell setprop debug.reverie.fb_mid_refresh_ms 100 # 运笔中渐进刷新已落定瓦片的最小间隔 (ms, 默认 0=关)
 * ```
 */
internal object FrontBufferFeatureFlags {
    /** L-02: 事件驱动的就绪门控 (每笔落笔评估一次), 关闭时退回原 3.2s 静默窗。 */
    val readyGate: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_ready_gate", 1) != 0 }

    /** H-1/H-2: 孔洞模式 ([FrontBufferHolePolicy.MODE_OFF] / HMIN / E / COMPOSE)。 */
    val holeMode: Int by lazy {
        FrontBufferHolePolicy.parseMode(
            PerfTrace.debugPropInt("debug.reverie.fb_holes", 1),
            PerfTrace.debugPropString("debug.reverie.fb_hole_mode", "compose"),
        )
    }

    /**
     * 前缓冲"真墨"单帧允许烤的最大屏幕面积 (像素)。超过就走主画布兜底。
     *
     * 为什么要有: 放大画布时同一笔在屏幕上的覆盖面积按 zoom² 增长, 每帧烤出来的成本
     * 会到几十~一两百毫秒, 帧率掉到个位数 (实测 7.4x + 400px 笔刷: frame p95 274ms)。
     * 默认 1.2M 像素 (≈4.8MB); 设 0 关闭该保护。
     */
    val maxInkAreaPx: Long by lazy {
        (PerfTrace.debugPropInt("debug.reverie.fb_max_ink_kpx", 1200).coerceAtLeast(0)).toLong() * 1000L
    }

    val holeMargin: Int by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_hole_margin", HoleMaskGrid.DEFAULT_MARGIN).coerceIn(0, 16)
    }

    /** 掩码占屏比例上限 (0..1)。 */
    val holeMaxCoverage: Float by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_hole_max_cov", 35).coerceIn(0, 100) / 100f
    }

    /** H-3: 严格同一性 —— 预测笔尖 (窗口里没有的像素) 不画。 */
    val strict: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_strict", 1) != 0 }

    /** H-4: 抬笔后窗口帧提交再多等的帧数。 */
    val clearDelayFrames: Int by lazy { PerfTrace.debugPropInt("debug.reverie.fb_clear_delay", 0).coerceIn(0, 8) }

    /**
     * 1a: 吸附导引线笔画按住 (前缓冲孔外画导引线)。默认 0 (compose 路径由 isCompose=true 放行)。
     */
    val snapHold: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_snap_hold", 0) != 0 }

    /** 蚂蚁线进 compose (默认 1 开)。 */
    val composeAnts: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_compose_ants", 1) != 0 }

    /** D3: 运笔中渐进刷新已落定瓦片的节流间隔 (ms), 默认 0 (关闭)。 */
    val midStrokeRefreshMs: Long by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_mid_refresh_ms", 0).toLong().coerceAtLeast(0L)
    }

    /** L2: 省电模式下按住真墨期间免节流 (默认 1 开)。 */
    val powersaveExempt: Boolean by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_powersave_exempt", 1) != 0
    }
}
