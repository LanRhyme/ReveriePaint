/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

enum class TransformMode {
    STANDARD,    // 标准: 等比锁定缩放
    FREE,        // 自由: 自由拉伸缩放
    PERSPECTIVE, // 透视: 4点透视变形
    DISTORT,     // 扭曲: 操控变形-3x3网格(16控制点)
}

/**
 * Observable state of the transform tool's rubber band (document coords)
 */
class TransformState {
    var active by mutableStateOf(false)
    var bounds by mutableStateOf(Rect.Zero)
    var mode by mutableStateOf(TransformMode.STANDARD)

    // Affine transform params (for Standard & Free)
    var scaleX by mutableStateOf(1f)
    var scaleY by mutableStateOf(1f)
    var rotation by mutableStateOf(0f)
    var tx by mutableStateOf(0f)
    var ty by mutableStateOf(0f)

    // 4 corners for Perspective mode: [0: TL, 1: TR, 2: BR, 3: BL]
    var quadCorners by mutableStateOf(listOf(Offset.Zero, Offset.Zero, Offset.Zero, Offset.Zero))

    // 16 control points for 3x3 Mesh Grid (Distort mode): row 0..3, col 0..3
    var meshPoints by mutableStateOf(List(16) { Offset.Zero })
    var origMeshPoints by mutableStateOf(List(16) { Offset.Zero })

    // gesture-internal (dragging handle id, drag start, start values)
    var handle by mutableStateOf(-1)
    var dragStart by mutableStateOf(Offset.Zero)
    var startScaleX by mutableStateOf(1f)
    var startScaleY by mutableStateOf(1f)
    var startRotation by mutableStateOf(0f)
    var startTx by mutableStateOf(0f)
    var startTy by mutableStateOf(0f)
    var startQuadCorners by mutableStateOf(listOf(Offset.Zero, Offset.Zero, Offset.Zero, Offset.Zero))
    var startMeshPoints by mutableStateOf(List(16) { Offset.Zero })

    fun reset(b: Rect) {
        active = true
        bounds = b
        scaleX = 1f
        scaleY = 1f
        rotation = 0f
        tx = 0f
        ty = 0f
        quadCorners = listOf(b.topLeft, b.topRight, b.bottomRight, b.bottomLeft)
        val mList = List(16) { idx ->
            val r = idx / 4
            val c = idx % 4
            Offset(
                b.left + b.width * (c / 3f),
                b.top + b.height * (r / 3f),
            )
        }
        meshPoints = mList
        origMeshPoints = mList
        handle = -1
    }
}

// ---- Transform geometry helpers (relocated from the removed legacy
// CanvasGestures.kt; used by CanvasOverlay handle drawing and
// CanvasTouchView handle hit-testing) ----

internal fun tfTransform(
    tfState: TransformState,
    p: Offset,
): Offset {
    val c = tfState.bounds.center
    val dx = p.x - c.x
    val dy = p.y - c.y
    val sx = dx * tfState.scaleX
    val sy = dy * tfState.scaleY
    val rad = Math.toRadians(tfState.rotation.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val rx = sx * cos - sy * sin
    val ry = sx * sin + sy * cos
    return Offset(rx + c.x + tfState.tx, ry + c.y + tfState.ty)
}

internal fun tfHandles(tfState: TransformState): List<Offset> {
    if (tfState.mode == TransformMode.PERSPECTIVE) {
        return tfState.quadCorners
    }
    if (tfState.mode == TransformMode.DISTORT) {
        return tfState.meshPoints
    }
    val r = tfState.bounds
    val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
    val mids =
        listOf(
            Offset((r.left + r.right) / 2f, r.top),
            Offset(r.right, (r.top + r.bottom) / 2f),
            Offset((r.left + r.right) / 2f, r.bottom),
            Offset(r.left, (r.top + r.bottom) / 2f),
        )
    return corners.map { tfTransform(tfState, it) } + mids.map { tfTransform(tfState, it) }
}
