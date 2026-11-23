/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.core.*
import com.reverie.paint.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.isShapeTool(t: Tool): Boolean =
    t == Tool.SHAPES || t == Tool.LINE || t == Tool.RECT ||
    t == Tool.ELLIPSE || t == Tool.POLYGON || t == Tool.POLYLINE ||
    t == Tool.PATH || t.group == ToolGroup.SHAPES

internal fun CanvasTouchView.defaultShapeType(t: Tool, fallback: ShapeType): ShapeType =
    when (t) {
        Tool.LINE -> ShapeType.LINE
        Tool.RECT -> ShapeType.RECT
        Tool.ELLIPSE -> ShapeType.ELLIPSE
        Tool.POLYGON -> ShapeType.POLYGON
        Tool.POLYLINE -> ShapeType.POLYLINE
        Tool.PATH -> ShapeType.BEZIER
        else -> fallback
    }

internal fun CanvasTouchView.hitTestShapeHandle(
    state: ShapeState,
    docPos: Offset,
    density: Float,
    currentScale: Float,
): Int {
    val hitDist = (28f * density) / maxOf(0.01f, currentScale)
    when (state.type) {
        ShapeType.LINE -> {
            if (hypot(docPos.x - state.p1.x, docPos.y - state.p1.y) < hitDist) return ShapeHandleId.LINE_P1
            if (hypot(docPos.x - state.p2.x, docPos.y - state.p2.y) < hitDist) return ShapeHandleId.LINE_P2
            val mid = (state.p1 + state.p2) / 2f
            if (hypot(docPos.x - mid.x, docPos.y - mid.y) < hitDist) return ShapeHandleId.TRANSLATE_BODY
            val d = ShapeGeometry.distanceToSegment(Point2D(docPos.x, docPos.y), Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
            if (d < hitDist) return ShapeHandleId.TRANSLATE_BODY
        }
        ShapeType.RECT, ShapeType.ROUNDED_RECT, ShapeType.ELLIPSE -> {
            val p2 = if (state.keepAspect) {
                val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                Offset(pt.x, pt.y)
            } else state.p2
            val (tl, br) = ShapeGeometry.normalizeRect(Point2D(state.p1.x, state.p1.y), Point2D(p2.x, p2.y))
            val tr = Offset(br.x, tl.y)
            val bl = Offset(tl.x, br.y)
            val center = Offset((tl.x + br.x) / 2f, (tl.y + br.y) / 2f)

            val localPos = if (abs(state.rotationDegrees) > 0.01f) {
                val rotated = ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(center.x, center.y), -state.rotationDegrees)
                Offset(rotated.x, rotated.y)
            } else docPos

            val rotPos = Offset(center.x, tl.y - (28f * density) / maxOf(0.01f, currentScale))
            if (hypot(localPos.x - rotPos.x, localPos.y - rotPos.y) < hitDist) return ShapeHandleId.ROTATE

            if (state.type == ShapeType.ROUNDED_RECT) {
                val cr = state.cornerRadius.coerceIn(0f, minOf(br.x - tl.x, br.y - tl.y) / 2f)
                val crPos = Offset(tl.x + cr, tl.y + cr)
                if (hypot(localPos.x - crPos.x, localPos.y - crPos.y) < hitDist) return ShapeHandleId.CORNER_RADIUS
            }

            if (hypot(localPos.x - tl.x, localPos.y - tl.y) < hitDist) return ShapeHandleId.CORNER_TL
            if (hypot(localPos.x - tr.x, localPos.y - tr.y) < hitDist) return ShapeHandleId.CORNER_TR
            if (hypot(localPos.x - br.x, localPos.y - br.y) < hitDist) return ShapeHandleId.CORNER_BR
            if (hypot(localPos.x - bl.x, localPos.y - bl.y) < hitDist) return ShapeHandleId.CORNER_BL

            if (localPos.x in tl.x..br.x && localPos.y in tl.y..br.y) return ShapeHandleId.TRANSLATE_BODY
        }
        ShapeType.REGULAR_POLYGON -> {
            val center = (state.p1 + state.p2) / 2f
            val radius = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
            val baseAngle = (-PI / 2.0).toFloat() + Math.toRadians(state.rotationDegrees.toDouble()).toFloat()
            val topH = Offset(center.x + radius * cos(baseAngle), center.y + radius * sin(baseAngle))
            if (hypot(docPos.x - topH.x, docPos.y - topH.y) < hitDist) return ShapeHandleId.STAR_OUTER
            if (hypot(docPos.x - center.x, docPos.y - center.y) < hitDist) return ShapeHandleId.TRANSLATE_BODY
            if (hypot(docPos.x - center.x, docPos.y - center.y) < radius) return ShapeHandleId.TRANSLATE_BODY
        }
        ShapeType.STAR -> {
            val center = (state.p1 + state.p2) / 2f
            val outerR = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
            val innerR = outerR * state.starInnerRatio
            val baseAngle = (-PI / 2.0).toFloat() + Math.toRadians(state.rotationDegrees.toDouble()).toFloat()
            val outerH = Offset(center.x + outerR * cos(baseAngle), center.y + outerR * sin(baseAngle))
            if (hypot(docPos.x - outerH.x, docPos.y - outerH.y) < hitDist) return ShapeHandleId.STAR_OUTER
            val angleStep = (PI / state.starPoints).toFloat()
            val innerAngle = baseAngle + angleStep
            val innerH = Offset(
                center.x + innerR * cos(innerAngle),
                center.y + innerR * sin(innerAngle)
            )
            if (hypot(docPos.x - innerH.x, docPos.y - innerH.y) < hitDist) return ShapeHandleId.STAR_INNER
            if (hypot(docPos.x - center.x, docPos.y - center.y) < hitDist) return ShapeHandleId.TRANSLATE_BODY
            if (hypot(docPos.x - center.x, docPos.y - center.y) < outerR) return ShapeHandleId.TRANSLATE_BODY
        }
        ShapeType.POLYLINE, ShapeType.POLYGON -> {
            for (i in state.nodes.indices) {
                val n = state.nodes[i]
                if (hypot(docPos.x - n.pos.x, docPos.y - n.pos.y) < hitDist) {
                    state.selectedNodeIndex = i
                    return ShapeHandleId.NODE_ANCHOR_BASE + i
                }
            }
        }
        ShapeType.BEZIER -> {
            val selIdx = state.selectedNodeIndex
            if (selIdx in 0 until state.nodes.size) {
                val n = state.nodes[selIdx]
                if (hypot(docPos.x - n.cpIn.x, docPos.y - n.cpIn.y) < hitDist) {
                    return ShapeHandleId.NODE_CP_IN_BASE + selIdx
                }
                if (hypot(docPos.x - n.cpOut.x, docPos.y - n.cpOut.y) < hitDist) {
                    return ShapeHandleId.NODE_CP_OUT_BASE + selIdx
                }
            }
            for (i in state.nodes.indices) {
                val n = state.nodes[i]
                if (hypot(docPos.x - n.pos.x, docPos.y - n.pos.y) < hitDist) {
                    state.selectedNodeIndex = i
                    return ShapeHandleId.NODE_ANCHOR_BASE + i
                }
            }
        }
    }
    return ShapeHandleId.NONE
}

