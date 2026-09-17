/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import com.reverie.paint.core.playbackEndFrame
import com.reverie.paint.core.shareAnimation
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Morandi
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 携带指定 MIME 类型的文档创建合约。
 */
private class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mime, fileName) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, fileName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}

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

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = remember { CreateDocumentWithMime() },
    ) { uri: Uri? ->
        if (uri != null) {
            isExporting = true
            val ext = selectedFormat.lowercase()
            val tempFile = File(context.cacheDir, "export_tmp_${System.currentTimeMillis()}.$ext")
            vm.exportDocument(
                format = ext,
                targetFile = tempFile,
                embedAuthor = embedAuthor,
                onSuccess = { file ->
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        file.delete()
                        isExporting = false
                        val targetDesc = uri.lastPathSegment?.let { segment ->
                            if (segment.contains(":")) segment.substringAfterLast(":") else segment
                        } ?: "所选位置"
                        Toast.makeText(context, "已成功导出至: $targetDesc", Toast.LENGTH_LONG).show()
                        onClose()
                    } catch (e: Exception) {
                        isExporting = false
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { err ->
                    isExporting = false
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择导出格式", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${vm.coreW} × ${vm.coreH} 像素", color = Morandi.subText, fontSize = 11.sp)
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
                        .padding(horizontal = 14.dp, vertical = 8.dp),
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

        // 选中格式说明
        val detail = exportFormats.firstOrNull { it.format == selectedFormat } ?: exportFormats.first()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi)
                .padding(horizontal = 14.dp, vertical = 10.dp),
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

        Spacer(Modifier.height(2.dp))

        // 主操作：导出到文件 (自定义保存位置)
        val currentItem = exportFormats.firstOrNull { it.format == selectedFormat } ?: exportFormats.first()
        val exportInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(exportInteraction, pressedScale = 0.97f)
                .height(44.dp)
                .shadow(8.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(12.dp))
                .liquidHighlight(exportInteraction, Color.White, radius = 80.dp)
                .background(if (isExporting) Morandi.accent.copy(alpha = 0.6f) else Morandi.accent)
                .clickable(interactionSource = exportInteraction, indication = null, enabled = !isExporting) {
                    val ext = selectedFormat.lowercase()
                    val mime = when (selectedFormat.uppercase()) {
                        "PNG" -> "image/png"
                        "JPEG", "JPG" -> "image/jpeg"
                        "WEBP" -> "image/webp"
                        "PSD" -> "image/vnd.adobe.photoshop"
                        "TIFF" -> "image/tiff"
                        "KRA" -> "application/x-krita"
                        "REVP" -> "application/x-reveriepaint"
                        else -> "*/*"
                    }
                    createDocLauncher.launch(mime to "${vm.docName}_export.$ext")
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
                text = if (isExporting) "正在导出..." else "导出 ${currentItem.format} 文件 (选择保存位置)",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 次级操作栏：存入相册与分享
        val supportsGallery = selectedFormat == "PNG" || selectedFormat == "JPEG" || selectedFormat == "WEBP"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (supportsGallery) {
                val galleryInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(galleryInteraction, pressedScale = 0.97f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .liquidHighlight(galleryInteraction, Color.White, radius = 60.dp)
                        .background(Morandi.panelHi)
                        .clickable(interactionSource = galleryInteraction, indication = null, enabled = !isExporting) {
                            isExporting = true
                            vm.exportImageToGallery(
                                format = selectedFormat.lowercase(),
                                embedAuthor = embedAuthor,
                                onSuccess = { _ ->
                                    isExporting = false
                                    Toast.makeText(context, "已成功保存到系统相册 (Pictures/ReveriePaint)", Toast.LENGTH_LONG).show()
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
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("存入相册", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            val shareInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .pressScale(shareInteraction, pressedScale = 0.97f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .liquidHighlight(shareInteraction, Color.White, radius = 60.dp)
                    .background(Morandi.panelHi)
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
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("分享作品", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
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
    val isLargeCanvas = (vm.coreW * vm.coreH) > 1920 * 1080
    var scaleRatio by remember { mutableFloatStateOf(if (isLargeCanvas) 0.5f else 1.0f) }
    var rangeMode by remember { mutableStateOf("all") } // "all", "custom"
    val totalDrawn = maxOf(1, vm.anim.length, vm.playbackEndFrame() + 1)
    var customStartFrame by remember { mutableIntStateOf(1) }
    var customEndFrame by remember(totalDrawn) { mutableIntStateOf(totalDrawn) }
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

    val finalStartFrame = if (rangeMode == "all") 0 else (customStartFrame - 1).coerceAtLeast(0)
    val finalEndFrame = if (rangeMode == "all") (totalDrawn - 1).coerceAtLeast(0) else (customEndFrame - 1).coerceAtLeast(finalStartFrame)

    val exportOptions = AnimationExportOptions(
        format = selectedFormat.lowercase(),
        scale = scaleRatio,
        startFrame = finalStartFrame,
        endFrame = finalEndFrame,
        transparentBg = transparentBg && selectedFormat != "MP4",
    )

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = remember { CreateDocumentWithMime() },
    ) { uri: Uri? ->
        if (uri != null) {
            isExporting = true
            exportCancelled = false
            val ext = selectedFormat.lowercase()
            val tempFile = File(context.cacheDir, "export_anim_tmp_${System.currentTimeMillis()}.$ext")

            vm.exportAnimation(
                options = exportOptions,
                targetFile = tempFile,
                isCancelled = { exportCancelled },
                onProgress = { cur, tot, stage ->
                    exportCurrentFrame = cur
                    exportTotalFrames = tot
                    exportStage = stage
                },
                onSuccess = { file ->
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        file.delete()
                        isExporting = false
                        val targetDesc = uri.lastPathSegment?.let { segment ->
                            if (segment.contains(":")) segment.substringAfterLast(":") else segment
                        } ?: "所选位置"
                        Toast.makeText(context, "动画已成功导出至: $targetDesc", Toast.LENGTH_LONG).show()
                        onClose()
                    } catch (e: Exception) {
                        isExporting = false
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { err ->
                    isExporting = false
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择动画格式", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${vm.anim.framerate} fps · 共 $totalDrawn 帧", color = Morandi.subText, fontSize = 11.sp)
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

            // 帧范围选择卡片
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("导出帧范围", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        val countText = if (rangeMode == "all") {
                            "全部有效帧：第 1 ~ $totalDrawn 帧 (共 $totalDrawn 帧)"
                        } else {
                            val count = max(1, customEndFrame - customStartFrame + 1)
                            "指定范围：第 $customStartFrame ~ $customEndFrame 帧 (共 $count 帧)"
                        }
                        Text(countText, color = Morandi.subText, fontSize = 10.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "全部帧", "custom" to "自定义").forEach { (mode, label) ->
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

                // 自定义起止帧调节器
                if (rangeMode == "custom") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("起止帧", color = Morandi.subText, fontSize = 11.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("从 ", color = Morandi.subText, fontSize = 11.sp)
                                FrameStepper(
                                    value = customStartFrame,
                                    onValueChange = { customStartFrame = it.coerceIn(1, customEndFrame) },
                                    min = 1,
                                    max = customEndFrame,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("至 ", color = Morandi.subText, fontSize = 11.sp)
                                FrameStepper(
                                    value = customEndFrame,
                                    onValueChange = { customEndFrame = it.coerceAtLeast(customStartFrame) },
                                    min = customStartFrame,
                                    max = 9999,
                                )
                            }
                        }
                    }
                }

                // 提示说明卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info_circle),
                        contentDescription = null,
                        tint = Morandi.subText,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "动画创作支持任意长帧数，无上限限制；默认导出已绘制全部帧",
                        color = Morandi.subText,
                        fontSize = 10.sp,
                    )
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

        Spacer(Modifier.height(2.dp))

        // 主操作：导出动画到文件 (自定义保存位置)
        val currentItem = animFormats.firstOrNull { it.format == selectedFormat } ?: animFormats.first()
        val exportInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(exportInteraction, pressedScale = 0.97f)
                .height(44.dp)
                .shadow(8.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(RoundedCornerShape(12.dp))
                .liquidHighlight(exportInteraction, Color.White, radius = 80.dp)
                .background(if (isExporting) Morandi.accent.copy(alpha = 0.6f) else Morandi.accent)
                .clickable(interactionSource = exportInteraction, indication = null, enabled = !isExporting) {
                    val ext = selectedFormat.lowercase()
                    val mime = when (selectedFormat.uppercase()) {
                        "MP4" -> "video/mp4"
                        "GIF" -> "image/gif"
                        "ZIP" -> "application/zip"
                        else -> "*/*"
                    }
                    createDocLauncher.launch(mime to "${vm.docName}_anim.$ext")
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
                text = if (isExporting) "正在导出..." else "导出 ${currentItem.format} 文件 (选择保存位置)",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 次级操作栏：存入相册与分享
        val supportsGallery = selectedFormat == "GIF" || selectedFormat == "MP4"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (supportsGallery) {
                val galleryInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(galleryInteraction, pressedScale = 0.97f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .liquidHighlight(galleryInteraction, Color.White, radius = 60.dp)
                        .background(Morandi.panelHi)
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
                                    Toast.makeText(context, "已成功保存到系统相册 (Pictures/ReveriePaint)", Toast.LENGTH_LONG).show()
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
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("存入相册", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            val shareInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .pressScale(shareInteraction, pressedScale = 0.97f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .liquidHighlight(shareInteraction, Color.White, radius = 60.dp)
                    .background(Morandi.panelHi)
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
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("分享作品", color = Morandi.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    // 导出动画时的模态进度弹窗
    if (isExporting) {
        Dialog(
            onDismissRequest = {
                exportCancelled = true
                exportStage = "正在取消…"
            },
        ) {
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
                    text = if (exportCancelled) "正在取消…" else "取消导出",
                    textColor = if (exportCancelled) Morandi.subText.copy(alpha = 0.5f) else Morandi.subText,
                    onClick = {
                        exportCancelled = true
                        exportStage = "正在取消…"
                    },
                )
            }
        }
    }
}

/**
 * 帧数步进微调组件。
 */
@Composable
private fun FrameStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Morandi.panelHi)
                .clickable(enabled = value > min) { onValueChange(value - 1) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "-",
                color = if (value > min) Morandi.text else Morandi.subText.copy(alpha = 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "$value",
            color = Morandi.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Morandi.panelHi)
                .clickable(enabled = value < max) { onValueChange(value + 1) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = if (value < max) Morandi.text else Morandi.subText.copy(alpha = 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
