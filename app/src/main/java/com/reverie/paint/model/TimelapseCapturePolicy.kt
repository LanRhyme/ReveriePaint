/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 延时摄影抓帧的**成本预算**(纯 Kotlin, 无 Android 依赖, 可 JVM 单测)。
 *
 * 为什么需要: 抓帧走引擎的 `renderDocumentThumb` → Krita 的
 * `KisPaintDevice::createThumbnailUncached` → `createThumbnailDeviceInternal`, 那是
 * **按输出像素**做最近邻随机访问(`srcIter->moveTo()` = 一次 tile 哈希查找 + memcpy)。
 * 真机实测(OPD2413 / Android 16):
 *
 *  | 文档 | 抓帧尺寸 | 输出像素 | 实测 |
 *  |---|---|---|---|
 *  | 6400x10240 (65MP) | 675x1080 | 0.73MP | **90~95 ms** |
 *  | 6400x8192 (52MP) | 675x1080 | 0.73MP | **109 ms** |
 *  | 2048x2048 | 1080x1080 | 1.17MP | **110~117 ms** |
 *
 * 也就是说单帧成本 ∝ **输出像素**(约 95~120 ns/px), 与画布多大基本无关 —— 每输出一个
 * 像素就是一次随机 tile 访问, 这是 Krita 那条 createThumbnail 路径的固有开销(正确实现
 * 应在 5~10 ns/px)。而抓帧跑在渲染线程(`runCore` 队列)上, 期间画布完全不动。
 *
 * 叠加默认策略"每笔抬笔抓一帧 + 200ms 节流" ⇒ 一边画一边抓时引擎有一半时间在做缩略图,
 * 每一笔抬笔后画布冻 ~0.1 秒。这是"画完一笔顿一下"的直接来源(与画布大小无关, 大画布只是
 * 更容易被注意到)。
 *
 * 这里能做的两条约束(Kotlin 侧, 不动引擎):
 *  1. 单帧输出像素上限 [MAX_CAPTURE_PX]: 延时视频最终是 720p/1080p, 单帧没必要按 2160 抓;
 *  2. 抓帧占空比上限 [DUTY_DIVISOR]: 抓帧总耗时不超过墙钟的 1/8, 即最小间隔至少是单帧
 *     成本估算的 8 倍 —— 把"每 200ms 来一发 90ms"换成"每 ~700ms 来一发 90ms"。
 *
 * 真正的解法在引擎侧: 用一张常驻的低分辨率投影(LoD 平面)或按行连续读 tile 来生成缩略图,
 * 把 120ns/px 降到 10ns/px 量级; 那样连这里的收紧都不需要。
 *
 * 用户设置的分辨率仍然生效, 只是作为**上限**; 间隔也只会被拉长, 不会被缩短。
 */
object TimelapseCapturePolicy {
    /** 单帧允许的输出像素上限 (约 1330x750, 足以支撑 720p/1080p 延时视频)。 */
    const val MAX_CAPTURE_PX = 1_000_000L

    /** 抓帧成本的保守模型: 每输出像素的纳秒数 (真机实测 95~120ns/px, 取上界)。 */
    const val COST_NS_PER_PX = 120L

    /** 占空比上限的倒数: 抓帧耗时 ≤ 墙钟 1/8 (每 8 帧墙钟最多卡掉 1 帧的量)。 */
    const val DUTY_DIVISOR = 8L

    /** 间隔上限, 与用户设置的 coerce 区间一致。 */
    const val MAX_INTERVAL_MS = 5_000L

    /** 抓帧计划: 目标尺寸与有效最小间隔。 */
    data class Plan(
        val width: Int,
        val height: Int,
        val minIntervalMs: Long,
        val estimatedCostMs: Long,
    )

    /**
     * 计算一次抓帧的目标尺寸与有效节流间隔。
     *
     * @param docW/docH 文档尺寸; <= 0 时返回 null (调用方自行回退)。
     * @param requestedMaxDim 用户设置的抓帧长边 (设置项, 作为上限)。
     * @param userMinIntervalMs 用户设置的最小抓帧间隔 (只会被拉长)。
     */
    fun plan(
        docW: Int,
        docH: Int,
        requestedMaxDim: Int,
        userMinIntervalMs: Long,
    ): Plan? {
        if (docW <= 0 || docH <= 0 || requestedMaxDim <= 0) return null
        var w: Int
        var h: Int
        if (docW >= docH) {
            w = requestedMaxDim
            h = (docH.toLong() * requestedMaxDim / docW).toInt().coerceAtLeast(2)
        } else {
            h = requestedMaxDim
            w = (docW.toLong() * requestedMaxDim / docH).toInt().coerceAtLeast(2)
        }
        // 输出像素超预算: 按面积等比缩(整数, 至少 2px)。
        val px = w.toLong() * h
        if (px > MAX_CAPTURE_PX) {
            val scale = kotlin.math.sqrt(MAX_CAPTURE_PX.toDouble() / px.toDouble())
            w = (w * scale).toInt().coerceAtLeast(2)
            h = (h * scale).toInt().coerceAtLeast(2)
        }
        val outPx = w.toLong() * h
        val costMs = (outPx * COST_NS_PER_PX + 999_999L) / 1_000_000L
        val floor = costMs * DUTY_DIVISOR
        val interval = maxOf(userMinIntervalMs.coerceIn(50L, MAX_INTERVAL_MS), floor)
            .coerceAtMost(MAX_INTERVAL_MS)
        return Plan(width = w, height = h, minIntervalMs = interval, estimatedCostMs = costMs)
    }
}
