/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReSlider
import androidx.compose.ui.draw.scale
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor
/**
 * 计算连体卡片分组的各子项形状 (对齐关于页面与现代移动端卡片规范)
 * 顶部项: 上大圆角(18dp) 下小微圆角(4dp)
 * 中间项: 四角微圆角(4dp)
 * 底部项: 上小微圆角(4dp) 下大圆角(18dp)
 * 单独项: 四角完整大圆角(18dp)
 */
fun settingGroupShape(index: Int, total: Int, radius: Dp = 18.dp, smallRadius: Dp = 4.dp): RoundedCornerShape {
    return when {
        total <= 1 -> RoundedCornerShape(radius)
        index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = smallRadius, bottomEnd = smallRadius)
        index == total - 1 -> RoundedCornerShape(topStart = smallRadius, topEnd = smallRadius, bottomStart = radius, bottomEnd = radius)
        else -> RoundedCornerShape(smallRadius)
    }
}

/** 分类分组标题 (莫兰迪强调色) */
@Composable
internal fun SettingCategoryTitle(title: String) {
    val colors = Theme.current
    Text(
        text = title,
        color = colors.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** 连体卡片垂直排布容器 (统一 3dp 间隙) */
@Composable
internal fun SettingGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

/** 通用设置卡片底衬容器 */
@Composable
internal fun SettingCardBox(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = Theme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        content()
    }
}

/** 智能图标渲染组件 (支持 ImageVector / drawable res id) */
@Composable
internal fun SettingIcon(
    icon: Any?,
    modifier: Modifier = Modifier,
    tint: Color = Theme.current.icon,
) {
    when (icon) {
        is ImageVector -> {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = modifier.size(22.dp),
            )
        }
        is Int -> {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = modifier.size(22.dp),
            )
        }
        else -> {}
    }
}

/**
 * 导航跳转卡片 (图标 + 标题 + 副标题 + 徽章 + Chevron)
 */
