/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.*
import com.reverie.paint.model.*
import kotlin.math.abs
import kotlin.math.hypot

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

/** 结束/作废本次分格手势: 清预览与待定状态。 */
internal fun CanvasTouchView.cancelPanelGesture() {
    removeCallbacks(panelLongPressRunnable)
    panelLongPressToken++
    panelLongPressPending = false
    panelMarqueeArmed = false
    panelDragMode = PANEL_DRAG_NONE
    panelInteraction?.clearDrag()
}

/**
 * 切分线两端延伸兜底 (doc px): 12dp 屏幕距离按当前缩放换算。MOVE 预览高亮与 UP 提交
 * 必须共用本函数, 两处口径漂移会导致预览高亮的格与实际被切的格不一致。
 */
internal fun CanvasTouchView.panelDivideExtendDocPx(currentScale: Float): Float =
    (PANEL_DIVIDE_EXTEND_DP * density) / currentScale

/**
 * 建格模式拖线的"贯穿切分"判定 (与切分模式预览/提交同口径):
 * [segmentCutsQuad] 线段语义 + [cutByLine] 双重过滤 (同一延伸兜底换算 [panelDivideExtendDocPx]),
 * 返回会被切开的格 id 集合。MOVE 预览与 UP 提交必须共用本函数, 两处口径漂移会导致
 * 预览高亮的格与实际被切的格不一致。
 */
internal fun CanvasTouchView.panelDivideHitIds(startDoc: Point2D, endDoc: Point2D, currentScale: Float): Set<Long> {
    val v = vm ?: return emptySet()
    if (!startDoc.x.isFinite() || !startDoc.y.isFinite() ||
        !endDoc.x.isFinite() || !endDoc.y.isFinite()
    ) return emptySet()
    val extendDocPx = panelDivideExtendDocPx(currentScale)
    val halfGap = v.panelDefaults.hGap / 2f
    var hit: MutableSet<Long>? = null
    for (m in v.panels) {
        if (segmentCutsQuad(m.meta.quad, startDoc, endDoc, extendDocPx) &&
            m.meta.quad.cutByLine(startDoc, endDoc, halfGap) != null
        ) {
            val set = hit ?: HashSet<Long>(4).also { hit = it }
            set.add(m.panelId)
        }
    }
    return hit ?: emptySet()
}

/**
 * 边框标尺吸附 (目标 B): 若落笔 doc 点距任一分格某条边线段的距离 < 8dp (换算 doc),
 * 投影吸附到该边线段最近点; 分格不存在或超阈值时原样返回。
 */
