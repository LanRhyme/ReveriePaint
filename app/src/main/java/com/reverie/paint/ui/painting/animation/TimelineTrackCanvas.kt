/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.FRAME_THUMB_H
import com.reverie.paint.core.FRAME_THUMB_W
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.animationRippleMoveFrame
import com.reverie.paint.core.animationRippleResizeFrame
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationToggleFrameSelection
import com.reverie.paint.core.frameThumbKey
import com.reverie.paint.core.setCurrentLayer
import com.reverie.paint.core.toggleLayerVisible
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private val TRACK_HEADER_W = 104.dp

/** 帧边缘拉伸状态: 目标图层 + 帧号 + 原始跨度 + 当前拖拽增量 (帧数) */
internal class TrimDragState(
    val layer: Int,
    val frameTime: Int,
    val origSpan: Int,
    var currentDeltaFrames: Int = 0,
)

/** 帧块拖拽态: 被拖的图层索引 + 块起始帧 */
internal class FrameDragState(val layer: Int, val fromTime: Int)

// ============================================================
// 刻度尺
// ============================================================

@Composable
internal fun TimelineRuler(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val frameW = vm.anim.frameWidthPx
    val scroll = vm.anim.scrollPx
    val count = vm.anim.length
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { off ->
                        vm.animationSeek(((scroll + off.x) / frameW).toInt())
                    },
                )
            },
    ) {
        translate(left = -scroll) {
            val first = (scroll / frameW).toInt() - 1
            val last = ((scroll + size.width) / frameW).toInt() + 1
            for (f in first..last) {
                if (f < 0 || f >= count) continue
                val x = f * frameW
                if (f % 5 == 0 || frameW > 26f) {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = f.toString(),
                        topLeft = Offset(x + 3f, 4f),
                        style = TextStyle(color = Morandi.subText, fontSize = 9.sp),
                    )
                }
                drawLine(
                    color = if (f % 5 == 0) Morandi.subText.copy(alpha = 0.7f) else Morandi.border,
                    start = Offset(x, size.height - 5f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

// ============================================================
// 轨道区 (轨道头 + 帧块 + 播放头, 可缩放平移, 支持推挤与多选)
// ============================================================

@Composable
internal fun TimelineTrackArea(
    vm: PaintViewModel,
    rowPx: Float,
    scrollY: Float,
    onScrollYChange: (Float) -> Unit,
    maxScrollY: Float = Float.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    val liveScrollY = rememberUpdatedState(scrollY)
    val liveMaxScrollY = rememberUpdatedState(maxScrollY)
    val layers = remember(vm.layers) { vm.layers.reversed() }

    val density = LocalDensity.current
    val eyePainter = painterResource(R.drawable.ic_eye)
    val eyeOffPainter = painterResource(R.drawable.ic_eye_off)
    val headerW = with(density) { TRACK_HEADER_W.toPx() }
    val headPadPx = with(density) { 6.dp.toPx() }
    val eyeZonePx = with(density) { 24.dp.toPx() }
    val eyeIconSizePx = with(density) { 17.dp.toPx() }
    val eyeBgSizePx = with(density) { 22.dp.toPx() }
    val textGapPx = with(density) { 4.dp.toPx() }
    val textX = headPadPx + eyeZonePx + textGapPx
    val textMaxW = (headerW - textX - headPadPx).coerceAtLeast(1f)

    val textMeasurer = rememberTextMeasurer()
    val nameLayouts = remember(layers, textMeasurer, density) {
        layers.associate { layer ->
            layer.index to textMeasurer.measure(
                text = layer.name,
                style = TextStyle(fontSize = 11.sp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = textMaxW.roundToInt()),
            )
        }
    }

    val liveLayers = rememberUpdatedState(layers)
    val liveRowPx = rememberUpdatedState(rowPx)
    val liveHeaderW = rememberUpdatedState(headerW)

    var frameMenu by remember { mutableStateOf<FrameMenuState?>(null) }
    var trackMenu by remember { mutableStateOf<TrackMenuState?>(null) }
    var frameDrag by remember { mutableStateOf<FrameDragState?>(null) }
    var trimDrag by remember { mutableStateOf<TrimDragState?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var canvasWpx by remember { mutableFloatStateOf(0f) }
    val liveHaptics = rememberUpdatedState(LocalHapticFeedback.current)

    val frameW = vm.anim.frameWidthPx
    val scrollX = vm.anim.scrollPx
    val currentTime = vm.anim.currentTime
    val cache = vm.anim.keyframeCache
    val thumbImages = vm.anim.frameThumbImages
    val showThumbs = vm.anim.showThumbnails
    val thumbAspect = FRAME_THUMB_W.toFloat() / FRAME_THUMB_H.toFloat()
    val selBorderPx = with(density) { 2.5.dp.toPx() }
    val selGlowPx = with(density) { 6.dp.toPx() }
    val trimHandleVisualW = with(density) { 5.dp.toPx() }
    val trimHandleRadius = with(density) { 2.5.dp.toPx() }
    val trimTouchRadius = with(density) { 22.dp.toPx() }

    val selPop = remember { Animatable(1f) }
    val selectedKey = currentTime to vm.anim.selectedTrack
    LaunchedEffect(selectedKey) {
        selPop.snapTo(0f)
        selPop.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 800f),
        )
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz ->
                    canvasWpx = sz.width.toFloat()
                    vm.anim.viewportWidthPx = sz.width.toFloat()
                }
                .pointerInput(Unit) {
                    val liveScroll = liveScrollY
                    val liveMax = liveMaxScrollY
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis

                    fun hitTest(x: Float, y: Float): Triple<Int, Int, Boolean>? {
                        val ls = liveLayers.value
                        val rpx = liveRowPx.value
                        val hw = liveHeaderW.value
                        val row = ((y + liveScroll.value) / rpx).toInt()
                        if (row !in ls.indices) return null
                        if (x < hw) return null
                        val layer = ls[row].index
                        val frame = ((vm.anim.scrollPx + x - hw) / vm.anim.frameWidthPx).toInt()
                        val times = vm.anim.keyframeCache[layer].orEmpty()
                        var hit = -1
                        for (t in times) {
                            if (t <= frame) hit = t else break
                        }
                        return if (hit >= 0) Triple(layer, hit, true) else Triple(layer, frame, false)
                    }

                    // 检查是否命中单帧右边缘的拉伸手柄
                    fun hitTestTrimHandle(x: Float, y: Float): TrimDragState? {
                        if (vm.anim.isMultiSelectMode || vm.anim.isPlaying) return null
                        val ls = liveLayers.value
                        val rpx = liveRowPx.value
                        val hw = liveHeaderW.value
                        val row = ((y + liveScroll.value) / rpx).toInt()
                        if (row !in ls.indices) return null
                        val layer = ls[row].index
                        val activeTime = vm.anim.currentTime
                        val times = vm.anim.keyframeCache[layer].orEmpty()
                        var hitStart = -1
                        var hitNext = -1
                        for (i in times.indices) {
                            val t = times[i]
                            val nxt = if (i + 1 < times.size) times[i + 1] else t + 1
                            if (activeTime in t until nxt) {
                                hitStart = t
                                hitNext = nxt
                                break
                            }
                        }
                        if (hitStart < 0) return null
                        val span = (hitNext - hitStart).coerceAtLeast(1)
                        val rightEdgeX = hw + (hitStart + span) * vm.anim.frameWidthPx - vm.anim.scrollPx
                        if (abs(x - rightEdgeX) <= trimTouchRadius) {
                            return TrimDragState(layer, hitStart, span)
                        }
                        return null
                    }

                    fun doTap(x: Float, y: Float) {
                        val ls = liveLayers.value
                        val rpx = liveRowPx.value
                        val hw = liveHeaderW.value
                        val row = ((y + liveScroll.value) / rpx).toInt()
                        if (x < hw) {
                            if (row in ls.indices) {
                                val layer = ls[row]
                                if (x < headPadPx + eyeZonePx) {
                                    vm.toggleLayerVisible(layer.index)
                                } else {
                                    vm.setCurrentLayer(layer.index)
                                }
                            }
                        } else {
                            val frame = ((vm.anim.scrollPx + x - hw) / vm.anim.frameWidthPx).toInt()
                            if (row in ls.indices) {
                                val lIdx = ls[row].index
                                vm.setCurrentLayer(lIdx)
                                vm.anim.selectedTrack = lIdx
                                if (vm.anim.isMultiSelectMode) {
                                    val times = vm.anim.keyframeCache[lIdx].orEmpty()
                                    var hit = -1
                                    for (t in times) {
                                        if (t <= frame) hit = t else break
                                    }
                                    val targetT = if (hit >= 0) hit else frame
                                    vm.animationToggleFrameSelection(targetT)
                                    vm.animationSeek(targetT)
                                    return
                                }
                            }
                            vm.animationSeek(frame)
                        }
                    }

                    awaitPointerEventScope {
                        gesture@ while (true) {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            val downPos = first.position
                            var lastCentroid = Offset.Zero
                            var lastDistance = 0f
                            var multiTouch = false
                            var axisLocked = 0
                            var travelX = 0f
                            var travelY = 0f
                            var pendingX = 0f
                            var pendingY = 0f

                            val menuWasOpen = frameMenu != null || trackMenu != null

                            // 优先检测是否直接触摸了边缘拉伸手柄
                            val potentialTrim = hitTestTrimHandle(downPos.x, downPos.y)

                            val phase: Int? = withTimeoutOrNull(longPressMs) {
                                var r = 0
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) { r = 0; break }
                                    if (pressed.size >= 2) { r = 2; break }
                                    val c = pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                    pendingX += c.positionChange().x
                                    pendingY += c.positionChange().y
                                    if (abs(pendingX) > slop || abs(pendingY) > slop) {
                                        r = 1
                                        break
                                    }
                                }
                                r
                            }

                            if (phase == 0) {
                                if (menuWasOpen) {
                                    frameMenu = null
                                    trackMenu = null
                                } else doTap(downPos.x, downPos.y)
                                continue@gesture
                            }

                            if ((phase == 1 || phase == 2) && menuWasOpen) {
                                frameMenu = null
                                trackMenu = null
                            }

                            // 边缘手柄直接拖动拉伸曝光 (无需等待长按)
                            if (phase == 1 && potentialTrim != null && abs(pendingX) > abs(pendingY)) {
                                trimDrag = potentialTrim
                                dragDx = pendingX
                                var lastHapticDelta = 0

                                while (true) {
                                    val dev = awaitPointerEvent()
                                    val pressed = dev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) {
                                        val td = trimDrag
                                        if (td != null) {
                                            val deltaF = (dragDx / vm.anim.frameWidthPx).roundToInt()
                                            val finalSpan = (td.origSpan + deltaF).coerceAtLeast(1)
                                            if (finalSpan != td.origSpan) {
                                                vm.animationRippleResizeFrame(td.layer, td.frameTime, finalSpan)
                                            }
                                        }
                                        trimDrag = null
                                        dragDx = 0f
                                        break
                                    }
                                    val c = pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                    dragDx = c.position.x - downPos.x
                                    val currentDelta = (dragDx / vm.anim.frameWidthPx).roundToInt()
                                    if (currentDelta != lastHapticDelta) {
                                        lastHapticDelta = currentDelta
                                        liveHaptics.value.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    for (cc in dev.changes) cc.consume()
                                }
                                continue@gesture
                            }

                            if (phase == null) {
                                val ls = liveLayers.value
                                val rpx = liveRowPx.value
                                val hw = liveHeaderW.value
                                val row = ((downPos.y + liveScroll.value) / rpx).toInt()

                                if (!vm.anim.isPlaying && downPos.x < hw && row in ls.indices) {
                                    val layer = ls[row]
                                    liveHaptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
                                    trackMenu = TrackMenuState(layer.index, layer.name, downPos.x, downPos.y)
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val pressed = ev.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                    }
                                    continue@gesture
                                }

                                val hit = if (!vm.anim.isPlaying) hitTest(downPos.x, downPos.y) else null
                                if (hit == null) {
                                    if (menuWasOpen) {
                                        frameMenu = null
                                        trackMenu = null
                                    }
                                } else {
                                    val (hitLayer, hitTime, hitOnBlock) = hit
                                    liveHaptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
                                    frameMenu = FrameMenuState(
                                        hitLayer, hitTime, downPos.x, downPos.y, hitOnBlock,
                                    )

                                    var b = 0
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val pressed = ev.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) { b = 0; break }
                                        if (pressed.size >= 2) { b = 2; break }
                                        val c = pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                        pendingX += c.positionChange().x
                                        pendingY += c.positionChange().y
                                        if (abs(pendingX) > slop || abs(pendingY) > slop) { b = 1; break }
                                    }
                                    if (b == 0) continue@gesture
                                    if (b == 2) {
                                        frameMenu = null
                                    } else {
                                        frameMenu = null
                                        if (hitOnBlock && !vm.anim.isMultiSelectMode) {
                                            frameDrag = FrameDragState(hitLayer, hitTime)
                                            dragDx = 0f
                                            while (true) {
                                                val dev = awaitPointerEvent()
                                                val pressed = dev.changes.filter { it.pressed }
                                                if (pressed.isEmpty()) {
                                                    val dr = frameDrag
                                                    if (dr != null) {
                                                        val from = dr.fromTime
                                                        val target = (from + (dragDx / vm.anim.frameWidthPx).roundToInt()).coerceAtLeast(0)
                                                        if (target != from) {
                                                            liveHaptics.value.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            vm.animationRippleMoveFrame(dr.layer, from, target)
                                                        }
                                                    }
                                                    frameDrag = null
                                                    dragDx = 0f
                                                    break
                                                }
                                                val c = pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                                dragDx = c.position.x - downPos.x
                                                for (cc in dev.changes) cc.consume()
                                            }
                                            continue@gesture
                                        }
                                    }
                                }
                            }

                            // 主循环: 单指滚动/平移 + 双指缩放
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                if (pressed.size >= 2) {
                                    multiTouch = true
                                    axisLocked = 0
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    val dx = p0.x - p1.x
                                    val dy = p0.y - p1.y
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                                    if (lastDistance > 0f) {
                                        val factor = (dist / lastDistance).coerceIn(0.85f, 1.15f)
                                        val oldW = vm.anim.frameWidthPx
                                        val newW = (oldW * factor).coerceIn(160f, 400f)
                                        val trackPivotX = centroid.x - liveHeaderW.value
                                        if (trackPivotX > 0f) {
                                            val frameAtPivot = (vm.anim.scrollPx + trackPivotX) / oldW
                                            vm.anim.scrollPx = (frameAtPivot * newW - trackPivotX).coerceAtLeast(0f)
                                        }
                                        vm.anim.frameWidthPx = newW

                                        val pan = centroid - lastCentroid
                                        vm.anim.scrollPx = (vm.anim.scrollPx - pan.x).coerceAtLeast(0f)
                                        val nextY = (liveScroll.value - pan.y).coerceIn(0f, liveMax.value)
                                        onScrollYChange(nextY)
                                    }
                                    lastCentroid = centroid
                                    lastDistance = dist
                                    for (c in ev.changes) c.consume()
                                    continue
                                }

                                if (multiTouch) continue

                                val c = pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                val dx = c.positionChange().x
                                val dy = c.positionChange().y
                                travelX += dx
                                travelY += dy
                                c.consume()

                                if (axisLocked == 0) {
                                    travelX += pendingX
                                    travelY += pendingY
                                    pendingX = 0f
                                    pendingY = 0f
                                    val ax = abs(travelX)
                                    val ay = abs(travelY)
                                    if (ax > slop || ay > slop) {
                                        axisLocked = if (ay > ax * 1.2f) 1 else 2
                                    }
                                }

                                if (axisLocked == 1) {
                                    val next = (liveScroll.value - travelY).coerceIn(0f, liveMax.value)
                                    onScrollYChange(next)
                                    travelY = 0f
                                } else {
                                    vm.anim.scrollPx = (vm.anim.scrollPx - travelX).coerceAtLeast(0f)
                                    travelX = 0f
                                }
                            }
                        }
                    }
                },
        ) {
            val trackW = (size.width - headerW).coerceAtLeast(0f)

            // —— 右侧轨道区 ——
            clipRect(left = headerW, top = 0f, right = size.width, bottom = size.height) {
                translate(left = headerW - scrollX, top = -scrollY) {
                    val contentH = (layers.size * rowPx).coerceAtLeast(size.height + scrollY)
                    val thumbInset = 3.dp.toPx()

                    val firstRow = (scrollY / rowPx).toInt().coerceIn(0, layers.size)
                    val lastRow = ((scrollY + size.height) / rowPx).toInt().coerceIn(0, layers.size - 1)
                    val firstFrame = (scrollX / frameW).toInt().coerceAtLeast(0)
                    val lastFrame = ((scrollX + trackW) / frameW).toInt().coerceAtLeast(0) + 1

                    for (i in firstRow..lastRow) {
                        val layer = layers[i]
                        val top = i * rowPx
                        val times = cache[layer.index]

                        if (!times.isNullOrEmpty()) {
                            var start = times.binarySearch(firstFrame)
                            if (start < 0) start = -(start + 1)
                            start = (start - 1).coerceAtLeast(0)

                            val isDraggingThisLayer = frameDrag?.layer == layer.index
                            val dragFrom = frameDrag?.fromTime ?: -1
                            val dragTarget = if (isDraggingThisLayer) {
                                (dragFrom + (dragDx / frameW).roundToInt()).coerceAtLeast(0)
                            } else -1

                            val isTrimmingThisLayer = trimDrag?.layer == layer.index
                            val trimTime = trimDrag?.frameTime ?: -1
                            val trimDelta = if (isTrimmingThisLayer) {
                                val delta = (dragDx / frameW).roundToInt()
                                val orig = trimDrag?.origSpan ?: 1
                                (orig + delta).coerceAtLeast(1) - orig
                            } else 0

                            for (idx in start until times.size) {
                                val t = times[idx]
                                if (t > lastFrame + abs(trimDelta) + 2) break

                                val next = if (idx + 1 < times.size) times[idx + 1] else t + 1
                                var span = (next - t).coerceAtLeast(1)

                                // 实时推挤位移计算
                                var visualT = t.toFloat()
                                if (isTrimmingThisLayer) {
                                    if (t == trimTime) {
                                        span = (span + trimDelta).coerceAtLeast(1)
                                    } else if (t > trimTime) {
                                        visualT += trimDelta
                                    }
                                } else if (isDraggingThisLayer && dragFrom >= 0 && dragTarget >= 0 && dragTarget != dragFrom) {
                                    if (t == dragFrom) {
                                        // 正在被拖动的块跳过本体绘制 (由 ghost 块绘制)
                                        continue
                                    }
                                    val movingSpan = (cache[layer.index]?.let { lTimes ->
                                        val mIdx = lTimes.indexOf(dragFrom)
                                        if (mIdx in lTimes.indices && mIdx + 1 < lTimes.size) lTimes[mIdx + 1] - lTimes[mIdx] else 1
                                    } ?: 1).coerceAtLeast(1)

                                    if (dragTarget > dragFrom) {
                                        if (t in (dragFrom + 1)..dragTarget) {
                                            visualT -= movingSpan
                                        }
                                    } else {
                                        if (t in dragTarget until dragFrom) {
                                            visualT += movingSpan
                                        }
                                    }
                                }

                                val active = currentTime >= t && currentTime < next
                                val isMultiSelected = vm.anim.isMultiSelectMode && vm.anim.selectedFrames.contains(t)
                                val cellX = visualT * frameW + 2f
                                val cellY = top + 4f
                                val cellW = (span * frameW - 4f).coerceAtLeast(2f)
                                val cellH = rowPx - 8f

                                // 底色
                                val baseColor = when {
                                    isMultiSelected -> Morandi.accent.copy(alpha = 0.38f)
                                    active -> Morandi.subText.copy(alpha = 0.42f)
                                    else -> Morandi.subText.copy(alpha = 0.30f)
                                }
                                drawRoundRectCompat(
                                    color = baseColor,
                                    topLeft = Offset(cellX, cellY),
                                    size = Size(cellW, cellH),
                                )

                                // 缩略图
                                val img = thumbImages[frameThumbKey(layer.index, t)]
                                if (showThumbs && img != null) {
                                    val slotW = frameW - 4f
                                    val iw = slotW - thumbInset * 2f
                                    val ih = cellH - thumbInset * 2f
                                    var tw = iw
                                    var th = tw / thumbAspect
                                    if (th > ih) {
                                        th = ih
                                        tw = th * thumbAspect
                                    }
                                    for (f in 0 until span) {
                                        val slotX = (visualT + f) * frameW + 2f
                                        drawImage(
                                            image = img,
                                            dstOffset = IntOffset(
                                                (slotX + (slotW - tw) / 2f).roundToInt(),
                                                (cellY + (cellH - th) / 2f).roundToInt(),
                                            ),
                                            dstSize = IntSize(
                                                tw.roundToInt().coerceAtLeast(1),
                                                th.roundToInt().coerceAtLeast(1),
                                            ),
                                            filterQuality = FilterQuality.Medium,
                                        )
                                    }
                                }

                                // 选中高亮
                                if (isMultiSelected) {
                                    drawRoundRect(
                                        color = Morandi.accent,
                                        topLeft = Offset(cellX, cellY),
                                        size = Size(cellW, cellH),
                                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                        style = Stroke(width = selBorderPx * 1.2f),
                                    )
                                } else if (active) {
                                    val tFactor = selPop.value
                                    val bw = selBorderPx * (0.8f + 0.6f * tFactor)
                                    val glowW = selGlowPx * (0.5f + 0.7f * tFactor)
                                    val glowA = 0.12f + 0.20f * tFactor
                                    drawRoundRect(
                                        color = Morandi.accent.copy(alpha = glowA),
                                        topLeft = Offset(cellX, cellY),
                                        size = Size(cellW, cellH),
                                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                        style = Stroke(width = glowW),
                                    )
                                    drawRoundRect(
                                        color = Morandi.accent,
                                        topLeft = Offset(cellX + bw / 2f, cellY + bw / 2f),
                                        size = Size(
                                            (cellW - bw).coerceAtLeast(1f),
                                            (cellH - bw).coerceAtLeast(1f),
                                        ),
                                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                        style = Stroke(width = bw),
                                    )

                                    // 单选状态下的右边缘拉伸手柄 (精致纤细的 5dp 胶囊把手)
                                    if (!vm.anim.isMultiSelectMode && !vm.anim.isPlaying) {
                                        val handleX = cellX + cellW - trimHandleVisualW / 2f
                                        val handleH = (cellH * 0.52f).coerceAtLeast(with(density) { 14.dp.toPx() })
                                        val handleY = cellY + (cellH - handleH) / 2f
                                        drawRoundRect(
                                            color = Morandi.accent,
                                            topLeft = Offset(handleX, handleY),
                                            size = Size(trimHandleVisualW, handleH),
                                            cornerRadius = CornerRadius(trimHandleRadius, trimHandleRadius),
                                        )
                                        drawRoundRect(
                                            color = Color.White.copy(alpha = 0.90f),
                                            topLeft = Offset(handleX + 0.5f, handleY + 0.5f),
                                            size = Size(
                                                (trimHandleVisualW - 1f).coerceAtLeast(1f),
                                                (handleH - 1f).coerceAtLeast(1f),
                                            ),
                                            cornerRadius = CornerRadius(trimHandleRadius, trimHandleRadius),
                                            style = Stroke(width = 1f),
                                        )
                                    }
                                }
                            }

                            // 实时重排避让预览: 绘制当前腾出的目标插入槽
                            if (isDraggingThisLayer && dragTarget >= 0 && dragFrom >= 0 && dragTarget != dragFrom) {
                                val movingSpan = (cache[layer.index]?.let { lTimes ->
                                    val mIdx = lTimes.indexOf(dragFrom)
                                    if (mIdx in lTimes.indices && mIdx + 1 < lTimes.size) lTimes[mIdx + 1] - lTimes[mIdx] else 1
                                } ?: 1).coerceAtLeast(1)
                                val gapX = dragTarget * frameW + 2f
                                val gapW = (movingSpan * frameW - 4f).coerceAtLeast(2f)
                                val gapY = top + 4f
                                val gapH = rowPx - 8f

                                drawRoundRect(
                                    color = Morandi.accent.copy(alpha = 0.25f),
                                    topLeft = Offset(gapX, gapY),
                                    size = Size(gapW, gapH),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                )
                                drawRoundRect(
                                    color = Morandi.accent,
                                    topLeft = Offset(gapX, gapY),
                                    size = Size(gapW, gapH),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                    style = Stroke(width = 2f),
                                )
                            }
                        }
                    }

                    val headX = currentTime * frameW + frameW / 2f
                    drawLine(
                        color = Morandi.accent,
                        start = Offset(headX, 0f),
                        end = Offset(headX, contentH),
                        strokeWidth = 2f,
                    )

                    // 拖拽跟随 ghost 块
                    frameDrag?.let { drg ->
                        val row = layers.indexOfFirst { it.index == drg.layer }
                        if (row >= 0) {
                            val t = drg.fromTime
                            val next = cache[drg.layer]?.firstOrNull { it > t } ?: (t + 1)
                            val span = (next - t).coerceAtLeast(1)
                            val gw = (span * frameW - 4f).coerceAtLeast(2f)
                            val gy = row * rowPx + 4f
                            val gh = rowPx - 8f
                            val gx = t * frameW + 2f + dragDx

                            drawRoundRect(
                                color = Morandi.panelHi.copy(alpha = 0.94f),
                                topLeft = Offset(gx, gy),
                                size = Size(gw, gh),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            )
                            drawRoundRect(
                                color = Morandi.accent,
                                topLeft = Offset(gx, gy),
                                size = Size(gw, gh),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                style = Stroke(width = selBorderPx),
                            )
                        }
                    }

                    // 边缘拉伸时的气泡提示
                    trimDrag?.let { td ->
                        val row = layers.indexOfFirst { it.index == td.layer }
                        if (row >= 0) {
                            val curSpan = (td.origSpan + (dragDx / frameW).roundToInt()).coerceAtLeast(1)
                            val tipText = "$curSpan 帧 · 1拍$curSpan"
                            val tipLayout = textMeasurer.measure(
                                text = tipText,
                                style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            )
                            val bubbleW = tipLayout.size.width + 16f
                            val bubbleH = tipLayout.size.height + 8f
                            val bx = (td.frameTime + curSpan) * frameW - bubbleW / 2f
                            val by = row * rowPx - bubbleH - 6f

                            drawRoundRect(
                                color = Morandi.panelHi.copy(alpha = 0.96f),
                                topLeft = Offset(bx, by),
                                size = Size(bubbleW, bubbleH),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            )
                            drawRoundRect(
                                color = Morandi.accent.copy(alpha = 0.8f),
                                topLeft = Offset(bx, by),
                                size = Size(bubbleW, bubbleH),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                style = Stroke(width = 1.2f),
                            )
                            drawText(
                                textLayoutResult = tipLayout,
                                topLeft = Offset(bx + 8f, by + 4f),
                            )
                        }
                    }
                }
            }

            // —— 左侧轨道头 ——
            if (layers.isNotEmpty()) {
                clipRect(left = 0f, top = 0f, right = headerW, bottom = size.height) {
                    translate(top = -scrollY) {
                        val firstRow = (scrollY / rowPx).toInt().coerceIn(0, layers.size - 1)
                        val lastRow = ((scrollY + size.height) / rowPx).toInt().coerceIn(0, layers.size - 1)
                        for (i in firstRow..lastRow) {
                            val layer = layers[i]
                            val rowCenterY = i * rowPx + rowPx / 2f

                            if (!layer.visible) {
                                drawRoundRect(
                                    color = Morandi.panel.copy(alpha = 0.7f),
                                    topLeft = Offset(
                                        headPadPx + (eyeZonePx - eyeBgSizePx) / 2f,
                                        rowCenterY - eyeBgSizePx / 2f,
                                    ),
                                    size = Size(eyeBgSizePx, eyeBgSizePx),
                                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                                )
                            }
                            val icon = if (layer.visible) eyePainter else eyeOffPainter
                            val iconTint = if (layer.visible) Morandi.icon else Morandi.subText.copy(alpha = 0.45f)
                            translate(
                                left = headPadPx + (eyeZonePx - eyeIconSizePx) / 2f,
                                top = rowCenterY - eyeIconSizePx / 2f,
                            ) {
                                with(icon) {
                                    draw(
                                        size = Size(eyeIconSizePx, eyeIconSizePx),
                                        colorFilter = ColorFilter.tint(iconTint),
                                    )
                                }
                            }

                            if (rowPx > 40f) {
                                nameLayouts[layer.index]?.let { layout ->
                                    drawText(
                                        textLayoutResult = layout,
                                        color = if (layer.index == vm.currentLayerIndex) {
                                            Morandi.accent
                                        } else {
                                            Morandi.text
                                        },
                                        topLeft = Offset(textX, rowCenterY - layout.size.height / 2f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PerfTrace.tickNanos(
                name = "timeline.draw",
                nanos = android.os.SystemClock.elapsedRealtimeNanos(),
            )
        }

        // 上下文弹出菜单
        frameMenu?.let { menu ->
            FrameMenuPopup(
                menu = menu,
                vm = vm,
                canvasWpx = canvasWpx,
                rowPx = rowPx,
                haptics = liveHaptics.value,
                onDismiss = { frameMenu = null },
            )
        }

        trackMenu?.let { menu ->
            TrackMenuPopup(
                menu = menu,
                vm = vm,
                canvasWpx = canvasWpx,
                rowPx = rowPx,
                onDismiss = { trackMenu = null },
            )
        }
    }
}
