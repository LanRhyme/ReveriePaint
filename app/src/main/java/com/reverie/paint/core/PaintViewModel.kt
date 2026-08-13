package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

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
        private set

    var docWidth by mutableStateOf(1080)
    var docHeight by mutableStateOf(1920)
    var docName by mutableStateOf("Untitled")

    // Brush state
    var brushSize by mutableStateOf(20.0)
    var brushColor by mutableStateOf("#262a30")
    var brushSecondaryColor by mutableStateOf("#ffffff")
    var brushOpacity by mutableStateOf(1.0)
    var brushPresets by mutableStateOf<List<BrushPresetInfo>>(emptyList())
    var brushPresetIndex by mutableStateOf(-1)
    var brushFlow by mutableStateOf(1.0)
    var brushSpacing by mutableStateOf(0.1)
    var brushAngle by mutableStateOf(0.0)
    var brushScatter by mutableStateOf(0.0)
    var brushFade by mutableStateOf(0.0)

    // Display bitmap (updated in place via renderToBuffer).
    // neverEqualPolicy: the same Bitmap object is mutated and re-assigned,
    // so referential equality would never notify Compose to repaint.
    var displayBitmap by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set

    // Layer panel. A revision state forces Compose to re-read the native
    // layer getters after add/remove/select/visibility operations.
    var layerPanelOpen by mutableStateOf(false)
    var brushPanelOpen by mutableStateOf(false)
    var settingsPanelOpen by mutableStateOf(false)
    var layerRevision by mutableStateOf(0)
        private set
        
    // UI Settings
    var uiOpacity by mutableStateOf(1.0f) // For Top and Left panels
    var popupPanelOpacity by mutableStateOf(0.95f) // For floating panels
    
    var colorPickerMode by mutableStateOf("SQUARE")
        private set
        
    fun updateColorPickerMode(mode: String) {
        colorPickerMode = mode
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("colorPickerMode", mode).apply()
        }
    }

    // Recent projects (name -> {w, h})
    data class Project(
        val name: String,
        val w: Int,
        val h: Int,
    )

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

    var projects by mutableStateOf(listOf<Project>())
        private set

    // ---- async render plumbing ----
    // Document size as known by the C++ core (written on the render thread,
    // read there too; Compose-facing docWidth/docHeight are mirrored via
    // the main handler after document creation).
    private var coreW = 1080
    private var coreH = 1920

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Rendering buffers. The engine's renderToBuffer does incremental
    // updates assuming the SAME destination bitmap every frame (full copy on
    // first use, then only the changed rows) - so a single render buffer is
    // used on the render thread, and the pixels are copied to an alternating
    // pair of publish bitmaps on the main thread for Compose to display.
    // (Alternating destination buffers would leave stale garbage in the
    // unchanged regions of every other frame.)
    private var renderBmp: Bitmap? = null
    private var publishA: Bitmap? = null
    private var publishB: Bitmap? = null
    private var publishFlip = false
    private val renderLock = Any()
    private var renderScheduled = false

    init {
        startRenderThread()
    }

    override fun onCleared() {
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        super.onCleared()
    }

    private fun startRenderThread() {
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
    private fun runCore(
        after: (() -> Unit)? = null,
        op: () -> Unit,
    ) {
        val h = renderHandler ?: return
        h.post {
            op()
            scheduleRender()
            if (after != null) mainHandler.post { after() }
        }
    }

    private fun scheduleRender(immediate: Boolean = false) {
        val h = renderHandler ?: return
        if (renderScheduled && !immediate) return
        renderScheduled = true
        val run = { doRender() }
        if (immediate) h.removeCallbacksAndMessages(null) // drop pending delayed renders
        h.post(run)
    }

    private fun doRender() {
        renderScheduled = false
        val w = coreW
        val h = coreH
        if (w <= 0 || h <= 0) return
        val rb = renderBmp
        if (rb == null || rb.width != w || rb.height != h) {
            renderBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val src = renderBmp ?: return
        synchronized(renderLock) {
            ReverieCoreBridge.renderToBuffer(src)
        }
        mainHandler.post {
            // Publish buffers must also resize when the document size
            // changes (canvas preset switch / project load): a stale-sized
            // buffer made displayBitmap mismatch the document and the canvas
            // visibly changed size.
            val pub: Bitmap =
                if (publishFlip) {
                    if (publishA == null || publishA!!.width != w || publishA!!.height != h) {
                        publishA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    }
                    publishA!!
                } else {
                    if (publishB == null || publishB!!.width != w || publishB!!.height != h) {
                        publishB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    }
                    publishB!!
                }
            publishFlip = !publishFlip
            synchronized(renderLock) {
                // copyPixelsFrom is API 26+; Canvas works on all APIs without
                // allocating a temp pixel array
                android.graphics.Canvas(pub).drawBitmap(src, 0f, 0f, null)
            }
            displayBitmap = pub
        }
    }

    var layers by mutableStateOf(listOf<LayerUiState>())
        private set

    var currentLayerIndex by mutableStateOf(-1)
        private set

    val layerCount: Int get() = layers.size

    /** Mirror all native layer state into [layers] / [currentLayerIndex].
     * Must run on the main thread after any C++ layer mutation. */
    private fun syncLayersFromNative() {
        val n = ReverieCoreBridge.layerCount()
        val list = ArrayList<LayerUiState>(n)
        for (i in 0 until n) {
            list.add(
                LayerUiState(
                    index = i,
                    name = ReverieCoreBridge.layerName(i),
                    visible = ReverieCoreBridge.layerVisible(i),
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

    fun saveProject(name: String) {
        runCore(
            after = {
                docName = name
                refreshProjects()
            },
        ) {
            val dir = java.io.File(appContext.filesDir, "projects")
            dir.mkdirs()
            val file = java.io.File(dir, "$name.png")
            ReverieCoreBridge.savePng(file.absolutePath)
        }
    }

    fun loadProject(name: String) {
        runCore(
            after = {
                docWidth = coreW
                docHeight = coreH
                docName = name
                currentPage = Page.PAINTING
            },
        ) {
            val file = java.io.File(projectDir(), "$name.png")
            if (file.exists() && ReverieCoreBridge.loadPng(file.absolutePath)) {
                coreW = ReverieCoreBridge.docWidth()
                coreH = ReverieCoreBridge.docHeight()
                syncLayersFromNative()
            }
        }
    }

    fun refreshProjects() {
        val dir = projectDir()
        if (!dir.exists()) {
            projects = emptyList()
            return
        }
        projects =
            dir
                .listFiles { f -> f.extension == "png" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { Project(it.nameWithoutExtension, 0, 0) }
                ?: emptyList()
    }

    fun projectDir(): java.io.File = java.io.File(appContext.filesDir, "projects")

    /** Injected by MainActivity; the engine needs it for file paths. */
    lateinit var appContext: android.content.Context

    fun goHome() {
        currentPage = Page.HOME
    }

    fun goCreate() {
        currentPage = Page.CREATE
    }

    fun startPainting(
        w: Int,
        h: Int,
        name: String = "Untitled",
    ) {
        runCore(
            after = {
                docWidth = w
                docHeight = h
                docName = name
                currentPage = Page.PAINTING
            },
        ) {
            if (ReverieCoreBridge.newDocument(w, h)) {
                coreW = w
                coreH = h
                syncLayersFromNative()
            }
        }
    }

    fun openProject(p: Project) {
        runCore(
            after = {
                docWidth = p.w
                docHeight = p.h
                docName = p.name
                currentPage = Page.PAINTING
            },
        ) {
            if (ReverieCoreBridge.newDocument(p.w, p.h)) {
                coreW = p.w
                coreH = p.h
                syncLayersFromNative()
            }
        }
    }

    fun refreshDisplay() {
        scheduleRender(immediate = true)
    }

    fun loadBrushPresets() {
        // Copy the bundled presets from assets to filesDir once
        val dir = java.io.File(appContext.filesDir, "paintoppresets")
        val assets = appContext.assets
        try {
            if (!dir.exists()) dir.mkdirs()
            for (name in assets.list("paintoppresets") ?: emptyArray()) {
                val target = java.io.File(dir, name)
                if (!target.exists()) {
                    assets.open("paintoppresets/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "preset copy failed", e)
        }
        android.util.Log.d("ReveriePaint", "loadBrushPresets files=" + (dir.list()?.size ?: -1))
        // Copy the bundled brush resource files (.gbr/.gih/.png/.svg) from
        // assets to filesDir once, so presets can resolve their
        // brush_definition files via the shared KisLocalStrokeResources.
        val brushDir = java.io.File(appContext.filesDir, "brushes")
        try {
            if (!brushDir.exists()) brushDir.mkdirs()
            for (name in assets.list("brushes") ?: emptyArray()) {
                val target = java.io.File(brushDir, name)
                if (!target.exists()) {
                    assets.open("brushes/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "brush copy failed", e)
        }
        // Build the list on the render thread (JNI reads), but assign the
        // Compose state on the MAIN thread: mutableStateOf written from the
        // render HandlerThread is not reliably visible to composition.
        val list = ArrayList<BrushPresetInfo>()
        runCore(after = {
            android.util.Log.d("ReveriePaint", "loadBrushPresets assign=${list.size}")
            brushPresets = list.toList()
        }) {
            android.util.Log.d("ReveriePaint", "loadBrushPresets runCore start")
            val nrb = ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
            android.util.Log.d("ReveriePaint", "loadBrushResources count=$nrb")
            val n = ReverieCoreBridge.loadBrushPresetsFromDir(dir.absolutePath)
            android.util.Log.d("ReveriePaint", "loadBrushPresets count=$n")
            list.clear()
            for (i in 0 until n) {
                list.add(
                    BrushPresetInfo(
                        index = i,
                        name = ReverieCoreBridge.brushPresetName(i),
                        thumbBytes = ReverieCoreBridge.brushPresetThumbData(i),
                    )
                )
            }
            android.util.Log.d("ReveriePaint", "loadBrushPresets list=${list.size}")
        }
    }

    fun updateBrushFlow(v: Double) {
        brushFlow = v
        ReverieCoreBridge.setBrushFlow(v)
    }

    fun updateBrushSpacing(v: Double) {
        brushSpacing = v
        ReverieCoreBridge.setBrushSpacing(v)
    }

    fun updateBrushAngle(v: Double) {
        brushAngle = v
        ReverieCoreBridge.setBrushAngle(v)
    }

    fun updateBrushScatter(v: Double) {
        brushScatter = v
        ReverieCoreBridge.setBrushScatter(v)
    }

    fun updateBrushFade(v: Double) {
        brushFade = v
        ReverieCoreBridge.setBrushFade(v)
    }

    fun selectBrushPreset(index: Int) {
        if (index == brushPresetIndex) return
        runCore {
            if (ReverieCoreBridge.loadBrushPreset(index)) {
                brushPresetIndex = index
            }
        }
    }

    fun updateBrushSize(v: Double) {
        brushSize = v
        ReverieCoreBridge.setBrushSize(v)
    }

    fun updateBrushColor(c: String) {
        brushColor = c
        ReverieCoreBridge.setBrushColor(c)
    }

    fun swapColors() {
        val temp = brushColor
        updateBrushColor(brushSecondaryColor)
        brushSecondaryColor = temp
    }

    fun updateBrushOpacity(v: Double) {
        brushOpacity = v
        ReverieCoreBridge.setBrushOpacity(v)
    }

    fun touchStart(
        x: Float,
        y: Float,
        pressure: Double = 1.0,
    ) {
        runCore { ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), pressure) }
    }

    fun touchMove(
        x: Float,
        y: Float,
        pressure: Double = 1.0,
    ) {
        runCore { ReverieCoreBridge.touchStrokeMove(x.toDouble(), y.toDouble(), pressure) }
    }

    fun touchEnd() {
        runCore(after = {
            scheduleRender(immediate = true)
            refreshLayerThumbs()
        }) {
            ReverieCoreBridge.touchStrokeEnd()
        }
    }

    fun touchCancel() {
        runCore(after = { refreshLayerThumbs() }) {
            ReverieCoreBridge.touchStrokeCancel()
        }
    }

    fun applyTool(toolId: String) {
        when (toolId) {
            "brush" -> ReverieCoreBridge.setToolMode(0)

            "eraser" -> ReverieCoreBridge.setToolMode(1)

            "fill" -> ReverieCoreBridge.setToolMode(2)

            "smudge" -> ReverieCoreBridge.setToolMode(3)

            // ToolSmudge
            else -> ReverieCoreBridge.setToolMode(0)
        }
    }

    fun undo() {
        runCore(after = { refreshLayerThumbs(force = true) }) {
            if (ReverieCoreBridge.canUndo()) {
                ReverieCoreBridge.undo()
            }
        }
    }

    fun redo() {
        runCore(after = { refreshLayerThumbs(force = true) }) {
            if (ReverieCoreBridge.canRedo()) {
                ReverieCoreBridge.redo()
            }
        }
    }

    fun liquify(
        fx: Float,
        fy: Float,
        tx: Float,
        ty: Float,
    ) {
        runCore {
            ReverieCoreBridge.liquify(fx.toInt(), fy.toInt(), tx.toInt(), ty.toInt())
        }
    }

    fun lassoFill(points: List<Pair<Int, Int>>) {
        val xs = points.map { it.first }.toIntArray()
        val ys = points.map { it.second }.toIntArray()
        runCore { ReverieCoreBridge.lassoFill(xs, ys, points.size) }
    }

    fun lassoClear(points: List<Pair<Int, Int>>) {
        val xs = points.map { it.first }.toIntArray()
        val ys = points.map { it.second }.toIntArray()
        runCore { ReverieCoreBridge.lassoClear(xs, ys, points.size) }
    }

    fun drawText(
        x: Float,
        y: Float,
        text: String,
        fontSize: Double,
    ) {
        runCore {
            ReverieCoreBridge.drawText(x.toInt(), y.toInt(), text, fontSize)
        }
    }

    fun drawShape(
        kind: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        runCore {
            ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        }
    }

    fun floodFill(
        x: Float,
        y: Float,
    ) {
        runCore { ReverieCoreBridge.floodFillAt(x.toInt(), y.toInt()) }
    }

    private val layerThumbStates = mutableStateMapOf<Int, Bitmap>()
    // Name-keyed mirror: layer indices change on every move/group op, so the
    // index cache goes empty right after a drag and the rows flash blank until
    // the 400ms-throttled refresh lands. Names are stable across moves, so a
    // by-name lookup keeps thumbnails visible (this is the drag flicker fix)
    private val layerThumbByName = mutableStateMapOf<String, Bitmap>()

    /** Layer thumbnails keyed by layer index (updated on the render thread). */
    val layerThumbs: Map<Int, Bitmap> = layerThumbStates

    /** Thumbnail lookup that survives index shifts (fallback by layer name). */
    fun thumbFor(layerIndex: Int, layerName: String): Bitmap? =
        layerThumbStates[layerIndex] ?: layerThumbByName[layerName]

    private var lastThumbRefreshNs = 0L

    /** (Re)generate layer thumbnails on the render thread. Throttled. */
    fun refreshLayerThumbs(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastThumbRefreshNs < 400_000_000L) return
        lastThumbRefreshNs = now
        val n = ReverieCoreBridge.layerCount()
        if (n <= 0) return
        runCore(after = {}) {
            for (i in 0 until n) {
                val bmp = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
                if (ReverieCoreBridge.renderLayerThumb(i, bmp)) {
                    val idx = i
                    val name = ReverieCoreBridge.layerName(idx)
                    mainHandler.post {
                        layerThumbStates[idx] = bmp
                        layerThumbByName[name] = bmp
                    }
                }
            }
        }
    }

    private fun notifyLayerChanged() {
        syncLayersFromNative()
        layerRevision++
        refreshLayerThumbs()
        scheduleRender(immediate = true)
    }

    fun addLayer() {
        runCore(after = ::notifyLayerChanged) {
            // empty name -> C++ generates 颜料图层 N
            ReverieCoreBridge.addLayer("")
        }
    }

    fun removeLayer() {
        removeLayer(currentLayerIndex)
    }

    fun removeLayer(index: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.removeLayer(index)
        }
    }

    fun setCurrentLayer(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setCurrentLayer(i)
        }
    }

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

    fun layerBlendMode(i: Int) = ReverieCoreBridge.layerBlendMode(i)

    fun setLayerBlendMode(
        i: Int,
        opId: String,
    ) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerBlendMode(i, opId)
        }
    }

    fun toggleLayerVisible(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerVisible(i, !ReverieCoreBridge.layerVisible(i))
        }
    }

    fun layerName(i: Int) = ReverieCoreBridge.layerName(i)

    /** Sample the color at document-space (x, y) and set it as the brush color. */
    fun pickColor(
        x: Float,
        y: Float,
    ) {
        val c = ReverieCoreBridge.pickColorAt(x.toInt(), y.toInt()) ?: return
        updateBrushColor(c)
    }

    fun layerVisible(i: Int) = ReverieCoreBridge.layerVisible(i)

    // ---- Full layer system ----
    fun addGroupLayer() {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.addGroupLayer("")
        }
    }

    fun copyLayer(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.copyLayer(i)
        }
    }

    fun clearLayer(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.clearLayer(i)
        }
    }

    fun renameLayer(i: Int, name: String) {
        if (name.isBlank()) return
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerName(i, name.trim())
        }
    }

    fun layerOpacity(i: Int) = ReverieCoreBridge.layerOpacity(i)

    fun setLayerOpacity(i: Int, v: Double) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerOpacity(i, v)
        }
    }

    fun layerLocked(i: Int) = ReverieCoreBridge.layerLocked(i)

    fun setLayerLocked(i: Int, locked: Boolean) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerLocked(i, locked)
        }
    }

    fun layerAlphaLocked(i: Int) = ReverieCoreBridge.layerAlphaLocked(i)

    fun setLayerAlphaLocked(i: Int, locked: Boolean) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerAlphaLocked(i, locked)
        }
    }

    fun layerColorLabel(i: Int) = ReverieCoreBridge.layerColorLabel(i)

    fun setLayerColorLabel(i: Int, label: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerColorLabel(i, label)
        }
    }

    fun layerIsGroup(i: Int) = ReverieCoreBridge.layerIsGroup(i)

    fun layerDepth(i: Int) = ReverieCoreBridge.layerDepth(i)

    fun layerBackground(i: Int) = ReverieCoreBridge.layerBackground(i)

    fun layerClipped(i: Int) = ReverieCoreBridge.layerClipped(i)

    fun setLayerClipped(i: Int, clipped: Boolean) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerClipped(i, clipped)
        }
    }

    fun flipLayerHorizontal(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.flipLayerHorizontal(i)
        }
    }

    fun flipLayerVertical(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.flipLayerVertical(i)
        }
    }

    fun moveLayer(from: Int, to: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.moveLayer(from, to)
        }
    }

    fun moveLayerAbove(from: Int, above: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.moveLayerAbove(from, above)
        }
    }

    fun moveLayerToGroup(from: Int, group: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.moveLayerToGroup(from, group)
        }
    }

    fun mergeDown(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.mergeDown(i)
        }
    }

    fun soloLayer(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.soloLayer(i)
        }
    }

    fun layerSoloed(i: Int) = ReverieCoreBridge.layerSoloed(i)

    fun applyFilter(i: Int, filterId: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.applyFilter(i, filterId)
        }
    }

    fun selectionFromLayer(i: Int) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.selectionFromLayer(i)
        }
    }

    fun clearSelection() {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.clearSelection()
        }
    }
}

enum class Page { HOME, CREATE, PAINTING }


/** A bundled Krita brush preset (.kpp) with its PNG thumbnail. */
data class BrushPresetInfo(
    val index: Int,
    val name: String,
    val thumbBytes: ByteArray,
)
