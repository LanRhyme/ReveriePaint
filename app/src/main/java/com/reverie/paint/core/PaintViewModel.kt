package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
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

    var pinnedTools by mutableStateOf<List<com.reverie.paint.model.Tool>>(emptyList())
        internal set

    var currentToolId by mutableStateOf("brush")
        internal set

    // UI Settings (persisted)
    var uiOpacity by mutableStateOf(1.0f) // For Top and Left panels
    var popupPanelOpacity by mutableStateOf(0.95f) // For floating panels
    var blurBackground by mutableStateOf(false) // 背景毛玻璃效果，默认关闭
    var accentColorHex by mutableStateOf("#5E8BA8")
    var extendToCutout by mutableStateOf(true)
    var homeSelectedTab by mutableStateOf(0)

    // Stylus Settings (画世界 Pro & Krita style, persisted)
    var penOnlyMode by mutableStateOf(false) // 笔模式 (禁止手指绘制，仅缩放旋转)
    var brushCursorMode by mutableStateOf(0) // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
    var eraserCursorMode by mutableStateOf(3)
    var cursorStyleMode by mutableStateOf(0) // 0: 圆形, 1: 十字准星, 2: 点, 3: 无
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

    fun updateAccentColor(hex: String) {
        accentColorHex = hex
        val color =
            com.reverie.paint.ui.theme
                .parseColor(hex)
        com.reverie.paint.ui.theme.Theme.current =
            com.reverie.paint.ui.theme.Theme.current.copy(
                accent = color,
                accentHi = color,
            )
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("accentColor", hex)
                .apply()
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
            blurBackground = prefs.getBoolean("blurBackground", false)
            accentColorHex = prefs.getString("accentColor", "#5E8BA8") ?: "#5E8BA8"
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

            val parsedColor =
                com.reverie.paint.ui.theme
                    .parseColor(accentColorHex)
            com.reverie.paint.ui.theme.Theme.current =
                com.reverie.paint.ui.theme.Theme.current.copy(
                    accent = parsedColor,
                    accentHi = parsedColor,
                )
        }
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

    // The single persistent display buffer native renders into directly
    // (dirty-rect incremental updates rely on this exact buffer surviving
    // across frames, so it must never be swapped for a pool). Written on
    // the render thread from doRender only; the main thread signals a
    // needed reallocation via [displayBufferInvalid].
    internal var displayBuffer: Bitmap? = null

    @Volatile internal var displayBufferInvalid = false

    @Volatile internal var renderScheduled = false

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
        h.post {
            op()
            if (render) scheduleRender()
            if (after != null) mainHandler.post { after() }
        }
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
        val w = renderW
        val h = renderH
        if (w <= 0 || h <= 0) return
        var target = displayBuffer
        val reallocated =
            target == null || target.width != w || target.height != h || displayBufferInvalid
        if (reallocated) {
            // A fresh buffer must always receive a full-frame blit: signal it
            // via forceFull so the native side resets its incremental state
            // (no stale content can leak through a reused-size buffer).
            target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            displayBuffer = target
            displayBufferInvalid = false
        }
        val buf = target ?: return
        val ok = ReverieCoreBridge.renderToBuffer(buf, reallocated)
        if (ok) {
            // Native wrote the pixels on the render thread; the UI thread only
            // flips the Compose reference and bumps the revision. The old
            // full-frame drawBitmap copy (~8MB per frame on the main thread)
            // is gone, and the buffer is never rewritten while a copy of it
            // is still in flight. A no-op render (nothing painted since the
            // last frame) returns false and skips this flip entirely.
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
    var selectionMode by mutableStateOf(0)
    var selectionTolerance by mutableStateOf(24)
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

/** Per-preset independent brush parameters (size/opacity/flow). */
data class BrushParams(
    val size: Double,
    val opacity: Double,
    val flow: Double,
)

/** A bundled Krita brush preset (.kpp) with its PNG thumbnail. */
data class BrushPresetInfo(
    val index: Int,
    val name: String,
    val thumbBytes: ByteArray,
    val group: String = "", // effective group (custom override or inferred)
)
