/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationAddKeyframe
import com.reverie.paint.core.animationRemoveKeyframe
import com.reverie.paint.R
import com.reverie.paint.core.animationImportAudio
import com.reverie.paint.core.animationImportImages
import com.reverie.paint.core.animationImportVideo
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationSetFramerate
import com.reverie.paint.core.animationApplyOnionSkin
import com.reverie.paint.core.animationTogglePlay
import com.reverie.paint.core.FRAME_THUMB_H
import com.reverie.paint.core.FRAME_THUMB_W
import com.reverie.paint.core.frameThumbKey
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSectionTitle
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.core.refreshFrameThumbs
import com.reverie.paint.core.setCurrentLayer
import com.reverie.paint.core.toggleLayerVisible
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
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
private const val MIN_FRAME_W = 160f
private const val MAX_FRAME_W = 400f

// 帧格固定比例 7:5 (宽:高), 不随画布变化; 与 FRAME_THUMB_W/H 一致
private const val CELL_RATIO_W = 7f
private const val CELL_RATIO_H = 5f
// 把手拖动松手时, 低于该高度吸附收成小横条
private const val BAR_SNAP_DP = 56f

// 控制条按钮的底色不透明度 (面板本体只有一层玻璃, 不再叠内部底色)
private const val BUTTON_ALPHA = 0.70f

