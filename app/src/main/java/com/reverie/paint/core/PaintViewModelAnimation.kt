/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 新建动画画布的默认帧率。 */
internal const val DEFAULT_ANIMATION_FPS = 12

/**
 * 动画 (帧 / 轨道 / 时间轴) 的 UI 状态镜像。
 *
 * 引擎侧真身在 Krita: 每条轨道 = 一个图层, 帧数据由该图层的
 * KisRasterKeyframeChannel 持有 (每个关键帧一份 KisPaintDevice, 共享瓦片)。
 * 这里只镜像 UI 需要读取的元信息, **不复制任何像素数据** (AGENTS.md §4 铁律 1)。
 *
 * 线程约束: 凡是要读引擎的同步动作 (syncAnimationFromNative) 必须在
 * reverie-render 线程执行, 即只能写在 runCore 的 op 块内。
 */
internal class AnimationState {
    /** 当前文档是否为动画文档 (任一轨道已启用动画) */
    var enabled by mutableStateOf(false)

    /** 时间轴面板是否展开 */
    var panelOpen by mutableStateOf(false)

    /** 当前帧号 (与引擎 currentTime 同步) */
    var currentTime by mutableIntStateOf(0)

    /** 内容总帧数 (末关键帧帧号 + 1) */
    var length by mutableIntStateOf(1)

    /** 帧率 */
    var framerate by mutableIntStateOf(DEFAULT_ANIMATION_FPS)

    /** 播放范围 (闭区间) */
    var playbackStart by mutableIntStateOf(0)

    var playbackEnd by mutableIntStateOf(0)

    /** 是否正在播放 */
    var isPlaying by mutableStateOf(false)

    /**
     * 时间轴上的选中轨道 (图层索引)。-1 表示跟随当前图层。
     * 动画里"轨道"就是图层, 不引入第二套层级。
     */
    var selectedTrack by mutableIntStateOf(-1)

    /** 时间轴上多选的帧号 */
    var selectedFrames by mutableStateOf<Set<Int>>(emptySet())

    /** 时间轴视图: 每帧像素宽 (缩放) 与水平滚动量 */
    var frameWidthPx by mutableFloatStateOf(34f)

    var scrollPx by mutableFloatStateOf(0f)

    /** 洋葱皮开关与前后帧数 */
    var onionSkin by mutableStateOf(false)

    var onionPrev by mutableIntStateOf(1)

    var onionNext by mutableIntStateOf(1)

    /**
     * 轨道关键帧缓存: 图层索引 -> 升序帧号列表。
     * 仅用于时间轴绘制加速 (避免每帧逐轨道跨 JNI 查询), 引擎始终是真身。
     */
    var keyframeCache by mutableStateOf<Map<Int, List<Int>>>(emptyMap())

    /** 结构版本号: 关键帧增删改后自增, 驱动 Compose 重组 */
    var revision by mutableIntStateOf(0)

    /**
     * 播放代际令牌。暂停 / 跳转 / 停止时自增, 使已挂起的播放回调失效,
     * 避免"幽灵步进"继续推进时间轴 (与 PlaybackEngine.ReplaySession.stepGen 同一手法)。
     */
    var playGen = 0
}

// ============================================================
// 同步: 引擎 -> UI 状态镜像
// ============================================================

/**
 * 从引擎重新读取动画元信息, 必须在 reverie-render 线程调用
 * (即写在 runCore 的 op 块里)。
 */
internal fun PaintViewModel.syncAnimationFromNative() {
    anim.enabled = ReverieCoreBridge.animationEnabled()
    anim.currentTime = ReverieCoreBridge.animationCurrentTime()
    anim.length = maxOf(1, ReverieCoreBridge.animationLength())
    anim.framerate = ReverieCoreBridge.animationFramerate()

    val range = ReverieCoreBridge.animationPlaybackRange()
    if (range.size >= 2) {
        anim.playbackStart = range[0]
        anim.playbackEnd = range[1]
    }

    anim.keyframeCache = readKeyframeCache()
    anim.revision++
}

/** 逐轨道读取关键帧位置, 组成绘制缓存。必须在 reverie-render 线程调用。 */
private fun PaintViewModel.readKeyframeCache(): Map<Int, List<Int>> {
    val map = HashMap<Int, List<Int>>()
    val count = ReverieCoreBridge.layerCount()
    for (i in 0 until count) {
        if (!ReverieCoreBridge.layerAnimated(i)) continue
        map[i] = ReverieCoreBridge.keyframeTimes(i).toList()
    }
    return map
}

/**
 * 新建动画画布后的引擎侧初始化: 为最上面的可动画图层开启动画通道, 并设置帧率。
 *
 * 必须在 reverie-render 线程调用。Krita 在创建通道时会自动补一个 frame 0,
 * 因此轨道不会处于"零帧"状态; 索引 0 是背景层, 不参与动画。
 */
