/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.AspectRatioPreset
import com.reverie.paint.model.CanvasAdjustMode
import com.reverie.paint.model.CanvasAdjustState
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class CropHandle {
    NONE,
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    MOVE_BOX,
}

@Composable
fun CanvasAdjustOverlay(
    vm: PaintViewModel,
    zoom: State<Float>,
    rotation: State<Float>,
    panX: State<Float>,
    panY: State<Float>,
    fitScale: Float,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val handleTouchRadiusPx = with(density) { 34.dp.toPx() }
    val bracketLenPx = with(density) { 16.dp.toPx() }
    val bracketThickPx = with(density) { 3.dp.toPx() }
    val pillLenPx = with(density) { 16.dp.toPx() }
    val pillThickPx = with(density) { 3.dp.toPx() }

    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }
    var initialCropX by remember { mutableStateOf(0) }
    var initialCropY by remember { mutableStateOf(0) }
    var initialTargetW by remember { mutableStateOf(1000) }
    var initialTargetH by remember { mutableStateOf(1000) }

    // 8 自由度拉伸 / 整体平移: 与原先 onDrag 逻辑完全一致, 只是抽出来供手势循环复用
    val applyHandleDrag: (screenPos: Offset, viewW: Int, viewH: Int) -> Unit = { screenPos, viewW, viewH ->
        if (activeHandle != CropHandle.NONE) {
            val docW = vm.docWidth
            val docH = vm.docHeight
            val bmpW = if (vm.renderW > 0) vm.renderW else docW
            val bmpH = if (vm.renderH > 0) vm.renderH else docH

            val currentDocPt = widgetToImage(
                screenPos,
                viewW,
                viewH,
                panX.value,
                panY.value,
                zoom.value,
                fitScale,
                rotation.value,
                bmpW,
                bmpH,
                docW,
                docH,
                vm.viewFlipX,
                vm.viewFlipY,
            )
            val startDocPt = widgetToImage(
                dragStartPos,
                viewW,
                viewH,
                panX.value,
                panY.value,
                zoom.value,
                fitScale,
                rotation.value,
                bmpW,
                bmpH,
                docW,
                docH,
                vm.viewFlipX,
                vm.viewFlipY,
            )

            val dX = (currentDocPt.x - startDocPt.x).roundToInt()
            val dY = (currentDocPt.y - startDocPt.y).roundToInt()
            val state = vm.canvasAdjustState

            if (activeHandle == CropHandle.MOVE_BOX) {
                // 平移整个裁切矩形框
                vm.canvasAdjustState = state.copy(
                    cropX = initialCropX + dX,
                    cropY = initialCropY + dY,
                )
            } else {
                // 8 自由度拉伸处理
                var newLeft = initialCropX
                var newTop = initialCropY
                var newRight = initialCropX + initialTargetW
                var newBottom = initialCropY + initialTargetH

                when (activeHandle) {
                    CropHandle.TOP_LEFT -> {
                        newLeft += dX
                        newTop += dY
                    }
                    CropHandle.TOP_CENTER -> {
                        newTop += dY
                    }
                    CropHandle.TOP_RIGHT -> {
                        newRight += dX
                        newTop += dY
                    }
                    CropHandle.CENTER_LEFT -> {
                        newLeft += dX
                    }
                    CropHandle.CENTER_RIGHT -> {
                        newRight += dX
                    }
                    CropHandle.BOTTOM_LEFT -> {
                        newLeft += dX
                        newBottom += dY
                    }
                    CropHandle.BOTTOM_CENTER -> {
                        newBottom += dY
                    }
                    CropHandle.BOTTOM_RIGHT -> {
                        newRight += dX
                        newBottom += dY
                    }
                    else -> {}
                }

                // 约束最小宽度与高度
                if (newRight - newLeft < 10) {
                    if (activeHandle == CropHandle.TOP_LEFT || activeHandle == CropHandle.CENTER_LEFT || activeHandle == CropHandle.BOTTOM_LEFT) {
                        newLeft = newRight - 10
                    } else {
                        newRight = newLeft + 10
                    }
                }
                if (newBottom - newTop < 10) {
                    if (activeHandle == CropHandle.TOP_LEFT || activeHandle == CropHandle.TOP_CENTER || activeHandle == CropHandle.TOP_RIGHT) {
                        newTop = newBottom - 10
                    } else {
                        newBottom = newTop + 10
                    }
                }

                var finalW = newRight - newLeft
                var finalH = newBottom - newTop

                // 比例锁定约束
                if (state.lockAspectRatio || state.selectedPreset != AspectRatioPreset.FREE) {
                    val ratio = if (state.selectedPreset == AspectRatioPreset.ORIGINAL) {
                        docW.toFloat() / maxOf(1, docH)
                    } else {
                        initialTargetW.toFloat() / initialTargetH
                    }

                    when (activeHandle) {
                        CropHandle.CENTER_LEFT, CropHandle.CENTER_RIGHT -> {
                            finalH = (finalW / ratio).roundToInt().coerceAtLeast(10)
                            val midY = (newTop + newBottom) / 2
                            newTop = midY - finalH / 2
                            newBottom = newTop + finalH
                        }
                        CropHandle.TOP_CENTER, CropHandle.BOTTOM_CENTER -> {
                            finalW = (finalH * ratio).roundToInt().coerceAtLeast(10)
                            val midX = (newLeft + newRight) / 2
                            newLeft = midX - finalW / 2
                            newRight = newLeft + finalW
                        }
                        CropHandle.TOP_LEFT -> {
                            finalH = (finalW / ratio).roundToInt().coerceAtLeast(10)
                            newTop = newBottom - finalH
                        }
                        CropHandle.TOP_RIGHT -> {
                            finalH = (finalW / ratio).roundToInt().coerceAtLeast(10)
                            newTop = newBottom - finalH
                        }
                        CropHandle.BOTTOM_LEFT -> {
                            finalH = (finalW / ratio).roundToInt().coerceAtLeast(10)
                            newBottom = newTop + finalH
                        }
                        CropHandle.BOTTOM_RIGHT -> {
                            finalH = (finalW / ratio).roundToInt().coerceAtLeast(10)
                            newBottom = newTop + finalH
                        }
                        else -> {}
                    }
                }

                vm.canvasAdjustState = state.withDirectCrop(
                    x = newLeft,
                    y = newTop,
                    w = finalW,
                    h = finalH,
                )
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 交互铁律: 本覆盖层铺满整个画布区, 一旦无条件消费指针事件就会吞掉
                // 下层 CanvasTouchView 的平移与缩放。因此只有"确认命中裁切手柄或
                // 裁切框内部"时才消费事件, 其余情况一律不消费, 让事件下沉到画布:
                //   · 空格抓手 (vm.isSpacePanning)      → 画布平移
                //   · 多指手势 (捏合缩放 / 双指平移)     → 画布变换
                //   · 裁切框之外的空白区域 (遮罩区)      → 画布平移
                // 注意: pointerInput 不 key 在 canvasAdjustState 上, 否则状态一变
                // 手势协程就被取消 (与 CanvasView 的教训一致)。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // 图像缩放模式无裁切框可交互; 空格按住时整体让位给抓手平移
                    if (vm.canvasAdjustState.mode != CanvasAdjustMode.CROP_EXPAND || vm.isSpacePanning) {
                        return@awaitEachGesture
                    }

                    // 第二根手指立刻落下 → 判定为捏合/双指平移, 交还画布
                    var event = awaitPointerEvent()
                    if (event.changes.size > 1) return@awaitEachGesture

                    val handle = resolveCropHandle(
                        pos = down.position,
                        viewW = size.width,
                        viewH = size.height,
                        state = vm.canvasAdjustState,
                        docW = vm.docWidth,
                        docH = vm.docHeight,
                        bmpW = if (vm.renderW > 0) vm.renderW else vm.docWidth,
                        bmpH = if (vm.renderH > 0) vm.renderH else vm.docHeight,
                        panX = panX.value,
                        panY = panY.value,
                        zoom = zoom.value,
                        fitScale = fitScale,
                        rotation = rotation.value,
                        handleTouchRadiusPx = handleTouchRadiusPx,
                        flipX = vm.viewFlipX,
                        flipY = vm.viewFlipY,
                    )

                    // 空白区域: 不消费, 事件下沉给画布平移
                    if (handle == CropHandle.NONE) return@awaitEachGesture

                    val startState = vm.canvasAdjustState
                    activeHandle = handle
                    dragStartPos = down.position
                    initialCropX = startState.cropX
                    initialCropY = startState.cropY
                    initialTargetW = startState.targetWidth
                    initialTargetH = startState.targetHeight
                    down.consume()

                    while (true) {
                        event = awaitPointerEvent()
                        // 拖拽过程中加入第二指: 立即放手, 让画布接管捏合缩放
                        if (event.changes.size > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        applyHandleDrag(change.position, size.width, size.height)
                    }
                    activeHandle = CropHandle.NONE
                }
            },
    ) {
        val state = vm.canvasAdjustState
        val docW = vm.docWidth
        val docH = vm.docHeight
        val bmpW = if (vm.renderW > 0) vm.renderW else docW
        val bmpH = if (vm.renderH > 0) vm.renderH else docH
        val viewW = size.width.toInt()
        val viewH = size.height.toInt()

        val leftDoc = state.cropX.toFloat()
        val topDoc = state.cropY.toFloat()
        val rightDoc = (state.cropX + state.targetWidth).toFloat()
        val bottomDoc = (state.cropY + state.targetHeight).toFloat()
        val midXDoc = (leftDoc + rightDoc) / 2f
        val midYDoc = (topDoc + bottomDoc) / 2f

        // 计算裁切框 4 个角在屏幕坐标系中的位置
    val toScreen = { docPt: Offset ->
        imageToWidget(
            docPt,
            viewW,
            viewH,
            panX.value,
            panY.value,
            zoom.value,
            fitScale,
            rotation.value,
            bmpW,
            bmpH,
            docW,
            docH,
            vm.viewFlipX,
            vm.viewFlipY,
        )
    }

        val pTL = toScreen(Offset(leftDoc, topDoc))
        val pTR = toScreen(Offset(rightDoc, topDoc))
        val pBR = toScreen(Offset(rightDoc, bottomDoc))
        val pBL = toScreen(Offset(leftDoc, bottomDoc))

        val pTC = toScreen(Offset(midXDoc, topDoc))
        val pBC = toScreen(Offset(midXDoc, bottomDoc))
        val pCL = toScreen(Offset(leftDoc, midYDoc))
        val pCR = toScreen(Offset(rightDoc, midYDoc))

        // 1. 绘制外部暗色反向遮罩 (穿孔镂空)
        val holePath = Path().apply {
            moveTo(pTL.x, pTL.y)
            lineTo(pTR.x, pTR.y)
            lineTo(pBR.x, pBR.y)
            lineTo(pBL.x, pBL.y)
            close()
        }

        drawContext.canvas.saveLayer(
            Rect(0f, 0f, size.width, size.height),
            Paint(),
        )
        // 外部 50% 黑色遮罩
        drawRect(color = Color.Black.copy(alpha = 0.50f))
        // 镂空裁切框内部
        drawPath(holePath, color = Color.White, blendMode = BlendMode.Clear)
        drawContext.canvas.restore()

        // 2. 绘制裁切边框外轮廓线 (双层：内白外黑确保任何底色下均清晰可见)
        val borderPath = Path().apply {
            moveTo(pTL.x, pTL.y)
            lineTo(pTR.x, pTR.y)
            lineTo(pBR.x, pBR.y)
            lineTo(pBL.x, pBL.y)
            close()
        }
        drawPath(borderPath, color = Color(0x66000000), style = Stroke(width = 3.dp.toPx()))
        drawPath(borderPath, color = Color.White.copy(alpha = 0.95f), style = Stroke(width = 1.5.dp.toPx()))

        // 3. 绘制九宫格三等分参考线
        for (i in 1..2) {
            val f = i / 3f
            // 横线
            val h1 = toScreen(Offset(leftDoc, topDoc + (bottomDoc - topDoc) * f))
            val h2 = toScreen(Offset(rightDoc, topDoc + (bottomDoc - topDoc) * f))
            drawLine(Color.White.copy(alpha = 0.35f), h1, h2, strokeWidth = 1.dp.toPx())

            // 竖线
            val v1 = toScreen(Offset(leftDoc + (rightDoc - leftDoc) * f, topDoc))
            val v2 = toScreen(Offset(leftDoc + (rightDoc - leftDoc) * f, bottomDoc))
            drawLine(Color.White.copy(alpha = 0.35f), v1, v2, strokeWidth = 1.dp.toPx())
        }

        // 4. 绘制 8 个控制手柄 (在裁切模式下显示)
        if (state.mode == CanvasAdjustMode.CROP_EXPAND) {
            // 四个直角 L 型手柄
            drawCornerBracket(pTL, pTR, pBL, bracketLenPx, bracketThickPx)
            drawCornerBracket(pTR, pBR, pTL, bracketLenPx, bracketThickPx)
            drawCornerBracket(pBR, pBL, pTR, bracketLenPx, bracketThickPx)
            drawCornerBracket(pBL, pTL, pBR, bracketLenPx, bracketThickPx)

            // 四条边居中药丸手柄
            drawEdgePill(pTC, pTL, pTR, pillLenPx, pillThickPx)
            drawEdgePill(pBC, pBL, pBR, pillLenPx, pillThickPx)
            drawEdgePill(pCL, pTL, pBL, pillLenPx, pillThickPx)
            drawEdgePill(pCR, pTR, pBR, pillLenPx, pillThickPx)
        }

        // 5. 绘制悬浮尺寸气泡徽标 (在顶部中心手柄上方居中)
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 12.dp.toPx()
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val bgPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(210, 24, 26, 32)
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }

        val maxLayers = com.reverie.paint.ui.create.calculateRealMaxLayers(context, state.targetWidth, state.targetHeight)
        val maxLayersDesc = context.getString(R.string.create_max_layers_desc, maxLayers)
        val dimText = "${state.targetWidth} × ${state.targetHeight} px  ·  $maxLayersDesc"
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(dimText, 0, dimText.length, textBounds)
        val badgeW = textBounds.width() + 24.dp.toPx()
        val badgeH = textBounds.height() + 14.dp.toPx()

        // 徽标摆放在裁切框顶部中央外侧 ~22dp
        val badgeCenter = pTC + Offset(0f, -22.dp.toPx())
        val badgeRect = android.graphics.RectF(
            badgeCenter.x - badgeW / 2f,
            badgeCenter.y - badgeH / 2f,
            badgeCenter.x + badgeW / 2f,
            badgeCenter.y + badgeH / 2f,
        )
        drawContext.canvas.nativeCanvas.drawRoundRect(badgeRect, 8.dp.toPx(), 8.dp.toPx(), bgPaint)
        drawContext.canvas.nativeCanvas.drawText(
            dimText,
            badgeCenter.x,
            badgeCenter.y + textBounds.height() / 2f - 2.dp.toPx(),
            textPaint,
        )
    }
}

