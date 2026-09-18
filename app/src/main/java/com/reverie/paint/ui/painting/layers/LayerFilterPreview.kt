/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.reverie.paint.model.AdjustmentConfigCodec
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateListOf
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
import kotlin.math.roundToInt


/**
 * 过滤器调整页 UI 状态
 *
 * 原为 FilterAdjustPage 内的 remember 局部变量;提取为类后由
 * `remember { FilterAdjustState() }` 单次创建,语义与原先完全一致
 */
internal class FilterAdjustState {
    var isPreview by mutableStateOf(true)

    // Curves state: channel -> list of control points
    val curveChannels = mutableStateMapOf<Int, MutableList<Offset>>(
        0 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f)), // Master RGB
        1 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f)), // Red
        2 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f)), // Green
        3 to mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f))  // Blue
    )
    var activeCurveChannel by mutableIntStateOf(0)

    // Custom Gradient Map state
    val customGradStops = mutableStateListOf(
        CustomGradStop(1L, 0.0f, Color(0xFF2C0B38)),
        CustomGradStop(2L, 0.35f, Color(0xFFB82E55)),
        CustomGradStop(3L, 0.7f, Color(0xFFE88A35)),
        CustomGradStop(4L, 1.0f, Color(0xFFFFF6A5))
    )
    var reverseGradient by mutableStateOf(false)

    // Standard Sliders
    var hue by mutableFloatStateOf(0f)
    var sat by mutableFloatStateOf(1f)
    var bright by mutableFloatStateOf(1f)
    var contrast by mutableFloatStateOf(1f)

    var cr by mutableFloatStateOf(0f)
    var mg by mutableFloatStateOf(0f)
    var yb by mutableFloatStateOf(0f)

    var blurRadius by mutableFloatStateOf(8f)
    var motionAngle by mutableFloatStateOf(0f)
    var motionDist by mutableFloatStateOf(12f)
    var sharpenAmt by mutableFloatStateOf(1.0f)
    var mosaicSize by mutableFloatStateOf(10f)
    var noiseAmt by mutableFloatStateOf(20f)
    var glitchOffset by mutableFloatStateOf(8f)

    var levelBlack by mutableFloatStateOf(0f)
    var levelWhite by mutableFloatStateOf(255f)
    var levelGamma by mutableFloatStateOf(1.0f)

    var tempVal by mutableFloatStateOf(0f)
    var tintVal by mutableFloatStateOf(0f)
    var thresholdVal by mutableFloatStateOf(128f)
    var posterizeLevels by mutableFloatStateOf(4f)

    var bloomThresh by mutableFloatStateOf(40f)
    var bloomRadius by mutableFloatStateOf(16f)
    var bloomIntensity by mutableFloatStateOf(1.5f)

    var shadowAngle by mutableFloatStateOf(45f)
    var shadowDist by mutableFloatStateOf(12f)
    var shadowRadius by mutableFloatStateOf(10f)
    var shadowOpacity by mutableFloatStateOf(0.6f)

    var oilRadius by mutableFloatStateOf(3f)
    var radialBlurAmt by mutableFloatStateOf(15f)
    var halftoneDotSize by mutableFloatStateOf(10f)
    var exposureVal by mutableFloatStateOf(0f)
    var exposureGamma by mutableFloatStateOf(1.0f)
    var edgeGlowStrength by mutableFloatStateOf(2.5f)
    var edgeGlowRadius by mutableFloatStateOf(8f)
    var edgeGlowHue by mutableIntStateOf(0)
    var defocusRadius by mutableFloatStateOf(8f)
    var lumOpacityInvert by mutableStateOf(false)

    // New filters state
    var shadowBoost by mutableFloatStateOf(30f)
    var highlightReduce by mutableFloatStateOf(30f)
    var vibranceAmt by mutableFloatStateOf(40f)
    var colorToAlphaTarget by mutableIntStateOf(0xFFFFFF)
    var colorToAlphaTol by mutableFloatStateOf(20f)
    var colorToAlphaSmooth by mutableFloatStateOf(15f)
    var rippleAmp by mutableFloatStateOf(10f)
    var rippleFreq by mutableFloatStateOf(12f)
    var twirlAngle by mutableFloatStateOf(90f)
    var twirlRadius by mutableFloatStateOf(150f)
    var surfaceBlurRadius by mutableFloatStateOf(6f)
    var surfaceBlurThresh by mutableFloatStateOf(25f)
    var scanlineSpacing by mutableFloatStateOf(4f)
    var scanlineIntensity by mutableFloatStateOf(40f)

    // Enhanced filter states
    var invertAmt by mutableFloatStateOf(100f)
    var lineartThresh by mutableFloatStateOf(240f)
    var lineartWhiteLine by mutableStateOf(false)
    var sobelStrength by mutableFloatStateOf(2.0f)
    var sobelMode by mutableIntStateOf(0)
    var embossDepth by mutableFloatStateOf(2.0f)
    var embossAngle by mutableFloatStateOf(45f)
    var embossPreserveColor by mutableStateOf(true)
    var desaturateAmt by mutableFloatStateOf(100f)

    fun reset() {
        curveChannels.forEach { (_, list) ->
            list.clear()
            list.addAll(listOf(Offset(0f, 0f), Offset(255f, 255f)))
        }
        customGradStops.clear()
        customGradStops.addAll(
            listOf(
                CustomGradStop(1L, 0.0f, Color(0xFF2C0B38)),
                CustomGradStop(2L, 0.35f, Color(0xFFB82E55)),
                CustomGradStop(3L, 0.7f, Color(0xFFE88A35)),
                CustomGradStop(4L, 1.0f, Color(0xFFFFF6A5))
            )
        )
        reverseGradient = false
        hue = 0f; sat = 1f; bright = 1f; contrast = 1f
        cr = 0f; mg = 0f; yb = 0f
        blurRadius = 8f; motionAngle = 0f; motionDist = 12f
        sharpenAmt = 1.0f; mosaicSize = 10f; noiseAmt = 20f; glitchOffset = 8f
        levelBlack = 0f; levelWhite = 255f; levelGamma = 1.0f
        tempVal = 0f; tintVal = 0f; thresholdVal = 128f; posterizeLevels = 4f
        bloomThresh = 40f; bloomRadius = 16f; bloomIntensity = 1.5f
        shadowAngle = 45f; shadowDist = 12f; shadowRadius = 10f; shadowOpacity = 0.6f
        oilRadius = 3f; radialBlurAmt = 15f; halftoneDotSize = 10f; exposureVal = 0f; exposureGamma = 1.0f
        edgeGlowStrength = 2.5f; edgeGlowRadius = 8f; edgeGlowHue = 0; defocusRadius = 8f; lumOpacityInvert = false
        shadowBoost = 30f; highlightReduce = 30f; vibranceAmt = 40f
        colorToAlphaTarget = 0xFFFFFF; colorToAlphaTol = 20f; colorToAlphaSmooth = 15f
        rippleAmp = 10f; rippleFreq = 12f; twirlAngle = 90f; twirlRadius = 150f
        surfaceBlurRadius = 6f; surfaceBlurThresh = 25f
        scanlineSpacing = 4f; scanlineIntensity = 40f
        invertAmt = 100f; lineartThresh = 240f; lineartWhiteLine = false
        sobelStrength = 2.0f; sobelMode = 0; embossDepth = 2.0f; embossAngle = 45f; embossPreserveColor = true
        desaturateAmt = 100f
    }
}