@Composable
internal fun SettingNavGroupItem(
    icon: Any? = null,
    title: String,
    summary: String = "",
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    badge: String? = null,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    SettingCardBox(shape = shape, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (icon != null) {
                    SettingIcon(icon = icon, tint = colors.icon)
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = colors.subText.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 开关项卡片 (图标 + 标题 + 副标题 + ReSwitch，点击整行触发)
 */
@Composable
internal fun SettingSwitchGroupItem(
    icon: Any? = null,
    title: String,
    summary: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = Theme.current
    SettingCardBox(
        shape = shape,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (icon != null) {
                    SettingIcon(
                        icon = icon,
                        tint = if (enabled) colors.icon else colors.icon.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = if (enabled) colors.text else colors.subText.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            color = if (enabled) colors.subText else colors.subText.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ReSwitch(
                checked = checked,
                onChecked = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}

/**
 * 下拉选择卡片 (图标 + 标题 + 副标题 + 胶囊下拉按钮)
 */
@Composable
internal fun SettingDropdownGroupItem(
    icon: Any? = null,
    title: String,
    summary: String = "",
    currentText: String,
    options: List<String>,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    onSelect: (Int) -> Unit,
) {
    val colors = Theme.current
    var expanded by remember { mutableStateOf(false) }

    SettingCardBox(shape = shape, onClick = { expanded = true }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (icon != null) {
                    SettingIcon(icon = icon, tint = colors.icon)
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panelHi)
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
}

/**
 * 滑块调节卡片 (图标 + 标题 + 副标题 + 强调色数值 + ReSlider)
 */
@Composable
internal fun SettingSliderGroupItem(
    icon: Any? = null,
    title: String,
    summary: String = "",
    valueText: String,
    sliderFraction: Float,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    onValueChange: (Float) -> Unit,
) {
    val colors = Theme.current
    SettingCardBox(shape = shape) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (icon != null) {
                        SettingIcon(icon = icon, tint = colors.icon)
                        Spacer(Modifier.width(14.dp))
                    }
                    Column {
                        Text(
                            text = title,
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (summary.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = summary,
                                color = colors.subText,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
                Text(
                    text = valueText,
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            ReSlider(
                value = sliderFraction.coerceIn(0f, 1f),
                onValue = onValueChange,
            )
        }
    }
}

/**
 * 分段选择卡片 (图标 + 标题 + 副标题 + 胶囊分段栏)
 */
@Composable
internal fun <T> SettingSegmentGroupItem(
    icon: Any? = null,
    title: String,
    summary: String = "",
    options: List<Pair<T, String>>,
    selected: T,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    onSelect: (T) -> Unit,
) {
    val colors = Theme.current
    SettingCardBox(shape = shape) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    SettingIcon(icon = icon, tint = colors.icon)
                    Spacer(Modifier.width(14.dp))
                }
                Column {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panelHi)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                options.forEach { (key, label) ->
                    val isSelected = key == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) colors.accent else Color.Transparent)
                            .clickable { onSelect(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else colors.subText,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统一信息/说明卡片 (带莫兰迪竖条标示，与关于页面软件介绍卡片完全一致)
 */
@Composable
internal fun SettingInfoCard(
    title: String = stringResource(R.string.common_info),
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.panel)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                color = colors.subText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/**
 * 统一子页面导航标题栏 (圆形返回按钮与标题)
 */
@Composable
internal fun SettingSubPageHeader(
    title: String,
    subtitle: String = "",
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit = {},
) {
    val colors = Theme.current
    if (showBackButton) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.panel)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.common_back),
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = if (compact) 16.sp else 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    } else if (subtitle.isNotBlank()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 12.dp),
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.subText,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * 手写笔硬件设备卡片 (集成连体卡片样式)
 */
@Composable
internal fun SettingStylusDeviceRow(
    title: String,
    summary: String,
    isCurrentDevice: Boolean,
    isConnected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    onClick: (() -> Unit)? = null,
) {
    val colors = Theme.current
    SettingCardBox(shape = shape, onClick = onClick) {
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
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isConnected) colors.accent.copy(alpha = 0.12f) else colors.panelHi),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil),
                        contentDescription = null,
                        tint = if (isConnected) colors.accent else colors.subText,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (isConnected) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.accent.copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.stylus_connected),
                                    color = colors.accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else if (isCurrentDevice) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.panelHi)
                                    .padding(horizontal = 7.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.stylus_model_supported),
                                    color = colors.subText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = colors.subText.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 平板横屏左侧 Master 导航项 */
@Composable
internal fun SettingMasterNavRow(
    iconRes: Int,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (isSelected) colors.accent else colors.subText,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = if (isSelected) colors.accent else colors.text,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 自定义颜色选取弹窗 (已废弃，统一使用 CompactColorPickerPopup) */
@Deprecated(
    "Use CompactColorPickerPopup instead",
    ReplaceWith("CompactColorPickerPopup(initialHex = initialHex, onColorConfirmed = onConfirm, onDismiss = onDismiss)")
)
@Composable
internal fun CustomColorDialog(
    initialHex: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    com.reverie.paint.ui.painting.panels.CompactColorPickerPopup(
        title = stringResource(R.string.color_custom),
        initialHex = initialHex,
        onColorConfirmed = onConfirm,
        onDismiss = onDismiss,
    )
}

// =========================================================================
// 兼容性留存 (供旧组件平滑过渡)
// =========================================================================

@Composable
internal fun SettingCategoryHeader(title: String) {
    SettingCategoryTitle(title)
}

@Composable
internal fun GroupedSettingsCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Theme.current.panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
internal fun SettingsCardDivider() {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(colors.border.copy(alpha = 0.25f)),
    )
}

@Composable
internal fun SettingNavRow(
    iconRes: Int? = null,
    title: String,
    summary: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    SettingNavGroupItem(
        icon = iconRes,
        title = title,
        summary = summary,
        shape = RoundedCornerShape(14.dp),
        badge = badge,
        onClick = onClick,
    )
}

@Composable
internal fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingSwitchGroupItem(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun SettingDropdownRow(
    title: String,
    currentText: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    SettingDropdownGroupItem(
        title = title,
        currentText = currentText,
        options = options,
        shape = RoundedCornerShape(14.dp),
        onSelect = onSelect,
    )
}

@Composable
internal fun SettingSliderRow(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    SettingSliderGroupItem(
        title = title,
        summary = summary,
        valueText = "${(value * 100).toInt()}%",
        sliderFraction = ((value - 0.2f) / 0.8f).coerceIn(0f, 1f),
        shape = RoundedCornerShape(14.dp),
        onValueChange = { f -> onValueChange((0.2f + f * 0.8f).coerceIn(0.2f, 1f)) },
    )
}

