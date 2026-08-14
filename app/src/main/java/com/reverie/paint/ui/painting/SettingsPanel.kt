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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
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

enum class SettingsTab { CANVAS, EXPORT, SETTINGS }

/**
 * Settings panel (top-right dropdown menu, multi-page 画世界 Pro style)
 */
@Composable
fun SettingsPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onResetView: () -> Unit = {},
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
    hazeState: HazeState? = null,
) {
    var currentTab by remember { mutableStateOf(SettingsTab.CANVAS) }
    val panelShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose),
    ) {
        Column(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Default)
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = 8.dp)
                .width(280.dp)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f)),
                                tint = HazeTint(Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f))),
                                blurRadius = 24.dp,
                                noiseFactor = 0.05f
                            )
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .border(1.dp, Morandi.border.copy(alpha = opacity), panelShape)
                .padding(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
        ) {
            // Top tab icons (3 tabs: 画布, 导出, 设置)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TabHeaderItem(
                    icon = R.drawable.ic_canvas_tab,
                    label = "画布",
                    selected = currentTab == SettingsTab.CANVAS,
                    onClick = { currentTab = SettingsTab.CANVAS }
                )
                TabHeaderItem(
                    icon = R.drawable.ic_export_tab,
                    label = "导出",
                    selected = currentTab == SettingsTab.EXPORT,
                    onClick = { currentTab = SettingsTab.EXPORT }
                )
                TabHeaderItem(
                    icon = R.drawable.ic_settings,
                    label = "设置",
                    selected = currentTab == SettingsTab.SETTINGS,
                    onClick = { currentTab = SettingsTab.SETTINGS }
                )
            }
            
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(tween(180, easing = FastOutSlowInEasing))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "SettingsTabTransition"
            ) { tab ->
                when (tab) {
                    SettingsTab.CANVAS -> CanvasTabPage(vm = vm, onClose = onClose)
                    SettingsTab.EXPORT -> ExportTabPage(vm = vm, onClose = onClose)
                    SettingsTab.SETTINGS -> SettingsTabPage(vm = vm, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun TabHeaderItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = Morandi.accent
    val inactiveColor = Morandi.icon
    val textColor = if (selected) activeColor else Morandi.text

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (selected) activeColor else inactiveColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CanvasTabPage(
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
                            if (saveAsName.isNotBlank()) {
                                vm.saveProject(saveAsName.trim())
                                android.widget.Toast.makeText(context, "已另存为 ${saveAsName.trim()}.revp", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            showSaveAsDialog = false
                            onClose()
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
                                        imageVector = androidx.compose.material.icons.Icons.Default.ChevronRight,
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
        val durationStr = String.format(Locale.getDefault(), "%02d小时%02d分钟", hours, mins)

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
                vm.saveProject(vm.docName)
                android.widget.Toast.makeText(context, "工程已保存 (${vm.docName}.revp)", android.widget.Toast.LENGTH_SHORT).show()
                onClose()
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

@Composable
private fun ExportTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf("PNG") }
    val formats = listOf("PNG", "JPEG", "PSD", "TIFF", "KRA", "REVP")
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("导出格式", color = Morandi.subText, fontSize = 12.sp)

        // 2 rows of 3 columns for 6 export formats
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                formats.take(3).forEach { fmt ->
                    val isSel = selectedFormat == fmt
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel)
                            .border(1.dp, if (isSel) Morandi.accent else Morandi.border, RoundedCornerShape(8.dp))
                            .clickable { selectedFormat = fmt },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmt,
                            color = if (isSel) Morandi.accent else Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                formats.drop(3).forEach { fmt ->
                    val isSel = selectedFormat == fmt
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel)
                            .border(1.dp, if (isSel) Morandi.accent else Morandi.border, RoundedCornerShape(8.dp))
                            .clickable { selectedFormat = fmt },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmt,
                            color = if (isSel) Morandi.accent else Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Actions: Export & Share
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.accent)
                .clickable {
                    val ext = selectedFormat.lowercase()
                    val targetDir = context.getExternalFilesDir("exports") ?: context.cacheDir
                    targetDir.mkdirs()
                    val exportFile = java.io.File(targetDir, "${vm.docName}_export.$ext")
                    vm.exportDocument(
                        format = ext,
                        targetFile = exportFile,
                        onSuccess = { file ->
                            android.widget.Toast.makeText(context, "导出成功: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                            onClose()
                        },
                        onError = { err ->
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
            Text("导出 $selectedFormat 文件", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(8.dp))
                .clickable {
                    val ext = selectedFormat.lowercase()
                    val shareDir = java.io.File(context.cacheDir, "share")
                    shareDir.mkdirs()
                    val shareFile = java.io.File(shareDir, "${vm.docName}.$ext")
                    vm.exportDocument(
                        format = ext,
                        targetFile = shareFile,
                        onSuccess = { file ->
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
            Text("分享当前作品", color = Morandi.text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit
) {
    var isRightHanded by remember { mutableStateOf(false) }
    var stabilizer by remember { mutableFloatStateOf(0.15f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 1. 笔模式 (快速切换)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("笔模式", color = Morandi.text, fontSize = 13.sp)
            ReSwitch(
                checked = vm.penOnlyMode,
                onChecked = { vm.updatePenOnlyMode(it) }
            )
        }

        Spacer(Modifier.height(6.dp))

        // List item links with chevron
        SettingNavRow("视图显示") {}
        SettingNavRow("手势") {}
        SettingNavRow("手写笔设置") {
            vm.homeSelectedTab = 1
            vm.settingsInitialSubPage = "STYLUS"
            vm.goHome()
            onClose()
        }
        SettingNavRow("快捷键设置") {}
        SettingNavRow("颜色设置") {}

        // 更多设置 -> 跳转到设置页面
        SettingNavRow("更多设置") {
            vm.homeSelectedTab = 1
            vm.settingsInitialSubPage = "MAIN"
            vm.goHome()
            onClose()
        }

        Spacer(Modifier.height(4.dp))

        // 8. 抖动修正
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("抖动修正", color = Morandi.text, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Morandi.panel)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("${(stabilizer * 100).toInt()}%", color = Morandi.subText, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(6.dp))

        // Compact Slider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Morandi.panel)
            )
            // Active Track
            Box(
                modifier = Modifier
                    .fillMaxWidth(stabilizer.coerceIn(0.01f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Morandi.accent)
            )
            // Thumb
            Box(
                modifier = Modifier
                    .padding(start = ((280 - 24 - 16) * stabilizer).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Morandi.text)
                    .border(2.dp, Morandi.panelHi, CircleShape)
            )
        }
        
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingNavRow(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Morandi.text, fontSize = 13.sp)
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Morandi.subText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Morandi.subText, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Morandi.text, fontSize = 12.sp)
    }
}
