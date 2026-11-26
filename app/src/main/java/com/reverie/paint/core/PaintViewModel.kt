/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.toCurveXml

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.core.stylus.StylusAudioType
import com.reverie.paint.model.*
import com.reverie.paint.perf.PerfHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** Max reference images allowed in reference window and album picker */
const val MAX_REFERENCE_IMAGES = 50

/** Max stroke samples buffered between render-thread drains. Sized for a
 *  240Hz stylus under heavy multi-frame stalls with generous headroom (256 samples);
 *  preallocated once, zero allocation on hot path. */
internal const val STROKE_BATCH_CAPACITY = 256

/** Delay before the stroke-start idle kick flushes a pen-down dot. */
internal const val STROKE_START_KICK_MS = 24L

/** 批内合并: 上一批「引擎 flush + 瓦片渲染」超过这个耗时 (一整帧 60Hz 预算) 才把新样本并进同一批 (ms)。 */
private const val STROKE_MERGE_TRIGGER_MS = 16L

/** 批内合并: 合并后单批样本数上限 (与引擎 appendStrokeSample 的 32 样本分段对齐)。 */
private const val STROKE_MERGE_MAX_SAMPLES = 32

internal fun java.util.concurrent.atomic.AtomicInteger.decrementPositive() {
    while (true) {
        val cur = get()
        if (cur <= 0) return
        if (compareAndSet(cur, cur - 1)) return
    }
}

/**
 * Holds the painting UI state. The actual document lives in C++ (ReverieCore).
 *
 * All document operations (painting, layers, undo, rendering) run on a
 * dedicated handler thread: Krita's projection recomposition takes ~5ms+ on
 * device, and doing it on the UI thread blocked touch delivery, which made
 * strokes appear in chunks. Rendering uses two bitmaps so the thread writing
 * pixels is never the one Compose is reading.
 */
class PaintViewModel : ViewModel() {
    var currentPage by mutableStateOf(Page.HOME)
        internal set

    var docWidth by mutableIntStateOf(1080)
    var docHeight by mutableIntStateOf(1920)
    var docDpi by mutableIntStateOf(300)
    var docName by mutableStateOf("Untitled")
    var totalStrokes by mutableIntStateOf(0)
    var initialStrokeCount by mutableIntStateOf(0)
    var isModified by mutableStateOf(false)
    var brushModifiedEpoch by mutableLongStateOf(0L)
    var elapsedSeconds by mutableLongStateOf(0L)
    var canvasCreatedTime by mutableLongStateOf(System.currentTimeMillis())

    /**
     * 创作统计的"已上报基线"：上一次把本工程的累计时长/笔画数记进每日活跃数据时的快照。
     *
     * `elapsedSeconds` / `totalStrokes` 是**工程累计值**（会随 .revp 一起持久化，重开工程后
     * 从存档值继续累加），而每日统计只该记"这一次真正新增了多少"。直接上报累计值会把
     * 整个工程的历史时长反复加进当天 —— 保存一次加一遍、退出再加一遍，一天下来能算出
     * 十几个小时（issue: 用户 "当日已画 15 小时 35 分 / 落笔 108 笔"）。
     * 打开工程时基线同步成存档值，每次上报后基线跟到最新，保证只记增量。
     */
    internal var statsReportedStrokes by mutableIntStateOf(0)
    internal var statsReportedSeconds by mutableLongStateOf(0L)
    var colorMode by mutableStateOf("RGB 8位 (sRGB)")

    // Active drawing duration tracking (1-minute idle threshold)
    internal var lastActiveTimeMs = 0L
    internal var lastTickTimeMs = 0L
    internal var activeMillisAccumulator = 0L
    internal val IDLE_THRESHOLD_MS = 60_000L
    internal var timerJob: Job? = null

    /** ElapsedRealtime of the last stroke end; autosave defers for a quiet window after it. */
    @Volatile internal var lastStrokeEndElapsedMs = 0L

    // Auto-Save & General Settings state
    var autoSaveEnabled by mutableStateOf(true)
    var autoSaveIntervalMinutes by mutableIntStateOf(5)
    var autoSaveMaxSnapshots by mutableIntStateOf(com.reverie.paint.model.AutoSaveSnapshotPolicy.DEFAULT_MAX_SNAPSHOTS)
    var autoSaveToastEnabled by mutableStateOf(true)
    var layerFlashEnabled by mutableStateOf(false)
    var strokesSinceLastAutoSave by mutableIntStateOf(0)
    var hasPendingMajorOp by mutableStateOf(false)

    // Timelapse Recording state (default enabled for seamless replay)
    var timelapseRecordingEnabled by mutableStateOf(true)
    var timelapseMinIntervalMs by mutableLongStateOf(200L)
    var timelapseMaxFrames by mutableIntStateOf(3000)
    var timelapseResolution by mutableIntStateOf(1080)
    var timelapseQuality by mutableIntStateOf(85)

    /**
     * 只改了"选中图层"、没改内容 (见 [notifyLayerChanged] 的 contentChanged)。
     *
     * `selectedLayerIndex` 会写进工程 (Kotlin 工程 JSON + 原生 `.revp` meta), 所以只切层的
     * 结果也该落盘; 但它不值得**触发**自动保存 (那是"点图层就卡一下"的来源), 也不该让退出
     * 弹"未保存"。
     *
     * 折中: 不参与 [hasUnsavedChanges] (退出不误报), 只作为"软件切后台时静默存一次"的理由
     * (见 [onAppBackgrounded]) —— 那本来就是既有的保存时机, 用户也预期此时会存。
     * 任何一次真实保存完成后清掉。
     */
    @Volatile
    internal var selectionDirty = false

    /**
     * 性能标尺 (画布左上角实时显示渲染路径/纹理重传/保存阶段耗时)。见 [PerfTrace]。
     * 与 `setprop debug.reverie.perf 1` 任一为真即为开 (后者方便现场量测, 不写偏好);
     * 设置页显式关闭会撤掉"属性强制"状态, 否则属性残留时开关点了不生效 (= 关不掉)。
     */
    var perfHudEnabled by mutableStateOf(false)

    /**
     * 标尺是否被 `setprop debug.reverie.perf 1` 强制打开 (见 [PerfTrace.isEnabledByProp])。
     *
     * 为什么在这里镜像一份: [PerfTrace.isEnabledByProp] 是普通 `@Volatile` 字段, 读它不会
     * 触发重组 —— 设置页那一行会停在"已被属性强制打开"的旧文本上。真正的热路径只看
     * [PerfTrace.enabled], 这个镜像只在启动同步与开关切换时写入。
     */
    var perfHudForcedByProp by mutableStateOf(false)

    /**
     * 实验 A: 液化预览代理分辨率百分比 (0 = 跟随 property/构建档位)。debug 设置页可改 ——
     * 没有数据线时靠它做 100/75/50/25 对照; 正式版没有该入口, 读取器恒返回 0。
     */
    var liquifyProxyPercent by mutableIntStateOf(0)

    /**
     * Phase 3B: latest-state-wins 每帧最多推进的补点数 (-1 = 跟随 property/构建档位; 0 = 关闭)。
     * debug 设置页可改; 正式版没有该入口, 读取器恒返回 -1。
     */
    var liquifyCoalesceSteps by mutableIntStateOf(-1)

    /**
     * Phase 6(稳定性 v2): 分帧物化是否仍有积压(引擎线程写, UI 线程读)。
     *
     * 真机 HUD 证据: 单次 `liquify()` 调用峰值 128.7ms(其中 rebase 同步物化 126ms) ——
     * 引擎现在把整块物化拆成 64 行行带按预算消费, UI 侧靠这个标志决定"是否继续按帧推进"
     * (见 `CanvasTouchView.flushLiquifyPending`)。队列空时恒 false, 不产生额外调度。
     */
    @Volatile
    internal var liquifyMaterializePending: Boolean = false

    /**
     * Phase 5 · C2: 液化预览**由谁画** (0 = 自动 / 1 = 引擎侧 CPU 叠加 / 2 = AGSL 覆盖层 /
     * 3 = GLES 覆盖层)。debug 设置页可改 —— 这是**没有数据线时**做 AGSL↔GLES A/B 的唯一入口
     * (正式版没有该入口, 读取器恒返回 0)。判定发生在手势开始, 改完下一段手势生效。
     */
    var liquifyHostDraw by mutableIntStateOf(LiquifyGpuPreview.HOST_OVERRIDE_AUTO)

    /**
     * Phase 5 · C3: 液化位移场的来源 (0 = 自动 / 1 = 常驻浮点场 / 2 = Krita 网格)。
     *
     * debug 设置页可改 —— **没有数据线时**做"场 vs 网格"A/B 的唯一入口(正式版没有该入口,
     * 读取器恒返回 0)。判定在**手势开始**冻结(场是从 0 逐 dab 累加的, 半路切换会让已累加的
     * 形变凭空消失), 所以改完下一段手势生效。
     */
    var liquifyField by mutableIntStateOf(LiquifyGlesPreview.FIELD_OVERRIDE_AUTO)

    // Phase 2B: 上次取到的预览版本与裁剪指纹(只在引擎线程读写, 无需加锁)
    internal var lqGpuPreviewSeq = -1L
    internal var lqGpuCropKey = Long.MIN_VALUE

    // Phase 5 · C3-2 收尾: 源像素缓冲**复用**(两块轮换)。
    // 以前每段手势都要新分配一份 `cropW*cropH*4`(4M px 文档就是 16MB), 连续压测时是持续的
    // 大对象垃圾; 复用后这块只在尺寸变化时才重建。用两块轮换是因为上一段手势的数组可能还被
    // 覆盖层暂存着(等渲染线程消费), 直接复用同一块会让那张源纹理读到新一段的像素(画面错位),
    // 轮换一块就够避开这个窗口。
    internal var lqSrcBufA: ByteArray? = null
    internal var lqSrcBufB: ByteArray? = null
    internal var lqSrcBufUseA = false

    /** Phase 5 · C2: GLES 覆盖层失败后的"一次性交回引擎 CPU 预览"标记(只在引擎线程读写)。 */
    internal var lqGlesRecovered = false

    // 无 UI 读者: 保持普通字段, 避免每次自动保存触发 Compose 快照写入
    var isAutoSaving = false
    var lastAutoSaveTimeMs by mutableLongStateOf(0L)
    var maxUndoSteps by mutableIntStateOf(50)
    var promptSaveOnExit by mutableStateOf(true)
    var renderScheduleMode by mutableStateOf(com.reverie.paint.model.RenderScheduleMode.PERFORMANCE)

    // Brush state
    /**
     * 笔刷参数的唯一所有者：下面的 brushXxx 全是它的只读派生（getter，每次读都是 brushParams 的一次状态读，
     * 没有第二份拷贝）。写只许 `brushParams = ...`；预设参数的乐观更新由 BrushStoreVm.stage 一处完成。
     */
    var brushParams by mutableStateOf(BrushParams(paintOpId = "paintbrush"))
    var brushColor by mutableStateOf("#000000")
    var colorCompareBaseline by mutableStateOf("#000000")
    var brushSecondaryColor by mutableStateOf("#ffffff")
    var brushPresets by mutableStateOf<List<BrushPresetInfo>>(emptyList())
    var isBrushPresetsLoading by mutableStateOf(false)
    var brushPresetsLoaded = false
        internal set
    var brushPresetIndex by mutableIntStateOf(-1)
    internal var presetSelectDebounceJob: kotlinx.coroutines.Job? = null
    internal var isPresetLoading: Boolean = false
    internal var queuedPresetIndex: Int? = null

    // User-defined brush groups: preset ID -> group name (按 id 存, 见 BrushIdPrefs); and the list of
    // custom group names the user created (persisted in SharedPreferences)
    var userBrushGroups by mutableStateOf<Map<String, String>>(emptyMap())
    var customBrushGroups by mutableStateOf<List<String>>(emptyList())
    /** 笔刷导入进度: Pair(当前处理数, 总数), 非导入状态时为 null */
    var brushImportProgress by mutableStateOf<Pair<Int, Int>?>(null)

    // Custom display order of presets (persisted, 笔刷 id); empty = default (sorted)
    var brushOrder by mutableStateOf<List<String>>(emptyList())
    var categoryOrder by mutableStateOf<List<String>>(emptyList())

    // 上一次使用的工具 (toggle_last_tool 快捷键用)
    var lastToolId by mutableStateOf("brush")
    /** 是否是由手写笔侧键或快捷键临时激活的吸管工具 (取色完成后自动切回上一工具) */
    var isTemporaryPicker by mutableStateOf(false)
    /** 是否长按空格键临时激活画布抓手平移模式 */
    var isSpacePanning by mutableStateOf(false)
    /** 是否长按 R 键临时激活画布旋转模式 */
    var isRotateHolding by mutableStateOf(false)
    /** 当前是否有文本输入弹窗处于编辑聚焦状态 (聚焦时跳过物理快捷键拦截) */
    var isTextInputActive by mutableStateOf(false)
    internal var textInputCounter = 0

    /** 当前是否有快捷键录制弹窗打开 (打开时跳过物理快捷键拦截，优先录制按键) */
    var isShortcutRecordingActive by mutableStateOf(false)

    // One-shot UI 命令: 视口缩放/旋转与面板开关状态在 PaintingPage 本地,
    // VM 只发命令 token, UI 侧 LaunchedEffect 消费 (见 PaintingPage)。
    // 不能 private set —— 同包扩展函数 (requestUiCommand) 需要写入
    var uiCommandTick by mutableIntStateOf(0)
    var pendingUiCommand by mutableStateOf<String?>(null)

    // Extended brush studio properties
    var brushSizeScalesWithCanvas by mutableStateOf(true)

    // 独立参数动态响应曲线配置映射表 (Size, Opacity, Flow, Softness, Rotation, Spacing, Scatter, SmudgeRate)
    val brushDynamicOptions = mutableStateMapOf<String, com.reverie.paint.model.DynamicOptionConfig>()

    // 试画板实时动态光点追踪游标输入与输出数值
    var scratchpadLiveInput by mutableFloatStateOf(-1f)
    var scratchpadLiveOutput by mutableFloatStateOf(-1f)

    internal var brushStudioInitialKppBytes: ByteArray? = null

    /** v2：进入工作台时的覆盖（null = 该预设不归 v2 管，走 [brushStudioInitialKppBytes]） */
    internal var brushStudioInitial: BrushStoreVm.StudioSnapshot? = null

    // Masking Brush (双重画笔/蒙版画笔)

    // Sensor dynamics

    // Metadata properties