internal fun CanvasTouchView.handleShapeDown(docPos: Offset) {
    val v = vm ?: return
    val state = v.shapeState
    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
    val hitDist = (28f * density) / currentScale
    val now = SystemClock.uptimeMillis()

    if (state.active) {
        val hit = hitTestShapeHandle(state, docPos, density, currentScale)
        if (hit != ShapeHandleId.NONE) {
            state.activeHandle = hit
            state.dragStartDocPos = docPos
            state.dragP1 = state.p1
            state.dragP2 = state.p2
            state.dragRotation = state.rotationDegrees
            state.dragCornerRadius = state.cornerRadius
            state.dragStarInnerRatio = state.starInnerRatio
            state.isCreatingNewNode = false
            shapeCreatedOnCurrentTouch = false

            val isBoxShape = state.type == ShapeType.RECT ||
                state.type == ShapeType.ROUNDED_RECT ||
                state.type == ShapeType.ELLIPSE
            if (isBoxShape) {
                val p2Init = if (state.keepAspect) {
                    val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                    Point2D(pt.x, pt.y)
                } else Point2D(state.p2.x, state.p2.y)
                val corners = ShapeGeometry.getBoxCornersDoc(Point2D(state.p1.x, state.p1.y), p2Init, state.rotationDegrees)
                state.dragAnchorDoc = when (hit) {
                    ShapeHandleId.CORNER_TL -> Offset(corners[2].x, corners[2].y)
                    ShapeHandleId.CORNER_TR -> Offset(corners[3].x, corners[3].y)
                    ShapeHandleId.CORNER_BR -> Offset(corners[0].x, corners[0].y)
                    ShapeHandleId.CORNER_BL -> Offset(corners[1].x, corners[1].y)
                    else -> Offset.Zero
                }
            }
        } else {
            if (state.type == ShapeType.POLYLINE || state.type == ShapeType.POLYGON || state.type == ShapeType.BEZIER) {
                if (state.nodes.size >= 3) {
                    val firstPos = state.nodes.first().pos
                    val isNearFirst = hypot(docPos.x - firstPos.x, docPos.y - firstPos.y) < hitDist
                    val isDoubleTap = now - lastShapeTapTimeMs < 350L &&
                        hypot(docPos.x - lastShapeTapDocPos.x, docPos.y - lastShapeTapDocPos.y) < hitDist
                    if (isNearFirst || isDoubleTap) {
                        lastShapeTapTimeMs = 0L
                        state.closed = true
                        v.commitActiveShape()
                        return
                    }
                }
                lastShapeTapTimeMs = now
                lastShapeTapDocPos = docPos

                state.nodes.add(ShapeNode(Point2D(docPos.x, docPos.y)))
                val newIdx = state.nodes.size - 1
                state.selectedNodeIndex = newIdx
                state.activeHandle = ShapeHandleId.NODE_ANCHOR_BASE + newIdx
                state.dragStartDocPos = docPos
                state.isCreatingNewNode = true
                shapeCreatedOnCurrentTouch = false
            } else {
                // 连续绘制保障：若当前有尺寸有效的前序形状，自动提交上屏
                val shapeDist = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y)
                if (shapeDist > 4f) {
                    v.commitActiveShape()
                }

                val targetType = defaultShapeType(tool, state.type)
                val targetDoc = if (targetType == ShapeType.LINE) {
                    val sp = snapPointToPanelBorders(Point2D(docPos.x, docPos.y))
                    Offset(sp.x, sp.y)
                } else docPos
                state.reset(targetType, targetDoc)
                if (state.strokeWidth <= 0f) {
                    state.strokeWidth = v.brushSize.toFloat().coerceIn(1f, 100f)
                }
                state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
                state.dragStartDocPos = targetDoc
                state.dragAnchorDoc = targetDoc
                state.dragP1 = targetDoc
                state.dragP2 = targetDoc
                state.isCreatingNewNode = false
                shapeCreatedOnCurrentTouch = true
            }
        }
    } else {
        val targetType = defaultShapeType(tool, state.type)
        val targetDoc = if (targetType == ShapeType.LINE) {
            val sp = snapPointToPanelBorders(Point2D(docPos.x, docPos.y))
            Offset(sp.x, sp.y)
        } else docPos
        state.reset(targetType, targetDoc)
        if (state.strokeWidth <= 0f) {
            state.strokeWidth = v.brushSize.toFloat().coerceIn(1f, 100f)
        }
        if (targetType == ShapeType.POLYLINE || targetType == ShapeType.POLYGON || targetType == ShapeType.BEZIER) {
            state.activeHandle = ShapeHandleId.NODE_ANCHOR_BASE + 0
            state.selectedNodeIndex = 0
            state.dragStartDocPos = targetDoc
            state.isCreatingNewNode = true
            shapeCreatedOnCurrentTouch = false
            lastShapeTapTimeMs = now
            lastShapeTapDocPos = targetDoc
        } else {
            state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
            state.dragStartDocPos = targetDoc
            state.dragAnchorDoc = targetDoc
            state.dragP1 = targetDoc
            state.dragP2 = targetDoc
            state.isCreatingNewNode = false
            shapeCreatedOnCurrentTouch = true
        }
    }
}

