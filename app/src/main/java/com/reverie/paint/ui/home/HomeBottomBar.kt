/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.graphics.BlurMaskFilter
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

internal val HOME_DOCK_HEIGHT = 48.dp
private val DOCK_TRACK_INSET = 3.dp
private val MIN_TAB_SLOT_WIDTH = 92.dp

// 按住悬浮放大倍率（突破底栏上下边界浮出）
private const val LENS_PRESSED_SCALE = 1.24f

/**
 * 外部投影（参考 dsh-Go `_GlassShadowPainter`）：
 * 使用 [ClipOp.Difference] 只在形状外部绘制柔和投影，避免半透明底栏内部被阴影弄脏发灰。
 * 预缓存 [BlurMaskFilter] 与 [Path]，绘制期零对象分配。
 */
@Composable
internal fun Modifier.externalShadow(
    shape: Shape,
    elevation: Dp,
    spotColor: Color,
    offsetY: Dp = 6.dp,
): Modifier = externalShadow(
    shape = shape,
    elevation = elevation,
    spotColorProvider = { spotColor },
    offsetY = offsetY,
)

/**
 * 支持延迟读取颜色的外部投影重载：动画期间仅在绘制阶段计算阴影透明度，零重组开销。
 */
@Composable
private fun Modifier.externalShadow(
    shape: Shape,
    elevation: Dp,
    spotColorProvider: () -> Color,
    offsetY: Dp = 6.dp,
): Modifier {
    val density = LocalDensity.current
    val blurPx = with(density) { elevation.toPx() }
    val offsetYPx = with(density) { offsetY.toPx() }
    val shadowPaint = remember(blurPx) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            if (blurPx > 0.5f) {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
    val shapePath = remember { Path() }
    var cachedSize by remember { mutableStateOf(Size.Zero) }

    return drawBehind {
        val spotColor = spotColorProvider()
        if (blurPx <= 0.5f || spotColor.alpha <= 0.001f) return@drawBehind
        if (cachedSize != size) {
            cachedSize = size
            val outline = shape.createOutline(size, layoutDirection, this)
            shapePath.reset()
            shapePath.addOutline(outline)
        }
        shadowPaint.color = spotColor.toArgb()

        clipPath(shapePath, clipOp = ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(0f, offsetYPx)
                native.drawPath(shapePath.asAndroidPath(), shadowPaint)
                native.restore()
            }
        }
    }
}

/**
 * 主页底栏磨砂表面（移除炫光/反光带，仅保留干净柔和的磨砂底与 0.5dp 极细微边 + 外部投影）：
 */
@Composable
internal fun Modifier.homeLiquidGlassSurface(
    colors: AppColors,
    shape: Shape = CircleShape,
    hazeState: HazeState? = null,
): Modifier {
    val isDark = colors.panel.luminance() < 0.5f
    val fallbackAlpha = 0.94f

    val shadowColor = if (isDark) Color.Black.copy(alpha = 0.36f) else Color.Black.copy(alpha = 0.11f)
    val rimBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.05f),
                ),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.72f),
                    Color.Black.copy(alpha = 0.08f),
                ),
            )
        }
    }

    return this
        .externalShadow(
            shape = shape,
            elevation = 16.dp,
            spotColor = shadowColor,
            offsetY = 6.dp,
        )
        .clip(shape)
        .then(
            if (hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = Glass.dockStyle(),
                )
            } else {
                Modifier.background(colors.panel.copy(alpha = fallbackAlpha))
            },
        )
        .drawWithContent {
            drawContent()
            val strokePx = 0.75.dp.toPx().coerceAtLeast(1f)
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                brush = rimBrush,
                style = Stroke(width = strokePx),
            )
        }
}

@Composable
internal fun HomeBottomBar(
    colors: AppColors,
    vm: PaintViewModel,
    selectedTab: Int,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val effectiveHaze = if (vm.blurBackground) hazeState else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiquidTabCapsule(
                colors = colors,
                selectedTab = selectedTab,
                hazeState = effectiveHaze,
                onSelectTab = { target ->
                    if (vm.homeSelectedTab != target) {
                        vm.homeSelectedTab = target
                    }
                },
            )

            DetachedCreateButton(
                colors = colors,
                onClick = { vm.goCreate() },
            )
        }
    }
}

