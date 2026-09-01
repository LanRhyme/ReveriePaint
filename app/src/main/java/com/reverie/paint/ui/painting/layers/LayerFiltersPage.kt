/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

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

data class FilterItemDef(
    val id: Int,
    val name: String,
    val hasSliders: Boolean,
    val desc: String = ""
)

data class FilterCategoryDef(
    val id: String,
    val name: String,
    val iconRes: Int,
    val filters: List<FilterItemDef>
)

@Composable
internal fun FiltersPage(
    vm: PaintViewModel,
    indices: List<Int>,
    onBack: () -> Unit,
    onSelectFilter: (Int, String) -> Unit,
    initialCategoryId: String? = null,
) {
    val categories = remember {
        listOf(
            FilterCategoryDef(
                id = "color",
                name = "调整图像/颜色",
                iconRes = R.drawable.ic_image_adjust,
                filters = listOf(
                    FilterItemDef(13, "曲线 (颜色调整)", true, "交互式多通道RGB调色曲线"),
                    FilterItemDef(14, "色阶", true, "黑场、白场与中间调伽马调整"),
                    FilterItemDef(27, "阴影与高光", true, "暗部提亮与高光过曝抑制"),
                    FilterItemDef(28, "自然饱和度 (Vibrance)", true, "保护肤色与低饱和度色彩提升"),
                    FilterItemDef(0, "HSV 色相/饱和度/明度/对比度", true, "色相偏移与明暗饱和度"),
                    FilterItemDef(1, "色彩平衡", true, "青红、洋绿、黄蓝平衡"),
                    FilterItemDef(15, "色温与色调", true, "冷暖色温与绿-洋红色调"),
                    FilterItemDef(24, "曝光度与伽马", true, "线性曝光值与伽马曲线"),
                    FilterItemDef(16, "阈值 (黑白二值化)", true, "明度门限黑白分割"),
                    FilterItemDef(12, "去色 (灰度化)", true, "转为黑白灰度图"),
                    FilterItemDef(6, "反相 (底片效果)", true, "反转通道颜色"),
                )
            ),
            FilterCategoryDef(
                id = "artistic",
                name = "艺术效果",
                iconRes = R.drawable.ic_brush,
                filters = listOf(
                    FilterItemDef(21, "油画效果 (Kuwahara)", true, "基于局部方差的写生油画质感"),
                    FilterItemDef(34, "扫描线与 CRT 风格", true, "复古显像管扫描光栅效果"),
                    FilterItemDef(5, "马赛克 / 像素化", true, "网格块状像素化"),
                    FilterItemDef(17, "色调分离", true, "色彩阶数离散量化"),
                    FilterItemDef(10, "杂色 / 噪点", true, "胶片颗粒感噪点添加"),
                    FilterItemDef(23, "半色调网点", true, "印刷漫画网点风格"),
                )
            ),
            FilterCategoryDef(
                id = "blur",
                name = "模糊与平滑",
                iconRes = R.drawable.ic_smudge,
                filters = listOf(
                    FilterItemDef(33, "保边平滑 (Surface Blur)", true, "磨皮降噪且保留清晰轮廓边缘"),
                    FilterItemDef(2, "高斯模糊", true, "Alpha加权多核高斯平滑"),
                    FilterItemDef(3, "动感模糊", true, "任意角度线性积分模糊"),
                    FilterItemDef(22, "径向/缩放模糊", true, "中心辐射聚焦模糊"),
                    FilterItemDef(26, "散焦模糊 (镜头光圈)", true, "圆形弥散斑镜头虚化"),
                )
            ),
            FilterCategoryDef(
                id = "distort",
                name = "空间与扭曲",
                iconRes = R.drawable.ic_crop,
                filters = listOf(
                    FilterItemDef(31, "水波纹 / 涟漪扭曲", true, "正弦水面波浪波动畸变"),
                    FilterItemDef(32, "旋涡扭曲 (Swirl)", true, "中心渐进旋转扭曲"),
                    FilterItemDef(11, "色散错位 (Glitch)", true, "红蓝RGB通道错位色散"),
                )
            ),
            FilterCategoryDef(
                id = "enhance",
                name = "图像增强",
                iconRes = R.drawable.ic_magicwand,
                filters = listOf(
                    FilterItemDef(4, "锐化", true, "拉普拉斯边缘对比度锐化"),
                    FilterItemDef(18, "泛光 / 辉光 (Bloom)", true, "高光溢出扩散光晕"),
                    FilterItemDef(19, "投影效果 (Drop Shadow)", true, "自定义角度与模糊阴影"),
                    FilterItemDef(8, "查找边缘 (Sobel)", true, "轮廓边缘检测提取"),
                    FilterItemDef(25, "边缘霓虹发光", true, "边缘高亮荧光发光"),
                    FilterItemDef(9, "浮雕效果", true, "立体凹凸光影浮雕"),
                )
            ),
            FilterCategoryDef(
                id = "map",
                name = "映射与通道",
                iconRes = R.drawable.ic_gradient,
                filters = listOf(
                    FilterItemDef(30, "渐变映射 (自定义调色板)", true, "灰度映射至多色阶调调色板"),
                    FilterItemDef(29, "颜色转透明度 (抠图)", true, "指定颜色转透明并羽化边缘"),
                    FilterItemDef(20, "亮度转不透明度", true, "明度保留色彩并调制Alpha通道"),
                    FilterItemDef(7, "亮度转透明度 (提取线稿)", true, "纯黑线稿透明化提取"),
                )
            ),
        )
    }

    // 快捷键 (filter_hsv/curves/blur/sharpen) 预选分类; 用户仍可返回重选
    var selectedCategory by remember(initialCategoryId) {
        mutableStateOf(initialCategoryId?.let { id -> categories.firstOrNull { it.id == id } })
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .noRippleClickable {
                            if (selectedCategory != null) {
                                selectedCategory = null
                            } else {
                                onBack()
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = "返回",
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            val titleText = selectedCategory?.name ?: if (indices.size > 1) "滤镜库 (${indices.size}个图层)" else "滤镜库"
            Text(
                text = titleText,
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        AnimatedContent(
            targetState = selectedCategory,
            label = "FilterNav"
        ) { category ->
            if (category == null) {
                // Category List (Level 1)
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable { selectedCategory = cat }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.accent.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(cat.iconRes),
                                        contentDescription = null,
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column {
                                    Text(cat.name, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("${cat.filters.size} 个滤镜", color = Morandi.subText, fontSize = 11.sp)
                                }
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.subText.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(0.5.dp).background(Morandi.border.copy(alpha = 0.4f)))
                    }
                }
            } else {
                // Category Filters (Level 2)
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    category.filters.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable {
                                    onSelectFilter(item.id, item.name)
                                }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (item.desc.isNotEmpty()) {
                                    Text(item.desc, color = Morandi.subText, fontSize = 11.sp)
                                }
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.subText.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(0.5.dp).background(Morandi.border.copy(alpha = 0.4f)))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Real Interactive 2D Curves Graph Component
// ---------------------------------------------------------------------------

internal fun calculateMonotoneCubicSplineLUT(points: List<Offset>): ByteArray {
    val sorted = points.sortedBy { it.x }.distinctBy { it.x.toInt() }
    val lut = ByteArray(256)
    if (sorted.isEmpty()) {
        for (i in 0..255) lut[i] = i.toByte()
        return lut
    }
    if (sorted.size == 1) {
        val y = sorted[0].y.coerceIn(0f, 255f).toInt().toByte()
        for (i in 0..255) lut[i] = y
        return lut
    }
    val n = sorted.size
    val x = sorted.map { it.x.coerceIn(0f, 255f) }
    val y = sorted.map { it.y.coerceIn(0f, 255f) }
    val d = FloatArray(n - 1)
    val m = FloatArray(n)
    for (i in 0 until n - 1) {
        val dx = x[i + 1] - x[i]
        d[i] = if (dx != 0f) (y[i + 1] - y[i]) / dx else 0f
    }
    m[0] = d[0]
    for (i in 1 until n - 1) {
        m[i] = (d[i - 1] + d[i]) * 0.5f
    }
    m[n - 1] = d[n - 2]
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val tau = 3f / kotlin.math.sqrt(s)
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
    }
    var seg = 0
    for (i in 0..255) {
        val curX = i.toFloat()
        if (curX <= x[0]) {
            lut[i] = y[0].toInt().coerceIn(0, 255).toByte()
            continue
        }
        if (curX >= x[n - 1]) {
            lut[i] = y[n - 1].toInt().coerceIn(0, 255).toByte()
            continue
        }
        while (seg < n - 2 && curX > x[seg + 1]) {
            seg++
        }
        val h = x[seg + 1] - x[seg]
        val t = if (h != 0f) (curX - x[seg]) / h else 0f
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        val curY = h00 * y[seg] + h10 * h * m[seg] + h01 * y[seg + 1] + h11 * h * m[seg + 1]
        lut[i] = curY.toInt().coerceIn(0, 255).toByte()
    }
    return lut
}

