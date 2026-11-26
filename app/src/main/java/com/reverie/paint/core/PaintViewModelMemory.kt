/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import com.reverie.paint.R
import com.reverie.paint.model.FrameCachePolicy
import com.reverie.paint.model.RuntimeMemoryPolicy
import com.reverie.paint.model.RuntimeMemorySnapshot
import java.io.File

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
    if (aggressive) {
        clearLayerThumbs()
    }
    val rh = renderHandler
    if (rh != null) {
        rh.post {
            val keepRefSnapshot = referenceWindowOpen && referenceActiveTab == 1
            displayTiles.trimMemory(aggressive = aggressive || !keepRefSnapshot)
            if (displayTiles.published != null && !keepRefSnapshot) {
                displayBitmap = null
            }
            runCatching { ReverieCoreBridge.trimNativeMemory(aggressive) }
        }
    }
}

private fun PaintViewModel.collectProcessAndSystemSnapshot(
    nativeStats: LongArray?,
    canClearHistory: Boolean,
): RuntimeMemorySnapshot {
    var availMem = 0L
    var totalMem = 0L
    var lowMem = false
    if (isAppContextReady()) {
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                availMem = mi.availMem
                totalMem = mi.totalMem
                lowMem = mi.lowMemory
            }
        }
    }
    val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
    var appRss = runCatching {
        RuntimeMemoryPolicy.parseStatmRssBytes(File("/proc/self/statm").readText(), pageSize)
    }.getOrDefault(0L)
    if (appRss <= 0L) {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
        val nativeUsed = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        appRss = javaUsed + nativeUsed
    }
    return RuntimeMemorySnapshot(
        appRssBytes = appRss,
        sysAvailBytes = availMem,
        sysTotalBytes = totalMem,
        sysLowMemory = lowMem,
        kritaTotalBytes = nativeStats?.getOrNull(0) ?: 0L,
        kritaRealBytes = nativeStats?.getOrNull(1) ?: 0L,
        kritaHistoryBytes = nativeStats?.getOrNull(2) ?: 0L,
        kritaPoolBytes = nativeStats?.getOrNull(3) ?: 0L,
        kritaSwapBytes = nativeStats?.getOrNull(4) ?: 0L,
        kritaTilesInMemory = nativeStats?.getOrNull(5) ?: 0L,
        canClearHistory = canClearHistory,
    )
}

/** 在引擎线程采集 Krita 瓦片统计并汇总进程 RSS 与系统可用内存，通过 [onResult] 回到主线程。 */
internal fun PaintViewModel.refreshRuntimeMemorySnapshot(onResult: (RuntimeMemorySnapshot) -> Unit) {
    val rh = renderHandler
    if (rh == null) {
        onResult(collectProcessAndSystemSnapshot(null, false))
        return
    }
    rh.post {
        val stats = runCatching { ReverieCoreBridge.nativeMemoryStats() }.getOrNull()
        val hasHistory = runCatching { ReverieCoreBridge.canUndo() || ReverieCoreBridge.canRedo() }.getOrDefault(false)
        val snap = collectProcessAndSystemSnapshot(stats, hasHistory)
        mainHandler.post { onResult(snap) }
    }
}

/** 用户主动「立即释放缓存」: 清理可重建缓冲、冷瓦片换出到磁盘，绝不动撤销历史。 */
internal fun PaintViewModel.manualReleaseCaches(
    onDone: (before: RuntimeMemorySnapshot, after: RuntimeMemorySnapshot) -> Unit,
) {
    val beforeHolder = arrayOfNulls<RuntimeMemorySnapshot>(1)
    val afterHolder = arrayOfNulls<RuntimeMemorySnapshot>(1)
    val showing = displayBitmap
    for ((_, bmp) in anim.playbackRamCache) {
        if (bmp !== showing && !bmp.isRecycled) bmp.recycle()
    }
    anim.playbackRamCache.clear()
    clearLayerThumbs()
    realInkSink?.trimMemory()
    runCore(render = false, after = {
        val before = beforeHolder[0] ?: collectProcessAndSystemSnapshot(null, false)
        val after = afterHolder[0] ?: collectProcessAndSystemSnapshot(null, false)
        onDone(before, after)
    }) {
        val beforeStats = runCatching { ReverieCoreBridge.nativeMemoryStats() }.getOrNull()
        val beforeHasHistory = runCatching { ReverieCoreBridge.canUndo() || ReverieCoreBridge.canRedo() }.getOrDefault(false)
        beforeHolder[0] = collectProcessAndSystemSnapshot(beforeStats, beforeHasHistory)

        displayTiles.trimMemory(aggressive = true)
        if (displayTiles.published != null && !(referenceWindowOpen && referenceActiveTab == 1)) {
            displayBitmap = null
        }
        runCatching { ReverieCoreBridge.trimNativeMemory(true) }
        System.gc()

        val afterStats = runCatching { ReverieCoreBridge.nativeMemoryStats() }.getOrNull()
        val afterHasHistory = runCatching { ReverieCoreBridge.canUndo() || ReverieCoreBridge.canRedo() }.getOrDefault(false)
        afterHolder[0] = collectProcessAndSystemSnapshot(afterStats, afterHasHistory)
    }
}

