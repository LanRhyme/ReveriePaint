package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
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

    var docWidth by mutableStateOf(1080)
    var docHeight by mutableStateOf(1920)
    var docName by mutableStateOf("Untitled")
    var totalStrokes by mutableStateOf(0)
    var initialStrokeCount by mutableStateOf(0)
    var isModified by mutableStateOf(false)
    var elapsedSeconds by mutableStateOf(0L)
    var canvasCreatedTime by mutableStateOf(System.currentTimeMillis())
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
        timerJob?.cancel()
        timerJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(1000L)
                    if (currentPage == Page.PAINTING) {
                        tickPaintingTimer()
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

    fun markModified() {
        isModified = true
    }

    fun hasUnsavedChanges(): Boolean {
        val strokesAdded = totalStrokes > initialStrokeCount
        return isModified || strokesAdded || ReverieCoreBridge.canUndo()
    }

    // Brush state
    var brushSize by mutableStateOf(20.0)
    var brushColor by mutableStateOf("#000000")
    var brushSecondaryColor by mutableStateOf("#ffffff")
    var brushOpacity by mutableStateOf(1.0)
    var brushPresets by mutableStateOf<List<BrushPresetInfo>>(emptyList())
    var brushPresetIndex by mutableStateOf(-1)

    // User-defined brush groups: preset name -> group name; and the list of
    // custom group names the user created (persisted in SharedPreferences)
    var userBrushGroups by mutableStateOf<Map<String, String>>(emptyMap())
    var customBrushGroups by mutableStateOf<List<String>>(emptyList())

    // Custom display order of presets (persisted); empty = default (sorted)
    var brushOrder by mutableStateOf<List<String>>(emptyList())
    var categoryOrder by mutableStateOf<List<String>>(emptyList())
    var brushFlow by mutableStateOf(1.0)
    var brushSpacing by mutableStateOf(0.1)
    var brushAngle by mutableStateOf(0.0)
    var brushScatter by mutableStateOf(0.0)
    var brushFade by mutableStateOf(0.0)
    var brushSoftness by mutableStateOf(0.5)
    var brushRatio by mutableStateOf(1.0)
    var brushSharpness by mutableStateOf(0.0)
    var brushRotation by mutableStateOf(0.0)
    var brushCompositeOp by mutableStateOf("normal")

    // Extended brush studio properties
    var brushAntiAliasing by mutableStateOf(1) // 0: 无, 1: 正常, 2: 强化, 3: 分级
    var brushTipShape by mutableStateOf(0) // 0: 圆形笔触, 1: 方形笔触
    var brushRandomFlipX by mutableStateOf(false)
    var brushRandomFlipY by mutableStateOf(false)
    var brushFollowDirection by mutableStateOf(false)
    var brushStreamline by mutableStateOf(0.0)
    var brushTaper by mutableStateOf(0.0)
    var brushTextureEnabled by mutableStateOf(false)
    var brushTextureScale by mutableStateOf(1.0)
    var brushTextureStrength by mutableStateOf(0.5)
    var brushTextureMode by mutableStateOf("multiply")
    var brushHueJitter by mutableStateOf(0.0)
    var brushSatJitter by mutableStateOf(0.0)
    var brushValJitter by mutableStateOf(0.0)
    var brushSecondaryMix by mutableStateOf(0.0)
    var brushPressureColorMix by mutableStateOf(false)
    var brushPressureEnabled by mutableStateOf(true)
    var brushPressureSize by mutableStateOf(1.0)
    var brushPressureOpacity by mutableStateOf(1.0)
    var brushPressureFlow by mutableStateOf(1.0)
    var brushSpeedSize by mutableStateOf(0.0)
    var brushPressureCurve by mutableStateOf(0) // 0: 线性, 1: 柔和, 2: 硬朗, 3: S型
    var brushMinSizeLimit by mutableStateOf(1.0)
    var brushMaxSizeLimit by mutableStateOf(500.0)
    var brushTipAsset by mutableStateOf("")
    var brushPaintOpId by mutableStateOf("defaultpaintop")
    var brushAirbrush by mutableStateOf(false)
    var brushAirbrushRate by mutableStateOf(0.05)
    var brushSmudgeRate by mutableStateOf(0.5)
    var brushSmudgeLength by mutableStateOf(0.5)
    var brushSpikes by mutableStateOf(2)
    var brushJitterAngle by mutableStateOf(0.0)
    var brushJitterSize by mutableStateOf(0.0)

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
    var brushCategoryScrollIndex by mutableStateOf(0)
    var brushCategoryScrollOffset by mutableStateOf(0)
    var brushPresetScrollIndex by mutableStateOf(0)
    var brushPresetScrollOffset by mutableStateOf(0)
    var brushPropertyScrollValue by mutableStateOf(0)
    var settingsPanelOpen by mutableStateOf(false)
    var layerRevision by mutableStateOf(0)
    var smoothedStrokeX by mutableStateOf(0f)
    var smoothedStrokeY by mutableStateOf(0f)

    data class ToolBrushState(
        val presetIndex: Int = -1,
        val category: String = "全部",
        val categoryScrollIndex: Int = 0,
        val categoryScrollOffset: Int = 0,
        val presetScrollIndex: Int = 0,
        val presetScrollOffset: Int = 0,
    )

    var toolBrushStates by mutableStateOf<Map<String, ToolBrushState>>(emptyMap())
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

    fun updateReferenceAllowRotation(allow: Boolean) {
        referenceAllowRotation = allow
        if (!allow) {
            referenceRotation = 0f
        }
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
    }

    fun resetReferenceTransform() {
        referenceZoom = 1f
        referenceRotation = 0f
        referencePanX = 0f
        referencePanY = 0f
    }

    // UI & View Settings (persisted)
    var uiOpacity by mutableStateOf(1.0f) // For Top and Left panels
    var popupPanelOpacity by mutableStateOf(0.95f) // For floating panels
    var blurBackground by mutableStateOf(false) // 背景毛玻璃效果，默认关闭
    var accentColorHex by mutableStateOf("#5E8BA8")
    var monetEnabled by mutableStateOf(false) // 莫奈动态取色
    var themeMode by mutableStateOf("DARK") // "DARK", "LIGHT", "SYSTEM"
    var paintingUiScale by mutableStateOf(1.0f) // 绘画页面整体 UI 大小缩放 (0.75 - 1.35)
    var extendToCutout by mutableStateOf(true)
    var homeSelectedTab by mutableStateOf(0)

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
    var strokeStabilizer by mutableStateOf(0.15f)
    var smoothedStrokePressure by mutableStateOf(1.0)

    // Keyboard Shortcuts (参考图 2)
    var shortcutBindings by mutableStateOf<Map<String, String>>(emptyMap())

    // Stylus Settings (画世界 Pro & Krita style, persisted)
    var penOnlyMode by mutableStateOf(false) // 笔模式 (禁止手指绘制，单指平移，双指缩放旋转)
    var brushCursorMode by mutableStateOf(0) // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
    var eraserCursorMode by mutableStateOf(3)
    var cursorStyleMode by mutableStateOf(0) // 0: 圆形, 1: 十字准星, 2: 点, 3: 无, 4: 系统指针, 5: 圆+十字准星
    var quickShapeEnabled by mutableStateOf(true) // 驻停线条成形
    var pressureCurvePreset by mutableStateOf(0) // 0: 线性, 1: 轻压灵敏, 2: 重压偏硬, 3: S型, 4: 自定义
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
    var fillTolerance by mutableIntStateOf(24)
    var fillSampleLayers by mutableIntStateOf(0) // 0: 当前图层, 1: 全部图层
    var fillOpacity by mutableStateOf(1.0)
    var fillCompositeOp by mutableStateOf("normal")

    var gradientType by mutableIntStateOf(0) // 0: 线性, 1: 径向, 2: 角度
    var gradientRepeat by mutableIntStateOf(0) // 0: 无, 1: 重复, 2: 往返
    var gradientReverse by mutableStateOf(false)

    var shapeStrokeWidth by mutableStateOf(4.0)
    var shapeFillMode by mutableIntStateOf(0) // 0: 仅描边, 1: 仅填充, 2: 描边与填充
    var shapeKeepAspect by mutableStateOf(false)

    var selectionMode by mutableIntStateOf(0) // 0: 替换, 1: 添加, 2: 减去, 3: 相交
    var selectionTolerance by mutableIntStateOf(24)
    var selectionSampleLayers by mutableIntStateOf(1) // 0: 当前图层, 1: 全部图层
    var selectionFeatherRadius by mutableIntStateOf(0)

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

    var gestureTwoFingerUndo by mutableStateOf(true)
    var gestureThreeFingerRedo by mutableStateOf(true)
    var gesturePinchTransform by mutableStateOf(true)

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
        quickShapeEnabled = enable
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("quickShapeEnabled", enable)
                .apply()
        }
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
        if (monetEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            com.reverie.paint.ui.theme.Theme.current =
                com.reverie.paint.ui.theme.getMonetColors(appContext, isDark = dark, fallbackAccent = parsedAccent)
        } else {
            com.reverie.paint.ui.theme.Theme.current =
                com.reverie.paint.ui.theme.buildThemeColors(isDark = dark, accent = parsedAccent)
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
            blurBackground = prefs.getBoolean("blurBackground", false)
            accentColorHex = prefs.getString("accentColor", "#5E8BA8") ?: "#5E8BA8"
            monetEnabled = prefs.getBoolean("monetEnabled", false)
            themeMode = prefs.getString("themeMode", "DARK") ?: "DARK"
            immersiveMode = prefs.getBoolean("immersiveMode", false)
            extendToCutout = prefs.getBoolean("extendToCutout", true)
            penOnlyMode = prefs.getBoolean("penOnlyMode", false)
            gestureTwoFingerUndo = prefs.getBoolean("gestureTwoFingerUndo", true)
            gestureThreeFingerRedo = prefs.getBoolean("gestureThreeFingerRedo", true)
            gesturePinchTransform = prefs.getBoolean("gesturePinchTransform", true)
            longPressEyedropperEnabled = prefs.getBoolean("longPressEyedropperEnabled", true)
            eyedropperSensitivity = prefs.getInt("eyedropperSensitivity", 3).coerceIn(1, 5)
            eyedropperOffsetEnabled = prefs.getBoolean("eyedropperOffsetEnabled", true)
            brushCursorMode = prefs.getInt("brushCursorMode", 0)
            eraserCursorMode = prefs.getInt("eraserCursorMode", 3)
            cursorStyleMode = prefs.getInt("cursorStyleMode", 0)
            quickShapeEnabled = prefs.getBoolean("quickShapeEnabled", true)
            val savedPreset = prefs.getInt("pressureCurvePreset", 0)
            updatePressureCurvePreset(savedPreset)

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
            recentColors = loadRecentColors()
            userPalettes = loadUserPalettes()

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
        val scaled = if (bmp.width > 100 || bmp.height > 100) {
            android.graphics.Bitmap.createScaledBitmap(bmp, 80, 80, true)
        } else bmp
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val colorCounts = mutableMapOf<Int, Int>()
        for (p in pixels) {
            val a = (p shr 24) and 0xFF
            if (a < 64) continue
            val qr = ((p shr 16) and 0xFF) / 16 * 16
            val qg = ((p shr 8) and 0xFF) / 16 * 16
            val qb = (p and 0xFF) / 16 * 16
            val key = (qr shl 16) or (qg shl 8) or qb
            colorCounts[key] = (colorCounts[key] ?: 0) + 1
        }
        val sorted = colorCounts.entries.sortedByDescending { it.value }.map {
            String.format("#%06X", it.key and 0xFFFFFF)
        }.take(16)

        createNewPalette(name.ifBlank { "图片导入色卡" }, sorted)
    }

    fun updateColorModel(model: String) {
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
        recorder.endSession()
        replaySession?.stop()
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        super.onCleared()
    }

    internal fun startRenderThread() {
        val thread = HandlerThread("reverie-render")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)
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
        pendingCoreOps++
        h.post {
            if (pendingCoreOps > 0) pendingCoreOps--
            op()
            if (render) scheduleRender()
            if (after != null) mainHandler.post { after() }
        }
    }

    @Volatile internal var pendingCoreOps = 0

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
        // Throttle to ~60fps: during fast strokes several touchMove events
        // arrive per frame; rendering each one separately (waitForDone +
        // projection recomposite + bitmap copy) saturates the render thread
        // and made large brushes lag badly.
        if (renderScheduled) return
        renderScheduled = true
        val r =
            Runnable {
                pendingRenderRunnable = null
                renderScheduled = false
                doRender()
            }
        pendingRenderRunnable = r
        h.postDelayed(r, 16)
    }

    internal fun doRender() {
        renderScheduled = false
        val rh = renderHandler
        // Input-first: if stroke ops are queued ahead on this thread, let the
        // stroke extend first and render after (each render waits for the
        // projection recomposite - blocking it while input waits behind was
        // felt as lag/stutter during fast scribbling). Bounded to two 4ms
        // defers so rendering can never starve.
        if (rh != null && pendingCoreOps > 0 && renderDeferCount < 2) {
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
        val dirty = IntArray(4)
        val ok = ReverieCoreBridge.renderToBuffer(buf, forceFull, dirty)
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
            if (dirty[2] > 0 && dirty[3] > 0) {
                val written =
                    android.graphics.Rect(dirty[0], dirty[1], dirty[0] + dirty[2], dirty[1] + dirty[3])
                // Keep every OTHER buffer in sync: non-displayed ones get the
                // region blitted straight from the just-rendered buffer (it is
                // fully up to date), displayed/pending ones just accumulate
                // their missing union for when they are recycled
                for (j in displayBuffers.indices) {
                    if (j == idx) continue
                    val other = displayBuffers[j] ?: continue
                    if (other === displayed || other === pendingDisplay) continue
                    val miss = bufferMissing[j]
                    if (miss == null) {
                        // Up to date except for this render's region
                        android.graphics.Canvas(other).drawBitmap(buf, written, written, null)
                    } else {
                        miss.union(written)
                        android.graphics.Canvas(other).drawBitmap(buf, miss, miss, null)
                        bufferMissing[j] = null
                    }
                }
                for (j in displayBuffers.indices) {
                    val other = displayBuffers[j] ?: continue
                    if (other === displayed || other === pendingDisplay) {
                        bufferMissing[j]?.union(written) ?: run {
                            bufferMissing[j] = android.graphics.Rect(written)
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

    var currentLayerIndex by mutableStateOf(-1)
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
}

enum class Page { HOME, CREATE, PAINTING, REPLAY }

/** Krita-style brush grouping: the preset name prefix maps to a group. */
fun inferBrushGroup(name: String): String =
    when {
        name.startsWith("a)") -> "橡皮擦"
        name.startsWith("b)") || name.startsWith("Airbrush") || name.startsWith("Basic") -> "基础"
        name.startsWith("c)") || name.startsWith("Pencil") -> "铅笔"
        name.startsWith("d)") || name.startsWith("Ink") -> "勾线"
        name.startsWith("e)") || name.startsWith("Marker") -> "马克笔"
        name.startsWith("f)") || name.contains("Bristle") || name.contains("Charcoal") -> "鬃毛"
        name.startsWith("g)") || name.startsWith("Dry") -> "干笔"
        name.startsWith("h)") || name.startsWith("Chalk") -> "粉笔"
        name.startsWith("i)") || name.startsWith("Wet") -> "湿笔"
        name.startsWith("j)") || name.startsWith("Water") -> "水彩"
        name.startsWith("k)") || name.contains("Blender") || name.contains("Smudge") -> "混合"
        name.startsWith("l)") || name.startsWith("Adjust") -> "调整"
        name.startsWith("t)") || name.startsWith("Shape") -> "形状"
        name.startsWith("u)") || name.contains("Pixel") -> "像素画"
        name.startsWith("v)") -> "特效"
        name.startsWith("w)") -> "纹理"
        name.startsWith("x)") || name.startsWith("Filter") -> "滤镜"
        name.startsWith("y)") -> "纹理"
        name.startsWith("z)") || name.startsWith("Stamp") -> "印章"
        name.contains("Spray") -> "喷枪"
        name.contains("Clone") || name.contains("Distort") -> "特效"
        else -> "其他"
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
    "全部", "橡皮擦", "基础", "铅笔", "勾线", "马克笔", "鬃毛",
    "干笔", "粉笔", "湿笔", "水彩", "混合", "调整", "形状",
    "像素画", "特效", "纹理", "滤镜", "印章", "喷枪", "其他", "导入"
)

fun isBuiltInBrushGroup(group: String, presets: List<BrushPresetInfo> = emptyList()): Boolean {
    if (BUILT_IN_BRUSH_GROUPS.contains(group)) return true
    if (presets.any { it.isBuiltIn && it.group == group }) return true
    return false
}

fun PaintViewModel.isBuiltInGroup(group: String): Boolean = isBuiltInBrushGroup(group, brushPresets)
