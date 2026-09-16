/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.refreshFrameThumbs
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.abs
import kotlin.math.roundToInt

private val TRACK_HEADER_W = 104.dp
private val RULER_H = 22.dp
private val HANDLE_H = 20.dp
private val CONTROL_H = 42.dp
private const val MIN_PANEL_H = 28f
private const val MAX_PANEL_H = 460f

// 帧格固定比例 7:5 (宽:高), 不随画布变化; 与 FRAME_THUMB_W/H 一致
private const val CELL_RATIO_W = 7f
private const val CELL_RATIO_H = 5f
// 把手拖动松手时, 低于该高度吸附收成小横条
private const val BAR_SNAP_DP = 56f

/**
 * 时间轴面板 (动画画布专用)。
 *
 * 顶部把手上下拖拽调高度, 拖到底自动收起; 轨道区支持双指缩放帧宽与单指平移。
 * 支持单帧曝光右边缘拉伸手柄 (Ripple 推挤)、单帧拖拽实时重排避让预览、以及多选模式批量操作条。
 */
@Composable
internal fun AnimationTimelinePanel(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val d = density.density
    val minPx = MIN_PANEL_H * d
    val maxPx = MAX_PANEL_H * d

    // 三种形态:
    //   bar   —— 只留顶部小横条 (把手拖到最底部松手进入, 点击展开)
    //   mini  —— 把手 + 当前选中帧所在的那一行 + 操作栏 (点击收起进入)
    //   custom—— 完整面板, 高度由拖动自由调整 (默认/展开目标 = 滑块面板同高)
    var mode by remember { mutableStateOf("custom") }
    var panelHeightPx by remember { mutableFloatStateOf(240f * d) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 行高 = 帧宽 × 5/7: 帧格固定 7:5, 不随画布宽高比变化
    val rowPx = vm.anim.frameWidthPx * (CELL_RATIO_H / CELL_RATIO_W)
    val rowDp = with(density) { rowPx.toDp() }

    var trackViewportPx by remember { mutableFloatStateOf(0f) }
    val contentHeightPx = vm.layers.size * rowPx
    val maxScrollY = (contentHeightPx - trackViewportPx).coerceAtLeast(0f)

    val mediumHeightDp = with(density) {
        if (vm.railSliderPanelHeightPx > 200f) vm.railSliderPanelHeightPx.toDp() else 240.dp
    }

    LaunchedEffect(
        vm.anim.enabled,
        vm.anim.revision,
        vm.anim.thumbRevision,
        vm.anim.thumbGen,
    ) {
        vm.refreshFrameThumbs()
    }

    val vpScroll = vm.anim.scrollPx
    val vpFrameW = vm.anim.frameWidthPx
    val vpWidth = vm.anim.viewportWidthPx
    LaunchedEffect(vpScroll, vpFrameW, vpWidth) {
        kotlinx.coroutines.delay(180L)
        vm.refreshFrameThumbs()
    }

    val panelAlpha = if (vm.uiOpacity >= 0.99f) 0.90f else vm.uiOpacity

    val targetHeightDp = when (mode) {
        "bar" -> HANDLE_H
        "mini" -> HANDLE_H + rowDp + CONTROL_H
        else -> (panelHeightPx / d).dp
    }
    val panelHeight by animateDpAsState(
        targetValue = targetHeightDp,
        animationSpec = if (dragging) snap() else spring(stiffness = 400f),
        label = "timelinePanelHeight",
    )

    val useHaze = hazeState != null && !dragging

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = vm.anim.toolbarExpanded && mode == "custom",
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 2 }),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                AnimationSettingsCard(
                    vm = vm,
                    hazeState = hazeState,
                    onClose = { vm.anim.toolbarExpanded = false },
                )
            }
        }

        TimelinePanelSurface(
            vm = vm,
            hazeState = hazeState,
            panelHeight = panelHeight,
            useHaze = useHaze,
            panelAlpha = panelAlpha,
            rowDp = rowDp,
            rowPx = rowPx,
            maxScrollY = maxScrollY,
            scrollY = scrollY,
            onScrollYChange = { scrollY = it },
            onTrackViewportChange = { trackViewportPx = it },
            onToggleSettings = { vm.anim.toolbarExpanded = !vm.anim.toolbarExpanded },
            mode = mode,
            onModeChange = { mode = it },
            panelHeightPx = panelHeightPx,
            onPanelHeightPxChange = { panelHeightPx = it },
            dragging = dragging,
            onDraggingChange = { dragging = it },
            d = d,
            minPx = minPx,
            maxPx = maxPx,
            mediumHeightDp = mediumHeightDp,
        )
    }
}

/**
 * 时间轴面板本体。
 */
