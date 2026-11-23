/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel
import kotlin.math.hypot
import com.reverie.paint.core.executeStylusAction

/**
 * Enhanced fallback adapter for generic Android styluses, Wacom EMR digitizers,
 * Ugee / XP-Pen digitizers, and third-party active capacitive pens adhering to standard Android touch/pen protocols.
 * Supports primary barrel button, secondary button, tail button, and hold-to-erase.
 */
class GenericStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.GENERIC

    private var isPrimaryCurrentlyDown: Boolean = false
    private var isSecondaryCurrentlyDown: Boolean = false

    private var primaryDownTime: Long = 0L
    private var primaryDownX: Float = 0f
    private var primaryDownY: Float = 0f
    private var strokeHappenedSincePrimaryPress: Boolean = false
    private var lastPrimaryReleaseTime: Long = 0L

    private var secondaryDownTime: Long = 0L
    private var secondaryDownX: Float = 0f
    private var secondaryDownY: Float = 0f
    private var strokeHappenedSinceSecondaryPress: Boolean = false
    private var lastSecondaryReleaseTime: Long = 0L

    companion object {
        private const val MIN_CLICK_INTERVAL_MS = 150L
        private const val STROKE_DRAG_THRESHOLD_PX = 24f
    }

    override val isSideButtonPressed: Boolean
        get() = isPrimaryCurrentlyDown || isSecondaryCurrentlyDown

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected {
        return StylusDeviceDetected(
            brand = StylusBrand.GENERIC,
            isCurrentDeviceSupported = true,
            isConnected = vm.genericStylusEnabled,
            deviceName = if (vm.genericStylusEnabled) "通用触控手写笔 (已启用按键映射)" else "通用触控手写笔 (标准触控协议·未启用按键)",
        )
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (!vm.genericStylusEnabled) return false
        val keyCode = event.keyCode
        val now = SystemClock.uptimeMillis()

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_A,
                304,
                308 -> {
                    isPrimaryCurrentlyDown = true
                    return true
                }
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_B,
                305,
                309,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                310,
                311 -> {
                    isSecondaryCurrentlyDown = true
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                KeyEvent.KEYCODE_BUTTON_1,
                KeyEvent.KEYCODE_BUTTON_A,
                304,
                308 -> {
                    return onPrimaryButtonReleased(now, vm, feedbackManager)
                }
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                KeyEvent.KEYCODE_BUTTON_2,
                KeyEvent.KEYCODE_BUTTON_B,
                305,
                309,
                KeyEvent.KEYCODE_BUTTON_3,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                310,
                311 -> {
                    return onSecondaryButtonReleased(now, vm, feedbackManager)
                }
            }
        }
        return false
    }

    override fun onStylusMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (!vm.genericStylusEnabled) return false

        val buttonState = event.buttonState
        val action = event.actionMasked
        val actionBtn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) event.actionButton else 0

        // 识别设备按键通道：
        // 标准手写笔专用按键位 (BUTTON_STYLUS_PRIMARY = 32, BUTTON_STYLUS_SECONDARY = 64)
        // 数位板 / EMR 驱动兼容键位 (BUTTON_SECONDARY = 2, BUTTON_TERTIARY = 4 / BACK / FORWARD)
        // 注意：绝不将 BUTTON_PRIMARY (1) 视作侧键，因其在触控协议中对应笔尖触屏 (BTN_TOUCH / 左键)
        val hasStylusSpecific = (buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0 ||
                actionBtn == MotionEvent.BUTTON_STYLUS_PRIMARY || actionBtn == MotionEvent.BUTTON_STYLUS_SECONDARY

        val isPrimaryBtnDown = if (hasStylusSpecific) {
            (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                    (action == MotionEvent.ACTION_BUTTON_PRESS && actionBtn == MotionEvent.BUTTON_STYLUS_PRIMARY)
        } else {
            (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                    (action == MotionEvent.ACTION_BUTTON_PRESS && actionBtn == MotionEvent.BUTTON_SECONDARY)
        }

        val isSecondaryBtnDown = if (hasStylusSpecific) {
            (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                    (action == MotionEvent.ACTION_BUTTON_PRESS && actionBtn == MotionEvent.BUTTON_STYLUS_SECONDARY)
        } else {
            (buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_FORWARD)) != 0 ||
                    (action == MotionEvent.ACTION_BUTTON_PRESS && (actionBtn == MotionEvent.BUTTON_TERTIARY || actionBtn == MotionEvent.BUTTON_BACK || actionBtn == MotionEvent.BUTTON_FORWARD))
        }

        val now = SystemClock.uptimeMillis()
        var handled = false

        // 1. Primary button (下侧键 / 笔身第 1 键)
        if (isPrimaryBtnDown && !isPrimaryCurrentlyDown) {
            if (now - lastPrimaryReleaseTime >= MIN_CLICK_INTERVAL_MS) {
                isPrimaryCurrentlyDown = true
                primaryDownTime = now
                primaryDownX = event.x
                primaryDownY = event.y
                strokeHappenedSincePrimaryPress = false
                handled = true
            }
        } else if (isPrimaryBtnDown && isPrimaryCurrentlyDown) {
            // 仅在真实带压感运笔且位移超过阈值时判定为在画临时橡皮笔画
            if (action == MotionEvent.ACTION_MOVE && event.getPressure(0) > 0.01f) {
                val dx = event.x - primaryDownX
                val dy = event.y - primaryDownY
                if (hypot(dx, dy) > STROKE_DRAG_THRESHOLD_PX) {
                    strokeHappenedSincePrimaryPress = true
                }
            }
        } else if (!isPrimaryBtnDown && isPrimaryCurrentlyDown) {
            if (onPrimaryButtonReleased(now, vm, feedbackManager)) {
                handled = true
            }
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE &&
            (actionBtn == MotionEvent.BUTTON_STYLUS_PRIMARY || (!hasStylusSpecific && actionBtn == MotionEvent.BUTTON_SECONDARY))
        ) {
            if (onPrimaryButtonReleased(now, vm, feedbackManager)) {
                handled = true
            }
        }

        // 2. Secondary button (上侧键 / 笔身第 2 键 / 尾部按键)
        if (isSecondaryBtnDown && !isSecondaryCurrentlyDown) {
            if (now - lastSecondaryReleaseTime >= MIN_CLICK_INTERVAL_MS) {
                isSecondaryCurrentlyDown = true
                secondaryDownTime = now
                secondaryDownX = event.x
                secondaryDownY = event.y
                strokeHappenedSinceSecondaryPress = false
                handled = true
            }
        } else if (isSecondaryBtnDown && isSecondaryCurrentlyDown) {
            if (action == MotionEvent.ACTION_MOVE && event.getPressure(0) > 0.01f) {
                val dx = event.x - secondaryDownX
                val dy = event.y - secondaryDownY
                if (hypot(dx, dy) > STROKE_DRAG_THRESHOLD_PX) {
                    strokeHappenedSinceSecondaryPress = true
                }
            }
        } else if (!isSecondaryBtnDown && isSecondaryCurrentlyDown) {
            if (onSecondaryButtonReleased(now, vm, feedbackManager)) {
                handled = true
            }
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE &&
            (actionBtn == MotionEvent.BUTTON_STYLUS_SECONDARY || (!hasStylusSpecific && (actionBtn == MotionEvent.BUTTON_TERTIARY || actionBtn == MotionEvent.BUTTON_BACK || actionBtn == MotionEvent.BUTTON_FORWARD)))
        ) {
            if (onSecondaryButtonReleased(now, vm, feedbackManager)) {
                handled = true
            }
        }

        return handled
    }

    private fun onPrimaryButtonReleased(
        now: Long,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        isPrimaryCurrentlyDown = false
        if (now - lastPrimaryReleaseTime < MIN_CLICK_INTERVAL_MS) {
            return true
        }
        lastPrimaryReleaseTime = now

        // 若按住侧键时在屏幕上画过实质性橡皮笔画且开启了临时橡皮功能，则抑制松开时的动作映射
        if (strokeHappenedSincePrimaryPress && vm.genericSideButtonErase) {
            strokeHappenedSincePrimaryPress = false
            return true
        }
        strokeHappenedSincePrimaryPress = false

        val action = StylusAction.fromActionId(vm.genericPrimaryButtonAction)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    private fun onSecondaryButtonReleased(
        now: Long,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        isSecondaryCurrentlyDown = false
        if (now - lastSecondaryReleaseTime < MIN_CLICK_INTERVAL_MS) {
            return true
        }
        lastSecondaryReleaseTime = now

        if (strokeHappenedSinceSecondaryPress && vm.genericSideButtonErase) {
            strokeHappenedSinceSecondaryPress = false
            return true
        }
        strokeHappenedSinceSecondaryPress = false

        val action = StylusAction.fromActionId(vm.genericSecondaryButtonAction)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    override fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        val now = SystemClock.uptimeMillis()
        if (isPrimaryCurrentlyDown) {
            onPrimaryButtonReleased(now, vm, feedbackManager)
        }
        if (isSecondaryCurrentlyDown) {
            onSecondaryButtonReleased(now, vm, feedbackManager)
        }
        strokeHappenedSincePrimaryPress = false
        strokeHappenedSinceSecondaryPress = false
    }
}