/**
 * 永不退出的常驻物理状态机（针对"落下"顺畅度专项调优）：
 * 1. **单一连续滑块**：彻底取消"底栏静息滑块 + 顶层悬浮滑块"两层 alpha 交叉淡入淡出。
 *    之前因为 `pressProgress` 弹簧（80ms）比 `scaleX/scaleY` 弹簧（350ms）快 4 倍，落下时顶层滑块在 `1.15x` 半空中就透明消失、切回底层 `1.0x` 滑块，造成明显的落体断层。
 *    现在全程只有同一个实体滑块，所有视觉高度 `liftFraction` 直接由真实物理缩放 `(scale - 1) / (MAX - 1)` 连续映射，从 `1.24x` 一直丝滑收缩贴合到 `1.000x`。
 * 2. **抛物线斜向落位**：跨槽切页时无需等水平完全停死再垂直下落，滑过中线进入目标槽位（`posDist < 0.42f`）即同步开始柔和下落，形成自然抛物线落入槽内。
 * 3. **非对称起落弹簧**：起跳采用欠阻尼弹性（$\zeta = 0.60/0.68$）保有灵动Q弹；落下采用高柔顺近临界阻尼（$\zeta = 0.88/0.92$），像气垫一样平稳落槽，不产生缩进槽内的生硬抽搐。
 */
@Stable
private class LiquidLensPhysicsState(initialTab: Int) {
    var curValue by mutableFloatStateOf(initialTab.toFloat())
    var targetValue by mutableFloatStateOf(initialTab.toFloat())
    var posVelocity by mutableFloatStateOf(0f)
    var visualVelocity by mutableFloatStateOf(0f)

    var scaleX by mutableFloatStateOf(1f)
    var scaleXVelocity by mutableFloatStateOf(0f)

    var scaleY by mutableFloatStateOf(1f)
    var scaleYVelocity by mutableFloatStateOf(0f)

    var dragOffsetPx by mutableFloatStateOf(0f)
    var targetDragOffsetPx by mutableFloatStateOf(0f)
    var dragOffsetVelocity by mutableFloatStateOf(0f)

    var isHolding by mutableStateOf(false)
    var isDragging by mutableStateOf(false)
    var holdPulseFrames by mutableIntStateOf(0)

    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * 由真实物理尺寸直接导出的归一化悬浮高度 [0f..1f]，保证颜色、阴影、图文放大与胶囊尺寸 100% 同步落地。
     */
    val liftFraction: Float
        get() {
            val avgScale = (scaleX + scaleY) * 0.5f
            return ((avgScale - 1f) / (LENS_PRESSED_SCALE - 1f)).coerceIn(0f, 1.15f)
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
            abs(scaleYVelocity) > 0.008f ||
            abs(dragOffsetPx) > 0.15f
    }

    fun snapAllToRest(tab: Int) {
        val t = tab.toFloat()
        curValue = t
        targetValue = t
        posVelocity = 0f
        visualVelocity = 0f
        scaleX = 1f
        scaleXVelocity = 0f
        scaleY = 1f
        scaleYVelocity = 0f
        dragOffsetPx = 0f
        targetDragOffsetPx = 0f
        dragOffsetVelocity = 0f
        isHolding = false
        isDragging = false
        holdPulseFrames = 0
    }

    fun onGestureDown(downSlot: Int, currentTab: Int) {
        isHolding = true
        isDragging = false
        targetDragOffsetPx = 0f
        holdPulseFrames = 6
        if (downSlot != currentTab) {
            targetValue = downSlot.toFloat()
        }
        wake()
    }

    fun onGestureDrag(dampedPos: Float, dragDxFromDown: Float) {
        isDragging = true
        targetValue = dampedPos
        targetDragOffsetPx = dragDxFromDown
        wake()
    }

    fun onGestureEnd(finalTargetTab: Int) {
        isHolding = false
        isDragging = false
        targetValue = finalTargetTab.toFloat()
        targetDragOffsetPx = 0f
        wake()
    }

    fun onExternalTabChange(newTab: Int) {
        val t = newTab.toFloat()
        if (!isHolding && abs(targetValue - t) > 0.001f) {
            targetValue = t
            holdPulseFrames = 6
            wake()
        }
    }

