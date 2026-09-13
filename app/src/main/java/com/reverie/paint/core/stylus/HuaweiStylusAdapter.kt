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
 * Extensible Stylus Adapter for Huawei M-Pencil (NearLink 星闪 / Bluetooth) series.
 * Prepared for HarmonyOS / EMUI stealth touch double-tap and high-sampling-rate input.
 */
class HuaweiStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.HUAWEI_MPENCIL

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isHuaweiDevice = manufacturer.contains("huawei") || brandName.contains("huawei") ||
                manufacturer.contains("honor") || brandName.contains("honor")

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
                        if (name.contains("m-pencil") || name.contains("huawei") || name.contains("nearlink")) {
                            isConnected = true
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return StylusDeviceDetected(
            brand = StylusBrand.HUAWEI_MPENCIL,
            isCurrentDeviceSupported = isHuaweiDevice,
            isConnected = isConnected || isHuaweiDevice,
            deviceName = if (isHuaweiDevice) "HUAWEI M-Pencil (${Build.MODEL})" else "华为 M-Pencil",
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
                    // Huawei double-tap side touch typically emits primary stylus button
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
