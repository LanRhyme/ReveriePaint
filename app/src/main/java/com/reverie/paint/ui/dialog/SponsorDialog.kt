/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReFab
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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

private val okHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun downloadAvatarBitmap(urlString: String): ImageBitmap? {
    if (urlString.isBlank()) return null
    return try {
        val request = Request.Builder()
            .url(urlString)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        val response = okHttpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun SponsorsDialog(onDismiss: () -> Unit) {
    var sponsors by remember { mutableStateOf<List<SponsorItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(reloadTrigger) {
        withContext(Dispatchers.IO) {
            val userId = com.reverie.paint.BuildConfig.AIFADIAN_USER_ID
            val apiToken = com.reverie.paint.BuildConfig.AIFADIAN_API_TOKEN

            if (userId.isNotBlank() && apiToken.isNotBlank()) {
                isLoading = true
                error = null
                var attempt = 0
                var success = false

                while (attempt < 3 && !success) {
                    attempt++
                    try {
                        val allSponsors = mutableListOf<SponsorItem>()
                        var currentPage = 1
                        var totalPages = 1
                        val ts = System.currentTimeMillis() / 1000

                        while (currentPage <= totalPages) {
                            val paramsJson = "{\"page\":$currentPage,\"per_page\":100}"
                            val signStr = "${apiToken}params${paramsJson}ts${ts}user_id${userId}"
                            val sign = md5(signStr)

                            val requestJson = JSONObject().apply {
                                put("user_id", userId)
                                put("params", paramsJson)
                                put("ts", ts)
                                put("sign", sign)
                            }

                            val mediaType = "application/json; charset=utf-8".toMediaType()
                            val body = requestJson.toString().toRequestBody(mediaType)

                            val request = Request.Builder()
                                .url("https://afdian.com/api/open/query-sponsor")
                                .post(body)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                                .header("Accept", "application/json")
                                .build()

                            val response = okHttpClient.newCall(request).execute()

                            if (response.isSuccessful) {
                                val resText = response.body?.string() ?: "{}"
                                val json = JSONObject(resText)
                                if (json.optInt("ec", -1) == 200) {
                                    val dataObj = json.optJSONObject("data")
                                    if (dataObj != null) {
                                        totalPages = dataObj.optInt("total_page", 1)
                                        val listArr = dataObj.optJSONArray("list")
                                        if (listArr != null) {
                                            for (i in 0 until listArr.length()) {
                                                val item = listArr.getJSONObject(i)
                                                val userObj = item.optJSONObject("user")
                                                val planObj = item.optJSONObject("current_plan")
                                                allSponsors.add(
                                                    SponsorItem(
                                                        userName = userObj?.optString("name", "Anonymous") ?: "Anonymous",
                                                        userAvatar = userObj?.optString("avatar", "") ?: "",
                                                        userId = userObj?.optString("user_id", "") ?: "",
                                                        amount = item.optString("all_sum_amount", "0"),
                                                        timestamp = item.optLong("first_pay_time", item.optLong("create_time", 0)),
                                                        planName = planObj?.optString("name", "")?.ifBlank { null },
                                                    )
                                                )
                                            }
                                        }
                                        currentPage++
                                    } else {
                                        break
                                    }
                                } else {
                                    error = json.optString("em", "API 响应异常")
                                    break
                                }
                            } else {
                                error = "HTTP ${response.code}"
                                break
                            }
                        }

                        if (error == null) {
                            val sorted = allSponsors.sortedByDescending { it.timestamp }
                            sponsors = sorted
                            success = true
                            isLoading = false

                            // Load avatars in parallel
                            sponsors.forEach { item ->
                                launch(Dispatchers.IO) {
                                    val bmp = downloadAvatarBitmap(item.userAvatar)
                                    if (bmp != null) {
                                        item.bitmap = bmp
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (attempt < 3) {
                            delay(800)
                        } else {
                            error = "网络连接受阻，请检查设备联网状态或稍后重试"
                            isLoading = false
                        }
                    }
                }
            } else {
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
                            "正在加载爱发电赞助者数据...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Morandi.subText,
                        )
                    }
                }

                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("⚠️", fontSize = 42.sp)
                        Text(
                            error ?: "加载失败",
                            color = Morandi.subText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panel)
                                .clickable { reloadTrigger++ }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "重试",
                                tint = Morandi.accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("重新加载", color = Morandi.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
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
                                ReIconButton(R.drawable.ic_x, "关闭", onDismiss, size = 36.dp, tint = Morandi.text, iconSize = 18.dp)
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

                        // Floating Action Button
                        ReFab(
                            R.drawable.ic_heart,
                            "前往爱发电赞助",
                            {
                                try {
                                    uriHandler.openUri("https://afdian.com/a/LanRhyme")
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(20.dp),
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
                                        "${sponsors.size} 位赞助者",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Morandi.subText,
                                    )
                                }
                                ReIconButton(R.drawable.ic_x, "关闭", onDismiss, size = 36.dp, tint = Morandi.text, iconSize = 18.dp)
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
                        ReFab(
                            R.drawable.ic_heart,
                            "前往爱发电赞助",
                            {
                                try {
                                    uriHandler.openUri("https://afdian.com/a/LanRhyme")
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(20.dp),
                        )
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
