/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale as drawScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 液态交互 Modifier 集（交互参数模型参考 Kyant0/LiquidGlass LiquidButton，纯 Compose 复刻）。
 *
 * 全部以外部 [MutableInteractionSource] 驱动，可与既有 `clickable(interactionSource = …)`
 * 组合；所有状态对象在组合期 remember 预分配，符合零分配铁律。
 *
 * 典型用法：
 * ```
 * val isr = remember { MutableInteractionSource() }
 * Box(Modifier.pressScale(isr).clickable(interactionSource = isr, indication = null) { … })
 * ```
 */

/** 按压缩放回弹（图标/chip/图层行/卡片），pressedScale < 1 压缩手感 */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier =
    composed {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = Motion.snapBouncy,
            label = "liquidPress",
        )
        scale(scale)
    }

/** 按压微放大（大按钮，LiquidButton 参数模型：lerp(1, 1+growFraction, progress)） */
fun Modifier.pressGrow(source: MutableInteractionSource, growFraction: Float = 0.04f): Modifier =
    pressScale(source, 1f + growFraction)

/**
 * 触点倾倒：按住时向触点方向饱和位移 `maxOffset * tanh(0.05 · d/maxOffset)`（不越界），
 * 松手弹回。用于工具栏大按钮。位移经 offset{} lambda 延迟读取，不触发重组。
 */
fun Modifier.liquidLean(source: MutableInteractionSource, maxOffset: Dp = 8.dp): Modifier =
    composed {
        val density = LocalDensity.current
        val maxPx = with(density) { maxOffset.toPx() }.coerceAtLeast(0.01f)
        var size by remember { mutableStateOf(IntSize.Zero) }
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }

        LaunchedEffect(source) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        val dx = interaction.pressPosition.x - size.width / 2f
                        val dy = interaction.pressPosition.y - size.height / 2f
                        val tx = (maxPx * tanh(0.07 * dx / maxPx)).toFloat()
                        val ty = (maxPx * tanh(0.07 * dy / maxPx)).toFloat()
                        coroutineScope {
                            launch { offsetX.animateTo(tx, Motion.springSnap) }
                            launch { offsetY.animateTo(ty, Motion.springSnap) }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        coroutineScope {
                            launch { offsetX.animateTo(0f, Motion.springSnap) }
                            launch { offsetY.animateTo(0f, Motion.springSnap) }
                        }
                    }
                }
            }
        }

        this
            .onSizeChanged { size = it }
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
    }

private fun liquidPressSpec(): AnimationSpec<Float> = when (Motion.currentSpeed) {
    UiAnimationSpeed.NORMAL -> tween(durationMillis = 110, easing = FastOutSlowInEasing)
    UiAnimationSpeed.FAST -> tween(durationMillis = 55, easing = FastOutSlowInEasing)
    UiAnimationSpeed.OFF -> snap()
}

private fun liquidReleaseSpec(): AnimationSpec<Float> = when (Motion.currentSpeed) {
    UiAnimationSpeed.NORMAL -> tween(durationMillis = 220, easing = FastOutSlowInEasing)
    UiAnimationSpeed.FAST -> tween(durationMillis = 90, easing = FastOutSlowInEasing)
    UiAnimationSpeed.OFF -> snap()
}

private class HighlightBrushCache {
    var brush: Brush? = null
    var center: Offset = Offset.Unspecified
    var radius: Float = -1f
    var color: Color = Color.Unspecified
    var peakAlpha: Float = -1f
}

