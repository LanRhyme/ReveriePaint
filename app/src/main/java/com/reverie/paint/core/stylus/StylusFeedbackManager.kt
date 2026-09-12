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

    /**
     * Controls physical in-pen haptic micro-vibrations via ColorOS OCS AIDL.
     * When enabled on touchdown, triggers continuous in-pen micro-vibration;
     * on pen lift, immediately stops the vibration.
     */
    fun setWritingHapticsEnabled(enabled: Boolean, isEraser: Boolean = false) {
        if (!hapticsEnabled || !inPenHaptics) {
            if (ocsClient.isAvailable) {
                ocsClient.stopFeedBackVibration()
            }
            isWritingHapticsActive = false
            return
        }

        if (enabled) {
            val vType = if (isEraser) OppoOcsStylusClient.VIBRATION_TYPE_ERASER else OppoOcsStylusClient.VIBRATION_TYPE_PENCIL
            if (ocsClient.isAvailable) {
                ocsClient.setVibrationType(vType)
                ocsClient.startFeedBackVibration()
            } else {
                triggerStrokeStartTick()
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

    /**
     * Subtle tick feedback on stroke start when hardware in-pen haptics is not available.
     */
    fun triggerStrokeStartTick() {
        if (!hapticsEnabled) return
        if (isWritingHapticsActive) return // Hardware in-pen vibration already handling this
        try {
            val vib = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = (hapticsIntensity * 180).toInt().coerceIn(1, 255)
                vib.vibrate(VibrationEffect.createOneShot(12L, amp))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(12L)
            }
        } catch (_: Throwable) {}
    }

    fun release() {
        setWritingHapticsEnabled(false)
        ocsClient.release()
    }
}
