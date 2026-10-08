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
 * Dedicated Stylus Adapter for Xiaomi Focus Pen and Xiaomi Smart Pen (1st & 2nd Gen).
 * Supports primary (writing) key, secondary (screenshot) key, focus key (Focus Pen),
 * double-click detection, hold-to-erase, and tactile feedback.
 */
class XiaomiStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.XIAOMI_STYLUS

    companion object {
        private const val DOUBLE_CLICK_TIMEOUT_MS = 320L
        private const val KEY_FOCUS_KEYCODE_1 = 310
        private const val KEY_FOCUS_KEYCODE_2 = KeyEvent.KEYCODE_F1
    }

    private var isSupportedXiaomiDevice: Boolean = run {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        m.contains("xiaomi") || b.contains("xiaomi") || b.contains("redmi") || m.contains("redmi")
    }

    private val handler = Handler(Looper.getMainLooper())

    // Primary button tracking
    private var lastPrimaryDownTime: Long = 0L
    private var isPrimaryCurrentlyDown: Boolean = false
    private var primaryClickCount: Int = 0
    private var strokeHappenedSincePrimaryPress: Boolean = false
    private var pendingPrimarySingleClickRunnable: Runnable? = null

    // Secondary button tracking
    private var lastSecondaryDownTime: Long = 0L
    private var isSecondaryCurrentlyDown: Boolean = false
    private var strokeHappenedSinceSecondaryPress: Boolean = false

    // PenEngine (Xiaomi HyperOS 3.0+ TouchFilmUtils) reflection bridge
    private var currentVm: PaintViewModel? = null
    private var currentFeedbackManager: StylusFeedbackManager? = null
    private var touchFilmUtilsClass: Class<*>? = null
    private var onDispatchKeyEventMethod: java.lang.reflect.Method? = null
    private var isPenEngineInitialized = false

    override fun register(context: Context, vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        currentVm = vm
        currentFeedbackManager = feedbackManager
        initPenEngineIfAvailable(context.applicationContext, vm, feedbackManager)
    }

    override fun onActivityResume(activity: android.app.Activity) {
        currentVm?.let { vm ->
            currentFeedbackManager?.let { fm ->
                initPenEngineIfAvailable(activity.applicationContext, vm, fm)
            }
        }
    }

    override fun onActivityPause(activity: android.app.Activity) {
        destroyPenEngine()
    }

    override fun unregister(context: Context) {
        destroyPenEngine()
        release()
    }

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandName = Build.BRAND.lowercase()
        val isXiaomiDevice = manufacturer.contains("xiaomi") || brandName.contains("xiaomi") || brandName.contains("redmi")
        isSupportedXiaomiDevice = isXiaomiDevice

        var xiaomiStylusConnected = false
        var detectedPenName = "小米灵感触控笔"
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
                        if (name.contains("xiaomi") || name.contains("mi pen") || name.contains("smart pen") || name.contains("focus")) {
                            xiaomiStylusConnected = true
                            if (name.contains("focus pro") || name.contains("focus pen pro") || name.contains("stylus pro")) {
                                detectedPenName = "小米焦点触控笔 Pro"
                            } else if (name.contains("focus")) {
                                detectedPenName = "小米焦点触控笔"
                            }
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return StylusDeviceDetected(
            brand = StylusBrand.XIAOMI_STYLUS,
            isCurrentDeviceSupported = isXiaomiDevice,
            isConnected = xiaomiStylusConnected || isXiaomiDevice,
            deviceName = if (isXiaomiDevice) "$detectedPenName (${Build.MODEL})" else detectedPenName,
        )
    }

    fun detectModel(context: Context): XiaomiPencilModel {
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            if (inputManager != null) {
                for (id in inputManager.inputDeviceIds) {
                    val dev = inputManager.getInputDevice(id) ?: continue
                    val name = dev.name.lowercase()
                    if (name.contains("focus pro") || name.contains("focus pen pro") || name.contains("stylus pro")) {
                        return XiaomiPencilModel.FOCUS_PEN_PRO
                    }
                    if (name.contains("focus")) {
                        return XiaomiPencilModel.FOCUS_PEN
                    }
                    if (name.contains("smart pen 2") || name.contains("smartpen2")) {
                        return XiaomiPencilModel.SMART_PEN_2
                    }
                    if (name.contains("smart pen 1") || name.contains("smartpen1")) {
                        return XiaomiPencilModel.SMART_PEN_1
                    }
                }
            }
        } catch (_: Throwable) {}

        val model = Build.MODEL.lowercase()
        return when {
            model.contains("pad 8") -> XiaomiPencilModel.FOCUS_PEN_PRO
            model.contains("pad 6s") || model.contains("pad 7") -> XiaomiPencilModel.FOCUS_PEN
            model.contains("pad 6") -> XiaomiPencilModel.SMART_PEN_2
            model.contains("pad 5") -> XiaomiPencilModel.SMART_PEN_1
            else -> XiaomiPencilModel.SMART_PEN_2
        }
    }

    private fun initPenEngineIfAvailable(context: Context, vm: PaintViewModel, fm: StylusFeedbackManager) {
        if (isPenEngineInitialized) return
        try {
            val utilsClass = Class.forName("com.miui.penengine.touchfilm.MiuiTouchFilmUtils")
            val listenerInterface = Class.forName("com.miui.penengine.touchfilm.MiuiTouchFilmUtils\$TouchFilmListener")
            touchFilmUtilsClass = utilsClass
            onDispatchKeyEventMethod = utilsClass.getMethod("onDispatchKeyEvent", KeyEvent::class.java)

            val proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface),
            ) { _, method, args ->
                when (method.name) {
                    "onTouchFilmTriggered" -> {
                        val function = args?.getOrNull(0) as? Int ?: return@newProxyInstance null
                        handler.post {
                            handleTouchFilmTriggered(function, vm, fm)
                        }
                        null
                    }
                    "onBrushPreviewChanged" -> {
                        null
                    }
                    else -> null
                }
            }

            val initMethod = utilsClass.getMethod("init", Context::class.java, listenerInterface)
            initMethod.invoke(null, context, proxyListener)
            isPenEngineInitialized = true
        } catch (_: Throwable) {
            // Not running on Xiaomi HyperOS with PenEngine SDK, fallback gracefully
        }
    }

    private fun destroyPenEngine() {
        if (!isPenEngineInitialized) return
        try {
            touchFilmUtilsClass?.getMethod("onDestroy")?.invoke(null)
        } catch (_: Throwable) {}
        isPenEngineInitialized = false
    }

    private fun handleTouchFilmTriggered(function: Int, vm: PaintViewModel, fm: StylusFeedbackManager) {
        val switchBrushEraser = getTouchFilmConstant("SWITCH_BETWEEN_BRUSH_AND_ERASER", 1)
        val switchToPrevious = getTouchFilmConstant("SWITCH_TO_PREVIOUS_BRUSH", 2)
        val showColorWheel = getTouchFilmConstant("SHOW_COLOR_WHEEL", 3)
        val showBrushSettings = getTouchFilmConstant("SHOW_BRUSH_SETTINGS", 4)
        val stylusSlideUp = getTouchFilmConstant("STYLUS_SLIDE_UP", 5)
        val stylusSlideDown = getTouchFilmConstant("STYLUS_SLIDE_DOWN", 6)

        when (function) {
            switchBrushEraser -> {
                fm.triggerActionConfirmation()
                vm.executeStylusAction(StylusAction.fromActionId(vm.xiaomiDoubleTapAction))
            }
            switchToPrevious -> {
                fm.triggerActionConfirmation()
                vm.executeStylusAction(StylusAction.TOGGLE_LAST_TOOL)
            }
            showColorWheel -> {
                fm.triggerActionConfirmation()
                vm.executeStylusAction(StylusAction.SHOW_COLOR_PALETTE)
            }
            showBrushSettings -> {
                fm.triggerActionConfirmation()
                vm.executeStylusAction(StylusAction.fromActionId(vm.xiaomiSqueezeAction))
            }
            stylusSlideUp -> {
                vm.executeXiaomiSlide(up = true)
            }
            stylusSlideDown -> {
                vm.executeXiaomiSlide(up = false)
            }
        }
    }

    private fun getTouchFilmConstant(name: String, fallback: Int): Int {
        return try {
            touchFilmUtilsClass?.getField(name)?.getInt(null) ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (!isSupportedXiaomiDevice) return false
        // Forward to Xiaomi PenEngine SDK if available on HyperOS
        if (onDispatchKeyEventMethod != null) {
            try {
                val handled = onDispatchKeyEventMethod?.invoke(null, event) as? Boolean ?: false
                if (handled) return true
            } catch (_: Throwable) {}
        }

        // Focus Pen Pro is buttonless and handles gestures via PenEngine
        if (!vm.xiaomiPencilModel.hasPhysicalButtons) {
            return false
        }

        val keyCode = event.keyCode

        // Focus button (Focus Pen only)
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_3 ||
            keyCode == KEY_FOCUS_KEYCODE_1 ||
            keyCode == KEY_FOCUS_KEYCODE_2
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                val action = StylusAction.fromActionId(vm.xiaomiFocusButtonAction)
                if (action != StylusAction.NONE) {
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(action)
                    return true
                }
            }
            return true
        }

        // Secondary button (Screenshot key / Assistant key)
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_2 ||
            keyCode == 309
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                val action = StylusAction.fromActionId(vm.xiaomiSecondaryButtonAction)
                if (action != StylusAction.NONE) {
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(action)
                    return true
                }
            }
            return true
        }

        // Primary button (Writing key)
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
            keyCode == 308
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                return dispatchPrimaryClick(now, vm, feedbackManager)
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
        if (!isSupportedXiaomiDevice) return false
        val buttonState = event.buttonState
        val isPrimaryBtnDown = (buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val isSecondaryBtnDown = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

        val now = SystemClock.uptimeMillis()

        // 1. Primary button tracking (Writing key)
        if (isPrimaryBtnDown && !isPrimaryCurrentlyDown) {
            isPrimaryCurrentlyDown = true
            lastPrimaryDownTime = now
            strokeHappenedSincePrimaryPress = false
        } else if (isPrimaryBtnDown && isPrimaryCurrentlyDown) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                strokeHappenedSincePrimaryPress = true
            }
        } else if (!isPrimaryBtnDown && isPrimaryCurrentlyDown) {
            isPrimaryCurrentlyDown = false
            if (!strokeHappenedSincePrimaryPress) {
                dispatchPrimaryClick(now, vm, feedbackManager)
            }
        }

        // 2. Secondary button tracking (Screenshot key)
        if (isSecondaryBtnDown && !isSecondaryCurrentlyDown) {
            isSecondaryCurrentlyDown = true
            lastSecondaryDownTime = now
            strokeHappenedSinceSecondaryPress = false
        } else if (isSecondaryBtnDown && isSecondaryCurrentlyDown) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                strokeHappenedSinceSecondaryPress = true
            }
        } else if (!isSecondaryBtnDown && isSecondaryCurrentlyDown) {
            isSecondaryCurrentlyDown = false
            if (!strokeHappenedSinceSecondaryPress) {
                val action = StylusAction.fromActionId(vm.xiaomiSecondaryButtonAction)
                if (action != StylusAction.NONE) {
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(action)
                }
            }
        }

        return false
    }

    private fun dispatchPrimaryClick(
        now: Long,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        if (!vm.xiaomiPencilModel.hasDoubleTap) {
            val action = StylusAction.fromActionId(vm.xiaomiPrimaryButtonAction)
            if (action != StylusAction.NONE) {
                feedbackManager.triggerActionConfirmation()
                vm.executeStylusAction(action)
            }
            return true
        }

        primaryClickCount++
        if (primaryClickCount == 1) {
            val singleRunnable = Runnable {
                primaryClickCount = 0
                val action = StylusAction.fromActionId(vm.xiaomiPrimaryButtonAction)
                if (action != StylusAction.NONE) {
                    feedbackManager.triggerActionConfirmation()
                    vm.executeStylusAction(action)
                }
            }
            pendingPrimarySingleClickRunnable = singleRunnable
            handler.postDelayed(singleRunnable, DOUBLE_CLICK_TIMEOUT_MS)
            return true
        } else if (primaryClickCount >= 2) {
            pendingPrimarySingleClickRunnable?.let { handler.removeCallbacks(it) }
            pendingPrimarySingleClickRunnable = null
            primaryClickCount = 0
            val action = StylusAction.fromActionId(vm.xiaomiDoubleTapAction)
            if (action != StylusAction.NONE) {
                feedbackManager.triggerActionConfirmation()
                vm.executeStylusAction(action)
                return true
            }
        }
        return false
    }

    override fun onStylusHoverExited(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        isPrimaryCurrentlyDown = false
        isSecondaryCurrentlyDown = false
        strokeHappenedSincePrimaryPress = false
        strokeHappenedSinceSecondaryPress = false
    }

    override fun release() {
        pendingPrimarySingleClickRunnable?.let { handler.removeCallbacks(it) }
        pendingPrimarySingleClickRunnable = null
    }
}
