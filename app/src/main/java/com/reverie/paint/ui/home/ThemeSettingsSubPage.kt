/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.reverie.paint.ui.components.ReButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Layers
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.painting.panels.CompactColorPickerPopup
import com.reverie.paint.ui.painting.panels.labelRes
import com.reverie.paint.ui.theme.PresetAccentSwatches
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.ThemeStyle
import com.reverie.paint.ui.theme.colorToHsl
import com.reverie.paint.ui.theme.hslToColor
import com.reverie.paint.ui.theme.optimalTextColor
import com.reverie.paint.ui.theme.parseColor

@Composable
internal fun ThemeSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val ok = vm.importHomeBackground(uri, context)
            if (ok) {
                Toast.makeText(context, R.string.theme_home_bg_import_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.theme_home_bg_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var customColorAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var showCustomCanvasBgDialog by remember { mutableStateOf(false) }
    var customCanvasBgAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var showFloatingLayoutDialog by remember { mutableStateOf(false) }
    var showSmallScreenWarningDialog by remember { mutableStateOf(false) }
    val presetSwatches = PresetAccentSwatches

    val canvasPresetSwatches = listOf(
        "DEFAULT",
        "#121316",
        "#1E2024",
        "#2F3136",
        "#35383F",
        "#4E5159",
        "#7A7E85",
        "#B0B5BD",
        "#D8DCE2",
        "#F0F2F5",
        "#000000",
        "#FFFFFF",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (vm.homeCustomBgEnabled && vm.getHomeCustomBgFile() != null) Color.Transparent else colors.bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 12.dp else 20.dp),
        ) {
            SettingSubPageHeader(
                title = stringResource(R.string.theme_settings_title),
                subtitle = stringResource(R.string.theme_settings_subtitle),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // Section 1: 色彩与主题
            SettingCategoryTitle(stringResource(R.string.theme_category_color_appearance))
            SettingGroup {
                SettingSegmentGroupItem(
                    icon = Icons.Rounded.Brightness4,
                    title = stringResource(R.string.theme_color_mode_title),
                    summary = stringResource(R.string.theme_color_mode_summary),
                    options = listOf(
                        "DARK" to stringResource(R.string.theme_mode_dark),
                        "LIGHT" to stringResource(R.string.theme_mode_light),
                        "SYSTEM" to stringResource(R.string.theme_mode_system),
                    ),
                    selected = vm.themeMode,
                    shape = settingGroupShape(0, 4),
                    onSelect = { vm.updateThemeMode(it) },
                )

                SettingSegmentGroupItem(
                    icon = R.drawable.ic_contrast,
                    title = stringResource(R.string.theme_contrast_title),
                    summary = stringResource(R.string.theme_contrast_summary),
                    options = listOf(
                        "SOFT" to stringResource(R.string.theme_contrast_soft),
                        "STANDARD" to stringResource(R.string.theme_contrast_standard),
                        "HIGH" to stringResource(R.string.theme_contrast_high),
                    ),
                    selected = vm.themeContrast,
                    shape = settingGroupShape(1, 4),
                    onSelect = { vm.updateThemeContrast(it) },
                )

                SettingSegmentGroupItem(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.theme_style_title),
                    summary = when (vm.themeStyle) {
                        ThemeStyle.MORANDI_TINTED -> stringResource(R.string.theme_style_morandi_tinted_desc)
                        ThemeStyle.NEUTRAL_STUDIO -> stringResource(R.string.theme_style_neutral_studio_desc)
                        ThemeStyle.WARM_EARTHY -> stringResource(R.string.theme_style_warm_earthy_desc)
                        ThemeStyle.VIBRANT_GLOW -> stringResource(R.string.theme_style_vibrant_glow_desc)
                        else -> stringResource(R.string.theme_style_summary)
                    },
                    options = listOf(
                        ThemeStyle.MORANDI_TINTED to stringResource(R.string.theme_style_morandi_tinted),
                        ThemeStyle.NEUTRAL_STUDIO to stringResource(R.string.theme_style_neutral_studio),
                        ThemeStyle.WARM_EARTHY to stringResource(R.string.theme_style_warm_earthy),
                        ThemeStyle.VIBRANT_GLOW to stringResource(R.string.theme_style_vibrant_glow),
                    ),
                    selected = vm.themeStyle,
                    shape = settingGroupShape(2, 4),
                    onSelect = { vm.updateThemeStyle(it) },
                )

                SettingSwitchGroupItem(
                    icon = Icons.Rounded.ColorLens,
                    title = stringResource(R.string.theme_monet_title),
                    summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        stringResource(R.string.theme_monet_summary)
                    } else {
                        stringResource(R.string.theme_monet_unsupported)
                    },
                    checked = vm.monetEnabled,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    shape = settingGroupShape(3, 4),
                    onCheckedChange = { vm.updateMonetEnabled(it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // 主色调强调色
            SettingGroup {
                SettingCardBox(shape = settingGroupShape(0, 1)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SettingIcon(icon = Icons.Rounded.Palette, tint = colors.icon)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stringResource(R.string.theme_accent_palette_monet) else stringResource(R.string.theme_accent_palette),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (vm.monetEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        stringResource(R.string.theme_monet_override_hint)
                                    } else {
                                        stringResource(R.string.theme_accent_desc)
                                    },
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(presetSwatches) { hex ->
                                val swatchColor = parseColor(hex)
                                val isSelected = !vm.monetEnabled && vm.accentColorHex.equals(hex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .then(
                                            if (isSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                        )
                                        .clickable {
                                            if (vm.monetEnabled) {
                                                vm.updateMonetEnabled(false)
                                            }
                                            vm.updateAccentColor(hex)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = swatchColor.optimalTextColor(),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }

                            // Custom color button
                            item {
                                val isCustomSelected = !vm.monetEnabled && presetSwatches.none { it.equals(vm.accentColorHex, ignoreCase = true) }
                                val currentCustomColor = if (isCustomSelected) parseColor(vm.accentColorHex) else colors.panelHi
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .onGloballyPositioned { coordinates ->
                                            customColorAnchorBounds = coordinates.boundsInRoot()
                                        }
                                        .clip(CircleShape)
                                        .background(currentCustomColor)
                                        .then(
                                            if (isCustomSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                        )
                                        .clickable {
                                            if (vm.monetEnabled) {
                                                vm.updateMonetEnabled(false)
                                            }
                                            showCustomColorDialog = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(if (isCustomSelected) R.drawable.ic_check else R.drawable.ic_plus),
                                        contentDescription = stringResource(R.string.color_custom),
                                        tint = if (isCustomSelected) currentCustomColor.optimalTextColor() else colors.icon,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 画布工作区背景底色与静态网格
            val canvasBgItems = if (vm.canvasGridEnabled) 4 else 2
            SettingGroup {
                SettingCardBox(shape = settingGroupShape(0, canvasBgItems)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SettingIcon(icon = Icons.Rounded.Wallpaper, tint = colors.icon)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.theme_canvas_bg_title),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.theme_canvas_bg_desc),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(canvasPresetSwatches) { hex ->
                                if (hex == "DEFAULT") {
                                    val isDefaultSelected = vm.canvasBgColorHex == "DEFAULT" || vm.canvasBgColorHex.isBlank()
                                    Box(
                                        modifier = Modifier
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(19.dp))
                                            .background(colors.panelHi)
                                            .then(
                                                if (isDefaultSelected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(19.dp)) else Modifier
                                            )
                                            .clickable { vm.updateCanvasBgColor("DEFAULT") }
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isDefaultSelected) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null,
                                                    tint = colors.accent,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = stringResource(R.string.theme_canvas_bg_follow),
                                                color = if (isDefaultSelected) colors.accent else colors.text,
                                                fontSize = 12.sp,
                                                fontWeight = if (isDefaultSelected) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                } else {
                                    val swatchColor = parseColor(hex)
                                    val isSelected = vm.canvasBgColorHex.equals(hex, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .then(
                                                if (isSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                            )
                                            .clickable { vm.updateCanvasBgColor(hex) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = swatchColor.optimalTextColor(),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            // Custom canvas background button
                            item {
                                val isCustomSelected = vm.canvasBgColorHex != "DEFAULT" && vm.canvasBgColorHex.isNotBlank() && canvasPresetSwatches.none { it.equals(vm.canvasBgColorHex, ignoreCase = true) }
                                val currentCustomColor = if (isCustomSelected) parseColor(vm.canvasBgColorHex) else colors.panelHi
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .onGloballyPositioned { coordinates ->
                                            customCanvasBgAnchorBounds = coordinates.boundsInRoot()
                                        }
                                        .clip(CircleShape)
                                        .background(currentCustomColor)
                                        .then(
                                            if (isCustomSelected) Modifier.border(2.5.dp, colors.accent, CircleShape) else Modifier
                                        )
                                        .clickable { showCustomCanvasBgDialog = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val iconTint = if (isCustomSelected) currentCustomColor.optimalTextColor() else colors.icon
                                    Icon(
                                        painter = painterResource(if (isCustomSelected) R.drawable.ic_check else R.drawable.ic_plus),
                                        contentDescription = stringResource(R.string.theme_custom_canvas_bg),
                                        tint = iconTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 背景网格总开关
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.GridOn,
                    title = stringResource(R.string.theme_canvas_grid_title),
                    summary = stringResource(R.string.theme_canvas_grid_desc),
                    checked = vm.canvasGridEnabled,
                    onCheckedChange = { vm.updateCanvasGridEnabled(it) },
                    shape = settingGroupShape(1, canvasBgItems),
                )

                if (vm.canvasGridEnabled) {
                    // 网格不透明度滑块
                    SettingSliderGroupItem(
                        icon = Icons.Rounded.Opacity,
                        title = stringResource(R.string.theme_canvas_grid_opacity),
                        summary = stringResource(R.string.theme_canvas_grid_opacity_desc),
                        valueText = "${(vm.canvasGridOpacity * 100).roundToInt()}%",
                        sliderFraction = (vm.canvasGridOpacity - 0.05f) / 0.55f,
                        shape = settingGroupShape(2, canvasBgItems),
                        onValueChange = { fraction ->
                            vm.updateCanvasGridOpacity(0.05f + fraction * 0.55f)
                        },
                    )

                    // 网格尺寸滑块
                    SettingSliderGroupItem(
                        icon = Icons.Rounded.AspectRatio,
                        title = stringResource(R.string.theme_canvas_grid_size),
                        summary = stringResource(R.string.theme_canvas_grid_size_desc),
                        valueText = "${vm.canvasGridSizeDp} dp",
                        sliderFraction = (vm.canvasGridSizeDp - 12f) / (48f - 12f),
                        shape = settingGroupShape(3, canvasBgItems),
                        onValueChange = { fraction ->
                            vm.updateCanvasGridSizeDp((12f + fraction * 36f).roundToInt())
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 主页背景图片
            SettingCategoryTitle(stringResource(R.string.theme_home_bg_category))
            SettingGroup {
                val hasCustomBg = vm.homeCustomBgEnabled && vm.getHomeCustomBgFile() != null
                val totalItems = if (hasCustomBg) 5 else 1

                // 1. 壁纸预览卡片与导入/清除操作
                SettingCardBox(shape = settingGroupShape(0, totalItems)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SettingIcon(icon = Icons.Rounded.Wallpaper, tint = colors.icon)
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.theme_home_bg_title),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.theme_home_bg_desc),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 预览容器
                        val bgFile = remember(vm.homeCustomBgVersion) { vm.getHomeCustomBgFile() }
                        val bgBitmap = remember(vm.homeCustomBgVersion, bgFile?.lastModified()) {
                            bgFile?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panelHi)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bgBitmap != null && vm.homeCustomBgEnabled) {
                                val scale = if (vm.homeCustomBgScaleMode == "FIT") ContentScale.Fit else ContentScale.Crop
                                val blurModifier = if (vm.homeCustomBgBlurDp > 0f) Modifier.blur(vm.homeCustomBgBlurDp.dp) else Modifier
                                Image(
                                    bitmap = bgBitmap,
                                    contentDescription = null,
                                    contentScale = scale,
                                    alpha = vm.homeCustomBgOpacity,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(blurModifier),
                                )
                                if (vm.homeCustomBgMaskAlpha > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = vm.homeCustomBgMaskAlpha)),
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.theme_home_bg_empty),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 操作按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ReButton(
                                text = if (hasCustomBg) stringResource(R.string.theme_home_bg_change) else stringResource(R.string.theme_home_bg_import),
                                onClick = { imagePickerLauncher.launch("image/*") },
                                primary = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (hasCustomBg) {
                                ReButton(
                                    text = stringResource(R.string.theme_home_bg_clear),
                                    onClick = {
                                        vm.clearHomeBackground()
                                        Toast.makeText(context, R.string.theme_home_bg_cleared, Toast.LENGTH_SHORT).show()
                                    },
                                    primary = false,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                if (hasCustomBg) {
                    // 2. 不透明度滑块
                    SettingSliderGroupItem(
                        icon = Icons.Rounded.Opacity,
                        title = stringResource(R.string.theme_home_bg_opacity),
                        summary = stringResource(R.string.theme_home_bg_opacity_desc),
                        valueText = "${(vm.homeCustomBgOpacity * 100).roundToInt()}%",
                        sliderFraction = (vm.homeCustomBgOpacity - 0.1f) / 0.9f,
                        shape = settingGroupShape(1, totalItems),
                        onValueChange = { fraction ->
                            vm.updateHomeBgOpacity(0.1f + fraction * 0.9f)
                        },
                    )

                    // 3. 高斯模糊滑块
                    SettingSliderGroupItem(
                        icon = Icons.Rounded.BlurOn,
                        title = stringResource(R.string.theme_home_bg_blur),
                        summary = stringResource(R.string.theme_home_bg_blur_desc),
                        valueText = "${vm.homeCustomBgBlurDp.roundToInt()} dp",
                        sliderFraction = vm.homeCustomBgBlurDp / 30f,
                        shape = settingGroupShape(2, totalItems),
                        onValueChange = { fraction ->
                            vm.updateHomeBgBlurDp(fraction * 30f)
                        },
                    )

                    // 4. 填充裁剪模式
                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.AspectRatio,
                        title = stringResource(R.string.theme_home_bg_scale_mode),
                        options = listOf(
                            "CROP" to stringResource(R.string.theme_home_bg_scale_crop),
                            "FIT" to stringResource(R.string.theme_home_bg_scale_fit),
                        ),
                        selected = vm.homeCustomBgScaleMode,
                        shape = settingGroupShape(3, totalItems),
                        onSelect = { vm.updateHomeBgScaleMode(it) },
                    )

                    // 5. 蒙版遮罩
                    SettingSliderGroupItem(
                        icon = Icons.Rounded.Brightness4,
                        title = stringResource(R.string.theme_home_bg_mask),
                        summary = stringResource(R.string.theme_home_bg_mask_desc),
                        valueText = "${(vm.homeCustomBgMaskAlpha * 100).roundToInt()}%",
                        sliderFraction = vm.homeCustomBgMaskAlpha / 0.8f,
                        shape = settingGroupShape(4, totalItems),
                        onValueChange = { fraction ->
                            vm.updateHomeBgMaskAlpha(fraction * 0.8f)
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Section 2: 视觉与效果
            SettingCategoryTitle(stringResource(R.string.theme_category_effects))
            SettingGroup {
                val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.BlurOn,
                    title = stringResource(R.string.theme_blur_title),
                    summary = if (blurSupported) stringResource(R.string.theme_blur_desc) else stringResource(R.string.theme_blur_unsupported),
                    checked = vm.blurBackground,
                    enabled = blurSupported,
                    shape = settingGroupShape(0, 3),
                    onCheckedChange = { vm.updateBlurBackground(it) },
                )

                SettingSegmentGroupItem(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.settings_anim_speed_title),
                    summary = stringResource(R.string.settings_anim_speed_desc),
                    options = UiAnimationSpeed.entries.map {
                        it to stringResource(it.labelRes())
                    },
                    selected = vm.uiAnimationSpeed,
                    shape = settingGroupShape(1, 3),
                    onSelect = { vm.updateUiAnimationSpeed(it) },
                )

                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Fullscreen,
                    title = stringResource(R.string.theme_immersive_title),
                    summary = stringResource(R.string.theme_immersive_desc),
                    checked = vm.immersiveMode,
                    shape = settingGroupShape(2, 3),
                    onCheckedChange = {
                        vm.updateExtendToCutout(true)
                        vm.updateImmersiveMode(it)
                    },
                )
            }

            // Section 3: 面板与尺寸
            SettingCategoryTitle(stringResource(R.string.theme_category_panels_scale))
            SettingGroup {
                SettingSliderGroupItem(
                    icon = Icons.Rounded.Opacity,
                    title = stringResource(R.string.theme_opacity_main_panel),
                    summary = stringResource(R.string.theme_opacity_main_panel_desc),
                    valueText = "${(vm.uiOpacity * 100).toInt()}%",
                    sliderFraction = ((vm.uiOpacity - 0.2f) / 0.8f).coerceIn(0f, 1f),
                    shape = settingGroupShape(0, 4),
                    onValueChange = { vm.updateUiOpacity((0.2f + it * 0.8f).coerceIn(0.2f, 1f)) },
                )

                SettingSliderGroupItem(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.theme_opacity_floating_panel),
                    summary = stringResource(R.string.theme_opacity_floating_panel_desc),
                    valueText = "${(vm.popupPanelOpacity * 100).toInt()}%",
                    sliderFraction = ((vm.popupPanelOpacity - 0.2f) / 0.8f).coerceIn(0f, 1f),
                    shape = settingGroupShape(1, 4),
                    onValueChange = { vm.updatePopupPanelOpacity((0.2f + it * 0.8f).coerceIn(0.2f, 1f)) },
                )

                SettingSliderGroupItem(
                    icon = Icons.Rounded.AspectRatio,
                    title = stringResource(R.string.theme_ui_scale_title),
                    summary = stringResource(R.string.theme_ui_scale_desc),
                    valueText = "${(vm.paintingUiScale * 100).toInt()}%",
                    sliderFraction = ((vm.paintingUiScale - 0.75f) / (1.35f - 0.75f)).coerceIn(0f, 1f),
                    shape = settingGroupShape(2, 4),
                    onValueChange = { fraction ->
                        val newScale = 0.75f + fraction * (1.35f - 0.75f)
                        vm.updatePaintingUiScale(newScale)
                    },
                )

                SettingSliderGroupItem(
                    icon = Icons.Rounded.Height,
                    title = stringResource(R.string.theme_quick_slider_height_title),
                    summary = stringResource(R.string.theme_quick_slider_height_desc),
                    valueText = "${vm.quickSliderHeightDp} dp",
                    sliderFraction = ((vm.quickSliderHeightDp - 100f) / (260f - 100f)).coerceIn(0f, 1f),
                    shape = settingGroupShape(3, 4),
                    onValueChange = { fraction ->
                        val newHeight = (100f + fraction * 160f).roundToInt()
                        vm.updateQuickSliderHeight(newHeight)
                    },
                )
            }

            // Section 4: 工作区与布局
            val configuration = LocalConfiguration.current
            val isSmallScreen = configuration.smallestScreenWidthDp < 600 || configuration.screenWidthDp < 600
            SettingCategoryTitle(stringResource(R.string.theme_category_workspace_layout))
            SettingGroup {
                val layoutCount = if (vm.floatingUiConfig.enabled) 5 else 2
                // 1. 默认贴边布局单选
                SettingRadioGroupItem(
                    title = stringResource(R.string.theme_layout_docked),
                    summary = stringResource(R.string.theme_layout_docked_desc),
                    selected = !vm.floatingUiConfig.enabled,
                    shape = settingGroupShape(0, layoutCount),
                    indentStart = 16.dp,
                    onClick = { vm.updateFloatingUiEnabled(false) },
                )

                // 2. 自由悬浮模式
                SettingRadioGroupItem(
                    title = stringResource(R.string.theme_layout_floating),
                    summary = stringResource(R.string.theme_layout_floating_desc),
                    selected = vm.floatingUiConfig.enabled,
                    shape = settingGroupShape(1, layoutCount),
                    indentStart = 16.dp,
                    onClick = {
                        if (!vm.floatingUiConfig.enabled) {
                            if (isSmallScreen) {
                                showSmallScreenWarningDialog = true
                            } else {
                                vm.updateFloatingUiEnabled(true)
                            }
                        }
                    },
                )

                if (vm.floatingUiConfig.enabled) {
                    // 专属可视化排布调整入口
                    SettingNavGroupItem(
                        icon = Icons.AutoMirrored.Rounded.ViewSidebar,
                        title = stringResource(R.string.panel_dock_dialog_title),
                        summary = stringResource(R.string.panel_dock_dialog_tip),
                        shape = settingGroupShape(2, layoutCount),
                        onClick = { showFloatingLayoutDialog = true },
                    )

                    // 独立大小调整
                    SettingSliderGroupItem(
                        icon = Icons.Rounded.AspectRatio,
                        title = stringResource(R.string.panel_dock_scale_title),
                        summary = stringResource(R.string.panel_dock_scale_desc),
                        valueText = "${(vm.floatingUiConfig.scale * 100).roundToInt()}%",
                        sliderFraction = ((vm.floatingUiConfig.scale - 0.70f) / (1.40f - 0.70f)).coerceIn(0f, 1f),
                        shape = settingGroupShape(3, layoutCount),
                        onValueChange = { fraction ->
                            val newScale = 0.70f + fraction * 0.70f
                            vm.updateFloatingUiScale(newScale)
                        },
                    )

                    // 一键恢复默认停靠
                    SettingNavGroupItem(
                        icon = R.drawable.ic_undo,
                        title = stringResource(R.string.panel_dock_reset),
                        shape = settingGroupShape(4, layoutCount),
                        onClick = { vm.resetFloatingUiEdges() },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 图层、手势偏好与选区遮罩
            SettingGroup {
                SettingSegmentGroupItem(
                    icon = R.drawable.ic_layers,
                    title = stringResource(R.string.settings_layer_row_height),
                    summary = "${vm.layerRowHeightDp} dp",
                    options = listOf(
                        44 to stringResource(R.string.settings_layer_height_compact),
                        52 to stringResource(R.string.settings_layer_height_standard),
                        64 to stringResource(R.string.settings_layer_height_spacious),
                    ),
                    selected = vm.layerRowHeightDp,
                    shape = settingGroupShape(0, 3),
                    onSelect = { vm.updateLayerRowHeight(it) },
                )

                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.ViewSidebar,
                    title = stringResource(R.string.theme_left_hand_mode_title),
                    summary = stringResource(R.string.theme_left_hand_mode_desc),
                    checked = vm.leftHandMode,
                    shape = settingGroupShape(1, 3),
                    onCheckedChange = { vm.updateLeftHandMode(it) },
                )

                SettingSliderGroupItem(
                    icon = Icons.Rounded.Opacity,
                    title = stringResource(R.string.theme_selection_mask_opacity_title),
                    summary = stringResource(R.string.theme_selection_mask_opacity_desc),
                    valueText = "${(vm.selectionMaskOpacity * 100).roundToInt()}%",
                    sliderFraction = ((vm.selectionMaskOpacity - 0.10f) / (0.90f - 0.10f)).coerceIn(0f, 1f),
                    shape = settingGroupShape(2, 3),
                    onValueChange = { fraction ->
                        val newOpacity = (0.10f + fraction * 0.80f).coerceIn(0.10f, 0.90f)
                        vm.updateSelectionMaskOpacity(newOpacity)
                    },
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showCustomColorDialog) {
        CompactColorPickerPopup(
            title = stringResource(R.string.color_custom),
            initialHex = vm.accentColorHex,
            anchorBounds = customColorAnchorBounds,
            onColorConfirmed = { hex ->
                vm.updateAccentColor(hex)
                showCustomColorDialog = false
            },
            onDismiss = { showCustomColorDialog = false },
        )
    }

    if (showCustomCanvasBgDialog) {
        CompactColorPickerPopup(
            title = stringResource(R.string.theme_custom_canvas_bg),
            initialHex = if (vm.canvasBgColorHex == "DEFAULT" || vm.canvasBgColorHex.isBlank()) "#2F3136" else vm.canvasBgColorHex,
            anchorBounds = customCanvasBgAnchorBounds,
            onColorConfirmed = { hex ->
                vm.updateCanvasBgColor(hex)
                showCustomCanvasBgDialog = false
            },
            onDismiss = { showCustomCanvasBgDialog = false },
        )
    }

    if (showFloatingLayoutDialog) {
        com.reverie.paint.ui.painting.FloatingLayoutConfigDialog(
            vm = vm,
            onDismiss = { showFloatingLayoutDialog = false },
        )
    }

    if (showSmallScreenWarningDialog) {
        AlertDialog(
            onDismissRequest = { showSmallScreenWarningDialog = false },
            containerColor = colors.bg,
            titleContentColor = colors.text,
            textContentColor = colors.subText,
            title = { Text(stringResource(R.string.theme_floating_small_screen_title)) },
            text = { Text(stringResource(R.string.theme_floating_small_screen_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showSmallScreenWarningDialog = false
                    vm.updateFloatingUiEnabled(true)
                }) {
                    Text(stringResource(R.string.common_confirm_enable), color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSmallScreenWarningDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.text)
                }
            },
        )
    }
}


