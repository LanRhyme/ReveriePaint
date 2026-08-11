package com.reverie.paint.core

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

    // Display bitmap (updated via renderToBuffer)
    var displayBitmap by mutableStateOf<Bitmap?>(null)
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
        ReverieCoreBridge.touchStrokeMove(x.toDouble(), y.toDouble(), 1.0)
        // Throttle repaints: flush at most every ~16ms (60fps) while stroking.
        val now = System.nanoTime()
        if (now - lastPaintNs > 16_000_000L) {
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
