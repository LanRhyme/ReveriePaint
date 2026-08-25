/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.R
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class GitHubContributor(
    val login: String,
    val avatarUrl: String,
    val htmlUrl: String,
    val contributions: Int,
)

/**
 * Each contributor becomes a "bubble" that floats with a unique phase/amplitude.
 */
private data class ContributorBubble(
    val contributor: GitHubContributor,
    val size: Float, // dp size
    val floatPhase: Float, // random phase offset for floating animation
    val floatAmpX: Float, // horizontal float amplitude (dp)
    val floatAmpY: Float, // vertical float amplitude (dp)
    val floatSpeedX: Float, // horizontal oscillation speed factor
    val floatSpeedY: Float, // vertical oscillation speed factor
)

private val okHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
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
fun ContributorsDialog(onDismiss: () -> Unit) {
    var bubbles by remember { mutableStateOf<List<ContributorBubble>>(emptyList()) }
    val avatarMap = remember { mutableStateMapOf<String, ImageBitmap>() }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/LanRhyme/ReveriePaint/contributors")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) ReveriePaintApp")
                    .header("Accept", "application/vnd.github+json")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val list = mutableListOf<GitHubContributor>()

                if (response.isSuccessful) {
                    val jsonText = response.body?.string() ?: "[]"
                    val array = JSONArray(jsonText)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            GitHubContributor(
                                login = obj.optString("login", "Unknown"),
                                avatarUrl = obj.optString("avatar_url", ""),
                                htmlUrl = obj.optString("html_url", "https://github.com"),
                                contributions = obj.optInt("contributions", 1),
                            )
                        )
                    }
                }

                if (list.isEmpty()) {
                    list.add(
                        GitHubContributor(
                            login = "LanRhyme",
                            avatarUrl = "https://avatars.githubusercontent.com/u/113491998?v=4",
                            htmlUrl = "https://github.com/LanRhyme",
                            contributions = 207,
                        )
                    )
                }

                bubbles = list.map { c ->
                    val sz = 75f + min(c.contributions.toFloat() * 1.5f, 45f)
                    ContributorBubble(
                        contributor = c,
                        size = sz,
                        floatPhase = Random.nextFloat() * 6.28f,
                        floatAmpX = 4f + Random.nextFloat() * 8f,
                        floatAmpY = 4f + Random.nextFloat() * 8f,
                        floatSpeedX = 0.6f + Random.nextFloat() * 0.8f,
                        floatSpeedY = 0.7f + Random.nextFloat() * 0.9f,
                    )
                }
                isLoading = false

                // Load avatar images in parallel
                bubbles.forEach { bubble ->
                    launch(Dispatchers.IO) {
                        val bmp = downloadAvatarBitmap(bubble.contributor.avatarUrl)
                        if (bmp != null) {
                            withContext(Dispatchers.Main) {
                                avatarMap[bubble.contributor.login] = bmp
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val fallbackList = listOf(
                    GitHubContributor("LanRhyme", "https://avatars.githubusercontent.com/u/113491998?v=4", "https://github.com/LanRhyme", 207),
                )
                bubbles = fallbackList.map { c ->
                    ContributorBubble(
                        contributor = c,
                        size = 85f,
                        floatPhase = Random.nextFloat() * 6.28f,
                        floatAmpX = 6f,
                        floatAmpY = 6f,
                        floatSpeedX = 0.8f,
                        floatSpeedY = 0.8f,
                    )
                }
                isLoading = false

                bubbles.forEach { bubble ->
                    launch(Dispatchers.IO) {
                        val bmp = downloadAvatarBitmap(bubble.contributor.avatarUrl)
                        if (bmp != null) {
                            withContext(Dispatchers.Main) {
                                avatarMap[bubble.contributor.login] = bmp
                            }
                        }
                    }
                }
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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Morandi.panelHi,
                            Morandi.panel,
                        )
                    )
                )
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
                            "正在加载贡献者列表...",
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
                    FloatingBubbleGrid(
                        bubbles = bubbles,
                        avatarMap = avatarMap,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                    ) { url ->
                        try {
                            uriHandler.openUri(url)
                        } catch (_: Exception) {}
                    }
                }
            }

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Morandi.panelHi,
                                Morandi.panelHi.copy(alpha = 0.9f),
                                Color.Transparent,
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "贡献者",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Morandi.accent,
                    )
                    if (bubbles.isNotEmpty()) {
                        Text(
                            "共 ${bubbles.size} 位贡献者",
                            style = MaterialTheme.typography.bodySmall,
                            color = Morandi.subText,
                        )
                    }
                }

                ReIconButton(R.drawable.ic_x, "关闭", onDismiss, size = 36.dp, tint = Morandi.text, iconSize = 18.dp)
            }
        }
    }
}

/**
 * Displays contributor bubbles in a scrollable wrapped grid layout,
 * each bubble gently floating with sinusoidal animation.
 */
@Composable
private fun FloatingBubbleGrid(
    bubbles: List<ContributorBubble>,
    avatarMap: Map<String, ImageBitmap>,
    modifier: Modifier = Modifier,
    onClick: (String) -> Unit,
) {
    val scrollState = rememberScrollState()

    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831853f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "floatTime",
    )

    val fadeAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeAnim.animateTo(1f, animationSpec = tween(600))
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .alpha(fadeAnim.value),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BubbleFlowLayout(bubbles = bubbles, time = time) { bubble, offsetX, offsetY ->
            val avatarBitmap = avatarMap[bubble.contributor.login]
            ContributorBubbleItem(
                bubble = bubble,
                avatarBitmap = avatarBitmap,
                offsetX = offsetX,
                offsetY = offsetY,
                onClick = { onClick(bubble.contributor.htmlUrl) },
            )
        }
    }
}

@Composable
private fun BubbleFlowLayout(
    bubbles: List<ContributorBubble>,
    time: Float,
    content: @Composable (ContributorBubble, Float, Float) -> Unit,
) {
    val rows = remember(bubbles) {
        val result = mutableListOf<List<ContributorBubble>>()
        var index = 0
        var rowSize = 3
        while (index < bubbles.size) {
            val end = min(index + rowSize, bubbles.size)
            result.add(bubbles.subList(index, end))
            index = end
            rowSize = if (rowSize == 3) 4 else 3
        }
        result
    }

    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.forEach { bubble ->
                val floatX = sin(time * bubble.floatSpeedX + bubble.floatPhase) * bubble.floatAmpX
                val floatY = sin(time * bubble.floatSpeedY + bubble.floatPhase + 1.5f) * bubble.floatAmpY
                content(bubble, floatX, floatY)
            }
        }
    }
}

@Composable
private fun ContributorBubbleItem(
    bubble: ContributorBubble,
    avatarBitmap: ImageBitmap?,
    offsetX: Float,
    offsetY: Float,
    onClick: () -> Unit,
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .graphicsLayer {
                translationX = offsetX
                translationY = offsetY
            }
            .width(bubble.size.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(bubble.size.dp)
                .clip(CircleShape)
                .background(Morandi.panelHi)
                .border(2.dp, Morandi.accent.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap,
                    contentDescription = bubble.contributor.login,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = bubble.contributor.login.take(1).uppercase(),
                    color = Morandi.accent,
                    fontSize = (bubble.size * 0.35f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = bubble.contributor.login,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "${bubble.contributor.contributions} 次贡献",
            fontSize = 10.sp,
            color = Morandi.subText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
