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
import com.reverie.paint.model.TransformPreviewGeometry
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
                                // 裁剪框在文档空间, 直接按文档→预览位图比例换算, 不受显示缓冲降采样影响
                                val crop = TransformPreviewGeometry.docBoundsToPreviewCrop(
                                    left = b.left,
                                    top = b.top,
                                    right = b.right,
                                    bottom = b.bottom,
                                    docWidth = vm.docWidth,
                                    docHeight = vm.docHeight,
                                    previewWidth = aBmp.width,
                                    previewHeight = aBmp.height,
                                )
                                cachedDistortCropBmp = android.graphics.Bitmap.createBitmap(
                                    aBmp,
                                    crop.left,
                                    crop.top,
                                    crop.width,
                                    crop.height,
                                )
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
                            // srcRect 是"从预览位图里取哪块" ⇒ 文档→预览位图比例换算;
                            // 下面的 dstRect / 四边形顶点都是画布空间, 不乘。
                            val crop = TransformPreviewGeometry.docBoundsToPreviewCrop(
                                left = b.left,
                                top = b.top,
                                right = b.right,
                                bottom = b.bottom,
                                docWidth = vm.docWidth,
                                docHeight = vm.docHeight,
                                previewWidth = aBmp.width,
                                previewHeight = aBmp.height,
                            )
                            srcRect.set(crop.left, crop.top, crop.right, crop.bottom)
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
                        withTransform({
                            translate((c.x + tfState.tx) * scX - bmp.width / 2f, (c.y + tfState.ty) * scY - bmp.height / 2f)
                            rotate(tfState.rotation, pivot = Offset.Zero)
                            scale(tfState.scaleX, tfState.scaleY, pivot = Offset.Zero)
                            translate(-c.x * scX, -c.y * scY)
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
                            val dstOffset = androidx.compose.ui.unit.IntOffset(0, 0)
                            // 预览位图可能小于画布 (TransformPreviewPolicy) ⇒ 目标尺寸按**显示缓冲空间**
                            val (dstW, dstH) = TransformPreviewGeometry.affineDestinationSize(bmp.width, bmp.height)
                            val dstSize = androidx.compose.ui.unit.IntSize(
                                dstW,
                                dstH,
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
                                color = Color(0x38181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx() / currentScale),
                            )
                            drawPath(
                                distortGridInnerPath,
                                color = Color(0x66AAB3C2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.9.dp.toPx() / currentScale),
                            )

                            // Draw border grid lines
                            drawPath(
                                distortGridBorderPath,
                                color = Color(0x4D181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale),
                            )
                            drawPath(
                                distortGridBorderPath,
                                color = Morandi.accent.copy(alpha = 0.78f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1.dp.toPx() / currentScale),
                            )

                            // 3. Draw 16 Control Handles
                            handles.forEachIndexed { idx, h ->
                                val isCorner = (idx == 0 || idx == 3 || idx == 12 || idx == 15)
                                val isActive = (tfState.handle == idx)
                                val hr = (if (isCorner) 7.5.dp.toPx() else 5.5.dp.toPx()) / currentScale
                                drawCircle(Color(if (isActive) 0x80D8DEE8 else 0x55C8CFD8), radius = hr, center = h)
                                drawCircle(
                                    Color(0x44181B22),
                                    radius = hr,
                                    center = h,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx() / currentScale),
                                )
                                drawCircle(
                                    if (isCorner || isActive) Morandi.accent.copy(alpha = 0.85f) else Color(0xAAAAB3C2),
                                    radius = hr,
                                    center = h,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1.dp.toPx() / currentScale),
                                )
                                drawCircle(Color(0xDCE8ECF2), radius = (if (isCorner) 2.5.dp.toPx() else 1.8.dp.toPx()) / currentScale, center = h)
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
                                color = Color(0x4D181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale),
                            )
                            drawPath(
                                quadPath,
                                color = Morandi.accent.copy(alpha = 0.78f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1.dp.toPx() / currentScale),
                            )
                            val handleRadius = 8.dp.toPx() / currentScale
                            handles.forEachIndexed { idx, h ->
                                val isActive = (tfState.handle == idx)
                                drawCircle(Color(if (isActive) 0x80D8DEE8 else 0x55C8CFD8), radius = handleRadius, center = h)
                                drawCircle(Color(0x44181B22), radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx() / currentScale))
                                drawCircle(Morandi.accent.copy(alpha = if (isActive) 0.95f else 0.85f), radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1.dp.toPx() / currentScale))
                                drawCircle(Color(0xDCE8ECF2), radius = 2.5.dp.toPx() / currentScale, center = h)
                            }
                        }
                    } else {
                        // Standard / Free 4-Corner Bounding Box (edges are directly draggable)
                        val frame = androidx.compose.ui.graphics.Path()
                        if (handles.size >= 4) {
                            frame.moveTo(handles[0].x, handles[0].y)
                            for (i in 1..3) {
                                frame.lineTo(handles[i].x, handles[i].y)
                            }
                            frame.close()

                            // 1. Lightweight semi-transparent dual-layer bounding frame
                            drawPath(
                                frame,
                                color = Color(0x4D181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale),
                            )
                            drawPath(
                                frame,
                                color = Color(0xB8D8DEE8),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx() / currentScale),
                            )

                            // Highlight active edge when dragging an edge (handles 4..7)
                            if (tfState.handle in 4..7) {
                                val edgeStart = handles[tfState.handle - 4]
                                val edgeEnd = handles[(tfState.handle - 3) % 4]
                                drawLine(
                                    color = Morandi.accent.copy(alpha = 0.9f),
                                    start = edgeStart,
                                    end = edgeEnd,
                                    strokeWidth = 2.dp.toPx() / currentScale,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }

                            // 2. Center Pivot Indicator
                            val centerDoc = tfState.bounds.center + Offset(tfState.tx, tfState.ty)
                            val centerBmp = bx(centerDoc)
                            val cr = 4.5.dp.toPx() / currentScale
                            drawLine(Color(0x4D181B22), centerBmp - Offset(cr, 0f), centerBmp + Offset(cr, 0f), strokeWidth = 2.dp.toPx() / currentScale)
                            drawLine(Color(0x4D181B22), centerBmp - Offset(0f, cr), centerBmp + Offset(0f, cr), strokeWidth = 2.dp.toPx() / currentScale)
                            drawLine(Morandi.accent.copy(alpha = 0.75f), centerBmp - Offset(cr, 0f), centerBmp + Offset(cr, 0f), strokeWidth = 1.2.dp.toPx() / currentScale)
                            drawLine(Morandi.accent.copy(alpha = 0.75f), centerBmp - Offset(0f, cr), centerBmp + Offset(0f, cr), strokeWidth = 1.2.dp.toPx() / currentScale)

                            // 3. 4 Translucent Corner Handle Badges
                            val handleRadius = 8.dp.toPx() / currentScale
                            val badgeStrokeW = 1.1.dp.toPx() / currentScale
                            val outerContrastW = 1.6.dp.toPx() / currentScale
                            val glyphSize = handleRadius * 0.52f
                            val glyphColor = Color(0xDCE8ECF2)
                            val glyphShadowColor = Color(0x38181B22)
                            val glyphStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.3.dp.toPx() / currentScale,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                            )
                            val glyphShadowStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 2.1.dp.toPx() / currentScale,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                            )

                            for (i in 0..3) {
                                val h = handles[i]
                                val isActive = (tfState.handle == i)
                                drawCircle(Color(if (isActive) 0x80D8DEE8 else 0x55C8CFD8), radius = handleRadius, center = h)
                                drawCircle(Color(0x44181B22), radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = outerContrastW))
                                drawCircle(
                                    if (i == 1 || i == 3 || isActive) Morandi.accent.copy(alpha = if (isActive) 0.95f else 0.85f) else Color(0xBBE2E6EE),
                                    radius = handleRadius,
                                    center = h,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = badgeStrokeW),
                                )
                                when (i) {
                                    0, 2 -> {
                                        val ah = glyphSize * 0.45f
                                        val p1 = h - Offset(glyphSize, glyphSize)
                                        val p2 = h + Offset(glyphSize, glyphSize)
                                        drawLine(glyphShadowColor, p1, p2, strokeWidth = glyphShadowStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, p1, p2, strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, p1, h - Offset(glyphSize - ah, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, p1, h - Offset(glyphSize, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, p2, h + Offset(glyphSize - ah, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, p2, h + Offset(glyphSize, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    1, 3 -> {
                                        val arcRect = androidx.compose.ui.geometry.Rect(h - Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize))
                                        val accentGlyph = Morandi.accent.copy(alpha = 0.9f)
                                        drawArc(
                                            color = glyphShadowColor,
                                            startAngle = 40f,
                                            sweepAngle = 260f,
                                            useCenter = false,
                                            topLeft = arcRect.topLeft,
                                            size = arcRect.size,
                                            style = glyphShadowStroke,
                                        )
                                        drawArc(
                                            color = accentGlyph,
                                            startAngle = 40f,
                                            sweepAngle = 260f,
                                            useCenter = false,
                                            topLeft = arcRect.topLeft,
                                            size = arcRect.size,
                                            style = glyphStroke,
                                        )
                                        val rad = Math.toRadians(300.0)
                                        val tip = h + Offset((glyphSize * kotlin.math.cos(rad)).toFloat(), (glyphSize * kotlin.math.sin(rad)).toFloat())
                                        drawLine(accentGlyph, tip, tip + Offset(-glyphSize * 0.35f, -glyphSize * 0.2f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(accentGlyph, tip, tip + Offset(-glyphSize * 0.15f, glyphSize * 0.35f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                }
                            }
                        }
                    }
                }
}
