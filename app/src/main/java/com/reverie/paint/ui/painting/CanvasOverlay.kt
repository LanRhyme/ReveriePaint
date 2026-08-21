package com.reverie.paint.ui.painting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin


/**
 * Full workspace canvas with one shared forward and inverse transform
 *
 * The pointer handler deliberately does not key on zoom/pan/rotation. Those
 * states change on every gesture event; keying on them cancels pointerInput
 * during the gesture and was the reason pinch/rotate stopped after one frame
 */
@Composable
internal fun CanvasOverlay(
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    vm: PaintViewModel,
    zoom: Float,
    rotation: Float,
    panX: Float,
    panY: Float,
    fitScale: Float,
    tool: Tool,
    tfState: TransformState,
    polyPoints: List<Offset>,
    cropRect: androidx.compose.ui.geometry.Rect?,
    liveShapeStart: androidx.compose.runtime.MutableState<Offset?>,
    liveShapeEnd: androidx.compose.runtime.MutableState<Offset?>,
    measureStart: androidx.compose.runtime.MutableState<Offset?>,
    measureEnd: androidx.compose.runtime.MutableState<Offset?>,
    pickerActive: androidx.compose.runtime.MutableState<Boolean>,
    pickerScreenPos: androidx.compose.runtime.MutableState<Offset>,
    pickerInitialColor: androidx.compose.runtime.MutableState<Color>,
    pickerCurrentColor: androidx.compose.runtime.MutableState<Color>,
    cursorScreenPos: androidx.compose.runtime.MutableState<Offset?>,
    isCursorHovering: androidx.compose.runtime.MutableState<Boolean>,
    isCursorTouching: androidx.compose.runtime.MutableState<Boolean>,
    livePressure: androidx.compose.runtime.MutableState<Float>,
    wandFlash: androidx.compose.runtime.MutableState<Offset?>,
    liveSelectionPath: androidx.compose.runtime.MutableState<androidx.compose.ui.graphics.Path?>,
    checkerboardPaint: android.graphics.Paint,
) {
        Canvas(Modifier.fillMaxSize()) {
            val rev = vm.displayRevision
            val bmp = vm.displayBitmap ?: return@Canvas
            val image = imageBitmap ?: bmp.asImageBitmap()
            val imgW = bmp.width.toFloat()
            val imgH = bmp.height.toFloat()
            val scale = (zoom * fitScale).coerceAtLeast(0.001f)
            val center = Offset(size.width / 2f + panX, size.height / 2f + panY)
            withTransform({
                translate(center.x + 8f, center.y + 8f)
                rotate(rotation, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawRect(
                    Morandi.canvasShadow,
                    topLeft = Offset(-imgW / 2f, -imgH / 2f),
                    size = androidx.compose.ui.geometry.Size(imgW, imgH),
                )
            }
            withTransform({
                translate(center.x, center.y)
                rotate(rotation, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                // Draw transparency checkerboard under the canvas image
                val nativeCanvas = drawContext.canvas.nativeCanvas
                nativeCanvas.drawRect(
                    -imgW / 2f,
                    -imgH / 2f,
                    imgW / 2f,
                    imgH / 2f,
                    checkerboardPaint
                )
                
                // Draw the actual canvas image over the checkerboard
                val imagePaint = android.graphics.Paint().apply {
                    isFilterBitmap = vm.magnificationInterpolation
                    isAntiAlias = vm.magnificationInterpolation
                    isDither = true
                }
                nativeCanvas.drawBitmap(bmp, -imgW / 2f, -imgH / 2f, imagePaint)

                // Pixel grid on high zoom (scale >= 4.0)
                if (vm.pixelGridEnabled && scale >= 4f) {
                    val halfW = imgW / 2f
                    val halfH = imgH / 2f
                    val gridAlpha = ((scale - 4f) / 4f).coerceIn(0f, 1f) * 0.15f
                    if (gridAlpha > 0.01f) {
                        val gridPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb((gridAlpha * 255).toInt(), 255, 255, 255)
                            strokeWidth = 1f / scale
                            style = android.graphics.Paint.Style.STROKE
                        }
                        for (gx in 0..image.width) {
                            nativeCanvas.drawLine(gx - halfW, -halfH, gx - halfW, halfH, gridPaint)
                        }
                        for (gy in 0..image.height) {
                            nativeCanvas.drawLine(-halfW, gy - halfH, halfW, gy - halfH, gridPaint)
                        }
                    }
                }

                // Draw transform preview
                val previewBmp = vm.transformPreviewBitmap
                if ((tool == Tool.TRANSFORM || tool == Tool.MOVE) && tfState.active && previewBmp != null) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    if (tool == Tool.TRANSFORM && tfState.mode == TransformMode.DISTORT) {
                        // 3x3 Mesh Grid (9 cells) Piecewise Quad Warping on GPU
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val aBmp = previewBmp.asAndroidBitmap()
                        val b = tfState.bounds
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)

                        for (r in 0..2) {
                            for (c in 0..2) {
                                val sLeft = (b.left + b.width * (c / 3f)) * scX - image.width / 2f
                                val sRight = (b.left + b.width * ((c + 1) / 3f)) * scX - image.width / 2f
                                val sTop = (b.top + b.height * (r / 3f)) * scY - image.height / 2f
                                val sBottom = (b.top + b.height * ((r + 1) / 3f)) * scY - image.height / 2f

                                val srcQuad = floatArrayOf(
                                    sLeft, sTop,
                                    sRight, sTop,
                                    sRight, sBottom,
                                    sLeft, sBottom,
                                )

                                val pTL = tfState.meshPoints[r * 4 + c]
                                val pTR = tfState.meshPoints[r * 4 + (c + 1)]
                                val pBR = tfState.meshPoints[(r + 1) * 4 + (c + 1)]
                                val pBL = tfState.meshPoints[(r + 1) * 4 + c]

                                val dstQuad = floatArrayOf(
                                    pTL.x * scX - image.width / 2f, pTL.y * scY - image.height / 2f,
                                    pTR.x * scX - image.width / 2f, pTR.y * scY - image.height / 2f,
                                    pBR.x * scX - image.width / 2f, pBR.y * scY - image.height / 2f,
                                    pBL.x * scX - image.width / 2f, pBL.y * scY - image.height / 2f,
                                )

                                val m = android.graphics.Matrix()
                                if (m.setPolyToPoly(srcQuad, 0, dstQuad, 0, 4)) {
                                    nativeCanvas.save()
                                    val clipPath = android.graphics.Path().apply {
                                        moveTo(dstQuad[0], dstQuad[1])
                                        lineTo(dstQuad[2], dstQuad[3])
                                        lineTo(dstQuad[4], dstQuad[5])
                                        lineTo(dstQuad[6], dstQuad[7])
                                        close()
                                    }
                                    nativeCanvas.clipPath(clipPath)
                                    nativeCanvas.concat(m)
                                    val cellSrcRect = android.graphics.Rect(
                                        (b.left + b.width * (c / 3f)).toInt().coerceIn(0, aBmp.width),
                                        (b.top + b.height * (r / 3f)).toInt().coerceIn(0, aBmp.height),
                                        (b.left + b.width * ((c + 1) / 3f)).toInt().coerceIn(0, aBmp.width),
                                        (b.top + b.height * ((r + 1) / 3f)).toInt().coerceIn(0, aBmp.height)
                                    )
                                    val cellDstRect = android.graphics.RectF(sLeft, sTop, sRight, sBottom)
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
                        val src = floatArrayOf(
                            b.left * scX - image.width / 2f, b.top * scY - image.height / 2f,
                            b.right * scX - image.width / 2f, b.top * scY - image.height / 2f,
                            b.right * scX - image.width / 2f, b.bottom * scY - image.height / 2f,
                            b.left * scX - image.width / 2f, b.bottom * scY - image.height / 2f,
                        )
                        val c0 = tfState.quadCorners[0]
                        val c1 = tfState.quadCorners[1]
                        val c2 = tfState.quadCorners[2]
                        val c3 = tfState.quadCorners[3]
                        val dst = floatArrayOf(
                            c0.x * scX - image.width / 2f, c0.y * scY - image.height / 2f,
                            c1.x * scX - image.width / 2f, c1.y * scY - image.height / 2f,
                            c2.x * scX - image.width / 2f, c2.y * scY - image.height / 2f,
                            c3.x * scX - image.width / 2f, c3.y * scY - image.height / 2f,
                        )
                        val m = android.graphics.Matrix()
                        if (m.setPolyToPoly(src, 0, dst, 0, 4)) {
                            nativeCanvas.save()
                            nativeCanvas.concat(m)
                            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)
                            val srcRect = android.graphics.Rect(
                                b.left.toInt().coerceIn(0, aBmp.width),
                                b.top.toInt().coerceIn(0, aBmp.height),
                                b.right.toInt().coerceIn(0, aBmp.width),
                                b.bottom.toInt().coerceIn(0, aBmp.height)
                            )
                            val dstRect = android.graphics.RectF(
                                b.left * scX - image.width / 2f,
                                b.top * scY - image.height / 2f,
                                b.right * scX - image.width / 2f,
                                b.bottom * scY - image.height / 2f
                            )
                            nativeCanvas.drawBitmap(aBmp, srcRect, dstRect, p)
                            nativeCanvas.restore()
                        }
                    } else {
                        // Standard / Free / Move Affine Transform
                        val c = tfState.bounds.center
                        val b = tfState.bounds
                        withTransform({
                            translate(c.x * scX - image.width / 2f + tfState.tx * scX, c.y * scY - image.height / 2f + tfState.ty * scY)
                            rotate(tfState.rotation, pivot = Offset.Zero)
                            scale(tfState.scaleX, tfState.scaleY, pivot = Offset.Zero)
                            translate(-c.x * scX + image.width / 2f, -c.y * scY + image.height / 2f)
                        }) {
                            val srcOffset = androidx.compose.ui.unit.IntOffset(b.left.toInt().coerceIn(0, previewBmp.width), b.top.toInt().coerceIn(0, previewBmp.height))
                            val srcSize = androidx.compose.ui.unit.IntSize(b.width.toInt().coerceAtLeast(1), b.height.toInt().coerceAtLeast(1))
                            val dstOffset = androidx.compose.ui.unit.IntOffset((b.left * scX - image.width / 2f).toInt(), (b.top * scY - image.height / 2f).toInt())
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

                // Magic-wand tap flash: instant feedback ring in document
                // space (scaled into bitmap space like the preview path)
                wandFlash.value?.let { wf ->
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 6.dp.toPx(),
                        center = Offset(wf.x * scX - image.width / 2f, wf.y * scY - image.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    )
                }

                // Point-click shape preview (polygon/polyline/select)
                if (polyPoints.isNotEmpty() && (tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.SELECT_POLYGON)) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val pth = androidx.compose.ui.graphics.Path()
                    pth.moveTo(
                        polyPoints[0].x * scX - image.width / 2f,
                        polyPoints[0].y * scY - image.height / 2f,
                    )
                    for (i in 1 until polyPoints.size) {
                        pth.lineTo(
                            polyPoints[i].x * scX - image.width / 2f,
                            polyPoints[i].y * scY - image.height / 2f,
                        )
                    }
                    if (tool == Tool.POLYGON) {
                        pth.close()
                    }
                    drawPath(
                        pth,
                        color = Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                    polyPoints.forEach { pt ->
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx(),
                            center = Offset(pt.x * scX - image.width / 2f, pt.y * scY - image.height / 2f),
                        )
                    }
                }

                // Measure tool: white line + distance/angle text
                if (tool == Tool.MEASURE && measureStart.value != null && measureEnd.value != null) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val s = measureStart.value!!
                    val e = measureEnd.value!!
                    val p1 = Offset(s.x * scX - image.width / 2f, s.y * scY - image.height / 2f)
                    val p2 = Offset(e.x * scX - image.width / 2f, e.y * scY - image.height / 2f)
                    drawLine(Color.White, p1, p2, strokeWidth = 2.dp.toPx())
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = p1)
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = p2)
                    val dist = hypot(e.x - s.x, e.y - s.y)
                    val ang = Math.toDegrees(atan2((e.y - s.y).toDouble(), (e.x - s.x).toDouble())).toFloat()
                    val label =
                        "%.0f px  %.1f°".format(dist, ang)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        (p2.x + 8.dp.toPx()),
                        (p2.y - 8.dp.toPx()),
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textSize = 13.dp.toPx()
                            isFakeBoldText = true
                        },
                    )
                }

                // Crop tool preview: dim the outside, white frame
                cropRect?.let { cr ->
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { v: Float -> v * scX - image.width / 2f }
                    val by2 = { v: Float -> v * scY - image.height / 2f }
                    val hole =
                        androidx.compose.ui.geometry.Rect(
                            bx(cr.left),
                            by2(cr.top),
                            bx(cr.right),
                            by2(cr.bottom),
                        )
                    drawContext.canvas.saveLayer(
                        androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()),
                        androidx.compose.ui.graphics.Paint(),
                    )
                    drawRect(color = Color.Black.copy(alpha = 0.4f))
                    drawRect(
                        color = Color.White,
                        topLeft = hole.topLeft,
                        size = hole.size,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
                    )
                    drawContext.canvas.restore()
                    drawRect(
                        color = Color.White,
                        topLeft = hole.topLeft,
                        size = hole.size,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }

                // Transform tool rubber band (bitmap space, origin at the image centre)
                if (tool == Tool.TRANSFORM && tfState.active) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - image.width / 2f, p.y * scY - image.height / 2f) }
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

                // Polygon / Polyline vertices preview
                if (polyPoints.isNotEmpty()) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - image.width / 2f, p.y * scY - image.height / 2f) }
                    val mappedPts = polyPoints.map { bx(it) }
                    val currentScale = zoom * fitScale
                    val polyPath = androidx.compose.ui.graphics.Path()
                    polyPath.moveTo(mappedPts[0].x, mappedPts[0].y)
                    for (i in 1 until mappedPts.size) {
                        polyPath.lineTo(mappedPts[i].x, mappedPts[i].y)
                    }
                    if (tool == Tool.POLYGON && mappedPts.size >= 3) {
                        polyPath.close()
                    }
                    drawPath(
                        polyPath,
                        color = Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale),
                    )
                    mappedPts.forEach { pt ->
                        drawCircle(
                            color = Morandi.accent,
                            radius = 5.dp.toPx() / currentScale,
                            center = pt,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx() / currentScale,
                            center = pt,
                        )
                    }
                }

                // Live shape drawing preview (line, rect, ellipse, gradient)
                if (liveShapeStart.value != null && liveShapeEnd.value != null) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - image.width / 2f, p.y * scY - image.height / 2f) }
                    val s = bx(liveShapeStart.value!!)
                    val e = bx(liveShapeEnd.value!!)
                    val currentScale = zoom * fitScale
                    val strokeStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale)

                    when (tool) {
                        Tool.LINE -> drawLine(Color.White, s, e, strokeWidth = 2.dp.toPx() / currentScale)
                        Tool.RECT -> {
                            val r = androidx.compose.ui.geometry.Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                            drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = strokeStyle)
                        }
                        Tool.ELLIPSE -> {
                            val r = androidx.compose.ui.geometry.Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                            drawOval(Color.White, topLeft = r.topLeft, size = r.size, style = strokeStyle)
                        }
                        Tool.GRADIENT -> {
                            drawLine(Color.White, s, e, strokeWidth = 2.dp.toPx() / currentScale)
                            drawCircle(Color.White, radius = 5.dp.toPx() / currentScale, center = s)
                            drawCircle(Morandi.accent, radius = 5.dp.toPx() / currentScale, center = e)
                        }
                        else -> Unit
                    }
                }

                val selBmp = vm.selectionOverlayBitmap?.asImageBitmap()
                if (selBmp != null || liveSelectionPath.value != null) {
                    val paint = androidx.compose.ui.graphics.Paint().apply {
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                            Morandi.accent.copy(alpha = 0.35f)
                        )
                    }
                    val bounds = androidx.compose.ui.geometry.Rect(
                        -image.width / 2f, -image.height / 2f,
                        image.width / 2f, image.height / 2f
                    )
                    drawContext.canvas.saveLayer(bounds, paint)

                    if (selBmp != null) {
                        // The selection overlay is full-document resolution;
                        // scale it into the (viewport-sized) canvas image
                        drawImage(
                            image = selBmp,
                            topLeft = Offset(-image.width / 2f, -image.height / 2f),
                        )
                    }

                    liveSelectionPath.value?.let { livePath ->
                        // The preview path is a pure visual outline: the live
                        // fill overlay already reflects the merged result (the
                        // C++ preview merge runs the same combine as the
                        // committed path), so drawing the outline with a
                        // subtract/intersect blend mode would carve a hole in
                        // that merged fill and visibly differ from the final
                        // selection - keep it SrcOver on top of the fill
                        drawPath(
                            path = livePath,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 2.dp.toPx(),
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver
                        )
                    }

                    drawContext.canvas.restore()
                }
            }

            // Draw PaintWorld-style Color Loupe when picker is active
            if (pickerActive.value) {
                val loupeCenter = pickerScreenPos.value + Offset(0f, -80.dp.toPx())
                val outerRadius = 45.dp.toPx()
                val innerRadius = 28.dp.toPx()
                val ringThickness = outerRadius - innerRadius
                val ringRadius = (outerRadius + innerRadius) / 2f

                // Outer drop shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = outerRadius + 4.dp.toPx(),
                    center = loupeCenter
                )

                // Top half ring: Reference / Previous color
                drawArc(
                    color = pickerInitialColor.value,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringThickness)
                )

                // Bottom half ring: Current sampled color
                drawArc(
                    color = pickerCurrentColor.value,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringThickness)
                )

                // Outer border line
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = outerRadius,
                    center = loupeCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
                // Inner border line
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = innerRadius,
                    center = loupeCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )

                // Center crosshair inside the loupe
                val crosshairInner = 6.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.7f),
                    start = Offset(loupeCenter.x - crosshairInner, loupeCenter.y),
                    end = Offset(loupeCenter.x + crosshairInner, loupeCenter.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.7f),
                    start = Offset(loupeCenter.x, loupeCenter.y - crosshairInner),
                    end = Offset(loupeCenter.x, loupeCenter.y + crosshairInner),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Crosshair at the target touch point on the canvas
                val crossLen = 14.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(pickerScreenPos.value.x - crossLen, pickerScreenPos.value.y),
                    end = Offset(pickerScreenPos.value.x + crossLen, pickerScreenPos.value.y),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(pickerScreenPos.value.x - crossLen, pickerScreenPos.value.y),
                    end = Offset(pickerScreenPos.value.x + crossLen, pickerScreenPos.value.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(pickerScreenPos.value.x, pickerScreenPos.value.y - crossLen),
                    end = Offset(pickerScreenPos.value.x, pickerScreenPos.value.y + crossLen),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(pickerScreenPos.value.x, pickerScreenPos.value.y - crossLen),
                    end = Offset(pickerScreenPos.value.x, pickerScreenPos.value.y + crossLen),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // ---- 7. Brush cursor ring lives in its own layer now ----
            // (see BrushCursorOverlay below): the cursor states change at
            // input rate, and sharing this Canvas re-drew the full-screen
            // image on every pointer move
        }
}