/**
 * 过滤器参数纯表:把 FilterAdjustState 的 UI 状态翻译为 (type, p1..p4)。
 * 供像素预览分派与"新建调整图层时的初始参数"两处共用 —— 保证创建即所见即所得
 * (此前创建用全零参数导致 HSBC sat=0 全图变灰, 真机已验证的根因)。
 * 返回 null 表示 LUT 型 (13 曲线 / 30 渐变映射), 由调用方的专用路径处理。
 */
internal data class AdjustParams(val type: Int, val p1: Double, val p2: Double, val p3: Double, val p4: Double)

internal fun adjustParamsOf(st: FilterAdjustState, filterId: Int): AdjustParams? {
    val p = when (filterId) {
            0 -> AdjustParams(0, st.hue.toDouble(), st.sat.toDouble(), st.bright.toDouble(), st.contrast.toDouble())
            1 -> AdjustParams(1, st.cr.toDouble(), st.mg.toDouble(), st.yb.toDouble(), 0.0)
            2 -> AdjustParams(2, st.blurRadius.toDouble(), 0.0, 0.0, 0.0)
            3 -> AdjustParams(3, st.motionAngle.toDouble(), st.motionDist.toDouble(), 0.0, 0.0)
            4 -> AdjustParams(4, st.sharpenAmt.toDouble(), 0.0, 0.0, 0.0)
            5 -> AdjustParams(5, st.mosaicSize.toDouble(), 0.0, 0.0, 0.0)
            6 -> AdjustParams(6, st.invertAmt.toDouble(), 0.0, 0.0, 0.0)
            7 -> AdjustParams(7, st.lineartThresh.toDouble(), if (st.lineartWhiteLine) 1.0 else 0.0, 0.0, 0.0)
            8 -> AdjustParams(8, st.sobelStrength.toDouble(), st.sobelMode.toDouble(), 0.0, 0.0)
            9 -> AdjustParams(9, st.embossDepth.toDouble(), st.embossAngle.toDouble(), if (st.embossPreserveColor) 1.0 else 0.0, 0.0)
            10 -> AdjustParams(10, st.noiseAmt.toDouble(), 0.0, 0.0, 0.0)
            11 -> AdjustParams(11, st.glitchOffset.toDouble(), 0.0, 0.0, 0.0)
            12 -> AdjustParams(12, st.desaturateAmt.toDouble(), 0.0, 0.0, 0.0)
            14 -> AdjustParams(14, st.levelBlack.toDouble(), st.levelWhite.toDouble(), st.levelGamma.toDouble(), 0.0)
            15 -> AdjustParams(15, st.tempVal.toDouble(), st.tintVal.toDouble(), 0.0, 0.0)
            16 -> AdjustParams(16, st.thresholdVal.toDouble(), 0.0, 0.0, 0.0)
            17 -> AdjustParams(17, st.posterizeLevels.toDouble(), 0.0, 0.0, 0.0)
            18 -> AdjustParams(18, st.bloomThresh.toDouble(), st.bloomRadius.toDouble(), st.bloomIntensity.toDouble(), 0.0)
            19 -> AdjustParams(19, st.shadowAngle.toDouble(), st.shadowDist.toDouble(), st.shadowRadius.toDouble(), st.shadowOpacity.toDouble())
            20 -> AdjustParams(20, if (st.lumOpacityInvert) 1.0 else 0.0, 0.0, 0.0, 0.0)
            21 -> AdjustParams(21, st.oilRadius.toDouble(), 0.0, 0.0, 0.0)
            22 -> AdjustParams(22, st.radialBlurAmt.toDouble(), 0.5, 0.5, 0.0)
            23 -> AdjustParams(23, st.halftoneDotSize.toDouble(), 0.0, 0.0, 0.0)
            24 -> AdjustParams(24, st.exposureVal.toDouble(), st.exposureGamma.toDouble(), 0.0, 0.0)
            25 -> AdjustParams(25, st.edgeGlowStrength.toDouble(), st.edgeGlowRadius.toDouble(), st.edgeGlowHue.toDouble(), 0.0)
            26 -> AdjustParams(26, st.defocusRadius.toDouble(), 0.0, 0.0, 0.0)
            27 -> AdjustParams(27, st.shadowBoost.toDouble(), st.highlightReduce.toDouble(), 0.0, 0.0)
            28 -> AdjustParams(28, st.vibranceAmt.toDouble(), 0.0, 0.0, 0.0)
            29 -> AdjustParams(29, st.colorToAlphaTarget.toDouble(), st.colorToAlphaTol.toDouble(), st.colorToAlphaSmooth.toDouble(), 0.0)
            31 -> AdjustParams(31, st.rippleAmp.toDouble(), st.rippleFreq.toDouble(), 0.0, 0.0)
            32 -> AdjustParams(32, st.twirlAngle.toDouble(), st.twirlRadius.toDouble(), 0.0, 0.0)
            33 -> AdjustParams(33, st.surfaceBlurRadius.toDouble(), st.surfaceBlurThresh.toDouble(), 0.0, 0.0)
            34 -> AdjustParams(34, st.scanlineSpacing.toDouble(), st.scanlineIntensity.toDouble(), 0.0, 0.0)
            else -> return null // 13 / 30 为 LUT 型, 走专用通道
    }
    return p
}

