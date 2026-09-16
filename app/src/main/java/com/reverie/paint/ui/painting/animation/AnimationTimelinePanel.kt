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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.animationAddBlankKeyframeAt
import com.reverie.paint.core.animationAddKeyframe
import com.reverie.paint.core.animationCopyCurrentFrameTo
import com.reverie.paint.core.animationMoveKeyframe
import com.reverie.paint.core.animationRemoveKeyframe
import com.reverie.paint.R
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.reverie.paint.core.animationImportAudio
import com.reverie.paint.core.animationImportImages
import com.reverie.paint.core.animationImportVideo
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationSetFramerate
import com.reverie.paint.core.animationApplyOnionSkin
import com.reverie.paint.core.animationTogglePlay
import com.reverie.paint.core.animationToggleLoop
import com.reverie.paint.core.copyLayer
import com.reverie.paint.core.clearLayer
import com.reverie.paint.core.removeLayer
import com.reverie.paint.core.FRAME_THUMB_H
import com.reverie.paint.core.FRAME_THUMB_W
import com.reverie.paint.ui.painting.layers.CompactColorPickerDialog
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

/** 长按菜单命中态: 命中的图层索引 + 块起始帧 (空白格则该格帧号) + 弹出锚点 (Canvas 局部 px) */
private class FrameMenuState(
    val layer: Int,
    val time: Int,
    val anchorX: Float,
    val anchorY: Float,
    // true = 长按命中已有帧块 (全量菜单+可拖拽); false = 长按空白格 (仅新建帧/粘贴)
    val onBlock: Boolean,
)

/** 轨道头长按菜单态: 图层索引 + 图层名称 + 锚点坐标 */
private class TrackMenuState(
    val layerIndex: Int,
    val layerName: String,
    val anchorX: Float,
    val anchorY: Float,
)

/** 帧块拖拽态: 被拖的图层索引 + 块起始帧 (ghost 偏移单独放 dragDx 状态) */
private class FrameDragState(val layer: Int, val fromTime: Int)

// 拖拽目标格描边: 可落 = 绿 (空槽), 占用 = 红 (拒绝, 引擎 moveKeyframe 的
// "目标有帧先删"是覆盖语义, 静默毁数据, 必须在 UI 层挡住)
private val DragTargetOk = Color(0xFF7FA96B)
private val DragTargetBad = Color(0xFFC96A5A)

/**
 * 帧剪贴板 (Kotlin 层, 记录"源轨道 + 源帧号"; 像素不搬家, 粘贴时引擎从源帧
 * 现做独立副本 —— 源帧被删则 keyframeCache 里查不到, 粘贴项自动隐藏)。
 * 应用级单实例 VM, top-level 变量即全局单份。
 */
private var frameClipboard: Pair<Int, Int>? = null

