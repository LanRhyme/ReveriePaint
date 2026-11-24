/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

/**
 * 判定一份原生崩溃日志是否属于「前缓冲 (低延迟真墨) 拆解竞态」。
 *
 * 已知两种表现, 都发生在系统图形栈里、Kotlin 侧无法捕获:
 *
 * 1. 栈里直接出现 androidx 的 `libgraphics-core.so`
 *    (`SurfaceControl` 拆解竞态, fault addr 0x0)。
 * 2. 栈里没有 `libgraphics-core.so`, 只有 `libgui.so` + `libandroid_runtime.so`,
 *    且崩溃时前缓冲开关是开着的 —— 也就是 Surface/SurfaceControl 已经被系统销毁
 *    (页面切换/退出绘画页) 之后, 还有事务打到 libgui 的空指针上。
 *    线上 d8f74afe88 (vivo V2230A, 1.6.2)、7f68bfcd44 (Mi Pad 5, 1.6.1)、
 *    f047d7d9a2 / 8d5df3d6e2 (Xiaomi, 1.6.3) 都是这一种; 只匹配第一种的话
 *    这些机器永远不会被隔离, 于是每次启动→退页面→再崩 (单机 6 条上报)。
 *
 * 第二个判据要求日志里带前缓冲开关的快照 (`FrontBuffer: pref=true`), 避免把
 * 与低延迟无关的 libgui 崩溃也算进来。
 */
internal object FrontBufferCrashSignature {

    /** 原生崩溃报告里的故障地址行: `Fault Address: 0x0000000000000000` */
    private val FAULT_ADDRESS = Regex("Fault Address:\\s*0x([0-9a-fA-F]+)")

    /**
     * 判定一份原生崩溃日志是否属于「前缓冲 (低延迟真墨) 拆解竞态或系统图形栈事务缺陷」。
     *
     * 已知两种表现, 都发生在系统图形栈里、Kotlin 侧无法捕获:
     *
     * 1. 栈里直接出现 androidx 的 `libgraphics-core.so`
     *    (`SurfaceControl` 拆解竞态, fault addr 0x0)。
     * 2. 栈里没有 `libgraphics-core.so`, 只有 `libgui.so` 与 SurfaceControl / BLASTBufferQueue / 事务层,
     *    且崩溃时前缓冲开关是开着的 (`FrontBuffer: pref=true`):
     *    - 页面切换或退出绘画页时 Surface 已销毁, 仍有事务打到 libgui 空指针 (SIGSEGV 0x0/0x4/0x8);
     *    - HWUI RenderThread 派发表面移动/缩放 (`PositionListenerTrampoline`) 时, 与前缓冲事务发生
     *      BLAST 队列或 Transaction 状态并发冲突, 触发 RefBase 引用计数断言失败 (SIGABRT)。
     *    线上 d8f74afe88 (vivo V2230A, 1.6.2)、7f68bfcd44 (Mi Pad 5, 1.6.1)、
     *    f047d7d9a2 / 8d5df3d6e2 (Xiaomi, 1.6.3)、#232 (XPPen Magic Drawing Pad, 1.10.2) 均属此类。
     */
    private val SURFACE_STACK_KEYWORDS = arrayOf(
        "libandroid_runtime.so",
        "SurfaceComposerClient",
        "BLASTBufferQueue",
        "SurfaceControl",
        "onSetSurfacePositionAndScaleRT",
        "PositionListenerTrampoline",
        "layer_state_t",
    )

    /** 与低延迟前缓冲无关时返回 false。 */
    fun matches(logText: String): Boolean {
        if (logText.isEmpty()) return false

        // 变体 1: 栈里直接点名 androidx 图形库
        if (logText.contains("libgraphics-core.so")) return true

        // 变体 2: 前缓冲开着 + Surface 相关系统库 + 异常信号 (SIGSEGV / SIGABRT)
        if (!logText.contains("FrontBuffer: pref=true")) return false
        if (!logText.contains("libgui.so")) return false
        val isCrashSignal = logText.contains("SIGSEGV") || logText.contains("SIGABRT")
        if (!isCrashSignal) return false

        return SURFACE_STACK_KEYWORDS.any { logText.contains(it) }
    }
}
