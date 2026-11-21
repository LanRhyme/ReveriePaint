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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.AspectRatioPreset
import com.reverie.paint.model.CanvasAdjustMode
import com.reverie.paint.model.CanvasAdjustState
import com.reverie.paint.model.RotationSnapGesture
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
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
    onViewTransform: (zoom: Float, rotation: Float, panX: Float, panY: Float) -> Unit = { _, _, _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
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

    val viewportGesture = remember { CanvasAdjustViewportGesture() }

    // 8 自由度拉伸 / 整体平移 (裁切与扩展模式)
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

    // 4 角手柄拖动实时等比/自由缩放图像尺寸 (图像缩放模式)
    val applyScaleHandleDrag: (screenPos: Offset, viewW: Int, viewH: Int) -> Unit = { screenPos, viewW, viewH ->
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

            val state = vm.canvasAdjustState
            val centerDocX = docW / 2f
            val centerDocY = docH / 2f

            val halfTargetW = abs(currentDocPt.x - centerDocX)
            val halfTargetH = abs(currentDocPt.y - centerDocY)

            val newW: Int
            val newH: Int
            if (state.lockAspectRatio) {
                val origW = maxOf(1, state.origWidth)
                val origH = maxOf(1, state.origHeight)
                val scaleW = halfTargetW / (origW / 2f)
                val scaleH = halfTargetH / (origH / 2f)
                val scale = maxOf(scaleW, scaleH).coerceIn(0.01f, 16f)
                newW = (origW * scale).roundToInt().coerceIn(1, 16384)
                newH = (origH * scale).roundToInt().coerceIn(1, 16384)
            } else {
                newW = (halfTargetW * 2f).roundToInt().coerceIn(1, 16384)
                newH = (halfTargetH * 2f).roundToInt().coerceIn(1, 16384)
            }

            vm.canvasAdjustState = state.copy(
                targetWidth = newW,
                targetHeight = newH,
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 交互规范 (对标画世界 Pro 与 Issue #200):
                // 1. 双指触控: 无论落在何处 (手柄、区域内或暗部空白) 均无缝触发视口平移与捏合缩放;
                // 2. 单指触控:
                //    - 命中手柄或内部区域: 拖拉变形或移动裁切框;
                //    - 落在空白暗部或抓手状态: 顺滑平移画布;
                // 3. 单指拖动手柄中落下第二指: 立即解除手柄捕获, 平滑交由双指驱动画布变换。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val viewW = size.width
                    val viewH = size.height
                    val docW = vm.docWidth
                    val docH = vm.docHeight
                    val bmpW = if (vm.renderW > 0) vm.renderW else docW
                    val bmpH = if (vm.renderH > 0) vm.renderH else docH

                    val isResample = vm.canvasAdjustState.mode == CanvasAdjustMode.RESAMPLE_SCALE
                    var twoFingerActive = false
                    viewportGesture.reset()

                    val initialHandle = if (vm.isSpacePanning) {
                        CropHandle.NONE
                    } else if (isResample) {
                        resolveScaleHandle(
                            pos = down.position,
                            viewW = viewW,
                            viewH = viewH,
                            state = vm.canvasAdjustState,
                            docW = docW,
                            docH = docH,
                            bmpW = bmpW,
                            bmpH = bmpH,
                            panX = panX.value,
                            panY = panY.value,
                            zoom = zoom.value,
                            fitScale = fitScale,
                            rotation = rotation.value,
                            handleTouchRadiusPx = handleTouchRadiusPx,
                            flipX = vm.viewFlipX,
                            flipY = vm.viewFlipY,
                        )
                    } else {
                        resolveCropHandle(
                            pos = down.position,
                            viewW = viewW,
                            viewH = viewH,
                            state = vm.canvasAdjustState,
                            docW = docW,
                            docH = docH,
                            bmpW = bmpW,
                            bmpH = bmpH,
                            panX = panX.value,
                            panY = panY.value,
                            zoom = zoom.value,
                            fitScale = fitScale,
                            rotation = rotation.value,
                            handleTouchRadiusPx = handleTouchRadiusPx,
                            flipX = vm.viewFlipX,
                            flipY = vm.viewFlipY,
                        )
                    }

                    activeHandle = initialHandle
                    dragStartPos = down.position
                    val startState = vm.canvasAdjustState
                    initialCropX = startState.cropX
                    initialCropY = startState.cropY
                    initialTargetW = startState.targetWidth
                    initialTargetH = startState.targetHeight

                    var lastScreenPos = down.position

                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        var first: PointerInputChange? = null
                        var second: PointerInputChange? = null

                        for (i in event.changes.indices) {
                            val p = event.changes[i]
                            if (!p.pressed) continue
                            if (first == null) first = p else { second = p; break }
                        }

                        if (first != null && second != null) {
                            // 多指捏合/平移/旋转手势: 直接驱动画布变换 (Issue #200)
                            twoFingerActive = true
                            activeHandle = CropHandle.NONE

                            val snapThreshold = if (vm.canvasRotationEnabled && !vm.isViewTransformLocked) vm.canvasRotationSnapDegrees else 0f
                            if (viewportGesture.update(
                                    firstPointerId = first.id.value,
                                    firstX = first.position.x,
                                    firstY = first.position.y,
                                    secondPointerId = second.id.value,
                                    secondX = second.position.x,
                                    secondY = second.position.y,
                                    viewWidth = viewW,
                                    viewHeight = viewH,
                                    currentZoom = zoom.value,
                                    currentRotation = rotation.value,
                                    currentPanX = panX.value,
                                    currentPanY = panY.value,
                                    rotationEnabled = vm.canvasRotationEnabled,
                                    snapThreshold = snapThreshold,
                                    locked = vm.isViewTransformLocked,
                                    onSnapEngaged = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    },
                                )
                            ) {
                                onViewTransform(
                                    viewportGesture.zoom,
                                    viewportGesture.rotation,
                                    viewportGesture.panX,
                                    viewportGesture.panY,
                                )
                            }
                        } else {
                            viewportGesture.reset()

                            if (!twoFingerActive) {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null && change.pressed) {
                                    if (vm.isSpacePanning || initialHandle == CropHandle.NONE) {
                                        // 空白区域/抓手状态下的单指拖拽: 平移画布
                                        val deltaX = change.position.x - lastScreenPos.x
                                        val deltaY = change.position.y - lastScreenPos.y
                                        if (deltaX != 0f || deltaY != 0f) {
                                            onViewTransform(
                                                zoom.value,
                                                rotation.value,
                                                panX.value + deltaX,
                                                panY.value + deltaY,
                                            )
                                        }
                                    } else {
                                        // 命中控制柄或内部区域: 驱动裁切/缩放变形
                                        if (isResample) {
                                            applyScaleHandleDrag(change.position, viewW, viewH)
                                        } else {
                                            applyHandleDrag(change.position, viewW, viewH)
                                        }
                                    }
                                    lastScreenPos = change.position
                                }
                            }
                        }

                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })

                    activeHandle = CropHandle.NONE
                    viewportGesture.reset()
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

        if (state.mode == CanvasAdjustMode.CROP_EXPAND) {
            // ==========================================
            // 模式 1: 裁切与扩展 (CROP_EXPAND)
            // ==========================================
            val leftDoc = state.cropX.toFloat()
            val topDoc = state.cropY.toFloat()
            val rightDoc = (state.cropX + state.targetWidth).toFloat()
            val bottomDoc = (state.cropY + state.targetHeight).toFloat()
            val midXDoc = (leftDoc + rightDoc) / 2f
            val midYDoc = (topDoc + bottomDoc) / 2f

            val pTL = toScreen(Offset(leftDoc, topDoc))
            val pTR = toScreen(Offset(rightDoc, topDoc))
            val pBR = toScreen(Offset(rightDoc, bottomDoc))
            val pBL = toScreen(Offset(leftDoc, bottomDoc))

            val pTC = toScreen(Offset(midXDoc, topDoc))
            val pBC = toScreen(Offset(midXDoc, bottomDoc))
            val pCL = toScreen(Offset(leftDoc, midYDoc))
            val pCR = toScreen(Offset(rightDoc, midYDoc))

            // 1. 外部暗色反向遮罩 (穿孔镂空)
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
            drawRect(color = Color.Black.copy(alpha = 0.50f))
            drawPath(holePath, color = Color.White, blendMode = BlendMode.Clear)
            drawContext.canvas.restore()

            // 2. 裁切边框外轮廓线 (双层：内白外黑确保任何底色下均清晰可见)
            val borderPath = Path().apply {
                moveTo(pTL.x, pTL.y)
                lineTo(pTR.x, pTR.y)
                lineTo(pBR.x, pBR.y)
                lineTo(pBL.x, pBL.y)
                close()
            }
            drawPath(borderPath, color = Color(0x66000000), style = Stroke(width = 3.dp.toPx()))
            drawPath(borderPath, color = Color.White.copy(alpha = 0.95f), style = Stroke(width = 1.5.dp.toPx()))

            // 3. 九宫格三等分参考线
            for (i in 1..2) {
                val f = i / 3f
                val h1 = toScreen(Offset(leftDoc, topDoc + (bottomDoc - topDoc) * f))
                val h2 = toScreen(Offset(rightDoc, topDoc + (bottomDoc - topDoc) * f))
                drawLine(Color.White.copy(alpha = 0.35f), h1, h2, strokeWidth = 1.dp.toPx())

                val v1 = toScreen(Offset(leftDoc + (rightDoc - leftDoc) * f, topDoc))
                val v2 = toScreen(Offset(leftDoc + (rightDoc - leftDoc) * f, bottomDoc))
                drawLine(Color.White.copy(alpha = 0.35f), v1, v2, strokeWidth = 1.dp.toPx())
            }

            // 4. 8 个控制手柄
            drawCornerBracket(pTL, pTR, pBL, bracketLenPx, bracketThickPx)
            drawCornerBracket(pTR, pBR, pTL, bracketLenPx, bracketThickPx)
            drawCornerBracket(pBR, pBL, pTR, bracketLenPx, bracketThickPx)
            drawCornerBracket(pBL, pTL, pBR, bracketLenPx, bracketThickPx)

            drawEdgePill(pTC, pTL, pTR, pillLenPx, pillThickPx)
            drawEdgePill(pBC, pBL, pBR, pillLenPx, pillThickPx)
            drawEdgePill(pCL, pTL, pBL, pillLenPx, pillThickPx)
            drawEdgePill(pCR, pTR, pBR, pillLenPx, pillThickPx)

            // 5. 悬浮尺寸徽标 (在顶部中心手柄上方居中)
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 12.dp.toPx()
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(215, 24, 26, 32)
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
        } else {
            // ==========================================
            // 模式 2: 图像缩放 (RESAMPLE_SCALE) 实时对比预览
            // ==========================================
            val centerDocX = docW / 2f
            val centerDocY = docH / 2f
            val halfW = state.targetWidth / 2f
            val halfH = state.targetHeight / 2f

            val targetLeft = centerDocX - halfW
            val targetTop = centerDocY - halfH
            val targetRight = centerDocX + halfW
            val targetBottom = centerDocY + halfH

            val pTL = toScreen(Offset(targetLeft, targetTop))
            val pTR = toScreen(Offset(targetRight, targetTop))
            val pBR = toScreen(Offset(targetRight, targetBottom))
            val pBL = toScreen(Offset(targetLeft, targetBottom))
            val pTC = toScreen(Offset(centerDocX, targetTop))

            val origTL = toScreen(Offset(0f, 0f))
            val origTR = toScreen(Offset(docW.toFloat(), 0f))
            val origBR = toScreen(Offset(docW.toFloat(), docH.toFloat()))
            val origBL = toScreen(Offset(0f, docH.toFloat()))

            val isDifferentSize = state.targetWidth != docW || state.targetHeight != docH

            // 1. 原图参考轮廓线 (半透明白色虚线)
            if (isDifferentSize) {
                val origPath = Path().apply {
                    moveTo(origTL.x, origTL.y)
                    lineTo(origTR.x, origTR.y)
                    lineTo(origBR.x, origBR.y)
                    lineTo(origBL.x, origBL.y)
                    close()
                }
                drawPath(
                    origPath,
                    color = Color.White.copy(alpha = 0.40f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
                )

                // 2. 4 角缩放投影参考线 (连接原图角点与目标角点)
                val rayColor = Morandi.accent.copy(alpha = 0.50f)
                val rayEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                drawLine(rayColor, origTL, pTL, strokeWidth = 1.2.dp.toPx(), pathEffect = rayEffect)
                drawLine(rayColor, origTR, pTR, strokeWidth = 1.2.dp.toPx(), pathEffect = rayEffect)
                drawLine(rayColor, origBR, pBR, strokeWidth = 1.2.dp.toPx(), pathEffect = rayEffect)
                drawLine(rayColor, origBL, pBL, strokeWidth = 1.2.dp.toPx(), pathEffect = rayEffect)
            }

            // 3. 目标缩放区域遮罩与高亮
            val targetPath = Path().apply {
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
            // 镂空目标缩放框
            drawPath(targetPath, color = Color.White, blendMode = BlendMode.Clear)
            drawContext.canvas.restore()

            // 4. 若为放大模式, 在原图到目标之间的扩展带填充淡强调色
            if (state.targetWidth > docW || state.targetHeight > docH) {
                val origPath = Path().apply {
                    moveTo(origTL.x, origTL.y)
                    lineTo(origTR.x, origTR.y)
                    lineTo(origBR.x, origBR.y)
                    lineTo(origBL.x, origBL.y)
                    close()
                }
                drawContext.canvas.saveLayer(
                    Rect(0f, 0f, size.width, size.height),
                    Paint(),
                )
                drawPath(targetPath, color = Morandi.accent.copy(alpha = 0.08f))
                drawPath(origPath, color = Color.White, blendMode = BlendMode.Clear)
                drawContext.canvas.restore()
            }

            // 5. 绘制目标尺寸边框 (外阴影内强调色)
            drawPath(targetPath, color = Color(0x66000000), style = Stroke(width = 3.dp.toPx()))
            drawPath(targetPath, color = Morandi.accent.copy(alpha = 0.95f), style = Stroke(width = 1.8.dp.toPx()))

            // 6. 九宫格参考线
            for (i in 1..2) {
                val f = i / 3f
                val h1 = toScreen(Offset(targetLeft, targetTop + (targetBottom - targetTop) * f))
                val h2 = toScreen(Offset(targetRight, targetTop + (targetBottom - targetTop) * f))
                drawLine(Color.White.copy(alpha = 0.25f), h1, h2, strokeWidth = 1.dp.toPx())

                val v1 = toScreen(Offset(targetLeft + (targetRight - targetLeft) * f, targetTop))
                val v2 = toScreen(Offset(targetLeft + (targetRight - targetLeft) * f, targetBottom))
                drawLine(Color.White.copy(alpha = 0.25f), v1, v2, strokeWidth = 1.dp.toPx())
            }

            // 7. 4 个角手柄 (转角 L 型手柄, 支持触控拖拉实时调尺寸)
            drawCornerBracket(pTL, pTR, pBL, bracketLenPx, bracketThickPx)
            drawCornerBracket(pTR, pBR, pTL, bracketLenPx, bracketThickPx)
            drawCornerBracket(pBR, pBL, pTR, bracketLenPx, bracketThickPx)
            drawCornerBracket(pBL, pTL, pBR, bracketLenPx, bracketThickPx)

            // 8. 悬浮尺寸与缩放徽标 (在顶部中心外侧)
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 12.dp.toPx()
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(215, 24, 26, 32)
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            }

            val scaleRatioX = state.targetWidth.toFloat() / maxOf(1, docW)
            val scaleRatioY = state.targetHeight.toFloat() / maxOf(1, docH)
            val ratioText = if (abs(scaleRatioX - scaleRatioY) < 0.005f) {
                "%.2f× (%d%%)".format(scaleRatioX, (scaleRatioX * 100).roundToInt())
            } else {
                "W: %.2f×, H: %.2f×".format(scaleRatioX, scaleRatioY)
            }
            val filterName = context.getString(state.resampleFilter.labelRes)
            val maxLayers = com.reverie.paint.ui.create.calculateRealMaxLayers(context, state.targetWidth, state.targetHeight)
            val maxLayersDesc = context.getString(R.string.create_max_layers_desc, maxLayers)

            val dimText = "${state.targetWidth} × ${state.targetHeight} px  ·  $ratioText  ·  $filterName  ·  $maxLayersDesc"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(dimText, 0, dimText.length, textBounds)
            val badgeW = textBounds.width() + 24.dp.toPx()
            val badgeH = textBounds.height() + 14.dp.toPx()

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

            // 9. 原图参考标签 (在尺寸改变时显示在原图左上角外侧)
            if (isDifferentSize) {
                val subTextPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(230, 200, 205, 215)
                    textSize = 10.dp.toPx()
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                    textAlign = android.graphics.Paint.Align.LEFT
                    isAntiAlias = true
                }
                val origDesc = "${docW} × ${docH} (${context.getString(R.string.filter_compare_original)})"
                val origBounds = android.graphics.Rect()
                subTextPaint.getTextBounds(origDesc, 0, origDesc.length, origBounds)
                val origBadgeW = origBounds.width() + 14.dp.toPx()
                val origBadgeH = origBounds.height() + 8.dp.toPx()
                val origBadgePos = origTL + Offset(0f, -14.dp.toPx())
                val origBadgeRect = android.graphics.RectF(
                    origBadgePos.x,
                    origBadgePos.y - origBadgeH,
                    origBadgePos.x + origBadgeW,
                    origBadgePos.y,
                )
                val subBgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 20, 22, 28)
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawRoundRect(origBadgeRect, 4.dp.toPx(), 4.dp.toPx(), subBgPaint)
                drawContext.canvas.nativeCanvas.drawText(
                    origDesc,
                    origBadgePos.x + 7.dp.toPx(),
                    origBadgePos.y - 4.dp.toPx(),
                    subTextPaint,
                )
            }
        }
    }
}

