/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Enhanced fallback adapter for generic Android styluses, Wacom EMR digitizers,
 * and third-party active capacitive pens adhering to standard Android touch/pen protocols.
 * Supports primary barrel button, secondary button, tail button, and hold-to-erase.
 */
class GenericStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.GENERIC

    private var isPrimaryCurrentlyDown: Boolean = false
    private var isSecondaryCurrentlyDown: Boolean = false
    private var strokeHappenedSincePrimaryPress: Boolean = false
    private var strokeHappenedSinceSecondaryPress: Boolean = false

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
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                KeyEvent.KEYCODE_BUTTON_1,
                308 -> {
                    val action = StylusAction.fromActionId(vm.genericPrimaryButtonAction)
                    if (action != StylusAction.NONE) {
                        feedbackManager.triggerActionConfirmation()
                        vm.executeStylusAction(action)
                        return true
                    }
                }
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                KeyEvent.KEYCODE_BUTTON_2,
                309,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL -> {
                    val action = StylusAction.fromActionId(vm.genericSecondaryButtonAction)
                    if (action != StylusAction.NONE) {
                        feedbackManager.triggerActionConfirmation()
                        vm.executeStylusAction(action)
                        return true
                    }
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
        val isPrimaryBtnDown = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val isSecondaryBtnDown = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

        // 1. Primary button (Barrel button 1)
        if (isPrimaryBtnDown && !isPrimaryCurrentlyDown) {
            isPrimaryCurrentlyDown = true
            strokeHappenedSincePrimaryPress = false
        } else if (isPrimaryBtnDown && isPrimaryCurrentlyDown) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                strokeHappenedSincePrimaryPress = true
            }
        } else if (!isPrimaryBtnDown && isPrimaryCurrentlyDown) {
            isPrimaryCurrentlyDown = false
            if (!strokeHappenedSincePrimaryPress) {
                val action = StylusAction.fromActionId(vm.genericPrimaryButtonAction)
                if (action != StylusAction.NONE) {
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(action)
                    return true
                }
            }
        }

        // 2. Secondary button (Barrel button 2 / tail)
        if (isSecondaryBtnDown && !isSecondaryCurrentlyDown) {
            isSecondaryCurrentlyDown = true
            strokeHappenedSinceSecondaryPress = false
        } else if (isSecondaryBtnDown && isSecondaryCurrentlyDown) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                strokeHappenedSinceSecondaryPress = true
            }
        } else if (!isSecondaryBtnDown && isSecondaryCurrentlyDown) {
            isSecondaryCurrentlyDown = false
            if (!strokeHappenedSinceSecondaryPress) {
                val action = StylusAction.fromActionId(vm.genericSecondaryButtonAction)
                if (action != StylusAction.NONE) {
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(action)
                    return true
                }
            }
        }

        return false
    }

    override fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        isPrimaryCurrentlyDown = false
        isSecondaryCurrentlyDown = false
        strokeHappenedSincePrimaryPress = false
        strokeHappenedSinceSecondaryPress = false
    }
}