internal fun CanvasTouchView.handleShapeMove(docPos: Offset) {
    val v = vm ?: return
    val state = v.shapeState
    if (state.active && state.activeHandle != ShapeHandleId.NONE) {
        val handle = state.activeHandle
        when {
            handle == ShapeHandleId.TRANSLATE_BODY -> {
                val delta = docPos - state.dragStartDocPos
                state.p1 = state.dragP1 + delta
                state.p2 = state.dragP2 + delta
            }
            handle == ShapeHandleId.LINE_P1 -> {
                state.p1 = docPos
            }
            handle == ShapeHandleId.LINE_P2 -> {
                state.p2 = docPos
            }
            handle == ShapeHandleId.ROTATE -> {
                val center = (state.p1 + state.p2) / 2f
                val angleRad = atan2(docPos.y - center.y, docPos.x - center.x)
                state.rotationDegrees = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
            }
            handle == ShapeHandleId.CORNER_BR || handle == ShapeHandleId.CORNER_TL ||
            handle == ShapeHandleId.CORNER_TR || handle == ShapeHandleId.CORNER_BL -> {
                val (newP1, newP2) = ShapeGeometry.resizeBox(
                    anchorDoc = Point2D(state.dragAnchorDoc.x, state.dragAnchorDoc.y),
                    touchDoc = Point2D(docPos.x, docPos.y),
                    rotationDeg = state.rotationDegrees,
                    keepAspect = state.keepAspect,
                )
                state.p1 = Offset(newP1.x, newP1.y)
                state.p2 = Offset(newP2.x, newP2.y)
            }
            handle == ShapeHandleId.CORNER_RADIUS -> {
                val center = (state.dragP1 + state.dragP2) / 2f
                val localPt = if (abs(state.rotationDegrees) > 0.01f) {
                    ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(center.x, center.y), -state.rotationDegrees)
                } else Point2D(docPos.x, docPos.y)
                val localDocPos = Offset(localPt.x, localPt.y)
                val minDim = minOf(abs(state.p2.x - state.p1.x), abs(state.p2.y - state.p1.y))
                val dist = hypot(localDocPos.x - state.p1.x, localDocPos.y - state.p1.y)
                state.cornerRadius = dist.coerceIn(0f, minDim / 2f)
            }
            handle == ShapeHandleId.STAR_OUTER -> {
                val center = (state.p1 + state.p2) / 2f
                val r = hypot(docPos.x - center.x, docPos.y - center.y)
                state.p1 = center - Offset(r, r)
                state.p2 = center + Offset(r, r)
                val angleRad = atan2(docPos.y - center.y, docPos.x - center.x)
                state.rotationDegrees = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
            }
            handle == ShapeHandleId.STAR_INNER -> {
                val center = (state.p1 + state.p2) / 2f
                val outerR = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
                val curR = hypot(docPos.x - center.x, docPos.y - center.y)
                if (outerR > 1f) state.starInnerRatio = (curR / outerR).coerceIn(0.1f, 0.9f)
            }
            handle >= ShapeHandleId.NODE_CP_OUT_BASE -> {
                val idx = handle - ShapeHandleId.NODE_CP_OUT_BASE
                if (idx in 0 until state.nodes.size) {
                    val old = state.nodes[idx]
                    state.nodes[idx] = ShapeNode(old.pos, old.cpIn, Point2D(docPos.x, docPos.y))
                }
            }
            handle >= ShapeHandleId.NODE_CP_IN_BASE -> {
                val idx = handle - ShapeHandleId.NODE_CP_IN_BASE
                if (idx in 0 until state.nodes.size) {
                    val old = state.nodes[idx]
                    state.nodes[idx] = ShapeNode(old.pos, Point2D(docPos.x, docPos.y), old.cpOut)
                }
            }
            handle >= ShapeHandleId.NODE_ANCHOR_BASE -> {
                val idx = handle - ShapeHandleId.NODE_ANCHOR_BASE
                if (idx in 0 until state.nodes.size) {
                    if (state.isCreatingNewNode && state.type == ShapeType.BEZIER) {
                        val center = state.dragStartDocPos
                        val deltaX = docPos.x - center.x
                        val deltaY = docPos.y - center.y
                        state.nodes[idx] = ShapeNode(
                            Point2D(center.x, center.y),
                            Point2D(center.x - deltaX, center.y - deltaY),
                            Point2D(center.x + deltaX, center.y + deltaY),
                        )
                    } else {
                        val old = state.nodes[idx]
                        val delta = docPos - Offset(old.pos.x, old.pos.y)
                        state.nodes[idx] = ShapeNode(
                            Point2D(docPos.x, docPos.y),
                            Point2D(old.cpIn.x + delta.x, old.cpIn.y + delta.y),
                            Point2D(old.cpOut.x + delta.x, old.cpOut.y + delta.y)
                        )
                    }
                }
            }
        }
    }
}

