/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import com.reverie.paint.model.AdjustmentConfigCodec
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.util.lerp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun LayerDetailPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
    onOpenBlendModes: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenFilterAdjust: (Int, String) -> Unit = { _, _ -> onOpenFilters() },
    onRename: (String) -> Unit,
) {
    val context = LocalContext.current
    val layer = vm.layers.firstOrNull { it.index == index }
    val isBg = layer?.isBackground == true
    val name = layer?.name ?: ""
    if (layer?.isBackground == true) {
    Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .noRippleClickable(onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chevron),
                        contentDescription = stringResource(R.string.common_back),
                        tint = Morandi.icon,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    stringResource(R.string.layer_bg_settings_title),
                    color = Morandi.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                var currentColor by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
                var showBgColorPicker by remember { mutableStateOf(false) }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
                            .noRippleClickable { showBgColorPicker = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(currentColor)),
                        )
                        Column {
                            Text(stringResource(R.string.layer_bg_color), color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(String.format("#%06X", 0xFFFFFF and currentColor), color = Morandi.subText, fontSize = 11.sp)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.layer_bg_pick_color), color = Morandi.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Icon(
                            painterResource(R.drawable.ic_chevron),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (showBgColorPicker) {
                    CompactColorPickerDialog(
                        title = stringResource(R.string.layer_bg_set_dialog_title),
                        initialColor = Color(currentColor),
                        onColorSelected = { col ->
                            val cInt =
                                android.graphics.Color.argb(
                                    255,
                                    (col.red * 255).toInt(),
                                    (col.green * 255).toInt(),
                                    (col.blue * 255).toInt(),
                                )
                            currentColor = cInt
                            vm.setBackgroundColor(cInt, commit = true)
                        },
                        onDismiss = { showBgColorPicker = false },
                    )
                }
            }
        }
        return
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
    ) {
        // Header: < 图层设置
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .noRippleClickable(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.common_back),
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                stringResource(R.string.layer_settings_title),
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        val isFillLayer = (layer?.nodeType == 2) || name.contains("填充") || name.contains("Fill", ignoreCase = true)
        val isFilterLayer = (layer?.nodeType == 3) || name.contains("滤镜") || name.contains("Filter", ignoreCase = true)

        if (isFillLayer) {
            var showFillColorPicker by remember { mutableStateOf(false) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .noRippleClickable { showFillColorPicker = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.accent),
                    )
                    Column {
                        Text(stringResource(R.string.layer_fill_color), color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.layer_fill_change), color = Morandi.subText, fontSize = 11.sp)
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = Morandi.icon,
                    modifier = Modifier.size(14.dp),
                )
            }

            if (showFillColorPicker) {
                CompactColorPickerDialog(
                    title = stringResource(R.string.layer_fill_select_title),
                    initialColor = Morandi.accent,
                    onColorSelected = { col ->
                        val hex = String.format("#%02X%02X%02X", (col.red * 255).toInt(), (col.green * 255).toInt(), (col.blue * 255).toInt())
                        // 必须走 updateBrushColor 同步引擎侧颜色, 裸赋值只改 UI 镜像会填上一次的旧色
                        vm.updateBrushColor(hex)
                        vm.fillLayerForeground(index)
                    },
                    onDismiss = { showFillColorPicker = false },
                )
            }
        }

        if (isFilterLayer) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.accent.copy(alpha = 0.15f))
                        .noRippleClickable {
                            val json = vm.snapshotAdjustmentConfig(index)
                            val cfg = AdjustmentConfigCodec.decodeJson(json)
                            if (cfg != null) {
                                onOpenFilterAdjust(cfg.type, filterNameOf(cfg.type, context))
                            } else {
                                onOpenFilters()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_image_adjust),
                        contentDescription = null,
                        tint = Morandi.icon,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(stringResource(R.string.layer_filter_adjust_title), color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.layer_filter_adjust_desc), color = Morandi.subText, fontSize = 11.sp)
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = Morandi.icon,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Blend mode row button
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .noRippleClickable(onOpenBlendModes)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_layerstack),
                contentDescription = null,
                tint = Morandi.icon,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.layer_blend_mode),
                color = Morandi.text,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            val curBlendName = stringResource(blendModeResId(layer?.blendMode ?: "normal"))
            Text(
                curBlendName,
                color = Morandi.subText,
                fontSize = 13.sp,
            )
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(16.dp))
        }

        // 滤镜与颜色调整 (非滤镜图层时显示)
        if (layer?.isGroup != true && !isFilterLayer) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .noRippleClickable(onOpenFilters)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_sliders),
                    contentDescription = null,
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
                Text(stringResource(R.string.layer_filters_and_color), color = Morandi.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = Morandi.subText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Morandi.border.copy(alpha = 0.5f)),
        )

        // Opacity slider
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.layer_opacity), color = Morandi.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${((layer?.opacity ?: 1.0) * 100).roundToInt()}%", color = Morandi.subText, fontSize = 13.sp)
        }
        var localOpacity by remember(index) { mutableFloatStateOf((layer?.opacity ?: 1.0).toFloat()) }
        var lastOpacityNs by remember(index) { mutableLongStateOf(0L) }
        ReSlider(
            value = localOpacity,
            onValue = {
                localOpacity = it
                val now = System.nanoTime()
                if (now - lastOpacityNs > 50_000_000L) {
                    lastOpacityNs = now
                    // Drag preview: no undo step, no thumbnail refresh, no
                    // immediate frame - just the throttled render
                    vm.setLayerOpacity(index, it.toDouble(), preview = true)
                }
            },
            onRelease = {
                // Drag finished: commit once through the undo stack and do the
                // full refresh (thumbnails + immediate render). One drag = one
                // undo step instead of dozens of per-tick commands
                vm.setLayerOpacity(index, localOpacity.toDouble())
            },
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Morandi.border),
        )

        // Krita 8-color label picker
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val currentLabel = layer?.colorLabel ?: 0
            for (label in 0..8) {
                val color = layerLabelColor(label)
                val isSelected = currentLabel == label
                Box(
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (label == 0) Morandi.panelHi else color)
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, Morandi.accent, CircleShape)
                                } else Modifier
                            ).clickable { vm.setLayerColorLabel(index, label) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (label == 0) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Morandi.subText.copy(alpha = 0.5f)))
                    } else if (isSelected) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Morandi.border),
        )

        var showGroupPicker by remember { mutableStateOf(false) }
        var showRasterizeConfirm by remember { mutableStateOf(false) }
        val availableGroups =
            remember(vm.layers) {
                vm.layers.filter { it.isGroup && it.index != index }
            }

        if (showRasterizeConfirm) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showRasterizeConfirm = false }) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.9f)
                            .shadow(16.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                            .clip(RoundedCornerShape(14.dp))
                            .background(Morandi.panel)
                            .glassBorder(RoundedCornerShape(14.dp))
                            .padding(18.dp),
                ) {
                    Column {
                        Text(stringResource(R.string.layer_rasterize_dialog_title), color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.layer_rasterize_dialog_desc),
                            color = Morandi.subText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.panelHi)
                                        .clickable { showRasterizeConfirm = false }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                            ) {
                                Text(stringResource(R.string.common_cancel), color = Morandi.text, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.accent)
                                        .clickable {
                                            showRasterizeConfirm = false
                                            vm.rasterizeLayer(index)
                                            onBack()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                            ) {
                                Text(stringResource(R.string.layer_rasterize_confirm), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        if (showGroupPicker) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showGroupPicker = false }) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.9f)
                            .shadow(16.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                            .clip(RoundedCornerShape(14.dp))
                            .background(Morandi.panel)
                            .glassBorder(RoundedCornerShape(14.dp))
                            .padding(18.dp),
                ) {
                    Column {
                        Text(stringResource(R.string.layer_move_into_group_title), color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            if (availableGroups.isEmpty()) {
                                Text(stringResource(R.string.layer_no_other_groups), color = Morandi.subText, fontSize = 13.sp)
                            } else {
                                availableGroups.forEach { grp ->
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .noRippleClickable {
                                                    vm.moveLayerToGroup(index, grp.index)
                                                    showGroupPicker = false
                                                }.padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_folder),
                                            null,
                                            tint = Morandi.icon,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(layerDisplayName(grp.name), color = Morandi.text, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.panelHi)
                                        .clickable { showGroupPicker = false }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(stringResource(R.string.common_cancel), color = Morandi.text, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        if (layer?.isGroup == true) {
            // Group-specific page
            Column {
                OpItem(R.drawable.ic_rename, stringResource(R.string.layer_op_rename)) { onRename(name) }
                OpItem(R.drawable.ic_trash, stringResource(R.string.layer_op_delete_group), enabled = !isBg) {
                    vm.removeLayer(index)
                    onBack()
                }
                OpItem(R.drawable.ic_merge_down, stringResource(R.string.layer_op_merge_group), enabled = !isBg) {
                    vm.flattenGroup(index)
                    onBack()
                }
                OpToggle(R.drawable.ic_lock, stringResource(R.string.layer_op_lock_group), layer?.locked == true || isBg, enabled = !isBg) {
                    vm.setLayerLocked(index, !(layer?.locked == true))
                }
                OpToggle(R.drawable.ic_clip, stringResource(R.string.layer_op_clip), layer?.clipped == true, enabled = !isBg) {
                    vm.setLayerClipped(index, !(layer?.clipped == true))
                }
                OpToggle(R.drawable.ic_sliders, stringResource(R.string.layer_op_pass_through), vm.groupPassThrough(index)) {
                    vm.setGroupPassThrough(index, !vm.groupPassThrough(index))
                }
            }
        } else {
            // Vertical operation list
            Column {
                OpItem(R.drawable.ic_copy, stringResource(R.string.layer_op_duplicate)) { vm.copyLayer(index) }
                OpItem(R.drawable.ic_rename, stringResource(R.string.layer_op_rename)) { onRename(name) }
                OpItem(R.drawable.ic_trash, stringResource(R.string.layer_op_delete_layer), enabled = !isBg) {
                    vm.removeLayer(index)
                    onBack()
                }
                if ((layer?.depth ?: 0) > 0) {
                    OpItem(R.drawable.ic_folder, stringResource(R.string.layer_op_move_out_group)) { vm.moveLayerOut(index) }
                }
                if (availableGroups.isNotEmpty()) {
                    OpItem(R.drawable.ic_folder, stringResource(R.string.layer_op_move_into_group)) { showGroupPicker = true }
                }
                OpItem(R.drawable.ic_flip_h, stringResource(R.string.layer_op_flip_h), enabled = !isFilterLayer) { vm.flipLayerHorizontal(index) }
                OpItem(R.drawable.ic_flip_v, stringResource(R.string.layer_op_flip_v), enabled = !isFilterLayer) { vm.flipLayerVertical(index) }
                OpItem(R.drawable.ic_merge_down, stringResource(R.string.layer_op_merge_down), enabled = !isBg && index > 0 && !isFilterLayer) {
                    vm.mergeDown(index)
                    onBack()
                }
                OpItem(R.drawable.ic_select, stringResource(R.string.layer_op_select_from_layer), enabled = !isFilterLayer) { vm.selectionFromLayer(index) }
                OpToggle(R.drawable.ic_lock, stringResource(R.string.layer_op_lock_layer), layer?.locked == true || isBg, enabled = !isBg) {
                    vm.setLayerLocked(index, !(layer?.locked == true))
                }
                OpToggle(R.drawable.ic_grid, stringResource(R.string.layer_op_alpha_lock), layer?.alphaLocked == true, enabled = !isBg && !isFilterLayer) {
                    vm.setLayerAlphaLocked(index, !(layer?.alphaLocked == true))
                }
                OpToggle(R.drawable.ic_clip, stringResource(R.string.layer_op_clip), layer?.clipped == true, enabled = !isBg) {
                    vm.setLayerClipped(index, !(layer?.clipped == true))
                }
                val canRasterize = isFilterLayer || isFillLayer || (layer != null && layer.nodeType != 0 && !layer.isGroup)
                if (canRasterize) {
                    OpItem(R.drawable.ic_fill, stringResource(R.string.layer_op_rasterize)) {
                        if (isFilterLayer) {
                            showRasterizeConfirm = true
                        } else {
                            vm.rasterizeLayer(index)
                            onBack()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpItem(
    resId: Int,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable { if (enabled) onClick() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(resId),
            contentDescription = null,
            tint = if (enabled) Morandi.icon else Morandi.subText.copy(alpha = 0.35f),
            modifier = Modifier.size(17.dp),
        )
        Text(
            text,
            color = if (enabled) Morandi.text else Morandi.subText.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun OpToggle(
    resId: Int,
    text: String,
    on: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable { if (enabled) onClick() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(resId),
            contentDescription = null,
            tint =
                if (enabled && on) {
                    Morandi.accent
                } else if (enabled) {
                    Morandi.icon
                } else {
                    Morandi.subText.copy(alpha = 0.4f)
                },
            modifier = Modifier.size(17.dp),
        )
        Text(
            text,
            color = if (enabled) Morandi.text else Morandi.subText.copy(alpha = 0.5f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(34.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (on && enabled) Morandi.accent else Morandi.panel),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on && enabled) Morandi.onAccent else Morandi.subText)
                    .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Blend modes sub page
// ---------------------------------------------------------------------------

@Composable
internal fun BlendModesPage(
    vm: PaintViewModel,
    index: Int,
    onBack: () -> Unit,
) {
    val current = vm.layers.firstOrNull { it.index == index }?.blendMode
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemH = 40.dp
    val wheelH = 240.dp
    val padV = (wheelH - itemH) / 2
        // 混合模式图标（语义映射到现有 drawable，便于扫视区分）
        // GIMP ell/blending-mode-icons 分支转换的白描边专属图标（GPL 兼容）
        val modeIcons = mapOf(
            "normal" to R.drawable.ic_blend_normal,
            "multiply" to R.drawable.ic_blend_multiply,
            "screen" to R.drawable.ic_blend_screen,
            "overlay" to R.drawable.ic_blend_hardlight,
            "darken" to R.drawable.ic_blend_darken,
            "lighten" to R.drawable.ic_blend_lighten,
            "dodge" to R.drawable.ic_blend_dodge,
            "burn" to R.drawable.ic_blend_burn,
            "linear_burn" to R.drawable.ic_blend_darken,
            "linear_dodge" to R.drawable.ic_blend_add,
            "difference" to R.drawable.ic_blend_difference,
            "add" to R.drawable.ic_blend_add,
            "subtract" to R.drawable.ic_blend_subtract,
            "divide" to R.drawable.ic_blend_divide,
            "hard_light" to R.drawable.ic_blend_hardlight,
            "soft_light" to R.drawable.ic_blend_softlight,
            "vivid_light" to R.drawable.ic_blend_dodge,
            "pin_light" to R.drawable.ic_blend_burn,
            "linear light" to R.drawable.ic_blend_softlight,
            "exclusion" to R.drawable.ic_blend_difference,
            "hue" to R.drawable.ic_blend_hue,
            "saturation" to R.drawable.ic_blend_saturation,
            "color" to R.drawable.ic_blend_color,
            "value" to R.drawable.ic_blend_value,
        )
        fun iconFor(opId: String) = modeIcons[opId] ?: R.drawable.ic_blend_normal

    fun applyMode(opId: String) {
        if (opId != vm.layers.firstOrNull { it.index == index }?.blendMode) {
            vm.setLayerBlendMode(index, opId)
        }
    }

    val haptic = LocalHapticFeedback.current
    var lastCenterIdx by remember { mutableIntStateOf(-1) }

    LaunchedEffect(listState) {
        // 换挡轻震（不应用模式）
        launch {
            snapshotFlow {
                val info = listState.layoutInfo
                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo
                    .minByOrNull { abs((it.offset + it.size / 2) - mid) }?.index ?: -1
            }
                .distinctUntilChanged()
                .collect { idx ->
                    if (idx != -1 && idx != lastCenterIdx) {
                        lastCenterIdx = idx
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
        }
        // 滚动停止才应用当前中心项——快速甩动的中间模式不会被选中
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling && lastCenterIdx != -1) {
                    vm.blendModes.getOrNull(lastCenterIdx)?.let { applyMode(it.first) }
                }
            }
    }

    // 打开时把当前模式滚到定位条正中（contentPadding 已保证首尾可居中）
    LaunchedEffect(Unit) {
        val curIdx = vm.blendModes.indexOfFirst { it.first == current }
        if (curIdx > 0) listState.scrollToItem(curIdx)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .noRippleClickable(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.common_back),
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(stringResource(R.string.layer_blend_mode), color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(wheelH)
                .clip(RoundedCornerShape(10.dp))
                // 顶部/底部渐隐（iOS 滚轮签名效果）
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val f = ((itemH.toPx() * 1.1f) / size.height).coerceIn(0f, 0.45f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            f to Color.Black,
                            (1f - f) to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 中央定位托盘：中性胶囊底
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(itemH)
                    .background(Morandi.panelHi.copy(alpha = 0.78f), RoundedCornerShape(10.dp)),
            )
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(listState),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = padV),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(vm.blendModes) { itemIdx, (opId, _) ->
                    val name = stringResource(blendModeResId(opId))
                    val isSelected = opId == current
                    val rowSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val t by remember(itemIdx) {
                        androidx.compose.runtime.derivedStateOf {
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIdx }
                            val mid = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                            val dist = if (info != null) abs((info.offset + info.size / 2) - mid).toFloat() else Float.MAX_VALUE
                            val maxDist = with(density) { (itemH * 2f).toPx() }
                            (1f - dist / maxDist).coerceIn(0f, 1f) // 1=正中
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(itemH)
                                .pressScale(rowSource, pressedScale = 0.97f)
                                .clickable(interactionSource = rowSource, indication = null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    applyMode(opId)
                                    scope.launch {
                                        listState.animateScrollToItem(
                                            vm.blendModes.indexOfFirst { it.first == opId },
                                        )
                                    }
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(iconFor(opId)),
                                contentDescription = null,
                                tint = if (isSelected) Morandi.accent else Morandi.text.copy(alpha = lerp(0.35f, 0.9f, t)),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                name,
                                color = if (isSelected) Morandi.accent else Morandi.subText.copy(alpha = lerp(0.45f, 1f, t)),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filters sub page (HuaShijie Pro style list matching user screenshot)
// ---------------------------------------------------------------------------

internal fun blendModeResId(opId: String): Int = when (opId) {
    "normal", "copy" -> R.string.blend_normal
    "multiply" -> R.string.blend_multiply
    "screen" -> R.string.blend_screen
    "overlay" -> R.string.blend_overlay
    "darken" -> R.string.blend_darken
    "lighten" -> R.string.blend_lighten
    "dodge" -> R.string.blend_color_dodge
    "burn" -> R.string.blend_color_burn
    "linear_burn" -> R.string.blend_linear_burn
    "linear_dodge" -> R.string.blend_linear_dodge
    "difference" -> R.string.blend_difference
    "add" -> R.string.blend_add
    "subtract" -> R.string.blend_subtract
    "divide" -> R.string.blend_divide
    "hard_light" -> R.string.blend_hard_light
    "soft_light" -> R.string.blend_soft_light
    "vivid_light" -> R.string.blend_vivid_light
    "pin_light" -> R.string.blend_pin_light
    "linear light" -> R.string.blend_linear_light
    "exclusion" -> R.string.blend_exclusion
    "hue" -> R.string.blend_hue
    "saturation" -> R.string.blend_saturation
    "color" -> R.string.blend_color
    "value" -> R.string.blend_value
    "erase" -> R.string.blend_erase
    else -> R.string.blend_normal
}
