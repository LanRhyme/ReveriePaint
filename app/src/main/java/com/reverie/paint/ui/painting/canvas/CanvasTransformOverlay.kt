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

private const val DISTORT_MESH_SUBDIV = 21
private val distortMeshVerts = FloatArray((DISTORT_MESH_SUBDIV + 1) * (DISTORT_MESH_SUBDIV + 1) * 2)
private val distortMlsWeights = DoubleArray(16)
private var cachedDistortSrcBmp: android.graphics.Bitmap? = null
private var cachedDistortBounds: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
private var cachedDistortCropBmp: android.graphics.Bitmap? = null

private const val DISTORT_LINE_SAMPLES = 21
private val distortGridBorderPath = Path()
private val distortGridInnerPath = Path()

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
                if (tool != Tool.TRANSFORM || !tfState.active || tfState.mode != TransformMode.DISTORT || previewBmp == null) {
                    cachedDistortCropBmp = null
                    cachedDistortSrcBmp = null
                    cachedDistortBounds = androidx.compose.ui.geometry.Rect.Zero
                }
                if (tool == Tool.TRANSFORM && tfState.active && previewBmp != null) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    if (tool == Tool.TRANSFORM && tfState.mode == TransformMode.DISTORT) {
                        // Moving Least Squares (MLS) 2D Rigid Warp continuous GPU mesh
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val aBmp = previewBmp.asAndroidBitmap()
                        val b = tfState.bounds
                        val p = previewBitmapPaint

                        if (b.width > 0f && b.height > 0f && aBmp.width > 0 && aBmp.height > 0) {
                            if (cachedDistortSrcBmp !== aBmp || cachedDistortBounds != b || cachedDistortCropBmp == null || cachedDistortCropBmp?.isRecycled == true) {
                                val cropLeft = b.left.toInt().coerceIn(0, (aBmp.width - 1).coerceAtLeast(0))
                                val cropTop = b.top.toInt().coerceIn(0, (aBmp.height - 1).coerceAtLeast(0))
                                val maxW = aBmp.width - cropLeft
                                val maxH = aBmp.height - cropTop
                                val cropW = kotlin.math.round(b.width).toInt().coerceIn(1, maxW)
                                val cropH = kotlin.math.round(b.height).toInt().coerceIn(1, maxH)
                                cachedDistortCropBmp = android.graphics.Bitmap.createBitmap(aBmp, cropLeft, cropTop, cropW, cropH)
                                cachedDistortSrcBmp = aBmp
                                cachedDistortBounds = b
                            }
                            val cropBmp = cachedDistortCropBmp
                            if (cropBmp != null && !cropBmp.isRecycled) {
                                val halfBmpW = bmp.width / 2f
                                val halfBmpH = bmp.height / 2f
                                val origPts = tfState.origMeshPoints
                                val transfPts = tfState.meshPoints
                                val cropLeft = cachedDistortBounds.left
                                val cropTop = cachedDistortBounds.top
                                val cropW = cachedDistortBounds.width
                                val cropH = cachedDistortBounds.height

                                var vertIdx = 0
                                for (v in 0..DISTORT_MESH_SUBDIV) {
                                    val vFrac = v.toFloat() / DISTORT_MESH_SUBDIV
                                    val srcY = cropTop + vFrac * cropH
                                    for (u in 0..DISTORT_MESH_SUBDIV) {
                                        val uFrac = u.toFloat() / DISTORT_MESH_SUBDIV
                                        val srcX = cropLeft + uFrac * cropW
                                        val pt = MlsWarp.evaluate(srcX, srcY, origPts, transfPts, alpha = 1.0f, weights = distortMlsWeights)
                                        distortMeshVerts[vertIdx++] = pt.x * scX - halfBmpW
                                        distortMeshVerts[vertIdx++] = pt.y * scY - halfBmpH
                                    }
                                }
                                nativeCanvas.drawBitmapMesh(
                                    cropBmp,
                                    DISTORT_MESH_SUBDIV,
                                    DISTORT_MESH_SUBDIV,
                                    distortMeshVerts,
                                    0,
                                    null,
                                    0,
                                    p,
                                )
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
                            // 整张预览位图都要画: 预览位图 = 画布尺寸、里面只有"这次要被
                            // 变换的内容"(有选区时引擎只抠了选区内的像素)。以前只画变换框
                            // (tfState.bounds) 那一块, 而框是 contentBounds() = 目标层像素
                            // 包围盒 ∩ 选区 —— 内容没铺满选区时框比内容小, 框外的已抠内容
                            // 在预览里根本不显示, 但要按 Done 后它们会照着变换结果出现:
                            // 用户看到的就是"选区的东西凭空漂移"(翻转时最明显, 因为内容
                            // 会翻到框的另一侧)。整图绘制后预览与引擎提交逐像素一致。
                            val srcOffset = androidx.compose.ui.unit.IntOffset(0, 0)
                            val srcSize = androidx.compose.ui.unit.IntSize(
                                previewBmp.width.coerceAtLeast(1),
                                previewBmp.height.coerceAtLeast(1),
                            )
                            val dstOffset = androidx.compose.ui.unit.IntOffset(
                                (-bmp.width / 2f).toInt(),
                                (-bmp.height / 2f).toInt(),
                            )
                            val dstSize = androidx.compose.ui.unit.IntSize(
                                (previewBmp.width * scX).toInt().coerceAtLeast(1),
                                (previewBmp.height * scY).toInt().coerceAtLeast(1),
                            )
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
                        // 3x3 Mesh Grid with continuous MLS curved lines + 16 Control Handles
                        if (handles.size == 16 && tfState.origMeshPoints.size == 16) {
                            distortGridBorderPath.reset()
                            distortGridInnerPath.reset()
                            val b = tfState.bounds
                            val origPts = tfState.origMeshPoints
                            val transfPts = tfState.meshPoints

                            // 1. Horizontal grid lines (rows 0..3)
                            for (r in 0..3) {
                                val targetPath = if (r == 0 || r == 3) distortGridBorderPath else distortGridInnerPath
                                val py = b.top + b.height * (r / 3f)
                                for (s in 0..DISTORT_LINE_SAMPLES) {
                                    val px = b.left + b.width * (s.toFloat() / DISTORT_LINE_SAMPLES)
                                    val pt = MlsWarp.evaluate(px, py, origPts, transfPts, alpha = 1.0f, weights = distortMlsWeights)
                                    val screenPt = bx(pt)
                                    if (s == 0) {
                                        targetPath.moveTo(screenPt.x, screenPt.y)
                                    } else {
                                        targetPath.lineTo(screenPt.x, screenPt.y)
                                    }
                                }
                            }

                            // 2. Vertical grid lines (cols 0..3)
                            for (c in 0..3) {
                                val targetPath = if (c == 0 || c == 3) distortGridBorderPath else distortGridInnerPath
                                val px = b.left + b.width * (c / 3f)
                                for (s in 0..DISTORT_LINE_SAMPLES) {
                                    val py = b.top + b.height * (s.toFloat() / DISTORT_LINE_SAMPLES)
                                    val pt = MlsWarp.evaluate(px, py, origPts, transfPts, alpha = 1.0f, weights = distortMlsWeights)
                                    val screenPt = bx(pt)
                                    if (s == 0) {
                                        targetPath.moveTo(screenPt.x, screenPt.y)
                                    } else {
                                        targetPath.lineTo(screenPt.x, screenPt.y)
                                    }
                                }
                            }

                            // Draw inner grid lines
                            drawPath(
                                distortGridInnerPath,
                                color = Color(0x66181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale),
                            )
                            drawPath(
                                distortGridInnerPath,
                                color = Color(0x88AAB3C2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx() / currentScale),
                            )

                            // Draw border grid lines
                            drawPath(
                                distortGridBorderPath,
                                color = Color(0xFF181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() / currentScale),
                            )
                            drawPath(
                                distortGridBorderPath,
                                color = Morandi.accent,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale),
                            )

                            // 3. Draw 16 Control Handles
                            handles.forEachIndexed { idx, h ->
                                val isCorner = (idx == 0 || idx == 3 || idx == 12 || idx == 15)
                                val hr = (if (isCorner) 9.dp.toPx() else 6.5.dp.toPx()) / currentScale
                                drawCircle(Color(0xFF22262E), radius = hr, center = h)
                                drawCircle(
                                    if (isCorner) Morandi.accent else Color(0xFFAAB3C2),
                                    radius = hr,
                                    center = h,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale),
                                )
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
