/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 捏合判定阈值 (CanvasTouchView 多指手势): 判据必须与手指间距无关, 否则
 * 手指并拢的双指/三指轻点会被误判成捏合, 撤销/重做手势因此失效。
 *
 * 与 GesturePivotAndPersistenceTest 同样的约定: 这里复刻 CanvasTouchView
 * 中的判据公式, 用数值固定其语义。
 */
class PinchDetectionThresholdTest {

    private val density = 2.75f

    /** 修复前: 缩放用比例、旋转用绝对角度 —— 灵敏度随手指间距反比放大 */
    private fun pinchLikeOld(
        centroidMoved: Float,
        initialDistance: Float,
        distance: Float,
        angleDiffDeg: Float,
    ): Boolean =
        centroidMoved > 6f * density ||
            abs(distance / initialDistance - 1f) > 0.02f ||
            angleDiffDeg > 2f

    /** 修复后: 三个判据统一折算成"单指实际移动了多少像素" */
    private fun pinchLikeNew(
        centroidMoved: Float,
        initialDistance: Float,
        distance: Float,
        angleDiffDeg: Float,
    ): Boolean {
        val spreadMoved = abs(distance - initialDistance) * 0.5f
        val arcMoved = Math.toRadians(angleDiffDeg.toDouble()).toFloat() * initialDistance * 0.5f
        return centroidMoved > 6f * density ||
            spreadMoved > 3f * density ||
            arcMoved > 3f * density
    }

    @Test
    fun `close fingers - 1px spread jitter is no longer mistaken for a pinch`() {
        // 三指并拢轻点: 被跟踪的两指间距仅 40px, 落指抖动 1px
        assertTrue(pinchLikeOld(centroidMoved = 0f, initialDistance = 40f, distance = 41f, angleDiffDeg = 0f))
        assertFalse(pinchLikeNew(centroidMoved = 0f, initialDistance = 40f, distance = 41f, angleDiffDeg = 0f))
    }

    @Test
    fun `close fingers - small tangential wobble is no longer mistaken for a rotation`() {
        // 间距 40px 下 3° 相当于单指切向位移约 1px
        assertTrue(pinchLikeOld(centroidMoved = 0f, initialDistance = 40f, distance = 40f, angleDiffDeg = 3f))
        assertFalse(pinchLikeNew(centroidMoved = 0f, initialDistance = 40f, distance = 40f, angleDiffDeg = 3f))
    }

    @Test
    fun `spread fingers - a real pinch is still detected`() {
        assertTrue(pinchLikeOld(centroidMoved = 0f, initialDistance = 400f, distance = 340f, angleDiffDeg = 0f))
        assertTrue(pinchLikeNew(centroidMoved = 0f, initialDistance = 400f, distance = 340f, angleDiffDeg = 0f))
    }

    @Test
    fun `spread fingers - a steady two-finger tap is still not a pinch`() {
        assertFalse(pinchLikeOld(centroidMoved = 0f, initialDistance = 400f, distance = 404f, angleDiffDeg = 0f))
        assertFalse(pinchLikeNew(centroidMoved = 0f, initialDistance = 400f, distance = 404f, angleDiffDeg = 0f))
    }

    @Test
    fun `centroid translation criterion is unchanged`() {
        assertTrue(pinchLikeNew(centroidMoved = 7f * density, initialDistance = 400f, distance = 400f, angleDiffDeg = 0f))
        assertFalse(pinchLikeNew(centroidMoved = 5f * density, initialDistance = 400f, distance = 400f, angleDiffDeg = 0f))
    }
}
