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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

    // Wide landscape check: only large screens in landscape use 2-column split view
    val isWideLandscape = configuration.screenWidthDp >= 720 &&
            configuration.screenHeightDp >= 500 &&
            configuration.screenWidthDp > configuration.screenHeightDp

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
    // 动画画布: 勾选后新建的文档自带时间轴 (后端走 startPainting(animation = true))
    var animationCanvas by remember { mutableStateOf(false) }

    val widthVal = customW.toIntOrNull() ?: deviceW
    val heightVal = customH.toIntOrNull() ?: deviceH
    val ppiVal = customPpi.toIntOrNull() ?: 300

    // Tab state:
    // In Wide Landscape: presetTab (0 = 系统预设, 1 = 我的预设)
    // In Portrait / Phone: portraitTab (0 = 常用预设, 1 = 自定义尺寸)
    var presetTab by remember { mutableIntStateOf(0) }
    var portraitTab by remember { mutableIntStateOf(0) }

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
        if (!isWideLandscape && portraitTab != 0) {
            portraitTab = 0
        } else {
            vm.goHome()
        }
    }

    // Save Preset Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("保存预设", color = colors.text, fontWeight = FontWeight.Bold) },
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
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = colors.panelHi,
                            unfocusedContainerColor = colors.panelHi
                        ),
                        shape = RoundedCornerShape(12.dp),
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
                        presetTab = 1
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

    val onSwapDimensions = {
        val tmp = customW
        customW = customH
        customH = tmp
    }

    val onOrientationChange: (Boolean) -> Unit = { toLandscape ->
        val curLandscape = widthVal >= heightVal
        if (toLandscape != curLandscape) {
            onSwapDimensions()
        }
    }

    val onStartPainting = {
        val finalW = customW.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
        val finalH = customH.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
        vm.startPainting(
            w = finalW,
            h = finalH,
            animation = animationCanvas,
            animationFps = DEFAULT_ANIMATION_FPS,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        // Minimalist Top Bar (Borderless)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi)
                    .clickable {
                        if (!isWideLandscape && portraitTab != 0) {
                            portraitTab = 0
                        } else {
                            vm.goHome()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.common_back),
                    tint = colors.text,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = stringResource(R.string.create_title),
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(colors.panelHi)
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
                        contentDescription = stringResource(R.string.create_from_image),
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_from_image),
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (isWideLandscape) {
            // Tablet Wide Landscape 2-Column Split View
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Left Column: Presets List
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                ) {
                    SegmentedTabSwitcher(
                        tabs = listOf(stringResource(R.string.create_tab_system_presets), stringResource(R.string.create_tab_my_presets)),
                        selectedIndex = presetTab.coerceIn(0, 1),
                        onTabSelected = { presetTab = it },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    if (presetTab == 0) {
                        // System Presets
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)
                        ) {
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

                // Right Column: Canvas Inspector
                Column(
                    modifier = Modifier
                        .weight(1.0f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PaperCanvasPreview(
                            widthVal = widthVal,
                            heightVal = heightVal,
                            onOrientationChange = onOrientationChange
                        )

                        CanvasDimensionsCard(
                            width = customW,
                            onWidthChange = { customW = it },
                            height = customH,
                            onHeightChange = { customH = it },
                            ppi = customPpi,
                            onPpiChange = { customPpi = it },
                            onSwap = onSwapDimensions
                        )

                        // Clean quiet layer stat line
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "预计最大图层",
                                color = colors.subText,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "最多 $maxLayers 层",
                                color = colors.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    CreateCanvasActions(
                        onSavePreset = {
                            newPresetName = "预设 ${widthVal}×${heightVal}"
                            showSavePresetDialog = true
                        },
                        onCreate = onStartPainting,
                        animationCanvas = animationCanvas,
                        onAnimationCanvasChange = { animationCanvas = it },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        } else {
            // Dedicated Portrait Layout (Phones & Tablets in Portrait)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Top Segmented Switcher: [ 常用预设 | 自定义尺寸 ]
                SegmentedTabSwitcher(
                    tabs = listOf("常用预设", "自定义尺寸"),
                    selectedIndex = portraitTab,
                    onTabSelected = { portraitTab = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                if (portraitTab == 0) {
                    // Portrait Presets View
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Sub-switcher between System and Saved presets
                        SegmentedTabSwitcher(
                            tabs = listOf("系统预设", "我的预设"),
                            selectedIndex = presetTab.coerceIn(0, 1),
                            onTabSelected = { presetTab = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(10.dp))

                        if (presetTab == 0) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
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
                            if (customPresets.isEmpty()) {
                                SavedPresetsEmptyState(modifier = Modifier.weight(1f))
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 12.dp)
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

                        // Bottom Floating Quick Creation Dock in Portrait Presets Mode (Borderless)
                        PortraitPresetBottomBar(
                            widthVal = widthVal,
                            heightVal = heightVal,
                            maxLayers = maxLayers,
                            onSwap = onSwapDimensions,
                            onCustomize = { portraitTab = 1 },
                            onCreate = onStartPainting,
                            animationCanvas = animationCanvas,
                            onAnimationCanvasChange = { animationCanvas = it },
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                } else {
                    // Portrait Custom Dimensions View
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            PaperCanvasPreview(
                                widthVal = widthVal,
                                heightVal = heightVal,
                                onOrientationChange = onOrientationChange
                            )

                            CanvasDimensionsCard(
                                width = customW,
                                onWidthChange = { customW = it },
                                height = customH,
                                onHeightChange = { customH = it },
                                ppi = customPpi,
                                onPpiChange = { customPpi = it },
                                onSwap = onSwapDimensions
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "预计最大图层",
                                    color = colors.subText,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "最多 $maxLayers 层",
                                    color = colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        CreateCanvasActions(
                            onSavePreset = {
                                newPresetName = "预设 ${widthVal}×${heightVal}"
                                showSavePresetDialog = true
                            },
                            onCreate = onStartPainting,
                            animationCanvas = animationCanvas,
                            onAnimationCanvasChange = { animationCanvas = it },
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperCanvasPreview(
    widthVal: Int,
    heightVal: Int,
    onOrientationChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    val isLandscape = widthVal >= heightVal
    val ratioLabel = remember(widthVal, heightVal) { getAspectRatioLabel(widthVal, heightVal) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ratioLabel,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            OrientationToggle(
                isLandscape = isLandscape,
                onToggle = onOrientationChange
            )
        }

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.panelHi.copy(alpha = 0.5f))
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

            val fitsInside = targetW >= 96.dp && targetH >= 34.dp
            val isNarrowTall = targetW < 96.dp && targetH >= 34.dp

            if (fitsInside) {
                // Regular proportion: thin-bordered frame with centered resolution text
                Box(
                    modifier = Modifier
                        .size(animW, animH)
                        .border(1.5.dp, colors.accent, RoundedCornerShape(4.dp))
                        .background(colors.accent.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${widthVal} × ${heightVal}",
                        color = colors.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isNarrowTall) {
                // Narrow tall proportion (e.g. strip comic): resolution text beside the frame
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(animW, animH)
                            .border(1.5.dp, colors.accent, RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.06f))
                    )
                    Text(
                        text = "${widthVal} × ${heightVal}",
                        color = colors.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                // Flat wide proportion: resolution text below the frame
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(animW, animH)
                            .border(1.5.dp, colors.accent, RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.06f))
                    )
                    Text(
                        text = "${widthVal} × ${heightVal}",
                        color = colors.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun OrientationToggle(
    isLandscape: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(colors.panelHi)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(15.dp))
                .background(if (isLandscape) colors.accent else Color.Transparent)
                .clickable { onToggle(true) }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flip_horizontal),
                    contentDescription = null,
                    tint = if (isLandscape) colors.onAccent else colors.subText,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(R.string.create_orientation_landscape),
                    color = if (isLandscape) colors.onAccent else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = if (isLandscape) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(15.dp))
                .background(if (!isLandscape) colors.accent else Color.Transparent)
                .clickable { onToggle(false) }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flip_vertical),
                    contentDescription = null,
                    tint = if (!isLandscape) colors.onAccent else colors.subText,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(R.string.create_orientation_portrait),
                    color = if (!isLandscape) colors.onAccent else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = if (!isLandscape) FontWeight.Bold else FontWeight.Medium
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
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.create_size_and_res),
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panelHi)
                    .clickable { onSwap() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flip_horizontal),
                    contentDescription = stringResource(R.string.create_swap_dimensions),
                    tint = colors.accent,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = stringResource(R.string.create_swap_dimensions),
                    color = colors.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SizeInputField(
                label = stringResource(R.string.create_width),
                unit = "PX",
                value = width,
                onValueChange = onWidthChange,
                modifier = Modifier.weight(1f)
            )
            SizeInputField(
                label = stringResource(R.string.create_height),
                unit = "PX",
                value = height,
                onValueChange = onHeightChange,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("分辨率 (DPI)", color = colors.subText, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(72, 150, 300, 350).forEach { ppiOption ->
                    val isSelected = ppi == ppiOption.toString()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) colors.accent else colors.panelHi)
                            .clickable { onPpiChange(ppiOption.toString()) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
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
    }
}

@Composable
private fun PortraitPresetBottomBar(
    widthVal: Int,
    heightVal: Int,
    maxLayers: Int,
    onSwap: () -> Unit,
    onCustomize: () -> Unit,
    onCreate: () -> Unit,
    animationCanvas: Boolean = false,
    onAnimationCanvasChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${widthVal} × ${heightVal} px",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            val orientationText = if (widthVal >= heightVal) stringResource(R.string.create_orientation_landscape) else stringResource(R.string.create_orientation_portrait)
            Text(
                text = "$orientationText · " + stringResource(R.string.create_max_layers_desc, maxLayers),
                color = colors.subText,
                fontSize = 11.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (animationCanvas) colors.accent else colors.panelHi)
                    .clickable { onAnimationCanvasChange(!animationCanvas) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_clock),
                        contentDescription = stringResource(R.string.create_anim_canvas),
                        tint = if (animationCanvas) colors.onAccent else colors.text,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (animationCanvas) stringResource(R.string.create_anim_tag) else stringResource(R.string.create_static_canvas),
                        color = if (animationCanvas) colors.onAccent else colors.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Beta 标记: 动画画布功能尚在测试阶段
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "Beta",
                        color = if (animationCanvas) colors.onAccent else colors.accent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi)
                    .clickable { onSwap() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_flip_horizontal),
                    contentDescription = stringResource(R.string.create_swap_dimensions),
                    tint = colors.text,
                    modifier = Modifier.size(15.dp)
                )
            }

            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.panelHi)
                    .clickable { onCustomize() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.create_adjust),
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            val createSource = remember { MutableInteractionSource() }
            val isCreatePressed by createSource.collectIsPressedAsState()
            val btnScale by animateFloatAsState(
                targetValue = if (isCreatePressed) 0.96f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "PortraitCreateScale"
            )

            Box(
                modifier = Modifier
                    .scale(btnScale)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(colors.accent)
                    .clickable(interactionSource = createSource, indication = null) { onCreate() }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "创建画布",
                    color = colors.onAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CreateCanvasActions(
    onSavePreset: () -> Unit,
    onCreate: () -> Unit,
    animationCanvas: Boolean = false,
    onAnimationCanvasChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Column(modifier = modifier.fillMaxWidth()) {
        // 动画画布开关: 勾选后新建的文档自带时间轴面板
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.panelHi)
                .clickable { onAnimationCanvasChange(!animationCanvas) }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "动画画布",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                // Beta 标记: 动画画布功能尚在测试阶段
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.accent.copy(alpha = 0.16f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "Beta",
                        color = colors.accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = if (animationCanvas) "含时间轴" else "静态单帧",
                color = colors.subText,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            AnimationCanvasToggle(checked = animationCanvas)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier
                .weight(0.38f)
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelHi)
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
                    modifier = Modifier.size(16.dp)
                )
                Text("保存预设", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        val createSource = remember { MutableInteractionSource() }
        val isCreatePressed by createSource.collectIsPressedAsState()
        val btnScale by animateFloatAsState(
            targetValue = if (isCreatePressed) 0.96f else 1.0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "CreateActionScale"
        )

        Box(
            modifier = Modifier
                .weight(0.62f)
                .scale(btnScale)
                .height(50.dp)
                .clip(RoundedCornerShape(16.dp))
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
}

@Composable
private fun AnimationCanvasToggle(checked: Boolean) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) colors.accent else colors.subText.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 3.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.onAccent)
        )
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
        targetValue = if (isItemPressed) 0.98f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "PresetItemScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(itemScale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) colors.accent.copy(alpha = 0.12f) else colors.panel)
            .clickable(interactionSource = itemSource, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Subtle left accent bar indicator on select
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(colors.accent)
            )
            Spacer(Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.name,
                    color = if (isSelected) colors.accent else colors.text,
                    fontSize = 15.sp,
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
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.description,
                    color = colors.subText,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${item.width} × ${item.height}",
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${item.defaultPpi} DPI · ${maxLayers}层",
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
                    .background(colors.panelHi)
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
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.panelHi),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "暂无保存的预设",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "在自定义尺寸中配置好画幅后，点击保存预设即可在此随时调用",
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
            .background(colors.panelHi)
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
        Text(label, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = { v ->
                if (v.length <= 5) onValueChange(v.filter { it.isDigit() })
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            trailingIcon = {
                Text(unit, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp))
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.panelHi,
                unfocusedContainerColor = colors.panelHi,
                disabledContainerColor = colors.panelHi,
                cursorColor = colors.accent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        )
    }
}
