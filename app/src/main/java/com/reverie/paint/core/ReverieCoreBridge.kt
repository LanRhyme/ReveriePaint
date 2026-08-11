package com.reverie.paint.core

import android.graphics.Bitmap

/**
 * JNI bridge to the C++ ReverieCore engine (Krita-based painting core).
 * All methods are thin wrappers over the native library.
 */
object ReverieCoreBridge {
    init {
        System.loadLibrary("reverie_jni")
    }

    external fun newDocument(
        w: Int,
        h: Int,
    ): Boolean

    external fun fillBackground(color: String)

    external fun clearCanvas()

    external fun addLayer(name: String)

    external fun removeLayer(index: Int)

    external fun setCurrentLayer(index: Int)

    external fun layerCount(): Int

    external fun layerName(index: Int): String

    external fun setLayerVisible(
        index: Int,
        visible: Boolean,
    )

    external fun layerVisible(index: Int): Boolean

    external fun currentLayerIndex(): Int

    external fun setToolMode(mode: Int)
    external fun floodFillAt(x: Int, y: Int)
    external fun setBrushSize(size: Double)

    external fun setBrushColor(color: String)

    external fun setBrushOpacity(opacity: Double)

    external fun touchStrokeStart(
        x: Double,
        y: Double,
        pressure: Double,
    )

    external fun touchStrokeMove(
        x: Double,
        y: Double,
        pressure: Double,
    )

    external fun touchStrokeEnd()

    external fun renderToBuffer(bitmap: Bitmap): Boolean

    external fun pickColorAt(x: Int, y: Int): String?
    external fun drawShape(kind: Int, x1: Int, y1: Int, x2: Int, y2: Int)
    external fun savePng(path: String): Boolean
    external fun loadPng(path: String): Boolean

    external fun docWidth(): Int

    external fun docHeight(): Int
}