    fun stepFrame(dt: Float, speed: UiAnimationSpeed): Boolean {
        if (speed == UiAnimationSpeed.OFF) {
            snapAllToRest(if (targetValue >= 0.5f) 1 else 0)
            return true
        }

        if (holdPulseFrames > 0) {
            holdPulseFrames -= 1
        }

        val posDist = abs(targetValue - curValue)
        // 跨槽飞行前半程（posDist > 0.38f）保持浮起，过中线后一边滑入目标槽位一边平滑降落，消除"半空停顿再砸下"的割裂感
        val inLaunchPhase = posDist > 0.38f
        val wantPressed = isHolding || holdPulseFrames > 0 || inLaunchPhase

        val scaleTarget = if (wantPressed) LENS_PRESSED_SCALE else 1f
        val dragOffsetTarget = if (isHolding) targetDragOffsetPx else 0f

        val fast = speed == UiAnimationSpeed.FAST
        val posK = when {
            isDragging -> 1600f
            fast -> 1200f
            else -> 420f
        }
        val posC = when {
            isDragging -> 80f
            fast -> 66f
            else -> 36.5f
        }

        // 非对称起落弹簧：起跳灵动Q弹（阻尼比 0.60/0.68），落下柔顺气垫缓冲（阻尼比 0.88/0.92，不缩进槽内抖动）
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

        val dragK = if (isHolding) 1200f else 340f
        val dragC = if (isHolding) 70f else 36f

        val steps = ceil((dt * 240f).toDouble()).toInt().coerceIn(1, 8)
        val h = dt / steps

        var curVal = curValue
        var pVelPos = posVelocity
        var visVel = visualVelocity
        var sX = scaleX
        var sXVel = scaleXVelocity
        var sY = scaleY
        var sYVel = scaleYVelocity
        var dOff = dragOffsetPx
        var dOffVel = dragOffsetVelocity
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

            val dAcc = dragK * (dragOffsetTarget - dOff) - dragC * dOffVel
            dOffVel += dAcc * h
            dOff += dOffVel * h
        }

        curValue = curVal
        posVelocity = pVelPos
        visualVelocity = visVel
        scaleX = sX
        scaleXVelocity = sXVel
        scaleY = sY
        scaleYVelocity = sYVel
        dragOffsetPx = dOff
        dragOffsetVelocity = dOffVel

        val settled = !wantPressed && !needsStepping()
        if (settled) {
            curValue = targetVal
            posVelocity = 0f
            visualVelocity = 0f
            scaleX = 1f
            scaleXVelocity = 0f
            scaleY = 1f
            scaleYVelocity = 0f
            dragOffsetPx = 0f
            dragOffsetVelocity = 0f
        }
        return settled
    }
}

/**
 * 左侧双槽导航胶囊（全程零重组 RenderNode 驱动 + 单一连续滑块丝滑起落）：
 * - 动画过程中所有物理量仅在 `graphicsLayer` 与 `drawWithContent` / `drawBehind` 阶段读取，Compose 重组次数为 0
 * - 静息态与悬浮态共用同一个实体胶囊层 [UnifiedFloatingPill]，消除双层淡入淡出造成的落下断层与闪烁
 */
