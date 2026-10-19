/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.hypot

/** 笔尖与面板的双圈状态。 */
enum class UiRingState {
    NONE,
    WARN,
    CONTACT,
}

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
 * 裁剪相关查询（[snapshot]/[hitTest]/[intersects]/[ringState]）使用槽位内
 * 所有 owner 的并集矩形：退出动画期间新旧面板同时可见，只裁当前 owner 会
 * 让旧面板漏到前缓冲上。并集是保守裁剪，可能把两个 owner 之间的空隙也一并
 * 裁掉；这是有意的取舍（宁可多裁，不可漏裁），每个槽位仍只产生一个矩形。
 */
class UiPanelOcclusionTracker {

    /** 槽位内所有 owner 的并集 bounds（裁剪查询使用，可能大于当前 owner）。 */
    private val slotBounds = FloatArray(UiPanelSlot.COUNT * 4)

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
    private var unclippedMaskValue = 0

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

    val unclippedMask: Int get() = unclippedMaskValue

    /** 单调递增的 owner token；0 保留给“未注册”。 */
    fun nextToken(): Int {
        val token = nextTokenValue
        nextTokenValue = if (nextTokenValue == Int.MAX_VALUE) 1 else nextTokenValue + 1
        return token
    }

    /**
     * 写入某个 owner 的槽位 bounds。
     *
     * token == 0 时忽略。token 未登记时追加到该槽位的 owner 栈（栈满时替换
     * 其中最小的 token，即最旧 owner）。写入后重新选出最高 token 作为当前
     * owner，并更新当前 owner 的快速读取区与槽位并集。当前 owner 从旧 token
     * 切换到新 token 时清掉该槽位的渐隐/撤裁剪位，避免新接管的可见面板继承
     * 旧 owner 的状态。
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
     * 被移除的是当前 owner 时清掉该槽位的渐隐/撤裁剪位；不是当前 owner 时
     * 掩码保持不变。移除后自动回退到剩余 owner 中最高 token 者；没有剩余
     * owner 时槽位失活并清零 bounds。
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
     * 被移除的是当前 owner 时清掉该槽位的渐隐/撤裁剪位；否则掩码保持不变。
     */
    private fun removeOwner(ordinal: Int, index: Int, ownerToken: Int) {
        val tokens = ownerTokens[ordinal]
        val count = ownerCounts[ordinal]

        if (currentTokens[ordinal] == ownerToken) {
            evadedMaskValue = evadedMaskValue and SLOTS[ordinal].bit.inv()
            unclippedMaskValue = unclippedMaskValue and SLOTS[ordinal].bit.inv()
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
            val slot = SLOTS[ordinal]
            if (currentTokens[slot.ordinal] != 0) n++
        }
        return n
    }

    /** 复制当前 owner 的 bounds 到 [out] 的前 4 项；槽位未激活时返回 false。 */
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

    /** 设置外圈渐隐位；槽位未激活时忽略。 */
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

    /** 设置内圈撤裁剪位；槽位未激活时忽略。 */
    fun setUnclipped(slot: UiPanelSlot, unclipped: Boolean): Boolean {
        if (!isActive(slot)) return false
        val old = unclippedMaskValue
        unclippedMaskValue = if (unclipped) {
            unclippedMaskValue or slot.bit
        } else {
            unclippedMaskValue and slot.bit.inv()
        }
        if (old == unclippedMaskValue) return false
        bumpVersion()
        return true
    }

    fun isEvaded(slot: UiPanelSlot): Boolean = (evadedMaskValue and slot.bit) != 0

    fun isUnclipped(slot: UiPanelSlot): Boolean = (unclippedMaskValue and slot.bit) != 0

    /** 清除单个槽位的渐隐/撤裁剪位。 */
    fun clearEvasion(slot: UiPanelSlot) {
        val oldEvaded = evadedMaskValue
        val oldUnclipped = unclippedMaskValue
        evadedMaskValue = evadedMaskValue and slot.bit.inv()
        unclippedMaskValue = unclippedMaskValue and slot.bit.inv()
        if (oldEvaded != evadedMaskValue || oldUnclipped != unclippedMaskValue) {
            bumpVersion()
        }
    }

    /** 抬笔/取消/切工具后清除全部渐隐状态。 */
    fun clearAllEvasion() {
        if (evadedMaskValue == 0 && unclippedMaskValue == 0) return
        evadedMaskValue = 0
        unclippedMaskValue = 0
        bumpVersion()
    }