internal fun CanvasTouchView.handleShapeUp(docPos: Offset) {
    val v = vm ?: return
    if (v.shapeState.active && v.shapeState.type == ShapeType.LINE) {
        val sp = snapPointToPanelBorders(Point2D(docPos.x, docPos.y))
        v.shapeState.p2 = Offset(sp.x, sp.y)
    }
    v.shapeState.activeHandle = ShapeHandleId.NONE
    v.shapeState.dragStartDocPos = Offset.Zero
    v.shapeState.isCreatingNewNode = false
    shapeCreatedOnCurrentTouch = false
}

internal fun CanvasTouchView.cancelPendingShapeGesture() {
    val v = vm ?: return
    val state = v.shapeState
    lastShapeTapTimeMs = 0L

    if (state.active) {
        if (state.isCreatingNewNode) {
            if (state.nodes.size <= 1) {
                state.clear()
            } else {
                state.nodes.removeAt(state.nodes.size - 1)
                state.selectedNodeIndex = state.nodes.size - 1
                state.activeHandle = ShapeHandleId.NONE
                state.dragStartDocPos = Offset.Zero
                state.isCreatingNewNode = false
            }
        } else if (shapeCreatedOnCurrentTouch) {
            state.clear()
        } else if (state.activeHandle != ShapeHandleId.NONE) {
            state.p1 = state.dragP1
            state.p2 = state.dragP2
            state.rotationDegrees = state.dragRotation
            state.cornerRadius = state.dragCornerRadius
            state.starInnerRatio = state.dragStarInnerRatio
            state.activeHandle = ShapeHandleId.NONE
            state.dragStartDocPos = Offset.Zero
        }
    }

    shapeCreatedOnCurrentTouch = false
    cancelPendingGradientGesture()

    if (isPolyPointPendingOnTouch) {
        onPolyPopPoint?.invoke()
        isPolyPointPendingOnTouch = false
    }

    liveShapeStart?.value = null
    liveShapeEnd?.value = null
    if (lassoPoints.isNotEmpty()) {
        lassoPoints.clear()
        liveSelectionPath?.value = null
    }

    invalidate()
}

