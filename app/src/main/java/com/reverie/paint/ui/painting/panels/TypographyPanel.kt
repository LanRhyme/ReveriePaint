/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 画布内富文本排版控制悬浮面板与操作栏 (两层结构：上层核心工具/字体/排版/样式，下层精密滑块与提交)
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
    var fontPickerOpen by remember { mutableStateOf(false) }

    if (fontPickerOpen) {
        FontPickerDialog(vm = vm, onDismiss = { fontPickerOpen = false })
    }

    ToolFloatPanel(modifier = modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // === 第一层 (Tier 1): 核心工具、字体、横竖排版、样式与对齐 ===
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1. 编辑文字内容按钮
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .clickable(onClick = onOpenTextDialog)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_text),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.typography_edit_text),
                        fontSize = 12.sp,
                        color = Morandi.text,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 2. 字体选择卡片
                val currentFontDisplay = remember(cfg.fontFamilyName, cfg.fontPath) {
                    FontManager.resolveDisplayName(context, cfg.fontFamilyName, cfg.fontPath)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .clickable { fontPickerOpen = true }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = currentFontDisplay,
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = Morandi.subText,
                        modifier = Modifier.size(12.dp),
                    )
                }

                // 3. 横排 / 竖排 分段切换
                ToolFloatSegmented(
                    options = listOf(
                        false to stringResource(R.string.typography_horizontal),
                        true to stringResource(R.string.typography_vertical),
                    ),
                    selected = cfg.isVertical,
                    onSelect = { vm.typographyConfig = cfg.copy(isVertical = it) },
                )

                // 4. 竖排换列方向 (右至左 RTL / 左至右 LTR)
                AnimatedVisibility(
                    visible = cfg.isVertical,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    ToolFloatSegmented(
                        options = listOf(
                            true to stringResource(R.string.typography_direction_rtl),
                            false to stringResource(R.string.typography_direction_ltr),
                        ),
                        selected = cfg.verticalRtl,
                        onSelect = { vm.typographyConfig = cfg.copy(verticalRtl = it) },
                    )
                }

                // 5. 样式组: 粗体(B) / 斜体(I) / 下划线(U)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolFloatStyleChip(
                        label = "B",
                        selected = cfg.isBold,
                        onClick = { vm.typographyConfig = cfg.copy(isBold = !cfg.isBold) },
                    )
                    ToolFloatStyleChip(
                        label = "I",
                        selected = cfg.isItalic,
                        onClick = { vm.typographyConfig = cfg.copy(isItalic = !cfg.isItalic) },
                    )
                    ToolFloatStyleChip(
                        label = "U",
                        selected = cfg.isUnderline,
                        onClick = { vm.typographyConfig = cfg.copy(isUnderline = !cfg.isUnderline) },
                    )
                }

                // 6. 对齐方式
                ToolFloatSegmented(
                    options = listOf(
                        0 to stringResource(R.string.typography_align_left),
                        1 to stringResource(R.string.typography_align_center),
                        2 to stringResource(R.string.typography_align_right),
                    ),
                    selected = cfg.alignment,
                    onSelect = { vm.typographyConfig = cfg.copy(alignment = it) },
                )

                // 7. 磁吸吸附开关
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

            // 分割细线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Morandi.border.copy(alpha = 0.35f))
            )

            // === 第二层 (Tier 2): 参数滑块 (字号、字间距、行距) + 完成 / 取消操作 ===
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 字号调节
                ToolFloatSlider(
                    label = stringResource(R.string.typography_font_size),
                    valueText = "${cfg.fontSize.roundToInt()}px",
                    range = 12f..240f,
                    value = cfg.fontSize,
                    onValue = { vm.typographyConfig = cfg.copy(fontSize = it) },
                    modifier = Modifier.width(140.dp),
                    labelWidth = 28.dp,
                )

                // 字间距调节
                ToolFloatSlider(
                    label = stringResource(R.string.typography_letter_spacing),
                    valueText = "${cfg.letterSpacingSp.roundToInt()}px",
                    range = -4f..32f,
                    value = cfg.letterSpacingSp,
                    onValue = { vm.typographyConfig = cfg.copy(letterSpacingSp = it) },
                    modifier = Modifier.width(144.dp),
                    labelWidth = 36.dp,
                )

                // 行距倍数调节
                ToolFloatSlider(
                    label = stringResource(R.string.typography_line_height),
                    valueText = String.format("%.1fx", cfg.lineHeightMultiplier),
                    range = 0.8f..2.5f,
                    value = cfg.lineHeightMultiplier,
                    onValue = { vm.typographyConfig = cfg.copy(lineHeightMultiplier = it) },
                    modifier = Modifier.width(140.dp),
                    labelWidth = 28.dp,
                )

                // 操作按钮组: 完成 (✔) 与 取消 (✕)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.accent)
                            .clickable { vm.commitTypographyToCanvas() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Morandi.onAccent,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = stringResource(R.string.confirm),
                            color = Morandi.onAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panelHi)
                            .clickable {
                                vm.isTypographyEditing = false
                                vm.typographySnapGuides = emptyList()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = null,
                            tint = Morandi.subText,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = stringResource(R.string.cancel),
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolFloatStyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Morandi.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Morandi.onAccent else Morandi.subText,
        )
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
