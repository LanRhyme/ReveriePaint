/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 通用 N 槽液态弹性滑块物理状态机（对齐主页底栏 `LiquidLensPhysicsState`）：
 * - 单一连续实体滑块 + 非对称起落弹簧（起跳 Q 弹，落槽柔顺无抖动）
 * - 支持任意 N 个等宽槽位的点按切槽与横向拖拽跨槽
 * - 动画全程仅在 `graphicsLayer` / `drawWithContent` 阶段读取状态，零 Compose 重组
 */
@Stable
private class SegmentedLensPhysics(initialIndex: Int, private val pressedScale: Float) {
    var curValue by mutableFloatStateOf(initialIndex.toFloat())
    var targetValue by mutableFloatStateOf(initialIndex.toFloat())
    var posVelocity by mutableFloatStateOf(0f)
    var visualVelocity by mutableFloatStateOf(0f)

    var scaleX by mutableFloatStateOf(1f)
    var scaleXVelocity by mutableFloatStateOf(0f)

    var scaleY by mutableFloatStateOf(1f)
    var scaleYVelocity by mutableFloatStateOf(0f)

    var isHolding by mutableStateOf(false)
    var isDragging by mutableStateOf(false)
    var holdPulseFrames by mutableIntStateOf(0)

    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    val liftFraction: Float
        get() {
            val denom = (pressedScale - 1f).coerceAtLeast(0.01f)
            val avgScale = (scaleX + scaleY) * 0.5f
            return ((avgScale - 1f) / denom).coerceIn(0f, 1.15f)
        }

    fun wake() {
        wakeChannel.trySend(Unit)
    }

    suspend fun awaitWake() {
        if (needsStepping()) return
        wakeChannel.receive()
    }

    fun needsStepping(): Boolean {
        return isHolding ||
            holdPulseFrames > 0 ||
            abs(targetValue - curValue) > 0.0008f ||
            abs(posVelocity) > 0.008f ||
            abs(visualVelocity) > 0.01f ||
            abs(scaleX - 1f) > 0.001f ||
            abs(scaleXVelocity) > 0.008f ||
            abs(scaleY - 1f) > 0.001f ||
            abs(scaleYVelocity) > 0.008f
    }

    fun snapAllToRest(index: Int) {
        val t = index.toFloat()
        curValue = t
        targetValue = t
        posVelocity = 0f
        visualVelocity = 0f
        scaleX = 1f
        scaleXVelocity = 0f
        scaleY = 1f
        scaleYVelocity = 0f
        isHolding = false
        isDragging = false
        holdPulseFrames = 0
    }

    fun onGestureDown(downSlot: Int, currentIndex: Int) {
        isHolding = true
        isDragging = false
        holdPulseFrames = 6
        if (downSlot != currentIndex) {
            targetValue = downSlot.toFloat()
        }
        wake()
    }

    fun onGestureDrag(dampedPos: Float) {
        isDragging = true
        targetValue = dampedPos
        wake()
    }

    fun onGestureEnd(finalTargetIndex: Int) {
        isHolding = false
        isDragging = false
        targetValue = finalTargetIndex.toFloat()
        wake()
    }

    fun onExternalChange(newIndex: Int) {
        val t = newIndex.toFloat()
        if (!isHolding && abs(targetValue - t) > 0.001f) {
            targetValue = t
            holdPulseFrames = 6
            wake()
        }
    }

