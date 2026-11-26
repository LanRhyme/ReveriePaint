/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ShapeState
import com.reverie.paint.core.TypographyEngine
import com.reverie.paint.core.brushOpacity
import com.reverie.paint.model.CanvasDecorGeometry
import com.reverie.paint.model.GradientHandleId
import com.reverie.paint.model.GradientRepeat
import com.reverie.paint.model.GradientType
import com.reverie.paint.model.LassoSubMode
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.ShapeFillMode
import com.reverie.paint.model.ShapeGeometry
import com.reverie.paint.model.ShapeHandleId
import com.reverie.paint.model.ShapeType
import com.reverie.paint.model.SnapGuideType
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal class MeasureOverlayCache {
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        isFakeBoldText = true
    }
    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(200, 20, 20, 20)
        style = android.graphics.Paint.Style.FILL
    }
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(120, 255, 255, 255)
        style = android.graphics.Paint.Style.STROKE
    }
    val badgeRect = android.graphics.RectF()
}

internal class SelectionOverlayCache {
    // 45 度斜向条纹纹理：周期 24px，暗条纹与微透亮条纹交错，完全无缝平铺
    // 暗阶 ~18% 透明度 (0x2E141416)，明阶 ~8% 透明度 (0x14141416)
    private val zebraTileBitmap: android.graphics.Bitmap = run {
        val size = 24
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val colDark = 0x2E141416u.toInt()
        val colLight = 0x14141416u.toInt()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val d = ((x - y) % size + size) % size
                pixels[y * size + x] = if (d < size / 2) colDark else colLight
            }
        }
        b.setPixels(pixels, 0, size, 0, 0, size, size)
        b
    }
    val zebraShader = android.graphics.BitmapShader(
        zebraTileBitmap,
        android.graphics.Shader.TileMode.REPEAT,
        android.graphics.Shader.TileMode.REPEAT,
    )
    val zebraPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = zebraShader
    }
    val zebraShaderMatrix = android.graphics.Matrix()
    val selMaskPaint = android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
    ).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    val clearPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    val selDstRectF = android.graphics.RectF()
    val selSrcRect = android.graphics.Rect()
    val inProgressClosedPath = Path()
    val inProgressRectF = android.graphics.RectF()
    // 逆选预览的差集路径 (android.graphics.Path.op 自 API 19 可用, 兼容 minSdk 23)
    val inverseClipPath = android.graphics.Path()
    val multiLassoPath = Path()
    val multiLassoClosedPath = Path()
}

internal fun DrawScope.drawMeasureOverlay(
    vm: PaintViewModel,
    bmpW: Int,
    bmpH: Int,
    start: Offset,
    end: Offset,
    currentScale: Float,
    flipX: Boolean,
    flipY: Boolean,
    cache: MeasureOverlayCache,
) {
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val p1 = Offset(start.x * scX - bmpW / 2f, start.y * scY - bmpH / 2f)
    val p2 = Offset(end.x * scX - bmpW / 2f, end.y * scY - bmpH / 2f)

    // 恒定屏幕物理像素: 除以当前缩放比例 scale，消除随画布放大变粗/过大问题
    val userStrokePx = vm.measureStrokeWidth.dp.toPx()
    val strokeW = userStrokePx / currentScale
    val circleR = maxOf(userStrokePx * 1.6f, 4.5.dp.toPx()) / currentScale
    val innerCircleR = maxOf(1f / currentScale, circleR * 0.45f)

    // 底部颜色反相渲染 (BlendMode.Difference + 白色 = 底部像素绝对反相)
    drawLine(Color.White, start = p1, end = p2, strokeWidth = strokeW, blendMode = BlendMode.Difference)
    drawCircle(Color.White, radius = circleR, center = p1, blendMode = BlendMode.Difference)
    drawCircle(Color.White, radius = circleR, center = p2, blendMode = BlendMode.Difference)
    drawCircle(Color.White, radius = innerCircleR, center = p1, blendMode = BlendMode.Difference)
    drawCircle(Color.White, radius = innerCircleR, center = p2, blendMode = BlendMode.Difference)

    val dist = hypot(end.x - start.x, end.y - start.y)
    val ang = Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
    val label = "%.0f px  %.1f°".format(dist, ang)

    withTransform({
        if (flipX || flipY) {
            scale(
                if (flipX) -1f else 1f,
                if (flipY) -1f else 1f,
                pivot = p2,
            )
        }
    }) {
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val textPaint = cache.textPaint
        textPaint.textSize = 13.dp.toPx() / currentScale
        val textW = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent

        val padX = 6.dp.toPx() / currentScale
        val padY = 3.dp.toPx() / currentScale
        val offsetX = 8.dp.toPx() / currentScale
        val offsetY = 8.dp.toPx() / currentScale

        val badgeLeft = p2.x + offsetX
        val badgeBottom = p2.y - offsetY
        val badgeTop = badgeBottom - textH - padY * 2
        val badgeRight = badgeLeft + textW + padX * 2
        val badgeRadius = 4.dp.toPx() / currentScale

        cache.strokePaint.strokeWidth = 1.dp.toPx() / currentScale
        cache.badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeBottom)
        nativeCanvas.drawRoundRect(cache.badgeRect, badgeRadius, badgeRadius, cache.bgPaint)
        nativeCanvas.drawRoundRect(cache.badgeRect, badgeRadius, badgeRadius, cache.strokePaint)

        val textY = badgeBottom - padY - fm.descent
        nativeCanvas.drawText(label, badgeLeft + padX, textY, textPaint)
    }
}

internal fun DrawScope.drawCropOverlay(
    vm: PaintViewModel,
    bmpW: Int,
    bmpH: Int,
    cr: Rect,
) {
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val bx = { v: Float -> v * scX - bmpW / 2f }
    val by2 = { v: Float -> v * scY - bmpH / 2f }
    val hole = Rect(bx(cr.left), by2(cr.top), bx(cr.right), by2(cr.bottom))
    drawContext.canvas.saveLayer(
        Rect(0f, 0f, size.width, size.height),
        Paint(),
    )
    drawRect(color = Color.Black.copy(alpha = 0.4f))
    drawRect(color = Color.White, topLeft = hole.topLeft, size = hole.size, blendMode = BlendMode.Clear)
    drawContext.canvas.restore()
    drawRect(
        color = Color.White,
        topLeft = hole.topLeft,
        size = hole.size,
        style = Stroke(width = 2.dp.toPx()),
    )
}

