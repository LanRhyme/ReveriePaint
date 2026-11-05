/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 创作数据 4x2 宽版小组件广播接收器
 */
class CreationStatsDashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CreationStatsDashboardWidget()
}

/**
 * 创作打卡 2x2 紧凑小组件广播接收器
 */
class CreationStatsCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CreationStatsCompactWidget()
}

/**
 * 桌面小组件即时数据刷新辅助类
 */
object CreationStatsWidgetHelper {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun notifyAllWidgets(context: Context) {
        val appCtx = context.applicationContext ?: context
        scope.launch {
            try {
                CreationStatsDashboardWidget().updateAll(appCtx)
                CreationStatsCompactWidget().updateAll(appCtx)
            } catch (_: Exception) {}
        }
    }
}
