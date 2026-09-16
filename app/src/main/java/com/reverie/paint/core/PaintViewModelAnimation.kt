/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.reverie.paint.R

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

    /** 是否循环播放 (true: 循环播放, false: 单次播放) */
    var loopPlayback by mutableStateOf(true)

    /**
     * 时间轴上的选中轨道 (图层索引)。-1 表示跟随当前图层。
     * 动画里"轨道"就是图层, 不引入第二套层级。
     */
    var selectedTrack by mutableIntStateOf(-1)

    /** 时间轴上多选的帧号 */
    var selectedFrames by mutableStateOf<Set<Int>>(emptySet())

    /** 是否开启多选模式 */
    var isMultiSelectMode by mutableStateOf(false)

    /**
     * 时间轴视图: 每帧像素宽 (缩放)。
     * 默认 160px (即 UI 侧的最小帧宽): 帧格要容纳缩略图, 太小不可辨。
     * UI 侧限幅 160..400 (见 AnimationTimelinePanel)。
     */
    var frameWidthPx by mutableFloatStateOf(160f)

    var scrollPx by mutableFloatStateOf(0f)

    /**
     * 时间轴视图: 每轨道行高 (像素)。
     * 注: 轨道区的**实际**行高由帧宽与画布比例反推并限幅 (见 AnimationTimelinePanel),
     * 本值只作为"轨高"滑杆的持久化镜像与折叠区显示值, 不参与布局计算,
     * 以保证帧块恒为画布比例。
     */
    var trackHeightPx by mutableFloatStateOf(48f)

    /** 帧块内是否绘制缩略图 (对标参考插件的"预览缩略图开关") */
    var showThumbnails by mutableStateOf(true)

    /**
     * 工具栏"更多"折叠区是否展开 (帧宽 / 轨高 / 播放速度 / 掉帧)。
     * 默认收起, 让轨道区拿到更多纵向空间。
     */
    var toolbarExpanded by mutableStateOf(false)

    /** 时间轴轨道区的可见宽度 (px), UI 侧测量后写入, 供缩略图按需渲染用 */
    var viewportWidthPx by mutableFloatStateOf(0f)

    /** 播放速度倍率 (0.25x ~ 4.0x) */
    var playbackSpeed by mutableFloatStateOf(1f)

    /** 播放时掉帧 (只画关键帧位置, 跳过中间帧) */
    var dropFrames by mutableStateOf(false)

    /**
     * 帧缩略图: (图层索引, 帧号) -> 已渲染的 Bitmap。
     * 引擎侧按同样的 (图层, 帧号) 做二级缓存, 这里只持有已拷出的位图,
     * 由 thumbGen 变化整体失效。
     */
    var frameThumbs by mutableStateOf<Map<Long, Bitmap>>(emptyMap())

    /**
     * [frameThumbs] 对应的 ImageBitmap 包装缓存。
     *
     * 为什么需要: `Bitmap.asImageBitmap()` **每次调用都新建一个
     * AndroidImageBitmap 包装对象**。时间轴的绘制循环里每个帧槽都要 drawImage,
     * 手势期间每帧重画 —— 于是每秒产生成百上千个短命对象, 直接喂给 GC。
     * 这里按位图实例做一次包装并复用 (位图本身由 [frameThumbs] 持有,
     * 同一代的位图实例不变, 所以包装可以安全重用)。
     */
    var frameThumbImages by mutableStateOf<Map<Long, ImageBitmap>>(emptyMap())

    /** [frameThumbImages] 里每个包装对应的位图实例, 用于判断能否复用包装 */
    var frameThumbBitmapsForImages by mutableStateOf<Map<Long, Bitmap>>(emptyMap())

    /**
     * 帧缩略图代际 (来自引擎 keyframeThumbGen), 变化即整体重取
     */
    var thumbGen by mutableLongStateOf(0L)

    /**
     * animationAddKeyframe 的一次性落点 (-1 = 未新增)。
     *
     * 渲染线程写、主线程读; 用 @Volatile 保证可见性, 且刻意不做成 Compose state
     * (它只在一次调用的首尾被读写, 不应触发重组)。
     */
    @Volatile
    var pendingAddedFrame: Int = -1

    /**
     * 缩略图刷新触发器。笔画落笔 / 图层内容变化时自增 (而 revision 只在
     * 关键帧结构变化时自增), 让时间轴知道该重画帧块里的画面。
     */
    var thumbRevision by mutableIntStateOf(0)

    /** 正在拖动中的帧块 (图层索引, 起始帧号), 用于绘制拖拽高亮 */
    var draggingKeyframe by mutableStateOf<Pair<Int, Int>?>(null)

    /** 洋葱皮开关与前后帧数 */
    var onionSkin by mutableStateOf(false)

    var onionPrev by mutableIntStateOf(1)

    var onionNext by mutableIntStateOf(1)

    /** 最近帧不透明度上限 (0~255), 再远的帧线性衰减 */
    var onionOpacity by mutableIntStateOf(160)

    /** 着色强度 (0~100), 0 = 不着色 */
    var onionTint by mutableIntStateOf(30)

    /**
     * 过去帧 / 未来帧的着色色板 (ARGB)。用颜色区分时间方向, 这是动画作画的
     * 核心信息 —— 单色洋葱皮分不清"哪边是之前画的"。
     * 默认沿用 Krita 桌面习惯: 红=过去, 绿=未来。
     */
    var onionColorBackward by mutableStateOf(0xFFE0555A.toInt())

    var onionColorForward by mutableStateOf(0xFF4E9E6A.toInt())

    /** 导入的音频资源名列表 (随 .revp assets 持久化, 播放动画时同步播放) */
    var audioAssets by mutableStateOf<List<String>>(emptyList())

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
    // 洋葱皮开关与音频资源: 与引擎/文档保持一致 (打开动画项目后还原保存时的状态)
    anim.onionSkin = ReverieCoreBridge.anyLayerOnionSkin()
    // 色板/强度也要还原: 它们存在 KisImageConfig (随工程持久化), 不回读的话
    // UI 会显示默认值而引擎用的是别的值, 用户一拖滑块就"跳色"
    val onionCfg = ReverieCoreBridge.onionSkinConfig()
    if (onionCfg.size >= 3) {
        anim.onionColorBackward = onionCfg[0]
        anim.onionColorForward = onionCfg[1]
        // Krita 侧是 0~255, UI 是百分比
        anim.onionTint = (onionCfg[2] * 100 + 127) / 255
    }
    anim.audioAssets = ReverieCoreBridge.revAssetNames().toList()
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
            if (!anim.isMultiSelectMode) {
                anim.selectedFrames = emptySet()
            }
        },
    ) {
        ReverieCoreBridge.setAnimationCurrentTime(t, recordUndo)
        // 切帧时丢弃洋葱皮缓存。
        //
        // 为什么是这里而不是落笔时: 洋葱皮缓存存的是"当前帧看到的邻帧叠影"。
        // 在 A 帧落笔只改了 A, 当前视图的洋葱皮(来自邻帧)没变, 落笔时丢缓存
        // 等于白白重合成一遍 —— 那是逐帧作画里最贵的一步。真正需要重算的时刻
        // 是"视线移到另一帧", 因为那一帧的洋葱皮里才包含刚被改过的 A。
        //
        // 播放时跳过: 播放态下不需要洋葱皮, 而这里每帧都会被调用, 不跳会把
        // 播放拖到掉帧 (曾经踩过)。C++ 侧只 reset 缓存不重算 extent。
        if (!anim.isPlaying) ReverieCoreBridge.flushOnionSkinCaches()
    }
}