    fun stepFrame(dt: Float, speed: UiAnimationSpeed, maxIndex: Int): Boolean {
        if (speed == UiAnimationSpeed.OFF) {
            snapAllToRest(targetValue.roundToInt().coerceIn(0, maxIndex))
            return true
        }

        if (holdPulseFrames > 0) {
            holdPulseFrames -= 1
        }

        val posDist = abs(targetValue - curValue)
        val inLaunchPhase = posDist > 0.35f
        val wantPressed = isHolding || holdPulseFrames > 0 || inLaunchPhase

        val scaleTarget = if (wantPressed) pressedScale else 1f
        val fast = speed == UiAnimationSpeed.FAST

        val posK = when {
            isDragging -> 1600f
            fast -> 1250f
            else -> 460f
        }
        val posC = when {
            isDragging -> 80f
            fast -> 68f
            else -> 38.5f
        }

        val scaleK: Float
        val scaleXC: Float
        val scaleYC: Float
        if (fast) {
            scaleK = 900f
            scaleXC = 52f
            scaleYC = 54f
        } else if (wantPressed) {
            scaleK = 350f
            scaleXC = 22.5f
            scaleYC = 25.4f
        } else {
            scaleK = 380f
            scaleXC = 34.3f
            scaleYC = 35.8f
        }

        val steps = ceil((dt * 240f).toDouble()).toInt().coerceIn(1, 8)
        val h = dt / steps

        var curVal = curValue
        var pVelPos = posVelocity
        var visVel = visualVelocity
        var sX = scaleX
        var sXVel = scaleXVelocity
        var sY = scaleY
        var sYVel = scaleYVelocity
        val targetVal = targetValue

        for (i in 0 until steps) {
            val posAcc = posK * (targetVal - curVal) - posC * pVelPos
            pVelPos += posAcc * h
            curVal += pVelPos * h

            val clampedVel = if (wantPressed) pVelPos.coerceIn(-10f, 10f) else (pVelPos * 0.55f).coerceIn(-6f, 6f)
            val velFollowRate = if (wantPressed) 22f else 30f
            visVel += (clampedVel - visVel) * min(1f, h * velFollowRate)

            val sxAcc = scaleK * (scaleTarget - sX) - scaleXC * sXVel
            sXVel += sxAcc * h
            sX += sXVel * h

            val syAcc = scaleK * (scaleTarget - sY) - scaleYC * sYVel
            sYVel += syAcc * h
            sY += sYVel * h
        }

        curValue = curVal
        posVelocity = pVelPos
        visualVelocity = visVel
        scaleX = sX
        scaleXVelocity = sXVel
        scaleY = sY
        scaleYVelocity = sYVel

        val settled = !wantPressed && !needsStepping()
        if (settled) {
            curValue = targetVal
            posVelocity = 0f
            visualVelocity = 0f
            scaleX = 1f
            scaleXVelocity = 0f
            scaleY = 1f
            scaleYVelocity = 0f
        }
        return settled
    }
}

/**
 * 通用横向分段选择栏（带主页同款液态弹性滑块 + 拖拽跨槽动效）。
 *
 * @param count 选项数量（>= 1）
 * @param selectedIndex 当前选中索引
 * @param onSelect 选中回调
 * @param modifier 外层容器修饰符（通常指定宽度与高度，如 `.fillMaxWidth().height(38.dp)`）
 * @param trackColor 轨道底色
 * @param pillColor 选中滑块底色
 * @param trackCornerRadius 轨道圆角
 * @param pillCornerRadius 滑块圆角
 * @param trackPadding 轨道内边距
 * @param pressedScale 按住/飞跃时的轻微放大倍数
 * @param slotContent 每个槽位的内容，`isActive` 为 `true` 时用于滑块内部高亮层，`false` 时用于未选中底轨层
 */