/**
 * 命中测试 (裁切模式): 判断触点落在哪个裁切手柄或裁切框内。
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
 * 命中测试 (图像缩放模式): 判断触点落在哪个角手柄上。
 */
private fun resolveScaleHandle(
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
    val centerDocX = docW / 2f
    val centerDocY = docH / 2f
    val halfW = state.targetWidth / 2f
    val halfH = state.targetHeight / 2f

    val targetLeft = centerDocX - halfW
    val targetTop = centerDocY - halfH
    val targetRight = centerDocX + halfW
    val targetBottom = centerDocY + halfH

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
        CropHandle.TOP_LEFT to Offset(targetLeft, targetTop),
        CropHandle.TOP_RIGHT to Offset(targetRight, targetTop),
        CropHandle.BOTTOM_LEFT to Offset(targetLeft, targetBottom),
        CropHandle.BOTTOM_RIGHT to Offset(targetRight, targetBottom),
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
    return nearestHandle
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

/**
 * 画布调整模式专属双指视口手势探测器:
 * 联动缩放、平移与旋转, 保持双指触控中心点绝对标定, 并集成 90° 吸附收敛与震动触觉反馈。
 */
private class CanvasAdjustViewportGesture {
    private var active = false
    private var firstId = 0L
    private var secondId = 0L
    private var previousX = 0f
    private var previousY = 0f
    private var previousDistance = 1f
    private var previousAngle = 0f

    var zoom = 1f
        private set
    var rotation = 0f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    val rotationSnapGesture = RotationSnapGesture()
    private var snapEngaged = false

    fun reset() {
        active = false
    }

    fun update(
        firstPointerId: Long,
        firstX: Float,
        firstY: Float,
        secondPointerId: Long,
        secondX: Float,
        secondY: Float,
        viewWidth: Int,
        viewHeight: Int,
        currentZoom: Float,
        currentRotation: Float,
        currentPanX: Float,
        currentPanY: Float,
        rotationEnabled: Boolean,
        snapThreshold: Float,
        locked: Boolean,
        onSnapEngaged: (() -> Unit)? = null,
    ): Boolean {
        if (firstPointerId == secondPointerId || viewWidth <= 0 || viewHeight <= 0 ||
            !firstX.isFinite() || !firstY.isFinite() || !secondX.isFinite() || !secondY.isFinite() ||
            !currentZoom.isFinite() || currentZoom <= 0f ||
            !currentRotation.isFinite() ||
            !currentPanX.isFinite() || !currentPanY.isFinite()
        ) {
            reset()
            return false
        }

        val centerX = (firstX + secondX) * 0.5f
        val centerY = (firstY + secondY) * 0.5f
        val distance = hypot(secondX - firstX, secondY - firstY).coerceAtLeast(1f)
        val angle = Math.toDegrees(kotlin.math.atan2((secondY - firstY).toDouble(), (secondX - firstX).toDouble())).toFloat()

        val samePair = active &&
            ((firstPointerId == firstId && secondPointerId == secondId) ||
                (firstPointerId == secondId && secondPointerId == firstId))

        var changed = false
        if (samePair) {
            val nextZoom = if (locked) currentZoom
                else (currentZoom * (distance / previousDistance)).coerceIn(0.02f, 128f)
            val actualK = if (currentZoom > 0.0001f) nextZoom / currentZoom else 1f

            val dRot: Float
            val nextRotation: Float
            if (rotationEnabled && !locked) {
                val rawDelta = normalizeAngle(angle - previousAngle).coerceIn(-15f, 15f)
                val safeDelta = if (rawDelta.isFinite()) rawDelta else 0f
                val snapped = rotationSnapGesture.update(safeDelta, snapThreshold)
                dRot = snapped - currentRotation
                nextRotation = snapped

                val engaged = rotationSnapGesture.isSnapped
                if (engaged && !snapEngaged) {
                    onSnapEngaged?.invoke()
                }
                snapEngaged = engaged
            } else {
                dRot = 0f
                nextRotation = currentRotation
            }

            val rad = Math.toRadians(dRot.toDouble())
            val cosR = kotlin.math.cos(rad).toFloat()
            val sinR = kotlin.math.sin(rad).toFloat()

            val vx = previousX - (viewWidth * 0.5f + currentPanX)
            val vy = previousY - (viewHeight * 0.5f + currentPanY)

            val vRotX = actualK * (vx * cosR - vy * sinR)
            val vRotY = actualK * (vx * sinR + vy * cosR)

            val nextPanX = centerX - vRotX - viewWidth * 0.5f
            val nextPanY = centerY - vRotY - viewHeight * 0.5f

            if (!nextZoom.isFinite() || !nextRotation.isFinite() || !nextPanX.isFinite() || !nextPanY.isFinite()) {
                reset()
                return false
            }

            zoom = nextZoom
            rotation = nextRotation
            panX = nextPanX
            panY = nextPanY
            changed = true
        } else {
            rotationSnapGesture.begin(currentRotation)
            snapEngaged = false
        }

        active = true
        firstId = firstPointerId
        secondId = secondPointerId
        previousX = centerX
        previousY = centerY
        previousDistance = distance
        previousAngle = angle
        return changed
    }
}