@Composable
private fun LiquidTabCapsule(
    colors: AppColors,
    selectedTab: Int,
    hazeState: HazeState?,
    onSelectTab: (Int) -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val isDark = colors.panel.luminance() < 0.5f

    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestOnSelectTab by rememberUpdatedState(onSelectTab)
    val latestSpeed by rememberUpdatedState(Motion.currentSpeed)

    val physics = remember { LiquidLensPhysicsState(selectedTab) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        if (!initialized) {
            initialized = true
            physics.snapAllToRest(selectedTab)
            return@LaunchedEffect
        }
        if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            physics.snapAllToRest(selectedTab)
        } else {
            physics.onExternalTabChange(selectedTab)
        }
    }

    // 常驻单协程帧循环
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
                if (physics.stepFrame(dt, latestSpeed)) {
                    break
                }
            }
        }
    }

    val idleColor = if (isDark) colors.subText else colors.icon
    val cutoutPath = remember { Path() }
    val slotWidthPx = trackWidthPx * 0.5f
    val insetPx = with(density) { DOCK_TRACK_INSET.toPx() }
    val maxPanelShiftPx = with(density) { 4.dp.toPx() }
    val barGrowExtraPx = with(density) { 7.dp.toPx() }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = trackWidthPx
                if (width <= 1f) return@awaitEachGesture
                val slotW = width * 0.5f
                val dragSlop = min(viewConfiguration.touchSlop * 0.4f, 6.dp.toPx())
                val downLocalX = (down.position.x - insetPx).coerceIn(0f, width)
                val downSlot = if (downLocalX < slotW) 0 else 1

                var activePointerId = down.id
                var dragging = false
                var previewTab = downSlot
                var committedTab = latestSelectedTab
                var dragGrabOffset = 0f
                var upLocalX = downLocalX
                var pointerInVerticalRange = true

                if (downSlot != latestSelectedTab) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                physics.onGestureDown(downSlot = downSlot, currentTab = latestSelectedTab)

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
                                        change.position.y >= -56f && change.position.y <= size.height + 56f
                                }
                                break
                            }
                        }

                        val localX = change.position.x - insetPx
                        upLocalX = localX
                        pointerInVerticalRange =
                            change.position.y >= -56f && change.position.y <= size.height + 56f

                        val dxFromDown = localX - downLocalX
                        if (!dragging && abs(dxFromDown) > dragSlop) {
                            dragging = true
                            val activeCenterX = downSlot * slotW + slotW * 0.5f
                            dragGrabOffset =
                                (downLocalX - activeCenterX).coerceIn(-slotW * 0.35f, slotW * 0.35f)
                        }

                        if (dragging) {
                            change.consume()

                            val rawPos = (localX - dragGrabOffset - slotW * 0.5f) / slotW
                            val dampedPos = when {
                                rawPos < 0f -> -(0.08f * tanh((-rawPos / 0.32f).toDouble())).toFloat()
                                rawPos > 1f -> 1f + (0.08f * tanh(((rawPos - 1f) / 0.32f).toDouble())).toFloat()
                                else -> rawPos
                            }

                            physics.onGestureDrag(dampedPos = dampedPos, dragDxFromDown = dxFromDown)

                            val newPreview = if (dampedPos >= 0.5f) 1 else 0
                            if (newPreview != previewTab) {
                                previewTab = newPreview
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }

                    val finalTarget = if (dragging) {
                        previewTab
                    } else if (pointerInVerticalRange && upLocalX >= -24f && upLocalX <= width + 24f) {
                        val tapped = if (upLocalX < slotW) 0 else 1
                        if (tapped != previewTab) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        tapped
                    } else {
                        previewTab
                    }

                    committedTab = finalTarget
                    if (finalTarget != latestSelectedTab) {
                        latestOnSelectTab(finalTarget)
                    }
                } finally {
                    physics.onGestureEnd(committedTab)
                }
            }
        },
    ) {
        // ── 1. 底栏胶囊轨道本体（仅绘制滑块外部的未激活图文，滑块覆盖区域精确挖空，零重影） ──
        Box(
            modifier = Modifier
                .height(HOME_DOCK_HEIGHT)
                .homeLiquidGlassSurface(
                    colors = colors,
                    shape = CircleShape,
                    hazeState = hazeState,
                )
                .graphicsLayer {
                    val w = trackWidthPx
                    val panelOffset = if (w <= 1f) {
                        0f
                    } else {
                        val fraction = (physics.dragOffsetPx / w).coerceIn(-1f, 1f)
                        maxPanelShiftPx * fraction.sign * EaseOut.transform(abs(fraction))
                    }
                    translationX = panelOffset
                    val lift = physics.liftFraction.coerceIn(0f, 1f)
                    if (size.width > 0f && lift > 0.001f) {
                        val grow = lerp(1f, 1f + barGrowExtraPx / size.width, lift)
                        scaleX = grow
                        scaleY = grow
                    }
                }
                .padding(DOCK_TRACK_INSET),
            contentAlignment = Alignment.Center,
        ) {
            EqualTwoSlotRow(
                minSlotWidth = MIN_TAB_SLOT_WIDTH,
                modifier = Modifier
                    .onSizeChanged {
                        trackWidthPx = it.width.toFloat()
                        trackHeightPx = it.height.toFloat()
                    }
                    .drawWithContent {
                        if (size.width <= 1f || size.height <= 1f) {
                            drawContent()
                            return@drawWithContent
                        }
                        val slotW = size.width * 0.5f
                        val h = size.height

                        val velocity = physics.visualVelocity / 10f
                        val vStretch = (velocity * 0.70f).coerceIn(-0.16f, 0.16f)
                        val vSquash = (velocity * 0.22f).coerceIn(-0.16f, 0.16f)
                        val curScaleX = physics.scaleX / (1f - vStretch)
                        val curScaleY = physics.scaleY * (1f - vSquash)

                        val centerX = (physics.curValue + 0.5f) * slotW
                        val centerY = h * 0.5f
                        val halfW = slotW * 0.5f * curScaleX
                        val halfH = h * 0.5f * curScaleY

                        // 始终精确裁掉上方实体胶囊所占据的圆角区域，起落全程无任何开关跳变
                        cutoutPath.reset()
                        cutoutPath.addRoundRect(
                            RoundRect(
                                rect = Rect(
                                    left = centerX - halfW,
                                    top = centerY - halfH,
                                    right = centerX + halfW,
                                    bottom = centerY + halfH,
                                ),
                                cornerRadius = CornerRadius(halfH, halfH),
                            ),
                        )
                        clipPath(cutoutPath, clipOp = ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                DockTabSlot(
                    iconRes = R.drawable.ic_brush,
                    label = stringResource(R.string.gallery_title),
                    selected = selectedTab == 0,
                    iconColor = idleColor,
                    textColor = idleColor,
                    isBold = false,
                    onSemanticClick = {
                        if (selectedTab != 0) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            physics.onExternalTabChange(0)
                            onSelectTab(0)
                        }
                    },
                )

                DockTabSlot(
                    iconRes = R.drawable.ic_settings,
                    label = stringResource(R.string.settings_title),
                    selected = selectedTab == 1,
                    iconColor = idleColor,
                    textColor = idleColor,
                    isBold = false,
                    onSemanticClick = {
                        if (selectedTab != 1) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            physics.onExternalTabChange(1)
                            onSelectTab(1)
                        }
                    },
                )
            }
        }

        // ── 2. 唯一连续实体滑块（静息态 1.0x 贴合轨道，按住/切页破界浮起 1.24x，松手一气呵成收缩回 1.0x） ──
        if (slotWidthPx > 1f && trackHeightPx > 1f) {
            val slotW = slotWidthPx
            val slotH = trackHeightPx
            val slotWDp = with(density) { slotW.toDp() }
            val slotHDp = with(density) { slotH.toDp() }

            UnifiedFloatingPill(
                colors = colors,
                isDark = isDark,
                slotWDp = slotWDp,
                slotHDp = slotHDp,
                slotWidthPx = slotW,
                maxPanelShiftPx = maxPanelShiftPx,
                physics = physics,
            )
        }
    }
}

