package com.reverie.paint.ui.painting

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import com.reverie.paint.ui.components.ReSlider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.reverie.paint.ui.components.noRippleClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.reverie.paint.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.min

/**
 * Painting page: full-bleed canvas with touch painting + gestures,
 * overlaid by the top bar, left tool rail and popup panels.
 *
 * 画世界 Pro style: left tool rail with vertical sliders, top operation
 * bar, dark grid workspace with a centered white canvas.
 */
@Composable
internal fun PickerLayerSourceBar(
    tool: Tool,
    vm: PaintViewModel,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = tool == Tool.PICKER,
        modifier = modifier.padding(bottom = 24.dp),
        enter =
            androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(200)
            ) +
                androidx.compose.animation.slideInVertically(
                    androidx.compose.animation.core.tween(200),
                    initialOffsetY = { it / 2 }
                ),
        exit =
            androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(200)
            ) +
                androidx.compose.animation.slideOutVertically(
                    androidx.compose.animation.core.tween(200),
                    targetOffsetY = { it / 2 }
                )
    ) {
        ToolFloatPanel(vm = vm, hazeState = hazeState) {
            ToolFloatSegmented(
                options = listOf(0 to "当前图层", 1 to "全部图层"),
                selected = vm.pickerSampleLayers,
                onSelect = { vm.updatePickerSampleLayers(it) },
            )
        }
    }
}

@Composable
internal fun SelectionMenuItem(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable {
                    onClick()
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFB05552) else Morandi.text,
            fontSize = 13.sp,
        )
    }
}

// Floating selection panel docked at the bottom of the screen, in the style
// of Krita's tool options docker. Each selection tool exposes its own
// property set: the magic wand / similar-color tools get a tolerance slider
// plus the common feather/expand/contract/smooth modifiers, while simple
// lasso-style tools only get the common modifiers (like Krita, which has no
// tolerance on the lasso). No close button: switching tools hides it.
@Composable
internal fun SelectionFloatPanel(
    modifier: Modifier = Modifier,
    vm: PaintViewModel,
    tool: Tool,
    propsOpen: Boolean,
    onToggleProps: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    hazeState: HazeState? = null,
) {
    ToolFloatPanel(modifier = modifier, vm = vm, hazeState = hazeState) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Row 1: Top Segmented Mode Selector (新建, 增加, 减去, 相交)
            ToolFloatSegmented(
                options = listOf(
                    0 to "新建",
                    1 to "增加",
                    2 to "减去",
                    3 to "相交",
                ),
                selected = vm.selectionMode,
                onSelect = { vm.updateSelectionMode(it) },
            )

            // Row 2: Magic Wand / Similar Tolerance Slider + Reference Layers
            if (tool == Tool.MAGICWAND || tool == Tool.SELECT_SIMILAR) {
                Row(
                    modifier = Modifier.width(260.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ToolFloatSlider(
                            label = "容差",
                            valueText = "${vm.selectionTolerance}",
                            range = 0f..255f,
                            value = vm.selectionTolerance.toFloat(),
                            onValue = { vm.updateSelectionTolerance(it.toInt()) },
                        )
                    }
                    ToolFloatSegmented(
                        options = listOf(0 to "当前", 1 to "全部"),
                        selected = vm.selectionSampleLayers,
                        onSelect = { vm.updateSelectionSampleLayers(it) },
                    )
                }
            }

            // Expandable Modifiers Drawer (羽化, 扩展, 收缩, 平滑)
            androidx.compose.animation.AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.width(220.dp).padding(vertical = 4.dp),
                ) {
                    ToolFloatSlider(label = "羽化", valueText = "8px", range = 0f..32f, value = 8f, onValue = { vm.featherSelection(it.toInt()) })
                    ToolFloatSlider(label = "扩展", valueText = "16px", range = 0f..64f, value = 16f, onValue = { vm.expandSelection(it.toInt()) })
                    ToolFloatSlider(label = "收缩", valueText = "8px", range = 0f..64f, value = 8f, onValue = { vm.contractSelection(it.toInt()) })
                    ToolFloatSlider(label = "平滑", valueText = "4px", range = 1f..16f, value = 4f, onValue = { vm.smoothSelection(it.toInt()) })
                }
            }

            // Row 3: Action Buttons (全选, 反选, 清除, 属性)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionActionItem(
                    iconRes = R.drawable.ic_layers,
                    label = "全选",
                    onClick = { vm.selectAllAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_refresh,
                    label = "反选",
                    onClick = { vm.invertSelectionAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_trash,
                    label = "清除",
                    danger = true,
                    onClick = { vm.clearSelectionAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) "收起" else "属性",
                    active = propsOpen,
                    onClick = { onToggleProps() },
                )
            }
        }
    }
}

@Composable
internal fun SelectionActionItem(
    iconRes: Int,
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.90f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "btn_scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    primary -> Morandi.accent
                    danger -> Color(0x33C45656)
                    active -> Morandi.accent.copy(alpha = 0.25f)
                    else -> Color(0xFF262A33).copy(alpha = 0.7f)
                }
            )
            .border(
                1.dp,
                when {
                    primary -> Morandi.accent
                    danger -> Color(0x66C45656)
                    active -> Morandi.accent
                    else -> Color(0xFF383D48)
                },
                RoundedCornerShape(10.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(id = iconRes),
            contentDescription = label,
            tint = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFF28B82)
                active -> Morandi.accent
                else -> Morandi.text
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (primary || active) FontWeight.Bold else FontWeight.Normal,
            color = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFF28B82)
                active -> Morandi.accent
                else -> Morandi.subText
            },
        )
    }
}


/** Catmull-Rom spline through the anchor points - Krita's path tool draws
 * Bézier curves through the clicked anchors; this produces an equivalent
 * smooth curve used to commit a path selection */
