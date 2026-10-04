/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context

internal const val ANIMATION_MANUAL_KEYFRAMES_PREF = "animationManualKeyframes"

/** 作画方式只控制下一次落笔，不改写文档、已有曝光或洋葱皮配置。 */
internal fun PaintViewModel.animationSetManualKeyframes(enabled: Boolean) {
    anim.manualKeyframes = enabled
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean(ANIMATION_MANUAL_KEYFRAMES_PREF, enabled).apply()
}

/** Krita 的洋葱皮偏移 -1 指上一关键帧，不是播放头前一格。 */
private val AnimationState.hasPreviousFrameReferenceConfig: Boolean
    get() = onionPrev == 1 && onionNext == 0 && !onionKeyframesOnly

internal val AnimationState.previousFrameReference: Boolean
    get() = onionSkin && hasPreviousFrameReferenceConfig

internal fun AnimationState.setPreviousFrameReference(enabled: Boolean) {
    if (enabled && !hasPreviousFrameReferenceConfig) {
        onionPrev = 1
        onionNext = 0
        onionKeyframesOnly = false
        onionOpacity = 96
    }
    // 关闭再开启参考时保留用户调过的透明度和着色。
    onionSkin = enabled
}

internal fun PaintViewModel.animationSetPreviousFrameReference(enabled: Boolean) {
    anim.setPreviousFrameReference(enabled)
    animationApplyOnionSkin()
}
