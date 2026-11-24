/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.reverie.paint.BuildConfig
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.core.stylus.UniversalKalmanPredictor
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.handleToolDown(
    screenPos: Offset,
    docPos: Offset,
    pressure: Float,
    isStylus: Boolean,
    tiltX: Double = 0.0,
    tiltY: Double = 0.0,
    rotation: Double = 0.0,
    eventTimeMs: Long = 0L,
) {
    val v = vm ?: return
    // 落笔时按用户偏好收起「临时态」快捷调色盘(默认不收起, 见 quickColorAutoHideOnStroke)
    v.dismissEphemeralQuickColor()
    // 不变式: 先发布裁剪快照, 再把触摸点提交给引擎。
    val et = effTool()
    if (et == Tool.BRUSH || et == Tool.ERASER || et == Tool.SMUDGE) {
        // 80ms 内新落笔：落点仍在 40dp 内则无缝保持 50%，否则当帧恢复。
        removeCallbacks(clearEvasionAfterUpRunnable)
        rebuildUiExclusionSnapshot(force = true)
        val brushR = effectiveBrushScreenRadius(v, pressure)
        evaluateUiEvasion50(v, screenPos.x, screenPos.y, screenPos.x, screenPos.y, brushR)
    } else {
        rebuildUiExclusionSnapshot()
    }
    removeCallbacks(quickShapeHold)
    quickShapeCandidate = null
    v.quickShapeCapture = null
    val t = effTool()
    val isDrawingTool = t.group == ToolGroup.BRUSH || t.group == ToolGroup.FILL || t.group == ToolGroup.SHAPES
    if (v.isLayerBackendBusy() && (isDrawingTool || tool == Tool.LIQUIFY)) {
        return
    }
    // 拆分等结构操作可能把当前层留在隐藏的分格蒙版/边框上，先改回内容层再落笔
    if (isDrawingTool || tool == Tool.LIQUIFY) {
        v.retargetOffPanelAuxLayer()
    }
    val activeLayer = v.layers.firstOrNull { it.index == v.currentLayerIndex }

    if (activeLayer?.isGroup == true && isDrawingTool) {
        v.showActionToast(context.getString(R.string.canvas_toast_group_not_drawable), R.drawable.ic_folder)
        return
    }
    if ((activeLayer?.nodeType == 3) && isDrawingTool) {
        v.showActionToast(context.getString(R.string.canvas_toast_filter_not_drawable), R.drawable.ic_image_adjust)
        return
    }
    if ((activeLayer?.nodeType == com.reverie.paint.model.LayerNodeType.SELECTION_MASK) && isDrawingTool) {
        v.showActionToast(context.getString(R.string.canvas_toast_selection_mask_not_drawable), R.drawable.ic_select)
        return
    }
    if (activeLayer?.locked == true && (isDrawingTool || tool == Tool.LIQUIFY)) {
        v.showActionToast(context.getString(R.string.canvas_toast_layer_locked), R.drawable.ic_lock)
        return
    }
    if (v.isLayerEffectivelyHidden(v.currentLayerIndex) && (isDrawingTool || tool == Tool.LIQUIFY)) {
        v.showActionToast(context.getString(R.string.canvas_toast_layer_hidden), R.drawable.ic_eye_off)
        return
    }
    if (activeLayer != null && v.activeTextLayers.containsKey(activeLayer.id) && isDrawingTool) {
        v.showActionToast(context.getString(R.string.canvas_toast_text_not_drawable), R.drawable.ic_text)
        return
    }

    firstDocPos = docPos
    shapeEndDocPos = docPos

    when (effTool()) {
        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
            val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
            assistLockedRay = null
            resetFisheyeAssist()
            strokeAbortedUntilUp = false
            if (hasSymmetry) {
                safeBeginSymmetryUndoMacro()
                clearPredictionState(triggerInvalidate = false)
            }
            val rawStartDocPos = docPos
            firstDocPos = rawStartDocPos
            val guideAtStart = v.drawingGuide
            val targetDocPos = if (
                guideAtStart.mode == GuideMode.PERSPECTIVE &&
                guideAtStart.perspectiveFisheye &&
                guideAtStart.assistedDrawing
            ) {
                applyAssistedDrawing(rawStartDocPos, rawStartDocPos)
            } else {
                rawStartDocPos
            }
            smoothedPressure = pressure
            currentStrokeDocPos = targetDocPos
            if (isStylus) {
                getOrCreateStylusDriver()?.feedbackManager?.setWritingHapticsEnabled(true, isEraser = (effTool() == Tool.ERASER))
            }
            // Native stroke stays untouched until a deliberate hold followed by pen-up.
            if (v.quickShapeEnabled && effTool() == Tool.BRUSH && !v.brushAirbrush &&
                com.reverie.paint.model.QuickShapeAssistPolicy.isQuickShapeAllowed(v.drawingGuide) && !v.anim.isPlaying) {
                v.quickShapeCapture = QuickShapeStrokeCapture().also {
                    val startScreen = docToScreen(targetDocPos)
                    it.begin(startScreen.x, startScreen.y, SystemClock.uptimeMillis())
                }
            }
            val overrideTool = if (effTool() == Tool.ERASER && tool != Tool.ERASER) "eraser" else null
            lastStrokeRawPressure = pressure
            beginStrokeTilt(tiltX, tiltY, targetDocPos.x, targetDocPos.y)
            strokeStarted = v.touchStart(targetDocPos.x, targetDocPos.y, pressure.toDouble(), strokeTiltX, strokeTiltY, rotation, toolOverride = overrideTool, inputEventTimeMs = eventTimeMs, isStylus = isStylus)
            if (strokeStarted) {
                // 纸张摩擦音效: 落笔起振 (橡皮稍收音量, 附带初始落笔压感)
                getOrCreateStylusDriver()?.feedbackManager?.startStrokeSound(effTool() == Tool.ERASER, pressure.toFloat())
                lastSoundTimeMs = 0L
            }
            val isAssist = hasSymmetry || (v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing)
            val assistedScreen = if (isAssist) docToScreen(targetDocPos) else screenPos

            if (hasSymmetry) {
                updateSymmetryPressureLut(v)
                val stx = strokeTiltX.toFloat()
                val sty = strokeTiltY.toFloat()
                val srot = rotation.toFloat()
                prepareMirrorBranches(
                    symmetricSamplesFor(v.smoothedStrokeX, v.smoothedStrokeY, stx, sty, srot).size
                )
                appendSymmetricSamplePair(
                    v.smoothedStrokeX, v.smoothedStrokeY, v.smoothedStrokePressure,
                    stx, sty, srot,
                    eventTimeMs = android.os.SystemClock.uptimeMillis()
                )
            } else {
                resetMirrorBranches()
            }
            if (strokeStarted) {
                armRealInkHold(
                    v,
                    hasSymmetry,
                    screenPos.x,
                    screenPos.y,
                    if (v.speedSimulatedPressureActiveForStroke) v.lastStrokeEffectivePressure.toFloat() else pressure,
                )
            }
        }
        Tool.LIQUIFY -> {
            liquifyFlushPosted = false
            removeCallbacks(liquifyFlushRunnable)
            // Phase 3A 实验开关: `setprop debug.reverie.lqcoalesce <n>` 指定每帧最多推进的
            // 补点数(0 = 不限)。默认值见 LiquifyPath.DEFAULT_MAX_DABS_PER_FLUSH:
            //   **旧默认 2 是 test16"小笔刷连续拖动串珠状断触"的主根因** —— 场路径每帧
            //   最多推进 2 个补点, 快速拖动时手指远跑在形变前面, 且被丢掉的中间补点让
            //   GPU 场上的核变稀疏。现在统一为 24 步/帧(≈1.9 万 px/s 跟随能力, 远超手速)。
            // 应用内覆盖(debug 设置页)优先于 property —— 无数据线时靠它切换
            val ov = PerfTrace.liquifyCoalesceOverride
            val prop = if (ov >= 0) ov else PerfTrace.debugPropInt("debug.reverie.lqcoalesce", -1)
            val maxDabs = when {
                // 1) property 最高优先(无数据线时也能靠构建档位兜底)
                prop >= 0 -> prop
                // 2) 构建期档位: 3 = 对照"不做调度合并"(全量推进), 其余走默认上限
                BuildConfig.LQ_TEST_PROFILE == 3 -> 0
                else -> LiquifyPath.DEFAULT_MAX_DABS_PER_FLUSH
            }
            // 交互态会话: 以落笔点为已渲染基准, 后续 MOVE 只提交"最新位置"
            liquifySession.begin(docPos.x, docPos.y, maxDabs)
            // Phase 6: 压力初值(笔输入才有意义; 手指路径由 updateLiquifyPressure 跳过)
            smoothedPressure = if (isStylus) pressure.coerceIn(0f, 1f) else 1f
            lastLiquifyEventTimeMs = 0L
            liquifyPressureActive = isStylus
            v.setLiquifyBrushSize(liquifyBrushSize.toDouble())
            v.configureLiquifyProfile(liquifyHardness)
            v.liquifyBegin()
            LiquifyGlesPreview.configureProfile(liquifyHardness)
            // Phase 5 · C3-2: 能走场通路就走 —— 引擎只交一份"未形变的源像素", 拖动期零解算。
            // 判定失败(超预算/非 8bit BGRA/覆盖层不在)自动退回下面的逐 dab 路径。
            liquifyFieldGesture = beginLiquifyFieldGesture(v)
            if (!liquifyFieldGesture) v.liquifyUseEnginePreview()
            strokeStarted = true
            liquifyHoldTimeMs = android.os.SystemClock.uptimeMillis()
            if (liquifyMode in 1..4) postOnAnimation(liquifyHoldRunnable)
        }
        Tool.PICKER -> {
            pickerActive?.value = true
            val refHex = v.brushColor
            pickerInitialColor?.value = parseColor(refHex)
            sampleColorAtScreenPos(previousSinglePos)
        }
        Tool.FILL -> {
            if (isStylus) {
                v.floodFill(docPos.x, docPos.y, fillTolerance)
            }
        }
        Tool.MAGICWAND -> {
            if (isStylus) {
                wandFlash?.value = docPos
                v.selectContiguous(docPos.x.toInt(), docPos.y.toInt())
            }
        }
        Tool.SELECT_SIMILAR -> {
            if (isStylus) {
                wandFlash?.value = docPos
                v.selectSimilar(docPos.x.toInt(), docPos.y.toInt())
            }
        }
        Tool.SELECT_POLYGON -> {
            onPolyPoint?.invoke(docPos)
            isPolyPointPendingOnTouch = true
        }
        Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
            handleShapeDown(docPos)
        }
        Tool.TEXT -> {
            handleTextDown(docPos)
        }
        Tool.GRADIENT -> {
            handleGradientDown(docPos)
        }
        Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
            if (v.selectionMode == 0) {
                v.clearSelectionOverlayLocal()
            }
            liveShapeStart?.value = docPos
            liveShapeEnd?.value = docPos
        }
        Tool.ENCLOSE_FILL -> {
            lassoPoints.clear()
            lassoPoints.add(docPos)
        }
        Tool.LASSO -> {
            if (v.selectionMode == 0 && v.lassoMultiPoints.isEmpty()) {
                v.clearSelectionOverlayLocal()
            }
            val subMode = v.lassoSubMode
            if (subMode == LassoSubMode.FREEHAND) {
                lassoPoints.clear()
                lassoPoints.add(docPos)
            } else {
                val now = android.os.SystemClock.uptimeMillis()
                val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
                val tiles = v.displayTiles.drawable()
                val bmp = if (tiles != null) null else (v.displayBitmap ?: docBitmap)?.takeIf { !it.isRecycled }
                val bmpW = (tiles?.grid?.width ?: bmp?.width ?: v.docWidth.takeIf { it > 0 } ?: 1).toFloat()
                val docW = (if (v.docWidth > 0) v.docWidth else bmpW.toInt()).toFloat()
                val bmpPerDoc = (bmpW / docW).coerceAtLeast(0.001f)
                val snapDistThreshold = (24f * density) / (currentScale * bmpPerDoc)

                // 双击闭合 (至少已有3个点时双击直接闭合选区)
                if (v.lassoMultiPoints.size >= 3 && now - lastLassoTapTimeMs < 350L &&
                    hypot(docPos.x - lastLassoTapDocPos.x, docPos.y - lastLassoTapDocPos.y) < snapDistThreshold
                ) {
                    justFinishedLassoInDown = true
                    lastLassoTapTimeMs = 0L
                    v.finishLassoMulti()
                    liveSelectionPath?.value = null
                    lassoPoints.clear()
                    return
                }
                lastLassoTapTimeMs = now
                lastLassoTapDocPos = docPos

                // 点击起点附近闭合
                if (v.lassoMultiPoints.size >= 3) {
                    val startPt = v.lassoMultiPoints.first()
                    val distToStart = hypot(docPos.x - startPt.first, docPos.y - startPt.second)
                    if (distToStart <= snapDistThreshold) {
                        justFinishedLassoInDown = true
                        lastLassoTapTimeMs = 0L
                        v.finishLassoMulti()
                        liveSelectionPath?.value = null
                        lassoPoints.clear()
                        return
                    }
                }

                lassoPoints.clear()
                lassoPoints.add(docPos)
            }
        }
        Tool.MEASURE -> {
            val s = measureStart?.value
            val e = measureEnd?.value
            val currentScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val hitThresholdDoc = (28f * density) / currentScale
            if (s != null && e != null) {
                val dStart = hypot(docPos.x - s.x, docPos.y - s.y)
                val dEnd = hypot(docPos.x - e.x, docPos.y - e.y)
                when {
                    dStart <= hitThresholdDoc && dStart <= dEnd -> {
                        activeMeasureHandle = 0
                        measureStart?.value = docPos
                    }
                    dEnd <= hitThresholdDoc -> {
                        activeMeasureHandle = 1
                        measureEnd?.value = docPos
                    }
                    else -> {
                        activeMeasureHandle = -1
                        measureStart?.value = docPos
                        measureEnd?.value = docPos
                    }
                }
            } else {
                activeMeasureHandle = -1
                measureStart?.value = docPos
                measureEnd?.value = docPos
            }
        }
        Tool.TRANSFORM -> {
            val state = tfState
            if (state != null) {
                if (!state.active) {
                    val b = v.contentBounds()
                    if (b != null && b[2] > 0 && b[3] > 0) {
                        state.reset(
                            Rect(
                                b[0].toFloat(),
                                b[1].toFloat(),
                                (b[0] + b[2]).toFloat(),
                                (b[1] + b[3]).toFloat(),
                            )
                        )
                    } else {
                        state.reset(
                            Rect(
                                0f,
                                0f,
                                v.docWidth.toFloat(),
                                v.docHeight.toFloat(),
                            )
                        )
                    }
                    v.startTransformPreview()
                }
                val handles = tfHandles(state)
                val currentScale = canvasZoom * canvasFitScale
                val baseThresholdDoc = (18f * density) / maxOf(0.01f, currentScale)

                if (state.mode == TransformMode.PERSPECTIVE) {
                    var best = -1
                    var bestD = baseThresholdDoc
                    for (i in handles.indices) {
                        val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                        if (d < bestD) {
                            bestD = d
                            best = i
                        }
                    }
                    state.handle = if (best in 0..3) best else 8
                } else if (state.mode == TransformMode.DISTORT) {
                    var best = -1
                    var baseD = baseThresholdDoc
                    for (i in handles.indices) {
                        val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                        if (d < baseD) {
                            baseD = d
                            best = i
                        }
                    }
                    state.handle = if (best in 0..15) best else 99
                } else {
                    val c = state.bounds.center
                    val dx = docPos.x - c.x - state.tx
                    val dy = docPos.y - c.y - state.ty
                    val rad = Math.toRadians(-state.rotation.toDouble())
                    val cosR = cos(rad).toFloat()
                    val sinR = sin(rad).toFloat()
                    val ux = (dx * cosR - dy * sinR) / state.scaleX
                    val uy = (dx * sinR + dy * cosR) / state.scaleY

                    val halfW = state.bounds.width / 2f
                    val halfH = state.bounds.height / 2f
                    val inBox = ux >= -halfW && ux <= halfW && uy >= -halfH && uy <= halfH

                    val scaledHalfW = halfW * abs(state.scaleX)
                    val scaledHalfH = halfH * abs(state.scaleY)
                    val maxHandleRadius = minOf(scaledHalfW, scaledHalfH) * 0.4f
                    val hitThresholdDoc = minOf(baseThresholdDoc, maxOf(1f, maxHandleRadius))

                    var bestCorner = -1
                    var bestCornerD = hitThresholdDoc
                    for (i in 0 until minOf(4, handles.size)) {
                        val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                        if (d < bestCornerD) {
                            bestCornerD = d
                            bestCorner = i
                        }
                    }

                    var bestEdge = -1
                    var bestEdgeD = baseThresholdDoc * 0.85f
                    if (bestCorner < 0 && handles.size >= 4) {
                        for (e in 0..3) {
                            val d = distanceToSegment(docPos, handles[e], handles[(e + 1) % 4])
                            if (d < bestEdgeD) {
                                bestEdgeD = d
                                bestEdge = 4 + e
                            }
                        }
                    }

                    // 框内核心平移区保护：落点在矩形中央安全区优先判定为平移，杜绝误触缩放手柄
                    val inInnerSafetyZone = inBox && halfW > 0f && halfH > 0f &&
                        (abs(ux) < halfW * 0.65f && abs(uy) < halfH * 0.65f)

                    state.handle = when {
                        inInnerSafetyZone -> 8
                        bestCorner >= 0 -> bestCorner
                        bestEdge >= 0 -> bestEdge
                        inBox -> 8
                        else -> 9
                    }
                }
                state.dragStart = docPos
                state.startScaleX = state.scaleX
                state.startScaleY = state.scaleY
                state.startRotation = state.rotation
                state.startTx = state.tx
                state.startTy = state.ty
                state.startQuadCorners = state.quadCorners.toList()
                state.startMeshPoints = state.meshPoints.toList()
            }
        }
        Tool.PANEL -> {
            val st = panelInteraction ?: return
            st.dismissMenu()
            panelDragStartDoc = docPos
            panelDownRawDoc = docPos
            panelDownScreenPos = screenPos
            panelDragCorner = -1
            panelDragEdgeIndex = -1
            panelDragSecondEdgeIndex = -1
            panelCornerSnapDirs = FloatArray(0)
            panelPendingPanelId = -1L
            panelDragIds = longArrayOf()
            panelDragStartQuads = emptyList()
            panelLongPressPending = false
            panelMarqueeArmed = false
            if (maxTouchPointers > 1) {
                // 双指接管: 本手势不分格 (视口缩放/平移走既有路径)
                panelDragMode = PANEL_DRAG_CONSUMED
                return
            }
            val currentScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val thresholdDoc = (18f * density) / currentScale
            val docPoint = Point2D(docPos.x, docPos.y)
            val mirrors = v.panels
            val quads = mirrors.map { it.meta.quad }
            val bodyHit = hitTestPanels(quads, -1, docPoint, -1f)
            val bodyIdx = (bodyHit as? PanelHit.Body)?.panelIndex ?: -1
            val bodyPanelId = if (bodyIdx in mirrors.indices) mirrors[bodyIdx].panelId else -1L
            val now = SystemClock.uptimeMillis()

            // 双击: 300ms 内两次 DOWN 同一格体 → 进格绘画 (切回笔刷)。
            // 切分模式跳过: 纯切分下双击也只是又一次切分尝试, 不进格
            if (!v.panelDivideMode && bodyPanelId >= 0 && bodyPanelId == panelLastTapPanelId &&
                now - panelLastTapTimeMs < PANEL_TAP_MS
            ) {
                panelLastTapTimeMs = 0L
                panelLastTapPanelId = -1L
                panelDragMode = PANEL_DRAG_CONSUMED
                v.enterPanelPainting(bodyPanelId)
                v.applyTool(Tool.BRUSH.id)
                return
            }
            if (bodyPanelId >= 0) {
                panelLastTapPanelId = bodyPanelId
                panelLastTapTimeMs = now
            } else {
                panelLastTapPanelId = -1L
                panelLastTapTimeMs = 0L
            }

            // 切分模式总分支: 纯切分 —— 画布任意处 (格内/缝隙/空白) 单指起笔都是切分
            // 尝试; 建格/移动/改形状/角点/共享边/轻点选中全部禁用 (选中态原样保留,
            // 切分轻点不改选中, 见 setPanelDivideMode)。优先级压过下方全部建格模式
            // 分支 (直接 return, 不与之共存)。
            // 压在格体上: 先等 480ms 长按弹菜单 (与建格同一套, 菜单用 st.menuPanelId,
            // 不改 selectedPanelId); slop 内移动则取消长按转 DIVIDE。压在空白/缝隙:
            // 保持立刻拖刀 —— 起笔吸附到最近的分格边线段 (该函数自带 8dp 半径判定,
            // 远处不吸附); 轻点 (<24dp, 用原始落点 panelDownRawDoc 判距) 在 UP 判无操作。
            if (v.panelDivideMode) {
                if (bodyPanelId >= 0) {
                    // 压在格体: panelDragStartDoc 保持原始落点 (slop 判距与建格格体一致),
                    // MOVE 里转 DIVIDE 时才把起点吸附到边框
                    panelDragMode = PANEL_DRAG_DIVIDE_PENDING
                    panelPendingPanelId = bodyPanelId
                    panelLongPressPending = true
                    panelLongPressPanelId = bodyPanelId
                    panelActiveLongPressToken = ++panelLongPressToken
                    postDelayed(panelLongPressRunnable, PANEL_LONG_PRESS_MS)
                } else {
                    val snappedDown = snapPointToPanelBorders(docPoint)
                    panelDragStartDoc = Offset(snappedDown.x, snappedDown.y)
                    panelDragMode = PANEL_DRAG_DIVIDE
                    panelPendingPanelId = -1L
                }
                return
            }

            val selIdx = mirrors.indexOfFirst { it.panelId == v.selectedPanelId }
            val hasSelection = v.selectedPanelId != null || v.panelMultiSelectedIds.isNotEmpty()
            var handled = false
            if (hasSelection && selIdx >= 0 && !v.panelDivideMode) {
                // 1) 选中格 4 角手柄 → 拖形
                val handleHit = hitTestPanels(quads, selIdx, docPoint, thresholdDoc)
                if (handleHit is PanelHit.Handle) {
                    panelDragMode = PANEL_DRAG_HANDLE
                    panelDragIds = longArrayOf(mirrors[handleHit.panelIndex].panelId)
                    panelDragStartQuads = listOf(quads[handleHit.panelIndex])
                    panelDragCorner = handleHit.corner
                    panelCornerSnapDirs = buildPanelCornerSnapDirs(v, panelDragIds[0])
                    handled = true
                }
                // 2) 选中格 4 边中点手柄 → 平行推拉整边 (8 手柄系统)
                if (!handled) {
                    val selQuad = quads[selIdx]
                    val midHit = selQuad.hitTestEdgeMidpoint(docPoint, thresholdDoc)
                    if (midHit >= 0) {
                        panelDragMode = PANEL_DRAG_EDGE_MIDPOINT
                        panelDragIds = longArrayOf(mirrors[selIdx].panelId)
                        panelDragStartQuads = listOf(selQuad)
                        panelDragEdgeIndex = midHit
                        handled = true
                    }
                }
                // 3) 选中格与相邻格的共享边 (按压点距边线段 < 阈值) → 间隔拖动
                if (!handled) {
                    val selQuad = quads[selIdx]
                    edgeSearch@ for ((oi, o) in mirrors.withIndex()) {
                        if (oi == selIdx) continue
                        val aEdge = selQuad.sharedEdge(o.meta.quad, PANEL_SHARED_EDGE_TOL) ?: continue
                        val seg0 = selQuad.corners[aEdge]
                        val seg1 = selQuad.corners[(aEdge + 1) % selQuad.corners.size]
                        if (ShapeGeometry.distanceToSegment(docPoint, seg0, seg1) > thresholdDoc) continue
                        val bEdge = o.meta.quad.sharedEdge(selQuad, PANEL_SHARED_EDGE_TOL)
                        if (bEdge == null) continue
                        panelDragMode = PANEL_DRAG_EDGE
                        panelDragIds = longArrayOf(mirrors[selIdx].panelId, mirrors[oi].panelId)
                        panelDragStartQuads = listOf(selQuad, o.meta.quad)
                        panelDragEdgeIndex = aEdge
                        panelDragSecondEdgeIndex = bEdge
                        handled = true
                        break@edgeSearch
                    }
                }
            }
            // 3) 格体命中 → 拖移 (多选时含跨格: 命中多选格则整组拖动)
            if (!handled && bodyPanelId >= 0 && !v.panelDivideMode) {
                val inMulti = v.panelMultiSelectedIds.isNotEmpty() && bodyPanelId in v.panelMultiSelectedIds
                if (inMulti) {
                    val ids = v.panelMultiSelectedIds.mapNotNull { id ->
                        mirrors.firstOrNull { it.panelId == id }?.panelId
                    }
                    panelDragMode = PANEL_DRAG_BODY
                    panelDragIds = ids.toLongArray()
                    panelDragStartQuads = ids.map { id ->
                        mirrors.first { it.panelId == id }.meta.quad
                    }
                } else {
                    panelDragMode = PANEL_DRAG_BODY_PENDING
                    panelPendingPanelId = bodyPanelId
                    panelDragIds = longArrayOf(bodyPanelId)
                    panelDragStartQuads = listOf(quads[bodyIdx])
                }
                // 长按: 压在格上 → 弹极简菜单; 长按后拖动 → 框选多选
                panelLongPressPending = true
                panelLongPressPanelId = bodyPanelId
                panelActiveLongPressToken = ++panelLongPressToken
                postDelayed(panelLongPressRunnable, PANEL_LONG_PRESS_MS)
                handled = true
            }
            // 4) 空白/缝隙: 无论有没有选中都进建格手势 —— MOVE 中若拖线贯穿既有格则
            // 转为切分预览 (选中态也能从空白外起贯穿切), UP 再按贯穿/位移分流;
            // 轻点空白 = 取消选中 (切分模式已在上方总分支 return, 此守卫是防御)。
            if (!handled && !v.panelDivideMode) {
                panelDragMode = PANEL_DRAG_CREATE
                st.createRect = PanelBounds(docPos.x, docPos.y, docPos.x, docPos.y)
            }
        }
        else -> Unit
    }
}

