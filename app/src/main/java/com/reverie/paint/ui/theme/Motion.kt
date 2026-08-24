/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 统一动效 token。三档弹簧覆盖全部 UI 交互：
 *  - [snapBouncy]: 按压回弹（轻微过冲，液态手感）
 *  - [springSoft]: 面板出入场（柔和滑入；出场仍用短 tween 保证响应速度）
 *  - [springSnap]: 选中切换/开关 thumb（快而稳）
 *
 * 泛型场景（Dp/Offset/Color 向量转换）用 [enterSpring] 或直接
 * `spring(dampingRatio = …, stiffness = …)` 按同参数构造。
 */
object Motion {
    val snapBouncy: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 400f)
    val springSoft: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 350f)
    val springSnap: SpringSpec<Float> = spring(dampingRatio = 0.90f, stiffness = 500f)

    /** 面板出入场入场用泛型版本 */
    fun <T> enterSpring(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 350f)
}
