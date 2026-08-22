/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.create

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.reverie.paint.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Theme
import kotlin.math.max

data class CanvasPresetItem(
    val name: String,
    val width: Int,
    val height: Int,
    val defaultPpi: Int = 300,
    val category: String,
    val description: String = ""
)

/**
 * Calculates realistic maximum layer capacity based on device physical available RAM & JVM Heap limit.
 */
fun calculateRealMaxLayers(context: Context, width: Int, height: Int): Int {
    val w = max(64, width)
    val h = max(64, height)
    // 4 bytes per RGBA pixel + ~25% buffer overhead (undo history, mipmaps, composite buffer)
    val bytesPerLayer = (w.toLong() * h.toLong() * 4L * 1.25).toLong()

    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    actManager?.getMemoryInfo(memInfo)

    // Total available system RAM
    val availSysMem = memInfo.availMem
    // App's JVM max memory budget
    val maxHeap = Runtime.getRuntime().maxMemory()

    // Layer allocation budget: 50% of available RAM, capped reasonably by heap
    val layerMemoryBudget = if (availSysMem > 0) {
        (availSysMem * 0.40).toLong().coerceAtLeast(maxHeap)
    } else {
        (maxHeap * 0.75).toLong()
    }

    val calculated = (layerMemoryBudget / bytesPerLayer).toInt()
    return calculated.coerceIn(4, 250)
}