internal fun CanvasTouchView.hitTestTextHandle(
    cfg: TypographyConfig,
    docPos: Offset,
    density: Float,
    currentScale: Float,
): Int {
    val hitDist = (28f * density) / maxOf(0.01f, currentScale)
    val v = vm ?: return -1
    val paint = TypographyEngine.createTextPaint(cfg, v.brushOpacity)
    val targetW = TypographyEngine.resolveTargetWidth(cfg)
    val layout = TypographyEngine.createLayout(cfg, paint, targetW)
    val w = layout.width.toFloat()
    val h = layout.height.toFloat()

    val left = cfg.posX
    val top = cfg.posY
    val right = left + w
    val bottom = top + h
    val cx = left + w / 2f
    val cy = top + h / 2f

    val localPt = if (abs(cfg.rotationDeg) > 0.01f) {
        ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(cx, cy), -cfg.rotationDeg)
    } else Point2D(docPos.x, docPos.y)
    val localPos = Offset(localPt.x, localPt.y)

    // 1. 顶部旋转手柄
    val rotPos = Offset(cx, top - (24f * density) / maxOf(0.01f, currentScale))
    if (hypot(localPos.x - rotPos.x, localPos.y - rotPos.y) < hitDist) {
        return -20
    }

    // 2. 右下角尺寸缩放手柄
    if (hypot(localPos.x - right, localPos.y - bottom) < hitDist) {
        return -30
    }

    // 3. 文本框内部拖拽平移
    if (localPos.x in (left - hitDist)..(right + hitDist) && localPos.y in (top - hitDist)..(bottom + hitDist)) {
        return -10
    }

    return -1
}

internal fun CanvasTouchView.hitTestText(
    cfg: TypographyConfig,
    docPos: Offset,
    density: Float,
    currentScale: Float,
    brushOpacity: Double = 1.0,
): Boolean {
    val hitDist = (28f * density) / maxOf(0.01f, currentScale)
    val paint = TypographyEngine.createTextPaint(cfg, brushOpacity)
    val targetW = TypographyEngine.resolveTargetWidth(cfg)
    val layout = TypographyEngine.createLayout(cfg, paint, targetW)
    val w = layout.width.toFloat()
    val h = layout.height.toFloat()

    val left = cfg.posX
    val top = cfg.posY
    val right = left + w
    val bottom = top + h
    val cx = left + w / 2f
    val cy = top + h / 2f

    val localPt = if (abs(cfg.rotationDeg) > 0.01f) {
        ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(cx, cy), -cfg.rotationDeg)
    } else Point2D(docPos.x, docPos.y)
    val localPos = Offset(localPt.x, localPt.y)

    return localPos.x in (left - hitDist)..(right + hitDist) && localPos.y in (top - hitDist)..(bottom + hitDist)
}