/**
 * 静息与悬浮合一的单一连续滑块：
 * - 全程 alpha = 1f，绝不在半空中透明消失，从而让 `1.24x -> 1.0x` 的落槽回弹每一帧都真实可见、连贯顺滑
 * - 所有动画属性均在 `graphicsLayer` 与 `drawBehind` 闭包内读取，动画期 0 重组
 */
@Composable
private fun UnifiedFloatingPill(
    colors: AppColors,
    isDark: Boolean,
    slotWDp: Dp,
    slotHDp: Dp,
    slotWidthPx: Float,
    maxPanelShiftPx: Float,
    physics: LiquidLensPhysicsState,
) {
    val restFill = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.96f)
    val floatFill = if (isDark) {
        lerp(colors.panelHi, Color.White, 0.12f).copy(alpha = 0.96f)
    } else {
        Color.White
    }

    val restShadowAlpha = if (isDark) 0.22f else 0.07f
    val floatShadowAlpha = if (isDark) 0.44f else 0.16f

    Box(
        modifier = Modifier
            .padding(horizontal = DOCK_TRACK_INSET)
            .size(width = slotWDp, height = slotHDp)
            .graphicsLayer {
                val velocity = physics.visualVelocity / 10f
                val vStretch = (velocity * 0.70f).coerceIn(-0.16f, 0.16f)
                val vSquash = (velocity * 0.22f).coerceIn(-0.16f, 0.16f)
                val curScaleX = physics.scaleX / (1f - vStretch)
                val curScaleY = physics.scaleY * (1f - vSquash)

                val trackW = slotWidthPx * 2f
                val panelOffset = if (trackW <= 1f) {
                    0f
                } else {
                    val fraction = (physics.dragOffsetPx / trackW).coerceIn(-1f, 1f)
                    maxPanelShiftPx * fraction.sign * EaseOut.transform(abs(fraction))
                }

                translationX = physics.curValue * slotWidthPx + panelOffset
                scaleX = curScaleX
                scaleY = curScaleY
            }
            .externalShadow(
                shape = CircleShape,
                elevation = 14.dp,
                spotColorProvider = {
                    val lift = physics.liftFraction.coerceIn(0f, 1f)
                    Color.Black.copy(alpha = lerp(restShadowAlpha, floatShadowAlpha, lift))
                },
                offsetY = 4.dp,
            )
            .clip(CircleShape)
            .drawBehind {
                val lift = physics.liftFraction.coerceIn(0f, 1f)
                val fill = lerp(restFill, floatFill, lift)
                drawRect(color = fill)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val velocity = physics.visualVelocity / 10f
                    val vStretch = (velocity * 0.70f).coerceIn(-0.16f, 0.16f)
                    val vSquash = (velocity * 0.22f).coerceIn(-0.16f, 0.16f)
                    val curScaleX = (physics.scaleX / (1f - vStretch)).coerceAtLeast(0.5f)
                    val curScaleY = (physics.scaleY * (1f - vSquash)).coerceAtLeast(0.5f)

                    val lift = physics.liftFraction.coerceIn(0f, 1.15f)
                    val contentMag = lerp(1f, 1.13f, lift)
                    scaleX = contentMag / curScaleX
                    scaleY = contentMag / curScaleY
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            EqualTwoSlotRow(
                minSlotWidth = MIN_TAB_SLOT_WIDTH,
                modifier = Modifier
                    .wrapContentWidth(unbounded = true, align = Alignment.Start)
                    .clearAndSetSemantics {}
                    .graphicsLayer {
                        translationX = -physics.curValue * slotWidthPx
                    },
            ) {
                DockTabSlot(
                    iconRes = R.drawable.ic_brush,
                    label = stringResource(R.string.gallery_title),
                    selected = false,
                    iconColor = colors.accent,
                    textColor = colors.text,
                    isBold = true,
                    onSemanticClick = {},
                )
                DockTabSlot(
                    iconRes = R.drawable.ic_settings,
                    label = stringResource(R.string.settings_title),
                    selected = false,
                    iconColor = colors.accent,
                    textColor = colors.text,
                    isBold = true,
                    onSemanticClick = {},
                )
            }
        }
    }
}

