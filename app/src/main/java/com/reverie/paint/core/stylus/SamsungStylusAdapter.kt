/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Dedicated Stylus Adapter for Samsung Galaxy S Pen and Wacom EMR digitizers.
 * Supports side button single-click, double-click, and long-press event detection.
 */
class SamsungStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.SAMSUNG_SPEN

    private var lastButtonDownTime: Long = 0L
    private var lastButtonReleaseTime: Long = 0L
    private var isButtonCurrentlyDown: Boolean = false
    private var buttonClickCount: Int = 0
    private var pendingSingleClickRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isSamsungDevice = manufacturer.contains("samsung") || brandName.contains("samsung")

        var samsungStylusConnected = false
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
                        if (name.contains("spen") || name.contains("s-pen") || name.contains("samsung") || name.contains("wacom")) {
                            samsungStylusConnected = true
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return StylusDeviceDetected(
            brand = StylusBrand.SAMSUNG_SPEN,
            isCurrentDeviceSupported = isSamsungDevice,
            isConnected = samsungStylusConnected || isSamsungDevice,
            deviceName = if (isSamsungDevice) "Samsung S Pen (${Build.MODEL})" else "三星 S Pen",
        )
    }

    override fun onStylusMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        val buttonState = event.buttonState
        val isPrimaryBtnDown = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_SECONDARY) != 0

        val now = SystemClock.uptimeMillis()

        if (isPrimaryBtnDown && !isButtonCurrentlyDown) {
            isButtonCurrentlyDown = true
            lastButtonDownTime = now
            return false
        } else if (!isPrimaryBtnDown && isButtonCurrentlyDown) {
            isButtonCurrentlyDown = false
            val pressDuration = now - lastButtonDownTime
            val timeSinceLastRelease = now - lastButtonReleaseTime
            lastButtonReleaseTime = now

            if (pressDuration > 450L) {
                pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                pendingSingleClickRunnable = null
                buttonClickCount = 0
                return handleLongPress(vm, feedbackManager)
            }

            if (timeSinceLastRelease < 320L) {
                pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                pendingSingleClickRunnable = null
                buttonClickCount = 0
                return handleDoubleClick(vm, feedbackManager)
            } else {
                buttonClickCount = 1
                pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (buttonClickCount == 1) {
                        buttonClickCount = 0
                        handleSingleClick(vm, feedbackManager)
                    }
                }
                pendingSingleClickRunnable = runnable
                handler.postDelayed(runnable, 280L)
            }
        }
        return false
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY -> {
                    return handleDoubleClick(vm, feedbackManager)
                }
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY -> {
                    return handleSingleClick(vm, feedbackManager)
                }
            }
        }
        return false
    }

    fun handleSingleClick(vm: PaintViewModel, feedbackManager: StylusFeedbackManager): Boolean {
        val action = StylusAction.fromActionId(vm.samsungSingleClickAction)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun handleDoubleClick(vm: PaintViewModel, feedbackManager: StylusFeedbackManager): Boolean {
        val action = StylusAction.fromActionId(vm.samsungDoubleClickAction)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun handleLongPress(vm: PaintViewModel, feedbackManager: StylusFeedbackManager): Boolean {
        val action = StylusAction.fromActionId(vm.samsungLongPressAction)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    override fun release() {
        pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
        pendingSingleClickRunnable = null
    }
}
