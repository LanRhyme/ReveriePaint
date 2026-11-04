/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.reverie.paint.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * 画廊卡片入场级联的一次性记录器。
 *
 * 旧实现把入场动画的进度 remember 在网格项的键上: LazyGrid 回收复用后每次滚回原位都会
 * 从 0 重播 (缩放 0.74 + 倾斜 + 淡入), 上下滑动时卡片反复闪烁。这里把"已入场"记在画集
 * 级别的集合里 (普通 HashSet, 不参与快照读, 不会在读取时触发重组), 只有真正首次出现的
 * 键才播放入场。
 */
internal class GalleryEntranceTracker {
    private val entered = HashSet<String>(64)

    fun hasEntered(key: String): Boolean = entered.contains(key)

    /** 标记为已入场; 返回 true 表示这是第一次出现 (调用方应当播放入场动画) */
    fun markEntered(key: String): Boolean = entered.add(key)
}

/**
 * 画廊卡片入场进度 (0f -> 1f)。
 *
 * 只有首屏的前 [ENTRANCE_CASCADE_LIMIT] 张卡片参与错峰级联, 其余卡片直接就位 —— 快速滑动
 * 时不会出现"滚到哪里哪里补间"的拖影。动效曲线走 [Motion], 用户把界面动效设为"关闭"时
 * 直接瞬时到位。
 */
@Composable
internal fun rememberGalleryEntrance(
    tracker: GalleryEntranceTracker,
    key: String,
    index: Int,
): Animatable<Float, AnimationVector1D> {
    val cascade = index < ENTRANCE_CASCADE_LIMIT
    val progress = remember(key) {
        Animatable(if (cascade && !tracker.hasEntered(key)) 0f else 1f)
    }
    LaunchedEffect(key) {
        if (!cascade || !tracker.markEntered(key)) return@LaunchedEffect
        val delayMs = (index * ENTRANCE_STAGGER_MS).coerceAtMost(ENTRANCE_MAX_STAGGER_MS)
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, Motion.enterSpring())
    }
    return progress
}

/** 首屏参与入场级联的最大索引 (2 列约 6 行) */
private const val ENTRANCE_CASCADE_LIMIT = 12

/** 相邻卡片入场的时间差 (毫秒) */
private const val ENTRANCE_STAGGER_MS = 22

/** 整批入场的最大错峰时长 (毫秒) */
private const val ENTRANCE_MAX_STAGGER_MS = 180
