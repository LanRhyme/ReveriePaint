/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** HyperOS 的 Pro 手势键码为 194–197，不能按普通笔的侧键点击次数推断。 */
enum class XiaomiTouchFilmGesture(val keyCode: Int) {
    SQUEEZE(194),
    DOUBLE_TAP(195),
    SLIDE_UP(196),
    SLIDE_DOWN(197);

    companion object {
        fun fromKeyCode(keyCode: Int): XiaomiTouchFilmGesture? = when (keyCode) {
            194 -> SQUEEZE
            195 -> DOUBLE_TAP
            196 -> SLIDE_UP
            197 -> SLIDE_DOWN
            else -> null
        }
    }
}

/** 同一按键可能经 Activity 和 View 再次派发；抬起、取消及生命周期切换均须清理。 */
class XiaomiTouchFilmKeys {
    private var pressedMask = 0

    fun onDown(keyCode: Int, repeatCount: Int): XiaomiTouchFilmGesture? {
        val gesture = XiaomiTouchFilmGesture.fromKeyCode(keyCode) ?: return null
        val bit = 1 shl gesture.ordinal
        if (repeatCount != 0 || pressedMask and bit != 0) return null
        pressedMask = pressedMask or bit
        return gesture
    }

    fun onUp(keyCode: Int) {
        val gesture = XiaomiTouchFilmGesture.fromKeyCode(keyCode) ?: return
        pressedMask = pressedMask and (1 shl gesture.ordinal).inv()
    }

    fun reset() {
        pressedMask = 0
    }
}
