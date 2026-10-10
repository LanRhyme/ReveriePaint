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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
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
                val name = ImageImportHelper.getFileName(context, uri) ?: context.getString(R.string.canvas_action_import_image)
                val snapFile = ImageImportHelper.writeTempPng(context, bmp)
                vm.startPainting(
                    w = bmp.width,
                    h = bmp.height,
                    name = name,
                    initialBitmap = bmp,
                    initialSnapshotFile = snapFile,
                )
            } else {
                android.widget.Toast.makeText(context, context.getString(R.string.create_toast_image_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Dynamic Device Screen Resolution
    val (deviceW, deviceH) = remember { getDeviceScreenResolution(context) }

    // Canvas States - default to device screen resolution
    var selectedUnit by remember { mutableStateOf(CanvasUnit.PX) }
    var customW by remember { mutableStateOf(deviceW.toString()) }
    var customH by remember { mutableStateOf(deviceH.toString()) }
    var customPpi by remember { mutableStateOf("300") }
    // 动画画布: 勾选后新建的文档自带时间轴 (后端走 startPainting(animation = true))
    var animationCanvas by remember { mutableStateOf(false) }

    val ppiVal = customPpi.toIntOrNull()?.coerceIn(72, 1200) ?: 300

    val widthVal = remember(customW, selectedUnit, ppiVal) {
        if (selectedUnit == CanvasUnit.PX) {
            customW.toIntOrNull()?.coerceIn(64, 8192) ?: deviceW
        } else {
            val num = customW.toDoubleOrNull() ?: 0.0
            unitToPx(num, selectedUnit, ppiVal).coerceIn(64, 8192)
        }
    }
    val heightVal = remember(customH, selectedUnit, ppiVal) {
        if (selectedUnit == CanvasUnit.PX) {
            customH.toIntOrNull()?.coerceIn(64, 8192) ?: deviceH
        } else {
            val num = customH.toDoubleOrNull() ?: 0.0
            unitToPx(num, selectedUnit, ppiVal).coerceIn(64, 8192)
        }
    }

    val convertedHint = remember(selectedUnit, widthVal, heightVal, ppiVal) {
        if (selectedUnit == CanvasUnit.PX) {
            val mmW = pxToUnit(widthVal.toDouble(), CanvasUnit.MM, ppiVal)
            val mmH = pxToUnit(heightVal.toDouble(), CanvasUnit.MM, ppiVal)
            context.getString(R.string.create_print_size_format, "${formatUnitValue(mmW, CanvasUnit.MM)} × ${formatUnitValue(mmH, CanvasUnit.MM)} mm")
        } else {
            context.getString(R.string.create_pixel_size_format, widthVal, heightVal)
        }
    }

    val applyPreset: (CanvasPresetItem) -> Unit = { item ->
        customPpi = item.defaultPpi.toString()
        if (selectedUnit == CanvasUnit.PX) {
            customW = item.width.toString()
            customH = item.height.toString()
        } else {
            val wInUnit = pxToUnit(item.width.toDouble(), selectedUnit, item.defaultPpi)
            val hInUnit = pxToUnit(item.height.toDouble(), selectedUnit, item.defaultPpi)
            customW = formatUnitValue(wInUnit, selectedUnit)
            customH = formatUnitValue(hInUnit, selectedUnit)
        }
    }

    val onUnitChange: (CanvasUnit) -> Unit = { newUnit ->
        if (newUnit != selectedUnit) {
            val curWInPx = widthVal.toDouble()
            val curHInPx = heightVal.toDouble()
            val newW = pxToUnit(curWInPx, newUnit, ppiVal)
            val newH = pxToUnit(curHInPx, newUnit, ppiVal)
            selectedUnit = newUnit
            customW = formatUnitValue(newW, newUnit)
            customH = formatUnitValue(newH, newUnit)
        }
    }

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
            title = { Text(stringResource(R.string.create_preset_save_title), color = colors.text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.create_preset_name_hint), color = colors.subText, fontSize = 13.sp)
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
                    stringResource(R.string.common_save),
                    onClick = {
                        val pName = newPresetName.ifBlank { context.getString(R.string.create_preset_default_title_format, widthVal, heightVal) }
                        val newItem = CanvasPresetItem(
                            name = pName,
                            width = widthVal,
                            height = heightVal,
                            defaultPpi = ppiVal,
                            description = context.getString(R.string.create_custom_preset_desc_format, pName),
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
                ReTextButton(stringResource(R.string.common_cancel), { showSavePresetDialog = false }, textColor = colors.subText)
            },
            containerColor = colors.panel
        )
    }

    // Delete Preset Dialog
    if (presetToDelete != null) {
        val target = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text(stringResource(R.string.create_preset_delete_title), color = colors.text, fontWeight = FontWeight.Bold) },
            text = { Text(context.getString(R.string.create_preset_delete_confirm, target.name), color = colors.subText, fontSize = 14.sp) },
            confirmButton = {
                ReTextButton(
                    stringResource(R.string.common_delete),
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
                ReTextButton(stringResource(R.string.common_cancel), onClick = { presetToDelete = null }, textColor = colors.subText)
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
        val finalW = widthVal.coerceIn(64, 8192)
        val finalH = heightVal.coerceIn(64, 8192)
        vm.startPainting(
            w = finalW,
            h = finalH,
            dpi = ppiVal,
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
                                    onClick = { applyPreset(item) }
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
                                        onClick = { applyPreset(item) },
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
                            unit = selectedUnit,
                            onUnitChange = onUnitChange,
                            width = customW,
                            onWidthChange = { customW = it },
                            height = customH,
                            onHeightChange = { customH = it },
                            ppi = customPpi,
                            onPpiChange = { customPpi = it },
                            convertedHint = convertedHint,
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
                                text = stringResource(R.string.create_expected_max_layers),
                                color = colors.subText,
                                fontSize = 12.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.create_max_layers_desc, maxLayers),
                                    color = if (maxLayers < 10) Color(0xFFD97757) else colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (maxLayers < 10) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.create_layer_warning_low),
                                        color = Color(0xFFD97757),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    CreateCanvasActions(
                        onSavePreset = {
                            newPresetName = context.getString(R.string.create_preset_default_title_format, widthVal, heightVal)
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
                    tabs = listOf(stringResource(R.string.create_tab_common_presets), stringResource(R.string.create_tab_custom_size)),
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
                            tabs = listOf(stringResource(R.string.create_tab_system_presets), stringResource(R.string.create_tab_my_presets)),
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
                                        onClick = { applyPreset(item) }
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
                                            onClick = { applyPreset(item) },
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
                            ppiVal = ppiVal,
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
                                unit = selectedUnit,
                                onUnitChange = onUnitChange,
                                width = customW,
                                onWidthChange = { customW = it },
                                height = customH,
                                onHeightChange = { customH = it },
                                ppi = customPpi,
                                onPpiChange = { customPpi = it },
                                convertedHint = convertedHint,
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
                                    text = stringResource(R.string.create_expected_max_layers),
                                    color = colors.subText,
                                    fontSize = 12.sp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.create_max_layers_desc, maxLayers),
                                        color = if (maxLayers < 10) Color(0xFFD97757) else colors.accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (maxLayers < 10) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.create_layer_warning_low),
                                            color = Color(0xFFD97757),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        CreateCanvasActions(
                            onSavePreset = {
                                newPresetName = context.getString(R.string.create_preset_default_title_format, widthVal, heightVal)
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
    val context = LocalContext.current
    val isLandscape = widthVal >= heightVal
    val ratioLabel = remember(widthVal, heightVal) { getAspectRatioLabel(widthVal, heightVal, context) }

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
                animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing),
                label = "previewAnimW"
            )
            val animH by animateDpAsState(
                targetValue = targetH,
                animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing),
                label = "previewAnimH"
            )

            val fitsInside = animW >= 96.dp && animH >= 34.dp
            val isNarrowTall = animW < 96.dp && animH >= 34.dp

            // 恒定居中画布预览框：中心点固定在容器中央，绝不使用外层 Row/Column 破坏其绝对居中位置
            Box(
                modifier = Modifier
                    .size(animW, animH)
                    .border(1.5.dp, colors.accent, RoundedCornerShape(4.dp))
                    .background(colors.accent.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (fitsInside) {
                    Text(
                        text = "${widthVal} × ${heightVal}",
                        color = colors.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            // 当画布框过窄无法在框内完整展示文字时，将分辨率尺寸标注依附在框体右侧；框体自身始终保持绝对居中
            if (isNarrowTall) {
                Text(
                    text = "${widthVal} × ${heightVal}",
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = (animW / 2) + 44.dp)
                )
            } else if (!fitsInside) {
                // 当画布框过扁时，将分辨率尺寸标注居中对齐在框体下方
                Text(
                    text = "${widthVal} × ${heightVal}",
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (animH / 2) + 16.dp)
                )
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
    unit: CanvasUnit,
    onUnitChange: (CanvasUnit) -> Unit,
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    ppi: String,
    onPpiChange: (String) -> Unit,
    convertedHint: String,
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Unit switcher segmented pills
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panelHi)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CanvasUnit.values().forEach { u ->
                        val isSelected = unit == u
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) colors.accent else Color.Transparent)
                                .clickable { onUnitChange(u) }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = u.symbol,
                                color = if (isSelected) colors.onAccent else colors.subText,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Swap dimensions button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panelHi)
                        .clickable { onSwap() }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
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
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SizeInputField(
                label = stringResource(R.string.create_width),
                unit = unit.symbol,
                value = width,
                onValueChange = onWidthChange,
                allowDecimal = (unit != CanvasUnit.PX),
                modifier = Modifier.weight(1f)
            )
            SizeInputField(
                label = stringResource(R.string.create_height),
                unit = unit.symbol,
                value = height,
                onValueChange = onHeightChange,
                allowDecimal = (unit != CanvasUnit.PX),
                modifier = Modifier.weight(1f)
            )
        }

        if (convertedHint.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = convertedHint,
                color = colors.subText,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // DPI Row: Label & Direct Number Input Field
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.create_dpi_label), color = colors.subText, fontSize = 12.sp)

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panelHi)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BasicTextField(
                    value = ppi,
                    onValueChange = { v ->
                        val filtered = v.filter { it.isDigit() }.take(4)
                        onPpiChange(filtered)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.width(46.dp)
                )
                Text(
                    text = "DPI",
                    color = colors.subText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // DPI Quick Preset Chips: 72, 150, 300, 350, 600
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(72, 150, 300, 350, 600).forEach { ppiOption ->
                val isSelected = ppi == ppiOption.toString()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) colors.accent else colors.panelHi)
                        .clickable { onPpiChange(ppiOption.toString()) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$ppiOption",
                        color = if (isSelected) colors.onAccent else colors.text,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitPresetBottomBar(
    widthVal: Int,
    heightVal: Int,
    ppiVal: Int,
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
                text = "${widthVal} × ${heightVal} px · ${ppiVal} DPI",
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
                    text = stringResource(R.string.create_title),
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
                    text = stringResource(R.string.create_anim_canvas_title),
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
                text = if (animationCanvas) stringResource(R.string.create_anim_timeline_active) else stringResource(R.string.create_anim_single_frame),
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
                Text(stringResource(R.string.create_save_preset_btn), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                text = stringResource(R.string.create_title),
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
                        Text(stringResource(R.string.create_custom_badge), color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
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
                text = stringResource(R.string.create_preset_stat_format, item.defaultPpi, maxLayers),
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
                    contentDescription = stringResource(R.string.create_preset_delete_title),
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
                    painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = stringResource(R.string.create_no_saved_presets_title),
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.create_no_saved_presets_desc),
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
    allowDecimal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = Theme.current
    Column(modifier = modifier) {
        Text(label, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(4.dp))
        TextField(
            value = value,
            onValueChange = { v ->
                val filtered = if (allowDecimal) {
                    var hasDot = false
                    val sb = StringBuilder()
                    for (ch in v) {
                        if (ch.isDigit()) {
                            sb.append(ch)
                        } else if (ch == '.' && !hasDot) {
                            hasDot = true
                            sb.append(ch)
                        }
                    }
                    sb.toString().take(7)
                } else {
                    v.filter { it.isDigit() }.take(5)
                }
                onValueChange(filtered)
            },
            keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number),
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
