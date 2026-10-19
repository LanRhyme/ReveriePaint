/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 不可变 UI 裁剪快照。
 *
 * 扁平存储，每项 4 个 float：left, top, right, bottom（root 屏幕像素）。
 * 构建后内部数组不再暴露、不再修改；UI 线程构建并通过 `@Volatile` 发布，
 * 渲染线程只读。禁止 UI 线程与渲染线程共享可变 RectF。
 */
class UiClipSnapshot private constructor(
    private val flat: FloatArray,
    val count: Int,
) {
    init {
        require(count >= 0) { "count must be >= 0" }
        require(flat.size >= count * 4) { "flat too small: ${flat.size} < ${count * 4}" }
    }

    val isEmpty: Boolean get() = count == 0

    /** 与 [l, t, r, b] 是否有交叠；边界相等视为相交。 */
    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean {
        var i = 0
        while (i < count * 4) {
            if (r >= flat[i] && l <= flat[i + 2] && b >= flat[i + 1] && t <= flat[i + 3]) {
                return true
            }
            i += 4
        }
        return false
    }

    /**
     * 若 [l, t, r, b] 与任一裁剪孔径相交，将相交部分并入 [outBounds]
     * （`[minL, minT, maxR, maxB]`）并返回 true；完全不相交时返回 false。零分配。
     *
     * 调用方负责把 [outBounds] 初始化为空集（左/上 = Int.MAX_VALUE，
     * 右/下 = Int.MIN_VALUE）。返回 true 时 [outBounds] 一定是合法非空矩形。
     */
    fun accumulateIntersection(l: Int, t: Int, r: Int, b: Int, outBounds: IntArray): Boolean {
        require(outBounds.size >= 4) { "outBounds must hold 4 ints" }
        val fl = l.toFloat()
        val ft = t.toFloat()
        val fr = r.toFloat()
        val fb = b.toFloat()
        var hit = false
        var i = 0
        while (i < count * 4) {
            val cl = flat[i]
            val ct = flat[i + 1]
            val cr = flat[i + 2]
            val cb = flat[i + 3]
            if (fr > cl && fl < cr && fb > ct && ft < cb) {
                val il = maxOf(l, kotlin.math.floor(cl).toInt())
                val it = maxOf(t, kotlin.math.floor(ct).toInt())
                val ir = minOf(r, kotlin.math.ceil(cr).toInt())
                val ib = minOf(b, kotlin.math.ceil(cb).toInt())
                if (ir > il && ib > it) {
                    if (il < outBounds[0]) outBounds[0] = il
                    if (it < outBounds[1]) outBounds[1] = it
                    if (ir > outBounds[2]) outBounds[2] = ir
                    if (ib > outBounds[3]) outBounds[3] = ib
                    hit = true
                }
            }
            i += 4
        }
        return hit
    }

    /** 点是否落在任一矩形内；边界相等视为命中。 */
    fun contains(x: Float, y: Float): Boolean {
        var i = 0
        while (i < count * 4) {
            if (x >= flat[i] && x <= flat[i + 2] && y >= flat[i + 1] && y <= flat[i + 3]) {
                return true
            }
            i += 4
        }
        return false
    }

    fun left(index: Int): Float = flat[checkedIndex(index)]

    fun top(index: Int): Float = flat[checkedIndex(index) + 1]

    fun right(index: Int): Float = flat[checkedIndex(index) + 2]

    fun bottom(index: Int): Float = flat[checkedIndex(index) + 3]

    /**
     * 把前 [maxItems] 项复制到 [dest] 的 [0, n*4)。
     * @return 实际复制的项数。
     */
    fun copyInto(dest: FloatArray, maxItems: Int = count): Int {
        val n = minOf(count, maxItems.coerceAtLeast(0), dest.size / 4)
        if (n <= 0) return 0
        System.arraycopy(flat, 0, dest, 0, n * 4)
        return n
    }

    private fun checkedIndex(index: Int): Int {
        require(index in 0 until count) { "index $index out of 0 until $count" }
        return index * 4
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiClipSnapshot || other.count != count) return false
        var i = 0
        while (i < count * 4) {
            if (flat[i] != other.flat[i]) return false
            i++
        }
        return true
    }

    override fun hashCode(): Int {
        var result = count
        var i = 0
        while (i < count * 4) {
            result = 31 * result + flat[i].hashCode()
            i++
        }
        return result
    }

    override fun toString(): String {
        val sb = StringBuilder("UiClipSnapshot(count=").append(count)
        var i = 0
        while (i < count) {
            sb.append(", [")
                .append(left(i)).append(',')
                .append(top(i)).append(',')
                .append(right(i)).append(',')
                .append(bottom(i)).append(']')
            i++
        }
        return sb.append(')').toString()
    }

    companion object {
        val EMPTY: UiClipSnapshot = UiClipSnapshot(FloatArray(0), 0)

        /**
         * 从工作数组复制有效项；仅在低频边沿调用。
         * [count] 会被 clamp 到 `[0, min(max, src.size / 4)]`。
         */
        fun copyOf(src: FloatArray, count: Int, max: Int): UiClipSnapshot {
            val capacity = minOf(max, src.size / 4).coerceAtLeast(0)
            val n = count.coerceIn(0, capacity)
            if (n == 0) return EMPTY
            val out = FloatArray(n * 4)
            System.arraycopy(src, 0, out, 0, n * 4)
            return UiClipSnapshot(out, n)
        }

        /**
         * 接管一个刚分配、不会再被外部修改的数组，避免二次复制。
         * 仅供同包/同模块的构建器使用。
         */
        internal fun fromOwned(flat: FloatArray, count: Int): UiClipSnapshot {
            if (count == 0) return EMPTY
            require(flat.size == count * 4) { "owned array size ${flat.size} != ${count * 4}" }
            return UiClipSnapshot(flat, count)
        }
    }
}
