package com.reverie.paint.core

/**
 * s12-F1: 对称镜像实时采样队列, 每个采样点带落笔时分配的笔画号 (seq)。
 *
 * UI 线程 [push]; 渲染线程只按"引擎当前打开的那一笔"的笔画号 [take]。快速连笔时上一笔的
 * touchEnd 还没在渲染线程跑完, 下一笔的镜像点就已入队: 按笔画号取, 下一笔的点会原样留在
 * 队列里, 等下一笔的 touchStrokeStart 之后才送进引擎, 不会被画进上一笔的笔触 / 撤销事务。
 *
 * 同一分支里点按入队顺序排列, 笔画号单调不减。线程安全 (自带锁), 纯逻辑, JVM 单测见
 * MirrorSampleQueueTest。
 */
class MirrorSampleQueue(
    val branchCount: Int,
    val capacity: Int,
    val stride: Int = 7,
) {
    private val coords = FloatArray(branchCount * capacity * stride)
    private val seqs = IntArray(branchCount * capacity)
    private val counts = IntArray(branchCount)

    /** 因队列满被丢弃的点数 (诊断用)。 */
    @Volatile
    var droppedCount: Long = 0L
        private set

    /** 追加一个点。分支越界或队列满时返回 false (满时丢弃这个最新点, 不覆盖已入队的点)。 */
    @Synchronized
    fun push(branch: Int, seq: Int, sample: FloatArray, offset: Int = 0): Boolean {
        if (branch < 0 || branch >= branchCount) return false
        val n = counts[branch]
        if (n >= capacity) {
            droppedCount++
            return false
        }
        System.arraycopy(sample, offset, coords, (branch * capacity + n) * stride, stride)
        seqs[branch * capacity + n] = seq
        counts[branch] = n + 1
        return true
    }

    /**
     * 取出分支 [branch] 里笔画号等于 [seq] 的点, 同时丢弃笔画号小于 [seq] 的过期点,
     * 笔画号大于 [seq] 的点 (下一笔) 原样保留在队列前部。
     *
     * 返回长度精确为 `点数 * stride` 的数组 (JNI 要求); [reuse] 长度刚好时复用它。没有点时返回 null。
     */
    @Synchronized
    fun take(branch: Int, seq: Int, reuse: FloatArray?): FloatArray? {
        if (branch < 0 || branch >= branchCount) return null
        val n = counts[branch]
        if (n == 0) return null
        val base = branch * capacity
        var match = 0
        for (i in 0 until n) if (seqs[base + i] == seq) match++
        var out: FloatArray? = null
        if (match > 0) {
            out = if (reuse != null && reuse.size == match * stride) reuse else FloatArray(match * stride)
        }
        var w = 0 // 保留点的写入位置 (就地压实)
        var o = 0
        for (i in 0 until n) {
            val s = seqs[base + i]
            val src = (base + i) * stride
            when {
                s == seq -> {
                    System.arraycopy(coords, src, out!!, o * stride, stride)
                    o++
                }
                s > seq -> {
                    if (w != i) {
                        System.arraycopy(coords, src, coords, (base + w) * stride, stride)
                        seqs[base + w] = s
                    }
                    w++
                }
                else -> Unit // s < seq: 过期, 丢弃
            }
        }
        counts[branch] = w
        return out
    }

    /** 丢弃所有分支里笔画号 <= [seq] 的点 (该笔改走抬笔重放 / 引擎已不认它)。 */
    @Synchronized
    fun discardUpTo(seq: Int) {
        for (b in 0 until branchCount) {
            val n = counts[b]
            val base = b * capacity
            var w = 0
            for (i in 0 until n) {
                val s = seqs[base + i]
                if (s > seq) {
                    if (w != i) {
                        System.arraycopy(coords, (base + i) * stride, coords, (base + w) * stride, stride)
                        seqs[base + w] = s
                    }
                    w++
                }
            }
            counts[b] = w
        }
    }

    @Synchronized
    fun clear() {
        for (b in 0 until branchCount) counts[b] = 0
    }

    @Synchronized
    fun size(branch: Int): Int = if (branch in 0 until branchCount) counts[branch] else 0
}