internal fun CanvasTouchView.snapPointToPanelBorders(docPos: Point2D): Point2D {
    if (!docPos.x.isFinite() || !docPos.y.isFinite()) return docPos
    val v = vm ?: return docPos
    val mirrors = v.panels
    if (mirrors.isEmpty()) return docPos

    val currentScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
    val snapTolerance = (PANEL_SNAP_TOLERANCE_DP * density) / currentScale
    val tolSq = snapTolerance * snapTolerance

    var bestDistSq = Float.MAX_VALUE
    var bestProjX = 0f
    var bestProjY = 0f

    for (m in mirrors) {
        val corners = m.meta.quad.corners
        val n = corners.size
        for (i in 0 until n) {
            val a = corners[i]
            val b = corners[(i + 1) % n]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lenSq = dx * dx + dy * dy
            val px: Float
            val py: Float
            if (lenSq < 1e-6f) {
                px = a.x
                py = a.y
            } else {
                val t = (((docPos.x - a.x) * dx + (docPos.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
                px = a.x + t * dx
                py = a.y + t * dy
            }
            val ddx = docPos.x - px
            val ddy = docPos.y - py
            val distSq = ddx * ddx + ddy * ddy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestProjX = px
                bestProjY = py
            }
        }
    }

    return if (bestDistSq <= tolSq) Point2D(bestProjX, bestProjY) else docPos
}

/**
 * 格体拖移吸附。多选只保留一条最近的边对边候选。
 * 单格额外加入整页居中，X/Y 各取本轴最近的一条，可以同时吸住。
 */
internal fun CanvasTouchView.computeBodySnap(
    rawDelta: Point2D,
    currentScale: Float,
): Pair<Point2D, List<PanelSnapGuide>> {
    val v = vm ?: return Pair(rawDelta, emptyList())
    if (panelDragIds.isEmpty() || panelDragStartQuads.isEmpty()) return Pair(rawDelta, emptyList())
    val mirrors = v.panels
    if (mirrors.isEmpty()) return Pair(rawDelta, emptyList())

    val neighbors = ArrayList<PanelQuad>(mirrors.size)
    for (m in mirrors) {
        if (m.panelId !in panelDragIds) {
            neighbors.add(m.meta.quad)
        }
    }
    val single = panelDragIds.size == 1
    if (neighbors.isEmpty() && !single) return Pair(rawDelta, emptyList())

    val tolerance = (PANEL_SNAP_TOLERANCE_DP * density) / currentScale
    val hGap = v.panelDefaults.hGap
    val vGap = v.panelDefaults.vGap
    val pageW = v.docWidth.toFloat()
    val pageH = v.docHeight.toFloat()

    if (!single) {
        var bestCandidate: PanelSnapCandidate? = null
        var bestDiff = Float.MAX_VALUE
        for (startQuad in panelDragStartQuads) {
            val movedQuad = startQuad.movedBy(rawDelta)
            val candidates = axisSnapCandidates(movedQuad, neighbors, hGap, vGap, tolerance)
            for (c in candidates) {
                val diff = if (c.guideAxis == 0) abs(c.delta.x) else abs(c.delta.y)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestCandidate = c
                }
            }
        }
        if (bestCandidate == null) return Pair(rawDelta, emptyList())
        val snapped = Point2D(rawDelta.x + bestCandidate.delta.x, rawDelta.y + bestCandidate.delta.y)
        return Pair(snapped, listOf(PanelSnapGuide(bestCandidate.guideAxis, bestCandidate.guideCoord)))
    }

    var bestX: PanelSnapCandidate? = null
    var bestY: PanelSnapCandidate? = null
    var bestXDiff = Float.MAX_VALUE
    var bestYDiff = Float.MAX_VALUE
    val movedQuad = panelDragStartQuads[0].movedBy(rawDelta)
    val candidates = ArrayList<PanelSnapCandidate>()
    if (neighbors.isNotEmpty()) {
        candidates.addAll(axisSnapCandidates(movedQuad, neighbors, hGap, vGap, tolerance))
    }
    if (v.panelSnapPageCenter) {
        candidates.addAll(pageCenterSnapCandidates(movedQuad, pageW, pageH, tolerance))
    }
    for (c in candidates) {
        if (c.guideAxis == 0) {
            val diff = abs(c.delta.x)
            if (diff < bestXDiff) {
                bestXDiff = diff
                bestX = c
            }
        } else if (c.guideAxis == 1) {
            val diff = abs(c.delta.y)
            if (diff < bestYDiff) {
                bestYDiff = diff
                bestY = c
            }
        }
    }
    if (bestX == null && bestY == null) return Pair(rawDelta, emptyList())
    val snappedDelta = Point2D(
        rawDelta.x + (bestX?.delta?.x ?: 0f),
        rawDelta.y + (bestY?.delta?.y ?: 0f),
    )
    val guides = ArrayList<PanelSnapGuide>(2)
    bestX?.let { guides.add(PanelSnapGuide(0, it.guideCoord)) }
    bestY?.let { guides.add(PanelSnapGuide(1, it.guideCoord)) }
    return Pair(snappedDelta, guides)
}

/**
 * 切分线：接近水平或垂直就锁直，并沿锁定方向拉到被切格对边。
 * 没锁方向时终点仍贴边框。MOVE 与两处 UP 共用。
 */
internal fun CanvasTouchView.resolveDivideLine(start: Point2D, finger: Point2D, currentScale: Float): DivideLineSnap {
    val v = vm
    if (v?.panelSnapAxis == false) {
        return DivideLineSnap(start, snapPointToPanelBorders(finger), -1, 0f)
    }
    val hitIds = panelDivideHitIds(start, finger, currentScale)
    val targets = ArrayList<PanelQuad>()
    if (v != null) {
        for (m in v.panels) {
            if (m.panelId in hitIds) targets.add(m.meta.quad)
        }
        if (targets.isEmpty()) {
            val quads = v.panels.map { it.meta.quad }
            val hit = hitTestPanels(quads, -1, start, -1f)
            val idx = (hit as? PanelHit.Body)?.panelIndex ?: -1
            if (idx in v.panels.indices) targets.add(v.panels[idx].meta.quad)
        }
    }
    val locked = snapDivideLine(start, finger, targets, PANEL_PARALLEL_SNAP_ANGLE_RAD)
    if (locked.guideAxis >= 0) return locked
    return DivideLineSnap(start, snapPointToPanelBorders(finger), -1, 0f)
}

/** 边中点拖动吸附: 将整条边平移并吸附到邻格对齐轴或 gutter 间距。 */
internal fun CanvasTouchView.computeMidpointEdgeSnap(
    startQuad: PanelQuad,
    edgeIndex: Int,
    rawDelta: Point2D,
    currentScale: Float,
): Pair<PanelQuad?, PanelSnapGuide?> {
    val v = vm ?: return Pair(startQuad.moveEdge(edgeIndex, rawDelta), null)
    val tolerance = (PANEL_SNAP_TOLERANCE_DP * density) / currentScale
    val selfId = panelDragIds.getOrNull(0) ?: -1L
    val neighbors = ArrayList<PanelQuad>(v.panels.size)
    for (m in v.panels) {
        if (m.panelId != selfId) neighbors.add(m.meta.quad)
    }
    val movedQuad = startQuad.moveEdge(edgeIndex, rawDelta)
    if (neighbors.isEmpty()) {
        return Pair(if (movedQuad.isConvex() && !movedQuad.isDegenerate(PANEL_MIN_AREA)) movedQuad else null, null)
    }
    val candidates = axisSnapCandidates(movedQuad, neighbors, v.panelDefaults.hGap, v.panelDefaults.vGap, tolerance)
    var bestCandidate: PanelSnapCandidate? = null
    var bestDiff = Float.MAX_VALUE
    for (c in candidates) {
        val diff = if (c.guideAxis == 0) abs(c.delta.x) else abs(c.delta.y)
        if (diff < bestDiff) {
            bestDiff = diff
            bestCandidate = c
        }
    }
    val finalDelta = if (bestCandidate != null) {
        Point2D(rawDelta.x + bestCandidate.delta.x, rawDelta.y + bestCandidate.delta.y)
    } else {
        rawDelta
    }
    val snappedQuad = startQuad.moveEdge(edgeIndex, finalDelta)
    val guide = bestCandidate?.let { PanelSnapGuide(it.guideAxis, it.guideCoord) }
    return if (snappedQuad.isConvex() && !snappedQuad.isDegenerate(PANEL_MIN_AREA)) {
        Pair(snappedQuad, guide)
    } else {
        Pair(null, null)
    }
}

/**
 * 共享边拖动 gutter 对齐吸附 (目标 A): 用任一格对非本次共享边的其余邻格调 snapCandidates,
 * 命中则修正共享边 delta (两格联动保持)。
 */
internal fun CanvasTouchView.computeEdgeSnap(
    rawDelta: Point2D,
    currentScale: Float,
): Pair<Pair<PanelQuad, PanelQuad>?, PanelSnapGuide?> {
    val v = vm ?: return Pair(null, null)
    val q0 = panelDragStartQuads.getOrNull(0) ?: return Pair(null, null)
    val q1 = panelDragStartQuads.getOrNull(1) ?: return Pair(null, null)
    val aEdge = panelDragEdgeIndex
    val bEdge = panelDragSecondEdgeIndex
    if (aEdge < 0 || bEdge < 0) return Pair(null, null)

    val rawPair = applyEdgeDrag(q0, q1, aEdge, bEdge, rawDelta, PANEL_MIN_EDGE)
        ?: return Pair(null, null)

    val mirrors = v.panels
    if (mirrors.isEmpty() || panelDragIds.size < 2) return Pair(rawPair, null)

    val id0 = panelDragIds[0]
    val id1 = panelDragIds[1]
    val otherNeighbors = ArrayList<PanelQuad>(mirrors.size)
    for (m in mirrors) {
        if (m.panelId != id0 && m.panelId != id1) {
            otherNeighbors.add(m.meta.quad)
        }
    }
    if (otherNeighbors.isEmpty()) return Pair(rawPair, null)

    val tolerance = (PANEL_SNAP_TOLERANCE_DP * density) / currentScale
    val hGap = v.panelDefaults.hGap
    val vGap = v.panelDefaults.vGap
    // 被拖边近水平时法向是上下, 间隔取 vGap; 其余 (垂直/斜边) 取 hGap
    val edgeGap0 = panelEdgeGap(rawPair.first, aEdge, hGap, vGap)
    val edgeGap1 = panelEdgeGap(rawPair.second, bEdge, hGap, vGap)

    var bestCandidate: PanelSnapCandidate? = null
    var bestDiff = Float.MAX_VALUE

    // 候选集 = 分轴 gutter 候选 + 角度候选 (被拖共享边与邻格边近平行时的法向
    // 位置吸附); 偏差统一按平移量 (hypot(delta), 轴对齐候选单轴分量即平移量) 取最小。
    // 角度候选只对被拖共享边生成 (aEdge/bEdge 是同一条几何边, 两侧各生成一次, 取偏差小者)。
    val candidates0 = axisSnapCandidates(rawPair.first, otherNeighbors, hGap, vGap, tolerance) +
        parallelSnapCandidates(
            rawPair.first, aEdge, otherNeighbors, edgeGap0,
            tolerance, PANEL_PARALLEL_SNAP_ANGLE_RAD,
        )
    for (c in candidates0) {
        val diff = hypot(c.delta.x, c.delta.y)
        if (diff < bestDiff) {
            bestDiff = diff
            bestCandidate = c
        }
    }
    val candidates1 = axisSnapCandidates(rawPair.second, otherNeighbors, hGap, vGap, tolerance) +
        parallelSnapCandidates(
            rawPair.second, bEdge, otherNeighbors, edgeGap1,
            tolerance, PANEL_PARALLEL_SNAP_ANGLE_RAD,
        )
    for (c in candidates1) {
        val diff = hypot(c.delta.x, c.delta.y)
        if (diff < bestDiff) {
            bestDiff = diff
            bestCandidate = c
        }
    }

    if (bestCandidate != null) {
        val snappedDelta = Point2D(rawDelta.x + bestCandidate.delta.x, rawDelta.y + bestCandidate.delta.y)
        val snappedPair = applyEdgeDrag(q0, q1, aEdge, bEdge, snappedDelta, PANEL_MIN_EDGE)
        if (snappedPair != null) {
            // guideAxis = -1 (斜边角度吸附) 无合适参考线形态, 不画
            val guide = if (bestCandidate.guideAxis >= 0) {
                PanelSnapGuide(bestCandidate.guideAxis, bestCandidate.guideCoord)
            } else {
                null
            }
            return Pair(snappedPair, guide)
        }
    }
    return Pair(rawPair, null)
}

/**
 * 最后一帧格体预览相对按下时的平移。松手提交用它, 不用抬笔点重算:
 * 抬笔采样经常跳出磁吸范围, 预览虚线已经对齐, 重算会把格子弹回手指位置。
 */
internal fun CanvasTouchView.panelShownMoveDelta(): Point2D? {
    val shown = panelInteraction?.dragPreviews?.firstOrNull()?.quad ?: return null
    val start = panelDragStartQuads.firstOrNull() ?: return null
    val a = shown.corners.firstOrNull() ?: return null
    val b = start.corners.firstOrNull() ?: return null
    val dx = a.x - b.x
    val dy = a.y - b.y
    if (!dx.isFinite() || !dy.isFinite()) return null
    return Point2D(dx, dy)
}

/** 近水平边的法向间隔是上下 (vGap), 垂直边和斜边用法向左右 (hGap)。 */
internal fun CanvasTouchView.panelEdgeGap(quad: PanelQuad, edgeIndex: Int, hGap: Float, vGap: Float): Float {
    val n = quad.corners.size
    if (edgeIndex !in 0 until n) return hGap
    val a = quad.corners[edgeIndex]
    val b = quad.corners[(edgeIndex + 1) % n]
    val adx = abs(b.x - a.x)
    val ady = abs(b.y - a.y)
    return if (adx >= 4f * ady) vGap else hGap
}

/**
 * 构建角点拖形的角度吸附目标方向集: 除被拖格外所有镜像格的每条边单位方向 (模 180°,
 * 符号不限) + 水平 0°/垂直 90°。HANDLE DOWN 调一次, MOVE 帧直接复用 (零逐帧分配)。
 */
internal fun CanvasTouchView.buildPanelCornerSnapDirs(v: PaintViewModel, draggedPanelId: Long): FloatArray {
    val dirs = ArrayList<Float>(8 + v.panels.size * 8)
    dirs.add(1f); dirs.add(0f) // 水平
    dirs.add(0f); dirs.add(1f) // 垂直
    for (m in v.panels) {
        if (m.panelId == draggedPanelId) continue
        val q = m.meta.quad
        val n = q.corners.size
        for (i in 0 until n) {
            val a = q.corners[i]
            val b = q.corners[(i + 1) % n]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (!len.isFinite() || len < 1e-6f) continue
            dirs.add(dx / len); dirs.add(dy / len)
        }
    }
    val out = FloatArray(dirs.size)
    for (i in out.indices) out[i] = dirs[i]
    return out
}

internal fun CanvasTouchView.flushPendingEdgeFinger() {
    if (!isPendingEdgeFinger) return
    isPendingEdgeFinger = false
    val v = vm ?: return
    val canEyedrop = v.longPressEyedropperEnabled &&
        (v.gestureConfig.getTrigger(com.reverie.paint.model.GestureAction.EYEDROPPER) == com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_LONG_PRESS) &&
        (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY)
    if (canEyedrop) {
        isPendingLongPress = true
        pendingLongPressPointerId = 0
        activeLongPressToken = longPressToken
        pendingDownDocPos = pendingEdgeDocPos
        pendingDownScreenPos = pendingEdgeScreenPos
        pendingDownPressure = 1f
        val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
        postDelayed(longPressRunnable, delayMs)
        if (!v.penOnlyMode) {
            setLocalCursorPos(pendingEdgeScreenPos.x, pendingEdgeScreenPos.y)
            localIsTouching = true
            localIsHovering = false
            localPressure = 1f
            invalidate()
        }
    } else if (!v.penOnlyMode) {
        isPendingLongPress = false
        setLocalCursorPos(pendingEdgeScreenPos.x, pendingEdgeScreenPos.y)
        localIsTouching = true
        localIsHovering = false
        localPressure = 1f
        invalidate()
        handleToolDown(pendingEdgeScreenPos, pendingEdgeDocPos, 1f, isStylus = false)
    }
}
