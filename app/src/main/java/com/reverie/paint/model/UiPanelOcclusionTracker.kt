/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.hypot

/**
 * 21 个 UI 槽位的遮挡状态机（纯 Kotlin，可单测）。
 *
 * 线程约束：本类只在 UI 线程读写。渲染线程不得访问本类，只能读取
 * ViewModel 通过 `@Volatile` 发布的不可变 [UiClipSnapshot]。
 *
 * 每个槽位支持多个 owner 并存（最多 4 个）。共享槽位（如 ActionToast /
 * TransformIndicatorHud 在退出动画期间并存）会同时登记新旧 token；token
 * 最大者视为当前 owner，[bounds]/[ownerToken] 等读取当前 owner 的 bounds。
 * [clear] 移除指定 token 后自动回退到剩余 owner 中最高 token 者，因此旧
 * owner 无需重新登记即可恢复。
 *
 * 本类同时维护两套矩形：
 * - [slotBounds]：所有 owner 的并集本体边界，供 32dp/40dp 避让距离判定与
 *   [hitTest] 使用（阴影外扩不得虚增触发距离）；
 * - [slotClipBounds]：本体并集再向四周外扩该槽位的真实软阴影裙边，供
 *   [snapshot] 导出给前缓冲 `clipOutUi` 使用。
 *
 * 关键铁律：50% 渐隐态的面板绝不从裁剪快照中移除。前缓冲处于窗口最顶层，
 * 若撤掉孔径，`PorterDuff.Mode.SRC` 的不透明画布底色会重新盖住面板圆角、
 * 软阴影与 50% 本体。渐隐只改变 Compose 侧透明度，与裁剪孔径无关。
 */
class UiPanelOcclusionTracker {

    /** 槽位内所有 owner 的并集本体 bounds（距离判定与 hitTest 使用）。 */
    private val slotBounds = FloatArray(UiPanelSlot.COUNT * 4)

    /** 本体并集 + 阴影外扩后的孔径 bounds（裁剪快照使用）。 */
    private val slotClipBounds = FloatArray(UiPanelSlot.COUNT * 4)

    /** 每个槽位当前生效的阴影外扩像素（由 Compose 侧按 density 换算后传入）。 */
    private val slotPadH = FloatArray(UiPanelSlot.COUNT)
    private val slotPadT = FloatArray(UiPanelSlot.COUNT)
    private val slotPadB = FloatArray(UiPanelSlot.COUNT)

    /** 当前（最高 token）owner 的 bounds，供 [bounds]/[left] 等快速读取。 */
    private val currentOwnerBounds = FloatArray(UiPanelSlot.COUNT * 4)

    /** 当前（最高 token）owner 的 token；0 表示槽位未激活。 */
    private val currentTokens = IntArray(UiPanelSlot.COUNT)

    /** 每个槽位登记的 owner token；有效项为 `[0, ownerCounts[ordinal])`。 */
    private val ownerTokens = Array(UiPanelSlot.COUNT) { IntArray(MAX_OWNERS_PER_SLOT) }

    /** 每个槽位各 owner 的 bounds，与 [ownerTokens] 一一对应，每项 4 个 float。 */
    private val ownerBounds = Array(UiPanelSlot.COUNT) { FloatArray(MAX_OWNERS_PER_SLOT * 4) }

    /** 每个槽位当前登记的 owner 数量。 */
    private val ownerCounts = IntArray(UiPanelSlot.COUNT)

    private var nextTokenValue = 1
    private var versionValue = 0
    private var evadedMaskValue = 0

    /**
     * 槽位状态变化回调，只在 UI 线程触发。
     *
     * 回调内不得同步调用本类的方法，否则可能递归触发；需要重建快照的
     * 消费方应 post/合并到下一帧再读取。
     */
    var onChanged: (() -> Unit)? = null

    /** 槽位 bounds 或掩码变化时递增，供调用方判断是否需要重建快照。 */
    val version: Int get() = versionValue

    val evadedMask: Int get() = evadedMaskValue

    /** 单调递增的 owner token；0 保留给“未注册”。 */
    fun nextToken(): Int {
        val token = nextTokenValue
        nextTokenValue = if (nextTokenValue == Int.MAX_VALUE) 1 else nextTokenValue + 1
        return token
    }

