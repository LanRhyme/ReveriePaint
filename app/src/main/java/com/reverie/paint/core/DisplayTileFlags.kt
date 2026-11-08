/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

/**
 * 512 显示瓦片网格的预算与 A/B 开关。进程内只读一次, 改完需重启 App:
 *
 * ```
 * adb shell setprop debug.reverie.display_tiles 0        # 0 = 全部画布回退整图双缓冲 (对照旧行为)
 * adb shell setprop debug.reverie.tile_budget_mb 320     # 瓦片网格总显存预算 (MB, 16~1024)
 * ```
 *
 * 为什么要有预算这层: 瓦片网格是"把整幅文档的显示像素拆成 512² 的块", 总字节数在 1:1 路径上
 * 与文档面积成正比 (6400x10240 = 250MB)。它是目前**最省**的一条路径 (整图双缓冲要 2 份全幅
 * 位图 + 2048 分块副本), 但也不是无上限的 —— 超过预算就老实退回整图路径, 而不是让
 * `Bitmap.createBitmap` 在渲染线程上抛 OOM。
 */
internal object DisplayTileFlags {
    /** 总开关。false = 所有画布走整图双缓冲 (性能对照用)。 */
    val enabled: Boolean by lazy {
        PerfTrace.debugPropInt("debug.reverie.display_tiles", 1) != 0
    }

    /**
     * 视口 LoD 总开关 (`debug.reverie.lod 1`)。打开后, 缩放视图下显示瓦片按
     * 1/2、1/4、1/8 合成 (见 [com.reverie.paint.model.LodScalePolicy]), 显存与每帧纹理上传
     * 按档位平方下降; 放大回 1:1 时自动重建网格。
     */
    val lodEnabled: Boolean by lazy {
        PerfTrace.debugPropInt("debug.reverie.lod", 1) != 0
    }

    /** LoD 最大档位 (1 = 关闭; 2/4/8)。 */
    val lodMaxScale: Int by lazy {
        PerfTrace.debugPropInt("debug.reverie.lod_max", 8).coerceIn(1, 8)
    }

    /** 瓦片网格总显存预算 (字节)。 */
    val budgetBytes: Long by lazy {
        PerfTrace
            .debugPropInt(
                "debug.reverie.tile_budget_mb",
                (com.reverie.paint.model.DisplayTileGrid.DEFAULT_TILE_BUDGET_BYTES / MIB).toInt(),
            ).coerceIn(16, 1024)
            .toLong() * MIB
    }

    private const val MIB = 1024L * 1024L
}