internal fun DrawScope.drawPolygonToolOverlay(
    vm: PaintViewModel,
    tool: Tool,
    bmpW: Int,
    bmpH: Int,
    polyPoints: List<Offset>,
    currentScale: Float,
    animFraction: Float,
) {
    if (polyPoints.isEmpty()) return
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val mappedPts = polyPoints.map { Offset(it.x * scX - bmpW / 2f, it.y * scY - bmpH / 2f) }
    val polyPath = Path()
    polyPath.moveTo(mappedPts[0].x, mappedPts[0].y)
    for (i in 1 until mappedPts.size) {
        polyPath.lineTo(mappedPts[i].x, mappedPts[i].y)
    }

    if (tool == Tool.SELECT_POLYGON) {
        val strokeW = 1.0.dp.toPx() / currentScale
        val blackStrokeW = 1.5.dp.toPx() / currentScale
        val dashInterval = 3.5.dp.toPx() / currentScale
        val antPhase = animFraction * (dashInterval * 2)
        val drawPathTarget = if (mappedPts.size >= 3) {
            Path().apply {
                addPath(polyPath)
                close()
            }
        } else {
            polyPath
        }
        val blackAlpha = if (mappedPts.size >= 3) 0.85f else 0.75f
        drawPath(
            path = drawPathTarget,
            color = Color.Black.copy(alpha = blackAlpha),
            style = Stroke(
                width = blackStrokeW,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashInterval, dashInterval), phase = antPhase),
            ),
        )
        drawPath(
            path = drawPathTarget,
            color = Color.White,
            style = Stroke(
                width = strokeW,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dashInterval, dashInterval),
                    phase = antPhase + dashInterval,
                ),
            ),
        )

        mappedPts.forEachIndexed { idx, pt ->
            if (idx == 0) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = 8.5.dp.toPx() / currentScale,
                    center = pt,
                    style = Stroke(width = 2.8.dp.toPx() / currentScale),
                )
                drawCircle(
                    color = Morandi.accent,
                    radius = 8.dp.toPx() / currentScale,
                    center = pt,
                    style = Stroke(width = 2.dp.toPx() / currentScale),
                )
                drawCircle(color = Color.White, radius = 3.5.dp.toPx() / currentScale, center = pt)
            } else {
                drawCircle(color = Color.Black.copy(alpha = 0.65f), radius = 4.dp.toPx() / currentScale, center = pt)
                drawCircle(color = Color.White, radius = 2.5.dp.toPx() / currentScale, center = pt)
            }
        }
    } else {
        if (tool == Tool.POLYGON && mappedPts.size >= 3) {
            polyPath.close()
        }
        drawPath(
            polyPath,
            color = Color.White,
            style = Stroke(width = 2.dp.toPx() / currentScale),
        )
        mappedPts.forEach { pt ->
            drawCircle(color = Morandi.accent, radius = 5.dp.toPx() / currentScale, center = pt)
            drawCircle(color = Color.White, radius = 3.dp.toPx() / currentScale, center = pt)
        }
    }
}

internal fun DrawScope.drawLiveShapePreview(
    vm: PaintViewModel,
    tool: Tool,
    bmpW: Int,
    bmpH: Int,
    start: Offset,
    end: Offset,
    currentScale: Float,
    animFraction: Float,
) {
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val s = Offset(start.x * scX - bmpW / 2f, start.y * scY - bmpH / 2f)
    val e = Offset(end.x * scX - bmpW / 2f, end.y * scY - bmpH / 2f)
    val strokeStyle = Stroke(width = 2.dp.toPx() / currentScale)

    when (tool) {
        Tool.LINE -> drawLine(Color.White, s, e, strokeWidth = 2.dp.toPx() / currentScale)
        Tool.RECT -> {
            val r = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = strokeStyle)
        }
        Tool.ELLIPSE -> {
            val r = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            drawOval(Color.White, topLeft = r.topLeft, size = r.size, style = strokeStyle)
        }
        Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
            val r = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            val dashInterval = 3.5.dp.toPx() / currentScale
            val antPhase = animFraction * (dashInterval * 2)
            val blackStyle = Stroke(
                width = 1.4.dp.toPx() / currentScale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashInterval, dashInterval), phase = antPhase),
            )
            val whiteStyle = Stroke(
                width = 1.0.dp.toPx() / currentScale,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dashInterval, dashInterval),
                    phase = antPhase + dashInterval,
                ),
            )
            if (tool == Tool.SELECT_RECT) {
                drawRect(Color.Black.copy(alpha = 0.85f), topLeft = r.topLeft, size = r.size, style = blackStyle)
                drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = whiteStyle)
            } else {
                drawOval(Color.Black.copy(alpha = 0.85f), topLeft = r.topLeft, size = r.size, style = blackStyle)
                drawOval(Color.White, topLeft = r.topLeft, size = r.size, style = whiteStyle)
            }
        }
        Tool.GRADIENT -> Unit
        else -> Unit
    }
}

private fun DrawScope.drawOverlayHandle(center: Offset, isSelected: Boolean, currentScale: Float) {
    drawCircle(color = Color.Black.copy(alpha = 0.7f), radius = 7.dp.toPx() / currentScale, center = center)
    drawCircle(
        color = if (isSelected) Morandi.accent else Color.White,
        radius = 5.dp.toPx() / currentScale,
        center = center,
    )
    drawCircle(
        color = if (isSelected) Color.White else Morandi.accent,
        radius = 2.5.dp.toPx() / currentScale,
        center = center,
    )
}

