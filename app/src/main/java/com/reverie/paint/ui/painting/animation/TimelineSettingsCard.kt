/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationApplyOnionSkin
import com.reverie.paint.core.animationImportAudio
import com.reverie.paint.core.animationImportImages
import com.reverie.paint.core.animationImportVideo
import com.reverie.paint.core.animationSetFramerate
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSectionTitle
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.painting.layers.CompactColorPickerDialog
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

/** 动画设置浮窗: 独立卡片悬在时间轴面板上方 (不占面板高度, 不挤压轨道区) */
@Composable
internal fun AnimationSettingsCard(
    vm: PaintViewModel,
    hazeState: HazeState?,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val alpha = vm.popupPanelOpacity
    val config = LocalConfiguration.current
    val isPortrait = config.screenWidthDp < config.screenHeightDp || config.screenWidthDp < 600
    val cardWidth = if (isPortrait) (config.screenWidthDp - 44).coerceIn(240, 320).dp else 320.dp
    val maxCardHeight = (config.screenHeightDp - 90).coerceAtLeast(200).dp

    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .shadow(16.dp, shape, spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape)
            .then(
                if (vm.blurBackground && hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(alpha))
                } else {
                    Modifier.background(Morandi.panel.copy(alpha = alpha))
                },
            )
            .glassBorder(shape)
            .width(cardWidth)
            .heightIn(max = maxCardHeight)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.anim_settings),
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            ReIconButton(
                icon = R.drawable.ic_x,
                desc = stringResource(R.string.common_close),
                onTap = onClose,
                size = 30.dp,
                iconSize = 15.dp,
            )
        }
        TimelineSettings(vm = vm, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
internal fun TimelineSettings(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 4.dp),
    ) {
        var showFpsInput by remember { mutableStateOf(false) }
        var pickingOnionColor by remember { mutableStateOf<OnionColorTarget?>(null) }

        ReSectionTitle(text = stringResource(R.string.anim_settings_play), modifier = Modifier.padding(start = 12.dp))

        CompactSettingRow(label = stringResource(R.string.anim_fps)) {
            ReIconButton(
                icon = R.drawable.ic_minus,
                desc = stringResource(R.string.anim_fps_decrease),
                onTap = { vm.animationSetFramerate(vm.anim.framerate - 1) },
                size = 28.dp,
                iconSize = 14.dp,
            )
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { showFpsInput = true }
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${vm.anim.framerate} fps",
                    color = Morandi.text,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            ReIconButton(
                icon = R.drawable.ic_plus,
                desc = stringResource(R.string.anim_fps_increase),
                onTap = { vm.animationSetFramerate(vm.anim.framerate + 1) },
                size = 28.dp,
                iconSize = 14.dp,
            )
        }

        if (showFpsInput) {
            FpsInputDialog(
                initial = vm.anim.framerate,
                onDismiss = { showFpsInput = false },
                onConfirm = {
                    vm.animationSetFramerate(it)
                    showFpsInput = false
                },
            )
        }

        ReSectionTitle(text = stringResource(R.string.anim_onion_skin), modifier = Modifier.padding(start = 12.dp))

        CompactSettingRow(label = stringResource(R.string.anim_onion_show)) {
            ReSwitch(
                checked = vm.anim.onionSkin,
                onChecked = {
                    vm.anim.onionSkin = it
                    vm.animationApplyOnionSkin()
                },
            )
        }

        if (vm.anim.onionSkin) {
            CompactSettingRow(label = stringResource(R.string.anim_onion_keyframes_only)) {
                ReSwitch(
                    checked = vm.anim.onionKeyframesOnly,
                    onChecked = {
                        vm.anim.onionKeyframesOnly = it
                        vm.animationApplyOnionSkin()
                    },
                )
            }

            CompactSettingRow(label = stringResource(R.string.anim_onion_decay)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf(
                        com.reverie.paint.core.OnionDecayMode.LINEAR to stringResource(R.string.anim_onion_decay_linear_short),
                        com.reverie.paint.core.OnionDecayMode.SMOOTH to stringResource(R.string.anim_onion_decay_smooth_short),
                        com.reverie.paint.core.OnionDecayMode.CONSTANT to stringResource(R.string.anim_onion_decay_constant_short),
                    ).forEach { (mode, title) ->
                        val sel = vm.anim.onionDecayMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable {
                                    vm.anim.onionDecayMode = mode
                                    vm.animationApplyOnionSkin()
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                color = if (sel) Morandi.accent else Morandi.subText,
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            CompactSettingRow(label = stringResource(R.string.anim_onion_frames_prev_next)) {
                OnionFrameStepper(
                    prefix = stringResource(R.string.anim_onion_prefix_prev),
                    value = vm.anim.onionPrev,
                    onChange = {
                        vm.anim.onionPrev = it
                        vm.animationApplyOnionSkin()
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                OnionFrameStepper(
                    prefix = stringResource(R.string.anim_onion_prefix_next),
                    value = vm.anim.onionNext,
                    onChange = {
                        vm.anim.onionNext = it
                        vm.animationApplyOnionSkin()
                    },
                )
            }

            CompactSettingRow(label = stringResource(R.string.layer_opacity)) {
                val pct = (vm.anim.onionOpacity * 100 + 127) / 255
                ReSlider(
                    value = vm.anim.onionOpacity / 255f,
                    onValue = { vm.anim.onionOpacity = (it * 255f).roundToInt().coerceIn(0, 255) },
                    onRelease = { vm.animationApplyOnionSkin() },
                    modifier = Modifier.width(150.dp),
                    height = 18,
                )
                Text(
                    text = "$pct%",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }

            CompactSettingRow(label = stringResource(R.string.anim_onion_tint)) {
                ReSlider(
                    value = vm.anim.onionTint / 100f,
                    onValue = { vm.anim.onionTint = (it * 100f).roundToInt().coerceIn(0, 100) },
                    onRelease = { vm.animationApplyOnionSkin() },
                    modifier = Modifier.width(150.dp),
                    height = 18,
                )
                Text(
                    text = "${vm.anim.onionTint}",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }

            CompactSettingRow(label = stringResource(R.string.anim_onion_backward_forward)) {
                OnionColorSwatch(
                    color = Color(vm.anim.onionColorBackward),
                    label = stringResource(R.string.anim_onion_backward_short),
                    onPick = { pickingOnionColor = OnionColorTarget.Backward },
                )
                Spacer(modifier = Modifier.width(12.dp))
                OnionColorSwatch(
                    color = Color(vm.anim.onionColorForward),
                    label = stringResource(R.string.anim_onion_forward_short),
                    onPick = { pickingOnionColor = OnionColorTarget.Forward },
                )
            }
        }

        pickingOnionColor?.let { target ->
            val isBackward = target == OnionColorTarget.Backward
            CompactColorPickerDialog(
                title = if (isBackward) stringResource(R.string.anim_onion_backward_tint_title) else stringResource(R.string.anim_onion_forward_tint_title),
                initialColor = Color(
                    if (isBackward) vm.anim.onionColorBackward else vm.anim.onionColorForward,
                ),
                onColorSelected = { c ->
                    val argb = c.toArgb()
                    if (isBackward) {
                        vm.anim.onionColorBackward = argb
                    } else {
                        vm.anim.onionColorForward = argb
                    }
                    vm.animationApplyOnionSkin()
                    pickingOnionColor = null
                },
                onDismiss = { pickingOnionColor = null },
            )
        }

        ReSectionTitle(text = stringResource(R.string.anim_settings_display), modifier = Modifier.padding(start = 12.dp))

        CompactSettingRow(label = stringResource(R.string.anim_settings_thumbnails)) {
            ReSwitch(
                checked = vm.anim.showThumbnails,
                onChecked = { vm.anim.showThumbnails = it },
            )
        }

        if (vm.anim.audioAssets.isNotEmpty()) {
            CompactSettingRow(label = stringResource(R.string.anim_settings_audio)) {
                Text(
                    text = stringResource(R.string.anim_audio_with_playback, vm.anim.audioAssets.size),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
            }
        }

        ReSectionTitle(text = stringResource(R.string.anim_settings_import), modifier = Modifier.padding(start = 12.dp))
        TimelineImportRow(vm = vm)

        ReSectionTitle(text = stringResource(R.string.common_export), modifier = Modifier.padding(start = 12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReChip(
                text = stringResource(R.string.anim_export_chip),
                onTap = {
                    vm.targetSettingsTab = "EXPORT"
                    vm.targetExportAnimation = true
                    vm.settingsPanelOpen = true
                },
            )
        }
    }
}

@Composable
internal fun CompactSettingRow(
    label: String,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Morandi.text, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
internal fun FpsInputDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.anim_fps), color = Morandi.text, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw.filter { it.isDigit() }.take(3)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Morandi.border,
                            focusedContainerColor = Morandi.panel,
                            unfocusedContainerColor = Morandi.panel,
                            cursorColor = Morandi.accent,
                            focusedTextColor = Morandi.text,
                            unfocusedTextColor = Morandi.text,
                        ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.anim_fps_dialog_hint),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_confirm),
                onClick = {
                    val v = text.toIntOrNull() ?: initial
                    onConfirm(v.coerceIn(1, 240))
                },
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

@Composable
internal fun OnionFrameStepper(
    prefix: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = prefix, color = Morandi.subText, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(2.dp))
        ReIconButton(
            icon = R.drawable.ic_minus,
            desc = stringResource(R.string.common_decrease),
            onTap = { onChange((value - 1).coerceIn(0, 10)) },
            size = 26.dp,
            iconSize = 13.dp,
        )
        Text(
            text = "$value",
            color = Morandi.text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(18.dp),
        )
        ReIconButton(
            icon = R.drawable.ic_plus,
            desc = stringResource(R.string.common_increase),
            onTap = { onChange((value + 1).coerceIn(0, 10)) },
            size = 26.dp,
            iconSize = 13.dp,
        )
    }
}

internal enum class OnionColorTarget { Backward, Forward }

@Composable
internal fun OnionColorSwatch(
    color: Color,
    label: String,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable { onPick() }
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
                .glassBorder(RoundedCornerShape(5.dp)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = Morandi.subText, fontSize = 12.sp)
    }
}

@Composable
internal fun TimelineImportRow(vm: PaintViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var importing by remember { mutableStateOf(false) }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            vm.animationImportImages(uris) {
                importing = false
            }
        }
    }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            importing = true
            vm.animationImportVideo(uri, vm.anim.framerate) {
                importing = false
            }
        }
    }
    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val ext = context.contentResolver.getType(uri)
                ?.substringAfter('/')?.takeIf { it.length <= 5 } ?: "bin"
            vm.animationImportAudio(uri, "audio_${System.currentTimeMillis()}.$ext")
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReChip(
            text = stringResource(R.string.anim_import_images),
            onTap = { if (!importing) imagePicker.launch(arrayOf("image/*")) },
        )
        Spacer(modifier = Modifier.width(8.dp))
        ReChip(
            text = stringResource(R.string.anim_import_video),
            onTap = { if (!importing) videoPicker.launch("video/*") },
        )
        Spacer(modifier = Modifier.width(8.dp))
        ReChip(
            text = stringResource(R.string.anim_import_audio),
            onTap = { if (!importing) audioPicker.launch("audio/*") },
        )
        if (importing) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.anim_importing),
                color = Morandi.subText,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