    /**
     * 写入某个 owner 的槽位 bounds。
     *
     * [padH]/[padT]/[padB] 是该槽位的阴影外扩像素（左右 / 顶 / 底），用于生成
     * [snapshot] 的裁剪孔径；距离判定始终只使用本体 bounds。
     *
     * token == 0 时忽略。token 未登记时追加到该槽位的 owner 栈（栈满时替换
     * 其中最小的 token，即最旧 owner）。写入后重新选出最高 token 作为当前
     * owner，并更新当前 owner 的快速读取区与槽位并集。当前 owner 从旧 token
     * 切换到新 token 时清掉该槽位的渐隐位，避免新接管的可见面板继承旧
     * owner 的状态。
     *
     * bounds 非法（非有限值或零/负尺寸）时：token 已在栈中则移除该 owner
     * （面板折叠后不得留下过期 bounds），否则忽略。
     * @return 是否新增了 owner、该 owner 的 bounds 发生变化，或因非法 bounds
     * 移除了已有 owner。
     */
    fun update(
        slot: UiPanelSlot,
        ownerToken: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        padH: Float = 0f,
        padT: Float = 0f,
        padB: Float = 0f,
    ): Boolean {
        if (ownerToken == 0) return false

        val ordinal = slot.ordinal
        val tokens = ownerTokens[ordinal]
        val bounds = ownerBounds[ordinal]
        val count = ownerCounts[ordinal]

        var index = -1
        for (i in 0 until count) {
            if (tokens[i] == ownerToken) {
                index = i
                break
            }
        }

        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            right <= left || bottom <= top
        ) {
            if (index < 0) return false
            removeOwner(ordinal, index, ownerToken)
            return true
        }

        slotPadH[ordinal] = if (padH.isFinite()) padH.coerceAtLeast(0f) else 0f
        slotPadT[ordinal] = if (padT.isFinite()) padT.coerceAtLeast(0f) else 0f
        slotPadB[ordinal] = if (padB.isFinite()) padB.coerceAtLeast(0f) else 0f

        val added = index < 0
        if (added) {
            if (count < MAX_OWNERS_PER_SLOT) {
                index = count
                ownerCounts[ordinal] = count + 1
            } else {
                // 栈满：替换最小（最旧）的 token，保证内存有界。
                index = 0
                for (i in 1 until count) {
                    if (tokens[i] < tokens[index]) index = i
                }
            }
            tokens[index] = ownerToken
        }

        val bi = index * 4
        val boundsChanged = bounds[bi] != left ||
            bounds[bi + 1] != top ||
            bounds[bi + 2] != right ||
            bounds[bi + 3] != bottom
        bounds[bi] = left
        bounds[bi + 1] = top
        bounds[bi + 2] = right
        bounds[bi + 3] = bottom

