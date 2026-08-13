package com.reverie.paint.ui.painting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Observable state of the transform tool's rubber band (document coords)
 *
 * Lifted to the painting page so the TransformPanel can read rotation/scale
 * and commit via vm.applyTransform while CanvasView owns the gestures
 */
class TransformState {
    var active by mutableStateOf(false)
    var bounds by mutableStateOf(Rect.Zero)
    var scaleX by mutableStateOf(1f)
    var scaleY by mutableStateOf(1f)
    var rotation by mutableStateOf(0f)
    var tx by mutableStateOf(0f)
    var ty by mutableStateOf(0f)

    // gesture-internal (dragging handle id, drag start, start values)
    var handle by mutableStateOf(-1)
    var dragStart by mutableStateOf(Offset.Zero)
    var startScaleX by mutableStateOf(1f)
    var startScaleY by mutableStateOf(1f)
    var startRotation by mutableStateOf(0f)
    var startTx by mutableStateOf(0f)
    var startTy by mutableStateOf(0f)

    fun reset(b: Rect) {
        active = true
        bounds = b
        scaleX = 1f
        scaleY = 1f
        rotation = 0f
        tx = 0f
        ty = 0f
        handle = -1
    }
}