/** 高光跟随：按压时在触点画柔和径向高光，指尖移动时高光实时跟随，松手平滑淡出。 */
fun Modifier.liquidHighlight(
    source: MutableInteractionSource,
    color: Color = Color.Unspecified,
    radius: Dp = 56.dp,
    maxAlpha: Float = 0.13f,
    drawBehindContent: Boolean = false,
): Modifier = composed {
    val density = LocalDensity.current
    val rPx = with(density) { radius.toPx() }
    val smallThresholdPx = with(density) { 48.dp.toPx() }
    val colors = Theme.current
    val isDark = colors.panel.luminance() <= 0.5f
    val effectiveColor = if (color != Color.Unspecified) color else if (isDark) Color.White else colors.text
    val peakAlpha = if (color != Color.Unspecified) maxAlpha else if (isDark) maxAlpha else maxAlpha * 0.65f

    var size by remember { mutableStateOf(IntSize.Zero) }
    var center by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(0f) }
    val brushCache = remember { HighlightBrushCache() }

    // 按压状态驱动高光淡入淡出（与 clickable 共用同一 interactionSource）
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    center = Offset(
                        interaction.pressPosition.x.coerceIn(0f, size.width.toFloat()),
                        interaction.pressPosition.y.coerceIn(0f, size.height.toFloat()),
                    )
                    progress.animateTo(1f, liquidPressSpec())
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    progress.animateTo(0f, liquidReleaseSpec())
                }
            }
        }
    }

    // 触点跟踪：只观察不消费，不影响既有手势；写 center 仅触发 draw 重绘
    pointerInput(size) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                center = Offset(
                    change.position.x.coerceIn(0f, size.width.toFloat()),
                    change.position.y.coerceIn(0f, size.height.toFloat()),
                )
            }
        }
    }

    this
        .onSizeChanged { size = it }
        .drawWithContent {
            val p = progress.value
            val drawGlow: ContentDrawScope.() -> Unit = {
                if (p > 0.01f && center != Offset.Zero && this.size.width > 0f && this.size.height > 0f) {
                    val maxDim = max(this.size.width, this.size.height)
                    val minDim = min(this.size.width, this.size.height)
                    val effectiveCenter = if (minDim <= smallThresholdPx) {
                        val cx = this.size.width * 0.5f
                        val cy = this.size.height * 0.5f
                        Offset(cx + (center.x - cx) * 0.25f, cy + (center.y - cy) * 0.25f)
                    } else {
                        center
                    }
                    val effectiveRadius = if (minDim <= smallThresholdPx) {
                        min(rPx, maxDim * (if (drawBehindContent) 0.56f else 0.72f)).coerceAtLeast(1f)
                    } else {
                        rPx.coerceAtLeast(1f)
                    }
                    val brush = if (
                        brushCache.brush != null &&
                        brushCache.center == effectiveCenter &&
                        brushCache.radius == effectiveRadius &&
                        brushCache.color == effectiveColor &&
                        brushCache.peakAlpha == peakAlpha
                    ) {
                        brushCache.brush!!
                    } else {
                        Brush.radialGradient(
                            0.0f to effectiveColor.copy(alpha = peakAlpha),
                            0.35f to effectiveColor.copy(alpha = peakAlpha * 0.50f),
                            0.70f to effectiveColor.copy(alpha = peakAlpha * 0.14f),
                            1.0f to Color.Transparent,
                            center = effectiveCenter,
                            radius = effectiveRadius,
                        ).also {
                            brushCache.brush = it
                            brushCache.center = effectiveCenter
                            brushCache.radius = effectiveRadius
                            brushCache.color = effectiveColor
                            brushCache.peakAlpha = peakAlpha
                        }
                    }
                    drawScale(scale = 0.84f + 0.16f * p, pivot = effectiveCenter) {
                        drawCircle(
                            brush = brush,
                            alpha = p,
                            radius = effectiveRadius,
                            center = effectiveCenter,
                        )
                    }
                }
            }
            if (drawBehindContent) {
                drawGlow()
                drawContent()
            } else {
                drawContent()
                drawGlow()
            }
        }
}

/**
 * 软体果冻：容器级液态变形。按下时整体朝触点倾倒 + 触点侧不等比压扁，
 * 拖动持续跟随，松手以低阻尼弹簧晃动回正（Motion.springJelly）。
 *
 * 自带 pointerInput 观察原始按下事件（不消费，不影响子元素点击），
 * 无需外部 interactionSource——用于整条底栏/整块面板这类「软体容器」。
 */
