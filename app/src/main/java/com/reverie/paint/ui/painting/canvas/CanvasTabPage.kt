/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.ui.components.ReTextButton
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressScale
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
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
import com.reverie.paint.ui.painting.panels.SettingInfoRow

@Composable
internal fun CanvasTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onOpenFilters: ((List<Int>) -> Unit)? = null,
) {
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf(vm.docName) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            onClose()
            val bmp = ImageImportHelper.decodeUriSafely(context, uri)
            if (bmp != null) {
                vm.importImageToNewLayer(bmp) {
                    vm.isImportTransformPending = true
                    vm.applyTool(com.reverie.paint.model.Tool.TRANSFORM.id)
                }
            } else {
                android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_image_load_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Custom Styled Dialog: Save As
    if (showSaveAsDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showSaveAsDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .shadow(20.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.canvas_dialog_save_as_title),
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = saveAsName,
                        onValueChange = { saveAsName = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.canvas_dialog_save_as_placeholder), color = Morandi.subText) },
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
                        ReTextButton(stringResource(R.string.common_cancel), { showSaveAsDialog = false }, textColor = Morandi.subText)
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            stringResource(R.string.common_save),
                            onClick = {
                            val name = saveAsName.trim()
                            showSaveAsDialog = false
                            onClose()
                            if (name.isNotBlank()) {
                                vm.saveProject(name) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_saved_as, name), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                            textColor = Morandi.accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }





    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Dynamic Document Info in a sleek Morandi card
        val sdf = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
        val createdStr = remember(vm.canvasCreatedTime) { sdf.format(Date(vm.canvasCreatedTime)) }
        val hours = vm.elapsedSeconds / 3600
        val mins = (vm.elapsedSeconds % 3600) / 60
        val secs = vm.elapsedSeconds % 60
        val durationStr = if (hours > 0) {
            context.getString(R.string.duration_hours_mins, hours, mins.toString().padStart(2, '0'))
        } else if (mins > 0) {
            context.getString(R.string.duration_mins_secs, mins, secs.toString().padStart(2, '0'))
        } else {
            context.getString(R.string.duration_secs, secs)
        }

        // 1. 画布规格信息卡 (Specs Dashboard Card - Zero Border)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_canvas_tab),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.canvas_group_specs),
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = createdStr,
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(10.dp))

            // 2-Column Specs Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1.1f)) {
                    Text(stringResource(R.string.canvas_info_canvas_size), color = Morandi.subText, fontSize = 11.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${vm.docWidth} × ${vm.docHeight} (${vm.docDpi} PPI)",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Column(modifier = Modifier.weight(0.9f)) {
                    Text(stringResource(R.string.canvas_info_color_mode), color = Morandi.subText, fontSize = 11.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        vm.colorMode,
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1.1f)) {
                    Text(stringResource(R.string.canvas_info_total_drawn), color = Morandi.subText, fontSize = 11.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.canvas_info_strokes_layers, vm.totalStrokes, vm.layerCount),
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Column(modifier = Modifier.weight(0.9f)) {
                    Text(stringResource(R.string.canvas_info_drawing_time), color = Morandi.subText, fontSize = 11.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        durationStr,
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 2. 工程与图层卡片 (Project & Layers Group - Zero Border Tiles)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.canvas_group_project),
                color = Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CanvasActionTile(
                    icon = R.drawable.ic_save,
                    label = stringResource(R.string.common_save),
                    onClick = {
                        onClose()
                        vm.saveProject(vm.docName) {
                            android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_project_saved, vm.docName), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                CanvasActionTile(
                    icon = R.drawable.ic_save_as,
                    label = stringResource(R.string.canvas_action_save_as),
                    onClick = {
                        saveAsName = vm.docName + "_copy"
                        showSaveAsDialog = true
                    },
                    modifier = Modifier.weight(1f),
                )
                CanvasActionTile(
                    icon = R.drawable.ic_image,
                    label = stringResource(R.string.canvas_action_import_image),
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                )
                CanvasActionTile(
                    icon = R.drawable.ic_stamp,
                    label = stringResource(R.string.canvas_action_stamp),
                    onClick = {
                        vm.stampVisibleLayers()
                        android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_stamp_success), android.widget.Toast.LENGTH_SHORT).show()
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 3. 变换与调整卡片 (Transform & Adjust Group - Zero Border Tiles)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.canvas_group_transform),
                color = Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CanvasActionTile(
                    icon = R.drawable.ic_canvas_resize,
                    label = stringResource(R.string.canvas_action_resize),
                    onClick = {
                        onClose()
                        vm.enterCanvasAdjustMode(com.reverie.paint.model.CanvasAdjustMode.CROP_EXPAND)
                    },
                    modifier = Modifier.weight(1f),
                )
                // 画布翻转: 单击 = 视图翻转 (只镜像显示, 零开销, 可反复切),
                // 长按 = 完全翻转 (真的镜像每一层像素, 图层多时较慢), 且会把
                // 该轴的视图翻转复位, 免得看起来"翻了两次像没生效"。
                CanvasActionTile(
                    icon = R.drawable.ic_flip_horizontal,
                    label = stringResource(R.string.canvas_action_flip_h),
                    active = vm.viewFlipX,
                    onClick = {
                        vm.toggleViewFlipHorizontal()
                        onClose()
                    },
                    onLongClick = {
                        vm.flipCanvasHorizontalFull()
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                )
                CanvasActionTile(
                    icon = R.drawable.ic_flip_vertical,
                    label = stringResource(R.string.canvas_action_flip_v),
                    active = vm.viewFlipY,
                    onClick = {
                        vm.toggleViewFlipVertical()
                        onClose()
                    },
                    onLongClick = {
                        vm.flipCanvasVerticalFull()
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                )
                CanvasActionTile(
                    icon = R.drawable.ic_image_adjust,
                    label = stringResource(R.string.canvas_action_filters),
                    onClick = {
                        onClose()
                        onOpenFilters?.invoke(vm.editTargetLayers())
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CanvasActionTile(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 开关类动作的"已开启"态 (如视图翻转), 开启时整体高亮, 一眼能看出当前状态 */
    active: Boolean = false,
    /** 长按回调: 给了就启用长按 (画布翻转用长按 = 完全翻转) */
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    val iconTint = if (active) Morandi.accent else Morandi.icon
    val labelColor = if (active) Morandi.accent else Morandi.text

    Column(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .clip(shape)
            .liquidHighlight(interaction, Color.White, radius = 24.dp)
            .background(
                if (active) Morandi.accent.copy(alpha = 0.16f)
                else Morandi.panelHi.copy(alpha = 0.55f),
            )
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                },
            )
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}