/**
 * 时间轴面板 (动画画布专用)。
 *
 * 顶部把手上下拖拽调高度, 拖到底自动收起; 轨道区支持双指缩放帧宽与单指平移。
 * 每条轨道 = 一个图层, 轨道上的块 = 关键帧, 块宽 = 该帧的曝光长度
 * (Krita 的 hold 语义: 一个关键帧持续到下一个关键帧)。
 *
 * 数据全部取自 vm.anim 的状态镜像; 引擎真身在 Krita 的
 * KisRasterKeyframeChannel, 这里不持有任何像素数据。
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
    // 高度以**整像素**维护 (与手势的 rawY 同为 px 域)
    var panelHeightPx by remember { mutableFloatStateOf(240f * d) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 行高 = 帧宽 × 5/7: 帧格固定 7:5, 不随画布宽高比变化
    val rowPx = vm.anim.frameWidthPx * (CELL_RATIO_H / CELL_RATIO_W)
    val rowDp = with(density) { rowPx.toDp() }
    val maxScrollY = (vm.layers.size * rowPx - rowPx * 2f).coerceAtLeast(0f)
    // "滑块面板同高"目标: ToolRail 测量回写; 未测到前退回默认 240dp
    val mediumHeightDp =
        with(density) {
            if (vm.railSliderPanelHeightPx > 200f) vm.railSliderPanelHeightPx.toDp() else 240.dp
        }

    // 面板可见期间的帧缩略图按需渲染: 视口尺寸 / 缩放平移 / 内容变化时重取。
    // refreshFrameThumbs 内部有代际缓存, 重复触发几乎零成本。
    LaunchedEffect(
        vm.anim.enabled,
        vm.anim.revision,
        vm.anim.thumbRevision,
        vm.anim.thumbGen,
        vm.anim.frameWidthPx,
        vm.anim.scrollPx,
        vm.anim.viewportWidthPx,
        vm.anim.frameThumbs,
    ) {
        android.util.Log.d(
            "AnimThumbs",
            "ui: mapSize=${vm.anim.frameThumbs.size} keys=${vm.anim.frameThumbs.keys} " +
                "layerIdx=${vm.layers.map { it.index }} cacheKeys=${vm.anim.keyframeCache.keys}",
        )
        vm.refreshFrameThumbs()
    }

    // 透明度跟随主题设置里的"顶栏与侧栏"(uiOpacity) —— 时间轴与左侧工具条
    // 融合, 必须和它用同一个设置; 满档时与 TopBar/工具条一样封顶 0.90
    val panelAlpha = if (vm.uiOpacity >= 0.99f) 0.90f else vm.uiOpacity

    // 目标高度: 拖动中即时跟手 (snap), 形态切换 / 松手吸附时用弹簧动画过渡
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

    // 拖动期间换实色: 毛玻璃对每帧高度变化重采样会闪, 松手恢复玻璃
    val useHaze = hazeState != null && !dragging

    // 外层 Column: 设置浮窗作为独立卡片悬在时间轴面板**上方** (不占面板高度)
    Column(modifier = modifier.fillMaxWidth()) {
        // 设置浮窗贴右侧: 时间轴面板右端即屏幕右侧, 与顶栏/右侧面板同一竖向
        // 轴线; 从右侧滑入也让"点齿轮 -> 右侧展开"的空间关系自明
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
 *
 * 从 [AnimationTimelinePanel] 拆出来只为让设置浮窗能作为独立兄弟节点排在上方;
 * 拖动/形态相关状态仍由父级持有 (回调上抛), 保证拖动手势的生命周期与原来一致。
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
            // 无圆角: 与左侧滑块工具条同底色平齐融合
            .then(
                if (useHaze && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.barStyle(panelAlpha))
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = panelAlpha))
                },
            ),
    ) {
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
                // 一次性冻结: 非 custom 形态先按当前显示高度切到 custom 布局,
                // 之后整段拖动只调高度。手势用屏幕绝对坐标, 布局移动不影响
                // 后续 delta, 因此这里不需要任何补偿
                if (mode != "custom") {
                    val oldPx = when (mode) {
                        "mini" -> (HANDLE_H.value + rowDp.value + CONTROL_H.value) * d
                        else -> HANDLE_H.value * d
                    }
                    onPanelHeightPxChange(oldPx.coerceIn(minPx, maxPx).roundToInt().toFloat())
                    onModeChange("custom")
                }
            },
            onDragEnd = {
                onDraggingChange(false)
                // 松手才吸附: 拖得足够扁就收成小横条
                if (panelHeightPx <= BAR_SNAP_DP * d) onModeChange("bar")
            },
            onDragCancel = { onDraggingChange(false) },
            onDrag = { fingerPx ->
                // fingerPx 是**手指在屏幕上的真实位移** (rawY 差分), 直接作用到高度;
                // 向下拖(>0) 压扁
                onPanelHeightPxChange(
                    (panelHeightPx - fingerPx).coerceIn(minPx, maxPx).roundToInt().toFloat(),
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
                // 当前选中帧所在的那一行 (跟随当前轨道, 纵向滚到该行) + 操作栏
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
                TimelineControls(vm = vm, modifier = Modifier.fillMaxWidth().height(CONTROL_H))
            }

            else -> {
                // 刻度尺固定在顶部, 不随轨道垂直滚动 (无底色, 与整块玻璃统一)
                Row(modifier = Modifier.fillMaxWidth().height(RULER_H)) {
                    Spacer(modifier = Modifier.width(TRACK_HEADER_W))
                    TimelineRuler(vm = vm, modifier = Modifier.weight(1f).fillMaxSize())
                }

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TrackHeaders(
                        vm = vm,
                        scrollY = scrollY,
                        rowHeight = rowDp,
                        modifier = Modifier.width(TRACK_HEADER_W).fillMaxSize(),
                    )
                    TimelineTrackArea(
                        vm = vm,
                        rowPx = rowPx,
                        scrollY = scrollY,
                        onScrollYChange = { onScrollYChange(it.coerceIn(0f, maxScrollY)) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }

                // 设置已改为独立浮窗 (见 AnimationSettingsCard), 不再占面板高度
                TimelineControls(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth().height(CONTROL_H),
                    onToggleSettings = onToggleSettings,
                )
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
    // pointerInput 的回调只捕获首次组合的 lambda, 统一经 rememberUpdatedState 取最新
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 视觉: 小横条
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Morandi.subText.copy(alpha = 0.5f)),
        )
        // 手势: 原生 View 覆盖整条, 用 MotionEvent.rawY (**屏幕绝对坐标**)。
        // Compose 的 PointerInputChange.position 会被布局重投影 —— 面板自己
        // 在拖动中移动时, 局部 delta 里混入自身位移, 而布局延迟随帧率/事件
        // 密度在 1~4 个事件间浮动, 任何"补偿上一事件"的做法都对不上 (实测
        // 会发散成 28dp↔460dp 的整幅振荡)。rawY 不受任何布局影响, 无需补偿。
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
                                                currentOnDrag(e.rawY - lastRawY)
                                                lastRawY = e.rawY
                                            }
                                        } else {
                                            currentOnDrag(e.rawY - lastRawY)
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

// ============================================================
// 刻度尺
// ============================================================

@Composable
private fun TimelineRuler(
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
                detectTapGestures { off ->
                    vm.animationSeek(((scroll + off.x) / frameW).toInt())
                }
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
// 轨道头 (左侧固定列)
// ============================================================

@Composable
private fun TrackHeaders(
    vm: PaintViewModel,
    scrollY: Float,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val layers = vm.layers.reversed()
    // 无底色 (与整块玻璃统一); clipToBounds: 行随 scrollY 滚动时在
    // 刻度尺下缘 / 控制条上缘处被截断, 不外溢
    Column(modifier = modifier.clipToBounds()) {
        Column(modifier = Modifier.offset { IntOffset(0, -scrollY.roundToInt()) }) {
            layers.forEach { layer ->
                Row(
                    modifier = Modifier
                        .height(rowHeight)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { vm.toggleLayerVisible(layer.index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val c = if (layer.visible) Morandi.text else Morandi.subText.copy(alpha = 0.4f)
                            drawCircle(color = c, radius = 5.dp.toPx())
                            if (!layer.visible) {
                                drawLine(
                                    color = Morandi.panel,
                                    start = Offset(center.x - 6.dp.toPx(), center.y - 6.dp.toPx()),
                                    end = Offset(center.x + 6.dp.toPx(), center.y + 6.dp.toPx()),
                                    strokeWidth = 2f,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = layer.name,
                        color = if (layer.index == vm.currentLayerIndex) Morandi.accent else Morandi.text,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { vm.setCurrentLayer(layer.index) },
                    )
                }
            }
        }
    }
}

// ============================================================
// 轨道区 (帧块 + 播放头, 可缩放平移)
// ============================================================

@Composable
private fun TimelineTrackArea(
    vm: PaintViewModel,
    rowPx: Float,
    scrollY: Float,
    onScrollYChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layers = vm.layers.reversed()
    val frameW = vm.anim.frameWidthPx
    val scrollX = vm.anim.scrollPx
    val currentTime = vm.anim.currentTime
    val cache = vm.anim.keyframeCache
    val thumbs = vm.anim.frameThumbs
    val showThumbs = vm.anim.showThumbnails
    // 缩略图位图固定 7:5 (与帧格同比例, 引擎 KeepAspectRatio 后铺满)
    val thumbAspect = FRAME_THUMB_W.toFloat() / FRAME_THUMB_H.toFloat()
    // 选中块描边: 在 composable 作用域按密度换算成 px 后带进 DrawScope,
    // 避免在每帧绘制里重复算 dp->px
    val selBorderPx = with(LocalDensity.current) { 2.5.dp.toPx() }
    val selGlowPx = with(LocalDensity.current) { 6.dp.toPx() }
    // 播放头所在帧变化时, 让高亮"弹"一下: 用一个从 0 弹回 1 的过冲动画驱动
    // 描边宽度与外发光强度。所有帧块共用同一个进度值 —— 同一时刻只有一块是
    // active, 视觉上等价于"被选中的那一块在弹", 但省掉了每块的独立动画状态。
    //
    // 用 Animatable + LaunchedEffect(selectedKey) 而不是 animateFloatAsState:
    // 后者只做"向目标值过渡", 目标值不变时不会重播; 而这里要的是"每次换帧
    // 都重播一次"的即发动画。
    val selPop = remember { Animatable(1f) }
    val selectedKey = currentTime to vm.anim.selectedTrack
    LaunchedEffect(selectedKey) {
        selPop.snapTo(0f)
        selPop.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.40f, stiffness = 1500f),
        )
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { vm.anim.viewportWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldW = vm.anim.frameWidthPx
                    val newW = (oldW * zoom).coerceIn(MIN_FRAME_W, MAX_FRAME_W)
                    // 缩放锚定在双指中心: 保持该点下的帧号不变
                    val anchorFrame = (vm.anim.scrollPx + centroid.x) / oldW
                    vm.anim.frameWidthPx = newW
                    vm.anim.scrollPx = (anchorFrame * newW - centroid.x - pan.x).coerceAtLeast(0f)
                    onScrollYChange(scrollY - pan.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frame = ((vm.anim.scrollPx + offset.x) / vm.anim.frameWidthPx).toInt()
                    val row = ((offset.y + scrollY) / rowPx).toInt()
                    if (row in layers.indices) {
                        vm.setCurrentLayer(layers[row].index)
                        vm.anim.selectedTrack = layers[row].index
                    }
                    vm.animationSeek(frame)
                }
            },
    ) {
        translate(left = -scrollX, top = -scrollY) {
            val contentH = (layers.size * rowPx).coerceAtLeast(size.height + scrollY)
            val thumbInset = 3.dp.toPx()

            layers.forEachIndexed { i, layer ->
                val top = i * rowPx

                val times = cache[layer.index]
                if (!times.isNullOrEmpty()) {
                    for (idx in times.indices) {
                        val t = times[idx]
                        // hold 语义: 一个关键帧曝光到下一关键帧之前, 连成**一块**
                        // 宽格; 但曝光期内**每一帧的画面都要显示** —— 缩略图
                        // 按帧槽逐个复制铺进块内
                        val next = if (idx + 1 < times.size) times[idx + 1] else t + 1
                        val span = (next - t).coerceAtLeast(1)
                        val active = currentTime >= t && currentTime < next
                        val cellX = t * frameW + 2f
                        val cellY = top + 4f
                        val cellW = (span * frameW - 4f).coerceAtLeast(2f)
                        val cellH = rowPx - 8f

                        // 底格: 中性灰底 (半透明, 不压过缩略图里的线条)
                        drawRoundRectCompat(
                            color = Morandi.subText.copy(alpha = 0.35f),
                            topLeft = Offset(cellX, cellY),
                            size = Size(cellW, cellH),
                        )
                        // 缩略图逐帧槽复制: 每个帧槽内按位图比例 (7:5) letterbox 居中
                        val bmp = thumbs[frameThumbKey(layer.index, t)]
                        if (showThumbs && bmp != null && !bmp.isRecycled) {
                            val slotW = frameW - 4f
                            val iw = slotW - thumbInset * 2f
                            val ih = cellH - thumbInset * 2f
                            var tw = iw
                            var th = tw / thumbAspect
                            if (th > ih) {
                                th = ih
                                tw = th * thumbAspect
                            }
                            for (f in t until next) {
                                val slotX = f * frameW + 2f
                                drawImage(
                                    image = bmp.asImageBitmap(),
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
                        // 当前曝光块: 主题色描边高亮 (不填充)。
                        // 描边压在内侧 (inset = 线宽/2) 才能保证边线不被块外裁掉。
                        // 宽度/亮度由 selProgress 驱动: 换帧瞬间过冲一下再回落,
                        // 让"选中"这个状态变化有反馈 (t 在 0.9~1.15 之间摆动一次)
                        if (active) {
                            val t = selPop.value
                            val bw = selBorderPx * (0.8f + 0.6f * t)
                            val glowW = selGlowPx * (0.5f + 0.7f * t)
                            val glowA = 0.12f + 0.20f * t
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
                        }
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
        }
    }
}

// ============================================================
// 底部控制条
// ============================================================

@Composable
private fun TimelineControls(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    onToggleSettings: () -> Unit = { vm.anim.toolbarExpanded = !vm.anim.toolbarExpanded },
) {
    val playing = vm.anim.isPlaying
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(onClick = { vm.animationSeek((vm.anim.currentTime - 1).coerceAtLeast(0)) }) { drawGlyphPrev() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationTogglePlay() }) {
            if (playing) drawGlyphPause() else drawGlyphPlay()
        }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationSeek(vm.anim.currentTime + 1) }) { drawGlyphNext() }
        Spacer(modifier = Modifier.width(14.dp))
        GlyphButton(onClick = { vm.animationAddKeyframe(duplicate = false) }) { drawGlyphPlus() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationAddKeyframe(duplicate = true) }) { drawGlyphDuplicate() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphButton(onClick = { vm.animationRemoveKeyframe() }) { drawGlyphTrash() }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "${vm.anim.framerate}fps · ${vm.anim.length}帧 · 第 ${vm.anim.currentTime + 1} 帧",
            color = Morandi.subText,
            fontSize = 10.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        GlyphButton(onClick = onToggleSettings) {
            if (vm.anim.toolbarExpanded) drawGlyphClose() else drawGlyphSettings()
        }
    }
}

// ============================================================
// 设置面板 (帧率 / 洋葱皮 / 缩略图)
// ============================================================

/** 动画设置浮窗: 独立卡片悬在时间轴面板上方 (不占面板高度, 不挤压轨道区) */
@Composable
private fun AnimationSettingsCard(
    vm: PaintViewModel,
    hazeState: HazeState?,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val alpha = vm.popupPanelOpacity
    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .shadow(16.dp, shape, spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape)
            .then(
                if (vm.blurBackground && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(alpha))
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = alpha))
                },
            )
            .glassBorder(shape)
            .width(320.dp)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "动画设置",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            ReIconButton(
                icon = R.drawable.ic_x,
                desc = "关闭设置",
                onTap = onClose,
                size = 30.dp,
                iconSize = 15.dp,
            )
        }
        TimelineSettings(vm = vm)
    }
}