/** 用户二次确认「清空历史记录」: 释放撤销/重做栈占据的大量历史瓦片，不改动当前画布像素。 */
internal fun PaintViewModel.manualClearUndoHistory(
    onDone: (after: RuntimeMemorySnapshot) -> Unit,
) {
    if (isHistoryBlockedByTransform) return
    val afterHolder = arrayOfNulls<RuntimeMemorySnapshot>(1)
    runCore(render = false, after = {
        refreshUndoCounts()
        onDone(afterHolder[0] ?: collectProcessAndSystemSnapshot(null, false))
    }) {
        ReverieCoreBridge.clearUndoHistory()
        displayTiles.trimMemory(aggressive = true)
        if (displayTiles.published != null && !(referenceWindowOpen && referenceActiveTab == 1)) {
            displayBitmap = null
        }
        runCatching { ReverieCoreBridge.trimNativeMemory(true) }
        System.gc()
        val stats = runCatching { ReverieCoreBridge.nativeMemoryStats() }.getOrNull()
        val hasHistory = runCatching { ReverieCoreBridge.canUndo() || ReverieCoreBridge.canRedo() }.getOrDefault(false)
        afterHolder[0] = collectProcessAndSystemSnapshot(stats, hasHistory)
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
                    val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                    releaseReclaimableCaches(aggressive = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                    realInkSink?.trimMemory()
                    if (critical) {
                        triggerLowMemoryWarningIfNeeded()
                    }
                }
            }

            override fun onLowMemory() {
                releaseReclaimableCaches(aggressive = true)
                realInkSink?.trimMemory()
                triggerLowMemoryWarningIfNeeded()
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

/** 当前设备可用内存或 Native 堆是否偏紧（用于保存前主动腾挪缓存） */
internal fun PaintViewModel.isMemoryTight(): Boolean {
    if (!isAppContextReady()) return false
    return try {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val nativeHeap = try { Debug.getNativeHeapAllocatedSize() } catch (_: Throwable) { 0L }
        val tightAvail = mi.lowMemory || (mi.availMem in 1L until 512L * 1024 * 1024)
        val tightNative = (mi.totalMem > 0L && nativeHeap * 100L >= mi.totalMem * 22L) ||
            nativeHeap >= 1536L * 1024 * 1024
        tightAvail || tightNative
    } catch (_: Throwable) {
        false
    }
}

/** 严重低内存告警时提示用户（每个绘画会话限弹一次，避免打断作画） */
internal fun PaintViewModel.triggerLowMemoryWarningIfNeeded() {
    mainHandler.post {
        if (currentPage == Page.PAINTING && !lowMemoryWarnedInSession && !isBlockingLoading && !showLowMemoryDialog) {
            lowMemoryWarnedInSession = true
            lowMemoryMessage = getString(R.string.dialog_low_memory_desc)
            showLowMemoryDialog = true
            Breadcrumbs.record("Memory", "Low memory warning dialog triggered on PAINTING")
        }
    }
}

/** 用户在低内存弹窗点击「释放缓存并保存」 */
internal fun PaintViewModel.releaseCachesAndSave() {
    showLowMemoryDialog = false
    releaseReclaimableCaches(aggressive = true)
    realInkSink?.trimMemory()
    System.gc()
    val name = docName.ifBlank {
        if (LanguageManager.isChinese()) "未命名作品" else "Untitled Artwork"
    }
    saveProject(name)
}