internal fun filterNameResOf(filterId: Int): Int {
    return when (filterId) {
        0 -> R.string.filter_name_hsv
        1 -> R.string.filter_name_color_balance
        2 -> R.string.filter_name_gaussian_blur
        3 -> R.string.filter_name_motion_blur
        4 -> R.string.filter_name_sharpen
        5 -> R.string.filter_name_mosaic
        6 -> R.string.filter_name_invert
        7 -> R.string.filter_name_lum_to_alpha
        8 -> R.string.filter_name_sobel
        9 -> R.string.filter_name_emboss
        10 -> R.string.filter_name_noise
        11 -> R.string.filter_name_glitch
        12 -> R.string.filter_name_desaturate
        13 -> R.string.filter_name_curves
        14 -> R.string.filter_name_levels
        15 -> R.string.filter_name_temp_tint
        16 -> R.string.filter_name_threshold
        17 -> R.string.filter_name_posterize
        18 -> R.string.filter_name_bloom
        19 -> R.string.filter_name_drop_shadow
        20 -> R.string.filter_name_lum_to_opacity
        21 -> R.string.filter_name_oil
        22 -> R.string.filter_name_radial_blur
        23 -> R.string.filter_name_halftone
        24 -> R.string.filter_name_exposure
        25 -> R.string.filter_name_edge_glow
        26 -> R.string.filter_name_defocus
        27 -> R.string.filter_name_shadow_highlight
        28 -> R.string.filter_name_vibrance
        29 -> R.string.filter_name_color_to_alpha
        30 -> R.string.filter_name_gradient_map
        31 -> R.string.filter_name_ripple
        32 -> R.string.filter_name_swirl
        33 -> R.string.filter_name_surface_blur
        34 -> R.string.filter_name_scanline
        else -> R.string.filter_name_default
    }
}

