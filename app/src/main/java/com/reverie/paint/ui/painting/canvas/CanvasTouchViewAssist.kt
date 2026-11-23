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
import kotlin.math.hypot
import com.reverie.paint.ui.painting.canvas.CanvasTouchView.AssistRay

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

/** 是否有任一分支已累积采样点 (绘制 / 回放 / 局部失效判定的共同前提) */
internal fun CanvasTouchView.mirrorBranchHasSamples(): Boolean {
    if (primarySampleSize > 0) return true
    val n = minOf(mirroredSamples.size, mirroredSizes.size)
    for (i in 0 until n) {
        if (mirroredSizes[i] > 0) return true
    }
    return false
}

internal fun CanvasTouchView.resetMirrorBranches() {
    primarySampleSize = 0
    val n = minOf(mirroredSamples.size, mirroredSizes.size)
    for (i in 0 until n) mirroredSizes[i] = 0
    isSymmetryReplaying = false
    symmetryStrokeStartTimeMs = 0L
}

/** 采样时刻 -> 相对本笔起点的秒 (负值夹成 0)。[eventTimeMs] 为 0 时视为起点。 */
internal fun CanvasTouchView.symmetrySampleSeconds(eventTimeMs: Long): Float {
    if (symmetryStrokeStartTimeMs == 0L) {
        symmetryStrokeStartTimeMs =
            if (eventTimeMs > 0L) eventTimeMs else android.os.SystemClock.uptimeMillis()
    }
    val dt = eventTimeMs - symmetryStrokeStartTimeMs
    return if (dt > 0L) (dt / 1000.0).toFloat() else 0f
}

/** 按当前分支数准备缓冲; 多余的数组保留复用, 下一笔不再重新分配 */
internal fun CanvasTouchView.ensureMirrorBranches(count: Int) {
    val wanted = count.coerceAtLeast(0)
    while (mirroredSamples.size > wanted) {
        mirroredSamples.removeAt(mirroredSamples.size - 1)
    }
    while (mirroredSamples.size < wanted) {
        mirroredSamples.add(FloatArray(0))
    }
    // 分支数由对称配置决定 (2..36 轴 -> 最多 71 支): 尺寸数组必须同步扩到分支数,
    // 否则写入第 9 支起就越界。这里只在变长时才重新分配, 稳态零分配。
    if (mirroredSizes.size < mirroredSamples.size) {
        mirroredSizes = mirroredSizes.copyOf(mirroredSamples.size)
    }
    resetMirrorBranches()
}

/**
 * 落笔时按当前对称分支数准备缓冲, 并决定本笔能否走实时镜像。
 *
 * 实时镜像需要每个分支各建一套原生 paintop, 引擎侧固定只接受 0..7; 分支数超出时
 * 整笔改走抬笔重放 ([CanvasTouchView] 抬笔分支), 避免出现"前 8 支实时出墨、其余
 * 分支永久缺墨"的半截结果。
 */
internal fun CanvasTouchView.prepareMirrorBranches(branchCount: Int) {
    ensureMirrorBranches(branchCount)
    mirrorLiveQueueAllowed = branchCount <= PaintViewModel.MIRROR_BRANCH_COUNT
}

/** 追加主落笔采样点 (零分配热路径); 姿态与时间缺省 0 时与旧 3 元语义一致 */
internal fun CanvasTouchView.appendPrimarySample(
    x: Float,
    y: Float,
    pressure: Double,
    tiltX: Float = 0f,
    tiltY: Float = 0f,
    rotation: Float = 0f,
    timeSeconds: Float = 0f,
) {
    val used = primarySampleSize
    val need = (used + 1) * SYMMETRY_SAMPLE_STRIDE
    if (primarySamples.size < need) {
        val grown = FloatArray(maxOf(need, primarySamples.size * 2, 48))
        System.arraycopy(primarySamples, 0, grown, 0, used * SYMMETRY_SAMPLE_STRIDE)
        primarySamples = grown
    }
    val base = used * SYMMETRY_SAMPLE_STRIDE
    primarySamples[base] = x
    primarySamples[base + 1] = y
    primarySamples[base + 2] = pressure.toFloat()
    primarySamples[base + 3] = tiltX
    primarySamples[base + 4] = tiltY
    primarySamples[base + 5] = rotation
    primarySamples[base + 6] = timeSeconds
    primarySampleSize = used + 1
}

/**
 * 追加一个镜像采样点。缓冲以 [SYMMETRY_SAMPLE_STRIDE] 个 float 为一组存
 * [x, y, pressure, tiltX, tiltY, rotation, timeSeconds], 扩容按需翻倍 (初始 16 个点),
 * 热路径上不产生任何对象。旧的低元数组照常按需扩容。
 */
