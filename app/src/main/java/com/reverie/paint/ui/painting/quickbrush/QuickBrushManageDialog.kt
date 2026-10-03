/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.quickbrush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.painting.brush.rememberPresetThumb
import com.reverie.paint.ui.theme.Theme

/**
 * 快捷笔刷设置与排序对话框 (QuickBrushManageDialog)
 * 极速秒开：无全库冗余计算，聚焦于排列方向、最大长度上限设置、添加提示与常用笔刷排序
 */
@Composable
fun QuickBrushManageDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val favoritePresets = remember(vm.brushPresets, vm.favoriteBrushNames, vm.quickBrushOrder) {
        vm.getOrderedFavoriteBrushes()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panel)
                .border(1.dp, colors.panelHi, RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.quick_brush_manage_title),
                            color = colors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.quick_brush_manage_desc),
                            color = colors.subText,
                            fontSize = 12.sp,
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = colors.subText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // 2. 布局方向设置
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.panelHi.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.quick_brush_orientation),
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.panel)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val isHoriz = vm.quickBrushOrientation == "horizontal"

                        // 横向单排选项
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isHoriz) colors.accent.copy(alpha = 0.22f) else colors.panelHi.copy(alpha = 0f))
                                .clickable {
                                    vm.quickBrushOrientation = "horizontal"
                                    vm.persistQuickBrushState()
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ViewStream,
                                    contentDescription = null,
                                    tint = if (isHoriz) colors.accent else colors.subText,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.quick_brush_orientation_horizontal),
                                    color = if (isHoriz) colors.accent else colors.text,
                                    fontSize = 12.sp,
                                    fontWeight = if (isHoriz) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }

                        // 纵向单列选项
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isHoriz) colors.accent.copy(alpha = 0.22f) else colors.panelHi.copy(alpha = 0f))
                                .clickable {
                                    vm.quickBrushOrientation = "vertical"
                                    vm.persistQuickBrushState()
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ViewColumn,
                                    contentDescription = null,
                                    tint = if (!isHoriz) colors.accent else colors.subText,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.quick_brush_orientation_vertical),
                                    color = if (!isHoriz) colors.accent else colors.text,
                                    fontSize = 12.sp,
                                    fontWeight = if (!isHoriz) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // 3. 最大长度设置 (容量与滚动限制)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.panelHi.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.quick_brush_max_length),
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.quick_brush_max_length_desc),
                                color = colors.subText,
                                fontSize = 11.sp,
                            )
                        }

                        // 步进调节器
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panel)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    if (vm.quickBrushMaxLength > 3) {
                                        vm.quickBrushMaxLength--
                                        vm.persistQuickBrushState()
                                    }
                                },
                                modifier = Modifier.size(26.dp),
                                enabled = vm.quickBrushMaxLength > 3,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Remove,
                                    contentDescription = "Decrease",
                                    tint = if (vm.quickBrushMaxLength > 3) colors.text else colors.subText.copy(alpha = 0.4f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }

                            Text(
                                text = stringResource(R.string.quick_brush_count_format, vm.quickBrushMaxLength),
                                color = colors.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )

                            IconButton(
                                onClick = {
                                    if (vm.quickBrushMaxLength < 16) {
                                        vm.quickBrushMaxLength++
                                        vm.persistQuickBrushState()
                                    }
                                },
                                modifier = Modifier.size(26.dp),
                                enabled = vm.quickBrushMaxLength < 16,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Increase",
                                    tint = if (vm.quickBrushMaxLength < 16) colors.text else colors.subText.copy(alpha = 0.4f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    // 快速档位选项
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val quickLimits = listOf(4, 6, 8, 10, 12)
                        quickLimits.forEach { limit ->
                            val isSel = vm.quickBrushMaxLength == limit
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) colors.accent.copy(alpha = 0.22f) else colors.panel)
                                    .border(
                                        width = if (isSel) 1.dp else 0.5.dp,
                                        color = if (isSel) colors.accent else colors.border.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable {
                                        vm.quickBrushMaxLength = limit
                                        vm.persistQuickBrushState()
                                    }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$limit",
                                    color = if (isSel) colors.accent else colors.subText,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // 4. 添加画笔提示横幅 (极简指引)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.accent.copy(alpha = 0.1f))
                        .border(1.dp, colors.accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.quick_brush_add_tip),
                        color = colors.text,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ReTextButton(
                        text = stringResource(R.string.quick_brush_open_brush_panel),
                        onClick = {
                            onDismiss()
                            vm.brushPanelOpen = true
                        },
                    )
                }

                // 5. 常用笔刷列表与排序
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${stringResource(R.string.quick_brush_favorites_list)} (${favoritePresets.size})",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )

                        if (favoritePresets.isNotEmpty()) {
                            Text(
                                text = "支持上下调整顺序",
                                color = colors.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    if (favoritePresets.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panelHi.copy(alpha = 0.3f))
                                .border(1.dp, colors.border.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = colors.subText.copy(alpha = 0.6f),
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = stringResource(R.string.quick_brush_empty),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panelHi.copy(alpha = 0.35f))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(favoritePresets, key = { _, it -> it.name }) { index, preset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.panel)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 缩略图
                                    val thumbBmp = rememberPresetThumb(preset.name, preset.thumbBytes)
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(colors.panelHi),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (thumbBmp != null) {
                                            Image(
                                                bitmap = thumbBmp.asImageBitmap(),
                                                contentDescription = preset.name,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_brush),
                                                contentDescription = preset.name,
                                                tint = colors.icon,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }

                                    // 笔刷名称与分组
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.name,
                                            color = colors.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = preset.group.ifBlank { "常用" },
                                            color = colors.subText,
                                            fontSize = 10.sp,
                                        )
                                    }

                                    // 上移按钮
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val list = favoritePresets.map { it.name }.toMutableList()
                                                val item = list.removeAt(index)
                                                list.add(index - 1, item)
                                                vm.quickBrushOrder = list
                                                vm.persistQuickBrushState()
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowUpward,
                                            contentDescription = "Move Up",
                                            tint = if (index > 0) colors.icon else colors.subText.copy(alpha = 0.3f),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }

                                    // 下移按钮
                                    IconButton(
                                        onClick = {
                                            if (index < favoritePresets.size - 1) {
                                                val list = favoritePresets.map { it.name }.toMutableList()
                                                val item = list.removeAt(index)
                                                list.add(index + 1, item)
                                                vm.quickBrushOrder = list
                                                vm.persistQuickBrushState()
                                            }
                                        },
                                        enabled = index < favoritePresets.size - 1,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowDownward,
                                            contentDescription = "Move Down",
                                            tint = if (index < favoritePresets.size - 1) colors.icon else colors.subText.copy(alpha = 0.3f),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }

                                    // 移除按钮
                                    IconButton(
                                        onClick = {
                                            vm.toggleFavoriteBrush(preset.name)
                                            vm.quickBrushOrder = vm.quickBrushOrder - preset.name
                                            vm.persistQuickBrushState()
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Remove",
                                            tint = colors.subText,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
