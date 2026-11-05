/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.AuthorProfile
import com.reverie.paint.model.AuthorProfilePreset
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.optimalTextColor

@Composable
internal fun AuthorSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val profile = vm.authorProfile

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showCreatePresetDialog by remember { mutableStateOf(false) }
    var showManagePresetsDialog by remember { mutableStateOf(false) }

    val quickLicenses = listOf(
        "All rights reserved",
        "CC BY-NC-SA 4.0",
        "CC BY 4.0",
        "CC0 1.0 Universal",
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
                title = stringResource(R.string.author_settings_title),
                subtitle = stringResource(R.string.author_settings_subtitle),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // 顶部名片预览卡片与预设切换
            AuthorProfileCard(
                vm = vm,
                profile = profile,
                onOpenCreatePreset = { showCreatePresetDialog = true },
                onOpenManagePresets = { showManagePresetsDialog = true },
            )

            Spacer(Modifier.height(8.dp))

            // Section 1: 总开关
            SettingCategoryTitle(stringResource(R.string.author_category_enable))
            SettingGroup {
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.AccountCircle,
                    title = stringResource(R.string.author_switch_title),
                    summary = stringResource(R.string.author_switch_summary),
                    checked = profile.enabled,
                    shape = settingGroupShape(0, 1),
                    onCheckedChange = { vm.updateAuthorProfile(profile.copy(enabled = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Section 2: 创作者信息 (内联扁平表单，无占位提示文本)
            SettingCategoryTitle(stringResource(R.string.author_category_info))
            SettingGroup {
                AuthorInlineInputField(
                    label = stringResource(R.string.author_field_full_name),
                    value = profile.name,
                    shape = settingGroupShape(0, 3),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(name = it)) },
                )
                AuthorInlineInputField(
                    label = stringResource(R.string.author_field_nickname),
                    value = profile.nickname,
                    shape = settingGroupShape(1, 3),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(nickname = it)) },
                )
                AuthorInlineInputField(
                    label = stringResource(R.string.author_field_organization),
                    value = profile.organization,
                    shape = settingGroupShape(2, 3),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(organization = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Section 3: 联系与主页
            SettingCategoryTitle(stringResource(R.string.author_category_contact))
            SettingGroup {
                AuthorInlineInputField(
                    label = stringResource(R.string.author_field_email),
                    value = profile.email,
                    keyboardType = KeyboardType.Email,
                    shape = settingGroupShape(0, 2),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(email = it)) },
                )
                AuthorInlineInputField(
                    label = stringResource(R.string.author_field_website),
                    value = profile.website,
                    keyboardType = KeyboardType.Uri,
                    shape = settingGroupShape(1, 2),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(website = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Section 4: 默认版权声明
            SettingCategoryTitle(stringResource(R.string.author_category_copyright))
            SettingGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(settingGroupShape(0, 1))
                        .background(colors.panel)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.author_field_copyright),
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(105.dp),
                        )
                        BasicTextField(
                            value = profile.copyright,
                            onValueChange = { vm.updateAuthorProfile(profile.copy(copyright = it)) },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = colors.text,
                                fontSize = 13.sp,
                            ),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.weight(1f),
                        )
                        if (profile.copyright.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            val clearInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .pressScale(clearInteraction, pressedScale = 0.85f)
                                    .clip(CircleShape)
                                    .background(colors.panelHi)
                                    .clickable(interactionSource = clearInteraction, indication = null) {
                                        vm.updateAuthorProfile(profile.copy(copyright = ""))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.author_clear_fields),
                                    tint = colors.subText,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.author_templates_label),
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickLicenses.forEach { lic ->
                            val chipInteraction = remember { MutableInteractionSource() }
                            val isSelected = profile.copyright.trim().equals(lic, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .pressScale(chipInteraction, pressedScale = 0.94f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) colors.accent else colors.panelHi)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) colors.accent else colors.border.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable(interactionSource = chipInteraction, indication = null) {
                                        vm.updateAuthorProfile(profile.copy(copyright = lic))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = lic,
                                    color = if (isSelected) colors.accent.optimalTextColor() else colors.text,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Section 5: 清空档案操作 (带二次确认弹窗)
            if (profile.isNotEmpty()) {
                val clearInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(clearInteraction, pressedScale = 0.97f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE05555).copy(alpha = 0.10f))
                        .clickable(interactionSource = clearInteraction, indication = null) {
                            showClearConfirmDialog = true
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = Color(0xFFE05555),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.author_clear_fields),
                        color = Color(0xFFE05555),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(60.dp))
        }
    }

    // 二次确认清空对话框
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = colors.panelHi,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = stringResource(R.string.author_clear_confirm_title),
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.author_clear_confirm_msg),
                    color = colors.subText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                ReTextButton(
                    text = stringResource(R.string.clear),
                    primary = true,
                    containerColor = Color(0xFFE05555),
                    contentColor = Color.White,
                    onClick = {
                        vm.updateAuthorProfile(AuthorProfile(enabled = profile.enabled))
                        showClearConfirmDialog = false
                        Toast.makeText(context, R.string.author_cleared_toast, Toast.LENGTH_SHORT).show()
                    },
                )
            },
            dismissButton = {
                ReTextButton(
                    text = stringResource(R.string.cancel),
                    textColor = colors.subText,
                    onClick = { showClearConfirmDialog = false },
                )
            },
        )
    }

    // 新建预设对话框
    if (showCreatePresetDialog) {
        var newPresetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePresetDialog = false },
            containerColor = colors.panelHi,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = stringResource(R.string.author_preset_create_dialog_title),
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.panel)
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = colors.text, fontSize = 13.sp),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                ReTextButton(
                    text = stringResource(R.string.confirm),
                    primary = true,
                    onClick = {
                        vm.createAuthorProfilePreset(newPresetName)
                        showCreatePresetDialog = false
                    },
                )
            },
            dismissButton = {
                ReTextButton(
                    text = stringResource(R.string.cancel),
                    textColor = colors.subText,
                    onClick = { showCreatePresetDialog = false },
                )
            },
        )
    }

    // 预设管理对话框
    if (showManagePresetsDialog) {
        AuthorPresetManageDialog(
            vm = vm,
            onDismiss = { showManagePresetsDialog = false },
            onCreateNew = {
                showManagePresetsDialog = false
                showCreatePresetDialog = true
            },
        )
    }
}

