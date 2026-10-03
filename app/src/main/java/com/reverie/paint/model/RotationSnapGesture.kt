/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.sign

/** 旋转意图确认后锁定标准角度；越过释放阈值后，从锁定角连续恢复自由旋转。 */
class RotationSnapGesture {
    var rawDegrees = 0f
        private set
    var isActive = false
        private set
    var isSnapped = false
        private set

    private var appliedDegrees = 0f
    private var signedTravel = 0f
    private var lastThreshold = 0f
    private var targetDegrees = 0f
    private var releasedTarget = Float.NaN

    fun begin(rotation: Float) {
        rawDegrees = rotation
        appliedDegrees = rotation
        signedTravel = 0f
        lastThreshold = 0f
        isActive = false
        isSnapped = false
        releasedTarget = Float.NaN
    }

    fun update(delta: Float, threshold: Float): Float {
        if (!delta.isFinite()) return appliedDegrees
        val limit = if (threshold.isFinite()) threshold.coerceIn(0f, RotationSnap.MAX_THRESHOLD_DEGREES) else 0f
        if (limit <= 0f) {
            rawDegrees = appliedDegrees + delta
            appliedDegrees = rawDegrees
            signedTravel = 0f
            lastThreshold = 0f
            isActive = false
            isSnapped = false
            releasedTarget = Float.NaN
            return appliedDegrees
        }
        if (lastThreshold > 0f && limit != lastThreshold) {
            // 设置改变只重新建立参考，不在没有旋转输入时改变预览。
            rawDegrees = appliedDegrees
            isSnapped = false
            releasedTarget = RotationSnap.nearestMultiple(appliedDegrees)
        }
        lastThreshold = limit
        signedTravel += delta
        rawDegrees += delta
        if (!isActive) {
            isActive = abs(signedTravel) >= ENGAGE_DEGREES
            if (!isActive) {
                appliedDegrees = rawDegrees
                return appliedDegrees
            }
        }

        if (isSnapped) {
            val offset = rawDegrees - targetDegrees
            val releaseLimit = limit * RELEASE_RATIO
            if (abs(offset) <= releaseLimit) return appliedDegrees
            // 消耗锁定期间的角位移，只把越过释放边界的部分交给画布。
            appliedDegrees = targetDegrees + sign(offset) * (abs(offset) - releaseLimit)
            rawDegrees = appliedDegrees
            isSnapped = false
            releasedTarget = targetDegrees
            return appliedDegrees
        }

        val target = RotationSnap.nearestMultiple(rawDegrees)
        val blocked = releasedTarget
        if (blocked.isFinite()) {
            // 释放后不要下一帧立即吸回；离开捕获区或反向穿过目标后才能重新吸附。
            val crossesTarget = delta != 0f &&
                (appliedDegrees - blocked) * (rawDegrees - blocked) <= 0f
            if (abs(rawDegrees - blocked) > limit || crossesTarget) releasedTarget = Float.NaN
        }
        if (delta != 0f && target != releasedTarget && abs(rawDegrees - target) <= limit) {
            targetDegrees = target
            appliedDegrees = target
            isSnapped = true
        } else {
            appliedDegrees = rawDegrees
        }
        return appliedDegrees
    }

    companion object {
        const val ENGAGE_DEGREES = 0.6f
        private const val RELEASE_RATIO = 1.15f
    }
}
