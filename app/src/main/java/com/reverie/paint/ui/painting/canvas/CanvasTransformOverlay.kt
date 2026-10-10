/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi

internal fun DrawScope.drawTransformPreview(
    vm: PaintViewModel,
    tool: Tool,
    tfState: TransformState,
    bmpWidth: Float,
    bmpHeight: Float,
    previewSrcQuad: FloatArray,
    previewDstQuad: FloatArray,
    previewMatrix: Matrix,
    previewClipPath: AndroidPath,
    previewSrcRect: Rect,
    previewDstRectF: RectF,
    previewBitmapPaint: Paint,
) {
    val bmp = object {
        val width = bmpWidth.toInt()
        val height = bmpHeight.toInt()
    }
                // Draw transform preview
                val previewBmp = vm.transformPreviewBitmap
                if (tool == Tool.TRANSFORM && tfState.active && previewBmp != null) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    if (tool == Tool.TRANSFORM && tfState.mode == TransformMode.DISTORT) {
                        // 3x3 Mesh Grid (9 cells) Piecewise Quad Warping on GPU
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val aBmp = previewBmp.asAndroidBitmap()
                        val b = tfState.bounds
                        val p = previewBitmapPaint

                        for (r in 0..2) {
                            for (c in 0..2) {
                                val sLeft = (b.left + b.width * (c / 3f)) * scX - bmp.width / 2f
                                val sRight = (b.left + b.width * ((c + 1) / 3f)) * scX - bmp.width / 2f
                                val sTop = (b.top + b.height * (r / 3f)) * scY - bmp.height / 2f
                                val sBottom = (b.top + b.height * ((r + 1) / 3f)) * scY - bmp.height / 2f

                                val srcQuad = previewSrcQuad
                                srcQuad[0] = sLeft
                                srcQuad[1] = sTop
                                srcQuad[2] = sRight
                                srcQuad[3] = sTop
                                srcQuad[4] = sRight
                                srcQuad[5] = sBottom
                                srcQuad[6] = sLeft
                                srcQuad[7] = sBottom

                                val pTL = tfState.meshPoints[r * 4 + c]
                                val pTR = tfState.meshPoints[r * 4 + (c + 1)]
                                val pBR = tfState.meshPoints[(r + 1) * 4 + (c + 1)]
                                val pBL = tfState.meshPoints[(r + 1) * 4 + c]

                                val dstQuad = previewDstQuad
                                dstQuad[0] = pTL.x * scX - bmp.width / 2f
                                dstQuad[1] = pTL.y * scY - bmp.height / 2f
                                dstQuad[2] = pTR.x * scX - bmp.width / 2f
                                dstQuad[3] = pTR.y * scY - bmp.height / 2f
                                dstQuad[4] = pBR.x * scX - bmp.width / 2f
                                dstQuad[5] = pBR.y * scY - bmp.height / 2f
                                dstQuad[6] = pBL.x * scX - bmp.width / 2f
                                dstQuad[7] = pBL.y * scY - bmp.height / 2f

                                val m = previewMatrix
                                if (m.setPolyToPoly(srcQuad, 0, dstQuad, 0, 4)) {
                                    nativeCanvas.save()
                                    val clipPath = previewClipPath
                                    clipPath.reset()
                                    clipPath.moveTo(dstQuad[0], dstQuad[1])
                                    clipPath.lineTo(dstQuad[2], dstQuad[3])
                                    clipPath.lineTo(dstQuad[4], dstQuad[5])
                                    clipPath.lineTo(dstQuad[6], dstQuad[7])
                                    clipPath.close()
                                    nativeCanvas.clipPath(clipPath)
                                    nativeCanvas.concat(m)
                                    val cellSrcRect = previewSrcRect
                                    cellSrcRect.set(
                                        (b.left + b.width * (c / 3f)).toInt().coerceIn(0, aBmp.width),
                                        (b.top + b.height * (r / 3f)).toInt().coerceIn(0, aBmp.height),
                                        (b.left + b.width * ((c + 1) / 3f)).toInt().coerceIn(0, aBmp.width),
                                        (b.top + b.height * ((r + 1) / 3f)).toInt().coerceIn(0, aBmp.height)
                                    )
                                    val cellDstRect = previewDstRectF
                                    cellDstRect.set(sLeft, sTop, sRight, sBottom)
                                    nativeCanvas.drawBitmap(aBmp, cellSrcRect, cellDstRect, p)
                                    nativeCanvas.restore()
                                }
                            }
                        }
                    } else if (tool == Tool.TRANSFORM && tfState.mode == TransformMode.PERSPECTIVE) {
                        // Projective / Perspective Matrix Mapping using Android nativeCanvas
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val aBmp = previewBmp.asAndroidBitmap()
                        val b = tfState.bounds
                        val src = previewSrcQuad
                        src[0] = b.left * scX - bmp.width / 2f
                        src[1] = b.top * scY - bmp.height / 2f
                        src[2] = b.right * scX - bmp.width / 2f
                        src[3] = b.top * scY - bmp.height / 2f
                        src[4] = b.right * scX - bmp.width / 2f
                        src[5] = b.bottom * scY - bmp.height / 2f
                        src[6] = b.left * scX - bmp.width / 2f
                        src[7] = b.bottom * scY - bmp.height / 2f
                        val c0 = tfState.quadCorners[0]
                        val c1 = tfState.quadCorners[1]
                        val c2 = tfState.quadCorners[2]
                        val c3 = tfState.quadCorners[3]
                        val dst = previewDstQuad
                        dst[0] = c0.x * scX - bmp.width / 2f
                        dst[1] = c0.y * scY - bmp.height / 2f
                        dst[2] = c1.x * scX - bmp.width / 2f
                        dst[3] = c1.y * scY - bmp.height / 2f
                        dst[4] = c2.x * scX - bmp.width / 2f
                        dst[5] = c2.y * scY - bmp.height / 2f
                        dst[6] = c3.x * scX - bmp.width / 2f
                        dst[7] = c3.y * scY - bmp.height / 2f
                        val m = previewMatrix
                        if (m.setPolyToPoly(src, 0, dst, 0, 4)) {
                            nativeCanvas.save()
                            nativeCanvas.concat(m)
                            val p = previewBitmapPaint
                            val srcRect = previewSrcRect
                            srcRect.set(
                                b.left.toInt().coerceIn(0, aBmp.width),
                                b.top.toInt().coerceIn(0, aBmp.height),
                                b.right.toInt().coerceIn(0, aBmp.width),
                                b.bottom.toInt().coerceIn(0, aBmp.height)
                            )
                            val dstRect = previewDstRectF
                            dstRect.set(
                                b.left * scX - bmp.width / 2f,
                                b.top * scY - bmp.height / 2f,
                                b.right * scX - bmp.width / 2f,
                                b.bottom * scY - bmp.height / 2f
                            )
                            nativeCanvas.drawBitmap(aBmp, srcRect, dstRect, p)
                            nativeCanvas.restore()
                        }
                    } else {
                        // Standard / Free / Move Affine Transform
                        val c = tfState.bounds.center
                        val b = tfState.bounds
                        withTransform({
                            translate(c.x * scX - bmp.width / 2f + tfState.tx * scX, c.y * scY - bmp.height / 2f + tfState.ty * scY)
                            rotate(tfState.rotation, pivot = Offset.Zero)
                            scale(tfState.scaleX, tfState.scaleY, pivot = Offset.Zero)
                            translate(-c.x * scX + bmp.width / 2f, -c.y * scY + bmp.height / 2f)
                        }) {
                            val srcOffset = androidx.compose.ui.unit.IntOffset(b.left.toInt().coerceIn(0, previewBmp.width), b.top.toInt().coerceIn(0, previewBmp.height))
                            val srcSize = androidx.compose.ui.unit.IntSize(b.width.toInt().coerceAtLeast(1), b.height.toInt().coerceAtLeast(1))
                            val dstOffset = androidx.compose.ui.unit.IntOffset((b.left * scX - bmp.width / 2f).toInt(), (b.top * scY - bmp.height / 2f).toInt())
                            val dstSize = androidx.compose.ui.unit.IntSize((b.width * scX).toInt().coerceAtLeast(1), (b.height * scY).toInt().coerceAtLeast(1))
                            drawImage(
                                image = previewBmp,
                                srcOffset = srcOffset,
                                srcSize = srcSize,
                                dstOffset = dstOffset,
                                dstSize = dstSize,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            )
                        }
                    }
                }
}

