/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import com.reverie.paint.model.FrameCachePolicy

/**
 * 移动端内存策略 (部分对应 Plan.md 优化思路 4)。
 *
 * 做法:
 *  1. 缓存按**内存预算**而非固定条目数管理, 淘汰时丢"最不可能马上用到"的那一份
 *     (它用在页面/PDF 缓存上, 这里用在动画回放的帧 RAM 缓存上);
 *  2. 响应系统的内存压力回调, 在**还没到 OOM 之前**主动释放可重建的缓存
 *     (它用 `releaseFocusCachesBelowThreshold`, 这里用 [ComponentCallbacks2])。
 *
 * 缓存释放只碰"随时可以重建"的东西: 回放帧缓存、图层缩略图。文档像素 (真身在
 * C++ 的 KisPaintDevice) 一律不动。
 */

/** 设备堆上限 (字节); 读取一次后缓存 */
private var cachedMaxHeapBytes = 0L

/** 内存压力回调只注册一次 */
@Volatile
private var memoryCallbacksRegistered = false

private var memoryCallbacks: ComponentCallbacks2? = null

internal fun PaintViewModel.maxHeapBytes(): Long {
    val cached = cachedMaxHeapBytes
    if (cached > 0L) return cached
    val bytes =
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.memoryClass.toLong() * 1024L * 1024L
        }.getOrDefault(0L)
    if (bytes > 0L) cachedMaxHeapBytes = bytes
    return bytes
}

/** 回放帧 RAM 缓存的字节预算 */
internal fun PaintViewModel.playbackCacheBudgetBytes(): Long =
    FrameCachePolicy.budgetBytes(maxHeapBytes())

/**
 * 按字节预算淘汰回放帧缓存。正在显示的位图永不回收 (它马上还要被绘制)。
 * 每缓存一帧调一次, 单帧开销只是遍历已缓存帧号。
 */
internal fun PaintViewModel.trimPlaybackRamCache(keepFrame: Int) {
    val cache = anim.playbackRamCache
    if (cache.isEmpty()) return
    val frameBytes = HashMap<Int, Long>(cache.size)
    for ((frame, bmp) in cache) {
        frameBytes[frame] = bmp.byteCount.toLong()
    }
    val evict =
        FrameCachePolicy.framesToEvict(
            frameBytes,
            playbackCacheBudgetBytes(),
            keepFrame,
            FrameCachePolicy.MAX_FRAMES,
        )
    if (evict.isEmpty()) return
    val showing = displayBitmap
    for (frame in evict) {
        val bmp = cache.remove(frame) ?: continue
        if (bmp === showing) continue
        if (!bmp.isRecycled) bmp.recycle()
    }
}

/** 释放可重建的缓存。[aggressive] 为 true 时连图层缩略图一起丢 (内存压力下的选择) */
internal fun PaintViewModel.releaseReclaimableCaches(aggressive: Boolean) {
    val showing = displayBitmap
    for ((_, bmp) in anim.playbackRamCache) {
        if (bmp !== showing && !bmp.isRecycled) bmp.recycle()
    }
    anim.playbackRamCache.clear()
    // 录制器的快照字节缓存同样是"随时能从磁盘重建"的几十 MB 级数据
    recorder.dropSnapshotCache()
    if (aggressive) {
        clearLayerThumbs()
    }
}

/** 把内存压力回调挂到 application context 上 (幂等) */
internal fun PaintViewModel.registerMemoryPressureCallbacks() {
    if (memoryCallbacksRegistered) return
    val ctx = if (isAppContextReady()) appContext else return
    val cb =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // RUNNING_LOW 以下就开始丢缓存: 等到 OOM 再丢, 用户损失的是
                // 正在播放的动画进度甚至进程本身
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    releaseReclaimableCaches(aggressive = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                }
            }

            override fun onLowMemory() {
                releaseReclaimableCaches(aggressive = true)
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
    memoryCallbacks = cb
    ctx.registerComponentCallbacks(cb)
    memoryCallbacksRegistered = true
}

/** 解绑内存压力回调 (VM 销毁时调用, 避免 application 持有已销毁的 VM) */
internal fun PaintViewModel.unregisterMemoryPressureCallbacks() {
    val cb = memoryCallbacks ?: return
    memoryCallbacks = null
    memoryCallbacksRegistered = false
    if (isAppContextReady()) {
        runCatching { appContext.unregisterComponentCallbacks(cb) }
    }
}

/** 回放/动画结束后释放帧缓存 */
internal fun PaintViewModel.recyclePlaybackBitmap(bmp: Bitmap?) {
    if (bmp == null || bmp.isRecycled) return
    if (bmp === displayBitmap) return
    bmp.recycle()
}