internal fun DrawScope.drawInteractiveShapeOverlay(
    vm: PaintViewModel,
    bmpW: Int,
    bmpH: Int,
    currentScale: Float,
) {
    val shapeState = vm.shapeState
    if (!shapeState.active) return
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val bx = { p: Offset -> Offset(p.x * scX - bmpW / 2f, p.y * scY - bmpH / 2f) }
    val strokeW = maxOf(1f, shapeState.strokeWidth * scX)
    val shapeColor = try {
        parseColor(vm.brushColor).copy(alpha = vm.brushOpacity.toFloat())
    } catch (_: Exception) {
        Color.White
    }

    val isBoxShape = shapeState.type == ShapeType.RECT ||
        shapeState.type == ShapeType.ROUNDED_RECT ||
        shapeState.type == ShapeType.ELLIPSE
    val p1Canvas = bx(shapeState.p1)
    val p2Raw = if (shapeState.keepAspect && isBoxShape) {
        val pt = ShapeGeometry.constrainAspect(
            Point2D(shapeState.p1.x, shapeState.p1.y),
            Point2D(shapeState.p2.x, shapeState.p2.y),
        )
        Offset(pt.x, pt.y)
    } else {
        shapeState.p2
    }
    val p2Canvas = bx(p2Raw)
    val shapePath = Path()

    when (shapeState.type) {
        ShapeType.LINE -> {
            shapePath.moveTo(p1Canvas.x, p1Canvas.y)
            shapePath.lineTo(p2Canvas.x, p2Canvas.y)
        }
        ShapeType.RECT -> {
            shapePath.addRect(
                Rect(
                    minOf(p1Canvas.x, p2Canvas.x),
                    minOf(p1Canvas.y, p2Canvas.y),
                    maxOf(p1Canvas.x, p2Canvas.x),
                    maxOf(p1Canvas.y, p2Canvas.y),
                ),
            )
        }
        ShapeType.ROUNDED_RECT -> {
            val r = Rect(
                minOf(p1Canvas.x, p2Canvas.x),
                minOf(p1Canvas.y, p2Canvas.y),
                maxOf(p1Canvas.x, p2Canvas.x),
                maxOf(p1Canvas.y, p2Canvas.y),
            )
            val cr = (shapeState.cornerRadius * scX).coerceIn(0f, minOf(r.width, r.height) / 2f)
            shapePath.addRoundRect(RoundRect(rect = r, cornerRadius = CornerRadius(cr, cr)))
        }
        ShapeType.ELLIPSE -> {
            shapePath.addOval(
                Rect(
                    minOf(p1Canvas.x, p2Canvas.x),
                    minOf(p1Canvas.y, p2Canvas.y),
                    maxOf(p1Canvas.x, p2Canvas.x),
                    maxOf(p1Canvas.y, p2Canvas.y),
                ),
            )
        }
        ShapeType.REGULAR_POLYGON -> {
            val center = (shapeState.p1 + shapeState.p2) / 2f
            val radius = hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f
            if (radius > 1f) {
                val pts = ShapeGeometry.generateRegularPolygon(
                    Point2D(center.x, center.y),
                    radius,
                    shapeState.polygonSides,
                    shapeState.rotationDegrees,
                ).map { bx(Offset(it.x, it.y)) }
                shapePath.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) shapePath.lineTo(pts[i].x, pts[i].y)
                shapePath.close()
            }
        }
        ShapeType.STAR -> {
            val center = (shapeState.p1 + shapeState.p2) / 2f
            val outerR = hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f
            val innerR = outerR * shapeState.starInnerRatio
            if (outerR > 1f) {
                val pts = ShapeGeometry.generateStar(
                    Point2D(center.x, center.y),
                    outerR,
                    innerR,
                    shapeState.starPoints,
                    shapeState.rotationDegrees,
                ).map { bx(Offset(it.x, it.y)) }
                shapePath.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) shapePath.lineTo(pts[i].x, pts[i].y)
                shapePath.close()
            }
        }
        ShapeType.POLYLINE, ShapeType.POLYGON -> {
            if (shapeState.nodes.isNotEmpty()) {
                val first = bx(Offset(shapeState.nodes[0].pos.x, shapeState.nodes[0].pos.y))
                shapePath.moveTo(first.x, first.y)
                for (i in 1 until shapeState.nodes.size) {
                    val pt = bx(Offset(shapeState.nodes[i].pos.x, shapeState.nodes[i].pos.y))
                    shapePath.lineTo(pt.x, pt.y)
                }
                if (shapeState.type == ShapeType.POLYGON || shapeState.closed) shapePath.close()
            }
        }
        ShapeType.BEZIER -> {
            if (shapeState.nodes.isNotEmpty()) {
                val first = bx(Offset(shapeState.nodes[0].pos.x, shapeState.nodes[0].pos.y))
                shapePath.moveTo(first.x, first.y)
                for (i in 1 until shapeState.nodes.size) {
                    val prev = shapeState.nodes[i - 1]
                    val curr = shapeState.nodes[i]
                    val cpOut = bx(Offset(prev.cpOut.x, prev.cpOut.y))
                    val cpIn = bx(Offset(curr.cpIn.x, curr.cpIn.y))
                    val pos = bx(Offset(curr.pos.x, curr.pos.y))
                    shapePath.cubicTo(cpOut.x, cpOut.y, cpIn.x, cpIn.y, pos.x, pos.y)
                }
                if (shapeState.closed && shapeState.nodes.size >= 3) {
                    val last = shapeState.nodes.last()
                    val firstNode = shapeState.nodes.first()
                    val cpOut = bx(Offset(last.cpOut.x, last.cpOut.y))
                    val cpIn = bx(Offset(firstNode.cpIn.x, firstNode.cpIn.y))
                    val pos = bx(Offset(firstNode.pos.x, firstNode.pos.y))
                    shapePath.cubicTo(cpOut.x, cpOut.y, cpIn.x, cpIn.y, pos.x, pos.y)
                    shapePath.close()
                }
            }
        }
    }

    val drawBoxContent = {
        if (shapeState.fillMode == ShapeFillMode.FILL || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL) {
            drawPath(shapePath, color = shapeColor, style = Fill)
        }
        if (shapeState.fillMode == ShapeFillMode.STROKE || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL) {
            drawPath(
                shapePath,
                color = shapeColor,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        val minX = minOf(p1Canvas.x, p2Canvas.x)
        val minY = minOf(p1Canvas.y, p2Canvas.y)
        val maxX = maxOf(p1Canvas.x, p2Canvas.x)
        val maxY = maxOf(p1Canvas.y, p2Canvas.y)
        val tl = Offset(minX, minY)
        val tr = Offset(maxX, minY)
        val br = Offset(maxX, maxY)
        val bl = Offset(minX, maxY)
        val center = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)

        val dashStyle = Stroke(
            width = 1.dp.toPx() / currentScale,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx() / currentScale, 4.dp.toPx() / currentScale)),
        )
        drawRect(Color.White.copy(alpha = 0.6f), topLeft = tl, size = Size(maxX - minX, maxY - minY), style = dashStyle)

        val rotStemY = tl.y - 28.dp.toPx() / currentScale
        val rotPos = Offset(center.x, rotStemY)
        drawLine(Color.White.copy(alpha = 0.8f), Offset(center.x, tl.y), rotPos, strokeWidth = 1.dp.toPx() / currentScale)
        drawOverlayHandle(rotPos, shapeState.activeHandle == ShapeHandleId.ROTATE, currentScale)

        if (shapeState.type == ShapeType.ROUNDED_RECT) {
            val cr = (shapeState.cornerRadius * scX).coerceIn(0f, minOf(maxX - minX, maxY - minY) / 2f)
            val crPos = Offset(tl.x + cr, tl.y + cr)
            drawCircle(Color.White, radius = 4.dp.toPx() / currentScale, center = crPos)
            drawCircle(Morandi.accent, radius = 2.5.dp.toPx() / currentScale, center = crPos)
        }

        drawOverlayHandle(tl, shapeState.activeHandle == ShapeHandleId.CORNER_TL, currentScale)
        drawOverlayHandle(tr, shapeState.activeHandle == ShapeHandleId.CORNER_TR, currentScale)
        drawOverlayHandle(br, shapeState.activeHandle == ShapeHandleId.CORNER_BR, currentScale)
        drawOverlayHandle(bl, shapeState.activeHandle == ShapeHandleId.CORNER_BL, currentScale)
    }

    if (isBoxShape) {
        val boxCenter = (p1Canvas + p2Canvas) / 2f
        if (kotlin.math.abs(shapeState.rotationDegrees) > 0.01f) {
            withTransform({ rotate(shapeState.rotationDegrees, pivot = boxCenter) }) {
                drawBoxContent()
            }
        } else {
            drawBoxContent()
        }
    } else {
        if (shapeState.fillMode == ShapeFillMode.FILL || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL) {
            drawPath(shapePath, color = shapeColor, style = Fill)
        }
        if (shapeState.fillMode == ShapeFillMode.STROKE || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL ||
            shapeState.type == ShapeType.LINE || shapeState.type == ShapeType.POLYLINE
        ) {
            drawPath(
                shapePath,
                color = shapeColor,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawNonBoxShapeHandles(shapeState, p1Canvas, p2Canvas, scX, currentScale, bx)
    }
}

private fun DrawScope.drawNonBoxShapeHandles(
    shapeState: ShapeState,
    p1Canvas: Offset,
    p2Canvas: Offset,
    scX: Float,
    currentScale: Float,
    bx: (Offset) -> Offset,
) {
    when (shapeState.type) {
        ShapeType.LINE -> {
            drawOverlayHandle(p1Canvas, shapeState.activeHandle == ShapeHandleId.LINE_P1, currentScale)
            drawOverlayHandle(p2Canvas, shapeState.activeHandle == ShapeHandleId.LINE_P2, currentScale)
            drawOverlayHandle((p1Canvas + p2Canvas) / 2f, shapeState.activeHandle == ShapeHandleId.TRANSLATE_BODY, currentScale)
        }
        ShapeType.REGULAR_POLYGON -> {
            val center = bx((shapeState.p1 + shapeState.p2) / 2f)
            val radius = (hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f) * scX
            drawOverlayHandle(center, shapeState.activeHandle == ShapeHandleId.TRANSLATE_BODY, currentScale)
            val baseAngle = (-kotlin.math.PI / 2.0).toFloat() + Math.toRadians(shapeState.rotationDegrees.toDouble()).toFloat()
            val topH = Offset(center.x + radius * cos(baseAngle), center.y + radius * sin(baseAngle))
            drawOverlayHandle(topH, shapeState.activeHandle == ShapeHandleId.STAR_OUTER, currentScale)
        }
        ShapeType.STAR -> {
            val center = bx((shapeState.p1 + shapeState.p2) / 2f)
            val outerR = (hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f) * scX
            val innerR = outerR * shapeState.starInnerRatio
            drawOverlayHandle(center, shapeState.activeHandle == ShapeHandleId.TRANSLATE_BODY, currentScale)
            val baseAngle = (-kotlin.math.PI / 2.0).toFloat() + Math.toRadians(shapeState.rotationDegrees.toDouble()).toFloat()
            val outerH = Offset(center.x + outerR * cos(baseAngle), center.y + outerR * sin(baseAngle))
            drawOverlayHandle(outerH, shapeState.activeHandle == ShapeHandleId.STAR_OUTER, currentScale)
            val innerAngle = baseAngle + (kotlin.math.PI / shapeState.starPoints).toFloat()
            val innerH = Offset(center.x + innerR * cos(innerAngle), center.y + innerR * sin(innerAngle))
            drawOverlayHandle(innerH, shapeState.activeHandle == ShapeHandleId.STAR_INNER, currentScale)
        }
        ShapeType.POLYLINE, ShapeType.POLYGON -> {
            shapeState.nodes.forEachIndexed { idx, node ->
                drawOverlayHandle(bx(Offset(node.pos.x, node.pos.y)), idx == shapeState.selectedNodeIndex, currentScale)
            }
        }
        ShapeType.BEZIER -> {
            shapeState.nodes.forEachIndexed { idx, node ->
                val pt = bx(Offset(node.pos.x, node.pos.y))
                val isNodeSelected = idx == shapeState.selectedNodeIndex
                val hasCpIn = node.cpIn.x != node.pos.x || node.cpIn.y != node.pos.y
                val hasCpOut = node.cpOut.x != node.pos.x || node.cpOut.y != node.pos.y
                if (isNodeSelected || hasCpIn || hasCpOut) {
                    val cpInCanvas = bx(Offset(node.cpIn.x, node.cpIn.y))
                    val cpOutCanvas = bx(Offset(node.cpOut.x, node.cpOut.y))
                    val handleLineColor = Color.White.copy(alpha = 0.6f)
                    val handleLineWidth = 1.dp.toPx() / currentScale
                    if (hasCpIn || isNodeSelected) {
                        drawLine(handleLineColor, pt, cpInCanvas, strokeWidth = handleLineWidth)
                        drawCircle(Color.Black.copy(alpha = 0.4f), radius = 4.5.dp.toPx() / currentScale, center = cpInCanvas)
                        drawCircle(
                            color = if (shapeState.activeHandle == ShapeHandleId.NODE_CP_IN_BASE + idx) Color.White else Morandi.accent,
                            radius = 3.dp.toPx() / currentScale,
                            center = cpInCanvas,
                        )
                    }
                    if (hasCpOut || isNodeSelected) {
                        drawLine(handleLineColor, pt, cpOutCanvas, strokeWidth = handleLineWidth)
                        drawCircle(Color.Black.copy(alpha = 0.4f), radius = 4.5.dp.toPx() / currentScale, center = cpOutCanvas)
                        drawCircle(
                            color = if (shapeState.activeHandle == ShapeHandleId.NODE_CP_OUT_BASE + idx) Color.White else Morandi.accent,
                            radius = 3.dp.toPx() / currentScale,
                            center = cpOutCanvas,
                        )
                    }
                }
                drawOverlayHandle(pt, isNodeSelected, currentScale)
            }
        }
        else -> Unit
    }
}

internal fun DrawScope.drawInteractiveGradientOverlay(
    vm: PaintViewModel,
    bmpW: Int,
    bmpH: Int,
    currentScale: Float,
) {
    val gradientState = vm.gradientState
    if (!gradientState.active || gradientState.p1 == gradientState.p2) return
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val bx = { p: Offset -> Offset(p.x * scX - bmpW / 2f, p.y * scY - bmpH / 2f) }

    val fgColor = try { Color(android.graphics.Color.parseColor(vm.brushColor)) } catch (_: Exception) { Color.Black }
    val bgColor = try { Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)) } catch (_: Exception) { Color.White }

    var effectiveStops = gradientState.getEffectiveStops(fgColor, bgColor)
    if (gradientState.reverse) {
        effectiveStops = effectiveStops.map {
            com.reverie.paint.model.GradientColorStop(1f - it.position, it.colorArgb)
        }.sortedBy { it.position }
    }
    val colorStops = if (effectiveStops.size >= 2) {
        effectiveStops.map { it.position.coerceIn(0f, 1f) to Color(it.colorArgb) }.toTypedArray()
    } else {
        arrayOf(0f to Color.Black, 1f to Color.White)
    }
    val tileMode = when (gradientState.repeat) {
        GradientRepeat.LOOP -> TileMode.Repeated
        GradientRepeat.MIRROR -> TileMode.Mirror
        else -> TileMode.Clamp
    }
    val composeBlendMode = when (gradientState.blendMode) {
        "multiply" -> BlendMode.Multiply
        "screen" -> BlendMode.Screen
        "overlay" -> BlendMode.Overlay
        "soft_light" -> BlendMode.Softlight
        "hard_light" -> BlendMode.Hardlight
        "dodge" -> BlendMode.ColorDodge
        "burn" -> BlendMode.ColorBurn
        else -> BlendMode.SrcOver
    }

    val p1Canvas = bx(gradientState.p1)
    val p2Canvas = bx(gradientState.p2)
    val previewBrush = when (gradientState.type) {
        GradientType.RADIAL -> Brush.radialGradient(
            colorStops = colorStops,
            center = p1Canvas,
            radius = maxOf(1f, (p2Canvas - p1Canvas).getDistance()),
            tileMode = tileMode,
        )
        GradientType.CONICAL -> Brush.sweepGradient(colorStops = colorStops, center = p1Canvas)
        GradientType.SYMMETRIC -> {
            val dir = p2Canvas - p1Canvas
            val symStops = mutableListOf<Pair<Float, Color>>()
            effectiveStops.forEach { s ->
                val orig = s.position.coerceIn(0f, 1f)
                symStops.add(0.5f + orig * 0.5f to Color(s.colorArgb))
                symStops.add(0.5f - orig * 0.5f to Color(s.colorArgb))
            }
            symStops.sortBy { it.first }
            Brush.linearGradient(colorStops = symStops.toTypedArray(), start = p1Canvas - dir, end = p2Canvas, tileMode = tileMode)
        }
        GradientType.DIAMOND -> Brush.radialGradient(
            colorStops = colorStops,
            center = p1Canvas,
            radius = maxOf(1f, (p2Canvas - p1Canvas).getDistance()),
            tileMode = tileMode,
        )
        else -> Brush.linearGradient(colorStops = colorStops, start = p1Canvas, end = p2Canvas, tileMode = tileMode)
    }

    drawRect(
        brush = previewBrush,
        topLeft = Offset(-bmpW / 2f, -bmpH / 2f),
        size = Size(bmpW.toFloat(), bmpH.toFloat()),
        alpha = gradientState.opacity,
        blendMode = composeBlendMode,
    )
    drawLine(Color.Black.copy(alpha = 0.6f), p1Canvas, p2Canvas, strokeWidth = 3.5.dp.toPx() / currentScale)
    drawLine(Color.White, p1Canvas, p2Canvas, strokeWidth = 1.5.dp.toPx() / currentScale)

    val midPt = (p1Canvas + p2Canvas) / 2f
    drawOverlayHandle(midPt, gradientState.activeHandle == GradientHandleId.MID_BODY, currentScale)

    effectiveStops.forEachIndexed { idx, s ->
        val stopPt = p1Canvas + (p2Canvas - p1Canvas) * s.position
        val isSelected = gradientState.activeHandle == GradientHandleId.STOP_BASE + idx
        drawCircle(Color.Black.copy(alpha = 0.7f), radius = 6.dp.toPx() / currentScale, center = stopPt)
        drawCircle(if (isSelected) Morandi.accent else Color.White, radius = 4.5.dp.toPx() / currentScale, center = stopPt)
        drawCircle(Color(s.colorArgb), radius = 3.dp.toPx() / currentScale, center = stopPt)
    }

    val firstCol = effectiveStops.firstOrNull()?.colorArgb?.let { Color(it) } ?: Color.Black
    drawCircle(Color.Black.copy(alpha = 0.7f), radius = 9.dp.toPx() / currentScale, center = p1Canvas)
    drawCircle(if (gradientState.activeHandle == GradientHandleId.START) Morandi.accent else Color.White, radius = 7.dp.toPx() / currentScale, center = p1Canvas)
    drawCircle(firstCol, radius = 5.dp.toPx() / currentScale, center = p1Canvas)

    val lastCol = effectiveStops.lastOrNull()?.colorArgb?.let { Color(it) } ?: Color.White
    drawCircle(Color.Black.copy(alpha = 0.7f), radius = 9.dp.toPx() / currentScale, center = p2Canvas)
    drawCircle(if (gradientState.activeHandle == GradientHandleId.END) Morandi.accent else Color.White, radius = 7.dp.toPx() / currentScale, center = p2Canvas)
    drawCircle(lastCol, radius = 5.dp.toPx() / currentScale, center = p2Canvas)
}

