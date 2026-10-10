/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import com.reverie.paint.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PaintViewModel.animationPlay() {
    if (anim.isPlaying) return
    val start = playbackStartFrame()
    val end = playbackEndFrame()
    if (start >= end) {
        showActionToast(R.string.toast_single_frame, R.drawable.ic_repeat_none)
        return
    }
    // 若当前播放头在已有帧块范围外或正好在末尾, 从起点开始播放
    if (anim.currentTime >= end || anim.currentTime < start) {
        animationSeek(start)
    }
    anim.isPlaying = true
    anim.playGen++
    val gen = anim.playGen
    startAnimAudio()
    renderHandler?.post {
        // 播放时不显示洋葱皮: 与引擎同线程串行下发, 保证第一帧渲染前生效;
        // 暂停/停止经 animationPause 投递还原。
        ReverieCoreBridge.setOnionSkinSuppressed(true)
        val interval = 1000L / anim.framerate.coerceIn(1, 240)
        renderHandler?.postDelayed({ animationStep(gen) }, interval)
    }
}

/** 暂停播放 (自增代际令牌使挂起的步进失效) */
internal fun PaintViewModel.animationPause() {
    if (!anim.isPlaying) return
    anim.isPlaying = false
    anim.playGen++
    stopAnimAudio()
    // 恢复洋葱皮并重新渲染当前静止帧, 然后释放 RAM 缓存
    renderHandler?.post {
        ReverieCoreBridge.setOnionSkinSuppressed(false)
        doRender()
        for ((_, bmp) in anim.playbackRamCache) {
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        anim.playbackRamCache.clear()
    }
}

/**
 * 离开绘画页时停止动画播放 (循环步进链 + 导入音频的播放器)。
 *
 * [animationStep] 是挂在 reverie-render 线程上的自续定时链, 默认循环播放;
 * 页面退出不停止的话, 用户停留在主页/回放页时仍会按帧率持续做全量投影渲染,
 * 并让导入音频的 MediaPlayer 一直循环播放 —— 系统会把这种"持续出帧 + 常驻
 * 媒体会话"判为应用在静音播放视频, 直接表现为回放播完后耗电仍居高不下
 * (动画帧还会覆盖回放画布)。幂等, 可安全重复调用。
 */
internal fun PaintViewModel.stopAnimationPlaybackForPageExit() {
    if (anim.isPlaying) animationPause()
    stopAnimAudio()
}

/** 清理循环播放帧 RAM 缓存 (释放位图堆内存) */
internal fun PaintViewModel.clearPlaybackRamCache() {
    val bmpsToRecycle: List<Bitmap>
    synchronized(anim.playbackRamCache) {
        if (anim.playbackRamCache.isEmpty()) return
        bmpsToRecycle = anim.playbackRamCache.values.toList()
        anim.playbackRamCache.clear()
    }
    renderHandler?.post {
        displayBitmap = frontBuffer
        for (bmp in bmpsToRecycle) {
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }
}

/**
 * 彻底重置动画状态镜像与运行时资源 (新建画布、加载非动画工程或退出到画廊时调用)。
 * 保证静态画布 100% 不继承任何动画状态与时间轴展开标记。
 */
internal fun PaintViewModel.resetAnimationState() {
    stopAnimationPlaybackForPageExit()
    clearPlaybackRamCache()
    anim.reset()
}

// ============================================================
// 动画音频: 导入的资源在播放时同步播放 (循环), 暂停/停止即停
// ============================================================

/** 播放所有导入的音频资源。字节从引擎取出写临时文件, MediaPlayer 循环播放。 */
private fun PaintViewModel.startAnimAudio() {
    stopAnimAudio()
    val names = anim.audioAssets
    if (names.isEmpty()) return
    val dir = java.io.File(appContext.cacheDir, "anim_audio").apply { mkdirs() }
    for (name in names) {
        val bytes = runCatching { ReverieCoreBridge.revAssetBytes(name) }.getOrNull() ?: continue
        val f = java.io.File(dir, name.replace('/', '_'))
        runCatching {
            f.writeBytes(bytes)
            val mp = android.media.MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.isLooping = anim.loopPlayback
            mp.prepare()
            mp.start()
            animAudioPlayers.add(mp)
        }
    }
}

/** 停止动画音频播放并释放播放器 */
private fun PaintViewModel.stopAnimAudio() {
    for (mp in animAudioPlayers) {
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
    }
    animAudioPlayers.clear()
}

internal fun PaintViewModel.animationTogglePlay() {
    if (anim.isPlaying) animationPause() else animationPlay()
}

/** 切换单次 / 循环播放 */
internal fun PaintViewModel.animationToggleLoop() {
    anim.loopPlayback = !anim.loopPlayback
    for (mp in animAudioPlayers) {
        runCatching { mp.isLooping = anim.loopPlayback }
    }
    showActionToast(
        if (anim.loopPlayback) R.string.anim_loop else R.string.anim_single_play,
        if (anim.loopPlayback) R.drawable.ic_repeat_loop else R.drawable.ic_repeat_none,
    )
}

/** 播放时跳回起点并停止 */
internal fun PaintViewModel.animationStop() {
    animationPause()
    animationSeek(playbackStartFrame())
}

// ============================================================
// 播放驱动: 步进逻辑与代际令牌机制
// ============================================================

/**
 * 实际用于播放的起始帧号: 优先用范围勾选时的起点, 否则是整个时间轴已有帧起点。
 */
internal fun PaintViewModel.playbackStartFrame(): Int {
    if (anim.hasCustomPlaybackRange && anim.playbackEnd > anim.playbackStart) return anim.playbackStart
    val allTimes = anim.keyframeCache.values.flatten()
    return allTimes.minOrNull() ?: 0
}

/**
 * 实际用于播放的结束帧号 (闭区间端点):
 * 优先用范围勾选时的终点; 若未设置, 取各轨道关键帧终点 (含末帧 hold)。
 */
internal fun PaintViewModel.playbackEndFrame(): Int {
    if (anim.hasCustomPlaybackRange && anim.playbackEnd > anim.playbackStart) return anim.playbackEnd

    var maxKey = -1
    for ((layer, times) in anim.keyframeCache) {
        if (times.isNotEmpty()) {
            val lastT = times.last()
            val hold = anim.lastFrameHold[layer] ?: 1
            val end = lastT + hold - 1
            if (end > maxKey) maxKey = end
        }
    }
    return if (maxKey >= 0) maxKey else maxOf(0, anim.length - 1)
}

/** 单步播放。运行在 reverie-render 线程上 (由 animationPlay 投递)。 */
private fun PaintViewModel.animationStep(gen: Int) {
    if (gen != anim.playGen || !anim.isPlaying) return
    // 兜底: 步进链只能在绘画页运行。离开绘画页的路径都会显式停播, 这里再挡
    // 一次, 保证页面外的异常路径也不会残留按帧率渲染的"静音视频"式活动。
    if (currentPage != Page.PAINTING) {
        mainHandler.post { animationPause() }
        return
    }

    val start = playbackStartFrame()
    val end = playbackEndFrame()
    if (start >= end) {
        mainHandler.post { animationPause() }
        return
    }

    val cur = anim.currentTime
    if (cur >= end) {
        if (!anim.loopPlayback) {
            mainHandler.post { animationPause() }
            return
        }
    }
    val next = if (cur >= end || cur < start) start else cur + 1

    ReverieCoreBridge.setAnimationCurrentTime(next, false)
    anim.currentTime = next

    val cachedBmp = anim.playbackRamCache[next]
    if (cachedBmp != null && !cachedBmp.isRecycled && cachedBmp.width == renderW && cachedBmp.height == renderH) {
        displayBitmap = cachedBmp
        val tv = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            tv?.postInvalidateOnAnimation()
        } else {
            tv?.postInvalidate()
        }
    } else {
        doRender()
        // 覆盖同一帧号的旧缓存 (尺寸可能已变), 但不能回收正在显示的那张
        val stale = anim.playbackRamCache[next]
        if (stale != null && stale !== displayBitmap && !stale.isRecycled) {
            stale.recycle()
        }
        val front = frontBuffer
        if (front != null && !front.isRecycled) {
            val copy = front.copy(Bitmap.Config.ARGB_8888, false)
            if (copy != null) {
                anim.playbackRamCache[next] = copy
                // 按字节预算 (堆的 1/16, 上限 256MB) + 最远优先淘汰。原先写死
                // "最多 120 帧": 4096 画幅下单帧 64MB, 120 帧就是 7.7GB, 必然
                // 触顶甚至 OOM —— 大画幅播放发热/卡顿的元凶之一。
                trimPlaybackRamCache(next)
            }
        }
    }

    if (!anim.loopPlayback && next >= end) {
        // 单次播放到达终点: 渲染完此最后一帧后停在最后一帧并暂停
        mainHandler.post { animationPause() }
        return
    }

    // 按帧率换算步进间隔: 帧动画跟随文档帧率 (12fps = 83ms)
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
