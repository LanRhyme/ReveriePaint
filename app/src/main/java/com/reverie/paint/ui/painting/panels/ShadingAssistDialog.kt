/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.*
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*

/**
 * 智能阴影辅助 (Shading Assist Beta) 全流程交互配置弹窗。
 * 提供 3D 光影球拖拽、深度神经网络实时预览、模型管理与随时卸载/重新下载、参考线稿选择与一键生成。
 */
@Composable
fun ShadingAssistDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit = { vm.closeShadingAssist() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val config = vm.shadingAssistConfig
    val modelStatus = ShadingModelManager.modelStatus

    // 本地 .onnx 模型文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                ShadingModelManager.importCustomModel(context, uri).fold(
                    onSuccess = {
                        Toast.makeText(context, R.string.shading_assist_model_ready, Toast.LENGTH_SHORT).show()
                        vm.updateShadingConfig(vm.shadingAssistConfig, reloadLineart = true)
                    },
                    onFailure = {
                        Toast.makeText(context, it.message ?: context.getString(R.string.shading_assist_import_failed), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxHeight(0.92f)
                    .clip(RoundedCornerShape(20.dp))
                    .shadow(16.dp, RoundedCornerShape(20.dp)),
                color = Morandi.panel
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // ---- 顶部标题栏 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Morandi.panelHi),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_moon),
                                    contentDescription = null,
                                    tint = Morandi.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_title),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Morandi.text
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Morandi.accent.copy(alpha = 0.20f))
                                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                    ) {
                                        Text(
                                            text = "Beta",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Morandi.accent
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.shading_assist_desc),
                                    fontSize = 11.sp,
                                    color = Morandi.subText
                                )
                            }
                        }

                        ReIconButton(
                            icon = R.drawable.ic_x,
                            desc = stringResource(R.string.common_close),
                            onTap = onDismiss,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Morandi.panelHi, thickness = 0.8.dp)
                    Spacer(Modifier.height(14.dp))

                    // ---- 主交互区 (左视口预览 + 右侧控制面板) ----
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // 左侧：3D 光源小球 + 实时阴影预览
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.shading_assist_light_source),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Morandi.text
                            )

                            // 3D 光照球拖拽控件
                            LightOrbManipulator(
                                azimuthDeg = config.lightAzimuthDeg,
                                elevationDeg = config.lightElevationDeg,
                                onLightChange = { az, el ->
                                    vm.updateShadingLight(az, el)
                                },
                                modifier = Modifier.size(136.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Text(
                                    text = stringResource(R.string.shading_assist_azimuth, config.lightAzimuthDeg.toInt()),
                                    fontSize = 11.sp,
                                    color = Morandi.subText
                                )
                                Text(
                                    text = stringResource(R.string.shading_assist_elevation, config.lightElevationDeg.toInt()),
                                    fontSize = 11.sp,
                                    color = Morandi.subText
                                )
                            }

                            // 常用打光预设快捷标签
                            val presetsList = remember {
                                listOf(
                                    Triple(R.string.shading_assist_preset_front_left, 150f, 50f),
                                    Triple(R.string.shading_assist_preset_front_right, 30f, 50f),
                                    Triple(R.string.shading_assist_preset_top, 90f, 75f),
                                    Triple(R.string.shading_assist_preset_side, 180f, 40f),
                                    Triple(R.string.shading_assist_preset_back, 300f, 45f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                presetsList.forEach { (labelRes, az, el) ->
                                    val isSel = abs(config.lightAzimuthDeg - az) < 15f && abs(config.lightElevationDeg - el) < 15f
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSel) Morandi.accent else Morandi.panelHi)
                                            .clickable { vm.updateShadingLight(az, el) }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(labelRes),
                                            fontSize = 10.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) Color.White else Morandi.subText,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // 实时预览视口 (就绪态 / 未下载空状态 / 下载进度态)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Morandi.panelHi),
                                contentAlignment = Alignment.Center
                            ) {
                                when (modelStatus) {
                                    is ModelStatus.Ready -> {
                                        val baseBmp = vm.shadingAssistBaseBitmap
                                        val shadowBmp = vm.shadingAssistPreviewBitmap
                                        val lineBmp = vm.shadingAssistLineBitmap

                                        if (baseBmp != null && !baseBmp.isRecycled) {
                                            Image(
                                                bitmap = baseBmp.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        if (shadowBmp != null && !shadowBmp.isRecycled) {
                                            Image(
                                                bitmap = shadowBmp.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        if (lineBmp != null && !lineBmp.isRecycled) {
                                            Image(
                                                bitmap = lineBmp.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        if (vm.shadingAssistIsProcessing) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.Black.copy(alpha = 0.35f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(28.dp),
                                                    color = Morandi.accent,
                                                    strokeWidth = 2.5.dp
                                                )
                                            }
                                        }
                                    }

                                    is ModelStatus.Downloading -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { modelStatus.progressPercent / 100f },
                                                modifier = Modifier.size(40.dp),
                                                color = Morandi.accent,
                                                strokeWidth = 3.dp
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Text(
                                                text = stringResource(R.string.shading_assist_downloading, modelStatus.progressPercent),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Morandi.text
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            val downloadedMb = modelStatus.downloadedBytes / (1024f * 1024f)
                                            val totalMb = modelStatus.totalBytes / (1024f * 1024f)
                                            Text(
                                                text = String.format(Locale.US, "%.1f MB / %.1f MB", downloadedMb, totalMb),
                                                fontSize = 10.sp,
                                                color = Morandi.subText
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            ReTextButton(
                                                text = stringResource(R.string.common_cancel),
                                                fontSize = 11.sp,
                                                onClick = { ShadingModelManager.cancelDownload(context) }
                                            )
                                        }
                                    }

                                    else -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(Morandi.panel),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_moon),
                                                    contentDescription = null,
                                                    tint = Morandi.accent,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text(
                                                text = stringResource(R.string.shading_assist_model_not_installed),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Morandi.text
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.shading_assist_model_not_installed_desc),
                                                fontSize = 10.sp,
                                                color = Morandi.subText,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(Modifier.height(14.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                ReTextButton(
                                                    text = stringResource(R.string.shading_assist_model_download),
                                                    primary = true,
                                                    fontSize = 11.sp,
                                                    onClick = {
                                                        ShadingModelManager.startDownload(
                                                            context = context,
                                                            scope = coroutineScope,
                                                            onSuccess = {
                                                                vm.updateShadingConfig(vm.shadingAssistConfig, reloadLineart = true)
                                                            }
                                                        )
                                                    }
                                                )
                                                ReTextButton(
                                                    text = stringResource(R.string.shading_assist_import_model),
                                                    primary = false,
                                                    fontSize = 11.sp,
                                                    onClick = { filePickerLauncher.launch("*/*") }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        VerticalDivider(color = Morandi.panelHi, thickness = 0.8.dp)

                        // 右侧：模型管理与参数微调
                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // 1. 深度神经网络模型状态与操作卡片
                            ModelStatusCard(
                                status = modelStatus,
                                onDownload = {
                                    ShadingModelManager.startDownload(
                                        context = context,
                                        scope = coroutineScope,
                                        onSuccess = {
                                            vm.updateShadingConfig(vm.shadingAssistConfig, reloadLineart = true)
                                        }
                                    )
                                },
                                onCancelDownload = { ShadingModelManager.cancelDownload(context) },
                                onUninstall = {
                                    ShadingModelManager.uninstallModel(context)
                                    Toast.makeText(context, R.string.shading_assist_model_uninstalled, Toast.LENGTH_SHORT).show()
                                    vm.updateShadingConfig(vm.shadingAssistConfig, reloadLineart = true)
                                },
                                onImportLocal = { filePickerLauncher.launch("*/*") },
                                onResetDefault = {
                                    ShadingModelManager.clearCustomModel(context)
                                    vm.updateShadingConfig(vm.shadingAssistConfig, reloadLineart = true)
                                }
                            )

                            // 2. 参考线稿图层选择
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.shading_assist_lineart_layer),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Morandi.text
                                )

                                LineArtLayerPicker(
                                    layers = vm.layers,
                                    excludeLayerId = vm.currentLayerIndex,
                                    selectedLayerId = config.referenceLineLayerId,
                                    onSelect = { layerId ->
                                        vm.updateShadingConfig(
                                            vm.shadingAssistConfig.copy(referenceLineLayerId = layerId),
                                            reloadLineart = true
                                        )
                                    }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_lineart_occlusion),
                                        fontSize = 12.sp,
                                        color = Morandi.subText
                                    )
                                    ReSwitch(
                                        checked = config.lineArtOcclusion,
                                        onChecked = {
                                            vm.updateShadingConfig(vm.shadingAssistConfig.copy(lineArtOcclusion = it))
                                        }
                                    )
                                }
                            }

                            HorizontalDivider(color = Morandi.panelHi, thickness = 0.8.dp)

                            // 3. 风格模式 (赛璐珞 / 柔和过渡)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panelHi)
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val isCel = config.mode == ShadingMode.CEL
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isCel) Morandi.accent else Color.Transparent)
                                        .clickable { vm.updateShadingConfig(vm.shadingAssistConfig.copy(mode = ShadingMode.CEL)) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_mode_cel),
                                        fontSize = 12.sp,
                                        fontWeight = if (isCel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCel) Color.White else Morandi.subText
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (!isCel) Morandi.accent else Color.Transparent)
                                        .clickable { vm.updateShadingConfig(vm.shadingAssistConfig.copy(mode = ShadingMode.SMOOTH)) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_mode_smooth),
                                        fontSize = 12.sp,
                                        fontWeight = if (!isCel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!isCel) Color.White else Morandi.subText
                                    )
                                }
                            }

                            // 赛璐珞模式下的阶梯切换 (单阶 / 双阶) - 采用与上一致的莫兰迪分段按钮，杜绝白边
                            if (config.mode == ShadingMode.CEL) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.panelHi)
                                        .padding(3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val isTwoStep = config.celSteps == CelSteps.TWO_STEP
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (!isTwoStep) Morandi.accent else Color.Transparent)
                                            .clickable { vm.updateShadingConfig(vm.shadingAssistConfig.copy(celSteps = CelSteps.ONE_STEP)) }
                                            .padding(vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.shading_assist_steps_1),
                                            fontSize = 11.sp,
                                            fontWeight = if (!isTwoStep) FontWeight.Bold else FontWeight.Normal,
                                            color = if (!isTwoStep) Color.White else Morandi.subText
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isTwoStep) Morandi.accent else Color.Transparent)
                                            .clickable { vm.updateShadingConfig(vm.shadingAssistConfig.copy(celSteps = CelSteps.TWO_STEP)) }
                                            .padding(vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.shading_assist_steps_2),
                                            fontSize = 11.sp,
                                            fontWeight = if (isTwoStep) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isTwoStep) Color.White else Morandi.subText
                                        )
                                    }
                                }
                            }

                            // 4. 阴影覆盖范围滑块
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_threshold),
                                        fontSize = 11.sp,
                                        color = Morandi.subText
                                    )
                                    Text(
                                        text = "${(config.threshold1 * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = Morandi.text
                                    )
                                }
                                ReSlider(
                                    value = config.threshold1,
                                    onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(threshold1 = it)) }
                                )
                            }

                            // 双阶阴影下的二阶深色阈值
                            if (config.mode == ShadingMode.CEL && config.celSteps == CelSteps.TWO_STEP) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.shading_assist_threshold_sub),
                                            fontSize = 11.sp,
                                            color = Morandi.subText
                                        )
                                        Text(
                                            text = "${(config.threshold2 * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            color = Morandi.text
                                        )
                                    }
                                    ReSlider(
                                        value = config.threshold2,
                                        onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(threshold2 = it)) }
                                    )
                                }
                            }

                            // 线稿投射微调参数
                            if (config.lineArtOcclusion) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.shading_assist_line_cast_dist),
                                            fontSize = 11.sp,
                                            color = Morandi.subText
                                        )
                                        Text(
                                            text = "${config.lineCastDistance.toInt()} px",
                                            fontSize = 11.sp,
                                            color = Morandi.text
                                        )
                                    }
                                    ReSlider(
                                        value = (config.lineCastDistance / 40.0f).coerceIn(0f, 1f),
                                        onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(lineCastDistance = it * 40.0f)) }
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.shading_assist_line_cast_intensity),
                                            fontSize = 11.sp,
                                            color = Morandi.subText
                                        )
                                        Text(
                                            text = "${(config.lineCastIntensity * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            color = Morandi.text
                                        )
                                    }
                                    ReSlider(
                                        value = (config.lineCastIntensity / 2.0f).coerceIn(0f, 1f),
                                        onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(lineCastIntensity = it * 2.0f)) }
                                    )
                                }
                            }

                            // 体积平滑度 (消除局部斑块并决定宏观光影圆润度)
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_volume_smoothness),
                                        fontSize = 11.sp,
                                        color = Morandi.subText
                                    )
                                    Text(
                                        text = "${config.volumeSmoothness.toInt()} px",
                                        fontSize = 11.sp,
                                        color = Morandi.text
                                    )
                                }
                                ReSlider(
                                    value = ((config.volumeSmoothness - 10.0f) / 50.0f).coerceIn(0f, 1f),
                                    onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(volumeSmoothness = 10.0f + it * 50.0f)) }
                                )
                            }

                            // 边缘平滑度
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_softness),
                                        fontSize = 11.sp,
                                        color = Morandi.subText
                                    )
                                    Text(
                                        text = "${(config.softness * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = Morandi.text
                                    )
                                }
                                ReSlider(
                                    value = config.softness,
                                    onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(softness = it)) }
                                )
                            }

                            // 不透明度
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.shading_assist_opacity),
                                        fontSize = 11.sp,
                                        color = Morandi.subText
                                    )
                                    Text(
                                        text = "${(config.shadowOpacity * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = Morandi.text
                                    )
                                }
                                ReSlider(
                                    value = config.shadowOpacity,
                                    onValue = { vm.updateShadingConfig(vm.shadingAssistConfig.copy(shadowOpacity = it)) }
                                )
                            }

                            // 5. 阴影色彩色板
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.shading_assist_shadow_color),
                                    fontSize = 11.sp,
                                    color = Morandi.subText
                                )
                                val presets = listOf(
                                    0xFF5A4D6EL, // 经典冷紫灰 (日漫标准正片叠底)
                                    0xFF3D465CL, // 暮夜冷青
                                    0xFF5C4738L, // 暖褐色
                                    0xFF2C3947L, // 深藏青
                                    0xFF3F3F44L  // 中性铅灰
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    presets.forEach { colorVal ->
                                        val isSelected = config.shadowColor == colorVal
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(Color(colorVal))
                                                .clickable {
                                                    vm.updateShadingConfig(vm.shadingAssistConfig.copy(shadowColor = colorVal))
                                                }
                                                .then(
                                                    if (isSelected) {
                                                        Modifier.border(2.5.dp, Morandi.accent, CircleShape)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Morandi.panelHi, thickness = 0.8.dp)
                    Spacer(Modifier.height(14.dp))

                    // ---- 底部确认按钮 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.padding(end = 10.dp)
                        )

                        val canApply = modelStatus is ModelStatus.Ready && !vm.shadingAssistIsProcessing
                        ReTextButton(
                            text = if (vm.shadingAssistIsProcessing) {
                                stringResource(R.string.shading_assist_generating)
                            } else {
                                stringResource(R.string.shading_assist_apply)
                            },
                            primary = true,
                            enabled = canApply,
                            onClick = {
                                vm.applyShadingAssist()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 3D 光影球触摸旋转控制器
 */
@Composable
private fun LightOrbManipulator(
    azimuthDeg: Float,
    elevationDeg: Float,
    onLightChange: (azimuth: Float, elevation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnLightChange by rememberUpdatedState(onLightChange)
    val azRad = Math.toRadians(azimuthDeg.toDouble())
    // 仰角在 [40°, 85°] 之间平滑映射 (圆心 85° 顶部光, 边缘 40° 侧光)
    val distRatio = ((85f - elevationDeg.coerceIn(40f, 85f)) / 45f).coerceIn(0.0f, 0.95f)

    Box(
        modifier = modifier
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val center = size.width / 2f
                    val dx = (change.position.x - center) / center
                    val dy = (change.position.y - center) / center

                    val dist = sqrt(dx * dx + dy * dy).coerceIn(0f, 0.95f)
                    var az = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
                    if (az < 0) az += 360f

                    val el = (1.0f - dist) * 45f + 40f
                    currentOnLightChange(az, el)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size.width / 2f
            val indicatorDist = center * distRatio

            val pinX = center + indicatorDist * cos(azRad).toFloat()
            val pinY = center - indicatorDist * sin(azRad).toFloat()

            // 动态绘制带 3D 照亮高光与背光渐变的立体小球
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Morandi.text, Morandi.subText, Morandi.panelHi, Morandi.bg),
                    center = Offset(pinX, pinY),
                    radius = center * 1.6f
                ),
                radius = center,
                center = Offset(center, center)
            )

            // 绘制光源光晕与锚点
            drawCircle(
                color = Morandi.accent.copy(alpha = 0.40f),
                radius = 14.dp.toPx(),
                center = Offset(pinX, pinY)
            )
            drawCircle(
                color = Morandi.onAccent,
                radius = 6.dp.toPx(),
                center = Offset(pinX, pinY)
            )
            drawCircle(
                color = Morandi.accentHi,
                radius = 6.dp.toPx(),
                center = Offset(pinX, pinY),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * 神经网络模型状态展示与操作卡片
 */
@Composable
private fun ModelStatusCard(
    status: ModelStatus,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onUninstall: () -> Unit,
    onImportLocal: () -> Unit,
    onResetDefault: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Morandi.panelHi),
        border = null
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 顶栏：标题与状态指示胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_moon),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.shading_assist_model_card_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Morandi.text
                    )
                }

                when (status) {
                    is ModelStatus.Ready -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Morandi.accent.copy(alpha = 0.15f),
                            border = null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Morandi.accent)
                                )
                                Text(
                                    text = stringResource(R.string.shading_assist_model_status_ready),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Morandi.accent
                                )
                            }
                        }
                    }

                    is ModelStatus.Downloading -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Morandi.accent.copy(alpha = 0.15f),
                            border = null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(8.dp),
                                    color = Morandi.accent,
                                    strokeWidth = 1.5.dp
                                )
                                Text(
                                    text = stringResource(R.string.shading_assist_model_status_downloading),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Morandi.accent
                                )
                            }
                        }
                    }

                    else -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Morandi.panel,
                            border = null
                        ) {
                            Text(
                                text = stringResource(R.string.shading_assist_model_status_not_downloaded),
                                fontSize = 10.sp,
                                color = Morandi.subText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 内容与交互区
            when (status) {
                is ModelStatus.Ready -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (status.isCustom) {
                                stringResource(R.string.shading_assist_custom_model_active, status.fileName)
                            } else {
                                status.fileName
                            },
                            fontSize = 11.sp,
                            color = Morandi.subText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (status.isCustom) {
                                Text(
                                    text = stringResource(R.string.shading_assist_reset_model),
                                    fontSize = 11.sp,
                                    color = Morandi.accent,
                                    modifier = Modifier.clickable { onResetDefault() }
                                )
                            }
                            Text(
                                text = stringResource(R.string.shading_assist_import_model),
                                fontSize = 11.sp,
                                color = Morandi.accent,
                                modifier = Modifier.clickable { onImportLocal() }
                            )
                            Text(
                                text = stringResource(R.string.shading_assist_model_uninstall),
                                fontSize = 11.sp,
                                color = Morandi.error,
                                modifier = Modifier.clickable { onUninstall() }
                            )
                        }
                    }
                }

                is ModelStatus.Downloading -> {
                    LinearProgressIndicator(
                        progress = { status.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Morandi.accent,
                        trackColor = Morandi.panel
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val downloadedMb = status.downloadedBytes / (1024f * 1024f)
                        val totalMb = status.totalBytes / (1024f * 1024f)
                        Text(
                            text = String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", downloadedMb, totalMb, status.progressPercent),
                            fontSize = 10.sp,
                            color = Morandi.subText
                        )
                        Text(
                            text = stringResource(R.string.common_cancel),
                            fontSize = 11.sp,
                            color = Morandi.accent,
                            modifier = Modifier.clickable { onCancelDownload() }
                        )
                    }
                }

                else -> {
                    Text(
                        text = stringResource(R.string.shading_assist_model_not_installed_desc),
                        fontSize = 11.sp,
                        color = Morandi.subText
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.shading_assist_model_download),
                            onClick = onDownload,
                            primary = true,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        ReTextButton(
                            text = stringResource(R.string.shading_assist_import_model),
                            onClick = onImportLocal,
                            primary = false,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 线稿图层下拉选择器 (纯净无白边，支持深色模式高对比度文字)
 */
@Composable
private fun LineArtLayerPicker(
    layers: List<PaintViewModel.LayerUiState>,
    excludeLayerId: Int,
    selectedLayerId: Int?,
    onSelect: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val candidateLayers = remember(layers, excludeLayerId) {
        layers.filter { it.index != excludeLayerId && !it.isGroup && it.nodeType == 0 }
    }

    val selectedName = if (selectedLayerId == null) {
        stringResource(R.string.shading_assist_lineart_none)
    } else {
        candidateLayers.firstOrNull { it.index == selectedLayerId }?.name
            ?: stringResource(R.string.shading_assist_lineart_none)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true },
            color = Morandi.panelHi
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedName,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Morandi.text
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = Morandi.subText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        ReDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp)
        ) {
            val isNoneSelected = selectedLayerId == null
            ReDropdownMenuItem(
                text = stringResource(R.string.shading_assist_lineart_none),
                selected = isNoneSelected,
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            candidateLayers.forEach { layer ->
                val isSelected = layer.index == selectedLayerId
                ReDropdownMenuItem(
                    text = layer.name,
                    selected = isSelected,
                    onClick = {
                        onSelect(layer.index)
                        expanded = false
                    }
                )
            }
        }
    }
}