    // Per-preset independent size/opacity/flow (persisted). Switching presets
    // restores that brush's own values; adjusting a slider only affects the
    // current brush.

    // Display bitmap (managed as front/back double buffer, updated in place via renderToBuffer).
    // Decoupled from Compose state: hardware Canvas draws it directly with 0 recomposition overhead.
    @Volatile
    var displayBitmap: Bitmap? = null
        internal set

    // Layer panel. A revision state forces Compose to re-read the native
    // layer getters after add/remove/select/visibility operations.
    var layerPanelOpen by mutableStateOf(false)
    var brushPanelOpen by mutableStateOf(false)
    var brushStudioOpen by mutableStateOf(false)

    // Brush panel persistence state
    var brushPanelSelectedCategory by mutableStateOf("全部")
    var brushPanelDetailIndex by mutableStateOf<Int?>(null)
    var brushCategoryScrollIndex: Int = 0
    var brushCategoryScrollOffset: Int = 0
    var brushPresetScrollIndex: Int = 0
    var brushPresetScrollOffset: Int = 0
    var categoryPresetScrollMap: Map<String, Pair<Int, Int>> = emptyMap()
    var brushPropertyScrollValue: Int = 0
    var brushPanelViewMode by mutableStateOf(BrushPanelViewMode.STROKE_ICON_NAME)
    internal val strokePreviewMutex = kotlinx.coroutines.sync.Mutex()
    internal val strokePreviewInFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()
    /** 常用 / 最近：笔刷 id（不是显示名；改名、重名都不影响）。见 [com.reverie.paint.core.brushstore.BrushIdPrefs] */
    var favoriteBrushIds by mutableStateOf<Set<String>>(emptySet())
    var recentBrushIds by mutableStateOf<List<String>>(emptyList())
    /** 按 id 的四项偏好已读入内存之前不写盘，免得空状态盖掉磁盘上的数据 */
    @Volatile internal var brushIdPrefsLoaded = false
    var settingsPanelOpen by mutableStateOf(false)
    var drawingGuidePanelOpen by mutableStateOf(false)
    var targetSettingsTab by mutableStateOf<String?>(null)
    var targetExportAnimation by mutableStateOf(false)
    var layerRevision by mutableStateOf(0)

    // 智能阴影辅助状态
    var shadingAssistDialogOpen by mutableStateOf(false)
    var shadingAssistConfig by mutableStateOf(com.reverie.paint.model.ShadingAssistConfig())
    var shadingAssistPreviewBitmap by mutableStateOf<Bitmap?>(null)
    var shadingAssistIsProcessing by mutableStateOf(false)
    internal var shadingAssistNormalField: ShadingAssistEngine.NormalField? = null
    internal var shadingAssistBaseBitmap: Bitmap? = null
    internal var shadingAssistLineBitmap: Bitmap? = null
    internal var shadingAssistRenderJob: kotlinx.coroutines.Job? = null
    internal var shadingAssistComputeJob: kotlinx.coroutines.Job? = null
    internal var shadingAssistRawDepth: ShadingAssistEngine.RawDepthData? = null

    // Stroke stabilizer working set: written every touch sample, read by no
    // composable — plain fields by design (zero allocation, zero recomposition).
    internal var smoothedStrokeX = 0f
    internal var smoothedStrokeY = 0f
    internal var smoothedStrokePressure = 0.0
    internal var lastStrokeTimeMs: Long = 0L
    internal var lastInputEventTimeMs: Long = 0L
    internal var lastStrokeX: Float = 0f
    internal var lastStrokeY: Float = 0f
    internal var lastStrokeDeltaX: Float = 0f
    internal var lastStrokeDeltaY: Float = 0f
    internal var strokeDistanceAccumulator: Float = 0f
    internal var lastDynamicColor: String = ""

    // Krita Gaussian Weighted Smoothing history buffer (zero allocation)
    internal val smoothingHistX = FloatArray(SMOOTHING_HISTORY_CAPACITY)
    internal val smoothingHistY = FloatArray(SMOOTHING_HISTORY_CAPACITY)
    internal val smoothingHistP = DoubleArray(SMOOTHING_HISTORY_CAPACITY)
    internal val smoothingHistDist = DoubleArray(SMOOTHING_HISTORY_CAPACITY)
    internal var smoothingHistCount = 0

    data class ToolBrushState(
        val presetIndex: Int = -1,
        /**
         * 预设稳定身份 (BrushIdentity.id)。#136 A / v5 S3：工具槽以 presetId 为身份存储。
         */
        val presetId: String = "",
        val category: String = "全部",
        val categoryScrollIndex: Int = 0,
        val categoryScrollOffset: Int = 0,
        val presetScrollIndex: Int = 0,
        val presetScrollOffset: Int = 0,
        // Krita saved{Brush,Eraser}{Size,Opacity} 对应物:
        // 预设名 -> [size, opacity, flow], 按 (工具 × 预设) 粒度隔离记忆,
        // 使"笔刷调大的尺寸"不会在切到橡皮擦后仍然生效。
        val paramMemory: Map<String, List<Double>> = emptyMap(),
    )

    var toolBrushStates: Map<String, ToolBrushState> = emptyMap()
        internal set

    var pinnedTools by mutableStateOf<List<com.reverie.paint.model.Tool>>(DEFAULT_PINNED_TOOLS)
        internal set

    var currentToolId by mutableStateOf("brush")
        internal set

    /** 橡皮模式 (Krita 风格): 当前笔刷不变, 仅将底层绘制模式切换为擦除 */
    var isEraserMode by mutableStateOf(false)
        internal set

    /** 当前笔画临时覆盖工具 (如物理橡皮擦末端 TOOL_TYPE_ERASER 或侧键临时擦除生效时设为 "eraser") */
    var activeStrokeToolOverride by mutableStateOf<String?>(null)
        internal set
    /** 临时橡皮擦覆盖生效前的现场（只在 touchStart 写一次、只在 restore 读；这是真正会丢的旧状态，不是镜像） */
    internal data class StrokeSaved(
        val presetIndex: Int = -1,
        val size: Double = 0.0,
        val opacity: Double = 1.0,
        val flow: Double = 1.0,
        val compositeOp: String = "normal",
        val toolMode: Int = 0,
    )
    internal var strokeSaved: StrokeSaved = StrokeSaved()
    internal var activeStrokePresetSwitched: Boolean = false

    /** 最近一次活跃的绘制类工具 (brush / eraser / smudge), 用于在临时工具 (如吸管) 切换回笔刷时保留笔刷尺寸与参数 */
    var lastDrawingToolId: String = "brush"
        internal set

    /** 是否正在执行图层复制, 防抖防止连续狂点触发多线程竞态崩溃 */
    var isCopyingLayer by mutableStateOf(false)
        internal set

    // Reference Tool Window State (常态固定显示参考窗口)
    var referenceWindowOpen by mutableStateOf(false)
    // 置中只发生在 referenceWindowOpen 的 false->true 边沿: 面板保持打开时
    // 进入工程/重新组合不重置位置。VM 级字段跨工程保留。
    var referenceWindowWasOpen = false
    var referenceImages by mutableStateOf<List<Bitmap>>(emptyList())
    var referenceAlbumSelectedUris by mutableStateOf<List<android.net.Uri>>(emptyList())
    var referenceIsGrayscale by mutableStateOf(false)
    var referenceAllowRotation by mutableStateOf(true)
    var referenceIsFlipped by mutableStateOf(false)
    var referenceActiveTab by mutableIntStateOf(0) // 0: 图片, 1: 画布
    var referenceBarsCollapsed by mutableStateOf(false)
    /** 是否正在导入媒体资源 (图片/视频/参考图), 用于防止频繁连续点击导致并发 OOM 崩溃 */
    var isImportingMedia by mutableStateOf(false)

    // Reference Window View Transforms (Pan / Zoom / Rotation)
    var referenceZoom by mutableFloatStateOf(1f)
    var referenceRotation by mutableFloatStateOf(0f)
    var referencePanX by mutableFloatStateOf(0f)
    var referencePanY by mutableFloatStateOf(0f)

    // 画布预览页 (tab 1) 使用独立的视图变换, 与参考图片页互不影响 (issue #208)
    var referenceCanvasZoom by mutableFloatStateOf(1f)
    var referenceCanvasRotation by mutableFloatStateOf(0f)
    var referenceCanvasPanX by mutableFloatStateOf(0f)
    var referenceCanvasPanY by mutableFloatStateOf(0f)

    // Reference Window Position & Size (pixels/dp)
    var referenceWindowX by mutableFloatStateOf(80f)
    var referenceWindowY by mutableFloatStateOf(140f)
    var referenceWindowWidth by mutableFloatStateOf(260f)
    var referenceWindowHeight by mutableFloatStateOf(300f)

    @Volatile internal var referenceSession: Long = 0
    @Volatile internal var referenceOperation: Long = 0
    internal var referenceImportJob: Job? = null
    internal var referenceRestoreJob: Job? = null
    internal var referenceImportActive = false
    internal var referenceLoading = false
    var referenceBitmapLoading by mutableStateOf(false)
        internal set
    internal var referenceDeferredFiles: List<java.io.File>? = null
    internal var referenceSavedState = com.reverie.paint.model.ReferenceViewState()
    @Volatile internal var referenceProfileId = java.util.UUID.randomUUID().toString()
    internal val referenceStore by lazy { LocalReferenceStore(appContext) }
    internal val referenceIoMutex = kotlinx.coroutines.sync.Mutex()
    var referenceCacheBytes by mutableLongStateOf(0L)
        internal set
    var referenceCacheClearing by mutableStateOf(false)
        internal set
    var hasLegacyReferenceImages by mutableStateOf(false)
        internal set

    // Current orientation tracked for quick windows layout
    var isLandscapeMode by mutableStateOf(true)
        internal set

    // Per-orientation persistent positions for Quick Windows
    internal var quickActionWindowX_land: Float = -1f
    internal var quickActionWindowY_land: Float = -1f
    internal var quickActionWindowX_port: Float = -1f
    internal var quickActionWindowY_port: Float = -1f

    internal var quickBrushWindowX_land: Float = -1f
    internal var quickBrushWindowY_land: Float = -1f
    internal var quickBrushOrientation_land: String = "horizontal"
    internal var quickBrushWindowX_port: Float = -1f
    internal var quickBrushWindowY_port: Float = -1f
    internal var quickBrushOrientation_port: String = "horizontal"

    internal var quickColorWindowX_land: Float = -1f
    internal var quickColorWindowY_land: Float = -1f
    internal var quickColorWindowX_port: Float = -1f
    internal var quickColorWindowY_port: Float = -1f

    internal var quickOklchWindowX_land: Float = -1f
    internal var quickOklchWindowY_land: Float = -1f
    internal var quickOklchWindowX_port: Float = -1f
    internal var quickOklchWindowY_port: Float = -1f

    internal var quickLayerWindowX_land: Float = -1f
    internal var quickLayerWindowY_land: Float = -1f
    internal var quickLayerWindowX_port: Float = -1f
    internal var quickLayerWindowY_port: Float = -1f

    internal var referenceWindowX_land: Float = -1f
    internal var referenceWindowY_land: Float = -1f
    internal var referenceWindowX_port: Float = -1f
    internal var referenceWindowY_port: Float = -1f

    // Quick Actions Tool Window State (快捷操作浮窗)
    var quickActionWindowOpen by mutableStateOf(false)
    var quickActionWindowWasOpen = false
    var quickActionsConfig by mutableStateOf(com.reverie.paint.model.QuickActionsConfig())
    var quickActionWindowX by mutableFloatStateOf(-1f)
    var quickActionWindowY by mutableFloatStateOf(-1f)
    var quickActionWindowWidth by mutableFloatStateOf(0f)
    var quickActionWindowHeight by mutableFloatStateOf(0f)
    var quickActionCollapsed by mutableStateOf(false)

    // Countdown floating tool window and session state.
    var countdownWindowOpen by mutableStateOf(false)
    var countdownWindowWasOpen = false
    var countdownWindowX by mutableFloatStateOf(-1f)
    var countdownWindowY by mutableFloatStateOf(-1f)
    var countdownWindowWidth by mutableFloatStateOf(0f)
    var countdownWindowHeight by mutableFloatStateOf(0f)
    var countdownRemainingMs by mutableLongStateOf(0L)
    var countdownDeadlineElapsedMs by mutableLongStateOf(0L)
    var countdownRunning by mutableStateOf(false)
    internal var countdownJob: Job? = null

    // Quick Brush Tool Window State (快捷笔刷浮窗)
    var quickBrushWindowOpen by mutableStateOf(false)
    var quickBrushWindowWasOpen = false
    var quickBrushWindowX by mutableFloatStateOf(-1f)
    var quickBrushWindowY by mutableFloatStateOf(-1f)
    var quickBrushWindowWidth by mutableFloatStateOf(0f)
    var quickBrushWindowHeight by mutableFloatStateOf(0f)
    var quickBrushCollapsed by mutableStateOf(false)
    var quickBrushOrientation by mutableStateOf("horizontal") // "horizontal" or "vertical"
    var quickBrushMaxLength by mutableIntStateOf(6) // 最大长度（显示数量上限）
    var quickBrushOrder by mutableStateOf<List<String>>(emptyList())

