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
    // Samsung Notes 语义: 侧键按住期间发生过落笔(临时橡皮笔画), 松键时不触发单击/长按动作
    private var strokeHappenedSincePress: Boolean = false
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
            strokeHappenedSincePress = false
            return false
        } else if (isPrimaryBtnDown && isButtonCurrentlyDown) {
            // 侧键按住期间笔尖接触屏幕: 该笔是临时橡皮, 松键不应再触发动作
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                strokeHappenedSincePress = true
            }
            return false
        } else if (!isPrimaryBtnDown && isButtonCurrentlyDown) {
            return onSideButtonReleased(now, vm, feedbackManager)
        }
        return false
    }

    /**
     * Shared release-edge handling for the S Pen side button: classifies the press as
     * long-press / double-click / single-click and dispatches the configured action.
     * Called from both the touch/hover motion path and the hover-exit reset path.
     */
    private fun onSideButtonReleased(
        now: Long,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        isButtonCurrentlyDown = false
        val pressDuration = now - lastButtonDownTime
        val timeSinceLastRelease = now - lastButtonReleaseTime
        lastButtonReleaseTime = now

        // 按住侧键画过临时橡皮笔画: 抑制动作 (Samsung Notes 标准行为)
        if (strokeHappenedSincePress) {
            strokeHappenedSincePress = false
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
            buttonClickCount = 0
            return false
        }

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
        return false
    }

    /**
     * S Pen left the hover field while the side button was tracked as down
     * (e.g. user pressed the button in mid-air and pulled the pen away).
     * Flush the pending press so the state machine does not stay latched.
     */
    override fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        if (isButtonCurrentlyDown) {
            onSideButtonReleased(SystemClock.uptimeMillis(), vm, feedbackManager)
        }
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
