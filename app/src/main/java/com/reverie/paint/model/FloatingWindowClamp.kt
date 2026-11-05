/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

/**
 * 悬浮窗原点夹取的结果容器。
 *
 * 拖拽期每帧要对 6 个窗各夹一次; 复用一个容器, 避免每帧产生 Pair 与两次装箱
 * (Float 只有 -128..127 命中装箱缓存)。只在主线程使用, 用完立即取值, 不跨帧保存。
 */
class WindowOrigin {
    var x: Float = 0f
    var y: Float = 0f
}

/**
 * 悬浮窗拖把手几何常量 (dp)。
 *
 * 同一组数字被两侧消费, 改这里即可两边同时生效:
 * - ui 侧: 各窗拖把手的 Compose 尺寸 (DragPillHandle / 顶栏 / 侧握把)
 * - core 侧: [com.reverie.paint.core.PaintViewModel.clampQuickWindowsToSafeArea]
 *   在把手尚未测量 (首帧, 宿主测量早于子节点 place) 时的兜底夹取
 *
 * 放在 model 是因为 core 不能引用 ui, 而两侧都要用。
 */
object DragGripSpec {
    /** 顶部小横条把手: 颜色窗/OKLCH 窗展开态, 也是通用 DragPillHandle 的尺寸 */
    const val PILL_WIDTH_DP = 60f
    const val PILL_HEIGHT_DP = 16f

    /** 参考窗顶栏 (展开态) */
    const val REFERENCE_TOP_BAR_HEIGHT_DP = 38f

    /** 参考窗收起态拖动条 */
    const val REFERENCE_STRIP_HEIGHT_DP = 28f

    /** 快捷操作窗展开态顶栏 */
    const val QUICK_ACTION_TOP_BAR_HEIGHT_DP = 24f

    /** 快捷图层面板展开态顶栏 */
    const val QUICK_LAYER_TOP_BAR_HEIGHT_DP = 38f

    /** 笔刷竖向胶囊: 顶部握把 */
    const val QUICK_BRUSH_TOP_WIDTH_DP = 42f
    const val QUICK_BRUSH_TOP_HEIGHT_DP = 20f

    /** 笔刷横向胶囊: 左端握把, 其左缘偏移等于胶囊行内留白 */
    const val QUICK_BRUSH_SIDE_WIDTH_DP = 14f
    const val QUICK_BRUSH_SIDE_HEIGHT_DP = 28f
    const val QUICK_BRUSH_SIDE_INSET_DP = 6f
}

/**
 * 悬浮小窗原点夹取的纯函数 (无 Android 依赖, 可单测)。
 *
 * 夹取对象是窗内拖把手, 不是整窗。窗体可以出屏, 把手必须还能抓住。
 *
 * 规则:
 * - parent 是绘画页布局尺寸, 不是 Configuration 上报的屏幕宽高。
 * - 把手矩形相对窗口原点: (gripLeft, gripTop, gripW, gripH), 单位与 parent 相同 (像素)。
 * - X 允许出屏, 但把手左右各留 keepInsidePx。把手比 keepInside 窄时整段留在父级内。
 * - 把手顶缘不能小于 0。下沿只留 keepY, 窗口下半部分可以超出底边。
 * - 布局尺寸非法 (<=0) 时原样返回, 不做夹取。
 *
 * 整窗就是把手时传入 gripLeft=0, gripTop=0, gripW=width, gripH=height。
 *
 * 「未定位哨兵」(x 与 y 同时 < 0, 常见 -1,-1) 的跳过逻辑由调用方 (PaintViewModel) 负责,
 * 本函数不认识哨兵。
 *
 * 结果写入调用方持有的 [out] 并原样返回 (便于链式取值), 热路径请复用同一个容器。
 */
fun clampPlacedWindowOrigin(
    x: Float,
    y: Float,
    parentW: Float,
    parentH: Float,
    gripLeft: Float,
    gripTop: Float,
    gripW: Float,
    gripH: Float,
    keepInsidePx: Float,
    out: WindowOrigin,
): WindowOrigin {
    if (parentW <= 0f || parentH <= 0f) {
        out.x = x
        out.y = y
        return out
    }
    val safeGripW = gripW.coerceAtLeast(0f)
    val safeGripH = gripH.coerceAtLeast(0f)
    val keepX = keepInsidePx.coerceIn(1f, safeGripW.coerceAtLeast(1f))
    val keepY = keepInsidePx.coerceIn(1f, safeGripH.coerceAtLeast(1f))
    val minGripX = keepX - safeGripW
    val maxGripX = (parentW - keepX).coerceAtLeast(minGripX)
    val minGripY = 0f
    val maxGripY = (parentH - keepY).coerceAtLeast(minGripY)
    val gripX = (x + gripLeft).coerceIn(minGripX, maxGripX)
    val gripY = (y + gripTop).coerceIn(minGripY, maxGripY)
    out.x = gripX - gripLeft
    out.y = gripY - gripTop
    return out
}
