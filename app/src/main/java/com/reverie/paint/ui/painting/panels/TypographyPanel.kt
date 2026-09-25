/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.commitTypographyToCanvas
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 画布内富文本排版控制悬浮面板与操作栏
 */
@Composable
fun TypographyPanel(
    vm: PaintViewModel,
    onOpenTextDialog: () -> Unit = {},
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val cfg = vm.typographyConfig
    var propsOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    ToolFloatPanel(modifier = modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶层水平操作栏
            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. 编辑文字内容按钮
                ToolActionButton(
                    iconRes = R.drawable.ic_text,
                    label = androidx.compose.ui.res.stringResource(R.string.typography_edit_text),
                    onClick = onOpenTextDialog,
                )

                // 2. 粗体 / 斜体 / 下划线
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

                // 3. 对齐方式
                ToolFloatChip(
                    label = androidx.compose.ui.res.stringResource(R.string.typography_align_left),
                    selected = cfg.alignment == 0,
                    onClick = { vm.typographyConfig = cfg.copy(alignment = 0) },
                )
                ToolFloatChip(
                    label = androidx.compose.ui.res.stringResource(R.string.typography_align_center),
                    selected = cfg.alignment == 1,
                    onClick = { vm.typographyConfig = cfg.copy(alignment = 1) },
                )
                ToolFloatChip(
                    label = androidx.compose.ui.res.stringResource(R.string.typography_align_right),
                    selected = cfg.alignment == 2,
                    onClick = { vm.typographyConfig = cfg.copy(alignment = 2) },
                )

                // 4. 展开详细排版属性
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) androidx.compose.ui.res.stringResource(R.string.typography_collapse) else androidx.compose.ui.res.stringResource(R.string.typography_props),
                    active = propsOpen,
                    onClick = { propsOpen = !propsOpen },
                )

                // 5. 完成 (✔) 与 取消 (✕)
                ToolActionButton(
                    iconRes = R.drawable.ic_check,
                    label = androidx.compose.ui.res.stringResource(R.string.confirm),
                    primary = true,
                    onClick = { vm.commitTypographyToCanvas() },
                )
                ToolActionButton(
                    iconRes = R.drawable.ic_x,
                    label = androidx.compose.ui.res.stringResource(R.string.cancel),
                    danger = true,
                    onClick = { vm.isTypographyEditing = false },
                )
            }

            // 展开的纵向属性抽屉 (字体、字号、字间距、行间距)
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
                    // 字体族选择
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            androidx.compose.ui.res.stringResource(R.string.typography_font_default_short) to "default",
                            androidx.compose.ui.res.stringResource(R.string.typography_font_serif_short) to "serif",
                            androidx.compose.ui.res.stringResource(R.string.typography_font_monospace_short) to "monospace",
                            androidx.compose.ui.res.stringResource(R.string.typography_font_cursive_short) to "cursive",
                        ).forEach { (short, id) ->
                            val sel = cfg.fontFamilyName == id || (id == "default" && cfg.fontFamilyName == "系统默认") || (id == "serif" && cfg.fontFamilyName == "衬线体") || (id == "monospace" && cfg.fontFamilyName == "等宽体") || (id == "cursive" && cfg.fontFamilyName == "手写体")
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) Morandi.accent else Morandi.border.copy(alpha = 0.35f))
                                    .clickable { vm.typographyConfig = cfg.copy(fontFamilyName = id) }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(short, color = if (sel) Color.White else Morandi.text, fontSize = 11.sp)
                            }
                        }
                    }

                    // 字号调节
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.typography_font_size),
                        valueText = "${cfg.fontSize.roundToInt()}px",
                        range = 12f..240f,
                        value = cfg.fontSize,
                        onValue = { vm.typographyConfig = cfg.copy(fontSize = it) },
                        labelWidth = 56.dp,
                    )

                    // 字间距调节
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.typography_letter_spacing),
                        valueText = "${cfg.letterSpacingSp.roundToInt()}px",
                        range = -4f..32f,
                        value = cfg.letterSpacingSp,
                        onValue = { vm.typographyConfig = cfg.copy(letterSpacingSp = it) },
                        labelWidth = 56.dp,
                    )

                    // 行距倍数调节
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.typography_line_height),
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