fun Modifier.liquidJelly(maxOffset: Dp = 10.dp): Modifier = composed {
    val density = LocalDensity.current
    val maxPx = with(density) { maxOffset.toPx() }.coerceAtLeast(0.01f)
    val glowR = with(density) { 90.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val leanX = remember { Animatable(0f) }
    val leanY = remember { Animatable(0f) }
    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }
    val glowAlpha = remember { Animatable(0f) }
    var glowCenter by remember { mutableStateOf(Offset.Zero) }

    pointerInput(maxPx) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            scope.launch { glowAlpha.animateTo(0.14f, Motion.springSnap) }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                if (size == IntSize.Zero) continue
                val dx = change.position.x - size.width / 2f
                val dy = change.position.y - size.height / 2f
                val nx = tanh(0.06 * dx / maxPx).toFloat()
                val ny = tanh(0.06 * dy / maxPx).toFloat()
                val tx = maxPx * nx * 1.4f          // 倾倒加强
                val ty = maxPx * ny * 1.4f
                val sxT = 1f - 0.022f * abs(ny) - 0.012f * abs(nx)
                val syT = 1f - 0.022f * abs(nx) - 0.06f * abs(ny)
                glowCenter = Offset(change.position.x, change.position.y)
                scope.launch {
                    launch { leanX.animateTo(tx, Motion.springSnap) }
                    launch { leanY.animateTo(ty, Motion.springSnap) }
                    launch { squashX.animateTo(sxT, Motion.springSnap) }
                    launch { squashY.animateTo(syT, Motion.springSnap) }
                }
            }
            // 松手：低阻尼果冻回正 + 光效淡出
            scope.launch {
                launch { leanX.animateTo(0f, Motion.springJelly) }
                launch { leanY.animateTo(0f, Motion.springJelly) }
                launch { squashX.animateTo(1f, Motion.springJelly) }
                launch { squashY.animateTo(1f, Motion.springJelly) }
            }
            scope.launch { glowAlpha.animateTo(0f, Motion.springSoft) }
        }
    }

    this
        .onSizeChanged { size = it }
        .graphicsLayer {
            translationX = leanX.value
            translationY = leanY.value
            scaleX = squashX.value
            scaleY = squashY.value
        }
        .drawWithContent {
            drawContent()
            if (glowAlpha.value > 0.01f && glowCenter != Offset.Zero) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = glowAlpha.value), Color.Transparent),
                        center = glowCenter,
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = glowCenter,
                )
            }
        }
}

/**
 * 高光扫过（specular sweep）：[trigger] 变化时（跳过首次组合）一道斜向亮带快速掠过。
 * 用于选中态切换的「光擦过」反馈。放在 clip 之后、背景之后调用。
 */
fun Modifier.liquidSheen(trigger: Any?): Modifier = composed {
    val progress = remember { Animatable(1f) } // 1f = 完成/隐藏
    var first by remember { mutableStateOf(true) }

    LaunchedEffect(trigger) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 550, easing = CubicBezierEasing(0.2f, 0f, 0.15f, 1f)))
    }

    drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 0.999f && size.width > 0f) {
            val bandX = size.width * (p * 2.2f - 0.6f)
            val half = size.width * 0.22f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.30f * (1f - p)),
                        Color.Transparent,
                    ),
                    start = Offset(bandX - half, 0f),
                    end = Offset(bandX + half, size.height.toFloat()),
                ),
            )
        }
    }
}

/**
 * 全局兜底光效 indication：替代 Material 默认涟漪。
 *
 * 按下时在触点画径向白柔光，松手淡出。由 ReverieApp 根部
 * `LocalIndication provides LiquidIndication` 统一提供——所有未显式
 * 关闭 indication 的 clickable / M3 组件自动获得光效，涟漪全局清零。
 *
 * 大面积表面（全屏 scrim 等）自动衰减亮度并限制半径，避免整屏闪光；
 * 小组件光斑半径贴合自身尺寸。Brush 按触点/尺寸惰性缓存，
 * 动画期间零分配；节点经 IndicationNodeFactory 身份相等复用。
 */
