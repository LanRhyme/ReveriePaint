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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay


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
    /** 分格工具手势预览状态 (CanvasView remember 后传给触摸层与本覆盖层); null = 不绘制 */
    panelInteraction: PanelInteractionState? = null,
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

    // 选区闭合/生成时的晶莹轮廓流光爆发 (Selection Contour Flash)
    val selectionFlashProgress = remember { Animatable(1f) }
    var lastFlashOutline by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(vm.selectionOutlinePath) {
        val path = vm.selectionOutlinePath
        if (path != null && path !== lastFlashOutline) {
            lastFlashOutline = path
            selectionFlashProgress.snapTo(0f)
            selectionFlashProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 360,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else if (path == null) {
            lastFlashOutline = null
        }
    }

    // 吸色放大镜平滑微弹出现与渐隐动画
    val pickerAnimProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pickerActive.value) 1f else 0f,
        animationSpec = if (pickerActive.value) {
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
            )
        } else {
            androidx.compose.animation.core.tween(durationMillis = 140, easing = androidx.compose.animation.core.FastOutLinearInEasing)
        },
        label = "pickerAnimProgress",
    )

    // 长按吸色状态下副手指点按切换采样模式 (当前图层 <-> 全部图层) 时的弹性微动效与光环扩散
    val pickerModePulseAnim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    var lastPickerSampleMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(vm.pickerSampleLayers) }
    androidx.compose.runtime.LaunchedEffect(vm.pickerSampleLayers) {
        if (pickerActive.value && vm.pickerSampleLayers != lastPickerSampleMode) {
            pickerModePulseAnim.snapTo(1f)
            pickerModePulseAnim.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                ),
            )
        }
        lastPickerSampleMode = vm.pickerSampleLayers
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val loupeTextPaint = androidx.compose.runtime.remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 10.5.sp.toPx() }
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }
    val currentLayerStr = stringResource(com.reverie.paint.R.string.selection_current_layer)
    val allLayersStr = stringResource(com.reverie.paint.R.string.selection_all_layers)
    val pickerModeLabel = if (vm.pickerSampleLayers == 0) currentLayerStr else allLayersStr

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
    val selSrcRect = androidx.compose.runtime.remember { android.graphics.Rect() }
    val inProgressClosedPath = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Path() }
    val inProgressRectF = androidx.compose.runtime.remember { android.graphics.RectF() }
    val multiLassoPath = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Path() }
    val multiLassoClosedPath = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Path() }

    // 切层闪现: 合成画面之上叠加"本层内容的纯色形状" (tint+SrcIn, 与图层选中态
    // 同一语义色) 脉冲, 只有本层内容在闪 (线条闪 accent 色再恢复), 其他层像素
    // 不动。滤镜 remember 缓存, 不逐帧分配。
    val layerFlashAnim = remember { Animatable(0f) }
    // 强调色在组合期读取并作 remember key: 换强调色 → Theme.current 变化触发重组 →
    // 滤镜重建, 闪现色始终跟随设置里的强调色; 未变化时不产生任何分配
    val layerFlashAccent = Morandi.accent
    val layerFlashTint = remember(layerFlashAccent) {
        androidx.compose.ui.graphics.ColorFilter.tint(layerFlashAccent, BlendMode.SrcIn)
    }
    LaunchedEffect(vm.layerFlashTick) {
        if (vm.layerFlashTick == 0 || vm.layerFlashBitmap == null) {
            layerFlashAnim.snapTo(0f)
            return@LaunchedEffect
        }
        layerFlashAnim.snapTo(0.9f)
        delay(80)
        layerFlashAnim.animateTo(0f, tween(durationMillis = 120))
        vm.clearLayerFlash()
    }

    val textEmptyPlaceholder = androidx.compose.ui.res.stringResource(com.reverie.paint.R.string.typography_empty_placeholder)

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

        val tiles = vm.displayTiles.drawable()
        val displayBmp = if (tiles != null) null else vm.displayBitmap?.takeIf { !it.isRecycled }
        val bmpW = tiles?.grid?.width ?: displayBmp?.width ?: vm.docWidth.takeIf { it > 0 } ?: 1
        val bmpH = tiles?.grid?.height ?: displayBmp?.height ?: vm.docHeight.takeIf { it > 0 } ?: 1
        val bmp = object {
            // 上游 ed32b1a8 口径 (瓦片网格 → 整图 → 文档); B 线 overlayBitmapSize 仅供前缓冲重放路径使用
            val width: Int = bmpW
            val height: Int = bmpH
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
                val flashBmp = vm.layerFlashBitmap?.takeIf { !it.isRecycled }
                if (flashBmp != null && layerFlashAnim.value > 0f) {
                    // 本层内容 accent 色形状脉冲叠加, 其他层像素不动
                    drawImage(
                        image = flashBmp.asImageBitmap(),
                        dstOffset = IntOffset(-bmp.width / 2, -bmp.height / 2),
                        dstSize = IntSize(bmp.width, bmp.height),
                        alpha = layerFlashAnim.value,
                        colorFilter = layerFlashTint,
                        filterQuality = FilterQuality.Low,
                    )
                }

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
                        Tool.GRADIENT -> Unit
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

                    val isBoxShape = shapeState.type == ShapeType.RECT ||
                        shapeState.type == ShapeType.ROUNDED_RECT ||
                        shapeState.type == ShapeType.ELLIPSE

                    val p1Canvas = bx(shapeState.p1)
                    val p2Raw = if (shapeState.keepAspect && isBoxShape) {
                        val pt = ShapeGeometry.constrainAspect(Point2D(shapeState.p1.x, shapeState.p1.y), Point2D(shapeState.p2.x, shapeState.p2.y))
                        Offset(pt.x, pt.y)
                    } else shapeState.p2
                    val p2Canvas = bx(p2Raw)

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

                // Interactive Gradient Tool Rendering (Handles + High Fidelity Live Preview)
                val gradientState = vm.gradientState
                if (gradientState.active && gradientState.p1 != gradientState.p2) {
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val currentScale = zoom.value * fitScale

                    val fgColor = try {
                        Color(android.graphics.Color.parseColor(vm.brushColor))
                    } catch (_: Exception) {
                        Color.Black
                    }
                    val bgColor = try {
                        Color(android.graphics.Color.parseColor(vm.brushSecondaryColor))
                    } catch (_: Exception) {
                        Color.White
                    }

                    var effectiveStops = gradientState.getEffectiveStops(fgColor, bgColor)
                    if (gradientState.reverse) {
                        effectiveStops = effectiveStops.map {
                            GradientColorStop(1f - it.position, it.colorArgb)
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
                        GradientType.RADIAL -> {
                            val r = maxOf(1f, (p2Canvas - p1Canvas).getDistance())
                            Brush.radialGradient(colorStops = colorStops, center = p1Canvas, radius = r, tileMode = tileMode)
                        }
                        GradientType.CONICAL -> {
                            Brush.sweepGradient(colorStops = colorStops, center = p1Canvas)
                        }
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
                        GradientType.DIAMOND -> {
                            val r = maxOf(1f, (p2Canvas - p1Canvas).getDistance())
                            Brush.radialGradient(colorStops = colorStops, center = p1Canvas, radius = r, tileMode = tileMode)
                        }
                        else -> { // LINEAR
                            Brush.linearGradient(colorStops = colorStops, start = p1Canvas, end = p2Canvas, tileMode = tileMode)
                        }
                    }

                    // 1. 全幅画布渐变预览
                    drawRect(
                        brush = previewBrush,
                        topLeft = Offset(-bmp.width / 2f, -bmp.height / 2f),
                        size = androidx.compose.ui.geometry.Size(bmp.width.toFloat(), bmp.height.toFloat()),
                        alpha = gradientState.opacity,
                        blendMode = composeBlendMode,
                    )

                    // 2. 渐变轴线与手柄
                    drawLine(
                        Color.Black.copy(alpha = 0.6f),
                        p1Canvas,
                        p2Canvas,
                        strokeWidth = 3.5.dp.toPx() / currentScale,
                    )
                    drawLine(
                        Color.White,
                        p1Canvas,
                        p2Canvas,
                        strokeWidth = 1.5.dp.toPx() / currentScale,
                    )

                    // 轴中点平移手柄
                    val midPt = (p1Canvas + p2Canvas) / 2f
                    drawCircle(
                        Color.Black.copy(alpha = 0.7f),
                        radius = 7.dp.toPx() / currentScale,
                        center = midPt,
                    )
                    drawCircle(
                        if (gradientState.activeHandle == GradientHandleId.MID_BODY) Morandi.accent else Color.White,
                        radius = 5.dp.toPx() / currentScale,
                        center = midPt,
                    )
                    drawCircle(
                        if (gradientState.activeHandle == GradientHandleId.MID_BODY) Color.White else Morandi.accent,
                        radius = 2.5.dp.toPx() / currentScale,
                        center = midPt,
                    )

                    // 轴上多色标小手柄
                    effectiveStops.forEachIndexed { idx, s ->
                        val stopPt = p1Canvas + (p2Canvas - p1Canvas) * s.position
                        val isSelected = gradientState.activeHandle == GradientHandleId.STOP_BASE + idx
                        drawCircle(
                            Color.Black.copy(alpha = 0.7f),
                            radius = 6.dp.toPx() / currentScale,
                            center = stopPt,
                        )
                        drawCircle(
                            if (isSelected) Morandi.accent else Color.White,
                            radius = 4.5.dp.toPx() / currentScale,
                            center = stopPt,
                        )
                        drawCircle(
                            Color(s.colorArgb),
                            radius = 3.dp.toPx() / currentScale,
                            center = stopPt,
                        )
                    }

                    // 起点手柄 (P1)
                    val firstCol = effectiveStops.firstOrNull()?.colorArgb?.let { Color(it) } ?: Color.Black
                    drawCircle(
                        Color.Black.copy(alpha = 0.7f),
                        radius = 9.dp.toPx() / currentScale,
                        center = p1Canvas,
                    )
                    drawCircle(
                        if (gradientState.activeHandle == GradientHandleId.START) Morandi.accent else Color.White,
                        radius = 7.dp.toPx() / currentScale,
                        center = p1Canvas,
                    )
                    drawCircle(
                        firstCol,
                        radius = 5.dp.toPx() / currentScale,
                        center = p1Canvas,
                    )

                    // 终点手柄 (P2)
                    val lastCol = effectiveStops.lastOrNull()?.colorArgb?.let { Color(it) } ?: Color.White
                    drawCircle(
                        Color.Black.copy(alpha = 0.7f),
                        radius = 9.dp.toPx() / currentScale,
                        center = p2Canvas,
                    )
                    drawCircle(
                        if (gradientState.activeHandle == GradientHandleId.END) Morandi.accent else Color.White,
                        radius = 7.dp.toPx() / currentScale,
                        center = p2Canvas,
                    )
                    drawCircle(
                        lastCol,
                        radius = 5.dp.toPx() / currentScale,
                        center = p2Canvas,
                    )
                }

                // Interactive Typography Tool Rendering (Handles + WYSIWYG Preview)
                if (vm.isTypographyEditing) {
                    val cfg = vm.typographyConfig
                    val editLayerId = vm.editingTextLayerId
                    val activeItemId = vm.editingTextItemId
                    val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - bmp.width / 2f, p.y * scY - bmp.height / 2f) }
                    val currentScale = zoom.value * fitScale

                    // 1. 同图层其他非编辑文字块实时预览渲染
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

                            withTransform({
                                rotate(item.rotationDeg, pivot = itemBoxCenter)
                            }) {
                                drawContext.canvas.nativeCanvas.save()
                                drawContext.canvas.nativeCanvas.translate(itemTl.x, itemTl.y)
                                itemLayout.draw(drawContext.canvas.nativeCanvas)
                                drawContext.canvas.nativeCanvas.restore()
                            }
                        }
                    }

                    // 2. 当前正在交互编辑的文字块
                    val isEmptyText = cfg.text.isEmpty()
                    val displayCfg = if (isEmptyText) cfg.copy(text = textEmptyPlaceholder) else cfg
                    val displayOpacity = if (isEmptyText) vm.brushOpacity * 0.45 else vm.brushOpacity
                    val paint = TypographyEngine.createTextPaint(displayCfg, displayOpacity)
                    val targetW = TypographyEngine.resolveTargetWidth(displayCfg)
                    val layout = TypographyEngine.createLayout(displayCfg, paint, targetW)
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
                val shouldShowZebra = isSelectionTool && !isTransform && !vm.transientTransformSelectionPending && (vm.hasSelection || isSelecting)
                val selBmp = if (vm.hasSelection) vm.selectionOverlayBitmap else null

                // 计算当前屏幕视口在画布坐标系中的包围盒 (AABB)，并与文档边界求交集。
                // 仅对可见区域执行 saveLayer、斑马纹填充与选区轮廓绘制，
                // 避免在放大画布时盲目渲染数千万像素及海量密集条纹导致的严重卡顿。
                val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
                val invScale = 1f / currentScale
                val rad = Math.toRadians(-rotation.value.toDouble())
                val cosA = kotlin.math.cos(rad).toFloat()
                val sinA = kotlin.math.sin(rad).toFloat()
                val cX = center.x
                val cY = center.y
                val fx = if (flipX) -1f else 1f
                val fy = if (flipY) -1f else 1f

                var minVisX = Float.POSITIVE_INFINITY
                var maxVisX = Float.NEGATIVE_INFINITY
                var minVisY = Float.POSITIVE_INFINITY
                var maxVisY = Float.NEGATIVE_INFINITY

                for (cornerIdx in 0 until 4) {
                    val sx = if (cornerIdx == 1 || cornerIdx == 2) size.width else 0f
                    val sy = if (cornerIdx == 2 || cornerIdx == 3) size.height else 0f
                    val dx = sx - cX
                    val dy = sy - cY
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
                minVisX -= visPad
                maxVisX += visPad
                minVisY -= visPad
                maxVisY += visPad

                val docLeft = -bmp.width / 2f
                val docTop = -bmp.height / 2f
                val docRight = bmp.width / 2f
                val docBottom = bmp.height / 2f

                val visLeft = maxOf(docLeft, minVisX)
                val visTop = maxOf(docTop, minVisY)
                val visRight = minOf(docRight, maxVisX)
                val visBottom = minOf(docBottom, maxVisY)
                val isVisibleOnScreen = visLeft < visRight && visTop < visBottom

                if (shouldShowZebra && isVisibleOnScreen) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas

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

                    val saveCount = nativeCanvas.saveLayer(visLeft, visTop, visRight, visBottom, null)
                    nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, zebraPaint)

                    // 若存在已有提交选区，且当前处于追加/减去/相交模式（或当前未在绘制新选区），则先应用已有选区遮罩 (仅绘制视口内可见部分)
                    if (vm.hasSelection && selBmp != null && !selBmp.isRecycled && (vm.selectionMode != 0 || !isSelecting)) {
                        val bmLeft = ((visLeft - docLeft) / bmp.width * selBmp.width).toInt().coerceIn(0, selBmp.width)
                        val bmTop = ((visTop - docTop) / bmp.height * selBmp.height).toInt().coerceIn(0, selBmp.height)
                        val bmRight = kotlin.math.ceil((visRight - docLeft) / bmp.width * selBmp.width).toInt().coerceIn(bmLeft, selBmp.width)
                        val bmBottom = kotlin.math.ceil((visBottom - docTop) / bmp.height * selBmp.height).toInt().coerceIn(bmTop, selBmp.height)
                        if (bmRight > bmLeft && bmBottom > bmTop) {
                            selSrcRect.set(bmLeft, bmTop, bmRight, bmBottom)
                            selDstRectF.set(visLeft, visTop, visRight, visBottom)
                            nativeCanvas.drawBitmap(selBmp, selSrcRect, selDstRectF, selMaskPaint)
                        }
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
                                        nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, zebraPaint)
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
                                        nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                            // 多边形选择（>=3 点时可闭合镂空预览）
                            tool == Tool.SELECT_POLYGON && polyPoints.size >= 3 -> {
                                val pts = polyPoints
                                inProgressClosedPath.reset()
                                inProgressClosedPath.moveTo(pts[0].x * scX - halfW, pts[0].y * scY - halfH)
                                for (i in 1 until pts.size) {
                                    inProgressClosedPath.lineTo(pts[i].x * scX - halfW, pts[i].y * scY - halfH)
                                }
                                inProgressClosedPath.close()
                                when (vm.selectionMode) {
                                    0, 1 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), clearPaint)
                                    2 -> nativeCanvas.drawPath(inProgressClosedPath.asAndroidPath(), zebraPaint)
                                    3 -> {
                                        nativeCanvas.save()
                                        nativeCanvas.clipOutPath(inProgressClosedPath.asAndroidPath())
                                        nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, zebraPaint)
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
                                        nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, zebraPaint)
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
                                        nativeCanvas.drawRect(visLeft, visTop, visRight, visBottom, zebraPaint)
                                        nativeCanvas.restore()
                                    }
                                }
                            }
                        }
                    }

                    nativeCanvas.restoreToCount(saveCount)
                }

                // 2. 选区边界动态黑白交替流动蚂蚁线：无论在选区工具内还是切到其他工具（画笔、橡皮擦等），只要存在活动选区，选区边缘均显示动态流动的黑白相间蚂蚁线
                val shouldShowOutline = !vm.transientTransformSelectionPending && isVisibleOnScreen && CanvasDecorGeometry.selectionOutlineVisible(
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

                                // 选区闭合/生成瞬间的晶莹轮廓流光爆发 (Selection Contour Flash)
                                val flashFrac = selectionFlashProgress.value
                                if (flashFrac < 1f) {
                                    val flashAlpha = (1f - flashFrac).coerceIn(0f, 1f)
                                    val glowWidth = (2.2.dp + 5.0.dp * flashFrac).toPx() / currentScale
                                    val coreWidth = (1.5.dp + 0.8.dp * (1f - flashFrac)).toPx() / currentScale

                                    // 柔和外晕 (Morandi Accent / Cyan Light)
                                    drawPath(
                                        path = outlinePath,
                                        color = Morandi.accent.copy(alpha = 0.55f * flashAlpha),
                                        style = Stroke(
                                            width = glowWidth,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round,
                                        ),
                                    )
                                    // 晶莹白光高光核心
                                    drawPath(
                                        path = outlinePath,
                                        color = Color.White.copy(alpha = 0.90f * flashAlpha),
                                        style = Stroke(
                                            width = coreWidth,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                if (isVisibleOnScreen) {
                    liveSelectionPath.value?.let { livePath ->
                        clipRect(visLeft, visTop, visRight, visBottom) {
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
                    }
                }

                // 多次操作套索（折线/自由混合）在空闲等待手势时的在编路径与起点指示
                val multiPts = vm.lassoMultiPoints
                if (multiPts.isNotEmpty() && liveSelectionPath.value == null && tool == Tool.LASSO && isVisibleOnScreen) {
                    clipRect(visLeft, visTop, visRight, visBottom) {
                        val strokeW = 1.0.dp.toPx() / currentScale
                        val blackStrokeW = 1.5.dp.toPx() / currentScale
                        val dashInterval = 3.5.dp.toPx() / currentScale
                        val antPhase = animFraction * (dashInterval * 2)
                        val scX = if (vm.docWidth > 0) bmp.width.toFloat() / vm.docWidth else 1f
                        val scY = if (vm.docHeight > 0) bmp.height.toFloat() / vm.docHeight else 1f
                        val halfW = bmp.width / 2f
                        val halfH = bmp.height / 2f
                        val bx = { x: Int, y: Int -> Offset(x * scX - halfW, y * scY - halfH) }

                        multiLassoPath.reset()
                        val p0 = bx(multiPts[0].first, multiPts[0].second)
                        multiLassoPath.moveTo(p0.x, p0.y)
                        for (i in 1 until multiPts.size) {
                            val pi = bx(multiPts[i].first, multiPts[i].second)
                            multiLassoPath.lineTo(pi.x, pi.y)
                        }

                        if (multiPts.size >= 3) {
                            multiLassoClosedPath.reset()
                            multiLassoClosedPath.addPath(multiLassoPath)
                            multiLassoClosedPath.close()
                            // 双色闭合蚂蚁线
                            drawPath(
                                path = multiLassoClosedPath,
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
                                path = multiLassoClosedPath,
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
                                path = multiLassoPath,
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
                                path = multiLassoPath,
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
                }

                drawDrawingGuides(
                    vm = vm,
                    bmpWidth = imgW,
                    bmpHeight = imgH,
                    zoom = zoom.value,
                    fitScale = fitScale,
                )

                // 分格覆盖层 (轮廓/选中高亮/角手柄/拖动预览/画画模式分格轮廓提示)
                if (panelInteraction != null &&
                    (tool == Tool.PANEL || vm.panels.isNotEmpty() || vm.selectedPanelId != null || vm.panelMultiSelectedIds.isNotEmpty())
                ) {
                    drawPanelsOverlay(
                        vm = vm,
                        interaction = panelInteraction,
                        bmpWidth = imgW,
                        bmpHeight = imgH,
                        viewScale = zoom.value * fitScale,
                        toolActive = tool == Tool.PANEL,
                    )
                }

            }

            if (pickerAnimProgress > 0.001f) {
                drawColorLoupe(
                    pickerActive = true,
                    pickerScreenPos = pickerScreenPos.value,
                    pickerInitialColor = pickerInitialColor.value,
                    pickerCurrentColor = pickerCurrentColor.value,
                    animProgress = pickerAnimProgress,
                    modePulseProgress = pickerModePulseAnim.value,
                    sampleLayersMode = vm.pickerSampleLayers,
                    modeLabel = pickerModeLabel,
                    loupeTextPaint = loupeTextPaint,
                )
            }
        }
}