internal fun CanvasTouchView.appendMirrorSample(
    branchIndex: Int,
    x: Float,
    y: Float,
    pressure: Double,
    tiltX: Float = 0f,
    tiltY: Float = 0f,
    rotation: Float = 0f,
    timeSeconds: Float = 0f,
) {
    if (branchIndex < 0 || branchIndex >= mirroredSamples.size || branchIndex >= mirroredSizes.size) return
    val used = mirroredSizes[branchIndex]
    val need = (used + 1) * SYMMETRY_SAMPLE_STRIDE
    var buf = mirroredSamples[branchIndex]
    if (buf.size < need) {
        val grown = FloatArray(maxOf(need, buf.size * 2, 48))
        System.arraycopy(buf, 0, grown, 0, used * SYMMETRY_SAMPLE_STRIDE)
        mirroredSamples[branchIndex] = grown
        buf = grown
    }
    val base = used * SYMMETRY_SAMPLE_STRIDE
    buf[base] = x
    buf[base + 1] = y
    buf[base + 2] = pressure.toFloat()
    buf[base + 3] = tiltX
    buf[base + 4] = tiltY
    buf[base + 5] = rotation
    buf[base + 6] = timeSeconds
    mirroredSizes[branchIndex] = used + 1
    // 实时镜像: 同一点送进 ViewModel 的镜像队列, 由渲染线程画进已经打开的笔触。
    // 不要在其他地方再送一份, 否则会画两次。分支数超过引擎上限时整笔走重放, 这里不入队。
    if (mirrorLiveQueueAllowed) {
        vm?.queueMirrorSample(branchIndex, x, y, pressure.toFloat(), tiltX, tiltY, rotation, timeSeconds)
    }
}

/** 当前对称类型下, 一个文档坐标点对应的镜像采样点 (含镜像后的姿态)。 */
internal fun CanvasTouchView.symmetricSamplesFor(
    x: Float,
    y: Float,
    tiltX: Float,
    tiltY: Float,
    rotation: Float,
): List<SymmetricSample> {
    val v = vm ?: return emptyList()
    return v.drawingGuide.computeSymmetricSamples(
        Point2D(x, y), tiltX, tiltY, rotation, v.docWidth, v.docHeight,
    )
}

/**
 * 把一个采样点同时写进主笔与所有镜像分支 (镜像分支带镜像后的姿态)。
 * 调用前必须已 [ensureMirrorBranches]: 分支数在落笔时按当前对称类型固定。
 * [eventTimeMs] 是本次采样的 uptime 毫秒, 换算成相对本笔起点的秒写进缓冲。
 */
internal fun CanvasTouchView.appendSymmetricSamplePair(
    x: Float,
    y: Float,
    pressure: Double,
    tiltX: Float,
    tiltY: Float,
    rotation: Float,
    eventTimeMs: Long,
) {
    val t = symmetrySampleSeconds(eventTimeMs)
    appendPrimarySample(x, y, pressure, tiltX, tiltY, rotation, t)
    val samples = symmetricSamplesFor(x, y, tiltX, tiltY, rotation)
    for (i in samples.indices) {
        val s = samples[i]
        appendMirrorSample(i, s.point.x, s.point.y, pressure, s.tiltX, s.tiltY, s.rotation, t)
    }
}

/**
 * 抬笔时把主笔的追赶点 (平滑未追完的剩余段) 与尾锋点同步写进镜像分支。
 *
 * 必须与 `PaintViewModelTools.touchEnd` 里主笔那两段逐字对齐 —— 同一条
 * `strokeSmoothingType` 判定、同一个缓动、同一套步数预算/压感过渡/时间戳步进
 * (R4-B: 追赶点+尾锋点总数 ≤3 步、压感从平滑值线性过渡到抬笔末端压感、每步 +4ms),
 * 否则抬笔重放后镜像笔迹的末端与主笔对不上。放在界面层是因为对称几何属于界面侧
 * (引擎不知道对称模式), 不能反过来让 ViewModel 依赖它。
 *
 * [tailEndPressure] 与 touchEnd 收到的值同源 (handleToolUp 计算一次两边传), 保证逐字一致。
 */
