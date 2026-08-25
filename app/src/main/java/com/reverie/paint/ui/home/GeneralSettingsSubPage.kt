/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun GeneralSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit,
    compact: Boolean = false,
) {
    val colors = Theme.current

    val intervalOptions = listOf(
        1 to "1分钟",
        3 to "3分钟",
        5 to "5分钟",
        10 to "10分钟",
        15 to "15分钟",
        30 to "30分钟",
    )

    val undoOptions = listOf(
        30 to "30步",
        50 to "50步 (推荐)",
        100 to "100步",
        200 to "200步",
    )

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 12.dp else 20.dp),
    ) {
        // Back Bar
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "通用设置",
                color = colors.text,
                fontSize = if (compact) 18.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Section 1: 自动保存
        SettingCategoryHeader("自动保存")

        SettingSwitchRow(
            title = "启用自动保存",
            summary = "在绘画过程中按设定时间间隔自动在后台保存作品",
            checked = vm.autoSaveEnabled,
            onCheckedChange = { vm.updateAutoSaveEnabled(it) },
        )

        if (vm.autoSaveEnabled) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "保存时间间隔",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // Segment selector for quick interval selection
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panel)
                        .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                intervalOptions.forEach { (mins, _) ->
                    val isSelected = vm.autoSaveIntervalMinutes == mins
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) colors.accent else Color.Transparent)
                                .clickable { vm.updateAutoSaveIntervalMinutes(mins) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (mins >= 10) "${mins}m" else "${mins}分",
                            color = if (isSelected) Color.White else colors.subText,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            SettingSwitchRow(
                title = "自动保存轻量提示",
                summary = "自动保存成功后在屏幕上方弹出非阻塞提示",
                checked = vm.autoSaveToastEnabled,
                onCheckedChange = { vm.updateAutoSaveToastEnabled(it) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // Info Card
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panelHi)
                    .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painter = painterResource(R.drawable.ic_info_circle),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "说明：自动保存将在后台静默执行，仅在画布产生修改时触发，且绝不会打断您当前的笔画绘制。",
                    color = colors.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(20.dp))

        // Section 2: 历史记录与性能
        SettingCategoryHeader("历史记录与性能")

        SettingDropdownRow(
            title = "最大撤销步数",
            currentText = undoOptions.find { it.first == vm.maxUndoSteps }?.second ?: "${vm.maxUndoSteps}步",
            options = undoOptions.map { it.second },
            onSelect = { idx ->
                vm.updateMaxUndoSteps(undoOptions[idx].first)
            },
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(20.dp))

        // Section 3: 项目管理
        SettingCategoryHeader("项目管理")

        SettingSwitchRow(
            title = "退出时提示保存",
            summary = "若当前画布有未保存的修改，退出到主页时提示保存",
            checked = vm.promptSaveOnExit,
            onCheckedChange = { vm.updatePromptSaveOnExit(it) },
        )

        Spacer(Modifier.height(60.dp))
    }
}
