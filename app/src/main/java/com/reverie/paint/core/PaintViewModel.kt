package com.reverie.paint.core

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Holds the painting UI state. The actual document lives in C++ (ReverieCore);
 * this only tracks view-level state + a bitmap for display.
 */
class PaintViewModel : ViewModel() {
    var currentPage by mutableStateOf(Page.HOME)
        private set

    var docWidth by mutableStateOf(1080)
    var docHeight by mutableStateOf(1920)
    var docName by mutableStateOf("画布")

    // Brush state
    var brushSize by mutableStateOf(20.0)
    var brushColor by mutableStateOf("#262a30")
    var brushOpacity by mutableStateOf(1.0)

    // Display bitmap (updated in place via renderToBuffer).
    // neverEqualPolicy: the same Bitmap object is mutated and re-assigned,
    // so referential equality would never notify Compose to repaint.
    var displayBitmap by mutableStateOf<Bitmap?>(null, neverEqualPolicy())
        private set

    // Layer panel
    var layerPanelOpen by mutableStateOf(false)

    private var lastPaintNs = 0L

    // Recent projects (name -> {w, h})
    data class Project(
        val name: String,
        val w: Int,
        val h: Int,
    )

    var projects by mutableStateOf(listOf<Project>())
        private set

    val layerCount: Int get() = ReverieCoreBridge.layerCount()
    val currentLayerIndex: Int get() = ReverieCoreBridge.currentLayerIndex()

    fun saveProject(name: String) {
        val dir = projectDir()
        dir.mkdirs()
        val file = java.io.File(dir, "$name.png")
        if (ReverieCoreBridge.savePng(file.absolutePath)) {
            docName = name
            refreshProjects()
        }
    }

    fun loadProject(name: String) {
        val file = java.io.File(projectDir(), "$name.png")
        if (file.exists() && ReverieCoreBridge.loadPng(file.absolutePath)) {
            docName = name
            docWidth = ReverieCoreBridge.docWidth()
            docHeight = ReverieCoreBridge.docHeight()
            currentPage = Page.PAINTING
            refreshDisplay()
        }
    }