internal fun CanvasTouchView.appendMirrorCatchUpAndTaper(v: PaintViewModel, eventTimeMs: Long, tailEndPressure: Double) {
    val tiltX = strokeTiltX.toFloat()
    val tiltY = strokeTiltY.toFloat()
    val rotation = touchRotation.toFloat()
    val needCatchUp = when (v.strokeSmoothingType) {
        PaintViewModel.SMOOTHING_OFF -> false
        PaintViewModel.SMOOTHING_WEIGHTED ->
            (v.strokeSmoothnessDistanceMin > 0.0 || v.strokeSmoothnessDistanceMax > 0.0)
        else -> maxOf(v.strokeStabilizer.toDouble(), v.brushStreamline) > 0.0
    }
    // 与 touchEnd 共用同一套步数计划 (tailStepPlan 单一来源); 剩余 ≤0.5px 追赶 0 步,
    // 同样不发零时差重复样本, 保证主/镜像收笔补点总数与位置逐字一致
    val taperRuns = StrokeTailMath.taperWillRun(v.brushTaper, v.lastStrokeDeltaX, v.lastStrokeDeltaY)
    val tailDx = v.lastStrokeX - v.smoothedStrokeX
    val tailDy = v.lastStrokeY - v.smoothedStrokeY
    val tailRemainingDist = hypot(tailDx.toDouble(), tailDy.toDouble())
    val tailPlan = StrokeTailMath.tailStepPlan(needCatchUp, tailRemainingDist, taperRuns)
    // 尾锋起点跟着 touchEnd 的推进走: 追赶跑完时 smoothed 已被拉到 lastStroke
    val startP = v.smoothedStrokePressure
    var tailX = v.smoothedStrokeX
    var tailY = v.smoothedStrokeY
    var tailP = startP
    if (needCatchUp && tailRemainingDist > 0.5 && tailPlan.catchUpSteps > 0) {
        val steps = tailPlan.catchUpSteps
        val startX = tailX
        val startY = tailY
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val ease = StrokeTailMath.easeOutCubic(t)
            val curX = startX + tailDx * ease
            val curY = startY + tailDy * ease
            val curP = StrokeTailMath.tailPressure(startP, tailEndPressure, t)
            appendSymmetricSamplePair(
                curX, curY, curP, tiltX, tiltY, rotation,
                eventTimeMs + step * StrokeTailMath.TAIL_STEP_DT_MS,
            )
        }
        tailX = v.lastStrokeX
        tailY = v.lastStrokeY
        tailP = StrokeTailMath.tailPressure(startP, tailEndPressure, 1f)
    }
    if (taperRuns) {
        val norm = hypot(v.lastStrokeDeltaX.toDouble(), v.lastStrokeDeltaY.toDouble()).toFloat()
        if (norm > 0.5f) {
            val nx = v.lastStrokeDeltaX / norm
            val ny = v.lastStrokeDeltaY / norm
            val taperSteps = tailPlan.taperSteps
            val stepLen = (v.brushSize * v.brushTaper * 0.75).toFloat() / taperSteps
            var tx = tailX
            var ty = tailY
            var tp = tailP
            for (i in 1..taperSteps) {
                tx += nx * stepLen
                ty += ny * stepLen
                tp = StrokeTailMath.taperPressure(tp, i, taperSteps)
                appendSymmetricSamplePair(
                    tx, ty, tp, tiltX, tiltY, rotation,
                    eventTimeMs + (tailPlan.catchUpSteps + i) * StrokeTailMath.TAIL_STEP_DT_MS,
                )
            }
        }
    }
}

internal fun CanvasTouchView.updateSymmetryPressureLut(v: PaintViewModel) {
    val currentPreset = v.brushPresetIndex
    val currentCurve = v.brushPressureCurve
    val currentEnabled = v.brushPressureEnabled
    val currentSize = v.brushPressureSize
    val currentGlobal = v.pressureControlPoints

    if (currentPreset == lastLutPresetIndex &&
        currentCurve == lastLutCurve &&
        currentEnabled == lastLutEnabled &&
        currentSize == lastLutPressureSize &&
        currentGlobal === lastLutGlobalCurvePoints
    ) {
        return
    }

    lastLutPresetIndex = currentPreset
    lastLutCurve = currentCurve
    lastLutEnabled = currentEnabled
    lastLutPressureSize = currentSize
    lastLutGlobalCurvePoints = currentGlobal

    for (k in 0..128) {
        val effP = (k / 128.0).toFloat()
        val bridgeFrac = pressureFractionCached(effP)
        val frac = if (currentEnabled) {
            if (bridgeFrac > 0f && bridgeFrac < 1.0f) {
                (1.0f - currentSize.toFloat()) + currentSize.toFloat() * bridgeFrac
            } else {
                (1.0f - currentSize.toFloat()) + currentSize.toFloat() * effP
            }
        } else {
            1.0f
        }
        symmetryPressureLut[k] = frac.coerceIn(0.01f, 1f)
    }
}

internal fun CanvasTouchView.computeAllSymmetricPoints(docPt: Point2D): List<Point2D> {
    val v = vm ?: return emptyList()
    return v.drawingGuide.computeSymmetricPoints(docPt, v.docWidth, v.docHeight)
}

internal fun CanvasTouchView.resetFisheyeAssist() {
    assistFisheyeLocked = false
    assistFisheyeFamily = 0
    assistFisheyeA = 0.0
}

/**
 * 把当前笔尖投影回锁定的一条鱼眼曲线。
 *
 * 方向向量 -> 反投影到球面 -> 固定 a 的曲线只保留一个自由度 (t) -> 再投影回画布。
 * 按面板选择锁定离起点最近的一条横弧/纵弧，或经过球心的中心射线；
 * 锁定后整笔都投影到该曲线上。
 */
