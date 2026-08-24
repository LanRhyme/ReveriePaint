# UI 玻璃拟态与液态动效升级 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline execution chosen by user).

**Goal:** 为 ReveriePaint 建立玻璃拟态设计系统（Glass/Motion/Liquid 三件套），统一全部面板样式与动效，并克制化品牌蓝用法。

**Architecture:** 新增 `ui/theme/Motion.kt`、`ui/theme/Glass.kt`、`ui/components/Liquid.kt` 三个纯新增文件作为设计系统层；既有组件与面板渐进迁移到该层；blur 能力在 ViewModel 单点收口。

**Tech Stack:** Kotlin 2.4 + Compose BOM 2026.05.00 + haze 1.5.3（已接入）

**Spec:** docs/superpowers/specs/2026-08-24-ui-glass-motion-design.md

## Global Constraints

- 零分配铁律：动效状态全部 `remember` 预分配；不碰画布手势代码（`ui/painting/canvas/`）
- 色板饱和度不动（用户修订）；accent 作文字/图标/实心控件色时不降透明度
- 有效模糊条件在 ViewModel 单点收口：`prefs.getBoolean("blurBackground", true) && SDK>=31`
- 每个 Task 后跑 `./gradlew :app:compileDebugKotlin`；中文 Conventional Commits
- UI 文案中文字面量；颜色引用语义 token

---

### Task 1: 新增 Motion.kt + Glass.kt + Liquid.kt

**Files:**
- Create: `app/src/main/java/com/reverie/paint/ui/theme/Motion.kt`
- Create: `app/src/main/java/com/reverie/paint/ui/theme/Glass.kt`
- Create: `app/src/main/java/com/reverie/paint/ui/components/Liquid.kt`

**Interfaces (Produces):**
```kotlin
// Motion.kt
object Motion {
    val snapBouncy: Spring<Float>   // 0.55/400 按压回弹
    val springSoft: Spring<Float>   // 0.85/350 面板出入场
    val springSnap: Spring<Float>   // 0.90/500 选中切换
    fun <T> enterSpring(): Spring<T>   // 泛型便捷（Dp/Offset/Color 向量转换器均适用）
}

// Glass.kt
val deviceSupportsBlur: Boolean          // SDK >= S
object Glass {
    val blurRadius: Dp                   // 24.dp
    fun barStyle(alpha: Float = 0.78f): HazeStyle      // @Composable
    fun popupStyle(alpha: Float = 0.66f): HazeStyle    // @Composable
}
fun Modifier.glassBorder(shape: Shape): Modifier   // 上亮下暗方向性渐变描边

// Liquid.kt
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier
fun Modifier.pressGrow(source: MutableInteractionSource, growFraction: Float = 0.04f): Modifier
fun Modifier.liquidLean(source: MutableInteractionSource, maxOffset: Dp = 5.dp): Modifier
fun Modifier.liquidHighlight(source: MutableInteractionSource, color: Color, radius: Dp = 56.dp): Modifier
```

**Steps:**
- [ ] 写三个文件（完整源码如下）
- [ ] `./gradlew :app:compileDebugKotlin` PASS
- [ ] Commit: `feat(ui): 新增 Motion/Glass/Liquid 设计系统三件套`

Motion.kt 源码：
```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 统一动效 token。三档弹簧覆盖全部交互：
 *  - snapBouncy: 按压回弹（过冲感）
 *  - springSoft: 面板出入场（柔和滑入）
 *  - springSnap: 选中切换/开关（快而稳）
 */
object Motion {
    val snapBouncy: Spring<Float> = spring(dampingRatio = 0.55f, stiffness = 400f)
    val springSoft: Spring<Float> = spring(dampingRatio = 0.85f, stiffness = 350f)
    val springSnap: Spring<Float> = spring(dampingRatio = 0.90f, stiffness = 500f)

    /** 面板出入场入场用；出场保持短 tween 保证响应速度 */
    fun <T> enterSpring(): Spring<T> = spring(dampingRatio = 0.85f, stiffness = 350f)
}
```

