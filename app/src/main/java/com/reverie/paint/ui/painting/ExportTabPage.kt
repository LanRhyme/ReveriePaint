/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.reverie.paint.R
import com.reverie.paint.core.AnimationExportOptions
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.exportAnimation
import com.reverie.paint.core.exportAnimationToGallery
import com.reverie.paint.core.exportDocument
import com.reverie.paint.core.exportImageToGallery
import com.reverie.paint.core.shareAnimation
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Morandi
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class ExportFormatItem(
    val format: String,
    val name: String,
    val description: String,
    val tag: String,
    val isLayered: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val hasAnimation = vm.anim.enabled

    // 分类状态：若从时间轴快捷跳转，优先开启「动画导出」
    var isAnimationMode by remember {
        mutableStateOf(hasAnimation && vm.targetExportAnimation)
    }

    LaunchedEffect(Unit) {
        vm.targetExportAnimation = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 顶部分类切换 (当且仅当该工程支持动画时展示)
        if (hasAnimation) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panelHi)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val staticInteraction = remember { MutableInteractionSource() }
                val animInteraction = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isAnimationMode) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(interactionSource = staticInteraction, indication = null) {
                            isAnimationMode = false
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "静态图像",
                        color = if (!isAnimationMode) Morandi.accent else Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = if (!isAnimationMode) FontWeight.Bold else FontWeight.Medium,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isAnimationMode) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable(interactionSource = animInteraction, indication = null) {
                            isAnimationMode = true
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "动画导出",
                        color = if (isAnimationMode) Morandi.accent else Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = if (isAnimationMode) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        AnimatedContent(
            targetState = isAnimationMode,
            transitionSpec = {
                fadeIn(tween(180, easing = FastOutSlowInEasing))
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "ExportCategoryTransition",
        ) { animMode ->
            if (animMode) {
                AnimationExportSection(vm = vm, onClose = onClose)
            } else {
                StaticExportSection(vm = vm, onClose = onClose)
            }
        }
    }
}

