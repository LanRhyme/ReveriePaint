/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.reverie.paint.MainActivity
import com.reverie.paint.R
import com.reverie.paint.core.CreationStatsManager
import com.reverie.paint.model.CreationStatsSummary
import java.util.Locale

class CreationStatsDashboardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = CreationStatsManager.getSummary(context)

        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accentColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_400)
        } else {
            0xFF879ED1.toInt()
        }
        val emptyColor = if (isDark) 0x22808080 else 0x1A000000

        val heatmapBitmap = HeatmapBitmapRenderer.render(
            dailyActivities = summary.dailyActivities,
            accentColor = accentColor,
            emptyColor = emptyColor,
            weeksCount = 16,
            widthPx = 480,
            heightPx = 210,
        )

        provideContent {
            GlanceTheme {
                DashboardWidgetContent(
                    context = context,
                    summary = summary,
                    heatmapBitmap = heatmapBitmap,
                )
            }
        }
    }

    @Composable
    private fun DashboardWidgetContent(
        context: Context,
        summary: CreationStatsSummary,
        heatmapBitmap: android.graphics.Bitmap,
    ) {
        val galleryIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val createIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_CREATE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val statsIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_STATS)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val totalHours = summary.totalDurationSeconds / 3600
        val totalMins = (summary.totalDurationSeconds % 3600) / 60
        val durationStr = if (totalHours > 0) "${totalHours}h ${totalMins}m" else "${totalMins}m"

        val strokesStr = when {
            summary.totalStrokes >= 10000 -> String.format(Locale.US, "%.1fw", summary.totalStrokes / 10000f)
            summary.totalStrokes >= 1000 -> String.format(Locale.US, "%.1fk", summary.totalStrokes / 1000f)
            else -> summary.totalStrokes.toString()
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(13.dp)
                .clickable(actionStartActivity(galleryIntent)),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left Column: Metrics & Quick Action
                Column(
                    modifier = GlanceModifier
                        .fillMaxHeight()
                        .width(112.dp)
                        .clickable(actionStartActivity(statsIntent)),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Streak badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_star_filled),
                            contentDescription = null,
                            modifier = GlanceModifier.size(13.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                        )
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        Text(
                            text = context.getString(R.string.widget_streak_days, summary.currentStreakDays),
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(4.dp))

                    Text(
                        text = context.getString(R.string.widget_streak_active),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                    )

                    Spacer(modifier = GlanceModifier.height(8.dp))

                    // Duration and strokes info
                    Text(
                        text = context.getString(R.string.widget_total_duration, durationStr),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = context.getString(R.string.widget_total_strokes, strokesStr),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    // Quick Action: New Canvas Button
                    Row(
                        modifier = GlanceModifier
                            .cornerRadius(10.dp)
                            .background(GlanceTheme.colors.primary)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable(actionStartActivity(createIntent)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_plus),
                            contentDescription = null,
                            modifier = GlanceModifier.size(11.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                        )
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        Text(
                            text = context.getString(R.string.widget_new_canvas),
                            style = TextStyle(
                                color = GlanceTheme.colors.onPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                // Right Column: Mini Heatmap Display
                Column(
                    modifier = GlanceModifier
                        .fillMaxHeight()
                        .defaultWeight()
                        .clickable(actionStartActivity(statsIntent)),
                    verticalAlignment = Alignment.Top,
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = context.getString(R.string.widget_recent_activity),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(6.dp))

                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(heatmapBitmap),
                            contentDescription = null,
                            modifier = GlanceModifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
