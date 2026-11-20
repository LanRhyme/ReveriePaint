/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.reverie.paint.ui.components.ReSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.updateEncloseFillCloseGap
import com.reverie.paint.core.updateEncloseFillExpand
import com.reverie.paint.core.updateEncloseFillFeather
import com.reverie.paint.core.updateEncloseFillIncludeContour
import com.reverie.paint.core.updateEncloseFillRegionMode
import com.reverie.paint.core.updateEncloseFillSampleLayers
import com.reverie.paint.core.updateEncloseFillSpecificColor
import com.reverie.paint.core.updateEncloseFillTolerance
import com.reverie.paint.model.EncloseFillRegionMode
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState

@Composable
private fun getEncloseFillSampleOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_rect, stringResource(R.string.selection_current_layer)),
    ToolDropdownItemData(1, R.drawable.ic_layers, stringResource(R.string.selection_all_layers)),
)

@Composable
private fun getEncloseFillRegionModeOptions(): List<ToolDropdownItemData<EncloseFillRegionMode>> = listOf(
    ToolDropdownItemData(EncloseFillRegionMode.ALL, R.drawable.ic_enclose_fill, stringResource(R.string.enclose_fill_mode_all)),
    ToolDropdownItemData(EncloseFillRegionMode.TRANSPARENT, R.drawable.ic_enclose_fill, stringResource(R.string.enclose_fill_mode_transparent)),
    ToolDropdownItemData(EncloseFillRegionMode.SPECIFIC_COLOR, R.drawable.ic_picker, stringResource(R.string.enclose_fill_mode_specific_color)),
    ToolDropdownItemData(EncloseFillRegionMode.EXCEPT_SPECIFIC_COLOR, R.drawable.ic_picker, stringResource(R.string.enclose_fill_mode_except_specific_color)),
)

/**
 * 圈涂 (Enclose and Fill) 工具属性浮窗:
 * 遵循与 FillPanel / SelectionPanel 统一的折叠展开式 ToolFloatPanel 设计，
 * 支持在原面板内就地展开调整模式、容差、采样、扩缩与边缘连通。
 */
@Composable
fun EncloseFillPanel(
    vm: PaintViewModel,
    closeGap: Int = vm.encloseFillCloseGap,
    onCloseGap: (Int) -> Unit = { vm.updateEncloseFillCloseGap(it) },
    tolerance: Int = vm.encloseFillTolerance,
    onTolerance: (Int) -> Unit = { vm.updateEncloseFillTolerance(it) },
    sampleLayers: Int = vm.encloseFillSampleLayers,
    onSampleLayers: (Int) -> Unit = { vm.updateEncloseFillSampleLayers(it) },
    expand: Int = vm.encloseFillExpand,
    onExpand: (Int) -> Unit = { vm.updateEncloseFillExpand(it) },
    feather: Int = vm.encloseFillFeather,
    onFeather: (Int) -> Unit = { vm.updateEncloseFillFeather(it) },
    hazeState: HazeState? = null,
) {
    val sampleOptions = getEncloseFillSampleOptions()
    val modeOptions = getEncloseFillRegionModeOptions()
    var propsOpen by remember { mutableStateOf(false) }

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(320.dp),
        ) {
            // 常驻第一行: 采样图层 + 闭合空隙主滑块 + 展开/折叠属性按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolBubbleDropdown(
                    items = sampleOptions,
                    selected = sampleLayers,
                    labelOverride = if (sampleLayers == 0) {
                        stringResource(R.string.fill_sample_current_short)
                    } else {
                        stringResource(R.string.fill_sample_all_short)
                    },
                    onSelect = onSampleLayers,
                    active = true,
                )
                Box(modifier = Modifier.weight(1f)) {
                    ToolFloatSlider(
                        label = stringResource(R.string.fill_close_gap),
                        valueText = "${closeGap}px",
                        range = 0f..32f,
                        value = closeGap.toFloat().coerceIn(0f, 32f),
                        onValue = { onCloseGap(it.toInt()) },
                    )
                }
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) {
                        stringResource(R.string.selection_collapse)
                    } else {
                        stringResource(R.string.selection_props)
                    },
                    active = propsOpen,
                    onClick = { propsOpen = !propsOpen },
                )
            }

            // 折叠展开高级选项 (就地原地展开，不弹任何独立对话框)
            AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    // 过滤模式下拉选择 + 颜色拾取 (如果需要特定色)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val modeLabel = when (vm.encloseFillRegionMode) {
                            EncloseFillRegionMode.ALL -> stringResource(R.string.enclose_fill_mode_all_short)
                            EncloseFillRegionMode.TRANSPARENT -> stringResource(R.string.enclose_fill_mode_transparent_short)
                            EncloseFillRegionMode.SPECIFIC_COLOR -> stringResource(R.string.enclose_fill_mode_specific_color_short)
                            EncloseFillRegionMode.EXCEPT_SPECIFIC_COLOR -> stringResource(R.string.enclose_fill_mode_except_specific_color_short)
                        }

                        ToolBubbleDropdown(
                            items = modeOptions,
                            selected = vm.encloseFillRegionMode,
                            labelOverride = modeLabel,
                            onSelect = { vm.updateEncloseFillRegionMode(it) },
                            active = true,
                        )

                        if (vm.encloseFillRegionMode == EncloseFillRegionMode.SPECIFIC_COLOR ||
                            vm.encloseFillRegionMode == EncloseFillRegionMode.EXCEPT_SPECIFIC_COLOR
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .clickable {
                                        runCatching {
                                            android.graphics.Color.parseColor(vm.brushColor)
                                        }.onSuccess {
                                            vm.updateEncloseFillSpecificColor(it)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.enclose_fill_use_current_color),
                                    fontSize = 11.sp,
                                    color = Morandi.text,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(vm.encloseFillSpecificColor))
                                        .border(1.dp, Morandi.border, CircleShape)
                                )
                            }
                        }
                    }

                    // 容差滑块
                    ToolFloatSlider(
                        label = stringResource(R.string.fill_tolerance),
                        valueText = "$tolerance",
                        range = 1f..100f,
                        value = tolerance.toFloat().coerceIn(1f, 100f),
                        onValue = { onTolerance(it.toInt()) },
                        labelWidth = 48.dp,
                    )

                    // 拓展滑块
                    ToolFloatSlider(
                        label = stringResource(R.string.fill_expand),
                        valueText = "${expand}px",
                        range = -16f..32f,
                        value = expand.toFloat().coerceIn(-16f, 32f),
                        onValue = { onExpand(it.toInt()) },
                        labelWidth = 48.dp,
                    )

                    // 羽化滑块
                    ToolFloatSlider(
                        label = stringResource(R.string.fill_feather),
                        valueText = "${feather}px",
                        range = 0f..32f,
                        value = feather.toFloat().coerceIn(0f, 32f),
                        onValue = { onFeather(it.toInt()) },
                        labelWidth = 48.dp,
                    )

                    // 包含边缘连通区开关 (Switch)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.enclose_fill_include_contour),
                            color = Morandi.subText,
                            fontSize = 12.sp,
                        )
                        ReSwitch(
                            checked = vm.encloseFillIncludeContour,
                            onChecked = { vm.updateEncloseFillIncludeContour(it) },
                        )
                    }
                }
            }
        }
    }
}