internal fun DrawScope.drawTransformHandles(
    vm: PaintViewModel,
    tool: Tool,
    tfState: TransformState,
    bmpWidth: Float,
    bmpHeight: Float,
    zoom: Float,
    fitScale: Float,
) {
    val bmp = object {
        val width = bmpWidth.toInt()
        val height = bmpHeight.toInt()
    }
                // Transform tool rubber band (bitmap space, origin at the image centre)
                if (tool == Tool.TRANSFORM && tfState.active) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val handles = tfHandles(tfState).map { bx(it) }
                    val currentScale = zoom * fitScale

                    if (tfState.mode == TransformMode.DISTORT) {
                        // 3x3 Mesh Grid (16 Handles + 4 horizontal lines + 4 vertical lines)
                        if (handles.size == 16) {
                            // 1. Draw horizontal grid lines
                            for (r in 0..3) {
                                val linePath = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(handles[r * 4].x, handles[r * 4].y)
                                    for (c in 1..3) {
                                        lineTo(handles[r * 4 + c].x, handles[r * 4 + c].y)
                                    }
                                }
                                val isBorder = (r == 0 || r == 3)
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Color(0xFF181B22) else Color(0x66181B22),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 3.dp.toPx() / currentScale else 2.dp.toPx() / currentScale),
                                )
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Morandi.accent else Color(0x88AAB3C2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 1.5.dp.toPx() / currentScale else 1.dp.toPx() / currentScale),
                                )
                            }
                            // 2. Draw vertical grid lines
                            for (c in 0..3) {
                                val linePath = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(handles[c].x, handles[c].y)
                                    for (r in 1..3) {
                                        lineTo(handles[r * 4 + c].x, handles[r * 4 + c].y)
                                    }
                                }
                                val isBorder = (c == 0 || c == 3)
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Color(0xFF181B22) else Color(0x66181B22),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 3.dp.toPx() / currentScale else 2.dp.toPx() / currentScale),
                                )
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Morandi.accent else Color(0x88AAB3C2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 1.5.dp.toPx() / currentScale else 1.dp.toPx() / currentScale),
                                )
                            }
                            // 3. Draw 16 Control Handles
                            handles.forEachIndexed { idx, h ->
                                val isCorner = (idx == 0 || idx == 3 || idx == 12 || idx == 15)
                                val hr = (if (isCorner) 9.dp.toPx() else 6.5.dp.toPx()) / currentScale
                                drawCircle(Color(0xFF22262E), radius = hr, center = h)
                                drawCircle(if (isCorner) Morandi.accent else Color(0xFFAAB3C2), radius = hr, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale))
                                drawCircle(Color.White, radius = (if (isCorner) 3.dp.toPx() else 2.dp.toPx()) / currentScale, center = h)
                            }
                        }
                    } else if (tfState.mode == TransformMode.PERSPECTIVE) {
                        // 4-Point Quad Frame
                        if (handles.size == 4) {
                            val quadPath = androidx.compose.ui.graphics.Path().apply {
                                moveTo(handles[0].x, handles[0].y)
                                lineTo(handles[1].x, handles[1].y)
                                lineTo(handles[2].x, handles[2].y)
                                lineTo(handles[3].x, handles[3].y)
                                close()
                            }
                            drawPath(
                                quadPath,
                                color = Color(0xFF181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() / currentScale),
                            )
                            drawPath(
                                quadPath,
                                color = Morandi.accent,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale),
                            )
                            val handleRadius = 11.dp.toPx() / currentScale
                            handles.forEach { h ->
                                drawCircle(Color(0xFF22262E), radius = handleRadius, center = h)
                                drawCircle(Morandi.accent, radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx() / currentScale))
                                drawCircle(Color.White, radius = 3.dp.toPx() / currentScale, center = h)
                            }
                        }
                    } else {
                        // Standard / Free 8-Handle Bounding Box
                        val frame = androidx.compose.ui.graphics.Path()
                        if (handles.size >= 4) {
                            frame.moveTo(handles[0].x, handles[0].y)
                            for (i in 1..3) {
                                frame.lineTo(handles[i].x, handles[i].y)
                            }
                            frame.close()

                            // 1. High-contrast dual-layer bounding frame
                            drawPath(
                                frame,
                                color = Color(0xFF181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() / currentScale),
                            )
                            drawPath(
                                frame,
                                color = Color(0xFFAAB3C2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale),
                            )

                            // 2. Center Pivot Indicator
                            val centerDoc = tfState.bounds.center + Offset(tfState.tx, tfState.ty)
                            val centerBmp = bx(centerDoc)
                            val cr = 5.dp.toPx() / currentScale
                            drawLine(Color(0xFF181B22), centerBmp - Offset(cr, 0f), centerBmp + Offset(cr, 0f), strokeWidth = 3.dp.toPx() / currentScale)
                            drawLine(Color(0xFF181B22), centerBmp - Offset(0f, cr), centerBmp + Offset(0f, cr), strokeWidth = 3.dp.toPx() / currentScale)
                            drawLine(Morandi.accent, centerBmp - Offset(cr, 0f), centerBmp + Offset(cr, 0f), strokeWidth = 1.5.dp.toPx() / currentScale)
                            drawLine(Morandi.accent, centerBmp - Offset(0f, cr), centerBmp + Offset(0f, cr), strokeWidth = 1.5.dp.toPx() / currentScale)

                            // 3. Huashijie Pro Style Vector Handle Badges
                            val handleRadius = 11.dp.toPx() / currentScale
                            val badgeStrokeW = 1.4.dp.toPx() / currentScale
                            val glyphSize = handleRadius * 0.52f
                            val glyphColor = Color.White
                            val glyphStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.5.dp.toPx() / currentScale,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                            )

                            handles.forEachIndexed { i, h ->
                                drawCircle(Color(0xFF22262E), radius = handleRadius, center = h)
                                drawCircle(if (i == 1 || i == 3) Morandi.accent else Color(0xFF9098A6), radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = badgeStrokeW))
                                when (i) {
                                    0, 2 -> {
                                        drawLine(glyphColor, h - Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        val ah = glyphSize * 0.45f
                                        drawLine(glyphColor, h - Offset(glyphSize, glyphSize), h - Offset(glyphSize - ah, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h - Offset(glyphSize, glyphSize), h - Offset(glyphSize, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, glyphSize), h + Offset(glyphSize - ah, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    1, 3 -> {
                                        val arcRect = androidx.compose.ui.geometry.Rect(h - Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize))
                                        drawArc(
                                            color = Morandi.accent,
                                            startAngle = 40f,
                                            sweepAngle = 260f,
                                            useCenter = false,
                                            topLeft = arcRect.topLeft,
                                            size = arcRect.size,
                                            style = glyphStroke,
                                        )
                                        val rad = Math.toRadians(300.0)
                                        val tip = h + Offset((glyphSize * kotlin.math.cos(rad)).toFloat(), (glyphSize * kotlin.math.sin(rad)).toFloat())
                                        drawLine(Morandi.accent, tip, tip + Offset(-glyphSize * 0.35f, -glyphSize * 0.2f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(Morandi.accent, tip, tip + Offset(-glyphSize * 0.15f, glyphSize * 0.35f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    4, 6 -> {
                                        drawLine(glyphColor, h - Offset(0f, glyphSize), h + Offset(0f, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        val ah = glyphSize * 0.38f
                                        drawLine(glyphColor, h - Offset(0f, glyphSize), h - Offset(-ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h - Offset(0f, glyphSize), h - Offset(ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(0f, glyphSize), h + Offset(-ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(0f, glyphSize), h + Offset(ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    5, 7 -> {
                                        drawLine(glyphColor, h - Offset(glyphSize, 0f), h + Offset(glyphSize, 0f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        val ah = glyphSize * 0.38f
                                        drawLine(glyphColor, h - Offset(glyphSize, 0f), h - Offset(glyphSize - ah, -ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h - Offset(glyphSize, 0f), h - Offset(glyphSize - ah, ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, 0f), h + Offset(glyphSize - ah, -ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, 0f), h + Offset(glyphSize - ah, ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                }
                            }
                        }
                    }
                }
}
