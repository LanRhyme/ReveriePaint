/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.handleTouchEvent(event: MotionEvent): Boolean {
    val v = vm ?: return superOnTouchEvent(event)
    // 落笔立刻结束闪现提示, 免得新笔画与闪现叠加 (热路径: 仅一个 null 判断, 零分配)
    if (event.actionMasked == MotionEvent.ACTION_DOWN && v.layerFlashBitmap != null) {
        v.cancelLayerContentFlash()
    }
    if (v.isQuickShapeEditing) return true
    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_CANCEL) {
        removeCallbacks(quickShapeHold)
        quickShapeCandidate = null
        v.quickShapeCapture = null
    }
    if (event.actionMasked == MotionEvent.ACTION_DOWN && editMenuGestureActive) {
        removeCallbacks(continuousRedoRunnable)
        editMenuGestureActive = false
        isContinuousUndoing = false
        isTransformActive = false
        isPinchMotion = false
        maxTouchPointers = 0
    }

    // 空格键长按临时抓手平移 (Spacebar Hold-to-Pan)
    if (v.isSpacePanning) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 空格抓手平移同样写画布变换, 且此分支在统一取消点之前 return:
                // 这里先终止动画并失效待执行帧, 避免与旧动画同帧竞争
                cancelCanvasTransformAnimators()
                spacePanStartPos = Offset(event.x, event.y)
                spacePanInitialPan = Offset(canvasPanX, canvasPanY)
                isSpaceDragging = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemGrabPointer != null) {
                    pointerIcon = systemGrabPointer
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSpaceDragging) {
                    val dx = event.x - spacePanStartPos.x
                    val dy = event.y - spacePanStartPos.y
                    canvasPanX = spacePanInitialPan.x + dx
                    canvasPanY = spacePanInitialPan.y + dy
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSpaceDragging = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemHandPointer != null) {
                    pointerIcon = systemHandPointer
                }
            }
        }
        return true
    }

    // 旋转快捷交互 (Hold-to-Rotate, 默认 R 键按住)
    if (v.isRotateHolding) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelCanvasTransformAnimators()
                rotateHoldStartPos = Offset(event.x, event.y)
                rotateHoldStartDownTime = android.os.SystemClock.uptimeMillis()
                val cx = viewW / 2f
                val cy = viewH / 2f
                rotateHoldPrevAngle = Math.toDegrees(kotlin.math.atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())).toFloat()
                rotateHoldCumulativeRot = canvasRotation
                rotateHoldSnapped = false
                isRotateHoldingDragging = true
                isInteracting = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRotateHoldingDragging) {
                    val cx = viewW / 2f
                    val cy = viewH / 2f
                    val currentAngle = Math.toDegrees(kotlin.math.atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())).toFloat()
                    val dAngle = normalizeAngle(currentAngle - rotateHoldPrevAngle)
                    rotateHoldPrevAngle = currentAngle
                    rotateHoldCumulativeRot += dAngle
                    // Cardinal angle soft snapping: when within ±2° of 0°, 90°, 180°, 270°, snap to that angle
                    val target = RotationSnap.nearestMultiple(rotateHoldCumulativeRot)
                    val deltaToTarget = RotationSnap.shortestDelta(rotateHoldCumulativeRot, target)
                    val isNearSnap = kotlin.math.abs(deltaToTarget) <= 2f
                    val finalRot = if (isNearSnap) target else rotateHoldCumulativeRot
                    if (isNearSnap && !rotateHoldSnapped) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        onRotationSnap?.invoke(finalRot)
                    }
                    rotateHoldSnapped = isNearSnap
                    val dRot = finalRot - canvasRotation
                    if (dRot != 0f) {
                        val rad = Math.toRadians(dRot.toDouble())
                        val cosR = kotlin.math.cos(rad).toFloat()
                        val sinR = kotlin.math.sin(rad).toFloat()
                        val oldPanX = canvasPanX
                        val oldPanY = canvasPanY
                        canvasRotation = finalRot
                        canvasPanX = oldPanX * cosR - oldPanY * sinR
                        canvasPanY = oldPanX * sinR + oldPanY * cosR
                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isRotateHoldingDragging) {
                    isRotateHoldingDragging = false
                    val duration = android.os.SystemClock.uptimeMillis() - rotateHoldStartDownTime
                    val dist = kotlin.math.hypot(event.x - rotateHoldStartPos.x, event.y - rotateHoldStartPos.y)
                    val slop = 8f * density
                    if (duration < 300L && dist < slop) {
                        val dRot = 0f - canvasRotation
                        if (dRot != 0f) {
                            val rad = Math.toRadians(dRot.toDouble())
                            val cosR = kotlin.math.cos(rad).toFloat()
                            val sinR = kotlin.math.sin(rad).toFloat()
                            val oldPanX = canvasPanX
                            val oldPanY = canvasPanY
                            canvasRotation = 0f
                            canvasPanX = oldPanX * cosR - oldPanY * sinR
                            canvasPanY = oldPanX * sinR + oldPanY * cosR
                            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        }
                    }
                }
                isInteracting = false
                invalidate()
            }
        }
        return true
    }
    // 任何新的接触 (新手势第一个触点 / 新落下的触点) 都可能成为画布变换的新写入者:
    // 先终止吸附收敛与满屏复位动画并失效其待执行帧, 避免与手势同帧竞争画布变换
    val touchMask = event.actionMasked
    if (touchMask == MotionEvent.ACTION_DOWN || touchMask == MotionEvent.ACTION_POINTER_DOWN) {
        cancelCanvasTransformAnimators()
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val mask = event.actionMasked
        if (mask == MotionEvent.ACTION_DOWN || mask == MotionEvent.ACTION_POINTER_DOWN) {
            try {
                requestUnbufferedDispatch(event)
            } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    requestUnbufferedDispatch(android.view.InputDevice.SOURCE_STYLUS)
                    requestUnbufferedDispatch(android.view.InputDevice.SOURCE_TOUCHSCREEN)
                } catch (_: Throwable) {}
            }
        }
    }
    val pointerCount = event.pointerCount

    // 取消 pending 的撤销或会话重置
    pendingUndoRunnable?.let { removeCallbacks(it) }
    removeCallbacks(resetTransformRunnable)

    // 分离手写笔 Pointer 与手指 Pointer (零堆分配)
    fingerCount = 0
    var stylusPointerIndex = -1

    for (i in 0 until pointerCount) {
        val toolType = event.getToolType(i)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER || toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            stylusPointerIndex = i
        } else {
            if (fingerCount < fingerIndices.size) {
                fingerIndices[fingerCount++] = i
            }
        }
    }

    // 软开关：若已开启“禁用画布触控”，拦截一切手指交互，专供手写笔与鼠标操作
    if (v.isCanvasTouchDisabled) {
        if (stylusPointerIndex < 0) {
            // 纯手指接触：静默丢弃所有事件（无反馈、无弹窗、不作画、不手势）
            // 12 条强制复位路径之一：开启“禁用画布触控”拦截手指笔画。
            clearAllEvasionState(v)
            if (strokeStarted) {
                cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                cachedDriver?.feedbackManager?.stopStrokeSound()
                abortAndClearRealInk()
                v.touchCancel()
                strokeStarted = false
                safeEndSymmetryUndoMacro()
                resetMirrorBranches()
                clearPredictionState(triggerInvalidate = true)
                firstDocPos = Offset.Zero
                shapeEndDocPos = Offset.Zero
            }
            cancelPendingShapeGesture()
            cancelPanelGesture()
            isTransformActive = false
            isContinuousUndoing = false
            dismissPickerOverlay()
            return true
        }
        // 存在手写笔 Pointer 时，强制清除手指计数，避免手掌贴屏引发视口晃动或多指手势
        fingerCount = 0
    }

    // 手写笔触控判定：存在手写笔 Pointer 且未处于双指画布手势导航中
    // (只要当前未处于双指手势，任何手指接触均视为手掌接触，优先保证手写笔落笔防误触)
    val isStylusTouch = !editMenuGestureActive && stylusPointerIndex >= 0 && (!isTransformActive || fingerCount < 2)

    // 侧键按住=临时橡皮 (Samsung Notes 语义) 或 物理橡皮尾接触: 每个笔接触事件重判, 纯手指路径清残留
    if (isStylusTouch) {
        tempEraseActive = getOrCreateStylusDriver()?.isSideButtonEraseActive(event) == true
        physicalEraserActive = (event.getToolType(stylusPointerIndex) == MotionEvent.TOOL_TYPE_ERASER)
        lastSingleFingerTapTimeMs = 0L
        lastSingleFingerTapDrewStroke = false
    } else if (fingerCount > 0) {
        tempEraseActive = false
        physicalEraserActive = false
    }

    // =========================================================
    // 滤镜调节模式手势交互 (单指/笔横划调节参数，长按对比原图；双指保留视口变换)
    // =========================================================
    if (filterSessionActive) {
        if (fingerCount >= 2 || isTransformActive) {
            removeCallbacks(filterLongPressRunnable)
            if (isFilterComparing) {
                isFilterComparing = false
                onFilterHoldingCompare?.invoke(false)
            }
        } else {
            val pIdx = if (isStylusTouch) stylusPointerIndex else (if (fingerCount > 0) fingerIndices[0] else 0)
            val curX = event.getX(pIdx)
            val curY = event.getY(pIdx)
            val nowMs = SystemClock.uptimeMillis()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTimeMs = nowMs
                    filterTouchStartX = curX
                    filterTouchStartY = curY
                    previousSinglePos = Offset(curX, curY)
                    isFilterDragging = false
                    isFilterComparing = false
                    removeCallbacks(filterLongPressRunnable)
                    postDelayed(filterLongPressRunnable, 300)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = curX - previousSinglePos.x
                    val totalDist = hypot(curX - filterTouchStartX, curY - filterTouchStartY)
                    val touchSlop = 8f * density
                    if (totalDist > touchSlop) {
                        if (!isFilterDragging) {
                            isFilterDragging = true
                            removeCallbacks(filterLongPressRunnable)
                            if (isFilterComparing) {
                                isFilterComparing = false
                                onFilterHoldingCompare?.invoke(false)
                            }
                        }
                        previousSinglePos = Offset(curX, curY)
                        val deltaRatio = dx / (viewW.toFloat().coerceAtLeast(200f))
                        onFilterSlideDelta?.invoke(deltaRatio)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(filterLongPressRunnable)
                    if (isFilterComparing) {
                        isFilterComparing = false
                        onFilterHoldingCompare?.invoke(false)
                    }
                    isFilterDragging = false
                    if (strokeStarted) {
                        val doc = screenToDoc(Offset(curX, curY))
                        handleToolUp(
                            event,
                            doc,
                            isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL,
                            isStylus = isStylusTouch,
                        )
                    }
                }
            }
            return true
        }
    }

    // =========================================================
    // 3D 参考图层手势交互 (单指/笔旋转或选定模式，双指自由缩放平移)
    // =========================================================
    if (v.isCurrentLayer3D) {
        val state = v.currentLayer3DState
        if (state != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    v.isInteracting3D = true
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        touch3DStartState = state.copy()
                    }
                    if (fingerCount >= 2) {
                        touch3DIsPinch = true
                        val idx0 = fingerIndices[0]
                        val idx1 = fingerIndices[1]
                        val x0 = event.getX(idx0); val y0 = event.getY(idx0)
                        val x1 = event.getX(idx1); val y1 = event.getY(idx1)
                        touch3DPinchDist = hypot(x1 - x0, y1 - y0).coerceAtLeast(10f)
                        touch3DMidX = (x0 + x1) * 0.5f
                        touch3DMidY = (y0 + y1) * 0.5f
                    } else {
                        touch3DIsPinch = false
                        val pIdx = if (isStylusTouch) stylusPointerIndex else (if (fingerCount > 0) fingerIndices[0] else 0)
                        val sx = event.getX(pIdx)
                        val sy = event.getY(pIdx)
                        touch3DStartX = sx
                        touch3DStartY = sy
                        if (v.active3DInteractionMode == Interaction3DMode.BONE) {
                            val docPos = screenToDoc(Offset(sx, sy))
                            val relDocX = docPos.x - v.docWidth.toFloat() * 0.5f
                            val relDocY = docPos.y - v.docHeight.toFloat() * 0.5f
                            ensureViewTransform()
                            // 先看是否点在 X/Y 轴环上: 点中只锁轴, 不改选中关节
                            val ringAxis = canvas3DDrawer.hitTestAxisRing(
                                relDocX, relDocY,
                                tolerance = 22f / viewTransform.currentScale.coerceAtLeast(0.1f),
                            )
                            if (ringAxis >= 0 && v.active3DSelectedJointName != null) {
                                v.active3DBoneAxis = ringAxis
                                invalidate()
                                return true
                            }
                            v.active3DBoneAxis = -1
                            val hit = canvas3DDrawer.hitTestJoint(relDocX, relDocY, maxDist = 36f / viewTransform.currentScale.coerceAtLeast(0.1f))
                            // 连点同一根骨骼 = 归零该骨骼 (比划到面板更快)
                            val now = System.currentTimeMillis()
                            if (hit != null && hit == v.active3DSelectedJointName && now - lastBoneTapTime <= 350L) {
                                val cur = v.currentLayer3DState
                                if (cur != null) {
                                    val keys = listOf("${hit}_p", "${hit}_y", "${hit}_r")
                                    val kept = cur.jointAngles.filterKeys { it !in keys }
                                    if (kept.size != cur.jointAngles.size) {
                                        v.push3DUndoState(cur)
                                        v.update3DState(cur.copy(jointAngles = kept))
                                    }
                                }
                                lastBoneTapTime = 0L
                            } else {
                                lastBoneTapTime = if (hit != null) now else 0L
                            }
                            // 关节位置微调时不因一次未命中就丢失选择:
                            // 拖拽的按下点常常偏离球心几像素, 清空选择会让拖动退化成旋转模型
                            if (hit != null || !v.jointPositionEditMode) {
                                v.active3DSelectedJointName = hit
                            }
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touch3DIsPinch && fingerCount >= 2) {
                        val idx0 = fingerIndices[0]
                        val idx1 = fingerIndices[1]
                        val x0 = event.getX(idx0); val y0 = event.getY(idx0)
                        val x1 = event.getX(idx1); val y1 = event.getY(idx1)
                        val curDist = hypot(x1 - x0, y1 - y0).coerceAtLeast(10f)
                        val curMidX = (x0 + x1) * 0.5f
                        val curMidY = (y0 + y1) * 0.5f

                        val scaleFactor = curDist / touch3DPinchDist
                        val midDx = curMidX - touch3DMidX
                        val midDy = curMidY - touch3DMidY

                        touch3DPinchDist = curDist
                        touch3DMidX = curMidX
                        touch3DMidY = curMidY

                        val panSensitivity = 0.0035f / state.zoom.coerceAtLeast(0.1f)
                        val newZoom = (state.zoom * scaleFactor).coerceIn(0.1f, 10.0f)
                        val newPanX = (state.panX + midDx * panSensitivity).coerceIn(-5.0f, 5.0f)
                        val newPanY = (state.panY - midDy * panSensitivity).coerceIn(-5.0f, 5.0f)

                        val updated = state.copy(zoom = newZoom, panX = newPanX, panY = newPanY)
                        v.update3DState(updated)
                        invalidate()
                    } else if (!touch3DIsPinch) {
                        val pIdx = if (isStylusTouch) stylusPointerIndex else (if (fingerCount > 0) fingerIndices[0] else 0)
                        val curX = event.getX(pIdx)
                        val curY = event.getY(pIdx)
                        val dx = curX - touch3DStartX
                        val dy = curY - touch3DStartY
                        touch3DStartX = curX
                        touch3DStartY = curY
                        v.handle3DGesture(dx, dy)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val start = touch3DStartState
                        val end = v.currentLayer3DState
                        if (start != null && end != null && start != end) {
                            v.push3DUndoState(start)
                        }
                    }
                    touch3DStartState = null
                    touch3DIsPinch = false
                    v.active3DBoneAxis = -1
                    v.isInteracting3D = false
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (fingerCount <= 2) {
                        touch3DIsPinch = false
                        val remainingIdx = if (event.actionIndex == fingerIndices[0]) {
                            if (fingerCount > 1) fingerIndices[1] else 0
                        } else {
                            fingerIndices[0]
                        }
                        touch3DStartX = event.getX(remainingIdx)
                        touch3DStartY = event.getY(remainingIdx)
                    }
                }
            }
            return true
        }
    }

    // =========================================================
    // A. 手写笔交互流程：100% 负责笔刷绘制与图层编辑
    // =========================================================
    if (isStylusTouch) {
        if (touchAndSlideActive) {
            touchAndSlideActive = false
            v.quickMenuHoverIndex = -1
            v.requestUiCommand("close_edit_menu")
        }
        val driver = getOrCreateStylusDriver()
        driver?.onStylusMotionEvent(event)

        val x = event.getX(stylusPointerIndex)
        val y = event.getY(stylusPointerIndex)
        val screenPos = Offset(x, y)
        val docPos = screenToDoc(screenPos)
        val rawPressure = event.getPressure(stylusPointerIndex)
        val pressure = if (rawPressure.isNaN()) 1f else rawPressure.coerceIn(0f, 1f)

        setLocalCursorPos(screenPos.x, screenPos.y)
        vm?.lastPointerScreenPosition = screenPos
        val isDown = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        updateCursorAngleFromDocMove(docPos.x, docPos.y, isDown)
        localIsTouching = true
        localIsHovering = false
        localPressure = pressure
        // 前缓冲 UI 裁剪快照: 每笔落笔时刷新 (布局/浮窗/左右手状态快照)。
        // 240Hz 的 ACTION_MOVE 上只在 tracker.version 变化时重建。
        if (isDown) rebuildUiExclusionSnapshot()

        val hideCursor = (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (hideCursor && systemNullPointer != null && pointerIcon != systemNullPointer) {
                pointerIcon = systemNullPointer
            }
        }

        val canEyedrop = v.longPressEyedropperEnabled &&
            v.penModeStylusEyedropperEnabled &&
            (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 不在此处取消抬笔延续窗口: 手势/仅笔模式的 DOWN 未必真正落笔 (如参考线手柄、
                // 长按取色提前返回), 这些路径不会重新调度, 面板会永久停留在回避态。
                // 真正起笔时由 handleToolDown 的 BRUSH/ERASER/SMUDGE 分支负责取消。
                // 若是多指接触，只有当刚按下的指针就是手写笔自身时才触发落笔起画；
                // 手写笔画画时手掌压在屏幕上产生的 ACTION_POINTER_DOWN 直接忽略，不打断笔画
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.actionIndex != stylusPointerIndex) {
                    return true
                }
                clearPredictionState(triggerInvalidate = false)
                removeCallbacks(longPressRunnable)
                longPressToken++
                isTransformActive = false
                isPinchMotion = false
                isInteracting = true
                lastPos0 = Offset.Zero
                lastPos1 = Offset.Zero
                previousSinglePos = screenPos
                priorStrokeScreenPos = screenPos
                val downP = pressure.coerceIn(0.01f, 1f)
                val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
                if (!hasSymmetry && v.frontBufferPredictionEnabled && RealInkHoldPolicy.isRealInkTool(tool, effTool()) && screenPos.x.isFinite() && screenPos.y.isFinite()) {
                    universalPredictor.addPoint(screenPos.x, screenPos.y, downP, event.eventTime)
                    recordTouchSample(screenPos.x, screenPos.y, event.eventTime)
                }
                firstDocPos = docPos
                currentStrokeDocPos = docPos
                shapeEndDocPos = docPos
                dismissPickerOverlay()

                if (checkHitGuideHandle(screenPos)) {
                    isPendingLongPress = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }

                if (effTool() == Tool.PAN) {
                    isPendingLongPress = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemGrabPointer != null) {
                        pointerIcon = systemGrabPointer
                    }
                    return true
                }

                if (effTool() == Tool.ZOOM) {
                    cancelCanvasTransformAnimators()
                    isPendingLongPress = false
                    isZoomToolDragging = true
                    zoomToolStartPos = screenPos
                    zoomToolStartCanvasZoom = canvasZoom
                    zoomToolStartPanX = canvasPanX
                    zoomToolStartPanY = canvasPanY
                    zoomToolStartDownTime = event.eventTime
                    isInteracting = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemZoomInPointer != null) {
                        pointerIcon = systemZoomInPointer
                    }
                    return true
                }

                // 笔尖接触瞬间立即启动绘图，彻底消除长按判定位移容差带来的起笔延迟
                logStylusAxesOnce(event, stylusPointerIndex)
                updateStylusSensors(event, stylusPointerIndex, isStylus = true)
                handleToolDown(screenPos, docPos, pressure, isStylus = true, touchTiltX, touchTiltY, touchRotation, eventTimeMs = event.eventTime)
                // 真墨按住时这一笔由前缓冲呈现, 不再整屏失效主画布。
                if (!realInk.wantsHold()) invalidate()

                if (canEyedrop) {
                    isPendingLongPress = true
                    pendingLongPressPointerId = event.getPointerId(stylusPointerIndex)
                    activeLongPressToken = longPressToken
                    pendingDownDocPos = docPos
                    pendingDownScreenPos = screenPos
                    pendingDownPressure = pressure
                    val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
                    postDelayed(longPressRunnable, delayMs)
                } else {
                    isPendingLongPress = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (effTool() == Tool.PAN) {
                    val deltaX = screenPos.x - previousSinglePos.x
                    val deltaY = screenPos.y - previousSinglePos.y
                    previousSinglePos = screenPos
                    canvasPanX += deltaX
                    canvasPanY += deltaY
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    invalidate()
                    return true
                }

                if (effTool() == Tool.ZOOM) {
                    if (isZoomToolDragging) {
                        val dx = screenPos.x - zoomToolStartPos.x
                        val dy = screenPos.y - zoomToolStartPos.y
                        val dist = kotlin.math.hypot(dx, dy)
                        val slop = 8f * density
                        if (dist >= slop) {
                            val rawDelta = dx + dy
                            val delta = if (rawDelta > 0f) rawDelta - slop else rawDelta + slop
                            val factor = kotlin.math.exp((delta / 300f).toDouble()).toFloat()
                            val targetZoom = (zoomToolStartCanvasZoom * factor).coerceIn(0.02f, 128f)
                            val ratio = targetZoom / zoomToolStartCanvasZoom
                            val anchorOffsetX = zoomToolStartPos.x - viewW / 2f - zoomToolStartPanX
                            val anchorOffsetY = zoomToolStartPos.y - viewH / 2f - zoomToolStartPanY
                            canvasZoom = targetZoom
                            canvasPanX = zoomToolStartPos.x - viewW / 2f - ratio * anchorOffsetX
                            canvasPanY = zoomToolStartPos.y - viewH / 2f - ratio * anchorOffsetY
                            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                            invalidate()
                        }
                    }
                    return true
                }

                if (draggingGuideHandleIndex != -1) {
                    if (handleGuideHandleDrag(docPos)) return true
                }

                if (isLongPressPickerActive) {
                    sampleColorAtScreenPos(screenPos)
                    return true
                }

                if (isPendingLongPress) {
                    val moveSlopPx = (1.5f + (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                    val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                    if (moveDist > moveSlopPx) {
                        removeCallbacks(longPressRunnable)
                        longPressToken++
                        isPendingLongPress = false
                    }
                }
                currentStrokeDocPos = docPos
                priorStrokeScreenPos = previousSinglePos
                handleToolMove(event, stylusPointerIndex, docPos, pressure, isStylus = true)
                previousSinglePos = screenPos
                setLocalCursorPos(screenPos.x, screenPos.y)
                localIsTouching = true
                // 仅在非笔刷工具、光标跟随模式、绘图辅助生效或预测延伸激活时在 UI 线程重绘
                val isDrawingTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER || tool == Tool.SMUDGE
                if (!isDrawingTool) {
                    invalidate()
                } else if (!realInk.wantsHold()) {
                    val isEraser = tool == Tool.ERASER
                    val cursorMode = if (isEraser) v.eraserCursorMode else v.brushCursorMode
                    val hasAssist = v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing
                    val predictionVisualActive = if (v.frontBufferPredictionEnabled) {
                        hasPredictedScreenPoint
                    } else {
                        v.isCurrentBrushPredictionEligible && hasPredictedScreenPoint
                    }
                    if (cursorMode == 1 || cursorMode == 3 || hasAssist || predictionVisualActive) {
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // 若是多指接触，只有当手写笔自身抬起时才执行抬笔收尾；
                // 手掌或非手写笔触控点抬起不影响正在进行的手写笔绘制
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex != stylusPointerIndex) {
                    return true
                }
                if (draggingGuideHandleIndex != -1) {
                    draggingGuideHandleIndex = -1
                    if (strokeStarted) cancelActiveStroke(v)
                    isInteracting = false
                    invalidate()
                    return true
                }

                if (effTool() == Tool.PAN) {
                    isInteracting = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemHandPointer != null) {
                        pointerIcon = systemHandPointer
                    }
                    invalidate()
                    return true
                }

                if (effTool() == Tool.ZOOM) {
                    if (isZoomToolDragging) {
                        isZoomToolDragging = false
                        val elapsed = event.eventTime - zoomToolStartDownTime
                        val dist = kotlin.math.hypot(screenPos.x - zoomToolStartPos.x, screenPos.y - zoomToolStartPos.y)
                        val slop = 8f * density
                        if (elapsed < 300L && dist < slop) {
                            val targetZoom = (zoomToolStartCanvasZoom * 1.25f).coerceIn(0.02f, 128f)
                            val ratio = targetZoom / zoomToolStartCanvasZoom
                            val anchorOffsetX = zoomToolStartPos.x - viewW / 2f - zoomToolStartPanX
                            val anchorOffsetY = zoomToolStartPos.y - viewH / 2f - zoomToolStartPanY
                            canvasZoom = targetZoom
                            canvasPanX = zoomToolStartPos.x - viewW / 2f - ratio * anchorOffsetX
                            canvasPanY = zoomToolStartPos.y - viewH / 2f - ratio * anchorOffsetY
                            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        }
                    }
                    isInteracting = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemZoomInPointer != null) {
                        pointerIcon = systemZoomInPointer
                    }
                    invalidate()
                    return true
                }

                clearPredictionState(triggerInvalidate = true)
                cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                cachedDriver?.feedbackManager?.stopStrokeSound()
                removeCallbacks(longPressRunnable)
                longPressToken++
                isPendingLongPress = false
                if (isLongPressPickerActive) {
                    finishLongPressPicker(v)
                    localIsHovering = true
                    return true
                }

                handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL), isStylus = true)
                localIsTouching = false
                localIsHovering = true
                isInteracting = false
                currentStrokeDocPos = Offset.Zero
                firstDocPos = Offset.Zero
                shapeEndDocPos = Offset.Zero
                invalidate()
            }
        }
        return true
    }

    // =========================================================
    // B. 手指交互流程：画世界 / Procreate 模式 (100% 画布手势导航)
    // =========================================================
    val nowMs = System.currentTimeMillis()
    val numFingers = fingerCount
    maxTouchPointers = maxOf(maxTouchPointers, numFingers)
    isInteracting = true

    // ---- 长按吸色状态下的副手指点按交互 ----
    // 单指长按激活吸色后，若另一只手指点击画布，切换吸取模式 (当前图层 <-> 全部图层)，不打断吸色与变换
    if (isLongPressPickerActive) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val newMode = if (v.pickerSampleLayers == 1) 0 else 1
                v.updatePickerSampleLayers(newMode)
                val primaryIdx = if (longPressPointerId >= 0) event.findPointerIndex(longPressPointerId) else -1
                val primaryPos = if (primaryIdx >= 0) {
                    Offset(event.getX(primaryIdx), event.getY(primaryIdx))
                } else {
                    pendingDownScreenPos
                }
                sampleColorAtScreenPos(primaryPos)
                try {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                } catch (_: Throwable) {}
                val modeName = if (newMode == 0) R.string.selection_current_layer else R.string.selection_all_layers
                val modeIcon = if (newMode == 0) R.drawable.ic_layers else R.drawable.ic_layerstack
                v.showActionToast(modeName, modeIcon)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val primaryIdx = if (longPressPointerId >= 0) event.findPointerIndex(longPressPointerId) else 0
                if (primaryIdx in 0 until event.pointerCount) {
                    sampleColorAtScreenPos(Offset(event.getX(primaryIdx), event.getY(primaryIdx)))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upPointerId = event.getPointerId(event.actionIndex)
                if (upPointerId != longPressPointerId) {
                    return true
                }
                finishLongPressPicker(v)
                return true
            }
            MotionEvent.ACTION_UP -> {
                finishLongPressPicker(v)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dismissPickerOverlay()
                localIsTouching = false
                clearLocalCursorPos()
                invalidate()
                return true
            }
        }
    }

    // Reserve a fresh three-finger gesture before viewport navigation sees it.
    // Keep ownership through staggered pointer-up events: neither redo nor a stray stroke
    // may run after a swipe. Existing two-finger navigation remains untouched.
    val hasThreeFingerAction = v.gestureThreeFingerEditMenu ||
        v.gestureThreeFingerRedo ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_DOWN) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_UP) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_LEFT) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_RIGHT) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_TAP) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_DOUBLE_TAP) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_LONG_PRESS) != null ||
        v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_SLIDE) != null

    if (!editMenuGestureActive && hasThreeFingerAction && numFingers == 3 &&
        (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || (!isTransformActive && !isPinchMotion)) &&
        !isContinuousUndoing && !filterSessionActive && !isLiquifyGestureActive &&
        !v.anim.shiftTraceActive && tool != Tool.TRANSFORM && tool != Tool.CROP
    ) {
        editMenuGestureActive = true
        isPinchMotion = false
        editMenuPointersReleased = false
        threeFingerSlideActive = false
        threeFingerSlideAxis = 0
        val idx0 = fingerIndices[0]
        val idx1 = fingerIndices[1]
        val idx2 = fingerIndices[2]
        threeFingerSlideStartX = (event.getX(idx0) + event.getX(idx1) + event.getX(idx2)) / 3f
        threeFingerSlideStartY = (event.getY(idx0) + event.getY(idx1) + event.getY(idx2)) / 3f
        threeFingerSlideInitialSize = v.brushSize
        threeFingerSlideInitialOpacity = v.brushOpacity
        lastHapticOpacityStep = (v.brushOpacity * 10).roundToInt()
        val minL = v.brushMinSizeLimit.coerceAtLeast(0.5)
        lastHapticSizeStep = (Math.log((v.brushSize / minL).coerceAtLeast(1.0)) / Math.log(1.25)).toInt()

        editMenuSwipe.begin(event.eventTime, density)
        for (i in 0..2) {
            val index = fingerIndices[i]
            editMenuPointerIds[i] = event.getPointerId(index)
            editMenuSwipe.setStart(i, event.getX(index), event.getY(index))
        }
        removeCallbacks(longPressRunnable)
        longPressToken++
        isPendingLongPress = false
        removeCallbacks(continuousUndoRunnable)
        removeCallbacks(continuousRedoRunnable)
        removeCallbacks(threeFingerLongPressRunnable)
        // 12 条强制复位路径之一：三指编辑菜单/三指轻点重做接管。
        clearAllEvasionState(v)
        if (strokeStarted) {
            abortAndClearRealInk()
            v.touchCancel()
            strokeStarted = false
            safeEndSymmetryUndoMacro()
            resetMirrorBranches()
            clearPredictionState(triggerInvalidate = true)
        }
        cancelPendingShapeGesture()
        cancelPanelGesture()
        val threeLongPressAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_LONG_PRESS)
        if (threeLongPressAction != null && threeLongPressAction != com.reverie.paint.model.GestureAction.NONE) {
            postDelayed(threeFingerLongPressRunnable, 420L)
        } else if (v.gestureThreeFingerRedo && v.gestureContinuousUndo) {
            postDelayed(continuousRedoRunnable, 420L)
        }
    }
    if (editMenuGestureActive) {
        if (numFingers > 3 || stylusPointerIndex >= 0 ||
            event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (editMenuPointersReleased && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
        ) {
            if (!editMenuSwipe.swiped) editMenuSwipe.reject()
            if (numFingers > 3) {
                editMenuGestureActive = false
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            for (i in 0..2) {
                val index = event.findPointerIndex(editMenuPointerIds[i])
                if (index < 0) {
                    if (!editMenuPointersReleased && !editMenuSwipe.swiped) editMenuSwipe.reject()
                } else {
                    editMenuSwipe.update(i, event.getX(index), event.getY(index))
                }
            }
            if (!editMenuPointersReleased && numFingers == 3) {
                val pIdx0 = event.findPointerIndex(editMenuPointerIds[0])
                val pIdx1 = event.findPointerIndex(editMenuPointerIds[1])
                val pIdx2 = event.findPointerIndex(editMenuPointerIds[2])
                if (pIdx0 >= 0 && pIdx1 >= 0 && pIdx2 >= 0) {
                    val cx = (event.getX(pIdx0) + event.getX(pIdx1) + event.getX(pIdx2)) / 3f
                    val cy = (event.getY(pIdx0) + event.getY(pIdx1) + event.getY(pIdx2)) / 3f
                    val distMoved = hypot(cx - threeFingerSlideStartX, cy - threeFingerSlideStartY)
                    if (v.brushSlideAdjustEnabled) {
                        val dx = cx - threeFingerSlideStartX
                        val dy = cy - threeFingerSlideStartY
                        if (!threeFingerSlideActive && distMoved > 10f * density) {
                            threeFingerSlideActive = true
                            threeFingerSlideAxis = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) 2 else 1
                            editMenuSwipe.reject()
                            removeCallbacks(threeFingerLongPressRunnable)
                            removeCallbacks(continuousRedoRunnable)
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if (threeFingerSlideActive) {
                            if (threeFingerSlideAxis == 2) {
                                // 水平滑动 -> 仅调节不透明度 (Horizontal slide -> Opacity only)
                                val targetOpacity = (threeFingerSlideInitialOpacity + dx / (260f * density)).coerceIn(0.01, 1.0)
                                v.updateBrushOpacity(targetOpacity, commit = false)
                                val percent = kotlin.math.round(targetOpacity * 100).toInt()
                                v.showActionToast(R.string.toast_brush_opacity, R.drawable.ic_droplet, percent)

                                val curStep = (targetOpacity * 10).roundToInt()
                                if (curStep != lastHapticOpacityStep) {
                                    lastHapticOpacityStep = curStep
                                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            } else if (threeFingerSlideAxis == 1) {
                                // 垂直滑动 -> 仅调节笔刷大小 (Vertical slide -> Size only, 向上增大/向下减小)
                                val factor = Math.pow(2.0, (-dy / (90f * density)).toDouble())
                                val minL = v.brushMinSizeLimit.coerceAtLeast(0.5)
                                val maxL = v.effectiveBrushMaxSize.coerceAtLeast(minL)
                                val targetSize = (threeFingerSlideInitialSize * factor).coerceIn(minL, maxL)
                                v.updateBrushSize(targetSize, commit = false)
                                val formatted = if (targetSize < 10.0) {
                                    String.format(java.util.Locale.US, "%.1f", targetSize)
                                } else {
                                    "${targetSize.toInt()}"
                                }
                                v.showActionToast(R.string.toast_brush_size, R.drawable.ic_brush, formatted)

                                val curStep = (Math.log((targetSize / minL).coerceAtLeast(1.0)) / Math.log(1.25)).toInt()
                                if (curStep != lastHapticSizeStep) {
                                    lastHapticSizeStep = curStep
                                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }
                    }
                }
                if (!threeFingerSlideActive) {
                    editMenuSwipe.evaluate(event.eventTime)
                }
            }
        }
        if (editMenuSwipe.moved || editMenuSwipe.rejected || threeFingerSlideActive || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            removeCallbacks(threeFingerLongPressRunnable)
            removeCallbacks(continuousRedoRunnable)
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            editMenuPointersReleased = true
            if (threeFingerSlideActive) {
                if (threeFingerSlideAxis == 1) {
                    v.updateBrushSize(v.brushSize, commit = true)
                } else if (threeFingerSlideAxis == 2) {
                    v.updateBrushOpacity(v.brushOpacity, commit = true)
                }
                threeFingerSlideActive = false
                threeFingerSlideAxis = 0
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(threeFingerLongPressRunnable)
            removeCallbacks(continuousRedoRunnable)
            if (threeFingerSlideActive) {
                if (threeFingerSlideAxis == 1) {
                    v.updateBrushSize(v.brushSize, commit = true)
                } else if (threeFingerSlideAxis == 2) {
                    v.updateBrushOpacity(v.brushOpacity, commit = true)
                }
                threeFingerSlideActive = false
                threeFingerSlideAxis = 0
            } else if (event.actionMasked == MotionEvent.ACTION_UP && !isContinuousUndoing) {
                if (editMenuSwipe.swiped) {
                    val trigger = when (editMenuSwipe.swipeDirection) {
                        com.reverie.paint.model.SwipeDirection.DOWN -> com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_DOWN
                        com.reverie.paint.model.SwipeDirection.UP -> com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_UP
                        com.reverie.paint.model.SwipeDirection.LEFT -> com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_LEFT
                        com.reverie.paint.model.SwipeDirection.RIGHT -> com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_RIGHT
                        com.reverie.paint.model.SwipeDirection.NONE -> com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_DOWN
                    }
                    val action = v.gestureConfig.getActionForTrigger(trigger)
                    if (action != null) {
                        v.dispatchGestureAction(action)
                    } else if (trigger == com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_DOWN) {
                        v.requestUiCommand("open_edit_menu")
                    }
                } else if (editMenuSwipe.isTap(event.eventTime)) {
                    val action = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_TAP)
                    if (action != null) {
                        v.dispatchGestureAction(action)
                    } else if (v.gestureThreeFingerRedo) {
                        v.redo()
                    }
                }
            }
            editMenuGestureActive = false
            isContinuousUndoing = false
            isTransformActive = false
            isInteracting = false
            isPinchMotion = false
            maxTouchPointers = 0
            lastPos0 = Offset.Zero
            lastPos1 = Offset.Zero
            invalidate()
        }
        return true
    }

    // 1. 多指手势 (双指捏合缩放/旋转/平移 + 碎片期融合)
    if (numFingers >= 2 || (isTransformActive && (nowMs - lastTransformTimestamp) < 150)) {
        lastSingleFingerTapTimeMs = 0L
        lastSingleFingerTapDrewStroke = false
        if (touchAndSlideActive) {
            touchAndSlideActive = false
            v.quickMenuHoverIndex = -1
            v.requestUiCommand("close_edit_menu")
        }
        removeCallbacks(longPressRunnable)
        longPressToken++
        isPendingLongPress = false
        dismissPickerOverlay()
        if (isPendingEdgeFinger) {
            removeCallbacks(flushEdgeFingerRunnable)
            isPendingEdgeFinger = false
        }

        // 12 条强制复位路径之一：双指捏合/旋转/双指轻点撤销接管。
        clearAllEvasionState(v)
        if (strokeStarted) {
            cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
            cachedDriver?.feedbackManager?.stopStrokeSound()
            abortAndClearRealInk()
            v.touchCancel()
            strokeStarted = false
            safeEndSymmetryUndoMacro()
            resetMirrorBranches()
            clearPredictionState(triggerInvalidate = true)
        }
        cancelPendingShapeGesture()
        cancelPanelGesture()
        if (tfState?.cancelDrag() == true) {
            invalidate()
        }
        isZoomToolDragging = false

        val isShiftTraceAlign = v.anim.shiftTraceActive && v.anim.shiftTraceGestureMode == ShiftTraceGestureMode.ALIGN_FRAME

        if (numFingers >= 2) {
            val idx0 = fingerIndices[0]
            val idx1 = fingerIndices[1]
            val raw0 = Offset(event.getX(idx0), event.getY(idx0))
            val raw1 = Offset(event.getX(idx1), event.getY(idx1))

            val (p0, p1) = if (lastPos0 != Offset.Zero && lastPos1 != Offset.Zero) {
                val d00_11 = hypot(raw0.x - lastPos0.x, raw0.y - lastPos0.y) + hypot(raw1.x - lastPos1.x, raw1.y - lastPos1.y)
                val d01_10 = hypot(raw0.x - lastPos1.x, raw0.y - lastPos1.y) + hypot(raw1.x - lastPos0.x, raw1.y - lastPos0.y)
                if (d01_10 < d00_11) Pair(raw1, raw0) else Pair(raw0, raw1)
            } else {
                Pair(raw0, raw1)
            }
            lastPos0 = p0
            lastPos1 = p1

            val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
            val distance = hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
            val angle = Math.toDegrees(atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())).toFloat()

            val isNewGesture = !isTransformActive
            val isStaleFragment = (nowMs - lastTransformTimestamp) >= 150

            if (isNewGesture || isStaleFragment) {
                isTransformActive = true
                if (isNewGesture) {
                    isPinchMotion = false
                    isTwoFingerRotation = false
                    initialCentroid = centroid
                    initialDistance = distance
                    initialAngle = angle
                    touchDownTimeMs = nowMs
                    // NOTE: 纯两指落笔尚未产生任何变换位移, 不在此触发 onTransform,
                    // 避免将两指轻点撤销误识别为捏合手势并唤醒 HUD (plan 四.6).
                    // 实际变换位移在后续分支产生 delta 后触发 onTransform.
                }
                isContinuousUndoing = false
                prevCentroid = centroid
                prevDistance = distance
                prevAngle = angle
                // 新一段画布手势开始: 终止吸附收敛与满屏复位动画并失效其待执行帧, 同时把
                // 当前生效角交给吸附状态机作为本段手势起点, 避免残留动画与新手势互相拉扯
                cancelCanvasTransformAnimators()
                if (isNewGesture) {
                    rotationSnapGesture.begin(canvasRotation)
                    rotationSnapEngaged = false
                }

                removeCallbacks(continuousUndoRunnable)
                removeCallbacks(continuousRedoRunnable)
                removeCallbacks(twoFingerLongPressRunnable)
                continuousStepCount = 0
                if (!isShiftTraceAlign && isNewGesture) {
                    val twoLongPressAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.TWO_FINGER_LONG_PRESS)
                    if (twoLongPressAction != null && twoLongPressAction != com.reverie.paint.model.GestureAction.NONE) {
                        postDelayed(twoFingerLongPressRunnable, 420L)
                    } else if (v.gestureContinuousUndo) {
                        if (numFingers == 2 && v.gestureTwoFingerUndo) {
                            postDelayed(continuousUndoRunnable, 560L)
                        } else if (numFingers >= 3 && v.gestureThreeFingerRedo) {
                            postDelayed(continuousRedoRunnable, 560L)
                        }
                    }
                }
            } else {
                val distCentroidMoved = hypot(centroid.x - prevCentroid.x, centroid.y - prevCentroid.y)
                if (distCentroidMoved > 80f * density) {
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                } else if (isShiftTraceAlign) {
                    isPinchMotion = true
                    val target = v.anim.shiftTraceTarget
                    val curTf = if (target == ShiftTraceTarget.PREV) v.anim.shiftTracePrevTransform else v.anim.shiftTraceNextTransform

                    val viewScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                    val bmpW = (v.renderW.takeIf { it > 0 } ?: v.docWidth.coerceAtLeast(1)).toFloat()
                    val docW = v.docWidth.coerceAtLeast(1).toFloat()
                    val docToBmpRatio = bmpW / docW

                    val dScreenX = centroid.x - prevCentroid.x
                    val dScreenY = centroid.y - prevCentroid.y
                    val viewRad = -Math.toRadians(canvasRotation.toDouble())
                    val cosV = kotlin.math.cos(viewRad).toFloat()
                    val sinV = kotlin.math.sin(viewRad).toFloat()
                    val unrotDx = (dScreenX * cosV - dScreenY * sinV) / viewScale
                    val unrotDy = (dScreenX * sinV + dScreenY * cosV) / viewScale
                    val docDx = unrotDx / docToBmpRatio
                    val docDy = unrotDy / docToBmpRatio

                    val k = (distance / prevDistance).coerceIn(0.7f, 1.4f)
                    val dRot = normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f)

                    val newScale = (curTf.scale * k).coerceIn(0.05f, 20f)
                    val newRot = normalizeAngle(curTf.rotation + dRot)
                    val newTrans = curTf.translation + Offset(docDx, docDy)

                    val updated = ShiftTransform(
                        translation = newTrans,
                        rotation = newRot,
                        scale = newScale,
                    )
                    if (target == ShiftTraceTarget.PREV) {
                        v.anim.shiftTracePrevTransform = updated
                    } else {
                        v.anim.shiftTraceNextTransform = updated
                    }
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                    invalidate()
                } else {
                    val k = (distance / prevDistance).coerceIn(0.7f, 1.4f)
                    val isLocked = v.isViewTransformLocked
                    val snapThreshold = if (v.canvasRotationEnabled && !isLocked) v.canvasRotationSnapDegrees else 0f
                    // 用实际生效角增量补偿平移，吸附时仍以双指中心为旋转中心。
                    val dRot: Float
                    if (v.canvasRotationEnabled && !isLocked) {
                        val rawDelta = normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f)
                        // 退化输入防御: 非有限增量按 0 处理, 不得把 NaN 累积进吸附状态与画布角度
                        val safeDelta = if (rawDelta.isFinite()) rawDelta else 0f
                        // 模型负责旋转意图、精确锁定和脱离吸附时的连续衔接。
                        val snapped = rotationSnapGesture.update(safeDelta, snapThreshold)
                        dRot = snapped - canvasRotation
                        canvasRotation = snapped
                        val engaged = rotationSnapGesture.isSnapped
                        if (engaged && !rotationSnapEngaged) {
                            // 进入吸附区沿: 轻微触觉反馈 + 上层高亮角度 HUD (视觉反馈)
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            onRotationSnap?.invoke(canvasRotation)
                        }
                        rotationSnapEngaged = engaged
                    } else {
                        dRot = 0f
                        rotationSnapGesture.begin(canvasRotation)
                        rotationSnapEngaged = false
                    }
                    val rad = Math.toRadians(dRot.toDouble())
                    val cosR = kotlin.math.cos(rad).toFloat()
                    val sinR = kotlin.math.sin(rad).toFloat()

                    val totalMoved = hypot(centroid.x - initialCentroid.x, centroid.y - initialCentroid.y)
                    // 捏合判定统一用"手指真实移动了多少像素"衡量, 三个判据同量纲。
                    // 旧写法用比例(缩放 2%)与绝对角度(2°), 两者的实际灵敏度都随
                    // 手指间距反比放大: 间距 40px 时 0.8px 的抖动即判成捏合, 于是
                    // 手指并拢的双指/三指轻点几乎必然被吞掉 (三指重做手指靠近就失效)。
                    // 间距张开时新旧阈值量级相当, 手感不变。
                    val spreadMoved = abs(distance - initialDistance) * 0.5f
                    val angleDiff = abs(normalizeAngle(angle - initialAngle))
                    // 门控判据 (isPinchMotion) 保持改动前的三项旧判据逐点不变, 其中旋转项仍是旧
                    // 公式 `角差 × 指距 × 0.5` —— 三指重做 (maxTouchPointers >= 3) 与三指手势
                    // 消费的正是这个标记, 因此三指路径行为不发生任何漂移。
                    val legacyArcMoved = Math.toRadians(angleDiff.toDouble()).toFloat() * initialDistance * 0.5f
                    val isMotion = TwoFingerGesturePolicy.isMotion(
                        centroidMovedPx = totalMoved,
                        spreadMovedPx = spreadMoved,
                        rotationArcPx = legacyArcMoved,
                        moveTolerancePx = 6f * density,
                        spreadTolerancePx = 3f * density,
                        arcTolerancePx = 3f * density,
                    )
                    if (isMotion) {
                        isPinchMotion = true
                        removeCallbacks(continuousUndoRunnable)
                        removeCallbacks(continuousRedoRunnable)
                        removeCallbacks(twoFingerLongPressRunnable)
                    }
                    // 双指专用修正: 旧公式在双指并拢时几乎失敏 —— 间距 40px 时可转 20° 以上仍被
                    // 判成"轻点", 双指轻点撤销会吞掉旋转手势 (既多触发一次撤销, 又让旋转结束后
                    // 的 90° 吸附收敛不执行)。这里给旋转弧长加参考半径下限, 结果只写入独立的
                    // isTwoFingerRotation (仅抑制双指撤销, 不动 isPinchMotion); 判据内部对 >= 3 指
                    // 仍返回旧公式, 双保险保证三指等价。
                    if (
                        TwoFingerGesturePolicy.isStrictTwoFingerRotation(
                            angleDiffDegrees = angleDiff,
                            spacingPx = initialDistance,
                            density = density,
                            arcTolerancePx = 3f * density,
                            fingerCount = numFingers,
                        )
                    ) {
                        isTwoFingerRotation = true
                        removeCallbacks(continuousUndoRunnable)
                        removeCallbacks(twoFingerLongPressRunnable)
                    }

                    // 围绕双指中心 (prevCentroid) 几何旋转与缩放补偿，保证手指标定点完全不动
                    val vx = prevCentroid.x - (viewW / 2f + canvasPanX)
                    val vy = prevCentroid.y - (viewH / 2f + canvasPanY)

                    val targetZoom = if (isLocked) canvasZoom else (canvasZoom * k).coerceIn(0.02f, 128f)
                    val actualK = if (canvasZoom > 0.0001f) targetZoom / canvasZoom else 1f

                    val vRotX = actualK * (vx * cosR - vy * sinR)
                    val vRotY = actualK * (vx * sinR + vy * cosR)

                    canvasZoom = targetZoom
                    canvasPanX = centroid.x - vRotX - viewW / 2f
                    canvasPanY = centroid.y - vRotY - viewH / 2f

                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    invalidate()

                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                }
            }
            lastTransformTimestamp = nowMs
        } else if (numFingers == 1 && isTransformActive) {
            // 驱动碎片期（单指短暂存活）：持续补偿平移，零丢帧
            removeCallbacks(continuousUndoRunnable)
            removeCallbacks(continuousRedoRunnable)
            val idx0 = fingerIndices[0]
            val cur = Offset(event.getX(idx0), event.getY(idx0))
            val dist0 = hypot(cur.x - lastPos0.x, cur.y - lastPos0.y)
            val dist1 = hypot(cur.x - lastPos1.x, cur.y - lastPos1.y)

            if (isShiftTraceAlign) {
                val (dx, dy) = if (dist0 < dist1 && dist0 < 60f * density) {
                    val d = Pair(cur.x - lastPos0.x, cur.y - lastPos0.y)
                    lastPos0 = cur
                    prevCentroid = prevCentroid + Offset(d.first / 2f, d.second / 2f)
                    d
                } else if (dist1 <= dist0 && dist1 < 60f * density) {
                    val d = Pair(cur.x - lastPos1.x, cur.y - lastPos1.y)
                    lastPos1 = cur
                    prevCentroid = prevCentroid + Offset(d.first / 2f, d.second / 2f)
                    d
                } else {
                    lastPos0 = cur
                    Pair(0f, 0f)
                }
                if (hypot(dx, dy) > 0.2f) {
                    val target = v.anim.shiftTraceTarget
                    val curTf = if (target == ShiftTraceTarget.PREV) v.anim.shiftTracePrevTransform else v.anim.shiftTraceNextTransform
                    val viewScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                    val bmpW = (v.renderW.takeIf { it > 0 } ?: v.docWidth.coerceAtLeast(1)).toFloat()
                    val docW = v.docWidth.coerceAtLeast(1).toFloat()
                    val docToBmpRatio = bmpW / docW
                    val viewRad = -Math.toRadians(canvasRotation.toDouble())
                    val cosV = kotlin.math.cos(viewRad).toFloat()
                    val sinV = kotlin.math.sin(viewRad).toFloat()
                    val unrotDx = (dx * cosV - dy * sinV) / viewScale
                    val unrotDy = (dx * sinV + dy * cosV) / viewScale
                    val docDx = unrotDx / docToBmpRatio
                    val docDy = unrotDy / docToBmpRatio
                    val updated = curTf.copy(translation = curTf.translation + Offset(docDx, docDy))
                    if (target == ShiftTraceTarget.PREV) {
                        v.anim.shiftTracePrevTransform = updated
                    } else {
                        v.anim.shiftTraceNextTransform = updated
                    }
                    invalidate()
                }
                lastTransformTimestamp = nowMs
            } else if (dist0 < dist1 && dist0 < 60f * density) {
                val dx = cur.x - lastPos0.x
                val dy = cur.y - lastPos0.y
                lastPos0 = cur
                prevCentroid = prevCentroid + Offset(dx / 2f, dy / 2f)
                canvasPanX += dx
                canvasPanY += dy
                if (hypot(dx, dy) > 2f) isPinchMotion = true
                lastTransformTimestamp = nowMs
                onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                invalidate()
            } else if (dist1 <= dist0 && dist1 < 60f * density) {
                val dx = cur.x - lastPos1.x
                val dy = cur.y - lastPos1.y
                lastPos1 = cur
                prevCentroid = prevCentroid + Offset(dx / 2f, dy / 2f)
                canvasPanX += dx
                canvasPanY += dy
                if (hypot(dx, dy) > 2f) isPinchMotion = true
                lastTransformTimestamp = nowMs
                onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                invalidate()
            } else {
                lastPos0 = cur
                lastTransformTimestamp = nowMs
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(continuousUndoRunnable)
                removeCallbacks(continuousRedoRunnable)
                removeCallbacks(twoFingerLongPressRunnable)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(continuousUndoRunnable)
                removeCallbacks(continuousRedoRunnable)
                removeCallbacks(twoFingerLongPressRunnable)
                val durationMs = nowMs - touchDownTimeMs
                isInteracting = false
                val settled = fitAnimator?.isRunning != true && snapAnimator?.isRunning != true
                val gestureUpPose = realInk.pose
                if (settled && v.frontBufferPredictionEnabled && gestureUpPose != null && !realInk.held && FrontBufferFeatureFlags.holeMode != FrontBufferHolePolicy.MODE_OFF) {
                    if (composeApplies(v, gestureUpPose)) {
                        composeDecorFor(v, gestureUpPose)
                    } else {
                        holeMasks.scheduleIfStale(v, gestureUpPose, settled = true, immediate = true)
                    }
                }

                // Procreate Quick-Pinch to Fit Canvas (高门槛防误触 + 平滑复位动画)
                val isQuickPinchFit = v.gestureQuickPinchFit &&
                    maxTouchPointers == 2 &&
                    durationMs in 60L..250L &&
                    initialDistance > 130f * density &&
                    prevDistance < initialDistance * 0.45f &&
                    (initialDistance - prevDistance) / durationMs > 0.60f * density

                if (isShiftTraceAlign) {
                    // 透光台对位手势完成：不触发满屏复位或历史撤销
                } else if (isQuickPinchFit) {
                    val fitAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.QUICK_PINCH)
                    if (fitAction != null) {
                        v.dispatchGestureAction(fitAction)
                    } else {
                        animateFitCanvas()
                        v.showActionToast(context.getString(R.string.canvas_toast_fit_reset), R.drawable.ic_refresh)
                    }
                } else if (!isContinuousUndoing && !isPinchMotion && !isTwoFingerRotation && !filterSessionActive && maxTouchPointers == 2 && durationMs < 360L) {
                    val tapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.TWO_FINGER_TAP)
                    if (tapAction != null && tapAction != com.reverie.paint.model.GestureAction.NONE) {
                        v.dispatchGestureAction(tapAction, anchor = initialCentroid, docPos = screenToDoc(initialCentroid))
                    } else {
                        val doubleTapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.TWO_FINGER_DOUBLE_TAP)
                        val isDoubleTap = doubleTapAction != null && doubleTapAction != com.reverie.paint.model.GestureAction.NONE && (nowMs - lastTwoFingerTapTimeMs < 350L)
                        if (isDoubleTap) {
                            lastTwoFingerTapTimeMs = 0L
                            v.dispatchGestureAction(doubleTapAction, anchor = initialCentroid, docPos = screenToDoc(initialCentroid))
                        } else {
                            lastTwoFingerTapTimeMs = nowMs
                        }
                    }
                } else if (!isContinuousUndoing && !isPinchMotion && !filterSessionActive && maxTouchPointers == 3 && durationMs < 380L) {
                    val tapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_TAP)
                    if (tapAction != null && tapAction != com.reverie.paint.model.GestureAction.NONE) {
                        v.dispatchGestureAction(tapAction, anchor = initialCentroid, docPos = screenToDoc(initialCentroid))
                    } else {
                        val doubleTapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_DOUBLE_TAP)
                        val isDoubleTap = doubleTapAction != null && doubleTapAction != com.reverie.paint.model.GestureAction.NONE && (nowMs - lastThreeFingerTapTimeMs < 350L)
                        if (isDoubleTap) {
                            lastThreeFingerTapTimeMs = 0L
                            v.dispatchGestureAction(doubleTapAction, anchor = initialCentroid, docPos = screenToDoc(initialCentroid))
                        } else {
                            lastThreeFingerTapTimeMs = nowMs
                        }
                    }
                } else if (!isContinuousUndoing && !filterSessionActive && maxTouchPointers >= 4 && durationMs < 550L) {
                    val distCentroidMoved = hypot(lastPos0.x - initialCentroid.x, lastPos0.y - initialCentroid.y)
                    if (distCentroidMoved < 36f * density) {
                        val tapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.FOUR_FINGER_TAP)
                        if (tapAction != null) {
                            v.dispatchGestureAction(tapAction)
                        }
                    }
                } else if (rotationSnapGesture.isActive && !v.isViewTransformLocked) {
                    // 正常画布手势结束: 若停在 90° 倍数吸附区内, 平滑收敛到精确倍数
                    settleRotationToSnap(prevCentroid.x, prevCentroid.y)
                }

                isContinuousUndoing = false
                isTransformActive = false
                isPinchMotion = false
                isTwoFingerRotation = false
                maxTouchPointers = 0
                lastPos0 = Offset.Zero
                lastPos1 = Offset.Zero
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(continuousUndoRunnable)
                removeCallbacks(continuousRedoRunnable)
                removeCallbacks(twoFingerLongPressRunnable)
                isContinuousUndoing = false
                dismissPickerOverlay()
                if (strokeStarted) {
                    cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                    cachedDriver?.feedbackManager?.stopStrokeSound()
                    abortAndClearRealInk()
                    clearAllEvasionState(v)
                    v.touchCancel()
                    strokeStarted = false
                    safeEndSymmetryUndoMacro()
                    resetMirrorBranches()
                    clearPredictionState(triggerInvalidate = true)
                }
                cancelPendingShapeGesture()
                cancelPanelGesture()
                postDelayed(resetTransformRunnable, 150)
                invalidate()
            }
        }
        return true
    }

    // 2. 单指手势 (根据 vm.penOnlyMode 切换手指平移 vs 手指作画)
    val singleFingerIdx = if (fingerCount > 0) fingerIndices[0] else 0
    val screenPos = Offset(event.getX(singleFingerIdx), event.getY(singleFingerIdx))
    val docPos = screenToDoc(screenPos)
    val isTouchSlideQuickMenu = v.gestureConfig.getTrigger(com.reverie.paint.model.GestureAction.QUICK_MENU) ==
        com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE
    val isDrawingTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY
    val canEyedrop = v.longPressEyedropperEnabled &&
        (v.gestureConfig.getTrigger(com.reverie.paint.model.GestureAction.EYEDROPPER) == com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_LONG_PRESS) &&
        isDrawingTool
    val isPanTool = effTool() == Tool.PAN
    val isZoomTool = effTool() == Tool.ZOOM
    val isSinglePointerPan = isPanTool || (!isTouchSlideQuickMenu && v.penOnlyMode && v.penModeSingleFingerPanEnabled && (
        tool.group == ToolGroup.BRUSH ||
        tool.group == ToolGroup.SELECTION ||
        tool.group == ToolGroup.SHAPES ||
        tool.group == ToolGroup.FILL ||
        tool == Tool.LIQUIFY ||
        tool == Tool.PICKER ||
        tool == Tool.MEASURE ||
        tool == Tool.TEXT
    ))

    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (isZoomTool) {
                cancelCanvasTransformAnimators()
                isZoomToolDragging = true
                zoomToolStartPos = screenPos
                zoomToolStartCanvasZoom = canvasZoom
                zoomToolStartPanX = canvasPanX
                zoomToolStartPanY = canvasPanY
                zoomToolStartDownTime = nowMs
                isInteracting = true
                return true
            }
            // 不在此处取消 180ms 恢复: 双指撤销/重做等多指手势的 DOWN 不经过 handleToolUp,
            // 取消后没有路径重新调度, 面板会永久停留在回避态。
            // 真正起笔时由 handleToolDown 的 BRUSH/ERASER/SMUDGE 分支负责取消。
            // 手指落笔同样要在提交前刷新裁剪快照 (此前只覆盖了手写笔路径)。
            rebuildUiExclusionSnapshot()
            touchDownTimeMs = nowMs
            maxTouchPointers = 1
            previousSinglePos = screenPos
            priorStrokeScreenPos = screenPos
            clearPredictionState(triggerInvalidate = false)

            singleFingerDownScreenPos = screenPos
            singleFingerDownDocPos = docPos
            singleFingerDownTimeMs = nowMs

            val singleDoubleTapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_DOUBLE_TAP)
            val doubleTapDist = hypot(screenPos.x - lastSingleFingerTapScreenPos.x, screenPos.y - lastSingleFingerTapScreenPos.y)
            if (singleDoubleTapAction != null && (nowMs - lastSingleFingerTapTimeMs in 40L..350L) && doubleTapDist < 36f * density) {
                lastSingleFingerTapTimeMs = 0L
                isSingleFingerDoubleTapActive = true
                removeCallbacks(longPressRunnable)
                longPressToken++
                isPendingLongPress = false
                if (isPendingEdgeFinger) {
                    removeCallbacks(flushEdgeFingerRunnable)
                    isPendingEdgeFinger = false
                }
                if (lastSingleFingerTapDrewStroke) {
                    lastSingleFingerTapDrewStroke = false
                    v.undo(showToast = false)
                }
                v.dispatchGestureAction(singleDoubleTapAction, anchor = screenPos, docPos = docPos)
                return true
            } else if (nowMs - lastSingleFingerTapTimeMs > 350L) {
                lastSingleFingerTapTimeMs = 0L
                lastSingleFingerTapDrewStroke = false
            }

            if (isTouchSlideQuickMenu) {
                touchAndSlideActive = true
                touchAndSlideStartPos = screenPos
                v.quickMenuHoverIndex = -1
                v.dispatchGestureAction(com.reverie.paint.model.GestureAction.QUICK_MENU, screenPos)
                return true
            }
            val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
            if (!hasSymmetry && v.frontBufferPredictionEnabled && RealInkHoldPolicy.isRealInkTool(tool, effTool()) && screenPos.x.isFinite() && screenPos.y.isFinite()) {
                universalPredictor.addPoint(screenPos.x, screenPos.y, 1f, event.eventTime)
                recordTouchSample(screenPos.x, screenPos.y, event.eventTime)
            }
            firstDocPos = docPos
            currentStrokeDocPos = docPos
            shapeEndDocPos = docPos
            updateCursorAngleFromDocMove(docPos.x, docPos.y, true)
            dismissPickerOverlay()
            removeCallbacks(longPressRunnable)
            longPressToken++
            if (isPendingEdgeFinger) {
                removeCallbacks(flushEdgeFingerRunnable)
                isPendingEdgeFinger = false
            }

            if (checkHitGuideHandle(screenPos)) {
                return true
            }

            val curW = if (width > 0) width else viewW
            val edgeThreshold = (32 * density)
            val isEdgeTouch = screenPos.x <= edgeThreshold || (curW > 0 && screenPos.x >= curW - edgeThreshold)
            val isEdgeBackAllowed = (v.allowEdgeBackGesture && v.backKeyAction != BackKeyAction.NONE) || overlayPanelsOpen

            if (isEdgeTouch && isEdgeBackAllowed && !isSinglePointerPan && !v.penOnlyMode) {
                isPendingEdgeFinger = true
                pendingEdgeScreenPos = screenPos
                pendingEdgeDocPos = docPos
                postDelayed(flushEdgeFingerRunnable, 180L)
                return true
            }

            if (canEyedrop) {
                isPendingLongPress = true
                pendingLongPressPointerId = event.getPointerId(singleFingerIdx)
                activeLongPressToken = longPressToken
                pendingDownDocPos = docPos
                pendingDownScreenPos = screenPos
                pendingDownPressure = 1f
                val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
                postDelayed(longPressRunnable, delayMs)
                if (!v.penOnlyMode) {
                    setLocalCursorPos(screenPos.x, screenPos.y)
                    localIsTouching = true
                    localIsHovering = false
                    localPressure = 1f
                    invalidate()
                }
            } else if (!isSinglePointerPan) {
                if (v.penOnlyMode) {
                    // 笔模式下且未开启单指平移：忽略单指触控输入，防止手掌误触作画
                    return true
                }
                isPendingLongPress = false
                setLocalCursorPos(screenPos.x, screenPos.y)
                localIsTouching = true
                localIsHovering = false
                localPressure = 1f
                // 点击型工具 (油漆桶/魔棒/相似选区): 手指触摸下延后至 ACTION_UP 确认单指轻点时触发，
                // 防止双指撤回时第一根手指先落下的毫秒级微差误触发新填充并顶掉撤销堆栈
                val isTapCommitTool = tool == Tool.FILL || tool == Tool.MAGICWAND || tool == Tool.SELECT_SIMILAR
                if (!isTapCommitTool) {
                    handleToolDown(screenPos, docPos, 1f, isStylus = false)
                }
                // 真墨按住时这一笔由前缓冲呈现, 不再整屏失效主画布。
                if (!realInk.wantsHold()) invalidate()
            } else if (isPanTool) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemGrabPointer != null) {
                    pointerIcon = systemGrabPointer
                }
            }
            return true
        }

        MotionEvent.ACTION_MOVE -> {
            if (isSingleFingerDoubleTapActive) return true
            val deltaX = screenPos.x - previousSinglePos.x
            val deltaY = screenPos.y - previousSinglePos.y
            priorStrokeScreenPos = previousSinglePos
            previousSinglePos = screenPos

            if (touchAndSlideActive) {
                val dx = screenPos.x - touchAndSlideStartPos.x
                val dy = screenPos.y - touchAndSlideStartPos.y
                val dist = hypot(dx, dy)
                val deadZone = 48f * density
                if (dist > deadZone) {
                    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val count = v.canvasEditMenuActions.take(6).size
                    v.quickMenuHoverIndex = com.reverie.paint.model.QuickMenuLayout.findHoverIndex(deg, count)
                } else {
                    v.quickMenuHoverIndex = -1
                }
                return true
            }

            if (draggingGuideHandleIndex != -1) {
                if (handleGuideHandleDrag(docPos)) return true
            }

            if (isLongPressPickerActive) {
                sampleColorAtScreenPos(screenPos)
                return true
            }

            if (isPendingEdgeFinger) {
                val dx = screenPos.x - pendingEdgeScreenPos.x
                val dy = screenPos.y - pendingEdgeScreenPos.y
                val dist = hypot(dx, dy)
                val isVerticalMove = abs(dy) > abs(dx) * 1.2f && dist > (8 * density)
                val isDeepMove = dist > (36 * density)
                if (isVerticalMove || isDeepMove) {
                    removeCallbacks(flushEdgeFingerRunnable)
                    val downScreen = pendingEdgeScreenPos
                    val downDoc = pendingEdgeDocPos
                    isPendingEdgeFinger = false
                    if (!v.penOnlyMode) {
                        setLocalCursorPos(screenPos.x, screenPos.y)
                        localIsTouching = true
                        localIsHovering = false
                        localPressure = 1f
                        handleToolDown(downScreen, downDoc, 1f, isStylus = false)
                        handleToolMove(event, 0, docPos, 1f, isStylus = false)
                        if (!realInk.wantsHold()) invalidate()
                        return true
                    }
                } else {
                    // 仍在边缘侧滑判定区中，暂缓落笔，避免抢占系统侧滑或在边缘误画
                    return true
                }
            }

            if (isPendingLongPress) {
                val moveSlopPx = (1.5f + (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                if (moveDist > moveSlopPx) {
                    removeCallbacks(longPressRunnable)
                    longPressToken++
                    isPendingLongPress = false
                    if (!v.penOnlyMode) {
                        setLocalCursorPos(screenPos.x, screenPos.y)
                        localIsTouching = true
                        handleToolDown(pendingDownScreenPos, pendingDownDocPos, pendingDownPressure, isStylus = false)
                        handleToolMove(event, 0, docPos, 1f, isStylus = false)
                        if (!realInk.wantsHold()) invalidate()
                        return true
                    }
                } else {
                    // 处于长按吸色等待期，位移未超容差，防止手指微动误触发平移或画线
                    return true
                }
            }

            if (isZoomTool) {
                if (isZoomToolDragging) {
                    val dx = screenPos.x - zoomToolStartPos.x
                    val dy = screenPos.y - zoomToolStartPos.y
                    val dist = kotlin.math.hypot(dx, dy)
                    val slop = 8f * density
                    if (dist >= slop) {
                        val rawDelta = dx + dy
                        val delta = if (rawDelta > 0f) rawDelta - slop else rawDelta + slop
                        val factor = kotlin.math.exp((delta / 300f).toDouble()).toFloat()
                        val targetZoom = (zoomToolStartCanvasZoom * factor).coerceIn(0.02f, 128f)
                        val ratio = targetZoom / zoomToolStartCanvasZoom
                        val anchorOffsetX = zoomToolStartPos.x - viewW / 2f - zoomToolStartPanX
                        val anchorOffsetY = zoomToolStartPos.y - viewH / 2f - zoomToolStartPanY
                        canvasZoom = targetZoom
                        canvasPanX = zoomToolStartPos.x - viewW / 2f - ratio * anchorOffsetX
                        canvasPanY = zoomToolStartPos.y - viewH / 2f - ratio * anchorOffsetY
                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        invalidate()
                    }
                }
                return true
            }

            val isShiftTraceAlign = v.anim.shiftTraceActive && v.anim.shiftTraceGestureMode == ShiftTraceGestureMode.ALIGN_FRAME
            if (isSinglePointerPan) {
                if (isShiftTraceAlign) {
                    val target = v.anim.shiftTraceTarget
                    val curTf = if (target == ShiftTraceTarget.PREV) v.anim.shiftTracePrevTransform else v.anim.shiftTraceNextTransform
                    val viewScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                    val bmpW = (v.renderW.takeIf { it > 0 } ?: v.docWidth.coerceAtLeast(1)).toFloat()
                    val docW = v.docWidth.coerceAtLeast(1).toFloat()
                    val docToBmpRatio = bmpW / docW
                    val viewRad = -Math.toRadians(canvasRotation.toDouble())
                    val cosV = kotlin.math.cos(viewRad).toFloat()
                    val sinV = kotlin.math.sin(viewRad).toFloat()
                    val unrotDx = (deltaX * cosV - deltaY * sinV) / viewScale
                    val unrotDy = (deltaX * sinV + deltaY * cosV) / viewScale
                    val docDx = unrotDx / docToBmpRatio
                    val docDy = unrotDy / docToBmpRatio
                    val updated = curTf.copy(translation = curTf.translation + Offset(docDx, docDy))
                    if (target == ShiftTraceTarget.PREV) {
                        v.anim.shiftTracePrevTransform = updated
                    } else {
                        v.anim.shiftTraceNextTransform = updated
                    }
                    invalidate()
                    return true
                } else {
                    // 笔模式开启且处于绘图工具：单指丝滑平移画布
                    canvasPanX += deltaX
                    canvasPanY += deltaY
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    invalidate()
                    return true
                }
            } else {
                if (v.penOnlyMode) {
                    // 笔模式下且未开启单指平移：忽略单指移动
                    return true
                }
                setLocalCursorPos(screenPos.x, screenPos.y)
                updateCursorAngleFromDocMove(docPos.x, docPos.y, false)
                localIsTouching = true
                handleToolMove(event, 0, docPos, 1f, isStylus = false)
                if (!realInk.wantsHold()) invalidate()
                return true
            }
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (isZoomTool) {
                if (isZoomToolDragging) {
                    isZoomToolDragging = false
                    val elapsed = nowMs - zoomToolStartDownTime
                    val dist = kotlin.math.hypot(screenPos.x - zoomToolStartPos.x, screenPos.y - zoomToolStartPos.y)
                    val slop = 8f * density
                    if (elapsed < 300L && dist < slop) {
                        val targetZoom = (zoomToolStartCanvasZoom * 1.25f).coerceIn(0.02f, 128f)
                        val ratio = targetZoom / zoomToolStartCanvasZoom
                        val anchorOffsetX = zoomToolStartPos.x - viewW / 2f - zoomToolStartPanX
                        val anchorOffsetY = zoomToolStartPos.y - viewH / 2f - zoomToolStartPanY
                        canvasZoom = targetZoom
                        canvasPanX = zoomToolStartPos.x - viewW / 2f - ratio * anchorOffsetX
                        canvasPanY = zoomToolStartPos.y - viewH / 2f - ratio * anchorOffsetY
                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    }
                }
                isInteracting = false
                invalidate()
                return true
            }
            clearPredictionState(triggerInvalidate = true)
            removeCallbacks(longPressRunnable)
            longPressToken++
            removeCallbacks(flushEdgeFingerRunnable)
            isInteracting = false
            if (isSingleFingerDoubleTapActive) {
                isSingleFingerDoubleTapActive = false
                lastSingleFingerTapTimeMs = 0L
                lastSingleFingerTapDrewStroke = false
                localIsTouching = false
                clearLocalCursorPos()
                invalidate()
                return true
            }
            // 12 条强制复位路径之一：手势取消（含边缘侧滑/长按待定等提前返回路径）。
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) clearAllEvasionState(v)

            if (touchAndSlideActive) {
                touchAndSlideActive = false
                lastSingleFingerTapTimeMs = 0L
                lastSingleFingerTapDrewStroke = false
                val hovered = v.quickMenuHoverIndex
                v.quickMenuHoverIndex = -1
                val slideDist = hypot(screenPos.x - touchAndSlideStartPos.x, screenPos.y - touchAndSlideStartPos.y)
                if (event.actionMasked == MotionEvent.ACTION_UP && hovered >= 0 && slideDist >= 48f * density) {
                    v.executeCanvasEditActionByIndex(hovered)
                    v.requestUiCommand("close_edit_menu")
                } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    v.requestUiCommand("close_edit_menu")
                }
                return true
            }

            if (draggingGuideHandleIndex != -1) {
                draggingGuideHandleIndex = -1
                lastSingleFingerTapTimeMs = 0L
                lastSingleFingerTapDrewStroke = false
                if (strokeStarted) cancelActiveStroke(v)
                return true
            }

            if (isLongPressPickerActive) {
                finishLongPressPicker(v)
                return true
            }

            if (isPendingEdgeFinger) {
                val downScreen = pendingEdgeScreenPos
                val downDoc = pendingEdgeDocPos
                isPendingEdgeFinger = false
                val wasDrawing = event.actionMasked != MotionEvent.ACTION_CANCEL && !v.penOnlyMode
                var drewStroke = false
                if (wasDrawing) {
                    handleToolDown(downScreen, downDoc, 1f, isStylus = false)
                    drewStroke = strokeStarted
                    handleToolUp(event, downDoc, isCancel = false)
                }
                localIsTouching = false
                clearLocalCursorPos()
                val tapDuration = nowMs - singleFingerDownTimeMs
                val moveDist = hypot(screenPos.x - singleFingerDownScreenPos.x, screenPos.y - singleFingerDownScreenPos.y)
                if (event.actionMasked == MotionEvent.ACTION_UP && tapDuration < 320L && moveDist < 16f * density) {
                    lastSingleFingerTapTimeMs = nowMs
                    lastSingleFingerTapScreenPos = singleFingerDownScreenPos
                    lastSingleFingerTapDocPos = singleFingerDownDocPos
                    lastSingleFingerTapDrewStroke = drewStroke
                } else {
                    lastSingleFingerTapTimeMs = 0L
                    lastSingleFingerTapDrewStroke = false
                }
                invalidate()
                return true
            }

            if (isPendingLongPress) {
                isPendingLongPress = false
                val wasDrawing = !v.penOnlyMode && event.actionMasked != MotionEvent.ACTION_CANCEL
                var drewStroke = false
                if (wasDrawing) {
                    handleToolDown(pendingDownScreenPos, pendingDownDocPos, pendingDownPressure, isStylus = false)
                    drewStroke = strokeStarted
                    handleToolUp(event, pendingDownDocPos, isCancel = false)
                }
                localIsTouching = false
                clearLocalCursorPos()
                val tapDuration = nowMs - singleFingerDownTimeMs
                val moveDist = hypot(screenPos.x - singleFingerDownScreenPos.x, screenPos.y - singleFingerDownScreenPos.y)
                if (event.actionMasked == MotionEvent.ACTION_UP && tapDuration < 320L && moveDist < 16f * density) {
                    lastSingleFingerTapTimeMs = nowMs
                    lastSingleFingerTapScreenPos = singleFingerDownScreenPos
                    lastSingleFingerTapDocPos = singleFingerDownDocPos
                    lastSingleFingerTapDrewStroke = drewStroke
                } else {
                    lastSingleFingerTapTimeMs = 0L
                    lastSingleFingerTapDrewStroke = false
                }
                invalidate()
                return true
            }

            val wasStroke = strokeStarted
            if (!isSinglePointerPan) {
                if (v.penOnlyMode) {
                    localIsTouching = false
                    clearLocalCursorPos()
                    invalidate()
                } else {
                    handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    localIsTouching = false
                    clearLocalCursorPos()
                    invalidate()
                }
            } else if (isPanTool) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemHandPointer != null) {
                    pointerIcon = systemHandPointer
                }
                invalidate()
            }
            val tapDuration = nowMs - singleFingerDownTimeMs
            val moveDist = hypot(screenPos.x - singleFingerDownScreenPos.x, screenPos.y - singleFingerDownScreenPos.y)
            if (event.actionMasked == MotionEvent.ACTION_UP && tapDuration < 320L && moveDist < 16f * density) {
                lastSingleFingerTapTimeMs = nowMs
                lastSingleFingerTapScreenPos = singleFingerDownScreenPos
                lastSingleFingerTapDocPos = singleFingerDownDocPos
                lastSingleFingerTapDrewStroke = wasStroke
            } else {
                lastSingleFingerTapTimeMs = 0L
                lastSingleFingerTapDrewStroke = false
            }
            return true
        }
    }

    return superOnTouchEvent(event)
}