@Composable
fun CreatePage(vm: PaintViewModel) {
    val colors = Theme.current
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = 预设, 1 = 自定义

    // Custom Canvas States
    var customW by remember { mutableStateOf("2048") }
    var customH by remember { mutableStateOf("2048") }
    var customPpi by remember { mutableStateOf("300") }
    var isInfiniteMode by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("常用") }

    BackHandler {
        if (selectedTab == 1) {
            selectedTab = 0
        } else {
            vm.goHome()
        }
    }

    val categories = listOf("常用", "屏幕", "纸张印刷", "社交媒体")

    val customPresets = remember {
        mutableStateListOf<CanvasPresetItem>()
    }

    val defaultPresets = remember {
        listOf(
            CanvasPresetItem("无限画布", 4096, 4096, 300, "常用", "无边界自由创作，可随心向四周扩展绘制"),
            CanvasPresetItem("正方形 1:1", 2048, 2048, 300, "常用", "适合头像、插画与社交贴图"),
            CanvasPresetItem("标准 4:3", 2048, 1536, 300, "常用", "标准平板与经典绘画比例"),
            CanvasPresetItem("高清宽屏 16:9", 1920, 1080, 72, "常用", "影视与桌面壁纸通用标准"),
            CanvasPresetItem("竖屏 9:16", 1080, 1920, 72, "常用", "短视频与移动端海报"),
            CanvasPresetItem("平板全屏", 2388, 1668, 264, "屏幕", "完美铺满当前设备屏幕"),
            CanvasPresetItem("4K 超高清", 3840, 2160, 150, "屏幕", "高细节概念设计与场景绘制"),
            CanvasPresetItem("2K 标准", 2560, 1440, 100, "屏幕", "高效绘制，平衡画质与性能"),
            CanvasPresetItem("A4 纸张 (300 DPI)", 2480, 3508, 300, "纸张印刷", "标准打印尺寸，210×297 mm"),
            CanvasPresetItem("A5 便携手帐", 1748, 2480, 300, "纸张印刷", "手帐、明信片与插画印刷"),
            CanvasPresetItem("B5 漫画原稿", 2150, 3035, 350, "纸张印刷", "漫画单页与同人志标准"),
            CanvasPresetItem("头像特写", 1000, 1000, 72, "社交媒体", "高清社交平台头像"),
            CanvasPresetItem("长图条漫", 1080, 4000, 150, "社交媒体", "条漫与长图连载排版")
        )
    }

    val allPresets = remember(customPresets.size, selectedCategory) {
        (customPresets + defaultPresets).filter { it.category == selectedCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        // Minimalist Top Bar with Segmented Tab Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi.copy(alpha = 0.6f))
                    .border(1.dp, colors.border.copy(alpha = 0.6f), CircleShape)
                    .clickable {
                        if (selectedTab == 1) selectedTab = 0 else vm.goHome()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            // Centered Segmented Capsule Switcher
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(19.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("预设画布", "自定义尺寸").forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) colors.accent else Color.Transparent)
                            .clickable { selectedTab = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) colors.onAccent else colors.subText,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.width(16.dp))
            Box(modifier = Modifier.size(38.dp)) // Visual balance placeholder
        }

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { width -> width / 4 } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width / 4 } + fadeOut()
                    )
                } else {
                    (slideInHorizontally { width -> -width / 4 } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> width / 4 } + fadeOut()
                    )
                }.using(SizeTransform(clip = false))
            },
            modifier = Modifier.weight(1f),
            label = "CreatePageTabTransition"
        ) { tab ->
            if (tab == 1) {
                // Custom Canvas Panel
                val widthVal = customW.toIntOrNull() ?: 2048
                val heightVal = customH.toIntOrNull() ?: 2048
                val ppiVal = customPpi.toIntOrNull() ?: 300

                // Real RAM Layer Calculation
                val maxLayers = remember(widthVal, heightVal) {
                    calculateRealMaxLayers(context, widthVal, heightVal)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    // Dimension inputs card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.panel)
                            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "尺寸与分辨率",
                            color = colors.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            SizeInputField(
                                label = "宽度",
                                unit = "PX",
                                value = customW,
                                onValueChange = { customW = it },
                                modifier = Modifier.weight(1f)
                            )
                            SizeInputField(
                                label = "高度",
                                unit = "PX",
                                value = customH,
                                onValueChange = { customH = it },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // PPI Resolution Field with Quick Selectors
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("分辨率 (PPI / DPI)", color = colors.subText, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(72, 150, 300, 350).forEach { ppiOption ->
                                        val isSelected = customPpi == ppiOption.toString()
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isSelected) colors.accent else colors.panelHi)
                                                .clickable { customPpi = ppiOption.toString() }
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                "$ppiOption",
                                                color = if (isSelected) colors.onAccent else colors.text,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            SizeInputField(
                                label = "自定义 PPI",
                                unit = "DPI",
                                value = customPpi,
                                onValueChange = { customPpi = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Real Hardware Info Summary Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panelHi.copy(alpha = 0.5f))
                            .border(1.dp, colors.border.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("色彩空间", color = colors.subText, fontSize = 11.sp)
                            Spacer(Modifier.height(2.dp))
                            Text("sRGB IEC61966-2.1", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("设备可用运存支持图层", color = colors.subText, fontSize = 11.sp)
                            Spacer(Modifier.height(2.dp))
                            Text("最多 $maxLayers 层", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Infinite Canvas Mode Switch Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panelHi.copy(alpha = 0.5f))
                            .border(1.dp, colors.border.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .clickable { isInfiniteMode = !isInfiniteMode }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("无限画布模式", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("无边界自由创作，随心向四周扩展绘制", color = colors.subText, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isInfiniteMode,
                            onCheckedChange = { isInfiniteMode = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onAccent,
                                checkedTrackColor = colors.accent,
                                uncheckedThumbColor = colors.subText,
                                uncheckedTrackColor = colors.panel
                            )
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Actions: Save to Preset & Create Canvas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Save to Presets Button
                        var showSavePresetDialog by remember { mutableStateOf(false) }
                        var newPresetName by remember { mutableStateOf("") }

                        if (showSavePresetDialog) {
                            AlertDialog(
                                onDismissRequest = { showSavePresetDialog = false },
                                title = { Text("保存自定义预设", color = colors.text, fontWeight = FontWeight.Bold) },
                                text = {
                                    Column {
                                        Text("为此预设命名：", color = colors.subText, fontSize = 13.sp)
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = newPresetName,
                                            onValueChange = { newPresetName = it },
                                            placeholder = { Text("${widthVal}×${heightVal}", color = colors.subText) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = colors.accent,
                                                unfocusedBorderColor = colors.border,
                                                focusedContainerColor = colors.panelHi,
                                                unfocusedContainerColor = colors.panelHi
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val pName = newPresetName.ifBlank { "自定义 ${widthVal}×${heightVal}" }
                                        customPresets.add(
                                            0,
                                            CanvasPresetItem(pName, widthVal, heightVal, ppiVal, "常用", "自定义预设 · $pName")
                                        )
                                        showSavePresetDialog = false
                                        selectedTab = 0
                                    }) {
                                        Text("保存", color = colors.accent, fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showSavePresetDialog = false }) {
                                        Text("取消", color = colors.subText)
                                    }
                                },
                                containerColor = colors.panel
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(0.42f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.panelHi)
                                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                .clickable {
                                    newPresetName = "预设 ${widthVal}×${heightVal}"
                                    showSavePresetDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_bookmark_plus), contentDescription = null, tint = colors.text, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("保存预设", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Confirm Create Button
                        val createSource = remember { MutableInteractionSource() }
                        val isCreatePressed by createSource.collectIsPressedAsState()
                        val btnScale by animateFloatAsState(
                            targetValue = if (isCreatePressed) 0.96f else 1.0f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                            label = "CreateActionScale"
                        )

                        Box(
                            modifier = Modifier
                                .weight(0.58f)
                                .scale(btnScale)
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.accent)
                                .clickable(interactionSource = createSource, indication = null) {
                                    val finalW = customW.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
                                    val finalH = customH.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
                                    vm.startPainting(finalW, finalH, isInfiniteCanvas = isInfiniteMode)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "创建画布",
                                color = colors.onAccent,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                // Preset Categories & Canvas List
                Column(modifier = Modifier.fillMaxSize()) {
                    // Category Chips Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSelected = selectedCategory == cat
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSelected) colors.panelHi else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) colors.accent.copy(alpha = 0.8f) else colors.border.copy(alpha = 0.5f),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable { selectedCategory = cat }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = cat,
                                    color = if (isSelected) colors.accent else colors.subText,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Preset List without redundant icons
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(allPresets, key = { "${it.name}_${it.width}_${it.height}" }) { item ->
                            val itemSource = remember { MutableInteractionSource() }
                            val isItemPressed by itemSource.collectIsPressedAsState()
                            val itemScale by animateFloatAsState(
                                targetValue = if (isItemPressed) 0.97f else 1.0f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                                label = "PresetItemScale"
                            )

                            val realLayers = remember(item.width, item.height) {
                                calculateRealMaxLayers(context, item.width, item.height)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .scale(itemScale)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.panel)
                                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                                    .clickable(interactionSource = itemSource, indication = null) {
                                        val isInfinite = item.name.contains("无限")
                                        vm.startPainting(item.width, item.height, isInfiniteCanvas = isInfinite)
                                    }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        color = colors.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (item.description.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = item.description,
                                            color = colors.subText,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${item.width} × ${item.height}",
                                        color = colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${item.defaultPpi} DPI · 最大 ${realLayers}层",
                                        color = colors.subText,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SizeInputField(
    label: String,
    unit: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Column(modifier = modifier) {
        Text(label, color = colors.subText, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                if (v.length <= 5) onValueChange(v.filter { it.isDigit() })
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            trailingIcon = {
                Text(unit, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp))
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.panelHi,
                unfocusedContainerColor = colors.panelHi,
                cursorColor = colors.accent
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        )
    }
}