internal fun CanvasTouchView.findHitText(
    docPos: Offset,
    density: Float,
    currentScale: Float,
): Pair<PaintViewModel.LayerUiState, TypographyConfig>? {
    val v = vm ?: return null
    val curLayer = v.layers.getOrNull(v.currentLayerIndex)

    // 1. 优先检测当前选中的图层 (若是文字图层)
    if (curLayer != null && curLayer.visible && !curLayer.locked) {
        val items = v.activeTextLayers[curLayer.id]
        if (items != null) {
            for (cfg in items.asReversed()) {
                if (hitTestText(cfg, docPos, density, currentScale, v.brushOpacity)) {
                    return curLayer to cfg
                }
            }
        }
    }

    // 2. 从上至下扫描其他可见且未锁定的文字图层
    for (layer in v.layers.asReversed()) {
        if (layer.id == curLayer?.id) continue
        if (!layer.visible || layer.locked) continue
        val items = v.activeTextLayers[layer.id] ?: continue
        for (cfg in items.asReversed()) {
            if (hitTestText(cfg, docPos, density, currentScale, v.brushOpacity)) {
                return layer to cfg
            }
        }
    }
    return null
}

internal fun CanvasTouchView.handleTextDown(docPos: Offset) {
    val v = vm ?: return
    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)

    textTouchDownDocPos = docPos
    textSelectedBeforeDown = v.isTypographyEditing
    textDraggedDuringGesture = false

    if (v.isTypographyEditing) {
        val hit = hitTestTextHandle(v.typographyConfig, docPos, density, currentScale)
        if (hit != -1) {
            activeTextHandle = hit
            textDragStartDocPos = docPos
            textDragStartCfg = v.typographyConfig
            v.typographySnapGuides = emptyList()
            return
        }

        // 点击外部：若当前文本有内容，自动提交；若为空文本则取消编辑
        if (v.typographyConfig.text.isNotBlank()) {
            v.commitTypographyToCanvas()
        } else {
            v.cancelTypographyEditing()
        }
    }

    // 检查是否点中了画布上现存的某个文字块
    val hitEntry = findHitText(docPos, density, currentScale)
    if (hitEntry != null) {
        val (hitLayer, hitCfg) = hitEntry
        v.startEditingTextLayer(hitLayer.id, hitCfg.id)
        activeTextHandle = -10
        textDragStartDocPos = docPos
        textDragStartCfg = v.typographyConfig
        v.typographySnapGuides = emptyList()
        lastTextTapTimeMs = 0L
        return
    }

    // 若之前正在编辑文字，点击画布空白处已在上面提交，此时仅取消选中，不新建文字
    if (textSelectedBeforeDown) {
        activeTextHandle = -1
        lastTextTapTimeMs = 0L
        return
    }

    // 之前未选中任何文字且点击空白处：
    // 若当前选中的图层已经是文字图层，则直接在当前图层追加新文字块；否则准备新建独立文字图层
    val curLayer = v.layers.getOrNull(v.currentLayerIndex)
    val targetLayerId = if (curLayer != null && v.activeTextLayers.containsKey(curLayer.id)) {
        curLayer.id
    } else null

    v.startNewTextItem(targetLayerId, docPos)
    textDragStartDocPos = docPos
    textDragStartCfg = v.typographyConfig
    v.typographySnapGuides = emptyList()
    activeTextHandle = -1
    lastTextTapTimeMs = 0L
    onTextRequested?.invoke(docPos.x, docPos.y)
}