internal fun DrawScope.drawInteractiveTypographyOverlay(
    vm: PaintViewModel,
    bmpW: Int,
    bmpH: Int,
    currentScale: Float,
    textEmptyPlaceholder: String,
) {
    if (!vm.isTypographyEditing) return
    val cfg = vm.typographyConfig
    val editLayerId = vm.editingTextLayerId
    val activeItemId = vm.editingTextItemId
    val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
    val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
    val bx = { p: Offset -> Offset(p.x * scX - bmpW / 2f, p.y * scY - bmpH / 2f) }

    if (editLayerId != null) {
        val siblingItems = vm.activeTextLayers[editLayerId] ?: emptyList()
        for (item in siblingItems) {
            if (item.id == activeItemId || item.text.isBlank()) continue
            val itemPaint = TypographyEngine.createTextPaint(item, vm.brushOpacity)
            val itemTargetW = TypographyEngine.resolveTargetWidth(item)
            val itemLayout = TypographyEngine.createLayout(item, itemPaint, itemTargetW)
            val itemTl = bx(Offset(item.posX, item.posY))
            val itemBr = bx(Offset(item.posX + itemLayout.width, item.posY + itemLayout.height))
            val itemBoxCenter = (itemTl + itemBr) / 2f
            withTransform({ rotate(item.rotationDeg, pivot = itemBoxCenter) }) {
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.translate(itemTl.x, itemTl.y)
                itemLayout.draw(drawContext.canvas.nativeCanvas)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
    }

    val isEmptyText = cfg.text.isEmpty()
    val displayCfg = if (isEmptyText) cfg.copy(text = textEmptyPlaceholder) else cfg
    val displayOpacity = if (isEmptyText) vm.brushOpacity * 0.45 else vm.brushOpacity
    val paint = TypographyEngine.createTextPaint(displayCfg, displayOpacity)
    val layout = TypographyEngine.createLayout(displayCfg, paint, TypographyEngine.resolveTargetWidth(displayCfg))
    val tl = bx(Offset(cfg.posX, cfg.posY))
    val br = bx(Offset(cfg.posX + layout.width.toFloat(), cfg.posY + layout.height.toFloat()))
    val boxCenter = (tl + br) / 2f

    val drawTextHandle = { center: Offset ->
        drawCircle(color = Color.Black.copy(alpha = 0.7f), radius = 7.dp.toPx() / currentScale, center = center)
        drawCircle(color = Morandi.accent, radius = 5.dp.toPx() / currentScale, center = center)
        drawCircle(color = Color.White, radius = 2.dp.toPx() / currentScale, center = center)
    }

    withTransform({ rotate(cfg.rotationDeg, pivot = boxCenter) }) {
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.translate(tl.x, tl.y)
        layout.draw(drawContext.canvas.nativeCanvas)
        drawContext.canvas.nativeCanvas.restore()

        drawRect(
            color = Morandi.accent.copy(alpha = 0.85f),
            topLeft = tl,
            size = Size(maxOf(1f, br.x - tl.x), maxOf(1f, br.y - tl.y)),
            style = Stroke(
                width = 1.2.dp.toPx() / currentScale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx() / currentScale, 4.dp.toPx() / currentScale)),
            ),
        )
        drawTextHandle(br)
        val rotPos = Offset(boxCenter.x, tl.y - 24.dp.toPx() / currentScale)
        drawLine(
            color = Morandi.accent.copy(alpha = 0.7f),
            start = Offset(boxCenter.x, tl.y),
            end = rotPos,
            strokeWidth = 1.dp.toPx() / currentScale,
        )
        drawTextHandle(rotPos)
    }

    if (vm.typographySnapGuides.isNotEmpty()) {
        for (guide in vm.typographySnapGuides) {
            val isCenter = guide.type == SnapGuideType.CENTER
            val color = if (isCenter) Morandi.accent else Morandi.accent.copy(alpha = 0.55f)
            val strokeWidth = if (isCenter) 1.5.dp.toPx() / currentScale else 1.0.dp.toPx() / currentScale
            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / currentScale, 4.dp.toPx() / currentScale))
            if (guide.isVertical) {
                val sx = guide.position * scX - bmpW / 2f
                drawLine(color = color, start = Offset(sx, -bmpH / 2f), end = Offset(sx, bmpH / 2f), strokeWidth = strokeWidth, pathEffect = pathEffect)
            } else {
                val sy = guide.position * scY - bmpH / 2f
                drawLine(color = color, start = Offset(-bmpW / 2f, sy), end = Offset(bmpW / 2f, sy), strokeWidth = strokeWidth, pathEffect = pathEffect)
            }
        }
    }
}