        updateCurrentOwner(ordinal)
        return added || boundsChanged
    }

    /**
     * 注销指定 owner。
     *
     * 被移除的是当前 owner 时清掉该槽位的渐隐位；不是当前 owner 时掩码保持不变。
     * 移除后自动回退到剩余 owner 中最高 token 者；没有剩余 owner 时槽位失活
     * 并清零 bounds。
     * @return token 是否存在于该槽位的 owner 栈。
     */
    fun clear(slot: UiPanelSlot, ownerToken: Int): Boolean {
        if (ownerToken == 0) return false
        val ordinal = slot.ordinal
        val tokens = ownerTokens[ordinal]
        val count = ownerCounts[ordinal]

        var index = -1
        for (i in 0 until count) {
            if (tokens[i] == ownerToken) {
                index = i
                break
            }
        }
        if (index < 0) return false

        removeOwner(ordinal, index, ownerToken)
        return true
    }

    /**
     * 从 owner 栈移除指定下标并压缩存储，然后重算当前 owner 与并集。
     * 被移除的是当前 owner 时清掉该槽位的渐隐位；否则掩码保持不变。
     */
    private fun removeOwner(ordinal: Int, index: Int, ownerToken: Int) {
        val tokens = ownerTokens[ordinal]
        val count = ownerCounts[ordinal]

        if (currentTokens[ordinal] == ownerToken) {
            evadedMaskValue = evadedMaskValue and SLOTS[ordinal].bit.inv()
        }

        val last = count - 1
        val bounds = ownerBounds[ordinal]
        if (index != last) {
            tokens[index] = tokens[last]
            val from = last * 4
            val to = index * 4
            bounds[to] = bounds[from]
            bounds[to + 1] = bounds[from + 1]
            bounds[to + 2] = bounds[from + 2]
            bounds[to + 3] = bounds[from + 3]
        }
        tokens[last] = 0
        val bi = last * 4
        bounds[bi] = 0f
        bounds[bi + 1] = 0f
        bounds[bi + 2] = 0f
        bounds[bi + 3] = 0f
        ownerCounts[ordinal] = last

        updateCurrentOwner(ordinal)
    }

    fun isActive(slot: UiPanelSlot): Boolean = currentTokens[slot.ordinal] != 0

    fun ownerToken(slot: UiPanelSlot): Int = currentTokens[slot.ordinal]

    fun activeCount(): Int {
        var n = 0
        for (ordinal in SLOTS.indices) {
            if (currentTokens[ordinal] != 0) n++
        }
        return n
    }

    /** 复制当前 owner 的本体 bounds 到 [out] 的前 4 项；槽位未激活时返回 false。 */
    fun bounds(slot: UiPanelSlot, out: FloatArray): Boolean {
        require(out.size >= 4) { "out must hold 4 floats" }
        val ordinal = slot.ordinal
        if (currentTokens[ordinal] == 0) return false
        val i = ordinal * 4
        out[0] = currentOwnerBounds[i]
        out[1] = currentOwnerBounds[i + 1]
        out[2] = currentOwnerBounds[i + 2]
        out[3] = currentOwnerBounds[i + 3]
        return true
    }

    fun left(slot: UiPanelSlot): Float = currentOwnerBounds[slot.ordinal * 4]

    fun top(slot: UiPanelSlot): Float = currentOwnerBounds[slot.ordinal * 4 + 1]

    fun right(slot: UiPanelSlot): Float = currentOwnerBounds[slot.ordinal * 4 + 2]

    fun bottom(slot: UiPanelSlot): Float = currentOwnerBounds[slot.ordinal * 4 + 3]

    /** 设置渐隐位；槽位未激活时忽略。 */
    fun setEvaded(slot: UiPanelSlot, evaded: Boolean): Boolean {
        if (!isActive(slot)) return false
        val old = evadedMaskValue
        evadedMaskValue = if (evaded) {
            evadedMaskValue or slot.bit
        } else {
            evadedMaskValue and slot.bit.inv()
        }
        if (old == evadedMaskValue) return false
        bumpVersion()
        return true
    }

    fun isEvaded(slot: UiPanelSlot): Boolean = (evadedMaskValue and slot.bit) != 0

    /** 清除单个槽位的渐隐位。 */
    fun clearEvasion(slot: UiPanelSlot) {
        val oldEvaded = evadedMaskValue
        evadedMaskValue = evadedMaskValue and slot.bit.inv()
        if (oldEvaded != evadedMaskValue) bumpVersion()
    }

    /** 抬笔/取消/切工具后清除全部渐隐状态。 */
    fun clearAllEvasion() {
        if (evadedMaskValue == 0) return
        evadedMaskValue = 0
        bumpVersion()
    }

    /**
     * 双向滞后评估指定槽位是否应处于 50% 避让态（零分配）。
     *
     * - 包络到槽位本体 bounds 的距离 <= [enterRadiusPx]：进入避让（true）；
     * - 距离 > [exitRadiusPx]：离开避让（false）；
     * - 处于 (enterRadiusPx, exitRadiusPx] 滞后带内：保持当前 [isEvaded] 状态。
     *
     * 槽位未激活或不可避让时强制回到非避让态并返回 false。
     */
    fun evaluateSlotEvasion(
        slot: UiPanelSlot,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        enterRadiusPx: Float,
        exitRadiusPx: Float,
    ): Boolean {
        val ordinal = slot.ordinal
        if (!slot.evadable || currentTokens[ordinal] == 0) {
            setEvaded(slot, false)
            return false
        }
        val i = ordinal * 4
        val dist = distanceBetweenRects(
            left, top, right, bottom,
            slotBounds[i], slotBounds[i + 1], slotBounds[i + 2], slotBounds[i + 3],
        )
        val currentlyEvaded = isEvaded(slot)
        val nextEvaded = when {
            dist <= enterRadiusPx -> true
            dist > exitRadiusPx -> false
            else -> currentlyEvaded
        }
        setEvaded(slot, nextEvaded)
        return nextEvaded
    }

    /**
     * 生成不可变裁剪快照：包含**所有**激活槽位的阴影外扩孔径。
     *
     * 渐隐态（[evadedMask]）不参与排除：50% 面板仍必须通过孔径裁剪保护其
     * 圆角、软阴影与 50% 本体不被前缓冲 `SRC` 覆盖。
     *
     * 每个槽位只输出一个矩形，取该槽位所有 owner 本体并集再统一外扩阴影
     * 裙边（见类 KDoc 的保守裁剪说明）。
     */
    fun snapshot(): UiClipSnapshot {
        var n = 0
        for (ordinal in SLOTS.indices) {
            if (currentTokens[ordinal] != 0) n++
        }
        if (n == 0) return UiClipSnapshot.EMPTY

        val out = FloatArray(n * 4)
        var w = 0
        for (ordinal in SLOTS.indices) {
            if (currentTokens[ordinal] == 0) continue
            val i = ordinal * 4
            out[w] = slotClipBounds[i]
            out[w + 1] = slotClipBounds[i + 1]
            out[w + 2] = slotClipBounds[i + 2]
            out[w + 3] = slotClipBounds[i + 3]
            w += 4
        }
        return UiClipSnapshot.fromOwned(out, n)
    }

    /** 点是否命中任一激活槽位的本体 bounds。 */
    fun hitTest(x: Float, y: Float): Boolean {
        for (ordinal in SLOTS.indices) {
            if (currentTokens[ordinal] == 0) continue
            val i = ordinal * 4
            if (x >= slotBounds[i] && x <= slotBounds[i + 2] &&
                y >= slotBounds[i + 1] && y <= slotBounds[i + 3]
            ) {
                return true
            }
        }
        return false
    }

    /** 矩形是否与任一激活槽位的本体 bounds 相交。 */
    fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        for (ordinal in SLOTS.indices) {
            if (currentTokens[ordinal] == 0) continue
            val i = ordinal * 4
            if (right >= slotBounds[i] && left <= slotBounds[i + 2] &&
                bottom >= slotBounds[i + 1] && top <= slotBounds[i + 3]
            ) {
                return true
            }
        }
        return false
    }

    /** 清空所有槽位与掩码（测试/切工程使用）。 */
    fun reset() {
        slotBounds.fill(0f)
        slotClipBounds.fill(0f)
        slotPadH.fill(0f)
        slotPadT.fill(0f)
        slotPadB.fill(0f)
        currentOwnerBounds.fill(0f)
        currentTokens.fill(0)
        for (ordinal in SLOTS.indices) {
            ownerTokens[ordinal].fill(0)
            ownerBounds[ordinal].fill(0f)
        }
        ownerCounts.fill(0)
        evadedMaskValue = 0
        bumpVersion()
    }

    /**
     * 重新计算槽位当前 owner（最高 token）、所有 owner 的本体并集与阴影外扩
     * 孔径，仅在当前 owner、bounds 或孔径实际变化时递增版本。
     */
    private fun updateCurrentOwner(ordinal: Int) {
        val tokens = ownerTokens[ordinal]
        val count = ownerCounts[ordinal]
        val oldToken = currentTokens[ordinal]
        val si = ordinal * 4
        val oldLeft = slotBounds[si]
        val oldTop = slotBounds[si + 1]
        val oldRight = slotBounds[si + 2]
        val oldBottom = slotBounds[si + 3]
        val oldClipLeft = slotClipBounds[si]
        val oldClipTop = slotClipBounds[si + 1]
        val oldClipRight = slotClipBounds[si + 2]
        val oldClipBottom = slotClipBounds[si + 3]
        val oldCurLeft = currentOwnerBounds[si]
        val oldCurTop = currentOwnerBounds[si + 1]
        val oldCurRight = currentOwnerBounds[si + 2]
        val oldCurBottom = currentOwnerBounds[si + 3]

        if (count == 0) {
            currentTokens[ordinal] = 0
            currentOwnerBounds[si] = 0f
            currentOwnerBounds[si + 1] = 0f
            currentOwnerBounds[si + 2] = 0f
            currentOwnerBounds[si + 3] = 0f
            slotBounds[si] = 0f
            slotBounds[si + 1] = 0f
            slotBounds[si + 2] = 0f
            slotBounds[si + 3] = 0f
            slotClipBounds[si] = 0f
            slotClipBounds[si + 1] = 0f
            slotClipBounds[si + 2] = 0f
            slotClipBounds[si + 3] = 0f
        } else {
            var current = 0
            for (i in 1 until count) {
                if (tokens[i] > tokens[current]) current = i
            }
            currentTokens[ordinal] = tokens[current]
            val ci = current * 4
            val bounds = ownerBounds[ordinal]
            currentOwnerBounds[si] = bounds[ci]
            currentOwnerBounds[si + 1] = bounds[ci + 1]
            currentOwnerBounds[si + 2] = bounds[ci + 2]
            currentOwnerBounds[si + 3] = bounds[ci + 3]

            // 并集：退出动画期间新旧 owner 同时可见，裁剪必须覆盖两者。
            var unionLeft = bounds[0]
            var unionTop = bounds[1]
            var unionRight = bounds[2]
            var unionBottom = bounds[3]
            for (i in 1 until count) {
                val bi = i * 4
                if (bounds[bi] < unionLeft) unionLeft = bounds[bi]
                if (bounds[bi + 1] < unionTop) unionTop = bounds[bi + 1]
                if (bounds[bi + 2] > unionRight) unionRight = bounds[bi + 2]
                if (bounds[bi + 3] > unionBottom) unionBottom = bounds[bi + 3]
            }
            slotBounds[si] = unionLeft
            slotBounds[si + 1] = unionTop
            slotBounds[si + 2] = unionRight
            slotBounds[si + 3] = unionBottom

            val padH = slotPadH[ordinal]
            val padT = slotPadT[ordinal]
            val padB = slotPadB[ordinal]
            slotClipBounds[si] = unionLeft - padH
            slotClipBounds[si + 1] = unionTop - padT
            slotClipBounds[si + 2] = unionRight + padH
            slotClipBounds[si + 3] = unionBottom + padB
        }

        val newToken = currentTokens[ordinal]
        // 当前 owner 真正切换（非 0 → 另一个非 0）时清掉槽位掩码，
        // 避免新接管的可见面板继承旧 owner 的渐隐状态。
        if (oldToken != 0 && newToken != 0 && oldToken != newToken) {
            evadedMaskValue = evadedMaskValue and SLOTS[ordinal].bit.inv()
        }

        val changed = oldToken != currentTokens[ordinal] ||
            oldLeft != slotBounds[si] ||
            oldTop != slotBounds[si + 1] ||
            oldRight != slotBounds[si + 2] ||
            oldBottom != slotBounds[si + 3] ||
            oldClipLeft != slotClipBounds[si] ||
            oldClipTop != slotClipBounds[si + 1] ||
            oldClipRight != slotClipBounds[si + 2] ||
            oldClipBottom != slotClipBounds[si + 3] ||
            oldCurLeft != currentOwnerBounds[si] ||
            oldCurTop != currentOwnerBounds[si + 1] ||
            oldCurRight != currentOwnerBounds[si + 2] ||
            oldCurBottom != currentOwnerBounds[si + 3]
        if (changed) bumpVersion()
    }

    private fun bumpVersion() {
        versionValue++
        onChanged?.invoke()
    }

    /**
     * 两个轴对齐矩形之间的最短距离；相交或相切时为 0。
     *
     * 零尺寸矩形（点）同样适用：此时另一矩形上的最近点即点到矩形的距离。
     */
    private fun distanceBetweenRects(
        aLeft: Float,
        aTop: Float,
        aRight: Float,
        aBottom: Float,
        bLeft: Float,
        bTop: Float,
        bRight: Float,
        bBottom: Float,
    ): Float {
        val dx = when {
            aRight < bLeft -> bLeft - aRight
            bRight < aLeft -> aLeft - bRight
            else -> 0f
        }
        val dy = when {
            aBottom < bTop -> bTop - aBottom
            bBottom < aTop -> aTop - bBottom
            else -> 0f
        }
        return when {
            dx == 0f -> dy
            dy == 0f -> dx
            else -> hypot(dx, dy)
        }
    }

    private companion object {
        const val MAX_OWNERS_PER_SLOT = 4
        val SLOTS: Array<UiPanelSlot> = UiPanelSlot.entries.toTypedArray()
    }
}
