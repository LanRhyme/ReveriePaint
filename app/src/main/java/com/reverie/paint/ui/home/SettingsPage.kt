/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

enum class SettingsSubPage {
    MAIN,
    GENERAL,
    THEME,
    STYLUS,
    ABOUT,
}

@Composable
fun SettingsPageContent(
    vm: PaintViewModel,
    onExit: () -> Unit = {},
) {
    var subPage by remember {
        mutableStateOf(
            if (vm.settingsInitialSubPage == "STYLUS") SettingsSubPage.STYLUS
            else if (vm.settingsInitialSubPage == "GENERAL") SettingsSubPage.GENERAL
            else SettingsSubPage.MAIN,
        )
    }

    androidx.compose.runtime.LaunchedEffect(vm.settingsInitialSubPage) {
        if (vm.settingsInitialSubPage == "GENERAL") {
            subPage = SettingsSubPage.GENERAL
            vm.settingsInitialSubPage = "MAIN"
        } else if (vm.settingsInitialSubPage == "STYLUS") {
            subPage = SettingsSubPage.STYLUS
            vm.settingsInitialSubPage = "MAIN"
        } else if (vm.settingsInitialSubPage == "THEME") {
            subPage = SettingsSubPage.THEME
            vm.settingsInitialSubPage = "MAIN"
        } else if (vm.settingsInitialSubPage == "ABOUT") {
            subPage = SettingsSubPage.ABOUT
            vm.settingsInitialSubPage = "MAIN"
        }
    }

    androidx.activity.compose.BackHandler(enabled = subPage != SettingsSubPage.MAIN) {
        subPage = SettingsSubPage.MAIN
    }

    // 主页内嵌时无退出入口；绘画页覆盖层通过 onExit 返回画布
    androidx.activity.compose.BackHandler(enabled = subPage == SettingsSubPage.MAIN) {
        onExit()
    }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            if (targetState != SettingsSubPage.MAIN) {
                (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { it } + fadeOut(tween(150)))
            }
        },
        label = "SettingsSubPageTransition",
    ) { page ->
        when (page) {
            SettingsSubPage.MAIN -> {
                SettingsMainPage(
                    onNavigate = { subPage = it },
                )
            }

            SettingsSubPage.GENERAL -> {
                GeneralSettingsSubPage(
                    vm = vm,
                    onBack = { subPage = SettingsSubPage.MAIN },
                )
            }

            SettingsSubPage.THEME -> {
                ThemeSettingsSubPage(
                    vm = vm,
                    onBack = { subPage = SettingsSubPage.MAIN },
                )
            }

            SettingsSubPage.STYLUS -> {
                StylusSettingsSubPage(
                    vm = vm,
                    onBack = { subPage = SettingsSubPage.MAIN },
                )
            }

            SettingsSubPage.ABOUT -> {
                AboutSettingsSubPage(
                    onBack = { subPage = SettingsSubPage.MAIN },
                )
            }
        }
    }
}

@Composable
private fun SettingsMainPage(onNavigate: (SettingsSubPage) -> Unit) {
    val colors = Theme.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "设置",
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        // Native Android settings row: 通用设置
        SettingNavRow(
            iconRes = R.drawable.ic_settings,
            title = "通用设置",
            summary = "自动保存时间间隔、提示与撤销历史上限",
            onClick = { onNavigate(SettingsSubPage.GENERAL) },
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(8.dp))

        // Native Android settings row: 主题设置
        SettingNavRow(
            iconRes = R.drawable.ic_palette,
            title = "主题设置",
            summary = "主色调、面板透明度与全屏沉浸模式",
            onClick = { onNavigate(SettingsSubPage.THEME) },
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(8.dp))

        // Native Android settings row: 手写笔设置
        SettingNavRow(
            iconRes = R.drawable.ic_pencil,
            title = "手写笔设置",
            summary = "笔模式、光标显示、驻停成形与全局压力曲线",
            onClick = { onNavigate(SettingsSubPage.STYLUS) },
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.3f)))
        Spacer(Modifier.height(8.dp))

        // Native Android settings row: 关于应用
        SettingNavRow(
            iconRes = R.drawable.ic_info_circle,
            title = "关于应用",
            summary = "版本、作者与系统架构信息",
            onClick = { onNavigate(SettingsSubPage.ABOUT) },
        )

        Spacer(Modifier.height(100.dp))
    }
}