internal fun DrawScope.drawSelectionAndLassoOverlay(
    vm: PaintViewModel,
    tool: Tool,
    bmpW: Int,
    bmpH: Int,
    polyPoints: List<Offset>,
    liveShapeStart: State<Offset?>,
    liveShapeEnd: State<Offset?>,
    liveSelectionPath: State<Path?>,
    animFraction: Float,
    selectionFlashProgress: Animatable<Float, AnimationVector1D>,
    currentScale: Float,
    rotationDeg: Float,
    center: Offset,
    flipX: Boolean,
    flipY: Boolean,
    cache: SelectionOverlayCache,
) {
    val livePath = liveSelectionPath.value
    val isSelecting = livePath != null ||
        ((tool == Tool.SELECT_RECT || tool == Tool.SELECT_ELLIPSE) && liveShapeStart.value != null) ||
        (tool == Tool.LASSO && vm.lassoMultiPoints.isNotEmpty()) ||
        (tool == Tool.SELECT_POLYGON && polyPoints.isNotEmpty())
    val isTransform = (tool == Tool.TRANSFORM)
    val isSelectionTool = tool.group == ToolGroup.SELECTION
    val shouldShowZebra = isSelectionTool && !isTransform && !vm.transientTransformSelectionPending && (vm.hasSelection || isSelecting)

    val invScale = 1f / currentScale
    val rad = Math.toRadians(-rotationDeg.toDouble())
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()
    val fx = if (flipX) -1f else 1f
    val fy = if (flipY) -1f else 1f

    var minVisX = Float.POSITIVE_INFINITY
    var maxVisX = Float.NEGATIVE_INFINITY
    var minVisY = Float.POSITIVE_INFINITY
    var maxVisY = Float.NEGATIVE_INFINITY
    for (cornerIdx in 0 until 4) {
        val sx = if (cornerIdx == 1 || cornerIdx == 2) size.width else 0f
        val sy = if (cornerIdx == 2 || cornerIdx == 3) size.height else 0f
        val dx = sx - center.x
        val dy = sy - center.y
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA
        val cx = (rx * invScale) * fx
        val cy = (ry * invScale) * fy
        if (cx < minVisX) minVisX = cx
        if (cx > maxVisX) maxVisX = cx
        if (cy < minVisY) minVisY = cy
        if (cy > maxVisY) maxVisY = cy
    }
    val visPad = 32.dp.toPx() * invScale
    val docLeft = -bmpW / 2f
    val docTop = -bmpH / 2f
    val visLeft = maxOf(docLeft, minVisX - visPad)
    val visTop = maxOf(docTop, minVisY - visPad)
    val visRight = minOf(bmpW / 2f, maxVisX + visPad)
    val visBottom = minOf(bmpH / 2f, maxVisY + visPad)
    if (visLeft >= visRight || visTop >= visBottom) return

    if (shouldShowZebra) {
        drawSelectionZebraMask(
            vm = vm,
            tool = tool,
            bmpW = bmpW,
            bmpH = bmpH,
            polyPoints = polyPoints,
            liveShapeStart = liveShapeStart,
            liveShapeEnd = liveShapeEnd,
            livePath = livePath,
            isSelecting = isSelecting,
            animFraction = animFraction,
            currentScale = currentScale,
            rotationDeg = rotationDeg,
            docLeft = docLeft,
            docTop = docTop,
            visLeft = visLeft,
            visTop = visTop,
            visRight = visRight,
            visBottom = visBottom,
            cache = cache,
        )
    }

    drawSelectionOutlineAndLivePath(
        vm = vm,
        isTransform = isTransform,
        isSelecting = isSelecting,
        livePath = livePath,
        animFraction = animFraction,
        selectionFlashProgress = selectionFlashProgress,
        currentScale = currentScale,
        visLeft = visLeft,
        visTop = visTop,
        visRight = visRight,
        visBottom = visBottom,
    )

    val multiPts = vm.lassoMultiPoints
    if (multiPts.isNotEmpty() && livePath == null && tool == Tool.LASSO) {
        drawMultiLassoOverlay(
            vm = vm,
            bmpW = bmpW,
            bmpH = bmpH,
            multiPts = multiPts,
            animFraction = animFraction,
            currentScale = currentScale,
            visLeft = visLeft,
            visTop = visTop,
            visRight = visRight,
            visBottom = visBottom,
            cache = cache,
        )
    }
}

