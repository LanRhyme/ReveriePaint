/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.home.GroupedSettingsCard
import com.reverie.paint.ui.home.SettingCategoryHeader
import com.reverie.paint.ui.home.SettingDropdownRow
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun HuaweiStylusConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val actionOptions = listOf(
        "切换画笔与橡皮" to "toggle_eraser",
        "撤销" to "undo",
        "重做" to "redo",
        "吸管取色" to "tool_picker",
        "切换上一工具" to "toggle_last_tool",
        "快捷调色盘" to "tool_color",
        "无操作" to "none",
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "华为 M-Pencil 专属设置",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            tint = colors.subText,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 设备硬件规格简报
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.panelHi.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "HUAWEI M-Pencil (星闪 / 蓝牙)",
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "NearLink星闪支持",
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "万级超高压感采样 · 侧边360°隐形触控 · 微秒级超低时延连接",
                            color = colors.subText,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("触控手势动作")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val currentTitle = actionOptions.find { it.second == vm.oppoDoubleTapAction }?.first ?: "切换画笔与橡皮"
                    SettingDropdownRow(
                        title = "双击笔身动作",
                        currentText = currentTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateOppoDoubleTapAction(actionOptions[idx].second)
                        },
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton("完成", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
