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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import kotlin.math.abs
import kotlin.math.max

data class CanvasPresetItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val width: Int,
    val height: Int,
    val defaultPpi: Int = 300,
    val description: String = "",
    val isCustom: Boolean = false
)

/**
 * Calculates realistic maximum layer capacity based on device physical available RAM & JVM Heap limit.
 * Uses aggressive memory allocation budget (up to 65% availMem or 30% totalMem) and Krita's sparse tile weighting.
 */
fun calculateRealMaxLayers(context: Context, width: Int, height: Int): Int {
    val w = max(64, width)
    val h = max(64, height)
    // 4 bytes per RGBA pixel + 10% tile/mipmap overhead in Krita
    val bytesPerLayer = (w.toLong() * h.toLong() * 4L * 1.10).toLong().coerceAtLeast(1024L)

    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    actManager?.getMemoryInfo(memInfo)

    val availSysMem = memInfo.availMem
    val totalSysMem = memInfo.totalMem
    val maxHeap = Runtime.getRuntime().maxMemory()

    // Aggressive layer allocation budget: 65% of available RAM or 30% of total RAM, fallback to maxHeap
    val aggressiveBudget = if (totalSysMem > 0) {
        max(
            (availSysMem * 0.65).toLong(),
            (totalSysMem * 0.30).toLong()
        )
    } else {
        (maxHeap * 1.5).toLong()
    }

    val calculated = (aggressiveBudget / bytesPerLayer).toInt()
    return calculated.coerceIn(6, 300)
}

/**
 * Dynamically resolves physical device screen resolution.
 */
fun getDeviceScreenResolution(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        if (w > 0 && h > 0) return Pair(w, h)
    }
    val dm = context.resources.displayMetrics
    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
        return Pair(dm.widthPixels, dm.heightPixels)
    }
    return Pair(3000, 2120)
}

/**
 * Returns human-readable aspect ratio label.
 */
fun getAspectRatioLabel(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return ""
    // Specific standard paper / comic dimensions
    if ((w == 2150 && h == 3035) || (w == 3035 && h == 2150)) {
        return if (w <= h) "B5 漫画" else "B5 横版"
    }
    if ((w == 2480 && h == 3508) || (w == 3508 && h == 2480)) {
        return if (w <= h) "A4 纸张" else "A4 横版"
    }
    val ratio = w.toFloat() / h.toFloat()
    return when {
        abs(ratio - 1.0f) < 0.01f -> "1:1 正方形"
        abs(ratio - 16f / 9f) < 0.02f -> "16:9 宽屏"
        abs(ratio - 9f / 16f) < 0.02f -> "9:16 竖屏"
        abs(ratio - 4f / 3f) < 0.02f -> "4:3 标准"
        abs(ratio - 3f / 4f) < 0.02f -> "3:4 竖屏"
        abs(ratio - 1080f / 2400f) < 0.02f -> "20:9 手机壁纸"
        abs(ratio - 1080f / 4000f) < 0.02f -> "长图条漫"
        else -> {
            val gcdVal = gcd(w, h)
            val rw = w / gcdVal
            val rh = h / gcdVal
            if (rw in 1..20 && rh in 1..20) "$rw:$rh" else if (w >= h) "横屏画幅" else "竖屏画幅"
        }
    }
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return x
}

/**
 * SharedPreferences JSON storage for custom presets.
 */
object CustomPresetManager {
    private const val PREFS_NAME = "reverie_custom_presets"
    private const val KEY_PRESETS = "presets_json"