/**
 * 命中测试: 判断触点落在哪个裁切手柄上。
 * 返回 NONE 表示该点既不在手柄上也不在裁切框内, 事件应下沉给画布。
 */
private fun resolveCropHandle(
    pos: Offset,
    viewW: Int,
    viewH: Int,
    state: CanvasAdjustState,
    docW: Int,
    docH: Int,
    bmpW: Int,
    bmpH: Int,
    panX: Float,
    panY: Float,
    zoom: Float,
    fitScale: Float,
    rotation: Float,
    handleTouchRadiusPx: Float,
    flipX: Boolean = false,
    flipY: Boolean = false,
): CropHandle {
    val leftDoc = state.cropX.toFloat()
    val topDoc = state.cropY.toFloat()
    val rightDoc = (state.cropX + state.targetWidth).toFloat()
    val bottomDoc = (state.cropY + state.targetHeight).toFloat()
    val midXDoc = (leftDoc + rightDoc) / 2f
    val midYDoc = (topDoc + bottomDoc) / 2f

    val toScreen = { docPt: Offset ->
        imageToWidget(
            docPt,
            viewW,
            viewH,
            panX,
            panY,
            zoom,
            fitScale,
            rotation,
            bmpW,
            bmpH,
            docW,
            docH,
            flipX,
            flipY,
        )
    }

    val handleMap = mapOf(
        CropHandle.TOP_LEFT to Offset(leftDoc, topDoc),
        CropHandle.TOP_CENTER to Offset(midXDoc, topDoc),
        CropHandle.TOP_RIGHT to Offset(rightDoc, topDoc),
        CropHandle.CENTER_LEFT to Offset(leftDoc, midYDoc),
        CropHandle.CENTER_RIGHT to Offset(rightDoc, midYDoc),
        CropHandle.BOTTOM_LEFT to Offset(leftDoc, bottomDoc),
        CropHandle.BOTTOM_CENTER to Offset(midXDoc, bottomDoc),
        CropHandle.BOTTOM_RIGHT to Offset(rightDoc, bottomDoc),
    )

    var nearestHandle = CropHandle.NONE
    var minDist = handleTouchRadiusPx
    for ((handle, docPt) in handleMap) {
        val screenPt = toScreen(docPt)
        val dist = hypot(pos.x - screenPt.x, pos.y - screenPt.y)
        if (dist < minDist) {
            minDist = dist
            nearestHandle = handle
        }
    }

    if (nearestHandle != CropHandle.NONE) return nearestHandle

    // 落在裁切框内部 → 整体平移裁切框
    val docPt = widgetToImage(
        pos,
        viewW,
        viewH,
        panX,
        panY,
        zoom,
        fitScale,
        rotation,
        bmpW,
        bmpH,
        docW,
        docH,
        flipX,
        flipY,
    )
    return if (docPt.x in leftDoc..rightDoc && docPt.y in topDoc..bottomDoc) {
        CropHandle.MOVE_BOX
    } else {
        CropHandle.NONE
    }
}

