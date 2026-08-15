package com.reverie.paint.ui.painting

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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

}


/**
 * 过滤器参数分派:把 FilterAdjustState 的 UI 状态翻译为
 * vm.applyFilterPreview(index, filterType, p1..p4) 调用
 * (13 曲线 / 30 渐变映射由 FilterAdjustPage 内的专用预览处理)
 */
internal fun dispatchFilterPreview(
    vm: PaintViewModel,
    index: Int,
    filterId: Int,
    st: FilterAdjustState,
) {
    when (filterId) {
            0 -> vm.applyFilterPreview(index, 0, st.hue.toDouble(), st.sat.toDouble(), st.bright.toDouble(), st.contrast.toDouble())
            1 -> vm.applyFilterPreview(index, 1, st.cr.toDouble(), st.mg.toDouble(), st.yb.toDouble(), 0.0)
            2 -> vm.applyFilterPreview(index, 2, st.blurRadius.toDouble(), 0.0, 0.0, 0.0)
            3 -> vm.applyFilterPreview(index, 3, st.motionAngle.toDouble(), st.motionDist.toDouble(), 0.0, 0.0)
            4 -> vm.applyFilterPreview(index, 4, st.sharpenAmt.toDouble(), 0.0, 0.0, 0.0)
            5 -> vm.applyFilterPreview(index, 5, st.mosaicSize.toDouble(), 0.0, 0.0, 0.0)
            6 -> vm.applyFilterPreview(index, 6, st.invertAmt.toDouble(), 0.0, 0.0, 0.0)
            7 -> vm.applyFilterPreview(index, 7, st.lineartThresh.toDouble(), if (st.lineartWhiteLine) 1.0 else 0.0, 0.0, 0.0)
            8 -> vm.applyFilterPreview(index, 8, st.sobelStrength.toDouble(), st.sobelMode.toDouble(), 0.0, 0.0)
            9 -> vm.applyFilterPreview(index, 9, st.embossDepth.toDouble(), st.embossAngle.toDouble(), if (st.embossPreserveColor) 1.0 else 0.0, 0.0)
            10 -> vm.applyFilterPreview(index, 10, st.noiseAmt.toDouble(), 0.0, 0.0, 0.0)
            11 -> vm.applyFilterPreview(index, 11, st.glitchOffset.toDouble(), 0.0, 0.0, 0.0)
            12 -> vm.applyFilterPreview(index, 12, st.desaturateAmt.toDouble(), 0.0, 0.0, 0.0)
            14 -> vm.applyFilterPreview(index, 14, st.levelBlack.toDouble(), st.levelWhite.toDouble(), st.levelGamma.toDouble(), 0.0)
            15 -> vm.applyFilterPreview(index, 15, st.tempVal.toDouble(), st.tintVal.toDouble(), 0.0, 0.0)
            16 -> vm.applyFilterPreview(index, 16, st.thresholdVal.toDouble(), 0.0, 0.0, 0.0)
            17 -> vm.applyFilterPreview(index, 17, st.posterizeLevels.toDouble(), 0.0, 0.0, 0.0)
            18 -> vm.applyFilterPreview(index, 18, st.bloomThresh.toDouble(), st.bloomRadius.toDouble(), st.bloomIntensity.toDouble(), 0.0)
            19 -> vm.applyFilterPreview(index, 19, st.shadowAngle.toDouble(), st.shadowDist.toDouble(), st.shadowRadius.toDouble(), st.shadowOpacity.toDouble())
            20 -> vm.applyFilterPreview(index, 20, if (st.lumOpacityInvert) 1.0 else 0.0, 0.0, 0.0, 0.0)
            21 -> vm.applyFilterPreview(index, 21, st.oilRadius.toDouble(), 0.0, 0.0, 0.0)
            22 -> vm.applyFilterPreview(index, 22, st.radialBlurAmt.toDouble(), 0.5, 0.5, 0.0)
            23 -> vm.applyFilterPreview(index, 23, st.halftoneDotSize.toDouble(), 0.0, 0.0, 0.0)
            24 -> vm.applyFilterPreview(index, 24, st.exposureVal.toDouble(), st.exposureGamma.toDouble(), 0.0, 0.0)
            25 -> vm.applyFilterPreview(index, 25, st.edgeGlowStrength.toDouble(), st.edgeGlowRadius.toDouble(), st.edgeGlowHue.toDouble(), 0.0)
            26 -> vm.applyFilterPreview(index, 26, st.defocusRadius.toDouble(), 0.0, 0.0, 0.0)
            27 -> vm.applyFilterPreview(index, 27, st.shadowBoost.toDouble(), st.highlightReduce.toDouble(), 0.0, 0.0)
            28 -> vm.applyFilterPreview(index, 28, st.vibranceAmt.toDouble(), 0.0, 0.0, 0.0)
            29 -> vm.applyFilterPreview(index, 29, st.colorToAlphaTarget.toDouble(), st.colorToAlphaTol.toDouble(), st.colorToAlphaSmooth.toDouble(), 0.0)
            31 -> vm.applyFilterPreview(index, 31, st.rippleAmp.toDouble(), st.rippleFreq.toDouble(), 0.0, 0.0)
            32 -> vm.applyFilterPreview(index, 32, st.twirlAngle.toDouble(), st.twirlRadius.toDouble(), 0.0, 0.0)
            33 -> vm.applyFilterPreview(index, 33, st.surfaceBlurRadius.toDouble(), st.surfaceBlurThresh.toDouble(), 0.0, 0.0)
            34 -> vm.applyFilterPreview(index, 34, st.scanlineSpacing.toDouble(), st.scanlineIntensity.toDouble(), 0.0, 0.0)
    }
}



