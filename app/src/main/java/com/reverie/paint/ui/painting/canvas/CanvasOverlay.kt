/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
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
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.PI
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
    vm: PaintViewModel,
    // Transform states are read INSIDE the draw lambda only: gesture writes
    // then invalidate just this canvas redraw instead of recomposing the
    // whole page (see perf note in PaintingPage's onTransform).
    zoom: androidx.compose.runtime.State<Float>,
    rotation: androidx.compose.runtime.State<Float>,
    panX: androidx.compose.runtime.State<Float>,
    panY: androidx.compose.runtime.State<Float>,
    /** 视图翻转 (仅镜像显示): 覆盖层必须与画布位图用同一套镜像, 否则选区/控制点会错位 */
    flipX: Boolean = false,
    flipY: Boolean = false,
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
    // 变换预览每帧都要做 setPolyToPoly / clipPath, 这些临时对象全部复用: 旧实现
    // 每格 new 一组 floatArray/Matrix/Path/Rect/RectF (网格模式 9 格 ≈ 每帧 50+
    // 个对象), 拖动变换框时是实打实的 GC 压力。绘制只在主线程执行, 复用安全。
    val previewSrcQuad = androidx.compose.runtime.remember { FloatArray(8) }
    val previewDstQuad = androidx.compose.runtime.remember { FloatArray(8) }
    val previewMatrix = androidx.compose.runtime.remember { android.graphics.Matrix() }
    val previewClipPath = androidx.compose.runtime.remember { android.graphics.Path() }
    val previewSrcRect = androidx.compose.runtime.remember { android.graphics.Rect() }
    val previewDstRectF = androidx.compose.runtime.remember { android.graphics.RectF() }
    val previewBitmapPaint = androidx.compose.runtime.remember {
        android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or
                android.graphics.Paint.FILTER_BITMAP_FLAG or
                android.graphics.Paint.DITHER_FLAG,
        )
    }

    // 选区 Procreate 风格流动蚂蚁线与 45 度斑马纹共用无限流动时间线
    val selInfiniteTransition = rememberInfiniteTransition(label = "selectionAnimation")
    val selAnimFraction = selInfiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "selectionFraction"
    )

    // 45 度斜向条纹纹理：周期 24px，暗条纹与微透亮条纹交错，完全无缝平铺
    // 柔和低对比度中性灰阶斑马纹（Procreate 原生质感）：
    // 暗阶 ~18% 透明度 (0x2E141416)，明阶 ~8% 透明度 (0x14141416)
    // 二者均为同色系中性微透底色，对比度温和通透，绝不刺眼抢眼
    val zebraTileBitmap = androidx.compose.runtime.remember {
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
    val zebraShader = androidx.compose.runtime.remember(zebraTileBitmap) {
        android.graphics.BitmapShader(
            zebraTileBitmap,
            android.graphics.Shader.TileMode.REPEAT,
            android.graphics.Shader.TileMode.REPEAT
        )
    }
    val zebraPaint = androidx.compose.runtime.remember(zebraShader) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = zebraShader
        }
    }
    val zebraShaderMatrix = androidx.compose.runtime.remember { android.graphics.Matrix() }
    val selMaskPaint = androidx.compose.runtime.remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
    }
    val clearPaint = androidx.compose.runtime.remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
    }
    val selDstRectF = androidx.compose.runtime.remember { android.graphics.RectF() }
    val inProgressClosedPath = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Path() }
    val inProgressRectF = androidx.compose.runtime.remember { android.graphics.RectF() }

    Canvas(Modifier.fillMaxSize()) {
        val isSelectionTool = tool.group == ToolGroup.SELECTION
        val isSelecting = liveSelectionPath.value != null ||
            ((tool == Tool.SELECT_RECT || tool == Tool.SELECT_ELLIPSE) && liveShapeStart.value != null) ||
            (tool == Tool.LASSO && vm.lassoMultiPoints.isNotEmpty()) ||
            (tool == Tool.SELECT_POLYGON && polyPoints.isNotEmpty())
        val isTransform = (tool == Tool.TRANSFORM)

        // 仅在存在活动选区或正在绘制选区且非变换操作时才读取动画状态，无选区时完全不触发多余重绘
        val hasActiveSelection = (vm.hasSelection && !isTransform) || isSelecting
        val animFraction = if (hasActiveSelection) selAnimFraction.value else 0f

        val bmp = object {
            val width: Int = if (vm.renderW > 0) vm.renderW else vm.docWidth
            val height: Int = if (vm.renderH > 0) vm.renderH else vm.docHeight
        }
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()
        if (imgW <= 0f || imgH <= 0f) return@Canvas

            val scale = (zoom.value * fitScale).coerceAtLeast(0.001f)
            val center = Offset(size.width / 2f + panX.value, size.height / 2f + panY.value)
            withTransform({
                translate(center.x, center.y)
                rotate(rotation.value, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
                // 视图翻转: 与 CanvasTouchView.drawCanvas 里的 canvas.scale(-1,1)
                // 同一套 (绕画布中心镜像), 于是选区/变换框/辅助线继续贴合画面
                if (flipX || flipY) {
                    scale(
                        if (flipX) -1f else 1f,
                        if (flipY) -1f else 1f,
                        pivot = Offset.Zero,
                    )
                }
            }) {
                drawTransformPreview(
                    vm = vm,
                    tool = tool,
                    tfState = tfState,
                    bmpWidth = imgW,
                    bmpHeight = imgH,
                    previewSrcQuad = previewSrcQuad,
                    previewDstQuad = previewDstQuad,
                    previewMatrix = previewMatrix,
                    previewClipPath = previewClipPath,
                    previewSrcRect = previewSrcRect,
                    previewDstRectF = previewDstRectF,
                    previewBitmapPaint = previewBitmapPaint,
                )

                // Magic-wand tap flash: instant feedback ring in document
                // space (scaled into bitmap space like the preview path)
                wandFlash.value?.let { wf ->
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 6.dp.toPx(),
                        center = Offset(wf.x * scX - bmp.width / 2f, wf.y * scY - bmp.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    )
                }

                // Measure tool: customizable color/width, constant physical screen size, contrast stroke & badge
                if (tool == Tool.MEASURE && measureStart.value != null && measureEnd.value != null) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val s = measureStart.value!!
                    val e = measureEnd.value!!
                    val p1 = Offset(s.x * scX - bmp.width / 2f, s.y * scY - bmp.height / 2f)
                    val p2 = Offset(e.x * scX - bmp.width / 2f, e.y * scY - bmp.height / 2f)

                    // 恒定屏幕物理像素: 除以当前缩放比例 scale，消除随画布放大变粗/过大问题
                    val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
                    val userStrokePx = vm.measureStrokeWidth.dp.toPx()
                    val strokeW = userStrokePx / currentScale
                    val circleR = maxOf(userStrokePx * 1.6f, 4.5.dp.toPx()) / currentScale
                    val innerCircleR = maxOf(1f / currentScale, circleR * 0.45f)

                    // 底部颜色反相渲染 (BlendMode.Difference + 白色 = 底部像素绝对反相，黑变白，白变黑，彩色变为互补色)
                    drawLine(
                        color = Color.White,
                        start = p1,
                        end = p2,
                        strokeWidth = strokeW,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Difference,
                    )
                    // 端点外圈反相实心圆
                    drawCircle(
                        color = Color.White,
                        radius = circleR,
                        center = p1,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Difference,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = circleR,
                        center = p2,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Difference,
                    )
                    // 端点圆心小靶心反相点 (增强控制柄定位清晰度)
                    drawCircle(
                        color = Color.White,
                        radius = innerCircleR,
                        center = p1,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Difference,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = innerCircleR,
                        center = p2,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Difference,
                    )

                    val dist = hypot(e.x - s.x, e.y - s.y)
                    val ang = Math.toDegrees(atan2((e.y - s.y).toDouble(), (e.x - s.x).toDouble())).toFloat()
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
                        val textSizePx = 13.dp.toPx() / currentScale
                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textSize = textSizePx
                            isFakeBoldText = true
                        }
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

                        // 半透明深色圆角胶囊底衬 + 细边框，确保在任何复杂背景上均清晰可读
                        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.argb(200, 20, 20, 20)
                            style = android.graphics.Paint.Style.FILL
                        }
                        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.argb(120, 255, 255, 255)
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 1.dp.toPx() / currentScale
                        }
                        val badgeRect = android.graphics.RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)
                        nativeCanvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, bgPaint)
                        nativeCanvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, strokePaint)

                        // 绘制标签文本
                        val textY = badgeBottom - padY - fm.descent
                        nativeCanvas.drawText(label, badgeLeft + padX, textY, textPaint)
                    }
                }

                // Crop tool preview: dim the outside, white frame
                cropRect?.let { cr ->
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { v: Float -> v * scX - bmp.width / 2f }
                    val by2 = { v: Float -> v * scY - bmp.height / 2f }
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

                drawTransformHandles(
                    vm = vm,
                    tool = tool,
                    tfState = tfState,
                    bmpWidth = imgW,
                    bmpHeight = imgH,
                    zoom = zoom.value,
                    fitScale = fitScale,
                )

                // Polygon / Polyline / Select Polygon vertices preview
                if (polyPoints.isNotEmpty()) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val mappedPts = polyPoints.map { bx(it) }
                    val currentScale = zoom.value * fitScale
                    val polyPath = androidx.compose.ui.graphics.Path()
                    polyPath.moveTo(mappedPts[0].x, mappedPts[0].y)
                    for (i in 1 until mappedPts.size) {
                        polyPath.lineTo(mappedPts[i].x, mappedPts[i].y)
                    }

                    if (tool == Tool.SELECT_POLYGON) {
                        val strokeW = 1.0.dp.toPx() / currentScale
                        val blackStrokeW = 1.5.dp.toPx() / currentScale
                        val dashInterval = 3.5.dp.toPx() / currentScale
                        val antPhase = animFraction * (dashInterval * 2)

                        if (mappedPts.size >= 3) {
                            val closedPath = androidx.compose.ui.graphics.Path().apply {
                                addPath(polyPath)
                                close()
                            }
                            // 双色闭合蚂蚁线
                            drawPath(
                                path = closedPath,
                                color = Color.Black.copy(alpha = 0.85f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = blackStrokeW,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase
                                    )
                                )
                            )
                            drawPath(
                                path = closedPath,
                                color = Color.White,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = strokeW,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase + dashInterval
                                    )
                                )
                            )
                        } else {
                            drawPath(
                                path = polyPath,
                                color = Color.Black.copy(alpha = 0.75f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = blackStrokeW,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase
                                    )
                                )
                            )
                            drawPath(
                                path = polyPath,
                                color = Color.White,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = strokeW,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase + dashInterval
                                    )
                                )
                            )
                        }

                        // 顶点锚点高对比度绘制
                        mappedPts.forEachIndexed { idx, pt ->
                            if (idx == 0) {
                                // 起点：高亮提示点击闭合
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    radius = 8.5.dp.toPx() / currentScale,
                                    center = pt,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.8.dp.toPx() / currentScale)
                                )
                                drawCircle(
                                    color = Morandi.accent,
                                    radius = 8.dp.toPx() / currentScale,
                                    center = pt,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 3.5.dp.toPx() / currentScale,
                                    center = pt
                                )
                            } else {
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    radius = 4.dp.toPx() / currentScale,
                                    center = pt,
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 2.5.dp.toPx() / currentScale,
                                    center = pt,
                                )
                            }
                        }
                    } else {
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
                }

                // Live shape drawing preview (line, rect, ellipse, gradient)
                if (liveShapeStart.value != null && liveShapeEnd.value != null) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val s = bx(liveShapeStart.value!!)
                    val e = bx(liveShapeEnd.value!!)
                    val currentScale = zoom.value * fitScale
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
                        Tool.SELECT_RECT -> {
                            val r = androidx.compose.ui.geometry.Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                            val dashInterval = 3.5.dp.toPx() / currentScale
                            val antPhase = animFraction * (dashInterval * 2)
                            drawRect(
                                Color.Black.copy(alpha = 0.85f),
                                topLeft = r.topLeft,
                                size = r.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.4.dp.toPx() / currentScale,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase
                                    )
                                )
                            )
                            drawRect(
                                Color.White,
                                topLeft = r.topLeft,
                                size = r.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.0.dp.toPx() / currentScale,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase + dashInterval
                                    )
                                )
                            )
                        }
                        Tool.SELECT_ELLIPSE -> {
                            val r = androidx.compose.ui.geometry.Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                            val dashInterval = 3.5.dp.toPx() / currentScale
                            val antPhase = animFraction * (dashInterval * 2)
                            drawOval(
                                Color.Black.copy(alpha = 0.85f),
                                topLeft = r.topLeft,
                                size = r.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.4.dp.toPx() / currentScale,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase
                                    )
                                )
                            )
                            drawOval(
                                Color.White,
                                topLeft = r.topLeft,
                                size = r.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.0.dp.toPx() / currentScale,
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(dashInterval, dashInterval),
                                        phase = antPhase + dashInterval
                                    )
                                )
                            )
                        }
                        Tool.GRADIENT -> {
                            drawLine(Color.White, s, e, strokeWidth = 2.dp.toPx() / currentScale)
                            drawCircle(Color.White, radius = 5.dp.toPx() / currentScale, center = s)
                            drawCircle(Morandi.accent, radius = 5.dp.toPx() / currentScale, center = e)
                        }
                        else -> Unit
                    }
                }

                // Interactive Shape Tool Rendering (Handles + High Fidelity Preview)
                val shapeState = vm.shapeState
                if (shapeState.active) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val currentScale = zoom.value * fitScale
                    val strokeW = maxOf(1f, shapeState.strokeWidth * scX)

                    val shapeColor = try {
                        parseColor(vm.brushColor).copy(alpha = vm.brushOpacity.toFloat())
                    } catch (_: Exception) {
                        Color.White
                    }

                    val drawHandle = { center: Offset, isSelected: Boolean ->
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.7f),
                            radius = 7.dp.toPx() / currentScale,
                            center = center,
                        )
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

                    val p1Canvas = bx(shapeState.p1)
                    val p2Raw = if (shapeState.keepAspect) {
                        val pt = ShapeGeometry.constrainAspect(Point2D(shapeState.p1.x, shapeState.p1.y), Point2D(shapeState.p2.x, shapeState.p2.y))
                        Offset(pt.x, pt.y)
                    } else shapeState.p2
                    val p2Canvas = bx(p2Raw)

                    val isBoxShape = shapeState.type == ShapeType.RECT ||
                        shapeState.type == ShapeType.ROUNDED_RECT ||
                        shapeState.type == ShapeType.ELLIPSE

                    val shapePath = androidx.compose.ui.graphics.Path()

                    when (shapeState.type) {
                        ShapeType.LINE -> {
                            shapePath.moveTo(p1Canvas.x, p1Canvas.y)
                            shapePath.lineTo(p2Canvas.x, p2Canvas.y)
                        }
                        ShapeType.RECT -> {
                            val minX = minOf(p1Canvas.x, p2Canvas.x)
                            val minY = minOf(p1Canvas.y, p2Canvas.y)
                            val maxX = maxOf(p1Canvas.x, p2Canvas.x)
                            val maxY = maxOf(p1Canvas.y, p2Canvas.y)
                            val r = androidx.compose.ui.geometry.Rect(minX, minY, maxX, maxY)
                            shapePath.addRect(r)
                        }
                        ShapeType.ROUNDED_RECT -> {
                            val minX = minOf(p1Canvas.x, p2Canvas.x)
                            val minY = minOf(p1Canvas.y, p2Canvas.y)
                            val maxX = maxOf(p1Canvas.x, p2Canvas.x)
                            val maxY = maxOf(p1Canvas.y, p2Canvas.y)
                            val r = androidx.compose.ui.geometry.Rect(minX, minY, maxX, maxY)
                            val cr = (shapeState.cornerRadius * scX).coerceIn(0f, minOf(r.width, r.height) / 2f)
                            shapePath.addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    rect = r,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr)
                                )
                            )
                        }
                        ShapeType.ELLIPSE -> {
                            val minX = minOf(p1Canvas.x, p2Canvas.x)
                            val minY = minOf(p1Canvas.y, p2Canvas.y)
                            val maxX = maxOf(p1Canvas.x, p2Canvas.x)
                            val maxY = maxOf(p1Canvas.y, p2Canvas.y)
                            val r = androidx.compose.ui.geometry.Rect(minX, minY, maxX, maxY)
                            shapePath.addOval(r)
                        }
                        ShapeType.REGULAR_POLYGON -> {
                            val center = (shapeState.p1 + shapeState.p2) / 2f
                            val radius = kotlin.math.hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f
                            if (radius > 1f) {
                                val pts = ShapeGeometry.generateRegularPolygon(
                                    Point2D(center.x, center.y),
                                    radius,
                                    shapeState.polygonSides,
                                    shapeState.rotationDegrees
                                ).map { bx(Offset(it.x, it.y)) }
                                shapePath.moveTo(pts[0].x, pts[0].y)
                                for (i in 1 until pts.size) {
                                    shapePath.lineTo(pts[i].x, pts[i].y)
                                }
                                shapePath.close()
                            }
                        }
                        ShapeType.STAR -> {
                            val center = (shapeState.p1 + shapeState.p2) / 2f
                            val outerR = kotlin.math.hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f
                            val innerR = outerR * shapeState.starInnerRatio
                            if (outerR > 1f) {
                                val pts = ShapeGeometry.generateStar(
                                    Point2D(center.x, center.y),
                                    outerR,
                                    innerR,
                                    shapeState.starPoints,
                                    shapeState.rotationDegrees
                                ).map { bx(Offset(it.x, it.y)) }
                                shapePath.moveTo(pts[0].x, pts[0].y)
                                for (i in 1 until pts.size) {
                                    shapePath.lineTo(pts[i].x, pts[i].y)
                                }
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
                                if (shapeState.type == ShapeType.POLYGON || shapeState.closed) {
                                    shapePath.close()
                                }
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
                        // 1. Fill preview
                        if (shapeState.fillMode == ShapeFillMode.FILL || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL) {
                            drawPath(shapePath, color = shapeColor, style = androidx.compose.ui.graphics.drawscope.Fill)
                        }

                        // 2. Stroke preview
                        if (shapeState.fillMode == ShapeFillMode.STROKE || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL) {
                            drawPath(
                                shapePath,
                                color = shapeColor,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = strokeW,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                )
                            )
                        }

                        // 3. Handles & guidelines for box shapes
                        val minX = minOf(p1Canvas.x, p2Canvas.x)
                        val minY = minOf(p1Canvas.y, p2Canvas.y)
                        val maxX = maxOf(p1Canvas.x, p2Canvas.x)
                        val maxY = maxOf(p1Canvas.y, p2Canvas.y)
                        val tl = Offset(minX, minY)
                        val tr = Offset(maxX, minY)
                        val br = Offset(maxX, maxY)
                        val bl = Offset(minX, maxY)
                        val center = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)

                        val dashStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.dp.toPx() / currentScale,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(4.dp.toPx() / currentScale, 4.dp.toPx() / currentScale)
                            )
                        )
                        drawRect(Color.White.copy(alpha = 0.6f), topLeft = tl, size = androidx.compose.ui.geometry.Size(maxX - minX, maxY - minY), style = dashStyle)

                        val rotStemY = tl.y - 28.dp.toPx() / currentScale
                        val rotPos = Offset(center.x, rotStemY)
                        drawLine(Color.White.copy(alpha = 0.8f), Offset(center.x, tl.y), rotPos, strokeWidth = 1.dp.toPx() / currentScale)
                        drawHandle(rotPos, shapeState.activeHandle == ShapeHandleId.ROTATE)

                        if (shapeState.type == ShapeType.ROUNDED_RECT) {
                            val cr = (shapeState.cornerRadius * scX).coerceIn(0f, minOf(maxX - minX, maxY - minY) / 2f)
                            val crPos = Offset(tl.x + cr, tl.y + cr)
                            drawCircle(Color.White, radius = 4.dp.toPx() / currentScale, center = crPos)
                            drawCircle(Morandi.accent, radius = 2.5.dp.toPx() / currentScale, center = crPos)
                        }

                        drawHandle(tl, shapeState.activeHandle == ShapeHandleId.CORNER_TL)
                        drawHandle(tr, shapeState.activeHandle == ShapeHandleId.CORNER_TR)
                        drawHandle(br, shapeState.activeHandle == ShapeHandleId.CORNER_BR)
                        drawHandle(bl, shapeState.activeHandle == ShapeHandleId.CORNER_BL)
                    }

                    if (isBoxShape) {
                        val boxCenter = (p1Canvas + p2Canvas) / 2f
                        if (kotlin.math.abs(shapeState.rotationDegrees) > 0.01f) {
                            withTransform({
                                rotate(shapeState.rotationDegrees, pivot = boxCenter)
                            }) {
                                drawBoxContent()
                            }
                        } else {
                            drawBoxContent()
                        }
                    } else {
                        // Non-box shapes: Fill & Stroke preview
                        if (shapeState.fillMode == ShapeFillMode.FILL || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL) {
                            drawPath(shapePath, color = shapeColor, style = androidx.compose.ui.graphics.drawscope.Fill)
                        }
                        if (shapeState.fillMode == ShapeFillMode.STROKE || shapeState.fillMode == ShapeFillMode.STROKE_AND_FILL ||
                            shapeState.type == ShapeType.LINE || shapeState.type == ShapeType.POLYLINE) {
                            drawPath(
                                shapePath,
                                color = shapeColor,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = strokeW,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                )
                            )
                        }

                        // Handles for Line, Polygon, Star, Polyline, Bezier
                        when (shapeState.type) {
                            ShapeType.LINE -> {
                                drawHandle(p1Canvas, shapeState.activeHandle == ShapeHandleId.LINE_P1)
                                drawHandle(p2Canvas, shapeState.activeHandle == ShapeHandleId.LINE_P2)
                                val mid = (p1Canvas + p2Canvas) / 2f
                                drawHandle(mid, shapeState.activeHandle == ShapeHandleId.TRANSLATE_BODY)
                            }
                            ShapeType.REGULAR_POLYGON -> {
                                val center = bx((shapeState.p1 + shapeState.p2) / 2f)
                                val radius = (kotlin.math.hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f) * scX
                                drawHandle(center, shapeState.activeHandle == ShapeHandleId.TRANSLATE_BODY)
                                val baseAngle = (-kotlin.math.PI / 2.0).toFloat() + Math.toRadians(shapeState.rotationDegrees.toDouble()).toFloat()
                                val topH = Offset(
                                    center.x + radius * kotlin.math.cos(baseAngle),
                                    center.y + radius * kotlin.math.sin(baseAngle)
                                )
                                drawHandle(topH, shapeState.activeHandle == ShapeHandleId.STAR_OUTER)
                            }
                            ShapeType.STAR -> {
                                val center = bx((shapeState.p1 + shapeState.p2) / 2f)
                                val outerR = (kotlin.math.hypot(shapeState.p2.x - shapeState.p1.x, shapeState.p2.y - shapeState.p1.y) / 2f) * scX
                                val innerR = outerR * shapeState.starInnerRatio
                                drawHandle(center, shapeState.activeHandle == ShapeHandleId.TRANSLATE_BODY)
                                val baseAngle = (-kotlin.math.PI / 2.0).toFloat() + Math.toRadians(shapeState.rotationDegrees.toDouble()).toFloat()
                                val outerH = Offset(
                                    center.x + outerR * kotlin.math.cos(baseAngle),
                                    center.y + outerR * kotlin.math.sin(baseAngle)
                                )
                                drawHandle(outerH, shapeState.activeHandle == ShapeHandleId.STAR_OUTER)
                                val angleStep = (kotlin.math.PI / shapeState.starPoints).toFloat()
                                val innerAngle = baseAngle + angleStep
                                val innerH = Offset(
                                    center.x + innerR * kotlin.math.cos(innerAngle),
                                    center.y + innerR * kotlin.math.sin(innerAngle)
                                )
                                drawHandle(innerH, shapeState.activeHandle == ShapeHandleId.STAR_INNER)
                            }
                            ShapeType.POLYLINE, ShapeType.POLYGON -> {
                                shapeState.nodes.forEachIndexed { idx, node ->
                                    val pt = bx(Offset(node.pos.x, node.pos.y))
                                    drawHandle(pt, idx == shapeState.selectedNodeIndex)
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
                                                center = cpInCanvas
                                            )
                                        }
                                        if (hasCpOut || isNodeSelected) {
                                            drawLine(handleLineColor, pt, cpOutCanvas, strokeWidth = handleLineWidth)
                                            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 4.5.dp.toPx() / currentScale, center = cpOutCanvas)
                                            drawCircle(
                                                color = if (shapeState.activeHandle == ShapeHandleId.NODE_CP_OUT_BASE + idx) Color.White else Morandi.accent,
                                                radius = 3.dp.toPx() / currentScale,
                                                center = cpOutCanvas
                                            )
                                        }
                                    }

                                    drawHandle(pt, isNodeSelected)
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                // Interactive Typography Tool Rendering (Handles + WYSIWYG Preview)
                if (vm.isTypographyEditing) {
                    val cfg = vm.typographyConfig
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val currentScale = zoom.value * fitScale

                    val paint = TypographyEngine.createTextPaint(cfg, vm.brushOpacity)
                    val targetW = cfg.boxWidth.toInt().coerceAtLeast(60)
                    val layout = TypographyEngine.createLayout(cfg, paint, targetW)
                    val w = layout.width.toFloat()
                    val h = layout.height.toFloat()

                    val tl = bx(Offset(cfg.posX, cfg.posY))
                    val br = bx(Offset(cfg.posX + w, cfg.posY + h))
                    val boxCenter = (tl + br) / 2f

                    val drawTextHandle = { center: Offset ->
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.7f),
                            radius = 7.dp.toPx() / currentScale,
                            center = center,
                        )
                        drawCircle(
                            color = Morandi.accent,
                            radius = 5.dp.toPx() / currentScale,
                            center = center,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 2.dp.toPx() / currentScale,
                            center = center,
                        )
                    }

                    withTransform({
                        rotate(cfg.rotationDeg, pivot = boxCenter)
                    }) {
                        // 1. 文本内容实时渲染 (利用 nativeCanvas)
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.translate(tl.x, tl.y)
                        layout.draw(drawContext.canvas.nativeCanvas)
                        drawContext.canvas.nativeCanvas.restore()

                        // 2. 文本包围边框 (虚线)
                        val boxSize = androidx.compose.ui.geometry.Size(
                            maxOf(1f, br.x - tl.x),
                            maxOf(1f, br.y - tl.y),
                        )
                        drawRect(
                            color = Morandi.accent.copy(alpha = 0.85f),
                            topLeft = tl,
                            size = boxSize,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.2.dp.toPx() / currentScale,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx() / currentScale, 4.dp.toPx() / currentScale),
                                ),
                            ),
                        )

                        // 3. 右下角尺寸缩放手柄
                        drawTextHandle(br)

                        // 4. 顶部旋转手柄
                        val rotPos = Offset(boxCenter.x, tl.y - 24.dp.toPx() / currentScale)
                        drawLine(
                            color = Morandi.accent.copy(alpha = 0.7f),
                            start = Offset(boxCenter.x, tl.y),
                            end = rotPos,
                            strokeWidth = 1.dp.toPx() / currentScale,
                        )
                        drawTextHandle(rotPos)
                    }

                    // 5. 磁吸吸附对齐动态参考线 (沿画布全长贯穿的 Morandi 虚线)
                    if (vm.typographySnapGuides.isNotEmpty()) {
                        for (guide in vm.typographySnapGuides) {
                            val isCenter = guide.type == com.reverie.paint.model.SnapGuideType.CENTER
                            val color = if (isCenter) Morandi.accent else Morandi.accent.copy(alpha = 0.55f)
                            val strokeWidth = if (isCenter) 1.5.dp.toPx() / currentScale else 1.0.dp.toPx() / currentScale
                            val pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx() / currentScale, 4.dp.toPx() / currentScale),
                            )
                            if (guide.isVertical) {
                                val sx = guide.position * scX - bmp.width / 2f
                                drawLine(
                                    color = color,
                                    start = Offset(sx, -bmp.height / 2f),
                                    end = Offset(sx, bmp.height / 2f),
                                    strokeWidth = strokeWidth,
                                    pathEffect = pathEffect,
                                )
                            } else {
                                val sy = guide.position * scY - bmp.height / 2f
                                drawLine(
                                    color = color,
                                    start = Offset(-bmp.width / 2f, sy),
                                    end = Offset(bmp.width / 2f, sy),
                                    strokeWidth = strokeWidth,
                                    pathEffect = pathEffect,
                                )
                            }
                        }
                    }
                }

                val isSelecting = liveSelectionPath.value != null ||
                    ((tool == Tool.SELECT_RECT || tool == Tool.SELECT_ELLIPSE) && liveShapeStart.value != null) ||
                    (tool == Tool.LASSO && vm.lassoMultiPoints.isNotEmpty()) ||
                    (tool == Tool.SELECT_POLYGON && polyPoints.isNotEmpty())
                // 1. Procreate 风格：仅在选区工具内显示未选区 45 度动态流动斑马纹；切到非选区工具（画笔、橡皮擦等）时自动隐藏遮罩，保持画布视野干净
                val isTransform = (tool == Tool.TRANSFORM)
                val isSelectionTool = tool.group == ToolGroup.SELECTION
                val shouldShowZebra = isSelectionTool && !isTransform && (vm.hasSelection || isSelecting)
                val selBmp = if (vm.hasSelection) vm.selectionOverlayBitmap else null
                if (shouldShowZebra) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
                    val left = -bmp.width / 2f
                    val top = -bmp.height / 2f
                    val right = bmp.width / 2f
                    val bottom = bmp.height / 2f

                    // 45 度斜向动态流动斑马纹（Procreate 风格）
                    // 保持在物理屏幕上 1:1 的条纹粗细与方向，不受画布缩放与旋转影响
                    zebraShaderMatrix.reset()
                    val stripeTileScreenPx = 18.dp.toPx()
                    val stripeScale = (stripeTileScreenPx / 24f) / currentScale
                    zebraShaderMatrix.setScale(stripeScale, stripeScale)
                    zebraShaderMatrix.postRotate(-rotation.value)
                    val zebraOffset = animFraction * stripeTileScreenPx
                    zebraShaderMatrix.postTranslate(zebraOffset / currentScale, 0f)
                    zebraShader.setLocalMatrix(zebraShaderMatrix)

                    // 动态调整斑马纹不透明度（与蒙版不透明度设置项联动）
                    val opacityScale = (vm.selectionMaskOpacity / 0.47f).coerceIn(0.2f, 2.0f)
                    zebraPaint.alpha = (opacityScale * 255).toInt().coerceIn(20, 255)

                    val saveCount = nativeCanvas.saveLayer(left, top, right, bottom, null)
                    nativeCanvas.drawRect(left, top, right, bottom, zebraPaint)

                    // 若存在已有提交选区，且当前处于追加/减去/相交模式（或当前未在绘制新选区），则先应用已有选区遮罩
                    if (vm.hasSelection && selBmp != null && !selBmp.isRecycled && (vm.selectionMode != 0 || !isSelecting)) {
                        selDstRectF.set(left, top, right, bottom)
                        nativeCanvas.drawBitmap(selBmp, null, selDstRectF, selMaskPaint)
                    }

                    // 实时反向斑马纹预览：将正在拖拽/绘制的路径内部镂空 (CLEAR) 或叠加，外部即刻呈现斑马纹
                    if (isSelecting) {
                        val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                        val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                        val halfW = bmp.width / 2f
                        val halfH = bmp.height / 2f

                        when {
                            // 自由套索实时轨迹
                            liveSelectionPath.value != null -> {
                                val livePath = liveSelectionPath.value!!
                                inProgressClosedPath.reset()
                                inProgressClosedPath.addPath(livePath)
                                inProgressClosedPath.close()
                                when (vm.selectionMode) {
                                    0, 1 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), clearPaint)
                                    2 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), zebraPaint)
                                    3 -> {
                                        nativeCanvas.save()
                                        nativeCanvas.clipOutPath(inProgressClosedPath.asAndroidPath())
                                        nativeCanvas.drawRect(left, top, right, bottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                            // 折线套索已确认点集（>=3 点时可闭合镂空预览）
                            tool == Tool.LASSO && vm.lassoMultiPoints.size >= 3 -> {
                                val pts = vm.lassoMultiPoints
                                inProgressClosedPath.reset()
                                inProgressClosedPath.moveTo(pts[0].first * scX - halfW, pts[0].second * scY - halfH)
                                for (i in 1 until pts.size) {
                                    inProgressClosedPath.lineTo(pts[i].first * scX - halfW, pts[i].second * scY - halfH)
                                }
                                inProgressClosedPath.close()
                                when (vm.selectionMode) {
                                    0, 1 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), clearPaint)
                                    2 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), zebraPaint)
                                    3 -> {
                                        nativeCanvas.save()
                                        nativeCanvas.clipOutPath(inProgressClosedPath.asAndroidPath())
                                        nativeCanvas.drawRect(left, top, right, bottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                            // 多边形选择（>=3 点时可闭合镂空预览）
                            tool == Tool.SELECT_POLYGON && polyPoints.size >= 3 -> {
                                inProgressClosedPath.reset()
                                inProgressClosedPath.moveTo(polyPoints[0].x * scX - halfW, polyPoints[0].y * scY - halfH)
                                for (i in 1 until polyPoints.size) {
                                    inProgressClosedPath.lineTo(polyPoints[i].x * scX - halfW, polyPoints[i].y * scY - halfH)
                                }
                                inProgressClosedPath.close()
                                when (vm.selectionMode) {
                                    0, 1 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), clearPaint)
                                    2 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), zebraPaint)
                                    3 -> {
                                        nativeCanvas.save()
                                        nativeCanvas.clipOutPath(inProgressClosedPath.asAndroidPath())
                                        nativeCanvas.drawRect(left, top, right, bottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                            // 矩形选择实时拖拽框
                            tool == Tool.SELECT_RECT && liveShapeStart.value != null && liveShapeEnd.value != null -> {
                                val s = liveShapeStart.value!!
                                val e = liveShapeEnd.value!!
                                val rLeft = minOf(s.x, e.x) * scX - halfW
                                val rTop = minOf(s.y, e.y) * scY - halfH
                                val rRight = maxOf(s.x, e.x) * scX - halfW
                                val rBottom = maxOf(s.y, e.y) * scY - halfH
                                inProgressRectF.set(rLeft, rTop, rRight, rBottom)
                                when (vm.selectionMode) {
                                    0, 1 -> nativeCanvas.drawRect(inProgressRectF, clearPaint)
                                    2 -> nativeCanvas.drawRect(inProgressRectF, zebraPaint)
                                    3 -> {
                                        nativeCanvas.save()
                                        nativeCanvas.clipOutRect(inProgressRectF)
                                        nativeCanvas.drawRect(left, top, right, bottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                            // 椭圆选择实时拖拽框
                            tool == Tool.SELECT_ELLIPSE && liveShapeStart.value != null && liveShapeEnd.value != null -> {
                                val s = liveShapeStart.value!!
                                val e = liveShapeEnd.value!!
                                val rLeft = minOf(s.x, e.x) * scX - halfW
                                val rTop = minOf(s.y, e.y) * scY - halfH
                                val rRight = maxOf(s.x, e.x) * scX - halfW
                                val rBottom = maxOf(s.y, e.y) * scY - halfH
                                inProgressRectF.set(rLeft, rTop, rRight, rBottom)
                                when (vm.selectionMode) {
                                    0, 1 -> nativeCanvas.drawOval(inProgressRectF, clearPaint)
                                    2 -> nativeCanvas.drawOval(inProgressRectF, zebraPaint)
                                    3 -> {
                                        inProgressClosedPath.reset()
                                        inProgressClosedPath.addOval(androidx.compose.ui.geometry.Rect(rLeft, rTop, rRight, rBottom))
                                        nativeCanvas.save()
                                        nativeCanvas.clipOutPath(inProgressClosedPath.asAndroidPath())
                                        nativeCanvas.drawRect(left, top, right, bottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                        }
                    }

                    nativeCanvas.restoreToCount(saveCount)
                }

                // 2. 选区边界动态黑白交替流动蚂蚁线：无论在选区工具内还是切到其他工具（画笔、橡皮擦等），只要存在活动选区，选区边缘均显示动态流动的黑白相间蚂蚁线
                val shouldShowOutline = !isTransform && vm.hasSelection && (!isSelecting || vm.selectionMode != 0)
                if (shouldShowOutline) {
                    vm.selectionOutlinePath?.let { outlinePath ->
                        val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
                        val strokeW = 1.0.dp.toPx() / currentScale
                        val blackStrokeW = 1.4.dp.toPx() / currentScale
                        val dashInterval = 3.5.dp.toPx() / currentScale
                        val antPhase = animFraction * (dashInterval * 2)

                        drawPath(
                            path = outlinePath,
                            color = Color.Black.copy(alpha = 0.85f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = blackStrokeW,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(dashInterval, dashInterval),
                                    phase = antPhase
                                )
                            )
                        )
                        drawPath(
                            path = outlinePath,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeW,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(dashInterval, dashInterval),
                                    phase = antPhase + dashInterval
                                )
                            )
                        )
                    }
                }

                liveSelectionPath.value?.let { livePath ->
                    val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
                    val strokeW = 1.0.dp.toPx() / currentScale
                    val blackStrokeW = 1.4.dp.toPx() / currentScale
                    val dashInterval = 3.5.dp.toPx() / currentScale
                    val antPhase = animFraction * (dashInterval * 2)

                    // 细腻双色虚线蚂蚁线轮廓 (黑色底 + 白色错位虚线)
                    drawPath(
                        path = livePath,
                        color = Color.Black.copy(alpha = 0.85f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = blackStrokeW,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(dashInterval, dashInterval),
                                phase = antPhase
                            )
                        )
                    )
                    drawPath(
                        path = livePath,
                        color = Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeW,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(dashInterval, dashInterval),
                                phase = antPhase + dashInterval
                            )
                        )
                    )
                }

                // 多次操作套索（折线/自由混合）在空闲等待手势时的在编路径与起点指示
                val multiPts = vm.lassoMultiPoints
                if (multiPts.isNotEmpty() && liveSelectionPath.value == null && tool == Tool.LASSO) {
                    val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
                    val strokeW = 1.0.dp.toPx() / currentScale
                    val blackStrokeW = 1.5.dp.toPx() / currentScale
                    val dashInterval = 3.5.dp.toPx() / currentScale
                    val antPhase = animFraction * (dashInterval * 2)
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val halfW = bmp.width / 2f
                    val halfH = bmp.height / 2f
                    val bx = { x: Int, y: Int -> Offset(x * scX - halfW, y * scY - halfH) }

                    val path = Path().apply {
                        val p0 = bx(multiPts[0].first, multiPts[0].second)
                        moveTo(p0.x, p0.y)
                        for (i in 1 until multiPts.size) {
                            val pi = bx(multiPts[i].first, multiPts[i].second)
                            lineTo(pi.x, pi.y)
                        }
                    }

                    if (multiPts.size >= 3) {
                        val closedPath = Path().apply {
                            addPath(path)
                            close()
                        }
                        // 双色闭合蚂蚁线
                        drawPath(
                            path = closedPath,
                            color = Color.Black.copy(alpha = 0.85f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = blackStrokeW,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(dashInterval, dashInterval),
                                    phase = antPhase
                                )
                            )
                        )
                        drawPath(
                            path = closedPath,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeW,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(dashInterval, dashInterval),
                                    phase = antPhase + dashInterval
                                )
                            )
                        )
                    } else {
                        // 只有2点时的开放线段双色蚂蚁线
                        drawPath(
                            path = path,
                            color = Color.Black.copy(alpha = 0.85f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = blackStrokeW,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(dashInterval, dashInterval),
                                    phase = antPhase
                                )
                            )
                        )
                        drawPath(
                            path = path,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeW,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(dashInterval, dashInterval),
                                    phase = antPhase + dashInterval
                                )
                            )
                        )
                    }

                    // 中间转折锚点（仅在每段操作的交接点绘制，不在自由曲线内部点上绘制）
                    if (vm.lassoSegmentCounts.isNotEmpty()) {
                        var acc = 0
                        for (count in vm.lassoSegmentCounts) {
                            acc += count
                            val jointIdx = acc - 1
                            if (jointIdx in 1 until multiPts.size - 1) {
                                val pt = multiPts[jointIdx]
                                val center = bx(pt.first, pt.second)
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    radius = 3.8.dp.toPx() / currentScale,
                                    center = center,
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 2.4.dp.toPx() / currentScale,
                                    center = center,
                                )
                            }
                        }
                    } else if (vm.lassoSubMode == LassoSubMode.POLYLINE) {
                        for (i in 1 until multiPts.size - 1) {
                            val center = bx(multiPts[i].first, multiPts[i].second)
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.65f),
                                radius = 3.8.dp.toPx() / currentScale,
                                center = center,
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.4.dp.toPx() / currentScale,
                                center = center,
                            )
                        }
                    }

                    // 起点闭合光圈 (高亮起点，提示用户点击可闭合)
                    val startCenter = bx(multiPts[0].first, multiPts[0].second)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.6f),
                        radius = 8.5.dp.toPx() / currentScale,
                        center = startCenter,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.8.dp.toPx() / currentScale)
                    )
                    drawCircle(
                        color = if (vm.selectionMode == 2) Color(0xFFFF5252) else Morandi.accent,
                        radius = 8.dp.toPx() / currentScale,
                        center = startCenter,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.5.dp.toPx() / currentScale,
                        center = startCenter
                    )

                    // 最新末端锚点
                    val lastPt = multiPts.last()
                    val endCenter = bx(lastPt.first, lastPt.second)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.6f),
                        radius = 4.8.dp.toPx() / currentScale,
                        center = endCenter
                    )
                    drawCircle(
                        color = Morandi.accent,
                        radius = 3.4.dp.toPx() / currentScale,
                        center = endCenter
                    )
                }

                drawDrawingGuides(
                    vm = vm,
                    bmpWidth = imgW,
                    bmpHeight = imgH,
                    zoom = zoom.value,
                    fitScale = fitScale,
                )

            }

            drawColorLoupe(
                pickerActive = pickerActive.value,
                pickerScreenPos = pickerScreenPos.value,
                pickerInitialColor = pickerInitialColor.value,
                pickerCurrentColor = pickerCurrentColor.value,
            )
        }
}
