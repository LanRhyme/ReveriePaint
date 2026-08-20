package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme

/**
 * High-end Custom ROM / System Info style About Page
 */
@Composable
fun AboutSettingsSubPage(
    onBack: () -> Unit,
    compact: Boolean = false,
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (compact) Color.Transparent else colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 4.dp else 20.dp, vertical = if (compact) 4.dp else 20.dp),
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "关于应用",
                color = colors.text,
                fontSize = if (compact) 15.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Hero App Banner (AOSP / Nothing OS Device Card Style)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Morandi.panelHi,
                            Morandi.panel,
                        )
                    )
                )
                .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
                .padding(if (compact) 14.dp else 20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Stylized Logo Monogram
                    Box(
                        modifier = Modifier
                            .size(if (compact) 44.dp else 56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Morandi.accent,
                                        Morandi.accent.copy(alpha = 0.6f),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "RP",
                            color = Color.White,
                            fontSize = if (compact) 18.sp else 22.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "ReveriePaint Native",
                            color = colors.text,
                            fontSize = if (compact) 15.sp else 19.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Professional Digital Painting Studio",
                            color = Morandi.subText,
                            fontSize = if (compact) 10.sp else 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Tag Pills Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AboutTagPill("Krita 5.3 Core")
                    AboutTagPill("C++20 / Qt6")
                    AboutTagPill("v1.2.0-native")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Section: System Architecture & Core Engine (2x2 Grid)
        Text(
            text = "核心架构与运行时",
            color = Morandi.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AboutSpecCard(
                modifier = Modifier.weight(1f),
                title = "绘制引擎",
                value = "Krita PaintOp",
                detail = "C++ 原生笔刷管线",
                iconRes = R.drawable.ic_brush,
                compact = compact,
            )
            AboutSpecCard(
                modifier = Modifier.weight(1f),
                title = "抖动修正",
                value = "Weighted EMA",
                detail = "距离指数加权平滑",
                iconRes = R.drawable.ic_line,
                compact = compact,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AboutSpecCard(
                modifier = Modifier.weight(1f),
                title = "内存分块",
                value = "Tile Engine",
                detail = "按需换页与平滑合成",
                iconRes = R.drawable.ic_layers,
                compact = compact,
            )
            AboutSpecCard(
                modifier = Modifier.weight(1f),
                title = "目标平台",
                value = "ARM64-v8a",
                detail = "NEON / SIMD 极速指令",
                iconRes = R.drawable.ic_grid,
                compact = compact,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Section: Author & Community
        Text(
            text = "开发者与致谢",
            color = Morandi.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Author row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Morandi.accent.copy(alpha = 0.2f))
                                .border(1.dp, Morandi.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "L",
                                color = Morandi.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "LanRhyme (蓝韵)",
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Lead Developer & UI Designer",
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Morandi.panel)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Author",
                            color = Morandi.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))

                // Acknowledgements
                AboutInfoItem(
                    label = "核心致谢",
                    value = "KDE & Krita Foundation · Qt Project",
                )
                AboutInfoItem(
                    label = "设计灵感",
                    value = "PaintWorld (画世界Pro) · AOSP Custom ROM",
                )
                AboutInfoItem(
                    label = "开源协议",
                    value = "GNU General Public License v3.0 (GPL-3.0)",
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Build Environment Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "系统与环境：Arch Linux (CachyOS) · Android NDK r28",
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
                Text(
                    text = "构建时间：2026 · Reverie Studio Native Build",
                    color = Morandi.subText.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AboutTagPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Morandi.panel)
            .border(1.dp, Morandi.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = Morandi.text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AboutSpecCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    detail: String,
    iconRes: Int,
    compact: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
            .padding(if (compact) 10.dp else 14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = Morandi.subText,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                color = Morandi.text,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                color = Morandi.subText,
                fontSize = if (compact) 9.sp else 11.sp,
            )
        }
    }
}

@Composable
private fun AboutInfoItem(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 11.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            color = Morandi.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