internal fun PaintViewModel.nativeInitAnimation(fps: Int = DEFAULT_ANIMATION_FPS) {
    for (i in ReverieCoreBridge.layerCount() - 1 downTo 1) {
        if (ReverieCoreBridge.layerAnimatable(i)) {
            ReverieCoreBridge.enableLayerAnimation(i)
            break
        }
    }
    ReverieCoreBridge.setAnimationFramerate(fps)
}

// ============================================================
// 时间轴: 时间跳转与播放范围
// ============================================================

/** 跳转到指定帧。默认不记录撤销 (与桌面 Krita 的时间轴行为一致)。 */
internal fun PaintViewModel.animationSeek(
    time: Int,
    recordUndo: Boolean = false,
) {
    val t = time.coerceAtLeast(0)
    runCore(
        after = {
            anim.currentTime = t
            anim.selectedFrames = emptySet()
        },
    ) {
        ReverieCoreBridge.setAnimationCurrentTime(t, recordUndo)
    }
}

/** 上一帧 / 下一帧 (跳过无内容的帧位置, 落在最近的关键帧上) */
internal fun PaintViewModel.animationStepFrame(delta: Int) {
    if (delta == 0) return
    val layer = selectedTrackIndex()
    if (layer < 0) return
    runCore(
        after = { syncAnimationFromNativeAfter() },
    ) {
        val cur = ReverieCoreBridge.animationCurrentTime()
        val target =
            if (delta > 0) {
                ReverieCoreBridge.nextKeyframeTime(layer, cur).takeIf { it >= 0 } ?: (cur + 1)
            } else {
                ReverieCoreBridge.previousKeyframeTime(layer, cur).takeIf { it >= 0 } ?: (cur - 1)
            }
        ReverieCoreBridge.setAnimationCurrentTime(target.coerceAtLeast(0), false)
    }
}

internal fun PaintViewModel.animationSetFramerate(fps: Int) {
    val v = fps.coerceIn(1, 240)
    runCore(after = { anim.framerate = v }) {
        ReverieCoreBridge.setAnimationFramerate(v)
    }
}

internal fun PaintViewModel.animationSetPlaybackRange(
    start: Int,
    end: Int,
) {
    val s = start.coerceAtLeast(0)
    val e = maxOf(end, s)
    runCore(
        after = {
            anim.playbackStart = s
            anim.playbackEnd = e
        },
    ) {
        ReverieCoreBridge.setAnimationPlaybackRange(s, e)
    }
}

/** 当前生效的轨道索引: 优先时间轴选中, 否则跟随画布当前图层 */
internal fun PaintViewModel.selectedTrackIndex(): Int {
    val selected = anim.selectedTrack
    return if (selected >= 0) selected else ReverieCoreBridge.currentLayerIndex()
}

// ============================================================
// 轨道: 开启动画
// ============================================================

/** 为指定图层(轨道)开启动画。幂等: 已开启时只刷新缓存。 */
internal fun PaintViewModel.animationEnableTrack(layerIndex: Int) {
    if (layerIndex < 0) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        if (!ReverieCoreBridge.layerAnimatable(layerIndex)) return@runCore
        ReverieCoreBridge.enableLayerAnimation(layerIndex)
    }
}

// ============================================================
// 帧: 增 / 删 / 复制 / 移动 / 一拍N
// ============================================================

/**
 * 在当前帧位置插入一个关键帧。
 *
 * @param duplicate true = 复制前一帧内容 (逐帧作画最高频操作), false = 空白帧
 */
internal fun PaintViewModel.animationAddKeyframe(
    layerIndex: Int = -1,
    duplicate: Boolean = false,
) {
    val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
    if (layer < 0) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        if (!ReverieCoreBridge.layerAnimatable(layer)) return@runCore
        if (!ReverieCoreBridge.layerAnimated(layer)) {
            ReverieCoreBridge.enableLayerAnimation(layer)
        }
        val time = ReverieCoreBridge.animationCurrentTime()
        if (duplicate) {
            ReverieCoreBridge.addDuplicateKeyframe(layer, time)
        } else {
            ReverieCoreBridge.addKeyframe(layer, time)
        }
    }
}

/** 删除当前帧位置的关键帧 (轨道至少保留一帧) */
internal fun PaintViewModel.animationRemoveKeyframe(layerIndex: Int = -1) {
    val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
    if (layer < 0) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        val time = ReverieCoreBridge.animationCurrentTime()
        ReverieCoreBridge.removeKeyframe(layer, time)
    }
}

/**
 * 把当前帧复制到 [toTime]。
 *
 * @param share true = 共享像素 (clone, 更省内存, 首次落笔才分叉), false = 独立副本
 */