/**
 * 静态图像导出模块。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaticExportSection(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var selectedFormat by remember { mutableStateOf("PNG") }
    var isExporting by remember { mutableStateOf(false) }
    var embedAuthor by remember { mutableStateOf(vm.authorProfile.enabled) }

    val exportFormats = remember {
        listOf(
            ExportFormatItem(
                format = "PNG",
                name = "PNG 图像",
                description = "无损透明合层，最常用的位图格式",
                tag = "无损合层",
                isLayered = false,
            ),
            ExportFormatItem(
                format = "JPEG",
                name = "JPEG 图像",
                description = "高品质压缩合并图，适合网络快速分享",
                tag = "轻量分享",
                isLayered = false,
            ),
            ExportFormatItem(
                format = "WEBP",
                name = "WebP 现代图像",
                description = "新一代网络图像格式，支持高压缩率与无损透明",
                tag = "高效网络",
                isLayered = false,
            ),
            ExportFormatItem(
                format = "PSD",
                name = "Photoshop 分层",
                description = "完整保留各图层、混合模式与剪裁属性",
                tag = "分层工程",
                isLayered = true,
            ),
            ExportFormatItem(
                format = "KRA",
                name = "Krita 原生工程",
                description = "标准 Krita 规范，含继承透明度与正片叠底",
                tag = "Krita 原生",
                isLayered = true,
            ),
            ExportFormatItem(
                format = "REVP",
                name = "ReveriePaint 原生",
                description = "专有工程包，完整保留活跃作画耗时与图层数据",
                tag = "原生工程",
                isLayered = true,
            ),
            ExportFormatItem(
                format = "TIFF",
                name = "TIFF 图像",
                description = "高保真出版级无损位图，色彩还原精准",
                tag = "出版印刷",
                isLayered = false,
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择导出格式", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${exportFormats.size} 种格式支持", color = Morandi.subText, fontSize = 11.sp)
        }

        // 格式芯片列表
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            exportFormats.forEach { item ->
                val isSel = selectedFormat == item.format
                val chipInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .pressScale(chipInteraction, pressedScale = 0.94f)
                        .clip(RoundedCornerShape(10.dp))
                        .liquidHighlight(chipInteraction, Color.White, radius = 30.dp)
                        .background(if (isSel) Morandi.accent.copy(alpha = 0.12f) else Morandi.panel)
                        .border(
                            width = if (isSel) 1.dp else 0.dp,
                            color = if (isSel) Morandi.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable(interactionSource = chipInteraction, indication = null) {
                            selectedFormat = item.format
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.format,
                        color = if (isSel) Morandi.accent else Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        // 选中格式详情卡
        val detail = exportFormats.firstOrNull { it.format == selectedFormat } ?: exportFormats.first()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.subText.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(detail.tag, color = Morandi.subText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = detail.name,
                    color = Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail.description,
                    color = Morandi.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            if (detail.isLayered) {
                Icon(
                    painter = painterResource(R.drawable.ic_layerstack),
                    contentDescription = "包含图层数据",
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // 嵌入作者元数据卡片
        if (vm.authorProfile.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.panelHi)
                    .clickable { embedAuthor = !embedAuthor }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_author),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "嵌入作者元数据",
                            color = Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val authorSummary = vm.authorProfile.name.ifBlank { vm.authorProfile.nickname }
                        if (authorSummary.isNotBlank()) {
                            Text(
                                text = "创作者: $authorSummary",
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                ReSwitch(
                    checked = embedAuthor,
                    onChecked = { embedAuthor = it },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 按钮：导出到文件
        val currentItem = exportFormats.firstOrNull { it.format == selectedFormat } ?: exportFormats.first()
        val exportInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(exportInteraction, pressedScale = 0.97f)
                .height(46.dp)
                .shadow(8.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.30f))
                .clip(RoundedCornerShape(14.dp))
                .liquidHighlight(exportInteraction, Color.White, radius = 80.dp)
                .background(if (isExporting) Morandi.accent.copy(alpha = 0.6f) else Morandi.accent)
                .clickable(interactionSource = exportInteraction, indication = null, enabled = !isExporting) {
                    isExporting = true
                    val ext = selectedFormat.lowercase()
                    val targetDir = context.getExternalFilesDir("exports") ?: context.cacheDir
                    targetDir.mkdirs()
                    val exportFile = File(targetDir, "${vm.docName}_export.$ext")
                    vm.exportDocument(
                        format = ext,
                        targetFile = exportFile,
                        embedAuthor = embedAuthor,
                        onSuccess = { file ->
                            isExporting = false
                            Toast.makeText(context, "导出成功: ${file.name}", Toast.LENGTH_LONG).show()
                            onClose()
                        },
                        onError = { err ->
                            isExporting = false
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_export),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isExporting) "正在导出..." else "导出 ${currentItem.format} 文件",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (selectedFormat == "PNG" || selectedFormat == "JPEG" || selectedFormat == "WEBP") {
            val galleryInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(galleryInteraction, pressedScale = 0.97f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .liquidHighlight(galleryInteraction, Color.White, radius = 80.dp)
                    .background(Morandi.panel.copy(alpha = 0.8f))
                    .clickable(interactionSource = galleryInteraction, indication = null, enabled = !isExporting) {
                        isExporting = true
                        vm.exportImageToGallery(
                            format = selectedFormat.lowercase(),
                            embedAuthor = embedAuthor,
                            onSuccess = { _ ->
                                isExporting = false
                                Toast.makeText(context, "已成功保存到系统相册", Toast.LENGTH_LONG).show()
                                onClose()
                            },
                            onError = { err ->
                                isExporting = false
                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_save_as),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("保存到系统相册", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        val shareInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(shareInteraction, pressedScale = 0.97f)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .liquidHighlight(shareInteraction, Color.White, radius = 80.dp)
                .background(Morandi.panel.copy(alpha = 0.6f))
                .clickable(interactionSource = shareInteraction, indication = null, enabled = !isExporting) {
                    isExporting = true
                    val ext = selectedFormat.lowercase()
                    val shareDir = File(context.cacheDir, "share")
                    shareDir.mkdirs()
                    val shareFile = File(shareDir, "${vm.docName}.$ext")
                    vm.exportDocument(
                        format = ext,
                        targetFile = shareFile,
                        embedAuthor = embedAuthor,
                        onSuccess = { file ->
                            isExporting = false
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val mime = when (ext) {
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "webp" -> "image/webp"
                                    "psd" -> "image/vnd.adobe.photoshop"
                                    "tiff", "tif" -> "image/tiff"
                                    "kra" -> "application/x-krita"
                                    "revp" -> "application/x-reveriepaint"
                                    else -> "*/*"
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "分享作品"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            onClose()
                        },
                        onError = { err ->
                            isExporting = false
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = null,
                tint = Morandi.text,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("分享 ${currentItem.format} 到其他应用", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 动画导出专区模块。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimationExportSection(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    val animFormats = remember {
        listOf(
            ExportFormatItem(
                format = "GIF",
                name = "GIF 动图",
                description = "网络通用动态图片，支持循环与透明背景",
                tag = "动态分享",
                isLayered = false,
            ),
            ExportFormatItem(
                format = "MP4",
                name = "MP4 视频",
                description = "H.264 硬件加速高清视频，兼容各大视频平台",
                tag = "高清视频",
                isLayered = false,
            ),
            ExportFormatItem(
                format = "ZIP",
                name = "PNG 序列帧",
                description = "无损透明 PNG 序列帧归档包，适合专业后期合成",
                tag = "分帧无损",
                isLayered = false,
            ),
        )
    }

    var selectedFormat by remember { mutableStateOf("GIF") }
    var scaleRatio by remember { mutableFloatStateOf(1.0f) }
    var rangeMode by remember { mutableStateOf("all") } // "all", "range"
    var transparentBg by remember { mutableStateOf(false) }

    // 导出进度对话框状态
    var isExporting by remember { mutableStateOf(false) }
    var exportCurrentFrame by remember { mutableIntStateOf(0) }
    var exportTotalFrames by remember { mutableIntStateOf(1) }
    var exportStage by remember { mutableStateOf("") }
    var exportCancelled by remember { mutableStateOf(false) }

    val currentItem = animFormats.firstOrNull { it.format == selectedFormat } ?: animFormats.first()

    val computedWidth = max(2, (vm.coreW * scaleRatio).roundToInt())
    val computedHeight = max(2, (vm.coreH * scaleRatio).roundToInt())
    val finalWidth = if (selectedFormat == "MP4" && computedWidth % 2 != 0) computedWidth - 1 else computedWidth
    val finalHeight = if (selectedFormat == "MP4" && computedHeight % 2 != 0) computedHeight - 1 else computedHeight

    val exportOptions = AnimationExportOptions(
        format = selectedFormat.lowercase(),
        scale = scaleRatio,
        rangeMode = rangeMode,
        transparentBg = transparentBg && selectedFormat != "MP4",
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择动画格式", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${vm.anim.framerate} fps · ${vm.anim.length} 帧", color = Morandi.subText, fontSize = 11.sp)
        }

        // 格式芯片选择
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            animFormats.forEach { item ->
                val isSel = selectedFormat == item.format
                val chipInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .pressScale(chipInteraction, pressedScale = 0.94f)
                        .clip(RoundedCornerShape(10.dp))
                        .liquidHighlight(chipInteraction, Color.White, radius = 30.dp)
                        .background(if (isSel) Morandi.accent.copy(alpha = 0.12f) else Morandi.panel)
                        .border(
                            width = if (isSel) 1.dp else 0.dp,
                            color = if (isSel) Morandi.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable(interactionSource = chipInteraction, indication = null) {
                            selectedFormat = item.format
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.format,
                        color = if (isSel) Morandi.accent else Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        // 选中格式介绍卡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(currentItem.tag, color = Morandi.accent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = currentItem.name,
                    color = Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = currentItem.description,
                    color = Morandi.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }

        // 参数配置卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 分辨率缩放
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("分辨率比例", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "$finalWidth × $finalHeight 像素",
                        color = Morandi.subText,
                        fontSize = 10.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.0f to "100%", 0.5f to "50%", 0.25f to "25%").forEach { (ratio, label) ->
                        val isSel = scaleRatio == ratio
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) Morandi.accent else Morandi.panel)
                                .clickable { scaleRatio = ratio }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) Color.White else Morandi.text,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            // 帧范围
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("导出帧范围", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    val countText = if (rangeMode == "all") {
                        "共 ${vm.anim.length} 帧 (0 ~ ${vm.anim.length - 1})"
                    } else {
                        val s = vm.anim.playbackStart
                        val e = vm.anim.playbackEnd
                        "区间 ${s + 1} ~ ${e + 1} (共 ${max(1, e - s + 1)} 帧)"
                    }
                    Text(countText, color = Morandi.subText, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "全部帧", "range" to "播放区间").forEach { (mode, label) ->
                        val isSel = rangeMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) Morandi.accent else Morandi.panel)
                                .clickable { rangeMode = mode }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) Color.White else Morandi.text,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            // 透明背景开关 (仅 GIF / 序列帧有效)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("透明背景", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (selectedFormat == "MP4") "MP4 视频格式暂不支持透明背景" else "隐藏画布背景层并导出 Alpha 通道",
                        color = Morandi.subText,
                        fontSize = 10.sp,
                    )
                }
                ReSwitch(
                    checked = transparentBg && selectedFormat != "MP4",
                    onChecked = { transparentBg = it },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 按钮：导出动画到文件
        val exportInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(exportInteraction, pressedScale = 0.97f)
                .height(46.dp)
                .shadow(8.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.30f))
                .clip(RoundedCornerShape(14.dp))
                .liquidHighlight(exportInteraction, Color.White, radius = 80.dp)
                .background(if (isExporting) Morandi.accent.copy(alpha = 0.6f) else Morandi.accent)
                .clickable(interactionSource = exportInteraction, indication = null, enabled = !isExporting) {
                    isExporting = true
                    exportCancelled = false
                    val ext = selectedFormat.lowercase()
                    val targetDir = context.getExternalFilesDir("exports") ?: context.cacheDir
                    targetDir.mkdirs()
                    val exportFile = File(targetDir, "${vm.docName}_anim.$ext")

                    vm.exportAnimation(
                        options = exportOptions,
                        targetFile = exportFile,
                        isCancelled = { exportCancelled },
                        onProgress = { cur, tot, stage ->
                            exportCurrentFrame = cur
                            exportTotalFrames = tot
                            exportStage = stage
                        },
                        onSuccess = { file ->
                            isExporting = false
                            Toast.makeText(context, "动画导出成功: ${file.name}", Toast.LENGTH_LONG).show()
                            onClose()
                        },
                        onError = { err ->
                            isExporting = false
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_export),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isExporting) "正在导出..." else "导出 ${currentItem.format} 文件",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 保存到系统相册 (GIF / MP4 支持)
        if (selectedFormat == "GIF" || selectedFormat == "MP4") {
            val galleryInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(galleryInteraction, pressedScale = 0.97f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .liquidHighlight(galleryInteraction, Color.White, radius = 80.dp)
                    .background(Morandi.panel.copy(alpha = 0.8f))
                    .clickable(interactionSource = galleryInteraction, indication = null, enabled = !isExporting) {
                        isExporting = true
                        exportCancelled = false

                        vm.exportAnimationToGallery(
                            options = exportOptions,
                            isCancelled = { exportCancelled },
                            onProgress = { cur, tot, stage ->
                                exportCurrentFrame = cur
                                exportTotalFrames = tot
                                exportStage = stage
                            },
                            onSuccess = { _ ->
                                isExporting = false
                                Toast.makeText(context, "已成功保存到系统相册", Toast.LENGTH_LONG).show()
                                onClose()
                            },
                            onError = { err ->
                                isExporting = false
                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_save_as),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("保存到系统相册", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 分享给其他应用
        val shareInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(shareInteraction, pressedScale = 0.97f)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .liquidHighlight(shareInteraction, Color.White, radius = 80.dp)
                .background(Morandi.panel.copy(alpha = 0.6f))
                .clickable(interactionSource = shareInteraction, indication = null, enabled = !isExporting) {
                    isExporting = true
                    exportCancelled = false

                    vm.shareAnimation(
                        options = exportOptions,
                        isCancelled = { exportCancelled },
                        onProgress = { cur, tot, stage ->
                            exportCurrentFrame = cur
                            exportTotalFrames = tot
                            exportStage = stage
                        },
                        onSuccess = { file ->
                            isExporting = false
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val mime = when (selectedFormat.lowercase()) {
                                    "gif" -> "image/gif"
                                    "mp4" -> "video/mp4"
                                    "zip" -> "application/zip"
                                    else -> "*/*"
                                }
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "分享动画作品"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            onClose()
                        },
                        onError = { err ->
                            isExporting = false
                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = null,
                tint = Morandi.text,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("分享 ${currentItem.format} 到其他应用", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    // 导出动画时的模态进度弹窗
    if (isExporting) {
        Dialog(onDismissRequest = { /* 阻断点击外部关闭，需点击取消按钮 */ }) {
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panelHi)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "正在导出动画",
                    color = Morandi.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = exportStage.ifBlank { "准备中…" },
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
                val progress = if (exportTotalFrames > 0) {
                    (exportCurrentFrame.toFloat() / exportTotalFrames).coerceIn(0f, 1f)
                } else 0f

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = Morandi.accent,
                    trackColor = Morandi.panel,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$exportCurrentFrame / $exportTotalFrames 帧",
                        color = Morandi.subText,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "${(progress * 100).roundToInt()}%",
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ReTextButton(
                    text = "取消导出",
                    textColor = Morandi.subText,
                    onClick = { exportCancelled = true },
                )
            }
        }
    }
}
