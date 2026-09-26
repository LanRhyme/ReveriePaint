/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.SystemClock
import android.util.Log
import java.util.Arrays

/**
 * 轻量性能打点。用于定位"交互发涩 / 掉帧"这类无法靠读代码确定的问题。
 *
 * 设计要点:
 *  - **默认关闭**: [enabled] 为 false 时所有函数体只剩一次布尔判断, 热路径
 *    可以放心埋点, 不埋点就永远不知道瓶颈在哪、也永远不敢确定修好了没有;
 *  - 用 tag `ReveriePerf` 统一过滤: `adb logcat -s ReveriePerf`;
 *  - [span] 只在超过 [slowMs] 时打印, 避免刷屏掩盖真正的问题;
 *  - [rate] 用来量"每秒被调用多少次" —— 很多性能问题不是单次慢, 而是
 *    单次不慢但被调用了上千次 (例如手势期间每帧重启一个 effect)。
 *
 * 打开方式(三者任一):
 *  - 设置页 → 通用 → "性能标尺" 开关 (写入 `paint_prefs`, 重启后保持);
 *  - `adb shell setprop debug.reverie.perf 1` (见 [refreshFromSystemProp]);
 *  - 临时把 [enabled] 改成 true 出包。
 *
 * ## 分桶指标 (2026-09, 为"纹理重传 / 保存耗时"决策提供标尺)
 *
 * 打开后屏幕左上角会出现 HUD, 同时每秒往 logcat 打一行窗口摘要。指标定义:
 *  - `path full/incr/skip`: 引擎渲染路径分桶。`skip` = 引擎判定无脏区、返回 false
 *    (最省的一档); `full` = 缓冲重分配或有强制全量标记;
 *  - `flip`: 显示缓冲交换(翻转)次数。**每翻转一次, 下次绘制 HWUI 就要把整张
 *    Bitmap 纹理重传一次** —— 这是本项目最大的带宽开销, 故直接以
 *    "翻转次数 × 缓冲字节" 作为纹理重传的代理量(实测上限, 因为 HWUI 不做局部更新);
 *  - `脏比`: 本窗口写入的脏区像素 / 缓冲总像素。**这个数字就是 tile 化缓冲的收益上限**:
 *    脏比 5% 意味着分块上传最多能把每帧带宽降到 1/20;
 *  - `draw p95`: `CanvasTouchView.onDraw` 的耗时 p95 (UI 侧绘制命令录制, 不含 GPU 上传);
 *  - `save`: 上一次保存的 C++ 阶段耗时(快照/编码/写盘)与产物体积, 见 `revpSaveStats`;
 *  - `液化`: 上一次液化 apply 的四段拆解 —— `形变`(Krita 网格 run) / `补洞`(透明像素补洞的
 *    内存流量) / `回写`(bitBlt + setDirty) / `合成`(脏区标记 + 投影同步合成), 见 `liquifyStats`。
 *    **这一行是给"液化大笔刷卡顿"定位用的标尺**: 哪一段占大头, 决定下一步是优化网格形变、
 *    优化内存流量, 还是把"回写 + 合成"整段从交互态里剥离(见 docs/RENDER-OPTIMIZATION.md §9)。
 */
object PerfTrace {

    /**
     * 开关。**默认关闭** —— 打开时每次 [span]/[tick] 都要取时间并查一次 HashMap,
     * 而 [tick] 每秒还会写若干条 `Log.d` (系统调用 + 文件写入), 120Hz 高刷下不是白送。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * 是否由系统属性 (`debug.reverie.perf`) 打开。设置页关闭标尺时不影响它 ——
     * 现场量测(adb 可用)与日常开关(设置页)是两条独立开关, 任一为真即为开。
     */
    @Volatile
    var isEnabledByProp: Boolean = false

    /** 是否叠加"液化网格"可视化 (`setprop debug.reverie.lqgrid 1`; 只有 debug 构建会用到) */
    @Volatile
    var gridOverlayByProp: Boolean = false

    /** 慢操作阈值 (ms), 超过才打日志 */
    var slowMs: Long = 4L

    // ------------------------------------------------------------------
    // 渲染路径分桶 (固定数组 + @Synchronized, 热路径零分配)
    // ------------------------------------------------------------------
    const val PATH_FULL = 0
    const val PATH_INCR = 1
    const val PATH_SKIP = 2
    private const val PATH_N = 3
    private val PATH_NAMES = arrayOf("full", "incr", "skip")

    private val pathCount = LongArray(PATH_N)
    private val pathNanos = LongArray(PATH_N)
    private val pathPixels = LongArray(PATH_N)

    private var flipCount = 0L          // 缓冲翻转次数 (= 整张纹理重传次数的下限)
    private var flipBytes = 0L          // 翻转 × 缓冲字节
    private var scaledCount = 0L        // 走缩放回退路径的次数
    private var dirtyPixels = 0L        // 写入脏区像素
    private var bufferPixels = 0L       // 缓冲像素 (用于算脏比)

    // draw 耗时 p95: 环形缓冲 + 排序副本, 两者都是固定数组 ⇒ 零分配
    private const val RING = 256
    private val drawRing = LongArray(RING)
    private val drawSort = LongArray(RING)
    private var drawIdx = 0
    private var drawN = 0

