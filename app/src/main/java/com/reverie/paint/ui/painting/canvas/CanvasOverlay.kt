/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.Morandi
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
    wandFlash: androidx.compose.runtime.MutableState<Offset?>,
    liveSelectionPath: androidx.compose.runtime.MutableState<androidx.compose.ui.graphics.Path?>,
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
    val selectionCache = remember { SelectionOverlayCache() }
    val measureCache = remember { MeasureOverlayCache() }

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

    val textEmptyPlaceholder = stringResource(com.reverie.paint.R.string.typography_empty_placeholder)

    Canvas(Modifier.fillMaxSize()) {
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
        // 上游 ed32b1a8 口径 (瓦片网格 → 整图 → 文档)
        val bmpW = tiles?.grid?.width ?: displayBmp?.width ?: vm.docWidth.takeIf { it > 0 } ?: 1
        val bmpH = tiles?.grid?.height ?: displayBmp?.height ?: vm.docHeight.takeIf { it > 0 } ?: 1
        val imgW = bmpW.toFloat()
        val imgH = bmpH.toFloat()
        if (imgW <= 0f || imgH <= 0f) return@Canvas

        val currentScale = (zoom.value * fitScale).coerceAtLeast(0.001f)
        val rotationDeg = rotation.value
        val center = Offset(size.width / 2f + panX.value, size.height / 2f + panY.value)
        withTransform({
            translate(center.x, center.y)
            rotate(rotationDeg, pivot = Offset.Zero)
            scale(currentScale, currentScale, pivot = Offset.Zero)
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
                    dstOffset = IntOffset(-bmpW / 2, -bmpH / 2),
                    dstSize = IntSize(bmpW, bmpH),
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

            // Magic-wand tap flash: instant feedback ring in document space
            wandFlash.value?.let { wf ->
                val scX = if (vm.docWidth > 0) bmpW.toFloat() / vm.docWidth else 1f
                val scY = if (vm.docHeight > 0) bmpH.toFloat() / vm.docHeight else 1f
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 6.dp.toPx(),
                    center = Offset(wf.x * scX - bmpW / 2f, wf.y * scY - bmpH / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                )
            }

            if (tool == Tool.MEASURE) {
                val ms = measureStart.value
                val me = measureEnd.value
                if (ms != null && me != null) {
                    drawMeasureOverlay(vm, bmpW, bmpH, ms, me, currentScale, flipX, flipY, measureCache)
                }
            }

            cropRect?.let { cr ->
                drawCropOverlay(vm, bmpW, bmpH, cr)
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

            if (polyPoints.isNotEmpty()) {
                drawPolygonToolOverlay(vm, tool, bmpW, bmpH, polyPoints, currentScale, animFraction)
            }

            val lsStart = liveShapeStart.value
            val lsEnd = liveShapeEnd.value
            if (lsStart != null && lsEnd != null) {
                drawLiveShapePreview(vm, tool, bmpW, bmpH, lsStart, lsEnd, currentScale, animFraction)
            }

            drawInteractiveShapeOverlay(vm, bmpW, bmpH, currentScale)
            drawInteractiveGradientOverlay(vm, bmpW, bmpH, currentScale)
            drawInteractiveTypographyOverlay(vm, bmpW, bmpH, currentScale, textEmptyPlaceholder)

            drawSelectionAndLassoOverlay(
                vm = vm,
                tool = tool,
                bmpW = bmpW,
                bmpH = bmpH,
                polyPoints = polyPoints,
                liveShapeStart = liveShapeStart,
                liveShapeEnd = liveShapeEnd,
                liveSelectionPath = liveSelectionPath,
                animFraction = animFraction,
                selectionFlashProgress = selectionFlashProgress,
                currentScale = currentScale,
                rotationDeg = rotationDeg,
                center = center,
                flipX = flipX,
                flipY = flipY,
                cache = selectionCache,
            )

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
                    viewScale = currentScale,
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