    // Pointer Screen Position Tracking (for opening floating panels at pointer)
    var lastPointerScreenPosition by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)

    // Quick Color Tool Window State (快捷颜色浮窗)
    var quickColorWindowOpen by mutableStateOf(false)

    /**
     * 快捷调色盘是否处于「临时态」：由笔手势唤起。
     *
     * 是否在落笔时收起由用户偏好 [quickColorAutoHideOnStroke] 决定 —— 很多用户习惯
     * 把面板留在画布上随时取色，所以不能默认收起。临时态不写盘，避免下次启动复活
     * 一个只该存在几秒的面板。
     */
    var quickColorEphemeral by mutableStateOf(false)

    /**
     * 快捷调色盘：是否**随身/笔尖弹出**。
     *
     * true(默认) = 每次唤出都定位到当前笔尖附近，便于就近取色；
     * false = 固定在上次拖动到的位置，便于常驻侧边随时微调。
     * 两派用户都有，所以做成偏好而不是由应用替用户选。
     */
    var quickColorFollowsStylus by mutableStateOf(true)
        internal set

    /**
     * 快捷调色盘：**落笔是否自动收起**（仅对临时态面板生效）。
     *
     * 默认关闭 —— 强行收起会打断习惯常驻微调的作画流。
     */
    var quickColorAutoHideOnStroke by mutableStateOf(false)
        internal set

    var quickColorWindowWasOpen = false
    var quickColorWindowX by mutableFloatStateOf(-1f)
    var quickColorWindowY by mutableFloatStateOf(-1f)
    var quickColorWindowWidth by mutableFloatStateOf(0f)
    var quickColorWindowHeight by mutableFloatStateOf(0f)
    var quickColorCollapsed by mutableStateOf(false)
    var quickColorTab by mutableIntStateOf(0)

    // Quick OKLCH Tool Window State (OKLCH 悬浮调色窗)
    var quickOklchWindowOpen by mutableStateOf(false)
    var quickOklchWindowWasOpen = false
    var quickOklchWindowX by mutableFloatStateOf(-1f)
    var quickOklchWindowY by mutableFloatStateOf(-1f)
    var quickOklchWindowWidth by mutableFloatStateOf(0f)
    var quickOklchWindowHeight by mutableFloatStateOf(0f)
    var quickOklchCollapsed by mutableStateOf(false)

    // Quick Layer Tool Window State (快捷图层浮窗)
    var quickLayerWindowOpen by mutableStateOf(false)
    var quickLayerWindowWasOpen = false
    var quickLayerWindowX by mutableFloatStateOf(-1f)
    var quickLayerWindowY by mutableFloatStateOf(-1f)
    var quickLayerWindowWidth by mutableFloatStateOf(0f)
    var quickLayerWindowHeight by mutableFloatStateOf(0f)
    var quickLayerCollapsed by mutableStateOf(false)
    var quickLayerPinned by mutableStateOf(true)

    /** 绘画页宿主 Box 的实测像素尺寸。0 表示尚未 layout, 方向切换时回退到传入的屏幕尺寸。 */
    internal var floatingHostWidthPx: Float = 0f
    internal var floatingHostHeightPx: Float = 0f

    /** 窗内拖把手, 相对窗根的像素矩形。不是 Compose state: 上报与夹取都在主线程。 */
    internal class FloatingGrip {
        var left: Float = 0f
        var top: Float = 0f
        var width: Float = 0f
        var height: Float = 0f
        val placed: Boolean get() = width > 0f && height > 0f
        fun update(left: Float, top: Float, width: Float, height: Float) {
            if (this.left == left && this.top == top && this.width == width && this.height == height) return
            this.left = left
            this.top = top
            this.width = width
            this.height = height
        }
        fun clear() {
            left = 0f
            top = 0f
            width = 0f
            height = 0f
        }
    }

    internal val referenceGrip = FloatingGrip()
    internal val quickActionGrip = FloatingGrip()
    internal val quickBrushGrip = FloatingGrip()
    internal val quickColorGrip = FloatingGrip()
    internal val quickOklchGrip = FloatingGrip()
    internal val quickLayerGrip = FloatingGrip()

    /** 夹取结果复用容器: 拖拽期每帧要夹 6 个窗, 避免每帧分配 Pair 与装箱。仅主线程使用。 */
    internal val clampScratch = WindowOrigin()

    // 作者档案 (Dublin Core / Krita 兼容元数据) 与多预设管理
    var authorProfilePresets by mutableStateOf<List<com.reverie.paint.model.AuthorProfilePreset>>(emptyList())
        internal set

    var activeAuthorProfileId by mutableStateOf("default")
        internal set

    var authorProfile by mutableStateOf(com.reverie.paint.model.AuthorProfile())
        internal set

    // 新手引导与工作区指引状态
    var onboardingActive by mutableStateOf(false)
    var onboardingStep by mutableIntStateOf(0)
    var layerPanelMeasuredBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var allToolsPanelMeasuredBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var viewToolsMeasuredBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

    // 赞助提示与使用里程碑统计
    var showSponsorPromptDialog by mutableStateOf(false)
    var launchCount by mutableIntStateOf(0)
    var exportCount by mutableIntStateOf(0)
    var createdCanvasCount by mutableIntStateOf(0)

    // UI & View Settings (persisted)
    var uiOpacity by mutableFloatStateOf(1.0f) // For Top and Left panels
    var popupPanelOpacity by mutableFloatStateOf(0.95f) // For floating panels
    var blurBackground by mutableStateOf(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) // 背景毛玻璃效果，默认开启（API<31 设备不支持模糊，自动回退实色）
    var canvasOpenWaveEnabled by mutableStateOf(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) // 打开/新建画布扭曲波纹特效，默认开启（Android 12 以下强制关闭）
    var accentColorHex by mutableStateOf("#879ED1")
    var monetEnabled by mutableStateOf(false) // 莫奈动态取色
    var themeMode by mutableStateOf("DARK") // "DARK", "LIGHT", "SYSTEM"
    var themeContrast by mutableStateOf("STANDARD") // "SOFT", "STANDARD", "HIGH"
    var themeStyle by mutableStateOf(com.reverie.paint.ui.theme.ThemeStyle.MORANDI_TINTED) // MORANDI_TINTED, NEUTRAL_STUDIO, WARM_EARTHY, VIBRANT_GLOW
    var paintingUiScale by mutableFloatStateOf(1.0f) // 绘画页面整体 UI 大小缩放 (0.75 - 1.35)
    var layerRowHeightDp by mutableIntStateOf(52) // 44: 紧凑, 52: 标准, 64: 舒适
    var layerHeaderInheritAlpha by mutableStateOf(false) // 图层面板顶部按钮行为: false 为剪切蒙版, true 为继承透明度
    var quickSliderHeightDp by mutableIntStateOf(175) // 绘画界面快捷滑块长度 (100 - 260 dp, 默认 175)
    var leftHandMode by mutableStateOf(false) // 左手模式: 快捷工具栏与滑块镜像停靠在右侧
    /** 自由悬浮布局配置 (平板专属) */
    var floatingUiConfig by mutableStateOf(com.reverie.paint.model.FloatingUiConfig())

    // Custom Home Background Image settings (persisted)
    var homeCustomBgEnabled by mutableStateOf(false)
    var homeCustomBgOpacity by mutableFloatStateOf(0.85f)
    var homeCustomBgBlurDp by mutableFloatStateOf(0f)
    var homeCustomBgScaleMode by mutableStateOf("CROP") // "CROP" or "FIT"
    var homeCustomBgMaskAlpha by mutableFloatStateOf(0.2f)
    var homeCustomBgVersion by mutableLongStateOf(0L)

    // ---- 前缓冲 UI 裁剪槽位状态 (Phase 1) ----
    /** 21 个面板槽位的实测边界与 owner token (UI 线程独占)。 */
    val panelOcclusion = com.reverie.paint.model.UiPanelOcclusionTracker()

    /** 每个槽位独立的 Compose 状态, 避免全局 mask 牵连所有面板重组。 */
    internal val slotEvading =
        Array(com.reverie.paint.model.UiPanelSlot.COUNT) { mutableStateOf(false) }

    /**
     * 每个槽位唯一的透明度动画（1.0f <-> 0.5f, 70ms）。
     *
     * `evadablePanelSurface` 与 `evadablePanelContent` 必须共享同一个
     * [Animatable]，禁止两处各自 `animateFloatAsState` 造成相位差。
     */
    internal val slotEvadeAlpha =
        Array(com.reverie.paint.model.UiPanelSlot.COUNT) { Animatable(1f) }

    /** 运笔时自动避让悬浮面板（设置 -> 视图显示, 默认开启, 与预测模式无关）。 */
    var autoHidePanelsOnStroke by mutableStateOf(true)

    /**
     * 当前是否存在活跃笔画会话。绘画态优先协议：为 true 时面板只做被动避让，
     * 不消费额外指针事件；为 false 时面板恢复正常点击/拖拽。
     */
    @Volatile
    var isStrokeSessionActive: Boolean = false

    var selectionMaskColorHex by mutableStateOf("#141416") // 选区蒙版遮罩颜色 (默认深空灰黑)
    var selectionMaskOpacity by mutableFloatStateOf(0.47f) // 选区蒙版遮罩不透明度 (0.10 - 0.90, 默认 0.47)

    /** 左侧工具条滑块面板的实时高度与宽度 (px), 由 ToolRail 测量写入; 时间轴对齐与悬浮自适应避让用 */
    var railSliderPanelHeightPx by mutableFloatStateOf(0f)
    var railSliderPanelWidthPx by mutableFloatStateOf(0f)
    var showToolbarSqueezedDialog by mutableStateOf(false)
    var toolbarSqueezedWarningDismissed by mutableStateOf(false)
    var showLowStorageDialog by mutableStateOf(false)
    var lowStorageMessage by mutableStateOf("")
    var showLowMemoryDialog by mutableStateOf(false)
    var lowMemoryMessage by mutableStateOf("")
    internal var lowMemoryWarnedInSession = false

    var isCanvasAdjustActive by mutableStateOf(false)
    var canvasAdjustState by mutableStateOf(com.reverie.paint.model.CanvasAdjustState())
    /** 画布调整面板的拖拽偏移 (面板内部会按可视区钳制, 退出时归零) */
    var canvasAdjustPanelOffset by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    /** 画布调整面板折叠状态: 折叠后仅保留一条可拖动标题栏, 避免长期遮挡画布 */
    var isCanvasAdjustPanelCollapsed by mutableStateOf(false)

    var extendToCutout by mutableStateOf(true)
    var homeSelectedTab by mutableIntStateOf(0)

    // View Display Settings (参考图 1)
    var quickSliderMode by mutableIntStateOf(0) // 0: 不透明度, 1: 流量
    var brushSizePresets by mutableStateOf<List<Double?>>(listOf(2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 350.0))
    var brushOpacityPresets by mutableStateOf<List<Double?>>(listOf(0.10, 0.25, 0.40, 0.50, 0.65, 0.75, 0.85, 0.95, 1.00))
    var brushFlowPresets by mutableStateOf<List<Double?>>(listOf(0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.75, 0.90, 1.00))
    var canvasRotationEnabled by mutableStateOf(true) // 画布可旋转
    
    var canvasRotationSnapDegrees by mutableFloatStateOf(RotationSnap.DEFAULT_THRESHOLD_DEGREES)
    var magnificationInterpolation by mutableStateOf(true) // 放大插值
    var pixelGridEnabled by mutableStateOf(true) // 放大显示网格线
    var undoToastEnabled by mutableStateOf(true) // 撤销操作提醒
    var undoBarEnabled by mutableStateOf(true) // 连续撤销/重做滑动条 (默认开启)

    // Canvas View Lock (固定画布缩放与旋转，保留双指平移)
    var isViewTransformLocked by mutableStateOf(false)

    // 视图翻转 (View Flip): 只镜像**显示**, 一像素都不改 —— 与"完全翻转"
    // (flipCanvasHorizontal/Vertical, 逐层镜像像素) 是两回事。图层多时完全翻转
    // 要重绘每一层, 临时查看/对照画一下用视图翻转, 零开销且随时可切回。
    // 属于视图状态: 不进撤销栈、不影响导出、不录制进回放。
    var viewFlipX by mutableStateOf(false)
    var viewFlipY by mutableStateOf(false)

    // Canvas Touch Disabled (禁用画布触控：丢弃一切手指触控与手势，仅手写笔/鼠标可操作画布，单次会话有效)
    var isCanvasTouchDisabled by mutableStateOf(false)

    // Stroke Stabilizer & Smoothing (抖动修正与平滑算法)
    var strokeStabilizer by mutableFloatStateOf(0.0f) // 基础平滑比例: 0.0 ~ 1.0
    var strokeSmoothingType by mutableIntStateOf(SMOOTHING_BASIC) // 0: 关闭, 1: 基础平滑, 2: 加权平滑 (Krita)
    var strokeSmoothnessDistanceMin by mutableDoubleStateOf(30.0)
    var strokeSmoothnessDistanceMax by mutableDoubleStateOf(30.0)
    var strokeSmoothDistanceLocked by mutableStateOf(true)
    var strokeSmoothPressure by mutableStateOf(true)
    var strokeScalableDistance by mutableStateOf(true)
    var strokeTailAggressiveness by mutableDoubleStateOf(0.5)

    // Keyboard Shortcuts (参考图 2)
    // actionId -> 按键列表; 键不存在 = 出厂默认, 空列表 = 用户显式解绑 (见 model/ShortcutCatalog.kt)
    var shortcutBindings by mutableStateOf<Map<String, List<String>>>(emptyMap())
    // 长按按键是否连发 (连续调节类动作; 关掉可避免按住不放连续触发)
    var shortcutAutoRepeatEnabled by mutableStateOf(true)
    // 工具键按住时临时切到该工具 (松开回到原工具), 见 PaintViewModelShortcuts.handleKeyEvent
    var shortcutHoldTemporaryTools by mutableStateOf(true)

    // ---- 一次性运行时状态: 正在按住的临时工具 (不参与持久化) ----
    internal var holdTemporaryToolId: String? = null
    internal var holdTemporaryBaseToolId: String? = null
    internal var holdTemporaryKey: String? = null
    internal var holdTemporaryStartMs: Long = 0L

    // Stylus Settings (画世界 Pro & Krita style, persisted)
    var penOnlyMode by mutableStateOf(false) // 笔模式 (禁止手指绘制，单指平移，双指缩放旋转)
    var penModeSingleFingerPanEnabled by mutableStateOf(true) // 笔模式下单指平移画布 (可配置开关)
    var brushCursorMode by mutableIntStateOf(3) // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
    var eraserCursorMode by mutableIntStateOf(3)
    var cursorStyleMode by mutableIntStateOf(5) // 0: 圆形, 1: 十字准星, 2: 点, 3: 无, 4: 系统指针, 5: 圆+十字准星, 6: 笔刷轮廓
    // 轮廓反映实际大小 (Krita「使用有效轮廓大小」反选): 关掉后圆环/笔刷轮廓固定按满号笔刷直径画,
    // 运笔途中不再随压感忽大忽小
    var cursorOutlineReflectsActualSize by mutableStateOf(true)
    var quickShapeEnabled by mutableStateOf(false) // 驻停线条成形，默认关闭
    var quickShapeHoldDelayMs by mutableIntStateOf(450)
    var quickShapeQuadrilateralEnabled by mutableStateOf(false)
    var quickShapeConversionsEnabled by mutableStateOf(false)
    var quickShapeCurveEnabled by mutableStateOf(false)
    var quickShapeRelaxedEnabled by mutableStateOf(false)
    var quickShapeArcEnabled by mutableStateOf(false)
    var quickShapeBoxHandlesEnabled by mutableStateOf(false)
    var quickShapeCurvedContourEnabled by mutableStateOf(false)
    var quickShapeContourEnabled by mutableStateOf(false)
    var quickShapeAngleSnapEnabled by mutableStateOf(false)
    var quickShapePerPointPressureEnabled by mutableStateOf(false)
    internal var quickShapeCapture: QuickShapeStrokeCapture? = null
    internal var quickShapeDraft: QuickShapeDraft? = null
    var quickShapeCommitting by mutableStateOf(false)
    var activeQuickShape by mutableStateOf<QuickShapeResult?>(null)
    var isQuickShapeEditing by mutableStateOf(false)
    internal data class QuickShapePreviewSnapshot(
        val generation: Int,
        val shape: com.reverie.paint.model.QuickShapeResult,
    )
    internal val quickShapePreviewSnapshot = java.util.concurrent.atomic.AtomicReference<QuickShapePreviewSnapshot?>()
    internal val quickShapePreviewRequest = java.util.concurrent.atomic.AtomicReference<com.reverie.paint.model.QuickShapeResult?>()
    internal val quickShapePreviewGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    internal val quickShapePreviewQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val quickShapeSession = com.reverie.paint.model.QuickShapeSession()
    internal var quickShapeReplaySeed: Int = -1

    // Vendor Stylus Adaptations (OPPO Pencil / OnePlus Stylo & Samsung S Pen)
    var oppoPencilModelMode by mutableStateOf("AUTO") // "AUTO", "STANDARD", "PRO"
    var detectedOppoPencilModel by mutableStateOf(com.reverie.paint.core.stylus.OppoPencilModel.PRO)
    var oppoDoubleTapAction by mutableStateOf("toggle_eraser")
    var oppoSlideAction by mutableStateOf("adjust_brush_size")
    var oppoSlideSensitivity by mutableStateOf("normal") // "low", "normal", "high"
    var oppoInPenHapticsEnabled by mutableStateOf(true)
    var stylusHapticsEnabled by mutableStateOf(true)
    var stylusHapticsIntensity by mutableFloatStateOf(0.5f)
    var stylusAudioEnabled by mutableStateOf(false)
    var stylusAudioVolume by mutableFloatStateOf(0.6f)
    var stylusAudioType by mutableStateOf(StylusAudioType.PENCIL)
    var stylusStrokePredictionEnabled by mutableStateOf(false)
    /**
     * 前缓冲预测 (本次低延迟技术的总开关, 默认关防止机型不适配, 用户可在设置中随时手动开启)。
     * 开启时启用: 卡尔曼运动预测 + 墨迹空窗回填 + 前缓冲直出 + 光标跟随 + 悬停前瞻;
     * 关闭时回到上游原版行为 (仅"超低延迟笔迹预测"开关的 OEM 硬件尾线)。
     *
     * 默认关闭的原因: 前缓冲直出依赖 `androidx.graphics:graphics-core` 原生库对 SurfaceControl
     * 的用法, 在部分 ROM (已确认华为鸿蒙机型) 上会在系统 libgui 的事务完成回调线程里空指针崩溃,
     * Kotlin 侧无法捕获。想要更低延迟的用户可在设置里主动打开。
     */
    var frontBufferPredictionEnabled by mutableStateOf(false)

    /**
     * 新建画布后，前缓冲在这段 uptime 之前不贴真墨、也不按住前缓冲。
     * 画布刚出来的约 3 秒内落笔，若系统/HWUI 重排未稳定，笔迹可能分裂；等过这段再画则正常。
     */
    @Volatile
    var frontBufferQuietUntilMs: Long = 0L

    // Samsung Notes 标准语义: 按住侧键落笔 = 临时橡皮 (默认开, 可在三星 S Pen 专属设置中关闭)
    var samsungSideButtonErase by mutableStateOf(true)
    var samsungSingleClickAction by mutableStateOf("toggle_eraser")
    var samsungDoubleClickAction by mutableStateOf("undo")
    var samsungLongPressAction by mutableStateOf("tool_picker")

    // 三星 S Pen 隔空手势动作映射 (6 个挥动/画圈手势; 笔键单击/双击与侧键通道共用 samsung*ClickAction)
    var spenAirSwipeLeftAction by mutableStateOf(SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_LEFT))
    var spenAirSwipeRightAction by mutableStateOf(SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_RIGHT))
    var spenAirSwipeUpAction by mutableStateOf(SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_UP))
    var spenAirSwipeDownAction by mutableStateOf(SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_DOWN))
    var spenAirCircleCwAction by mutableStateOf(SpenAirActions.defaultBinding(SpenAirGesture.CIRCLE_CW))
    var spenAirCircleCcwAction by mutableStateOf(SpenAirActions.defaultBinding(SpenAirGesture.CIRCLE_CCW))

    // HUAWEI M-Pencil 适配参数
    var huaweiPencilModelMode by mutableStateOf("AUTO") // "AUTO", "PRO", "GEN3_NEARLINK", "GEN2", "GEN1"
    var huaweiDoubleTapAction by mutableStateOf("toggle_eraser")
    var huaweiSqueezeAction by mutableStateOf("tool_color")
    var huaweiSingleClickAction by mutableStateOf("none")
    var huaweiLongPressAction by mutableStateOf("tool_color")
    var huaweiSideButtonErase by mutableStateOf(true)
    var huaweiHapticsEnabled by mutableStateOf(true)

    // HONOR Magic-Pencil 适配参数
    var honorPencilModelMode by mutableStateOf("AUTO") // "AUTO", "MAGIC_PENCIL_3", "MAGIC_PENCIL_2", "MAGIC_PEN"
    var honorDoubleTapAction by mutableStateOf("toggle_eraser")
    var honorSingleClickAction by mutableStateOf("none")
    var honorSideButtonErase by mutableStateOf(true)
    var honorHapticsEnabled by mutableStateOf(true)

    // 小米 (Xiaomi) 触控笔适配参数
    var xiaomiPencilModelMode by mutableStateOf("AUTO") // "AUTO", "FOCUS_PEN_PRO", "FOCUS_PEN", "SMART_PEN_2", "SMART_PEN_1"
    var xiaomiPrimaryButtonAction by mutableStateOf("toggle_eraser")
    var xiaomiSecondaryButtonAction by mutableStateOf("tool_picker")
    var xiaomiFocusButtonAction by mutableStateOf("toggle_last_tool")
    var xiaomiDoubleTapAction by mutableStateOf("toggle_eraser")
    var xiaomiSideButtonErase by mutableStateOf(true)
    var xiaomiSqueezeAction by mutableStateOf("tool_color")
    var xiaomiSlideAction by mutableStateOf("adjust_brush_size")
    var xiaomiSlideSensitivity by mutableStateOf("normal")
    var xiaomiInPenHapticsEnabled by mutableStateOf(true)

    // vivo / iQOO 手写笔适配参数 (笔迹预测开关复用全局 stylusStrokePredictionEnabled)
    var vivoPencilModelMode by mutableStateOf("AUTO") // "AUTO", "VIVO_PENCIL2", "VIVO_PENCIL2_NV", "VIVO_PENCIL2S", "VIVO_PENCIL3", "VIVO_PENCIL1"
    var vivoDoubleTapAction by mutableStateOf("toggle_eraser")
    var vivoPrimaryClickAction by mutableStateOf("toggle_eraser")
    var vivoSecondaryClickAction by mutableStateOf("tool_picker")
    var vivoSideButtonErase by mutableStateOf(true)
    var vivoWritingVibrateEnabled by mutableStateOf(true)

    // 通用 (Generic) 手写笔适配参数
    var genericStylusEnabled by mutableStateOf(false)
    var genericPrimaryButtonAction by mutableStateOf("toggle_eraser")
    var genericSecondaryButtonAction by mutableStateOf("undo")
    var genericSideButtonErase by mutableStateOf(true)

    // 触控预测与输入延迟优化
    var motionPredictorEnabled by mutableStateOf(true)

    /**
     * 笔刷低延迟前向预测保真度分级 (Fidelity Tiering)
     * 严格依据 docs/WET-INK-EXPERIMENT.md 历史教训，避免图像笔尖替换跳变与粗劣生硬假线：
     * - NONE: 严禁放行 (印章/喷溅/特效/网格/形状/色彩混合等，保持纯裸管线)
     * - TIER_1: 高保真基础 (勾线/圆笔/墨水/平头/标准线稿，透明度对齐真墨全量放行)
     * - TIER_2: 精细拟合 (铅笔/速写/低流量及未知笔刷，发丝级导引线)
     * - STAMP: 真实笔尖戳印 (水彩/纹理与排线/绘画类)：前缓冲逐 dab 盖印真实 tip
     *   位图 (形状颜色与真墨一致，仅湿润累积为近似，预览只存活 1~2 帧)。
     *   仅 brush 工具；tip 不可解码时渲染侧回落 TIER_2 发丝线。
     */
    enum class PredictionFidelityTier {
        NONE,
        TIER_1,
        TIER_2,
        STAMP
    }

    internal var cachedPredictionTier: PredictionFidelityTier? = null
    internal var cachedTierBrushPresetIndex: Int = -1
    internal var cachedTierToolId: String = ""
    internal var cachedTierPredictionEnabled: Boolean = true
    internal var cachedTierTextureEnabled: Boolean = false
    internal var cachedTierOpacity: Double = -1.0
    internal var cachedTierFlow: Double = -1.0
    internal var cachedTierCompositeOp: String = ""

    // 绘图辅助与参考线状态 (Symmetry, Perspective, Grid)
    var drawingGuide by mutableStateOf(DrawingGuideConfig())
    var drawingGuideLinesVisible by mutableStateOf(true)
        internal set

    // 动画画面参考线 (仅动画工程显示)
    var compositionGuides by mutableStateOf<Set<CompositionGuide>>(emptySet())
        internal set
    var compositionHorizon by mutableFloatStateOf(0.5f)
        internal set

    // 画布内富文本排版状态 (In-place Typography)
    var typographyConfig by mutableStateOf(TypographyConfig())
    var isTypographyEditing by mutableStateOf(false)
    var typographySnapGuides by mutableStateOf<List<com.reverie.paint.model.TypographySnapGuide>>(emptyList())
    var activeTextLayers by mutableStateOf<Map<Long, List<TypographyConfig>>>(emptyMap())
    var editingTextLayerId by mutableStateOf<Long?>(null)
    var editingTextItemId by mutableStateOf<String?>(null)
    var typographyInputFocusTrigger by mutableIntStateOf(0)

    var pressureCurvePreset by mutableIntStateOf(0) // 0: 线性, 1: 轻压灵敏, 2: 重压偏硬, 3: S型, 4: 自定义
    var pressureControlPoints by mutableStateOf(
        listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(1f, 1f),
        ),
    )

    // 速度模拟压感 (Issue #225)
    var speedSimulatedPressureEnabled by mutableStateOf(false)
    var speedPressureInfluence by mutableFloatStateOf(0.7f)
    var speedPressureFingerOnly by mutableStateOf(true)
    internal val speedPressureSimulator = com.reverie.paint.model.SpeedPressureSimulator()
    internal var speedSimulatedPressureActiveForStroke: Boolean = false
    internal var lastStrokeEffectivePressure: Double = 1.0
    internal var stylusHardwarePressureDetected: Boolean = false

    var colorPickerMode by mutableStateOf("SQUARE")
        internal set

    // Tool options states (persisted)
    var fillTolerance by mutableIntStateOf(16)
    var fillSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层
    var fillExpand by mutableIntStateOf(0) // 拓展 (-16..32 px)
    var fillFeather by mutableIntStateOf(0) // 羽化 (0..32 px)
    var fillCloseGap by mutableIntStateOf(0) // 闭合空隙 (0..16 px, 默认为 0 保证秒级瞬时填充)
    var fillOpacity by mutableDoubleStateOf(1.0)
    var fillCompositeOp by mutableStateOf("normal")
    internal var fillPattern by mutableStateOf<FillPattern?>(null)

    // 圈涂 (Enclose and Fill) 属性状态 (持久化)
    var encloseFillCloseGap by mutableIntStateOf(4) // 闭合空隙 (0..32 px)
    var encloseFillTolerance by mutableIntStateOf(16) // 容差 (1..100)
    var encloseFillSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层
    var encloseFillExpand by mutableIntStateOf(0) // 拓展 (-16..32 px)
    var encloseFillFeather by mutableIntStateOf(0) // 羽化 (0..32 px)
    var encloseFillRegionMode by mutableStateOf(com.reverie.paint.model.EncloseFillRegionMode.ALL) // 过滤模式 (默认全部区域: 仅参考外层轮廓)
    var encloseFillSpecificColor by mutableIntStateOf(0xFF000000.toInt()) // 特定目标颜色
    var encloseFillIncludeContour by mutableStateOf(false) // 是否包含边缘连通区 (默认排除外部背景)
    var encloseFillSettingsOpen by mutableStateOf(false) // 圈涂高级设置弹窗开关

    // 填充连击防抖追踪，防止卡顿时连点向撤销栈压入重复事务
    internal var lastFillSubmitTime = 0L
    internal var lastFillDocX = -9999f
    internal var lastFillDocY = -9999f

    var gradientType by mutableIntStateOf(0) // 0: 线性, 1: 径向, 2: 角度
    var gradientRepeat by mutableIntStateOf(0) // 0: 无, 1: 重复, 2: 往返
    var gradientReverse by mutableStateOf(false)
    val gradientState = GradientState()

    var shapeStrokeWidth by mutableDoubleStateOf(4.0)
    var shapeFillMode by mutableIntStateOf(0) // 0: 仅描边, 1: 仅填充, 2: 描边与填充
    var shapeKeepAspect by mutableStateOf(false)
    val shapeState = ShapeState()

    var measureStrokeWidth by mutableFloatStateOf(2.5f)

    var liquifyStrength by mutableFloatStateOf(0.9f)
    var liquifyHardness by mutableFloatStateOf(0.5f)
    var liquifyMode by mutableIntStateOf(0)
    var liquifyBrushSize by mutableFloatStateOf(60f)

    var selectionMode by mutableIntStateOf(0) // 0: 替换, 1: 添加, 2: 减去, 3: 相交
    var lassoSubMode by mutableIntStateOf(LassoSubMode.FREEHAND) // 0: 自由描画, 1: 折线, 2: 自由+折线
    var lassoMultiPoints by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
    var lassoSegmentCounts = mutableListOf<Int>()
    var customUndoHook: (() -> Boolean)? = null
    var selectionTolerance by mutableIntStateOf(24)
    var selectionSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层
    var selectionFeatherRadius by mutableIntStateOf(0)
    var selectionCloseGap by mutableIntStateOf(4) // 闭合空隙 (0..16 px)
    var selectionExpand by mutableIntStateOf(0) // 拓展 (-16..32 px)

    // 存储选区 / 选区历史
    val savedSelections = androidx.compose.runtime.mutableStateListOf<SavedSelectionUiItem>()
    var savedSelectionsPopupOpen by mutableStateOf(false)

    var pickerSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层

    var settingsInitialSubPage by mutableStateOf("MAIN")

    // 图层面板多选（右滑选中）
    var selectedLayerIndices by mutableStateOf<Set<Int>>(emptySet())

    // 图层组折叠状态。键是**层的稳定 id**，不是名字 —— 同名组在图层树里完全合法
    // (复制组、手动命名都可能撞名)，早期按名字存导致"打开一个同名组，别的组也跟着开合"。
    var collapsedGroupKeys by mutableStateOf<Set<String>>(emptySet())

    // 分格组折叠状态 (key = 分格 panelId, 元数据随格持久化, 跨会话稳定; 与
    // collapsedGroupNames 同渠道读写项目 extraJson 的 collapsedPanels 字段)
    var collapsedPanelIds by mutableStateOf<Set<Long>>(emptySet())

    // 独显浮窗的“取消所有效果”模式（C++ 状态，经 notifyLayerChanged 同步为
    // Compose state，保证点击后 chip 高亮即时刷新）
    var soloRawMode by mutableStateOf(false)

    // 绘画页内的“更多设置”全屏覆盖层（不退出画布）
    var moreSettingsOpen by mutableStateOf(false)

    var gestureTwoFingerUndo by mutableStateOf(true)
    var gestureThreeFingerRedo by mutableStateOf(true)
    var gestureThreeFingerEditMenu by mutableStateOf(true)
    var canvasEditMenuActions by mutableStateOf(com.reverie.paint.model.CanvasEditAction.defaults)
        internal set
    var canvasEditBusy by mutableStateOf(false)
    var canvasEditCapabilities by mutableIntStateOf(0)
    var canvasClipboardAvailable by mutableStateOf(true)

    var gestureConfig by mutableStateOf(com.reverie.paint.model.GestureConfig())

    var quickMenuAnchor by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    var quickMenuHoverIndex by mutableIntStateOf(-1)

    var layerSelectPopupVisible by mutableStateOf(false)
    var layerSelectAnchor by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    var layerSelectHitIndices by mutableStateOf<List<Int>>(emptyList())

    var brushSlideAdjustEnabled by mutableStateOf(true)

    var gesturePinchTransform by mutableStateOf(true)
    var gestureQuickPinchFit by mutableStateOf(true)

    var gestureContinuousUndo by mutableStateOf(true)

    /** 绘画主界面返回键的兜底行为 (浮层都已关闭时才生效), 见 [BackKeyAction] */
    var backKeyAction by mutableStateOf(BackKeyAction.NONE)

    /** 是否允许屏幕边缘侧滑返回手势 (开启且返回键行为非无时释放手势排除区) */
    var allowEdgeBackGesture by mutableStateOf(true)

    /** 界面动效速度设置 (NORMAL / FAST / OFF) */
    var uiAnimationSpeed by mutableStateOf(com.reverie.paint.model.UiAnimationSpeed.NORMAL)

    var actionToastMessage by mutableStateOf<String?>(null)
    var actionToastIcon by mutableStateOf<Int?>(null)
    var actionToastRevision by mutableLongStateOf(0L)

    // 切层内容闪现 (见 PaintViewModelLayerFlash.kt): layerFlashBitmap 为当前
    // 闪现内容 (null=不显示), layerFlashTick 驱动 CanvasOverlay 动画重启,
    // layerFlashRequestId 让过期渲染作废, 双缓冲位图池与索引供引擎线程写入。
    // 顶层扩展函数无法访问 private 成员, 故与 renderHandler 等跨文件字段一致用 internal
    internal var layerFlashBitmap by mutableStateOf<Bitmap?>(null)
    internal var layerFlashTick by mutableIntStateOf(0)
    @Volatile internal var layerFlashRequestId = 0
    @Volatile internal var layerFlashBuffers: Array<Bitmap>? = null
    @Volatile internal var layerFlashFrontIdx = 0

    var longPressEyedropperEnabled by mutableStateOf(true)
    var eyedropperSensitivity by mutableIntStateOf(3) // 1..5, default 3
    var eyedropperOffsetEnabled by mutableStateOf(true) // offset sampling point to avoid finger blocking
    var colorWheelCompareEnabled by mutableStateOf(true)

    var penModeStylusEyedropperEnabled by mutableStateOf(false)

    /** 画布页面强制全屏沉浸模式 (隐藏系统栏) */
    var immersiveMode by mutableStateOf(true)
        internal set

    var canvasBgColorHex by mutableStateOf("DEFAULT")

    var canvasGridEnabled by mutableStateOf(true)
    var canvasGridOpacity by mutableFloatStateOf(0.08f)
    var canvasGridSizeDp by mutableIntStateOf(16)

    var colorPanelTab by mutableIntStateOf(0)
    var colorWheelInnerShape by mutableStateOf("SQUARE")
    var colorModel by mutableStateOf("hsv") // "hsv", "v-hsv", "hsl", "hsy"
    var recentColors by mutableStateOf<List<String>>(
        listOf(
            "#FFFFFF", "#D6D6D6", "#ADADAD", "#858585", "#5C5C5C", "#333333", "#141414", "#000000",
            "#F44336", "#FF9800", "#FFEB3B", "#4CAF50", "#00BCD4", "#2196F3", "#9C27B0", "#E91E63"
        )
    )

    data class ColorPaletteItem(
        val id: String,
        val name: String,
        val colors: List<String>
    )

    var userPalettes by mutableStateOf<List<ColorPaletteItem>>(
        listOf(
            ColorPaletteItem(
                "builtin_basic", "基本色",
                listOf(
                    "#000000", "#FFFFFF", "#FEEBD0", "#FFF000", "#FFA500", "#FF4500", "#E60000", "#990000",
                    "#99CC00", "#339900", "#009944", "#00A0E9", "#0068B7", "#1D2088", "#601986", "#4A225D"
                )
            ),
            ColorPaletteItem(
                "builtin_morandi", "莫兰迪",
                listOf(
                    "#B8A18F", "#9C8578", "#827065", "#A0A59A", "#8B958D", "#737F79", "#A1A0B0", "#838294",
                    "#978D7E", "#847A6B", "#6F6659", "#B49F82", "#A18A6C", "#8B7457", "#9C7C7C", "#806363"
                )
            )
        )
    )

    var defaultPaletteId by mutableStateOf("builtin_basic")

    /** Whether color panel is pinned as a floating companion window without blocking canvas interaction */
    var isColorPanelPinned by mutableStateOf(false)

    /** Drag offset for pinned floating companion color panel */
    var colorPanelOffset by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** Whether brush panel is pinned as a floating companion window without blocking canvas interaction */
    var isBrushPanelPinned by mutableStateOf(false)

    /** Drag offset for pinned floating companion brush panel */
    var brushPanelOffset by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** Whether layer panel is pinned as a floating companion window without blocking canvas interaction */
    var isLayerPanelPinned by mutableStateOf(false)

    /** Drag offset for pinned floating companion layer panel */
    var layerPanelOffset by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** Whether panel pinning & dragging controls are enabled for layer & brush panels (off by default) */
    var panelPinningEnabled by mutableStateOf(false)

    data class LayerDragState(
        val layer: LayerUiState,
        val draggedIds: Set<Long> = emptySet(),
        val isMulti: Boolean = false,
        val multiCount: Int = 1,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val grabOffsetX: Float = 0f,
        val grabOffsetY: Float = 0f,
        val cardWidthPx: Float = 0f,
        val cardHeightPx: Float = 0f,
    )

    var activeLayerDrag by mutableStateOf<LayerDragState?>(null)
    var layerDragFingerX by mutableFloatStateOf(0f)
    var layerDragFingerY by mutableFloatStateOf(0f)
    var layerDragSettleTo by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    var layerDragSettleFrom by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    var isLayerDragSettling by mutableStateOf(false)
    var isLayerDragGroupSettle by mutableStateOf(false)

    var colorHarmonyModeName by mutableStateOf("COMPLEMENTARY")
    var colorHarmonyBaseHue by mutableFloatStateOf(-1f)
    var colorHarmonySubTab by mutableIntStateOf(0)
    var colorSphereBaseHex by mutableStateOf("")

    // Recent projects list
    var projects by mutableStateOf<List<com.reverie.paint.model.Project>>(emptyList())

    /** Snapshot of one layer's native state, mirrored from C++ on every
     * structure change. The UI reads this Compose state instead of calling
     * JNI getters during composition, so updates are always visible. */
    data class LayerUiState(
        val index: Int,
        val id: Long = 0L,
        val name: String,
        val visible: Boolean,
        val locked: Boolean,
        val alphaLocked: Boolean,
        val isGroup: Boolean,
        val nodeType: Int = 0, // 0paint/1group/2fill/3adjust/5clone/10-13四mask
        val depth: Int,
        val colorLabel: Int,
        val clipped: Boolean,
        val alphaInherited: Boolean = false,
        val isBackground: Boolean,
        val soloed: Boolean,
        val opacity: Double,
        val blendMode: String,
        val isStrokeLayer: Boolean = false,
        val strokeSize: Int = 6,
        val strokeColor: Int = 0xFF000000.toInt(),
        val strokePosition: Int = 0,
        val strokeOpacity: Int = 100,
        val fillColor: Int = 0xFFFFFFFF.toInt(),
        // 穿透混合模式 (仅组有效)。必须进镜像 state: 详情面板的开关若在
        // 组合期直接 JNI 读, 设置后镜像 equals 不变 -> 面板重组被跳过 ->
        // 开关显示永远不刷新 (点锁定等"真正改变镜像"的操作才会顺带刷新)
        val passThrough: Boolean = false,
        val uuid: String = "",
        val ownerPeerId: String = "",
        val collabPermission: Int = 0,
    )

    // ---- async render plumbing ----
    // Document size as known by the C++ core (written on the render thread,
    // read there too; Compose-facing docWidth/docHeight are mirrored via
    // the main handler after document creation). setRenderViewport also
    // reads them on the main thread, hence @Volatile.
    @Volatile internal var coreW = 1080

    @Volatile internal var coreH = 1920

    // High-performance direct native canvas rendering:
    // Render buffer is kept at full native document resolution (or clamped to GPU texture limit e.g. 4096)
    // for pixel-perfect 1:1 Krita projection alignment with 0 scaling artifacts.
    @Volatile internal var renderW = 1080

    @Volatile internal var renderH = 1920

    var displayRevision by mutableLongStateOf(0L)
        internal set

    /** 参考窗口-画布页的独立刷新版本号: 仅当快照真正上屏时才自增, 避免 120Hz 冗余重组与纹理重传。 */
    var referenceCanvasRevision by mutableLongStateOf(0L)
        internal set

    // ===== 动画 (帧 / 轨道 / 时间轴) 状态镜像 =====
    // 引擎侧真身在 Krita 的 KisRasterKeyframeChannel (图层即轨道),
    // 这里只镜像 UI 需要的元信息。所有动画操作必须经
    // PaintViewModelAnimation.kt 的封装, 保持引擎调用不上 UI 线程。
    internal val anim = AnimationState()

    internal val syncState = SyncState()

    internal val localBackupState = LocalBackupState()

    internal val collab = CollabSessionState()

    /** 动画音频播放器 (导入的音频资源, 播放动画时循环同步播放; 见 PaintViewModelAnimation.kt) */
    internal val animAudioPlayers = mutableListOf<android.media.MediaPlayer>()

    internal var renderThread: HandlerThread? = null
    internal var renderHandler: Handler? = null
    internal val mainHandler = Handler(Looper.getMainLooper())

    // Front/back bitmap rotation: native renders into a buffer that is NOT
    // currently displayed (or pending display), then the main thread flips
    // the reference. Writing the single displayed buffer from the render
    // thread while the Compose RenderThread was still reading it produced
    // torn/ghost frames; with rotation the writer and the reader never touch
    // the same bitmap. Three buffers give each demoted bitmap two frame-times
    // of rest before it is written again.
    //
    // Double buffering: frontBuffer is read by the UI/view on the main thread;
    // backBuffer is rendered into by C++ ReverieCore on the render thread.
    internal var frontBuffer: Bitmap? = null
    internal var backBuffer: Bitmap? = null

    // 512 显示瓦片网格 (1:1 渲染路径的画布来源, 见 PaintViewModelTiles.kt)。
    internal val displayTiles = DisplayTileState()

    /**
     * 当前文档的瓦片网格分配曾经 OOM 失败 ⇒ 这份文档整份退回整图双缓冲路径 (熔断)。
     * 只在渲染线程写, 换文档时由 [discardTileGridForDocumentSwitch] 复位。
     */
    @Volatile internal var displayTilesUnavailableForDoc = false

    /**
     * 换文档进行中: 从 [discardTileGridForDocumentSwitch] 到新文档的第一帧真正上屏为止。
     *
     * 为什么需要这个概念: `drawCanvas` 只有两个文档像素来源 —— 瓦片网格与 [displayBitmap]。
     * 换文档时网格会立刻作废 (UI 线程可见), 但整图双缓冲里装的是**上一份文档**的像素,
     * 于是加载全程画布上一直是上一个文件的画面 (加载遮罩只有 45% 黑, 透得清清楚楚);
     * 若反过来把缓冲提前清空, 又会变成"白屏闪一下"。
     *
     * 这里不赌某一份缓冲, 而是让绘制路径知道"换文档还没结束" —— 期间不画任何文档像素
     * ([CanvasTouchView.drawCanvas]), 加载遮罩用不透明底色, 新文档第一帧上屏后才放行。
     */
    @Volatile internal var documentSwitchPending = false

    /** [documentSwitchPending] 的 Compose 镜像 (Compose 只认状态; 渲染线程读上面那个 volatile)。 */
    var awaitingFirstFrameAfterLoad by mutableStateOf(false)
        internal set

    internal val postedClearAwaitingFirstFrame =
        Runnable {
            documentSwitchPending = false
            awaitingFirstFrameAfterLoad = false
        }

    // Cumulative dirty rect for large bitmap tiling (> 100MB) synchronization
    internal val largeBitmapDirtyRect = android.graphics.Rect()

    // Zero-allocation reusable canvas, rect and dirty array for buffer synchronization
    internal val syncCanvas = android.graphics.Canvas()
    internal val syncPaint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }
    // Cumulative dirty rects missing from backBuffer and frontBuffer to ensure 100% sync
    internal val backMissingRect = android.graphics.Rect()
    internal val frontMissingRect = android.graphics.Rect()
    internal val renderDirty = IntArray(4)
    internal val forceFullDummyDirtyRects = IntArray(4)
    internal var hasWrittenRect = false

    // 本帧实际写入区域 (渲染缓冲坐标 x/y/w/h), 供 UI 侧做局部失效。
    // 双缓冲交替发布: 渲染线程每帧只写一份不重复使用的副本, 读侧拿到的引用
    // 必然是完整一帧的结果, 且这条每帧路径上不产生任何新数组 (§4)。
    @Volatile internal var renderDirtySnapshot: IntArray? = null
    internal val dirtySnapshotA = IntArray(4)
    internal val dirtySnapshotB = IntArray(4)
    internal var dirtySnapshotFlip = false

    @Volatile internal var displayBufferInvalid = false

    @Volatile internal var renderScheduled = false

    /**
     * 有一次不经 [renderScheduled] 记账的渲染已投递 (立即渲染 / 脏区未清完的 4ms 重试)。
     * 只给前缓冲就绪门控 (L-02) 作"渲染空闲"的**推断**信号; doRender 开头清掉。
     */
    @Volatile internal var renderFollowUpPending = false

    /** Pending multi-layer target set from the last replayed T_*_LAYERS
     *  recording event; consumed once by the following BEGIN / MOVE_CONTENT. */
    internal var pendingReplayLayers: IntArray? = null

    // The pending throttled-render runnable, kept so an immediate render can
    // actually cancel it (removeCallbacks needs the same instance; a bare
    // postDelayed creates a fresh Message each call that no token-based
    // removal can ever match).
    @Volatile internal var pendingRenderRunnable: Runnable? = null

    init {
        currentInstance = this
        startRenderThread()
        // 前缓冲内容保留能力的后台探测 (十毫秒级), 进入画布前结论早已就绪。
        com.reverie.paint.core.stylus.FrontBufferProbe.warmUpPreservedContentCheck()
    }

    /**
     * 前缓冲真墨呈现 (续 #81) 的渲染线程接口, 由画布视图注册。core 只认 [RealInkSink],
     * 不引用 ui 包; 为 null 时一切照旧 (主画布逐帧失效)。
     */
    @Volatile
    internal var realInkSink: RealInkSink? = null

    /**
     * 瓦片/整图渲染修订号 (单调递增, 永不归零)。
     * 每次主画布发布新瓦片或整图帧前自增, UI 线程绘制前读取, 用于抬笔追赶门控。
     */
    @Volatile
    var tileRevision: Long = 0L

    var stylusDriver: com.reverie.paint.core.stylus.StylusDriver? = null

    /** 应用是否处于前台 (由 MainActivity 的 onResume/onPause 维护)。 */
    internal var isAppForeground = true

    override fun onCleared() {
        if (currentInstance == this) {
            currentInstance = null
        }
        selectionClipboard?.takeUnless { it.isRecycled }?.recycle()
        selectionClipboard = null
        stylusDriver?.release()
        stylusDriver = null
        stopAirbrush()
        mainHandler.removeCallbacks(persistToolStatesRunnable)
        persistToolBrushStates()
        BrushStoreVm.flushAllPending()
        presetSelectDebounceJob?.cancel()
        presetSelectDebounceJob = null
        isPresetLoading = false
        queuedPresetIndex = null
        com.reverie.paint.core.brushstore.AtomicFiles.flushPending()
        closePerformanceHintSession()
        if (collab.isActive) {
            leaveCollabRoom(synchronousNativeStop = true)
        }
        renderThread?.quitSafely()
        unregisterMemoryPressureCallbacks()
        renderThread = null
        renderHandler = null
        brushPresetsLoaded = false
        super.onCleared()
    }

    internal var performanceHintSession: Any? = null
    internal var renderThreadTid: Int = -1
    internal var adpfDisabled: Boolean = false

    // Same debounced-write pattern for per-tool brush states: slider ticks
    // update the in-memory map every event, disk writes coalesce 250ms after
    // the last tick.
    internal val persistToolStatesRunnable = Runnable { persistToolBrushStates() }

    internal val pendingCoreOps = java.util.concurrent.atomic.AtomicInteger(0)
    internal val pendingHistoryOps = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 正在排队或执行中的后端图层结构/属性操作计数。
     * 与 [pendingCoreOps] 不同，此计数直到 `renderHandler` 执行完 C++ 操作且
     * 主线程完成状态对账回调 (`after`) 后才递减，用于阻止后端尚未完成时用户抢先下笔或重复拖拽。
     */
    internal val pendingLayerStructureOps = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 「帧转图层」整条链复用的中转位图(一张 ARGB_8888, 2800×1840 约 20MB)。
     *
     * 只分配一张、逐帧覆写: 拆 N 帧就只占一张的内存, 拆完由
     * `releaseFramesToLayersTransit()` 回收。**不能**是扩展属性 ——
     * Kotlin 不允许扩展属性持有 backing field。
     */
    internal var framesToLayersTransit: android.graphics.Bitmap? = null

    /** 本次计划拆多少帧 —— 用来区分"全成"与"中止"。 */
    internal var framesToLayersPlanned: Int = 0

    /** 本次实际建成的层数。 */
    internal var framesToLayersCreated: Int = 0

    /**
     * 阶段二要用: (层下标, 帧号) 的对应表。
     *
     * 建层在阶段一全部做完, 写像素在阶段二逐个进行 —— 靠这张表把两者对上。
     * 存下标而不是 id 是安全的: 新层是**连续追加在队尾**的, 阶段二又不再有任何结构变更。
     */
    internal val framesToLayersNewIds = mutableListOf<Pair<Int, Int>>()

    /** 第几次做帧转图层, 用来给组编号(「帧转图层 1」「帧转图层 2」…)。 */
    internal var framesToLayersSeqCounter: Int = 0

    // Stroke-sample transport (batch-preserving). The UI thread appends every
    // touch sample into preallocated arrays and posts ONE drain runnable; the
    // render thread submits the whole batch in a single JNI call. The old
    // latest-wins single slot DROPPED intermediate samples whenever the
    // render thread was busy, which turned fast strokes into polylines and
    // lost pressure detail. Buffers are allocated once: zero allocation on
    // the hot path (架构铁律 §4).
    companion object {
        /** 见 [frontBufferQuietUntilMs]。取实测窗口的上沿，避免卡在 3 秒边界上仍裂开。 */
        const val NEW_CANVAS_FRONT_BUFFER_QUIET_MS = 3_200L

        /**
         * 换文档后等第一帧上屏的兜底上限。正常路径由"渲染帧之后就绪"的收尾精确放行, 这个
         * 超时只防渲染线程异常/引擎无输出时画面永远停在"加载中"。
         * 大画布上首帧要写满整份文档的瓦片 (6400x10240 = 260 张 / 250MB, 真机 0.3~1s),
         * 所以给得比一帧宽松得多。
         */
        const val FIRST_FRAME_GUARD_TIMEOUT_MS = 8_000L

        @Volatile var currentInstance: PaintViewModel? = null
        const val SMOOTHING_OFF = 0
        const val SMOOTHING_BASIC = 1
        const val SMOOTHING_WEIGHTED = 2
        const val SMOOTHING_DISTANCE_MIN = 1.0
        const val SMOOTHING_DISTANCE_MAX = 300.0
        internal const val SMOOTHING_HISTORY_CAPACITY = 128

        val DEFAULT_PINNED_TOOLS = listOf(
            com.reverie.paint.model.Tool.BRUSH,
            com.reverie.paint.model.Tool.ERASER,
            com.reverie.paint.model.Tool.SMUDGE,
            com.reverie.paint.model.Tool.FILL,
            com.reverie.paint.model.Tool.LASSO,
        )
        const val PRESENT_ESTIMATE_MS = 8L // 普通 View 路径: UI draw + SurfaceFlinger 合成上屏延迟
        const val PRESENT_TAIL_MS = 4L // onDraw 提交 + HWUI/SF 合成尾

        /** 真墨由前缓冲直出时的上屏延迟估计 (单缓冲, 绕过 HWUI/SF 的排队)。 */
        const val FRONT_BUFFER_PRESENT_MS = 4L

        /**
         * 笔迹渲染节流判定 (P7 / L2): 按住真墨前缓冲时 ([held]) 永远不节流 ([minIntervalMs] 视为 0),
         * 以保证省电模式下运笔延迟与性能模式一致; 其余情况与原逻辑一致。
         */
        fun shouldThrottleStroke(minIntervalMs: Long, elapsed: Long, held: Boolean): Boolean =
            !held && minIntervalMs > 0L && elapsed < minIntervalMs
        const val STROKE_BATCH_CAPACITY = 256
        const val STROKE_SAMPLE_STRIDE = 6

        /**
         * 带时间的采样步进 (仅对称镜像缓冲使用): 前 6 个 float 与 [STROKE_SAMPLE_STRIDE]
         * 相同, 第 7 个是相对笔锋起点的秒。注意 JNI `touchStrokeMoveBatch` 只认
         * stride 6/3, 重放须重打包为 6 维并丢弃时间; 仅 `touchStrokeMirrorBatch`
         * -> `paintMirrorPolyline(..., 7, ...)` 接受 7 维。实时运笔缓冲仍固定 6 元。
         */
        const val STROKE_SAMPLE_STRIDE_WITH_TIME = 7
        /**
         * 对称镜像"实时出墨"的分支上限: 每条分支要单独建一套原生 paintop, 引擎侧按 8 支封顶。
         * 对称配置产生的分支数可以超过它 (36 轴万花筒 71 支): 超出时本笔不入实时队列, 整笔在
         * 抬笔后走 replaySymmetricBranches, 落盘结果一致。每条分支 256 个 7 元采样点。
         */
        const val MIRROR_BRANCH_COUNT = 8
        const val MIRROR_BRANCH_CAPACITY = 256
        /** JNI touchStrokeMirrorBatch 返回值 (与 C++ reverie::MirrorBatchAction 映射一致)。 */
        const val MIRROR_BATCH_ACCEPTED = 1
        const val MIRROR_BATCH_REJECTED = 0
        const val MIRROR_BATCH_STALE = -1

        /**
         * 点刷家族判定: 印章/喷溅/飞溅/海绵/耙/噪点/粒子/网格/曲线/网点类预设。
         * 这类笔刷以大间距散点落墨, 连续盖印/连续假线都会失真, STAMP 与假线一律禁行。
         */
        internal fun isDottedStampFamily(presetName: String): Boolean {
            return presetName.contains("Stamp", ignoreCase = true) ||
                presetName.contains("Spray", ignoreCase = true) ||
                presetName.contains("Splat", ignoreCase = true) ||
                presetName.contains("Sponge", ignoreCase = true) ||
                presetName.contains("Rake", ignoreCase = true) ||
                presetName.contains("Noise", ignoreCase = true) ||
                presetName.contains("Particle", ignoreCase = true) ||
                presetName.contains("Grid", ignoreCase = true) ||
                presetName.contains("Curve", ignoreCase = true) ||
                presetName.contains("Screentone", ignoreCase = true)
        }

        fun resolvePredictionTier(
            predictionEnabled: Boolean,
            toolId: String,
            compositeOp: String,
            scatter: Double,
            spacing: Double,
            opacity: Double,
            flow: Double,
            textureEnabled: Boolean,
            presetGroup: String?,
            presetName: String
        ): PredictionFidelityTier {
            if (!predictionEnabled) return PredictionFidelityTier.NONE
            if (toolId != "brush" && toolId != "eraser") return PredictionFidelityTier.NONE
            if (toolId == "smudge") return PredictionFidelityTier.NONE
            if (compositeOp != "normal" && compositeOp != "erase" && compositeOp.isNotEmpty()) return PredictionFidelityTier.NONE

            // 1. 特殊打点/严重散布绝对禁止
            if (scatter > 0.15) return PredictionFidelityTier.NONE
            if (spacing > 0.40) return PredictionFidelityTier.NONE

            // 2. 极低不透明度（如极度透明的罩染）直接拦截在 NONE，防范伪线突兀
            if (opacity < 0.35) return PredictionFidelityTier.NONE

            // 2.5 预设分组判定 (STAMP 与硬拦截共用)
            val grp = presetGroup?.ifEmpty { null } ?: inferBrushGroup(presetName)

            // 2.6 STAMP: 真实笔尖戳印预览 (水彩/纹理与排线/绘画类)，仅 brush 工具。
            // v1 假线对这类笔刷是"错误反馈" (形状颜色流量全对不上)，但真实 tip 戳印
            // 在形状颜色上与真墨一致，仅湿润累积为近似 (预览只存活 1~2 帧，真墨即达覆盖)。
            // 点刷家族 (印章/喷溅/飞溅/海绵/耙/噪点/粒子/网格/曲线/网点) 除外：大间距散点笔刷
            // 连续盖印反而失真，走后方硬拦截。橡皮擦不参与 (挖除语义无法用叠加表达)。
            // tip 不可解码 (如 GIH 动画笔尖) 时渲染侧回落 TIER_2 发丝线。
            if (toolId == "brush" && !isDottedStampFamily(presetName) &&
                (grp == "水彩" || grp == "纹理与排线" || grp == "绘画" ||
                    presetName.contains("Water", ignoreCase = true) ||
                    presetName.contains("Wet", ignoreCase = true))
            ) {
                return PredictionFidelityTier.STAMP
            }

            // 3. 笔刷预设与分组检查 (硬拦截)

            // Tier 3 (NONE): 严格拦截的特征 (印章、喷溅、特效、网格、形状、水彩、色彩混合)
            // 水彩/湿墨类真机实测: 假线 (纯色/固定宽) 与真墨 (纹理/混色/低流量) 形状颜色流量全对不上,
            // "错误的早期反馈"比"无预览"更伤跟手感 (同 WET-INK-EXPERIMENT.md 的替换跳变教训),
            // 故恢复硬拦截, 保持纯真墨管线。保真预览需像素级湿墨方案, 不在此路径。
            val hardExcludedGroups = setOf("印章与喷溅", "特效与滤镜", "形状", "水彩", "混合")
            if (hardExcludedGroups.contains(grp)) return PredictionFidelityTier.NONE

            if (presetName.contains("Stamp", ignoreCase = true) ||
                presetName.contains("Spray", ignoreCase = true) ||
                presetName.contains("Splat", ignoreCase = true) ||
                presetName.contains("Sponge", ignoreCase = true) ||
                presetName.contains("Rake", ignoreCase = true) ||
                presetName.contains("Noise", ignoreCase = true) ||
                presetName.contains("Particle", ignoreCase = true) ||
                presetName.contains("Grid", ignoreCase = true) ||
                presetName.contains("Curve", ignoreCase = true) ||
                presetName.contains("Screentone", ignoreCase = true) ||
                presetName.contains("Water", ignoreCase = true) ||
                presetName.contains("Wet", ignoreCase = true) ||
                presetName.contains("Blender", ignoreCase = true) ||
                presetName.contains("Smudge", ignoreCase = true)) {
                return PredictionFidelityTier.NONE
            }

            // 4. TIER_1 严格白名单准入 (仅基础纯色勾线、圆笔、墨水、标记笔，全段空窗回填)
            val isWhitelistedGroup = grp == "基础" || grp == "勾线" || grp == "马克笔"
            val isTextureActive = textureEnabled
            val isLowFlowOrOpacity = (flow > 0.0 && flow < 0.60) || opacity < 0.60
            val isSoftOrAirbrush = grp == "喷枪" ||
                presetName.contains("Airbrush", ignoreCase = true) ||
                presetName.contains("Soft", ignoreCase = true) ||
                presetName.contains("Fuzzy", ignoreCase = true) ||
                presetName.contains("Blur", ignoreCase = true)

            if (isWhitelistedGroup && !isTextureActive && !isLowFlowOrOpacity && !isSoftOrAirbrush) {
                return PredictionFidelityTier.TIER_1
            }

            // 5. TIER_2 防御式安全降级 (铅笔、速写、纹理排线、油画绘画、带纹理、低流量及所有未知笔刷)
            // 采用发丝级导引线 (0.35x 笔宽, 上限 4dp, 低透明度), 与真墨差异最小化 ——
            // 真机结论: 加粗/提透明会让"假线与真墨对不上"变得显眼, 错误反馈比无反馈更伤跟手感
            return PredictionFidelityTier.TIER_2
        }
    }

    @Volatile internal var pendingSampleX = 0.0
    @Volatile internal var pendingSampleY = 0.0
    @Volatile internal var pendingSampleP = 1.0
    internal val strokeBatchLock = Any()
    internal val strokeBatchCoords = FloatArray(STROKE_BATCH_CAPACITY * STROKE_SAMPLE_STRIDE)
    private val strokeDrainCoords = FloatArray(STROKE_BATCH_CAPACITY * (STROKE_SAMPLE_STRIDE + 1))
    internal val strokeBatchTimes = LongArray(STROKE_BATCH_CAPACITY)
    private val strokeDrainTimes = LongArray(STROKE_BATCH_CAPACITY)
    @Volatile internal var strokeStartInputTimeMs: Long = 0L
    internal var strokeBatchCount = 0
    internal var batchOverflowLogCounter = 0 // queueStrokeMove 仅 UI 线程调用
    // ---- 对称实时镜像队列 ----
    // 镜像点由 UI 线程在 appendMirrorSample 里追加, 渲染线程独占 drain。
    // s12-F1: 每个点带落笔时分配的笔画号; 渲染线程只取"引擎当前打开的那一笔"([engineMirrorSeq])
    // 的点, 快速连笔时下一笔的点不会被上一笔收尾的 drain 画进上一笔的笔触。
    internal val mirrorQueue = MirrorSampleQueue(MIRROR_BRANCH_COUNT, MIRROR_BRANCH_CAPACITY, STROKE_SAMPLE_STRIDE_WITH_TIME)
    internal val mirrorDrainCoords = arrayOfNulls<FloatArray>(MIRROR_BRANCH_COUNT)
    internal val mirrorPushScratch = FloatArray(STROKE_SAMPLE_STRIDE_WITH_TIME) // 仅 UI 线程
    internal var mirrorOverflowLogCounter = 0
    /** 笔画号计数 (仅 UI 线程, touchStart 递增; 0 表示"没有笔画")。 */
    internal var mirrorStrokeSeqCounter = 0
    /**
     * 渲染线程: 引擎当前打开的笔画的笔画号 (touchStart 的 runCore 在 touchStrokeStart 之后设置,
     * touchEnd / touchCancel 的 runCore 在 touchStrokeEnd / touchStrokeCancel 之后清 0)。
     */
    @Volatile internal var engineMirrorSeq = 0
    /**
     * s12: 本笔镜像的实时出墨状态门 (OFF / PENDING / LIVE / FALLBACK, 见 [MirrorLiveGate])。
     * 引擎侧每个镜像分支有独立 painter + paintop, 接受后镜像与主笔同笔触实时出真墨。
     */
    internal val mirrorGate = MirrorLiveGate()
    @Volatile internal var strokeBatchQueued = false
    @Volatile internal var lastQueuedInputEventTime = 0L
    @Volatile internal var lastQueuedUptime = 0L
    // 遥测锚点: 当前待消费批首点的入队时刻。queueStrokeMove 入队时若批原本为空 (本点即
    // 首点) 记录; strokeBatchRunnable 消费时快照并复位。消费慢时 UI 线程不会覆盖已排队
    // 批的锚点, queueWait 不再因 lastQueuedUptime 被反复刷新而变成负数。
    @Volatile internal var firstQueuedUptime = 0L
    // 初值取一帧量级: 原先 40L 配合 "e2e in 5L..250L" 的更新条件, 硬笔快速运笔时实测 e2e 只有
    // 1~4ms, 永远更新不上, 延迟估计锁死在 40 + 14 = 54ms, 预览回填窗口过大 (拐角重影)。
    @Volatile var lastE2ePipelineMs: Long = 8L
    data class RenderedFrontier(
        val docX: Float,
        val docY: Float,
        val timeMs: Long
    )

    @Volatile
    var currentRenderedFrontier: RenderedFrontier? = null

    private var perfLogCounter = 0

    /** 上一批「引擎 flush + 瓦片渲染」的实际耗时 (ns), 批内合并的触发依据。只在渲染线程读写。 */
    private var lastStrokeBatchWorkNs = 0L

    /** 上一次运笔渲染结束的系统时间戳 (uptimeMillis), 用于调度节流与温控。只在渲染线程读写。 */
    private var lastStrokeRenderFinishUptimeMs = 0L
    internal var strokeThrottleRenderScheduled = false
    private var pendingFrontierDocX = 0f
    private var pendingFrontierDocY = 0f
    private var pendingFrontierSampleTime = 0L
    private var hasPendingFrontier = false

    internal val strokeThrottleRenderRunnable = Runnable {
        strokeThrottleRenderScheduled = false
        if (renderW > 0 && renderH > 0) {
            val tStart = android.os.SystemClock.elapsedRealtimeNanos()
            doRender()
            val dtNs = android.os.SystemClock.elapsedRealtimeNanos() - tStart
            lastStrokeRenderFinishUptimeMs = android.os.SystemClock.uptimeMillis()
            if (hasPendingFrontier) {
                updateRenderedFrontier(pendingFrontierDocX, pendingFrontierDocY, pendingFrontierSampleTime)
                hasPendingFrontier = false
            }
            reportRenderWorkDuration(dtNs)
        }
    }

    internal val strokeBatchRunnable: Runnable = Runnable {
        val tDispatch = android.os.SystemClock.uptimeMillis()
        strokeBatchQueued = false
        val n: Int
        var firstQueued = 0L
        var hasMore = false
        val timeBaseOffset = STROKE_BATCH_CAPACITY * STROKE_SAMPLE_STRIDE
        synchronized(strokeBatchLock) {
            val total = strokeBatchCount
            val drainCount = if (total > 16) 16 else total
            n = drainCount
            firstQueued = firstQueuedUptime
            if (drainCount > 0) {
                System.arraycopy(strokeBatchCoords, 0, strokeDrainCoords, 0, drainCount * STROKE_SAMPLE_STRIDE)
                System.arraycopy(strokeBatchTimes, 0, strokeDrainTimes, 0, drainCount)
                val remaining = total - drainCount
                if (remaining > 0) {
                    System.arraycopy(strokeBatchCoords, drainCount * STROKE_SAMPLE_STRIDE, strokeBatchCoords, 0, remaining * STROKE_SAMPLE_STRIDE)
                    System.arraycopy(strokeBatchTimes, drainCount, strokeBatchTimes, 0, remaining)
                    strokeBatchCount = remaining
                    hasMore = true
                } else {
                    strokeBatchCount = 0
                    firstQueuedUptime = 0L
                }
                if (strokeStartInputTimeMs <= 0L && strokeDrainTimes[0] > 0L) {
                    strokeStartInputTimeMs = strokeDrainTimes[0]
                }
                val baseMs = strokeStartInputTimeMs
                for (i in 0 until drainCount) {
                    val tMs = strokeDrainTimes[i]
                    strokeDrainCoords[timeBaseOffset + i] =
                        if (baseMs > 0L && tMs >= baseMs) (tMs - baseMs).toFloat() / 1000f else -1f
                }
            } else {
                strokeBatchCount = 0
                firstQueuedUptime = 0L
            }
        }
        pendingCoreOps.decrementPositive()
        val tKritaStartNs = android.os.SystemClock.elapsedRealtimeNanos()
        val painted = n > 0 && try {
            ReverieCoreBridge.touchStrokeMoveBatch(strokeDrainCoords, n)
        } catch (_: Throwable) {
            false
        }
        // 镜像折线必须画在主笔同一批之后、doRender 之前。主笔这一批没落墨时也要 drain。
        // 不为镜像单独再开一次 doRender。
        val mirrorPainted = drainMirrorBatchesOnRenderThread()
        var dtKritaNs = android.os.SystemClock.elapsedRealtimeNanos() - tKritaStartNs
        var dtRenderNs = 0L

        // ---- 批内合并 (渲染线程) ----
        // 上一批 (引擎 flush + 瓦片渲染) 超过一整帧 16ms 预算说明渲染线程已落后于手写采样, 队列里又积了
        // 新样本。与其让它们各自触发一轮完整的 doRender (compositeDirtyRegions + 瓦片拷贝 + 前缓冲贴图),
        // 不如在这里并进同一批, 只做一轮 doRender。
        // 根据当前调度策略 (RenderScheduleMode) 设定合并阈值与单次吸纳上限。
        var lastDocX = if (n > 0) strokeDrainCoords[(n - 1) * STROKE_SAMPLE_STRIDE] else 0f
        var lastDocY = if (n > 0) strokeDrainCoords[(n - 1) * STROKE_SAMPLE_STRIDE + 1] else 0f
        var lastSampleTime = if (n > 0) strokeDrainTimes[n - 1] else 0L
        var merged = 0
        val maxMergeSamples = renderScheduleMode.maxStrokeMergeSamples
        if (painted && n in 1 until maxMergeSamples &&
            lastStrokeBatchWorkNs > STROKE_MERGE_TRIGGER_MS * 1_000_000L
        ) {
            var extra = 0
            val maxExtra = minOf(
                if (renderScheduleMode == com.reverie.paint.model.RenderScheduleMode.POWER_SAVER) 16 else 8,
                maxMergeSamples - n,
            )
            synchronized(strokeBatchLock) {
                if (strokeBatchCount in 1..maxExtra) {
                    extra = strokeBatchCount
                    System.arraycopy(strokeBatchCoords, 0, strokeDrainCoords, 0, extra * STROKE_SAMPLE_STRIDE)
                    System.arraycopy(strokeBatchTimes, 0, strokeDrainTimes, 0, extra)
                    strokeBatchCount = 0
                    firstQueuedUptime = 0L // 只清不读: firstQueued 已在主 drain 锁内先读后清
                    hasMore = false
                    val baseMs = strokeStartInputTimeMs
                    for (i in 0 until extra) {
                        val tMs = strokeDrainTimes[i]
                        strokeDrainCoords[timeBaseOffset + i] =
                            if (baseMs > 0L && tMs >= baseMs) (tMs - baseMs).toFloat() / 1000f else -1f
                    }
                }
            }
            if (extra > 0) {
                lastDocX = strokeDrainCoords[(extra - 1) * STROKE_SAMPLE_STRIDE]
                lastDocY = strokeDrainCoords[(extra - 1) * STROKE_SAMPLE_STRIDE + 1]
                lastSampleTime = strokeDrainTimes[extra - 1]
                val tMergeStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                try {
                    ReverieCoreBridge.touchStrokeMoveBatch(strokeDrainCoords, extra)
                } catch (_: Throwable) {
                    // 与首批一致: 引擎异常不影响本帧照常渲染已落墨的部分
                }
                dtKritaNs += android.os.SystemClock.elapsedRealtimeNanos() - tMergeStartNs
                merged = extra
            }
        }
        val dtKrita = dtKritaNs / 1_000_000L

        // Render immediately in-place after ink landed to eliminate message queue roundtrip latency.
        // 调度温控：若开启节流且距上一帧渲染完成间隔过短，暂缓 doRender() 并在窗口期满时统一输出，
        // 防止高刷屏上过度触发瓦片合成导致 CPU/GPU 发热满载。
        // 关键不变量：若前序切片因追赶合帧被节流 (strokeThrottleRenderScheduled == true)，
        // 当排空至最后一个切片 (!hasMore) 时，即便本切片未产出新墨 (painted == false)，也必须进入渲染分支立即冲刷积压脏区！
        if (painted || mirrorPainted || (!hasMore && strokeThrottleRenderScheduled)) {
            if (n > 0 || merged > 0) {
                pendingFrontierDocX = lastDocX
                pendingFrontierDocY = lastDocY
                pendingFrontierSampleTime = lastSampleTime
                hasPendingFrontier = true
            }

            val minIntervalMs = renderScheduleMode.minRenderIntervalMs
            // 追赶合帧门控：当队列仍有积压切片 (hasMore == true) 时，按 120Hz 帧周期 (8ms) 合并中间瓦片合成；
            // 一旦排空至最后一个切片 (hasMore == false)，恢复原模式间隔 (LOW_LATENCY 下为 0ms 立即上屏)。
            val effectiveIntervalMs = if (hasMore) {
                maxOf(minIntervalMs, 8L)
            } else {
                minIntervalMs
            }
            val nowMs = android.os.SystemClock.uptimeMillis()
            val elapsed = nowMs - lastStrokeRenderFinishUptimeMs
            val shouldThrottle = effectiveIntervalMs > 0L &&
                elapsed < effectiveIntervalMs &&
                (hasMore || !realInkHeld())

            if (!shouldThrottle) {
                if (strokeThrottleRenderScheduled) {
                    renderHandler?.removeCallbacks(strokeThrottleRenderRunnable)
                    strokeThrottleRenderScheduled = false
                }
                val tRenderStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                doRender()
                lastStrokeRenderFinishUptimeMs = android.os.SystemClock.uptimeMillis()
                dtRenderNs = android.os.SystemClock.elapsedRealtimeNanos() - tRenderStartNs
                val dtRender = dtRenderNs / 1_000_000L
                val tDone = android.os.SystemClock.uptimeMillis()
                val queueWait = tDispatch - (if (firstQueued > 0L) firstQueued else lastQueuedUptime)
                val e2e =
                    if (lastQueuedInputEventTime > 0) (tDone - lastQueuedInputEventTime).coerceAtLeast(1L) else 0L
                if (e2e in 1L..250L) {
                    lastE2ePipelineMs = ((lastE2ePipelineMs * 2L) + e2e) / 3L
                }
                if (hasPendingFrontier) {
                    updateRenderedFrontier(pendingFrontierDocX, pendingFrontierDocY, pendingFrontierSampleTime)
                    hasPendingFrontier = false
                }

                val perfTick = (++perfLogCounter % 5 == 0)
                if (perfTick || e2e >= 30L || dtKrita >= 16L || dtRender >= 16L) {
                    StrokeLatencyDiag.recordStrokePerf(
                        n = n,
                        merged = merged,
                        modeId = renderScheduleMode.id,
                        queueWaitMs = queueWait,
                        kritaMs = dtKrita,
                        renderMs = dtRender,
                        e2eMs = e2e,
                        effectiveDelayMs = effectivePipelineDelayMs,
                        presentWaitP50Ms = PerfTrace.presentWaitP50Ms,
                        frontierErrPx = PerfTrace.frontierErrP50Px,
                        realInkHeld = realInkHeld(),
                        uptimeMs = tDone,
                    )
                }
                if (perfTick) {
                    android.util.Log.i(
                        "ReveriePerf",
                        "StrokePerf: n=$n merged=$merged mode=${renderScheduleMode.name} queueWait=${queueWait}ms krita=${dtKrita}ms " +
                            "render=${dtRender}ms e2e=${e2e}ms effectiveDelay=${effectivePipelineDelayMs}ms " +
                            "presentWait=${PerfTrace.presentWaitP50Ms}ms " +
                            "frontierErr=${"%.1f".format(PerfTrace.frontierErrP50Px)}px"
                    )
                }
            } else {
                if (!strokeThrottleRenderScheduled) {
                    strokeThrottleRenderScheduled = true
                    val delay = (effectiveIntervalMs - elapsed).coerceIn(1L, effectiveIntervalMs)
                    renderHandler?.postDelayed(strokeThrottleRenderRunnable, delay)
                }
            }
        }
        lastStrokeBatchWorkNs = dtKritaNs + dtRenderNs
        if (n > 0) {
            reportRenderWorkDuration(dtKritaNs + dtRenderNs)
        }
        if (hasMore && !strokeBatchQueued) {
            strokeBatchQueued = true
            pendingCoreOps.incrementAndGet()
            renderHandler?.post(strokeBatchRunnable)
        }
    }

    // Airbrush hold-still ink flow: a self-rescheduling timer on the render
    // handler; every tick paints one dab via strokeAirbrushTick (engine-side,
    // same thread contract as queueStrokeMove). Ticks are not recorded into
    // the stroke event stream (known replay limitation).
    @Volatile internal var airbrushActive = false
    internal var airbrushIntervalMs = 950L
    internal val airbrushRunnable: Runnable = Runnable {
        if (!airbrushActive) return@Runnable
        val h = renderHandler ?: return@Runnable
        pendingCoreOps.incrementAndGet()
        val painted = try {
            ReverieCoreBridge.strokeAirbrushTick()
        } catch (_: Throwable) {
            false
        }
        pendingCoreOps.decrementPositive()
        if (painted) {
            scheduleRender()
        }
        h.postDelayed(airbrushRunnable, airbrushIntervalMs)
    }

    // Pen-down instant-ink kick: shortly after touchStrokeStart, if the stylus
    // has not moved yet, flush the start point as an ink dot so holding still
    // or a slow stroke start shows ink immediately instead of nothing until
    // pen-up. The engine side no-ops once the stroke moved, so fast strokes
    // never pay the deferred-undo-snapshot cost this avoids.
    internal val strokeStartKickRunnable = Runnable {
        val painted = try {
            ReverieCoreBridge.touchStrokeKickIdle()
        } catch (_: Throwable) {
            false
        }
        // s12: 落笔不动时镜像起笔点也要同帧出墨 (镜像分支实时 paintop), 否则镜像一侧要等第一批运笔。
        val mirrorPainted = drainMirrorBatchesOnRenderThread()
        if (painted || mirrorPainted) {
            scheduleRender()
        }
    }

    @Volatile internal var liquifyPresentationGesture = 0L
    internal val liquifyPresentation = com.reverie.paint.model.LiquifyPresentationFence<Bitmap>()

    internal var renderDeferCount = 0

    /** 渲染线程正在执行 [doRender] (L-02 "渲染空闲"推断信号的一部分)。 */
    @Volatile internal var renderInFlight = false

    /** 主线程上的画布失效 (预建 Runnable, 每帧投递零分配)。 */
    internal val postedCanvasInvalidate = Runnable {
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.invalidateFromRender()
    }

    var layers by mutableStateOf(listOf<LayerUiState>())
        internal set

    internal val layerOpacityDragStarts = mutableMapOf<Int, Double>()

    var currentLayerIndex by mutableIntStateOf(-1)
        internal set

    // ---- 3D Reference Layer State ----
    var active3DLayers by mutableStateOf<Map<Long, com.reverie.paint.model.Model3DState>>(emptyMap())
    var active3DInteractionMode by mutableStateOf(com.reverie.paint.model.Interaction3DMode.ROTATE)
    var active3DSelectedJointName by mutableStateOf<String?>(null)
    /** 骨骼拖拽锁定的旋转轴: -1 = 未锁定, 0 = 俯仰环(X), 1 = 偏转环(Y) */
    var active3DBoneAxis by mutableStateOf(-1)
    /**
     * 关节位置微调模式: 打开后画布上拖拽选中的关节 = 移动它的位置 (竖直), 而不是旋转。
     * 用于修正自动绑定的关节点偏差 —— 用户直接看着网格拖, 比数值滑杆直观得多。
     * 状态不进作品文件 (纯编辑期开关), 导出走 [com.reverie.paint.perf.JointOffsetExport]。
     */
    var jointPositionEditMode by mutableStateOf(false)
    var live3DPreviewBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    var isInteracting3D by mutableStateOf(false)
    var show3DModelPicker by mutableStateOf(false)
    var show3DPosePanel by mutableStateOf(false)
    val gl3dRenderer = com.reverie.paint.core.gl3d.Gl3dRenderer()
    var undo3DStack by mutableStateOf<List<com.reverie.paint.model.Model3DState>>(emptyList())
        internal set
    var redo3DStack by mutableStateOf<List<com.reverie.paint.model.Model3DState>>(emptyList())
        internal set

    internal fun isAppContextReady(): Boolean = ::appContext.isInitialized

    var currentProjectFile by mutableStateOf<String?>(null)

    /**
     * 待绘画页单次消费的工程画布方向；null 走默认横向（0°），新建工程与回主页时清掉，绝不串工程。
     */
    var pendingCanvasOrientation by mutableStateOf<CanvasOrientation?>(null)
    var isBlockingLoading by mutableStateOf(false)
    var blockingLoadingMessage by mutableStateOf("")
    /** 顶层画廊↔画布扭曲波转场宿主 (仅由 DistortionWaveStage 绑定与释放, 不反向依赖 ui/) */
    var waveTransitionHost: GalleryWaveTransitionHost? = null
    /** 当前页切换是否由扭曲波托管 (托管时 AnimatedContent 瞬切, 避免双层叠动画与缩放出画布) */
    var isWaveTransitionManaged by mutableStateOf(false)
    /** 转场蓄力等待首帧或退回截屏期间临时隐藏绘画页居中加载弹窗 */
    var suppressPaintingLoadingOverlay by mutableStateOf(false)
    /** 绘画页工具栏/顶栏/常驻浮窗是否允许入场显现 (扭曲波扫屏期间先露纯净画布, 扫过后与沉浸态就绪同步丝滑滑入) */
    var paintingChromeRevealed by mutableStateOf(true)
    /** 返回画廊波纹期间跳过画廊卡片首屏入场级联, 保持画面连贯不闪跳 */
    var suppressGalleryEntranceOnce by mutableStateOf(false)
    /** 最近一次从画廊打开/保存的工程路径与卡片根坐标, 供返回画廊收拢波定位锚点 */
    var lastOpenedProjectPath: String? = null
    var lastOpenedCardBoundsPx: com.reverie.paint.model.WaveRect? = null
    /** loadProject 异步完成结果版本戳与成功标记 (供转场状态机可靠感知加载成败) */
    var loadProjectOutcomeVersion by mutableIntStateOf(0)
        internal set
    var lastLoadProjectSucceeded by mutableStateOf(true)
        internal set
    var currentFolder by mutableStateOf<com.reverie.paint.model.Project?>(null)
    var searchQuery by mutableStateOf("")
    var pendingExternalImageUri by mutableStateOf<android.net.Uri?>(null)
    var pendingExternalBrushUris by mutableStateOf<List<android.net.Uri>?>(null)
    var isDraggingExternal by mutableStateOf(false)

    /** Injected by MainActivity; the engine needs it for file paths. */
    lateinit var appContext: android.content.Context

    /**
     * 统一的偏好文件访问器 (MODE_PRIVATE)。
     *
     * 原先 180 处散落的 `getSharedPreferences("paint_prefs", ...)` 字面量: 文件名拼错一处
     * 就会静默丢设置。集中到这里, 语义与逐处直取完全等价 (framework 按名缓存实例)。
     */
    internal val prefs: android.content.SharedPreferences
        get() = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)


    var selectionMask: ByteArray? by mutableStateOf(null)
    var hasSelection by mutableStateOf(false)

    /** Lasso clipboard pastes into the currently selected layer and animation cel. */
    internal var selectionClipboard: android.graphics.Bitmap? = null
    internal var selectionClipboardX = 0
    internal var selectionClipboardY = 0
    var selectionClipboardAvailable by mutableStateOf(false)

    // Semi-transparent blue overlay bitmap built from the mask, drawn on top
    // of the canvas so the user can see the active selection (Krita-style)
    var selectionOverlayBitmap: android.graphics.Bitmap? by mutableStateOf(null)

    // Vector outline path of the active selection (document coords centered at origin)
    var selectionOutlinePath: androidx.compose.ui.graphics.Path? by mutableStateOf(null)
    @Volatile internal var pendingSelectionOutlinePath: androidx.compose.ui.graphics.Path? = null

    /** 当前笔刷的归一化轮廓多边形集合 (每个多边形为 FloatArray: [x0, y0, x1, y1, ...], 坐标在 [-1, 1]) */
    var brushOutlinePolygons: List<FloatArray>? by mutableStateOf(null)

    var transformPreviewBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var transformPreviewPending: Boolean by mutableStateOf(false)
    internal var transformPreviewGen: Int = 0
    var transformCancelHook: (() -> Unit)? = null
    /** 变形会话内逐手势撤销钩子。返回 true 表示已消费本次撤销做会话弹栈。 */
    var transformSessionUndoHook: (() -> Boolean)? = null
    var transformCopyOnly: Boolean by mutableStateOf(false)
    var isImportTransformPending: Boolean by mutableStateOf(false)
    var isSelectionTransformPending: Boolean by mutableStateOf(false)
    var transformSessionToken: Int = 0
    var transientTransformSelectionPending: Boolean by mutableStateOf(false)
    var canUndoTransformSelection: Boolean by mutableStateOf(false)
    var canRedoTransformSelection: Boolean by mutableStateOf(false)
    internal val layerThumbStates = mutableStateMapOf<Int, Bitmap>()

    // Name-keyed mirror: layer indices change on every move/group op, so the
    // index cache goes empty right after a drag and the rows flash blank until
    // the 400ms-throttled refresh lands. Names are stable across moves, so a
    // by-name lookup keeps thumbnails visible (this is the drag flicker fix)
    internal val layerThumbByName = mutableStateMapOf<String, Bitmap>()
    internal val layerThumbIndexName = mutableStateMapOf<Int, String>()

    /** Layer thumbnails keyed by layer index (updated on the render thread). */
    val layerThumbs: Map<Int, Bitmap> = layerThumbStates
    internal var lastThumbRefreshNs = 0L
    internal var pendingThumbLayerIds: MutableSet<Long>? = null
    internal var pendingThumbRefreshAll: Boolean = false
    val blendModes =
        listOf(
            "normal" to "正常",
            "erase" to "擦除",
            "darken" to "变暗",
            "multiply" to "正片叠底",
            "burn" to "颜色加深",
            "linear_burn" to "线性加深",
            "lighten" to "变亮",
            "screen" to "滤色",
            "dodge" to "颜色减淡",
            "linear_dodge" to "线性减淡",
            "add" to "增加",
            "luminosity_sai" to "发光 (SAI)",
            "glow" to "发光",
            "overlay" to "叠加",
            "soft_light" to "柔光",
            "hard_light" to "强光",
            "vivid_light" to "亮光",
            "pin_light" to "点光",
            "linear light" to "线性光",
            "difference" to "差值",
            "exclusion" to "排除",
            "subtract" to "减去",
            "divide" to "划分",
            "hue" to "色相",
            "saturation" to "饱和度",
            "color" to "颜色",
            "value" to "明度",
        )
    var pickerCurrentLayerOnly by mutableStateOf(false)
    var isFilterAdjustActive by mutableStateOf(false)

    internal var filterPreviewJob: kotlinx.coroutines.Job? = null

    // ---- Replay (延时摄影回放) ----
    var replayProject by mutableStateOf<com.reverie.paint.model.Project?>(null)
        internal set
}

enum class Page { HOME, CREATE, PAINTING, REPLAY }

/**
 * 图层组折叠状态的键：用层的稳定 id，而不是名字。
 * 同名组是合法状态（复制组、手动重名），按名字存会让它们共享同一个折叠开关。
 */
internal fun groupCollapseKey(id: Long): String = "g$id"