internal fun filterNameOf(filterId: Int, context: android.content.Context? = null): String {
    val resId = filterNameResOf(filterId)
    return context?.getString(resId) ?: when (filterId) {
        0 -> "色相 / 饱和度 / 明度 / 对比度"
        1 -> "色彩平衡"
        2 -> "高斯模糊"
        3 -> "动感模糊"
        4 -> "锐化"
        5 -> "马赛克 / 像素化"
        6 -> "反相 (底片效果)"
        7 -> "亮度转透明度 (提取线稿)"
        8 -> "查找边缘 (Sobel)"
        9 -> "浮雕效果"
        10 -> "杂色 / 噪点"
        11 -> "色散错位 (Glitch)"
        12 -> "去色 (灰度化)"
        13 -> "曲线 (颜色调整)"
        14 -> "色阶"
        15 -> "色温与色调"
        16 -> "阈值 (黑白二值化)"
        17 -> "色调分离"
        18 -> "泛光 / 辉光 (Bloom)"
        19 -> "投影效果 (Drop Shadow)"
        20 -> "亮度转不透明度"
        21 -> "油画效果 (Kuwahara)"
        22 -> "径向/缩放模糊"
        23 -> "半色调网点"
        24 -> "曝光度与伽马"
        25 -> "边缘霓虹发光"
        26 -> "散焦模糊 (镜头光圈)"
        27 -> "阴影与高光"
        28 -> "自然饱和度 (Vibrance)"
        29 -> "颜色转透明度 (抠图)"
        30 -> "渐变映射 (自定义调色板)"
        31 -> "水波纹 / 涟漪扭曲"
        32 -> "旋涡扭曲 (Swirl)"
        33 -> "保边平滑 (Surface Blur)"
        34 -> "扫描线与 CRT 风格"
        else -> "滤镜"
    }
}