/** 顶部名片预览卡片 */
@Composable
private fun AuthorProfileCard(
    vm: PaintViewModel,
    profile: AuthorProfile,
    onOpenCreatePreset: () -> Unit,
    onOpenManagePresets: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    var showDropdown by remember { mutableStateOf(false) }

    val activePreset = vm.authorProfilePresets.firstOrNull { it.id == vm.activeAuthorProfileId }
    val presetName = activePreset?.name ?: stringResource(R.string.author_preset_default_name)

    val displayName = if (profile.nickname.isNotBlank()) {
        profile.nickname
    } else if (profile.name.isNotBlank()) {
        profile.name
    } else {
        stringResource(R.string.author_card_anonymous)
    }

    val subtitle = if (profile.organization.isNotBlank()) {
        profile.organization
    } else if (profile.email.isNotBlank()) {
        profile.email
    } else {
        stringResource(R.string.author_card_anonymous_desc)
    }

    val initialChar = (profile.nickname.ifBlank { profile.name }).trim().take(1).uppercase()
    val switchToastFormat = stringResource(R.string.author_preset_switched_toast)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panelHi)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 莫兰迪色调头像徽章
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.16f))
                        .border(1.5.dp, colors.accent.copy(alpha = 0.40f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (initialChar.isNotEmpty()) {
                        Text(
                            text = initialChar,
                            color = colors.accent,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_author),
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // 创作者姓名与机构预览
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = colors.subText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 预设选择胶囊
                Box {
                    val pillInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .pressScale(pillInteraction, pressedScale = 0.95f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.panel)
                            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                            .clickable(interactionSource = pillInteraction, indication = null) {
                                showDropdown = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = presetName,
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = showDropdown,
                        onDismissRequest = { showDropdown = false },
                        modifier = Modifier.background(colors.panelHi),
                    ) {
                        vm.authorProfilePresets.forEach { preset ->
                            val isActive = preset.id == vm.activeAuthorProfileId
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = preset.name,
                                        color = if (isActive) colors.accent else colors.text,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                    )
                                },
                                trailingIcon = if (isActive) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else null,
                                onClick = {
                                    showDropdown = false
                                    vm.switchAuthorProfile(preset.id)
                                    Toast.makeText(
                                        context,
                                        String.format(switchToastFormat, preset.name),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }

                        HorizontalDivider(
                            color = colors.border.copy(alpha = 0.5f),
                            thickness = 0.8.dp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.author_preset_create_action),
                                    color = colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            onClick = {
                                showDropdown = false
                                onOpenCreatePreset()
                            },
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.author_preset_manage_action),
                                    color = colors.subText,
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                showDropdown = false
                                onOpenManagePresets()
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 状态标签与版权标识
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isEnabled = profile.enabled
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isEnabled) colors.accent.copy(alpha = 0.12f)
                            else colors.subText.copy(alpha = 0.12f),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = if (isEnabled) stringResource(R.string.author_tag_enabled)
                               else stringResource(R.string.author_tag_disabled),
                        color = if (isEnabled) colors.accent else colors.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (profile.copyright.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.panel)
                            .border(0.8.dp, colors.border, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = profile.copyright,
                            color = colors.subText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 扁平无边框内联输入行组件 (左标签右输入，无提示文本) */
@Composable
private fun AuthorInlineInputField(
    label: String,
    value: String,
    shape: RoundedCornerShape,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val colors = Theme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(105.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.text,
                fontSize = 13.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            val clearInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .pressScale(clearInteraction, pressedScale = 0.85f)
                    .clip(CircleShape)
                    .background(colors.panelHi)
                    .clickable(interactionSource = clearInteraction, indication = null) {
                        onValueChange("")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.author_clear_fields),
                    tint = colors.subText,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** 预设管理弹窗 */
@Composable
private fun AuthorPresetManageDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
) {
    val colors = Theme.current
    var presetToRename by remember { mutableStateOf<AuthorProfilePreset?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var presetToDelete by remember { mutableStateOf<AuthorProfilePreset?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panelHi,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = stringResource(R.string.author_preset_manage_title),
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.authorProfilePresets.forEach { preset ->
                    val isActive = preset.id == vm.activeAuthorProfileId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isActive) colors.accent.copy(alpha = 0.12f) else colors.panel)
                            .border(
                                1.dp,
                                if (isActive) colors.accent.copy(alpha = 0.35f) else colors.border.copy(alpha = 0.4f),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    vm.switchAuthorProfile(preset.id)
                                },
                        ) {
                            Text(
                                text = preset.name,
                                color = if (isActive) colors.accent else colors.text,
                                fontSize = 13.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }

                        // 复制预设
                        IconButton(
                            onClick = { vm.duplicateAuthorProfilePreset(preset.id) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.author_preset_duplicate),
                                tint = colors.subText,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        // 重命名预设
                        IconButton(
                            onClick = {
                                presetToRename = preset
                                renameDraft = preset.name
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.author_preset_rename_dialog_title),
                                tint = colors.subText,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        // 删除预设 (保留至少 1 项)
                        if (vm.authorProfilePresets.size > 1) {
                            IconButton(
                                onClick = { presetToDelete = preset },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = Color(0xFFE05555),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ReTextButton(
                text = stringResource(R.string.common_done),
                primary = true,
                onClick = onDismiss,
            )
        },
        dismissButton = {
            ReTextButton(
                text = stringResource(R.string.author_preset_create_action),
                textColor = colors.accent,
                onClick = onCreateNew,
            )
        },
    )

    // 重命名子对话框
    if (presetToRename != null) {
        val target = presetToRename!!
        AlertDialog(
            onDismissRequest = { presetToRename = null },
            containerColor = colors.panelHi,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = stringResource(R.string.author_preset_rename_dialog_title),
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.panel)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.text, fontSize = 13.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                ReTextButton(
                    text = stringResource(R.string.confirm),
                    primary = true,
                    onClick = {
                        vm.renameAuthorProfilePreset(target.id, renameDraft)
                        presetToRename = null
                    },
                )
            },
            dismissButton = {
                ReTextButton(
                    text = stringResource(R.string.cancel),
                    textColor = colors.subText,
                    onClick = { presetToRename = null },
                )
            },
        )
    }

    // 删除确认子对话框
    if (presetToDelete != null) {
        val target = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            containerColor = colors.panelHi,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = stringResource(R.string.author_preset_delete_confirm_title),
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.author_preset_delete_confirm_msg),
                    color = colors.subText,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                ReTextButton(
                    text = stringResource(R.string.common_delete),
                    primary = true,
                    containerColor = Color(0xFFE05555),
                    contentColor = Color.White,
                    onClick = {
                        vm.deleteAuthorProfilePreset(target.id)
                        presetToDelete = null
                    },
                )
            },
            dismissButton = {
                ReTextButton(
                    text = stringResource(R.string.cancel),
                    textColor = colors.subText,
                    onClick = { presetToDelete = null },
                )
            },
        )
    }
}
