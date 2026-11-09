/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.reverie.paint.ui.widget.CreationStatsCompactWidgetReceiver
import com.reverie.paint.ui.widget.CreationStatsDashboardWidgetReceiver
import com.reverie.paint.ui.widget.RecentProjectCompactWidgetReceiver
import com.reverie.paint.ui.widget.RecentProjectDashboardWidgetReceiver
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.reverie.paint.core.CreationStatsManager
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.CreationStatsSummary
import com.reverie.paint.model.DailyActivity
import com.reverie.paint.ui.theme.Theme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun StatisticsSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit = {},
) {
    val colors = Theme.current
    val context = LocalContext.current
    // 清空统计后要立刻重算一次汇总 (getSummary 走文件缓存, 没有可观察状态)
    var statsEpoch by remember { mutableStateOf(0) }
    val stats = remember(vm.projects, statsEpoch) {
        CreationStatsManager.getSummary(context, vm.projects)
    }

    val hasCustomBg = vm.homeCustomBgEnabled && vm.getHomeCustomBgFile() != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (compact || hasCustomBg) Color.Transparent else colors.bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 12.dp else 20.dp),
        ) {
            SettingSubPageHeader(
                title = stringResource(R.string.stats_title),
                subtitle = stringResource(R.string.stats_subtitle),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            Spacer(Modifier.height(8.dp))

            // Section 1: Overview Metrics
            SettingCategoryTitle(stringResource(R.string.stats_category_overview))
            OverviewMetricsCards(stats)

            Spacer(Modifier.height(16.dp))

            // Section 2: GitHub-style Activity Heatmap
            SettingCategoryTitle(stringResource(R.string.stats_heatmap_title))
            ActivityHeatmapCard(stats)

            Spacer(Modifier.height(16.dp))

            // Section 3: Creation Preferences (Canvas Aspect Ratios & Brushes)
            SettingCategoryTitle(stringResource(R.string.stats_category_preferences))
            CreationPreferencesCards(stats)

            Spacer(Modifier.height(16.dp))

            // Section 4: Desktop Widgets (Pin Widget to Home Screen)
            SettingCategoryTitle(stringResource(R.string.stats_pin_widget_title))
            PinWidgetsCard()

            Spacer(Modifier.height(16.dp))

            // Section 5: 清空统计 (数据可能因为旧版本的重复累计而偏大)
            StatsResetCard(
                onReset = {
                    vm.resetCreationStats()
                    statsEpoch++
                },
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/**
 * 当前界面语言。Compose 里要读语言必须走 LocalConfiguration(可观察), 否则切系统语言后
 * 不会重组, 数字单位会一直停在旧语言(lint: NonObservableLocale)。
 * ConfigurationCompat 兼容 minSdk 23(API 24 才有 Configuration.locales)。
 */
@Composable
private fun uiLocale(): Locale =
    androidx.core.os.ConfigurationCompat
        .getLocales(androidx.compose.ui.platform.LocalConfiguration.current)
        .get(0) ?: Locale.US

@Composable
private fun OverviewMetricsCards(stats: CreationStatsSummary) {
    val colors = Theme.current

    val hours = stats.totalDurationSeconds / 3600
    val mins = (stats.totalDurationSeconds % 3600) / 60
    val durationText = if (hours > 0) {
        stringResource(R.string.stats_hours_mins, hours, mins)
    } else {
        stringResource(R.string.stats_mins_only, mins)
    }

    // 语言必须从 LocalConfiguration 读: 直接 Locale.getDefault() 在切换系统语言后不会触发
    // 重组(数字单位会停在旧语言), lint 的 NonObservableLocale 就是查这个
    val uiLocale = uiLocale()
    val isChinese = uiLocale.language == "zh"
    val strokesText = if (isChinese && stats.totalStrokes >= 10000) {
        String.format(uiLocale, "%.1f万", stats.totalStrokes / 10000.0)
    } else if (!isChinese && stats.totalStrokes >= 10000) {
        String.format(uiLocale, "%.1fk", stats.totalStrokes / 1000.0)
    } else {
        String.format(uiLocale, "%,d", stats.totalStrokes)
    }

    val items = listOf(
        Triple(R.drawable.ic_clock, stringResource(R.string.stats_total_duration), durationText),
        Triple(R.drawable.ic_pencil, stringResource(R.string.stats_total_strokes), stringResource(R.string.stats_strokes_unit, strokesText)),
        Triple(R.drawable.ic_image, stringResource(R.string.stats_total_canvases), stringResource(R.string.stats_canvases_unit, stats.totalCanvases)),
        Triple(R.drawable.ic_share, stringResource(R.string.stats_total_exports), stringResource(R.string.stats_times_unit, stats.totalExports)),
        Triple(R.drawable.ic_star, stringResource(R.string.stats_current_streak), stringResource(R.string.stats_days_unit, stats.currentStreakDays)),
        Triple(R.drawable.ic_chart_bar, stringResource(R.string.stats_active_days_year), stringResource(R.string.stats_days_unit, stats.activeDaysPastYear)),
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        for (item in items) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, if (colors.border != Color.Transparent) colors.border else colors.subText.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(item.first),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = item.second,
                            color = colors.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.third,
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 过去 52 周的 GitHub 风格活跃热力图卡片
 */
@Composable
private fun ActivityHeatmapCard(stats: CreationStatsSummary) {
    val colors = Theme.current
    val visibleBorder = if (colors.border != Color.Transparent) colors.border else colors.subText.copy(alpha = 0.12f)
    val todayStr = remember { CreationStatsManager.getTodayString() }
    var selectedDate by remember { mutableStateOf(todayStr) }

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val displayDateSdf = remember { SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()) }
    val monthSdf = remember { SimpleDateFormat("MMM", Locale.getDefault()) }

    // 计算 52 周各单元格日期与分布
    val gridWeeks = remember {
        val weeks = mutableListOf<List<String>>()
        val cal = Calendar.getInstance()
        cal.time = Date()

        // 调整至本周日 (Monday-based: Mon=0 .. Sun=6)
        val dayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0, Sunday = 6
        cal.add(Calendar.DAY_OF_YEAR, 6 - dayOfWeek) // Move to end of current week (Sunday)

        // 生成过去 52 周的数据
        val totalDays = 52 * 7
        cal.add(Calendar.DAY_OF_YEAR, -totalDays + 1) // Start date: 52 weeks ago Monday

        for (w in 0 until 52) {
            val weekDays = mutableListOf<String>()
            for (d in 0 until 7) {
                weekDays.add(sdf.format(cal.time))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            weeks.add(weekDays)
        }
        weeks
    }

    // 计算月份标记 (哪些周属于新月份首周)
    val monthHeaders = remember(gridWeeks) {
        val headers = mutableListOf<Pair<Int, String>>()
        var lastMonth = -1
        val cal = Calendar.getInstance()
        for ((idx, week) in gridWeeks.withIndex()) {
            val firstDay = week.firstOrNull() ?: continue
            val date = try { sdf.parse(firstDay) } catch (_: Exception) { null } ?: continue
            cal.time = date
            val m = cal.get(Calendar.MONTH)
            if (m != lastMonth) {
                lastMonth = m
                headers.add(idx to monthSdf.format(date))
            }
        }
        headers
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(Unit) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel)
            .border(1.dp, visibleBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Column {
            // Header: subtitle & streak badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.stats_heatmap_sub),
                    color = colors.subText,
                    fontSize = 12.sp,
                )

                // Streak tag
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_star_filled),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "${stringResource(R.string.stats_longest_streak)}: ${stringResource(R.string.stats_days_unit, stats.longestStreakDays)}",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Horizontally Scrollable 52-Week Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.Top,
            ) {
                // Day of week labels on left
                Column(
                    modifier = Modifier.padding(end = 6.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val days = listOf(
                        stringResource(R.string.stats_day_mon),
                        "",
                        stringResource(R.string.stats_day_wed),
                        "",
                        stringResource(R.string.stats_day_fri),
                        "",
                        stringResource(R.string.stats_day_sun),
                    )
                    for (name in days) {
                        Box(
                            modifier = Modifier.size(width = 14.dp, height = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (name.isNotEmpty()) {
                                Text(
                                    text = name,
                                    color = colors.subText.copy(alpha = 0.7f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // Grid of 52 weeks
                Column {
                    // Month labels row
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (w in 0 until 52) {
                            val header = monthHeaders.firstOrNull { it.first == w }
                            Box(
                                modifier = Modifier.width(12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (header != null) {
                                    Text(
                                        text = header.second,
                                        color = colors.subText,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }

                    // 7 rows of daily cells
                    for (dayIdx in 0 until 7) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(bottom = 3.dp),
                        ) {
                            for (w in 0 until 52) {
                                val dateStr = gridWeeks[w][dayIdx]
                                val activity = stats.dailyActivities[dateStr]
                                val level = activity?.level ?: 0
                                val isSelected = dateStr == selectedDate
                                val isFuture = dateStr > todayStr

                                val cellColor = when {
                                    isFuture -> Color.Transparent
                                    level == 0 -> colors.subText.copy(alpha = 0.08f)
                                    level == 1 -> colors.accent.copy(alpha = 0.28f)
                                    level == 2 -> colors.accent.copy(alpha = 0.52f)
                                    level == 3 -> colors.accent.copy(alpha = 0.78f)
                                    else -> colors.accent
                                }

                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(RoundedCornerShape(2.5.dp))
                                        .background(cellColor)
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(1.5.dp, colors.text, RoundedCornerShape(2.5.dp))
                                            } else if (!isFuture && level == 0) {
                                                Modifier.border(0.5.dp, visibleBorder, RoundedCornerShape(2.5.dp))
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable(enabled = !isFuture) {
                                            selectedDate = dateStr
                                        },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Footer: Selected day info on left + Legend on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Selected Date Detail Banner
                val selectedActivity = stats.dailyActivities[selectedDate]
                val formattedSelectedDate = try {
                    val d = sdf.parse(selectedDate)
                    if (d != null) displayDateSdf.format(d) else selectedDate
                } catch (_: Exception) {
                    selectedDate
                }

                if (selectedActivity != null && selectedActivity.level > 0) {
                    val dHours = selectedActivity.durationSeconds / 3600
                    val dMins = (selectedActivity.durationSeconds % 3600) / 60
                    val dText = if (dHours > 0) {
                        stringResource(R.string.stats_hours_mins, dHours, dMins)
                    } else {
                        stringResource(R.string.stats_mins_only, dMins)
                    }
                    val strokesStr = String.format(uiLocale(), "%,d", selectedActivity.strokes)
                    Text(
                        text = stringResource(R.string.stats_day_detail, formattedSelectedDate, dText, strokesStr),
                        color = colors.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        text = "$formattedSelectedDate · ${stringResource(R.string.stats_day_no_activity)}",
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                }

                // Legend: Less -> More
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.stats_heatmap_less),
                        color = colors.subText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    val legendLevels = listOf(
                        colors.subText.copy(alpha = 0.08f),
                        colors.accent.copy(alpha = 0.28f),
                        colors.accent.copy(alpha = 0.52f),
                        colors.accent.copy(alpha = 0.78f),
                        colors.accent,
                    )
                    for (c in legendLevels) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c)
                                .border(0.5.dp, visibleBorder, RoundedCornerShape(2.dp)),
                        )
                    }
                    Text(
                        text = stringResource(R.string.stats_heatmap_more),
                        color = colors.subText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 创作偏好卡片（常用画幅比例与常用画笔）
 */
@Composable
private fun CreationPreferencesCards(stats: CreationStatsSummary) {
    val colors = Theme.current
    val visibleBorder = if (colors.border != Color.Transparent) colors.border else colors.subText.copy(alpha = 0.12f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left Card: Aspect Ratios
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.panel)
                .border(1.dp, visibleBorder, RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_rect),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.stats_top_aspect_ratios),
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (stats.topAspectRatios.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_day_no_activity),
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                } else {
                    val maxCount = stats.topAspectRatios.maxOfOrNull { it.second } ?: 1
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((ratio, count) in stats.topAspectRatios) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = ratio,
                                        color = colors.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = stringResource(R.string.stats_canvases_unit, count),
                                        color = colors.subText,
                                        fontSize = 11.sp,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colors.subText.copy(alpha = 0.12f)),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((count.toFloat() / maxCount).coerceIn(0.05f, 1f))
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(colors.accent),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right Card: Top Brushes
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.panel)
                .border(1.dp, visibleBorder, RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.stats_top_brushes),
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (stats.topBrushes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_day_no_activity),
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((brushName, count) in stats.topBrushes) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.bg.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = brushName,
                                    color = colors.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.stats_times_unit, count),
                                    color = colors.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsResetCard(onReset: () -> Unit) {
    val colors = Theme.current
    var confirm by remember { mutableStateOf(false) }
    val visibleBorder = if (colors.border != Color.Transparent) colors.border else colors.subText.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel)
            .border(1.dp, visibleBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.stats_reset_summary),
                color = colors.subText,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { confirm = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.stats_reset_title),
                    color = colors.accent,
                    fontSize = 13.sp,
                )
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = colors.bg,
            titleContentColor = colors.text,
            textContentColor = colors.subText,
            title = { Text(stringResource(R.string.stats_reset_title)) },
            text = { Text(stringResource(R.string.stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReset()
                }) {
                    Text(stringResource(R.string.clear), color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.text)
                }
            },
        )
    }
}

@Composable
private fun PinWidgetsCard() {
    val colors = Theme.current
    val context = LocalContext.current
    val visibleBorder = if (colors.border != Color.Transparent) colors.border else colors.subText.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel)
            .border(1.dp, visibleBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.stats_pin_widget_desc),
                color = colors.subText,
                fontSize = 12.sp,
            )

            // Row 1: Creation Stats Widgets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PinWidgetButton(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_chart_bar,
                    label = stringResource(R.string.stats_pin_dashboard),
                    colors = colors,
                    onClick = { requestPinWidget(context, PinWidgetType.STATS_DASHBOARD) },
                )
                PinWidgetButton(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_star_filled,
                    label = stringResource(R.string.stats_pin_compact),
                    colors = colors,
                    onClick = { requestPinWidget(context, PinWidgetType.STATS_COMPACT) },
                )
            }

            // Row 2: Recent Artwork Widgets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PinWidgetButton(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_palette,
                    label = stringResource(R.string.stats_pin_recent_dashboard),
                    colors = colors,
                    onClick = { requestPinWidget(context, PinWidgetType.RECENT_DASHBOARD) },
                )
                PinWidgetButton(
                    modifier = Modifier.weight(1f),
                    iconRes = R.drawable.ic_brush,
                    label = stringResource(R.string.stats_pin_recent_compact),
                    colors = colors,
                    onClick = { requestPinWidget(context, PinWidgetType.RECENT_COMPACT) },
                )
            }
        }
    }
}

@Composable
private fun PinWidgetButton(
    modifier: Modifier,
    iconRes: Int,
    label: String,
    colors: com.reverie.paint.ui.theme.AppColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent.copy(alpha = 0.12f))
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private enum class PinWidgetType {
    STATS_DASHBOARD,
    STATS_COMPACT,
    RECENT_DASHBOARD,
    RECENT_COMPACT,
}

private fun requestPinWidget(context: android.content.Context, type: PinWidgetType) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
        if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
            val receiverClass = when (type) {
                PinWidgetType.STATS_DASHBOARD -> CreationStatsDashboardWidgetReceiver::class.java
                PinWidgetType.STATS_COMPACT -> CreationStatsCompactWidgetReceiver::class.java
                PinWidgetType.RECENT_DASHBOARD -> RecentProjectDashboardWidgetReceiver::class.java
                PinWidgetType.RECENT_COMPACT -> RecentProjectCompactWidgetReceiver::class.java
            }
            val provider = ComponentName(context, receiverClass)
            appWidgetManager.requestPinAppWidget(provider, null, null)
            Toast.makeText(context, context.getString(R.string.stats_pin_success), Toast.LENGTH_SHORT).show()
            return
        }
    }
    Toast.makeText(context, context.getString(R.string.stats_pin_not_supported), Toast.LENGTH_SHORT).show()
}
