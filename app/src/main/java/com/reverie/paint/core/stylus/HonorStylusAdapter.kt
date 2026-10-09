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
 * Dedicated Stylus Adapter for HONOR Magic-Pencil (1st, 2nd, 3rd Gen) and Magic-Pen (Foldable Series).
 * Listens for system double-tap broadcast intents bound to the focused Activity window,
 * handles MagicOS system settings sync, motion events, and foldable pen side-button actions.
 */
class HonorStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.HONOR_MAGIC_PENCIL
    override val isSideButtonPressed: Boolean get() = isButtonCurrentlyDown

    companion object {
        private const val TAG = "ReverieHonorStylus"

        // Official & Community Honor Stylus Broadcast Actions
        const val ACTION_HONOR_STYLUS_DOUBLE_CLICK = "com.hihonor.stylus.action.STYLUS_DOUBLE_CLICK"
        const val ACTION_HONOR_DOUBLE_PRESSED = "com.hihonor.android.stylus.action.BUTTON_DOUBLE_PRESSED"
        const val ACTION_HONOR_DOUBLE_CLICK = "com.hihonor.stylus.action.DOUBLE_CLICK"
        const val ACTION_HONOR_DOUBLE_TAP = "com.hihonor.stylus.action.DOUBLE_TAP"
        const val ACTION_HONOR_STYLUS_BUTTON_CLICK = "com.hihonor.intent.action.STYLUS_BUTTON_CLICK"

        // Honor MagicOS Global System Settings for Stylus Double-Click
        const val SETTING_DOUBLE_CLICK_SWITCH_MODE = "double_click_switch_mode"
        const val SYSTEM_MODE_CURRENT_AND_ERASER = 0
        const val SYSTEM_MODE_CURRENT_AND_LAST_TOOL = 1
        const val SYSTEM_MODE_SHOW_COLOR_PALETTE = 2
        const val SYSTEM_MODE_DISABLE = 3

        private const val DOUBLE_CLICK_TIMEOUT_MS = 360L
        private const val DEDUPLICATE_WINDOW_MS = 250L
    }

    private var isSupportedHonorDevice: Boolean = run {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        m.contains("honor") || b.contains("honor")
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

    // Hardware key event tracking
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
        if (hasFocus && !isReceiverRegistered) {
            bindBroadcastReceiver(activity)
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
            addAction(ACTION_HONOR_STYLUS_DOUBLE_CLICK)
            addAction(ACTION_HONOR_DOUBLE_PRESSED)
            addAction(ACTION_HONOR_DOUBLE_CLICK)
            addAction(ACTION_HONOR_DOUBLE_TAP)
            addAction(ACTION_HONOR_STYLUS_BUTTON_CLICK)
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
            Log.i(TAG, "Honor stylus broadcast receiver registered on: ${targetContext.javaClass.simpleName}")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register receiver with RECEIVER_EXPORTED, trying fallback: ${e.message}")
            try {
                targetContext.registerReceiver(stylusBroadcastReceiver, filter)
                isReceiverRegistered = true
                registeredContextRef = WeakReference(targetContext)
                Log.i(TAG, "Honor stylus broadcast receiver registered via fallback")
            } catch (fallbackError: Throwable) {
                Log.e(TAG, "Failed to register Honor stylus receiver: ${fallbackError.message}")
            }
        }
    }

    @Synchronized
    fun unbindBroadcastReceiver(targetContext: Context?) {
        if (!isReceiverRegistered) return
        val target = targetContext ?: registeredContextRef?.get()
        try {
            target?.unregisterReceiver(stylusBroadcastReceiver)
            Log.i(TAG, "Honor stylus broadcast receiver unregistered from: ${target?.javaClass?.simpleName}")
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
        try {
            val vm = currentVm ?: return
            val fm = currentFeedbackManager

            if (intent != null) {
                try {
                    val loader = registeredContextRef?.get()?.classLoader
                        ?: currentAppContext?.classLoader
                        ?: vm.javaClass.classLoader
                    intent.setExtrasClassLoader(loader)
                } catch (_: Throwable) {}
            }

            Log.i(TAG, "onBroadcastReceived: action=$action")

            val count = safeGetIntExtra(intent, "count", safeGetIntExtra(intent, "click_count", safeGetIntExtra(intent, "clickCount", -1)))
            val clickType = safeGetIntExtra(intent, "click_type", safeGetIntExtra(intent, "clickType", safeGetIntExtra(intent, "type", -1)))

            val isDoubleTapAction = when (action) {
                ACTION_HONOR_STYLUS_DOUBLE_CLICK,
                ACTION_HONOR_DOUBLE_PRESSED,
                ACTION_HONOR_DOUBLE_CLICK,
                ACTION_HONOR_DOUBLE_TAP -> true
                else -> (count >= 2) || (clickType == 2)
            }

            if (isDoubleTapAction) {
                handleDoubleTap(vm, fm)
            } else {
                handleSingleClick(vm, fm)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error handling broadcast: ${t.message}")
        }
    }

    private fun safeGetIntExtra(intent: Intent?, key: String, default: Int): Int {
        if (intent == null) return default
        return try {
            intent.getIntExtra(key, default)
        } catch (_: Throwable) {
            default
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
            Settings.System.getInt(
                context.contentResolver,
                SETTING_DOUBLE_CLICK_SWITCH_MODE,
                Settings.Global.getInt(
                    context.contentResolver,
                    SETTING_DOUBLE_CLICK_SWITCH_MODE,
                    SYSTEM_MODE_CURRENT_AND_ERASER,
                ),
            )
        } catch (_: Throwable) {
            SYSTEM_MODE_CURRENT_AND_ERASER
        }
    }

    fun detectModel(context: Context): HonorPencilModel {
        val model = Build.MODEL.uppercase()
        val device = Build.DEVICE.uppercase()
        val product = Build.PRODUCT.uppercase()

        // Check for foldable models (Honor Magic V, V2, V3, Vs)
        val isFoldable = model.contains("MAGIC V") || model.contains("VER-") ||
                model.contains("FRI-") || model.contains("FLB-") ||
                device.contains("MAGIC V") || product.contains("MAGIC V")

        if (isFoldable) {
            return HonorPencilModel.MAGIC_PEN
        }

        // Check input device names for Magic-Pencil editions
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            if (inputManager != null) {
                for (id in inputManager.inputDeviceIds) {
                    val dev = inputManager.getInputDevice(id) ?: continue
                    val name = dev.name.lowercase()
                    if (name.contains("magic-pen") || name.contains("magic pen")) {
                        return HonorPencilModel.MAGIC_PEN
                    }
                    if (name.contains("magic-pencil 3") || name.contains("magic-pencil3") ||
                        name.contains("pencil 3") || name.contains("pencil3")
                    ) {
                        return HonorPencilModel.MAGIC_PENCIL_3
                    }
                    if (name.contains("magic-pencil 2") || name.contains("magic-pencil2") ||
                        name.contains("pencil 2") || name.contains("pencil2")
                    ) {
                        return HonorPencilModel.MAGIC_PENCIL_2
                    }
                }
            }
        } catch (_: Throwable) {}

        // Tablets supporting Magic-Pencil 3 (MagicPad 2, MagicPad 13, Pad 9 Pro, etc.)
        val isModernHonorPad = model.contains("ROD-") || model.contains("HEY-") ||
                model.contains("MAGICPAD") || model.contains("HONOR PAD 9")
        return if (isModernHonorPad) {
            HonorPencilModel.MAGIC_PENCIL_3
        } else {
            HonorPencilModel.MAGIC_PENCIL_2
        }
    }

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isHonor = manufacturer.contains("honor") || brandName.contains("honor")
        isSupportedHonorDevice = isHonor

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
                        if (name.contains("magic-pencil") || name.contains("magicpencil") ||
                            name.contains("magic-pen") || name.contains("magicpen") ||
                            name.contains("honor")
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
            brand = StylusBrand.HONOR_MAGIC_PENCIL,
            isCurrentDeviceSupported = isHonor,
            isConnected = stylusConnected || isHonor,
            deviceName = if (isHonor) {
                "HONOR Magic-Pencil (${detectedModel.editionName} · ${Build.MODEL})"
            } else {
                "HONOR Magic-Pencil"
            },
        )
    }

    override fun onGenericMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        return false
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (!isSupportedHonorDevice) return false
        val keyCode = event.keyCode
        val isStylusKey = keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
                keyCode == 304 || keyCode == 305

        if (!isStylusKey) return false

        val now = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_UP) {
            val interval = now - lastKeyReleaseTime
            lastKeyReleaseTime = now

            if (interval < DOUBLE_CLICK_TIMEOUT_MS) {
                keyClickCount++
                if (keyClickCount >= 2) {
                    pendingKeySingleClickRunnable?.let { handler.removeCallbacks(it) }
                    pendingKeySingleClickRunnable = null
                    keyClickCount = 0
                    handleDoubleTap(vm, feedbackManager)
                    return true
                }
            } else {
                keyClickCount = 1
                pendingKeySingleClickRunnable?.let { handler.removeCallbacks(it) }
                val task = Runnable {
                    keyClickCount = 0
                    handleSingleClick(vm, feedbackManager)
                }
                pendingKeySingleClickRunnable = task
                handler.postDelayed(task, DOUBLE_CLICK_TIMEOUT_MS)
            }
            return true
        }

        return false
    }

    override fun onStylusMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (!isSupportedHonorDevice) return false
        val buttonState = event.buttonState
        val isSideButtonPressed = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        val now = SystemClock.uptimeMillis()

        if (event.actionMasked == MotionEvent.ACTION_MOVE && isSideButtonPressed) {
            strokeHappenedSincePress = true
        }

        if (isSideButtonPressed && !isButtonCurrentlyDown) {
            isButtonCurrentlyDown = true
            lastButtonDownTime = now
            strokeHappenedSincePress = false
        } else if (!isSideButtonPressed && isButtonCurrentlyDown) {
            isButtonCurrentlyDown = false
            lastButtonReleaseTime = now
            val pressDuration = lastButtonReleaseTime - lastButtonDownTime

            if (!strokeHappenedSincePress && pressDuration < 400L) {
                buttonClickCount++
                if (buttonClickCount == 1) {
                    val task = Runnable {
                        buttonClickCount = 0
                        handleSingleClick(vm, feedbackManager)
                    }
                    pendingSingleClickRunnable = task
                    handler.postDelayed(task, DOUBLE_CLICK_TIMEOUT_MS)
                } else if (buttonClickCount >= 2) {
                    pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                    pendingSingleClickRunnable = null
                    buttonClickCount = 0
                    handleDoubleTap(vm, feedbackManager)
                }
            }
        }

        return false
    }

    override fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        clearPendingTasks()
    }

    private fun handleDoubleTap(vm: PaintViewModel, fm: StylusFeedbackManager?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDoubleTapTriggerTime < DEDUPLICATE_WINDOW_MS) {
            Log.d(TAG, "Ignoring duplicated Honor stylus double-tap event within ${DEDUPLICATE_WINDOW_MS}ms")
            return
        }
        lastDoubleTapTriggerTime = now

        val actionId = vm.honorDoubleTapAction
        Log.i(TAG, "Honor stylus double-tap triggered with actionId: $actionId")

        val effectiveAction = if (actionId.equals("system", ignoreCase = true)) {
            val context = currentAppContext ?: vm.appContext
            val mode = readSystemDoubleClickMode(context)
            Log.i(TAG, "Honor double-tap resolved from MagicOS system mode: $mode")
            when (mode) {
                SYSTEM_MODE_CURRENT_AND_ERASER -> StylusAction.TOGGLE_ERASER
                SYSTEM_MODE_CURRENT_AND_LAST_TOOL -> StylusAction.TOGGLE_LAST_TOOL
                SYSTEM_MODE_SHOW_COLOR_PALETTE -> StylusAction.SHOW_COLOR_PALETTE
                SYSTEM_MODE_DISABLE -> StylusAction.NONE
                else -> StylusAction.TOGGLE_ERASER
            }
        } else {
            StylusAction.fromActionId(actionId)
        }

        if (effectiveAction != StylusAction.NONE) {
            vm.executeStylusAction(effectiveAction)
            if (vm.honorHapticsEnabled) {
                fm?.triggerActionConfirmation()
            }
        }
    }

    private fun handleSingleClick(vm: PaintViewModel, fm: StylusFeedbackManager?) {
        val actionId = vm.honorSingleClickAction
        if (actionId.equals("none", ignoreCase = true) || actionId.isEmpty()) return

        val action = StylusAction.fromActionId(actionId)
        if (action != StylusAction.NONE) {
            vm.executeStylusAction(action)
            if (vm.honorHapticsEnabled) {
                fm?.triggerActionConfirmation()
            }
        }
    }

    override fun release() {
        clearPendingTasks()
    }
}
