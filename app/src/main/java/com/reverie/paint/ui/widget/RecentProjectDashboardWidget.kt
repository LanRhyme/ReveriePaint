/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.layout.ContentScale
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
import java.util.Locale

/**
 * 最近画作展台 4x2 宽版小组件
 */
class RecentProjectDashboardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = RecentProjectWidgetHelper.getLatestProjectDisplayData(context)

        provideContent {
            GlanceTheme {
                DashboardContent(
                    context = context,
                    data = data,
                )
            }
        }
    }

    @Composable
    private fun DashboardContent(
        context: Context,
        data: RecentProjectDisplayData?,
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
        val openProjectIntent = if (data != null) {
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_OPEN_PROJECT)
                putExtra(MainActivity.EXTRA_PROJECT_PATH, data.filePath)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            createIntent
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(if (data != null) openProjectIntent else galleryIntent)),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: Artwork Cover Thumbnail
                Box(
                    modifier = GlanceModifier
                        .size(width = 96.dp, height = 96.dp)
                        .cornerRadius(14.dp)
                        .background(GlanceTheme.colors.surfaceVariant)
                        .clickable(actionStartActivity(if (data != null) openProjectIntent else createIntent)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (data?.thumbnailBitmap != null) {
                        Image(
                            provider = ImageProvider(data.thumbnailBitmap),
                            contentDescription = data.name,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier.fillMaxSize().cornerRadius(14.dp),
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.drawable.ic_palette),
                            contentDescription = null,
                            modifier = GlanceModifier.size(32.dp),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(12.dp))

                // Right: Details & Action buttons
                Column(
                    modifier = GlanceModifier
                        .fillMaxHeight()
                        .defaultWeight()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (data != null) {
                        // Title
                        Text(
                            text = data.name,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )

                        Spacer(modifier = GlanceModifier.height(4.dp))

                        // Dimensions
                        Text(
                            text = "${data.width} × ${data.height} px",
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 11.sp,
                            ),
                            maxLines = 1,
                        )

                        Spacer(modifier = GlanceModifier.height(2.dp))

                        // Duration & Strokes
                        val totalHours = data.elapsedSeconds / 3600
                        val totalMins = (data.elapsedSeconds % 3600) / 60
                        val durationStr = if (totalHours > 0) "${totalHours}h ${totalMins}m" else "${totalMins}m"
                        val strokesStr = when {
                            data.strokeCount >= 10000 -> String.format(Locale.US, "%.1fw", data.strokeCount / 10000f)
                            data.strokeCount >= 1000 -> String.format(Locale.US, "%.1fk", data.strokeCount / 1000f)
                            else -> data.strokeCount.toString()
                        }
                        Text(
                            text = context.getString(R.string.widget_recent_duration_strokes, durationStr, strokesStr),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 10.sp,
                            ),
                            maxLines = 1,
                        )

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        // Actions Row
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Continue Painting Primary Button
                            Row(
                                modifier = GlanceModifier
                                    .cornerRadius(10.dp)
                                    .background(GlanceTheme.colors.primary)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .clickable(actionStartActivity(openProjectIntent)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_brush),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(11.dp),
                                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
                                )
                                Spacer(modifier = GlanceModifier.width(4.dp))
                                Text(
                                    text = context.getString(R.string.widget_recent_continue_painting),
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            // Gallery Button
                            Row(
                                modifier = GlanceModifier
                                    .cornerRadius(10.dp)
                                    .background(GlanceTheme.colors.surfaceVariant)
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                                    .clickable(actionStartActivity(galleryIntent)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = context.getString(R.string.widget_recent_open_gallery),
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                            }
                        }
                    } else {
                        // Empty State
                        Text(
                            text = context.getString(R.string.widget_recent_no_project),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )

                        Spacer(modifier = GlanceModifier.height(3.dp))

                        Text(
                            text = context.getString(R.string.widget_recent_no_project_desc),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 11.sp,
                            ),
                            maxLines = 1,
                        )

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        Row(
                            modifier = GlanceModifier
                                .cornerRadius(10.dp)
                                .background(GlanceTheme.colors.primary)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
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
                }
            }
        }
    }
}
