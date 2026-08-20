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
import java.io.OutputStreamWriter
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

private fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

@Composable
fun SponsorsDialog(onDismiss: () -> Unit) {
    var sponsors by remember { mutableStateOf<List<SponsorItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

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
                            "正在加载赞助者...",
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

                sponsors.isEmpty() -> {
                    // Exact MicYou Empty State
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
                                        "0 位赞助者",
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
                                    "此处仅显示爱发电上对项目发起者个人 LanRhyme 的赞助。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Morandi.subText,
                                    lineHeight = 18.sp,
                                )
                            }

                            // Empty Center
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("❤️", fontSize = 48.sp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "暂无赞助者，等待好心人出现 (｡•́︿•̀｡)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Morandi.subText,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        // Floating Action Button (MicYou Style)
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
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "前往爱发电赞助",
                                modifier = Modifier.size(24.dp),
                            )
                        }
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
                                        "${sponsors.size} 位赞助者",
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

                            // Disclaimer banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.panel)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    "此处仅显示爱发电上对项目发起者个人 LanRhyme 的赞助。",
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

                        // Floating Action Button
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
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "前往爱发电赞助",
                                modifier = Modifier.size(24.dp),
                            )
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