@Composable
private fun TimelineSettings(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 4.dp)) {
        // 帧率输入弹窗的开关, 由下面"帧率"一行里的数值文案触发
        var showFpsInput by remember { mutableStateOf(false) }

        ReSectionTitle(text = "播放", modifier = Modifier.padding(start = 12.dp))

        // 帧率: [−] 数值 [+] 三件套, 数值本身可点 —— 点开数字键盘直接输入,
        // 长按 ± 的逐点微调适合小改, 直接输入适合 12 -> 24 这类大跨度调整
        CompactSettingRow(label = "帧率") {
            ReIconButton(
                icon = R.drawable.ic_minus,
                desc = "降低帧率",
                onTap = { vm.animationSetFramerate(vm.anim.framerate - 1) },
                size = 28.dp,
                iconSize = 14.dp,
            )
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { showFpsInput = true }
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${vm.anim.framerate} fps",
                    color = Morandi.text,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            ReIconButton(
                icon = R.drawable.ic_plus,
                desc = "提高帧率",
                onTap = { vm.animationSetFramerate(vm.anim.framerate + 1) },
                size = 28.dp,
                iconSize = 14.dp,
            )
        }

        if (showFpsInput) {
            FpsInputDialog(
                initial = vm.anim.framerate,
                onDismiss = { showFpsInput = false },
                onConfirm = {
                    vm.animationSetFramerate(it)
                    showFpsInput = false
                },
            )
        }

        ReSectionTitle(text = "洋葱皮", modifier = Modifier.padding(start = 12.dp))

        CompactSettingRow(label = "显示洋葱皮") {
            ReSwitch(
                checked = vm.anim.onionSkin,
                onChecked = {
                    vm.anim.onionSkin = it
                    vm.animationApplyOnionSkin()
                },
            )
        }

        // 参数只在开启时展开, 免得收起状态也占满屏
        if (vm.anim.onionSkin) {
            CompactSettingRow(label = "前 / 后帧数") {
                OnionFrameStepper(
                    prefix = "前",
                    value = vm.anim.onionPrev,
                    onChange = {
                        vm.anim.onionPrev = it
                        vm.animationApplyOnionSkin()
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                OnionFrameStepper(
                    prefix = "后",
                    value = vm.anim.onionNext,
                    onChange = {
                        vm.anim.onionNext = it
                        vm.animationApplyOnionSkin()
                    },
                )
            }

            CompactSettingRow(label = "不透明度") {
                val pct = (vm.anim.onionOpacity * 100 + 127) / 255
                ReSlider(
                    value = vm.anim.onionOpacity / 255f,
                    onValue = { vm.anim.onionOpacity = (it * 255f).roundToInt().coerceIn(0, 255) },
                    onRelease = { vm.animationApplyOnionSkin() },
                    modifier = Modifier.width(150.dp),
                    height = 18,
                )
                Text(
                    text = "$pct%",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }

            CompactSettingRow(label = "着色强度") {
                ReSlider(
                    value = vm.anim.onionTint / 100f,
                    onValue = { vm.anim.onionTint = (it * 100f).roundToInt().coerceIn(0, 100) },
                    onRelease = { vm.animationApplyOnionSkin() },
                    modifier = Modifier.width(150.dp),
                    height = 18,
                )
                Text(
                    text = "${vm.anim.onionTint}",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
        }

        ReSectionTitle(text = "显示", modifier = Modifier.padding(start = 12.dp))

        CompactSettingRow(label = "帧缩略图") {
            ReSwitch(
                checked = vm.anim.showThumbnails,
                onChecked = { vm.anim.showThumbnails = it },
            )
        }

        if (vm.anim.audioAssets.isNotEmpty()) {
            CompactSettingRow(label = "音频") {
                Text(
                    text = "${vm.anim.audioAssets.size} 条 · 随播放",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
            }
        }

        ReSectionTitle(text = "导入", modifier = Modifier.padding(start = 12.dp))
        TimelineImportRow(vm = vm)
    }
}

/** 设置面板的单行 (沿用 ReSettingRow 的语言, 行高更紧凑以适应时间轴面板) */
@Composable
private fun CompactSettingRow(
    label: String,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Morandi.text, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/**
 * 帧率输入弹窗: 调起数字键盘直接输入。
 *
 * 输入框拿到焦点即自动弹出键盘 (TextFieldValue 初值带选区, 用户输入即覆盖),
 * 因此不需要用户再点一次输入框; 未点确定就关掉则不改动帧率。
 */
@Composable
private fun FpsInputDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("帧率", color = Morandi.text, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        // 只接受数字, 且把长度卡在 3 位 (240 fps 已是上限)
                        text = raw.filter { it.isDigit() }.take(3)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Morandi.border,
                            focusedContainerColor = Morandi.panel,
                            unfocusedContainerColor = Morandi.panel,
                            cursorColor = Morandi.accent,
                            focusedTextColor = Morandi.text,
                            unfocusedTextColor = Morandi.text,
                        ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "可输入 1 ~ 240, 每秒帧数",
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            ReTextButton(
                "确定",
                onClick = {
                    val v = text.toIntOrNull() ?: initial
                    onConfirm(v.coerceIn(1, 240))
                },
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** 洋葱皮前后帧数: 前缀 + [−] 值 [+], 紧凑排布省横向空间 */
@Composable
private fun OnionFrameStepper(
    prefix: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = prefix, color = Morandi.subText, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(2.dp))
        ReIconButton(
            icon = R.drawable.ic_minus,
            desc = "减少",
            onTap = { onChange((value - 1).coerceIn(0, 10)) },
            size = 26.dp,
            iconSize = 13.dp,
        )
        Text(
            text = "$value",
            color = Morandi.text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(18.dp),
        )
        ReIconButton(
            icon = R.drawable.ic_plus,
            desc = "增加",
            onTap = { onChange((value + 1).coerceIn(0, 10)) },
            size = 26.dp,
            iconSize = 13.dp,
        )
    }
}

@Composable
private fun TimelineImportRow(vm: PaintViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var importing by remember { mutableStateOf(false) }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            vm.animationImportImages(uris) {
                importing = false
            }
        }
    }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            importing = true
            vm.animationImportVideo(uri, vm.anim.framerate) {
                importing = false
            }
        }
    }
    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val ext = context.contentResolver.getType(uri)
                ?.substringAfter('/')?.takeIf { it.length <= 5 } ?: "bin"
            vm.animationImportAudio(uri, "audio_${System.currentTimeMillis()}.$ext")
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReChip(
            text = "图像帧",
            onTap = { if (!importing) imagePicker.launch(arrayOf("image/*")) },
        )
        Spacer(modifier = Modifier.width(8.dp))
        ReChip(
            text = "视频",
            onTap = { if (!importing) videoPicker.launch("video/*") },
        )
        Spacer(modifier = Modifier.width(8.dp))
        ReChip(
            text = "音频",
            onTap = { if (!importing) audioPicker.launch("audio/*") },
        )
        if (importing) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "导入中…",
                color = Morandi.subText,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ============================================================
// 图标 (自绘, 避免引入图标库依赖)
// ============================================================

/** 控制条上自绘图标的小圆角按钮 */
@Composable
private fun GlyphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panelHi.copy(alpha = BUTTON_ALPHA))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp), onDraw = draw)
    }
}

