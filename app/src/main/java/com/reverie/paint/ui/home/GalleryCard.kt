/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.AppColors
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
 * 单张画作卡片缩略图容器 (1:1 外框 + 内置自适应画面比例)
 */
@Composable
internal fun ArtworkThumbnailBox(
    project: Project,
    cardRingWidth: Dp,
    selectTint: Color,
    isSelected: Boolean = false,
    isSelectMode: Boolean = false,
    isFolderCover: Boolean = false,
    isMergeTarget: Boolean = false,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    val thumb = rememberThumbnail(project.previewPath, project.lastModified)
    val rawRatio = if (project.width > 0 && project.height > 0) {
        (project.width.toFloat() / project.height.toFloat()).coerceIn(0.2f, 5.0f)
    } else {
        1.0f
    }
    val thumbFade by animateFloatAsState(
        targetValue = if (thumb != null) 1f else 0f,
        animationSpec = tween(220),
        label = "ThumbFade",
    )

    // 拖拽互叠合并提示脉冲光圈
    val mergePulse by animateFloatAsState(
        targetValue = if (isMergeTarget) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "MergePulse",
    )

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
                    scaleX = mergePulse
                    scaleY = mergePulse
                }
                .aspectRatio(rawRatio, matchHeightConstraintsFirst = rawRatio < 1.0f)
                .shadow(
                    elevation = if (isMergeTarget) 12.dp else 6.dp,
                    shape = RoundedCornerShape(8.dp),
                    spotColor = if (isMergeTarget) colors.accent.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.22f),
                    ambientColor = Color.Black.copy(alpha = 0.14f),
                    clip = false,
                )
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .then(
                    if (isMergeTarget) {
                        Modifier.border(2.5.dp, colors.accent, RoundedCornerShape(8.dp))
                    } else if (cardRingWidth > 0.dp) {
                        Modifier.border(cardRingWidth, colors.accent, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
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
                        .graphicsLayer { alpha = thumbFade },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_canvas_tab),
                        contentDescription = null,
                        tint = Color(0xFFB0B0B0),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            // 选中蒙层
            Box(modifier = Modifier.fillMaxSize().background(selectTint))
        }

        // 自动保存角标
        if (project.isAutoSaved) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_save),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.gallery_badge_autosave),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 动画画布角标
        if (project.isAnimation) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_clock),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.gallery_badge_anim),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 画集封面角标
        if (isFolderCover) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .shadow(2.dp, RoundedCornerShape(8.dp), clip = false)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panel.copy(alpha = 0.92f))
                    .border(0.5.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.gallery_cover_badge),
                        color = colors.text,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 多选模式勾选角标
        CardSelectionBadge(
            isSelectMode = isSelectMode,
            isSelected = isSelected,
            colors = colors,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )

        // 互叠合成新画集浮动提示徽章
        AnimatedVisibility(
            visible = isMergeTarget,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f) + fadeIn(tween(140)),
            exit = scaleOut(tween(120), targetScale = 0.9f) + fadeOut(tween(100)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel.copy(alpha = 0.96f))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_plus),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
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
}

