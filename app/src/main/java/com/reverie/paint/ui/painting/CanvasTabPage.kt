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

@Composable
internal fun CanvasTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf(vm.docName) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    var showCanvasResizeDialog by remember { mutableStateOf(false) }
    var showImageAdjustDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }

    var newW by remember { mutableStateOf("1080") }
    var newH by remember { mutableStateOf("1920") }
    var resizeW by remember { mutableStateOf(vm.docWidth.toString()) }
    var resizeH by remember { mutableStateOf(vm.docHeight.toString()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Custom Styled Dialog: Save As
    if (showSaveAsDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showSaveAsDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "另存为工程",
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = saveAsName,
                        onValueChange = { saveAsName = it },
                        singleLine = true,
                        placeholder = { Text("工程名称", color = Morandi.subText) },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Morandi.text,
                            unfocusedTextColor = Morandi.text,
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Morandi.border,
                            focusedContainerColor = Morandi.panelHi,
                            unfocusedContainerColor = Morandi.panelHi,
                            cursorColor = Morandi.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = { showSaveAsDialog = false }) {
                            Text("取消", color = Morandi.subText)
                        }
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            val name = saveAsName.trim()
                            showSaveAsDialog = false
                            onClose()
                            if (name.isNotBlank()) {
                                vm.saveProject(name) {
                                    android.widget.Toast.makeText(context, "已另存为 $name.revp", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Text("保存", color = Morandi.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Styled Dialog: New Canvas
    if (showNewCanvasDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showNewCanvasDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "新建画布",
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = newW,
                            onValueChange = { newW = it },
                            singleLine = true,
                            label = { Text("宽度", color = Morandi.subText, fontSize = 12.sp) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Morandi.text,
                                unfocusedTextColor = Morandi.text,
                                focusedBorderColor = Morandi.accent,
                                unfocusedBorderColor = Morandi.border,
                                focusedContainerColor = Morandi.panelHi,
                                unfocusedContainerColor = Morandi.panelHi,
                                cursorColor = Morandi.accent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = newH,
                            onValueChange = { newH = it },
                            singleLine = true,
                            label = { Text("高度", color = Morandi.subText, fontSize = 12.sp) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Morandi.text,
                                unfocusedTextColor = Morandi.text,
                                focusedBorderColor = Morandi.accent,
                                unfocusedBorderColor = Morandi.border,
                                focusedContainerColor = Morandi.panelHi,
                                unfocusedContainerColor = Morandi.panelHi,
                                cursorColor = Morandi.accent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = { showNewCanvasDialog = false }) {
                            Text("取消", color = Morandi.subText)
                        }
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            val w = newW.toIntOrNull() ?: 1080
                            val h = newH.toIntOrNull() ?: 1920
                            vm.startPainting(w, h, "画布_${System.currentTimeMillis() % 1000}")
                            showNewCanvasDialog = false
                            onClose()
                        }) {
                            Text("创建", color = Morandi.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Styled Dialog: Resize Canvas
    if (showCanvasResizeDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showCanvasResizeDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "调整画布尺寸",
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "修改画布分辨率边界",
                        color = Morandi.subText,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = resizeW,
                            onValueChange = { resizeW = it },
                            singleLine = true,
                            label = { Text("目标宽 (px)", color = Morandi.subText, fontSize = 12.sp) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Morandi.text,
                                unfocusedTextColor = Morandi.text,
                                focusedBorderColor = Morandi.accent,
                                unfocusedBorderColor = Morandi.border,
                                focusedContainerColor = Morandi.panelHi,
                                unfocusedContainerColor = Morandi.panelHi,
                                cursorColor = Morandi.accent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = resizeH,
                            onValueChange = { resizeH = it },
                            singleLine = true,
                            label = { Text("目标高 (px)", color = Morandi.subText, fontSize = 12.sp) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Morandi.text,
                                unfocusedTextColor = Morandi.text,
                                focusedBorderColor = Morandi.accent,
                                unfocusedBorderColor = Morandi.border,
                                focusedContainerColor = Morandi.panelHi,
                                unfocusedContainerColor = Morandi.panelHi,
                                cursorColor = Morandi.accent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = { showCanvasResizeDialog = false }) {
                            Text("取消", color = Morandi.subText)
                        }
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            val targetW = resizeW.toIntOrNull() ?: vm.docWidth
                            val targetH = resizeH.toIntOrNull() ?: vm.docHeight
                            vm.cropCanvas(0, 0, targetW, targetH)
                            android.widget.Toast.makeText(context, "画布已调整为 $targetW×$targetH", android.widget.Toast.LENGTH_SHORT).show()
                            showCanvasResizeDialog = false
                            onClose()
                        }) {
                            Text("确定", color = Morandi.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Styled Dialog: Image Adjust
    if (showImageAdjustDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showImageAdjustDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "图像滤镜与调整",
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "对当前活动图层应用快捷效果",
                        color = Morandi.subText,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val filters = listOf(
                            "黑白滤镜" to 0,
                            "反相颜色" to 1,
                            "轻微模糊" to 2,
                            "画面锐化" to 3
                        )
                        filters.forEach { (lbl, fid) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        vm.applyFilter(vm.currentLayerIndex, fid)
                                        android.widget.Toast.makeText(context, "已应用 $lbl", android.widget.Toast.LENGTH_SHORT).show()
                                        showImageAdjustDialog = false
                                        onClose()
                                    }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(lbl, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron),
                                        contentDescription = null,
                                        tint = Morandi.subText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = { showImageAdjustDialog = false }) {
                            Text("关闭", color = Morandi.subText)
                        }
                    }
                }
            }
        }
    }

    // Custom Styled Dialog: Color Profile
    if (showProfileDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showProfileDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "色彩描述文件",
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingInfoRow("色彩空间", "sRGB IEC61966-2.1")
                        SettingInfoRow("通道位深", "8位整数 (8-bit)")
                        SettingInfoRow("色彩模式", "RGB + Alpha 通道")
                        SettingInfoRow("颜色引擎", "Krita Pigment Engine")
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        androidx.compose.material3.TextButton(onClick = { showProfileDialog = false }) {
                            Text("确定", color = Morandi.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // Dynamic Document Info in a sleek Morandi card
        val sdf = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
        val createdStr = remember(vm.canvasCreatedTime) { sdf.format(Date(vm.canvasCreatedTime)) }
        val hours = vm.elapsedSeconds / 3600
        val mins = (vm.elapsedSeconds % 3600) / 60
        val secs = vm.elapsedSeconds % 60
        val durationStr = if (hours > 0) {
            String.format(Locale.getDefault(), "%d小时%02d分钟", hours, mins)
        } else if (mins > 0) {
            String.format(Locale.getDefault(), "%d分钟%02d秒", mins, secs)
        } else {
            String.format(Locale.getDefault(), "%d秒", secs)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SettingInfoRow("创建时间", createdStr)
            SettingInfoRow("画布尺寸", "${vm.docWidth}×${vm.docHeight} - 300ppi")
            SettingInfoRow("一共画了", "${vm.totalStrokes} 笔 / ${vm.layerCount} 图层")
            SettingInfoRow("作画耗时", durationStr)
            SettingInfoRow("颜色模式", vm.colorMode)
        }
        
        Spacer(Modifier.height(12.dp))

        // Action Grid (Equal 4-column modern card buttons)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReMenuItem(R.drawable.ic_save, "保存", {
                onClose()
                vm.saveProject(vm.docName) {
                    android.widget.Toast.makeText(context, "工程已保存 (${vm.docName}.revp)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_save_as, "另存为", {
                saveAsName = vm.docName + "_copy"
                showSaveAsDialog = true
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_file_repair, "修复草稿", {
                vm.recompositeProjection()
                android.widget.Toast.makeText(context, "已刷新并重构画布合成缓存", android.widget.Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_file_new, "新建画布", {
                showNewCanvasDialog = true
            }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReMenuItem(R.drawable.ic_canvas_resize, "画布调整", {
                resizeW = vm.docWidth.toString()
                resizeH = vm.docHeight.toString()
                showCanvasResizeDialog = true
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_image_adjust, "图像调整", {
                showImageAdjustDialog = true
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_flip_horizontal, "水平翻转", {
                vm.flipLayerHorizontal(vm.currentLayerIndex)
                onClose()
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_flip_vertical, "垂直翻转", {
                vm.flipLayerVertical(vm.currentLayerIndex)
                onClose()
            }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReMenuItem(R.drawable.ic_stamp, "画布盖印", {
                vm.stampVisibleLayers()
                android.widget.Toast.makeText(context, "已盖印可见图层至新图层", android.widget.Toast.LENGTH_SHORT).show()
                onClose()
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_color_profile, "颜色配置", {
                showProfileDialog = true
            }, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}