@Composable
private fun DockTabSlot(
    iconRes: Int,
    label: String,
    selected: Boolean,
    iconColor: Color,
    textColor: Color,
    isBold: Boolean,
    onSemanticClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .semantics {
                this.role = Role.Tab
                this.selected = selected
                onClick(label = label) {
                    onSemanticClick()
                    true
                }
            }
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 13.5.sp,
            fontWeight = if (isBold) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 右侧独立圆形新建画布按钮（同样采用非对称起落弹簧：起跳灵动弹出 1.14x，松手柔顺缓冲落位，全程零重组）：
 */
@Composable
private fun DetachedCreateButton(
    colors: AppColors,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isDark = colors.panel.luminance() < 0.5f
    val latestSpeed by rememberUpdatedState(Motion.currentSpeed)

    var pressProgress by remember { mutableFloatStateOf(0f) }
    var pressVelocity by remember { mutableFloatStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var holdPulseFrames by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val wakeChannel = remember { Channel<Unit>(Channel.CONFLATED) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isHolding && holdPulseFrames <= 0 && pressProgress <= 0.001f && abs(pressVelocity) <= 0.008f) {
                wakeChannel.receive()
            }
            var lastNanos = 0L
            while (true) {
                val frameNanos = withFrameNanos { it }
                val speed = latestSpeed
                if (speed == UiAnimationSpeed.OFF) {
                    pressProgress = if (isHolding) 1f else 0f
                    pressVelocity = 0f
                    holdPulseFrames = 0
                    if (!isHolding) break
                    continue
                }
                val dt = if (lastNanos == 0L) {
                    1f / 120f
                } else {
                    ((frameNanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(1f / 240f, 0.04f)
                }
                lastNanos = frameNanos

                if (holdPulseFrames > 0) holdPulseFrames -= 1
                val wantPressed = isHolding || holdPulseFrames > 0
                val target = if (wantPressed) 1f else 0f

                val fast = speed == UiAnimationSpeed.FAST
                val k = when {
                    fast -> 900f
                    wantPressed -> 340f
                    else -> 380f
                }
                val c = when {
                    fast -> 52f
                    wantPressed -> 22.1f
                    else -> 34.5f
                }

                val steps = ceil((dt * 240f).toDouble()).toInt().coerceIn(1, 8)
                val h = dt / steps
                var p = pressProgress
                var v = pressVelocity
                for (i in 0 until steps) {
                    val a = k * (target - p) - c * v
                    v += a * h
                    p += v * h
                }
                pressProgress = p.coerceAtLeast(0f)
                pressVelocity = v

                if (!wantPressed && p < 0.001f && abs(v) < 0.008f) {
                    pressProgress = 0f
                    pressVelocity = 0f
                    break
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .size(HOME_DOCK_HEIGHT)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    dragOffset = Offset.Zero
                    isHolding = true
                    holdPulseFrames = 6
                    wakeChannel.trySend(Unit)

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            dragOffset = change.position - startPos
                        }
                    } finally {
                        isHolding = false
                        dragOffset = Offset.Zero
                        wakeChannel.trySend(Unit)
                    }
                }
            }
            .graphicsLayer {
                val baseScale = lerp(1f, 1.14f, pressProgress)
                val maxOffset = size.minDimension.coerceAtLeast(1f)
                val offset = dragOffset
                translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)

                val maxDragScale = 4.dp.toPx() / size.height.coerceAtLeast(1f)
                val angle = atan2(offset.y, offset.x)
                scaleX = baseScale + maxDragScale * abs(cos(angle) * offset.x / maxOffset)
                scaleY = baseScale + maxDragScale * abs(sin(angle) * offset.y / maxOffset)
            }
            .externalShadow(
                shape = CircleShape,
                elevation = 15.dp,
                spotColorProvider = {
                    val clampedPress = pressProgress.coerceIn(0f, 1.15f)
                    if (isDark) {
                        Color.Black.copy(alpha = (0.36f + 0.16f * clampedPress).coerceIn(0f, 1f))
                    } else {
                        colors.accent.copy(alpha = (0.30f + 0.15f * clampedPress).coerceIn(0f, 1f))
                    }
                },
                offsetY = 5.dp,
            )
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.home_new_canvas),
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_plus),
            contentDescription = stringResource(R.string.home_new_canvas),
            tint = colors.onAccent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 严格等宽双槽测量布局：取两槽自然宽与 [minSlotWidth] 最大值，保证左右完全对称且与底槽同心。
 */