    /**
     * 生成不可变裁剪快照：包含所有激活且未被内圈撤裁剪的槽位。
     *
     * 每个槽位只输出一个矩形，取该槽位所有 owner bounds 的并集（见类
     * KDoc 的保守裁剪说明）。[evadedMask] 仅保留给 Phase 2 的诊断/未来
     * 策略；外圈渐隐不撤 Clip，因此当前实现不会因 evaded 位排除槽位。
     * 真正决定是否排除的是 [unclippedMask]。
     */
    fun snapshot(
        evadedMask: Int = evadedMaskValue,
        unclippedMask: Int = unclippedMaskValue,
    ): UiClipSnapshot {
        // evadedMask 当前不参与排除，见上方 KDoc。
        var n = 0
        for (ordinal in SLOTS.indices) {
            val slot = SLOTS[ordinal]
            if (currentTokens[slot.ordinal] == 0) continue
            if ((unclippedMask and slot.bit) != 0) continue
            n++
        }
        if (n == 0) return UiClipSnapshot.EMPTY

        val out = FloatArray(n * 4)
        var w = 0
        for (ordinal in SLOTS.indices) {
            val slot = SLOTS[ordinal]
            if (currentTokens[slot.ordinal] == 0) continue
            if ((unclippedMask and slot.bit) != 0) continue
            val i = ordinal * 4
            out[w] = slotBounds[i]
            out[w + 1] = slotBounds[i + 1]
            out[w + 2] = slotBounds[i + 2]
            out[w + 3] = slotBounds[i + 3]
            w += 4
        }
        return UiClipSnapshot.fromOwned(out, n)
    }

    /** 点是否命中任一“可见且仍参与裁剪”的槽位。 */
    fun hitTest(x: Float, y: Float): Boolean {
        for (ordinal in SLOTS.indices) {
            val slot = SLOTS[ordinal]
            if (currentTokens[ordinal] == 0) continue
            if ((unclippedMaskValue and slot.bit) != 0) continue
            val i = ordinal * 4
            if (x >= slotBounds[i] && x <= slotBounds[i + 2] &&
                y >= slotBounds[i + 1] && y <= slotBounds[i + 3]
            ) {
                return true
            }
        }
        return false
    }

    /** 矩形是否与任一“可见且仍参与裁剪”的槽位相交。 */
    fun intersects(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        for (ordinal in SLOTS.indices) {
            val slot = SLOTS[ordinal]
            if (currentTokens[ordinal] == 0) continue
            if ((unclippedMaskValue and slot.bit) != 0) continue
            val i = ordinal * 4
            if (right >= slotBounds[i] && left <= slotBounds[i + 2] &&
                bottom >= slotBounds[i + 1] && top <= slotBounds[i + 3]
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 双圈检测：点到槽位矩形的最短距离。
     *
     * [warnPx] / [contactPx] 是基础外扩距离，函数内部再叠加 [brushRadiusPx]，
     * 与计划中的 `warnR = 64dp + brushR`、`contactR = brushR + 4dp` 一致。
     * 已撤裁剪的槽位不参与检测。
     */
    fun ringState(
        x: Float,
        y: Float,
        brushRadiusPx: Float,
        warnPx: Float,
        contactPx: Float,
    ): UiRingState {
        val contactRadius = brushRadiusPx + contactPx
        val warnRadius = maxOf(brushRadiusPx + warnPx, contactRadius)
        var warnHit = false

        for (ordinal in SLOTS.indices) {
            val slot = SLOTS[ordinal]
            if (currentTokens[ordinal] == 0) continue
            if ((unclippedMaskValue and slot.bit) != 0) continue

            val i = ordinal * 4
            val distance = distanceToRect(
                x, y,
                slotBounds[i], slotBounds[i + 1], slotBounds[i + 2], slotBounds[i + 3],
            )
            if (distance <= contactRadius) return UiRingState.CONTACT
            if (distance <= warnRadius) warnHit = true
        }
        return if (warnHit) UiRingState.WARN else UiRingState.NONE
    }

    /** 清空所有槽位与掩码（测试/切工程使用）。 */
    fun reset() {
        slotBounds.fill(0f)
        currentOwnerBounds.fill(0f)
        currentTokens.fill(0)
        for (ordinal in SLOTS.indices) {
            ownerTokens[ordinal].fill(0)
            ownerBounds[ordinal].fill(0f)
        }
        ownerCounts.fill(0)
        evadedMaskValue = 0
        unclippedMaskValue = 0
        bumpVersion()
    }

    /**
     * 重新计算槽位当前 owner（最高 token）与所有 owner 的并集 bounds，
     * 仅在当前 owner、当前 owner bounds 或并集实际变化时递增版本。
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
        }

        val newToken = currentTokens[ordinal]
        // 当前 owner 真正切换（非 0 → 另一个非 0）时清掉槽位掩码，
        // 避免新接管的可见面板继承旧 owner 的 unclipped 状态。
        if (oldToken != 0 && newToken != 0 && oldToken != newToken) {
            val bit = SLOTS[ordinal].bit
            evadedMaskValue = evadedMaskValue and bit.inv()
            unclippedMaskValue = unclippedMaskValue and bit.inv()
        }

        val changed = oldToken != currentTokens[ordinal] ||
            oldLeft != slotBounds[si] ||
            oldTop != slotBounds[si + 1] ||
            oldRight != slotBounds[si + 2] ||
            oldBottom != slotBounds[si + 3] ||
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

    private fun distanceToRect(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
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