private fun DrawScope.drawSelectionZebraMask(
    vm: PaintViewModel,
    tool: Tool,
    bmpW: Int,
    bmpH: Int,
    polyPoints: List<Offset>,
    liveShapeStart: State<Offset?>,
    liveShapeEnd: State<Offset?>,
    livePath: Path?,
    isSelecting: Boolean,
    animFraction: Float,
    currentScale: Float,
    rotationDeg: Float,
    docLeft: Float,
    docTop: Float,
    visLeft: Float,
    visTop: Float,
    visRight: Float,
    visBottom: Float,
    cache: SelectionOverlayCache,
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val selBmp = if (vm.hasSelection) vm.selectionOverlayBitmap else null

    cache.zebraShaderMatrix.reset()
    val stripeTileScreenPx = 18.dp.toPx()
    val stripeScale = (stripeTileScreenPx / 24f) / currentScale
    cache.zebraShaderMatrix.setScale(stripeScale, stripeScale)
    cache.zebraShaderMatrix.postRotate(-rotationDeg)
    cache.zebraShaderMatrix.postTranslate((animFraction * stripeTileScreenPx) / currentScale, 0f)
    cache.zebraShader.setLocalMatrix(cache.zebraShaderMatrix)

    val opacityScale = (vm.selectionMaskOpacity / 0.47f).coerceIn(0.2f, 2.0f)
    cache.zebraPaint.alpha = (opacityScale * 255).toInt().coerceIn(20, 255)

    val saveCount = nativeCanvas.saveLayer(visLeft, visTop, visRight, visBottom, null)
    nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, cache.zebraPaint)

    if (vm.hasSelection && selBmp != null && !selBmp.isRecycled && (vm.selectionMode != 0 || !isSelecting)) {
        val bmLeft = ((visLeft - docLeft) / bmpW * selBmp.width).toInt().coerceIn(0, selBmp.width)
        val bmTop = ((visTop - docTop) / bmpH * selBmp.height).toInt().coerceIn(0, selBmp.height)
        val bmRight = ceil((visRight - docLeft) / bmpW * selBmp.width).toInt().coerceIn(bmLeft, selBmp.width)
        val bmBottom = ceil((visBottom - docTop) / bmpH * selBmp.height).toInt().coerceIn(bmTop, selBmp.height)
        if (bmRight > bmLeft && bmBottom > bmTop) {
            cache.selSrcRect.set(bmLeft, bmTop, bmRight, bmBottom)
            cache.selDstRectF.set(visLeft, visTop, visRight, visBottom)
            nativeCanvas.drawBitmap(selBmp, cache.selSrcRect, cache.selDstRectF, cache.selMaskPaint)
        }
    }

    if (isSelecting) {
        val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
        val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
        val halfW = bmpW / 2f
        val halfH = bmpH / 2f

        val applyClosedPathPreview = { path: Path ->
            when (vm.selectionMode) {
                0, 1 -> nativeCanvas.drawPath(path.asAndroidPath(), cache.clearPaint)
                2 -> nativeCanvas.drawPath(path.asAndroidPath(), cache.zebraPaint)
                3 -> drawZebraOutsidePath(
                    nativeCanvas, path.asAndroidPath(),
                    visLeft, visTop, visRight, visBottom, cache,
                )
            }
        }

        when {
            livePath != null -> {
                cache.inProgressClosedPath.reset()
                cache.inProgressClosedPath.addPath(livePath)
                cache.inProgressClosedPath.close()
                applyClosedPathPreview(cache.inProgressClosedPath)
            }
            tool == Tool.LASSO && vm.lassoMultiPoints.size >= 3 -> {
                val pts = vm.lassoMultiPoints
                cache.inProgressClosedPath.reset()
                cache.inProgressClosedPath.moveTo(pts[0].first * scX - halfW, pts[0].second * scY - halfH)
                for (i in 1 until pts.size) {
                    cache.inProgressClosedPath.lineTo(pts[i].first * scX - halfW, pts[i].second * scY - halfH)
                }
                cache.inProgressClosedPath.close()
                applyClosedPathPreview(cache.inProgressClosedPath)
            }
            tool == Tool.SELECT_POLYGON && polyPoints.size >= 3 -> {
                cache.inProgressClosedPath.reset()
                cache.inProgressClosedPath.moveTo(polyPoints[0].x * scX - halfW, polyPoints[0].y * scY - halfH)
                for (i in 1 until polyPoints.size) {
                    cache.inProgressClosedPath.lineTo(polyPoints[i].x * scX - halfW, polyPoints[i].y * scY - halfH)
                }
                cache.inProgressClosedPath.close()
                applyClosedPathPreview(cache.inProgressClosedPath)
            }
            tool == Tool.SELECT_RECT && liveShapeStart.value != null && liveShapeEnd.value != null -> {
                val s = liveShapeStart.value!!
                val e = liveShapeEnd.value!!
                cache.inProgressRectF.set(
                    minOf(s.x, e.x) * scX - halfW,
                    minOf(s.y, e.y) * scY - halfH,
                    maxOf(s.x, e.x) * scX - halfW,
                    maxOf(s.y, e.y) * scY - halfH,
                )
                when (vm.selectionMode) {
                    0, 1 -> nativeCanvas.drawRect(cache.inProgressRectF, cache.clearPaint)
                    2 -> nativeCanvas.drawRect(cache.inProgressRectF, cache.zebraPaint)
                    3 -> drawZebraOutsideRect(
                        nativeCanvas, cache.inProgressRectF,
                        visLeft, visTop, visRight, visBottom, cache,
                    )
                }
            }
            tool == Tool.SELECT_ELLIPSE && liveShapeStart.value != null && liveShapeEnd.value != null -> {
                val s = liveShapeStart.value!!
                val e = liveShapeEnd.value!!
                val rLeft = minOf(s.x, e.x) * scX - halfW
                val rTop = minOf(s.y, e.y) * scY - halfH
                val rRight = maxOf(s.x, e.x) * scX - halfW
                val rBottom = maxOf(s.y, e.y) * scY - halfH
                cache.inProgressRectF.set(rLeft, rTop, rRight, rBottom)
                when (vm.selectionMode) {
                    0, 1 -> nativeCanvas.drawOval(cache.inProgressRectF, cache.clearPaint)
                    2 -> nativeCanvas.drawOval(cache.inProgressRectF, cache.zebraPaint)
                    3 -> drawZebraOutsideOval(
                        nativeCanvas, rLeft, rTop, rRight, rBottom,
                        visLeft, visTop, visRight, visBottom, cache,
                    )
                }
            }
        }
    }
    nativeCanvas.restoreToCount(saveCount)
}

