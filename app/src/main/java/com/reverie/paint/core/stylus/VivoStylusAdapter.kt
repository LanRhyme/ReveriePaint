/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.app.Activity
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.reverie.paint.core.PaintViewModel
import java.lang.ref.WeakReference
import com.reverie.paint.core.executeStylusAction
import com.reverie.paint.core.vivoPencilModel

/**
 * Dedicated Stylus Adapter for vivo Pencil / iQOO Pencil series (vivo Pad / iQOO Pad).
 *
 * Dynamically resolves vivo stylus penengine service via reflection on vivo ROMs:
 *  - 双击切换 / 按键切换: VivoStylusGestureManagerImpl binder callbacks (gestureType 2 = 双击主键/笔身,
 *    3 = 单击主键, 4 = 单击副键), 返回值语义遵循官方规范: 触发业务逻辑才返回 true
 *  - 书写振动: VivoStylusManagerImpl.enableWritingVibrate, 经 [StylusFeedbackManager.vivoWritingVibrateHook]
 *    挂在既有落笔/抬笔调用点上 (受系统「书写振动」总开关与强度等级门控)
 *
 * 非 vivo/iQOO 设备或系统服务未内置时本适配器零副作用: 不注册回调、不绑定系统服务。
 */
class VivoStylusAdapter : StylusBrandAdapter {
    override val brand: StylusBrand = StylusBrand.VIVO_PENCIL

    companion object {
        private const val TAG = "ReverieVivoStylus"

        /** 手势回调类型 (官方文档《手写笔SDK 2.0接入指南》§二.2) */
        const val GESTURE_DOUBLE_CLICK = 2
        const val GESTURE_PRIMARY_CLICK = 3
        const val GESTURE_SECONDARY_CLICK = 4

        /**
         * 当前设备是否为 vivo / iQOO
         */
        fun isVivoDeviceSupported(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brandName = Build.BRAND.lowercase()
            return manufacturer.contains("vivo") || brandName.contains("vivo") ||
                manufacturer.contains("iqoo") || brandName.contains("iqoo")
        }
    }

    private var currentVm: PaintViewModel? = null
    private var currentFeedbackManager: StylusFeedbackManager? = null

    private var gestureManager: Any? = null
    private var gestureCallbackProxy: Any? = null
    private var unregisterGestureCallbackMethod: java.lang.reflect.Method? = null
    private var registerLifecycleMethod: java.lang.reflect.Method? = null
    private var unregisterLifecycleMethod: java.lang.reflect.Method? = null

    /** 书写振动管理器 (VivoStylusManagerImpl), 惰性 init (官方要求 init 后才能 enableWritingVibrate) */
    private var stylusManager: Any? = null
    private var enableWritingVibrateMethod: java.lang.reflect.Method? = null
    private var destroyStylusManagerMethod: java.lang.reflect.Method? = null
    private var writingVibrateActive = false
    private var vibrationHookAttached = false

    /** registerLifecycle 以 Activity 实例为单位注册, 用弱引用避免持有已销毁 Activity */
    private var lifecycleActivityRef: WeakReference<Activity>? = null

    override fun register(
        context: Context,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ) {
        val appCtx = context.applicationContext
        currentVm = vm
        currentFeedbackManager = feedbackManager
        if (!isVivoDeviceSupported()) return

        // 书写振动钩子: 挂在 StylusFeedbackManager 既有落笔/抬笔调用点 (必须在 OPPO 系 early-return 之前生效)
        feedbackManager.vivoWritingVibrateHook = { enabled -> applyWritingVibrate(enabled) }
        vibrationHookAttached = true

        try {
            val gmClass = Class.forName("com.vivo.penengine.impl.VivoStylusGestureManagerImpl")
            val getInstanceMethod = gmClass.getMethod("getInstance", Context::class.java)
            val manager = getInstanceMethod.invoke(null, appCtx)
            gestureManager = manager

            val cbInterface = Class.forName("com.vivo.penengine.impl.VivoStylusGestureManagerImpl\$OnGestureCallback")
            val callback = gestureCallbackProxy ?: run {
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    cbInterface.classLoader,
                    arrayOf(cbInterface)
                ) { _, method, args ->
                    if (method.name == "onGesture" && args != null && args.isNotEmpty()) {
                        handleGesture((args[0] as? Number)?.toInt() ?: 0)
                    } else {
                        false
                    }
                }
                gestureCallbackProxy = proxy
                proxy
            }
            val regMethod = gmClass.getMethod("registerGestureCallback", cbInterface)
            regMethod.invoke(manager, callback)

            unregisterGestureCallbackMethod = try {
                gmClass.getMethod("unregisterGestureCallback", cbInterface)
            } catch (_: Throwable) { null }
            registerLifecycleMethod = try {
                gmClass.getMethod("registerLifecycle", Activity::class.java)
            } catch (_: Throwable) { null }
            unregisterLifecycleMethod = try {
                gmClass.getMethod("unregisterLifecycle", Activity::class.java)
            } catch (_: Throwable) { null }
        } catch (t: Throwable) {
            Log.w(TAG, "vivo 手势管理器注册失败 (非 vivo 平板或服务缺失): ${t.message}")
        }

