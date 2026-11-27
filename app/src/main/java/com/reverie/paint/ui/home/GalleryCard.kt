/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.model.Project
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.components.liquidSheen
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.blendColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// In-Memory LRU Cache for high-performance thumbnail rendering without disk jank
internal object ThumbnailCache {
    /**
     * 缩略图解码长边上限: 网格单元约 160dp, 4x 屏约 640px, 取 768px 覆盖到平板的大单元格;
     * 不解码原尺寸可省下数倍位图内存, 滑动时少一次 GC 抖动。
     */
    private const val MAX_DECODE_EDGE = 768

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024 * 16)
    private val lruCache =
        object : android.util.LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(
                key: String,
                bitmap: Bitmap,
            ): Int = bitmap.byteCount / 1024
        }

    fun peek(key: String): Bitmap? {
        val cached = lruCache.get(key)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    private fun sampleSizeFor(path: String): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_DECODE_EDGE) {
            sample *= 2
        }
        return sample
    }

    fun get(
        path: String,
        lastModified: Long,
    ): Bitmap? {
        if (path.isEmpty()) return null
        val key = "$path:$lastModified"
        val cached = peek(key)
        if (cached != null) return cached
        val file = File(path)
        if (file.exists()) {
            return try {
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(path) }
                val bmp = BitmapFactory.decodeFile(path, options)
                if (bmp != null) {
                    lruCache.put(key, bmp)
                }
                bmp
            } catch (_: Exception) {
                null
            }
        }
        return null
    }
}

@Composable
internal fun rememberThumbnail(path: String?, lastModified: Long): Bitmap? {
    if (path.isNullOrEmpty()) return null
    val key = "$path:$lastModified"
    val immediate = remember(key) { ThumbnailCache.peek(key) }
    if (immediate != null) return immediate

    var bmp by remember(key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key) {
        val loaded = withContext(Dispatchers.IO) {
            ThumbnailCache.get(path, lastModified)
        }
        bmp = loaded
    }
    return bmp
}

/**
 * 单张画作卡片缩略图容器 (1:1 外框 + 内置自适应画面比例与工作室画框镶边)
 */