    fun loadPresets(context: Context): List<CanvasPresetItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        val list = mutableListOf<CanvasPresetItem>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CanvasPresetItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.getString("name"),
                        width = obj.getInt("width"),
                        height = obj.getInt("height"),
                        defaultPpi = obj.optInt("ppi", 300),
                        description = obj.optString("desc", ""),
                        isCustom = true
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun savePresets(context: Context, presets: List<CanvasPresetItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        for (item in presets) {
            val obj = org.json.JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("width", item.width)
            obj.put("height", item.height)
            obj.put("ppi", item.defaultPpi)
            obj.put("desc", item.description)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }
}

fun getSystemPresets(context: Context): List<CanvasPresetItem> {
    val (screenW, screenH) = getDeviceScreenResolution(context)
    return listOf(
        CanvasPresetItem(
            id = "sys_screen",
            name = "当前设备全屏",
            width = screenW,
            height = screenH,
            defaultPpi = 300,
            description = "完美铺满当前设备物理屏幕"
        ),
        CanvasPresetItem(
            id = "sys_square_2k",
            name = "正方形 2K",
            width = 2048,
            height = 2048,
            defaultPpi = 300,
            description = "头像、插画与社交贴图常用规格"
        ),
        CanvasPresetItem(
            id = "sys_square_4k",
            name = "正方形 4K",
            width = 4096,
            height = 4096,
            defaultPpi = 300,
            description = "超高清正方形精细绘制与高细节输出"
        ),
        CanvasPresetItem(
            id = "sys_16_9_4k",
            name = "4K UHD (16:9)",
            width = 3840,
            height = 2160,
            defaultPpi = 150,
            description = "超清横屏概念设计、影视与场景绘制"
        ),
        CanvasPresetItem(
            id = "sys_16_9_2k",
            name = "2K QHD (16:9)",
            width = 2560,
            height = 1440,
            defaultPpi = 100,
            description = "标准高清横屏壁纸与概念插画"
        ),
        CanvasPresetItem(
            id = "sys_16_9_fhd",
            name = "全高清 FHD (16:9)",
            width = 1920,
            height = 1080,
            defaultPpi = 72,
            description = "经典 16:9 影视标准与游戏插图"
        ),
        CanvasPresetItem(
            id = "sys_9_16_poster",
            name = "竖屏海报 (9:16)",
            width = 1080,
            height = 1920,
            defaultPpi = 72,
            description = "移动端全屏短视频封面与竖屏宣传海报"
        ),
        CanvasPresetItem(
            id = "sys_mobile_wallpaper",
            name = "手机壁纸",
            width = 1080,
            height = 2400,
            defaultPpi = 300,
            description = "主流全面屏手机高分辨率锁屏与桌面"
        ),
        CanvasPresetItem(
            id = "sys_a4_print",
            name = "A4 纸张印刷",
            width = 2480,
            height = 3508,
            defaultPpi = 300,
            description = "国际标准 A4 规格 (210×297 mm @ 300 DPI)"
        ),
        CanvasPresetItem(
            id = "sys_b5_comic",
            name = "B5 漫画原稿",
            width = 2150,
            height = 3035,
            defaultPpi = 350,
            description = "日系漫画单页与同人志标准黑白/彩色原稿"
        ),
        CanvasPresetItem(
            id = "sys_strip_comic",
            name = "网络长图条漫",
            width = 1080,
            height = 4000,
            defaultPpi = 150,
            description = "长条漫画排版与多格剧情连载长图"
        )
    )
}

@Composable
fun CreatePage(vm: PaintViewModel) {
    val colors = Theme.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 640

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val bmp = ImageImportHelper.decodeUriSafely(context, uri)
            if (bmp != null) {
                val name = ImageImportHelper.getFileName(context, uri) ?: "导入图片"
                val snapFile = ImageImportHelper.writeTempPng(context, bmp)
                vm.startPainting(
                    w = bmp.width,
                    h = bmp.height,
                    name = name,
                    initialBitmap = bmp,
                    initialSnapshotFile = snapFile,
                )
            } else {
                android.widget.Toast.makeText(context, "无法载入该图片", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Dynamic Device Screen Resolution
    val (deviceW, deviceH) = remember { getDeviceScreenResolution(context) }

    // Canvas States - default to device screen resolution
    var customW by remember { mutableStateOf(deviceW.toString()) }
    var customH by remember { mutableStateOf(deviceH.toString()) }
    var customPpi by remember { mutableStateOf("300") }

    val widthVal = customW.toIntOrNull() ?: deviceW
    val heightVal = customH.toIntOrNull() ?: deviceH
    val ppiVal = customPpi.toIntOrNull() ?: 300

    // Tab state: 0 = 系统预设, 1 = 保存的预设 (on phone, 2 = 自定义尺寸)
    var selectedTab by remember { mutableIntStateOf(0) }

    // Custom Presets List loaded from SharedPreferences
    val customPresets = remember {
        mutableStateListOf<CanvasPresetItem>().apply {
            addAll(CustomPresetManager.loadPresets(context))
        }
    }

    // System Presets
    val systemPresets = remember(deviceW, deviceH) {
        getSystemPresets(context)
    }

    // Real RAM Layer Calculation
    val maxLayers = remember(widthVal, heightVal) {
        calculateRealMaxLayers(context, widthVal, heightVal)
    }

    // Save Preset Dialog State
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    // Delete Preset Confirm Dialog State
    var presetToDelete by remember { mutableStateOf<CanvasPresetItem?>(null) }

    BackHandler {
        if (!isTablet && selectedTab != 0) {
            selectedTab = 0
        } else {
            vm.goHome()
        }
    }

    // Save Preset Dialog
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
                ReTextButton(
                    "保存",
                    onClick = {
                        val pName = newPresetName.ifBlank { "预设 ${widthVal}×${heightVal}" }
                        val newItem = CanvasPresetItem(
                            name = pName,
                            width = widthVal,
                            height = heightVal,
                            defaultPpi = ppiVal,
                            description = "自定义预设 · $pName",
                            isCustom = true
                        )
                        customPresets.add(0, newItem)
                        CustomPresetManager.savePresets(context, customPresets)
                        showSavePresetDialog = false
                        selectedTab = 1
                    },
                    textColor = colors.accent,
                    fontWeight = FontWeight.Bold,
                )
            },
            dismissButton = {
                ReTextButton("取消", { showSavePresetDialog = false }, textColor = colors.subText)
            },
            containerColor = colors.panel
        )
    }