        try {
            // init 仅登记回调 + 置位初始化标记; 真正的振动开关由 enableWritingVibrate 逐笔驱动
            val smClass = Class.forName("com.vivo.penengine.impl.VivoStylusManagerImpl")
            val smGetInstance = smClass.getMethod("getInstance", Context::class.java)
            val sm = smGetInstance.invoke(null, appCtx)
            val smInit = smClass.getMethod("init")
            smInit.invoke(sm)
            stylusManager = sm
            enableWritingVibrateMethod = smClass.getMethod("enableWritingVibrate", Boolean::class.javaPrimitiveType)
            destroyStylusManagerMethod = try {
                smClass.getMethod("destroy")
            } catch (_: Throwable) { null }
        } catch (t: Throwable) {
            Log.w(TAG, "vivo 书写振动管理器初始化失败: ${t.message}")
        }
    }

    override fun onActivityResume(activity: Activity) {
        if (!isVivoDeviceSupported()) return
        val manager = gestureManager ?: return
        val regMethod = registerLifecycleMethod ?: return
        // 同一 Activity 实例只注册一次 (SDK 内部以 Application.ActivityLifecycleCallbacks 跟踪前台,
        // 重复注册会叠加回调); 换实例时先撤旧再注新
        val registered = lifecycleActivityRef?.get()
        if (registered === activity) return
        if (registered != null) {
            try {
                unregisterLifecycleMethod?.invoke(manager, registered)
            } catch (_: Throwable) {}
        }
        try {
            regMethod.invoke(manager, activity)
            lifecycleActivityRef = WeakReference(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "vivo registerLifecycle 失败: ${t.message}")
        }
    }

    override fun onActivityPause(activity: Activity) {
        applyWritingVibrate(false)
    }

    /**
     * 手势回调 (binder 线程之外的 SDK 主线程派发)。
     * 返回值语义 (官方文档): 触发了业务逻辑返回 true; 未触发 (如配置为无操作/应用不在前台) 返回 false。
     */
    private fun handleGesture(gestureType: Int): Boolean {
        val vm = currentVm ?: return false
        val actionId = when (gestureType) {
            GESTURE_DOUBLE_CLICK -> vm.vivoDoubleTapAction
            GESTURE_PRIMARY_CLICK -> vm.vivoPrimaryClickAction
            GESTURE_SECONDARY_CLICK -> vm.vivoSecondaryClickAction
            else -> return false
        }
        if (actionId.isBlank() || actionId.equals("none", ignoreCase = true)) return false
        if (!vm.isAppForeground) return false
        val action = StylusAction.fromActionId(actionId)
        if (action == StylusAction.NONE) return false
        Log.i(TAG, "vivo 手势 $gestureType 触发动作: ${action.name} (actionId=$actionId)")
        currentFeedbackManager?.triggerActionConfirmation()
        vm.executeStylusAction(action)
        return true
    }

    /**
     * 书写振动边沿驱动 (落笔开 / 抬笔关), 内部做状态去重 —— StylusFeedbackManager 的落笔调用点
     * 在 move 热路径上会被反复触发, 去重后每次书写仅 2 次 binder 调用。
     */
    private fun applyWritingVibrate(enabled: Boolean) {
        val vm = currentVm ?: return
        val desired = enabled &&
            vm.vivoWritingVibrateEnabled &&
            vm.stylusHapticsEnabled &&
            vm.vivoPencilModel.hasWritingVibrate
        if (desired == writingVibrateActive) return
        writingVibrateActive = desired
        val manager = stylusManager ?: return
        val method = enableWritingVibrateMethod ?: return
        try {
            method.invoke(manager, desired)
        } catch (t: Throwable) {
            Log.w(TAG, "vivo 书写振动切换失败: ${t.message}")
        }
    }

    override fun syncSettings(vm: PaintViewModel, feedbackManager: StylusFeedbackManager) {
        // 用户关闭书写振动 / 全局震动时, 立即停掉可能在振的笔身 (destroy 不会自动停振, 必须显式关闭)
        if (writingVibrateActive && (!vm.vivoWritingVibrateEnabled || !vm.stylusHapticsEnabled)) {
            applyWritingVibrate(false)
        }
    }

    override fun detect(context: Context, vm: PaintViewModel): StylusDeviceDetected? {
        val isVivoDevice = isVivoDeviceSupported()

        var stylusConnected = false
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            if (inputManager != null) {
                for (id in inputManager.inputDeviceIds) {
                    val dev = inputManager.getInputDevice(id) ?: continue
                    val name = dev.name.lowercase()
                    if (name.contains("vivo pencil") || name.contains("vivo pen") ||
                        name.contains("iqoo pencil") || name.contains("iqoo pen") ||
                        ((name.contains("pencil") || name.contains("stylus")) && name.contains("vivo"))
                    ) {
                        stylusConnected = true
                        break
                    }
                }
            }
        } catch (_: Throwable) {}

        val model = detectModel(context)
        return StylusDeviceDetected(
            brand = StylusBrand.VIVO_PENCIL,
            isCurrentDeviceSupported = isVivoDevice,
            isConnected = stylusConnected || isVivoDevice,
            deviceName = if (isVivoDevice) {
                "${model.displayName} (${Build.MODEL})"
            } else {
                model.displayName
            },
        )
    }

    /**
     * 型号自动识别: 优先扫描输入设备名, 其次按平板型号代号回退。
     * 官方文档未给出「平板 ↔ 笔」的对应关系, 识别不确定时回退 [VivoPencilModel.VIVO_PENCIL2]
     * (文档主推型号), 用户可在设置弹窗手动覆盖。
     */
    fun detectModel(context: Context): VivoPencilModel {
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            if (inputManager != null) {
                for (id in inputManager.inputDeviceIds) {
                    val dev = inputManager.getInputDevice(id) ?: continue
                    val name = dev.name.lowercase()
                    if (!(name.contains("pencil") || name.contains("pen") || name.contains("stylus"))) continue
                    if (!name.contains("vivo") && !name.contains("iqoo")) continue
                    return when {
                        name.contains("nv") -> VivoPencilModel.VIVO_PENCIL2_NV
                        name.contains("2s") || name.contains("air") -> VivoPencilModel.VIVO_PENCIL2S
                        name.contains("pencil 3") || name.contains("pencil3") || name.contains("gen3") ->
                            VivoPencilModel.VIVO_PENCIL3
                        name.contains("pencil 2") || name.contains("pencil2") -> VivoPencilModel.VIVO_PENCIL2
                        else -> VivoPencilModel.VIVO_PENCIL2
                    }
                }
            }
        } catch (_: Throwable) {}

        val model = Build.MODEL.uppercase()
        return when {
            model.contains("IQOO") && (model.contains("PAD AIR") || model.contains("AIR")) ->
                VivoPencilModel.VIVO_PENCIL2S
            // Pad2 为 Pencil2 主推机型; Pad3/Pad5 世代同样以 Pencil2 系为主流搭配
            else -> VivoPencilModel.VIVO_PENCIL2
        }
    }

    override fun onStylusMotionEvent(
        event: MotionEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        // 双击/按键手势全部经 SDK binder 回调 (handleGesture), 不走 MotionEvent 状态机;
        // 按住按键临时橡皮由 StylusDriver.isSideButtonEraseActive 在画布层统一处理。
        return false
    }

    override fun onStylusKeyEvent(
        event: KeyEvent,
        vm: PaintViewModel,
        feedbackManager: StylusFeedbackManager,
    ): Boolean {
        // vivo 侧按键手势由 SDK 回调覆盖; 硬件 KeyEvent 不在此消费, 避免与手势回调双重触发
        return false
    }

    override fun unregister(context: Context) {
        cleanup()
    }

    override fun release() {
        cleanup()
    }

    /** 释放全部 SDK 资源 (unregister / release 双入口, 幂等) */
    private fun cleanup() {
        // destroy 不会停止书写振动, 必须先显式关闭再注销 (官方文档要求振动必须有开有关)
        val sm = stylusManager
        if (sm != null) {
            enableWritingVibrateMethod?.let {
                try {
                    it.invoke(sm, false)
                } catch (_: Throwable) {}
            }
            writingVibrateActive = false
            destroyStylusManagerMethod?.let {
                try {
                    it.invoke(sm)
                } catch (_: Throwable) {}
            }
        }
        stylusManager = null
        enableWritingVibrateMethod = null
        destroyStylusManagerMethod = null

        val gm = gestureManager
        if (gm != null) {
            val cb = gestureCallbackProxy
            if (cb != null) {
                unregisterGestureCallbackMethod?.let {
                    try {
                        it.invoke(gm, cb)
                    } catch (_: Throwable) {}
                }
            }
            val act = lifecycleActivityRef?.get()
            if (act != null) {
                unregisterLifecycleMethod?.let {
                    try {
                        it.invoke(gm, act)
                    } catch (_: Throwable) {}
                }
            }
        }
        lifecycleActivityRef = null
        gestureCallbackProxy = null
        gestureManager = null
        unregisterGestureCallbackMethod = null
        registerLifecycleMethod = null
        unregisterLifecycleMethod = null

        if (vibrationHookAttached) {
            currentFeedbackManager?.vivoWritingVibrateHook = null
            vibrationHookAttached = false
        }
        currentVm = null
        currentFeedbackManager = null
    }
}
