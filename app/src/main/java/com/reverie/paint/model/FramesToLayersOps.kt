/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 「帧转图层」的纯逻辑。
 *
 * 与已下线的「图层转帧」正好相反: 那个把多个图层合并成一条轨道(要**删**源层),
 * 这个把一条轨道的各帧拆成多个图层(只**加**不删)。
 *
 * ## 为什么方向相反就安全
 *
 * 引擎里凡是改图层结构的操作(addLayer / removeLayer / clearLayer / setLayerOpacity …,
 * 共 13 处)都会走 `RecompositeProjection()` = `refreshGraphAsync() + waitForDone()`。
 * 真机取证(2026-10-05)证明: **若此刻引擎调度器里已有一次排队的投影更新, 两者互等 ⇒
 * 100% CPU 死循环**(utime 与墙钟 1:1, native 堆并不满)。
 * 「图层转帧」正是自己踩的坑: `importKeyframeFromBitmap` 刚 `setDirty()` 排了队,
 * 紧接着 `removeLayer` 又要求重算并等全部完成。
 *
 * 本功能**全程不删层、也不改任何已有图层**, 每个新图层各自只有一帧,
 * 像素写完就不再动它 ⇒ 不会与任何排队中的更新抢调度器。
 *
 * 判定分支(帧号是否合法、图层是否可动画、层数上限)都在这里, 由 JVM 单测覆盖。
 */
object FramesToLayersOps {

    /** 表示"没有图层"的 id。 */
    const val NO_LAYER: Long = -1L

    /** 一次最多拆多少个图层 —— 超出就该让用户分批做, 否则 UI 与撤销宏都会吃不消。 */
    const val MAX_OUTPUT_LAYERS = 64

    /** 帧转图层被拒的原因。 */
    enum class Reject {
        /** 当前文档不是动画文档, 没有帧可拆 */
        NOT_ANIMATED,

        /** 选中的帧号里没有有效帧 */
        NO_FRAMES,

        /** 帧数超过 [MAX_OUTPUT_LAYERS] */
        TOO_MANY,

        /** 源轨道不是位图层, 取不到画面 */
        SOURCE_NOT_ANIMATABLE,

        /** 源轨道被锁定 */
        SOURCE_LOCKED,
    }

    /** 执行计划。 */
    sealed interface Plan {
        /**
         * [times] 是要拆出的帧号(升序、去重), 每个帧号对应一个新图层。
         * [srcLayer] 是取画面的源轨道。
         */
        data class Ready(
            val srcLayer: Int,
            val times: List<Int>,
        ) : Plan

        /** [offender] 是触发拒绝的帧号; 其余情况为 -1。 */
        data class Refused(
            val reason: Reject,
            val offender: Int = -1,
        ) : Plan
    }

    /**
     * 判定能不能把 [srcLayer] 的这些帧拆成图层。
     *
     * @param times 用户选中的帧号(顺序任意, 内部会去重排序)。
     *   **传空集表示"整条轨道都拆"** —— UI 上不选任何帧就是"全部",
     *   所以这里必须能区分"没选"(→ 全拆)与"选了个不存在的"(→ 拒绝)。
     *   多选模式下用户可能选了**跨轨道**的帧, 这里按 [keyframeTimes] 取交集 ——
     *   属于本轨道的照拆, 不属于的静默剔除(拆错轨道的内容比不拆更糟)。
     * @param keyframeTimes 源轨道**实际存在**的帧号(引擎查得着)
     * @param animatable 源轨道是否位图图层
     * @param locked 源轨道是否锁定
     */
    fun plan(
        srcLayer: Int,
        times: List<Int>,
        keyframeTimes: List<Int>,
        animatable: Boolean,
        locked: Boolean,
    ): Plan {
        val real = keyframeTimes.filter { it >= 0 }.toSortedSet()
        // 引擎一个关键帧都没有 ⇒ 这条轨道根本没东西可拆, 直接拒绝(空集也不能当"全拆")
        if (real.isEmpty()) return Plan.Refused(Reject.NO_FRAMES)
        // 空集 = 全部帧
        val wanted = if (times.isEmpty()) real.toList() else times.filter { it >= 0 }.toSortedSet().toList()
        val valid = wanted.filter { it in real }
        if (valid.isEmpty()) return Plan.Refused(Reject.NO_FRAMES)
        if (valid.size > MAX_OUTPUT_LAYERS) return Plan.Refused(Reject.TOO_MANY, valid[MAX_OUTPUT_LAYERS])
        if (!animatable) return Plan.Refused(Reject.SOURCE_NOT_ANIMATABLE)
        if (locked) return Plan.Refused(Reject.SOURCE_LOCKED)
        return Plan.Ready(srcLayer = srcLayer, times = valid)
    }