@Composable
fun LiquidSegmentedBar(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Theme.current.panelHi,
    pillColor: Color = Theme.current.accent,
    trackCornerRadius: Dp = 10.dp,
    pillCornerRadius: Dp = 8.dp,
    trackPadding: Dp = 3.dp,
    pressedScale: Float = 1.06f,
    slotContent: @Composable BoxScope.(index: Int, isActive: Boolean) -> Unit,
) {
    if (count <= 0) return
    val safeSelected = selectedIndex.coerceIn(0, count - 1)
    val maxIndex = (count - 1).coerceAtLeast(1)

    val view = LocalView.current
    val density = LocalDensity.current
    val colors = Theme.current
    val isDark = colors.panel.luminance() < 0.5f

    val latestSelected by rememberUpdatedState(safeSelected)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestSpeed by rememberUpdatedState(Motion.currentSpeed)
    val latestCount by rememberUpdatedState(count)

    val physics = remember(pressedScale) { SegmentedLensPhysics(safeSelected, pressedScale) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(safeSelected) {
        if (!initialized) {
            initialized = true
            physics.snapAllToRest(safeSelected)
            return@LaunchedEffect
        }
        if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            physics.snapAllToRest(safeSelected)
        } else {
            physics.onExternalChange(safeSelected)
        }
    }

    LaunchedEffect(count) {
        physics.snapAllToRest(safeSelected)
    }

    LaunchedEffect(Unit) {
        while (true) {
            physics.awaitWake()
            var lastNanos = 0L
            while (true) {
                val frameNanos = withFrameNanos { it }
                val dt = if (lastNanos == 0L) {
                    1f / 120f
                } else {
                    ((frameNanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(1f / 240f, 0.04f)
                }
                lastNanos = frameNanos
                if (physics.stepFrame(dt, latestSpeed, (latestCount - 1).coerceAtLeast(0))) {
                    break
                }
            }
        }
    }

    val insetPx = with(density) { trackPadding.toPx() }
    val pillRadiusPx = with(density) { pillCornerRadius.toPx() }
    val trackShape = RoundedCornerShape(trackCornerRadius)
    val cutoutPath = remember { Path() }
    val activeClipPath = remember { Path() }

    Box(
        modifier = modifier
            .clip(trackShape)
            .background(trackColor)
            .pointerInput(count) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = trackWidthPx
                    if (width <= 1f || count <= 0) return@awaitEachGesture
                    val slotW = width / count.toFloat()
                    val dragSlop = min(viewConfiguration.touchSlop * 0.4f, 6.dp.toPx())
                    val downLocalX = (down.position.x - insetPx).coerceIn(0f, width)
                    val downSlot = (downLocalX / slotW).toInt().coerceIn(0, count - 1)

                    var activePointerId = down.id
                    var dragging = false
                    var previewSlot = downSlot
                    var committedSlot = latestSelected
                    var dragGrabOffset = 0f
                    var upLocalX = downLocalX
                    var pointerInVerticalRange = true

                    if (downSlot != latestSelected) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    physics.onGestureDown(downSlot = downSlot, currentIndex = latestSelected)

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            var change = event.changes.firstOrNull { it.id == activePointerId }
                            if (change == null || !change.pressed) {
                                val otherPressed = event.changes.firstOrNull { it.pressed }
                                if (otherPressed != null) {
                                    activePointerId = otherPressed.id
                                    change = otherPressed
                                } else {
                                    if (change != null) {
                                        upLocalX = change.position.x - insetPx
                                        pointerInVerticalRange =
                                            change.position.y >= -48f && change.position.y <= size.height + 48f
                                    }
                                    break
                                }
                            }

                            val localX = change.position.x - insetPx
                            upLocalX = localX
                            pointerInVerticalRange =
                                change.position.y >= -48f && change.position.y <= size.height + 48f

                            val dxFromDown = localX - downLocalX
                            if (!dragging && abs(dxFromDown) > dragSlop) {
                                dragging = true
                                val activeCenterX = (downSlot + 0.5f) * slotW
                                dragGrabOffset = (downLocalX - activeCenterX).coerceIn(-slotW * 0.35f, slotW * 0.35f)
                            }

                            if (dragging) {
                                change.consume()
                                val rawPos = (localX - dragGrabOffset - slotW * 0.5f) / slotW
                                val maxF = (count - 1).toFloat()
                                val dampedPos = when {
                                    rawPos < 0f -> -(0.08f * tanh((-rawPos / 0.32f).toDouble())).toFloat()
                                    rawPos > maxF -> maxF + (0.08f * tanh(((rawPos - maxF) / 0.32f).toDouble())).toFloat()
                                    else -> rawPos
                                }
                                physics.onGestureDrag(dampedPos)

                                val newPreview = dampedPos.roundToInt().coerceIn(0, count - 1)
                                if (newPreview != previewSlot) {
                                    previewSlot = newPreview
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }

                        val finalTarget = if (dragging) {
                            previewSlot
                        } else if (pointerInVerticalRange && upLocalX >= -24f && upLocalX <= width + 24f) {
                            val tapped = (upLocalX / slotW).toInt().coerceIn(0, count - 1)
                            if (tapped != previewSlot) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            tapped
                        } else {
                            previewSlot
                        }

                        committedSlot = finalTarget
                        if (finalTarget != latestSelected) {
                            latestOnSelect(finalTarget)
                        }
                    } finally {
                        physics.onGestureEnd(committedSlot)
                    }
                }
            }
            .padding(trackPadding),
    ) {
        // 内部双层容器：共享完全一致的等宽槽位布局
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    trackWidthPx = it.width.toFloat()
                    trackHeightPx = it.height.toFloat()
                },
        ) {
            // 1. 底层未选中槽位（滑块覆盖区域精确挖空，零重影）
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (size.width <= 1f || size.height <= 1f) {
                            drawContent()
                            return@drawWithContent
                        }
                        val slotW = size.width / count.toFloat()
                        val h = size.height
                        val velocity = physics.visualVelocity / 10f
                        val vStretch = (velocity * 0.55f).coerceIn(-0.14f, 0.14f)
                        val vSquash = (velocity * 0.18f).coerceIn(-0.14f, 0.14f)
                        val curScaleX = physics.scaleX / (1f - vStretch)
                        val curScaleY = physics.scaleY * (1f - vSquash)

                        val centerX = (physics.curValue + 0.5f) * slotW
                        val centerY = h * 0.5f
                        val halfW = slotW * 0.5f * curScaleX
                        val halfH = h * 0.5f * curScaleY
                        val cr = pillRadiusPx.coerceAtMost(halfH)

                        cutoutPath.reset()
                        cutoutPath.addRoundRect(
                            RoundRect(
                                rect = Rect(
                                    left = centerX - halfW,
                                    top = centerY - halfH,
                                    right = centerX + halfW,
                                    bottom = centerY + halfH,
                                ),
                                cornerRadius = CornerRadius(cr, cr),
                            ),
                        )
                        clipPath(cutoutPath, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                for (i in 0 until count) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                role = Role.Tab
                                selected = i == safeSelected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        slotContent(i, false)
                    }
                }
            }

            // 2. 实体滑块背景（跟随物理位置与起落弹性形变，以滑块自身中心为缩放原点）
            if (trackWidthPx > 1f && trackHeightPx > 1f) {
                val slotW = trackWidthPx / count.toFloat()
                val slotWDp = with(density) { slotW.toDp() }
                val slotHDp = with(density) { trackHeightPx.toDp() }
                val highlightAlpha = if (isDark) 0.20f else 0.32f
                val pillShape = RoundedCornerShape(pillCornerRadius)

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(slotWDp)
                        .height(slotHDp)
                        .graphicsLayer {
                            val velocity = physics.visualVelocity / 10f
                            val vStretch = (velocity * 0.55f).coerceIn(-0.14f, 0.14f)
                            val vSquash = (velocity * 0.18f).coerceIn(-0.14f, 0.14f)
                            translationX = physics.curValue * slotW
                            scaleX = physics.scaleX / (1f - vStretch)
                            scaleY = physics.scaleY * (1f - vSquash)
                        }
                        .clip(pillShape)
                        .background(pillColor)
                        .border(
                            width = 0.6.dp,
                            color = Color.White.copy(alpha = highlightAlpha * (0.4f + 0.6f * physics.liftFraction.coerceIn(0f, 1f))),
                            shape = pillShape,
                        ),
                )
            }

            // 3. 顶层激活槽位文字/图标（严格裁切到滑块圆角矩形内，与底层无缝衔接）
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (size.width <= 1f || size.height <= 1f) return@drawWithContent
                        val slotW = size.width / count.toFloat()
                        val h = size.height
                        val velocity = physics.visualVelocity / 10f
                        val vStretch = (velocity * 0.55f).coerceIn(-0.14f, 0.14f)
                        val vSquash = (velocity * 0.18f).coerceIn(-0.14f, 0.14f)
                        val curScaleX = physics.scaleX / (1f - vStretch)
                        val curScaleY = physics.scaleY * (1f - vSquash)

                        val centerX = (physics.curValue + 0.5f) * slotW
                        val centerY = h * 0.5f
                        val halfW = slotW * 0.5f * curScaleX
                        val halfH = h * 0.5f * curScaleY
                        val cr = pillRadiusPx.coerceAtMost(halfH)

                        activeClipPath.reset()
                        activeClipPath.addRoundRect(
                            RoundRect(
                                rect = Rect(
                                    left = centerX - halfW,
                                    top = centerY - halfH,
                                    right = centerX + halfW,
                                    bottom = centerY + halfH,
                                ),
                                cornerRadius = CornerRadius(cr, cr),
                            ),
                        )
                        clipPath(activeClipPath, clipOp = androidx.compose.ui.graphics.ClipOp.Intersect) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                for (i in 0 until count) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        slotContent(i, true)
                    }
                }
            }
        }
    }
}
