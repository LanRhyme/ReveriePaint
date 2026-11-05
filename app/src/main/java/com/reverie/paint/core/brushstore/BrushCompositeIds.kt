package com.reverie.paint.core.brushstore

/**
 * 笔刷混合模式 id 的唯一规范化入口。
 *
 * 笔刷笔触把 `CompositeOp` 原样交给 Krita 的 `KisPainter::setCompositeOpId`；id 不存在时
 * `KoColorSpace::compositeOp`（KoColorSpace.cpp:370-385）会退回 COMPOSITE_OVER 并打 warning，
 * 也就是「悄悄变成正常」。图层/渲染/图案填充路径对 "difference" 做了特判，笔刷路径没有，
 * 所以 UI 或旧数据里的图层别名必须在这里改成 Krita 真 id：
 *  - difference  → diff       （COMPOSITE_DIFF）
 *  - luminosity  → luminize   （COMPOSITE_LUMINIZE）
 * 其余 id 原样返回。读、写、下发引擎三处都经过这里。
 */
internal object BrushCompositeIds {
    private val ALIASES = mapOf(
        "difference" to "diff",
        "luminosity" to "luminize",
    )

    fun canonical(op: String): String {
        val t = op.trim()
        return ALIASES[t] ?: t
    }
}