    // 上一次保存 (C++ 侧阶段耗时, 单位 ms; 由 revpSaveStats 填入)
    private var saveTotalMs = -1L
    private var saveSnapshotMs = -1L
    private var saveEncodeMs = -1L
    private var saveWriteMs = -1L
    private var savePngCount = -1L
    private var savePngBytes = -1L
    private var saveFileBytes = -1L
    private var saveWasAsync = false

    // 上一次液化 apply 的分段耗时 (由 liquifyStats 填入; lqCount 用于判断是否有新数据)
    private var lqCount = -1L
    private var lqTotalMs = -1L
    private var lqWarpMs = 0L
    private var lqSeedMs = 0L
    private var lqBlitMs = 0L
    private var lqCompositeMs = 0L
    private var lqAreaPx = 0L
    private var lqTargets = 0L
    private var lqPrecision = 0L
    private var lqCells = 0L

    // 液化网格快照的摘要 (由 liquifyGrid 填入; 见 PerfHud 的网格可视化)
    private var gridCols = 0
    private var gridRows = 0
    private var gridPrec = 0
    private var gridCount = 0
    private var gridMax = 0f
    private var gridMean = 0f

    private var windowStart = 0L
    private var hudCache = ""
    private var hudCacheMs = 0L

    fun log(msg: String) {
        if (!enabled) return
        Log.d("ReveriePerf", msg)
    }

    /**
     * 量一段同步代码的耗时, 超过 [threshold] ms 才打印。
     *
     * 用法: `PerfTrace.span("renderThumb") { doWork() }`
     *
     * 用 `inline` + `crossinline` 让 lambda 内联, 避免每次调用分配一个
     * lambda 对象 —— 这本来就是要在热路径上用的工具, 自己不该再制造开销。
     */
    inline fun <T> span(name: String, threshold: Long = slowMs, block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = block()
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
        if (ms >= threshold) {
            Log.d("ReveriePerf", "$name ${"%.2f".format(ms)}ms")
        }
        return r
    }

    /**
     * 频率计数器: 按 [windowMs] 聚合, 每个窗口结束打印一次"本窗口调用次数"。
     * 专治"单次很快但被高频调用"的问题 —— 这类问题看单次耗时永远查不出来。
     */
    private val counters = HashMap<String, LongArray>() // name -> [windowStart, count, totalNs]