    /**
     * 新图层的名字: `帧 03` 这样, 两位补零, 超过 99 就是 `帧 100`。
     *
     * 补零是为了让图层面板里的名字**按字典序就是按帧序** —— 拆完一眼能对上,
     * 不用去数哪一层是第几帧。
     */
    fun layerName(time: Int): String =
        if (time in 0..99) "帧 %02d".format(time) else "帧 $time"

    // ==================== 编排层的推进判据（2026-10-05 补） ====================

    /**
     * 每一环结束后问: 下一环做什么?
     *
     * ## 为什么把它抽出来
     *
     * 这个功能连出 5 个 bug，其中两个是编排层的：
     * 「收尾和递归链脱钩导致只拆第一帧」「收尾里误调删层函数把成果全删掉」。
     * 根因都是**编排逻辑没有任何测试**——纯判定有单测，编排全靠真机试，
     * 而真机一次只能验一个场景。
     *
     * 抽成纯函数后，"链推进/收尾"这两个最容易出错的判据就能被穷举：
     * 含不含最后一环、该不该中止、该不该收尾。
     *
     * @param index 当前这一环的序号(0 起)
     * @param total 总环数
     * @param failed 这一环是否失败
     */
    enum class Next {
        /** 还有下一环, 继续 */
        CONTINUE,

        /** 拆完了, 收尾 */
        FINISH,

        /** 这一环失败, 立刻中止并收尾(已建成的部分仍算完成, 可撤销) */
        ABORT,
    }

    fun nextOf(index: Int, total: Int, failed: Boolean): Next = when {
        failed -> Next.ABORT
        index + 1 >= total -> Next.FINISH
        else -> Next.CONTINUE
    }

    /**
     * 收尾时给用户报什么。
     *
     * @param created 实际建成的层数
     * @param planned 计划要建的数量
     * @return true = 全部完成, false = 中止(部分完成)
     */
    fun isAllDone(created: Int, planned: Int): Boolean = created >= planned

    // ==================== 时间轴折叠：从组结构推导 ====================

    /**
     * 算出时间轴上**不该占行**的图层（返回图层下标集合）。
     *
     * 折叠一个组时, 它内部的层在时间轴上不再单独占行 —— 整组只留组本身那一行。
     * 这与图层面板的组折叠语义一致, 用户不需要学两套。
     *
     * ## 为什么从结构推, 而不是记住一组 id
     *
     * 早先的做法是拆帧时把新建层的 id 收集起来、绘制时按 id 匹配。
     * 真机日志显示那组 id **去重后只剩 1 个**（`collapse ids=... setSize=1`）——
     * `layerId()` 在真机上并不能给出互不相同的值, 整条路不可靠。
     *
     * 从结构推则不需要任何 id, 而且:
     * 用户把层拖出组时自动恢复占行; 重开工程后依然成立（组与子层关系在文档里）。
     *
     * @param indices 各图层的下标（与其余列表对齐）
     * @param depths 各图层的层级（组的子层 depth 更大）
     * @param names 各图层名字
     * @param isGroup 各图层是不是组（只有组能被折叠）
     * @param collapsedNames 当前处于折叠状态的组名
     */
    fun hiddenIndices(
        indices: List<Int>,
        depths: List<Int>,
        names: List<String>,
        isGroup: List<Boolean>,
        collapsedNames: Set<String>,
    ): Set<Int> {
        if (collapsedNames.isEmpty()) return emptySet()
        val n = minOf(indices.size, depths.size, names.size, isGroup.size)
        val out = LinkedHashSet<Int>()
        var i = 0
        while (i < n) {
            // 只有"是组"且"名字在折叠集合里"才折叠; 普通图层即使重名也不折
            if (!(isGroup[i] && names[i] in collapsedNames)) {
                i++
                continue
            }
            val d = depths[i]
            var j = i + 1
            // 深度回到组这一层就停 —— 那是组的内容结束了
            while (j < n && depths[j] > d) {
                out.add(indices[j])
                j++
            }
            i = j
        }
        return out
    }
}
