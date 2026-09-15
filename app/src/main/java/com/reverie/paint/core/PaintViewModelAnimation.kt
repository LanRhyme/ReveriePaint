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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            anim.selectedFrames = emptySet()
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
 * 解码在 IO 线程, 写入投递到 render 线程; **不等待**每张落地, 解码与导入
 * 重叠进行 (批量导入大图时快很多)。用计数器在所有写入完成后回调 onDone。
 */
internal fun PaintViewModel.animationImportImages(
    uris: List<android.net.Uri>,
    onDone: (Int) -> Unit = {},
) {
    if (uris.isEmpty()) return
    val context = appContext
    CoroutineScope(Dispatchers.IO).launch {
        val inserted = java.util.concurrent.atomic.AtomicInteger(0)
        val pending = java.util.concurrent.atomic.AtomicInteger(1) // 末位为"解码完成"标记
        var time = anim.currentTime
        for (uri in uris) {
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
            }.getOrNull() ?: continue
            val t = time++
            pending.incrementAndGet()
            runCore(after = {
                bmp.recycle()
                if (pending.decrementAndGet() == 0) {
                    mainHandler.post { onDone(inserted.get()) }
                }
            }) {
                if (ReverieCoreBridge.importKeyframeFromBitmap(importTargetLayer(), t, bmp)) {
                    inserted.incrementAndGet()
                }
            }
        }
        if (pending.decrementAndGet() == 0) {
            mainHandler.post { onDone(inserted.get()) }
        }
    }
}

/** 导入视频: 按文档帧率抽帧 (上限 300 帧) 作为关键帧序列插入当前轨道。
 *  抽帧与引擎写入重叠进行 (不等待每帧落地), 全部完成后回调 onDone。 */
internal fun PaintViewModel.animationImportVideo(
    uri: android.net.Uri,
    fps: Int,
    onDone: (Int) -> Unit = {},
) {
    val context = appContext
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
                val bmp = retriever.getFrameAtTime(
                    frame * stepUs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: break
                val t = time++
                pending.incrementAndGet()
                runCore(after = {
                    bmp.recycle()
                    if (pending.decrementAndGet() == 0) {
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
        if (ok) anim.pendingAddedFrame = time
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

/** 删除当前帧位置的关键帧 (轨道至少保留一帧) */
internal fun PaintViewModel.animationRemoveKeyframe(layerIndex: Int = -1) {
    runCore(after = { syncAnimationFromNativeAfter() }) {
        val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
        if (layer < 0) return@runCore
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
    runCore(after = { syncAnimationFromNativeAfter() }) {
        val layer = if (layerIndex >= 0) layerIndex else selectedTrackIndex()
        if (layer < 0) return@runCore
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
    startAnimAudio()
    renderHandler?.post { animationStep(gen) }
}

/** 暂停播放 (自增代际令牌使挂起的步进失效) */
internal fun PaintViewModel.animationPause() {
    if (!anim.isPlaying) return
    anim.isPlaying = false
    anim.playGen++
    stopAnimAudio()
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
        val merged = HashMap<Long, Bitmap>(existing)
        var rendered = 0
        var failed = 0
        for ((layerIndex, time) in visible) {
            val key = frameThumbKey(layerIndex, time)
            // 代际一致时沿用旧位图, 只在内容变了以后重画
            if (sameGen && merged[key]?.isRecycled == false) continue
            val bmp = Bitmap.createBitmap(FRAME_THUMB_W, FRAME_THUMB_H, Bitmap.Config.ARGB_8888)
            if (ReverieCoreBridge.renderKeyframeThumb(layerIndex, time, bmp)) {
                merged[key] = bmp
                rendered++
            } else {
                failed++
                bmp.recycle()
            }
        }
        // 代际未变又没有新渲染: 无需回写, 避免无谓重组
        if (sameGen && rendered == 0) return@runCore

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
        mainHandler.post {
            anim.thumbGen = gen
            anim.frameThumbs = snapshot
        }
    }
}