internal fun CanvasTouchView.handleTextMove(docPos: Offset) {
    val v = vm ?: return
    if (!v.isTypographyEditing || activeTextHandle == -1) return

    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
    val tapSlop = (8f * density) / currentScale
    if (!textDraggedDuringGesture) {
        val moveDist = hypot(docPos.x - textTouchDownDocPos.x, docPos.y - textTouchDownDocPos.y)
        if (moveDist <= tapSlop) return
        textDraggedDuringGesture = true
    }

    when (activeTextHandle) {
        -10 -> {
            val delta = docPos - textDragStartDocPos
            val rawLeft = textDragStartCfg.posX + delta.x
            val rawTop = textDragStartCfg.posY + delta.y

            if (textDragStartCfg.snapEnabled) {
                val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
                val targetW = TypographyEngine.resolveTargetWidth(textDragStartCfg)
                val layout = TypographyEngine.createLayout(textDragStartCfg, paint, targetW)
                val textW = layout.width.toFloat()
                val textH = layout.height.toFloat()

                val snapThreshold = (16f * density) / currentScale
                val margin = minOf(v.docWidth, v.docHeight) * 0.05f
                val snapResult = com.reverie.paint.model.TypographySnapHelper.calculateSnap(
                    boxLeft = rawLeft,
                    boxTop = rawTop,
                    boxWidth = textW,
                    boxHeight = textH,
                    canvasWidth = v.docWidth,
                    canvasHeight = v.docHeight,
                    threshold = snapThreshold,
                    safeMargin = margin,
                )
                v.typographyConfig = textDragStartCfg.copy(
                    posX = snapResult.snappedLeft,
                    posY = snapResult.snappedTop,
                )
                v.typographySnapGuides = snapResult.guides
            } else {
                v.typographyConfig = textDragStartCfg.copy(
                    posX = rawLeft,
                    posY = rawTop,
                )
                v.typographySnapGuides = emptyList()
            }
        }
        -20 -> {
            val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
            val targetW = TypographyEngine.resolveTargetWidth(textDragStartCfg)
            val layout = TypographyEngine.createLayout(textDragStartCfg, paint, targetW)
            val cx = textDragStartCfg.posX + layout.width / 2f
            val cy = textDragStartCfg.posY + layout.height / 2f
            val angleRad = atan2(docPos.y - cy, docPos.x - cx)
            val deg = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
            v.typographyConfig = textDragStartCfg.copy(rotationDeg = deg)
        }
        -30 -> {
            val deltaX = docPos.x - textDragStartDocPos.x
            val deltaY = docPos.y - textDragStartDocPos.y
            val localDelta = if (abs(textDragStartCfg.rotationDeg) > 0.01f) {
                ShapeGeometry.rotatePoint(Point2D(deltaX, deltaY), Point2D(0f, 0f), -textDragStartCfg.rotationDeg)
            } else Point2D(deltaX, deltaY)

            if (textDragStartCfg.isVertical) {
                val baseH = if (textDragStartCfg.boxHeight > 0f) {
                    textDragStartCfg.boxHeight
                } else {
                    val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
                    val targetW = TypographyEngine.resolveTargetWidth(textDragStartCfg)
                    TypographyEngine.createLayout(textDragStartCfg, paint, targetW).height.toFloat()
                }
                val minH = maxOf(24f, textDragStartCfg.fontSize * 1.2f)
                val newH = maxOf(minH, baseH + localDelta.y)
                v.typographyConfig = textDragStartCfg.copy(boxHeight = newH)
            } else {
                val minW = maxOf(24f, textDragStartCfg.fontSize * 1.1f)
                val newW = maxOf(minW, textDragStartCfg.boxWidth + localDelta.x)
                v.typographyConfig = textDragStartCfg.copy(boxWidth = newW)
            }
        }
    }
}

internal fun CanvasTouchView.handleTextUp(docPos: Offset) {
    val v = vm ?: return
    val now = SystemClock.uptimeMillis()
    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
    val tapDist = (24f * density) / currentScale

    if (textDraggedDuringGesture) {
        lastTextTapTimeMs = 0L
    } else if (activeTextHandle == -10 && v.isTypographyEditing) {
        // 仅在选中状态下发生干净双击时才弹出输入法
        if (textSelectedBeforeDown &&
            now - lastTextTapTimeMs < 350L &&
            hypot(docPos.x - lastTextTapDocPos.x, docPos.y - lastTextTapDocPos.y) < tapDist
        ) {
            onTextRequested?.invoke(v.typographyConfig.posX, v.typographyConfig.posY)
            lastTextTapTimeMs = 0L
        } else {
            lastTextTapTimeMs = now
            lastTextTapDocPos = docPos
        }
    }

    activeTextHandle = -1
    textDraggedDuringGesture = false
    textDragStartDocPos = Offset.Zero
    v.typographySnapGuides = emptyList()
}

internal fun CanvasTouchView.cancelPendingGradientGesture() {
    val v = vm ?: return
    val state = v.gradientState
    if (state.active) {
        if (gradientCreatedOnCurrentTouch) {
            state.clear()
        } else if (state.activeHandle != GradientHandleId.NONE) {
            state.p1 = state.dragOriginalP1
            state.p2 = state.dragOriginalP2
            state.activeHandle = GradientHandleId.NONE
        }
    }
    gradientCreatedOnCurrentTouch = false
}

