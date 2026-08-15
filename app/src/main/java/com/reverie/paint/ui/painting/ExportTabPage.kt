package com.reverie.paint.ui.painting

import com.reverie.paint.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReMenuItem
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

data class ExportFormatItem(
    val format: String,
    val name: String,
    val description: String,
    val tag: String,
    val isLayered: Boolean
)

@Composable
internal fun ExportTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf("PNG") }
    var isExporting by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportFormats = remember {
        listOf(
            ExportFormatItem(
                format = "PNG",
                name = "PNG 图像",
                description = "无损透明合层，最常用的位图格式",
                tag = "无损合层",
                isLayered = false
            ),
            ExportFormatItem(
                format = "JPEG",
                name = "JPEG 图像",
                description = "高品质压缩合并图，适合网络快速分享",
                tag = "轻量分享",
                isLayered = false
            ),
            ExportFormatItem(
                format = "PSD",
                name = "Photoshop 分层",
                description = "完整保留各图层、混合模式与剪裁属性",
                tag = "分层工程",
                isLayered = true
            ),
            ExportFormatItem(
                format = "KRA",
                name = "Krita 原生工程",
                description = "标准 Krita 规范，含继承透明度与正片叠底",
                tag = "Krita 原生",
                isLayered = true
            ),
            ExportFormatItem(
                format = "REVP",
                name = "ReveriePaint 原生",
                description = "专有工程包，完整保留活跃作画耗时与图层数据",
                tag = "原生工程",
                isLayered = true
            ),
            ExportFormatItem(
                format = "TIFF",
                name = "TIFF 图像",
                description = "高保真出版级无损位图，色彩还原精准",
                tag = "出版印刷",
                isLayered = false
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("选择导出格式", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${exportFormats.size} 种格式支持", color = Morandi.subText, fontSize = 11.sp)
        }

        // Format Cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            exportFormats.forEach { item ->
                val isSel = selectedFormat == item.format
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSel) Morandi.accent.copy(alpha = 0.12f) else Morandi.panel)
                        .border(
                            width = if (isSel) 1.5.dp else 1.dp,
                            color = if (isSel) Morandi.accent else Morandi.border.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { selectedFormat = item.format }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    color = if (isSel) Morandi.accent else Morandi.text,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (item.isLayered) Morandi.accent.copy(alpha = 0.15f)
                                            else Morandi.subText.copy(alpha = 0.12f)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.tag,
                                        color = if (item.isLayered) Morandi.accent else Morandi.subText,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = item.description,
                                color = Morandi.subText,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }

                        if (isSel) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Action Buttons: Save to File & Share
        val currentItem = exportFormats.firstOrNull { it.format == selectedFormat } ?: exportFormats.first()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isExporting) Morandi.accent.copy(alpha = 0.6f) else Morandi.accent)
                .clickable(enabled = !isExporting) {
                    isExporting = true
                    val ext = selectedFormat.lowercase()
                    val targetDir = context.getExternalFilesDir("exports") ?: context.cacheDir
                    targetDir.mkdirs()
                    val exportFile = java.io.File(targetDir, "${vm.docName}_export.$ext")
                    vm.exportDocument(
                        format = ext,
                        targetFile = exportFile,
                        onSuccess = { file ->
                            isExporting = false
                            android.widget.Toast.makeText(context, "导出成功: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                            onClose()
                        },
                        onError = { err ->
                            isExporting = false
                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_export),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isExporting) "正在导出..." else "导出 ${currentItem.format} 文件",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                .clickable(enabled = !isExporting) {
                    isExporting = true
                    val ext = selectedFormat.lowercase()
                    val shareDir = java.io.File(context.cacheDir, "share")
                    shareDir.mkdirs()
                    val shareFile = java.io.File(shareDir, "${vm.docName}.$ext")
                    vm.exportDocument(
                        format = ext,
                        targetFile = shareFile,
                        onSuccess = { file ->
                            isExporting = false
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val mime = when (ext) {
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "psd" -> "image/vnd.adobe.photoshop"
                                    "tiff", "tif" -> "image/tiff"
                                    "kra" -> "application/x-krita"
                                    "revp" -> "application/x-reveriepaint"
                                    else -> "*/*"
                                }
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "分享作品"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            onClose()
                        },
                        onError = { err ->
                            isExporting = false
                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = null,
                tint = Morandi.text,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("分享 ${currentItem.format} 到其他应用", color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}