@Composable
internal fun FilterAdjustControls(
    st: FilterAdjustState,
    filterId: Int,
    sendPreview: () -> Unit,
    sendCurvesPreview: () -> Unit,
    sendGradientMapPreview: () -> Unit,
) {
            when (filterId) {
                13 -> { // Real 2D Curves Graph
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("RGB" to 0, "红 (R)" to 1, "绿 (G)" to 2, "蓝 (B)" to 3).forEach { (name, ch) ->
                            val isSel = (st.activeCurveChannel == ch)
                            val chColor = when (ch) {
                                1 -> Color(0xFFFF5252)
                                2 -> Color(0xFF4CAF50)
                                3 -> Color(0xFF448AFF)
                                else -> Morandi.accent
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) chColor.copy(alpha = 0.25f) else Morandi.panelHi)
                                    .border(
                                        1.dp,
                                        if (isSel) chColor else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .noRippleClickable {
                                        st.activeCurveChannel = ch
                                    }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name,
                                    color = if (isSel) chColor else Morandi.subText,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    RealCurvesGraph(
                        channelPoints = st.curveChannels,
                        activeChannel = st.activeCurveChannel,
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
                27 -> { // Shadows & Highlights
                    FilterSliderRow(
                        label = "暗部提亮",
                        value = st.shadowBoost,
                        valueRange = 0f..100f,
                        valueText = "${st.shadowBoost.roundToInt()}%",
                        onValue = { st.shadowBoost = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "高光抑制",
                        value = st.highlightReduce,
                        valueRange = 0f..100f,
                        valueText = "${st.highlightReduce.roundToInt()}%",
                        onValue = { st.highlightReduce = it; sendPreview() }
                    )
                }
                28 -> { // Vibrance
                    FilterSliderRow(
                        label = "自然饱和度",
                        value = st.vibranceAmt,
                        valueRange = -100f..100f,
                        valueText = "${st.vibranceAmt.roundToInt()}%",
                        onValue = { st.vibranceAmt = it; sendPreview() }
                    )
                }
                29 -> { // Color to Alpha
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("目标抠像色彩", color = Morandi.text, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("白" to 0xFFFFFF, "黑" to 0x000000, "绿" to 0x00FF00, "蓝" to 0x0000FF).forEach { (lbl, col) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (st.colorToAlphaTarget == col) Morandi.accent else Morandi.panelHi)
                                        .noRippleClickable {
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
                    FilterSliderRow(
                        label = "颜色容差",
                        value = st.colorToAlphaTol,
                        valueRange = 0f..100f,
                        valueText = "${st.colorToAlphaTol.roundToInt()}",
                        onValue = { st.colorToAlphaTol = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "羽化过渡",
                        value = st.colorToAlphaSmooth,
                        valueRange = 0f..50f,
                        valueText = "${st.colorToAlphaSmooth.roundToInt()}",
                        onValue = { st.colorToAlphaSmooth = it; sendPreview() }
                    )
                }
                31 -> { // Water Ripple
                    FilterSliderRow(
                        label = "波动幅度",
                        value = st.rippleAmp,
                        valueRange = 1f..30f,
                        valueText = "${st.rippleAmp.roundToInt()} px",
                        onValue = { st.rippleAmp = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "波浪频率",
                        value = st.rippleFreq,
                        valueRange = 1f..50f,
                        valueText = "${st.rippleFreq.roundToInt()}",
                        onValue = { st.rippleFreq = it; sendPreview() }
                    )
                }
                32 -> { // Twirl / Swirl
                    FilterSliderRow(
                        label = "旋涡旋转角度",
                        value = st.twirlAngle,
                        valueRange = -360f..360f,
                        valueText = "${st.twirlAngle.roundToInt()}°",
                        onValue = { st.twirlAngle = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "旋涡作用半径",
                        value = st.twirlRadius,
                        valueRange = 20f..400f,
                        valueText = "${st.twirlRadius.roundToInt()} px",
                        onValue = { st.twirlRadius = it; sendPreview() }
                    )
                }
                33 -> { // Surface Blur
                    FilterSliderRow(
                        label = "保边平滑半径",
                        value = st.surfaceBlurRadius,
                        valueRange = 1f..15f,
                        valueText = "${st.surfaceBlurRadius.roundToInt()} px",
                        onValue = { st.surfaceBlurRadius = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "边缘对比阈值",
                        value = st.surfaceBlurThresh,
                        valueRange = 5f..80f,
                        valueText = "${st.surfaceBlurThresh.roundToInt()}",
                        onValue = { st.surfaceBlurThresh = it; sendPreview() }
                    )
                }
                34 -> { // Scanlines
                    FilterSliderRow(
                        label = "扫描线间距",
                        value = st.scanlineSpacing,
                        valueRange = 2f..12f,
                        valueText = "${st.scanlineSpacing.roundToInt()} px",
                        onValue = { st.scanlineSpacing = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "扫描光栅浓度",
                        value = st.scanlineIntensity,
                        valueRange = 0f..100f,
                        valueText = "${st.scanlineIntensity.roundToInt()}%",
                        onValue = { st.scanlineIntensity = it; sendPreview() }
                    )
                }
                0 -> { // HSBC
                    FilterSliderRow(
                        label = "色相",
                        value = st.hue,
                        valueRange = -180f..180f,
                        valueText = "${st.hue.roundToInt()}",
                        gradient = Brush.horizontalGradient(
                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                        ),
                        onValue = { st.hue = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "饱和度",
                        value = st.sat,
                        valueRange = 0f..2f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.sat),
                        gradient = Brush.horizontalGradient(listOf(Color(0xFF888888), Color(0xFFFF4444))),
                        onValue = { st.sat = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "明度",
                        value = st.bright,
                        valueRange = 0f..2f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.bright),
                        gradient = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                        onValue = { st.bright = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "对比度",
                        value = st.contrast,
                        valueRange = 0f..2f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.contrast),
                        onValue = { st.contrast = it; sendPreview() }
                    )
                }
                1 -> { // Color Balance
                    FilterSliderRow(
                        label = "青 - 红",
                        value = st.cr,
                        valueRange = -100f..100f,
                        valueText = "${st.cr.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Cyan, Color.Red)),
                        onValue = { st.cr = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "洋红 - 绿",
                        value = st.mg,
                        valueRange = -100f..100f,
                        valueText = "${st.mg.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Magenta, Color.Green)),
                        onValue = { st.mg = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "黄 - 蓝",
                        value = st.yb,
                        valueRange = -100f..100f,
                        valueText = "${st.yb.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Yellow, Color.Blue)),
                        onValue = { st.yb = it; sendPreview() }
                    )
                }
                2 -> { // Gaussian Blur
                    FilterSliderRow(
                        label = "模糊半径",
                        value = st.blurRadius,
                        valueRange = 1f..100f,
                        valueText = "${st.blurRadius.roundToInt()} px",
                        onValue = { st.blurRadius = it; sendPreview() }
                    )
                }
                3 -> { // Motion Blur
                    FilterSliderRow(
                        label = "模糊角度",
                        value = st.motionAngle,
                        valueRange = 0f..360f,
                        valueText = "${st.motionAngle.roundToInt()}°",
                        onValue = { st.motionAngle = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "模糊距离",
                        value = st.motionDist,
                        valueRange = 1f..100f,
                        valueText = "${st.motionDist.roundToInt()} px",
                        onValue = { st.motionDist = it; sendPreview() }
                    )
                }
                4 -> { // Sharpen
                    FilterSliderRow(
                        label = "锐化强度",
                        value = st.sharpenAmt,
                        valueRange = 0.1f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.sharpenAmt),
                        onValue = { st.sharpenAmt = it; sendPreview() }
                    )
                }
                5 -> { // Mosaic
                    FilterSliderRow(
                        label = "像素大小",
                        value = st.mosaicSize,
                        valueRange = 2f..64f,
                        valueText = "${st.mosaicSize.roundToInt()} px",
                        onValue = { st.mosaicSize = it; sendPreview() }
                    )
                }
                10 -> { // Noise
                    FilterSliderRow(
                        label = "杂色数量",
                        value = st.noiseAmt,
                        valueRange = 1f..100f,
                        valueText = "${st.noiseAmt.roundToInt()}",
                        onValue = { st.noiseAmt = it; sendPreview() }
                    )
                }
                11 -> { // Glitch
                    FilterSliderRow(
                        label = "色散偏移",
                        value = st.glitchOffset,
                        valueRange = 1f..40f,
                        valueText = "${st.glitchOffset.roundToInt()} px",
                        onValue = { st.glitchOffset = it; sendPreview() }
                    )
                }
                14 -> { // Levels
                    FilterSliderRow(
                        label = "输入黑场",
                        value = st.levelBlack,
                        valueRange = 0f..254f,
                        valueText = "${st.levelBlack.roundToInt()}",
                        onValue = { st.levelBlack = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "输入白场",
                        value = st.levelWhite,
                        valueRange = (st.levelBlack + 1f)..255f,
                        valueText = "${st.levelWhite.roundToInt()}",
                        onValue = { st.levelWhite = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "中间调灰度 (Gamma)",
                        value = st.levelGamma,
                        valueRange = 0.1f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.2f", st.levelGamma),
                        onValue = { st.levelGamma = it; sendPreview() }
                    )
                }
                15 -> { // Temperature & Tint
                    FilterSliderRow(
                        label = "色温 (冷 - 暖)",
                        value = st.tempVal,
                        valueRange = -100f..100f,
                        valueText = "${st.tempVal.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color(0xFF4A90E2), Color(0xFFF5A623))),
                        onValue = { st.tempVal = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "色调 (绿 - 洋红)",
                        value = st.tintVal,
                        valueRange = -100f..100f,
                        valueText = "${st.tintVal.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color(0xFF50E3C2), Color(0xFFBD10E0))),
                        onValue = { st.tintVal = it; sendPreview() }
                    )
                }
                16 -> { // Threshold
                    FilterSliderRow(
                        label = "黑白阈值",
                        value = st.thresholdVal,
                        valueRange = 1f..255f,
                        valueText = "${st.thresholdVal.roundToInt()}",
                        gradient = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                        onValue = { st.thresholdVal = it; sendPreview() }
                    )
                }
                17 -> { // Posterize
                    FilterSliderRow(
                        label = "色阶分离层数",
                        value = st.posterizeLevels,
                        valueRange = 2f..32f,
                        valueText = "${st.posterizeLevels.roundToInt()} 层",
                        onValue = { st.posterizeLevels = it; sendPreview() }
                    )
                }
                18 -> { // Bloom
                    FilterSliderRow(
                        label = "辉光亮度门限",
                        value = st.bloomThresh,
                        valueRange = 0f..255f,
                        valueText = "${st.bloomThresh.roundToInt()}",
                        onValue = { st.bloomThresh = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "泛光扩散半径",
                        value = st.bloomRadius,
                        valueRange = 1f..60f,
                        valueText = "${st.bloomRadius.roundToInt()} px",
                        onValue = { st.bloomRadius = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "辉光发光强度",
                        value = st.bloomIntensity,
                        valueRange = 0.1f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.bloomIntensity),
                        onValue = { st.bloomIntensity = it; sendPreview() }
                    )
                }
                19 -> { // Drop Shadow
                    FilterSliderRow(
                        label = "投影角度",
                        value = st.shadowAngle,
                        valueRange = 0f..360f,
                        valueText = "${st.shadowAngle.roundToInt()}°",
                        onValue = { st.shadowAngle = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "投影距离",
                        value = st.shadowDist,
                        valueRange = 0f..50f,
                        valueText = "${st.shadowDist.roundToInt()} px",
                        onValue = { st.shadowDist = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "阴影模糊半径",
                        value = st.shadowRadius,
                        valueRange = 1f..40f,
                        valueText = "${st.shadowRadius.roundToInt()} px",
                        onValue = { st.shadowRadius = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "阴影不透明度",
                        value = st.shadowOpacity,
                        valueRange = 0f..1f,
                        valueText = "${(st.shadowOpacity * 100).roundToInt()}%",
                        onValue = { st.shadowOpacity = it; sendPreview() }
                    )
                }
                20 -> { // Luminance to Opacity
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("反转亮度关系 (暗部不透明)", color = Morandi.text, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (st.lumOpacityInvert) Morandi.accent else Morandi.panelHi)
                                .noRippleClickable {
                                    st.lumOpacityInvert = !st.lumOpacityInvert
                                    sendPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (st.lumOpacityInvert) "反转" else "默认",
                                color = if (st.lumOpacityInvert) Color.White else Morandi.subText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                21 -> { // Oil Paint
                    FilterSliderRow(
                        label = "写生油画半径",
                        value = st.oilRadius,
                        valueRange = 1f..8f,
                        valueText = "${st.oilRadius.roundToInt()} px",
                        onValue = { st.oilRadius = it; sendPreview() }
                    )
                }
                22 -> { // Radial / Zoom Blur
                    FilterSliderRow(
                        label = "聚焦辐射强度",
                        value = st.radialBlurAmt,
                        valueRange = 1f..50f,
                        valueText = "${st.radialBlurAmt.roundToInt()}",
                        onValue = { st.radialBlurAmt = it; sendPreview() }
                    )
                }
                23 -> { // Halftone
                    FilterSliderRow(
                        label = "网点单元大小",
                        value = st.halftoneDotSize,
                        valueRange = 4f..24f,
                        valueText = "${st.halftoneDotSize.roundToInt()} px",
                        onValue = { st.halftoneDotSize = it; sendPreview() }
                    )
                }
                24 -> { // Exposure & Gamma
                    FilterSliderRow(
                        label = "曝光值 (EV)",
                        value = st.exposureVal,
                        valueRange = -3.0f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%+.1f EV", st.exposureVal),
                        onValue = { st.exposureVal = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "伽马校正",
                        value = st.exposureGamma,
                        valueRange = 0.2f..3.0f,
                        valueText = String.format(Locale.getDefault(), "%.2f", st.exposureGamma),
                        onValue = { st.exposureGamma = it; sendPreview() }
                    )
                }
                25 -> { // Edge Glow
                    FilterSliderRow(
                        label = "荧光发光强度",
                        value = st.edgeGlowStrength,
                        valueRange = 0.5f..5.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.edgeGlowStrength),
                        onValue = { st.edgeGlowStrength = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "霓虹扩散半径",
                        value = st.edgeGlowRadius,
                        valueRange = 1f..30f,
                        valueText = "${st.edgeGlowRadius.roundToInt()} px",
                        onValue = { st.edgeGlowRadius = it; sendPreview() }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("霓虹色彩模式", color = Morandi.text, fontSize = 12.sp)
                        val hueNames = listOf("原色增强", "赛博青蓝", "霓虹粉紫", "炫彩金黄")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panelHi)
                                .noRippleClickable {
                                    st.edgeGlowHue = (st.edgeGlowHue + 1) % 4
                                    sendPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                hueNames.getOrElse(st.edgeGlowHue) { "原色增强" },
                                color = Morandi.accent,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                26 -> { // Defocus Blur
                    FilterSliderRow(
                        label = "光圈散焦半径",
                        value = st.defocusRadius,
                        valueRange = 1f..30f,
                        valueText = "${st.defocusRadius.roundToInt()} px",
                        onValue = { st.defocusRadius = it; sendPreview() }
                    )
                }
                6 -> { // Invert
                    FilterSliderRow(
                        label = "反相强度",
                        value = st.invertAmt,
                        valueRange = 0f..100f,
                        valueText = "${st.invertAmt.roundToInt()}%",
                        onValue = { st.invertAmt = it; sendPreview() }
                    )
                }
                7 -> { // Lineart Extraction
                    FilterSliderRow(
                        label = "线稿提取门限",
                        value = st.lineartThresh,
                        valueRange = 0f..255f,
                        valueText = "${st.lineartThresh.roundToInt()}",
                        onValue = { st.lineartThresh = it; sendPreview() }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("反转线稿色彩 (生成白色线稿)", color = Morandi.text, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (st.lineartWhiteLine) Morandi.accent else Morandi.panelHi)
                                .noRippleClickable {
                                    st.lineartWhiteLine = !st.lineartWhiteLine
                                    sendPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (st.lineartWhiteLine) "白色" else "黑色",
                                color = if (st.lineartWhiteLine) Color.White else Morandi.subText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                8 -> { // Sobel
                    FilterSliderRow(
                        label = "边缘灵敏度",
                        value = st.sobelStrength,
                        valueRange = 0.5f..10.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.sobelStrength),
                        onValue = { st.sobelStrength = it; sendPreview() }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("边缘提取模式", color = Morandi.text, fontSize = 12.sp)
                        val sobelModes = listOf("白底黑线", "黑底彩色", "透明线稿")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panelHi)
                                .noRippleClickable {
                                    st.sobelMode = (st.sobelMode + 1) % 3
                                    sendPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                sobelModes.getOrElse(st.sobelMode) { "白底黑线" },
                                color = Morandi.accent,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                9 -> { // Emboss / 浮雕
                    FilterSliderRow(
                        label = "浮雕深度",
                        value = st.embossDepth,
                        valueRange = 0.5f..10.0f,
                        valueText = String.format(Locale.getDefault(), "%.1f", st.embossDepth),
                        onValue = { st.embossDepth = it; sendPreview() }
                    )
                    FilterSliderRow(
                        label = "光影投射角度",
                        value = st.embossAngle,
                        valueRange = 0f..360f,
                        valueText = "${st.embossAngle.roundToInt()}°",
                        onValue = { st.embossAngle = it; sendPreview() }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("保留原色 (彩色浮雕)", color = Morandi.text, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (st.embossPreserveColor) Morandi.accent else Morandi.panelHi)
                                .noRippleClickable {
                                    st.embossPreserveColor = !st.embossPreserveColor
                                    sendPreview()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (st.embossPreserveColor) "保留原色" else "经典灰阶",
                                color = if (st.embossPreserveColor) Color.White else Morandi.subText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                12 -> { // Desaturate
                    FilterSliderRow(
                        label = "去色强度",
                        value = st.desaturateAmt,
                        valueRange = 0f..100f,
                        valueText = "${st.desaturateAmt.roundToInt()}%",
                        onValue = { st.desaturateAmt = it; sendPreview() }
                    )
                }
                else -> {
                    Text(
                        text = "此滤镜已实时应用至图层预览，点击右上角应用按钮确认",
                        color = Morandi.subText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
}