    fun tick(name: String, windowMs: Long = 1000L) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val slot = counters.getOrPut(name) { longArrayOf(now, 0L, 0L) }
        slot[1]++
        if (now - slot[0] >= windowMs) {
            val n = slot[1]
            val avgUs = if (n > 0) slot[2] / n / 1000.0 else 0.0
            Log.d("ReveriePerf", "$name ${n} 次/${now - slot[0]}ms (平均 ${"%.2f".format(avgUs)}us)")
            slot[0] = now
            slot[1] = 0L
            slot[2] = 0L
        }
    }

    /** [tick] 的计时版: 额外累计耗时, 输出平均单次耗时 */
    fun tickNanos(name: String, nanos: Long, windowMs: Long = 1000L) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val slot = counters.getOrPut(name) { longArrayOf(now, 0L, 0L) }
        slot[1]++
        slot[2] += nanos
        if (now - slot[0] >= windowMs) {
            val n = slot[1]
            val avgUs = if (n > 0) slot[2] / n / 1000.0 else 0.0
            Log.d("ReveriePerf", "$name ${n} 次/${now - slot[0]}ms (平均 ${"%.2f".format(avgUs)}us)")
            slot[0] = now
            slot[1] = 0L
            slot[2] = 0L
        }
    }

    // ------------------------------------------------------------------
    // 分桶记录 API (调用方只需在热路径上调用, 关闭时每次一次布尔判断)
    // ------------------------------------------------------------------

    /** 记录一帧引擎渲染落在哪条路径上。[pixels] 为本次实际写入的像素数(0 表示无写入)。 */
    @Synchronized
    fun renderPath(path: Int, nanos: Long, pixels: Long) {
        if (!enabled) return
        if (path < 0 || path >= PATH_N) return
        pathCount[path]++
        pathNanos[path] += nanos
        if (pixels > 0) {
            pathPixels[path] += pixels
            dirtyPixels += pixels
        }
        rollWindowLocked()
    }

    /** 缓冲区尺寸与文档不一致 (走了缩放回退路径) */
    @Synchronized
    fun renderScaled() {
        if (!enabled) return
        scaledCount++
    }

    /**
     * 显示缓冲翻转 (= 下一帧 HWUI 会整张纹理重传)。
     * [bytes] 为缓冲字节数, [bufferPixelsFrame] 用于算脏比的分母。
     */
    @Synchronized
    fun renderFlip(bytes: Long, bufferPixelsFrame: Long) {
        if (!enabled) return
        flipCount++
        flipBytes += bytes
        if (bufferPixelsFrame > 0L) bufferPixels = bufferPixelsFrame
        rollWindowLocked()
    }

    /** CanvasTouchView.onDraw 的耗时 (UI 线程) */
    @Synchronized
    fun drawFrame(nanos: Long) {
        if (!enabled) return
        drawRing[drawIdx] = nanos
        drawIdx = (drawIdx + 1) % RING
        if (drawN < RING) drawN++
        rollWindowLocked()
    }

    /**
     * 记录一次 UI 线程 onDraw 的时刻, 统计"真实帧间隔"(相邻两帧的时间差)。
     * 与 [drawFrame](单帧绘制耗时) 不同: 帧间隔能暴露"每帧都很快, 但上屏被拖慢"的 pacing 问题。
     */
    @Synchronized
    fun frameTick() {
        if (!enabled) return
        val now = System.nanoTime()
        val prev = lastFrameNs
        lastFrameNs = now
        if (prev == 0L) return
        val dt = now - prev
        // 忽略首帧与长停顿(>1s): 它们不是"帧节奏", 会把 p95 拉歪
        if (dt <= 0L || dt > 1_000_000_000L) return
        frameIntervalLastMs = dt / 1_000_000.0
        frameRing[frameIdx] = dt
        frameIdx = (frameIdx + 1) % RING
        if (frameN < RING) frameN++
    }

    /** C++ 侧回报的上一次保存阶段耗时 (见 `revpSaveStats`) */
    @Synchronized
    fun saveStats(
        totalMs: Long,
        snapshotMs: Long,
        encodeMs: Long,
        writeMs: Long,
        pngCount: Long,
        pngBytes: Long,
        fileBytes: Long,
        wasAsync: Boolean,
    ) {
        if (!enabled) return
        saveTotalMs = totalMs
        saveSnapshotMs = snapshotMs
        saveEncodeMs = encodeMs
        saveWriteMs = writeMs
        savePngCount = pngCount
        savePngBytes = pngBytes
        saveFileBytes = fileBytes
        saveWasAsync = wasAsync
        hudCacheMs = 0L
    }

    // Phase 3A: 交互调度计数(每秒窗口) —— 输入事件 / 推进次数 / 补点总数, 用于判断合并倍率
    private var lqScheduleInput = 0L
    private var lqScheduleFlush = 0L
    private var lqScheduleDab = 0L
    private var lqApplyPerWindow = 0L
    private var lqApplyCountLast = 0L
    // Phase 3 埋点 (docs/LIQUIFY-REBASE-INVESTIGATION.md §8): rebase / 节流两条物化边的分离读数。
    // 引擎侧上报的是**累计量**, 这里缓存上次值取窗口增量(首次只建基线, 不把历史算进本窗口)。
    private var lqRebaseSeen = false
    private var lqRebaseCountLast = 0L
    private var lqRebaseFlushMsLast = 0L
    private var lqRebaseCloneMsLast = 0L
    private var lqThrottleCountLast = 0L
    private var lqThrottleMsLast = 0L
    // 窗口内累计
    private var lqRebasePerWindow = 0L
    private var lqRebaseFlushMsWin = 0L
    private var lqRebaseCloneMsWin = 0L
    private var lqThrottlePerWindow = 0L
    private var lqThrottleMsWin = 0L
    // 最近一次的构成 / 全局峰值(不随窗口清零, 便于长时间观察尖峰)
    private var lqRebaseReason = 0L
    private var lqRebaseOverflowPx = 0L
    private var lqRebaseNewAreaPx = 0L
    private var lqRebaseGridPoints = 0L
    private var lqRebaseFlushMaxMs = 0L
    private var lqThrottleMaxMs = 0L
    // Phase 3 · Commit 1b: 拖动热路径的单位成本 —— 一次 liquify() 调用的次数 / 累计 µs / 峰值 µs。
    // 这是判断"拖动是否卡在原生形变"的直接读数(liquifyStats 的"形变 52ms"是单次 apply 的拆分,
    // 拖动期间通常不触发 apply)。
    private var lqCallCountLast = 0L
    private var lqCallUsLast = 0L
    private var lqCallPerWindow = 0L
    private var lqCallUsWin = 0L
    private var lqCallMaxUs = 0L
    // Phase 3 · Commit 1b: UI 线程覆盖层"提交 + 绘制"的实际耗时(commitLocked + drawRect)。
    // draw p95 只含绘制命令录制, 不含纹理上传与 GPU, 所以需要这一项才能判断覆盖层贵不贵。
    private var lqOverlayCount = 0L
    private var lqOverlayUsWin = 0L
    private var lqOverlayMaxUs = 0L
    /** 上一次 apply 的采样时刻: 用于给 HUD 第 4 行标注"这条拆分是多久以前的"。 */
    private var lqLastApplyAtMs = 0L
    // Phase 3B: 交互态"滞后"即时值 + 窗口内的峰值 backlog。
    // backlog = 上一帧推进后仍未提交的补点数(真实"还差多少"); 若它随操作时间持续增大,
    // 就说明 latest-state-wins 没能消灭长期积压 —— 这是 200px/高速/长时间压力测试的核心读数。
    private var lqFlowLag = 0L
    private var lqFlowBacklog = 0
    private var lqFlowBacklogMax = 0
    private var lqUploadCount = 0L
    // 实验 A: 预览源纹理(代理)尺寸与占用, 用于 Proxy Resolution 对照。
    private var lqProxyW = 0
    private var lqProxyH = 0
    private var lqProxyBytes = 0L
    // 干预实验(§4.15): 预览"暂存 / 上传"计数(gauge, 每次手势内累计)
    private var lqPreviewUpdates = 0L
    private var lqGridUploads = 0L
    // Phase 5 · C2: 本次手势"由谁画预览"(见 liquifyHost)、GLES 覆盖层状态、GLES 首帧快照。
    // 没有数据线时可以只凭 HUD 截图判读"开关生效了没 / 坐标系与仿射口径对不对"。
    private var lqHostTag = HOST_ENGINE
    private var lqGlesState = ""
    private var lqGlesSnapshot = ""
    // 干预实验(§4.16): 物化(Krita apply)耗时累计 —— 本窗口内的 总耗时 / 峰值耗时。
    private var lqMatTotalMs = 0L
    private var lqMatMaxMs = 0L
    // 帧间隔(UI 线程相邻两次 onDraw 的时间差): 找 frame spike。
    // 不与窗口一起清零 —— 保持一个滚动窗口的 p95, 便于长时间观察。
    private val frameRing = LongArray(RING)
    private val frameSort = LongArray(RING)
    private var frameIdx = 0
    private var frameN = 0
    private var lastFrameNs = 0L
    private var frameIntervalLastMs = 0.0

    /**
     * 记录一次液化"推进"(Phase 2C latest-state-wins 的每帧一次):
     * [inputs] = 距上次推进累积的输入事件数, [dabs] = 本次推进实际提交的补点数。
     * 三者之比就是合并倍率: 1 次推进 = 1 帧, 补点数 ≤ 每帧上限。
     */
    /**
     * 应用内覆盖(debug 设置页): latest-state-wins 每帧最多推进的补点数。
     * -1 = 不覆盖(跟随 property/构建期档位); 0 = 关闭合并; >0 = 指定步数。
     * 没有数据线时靠它切换(见 CanvasTouchView 的手势起始判定)。
     */
    @Volatile
    var liquifyCoalesceOverride: Int = -1

    /** Phase 5 · C2: [liquifyHost] 的三个取值 —— 引擎侧 CPU 叠加 / AGSL 覆盖层 / GLES 覆盖层。 */
    const val HOST_ENGINE = 0
    const val HOST_AGSL = 1
    const val HOST_GLES = 2
    private val HOST_NAMES = arrayOf("引擎", "AGSL", "GLES")

    @Synchronized
    fun liquifySchedule(inputs: Int, dabs: Int) {
        lqScheduleInput += inputs
        lqScheduleFlush++
        lqScheduleDab += dabs
    }

    /**
     * Phase 3B: 记录一次交互态的即时滞后指标 (gauge, 不累加):
     * [lag] = 距上次推进累积的输入事件数, [backlogDabs] = 上一帧推进后仍未提交的补点数。
     * 每帧都会调用, 因此 **不** 置 `hudCacheMs = 0`(否则 HUD 会每帧重建); HUD 在其
     * 自身的 250ms 刷新节奏里读到最新 gauge 即可。
     */
    @Synchronized
    fun liquifyFlow(lag: Long, backlogDabs: Int) {
        if (!enabled) return
        lqFlowLag = lag
        lqFlowBacklog = backlogDabs
        if (backlogDabs > lqFlowBacklogMax) lqFlowBacklogMax = backlogDabs
    }

    /**
     * Phase 3B: 记录本次手势累计的源纹理上传次数 (gauge, 正常应恒为 1)。
     * > 1 说明高速拖动期间发生了多余的 rebase 重传, 是需要优先排查的问题。
     */
    @Synchronized
    fun liquifyUpload(count: Long) {
        if (!enabled) return
        lqUploadCount = count
    }

    /**
     * 实验 A: 预览源纹理(代理)尺寸与占用 (gauge) —— 跑 100/75/50/25 对照时,
     * 这一行直接告诉你"这一档实际把源纹理压到了多少"。
     */
    @Synchronized
    fun liquifyProxy(width: Int, height: Int, bytes: Long) {
        if (!enabled) return
        lqProxyW = width
        lqProxyH = height
        lqProxyBytes = bytes
    }

    /**
     * 干预实验(§4.15): 预览管线的"暂存 → 上传"计数 (gauge, 每次手势内累计)。
     * [previewUpdates] = 引擎暂存的预览状态更新次数; [gridUploads] = 实际构建/上传网格纹理的次数。
     * 目标: `gridUploads ≤ 帧数`, 即"上传 ≤ 1/帧"。
     */
    @Synchronized
    fun liquifyPipeline(previewUpdates: Long, gridUploads: Long) {
        if (!enabled) return
        lqPreviewUpdates = previewUpdates
        lqGridUploads = gridUploads
    }

    /**
     * Phase 5 · C2: 本次手势**实际在画预览**的那条路 ([HOST_ENGINE] / [HOST_AGSL] / [HOST_GLES])。
     *
     * 没有数据线时, 这一格是判断"设置里的预览方式到底生效了没"的唯一读数 —— 选了 GLES 却显示
     * AGSL/引擎, 说明 GLES 侧没就绪(见 [liquifyGlesState])。
     */
    @Synchronized
    fun liquifyHost(mode: Int) {
        if (!enabled) return
        if (mode != lqHostTag) {
            lqHostTag = mode
            hudCacheMs = 0L
        }
    }

    /** GLES 覆盖层的状态短语(就绪 / 失败原因 / 已卸载), 由 [com.reverie.paint.ui.painting.canvas.LiquifyGlesOverlay] 上报。 */
    @Synchronized
    fun liquifyGlesState(text: String?) {
        if (!enabled) return
        val t = text ?: ""
        if (t != lqGlesState) {
            lqGlesState = t
            hudCacheMs = 0L
        }
    }

    /** GLES 首帧喂给 shader 的全套 uniform 快照(每段手势一次), 直接附在 HUD 上供截图判读。 */
    @Synchronized
    fun liquifyGlesSnapshot(text: String?) {
        if (!enabled) return
        val t = text ?: ""
        if (t != lqGlesSnapshot) {
            lqGlesSnapshot = t
            hudCacheMs = 0L
        }
    }

    /**
     * 引擎侧回报的上一次液化 apply 的分段耗时 (见 `liquifyStats`)。
     * 四段之和约等于 total: warp(Krita 网格形变) / seed(补洞内存流量) / blit(回写图层) /
     * composite(脏区标记 + 投影同步合成)。同一次 apply 重复调用会被忽略。
     */
    @Synchronized
    fun liquifyApply(
        totalMs: Long,
        warpMs: Long,
        seedMs: Long,
        blitMs: Long,
        compositeMs: Long,
        areaPx: Long,
        targets: Long,
        applyCount: Long,
        precision: Long,
        cells: Long,
    ) {
        if (!enabled) return
        if (applyCount == lqCount) return
        val delta = (applyCount - lqApplyCountLast).coerceAtLeast(0L)
        lqCount = applyCount
        // 本窗口发生了多少次"物化"(apply): 预览模式下引擎只在 rebase 与抬笔时 apply,
        // 因此这个增量就是 **拖动中 rebase 的次数** —— 它决定下一步是优化 rebase 还是收工。
        lqApplyPerWindow += delta
        lqApplyCountLast = applyCount
        // 干预实验(§4.16): 物化耗时累计。采样点已改为"每渲染一次"(引擎线程), 因此每次检测到
        // 新的 apply 就把引擎上报的"上一次 apply 总耗时"累进来, 并取 max 抓尖峰。
        // 注意: 若两次采样之间发生了多次 apply, total 会低估(只记最后一次); max 仍是所采到的峰值。
        if (delta > 0L) {
            lqMatTotalMs += totalMs
            if (totalMs > lqMatMaxMs) lqMatMaxMs = totalMs
            // 记下采样时刻: HUD 第 4 行是"上一次 apply"的拆分, 必须能读出它的新鲜度
            lqLastApplyAtMs = SystemClock.elapsedRealtime()
        }
        lqTotalMs = totalMs
        lqWarpMs = warpMs
        lqSeedMs = seedMs
        lqBlitMs = blitMs
        lqCompositeMs = compositeMs
        lqAreaPx = areaPx
        lqTargets = targets
        lqPrecision = precision
        lqCells = cells
        hudCacheMs = 0L
    }

    /**
     * Phase 3 埋点 (docs/LIQUIFY-REBASE-INVESTIGATION.md §8): 引擎侧 rebase / 节流读数。
     *
     * 入参是 [ReverieCoreBridge.liquifyRebaseStats] 的 12 元数组:
     * `[rebaseCount, reason, flushMs, flushMaxMs, cloneMs, oldAreaPx, newAreaPx,
     *   innerOverflowPx, gridPoints, throttleCount, throttleMs, throttleMaxMs]`。
     *
     * 判读:`rebase/flushMs` 高 ⇒ 拖动中的物化确实来自 rebase(下一步做路线 B+C);
     * `rebase/cloneMs` 高 ⇒ 该治的是 src/dst 与 worker 的重建(§4.6.4);
     * `原因=越内框` 且 `越界 ~ 240px` ⇒ 证实调查 §3 的量化。
     */
    @Synchronized
    fun liquifyRebase(s: LongArray) {
        if (!enabled || s.size < 15) return
        if (!lqRebaseSeen) {
            // 首次取数只建立基线: 否则会把"标尺开启前"的历史累计全算进这一窗口
            lqRebaseSeen = true
            lqRebaseCountLast = s[0]
            lqRebaseFlushMsLast = s[2]
            lqRebaseCloneMsLast = s[4]
            lqThrottleCountLast = s[9]
            lqThrottleMsLast = s[10]
            lqCallCountLast = s[12]
            lqCallUsLast = s[13]
        } else {
            lqRebasePerWindow += (s[0] - lqRebaseCountLast).coerceAtLeast(0L)
            lqRebaseFlushMsWin += (s[2] - lqRebaseFlushMsLast).coerceAtLeast(0L)
            lqRebaseCloneMsWin += (s[4] - lqRebaseCloneMsLast).coerceAtLeast(0L)
            lqThrottlePerWindow += (s[9] - lqThrottleCountLast).coerceAtLeast(0L)
            lqThrottleMsWin += (s[10] - lqThrottleMsLast).coerceAtLeast(0L)
            lqCallPerWindow += (s[12] - lqCallCountLast).coerceAtLeast(0L)
            lqCallUsWin += (s[13] - lqCallUsLast).coerceAtLeast(0L)
            lqRebaseCountLast = s[0]
            lqRebaseFlushMsLast = s[2]
            lqRebaseCloneMsLast = s[4]
            lqThrottleCountLast = s[9]
            lqThrottleMsLast = s[10]
            lqCallCountLast = s[12]
            lqCallUsLast = s[13]
        }
        lqRebaseReason = s[1]
        lqRebaseOverflowPx = s[7]
        lqRebaseNewAreaPx = s[6]
        lqRebaseGridPoints = s[8]
        if (s[3] > lqRebaseFlushMaxMs) lqRebaseFlushMaxMs = s[3]
        if (s[11] > lqThrottleMaxMs) lqThrottleMaxMs = s[11]
        if (s[14] > lqCallMaxUs) lqCallMaxUs = s[14]
        hudCacheMs = 0L
    }

    /**
     * Phase 3 · Commit 1b 埋点: UI 线程上一次"覆盖层提交 + 绘制"的耗时(纳秒)。
     *
     * 与 `draw p95` 的区别: 后者只统计绘制命令录制, **不含**纹理构建/上传与 GPU 采样;
     * 覆盖层每帧要重建一张位移纹理(Bitmap + BitmapShader), 这一项才看得见那部分开销。
     */
    @Synchronized
    fun liquifyOverlay(ns: Long) {
        if (!enabled) return
        val us = ns / 1000L
        lqOverlayCount++
        lqOverlayUsWin += us
        if (us > lqOverlayMaxUs) lqOverlayMaxUs = us
    }

    // ------------------------------------------------------------------
    /**
     * 引擎侧回报的液化网格快照摘要 (见 `liquifyGrid`) —— 只喂标尺: HUD 第 5 行显示网格规模、
     * 精度与位移量级, 用来判断"网格几何 / 位移方向"是否符合预期(实现 Preview 前的自检)。
     */
    @Synchronized
    fun liquifyGrid(columns: Int, rows: Int, precision: Int, count: Int, data: FloatArray) {
        if (!enabled) return
        var maxAbs = 0f
        var sum = 0.0
        var i = 8
        while (i + 3 < 8 + count * 4 && i + 3 < data.size) {
            val dx = data[i + 2]
            val dy = data[i + 3]
            val d = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (d > maxAbs) maxAbs = d
            sum += d.toDouble()
            i += 4
        }
        gridCols = columns
        gridRows = rows
        gridPrec = precision
        gridCount = count
        gridMax = maxAbs
        gridMean = if (count > 0) (sum / count).toFloat() else 0f
        hudCacheMs = 0L
    }

    // 窗口汇总 / 输出
    // ------------------------------------------------------------------

    private fun rollWindowLocked() {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) {
            windowStart = now
            return
        }
        val span = now - windowStart
        if (span < 1000L) return
        dumpLocked(now, span)
    }

    private fun dumpLocked(now: Long, spanMs: Long) {
        val sb = StringBuilder(192)
        sb.append("win ").append(spanMs).append("ms | ")
        for (i in 0 until PATH_N) {
            if (pathCount[i] == 0L) continue
            sb.append(PATH_NAMES[i]).append(' ').append(pathCount[i])
                .append("(").append(pathNanos[i] / pathCount[i] / 1_000_000).append("ms) ")
        }
        if (scaledCount > 0L) sb.append("| scaled ").append(scaledCount).append(' ')
        val dirtyPct = if (bufferPixels > 0L) dirtyPixels * 100.0 / bufferPixels else 0.0
        sb.append("| flip ").append(flipCount).append(' ')
        if (flipCount > 0L) {
            val perFrameMb = flipBytes / flipCount / 1048576.0
            val perSecMb = flipBytes / (spanMs / 1000.0) / 1048576.0
            sb.append("重传 ").append("%.1f".format(perFrameMb)).append("MB/帧 ")
                .append("%.0f".format(perSecMb)).append("MB/s ")
        }
        sb.append("脏比 ").append("%.1f".format(dirtyPct)).append("% ")
        if (drawN > 0) {
            sb.append("| draw p95 ").append("%.2f".format(p95Locked(drawRing, drawSort, drawN) / 1e6))
                .append("ms avg ").append("%.2f".format(avgLocked(drawRing, drawN) / 1e6)).append("ms")
        }
        Log.d("ReveriePerf", sb.toString())

        resetWindowLocked(now)
    }

    private fun resetWindowLocked(now: Long) {
        for (i in 0 until PATH_N) {
            pathCount[i] = 0L
            pathNanos[i] = 0L
            pathPixels[i] = 0L
        }
        flipCount = 0L
        flipBytes = 0L
        scaledCount = 0L
        dirtyPixels = 0L
        drawIdx = 0
        drawN = 0
        // 调度计数与其它窗口量一起清零: HUD 显示的就是"当前这一秒"的合并倍率
        lqScheduleInput = 0L
        lqScheduleFlush = 0L
        lqScheduleDab = 0L
        lqApplyPerWindow = 0L
        lqRebasePerWindow = 0L
        lqRebaseFlushMsWin = 0L
        lqRebaseCloneMsWin = 0L
        lqThrottlePerWindow = 0L
        lqThrottleMsWin = 0L
        lqCallPerWindow = 0L
        lqCallUsWin = 0L
        lqOverlayCount = 0L
        lqOverlayUsWin = 0L
        lqFlowBacklogMax = 0
        lqMatTotalMs = 0L
        lqMatMaxMs = 0L
        windowStart = now
    }

    private fun avgLocked(a: LongArray, n: Int): Double {
        if (n <= 0) return 0.0
        var s = 0L
        for (i in 0 until n) s += a[i]
        return s.toDouble() / n
    }

    private fun p95Locked(src: LongArray, scratch: LongArray, n: Int): Double {
        if (n <= 0) return 0.0
        System.arraycopy(src, 0, scratch, 0, n)
        Arrays.sort(scratch, 0, n)
        return scratch[((n - 1) * 95 / 100).coerceIn(0, n - 1)].toDouble()
    }

    /**
     * HUD 文本 (屏幕左上角)。最多每 250ms 重建一次, 避免每帧拼字符串。
     * 返回空串表示暂无数据。
     */
    /** Phase 3 埋点: rebase 原因码 → 可读名(与 C++ 的 LiquifyRebaseReason 一一对应)。 */
    private val REBASE_REASON_NAMES = arrayOf("无", "首dab", "越内框")

    @Synchronized
    fun hudText(): String {
        if (!enabled) return ""
        val now = SystemClock.elapsedRealtime()
        if (now - hudCacheMs < 250L && hudCache.isNotEmpty()) return hudCache
        hudCacheMs = now

        val span = if (windowStart == 0L) 0L else (now - windowStart).coerceAtLeast(1L)
        val sb = StringBuilder(160)
        // 第 1 行: 渲染路径分布 + 脏比
        sb.append("render ")
        for (i in 0 until PATH_N) {
            if (i > 0) sb.append('/')
            sb.append(PATH_NAMES[i]).append(' ').append(pathCount[i])
        }
        val dirtyPct = if (bufferPixels > 0L) dirtyPixels * 100.0 / bufferPixels else 0.0
        sb.append("  脏比 ").append("%.1f".format(dirtyPct)).append('%')
        if (scaledCount > 0L) sb.append("  scaled ").append(scaledCount)
        sb.append('\n')

        // 第 2 行: 纹理重传代理 + draw 耗时。
        // 窗口不足 500ms 时不报 fps —— 否则"窗口刚重置 + 1 次翻转"会被折算成 100+/s 的
        // 伪读数 (真机实测见过 flip 140/s 这种不可能的数字, 会误导判断)。
        val fps = if (span >= 500L) flipCount * 1000.0 / span else -1.0
        sb.append("flip ").append(if (fps < 0) "--" else "%.1f".format(fps)).append("/s")
        if (flipCount > 0L) {
            sb.append("  重传 ").append("%.1f".format(flipBytes / flipCount / 1048576.0)).append("MB/帧")
        }
        if (drawN > 0) {
            sb.append("  draw p95 ").append("%.2f".format(p95Locked(drawRing, drawSort, drawN) / 1e6)).append("ms")
        }
        sb.append('\n')

        // 第 3 行: 上一次保存
        sb.append("save ")
        if (saveTotalMs < 0) {
            sb.append("--")
        } else {
            sb.append(saveTotalMs).append("ms 快照 ").append(saveSnapshotMs)
                .append("/编码 ").append(saveEncodeMs).append("/写盘 ").append(saveWriteMs)
            sb.append(' ').append(savePngCount).append("PNG ")
                .append("%.0f".format(savePngBytes / 1048576.0)).append("MB→")
                .append("%.0f".format(saveFileBytes / 1048576.0)).append("MB")
            if (saveWasAsync) sb.append(" async")
        }

        // 第 4 行: 上一次液化 apply 的四段拆解 (做过液化才有)
        if (lqCount >= 0L) {
            sb.append('\n')
            sb.append("液化 ").append(lqTotalMs).append("ms 形变 ").append(lqWarpMs)
                .append("/补洞 ").append(lqSeedMs).append("/回写 ").append(lqBlitMs)
                .append("/合成 ").append(lqCompositeMs).append("  ").append(lqTargets)
                .append("层 ").append("%.0f".format(lqAreaPx / 1024.0)).append("K px")
                .append(" 精度").append(lqPrecision).append("/单元").append(lqCells)
            // 新鲜度标注: 本行是"上一次 apply"的拆分, 而 AGSL 预览模式下拖动期间通常**不触发**
            // apply(读数里表现为"物化 0")。不标新鲜度就极易把抬笔那一次的 52ms 误读成拖动成本。
            if (lqLastApplyAtMs > 0L) {
                val agoMs = (SystemClock.elapsedRealtime() - lqLastApplyAtMs).coerceAtLeast(0L)
                sb.append(" (上次").append("%.1f".format(agoMs / 1000.0)).append("s前)")
            }
        }

        // 第 4.5 行: 液化交互管线的"暂存 → 上传 → 帧"三段读数。
        //   输入/推进/补点 = 交互调度(上一秒窗口);
        //   暂存/网格上传 = 状态暂存与实际纹理上传(每次手势内累计; 目标 网格上传 ≤ 1/帧);
        //   源上传 = rebase 次数; 滞后 = 当前未提交补点数; 代理 = 源纹理尺寸。
        if (lqScheduleFlush > 0L || lqPreviewUpdates > 0L) {
            sb.append('\n')
            sb.append("泵 输入").append(lqScheduleInput)
                .append("/推进").append(lqScheduleFlush)
                .append("/补点").append(lqScheduleDab)
            // 物化: 次数 / 本窗口累计耗时 / 单次峰值 —— 回答"lag=0 但仍卡"是否来自 apply 尖峰(§4.16)
            sb.append(" 物化").append(lqApplyPerWindow)
                .append('/').append(lqMatTotalMs).append("ms max").append(lqMatMaxMs).append("ms")
            // Phase 3 埋点(§8): 把 rebase 与节流**分开**读 —— 前者才是拖动中物化的真来源
            if (lqRebaseSeen) {
                sb.append(" rebase").append(lqRebasePerWindow)
                    .append('/').append(lqRebaseFlushMsWin).append("ms")
                    .append(" max").append(lqRebaseFlushMaxMs).append("ms")
                    .append(" 重建").append(lqRebaseCloneMsWin).append("ms")
                    .append(" 因").append(REBASE_REASON_NAMES[lqRebaseReason.toInt().coerceIn(0, 2)])
                    .append(" 越界").append(lqRebaseOverflowPx).append("px")
                sb.append(" 节流").append(lqThrottlePerWindow)
                    .append('/').append(lqThrottleMsWin).append("ms")
                // Phase 3 · Commit 1b: 拖动热路径的**单位成本**(每次 liquify() 调用)
                sb.append(" 调用").append(lqCallPerWindow)
                    .append('/').append("%.0f".format(lqCallUsWin / 1000.0)).append("ms")
                    .append(" max").append("%.1f".format(lqCallMaxUs / 1000.0)).append("ms")
                // 覆盖层的真实开销(含纹理构建/上传; draw p95 看不到这部分)
                if (lqOverlayCount > 0L) {
                    sb.append(" 覆盖层")
                        .append("%.2f".format(lqOverlayUsWin / 1000.0 / lqOverlayCount))
                        .append("ms max").append("%.2f".format(lqOverlayMaxUs / 1000.0)).append("ms")
                }
            }
            sb.append(" 暂存").append(lqPreviewUpdates)
                .append("/网格上传").append(lqGridUploads)
                .append("/源上传").append(lqUploadCount)
                .append(" 滞后").append(lqFlowBacklog).append("峰").append(lqFlowBacklogMax)
                .append(" lag").append(lqFlowLag)
                .append(" 代理").append(lqProxyW).append('x').append(lqProxyH)
                // Phase 5 · C2: 预览由谁画 + GLES 侧状态(无数据线时判断"开关生效了没"的读数)
                .append(" 预览").append(HOST_NAMES[lqHostTag.coerceIn(0, 2)])
                .append(if (lqGlesState.isEmpty()) "" else "($lqGlesState)")
        }

        // 第 4.55 行: GLES 首帧 uniform 快照(仅 GLES 路径在画时显示) —— 坐标系/仿射/网格口径
        // 全在这一行, 出问题(镜像/偏移)时截图即可判读, 不必连数据线抓 logcat。
        if (lqHostTag == HOST_GLES && lqGlesSnapshot.isNotEmpty()) {
            sb.append('\n').append(lqGlesSnapshot)
        }

        // 第 4.6 行: 真实帧间隔(相邻两帧 onDraw 的时间差) —— 判断"上传控制住了但仍卡"的关键。
        if (frameN > 0) {
            sb.append('\n')
            sb.append("frame ").append("%.1f".format(frameIntervalLastMs))
                .append("ms p95 ").append("%.1f".format(p95Locked(frameRing, frameSort, frameN) / 1e6))
                .append("ms n").append(frameN)
        }

        // 第 5 行: 液化网格快照 (setprop debug.reverie.lqgrid 1 时才有)
        if (gridCount > 0) {
            sb.append('\n')
            sb.append("网格 ").append(gridCols).append('x').append(gridRows)
                .append(" 精度").append(gridPrec)
                .append(" Δmax ").append("%.1f".format(gridMax))
                .append(" Δmean ").append("%.1f".format(gridMean)).append("px")
        }
        hudCache = sb.toString()
        return hudCache
    }

    /**
     * 读取引擎侧同名诊断 property 的整数值(未设 / 不可读时返回 [def])。
     * 交互类实验(如液化的 latest-state-wins 调度)用它做 A/B, 默认值保持既有行为。
     */
    internal fun debugPropInt(key: String, def: Int): Int =
        runCatching { SystemPropertiesCompat.getInt(key, def) }.getOrDefault(def)

    /** 允许用系统属性临时打开, 免去为了量一次数据重新出包 */
    fun refreshFromSystemProp() {
        isEnabledByProp = runCatching {
            SystemPropertiesCompat.getBoolean("debug.reverie.perf", false)
        }.getOrDefault(false)
        gridOverlayByProp = runCatching {
            SystemPropertiesCompat.getBoolean("debug.reverie.lqgrid", false)
        }.getOrDefault(false)
        // 只负责"打开", 不负责关闭: 设置页里的开关可能已经把它打开了
        if (isEnabledByProp) {
            enabled = true
            Log.d("ReveriePerf", "PerfTrace enabled via debug.reverie.perf")
        }
    }
}

/** 隔离 SystemProperties 的反射, 失败就当作 false (非 rooted/非 debug 设备) */
private object SystemPropertiesCompat {
    private val getBooleanMethod = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
    }.getOrNull()

    fun getBoolean(key: String, def: Boolean): Boolean {
        val m = getBooleanMethod ?: return def
        return runCatching { m.invoke(null, key, def) as? Boolean ?: def }.getOrDefault(def)
    }

    private val getStringMethod = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    }.getOrNull()

    /** 整数 property; 反射不可用或值非法时返回 [def] */
    fun getInt(key: String, def: Int): Int {
        val m = getStringMethod ?: return def
        val raw = runCatching { m.invoke(null, key, def.toString()) as? String }.getOrNull()
        return raw?.trim()?.toIntOrNull() ?: def
    }
}
