/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.reverie.paint.core.*
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.getOrCreateStylusDriver(): com.reverie.paint.core.stylus.StylusDriver? {
    val cached = cachedDriver
    if (cached != null) return cached
    val v = vm ?: return null
    val driver = v.getOrCreateStylusDriver(context)
    cachedDriver = driver
    return driver
}

internal fun CanvasTouchView.setLocalCursorPos(x: Float, y: Float) {
    localCursorX = x
    localCursorY = y
}

internal fun CanvasTouchView.clearLocalCursorPos() {
    localCursorX = Float.NaN
    localCursorY = Float.NaN
}

internal fun CanvasTouchView.updateCursorAngleFromDocMove(docX: Float, docY: Float, isDown: Boolean) {
    if (isDown) {
        lastCursorMoveDocX = docX
        lastCursorMoveDocY = docY
    } else if (!lastCursorMoveDocX.isNaN() && !lastCursorMoveDocY.isNaN()) {
        val dX = docX - lastCursorMoveDocX
        val dY = docY - lastCursorMoveDocY
        if (hypot(dX, dY) > 1.5f) {
            localCursorAngle = Math.toDegrees(kotlin.math.atan2(dY.toDouble(), dX.toDouble())).toFloat()
            lastCursorMoveDocX = docX
            lastCursorMoveDocY = docY
        }
    } else {
        lastCursorMoveDocX = docX
        lastCursorMoveDocY = docY
    }
}

internal fun CanvasTouchView.safeBeginSymmetryUndoMacro() {
    if (!isSymmetryUndoMacroOpen) {
        vm?.runCore(render = false) {
            ReverieCoreBridge.beginUndoMacro("Symmetry Stroke")
        }
        isSymmetryUndoMacroOpen = true
    }
}

internal fun CanvasTouchView.safeEndSymmetryUndoMacro() {
    if (isSymmetryUndoMacroOpen) {
        vm?.runCore(render = false) {
            ReverieCoreBridge.endUndoMacro()
        }
        isSymmetryUndoMacroOpen = false
    }
}
