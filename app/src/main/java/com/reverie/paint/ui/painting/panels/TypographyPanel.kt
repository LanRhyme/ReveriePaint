/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.FontManager
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.commitTypographyToCanvas
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 画布内富文本排版控制悬浮面板与操作栏 (紧凑胶囊栏 + 折叠抽屉规范化设计)
 */
@Composable
fun TypographyPanel(
    vm: PaintViewModel,
    onOpenTextDialog: () -> Unit = {},
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val cfg = vm.typographyConfig
    val context = LocalContext.current
    var propsOpen by remember { mutableStateOf(false) }
    var fontPickerOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    if (fontPickerOpen) {
        FontPickerDialog(vm = vm, onDismiss = { fontPickerOpen = false })
    }

    val currentFontDisplayShort = remember(cfg.fontFamilyName, cfg.fontPath) {
        val full = FontManager.resolveDisplayName(context, cfg.fontFamilyName, cfg.fontPath)
        full.substringBefore(" (").take(5)
    }

    val orientationOptions = listOf(
        ToolDropdownItemData(0, R.drawable.ic_text, stringResource(R.string.typography_horizontal)),
        ToolDropdownItemData(1, R.drawable.ic_text, stringResource(R.string.typography_vertical_rtl)),
        ToolDropdownItemData(2, R.drawable.ic_text, stringResource(R.string.typography_vertical_ltr)),
    )

    val alignOptions = listOf(
        ToolDropdownItemData(0, R.drawable.ic_text, stringResource(R.string.typography_align_left)),
        ToolDropdownItemData(1, R.drawable.ic_text, stringResource(R.string.typography_align_center)),
        ToolDropdownItemData(2, R.drawable.ic_text, stringResource(R.string.typography_align_right)),
    )

    ToolFloatPanel(modifier = modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 主胶囊操作栏 (统一规格图标动作按钮与气泡下拉)
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // 1. 编辑文字内容
                ToolActionButton(
                    iconRes = R.drawable.ic_text,
                    label = stringResource(R.string.typography_edit_text),
                    onClick = onOpenTextDialog,
                )

                // 2. 字体选择入口 (显示当前字体简称，带右下小三角指示)
                ToolDropdownTriggerButton(
                    iconRes = R.drawable.ic_text,
                    label = currentFontDisplayShort,
                    expanded = fontPickerOpen,
                    onClick = { fontPickerOpen = true },
                )

                // 3. 排版方向与换列模式下拉 (横排 / 竖排·右至左 / 竖排·左至右)
                ToolBubbleDropdown(
                    items = orientationOptions,
                    selected = if (!cfg.isVertical) 0 else if (cfg.verticalRtl) 1 else 2,
                    labelOverride = if (!cfg.isVertical) {
                        stringResource(R.string.typography_horizontal)
                    } else if (cfg.verticalRtl) {
                        stringResource(R.string.typography_vertical)
                    } else {
                        stringResource(R.string.typography_vertical)
                    },
                    iconOverride = R.drawable.ic_text,
                    onSelect = { mode ->
                        when (mode) {
                            0 -> vm.typographyConfig = cfg.copy(isVertical = false)
                            1 -> vm.typographyConfig = cfg.copy(isVertical = true, verticalRtl = true)
                            2 -> vm.typographyConfig = cfg.copy(isVertical = true, verticalRtl = false)
                        }
                    },
                )

                // 4. 对齐方式下拉 (左 / 中 / 右)
                ToolBubbleDropdown(
                    items = alignOptions,
                    selected = cfg.alignment,
                    labelOverride = when (cfg.alignment) {
                        1 -> stringResource(R.string.typography_align_center)
                        2 -> stringResource(R.string.typography_align_right)
                        else -> stringResource(R.string.typography_align_left)
                    },
                    iconOverride = R.drawable.ic_text,
                    onSelect = { vm.typographyConfig = cfg.copy(alignment = it) },
                )

                // 5. 属性抽屉开关 (字号、字距、行距、样式、磁吸)
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) stringResource(R.string.typography_collapse) else stringResource(R.string.typography_props),
                    active = propsOpen || cfg.isBold || cfg.isItalic || cfg.isUnderline,
                    onClick = { propsOpen = !propsOpen },
                )

                // 6. 完成 (✔) 与 取消 (✕)
                ToolActionButton(
                    iconRes = R.drawable.ic_check,
                    label = stringResource(R.string.confirm),
                    primary = true,
                    onClick = { vm.commitTypographyToCanvas() },
                )
                ToolActionButton(
                    iconRes = R.drawable.ic_x,
                    label = stringResource(R.string.cancel),
                    danger = true,
                    onClick = {
                        vm.isTypographyEditing = false
                        vm.typographySnapGuides = emptyList()
                    },
                )
            }

            // 平滑展开的纵向精密属性抽屉 (样式、字号、字间距、行距、磁吸)
            AnimatedVisibility(
                visible = propsOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 340.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    // 样式 (B / I / U) 与磁吸开关行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ToolFloatChip(
                                label = "B",
                                selected = cfg.isBold,
                                onClick = { vm.typographyConfig = cfg.copy(isBold = !cfg.isBold) },
                            )
                            ToolFloatChip(
                                label = "I",
                                selected = cfg.isItalic,
                                onClick = { vm.typographyConfig = cfg.copy(isItalic = !cfg.isItalic) },
                            )
                            ToolFloatChip(
                                label = "U",
                                selected = cfg.isUnderline,
                                onClick = { vm.typographyConfig = cfg.copy(isUnderline = !cfg.isUnderline) },
                            )
                        }

                        ToolFloatChip(
                            label = stringResource(R.string.typography_snap),
                            selected = cfg.snapEnabled,
                            onClick = {
                                val next = !cfg.snapEnabled
                                vm.typographyConfig = cfg.copy(snapEnabled = next)
                                if (!next) vm.typographySnapGuides = emptyList()
                            },
                        )
                    }

                    // 字号调节
                    ToolFloatSlider(
                        label = stringResource(R.string.typography_font_size),
                        valueText = "${cfg.fontSize.roundToInt()}px",
                        range = 12f..240f,
                        value = cfg.fontSize,
                        onValue = { vm.typographyConfig = cfg.copy(fontSize = it) },
                        labelWidth = 56.dp,
                    )

                    // 字间距调节
                    ToolFloatSlider(
                        label = stringResource(R.string.typography_letter_spacing),
                        valueText = "${cfg.letterSpacingSp.roundToInt()}px",
                        range = -4f..32f,
                        value = cfg.letterSpacingSp,
                        onValue = { vm.typographyConfig = cfg.copy(letterSpacingSp = it) },
                        labelWidth = 56.dp,
                    )

                    // 行距倍数调节
                    ToolFloatSlider(
                        label = stringResource(R.string.typography_line_height),
                        valueText = String.format("%.1fx", cfg.lineHeightMultiplier),
                        range = 0.8f..2.5f,
                        value = cfg.lineHeightMultiplier,
                        onValue = { vm.typographyConfig = cfg.copy(lineHeightMultiplier = it) },
                        labelWidth = 56.dp,
                    )
                }
            }
        }
    }
}


/**
 * 文本内容快速输入与编辑对话框
 */
@Composable
fun TypographyTextDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_text),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    androidx.compose.ui.res.stringResource(R.string.typography_dialog_title),
                    color = Morandi.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text(androidx.compose.ui.res.stringResource(R.string.typography_dialog_placeholder), color = Morandi.subText, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Morandi.accent,
                        unfocusedBorderColor = Morandi.border,
                        focusedContainerColor = Morandi.panel,
                        unfocusedContainerColor = Morandi.panel,
                        cursorColor = Morandi.accent,
                        focusedTextColor = Morandi.text,
                        unfocusedTextColor = Morandi.text,
                    ),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                text = androidx.compose.ui.res.stringResource(R.string.confirm),
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
                textColor = Morandi.accentHi,
            )
        },
        dismissButton = {
            ReTextButton(
                text = androidx.compose.ui.res.stringResource(R.string.cancel),
                onClick = onDismiss,
                textColor = Morandi.subText,
            )
        },
        containerColor = Morandi.panelHi,
    )
}
