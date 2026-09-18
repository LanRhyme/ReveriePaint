/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.AuthorProfile
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun AuthorSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit,
) {
    val colors = Theme.current
    val profile = vm.authorProfile

    val quickLicenses = listOf(
        "All rights reserved",
        "CC BY-NC-SA 4.0",
        "CC BY 4.0",
        "CC0 1.0 Universal",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
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

            // Section 2: 创作者信息
            SettingCategoryTitle(stringResource(R.string.author_category_info))
            SettingGroup {
                AuthorInputFieldItem(
                    label = stringResource(R.string.author_field_full_name),
                    value = profile.name,
                    placeholder = stringResource(R.string.author_field_full_name_placeholder),
                    shape = settingGroupShape(0, 3),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(name = it)) },
                )
                AuthorInputFieldItem(
                    label = stringResource(R.string.author_field_nickname),
                    value = profile.nickname,
                    placeholder = stringResource(R.string.author_field_nickname_placeholder),
                    shape = settingGroupShape(1, 3),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(nickname = it)) },
                )
                AuthorInputFieldItem(
                    label = stringResource(R.string.author_field_organization),
                    value = profile.organization,
                    placeholder = stringResource(R.string.author_field_organization_placeholder),
                    shape = settingGroupShape(2, 3),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(organization = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Section 3: 联系与主页
            SettingCategoryTitle(stringResource(R.string.author_category_contact))
            SettingGroup {
                AuthorInputFieldItem(
                    label = stringResource(R.string.author_field_email),
                    value = profile.email,
                    placeholder = stringResource(R.string.author_field_email_placeholder),
                    keyboardType = KeyboardType.Email,
                    shape = settingGroupShape(0, 2),
                    onValueChange = { vm.updateAuthorProfile(profile.copy(email = it)) },
                )
                AuthorInputFieldItem(
                    label = stringResource(R.string.author_field_website),
                    value = profile.website,
                    placeholder = stringResource(R.string.author_field_website_placeholder),
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
                    Text(
                        text = stringResource(R.string.author_field_copyright),
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = profile.copyright,
                        onValueChange = { vm.updateAuthorProfile(profile.copy(copyright = it)) },
                        placeholder = { Text(stringResource(R.string.author_field_copyright_placeholder), color = colors.subText.copy(alpha = 0.5f), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor = colors.accent,
                        ),
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.author_templates_label),
                        color = colors.subText,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        quickLicenses.forEach { lic ->
                            val chipInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .pressScale(chipInteraction, pressedScale = 0.94f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.panelHi)
                                    .clickable(interactionSource = chipInteraction, indication = null) {
                                        vm.updateAuthorProfile(profile.copy(copyright = lic))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(lic, color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Section 5: 清空档案操作
            if (profile.isNotEmpty()) {
                val clearInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(clearInteraction, pressedScale = 0.97f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE05555).copy(alpha = 0.10f))
                        .clickable(interactionSource = clearInteraction, indication = null) {
                            vm.updateAuthorProfile(AuthorProfile(enabled = profile.enabled))
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
}

@Composable
private fun AuthorInputFieldItem(
    label: String,
    value: String,
    placeholder: String,
    shape: RoundedCornerShape,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val colors = Theme.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panel)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = colors.subText.copy(alpha = 0.5f), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Next,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.accent,
            ),
        )
    }
}
