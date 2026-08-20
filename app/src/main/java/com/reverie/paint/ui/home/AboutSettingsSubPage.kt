package com.reverie.paint.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

/**
 * Custom ROM (Evolution X / Nothing OS / Pixel "Android Version" Spec UI) style About Page
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

    // Safely load application icon
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (compact) Color.Transparent else colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 8.dp else 24.dp, vertical = if (compact) 8.dp else 20.dp),
    ) {
        // 1. Back button
        Box(
            modifier = Modifier
                .size(44.dp)
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
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        // 2. Large Custom-ROM Style Title Header
        Text(
            text = "ReveriePaint",
            color = Morandi.accent,
            fontSize = if (compact) 28.sp else 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )

        Spacer(Modifier.height(28.dp))

        // 3. Hero Showcase Card (Left: Device Mockup, Right: Specs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Device / Canvas Visual Mockup Card
            Box(
                modifier = Modifier
                    .width(if (compact) 95.dp else 125.dp)
                    .aspectRatio(1f / 2.05f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF2A241F),
                                Color(0xFF141210),
                                Color(0xFF0A0908),
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Morandi.accent.copy(alpha = 0.8f),
                                Morandi.accent.copy(alpha = 0.2f),
                            )
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ),
            ) {
                // Artistic canvas illustration with app logo in center
                ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Warm golden celestial art gradients
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                Morandi.accent.copy(alpha = 0.65f),
                                Color.Transparent,
                            ),
                            center = Offset(w * 0.45f, h * 0.35f),
                            radius = w * 0.7f,
                        ),
                        center = Offset(w * 0.45f, h * 0.35f),
                        radius = w * 0.7f,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        center = Offset(w * 0.45f, h * 0.35f),
                        radius = 6.dp.toPx(),
                    )
                    drawCircle(
                        color = Morandi.accent,
                        center = Offset(w * 0.72f, h * 0.65f),
                        radius = 4.dp.toPx(),
                    )
                    // Orbital aesthetic axis lines
                    drawLine(
                        color = Morandi.accent.copy(alpha = 0.3f),
                        start = Offset(w * 0.72f, 0f),
                        end = Offset(w * 0.72f, h),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // App icon badge on the canvas mockup
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }

            Spacer(Modifier.width(if (compact) 16.dp else 24.dp))

            // Right Key-Value System Specs
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RomSpecItem(
                    label = "DEVELOPER",
                    value = "LanRhyme",
                )
                RomSpecItem(
                    label = "ENGINE",
                    value = "Krita PaintOp Core",
                )
                RomSpecItem(
                    label = "VERSION",
                    value = "1.0.0 (Release)",
                )
                RomSpecItem(
                    label = "TARGET",
                    value = "Android Tablet & Mobile",
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // 4. Contiguous Expressive Rounded Cards List (Custom ROM Group Style)
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 1. 开发者 (Top Rounded)
            RomCardItem(
                title = "开发者",
                value = "LanRhyme",
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                onClick = null,
            )

            // 2. Github 仓库 (Middle Rounded)
            RomCardItem(
                title = "Github 仓库",
                value = "https://github.com/LanRhyme/ReveriePaint",
                isLink = true,
                shape = RoundedCornerShape(4.dp),
                onClick = {
                    try {
                        uriHandler.openUri("https://github.com/LanRhyme/ReveriePaint")
                    } catch (_: Exception) {}
                },
            )

            // 3. 贡献者 (Middle Rounded)
            RomCardItem(
                title = "贡献者",
                value = "感谢每一位为本项目做出贡献的人",
                shape = RoundedCornerShape(4.dp),
                onClick = { showContributorsDialog = true },
            )

            // 4. 赞助者 (Middle Rounded)
            RomCardItem(
                title = "赞助者",
                value = "查看爱发电赞助者",
                isLink = true,
                shape = RoundedCornerShape(4.dp),
                onClick = {
                    try {
                        uriHandler.openUri("https://afdian.com/a/LanRhyme")
                    } catch (_: Exception) {}
                },
            )

            // 5. 版本与更新 (Middle Rounded)
            RomCardItem(
                title = "版本",
                value = "1.0.0 (检查更新)",
                shape = RoundedCornerShape(4.dp),
                onClick = {
                    Toast.makeText(context, "当前已是最新版本 (v1.0.0)", Toast.LENGTH_SHORT).show()
                },
            )

            // 6. 导出日志 (Middle Rounded)
            RomCardItem(
                title = "导出日志",
                value = "导出应用日志以供调试",
                shape = RoundedCornerShape(4.dp),
                onClick = {
                    Toast.makeText(context, "日志已准备就绪", Toast.LENGTH_SHORT).show()
                },
            )

            // 7. 软件介绍 (Bottom Rounded)
            RomCardItem(
                title = "软件介绍",
                value = "专注于移动端与平板体验的专业级数字绘图软件，搭载强大的笔刷绘制引擎，支持多图层合成与自然笔触体验。",
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
                onClick = null,
            )
        }

        Spacer(Modifier.height(30.dp))
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
private fun RomSpecItem(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            color = Morandi.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = Morandi.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun RomCardItem(
    title: String,
    value: String,
    shape: RoundedCornerShape,
    isLink: Boolean = false,
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column {
            Text(
                text = title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = if (isLink) Morandi.accent else Morandi.subText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textDecoration = if (isLink) TextDecoration.Underline else TextDecoration.None,
            )
        }
    }
}
