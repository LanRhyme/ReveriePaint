/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.StringRes
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
private const val STROKE_BATCH_CAPACITY = 256

/** Delay before the stroke-start idle kick flushes a pen-down dot. */
private const val STROKE_START_KICK_MS = 24L

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
    var docName by mutableStateOf("Untitled")
    var totalStrokes by mutableIntStateOf(0)
    var initialStrokeCount by mutableIntStateOf(0)
    var isModified by mutableStateOf(false)
    var elapsedSeconds by mutableLongStateOf(0L)
    var canvasCreatedTime by mutableLongStateOf(System.currentTimeMillis())
    var colorMode by mutableStateOf("RGB 8位 (sRGB)")

    // Active drawing duration tracking (1-minute idle threshold)
    internal var lastActiveTimeMs = 0L
    internal var lastTickTimeMs = 0L
    internal var activeMillisAccumulator = 0L
    internal val IDLE_THRESHOLD_MS = 60_000L
    internal var timerJob: Job? = null

    fun onPaintingActivity() {
        val now = System.currentTimeMillis()
        if (lastActiveTimeMs == 0L || (now - lastActiveTimeMs) > IDLE_THRESHOLD_MS) {
            // Start or resume active drawing session
            lastActiveTimeMs = now
            lastTickTimeMs = now
        } else {
            // Continuously painting within 1-minute window
            lastActiveTimeMs = now
        }
    }

    internal fun tickPaintingTimer() {
        val now = System.currentTimeMillis()
        if (lastActiveTimeMs > 0L) {
            val deltaFromActive = now - lastActiveTimeMs
            if (deltaFromActive <= IDLE_THRESHOLD_MS) {
                // Within 1-minute active window
                if (lastTickTimeMs > 0L) {
                    val dt = (now - lastTickTimeMs).coerceAtLeast(0L)
                    if (dt in 1..2000) {
                        activeMillisAccumulator += dt
                        val addSecs = activeMillisAccumulator / 1000L
                        if (addSecs > 0) {
                            elapsedSeconds += addSecs
                            activeMillisAccumulator %= 1000L
                        }
                    }
                }
                lastTickTimeMs = now
            } else {
                // Idle over 1 minute: pause timer
                lastTickTimeMs = now
            }
        } else {
            lastTickTimeMs = now
        }
    }

    internal fun startPaintingTimer() {
        if (lastAutoSaveTimeMs == 0L) {
            lastAutoSaveTimeMs = android.os.SystemClock.elapsedRealtime()
        }
        timerJob?.cancel()
        timerJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(1000L)
                    if (currentPage == Page.PAINTING) {
                        tickPaintingTimer()
                        checkAutoSave()
                        // 标尺开启时顺带取一次保存/液化的引擎侧统计 (关闭时只剩一次布尔判断)。
                        // 必须走 runCore: 架构铁律要求引擎调用不经 UI 线程。这两条 JNI 只读
                        // C++ 侧的 relaxed 原子量、不牵动渲染, 所以 render = false。
                        if (PerfTrace.enabled) {
                            runCore(render = false) {
                                pollSaveStats()
                                pollLiquifyStats()
                                if (PerfHud.gridOverlayEnabled) pollLiquifyGrid()
                            }
                        }
                    }
                }
            }
    }

    internal fun stopPaintingTimer() {
        tickPaintingTimer()
        timerJob?.cancel()
        timerJob = null
        lastActiveTimeMs = 0L
        lastTickTimeMs = 0L
        activeMillisAccumulator = 0L
    }

    internal fun checkAutoSave() {
        if (!autoSaveEnabled || isAutoSaving || isBlockingLoading) return
        if (currentPage != Page.PAINTING) return
        if (!hasUnsavedChanges()) return

        // 避免在用户正在落笔绘画或多指手势时保存，确保绘画热路径零干扰
        val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (touchView?.isInteracting == true || touchView?.isTransformActive == true) return

        // 无感化门控: 引擎队列非空 (笔画/滤镜/撤销在途) 或刚抬笔不久时不触发,
        // 防止 saveRevp 的秒级序列化排在下一笔前面造成卡顿/撤销迟滞。
        if (pendingCoreOps.get() > 0) return
        if (filterPreviewJob?.isActive == true) return
        val sinceStrokeEnd = android.os.SystemClock.elapsedRealtime() - lastStrokeEndElapsedMs
        if (lastStrokeEndElapsedMs != 0L && sinceStrokeEnd < 4_000L) return

        val now = android.os.SystemClock.elapsedRealtime()
        val intervalMs = autoSaveIntervalMinutes * 60 * 1000L
        if (lastAutoSaveTimeMs == 0L) {
            lastAutoSaveTimeMs = now
            return
        }
        if (now - lastAutoSaveTimeMs >= intervalMs) {
            autoSaveProject()
        }
    }

    fun onAppBackgrounded() {
        // 后台不再每秒唤醒: 计时与自动保存都只在绘画页前台有意义。
        // 必须放在下面几个早退之前 —— 否则"无未保存改动"时计时器会一直留在后台跑。
        stopPaintingTimer()
        if (!autoSaveEnabled || isAutoSaving || isBlockingLoading) return
        if (currentPage != Page.PAINTING) return
        if (!hasUnsavedChanges()) return

        // 软件切入后台时，立即触发后台静默自动保存
        autoSaveProject()
    }

    /** 回到前台: 若仍停在绘画页, 恢复每秒计时与自动保存唤醒。 */
    fun onAppForegrounded() {
        if (currentPage == Page.PAINTING) startPaintingTimer()
    }

    /** ElapsedRealtime of the last stroke end; autosave defers for a quiet window after it. */
    @Volatile internal var lastStrokeEndElapsedMs = 0L

    fun markModified() {
        isModified = true
    }

    fun hasUnsavedChanges(): Boolean {
        val strokesAdded = totalStrokes > initialStrokeCount
        return isModified || strokesAdded || ReverieCoreBridge.canUndo()
    }

    // Auto-Save & General Settings state
    var autoSaveEnabled by mutableStateOf(true)
    var autoSaveIntervalMinutes by mutableIntStateOf(5)
    var autoSaveToastEnabled by mutableStateOf(true)

    /**
     * 性能标尺 (画布左上角实时显示渲染路径/纹理重传/保存阶段耗时)。见 [PerfTrace]。
     * 与 `setprop debug.reverie.perf 1` 任一为真即为开 (后者方便现场量测, 不写偏好)。
     */
    var perfHudEnabled by mutableStateOf(false)

    fun updatePerfHudEnabled(on: Boolean) {
        perfHudEnabled = on
        PerfTrace.enabled = on || PerfTrace.isEnabledByProp
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("perfHud", on).apply()
        }
    }

    /**
     * 取一次引擎侧"上一次保存"的阶段统计 (仅标尺开启时调用, 每秒一次)。保存慢的时候
     * 必须能看清是慢在快照、PNG 编码还是写盘, 否则只能盲改。
     *
     * **必须在引擎线程调用**(调用点见 `startPaintingTimer` 里的 `runCore`): 虽然 C++ 侧只做
     * `relaxed` 原子量读取、不触碰文档与投影, 但"引擎调用不经 UI 线程"是架构铁律, 不做例外。
     */
    internal fun pollSaveStats() {
        if (!PerfTrace.enabled) return
        val s = ReverieCoreBridge.revpSaveStats() ?: return
        if (s.size < 8) return
        if (s[0] <= 0L) return // 还没保存过
        PerfTrace.saveStats(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7] != 0L)
    }

    /**
     * 取一次引擎侧"上一次液化 apply"的分段耗时 (仅标尺开启时调用, 每秒一次)。
     * 四段为 形变(Krita 网格) / 补洞(内存流量) / 回写图层 / 投影合成 —— 用来确定
     * "液化大笔刷卡顿"下一步该优化哪一段, 而不是凭感觉加线程。
     *
     * **必须在引擎线程调用**(调用点见 `startPaintingTimer` 里的 `runCore`)。
     */
    internal fun pollLiquifyStats() {
        if (!PerfTrace.enabled) return
        val s = ReverieCoreBridge.liquifyStats() ?: return
        if (s.size < 10 || s[7] <= 0L) return // 还没做过液化
        PerfTrace.liquifyApply(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9])
    }

    /**
     * 取一次液化网格快照 (仅"网格可视化"打开时调用, 每秒一次; **引擎线程**)。
     * 摘要进标尺 HUD 第 5 行, 原始网格交给 `PerfHud` 叠加绘制 —— 正式版里两者都不存在。
     */
    internal fun pollLiquifyGrid() {
        val g = ReverieCoreBridge.liquifyGrid() ?: return
        if (g.size < 8) return
        val count = g[7].toInt()
        if (count <= 0) return
        PerfTrace.liquifyGrid(g[4].toInt(), g[5].toInt(), g[6].toInt(), count, g)
        PerfHud.setLiquifyGrid(g)
    }

    // Phase 2B: 上次取到的预览版本与裁剪指纹(只在引擎线程读写, 无需加锁)
    private var lqGpuPreviewSeq = -1L
    private var lqGpuCropKey = Long.MIN_VALUE

    /**
     * Phase 2B: 取一次 AGSL 预览所需的"源裁剪 + 位移网格" (**引擎线程**, 调用点见 `doRender`)。
     *
     * 只有 GPU 诊断开关打开时才有内容, 且引擎此时既不生成也不叠加 CPU 预览:
     *  - 源裁剪(未形变)只在 rebase 时变 ⇒ 整段手势只上传一次纹理;
     *  - 位移网格每个 dab 都变 ⇒ 每次取(6~25KB 级小数组, 不做差分)。
     */
    internal fun pollLiquifyGpuPreview() {
        if (!LiquifyGpuPreview.requested) return
        val crop = ReverieCoreBridge.liquifyPreviewSourceMeta() ?: return
        if (crop.size < 7) return
        if (crop[0] <= 0) {
            // 没有源裁剪(超出面积预算 / 非 8bit BGRA / 手势结束): 摘掉覆盖层, 由引擎侧
            // CPU 预览兜底 —— 不能两边都不画
            LiquifyGpuPreview.clear()
            return
        }
        val seq = crop[6].toLong()
        if (seq == lqGpuPreviewSeq) return
        lqGpuPreviewSeq = seq
        val key = LiquifyGpuPreview.cropKeyOf(crop)
        val src =
            if (key != lqGpuCropKey) {
                lqGpuCropKey = key
                ReverieCoreBridge.liquifyPreviewSourcePixels()
            } else {
                null
            }
        LiquifyGpuPreview.update(crop, src, ReverieCoreBridge.liquifyGrid())
        // 主机侧绘制模式下引擎不写显示缓冲(没有脏区), 所以"该重绘了"必须由这里发起。
        // 手势期间 canPartialInvalidate 恒为 false, postInvalidate() 与既有语义一致。
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.postInvalidate()
    }
    // 无 UI 读者: 保持普通字段, 避免每次自动保存触发 Compose 快照写入
    var isAutoSaving = false
    var lastAutoSaveTimeMs by mutableLongStateOf(0L)
    var maxUndoSteps by mutableIntStateOf(50)
    var promptSaveOnExit by mutableStateOf(true)

    // Brush state
    var brushSize by mutableDoubleStateOf(20.0)
    var brushColor by mutableStateOf("#000000")
    var brushSecondaryColor by mutableStateOf("#ffffff")
    var brushOpacity by mutableDoubleStateOf(1.0)
    var brushPresets by mutableStateOf<List<BrushPresetInfo>>(emptyList())
    var brushPresetIndex by mutableIntStateOf(-1)

    // User-defined brush groups: preset name -> group name; and the list of
    // custom group names the user created (persisted in SharedPreferences)
    var userBrushGroups by mutableStateOf<Map<String, String>>(emptyMap())
    var customBrushGroups by mutableStateOf<List<String>>(emptyList())

    // Custom display order of presets (persisted); empty = default (sorted)
    var brushOrder by mutableStateOf<List<String>>(emptyList())
    var categoryOrder by mutableStateOf<List<String>>(emptyList())
    var brushFlow by mutableDoubleStateOf(1.0)
    var brushSpacing by mutableDoubleStateOf(0.1)
    var brushAngle by mutableDoubleStateOf(0.0)
    var brushScatter by mutableDoubleStateOf(0.0)
    var brushFade by mutableDoubleStateOf(0.0)
    var brushSoftness by mutableDoubleStateOf(0.5)
    var brushRatio by mutableDoubleStateOf(1.0)
    var brushSharpness by mutableDoubleStateOf(0.0)
    var brushRotation by mutableDoubleStateOf(0.0)
    var brushCompositeOp by mutableStateOf("normal")

    // 上一次使用的工具 (toggle_last_tool 快捷键用)
    var lastToolId by mutableStateOf("brush")
    /** 是否是由手写笔侧键或快捷键临时激活的吸管工具 (取色完成后自动切回上一工具) */
    var isTemporaryPicker by mutableStateOf(false)

    fun restorePreviousTool() {
        if (!isTemporaryPicker) return
        isTemporaryPicker = false
        val prev = lastToolId.ifEmpty { "brush" }
        applyTool(if (prev == "picker") "brush" else prev)
    }
    // One-shot UI 命令: 视口缩放/旋转与面板开关状态在 PaintingPage 本地,
    // VM 只发命令 token, UI 侧 LaunchedEffect 消费 (见 PaintingPage)。
    // 不能 private set —— 同包扩展函数 (requestUiCommand) 需要写入
    var uiCommandTick by mutableIntStateOf(0)
    var pendingUiCommand by mutableStateOf<String?>(null)

    // Extended brush studio properties
    var brushAntiAliasing by mutableIntStateOf(1) // 0: 无, 1: 正常, 2: 强化, 3: 分级
    var brushTipShape by mutableIntStateOf(0) // 0: 圆形笔触, 1: 方形笔触
    var brushRandomFlipX by mutableStateOf(false)
    var brushRandomFlipY by mutableStateOf(false)
    var brushFollowDirection by mutableStateOf(false)
    var brushStreamline by mutableDoubleStateOf(0.0)
    var brushTaper by mutableDoubleStateOf(0.0)
    var brushTextureEnabled by mutableStateOf(false)
    var brushTextureScale by mutableDoubleStateOf(1.0)
    var brushTextureStrength by mutableDoubleStateOf(0.5)
    var brushTextureMode by mutableStateOf("multiply")
    var brushHueJitter by mutableDoubleStateOf(0.0)
    var brushSatJitter by mutableDoubleStateOf(0.0)
    var brushValJitter by mutableDoubleStateOf(0.0)
    var brushSecondaryMix by mutableDoubleStateOf(0.0)
    var brushPressureColorMix by mutableStateOf(false)
    var brushPressureEnabled by mutableStateOf(true)
    var brushPressureSize by mutableDoubleStateOf(1.0)
    var brushPressureOpacity by mutableDoubleStateOf(1.0)
    var brushPressureFlow by mutableDoubleStateOf(1.0)
    var brushSpeedSize by mutableDoubleStateOf(0.0)
    var brushPressureCurve by mutableIntStateOf(0) // 0: 线性, 1: 柔和, 2: 硬朗, 3: S型
    var brushMinSizeLimit by mutableDoubleStateOf(1.0)
    var brushMaxSizeLimit by mutableDoubleStateOf(500.0)
    var brushTipAsset by mutableStateOf("")
    var brushPaintOpId by mutableStateOf("defaultpaintop")
    var brushAirbrush by mutableStateOf(false)
    var brushAirbrushRate by mutableDoubleStateOf(30.0)
    var brushSmudgeRate by mutableDoubleStateOf(0.5)
    var brushSmudgeLength by mutableDoubleStateOf(0.5)
    var brushSpikes by mutableIntStateOf(2)
    var brushJitterAngle by mutableDoubleStateOf(0.0)
    var brushJitterSize by mutableDoubleStateOf(0.0)

    // Metadata properties
    var brushAuthor by mutableStateOf("ReveriePaint")
    var brushIsAuthorLocked by mutableStateOf(false)
    var brushDescription by mutableStateOf("")
    var brushVersion by mutableStateOf("1.0")

    // Per-preset independent size/opacity/flow (persisted). Switching presets
    // restores that brush's own values; adjusting a slider only affects the
    // current brush.
    internal val brushParams = mutableStateMapOf<String, BrushParams>()

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
    var brushPanelGridView by mutableStateOf(false)
    var favoriteBrushNames by mutableStateOf<Set<String>>(emptySet())
    var recentBrushNames by mutableStateOf<List<String>>(emptyList())
    var settingsPanelOpen by mutableStateOf(false)
    var targetSettingsTab by mutableStateOf<String?>(null)
    var targetExportAnimation by mutableStateOf(false)
    var layerRevision by mutableStateOf(0)

    fun toggleFavoriteBrush(name: String) {
        if (name.isBlank()) return
        favoriteBrushNames = if (favoriteBrushNames.contains(name)) {
            favoriteBrushNames - name
        } else {
            favoriteBrushNames + name
        }
        persistBrushPanelState()
    }

    fun isFavoriteBrush(name: String): Boolean = favoriteBrushNames.contains(name)

    fun recordRecentBrush(name: String) {
        if (name.isBlank()) return
        val list = (listOf(name) + recentBrushNames.filter { it != name }).take(16)
        recentBrushNames = list
        persistBrushPanelState()
    }

    fun toggleBrushPanelGridView() {
        brushPanelGridView = !brushPanelGridView
        persistBrushPanelState()
    }

    // Stroke stabilizer working set: written every touch sample, read by no
    // composable — plain fields by design (zero allocation, zero recomposition).
    internal var smoothedStrokeX = 0f
    internal var smoothedStrokeY = 0f
    internal var smoothedStrokePressure = 0.0
    internal var lastStrokeTimeMs: Long = 0L
    internal var lastStrokeX: Float = 0f
    internal var lastStrokeY: Float = 0f
    internal var lastStrokeDeltaX: Float = 0f
    internal var lastStrokeDeltaY: Float = 0f
    internal var strokeDistanceAccumulator: Float = 0f
    internal var lastDynamicColor: String = ""

    fun getCategoryPresetScroll(cat: String): Pair<Int, Int> {
        return categoryPresetScrollMap[cat] ?: Pair(brushPresetScrollIndex, brushPresetScrollOffset)
    }

    fun saveCategoryPresetScroll(cat: String, index: Int, offset: Int, persist: Boolean = false) {
        brushPresetScrollIndex = index
        brushPresetScrollOffset = offset
        categoryPresetScrollMap = categoryPresetScrollMap.toMutableMap().apply {
            put(cat, Pair(index, offset))
        }
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER || t == com.reverie.paint.model.Tool.SMUDGE) {
            val state = toolBrushStates[t.id] ?: ToolBrushState()
            toolBrushStates = toolBrushStates.toMutableMap().apply {
                put(t.id, state.copy(presetScrollIndex = index, presetScrollOffset = offset))
            }
        }
        if (persist) {
            persistBrushPanelState()
        }
    }

    data class ToolBrushState(
        val presetIndex: Int = -1,
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

    var pinnedTools by mutableStateOf<List<com.reverie.paint.model.Tool>>(
        listOf(
            com.reverie.paint.model.Tool.BRUSH,
            com.reverie.paint.model.Tool.ERASER,
            com.reverie.paint.model.Tool.SMUDGE,
            com.reverie.paint.model.Tool.FILL,
            com.reverie.paint.model.Tool.GRADIENT,
            com.reverie.paint.model.Tool.LASSO,
            com.reverie.paint.model.Tool.TRANSFORM,
            com.reverie.paint.model.Tool.PICKER,
            com.reverie.paint.model.Tool.SYMMETRY,
            com.reverie.paint.model.Tool.PERSPECTIVE,
            com.reverie.paint.model.Tool.REFERENCE,
        )
    )
        internal set

    var currentToolId by mutableStateOf("brush")
        internal set

    /** 最近一次活跃的绘制类工具 (brush / eraser / smudge), 用于在临时工具 (如吸管) 切换回笔刷时保留笔刷尺寸与参数 */
    var lastDrawingToolId: String = "brush"
        internal set

    /** 是否正在执行图层复制, 防抖防止连续狂点触发多线程竞态崩溃 */
    var isCopyingLayer by mutableStateOf(false)
        internal set

    // Reference Tool Window State (常态固定显示参考窗口)
    var referenceWindowOpen by mutableStateOf(false)
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

    // Reference Window Position & Size (pixels/dp)
    var referenceWindowX by mutableFloatStateOf(80f)
    var referenceWindowY by mutableFloatStateOf(140f)
    var referenceWindowWidth by mutableFloatStateOf(260f)
    var referenceWindowHeight by mutableFloatStateOf(300f)

    fun persistReferenceState() {
        if (!::appContext.isInitialized) return
        try {
            val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
            p.putBoolean("ref_window_open", referenceWindowOpen)
            p.putFloat("ref_window_x", referenceWindowX)
            p.putFloat("ref_window_y", referenceWindowY)
            p.putFloat("ref_window_w", referenceWindowWidth)
            p.putFloat("ref_window_h", referenceWindowHeight)
            p.putBoolean("ref_is_grayscale", referenceIsGrayscale)
            p.putBoolean("ref_allow_rotation", referenceAllowRotation)
            p.putBoolean("ref_is_flipped", referenceIsFlipped)
            p.putInt("ref_active_tab", referenceActiveTab)
            p.putBoolean("ref_bars_collapsed", referenceBarsCollapsed)
            p.putFloat("ref_zoom", referenceZoom)
            p.putFloat("ref_rotation", referenceRotation)
            p.putFloat("ref_pan_x", referencePanX)
            p.putFloat("ref_pan_y", referencePanY)
            p.apply()
        } catch (_: Exception) {}
    }

    // 作者档案 (Dublin Core / Krita 兼容元数据)
    var authorProfile by mutableStateOf(com.reverie.paint.model.AuthorProfile())
        internal set

    fun updateAuthorProfile(profile: com.reverie.paint.model.AuthorProfile) {
        authorProfile = profile
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("author_profile_json", profile.toJson())
                .apply()
        }
        syncAuthorProfileToCore()
    }

    fun syncAuthorProfileToCore() {
        runCore(render = false) {
            ReverieCoreBridge.setAuthorProfile(authorProfile.toJson())
        }
    }

    fun persistReferenceImages() {
        if (!::appContext.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(appContext.filesDir, "ref_images")
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                val currentImgs = referenceImages
                for ((idx, bmp) in currentImgs.withIndex()) {
                    val file = java.io.File(dir, "ref_$idx.png")
                    java.io.FileOutputStream(file).use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                val urisJson = JSONArray().apply {
                    for (u in referenceAlbumSelectedUris) {
                        put(u.toString())
                    }
                }.toString()
                appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("ref_images_count", currentImgs.size)
                    .putString("ref_album_selected_uris", urisJson)
                    .apply()
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "Failed to persist reference images", e)
            }
        }
    }

    fun loadPersistedReferenceImages() {
        if (!::appContext.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                val urisJson = prefs.getString("ref_album_selected_uris", null)
                val restoredUris = mutableListOf<android.net.Uri>()
                if (!urisJson.isNullOrEmpty()) {
                    try {
                        val arr = JSONArray(urisJson)
                        for (i in 0 until arr.length()) {
                            restoredUris.add(android.net.Uri.parse(arr.getString(i)))
                        }
                    } catch (_: Throwable) {}
                }

                val dir = java.io.File(appContext.filesDir, "ref_images")
                val count = prefs.getInt("ref_images_count", 0)
                val list = mutableListOf<Bitmap>()
                if (dir.exists() && count > 0) {
                    for (i in 0 until count) {
                        val file = java.io.File(dir, "ref_$i.png")
                        if (file.exists()) {
                            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            if (bmp != null) list.add(bmp)
                        }
                    }
                }
                viewModelScope.launch(Dispatchers.Main) {
                    if (restoredUris.isNotEmpty()) {
                        referenceAlbumSelectedUris = restoredUris
                    }
                    if (list.isNotEmpty()) {
                        referenceImages = list
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "Failed to load reference images", e)
            }
        }
    }

    fun persistBrushPanelState() {
        if (!::appContext.isInitialized) return
        try {
            val p = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).edit()
            p.putString("brush_panel_category", brushPanelSelectedCategory)
            p.putInt("brush_cat_scroll_idx", brushCategoryScrollIndex)
            p.putInt("brush_cat_scroll_offset", brushCategoryScrollOffset)
            p.putInt("brush_preset_scroll_idx", brushPresetScrollIndex)
            p.putInt("brush_preset_scroll_offset", brushPresetScrollOffset)
            p.putInt("brush_prop_scroll_val", brushPropertyScrollValue)

            // Persist per-category scroll positions as JSON
            val json = org.json.JSONObject()
            categoryPresetScrollMap.forEach { (cat, pair) ->
                val arr = org.json.JSONArray().apply {
                    put(pair.first)
                    put(pair.second)
                }
                json.put(cat, arr)
            }
            p.putString("brush_category_preset_scroll_map", json.toString())

            if (brushPanelDetailIndex != null) {
                p.putInt("brush_panel_detail_idx", brushPanelDetailIndex!!)
            } else {
                p.remove("brush_panel_detail_idx")
            }

            p.putBoolean("brush_panel_grid_view", brushPanelGridView)
            p.putStringSet("brush_favorite_names", favoriteBrushNames)
            val recentsArr = org.json.JSONArray()
            recentBrushNames.forEach { recentsArr.put(it) }
            p.putString("brush_recent_names", recentsArr.toString())

            p.apply()
        } catch (_: Exception) {}
    }

    fun updateReferenceAllowRotation(allow: Boolean) {
        referenceAllowRotation = allow
        if (!allow) {
            referenceRotation = 0f
        }
        persistReferenceState()
    }

    fun decodeSampledBitmapFromUri(
        uri: android.net.Uri,
        maxDimension: Int = 2048,
    ): Bitmap? {
        return runCatching {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            appContext.contentResolver.openInputStream(uri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, options)
            }
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            var sampleSize = 1
            var halfW = origW / 2
            var halfH = origH / 2
            while ((halfW / sampleSize) >= maxDimension || (halfH / sampleSize) >= maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            appContext.contentResolver.openInputStream(uri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, decodeOptions)
            }
        }.getOrNull()
    }

    suspend fun loadBitmapsFromUris(uris: List<android.net.Uri>): List<Bitmap> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<Bitmap>()
        val maxDim = 2048
        for (uri in uris.take(MAX_REFERENCE_IMAGES)) {
            try {
                val bmp = if (uri.scheme == "http" || uri.scheme == "https") {
                    val conn = java.net.URL(uri.toString()).openConnection()
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    conn.getInputStream()?.use { s ->
                        android.graphics.BitmapFactory.decodeStream(s)
                    }
                } else {
                    decodeSampledBitmapFromUri(uri, maxDim)
                }
                if (bmp != null) {
                    loaded.add(bmp)
                }
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "Failed to load reference image $uri", e)
            }
        }
        loaded
    }

    fun applyReferenceAlbumSelection(selectedUris: List<android.net.Uri>) {
        val trimmed = selectedUris.distinct().take(MAX_REFERENCE_IMAGES)
        referenceAlbumSelectedUris = trimmed
        if (trimmed.isEmpty()) {
            clearReferenceImage()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadBitmapsFromUris(trimmed)
            viewModelScope.launch(Dispatchers.Main) {
                val wasEmpty = referenceImages.isEmpty()
                referenceImages = loaded
                referenceActiveTab = 0
                referenceWindowOpen = true
                if (wasEmpty) {
                    resetReferenceTransform()
                }
                persistReferenceImages()
                persistReferenceState()
            }
        }
    }

    fun importReferenceImagesFromUris(uris: List<android.net.Uri>) {
        if (!::appContext.isInitialized || uris.isEmpty()) return
        if (isImportingMedia) {
            showActionToast(R.string.toast_importing_media, R.drawable.ic_image)
            return
        }
        isImportingMedia = true
        viewModelScope.launch(Dispatchers.IO) {
            val newBitmaps = mutableListOf<Bitmap>()
            val maxAllowed = MAX_REFERENCE_IMAGES
            val maxDim = 2048
            for (uri in uris.take(maxAllowed)) {
                try {
                    val bmp = if (uri.scheme == "http" || uri.scheme == "https") {
                        val conn = java.net.URL(uri.toString()).openConnection()
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.getInputStream()?.use { s ->
                            android.graphics.BitmapFactory.decodeStream(s)
                        }
                    } else {
                        decodeSampledBitmapFromUri(uri, maxDim)
                    }
                    if (bmp != null) {
                        newBitmaps.add(bmp)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ReveriePaint", "Failed to load reference image $uri", e)
                }
            }
            viewModelScope.launch(Dispatchers.Main) {
                isImportingMedia = false
                if (newBitmaps.isNotEmpty()) {
                    val combined = referenceImages + newBitmaps
                    referenceImages = combined.takeLast(maxAllowed)
                    referenceAlbumSelectedUris = (referenceAlbumSelectedUris + uris).distinct().take(maxAllowed)
                    referenceActiveTab = 0
                    referenceWindowOpen = true
                    resetReferenceTransform()
                    persistReferenceImages()
                    persistReferenceState()
                    showActionToast(R.string.toast_imported_ref_images, R.drawable.ic_image, newBitmaps.size)
                }
            }
        }
    }

    fun importReferenceImageFromUri(uri: android.net.Uri) {
        importReferenceImagesFromUris(listOf(uri))
    }

    fun clearReferenceImage() {
        referenceImages = emptyList()
        referenceAlbumSelectedUris = emptyList()
        resetReferenceTransform()
        persistReferenceImages()
        persistReferenceState()
    }

    fun resetReferenceTransform() {
        referenceZoom = 1f
        referenceRotation = 0f
        referencePanX = 0f
        referencePanY = 0f
        persistReferenceState()
    }

    // UI & View Settings (persisted)
    var uiOpacity by mutableFloatStateOf(1.0f) // For Top and Left panels
    var popupPanelOpacity by mutableFloatStateOf(0.95f) // For floating panels
    var blurBackground by mutableStateOf(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) // 背景毛玻璃效果，默认开启（API<31 设备不支持模糊，自动回退实色）
    var accentColorHex by mutableStateOf("#5A6E8A")
    var monetEnabled by mutableStateOf(false) // 莫奈动态取色
    var themeMode by mutableStateOf("DARK") // "DARK", "LIGHT", "SYSTEM"
    var paintingUiScale by mutableFloatStateOf(1.0f) // 绘画页面整体 UI 大小缩放 (0.75 - 1.35)
    var layerRowHeightDp by mutableIntStateOf(52) // 44: 紧凑, 52: 标准, 64: 舒适
    var quickSliderHeightDp by mutableIntStateOf(175) // 绘画界面左下角快捷滑块长度 (100 - 260 dp, 默认 175)
    var selectionMaskColorHex by mutableStateOf("#141416") // 选区蒙版遮罩颜色 (默认深空灰黑)
    var selectionMaskOpacity by mutableFloatStateOf(0.47f) // 选区蒙版遮罩不透明度 (0.10 - 0.90, 默认 0.47)

    /** 左侧工具条滑块面板的实时高度 (px), 由 ToolRail 测量写入; 时间轴"展开至同高"对齐用 */
    var railSliderPanelHeightPx by mutableFloatStateOf(0f)
    var extendToCutout by mutableStateOf(true)
    var homeSelectedTab by mutableIntStateOf(0)

    // View Display Settings (参考图 1)
    var quickSliderMode by mutableIntStateOf(0) // 0: 不透明度, 1: 流量
    var brushSizePresets by mutableStateOf<List<Double?>>(listOf(2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 350.0))
    var brushOpacityPresets by mutableStateOf<List<Double?>>(listOf(0.10, 0.25, 0.40, 0.50, 0.65, 0.75, 0.85, 0.95, 1.00))
    var brushFlowPresets by mutableStateOf<List<Double?>>(listOf(0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.75, 0.90, 1.00))
    var canvasRotationEnabled by mutableStateOf(true) // 画布可旋转
    var magnificationInterpolation by mutableStateOf(true) // 放大插值
    var pixelGridEnabled by mutableStateOf(true) // 放大显示网格线
    var undoToastEnabled by mutableStateOf(true) // 撤销操作提醒

    // Stroke Stabilizer (抖动修正: 0.0 ~ 1.0, 默认为 0 实现零延迟物理直通)
    var strokeStabilizer by mutableFloatStateOf(0.0f)

    // Keyboard Shortcuts (参考图 2)
    var shortcutBindings by mutableStateOf<Map<String, String>>(emptyMap())

    // Stylus Settings (画世界 Pro & Krita style, persisted)
    var penOnlyMode by mutableStateOf(false) // 笔模式 (禁止手指绘制，单指平移，双指缩放旋转)
    var brushCursorMode by mutableIntStateOf(3) // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
    var eraserCursorMode by mutableIntStateOf(3)
    var cursorStyleMode by mutableIntStateOf(5) // 0: 圆形, 1: 十字准星, 2: 点, 3: 无, 4: 系统指针, 5: 圆+十字准星
    var quickShapeEnabled by mutableStateOf(false) // 驻停线条成形 (已禁用)
    var activeQuickShape by mutableStateOf<QuickShapeResult?>(null)
    var isQuickShapeEditing by mutableStateOf(false)

    // Vendor Stylus Adaptations (OPPO Pencil / OnePlus Stylo & Samsung S Pen)
    var oppoPencilModelMode by mutableStateOf("AUTO") // "AUTO", "STANDARD", "PRO"
    var detectedOppoPencilModel by mutableStateOf(com.reverie.paint.core.stylus.OppoPencilModel.PRO)
    val oppoPencilModel: com.reverie.paint.core.stylus.OppoPencilModel
        get() = when (oppoPencilModelMode) {
            "STANDARD" -> com.reverie.paint.core.stylus.OppoPencilModel.STANDARD
            "PRO" -> com.reverie.paint.core.stylus.OppoPencilModel.PRO
            else -> detectedOppoPencilModel
        }
    var oppoDoubleTapAction by mutableStateOf("toggle_eraser")
    var oppoSlideAction by mutableStateOf("adjust_brush_size")
    var oppoSlideSensitivity by mutableStateOf("normal") // "low", "normal", "high"
    var oppoInPenHapticsEnabled by mutableStateOf(true)
    var stylusHapticsEnabled by mutableStateOf(true)
    var stylusHapticsIntensity by mutableFloatStateOf(0.5f)
    var stylusAudioEnabled by mutableStateOf(false)
    var stylusAudioVolume by mutableFloatStateOf(0.6f)
    var stylusStrokePredictionEnabled by mutableStateOf(true)
    // Samsung Notes 标准语义: 按住侧键落笔 = 临时橡皮 (默认开, 可在三星 S Pen 专属设置中关闭)
    var samsungSideButtonErase by mutableStateOf(true)
    var samsungSingleClickAction by mutableStateOf("toggle_eraser")
    var samsungDoubleClickAction by mutableStateOf("undo")
    var samsungLongPressAction by mutableStateOf("tool_picker")

    // HUAWEI M-Pencil 适配参数
    var huaweiPencilModelMode by mutableStateOf("AUTO") // "AUTO", "GEN3_NEARLINK", "GEN2", "GEN1"
    val detectedHuaweiPencilModel: com.reverie.paint.core.stylus.HuaweiPencilModel
        get() = stylusDriver?.detectHuaweiPencilModel() ?: com.reverie.paint.core.stylus.HuaweiPencilModel.GEN2
    val huaweiPencilModel: com.reverie.paint.core.stylus.HuaweiPencilModel
        get() = when (huaweiPencilModelMode) {
            "GEN3_NEARLINK" -> com.reverie.paint.core.stylus.HuaweiPencilModel.GEN3_NEARLINK
            "GEN2" -> com.reverie.paint.core.stylus.HuaweiPencilModel.GEN2
            "GEN1" -> com.reverie.paint.core.stylus.HuaweiPencilModel.GEN1
            else -> detectedHuaweiPencilModel
        }
    var huaweiDoubleTapAction by mutableStateOf("toggle_eraser")
    var huaweiSingleClickAction by mutableStateOf("none")
    var huaweiLongPressAction by mutableStateOf("tool_color")
    var huaweiSideButtonErase by mutableStateOf(true)
    var huaweiHapticsEnabled by mutableStateOf(true)

    // 触控预测与输入延迟优化
    var motionPredictorEnabled by mutableStateOf(true)

    /**
     * 判定当前笔刷是否适用于前向矢量切线预测。
     * 画世界 Pro / Procreate 规范：
     * 仅对纯色、实心、无纹理的线稿类基础笔刷启用切线预测；
     * 自动排除带颗粒纹理、杂色抖动、低透明度罩染、大间距打点、复杂水彩/涂抹/笔刷印章的笔刷，
     * 避免在有质感的笔迹前方拉出生硬突兀的矢量直线。
     */
    val isCurrentBrushPredictionEligible: Boolean
        get() {
            if (!stylusStrokePredictionEnabled) return false
            if (currentToolId != "brush" && currentToolId != "eraser") return false

            // 1. 混合模式与涂抹检查：非 normal/erase 模式或涂抹不进行矢量假线预测
            if (currentToolId == "smudge") return false
            if (brushCompositeOp != "normal" && brushCompositeOp != "erase" && brushCompositeOp.isNotEmpty()) return false

            // 2. 纹理与颗粒检查：启用了纹理贴图或散布则不进行假线延伸
            if (brushTextureEnabled) return false
            if (brushScatter > 0.01) return false
            if (brushSpacing > 0.25) return false

            // 3. 透明度与流量检查：低不透明度（如淡彩、喷笔罩染等）不拉实体线
            if (brushOpacity < 0.65 || (brushFlow > 0.0 && brushFlow < 0.65)) return false

            // 4. 笔刷预设与分组检查：排除天然带纹理、特殊印章或水彩混合类材质笔刷
            val preset = brushPresets.firstOrNull { it.index == brushPresetIndex }
            val grp = preset?.group?.ifEmpty { null } ?: inferBrushGroup(preset?.name ?: "")
            val excludedGroups = setOf("铅笔", "水彩", "混合", "绘画", "纹理与排线", "印章与喷溅", "特效与滤镜", "速写", "形状")
            if (excludedGroups.contains(grp)) return false

            val name = preset?.name ?: ""
            if (name.contains("Pencil", ignoreCase = true) ||
                name.contains("Chalk", ignoreCase = true) ||
                name.contains("Charcoal", ignoreCase = true) ||
                name.contains("Pastel", ignoreCase = true) ||
                name.contains("Bristle", ignoreCase = true) ||
                name.contains("Dry", ignoreCase = true) ||
                name.contains("Texture", ignoreCase = true) ||
                name.contains("Wet", ignoreCase = true) ||
                name.contains("Water", ignoreCase = true) ||
                name.contains("Stamp", ignoreCase = true) ||
                name.contains("Spray", ignoreCase = true) ||
                name.contains("Splat", ignoreCase = true) ||
                name.contains("Sponge", ignoreCase = true) ||
                name.contains("Airbrush", ignoreCase = true) ||
                name.contains("Sketch", ignoreCase = true) ||
                name.contains("Curve", ignoreCase = true) ||
                name.contains("Blender", ignoreCase = true) ||
                name.contains("Smudge", ignoreCase = true) ||
                name.contains("Rake", ignoreCase = true) ||
                name.contains("Hatch", ignoreCase = true) ||
                name.contains("Screentone", ignoreCase = true) ||
                name.contains("Noise", ignoreCase = true) ||
                name.contains("Grain", ignoreCase = true) ||
                name.contains("Blur", ignoreCase = true) ||
                name.contains("Shade", ignoreCase = true) ||
                name.contains("Fuzzy", ignoreCase = true)) {
                return false
            }

            return true
        }

    // 绘图辅助与参考线状态 (Symmetry, Perspective, Grid)
    var drawingGuide by mutableStateOf(DrawingGuideConfig())

    // 画布内富文本排版状态 (In-place Typography)
    var typographyConfig by mutableStateOf(TypographyConfig())
    var isTypographyEditing by mutableStateOf(false)

    var pressureCurvePreset by mutableIntStateOf(0) // 0: 线性, 1: 轻压灵敏, 2: 重压偏硬, 3: S型, 4: 自定义
    var pressureControlPoints by mutableStateOf(
        listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(1f, 1f),
        ),
    )

    var colorPickerMode by mutableStateOf("SQUARE")
        internal set

    // Tool options states (persisted)
    var fillTolerance by mutableIntStateOf(16)
    var fillSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层
    var fillExpand by mutableIntStateOf(0) // 拓展 (-16..32 px)
    var fillFeather by mutableIntStateOf(0) // 羽化 (0..32 px)
    var fillCloseGap by mutableIntStateOf(4) // 闭合空隙 (0..16 px)
    var fillOpacity by mutableDoubleStateOf(1.0)
    var fillCompositeOp by mutableStateOf("normal")

    var gradientType by mutableIntStateOf(0) // 0: 线性, 1: 径向, 2: 角度
    var gradientRepeat by mutableIntStateOf(0) // 0: 无, 1: 重复, 2: 往返
    var gradientReverse by mutableStateOf(false)

    var shapeStrokeWidth by mutableDoubleStateOf(4.0)
    var shapeFillMode by mutableIntStateOf(0) // 0: 仅描边, 1: 仅填充, 2: 描边与填充
    var shapeKeepAspect by mutableStateOf(false)
    val shapeState = ShapeState()

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

    // 独显浮窗的“取消所有效果”模式（C++ 状态，经 notifyLayerChanged 同步为
    // Compose state，保证点击后 chip 高亮即时刷新）
    var soloRawMode by mutableStateOf(false)

    // 绘画页内的“更多设置”全屏覆盖层（不退出画布）
    var moreSettingsOpen by mutableStateOf(false)

    fun openMoreSettings(initialSubPage: String = "MAIN") {
        settingsInitialSubPage = initialSubPage
        moreSettingsOpen = true
    }

    fun closeMoreSettings() {
        settingsInitialSubPage = "MAIN"
        moreSettingsOpen = false
    }

    /** Evaluate mapped pressure from raw input pressure using the active curve */
    fun evaluatePressure(raw: Float): Float {
        val x = raw.coerceIn(0f, 1f)
        val pts = pressureControlPoints.sortedBy { it.x }
        val n = pts.size
        if (n < 2) return x
        if (n == 2) {
            val p0 = pts[0]
            val p1 = pts[1]
            if (p0.x == 0f && p0.y == 0f && p1.x == 1f && p1.y == 1f) return x
            val spanX = (p1.x - p0.x).coerceAtLeast(0.0001f)
            val t = ((x - p0.x) / spanX).coerceIn(0f, 1f)
            return (p0.y + t * (p1.y - p0.y)).coerceIn(0f, 1f)
        }
        if (x <= pts.first().x) return pts.first().y.coerceIn(0f, 1f)
        if (x >= pts.last().x) return pts.last().y.coerceIn(0f, 1f)

        var i = 0
        while (i < pts.size - 1 && pts[i + 1].x < x) {
            i++
        }
        val p0 = if (i > 0) pts[i - 1] else pts[i]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = if (i + 2 < pts.size) pts[i + 2] else p2

        val dx = (p2.x - p1.x).coerceAtLeast(0.0001f)
        val t = ((x - p1.x) / dx).coerceIn(0f, 1f)

        val m1 = (p2.y - p0.y) / (p2.x - p0.x).coerceAtLeast(0.0001f)
        val m2 = (p3.y - p1.y) / (p3.x - p1.x).coerceAtLeast(0.0001f)

        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2

        val y = h00 * p1.y + h10 * dx * m1 + h01 * p2.y + h11 * dx * m2
        return y.coerceIn(0f, 1f)
    }

    /**
     * Hot-path variant of [evaluatePressure] used per stroke sample.
     * Assumes control points are kept ascending by x.
     * Collinear default 2 points yield an exact identity mapping with 0 overhead.
     */
    internal fun applyGlobalPressureCurve(raw: Double): Double {
        val pts = pressureControlPoints
        val n = pts.size
        if (n < 2) return raw.coerceIn(0.0, 1.0)
        val x = raw.toFloat().coerceIn(0f, 1f)
        if (n == 2) {
            val p0 = pts[0]
            val p1 = pts[1]
            if (p0.x == 0f && p0.y == 0f && p1.x == 1f && p1.y == 1f) return x.toDouble()
            val spanX = (p1.x - p0.x).coerceAtLeast(0.0001f)
            val t = ((x - p0.x) / spanX).coerceIn(0f, 1f)
            return (p0.y + t * (p1.y - p0.y)).coerceIn(0f, 1f).toDouble()
        }
        if (x <= pts[0].x) return pts[0].y.coerceIn(0f, 1f).toDouble()
        if (x >= pts[n - 1].x) return pts[n - 1].y.coerceIn(0f, 1f).toDouble()
        var i = 0
        while (i < n - 2 && pts[i + 1].x < x) i++
        val p0 = if (i > 0) pts[i - 1] else pts[i]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = if (i + 2 < n) pts[i + 2] else p2
        val dx = (p2.x - p1.x).coerceAtLeast(0.0001f)
        val t = ((x - p1.x) / dx).coerceIn(0f, 1f)
        val m1 = (p2.y - p0.y) / (p2.x - p0.x).coerceAtLeast(0.0001f)
        val m2 = (p3.y - p1.y) / (p3.x - p1.x).coerceAtLeast(0.0001f)
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        val y = h00 * p1.y + h10 * dx * m1 + h01 * p2.y + h11 * dx * m2
        return y.coerceIn(0f, 1f).toDouble()
    }

    /** Persist a user-drawn custom pressure curve together with preset id 4. */
    fun updateCustomPressureCurve(points: List<androidx.compose.ui.geometry.Offset>) {
        pressureCurvePreset = 4
        pressureControlPoints = points
        if (::appContext.isInitialized) {
            try {
                val arr = org.json.JSONArray()
                for (pt in points) {
                    arr.put(org.json.JSONObject().put("x", pt.x.toDouble()).put("y", pt.y.toDouble()))
                }
                appContext
                    .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("pressureCurvePreset", 4)
                    .putString("pressureControlPoints", arr.toString())
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    var gestureTwoFingerUndo by mutableStateOf(true)
    var gestureThreeFingerRedo by mutableStateOf(true)
    var gesturePinchTransform by mutableStateOf(true)
    var gestureQuickPinchFit by mutableStateOf(true)

    fun updateGestureTwoFingerUndo(enable: Boolean) {
        gestureTwoFingerUndo = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gestureTwoFingerUndo", enable)
                .apply()
        }
    }

    fun updateGestureThreeFingerRedo(enable: Boolean) {
        gestureThreeFingerRedo = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gestureThreeFingerRedo", enable)
                .apply()
        }
    }

    fun updateGesturePinchTransform(enable: Boolean) {
        gesturePinchTransform = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gesturePinchTransform", enable)
                .apply()
        }
    }

    fun updateGestureQuickPinchFit(enable: Boolean) {
        gestureQuickPinchFit = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("gestureQuickPinchFit", enable)
                .apply()
        }
    }

    var actionToastMessage by mutableStateOf<String?>(null)
    var actionToastIcon by mutableStateOf<Int?>(null)
    var actionToastRevision by mutableLongStateOf(0L)

    fun showActionToast(
        message: String,
        iconRes: Int? = null,
    ) {
        actionToastMessage = message
        actionToastIcon = iconRes
        actionToastRevision++
    }

    fun showActionToast(
        @StringRes messageRes: Int,
        iconRes: Int? = null,
        vararg formatArgs: Any,
    ) {
        val msg = if (::appContext.isInitialized) {
            if (formatArgs.isEmpty()) appContext.getString(messageRes)
            else appContext.getString(messageRes, *formatArgs)
        } else ""
        showActionToast(msg, iconRes)
    }

    fun getString(@StringRes resId: Int, vararg formatArgs: Any): String {
        return if (::appContext.isInitialized) {
            if (formatArgs.isEmpty()) appContext.getString(resId)
            else appContext.getString(resId, *formatArgs)
        } else ""
    }

    var longPressEyedropperEnabled by mutableStateOf(true)
    var eyedropperSensitivity by mutableIntStateOf(3) // 1..5, default 3
    var eyedropperOffsetEnabled by mutableStateOf(true) // offset sampling point to avoid finger blocking

    fun updateLongPressEyedropperEnabled(enable: Boolean) {
        longPressEyedropperEnabled = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("longPressEyedropperEnabled", enable)
                .apply()
        }
    }

    fun updateEyedropperSensitivity(level: Int) {
        val clamped = level.coerceIn(1, 5)
        eyedropperSensitivity = clamped
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("eyedropperSensitivity", clamped)
                .apply()
        }
    }

    fun updateEyedropperOffsetEnabled(enable: Boolean) {
        eyedropperOffsetEnabled = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("eyedropperOffsetEnabled", enable)
                .apply()
        }
    }

    fun updatePenOnlyMode(enable: Boolean) {
        penOnlyMode = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("penOnlyMode", enable)
                .apply()
        }
    }

    fun updateBrushCursorMode(mode: Int) {
        brushCursorMode = mode
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("brushCursorMode", mode)
                .apply()
        }
    }

    fun updateEraserCursorMode(mode: Int) {
        eraserCursorMode = mode
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("eraserCursorMode", mode)
                .apply()
        }
    }

    fun updateCursorStyleMode(mode: Int) {
        cursorStyleMode = mode
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("cursorStyleMode", mode)
                .apply()
        }
    }

    fun updateQuickShapeEnabled(enable: Boolean) {
        quickShapeEnabled = false
    }

    fun updateOppoPencilModelMode(mode: String) {
        oppoPencilModelMode = mode
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("oppoPencilModelMode", mode).apply()
        }
    }

    fun updateOppoSlideAction(actionId: String) {
        oppoSlideAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("oppoSlideAction", actionId).apply()
        }
    }

    fun updateOppoSlideSensitivity(sensitivity: String) {
        oppoSlideSensitivity = sensitivity
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("oppoSlideSensitivity", sensitivity).apply()
        }
    }

    fun updateOppoInPenHapticsEnabled(enabled: Boolean) {
        oppoInPenHapticsEnabled = enabled
        stylusDriver?.syncSettings()
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("oppoInPenHapticsEnabled", enabled).apply()
        }
    }

    fun executeStylusSlide(delta: Float): Boolean {
        if (oppoSlideAction == "none" || oppoSlideAction.isBlank()) {
            return false
        }
        // 反转滑动方向以符合自然滑动交互（向笔尾滑动为增加，向笔尖滑动为减少）
        val effectiveDelta = -delta
        val isIncrease = effectiveDelta > 0
        return when (oppoSlideAction) {
            "adjust_brush_size" -> {
                val deltaFrac = when (oppoSlideSensitivity) {
                    "low" -> 0.015f
                    "high" -> 0.035f
                    else -> 0.025f
                }
                val minL = brushMinSizeLimit.coerceAtLeast(0.5)
                val maxL = brushMaxSizeLimit.coerceAtLeast(minL + 0.1)
                val logMin = kotlin.math.ln(minL)
                val logMax = kotlin.math.ln(maxL)
                val range = (logMax - logMin).coerceAtLeast(1e-6)
                val current = brushSize.coerceIn(minL, maxL)
                val currentFrac = ((kotlin.math.ln(current) - logMin) / range).toFloat().coerceIn(0f, 1f)
                val targetFrac = (currentFrac + (if (isIncrease) deltaFrac else -deltaFrac)).coerceIn(0f, 1f)
                val rawNewSize = kotlin.math.exp(logMin + targetFrac.toDouble() * range).coerceIn(minL, maxL)
                val newSize = if (rawNewSize < 10.0) {
                    (kotlin.math.round(rawNewSize * 10.0) / 10.0).coerceIn(minL, maxL)
                } else {
                    kotlin.math.round(rawNewSize).coerceIn(minL, maxL)
                }
                val finalSize = if (isIncrease && newSize <= brushSize) {
                    if (brushSize < 10.0) (brushSize + 0.1).coerceAtMost(maxL) else (brushSize + 1.0).coerceAtMost(maxL)
                } else if (!isIncrease && newSize >= brushSize) {
                    if (brushSize <= 10.0) (brushSize - 0.1).coerceAtLeast(minL) else (brushSize - 1.0).coerceAtLeast(minL)
                } else {
                    newSize
                }
                updateBrushSize(finalSize)
                if (::appContext.isInitialized) {
                    mainHandler.post {
                        val formatted = if (finalSize < 10.0) {
                            String.format(java.util.Locale.US, "%.1f", finalSize)
                        } else {
                            "${finalSize.toInt()}"
                        }
                        showActionToast(R.string.toast_brush_size, R.drawable.ic_brush, formatted)
                    }
                }
                true
            }
            "adjust_opacity" -> {
                val step = when (oppoSlideSensitivity) {
                    "low" -> 0.02
                    "high" -> 0.08
                    else -> 0.05
                }
                val newOpacity = (brushOpacity + (if (isIncrease) step else -step)).coerceIn(0.01, 1.0)
                updateBrushOpacity(newOpacity)
                if (::appContext.isInitialized) {
                    mainHandler.post {
                        showActionToast(R.string.toast_brush_opacity, R.drawable.ic_brush, kotlin.math.round(newOpacity * 100).toInt())
                    }
                }
                true
            }
            "undo_redo" -> {
                if (isIncrease) redo() else undo()
                true
            }
            else -> false
        }
    }

    fun updateOppoDoubleTapAction(actionId: String) {
        oppoDoubleTapAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("oppoDoubleTapAction", actionId).apply()
        }
    }

    fun updateStylusHapticsEnabled(enabled: Boolean) {
        stylusHapticsEnabled = enabled
        stylusDriver?.syncSettings()
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("stylusHapticsEnabled", enabled).apply()
        }
    }

    fun updateStylusHapticsIntensity(intensity: Float) {
        stylusHapticsIntensity = intensity
        stylusDriver?.syncSettings()
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putFloat("stylusHapticsIntensity", intensity).apply()
        }
    }

    fun updateStylusAudioEnabled(enabled: Boolean) {
        stylusAudioEnabled = enabled
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("stylusAudioEnabled", enabled).apply()
            // 设置面板可能在绘画页内打开: 即时同步引擎, 否则音量/开关要到重进绘画页才生效
            getOrCreateStylusDriver(appContext).syncSettings()
        }
    }

    fun updateStylusAudioVolume(volume: Float) {
        stylusAudioVolume = volume
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putFloat("stylusAudioVolume", volume).apply()
            getOrCreateStylusDriver(appContext).syncSettings()
        }
    }

    fun updateStylusStrokePredictionEnabled(enabled: Boolean) {
        stylusStrokePredictionEnabled = enabled
        motionPredictorEnabled = enabled
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("stylusStrokePredictionEnabled", enabled).apply()
        }
    }

    fun updateSamsungSideButtonErase(enabled: Boolean) {
        samsungSideButtonErase = enabled
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("samsungSideButtonErase", enabled).apply()
        }
    }

    fun updateSamsungSingleClickAction(actionId: String) {
        samsungSingleClickAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("samsungSingleClickAction", actionId).apply()
        }
    }

    fun updateSamsungDoubleClickAction(actionId: String) {
        samsungDoubleClickAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("samsungDoubleClickAction", actionId).apply()
        }
    }

    fun updateSamsungLongPressAction(actionId: String) {
        samsungLongPressAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("samsungLongPressAction", actionId).apply()
        }
    }

    fun updateHuaweiPencilModelMode(mode: String) {
        huaweiPencilModelMode = mode
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("huaweiPencilModelMode", mode).apply()
        }
    }

    fun updateHuaweiDoubleTapAction(actionId: String) {
        huaweiDoubleTapAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("huaweiDoubleTapAction", actionId).apply()
        }
    }

    fun updateHuaweiSingleClickAction(actionId: String) {
        huaweiSingleClickAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("huaweiSingleClickAction", actionId).apply()
        }
    }

    fun updateHuaweiLongPressAction(actionId: String) {
        huaweiLongPressAction = actionId
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("huaweiLongPressAction", actionId).apply()
        }
    }

    fun updateHuaweiSideButtonErase(enabled: Boolean) {
        huaweiSideButtonErase = enabled
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("huaweiSideButtonErase", enabled).apply()
        }
    }

    fun updateHuaweiHapticsEnabled(enabled: Boolean) {
        huaweiHapticsEnabled = enabled
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("huaweiHapticsEnabled", enabled).apply()
        }
    }

    fun executeStylusAction(action: com.reverie.paint.core.stylus.StylusAction) {
        if (action == com.reverie.paint.core.stylus.StylusAction.NONE) return
        executeShortcutAction(action.actionId)
        when (action) {
            com.reverie.paint.core.stylus.StylusAction.TOGGLE_ERASER -> {
                showActionToast(
                    if (currentToolId == "eraser") R.string.toast_switched_to_eraser else R.string.toast_switched_to_brush,
                    if (currentToolId == "eraser") R.drawable.ic_eraser else R.drawable.ic_brush,
                )
            }
            com.reverie.paint.core.stylus.StylusAction.UNDO -> showActionToast(R.string.toast_undo, R.drawable.ic_undo)
            com.reverie.paint.core.stylus.StylusAction.REDO -> showActionToast(R.string.toast_redo, R.drawable.ic_redo)
            com.reverie.paint.core.stylus.StylusAction.COLOR_PICKER -> showActionToast(R.string.stylus_action_eyedropper, R.drawable.ic_picker)
            com.reverie.paint.core.stylus.StylusAction.TOGGLE_LAST_TOOL -> showActionToast(R.string.toast_toggle_last_tool, R.drawable.ic_brush)
            com.reverie.paint.core.stylus.StylusAction.SHOW_COLOR_PALETTE -> showActionToast(R.string.toast_color_palette, R.drawable.ic_palette)
            else -> {}
        }
    }

    fun updatePressureCurvePreset(preset: Int) {
        pressureCurvePreset = preset
        when (preset) {
            0 -> {
                pressureControlPoints = listOf(
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                )
            }
            1 -> {
                pressureControlPoints = listOf( // Soft / Convex (轻压灵敏)
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(0.30f, 0.65f),
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                )
            }
            2 -> {
                pressureControlPoints = listOf( // Hard / Concave (重压偏硬)
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(0.70f, 0.35f),
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                )
            }
            3 -> {
                pressureControlPoints = listOf( // S-Curve (S型)
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(0.35f, 0.15f),
                    androidx.compose.ui.geometry.Offset(0.65f, 0.85f),
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                )
            }
            4 -> {
                // If switching to custom without existing points, start from linear
                if (pressureControlPoints.size < 2) {
                    pressureControlPoints = listOf(
                        androidx.compose.ui.geometry.Offset(0f, 0f),
                        androidx.compose.ui.geometry.Offset(1f, 1f),
                    )
                }
            }
        }
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("pressureCurvePreset", preset)
                .apply()
        }
    }

    /** Immersive mode (fullscreen + hidden system bars), persisted in prefs.
     *  The actual window changes are applied by MainActivity.applyImmersive. */
    var immersiveMode by mutableStateOf(false)
        internal set

    fun updateUiOpacity(v: Float) {
        uiOpacity = v
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putFloat("uiOpacity", v)
                .apply()
        }
    }

    fun updatePopupPanelOpacity(v: Float) {
        popupPanelOpacity = v
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putFloat("popupPanelOpacity", v)
                .apply()
        }
    }

    fun updatePaintingUiScale(scale: Float) {
        paintingUiScale = scale.coerceIn(0.70f, 1.40f)
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putFloat("paintingUiScale", paintingUiScale)
                .apply()
        }
    }

    fun updateLayerRowHeight(height: Int) {
        layerRowHeightDp = height.coerceIn(40, 80)
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("layerRowHeightDp", layerRowHeightDp)
                .apply()
        }
    }

    fun updateQuickSliderHeight(height: Int) {
        quickSliderHeightDp = height.coerceIn(100, 260)
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("quickSliderHeightDp", quickSliderHeightDp)
                .apply()
        }
    }

    fun updateSelectionMaskColor(hex: String) {
        selectionMaskColorHex = hex
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("selection_mask_color", hex)
                .apply()
        }
    }

    fun updateSelectionMaskOpacity(opacity: Float) {
        selectionMaskOpacity = opacity.coerceIn(0.10f, 0.90f)
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putFloat("selection_mask_opacity", selectionMaskOpacity)
                .apply()
        }
    }


    var canvasBgColorHex by mutableStateOf("DEFAULT")

    fun updateCanvasBgColor(hex: String) {
        canvasBgColorHex = hex
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("canvasBgColor", hex)
                .apply()
        }
        applyCurrentTheme()
    }

    fun updateAccentColor(hex: String) {
        accentColorHex = hex
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("accentColor", hex)
                .apply()
        }
        applyCurrentTheme()
    }

    fun updateMonetEnabled(enable: Boolean) {
        monetEnabled = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("monetEnabled", enable)
                .apply()
        }
        applyCurrentTheme()
    }

    fun updateThemeMode(mode: String) {
        themeMode = mode
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("themeMode", mode)
                .apply()
        }
        applyCurrentTheme()
    }

    fun isCurrentlyDark(): Boolean {
        return when (themeMode) {
            "LIGHT" -> false
            "SYSTEM" -> {
                if (::appContext.isInitialized) {
                    val uiMode =
                        appContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
                } else {
                    true
                }
            }
            else -> true
        }
    }

    fun applyCurrentTheme() {
        if (!::appContext.isInitialized) return
        val dark = isCurrentlyDark()
        val parsedAccent = com.reverie.paint.ui.theme.parseColor(accentColorHex)
        val baseTheme = if (monetEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            com.reverie.paint.ui.theme.getMonetColors(appContext, isDark = dark, fallbackAccent = parsedAccent)
        } else {
            com.reverie.paint.ui.theme.buildThemeColors(isDark = dark, accent = parsedAccent)
        }

        com.reverie.paint.ui.theme.Theme.current = if (canvasBgColorHex.isNotBlank() && canvasBgColorHex != "DEFAULT") {
            try {
                baseTheme.copy(canvasBg = com.reverie.paint.ui.theme.parseColor(canvasBgColorHex))
            } catch (_: Exception) {
                baseTheme
            }
        } else {
            baseTheme
        }
        com.reverie.paint.MainActivity.applySystemBarsTheme(com.reverie.paint.ui.theme.Theme.current, dark)
    }

    fun updateImmersiveMode(enable: Boolean) {
        immersiveMode = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("immersiveMode", enable)
                .apply()
        }
        com.reverie.paint.MainActivity
            .applyImmersive(enable, extendToCutout)
    }

    fun updateExtendToCutout(extend: Boolean) {
        extendToCutout = extend
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("extendToCutout", extend)
                .apply()
        }
        if (immersiveMode) {
            com.reverie.paint.MainActivity
                .applyImmersive(true, extend)
        }
    }

    fun updateBlurBackground(enable: Boolean) {
        blurBackground = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("blurBackground", enable)
                .apply()
        }
    }

    fun updateAutoSaveEnabled(enabled: Boolean) {
        autoSaveEnabled = enabled
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("autoSaveEnabled", enabled)
                .apply()
        }
        if (enabled && lastAutoSaveTimeMs == 0L) {
            lastAutoSaveTimeMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun updateAutoSaveIntervalMinutes(minutes: Int) {
        autoSaveIntervalMinutes = minutes.coerceIn(1, 60)
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("autoSaveIntervalMinutes", autoSaveIntervalMinutes)
                .apply()
        }
    }

    fun updateAutoSaveToastEnabled(enabled: Boolean) {
        autoSaveToastEnabled = enabled
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("autoSaveToastEnabled", enabled)
                .apply()
        }
    }

    fun updateMaxUndoSteps(steps: Int) {
        maxUndoSteps = steps.coerceIn(10, 200)
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("maxUndoSteps", maxUndoSteps)
                .apply()
        }
    }

    fun updatePromptSaveOnExit(prompt: Boolean) {
        promptSaveOnExit = prompt
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("promptSaveOnExit", prompt)
                .apply()
        }
    }

    fun clearActionToast() {
        actionToastMessage = null
        actionToastIcon = null
    }

    fun syncSettingsFromPrefs() {
        if (::appContext.isInitialized) {
            val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            // 性能标尺: 设置项(仅 debug 构建有该入口, 见 PerfHud)或 setprop 任一为真即为开。
            // 用 PerfHud.readPref 而不是直接读偏好 —— 正式版恒 false, 避免残留偏好默默开着标尺。
            perfHudEnabled = PerfHud.readPref(prefs)
            PerfTrace.enabled = perfHudEnabled || PerfTrace.isEnabledByProp
            uiOpacity = prefs.getFloat("uiOpacity", 1.0f)
            popupPanelOpacity = prefs.getFloat("popupPanelOpacity", 0.95f)
            paintingUiScale = prefs.getFloat("paintingUiScale", 1.0f).coerceIn(0.70f, 1.40f)
            layerRowHeightDp = prefs.getInt("layerRowHeightDp", 52).coerceIn(40, 80)
            quickSliderHeightDp = prefs.getInt("quickSliderHeightDp", 175).coerceIn(100, 260)
            panelPinningEnabled = prefs.getBoolean("panelPinningEnabled", false)
            selectionMaskColorHex = prefs.getString("selection_mask_color", "#141416") ?: "#141416"
            selectionMaskOpacity = prefs.getFloat("selection_mask_opacity", 0.47f).coerceIn(0.10f, 0.90f)
            blurBackground = prefs.getBoolean("blurBackground", true) &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            val savedAccent = prefs.getString("accentColor", "#5A6E8A") ?: "#5A6E8A"
            accentColorHex = if (savedAccent.equals("#0A84FF", ignoreCase = true) || savedAccent.equals("#5E8BA8", ignoreCase = true)) "#5A6E8A" else savedAccent
            canvasBgColorHex = prefs.getString("canvasBgColor", "DEFAULT") ?: "DEFAULT"
            monetEnabled = prefs.getBoolean("monetEnabled", false)
            themeMode = prefs.getString("themeMode", "DARK") ?: "DARK"
            immersiveMode = prefs.getBoolean("immersiveMode", false)
            extendToCutout = prefs.getBoolean("extendToCutout", true)
            penOnlyMode = prefs.getBoolean("penOnlyMode", false)
            oppoPencilModelMode = prefs.getString("oppoPencilModelMode", "AUTO") ?: "AUTO"
            oppoDoubleTapAction = prefs.getString("oppoDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
            oppoSlideAction = prefs.getString("oppoSlideAction", "adjust_brush_size") ?: "adjust_brush_size"
            oppoSlideSensitivity = prefs.getString("oppoSlideSensitivity", "normal") ?: "normal"
            oppoInPenHapticsEnabled = prefs.getBoolean("oppoInPenHapticsEnabled", true)
            stylusHapticsEnabled = prefs.getBoolean("stylusHapticsEnabled", true)
            stylusHapticsIntensity = prefs.getFloat("stylusHapticsIntensity", 0.5f)
            stylusAudioEnabled = prefs.getBoolean("stylusAudioEnabled", false)
            stylusAudioVolume = prefs.getFloat("stylusAudioVolume", 0.6f)
            // 音效类型设置已移除, 统一为程序化铅笔沙沙音色 (忽略历史存储值)
            samsungSideButtonErase = prefs.getBoolean("samsungSideButtonErase", true)
            stylusStrokePredictionEnabled = prefs.getBoolean("stylusStrokePredictionEnabled", true)
            motionPredictorEnabled = stylusStrokePredictionEnabled
            samsungSingleClickAction = prefs.getString("samsungSingleClickAction", "toggle_eraser") ?: "toggle_eraser"
            samsungDoubleClickAction = prefs.getString("samsungDoubleClickAction", "undo") ?: "undo"
            samsungLongPressAction = prefs.getString("samsungLongPressAction", "tool_picker") ?: "tool_picker"
            huaweiPencilModelMode = prefs.getString("huaweiPencilModelMode", "AUTO") ?: "AUTO"
            huaweiDoubleTapAction = prefs.getString("huaweiDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
            huaweiSingleClickAction = prefs.getString("huaweiSingleClickAction", "none") ?: "none"
            huaweiLongPressAction = prefs.getString("huaweiLongPressAction", "tool_color") ?: "tool_color"
            huaweiSideButtonErase = prefs.getBoolean("huaweiSideButtonErase", true)
            huaweiHapticsEnabled = prefs.getBoolean("huaweiHapticsEnabled", true)
            gestureTwoFingerUndo = prefs.getBoolean("gestureTwoFingerUndo", true)
            gestureThreeFingerRedo = prefs.getBoolean("gestureThreeFingerRedo", true)
            gesturePinchTransform = prefs.getBoolean("gesturePinchTransform", true)
            gestureQuickPinchFit = prefs.getBoolean("gestureQuickPinchFit", true)
            longPressEyedropperEnabled = prefs.getBoolean("longPressEyedropperEnabled", true)
            eyedropperSensitivity = prefs.getInt("eyedropperSensitivity", 3).coerceIn(1, 5)
            eyedropperOffsetEnabled = prefs.getBoolean("eyedropperOffsetEnabled", true)
            brushCursorMode = prefs.getInt("brushCursorMode", 3)
            eraserCursorMode = prefs.getInt("eraserCursorMode", 3)
            cursorStyleMode = prefs.getInt("cursorStyleMode", 5)
            quickShapeEnabled = false
            val savedPreset = prefs.getInt("pressureCurvePreset", 0)
            updatePressureCurvePreset(savedPreset)
            // Restore a user-drawn custom curve (preset 4) over the preset defaults
            try {
                val savedPts = prefs.getString("pressureControlPoints", null)
                if (savedPreset == 4 && savedPts != null) {
                    val arr = org.json.JSONArray(savedPts)
                    if (arr.length() >= 2) {
                        pressureControlPoints = (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            androidx.compose.ui.geometry.Offset(
                                o.getDouble("x").toFloat(),
                                o.getDouble("y").toFloat(),
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
            stylusDriver?.syncSettings()

            autoSaveEnabled = prefs.getBoolean("autoSaveEnabled", true)
            autoSaveIntervalMinutes = prefs.getInt("autoSaveIntervalMinutes", 5).coerceIn(1, 60)
            autoSaveToastEnabled = prefs.getBoolean("autoSaveToastEnabled", true)
            maxUndoSteps = prefs.getInt("maxUndoSteps", 50).coerceIn(10, 200)
            promptSaveOnExit = prefs.getBoolean("promptSaveOnExit", true)

            val authorJson = prefs.getString("author_profile_json", null)
            authorProfile = com.reverie.paint.model.AuthorProfile.fromJson(authorJson)
            syncAuthorProfileToCore()

            brushColor = prefs.getString("brushColor", "#000000") ?: "#000000"
            brushSecondaryColor = prefs.getString("brushSecondaryColor", "#ffffff") ?: "#ffffff"
            runCore {
                ReverieCoreBridge.setBrushColor(brushColor)
            }

            brushSizePresets = loadSliderPresets("brushSizePresets")
            brushOpacityPresets = loadSliderPresets("brushOpacityPresets")
            brushFlowPresets = loadSliderPresets("brushFlowPresets")
            colorWheelInnerShape = prefs.getString("colorWheelInnerShape", "SQUARE") ?: "SQUARE"
            colorModel = prefs.getString("colorModel", "hsv") ?: "hsv"
            if (colorWheelInnerShape != "SQUARE") {
                colorModel = "hsv"
            }
            recentColors = loadRecentColors()
            userPalettes = loadUserPalettes()
            defaultPaletteId = prefs.getString("defaultPaletteId", "builtin_basic") ?: "builtin_basic"
            colorHarmonyModeName = prefs.getString("colorHarmonyMode", "COMPLEMENTARY") ?: "COMPLEMENTARY"
            colorHarmonyBaseHue = prefs.getFloat("colorHarmonyBaseHue", -1f)
            colorHarmonySubTab = prefs.getInt("colorHarmonySubTab", 0)

            // 笔刷面板持久化恢复
            brushPanelSelectedCategory = prefs.getString("brush_panel_category", "全部") ?: "全部"
            brushCategoryScrollIndex = prefs.getInt("brush_cat_scroll_idx", 0)
            brushCategoryScrollOffset = prefs.getInt("brush_cat_scroll_offset", 0)
            brushPresetScrollIndex = prefs.getInt("brush_preset_scroll_idx", 0)
            brushPresetScrollOffset = prefs.getInt("brush_preset_scroll_offset", 0)
            brushPropertyScrollValue = prefs.getInt("brush_prop_scroll_val", 0)

            val catScrollJsonStr = prefs.getString("brush_category_preset_scroll_map", null)
            if (!catScrollJsonStr.isNullOrEmpty()) {
                try {
                    val catScrollJson = org.json.JSONObject(catScrollJsonStr)
                    val map = mutableMapOf<String, Pair<Int, Int>>()
                    val keys = catScrollJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val arr = catScrollJson.getJSONArray(key)
                        map[key] = Pair(arr.getInt(0), arr.getInt(1))
                    }
                    categoryPresetScrollMap = map
                } catch (_: Exception) {}
            }

            if (prefs.contains("brush_panel_detail_idx")) {
                brushPanelDetailIndex = prefs.getInt("brush_panel_detail_idx", -1).takeIf { it >= 0 }
            }

            brushPanelGridView = prefs.getBoolean("brush_panel_grid_view", false)
            favoriteBrushNames = prefs.getStringSet("brush_favorite_names", null)?.toSet() ?: emptySet()
            val recentsJsonStr = prefs.getString("brush_recent_names", null)
            if (!recentsJsonStr.isNullOrEmpty()) {
                try {
                    val arr = org.json.JSONArray(recentsJsonStr)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    recentBrushNames = list
                } catch (_: Exception) {}
            }

            // 参考窗口持久化恢复
            referenceWindowOpen = prefs.getBoolean("ref_window_open", false)
            referenceWindowX = prefs.getFloat("ref_window_x", 80f)
            referenceWindowY = prefs.getFloat("ref_window_y", 140f)
            referenceWindowWidth = prefs.getFloat("ref_window_w", 260f)
            referenceWindowHeight = prefs.getFloat("ref_window_h", 300f)
            referenceIsGrayscale = prefs.getBoolean("ref_is_grayscale", false)
            referenceAllowRotation = prefs.getBoolean("ref_allow_rotation", true)
            referenceIsFlipped = prefs.getBoolean("ref_is_flipped", false)
            referenceActiveTab = prefs.getInt("ref_active_tab", 0)
            referenceBarsCollapsed = prefs.getBoolean("ref_bars_collapsed", false)
            referenceZoom = prefs.getFloat("ref_zoom", 1f)
            referenceRotation = prefs.getFloat("ref_rotation", 0f)
            referencePanX = prefs.getFloat("ref_pan_x", 0f)
            referencePanY = prefs.getFloat("ref_pan_y", 0f)
            loadPersistedReferenceImages()

            applyCurrentTheme()
        }
    }

    fun saveSizePreset(size: Double, slotIndex: Int = -1) {
        val list = brushSizePresets.toMutableList()
        val targetIdx = if (slotIndex in 0..8) {
            slotIndex
        } else {
            val emptyIdx = list.indexOfFirst { it == null }
            if (emptyIdx != -1) emptyIdx else 0
        }
        list[targetIdx] = (size * 100.0).roundToInt() / 100.0
        brushSizePresets = list
        persistSliderPresets("brushSizePresets", list)
    }

    fun removeSizePreset(slotIndex: Int) {
        if (slotIndex in 0..8) {
            val list = brushSizePresets.toMutableList()
            list[slotIndex] = null
            brushSizePresets = list
            persistSliderPresets("brushSizePresets", list)
        }
    }

    fun saveOpacityPreset(opacity: Double, slotIndex: Int = -1) {
        val list = brushOpacityPresets.toMutableList()
        val targetIdx = if (slotIndex in 0..8) {
            slotIndex
        } else {
            val emptyIdx = list.indexOfFirst { it == null }
            if (emptyIdx != -1) emptyIdx else 0
        }
        list[targetIdx] = (opacity * 100.0).roundToInt() / 100.0
        brushOpacityPresets = list
        persistSliderPresets("brushOpacityPresets", list)
    }

    fun removeOpacityPreset(slotIndex: Int) {
        if (slotIndex in 0..8) {
            val list = brushOpacityPresets.toMutableList()
            list[slotIndex] = null
            brushOpacityPresets = list
            persistSliderPresets("brushOpacityPresets", list)
        }
    }

    fun saveFlowPreset(flow: Double, slotIndex: Int = -1) {
        val list = brushFlowPresets.toMutableList()
        val targetIdx = if (slotIndex in 0..8) {
            slotIndex
        } else {
            val emptyIdx = list.indexOfFirst { it == null }
            if (emptyIdx != -1) emptyIdx else 0
        }
        list[targetIdx] = (flow * 100.0).roundToInt() / 100.0
        brushFlowPresets = list
        persistSliderPresets("brushFlowPresets", list)
    }

    fun removeFlowPreset(slotIndex: Int) {
        if (slotIndex in 0..8) {
            val list = brushFlowPresets.toMutableList()
            list[slotIndex] = null
            brushFlowPresets = list
            persistSliderPresets("brushFlowPresets", list)
        }
    }

    private fun persistSliderPresets(key: String, list: List<Double?>) {
        if (::appContext.isInitialized) {
            val str = list.joinToString(",") { it?.toString() ?: "null" }
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(key, str)
                .apply()
        }
    }

    private fun loadSliderPresets(key: String): List<Double?> {
        if (::appContext.isInitialized) {
            val str = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE).getString(key, null)
            if (!str.isNullOrBlank()) {
                val parts = str.split(",")
                val result = MutableList<Double?>(9) { null }
                for (i in 0 until 9.coerceAtMost(parts.size)) {
                    val p = parts[i].trim()
                    result[i] = if (p == "null" || p.isEmpty()) null else p.toDoubleOrNull()
                }
                return result
            }
        }
        return when (key) {
            "brushSizePresets" -> listOf(2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 350.0)
            "brushOpacityPresets" -> listOf(0.10, 0.25, 0.40, 0.50, 0.65, 0.75, 0.85, 0.95, 1.00)
            "brushFlowPresets" -> listOf(0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.75, 0.90, 1.00)
            else -> listOf(null, null, null, null, null, null, null, null, null)
        }
    }

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

    val allPalettes: List<ColorPaletteItem>
        get() = userPalettes

    var defaultPaletteId by mutableStateOf("builtin_basic")

    fun setDefaultPalette(id: String) {
        defaultPaletteId = id
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("defaultPaletteId", id).apply()
        }
    }

    val defaultPalette: ColorPaletteItem?
        get() = allPalettes.firstOrNull { it.id == defaultPaletteId } ?: allPalettes.firstOrNull()

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

    fun updatePanelPinningEnabled(enabled: Boolean) {
        panelPinningEnabled = enabled
        if (!enabled) {
            isBrushPanelPinned = false
            brushPanelOffset = androidx.compose.ui.geometry.Offset.Zero
            isLayerPanelPinned = false
            layerPanelOffset = androidx.compose.ui.geometry.Offset.Zero
        }
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("panelPinningEnabled", enabled).apply()
        }
    }

    var colorHarmonyModeName by mutableStateOf("COMPLEMENTARY")
    var colorHarmonyBaseHue by mutableFloatStateOf(-1f)
    var colorHarmonySubTab by mutableIntStateOf(0)
    var colorSphereBaseHex by mutableStateOf("")

    fun updateColorHarmonyMode(mode: String) {
        colorHarmonyModeName = mode
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("colorHarmonyMode", mode).apply()
        }
    }

    fun updateColorHarmonyBaseHue(hue: Float) {
        colorHarmonyBaseHue = hue
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putFloat("colorHarmonyBaseHue", hue).apply()
        }
    }

    fun updateColorHarmonySubTab(subTab: Int) {
        colorHarmonySubTab = subTab
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putInt("colorHarmonySubTab", subTab).apply()
        }
    }

    fun updateColorSphereBaseHex(hex: String) {
        colorSphereBaseHex = hex
    }

    fun addColorToPalette(paletteId: String, hex: String) {
        val upper = hex.uppercase()
        userPalettes = userPalettes.map {
            if (it.id == paletteId) {
                val list = it.colors.toMutableList()
                list.add(upper)
                it.copy(colors = list)
            } else it
        }
        persistUserPalettes()
    }

    fun removeColorFromPalette(paletteId: String, index: Int) {
        userPalettes = userPalettes.map {
            if (it.id == paletteId && index in it.colors.indices) {
                val list = it.colors.toMutableList()
                list.removeAt(index)
                it.copy(colors = list)
            } else it
        }
        persistUserPalettes()
    }

    fun createNewPalette(name: String, initialColors: List<String> = emptyList()) {
        val newPal = ColorPaletteItem(
            id = "palette_${System.currentTimeMillis()}",
            name = name.ifBlank { "新建色卡" },
            colors = initialColors
        )
        userPalettes = userPalettes + newPal
        persistUserPalettes()
    }

    fun duplicatePalette(paletteId: String) {
        val src = allPalettes.firstOrNull { it.id == paletteId } ?: return
        val newPal = ColorPaletteItem(
            id = "palette_${System.currentTimeMillis()}",
            name = "${src.name} (副本)",
            colors = src.colors.toList()
        )
        userPalettes = userPalettes + newPal
        persistUserPalettes()
    }

    fun renamePalette(paletteId: String, newName: String) {
        if (newName.isBlank()) return
        userPalettes = userPalettes.map {
            if (it.id == paletteId) it.copy(name = newName) else it
        }
        persistUserPalettes()
    }

    fun deletePalette(paletteId: String) {
        userPalettes = userPalettes.filterNot { it.id == paletteId }
        persistUserPalettes()
    }

    fun importPaletteFromBitmap(bmp: android.graphics.Bitmap, name: String) {
        val scaled = if (bmp.width > 240 || bmp.height > 240) {
            val ratio = minOf(240f / bmp.width, 240f / bmp.height)
            android.graphics.Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else bmp
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val extracted = ColorQuantizer.extractPalette(pixels, targetCount = 30)
        createNewPalette(name.ifBlank { "智能提取色卡" }, extracted)
    }

    fun updateColorModel(model: String) {
        if (colorWheelInnerShape != "SQUARE" && model != "hsv") return
        colorModel = model
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("colorModel", model)
                .apply()
        }
    }

    fun addRecentColor(hex: String) {
        val upper = hex.uppercase()
        val list = recentColors.filterNot { it.equals(upper, ignoreCase = true) }.toMutableList()
        list.add(0, upper)
        recentColors = list.take(16)
        persistRecentColors()
    }

    fun clearRecentColors() {
        recentColors = emptyList()
        persistRecentColors()
    }

    fun updateColorWheelInnerShape(shape: String) {
        colorWheelInnerShape = shape
        if (shape != "SQUARE" && colorModel != "hsv") {
            updateColorModel("hsv")
        }
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("colorWheelInnerShape", shape)
                .apply()
        }
    }

    fun updateColorPanelTab(tab: Int) {
        colorPanelTab = tab
        if (tab == 2) {
            colorSphereBaseHex = brushColor
        }
    }

    private fun persistRecentColors() {
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("recentColors", recentColors.joinToString(","))
                .apply()
        }
    }

    private fun loadRecentColors(): List<String> {
        if (::appContext.isInitialized) {
            val saved = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .getString("recentColors", null)
            if (!saved.isNullOrBlank()) {
                return saved.split(",").filter { it.isNotBlank() }.take(16)
            }
        }
        return listOf(
            "#FFFFFF", "#D6D6D6", "#ADADAD", "#858585", "#5C5C5C", "#333333", "#141414", "#000000",
            "#F44336", "#FF9800", "#FFEB3B", "#4CAF50", "#00BCD4", "#2196F3", "#9C27B0", "#E91E63"
        )
    }

    private fun persistUserPalettes() {
        if (::appContext.isInitialized) {
            try {
                val arr = org.json.JSONArray()
                for (p in userPalettes) {
                    val o = org.json.JSONObject()
                    o.put("id", p.id)
                    o.put("name", p.name)
                    val colorsArr = org.json.JSONArray()
                    p.colors.forEach { colorsArr.put(it) }
                    o.put("colors", colorsArr)
                    arr.put(o)
                }
                appContext
                    .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("userPalettes", arr.toString())
                    .apply()
            } catch (e: Exception) { }
        }
    }

    private fun loadUserPalettes(): List<ColorPaletteItem> {
        if (::appContext.isInitialized) {
            val json = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .getString("userPalettes", null)
            if (!json.isNullOrBlank()) {
                return runCatching {
                    val arr = org.json.JSONArray(json)
                    val list = mutableListOf<ColorPaletteItem>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.getString("id")
                        val name = o.getString("name")
                        val colorsArr = o.getJSONArray("colors")
                        val colors = mutableListOf<String>()
                        for (j in 0 until colorsArr.length()) {
                            colors.add(colorsArr.getString(j))
                        }
                        list.add(ColorPaletteItem(id, name, colors))
                    }
                    list
                }.getOrDefault(emptyList())
            }
        }
        return listOf(
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
    }

    fun updateColorPickerMode(mode: String) {
        colorPickerMode = mode
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("colorPickerMode", mode)
                .apply()
        }
    }

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
        val isBackground: Boolean,
        val soloed: Boolean,
        val opacity: Double,
        val blendMode: String,
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

    // ===== 动画 (帧 / 轨道 / 时间轴) 状态镜像 =====
    // 引擎侧真身在 Krita 的 KisRasterKeyframeChannel (图层即轨道),
    // 这里只镜像 UI 需要的元信息。所有动画操作必须经
    // PaintViewModelAnimation.kt 的封装, 保持引擎调用不上 UI 线程。
    internal val anim = AnimationState()

    /** 动画音频播放器 (导入的音频资源, 播放动画时循环同步播放; 见 PaintViewModelAnimation.kt) */
    internal val animAudioPlayers = mutableListOf<android.media.MediaPlayer>()

    /** Report the visible canvas size (device px); keep render buffer at full native resolution */
    fun setRenderViewport(
        viewW: Int,
        viewH: Int,
    ) {
        if (viewW <= 0 || viewH <= 0) return
        val maxTex = 4096
        val scale =
            if (coreW > maxTex || coreH > maxTex) {
                minOf(maxTex.toFloat() / coreW, maxTex.toFloat() / coreH)
            } else {
                1f
            }
        val nw = maxOf(1, (coreW * scale).toInt())
        val nh = maxOf(1, (coreH * scale).toInt())
        if (nw != renderW || nh != renderH) {
            renderW = nw
            renderH = nh
            displayBufferInvalid = true
            scheduleRender(immediate = true)
        }
    }

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

    // Zero-allocation reusable canvas, rect and dirty array for buffer synchronization
    private val syncCanvas = android.graphics.Canvas()
    private val lastWrittenRect = android.graphics.Rect()
    private val renderDirty = IntArray(4)
    private var hasWrittenRect = false

    // 本帧实际写入区域 (渲染缓冲坐标 x/y/w/h), 供 UI 侧做局部失效。
    // 双缓冲交替发布: 渲染线程每帧只写一份不重复使用的副本, 读侧拿到的引用
    // 必然是完整一帧的结果, 且这条每帧路径上不产生任何新数组 (§4)。
    @Volatile internal var renderDirtySnapshot: IntArray? = null
    private val dirtySnapshotA = IntArray(4)
    private val dirtySnapshotB = IntArray(4)
    private var dirtySnapshotFlip = false

    @Volatile internal var displayBufferInvalid = false

    @Volatile internal var renderScheduled = false

    /** Pending multi-layer target set from the last replayed T_*_LAYERS
     *  recording event; consumed once by the following BEGIN / MOVE_CONTENT. */
    internal var pendingReplayLayers: IntArray? = null

    // The pending throttled-render runnable, kept so an immediate render can
    // actually cancel it (removeCallbacks needs the same instance; a bare
    // postDelayed creates a fresh Message each call that no token-based
    // removal can ever match).
    @Volatile internal var pendingRenderRunnable: Runnable? = null

    init {
        startRenderThread()
    }

    var stylusDriver: com.reverie.paint.core.stylus.StylusDriver? = null

    /** 应用是否处于前台 (由 MainActivity 的 onResume/onPause 维护)。 */
    internal var isAppForeground = true

    /**
     * 刷新纸张音效管线门控: 只有绘画页且前台才保持 AudioTrack 预热。
     *
     * 扩张性新增 (AGENTS.md §5): 不改既有签名, 仅由页面切换与前后台变化驱动。
     * 不做门控时音效引擎会在主页/回放页/后台持续写静音 PCM, 系统会把常驻的
     * USAGE_MEDIA 输出判为"应用在静音播放视频", 表现为静止时耗电仍偏高。
     */
    internal fun refreshStylusAudioGate(foreground: Boolean = isAppForeground) {
        isAppForeground = foreground
        stylusDriver?.refreshAudioGate(foreground)
    }

    fun getOrCreateStylusDriver(context: android.content.Context): com.reverie.paint.core.stylus.StylusDriver {
        val existing = stylusDriver
        if (existing != null) {
            return existing
        }
        val driver = com.reverie.paint.core.stylus.StylusDriver(context.applicationContext, this)
        stylusDriver = driver
        // 新建驱动时立即对齐场景门控, 避免在首页/回放页先预热一段
        driver.refreshAudioGate(isAppForeground)
        return driver
    }

    override fun onCleared() {
        stylusDriver?.release()
        stylusDriver = null
        stopAirbrush()
        mainHandler.removeCallbacks(persistParamsRunnable)
        persistBrushParams()
        mainHandler.removeCallbacks(persistToolStatesRunnable)
        persistToolBrushStates()
        recorder.endSession()
        replaySession?.stop()
        renderThread?.quitSafely()
        unregisterMemoryPressureCallbacks()
        renderThread = null
        renderHandler = null
        super.onCleared()
    }

    private val persistParamsRunnable = Runnable { persistBrushParams() }
    internal fun schedulePersistBrushParams(delayMs: Long = 250L) {
        mainHandler.removeCallbacks(persistParamsRunnable)
        mainHandler.postDelayed(persistParamsRunnable, delayMs)
    }

    // Same debounced-write pattern for per-tool brush states: slider ticks
    // update the in-memory map every event, disk writes coalesce 250ms after
    // the last tick.
    private val persistToolStatesRunnable = Runnable { persistToolBrushStates() }
    internal fun schedulePersistToolBrushStates(delayMs: Long = 250L) {
        mainHandler.removeCallbacks(persistToolStatesRunnable)
        mainHandler.postDelayed(persistToolStatesRunnable, delayMs)
    }

    internal fun startRenderThread() {
        val thread = HandlerThread("reverie-render", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
        // Ensure priority is set to URGENT_DISPLAY
        val h = renderHandler
        h?.post {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            )
        }
    }

    /**
     * Run a C++ document operation on the render thread, then schedule a
     * display refresh and (optionally) run [after] on the main thread.
     * All core mutation must go through here so it never races with the
     * projection recomposition running on the render thread.
     */
    internal fun runCore(
        render: Boolean = true,
        after: (() -> Unit)? = null,
        op: () -> Unit,
    ) {
        val h = renderHandler ?: return
        pendingCoreOps.incrementAndGet()
        h.post {
            pendingCoreOps.decrementPositive()
            try {
                op()
            } catch (t: Throwable) {
                android.util.Log.e("ReverieCore", "runCore op() threw, still dispatching after()", t)
            } finally {
                if (render) scheduleRender()
                if (after != null) mainHandler.post { after() }
            }
        }
    }

    internal val pendingCoreOps = java.util.concurrent.atomic.AtomicInteger(0)

    // Stroke-sample transport (batch-preserving). The UI thread appends every
    // touch sample into preallocated arrays and posts ONE drain runnable; the
    // render thread submits the whole batch in a single JNI call. The old
    // latest-wins single slot DROPPED intermediate samples whenever the
    // render thread was busy, which turned fast strokes into polylines and
    // lost pressure detail. Buffers are allocated once: zero allocation on
    // the hot path (架构铁律 §4).
    @Volatile private var pendingSampleX = 0.0
    @Volatile private var pendingSampleY = 0.0
    @Volatile private var pendingSampleP = 1.0
    private val strokeBatchLock = Any()
    private val strokeBatchCoords = FloatArray(STROKE_BATCH_CAPACITY * 3)
    private val strokeDrainCoords = FloatArray(STROKE_BATCH_CAPACITY * 3)
    private var strokeBatchCount = 0
    @Volatile private var strokeBatchQueued = false
    @Volatile private var lastQueuedInputEventTime = 0L
    @Volatile private var lastQueuedUptime = 0L
    private var perfLogCounter = 0

    private val strokeBatchRunnable = Runnable {
        val tDispatch = android.os.SystemClock.uptimeMillis()
        strokeBatchQueued = false
        val n: Int
        synchronized(strokeBatchLock) {
            n = strokeBatchCount
            strokeBatchCount = 0
            if (n > 0) {
                System.arraycopy(strokeBatchCoords, 0, strokeDrainCoords, 0, n * 3)
            }
        }
        pendingCoreOps.decrementPositive()
        val tKritaStart = android.os.SystemClock.uptimeMillis()
        val painted = n > 0 && try {
            ReverieCoreBridge.touchStrokeMoveBatch(strokeDrainCoords, n)
        } catch (_: Throwable) {
            false
        }
        val dtKrita = android.os.SystemClock.uptimeMillis() - tKritaStart
        // Render immediately in-place after ink landed to eliminate message queue roundtrip latency
        if (painted) {
            val tRenderStart = android.os.SystemClock.uptimeMillis()
            doRender()
            val dtRender = android.os.SystemClock.uptimeMillis() - tRenderStart
            val tDone = android.os.SystemClock.uptimeMillis()
            val queueWait = tDispatch - lastQueuedUptime
            val e2e = if (lastQueuedInputEventTime > 0) tDone - lastQueuedInputEventTime else 0L
            if (++perfLogCounter % 5 == 0) {
                android.util.Log.i(
                    "ReveriePerf",
                    "StrokePerf: n=$n queueWait=${queueWait}ms krita=${dtKrita}ms render=${dtRender}ms e2e=${e2e}ms"
                )
            }
        }
    }

    internal fun queueStrokeMove(x: Float, y: Float, p: Double, inputEventTimeMs: Long = 0L) {
        val h = renderHandler ?: return
        lastQueuedInputEventTime = inputEventTimeMs
        lastQueuedUptime = android.os.SystemClock.uptimeMillis()
        // Airbrush hold-still ticks mirror the latest sample position into
        // their recording; keep these legacy fields in sync.
        pendingSampleX = x.toDouble()
        pendingSampleY = y.toDouble()
        pendingSampleP = p
        synchronized(strokeBatchLock) {
            if (strokeBatchCount < STROKE_BATCH_CAPACITY) {
                val o = strokeBatchCount * 3
                strokeBatchCoords[o] = x
                strokeBatchCoords[o + 1] = y
                strokeBatchCoords[o + 2] = p.toFloat()
                strokeBatchCount++
            }
        }
        if (!strokeBatchQueued) {
            strokeBatchQueued = true
            pendingCoreOps.incrementAndGet()
            h.post(strokeBatchRunnable) // 预建 Runnable 直接投递, 每样本零分配
        }
    }

    /** Drop undelivered stroke samples (cancel path: they must not reach the
     *  engine after touchStrokeCancel ran). */
    internal fun clearPendingStrokeSamples() {
        synchronized(strokeBatchLock) {
            strokeBatchCount = 0
        }
    }

    // Airbrush hold-still ink flow: a self-rescheduling timer on the render
    // handler; every tick paints one dab via strokeAirbrushTick (engine-side,
    // same thread contract as queueStrokeMove). Ticks are not recorded into
    // the stroke event stream (known replay limitation).
    @Volatile private var airbrushActive = false
    private var airbrushIntervalMs = 950L
    private val airbrushRunnable: Runnable = Runnable {
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
            // Airbrush hold-still ticks enter the recording as ordinary
            // STROKE_MOVE samples at the same position/pressure, so playback
            // reproduces the continuous ink at the recorded cadence.
            if (recorder.recording) {
                recorder.strokeMove(
                    pendingSampleX.toFloat(),
                    pendingSampleY.toFloat(),
                    pendingSampleP.toFloat(),
                )
            }
        }
        h.postDelayed(airbrushRunnable, airbrushIntervalMs)
    }

    /** Arm the airbrush timer for the current stroke if the brush enables it.
     *  Also seeds the coalesced-sample fields so ticks fired before the first
     *  move record the stroke's true start point instead of stale values. */
    internal fun startAirbrushIfNeeded(
        x: Float,
        y: Float,
        p: Double,
    ) {
        pendingSampleX = x.toDouble()
        pendingSampleY = y.toDouble()
        pendingSampleP = p
        if (!brushAirbrush) return
        val rate = brushAirbrushRate.coerceIn(10.0, 120.0)
        airbrushIntervalMs = (1000.0 / rate).toLong().coerceIn(8L, 100L)
        airbrushActive = true
        renderHandler?.postDelayed(airbrushRunnable, airbrushIntervalMs)
    }

    /** Disarm the airbrush timer; safe to call repeatedly. */
    internal fun stopAirbrush() {
        airbrushActive = false
        renderHandler?.removeCallbacks(airbrushRunnable)
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
        if (painted) {
            scheduleRender()
        }
    }

    internal fun armStrokeStartKick() {
        renderHandler?.postDelayed(strokeStartKickRunnable, STROKE_START_KICK_MS)
    }

    internal fun disarmStrokeStartKick() {
        renderHandler?.removeCallbacks(strokeStartKickRunnable)
    }

    internal fun scheduleRender(immediate: Boolean = false) {
        val h = renderHandler ?: return
        if (immediate) {
            // touchEnd / undo / structural changes: render right away and
            // drop any pending throttled render (its 16ms-later run would
            // otherwise re-render the exact same state a second time)
            pendingRenderRunnable?.let { h.removeCallbacks(it) }
            pendingRenderRunnable = null
            renderScheduled = false
            h.post { doRender() }
            return
        }
        // Post immediately. The old fixed +16ms delay existed because renders
        // fired per touch event; now they only fire after the engine actually
        // painted ink (strokeBatchRunnable gates on touchStrokeMoveBatch's
        // result) or once per user op via runCore, so at most ONE render is
        // ever pending (renderScheduled dedupe) and doRender's input-first
        // defer still keeps queued stroke ops ahead of rendering. The delay
        // was two full frames of constant pen latency on 120Hz panels.
        if (renderScheduled) return
        renderScheduled = true
        val r =
            Runnable {
                pendingRenderRunnable = null
                renderScheduled = false
                doRender()
            }
        pendingRenderRunnable = r
        h.post(r)
    }

    internal var renderDeferCount = 0

    internal fun doRender() {
        renderScheduled = false
        renderDeferCount = 0
        val rh = renderHandler
        val w = renderW
        val h = renderH
        if (w <= 0 || h <= 0) return

        val sizeMismatch = backBuffer == null || backBuffer?.width != w || backBuffer?.height != h
        val reallocated = sizeMismatch || displayBufferInvalid
        if (reallocated) {
            frontBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            backBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            displayBufferInvalid = false
            hasWrittenRect = false
            // 缓冲重分配后旧脏区已无意义, 清掉免得 UI 侧拿它做局部失效
            renderDirtySnapshot = null
        }

        val front = frontBuffer
        val back = backBuffer ?: return

        // Synchronize previous frame's dirty region from front buffer to back buffer
        if (!reallocated && hasWrittenRect && !lastWrittenRect.isEmpty && front != null) {
            syncCanvas.setBitmap(back)
            syncCanvas.drawBitmap(front, lastWrittenRect, lastWrittenRect, null)
        }

        val forceFull = reallocated
        // Phase 2B: 先取 AGSL 预览数据再渲染 —— 放在 renderToBuffer 之前, 免得
        // "无脏区 ⇒ 直接 return" 的分支把这帧的预览更新吞掉(GPU 模式下缓冲本来就不变)。
        pollLiquifyGpuPreview()

        // renderToBuffer 是每帧最重的一步 (合成 + 像素转换)。标尺开启时按渲染路径分桶
        // 计时 (全量/增量/跳过), 关闭时整段只剩一次布尔判断 —— 分桶是为了回答
        // "到底走的是哪条路径、值不值得动它", 单看总耗时看不出来。
        val traceOn = PerfTrace.enabled
        val tEngineNs =
            if (traceOn) android.os.SystemClock.elapsedRealtimeNanos() else 0L
        val ok = ReverieCoreBridge.renderToBuffer(back, forceFull, renderDirty)
        if (traceOn) {
            PerfTrace.renderPath(
                when {
                    !ok -> PerfTrace.PATH_SKIP
                    forceFull -> PerfTrace.PATH_FULL
                    else -> PerfTrace.PATH_INCR
                },
                android.os.SystemClock.elapsedRealtimeNanos() - tEngineNs,
                if (ok) renderDirty[2].toLong() * renderDirty[3].toLong() else 0L,
            )
            if (w != coreW || h != coreH) PerfTrace.renderScaled()
        }
        PerfTrace.tick("render.calls", 1000L)
        if (!ok) {
            if (ReverieCoreBridge.renderPendingDirty()) {
                rh?.postDelayed({ doRender() }, 8L)
            }
            return
        }

        if (renderDirty[2] > 0 && renderDirty[3] > 0) {
            lastWrittenRect.set(
                renderDirty[0],
                renderDirty[1],
                renderDirty[0] + renderDirty[2],
                renderDirty[1] + renderDirty[3]
            )
            hasWrittenRect = true
            publishRenderDirtySnapshot()
        } else {
            hasWrittenRect = false
            renderDirtySnapshot = null
        }

        // Swap front and back buffers
        val rendered = back
        backBuffer = front
        frontBuffer = rendered
        displayBitmap = rendered

        // 纹理重传代理量: 每翻转一次, 下一帧 HWUI 都要把整张 Bitmap 纹理重传一遍
        // (HWUI 不做局部纹理更新), 这是本项目最大的带宽开销
        if (traceOn) PerfTrace.renderFlip(w.toLong() * h * 4, w.toLong() * h)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            rendered.prepareToDraw()
        }

        // Direct hardware invalidate from render thread (zero Handler hop, zero frame delay).
        // 走 invalidateFromRender: 视图侧能证明画面其他部分不受影响时只失效脏区,
        // 否则 (光标/预测笔迹/旋转/大脏区等) 自动回退为整屏重绘。
        val tv = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (tv != null) {
            tv.invalidateFromRender()
        } else {
            mainHandler.post {
                com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.invalidateFromRender()
            }
        }
        val isStrokeActive = strokeBatchQueued || (pendingCoreOps.get() > 0)
        if (!isStrokeActive) {
            mainHandler.post {
                com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.invalidateFromRender()
            }
        }

        // Notify Compose observers only when replay or reference window is active
        if (referenceWindowOpen || currentPage == Page.REPLAY) {
            mainHandler.post {
                displayRevision++
            }
        }
    }

    /** 把本帧写入区域发布给 UI 线程做局部失效 (双缓冲交替, 零分配) */
    private fun publishRenderDirtySnapshot() {
        val target = if (dirtySnapshotFlip) dirtySnapshotB else dirtySnapshotA
        dirtySnapshotFlip = !dirtySnapshotFlip
        target[0] = renderDirty[0]
        target[1] = renderDirty[1]
        target[2] = renderDirty[2]
        target[3] = renderDirty[3]
        renderDirtySnapshot = target
    }

    var layers by mutableStateOf(listOf<LayerUiState>())
        internal set

    var currentLayerIndex by mutableIntStateOf(-1)
        internal set

    val layerCount: Int get() = layers.size

    /** Mirror all native layer state into [layers] / [currentLayerIndex].
     * Must run on the main thread after any C++ layer mutation. */
    internal fun syncLayersFromNative() {
        val n = ReverieCoreBridge.layerCount()
        // Solo mode is a render-time filter: rows outside the keep set are
        // shown as hidden (eye off) in the panel while solo is active, but the
        // underlying layer state is untouched
        val soloKeep = if (ReverieCoreBridge.soloActive()) ReverieCoreBridge.layerSoloKeep().toSet() else null
        val list = ArrayList<LayerUiState>(n)
        for (i in 0 until n) {
            list.add(
                LayerUiState(
                    index = i,
                    id = ReverieCoreBridge.layerId(i),
                    name = ReverieCoreBridge.layerName(i),
                    visible = if (soloKeep != null) i in soloKeep else ReverieCoreBridge.layerVisible(i),
                    locked = ReverieCoreBridge.layerLocked(i),
                    alphaLocked = ReverieCoreBridge.layerAlphaLocked(i),
                    isGroup = ReverieCoreBridge.layerIsGroup(i),
                    nodeType = ReverieCoreBridge.layerNodeType(i),
                    depth = ReverieCoreBridge.layerDepth(i),
                    colorLabel = ReverieCoreBridge.layerColorLabel(i),
                    clipped = ReverieCoreBridge.layerClipped(i),
                    isBackground = ReverieCoreBridge.layerBackground(i),
                    soloed = ReverieCoreBridge.layerSoloed(i),
                    opacity = ReverieCoreBridge.layerOpacity(i),
                    blendMode = ReverieCoreBridge.layerBlendMode(i),
                ),
            )
        }
        layers = list
        currentLayerIndex = ReverieCoreBridge.currentLayerIndex()

        // 时间轴"选中轨道"与当前图层强制对齐 (修"创建帧错乱"):
        // selectedTrack 旧实现只在时间轴 tap 时写入且**永不重置** —— 用户在
        // 时间轴点过任意一行后, 去图层面板切了别的图层再点"新建帧"/落笔
        // 自动建帧, 帧会建到上次点过的那条轨道上 (selectedTrackIndex()
        // 优先读它)。UI 上选中轨道与当前图层本来就是同时设置 (轨道区 tap
        // 两处都写), 这里把图层面板改层 / undo 删层 / 切换文档等路径也
        // 收敛到同一状态, 帧的落轨依据从此只有 currentLayerIndex。
        if (anim.enabled && anim.selectedTrack != currentLayerIndex) {
            anim.selectedTrack = currentLayerIndex
        }
    }

    internal fun isAppContextReady(): Boolean = ::appContext.isInitialized

    var currentProjectFile by mutableStateOf<String?>(null)
    var isBlockingLoading by mutableStateOf(false)
    var blockingLoadingMessage by mutableStateOf("")
    var currentFolder by mutableStateOf<com.reverie.paint.model.Project?>(null)
    var searchQuery by mutableStateOf("")
    var pendingExternalImageUri by mutableStateOf<android.net.Uri?>(null)
    var isDraggingExternal by mutableStateOf(false)

    /** Injected by MainActivity; the engine needs it for file paths. */
    lateinit var appContext: android.content.Context
    var selectionMask: ByteArray? by mutableStateOf(null)
    var hasSelection by mutableStateOf(false)

    // Semi-transparent blue overlay bitmap built from the mask, drawn on top
    // of the canvas so the user can see the active selection (Krita-style)
    var selectionOverlayBitmap: android.graphics.Bitmap? by mutableStateOf(null)

    var transformPreviewBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
    var transformCopyOnly: Boolean by mutableStateOf(false)
    var isImportTransformPending: Boolean by mutableStateOf(false)
    var isSelectionTransformPending: Boolean by mutableStateOf(false)
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
    val blendModes =
        listOf(
            "normal" to "正常",
            "multiply" to "正片叠底",
            "screen" to "滤色",
            "overlay" to "叠加",
            "darken" to "变暗",
            "lighten" to "变亮",
            "dodge" to "颜色减淡",
            "burn" to "颜色加深",
            "linear_burn" to "线性加深",
            "linear_dodge" to "线性减淡",
            "difference" to "差值",
            "add" to "增加",
            "subtract" to "减去",
            "divide" to "划分",
            "hard_light" to "强光",
            "soft_light" to "柔光",
            "vivid_light" to "亮光",
            "pin_light" to "点光",
            "linear light" to "线性光",
            "exclusion" to "排除",
            "hue" to "色相",
            "saturation" to "饱和度",
            "color" to "颜色",
            "value" to "明度",
            "erase" to "擦除",
        )
    var pickerCurrentLayerOnly by mutableStateOf(false)
    var isFilterAdjustActive by mutableStateOf(false)

    internal var filterPreviewJob: kotlinx.coroutines.Job? = null

    // ---- Drawing-process recording / playback (录制回放) ----

    /** Session recorder: captures strokes, context and document ops. */
    internal val recorder = PaintRecorder()

    /** Active replay session; null while not replaying. */
    var replaySession by mutableStateOf<ReplaySession?>(null)
        internal set

    /** Last scalar filter preview params [type, p1..p4] captured at commit. */
    internal var lastFilterPreviewParams: DoubleArray? = null
    // LUT payloads captured for recording (curves RGB 3x256B / gradient map 256 ints)
    internal var lastCurvesLUT: ByteArray? = null
    internal var lastGradientMapLut: IntArray? = null
}

