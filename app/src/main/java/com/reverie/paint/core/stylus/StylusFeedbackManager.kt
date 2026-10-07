/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

import java.io.File

/**
 * Manages stylus tactile haptic feedback for action confirmation (double tap, slide, button clicks)
 * and ColorOS touchpanel hardware in-pen vibration (/proc/touchpanel/pencil_control).
 * Strictly guarantees zero-allocation and zero-blocking on touchmove drawing paths.
 */
class StylusFeedbackManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Configuration mirrors
    var hapticsEnabled: Boolean = true
    var hapticsIntensity: Float = 0.5f
    var inPenHaptics: Boolean = true
    var audioEnabled: Boolean = false
    var audioVolume: Float = 0.6f
    var audioType: StylusAudioType = StylusAudioType.PENCIL

    val ocsClient: OppoOcsStylusClient = OppoOcsStylusClient.getInstance(context)
    private var isWritingHapticsActive = false

    /** Procedural paper-friction writing sound (see [PaperSoundEngine]). */
    val paperSound = PaperSoundEngine(context.applicationContext)

    init {
        paperSound.start()
    }

    /** Sync audio config from StylusDriver.syncSettings mirrors. */
    fun syncAudioConfig() {
        paperSound.configure(audioEnabled, audioVolume, audioType)
    }

    /**
     * 场景门控: 是否允许纸张音效保持 AudioTrack 管线预热。
     * 只有绘画页且应用在前台才需要; 其余场景常驻的静音输出会被系统判为
     * "应用在静音播放媒体"并计入耗电 (见 [PaperSoundEngine.setActive])。
     */
    fun setAudioActive(active: Boolean) {
        paperSound.setActive(active)
    }

    /**
     * Begin paper friction sound at stroke start (non-blocking, zero allocation).
     * @param isEraser slightly quiets the texture for eraser strokes.
     */
    fun startStrokeSound(isEraser: Boolean, initialPressure: Float = 0.5f) {
        paperSound.startStroke(isEraser, initialPressure)
    }

    /**
     * Update friction loudness from stroke speed (px/ms in document space) and pressure.
     * Non-blocking, zero allocation: single volatile write.
     */
    fun updateStrokeSound(speedPxPerMs: Float, pressure: Float = 0.5f) {
        paperSound.updateStroke(speedPxPerMs, pressure)
    }

    /** Fade out friction sound on stroke end / cancel. */
    fun stopStrokeSound() {
        paperSound.stopStroke()
    }

    /**
     * vivo 笔身书写振动钩子 (由 VivoStylusAdapter 注册; 内部自行处理型号能力与用户开关门控)。
     * 必须在下方的 OPPO 系 early-return 之前调用 —— vivo 设备上 inPenHaptics 恒为 false,
     * 若放到分支内会被直接跳过。调用点只有落笔/抬笔, 不在每帧热路径上。
     */
    var vivoWritingVibrateHook: ((Boolean) -> Unit)? = null

    /**
     * Controls physical in-pen haptic micro-vibrations via ColorOS OCS AIDL.
     * When enabled on touchdown, triggers continuous in-pen micro-vibration;
     * on pen lift, immediately stops the vibration.
     */
    fun setWritingHapticsEnabled(enabled: Boolean, isEraser: Boolean = false) {
        vivoWritingVibrateHook?.invoke(enabled)
        if (!hapticsEnabled || !inPenHaptics) {
            if (ocsClient.isAvailable) {
                ocsClient.stopFeedBackVibration()
            }
            isWritingHapticsActive = false
            return
        }

        if (enabled) {
            val vType = if (isEraser) OppoOcsStylusClient.VIBRATION_TYPE_ERASER else OppoOcsStylusClient.VIBRATION_TYPE_PENCIL
            // 仅 OPPO Pro 笔身硬件微震 (ColorOS OCS); 无硬件时静默 —— 书写过程不再触发
            // 平板马达震动 (S Pen 等设备笔身无马达, 用户已确认移除书写震动)
            if (ocsClient.isAvailable) {
                ocsClient.setVibrationType(vType)
                ocsClient.startFeedBackVibration()
            }
            isWritingHapticsActive = true
        } else {
            if (ocsClient.isAvailable) {
                ocsClient.stopFeedBackVibration()
            }
            isWritingHapticsActive = false
        }
    }

    /**
     * Trigger a single crisp haptic click for action confirmation (double tap, barrel slide, shortcuts).
     * Non-blocking and only executes when explicitly triggered by a user gesture.
     */
    fun triggerActionConfirmation() {
        if (!hapticsEnabled) return
        if (ocsClient.isAvailable) {
            ocsClient.startVibration(0)
        }
        try {
            val vib = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = (hapticsIntensity * 255).toInt().coerceIn(1, 255)
                vib.vibrate(VibrationEffect.createOneShot(25L, amp))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(25L)
            }
        } catch (_: Throwable) {}
    }

    fun release() {
        setWritingHapticsEnabled(false)
        paperSound.release()
        ocsClient.release()
    }
}
