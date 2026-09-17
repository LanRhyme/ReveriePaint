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
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
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
import com.reverie.paint.ui.painting.panels.SettingInfoRow

@Composable
internal fun CanvasTabPage(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onOpenFilters: ((List<Int>) -> Unit)? = null,
) {
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var saveAsName by remember { mutableStateOf(vm.docName) }
    var showCanvasResizeDialog by remember { mutableStateOf(false) }

    var resizeW by remember { mutableStateOf(vm.docWidth.toString()) }
    var resizeH by remember { mutableStateOf(vm.docHeight.toString()) }
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
                    .glassBorder(RoundedCornerShape(16.dp))
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

    // Custom Styled Dialog: Resize Canvas
    if (showCanvasResizeDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showCanvasResizeDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .shadow(20.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.canvas_dialog_resize_title),
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.canvas_dialog_resize_desc),
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
                            label = { Text(stringResource(R.string.canvas_dialog_resize_width), color = Morandi.subText, fontSize = 12.sp) },
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
                            label = { Text(stringResource(R.string.canvas_dialog_resize_height), color = Morandi.subText, fontSize = 12.sp) },
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
                        ReTextButton(stringResource(R.string.common_cancel), { showCanvasResizeDialog = false }, textColor = Morandi.subText)
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            stringResource(R.string.common_confirm),
                            onClick = {
                            val targetW = resizeW.toIntOrNull() ?: vm.docWidth
                            val targetH = resizeH.toIntOrNull() ?: vm.docHeight
                            vm.cropCanvas(0, 0, targetW, targetH)
                            android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_resized, targetW, targetH), android.widget.Toast.LENGTH_SHORT).show()
                            showCanvasResizeDialog = false
                            onClose()
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
            .verticalScroll(rememberScrollState())
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SettingInfoRow(stringResource(R.string.canvas_info_created_time), createdStr)
            SettingInfoRow(stringResource(R.string.canvas_info_canvas_size), "${vm.docWidth}×${vm.docHeight} - 300ppi")
            SettingInfoRow(stringResource(R.string.canvas_info_total_drawn), stringResource(R.string.canvas_info_strokes_layers, vm.totalStrokes, vm.layerCount))
            SettingInfoRow(stringResource(R.string.canvas_info_drawing_time), durationStr)
            SettingInfoRow(stringResource(R.string.canvas_info_color_mode), vm.colorMode)
        }
        
        Spacer(Modifier.height(12.dp))

        // Action Grid (Equal 4-column modern card buttons, 2 rows of 4)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReMenuItem(R.drawable.ic_save, stringResource(R.string.common_save), {
                onClose()
                vm.saveProject(vm.docName) {
                    android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_project_saved, vm.docName), android.widget.Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_save_as, stringResource(R.string.canvas_action_save_as), {
                saveAsName = vm.docName + "_copy"
                showSaveAsDialog = true
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_canvas_resize, stringResource(R.string.canvas_action_resize), {
                resizeW = vm.docWidth.toString()
                resizeH = vm.docHeight.toString()
                showCanvasResizeDialog = true
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_image, stringResource(R.string.canvas_action_import_image), {
                imagePickerLauncher.launch("image/*")
            }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReMenuItem(R.drawable.ic_image_adjust, stringResource(R.string.canvas_action_filters), {
                onClose()
                onOpenFilters?.invoke(vm.editTargetLayers())
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_stamp, stringResource(R.string.canvas_action_stamp), {
                vm.stampVisibleLayers()
                android.widget.Toast.makeText(context, context.getString(R.string.canvas_toast_stamp_success), android.widget.Toast.LENGTH_SHORT).show()
                onClose()
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_flip_horizontal, stringResource(R.string.canvas_action_flip_h), {
                vm.flipCanvasHorizontal()
                onClose()
            }, modifier = Modifier.weight(1f))
            ReMenuItem(R.drawable.ic_flip_vertical, stringResource(R.string.canvas_action_flip_v), {
                vm.flipCanvasVertical()
                onClose()
            }, modifier = Modifier.weight(1f))
        }
    }
}