Glass.kt 源码：
```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.ui.theme

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/** 设备是否支持真实背景模糊（RenderEffect 需 API 31+） */
val deviceSupportsBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 玻璃拟态统一样式工厂。全 app 浮层只允许经此创建 HazeStyle，
 * 保证 blurRadius/noiseFactor/色调一致。
 */
object Glass {
    val blurRadius = 24.dp
    private const val noiseFactor = 0.02f

    /** 浮层条：顶栏/工具滑轨/侧栏大面板/底部栏 */
    @Composable
    fun barStyle(alpha: Float = 0.78f): HazeStyle = style(alpha)

    /** 弹出层：对话框/浮动弹窗，更透更虚 */
    @Composable
    fun popupStyle(alpha: Float = 0.66f): HazeStyle = style(alpha)

    @Composable
    private fun style(alpha: Float): HazeStyle {
        val c = Morandi.panel.copy(alpha = alpha.coerceIn(0.05f, 0.98f))
        return HazeStyle(backgroundColor = c, tint = HazeTint(c), blurRadius = blurRadius, noiseFactor = noiseFactor)
    }
}

/**
 * 方向性玻璃描边：上缘受光（白 α0.14）渐隐至下缘背光（黑 α0.06），
 * 替代纯色 border 的塑料感。hairline 1dp。
 */
fun Modifier.glassBorder(shape: Shape): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.14f),
            Color.White.copy(alpha = 0.02f),
            Color.Black.copy(alpha = 0.06f),
        )
    )
    drawOutline(outline, brush = brush, style = Stroke(width = 1.dp.toPx()))
}
```
（注意：`@Composable` 需要 import `androidx.compose.runtime.Composable`；`createOutline` 需要 `androidx.compose.ui.graphics.Outline` 相关 import 由编译器解析补齐。）

Liquid.kt 源码：
```kotlin
/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reverie.paint.ui.theme.Motion
import kotlin.math.tanh

/**
 * 液态交互 Modifier 集（参数模型参考 Kyant0/LiquidGlass LiquidButton，纯 Compose 复刻）。
 * 全部以外部 MutableInteractionSource 驱动，可与既有 clickable 组合；
 * 状态对象均在组合期预分配，符合零分配铁律。
 */

/** 按压缩放回弹（图标/chip/图层行/卡片）。pressedScale<1 压缩手感。 */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier =
    composed {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Motion.snapBouncy, label = "liquidPress")
        androidx.compose.ui.draw.scale(scale)
    }

/** 按压微放大（大按钮，LiquidButton 参数模型：lerp(1, 1+grow, progress)）。 */
fun Modifier.pressGrow(source: MutableInteractionSource, growFraction: Float = 0.04f): Modifier =
    composed {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 1f + growFraction else 1f, Motion.snapBouncy, label = "liquidGrow")
        androidx.compose.ui.draw.scale(scale)
    }

/**
 * 触点倾倒：按住时向触点方向饱和位移 maxOffset * tanh(0.05 * d/maxOffsetPx)，
 * 松手弹回。用于工具栏大按钮。
 */
fun Modifier.liquidLean(source: MutableInteractionSource, maxOffset: Dp = 5.dp): Modifier =
    composed {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxPx = with(density) { maxOffset.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        val pressed by source.collectIsPressedAsState()

        suspend fun leanTo(x: Float, y: Float) {
            val dx = x - size.width / 2f
            val dy = y - size.height / 2f
            kotlinx.coroutines.coroutineScope {
                launchAnim(offsetX, maxPx * tanh(0.05 * dx / maxPx))
                launchAnim(offsetY, maxPx * tanh(0.05 * dy / maxPx))
            }
        }
        androidx.compose.runtime.LaunchedEffect(pressed) {
            if (!pressed) {
                launchAnim(offsetX, 0f)
                launchAnim(offsetY, 0f)
            }
        }
        this
            .onSizeChanged { size = it }
            .pointerInput(source) {
                awaitPointerEventScopeWhilePressed(source) { x, y -> leanTo(x, y) }
            }
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
    }

/** 高光跟随：按压时触点径向高光，松手淡出。 */
fun Modifier.liquidHighlight(source: MutableInteractionSource, color: Color, radius: Dp = 56.dp): Modifier =
    composed {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val rPx = with(density) { radius.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val center = remember { mutableStateOf(Offset.Zero) }
        val alpha = remember { Animatable(0f) }
        val pressed by source.collectIsPressedAsState()

        androidx.compose.runtime.LaunchedEffect(pressed) {
            alpha.animateTo(if (pressed) 0.35f else 0f, Motion.springSoft)
        }
        this
            .onSizeChanged { size = it }
            .pointerInput(source) {
                awaitPointerEventScopeWhilePressed(source) { x, y ->
                    center.value = Offset(x, y)
                }
            }
            .drawWithContent {
                drawContent()
                if (alpha.value > 0.01f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = alpha.value), Color.Transparent),
                            center = center.value,
                            radius = rPx,
                        ),
                        radius = rPx,
                        center = center.value,
                    )
                }
            }
    }

// ---- 私有工具 ----
private fun kotlinx.coroutines.CoroutineScope.launchAnim(a: Animatable<Float, *>, target: Float) =
    launch { a.animateTo(target, Motion.springSnap) }

private suspend fun AwaitPointerEventScope ... // 见下
```