    // Delete Preset Dialog
    if (presetToDelete != null) {
        val target = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("删除预设", color = colors.text, fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除自定义预设「${target.name}」吗？", color = colors.subText, fontSize = 14.sp) },
            confirmButton = {
                ReTextButton(
                    "删除",
                    onClick = {
                        customPresets.removeAll { it.id == target.id }
                        CustomPresetManager.savePresets(context, customPresets)
                        presetToDelete = null
                    },
                    textColor = colors.accent,
                    fontWeight = FontWeight.Bold
                )
            },
            dismissButton = {
                ReTextButton("取消", onClick = { presetToDelete = null }, textColor = colors.subText)
            },
            containerColor = colors.panel
        )
    }

    val onFlipOrientation = {
        val tmp = customW
        customW = customH
        customH = tmp
    }

    val onStartPainting = {
        val finalW = customW.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
        val finalH = customH.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
        vm.startPainting(finalW, finalH)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        // Minimalist Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi.copy(alpha = 0.7f))
                    .border(1.dp, colors.border.copy(alpha = 0.6f), CircleShape)
                    .clickable {
                        if (!isTablet && selectedTab != 0) {
                            selectedTab = 0
                        } else {
                            vm.goHome()
                        }
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

            Text(
                text = "创建画布",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(colors.panelHi.copy(alpha = 0.7f))
                    .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(19.dp))
                    .clickable { imagePickerLauncher.launch("image/*") }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_image),
                        contentDescription = "从图片新建",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "从图片新建",
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (isTablet) {
            // Tablet Split Column Layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Left Column: Presets Manager (Weight 1.15f)
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                ) {
                    SegmentedTabSwitcher(
                        tabs = listOf("系统预设", "保存的预设"),
                        selectedIndex = selectedTab.coerceIn(0, 1),
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    if (selectedTab == 0) {
                        // System Presets
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)
                        ) {
                            item(key = "import_image_card") {
                                ImportImageCard(onImport = { imagePickerLauncher.launch("image/*") })
                            }

                            items(systemPresets, key = { it.id }) { item ->
                                val isSelected = (widthVal == item.width && heightVal == item.height)
                                val itemLayers = remember(item.width, item.height) {
                                    calculateRealMaxLayers(context, item.width, item.height)
                                }
                                CanvasPresetCard(
                                    item = item,
                                    isSelected = isSelected,
                                    maxLayers = itemLayers,
                                    onClick = {
                                        customW = item.width.toString()
                                        customH = item.height.toString()
                                        customPpi = item.defaultPpi.toString()
                                    }
                                )
                            }
                        }
                    } else {
                        // Saved Presets
                        if (customPresets.isEmpty()) {
                            SavedPresetsEmptyState(modifier = Modifier.weight(1f))
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(customPresets, key = { it.id }) { item ->
                                    val isSelected = (widthVal == item.width && heightVal == item.height)
                                    val itemLayers = remember(item.width, item.height) {
                                        calculateRealMaxLayers(context, item.width, item.height)
                                    }
                                    CanvasPresetCard(
                                        item = item,
                                        isSelected = isSelected,
                                        maxLayers = itemLayers,
                                        onClick = {
                                            customW = item.width.toString()
                                            customH = item.height.toString()
                                            customPpi = item.defaultPpi.toString()
                                        },
                                        onDelete = {
                                            presetToDelete = item
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.border.copy(alpha = 0.5f))
                )

                // Right Column: Live Ratio Preview + Dimensions Fine-tuning + Hardware Board + Action Bar
                Column(
                    modifier = Modifier
                        .weight(1.0f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CanvasRatioPreviewCard(
                            widthVal = widthVal,
                            heightVal = heightVal,
                            onFlip = onFlipOrientation
                        )

                        CanvasDimensionsCard(
                            width = customW,
                            onWidthChange = { customW = it },
                            height = customH,
                            onHeightChange = { customH = it },
                            ppi = customPpi,
                            onPpiChange = { customPpi = it }
                        )

                        HardwareLayerStatusCard(maxLayers = maxLayers)
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    CreateCanvasActions(
                        onSavePreset = {
                            newPresetName = "预设 ${widthVal}×${heightVal}"
                            showSavePresetDialog = true
                        },
                        onCreate = onStartPainting,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        } else {
            // Phone Responsive Single Column Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Top aspect ratio preview card
                CanvasRatioPreviewCard(
                    widthVal = widthVal,
                    heightVal = heightVal,
                    onFlip = onFlipOrientation
                )

                Spacer(Modifier.height(10.dp))

                // Segmented Switcher for Phone: [ 系统预设 | 保存的预设 | 自定义尺寸 ]
                SegmentedTabSwitcher(
                    tabs = listOf("系统预设", "保存的预设", "自定义尺寸"),
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                when (selectedTab) {
                    0 -> {
                        // System Presets
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 10.dp)
                        ) {
                            item(key = "import_image_card") {
                                ImportImageCard(onImport = { imagePickerLauncher.launch("image/*") })
                            }
                            items(systemPresets, key = { it.id }) { item ->
                                val isSelected = (widthVal == item.width && heightVal == item.height)
                                val itemLayers = remember(item.width, item.height) {
                                    calculateRealMaxLayers(context, item.width, item.height)
                                }
                                CanvasPresetCard(
                                    item = item,
                                    isSelected = isSelected,
                                    maxLayers = itemLayers,
                                    onClick = {
                                        customW = item.width.toString()
                                        customH = item.height.toString()
                                        customPpi = item.defaultPpi.toString()
                                    }
                                )
                            }
                        }
                    }
                    1 -> {
                        // Saved Presets
                        if (customPresets.isEmpty()) {
                            SavedPresetsEmptyState(modifier = Modifier.weight(1f))
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 10.dp)
                            ) {
                                items(customPresets, key = { it.id }) { item ->
                                    val isSelected = (widthVal == item.width && heightVal == item.height)
                                    val itemLayers = remember(item.width, item.height) {
                                        calculateRealMaxLayers(context, item.width, item.height)
                                    }
                                    CanvasPresetCard(
                                        item = item,
                                        isSelected = isSelected,
                                        maxLayers = itemLayers,
                                        onClick = {
                                            customW = item.width.toString()
                                            customH = item.height.toString()
                                            customPpi = item.defaultPpi.toString()
                                        },
                                        onDelete = {
                                            presetToDelete = item
                                        }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        // Custom Dimension Editing
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CanvasDimensionsCard(
                                width = customW,
                                onWidthChange = { customW = it },
                                height = customH,
                                onHeightChange = { customH = it },
                                ppi = customPpi,
                                onPpiChange = { customPpi = it }
                            )
                            HardwareLayerStatusCard(maxLayers = maxLayers)
                        }
                    }
                }

                // Bottom Action Bar on Phone
                CreateCanvasActions(
                    onSavePreset = {
                        newPresetName = "预设 ${widthVal}×${heightVal}"
                        showSavePresetDialog = true
                    },
                    onCreate = onStartPainting,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun CanvasRatioPreviewCard(
    widthVal: Int,
    heightVal: Int,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    val ratioLabel = remember(widthVal, heightVal) { getAspectRatioLabel(widthVal, heightVal) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Top header of preview card: ratio label and flip button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                )
                Text(
                    text = ratioLabel,
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Flip orientation button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panelHi)
                    .border(1.dp, colors.border.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                    .clickable { onFlip() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flip_horizontal),
                    contentDescription = "对调宽高",
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "对调宽高",
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Ratio visualization box
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panelHi.copy(alpha = 0.4f))
                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val availW = maxWidth - 24.dp
            val availH = maxHeight - 24.dp
            val aspect = (widthVal.toFloat() / heightVal.coerceAtLeast(1).toFloat()).coerceIn(0.15f, 6.0f)

            val (targetW, targetH) = if (aspect >= (availW / availH)) {
                val w = availW
                val h = (w / aspect).coerceAtMost(availH)
                Pair(w, h)
            } else {
                val h = availH
                val w = (h * aspect).coerceAtMost(availW)
                Pair(w, h)
            }

            val animW by animateDpAsState(
                targetValue = targetW,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "previewAnimW"
            )
            val animH by animateDpAsState(
                targetValue = targetH,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "previewAnimH"
            )

            Box(
                modifier = Modifier
                    .size(animW, animH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent.copy(alpha = 0.15f))
                    .border(1.5.dp, colors.accent, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${widthVal} × ${heightVal}",
                    color = colors.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CanvasDimensionsCard(
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    ppi: String,
    onPpiChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "尺寸与分辨率",
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SizeInputField(
                label = "宽度",
                unit = "PX",
                value = width,
                onValueChange = onWidthChange,
                modifier = Modifier.weight(1f)
            )
            SizeInputField(
                label = "高度",
                unit = "PX",
                value = height,
                onValueChange = onHeightChange,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("分辨率 (PPI / DPI)", color = colors.subText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(72, 150, 300, 350).forEach { ppiOption ->
                        val isSelected = ppi == ppiOption.toString()
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) colors.accent else colors.panelHi)
                                .clickable { onPpiChange(ppiOption.toString()) }
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
                value = ppi,
                onValueChange = onPpiChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HardwareLayerStatusCard(
    maxLayers: Int,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    val (perfStatus, perfColor) = when {
        maxLayers >= 100 -> Pair("极佳 · 充裕预算", colors.accent)
        maxLayers >= 40 -> Pair("良好 · 畅快绘制", colors.text)
        else -> Pair("适度 · 性能受控", colors.subText)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelHi.copy(alpha = 0.5f))
            .border(1.dp, colors.border.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("色彩空间", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text("sRGB IEC61966-2.1", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("内存预算模式", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text("激进模式 (全速)", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("性能评估", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(perfStatus, color = perfColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("可用运存支持图层", color = colors.subText, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text("最多 $maxLayers 层", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CreateCanvasActions(
    onSavePreset: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Save preset button
        Box(
            modifier = Modifier
                .weight(0.40f)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.panelHi)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                .clickable { onSavePreset() },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(17.dp)
                )
                Text("保存预设", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                .weight(0.60f)
                .scale(btnScale)
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent)
                .clickable(interactionSource = createSource, indication = null) { onCreate() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "创建画布",
                color = colors.onAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CanvasPresetCard(
    item: CanvasPresetItem,
    isSelected: Boolean,
    maxLayers: Int,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    val itemSource = remember { MutableInteractionSource() }
    val isItemPressed by itemSource.collectIsPressedAsState()
    val itemScale by animateFloatAsState(
        targetValue = if (isItemPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "PresetItemScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(itemScale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) colors.panelHi else colors.panel)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) colors.accent else colors.border,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = itemSource, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.name,
                    color = if (isSelected) colors.accent else colors.text,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                )
                if (item.isCustom) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("自定义", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (item.description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.description,
                    color = colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${item.width} × ${item.height}",
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.defaultPpi} DPI · 最大 ${maxLayers}层",
                color = colors.subText,
                fontSize = 11.sp
            )
        }

        if (onDelete != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi.copy(alpha = 0.7f))
                    .border(1.dp, colors.border.copy(alpha = 0.5f), CircleShape)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = "删除预设",
                    tint = colors.subText,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun ImportImageCard(
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    val importSource = remember { MutableInteractionSource() }
    val isImportPressed by importSource.collectIsPressedAsState()
    val importScale by animateFloatAsState(
        targetValue = if (isImportPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "ImportCardScale"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(importScale)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panel)
            .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = importSource, indication = null) { onImport() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "从图片新建画布",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "导入设备中的图片并以此尺寸创建画布",
                color = colors.subText,
                fontSize = 11.sp
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = colors.subText,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SavedPresetsEmptyState(
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = "暂无保存的预设",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "在参数面板调整尺寸后点击「保存预设」即可在此随时调用",
                color = colors.subText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SegmentedTabSwitcher(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(19.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .clickable { onTabSelected(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = if (isSelected) colors.onAccent else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
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
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.panelHi,
                unfocusedContainerColor = colors.panelHi,
                cursorColor = colors.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
