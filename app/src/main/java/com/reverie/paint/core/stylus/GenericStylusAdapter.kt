/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Fallback adapter for generic Android styluses compliant with standard Android touch/pen protocols.
 */
class GenericStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.GENERIC

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected {
        return StylusDeviceDetected(
            brand = StylusBrand.GENERIC,
            isCurrentDeviceSupported = true,
            isConnected = true,
            deviceName = "通用触控笔 (标准 Android 触控协议)",
        )
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY -> {
                    val action = StylusAction.fromActionId(vm.oppoDoubleTapAction)
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
}