@Composable
internal fun ArtworkThumbnailBox(
    project: Project,
    cardRingWidth: Dp,
    selectTint: Color,
    isSelected: Boolean = false,
    isSelectMode: Boolean = false,
    isFolderCover: Boolean = false,
    isMergePending: Boolean = false,
    isMergeTarget: Boolean = false,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    val thumb = rememberThumbnail(project.previewPath, project.lastModified)
    val rawRatio = if (project.width > 0 && project.height > 0) {
        (project.width.toFloat() / project.height.toFloat()).coerceIn(0.52f, 1.90f)
    } else {
        1.0f
    }
    val thumbFade by animateFloatAsState(
        targetValue = if (thumb != null) 1f else 0f,
        animationSpec = when (Motion.currentSpeed) {
            UiAnimationSpeed.OFF -> snap()
            UiAnimationSpeed.FAST -> tween(90)
            UiAnimationSpeed.NORMAL -> tween(240)
        },
        label = "ThumbFade",
    )

    // 400ms 互叠合并倒计时进度环
    val mergeProgress = remember { Animatable(0f) }
    LaunchedEffect(isMergePending) {
        if (isMergePending) {
            mergeProgress.snapTo(0f)
            mergeProgress.animateTo(1f, tween(400, easing = LinearEasing))
        } else {
            mergeProgress.snapTo(0f)
        }
    }

    // 拖拽互叠合并提示脉冲光圈
    val mergePulse by animateFloatAsState(
        targetValue = if (isMergeTarget) 1.07f else 1.0f,
        animationSpec = if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            snap()
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        },
        label = "MergePulse",
    )

    val isDark = colors.isDarkTheme()
    val artShape = RoundedCornerShape(12.dp)
    val hairlineBrush = remember(isDark) { artboardHairlineBrush(colors) }
    val backdropColor = if (thumb != null) {
        if (isDark) colors.panelHi else Color.White
    } else {
        colors.panelHi
    }
    val obsidianBadgeBg = Color(0xFF141418).copy(alpha = 0.68f)
    val obsidianBadgeBorder = Color.White.copy(alpha = 0.16f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .graphicsLayer {
                    val pendingScale = if (isMergePending) 1f + 0.035f * mergeProgress.value else 1f
                    val s = mergePulse * pendingScale
                    scaleX = s
                    scaleY = s
                    if (isMergePending) {
                        rotationZ = 3.5f * mergeProgress.value
                    }
                }
                .aspectRatio(rawRatio, matchHeightConstraintsFirst = rawRatio < 1.0f)
                .shadow(
                    elevation = if (isMergeTarget) 16.dp else if (isDark) 10.dp else 8.dp,
                    shape = artShape,
                    spotColor = if (isMergeTarget) {
                        colors.accent.copy(alpha = 0.45f)
                    } else {
                        Color.Black.copy(alpha = if (isDark) 0.42f else 0.16f)
                    },
                    ambientColor = Color.Black.copy(alpha = if (isDark) 0.24f else 0.08f),
                    clip = false,
                )
                .clip(artShape)
                .liquidSheen(trigger = isSelected)
                .background(backdropColor)
                .drawWithContent {
                    drawContent()
                    if (!isMergeTarget && !isMergePending && cardRingWidth <= 0.dp) {
                        val strokePx = 0.75.dp.toPx()
                        val outline = artShape.createOutline(size, layoutDirection, this)
                        drawOutline(
                            outline = outline,
                            brush = hairlineBrush,
                            style = Stroke(width = strokePx),
                        )
                    }
                }
                .then(
                    if (isMergeTarget) {
                        Modifier.border(2.5.dp, colors.accent, artShape)
                    } else if (isMergePending) {
                        Modifier.border(1.5.dp, colors.accent.copy(alpha = 0.45f + 0.45f * mergeProgress.value), artShape)
                    } else if (cardRingWidth > 0.dp) {
                        Modifier.border(cardRingWidth, colors.accent, artShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val settle = 0.985f + 0.015f * thumbFade
                            alpha = thumbFade
                            scaleX = settle
                            scaleY = settle
                        },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_canvas_tab),
                        contentDescription = null,
                        tint = colors.subText.copy(alpha = 0.45f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // 选中蒙层
            Box(modifier = Modifier.fillMaxSize().background(selectTint))

            // 自动保存角标 (贴合画框左上角)
            if (project.isAutoSaved) {
                GalleryMicroBadge(
                    text = stringResource(R.string.gallery_badge_autosave),
                    iconRes = R.drawable.ic_save,
                    containerColor = obsidianBadgeBg,
                    contentColor = Color.White,
                    borderColor = obsidianBadgeBorder,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }

            // 动画画布角标 (贴合画框右上角)
            if (project.isAnimation) {
                GalleryMicroBadge(
                    text = stringResource(R.string.gallery_badge_anim),
                    iconRes = R.drawable.ic_clock,
                    containerColor = obsidianBadgeBg,
                    contentColor = Color.White,
                    borderColor = obsidianBadgeBorder,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }

            // 底部左侧角标组合: 茶绘工程标识 / 画集封面角标
            if (project.isCollab || isFolderCover) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (project.isCollab) {
                        GalleryMicroBadge(
                            text = stringResource(R.string.gallery_badge_collab),
                            iconRes = R.drawable.ic_cloud,
                            containerColor = colors.accent.copy(alpha = 0.94f),
                            contentColor = colors.onAccent,
                            borderColor = Color.White.copy(alpha = 0.18f),
                        )
                    }
                    if (isFolderCover) {
                        GalleryMicroBadge(
                            text = stringResource(R.string.gallery_cover_badge),
                            iconRes = R.drawable.ic_image,
                            containerColor = obsidianBadgeBg,
                            contentColor = Color.White,
                            borderColor = obsidianBadgeBorder,
                        )
                    }
                }
            }

            // 多选模式勾选角标 (贴合画框右下角)
            CardSelectionBadge(
                isSelectMode = isSelectMode,
                isSelected = isSelected,
                colors = colors,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp),
            )
        }

        // 400ms 合并蓄力环形进度指示
        AnimatedVisibility(
            visible = isMergePending && !isMergeTarget,
            enter = scaleIn(initialScale = 0.75f, animationSpec = tween(120)) + fadeIn(tween(120)),
            exit = fadeOut(tween(80)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(8.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(colors.panel.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center,
            ) {
                val trackColor = colors.accent.copy(alpha = 0.22f)
                val arcColor = colors.accent
                Canvas(modifier = Modifier.fillMaxSize().padding(3.5.dp)) {
                    val strokeW = 2.5.dp.toPx()
                    drawCircle(
                        color = trackColor,
                        style = Stroke(width = strokeW),
                    )
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * mergeProgress.value,
                        useCenter = false,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round),
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_folder_plus),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // 互叠合成新画集浮动提示徽章
        AnimatedVisibility(
            visible = isMergeTarget,
            enter = scaleIn(
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.8f,
            ) + fadeIn(tween(140)),
            exit = scaleOut(tween(120), targetScale = 0.9f) + fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                modifier = Modifier
                    .shadow(10.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(colors.panel.copy(alpha = 0.96f))
                    .border(0.75.dp, studioHairlineBrush(colors), CircleShape)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder_plus),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(R.string.gallery_drag_merge_stack),
                    color = colors.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 画集扇形堆叠卡片缩略图容器 (3 层散落层叠 + 展开动画 + 主题自适应纸板质感)
 */
@Composable
internal fun FolderThumbnailBox(
    project: Project,
    fanPress: Float,
    borderStrokeWidth: Dp,
    isFolderHovered: Boolean,
    isSelected: Boolean = false,
    isSelectMode: Boolean = false,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    val hoverRingWidth by animateDpAsState(
        targetValue = if (isFolderHovered) 3.dp else 0.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "FolderHoverRingWidth",
    )
    val hoverRingAlpha by animateFloatAsState(
        targetValue = if (isFolderHovered) 1f else 0f,
        animationSpec = tween(160),
        label = "FolderHoverRingAlpha",
    )
    val ringAlpha = if (borderStrokeWidth > 0.dp) 1f else hoverRingAlpha

    val item0 = project.items.getOrNull(0)
    val item1 = project.items.getOrNull(1)
    val item2 = project.items.getOrNull(2)

    val thumb0 = rememberThumbnail(item0?.previewPath, item0?.lastModified ?: 0L)
    val thumb1 = rememberThumbnail(item1?.previewPath, item1?.lastModified ?: 0L)
    val thumb2 = rememberThumbnail(item2?.previewPath, item2?.lastModified ?: 0L)

    val ratio0 = remember(item0?.width, item0?.height) {
        if (item0 != null && item0.width > 0 && item0.height > 0) {
            (item0.width.toFloat() / item0.height.toFloat()).coerceIn(0.72f, 1.38f)
        } else {
            1.0f
        }
    }
    val ratio1 = remember(item1?.width, item1?.height, ratio0) {
        if (item1 != null && item1.width > 0 && item1.height > 0) {
            (item1.width.toFloat() / item1.height.toFloat()).coerceIn(0.72f, 1.38f)
        } else {
            ratio0
        }
    }
    val ratio2 = remember(item2?.width, item2?.height, ratio0) {
        if (item2 != null && item2.width > 0 && item2.height > 0) {
            (item2.width.toFloat() / item2.height.toFloat()).coerceIn(0.72f, 1.38f)
        } else {
            ratio0
        }
    }

    val isDark = colors.isDarkTheme()
    val stackShape = RoundedCornerShape(12.dp)
    val hairlineBrush = remember(isDark) { artboardHairlineBrush(colors) }
    val backHairlineColor = if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.12f)
    val back1Fill = if (isDark) blendColors(colors.panelHi, Color.White, 0.08f) else blendColors(colors.panel, Color.Black, 0.07f)
    val back2Fill = if (isDark) blendColors(colors.panelHi, Color.White, 0.14f) else blendColors(colors.panel, Color.Black, 0.03f)
    val frontFill = if (thumb0 != null) {
        if (isDark) colors.panelHi else Color.White
    } else {
        colors.panelHi
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // Stacked layer 1 (左后散落卡片 — 适配 160dp 网格的清晰露角)
        Box(
            modifier = Modifier
                .fillMaxSize(0.83f)
                .aspectRatio(ratio2, matchHeightConstraintsFirst = ratio2 < 1.0f)
                .graphicsLayer {
                    rotationZ = -7.5f - 7.5f * fanPress
                    translationX = (-6.5).dp.toPx() - 6.dp.toPx() * fanPress
                    translationY = (-2.5).dp.toPx() - 4.dp.toPx() * fanPress
                }
                .shadow(
                    elevation = if (isDark) 7.dp else 5.dp,
                    shape = stackShape,
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.40f else 0.14f),
                    clip = false,
                )
                .clip(stackShape)
                .background(back1Fill)
                .border(0.75.dp, backHairlineColor, stackShape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb2 != null) {
                Image(
                    bitmap = thumb2.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Color.Black.copy(alpha = 0.14f),
                    ),
                )
            }
        }

        // Stacked layer 2 (右后散落卡片 — 适配 160dp 网格的清晰露角)
        Box(
            modifier = Modifier
                .fillMaxSize(0.84f)
                .aspectRatio(ratio1, matchHeightConstraintsFirst = ratio1 < 1.0f)
                .graphicsLayer {
                    rotationZ = 6.5f + 6.5f * fanPress
                    translationX = 6.5.dp.toPx() + 6.dp.toPx() * fanPress
                    translationY = (-2).dp.toPx() - 3.dp.toPx() * fanPress
                }
                .shadow(
                    elevation = if (isDark) 9.dp else 7.dp,
                    shape = stackShape,
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.44f else 0.16f),
                    clip = false,
                )
                .clip(stackShape)
                .background(back2Fill)
                .border(0.75.dp, backHairlineColor, stackShape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb1 != null) {
                Image(
                    bitmap = thumb1.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Color.Black.copy(alpha = 0.06f),
                    ),
                )
            }
        }

        // Foreground main folder cover (顶层主封面)
        val finalRingWidth = maxOf(hoverRingWidth, borderStrokeWidth)
        val coverHoverLift by animateFloatAsState(
            targetValue = if (isFolderHovered) 1f else 0f,
            animationSpec = if (Motion.currentSpeed == UiAnimationSpeed.OFF) snap() else Motion.snapBouncy,
            label = "FolderCoverHoverLift",
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.87f)
                .aspectRatio(ratio0, matchHeightConstraintsFirst = ratio0 < 1.0f)
                .graphicsLayer {
                    rotationZ = -0.5f - 1.3f * fanPress
                    translationY = 2.dp.toPx() - 4.dp.toPx() * fanPress - 2.dp.toPx() * coverHoverLift
                }
                .shadow(
                    elevation = if (isDark) 13.dp else 10.dp,
                    shape = stackShape,
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.48f else 0.18f),
                    clip = false,
                )
                .clip(stackShape)
                .liquidSheen(trigger = isSelected)
                .background(frontFill)
                .drawWithContent {
                    drawContent()
                    if (finalRingWidth <= 0.dp) {
                        val strokePx = 0.75.dp.toPx()
                        val outline = stackShape.createOutline(size, layoutDirection, this)
                        drawOutline(
                            outline = outline,
                            brush = hairlineBrush,
                            style = Stroke(width = strokePx),
                        )
                    }
                }
                .then(
                    if (finalRingWidth > 0.dp) {
                        Modifier.border(
                            width = finalRingWidth,
                            color = colors.accent.copy(alpha = ringAlpha),
                            shape = stackShape,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb0 != null) {
                Image(
                    bitmap = thumb0.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panel.copy(alpha = 0.55f))
                        .border(0.5.dp, hairlineBrush, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folders),
                            contentDescription = null,
                            tint = colors.subText.copy(alpha = 0.7f),
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.gallery_badge_stack),
                            color = colors.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // 多选模式勾选角标 (贴合顶层主封面右下角)
            CardSelectionBadge(
                isSelectMode = isSelectMode,
                isSelected = isSelected,
                colors = colors,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            )
        }

        // Drop hint badge when dragging project over folder
        AnimatedVisibility(
            visible = isFolderHovered,
            enter = scaleIn(
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.8f,
            ) + fadeIn(tween(140)),
            exit = scaleOut(tween(120), targetScale = 0.9f) + fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                modifier = Modifier
                    .shadow(10.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(colors.panel.copy(alpha = 0.96f))
                    .border(0.75.dp, studioHairlineBrush(colors), CircleShape)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folders),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(R.string.gallery_drag_drop_to_stack),
                    color = colors.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 画作/画集卡片下方的居中元数据栏 (与居中画框光学对齐: 名称点击重命名、尺寸与笔画/相对时间)
 */
@Composable
internal fun CardMetaSection(
    project: Project,
    isSelectMode: Boolean,
    colors: AppColors,
    onRenameClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showDetails: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val justNowLabel = stringResource(R.string.gallery_time_just_now)
    val minsAgoFormat = stringResource(R.string.gallery_time_minutes_ago)
    val hoursAgoFormat = stringResource(R.string.gallery_time_hours_ago)
    val daysAgoFormat = stringResource(R.string.gallery_time_days_ago)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(9.dp))

        // 标题行: 点击弹出重命名对话框
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(
                    enabled = true,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onRenameClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 6.dp, vertical = 1.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = project.name,
                color = colors.text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 副标题行: 居中展示尺寸、笔画或相对时间 / 自动保存状态 / 画集作品数 (可由右上角菜单切换显隐)
        AnimatedVisibility(
            visible = showDetails,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(120)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(2.dp))

                val autoSavedText = stringResource(R.string.gallery_status_autosave)
                val dimenFormat = stringResource(R.string.gallery_dimen_px)
                val dimenText = remember(project.width, project.height, project.isFolder, dimenFormat) {
                    if (!project.isFolder && project.width > 0 && project.height > 0) {
                        String.format(Locale.getDefault(), dimenFormat, project.width, project.height)
                    } else {
                        null
                    }
                }

                if (project.isFolder) {
                    Text(
                        text = stringResource(R.string.gallery_stack_artworks_count, project.items.size),
                        color = colors.subText.copy(alpha = 0.82f),
                        fontSize = 11.sp,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (project.isAutoSaved) {
                    val info = if (dimenText != null) "$dimenText · $autoSavedText" else autoSavedText
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = info,
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    val secondaryMeta = when {
                        project.strokeCount > 0 -> stringResource(R.string.gallery_stroke_count, project.strokeCount)
                        project.lastModified > 0L -> remember(project.lastModified, justNowLabel) {
                            formatRelativeModifiedTime(
                                timestampMs = project.lastModified,
                                justNow = justNowLabel,
                                minsFormat = minsAgoFormat,
                                hoursFormat = hoursAgoFormat,
                                daysFormat = daysAgoFormat,
                            )
                        }
                        else -> null
                    }
                    val metaLine = listOfNotNull(dimenText, secondaryMeta).joinToString(" · ")
                    if (metaLine.isNotEmpty()) {
                        Text(
                            text = metaLine,
                            color = colors.subText.copy(alpha = 0.80f),
                            fontSize = 11.sp,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun formatRelativeModifiedTime(
    timestampMs: Long,
    justNow: String,
    minsFormat: String,
    hoursFormat: String,
    daysFormat: String,
): String {
    val diffMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    val hours = diffMs / 3_600_000L
    val days = diffMs / 86_400_000L
    return when {
        mins < 1L -> justNow
        mins < 60L -> String.format(Locale.getDefault(), minsFormat, mins.toInt())
        hours < 24L -> String.format(Locale.getDefault(), hoursFormat, hours.toInt())
        days < 7L -> String.format(Locale.getDefault(), daysFormat, days.toInt())
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestampMs))
    }
}
