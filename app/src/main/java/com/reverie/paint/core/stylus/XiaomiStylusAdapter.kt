/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Extensible Stylus Adapter for Xiaomi Smart Pen (灵感触控笔) & Focus Pen (焦点触控笔).
 * Handles primary (writing/palette) and secondary (eraser/undo) physical stylus buttons.
 */
class XiaomiStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.XIAOMI_SMARTPEN

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isXiaomiDevice = manufacturer.contains("xiaomi") || brandName.contains("xiaomi") ||
                manufacturer.contains("redmi") || brandName.contains("redmi")

        var isConnected = false
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            if (inputManager != null) {
                for (id in inputManager.inputDeviceIds) {
                    val dev = inputManager.getInputDevice(id) ?: continue
                    val sources = dev.sources
                    val hasStylusSource = (sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS ||
                            (sources and InputDevice.SOURCE_BLUETOOTH_STYLUS) == InputDevice.SOURCE_BLUETOOTH_STYLUS
                    val name = dev.name.lowercase()

                    if (hasStylusSource || name.contains("pen") || name.contains("stylus")) {
                        if (name.contains("xiaomi") || name.contains("smartpen") || name.contains("focus")) {
                            isConnected = true
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return StylusDeviceDetected(
            brand = StylusBrand.XIAOMI_SMARTPEN,
            isCurrentDeviceSupported = isXiaomiDevice,
            isConnected = isConnected || isXiaomiDevice,
            deviceName = if (isXiaomiDevice) "Xiaomi Smart Pen (${Build.MODEL})" else "小米 触控笔",
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
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY -> {
                    // Secondary button default: toggle eraser
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(StylusAction.TOGGLE_ERASER)
                    return true
                }
            }
        }
        return false
    }
}