/**
 * 逆选预览: 用「视口矩形减去选区」的差集路径直接填充斑马纹。
 * Canvas.clipOutPath / clipOutRect 需要 API 26, 这里改用路径填充规则与 Path.op, 兼容 minSdk 23。
 */
private fun drawZebraOutsideRect(
    nativeCanvas: android.graphics.Canvas,
    holeRect: android.graphics.RectF,
    visLeft: Float,
    visTop: Float,
    visRight: Float,
    visBottom: Float,
    cache: SelectionOverlayCache,
) {
    val out = cache.inverseClipPath
    out.reset()
    out.addRect(visLeft, visTop, visRight, visBottom, android.graphics.Path.Direction.CW)
    // 反向绕行挖洞 (nonzero 填充), 无需求布尔运算
    out.addRect(holeRect, android.graphics.Path.Direction.CCW)
    nativeCanvas.drawPath(out, cache.zebraPaint)
}

/** 逆选预览: 椭圆选区的差集填充 */
private fun drawZebraOutsideOval(
    nativeCanvas: android.graphics.Canvas,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    visLeft: Float,
    visTop: Float,
    visRight: Float,
    visBottom: Float,
    cache: SelectionOverlayCache,
) {
    val out = cache.inverseClipPath
    out.reset()
    out.addRect(visLeft, visTop, visRight, visBottom, android.graphics.Path.Direction.CW)
    out.addOval(left, top, right, bottom, android.graphics.Path.Direction.CCW)
    nativeCanvas.drawPath(out, cache.zebraPaint)
}

/** 逆选预览: 任意多边形/套索选区的差集填充 (自交路径交给 Path.op 精确求解) */
private fun drawZebraOutsidePath(
    nativeCanvas: android.graphics.Canvas,
    hole: android.graphics.Path,
    visLeft: Float,
    visTop: Float,
    visRight: Float,
    visBottom: Float,
    cache: SelectionOverlayCache,
) {
    val out = cache.inverseClipPath
    out.reset()
    out.addRect(visLeft, visTop, visRight, visBottom, android.graphics.Path.Direction.CW)
    if (!out.op(hole, android.graphics.Path.Op.DIFFERENCE)) {
        // 极端自交路径差集失败时退回整幅斑马纹, 至少保留预览反馈
        nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, cache.zebraPaint)
        return
    }
    nativeCanvas.drawPath(out, cache.zebraPaint)
}