@Composable
private fun EqualTwoSlotRow(
    minSlotWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        require(measurables.size == 2) { "EqualTwoSlotRow expects exactly 2 slots" }
        val trackHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            (HOME_DOCK_HEIGHT - DOCK_TRACK_INSET * 2).roundToPx()
        }
        val maxHalfWidth = if (constraints.hasBoundedWidth) constraints.maxWidth / 2 else Int.MAX_VALUE
        val minSlotPx = minSlotWidth.roundToPx().coerceAtMost(maxHalfWidth)

        val looseConstraints = Constraints(
            minWidth = 0,
            maxWidth = maxHalfWidth,
            minHeight = trackHeight,
            maxHeight = trackHeight,
        )
        val probe0 = measurables[0].measure(looseConstraints)
        val probe1 = measurables[1].measure(looseConstraints)
        val slotWidth = max(minSlotPx, max(probe0.width, probe1.width)).coerceAtMost(maxHalfWidth)

        layout(slotWidth * 2, trackHeight) {
            val x0 = (slotWidth - probe0.width) / 2
            val y0 = (trackHeight - probe0.height) / 2
            val x1 = slotWidth + (slotWidth - probe1.width) / 2
            val y1 = (trackHeight - probe1.height) / 2
            probe0.placeRelative(x0, y0)
            probe1.placeRelative(x1, y1)
        }
    }
}
