/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.reverie.paint.core.CreationStatsManager
import com.reverie.paint.model.DailyActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件微型活跃热力图渲染器 (GitHub-style Mini Activity Heatmap Renderer)
 * 将最近 16 周的打卡网格渲染至独立轻量 Bitmap 中供 Glance ImageProvider 消费
 */
object HeatmapBitmapRenderer {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun render(
        dailyActivities: Map<String, DailyActivity>,
        accentColor: Int,
        emptyColor: Int,
        weeksCount: Int = 16,
        widthPx: Int = 420,
        heightPx: Int = 180,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val rows = 7
        val cols = weeksCount

        val padX = 4f
        val padY = 4f
        val spacing = 4f
        val availableW = widthPx - padX * 2
        val availableH = heightPx - padY * 2
        val cellW = (availableW - (cols - 1) * spacing) / cols
        val cellH = (availableH - (rows - 1) * spacing) / rows
        val cornerRadius = Math.min(cellW, cellH) * 0.25f

        val todayStr = CreationStatsManager.getTodayString()
        val cal = Calendar.getInstance()
        cal.time = Date()
        val dayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0, Sunday = 6
        cal.add(Calendar.DAY_OF_YEAR, 6 - dayOfWeek) // Current week's Sunday

        val totalDays = weeksCount * 7
        cal.add(Calendar.DAY_OF_YEAR, -totalDays + 1) // Start: weeksCount ago Monday

        val rect = RectF()
        for (w in 0 until cols) {
            for (d in 0 until rows) {
                val dateStr = dateFormat.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 1)

                val isFuture = dateStr > todayStr
                if (isFuture) continue

                val activity = dailyActivities[dateStr]
                val level = activity?.level ?: 0

                val left = padX + w * (cellW + spacing)
                val top = padY + d * (cellH + spacing)
                rect.set(left, top, left + cellW, top + cellH)

                val cellColor = when (level) {
                    0 -> emptyColor
                    1 -> adjustAlpha(accentColor, 0.28f)
                    2 -> adjustAlpha(accentColor, 0.52f)
                    3 -> adjustAlpha(accentColor, 0.78f)
                    else -> accentColor
                }

                fillPaint.color = cellColor
                fillPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            }
        }

        return bitmap
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val originalAlpha = (color ushr 24) and 0xFF
        val newAlpha = (originalAlpha * factor).toInt().coerceIn(0, 255)
        return (newAlpha shl 24) or (color and 0x00FFFFFF)
    }
}
