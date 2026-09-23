/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Dedicated Stylus Adapter for HUAWEI M-Pencil (1st, 2nd, 3rd Gen NearLink) and M-Pen series.
 * Listens for system double-tap broadcast intents, detects hardware button events,
 * and handles side-button hold temporary erase actions.
 */
class HuaweiStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.HUAWEI_MPENCIL

    companion object {
        const val ACTION_HUAWEI_BUTTON_DOUBLE_PRESSED = "com.huawei.stylus.action.BUTTON_DOUBLE_PRESSED"
        const val ACTION_HUAWEI_DOUBLE_CLICK = "com.huawei.stylus.action.DOUBLE_CLICK"
        const val ACTION_HUAWEI_LEGACY_DOUBLE_PRESSED = "com.huawei.android.stylus.action.BUTTON_DOUBLE_PRESSED"

        private const val LONG_PRESS_THRESHOLD_MS = 400L
        private const val DOUBLE_CLICK_TIMEOUT_MS = 280L
    }

    private var isReceiverRegistered = false
    private var currentVm: PaintViewModel? = null
    private var currentFeedbackManager: StylusFeedbackManager? = null

    private var lastButtonDownTime: Long = 0L
    private var lastButtonReleaseTime: Long = 0L
    private var isButtonCurrentlyDown: Boolean = false
    private var buttonClickCount: Int = 0
    private var strokeHappenedSincePress: Boolean = false
    private var pendingSingleClickRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val stylusBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val vm = currentVm ?: return
            val fm = currentFeedbackManager
            when (action) {
                ACTION_HUAWEI_BUTTON_DOUBLE_PRESSED,
                ACTION_HUAWEI_DOUBLE_CLICK,
                ACTION_HUAWEI_LEGACY_DOUBLE_PRESSED -> {
                    handleDoubleTap(vm, fm)
                }
            }
        }
    }

    override fun register(
        context: Context,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ) {
        currentVm = vm
        currentFeedbackManager = feedbackManager
        registerBroadcastReceiver(context)
    }

    override fun unregister(context: Context) {
        unregisterBroadcastReceiver(context)
        pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
        pendingSingleClickRunnable = null
        currentVm = null
        currentFeedbackManager = null
    }

    private fun registerBroadcastReceiver(context: Context) {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_HUAWEI_BUTTON_DOUBLE_PRESSED)
            addAction(ACTION_HUAWEI_DOUBLE_CLICK)
            addAction(ACTION_HUAWEI_LEGACY_DOUBLE_PRESSED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(stylusBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(stylusBroadcastReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (_: Throwable) {}
    }

    private fun unregisterBroadcastReceiver(context: Context) {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(stylusBroadcastReceiver)
        } catch (_: Throwable) {}
        isReceiverRegistered = false
    }

    fun detectModel(context: Context): HuaweiPencilModel {
        val model = Build.MODEL.uppercase()
        val device = Build.DEVICE.uppercase()
        val product = Build.PRODUCT.uppercase()

        // Detect NearLink generation: MatePad Pro 13.2, MatePad 11.5S, MatePad Pro 11 2024, etc.
        val isNearLinkDevice = model.contains("11.5S") || model.contains("13.2") ||
                model.contains("PCE") || model.contains("WGR") || model.contains("DBX") ||
                product.contains("NEARLINK") || device.contains("NEARLINK")

        return if (isNearLinkDevice) {
            HuaweiPencilModel.GEN3_NEARLINK
        } else {
            HuaweiPencilModel.GEN2
        }
    }

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isHuaweiOrHonor = manufacturer.contains("huawei") || brandName.contains("huawei") ||
                manufacturer.contains("honor") || brandName.contains("honor")

        var stylusConnected = false
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
                        if (name.contains("m-pencil") || name.contains("mpencil") ||
                            name.contains("m-pen") || name.contains("huawei") || name.contains("honor")
                        ) {
                            stylusConnected = true
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        val detectedModel = detectModel(context)
        return StylusDeviceDetected(
            brand = StylusBrand.HUAWEI_MPENCIL,
            isCurrentDeviceSupported = isHuaweiOrHonor,
            isConnected = stylusConnected || isHuaweiOrHonor,
            deviceName = if (isHuaweiOrHonor) {
                "HUAWEI M-Pencil (${detectedModel.editionName} · ${Build.MODEL})"
            } else {
                "HUAWEI M-Pencil"
            },
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
            // If the pen touches the screen while the side button is held down, it's a temporary eraser stroke
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

    private fun onSideButtonReleased(
        now: Long,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        isButtonCurrentlyDown = false
        val pressDuration = now - lastButtonDownTime

        // If stroke happened during hold, skip dispatching click/long-press actions
        if (strokeHappenedSincePress) {
            buttonClickCount = 0
            return true
        }

        if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
            buttonClickCount = 0
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
            return handleLongPress(vm, feedbackManager)
        }

        val timeSinceLastRelease = now - lastButtonReleaseTime
        lastButtonReleaseTime = now

        if (timeSinceLastRelease < DOUBLE_CLICK_TIMEOUT_MS && buttonClickCount >= 1) {
            buttonClickCount = 0
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
            return handleDoubleTap(vm, feedbackManager)
        } else {
            buttonClickCount = 1
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            val singleClickTask = Runnable {
                buttonClickCount = 0
                handleSingleClick(vm, feedbackManager)
            }
            pendingSingleClickRunnable = singleClickTask
            handler.postDelayed(singleClickTask, DOUBLE_CLICK_TIMEOUT_MS)
            return true
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
                    return handleSingleClick(vm, feedbackManager)
                }
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY -> {
                    return handleDoubleTap(vm, feedbackManager)
                }
            }
        }
        return false
    }

    override fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        if (isButtonCurrentlyDown) {
            isButtonCurrentlyDown = false
            strokeHappenedSincePress = false
            buttonClickCount = 0
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
        }
    }

    fun handleDoubleTap(vm: PaintViewModel, feedbackManager: StylusFeedbackManager?): Boolean {
        val actionId = vm.huaweiDoubleTapAction
        if (actionId.trim().equals("none", ignoreCase = true)) return false
        val action = StylusAction.fromActionId(actionId)
        if (action != StylusAction.NONE) {
            if (vm.huaweiHapticsEnabled) {
                feedbackManager?.triggerActionConfirmation()
            }
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun handleSingleClick(vm: PaintViewModel, feedbackManager: StylusFeedbackManager?): Boolean {
        val actionId = vm.huaweiSingleClickAction
        if (actionId.trim().equals("none", ignoreCase = true)) return false
        val action = StylusAction.fromActionId(actionId)
        if (action != StylusAction.NONE) {
            if (vm.huaweiHapticsEnabled) {
                feedbackManager?.triggerActionConfirmation()
            }
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun handleLongPress(vm: PaintViewModel, feedbackManager: StylusFeedbackManager?): Boolean {
        val actionId = vm.huaweiLongPressAction
        if (actionId.trim().equals("none", ignoreCase = true)) return false
        val action = StylusAction.fromActionId(actionId)
        if (action != StylusAction.NONE) {
            if (vm.huaweiHapticsEnabled) {
                feedbackManager?.triggerActionConfirmation()
            }
            vm.executeStylusAction(action)
            return true
        }
        return false
    }
}