internal fun CanvasTouchView.hitTestGradientHandle(
    state: GradientState,
    docPos: Offset,
    density: Float,
    currentScale: Float,
): Int {
    val hitDist = (28f * density) / maxOf(0.01f, currentScale)
    val lineVec = state.p2 - state.p1
    val lineLen = hypot(lineVec.x, lineVec.y)

    // 1. 中间色标手柄 (仅非动态自定义色标且色标数 > 2 时允许拖动中间手柄)
    if (lineLen > 10f && !state.isDynamicFgBg && !state.isDynamicFgTrans && state.stops.size > 2) {
        for (i in 1 until state.stops.size - 1) {
            val s = state.stops[i]
            val stopDocPos = state.p1 + lineVec * s.position
            if (hypot(docPos.x - stopDocPos.x, docPos.y - stopDocPos.y) < hitDist) {
                return GradientHandleId.STOP_BASE + i
            }
        }
    }

    // 2. 起点手柄
    if (hypot(docPos.x - state.p1.x, docPos.y - state.p1.y) < hitDist) {
        return GradientHandleId.START
    }

    // 3. 终点手柄
    if (hypot(docPos.x - state.p2.x, docPos.y - state.p2.y) < hitDist) {
        return GradientHandleId.END
    }

    // 4. 中心手柄及轴线拖拽
    val mid = (state.p1 + state.p2) / 2f
    if (hypot(docPos.x - mid.x, docPos.y - mid.y) < hitDist) {
        return GradientHandleId.MID_BODY
    }
    if (lineLen > 10f) {
        val d = ShapeGeometry.distanceToSegment(
            Point2D(docPos.x, docPos.y),
            Point2D(state.p1.x, state.p1.y),
            Point2D(state.p2.x, state.p2.y)
        )
        if (d < hitDist) {
            return GradientHandleId.MID_BODY
        }
    }

    return GradientHandleId.NONE
}

internal fun CanvasTouchView.handleGradientDown(docPos: Offset) {
    val v = vm ?: return
    val state = v.gradientState
    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)

    if (state.active) {
        val hit = hitTestGradientHandle(state, docPos, density, currentScale)
        if (hit != GradientHandleId.NONE) {
            state.activeHandle = hit
            state.dragStartDocPos = docPos
            state.dragOriginalP1 = state.p1
            state.dragOriginalP2 = state.p2
            gradientCreatedOnCurrentTouch = false
            return
        }
        // 未命中现有手柄：先自动提交前序渐变
        val len = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y)
        if (len > 4f) {
            v.commitActiveGradient()
        }
    }

    // 新建渐变
    state.reset(docPos, docPos)
    state.activeHandle = GradientHandleId.END
    state.dragStartDocPos = docPos
    state.dragOriginalP1 = docPos
    state.dragOriginalP2 = docPos
    gradientCreatedOnCurrentTouch = true
}

internal fun CanvasTouchView.handleGradientMove(docPos: Offset) {
    val v = vm ?: return
    val state = v.gradientState
    if (!state.active) return

    when (val handle = state.activeHandle) {
        GradientHandleId.START -> {
            state.p1 = docPos
        }
        GradientHandleId.END -> {
            state.p2 = docPos
        }
        GradientHandleId.MID_BODY -> {
            val delta = docPos - state.dragStartDocPos
            state.p1 = state.dragOriginalP1 + delta
            state.p2 = state.dragOriginalP2 + delta
        }
        else -> {
            if (handle >= GradientHandleId.STOP_BASE) {
                val idx = handle - GradientHandleId.STOP_BASE
                if (idx in 1 until state.stops.size - 1) {
                    val lineVec = state.p2 - state.p1
                    val lenSq = lineVec.x * lineVec.x + lineVec.y * lineVec.y
                    if (lenSq > 1f) {
                        val toPos = docPos - state.p1
                        val proj = (toPos.x * lineVec.x + toPos.y * lineVec.y) / lenSq
                        val newPos = proj.coerceIn(0.01f, 0.99f)
                        val stop = state.stops[idx]
                        state.stops[idx] = stop.copy(position = newPos)
                    }
                }
            }
        }
    }
}

internal fun CanvasTouchView.handleGradientUp(docPos: Offset) {
    val v = vm ?: return
    val state = v.gradientState
    if (!state.active) return

    state.activeHandle = GradientHandleId.NONE
    if (gradientCreatedOnCurrentTouch) {
        gradientCreatedOnCurrentTouch = false
        val len = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y)
        if (len < 4f) {
            val defSpan = 150f / maxOf(0.1f, canvasZoom * canvasFitScale)
            state.p2 = state.p1 + Offset(defSpan, defSpan)
        }
    }
}