    fun refreshProjects() {
        val dir = projectDir()
        if (!dir.exists()) {
            projects = emptyList()
            return
        }
        projects = dir.listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Project(it.nameWithoutExtension, 0, 0) }
            ?: emptyList()
    }

    fun projectDir(): java.io.File =
        java.io.File(appContext.filesDir, "projects")

    /** Injected by MainActivity; the engine needs it for file paths. */
    lateinit var appContext: android.content.Context

    fun goHome() {
        currentPage = Page.HOME
        layerPanelOpen = false
    }

    fun goCreate() {
        currentPage = Page.CREATE
    }

    fun startPainting(
        w: Int,
        h: Int,
    ) {
        if (ReverieCoreBridge.newDocument(w, h)) {
            docWidth = w
            docHeight = h
            docName = "画布"
            currentPage = Page.PAINTING
            refreshDisplay()
        }
    }

    fun openProject(p: Project) {
        if (ReverieCoreBridge.newDocument(p.w, p.h)) {
            docWidth = p.w
            docHeight = p.h
            docName = p.name
            currentPage = Page.PAINTING
            refreshDisplay()
        }
    }

    /** Re-render the C++ composited document into the display bitmap. */
    fun refreshDisplay() {
        val w = docWidth
        val h = docHeight
        if (w <= 0 || h <= 0) return
        val bmp = displayBitmap
        val bitmap =
            if (bmp != null && bmp.width == w && bmp.height == h) {
                bmp
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    displayBitmap = it
                }
            }
        ReverieCoreBridge.renderToBuffer(bitmap)
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
        ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), 1.0)
    }

    fun touchMove(
        x: Float,
        y: Float,
    ) {
        // Send the sample to the C++ engine immediately (it flushes its own
        // batch), but defer the full composite to touchEnd to keep strokes
        // smooth. Rendering at 60fps during a stroke is too slow on Android.
        ReverieCoreBridge.touchStrokeMove(x.toDouble(), y.toDouble(), 1.0)
        // Throttle repaints: at most every ~33ms (30fps) while stroking so
        // the user sees live feedback without blocking input.
        val now = System.nanoTime()
        if (now - lastPaintNs > 33_000_000L) {
            lastPaintNs = now
            refreshDisplay()
        }
    }

    fun touchEnd() {
        ReverieCoreBridge.touchStrokeEnd()
        refreshDisplay()
    }

    fun touchCancel() {
        ReverieCoreBridge.touchStrokeEnd()
    }

    /** Map a UI tool to the engine's tool mode and record the active tool. */
    var activeTool by mutableStateOf("brush")
        private set

    fun applyTool(toolId: String) {
        activeTool = toolId
        when (toolId) {
            "brush" -> ReverieCoreBridge.setToolMode(0) // ToolBrush
            "eraser" -> ReverieCoreBridge.setToolMode(1) // ToolEraser
            "fill" -> ReverieCoreBridge.setToolMode(2) // ToolFill
            "smudge" -> ReverieCoreBridge.setToolMode(3) // ToolSmudge
            else -> ReverieCoreBridge.setToolMode(0)
        }
    }

    fun undo() {
        if (ReverieCoreBridge.canUndo()) {
            ReverieCoreBridge.undo()
            refreshDisplay()
        }
    }

    fun redo() {
        if (ReverieCoreBridge.canRedo()) {
            ReverieCoreBridge.redo()
            refreshDisplay()
        }
    }

    fun liquify(fx: Float, fy: Float, tx: Float, ty: Float) {
        ReverieCoreBridge.liquify(fx.toInt(), fy.toInt(), tx.toInt(), ty.toInt())
        refreshDisplay()
    }

    fun lassoFill(points: List<Pair<Int, Int>>) {
        val xs = points.map { it.first }.toIntArray()
        val ys = points.map { it.second }.toIntArray()
        ReverieCoreBridge.lassoFill(xs, ys, points.size)
        refreshDisplay()
    }

    fun lassoClear(points: List<Pair<Int, Int>>) {
        val xs = points.map { it.first }.toIntArray()
        val ys = points.map { it.second }.toIntArray()
        ReverieCoreBridge.lassoClear(xs, ys, points.size)
        refreshDisplay()
    }

    fun drawText(
        x: Float,
        y: Float,
        text: String,
        fontSize: Double,
    ) {
        ReverieCoreBridge.drawText(x.toInt(), y.toInt(), text, fontSize)
        refreshDisplay()
    }

    fun drawShape(
        kind: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        refreshDisplay()
    }

    fun floodFill(x: Float, y: Float) {
        ReverieCoreBridge.floodFillAt(x.toInt(), y.toInt())
        refreshDisplay()
    }

    fun addLayer() {
        ReverieCoreBridge.addLayer("图层 ${layerCount + 1}")
        refreshDisplay()
    }

    fun removeLayer() {
        ReverieCoreBridge.removeLayer(currentLayerIndex)
        refreshDisplay()
    }

    fun setCurrentLayer(i: Int) {
        ReverieCoreBridge.setCurrentLayer(i)
        refreshDisplay()
    }

    val blendModes = listOf(
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

    fun setLayerBlendMode(i: Int, opId: String) {
        ReverieCoreBridge.setLayerBlendMode(i, opId)
        refreshDisplay()
    }

    fun toggleLayerVisible(i: Int) {
        ReverieCoreBridge.setLayerVisible(i, !ReverieCoreBridge.layerVisible(i))
        refreshDisplay()
    }

    fun layerName(i: Int) = ReverieCoreBridge.layerName(i)

    /** Sample the color at document-space (x, y) and set it as the brush color. */
    fun pickColor(x: Float, y: Float) {
        val c = ReverieCoreBridge.pickColorAt(x.toInt(), y.toInt()) ?: return
        updateBrushColor(c)
    }

    fun layerVisible(i: Int) = ReverieCoreBridge.layerVisible(i)
}

enum class Page { HOME, CREATE, PAINTING }