/**
 * Brush cursor ring in its OWN Canvas layer above the image canvas.
 * cursorScreenPos / livePressure change at input rate while drawing; sharing
 * one Canvas with the (full-screen) image draw meant every pointer move
 * re-executed the whole overlay draw including the big drawImage - on top of
 * the per-render displayRevision redraws. Now cursor moves only invalidate
 * this (visually tiny) layer.
 */
@Composable
internal fun BrushCursorOverlay(
    vm: PaintViewModel,
    tool: Tool,
    zoom: Float,
    fitScale: Float,
    liquifyBrushSize: Float = 60f,
    cursorScreenPos: androidx.compose.runtime.MutableState<Offset?>,
    isCursorHovering: androidx.compose.runtime.MutableState<Boolean>,
    isCursorTouching: androidx.compose.runtime.MutableState<Boolean>,
    livePressure: androidx.compose.runtime.MutableState<Float>,
) {
    Canvas(Modifier.fillMaxSize()) {
        val isEraser = tool == Tool.ERASER
        val cursorMode = if (isEraser) vm.eraserCursorMode else vm.brushCursorMode
        // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
        val shouldShow = when (cursorMode) {
            1 -> isCursorTouching.value
            2 -> isCursorHovering.value
            3 -> isCursorTouching.value || isCursorHovering.value
            else -> false
        }
        // Liquify hides the system cursor too (hideSystemCursorForTool) and
        // has its own brush size - without the ring here the tool would have
        // NO visible cursor at all
        val isDrawTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.LIQUIFY
        if (shouldShow && cursorScreenPos.value != null && isDrawTool) {
            val curPos = cursorScreenPos.value!!
            val scale = (zoom * fitScale).coerceAtLeast(0.001f)
            val pressureScale = if (isCursorTouching.value) livePressure.value.coerceIn(0.08f, 1f) else 1f
            val cursorBrushSize = if (tool == Tool.LIQUIFY) liquifyBrushSize else vm.brushSize.toFloat()
            val brushRadiusScreen = (cursorBrushSize * scale * 0.5f * pressureScale).toFloat().coerceAtLeast(2f)

            when (vm.cursorStyleMode) {
                0 -> { // 圆形 (Brush Outline Ring - Krita dual-contrast circle)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = brushRadiusScreen + 0.8f,
                        center = curPos,
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = brushRadiusScreen,
                        center = curPos,
                        style = Stroke(width = 1.0.dp.toPx())
                    )
                }
                1 -> { // 十字准星 (Crosshair)
                    val len = 12.dp.toPx()
                    val gap = 3.5.dp.toPx()
                    // Black outline
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x - len, curPos.y), Offset(curPos.x - gap, curPos.y), strokeWidth = 3.dp.toPx())
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x + gap, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 3.dp.toPx())
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y - gap), strokeWidth = 3.dp.toPx())
                    drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x, curPos.y + gap), Offset(curPos.x, curPos.y + len), strokeWidth = 3.dp.toPx())
                    // White foreground
                    drawLine(Color.White, Offset(curPos.x - len, curPos.y), Offset(curPos.x - gap, curPos.y), strokeWidth = 1.5.dp.toPx())
                    drawLine(Color.White, Offset(curPos.x + gap, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 1.5.dp.toPx())
                    drawLine(Color.White, Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y - gap), strokeWidth = 1.5.dp.toPx())
                    drawLine(Color.White, Offset(curPos.x, curPos.y + gap), Offset(curPos.x, curPos.y + len), strokeWidth = 1.5.dp.toPx())
                }
                2 -> { // 点 (Precise Dot)
                    drawCircle(Color.Black.copy(alpha = 0.6f), radius = 3.5.dp.toPx(), center = curPos)
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = curPos)
                }
                3 -> {} // 无 (No Cursor)
            }
        }
    }
}
