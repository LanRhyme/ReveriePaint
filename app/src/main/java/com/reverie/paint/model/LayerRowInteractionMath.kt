/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.roundToInt

/**
 * 图层行内交互的纯换算 (无 Android 依赖, 可 JVM 单测)。
 *
 * 两个函数各对应一类"看得见的错觉"问题:
 * - [layerOpacityScrubPercent]: 行内不透明度药丸的拖动换算;
 * - [layersHiddenByHiddenGroup]: 父图层组隐藏时, 子行眼睛亮着却看不到。
 */

/**
 * 行内不透明度药丸: 起手百分比 + 横向位移 -> 新百分比 (0..100, 已 clamp)。
 *
 * [fullRangePx] = 走满 100% 需要的横向像素。位移是**起手点到当前点的总位移**
 * 而不是逐帧累加, 所以手指来回抖动不会漂移; 超出两端 clamp 住,
 * 手指退回时数值立即跟回 (和拖动滑杆的手感一致)。
 *
 * 方向取"横向调节"是沿用 [com.reverie.paint.ui.components.ReSlider] 在可滚动
 * 容器里的既有约定: 纵向位移让给列表滚动, 横向位移才认领手势。
 */
fun layerOpacityScrubPercent(
    startPercent: Int,
    dxPx: Float,
    fullRangePx: Float,
): Int {
    val start = startPercent.coerceIn(0, 100)
    if (!fullRangePx.isFinite() || fullRangePx <= 0f) return start
    val next = start + dxPx / fullRangePx * 100f
    if (!next.isFinite()) return start
    return next.roundToInt().coerceIn(0, 100)
}

/**
 * 标记哪些图层"自身可见, 但祖先组隐藏"——这些行眼睛是亮的, 画布上却看不见,
 * 是用户最常见的困惑来源 (Mangar 的图层面板把这类行的眼睛画成灰色)。
 *
 * [depths] / [visible] 是引擎层序遍历 (父节点一定排在子节点之前, 见
 * `ReverieCoreDocument.cpp` 的 `walk`) 的深度与自身可见性; 返回数组与 [depths]
 * 等长: true = 父链上有隐藏节点, 因此本层实际不参与合成。
 *
 * 祖先判定用"回看最近的 depth-1 行": 层序 DFS 里这就是父节点 (蒙版的 depth
 * 是所属图层 +1, 也成立)。深度跳跃等异常输入按"祖先可见"处理, 宁可不多画灰
 * 也不要误判成隐藏。
 */
fun layersHiddenByHiddenGroup(
    depths: List<Int>,
    visible: List<Boolean>,
): BooleanArray {
    val n = depths.size
    val out = BooleanArray(n)
    if (n == 0) return out
    val maxDepth = depths.maxOf { it.coerceAtLeast(0) }
    // effective[d] = 深度 d 上最后出现节点的"自身 && 祖先链"可见性
    val effective = BooleanArray(maxDepth + 1) { true }
    var seenDepth = -1
    for (i in 0 until n) {
        val depth = depths[i].coerceAtLeast(0)
        val own = i < visible.size && visible[i]
        val parentVisible = if (depth == 0) true else if (depth - 1 > seenDepth) true else effective[depth - 1]
        effective[depth] = own && parentVisible
        out[i] = own && !parentVisible
        if (depth > seenDepth) seenDepth = depth
    }
    return out
}

/**
 * [index] 行是否"实际不可见/不可落笔": 自身隐藏, 或祖先链上有隐藏的组。
 *
 * 与 [layersHiddenByHiddenGroup] 的差别: 后者批量给行画灰, 这里只回答"当前这行
 * 能不能编辑", 允许 O(n) 回溯。
 *
 * **透明度蒙版的直接父级是宿主图层, 不一定是组** (Layer-as-Mask: 蒙版是宿主上方
 * 的同级 KisPaintLayer, 列表里记为 depth+1 的子行)。旧实现只把组当父级, 会跨过
 * 宿主继续往上找到下方无关的组, 于是"下方组隐藏 → 上方蒙版被判隐藏" (issue #215)。
 */
inline fun isLayerEffectivelyHiddenAt(
    index: Int,
    layerCount: Int,
    depthAt: (Int) -> Int,
    visibleAt: (Int) -> Boolean,
    groupAt: (Int) -> Boolean,
    maskAt: (Int) -> Boolean,
): Boolean {
    if (index !in 0 until layerCount) return false
    if (!visibleAt(index)) return true
    // 只允许蒙版的第一层父级是普通图层; 宿主之上的祖先仍然必须是组
    var parentMayBeLayer = maskAt(index)
    var depth = depthAt(index).coerceAtLeast(0)
    var i = index - 1
    while (i >= 0 && depth > 0) {
        if (depthAt(i).coerceAtLeast(0) == depth - 1 && (groupAt(i) || parentMayBeLayer)) {
            if (!visibleAt(i)) return true
            depth -= 1
            parentMayBeLayer = false
        }
        i--
    }
    return false
}
