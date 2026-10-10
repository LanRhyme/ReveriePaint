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
        HonorStylusAdapter(),
        SamsungStylusAdapter(),
        XiaomiStylusAdapter(),
        VivoStylusAdapter(),
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
        feedbackManager.audioType = vm.stylusAudioType
        feedbackManager.syncAudioConfig()
        feedbackManager.setWritingHapticsEnabled(false)

        adapters.forEach { it.syncSettings(vm, feedbackManager) }
    }

    fun onWindowFocusChanged(activity: android.app.Activity, hasFocus: Boolean) {
        adapters.forEach { it.onWindowFocusChanged(activity, hasFocus) }
    }

    @Volatile
    private var cachedPrimaryBrand: StylusBrand? = null
    var hasDedicatedActiveStylus: Boolean = false
        private set

    init {
        detectDevices()
    }

    fun onActivityResume(activity: android.app.Activity) {
        adapters.forEach { it.onActivityResume(activity) }
        detectDevices()
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

    fun detectHonorPencilModel(): HonorPencilModel {
        return getAdapter<HonorStylusAdapter>()?.detectModel(context) ?: HonorPencilModel.MAGIC_PENCIL_3
    }

    fun detectXiaomiPencilModel(): XiaomiPencilModel {
        return getAdapter<XiaomiStylusAdapter>()?.detectModel(context) ?: XiaomiPencilModel.SMART_PEN_2
    }

    fun detectVivoPencilModel(): VivoPencilModel {
        return getAdapter<VivoStylusAdapter>()?.detectModel(context) ?: VivoPencilModel.VIVO_PENCIL2
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
        val primaryDedicated = detected.firstOrNull {
            it.brand != StylusBrand.GENERIC && it.isCurrentDeviceSupported && it.isConnected
        } ?: detected.firstOrNull {
            it.brand != StylusBrand.GENERIC && it.isCurrentDeviceSupported
        }
        cachedPrimaryBrand = primaryDedicated?.brand ?: detected.firstOrNull()?.brand
        hasDedicatedActiveStylus = primaryDedicated != null
        return detected
    }

    /**
     * Retrieves the single active adapter for the current platform/hardware.
     * Prevents cross-brand event leakage (e.g. HUAWEI M-Pencil intercepting Samsung S Pen KeyEvents).
     */
    fun getActiveAdapter(): StylusBrandAdapter? {
        val brand = cachedPrimaryBrand ?: run {
            val d = detectDevices().firstOrNull()
            cachedPrimaryBrand = d?.brand
            d?.brand
        }
        if (hasDedicatedActiveStylus && brand != null && brand != StylusBrand.GENERIC) {
            return getAdapterForBrand(brand)
        }
        if (vm.genericStylusEnabled) {
            return getAdapterForBrand(StylusBrand.GENERIC)
        }
        return null
    }

    /**
     * Handles generic motion events (e.g. ACTION_SCROLL from stylus barrel slide).
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return getActiveAdapter()?.onGenericMotionEvent(event, vm, feedbackManager) ?: false
    }

    /**
     * Process stylus motion events for barrel slide and side-buttons.
     * Hot-path safe: does NOT invoke blocking vibrators or create allocations.
     */
    fun onStylusMotionEvent(event: MotionEvent): Boolean {
        if (onGenericMotionEvent(event)) return true
        return getActiveAdapter()?.onStylusMotionEvent(event, vm, feedbackManager) ?: false
    }

    /**
     * Notifies adapters that the stylus left the hover field, so button state
     * tracked across hover events (e.g. S Pen side button) is reset safely.
     */
    fun onStylusHoverExited() {
        getActiveAdapter()?.onStylusHoverExited(vm, feedbackManager)
    }

    /**
     * Samsung Notes standard semantics: holding the side button while the pen
     * touches down turns that stroke into a temporary eraser stroke.
     * Hot-path safe: two bitmask reads, no allocation.
     */
    fun isSideButtonEraseActive(event: MotionEvent): Boolean {
        val brand = cachedPrimaryBrand ?: run {
            val d = detectDevices().firstOrNull()
            cachedPrimaryBrand = d?.brand
            d?.brand
        }
        val eraseAllowed = when (brand) {
            StylusBrand.HUAWEI_MPENCIL -> vm.huaweiSideButtonErase
            StylusBrand.HONOR_MAGIC_PENCIL -> vm.honorSideButtonErase
            StylusBrand.SAMSUNG_SPEN -> vm.samsungSideButtonErase
            StylusBrand.XIAOMI_STYLUS -> vm.xiaomiSideButtonErase
            StylusBrand.VIVO_PENCIL -> vm.vivoSideButtonErase
            StylusBrand.GENERIC -> vm.genericStylusEnabled && vm.genericSideButtonErase
            else -> vm.huaweiSideButtonErase || vm.honorSideButtonErase || vm.samsungSideButtonErase || vm.xiaomiSideButtonErase || vm.vivoSideButtonErase || (vm.genericStylusEnabled && vm.genericSideButtonErase)
        }
        if (!eraseAllowed) return false
        val activeAdapter = getActiveAdapter()
        if (activeAdapter != null && activeAdapter.isSideButtonPressed) {
            return true
        }
        val btn = event.buttonState
        return (btn and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (btn and MotionEvent.BUTTON_PRIMARY) != 0 ||
                (btn and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                (btn and MotionEvent.BUTTON_SECONDARY) != 0
    }

    /**
     * Handle physical/bluetooth stylus key events.
     * Prevents normal hardware keyboards from being intercepted and swallowed by stylus adapters.
     */
    fun onStylusKeyEvent(event: KeyEvent): Boolean {
        if (!isStylusKeyEvent(event)) {
            return false
        }
        return getActiveAdapter()?.onStylusKeyEvent(event, vm, feedbackManager) ?: false
    }

    private fun isStylusKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL ||
            keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_2 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_3 ||
            keyCode == 304 || keyCode == 305 ||
            keyCode == 308 || keyCode == 309 || keyCode == 310
        ) {
            return true
        }

        // 检查输入设备是否为手写笔或蓝牙/星闪笔类
        val src = event.source
        if ((src and android.view.InputDevice.SOURCE_STYLUS) != 0 ||
            (src and android.view.InputDevice.SOURCE_BLUETOOTH_STYLUS) != 0
        ) {
            return true
        }

        val dev = event.device ?: if (event.deviceId > 0) android.view.InputDevice.getDevice(event.deviceId) else null
        if (dev != null) {
            val devSources = dev.sources
            if ((devSources and android.view.InputDevice.SOURCE_STYLUS) != 0 ||
                (devSources and android.view.InputDevice.SOURCE_BLUETOOTH_STYLUS) != 0
            ) {
                return true
            }
            val name = dev.name.lowercase()
            if (name.contains("stylus") || name.contains("pen") || name.contains("pencil") ||
                name.contains("focus") || name.contains("nearlink") || name.contains("starflash") ||
                name.contains("星闪") || name.contains("cd-mp") || name.contains("mp0")
            ) {
                return true
            }
        }

        // 部分厂商 (如华为星闪/蓝牙双击或轻捏) 将快捷键映射为 PAGE_UP / PAGE_DOWN / F19 / F20
        // 若当前激活了专用手写笔适配器且按键落在这些扩展快捷键范围，允许进入分发
        if (hasDedicatedActiveStylus && (
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == KeyEvent.KEYCODE_F19 ||
            keyCode == KeyEvent.KEYCODE_F20
        )) {
            return true
        }

        return false
    }

    fun handleDoubleTap(): Boolean {
        val detected = detectDevices().firstOrNull()
        if (detected?.brand == StylusBrand.HUAWEI_MPENCIL) {
            return getAdapter<HuaweiStylusAdapter>()?.handleDoubleTap(vm, feedbackManager) ?: false
        }
        val actionId = when (detected?.brand) {
            StylusBrand.HONOR_MAGIC_PENCIL -> vm.honorDoubleTapAction
            StylusBrand.SAMSUNG_SPEN -> vm.samsungDoubleClickAction
            StylusBrand.XIAOMI_STYLUS -> vm.xiaomiDoubleTapAction
            StylusBrand.OPPO_ONEPLUS -> vm.oppoDoubleTapAction
            StylusBrand.VIVO_PENCIL -> vm.vivoDoubleTapAction
            else -> "none"
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
            StylusBrand.HONOR_MAGIC_PENCIL -> vm.honorSingleClickAction
            StylusBrand.SAMSUNG_SPEN -> vm.samsungSingleClickAction
            StylusBrand.XIAOMI_STYLUS -> vm.xiaomiPrimaryButtonAction
            StylusBrand.VIVO_PENCIL -> vm.vivoPrimaryClickAction
            StylusBrand.GENERIC -> if (vm.genericStylusEnabled) vm.genericPrimaryButtonAction else "none"
            else -> "none"
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