private fun DrawScope.drawGlyphPlay() {
    val p = Path().apply {
        moveTo(size.width * 0.22f, size.height * 0.12f)
        lineTo(size.width * 0.86f, size.height * 0.5f)
        lineTo(size.width * 0.22f, size.height * 0.88f)
        close()
    }
    drawPath(p, Morandi.text)
}

private fun DrawScope.drawGlyphPause() {
    val w = size.width * 0.18f
    drawRect(Morandi.text, Offset(size.width * 0.26f, size.height * 0.16f), Size(w, size.height * 0.68f))
    drawRect(Morandi.text, Offset(size.width * 0.56f, size.height * 0.16f), Size(w, size.height * 0.68f))
}

private fun DrawScope.drawGlyphPrev() {
    val h = size.height
    drawLine(Morandi.text, Offset(size.width * 0.18f, h * 0.2f), Offset(size.width * 0.18f, h * 0.8f), strokeWidth = 2f)
    val p = Path().apply {
        moveTo(size.width * 0.82f, h * 0.16f)
        lineTo(size.width * 0.34f, h * 0.5f)
        lineTo(size.width * 0.82f, h * 0.84f)
        close()
    }
    drawPath(p, Morandi.text)
}

private fun DrawScope.drawGlyphNext() {
    val h = size.height
    drawLine(Morandi.text, Offset(size.width * 0.82f, h * 0.2f), Offset(size.width * 0.82f, h * 0.8f), strokeWidth = 2f)
    val p = Path().apply {
        moveTo(size.width * 0.18f, h * 0.16f)
        lineTo(size.width * 0.66f, h * 0.5f)
        lineTo(size.width * 0.18f, h * 0.84f)
        close()
    }
    drawPath(p, Morandi.text)
}

