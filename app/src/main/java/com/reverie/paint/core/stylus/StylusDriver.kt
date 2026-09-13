/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel

/**
 * Unified Stylus Driver orchestrator.
 * Delegates brand-specific logic to modular [StylusBrandAdapter] instances (OPPO, Samsung, Huawei, Xiaomi, Generic)
 * while providing a single high-performance facade for CanvasTouchView and PaintViewModel.
 */
class StylusDriver(
    private val context: Context,
    private val vm: PaintViewModel,
) {
    val feedbackManager = StylusFeedbackManager(context)

    val adapters: List<StylusBrandAdapter> = listOf(
        OppoStylusAdapter(),
        SamsungStylusAdapter(),
        HuaweiStylusAdapter(),
        XiaomiStylusAdapter(),
        GenericStylusAdapter(),
    )

    init {
        adapters.forEach { it.register(context, vm, feedbackManager) }
        syncSettings()
    }

    /**
     * Retrieve a specific brand adapter if registered.
     */
    inline fun <reified T : StylusBrandAdapter> getAdapter(): T? {
        return adapters.filterIsInstance<T>().firstOrNull()
    }

    fun getAdapterForBrand(brand: StylusBrand): StylusBrandAdapter? {
        return adapters.firstOrNull { it.brand == brand }
    }

    fun syncSettings() {
        feedbackManager.hapticsEnabled = vm.stylusHapticsEnabled
        feedbackManager.hapticsIntensity = vm.stylusHapticsIntensity
        feedbackManager.inPenHaptics = vm.oppoInPenHapticsEnabled && vm.oppoPencilModel.hasInPenHaptics
        feedbackManager.audioEnabled = vm.stylusAudioEnabled
        feedbackManager.audioVolume = vm.stylusAudioVolume
        feedbackManager.audioType = StylusAudioType.fromOrdinal(vm.stylusAudioTypeOrdinal)
        feedbackManager.setWritingHapticsEnabled(false)

        adapters.forEach { it.syncSettings(vm, feedbackManager) }
    }

    fun detectOppoPencilModel(): OppoPencilModel {
        return getAdapter<OppoStylusAdapter>()?.detectModel(context) ?: OppoPencilModel.STANDARD
    }

    /**
     * Detects brand styluses and sorts them so the connected/supported stylus is pinned on top.
     */
    fun detectDevices(): List<StylusDeviceDetected> {
        val detected = mutableListOf<StylusDeviceDetected>()
        for (adapter in adapters) {
            val item = adapter.detect(context, vm)
            if (item != null) {
                detected.add(item)
            }
        }

        // Sort: Supported & Connected devices first (Pinned on top)
        detected.sortWith(
            compareByDescending<StylusDeviceDetected> { it.isCurrentDeviceSupported && it.isConnected }
                .thenByDescending { it.isCurrentDeviceSupported }
                .thenByDescending { it.isConnected }
        )
        return detected
    }

    /**
     * Handles generic motion events (e.g. ACTION_SCROLL from stylus barrel slide).
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        for (adapter in adapters) {
            if (adapter.onGenericMotionEvent(event, vm, feedbackManager)) {
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
        for (adapter in adapters) {
            if (adapter.onStylusMotionEvent(event, vm, feedbackManager)) {
                return true
            }
        }
        return false
    }

    /**
     * Handle physical/bluetooth stylus key events.
     */
    fun onStylusKeyEvent(event: KeyEvent): Boolean {
        for (adapter in adapters) {
            if (adapter.onStylusKeyEvent(event, vm, feedbackManager)) {
                return true
            }
        }
        return false
    }

    fun handleDoubleTap(): Boolean {
        val detected = detectDevices().firstOrNull()
        val action = when (detected?.brand) {
            StylusBrand.SAMSUNG_SPEN -> StylusAction.fromActionId(vm.samsungDoubleClickAction)
            else -> StylusAction.fromActionId(vm.oppoDoubleTapAction)
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
        val action = when (detected?.brand) {
            StylusBrand.SAMSUNG_SPEN -> StylusAction.fromActionId(vm.samsungSingleClickAction)
            else -> StylusAction.fromActionId(vm.oppoDoubleTapAction)
        }
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun release() {
        adapters.forEach {
            it.unregister(context)
            it.release()
        }
        feedbackManager.release()
    }
}