/**
 * 绘制转角 L 型手柄
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerBracket(
    corner: Offset,
    toward1: Offset,
    toward2: Offset,
    length: Float,
    thickness: Float,
) {
    val dir1 = normalize(toward1 - corner) * length
    val dir2 = normalize(toward2 - corner) * length

    val path = Path().apply {
        moveTo(corner.x + dir1.x, corner.y + dir1.y)
        lineTo(corner.x, corner.y)
        lineTo(corner.x + dir2.x, corner.y + dir2.y)
    }

    // 阴影底色
    drawPath(path, color = Color(0x66000000), style = Stroke(width = thickness + 2.dp.toPx()))
    // 亮白前景
    drawPath(path, color = Color.White, style = Stroke(width = thickness))
}

/**
 * 绘制边中点药丸手柄
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgePill(
    center: Offset,
    p1: Offset,
    p2: Offset,
    length: Float,
    thickness: Float,
) {
    val dir = normalize(p2 - p1) * (length / 2f)
    val start = center - dir
    val end = center + dir

    // 阴影底色
    drawLine(Color(0x66000000), start, end, strokeWidth = thickness + 2.dp.toPx())
    // 亮白前景
    drawLine(Color.White, start, end, strokeWidth = thickness)
}

private fun normalize(offset: Offset): Offset {
    val len = hypot(offset.x, offset.y)
    return if (len > 0.0001f) Offset(offset.x / len, offset.y / len) else Offset.Zero
}
