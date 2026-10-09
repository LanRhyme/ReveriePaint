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
    override val isSideButtonPressed: Boolean get() = isButtonCurrentlyDown

    private var isSupportedDevice: Boolean = run {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        m.contains("samsung") || b.contains("samsung")
    }

    private var lastButtonDownTime: Long = 0L
    private var lastButtonReleaseTime: Long = 0L
    private var isButtonCurrentlyDown: Boolean = false
    private var buttonClickCount: Int = 0
    // Samsung Notes 语义: 侧键按住期间发生过落笔(临时橡皮笔画), 松键时不触发单击/长按动作
    private var strokeHappenedSincePress: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var pendingSingleClickRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        // 防抖门限：低于此间隔视为硬件/系统重复上报(如 MotionEvent 与 KeyEvent 跨通道交叉触发或微动抖动)
        private const val MIN_CLICK_INTERVAL_MS = 80L
        // 双击时间窗口：在此窗口内的第2次释放判定为双击
        private const val DOUBLE_CLICK_TIMEOUT_MS = 280L
        // 长按判定阈值
        private const val LONG_PRESS_TIMEOUT_MS = 450L
    }

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isSamsungDevice = manufacturer.contains("samsung") || brandName.contains("samsung")
        isSupportedDevice = isSamsungDevice

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
        if (!isSupportedDevice) return false

        val buttonState = event.buttonState
        val isPrimaryBtnDown = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_SECONDARY) != 0

        val now = SystemClock.uptimeMillis()

        if (isPrimaryBtnDown && !isButtonCurrentlyDown) {
            // 防抖：刚释放不久（在 MIN_CLICK_INTERVAL_MS 内）的边缘抖动或残留，忽略为重复
            if (now - lastButtonReleaseTime < MIN_CLICK_INTERVAL_MS) {
                return true
            }
            isButtonCurrentlyDown = true
            lastButtonDownTime = now
            strokeHappenedSincePress = false
            downX = event.x
            downY = event.y
            return true
        } else if (isPrimaryBtnDown && isButtonCurrentlyDown) {
            // 侧键按住期间笔尖接触屏幕并有实质性位移(临时橡皮笔画), 松键时不触发单击/长按动作
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_MOVE && event.getPressure(0) > 0.01f) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (kotlin.math.hypot(dx, dy) > 24f) {
                    strokeHappenedSincePress = true
                }
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

        // 核心防重：若距上次释放间隔极短 (< MIN_CLICK_INTERVAL_MS)，必为 MotionEvent 与 KeyEvent 跨通道重复到达，直接静默丢弃
        if (now - lastButtonReleaseTime < MIN_CLICK_INTERVAL_MS) {
            return true
        }

        val pressDuration = now - lastButtonDownTime
        val timeSinceLastRelease = now - lastButtonReleaseTime
        lastButtonReleaseTime = now

        // 按住侧键真正画过临时橡皮笔画: 抑制动作 (Samsung Notes 标准行为)
        if (strokeHappenedSincePress && vm.samsungSideButtonErase) {
            strokeHappenedSincePress = false
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
            buttonClickCount = 0
            lastButtonReleaseTime = 0L
            return true
        }
        strokeHappenedSincePress = false

        if (pressDuration > LONG_PRESS_TIMEOUT_MS) {
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
            buttonClickCount = 0
            lastButtonReleaseTime = 0L
            handleLongPress(vm, feedbackManager)
            return true
        }

        if (buttonClickCount == 1 &&
            timeSinceLastRelease <= DOUBLE_CLICK_TIMEOUT_MS &&
            vm.samsungDoubleClickAction != "none"
        ) {
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingSingleClickRunnable = null
            buttonClickCount = 0
            lastButtonReleaseTime = 0L
            handleDoubleClick(vm, feedbackManager)
            return true
        } else {
            buttonClickCount = 1
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }

            // 若用户未配置双击动作 (none), 则无需等待双击超时判定, 立即派发单击以实现零延迟切换!
            if (vm.samsungDoubleClickAction == "none") {
                buttonClickCount = 0
                lastButtonReleaseTime = 0L
                handleSingleClick(vm, feedbackManager)
                return true
            }

            val runnable = Runnable {
                if (buttonClickCount == 1) {
                    buttonClickCount = 0
                    lastButtonReleaseTime = 0L
                    handleSingleClick(vm, feedbackManager)
                }
            }
            pendingSingleClickRunnable = runnable
            handler.postDelayed(runnable, DOUBLE_CLICK_TIMEOUT_MS)
            return true
        }
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
        if (!isSupportedDevice) return false

        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY &&
            keyCode != KeyEvent.KEYCODE_BUTTON_1 &&
            keyCode != 308
        ) {
            return false
        }

        val now = SystemClock.uptimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                if (now - lastButtonReleaseTime < MIN_CLICK_INTERVAL_MS) {
                    return true
                }
                if (!isButtonCurrentlyDown) {
                    isButtonCurrentlyDown = true
                    lastButtonDownTime = now
                    strokeHappenedSincePress = false
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (isButtonCurrentlyDown) {
                    return onSideButtonReleased(now, vm, feedbackManager)
                } else {
                    if (now - lastButtonReleaseTime < MIN_CLICK_INTERVAL_MS) {
                        return true
                    }
                    return onSideButtonReleased(now, vm, feedbackManager)
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
        buttonClickCount = 0
        lastButtonReleaseTime = 0L
        isButtonCurrentlyDown = false
    }
}
