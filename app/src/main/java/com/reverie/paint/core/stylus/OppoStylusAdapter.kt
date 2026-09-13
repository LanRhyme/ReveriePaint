/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Dedicated Stylus Adapter for OPPO Pencil & OnePlus Stylo series.
 * Connects ColorOS OpenCapabilityService (OCS) for in-pen haptic micro-vibrations,
 * listens to IPeManager double-tap broadcast intents, and captures barrel touch slide gestures.
 */
class OppoStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.OPPO_ONEPLUS

    companion object {
        const val ACTION_OPPO_DOUBLE_CLICK = "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK"
        const val ACTION_OPPO_SINGLE_CLICK = "com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK"
        const val ACTION_OPPO_DOUBLE_CLICK_LEGACY = "com.oplus.ipemanager.pencil.double_click"
        const val ACTION_OPPO_SINGLE_CLICK_LEGACY = "com.oplus.ipemanager.pencil.single_click"
    }

    private var isReceiverRegistered = false
    private var isObserverRegistered = false
    private var lastSlideTime: Long = 0L
    private var accumulatedSlideDelta: Float = 0f

    private var currentVm: PaintViewModel? = null
    private var currentFeedbackManager: StylusFeedbackManager? = null

    private val handler = Handler(Looper.getMainLooper())

    private val pencilBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val vm = currentVm ?: return
            val fm = currentFeedbackManager
            when (action) {
                ACTION_OPPO_DOUBLE_CLICK, ACTION_OPPO_DOUBLE_CLICK_LEGACY -> {
                    handleDoubleTap(vm, fm)
                }
                ACTION_OPPO_SINGLE_CLICK, ACTION_OPPO_SINGLE_CLICK_LEGACY -> {
                    handleSingleClick(vm, fm)
                }
            }
        }
    }

    private val pencilConnectionObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val vm = currentVm ?: return
            currentFeedbackManager?.let { syncSettings(vm, it) }
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
        registerConnectionObserver(context)
        feedbackManager.ocsClient.connect()
    }

    private fun registerBroadcastReceiver(context: Context) {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_OPPO_DOUBLE_CLICK)
            addAction(ACTION_OPPO_SINGLE_CLICK)
            addAction(ACTION_OPPO_DOUBLE_CLICK_LEGACY)
            addAction(ACTION_OPPO_SINGLE_CLICK_LEGACY)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(pencilBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(pencilBroadcastReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (_: Throwable) {}
    }

    private fun registerConnectionObserver(context: Context) {
        if (isObserverRegistered) return
        try {
            val uri = Settings.Global.getUriFor("ipe_pencil_connect_state")
            if (uri != null) {
                context.contentResolver.registerContentObserver(uri, false, pencilConnectionObserver)
                isObserverRegistered = true
            }
        } catch (_: Throwable) {}
    }

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()

        val isOppoDevice = manufacturer.contains("oppo") || brandName.contains("oppo") ||
                manufacturer.contains("oneplus") || brandName.contains("oneplus") ||
                manufacturer.contains("realme") || brandName.contains("realme")

        val btName = try {
            Settings.Global.getString(context.contentResolver, "ipe_pencil_bt_device_name")
        } catch (_: Throwable) { null } ?: ""

        val connectState = try {
            Settings.Global.getInt(context.contentResolver, "ipe_pencil_connect_state", 0)
        } catch (_: Throwable) { 0 }
        val oppoStylusConnected = (connectState == 2) || (isOppoDevice && btName.isNotBlank())

        val autoDetected = detectModel(context)
        vm.detectedOppoPencilModel = autoDetected
        val activeModel = vm.oppoPencilModel

        val oppoDeviceDisplayName = if (btName.isNotBlank()) {
            "$btName (${activeModel.editionName})"
        } else if (isOppoDevice) {
            "${activeModel.displayName} (${Build.MODEL})"
        } else {
            "OPPO Pencil / 一加智能手写笔"
        }

        return StylusDeviceDetected(
            brand = StylusBrand.OPPO_ONEPLUS,
            isCurrentDeviceSupported = isOppoDevice,
            isConnected = oppoStylusConnected,
            deviceName = oppoDeviceDisplayName,
        )
    }

    fun detectModel(context: Context): OppoPencilModel {
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

    override fun onGenericMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL || event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val scrollVal = if (vScroll != 0f) vScroll else hScroll
            if (scrollVal != 0f && vm.oppoPencilModel.hasSlideGesture) {
                val now = SystemClock.uptimeMillis()
                val debounceMs = when (vm.oppoSlideSensitivity) {
                    "low" -> 140L
                    "high" -> 60L
                    else -> 90L
                }
                val threshold = when (vm.oppoSlideSensitivity) {
                    "low" -> 1.5f
                    "high" -> 0.4f
                    else -> 0.8f
                }
                if (now - lastSlideTime > 500L) {
                    accumulatedSlideDelta = 0f
                }
                accumulatedSlideDelta += scrollVal
                if (kotlin.math.abs(accumulatedSlideDelta) >= threshold && now - lastSlideTime > debounceMs) {
                    val direction = if (accumulatedSlideDelta > 0f) 1f else -1f
                    accumulatedSlideDelta = 0f
                    lastSlideTime = now
                    vm.executeStylusSlide(direction)
                    feedbackManager.triggerActionConfirmation()
                }
                return true
            }
        }
        return false
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        val keyCode = event.keyCode
        // Barrel slide key codes emitted by stylus hardware
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                val debounceMs = when (vm.oppoSlideSensitivity) {
                    "low" -> 160L
                    "high" -> 70L
                    else -> 110L
                }
                if (now - lastSlideTime > debounceMs) {
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
                val debounceMs = when (vm.oppoSlideSensitivity) {
                    "low" -> 160L
                    "high" -> 70L
                    else -> 110L
                }
                if (now - lastSlideTime > debounceMs) {
                    lastSlideTime = now
                    vm.executeStylusSlide(-1f)
                    feedbackManager.triggerActionConfirmation()
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY) {
                handleDoubleTap(vm, feedbackManager)
                return true
            }
        }
        return false
    }

    fun handleDoubleTap(vm: PaintViewModel, feedbackManager: StylusFeedbackManager?) {
        val action = StylusAction.fromActionId(vm.oppoDoubleTapAction)
        if (action != StylusAction.NONE) {
            feedbackManager?.triggerActionConfirmation()
            vm.executeStylusAction(action)
        }
    }

    fun handleSingleClick(vm: PaintViewModel, feedbackManager: StylusFeedbackManager?) {
        handleDoubleTap(vm, feedbackManager)
    }

    override fun unregister(context: Context) {
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
    }

    override fun release() {
        currentVm = null
        currentFeedbackManager = null
    }
}
