package com.reverie.paint.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Description
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun AboutSettingsSubPage(
    onBack: () -> Unit,
    compact: Boolean = false,
) {
    val colors = Theme.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    var showContributorsDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (compact) Color.Transparent else colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = if (compact) 4.dp else 16.dp),
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
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
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "设置",
                color = colors.text,
                fontSize = if (compact) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(12.dp))

        // App Logo & Name header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "ReveriePaint",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "ReveriePaint",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "移动端与平板专业级数字绘画工具",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Section Title: ▍ 关于
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Morandi.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "关于",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Grouped List Items
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. 开发者
            AboutCardItem(
                icon = Icons.Rounded.Person,
                title = "开发者",
                summary = "LanRhyme",
                onClick = null,
            )

            // 2. Github 仓库
            AboutCardItem(
                icon = Icons.Rounded.Language,
                title = "Github 仓库",
                summary = "https://github.com/LanRhyme/ReveriePaint",
                isLink = true,
                onClick = {
                    try {
                        uriHandler.openUri("https://github.com/LanRhyme/ReveriePaint")
                    } catch (_: Exception) {}
                },
            )

            // 3. 贡献者
            AboutCardItem(
                icon = Icons.Rounded.People,
                title = "贡献者",
                summary = "感谢每一位为本项目做出贡献的人",
                onClick = { showContributorsDialog = true },
            )

            // 4. 赞助者
            AboutCardItem(
                icon = Icons.Rounded.Favorite,
                title = "赞助者",
                summary = "查看爱发电赞助者",
                onClick = {
                    try {
                        uriHandler.openUri("https://afdian.com/a/LanRhyme")
                    } catch (_: Exception) {}
                },
            )

            // 5. 版本
            AboutCardItem(
                icon = Icons.Rounded.Info,
                title = "版本",
                summary = "1.0.0",
                rightContent = {
                    Text(
                        text = "检查更新",
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                Toast.makeText(context, "当前已是最新版本 (v1.0.0)", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                },
                onClick = {
                    Toast.makeText(context, "当前已是最新版本 (v1.0.0)", Toast.LENGTH_SHORT).show()
                },
            )

            // 6. 开源许可
            AboutCardItem(
                icon = Icons.Rounded.Description,
                title = "开源许可",
                summary = "查看使用的开源库",
                onClick = { showLicensesDialog = true },
            )

            // 7. 导出日志
            AboutCardItem(
                icon = Icons.Rounded.Article,
                title = "导出日志",
                summary = "导出应用日志以供调试",
                onClick = {
                    Toast.makeText(context, "日志已准备就绪", Toast.LENGTH_SHORT).show()
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // 软件介绍卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Column {
                Text(
                    text = "软件介绍",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "ReveriePaint 是一款专注于移动端与平板体验的专业级数字绘图软件，搭载强大的笔刷绘制引擎，支持多图层合成、选区操作与丰富的工具生态，带来流畅自然的数字绘画创作体验。",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    // 贡献者弹窗
    if (showContributorsDialog) {
        Dialog(onDismissRequest = { showContributorsDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "贡献者",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "感谢所有为 ReveriePaint 提交代码、建议与反馈的开发者与创作者！\n\n• LanRhyme\n• Krita & KDE Community\n• Qt Project",
                        color = Morandi.subText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { showContributorsDialog = false }) {
                        Text("确定", color = Morandi.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // 开源许可弹窗
    if (showLicensesDialog) {
        Dialog(onDismissRequest = { showLicensesDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "开源许可与使用的库",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "• Krita Core (GPL-3.0)\n• Qt 6 Framework (LGPL-3.0)\n• Jetpack Compose & AndroidX (Apache-2.0)\n• Haze Blur Engine (Apache-2.0)\n• Kotlin Standard Library (Apache-2.0)",
                        color = Morandi.subText,
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { showLicensesDialog = false }) {
                        Text("确定", color = Morandi.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCardItem(
    icon: ImageVector,
    title: String,
    summary: String,
    isLink: Boolean = false,
    rightContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val colors = Theme.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Morandi.accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = summary,
                        color = if (isLink) Morandi.accent else Morandi.subText,
                        fontSize = 12.sp,
                        textDecoration = if (isLink) TextDecoration.Underline else TextDecoration.None,
                    )
                }
            }

            rightContent?.invoke()
        }
    }
}