internal fun applyConfigToState(cfg: AdjustmentConfigCodec.Config, st: FilterAdjustState) {
    val p1 = cfg.p1.toFloat()
    val p2 = cfg.p2.toFloat()
    val p3 = cfg.p3.toFloat()
    val p4 = cfg.p4.toFloat()
    when (cfg.type) {
        0 -> { st.hue = p1; st.sat = p2; st.bright = p3; st.contrast = p4 }
        1 -> { st.cr = p1; st.mg = p2; st.yb = p3 }
        2 -> { st.blurRadius = p1 }
        3 -> { st.motionAngle = p1; st.motionDist = p2 }
        4 -> { st.sharpenAmt = p1 }
        5 -> { st.mosaicSize = p1 }
        6 -> { st.invertAmt = p1 }
        7 -> { st.lineartThresh = p1; st.lineartWhiteLine = p2 > 0.5f }
        8 -> { st.sobelStrength = p1; st.sobelMode = p2.toInt() }
        9 -> { st.embossDepth = p1; st.embossAngle = p2; st.embossPreserveColor = p3 > 0.5f }
        10 -> { st.noiseAmt = p1 }
        11 -> { st.glitchOffset = p1 }
        12 -> { st.desaturateAmt = p1 }
        14 -> { st.levelBlack = p1; st.levelWhite = p2; st.levelGamma = p3 }
        15 -> { st.tempVal = p1; st.tintVal = p2 }
        16 -> { st.thresholdVal = p1 }
        17 -> { st.posterizeLevels = p1 }
        18 -> { st.bloomThresh = p1; st.bloomRadius = p2; st.bloomIntensity = p3 }
        19 -> { st.shadowAngle = p1; st.shadowDist = p2; st.shadowRadius = p3; st.shadowOpacity = p4 }
        20 -> { st.lumOpacityInvert = p1 > 0.5f }
        21 -> { st.oilRadius = p1 }
        22 -> { st.radialBlurAmt = p1 }
        23 -> { st.halftoneDotSize = p1 }
        24 -> { st.exposureVal = p1; st.exposureGamma = p2 }
        25 -> { st.edgeGlowStrength = p1; st.edgeGlowRadius = p2; st.edgeGlowHue = p3.toInt() }
        26 -> { st.defocusRadius = p1 }
        27 -> { st.shadowBoost = p1; st.highlightReduce = p2 }
        28 -> { st.vibranceAmt = p1 }
        29 -> { st.colorToAlphaTarget = p1.toInt(); st.colorToAlphaTol = p2; st.colorToAlphaSmooth = p3 }
        31 -> { st.rippleAmp = p1; st.rippleFreq = p2 }
        32 -> { st.twirlAngle = p1; st.twirlRadius = p2 }
        33 -> { st.surfaceBlurRadius = p1; st.surfaceBlurThresh = p2 }
        34 -> { st.scanlineSpacing = p1; st.scanlineIntensity = p2 }
    }
}

/**
 * 过滤器参数分派:把 FilterAdjustState 的 UI 状态翻译为
 * vm.applyFilterPreview(index, filterType, p1..p4) 调用
 * (13 曲线 / 30 渐变映射由 FilterAdjustPage 内的专用预览处理)
 */
internal fun dispatchFilterPreview(
    vm: PaintViewModel,
    indices: List<Int>,
    filterId: Int,
    st: FilterAdjustState,
) {
    val params = adjustParamsOf(st, filterId) ?: return
    vm.applyFilterPreview(indices, params.type, params.p1, params.p2, params.p3, params.p4)
}

internal fun dispatchFilterPreview(
    vm: PaintViewModel,
    index: Int,
    filterId: Int,
    st: FilterAdjustState,
) {
    dispatchFilterPreview(vm, listOf(index), filterId, st)
}