private fun DrawScope.drawSelectionOutlineAndLivePath(
    vm: PaintViewModel,
    isTransform: Boolean,
    isSelecting: Boolean,
    livePath: Path?,
    animFraction: Float,
    selectionFlashProgress: Animatable<Float, AnimationVector1D>,
    currentScale: Float,
    visLeft: Float,
    visTop: Float,
    visRight: Float,
    visBottom: Float,
) {
    val shouldShowOutline = !vm.transientTransformSelectionPending && CanvasDecorGeometry.selectionOutlineVisible(
        hasSelection = vm.hasSelection,
        isTransform = isTransform,
        isSelecting = isSelecting,
        selectionMode = vm.selectionMode,
    )
    if (shouldShowOutline) {
        vm.selectionOutlinePath?.let { outlinePath ->
            val pathBounds = outlinePath.getBounds()
            val isOutlineVisible = pathBounds.right >= visLeft && pathBounds.left <= visRight &&
                pathBounds.bottom >= visTop && pathBounds.top <= visBottom
            if (isOutlineVisible) {
                clipRect(visLeft, visTop, visRight, visBottom) {
                    with(CanvasDecorDraw) { drawSelectionAnts(outlinePath, currentScale, animFraction) }
                    val flashFrac = selectionFlashProgress.value
                    if (flashFrac < 1f) {
                        val flashAlpha = (1f - flashFrac).coerceIn(0f, 1f)
                        val glowWidth = (2.2.dp + 5.0.dp * flashFrac).toPx() / currentScale
                        val coreWidth = (1.5.dp + 0.8.dp * (1f - flashFrac)).toPx() / currentScale
                        drawPath(
                            path = outlinePath,
                            color = Morandi.accent.copy(alpha = 0.55f * flashAlpha),
                            style = Stroke(width = glowWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        drawPath(
                            path = outlinePath,
                            color = Color.White.copy(alpha = 0.90f * flashAlpha),
                            style = Stroke(width = coreWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }

    if (livePath != null) {
        clipRect(visLeft, visTop, visRight, visBottom) {
            val strokeW = 1.0.dp.toPx() / currentScale
            val blackStrokeW = 1.4.dp.toPx() / currentScale
            val dashInterval = 3.5.dp.toPx() / currentScale
            val antPhase = animFraction * (dashInterval * 2)
            drawPath(
                path = livePath,
                color = Color.Black.copy(alpha = 0.85f),
                style = Stroke(
                    width = blackStrokeW,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashInterval, dashInterval), phase = antPhase),
                ),
            )
            drawPath(
                path = livePath,
                color = Color.White,
                style = Stroke(
                    width = strokeW,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dashInterval, dashInterval),
                        phase = antPhase + dashInterval,
                    ),
                ),
            )
        }
    }
}

private fun DrawScope.drawMultiLassoOverlay(
    vm: PaintViewModel,
    bmpW: Int,
    bmpH: Int,
    multiPts: List<Pair<Int, Int>>,
    animFraction: Float,
    currentScale: Float,
    visLeft: Float,
    visTop: Float,
    visRight: Float,
    visBottom: Float,
    cache: SelectionOverlayCache,
) {
    clipRect(visLeft, visTop, visRight, visBottom) {
        val strokeW = 1.0.dp.toPx() / currentScale
        val blackStrokeW = 1.5.dp.toPx() / currentScale
        val dashInterval = 3.5.dp.toPx() / currentScale
        val antPhase = animFraction * (dashInterval * 2)
        val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
        val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
        val halfW = bmpW / 2f
        val halfH = bmpH / 2f
        val bx = { x: Int, y: Int -> Offset(x * scX - halfW, y * scY - halfH) }

        cache.multiLassoPath.reset()
        val p0 = bx(multiPts[0].first, multiPts[0].second)
        cache.multiLassoPath.moveTo(p0.x, p0.y)
        for (i in 1 until multiPts.size) {
            val pi = bx(multiPts[i].first, multiPts[i].second)
            cache.multiLassoPath.lineTo(pi.x, pi.y)
        }

        val targetPath = if (multiPts.size >= 3) {
            cache.multiLassoClosedPath.reset()
            cache.multiLassoClosedPath.addPath(cache.multiLassoPath)
            cache.multiLassoClosedPath.close()
            cache.multiLassoClosedPath
        } else {
            cache.multiLassoPath
        }
        drawPath(
            path = targetPath,
            color = Color.Black.copy(alpha = 0.85f),
            style = Stroke(
                width = blackStrokeW,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashInterval, dashInterval), phase = antPhase),
            ),
        )
        drawPath(
            path = targetPath,
            color = Color.White,
            style = Stroke(
                width = strokeW,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dashInterval, dashInterval),
                    phase = antPhase + dashInterval,
                ),
            ),
        )

        if (vm.lassoSegmentCounts.isNotEmpty()) {
            var acc = 0
            for (count in vm.lassoSegmentCounts) {
                acc += count
                val jointIdx = acc - 1
                if (jointIdx in 1 until multiPts.size - 1) {
                    val center = bx(multiPts[jointIdx].first, multiPts[jointIdx].second)
                    drawCircle(color = Color.Black.copy(alpha = 0.65f), radius = 3.8.dp.toPx() / currentScale, center = center)
                    drawCircle(color = Color.White, radius = 2.4.dp.toPx() / currentScale, center = center)
                }
            }
        } else if (vm.lassoSubMode == LassoSubMode.POLYLINE) {
            for (i in 1 until multiPts.size - 1) {
                val center = bx(multiPts[i].first, multiPts[i].second)
                drawCircle(color = Color.Black.copy(alpha = 0.65f), radius = 3.8.dp.toPx() / currentScale, center = center)
                drawCircle(color = Color.White, radius = 2.4.dp.toPx() / currentScale, center = center)
            }
        }

        val startCenter = bx(multiPts[0].first, multiPts[0].second)
        drawCircle(
            color = Color.Black.copy(alpha = 0.6f),
            radius = 8.5.dp.toPx() / currentScale,
            center = startCenter,
            style = Stroke(width = 2.8.dp.toPx() / currentScale),
        )
        drawCircle(
            color = if (vm.selectionMode == 2) Color(0xFFFF5252) else Morandi.accent,
            radius = 8.dp.toPx() / currentScale,
            center = startCenter,
            style = Stroke(width = 2.dp.toPx() / currentScale),
        )
        drawCircle(color = Color.White, radius = 3.5.dp.toPx() / currentScale, center = startCenter)

        val lastPt = multiPts.last()
        val endCenter = bx(lastPt.first, lastPt.second)
        drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = 4.8.dp.toPx() / currentScale, center = endCenter)
        drawCircle(color = Morandi.accent, radius = 3.4.dp.toPx() / currentScale, center = endCenter)
    }
}
