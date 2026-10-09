/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs

object AutoSaveSnapshotPolicy {
    const val DEFAULT_MAX_SNAPSHOTS = 8
    const val MIN_MAX_SNAPSHOTS = 3
    const val MAX_MAX_SNAPSHOTS = 30

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
     * 统一快照池管理（默认上限 [DEFAULT_MAX_SNAPSHOTS] = 8，可由用户自定义），
     * 超额时按时间戳由旧到新依次淘汰，优先保护标记为 [AutoSaveSnapshot.isEmergency] 的崩溃抢救快照。
     */
    fun prune(
        currentList: List<AutoSaveSnapshot>,
        newSnapshot: AutoSaveSnapshot,
        maxSnapshots: Int = DEFAULT_MAX_SNAPSHOTS,
    ): Pair<List<AutoSaveSnapshot>, List<AutoSaveSnapshot>> {
        val workingList = currentList.toMutableList()
        workingList.add(0, newSnapshot)
        val evicted = mutableListOf<AutoSaveSnapshot>()

        val effectiveLimit = maxSnapshots.coerceIn(MIN_MAX_SNAPSHOTS, MAX_MAX_SNAPSHOTS)
        while (workingList.size > effectiveLimit) {
            val candidate = workingList.minWithOrNull(
                compareBy<AutoSaveSnapshot> { it.isEmergency }
                    .thenBy { it.timestamp }
            ) ?: workingList.last()
            workingList.remove(candidate)
            evicted.add(candidate)
        }

        return Pair(workingList, evicted)
    }
}
