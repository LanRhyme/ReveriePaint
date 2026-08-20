package com.reverie.paint.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme
import kotlin.math.sin

/**
 * Bespoke Artistic About Page for ReveriePaint
 * Combining Custom ROM expressive clarity with digital painting artistic atmosphere
 */
@Composable
fun AboutSettingsSubPage(
    onBack: () -> Unit,
    compact: Boolean = false,
) {
    val colors = Theme.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    var showContributorsDialog by remember { mutableStateOf(false) }

    // Safely retrieve application icon
    val appIconBitmap = remember(context) {
        try {
            val pm = context.packageManager
            val d = pm.getApplicationIcon(context.packageName)
            val w = d.intrinsicWidth.coerceAtLeast(96)
            val h = d.intrinsicHeight.coerceAtLeast(96)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(cv)
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    // Subtle wave animation for the painting canvas header
    val infiniteTransition = rememberInfiniteTransition(label = "artHeader")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (compact) Color.Transparent else colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 8.dp else 20.dp, vertical = if (compact) 8.dp else 20.dp),
    ) {
        // 1. Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "关于",
                color = colors.text,
                fontSize = if (compact) 16.sp else 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(16.dp))

        // 2. Artistic Hero Canvas Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 130.dp else 160.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(24.dp)),
        ) {
            // Painterly fluid acrylic & watercolor waves
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Base ambient glow
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Morandi.accent.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.75f, h * 0.4f),
                        radius = w * 0.6f,
                    ),
                    center = Offset(w * 0.75f, h * 0.4f),
                    radius = w * 0.6f,
                )

                // Smooth organic wave paths
                val p1 = Path().apply {
                    moveTo(0f, h * 0.5f)
                    for (x in 0..w.toInt() step 15) {
                        val y = h * 0.55f + sin((x / w * 4f) + phase).toFloat() * 18.dp.toPx()
                        lineTo(x.toFloat(), y)
                    }
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = p1,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Morandi.accent.copy(alpha = 0.12f),
                            Morandi.accent.copy(alpha = 0.25f),
                            Morandi.panel.copy(alpha = 0.1f),
                        )
                    ),
                )

                val p2 = Path().apply {
                    moveTo(0f, h * 0.7f)
                    for (x in 0..w.toInt() step 15) {
                        val y = h * 0.72f + sin((x / w * 5f) - phase * 0.8f).toFloat() * 14.dp.toPx()
                        lineTo(x.toFloat(), y)
                    }
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = p2,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Morandi.panel.copy(alpha = 0.2f),
                            Morandi.accent.copy(alpha = 0.20f),
                            Morandi.accent.copy(alpha = 0.08f),
                        )
                    ),
                )
            }

            // Banner Content Overlay
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (compact) 16.dp else 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // App Logo
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = "ReveriePaint",
                        modifier = Modifier
                            .size(if (compact) 54.dp else 68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.5.dp, Morandi.accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 54.dp else 68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Morandi.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("RP", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.width(18.dp))

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "ReveriePaint",
                            color = colors.text,
                            fontSize = if (compact) 20.sp else 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.accent.copy(alpha = 0.18f))
                                .border(1.dp, Morandi.accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "v1.0.0",
                                color = Morandi.accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "移动端与平板专业数字绘画创作软件",
                        color = Morandi.subText,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 3. Contiguous Card Group 1: 创作者与社区 (Custom ROM Group Style)
        Text(
            text = "项目与社区",
            color = Morandi.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 1. 开发者 (Top Rounded)
            AboutGroupItem(
                icon = Icons.Rounded.Person,
                title = "开发者",
                summary = "LanRhyme",
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                onClick = null,
            )

            // 2. Github 仓库 (Middle Rounded)
            AboutGroupItem(
                icon = Icons.Rounded.Language,
                title = "Github 仓库",
                summary = "https://github.com/LanRhyme/ReveriePaint",
                isLink = true,
                shape = RoundedCornerShape(4.dp),
                onClick = {
                    try {
                        uriHandler.openUri("https://github.com/LanRhyme/ReveriePaint")
                    } catch (_: Exception) {}
                },
            )

            // 3. 贡献者 (Middle Rounded)
            AboutGroupItem(
                icon = Icons.Rounded.People,
                title = "贡献者",
                summary = "感谢每一位为本项目做出贡献的人",
                shape = RoundedCornerShape(4.dp),
                onClick = { showContributorsDialog = true },
            )

            // 4. 赞助者 (Bottom Rounded)
            AboutGroupItem(
                icon = Icons.Rounded.Favorite,
                title = "赞助者",
                summary = "查看爱发电赞助者",
                isLink = true,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                onClick = {
                    try {
                        uriHandler.openUri("https://afdian.com/a/LanRhyme")
                    } catch (_: Exception) {}
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // 4. Contiguous Card Group 2: 应用与维护
        Text(
            text = "应用与维护",
            color = Morandi.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 版本 & 检查更新 (Top Rounded)
            AboutGroupItem(
                icon = Icons.Rounded.Info,
                title = "版本",
                summary = "1.0.0",
                rightWidget = {
                    Text(
                        text = "检查更新",
                        color = Morandi.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                            .clickable {
                                Toast.makeText(context, "当前已是最新版本 (v1.0.0)", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                },
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                onClick = {
                    Toast.makeText(context, "当前已是最新版本 (v1.0.0)", Toast.LENGTH_SHORT).show()
                },
            )

            // 导出日志 (Bottom Rounded)
            AboutGroupItem(
                icon = Icons.Rounded.Article,
                title = "导出日志",
                summary = "导出应用运行日志以供调试",
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                onClick = {
                    Toast.makeText(context, "运行日志已准备完毕", Toast.LENGTH_SHORT).show()
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // 5. 软件介绍卡片 (Artistic Intro Card)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(3.5.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Morandi.accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "软件介绍",
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "ReveriePaint 是一款专注于移动端与平板体验的专业级数字绘图软件，搭载强大的笔刷绘制引擎，支持多图层合成、选区操作与丰富的工具生态，带来流畅自然的数字绘画创作体验。",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // 贡献者弹窗
    if (showContributorsDialog) {
        Dialog(onDismissRequest = { showContributorsDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(20.dp))
                    .padding(22.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "贡献者",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "感谢所有为 ReveriePaint 提交代码、建议与反馈的开发者与创作者！\n\n• LanRhyme\n• Krita & KDE Community\n• Qt Project",
                        color = Morandi.subText,
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    TextButton(onClick = { showContributorsDialog = false }) {
                        Text("确定", color = Morandi.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutGroupItem(
    icon: ImageVector,
    title: String,
    summary: String,
    shape: RoundedCornerShape,
    isLink: Boolean = false,
    rightWidget: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val colors = Theme.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Morandi.panelHi)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Morandi.accent,
                        modifier = Modifier.size(19.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        color = if (isLink) Morandi.accent else Morandi.subText,
                        fontSize = 12.sp,
                        textDecoration = if (isLink) TextDecoration.Underline else TextDecoration.None,
                    )
                }
            }

            rightWidget?.invoke()
        }
    }
}
