/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import com.reverie.paint.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** Max stroke samples buffered between render-thread drains. Sized for a
 *  240Hz stylus under heavy multi-frame stalls with generous headroom (256 samples);
 *  preallocated once, zero allocation on hot path. */
private const val STROKE_BATCH_CAPACITY = 256

/** Delay before the stroke-start idle kick flushes a pen-down dot. */
private const val STROKE_START_KICK_MS = 24L

private fun java.util.concurrent.atomic.AtomicInteger.decrementPositive() {
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
        if (!autoSaveEnabled || isAutoSaving || isBlockingLoading) return
        if (currentPage != Page.PAINTING) return
        if (!hasUnsavedChanges()) return

        // 软件切入后台时，立即触发后台静默自动保存
        autoSaveProject()
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
    var brushAirbrushRate by mutableDoubleStateOf(0.05)
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
    internal val brushParams: MutableMap<String, BrushParams> = mutableMapOf()

    // Display bitmap (updated in place via renderToBuffer).
    // neverEqualPolicy: the same Bitmap object is mutated and re-assigned,
    // so referential equality would never notify Compose to repaint.
    var displayBitmap by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
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

    // Reference Tool Window State (常态固定显示参考窗口)
    var referenceWindowOpen by mutableStateOf(false)
    var referenceImages by mutableStateOf<List<Bitmap>>(emptyList())
    var referenceIsGrayscale by mutableStateOf(false)
    var referenceAllowRotation by mutableStateOf(true)
    var referenceIsFlipped by mutableStateOf(false)
    var referenceActiveTab by mutableIntStateOf(0) // 0: 图片, 1: 画布
    var referenceBarsCollapsed by mutableStateOf(false)

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
                appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("ref_images_count", currentImgs.size).apply()
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "Failed to persist reference images", e)
            }
        }
    }

    fun loadPersistedReferenceImages() {
        if (!::appContext.isInitialized) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(appContext.filesDir, "ref_images")
                val count = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("ref_images_count", 0)
                if (dir.exists() && count > 0) {
                    val list = mutableListOf<Bitmap>()
                    for (i in 0 until count) {
                        val file = java.io.File(dir, "ref_$i.png")
                        if (file.exists()) {
                            val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            if (bmp != null) list.add(bmp)
                        }
                    }
                    if (list.isNotEmpty()) {
                        viewModelScope.launch(Dispatchers.Main) {
                            referenceImages = list
                        }
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

    fun importReferenceImagesFromUris(uris: List<android.net.Uri>) {
        if (!::appContext.isInitialized || uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val newBitmaps = mutableListOf<Bitmap>()
            for (uri in uris) {
                try {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            newBitmaps.add(bmp)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ReveriePaint", "Failed to load reference image $uri", e)
                }
            }
            if (newBitmaps.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.Main) {
                    referenceImages = referenceImages + newBitmaps
                    referenceActiveTab = 0
                    resetReferenceTransform()
                    persistReferenceImages()
                    persistReferenceState()
                }
            }
        }
    }

    fun importReferenceImageFromUri(uri: android.net.Uri) {
        importReferenceImagesFromUris(listOf(uri))
    }

    fun clearReferenceImage() {
        referenceImages = emptyList()
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
    var accentColorHex by mutableStateOf("#5E8BA8")
    var monetEnabled by mutableStateOf(false) // 莫奈动态取色
    var themeMode by mutableStateOf("DARK") // "DARK", "LIGHT", "SYSTEM"
    var paintingUiScale by mutableFloatStateOf(1.0f) // 绘画页面整体 UI 大小缩放 (0.75 - 1.35)
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

    // Stroke Stabilizer (抖动修正: 0.0 ~ 1.0)
    var strokeStabilizer by mutableFloatStateOf(0.15f)

    // Keyboard Shortcuts (参考图 2)
    var shortcutBindings by mutableStateOf<Map<String, String>>(emptyMap())

    // Stylus Settings (画世界 Pro & Krita style, persisted)
    var penOnlyMode by mutableStateOf(false) // 笔模式 (禁止手指绘制，单指平移，双指缩放旋转)
    var brushCursorMode by mutableIntStateOf(0) // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
    var eraserCursorMode by mutableIntStateOf(3)
    var cursorStyleMode by mutableIntStateOf(0) // 0: 圆形, 1: 十字准星, 2: 点, 3: 无, 4: 系统指针, 5: 圆+十字准星
    var quickShapeEnabled by mutableStateOf(false) // 驻停线条成形 (已禁用)
    var activeQuickShape by mutableStateOf<QuickShapeResult?>(null)
    var isQuickShapeEditing by mutableStateOf(false)

    // 触控预测与输入延迟优化
    var motionPredictorEnabled by mutableStateOf(true)

    // 绘图辅助与参考线状态 (Symmetry, Perspective, Grid)
    var drawingGuide by mutableStateOf(DrawingGuideConfig())

    // 画布内富文本排版状态 (In-place Typography)
    var typographyConfig by mutableStateOf(TypographyConfig())
    var isTypographyEditing by mutableStateOf(false)

    var pressureCurvePreset by mutableIntStateOf(0) // 0: 线性, 1: 轻压灵敏, 2: 重压偏硬, 3: S型, 4: 自定义
    var pressureControlPoints by mutableStateOf(
        listOf(
            androidx.compose.ui.geometry
                .Offset(0f, 0f),
            androidx.compose.ui.geometry
                .Offset(0.33f, 0.33f),
            androidx.compose.ui.geometry
                .Offset(0.66f, 0.66f),
            androidx.compose.ui.geometry
                .Offset(1f, 1f),
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

    var selectionMode by mutableIntStateOf(0) // 0: 替换, 1: 添加, 2: 减去, 3: 相交
    var selectionTolerance by mutableIntStateOf(24)
    var selectionSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层
    var selectionFeatherRadius by mutableIntStateOf(0)
    var selectionCloseGap by mutableIntStateOf(4) // 闭合空隙 (0..16 px)
    var selectionExpand by mutableIntStateOf(0) // 拓展 (-16..32 px)

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

    /** Evaluate mapped pressure from raw input pressure using the active curve (monotonic piecewise cubic spline) */
    fun evaluatePressure(raw: Float): Float {
        val x = raw.coerceIn(0f, 1f)
        val pts = pressureControlPoints.sortedBy { it.x }
        if (pts.size < 2) return x
        if (x <= pts.first().x) return pts.first().y.coerceIn(0.01f, 1f)
        if (x >= pts.last().x) return pts.last().y.coerceIn(0.01f, 1f)

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
        return y.coerceIn(0.01f, 1f)
    }

    /**
     * Hot-path variant of [evaluatePressure] used per stroke sample.
     * Assumes control points are kept ascending by x (the curve editor
     * clamps each point past its left neighbour and sorts on insert), so
     * no per-call sort/allocation happens. Collinear default points yield
     * an exact identity mapping.
     */
    internal fun applyGlobalPressureCurve(raw: Double): Double {
        val pts = pressureControlPoints
        val n = pts.size
        if (n < 2) return raw
        val x = raw.toFloat().coerceIn(0f, 1f)
        if (x <= pts[0].x) return pts[0].y.coerceIn(0.01f, 1f).toDouble()
        if (x >= pts[n - 1].x) return pts[n - 1].y.coerceIn(0.01f, 1f).toDouble()
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
        return y.coerceIn(0.01f, 1f).toDouble()
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

    fun updatePressureCurvePreset(preset: Int) {
        pressureCurvePreset = preset
        when (preset) {
            0 -> {
                pressureControlPoints =
                    listOf(
                        androidx.compose.ui.geometry
                            .Offset(0f, 0f),
                        androidx.compose.ui.geometry
                            .Offset(0.33f, 0.33f),
                        androidx.compose.ui.geometry
                            .Offset(0.66f, 0.66f),
                        androidx.compose.ui.geometry
                            .Offset(1f, 1f),
                    )
            }

            1 -> {
                pressureControlPoints =
                    listOf( // Soft / Convex (Huawei sensitive)
                        androidx.compose.ui.geometry
                            .Offset(0f, 0f),
                        androidx.compose.ui.geometry
                            .Offset(0.15f, 0.65f),
                        androidx.compose.ui.geometry
                            .Offset(0.45f, 0.90f),
                        androidx.compose.ui.geometry
                            .Offset(1f, 1f),
                    )
            }

            2 -> {
                pressureControlPoints =
                    listOf( // Hard / Concave
                        androidx.compose.ui.geometry
                            .Offset(0f, 0f),
                        androidx.compose.ui.geometry
                            .Offset(0.55f, 0.10f),
                        androidx.compose.ui.geometry
                            .Offset(0.85f, 0.35f),
                        androidx.compose.ui.geometry
                            .Offset(1f, 1f),
                    )
            }

            3 -> {
                pressureControlPoints =
                    listOf( // S-Curve
                        androidx.compose.ui.geometry
                            .Offset(0f, 0f),
                        androidx.compose.ui.geometry
                            .Offset(0.40f, 0.10f),
                        androidx.compose.ui.geometry
                            .Offset(0.60f, 0.90f),
                        androidx.compose.ui.geometry
                            .Offset(1f, 1f),
                    )
            }

            4 -> {
                pressureControlPoints =
                    listOf( // Extreme
                        androidx.compose.ui.geometry
                            .Offset(0f, 0f),
                        androidx.compose.ui.geometry
                            .Offset(0.10f, 0.85f),
                        androidx.compose.ui.geometry
                            .Offset(0.90f, 0.95f),
                        androidx.compose.ui.geometry
                            .Offset(1f, 1f),
                    )
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
            uiOpacity = prefs.getFloat("uiOpacity", 1.0f)
            popupPanelOpacity = prefs.getFloat("popupPanelOpacity", 0.95f)
            paintingUiScale = prefs.getFloat("paintingUiScale", 1.0f).coerceIn(0.70f, 1.40f)
            blurBackground = prefs.getBoolean("blurBackground", true) &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            accentColorHex = prefs.getString("accentColor", "#5E8BA8") ?: "#5E8BA8"
            canvasBgColorHex = prefs.getString("canvasBgColor", "DEFAULT") ?: "DEFAULT"
            monetEnabled = prefs.getBoolean("monetEnabled", false)
            themeMode = prefs.getString("themeMode", "DARK") ?: "DARK"
            immersiveMode = prefs.getBoolean("immersiveMode", false)
            extendToCutout = prefs.getBoolean("extendToCutout", true)
            penOnlyMode = prefs.getBoolean("penOnlyMode", false)
            gestureTwoFingerUndo = prefs.getBoolean("gestureTwoFingerUndo", true)
            gestureThreeFingerRedo = prefs.getBoolean("gestureThreeFingerRedo", true)
            gesturePinchTransform = prefs.getBoolean("gesturePinchTransform", true)
            gestureQuickPinchFit = prefs.getBoolean("gestureQuickPinchFit", true)
            longPressEyedropperEnabled = prefs.getBoolean("longPressEyedropperEnabled", true)
            eyedropperSensitivity = prefs.getInt("eyedropperSensitivity", 3).coerceIn(1, 5)
            eyedropperOffsetEnabled = prefs.getBoolean("eyedropperOffsetEnabled", true)
            brushCursorMode = prefs.getInt("brushCursorMode", 0)
            eraserCursorMode = prefs.getInt("eraserCursorMode", 3)
            cursorStyleMode = prefs.getInt("cursorStyleMode", 0)
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

            autoSaveEnabled = prefs.getBoolean("autoSaveEnabled", true)
            autoSaveIntervalMinutes = prefs.getInt("autoSaveIntervalMinutes", 5).coerceIn(1, 60)
            autoSaveToastEnabled = prefs.getBoolean("autoSaveToastEnabled", true)
            maxUndoSteps = prefs.getInt("maxUndoSteps", 50).coerceIn(10, 200)
            promptSaveOnExit = prefs.getBoolean("promptSaveOnExit", true)

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

    var colorHarmonyModeName by mutableStateOf("COMPLEMENTARY")
    var colorHarmonyBaseHue by mutableFloatStateOf(-1f)

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
    // Buffers stay INCREMENTAL: after each render the freshly written region
    // is blitted from the just-rendered (fully up-to-date) buffer into every
    // other non-displayed buffer, tracked per-buffer in [bufferMissing].
    // Displayed/pending buffers accumulate their missing union instead and
    // need one forceFull render when recycled - without the replication every
    // frame degenerated into a full-frame render, which saturated the render
    // thread and made fast scribbling stutter.
    internal var displayBuffers: Array<Bitmap?> = arrayOfNulls(3)

    internal var bufferMissing: Array<android.graphics.Rect?> = arrayOfNulls(3)

    // Reusable canvas, rect and dirty array for zero-allocation back-buffer synchronization
    private val syncCanvas = android.graphics.Canvas()
    private val renderWrittenRect = android.graphics.Rect()
    private val renderDirty = IntArray(4)

    internal var lastRenderIdx = -1

    // The buffer most recently handed to the main thread for display; still
    // readable by an in-flight frame, so never render into it either.
    @Volatile internal var pendingDisplay: Bitmap? = null

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

    override fun onCleared() {
        stopAirbrush()
        mainHandler.removeCallbacks(persistParamsRunnable)
        persistBrushParams()
        mainHandler.removeCallbacks(persistToolStatesRunnable)
        persistToolBrushStates()
        recorder.endSession()
        replaySession?.stop()
        renderThread?.quitSafely()
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
        val thread = HandlerThread("reverie-render")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
        // First runnable on the thread: raise priority so stroke flushes and
        // projection recomposition win CPU contention over background work.
        val h = renderHandler
        h?.post {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_DISPLAY
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
        // Advisory input-ops counter: doRender defers behind queued ops so
        // stroke samples extend before the (heavier) render runs
        pendingCoreOps.incrementAndGet()
        h.post {
            pendingCoreOps.decrementPositive()
            op()
            if (render) scheduleRender()
            if (after != null) mainHandler.post { after() }
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

    private val strokeBatchRunnable = Runnable {
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
        val painted = n > 0 && try {
            ReverieCoreBridge.touchStrokeMoveBatch(strokeDrainCoords, n)
        } catch (_: Throwable) {
            false
        }
        // Render only after real ink landed. Scheduling a +16ms render for
        // no-flush samples used to steal render-thread time from input,
        // widening the coalescing window and compounding pen latency.
        if (painted) {
            scheduleRender()
        }
    }

    internal fun queueStrokeMove(x: Float, y: Float, p: Double) {
        val h = renderHandler ?: return
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
        airbrushIntervalMs = ((1000.0 * (1.0 - brushAirbrushRate)).coerceAtLeast(20.0)).toLong()
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

    internal var renderDeferCount = 0

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

    internal fun doRender() {
        renderScheduled = false
        val rh = renderHandler
        // Input-first: if stroke ops are queued ahead on this thread, let the
        // stroke extend first and render after (each render waits for the
        // projection recomposite - blocking it while input waits behind was
        // felt as lag/stutter during fast scribbling). Bounded to two 4ms
        // defers so rendering can never starve.
        if (rh != null && pendingCoreOps.get() > 0 && renderDeferCount < 2) {
            renderDeferCount++
            rh.postDelayed({ doRender() }, 4L)
            return
        }
        renderDeferCount = 0
        val w = renderW
        val h = renderH
        if (w <= 0 || h <= 0) return
        // Round-robin over the buffers, never touching the displayed or the
        // pending-display bitmap (both may still be read by Compose)
        val displayed = displayBitmap
        var idx = (lastRenderIdx + 1) % displayBuffers.size
        if (displayBuffers[idx] === displayed || displayBuffers[idx] === pendingDisplay) {
            idx = (idx + 1) % displayBuffers.size
        }
        lastRenderIdx = idx
        var target = displayBuffers[idx]
        val sizeMismatch = target == null || target.width != w || target.height != h
        val reallocated = sizeMismatch || displayBufferInvalid
        if (reallocated) {
            target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            displayBuffers[idx] = target
            displayBufferInvalid = false
        }
        // Full frame only when the buffer is brand new or missed updates
        // while it was displayed (its missing union is non-null)
        val forceFull = reallocated || bufferMissing[idx] != null
        val buf = target ?: return
        val ok = ReverieCoreBridge.renderToBuffer(buf, forceFull, renderDirty)
        if (!ok) {
            // Skipped because the async projection recomposite is still
            // running (non-blocking render): retry shortly so the frame
            // lands as soon as the projection settles, without ever making
            // queued input ops wait behind a blocking waitForDone
            if (ReverieCoreBridge.renderPendingDirty()) {
                rh?.postDelayed({ doRender() }, 8L)
            }
            return
        }
        if (ok) {
            bufferMissing[idx] = null
            if (renderDirty[2] > 0 && renderDirty[3] > 0) {
                renderWrittenRect.set(
                    renderDirty[0],
                    renderDirty[1],
                    renderDirty[0] + renderDirty[2],
                    renderDirty[1] + renderDirty[3]
                )
                // Keep every OTHER buffer in sync: non-displayed ones get the
                // region blitted straight from the just-rendered buffer (it is
                // fully up to date), displayed/pending ones just accumulate
                // their missing union for when they are recycled
                for (j in displayBuffers.indices) {
                    if (j == idx) continue
                    val other = displayBuffers[j] ?: continue
                    if (other === displayed || other === pendingDisplay) continue
                    val miss = bufferMissing[j]
                    syncCanvas.setBitmap(other)
                    if (miss == null) {
                        // Up to date except for this render's region
                        syncCanvas.drawBitmap(buf, renderWrittenRect, renderWrittenRect, null)
                    } else {
                        miss.union(renderWrittenRect)
                        syncCanvas.drawBitmap(buf, miss, miss, null)
                        bufferMissing[j] = null
                    }
                }
                for (j in displayBuffers.indices) {
                    val other = displayBuffers[j] ?: continue
                    if (other === displayed || other === pendingDisplay) {
                        val m = bufferMissing[j]
                        if (m != null) {
                            m.union(renderWrittenRect)
                        } else {
                            bufferMissing[j] = android.graphics.Rect(renderWrittenRect)
                        }
                    }
                }
            }
            pendingDisplay = buf
            // Native wrote the back buffer's pixels on the render thread; the
            // UI thread only flips the Compose reference and bumps the
            // revision. A no-op render (nothing painted since the last frame)
            // returns false and skips this flip entirely.
            mainHandler.post {
                if (displayBitmap !== buf) displayBitmap = buf
                displayRevision++
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    buf.prepareToDraw()
                }
            }
        }
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
    }

    internal fun isAppContextReady(): Boolean = ::appContext.isInitialized

    var currentProjectFile by mutableStateOf<String?>(null)
    var isBlockingLoading by mutableStateOf(false)
    var blockingLoadingMessage by mutableStateOf("")
    var currentFolder by mutableStateOf<com.reverie.paint.model.Project?>(null)
    var searchQuery by mutableStateOf("")

    /** Injected by MainActivity; the engine needs it for file paths. */
    lateinit var appContext: android.content.Context
    var selectionMask: ByteArray? by mutableStateOf(null)
    var hasSelection by mutableStateOf(false)

    // Semi-transparent blue overlay bitmap built from the mask, drawn on top
    // of the canvas so the user can see the active selection (Krita-style)
    var selectionOverlayBitmap: android.graphics.Bitmap? by mutableStateOf(null)

    var transformPreviewBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
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
    val airbrushRate: Double = 0.05,
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