@Composable
private fun TimelinePanelSurface(
    vm: PaintViewModel,
    hazeState: HazeState?,
    panelHeight: Dp,
    useHaze: Boolean,
    panelAlpha: Float,
    rowDp: Dp,
    rowPx: Float,
    maxScrollY: Float,
    scrollY: Float,
    onScrollYChange: (Float) -> Unit,
    onTrackViewportChange: (Float) -> Unit = {},
    onToggleSettings: () -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    panelHeightPx: Float,
    onPanelHeightPxChange: (Float) -> Unit,
    dragging: Boolean,
    onDraggingChange: (Boolean) -> Unit,
    d: Float,
    minPx: Float,
    maxPx: Float,
    mediumHeightDp: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight)
            .then(
                if (useHaze && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.barStyle(panelAlpha))
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = panelAlpha))
                },
            ),
    ) {
        var dragStartHeightPx by remember { mutableFloatStateOf(0f) }

        TimelineHandle(
            onTap = {
                when (mode) {
                    "bar", "mini" -> {
                        onModeChange("custom")
                        onPanelHeightPxChange((mediumHeightDp.value * d).coerceIn(minPx, maxPx))
                    }
                    else -> onModeChange("mini")
                }
            },
            onDragStart = {
                onDraggingChange(true)
                val basePx = if (mode != "custom") {
                    val oldPx = when (mode) {
                        "mini" -> (HANDLE_H.value + rowDp.value + CONTROL_H.value) * d
                        else -> HANDLE_H.value * d
                    }
                    val clamped = oldPx.coerceIn(minPx, maxPx).roundToInt().toFloat()
                    onModeChange("custom")
                    onPanelHeightPxChange(clamped)
                    clamped
                } else {
                    panelHeightPx
                }
                dragStartHeightPx = basePx
            },
            onDragEnd = {
                onDraggingChange(false)
                if (panelHeightPx <= BAR_SNAP_DP * d) onModeChange("bar")
            },
            onDragCancel = { onDraggingChange(false) },
            onDrag = { totalTravelY ->
                onPanelHeightPxChange(
                    (dragStartHeightPx - totalTravelY).coerceIn(minPx, maxPx).roundToInt().toFloat(),
                )
            },
            onDoubleTap = {
                onModeChange("custom")
                onPanelHeightPxChange(((MAX_PANEL_H - 60f) * d).coerceIn(minPx, maxPx))
            },
            modifier = Modifier.fillMaxWidth().height(HANDLE_H),
        )

        when (mode) {
            "bar" -> Unit

            "mini" -> {
                val selTrack =
                    if (vm.anim.selectedTrack >= 0) vm.anim.selectedTrack else vm.currentLayerIndex
                val row = vm.layers.reversed().indexOfFirst { it.index == selTrack }
                    .coerceAtLeast(0)
                val miniScrollY = (row * rowPx).coerceIn(0f, maxScrollY)
                TimelineTrackArea(
                    vm = vm,
                    rowPx = rowPx,
                    scrollY = miniScrollY,
                    onScrollYChange = {},
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (vm.anim.isMultiSelectMode) {
                    TimelineBatchBar(vm = vm, modifier = Modifier.fillMaxWidth().height(CONTROL_H))
                } else {
                    TimelineControls(vm = vm, modifier = Modifier.fillMaxWidth().height(CONTROL_H))
                }
            }

            else -> {
                Row(modifier = Modifier.fillMaxWidth().height(RULER_H)) {
                    Spacer(modifier = Modifier.width(TRACK_HEADER_W))
                    TimelineRuler(vm = vm, modifier = Modifier.weight(1f).fillMaxSize())
                }

                TimelineTrackArea(
                    vm = vm,
                    rowPx = rowPx,
                    scrollY = scrollY,
                    onScrollYChange = onScrollYChange,
                    maxScrollY = maxScrollY,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { onTrackViewportChange(it.height.toFloat()) },
                )

                if (vm.anim.isMultiSelectMode) {
                    TimelineBatchBar(
                        vm = vm,
                        modifier = Modifier.fillMaxWidth().height(CONTROL_H),
                    )
                } else {
                    TimelineControls(
                        vm = vm,
                        modifier = Modifier.fillMaxWidth().height(CONTROL_H),
                        onToggleSettings = onToggleSettings,
                    )
                }
            }
        }
    }
}

// ============================================================
// 收放把手
// ============================================================

@Composable
private fun TimelineHandle(
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (delta: Float) -> Unit,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Morandi.subText.copy(alpha = 0.5f)),
        )
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                View(context).apply {
                    isClickable = true
                    setOnTouchListener(
                        object : View.OnTouchListener {
                            private var downRawY = 0f
                            private var lastRawY = 0f
                            private var dragging = false
                            private var lastTapMs = 0L
                            private val slop = ViewConfiguration.get(context).scaledTouchSlop

                            override fun onTouch(v: View, e: MotionEvent): Boolean {
                                when (e.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downRawY = e.rawY
                                        lastRawY = e.rawY
                                        dragging = false
                                        return true
                                    }

                                    MotionEvent.ACTION_MOVE -> {
                                        if (!dragging) {
                                            val acc = e.rawY - downRawY
                                            if (abs(acc) >= slop) {
                                                dragging = true
                                                currentOnDragStart()
                                                currentOnDrag(e.rawY - downRawY)
                                                lastRawY = e.rawY
                                            }
                                        } else {
                                            currentOnDrag(e.rawY - downRawY)
                                            lastRawY = e.rawY
                                        }
                                        return true
                                    }

                                    MotionEvent.ACTION_UP -> {
                                        if (dragging) {
                                            currentOnDragEnd()
                                        } else {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            if (now - lastTapMs < 320L) {
                                                lastTapMs = 0L
                                                currentOnDoubleTap()
                                            } else {
                                                lastTapMs = now
                                                currentOnTap()
                                            }
                                        }
                                        return true
                                    }

                                    MotionEvent.ACTION_CANCEL -> {
                                        if (dragging) currentOnDragCancel()
                                        return true
                                    }
                                }
                                return false
                            }
                        },
                    )
                }
            },
        )
    }
}
