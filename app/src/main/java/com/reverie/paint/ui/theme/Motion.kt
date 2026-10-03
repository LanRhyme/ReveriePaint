/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.reverie.paint.model.UiAnimationSpeed

/**
 * 统一动效 token。
 * 支持根据用户设置的 [currentSpeed]（标准 / 极速 <= 0.1s / 关闭）动态调整动效曲线与时长。
 */
object Motion {
    var currentSpeed: UiAnimationSpeed by mutableStateOf(UiAnimationSpeed.NORMAL)

    val snapBouncy: AnimationSpec<Float>
        get() = when (currentSpeed) {
            UiAnimationSpeed.NORMAL -> spring(dampingRatio = 0.55f, stiffness = 400f)
            UiAnimationSpeed.FAST -> spring(dampingRatio = 0.70f, stiffness = 1200f)
            UiAnimationSpeed.OFF -> snap()
        }

    val springSoft: AnimationSpec<Float>
        get() = when (currentSpeed) {
            UiAnimationSpeed.NORMAL -> spring(dampingRatio = 0.85f, stiffness = 350f)
            UiAnimationSpeed.FAST -> spring(dampingRatio = 0.90f, stiffness = 1200f)
            UiAnimationSpeed.OFF -> snap()
        }

    val springSnap: AnimationSpec<Float>
        get() = when (currentSpeed) {
            UiAnimationSpeed.NORMAL -> spring(dampingRatio = 0.90f, stiffness = 500f)
            UiAnimationSpeed.FAST -> spring(dampingRatio = 0.95f, stiffness = 1500f)
            UiAnimationSpeed.OFF -> snap()
        }

    /** 果冻回弹：低阻尼晃 2-3 周期（软体容器松手回正） */
    val springJelly: AnimationSpec<Float>
        get() = when (currentSpeed) {
            UiAnimationSpeed.NORMAL -> spring(dampingRatio = 0.45f, stiffness = 260f)
            UiAnimationSpeed.FAST -> spring(dampingRatio = 0.65f, stiffness = 1000f)
            UiAnimationSpeed.OFF -> snap()
        }

    /** 面板出入场入场用泛型版本 */
    fun <T> enterSpring(): FiniteAnimationSpec<T> = when (currentSpeed) {
        UiAnimationSpeed.NORMAL -> spring(dampingRatio = 0.85f, stiffness = 350f)
        UiAnimationSpeed.FAST -> spring(dampingRatio = 0.90f, stiffness = 1200f)
        UiAnimationSpeed.OFF -> snap()
    }

    /** 面板出场退场用泛型版本（标准模式下保留原先 150~200ms tween，极速模式为 80ms <= 0.1s，关闭模式直接 snap） */
    fun <T> exitTween(baseMillis: Int = 180): FiniteAnimationSpec<T> = when (currentSpeed) {
        UiAnimationSpeed.NORMAL -> tween(baseMillis)
        UiAnimationSpeed.FAST -> tween(80)
        UiAnimationSpeed.OFF -> snap()
    }
}