object LiquidIndication : IndicationNodeFactory {

    /** 光斑基准半径上限 */
    private const val BASE_RADIUS_DP = 140f

    override fun create(interactionSource: InteractionSource): Modifier.Node =
        LiquidIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = "LiquidIndication".hashCode()

    private class LiquidIndicationNode(
        private val source: InteractionSource,
    ) : Modifier.Node(), DrawModifierNode {

        private val progress = Animatable(0f)
        private var animJob: Job? = null
        private var center: Offset = Offset.Zero

        // Brush 缓存：仅在按压开始 / 尺寸或主题变化时重建一次
        private var cachedBrush: Brush? = null
        private var cachedSize: Size = Size.Unspecified
        private var cachedIsDark: Boolean = true
        private var cachedCenter: Offset = Offset.Unspecified
        private var builtRadius: Float = 0f

        fun animateTo(target: Float, spec: AnimationSpec<Float>) {
            animJob?.cancel()
            animJob = coroutineScope.launch { progress.animateTo(target, spec) }
        }

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> {
                            center = interaction.pressPosition
                            cachedBrush = null // 新触点 → 下一帧重建 Brush
                            animateTo(1f, liquidPressSpec())
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            animateTo(0f, liquidReleaseSpec())
                        }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val p = progress.value
            if (p <= 0.01f || size.width <= 0f || size.height <= 0f) return
            if (center == Offset.Zero) return

            val colors = Theme.current
            val isDark = colors.panel.luminance() <= 0.5f
            val baseColor = if (isDark) Color.White else colors.text
            val basePeakAlpha = if (isDark) 0.105f else 0.07f

            val maxDim = max(size.width, size.height)
            val minDim = min(size.width, size.height)
            val smallThresholdPx = with(density) { 48.dp.toPx() }
            val effectiveCenter = if (minDim <= smallThresholdPx) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                Offset(cx + (center.x - cx) * 0.25f, cy + (center.y - cy) * 0.25f)
            } else {
                center
            }

            if (
                cachedBrush == null ||
                cachedSize != size ||
                cachedIsDark != isDark ||
                cachedCenter != effectiveCenter
            ) {
                val baseRadiusPx = with(density) { BASE_RADIUS_DP.dp.toPx() }
                // 小组件：半径贴合自身；大表面：限制在触点附近
                builtRadius = min(baseRadiusPx, maxDim * 0.65f)
                    .coerceAtLeast(min(maxDim * 0.52f, with(density) { 24.dp.toPx() }))
                    .coerceAtLeast(1f)
                // 大面积表面（scrim 等）按尺寸衰减亮度，避免整屏白闪
                val alphaScale = if (maxDim > builtRadius * 3f) 0.45f else 1f
                val peakAlpha = basePeakAlpha * alphaScale
                cachedBrush = Brush.radialGradient(
                    0.0f to baseColor.copy(alpha = peakAlpha),
                    0.35f to baseColor.copy(alpha = peakAlpha * 0.50f),
                    0.70f to baseColor.copy(alpha = peakAlpha * 0.14f),
                    1.0f to Color.Transparent,
                    center = effectiveCenter,
                    radius = builtRadius,
                )
                cachedSize = size
                cachedIsDark = isDark
                cachedCenter = effectiveCenter
            }
            val brush = cachedBrush ?: return

            // 裁剪到组件边界，防止光斑溢出到相邻元素
            clipRect(right = size.width, bottom = size.height) {
                drawScale(scale = 0.84f + 0.16f * p, pivot = effectiveCenter) {
                    drawCircle(
                        brush = brush,
                        alpha = p,
                        center = effectiveCenter,
                        radius = builtRadius,
                    )
                }
            }
        }
    }
}
