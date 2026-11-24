/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** 备份分类使用真实偏好键；品牌设置并不使用旧的 stylus_ 前缀。 */
object BackupPreferenceKeys {
    private val stylusKeys = setOf(
        "penOnlyMode", "penModeSingleFingerPanEnabled", "penModeStylusEyedropperEnabled",
        "pressureCurvePreset", "pressureControlPoints", "pressure_curve",
        "speedSimulatedPressureEnabled", "speedPressureInfluence", "speedPressureFingerOnly",
        "stylusHapticsEnabled", "stylusHapticsIntensity",
        "stylusAudioEnabled", "stylusAudioVolume", "stylusAudioType",
        "stylusStrokePredictionEnabled", "stylusFrontBufferPreviewEnabled",
        "frontBufferPredictionEnabled", "stylusPredictionLastAlgorithm",
        "oppoPencilModelMode", "oppoDoubleTapAction", "oppoSlideAction",
        "oppoSlideSensitivity", "oppoInPenHapticsEnabled",
        "samsungSideButtonErase", "samsungSingleClickAction",
        "samsungDoubleClickAction", "samsungLongPressAction",
        "huaweiPencilModelMode", "huaweiDoubleTapAction", "huaweiSqueezeAction",
        "huaweiSingleClickAction", "huaweiLongPressAction", "huaweiSideButtonErase", "huaweiHapticsEnabled",
        "honorPencilModelMode", "honorDoubleTapAction", "honorSingleClickAction",
        "honorSideButtonErase", "honorHapticsEnabled",
        "xiaomiPencilModelMode", "xiaomiPrimaryButtonAction", "xiaomiSecondaryButtonAction",
        "xiaomiFocusButtonAction", "xiaomiDoubleTapAction", "xiaomiSideButtonErase",
        "xiaomiSqueezeAction", "xiaomiSlideAction", "xiaomiSlideSensitivity", "xiaomiInPenHapticsEnabled",
        "vivoPencilModelMode", "vivoDoubleTapAction", "vivoPrimaryClickAction", "vivoSecondaryClickAction",
        "vivoSideButtonErase", "vivoWritingVibrateEnabled",
        "genericStylusEnabled", "genericPrimaryButtonAction", "genericSecondaryButtonAction", "genericSideButtonErase",
    )

    // 保留旧备份的命名；不要按整个品牌前缀匹配，免得把未来无关配置也带入此模块。
    fun isStylus(key: String): Boolean = key in stylusKeys || key.startsWith("stylus_")

    fun isShortcutOrStylus(key: String): Boolean = key == "shortcut_bindings" || isStylus(key)
}
