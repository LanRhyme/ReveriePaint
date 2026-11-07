/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.AnchorPosition
import com.reverie.paint.model.AspectRatioPreset
import com.reverie.paint.model.CanvasAdjustMode
import com.reverie.paint.model.ResampleFilter
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/** 面板拖拽时与屏幕边缘/顶栏保持的最小安全间距 */
private val PANEL_EDGE_MARGIN = 12.dp
/** 面板上沿允许到达的最高位置 (避开顶栏) */
private val PANEL_TOP_SAFE = 52.dp
/** 分组标签统一宽度, 保证所有参数行的控件左边缘对齐 */
private val GROUP_LABEL_WIDTH = 40.dp

/**
 * 画布调整面板 (裁切与扩展 / 图像缩放)
 *
 * 紧凑分组布局: 模式 / 尺寸 / 位置 / 比例 / 缩放 / 插值, 标签列统一宽度对齐。
 * 面板整体可拖拽摆放 (偏移量持久化在 ViewModel), 并支持折叠为一条标题栏,
 * 避免长期遮挡画布主体区域。
 *
 * 交互约束 (架构铁律 §4/§5):
 * - 不使用 Dialog / Popup / 全屏 scrim, 面板自身不会阻断画布手势;
 * - 拖拽与点击只消费落在面板上的事件, 面板之外的区域照旧下沉到画布。
 */