internal fun CanvasTouchView.handleToolMove(event: MotionEvent, pointerIndex: Int, docPos: Offset, pressure: Float, isStylus: Boolean) {
    val v = vm ?: return
    // 面板在笔画中途移动/开关时, tracker.version 变化才重建快照;
    // 必须在本帧任何 v.touchMove 之前完成。
    rebuildUiExclusionSnapshot()

    if (draggingGuideHandleIndex != -1) {
        if (handleGuideHandleDrag(docPos)) return
    }

    when (StrokeDispatch.toolForMove(strokeStarted, strokeTool, effTool())) {
        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
            // s13-C5: 本笔已被笔画中的撤销/重做中止, 抬笔前不再迟到起笔
            if (strokeAbortedUntilUp) return
            val brushR = effectiveBrushScreenRadius(v, pressure)
            hasEvasionEnv = false
            if (v.autoHidePanelsOnStroke) {
                var anyActive = false
                for (i in UiPanelSlot.EVADABLE_ARRAY.indices) {
                    if (v.panelOcclusion.isActive(UiPanelSlot.EVADABLE_ARRAY[i])) { anyActive = true; break }
                }
                if (anyActive) {
                    val curX = event.getX(pointerIndex)
                    val curY = event.getY(pointerIndex)
                    var envL = curX
                    var envT = curY
                    var envR = curX
                    var envB = curY
                    val prior = priorStrokeScreenPos
                    if (prior.x.isFinite() && prior.y.isFinite() && (prior.x != 0f || prior.y != 0f)) {
                        envL = minOf(envL, prior.x); envT = minOf(envT, prior.y)
                        envR = maxOf(envR, prior.x); envB = maxOf(envB, prior.y)
                    }
                    for (i in 0 until event.historySize) {
                        val hx = event.getHistoricalX(pointerIndex, i)
                        val hy = event.getHistoricalY(pointerIndex, i)
                        if (!hx.isFinite() || !hy.isFinite()) continue
                        envL = minOf(envL, hx); envT = minOf(envT, hy)
                        envR = maxOf(envR, hx); envB = maxOf(envB, hy)
                    }
                    if (firstDocPos != Offset.Zero) {
                        val assisted = applyAssistedDrawing(firstDocPos, docPos)
                        if (assisted.x.isFinite() && assisted.y.isFinite()) {
                            val asScreen = docToScreen(assisted)
                            envL = minOf(envL, asScreen.x); envT = minOf(envT, asScreen.y)
                            envR = maxOf(envR, asScreen.x); envB = maxOf(envB, asScreen.y)
                        }
                    }
                    if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
                        val symCount = v.drawingGuide.writeSymmetricPoints(
                            docPos.x, docPos.y,
                            v.docWidth, v.docHeight,
                            symScratchX, symScratchY
                        )
                        ensureViewTransform()
                        for (i in 0 until symCount) {
                            viewTransform.docToScreen(symScratchX[i], symScratchY[i], pointScratch)
                            val sx = pointScratch[0]
                            val sy = pointScratch[1]
                            if (sx.isFinite() && sy.isFinite()) {
                                envL = minOf(envL, sx); envT = minOf(envT, sy)
                                envR = maxOf(envR, sx); envB = maxOf(envB, sy)
                            }
                        }
                    }
                    evasionEnvL = envL
                    evasionEnvT = envT
                    evasionEnvR = envR
                    evasionEnvB = envB
                    hasEvasionEnv = true
                    evaluateUiEvasion50(v, envL, envT, envR, envB, brushR)
                }
            }
            val hasSymmetry = (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing)
            val startedNow = !strokeStarted
            if (!strokeStarted && !v.isLayerBackendBusy()) {
                val startDoc = if (firstDocPos != Offset.Zero) firstDocPos else docPos
                firstDocPos = startDoc
                updateStylusSensors(event, pointerIndex, isStylus, historyPos = -1)
                lastStrokeRawPressure = pressure
                beginStrokeTilt(touchTiltX, touchTiltY, startDoc.x, startDoc.y)
                if (hasSymmetry) {
                    safeBeginSymmetryUndoMacro()
                    clearPredictionState(triggerInvalidate = false)
                }
                val overrideTool = if (effTool() == Tool.ERASER && tool != Tool.ERASER) "eraser" else null
                strokeStarted = v.touchStart(startDoc.x, startDoc.y, pressure.toDouble(), strokeTiltX, strokeTiltY, touchRotation, toolOverride = overrideTool, inputEventTimeMs = event.eventTime, isStylus = isStylus)
                if (strokeStarted) {
                    if (isStylus) {
                        getOrCreateStylusDriver()?.feedbackManager?.setWritingHapticsEnabled(true, isEraser = (effTool() == Tool.ERASER))
                    }
                    // 迟到的笔画起点 (首帧被历史点吞掉): 补启摩擦音效
                    getOrCreateStylusDriver()?.feedbackManager?.startStrokeSound(effTool() == Tool.ERASER, pressure.toFloat())
                    lastSoundTimeMs = 0L
                    if (hasSymmetry) {
                        updateSymmetryPressureLut(v)
                        val stx = strokeTiltX.toFloat()
                        val sty = strokeTiltY.toFloat()
                        val srot = touchRotation.toFloat()
                        prepareMirrorBranches(
                            symmetricSamplesFor(v.smoothedStrokeX, v.smoothedStrokeY, stx, sty, srot).size
                        )
                        appendSymmetricSamplePair(
                            v.smoothedStrokeX, v.smoothedStrokeY, v.smoothedStrokePressure,
                            stx, sty, srot,
                            eventTimeMs = event.eventTime
                        )
                    }
                }
            }
            if (!strokeStarted) return
            // 迟到的笔画起点 (首帧被历史点吞掉) 在这里才真正落笔。
            if (startedNow && pointerIndex in 0 until event.pointerCount) {
                armRealInkHold(
                    v,
                    hasSymmetry,
                    event.getX(pointerIndex),
                    event.getY(pointerIndex),
                    if (v.speedSimulatedPressureActiveForStroke) v.lastStrokeEffectivePressure.toFloat() else pressure,
                )
            }

            v.quickShapeCapture?.let { capture ->
                // 已经按住认出之后，抬笔的几 dp 抖动不能清掉结果。继续画过 24dp 才重新计时。
                val slop = if (quickShapeCandidate != null) 24f * density else 5f * density
                if (capture.moved(event.getX(pointerIndex), event.getY(pointerIndex),
                        SystemClock.uptimeMillis(), slop)) {
                    quickShapeCandidate = null
                    removeCallbacks(quickShapeHold)
                    postDelayed(quickShapeHold, v.quickShapeHoldDelayMs.toLong().coerceIn(150L, 1200L))
                }
            }
            currentStrokeDocPos = docPos

            val effectiveDocPos = applyAssistedDrawing(firstDocPos, docPos)
            val isAssist = (v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing)
            val docToDpScale = ((canvasZoom * canvasFitScale) / density.coerceAtLeast(0.5f)).coerceAtLeast(0.0005f)

            for (i in 0 until event.historySize) {
                val hScreen = Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))
                val hDoc = screenToDoc(hScreen)
                val hAssisted = applyAssistedDrawing(firstDocPos, hDoc)
                val hP = if (isStylus) {
                    val hp = event.getHistoricalPressure(pointerIndex, i)
                    if (hp.isNaN()) 1f else hp.coerceIn(0f, 1f)
                } else 1f
                val hTime = event.getHistoricalEventTime(i)
                updateStylusSensors(event, pointerIndex, isStylus, historyPos = i)
                resolveStrokeTilt(touchTiltX, touchTiltY, hAssisted.x, hAssisted.y)
                v.touchMove(hAssisted.x, hAssisted.y, hP.toDouble(), hTime, strokeTiltX, strokeTiltY, touchRotation, isStylus = isStylus, docToDpScale = docToDpScale)
                if (hasSymmetry) {
                    appendSymmetricSamplePair(
                        v.smoothedStrokeX, v.smoothedStrokeY, v.smoothedStrokePressure,
                        strokeTiltX.toFloat(), strokeTiltY.toFloat(), touchRotation.toFloat(),
                        eventTimeMs = hTime
                    )
                }
            }

            // Dual-track replacement: immediately clear old prediction upon real event reception
            clearPredictedScreenPoint()

            // 双系统分派:
            // - 前缓冲预测开启 (默认): 卡尔曼训练 + 空窗回填 + 前缓冲直出 (本次低延迟技术)
            // - 前缓冲预测关闭: 上游原版"超低延迟笔迹预测" (OEM 硬件预测器渐隐尾线, 仅 OPPO 有效)
            val frontBufferPrediction = !hasSymmetry && v.frontBufferPredictionEnabled && RealInkHoldPolicy.isRealInkTool(tool, effTool())
            val fidelityTier = if (frontBufferPrediction) v.effectivePredictionTier else PaintViewModel.PredictionFidelityTier.NONE

            if (frontBufferPrediction && fidelityTier != PaintViewModel.PredictionFidelityTier.NONE) {
                // 1. 通用 4 阶卡尔曼滤波器与物理采样训练（前向几何预测时间窗与后台渲染耗时彻底解耦）
                // 几何外推时间窗上限严格锁定为 MAX_PREDICTION_MS = 32ms (约 1~2 帧)，外推至 2.0 帧呈现前瞻，紧贴笔尖真实落点，防止高速运笔过冲断裂
                val frameTimeMs = universalPredictor.avgReportRateMs.coerceIn(4f, 16.67f)
                val rawLeadMs = (frameTimeMs * 2.0f).coerceIn(16f, UniversalKalmanPredictor.MAX_PREDICTION_MS)
                smoothedLeadMs = if (smoothedLeadMs <= 0f) rawLeadMs else (smoothedLeadMs * 0.8f + rawLeadMs * 0.2f)
                universalPredictor.setPredictionTargetMs(smoothedLeadMs)
                if (pointerIndex in 0 until event.pointerCount) {
                    val simPredP = if (v.speedSimulatedPressureActiveForStroke) {
                        v.lastStrokeEffectivePressure.toFloat().coerceIn(0.01f, 1f)
                    } else {
                        null
                    }
                    val histCount = event.historySize
                    for (i in 0 until histCount) {
                        val hx = event.getHistoricalX(pointerIndex, i)
                        val hy = event.getHistoricalY(pointerIndex, i)
                        val rawHp = simPredP ?: if (isStylus) event.getHistoricalPressure(pointerIndex, i) else 1f
                        val hp = if (rawHp.isFinite()) rawHp.coerceIn(0.01f, 1f) else 1f
                        val ht = event.getHistoricalEventTime(i)
                        if (hx.isFinite() && hy.isFinite()) {
                            universalPredictor.addPoint(hx, hy, hp, ht)
                            recordTouchSample(hx, hy, ht)
                        }
                    }
                    val curX = event.getX(pointerIndex)
                    val curY = event.getY(pointerIndex)
                    val rawP = simPredP ?: if (isStylus) pressure else 1f
                    val curP = if (rawP.isFinite()) rawP.coerceIn(0.01f, 1f) else 1f
                    val curT = event.eventTime
                    if (curX.isFinite() && curY.isFinite()) {
                        universalPredictor.addPoint(curX, curY, curP, curT)
                        recordTouchSample(curX, curY, curT)
                        priorStrokeScreenPos = Offset(universalPredictor.prevPosX, universalPredictor.prevPosY)
                    }
                }

                // 2. 笔尖前向超前预测计算 (分级放行：TIER_1/2/STAMP 放行，NONE 已在外层拦截)
                var predictionObtained = false

                val hp = huaweiPredictor
                if (!predictionObtained && hp != null && hp.isValid) {
                    try {
                        if (hp.predictPoint(event, pointerIndex, tempOemPoint)) {
                            val hx = tempOemPoint[0]
                            val hy = tempOemPoint[1]
                            val hpVal = tempOemPoint[2]
                            if (hx.isFinite() && hy.isFinite() && (hx != 0f || hy != 0f) &&
                                (hx != event.getX(pointerIndex) || hy != event.getY(pointerIndex))) {
                                setPredictedScreenPoint(hx, hy)
                                predictedPressure = if (hpVal.isFinite()) hpVal.coerceIn(0.01f, 1f) else 1f
                                predictionObtained = true
                            }
                        }
                    } catch (_: Throwable) {}
                }

                val xp = xiaomiPredictor
                if (!predictionObtained && xp != null && xp.isValid) {
                    try {
                        if (xp.predictPoint(event, pointerIndex, tempOemPoint)) {
                            val xx = tempOemPoint[0]
                            val xy = tempOemPoint[1]
                            val xpVal = tempOemPoint[2]
                            if (xx.isFinite() && xy.isFinite() && (xx != 0f || xy != 0f) &&
                                (xx != event.getX(pointerIndex) || xy != event.getY(pointerIndex))) {
                                setPredictedScreenPoint(xx, xy)
                                predictedPressure = if (xpVal.isFinite()) xpVal.coerceIn(0.01f, 1f) else 1f
                                predictionObtained = true
                            }
                        }
                    } catch (_: Throwable) {}
                }

                // 通用 4 阶卡尔曼前向预测（纯 Kotlin 几何外推兜底）
                if (!predictionObtained) {
                    try {
                        if (pointerIndex in 0 until event.pointerCount) {
                            val curX = event.getX(pointerIndex)
                            val curY = event.getY(pointerIndex)
                            if (curX.isFinite() && curY.isFinite()) {

                                val hasPred = universalPredictor.predictTouchPointScalar(tempScalarPredPoint)
                                if (hasPred) {
                                    val predX = tempScalarPredPoint[0]
                                    val predY = tempScalarPredPoint[1]
                                    val predP = tempScalarPredPoint[2]
                                    val curPos = Offset(curX, curY)
                                    val dx = predX - curPos.x
                                    val dy = predY - curPos.y
                                    val dist = kotlin.math.hypot(dx, dy)
                                    val curDiag = if (width > 0 && height > 0) {
                                        kotlin.math.hypot(width.toFloat(), height.toFloat())
                                    } else {
                                        universalPredictor.screenDiagonal
                                    }
                                    val wildDiag = curDiag > 0f && dist > (curDiag / 6f)

                                    // 门控检查：笔迹真实移动方向与预测方向的锐角偏差 (cos(theta) >= 0.55)
                                    var angleOk = true
                                    val prevPos = priorStrokeScreenPos
                                    if (prevPos != Offset.Zero && prevPos != curPos) {
                                        val v1x = curPos.x - prevPos.x
                                        val v1y = curPos.y - prevPos.y
                                        val len1 = kotlin.math.hypot(v1x, v1y)
                                        if (len1 > 1.5f && dist > 1.5f) {
                                            val dot = (v1x * dx + v1y * dy) / (len1 * dist)
                                            if (dot < 0.55f) {
                                                angleOk = false
                                            }
                                        }
                                    }

                                    if (!wildDiag && angleOk) {
                                        setPredictedScreenPoint(predX, predY)
                                        predictedPressure = if (predP.isFinite()) predP.coerceIn(0.01f, 1f) else 1f
                                        predictionObtained = true
                                    } else {
                                        clearPredictedScreenPoint()
                                    }
                                } else {
                                    clearPredictedScreenPoint()
                                }
                            } else {
                                clearPredictedScreenPoint()
                            }
                        } else {
                            clearPredictedScreenPoint()
                        }
                    } catch (_: Throwable) {
                        clearPredictedScreenPoint()
                    }
                }

                // 预览活动打点: 预测成功或回填段绘制都算"活跃", 看门狗据此判定真正停滞。
                // 回填段独立于预测工作, 慢速/急转时预测被抑制但预览仍在刷新, 不能清。
                lastPredictionUptimeMs = android.os.SystemClock.uptimeMillis()
                if (!isWatchdogScheduled) {
                    isWatchdogScheduled = true
                    postDelayed(predictionStagnationRunnable, 50L)
                }
                if (!predictionObtained) {
                    clearPredictedScreenPoint()
                }

                // 3. 前缓冲直出: 捕获到移动事件并计算好最新笔尖前沿后立即直出, 绕过 VSYNC。
                // 门控与 onDraw 软件回退互补 (canRenderPreview 为 false 时由软件回退接管,
                // 见 FrontBufferProbe.softwareFallbackRequired): 渲染器初始化失败时不能在此
                // 置 drew=true, 否则直出与回退两边都不画。
                if (hasPredictedScreenPoint) {
                    // 预测点并入本帧包络：只提前触发避让，不允许把已避让面板误判为离开。
                    if (hasEvasionEnv) {
                        evaluateUiEvasion50(
                            v,
                            minOf(evasionEnvL, predictedScreenX),
                            minOf(evasionEnvT, predictedScreenY),
                            maxOf(evasionEnvR, predictedScreenX),
                            maxOf(evasionEnvB, predictedScreenY),
                            brushR,
                        )
                    } else {
                        evaluateUiEvasion50(
                            v, predictedScreenX, predictedScreenY,
                            predictedScreenX, predictedScreenY, brushR,
                        )
                    }
                }

                if (!realInk.held) {
                    invalidateCursor(
                        if (hasLocalCursorPos) localCursorX else Float.NaN,
                        if (hasLocalCursorPos) localCursorY else Float.NaN
                    )
                }
            } else if (!v.frontBufferPredictionEnabled && isStylus && v.isCurrentBrushPredictionEligible) {
                // OEM 反射 / 系统前向预测尾线 (抵消 144Hz 屏幕 1~2 帧物理上屏延迟)
                val hp = huaweiPredictor
                if (hp != null && hp.isValid) {
                    try {
                        if (hp.predictPoint(event, pointerIndex, tempOemPoint)) {
                            val hx = tempOemPoint[0]
                            val hy = tempOemPoint[1]
                            val hpVal = tempOemPoint[2]
                            if (hx.isFinite() && hy.isFinite() && (hx != 0f || hy != 0f) &&
                                (hx != event.getX(pointerIndex) || hy != event.getY(pointerIndex))) {
                                setPredictedScreenPoint(hx, hy)
                                predictedPressure = if (hpVal.isFinite()) hpVal.coerceIn(0.01f, 1f) else 1f
                            } else {
                                clearPredictedScreenPoint()
                            }
                        } else {
                            clearPredictedScreenPoint()
                        }
                    } catch (_: Throwable) {
                        clearPredictedScreenPoint()
                    }
                } else {
                    val xp = xiaomiPredictor
                    if (xp != null && xp.isValid) {
                        try {
                            if (xp.predictPoint(event, pointerIndex, tempOemPoint)) {
                                val xx = tempOemPoint[0]
                                val xy = tempOemPoint[1]
                                val xpVal = tempOemPoint[2]
                                if (xx.isFinite() && xy.isFinite() && (xx != 0f || xy != 0f) &&
                                    (xx != event.getX(pointerIndex) || xy != event.getY(pointerIndex))) {
                                    setPredictedScreenPoint(xx, xy)
                                    predictedPressure = if (xpVal.isFinite()) xpVal.coerceIn(0.01f, 1f) else 1f
                                } else {
                                    clearPredictedScreenPoint()
                                }
                            } else {
                                clearPredictedScreenPoint()
                            }
                        } catch (_: Throwable) {
                            clearPredictedScreenPoint()
                        }
                    } else {
                        var predicted = false
                        try {
                            if (pointerIndex in 0 until event.pointerCount) {
                                val curX = event.getX(pointerIndex)
                                val curY = event.getY(pointerIndex)
                                if (curX.isFinite() && curY.isFinite()) {
                                    if (universalPredictor.predictTouchPointScalar(tempScalarPredPoint)) {
                                        val px = tempScalarPredPoint[0]
                                        val py = tempScalarPredPoint[1]
                                        val pp = tempScalarPredPoint[2]
                                        if (px.isFinite() && py.isFinite() && (px != 0f || py != 0f) && (px != curX || py != curY)) {
                                            setPredictedScreenPoint(px, py)
                                            predictedPressure = if (pp.isFinite()) pp.coerceIn(0.01f, 1f) else 1f
                                            predicted = true
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        if (!predicted) {
                            clearPredictedScreenPoint()
                        }
                    }
                }
                if (frontBufferOverlay != null) {
                    frontBufferOverlay?.clearPreview()
                }
            } else {
                clearPredictedScreenPoint()
                if (frontBufferOverlay != null) {
                    frontBufferOverlay?.clearPreview()
                }
            }

            // 受控预测前馈的输入: 本事件的预测笔尖 (文档坐标)。辅助绘图 (参考线吸附) 与非画笔
            // 工具不前馈; 是否采用、前推多少由 touchMove 按夹角与步长上限决定。
            // 预测在上面的分派里按本事件算好, 所以真实采样点在预测之后才提交。
            // 按实际生效的工具判断 (笔尾橡皮 / 临时擦除时选中的工具不是 ERASER, 但画的是橡皮)。
            var predDocX = Float.NaN
            var predDocY = Float.NaN
            val ffTool = effTool()
            if (!isAssist && hasPredictedScreenPoint && (ffTool == Tool.BRUSH || ffTool == Tool.ERASER)) {
                val pDoc = screenToDoc(Offset(predictedScreenX, predictedScreenY))
                predDocX = pDoc.x
                predDocY = pDoc.y
            }

            updateStylusSensors(event, pointerIndex, isStylus, historyPos = -1)
            lastStrokeRawPressure = pressure
            resolveStrokeTilt(touchTiltX, touchTiltY, effectiveDocPos.x, effectiveDocPos.y)
            v.touchMove(
                effectiveDocPos.x,
                effectiveDocPos.y,
                pressure.toDouble(),
                event.eventTime,
                strokeTiltX,
                strokeTiltY,
                touchRotation,
                predDocX,
                predDocY,
                isStylus = isStylus,
                docToDpScale = docToDpScale,
            )

            // 纸张摩擦音效: 按文档坐标瞬时速度与压感动态调制增益与共振 (零分配, 单次 volatile 写)
            val soundDt = event.eventTime - lastSoundTimeMs
            if (lastSoundTimeMs != 0L && soundDt > 0) {
                val sdx = effectiveDocPos.x - lastSoundDocPos.x
                val sdy = effectiveDocPos.y - lastSoundDocPos.y
                val speedPxPerMs = kotlin.math.sqrt(sdx * sdx + sdy * sdy) / soundDt
                getOrCreateStylusDriver()?.feedbackManager?.updateStrokeSound(speedPxPerMs, pressure.toFloat())
            }
            lastSoundDocPos = effectiveDocPos
            lastSoundTimeMs = event.eventTime

            if (hasSymmetry) {
                appendSymmetricSamplePair(
                    v.smoothedStrokeX, v.smoothedStrokeY, v.smoothedStrokePressure,
                    strokeTiltX.toFloat(), strokeTiltY.toFloat(), touchRotation.toFloat(),
                    eventTimeMs = event.eventTime
                )
                if (!v.symmetryMirrorLive && !realInk.held) {
                    invalidate()
                }
            }
            if (realInk.held && pointerIndex in 0 until event.pointerCount) {
                val sx = event.getX(pointerIndex)
                val sy = event.getY(pointerIndex)
                updatePredictedTip(v, sx, sy)
                snapshotHoldGuide(v)
                noteHoldCursor(
                    sx,
                    sy,
                    if (v.speedSimulatedPressureActiveForStroke) v.smoothedStrokePressure.toFloat() else pressure,
                )
            }
        }
        Tool.LIQUIFY -> {
            if (strokeStarted) {
                // Phase 6(手感, 对标 CSP/SAI2 的笔压响应): 强度跟随笔压 —— 只做平滑,
                // 不做曲线; 手指输入固定不调制(避免触摸压力噪声让手感发抖)。
                updateLiquifyPressure(event, pointerIndex)
                lastLiquifyEventTimeMs = event.eventTime
                if (liquifySession.coalescing) {
                    // Preserve coalesced history; only GPU/engine dispatch is frame-batched.
                    for (i in 0 until event.historySize) {
                        val historical = screenToDoc(Offset(event.getHistoricalX(pointerIndex, i),
                            event.getHistoricalY(pointerIndex, i)))
                        val pressure = if (liquifyPressureActive)
                            0.55f + 0.45f * event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f)
                        else 1f
                        liquifySession.submitTarget(historical.x, historical.y, pressure)
                    }
                    liquifySession.submitTarget(docPos.x, docPos.y, liquifyPressureFactor())
                    if (!liquifyFlushPosted) {
                        liquifyFlushPosted = true
                        postOnAnimation(liquifyFlushRunnable)
                    }
                } else {
                    // 历史点(coalesced)必须一起消费: 原先只取当帧最终点, 手快时
                    // 一次事件跨几十像素, 形变搭接不上就会留下断口
                    for (i in 0 until event.historySize) {
                        liquifyAlongPath(v, screenToDoc(Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))))
                    }
                    liquifyAlongPath(v, docPos)
                }
            }
        }
        Tool.PICKER -> {
            sampleColorAtScreenPos(previousSinglePos)
        }
        Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
            handleShapeMove(docPos)
        }
        Tool.TEXT -> {
            handleTextMove(docPos)
        }
        Tool.GRADIENT -> {
            handleGradientMove(docPos)
        }
        Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
            shapeEndDocPos = docPos
            liveShapeEnd?.value = docPos
        }
        Tool.ENCLOSE_FILL -> {
            val lastPt = lassoPoints.lastOrNull()
            val minMove = 3f
            if (lastPt == null || hypot(docPos.x - lastPt.x, docPos.y - lastPt.y) >= minMove) {
                lassoPoints.add(docPos)
            }
            val now = System.nanoTime()
            if (now - lastLassoPreviewNs > 16_000_000L) {
                lastLassoPreviewNs = now
                updateLiveSelectionPathFromPoints(lassoPoints, closed = true)
            }
        }
        Tool.LASSO -> {
            if (justFinishedLassoInDown) return
            val subMode = v.lassoSubMode
            val lastPt = lassoPoints.lastOrNull()
            val minMove = 3f
            if (lastPt == null || hypot(docPos.x - lastPt.x, docPos.y - lastPt.y) >= minMove) {
                lassoPoints.add(docPos)
            }
            val now = System.nanoTime()
            if (now - lastLassoPreviewNs > 16_000_000L) {
                lastLassoPreviewNs = now
                if (subMode == LassoSubMode.FREEHAND) {
                    updateLiveSelectionPathFromPoints(lassoPoints, closed = true)
                } else if (subMode == LassoSubMode.POLYLINE) {
                    val preview = v.lassoMultiPoints.map { Offset(it.first.toFloat(), it.second.toFloat()) } + docPos
                    updateLiveSelectionPathFromPoints(preview, closed = preview.size >= 3)
                } else {
                    val preview = v.lassoMultiPoints.map { Offset(it.first.toFloat(), it.second.toFloat()) } + lassoPoints
                    updateLiveSelectionPathFromPoints(preview, closed = preview.size >= 3)
                }
            }
        }
        Tool.MEASURE -> {
            when (activeMeasureHandle) {
                0 -> measureStart?.value = docPos
                else -> measureEnd?.value = docPos
            }
        }
        Tool.TRANSFORM -> {
            shapeEndDocPos = docPos
            val state = tfState
            if (state != null && state.active && state.handle >= 0) {
                val c = state.bounds.center
                val imagePos = docPos
                when {
                    state.mode == TransformMode.DISTORT -> {
                        val delta = imagePos - state.dragStart
                        if (state.handle in 0..15) {
                            val newMesh = state.startMeshPoints.toMutableList()
                            newMesh[state.handle] = state.startMeshPoints[state.handle] + delta
                            state.meshPoints = newMesh
                        } else {
                            state.meshPoints = state.startMeshPoints.map { it + delta }
                        }
                    }
                    state.mode == TransformMode.PERSPECTIVE -> {
                        val delta = imagePos - state.dragStart
                        if (state.handle in 0..3) {
                            val idx = state.handle
                            val newCorners = state.startQuadCorners.toMutableList()
                            newCorners[idx] = state.startQuadCorners[idx] + delta
                            state.quadCorners = newCorners
                        } else {
                            state.quadCorners = state.startQuadCorners.map { it + delta }
                        }
                    }
                    state.handle == 1 || state.handle == 3 || state.handle == 9 -> {
                        val a1 = atan2(state.dragStart.y - c.y - state.startTy, state.dragStart.x - c.x - state.startTx)
                        val a2 = atan2(imagePos.y - c.y - state.startTy, imagePos.x - c.x - state.startTx)
                        val d = Math.toDegrees((a2 - a1).toDouble()).toFloat()
                        state.rotation = state.startRotation + d
                    }
                    state.handle == 0 || state.handle == 2 -> {
                        val rad = Math.toRadians(-state.startRotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val dx = imagePos.x - c.x - state.startTx
                        val dy = imagePos.y - c.y - state.startTy
                        val ux = dx * cosR - dy * sinR
                        val uy = dx * sinR + dy * cosR

                        val sdx = state.dragStart.x - c.x - state.startTx
                        val sdy = state.dragStart.y - c.y - state.startTy
                        val sux = sdx * cosR - sdy * sinR
                        val suy = sdx * sinR + sdy * cosR

                        val kx = if (abs(sux) > 1f) ux / sux else 1f
                        val ky = if (abs(suy) > 1f) uy / suy else 1f

                        if (state.mode == TransformMode.STANDARD) {
                            val k = if (abs(kx - 1f) > abs(ky - 1f)) kx else ky
                            state.scaleX = state.startScaleX * k
                            state.scaleY = state.startScaleY * k
                        } else {
                            state.scaleX = state.startScaleX * kx
                            state.scaleY = state.startScaleY * ky
                        }
                    }
                    state.handle == 4 || state.handle == 6 -> {
                        val rad = Math.toRadians(-state.startRotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val dy = imagePos.y - c.y - state.startTy
                        val dx = imagePos.x - c.x - state.startTx
                        val uy = dx * sinR + dy * cosR

                        val sdy = state.dragStart.y - c.y - state.startTy
                        val sdx = state.dragStart.x - c.x - state.startTx
                        val suy = sdx * sinR + sdy * cosR

                        val ky = if (abs(suy) > 1f) uy / suy else 1f
                        state.scaleY = state.startScaleY * ky
                    }
                    state.handle == 5 || state.handle == 7 -> {
                        val rad = Math.toRadians(-state.startRotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val dx = imagePos.x - c.x - state.startTx
                        val dy = imagePos.y - c.y - state.startTy
                        val ux = dx * cosR - dy * sinR

                        val sdx = state.dragStart.x - c.x - state.startTx
                        val sdy = state.dragStart.y - c.y - state.startTy
                        val sux = sdx * cosR - sdy * sinR

                        val kx = if (abs(sux) > 1f) ux / sux else 1f
                        state.scaleX = state.startScaleX * kx
                    }
                    state.handle == 8 -> {
                        state.tx = state.startTx + (imagePos.x - state.dragStart.x)
                        state.ty = state.startTy + (imagePos.y - state.dragStart.y)
                    }
                }
            }
        }
        Tool.PANEL -> {
            val st = panelInteraction ?: return
            if (panelDragMode == PANEL_DRAG_NONE || panelDragMode == PANEL_DRAG_CONSUMED) return
            if (maxTouchPointers > 1) {
                // 双指接管: 作废本次分格手势 (视口手势照旧)
                cancelPanelGesture()
                return
            }
            val currentScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val moved = hypot(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y) >
                (PANEL_TAP_SLOP_DP * density) / currentScale
            if (panelLongPressPending && moved) {
                removeCallbacks(panelLongPressRunnable)
                panelLongPressToken++
                panelLongPressPending = false
            }
            if (panelMarqueeArmed && moved && !v.panelDivideMode) {
                // 长按后拖动 → 收菜单, 转框选多选 (切分模式不转: 长按成功已 CONSUMED,
                // 这是双保险, DIVIDE_PENDING 也不会武装 marquee)
                panelMarqueeArmed = false
                st.menuScreenPos = null
                st.menuPanelId = null
                panelDragMode = PANEL_DRAG_MARQUEE
            }
            // 未选中态压格后拖动: 先收敛为单选该格再拖移 (点选留给 UP 无位移)
            if (panelDragMode == PANEL_DRAG_BODY_PENDING && moved) {
                v.panelMultiSelectedIds = emptySet()
                v.selectedPanelId = panelPendingPanelId
                panelDragMode = PANEL_DRAG_BODY
            }
            // 切分模式压格体后 slop 内移动: 起点吸附到边框后转 DIVIDE (放在 when 之前,
            // 本帧立刻画切刀预览); 起点用原始落点再吸附, 与空白处起笔口径一致
            if (panelDragMode == PANEL_DRAG_DIVIDE_PENDING && moved) {
                val snappedDown = snapPointToPanelBorders(Point2D(panelDownRawDoc.x, panelDownRawDoc.y))
                panelDragStartDoc = Offset(snappedDown.x, snappedDown.y)
                panelDragMode = PANEL_DRAG_DIVIDE
            }
            // MOVE 只更新本地预览 (供覆盖层绘制); 引擎调用统一留到 UP
            when (panelDragMode) {
                PANEL_DRAG_HANDLE -> {
                    st.snapGuides = emptyList()
                    val q0 = panelDragStartQuads.firstOrNull()
                    if (q0 != null && panelDragCorner in q0.corners.indices &&
                        panelDragIds.isNotEmpty()
                    ) {
                        val corners = q0.corners.toMutableList()
                        corners[panelDragCorner] = Point2D(docPos.x, docPos.y)
                        // 角点角度吸附: 相邻边与邻格边/水平/垂直角度差 < 2.5° 时吸附到
                        // 精确平行 (绕相邻固定端点旋转, 边长不变); 斜边目标无可画参考线
                        val snap = cornerAngleSnap(
                            corners, panelDragCorner,
                            panelCornerSnapDirs, PANEL_PARALLEL_SNAP_ANGLE_RAD,
                        )
                        if (snap != null) {
                            corners[panelDragCorner] = snap.corner
                            st.snapGuides = if (snap.guideAxis >= 0) {
                                listOf(PanelSnapGuide(snap.guideAxis, snap.guideCoord))
                            } else {
                                emptyList()
                            }
                        }
                        st.dragPreviews = listOf(PanelPreviewEntry(panelDragIds[0], PanelQuad(corners)))
                    }
                }
                PANEL_DRAG_EDGE_MIDPOINT -> {
                    st.snapGuides = emptyList()
                    val q0 = panelDragStartQuads.firstOrNull()
                    if (q0 != null && panelDragEdgeIndex >= 0 && panelDragIds.isNotEmpty()) {
                        val rawDelta = Point2D(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                        val (snappedQuad, guide) = computeMidpointEdgeSnap(q0, panelDragEdgeIndex, rawDelta, currentScale)
                        st.snapGuides = if (guide != null) listOf(guide) else emptyList()
                        if (snappedQuad != null) {
                            st.dragPreviews = listOf(PanelPreviewEntry(panelDragIds[0], snappedQuad))
                        }
                    }
                }
                PANEL_DRAG_EDGE -> {
                    val rawDelta = Point2D(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                    val (pair, guide) = computeEdgeSnap(rawDelta, currentScale)
                    st.snapGuides = if (guide != null) listOf(guide) else emptyList()
                    st.dragPreviews = if (pair != null) {
                        listOf(
                            PanelPreviewEntry(panelDragIds[0], pair.first),
                            PanelPreviewEntry(panelDragIds[1], pair.second),
                        )
                    } else {
                        // 拖过头 (面积违规): 保持原样预览, 不给半更新
                        emptyList()
                    }
                }
                PANEL_DRAG_BODY -> {
                    val rawDelta = Point2D(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                    val (snappedDelta, guides) = computeBodySnap(rawDelta, currentScale)
                    st.snapGuides = guides
                    st.dragPreviews = panelDragIds.mapIndexed { i, id ->
                        PanelPreviewEntry(id, panelDragStartQuads[i].movedBy(snappedDelta))
                    }
                }
                PANEL_DRAG_DIVIDE -> {
                    // 预览切线与 UP 共用方向锁; 没锁方向时终点仍贴边框
                    val startDoc = Point2D(panelDragStartDoc.x, panelDragStartDoc.y)
                    val line = resolveDivideLine(startDoc, Point2D(docPos.x, docPos.y), currentScale)
                    st.divideLine = line.start to line.end
                    st.divideHighlightPanelIds = panelDivideHitIds(line.start, line.end, currentScale)
                    st.snapGuides = if (line.guideAxis >= 0) {
                        listOf(PanelSnapGuide(line.guideAxis, line.guideCoord))
                    } else {
                        emptyList()
                    }
                }
                PANEL_DRAG_MARQUEE -> {
                    st.snapGuides = emptyList()
                    val left = minOf(panelDragStartDoc.x, docPos.x)
                    val right = maxOf(panelDragStartDoc.x, docPos.x)
                    val top = minOf(panelDragStartDoc.y, docPos.y)
                    val bottom = maxOf(panelDragStartDoc.y, docPos.y)
                    st.marqueeRect = PanelBounds(left, top, right, bottom)
                }
                PANEL_DRAG_CREATE -> {
                    st.snapGuides = emptyList()
                    st.divideLine = null
                    st.divideHighlightPanelIds = emptySet()
                    val left = minOf(panelDragStartDoc.x, docPos.x)
                    val right = maxOf(panelDragStartDoc.x, docPos.x)
                    val top = minOf(panelDragStartDoc.y, docPos.y)
                    val bottom = maxOf(panelDragStartDoc.y, docPos.y)
                    st.createRect = PanelBounds(left, top, right, bottom)
                }
                else -> {
                    st.snapGuides = emptyList()
                }
            }
        }
        else -> Unit
    }
}

internal fun CanvasTouchView.handleToolUp(event: MotionEvent, docPos: Offset, isCancel: Boolean, isStylus: Boolean = false) {
    val v = vm ?: return
    // 正常抬笔走 80ms 延续窗口（防高频排线闪烁）；取消则立即复位。
    if (isCancel) clearAllEvasionState(v) else scheduleClearEvasionAfterUp()
    removeCallbacks(quickShapeHold)
    removeCallbacks(liquifyHoldRunnable)

    if (draggingGuideHandleIndex != -1) {
        draggingGuideHandleIndex = -1
        if (strokeStarted) cancelActiveStroke(v)
        return
    }

    when (StrokeDispatch.finish(strokeStarted, strokeTool, effTool(), isCancel)) {
        StrokeFinish.NONE -> Unit
        StrokeFinish.CANCEL -> {
            cancelActiveStroke(v)
            return
        }
        StrokeFinish.COMMIT -> Unit
    }

    when (StrokeDispatch.toolForMove(strokeStarted, strokeTool, effTool())) {
        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
            val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
            if (strokeStarted) {
                cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                cachedDriver?.feedbackManager?.stopStrokeSound()
                val capture = v.quickShapeCapture
                v.quickShapeCapture = null
                // 只有按住满 650ms 并且当时认出图形，抬笔才进入编辑。没按住的笔迹保持手绘。
                val recognized = quickShapeCandidate
                quickShapeCandidate = null
                if (!isCancel && v.quickShapeEnabled && effTool() == Tool.BRUSH &&
                    recognized != null && capture != null && !capture.overflowed) {
                    realInk.releaseAndCatchUp()
                    // 12 条强制复位路径之一：停顿进入 QuickShapeEditor。
                    clearAllEvasionState(v)
                    val snapRecognized = if (recognized.type == QuickShapeType.LINE && recognized.points.size == 2) {
                        val p0 = snapPointToPanelBorders(recognized.points[0])
                        val p1 = snapPointToPanelBorders(recognized.points[1])
                        recognized.copy(points = listOf(p0, p1), center = Point2D((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f))
                    } else {
                        recognized
                    }
                    val symmetryGuide = if (hasSymmetry) v.drawingGuide else null
                    val started = v.beginQuickShape(snapRecognized, capture.snapshot(), symmetryGuide = symmetryGuide)
                    resetMirrorBranches()
                    safeEndSymmetryUndoMacro()
                    if (!started) {
                        v.touchEnd(strokeSeq = realInk.currentStrokeSeq)
                    }
                } else if (isCancel) {
                    abortAndClearRealInk()
                    v.touchCancel()
                    resetMirrorBranches()
                    safeEndSymmetryUndoMacro()
                } else {
                    val hasMirrorSamples = hasSymmetry && mirrorBranchHasSamples()
                    // s12: 镜像已经由各分支独立 paintop 在打开的笔触里实时画过: 抬笔只收尾, 不再重放。
                    // 引擎拒绝 (快速路径 / 双笔刷蒙版 / 引擎绕过选区) 或抬笔时引擎还没确认时
                    // resolveMirrorLiveAtPenUp 返回 false 并停掉镜像队列, 走重放退路 (不会双画)。
                    val liveMirror = hasMirrorSamples && v.resolveMirrorLiveAtPenUp()
                    // s13-C5 兜底: 本笔的引擎笔触已被撤销/重做/取消清掉 (mirrorStrokeAborted),
                    // 镜像缓冲不能再整笔重放成一条只有镜像的新笔画
                    val hasBranchesToReplay = hasMirrorSamples && !liveMirror && !v.mirrorStrokeAborted
                    val isEraserStroke = (strokeTool == Tool.ERASER) || (v.isEraserMode && strokeTool == Tool.BRUSH)
                    val overrideTool = if (strokeTool == Tool.ERASER && tool != Tool.ERASER) "eraser" else null
                    // touchEnd 会给主笔补发追赶点与尾锋点。这些点也必须进镜像分支, 否则镜像笔迹缺少收尾
                    // R4-B: 抬笔末端压感 (最近一次真实输入过校准后的有效压感);
                    // 镜像收尾与主笔收尾用同一个值, 保证两条收笔补点序列逐字一致
                    val tailEndP = if (v.speedSimulatedPressureActiveForStroke) {
                        v.lastStrokeEffectivePressure.coerceIn(0.01, 1.0)
                    } else if (lastStrokeRawPressure.isFinite() && lastStrokeRawPressure in 0f..1f) {
                        v.computeEffectivePressure(lastStrokeRawPressure.toDouble())
                    } else {
                        v.smoothedStrokePressure
                    }
                    if (hasMirrorSamples) {
                        appendMirrorCatchUpAndTaper(v, event.eventTime, tailEndP)
                    }
                    // 仅当没有镜像分支重放时主笔才立即全量渲染且立即还原覆盖状态；有分支则在镜像分支执行完毕后统一渲染与还原
                    v.touchEnd(
                        render = !hasBranchesToReplay,
                        deferOverrideRestore = hasBranchesToReplay,
                        strokeSeq = realInk.currentStrokeSeq,
                        tailEndPressure = tailEndP,
                        tailTiltX = strokeTiltX,
                        tailTiltY = strokeTiltY,
                    )
                    if (hasBranchesToReplay) {
                        // 镜像分支重放走主画布刷新, 前缓冲这一笔就此交还。
                        realInk.releaseAndCatchUp()
                        isSymmetryReplaying = true
                        v.replaySymmetricBranches(
                            mirroredSamples,
                            mirroredSizes,
                            mirroredSamples.size,
                            isEraser = isEraserStroke,
                            overrideTool = overrideTool,
                            onComplete = {
                                resetMirrorBranches()
                                isSymmetryUndoMacroOpen = false
                                invalidate()
                            }
                        )
                    } else {
                        // P1: liveMirror 时重放已跳过, 镜像分支须仅写入录制流 (不发引擎)。
                        // 须在清缓冲之前调, 且仅当本笔未被撤销/取消中止时才补录;
                        // hasBranchesToReplay==true 时 replay 内已录制, 这里不再重复。
                        if (liveMirror && !v.mirrorStrokeAborted && hasMirrorSamples) {
                            v.recordMirroredBranchesOnly(
                                mirroredSamples,
                                mirroredSizes,
                                mirroredSamples.size,
                                isEraser = isEraserStroke,
                            )
                        }
                        resetMirrorBranches()
                        safeEndSymmetryUndoMacro()
                    }
                }
                strokeStarted = false
            } else {
                resetMirrorBranches()
                safeEndSymmetryUndoMacro()
            }
            strokeAbortedUntilUp = false
            assistLockedRay = null
            resetFisheyeAssist()
            clearPredictionState(triggerInvalidate = true)
            currentStrokeDocPos = Offset.Zero
            firstDocPos = Offset.Zero
            shapeEndDocPos = Offset.Zero
                }
        Tool.LIQUIFY -> {
            if (strokeStarted) {
                // Phase 3B: 抬笔要把没追完的剩余段一次性补齐(此时按常规补点规则覆盖整段,
                // 不丢形变), 再提交事务
                if (liquifySession.coalescing) {
                    removeCallbacks(liquifyFlushRunnable)
                    liquifyFlushPosted = false
                    liquifyFlushNow(v, forceFull = true)
                }
                if (com.reverie.paint.BuildConfig.DEBUG) android.util.Log.i(
                    "ReverieLiquify",
                    "end doc=${v.docWidth}x${v.docHeight} brush=$liquifyBrushSize " +
                        "field=$liquifyFieldGesture gles=${LiquifyGlesPreview.requested} " +
                        "inputs=${liquifySession.inputCount} dabs=${liquifySession.dabCount} " +
                        "frames=${LiquifyGlesPreview.renderedFrames} " +
                        "uploads=${LiquifyGlesPreview.sourceUploadCount} dropped=${LiquifyGlesPreview.dabsDropped}",
                )
                liquifySession.reset()
                // 多指误触保护: 液化手势收到 CANCEL(典型来源 = 第二指落下被系统判成
                // 手势接管)时**一律走提交** —— 提交后用户还能撤销, 而取消会直接丢掉
                // 整段形变(真机表现为"画到一半手指碰一下, 形变没了")。
                if (isCancel && !isLiquifyGestureActive) {
                    liquifyFieldGesture = false
                    liquifyBasePolicy.reset()
                    liquifyDrawRect = null
                    v.liquifyCancel()
                } else if (liquifyFieldGesture) {
                    // Phase 5 · C3-2: 拖动期一个 dab 都没进引擎 —— 现在把 GPU 算好的结果
                    // 一次性写回图层(失败则自动重放补点)
                    commitLiquifyField(v)
                } else {
                    v.liquifyEnd()
                }
                strokeStarted = false
            }
        }
        Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
            handleShapeUp(docPos)
        }
        Tool.SELECT_POLYGON -> {
            isPolyPointPendingOnTouch = false
        }
        Tool.TEXT -> {
            handleTextUp(docPos)
        }
        Tool.GRADIENT -> {
            handleGradientUp(docPos)
        }
        Tool.SELECT_RECT -> {
            liveShapeStart?.value = null
            liveShapeEnd?.value = null
            v.selectShape(0, firstDocPos.x.toInt(), firstDocPos.y.toInt(), shapeEndDocPos.x.toInt(), shapeEndDocPos.y.toInt())
        }
        Tool.SELECT_ELLIPSE -> {
            liveShapeStart?.value = null
            liveShapeEnd?.value = null
            v.selectShape(1, firstDocPos.x.toInt(), firstDocPos.y.toInt(), shapeEndDocPos.x.toInt(), shapeEndDocPos.y.toInt())
        }
        Tool.ENCLOSE_FILL -> {
            liveSelectionPath?.value = null
            if (isCancel || maxTouchPointers > 1) {
                lassoPoints.clear()
                return
            }
            if (lassoPoints.size >= 3) {
                val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                v.encloseAndFill(points)
            }
            lassoPoints.clear()
        }
        Tool.LASSO -> {
            val subMode = v.lassoSubMode
            liveSelectionPath?.value = null
            if (justFinishedLassoInDown) {
                justFinishedLassoInDown = false
                lassoPoints.clear()
                return
            }
            if (isCancel) {
                lassoPoints.clear()
                return
            }
            if (subMode == LassoSubMode.FREEHAND) {
                if (lassoPoints.size >= 3) {
                    val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                    v.lassoSelect(points)
                }
                lassoPoints.clear()
            } else {
                val tiles = v.displayTiles.drawable()
                val bmp = if (tiles != null) null else (v.displayBitmap ?: docBitmap)?.takeIf { !it.isRecycled }
                val bmpW = (tiles?.grid?.width ?: bmp?.width ?: v.docWidth.takeIf { it > 0 } ?: 1).toFloat()
                val docW = (if (v.docWidth > 0) v.docWidth else bmpW.toInt()).toFloat()
                val bmpPerDoc = (bmpW / docW).coerceAtLeast(0.001f)
                val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
                val currentDocScale = maxOf(0.001f, currentScale * bmpPerDoc)
                val dragDist = hypot(docPos.x - firstDocPos.x, docPos.y - firstDocPos.y)
                val isTap = dragDist < (8f * density) / currentDocScale && lassoPoints.size <= 3
                val snapDistThreshold = (24f * density) / currentDocScale

                // 1. 若已有 >= 3 个点，且本次抬手位置落在起点吸附阈值内，直接闭合提交（不重复追加起点）
                if (v.lassoMultiPoints.size >= 3) {
                    val startPt = v.lassoMultiPoints.first()
                    if (hypot(docPos.x - startPt.first, docPos.y - startPt.second) <= snapDistThreshold) {
                        v.finishLassoMulti()
                        lassoPoints.clear()
                        return
                    }
                }

                // 2. 将本次点击或拖拽段加入点列表
                if (subMode == LassoSubMode.POLYLINE || isTap) {
                    v.lassoMultiPoints = v.lassoMultiPoints + (docPos.x.toInt() to docPos.y.toInt())
                    v.lassoSegmentCounts.add(1)
                } else {
                    val pts = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                    v.lassoMultiPoints = v.lassoMultiPoints + pts
                    v.lassoSegmentCounts.add(pts.size)
                }

                // 3. 混合模式下：若刚画完的自由笔画本身形成闭环（终点靠近初始起点），直接闭合提交
                if (v.lassoMultiPoints.size >= 3) {
                    val startPt = v.lassoMultiPoints.first()
                    if (hypot(docPos.x - startPt.first, docPos.y - startPt.second) <= snapDistThreshold) {
                        v.finishLassoMulti()
                    }
                }
                lassoPoints.clear()
            }
        }
        Tool.MEASURE -> {
            activeMeasureHandle = -1
        }
        Tool.TRANSFORM -> {
            if (isCancel) {
                tfState?.cancelDrag()
            } else {
                tfState?.pushGestureUndo()
                tfState?.handle = -1
            }
        }
        Tool.PANEL -> {
            val st = panelInteraction
            removeCallbacks(panelLongPressRunnable)
            panelLongPressToken++
            panelLongPressPending = false
            panelMarqueeArmed = false
            if (st == null || panelDragMode == PANEL_DRAG_NONE ||
                panelDragMode == PANEL_DRAG_CONSUMED
            ) {
                panelDragMode = PANEL_DRAG_NONE
            } else if (isCancel || maxTouchPointers > 1) {
                // 取消/多指接管: 只清预览, 不写引擎
                panelDragMode = PANEL_DRAG_NONE
                st.clearDrag()
            } else {
                val currentScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                val dragDist = hypot(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                val isTap = dragDist < (PANEL_TAP_SLOP_DP * density) / currentScale
                when (panelDragMode) {
                    PANEL_DRAG_HANDLE -> {
                        val pq = st.dragPreviews.firstOrNull()?.quad
                        if (pq != null && panelDragIds.isNotEmpty()) {
                            v.updatePanelShape(panelDragIds[0], pq)
                        }
                    }
                    PANEL_DRAG_EDGE_MIDPOINT -> {
                        val pq = st.dragPreviews.firstOrNull()?.quad
                        if (pq != null && panelDragIds.isNotEmpty()) {
                            v.updatePanelShape(panelDragIds[0], pq)
                        }
                    }
                    PANEL_DRAG_EDGE -> {
                        // 提交最后一帧预览。UP 再算一次吸附会用抬笔点: 笔尖常在松手时
                        // 跳出 8dp 磁吸, 虚线已经对上, 落点却弹回手指位置。
                        val previewA = st.dragPreviews.firstOrNull { it.panelId == panelDragIds.getOrNull(0) }?.quad
                        val previewB = st.dragPreviews.firstOrNull { it.panelId == panelDragIds.getOrNull(1) }?.quad
                        val finalPair = if (previewA != null && previewB != null) {
                            previewA to previewB
                        } else {
                            val rawDelta = Point2D(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                            computeEdgeSnap(rawDelta, currentScale).first
                        }
                        if (finalPair != null && panelDragIds.size >= 2) {
                            // 两格各自入宏 → 两步撤销 (可接受)
                            v.updatePanelShape(panelDragIds[0], finalPair.first)
                            v.updatePanelShape(panelDragIds[1], finalPair.second)
                        }
                    }
                    PANEL_DRAG_BODY -> {
                        val shown = panelShownMoveDelta()
                        if (shown != null || !isTap) {
                            val snappedDelta = shown ?: run {
                                val rawDelta = Point2D(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                                computeBodySnap(rawDelta, currentScale).first
                            }
                            // 单格/多选统一走批量路径 (单 runCore + 单撤销宏一条撤销步);
                            // 返回 false (镜像缺失/delta 非有限) 时不进引擎、选中保持原状,
                            // 与旧 movePanelBy 的静默失败一致 (movePanelsBy 内部已记日志)
                            v.movePanelsBy(panelDragIds.toList(), snappedDelta.x, snappedDelta.y)
                        } else {
                            val pid = v.selectedPanelId
                            if (pid != null) {
                                val targetLayer = v.panelContentLayerIndex(pid)
                                if (targetLayer >= 0) {
                                    v.setCurrentLayer(targetLayer)
                                }
                            }
                        }
                        // 多选态轻点: 保持多选不变
                    }
                    PANEL_DRAG_BODY_PENDING -> {
                        val shown = panelShownMoveDelta()
                        if (shown != null || !isTap) {
                            // 兜底 (正常已在 MOVE 转为 BODY 拖移): 同样提交最后一帧预览
                            val snappedDelta = shown ?: run {
                                val rawDelta = Point2D(docPos.x - panelDragStartDoc.x, docPos.y - panelDragStartDoc.y)
                                computeBodySnap(rawDelta, currentScale).first
                            }
                            v.movePanelsBy(panelDragIds.toList(), snappedDelta.x, snappedDelta.y)
                        } else {
                            v.panelMultiSelectedIds = emptySet()
                            v.selectedPanelId = panelPendingPanelId
                            if (panelPendingPanelId >= 0) {
                                val targetLayer = v.panelContentLayerIndex(panelPendingPanelId)
                                if (targetLayer >= 0) {
                                    v.setCurrentLayer(targetLayer)
                                }
                            }
                        }
                    }
                    PANEL_DRAG_CREATE -> {
                        if (isTap) {
                            // 轻点空白: 取消选中
                            v.selectedPanelId = null
                            v.panelMultiSelectedIds = emptySet()
                        } else {
                            val rect = st.createRect
                            // 建格需拖划 ≥24dp 屏幕距离 (规格: 小拖划不建格, 防误触);
                            // 12dp tap slop 仍用于"点击 vs 拖动"的既有判定
                            if (rect != null &&
                                dragDist >= (PANEL_CREATE_MIN_DP * density) / currentScale
                            ) {
                                val d = v.panelDefaults
                                // 矩形按默认间隔内缩后建格 (面积过小由 createPanelAt 拒绝)
                                v.createPanelAt(insetForDefaultGaps(rect, d.hGap, d.vGap))
                            }
                        }
                    }
                    PANEL_DRAG_DIVIDE -> {
                        val startDoc = Point2D(panelDragStartDoc.x, panelDragStartDoc.y)
                        val line = resolveDivideLine(startDoc, Point2D(docPos.x, docPos.y), currentScale)
                        // tap/最短切分距离判定用**原始落点** (DOWN 已把起点吸附到边框,
                        // 最多 PANEL_SNAP_TOLERANCE_DP; 用吸附后起点会把拖划距离拉近,
                        // 真实拖线可能被误判成 tap 而丢切分)
                        val rawDist = hypot(docPos.x - panelDownRawDoc.x, docPos.y - panelDownRawDoc.y)
                        val rawIsTap = rawDist < (PANEL_TAP_SLOP_DP * density) / currentScale
                        if (!rawIsTap &&
                            rawDist >= (PANEL_CREATE_MIN_DP * density) / currentScale
                        ) {
                            // 有效拖划 → 切开所有被划过的格。方向锁与 MOVE 预览共用 resolveDivideLine
                            v.dividePanelsByLine(
                                line.start,
                                line.end,
                                panelDivideExtendDocPx(currentScale),
                            )
                        }
                        // 轻点不改选中 (切分模式选中态原样保留, 退出靠模式开关)
                    }
                    PANEL_DRAG_DIVIDE_PENDING -> {
                        // 兜底 (正常路径已在 MOVE 转 DIVIDE、或长按成功转 CONSUMED):
                        // UP 时仍 PENDING 且已位移 (MOVE 没跑到) → 按 DIVIDE 同样规则提交
                        // (起点/终点都吸附边框, tap/最短切分用原始落点判距);
                        // 轻点格体 = 不选中、不切分 (切分模式轻点本就不改选中)
                        if (!isTap) {
                            val snappedDown = snapPointToPanelBorders(Point2D(panelDownRawDoc.x, panelDownRawDoc.y))
                            val startDoc = Point2D(snappedDown.x, snappedDown.y)
                            val line = resolveDivideLine(startDoc, Point2D(docPos.x, docPos.y), currentScale)
                            val rawDist = hypot(docPos.x - panelDownRawDoc.x, docPos.y - panelDownRawDoc.y)
                            if (rawDist >= (PANEL_CREATE_MIN_DP * density) / currentScale) {
                                v.dividePanelsByLine(line.start, line.end, panelDivideExtendDocPx(currentScale))
                            }
                        }
                    }
                    PANEL_DRAG_MARQUEE -> {
                        val rect = st.marqueeRect
                        st.marqueeRect = null
                        if (rect != null && rect.width > 1f && rect.height > 1f) {
                            // 矩形内含格心或任一角点即选中
                            v.panelMultiSelectedIds = v.panels
                                .filter { m ->
                                    val q = m.meta.quad
                                    rect.contains(q.centroid()) || q.corners.any { rect.contains(it) }
                                }
                                .map { it.panelId }
                                .toSet()
                            // 多选确定的瞬间即建/同步跨格容器 (不等落笔):
                            // 引擎侧建组/切层/蒙版重绘随 runCore 完成, 之后任何工具落笔
                            // 都能画进跨格内容层 (panelBeginCrossStroke 只做镜像同步)
                            if (v.panelMultiSelectedIds.isNotEmpty()) {
                                v.ensureCrossContainer(v.panelMultiSelectedIds)
                            }
                        }
                    }
                }
                // 长按菜单在展示中时保留 (menuScreenPos 不清), 其余拖动预览全部清掉
                st.clearDrag()
                panelDragMode = PANEL_DRAG_NONE
            }
        }
        Tool.PICKER -> {
            pickerActive?.value = false
            val curCol = pickerCurrentColor?.value
            if (curCol != null) {
                val r = (curCol.red * 255).toInt().coerceIn(0, 255)
                val g = (curCol.green * 255).toInt().coerceIn(0, 255)
                val b = (curCol.blue * 255).toInt().coerceIn(0, 255)
                val hex = String.format("#%02X%02X%02X", r, g, b)
                v.updateBrushColor(hex)
                v.showActionToast(context.getString(R.string.canvas_toast_color_picked), R.drawable.ic_picker)
            }
            if (v.isTemporaryPicker) {
                v.restorePreviousTool()
            }
        }
        Tool.FILL -> {
            if (isStylus) {
                // 笔尖已在 handleToolDown 中触发
            } else if (!isCancel && maxTouchPointers <= 1) {
                val dragDist = hypot(docPos.x - firstDocPos.x, docPos.y - firstDocPos.y)
                val currentScale = maxOf(0.001f, canvasZoom * canvasFitScale)
                if (dragDist < (12f * density) / currentScale) {
                    v.floodFill(firstDocPos.x, firstDocPos.y, fillTolerance)
                }
            }
        }
        Tool.MAGICWAND -> {
            if (isStylus) {
                // 笔尖已在 handleToolDown 中触发
            } else if (!isCancel && maxTouchPointers <= 1) {
                val dragDist = hypot(docPos.x - firstDocPos.x, docPos.y - firstDocPos.y)
                val currentScale = maxOf(0.001f, canvasZoom * canvasFitScale)
                if (dragDist < (12f * density) / currentScale) {
                    wandFlash?.value = firstDocPos
                    v.selectContiguous(firstDocPos.x.toInt(), firstDocPos.y.toInt())
                }
            }
        }
        Tool.SELECT_SIMILAR -> {
            if (isStylus) {
                // 笔尖已在 handleToolDown 中触发
            } else if (!isCancel && maxTouchPointers <= 1) {
                val dragDist = hypot(docPos.x - firstDocPos.x, docPos.y - firstDocPos.y)
                val currentScale = maxOf(0.001f, canvasZoom * canvasFitScale)
                if (dragDist < (12f * density) / currentScale) {
                    wandFlash?.value = firstDocPos
                    v.selectSimilar(firstDocPos.x.toInt(), firstDocPos.y.toInt())
                }
            }
        }
        else -> Unit
    }
    if (isStylus) {
        tempEraseActive = false
    }
    firstDocPos = Offset.Zero
    shapeEndDocPos = Offset.Zero
}