@Composable
internal fun FilterParamSelectRow(
    def: FilterParamDef,
    valueText: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEditValue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isSelected) Morandi.accent.copy(alpha = 0.14f) else Color.Transparent
    val dotColor = if (isSelected) Morandi.accent else Morandi.subText.copy(alpha = 0.4f)
    val textColor = if (isSelected) Morandi.accentHi else Morandi.text
    val fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = if (def.nameRes != 0) stringResource(def.nameRes) else def.name,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = fontWeight,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) Morandi.panelHi else Morandi.panel)
                .border(
                    width = 0.8.dp,
                    color = if (isSelected) Morandi.accent.copy(alpha = 0.6f) else Morandi.border,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onEditValue)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = valueText,
                color = if (isSelected) Morandi.accentHi else Morandi.subText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun FilterAdjustControls(
    st: FilterAdjustState,
    filterId: Int,
    activeParamIndex: Int = 0,
    onSelectParam: (Int) -> Unit = {},
    onEditParamValue: (Int) -> Unit = {},
    sendPreview: () -> Unit,
    sendCurvesPreview: () -> Unit,
    sendGradientMapPreview: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (filterId) {
            13 -> { // Real 2D Curves Graph
                RealCurvesGraph(
                    channelPoints = st.curveChannels,
                    activeChannel = st.activeCurveChannel,
                    onChannelChange = { st.activeCurveChannel = it },
                    onCurveChanged = { sendCurvesPreview() }
                )
            }
            30 -> { // Custom Gradient Map
                CustomGradientEditor(
                    stops = st.customGradStops,
                    reverse = st.reverseGradient,
                    onReverseToggle = {
                        st.reverseGradient = !st.reverseGradient
                        sendGradientMapPreview()
                    },
                    onGradientChanged = { sendGradientMapPreview() }
                )
            }
            else -> {
                val defs = filterParamDefinitions(filterId)
                if (defs.isNotEmpty()) {
                    defs.forEachIndexed { idx, p ->
                        FilterParamSelectRow(
                            def = p,
                            valueText = p.format(p.getter(st)),
                            isSelected = activeParamIndex == idx,
                            onSelect = { onSelectParam(idx) },
                            onEditValue = { onEditParamValue(idx) },
                        )
                    }
                }

                // 针对含有离散选项的滤镜补充控制项
                when (filterId) {
                    7 -> { // Lineart Extraction
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.filter_invert_lineart_label), color = Morandi.text, fontSize = 12.sp)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (st.lineartWhiteLine) Morandi.accent else Morandi.panelHi)
                                    .clickable {
                                        st.lineartWhiteLine = !st.lineartWhiteLine
                                        sendPreview()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (st.lineartWhiteLine) stringResource(R.string.color_white) else stringResource(R.string.color_black),
                                    color = if (st.lineartWhiteLine) Color.White else Morandi.subText,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    8 -> { // Sobel
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.filter_edge_extract_mode), color = Morandi.text, fontSize = 12.sp)
                            val sobelModes = listOf(
                                stringResource(R.string.sobel_mode_white_bg_black_line),
                                stringResource(R.string.sobel_mode_black_bg_color),
                                stringResource(R.string.sobel_mode_transparent_lineart)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Morandi.panelHi)
                                    .clickable {
                                        st.sobelMode = (st.sobelMode + 1) % 3
                                        sendPreview()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    sobelModes.getOrElse(st.sobelMode) { sobelModes[0] },
                                    color = Morandi.accent,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    9 -> { // Emboss / 浮雕
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.filter_emboss_preserve_color_label), color = Morandi.text, fontSize = 12.sp)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (st.embossPreserveColor) Morandi.accent else Morandi.panelHi)
                                    .clickable {
                                        st.embossPreserveColor = !st.embossPreserveColor
                                        sendPreview()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (st.embossPreserveColor) stringResource(R.string.filter_emboss_preserve_color) else stringResource(R.string.filter_emboss_classic_gray),
                                    color = if (st.embossPreserveColor) Color.White else Morandi.subText,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    20 -> { // Luminance to Opacity
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.filter_lum_opacity_invert_label), color = Morandi.text, fontSize = 12.sp)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (st.lumOpacityInvert) Morandi.accent else Morandi.panelHi)
                                    .clickable {
                                        st.lumOpacityInvert = !st.lumOpacityInvert
                                        sendPreview()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (st.lumOpacityInvert) stringResource(R.string.filter_lum_opacity_inverted) else stringResource(R.string.filter_lum_opacity_default),
                                    color = if (st.lumOpacityInvert) Color.White else Morandi.subText,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    25 -> { // Edge Glow
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.filter_neon_color_mode), color = Morandi.text, fontSize = 12.sp)
                            val hueNames = listOf(
                                stringResource(R.string.neon_mode_enhanced),
                                stringResource(R.string.neon_mode_cyber),
                                stringResource(R.string.neon_mode_purple),
                                stringResource(R.string.neon_mode_gold)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Morandi.panelHi)
                                    .clickable {
                                        st.edgeGlowHue = (st.edgeGlowHue + 1) % 4
                                        sendPreview()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    hueNames.getOrElse(st.edgeGlowHue) { hueNames[0] },
                                    color = Morandi.accent,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    29 -> { // Color to Alpha
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.filter_target_keying_color), color = Morandi.text, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    stringResource(R.string.color_white_short) to 0xFFFFFF,
                                    stringResource(R.string.color_black_short) to 0x000000,
                                    stringResource(R.string.color_green_short) to 0x00FF00,
                                    stringResource(R.string.color_blue_short) to 0x0000FF
                                ).forEach { (lbl, col) ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (st.colorToAlphaTarget == col) Morandi.accent else Morandi.panelHi)
                                            .clickable {
                                                st.colorToAlphaTarget = col
                                                sendPreview()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(lbl, color = if (st.colorToAlphaTarget == col) Color.White else Morandi.text, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (defs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.filter_live_preview_hint),
                                color = Morandi.subText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
