/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.theme.Theme

/** 手写笔配置弹窗分组卡片容器 (底色为 panelHi，彻底杜绝内部多层嵌套圆角) */
@Composable
internal fun StylusDialogCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Theme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panelHi)
            .padding(vertical = 4.dp),
        content = content,
    )
}

/** 分类分组标题 (莫兰迪强调色) */
@Composable
internal fun StylusDialogSectionTitle(title: String) {
    val colors = Theme.current
    Text(
        text = title,
        color = colors.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** 弹窗头部 (品牌徽标 + 标题 + 圆形关闭按钮) */
@Composable
internal fun StylusDialogHeader(
    badgeText: String,
    title: String,
    onClose: () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accent.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badgeText,
                    color = colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.panelHi)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = stringResource(R.string.common_close),
                tint = colors.subText,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** 平整开关条目 */
@Composable
internal fun StylusDialogSwitchItem(
    title: String,
    summary: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colors.text else colors.subText.copy(alpha = 0.5f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = if (enabled) colors.subText else colors.subText.copy(alpha = 0.4f),
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        ReSwitch(
            checked = checked,
            enabled = enabled,
            onChecked = onCheckedChange,
        )
    }
}

/** 平整下拉选择条目 */
@Composable
internal fun StylusDialogDropdownItem(
    title: String,
    currentText: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    val colors = Theme.current
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = colors.text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panel)
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentText,
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(12.dp),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(colors.panelHi),
            ) {
                options.forEachIndexed { idx, opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt,
                                color = if (opt == currentText) colors.accent else colors.text,
                                fontSize = 13.sp,
                                fontWeight = if (opt == currentText) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            onSelect(idx)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 平整滑块条目 */
@Composable
internal fun StylusDialogSliderItem(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val colors = Theme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = valueText,
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        ReSlider(
            value = value,
            onValue = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 平整导航条目 */
@Composable
internal fun StylusDialogNavItem(
    iconRes: Int? = null,
    title: String,
    summary: String = "",
    onClick: () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = colors.icon,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (summary.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        color = colors.subText,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = colors.subText.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 组内分割线 (左右缩进 16dp) */
@Composable
internal fun StylusDialogDivider() {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.6.dp)
            .background(colors.border.copy(alpha = 0.25f)),
    )
}

/** 底部统一圆角操作按钮 */
@Composable
internal fun StylusDialogDoneButton(
    onClick: () -> Unit,
) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.common_done),
            color = colors.onAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