enum class Page { HOME, CREATE, PAINTING, REPLAY }

/** Krita-style brush grouping: strictly aligned with Krita default presets. */
fun inferBrushGroup(name: String): String =
    when {
        name.startsWith("a)") || name.contains("Eraser", ignoreCase = true) -> "橡皮擦"
        name.startsWith("e)") || name.contains("Marker", ignoreCase = true) -> "马克笔"
        name.startsWith("t)") || name.contains("Shape", ignoreCase = true) || (name.contains("Fill", ignoreCase = true) && !name.contains("starfield", ignoreCase = true)) -> "形状"
        name.startsWith("u)") || name.contains("Pixel", ignoreCase = true) || name.contains("pixel") -> "像素画"
        name.startsWith("l)") || name.startsWith("x)") || name.contains("Adjust", ignoreCase = true) || name.contains("FX", ignoreCase = true) || name.contains("Distort", ignoreCase = true) || name.contains("Clone", ignoreCase = true) || name.contains("Filter", ignoreCase = true) || name.contains("Move_tool") -> "特效与滤镜"
        name.startsWith("d)") || name.startsWith("Ink", ignoreCase = true) || name.contains("_Ink", ignoreCase = true) || name.contains("Gpen", ignoreCase = true) || name.contains("ballpen", ignoreCase = true) || name.contains("sumi-e", ignoreCase = true) -> "勾线"
        name.startsWith("c)") || name.startsWith("h)") || name.contains("Pencil", ignoreCase = true) || name.contains("Charcoal", ignoreCase = true) || name.contains("Chalk", ignoreCase = true) || name.contains("Pastel", ignoreCase = true) -> "铅笔"
        name.startsWith("b)") || name.contains("Airbrush", ignoreCase = true) || name.contains("Basic", ignoreCase = true) || name.startsWith("Layout") || name.startsWith("Quick") -> "基础"
        name.startsWith("i)") || name.startsWith("j)") || name.contains("Wet", ignoreCase = true) || name.contains("Water", ignoreCase = true) || name.contains("Sparkle_wet") || name.contains("Splat_wet") -> "水彩"
        name.startsWith("k)") || name.contains("Blender", ignoreCase = true) || name.contains("Smudge", ignoreCase = true) -> "混合"
        name.startsWith("v)_Sketching") || name.contains("Sketch", ignoreCase = true) || name.contains("Curve", ignoreCase = true) -> "速写"
        name.startsWith("f)") || name.startsWith("g)") || name.contains("Bristle", ignoreCase = true) || name.contains("Oils", ignoreCase = true) || name.contains("Block") || name.contains("Dry") -> "绘画"
        name.startsWith("w)") || name.startsWith("y)") || name.contains("Texture", ignoreCase = true) || name.contains("Textured", ignoreCase = true) || name.contains("Screentone", ignoreCase = true) || name.contains("Hatch", ignoreCase = true) || name.contains("Tangent", ignoreCase = true) || name.contains("Grid", ignoreCase = true) || name.contains("Sponge", ignoreCase = true) || name.contains("Rake", ignoreCase = true) || name.contains("Brush_", ignoreCase = true) -> "纹理与排线"
        name.startsWith("z)") || name.contains("Stamp", ignoreCase = true) || name.contains("Spray", ignoreCase = true) || name.contains("Splat", ignoreCase = true) || name.contains("particles", ignoreCase = true) || name.contains("Fuzzy", ignoreCase = true) || name.contains("dyna_dots", ignoreCase = true) || name.contains("Experimental", ignoreCase = true) -> "印章与喷溅"
        else -> "基础"
    }

