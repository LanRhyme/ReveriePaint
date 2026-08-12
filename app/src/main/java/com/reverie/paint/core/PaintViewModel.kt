package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
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
    var brushOpacity by mutableStateOf(1.0)

    // Display bitmap (updated in place via renderToBuffer).
    // neverEqualPolicy: the same Bitmap object is mutated and re-assigned,
    // so referential equality would never notify Compose to repaint.
    var displayBitmap by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set

    // Layer panel. A revision state forces Compose to re-read the native
    // layer getters after add/remove/select/visibility operations.
    var layerPanelOpen by mutableStateOf(false)
    var layerRevision by mutableStateOf(0)
        private set

    // Recent projects (name -> {w, h})
    data class Project(
        val name: String,
        val w: Int,
        val h: Int,
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

    // double buffer: the render thread writes the back buffer, then publishes
    // it to Compose; the other buffer is reused for the next render.
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var usingA = true
    private var renderScheduled = false

    init {
        startRenderThread()
    }

    override fun onCleared() {
        renderThread?.quitSafely()
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
        val bmp = nextBackBuffer(w, h) ?: return
        ReverieCoreBridge.renderToBuffer(bmp)
        usingA = !usingA
        mainHandler.post { displayBitmap = bmp }
    }

    private fun nextBackBuffer(
        w: Int,
        h: Int,
    ): Bitmap? {
        val cur = if (usingA) bufferB else bufferA
        if (cur != null && cur.width == w && cur.height == h) return cur
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (usingA) bufferB = nb else bufferA = nb
        return nb
    }

    val layerCount: Int get() = ReverieCoreBridge.layerCount()
    val currentLayerIndex: Int get() = ReverieCoreBridge.currentLayerIndex()

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
            }
        }
    }

    fun refreshDisplay() {
        scheduleRender(immediate = true)
    }

    fun updateBrushSize(v: Double) {
        brushSize = v
        ReverieCoreBridge.setBrushSize(v)
    }

    fun updateBrushColor(c: String) {
        brushColor = c
        ReverieCoreBridge.setBrushColor(c)
    }

    fun updateBrushOpacity(v: Double) {
        brushOpacity = v
        ReverieCoreBridge.setBrushOpacity(v)
    }

    fun touchStart(
        x: Float,
        y: Float,
    ) {
        runCore { ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), 1.0) }
    }

    fun touchMove(
        x: Float,
        y: Float,
    ) {
        runCore { ReverieCoreBridge.touchStrokeMove(x.toDouble(), y.toDouble(), 1.0) }
    }

    fun touchEnd() {
        runCore(after = { scheduleRender(immediate = true) }) {
            ReverieCoreBridge.touchStrokeEnd()
        }
    }

    fun touchCancel() {
        runCore { ReverieCoreBridge.touchStrokeCancel() }
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
        runCore {
            if (ReverieCoreBridge.canUndo()) {
                ReverieCoreBridge.undo()
            }
        }
    }

    fun redo() {
        runCore {
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

    private fun notifyLayerChanged() {
        layerRevision++
        scheduleRender(immediate = true)
    }

    fun addLayer() {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.addLayer("Layer ${layerCount + 1}")
        }
    }

    fun removeLayer() {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.removeLayer(currentLayerIndex)
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
            "difference" to "差值",
            "add" to "线性减淡",
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
}

enum class Page { HOME, CREATE, PAINTING }
