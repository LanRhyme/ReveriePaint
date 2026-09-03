/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.roundToInt

/**
 * Krita 风格专业文本工具对话框 (多行排版 / 字号 / 样式 / 对齐 / 实时预览)
 */
@Composable
fun KritaTextToolDialog(
    initialText: String = "",
    initialFontSize: Double = 48.0,
    brushColorHex: String = "#000000",
    onConfirm: (String, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var fontSize by remember { mutableFloatStateOf(initialFontSize.toFloat()) }
    var isBold by remember { mutableStateOf(false) }
    var isItalic by remember { mutableStateOf(false) }
    var alignment by remember { mutableStateOf(TextAlign.Left) }

    val textColor = remember(brushColorHex) { parseColor(brushColorHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_text),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "文本工具 (Krita)",
                        color = Morandi.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Color swatch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(textColor)
                            .border(1.dp, Morandi.border, CircleShape)
                    )
                    Text("当前颜色", color = Morandi.subText, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Multi-line Text Editor
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text("在此输入文本内容 (支持多行换行)...", color = Morandi.subText, fontSize = 13.sp) },
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

                // Style Controls: Bold, Italic, Alignments
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextOptChip("B", isBold, Modifier.weight(1f)) { isBold = !isBold }
                    TextOptChip("I", isItalic, Modifier.weight(1f)) { isItalic = !isItalic }
                    TextOptChip("左", alignment == TextAlign.Left, Modifier.weight(1f)) { alignment = TextAlign.Left }
                    TextOptChip("中", alignment == TextAlign.Center, Modifier.weight(1f)) { alignment = TextAlign.Center }
                    TextOptChip("右", alignment == TextAlign.Right, Modifier.weight(1f)) { alignment = TextAlign.Right }
                }

                // Font Size Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("字号", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    ReSlider(
                        value = ((fontSize - 12f) / 188f).coerceIn(0f, 1f),
                        onValue = { frac -> fontSize = 12f + frac * 188f },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${fontSize.roundToInt()}pt",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(42.dp),
                    )
                }

                // Live Preview Card
                if (text.isNotBlank()) {
                    Text("效果预览", color = Morandi.subText, fontSize = 11.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 110.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.bg)
                            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = text,
                            color = textColor,
                            fontSize = (fontSize * 0.45f).coerceIn(11f, 32f).sp,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                            textAlign = alignment,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            ReTextButton(
                text = "确定放置",
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text, fontSize.toDouble())
                    }
                    onDismiss()
                },
                textColor = Morandi.accentHi,
            )
        },
        dismissButton = {
            ReTextButton(
                text = "取消",
                onClick = onDismiss,
                textColor = Morandi.subText,
            )
        },
        containerColor = Morandi.panelHi,
    )
}

@Composable
private fun TextOptChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Morandi.accent else Morandi.panel)
            .border(1.dp, if (selected) Morandi.accent else Morandi.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Morandi.text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
