package com.reverie.paint.core

/**
 * s12 对称镜像实时出墨的状态门 (纯逻辑, JVM 单测见 MirrorLiveGateTest)。
 *
 * 引擎侧每个镜像分支有自己的 painter + paintop (仿 Krita KisToolMultihand), 镜像与主笔
 * 同一笔触、同一撤销事务实时出真墨。只有引擎拒绝 (快速路径 / 双笔刷蒙版 / 引擎绕过选区)
 * 时才退回旧的"抬笔重放 + 矢量预览"。
 *
 * s12-F1: 状态按**笔画号**(seq, touchStart 递增) 记录。快速连笔时上一笔的收尾 drain 可能在
 * 下一笔 [begin] 之后才在渲染线程执行; 引擎对上一笔的应答只改上一笔的状态, 不会把下一笔
 * 推成 LIVE (否则下一笔被引擎拒绝时既不实时也不重放, 镜像墨丢失)。
 *
 * 状态:
 *  - [State.OFF]      非对称笔画 / 已取消, 镜像点不入队。
 *  - [State.PENDING]  落笔后镜像点入队, 引擎还没确认; 矢量预览照常画。
 *  - [State.LIVE]     引擎已接受本笔镜像批 (分支 paintop 已建), 预览关闭, 抬笔不重放。
 *  - [State.FALLBACK] 引擎拒绝 (或抬笔时仍未确认), 停止入队, 抬笔重放整支镜像。
 *
 * 不变式: 一笔进入 LIVE 后不会再变成 FALLBACK; PENDING 阶段的送墨在 [withLock] 内执行,
 * 与 [resolveAtPenUp] 互斥。两条合起来保证镜像不会既实时画过又被重放一遍。
 */
class MirrorLiveGate {
    enum class State { OFF, PENDING, LIVE, FALLBACK }

    /** 引擎对一批镜像点的应答 (与 JNI touchStrokeMirrorBatch 的返回值一一对应)。 */
    enum class EngineResult { ACCEPTED, REJECTED, STALE }

    private val lock = Any()

    /** 已被后续笔画取代的近几笔的最终状态 (只为快速连笔时还没收尾的上一笔保留)。 */
    private val history = LinkedHashMap<Int, State>()

    @Volatile
    var currentSeq: Int = 0
        private set

    /** 当前笔 ([currentSeq]) 的状态。 */
    @Volatile
    var state: State = State.OFF
        private set

    val isLive: Boolean get() = state == State.LIVE
    val isQueueing: Boolean get() = state == State.PENDING || state == State.LIVE

    /**
     * s13-C5: 当前笔的引擎笔触已被撤销 / 重做 / 取消 / 离开页面清掉 ([abort])。抬笔时调用方
     * 据此禁止"镜像整笔重放"(否则会多出一条只有镜像的新笔画和一个撤销步)。[begin] 复位。
     */
    @Volatile
    var isAborted: Boolean = false
        private set

    /** UI 线程落笔 (含落笔失败: active=false)。 */
    fun begin(seq: Int, symmetryActive: Boolean) {
        synchronized(lock) {
            if (currentSeq != 0 && currentSeq != seq) {
                history[currentSeq] = state
                while (history.size > HISTORY_LIMIT) {
                    history.remove(history.keys.first())
                }
            }
            currentSeq = seq
            state = if (symmetryActive) State.PENDING else State.OFF
            isAborted = false
        }
    }

    /** 取消当前笔画: 停止入队。 */
    fun cancel() {
        synchronized(lock) { state = State.OFF }
    }

    /** s13-C5: 当前笔被中止 (引擎笔触已清): 停止入队, 并标记抬笔不得重放镜像。 */
    fun abort() {
        synchronized(lock) {
            state = State.OFF
            isAborted = true
        }
    }

    /** 任一笔画号的状态 (当前笔或最近被取代的笔; 更早的视为 OFF)。 */
    fun stateOf(seq: Int): State = synchronized(lock) {
        if (seq == currentSeq) state else history[seq] ?: State.OFF
    }

    /** 渲染线程: PENDING 阶段的送墨在锁内执行, 与 [resolveAtPenUp] 互斥。 */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /**
     * 渲染线程: 引擎对笔画 [seq] 一批镜像点的应答。返回该笔是否继续送后面的分支。
     * PENDING + ACCEPTED -> LIVE; PENDING + REJECTED -> FALLBACK;
     * LIVE 之后即使 REJECTED 也保持 LIVE (引擎侧保证此时不会拒绝; 这里再兜一层, 绝不回退);
     * STALE (引擎已不在画这一笔) 不改状态, 停止送这一笔。
     */
    fun onEngineResult(seq: Int, result: EngineResult): Boolean = synchronized(lock) {
        val cur = stateOf(seq)
        val next = when (cur) {
            State.PENDING -> when (result) {
                EngineResult.ACCEPTED -> State.LIVE
                EngineResult.REJECTED -> State.FALLBACK
                EngineResult.STALE -> State.PENDING
            }
            else -> cur
        }
        if (next != cur) {
            if (seq == currentSeq) state = next else history[seq] = next
        }
        result != EngineResult.STALE && next == State.LIVE
    }

    /**
     * UI 线程抬笔 (当前笔): 返回 true = 镜像已实时出墨, 不重放 (保持 LIVE, 收尾补点继续入队);
     * false = 走抬笔重放 (PENDING 在此转 FALLBACK, 调用方丢弃本笔还在队列里的镜像点)。
     */
    fun resolveAtPenUp(): Boolean = synchronized(lock) {
        when (state) {
            State.LIVE -> true
            State.PENDING -> {
                state = State.FALLBACK
                false
            }
            State.OFF, State.FALLBACK -> false
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 8
    }
}
