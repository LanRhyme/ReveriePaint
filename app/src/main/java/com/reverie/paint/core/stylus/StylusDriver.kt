/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.Page
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
        HuaweiStylusAdapter(),
        SamsungStylusAdapter(),
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
        feedbackManager.syncAudioConfig()
        feedbackManager.setWritingHapticsEnabled(false)

        adapters.forEach { it.syncSettings(vm, feedbackManager) }
    }

    fun onWindowFocusChanged(activity: android.app.Activity, hasFocus: Boolean) {
        adapters.forEach { it.onWindowFocusChanged(activity, hasFocus) }
    }

    fun onActivityResume(activity: android.app.Activity) {
        adapters.forEach { it.onActivityResume(activity) }
    }

    fun onActivityPause(activity: android.app.Activity) {
        adapters.forEach { it.onActivityPause(activity) }
    }

    /**
     * 刷新纸张音效管线门控: 仅绘画页 + 前台需要预热, 其余场景挂起 AudioTrack
     * (见 [PaperSoundEngine.setActive])。
     *
     * 触发源只有两处: 页面切换 (MainActivity 的 LaunchedEffect) 与 Activity
     * 的 STARTED 状态 (onStart/onStop)。刻意不用 onPause —— 分屏/悬浮窗失焦
     * 时 Activity 处于 PAUSED 但用户仍在绘画, 用 onPause 会把音效误关。
     */
    fun refreshAudioGate(foreground: Boolean) {
        feedbackManager.setAudioActive(foreground && vm.currentPage == Page.PAINTING)
    }

    fun detectOppoPencilModel(): OppoPencilModel {
        return getAdapter<OppoStylusAdapter>()?.detectModel(context) ?: OppoPencilModel.STANDARD
    }

    fun detectHuaweiPencilModel(): HuaweiPencilModel {
        return getAdapter<HuaweiStylusAdapter>()?.detectModel(context) ?: HuaweiPencilModel.GEN2
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
     * Notifies adapters that the stylus left the hover field, so button state
     * tracked across hover events (e.g. S Pen side button) is reset safely.
     */
    fun onStylusHoverExited() {
        for (adapter in adapters) {
            adapter.onStylusHoverExited(vm, feedbackManager)
        }
    }

    /**
     * Samsung Notes standard semantics: holding the side button while the pen
     * touches down turns that stroke into a temporary eraser stroke.
     * Hot-path safe: two bitmask reads, no allocation.
     */
    fun isSideButtonEraseActive(event: MotionEvent): Boolean {
        val detected = detectDevices().firstOrNull()
        val eraseAllowed = when (detected?.brand) {
            StylusBrand.HUAWEI_MPENCIL -> vm.huaweiSideButtonErase
            StylusBrand.SAMSUNG_SPEN -> vm.samsungSideButtonErase
            else -> vm.huaweiSideButtonErase || vm.samsungSideButtonErase
        }
        if (!eraseAllowed) return false
        val btn = event.buttonState
        return (btn and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (btn and MotionEvent.BUTTON_SECONDARY) != 0
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
        if (detected?.brand == StylusBrand.HUAWEI_MPENCIL) {
            return getAdapter<HuaweiStylusAdapter>()?.handleDoubleTap(vm, feedbackManager) ?: false
        }
        val actionId = when (detected?.brand) {
            StylusBrand.SAMSUNG_SPEN -> vm.samsungDoubleClickAction
            else -> vm.oppoDoubleTapAction
        }
        if (actionId.trim().equals("none", ignoreCase = true)) return false
        val action = StylusAction.fromActionId(actionId)
        if (action != StylusAction.NONE) {
            feedbackManager.triggerActionConfirmation()
            vm.executeStylusAction(action)
            return true
        }
        return false
    }

    fun handleSingleClick(): Boolean {
        val detected = detectDevices().firstOrNull()
        if (detected?.brand == StylusBrand.HUAWEI_MPENCIL) {
            return getAdapter<HuaweiStylusAdapter>()?.handleSingleClick(vm, feedbackManager) ?: false
        }
        val actionId = when (detected?.brand) {
            StylusBrand.SAMSUNG_SPEN -> vm.samsungSingleClickAction
            else -> vm.oppoDoubleTapAction
        }
        if (actionId.trim().equals("none", ignoreCase = true)) return false
        val action = StylusAction.fromActionId(actionId)
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