internal fun CanvasTouchView.applyFisheyeAssist(
    v: PaintViewModel,
    guide: com.reverie.paint.model.DrawingGuideConfig,
    firstPt: Offset,
    currentPt: Offset,
): Offset {
    val minDim = minOf(v.docWidth, v.docHeight).toFloat().coerceAtLeast(1f)
    val centerX = (guide.fisheyeCenterX * v.docWidth).toDouble()
    val centerY = (guide.fisheyeCenterY * v.docHeight).toDouble()
    val radius = (guide.fisheyeRadius * minDim).toDouble().coerceAtLeast(1.0)
    // 半球投影只在圆盘内有定义; 圆盘外起笔不吸附, 避免所有点被钳到边缘。
    if (hypot(firstPt.x.toDouble() - centerX, firstPt.y.toDouble() - centerY) > radius) return currentPt
    fun dir(px: Offset) = FisheyeGuideGeometry.directionFromDocument(
        px.x.toDouble(), px.y.toDouble(), centerX, centerY, radius,
    )
    if (!assistFisheyeLocked) {
        val dStart = dir(firstPt)
        if (guide.fisheyeSnapTarget == com.reverie.paint.model.FISHEYE_SNAP_CENTER_RAY) {
            assistFisheyeFamily = 2
            if (hypot(dStart.first, dStart.second) <= 1e-6) {
                val s0 = docToScreen(firstPt)
                val s1 = docToScreen(currentPt)
                if (hypot(s1.x - s0.x, s1.y - s0.y) < 3f * density) return currentPt
                val dCurrent = dir(currentPt)
                assistFisheyeA = FisheyeGuideGeometry.azimuthOf(dCurrent.first, dCurrent.second)
            } else {
                assistFisheyeA = FisheyeGuideGeometry.azimuthOf(dStart.first, dStart.second)
            }
            assistFisheyeLocked = true
        } else {
            // 合并后的弧线模式: 起笔后按初始运笔方向选横/纵弧, 参数直接取
            // 起笔点的连续值 (不量化到显示线), 因此空白区域同样有鱼眼约束,
            // 且落点不会被拉到邻近的可见参考线上。
            val s0 = docToScreen(firstPt)
            val s1 = docToScreen(currentPt)
            val sdx = s1.x - s0.x
            val sdy = s1.y - s0.y
            if (hypot(sdx, sdy) < 3f * density) return currentPt
            assistFisheyeFamily = FisheyeGuideGeometry.chooseCurveFamily(
                dStart.first,
                dStart.second,
                dStart.third,
                (currentPt.x - firstPt.x).toDouble(),
                (currentPt.y - firstPt.y).toDouble(),
                centerX,
                centerY,
                radius,
            )
            assistFisheyeA = if (assistFisheyeFamily == 0) {
                FisheyeGuideGeometry.horizontalParameter(dStart.first, dStart.second, dStart.third)
            } else {
                FisheyeGuideGeometry.verticalParameter(dStart.first, dStart.second, dStart.third)
            }
            assistFisheyeLocked = true
        }
    }

    val d = dir(currentPt)
    val snapped = snapFisheyePoint(d, centerX, centerY, radius)
    return snapped
}

internal fun CanvasTouchView.snapFisheyePoint(
    direction: Triple<Double, Double, Double>,
    centerX: Double,
    centerY: Double,
    radius: Double,
): Offset {
    val snapped = when (assistFisheyeFamily) {
        0 -> FisheyeGuideGeometry.snapToHorizontalCurve(
            assistFisheyeA, direction.first, direction.second, direction.third,
        )
        1 -> FisheyeGuideGeometry.snapToVerticalCurve(
            assistFisheyeA, direction.first, direction.second, direction.third,
        )
        else -> FisheyeGuideGeometry.snapToCenterRay(
            assistFisheyeA, direction.first, direction.second, direction.third,
        )
    }
    val p = FisheyeGuideGeometry.documentFromDirection(
        snapped.first, snapped.second, snapped.third, centerX, centerY, radius,
    )
    return Offset(p.x, p.y)
}

