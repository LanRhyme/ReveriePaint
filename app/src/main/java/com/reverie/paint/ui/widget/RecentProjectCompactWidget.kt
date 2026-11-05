/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.reverie.paint.MainActivity
import com.reverie.paint.R

/**
 * 最近画作 2x2 沉浸微型画框小组件
 */
class RecentProjectCompactWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = RecentProjectWidgetHelper.getLatestProjectDisplayData(context)

        provideContent {
            GlanceTheme {
                CompactContent(
                    context = context,
                    data = data,
                )
            }
        }
    }

    @Composable
    private fun CompactContent(
        context: Context,
        data: RecentProjectDisplayData?,
    ) {
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
                .clickable(actionStartActivity(if (data != null) openProjectIntent else createIntent)),
            contentAlignment = Alignment.Center,
        ) {
            if (data?.thumbnailBitmap != null) {
                // Artwork cover fills the card
                Image(
                    provider = ImageProvider(data.thumbnailBitmap),
                    contentDescription = data.name,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(20.dp),
                )

                // Top-right subtle brush badge
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = GlanceModifier
                            .size(24.dp)
                            .cornerRadius(12.dp)
                            .background(ColorProvider(Color(0x991E1E22))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_brush),
                            contentDescription = null,
                            modifier = GlanceModifier.size(12.dp),
                            colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                        )
                    }
                }

                // Bottom floating Morandi frosted pill
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .cornerRadius(12.dp)
                            .background(ColorProvider(Color(0xD91E1E22)))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Column {
                            Text(
                                text = data.name,
                                style = TextStyle(
                                    color = ColorProvider(Color.White),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                            )
                            val strokesStr = when {
                                data.strokeCount >= 10000 -> String.format(java.util.Locale.US, "%.1fw", data.strokeCount / 10000f)
                                data.strokeCount >= 1000 -> String.format(java.util.Locale.US, "%.1fk", data.strokeCount / 1000f)
                                else -> data.strokeCount.toString()
                            }
                            val subInfo = if (data.strokeCount > 0) "${data.width} × ${data.height} · $strokesStr" else "${data.width} × ${data.height}"
                            Text(
                                text = subInfo,
                                style = TextStyle(
                                    color = ColorProvider(Color(0xB3FFFFFF)),
                                    fontSize = 9.sp,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else {
                // Empty state
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_palette),
                        contentDescription = null,
                        modifier = GlanceModifier.size(32.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    )
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Text(
                        text = context.getString(R.string.widget_recent_no_project),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Spacer(modifier = GlanceModifier.height(3.dp))
                    Text(
                        text = context.getString(R.string.widget_new_canvas),
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}
