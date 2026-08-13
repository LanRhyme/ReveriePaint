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
    private val brushParams: MutableMap<String, BrushParams> = mutableMapOf()

    // Display bitmap (updated in place via renderToBuffer).
    // neverEqualPolicy: the same Bitmap object is mutated and re-assigned,
    // so referential equality would never notify Compose to repaint.
    var displayBitmap by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set

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
        val presetScrollOffset: Int = 0
    )
    var toolBrushStates by mutableStateOf<Map<String, ToolBrushState>>(emptyMap())
        private set
        
    var pinnedTools by mutableStateOf<List<com.reverie.paint.model.Tool>>(emptyList())
        private set
        
    var currentToolId by mutableStateOf("brush")
        private set
        
    // UI Settings
    var uiOpacity by mutableStateOf(1.0f) // For Top and Left panels
    var popupPanelOpacity by mutableStateOf(0.95f) // For floating panels
    
    var colorPickerMode by mutableStateOf("SQUARE")
        private set

    /** Immersive mode (fullscreen + hidden system bars), persisted in prefs.
     *  The actual window changes are applied by MainActivity.applyImmersive. */
    var immersiveMode by mutableStateOf(false)
        private set

    fun updateImmersiveMode(enable: Boolean) {
        immersiveMode = enable
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("immersiveMode", enable).apply()
        }
        com.reverie.paint.MainActivity.applyImmersive(enable)
    }

    fun syncImmersiveFromPrefs() {
        if (::appContext.isInitialized) {
            immersiveMode = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("immersiveMode", false)
        }
    }
        
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

    // Viewport render size: the Android bitmap is rendered at the on-screen
    // size (document aspect ratio preserved) instead of the full document
    // resolution. Full-document rendering made large brushes lag because
    // every frame composited a huge region (35-60ms+ on device).
    private var renderW = 540
    private var renderH = 960

    /** Report the visible canvas size (device px); recompute the viewport
     * render size at the document aspect ratio. */
    fun setRenderViewport(viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val scale = minOf(viewW.toFloat() / coreW, viewH.toFloat() / coreH)
        val nw = maxOf(1, (coreW * scale).toInt())
        val nh = maxOf(1, (coreH * scale).toInt())
        if (nw != renderW || nh != renderH) {
            renderW = nw
            renderH = nh
            scheduleRender(immediate = true)
        }
    }

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

    private val RENDER_TOKEN = Any()

    private fun scheduleRender(immediate: Boolean = false) {
        val h = renderHandler ?: return
        if (immediate) {
            // touchEnd / undo / structural changes: render right away and
            // drop any pending throttled render
            h.removeCallbacksAndMessages(RENDER_TOKEN)
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
        h.postDelayed(
            {
                renderScheduled = false
                doRender()
            },
            16,
        )
    }

    private fun doRender() {
        renderScheduled = false
        val w = renderW
        val h = renderH
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
        loadBrushParams()
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
        // Restore persisted user brush groups and custom order
        loadBrushGroups()
        val orderJson = prefs().getString("brush_order", null)
        brushOrder = if (orderJson != null) {
            runCatching {
                val arr = org.json.JSONArray(orderJson)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
        } else emptyList()
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
                val nm = ReverieCoreBridge.brushPresetName(i)
                list.add(
                    BrushPresetInfo(
                        index = i,
                        name = nm,
                        thumbBytes = ReverieCoreBridge.brushPresetThumbData(i),
                        group = userBrushGroups[nm] ?: inferBrushGroup(nm),
                    )
                )
            }
            android.util.Log.d("ReveriePaint", "loadBrushPresets list=${list.size}")
        }
    }

    // ---- User-defined brush groups ----------------------------------
    private fun prefs() =
        appContext.getSharedPreferences("brush_groups", android.content.Context.MODE_PRIVATE)

    private fun loadBrushGroups() {
        val groupsJson = prefs().getString("user_groups", null)
        val customsJson = prefs().getString("custom_groups", null)
        userBrushGroups = if (groupsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(groupsJson)
                (0 until arr.length()).associate { i ->
                    val o = arr.getJSONObject(i)
                    o.getString("n") to o.getString("g")
                }
            }.getOrDefault(emptyMap())
        } else emptyMap()
        customBrushGroups = if (customsJson != null) {
            runCatching {
                val arr = org.json.JSONArray(customsJson)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    private fun saveBrushGroups() {
        try {
            val arr = org.json.JSONArray()
            for ((n, g) in userBrushGroups) {
                arr.put(org.json.JSONObject().put("n", n).put("g", g))
            }
            val cust = org.json.JSONArray()
            for (g in customBrushGroups) cust.put(g)
            prefs().edit()
                .putString("user_groups", arr.toString())
                .putString("custom_groups", cust.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushGroups failed", e)
        }
    }

    /** Create a new user brush group. Returns false if the name exists. */
    fun createBrushGroup(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (customBrushGroups.contains(n) || n == "全部") return false
        customBrushGroups = customBrushGroups + n
        saveBrushGroups()
        return true
    }

    /** Move a preset into a group (or back to its inferred group). */
    fun moveBrushToGroup(presetName: String, group: String) {
        userBrushGroups = userBrushGroups + (presetName to group)
        saveBrushGroups()
        // Refresh the displayed group of this preset
        brushPresets = brushPresets.map {
            if (it.name == presetName) it.copy(group = group) else it
        }
    }

    private fun saveBrushOrder() {
        try {
            val arr = org.json.JSONArray()
            for (n in brushOrder) arr.put(n)
            prefs().edit().putString("brush_order", arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "saveBrushOrder failed", e)
        }
    }

    /** Move a preset up/down within its current list position. */
    fun moveBrushUp(presetName: String) {
        reorderBrush(presetName, -1)
    }

    fun moveBrushDown(presetName: String) {
        reorderBrush(presetName, 1)
    }

    private fun reorderBrush(presetName: String, delta: Int) {
        val cur = brushPresets
        val idx = cur.indexOfFirst { it.name == presetName }
        val to = idx + delta
        if (idx < 0 || to < 0 || to >= cur.size) return
        val newList = cur.toMutableList()
        val t = newList[idx]
        newList[idx] = newList[to]
        newList[to] = t
        brushPresets = newList
        brushOrder = newList.map { it.name }
        saveBrushOrder()
    }

    fun updateBrushFlow(v: Double) {
        brushFlow = v
        saveBrushParam()
        ReverieCoreBridge.setBrushFlow(v)
    }

    fun resetBrushParams() {
        val preset = brushPresets.getOrNull(brushPresetIndex) ?: return
        brushParams.remove(preset.name)
        persistBrushParams()
        
        updateBrushSpacing(0.1)
        updateBrushAngle(0.0)
        updateBrushScatter(0.0)
        updateBrushFade(0.0)
        updateBrushSoftness(0.5)
        updateBrushRatio(1.0)
        updateBrushSharpness(0.0)
        updateBrushRotation(0.0)

        runCore(after = {
            val d = ReverieCoreBridge.brushPresetDefaults(brushPresetIndex)
            if (d != null && d.size >= 3) {
                brushSize = d[0]
                brushOpacity = d[1].coerceIn(0.0, 1.0)
                brushFlow = d[2].coerceIn(0.0, 1.0)
            }
        }) {
            ReverieCoreBridge.loadBrushPreset(brushPresetIndex)
        }
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

    fun updateBrushSoftness(v: Double) {
        brushSoftness = v
        ReverieCoreBridge.setBrushSoftness(v)
    }

    fun updateBrushRatio(v: Double) {
        brushRatio = v
        ReverieCoreBridge.setBrushRatio(v)
    }

    fun updateBrushSharpness(v: Double) {
        brushSharpness = v
        ReverieCoreBridge.setBrushSharpness(v)
    }

    fun updateBrushRotation(v: Double) {
        brushRotation = v
        ReverieCoreBridge.setBrushRotation(v)
    }

    fun updateBrushCompositeOp(op: String) {
        brushCompositeOp = op
        ReverieCoreBridge.setBrushCompositeOp(op)
    }

    private fun saveBrushParam() {
        val name = brushPresets.getOrNull(brushPresetIndex)?.name ?: return
        brushParams[name] = BrushParams(brushSize, brushOpacity, brushFlow)
        persistBrushParams()
    }

    private fun persistBrushParams() {
        try {
            val json = org.json.JSONArray()
            for ((name, p) in brushParams) {
                val o = org.json.JSONObject()
                o.put("n", name)
                o.put("s", p.size)
                o.put("o", p.opacity)
                o.put("f", p.flow)
                json.put(o)
            }
            prefs().edit().putString("brush_params", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun persistToolBrushStates() {
        try {
            val json = org.json.JSONArray()
            for ((id, s) in toolBrushStates) {
                val o = org.json.JSONObject()
                o.put("id", id)
                o.put("pi", s.presetIndex)
                o.put("c", s.category)
                o.put("csi", s.categoryScrollIndex)
                o.put("cso", s.categoryScrollOffset)
                o.put("psi", s.presetScrollIndex)
                o.put("pso", s.presetScrollOffset)
                json.put(o)
            }
            prefs().edit().putString("tool_brush_states", json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun savePinnedTools(tools: List<com.reverie.paint.model.Tool>) {
        pinnedTools = tools
        try {
            val ids = tools.map { it.id }.joinToString(",")
            prefs().edit().putString("pinned_tools", ids).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadBrushParams() {
        try {
            val raw = prefs().getString("brush_params", null) ?: return
            val json = org.json.JSONArray(raw)
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                brushParams[o.getString("n")] =
                    BrushParams(o.getDouble("s"), o.getDouble("o"), o.getDouble("f"))
            }
        } catch (_: Exception) {
        }
        
        try {
            val raw = prefs().getString("tool_brush_states", null) ?: return
            val json = org.json.JSONArray(raw)
            val map = mutableMapOf<String, ToolBrushState>()
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                map[o.getString("id")] = ToolBrushState(
                    presetIndex = o.optInt("pi", -1),
                    category = o.optString("c", "全部"),
                    categoryScrollIndex = o.optInt("csi", 0),
                    categoryScrollOffset = o.optInt("cso", 0),
                    presetScrollIndex = o.optInt("psi", 0),
                    presetScrollOffset = o.optInt("pso", 0)
                )
            }
            toolBrushStates = map
        } catch (_: Exception) {
        }
        
        try {
            val raw = prefs().getString("pinned_tools", null)
            if (raw != null && raw.isNotBlank()) {
                val tools = raw.split(",").map { com.reverie.paint.model.Tool.fromId(it) }
                pinnedTools = tools
            } else {
                pinnedTools = listOf(
                    com.reverie.paint.model.Tool.BRUSH,
                    com.reverie.paint.model.Tool.ERASER
                )
            }
        } catch (_: Exception) {
            pinnedTools = listOf(
                com.reverie.paint.model.Tool.BRUSH,
                com.reverie.paint.model.Tool.ERASER
            )
        }
    }

    fun selectBrushPreset(index: Int) {
        if (index == brushPresetIndex) return
        val preset = brushPresets.getOrNull(index) ?: return
        val saved = brushParams[preset.name]
        brushPresetIndex = index
        runCore(after = {
            if (saved != null) {
                // User-adjusted values for this preset
                brushSize = saved.size
                brushOpacity = saved.opacity
                brushFlow = saved.flow
            } else {
                // First use: the preset's own defaults
                val d = ReverieCoreBridge.brushPresetDefaults(index)
                if (d != null && d.size >= 3) {
                    brushSize = d[0]
                    brushOpacity = d[1].coerceIn(0.0, 1.0)
                    brushFlow = d[2].coerceIn(0.0, 1.0)
                }
            }
        }) {
            if (saved != null) {
                ReverieCoreBridge.setBrushSize(saved.size)
                ReverieCoreBridge.setBrushOpacity(saved.opacity)
                ReverieCoreBridge.setBrushFlow(saved.flow)
            }
            if (ReverieCoreBridge.loadBrushPreset(index)) {
                brushPresetIndex = index
                updateCurrentToolBrushState { it.copy(presetIndex = index) }
            }
        }
    }

    private fun updateCurrentToolBrushState(updater: (ToolBrushState) -> ToolBrushState) {
        val t = com.reverie.paint.model.Tool.fromId(currentToolId)
        if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER || t == com.reverie.paint.model.Tool.SMUDGE) {
            val state = toolBrushStates[t.id] ?: ToolBrushState()
            toolBrushStates = toolBrushStates.toMutableMap().apply { put(t.id, updater(state)) }
            persistToolBrushStates()
        }
    }

    fun updateBrushPanelCategory(cat: String) {
        brushPanelSelectedCategory = cat
        updateCurrentToolBrushState { it.copy(category = cat) }
    }

    fun updateBrushCategoryScroll(index: Int, offset: Int) {
        brushCategoryScrollIndex = index
        brushCategoryScrollOffset = offset
        updateCurrentToolBrushState { it.copy(categoryScrollIndex = index, categoryScrollOffset = offset) }
    }

    fun updateBrushPresetScroll(index: Int, offset: Int) {
        brushPresetScrollIndex = index
        brushPresetScrollOffset = offset
        updateCurrentToolBrushState { it.copy(presetScrollIndex = index, presetScrollOffset = offset) }
    }

    fun updateBrushSize(v: Double) {
        brushSize = v
        saveBrushParam()
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
        saveBrushParam()
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
        val mode =
            when (toolId) {
                "brush" -> 0
                "eraser" -> 1
                "smudge" -> 3
                else -> -1
            }
        if (mode >= 0) {
            ReverieCoreBridge.setToolMode(mode)
        }
        currentToolId = toolId

        val t = com.reverie.paint.model.Tool.fromId(toolId)
        if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER || t == com.reverie.paint.model.Tool.SMUDGE) {
            var state = toolBrushStates[toolId]
            if (state == null) {
                val cat = when (t) {
                    com.reverie.paint.model.Tool.ERASER -> "橡皮擦"
                    com.reverie.paint.model.Tool.SMUDGE -> "混合"
                    else -> "全部"
                }
                var defaultIdx = brushPresets.indexOfFirst { it.group == cat }
                if (defaultIdx < 0) defaultIdx = 0
                state = ToolBrushState(category = cat, presetIndex = defaultIdx)
                toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
            }
            
            brushPanelSelectedCategory = state.category
            brushCategoryScrollIndex = state.categoryScrollIndex
            brushCategoryScrollOffset = state.categoryScrollOffset
            brushPresetScrollIndex = state.presetScrollIndex
            brushPresetScrollOffset = state.presetScrollOffset
            
            if (state.presetIndex >= 0) {
                if (state.presetIndex != brushPresetIndex) {
                    selectBrushPreset(state.presetIndex)
                } else {
                    // Force refresh Krita param for this specific tool even if it's the same index
                    val saved = brushParams[brushPresets.getOrNull(state.presetIndex)?.name]
                    if (saved != null) {
                        brushSize = saved.size
                        brushOpacity = saved.opacity
                        brushFlow = saved.flow
                        ReverieCoreBridge.setBrushSize(saved.size)
                        ReverieCoreBridge.setBrushOpacity(saved.opacity)
                        ReverieCoreBridge.setBrushFlow(saved.flow)
                    }
                }
            }
        }
    }

    // ---- New Krita tool actions --------------------------------------

    fun gradientFill(x1: Int, y1: Int, x2: Int, y2: Int) {
        runCore { ReverieCoreBridge.gradientFill(x1, y1, x2, y2) }
    }

    fun selectShape(kind: Int, x1: Int, y1: Int, x2: Int, y2: Int) {
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.selectShape(kind, x1, y1, x2, y2)
        }
    }

    fun selectPolygon(points: List<Pair<Int, Int>>) {
        if (points.size < 3) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.selectPolygon(xs, ys, points.size)
        }
    }

    fun drawPolygon(points: List<Pair<Int, Int>>, closed: Boolean) {
        if (points.size < 2) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        runCore { ReverieCoreBridge.drawPolygon(xs, ys, points.size, closed) }
    }

    fun moveLayerContent(dx: Int, dy: Int) {
        runCore { ReverieCoreBridge.moveLayerContent(dx, dy) }
    }

    fun cropCanvas(x: Int, y: Int, w: Int, h: Int) {
        runCore {
            ReverieCoreBridge.cropCanvas(x, y, w, h)
        }
    }

    fun undo() {
        runCore(after = ::notifyLayerChanged) {
            if (ReverieCoreBridge.canUndo()) {
                ReverieCoreBridge.undo()
            }
        }
    }

    fun redo() {
        runCore(after = ::notifyLayerChanged) {
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

    // ---- Selection state (mirrored from C++ for the canvas overlay) ----
    var selectionMask: ByteArray? by mutableStateOf(null)
    var hasSelection by mutableStateOf(false)
    // Semi-transparent blue overlay bitmap built from the mask, drawn on top
    // of the canvas so the user can see the active selection (Krita-style)
    var selectionOverlayBitmap: android.graphics.Bitmap? by mutableStateOf(null)

    // ---- Selection merge mode (replace/add/subtract/intersect) ----
    var selectionMode by mutableStateOf(0)

    fun updateSelectionMode(mode: Int) {
        selectionMode = mode
        runCore { ReverieCoreBridge.setSelectionMode(mode) }
    }

    fun featherSelection(radius: Int) {
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.featherSelection(radius)
        }
    }

    fun expandSelection(px: Int) {
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.expandSelection(px)
        }
    }

    fun contractSelection(px: Int) {
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.contractSelection(px)
        }
    }

    fun smoothSelection(radius: Int) {
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.smoothSelection(radius)
        }
    }


    fun refreshSelection() {
        // Build the overlay bitmap on the render thread; only assign the
        // Compose state on the main thread (via after) so the UI never
        // blocks on the 2M-pixel mask conversion
        var result: android.graphics.Bitmap? = null
        runCore(after = {
            selectionOverlayBitmap = result
            hasSelection = result != null
        }) {
            val mask = ReverieCoreBridge.selectionMask()
            val docW = coreW
            val docH = coreH
            result =
                if (
                    mask != null &&
                    docW > 0 &&
                    docH > 0 &&
                    mask.size == docW * docH &&
                    mask.any { it.toInt() != 0 }
                ) {
                    // Render the overlay at the viewport size so it matches
                    // the (downscaled) canvas bitmap 1:1; the full-document
                    // mask is downsampled on the render thread
                    val vw = maxOf(1, renderW)
                    val vh = maxOf(1, renderH)
                    val bmp =
                        android.graphics.Bitmap.createBitmap(
                            vw,
                            vh,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    // White alpha mask; CanvasView tints it with the theme accent
                    val px = IntArray(vw * vh)
                    val stepX = docW.toFloat() / vw
                    val stepY = docH.toFloat() / vh
                    for (y in 0 until vh) {
                        val srcY = minOf(docH - 1, (y * stepY).toInt())
                        val rowOff = srcY * docW
                        val dstOff = y * vw
                        for (x in 0 until vw) {
                            val srcX = minOf(docW - 1, (x * stepX).toInt())
                            px[dstOff + x] =
                                if (mask[rowOff + srcX].toInt() != 0) 0xFFFFFFFF.toInt() else 0
                        }
                    }
                    bmp.setPixels(px, 0, vw, 0, 0, vw, vh)
                    bmp
                } else {
                    null
                }
        }
    }

    // Clear only the displayed overlay (replace mode: finger-down clears the
    // old selection immediately; the C++ selection is committed on release)
    fun clearSelectionOverlayLocal() {
        selectionOverlayBitmap = null
        selectionMask = null
        hasSelection = false
    }

    fun clearSelectionAction() {
        runCore(after = {
            selectionMask = null
            hasSelection = false
            selectionOverlayBitmap = null
        }) {
            ReverieCoreBridge.clearSelection()
            refreshDisplay()
        }
    }

    fun selectAllAction() {
        val layerIdx = currentLayerIndex
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.selectionFromLayer(layerIdx)
            refreshDisplay()
        }
    }

    fun invertSelectionAction() {
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.invertSelection()
            refreshDisplay()
        }
    }

    fun lassoSelect(points: List<Pair<Int, Int>>) {
        if (points.size < 3) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.lassoSelect(xs, ys, points.size)
        }
    }

    // Magic-wand / similar-color tolerance (0-255, default 24 like Krita)
    var selectionTolerance by mutableStateOf(24)

    fun updateSelectionTolerance(value: Int) {
        selectionTolerance = value.coerceIn(0, 255)
    }

    fun selectContiguous(x: Int, y: Int) {
        val tol = selectionTolerance
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.selectContiguousAt(x, y, tol)
        }
    }

    fun selectSimilar(x: Int, y: Int) {
        val tol = selectionTolerance
        runCore(after = { refreshSelection() }) {
            ReverieCoreBridge.selectSimilarAt(x, y, tol)
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
    private val layerThumbIndexName = mutableStateMapOf<Int, String>()

    /** Layer thumbnails keyed by layer index (updated on the render thread). */
    val layerThumbs: Map<Int, Bitmap> = layerThumbStates

    /**
     * Thumbnail lookup with index+name double check: an index-keyed entry is
     * only trusted when the layer name currently at that index matches the
     * requested one (indexes shift after add/remove/move and stale entries
     * would show another layer's thumbnail on a blank layer). Falls back to
     * the name-keyed map, which also avoids the stale-index case.
     */
    fun thumbFor(layerIndex: Int, layerName: String): Bitmap? {
        val idxName = layerThumbIndexName[layerIndex]
        if (idxName != null && idxName == layerName) {
            return layerThumbStates[layerIndex]
        }
        return layerThumbByName[layerName]
    }

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
                        layerThumbIndexName[idx] = name
                        layerThumbByName[name] = bmp
                    }
                }
            }
        }
    }

    private fun notifyLayerChanged() {
        syncLayersFromNative()
        layerRevision++
        // Structural/attribute changes can shift layer indexes and invalidate
        // index-keyed thumbnails, so force a fresh render (the 400ms throttle
        // would otherwise skip it and show another layer's stale thumbnail)
        refreshLayerThumbs(force = true)
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

    var pickerCurrentLayerOnly by mutableStateOf(false)

    /** Sample the color at document-space (x, y) and set it as the brush color. */
    fun pickColor(
        x: Float,
        y: Float,
        currentLayerOnly: Boolean = pickerCurrentLayerOnly,
    ): String? {
        val c = ReverieCoreBridge.pickColorAt(x.toInt(), y.toInt(), currentLayerOnly) ?: return null
        updateBrushColor(c)
        return c
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


/** Krita-style brush grouping: the preset name prefix maps to a group. */
fun inferBrushGroup(name: String): String = when {
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
    val group: String = "",  // effective group (custom override or inferred)
)
