/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.commitTypographyToCanvas
import com.reverie.paint.model.TypographyConfig
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/**
 * 画布内富文本排版控制面板与交互浮窗 (In-place Typography Editor)
 */
@Composable
fun TypographyPanel(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val cfg = vm.typographyConfig
    val panelShape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .width(330.dp)
            .shadow(16.dp, panelShape, spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(panelShape)
            .background(Morandi.panel.copy(alpha = vm.popupPanelOpacity))
            .then(
                if (vm.blurBackground && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(vm.popupPanelOpacity))
                } else Modifier
            )
            .border(1.dp, Morandi.border, panelShape)
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header with Commit and Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "文字排版 (Typography)",
                    color = Morandi.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Morandi.accent)
                            .clickable { vm.commitTypographyToCanvas() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("完成", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Morandi.border.copy(alpha = 0.4f))
                            .clickable { vm.isTypographyEditing = false }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消", color = Morandi.subText, fontSize = 12.sp)
                    }
                }
            }

            // Editable Input Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.bg.copy(alpha = 0.6f))
                    .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                BasicTextField(
                    value = cfg.text,
                    onValueChange = { vm.typographyConfig = cfg.copy(text = it) },
                    textStyle = TextStyle(
                        color = Morandi.text,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(Morandi.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Font Family Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("默认" to "系统默认", "衬线" to "衬线体", "等宽" to "等宽体", "手写" to "手写体").forEach { (short, full) ->
                    val sel = cfg.fontFamilyName == full
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) Morandi.accent else Morandi.border.copy(alpha = 0.35f))
                            .clickable { vm.typographyConfig = cfg.copy(fontFamilyName = full) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(short, color = if (sel) Color.White else Morandi.text, fontSize = 11.sp)
                    }
                }
            }

            // Style Toggles: Bold, Italic, Underline, All-Caps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StyleToggle("B", cfg.isBold, Modifier.weight(1f)) {
                    vm.typographyConfig = cfg.copy(isBold = !cfg.isBold)
                }
                StyleToggle("I", cfg.isItalic, Modifier.weight(1f)) {
                    vm.typographyConfig = cfg.copy(isItalic = !cfg.isItalic)
                }
                StyleToggle("U", cfg.isUnderline, Modifier.weight(1f)) {
                    vm.typographyConfig = cfg.copy(isUnderline = !cfg.isUnderline)
                }
                StyleToggle("aA", cfg.isAllCaps, Modifier.weight(1f)) {
                    vm.typographyConfig = cfg.copy(isAllCaps = !cfg.isAllCaps)
                }
            }

            // Alignment: Left, Center, Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0 to "左对齐", 1 to "居中", 2 to "右对齐").forEach { (alignVal, label) ->
                    val sel = cfg.alignment == alignVal
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) Morandi.accent.copy(alpha = 0.2f) else Morandi.border.copy(alpha = 0.3f))
                            .border(1.dp, if (sel) Morandi.accent else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { vm.typographyConfig = cfg.copy(alignment = alignVal) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (sel) Morandi.accent else Morandi.text, fontSize = 11.sp)
                    }
                }
            }

            // Font Size Slider
            ToolFloatSlider(
                label = "字号",
                valueText = "${cfg.fontSize.roundToInt()}sp",
                range = 12f..160f,
                value = cfg.fontSize,
                onValue = { vm.typographyConfig = cfg.copy(fontSize = it) },
            )

            // Letter Spacing (Kerning) Slider
            ToolFloatSlider(
                label = "字间距",
                valueText = "${cfg.letterSpacingSp.roundToInt()}sp",
                range = -2f..24f,
                value = cfg.letterSpacingSp,
                onValue = { vm.typographyConfig = cfg.copy(letterSpacingSp = it) },
            )

            // Line Height (Leading) Slider
            ToolFloatSlider(
                label = "行距倍数",
                valueText = String.format("%.1fx", cfg.lineHeightMultiplier),
                range = 0.8f..2.5f,
                value = cfg.lineHeightMultiplier,
                onValue = { vm.typographyConfig = cfg.copy(lineHeightMultiplier = it) },
            )
        }
    }
}

@Composable
private fun StyleToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Morandi.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