internal fun CanvasTouchView.applyAssistedDrawing(firstPt: Offset, currentPt: Offset): Offset {
    val v = vm ?: return currentPt
    val guide = v.drawingGuide
    if (!guide.assistedDrawing) return currentPt
    val dx = currentPt.x - firstPt.x
    val dy = currentPt.y - firstPt.y
    val dist = hypot(dx, dy)
    return when (guide.mode) {
        GuideMode.GRID_2D -> {
            val locked = assistLockedRay
            if (locked != null) {
                return when (locked) {
                    is AssistRay.Horizontal -> Offset(currentPt.x, locked.y)
                    is AssistRay.Vertical -> Offset(locked.x, currentPt.y)
                    else -> currentPt
                }
            }
            if (dist < 4f) return currentPt
            val isHoriz = abs(dx) >= abs(dy)
            if (dist >= 8f) {
                assistLockedRay = if (isHoriz) AssistRay.Horizontal(firstPt.y) else AssistRay.Vertical(firstPt.x)
            }
            if (isHoriz) Offset(currentPt.x, firstPt.y) else Offset(firstPt.x, currentPt.y)
        }
        GuideMode.ISOMETRIC -> {
            val locked = assistLockedRay
            if (locked is AssistRay.Direction) {
                val dot = dx * locked.dir.x + dy * locked.dir.y
                return Offset(firstPt.x + locked.dir.x * dot, firstPt.y + locked.dir.y * dot)
            }
            if (dist < 4f) return currentPt
            var bestDir = ISO_DIRS[0]
            var minError = Float.MAX_VALUE
            for (dir in ISO_DIRS) {
                val err = abs(-dx * dir.y + dy * dir.x)
                if (err < minError) {
                    minError = err
                    bestDir = dir
                }
            }
            if (dist >= 8f) {
                assistLockedRay = AssistRay.Direction(bestDir)
            }
            val dot = dx * bestDir.x + dy * bestDir.y
            Offset(firstPt.x + bestDir.x * dot, firstPt.y + bestDir.y * dot)
        }
        GuideMode.PERSPECTIVE -> {
            if (guide.perspectiveFisheye) {
                return applyFisheyeAssist(v, guide, firstPt, currentPt)
            }
            if (guide.perspective3d) return currentPt
            val vps = if (guide.perspectiveVanishingPoints.isEmpty()) {
                listOf(Point2D(v.docWidth * 0.5f, vm?.docHeight?.toFloat()?.times(0.35f) ?: 350f))
            } else guide.perspectiveVanishingPoints

            if (dist < 4f && assistLockedRay == null) return currentPt

            // 若已锁定射线，直接沿该锁定方向投影
            val locked = assistLockedRay
            if (locked != null) {
                return when (locked) {
                    is AssistRay.Horizontal -> Offset(currentPt.x, locked.y)
                    is AssistRay.Vertical -> Offset(locked.x, currentPt.y)
                    is AssistRay.VanishingPoint -> {
                        val dot = dx * locked.dir.x + dy * locked.dir.y
                        Offset(firstPt.x + locked.dir.x * dot, firstPt.y + locked.dir.y * dot)
                    }
                    is AssistRay.Direction -> {
                        val dot = dx * locked.dir.x + dy * locked.dir.y
                        Offset(firstPt.x + locked.dir.x * dot, firstPt.y + locked.dir.y * dot)
                    }
                }
            }

            // 未锁定时寻找最佳候选射线
            var bestCandidate = Offset(currentPt.x, firstPt.y)
            var bestRay: AssistRay = AssistRay.Horizontal(firstPt.y)
            var minError = abs(dy)

            val vertError = abs(dx)
            if (vertError < minError) {
                minError = vertError
                bestCandidate = Offset(firstPt.x, currentPt.y)
                bestRay = AssistRay.Vertical(firstPt.x)
            }

            for (vp in vps) {
                val vRayX = vp.x - firstPt.x
                val vRayY = vp.y - firstPt.y
                val vLen = hypot(vRayX, vRayY)
                if (vLen > 0.001f) {
                    val nx = vRayX / vLen
                    val ny = vRayY / vLen
                    val dot = dx * nx + dy * ny
                    val err = abs(dx * (-ny) + dy * nx)
                    if (err < minError) {
                        minError = err
                        bestCandidate = Offset(firstPt.x + nx * dot, firstPt.y + ny * dot)
                        bestRay = AssistRay.VanishingPoint(vp, Offset(nx, ny))
                    }
                }
            }

            // 运笔超过 8px 判定阈值即刻锁定方向，避免运笔中途跳变
            if (dist >= 8f) {
                assistLockedRay = bestRay
            }

            bestCandidate
        }
        else -> currentPt
    }
}

