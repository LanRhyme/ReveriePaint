/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 动画前景 / 背景帧的纯逻辑。
 *
 * "背景 / 前景帧"是把某个图层标记成"常驻所有帧之下 / 之上"。判定分支不少 ——
 * 标记是否已失效、该不该放行设置、资源怎么存取 —— 全塞进 ViewModel 就只能靠
 * 真机手点来验证, 所以把可判定的部分抽到这里, 与引擎完全解耦, 由 JVM 单测覆盖。
 * 真正调引擎的部分只负责按计划执行。
 */
object AnimationLayerOps {

    /** 表示"没有图层"的 id。 */
    const val NO_LAYER: Long = -1L

    // ==================== 标记资源的命名空间 ====================

    /**
     * 前景/背景标记在 .revp 里的资源键名前缀。
     *
     * 音频资源是以**原始文件名**为键存进去的(见 animationImportAudio), 没有任何命名空间,
     * 所以标记必须自带前缀才分得开 —— 否则标记的字节会被当成音频送进波形解析。
     */
    const val ASSET_PREFIX = "reverie.anim."

    const val ASSET_BACKGROUND = "reverie.anim.bg"
    const val ASSET_FOREGROUND = "reverie.anim.fg"

    /** 该资源名是不是我们的动画标记。 */
    fun isMarkerAsset(name: String): Boolean = name.startsWith(ASSET_PREFIX)

    /**
     * 从 .revp 的全部资源名里挑出音频。
     *
     * 必须滤掉标记资源, 否则标记会挤进音频列表 —— 现有代码取的是 `assets.first()`,
     * QMap 按名排序, `reverie.anim.bg` 排在多数音频文件名之前, 会把音频顶掉。
     */
    fun audioAssetsOf(names: List<String>): List<String> = names.filterNot(::isMarkerAsset)

    // ==================== 标记值编解码 ====================

    /**
     * 标记存盘时写入的是**图层索引**而不是 layerId。
     *
     * 因为 layerId 实际是 KisNode 的指针地址 (ReverieCore.h:83-88), 同一次运行内稳定
     * (扛得住增删重排), 但重新加载文档后是全新的分配, 指针必然不同。
     */
    fun encodeMarkerIndex(index: Int): String = index.coerceAtLeast(0).toString()

    /** 解析存盘的索引; 内容为垃圾或负数时返回 -1 (视作没有标记)。 */
    fun decodeMarkerIndex(text: String?): Int {
        val v = text?.trim()?.toIntOrNull() ?: return -1
        return if (v >= 0) v else -1
    }

    // ==================== 图层 id <-> 下标 ====================

    /** [ids] 里下标 [index] 处的图层 id; 越界返回 [NO_LAYER]。 */
    fun layerIdAt(ids: List<Long>, index: Int): Long =
        if (index in ids.indices) ids[index] else NO_LAYER

    /** [ids] 里 id 为 [id] 的下标; 找不到(图层已被删除)返回 -1。 */
    fun indexOfLayerId(ids: List<Long>, id: Long): Int =
        if (id == NO_LAYER) -1 else ids.indexOf(id)

    // ==================== 时间轴轨道编排与层级结构 ====================

    /**
     * 构造动画时间轴上展示的图层轨道列表 (按树形层级排序, 过滤空组, 遵循折叠状态)。
     *
     * 规则:
     * 1. 顶层图层自顶向下排列 (底层在最下, 顶层在最上, 对应画布视觉堆叠顺序)。
     * 2. 当遇到图层组时:
     *    - 若为空图层组 (内部无任何普通图层), 则整组过滤, 不占时间轴轨道 (引擎中组本身不可动画化)。
     *    - 若非空, 则先产出图层组轨道 (作为组头轨道)。
     *    - 若该组未被折叠, 则递归产出其内部子图层 (子图层紧跟组头下方, 保持树形层级关系)。
     *    - 若该组已折叠, 则其子图层不占行。
     *
     * @param allLayers 完整的图层列表 (自底向上顺序, 即底层 index 0, 顶层 index n-1)
     * @param depthOf 获取图层深度 (0 起)
     * @param isGroupOf 判定图层是否为组
     * @param nameOf 获取图层名
     * @param collapsedNames 折叠状态的组名集合
     */
    fun <T> buildTimelineTracks(
        allLayers: List<T>,
        depthOf: (T) -> Int,
        isGroupOf: (T) -> Boolean,
        nameOf: (T) -> String,
        collapsedNames: Set<String>,
    ): List<T> {
        if (allLayers.isEmpty()) return emptyList()
        val n = allLayers.size

        fun hasNonGroupDescendants(lo: Int, hi: Int): Boolean {
            for (i in lo until hi) {
                if (!isGroupOf(allLayers[i])) return true
            }
            return false
        }

        val out = ArrayList<T>(n)
        val minDepth = allLayers.minOfOrNull(depthOf) ?: 0

        fun collectBlock(lo: Int, hi: Int, parentDepth: Int) {
            val siblings = ArrayList<Int>()
            for (j in lo until hi) {
                if (depthOf(allLayers[j]) == parentDepth + 1) {
                    siblings.add(j)
                }
            }
            for (k in siblings.indices.reversed()) {
                val j = siblings[k]
                val item = allLayers[j]
                if (isGroupOf(item)) {
                    val e = (j + 1 until hi).firstOrNull { depthOf(allLayers[it]) <= depthOf(item) } ?: hi
                    if (!hasNonGroupDescendants(j + 1, e)) {
                        continue
                    }
                    out.add(item)
                    val isCollapsed = nameOf(item) in collapsedNames
                    if (!isCollapsed && e > j + 1) {
                        collectBlock(j + 1, e, depthOf(item))
                    }
                } else {
                    out.add(item)
                }
            }
        }

        collectBlock(0, n, minDepth - 1)
        return out
    }

    /**
     * 获取指定图层组包含的全部非组后代图层 (用于组折叠时聚合显示全部子帧关键帧)。
     */
    fun <T> findChildLayers(
        allLayers: List<T>,
        groupIndex: Int,
        depthOf: (T) -> Int,
        isGroupOf: (T) -> Boolean,
    ): List<T> {
        if (groupIndex !in allLayers.indices) return emptyList()
        val groupDepth = depthOf(allLayers[groupIndex])
        val n = allLayers.size
        val e = (groupIndex + 1 until n).firstOrNull { depthOf(allLayers[it]) <= groupDepth } ?: n
        val out = ArrayList<T>()
        for (i in groupIndex + 1 until e) {
            val item = allLayers[i]
            if (!isGroupOf(item)) {
                out.add(item)
            }
        }
        return out
    }
}

