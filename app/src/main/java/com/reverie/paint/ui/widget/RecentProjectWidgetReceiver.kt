/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 最近画作 4x2 宽版展台广播接收器
 */
class RecentProjectDashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentProjectDashboardWidget()
}

/**
 * 最近画作 2x2 沉浸画框广播接收器
 */
class RecentProjectCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentProjectCompactWidget()
}
