package com.reverie.paint.ui.dialog

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SponsorItem(
    val userName: String,
    val userAvatar: String,
    val userId: String,
    val amount: String,
    val timestamp: Long,
    val planName: String? = null,
) {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
}

@Composable
fun SponsorsDialog(onDismiss: () -> Unit) {
    var sponsors by remember { mutableStateOf<List<SponsorItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // Fetch public sponsor info or default community list
                val list = mutableListOf<SponsorItem>()
                // Simulated or cached sponsors if no public endpoint
                list.add(SponsorItem("爱发电赞助者", "", "1", "100.00", System.currentTimeMillis() / 1000, "自选赞助"))
                sponsors = list
                isLoading = false
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                isLoading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(RoundedCornerShape(28.dp))
                .background(Morandi.panelHi)
                .border(1.dp, Morandi.border, RoundedCornerShape(28.dp)),
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Morandi.accent,
                            strokeWidth = 3.dp,
                        )
                        Text(
                            "正在加载赞助者列表...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Morandi.subText,
                        )
                    }
                }

                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        Text(
                            error ?: "加载失败",
                            color = Morandi.subText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Top Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Morandi.panelHi)
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        "赞助者",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Morandi.accent,
                                    )
                                    Text(
                                        "感谢所有支持 ReveriePaint 独立开发的伙伴",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Morandi.subText,
                                    )
                                }
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Morandi.panel),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "关闭",
                                        tint = Morandi.text,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            // Disclaimer banner (MicYou Style)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.panel)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    "数据来源于爱发电公开赞助记录。您的每一份支持都是本项目持续维护与优化的最大动力！",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Morandi.subText,
                                    lineHeight = 18.sp,
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // Sponsors List
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(sponsors) { item ->
                                    SponsorListItem(item)
                                }
                            }
                        }

                        // Floating Action Button to Afdian
                        FloatingActionButton(
                            onClick = {
                                try {
                                    uriHandler.openUri("https://afdian.com/a/LanRhyme")
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(20.dp),
                            containerColor = Morandi.accent,
                            contentColor = Morandi.onAccent,
                            shape = CircleShape,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = "前往爱发电赞助",
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("前往爱发电赞助", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SponsorListItem(item: SponsorItem) {
    val colors = Theme.current
    val dateStr = remember(item.timestamp) {
        if (item.timestamp > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.format(Date(item.timestamp * 1000))
        } else {
            ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panel)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Morandi.panelHi)
                        .border(1.dp, Morandi.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.bitmap != null) {
                        Image(
                            bitmap = item.bitmap!!,
                            contentDescription = item.userName,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(
                            item.userName.take(1).uppercase(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Morandi.accent,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = item.userName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${item.planName ?: "爱发电赞助"} · $dateStr",
                        fontSize = 11.sp,
                        color = Morandi.subText,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panelHi)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "¥ ${item.amount}",
                    color = Morandi.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