/** 上一帧 / 下一帧 (跳过无内容的帧位置, 落在最近的关键帧上) */
internal fun PaintViewModel.animationStepFrame(delta: Int) {
    if (delta == 0) return
    runCore(
        after = { syncAnimationFromNativeAfter() },
    ) {
        // 图层解析必须在渲染线程完成: selectedTrackIndex() 会回退到引擎查
        // 当前图层, 在 UI 线程读引擎违反铁律 2, 且并发下可能拿到过期索引
        val layer = selectedTrackIndex()
        if (layer < 0) return@runCore
        val cur = ReverieCoreBridge.animationCurrentTime()
        val target =
            if (delta > 0) {
                ReverieCoreBridge.nextKeyframeTime(layer, cur).takeIf { it >= 0 } ?: (cur + 1)
            } else {
                ReverieCoreBridge.previousKeyframeTime(layer, cur).takeIf { it >= 0 } ?: (cur - 1)
            }
        ReverieCoreBridge.setAnimationCurrentTime(target.coerceAtLeast(0), false)
        // 与 animationSeek 同理: 视线换帧了, 该帧的洋葱皮要重算
        ReverieCoreBridge.flushOnionSkinCaches()
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

// ============================================================
// 洋葱皮
// ============================================================

/** 洋葱皮: 任何参数变化都经此统一入口下发引擎 (全局配置 + 逐图层开关)。 */
internal fun PaintViewModel.animationApplyOnionSkin() {
    runCore(after = {}) {
        ReverieCoreBridge.configureOnionSkin(
            anim.onionSkin, anim.onionPrev, anim.onionNext,
            anim.onionOpacity, anim.onionTint,
            anim.onionColorBackward, anim.onionColorForward,
        )
    }
}

// ============================================================
// 导入: 图像序列帧 / 视频 / 音频
// ============================================================

/** 当前帧起插入图像序列帧的目标轨道 (选中轨道, 回退当前图层); 渲染线程内调用 */
private fun PaintViewModel.importTargetLayer(): Int = selectedTrackIndex()

/**
 * 导入图像作为关键帧序列: 从当前帧起依次插入当前轨道。
 * 解码在 IO 线程, 写入投递到 render 线程; 采用信号量背压限制在途未写入帧, 防止多大图瞬间 OOM。
 * 全部完成后回调 onDone。
 */
internal fun PaintViewModel.animationImportImages(
    uris: List<android.net.Uri>,
    onDone: (Int) -> Unit = {},
) {
    if (uris.isEmpty()) return
    if (isImportingMedia) {
        showActionToast("正在导入媒体，请稍候...", R.drawable.ic_image)
        return
    }
    isImportingMedia = true
    val targetDim = maxOf(docWidth, docHeight, 2048)
    val semaphore = java.util.concurrent.Semaphore(2) // 限制在途未写入帧最多 2 张，防 OOM 内存背压

    CoroutineScope(Dispatchers.IO).launch {
        val inserted = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = java.util.concurrent.atomic.AtomicInteger(1) // 末位为"解码完成"标记
        var time = anim.currentTime
        try {
            for (uri in uris) {
                semaphore.acquire()
                val bmp = decodeSampledBitmapFromUri(uri, targetDim)
                if (bmp == null) {
                    semaphore.release()
                    continue
                }
                val t = time++
                pending.incrementAndGet()
                runCore(after = {
                    bmp.recycle()
                    semaphore.release()
                    if (pending.decrementAndGet() == 0) {
                        isImportingMedia = false
                        mainHandler.post { onDone(inserted.get()) }
                    }
                }) {
                    if (ReverieCoreBridge.importKeyframeFromBitmap(importTargetLayer(), t, bmp)) {
                        inserted.incrementAndGet()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("RP_Import", "images import failed", t)
        } finally {
            if (pending.decrementAndGet() == 0) {
                isImportingMedia = false
                mainHandler.post { onDone(inserted.get()) }
            }
        }
    }
}

/** 导入视频: 按文档帧率抽帧 (上限 300 帧) 作为关键帧序列插入当前轨道。
 *  抽帧与引擎写入重叠进行并施加信号量背压与降采样, 全部完成后回调 onDone。 */
internal fun PaintViewModel.animationImportVideo(
    uri: android.net.Uri,
    fps: Int,
    onDone: (Int) -> Unit = {},
) {
    if (isImportingMedia) {
        showActionToast("正在导入媒体，请稍候...", R.drawable.ic_image)
        return
    }
    isImportingMedia = true
    val context = appContext
    val targetDim = maxOf(docWidth, docHeight, 1920).coerceIn(512, 2048)
    val semaphore = java.util.concurrent.Semaphore(2) // 限制在途未写入帧最多 2 张，防 OOM

    CoroutineScope(Dispatchers.IO).launch {
        val inserted = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = java.util.concurrent.atomic.AtomicInteger(1)
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            val stepUs = 1_000_000L / fps.coerceIn(1, 60)
            val maxFrames = 300
            var frame = 0
            var time = anim.currentTime
            while (frame * stepUs / 1000 <= durationMs && frame < maxFrames) {
                semaphore.acquire()
                var rawBmp: Bitmap? = null
                try {
                    rawBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            frame * stepUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                            targetDim,
                            targetDim,
                        )
                    } else {
                        retriever.getFrameAtTime(
                            frame * stepUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                        )
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("RP_Import", "getFrameAtTime error", e)
                }

                if (rawBmp == null) {
                    semaphore.release()
                    break
                }

                // 尺寸安全兜底: 若抽出的帧仍大于 targetDim, 做缩放
                val bmp = if (rawBmp.width > targetDim || rawBmp.height > targetDim) {
                    val scale = targetDim.toFloat() / maxOf(rawBmp.width, rawBmp.height)
                    val sw = (rawBmp.width * scale).toInt().coerceAtLeast(1)
                    val sh = (rawBmp.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(rawBmp, sw, sh, true)
                    rawBmp.recycle()
                    scaled
                } else {
                    rawBmp
                }

                val t = time++
                pending.incrementAndGet()
                runCore(after = {
                    bmp.recycle()
                    semaphore.release()
                    if (pending.decrementAndGet() == 0) {
                        isImportingMedia = false
                        mainHandler.post { onDone(inserted.get()) }
                    }
                }) {
                    if (ReverieCoreBridge.importKeyframeFromBitmap(importTargetLayer(), t, bmp)) {
                        inserted.incrementAndGet()
                    }
                }
                frame++
            }
        } catch (t: Throwable) {
            android.util.Log.e("RP_Import", "video import failed", t)
        } finally {
            runCatching { retriever.release() }
            if (pending.decrementAndGet() == 0) {
                isImportingMedia = false
                mainHandler.post { onDone(inserted.get()) }
            }
        }
    }
}

/** 导入音频: 字节存入 .revp 的 assets/<name> (随保存/加载持久化)。 */
internal fun PaintViewModel.animationImportAudio(uri: android.net.Uri, name: String) {
    runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null && bytes.isNotEmpty()) {
            ReverieCoreBridge.storeRevAsset(name, bytes)
        }
    }
}

/**
 * 当前生效的轨道索引: 优先时间轴选中, 否则跟随画布当前图层。
 *
 * **只能在 reverie-render 线程调用** (内部会回退到 JNI 查当前图层)。
 * 主线程需要这个值时, 请把它写进 runCore 的 op 块, 或直接读
 * [AnimationState.selectedTrack] —— 后者是 UI 镜像, 线程安全。
 */
internal fun PaintViewModel.selectedTrackIndex(): Int {
    val selected = anim.selectedTrack
    return if (selected >= 0) selected else ReverieCoreBridge.currentLayerIndex()
}

/**
 * 画布宽高比 (宽/高)。
 *
 * 帧格按此比例绘制, 让缩略图格与画布同形 —— 画世界 Pro 的帧格是
 * "画布缩略框"而不是任意长宽比的方块。直接派生自 [coreW]/[coreH],
 * 不额外维护一份需要同步的状态。
 */
internal val PaintViewModel.canvasAspect: Float
    get() = if (coreH > 0) coreW.toFloat() / coreH.toFloat() else 1f

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
 * 当前帧已有帧时自动落到"下一个空位"(TVPaint / 参考插件里点空帧格再作画
 * 的等价效果) —— Krita 建通道时会自动补 frame 0, 所以若不做这一步,
 * 光标停在 0 时点"空白/重复"永远是空操作, 用户会以为按钮坏了。
 *
 * @param duplicate true = 复制前一帧内容 (逐帧作画最高频操作), false = 空白帧
 */
internal fun PaintViewModel.animationAddKeyframe(
    layerIndex: Int = -1,
    duplicate: Boolean = false,
) {
    runCore(
        after = {
            // 落点变化时把播放头跟过去, 让用户立刻看到新帧
            val placed = anim.pendingAddedFrame
            if (placed >= 0) anim.currentTime = placed
            syncAnimationFromNativeAfter()
        },
    ) {
        // 图层解析放在渲染线程: 见 selectedTrackIndex 的线程约束说明。
        // 这里若返回 -1 说明当前图层不可动画, 直接静默退出 (UI 侧会看到按钮无反应,
        // 但不会崩溃); 正常情况下背景层之外至少有一个可动画图层。
        val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
        if (layer < 0) return@runCore
        anim.pendingAddedFrame = -1
        if (!ReverieCoreBridge.layerAnimatable(layer)) return@runCore
        if (!ReverieCoreBridge.layerAnimated(layer)) {
            ReverieCoreBridge.enableLayerAnimation(layer)
        }
        // 从当前时间起找第一个没有关键帧的位置, 上限 512 帧防止病态循环
        var time = ReverieCoreBridge.animationCurrentTime().coerceAtLeast(0)
        var guard = 0
        while (ReverieCoreBridge.hasKeyframe(layer, time) && guard < 512) {
            time++
            guard++
        }
        val ok =
            if (duplicate) {
                ReverieCoreBridge.addDuplicateKeyframe(layer, time)
            } else {
                ReverieCoreBridge.addKeyframe(layer, time)
            }
        if (ok) {
            // 引擎播放头立刻跟到新帧 (不等 after/sync): 新帧可能落在
            // currentTime 之后 ("当前帧已有帧时自动落到下一个空位"), 引擎
            // 时间不动的话 UI 当前帧与引擎 currentTime 分叉 —— 设备的写入
            // 目标帧是 activeKeyframeAt(currentTime) 动态求值的
            // (kis_paint_device.cc Private::currentFrameId), 落笔会画进旧帧;
            // 而迟到的时间切换若落在笔画事务中途, 会触发
            // KisTransactionData::endTransaction 的"时间不得中途变更"断言,
            // 整笔丢弃 (用户看到"创建帧后第一次落笔画不上")。在这里同步切好,
            // 引擎与 UI 从此一致, 后续不会再有中途切时间。
            ReverieCoreBridge.setAnimationCurrentTime(time, false)
            anim.pendingAddedFrame = time
        }
    }
}

/**
 * 落笔前保证当前轨道在**当前帧**上有关键帧, 没有就地补一个空白帧。
 *
 * 这是"在空白区域作画自动建帧"的引擎侧实现: 时间轴里一格没有关键帧时,
 * 该格显示的是前一帧的 hold 画面 (Krita 的曝光语义), 用户在那格里下笔若
 * 不做处理, 墨迹会直接烙在**被 hold 的那一帧**上, 从而污染前面所有帧 ——
 * 这是逐帧动画里最难查的一类 bug。因此必须在笔尖落下前先把帧"分"出来。
 *
 * **必须在 reverie-render 线程调用** (内部走 JNI 查询 + selectedTrackIndex 回退)。
 * 调用方把它内联在自己的 `runCore` 块里、排在 `touchStrokeStart` **之前** ——
 * 同一次投递内顺序执行, 既保证建帧先于落笔, 又省掉额外的 handler 往返。
 *
 * 只在**当前帧确实没有关键帧**且轨道已开启动画时动作, 否则完全零开销,
 * 静态绘画 / 已有帧上作画的路径不受任何影响。
 *
 * @return true 表示本次真的新建了关键帧
 */
internal fun PaintViewModel.ensureKeyframeForPaintOnRenderThread(): Boolean {
    if (anim.isPlaying) return false
    val layer = selectedTrackIndex()
    if (layer < 0) return false
    // 轨道没开动画 = 用户没把这一层当动画层用, 不擅自开启
    if (!ReverieCoreBridge.layerAnimated(layer)) return false
    val time = ReverieCoreBridge.animationCurrentTime().coerceAtLeast(0)
    if (ReverieCoreBridge.hasKeyframe(layer, time)) return false
    if (!ReverieCoreBridge.addKeyframe(layer, time)) return false

    // 局部补缓存而不是全量重读:
    //  - keyframeCache 是时间轴绘制用的缓存, 新增一个帧号只需把该轨道这一项
    //    按序插进去 (缓存本身不要求有序, 但保持有序可让绘制少一次排序);
    //  - length / revision 也只需按已知信息推进, 无需问引擎。
    val prev = anim.keyframeCache[layer].orEmpty()
    if (time !in prev) {
        anim.keyframeCache = anim.keyframeCache + (layer to (prev + time).sorted())
    }
    if (time + 1 > anim.length) anim.length = time + 1
    anim.revision++
    anim.thumbRevision++
    return true
}

/**
 * 在 [layerIndex] 轨道的 [fromTime] 起第一个空位新建空白帧 (时间轴长按菜单)。
 *
 * 与 [animationAddKeyframe] 的区别: 时间基准是菜单指定的帧号而非引擎当前时间。
 * 同样在 runCore 内立即把引擎播放头跟到新帧 —— 设备写入目标帧是
 * activeKeyframeAt(currentTime) 动态求值的, 迟到的 seek 若落进笔画事务中途
 * 会触发 KisTransactionData::endTransaction 断言 (详见 animationAddKeyframe 注释)。
 */
internal fun PaintViewModel.animationAddBlankKeyframeAt(layerIndex: Int, fromTime: Int) {
    if (layerIndex < 0) return
    runCore(after = { syncAnimationFromNativeAfter() }) {
        if (!ReverieCoreBridge.layerAnimatable(layerIndex)) return@runCore
        if (!ReverieCoreBridge.layerAnimated(layerIndex)) {
            ReverieCoreBridge.enableLayerAnimation(layerIndex)
        }
        var time = fromTime.coerceAtLeast(0)
        var guard = 0
        while (ReverieCoreBridge.hasKeyframe(layerIndex, time) && guard < 512) {
            time++
            guard++
        }
        if (ReverieCoreBridge.addKeyframe(layerIndex, time)) {
            ReverieCoreBridge.setAnimationCurrentTime(time, false)
            anim.pendingAddedFrame = time
        }
    }
}

/**
 * 删除关键帧 (轨道至少保留一帧)。
 *
 * 默认删引擎当前帧 (底部工具栏的删除按钮); 时间轴长按菜单会显式传 [time],
 * 直接删被按住的那一帧 —— 不动播放头, 避免"删个帧画面却跳走了"的惊吓。
 */
internal fun PaintViewModel.animationRemoveKeyframe(layerIndex: Int = -1, time: Int = -1) {
    runCore(after = { syncAnimationFromNativeAfter() }) {
        val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
        if (layer < 0) return@runCore
        val t = if (time >= 0) time else ReverieCoreBridge.animationCurrentTime()
        ReverieCoreBridge.removeKeyframe(layer, t)
    }
}

/**
 * 把 [fromTime] 处的帧复制到 [toTime]; [fromTime] < 0 时用引擎当前帧。
 *
 * @param share true = 共享像素 (clone, 更省内存, 首次落笔才分叉), false = 独立副本
 */
internal fun PaintViewModel.animationCopyCurrentFrameTo(
    toTime: Int,
    layerIndex: Int = -1,
    share: Boolean = true,
    fromTime: Int = -1,
) {
    runCore(after = { syncAnimationFromNativeAfter() }) {
        val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
        if (layer < 0) return@runCore
        val from = if (fromTime >= 0) fromTime else ReverieCoreBridge.animationCurrentTime()
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
// 多选与推挤 (Ripple) 批处理
// ============================================================

/** 切换单帧的多选选中状态 */
internal fun PaintViewModel.animationToggleFrameSelection(time: Int) {
    val cur = anim.selectedFrames
    anim.selectedFrames = if (cur.contains(time)) cur - time else cur + time
}

/** 全选当前轨道的所有关键帧 */
internal fun PaintViewModel.animationSelectAllFrames(layerIndex: Int = selectedTrackIndex()) {
    val times = anim.keyframeCache[layerIndex].orEmpty()
    anim.selectedFrames = times.toSet()
}

/** 清空当前多选选区 */
internal fun PaintViewModel.animationClearSelectedFrames() {
    anim.selectedFrames = emptySet()
}

/** 反选当前轨道的所有关键帧 */
internal fun PaintViewModel.animationInvertSelectedFrames(layerIndex: Int = selectedTrackIndex()) {
    val all = anim.keyframeCache[layerIndex].orEmpty().toSet()
    anim.selectedFrames = all - anim.selectedFrames
}

/**
 * 事务性重排一组关键帧, 采用全局停放区两阶段搬移保证不发生自覆盖或碰撞丢失,
 * 并以 [macroName] 打包为一个单步撤销。
 */
internal fun PaintViewModel.rearrangeKeyframesMacro(
    layerIndex: Int,
    oldTimes: List<Int>,
    newTimes: List<Int>,
    macroName: String = "重排关键帧",
    after: () -> Unit = {},
) {
    if (layerIndex < 0 || oldTimes.size != newTimes.size || oldTimes.isEmpty()) return
    val moves = oldTimes.indices.filter { oldTimes[it] != newTimes[it] }
    if (moves.isEmpty()) {
        after()
        return
    }

    runCore(after = {
        syncAnimationFromNativeAfter()
        after()
    }) {
        ReverieCoreBridge.beginUndoMacro(macroName)
        try {
            val maxT = maxOf((oldTimes + newTimes).maxOrNull() ?: 0, 1000)
            val parkingBase = maxT + 10000

            // Phase 1: 将待移动帧移入停放区 (原槽位腾空)
            for (idx in moves) {
                ReverieCoreBridge.moveKeyframe(layerIndex, oldTimes[idx], parkingBase + idx)
            }
            // Phase 2: 将停放区的帧搬入新位置 (目标槽位此时均为空槽)
            for (idx in moves) {
                ReverieCoreBridge.moveKeyframe(layerIndex, parkingBase + idx, newTimes[idx])
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/**
 * 推挤调整单帧曝光时长 (Hold 长度):
 * 当前帧从 [frameTime] 起持续 [newSpan] 帧, 其后所有关键帧顺延推挤 (Ripple)。
 */
internal fun PaintViewModel.animationRippleResizeFrame(
    layerIndex: Int,
    frameTime: Int,
    newSpan: Int,
    onDone: () -> Unit = {},
) {
    if (layerIndex < 0 || newSpan < 1) {
        onDone()
        return
    }
    val times = anim.keyframeCache[layerIndex].orEmpty()
    val idx = times.indexOf(frameTime)
    if (idx < 0) {
        onDone()
        return
    }

    val curSpan = if (idx + 1 < times.size) times[idx + 1] - times[idx] else 1
    val delta = newSpan - curSpan
    if (delta == 0) {
        onDone()
        return
    }

    if (idx + 1 < times.size) {
        val newTimes = times.toMutableList()
        for (i in (idx + 1) until times.size) {
            newTimes[i] = times[i] + delta
        }
        rearrangeKeyframesMacro(layerIndex, times, newTimes, "调整帧曝光", after = onDone)
    } else {
        // 末帧曝光扩展: 在 frameTime + newSpan 处新建空白帧以闭合曝光区间
        val newEndFrame = frameTime + newSpan
        runCore(
            after = {
                syncAnimationFromNativeAfter()
                onDone()
            },
        ) {
            ReverieCoreBridge.beginUndoMacro("调整帧曝光")
            try {
                ReverieCoreBridge.addKeyframe(layerIndex, newEndFrame)
            } finally {
                ReverieCoreBridge.endUndoMacro()
            }
        }
    }
}

/**
 * 单帧拖拽推挤插入 (Ripple Insert):
 * 将 [fromTime] 处的关键帧插入到 [targetSlot] 槽位, 其余帧保持原曝光时长并向两侧动态顺延避让。
 */
internal fun PaintViewModel.animationRippleMoveFrame(
    layerIndex: Int,
    fromTime: Int,
    targetSlot: Int,
    onDone: () -> Unit = {},
) {
    if (layerIndex < 0 || targetSlot < 0) {
        onDone()
        return
    }
    val times = anim.keyframeCache[layerIndex].orEmpty()
    val fromIdx = times.indexOf(fromTime)
    if (fromIdx < 0 || times.size <= 1) {
        onDone()
        return
    }

    val reorder = com.reverie.paint.model.TimelineReorderHelper.computeReorderedTimes(
        times = times,
        fromTime = fromTime,
        targetSlot = targetSlot,
    ) ?: run {
        onDone()
        return
    }

    rearrangeKeyframesMacro(layerIndex, reorder.oldTimes, reorder.newTimes, "推挤移动帧") {
        animationSeek(reorder.landingTime)
        onDone()
    }
}

/** 批量左右平移选中的关键帧 */
internal fun PaintViewModel.animationBatchShiftSelected(
    layerIndex: Int,
    delta: Int,
) {
    val selected = anim.selectedFrames
    if (layerIndex < 0 || delta == 0 || selected.isEmpty()) return
    val times = anim.keyframeCache[layerIndex].orEmpty()
    if (times.isEmpty()) return

    val minSel = selected.minOrNull() ?: 0
    if (minSel + delta < 0) return

    val newTimes = times.toMutableList()
    if (delta > 0) {
        for (i in times.indices.reversed()) {
            if (selected.contains(times[i])) {
                newTimes[i] = times[i] + delta
            }
        }
        for (i in 1 until newTimes.size) {
            if (newTimes[i] <= newTimes[i - 1]) {
                newTimes[i] = newTimes[i - 1] + 1
            }
        }
    } else {
        for (i in times.indices) {
            if (selected.contains(times[i])) {
                newTimes[i] = (times[i] + delta).coerceAtLeast(0)
            }
        }
        for (i in newTimes.indices.reversed()) {
            if (i > 0 && newTimes[i - 1] >= newTimes[i]) {
                newTimes[i - 1] = (newTimes[i] - 1).coerceAtLeast(0)
            }
        }
        for (i in 1 until newTimes.size) {
            if (newTimes[i] <= newTimes[i - 1]) {
                newTimes[i] = newTimes[i - 1] + 1
            }
        }
    }

    val updatedSelected = selected.mapNotNull { oldT ->
        val idx = times.indexOf(oldT)
        if (idx in newTimes.indices) newTimes[idx] else null
    }.toSet()

    rearrangeKeyframesMacro(layerIndex, times, newTimes, "批量平移帧") {
        anim.selectedFrames = updatedSelected
    }
}

/** 批量删除选中的关键帧 */
internal fun PaintViewModel.animationBatchDeleteSelected(
    layerIndex: Int,
) {
    val selected = anim.selectedFrames
    if (layerIndex < 0 || selected.isEmpty()) return

    runCore(after = {
        anim.selectedFrames = emptySet()
        syncAnimationFromNativeAfter()
    }) {
        ReverieCoreBridge.beginUndoMacro("批量删除关键帧")
        try {
            for (t in selected.sortedDescending()) {
                ReverieCoreBridge.removeKeyframe(layerIndex, t)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
    }
}

/** 批量复制选中的关键帧 (紧接在其后追加并推挤后续帧) */
internal fun PaintViewModel.animationBatchDuplicateSelected(
    layerIndex: Int,
) {
    val selected = anim.selectedFrames.sorted()
    if (layerIndex < 0 || selected.isEmpty()) return
    val times = anim.keyframeCache[layerIndex].orEmpty()
    if (times.isEmpty()) return

    val lastSelected = selected.last()
    val count = selected.size
    val lastSelectedIdx = times.indexOf(lastSelected)
    val spanAfter = if (lastSelectedIdx >= 0 && lastSelectedIdx + 1 < times.size) {
        times[lastSelectedIdx + 1] - times[lastSelectedIdx]
    } else 1

    val insertBase = lastSelected + spanAfter
    val totalSpan = (selected.last() - selected.first() + 1).coerceAtLeast(count)

    runCore(after = {
        syncAnimationFromNativeAfter()
        val newSelected = (0 until count).map { insertBase + (selected[it] - selected.first()) }.toSet()
        anim.selectedFrames = newSelected
    }) {
        ReverieCoreBridge.beginUndoMacro("批量复制关键帧")
        try {
            // 先将 insertBase 之后的所有原关键帧向后推挤 totalSpan 格
            for (t in times.filter { it >= insertBase }.reversed()) {
                ReverieCoreBridge.moveKeyframe(layerIndex, t, t + totalSpan)
            }
            // 逐个复制选中的帧
            for (t in selected) {
                val offset = t - selected.first()
                ReverieCoreBridge.copyKeyframe(layerIndex, t, insertBase + offset)
            }
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
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
    val start = playbackStartFrame()
    val end = playbackEndFrame()
    if (start >= end) {
        showActionToast("当前仅有 1 帧", R.drawable.ic_repeat_none)
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
    // 恢复洋葱皮: 与引擎同线程串行, 排在已入队的播放步进之后 (FIFO 保证
    // 不会出现"恢复后仍有一帧播放渲染"的乱序)。
    renderHandler?.post { ReverieCoreBridge.setOnionSkinSuppressed(false) }
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
            mp.isLooping = true
            mp.prepare()
            mp.start()
            animAudioPlayers.add(mp)
        }
    }
}

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
    showActionToast(
        if (anim.loopPlayback) "循环播放" else "单次播放",
        if (anim.loopPlayback) R.drawable.ic_repeat_loop else R.drawable.ic_repeat_none,
    )
}

/** 播放时跳回起点并停止 */
internal fun PaintViewModel.animationStop() {
    animationPause()
    animationSeek(playbackStartFrame())
}

/** 起始帧: 取所有轨道上已有关键帧的最小帧号 (无帧块处不播放) */
private fun PaintViewModel.playbackStartFrame(): Int {
    val allTimes = anim.keyframeCache.values.flatten()
    return allTimes.minOrNull() ?: 0
}

/** 结束帧: 取所有轨道上已有关键帧的最大帧号 (无帧块处不播放) */
private fun PaintViewModel.playbackEndFrame(): Int {
    val allTimes = anim.keyframeCache.values.flatten()
    return allTimes.maxOrNull() ?: maxOf(0, anim.length - 1)
}

/** 单步播放。运行在 reverie-render 线程上 (由 animationPlay 投递)。 */
private fun PaintViewModel.animationStep(gen: Int) {
    if (gen != anim.playGen || !anim.isPlaying) return

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
    scheduleRender(immediate = true)

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
internal fun PaintViewModel.syncAnimationFromNativeAfter() {
    renderHandler?.post { syncAnimationFromNative() }
}

// ============================================================
// 帧缩略图: 时间轴帧块内的画面预览
// ============================================================

/**
 * 帧缩略图位图尺寸 (px), **7:5** 与帧格固定比例一致 —— 引擎侧
 * KeepAspectRatio 后正好铺满位图, UI 侧按位图比例画进帧格不变形。
 * 单张 RGBA8888 约 80KB, 512 张上限 ≈ 41MB, 仍在可控范围。
 */
internal const val FRAME_THUMB_W = 168
internal const val FRAME_THUMB_H = 120

/** 帧缩略图缓存上限 (张)。超出时丢弃最旧的, 避免长时间作画后 OOM。 */
private const val MAX_FRAME_THUMBS = 512

/** (图层, 帧号) -> 缓存键 */
internal fun frameThumbKey(
    layerIndex: Int,
    time: Int,
): Long = (layerIndex.toLong() shl 32) or (time.toLong() and 0xFFFFFFFFL)

/**
 * 把时间轴当前可见的帧渲染成缩略图。
 *
 * 在 reverie-render 线程上批量完成 (一次投递渲染多帧, 避免逐帧跨线程往返);
 * 渲染出的 Bitmap 通过 mainHandler 交回 UI 状态。引擎侧另有按 (图层, 帧号)
 * 的二级缓存, 所以重复请求同一帧几乎零成本。
 *
 * 代际号 [AnimationState.thumbGen] 与引擎 keyframeThumbGen() 对齐: 代际变化
 * (笔画落笔 / 关键帧增删改) 时既有的位图全部作废, 需要重取; 代际未变时
 * 屏幕外的旧位图保留, 这样就地平移不会反复重渲染。
 */
internal fun PaintViewModel.refreshFrameThumbs() {
    if (!anim.enabled || !anim.showThumbnails) return
    // 调用频率是重点排查对象: 手势期间如果这个函数每秒被调上百次, 说明
    // 视口参数又漏进了某个 effect 的 key (2026-09-15 踩过这个坑)
    PerfTrace.tick("thumbs.refreshCalls", 1000L)

    val cache = anim.keyframeCache
    // 关键帧缓存还没建立 (面板刚打开 / 首次同步尚未落地) 时,
    // 先催一次引擎同步再退出。LaunchedEffect 会在 revision 自增后重跑。
    if (cache.isEmpty()) {
        renderHandler?.post { syncAnimationFromNative() }
        return
    }

    val fw = anim.frameWidthPx
    if (fw <= 0f) return
    val scroll = anim.scrollPx
    val viewportPx = anim.viewportWidthPx

    // 可见帧范围多留一屏余量, 小幅平移不必重新渲染。
    // 网格视图里帧格是无间隙等距排列 (节距 = frameW, 左缘锚点), 与
    // AnimationTimelinePanel 的绘制公式保持一致, 否则首尾会少取帧。
    val step = fw
    val margin = viewportPx * 0.5f
    val firstFrame = ((scroll - margin) / step).toInt().coerceAtLeast(0)
    val lastFrame = ((scroll + viewportPx + margin) / step).toInt().coerceAtLeast(firstFrame)

    // 引擎代际在渲染线程读一次, 决定既有位图能否复用
    val currentGen = anim.thumbGen
    val existing = anim.frameThumbs

    // 收集可见范围内的所有 (图层, 帧); 是否需要真正渲染留到渲染线程按代际判断
    val visible = ArrayList<Pair<Int, Int>>()
    for ((layerIndex, times) in cache) {
        for (t in times) {
            if (t < firstFrame || t > lastFrame) continue
            visible.add(layerIndex to t)
        }
    }
    if (visible.isEmpty()) return

    runCore(after = {}) {
        val gen = ReverieCoreBridge.keyframeThumbGen()
        val sameGen = gen == currentGen
        // 取走"精准失效"脏帧 (落笔 / 帧增删改 / 复制)。这些高频路径不再
        // bump 全局代际, 所以代际一致时除了脏帧之外全部照常复用 ——
        // 旧实现里每抬一笔就把所有图层所有帧的缩略图全部重渲染一遍。
        val dirtyArr = ReverieCoreBridge.takeDirtyKeyframeThumbs()
        val dirty = HashSet<Long>(dirtyArr.size / 2)
        var di = 0
        while (di + 1 < dirtyArr.size) {
            dirty.add(frameThumbKey(dirtyArr[di], dirtyArr[di + 1]))
            di += 2
        }
        val merged = HashMap<Long, Bitmap>(existing)
        var rendered = 0
        var failed = 0
        // 脏帧内容已变: 先把 UI 侧旧位图剔掉。在可见范围内的脏帧由下面的
        // 循环重渲染; 不在范围内的 (用户滚走了) 也要保证滚回来时不命中
        // stale 旧图 —— 引擎缓存条目已被 dirtyKeyframeThumb 删除, 这里
        // 丢掉 UI 侧副本后, 下次进入视口自然走渲染。
        if (dirty.isNotEmpty()) merged.keys.removeAll(dirty)
        for ((layerIndex, time) in visible) {
            val key = frameThumbKey(layerIndex, time)
            // 代际一致且不是脏帧时沿用旧位图, 只在内容变了以后重画
            if (sameGen && key !in dirty && merged[key]?.isRecycled == false) continue
            val bmp = Bitmap.createBitmap(FRAME_THUMB_W, FRAME_THUMB_H, Bitmap.Config.ARGB_8888)
            if (ReverieCoreBridge.renderKeyframeThumb(layerIndex, time, bmp)) {
                merged[key] = bmp
                rendered++
            } else {
                failed++
                bmp.recycle()
            }
        }
        // 代际未变、没有新渲染、也没有脏帧剔留: 无需回写, 避免无谓重组
        // (dirty 非空时 merged 已被修改, 必须回写让 UI 侧丢掉旧位图)
        if (sameGen && rendered == 0 && dirty.isEmpty()) return@runCore

        val snapshot =
            if (merged.size <= MAX_FRAME_THUMBS) {
                merged
            } else {
                // 超限时只保留当前视图范围内的, 丢弃平移过去的旧帧。
                // **必须 recycle 被丢弃的位图**: ARGB_8888 的 168x120 一张约
                // 80KB, 不回收的话平移浏览长动画会持续吃掉几十 MB 堆,
                // 直到 GC 介入才回收 —— 表现为"用一会儿就卡一下"。
                // 回收前排掉还在新快照里被引用的那些 (同一 key 的位图可能同时
                // 出现在 merged 和 keep 里)。
                val keep = HashMap<Long, Bitmap>()
                for ((layerIndex, time) in visible) {
                    val k = frameThumbKey(layerIndex, time)
                    merged[k]?.let { keep[k] = it }
                }
                for ((k, bmp) in merged) {
                    if (!keep.containsKey(k) && !bmp.isRecycled) {
                        bmp.recycle()
                    }
                }
                keep
            }
        // 位图 -> ImageBitmap 包装在这里一次性建好。
        //
        // 为什么不在绘制里现包: `Bitmap.asImageBitmap()` 每次调用都新建一个
        // AndroidImageBitmap 包装对象, 而绘制循环每个帧槽都要 drawImage、
        // 手势期间还每帧重画 —— 等于每秒产生成百上千个短命对象喂给 GC。
        //
        // 复用判据必须是**位图实例同一** (用 identity 比较), 只看 key 会错:
        // 同一 (图层, 帧号) 重新渲染后会换成新 Bitmap 实例, 沿用旧包装就会
        // 画到那张已废弃的旧图上。
        val prevByKey = anim.frameThumbImages
        val prevBitmaps = anim.frameThumbBitmapsForImages
        val images = HashMap<Long, ImageBitmap>(snapshot.size)
        val bitmapsForImages = HashMap<Long, Bitmap>(snapshot.size)
        for ((k, bmp) in snapshot) {
            val cached = prevByKey[k]
            val sameBitmap = prevBitmaps[k] === bmp
            images[k] = if (cached != null && sameBitmap) cached else bmp.asImageBitmap()
            bitmapsForImages[k] = bmp
        }
        mainHandler.post {
            anim.thumbGen = gen
            anim.frameThumbs = snapshot
            anim.frameThumbImages = images
            anim.frameThumbBitmapsForImages = bitmapsForImages
        }
    }
}
