/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.reverie.paint.core.PaintViewModel
import java.lang.ref.WeakReference

/**
 * Dedicated Stylus Adapter for HUAWEI M-Pencil (1st, 2nd, 3rd Gen NearLink) and M-Pen series.
 * Listens for system double-tap broadcast intents bound to the focused Activity window,
 * aggregates hardware key double-tap events from NearLink drivers, detects motion events,
 * and handles side-button hold temporary erase actions.
 */
class HuaweiStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.HUAWEI_MPENCIL

    companion object {
        private const val TAG = "ReverieHuaweiStylus"

        // Official & Community Huawei Stylus Broadcast Actions
        const val ACTION_HUAWEI_BUTTON_DOUBLE_PRESSED = "com.huawei.stylus.action.BUTTON_DOUBLE_PRESSED"
        const val ACTION_HUAWEI_DOUBLE_CLICK = "com.huawei.stylus.action.DOUBLE_CLICK"
        const val ACTION_HUAWEI_LEGACY_DOUBLE_PRESSED = "com.huawei.android.stylus.action.BUTTON_DOUBLE_PRESSED"
        const val ACTION_HUAWEI_DOUBLE_TAP = "com.huawei.stylus.action.DOUBLE_TAP"
        const val ACTION_HUAWEI_STYLUS_BUTTON_CLICK = "com.huawei.intent.action.STYLUS_BUTTON_CLICK"
        const val ACTION_HUAWEI_STYLUS_BUTTON_CLICK_ALT = "huawei.intent.action.STYLUS_BUTTON_CLICK"
        const val ACTION_HUAWEI_BUTTON_CLICK = "com.huawei.stylus.action.BUTTON_CLICK"
        const val ACTION_HUAWEI_BUTTON_PRESSED = "com.huawei.stylus.action.BUTTON_PRESSED"

        // Huawei Global System Settings for Stylus Double-Click
        const val SETTING_DOUBLE_CLICK_SWITCH_MODE = "double_click_switch_mode"
        const val SYSTEM_MODE_CURRENT_AND_ERASER = 0
        const val SYSTEM_MODE_CURRENT_AND_LAST_TOOL = 1
        const val SYSTEM_MODE_SHOW_COLOR_PALETTE = 2
        const val SYSTEM_MODE_DISABLE = 3

        private const val LONG_PRESS_THRESHOLD_MS = 400L
        private const val DOUBLE_CLICK_TIMEOUT_MS = 360L
        private const val DEDUPLICATE_WINDOW_MS = 250L
    }

    private var isReceiverRegistered = false
    private var registeredContextRef: WeakReference<Context>? = null
    private var currentAppContext: Context? = null
    private var currentVm: PaintViewModel? = null
    private var currentFeedbackManager: StylusFeedbackManager? = null

    // Motion event button state tracking
    private var lastButtonDownTime: Long = 0L
    private var lastButtonReleaseTime: Long = 0L
    private var isButtonCurrentlyDown: Boolean = false
    private var buttonClickCount: Int = 0
    private var strokeHappenedSincePress: Boolean = false
    private var pendingSingleClickRunnable: Runnable? = null

    // Hardware key event double-click aggregation state (for NearLink 3rd gen barrel taps)
    private var lastKeyReleaseTime: Long = 0L
    private var keyClickCount: Int = 0
    private var pendingKeySingleClickRunnable: Runnable? = null

    // Deduplication between broadcast intent and hardware key event
    private var lastDoubleTapTriggerTime: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val stylusBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            onBroadcastReceived(action, intent)
        }
    }

    override fun register(
        context: Context,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ) {
        currentAppContext = context.applicationContext
        currentVm = vm
        currentFeedbackManager = feedbackManager
        if (context is Activity) {
            bindBroadcastReceiver(context)
        }
    }

    override fun unregister(context: Context) {
        unbindBroadcastReceiver(context)
        clearPendingTasks()
        currentVm = null
        currentFeedbackManager = null
        currentAppContext = null
    }

    override fun onWindowFocusChanged(activity: Activity, hasFocus: Boolean) {
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, activity=${activity.localClassName}")
        if (hasFocus) {
            bindBroadcastReceiver(activity)
        } else {
            unbindBroadcastReceiver(activity)
        }
    }

    override fun onActivityResume(activity: Activity) {
        Log.d(TAG, "onActivityResume: activity=${activity.localClassName}")
        bindBroadcastReceiver(activity)
    }

    override fun onActivityPause(activity: Activity) {
        Log.d(TAG, "onActivityPause: activity=${activity.localClassName}")
        unbindBroadcastReceiver(activity)
    }

    @Synchronized
    fun bindBroadcastReceiver(targetContext: Context) {
        val existing = registeredContextRef?.get()
        if (isReceiverRegistered && existing == targetContext) {
            return
        }
        if (isReceiverRegistered && existing != null && existing != targetContext) {
            unbindBroadcastReceiver(existing)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_HUAWEI_BUTTON_DOUBLE_PRESSED)
            addAction(ACTION_HUAWEI_DOUBLE_CLICK)
            addAction(ACTION_HUAWEI_LEGACY_DOUBLE_PRESSED)
            addAction(ACTION_HUAWEI_DOUBLE_TAP)
            addAction(ACTION_HUAWEI_STYLUS_BUTTON_CLICK)
            addAction(ACTION_HUAWEI_STYLUS_BUTTON_CLICK_ALT)
            addAction(ACTION_HUAWEI_BUTTON_CLICK)
            addAction(ACTION_HUAWEI_BUTTON_PRESSED)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.registerReceiver(stylusBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    targetContext,
                    stylusBroadcastReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
            isReceiverRegistered = true
            registeredContextRef = WeakReference(targetContext)
            Log.i(TAG, "Huawei stylus broadcast receiver registered on: ${targetContext.javaClass.simpleName}")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register receiver with RECEIVER_EXPORTED, trying fallback: ${e.message}")
            try {
                targetContext.registerReceiver(stylusBroadcastReceiver, filter)
                isReceiverRegistered = true
                registeredContextRef = WeakReference(targetContext)
                Log.i(TAG, "Huawei stylus broadcast receiver registered via fallback")
            } catch (fallbackError: Throwable) {
                Log.e(TAG, "Failed to register Huawei stylus receiver: ${fallbackError.message}")
            }
        }
    }

    @Synchronized
    fun unbindBroadcastReceiver(targetContext: Context?) {
        if (!isReceiverRegistered) return
        val target = targetContext ?: registeredContextRef?.get()
        try {
            target?.unregisterReceiver(stylusBroadcastReceiver)
            Log.i(TAG, "Huawei stylus broadcast receiver unregistered from: ${target?.javaClass?.simpleName}")
        } catch (e: Throwable) {
            Log.d(TAG, "Error unregistering receiver: ${e.message}")
        }
        isReceiverRegistered = false
        registeredContextRef = null
    }

    /**
     * Dispatch point for both dynamic and manifest-declared static receivers.
     */
    fun onBroadcastReceived(action: String, intent: Intent?) {
        val vm = currentVm ?: return
        val fm = currentFeedbackManager

        @Suppress("DEPRECATION")
        val extrasInfo = intent?.extras?.let { bundle ->
            bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" }
        } ?: "none"
        Log.i(TAG, "onBroadcastReceived: action=$action, extras=[$extrasInfo]")

        val count = intent?.getIntExtra("count", intent.getIntExtra("click_count", intent.getIntExtra("clickCount", -1))) ?: -1
        val clickType = intent?.getIntExtra("click_type", intent.getIntExtra("clickType", intent.getIntExtra("type", -1))) ?: -1

        val isDoubleTapAction = when (action) {
            ACTION_HUAWEI_BUTTON_DOUBLE_PRESSED,
            ACTION_HUAWEI_DOUBLE_CLICK,
            ACTION_HUAWEI_LEGACY_DOUBLE_PRESSED,
            ACTION_HUAWEI_DOUBLE_TAP -> true
            else -> (count >= 2) || (clickType == 2)
        }

        if (isDoubleTapAction) {
            handleDoubleTap(vm, fm)
        } else {
            handleSingleClick(vm, fm)
        }
    }

    private fun clearPendingTasks() {
        pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
        pendingSingleClickRunnable = null
        pendingKeySingleClickRunnable?.let { handler.removeCallbacks(it) }
        pendingKeySingleClickRunnable = null
        buttonClickCount = 0
        keyClickCount = 0
        isButtonCurrentlyDown = false
        strokeHappenedSincePress = false
    }

    fun readSystemDoubleClickMode(context: Context): Int {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                SETTING_DOUBLE_CLICK_SWITCH_MODE,
                SYSTEM_MODE_CURRENT_AND_ERASER,
            )
        } catch (_: Throwable) {
            SYSTEM_MODE_CURRENT_AND_ERASER
        }
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

    override fun onGenericMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        return onStylusMotionEvent(event, vm, feedbackManager)
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
            Log.i(TAG, "Motion event double-tap detected (interval=${timeSinceLastRelease}ms)")
            return handleDoubleTap(vm, feedbackManager)
        } else {
            buttonClickCount = 1
            pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
            val singleClickTask = Runnable {
                buttonClickCount = 0
                Log.i(TAG, "Motion event single-tap dispatched")
                handleSingleClick(vm, feedbackManager)
            }
            pendingSingleClickRunnable = singleClickTask
            handler.postDelayed(singleClickTask, DOUBLE_CLICK_TIMEOUT_MS)
            return true
        }
    }

    /**
     * Intercepts physical key events from M-Pencil (such as NearLink 3rd gen barrel touch taps).
     * Implements software double-tap aggregation window so two taps within 360ms fire double-tap
     * instead of two single-clicks.
     */
    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        val keyCode = event.keyCode
        val isTargetKey = keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
                keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
                keyCode == KeyEvent.KEYCODE_BUTTON_2 ||
                keyCode == KeyEvent.KEYCODE_PAGE_UP ||
                keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
                keyCode == KeyEvent.KEYCODE_F19 ||
                keyCode == KeyEvent.KEYCODE_F20

        if (!isTargetKey) return false

        Log.d(TAG, "onStylusKeyEvent: action=${event.action}, keyCode=$keyCode, repeat=${event.repeatCount}")

        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY) {
                Log.i(TAG, "Secondary button pressed -> immediate double tap")
                return handleDoubleTap(vm, feedbackManager)
            }

            val now = SystemClock.uptimeMillis()
            val timeSinceLastKey = now - lastKeyReleaseTime
            lastKeyReleaseTime = now

            if (timeSinceLastKey < DOUBLE_CLICK_TIMEOUT_MS && keyClickCount >= 1) {
                keyClickCount = 0
                pendingKeySingleClickRunnable?.let { handler.removeCallbacks(it) }
                pendingKeySingleClickRunnable = null
                Log.i(TAG, "Hardware key double-tap aggregated within ${timeSinceLastKey}ms (keyCode=$keyCode)")
                return handleDoubleTap(vm, feedbackManager)
            } else {
                keyClickCount = 1
                pendingKeySingleClickRunnable?.let { handler.removeCallbacks(it) }
                val singleTask = Runnable {
                    keyClickCount = 0
                    Log.i(TAG, "Hardware key single-tap dispatched (keyCode=$keyCode)")
                    handleSingleClick(vm, feedbackManager)
                }
                pendingKeySingleClickRunnable = singleTask
                handler.postDelayed(singleTask, DOUBLE_CLICK_TIMEOUT_MS)
                return true
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
        val now = SystemClock.uptimeMillis()
        if (now - lastDoubleTapTriggerTime < DEDUPLICATE_WINDOW_MS) {
            Log.d(TAG, "Double-tap ignored due to deduplication (${now - lastDoubleTapTriggerTime}ms)")
            return true
        }
        lastDoubleTapTriggerTime = now

        val actionId = vm.huaweiDoubleTapAction
        if (actionId.trim().equals("none", ignoreCase = true)) {
            Log.d(TAG, "Double-tap ignored: configured as 'none'")
            return false
        }

        val resolvedActionId = if (actionId.equals("system", ignoreCase = true)) {
            val ctx = registeredContextRef?.get() ?: currentAppContext
            val mode = if (ctx != null) readSystemDoubleClickMode(ctx) else SYSTEM_MODE_CURRENT_AND_ERASER
            when (mode) {
                SYSTEM_MODE_CURRENT_AND_ERASER -> "toggle_eraser"
                SYSTEM_MODE_CURRENT_AND_LAST_TOOL -> "toggle_last_tool"
                SYSTEM_MODE_SHOW_COLOR_PALETTE -> "tool_color"
                SYSTEM_MODE_DISABLE -> "none"
                else -> "toggle_eraser"
            }
        } else {
            actionId
        }

        if (resolvedActionId.trim().equals("none", ignoreCase = true)) return false

        val action = StylusAction.fromActionId(resolvedActionId)
        if (action != StylusAction.NONE) {
            Log.i(TAG, "handleDoubleTap executing action: ${action.name} (actionId=$resolvedActionId)")
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
            Log.i(TAG, "handleSingleClick executing action: ${action.name}")
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
            Log.i(TAG, "handleLongPress executing action: ${action.name}")
            if (vm.huaweiHapticsEnabled) {
                feedbackManager?.triggerActionConfirmation()
            }
            vm.executeStylusAction(action)
            return true
        }
        return false
    }
}