/**
 * 时间轴面板 (动画画布专用)。
 *
 * 顶部把手上下拖拽调高度, 拖到底自动收起; 轨道区支持双指缩放帧宽与单指平移。
 * 长按帧块弹上下文菜单 (复制帧/删除帧), 长按后横向拖动可直接搬移帧块 (只落空槽)。
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
    // 轨道区**实际视口高度** (px), 由 TimelineTrackArea 测量回写。
    //
    // 为什么必须有这个: 上一版 maxScrollY 写成 `layers*rowPx - 2*rowPx`,
    // 那个 "2 行" 是拍脑袋的常数, 与视口真实高度无关 ——
    //   3 轨时 maxScrollY = 114px, 而视口有 410px: 内容比视口还矮,
    //   本就不该能滚, 却允许滚动一个行高 (画面跟着动, 像是"动了但不对");
    //   8 轨时 maxScrollY = 686px, 视口 410px, 正确上限是 914-410 = 504px,
    //   于是能滚出 182px 的空白。
    // 正确公式只有一个: maxScroll = max(0, 内容高 - 视口高)。
    var trackViewportPx by remember { mutableFloatStateOf(0f) }
    val contentHeightPx = vm.layers.size * rowPx
    val maxScrollY = (contentHeightPx - trackViewportPx).coerceAtLeast(0f)
    // "滑块面板同高"目标: ToolRail 测量回写; 未测到前退回默认 240dp
    val mediumHeightDp =
        with(density) {
            if (vm.railSliderPanelHeightPx > 200f) vm.railSliderPanelHeightPx.toDp() else 240.dp
        }

    // 面板可见期间的帧缩略图按需渲染。
    //
    // 关键: **视口参数 (scrollPx / frameWidthPx / viewportWidthPx) 不能作为
    // LaunchedEffect 的 key**。它们在手势期间每帧都变, 每个像素都会:
    //   重启 effect -> 扫一遍 keyframeCache -> runCore 投递到渲染线程 ->
    //   回写 anim.frameThumbs -> 触发整块面板重组 -> ...
    // 而 frameThumbs 自己又出现在 key 列表里, 形成"手势 <-> 合成"的抖动回路。
    // 三层轨道时就能明显感觉到交互发涩。
    //
    // 现在改成: 只有**结构性变化** (开关 / 关键帧增删 / 内容代际) 立即触发;
    // 视口变化走独立的防抖通道, 手势停下来之后才真正去补缩略图。
    LaunchedEffect(
        vm.anim.enabled,
        vm.anim.revision,
        vm.anim.thumbRevision,
        vm.anim.thumbGen,
    ) {
        vm.refreshFrameThumbs()
    }

    // 视口变化 -> 防抖补渲染。快照成局部值再进 effect, 避免在 effect 内部
    // 读取这些 state 又把它们变回依赖 (那等于没去掉 key)。
    val vpScroll = vm.anim.scrollPx
    val vpFrameW = vm.anim.frameWidthPx
    val vpWidth = vm.anim.viewportWidthPx
    LaunchedEffect(vpScroll, vpFrameW, vpWidth) {
        // 手势期间 (还在缩放/平移) 不渲染: 每帧都去补一遍是纯浪费, 而且
        // 回写的 frameThumbs 会打断手势的流畅度。停手 180ms 后再补。
        kotlinx.coroutines.delay(180L)
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
            // 无圆角: 与左侧滑块工具条同底色平齐融合
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
                // 拖动启动时记录起始高度: 解决高刷下 Compose 状态未能即时重组导致增量丢失问题
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
                // 松手才吸附: 拖得足够扁就收成小横条
                if (panelHeightPx <= BAR_SNAP_DP * d) onModeChange("bar")
            },
            onDragCancel = { onDraggingChange(false) },
            onDrag = { totalTravelY ->
                // totalTravelY 是手指自按下起的垂直绝对位移 (rawY - downRawY), 严格 1:1 跟手
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

                // 轨道头不再用独立的 Compose Column 渲染 —— 已并入
                // TimelineTrackArea 的同一块 Canvas (内部左侧裁剪区)。
                // 此前 "Column 布局 + offset 整数滚动" 与 "Canvas float 坐标
                // 滚动" 是两套必须像素级一致的独立系统, 实测恒定偏差 ~40px、
                // 部分行无标签; 合并后同一坐标系 + 同一 scrollY,
                // "行与轨道对不上"在结构上不可能再发生。
                TimelineTrackArea(
                    vm = vm,
                    rowPx = rowPx,
                    scrollY = scrollY,
                    // 钳位改在 TimelineTrackArea 内部用 liveMaxScrollY 做,
                    // 这里直接透传 —— 外层 lambda 捕获的 maxScrollY 同样是
                    // 组合期快照, 不能作为唯一的钳位依据。
                    onScrollYChange = onScrollYChange,
                    maxScrollY = maxScrollY,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 量轨道区的真实视口高 (px), 回写给 maxScrollY 用
                        .onSizeChanged { onTrackViewportChange(it.height.toFloat()) },
                )

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
                // 同样必须显式 onTap = : 尾 lambda 会被绑到 onDoubleTap。
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
// 轨道区 (轨道头 + 帧块 + 播放头, 可缩放平移)
// ============================================================

@Composable
private fun TimelineTrackArea(
    vm: PaintViewModel,
    rowPx: Float,
    scrollY: Float,
    onScrollYChange: (Float) -> Unit,
    maxScrollY: Float = Float.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    // ★ 手势必须读"活"的 scrollY / maxScrollY。
    //
    // scrollY 是**普通 Float 参数** (composition 快照), 下面那个
    // `.pointerInput(Unit)` 只在**首次组合**时建立协程 —— 闭包里捕获的
    // scrollY 会被永久冻结成首次组合的值 (0f)。后果: 每一次拖动事件都算
    // `0f - travelY`, 于是无论已经滚到哪里, 手指一动画面就"跳回原点再偏移",
    // 表现为上滑一段后松手又弹回、或根本滚不动。
    //
    // rememberUpdatedState 提供一个稳定的 State 容器, 每次重组写入最新值,
    // 手势内部读 `.value` 永远拿到当前值 —— 这是 Compose 里"长生命手势
    // 调用短生命数据"的标准解法。
    val liveScrollY = rememberUpdatedState(scrollY)
    val liveMaxScrollY = rememberUpdatedState(maxScrollY)

    // vm.layers 是 Compose state 列表, reversed() 每次调用都**新建一个 list**。
    // 这里把它记住: 只有图层集合本身变化时才重算, 而不是每次重组都分配。
    // (手势期间这个 composable 会频繁重组, 每帧一次 reversed() 是实打实的浪费)
    val layers = remember(vm.layers) { vm.layers.reversed() }

    // —— 轨道头并入本 Canvas ——
    //
    // 此前左侧轨道头是独立的 Compose Column (TrackHeaders), 用
    // Modifier.offset 整数滚动; 右侧轨道网格是 Canvas, 用 float 坐标滚动。
    // 两套系统必须像素级一致才对得上, 实测却恒定偏差 ~40px、部分行无标签、
    // 一开面板就错位。现在头部与网格共用同一 Canvas / 同一 scrollY /
    // 同一 float 行坐标 (i * rowPx), 错位在结构上不可能再发生。
    val density = LocalDensity.current
    val headerW = with(density) { TRACK_HEADER_W.toPx() }
    val headPadPx = with(density) { 6.dp.toPx() }
    val dotZonePx = with(density) { 20.dp.toPx() }
    val dotGapPx = with(density) { 4.dp.toPx() }
    val dotRadiusPx = with(density) { 5.dp.toPx() }
    val textX = headPadPx + dotZonePx + dotGapPx
    val textMaxW = (headerW - textX - headPadPx).coerceAtLeast(1f)
    // 图层名排版结果缓存: 图层集合变化才重排 (手势期间频繁重组, 别每帧都
    // measure)。文字高度固定 (11sp), 行高 (缩放) 变化只影响垂直居中位置。
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
    // 点击手势闭包只捕获首次组合的值 (pointerInput(Unit) 教训), 会变的
    // (layers / rowPx / scrollY) 一律经 live 容器读; 几何常量 (headerW 等
    // 由 density 派生) 不会变, 直接捕获即可。
    val liveLayers = rememberUpdatedState(layers)
    val liveRowPx = rememberUpdatedState(rowPx)
    val liveHeaderW = rememberUpdatedState(headerW)

    // —— 长按菜单 / 帧块拖拽状态 ——
    //
    // 都是 `by remember` 的 State 委托: 手势闭包捕获的是稳定的 State 对象本身,
    // 读写永远拿到最新值 (与 vm.anim.scrollPx 同一机制); 绘制阶段读它们,
    // 变化只重触发 draw, 不重组。
    var frameMenu by remember { mutableStateOf<FrameMenuState?>(null) }
    var trackMenu by remember { mutableStateOf<TrackMenuState?>(null) }
    var frameDrag by remember { mutableStateOf<FrameDragState?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    // Canvas 宽 (px): 弹菜单时横向钳位用
    var canvasWpx by remember { mutableFloatStateOf(0f) }
    val liveHaptics = rememberUpdatedState(LocalHapticFeedback.current)

    val frameW = vm.anim.frameWidthPx
    val scrollX = vm.anim.scrollPx
    val currentTime = vm.anim.currentTime
    val cache = vm.anim.keyframeCache
    val thumbs = vm.anim.frameThumbs
    val thumbImages = vm.anim.frameThumbImages
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

    // Canvas 外只包一层 Box: 长按菜单是真实可点的 Compose 节点 (Popup),
    // 需要与 Canvas 同原点的布局锚 —— Popup 相对 Box 定位后, 锚点坐标就是
    // 手势里的 Canvas 局部坐标, 无需任何换算。
    Box(modifier = modifier) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 视口宽回写时扣掉头部区: 这个值只喂给缩略图防抖, 但语义上是
            // "轨道区宽度", 不是整块 Canvas 宽度
            .onSizeChanged {
                canvasWpx = it.width.toFloat()
                vm.anim.viewportWidthPx = (it.width - headerW).coerceAtLeast(0f)
            }
            // 统一手势入口 (v2): tap / 长按菜单 / 长按拖拽帧块 / 单指滚动平移 / 双指缩放平移。
            //
            // 铁律: 本节点上**只有一个**消费者 pointerInput。此前 tap 是独立的
            // detectTapGestures (第二个消费者), 靠"它只消费 up 不消费 move"才与
            // 手写循环勉强共存; 现在加入长按 + 块拖拽后手势语义复杂化, 若再挂
            // 第三个消费者必然互相饿死 (单指拖动 + detectTransformGestures 那次
            // 已经踩过)。所以 tap/长按/拖拽全部并入同一个 awaitPointerEventScope,
            // 自己做手势仲裁:
            //
            //   阶段 A (按下 → longPressTimeout 判定窗口, 位移累计不超 slop):
            //     抬起                 → tap (菜单开着时只收菜单, 不当 seek)
            //     累计位移超 slop       → 滚动/平移
            //     第二根手指落下        → 双指缩放
            //     超时仍按住未超 slop   → 长按
            //   长按之后 (阶段 B):
            //     抬起                 → 菜单保留, 等用户点菜单项
            //     位移超 slop           → 长按命中帧块 → 块拖拽; 未命中 → 滚动/平移
            //     第二根手指            → 双指缩放 (收菜单)
            //
            // 播放中不响应长按: 菜单/拖拽是编辑操作, 与播放互斥 (isPlaying 时
            // 长按视为空按, 后续移动照常滚动)。
            .pointerInput(Unit) {
                // 手势闭包只在首次组合建立, 普通参数会被冻结 —— 一律走 live
                val liveScroll = liveScrollY
                val liveMax = liveMaxScrollY
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis

                // tap 动作 (原 detectTapGestures.onTap 逻辑并入后成为本地函数)
                fun doTap(x: Float, y: Float) {
                    val ls = liveLayers.value
                    val rpx = liveRowPx.value
                    val hw = liveHeaderW.value
                    val row = ((y + liveScroll.value) / rpx).toInt()
                    if (x < hw) {
                        // 轨道头区: 圆点 = 切换可见性, 其余 = 选中图层
                        if (row in ls.indices) {
                            val layer = ls[row]
                            if (x < headPadPx + dotZonePx) {
                                vm.toggleLayerVisible(layer.index)
                            } else {
                                vm.setCurrentLayer(layer.index)
                            }
                        }
                    } else {
                        // 轨道区: x 先扣掉头部宽才是帧坐标
                        val frame =
                            ((vm.anim.scrollPx + x - hw) / vm.anim.frameWidthPx).toInt()
                        if (row in ls.indices) {
                            vm.setCurrentLayer(ls[row].index)
                            vm.anim.selectedTrack = ls[row].index
                        }
                        vm.animationSeek(frame)
                    }
                }

                // 长按命中测试: 返回 (图层索引, 帧号, 是否命中已有帧块)。
                // 命中块 = hold 语义下"曝光覆盖该帧"的关键帧起点; 空白格 = 该格
                // 帧号本身 (弹"新建帧/粘贴"菜单)。
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

                awaitPointerEventScope {
                    gesture@ while (true) {
                        // —— 等待第一根手指 ——
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val downPos = first.position
                        var lastCentroid = Offset.Zero
                        var lastDistance = 0f
                        var multiTouch = false
                        // 单指轴锁定状态
                        var axisLocked = 0            // 0=未定 1=纵向 2=横向
                        var travelX = 0f
                        var travelY = 0f
                        // 判定前累计、判定后一次性补齐, 手感才跟手
                        var pendingX = 0f
                        var pendingY = 0f

                        val menuWasOpen = frameMenu != null || trackMenu != null

                        // —— 阶段 A: 判定窗口。返回 null = 超时未动 → 长按成立 ——
                        val phase: Int? = withTimeoutOrNull(longPressMs) {
                            var r = 0
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) { r = 0; break }          // tap
                                if (pressed.size >= 2) { r = 2; break }          // 双指
                                val c =
                                    pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                pendingX += c.positionChange().x
                                pendingY += c.positionChange().y
                                if (abs(pendingX) > slop || abs(pendingY) > slop) {
                                    r = 1; break                                 // 滚动/平移
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

                        // 菜单开着时开始滚动/缩放: 先收菜单 (tap 收菜单在 phase == 0)
                        if ((phase == 1 || phase == 2) && menuWasOpen) {
                            frameMenu = null
                            trackMenu = null
                        }

                        if (phase == null) {
                            val ls = liveLayers.value
                            val rpx = liveRowPx.value
                            val hw = liveHeaderW.value
                            val row = ((downPos.y + liveScroll.value) / rpx).toInt()

                            // —— 轨道头长按: 弹出图层管理菜单 (删除/复制/清空) ——
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

                            // —— 长按成立: 命中帧块 → 全量菜单 (可拖拽);
                            //    命中空白格 → 精简菜单 (新建帧/粘贴); 播放中不弹 ——
                            val hit =
                                if (!vm.anim.isPlaying) hitTest(downPos.x, downPos.y) else null
                            if (hit == null) {
                                // 播放中长按: 收掉旧菜单, 后续移动转滚动 (主循环)
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

                                // —— 阶段 B: 长按后等抬起 / 拖动 / 第二指 ——
                                var b = 0              // 0=抬起(菜单保留) 1=移动 2=双指
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) { b = 0; break }
                                    if (pressed.size >= 2) { b = 2; break }
                                    val c =
                                        pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                                    pendingX += c.positionChange().x
                                    pendingY += c.positionChange().y
                                    if (abs(pendingX) > slop || abs(pendingY) > slop) { b = 1; break }
                                }
                                if (b == 0) continue@gesture   // 菜单保留, 本次手势结束
                                if (b == 2) {
                                    frameMenu = null           // 双指: 收菜单进缩放 (主循环)
                                } else {
                                    // 位移: 关菜单; 长按的是帧块才进入拖拽,
                                    // 空白格菜单不拖拽 (转普通滚动, 落入主循环)
                                    frameMenu = null
                                    if (hitOnBlock) {
                                        frameDrag = FrameDragState(hitLayer, hitTime)
                                        dragDx = 0f
                                        // —— 块拖拽循环: ghost 横向跟手, 抬手结算 ——
                                        // 注: Android 的 ACTION_CANCEL 在 Compose 层就是
                                        // "全部指针抬起" (无独立 Cancel 事件), 无法区分,
                                        // 空槽校验保证误结算也不会覆盖已有帧
                                        while (true) {
                                            val dev = awaitPointerEvent()
                                            val pressed = dev.changes.filter { it.pressed }
                                            if (pressed.isEmpty()) {
                                                val dr = frameDrag
                                                if (dr != null) {
                                                    val from = dr.fromTime
                                                    val target =
                                                        from + (dragDx / vm.anim.frameWidthPx).roundToInt()
                                                    val times =
                                                        vm.anim.keyframeCache[dr.layer].orEmpty()
                                                    // 只落空槽: 目标已有帧时引擎 moveKeyframe
                                                    // 会"先删再移" (覆盖语义, 静默毁数据) ——
                                                    // 拒绝, 表现为回弹
                                                    if (target != from && target >= 0 &&
                                                        !times.contains(target)
                                                    ) {
                                                        liveHaptics.value.performHapticFeedback(
                                                            HapticFeedbackType.LongPress,
                                                        )
                                                        vm.animationMoveKeyframe(dr.layer, from, target)
                                                        // 播放头跟过去: 新位置立即高亮,
                                                        // 画布也切到搬过去的帧
                                                        vm.animationSeek(target)
                                                    }
                                                }
                                                frameDrag = null
                                                dragDx = 0f
                                                break
                                            }
                                            val c =
                                                pressed.firstOrNull { it.id == first.id }
                                                    ?: pressed.first()
                                            // v1 只做同轨道水平移动: ghost 取相对按下点的
                                            // 绝对偏移 (无累计漂移), 纵向位移忽略 (锁定本行)
                                            dragDx = c.position.x - downPos.x
                                            for (cc in dev.changes) cc.consume()
                                        }
                                        continue@gesture
                                    }
                                    // 空白格菜单: 不拖拽, 转普通滚动 (落入主循环)
                                }
                            }
                        }

                        // —— 主循环: 单指滚动/平移 + 双指缩放 ——
                        // (phase == 1 / 2 直达这里; 长按未命中后的移动也落到这里)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            // 质心与平均间距: 单指时就是该指位置, 双指时是两指中心
                            var cx = 0f
                            var cy = 0f
                            for (c in pressed) { cx += c.position.x; cy += c.position.y }
                            cx /= pressed.size
                            cy /= pressed.size
                            val centroid = Offset(cx, cy)

                            // 平均到质心的距离, 用来算缩放比例
                            var dist = 0f
                            for (c in pressed) {
                                dist += kotlin.math.hypot(c.position.x - cx, c.position.y - cy)
                            }
                            dist /= pressed.size

                            if (pressed.size >= 2) {
                                // 进入双指模式: 放弃单指的轴锁定累积, 避免松手后
                                // 残留位移被应用成一次跳动
                                if (!multiTouch) {
                                    multiTouch = true
                                    axisLocked = 0
                                    travelX = 0f
                                    travelY = 0f
                                    pendingX = 0f
                                    pendingY = 0f
                                    lastCentroid = centroid
                                    lastDistance = dist
                                } else {
                                    val panX = centroid.x - lastCentroid.x
                                    val panY = centroid.y - lastCentroid.y
                                    val zoom = if (lastDistance > 1f && dist > 1f) dist / lastDistance else 1f

                                    val oldW = vm.anim.frameWidthPx
                                    val newW = (oldW * zoom).coerceIn(MIN_FRAME_W, MAX_FRAME_W)
                                    // 缩放锚定在双指中心: 保持该点下的帧号不变
                                    val anchorFrame = (vm.anim.scrollPx + centroid.x) / oldW
                                    vm.anim.frameWidthPx = newW
                                    vm.anim.scrollPx =
                                        (anchorFrame * newW - centroid.x - panX).coerceAtLeast(0f)
                                    if (panY != 0f) {
                                        onScrollYChange(
                                            (liveScroll.value - panY).coerceIn(0f, liveMax.value),
                                        )
                                    }
                                    lastCentroid = centroid
                                    lastDistance = dist
                                    PerfTrace.tick("timeline.pinch", 500L)
                                }
                                for (c in ev.changes) c.consume()
                                continue
                            }

                            // —— 单指路径 ——
                            // 双指抬起后剩一根: 重新进入单指, 但不清零已有进度,
                            // 只重置累计器, 否则会突然跳一段
                            if (multiTouch) {
                                multiTouch = false
                                axisLocked = 0
                                travelX = 0f
                                travelY = 0f
                                pendingX = 0f
                                pendingY = 0f
                            }

                            val change = pressed.firstOrNull { it.id == first.id } ?: pressed.first()
                            val dx = change.positionChange().x
                            val dy = change.positionChange().y
                            change.consume()

                            if (axisLocked == 0) {
                                pendingX += dx
                                pendingY += dy
                                if (kotlin.math.abs(pendingX) > slop ||
                                    kotlin.math.abs(pendingY) > slop
                                ) {
                                    axisLocked =
                                        if (kotlin.math.abs(pendingY) >= kotlin.math.abs(pendingX)) 1 else 2
                                    travelX = pendingX
                                    travelY = pendingY
                                    pendingX = 0f
                                    pendingY = 0f
                                } else {
                                    continue
                                }
                            } else {
                                travelX += dx
                                travelY += dy
                            }

                            if (axisLocked == 1) {
                                // 读 liveScroll.value (不是冻结的 scrollY 参数),
                                // 并在这里做钳位 —— 不再依赖外层 lambda 的
                                // coerceIn, 因为外层拿到的 maxScrollY 同样是
                                // 组合期快照, 转屏/加轨道后会过期。
                                val next = (liveScroll.value - travelY)
                                    .coerceIn(0f, liveMax.value)
                                onScrollYChange(next)
                                travelY = 0f
                                // 纵向滚动事件计数。日志里应看到"次/窗口"随滑动
                                // 持续增长; 若滑了却几乎不增长, 说明手势根本没
                                // 进到这一支 (轴锁定判成了横向, 或压根没收到事件)。
                                PerfTrace.tick("timeline.scrollY", 500L)
                            } else {
                                vm.anim.scrollPx =
                                    (vm.anim.scrollPx - travelX).coerceAtLeast(0f)
                                travelX = 0f
                            }
                        }
                    }
                }
            },
    ) {
        val drawStart = android.os.SystemClock.elapsedRealtimeNanos()
        // 轨道区宽 = 整块 Canvas 宽 - 头部宽 (用于横向帧裁剪)
        val trackW = (size.width - headerW).coerceAtLeast(0f)

        // —— 右侧轨道区 ——
        // 裁剪到 headerW 右侧: 横向滚动时帧块向左滑出视口, 若不裁会滑进
        // 头部区 (此前的独立 Canvas 天然有自身边界裁剪, 合并后必须显式做)
        clipRect(left = headerW, top = 0f, right = size.width, bottom = size.height) {
            translate(left = headerW - scrollX, top = -scrollY) {
                val contentH = (layers.size * rowPx).coerceAtLeast(size.height + scrollY)
                val thumbInset = 3.dp.toPx()

                // 视口裁剪: 这个 Canvas 在缩放/平移期间每帧重画, 若对**所有**轨道
                // 的**所有**关键帧无条件绘制, 代价就是 O(图层数 x 关键帧数) 次
                // drawRoundRect + drawImage。轨道一多、帧一密, 手势立刻发涩。
                //
                // 只画与可视区相交的行与帧块:
                //  - 行: 由 scrollY / size.height 反算出行索引区间;
                //  - 帧: 由 scrollX / frameW / trackW 反算出帧号区间, 再用
                //    二分找到该轨道里第一个可能可见的关键帧 (times 是有序的),
                //    从那里开始扫到超出右边界为止。
                val firstRow = (scrollY / rowPx).toInt().coerceIn(0, layers.size)
                val lastRow = ((scrollY + size.height) / rowPx).toInt().coerceIn(0, layers.size - 1)
                // 首帧测量前 size.height/width 可能为 0, 这时 lastRow < firstRow,
                // 区间为空 (只画播放头), 不会出错。
                val firstFrame = (scrollX / frameW).toInt().coerceAtLeast(0)
                // 左边界往左多留一帧 (块可能跨越左边界), 右边界同理
                val lastFrame = ((scrollX + trackW) / frameW).toInt().coerceAtLeast(0) + 1

                for (i in firstRow..lastRow) {
                    val layer = layers[i]
                    val top = i * rowPx

                    val times = cache[layer.index]
                    if (!times.isNullOrEmpty()) {
                        // times 升序: 二分到第一个 >= firstFrame 的位置。
                        // 不能简单跳过 "下一帧号 < firstFrame" 的块 —— 它可能正
                        // 跨越左边界 (长曝光块), 所以起点再退一格。
                        var start = times.binarySearch(firstFrame)
                        if (start < 0) start = -(start + 1)
                        start = (start - 1).coerceAtLeast(0)

                        for (idx in start until times.size) {
                            val t = times[idx]
                            if (t > lastFrame) break
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
                            // 用预算好的 ImageBitmap 包装 (见 frameThumbImages):
                            // 在绘制里现调 asImageBitmap() 会每个帧槽分配一个包装对象
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
                                for (f in t until next) {
                                    val slotX = f * frameW + 2f

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

                // —— 长按高亮: 被按住的块描白边 + 轻微提亮 ——
                // 用白色而不是主题色, 与"播放头所在块"的 accent 高亮区分开
                // (画世界 Pro 的选中块也是白/浅色描边)
                frameMenu?.let { m ->
                    val row = layers.indexOfFirst { it.index == m.layer }
                    if (row >= 0) {
                        val t = m.time
                        // 命中块 = 整块跨度; 空白格 = 单格
                        val span = if (m.onBlock) {
                            val next = cache[m.layer]?.firstOrNull { it > t } ?: (t + 1)
                            (next - t).coerceAtLeast(1)
                        } else {
                            1
                        }
                        val mx = t * frameW + 2f
                        val my = row * rowPx + 4f
                        val mw = (span * frameW - 4f).coerceAtLeast(2f)
                        val mh = rowPx - 8f
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.10f),
                            topLeft = Offset(mx, my),
                            size = Size(mw, mh),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.90f),
                            topLeft = Offset(mx, my),
                            size = Size(mw, mh),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = selBorderPx),
                        )
                    }
                }

                // —— 块拖拽: ghost 跟手 + 目标格描边 (绿 = 空槽可落, 红 = 占用拒绝) ——
                frameDrag?.let { drg ->
                    val row = layers.indexOfFirst { it.index == drg.layer }
                    if (row >= 0) {
                        val t = drg.fromTime
                        val next = cache[drg.layer]?.firstOrNull { it > t } ?: (t + 1)
                        val span = (next - t).coerceAtLeast(1)
                        val gw = (span * frameW - 4f).coerceAtLeast(2f)
                        val gy = row * rowPx + 4f
                        val gh = rowPx - 8f
                        // 原位置残影: 提示"从这里搬走"
                        drawRoundRect(
                            color = Morandi.subText.copy(alpha = 0.35f),
                            topLeft = Offset(t * frameW + 2f, gy),
                            size = Size(gw, gh),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = 1.5f),
                        )
                        // ghost 块体 + 缩略图
                        val gx = t * frameW + 2f + dragDx
                        drawRoundRect(
                            color = Morandi.panelHi.copy(alpha = 0.94f),
                            topLeft = Offset(gx, gy),
                            size = Size(gw, gh),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        )
                        val img = thumbImages[frameThumbKey(drg.layer, t)]
                        if (showThumbs && img != null) {
                            val iw = gw - thumbInset * 2f
                            val ih = gh - thumbInset * 2f
                            var tw = iw
                            var th = tw / thumbAspect
                            if (th > ih) {
                                th = ih
                                tw = th * thumbAspect
                            }
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(
                                    (gx + (gw - tw) / 2f).roundToInt(),
                                    (gy + (gh - th) / 2f).roundToInt(),
                                ),
                                dstSize = IntSize(
                                    tw.roundToInt().coerceAtLeast(1),
                                    th.roundToInt().coerceAtLeast(1),
                                ),
                                filterQuality = FilterQuality.Medium,
                                alpha = 0.9f,
                            )
                        }
                        drawRoundRect(
                            color = Morandi.accent,
                            topLeft = Offset(gx, gy),
                            size = Size(gw, gh),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = selBorderPx),
                        )
                        // 目标格: 单格宽 (移动后该帧从目标位置开始曝光)
                        val target = t + (dragDx / frameW).roundToInt()
                        if (target != t && target >= 0) {
                            val occupied = cache[drg.layer]?.contains(target) == true
                            drawRoundRect(
                                color = if (occupied) DragTargetBad else DragTargetOk,
                                topLeft = Offset(target * frameW + 2f, gy),
                                size = Size(frameW - 4f, gh),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                                style = Stroke(width = selBorderPx),
                            )
                        }
                    }
                }
            }
        }

        // —— 左侧轨道头 ——
        // 只随 scrollY 垂直滚动, 不随 scrollX 水平平移; 裁剪到头部宽度内,
        // 滚出视口的行被截断 (等价于旧 TrackHeaders 的 clipToBounds)。
        // 与轨道区同一条 for 循环公式 (i * rowPx), 行必然对齐。
        if (layers.isNotEmpty()) {
            clipRect(left = 0f, top = 0f, right = headerW, bottom = size.height) {
                translate(top = -scrollY) {
                    val firstRow = (scrollY / rowPx).toInt().coerceIn(0, layers.size - 1)
                    val lastRow =
                        ((scrollY + size.height) / rowPx).toInt().coerceIn(0, layers.size - 1)
                    for (i in firstRow..lastRow) {
                        val layer = layers[i]
                        val rowCenterY = i * rowPx + rowPx / 2f

                        // 可见性圆点 (与旧 TrackHeaders 同视觉: 实心圆 + 隐藏时斜杠)
                        val dotCx = headPadPx + dotZonePx / 2f
                        drawCircle(
                            color = if (layer.visible) Morandi.text
                            else Morandi.subText.copy(alpha = 0.4f),
                            radius = dotRadiusPx,
                            center = Offset(dotCx, rowCenterY),
                        )
                        if (!layer.visible) {
                            drawLine(
                                color = Morandi.panel,
                                start = Offset(dotCx - 6.dp.toPx(), rowCenterY - 6.dp.toPx()),
                                end = Offset(dotCx + 6.dp.toPx(), rowCenterY + 6.dp.toPx()),
                                strokeWidth = 2f,
                            )
                        }

                        // 图层名: 行高足够时才画 (行高极小时只留圆点, 免得文字
                        // 越过行界叠到邻行); 颜色在绘制时覆盖 —— 选中层用主题色,
                        // 与旧 Text(color = ...) 行为一致
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

        // 打点: Canvas 每帧重画, 用频率计数看"到底每秒画了多少次、每次多少工作量"。
        // 单次耗时不重要 —— 高频 + 高工作量叠加才是掉帧的原因。
        // (不在这里逐帧打日志: 会刷屏, 反而掩盖真正的问题)
        PerfTrace.tickNanos(
            name = "timeline.draw",
            nanos = android.os.SystemClock.elapsedRealtimeNanos() - drawStart,
        )
        PerfTrace.tick("timeline.blocks", 2000L)
        PerfTrace.tick("timeline.images", 2000L)
    }

        // —— 长按上下文菜单 (画世界 Pro 风格: 深色圆角小卡, 锚在被按住的块上方) ——
        // Popup 是独立子窗口, 不被 Canvas 裁剪; focusable = false 让菜单外的
        // 点击继续落到 Canvas 手势 (统一循环里"菜单开着时的 tap = 只收菜单")
        frameMenu?.let { menu ->
            val menuShape = RoundedCornerShape(10.dp)
            val menuW = 132.dp

            // 菜单项只放引擎真实支持的操作, 不留死项。
            // 剪贴板是 Kotlin 层的"源位置记录": 源帧被删则缓存里查不到, 粘贴自动隐藏。
            val canPaste = frameClipboard?.let { (cl, ct) ->
                vm.anim.keyframeCache[cl].orEmpty().contains(ct)
            } == true

            // 从 from 起第一个空槽 (hold 语义: 块自身曝光内的格也算空)
            fun nextFreeSlot(layer: Int, from: Int): Int {
                val times = vm.anim.keyframeCache[layer].orEmpty()
                var t = from
                var guard = 0
                while (times.contains(t) && guard < 512) {
                    t++
                    guard++
                }
                return t
            }

            val items: List<Pair<String, () -> Unit>> = buildList {
                if (menu.onBlock) {
                    // 在该块之后第一个空位新建空白帧 (把画面"分"出来逐帧作画)
                    add("新建帧" to {
                        vm.animationAddBlankKeyframeAt(menu.layer, menu.time + 1)
                    })
                    // 复制到下一空位, 共享像素 (首次落笔才分叉, 逐帧微调最省内存)
                    add("复制帧" to {
                        val t2 = nextFreeSlot(menu.layer, menu.time + 1)
                        vm.animationCopyCurrentFrameTo(
                            t2,
                            menu.layer,
                            share = true,
                            fromTime = menu.time,
                        )
                        // 播放头跟过去: 新块立即高亮, 画布切到复制出来的帧
                        vm.animationSeek(t2)
                    })
                    add("拷贝" to {
                        frameClipboard = menu.layer to menu.time
                        liveHaptics.value.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    })
                    add("剪切" to {
                        frameClipboard = menu.layer to menu.time
                        vm.animationRemoveKeyframe(menu.layer, menu.time)
                    })
                }
                if (canPaste) {
                    val clip = frameClipboard
                    if (clip != null) {
                        val clipTime = clip.second
                        // 块上粘贴 = 贴到下一空位; 空白格粘贴 = 贴到该格
                        val target =
                            if (menu.onBlock) nextFreeSlot(menu.layer, menu.time + 1) else menu.time
                        add("粘贴" to {
                            // 独立副本 (share=false): 粘贴语义是"复制一份新内容",
                            // 之后两边各自修改互不影响
                            vm.animationCopyCurrentFrameTo(
                                target,
                                menu.layer,
                                share = false,
                                fromTime = clipTime,
                            )
                            vm.animationSeek(target)
                        })
                    }
                }
                if (menu.onBlock) {
                    add("删除帧" to {
                        vm.animationRemoveKeyframe(menu.layer, menu.time)
                    })
                }
            }

            val menuWpx = with(LocalDensity.current) { menuW.toPx() }
            val menuHpx = with(LocalDensity.current) { (items.size * 33 + 10).dp.toPx() }
            val padPx = with(LocalDensity.current) { 8.dp.toPx() }
            // 锚点: 块上方居中; 横向钳位防越界; 贴顶时翻到块下方
            val x = (menu.anchorX - menuWpx / 2f)
                .coerceIn(0f, (canvasWpx - menuWpx).coerceAtLeast(0f))
            val aboveY = menu.anchorY - menuHpx - padPx
            val y = if (aboveY < 0f) menu.anchorY + rowPx + padPx else aboveY
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x.roundToInt(), y.roundToInt()),
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    modifier = Modifier
                        .shadow(12.dp, menuShape, spotColor = Color.Black.copy(alpha = 0.45f))
                        .clip(menuShape)
                        .background(Morandi.panelHi.copy(alpha = 0.97f))
                        .glassBorder(menuShape)
                        .width(menuW)
                        .padding(vertical = 5.dp),
                ) {
                    items.forEach { (label, action) ->
                        FrameMenuItem(label) {
                            frameMenu = null
                            vm.setCurrentLayer(menu.layer)
                            action()
                        }
                    }
                }
            }
        }

        trackMenu?.let { menu ->
            val menuShape = RoundedCornerShape(10.dp)
            val menuW = 126.dp
            val canDelete = vm.layers.size > 1

            val items = listOf(
                "复制图层" to { vm.copyLayer(menu.layerIndex) },
                "清空图层" to { vm.clearLayer(menu.layerIndex) },
                "删除图层" to {
                    if (canDelete) {
                        vm.removeLayer(menu.layerIndex)
                    } else {
                        vm.showActionToast("无法删除唯一的图层", R.drawable.ic_x)
                    }
                },
            )

            val menuWpx = with(LocalDensity.current) { menuW.toPx() }
            val menuHpx = with(LocalDensity.current) { (items.size * 33 + 10).dp.toPx() }
            val padPx = with(LocalDensity.current) { 8.dp.toPx() }
            val x = (menu.anchorX - menuWpx / 2f).coerceIn(4f, (canvasWpx - menuWpx).coerceAtLeast(4f))
            val aboveY = menu.anchorY - menuHpx - padPx
            val y = if (aboveY < 0f) menu.anchorY + rowPx + padPx else aboveY

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x.roundToInt(), y.roundToInt()),
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    modifier = Modifier
                        .shadow(12.dp, menuShape, spotColor = Color.Black.copy(alpha = 0.45f))
                        .clip(menuShape)
                        .background(Morandi.panelHi.copy(alpha = 0.97f))
                        .glassBorder(menuShape)
                        .width(menuW)
                        .padding(vertical = 5.dp),
                ) {
                    items.forEach { (label, action) ->
                        val isDelete = label == "删除图层"
                        FrameMenuItem(
                            text = label,
                            textColor = if (isDelete) Color(0xFFE57373) else Morandi.text,
                        ) {
                            trackMenu = null
                            action()
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 长按菜单项
// ============================================================

/** 长按上下文菜单里的单行 (纯文字, 整行可点) */
@Composable
private fun FrameMenuItem(
    text: String,
    textColor: Color = Morandi.text,
    onTap: () -> Unit,
) {
    Text(
        text = text,
        color = textColor,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
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
        Spacer(modifier = Modifier.width(6.dp))
        IconButtonBox(onClick = { vm.animationToggleLoop() }) {
            Icon(
                painter = painterResource(if (vm.anim.loopPlayback) R.drawable.ic_repeat_loop else R.drawable.ic_repeat_none),
                contentDescription = if (vm.anim.loopPlayback) "循环播放" else "单次播放",
                tint = if (vm.anim.loopPlayback) Morandi.accent else Morandi.subText,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        GlyphTextButton(text = "新帧", onClick = { vm.animationAddKeyframe(duplicate = false) }) { drawGlyphPlus() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphTextButton(text = "复制", onClick = { vm.animationAddKeyframe(duplicate = true) }) { drawGlyphDuplicate() }
        Spacer(modifier = Modifier.width(6.dp))
        GlyphTextButton(text = "删帧", onClick = { vm.animationRemoveKeyframe() }) { drawGlyphTrash() }
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

/** 控制条上图文并排的小胶囊按钮 */
@Composable
private fun GlyphTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panelHi.copy(alpha = BUTTON_ALPHA))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(13.dp), onDraw = draw)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = Morandi.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 控制条上包裹自定义 Composable 图标的小按钮 */
@Composable
private fun IconButtonBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panelHi.copy(alpha = BUTTON_ALPHA))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
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
        // 正在取色的洋葱皮色板: null = 没开取色弹窗
        var pickingOnionColor by remember { mutableStateOf<OnionColorTarget?>(null) }

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

            // 前后帧独立配色: 动画作画时"哪边是之前画的"是核心信息,
            // 单色洋葱皮分不清时间方向。默认沿用 Krita 桌面习惯 红=过去/绿=未来。
            CompactSettingRow(label = "过去 / 未来") {
                OnionColorSwatch(
                    color = Color(vm.anim.onionColorBackward),
                    label = "过去",
                    onPick = { pickingOnionColor = OnionColorTarget.Backward },
                )
                Spacer(modifier = Modifier.width(12.dp))
                OnionColorSwatch(
                    color = Color(vm.anim.onionColorForward),
                    label = "未来",
                    onPick = { pickingOnionColor = OnionColorTarget.Forward },
                )
            }
        }

        pickingOnionColor?.let { target ->
            val isBackward = target == OnionColorTarget.Backward
            CompactColorPickerDialog(
                title = if (isBackward) "过去帧着色" else "未来帧着色",
                initialColor = Color(
                    if (isBackward) vm.anim.onionColorBackward else vm.anim.onionColorForward,
                ),
                onColorSelected = { c ->
                    val argb = c.toArgb()
                    if (isBackward) {
                        vm.anim.onionColorBackward = argb
                    } else {
                        vm.anim.onionColorForward = argb
                    }
                    vm.animationApplyOnionSkin()
                    pickingOnionColor = null
                },
                onDismiss = { pickingOnionColor = null },
            )
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

/** 正在取色的洋葱皮色板目标 */
private enum class OnionColorTarget { Backward, Forward }

/**
 * 洋葱皮色板按钮: 圆角色块 + 描述文字。
 * 色块外圈描边用主题边框色, 保证浅色/深色底上都看得清边界。
 */
@Composable
private fun OnionColorSwatch(
    color: Color,
    label: String,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable { onPick() }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
                .glassBorder(RoundedCornerShape(5.dp)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = Morandi.subText, fontSize = 12.sp)
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