@Composable
fun CanvasAdjustPanel(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    TextInputGuard(vm)
    val state = vm.canvasAdjustState
    val focusManager = LocalFocusManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val maxLayers = remember(context, state.targetWidth, state.targetHeight) {
        com.reverie.paint.ui.create.calculateRealMaxLayers(context, state.targetWidth, state.targetHeight)
    }
    val collapsed = vm.isCanvasAdjustPanelCollapsed
    val panelShape = RoundedCornerShape(16.dp)

    BoxWithConstraints(modifier = modifier) {
        val availW = if (maxWidth != Dp.Infinity) maxWidth else 400.dp
        val availH = if (maxHeight != Dp.Infinity) maxHeight else 640.dp
        // 窄屏收敛: 面板宽度跟随可用宽度, 不顶破屏幕
        val panelMaxW = minOf(400.dp, (availW - PANEL_EDGE_MARGIN * 2).coerceAtLeast(260.dp))
        val panelTargetW = minOf(352.dp, panelMaxW)

        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        val panelWDp = with(density) { panelSize.width.toDp() }
        val panelHDp = with(density) { panelSize.height.toDp() }

        // 拖拽边界钳制: 面板任何时刻都完整停留在可视区内, 不越界、不压顶栏与侧边工具栏
        val clampOffset: (Offset) -> Offset = { raw ->
            val railSideInset = PANEL_EDGE_MARGIN + 36.dp // ToolRail 固定 36dp 宽
            val leftInset = if (vm.leftHandMode) PANEL_EDGE_MARGIN else railSideInset
            val rightInset = if (vm.leftHandMode) railSideInset else PANEL_EDGE_MARGIN
            val xMin = leftInset + panelWDp / 2f - availW / 2f
            val xMax = availW / 2f - rightInset - panelWDp / 2f
            val clampedX = if (xMin <= xMax) raw.x.coerceIn(xMin.value, xMax.value) else 0f
            val minY = (panelHDp + PANEL_TOP_SAFE - availH).coerceAtMost(0.dp)
            Offset(x = clampedX, y = raw.y.coerceIn(minY.value, 0f))
        }
        // 屏幕旋转 / 面板高度变化后重新钳制, 防止残留偏移把面板推出屏幕
        LaunchedEffect(availW, availH, panelSize) {
            vm.canvasAdjustPanelOffset = clampOffset(vm.canvasAdjustPanelOffset)
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        vm.canvasAdjustPanelOffset.x.roundToInt(),
                        vm.canvasAdjustPanelOffset.y.roundToInt(),
                    )
                }
                .onSizeChanged { panelSize = it }
                // 展开态固定宽度保证分组对齐; 折叠态收窄为一条紧凑标题栏
                .width(if (collapsed) minOf(216.dp, panelMaxW) else panelTargetW)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.popupStyle(vm.popupPanelOpacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = vm.popupPanelOpacity))
                    }
                )
                .glassBorder(panelShape)
                // 点击面板空白处收起软键盘并释放焦点, 保证空格抓手等画布快捷键始终可用
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AdjustPanelHeader(
                    vm = vm,
                    collapsed = collapsed,
                    title = if (collapsed) {
                        "${state.targetWidth} × ${state.targetHeight}"
                    } else {
                        stringResource(R.string.canvas_adjust_title)
                    },
                    onDrag = { delta ->
                        vm.canvasAdjustPanelOffset = clampOffset(vm.canvasAdjustPanelOffset + delta)
                    },
                    onClearFocus = { focusManager.clearFocus() },
                    modifier = Modifier.fillMaxWidth(),
                )

                AnimatedVisibility(
                    visible = !collapsed,
                    enter = fadeIn(tween(150)) + expandVertically(tween(180), expandFrom = Alignment.Top),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(150), shrinkTowards = Alignment.Top),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // ---- 模式 ----
                        AdjustGroupRow(label = stringResource(R.string.canvas_adjust_group_mode)) {
                            ToolFloatSegmented(
                                options = listOf(
                                    CanvasAdjustMode.CROP_EXPAND to stringResource(R.string.canvas_adjust_mode_crop),
                                    CanvasAdjustMode.RESAMPLE_SCALE to stringResource(R.string.canvas_adjust_mode_scale),
                                ),
                                selected = state.mode,
                                onSelect = { mode ->
                                    focusManager.clearFocus()
                                    vm.canvasAdjustState = if (mode == CanvasAdjustMode.CROP_EXPAND) {
                                        state.copy(mode = mode, lockAspectRatio = false)
                                    } else {
                                        state.copy(mode = mode, lockAspectRatio = true, cropX = 0, cropY = 0)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // ---- 尺寸 ----
                        AdjustGroupRow(label = stringResource(R.string.canvas_adjust_group_size)) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    DimensionInputField(
                                        label = "W",
                                        value = state.targetWidth,
                                        modifier = Modifier.weight(1f),
                                        onValueCommitted = { newW ->
                                            vm.canvasAdjustState = state.withTargetWidth(newW)
                                        },
                                    )
                                    AdjustIconToggle(
                                        icon = if (isRatioLocked(state)) R.drawable.ic_lock else R.drawable.ic_lock_open,
                                        desc = stringResource(
                                            if (isRatioLocked(state)) {
                                                R.string.canvas_adjust_unlock_ratio
                                            } else {
                                                R.string.canvas_adjust_lock_ratio
                                            }
                                        ),
                                        active = isRatioLocked(state),
                                        onClick = {
                                            val newLocked = !isRatioLocked(state)
                                            vm.canvasAdjustState = state.copy(
                                                lockAspectRatio = newLocked,
                                                selectedPreset = if (!newLocked) {
                                                    AspectRatioPreset.FREE
                                                } else {
                                                    state.selectedPreset
                                                },
                                            )
                                        },
                                    )
                                    DimensionInputField(
                                        label = "H",
                                        value = state.targetHeight,
                                        modifier = Modifier.weight(1f),
                                        onValueCommitted = { newH ->
                                            vm.canvasAdjustState = state.withTargetHeight(newH)
                                        },
                                    )
                                    AdjustIconToggle(
                                        icon = R.drawable.ic_undo,
                                        desc = stringResource(R.string.canvas_adjust_reset),
                                        active = false,
                                        onClick = {
                                            focusManager.clearFocus()
                                            vm.canvasAdjustState = state.reset()
                                        },
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.create_max_layers_desc, maxLayers),
                                    color = Morandi.subText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }

                        if (state.mode == CanvasAdjustMode.CROP_EXPAND) {
                            // ---- 位置 (锚点) ----
                            AdjustGroupRow(label = stringResource(R.string.canvas_adjust_group_position)) {
                                AnchorMatrixGrid(
                                    selectedAnchor = state.anchor,
                                    onAnchorSelected = { newAnchor ->
                                        vm.canvasAdjustState = state.withAnchor(newAnchor)
                                    },
                                )
                            }
                            // ---- 比例 ----
                            AdjustGroupRow(label = stringResource(R.string.canvas_adjust_group_ratio)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AspectRatioPreset.entries.forEach { preset ->
                                        ToolFloatChip(
                                            label = stringResource(preset.labelRes),
                                            selected = state.selectedPreset == preset,
                                            onClick = { vm.canvasAdjustState = state.withPreset(preset) },
                                        )
                                    }
                                }
                            }
                        } else {
                            // ---- 缩放倍率 ----
                            AdjustGroupRow(label = stringResource(R.string.canvas_adjust_group_scale)) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    listOf(
                                        0.5f to "0.5×",
                                        0.75f to "0.75×",
                                        1.5f to "1.5×",
                                        2.0f to "2.0×",
                                        3.0f to "3.0×",
                                    ).forEach { (mul, label) ->
                                        val isCurrent = (state.origWidth * mul).roundToInt() == state.targetWidth &&
                                            (state.origHeight * mul).roundToInt() == state.targetHeight
                                        ToolFloatChip(
                                            label = label,
                                            selected = isCurrent,
                                            onClick = {
                                                vm.canvasAdjustState = state.copy(
                                                    targetWidth = (state.origWidth * mul).roundToInt().coerceIn(1, 16384),
                                                    targetHeight = (state.origHeight * mul).roundToInt().coerceIn(1, 16384),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            // ---- 插值算法 ----
                            AdjustGroupRow(label = stringResource(R.string.canvas_adjust_group_interpolation)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    listOf(ResampleFilter.BICUBIC, ResampleFilter.NEAREST).forEach { filter ->
                                        ToolFloatChip(
                                            label = stringResource(filter.labelRes),
                                            selected = state.resampleFilter == filter,
                                            onClick = {
                                                vm.canvasAdjustState = state.copy(resampleFilter = filter)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // ---- 操作栏 ----
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReTextButton(
                                text = stringResource(R.string.common_cancel),
                                onClick = {
                                    focusManager.clearFocus()
                                    vm.exitCanvasAdjustMode()
                                },
                                modifier = Modifier.weight(1f),
                                containerColor = Morandi.panelHi,
                                contentColor = Morandi.text,
                                fontSize = 13.sp,
                            )
                            ReTextButton(
                                text = stringResource(R.string.canvas_adjust_apply),
                                onClick = {
                                    focusManager.clearFocus()
                                    vm.applyCanvasAdjustment()
                                },
                                modifier = Modifier.weight(1f),
                                containerColor = Morandi.accent,
                                contentColor = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isRatioLocked(state: com.reverie.paint.model.CanvasAdjustState): Boolean =
    state.lockAspectRatio || state.selectedPreset != AspectRatioPreset.FREE

/**
 * 面板标题栏: 左侧握把区域可拖动摆放, 右侧折叠与关闭。
 * 拖动区与按钮区分离, 避免拖拽手势吞掉按钮点击。
 */
@Composable
private fun AdjustPanelHeader(
    vm: PaintViewModel,
    collapsed: Boolean,
    title: String,
    onDrag: (Offset) -> Unit,
    onClearFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onClearFocus() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        },
                    )
                }
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 握把视觉 (与 DragPillHandle 同款, 但拖动手势由本行统一消费, 避免嵌套重复累加)
            Box(
                modifier = Modifier
                    .size(24.dp, 4.dp)
                    .clip(CircleShape)
                    .background(Morandi.subText.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        ReIconButton(
            icon = if (collapsed) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
            desc = stringResource(if (collapsed) R.string.canvas_adjust_expand else R.string.canvas_adjust_collapse),
            onTap = {
                onClearFocus()
                vm.isCanvasAdjustPanelCollapsed = !collapsed
            },
            size = 24.dp,
            iconSize = 15.dp,
        )
        PanelCloseButton(onClose = {
            onClearFocus()
            vm.exitCanvasAdjustMode()
        })
    }
}

/**
 * 统一标签对齐的参数行: 40dp 标签列 + 右侧内容区。
 */
@Composable
private fun AdjustGroupRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(GROUP_LABEL_WIDTH),
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun AdjustIconToggle(
    icon: Int,
    desc: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (active) Morandi.accent.copy(alpha = 0.2f) else Morandi.panelHi)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = if (active) Morandi.accent else Morandi.subText,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 专为画布尺寸设计的紧凑数字输入控件
 */
@Composable
private fun DimensionInputField(
    label: String,
    value: Int,
    onValueCommitted: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = value.toString()))
    }

    // 当非编辑状态且外部 value 改变时，同步更新内部显示
    LaunchedEffect(value) {
        if (!isFocused) {
            val currentParsed = textFieldValue.text.toIntOrNull()
            if (currentParsed != value) {
                textFieldValue = TextFieldValue(text = value.toString())
            }
        }
    }

    val focusManager = LocalFocusManager.current

    Row(
        modifier = modifier
            .height(30.dp)
            .widthIn(min = 62.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 3.dp),
        )
        BasicTextField(
            value = textFieldValue,
            onValueChange = { input ->
                val filteredDigits = input.text.filter { it.isDigit() }
                if (filteredDigits.length <= 5) {
                    if (filteredDigits == input.text) {
                        textFieldValue = input
                    } else {
                        // 包含非数字字符被过滤，光标重置到末尾
                        textFieldValue = TextFieldValue(
                            text = filteredDigits,
                            selection = TextRange(filteredDigits.length),
                        )
                    }
                }
            },
            singleLine = true,
            textStyle = TextStyle(
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(Morandi.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    val parsed = textFieldValue.text.toIntOrNull() ?: value
                    onValueCommitted(parsed)
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { fs ->
                    isFocused = fs.isFocused
                    if (fs.isFocused) {
                        // 聚焦时全选文本，便于直接输入新数字覆盖
                        textFieldValue = textFieldValue.copy(
                            selection = TextRange(0, textFieldValue.text.length),
                        )
                    } else {
                        // 失焦时提交；若文本为空或非法则回退为原有值
                        val parsed = textFieldValue.text.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            if (parsed != value) {
                                onValueCommitted(parsed)
                            }
                        } else {
                            textFieldValue = TextFieldValue(text = value.toString())
                        }
                    }
                }
                // 输入框持有焦点时空格/Esc 会被 IME 吞掉, 这里先释放焦点再交给画布快捷键
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Spacebar || ev.key == Key.Escape)) {
                        focusManager.clearFocus()
                        true
                    } else {
                        false
                    }
                },
        )
        Text(
            text = "px",
            color = Morandi.subText.copy(alpha = 0.7f),
            fontSize = 10.sp,
        )
    }
}

/**
 * 3x3 经典九宫格扩展定位锚点选择器
 */
@Composable
private fun AnchorMatrixGrid(
    selectedAnchor: AnchorPosition,
    onAnchorSelected: (AnchorPosition) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val rows = listOf(
                listOf(AnchorPosition.TOP_LEFT, AnchorPosition.TOP_CENTER, AnchorPosition.TOP_RIGHT),
                listOf(AnchorPosition.CENTER_LEFT, AnchorPosition.CENTER, AnchorPosition.CENTER_RIGHT),
                listOf(AnchorPosition.BOTTOM_LEFT, AnchorPosition.BOTTOM_CENTER, AnchorPosition.BOTTOM_RIGHT),
            )
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (anchor in row) {
                        val isSelected = selectedAnchor == anchor
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isSelected) {
                                        Morandi.accent
                                    } else {
                                        Morandi.subText.copy(alpha = 0.35f)
                                    }
                                )
                                .clickable { onAnchorSelected(anchor) },
                        )
                    }
                }
            }
        }
    }
}