internal fun PaintViewModel.animationCopyCurrentFrameTo(
    toTime: Int,
    layerIndex: Int = -1,
    share: Boolean = true,
) {
    val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
    if (layer < 0) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        val from = ReverieCoreBridge.animationCurrentTime()
        if (share) {
            ReverieCoreBridge.cloneKeyframe(layer, from, toTime)
        } else {
            ReverieCoreBridge.copyKeyframe(layer, from, toTime)
        }
    }
}

/** 把轨道上的一个关键帧从 [fromTime] 移动到 [toTime] (拖拽落位) */
internal fun PaintViewModel.animationMoveKeyframe(
    layerIndex: Int,
    fromTime: Int,
    toTime: Int,
) {
    if (layerIndex < 0 || fromTime == toTime) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        ReverieCoreBridge.moveKeyframe(layerIndex, fromTime, toTime)
    }
}

/** 一拍 N: 整条轨道重排, 首帧原位, 后续每 duration 帧一个。单步撤销。 */
internal fun PaintViewModel.animationSetTrackDuration(
    layerIndex: Int,
    duration: Int,
) {
    if (layerIndex < 0 || duration < 1) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        ReverieCoreBridge.setAllKeyframesDuration(layerIndex, duration)
    }
}

/**
 * 一拍 N (选中帧版): 只重排选中的帧, 区间内未选中的帧按原间距整体后移。
 * 选中范围为时间轴上多选的帧号集合。
 */
internal fun PaintViewModel.animationSetSelectedDuration(
    layerIndex: Int,
    duration: Int,
) {
    val selected = anim.selectedFrames
    if (layerIndex < 0 || duration < 1 || selected.isEmpty()) return
    val times = selected.toIntArray()
    runCore(after = { syncAnimationFromNativeAfter() }) {
        ReverieCoreBridge.setSelectedKeyframesDuration(layerIndex, times, duration)
    }
}

// ============================================================
// 播放
// ============================================================

/**
 * 开始循环播放。
 *
 * 不走 runCore: 播放是连续的热路径, 每帧经 runCore 会额外绕一次主线程投递
 * (engine -> scheduleRender -> mainHandler), 在 24fps 下必然掉帧。
 * 这里直接投到 reverie-render 线程, 保持引擎调用不上 UI 线程 (铁律 2)。
 */
internal fun PaintViewModel.animationPlay() {
    if (anim.isPlaying) return
    anim.isPlaying = true
    anim.playGen++
    val gen = anim.playGen
    renderHandler?.post { animationStep(gen) }
}

/** 暂停播放 (自增代际令牌使挂起的步进失效) */
internal fun PaintViewModel.animationPause() {
    if (!anim.isPlaying) return
    anim.isPlaying = false
    anim.playGen++
}

internal fun PaintViewModel.animationTogglePlay() {
    if (anim.isPlaying) animationPause() else animationPlay()
}

/** 播放时跳回起点并停止 */
internal fun PaintViewModel.animationStop() {
    animationPause()
    animationSeek(playbackStartFrame())
}

private fun PaintViewModel.playbackStartFrame(): Int = anim.playbackStart.coerceAtLeast(0)

private fun PaintViewModel.playbackEndFrame(): Int {
    val end = anim.playbackEnd
    val fallback = maxOf(0, anim.length - 1)
    return if (end > anim.playbackStart) end else fallback
}

/** 单步播放。运行在 reverie-render 线程上 (由 animationPlay 投递)。 */
private fun PaintViewModel.animationStep(gen: Int) {
    if (gen != anim.playGen || !anim.isPlaying) return

    val start = playbackStartFrame()
    val end = playbackEndFrame()
    val next = if (anim.currentTime >= end) start else anim.currentTime + 1

    ReverieCoreBridge.setAnimationCurrentTime(next, false)
    anim.currentTime = next
    scheduleRender(immediate = true)

    // 按帧率换算步进间隔: 回放引擎里硬编码的 16ms 只适合 60fps 的过程回放,
    // 帧动画必须跟随文档帧率 (12fps = 83ms), 否则播放速度会快数倍
    val interval = 1000L / anim.framerate.coerceIn(1, 240)
    renderHandler?.postDelayed({ animationStep(gen) }, interval)
}

// ============================================================
// 辅助
// ============================================================

/**
 * runCore 的 after 回调里用的同步入口。
 *
 * after 运行在主线程, 不能读引擎 (铁律 2), 所以这里只做状态收尾;
 * 真正的引擎读取由紧接着的下一次 runCore 完成。实践中的做法是:
 * 变更类操作统一用 after = { syncAnimationFromNativeAfter() },
 * 该函数把同步动作重新投回渲染线程。
 */
internal fun PaintViewModel.syncAnimationFromNativeAfter() {
    renderHandler?.post { syncAnimationFromNative() }
}
