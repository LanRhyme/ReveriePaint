package com.reverie.paint.ui.painting.panels

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
import com.reverie.paint.ui.painting.canvas.CanvasTabPage
import com.reverie.paint.ui.painting.ExportTabPage

enum class SettingsTab { CANVAS, EXPORT, SETTINGS }

/**
 * Settings panel (top-right dropdown menu, multi-page 画世界 Pro style)
 */
@Composable
fun SettingsPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onResetView: () -> Unit = {},
    modifier: Modifier = Modifier,
    opacity: Float = 1.0f,
    hazeState: HazeState? = null,
) {
    var currentTab by remember { mutableStateOf(SettingsTab.CANVAS) }
    val panelShape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .noRippleClickable(onClose),
    ) {
        Column(
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Default)
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = 8.dp)
                .width(280.dp)
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f)),
                                tint = HazeTint(Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f))),
                                blurRadius = 24.dp,
                                noiseFactor = 0.05f
                            )
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .border(1.dp, Morandi.border.copy(alpha = opacity), panelShape)
                .padding(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
        ) {
            // Top tab icons (3 tabs: 画布, 导出, 设置)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TabHeaderItem(
                    icon = R.drawable.ic_canvas_tab,
                    label = "画布",
                    selected = currentTab == SettingsTab.CANVAS,
                    onClick = { currentTab = SettingsTab.CANVAS }
                )
                TabHeaderItem(
                    icon = R.drawable.ic_export_tab,
                    label = "导出",
                    selected = currentTab == SettingsTab.EXPORT,
                    onClick = { currentTab = SettingsTab.EXPORT }
                )
                TabHeaderItem(
                    icon = R.drawable.ic_settings,
                    label = "设置",
                    selected = currentTab == SettingsTab.SETTINGS,
                    onClick = { currentTab = SettingsTab.SETTINGS }
                )
            }
            
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(tween(180, easing = FastOutSlowInEasing))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "SettingsTabTransition"
            ) { tab ->
                when (tab) {
                    SettingsTab.CANVAS -> CanvasTabPage(vm = vm, onClose = onClose)
                    SettingsTab.EXPORT -> ExportTabPage(vm = vm, onClose = onClose)
                    SettingsTab.SETTINGS -> SettingsTabPage(vm = vm, onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun TabHeaderItem(
    icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = Morandi.accent
    val inactiveColor = Morandi.icon
    val textColor = if (selected) activeColor else Morandi.text

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (selected) activeColor else inactiveColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}


