/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs

object AutoSaveSnapshotPolicy {
    const val MAX_SNAPSHOTS_PER_PROJECT = 5
    const val MAX_GLOBAL_SNAPSHOTS = 30

    fun projectKey(snapshot: AutoSaveSnapshot): String =
        snapshot.masterPath.ifBlank { snapshot.displayName }

    /**
     * 判断是否应跳过记录本次快照:
     * 1. 非紧急快照下，若当前工程最新快照的笔画数与图层数完全相同且间隔在 90 秒内，跳过以避免冗余刷盘
     * 2. 防空置冲刷保护：若历史快照笔画丰富 (>20 笔)，当前保存突然变成 0 笔且间隔很短，跳过录入
     */
    fun shouldSkipRecord(
        existingForProject: List<AutoSaveSnapshot>,
        newStrokeCount: Int,
        newTimestamp: Long,
        isEmergency: Boolean,
        newLayerCount: Int = 0,
    ): Boolean {
        if (isEmergency) return false
        val latest = existingForProject.firstOrNull() ?: return false
        val timeDiff = newTimestamp - latest.timestamp
        val strokeDiff = abs(newStrokeCount - latest.strokeCount)
        val layerDiff = if (newLayerCount > 0) abs(newLayerCount - latest.layerCount) else 0
        if (timeDiff in 0 until 90_000L && strokeDiff == 0 && layerDiff == 0) {
            return true
        }
        if (newStrokeCount == 0 && latest.strokeCount > 20 && timeDiff in 0 until 300_000L) {
            return true
        }
        return false
    }

    /**
     * 计算纳入 [newSnapshot] 后的保留列表与淘汰列表。
     *
     * 淘汰规则:
     * 1. 单工程配额 (MAX_SNAPSHOTS_PER_PROJECT = 5):
     *    只在该工程超额时淘汰该工程自身的最旧快照，其他工程绝对不受波及。
     *    优先保护标记为 [AutoSaveSnapshot.isEmergency] 的崩溃抢救快照。
     * 2. 全局总容量 (MAX_GLOBAL_SNAPSHOTS = 30):
     *    跨工程总数超标时，只从快照数量 > 1 的工程中淘汰最旧快照。
     *    优先挑选快照数量最多、最旧的工程。
     *    绝对严禁淘汰任何工程的唯一幸存快照！
     */
    fun prune(
        currentList: List<AutoSaveSnapshot>,
        newSnapshot: AutoSaveSnapshot,
    ): Pair<List<AutoSaveSnapshot>, List<AutoSaveSnapshot>> {
        val workingList = currentList.toMutableList()
        workingList.add(0, newSnapshot)
        val evicted = mutableListOf<AutoSaveSnapshot>()

        val targetKey = projectKey(newSnapshot)

        // 阶段一: 单工程配额淘汰
        val thisProjectSnaps = workingList.filter { projectKey(it) == targetKey }
        if (thisProjectSnaps.size > MAX_SNAPSHOTS_PER_PROJECT) {
            val candidates = thisProjectSnaps.sortedWith(
                compareBy<AutoSaveSnapshot> { it.isEmergency }
                    .thenBy { it.timestamp }
            )
            val excess = thisProjectSnaps.size - MAX_SNAPSHOTS_PER_PROJECT
            for (i in 0 until excess) {
                val toEvict = candidates[i]
                workingList.remove(toEvict)
                evicted.add(toEvict)
            }
        }

        // 阶段二: 全局总容量淘汰
        while (workingList.size > MAX_GLOBAL_SNAPSHOTS) {
            val grouped = workingList.groupBy { projectKey(it) }
            val multiSnapProjects = grouped.filter { it.value.size > 1 }
            val victimGroup = if (multiSnapProjects.isNotEmpty()) {
                multiSnapProjects.maxByOrNull { entry ->
                    entry.value.size * 10_000_000_000_000L - entry.value.minOf { it.timestamp }
                }?.value
            } else {
                workingList
            }

            val victim = victimGroup?.minWithOrNull(
                compareBy<AutoSaveSnapshot> { it.isEmergency }
                    .thenBy { it.timestamp }
            ) ?: workingList.last()

            workingList.remove(victim)
            evicted.add(victim)
        }

        return Pair(workingList, evicted)
    }
}