private fun DrawScope.drawGlyphPlus() {
    drawLine(Morandi.text, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth = 2f)
}

private fun DrawScope.drawGlyphMinus() {
    drawLine(Morandi.text, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth = 2f)
}

// 设置 (三条滑杆)
private fun DrawScope.drawGlyphSettings() {
    val knob = Size(size.height * 0.22f, size.height * 0.22f)
    val rows = listOf(0.24f, 0.5f, 0.76f)
    val knobs = listOf(0.62f, 0.34f, 0.7f)
    rows.forEachIndexed { i, y ->
        val cy = size.height * y
        drawLine(Morandi.text, Offset(size.width * 0.15f, cy), Offset(size.width * 0.85f, cy), strokeWidth = 1.6f)
        val kx = size.width * knobs[i] - knob.width / 2f
        drawRoundRect(Morandi.text, Offset(kx, cy - knob.height / 2f), knob, CornerRadius(knob.width / 2f))
    }
}

// 关闭 (X)
private fun DrawScope.drawGlyphClose() {
    drawLine(Morandi.text, Offset(size.width * 0.22f, size.height * 0.22f), Offset(size.width * 0.78f, size.height * 0.78f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.78f, size.height * 0.22f), Offset(size.width * 0.22f, size.height * 0.78f), strokeWidth = 2f)
}

private fun DrawScope.drawGlyphDuplicate() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.08f, size.height * 0.22f), Size(size.width * 0.58f, size.height * 0.56f))
    drawRoundRectCompat(
        Morandi.text.copy(alpha = 0.45f),
        Offset(size.width * 0.34f, size.height * 0.22f),
        Size(size.width * 0.58f, size.height * 0.56f),
    )
}

private fun DrawScope.drawGlyphTrash() {
    drawRoundRectCompat(Morandi.text, Offset(size.width * 0.16f, size.height * 0.62f), Size(size.width * 0.68f, size.height * 0.24f))
    drawLine(Morandi.text, Offset(size.width * 0.1f, size.height * 0.28f), Offset(size.width * 0.9f, size.height * 0.28f), strokeWidth = 2f)
    drawLine(Morandi.text, Offset(size.width * 0.36f, size.height * 0.12f), Offset(size.width * 0.64f, size.height * 0.12f), strokeWidth = 2f)
}

private fun DrawScope.drawRoundRectCompat(
    color: Color,
    topLeft: Offset,
    size: Size,
) {
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
    )
}