/**
 * 画集扇形堆叠卡片缩略图容器 (3 层散落层叠 + 展开动画)
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // Stacked layer 1 (底左微倾)
        Box(
            modifier = Modifier
                .fillMaxSize(0.86f)
                .aspectRatio(ratio2, matchHeightConstraintsFirst = ratio2 < 1.0f)
                .graphicsLayer {
                    rotationZ = -7f - 5.5f * fanPress
                    translationX = (-7).dp.toPx() - 5.dp.toPx() * fanPress
                    translationY = 4.dp.toPx() + 3.dp.toPx() * fanPress
                }
                .shadow(4.dp, RoundedCornerShape(8.dp), clip = false)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFEDEDED)),
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
                        Color.Black.copy(alpha = 0.08f),
                    ),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE5E5E7)))
            }
        }

        // Stacked layer 2 (中右微倾)
        Box(
            modifier = Modifier
                .fillMaxSize(0.88f)
                .aspectRatio(ratio1, matchHeightConstraintsFirst = ratio1 < 1.0f)
                .graphicsLayer {
                    rotationZ = 6f + 5f * fanPress
                    translationX = 6.dp.toPx() + 5.dp.toPx() * fanPress
                    translationY = (-3).dp.toPx() - 2.dp.toPx() * fanPress
                }
                .shadow(6.dp, RoundedCornerShape(8.dp), clip = false)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF3F3F3)),
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
                        Color.Black.copy(alpha = 0.04f),
                    ),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEF0)))
            }
        }

        // Foreground main folder cover (顶层主封面)
        val finalRingWidth = maxOf(hoverRingWidth, borderStrokeWidth)
        Box(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .aspectRatio(ratio0, matchHeightConstraintsFirst = ratio0 < 1.0f)
                .graphicsLayer {
                    rotationZ = -0.8f - 1.0f * fanPress
                }
                .shadow(8.dp, RoundedCornerShape(8.dp), clip = false)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .then(
                    if (finalRingWidth > 0.dp) {
                        Modifier.border(
                            width = finalRingWidth,
                            color = colors.accent.copy(alpha = ringAlpha),
                            shape = RoundedCornerShape(8.dp),
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folders),
                        contentDescription = null,
                        tint = Color(0xFF757575),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.gallery_badge_stack),
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Badge: Folder count with layer icon pill
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .shadow(2.dp, RoundedCornerShape(12.dp), clip = false)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panel.copy(alpha = 0.92f))
                .border(0.5.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_folders),
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "${project.items.size}",
                    color = colors.text,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
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
            Box(
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel.copy(alpha = 0.96f))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folders),
                        contentDescription = null,
                        tint = colors.text,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.gallery_drag_drop_to_stack),
                        color = colors.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // 多选模式勾选角标
        CardSelectionBadge(
            isSelectMode = isSelectMode,
            isSelected = isSelected,
            colors = colors,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        )
    }
}

/**
 * 画作/画集卡片下方的元数据栏 (包含名称点击重命名、尺寸分辨率与笔画数)
 */
@Composable
internal fun CardMetaSection(
    project: Project,
    isSelectMode: Boolean,
    colors: AppColors,
    onRenameClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))

        // 标题行: 点击弹出重命名对话框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .combinedClickable(
                    enabled = true,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onRenameClick,
                    onLongClick = onLongClick,
                )
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = project.name,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(Modifier.height(2.dp))

        // 副标题行: 尺寸规格 (px) + 修改时间 / 自动保存 / 画集数量
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val autoSavedText = stringResource(R.string.gallery_status_autosave)

            // 尺寸规格标签 (非画集且具有有效尺寸时展示)
            val dimenFormat = stringResource(R.string.gallery_dimen_px)
            val dimenText = remember(project.width, project.height, project.isFolder, dimenFormat) {
                if (!project.isFolder && project.width > 0 && project.height > 0) {
                    String.format(Locale.getDefault(), dimenFormat, project.width, project.height)
                } else {
                    null
                }
            }

            val leftInfo = if (project.isFolder) {
                stringResource(R.string.gallery_stack_artworks_count, project.items.size)
            } else if (project.isAutoSaved) {
                if (dimenText != null) "$dimenText · $autoSavedText" else autoSavedText
            } else {
                dimenText ?: ""
            }

            if (leftInfo.isNotEmpty()) {
                Text(
                    text = leftInfo,
                    color = if (project.isAutoSaved) colors.accent else colors.subText,
                    fontSize = 11.sp,
                    fontWeight = if (project.isAutoSaved) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(Modifier.weight(1f, fill = false))
            }

            if (!project.isFolder && project.strokeCount > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.gallery_stroke_count, project.strokeCount),
                    color = colors.subText,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