/** Per-preset independent brush parameters. */
data class BrushParams(
    val size: Double = 20.0,
    val opacity: Double = 1.0,
    val flow: Double = 1.0,
    val spacing: Double = 0.1,
    val angle: Double = 0.0,
    val scatter: Double = 0.0,
    val fade: Double = 0.0,
    val softness: Double = 0.5,
    val ratio: Double = 1.0,
    val sharpness: Double = 0.0,
    val rotation: Double = 0.0,
    val compositeOp: String = "normal",
    val antiAliasing: Int = 1,
    val tipShape: Int = 0,
    val randomFlipX: Boolean = false,
    val randomFlipY: Boolean = false,
    val followDirection: Boolean = false,
    val streamline: Double = 0.0,
    val taper: Double = 0.0,
    val textureEnabled: Boolean = false,
    val textureScale: Double = 1.0,
    val textureStrength: Double = 0.5,
    val textureMode: String = "multiply",
    val hueJitter: Double = 0.0,
    val satJitter: Double = 0.0,
    val valJitter: Double = 0.0,
    val secondaryMix: Double = 0.0,
    val pressureColorMix: Boolean = false,
    val pressureEnabled: Boolean = true,
    val pressureSize: Double = 1.0,
    val pressureOpacity: Double = 1.0,
    val pressureFlow: Double = 1.0,
    val speedSize: Double = 0.0,
    val pressureCurve: Int = 0,
    val minSizeLimit: Double = 1.0,
    val maxSizeLimit: Double = 500.0,
    val tipAsset: String = "",
    val paintOpId: String = "defaultpaintop",
    val airbrush: Boolean = false,
    val airbrushRate: Double = 30.0,
    val smudgeRate: Double = 0.5,
    val smudgeLength: Double = 0.5,
    val spikes: Int = 2,
    val jitterAngle: Double = 0.0,
    val jitterSize: Double = 0.0,
    val author: String = "ReveriePaint",
    val isAuthorLocked: Boolean = false,
    val description: String = "",
    val version: String = "1.0",
)

/** A bundled Krita brush preset (.kpp) with its PNG thumbnail. */
data class BrushPresetInfo(
    val index: Int,
    val name: String,
    val thumbBytes: ByteArray,
    val group: String = "", // effective group (custom override or inferred)
    val isBuiltIn: Boolean = false,
)

val BUILT_IN_BRUSH_GROUPS = setOf(
    "全部", "常用", "最近", "基础", "铅笔", "勾线", "马克笔", "绘画", "水彩", "混合",
    "速写", "形状", "特效与滤镜", "纹理与排线", "印章与喷溅", "像素画", "橡皮擦", "导入"
)

fun isBuiltInBrushGroup(group: String, presets: List<BrushPresetInfo> = emptyList()): Boolean {
    if (BUILT_IN_BRUSH_GROUPS.contains(group)) return true
    if (presets.any { it.isBuiltIn && it.group == group }) return true
    return false
}

fun PaintViewModel.isBuiltInGroup(group: String): Boolean = isBuiltInBrushGroup(group, brushPresets)