> 实现注记（执行者读）：`awaitPointerEventScopeWhilePressed` 不是标准 API —— 直接内联实现：
> ```kotlin
> .pointerInput(source) {
>     awaitEachGesture {
>         val down = awaitFirstDown(requireUnconsumed = false)
>         onMove(down.position)
>         while (true) {
>             val event = awaitPointerEvent()
>             if (event.changes.all { !it.pressed }) break
>             onMove(event.changes.first().position)
>         }
>     }
> }
> ```
> 把该私有函数写成 `private suspend fun PointerInputScope.trackPress(source: ..., onMove: (Offset) -> Unit)` 或直接在每个 modifier 内联。lean/highlight 共用一个内部实现即可。所需 import：`androidx.compose.foundation.gestures.awaitEachGesture / awaitFirstDown`、`androidx.compose.foundation.layout.offset`、`androidx.compose.ui.unit.IntOffset`、`kotlin.math.roundToInt`、`androidx.compose.ui.input.pointer.PointerEventPass`（默认 pass 即可）、`kotlinx.coroutines.launch`、`kotlinx.coroutines.coroutineScope`。

### Task 2: blur 默认开启 + 能力单点收口

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/core/PaintViewModel.kt:525` 与 `:1162`
- Modify: `app/src/main/java/com/reverie/paint/ui/home/ThemeSettingsSubPage.kt:459-464`

**Steps:**
- [ ] L525: `var blurBackground by mutableStateOf(false)` → `mutableStateOf(deviceSupportsBlur)`，注释更新「默认开启；不支持设备自动降级」；import `com.reverie.paint.ui.theme.deviceSupportsBlur`
- [ ] L1162: `blurBackground = prefs.getBoolean("blurBackground", false)` → `prefs.getBoolean("blurBackground", true) && deviceSupportsBlur`
- [ ] ThemeSettingsSubPage SettingSwitchRow 加 `enabled = deviceSupportsBlur`；summary 不支持时显示「当前设备不支持（需 Android 12 及以上）」
- [ ] compileDebugKotlin PASS；Commit: `feat(ui): 背景毛玻璃默认开启并在低版本设备自动降级`

### Task 3: ReComponents 全面接入

**Files:**
- Modify: `app/src/main/java/com/reverie/paint/ui/components/ReComponents.kt`

**变换清单（逐项）:**
1. **ReIconButton**: 建 `val interaction = remember { MutableInteractionSource() }`；`.clickable { onTap() }` → `.clickable(interactionSource = interaction, indication = null) { onTap() }` + `.pressScale(interaction, 0.88f)`；tintColor 动画 `tween(200)` → `Motion.springSnap`
2. **ReButton**: 同上加 interaction；`.pressScale(interaction, 0.97f)`（primary 实心 accent 保留——对比度底线）；非 primary 加 `glassBorder(RoundedCornerShape(Dimens.radius))` 提质感
3. **ReChip**: 选中态从实心 accent 底改为中性克制着色：`background(if (selected) colors.panelHi else colors.panelHi.copy(alpha = 0.55f))` + `then(if (selected) Modifier.border(1.dp, colors.accent.copy(alpha = 0.55f), shape))`；文字 `if (selected) colors.accent else colors.text`；加 pressScale(0.95)
4. **ReSwitch**: checked 实心 accent 保留；thumb 位置加 spring 动画：`val frac by animateFloatAsState(if (checked) 1f else 0f, Motion.springSnap)`，thumb 用 `Box(Modifier.offset(x = (frac * 18).dp))` 替代 alignment 切换
5. **ReMenuItem**: pressed 背景 `colors.accent.copy(alpha = 0.15f)` → `colors.panelHi.copy(alpha = 0.95f)`；pressed 边框 `accent.copy(0.4f)` → `accent.copy(0.3f)`；icon/text pressed accent 保留；加 pressScale(0.96)（interaction 化 clickable）
6. **RePanel**: 出入场 tween(250)/tween(200) → 入场 `slideInVertically(Motion.enterSpring()) { it }`，出场保留 `tween(200, FastOutLinearInEasing)`
7. **SliderFineTunePopup**:
   - hazeChild 内联 HazeStyle → `style = Glass.popupStyle(popupAlpha.coerceIn(0.05f, 0.98f))`，gating 条件加 `&& com.reverie.paint.ui.theme.deviceSupportsBlur` 不需要（VM 已收口）
   - `.border(1.dp, colors.border, …)` → `.glassBorder(RoundedCornerShape(18.dp))`
   - 出入场 tween 220/160 → 入场三通道 `Motion.enterSpring()`，出场保留 tween(160)
   - 左右 step 按钮 scale 0.86 手写 spring → `pressScale(interaction, 0.90f)`
   - Value Pill 与「存入当前」chip：`background(colors.accent.copy(alpha = 0.12f))` → `background(colors.panelHi)` + 文字保持 accent（大面积 tint 中性化）
8. **ReVerticalSlider tooltip**: shadow spotColor `colors.accent.copy(0.25f)` → `Color.Black.copy(0.25f)`
9. 清理不再使用的 import（HazeStyle/HazeTint 若无残留使用）

**Steps:** 逐项修改 → compileDebugKotlin PASS → Commit: `feat(ui): ReComponents 接入液态动效与玻璃边框, 选中态品牌色克制化`

### Task 4: 全部浮层 HazeStyle 迁移 Glass 工厂

**Files (Modify):** TopBar.kt:64 / BrushPanel.kt:213 / LayerPanel.kt:223 / ToolRail.kt:124,219 / AllToolsPanel.kt:84 / SettingsPanel.kt:103 → `Glass.barStyle(opacity…)`；ColorPanel.kt:193 / ToolFloatPanel.kt:93 / ReferenceWindow.kt:119 / ReComponents SliderFineTunePopup(Task 3 已做) → popup；HomeBottomBar.kt:77 → `Glass.barStyle()` 固定值统一（删除不一致的 0.75 tint 与实心 bg）

每处形如：
```kotlin
style = HazeStyle(backgroundColor = Morandi.panel.copy(alpha = opacity.coerceIn(0.05f, 0.98f)), …)
```
→
```kotlin
style = Glass.barStyle(opacity.coerceIn(0.05f, 0.98f))
```
（ToolRail 用 `opacity.toFloat()`；HomeBottomBar 直接 `Glass.barStyle()`）

同时给各浮层外框 `.border(1.dp, colors.border/border.copy(…), shape)`（如存在且为纯色）→ `.glassBorder(shape)`；清理各文件多余 HazeStyle/HazeTint import。

**Steps:** 批量替换 → compileDebugKotlin PASS → Commit: `refactor(ui): 全部浮层毛玻璃样式收敛至 Glass 工厂并升级玻璃描边`

### Task 5: 面板出入场 spring 统一

**Files (Modify):**
- PaintingPage.kt: L1202-1242 四向面板、L1265-1272 左抽屉、L1303-1304 居中弹窗、L1319-1342 右侧表 —— 入场 `fadeIn(Motion.enterSpring()) + slideIn/…(Motion.enterSpring())`，出场一律保留原时长 tween（≤200ms 快出）
- ReferenceWindow.kt: L336-337, L358-359 同规则
- LayerPanel.kt L245-249 / BrushPanel.kt L231-235 内部 tab 切换 tween(180) **保留不动**

**Steps:** → compileDebugKotlin PASS → Commit: `feat(ui): 面板出入场统一弹簧动效(柔入快出)`

### Task 6: 大面积 accent 扫尾

**Steps:**
- [ ] `rg -n 'accent\.copy\(alpha = 0\.[3-9]' app/src/main/java/com/reverie/paint/ui` 与 `rg -n 'background\((colors|Morandi)\.accent\)'` 全列
- [ ] 逐处判断：功能性强状态（slider 填充/switch/取色当前色/选中指示）保留；装饰性大面积 tint 降 alpha 至 ≤0.10 或换 panelHi
- [ ] compileDebugKotlin PASS → Commit: `refactor(ui): 清理装饰性大面积品牌蓝着色`

### Task 7: 最终验证

- [ ] `./gradlew :app:compileDebugKotlin :app:lintDebug assembleDebug` 全绿
- [ ] 回归审查：对照 spec §7 风险清单逐一检查（API<31 降级路径、零分配、手势未触碰、主页布局未变）