internal fun CanvasTouchView.checkHitGuideHandle(screenPos: Offset): Boolean {
    val v = vm ?: return false
    if (!drawingGuidePanelOpen && !v.drawingGuidePanelOpen) return false
    if (!v.drawingGuideLinesVisible) {
        draggingGuideHandleIndex = -1
        return false
    }
    val guide = v.drawingGuide
    if (guide.mode == GuideMode.PERSPECTIVE) {
        if (guide.perspectiveFisheye) {
            val minDim = minOf(v.docWidth, v.docHeight).toFloat()
            val cDoc = Offset(v.docWidth * guide.fisheyeCenterX, v.docHeight * guide.fisheyeCenterY)
            val cScreen = docToScreen(cDoc)
            if (hypot(screenPos.x - cScreen.x, screenPos.y - cScreen.y) < 36f * density) {
                draggingGuideHandleIndex = 102
                isPendingLongPress = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            val rimDoc = Offset(cDoc.x + guide.fisheyeRadius * minDim, cDoc.y)
            val rimScreen = docToScreen(rimDoc)
            if (hypot(screenPos.x - rimScreen.x, screenPos.y - rimScreen.y) < 36f * density) {
                draggingGuideHandleIndex = 103
                isPendingLongPress = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            return false
        }
        if (guide.perspective3d) {
            val orbitDoc = com.reverie.paint.model.DrawingGuideHandleGeometry
                .perspective3dOrbitHandleDocPos(v.docWidth, v.docHeight, guide)
            val orbitScreen = docToScreen(Offset(orbitDoc.x, orbitDoc.y))
            if (com.reverie.paint.model.DrawingGuideHandleGeometry.isWithinHitRadius(
                    screenPos.x,
                    screenPos.y,
                    orbitScreen.x,
                    orbitScreen.y,
                    com.reverie.paint.model.DrawingGuideHandleGeometry.PERSPECTIVE_3D_ORBIT_HIT_RADIUS_DP,
                    density,
                )
            ) {
                draggingGuideHandleIndex = 200
                lastOrbitDocPos = screenToDoc(screenPos)
                isPendingLongPress = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            return false
        }
        val vps = guide.perspectiveVanishingPoints
        var hitVp = -1
        for (i in vps.indices) {
            val vpScreen = docToScreen(Offset(vps[i].x, vps[i].y))
            if (com.reverie.paint.model.DrawingGuideHandleGeometry.isWithinHitRadius(
                    screenPos.x,
                    screenPos.y,
                    vpScreen.x,
                    vpScreen.y,
                    com.reverie.paint.model.DrawingGuideHandleGeometry.PERSPECTIVE_VP_HIT_RADIUS_DP,
                    density,
                )
            ) {
                hitVp = i
                break
            }
        }
        if (hitVp != -1) {
            draggingGuideHandleIndex = hitVp
            isPendingLongPress = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
    } else if (guide.mode == GuideMode.SYMMETRY) {
        val centerDoc = com.reverie.paint.model.DrawingGuideHandleGeometry
            .symmetryCenterDocPos(v.docWidth, v.docHeight, guide)
        val symScreen = docToScreen(Offset(centerDoc.x, centerDoc.y))
        // 1. 中心平移控制柄
        if (com.reverie.paint.model.DrawingGuideHandleGeometry.isWithinHitRadius(
                screenPos.x,
                screenPos.y,
                symScreen.x,
                symScreen.y,
                com.reverie.paint.model.DrawingGuideHandleGeometry.SYMMETRY_HANDLE_HIT_RADIUS_DP,
                density,
            )
        ) {
            draggingGuideHandleIndex = 100
            isPendingLongPress = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        // 2. 旋转控制柄 (沿对称主轴分布)
        val rotDoc = com.reverie.paint.model.DrawingGuideHandleGeometry
            .symmetryRotationHandleDocPos(v.docWidth, v.docHeight, guide)
        val rotScreen = docToScreen(Offset(rotDoc.x, rotDoc.y))
        if (com.reverie.paint.model.DrawingGuideHandleGeometry.isWithinHitRadius(
                screenPos.x,
                screenPos.y,
                rotScreen.x,
                rotScreen.y,
                com.reverie.paint.model.DrawingGuideHandleGeometry.SYMMETRY_HANDLE_HIT_RADIUS_DP,
                density,
            )
        ) {
            draggingGuideHandleIndex = 101
            isPendingLongPress = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
    }
    return false
}

internal fun CanvasTouchView.handleGuideHandleDrag(docPos: Offset): Boolean {
    val v = vm ?: return false
    val g = v.drawingGuide
    return when (draggingGuideHandleIndex) {
        102 -> {
            val newX = (docPos.x / maxOf(1, v.docWidth)).coerceIn(0.02f, 0.98f)
            val newY = (docPos.y / maxOf(1, v.docHeight)).coerceIn(0.02f, 0.98f)
            v.drawingGuide = g.copy(fisheyeCenterX = newX, fisheyeCenterY = newY)
            invalidate()
            true
        }
        103 -> {
            val cx = v.docWidth * g.fisheyeCenterX
            val cy = v.docHeight * g.fisheyeCenterY
            val minDim = minOf(v.docWidth, v.docHeight).toFloat().coerceAtLeast(1f)
            val radius = (hypot(docPos.x - cx, docPos.y - cy) / minDim).coerceIn(0.05f, 1.5f)
            v.drawingGuide = g.copy(fisheyeRadius = radius)
            invalidate()
            true
        }
        200 -> {
            val dx = docPos.x - lastOrbitDocPos.x
            val dy = docPos.y - lastOrbitDocPos.y
            lastOrbitDocPos = docPos
            val docDim = maxOf(200f, maxOf(v.docWidth, v.docHeight).toFloat())
            val rotSpeed = 360f / docDim
            var newYaw = (g.perspectiveYawDeg + dx * rotSpeed) % 360f
            if (newYaw > 180f) newYaw -= 360f
            if (newYaw < -180f) newYaw += 360f
            val newPitch = (g.perspectivePitchDeg - dy * rotSpeed).coerceIn(-85f, 85f)
            v.drawingGuide = g.copy(
                perspectiveYawDeg = newYaw,
                perspectivePitchDeg = newPitch,
            )
            invalidate()
            true
        }
        100 -> {
            val isZeroRot = com.reverie.paint.model.DrawingGuideHandleGeometry
                .isAxisRotationZero(g.symmetryRotationDeg)
            val newX = if (g.symmetryType == SymmetryType.HORIZONTAL && isZeroRot) g.symmetryCenterX else (docPos.x / v.docWidth).coerceIn(0.05f, 0.95f)
            val newY = if (g.symmetryType == SymmetryType.VERTICAL && isZeroRot) g.symmetryCenterY else (docPos.y / v.docHeight).coerceIn(0.05f, 0.95f)
            v.drawingGuide = g.copy(symmetryCenterX = newX, symmetryCenterY = newY)
            invalidate()
            true
        }
        101 -> {
            val cx = v.docWidth * g.symmetryCenterX
            val cy = v.docHeight * g.symmetryCenterY
            val dx = docPos.x - cx
            val dy = docPos.y - cy
            val angRad = atan2(-dx, dy)
            var deg = (angRad * 180f / PI.toFloat() + 360f) % 180f
            for (snapDeg in floatArrayOf(0f, 45f, 90f, 135f, 180f)) {
                if (abs(deg - snapDeg) <= 3.5f) {
                    deg = if (snapDeg == 180f) 0f else snapDeg
                    break
                }
            }
            v.drawingGuide = g.copy(symmetryRotationDeg = deg)
            invalidate()
            true
        }
        in 0 until g.perspectiveVanishingPoints.size -> {
            val pts = g.perspectiveVanishingPoints.toMutableList()
            pts[draggingGuideHandleIndex] = Point2D(docPos.x, docPos.y)
            v.drawingGuide = g.copy(perspectiveVanishingPoints = pts)
            invalidate()
            true
        }
        else -> false
    }
}

internal fun CanvasTouchView.clearMeasure() {
    measureStart?.value = null
    measureEnd?.value = null
}

/**
 * 窗口这一帧会在第 2.5 段中止 (3D 透视辅助, 见 [computeDynamicGuide]): 关低延迟时光标根本画不出来,
 * 前缓冲也不画, 才同图。
 */
internal fun CanvasTouchView.dynamicGuideAbortsFrame(v: PaintViewModel): Boolean {
    val g = v.drawingGuide
    val assistTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE
    return g.mode == GuideMode.PERSPECTIVE && g.perspective3d && g.assistedDrawing && localIsTouching &&
        strokeStarted && assistTool
}

/** 这一笔可能出现动态吸附导引线 (2D 透视 / 等轴测 / 2D 网格 且开了辅助绘图)。 */
internal fun CanvasTouchView.snapGuidePossible(v: PaintViewModel): Boolean {
    val g = v.drawingGuide
    // 鱼眼当前不显示动态提示线, 前缓冲也不要为它预留孔洞/绘制。
    if (g.mode == GuideMode.PERSPECTIVE && g.perspectiveFisheye) return false
    return FrontBufferHolePolicy.snapLinesPossible(
        g.assistedDrawing,
        g.mode == GuideMode.PERSPECTIVE && !g.perspective3d,
        g.mode == GuideMode.ISOMETRIC || g.mode == GuideMode.GRID_2D,
    )
}

/**
 * 绘画中辅助吸附动态导引线的几何 (屏幕坐标) 写进 [out] (`x1,y1,x2,y2,vpX,vpY`)。
 * 返回 [DynamicGuidePainter.KIND_NONE] / [DynamicGuidePainter.KIND_LINE] / [DynamicGuidePainter.KIND_LINE_VP];
 * 3D 透视返回 [DYNAMIC_GUIDE_ABORT_FRAME] —— 主画布沿用原行为在此处结束本帧 (不画光标)。
 * 主画布每帧调用; 真墨按住时 MOVE 也调用它给前缓冲做快照。
 */
internal fun CanvasTouchView.computeDynamicGuide(v: PaintViewModel, out: FloatArray): Int {
    var kind = DynamicGuidePainter.KIND_NONE
    val guide = v.drawingGuide
    // 注意: 不能复用上面 1.5 段那支 isDrawingTool(它不含 SMUDGE) —— 两者语义不同,
    // 合并上游 1.3.3 时曾因此产生重复声明。
    val isGuideAssistTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE
    if (guide.mode != GuideMode.OFF && guide.assistedDrawing && localIsTouching && strokeStarted && isGuideAssistTool) {
        val maxExt = maxOf(v.docWidth, v.docHeight).toFloat() * 50f + 500_000f
        when (guide.mode) {
            GuideMode.PERSPECTIVE -> {
                // 鱼眼不参与旧透视的动态提示线: 旧逻辑会用 perspectiveVanishingPoints
                // 或默认 (0.5w, 0.35h) 画斜虚线, 与鱼眼圆心无关。
                // 暂不画曲线高亮, 直接返回"没有提示线", 不是中止整帧。
                if (guide.perspectiveFisheye) return DynamicGuidePainter.KIND_NONE
                if (guide.perspective3d) return DYNAMIC_GUIDE_ABORT_FRAME
                val locked = assistLockedRay
                val dx = currentStrokeDocPos.x - firstDocPos.x
                val dy = currentStrokeDocPos.y - firstDocPos.y
                val dist = hypot(dx, dy)
                if (locked != null || dist >= 6f) {
                    var bestType = 0 // 0: horizontal, 1: vertical, 2: vp
                    var bestVp: Point2D? = null
                    var bestDir = Offset(1f, 0f)

                    if (locked is AssistRay.Horizontal) {
                        bestType = 0
                    } else if (locked is AssistRay.Vertical) {
                        bestType = 1
                    } else if (locked is AssistRay.VanishingPoint) {
                        bestType = 2
                        bestVp = locked.vp
                        bestDir = locked.dir
                    } else if (locked is AssistRay.Direction) {
                        bestType = 2
                        bestDir = locked.dir
                    } else {
                        val vps = if (guide.perspectiveVanishingPoints.isEmpty()) {
                            listOf(Point2D(v.docWidth * 0.5f, v.docHeight * 0.35f))
                        } else guide.perspectiveVanishingPoints

                        var minError = abs(dy)
                        val vertError = abs(dx)
                        if (vertError < minError) {
                            minError = vertError
                            bestType = 1
                        }

                        for (vp in vps) {
                            val vRayX = vp.x - firstDocPos.x
                            val vRayY = vp.y - firstDocPos.y
                            val vLen = hypot(vRayX, vRayY)
                            if (vLen > 0.001f) {
                                val nx = vRayX / vLen
                                val ny = vRayY / vLen
                                val err = abs(dx * (-ny) + dy * nx)
                                if (err < minError) {
                                    minError = err
                                    bestType = 2
                                    bestVp = vp
                                    bestDir = Offset(nx, ny)
                                }
                            }
                        }
                    }

                    when (bestType) {
                        0 -> {
                            val p1 = docToScreen(Offset(firstDocPos.x - maxExt, firstDocPos.y))
                            val p2 = docToScreen(Offset(firstDocPos.x + maxExt, firstDocPos.y))
                            kind = writeGuideSeg(out, p1, p2)
                        }
                        1 -> {
                            val p1 = docToScreen(Offset(firstDocPos.x, firstDocPos.y - maxExt))
                            val p2 = docToScreen(Offset(firstDocPos.x, firstDocPos.y + maxExt))
                            kind = writeGuideSeg(out, p1, p2)
                        }
                        2 -> {
                            val p1 = docToScreen(Offset(firstDocPos.x - bestDir.x * maxExt, firstDocPos.y - bestDir.y * maxExt))
                            val p2 = docToScreen(Offset(firstDocPos.x + bestDir.x * maxExt, firstDocPos.y + bestDir.y * maxExt))
                            kind = writeGuideSeg(out, p1, p2)

                            if (bestVp != null) {
                                val vpScreen = docToScreen(Offset(bestVp.x, bestVp.y))
                                out[4] = vpScreen.x
                                out[5] = vpScreen.y
                                kind = DynamicGuidePainter.KIND_LINE_VP
                            }
                        }
                    }
                }
            }
            GuideMode.ISOMETRIC -> {
                val locked = assistLockedRay
                val dx = currentStrokeDocPos.x - firstDocPos.x
                val dy = currentStrokeDocPos.y - firstDocPos.y
                val dist = hypot(dx, dy)
                if (locked is AssistRay.Direction || dist >= 6f) {
                    val dir = if (locked is AssistRay.Direction) {
                        locked.dir
                    } else {
                        var bestDir = ISO_DIRS[0]
                        var minError = Float.MAX_VALUE
                        for (d in ISO_DIRS) {
                            val err = abs(-dx * d.y + dy * d.x)
                            if (err < minError) {
                                minError = err
                                bestDir = d
                            }
                        }
                        bestDir
                    }
                    val p1 = docToScreen(Offset(firstDocPos.x - dir.x * maxExt, firstDocPos.y - dir.y * maxExt))
                    val p2 = docToScreen(Offset(firstDocPos.x + dir.x * maxExt, firstDocPos.y + dir.y * maxExt))
                    kind = writeGuideSeg(out, p1, p2)
                }
            }
            GuideMode.GRID_2D -> {
                val locked = assistLockedRay
                val dx = currentStrokeDocPos.x - firstDocPos.x
                val dy = currentStrokeDocPos.y - firstDocPos.y
                val dist = hypot(dx, dy)
                if (locked != null || dist >= 6f) {
                    val isHoriz = if (locked is AssistRay.Horizontal) {
                        true
                    } else if (locked is AssistRay.Vertical) {
                        false
                    } else {
                        abs(dx) >= abs(dy)
                    }
                    if (isHoriz) {
                        val p1 = docToScreen(Offset(firstDocPos.x - maxExt, firstDocPos.y))
                        val p2 = docToScreen(Offset(firstDocPos.x + maxExt, firstDocPos.y))
                        kind = writeGuideSeg(out, p1, p2)
                    } else {
                        val p1 = docToScreen(Offset(firstDocPos.x, firstDocPos.y - maxExt))
                        val p2 = docToScreen(Offset(firstDocPos.x, firstDocPos.y + maxExt))
                        kind = writeGuideSeg(out, p1, p2)
                    }
                }
            }
            else -> Unit
        }
    }
    return kind
}

internal fun CanvasTouchView.writeGuideSeg(out: FloatArray, p1: Offset, p2: Offset): Int {
    out[0] = p1.x
    out[1] = p1.y
    out[2] = p2.x
    out[3] = p2.y
    return DynamicGuidePainter.KIND_LINE
}
