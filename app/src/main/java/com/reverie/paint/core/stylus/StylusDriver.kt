/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Unified Stylus Driver for vendor devices (OPPO Pencil / OnePlus Stylo, Samsung S Pen)
 * and generic Android styluses. Listens to ColorOS IPeManager broadcasts for double-tap,
 * observes stylus Bluetooth connection states, and handles hardware key events.
 */
class StylusDriver(
    private val context: Context,
    private val vm: PaintViewModel,
) {
    val feedbackManager = StylusFeedbackManager(context)

    // Button double-tap / long-press detection state for generic/Samsung digitizer buttons
    private var lastButtonDownTime: Long = 0L
    private var lastButtonReleaseTime: Long = 0L
    private var isButtonCurrentlyDown: Boolean = false
    private var buttonClickCount: Int = 0
    private var pendingSingleClickRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isReceiverRegistered = false
    private var isObserverRegistered = false

    // ColorOS IPeManager BroadcastReceiver for OPPO Pencil / OnePlus Stylo gestures
    private val pencilBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_OPPO_DOUBLE_CLICK -> {
                    handleDoubleTap()
                }
                ACTION_OPPO_SINGLE_CLICK -> {
                    handleSingleClick()
                }
            }
        }
    }

    // ContentObserver to detect stylus attach/detach in real-time
    private val pencilConnectionObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            detectDevices()
        }
    }

    init {
        syncSettings()
        registerColorOsPencilReceiver()
        registerConnectionObserver()
        feedbackManager.ocsClient.connect()
    }

    private fun registerColorOsPencilReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_OPPO_DOUBLE_CLICK)
            addAction(ACTION_OPPO_SINGLE_CLICK)
        }
        val permission = "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(pencilBroadcastReceiver, filter, permission, null, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(pencilBroadcastReceiver, filter, permission, null)
            }
            isReceiverRegistered = true
        } catch (_: Throwable) {
            // Fallback: register without vendor permission if not recognized by OS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(pencilBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(pencilBroadcastReceiver, filter)
                }
                isReceiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    private fun registerConnectionObserver() {
        if (isObserverRegistered) return
        try {
            val uri = Settings.Global.getUriFor("ipe_pencil_connect_state")
            if (uri != null) {
                context.contentResolver.registerContentObserver(uri, false, pencilConnectionObserver)
                isObserverRegistered = true
            }
        } catch (_: Throwable) {}
    }

    fun syncSettings() {
        feedbackManager.hapticsEnabled = vm.stylusHapticsEnabled
        feedbackManager.hapticsIntensity = vm.stylusHapticsIntensity
        feedbackManager.inPenHaptics = vm.oppoInPenHapticsEnabled && vm.oppoPencilModel.hasInPenHaptics
        feedbackManager.audioEnabled = vm.stylusAudioEnabled
        feedbackManager.audioVolume = vm.stylusAudioVolume
        feedbackManager.audioType = StylusAudioType.fromOrdinal(vm.stylusAudioTypeOrdinal)
        feedbackManager.setWritingHapticsEnabled(false)
    }

    fun detectOppoPencilModel(): OppoPencilModel {
        val btName = try {
            Settings.Global.getString(context.contentResolver, "ipe_pencil_bt_device_name")
        } catch (_: Throwable) { null } ?: ""

        if (btName.isNotBlank()) {
            return if (btName.contains("pro", ignoreCase = true) || btName.contains("stylo 2", ignoreCase = true)) {
                OppoPencilModel.PRO
            } else {
                OppoPencilModel.STANDARD
            }
        }

        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        if (model.contains("pro") || device.contains("pro") ||
            model.contains("opd2401") || model.contains("opd2403") || model.contains("opd2405")
        ) {
            return OppoPencilModel.PRO
        }
        return OppoPencilModel.STANDARD
    }

    /**
     * Detects brand styluses and sorts them so the connected/supported stylus is pinned on top.
     */
    fun detectDevices(): List<StylusDeviceDetected> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        val isOppoDevice = manufacturer.contains("oppo") || brand.contains("oppo") ||
                manufacturer.contains("oneplus") || brand.contains("oneplus") ||
                manufacturer.contains("realme") || brand.contains("realme")

        val isSamsungDevice = manufacturer.contains("samsung") || brand.contains("samsung")

        val btName = try {
            Settings.Global.getString(context.contentResolver, "ipe_pencil_bt_device_name")
        } catch (_: Throwable) { null } ?: ""

        val connectState = try {
            Settings.Global.getInt(context.contentResolver, "ipe_pencil_connect_state", 0)
        } catch (_: Throwable) { 0 }
        val oppoStylusConnected = (connectState == 2) || (isOppoDevice && btName.isNotBlank())

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
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        val autoDetected = detectOppoPencilModel()
        vm.detectedOppoPencilModel = autoDetected
        val activeModel = vm.oppoPencilModel

        val oppoDeviceDisplayName = if (btName.isNotBlank()) {
            "$btName (${activeModel.editionName})"
        } else if (isOppoDevice) {
            "${activeModel.displayName} (${Build.MODEL})"
        } else {
            "OPPO Pencil / 一加智能手写笔"
        }

        val oppoItem = StylusDeviceDetected(
            brand = StylusBrand.OPPO_ONEPLUS,
            isCurrentDeviceSupported = isOppoDevice,
            isConnected = oppoStylusConnected,
            deviceName = oppoDeviceDisplayName
        )

        val samsungItem = StylusDeviceDetected(
            brand = StylusBrand.SAMSUNG_SPEN,
            isCurrentDeviceSupported = isSamsungDevice,
            isConnected = samsungStylusConnected || isSamsungDevice,
            deviceName = if (isSamsungDevice) "Samsung S Pen (${Build.MODEL})" else "三星 S Pen"
        )

        val genericItem = StylusDeviceDetected(
            brand = StylusBrand.GENERIC,
            isCurrentDeviceSupported = true,
            isConnected = !oppoStylusConnected && !samsungStylusConnected,
            deviceName = "通用触控笔 (标准 Android 触控协议)"
        )

        // Sort: Supported & Connected devices first (Pinned on top)
        val list = mutableListOf(oppoItem, samsungItem, genericItem)
        list.sortWith(
            compareByDescending<StylusDeviceDetected> { it.isCurrentDeviceSupported && it.isConnected }
                .thenByDescending { it.isCurrentDeviceSupported }
                .thenByDescending { it.isConnected }
        )
        return list
    }

    private var lastSlideTime: Long = 0L

    /**
     * Handles generic motion events (especially ACTION_SCROLL from stylus barrel slide).
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL || event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val scrollVal = if (vScroll != 0f) vScroll else hScroll
            if (scrollVal != 0f && vm.oppoPencilModel.hasSlideGesture) {
                val now = SystemClock.uptimeMillis()
                if (now - lastSlideTime > 30L) {
                    lastSlideTime = now
                    vm.executeStylusSlide(scrollVal)
                    feedbackManager.triggerActionConfirmation()
                }
                return true
            }
        }
        return false
    }

    /**
     * Process stylus motion events for barrel slide and side-buttons.
     * Hot-path safe: does NOT invoke blocking vibrators or create allocations.
     */
    fun onStylusMotionEvent(event: MotionEvent): Boolean {
        if (onGenericMotionEvent(event)) return true

        val buttonState = event.buttonState
        val isPrimaryBtnDown = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_SECONDARY) != 0

        val now = SystemClock.uptimeMillis()

        if (isPrimaryBtnDown && !isButtonCurrentlyDown) {
            // Button pressed down
            isButtonCurrentlyDown = true
            lastButtonDownTime = now
            return false
        } else if (!isPrimaryBtnDown && isButtonCurrentlyDown) {
            // Button released (Click)
            isButtonCurrentlyDown = false
            val pressDuration = now - lastButtonDownTime
            val timeSinceLastRelease = now - lastButtonReleaseTime
            lastButtonReleaseTime = now

            if (pressDuration > 450L) {
                // Long press
                pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                pendingSingleClickRunnable = null
                buttonClickCount = 0
                return handleSamsungLongPress()
            }

            if (timeSinceLastRelease < 320L) {
                // Double tap / double click
                pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                pendingSingleClickRunnable = null
                buttonClickCount = 0
                return handleDoubleTap()
            } else {
                // Single click (delayed slightly to wait for potential second click)
                buttonClickCount = 1
                pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (buttonClickCount == 1) {
                        buttonClickCount = 0
                        handleSingleClick()
                    }
                }
                pendingSingleClickRunnable = runnable
                handler.postDelayed(runnable, 280L)
            }
        }
        return false
    }

    /**
     * Handle physical/bluetooth stylus key events (e.g. Android 14 KEYCODE_STYLUS_BUTTON_*,
     * and hardware PageUp/PageDown mapped to stylus barrel slide).
     */
    fun onStylusKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // Barrel slide key codes emitted by stylus keyboards
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - lastSlideTime > 30L) {
                    lastSlideTime = now
                    vm.executeStylusSlide(1f)
                    feedbackManager.triggerActionConfirmation()
                }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - lastSlideTime > 30L) {
                    lastSlideTime = now
                    vm.executeStylusSlide(-1f)
                    feedbackManager.triggerActionConfirmation()
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY -> {
                    handleDoubleTap()
                    return true
                }
                KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY -> {
                    handleSingleClick()
                    return true
                }
            }
        }
        return false
    }

    fun handleDoubleTap(): Boolean {
        val detected = detectDevices().firstOrNull()
        val action = if (detected?.brand == StylusBrand.SAMSUNG_SPEN) {
            StylusAction.fromActionId(vm.samsungDoubleClickAction)
        } else {
            StylusAction.fromActionId(vm.oppoDoubleTapAction)
        }
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun handleSingleClick(): Boolean {
        val detected = detectDevices().firstOrNull()
        val action = if (detected?.brand == StylusBrand.SAMSUNG_SPEN) {
            StylusAction.fromActionId(vm.samsungSingleClickAction)
        } else {
            StylusAction.fromActionId(vm.oppoDoubleTapAction)
        }
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    private fun handleSamsungLongPress(): Boolean {
        val action = StylusAction.fromActionId(vm.samsungLongPressAction)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun release() {
        pendingSingleClickRunnable?.let { handler.removeCallbacks(it) }
        pendingSingleClickRunnable = null
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(pencilBroadcastReceiver)
            } catch (_: Throwable) {}
            isReceiverRegistered = false
        }
        if (isObserverRegistered) {
            try {
                context.contentResolver.unregisterContentObserver(pencilConnectionObserver)
            } catch (_: Throwable) {}
            isObserverRegistered = false
        }
        feedbackManager.release()
    }

    companion object {
        const val ACTION_OPPO_DOUBLE_CLICK = "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK"
        const val ACTION_OPPO_SINGLE_CLICK = "com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK"
    }
}
